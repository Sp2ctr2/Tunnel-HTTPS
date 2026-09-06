package com.tunnelvpn.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class TurboClientPayloadBatch(capacity: Int) {
    val bytes = ByteArray(capacity)
    var size: Int = 0
        private set

    init {
        require(capacity > 0)
    }

    fun append(payload: ByteArray): Boolean {
        if (payload.size > bytes.size - size) return false
        payload.copyInto(bytes, size)
        size += payload.size
        return true
    }

    fun clear() {
        size = 0
    }
}

internal class TurboPayloadBudget(
    private val perFlowCapacity: Int,
    private val globalBytes: AtomicInteger,
    private val globalCapacity: Int
) {
    private val flowBytes = AtomicInteger()

    init {
        require(perFlowCapacity > 0)
        require(globalCapacity >= perFlowCapacity)
    }

    fun tryReserve(bytes: Int): Boolean {
        if (bytes <= 0 || bytes > perFlowCapacity) return false
        if (!tryAdd(globalBytes, globalCapacity, bytes)) return false
        if (tryAdd(flowBytes, perFlowCapacity, bytes)) return true
        globalBytes.addAndGet(-bytes)
        return false
    }

    fun release(bytes: Int): Int {
        require(bytes >= 0)
        if (bytes == 0) return availableBytes()
        subtract(flowBytes, bytes)
        subtract(globalBytes, bytes)
        return availableBytes()
    }

    fun availableBytes(): Int {
        val localAvailable = (perFlowCapacity - flowBytes.get()).coerceAtLeast(0)
        val globalAvailable = (globalCapacity - globalBytes.get()).coerceAtLeast(0)
        return minOf(localAvailable, globalAvailable)
    }

    fun retainedBytes(): Int = flowBytes.get()

    private fun tryAdd(counter: AtomicInteger, capacity: Int, bytes: Int): Boolean {
        while (true) {
            val current = counter.get()
            if (bytes > capacity - current) return false
            if (counter.compareAndSet(current, current + bytes)) return true
        }
    }

    private fun subtract(counter: AtomicInteger, bytes: Int) {
        while (true) {
            val current = counter.get()
            check(bytes <= current)
            if (counter.compareAndSet(current, current - bytes)) return
        }
    }
}

internal data class TurboTcpRetransmissionAction(
    val packet: ByteArray?,
    val retransmissionCount: Int,
    val nextRtoMs: Long,
    val exhausted: Boolean
)

internal data class TurboTcpPersistProbe(
    val sequence: Long,
    val value: Byte?,
    val packet: ByteArray?
)

internal class TurboTcpRetransmissionQueue(
    private val maxSegments: Int,
    perFlowCapacity: Int,
    globalBytes: AtomicInteger,
    globalCapacity: Int,
    private val initialRtoMs: Long,
    private val maxRtoMs: Long,
    private val maxRetransmissions: Int
) {
    private data class Segment(
        val sequence: Long,
        val endSequence: Long,
        val packet: ByteArray,
        val payloadOffset: Int,
        val payloadLength: Int,
        val hasSyn: Boolean,
        val hasFin: Boolean,
        var retransmissionCount: Int,
        var rtoMs: Long,
        var deadlineMs: Long
    )

    private val segments = ArrayDeque<Segment>()
    private val budget = TurboPayloadBudget(perFlowCapacity, globalBytes, globalCapacity)
    private var timeoutsPaused = false

    init {
        require(maxSegments > 0)
        require(initialRtoMs > 0L)
        require(maxRtoMs >= initialRtoMs)
        require(maxRetransmissions >= 0)
    }

    fun tryEnqueue(
        sequence: Long,
        sequenceLength: Int,
        packet: ByteArray,
        nowMs: Long,
        payloadOffset: Int = packet.size,
        payloadLength: Int = 0,
        hasSyn: Boolean = false,
        hasFin: Boolean = false
    ): Boolean {
        require(sequenceLength > 0)
        require(payloadOffset >= 0 && payloadLength >= 0 && payloadOffset + payloadLength <= packet.size)
        if (segments.size >= maxSegments || !budget.tryReserve(packet.size)) return false
        segments.addLast(
            Segment(
                sequence = sequence and TCP_UINT_MASK,
                endSequence = tcpSeqAdd(sequence, sequenceLength),
                packet = packet,
                payloadOffset = payloadOffset,
                payloadLength = payloadLength,
                hasSyn = hasSyn,
                hasFin = hasFin,
                retransmissionCount = 0,
                rtoMs = initialRtoMs,
                deadlineMs = nowMs + initialRtoMs
            )
        )
        return true
    }

    fun rollbackLast(sequence: Long): Boolean {
        val last = segments.peekLast() ?: return false
        if (last.sequence != (sequence and TCP_UINT_MASK)) return false
        segments.removeLast()
        budget.release(last.packet.size)
        return true
    }

    fun acknowledge(ackNumber: Long, nowMs: Long): Int {
        var released = 0
        while (true) {
            val first = segments.peekFirst() ?: break
            if (tcpSequenceCompare(ackNumber, first.endSequence) < 0) break
            segments.removeFirst()
            released += first.packet.size
        }
        if (released > 0) {
            budget.release(released)
            segments.peekFirst()?.let { it.deadlineMs = nowMs + it.rtoMs }
        }
        return released
    }

    fun pollTimeout(nowMs: Long): TurboTcpRetransmissionAction? {
        if (timeoutsPaused) return null
        val first = segments.peekFirst() ?: return null
        if (nowMs < first.deadlineMs) return null
        if (first.retransmissionCount >= maxRetransmissions) {
            return TurboTcpRetransmissionAction(
                packet = null,
                retransmissionCount = first.retransmissionCount,
                nextRtoMs = first.rtoMs,
                exhausted = true
            )
        }
        first.retransmissionCount += 1
        first.rtoMs = (first.rtoMs * 2L).coerceAtMost(maxRtoMs)
        first.deadlineMs = nowMs + first.rtoMs
        return TurboTcpRetransmissionAction(
            packet = first.packet,
            retransmissionCount = first.retransmissionCount,
            nextRtoMs = first.rtoMs,
            exhausted = false
        )
    }

    fun availablePacketBytes(): Int {
        return if (segments.size >= maxSegments) 0 else budget.availableBytes()
    }

    fun availableDataPacketBytes(reservedSegments: Int, reservedBytes: Int): Int {
        require(reservedSegments >= 0 && reservedBytes >= 0)
        return if (segments.size >= maxSegments - reservedSegments) {
            0
        } else {
            (budget.availableBytes() - reservedBytes).coerceAtLeast(0)
        }
    }

    fun pauseTimeouts() {
        timeoutsPaused = true
    }

    fun resumeTimeouts(nowMs: Long) {
        if (!timeoutsPaused) return
        timeoutsPaused = false
        segments.peekFirst()?.let { it.deadlineMs = nowMs + it.rtoMs }
    }

    fun persistProbe(sndUna: Long): TurboTcpPersistProbe? {
        val first = segments.peekFirst() ?: return null
        if (first.payloadLength > 0) {
            val payloadSequence = tcpSeqAdd(first.sequence, if (first.hasSyn) 1 else 0)
            val acknowledgedPayload = (sndUna - payloadSequence) and TCP_UINT_MASK
            if (acknowledgedPayload < first.payloadLength.toLong()) {
                return TurboTcpPersistProbe(
                    sequence = tcpSeqAdd(payloadSequence, acknowledgedPayload.toInt()),
                    value = first.packet[first.payloadOffset + acknowledgedPayload.toInt()],
                    packet = null
                )
            }
        }
        if (first.hasSyn || first.hasFin || first.payloadLength > 0) {
            return TurboTcpPersistProbe(first.sequence, null, first.packet)
        }
        return null
    }

    fun retainedBytes(): Int = budget.retainedBytes()

    fun size(): Int = segments.size

    fun clear(): Int {
        val released = budget.retainedBytes()
        segments.clear()
        timeoutsPaused = false
        if (released > 0) budget.release(released)
        return released
    }
}

internal class TurboTcpPersistTimer(
    private val initialDelayMs: Long,
    private val maxDelayMs: Long
) {
    private var delayMs = initialDelayMs
    private var deadlineMs: Long? = null

    init {
        require(initialDelayMs > 0L)
        require(maxDelayMs >= initialDelayMs)
    }

    fun millisUntilProbe(nowMs: Long): Long {
        val deadline = deadlineMs ?: (nowMs + delayMs).also { deadlineMs = it }
        return (deadline - nowMs).coerceAtLeast(0L)
    }

    fun onProbe(nowMs: Long): Long {
        delayMs = (delayMs * 2L).coerceAtMost(maxDelayMs)
        deadlineMs = nowMs + delayMs
        return delayMs
    }

    fun reset() {
        delayMs = initialDelayMs
        deadlineMs = null
    }

    fun isArmed(): Boolean = deadlineMs != null
}

private const val TCP_UINT_MASK = 0xffffffffL
private const val TCP_SEQUENCE_HALF = 0x80000000L

private fun tcpSeqAdd(sequence: Long, delta: Int): Long = (sequence + delta.toLong()) and TCP_UINT_MASK

private fun tcpSequenceCompare(left: Long, right: Long): Int {
    val delta = (left - right) and TCP_UINT_MASK
    return when {
        delta == 0L -> 0
        delta < TCP_SEQUENCE_HALF -> 1
        else -> -1
    }
}

internal enum class TurboTcpClosePhase {
    OPEN_OR_HALF_CLOSED,
    WAITING_FOR_FINAL_ACK,
    TIME_WAIT
}

internal enum class TlsServerHelloStatus {
    INCOMPLETE,
    VALID,
    INVALID
}

