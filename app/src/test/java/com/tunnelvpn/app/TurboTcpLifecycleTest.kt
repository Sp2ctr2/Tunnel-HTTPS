package com.tunnelvpn.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboTcpLifecycleTest {
    @Test
    fun duplicateSynReplaysImmutableHandshakeAcknowledgement() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val handlerStarted = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostics = DiagnosticsState()
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true,
            tcpDnsHandler = {
                handlerStarted.countDown()
                awaitCancellation()
            }
        )
        val client = byteArrayOf(10, 0, 0, 2)
        val destination = byteArrayOf(8, 8, 8, 8)
        val query = framedDnsQuery()
        val syn = tcpPacket(client, destination, 51_000, 53, 100, SYN)

        try {
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val serverInitial = u32(packets.single(), 24)
            val data = tcpPacket(
                client,
                destination,
                51_000,
                53,
                101,
                ACK or PSH,
                query,
                acknowledgement = (serverInitial + 1L).toInt()
            )
            assertTrue(forwarder.handleIpv4Packet(data, data.size))
            assertTrue(handlerStarted.await(3, TimeUnit.SECONDS))
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))

            val replay = packets.last()
            assertEquals(SYN or ACK, flags(replay))
            assertEquals(serverInitial, u32(replay, 24))
            assertEquals(101L, u32(replay, 28))
            assertEquals(1, forwarder.activeConnectionCount())
            assertEquals(1L, diagnostics.turboTcpConnectionsOpened.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun terminalFlowBecomesTombstoneAndGuardedReuseRejectsLateReset() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val serverFin = AtomicLong(-1L)
        val finWritten = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostics = DiagnosticsState()
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = { packet ->
                packets += packet
                if (flags(packet) and FIN != 0) {
                    serverFin.set(u32(packet, 24))
                    finWritten.countDown()
                }
            },
            directHttpsForwarding = true,
            tcpDnsHandler = { it }
        )
        val client = byteArrayOf(10, 0, 0, 3)
        val destination = byteArrayOf(8, 8, 4, 4)
        val query = framedDnsQuery()
        val oldSynSequence = 1_000
        val oldClientNext = oldSynSequence + 1 + query.size + 1
        val syn = tcpPacket(client, destination, 51_001, 53, oldSynSequence, SYN)

        try {
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val oldServerInitial = u32(packets.single(), 24)
            val requestAndFin = tcpPacket(
                client,
                destination,
                51_001,
                53,
                oldSynSequence + 1,
                ACK or PSH or FIN,
                query,
                acknowledgement = (oldServerInitial + 1L).toInt()
            )
            assertTrue(forwarder.handleIpv4Packet(requestAndFin, requestAndFin.size))
            assertTrue(finWritten.await(3, TimeUnit.SECONDS))
            val oldServerLargest = serverFin.get()
            val finalAck = tcpPacket(
                client,
                destination,
                51_001,
                53,
                oldClientNext,
                ACK,
                acknowledgement = (oldServerLargest + 1L).toInt()
            )
            assertTrue(forwarder.handleIpv4Packet(finalAck, finalAck.size))

            assertEquals(0, forwarder.activeConnectionCount())
            assertEquals(1, forwarder.timeWaitTombstoneCount())
            assertEquals(0L, diagnostics.turboTcpActiveConnections.get())
            assertEquals(1L, diagnostics.turboTcpTimeWaitTombstones.get())
            assertEquals(1L, diagnostics.turboTcpConnectionsClosed.get())

            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val guarded = packets.last()
            assertEquals(ACK, flags(guarded))
            assertEquals(oldClientNext.toLong(), u32(guarded, 28))
            assertEquals(0, forwarder.activeConnectionCount())
            assertEquals(1L, diagnostics.turboTcpTupleReuseGuarded.get())

            val newSynSequence = oldClientNext + 100
            val newSyn = tcpPacket(client, destination, 51_001, 53, newSynSequence, SYN)
            assertTrue(forwarder.handleIpv4Packet(newSyn, newSyn.size))
            val newSynAck = packets.last()
            val newServerInitial = u32(newSynAck, 24)
            assertEquals(SYN or ACK, flags(newSynAck))
            assertEquals((newSynSequence + 1L) and UINT_MASK, u32(newSynAck, 28))
            assertTrue(TurboTcpForwarder.isNewerIncarnationSyn(newServerInitial, oldServerLargest))
            assertEquals(1, forwarder.activeConnectionCount())
            assertEquals(0, forwarder.timeWaitTombstoneCount())
            assertEquals(1L, diagnostics.turboTcpTupleReuseAccepted.get())

            val lateReset = tcpPacket(client, destination, 51_001, 53, oldClientNext - 1, RST)
            assertTrue(forwarder.handleIpv4Packet(lateReset, lateReset.size))
            assertEquals(ACK, flags(packets.last()))
            assertEquals(1, forwarder.activeConnectionCount())
            assertEquals(1L, diagnostics.turboTcpConnectionsClosed.get())

            assertTrue(forwarder.handleIpv4Packet(newSyn, newSyn.size))
            val replay = packets.last()
            assertEquals(SYN or ACK, flags(replay))
            assertEquals((newSynSequence + 1L) and UINT_MASK, u32(replay, 28))

            val exactReset = tcpPacket(client, destination, 51_001, 53, newSynSequence + 1, RST)
            assertTrue(forwarder.handleIpv4Packet(exactReset, exactReset.size))
            assertEquals(0, forwarder.activeConnectionCount())
            assertEquals(2L, diagnostics.turboTcpConnectionsClosed.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun oneSidedHalfCloseRetainsActiveOwnership() {
        val handlerStarted = CountDownLatch(1)
        val packets = CopyOnWriteArrayList<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostics = DiagnosticsState()
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true,
            tcpDnsHandler = {
                handlerStarted.countDown()
                awaitCancellation()
            }
        )
        val client = byteArrayOf(10, 0, 0, 4)
        val destination = byteArrayOf(1, 1, 1, 1)
        val query = framedDnsQuery()

        try {
            val syn = tcpPacket(client, destination, 51_002, 53, 300, SYN)
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val serverInitial = u32(packets.single(), 24)
            val requestAndFin = tcpPacket(
                client,
                destination,
                51_002,
                53,
                301,
                ACK or PSH or FIN,
                query,
                acknowledgement = (serverInitial + 1L).toInt()
            )
            assertTrue(forwarder.handleIpv4Packet(requestAndFin, requestAndFin.size))
            assertTrue(handlerStarted.await(3, TimeUnit.SECONDS))
            assertEquals(1, forwarder.activeConnectionCount())
            assertEquals(0, forwarder.timeWaitTombstoneCount())
            assertEquals(1L, diagnostics.turboTcpActiveConnections.get())
            assertEquals(0L, diagnostics.turboTcpConnectionsClosed.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun repeatedThirtyTwoFlowBatchesReleaseCapacityExactlyOnce() {
        val serverInitials = ConcurrentHashMap<Int, Long>()
        val serverFins = ConcurrentHashMap<Int, Long>()
        val finLatches = ConcurrentHashMap<Int, CountDownLatch>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostics = DiagnosticsState()
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = { packet ->
                val port = u16(packet, 22)
                if (flags(packet) and SYN != 0) serverInitials[port] = u32(packet, 24)
                if (flags(packet) and FIN != 0) {
                    serverFins[port] = u32(packet, 24)
                    finLatches[port]?.countDown()
                }
            },
            directHttpsForwarding = true,
            tcpDnsHandler = { it }
        )
        val client = byteArrayOf(10, 0, 0, 5)
        val destination = byteArrayOf(9, 9, 9, 9)
        val query = framedDnsQuery()

        try {
            repeat(2) { batch ->
                val ports = (0 until 32).map { 52_000 + batch * 32 + it }
                ports.forEach { port ->
                    finLatches[port] = CountDownLatch(1)
                    val syn = tcpPacket(client, destination, port, 53, port, SYN)
                    assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
                }
                assertEquals(32, forwarder.activeConnectionCount())
                ports.forEach { port ->
                    val serverInitial = serverInitials.getValue(port)
                    val requestAndFin = tcpPacket(
                        client,
                        destination,
                        port,
                        53,
                        port + 1,
                        ACK or PSH or FIN,
                        query,
                        acknowledgement = (serverInitial + 1L).toInt()
                    )
                    assertTrue(forwarder.handleIpv4Packet(requestAndFin, requestAndFin.size))
                }
                ports.forEach { port ->
                    assertTrue(finLatches.getValue(port).await(3, TimeUnit.SECONDS))
                    val clientNext = port + 1 + query.size + 1
                    val finalAck = tcpPacket(
                        client,
                        destination,
                        port,
                        53,
                        clientNext,
                        ACK,
                        acknowledgement = (serverFins.getValue(port) + 1L).toInt()
                    )
                    assertTrue(forwarder.handleIpv4Packet(finalAck, finalAck.size))
                }
                assertEquals(0, forwarder.activeConnectionCount())
                assertEquals((batch + 1) * 32, forwarder.timeWaitTombstoneCount())
            }

            assertEquals(64L, diagnostics.turboTcpConnectionsOpened.get())
            assertEquals(64L, diagnostics.turboTcpConnectionsClosed.get())
            assertEquals(0L, diagnostics.turboTcpActiveConnections.get())
            assertEquals(64L, diagnostics.turboTcpTimeWaitTombstones.get())
            assertEquals(0L, diagnostics.turboTcpCapacityRejected.get())
            forwarder.closeAll()
            assertEquals(64L, diagnostics.turboTcpConnectionsClosed.get())
            assertEquals(0L, diagnostics.turboTcpTimeWaitTombstones.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun resetEmissionCannotFollowReplacementSynAck() {
        val resetEntered = CountDownLatch(1)
        val releaseReset = CountDownLatch(1)
        val replacementWritten = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val synAckCount = AtomicInteger()
        val packetFlags = CopyOnWriteArrayList<Int>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostics = DiagnosticsState()
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = { packet ->
                val value = flags(packet)
                packetFlags += value
                if (value and RST != 0) {
                    resetEntered.countDown()
                    releaseReset.await(3, TimeUnit.SECONDS)
                }
                if (value and SYN != 0 && synAckCount.incrementAndGet() == 2) replacementWritten.countDown()
            },
            directHttpsForwarding = true,
            tcpDnsHandler = { awaitCancellation() }
        )
        val client = byteArrayOf(10, 0, 0, 6)
        val destination = byteArrayOf(4, 4, 4, 4)
        val firstSyn = tcpPacket(client, destination, 51_003, 53, 400, SYN)
        val replacementSyn = tcpPacket(client, destination, 51_003, 53, 800, SYN)

        try {
            assertTrue(forwarder.handleIpv4Packet(firstSyn, firstSyn.size))
            val resetThread = Thread { forwarder.resetConnections() }.apply { start() }
            assertTrue(resetEntered.await(3, TimeUnit.SECONDS))
            val replacementThread = Thread {
                replacementStarted.countDown()
                forwarder.handleIpv4Packet(replacementSyn, replacementSyn.size)
            }.apply { start() }
            assertTrue(replacementStarted.await(3, TimeUnit.SECONDS))
            assertFalse(replacementWritten.await(100, TimeUnit.MILLISECONDS))
            releaseReset.countDown()
            resetThread.join(3_000L)
            replacementThread.join(3_000L)
            assertFalse(resetThread.isAlive)
            assertFalse(replacementThread.isAlive)
            assertTrue(replacementWritten.await(3, TimeUnit.SECONDS))
            assertEquals(RST, packetFlags[1] and RST)
            assertEquals(SYN or ACK, packetFlags.last())
            assertEquals(1, forwarder.activeConnectionCount())
            assertEquals(2L, diagnostics.turboTcpConnectionsOpened.get())
            assertEquals(1L, diagnostics.turboTcpConnectionsClosed.get())
        } finally {
            releaseReset.countDown()
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun resetGenerationCannotPublishTerminalTombstone() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val serverFin = AtomicLong(-1L)
        val finWritten = CountDownLatch(1)
        val resetEntered = CountDownLatch(1)
        val releaseReset = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostics = DiagnosticsState()
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = { packet ->
                packets += packet
                if (flags(packet) and FIN != 0) {
                    serverFin.set(u32(packet, 24))
                    finWritten.countDown()
                }
                if (flags(packet) and RST != 0) {
                    resetEntered.countDown()
                    releaseReset.await(3, TimeUnit.SECONDS)
                }
            },
            directHttpsForwarding = true,
            tcpDnsHandler = { it }
        )
        val client = byteArrayOf(10, 0, 0, 7)
        val destination = byteArrayOf(8, 8, 8, 8)
        val query = framedDnsQuery()
        val synSequence = 1_500

        try {
            val syn = tcpPacket(client, destination, 51_004, 53, synSequence, SYN)
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val serverInitial = u32(packets.single(), 24)
            val requestAndFin = tcpPacket(
                client,
                destination,
                51_004,
                53,
                synSequence + 1,
                ACK or PSH or FIN,
                query,
                acknowledgement = (serverInitial + 1L).toInt()
            )
            assertTrue(forwarder.handleIpv4Packet(requestAndFin, requestAndFin.size))
            assertTrue(finWritten.await(3, TimeUnit.SECONDS))
            val resetThread = Thread { forwarder.resetConnections() }.apply { start() }
            assertTrue(resetEntered.await(3, TimeUnit.SECONDS))
            val finalAck = tcpPacket(
                client,
                destination,
                51_004,
                53,
                synSequence + 1 + query.size + 1,
                ACK,
                acknowledgement = (serverFin.get() + 1L).toInt()
            )
            val ackThread = Thread { forwarder.handleIpv4Packet(finalAck, finalAck.size) }.apply { start() }
            assertTrue(ackThread.isAlive)
            assertEquals(0, forwarder.timeWaitTombstoneCount())
            releaseReset.countDown()
            resetThread.join(3_000L)
            ackThread.join(3_000L)
            assertFalse(resetThread.isAlive)
            assertFalse(ackThread.isAlive)
            assertEquals(0, forwarder.activeConnectionCount())
            assertEquals(0, forwarder.timeWaitTombstoneCount())
            assertEquals(0L, diagnostics.turboTcpTimeWaitTombstones.get())
            assertEquals(1L, diagnostics.turboTcpConnectionsClosed.get())
        } finally {
            releaseReset.countDown()
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun serialGuardsHandleWraparound() {
        assertTrue(TurboTcpForwarder.isNewerIncarnationSyn(1L, 0xffff_fffeL))
        assertFalse(TurboTcpForwarder.isNewerIncarnationSyn(0xffff_fffeL, 1L))
        assertFalse(TurboTcpForwarder.isNewerIncarnationSyn(7L, 7L))
        assertFalse(TurboTcpForwarder.isNewerIncarnationSyn(0x8000_0000L, 0L))
        assertTrue(TurboTcpForwarder.isAcceptableReset(0L, 0L))
        assertFalse(TurboTcpForwarder.isAcceptableReset(0xffff_ffffL, 0L))
        val selected = TurboTcpForwarder.selectServerInitialSequence(0xffff_ff00L, 0xffff_fff0L)
        assertTrue(TurboTcpForwarder.isNewerIncarnationSyn(selected, 0xffff_fff0L))
    }

    private fun framedDnsQuery(): ByteArray {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x4411)!!
        return ByteArray(query.size + 2).also {
            put16(it, 0, query.size)
            query.copyInto(it, 2)
        }
    }

    private fun tcpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Int,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        acknowledgement: Int = 0,
        window: Int = 65_535
    ): ByteArray {
        val packet = ByteArray(40 + payload.size)
        packet[0] = 0x45
        put16(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 6
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)
        put16(packet, 20, sourcePort)
        put16(packet, 22, destinationPort)
        put32(packet, 24, sequence)
        put32(packet, 28, acknowledgement)
        packet[32] = 0x50
        packet[33] = flags.toByte()
        put16(packet, 34, window)
        payload.copyInto(packet, 40)
        return packet
    }

    private fun flags(packet: ByteArray): Int = packet[33].toInt() and 0xff

    private fun u16(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
    }

    private fun u32(packet: ByteArray, offset: Int): Long {
        return ((packet[offset].toLong() and 0xffL) shl 24) or
            ((packet[offset + 1].toLong() and 0xffL) shl 16) or
            ((packet[offset + 2].toLong() and 0xffL) shl 8) or
            (packet[offset + 3].toLong() and 0xffL)
    }

    private fun put16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value ushr 8) and 0xff).toByte()
        packet[offset + 1] = (value and 0xff).toByte()
    }

    private fun put32(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value ushr 24) and 0xff).toByte()
        packet[offset + 1] = ((value ushr 16) and 0xff).toByte()
        packet[offset + 2] = ((value ushr 8) and 0xff).toByte()
        packet[offset + 3] = (value and 0xff).toByte()
    }

    companion object {
        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val PSH = 0x08
        private const val ACK = 0x10
        private const val UINT_MASK = 0xffff_ffffL
    }
}
