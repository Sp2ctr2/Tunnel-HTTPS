package com.tunnelvpn.app

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.withLock

class TurboUdpForwarder(
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val diagnostics: DiagnosticsState,
    private val scope: CoroutineScope,
    private val writeTunPacket: (ByteArray) -> Unit,
    mtu: Int = 1500,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val onUdp443Reachable: (ByteArray) -> Unit = {},
    private val onUdp443Unreachable: (ByteArray) -> Unit = {},
    private val udp443ProbeTimeoutMs: Long = UDP_443_PROBE_TIMEOUT_MS,
    private val httpsPort: Int = HTTPS_PORT,
    private val destinationTranslator: (InetAddress) -> InetAddress = { it },
    private val destinationCandidates: (InetAddress) -> List<InetAddress> = {
        listOf(destinationTranslator(it))
    },
    private val allowIcmpv6Error: () -> Boolean = { true },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() }
) {
    private val tunMtu = mtu
    private val closed = AtomicBoolean(false)
    private val responsePublicationLock = ReentrantLock(true)
    private val lifecycleGeneration = AtomicLong()
    private val lifecycleTransitionsPending = AtomicInteger()
    private val fragmentIdentification = AtomicInteger(SecureRandom().nextInt())
    private val safeBurst = SafeBurstController(UDP_SOFT_LIMIT, MAX_FLOWS, MAX_FLOWS - UDP_SOFT_LIMIT)
    private val maxTunPayload = (mtu - IPV4_HEADER_LENGTH - UDP_HEADER_LENGTH).coerceAtLeast(0)
    private val maxTunPayloadV6 = (mtu - IPV6_HEADER_LENGTH - UDP_HEADER_LENGTH).coerceAtLeast(0)

    private class IpAddr(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        val isV6: Boolean get() = bytes.size == 16
        override fun equals(other: Any?): Boolean =
            other is IpAddr && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = hash
    }

    private data class FlowKey(
        val clientIp: IpAddr,
        val clientPort: Int,
        val destIp: IpAddr,
        val destPort: Int
    )

    private data class OutboundIpv6(
        val validatedPacket: ByteArray,
        val invokingPacket: ByteArray
    )

    private data class OutboundTicket(
        val sequence: Long,
        val payload: ByteArray,
        val ipv6: OutboundIpv6?
    )

    private data class ActiveProbe(
        val generation: Long,
        val probe: QuicInitialClassifier.Probe,
        val ticket: OutboundTicket,
        val socketEpoch: Long
    )

    private sealed class ErrorProvenance {
        object None : ErrorProvenance()
        data class Unique(val ticket: OutboundTicket) : ErrorProvenance()
        object Ambiguous : ErrorProvenance()
    }

    private sealed class RetryResult {
        data class Retried(val ticket: OutboundTicket, val socketEpoch: Long) : RetryResult()
        object Stale : RetryResult()
        object Ambiguous : RetryResult()
        data class Exhausted(val outbound: OutboundIpv6?) : RetryResult()
    }

    private data class SendResult(
        val failure: Throwable?,
        val sent: Boolean,
        val ticket: OutboundTicket,
        val exhaustedOutbound: OutboundIpv6?,
        val activeProbe: ActiveProbe?
    )

    private inner class Flow(
        val key: FlowKey,
        @Volatile var socket: DatagramSocket,
        val destinations: List<InetAddress>,
        @Volatile var destinationIndex: Int,
        val lifecycle: Long
    ) {
        @Volatile var lastActivityMs: Long = clock()
        @Volatile var closed: Boolean = false
        @Volatile var responseReceived: Boolean = false
        @Volatile var probeJob: kotlinx.coroutines.Job? = null
        @Volatile var quicProbe: QuicInitialClassifier.Probe? = null
        val quicProbeObserved = AtomicBoolean(false)
        val probeOutcomeReported = AtomicBoolean(false)
        var probeGeneration: Long = 0L
        var activeProbe: ActiveProbe? = null
        var socketEpoch: Long = 0L
        var nextOutboundSequence: Long = 0L
        var latestOutbound: OutboundTicket? = null
        var errorProvenance: ErrorProvenance = ErrorProvenance.None
        var terminal: Boolean = false

        fun touch() {
            lastActivityMs = clock()
        }
    }

    private val flows = ConcurrentHashMap<FlowKey, Flow>()

    private val ioThreadCount = AtomicInteger()
    private val ioExecutor = Executors.newFixedThreadPool(MAX_FLOWS + 1) { runnable ->
        Thread(runnable, "TurboUdpIO-${ioThreadCount.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val ioDispatcher = ioExecutor.asCoroutineDispatcher()

    private val sweepJob = scope.launch(ioDispatcher) {
        while (scope.isActive && !closed.get()) {
            delay(SWEEP_INTERVAL_MS)
            reapIdleFlows()
        }
    }

    fun handleIpv4Packet(
        packet: ByteArray,
        length: Int,
        reassembled: Boolean = false
    ): Boolean = handlePacket(packet, length, reassembled, packet)

    fun handleIpv6Packet(
        packet: ByteArray,
        length: Int,
        reassembled: Boolean = false,
        invokingPacket: ByteArray = packet
    ): Boolean {
        return handlePacket(packet, length, reassembled, invokingPacket)
    }

    private fun handlePacket(
        packet: ByteArray,
        length: Int,
        reassembled: Boolean,
        invokingPacket: ByteArray
    ): Boolean {
        if (closed.get()) return false
        val udp = parseUdp(packet, length) ?: return false
        if (udp.destPort == DNS_PORT) return false
        val maximumPayload = when {
            reassembled && udp.destIp.isV6 -> MAX_IPV6_UDP_PAYLOAD
            reassembled -> MAX_IPV4_UDP_PAYLOAD
            udp.destIp.isV6 -> maxTunPayloadV6
            else -> maxTunPayload
        }
        val invokingIpv6Length = if (udp.destIp.isV6) declaredIpv6PacketLength(invokingPacket) else null
        val invokingPacketExceedsMtu = !reassembled && if (udp.destIp.isV6) {
            (invokingIpv6Length ?: length) > tunMtu
        } else {
            length > tunMtu
        }
        if (udp.payload.size > maximumPayload || invokingPacketExceedsMtu) {
            diagnostics.recordUdpRelayOversizeDropped()
            if (udp.destIp.isV6 && allowIcmpv6Error()) {
                Ipv6IcmpPacketTooBig.build(
                    validatedPacket = packet,
                    length = length,
                    mtu = tunMtu,
                    invokingPacket = invokingPacket
                )?.let { response ->
                    runCatching { writeTunPacket(response) }
                }
            }
            return true
        }

        val key = FlowKey(udp.sourceIp, udp.sourcePort, udp.destIp, udp.destPort)
        val quicProbe = if (udp.destPort == httpsPort) {
            QuicInitialClassifier.inspectProbe(udp.payload)
        } else {
            null
        }
        val existing = flows[key]
        var flow = if (existing != null && !existing.closed && !existing.terminal) {
            existing
        } else {
            openFlow(key) ?: run {
                diagnostics.recordUdpRelayFlowDropped()
                if (quicProbe?.knownInitial == true) reportUdp443Unreachable(key.destIp, null)
                return true
            }
        }
        val outboundIpv6 = if (udp.destIp.isV6) {
            OutboundIpv6(
                packet.copyOf(length),
                if (invokingIpv6Length != null) {
                    invokingPacket.copyOf(invokingIpv6Length)
                } else {
                    packet.copyOf(length)
                }
            )
        } else {
            null
        }
        var sendResult: SendResult? = null
        while (sendResult == null) {
            sendResult = synchronized(flow) {
                if (flow.closed || flow.terminal || closed.get()) {
                    null
                } else {
                    val ticket = nextOutboundTicket(flow, udp, outboundIpv6)
                    flow.latestOutbound = ticket
                    val previousProvenance = flow.errorProvenance
                    flow.errorProvenance = when (previousProvenance) {
                        ErrorProvenance.None -> ErrorProvenance.Unique(ticket)
                        is ErrorProvenance.Unique, ErrorProvenance.Ambiguous -> ErrorProvenance.Ambiguous
                    }
                    val sendingSocket = flow.socket
                    val sendingEpoch = flow.socketEpoch
                    val failure = runCatching {
                        sendingSocket.send(DatagramPacket(ticket.payload, ticket.payload.size))
                    }.exceptionOrNull()
                    var exhaustedOutbound: OutboundIpv6? = null
                    val sent = if (failure == null) {
                        true
                    } else if (failure is PortUnreachableException && previousProvenance === ErrorProvenance.None) {
                        when (val retry = retryExactTicket(flow, sendingSocket, sendingEpoch, ticket)) {
                            is RetryResult.Retried -> true
                            is RetryResult.Exhausted -> {
                                exhaustedOutbound = retry.outbound
                                false
                            }
                            RetryResult.Stale, RetryResult.Ambiguous -> false
                        }
                    } else {
                        flow.terminal = true
                        false
                    }
                    if (sent) flow.touch()
                    val activeProbe = if (sent && quicProbe != null) {
                        armQuicProbe(flow, quicProbe, ticket)
                    } else {
                        null
                    }
                    SendResult(failure, sent, ticket, exhaustedOutbound, activeProbe)
                }
            }
            if (sendResult == null) {
                if (closed.get()) return true
                flow = openFlow(key) ?: run {
                    diagnostics.recordUdpRelayFlowDropped()
                    if (quicProbe?.knownInitial == true) reportUdp443Unreachable(key.destIp, null)
                    return true
                }
            }
        }
        val (sendFailure, sent, _, exhaustedOutbound, activeProbe) = checkNotNull(sendResult)
        if (sent) {
            activeProbe?.let { scheduleQuicProbeTimeout(flow, it) }
            diagnostics.recordUdpRelayForwardUp(udp.payload.size)
        } else {
            if (sendFailure is PortUnreachableException && exhaustedOutbound != null) {
                emitIpv6PortUnreachable(exhaustedOutbound)
            }
            if (quicProbe?.knownInitial == true) {
                reportUdp443Unreachable(flow.key.destIp, null)
            } else if (flow.quicProbeObserved.get()) {
                reportUdp443Unreachable(flow.key.destIp, flow)
            }
            closeFlow(flow)
        }
        return true
    }

    private fun nextOutboundTicket(
        flow: Flow,
        udp: UdpPacket,
        outboundIpv6: OutboundIpv6?
    ): OutboundTicket {
        return OutboundTicket(
                ++flow.nextOutboundSequence,
                udp.payload,
                outboundIpv6
            )
    }

    private fun openFlow(key: FlowKey): Flow? {
        if (closed.get()) return null
        val openingGeneration = lifecycleGeneration.get()
        if (!safeBurst.admit(flows.size, currentResourcePressure())) {
            return null
        }
        val originalAddress = runCatching { InetAddress.getByAddress(key.destIp.bytes) }.getOrNull() ?: return null
        val destinations = runCatching { destinationCandidates(originalAddress) }.getOrDefault(emptyList()).distinctBy { address ->
            address.address.toList() to if (address is java.net.Inet6Address) address.scopeId else 0
        }
        if (destinations.isEmpty()) return null
        val destPort = key.destPort
        var destinationIndex = 0
        var socket: DatagramSocket? = null
        while (destinationIndex < destinations.size && socket == null) {
            socket = openSocket(destinations[destinationIndex], destPort)
            if (socket == null) destinationIndex += 1
        }
        socket ?: return null
        if (closed.get()) {
            runCatching { socket.close() }
            return null
        }

        val flow = Flow(key, socket, destinations, destinationIndex, openingGeneration)
        responsePublicationLock.withLock {
            if (closed.get() || lifecycleGeneration.get() != openingGeneration) {
                runCatching { socket.close() }
                return null
            }
            while (true) {
                val winner = flows.putIfAbsent(key, flow)
                if (winner == null) break
                if (!winner.closed && !winner.terminal) {
                    runCatching { socket.close() }
                    return winner
                }
                flows.remove(key, winner)
                if (!winner.closed) closeFlow(winner)
            }
        }
        diagnostics.recordUdpRelayFlowOpened()
        scope.launch(ioDispatcher) { readLoop(flow) }
        return flow
    }

    private fun openSocket(destination: InetAddress, port: Int): DatagramSocket? {
        val socket = runCatching { socketFactory() }.getOrNull() ?: return null
        return runCatching {
            runCatching { socket.receiveBufferSize = SOCKET_BUFFER_BYTES }
            runCatching { socket.sendBufferSize = SOCKET_BUFFER_BYTES }
            if (!protectSocket(socket)) error("protect-false")
            socket.connect(InetSocketAddress(destination, port))
            socket
        }.getOrElse {
            runCatching { socket.close() }
            null
        }
    }

    private fun armQuicProbe(
        flow: Flow,
        probe: QuicInitialClassifier.Probe,
        ticket: OutboundTicket
    ): ActiveProbe? {
        if (flow.closed || flow.terminal || flow.latestOutbound?.sequence != ticket.sequence) return null
        flow.quicProbe = probe
        flow.responseReceived = false
        flow.probeOutcomeReported.set(false)
        flow.probeJob?.cancel()
        flow.quicProbeObserved.set(probe.knownInitial)
        if (!probe.knownInitial) {
            flow.activeProbe = null
            return null
        }
        flow.probeGeneration += 1L
        return ActiveProbe(flow.probeGeneration, probe, ticket, flow.socketEpoch).also {
            flow.activeProbe = it
        }
    }

    private fun scheduleQuicProbeTimeout(flow: Flow, active: ActiveProbe) {
        val job = scope.launch {
            delay(udp443ProbeTimeoutMs.coerceAtLeast(1L))
            var nextActive: ActiveProbe? = null
            var exhausted = false
            synchronized(flow) {
                if (flow.closed || flow.terminal || flow.responseReceived || flow.activeProbe != active ||
                    flow.socketEpoch != active.socketEpoch ||
                    flow.latestOutbound?.sequence != active.ticket.sequence
                ) return@synchronized
                when (val retry = retryExactTicket(
                    flow,
                    flow.socket,
                    active.socketEpoch,
                    active.ticket
                )) {
                    is RetryResult.Retried -> {
                        flow.probeGeneration += 1L
                        nextActive = active.copy(
                            generation = flow.probeGeneration,
                            socketEpoch = retry.socketEpoch
                        ).also { flow.activeProbe = it }
                    }
                    RetryResult.Stale, RetryResult.Ambiguous -> Unit
                    is RetryResult.Exhausted -> {
                        flow.activeProbe = null
                        exhausted = true
                    }
                }
            }
            nextActive?.let { scheduleQuicProbeTimeout(flow, it) }
            if (exhausted) {
                reportUdp443Unreachable(flow.key.destIp, flow)
                closeFlow(flow)
            }
        }
        synchronized(flow) {
            if (!flow.closed && !flow.terminal && !flow.responseReceived && flow.activeProbe == active) {
                flow.probeJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun readLoop(flow: Flow) {
        val buffer = ByteArray(RECEIVE_BUFFER)
        val datagram = DatagramPacket(buffer, buffer.size)
        try {
            while (!flow.closed && scope.isActive) {
                datagram.length = buffer.size
                val receiveState = synchronized(flow) { flow.socket to flow.socketEpoch }
                val receivingSocket = receiveState.first
                val receivingEpoch = receiveState.second
                try {
                    receivingSocket.receive(datagram)
                } catch (_: PortUnreachableException) {
                    when (val retry = retryAsynchronousError(flow, receivingSocket, receivingEpoch)) {
                        is RetryResult.Retried -> {
                            rearmProbeAfterRetry(flow, retry)
                            continue
                        }
                        RetryResult.Stale, RetryResult.Ambiguous -> continue
                        is RetryResult.Exhausted -> {
                            diagnostics.recordUdpRelayFlowDropped()
                            retry.outbound?.let(::emitIpv6PortUnreachable)
                            break
                        }
                    }
                } catch (error: Exception) {
                    if (receivingSocket !== flow.socket) continue
                    throw error
                }
                val len = datagram.length
                if (len <= 0) continue
                val maxPayload = if (flow.key.destIp.isV6) {
                    MAX_IPV6_UDP_PAYLOAD
                } else {
                    MAX_IPV4_UDP_PAYLOAD
                }
                var oversizedResponse = false
                var reportReachable = false
                val currentResponse = synchronized(flow) {
                    if (flow.closed || closed.get() || flow.socket !== receivingSocket ||
                        flow.socketEpoch != receivingEpoch || flow.terminal ||
                        lifecycleGeneration.get() != flow.lifecycle
                    ) {
                        false
                    } else {
                        if (flow.errorProvenance is ErrorProvenance.Unique) {
                            flow.errorProvenance = ErrorProvenance.Ambiguous
                        }
                        flow.touch()
                        if (len > maxPayload) {
                            oversizedResponse = true
                        } else {
                            val active = flow.activeProbe
                            val responseType = active?.probe?.let { probe ->
                                QuicServerResponseClassifier.classify(buffer, 0, len, probe)
                            }
                            if (active != null && !flow.responseReceived && responseType != null) {
                                flow.responseReceived = true
                                flow.activeProbe = null
                                flow.probeJob?.cancel()
                                reportReachable = flow.probeOutcomeReported.compareAndSet(false, true)
                            }
                        }
                        true
                    }
                }
                if (!currentResponse) continue
                if (oversizedResponse) {
                    diagnostics.recordUdpRelayOversizeDropped()
                    continue
                }
                val reply = buildResponsePacket(flow, buffer, len)
                val packets = if (flow.key.destIp.isV6) {
                    Ipv6PacketFragmenter.fragment(reply, tunMtu, fragmentIdentification.getAndIncrement())
                        ?: throw IllegalStateException("ipv6-fragmentation-failed")
                } else {
                    Ipv4PacketFragmenter.fragment(reply, tunMtu, fragmentIdentification.getAndIncrement())
                        ?: throw IllegalStateException("ipv4-fragmentation-failed")
                }
                if (!writeResponsePackets(flow, receivingSocket, receivingEpoch, packets) {
                        diagnostics.recordUdpRelayForwardDown(len)
                        if (reportReachable) {
                            runCatching { onUdp443Reachable(flow.key.destIp.bytes.copyOf()) }
                        }
                    }
                ) continue
            }
        } catch (_: Exception) {
            if (!flow.closed) diagnostics.recordUdpRelayFlowDropped()
        } finally {
            if (flow.quicProbeObserved.get() && !flow.responseReceived && !flow.closed) {
                reportUdp443Unreachable(flow.key.destIp, flow)
            }
            closeFlow(flow)
        }
    }

    private fun writeResponsePackets(
        flow: Flow,
        receivingSocket: DatagramSocket,
        receivingEpoch: Long,
        packets: List<ByteArray>,
        commit: () -> Unit
    ): Boolean {
        packets.forEach { packet ->
            if (!publishResponsePacket(flow, receivingSocket, receivingEpoch, packet)) return false
        }
        return responsePublicationLock.withLock {
            if (lifecycleTransitionsPending.get() != 0 ||
                !isCurrentResponse(flow, receivingSocket, receivingEpoch)
            ) {
                false
            } else {
                commit()
                true
            }
        }
    }

    private fun publishResponsePacket(
        flow: Flow,
        receivingSocket: DatagramSocket,
        receivingEpoch: Long,
        packet: ByteArray
    ): Boolean {
        if (lifecycleTransitionsPending.get() != 0) return false
        return responsePublicationLock.withLock {
            if (lifecycleTransitionsPending.get() != 0 ||
                !isCurrentResponse(flow, receivingSocket, receivingEpoch)
            ) {
                false
            } else {
                writeTunPacket(packet)
                true
            }
        }
    }

    private fun isCurrentResponse(
        flow: Flow,
        receivingSocket: DatagramSocket,
        receivingEpoch: Long
    ): Boolean = synchronized(flow) {
        !flow.closed && !flow.terminal && !closed.get() &&
            flow.socket === receivingSocket && flow.socketEpoch == receivingEpoch &&
            lifecycleGeneration.get() == flow.lifecycle
    }

    private fun retryAsynchronousError(
        flow: Flow,
        failedSocket: DatagramSocket,
        expectedSocketEpoch: Long
    ): RetryResult {
        synchronized(flow) {
            if (flow.closed || flow.terminal || closed.get()) return RetryResult.Stale
            if (flow.socket !== failedSocket || flow.socketEpoch != expectedSocketEpoch) {
                return RetryResult.Stale
            }
            val provenance = flow.errorProvenance
            if (provenance !is ErrorProvenance.Unique) {
                return if (provenance === ErrorProvenance.Ambiguous) {
                    advanceAfterAmbiguousError(flow, failedSocket, expectedSocketEpoch)
                } else {
                    RetryResult.Stale
                }
            }
            return retryExactTicket(flow, failedSocket, expectedSocketEpoch, provenance.ticket)
        }
    }

    private fun advanceAfterAmbiguousError(
        flow: Flow,
        failedSocket: DatagramSocket,
        expectedSocketEpoch: Long
    ): RetryResult {
        synchronized(flow) {
            if (flow.closed || flow.terminal || closed.get()) return RetryResult.Stale
            if (flow.socket !== failedSocket || flow.socketEpoch != expectedSocketEpoch ||
                flow.errorProvenance !== ErrorProvenance.Ambiguous
            ) return RetryResult.Stale
            var nextIndex = flow.destinationIndex + 1
            while (nextIndex < flow.destinations.size) {
                val replacement = openSocket(flow.destinations[nextIndex], flow.key.destPort)
                if (replacement == null) {
                    nextIndex += 1
                    continue
                }
                val previous = flow.socket
                flow.socket = replacement
                flow.socketEpoch += 1L
                flow.destinationIndex = nextIndex
                flow.errorProvenance = ErrorProvenance.None
                flow.activeProbe = null
                flow.probeJob?.cancel()
                previous.close()
                return RetryResult.Ambiguous
            }
            flow.terminal = true
            return RetryResult.Exhausted(null)
        }
    }

    private fun rearmProbeAfterRetry(flow: Flow, retry: RetryResult.Retried) {
        val active = synchronized(flow) {
            val current = flow.activeProbe ?: return
            if (flow.closed || flow.terminal || flow.responseReceived ||
                current.ticket.sequence != retry.ticket.sequence ||
                flow.latestOutbound?.sequence != retry.ticket.sequence ||
                flow.socketEpoch != retry.socketEpoch
            ) return
            flow.probeJob?.cancel()
            flow.probeGeneration += 1L
            current.copy(
                generation = flow.probeGeneration,
                socketEpoch = retry.socketEpoch
            ).also { flow.activeProbe = it }
        }
        scheduleQuicProbeTimeout(flow, active)
    }

    private fun retryExactTicket(
        flow: Flow,
        failedSocket: DatagramSocket,
        expectedSocketEpoch: Long,
        ticket: OutboundTicket
    ): RetryResult {
        synchronized(flow) {
            if (flow.closed || flow.terminal || closed.get()) return RetryResult.Stale
            if (flow.socket !== failedSocket || flow.socketEpoch != expectedSocketEpoch ||
                flow.latestOutbound?.sequence != ticket.sequence
            ) return RetryResult.Stale
            var nextIndex = flow.destinationIndex + 1
            while (nextIndex < flow.destinations.size) {
                val replacement = openSocket(flow.destinations[nextIndex], flow.key.destPort)
                if (replacement == null) {
                    nextIndex += 1
                    continue
                }
                val previous = flow.socket
                flow.socket = replacement
                flow.socketEpoch += 1L
                flow.destinationIndex = nextIndex
                flow.errorProvenance = ErrorProvenance.Unique(ticket)
                previous.close()
                val failure = runCatching {
                    replacement.send(DatagramPacket(ticket.payload, ticket.payload.size))
                    flow.touch()
                }.exceptionOrNull()
                if (failure == null) return RetryResult.Retried(ticket, flow.socketEpoch)
                replacement.close()
                nextIndex += 1
            }
            flow.terminal = true
            return RetryResult.Exhausted(ticket.ipv6)
        }
    }

    private fun buildResponsePacket(flow: Flow, payload: ByteArray, length: Int): ByteArray {
        return if (flow.key.destIp.isV6) {
            buildIpv6UdpPacket(
                sourceIp = flow.key.destIp.bytes,
                destinationIp = flow.key.clientIp.bytes,
                sourcePort = flow.key.destPort,
                destinationPort = flow.key.clientPort,
                payload = payload,
                payloadLength = length
            )
        } else {
            buildIpv4UdpPacket(
                sourceIp = flow.key.destIp.bytes,
                destinationIp = flow.key.clientIp.bytes,
                sourcePort = flow.key.destPort,
                destinationPort = flow.key.clientPort,
                payload = payload,
                payloadLength = length
            )
        }
    }

    private fun emitIpv6PortUnreachable(outbound: OutboundIpv6) {
        if (!allowIcmpv6Error()) return
        Ipv6IcmpPortUnreachable.build(
            outbound.validatedPacket,
            outbound.validatedPacket.size,
            outbound.invokingPacket
        )?.let { response ->
            runCatching { writeTunPacket(response) }
        }
    }

    private fun reapIdleFlows() {
        val now = clock()
        flows.values.forEach { flow ->
            val shouldClose = responsePublicationLock.withLock {
                synchronized(flow) {
                    if (flow.closed || now - flow.lastActivityMs < idleTimeoutMs) {
                        false
                    } else {
                        flow.closed = true
                        flow.terminal = true
                        true
                    }
                }
            }
            if (shouldClose) finishCloseFlow(flow)
        }
    }

    private fun closeFlow(flow: Flow) {
        val shouldClose = responsePublicationLock.withLock {
            synchronized(flow) {
                if (flow.closed) {
                    false
                } else {
                    flow.closed = true
                    flow.terminal = true
                    true
                }
            }
        }
        if (!shouldClose) return
        finishCloseFlow(flow)
    }

    private fun finishCloseFlow(flow: Flow) {
        flows.remove(flow.key, flow)
        flow.probeJob?.cancel()
        runCatching { flow.socket.close() }
        diagnostics.recordUdpRelayFlowClosed()
    }

    private fun reportUdp443Unreachable(destination: IpAddr, flow: Flow?) {
        if (flow != null && !flow.probeOutcomeReported.compareAndSet(false, true)) return
        runCatching { onUdp443Unreachable(destination.bytes.copyOf()) }
    }

    private fun currentResourcePressure(): ResourcePressure {
        return ResourcePressure(
            lowMemory = false,
            thermalSevere = false,
            fileDescriptorPressure = false,
            queueUtilization = flows.size.toDouble() / MAX_FLOWS,
            timeoutRate = 0.0,
            handoverActive = false,
            recovering = false,
            riskLevel = RiskLevel.NORMAL
        )
    }

    fun resetFlows() {
        if (closed.get()) return
        lifecycleTransitionsPending.incrementAndGet()
        val snapshot = try {
            responsePublicationLock.withLock {
                if (closed.get()) return
                lifecycleGeneration.incrementAndGet()
                snapshotFlows()
            }
        } finally {
            lifecycleTransitionsPending.decrementAndGet()
        }
        snapshot.forEach { flow -> closeFlow(flow) }
    }

    fun closeAll() {
        if (closed.get()) return
        lifecycleTransitionsPending.incrementAndGet()
        val snapshot = try {
            responsePublicationLock.withLock {
                if (!closed.compareAndSet(false, true)) return
                lifecycleGeneration.incrementAndGet()
                snapshotFlows()
            }
        } finally {
            lifecycleTransitionsPending.decrementAndGet()
        }
        sweepJob.cancel()
        snapshot.forEach { flow -> closeFlow(flow) }
        runCatching { ioExecutor.shutdownNow() }
    }

    private fun snapshotFlows(): List<Flow> {
        val snapshot = ArrayList<Flow>(flows.size)
        flows.forEach { _, flow -> snapshot += flow }
        return snapshot
    }

    private data class UdpPacket(
        val sourceIp: IpAddr,
        val destIp: IpAddr,
        val sourcePort: Int,
        val destPort: Int,
        val payload: ByteArray
    )

    private fun parseUdp(packet: ByteArray, length: Int): UdpPacket? {
        if (length !in 1..packet.size) return null
        return when ((packet[0].toInt() ushr 4) and 0x0f) {
            4 -> parseIpv4Udp(packet, length)
            6 -> parseIpv6Udp(packet, length)
            else -> null
        }
    }

    private fun parseIpv4Udp(packet: ByteArray, length: Int): UdpPacket? {
        if (length < IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH) return null
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < IPV4_HEADER_LENGTH || length < ihl + UDP_HEADER_LENGTH) return null
        if ((packet[9].toInt() and 0xff) != UDP_PROTOCOL) return null

        val fragmentField = u16(packet, 6)
        if (fragmentField and 0x2000 != 0 || fragmentField and 0x1fff != 0) return null
        val totalLength = u16(packet, 2)
        val udpOffset = ihl
        if (totalLength != length || totalLength < udpOffset + UDP_HEADER_LENGTH) return null

        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_LENGTH || udpLength != totalLength - udpOffset) return null
        val payloadOffset = udpOffset + UDP_HEADER_LENGTH
        val payloadEnd = udpOffset + udpLength
        return UdpPacket(
            sourceIp = IpAddr(packet.copyOfRange(12, 16)),
            destIp = IpAddr(packet.copyOfRange(16, 20)),
            sourcePort = u16(packet, udpOffset),
            destPort = u16(packet, udpOffset + 2),
            payload = packet.copyOfRange(payloadOffset, payloadEnd)
        )
    }

    private fun parseIpv6Udp(packet: ByteArray, length: Int): UdpPacket? {
        if (length < IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH) return null

        if ((packet[6].toInt() and 0xff) != UDP_PROTOCOL) return null
        val payloadLength = u16(packet, 4)
        val totalLength = IPV6_HEADER_LENGTH + payloadLength
        val udpOffset = IPV6_HEADER_LENGTH
        if (totalLength != length || totalLength < udpOffset + UDP_HEADER_LENGTH) return null
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_LENGTH || udpLength != payloadLength) return null
        if (!Ipv6TransportChecksum.isValid(
                packet = packet,
                packetLength = length,
                transportOffset = udpOffset,
                transportLength = udpLength,
                protocol = UDP_PROTOCOL,
                rejectZeroChecksumAt = UDP_CHECKSUM_OFFSET
            )
        ) return null
        val payloadOffset = udpOffset + UDP_HEADER_LENGTH
        val payloadEnd = udpOffset + udpLength
        val source = packet.copyOfRange(8, 24)
        val destination = packet.copyOfRange(24, 40)
        if (!Ipv6FullForwardRoutePolicy.isProxyableUnicast(source) ||
            !Ipv6FullForwardRoutePolicy.isProxyableUnicast(destination)
        ) return null
        return UdpPacket(
            sourceIp = IpAddr(source),
            destIp = IpAddr(destination),
            sourcePort = u16(packet, udpOffset),
            destPort = u16(packet, udpOffset + 2),
            payload = packet.copyOfRange(payloadOffset, payloadEnd)
        )
    }

    companion object {
        private const val DNS_PORT = 53
        private const val HTTPS_PORT = 443
        private const val UDP_PROTOCOL = 17
        private const val IPV4_HEADER_LENGTH = 20
        private const val IPV6_HEADER_LENGTH = 40
        private const val UDP_HEADER_LENGTH = 8
        private const val UDP_CHECKSUM_OFFSET = 6
        private const val MAX_FLOWS = 32
        private const val UDP_SOFT_LIMIT = 28
        private const val RECEIVE_BUFFER = 65535
        private const val MAX_IPV4_UDP_PAYLOAD = 65_507
        private const val MAX_IPV6_UDP_PAYLOAD = 65_527
        private const val SOCKET_BUFFER_BYTES = 256 * 1024
        private const val SWEEP_INTERVAL_MS = 5_000L
        private const val UDP_443_PROBE_TIMEOUT_MS = 3_500L
        const val DEFAULT_IDLE_TIMEOUT_MS = 30_000L

        internal fun declaredIpv6PacketLength(packet: ByteArray): Int? {
            if (packet.size < IPV6_HEADER_LENGTH ||
                ((packet[0].toInt() ushr 4) and 0x0f) != 6
            ) return null
            val declared = IPV6_HEADER_LENGTH + u16(packet, 4)
            return declared.takeIf { it <= packet.size }
        }

        private fun buildIpv4UdpPacket(
            sourceIp: ByteArray,
            destinationIp: ByteArray,
            sourcePort: Int,
            destinationPort: Int,
            payload: ByteArray,
            payloadLength: Int
        ): ByteArray {
            val udpLength = UDP_HEADER_LENGTH + payloadLength
            val totalLength = IPV4_HEADER_LENGTH + udpLength
            val packet = ByteArray(totalLength)
            packet[0] = 0x45
            packet[1] = 0
            put16(packet, 2, totalLength)
            put16(packet, 4, 0)
            put16(packet, 6, 0)
            packet[8] = 64
            packet[9] = UDP_PROTOCOL.toByte()
            sourceIp.copyInto(packet, 12, 0, 4)
            destinationIp.copyInto(packet, 16, 0, 4)
            put16(packet, 10, ipv4Checksum(packet))

            val udpOffset = IPV4_HEADER_LENGTH
            put16(packet, udpOffset, sourcePort)
            put16(packet, udpOffset + 2, destinationPort)
            put16(packet, udpOffset + 4, udpLength)
            put16(packet, udpOffset + 6, 0)
            payload.copyInto(packet, udpOffset + UDP_HEADER_LENGTH, 0, payloadLength)
            put16(packet, udpOffset + 6, udpChecksum(packet, udpOffset, udpLength))
            return packet
        }

        private fun buildIpv6UdpPacket(
            sourceIp: ByteArray,
            destinationIp: ByteArray,
            sourcePort: Int,
            destinationPort: Int,
            payload: ByteArray,
            payloadLength: Int
        ): ByteArray {
            val udpLength = UDP_HEADER_LENGTH + payloadLength
            val totalLength = IPV6_HEADER_LENGTH + udpLength
            val packet = ByteArray(totalLength)

            packet[0] = 0x60
            put16(packet, 4, udpLength)
            packet[6] = UDP_PROTOCOL.toByte()
            packet[7] = 64
            sourceIp.copyInto(packet, 8, 0, 16)
            destinationIp.copyInto(packet, 24, 0, 16)

            val udpOffset = IPV6_HEADER_LENGTH
            put16(packet, udpOffset, sourcePort)
            put16(packet, udpOffset + 2, destinationPort)
            put16(packet, udpOffset + 4, udpLength)
            put16(packet, udpOffset + 6, 0)
            payload.copyInto(packet, udpOffset + UDP_HEADER_LENGTH, 0, payloadLength)
            put16(packet, udpOffset + 6, ipv6UdpChecksum(packet, udpOffset, udpLength))
            return packet
        }

        private fun ipv6UdpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
            var sum = 0L

            sum += checksumSum(packet, 8, 32)

            sum += udpLength.toLong()

            sum += UDP_PROTOCOL.toLong()
            sum += checksumSum(packet, udpOffset, udpLength)
            while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
            val value = sum.inv().toInt() and 0xffff
            return if (value == 0) 0xffff else value
        }

        private fun ipv4Checksum(packet: ByteArray): Int = checksum(packet, 0, IPV4_HEADER_LENGTH)

        private fun udpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
            var sum = 0L
            sum += u16(packet, 12).toLong()
            sum += u16(packet, 14).toLong()
            sum += u16(packet, 16).toLong()
            sum += u16(packet, 18).toLong()
            sum += UDP_PROTOCOL.toLong()
            sum += udpLength.toLong()
            sum += checksumSum(packet, udpOffset, udpLength)
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

        private fun u16(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        }

        private fun put16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value ushr 8) and 0xff).toByte()
            data[offset + 1] = (value and 0xff).toByte()
        }

    }
}