class TurboTcpForwarder(
    private val protectSocket: (Socket) -> Boolean,
    private val mapper: TurboDomainMapper,
    private val diagnostics: DiagnosticsState,
    private val scope: CoroutineScope,
    private val writeTunPacket: (ByteArray) -> Unit,
    private val directHttpsForwarding: Boolean = false,
    private val tlsFragmentationEnabled: Boolean = true,
    private val domainBlocker: DomainBlocker = DomainBlocker(enabled = false),
    private val latencyProfile: LatencyProfile = LatencyProfile.DEFAULT,
    private val turboAiRuntime: TurboAiRuntime? = null,
    private val clientHandshakeIdleTimeoutMs: Long = CLIENT_HANDSHAKE_IDLE_TIMEOUT_MS,
    private val httpsPort: Int = HTTPS_PORT,
    private val destinationTranslator: (InetAddress) -> InetAddress = { it },
    private val destinationCandidates: (InetAddress) -> List<InetAddress> = {
        listOf(destinationTranslator(it))
    },
    private val tcpDnsHandler: (suspend (ByteArray) -> ByteArray?)? = null,
    tunMtu: Int = DEFAULT_TUN_MTU
) {
    private val maxTunTcpPayloadV4 = (tunMtu - IPV4_HEADER_LENGTH - TCP_MIN_HEADER_LENGTH)
        .coerceIn(MIN_TCP_PAYLOAD, MAX_TCP_PAYLOAD)
    private val maxTunTcpPayloadV6 = (tunMtu - IPV6_HEADER_LENGTH - TCP_MIN_HEADER_LENGTH)
        .coerceIn(MIN_TCP_PAYLOAD, MAX_TCP_PAYLOAD)
    private val safeBurst = SafeBurstController(TCP_SOFT_LIMIT, MAX_CONNECTIONS, MAX_CONNECTIONS - TCP_SOFT_LIMIT)

    enum class LatencyProfile(
        val firstConnectionBudgetMs: Long,
        val timeWaitMs: Long,
        val fragmentInterByteDelayMs: Long
    ) {

        DEFAULT(firstConnectionBudgetMs = 9000L, timeWaitMs = 5000L, fragmentInterByteDelayMs = 0L),
        GAME(firstConnectionBudgetMs = 6000L, timeWaitMs = 1200L, fragmentInterByteDelayMs = 0L)
    }
    private class IpAddr(address: ByteArray) {
        val bytes = address.copyOf()
        private val hash = bytes.contentHashCode()
        val isV6: Boolean get() = bytes.size == IPV6_ADDRESS_LENGTH

        init {
            require(bytes.size == IPV4_ADDRESS_LENGTH || bytes.size == IPV6_ADDRESS_LENGTH)
        }

        override fun equals(other: Any?): Boolean =
            other is IpAddr && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = hash
    }

    private data class FlowKey(
        val clientIp: IpAddr,
        val clientPort: Int,
        val virtualIp: IpAddr,
        val destinationPort: Int
    )

    private data class TcpPacket(
        val sourceIp: IpAddr,
        val destinationIp: IpAddr,
        val sourcePort: Int,
        val destinationPort: Int,
        val sequence: Long,
        val ack: Long,
        val flags: Int,
        val window: Int,
        val mss: Int?,

        val windowScale: Int?,
        val payload: ByteArray
    )

    private data class TcpOptions(
        val mss: Int?,
        val windowScale: Int?
    )

    private data class TimeWaitTombstone(
        val key: FlowKey,
        val clientNext: Long,
        val serverNext: Long,
        val expiresAtMs: Long
    )

    private inner class Connection(
        val key: FlowKey,
        val lifecycleGeneration: Long,
        val mapping: TurboDomainMapper.Mapping,
        val destinationPort: Int,
        val isTls: Boolean,
        clientInitialSequence: Long,
        serverInitialSequence: Long,
        val clientWindow: Int,
        val clientMss: Int?,
        clientWindowScale: Int?
    ) {

        val windowScalingNegotiated = clientWindowScale != null
        val clientWindowShift = (clientWindowScale ?: 0).coerceIn(0, 14)
        val lock = Object()
        val clientInitial = clientInitialSequence and UINT_MASK
        var clientNext = seqAdd(clientInitial, 1)
        val serverInitial = serverInitialSequence and UINT_MASK
        @Volatile var serverNext = serverInitial

        @Volatile var sndUna = serverInitial
        @Volatile var sndWl1 = clientInitial
        @Volatile var sndWl2 = serverInitial
        @Volatile var advertisedWindow = clientWindow.coerceAtLeast(0)
        var lastTunDataByte: Byte = 0
        @Volatile var lastActivityMs = monotonicNowMs()
        var socket: Socket? = null
        var attemptSocket: Socket? = null
        @Volatile var closed = false
        var clientFinReceived = false
        var serverFinSent = false
        val timeWaitScheduled = AtomicBoolean(false)
        @Volatile var timeWaitJob: Job? = null
        private val familyMaxPayload = if (key.virtualIp.isV6) maxTunTcpPayloadV6 else maxTunTcpPayloadV4
        val maxPayload = (clientMss ?: familyMaxPayload).coerceIn(MIN_TCP_PAYLOAD, familyMaxPayload)
        val outboundLock = Mutex()
        val retransmissions = TurboTcpRetransmissionQueue(
            maxSegments = TUN_RETRANSMISSION_MAX_SEGMENTS,
            perFlowCapacity = TUN_RETRANSMISSION_BUFFER_BYTES,
            globalBytes = globalTunRetransmissionBytes,
            globalCapacity = GLOBAL_TUN_RETRANSMISSION_BUFFER_BYTES,
            initialRtoMs = INITIAL_RETRANSMISSION_RTO_MS,
            maxRtoMs = MAX_RETRANSMISSION_RTO_MS,
            maxRetransmissions = MAX_TUN_RETRANSMISSIONS
        )
        val persistTimer = TurboTcpPersistTimer(INITIAL_PERSIST_DELAY_MS, MAX_PERSIST_DELAY_MS)

        val clientPayloads = Channel<ByteArray>(CLIENT_PAYLOAD_QUEUE_CAPACITY)
        val clientPayloadBudget = TurboPayloadBudget(
            CLIENT_RELAY_BUFFER_BYTES,
            globalClientPayloadBytes,
            GLOBAL_CLIENT_RELAY_BUFFER_BYTES
        )
        @Volatile var lastAdvertisedReceiveWindow = DEFAULT_WINDOW
        val aiBytesUp = AtomicLong()
        val aiBytesDown = AtomicLong()
        val aiOutcomeReported = AtomicBoolean(false)
        val clientPayloadSeen = AtomicBoolean(false)
        @Volatile var clientHandshakeHandled = false
        @Volatile var handshakeDeadlineJob: Job? = null
        @Volatile var aiDecision: TurboDecision? = null
        @Volatile var aiSuccessfulStrategy: TurboStrategyId? = null
        @Volatile var aiSuccessfulPolicy: TurboDecisionPolicy? = null
        @Volatile var aiTlsResponseConfirmed: Boolean = false
        @Volatile var aiHandshakeLatencyMs: Long = 0L
        @Volatile var aiStartedElapsedMs: Long = 0L
        @Volatile var aiAttemptCount: Int = 0
        @Volatile var aiFallbackUsed: Boolean = false
        @Volatile var aiAbruptDisconnect: Boolean = false
        @Volatile var aiTerminalFailure: TurboFailureCategory = TurboFailureCategory.NONE

        fun touch() {
            lastActivityMs = monotonicNowMs()
        }
    }

    private val connections = ConcurrentHashMap<FlowKey, Connection>()
    private val timeWaitTombstones = ConcurrentHashMap<FlowKey, TimeWaitTombstone>()
    private val globalClientPayloadBytes = AtomicInteger()
    private val globalTunRetransmissionBytes = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val lifecycleGeneration = AtomicLong()
    private val suppressionNetworkGeneration = AtomicLong()
    private val serverSequenceSeed = AtomicInteger(0x5a510000)
    private val ioThreadCount = AtomicInteger()
    private val ioExecutor = ThreadPoolExecutor(
        0,
        MAX_CONNECTIONS * TCP_TASKS_PER_CONNECTION + TCP_IO_HEADROOM,
        TCP_THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable ->
            Thread(null, runnable, "TurboTcpIO-${ioThreadCount.incrementAndGet()}", TCP_THREAD_STACK_BYTES).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    private val ioDispatcher: CoroutineDispatcher = ioExecutor.asCoroutineDispatcher()

    private val successfulFragmentationModes: MutableMap<String, TlsClientHello.FragmentationMode> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, TlsClientHello.FragmentationMode>(256, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, TlsClientHello.FragmentationMode>?
                ): Boolean = size > FRAGMENTATION_CACHE_MAX
            }
        )
    private val fragmentationKeySalt = ByteArray(32).also(SecureRandom()::nextBytes)

    private data class FragmentationFailure(var count: Int, var lastFailureMs: Long)

    private val failedFragmentationModes: MutableMap<String, MutableMap<TlsClientHello.FragmentationMode, FragmentationFailure>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, MutableMap<TlsClientHello.FragmentationMode, FragmentationFailure>>(256, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, MutableMap<TlsClientHello.FragmentationMode, FragmentationFailure>>?
                ): Boolean = size > FRAGMENTATION_CACHE_MAX
            }
        )

    private val idleSweepJob = scope.launch {
        while (!closed.get()) {
            delay(CONNECTION_SWEEP_INTERVAL_MS)
            reapIdleConnections()
        }
    }

    private val retransmissionSweepJob = scope.launch {
        while (!closed.get()) {
            delay(RETRANSMISSION_SWEEP_INTERVAL_MS)
            processRetransmissions()
        }
    }

    fun handleIpv4Packet(packet: ByteArray, length: Int): Boolean = handlePacket(packet, length, expectIpv6 = false)

    fun handleIpv6Packet(packet: ByteArray, length: Int): Boolean = handlePacket(packet, length, expectIpv6 = true)

    private fun handlePacket(packet: ByteArray, length: Int, expectIpv6: Boolean): Boolean {
        if (closed.get()) return false
        val openingGeneration = lifecycleGeneration.get()
        val tcp = parseTcp(packet, length) ?: return false
        if (tcp.destinationIp.isV6 != expectIpv6) return false
        val destinationAddress = tcp.destinationIp.bytes
        val mapped = if (tcp.destinationIp.isV6) null else mapper.lookupVirtual(destinationAddress)
        if (mapped == null && isTurboVirtualIpv4(destinationAddress)) {
            diagnostics.recordTurboTcpPacketSeen()
            val key = FlowKey(tcp.sourceIp, tcp.sourcePort, tcp.destinationIp, tcp.destinationPort)
            if (tcp.flags and FLAG_RST == 0) writeTunPacket(buildStandaloneRst(key, tcp))
            diagnostics.recordTurboForwardingError("stale-virtual-address")
            return true
        }
        if (mapped == null && !directHttpsForwarding) return false
        diagnostics.recordTurboTcpPacketSeen()

        val key = FlowKey(tcp.sourceIp, tcp.sourcePort, tcp.destinationIp, tcp.destinationPort)
        if (tcp.flags and FLAG_SYN != 0 && tcp.flags and FLAG_ACK == 0) {
            val mapping = mapped ?: directMapping(destinationAddress)
            return handleInitialSyn(key, tcp, mapping, openingGeneration)
        }

        val connection = connections[key] ?: run {
            val tombstone = currentTimeWaitTombstone(key)
            if (tombstone != null) {
                handleTimeWaitPacket(tombstone, tcp)
                return true
            }
            if (tcp.flags and FLAG_ACK != 0 && tcp.flags and FLAG_SYN == 0) {
                writeTunPacket(buildStandaloneRst(key, tcp))
            }
            return true
        }
        connection.touch()

        if (tcp.flags and FLAG_RST != 0) {
            handleReset(connection, tcp)
            return true
        }

        if (tcp.flags and FLAG_ACK != 0) {
            val segmentLength = tcp.payload.size + if (tcp.flags and FLAG_FIN != 0) 1 else 0
            updateClientAck(connection, tcp.sequence, tcp.ack, tcp.window, segmentLength)
            if (connection.serverFinSent) maybeReap(connection)
        }

        if (tcp.flags and FLAG_FIN != 0) {
            if (tcp.payload.isNotEmpty()) {
                deliverClientPayload(connection, tcp.sequence, tcp.payload)
            }
            val finAccepted = synchronized(connection.lock) {
                if (connection.closed) return@synchronized false
                val finSequence = seqAdd(tcp.sequence, tcp.payload.size)
                if (!connection.clientFinReceived && sequenceCompare(finSequence, connection.clientNext) == 0) {
                    connection.clientNext = seqAdd(connection.clientNext, 1)
                    connection.clientFinReceived = true
                    true
                } else {
                    false
                }
            }
            writeTcpPacket(connection, FLAG_ACK, ByteArray(0))
            if (finAccepted) {
                connection.clientPayloads.close()
                maybeReap(connection)
            }
            return true
        }
        if (tcp.payload.isNotEmpty()) {
            deliverClientPayload(connection, tcp.sequence, tcp.payload)
            return true
        }
        return true
    }

    private fun handleInitialSyn(
        key: FlowKey,
        tcp: TcpPacket,
        mapping: TurboDomainMapper.Mapping,
        openingGeneration: Long
    ): Boolean {
        synchronized(lifecycleLock) {
            if (closed.get() || lifecycleGeneration.get() != openingGeneration) return true
            val existing = connections[key]
            if (existing != null && !isClosed(existing)) {
                val response = if (tcp.sequence == existing.clientInitial) {
                    buildSynAckResend(existing)
                } else {
                    buildChallengeAck(existing)
                }
                writeTunPacket(response)
                return true
            }
            if (existing != null) connections.remove(key, existing)

            val nowMs = monotonicNowMs()
            pruneExpiredTimeWaitsLocked(nowMs)
            val tombstone = timeWaitTombstones[key]
            if (tombstone != null && !isNewerIncarnationSyn(tcp.sequence, seqAdd(tombstone.clientNext, -1))) {
                diagnostics.recordTurboTupleReuseGuarded()
                writeTunPacket(buildTimeWaitAck(tombstone))
                return true
            }
            if (!safeBurst.admit(connections.size, currentResourcePressure())) {
                diagnostics.recordTurboCapacityRejected()
                writeTunPacket(buildStandaloneRst(key, tcp))
                diagnostics.recordTurboForwardingError("connection-capacity")
                return true
            }

            val previousServerLargest = tombstone?.let { seqAdd(it.serverNext, -1) }
            val connection = Connection(
                key = key,
                lifecycleGeneration = openingGeneration,
                mapping = mapping,
                destinationPort = tcp.destinationPort,
                isTls = tcp.destinationPort == httpsPort,
                clientInitialSequence = tcp.sequence,
                serverInitialSequence = nextServerSequence(previousServerLargest),
                clientWindow = tcp.window,
                clientMss = tcp.mss,
                clientWindowScale = tcp.windowScale
            )
            if (connections.putIfAbsent(key, connection) != null) return true
            diagnostics.recordTurboConnectionOpened()
            try {
                writeTcpPacket(connection, FLAG_SYN or FLAG_ACK, ByteArray(0))
            } catch (_: Exception) {
                diagnostics.recordTurboForwardingError("tun-synack-write")
                closeConnection(connection, sendReset = false)
                return true
            }
            if (tombstone != null && timeWaitTombstones.remove(key, tombstone)) {
                diagnostics.recordTurboTimeWaitRemoved()
                diagnostics.recordTurboTupleReuseAccepted()
            }
            scope.launch(ioDispatcher) { consumeClientPayloads(connection) }
            val handshakeDeadlineJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(clientHandshakeIdleTimeoutMs.coerceAtLeast(1L))
                val incomplete = if (connection.isTls) {
                    !connection.clientHandshakeHandled
                } else {
                    !connection.clientPayloadSeen.get()
                }
                if (incomplete && !isClosed(connection)) {
                    diagnostics.recordTurboForwardingError("client-handshake-idle")
                    closeConnection(connection, sendReset = true)
                }
            }
            val startDeadline = synchronized(connection.lock) {
                if (connection.closed) {
                    false
                } else {
                    connection.handshakeDeadlineJob = handshakeDeadlineJob
                    true
                }
            }
            if (startDeadline) handshakeDeadlineJob.start() else handshakeDeadlineJob.cancel()
            return true
        }
    }

    private fun currentTimeWaitTombstone(key: FlowKey): TimeWaitTombstone? {
        synchronized(lifecycleLock) {
            val tombstone = timeWaitTombstones[key] ?: return null
            if (tombstone.expiresAtMs > monotonicNowMs()) return tombstone
            if (timeWaitTombstones.remove(key, tombstone)) diagnostics.recordTurboTimeWaitRemoved()
            return null
        }
    }

    private fun handleTimeWaitPacket(tombstone: TimeWaitTombstone, tcp: TcpPacket) {
        synchronized(lifecycleLock) {
            if (connections.containsKey(tombstone.key)) return
            if (timeWaitTombstones[tombstone.key] !== tombstone) return
            if (tcp.flags and FLAG_RST != 0) return
            val terminalFin = tcp.flags and FLAG_FIN != 0 &&
                seqAdd(tcp.sequence, tcp.payload.size) == seqAdd(tombstone.clientNext, -1)
            val activeTombstone = if (terminalFin) {
                tombstone.copy(expiresAtMs = monotonicNowMs() + latencyProfile.timeWaitMs)
                    .also { timeWaitTombstones[tombstone.key] = it }
            } else {
                tombstone
            }
            writeTunPacket(buildTimeWaitAck(activeTombstone))
        }
    }

    private fun handleReset(connection: Connection, tcp: TcpPacket) {
        val accepted = synchronized(lifecycleLock) lifecycleLock@{
            if (connections[connection.key] !== connection) return@lifecycleLock false
            synchronized(connection.lock) connectionLock@{
                if (connection.closed) return@connectionLock false
                if (!isAcceptableReset(tcp.sequence, connection.clientNext)) {
                    writeTunPacket(buildChallengeAck(connection))
                    return@connectionLock false
                }
                connection.closed = true
                connections.remove(connection.key, connection)
                true
            }
        }
        if (accepted) finalizeConnectionClose(connection)
    }

    private fun deliverClientPayload(connection: Connection, sequence: Long, payload: ByteArray): Boolean {
        val delivered = synchronized(connection.lock) {
            if (connection.closed) return@synchronized false
            val sequenced = trimPayloadForExpectedSequence(connection.clientNext, sequence, payload)
            val accepted = sequenced?.second
            if (accepted != null && connection.clientPayloadBudget.tryReserve(accepted.size)) {
                if (!connection.clientPayloads.trySend(accepted).isSuccess) {
                    connection.clientPayloadBudget.release(accepted.size)
                    return@synchronized false
                }
                connection.clientPayloadSeen.set(true)
                connection.clientNext = sequenced.first
                true
            } else {
                false
            }
        }
        writeTcpPacket(connection, FLAG_ACK, ByteArray(0))
        if (!delivered && !isClosed(connection)) {
            diagnostics.recordTurboForwardingError("client-backpressure")
        }
        return delivered
    }

    private fun updateClientAck(
        connection: Connection,
        segmentSequence: Long,
        ackNumber: Long,
        window: Int,
        segmentLength: Int
    ) {
        synchronized(connection.lock) {
            if (connection.closed) return
            if (!isClientSegmentInReceiveWindow(
                    segmentSequence,
                    segmentLength,
                    connection.clientNext,
                    connection.lastAdvertisedReceiveWindow
                )
            ) return
            val nowMs = monotonicNowMs()
            val acceptableAck = isAckInSendRange(ackNumber, connection.sndUna, connection.serverNext)
            if (!acceptableAck) return
            if (sequenceCompare(ackNumber, connection.sndUna) > 0 &&
                sequenceCompare(ackNumber, connection.serverNext) <= 0
            ) {
                connection.sndUna = ackNumber
            }

            connection.retransmissions.acknowledge(ackNumber, nowMs)
            val newerWindowAdvertisement = shouldUpdateSendWindow(
                segmentSequence = segmentSequence,
                ackNumber = ackNumber,
                sndUna = connection.sndUna,
                sndNxt = connection.serverNext,
                sndWl1 = connection.sndWl1,
                sndWl2 = connection.sndWl2
            )
            if (newerWindowAdvertisement) {
                val scaledWindow =
                    if (connection.windowScalingNegotiated) window shl connection.clientWindowShift else window
                connection.advertisedWindow = scaledWindow.coerceAtLeast(0)
                connection.sndWl1 = segmentSequence
                connection.sndWl2 = ackNumber
                if (connection.advertisedWindow > 0) {
                    connection.persistTimer.reset()
                    connection.retransmissions.resumeTimeouts(nowMs)
                } else {
                    connection.retransmissions.pauseTimeouts()
                }
            }
            connection.lock.notifyAll()
        }
    }

    fun resetConnections() {
        if (closed.get()) return
        suppressionNetworkGeneration.incrementAndGet()
        val snapshot = synchronized(lifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            clearTimeWaitsLocked()
            snapshotConnections().filter { connection ->
                connection.aiAbruptDisconnect = true
                connection.aiTerminalFailure = TurboFailureCategory.NETWORK_CHANGED
                markConnectionClosedLocked(connection, sendReset = true)
            }
        }
        snapshot.forEach(::finalizeConnectionClose)
    }

    internal fun retainedClientPayloadBytes(): Int = globalClientPayloadBytes.get()

    internal fun retainedTunRetransmissionBytes(): Int = globalTunRetransmissionBytes.get()

    internal fun activeConnectionCount(): Int = connections.size

    internal fun timeWaitTombstoneCount(): Int = timeWaitTombstones.size

    fun closeAll() {
        if (!closed.compareAndSet(false, true)) return
        idleSweepJob.cancel()
        retransmissionSweepJob.cancel()
        val snapshot = synchronized(lifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            clearTimeWaitsLocked()
            snapshotConnections().filter { connection ->
                connection.aiTerminalFailure = TurboFailureCategory.CANCELLED
                markConnectionClosedLocked(connection, sendReset = false)
            }
        }
        snapshot.forEach(::finalizeConnectionClose)
        connections.clear()
        timeWaitTombstones.clear()
        runCatching { ioExecutor.shutdownNow() }
    }

    private fun snapshotConnections(): List<Connection> {
        val snapshot = ArrayList<Connection>(connections.size)
        connections.forEach { _, connection -> snapshot += connection }
        return snapshot
    }

    private fun clearTimeWaitsLocked() {
        val removed = timeWaitTombstones.size
        timeWaitTombstones.clear()
        repeat(removed) { diagnostics.recordTurboTimeWaitRemoved() }
    }

    private suspend fun consumeClientPayloads(connection: Connection) {
        var handshakeReservedBytes = 0
        try {
            if (connection.destinationPort == DNS_PORT && tcpDnsHandler != null) {
                consumeTcpDnsPayloads(connection, tcpDnsHandler)
                return
            }
            if (!connection.isTls || !tlsFragmentationEnabled) {
                val socket = ensureSocket(connection) ?: return
                val output = runCatching { socket.getOutputStream() }.getOrElse {
                    fail(connection, "socket-output")
                    return
                }
                relayClientPayloads(connection, output)
                return
            }

            val handshakeBuffer = ByteArrayOutputStream(4096)
            while (!isClosed(connection)) {
                val payload = connection.clientPayloads.receiveCatching().getOrNull() ?: return
                handshakeReservedBytes += payload.size
                handshakeBuffer.write(payload)
                val buffered = handshakeBuffer.toByteArray()
                val analysis = TlsClientHello.analyze(buffered, buffered.size)
                if (!analysis.complete && buffered.size < MAX_CLIENT_HELLO_BUFFER) continue
                try {
                    connection.clientHandshakeHandled = true
                    if (analysis.complete && analysis.clientHello && !analysis.malformed) {
                        diagnostics.recordTurboClientHello(analysis.sni != null)
                        val prefixEnd = analysis.clientHelloRecordEnd?.coerceIn(1, buffered.size)
                            ?: buffered.size
                        val retryMode = forwardClientHelloWithFallback(
                            connection,
                            buffered.copyOfRange(0, prefixEnd),
                            analysis,
                            buffered.copyOfRange(prefixEnd, buffered.size)
                        )
                        if (retryMode != null && !forwardRetryClientHello(connection, retryMode)) return
                    } else {
                        val socket = ensureSocket(connection) ?: return
                        val output = runCatching { socket.getOutputStream() }.getOrElse {
                            fail(connection, "socket-output")
                            return
                        }
                        writeSocketPayload(connection, output, buffered)
                    }
                } finally {
                    releaseClientPayloadBytes(connection, handshakeReservedBytes)
                    handshakeReservedBytes = 0
                }
                if (isClosed(connection)) return
                val socket = ensureSocket(connection) ?: return
                val output = runCatching { socket.getOutputStream() }.getOrElse {
                    fail(connection, "socket-output")
                    return
                }
                relayClientPayloads(connection, output)
                return
            }
        } finally {
            if (handshakeReservedBytes > 0) {
                releaseClientPayloadBytes(connection, handshakeReservedBytes)
            }
            halfCloseUpstream(connection)
        }
    }

    private suspend fun consumeTcpDnsPayloads(
        connection: Connection,
        handler: suspend (ByteArray) -> ByteArray?
    ) {
        var pending = ByteArray(0)
        while (!isClosed(connection)) {
            val payload = connection.clientPayloads.receiveCatching().getOrNull() ?: break
            try {
                if (pending.size + payload.size > MAX_TCP_DNS_BUFFER) {
                    fail(connection, "tcp-dns-buffer")
                    return
                }
                pending += payload
            } finally {
                releaseClientPayloadBytes(connection, payload.size)
            }
            while (pending.size >= TCP_DNS_PREFIX_SIZE && !isClosed(connection)) {
                val queryLength = ((pending[0].toInt() and 0xff) shl 8) or (pending[1].toInt() and 0xff)
                if (queryLength !in MIN_DNS_MESSAGE_SIZE..MAX_DNS_MESSAGE_SIZE) {
                    fail(connection, "tcp-dns-size")
                    return
                }
                val framedLength = TCP_DNS_PREFIX_SIZE + queryLength
                if (pending.size < framedLength) break
                val query = pending.copyOfRange(TCP_DNS_PREFIX_SIZE, framedLength)
                pending = pending.copyOfRange(framedLength, pending.size)
                val response = runCatching { handler(query) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    fail(connection, "tcp-dns-resolve")
                    return
                } ?: run {
                    fail(connection, "tcp-dns-query")
                    return
                }
                if (response.size !in MIN_DNS_MESSAGE_SIZE..MAX_DNS_MESSAGE_SIZE) {
                    fail(connection, "tcp-dns-response")
                    return
                }
                val framedResponse = ByteArray(TCP_DNS_PREFIX_SIZE + response.size)
                framedResponse[0] = (response.size ushr 8).toByte()
                framedResponse[1] = response.size.toByte()
                response.copyInto(framedResponse, TCP_DNS_PREFIX_SIZE)
                emitServerBytes(connection, framedResponse)
            }
        }
        if (pending.isNotEmpty() && !isClosed(connection)) {
            fail(connection, "tcp-dns-incomplete")
        } else if (!isClosed(connection)) {
            sendServerFin(connection)
        }
    }

    private suspend fun relayClientPayloads(connection: Connection, output: java.io.OutputStream) {
        val batch = TurboClientPayloadBatch(CLIENT_WRITE_BATCH_BYTES)
        var pending: ByteArray? = null
        try {
            while (!isClosed(connection)) {
                val first = pending ?: connection.clientPayloads.receiveCatching().getOrNull() ?: return
                pending = null
                if (!batch.append(first)) {
                    try {
                        writeSocketPayload(connection, output, first)
                    } finally {
                        releaseClientPayloadBytes(connection, first.size)
                    }
                    continue
                }
                while (batch.size < batch.bytes.size) {
                    val next = connection.clientPayloads.tryReceive().getOrNull() ?: break
                    if (!batch.append(next)) {
                        pending = next
                        break
                    }
                }
                val batchSize = batch.size
                try {
                    writeSocketPayload(connection, output, batch.bytes, 0, batchSize)
                } finally {
                    releaseClientPayloadBytes(connection, batchSize)
                }
                batch.clear()
            }
        } finally {
            pending?.let { releaseClientPayloadBytes(connection, it.size) }
        }
    }

    private fun releaseClientPayloadBytes(connection: Connection, bytes: Int) {
        val globalAvailableBefore = (GLOBAL_CLIENT_RELAY_BUFFER_BYTES - globalClientPayloadBytes.get()).coerceAtLeast(0)
        val available = connection.clientPayloadBudget.release(bytes)
        val shouldUpdate = synchronized(connection.lock) {
            !connection.closed && available - connection.lastAdvertisedReceiveWindow >= CLIENT_WINDOW_UPDATE_THRESHOLD
        }
        if (shouldUpdate) {
            runCatching { writeTcpPacket(connection, FLAG_ACK, ByteArray(0)) }
        }
        val globalAvailableAfter = (GLOBAL_CLIENT_RELAY_BUFFER_BYTES - globalClientPayloadBytes.get()).coerceAtLeast(0)
        if (globalAvailableBefore < CLIENT_WINDOW_UPDATE_THRESHOLD &&
            globalAvailableAfter >= CLIENT_WINDOW_UPDATE_THRESHOLD
        ) {
            reopenGloballyBlockedWindows(connection)
        }
    }

    private fun reopenGloballyBlockedWindows(releasingConnection: Connection) {
        if (closed.get()) return
        connections.values.forEach { candidate ->
            if (candidate === releasingConnection) return@forEach
            val shouldUpdate = synchronized(candidate.lock) {
                !candidate.closed &&
                    candidate.lastAdvertisedReceiveWindow < CLIENT_WINDOW_UPDATE_THRESHOLD &&
                    candidate.clientPayloadBudget.availableBytes() >= CLIENT_WINDOW_UPDATE_THRESHOLD
            }
            if (shouldUpdate) runCatching { writeTcpPacket(candidate, FLAG_ACK, ByteArray(0)) }
        }
    }

    private fun discardQueuedClientPayloads(connection: Connection) {
        while (true) {
            val payload = connection.clientPayloads.tryReceive().getOrNull() ?: return
            releaseClientPayloadBytes(connection, payload.size)
        }
    }

    private fun halfCloseUpstream(connection: Connection) {
        val socket = synchronized(connection.lock) { if (connection.closed) null else connection.socket }
        if (socket != null) {
            runCatching { if (!socket.isOutputShutdown && !socket.isClosed) socket.shutdownOutput() }
        }
    }

    private suspend fun forwardClientHelloWithFallback(
        connection: Connection,
        payload: ByteArray,
        analysis: TlsClientHello.Analysis,
        trailingPayload: ByteArray
    ): TlsClientHello.FragmentationMode? {
        val domain = analysis.sni ?: connection.mapping.domain

        if (domainBlocker.shouldBlock(domain)) {
            diagnostics.recordBlockedDns(domain)
            fail(connection, "adblock-sni")
            return null
        }
        val candidates = connection.mapping.realInetAddresses.ifEmpty { listOf(connection.mapping.realInetAddress) }
        val suppressionContext = buildString(64) {
            append(suppressionNetworkGeneration.get())
            append('|')
            append(turboAiRuntime?.suppressionContextKey() ?: FALLBACK_SUPPRESSION_CONTEXT)
        }
        val baselineModes = if (tlsFragmentationEnabled) {
            adaptiveModesForDomain(domain, suppressionContext)
        } else {
            listOf(TlsClientHello.FragmentationMode.NONE)
        }
        val decision = turboAiRuntime?.select(
            destination = domain,
            destinationPort = connection.destinationPort,
            analysis = analysis,
            baselineModes = baselineModes,
            resourcePressureBucket = resourcePressureBucket()
        )
        connection.aiDecision = decision
        connection.aiStartedElapsedMs = monotonicNowMs()
        val selectedModes = if (decision == null) {
            baselineModes
        } else {
            (listOf(decision.strategy) + decision.fallbackOrder)
                .map { it.fragmentationMode }
                .plus(baselineModes)
                .distinct()
                .filter { it in baselineModes }
                .ifEmpty { baselineModes }
        }
        val modes = prioritizePlainFallback(selectedModes)

        val budgetMs = latencyProfile.firstConnectionBudgetMs
        val deadlineMs = if (budgetMs > 0L) monotonicNowMs() + budgetMs else Long.MAX_VALUE
        var totalAttempts = 0
        for (address in candidates) {
            var addressReachable = true
            for ((modeIndex, mode) in modes.withIndex()) {
                if (!addressReachable) break
                if (totalAttempts >= MAX_TOTAL_HANDSHAKE_ATTEMPTS) {
                    diagnostics.recordTurboHandshakeBudgetExhausted()
                    fail(connection, "handshake-attempt-limit")
                    return null
                }
                val attemptStartedMs = monotonicNowMs()
                val remainingBudgetMs = deadlineMs - attemptStartedMs
                if (remainingBudgetMs <= 0L) {
                    diagnostics.recordTurboHandshakeBudgetExhausted()
                    fail(connection, "handshake-budget")
                    return null
                }
                val plainFallbackPending = mode != TlsClientHello.FragmentationMode.NONE &&
                    modes.drop(modeIndex + 1).contains(TlsClientHello.FragmentationMode.NONE)
                val reservedForPlainMs = if (plainFallbackPending) PLAIN_FALLBACK_RESERVE_MS else 0L
                val usableAttemptMs = remainingBudgetMs - reservedForPlainMs
                if (usableAttemptMs < MIN_HANDSHAKE_ATTEMPT_MS) continue
                val attemptBudgetMs = minOf(
                    usableAttemptMs,
                    if (mode == TlsClientHello.FragmentationMode.NONE) remainingBudgetMs else MAX_FRAGMENTED_ATTEMPT_MS
                )
                totalAttempts += 1
                connection.aiAttemptCount = totalAttempts
                val attemptDeadlineMs = attemptStartedMs + attemptBudgetMs
                val plan = TlsClientHello.fragmentationPlan(payload, payload.size, mode) ?: continue
                val socket = openUpstreamSocket(
                    connection,
                    address,
                    minOf(CONNECT_TIMEOUT_MS.toLong(), attemptBudgetMs).coerceAtLeast(1L).toInt()
                )
                if (socket == null) {
                    recordFailedAiAttempt(
                        connection,
                        mode,
                        TurboFailureCategory.CONNECT,
                        attemptStartedMs,
                        timedOut = false
                    )
                    addressReachable = false
                    break
                }
                try {
                    val output = socket.getOutputStream()
                    if (!writeFragmentedClientHello(connection, output, payload, plan, failOnError = false)) {
                        recordFragmentationFailure(domain, suppressionContext, mode)
                        recordFailedAiAttempt(
                            connection,
                            mode,
                            TurboFailureCategory.WRITE,
                            attemptStartedMs,
                            timedOut = false
                        )
                        runCatching { socket.close() }
                        releaseAttemptSocket(connection, socket)
                        if (isClosed(connection)) return null
                        continue
                    }
                    val responseBudgetMs = (attemptDeadlineMs - monotonicNowMs()).coerceAtLeast(0L)
                    val firstChunk = if (responseBudgetMs > 0L) {
                        readFirstServerChunk(connection, socket, responseBudgetMs)
                    } else {
                        null
                    }
                    if (firstChunk != null) {
                        if (isLikelyTlsAlertResponse(firstChunk)) {
                            recordFragmentationFailure(domain, suppressionContext, mode)
                            recordFailedAiAttempt(
                                connection,
                                mode,
                                TurboFailureCategory.TLS_ALERT,
                                attemptStartedMs,
                                timedOut = false
                            )
                            runCatching { socket.close() }
                            releaseAttemptSocket(connection, socket)
                            if (isClosed(connection)) return null
                            continue
                        }
                        if (!isPlausibleTlsServerResponse(firstChunk)) {
                            recordFragmentationFailure(domain, suppressionContext, mode)
                            recordFailedAiAttempt(
                                connection,
                                mode,
                                TurboFailureCategory.OTHER,
                                attemptStartedMs,
                                timedOut = false
                            )
                            runCatching { socket.close() }
                            releaseAttemptSocket(connection, socket)
                            if (isClosed(connection)) return null
                            continue
                        }
                        recordFragmentationSuccess(domain, suppressionContext, mode)
                        markSuccessfulAiAttempt(connection, mode, attemptStartedMs)
                        socket.soTimeout = 0
                        synchronized(connection.lock) {
                            if (connection.closed) {
                                socket.close()
                                return null
                            }
                            connection.socket = socket
                            if (connection.attemptSocket === socket) connection.attemptSocket = null
                        }
                        val helloRetryRequest = isHelloRetryRequest(firstChunk)
                        if (!helloRetryRequest && trailingPayload.isNotEmpty()) {
                            writeSocketPayload(connection, socket.getOutputStream(), trailingPayload)
                            if (isClosed(connection)) return null
                        }
                        emitServerBytes(connection, firstChunk)
                        scope.launch(ioDispatcher) { readServerLoop(connection, socket) }
                        return if (helloRetryRequest) mode else null
                    }
                    recordFragmentationFailure(domain, suppressionContext, mode)
                    recordFailedAiAttempt(
                        connection,
                        mode,
                        TurboFailureCategory.TIMEOUT,
                        attemptStartedMs,
                        timedOut = true
                    )
                } catch (error: CancellationException) {
                    runCatching { socket.close() }
                    releaseAttemptSocket(connection, socket)
                    throw error
                } catch (_: Exception) {
                    recordFragmentationFailure(domain, suppressionContext, mode)
                    recordFailedAiAttempt(
                        connection,
                        mode,
                        TurboFailureCategory.OTHER,
                        attemptStartedMs,
                        timedOut = false
                    )
                }
                runCatching { socket.close() }
                releaseAttemptSocket(connection, socket)
                if (isClosed(connection)) return null
            }
        }
        fail(connection, "handshake-upstream")
        return null
    }

    private suspend fun forwardRetryClientHello(
        connection: Connection,
        mode: TlsClientHello.FragmentationMode
    ): Boolean {
        var buffered = ByteArray(0)
        var reservedBytes = 0
        var discardedPrelude = 0
        val deadline = monotonicNowMs() + RETRY_CLIENT_HELLO_TIMEOUT_MS
        try {
            while (!isClosed(connection)) {
                val remaining = deadline - monotonicNowMs()
                if (remaining <= 0L) {
                    fail(connection, "retry-client-hello-timeout")
                    return false
                }
                val payload = withTimeoutOrNull(remaining) {
                    connection.clientPayloads.receiveCatching().getOrNull()
                } ?: run {
                    fail(connection, "retry-client-hello-timeout")
                    return false
                }
                reservedBytes += payload.size
                if (buffered.size + payload.size > MAX_CLIENT_HELLO_BUFFER) {
                    fail(connection, "retry-client-hello-size")
                    return false
                }
                buffered += payload
                while (buffered.isNotEmpty()) {
                    val recordType = buffered[0].toInt() and 0xff
                    if (recordType == TLS_CHANGE_CIPHER_SPEC_RECORD_TYPE ||
                        recordType == TLS_APPLICATION_DATA_RECORD_TYPE
                    ) {
                        if (buffered.size < TLS_RECORD_HEADER_LENGTH) break
                        val major = buffered[1].toInt() and 0xff
                        val minor = buffered[2].toInt() and 0xff
                        val length = ((buffered[3].toInt() and 0xff) shl 8) or
                            (buffered[4].toInt() and 0xff)
                        if (major != TLS_MAJOR_VERSION || minor !in 0..4 ||
                            length !in 1..MAX_TLS_RECORD_LENGTH ||
                            discardedPrelude + TLS_RECORD_HEADER_LENGTH + length > MAX_HRR_PRELUDE_DISCARD
                        ) {
                            fail(connection, "retry-early-data")
                            return false
                        }
                        val recordEnd = TLS_RECORD_HEADER_LENGTH + length
                        if (buffered.size < recordEnd) break
                        if (recordType == TLS_CHANGE_CIPHER_SPEC_RECORD_TYPE &&
                            (length != 1 || buffered[TLS_RECORD_HEADER_LENGTH].toInt() != 1)
                        ) {
                            fail(connection, "retry-change-cipher-spec")
                            return false
                        }
                        discardedPrelude += recordEnd
                        buffered = buffered.copyOfRange(recordEnd, buffered.size)
                        continue
                    }
                    val analysis = TlsClientHello.analyze(buffered, buffered.size)
                    if (!analysis.complete) break
                    if (!analysis.clientHello || analysis.malformed) {
                        fail(connection, "retry-client-hello")
                        return false
                    }
                    diagnostics.recordTurboClientHello(analysis.sni != null)
                    val prefixEnd = analysis.clientHelloRecordEnd?.coerceIn(1, buffered.size)
                        ?: buffered.size
                    val clientHello = buffered.copyOfRange(0, prefixEnd)
                    val plan = TlsClientHello.fragmentationPlan(clientHello, clientHello.size, mode)
                        ?: run {
                            fail(connection, "retry-fragmentation-plan")
                            return false
                        }
                    val socket = ensureSocket(connection) ?: return false
                    val output = runCatching { socket.getOutputStream() }.getOrElse {
                        fail(connection, "retry-socket-output")
                        return false
                    }
                    if (!writeFragmentedClientHello(connection, output, clientHello, plan, failOnError = true)) {
                        return false
                    }
                    val trailing = buffered.copyOfRange(prefixEnd, buffered.size)
                    if (trailing.isNotEmpty()) writeSocketPayload(connection, output, trailing)
                    return !isClosed(connection)
                }
            }
            return false
        } finally {
            if (reservedBytes > 0) releaseClientPayloadBytes(connection, reservedBytes)
        }
    }

    private fun adaptiveModesForDomain(
        domain: String,
        suppressionContext: String
    ): List<TlsClientHello.FragmentationMode> {
        val key = fragmentationKey(domain, suppressionContext)
        val now = monotonicNowMs()
        val preferred = successfulFragmentationModes[key]
        val ordered = TlsClientHello.adaptiveModeOrder(preferred)
        val usable = synchronized(failedFragmentationModes) {
            val perMode = failedFragmentationModes[key]
            ordered.filter { mode ->
                if (mode == TlsClientHello.FragmentationMode.NONE) {
                    true
                } else {
                    val failure = perMode?.get(mode)
                    if (failure != null && now - failure.lastFailureMs >= MODE_FAILURE_SUPPRESSION_TTL_MS) {
                        perMode.remove(mode)
                        true
                    } else {
                        failure == null || failure.count < MODE_FAILURE_SUPPRESSION_THRESHOLD
                    }
                }
            }.also {
                if (perMode?.isEmpty() == true) failedFragmentationModes.remove(key)
            }
        }
        return usable.ifEmpty { listOf(TlsClientHello.FragmentationMode.NONE) }
    }

    private fun recordFragmentationSuccess(
        domain: String,
        suppressionContext: String,
        mode: TlsClientHello.FragmentationMode
    ) {
        val key = fragmentationKey(domain, suppressionContext)
        if (shouldCacheSuccessfulFragmentationMode(mode)) {
            successfulFragmentationModes[key] = mode
        } else {
            successfulFragmentationModes.remove(key)
        }
        synchronized(failedFragmentationModes) {
            val perMode = failedFragmentationModes[key] ?: return@synchronized
            perMode.remove(mode)
            if (perMode.isEmpty()) failedFragmentationModes.remove(key)
        }
    }

    private fun recordFragmentationFailure(
        domain: String,
        suppressionContext: String,
        mode: TlsClientHello.FragmentationMode
    ) {
        if (mode == TlsClientHello.FragmentationMode.NONE) return
        val key = fragmentationKey(domain, suppressionContext)
        val now = monotonicNowMs()
        synchronized(failedFragmentationModes) {
            val perMode = failedFragmentationModes.getOrPut(key) { linkedMapOf() }
            val failure = perMode[mode]
            if (failure == null || now - failure.lastFailureMs >= MODE_FAILURE_SUPPRESSION_TTL_MS) {
                perMode[mode] = FragmentationFailure(count = 1, lastFailureMs = now)
            } else {
                failure.count = (failure.count + 1).coerceAtMost(MODE_FAILURE_SUPPRESSION_THRESHOLD)
                failure.lastFailureMs = now
            }
        }
    }

    private fun markSuccessfulAiAttempt(
        connection: Connection,
        mode: TlsClientHello.FragmentationMode,
        attemptStartedMs: Long
    ) {
        val decision = connection.aiDecision ?: return
        val strategy = TurboStrategyId.fromMode(mode)
        connection.aiSuccessfulStrategy = strategy
        connection.aiSuccessfulPolicy = policyForAttempt(decision, strategy)
        connection.aiTlsResponseConfirmed = true
        val startedMs = connection.aiStartedElapsedMs.takeIf { it > 0L } ?: attemptStartedMs
        connection.aiHandshakeLatencyMs = (monotonicNowMs() - startedMs).coerceAtLeast(0L)
        connection.aiStartedElapsedMs = startedMs
        connection.aiFallbackUsed = strategy != decision.strategy || connection.aiAttemptCount > 1
        turboAiRuntime?.recordHandshakeOutcome(
            TurboOutcome(
                contextKey = decision.contextKey,
                strategy = strategy,
                policy = connection.aiSuccessfulPolicy ?: decision.policy,
                success = true,
                handshakeLatencyMs = connection.aiHandshakeLatencyMs,
                connectionDurationMs = connection.aiHandshakeLatencyMs,
                bytesUp = connection.aiBytesUp.get(),
                bytesDown = connection.aiBytesDown.get(),
                retries = (connection.aiAttemptCount - 1).coerceAtLeast(0),
                timedOut = false,
                abruptDisconnect = false,
                fallbackUsed = connection.aiFallbackUsed,
                decisionOverheadNanos = decision.decisionOverheadNanos,
                batterySaver = decision.batterySaver,
                failureCategory = TurboFailureCategory.NONE,
                completedAtMs = System.currentTimeMillis(),
                sharedContextKey = decision.sharedContextKey,
                aegisToken = decision.aegisToken,
                networkGeneration = decision.networkGeneration
            )
        )
    }

    private fun recordFailedAiAttempt(
        connection: Connection,
        mode: TlsClientHello.FragmentationMode,
        failureCategory: TurboFailureCategory,
        attemptStartedMs: Long,
        timedOut: Boolean
    ) {
        val decision = connection.aiDecision ?: return
        val strategy = TurboStrategyId.fromMode(mode)
        val elapsed = (monotonicNowMs() - attemptStartedMs).coerceAtLeast(0L)
        turboAiRuntime?.recordOutcome(
            TurboOutcome(
                contextKey = decision.contextKey,
                strategy = strategy,
                policy = policyForAttempt(decision, strategy),
                success = false,
                handshakeLatencyMs = elapsed,
                connectionDurationMs = elapsed,
                bytesUp = 0L,
                bytesDown = 0L,
                retries = (connection.aiAttemptCount - 1).coerceAtLeast(0),
                timedOut = timedOut,
                abruptDisconnect = false,
                fallbackUsed = strategy != decision.strategy || connection.aiAttemptCount > 1,
                decisionOverheadNanos = decision.decisionOverheadNanos,
                batterySaver = decision.batterySaver,
                failureCategory = failureCategory,
                completedAtMs = System.currentTimeMillis(),
                sharedContextKey = decision.sharedContextKey,
                aegisToken = decision.aegisToken,
                networkGeneration = decision.networkGeneration
            )
        )
    }

    private fun policyForAttempt(
        decision: TurboDecision,
        strategy: TurboStrategyId
    ): TurboDecisionPolicy {
        return when {
            strategy == decision.strategy -> decision.policy
            strategy == TurboStrategyId.TLS_PLAIN_V1 -> TurboDecisionPolicy.DEFAULT_SAFE
            else -> TurboDecisionPolicy.BASELINE_FALLBACK
        }
    }

    private fun fragmentationKey(domain: String, suppressionContext: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(fragmentationKeySalt)
        digest.update(domain.lowercase().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(suppressionContext.toByteArray(Charsets.UTF_8))
        return TurboModelCodec.encodeHex(digest.digest(), byteLimit = 16)
    }

    private fun directMapping(destinationAddress: ByteArray): TurboDomainMapper.Mapping {
        val address = destinationAddress.copyOf()
        return TurboDomainMapper.Mapping(
            domain = InetAddress.getByAddress(address).hostAddress.orEmpty(),
            virtualAddress = address,
            realAddress = address,
            realAddresses = listOf(address),
            expiresAtMs = Long.MAX_VALUE
        )
    }

    private suspend fun writeFragmentedClientHello(
        connection: Connection,
        output: java.io.OutputStream,
        payload: ByteArray,
        plan: TlsClientHello.FragmentationPlan,
        failOnError: Boolean = true
    ): Boolean {
        return runCatching {
            connection.outboundLock.withLock {

                if (plan.splitPoints.isEmpty()) {
                    output.write(payload)
                } else {
                    val records = TlsClientHello.splitIntoTlsRecords(payload, payload.size, plan.splitPoints)
                        ?: throw IOException("TLS record fragmentation plan cannot be represented")
                    records.forEachIndexed { index, record ->
                        output.write(record)
                        if (index < records.lastIndex && latencyProfile.fragmentInterByteDelayMs > 0L) {
                            delay(latencyProfile.fragmentInterByteDelayMs)
                        }
                    }
                }
            }
        }.onSuccess {
            diagnostics.recordTurboFragmentation(plan.strategy)
            diagnostics.recordTurboForwardUp(payload.size)
            if (connection.aiDecision != null) connection.aiBytesUp.addAndGet(payload.size.toLong())
        }.onFailure {
            if (it is CancellationException) throw it
            if (failOnError) {
                fail(connection, "clienthello-write")
            }
        }.isSuccess
    }

    private suspend fun writeSocketPayload(
        connection: Connection,
        output: java.io.OutputStream,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= payload.size)
        runCatching {
            connection.outboundLock.withLock {
                output.write(payload, offset, length)
            }
        }.onSuccess {
            diagnostics.recordTurboForwardUp(length)
            if (connection.aiDecision != null) connection.aiBytesUp.addAndGet(length.toLong())
        }.onFailure {
            if (it is CancellationException) throw it
            fail(connection, "client-write")
        }
    }

    private fun ensureSocket(connection: Connection): Socket? {
        synchronized(connection.lock) {
            connection.socket?.let { return it }
            if (connection.closed) return null
        }
        val addresses = connection.mapping.realInetAddresses.ifEmpty { listOf(connection.mapping.realInetAddress) }
        val socket = openUpstreamSocket(connection, addresses) ?: run {
            fail(connection, "upstream-connect")
            return null
        }
        synchronized(connection.lock) {
            if (connection.closed) {
                socket.close()
                return null
            }
            connection.socket = socket
            if (connection.attemptSocket === socket) connection.attemptSocket = null
        }
        scope.launch(ioDispatcher) { readServerLoop(connection, socket) }
        return socket
    }

    private fun openUpstreamSocket(
        connection: Connection,
        address: InetAddress,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS
    ): Socket? = openUpstreamSocket(connection, listOf(address), connectTimeoutMs)

    private fun openUpstreamSocket(
        connection: Connection,
        addresses: List<InetAddress>,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS
    ): Socket? {
        val candidates = addresses.flatMap { address ->
            runCatching { destinationCandidates(address) }.getOrDefault(emptyList())
        }
            .distinctBy { candidate ->
                candidate.address.toList() to ((candidate as? java.net.Inet6Address)?.scopeId ?: 0)
            }
        if (candidates.isEmpty()) return null
        val deadline = monotonicNowMs() + connectTimeoutMs.coerceIn(1, CONNECT_TIMEOUT_MS)
        for ((index, candidate) in candidates.withIndex()) {
            val remaining = (deadline - monotonicNowMs()).coerceAtMost(CONNECT_TIMEOUT_MS.toLong())
            if (remaining <= 0L) return null
            val candidatesLeft = candidates.size - index
            val attemptTimeout = if (candidatesLeft == 1) {
                remaining
            } else {
                (remaining / candidatesLeft).coerceAtLeast(1L)
            }.toInt()
            val socket = Socket()
            synchronized(connection.lock) {
                if (connection.closed) {
                    socket.close()
                    return null
                }
                connection.attemptSocket = socket
            }
            val connected = runCatching {
                socket.tcpNoDelay = true
                runCatching { socket.receiveBufferSize = UPSTREAM_SOCKET_BUFFER }
                runCatching { socket.sendBufferSize = UPSTREAM_SOCKET_BUFFER }
                if (!protectSocket(socket)) error("protect-false")
                socket.connect(
                    InetSocketAddress(candidate, connection.destinationPort),
                    attemptTimeout
                )
                true
            }.getOrDefault(false)
            if (connected) return socket
            runCatching { socket.close() }
            releaseAttemptSocket(connection, socket)
            if (isClosed(connection)) return null
        }
        return null
    }

    private fun releaseAttemptSocket(connection: Connection, socket: Socket) {
        synchronized(connection.lock) {
            if (connection.attemptSocket === socket) connection.attemptSocket = null
        }
    }

    private fun readFirstServerChunk(
        connection: Connection,
        socket: Socket,
        timeoutMs: Long = FIRST_SERVER_RESPONSE_TIMEOUT_MS.toLong()
    ): ByteArray? {
        val buffer = ByteArray(SERVER_READ_BUFFER)
        return try {
            val input = socket.getInputStream()
            val deadline = monotonicNowMs() +
                timeoutMs.coerceIn(1L, FIRST_SERVER_RESPONSE_TIMEOUT_MS.toLong())
            var total = 0
            var records = 0
            while (total < MAX_SERVER_HELLO_FLIGHT_BYTES) {
                val headerEnd = total + TLS_RECORD_HEADER_LENGTH
                if (!readServerBytes(connection, socket, buffer, total, headerEnd, deadline)) return null
                val type = buffer[total].toInt() and 0xff
                val major = buffer[total + 1].toInt() and 0xff
                val minor = buffer[total + 2].toInt() and 0xff
                val length = ((buffer[total + 3].toInt() and 0xff) shl 8) or
                    (buffer[total + 4].toInt() and 0xff)
                if (type !in TLS_ALERT_RECORD_TYPE..TLS_HANDSHAKE_RECORD_TYPE ||
                    major != TLS_MAJOR_VERSION || minor !in 0..4 || length !in 1..MAX_TLS_RECORD_LENGTH
                ) {
                    return buffer.copyOf(headerEnd)
                }
                val recordEnd = headerEnd + length
                if (recordEnd > MAX_SERVER_HELLO_FLIGHT_BYTES || recordEnd > buffer.size) return null
                if (!readServerBytes(connection, socket, buffer, headerEnd, recordEnd, deadline)) return null
                total = recordEnd
                records += 1
                if (type == TLS_ALERT_RECORD_TYPE || records >= MAX_SERVER_HELLO_RECORDS) {
                    return buffer.copyOf(total)
                }
                when (tlsServerHelloStatus(buffer.copyOf(total))) {
                    TlsServerHelloStatus.VALID, TlsServerHelloStatus.INVALID -> return buffer.copyOf(total)
                    TlsServerHelloStatus.INCOMPLETE -> Unit
                }
            }
            null
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readServerBytes(
        connection: Connection,
        socket: Socket,
        buffer: ByteArray,
        start: Int,
        end: Int,
        deadline: Long
    ): Boolean {
        val input = socket.getInputStream()
        var total = start
        while (total < end) {
            if (isClosed(connection)) return false
            val remaining = deadline - monotonicNowMs()
            if (remaining <= 0L) throw SocketTimeoutException()
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val read = input.read(buffer, total, end - total)
            if (read < 0) return false
            if (read > 0) total += read
        }
        return true
    }

    private fun emitServerBytes(connection: Connection, bytes: ByteArray, length: Int = bytes.size) {
        connection.touch()
        val safeLength = length.coerceIn(0, bytes.size)
        var offset = 0
        try {
            while (offset < safeLength) {
                if (isClosed(connection)) return
                val requested = minOf(connection.maxPayload, safeLength - offset)
                val chunkSize = awaitWindow(connection, requested)
                if (chunkSize <= 0 || isClosed(connection)) return
                writeTcpPacket(connection, FLAG_ACK or FLAG_PSH, bytes, offset, chunkSize)
                offset += chunkSize
            }
        } finally {
            if (offset > 0) {
                diagnostics.recordTurboForwardDown(offset)
                if (connection.aiDecision != null) connection.aiBytesDown.addAndGet(offset.toLong())
            }
        }
    }

    private fun writeTcpPacket(
        connection: Connection,
        flags: Int,
        bytes: ByteArray,
        offset: Int,
        length: Int
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        synchronized(connection.lock) {
            if (connection.closed && flags and FLAG_RST == 0) return
            val sequence = connection.serverNext
            val ack = connection.clientNext
            val availableReceiveWindow = connection.clientPayloadBudget.availableBytes()
            val windowField = when {
                flags and FLAG_RST != 0 -> 0
                flags and FLAG_SYN != 0 -> DEFAULT_WINDOW
                connection.windowScalingNegotiated -> availableReceiveWindow ushr OUR_WINDOW_SCALE
                else -> minOf(availableReceiveWindow, DEFAULT_WINDOW)
            }
            val advertisedWindow = if (connection.windowScalingNegotiated && flags and FLAG_SYN == 0) {
                windowField shl OUR_WINDOW_SCALE
            } else {
                windowField
            }
            val packet = buildTcpPacket(
                sourceIp = connection.key.virtualIp.bytes,
                destinationIp = connection.key.clientIp.bytes,
                sourcePort = connection.destinationPort,
                destinationPort = connection.key.clientPort,
                sequence = sequence,
                ack = ack,
                flags = flags,
                window = windowField,
                payload = bytes,
                payloadOffset = offset,
                payloadLength = length,
                options = if (flags and FLAG_SYN != 0) {
                    synAckOptions(connection.maxPayload, if (connection.windowScalingNegotiated) OUR_WINDOW_SCALE else null)
                } else {
                    ByteArray(0)
                }
            )
            val consumes = length + if (flags and (FLAG_SYN or FLAG_FIN) != 0) 1 else 0
            val queued = consumes > 0 && flags and FLAG_RST == 0
            if (queued && !connection.retransmissions.tryEnqueue(
                    sequence = sequence,
                    sequenceLength = consumes,
                    packet = packet,
                    nowMs = monotonicNowMs(),
                    payloadOffset = packet.size - length,
                    payloadLength = length,
                    hasSyn = flags and FLAG_SYN != 0,
                    hasFin = flags and FLAG_FIN != 0
                )
            ) {
                throw IOException("TUN retransmission queue is full")
            }
            if (queued) connection.serverNext = seqAdd(sequence, consumes)
            try {
                writeTunPacket(packet)
            } catch (error: Exception) {
                if (queued) {
                    connection.retransmissions.rollbackLast(sequence)
                    connection.serverNext = sequence
                }
                throw error
            }
            connection.lastAdvertisedReceiveWindow = advertisedWindow
            if (length > 0) connection.lastTunDataByte = bytes[offset + length - 1]
        }
    }

    private fun awaitWindow(connection: Connection, need: Int): Int {
        while (true) {
            var sendProbe = false
            synchronized(connection.lock) {
                if (connection.closed) return 0
                val unacked = (connection.serverNext - connection.sndUna) and UINT_MASK
                val available = connection.advertisedWindow.toLong() - unacked
                val packetOverhead = if (connection.key.virtualIp.isV6) {
                    IPV6_HEADER_LENGTH + TCP_MIN_HEADER_LENGTH
                } else {
                    IPV4_HEADER_LENGTH + TCP_MIN_HEADER_LENGTH
                }
                val queuePayloadBytes =
                    (connection.retransmissions.availableDataPacketBytes(
                        reservedSegments = TUN_FIN_RESERVE_SEGMENTS,
                        reservedBytes = TUN_FIN_RESERVE_BYTES
                    ) - packetOverhead).coerceAtLeast(0)
                if (available > 0L && queuePayloadBytes > 0) {
                    connection.persistTimer.reset()
                    return minOf(need.toLong(), available, queuePayloadBytes.toLong()).toInt()
                }
                val waitMs = if (connection.advertisedWindow == 0) {
                    val remaining = connection.persistTimer.millisUntilProbe(monotonicNowMs())
                    if (remaining == 0L) {
                        connection.persistTimer.onProbe(monotonicNowMs())
                        sendProbe = true
                        0L
                    } else {
                        remaining
                    }
                } else {
                    connection.persistTimer.reset()
                    SEND_STATE_RECHECK_MS
                }
                if (!sendProbe) runCatching { connection.lock.wait(waitMs.coerceAtLeast(1L)) }
            }
            if (sendProbe && !writeZeroWindowProbe(connection)) return 0
        }
    }

    private fun writeZeroWindowProbe(connection: Connection): Boolean {
        return try {
            synchronized(connection.lock) {
                if (connection.closed || connection.advertisedWindow != 0) return true
                writePersistProbeLocked(
                    connection,
                    connection.retransmissions.persistProbe(connection.sndUna)
                        ?: TurboTcpPersistProbe(
                            sequence = seqAdd(connection.sndUna, -1L),
                            value = connection.lastTunDataByte,
                            packet = null
                        )
                )
            }
            true
        } catch (_: Exception) {
            diagnostics.recordTurboForwardingError("tun-persist-write")
            closeConnection(connection, sendReset = false)
            false
        }
    }

    private fun writePersistProbeLocked(connection: Connection, probe: TurboTcpPersistProbe) {
        val retainedPacket = probe.packet
        if (retainedPacket != null) {
            writeTunPacket(retainedPacket)
            return
        }
        val value = probe.value ?: return
        val availableReceiveWindow = connection.clientPayloadBudget.availableBytes()
        val windowField = if (connection.windowScalingNegotiated) {
            availableReceiveWindow ushr OUR_WINDOW_SCALE
        } else {
            minOf(availableReceiveWindow, DEFAULT_WINDOW)
        }
        writeTunPacket(
            buildTcpPacket(
                sourceIp = connection.key.virtualIp.bytes,
                destinationIp = connection.key.clientIp.bytes,
                sourcePort = connection.destinationPort,
                destinationPort = connection.key.clientPort,
                sequence = probe.sequence,
                ack = connection.clientNext,
                flags = FLAG_ACK,
                window = windowField,
                payload = byteArrayOf(value)
            )
        )
        connection.lastAdvertisedReceiveWindow = if (connection.windowScalingNegotiated) {
            windowField shl OUR_WINDOW_SCALE
        } else {
            windowField
        }
    }

    private fun isClosed(connection: Connection): Boolean {
        return connection.closed
    }

    private fun readServerLoop(connection: Connection, socket: Socket) {
        val buffer = ByteArray(SERVER_READ_BUFFER)
        var cleanEof = false
        try {
            val input = socket.getInputStream()
            while (!connection.closed) {
                val read = input.read(buffer)
                if (read <= 0) {
                    cleanEof = true
                    break
                }
                connection.touch()
                emitServerBytes(connection, buffer, read)
            }
        } catch (_: Exception) {
            if (!connection.closed) {
                connection.aiAbruptDisconnect = true
                connection.aiTerminalFailure = TurboFailureCategory.SERVER_CLOSED
                diagnostics.recordTurboForwardingError("server-read")
                closeConnection(connection, sendReset = true)
            }
        } finally {
            if (cleanEof && !isClosed(connection)) sendServerFin(connection)
        }
    }

    private fun sendServerFin(connection: Connection) {
        while (true) {
            if (!awaitFinWindow(connection)) return
            var writeFailed = false
            var capacityChanged = false
            val finSent = synchronized(connection.lock) {
                if (connection.closed || connection.serverFinSent) {
                    false
                } else if (!hasFinSendCapacity(connection)) {
                    capacityChanged = true
                    false
                } else {
                    try {
                        writeTcpPacket(connection, FLAG_FIN or FLAG_ACK, ByteArray(0))
                        connection.serverFinSent = true
                        true
                    } catch (_: Exception) {
                        writeFailed = true
                        false
                    }
                }
            }
            if (capacityChanged) continue
            if (writeFailed) {
                diagnostics.recordTurboForwardingError("tun-fin-write")
                closeConnection(connection, sendReset = true)
                return
            }
            if (finSent || connection.serverFinSent) maybeReap(connection)
            return
        }
    }

    private fun awaitFinWindow(connection: Connection): Boolean {
        while (true) {
            var sendProbe = false
            synchronized(connection.lock) {
                if (connection.closed || connection.serverFinSent) return false
                if (hasFinSendCapacity(connection)) {
                    connection.persistTimer.reset()
                    return true
                }
                val waitMs = if (connection.advertisedWindow == 0) {
                    val nowMs = monotonicNowMs()
                    val remaining = connection.persistTimer.millisUntilProbe(nowMs)
                    if (remaining == 0L) {
                        connection.persistTimer.onProbe(nowMs)
                        sendProbe = true
                        0L
                    } else {
                        remaining
                    }
                } else {
                    connection.persistTimer.reset()
                    SEND_STATE_RECHECK_MS
                }
                if (!sendProbe) runCatching { connection.lock.wait(waitMs.coerceAtLeast(1L)) }
            }
            if (sendProbe && !writeZeroWindowProbe(connection)) return false
        }
    }

    private fun hasFinSendCapacity(connection: Connection): Boolean {
        val packetBytes = if (connection.key.virtualIp.isV6) {
            IPV6_HEADER_LENGTH + TCP_MIN_HEADER_LENGTH
        } else {
            IPV4_HEADER_LENGTH + TCP_MIN_HEADER_LENGTH
        }
        return hasSendWindow(
            sndUna = connection.sndUna,
            sndNxt = connection.serverNext,
            advertisedWindow = connection.advertisedWindow,
            sequenceLength = 1
        ) && connection.retransmissions.availablePacketBytes() >= packetBytes
    }

    private fun maybeReap(connection: Connection) {
        val phase = synchronized(connection.lock) {
            tcpClosePhase(
                connection.serverFinSent,
                connection.clientFinReceived,
                connection.sndUna,
                connection.serverNext
            )
        }
        when (phase) {
            TurboTcpClosePhase.OPEN_OR_HALF_CLOSED -> Unit
            TurboTcpClosePhase.WAITING_FOR_FINAL_ACK -> Unit
            TurboTcpClosePhase.TIME_WAIT -> {
                if (!detachToTimeWait(connection) && connection.timeWaitScheduled.compareAndSet(false, true)) {
                    val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                        delay(latencyProfile.timeWaitMs)
                        val stillInTimeWait = synchronized(connection.lock) {
                            tcpClosePhase(
                                connection.serverFinSent,
                                connection.clientFinReceived,
                                connection.sndUna,
                                connection.serverNext
                            ) == TurboTcpClosePhase.TIME_WAIT
                        }
                        if (stillInTimeWait) closeConnection(connection, sendReset = false)
                    }
                    val startJob = synchronized(connection.lock) {
                        if (connection.closed) {
                            false
                        } else {
                            connection.timeWaitJob = job
                            true
                        }
                    }
                    if (startJob) job.start() else job.cancel()
                }
            }
        }
    }

    private fun detachToTimeWait(connection: Connection): Boolean {
        val detached = synchronized(lifecycleLock) lifecycleLock@{
            val nowMs = monotonicNowMs()
            pruneExpiredTimeWaitsLocked(nowMs)
            if (closed.get() || connection.lifecycleGeneration != lifecycleGeneration.get()) {
                return@lifecycleLock false
            }
            if (timeWaitTombstones.size >= MAX_TIME_WAIT_TOMBSTONES) return@lifecycleLock false
            val tombstone = synchronized(connection.lock) connectionLock@{
                if (connection.closed || tcpClosePhase(
                        connection.serverFinSent,
                        connection.clientFinReceived,
                        connection.sndUna,
                        connection.serverNext
                    ) != TurboTcpClosePhase.TIME_WAIT
                ) return@connectionLock null
                if (!connections.remove(connection.key, connection)) return@connectionLock null
                connection.closed = true
                TimeWaitTombstone(
                    key = connection.key,
                    clientNext = connection.clientNext,
                    serverNext = connection.serverNext,
                    expiresAtMs = nowMs + latencyProfile.timeWaitMs
                )
            } ?: return@lifecycleLock false
            timeWaitTombstones[connection.key] = tombstone
            diagnostics.recordTurboTimeWaitCreated()
            true
        }
        if (detached) finalizeConnectionClose(connection)
        return detached
    }

    private fun pruneExpiredTimeWaitsLocked(nowMs: Long) {
        timeWaitTombstones.forEach { (key, tombstone) ->
            if (tombstone.expiresAtMs <= nowMs && timeWaitTombstones.remove(key, tombstone)) {
                diagnostics.recordTurboTimeWaitRemoved()
            }
        }
    }

    private fun processRetransmissions() {
        val nowMs = monotonicNowMs()
        synchronized(lifecycleLock) {
            pruneExpiredTimeWaitsLocked(nowMs)
        }
        connections.values.forEach { connection ->
            var exhausted = false
            var writeFailed = false
            synchronized(connection.lock) {
                if (!connection.closed) {
                    if (connection.advertisedWindow == 0) {
                        connection.retransmissions.pauseTimeouts()
                        val probe = connection.retransmissions.persistProbe(connection.sndUna)
                        if (probe != null && connection.persistTimer.millisUntilProbe(nowMs) == 0L) {
                            connection.persistTimer.onProbe(nowMs)
                            try {
                                writePersistProbeLocked(connection, probe)
                            } catch (_: Exception) {
                                writeFailed = true
                            }
                        }
                    } else {
                        connection.retransmissions.resumeTimeouts(nowMs)
                        val action = connection.retransmissions.pollTimeout(nowMs)
                        when {
                            action == null -> Unit
                            action.exhausted -> exhausted = true
                            else -> {
                                val packet = action.packet
                                if (packet != null) {
                                    try {
                                        writeTunPacket(packet)
                                    } catch (_: Exception) {
                                        writeFailed = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (writeFailed) {
                diagnostics.recordTurboForwardingError("tun-retransmit-write")
                closeConnection(connection, sendReset = false)
            } else if (exhausted) {
                connection.aiAbruptDisconnect = true
                connection.aiTerminalFailure = TurboFailureCategory.TIMEOUT
                diagnostics.recordTurboForwardingError("client-retransmit-timeout")
                closeConnection(connection, sendReset = true)
            }
        }
    }

    private fun reapIdleConnections() {
        val now = monotonicNowMs()
        connections.values
            .filter { !it.closed && now - it.lastActivityMs >= ESTABLISHED_IDLE_TIMEOUT_MS }
            .forEach { connection ->
                connection.aiAbruptDisconnect = true
                connection.aiTerminalFailure = TurboFailureCategory.TIMEOUT
                diagnostics.recordTurboForwardingError("connection-idle-timeout")
                closeConnection(connection, sendReset = true)
            }
    }

    private fun closeConnection(connection: Connection, sendReset: Boolean) {
        val shouldClose = synchronized(lifecycleLock) { markConnectionClosedLocked(connection, sendReset) }
        if (!shouldClose) return
        finalizeConnectionClose(connection)
    }

    private fun markConnectionClosedLocked(connection: Connection, sendReset: Boolean): Boolean {
        val ownsFlow = connections[connection.key] === connection
        return synchronized(connection.lock) {
            if (connection.closed) return@synchronized false
            connection.closed = true
            if (sendReset && ownsFlow) {
                runCatching { writeTcpPacket(connection, FLAG_RST or FLAG_ACK, ByteArray(0)) }
            }
            if (ownsFlow) connections.remove(connection.key, connection)
            true
        }
    }

    private fun finalizeConnectionClose(connection: Connection) {
        connection.handshakeDeadlineJob?.cancel()
        connection.timeWaitJob?.cancel()
        synchronized(connection.lock) {
            connection.retransmissions.clear()
            connection.persistTimer.reset()
            runCatching { connection.socket?.close() }
            runCatching { connection.attemptSocket?.close() }
            connection.attemptSocket = null
            connection.lock.notifyAll()
        }
        connection.clientPayloads.close()
        discardQueuedClientPayloads(connection)
        reportSuccessfulAiOutcome(connection)
        diagnostics.recordTurboConnectionClosed()
    }

    private fun reportSuccessfulAiOutcome(connection: Connection) {
        val decision = connection.aiDecision ?: return
        val strategy = connection.aiSuccessfulStrategy ?: return
        val policy = connection.aiSuccessfulPolicy ?: return
        if (!connection.aiOutcomeReported.compareAndSet(false, true)) return
        val nowElapsed = monotonicNowMs()
        val successful = isSuccessfulAiConnection(
            connection.aiTlsResponseConfirmed,
            connection.aiTerminalFailure
        )
        turboAiRuntime?.recordOutcome(
            TurboOutcome(
                contextKey = decision.contextKey,
                strategy = strategy,
                policy = policy,
                success = successful,
                handshakeLatencyMs = connection.aiHandshakeLatencyMs,
                connectionDurationMs = (nowElapsed - connection.aiStartedElapsedMs).coerceAtLeast(0L),
                bytesUp = connection.aiBytesUp.get(),
                bytesDown = connection.aiBytesDown.get(),
                retries = (connection.aiAttemptCount - 1).coerceAtLeast(0),
                timedOut = false,
                abruptDisconnect = connection.aiAbruptDisconnect,
                fallbackUsed = connection.aiFallbackUsed,
                decisionOverheadNanos = decision.decisionOverheadNanos,
                batterySaver = decision.batterySaver,
                failureCategory = if (successful) TurboFailureCategory.NONE else
                    connection.aiTerminalFailure.takeIf { it != TurboFailureCategory.NONE }
                        ?: TurboFailureCategory.SERVER_CLOSED,
                completedAtMs = System.currentTimeMillis(),
                sharedContextKey = decision.sharedContextKey,
                aegisToken = decision.aegisToken,
                networkGeneration = decision.networkGeneration
            )
        )
    }

    private fun fail(connection: Connection, reason: String) {
        diagnostics.recordTurboForwardingError(reason)
        closeConnection(connection, sendReset = true)
    }

    private fun buildSynAckResend(connection: Connection): ByteArray {
        val sequence: Long
        val ack: Long
        synchronized(connection.lock) {
            sequence = connection.serverInitial
            ack = seqAdd(connection.clientInitial, 1)
        }
        return buildTcpPacket(
            sourceIp = connection.key.virtualIp.bytes,
            destinationIp = connection.key.clientIp.bytes,
            sourcePort = connection.destinationPort,
            destinationPort = connection.key.clientPort,
            sequence = sequence,
            ack = ack,
            flags = FLAG_SYN or FLAG_ACK,
            window = DEFAULT_WINDOW,
            payload = ByteArray(0),
            options = synAckOptions(
                connection.maxPayload,
                if (connection.windowScalingNegotiated) OUR_WINDOW_SCALE else null
            )
        )
    }

    private fun buildChallengeAck(connection: Connection): ByteArray {
        val sequence: Long
        val ack: Long
        synchronized(connection.lock) {
            sequence = connection.serverNext
            ack = connection.clientNext
        }
        return buildTcpPacket(
            sourceIp = connection.key.virtualIp.bytes,
            destinationIp = connection.key.clientIp.bytes,
            sourcePort = connection.destinationPort,
            destinationPort = connection.key.clientPort,
            sequence = sequence,
            ack = ack,
            flags = FLAG_ACK,
            window = connection.lastAdvertisedReceiveWindow,
            payload = ByteArray(0)
        )
    }

    private fun buildTimeWaitAck(tombstone: TimeWaitTombstone): ByteArray {
        return buildTcpPacket(
            sourceIp = tombstone.key.virtualIp.bytes,
            destinationIp = tombstone.key.clientIp.bytes,
            sourcePort = tombstone.key.destinationPort,
            destinationPort = tombstone.key.clientPort,
            sequence = tombstone.serverNext,
            ack = tombstone.clientNext,
            flags = FLAG_ACK,
            window = 0,
            payload = ByteArray(0)
        )
    }

    private fun writeTcpPacket(connection: Connection, flags: Int, payload: ByteArray) {
        writeTcpPacket(connection, flags, payload, 0, payload.size)
    }

    private fun buildStandaloneRst(key: FlowKey, tcp: TcpPacket): ByteArray {
        val acknowledges = tcp.flags and FLAG_ACK == 0
        val consumed = tcp.payload.size +
            (if (tcp.flags and FLAG_SYN != 0) 1 else 0) +
            (if (tcp.flags and FLAG_FIN != 0) 1 else 0)
        return buildTcpPacket(
            sourceIp = key.virtualIp.bytes,
            destinationIp = key.clientIp.bytes,
            sourcePort = key.destinationPort,
            destinationPort = key.clientPort,
            sequence = if (acknowledges) 0L else tcp.ack,
            ack = if (acknowledges) seqAdd(tcp.sequence, consumed) else 0L,
            flags = if (acknowledges) FLAG_RST or FLAG_ACK else FLAG_RST,
            window = 0,
            payload = ByteArray(0)
        )
    }

    private fun parseTcp(packet: ByteArray, length: Int): TcpPacket? {
        if (length !in 1..packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0x0f
        val tcpOffset: Int
        val totalLength: Int
        val sourceIp: IpAddr
        val destinationIp: IpAddr
        when (version) {
            IPV4_VERSION -> {
                if (length < IPV4_HEADER_LENGTH + TCP_MIN_HEADER_LENGTH) return null
                val ihl = (packet[0].toInt() and 0x0f) * 4
                if (ihl < IPV4_HEADER_LENGTH || length < ihl + TCP_MIN_HEADER_LENGTH) return null
                if ((packet[IPV4_PROTOCOL_OFFSET].toInt() and 0xff) != TCP_PROTOCOL) return null
                val fragmentField = u16(packet, IPV4_FRAGMENT_OFFSET)
                if (fragmentField and IPV4_FRAGMENT_MASK != 0) return null
                totalLength = u16(packet, IPV4_TOTAL_LENGTH_OFFSET)
                if (totalLength != length) return null
                tcpOffset = ihl
                sourceIp = IpAddr(packet.copyOfRange(IPV4_SOURCE_OFFSET, IPV4_SOURCE_OFFSET + IPV4_ADDRESS_LENGTH))
                destinationIp = IpAddr(
                    packet.copyOfRange(IPV4_DESTINATION_OFFSET, IPV4_DESTINATION_OFFSET + IPV4_ADDRESS_LENGTH)
                )
            }
            IPV6_VERSION -> {
                if (length < IPV6_HEADER_LENGTH + TCP_MIN_HEADER_LENGTH) return null
                if ((packet[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != TCP_PROTOCOL) return null
                if (IPV6_HEADER_LENGTH + u16(packet, IPV6_PAYLOAD_LENGTH_OFFSET) != length) return null
                tcpOffset = IPV6_HEADER_LENGTH
                totalLength = length
                sourceIp = IpAddr(packet.copyOfRange(IPV6_SOURCE_OFFSET, IPV6_SOURCE_OFFSET + IPV6_ADDRESS_LENGTH))
                destinationIp = IpAddr(
                    packet.copyOfRange(IPV6_DESTINATION_OFFSET, IPV6_DESTINATION_OFFSET + IPV6_ADDRESS_LENGTH)
                )
                if (!Ipv6FullForwardRoutePolicy.isProxyableUnicast(sourceIp.bytes) ||
                    !Ipv6FullForwardRoutePolicy.isProxyableUnicast(destinationIp.bytes)
                ) return null
            }
            else -> return null
        }
        val dataOffset = ((packet[tcpOffset + 12].toInt() ushr 4) and 0x0f) * 4
        if (dataOffset < TCP_MIN_HEADER_LENGTH || tcpOffset + dataOffset > totalLength) return null
        if (packet[tcpOffset + 12].toInt() and TCP_RESERVED_BITS_MASK != 0) return null
        val flags = packet[tcpOffset + 13].toInt() and 0xff
        if (flags and FLAG_SYN != 0 && flags and (FLAG_FIN or FLAG_RST) != 0) return null
        val sourcePort = u16(packet, tcpOffset)
        val destinationPort = u16(packet, tcpOffset + 2)
        if (sourcePort == 0 || destinationPort == 0) return null
        val options = parseTcpOptions(
            packet,
            tcpOffset + TCP_MIN_HEADER_LENGTH,
            dataOffset - TCP_MIN_HEADER_LENGTH
        ) ?: return null
        if (version == IPV6_VERSION && !Ipv6TransportChecksum.isValid(
                packet = packet,
                packetLength = length,
                transportOffset = tcpOffset,
                transportLength = totalLength - tcpOffset,
                protocol = TCP_PROTOCOL
            )
        ) return null
        val payloadOffset = tcpOffset + dataOffset
        return TcpPacket(
            sourceIp = sourceIp,
            destinationIp = destinationIp,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequence = u32(packet, tcpOffset + 4),
            ack = u32(packet, tcpOffset + 8),
            flags = flags,
            window = u16(packet, tcpOffset + 14),
            mss = options.mss.takeIf { flags and FLAG_SYN != 0 },
            windowScale = options.windowScale.takeIf { flags and FLAG_SYN != 0 },
            payload = packet.copyOfRange(payloadOffset, totalLength)
        )
    }

    private fun parseTcpOptions(packet: ByteArray, optionOffset: Int, optionLength: Int): TcpOptions? {
        var offset = optionOffset
        val end = optionOffset + optionLength
        var mss: Int? = null
        var windowScale: Int? = null
        while (offset < end) {
            when (packet[offset].toInt() and 0xff) {
                0 -> return TcpOptions(mss, windowScale)
                1 -> offset += 1
                else -> {
                    if (offset + 1 >= end) return null
                    val length = packet[offset + 1].toInt() and 0xff
                    if (length < 2 || length > end - offset) return null
                    when (packet[offset].toInt() and 0xff) {
                        2 -> {
                            if (length != 4 || mss != null) return null
                            val value = u16(packet, offset + 2)
                            if (value == 0) return null
                            mss = value
                        }
                        3 -> {
                            if (length != 3 || windowScale != null) return null
                            windowScale = (packet[offset + 2].toInt() and 0xff).coerceAtMost(14)
                        }
                    }
                    offset += length
                }
            }
        }
        return TcpOptions(mss, windowScale)
    }

    private fun buildTcpPacket(
        sourceIp: ByteArray,
        destinationIp: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
        options: ByteArray = ByteArray(0)
    ): ByteArray {
        require(sourceIp.size == destinationIp.size)
        return when (sourceIp.size) {
            IPV4_ADDRESS_LENGTH -> buildIpv4TcpPacket(
                sourceIp,
                destinationIp,
                sourcePort,
                destinationPort,
                sequence,
                ack,
                flags,
                window,
                payload,
                payloadOffset,
                payloadLength,
                options
            )
            IPV6_ADDRESS_LENGTH -> buildIpv6TcpPacket(
                sourceIp,
                destinationIp,
                sourcePort,
                destinationPort,
                sequence,
                ack,
                flags,
                window,
                payload,
                payloadOffset,
                payloadLength,
                options
            )
            else -> error("unsupported IP address family")
        }
    }

    private fun buildIpv4TcpPacket(
        sourceIp: ByteArray,
        destinationIp: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
        options: ByteArray = ByteArray(0)
    ): ByteArray {
        require(payloadOffset >= 0 && payloadLength >= 0 && payloadOffset + payloadLength <= payload.size)
        val paddedOptions = padOptions(options)
        val tcpHeaderLength = TCP_MIN_HEADER_LENGTH + paddedOptions.size
        val totalLength = IPV4_HEADER_LENGTH + tcpHeaderLength + payloadLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[1] = 0
        put16(packet, 2, totalLength)
        put16(packet, 4, 0)
        put16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = TCP_PROTOCOL.toByte()
        sourceIp.copyInto(packet, IPV4_SOURCE_OFFSET)
        destinationIp.copyInto(packet, IPV4_DESTINATION_OFFSET)
        put16(packet, 10, ipv4Checksum(packet))

        val tcpOffset = IPV4_HEADER_LENGTH
        put16(packet, tcpOffset, sourcePort)
        put16(packet, tcpOffset + 2, destinationPort)
        put32(packet, tcpOffset + 4, sequence.toInt())
        put32(packet, tcpOffset + 8, ack.toInt())
        packet[tcpOffset + 12] = ((tcpHeaderLength / 4) shl 4).toByte()
        packet[tcpOffset + 13] = flags.toByte()
        put16(packet, tcpOffset + 14, window)
        put16(packet, tcpOffset + 16, 0)
        put16(packet, tcpOffset + 18, 0)
        paddedOptions.copyInto(packet, tcpOffset + TCP_MIN_HEADER_LENGTH)
        payload.copyInto(
            destination = packet,
            destinationOffset = tcpOffset + tcpHeaderLength,
            startIndex = payloadOffset,
            endIndex = payloadOffset + payloadLength
        )
        put16(packet, tcpOffset + 16, tcpChecksum(packet, tcpOffset, tcpHeaderLength + payloadLength))
        return packet
    }

    private fun buildIpv6TcpPacket(
        sourceIp: ByteArray,
        destinationIp: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
        options: ByteArray = ByteArray(0)
    ): ByteArray {
        require(sourceIp.size == IPV6_ADDRESS_LENGTH && destinationIp.size == IPV6_ADDRESS_LENGTH)
        require(payloadOffset >= 0 && payloadLength >= 0 && payloadOffset + payloadLength <= payload.size)
        val paddedOptions = padOptions(options)
        val tcpHeaderLength = TCP_MIN_HEADER_LENGTH + paddedOptions.size
        val tcpLength = tcpHeaderLength + payloadLength
        require(tcpLength <= 0xffff)
        val packet = ByteArray(IPV6_HEADER_LENGTH + tcpLength)
        packet[0] = 0x60
        put16(packet, IPV6_PAYLOAD_LENGTH_OFFSET, tcpLength)
        packet[IPV6_NEXT_HEADER_OFFSET] = TCP_PROTOCOL.toByte()
        packet[IPV6_HOP_LIMIT_OFFSET] = DEFAULT_HOP_LIMIT.toByte()
        sourceIp.copyInto(packet, IPV6_SOURCE_OFFSET)
        destinationIp.copyInto(packet, IPV6_DESTINATION_OFFSET)

        val tcpOffset = IPV6_HEADER_LENGTH
        put16(packet, tcpOffset, sourcePort)
        put16(packet, tcpOffset + 2, destinationPort)
        put32(packet, tcpOffset + 4, sequence.toInt())
        put32(packet, tcpOffset + 8, ack.toInt())
        packet[tcpOffset + 12] = ((tcpHeaderLength / 4) shl 4).toByte()
        packet[tcpOffset + 13] = flags.toByte()
        put16(packet, tcpOffset + 14, window)
        put16(packet, tcpOffset + 16, 0)
        put16(packet, tcpOffset + 18, 0)
        paddedOptions.copyInto(packet, tcpOffset + TCP_MIN_HEADER_LENGTH)
        payload.copyInto(
            destination = packet,
            destinationOffset = tcpOffset + tcpHeaderLength,
            startIndex = payloadOffset,
            endIndex = payloadOffset + payloadLength
        )
        put16(packet, tcpOffset + 16, ipv6TcpChecksum(packet, tcpOffset, tcpLength))
        return packet
    }

    private fun nextServerSequence(previousLargest: Long?): Long {
        val generated = serverSequenceSeed.addAndGet(SERVER_SEQUENCE_STRIDE).toLong() and UINT_MASK
        val adjusted = selectServerInitialSequence(generated, previousLargest)
        serverSequenceSeed.set(adjusted.toInt())
        return adjusted
    }

    private fun currentResourcePressure(): ResourcePressure {
        return ResourcePressure(
            lowMemory = false,
            thermalSevere = false,
            fileDescriptorPressure = false,
            queueUtilization = connections.size.toDouble() / MAX_CONNECTIONS,
            timeoutRate = 0.0,
            handoverActive = false,
            recovering = false,
            riskLevel = RiskLevel.NORMAL
        )
    }

    private fun resourcePressureBucket(): Int {
        return when {
            connections.size >= MAX_CONNECTIONS * 3 / 4 -> 4
            connections.size >= MAX_CONNECTIONS / 2 -> 3
            connections.size >= MAX_CONNECTIONS / 4 -> 2
            connections.size > 1 -> 1
            else -> 0
        }
    }

    companion object {
        private const val HTTPS_PORT = 443
        private const val DNS_PORT = 53
        private const val TCP_DNS_PREFIX_SIZE = 2
        private const val MIN_DNS_MESSAGE_SIZE = 12
        private const val MAX_DNS_MESSAGE_SIZE = 65_535
        private const val MAX_TCP_DNS_BUFFER = MAX_DNS_MESSAGE_SIZE + TCP_DNS_PREFIX_SIZE
        private const val TCP_PROTOCOL = 6
        private const val IPV4_VERSION = 4
        private const val IPV4_HEADER_LENGTH = 20
        private const val IPV4_ADDRESS_LENGTH = 4
        private const val IPV4_TOTAL_LENGTH_OFFSET = 2
        private const val IPV4_FRAGMENT_OFFSET = 6
        private const val IPV4_FRAGMENT_MASK = 0x3fff
        private const val IPV4_PROTOCOL_OFFSET = 9
        private const val IPV4_SOURCE_OFFSET = 12
        private const val IPV4_DESTINATION_OFFSET = 16
        private const val IPV6_VERSION = 6
        private const val IPV6_HEADER_LENGTH = 40
        private const val IPV6_ADDRESS_LENGTH = 16
        private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
        private const val IPV6_NEXT_HEADER_OFFSET = 6
        private const val IPV6_HOP_LIMIT_OFFSET = 7
        private const val IPV6_SOURCE_OFFSET = 8
        private const val IPV6_DESTINATION_OFFSET = 24
        private const val DEFAULT_HOP_LIMIT = 64
        private const val TCP_MIN_HEADER_LENGTH = 20
        private const val FLAG_FIN = 0x01
        private const val FLAG_SYN = 0x02
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10
        private const val TCP_RESERVED_BITS_MASK = 0x0e
        private const val MAX_CONNECTIONS = 64
        private const val MAX_TIME_WAIT_TOMBSTONES = 256
        private const val TCP_SOFT_LIMIT = 56
        private const val SERVER_SEQUENCE_STRIDE = 0x10001
        private const val TCP_IO_HEADROOM = 8
        private const val TCP_TASKS_PER_CONNECTION = 2
        private const val TCP_THREAD_KEEP_ALIVE_SECONDS = 30L
        private const val TCP_THREAD_STACK_BYTES = 512L * 1024L
        private const val DEFAULT_WINDOW = 65535
        private const val DEFAULT_TUN_MTU = 1500

        private const val OUR_WINDOW_SCALE = 8
        internal const val CLIENT_RELAY_BUFFER_BYTES = 2 * 1024 * 1024
        internal const val GLOBAL_CLIENT_RELAY_BUFFER_BYTES = 32 * 1024 * 1024
        internal const val MIN_TCP_PAYLOAD = 480
        internal const val CLIENT_PAYLOAD_QUEUE_CAPACITY = CLIENT_RELAY_BUFFER_BYTES / MIN_TCP_PAYLOAD + 1
        private const val CLIENT_WINDOW_UPDATE_THRESHOLD = 32 * 1024

        private const val UPSTREAM_SOCKET_BUFFER = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val FIRST_SERVER_RESPONSE_TIMEOUT_MS = 3500
        private const val MAX_CLIENT_HELLO_BUFFER = 65_699
        private const val SERVER_READ_BUFFER = 256 * 1024
        internal const val CLIENT_WRITE_BATCH_BYTES = 64 * 1024
        private const val MAX_TCP_PAYLOAD = 32728
        private const val MODE_FAILURE_SUPPRESSION_THRESHOLD = 3
        private const val MODE_FAILURE_SUPPRESSION_TTL_MS = 10 * 60 * 1000L
        private const val FRAGMENTATION_CACHE_MAX = 1024
        private const val MAX_TOTAL_HANDSHAKE_ATTEMPTS = 8
        private const val PLAIN_FALLBACK_RESERVE_MS = 3_000L
        private const val MAX_FRAGMENTED_ATTEMPT_MS = 2_500L
        private const val MIN_HANDSHAKE_ATTEMPT_MS = 250L
        private const val CONNECTION_SWEEP_INTERVAL_MS = 15_000L
        private const val RETRANSMISSION_SWEEP_INTERVAL_MS = 100L
        private const val ESTABLISHED_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val FALLBACK_SUPPRESSION_CONTEXT = "network:unknown"
        private const val INITIAL_RETRANSMISSION_RTO_MS = 1_000L
        private const val MAX_RETRANSMISSION_RTO_MS = 16_000L
        private const val MAX_TUN_RETRANSMISSIONS = 5
        private const val INITIAL_PERSIST_DELAY_MS = 1_000L
        private const val MAX_PERSIST_DELAY_MS = 60_000L
        private const val SEND_STATE_RECHECK_MS = 1_000L
        internal const val TUN_RETRANSMISSION_BUFFER_BYTES = 2 * 1024 * 1024
        internal const val TUN_RETRANSMISSION_MAX_SEGMENTS = 1024
        private const val TUN_FIN_RESERVE_SEGMENTS = 1
        private const val TUN_FIN_RESERVE_BYTES = IPV6_HEADER_LENGTH + TCP_MIN_HEADER_LENGTH
        internal const val GLOBAL_TUN_RETRANSMISSION_BUFFER_BYTES = 64 * 1024 * 1024
        private const val CLIENT_HANDSHAKE_IDLE_TIMEOUT_MS = 15_000L
        private const val UINT_MASK = TCP_UINT_MASK

        private fun synAckOptions(mss: Int, windowScale: Int?): ByteArray {
            val safeMss = mss.coerceIn(MIN_TCP_PAYLOAD, MAX_TCP_PAYLOAD)
            val mssOpt = byteArrayOf(2, 4, ((safeMss ushr 8) and 0xff).toByte(), (safeMss and 0xff).toByte())
            val wsOpt = if (windowScale != null) byteArrayOf(3, 3, (windowScale and 0xff).toByte()) else ByteArray(0)
            return padOptions(mssOpt + wsOpt)
        }

        private fun padOptions(options: ByteArray): ByteArray {
            if (options.isEmpty()) return options
            val padded = ((options.size + 3) / 4) * 4
            return if (padded == options.size) options else options.copyOf(padded)
        }

        fun isTurboVirtualIpv4(address: ByteArray): Boolean {
            return address.size == 4 &&
                (address[0].toInt() and 0xff) == 192 &&
                (address[1].toInt() and 0xff) == 0 &&
                (address[2].toInt() and 0xff) == 2
        }

        private fun seqAdd(sequence: Long, delta: Int): Long = tcpSeqAdd(sequence, delta)
        private fun seqAdd(sequence: Long, delta: Long): Long = (sequence + delta) and UINT_MASK
        private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

        internal fun prioritizePlainFallback(
            modes: List<TlsClientHello.FragmentationMode>
        ): List<TlsClientHello.FragmentationMode> {
            val unique = modes.distinct()
            val plain = TlsClientHello.FragmentationMode.NONE
            if (plain !in unique || unique.firstOrNull() == plain) return unique
            val fragmented = unique.filterNot { it == plain }
            return buildList {
                addAll(fragmented.take(2))
                add(plain)
                addAll(fragmented.drop(2))
            }
        }

        internal fun isAckInSendRange(ackNumber: Long, sndUna: Long, sndNxt: Long): Boolean {
            return sequenceCompare(ackNumber, sndUna) >= 0 && sequenceCompare(ackNumber, sndNxt) <= 0
        }

        internal fun isNewerIncarnationSyn(sequence: Long, previousLargest: Long): Boolean {
            return sequenceCompare(sequence, previousLargest) > 0
        }

        internal fun isAcceptableReset(sequence: Long, expectedSequence: Long): Boolean {
            return sequenceCompare(sequence, expectedSequence) == 0
        }

        internal fun selectServerInitialSequence(generated: Long, previousLargest: Long?): Long {
            if (previousLargest == null || sequenceCompare(generated, previousLargest) > 0) {
                return generated and UINT_MASK
            }
            return seqAdd(previousLargest, SERVER_SEQUENCE_STRIDE.toLong())
        }

        internal fun isClientSegmentInReceiveWindow(
            segmentSequence: Long,
            segmentLength: Int,
            expectedSequence: Long,
            receiveWindow: Int
        ): Boolean {
            if (segmentLength < 0 || receiveWindow < 0) return false
            if (receiveWindow == 0) {
                return segmentLength == 0 && sequenceCompare(segmentSequence, expectedSequence) == 0
            }
            val windowEnd = seqAdd(expectedSequence, receiveWindow)
            fun inWindow(sequence: Long): Boolean {
                return sequenceCompare(sequence, expectedSequence) >= 0 &&
                    sequenceCompare(sequence, windowEnd) < 0
            }
            if (segmentLength == 0) return inWindow(segmentSequence)
            return inWindow(segmentSequence) || inWindow(seqAdd(segmentSequence, segmentLength - 1))
        }

        internal fun hasSendWindow(
            sndUna: Long,
            sndNxt: Long,
            advertisedWindow: Int,
            sequenceLength: Int
        ): Boolean {
            if (advertisedWindow < 0 || sequenceLength <= 0) return false
            val unacknowledged = (sndNxt - sndUna) and UINT_MASK
            return unacknowledged <= advertisedWindow.toLong() - sequenceLength.toLong()
        }

        internal fun shouldUpdateSendWindow(
            segmentSequence: Long,
            ackNumber: Long,
            sndUna: Long,
            sndNxt: Long,
            sndWl1: Long,
            sndWl2: Long
        ): Boolean {
            if (!isAckInSendRange(ackNumber, sndUna, sndNxt)) return false
            val sequenceOrder = sequenceCompare(segmentSequence, sndWl1)
            return sequenceOrder > 0 ||
                (sequenceOrder == 0 && sequenceCompare(ackNumber, sndWl2) >= 0)
        }

        internal fun trimPayloadForExpectedSequence(
            expectedSequence: Long,
            packetSequence: Long,
            payload: ByteArray
        ): Pair<Long, ByteArray>? {
            if (payload.isEmpty()) return expectedSequence to payload
            return when (sequenceCompare(packetSequence, expectedSequence)) {
                0 -> seqAdd(expectedSequence, payload.size) to payload
                1 -> null
                else -> {

                    val duplicatePrefix = (expectedSequence - packetSequence) and UINT_MASK
                    if (duplicatePrefix >= payload.size.toLong()) {
                        null
                    } else {
                        val trimmed = payload.copyOfRange(duplicatePrefix.toInt(), payload.size)
                        seqAdd(expectedSequence, trimmed.size) to trimmed
                    }
                }
            }
        }

        internal fun isLikelyTlsAlertResponse(payload: ByteArray): Boolean {
            if (payload.size < TLS_RECORD_HEADER_LENGTH) return false
            val length = ((payload[3].toInt() and 0xff) shl 8) or (payload[4].toInt() and 0xff)
            return (payload[0].toInt() and 0xff) == TLS_ALERT_RECORD_TYPE &&
                (payload[1].toInt() and 0xff) == TLS_MAJOR_VERSION &&
                (payload[2].toInt() and 0xff) in 0..4 &&
                length in 1..MAX_TLS_RECORD_LENGTH && payload.size == TLS_RECORD_HEADER_LENGTH + length
        }

        internal fun isPlausibleTlsServerResponse(payload: ByteArray): Boolean {
            return tlsServerHelloStatus(payload) == TlsServerHelloStatus.VALID
        }

        internal fun tlsServerHelloStatus(payload: ByteArray): TlsServerHelloStatus {
            val handshake = extractServerHandshake(payload) ?: return TlsServerHelloStatus.INVALID
            if (handshake.size < 4) return TlsServerHelloStatus.INCOMPLETE
            if ((handshake[0].toInt() and 0xff) != TLS_SERVER_HELLO) return TlsServerHelloStatus.INVALID
            val handshakeLength = ((handshake[1].toInt() and 0xff) shl 16) or
                ((handshake[2].toInt() and 0xff) shl 8) or (handshake[3].toInt() and 0xff)
            if (handshakeLength < 38) return TlsServerHelloStatus.INVALID
            val handshakeEnd = 4 + handshakeLength
            if (handshake.size < handshakeEnd) return TlsServerHelloStatus.INCOMPLETE
            if ((handshake[4].toInt() and 0xff) != TLS_MAJOR_VERSION ||
                (handshake[5].toInt() and 0xff) !in 0..4
            ) return TlsServerHelloStatus.INVALID
            val sessionIdLength = handshake[38].toInt() and 0xff
            if (sessionIdLength > 32) return TlsServerHelloStatus.INVALID
            val cipherOffset = 39 + sessionIdLength
            if (cipherOffset + 3 > handshakeEnd) return TlsServerHelloStatus.INVALID
            val cipherSuite = ((handshake[cipherOffset].toInt() and 0xff) shl 8) or
                (handshake[cipherOffset + 1].toInt() and 0xff)
            if (cipherSuite == 0 || handshake[cipherOffset + 2].toInt() != 0) {
                return TlsServerHelloStatus.INVALID
            }
            val extensionsOffset = cipherOffset + 3
            if (extensionsOffset == handshakeEnd) return TlsServerHelloStatus.VALID
            if (extensionsOffset + 2 > handshakeEnd) return TlsServerHelloStatus.INVALID
            val extensionsLength = ((handshake[extensionsOffset].toInt() and 0xff) shl 8) or
                (handshake[extensionsOffset + 1].toInt() and 0xff)
            return if (extensionsOffset + 2 + extensionsLength == handshakeEnd) {
                TlsServerHelloStatus.VALID
            } else {
                TlsServerHelloStatus.INVALID
            }
        }

        internal fun isHelloRetryRequest(payload: ByteArray): Boolean {
            if (!isPlausibleTlsServerResponse(payload)) return false
            val handshake = extractServerHandshake(payload) ?: return false
            return handshake.copyOfRange(6, 38).contentEquals(HELLO_RETRY_REQUEST_RANDOM)
        }

        internal fun shouldCacheSuccessfulFragmentationMode(mode: TlsClientHello.FragmentationMode): Boolean {
            return mode != TlsClientHello.FragmentationMode.NONE
        }

        internal fun isSuccessfulAiConnection(responseConfirmed: Boolean, failure: TurboFailureCategory): Boolean {
            return responseConfirmed && failure == TurboFailureCategory.NONE
        }

        internal fun tcpClosePhase(
            serverFinSent: Boolean,
            clientFinReceived: Boolean,
            sndUna: Long,
            serverNext: Long
        ): TurboTcpClosePhase {
            if (!serverFinSent || !clientFinReceived) return TurboTcpClosePhase.OPEN_OR_HALF_CLOSED
            return if (sndUna == serverNext) {
                TurboTcpClosePhase.TIME_WAIT
            } else {
                TurboTcpClosePhase.WAITING_FOR_FINAL_ACK
            }
        }

        private fun sequenceCompare(left: Long, right: Long): Int {
            return tcpSequenceCompare(left, right)
        }

        private const val TLS_RECORD_HEADER_LENGTH = 5
        private const val TLS_CHANGE_CIPHER_SPEC_RECORD_TYPE = 20
        private const val TLS_ALERT_RECORD_TYPE = 21
        private const val TLS_HANDSHAKE_RECORD_TYPE = 22
        private const val TLS_APPLICATION_DATA_RECORD_TYPE = 23
        private const val TLS_SERVER_HELLO = 2
        private const val TLS_MAJOR_VERSION = 3
        private const val MAX_TLS_RECORD_LENGTH = 18_432
        private const val MAX_SERVER_HELLO_RECORDS = 8
        private const val MAX_SERVER_HELLO_FLIGHT_BYTES = 64 * 1024
        private const val RETRY_CLIENT_HELLO_TIMEOUT_MS = 10_000L
        private const val MAX_HRR_PRELUDE_DISCARD = 64 * 1024
        private val HELLO_RETRY_REQUEST_RANDOM = byteArrayOf(
            0xcf.toByte(), 0x21, 0xad.toByte(), 0x74, 0xe5.toByte(), 0x9a.toByte(), 0x61, 0x11,
            0xbe.toByte(), 0x1d, 0x8c.toByte(), 0x02, 0x1e, 0x65, 0xb8.toByte(), 0x91.toByte(),
            0xc2.toByte(), 0xa2.toByte(), 0x11, 0x16, 0x7a, 0xbb.toByte(), 0x8c.toByte(), 0x5e,
            0x07, 0x9e.toByte(), 0x09, 0xe2.toByte(), 0xc8.toByte(), 0xa8.toByte(), 0x33, 0x9c.toByte()
        )

        private fun extractServerHandshake(payload: ByteArray): ByteArray? {
            if (payload.isEmpty()) return ByteArray(0)
            val output = ByteArrayOutputStream(payload.size)
            var offset = 0
            var records = 0
            while (offset < payload.size) {
                if (offset + TLS_RECORD_HEADER_LENGTH > payload.size) return null
                if ((payload[offset].toInt() and 0xff) != TLS_HANDSHAKE_RECORD_TYPE ||
                    (payload[offset + 1].toInt() and 0xff) != TLS_MAJOR_VERSION ||
                    (payload[offset + 2].toInt() and 0xff) !in 0..4
                ) return null
                val length = ((payload[offset + 3].toInt() and 0xff) shl 8) or
                    (payload[offset + 4].toInt() and 0xff)
                if (length !in 1..MAX_TLS_RECORD_LENGTH) return null
                val end = offset + TLS_RECORD_HEADER_LENGTH + length
                if (end > payload.size) return null
                output.write(payload, offset + TLS_RECORD_HEADER_LENGTH, length)
                records += 1
                if (records > MAX_SERVER_HELLO_RECORDS || output.size() > MAX_SERVER_HELLO_FLIGHT_BYTES) return null
                offset = end
            }
            return output.toByteArray()
        }

        private fun u16(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        }

        private fun u32(data: ByteArray, offset: Int): Long {
            return ((data[offset].toLong() and 0xffL) shl 24) or
                ((data[offset + 1].toLong() and 0xffL) shl 16) or
                ((data[offset + 2].toLong() and 0xffL) shl 8) or
                (data[offset + 3].toLong() and 0xffL)
        }

        private fun put16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value ushr 8) and 0xff).toByte()
            data[offset + 1] = (value and 0xff).toByte()
        }

        private fun put32(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value ushr 24) and 0xff).toByte()
            data[offset + 1] = ((value ushr 16) and 0xff).toByte()
            data[offset + 2] = ((value ushr 8) and 0xff).toByte()
            data[offset + 3] = (value and 0xff).toByte()
        }

        private fun ipv4Checksum(packet: ByteArray): Int {
            return checksum(packet, 0, IPV4_HEADER_LENGTH)
        }

        private fun tcpChecksum(packet: ByteArray, tcpOffset: Int, tcpLength: Int): Int {
            var sum = 0L
            sum += u16(packet, 12).toLong()
            sum += u16(packet, 14).toLong()
            sum += u16(packet, 16).toLong()
            sum += u16(packet, 18).toLong()
            sum += TCP_PROTOCOL.toLong()
            sum += tcpLength.toLong()
            sum += checksumSum(packet, tcpOffset, tcpLength)
            while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
            val value = sum.inv().toInt() and 0xffff
            return if (value == 0) 0xffff else value
        }

        private fun ipv6TcpChecksum(packet: ByteArray, tcpOffset: Int, tcpLength: Int): Int {
            var sum = 0L
            sum += checksumSum(packet, IPV6_SOURCE_OFFSET, IPV6_ADDRESS_LENGTH * 2)
            sum += tcpLength.toLong()
            sum += TCP_PROTOCOL.toLong()
            sum += checksumSum(packet, tcpOffset, tcpLength)
            while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
            val value = sum.inv().toInt() and 0xffff
            return if (value == 0) 0xffff else value
        }

        private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = checksumSum(data, offset, length)
            while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
            return sum.inv().toInt() and 0xffff
        }

        private fun checksumSum(data: ByteArray, offset: Int, length: Int): Long {
            var sum = 0L
            var index = offset
            val end = offset + length
            while (index + 1 < end) {
                sum += u16(data, index).toLong()
                index += 2
            }
            if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
            return sum
        }
    }
}
