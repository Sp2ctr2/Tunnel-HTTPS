package com.tunnelvpn.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboUdpForwarderTest {
    @Test
    fun flowCreationFailuresStayIsolatedToTheDatagram() {
        val packet = ipv4Packet(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), 4444, byteArrayOf(1))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarders = listOf<TurboUdpForwarder>(
            TurboUdpForwarder(
                protectSocket = { true },
                diagnostics = DiagnosticsState(),
                scope = scope,
                writeTunPacket = {},
                destinationCandidates = { error("candidate failure") }
            ),
            TurboUdpForwarder(
                protectSocket = { true },
                diagnostics = DiagnosticsState(),
                scope = scope,
                writeTunPacket = {},
                socketFactory = { error("socket failure") }
            )
        )
        try {
            forwarders.forEach { forwarder ->
                assertTrue(forwarder.handleIpv4Packet(packet, packet.size))
            }
        } finally {
            forwarders.forEach(TurboUdpForwarder::closeAll)
            scope.cancel()
        }
    }

    @Test
    fun oldSocketErrorMustNotRetryNewCommittedPayload() {
        val oldReceiveObserved = CountDownLatch(1)
        val releaseOldError = CountDownLatch(1)
        val replacementReceiveObserved = CountDownLatch(1)
        val releaseReplacementReceive = CountDownLatch(1)
        val socketCount = AtomicInteger()
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, ByteArray>>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            destinationCandidates = {
                listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2"))
            },
            clock = { System.nanoTime() / 1_000_000L },
            socketFactory = {
                val socketId = socketCount.incrementAndGet()
                ScriptedDatagramSocket(
                    onSend = { sent += socketId to it },
                    onReceive = {
                        if (socketId == 1) {
                            oldReceiveObserved.countDown()
                            releaseOldError.await(3, TimeUnit.SECONDS)
                            throw PortUnreachableException("old socket error")
                        }
                        replacementReceiveObserved.countDown()
                        releaseReplacementReceive.await(3, TimeUnit.SECONDS)
                    },
                    onClose = {
                        if (socketId == 2) releaseReplacementReceive.countDown()
                    }
                )
            }
        )
        val destination = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val first = ipv4Packet(destination, 4444, byteArrayOf(1))
        val committed = ipv4Packet(destination, 4444, byteArrayOf(2))
        val next = ipv4Packet(destination, 4444, byteArrayOf(3))

        try {
            assertTrue(forwarder.handleIpv4Packet(first, first.size))
            assertTrue(oldReceiveObserved.await(3, TimeUnit.SECONDS))
            assertTrue(forwarder.handleIpv4Packet(committed, committed.size))
            assertEquals(2, sent.size)

            releaseOldError.countDown()
            assertTrue(replacementReceiveObserved.await(3, TimeUnit.SECONDS))
            assertEquals(2, socketCount.get())
            assertEquals(2, sent.size)

            assertTrue(forwarder.handleIpv4Packet(next, next.size))
            assertEquals(3, sent.size)
            assertEquals(listOf(1, 1, 2), sent.map { it.first })
            assertArrayEquals(byteArrayOf(1), sent[0].second)
            assertArrayEquals(byteArrayOf(2), sent[1].second)
            assertArrayEquals(byteArrayOf(3), sent[2].second)
        } finally {
            releaseOldError.countDown()
            releaseReplacementReceive.countDown()
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun quicProbeResponseMarksDestinationReachable() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(1_500), 1_500)
                server.receive(input)
                val response = quicServerInitial()
                server.send(DatagramPacket(response, response.size, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val reachable = CountDownLatch(1)
        val unreachable = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            udp443ProbeTimeoutMs = 1_000L,
            httpsPort = server.localPort,
            onUdp443Reachable = { reachable.countDown() },
            onUdp443Unreachable = { unreachable.incrementAndGet() },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, quicInitial())

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(reachable.await(3, TimeUnit.SECONDS))
            assertEquals(0, unreachable.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun quicRetryResponseMarksDestinationReachableWithoutWaitingForTheProbeTimeout() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(1_500), 1_500)
                server.receive(input)
                val response = quicServerRetry()
                server.send(DatagramPacket(response, response.size, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val reachable = CountDownLatch(1)
        val unreachable = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            udp443ProbeTimeoutMs = 1_000L,
            httpsPort = server.localPort,
            onUdp443Reachable = { reachable.countDown() },
            onUdp443Unreachable = { unreachable.incrementAndGet() },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, quicInitial())

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(reachable.await(500, TimeUnit.MILLISECONDS))
            assertEquals(0, unreachable.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun arbitraryUdpResponseDoesNotMarkAQuicProbeReachable() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(1_500), 1_500)
                server.receive(input)
                val response = "HTTP/1.1 403".toByteArray()
                server.send(DatagramPacket(response, response.size, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val unreachable = CountDownLatch(1)
        val reachableCount = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            udp443ProbeTimeoutMs = 75L,
            httpsPort = server.localPort,
            onUdp443Reachable = { reachableCount.incrementAndGet() },
            onUdp443Unreachable = { unreachable.countDown() },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, quicInitial())

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(unreachable.await(3, TimeUnit.SECONDS))
            assertEquals(0, reachableCount.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun udpPortRejectionFallsThroughToTheNextDestinationCandidate() {
        val responderAddress = InetAddress.getByName("127.0.0.1")
        val rejectedAddress = InetAddress.getByName("127.0.0.2")
        val server = DatagramSocket(0, responderAddress)
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(64), 64)
                server.receive(input)
                val payload = "alternate".toByteArray()
                server.send(DatagramPacket(payload, payload.size, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val reply = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet ->
                if (packet.copyOfRange(28, packet.size).contentEquals("alternate".toByteArray())) reply.countDown()
            },
            destinationCandidates = { listOf(rejectedAddress, responderAddress) },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), server.localPort, byteArrayOf(1))

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(reply.await(3, TimeUnit.SECONDS))
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun quicBlackholeRetriesTheNextDestinationCandidate() {
        val responderAddress = InetAddress.getByName("127.0.0.1")
        val blackholeAddress = InetAddress.getByName("127.0.0.2")
        val responder = DatagramSocket(0, responderAddress)
        val blackhole = DatagramSocket(responder.localPort, blackholeAddress)
        val blackholeThread = Thread {
            runCatching { blackhole.receive(DatagramPacket(ByteArray(1_500), 1_500)) }
        }.apply { isDaemon = true; start() }
        val responderThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(1_500), 1_500)
                responder.receive(input)
                val payload = quicServerInitial()
                responder.send(DatagramPacket(payload, payload.size, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val reachable = CountDownLatch(1)
        val unreachable = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            udp443ProbeTimeoutMs = 50L,
            httpsPort = responder.localPort,
            destinationCandidates = { listOf(blackholeAddress, responderAddress) },
            onUdp443Reachable = { reachable.countDown() },
            onUdp443Unreachable = { unreachable.incrementAndGet() },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), responder.localPort, quicInitial())

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(reachable.await(3, TimeUnit.SECONDS))
            assertEquals(0, unreachable.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            blackhole.close()
            responder.close()
            blackholeThread.join(1_000L)
            responderThread.join(1_000L)
        }
    }

    @Test
    fun unansweredQuicProbeMarksDestinationUnreachableOnce() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val serverThread = Thread {
            runCatching { server.receive(DatagramPacket(ByteArray(1_500), 1_500)) }
        }.apply { isDaemon = true; start() }
        val unreachable = CountDownLatch(1)
        val count = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            udp443ProbeTimeoutMs = 50L,
            httpsPort = server.localPort,
            onUdp443Unreachable = { count.incrementAndGet(); unreachable.countDown() },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, quicInitial())

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(unreachable.await(3, TimeUnit.SECONDS))
            Thread.sleep(100L)
            assertEquals(1, count.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun nonQuicUdp443DoesNotContaminateQuicReachability() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val serverThread = Thread {
            runCatching { server.receive(DatagramPacket(ByteArray(64), 64)) }
        }.apply { isDaemon = true; start() }
        val outcomes = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            udp443ProbeTimeoutMs = 25L,
            httpsPort = server.localPort,
            onUdp443Reachable = { outcomes.incrementAndGet() },
            onUdp443Unreachable = { outcomes.incrementAndGet() },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, byteArrayOf(1, 2, 3))

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            Thread.sleep(100L)
            assertEquals(0, outcomes.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun futureQuicVersionIsNotTimedOutUsingVersionSpecificAssumptions() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val received = CountDownLatch(1)
        val serverThread = Thread {
            runCatching {
                server.receive(DatagramPacket(ByteArray(1_500), 1_500))
                received.countDown()
            }
        }.apply { isDaemon = true; start() }
        val outcomes = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            udp443ProbeTimeoutMs = 25L,
            httpsPort = server.localPort,
            onUdp443Reachable = { outcomes.incrementAndGet() },
            onUdp443Unreachable = { outcomes.incrementAndGet() },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, futureQuicLongHeader())

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            Thread.sleep(100L)
            assertEquals(0, outcomes.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun ipv6UdpPayloadAndResponseAreRelayedWithAValidChecksum() {
        val loopback = InetAddress.getByName("::1")
        val server = DatagramSocket(0, loopback)
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(32), 32)
                server.receive(input)
                val response = "pong".toByteArray()
                server.send(DatagramPacket(response, response.size, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val replies = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val replyLatch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { replies += it; replyLatch.countDown() },
            mtu = 1500,
            clock = { System.nanoTime() / 1_000_000L }
        )
        val client = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val request = ipv6Packet(client, loopback.address, 50_000, server.localPort, "ping".toByteArray())

        try {
            assertTrue(forwarder.handleIpv6Packet(request, request.size))
            assertTrue(replyLatch.await(3, TimeUnit.SECONDS))
            val reply = replies.single()
            assertEquals(6, (reply[0].toInt() ushr 4) and 0x0f)
            assertEquals(17, reply[6].toInt() and 0xff)
            assertArrayEquals(loopback.address, reply.copyOfRange(8, 24))
            assertArrayEquals(client, reply.copyOfRange(24, 40))
            assertEquals(server.localPort, u16(reply, 40))
            assertEquals(50_000, u16(reply, 42))
            assertArrayEquals("pong".toByteArray(), reply.copyOfRange(48, reply.size))
            assertEquals(0, ipv6UpperLayerChecksum(reply, 40, reply.size - 40, 17))
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun closedIpv6UdpPortReturnsIcmpv6PortUnreachableToTun() {
        val loopback = InetAddress.getByName("::1")
        val reservation = DatagramSocket(0, loopback)
        val closedPort = reservation.localPort
        reservation.close()
        val replies = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val replyLatch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet -> replies += packet; replyLatch.countDown() },
            mtu = 1_500,
            clock = { System.nanoTime() / 1_000_000L }
        )
        val client = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val request = ipv6Packet(client, loopback.address, 50_000, closedPort, byteArrayOf(1, 2, 3))

        try {
            assertTrue(forwarder.handleIpv6Packet(request, request.size))
            assertTrue(replyLatch.await(3, TimeUnit.SECONDS))
            val reply = replies.single()
            assertEquals(58, reply[6].toInt() and 0xff)
            assertEquals(1, reply[40].toInt() and 0xff)
            assertEquals(4, reply[41].toInt() and 0xff)
            assertArrayEquals(loopback.address, reply.copyOfRange(8, 24))
            assertArrayEquals(client, reply.copyOfRange(24, 40))
            assertEquals(0, ipv6UpperLayerChecksum(reply, 40, reply.size - 40, 58))
            assertArrayEquals(request, reply.copyOfRange(48, reply.size))
        } finally {
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun fragmentedIpv4UdpIsNormalizedRelayedAndReplyIsFragmentedWithValidChecksums() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val payload = ByteArray(60_000) { (it * 29).toByte() }
        val received = CountDownLatch(1)
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(65_535), 65_535)
                server.receive(input)
                if (input.data.copyOf(input.length).contentEquals(payload)) received.countDown()
                server.send(DatagramPacket(input.data, input.length, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val fragments = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val finalFragment = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet ->
                fragments += packet
                if (u16(packet, 6) and 0x2000 == 0) finalFragment.countDown()
            },
            mtu = 32_768,
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, payload).also { packet ->
            put16(packet, 26, 0)
            put16(packet, 26, ipv4UdpChecksumValue(packet))
            put16(packet, 10, 0)
            put16(packet, 10, ipv4HeaderChecksum(packet))
        }
        val inputFragments = Ipv4PacketFragmenter.fragment(request, 32_768, 0x4321)!!
        val inputNormalizer = Ipv4PacketNormalizer()

        try {
            var inputReady: Ipv4PacketNormalizer.Result.Ready? = null
            inputFragments.reversed().forEach { fragment ->
                when (val result = inputNormalizer.process(fragment)) {
                    is Ipv4PacketNormalizer.Result.Ready -> inputReady = result
                    Ipv4PacketNormalizer.Result.Pending -> Unit
                    Ipv4PacketNormalizer.Result.Rejected -> throw AssertionError("valid input fragment rejected")
                }
            }
            val ready = checkNotNull(inputReady)
            assertTrue(ready.reassembled)
            assertEquals(request.size, ready.packet.size)
            assertTrue(forwarder.handleIpv4Packet(ready.packet, ready.packet.size, ready.reassembled))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertTrue(finalFragment.await(3, TimeUnit.SECONDS))
            assertEquals(2, fragments.size)
            assertTrue(fragments.all { it.size <= 32_768 && ipv4HeaderChecksum(it) == 0 })
            assertEquals(1, fragments.map { u16(it, 4) }.distinct().size)

            val outputNormalizer = Ipv4PacketNormalizer()
            var outputReady: Ipv4PacketNormalizer.Result.Ready? = null
            fragments.reversed().forEach { fragment ->
                when (val result = outputNormalizer.process(fragment)) {
                    is Ipv4PacketNormalizer.Result.Ready -> outputReady = result
                    Ipv4PacketNormalizer.Result.Pending -> Unit
                    Ipv4PacketNormalizer.Result.Rejected -> throw AssertionError("valid output fragment rejected")
                }
            }
            val reply = checkNotNull(outputReady).packet
            assertEquals(20 + 8 + payload.size, reply.size)
            assertEquals(0, ipv4HeaderChecksum(reply))
            assertEquals(0, ipv4UdpChecksum(reply))
            assertArrayEquals(loopback.address, reply.copyOfRange(12, 16))
            assertArrayEquals(byteArrayOf(10, 0, 0, 2), reply.copyOfRange(16, 20))
            assertEquals(server.localPort, u16(reply, 20))
            assertEquals(50_000, u16(reply, 22))
            assertEquals(8 + payload.size, u16(reply, 24))
            assertArrayEquals(payload, reply.copyOfRange(28, reply.size))
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun onlyReassembledLargeIpv4UdpBypassesTheTunMtuAdmissionLimit() {
        val sent = CountDownLatch(1)
        val releaseReceive = CountDownLatch(1)
        val protectedSockets = AtomicInteger()
        val sentPayloads = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val diagnostics = DiagnosticsState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { protectedSockets.incrementAndGet(); true },
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = {},
            mtu = 32_768,
            destinationCandidates = { listOf(InetAddress.getByName("127.0.0.1")) },
            clock = { System.nanoTime() / 1_000_000L },
            socketFactory = {
                ScriptedDatagramSocket(
                    onSend = { payload -> sentPayloads += payload; sent.countDown() },
                    onReceive = { releaseReceive.await(3, TimeUnit.SECONDS) },
                    onClose = { releaseReceive.countDown() }
                )
            }
        )
        val payload = ByteArray(60_000) { (it * 31).toByte() }
        val packet = ipv4Packet(InetAddress.getByName("127.0.0.1").address, 18_082, payload)

        try {
            assertTrue(forwarder.handleIpv4Packet(packet, packet.size))
            assertEquals(1, diagnostics.udpRelayOversizeDropped.get())
            assertEquals(0, diagnostics.udpRelayFlowsOpened.get())
            assertEquals(0, protectedSockets.get())

            assertTrue(forwarder.handleIpv4Packet(packet, packet.size, reassembled = true))
            assertTrue(sent.await(3, TimeUnit.SECONDS))
            assertEquals(1, diagnostics.udpRelayOversizeDropped.get())
            assertEquals(1, diagnostics.udpRelayFlowsOpened.get())
            assertEquals(1, protectedSockets.get())
            assertEquals(1, sentPayloads.size)
            assertArrayEquals(payload, sentPayloads.single())
        } finally {
            releaseReceive.countDown()
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun resetDuringFragmentedResponseSuppressesAllRemainingTunWrites() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val response = ByteArray(60_000) { (it * 37).toByte() }
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(32), 32)
                server.receive(input)
                server.send(DatagramPacket(response, response.size, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val resetComplete = CountDownLatch(1)
        val staleWrite = CountDownLatch(1)
        val writes = AtomicInteger()
        val diagnostics = DiagnosticsState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { true },
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = {
                if (writes.incrementAndGet() == 1) {
                    firstWriteEntered.countDown()
                    releaseFirstWrite.await(3, TimeUnit.SECONDS)
                } else {
                    staleWrite.countDown()
                }
            },
            mtu = 32_768,
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, byteArrayOf(1))
        var resetThread: Thread? = null

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(firstWriteEntered.await(3, TimeUnit.SECONDS))
            resetThread = Thread {
                forwarder.resetFlows()
                resetComplete.countDown()
            }.apply { isDaemon = true; start() }
            assertTrue(awaitThreadWaiting(resetThread, resetComplete, 3_000L))
            assertEquals(1L, resetComplete.count)
            releaseFirstWrite.countDown()
            assertTrue(resetComplete.await(3, TimeUnit.SECONDS))
            assertFalse(staleWrite.await(500, TimeUnit.MILLISECONDS))
            assertEquals(1, writes.get())
            assertEquals(0L, diagnostics.udpRelayBytesDown.get())
        } finally {
            releaseFirstWrite.countDown()
            forwarder.closeAll()
            scope.cancel()
            server.close()
            resetThread?.join(1_000L)
            serverThread.join(1_000L)
        }
    }

    @Test
    fun resetDuringLastResponseWriteSuppressesTheReachabilityCommit() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback)
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(1_500), 1_500)
                server.receive(input)
                val response = quicServerInitial()
                server.send(DatagramPacket(response, response.size, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val packetWritten = CountDownLatch(1)
        val reachable = CountDownLatch(1)
        val diagnostics = DiagnosticsState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var forwarder: TurboUdpForwarder
        forwarder = TurboUdpForwarder(
            protectSocket = { true },
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = {
                forwarder.resetFlows()
                packetWritten.countDown()
            },
            httpsPort = server.localPort,
            onUdp443Reachable = { reachable.countDown() },
            clock = { System.nanoTime() / 1_000_000L }
        )
        val request = ipv4Packet(loopback.address, server.localPort, quicInitial())

        try {
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(packetWritten.await(3, TimeUnit.SECONDS))
            assertFalse(reachable.await(500, TimeUnit.MILLISECONDS))
            assertEquals(0L, diagnostics.udpRelayBytesDown.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun reassembledLargeIpv6UdpIsRelayedAndReplyIsFragmentedToTunMtu() {
        val loopback = InetAddress.getByName("::1")
        val server = DatagramSocket(0, loopback)
        val received = CountDownLatch(1)
        val payload = ByteArray(2_048) { (it and 0xff).toByte() }
        val serverThread = Thread {
            runCatching {
                val input = DatagramPacket(ByteArray(4_096), 4_096)
                server.receive(input)
                if (input.data.copyOf(input.length).contentEquals(payload)) received.countDown()
                server.send(DatagramPacket(input.data, input.length, input.socketAddress))
            }
        }.apply { isDaemon = true; start() }
        val fragments = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val finalFragment = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet ->
                fragments += packet
                if ((u16(packet, 42) and 1) == 0) finalFragment.countDown()
            },
            mtu = 1_280,
            clock = { System.nanoTime() / 1_000_000L }
        )
        val client = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val request = ipv6Packet(client, loopback.address, 50_000, server.localPort, payload)

        try {
            assertTrue(forwarder.handleIpv6Packet(request, request.size, reassembled = true))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertTrue(finalFragment.await(3, TimeUnit.SECONDS))
            assertTrue(fragments.size > 1)
            assertTrue(fragments.all { it.size <= 1_280 && (it[6].toInt() and 0xff) == 44 })
            val normalizer = Ipv6PacketNormalizer()
            var result: Ipv6PacketNormalizer.Result = Ipv6PacketNormalizer.Result.Pending
            fragments.forEach { fragment -> result = normalizer.process(fragment) }
            assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
            val reply = (result as Ipv6PacketNormalizer.Result.Ready).packet
            assertArrayEquals(payload, reply.copyOfRange(48, reply.size))
            assertEquals(0, ipv6UpperLayerChecksum(reply, 40, reply.size - 40, 17))
        } finally {
            forwarder.closeAll()
            scope.cancel()
            server.close()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun atomicFragmentWhoseOriginalPacketExceedsMtuReturnsPacketTooBig() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val replies = mutableListOf<ByteArray>()
        val protectedSockets = AtomicInteger()
        val forwarder = TurboUdpForwarder(
            protectSocket = { protectedSockets.incrementAndGet(); false },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { replies += it },
            mtu = 1_280,
            clock = { System.nanoTime() / 1_000_000L }
        )
        val source = ByteArray(16).also { it[0] = 0x20; it[15] = 2 }
        val destination = ByteArray(16).also { it[0] = 0x20; it[15] = 1 }
        val normalized = ipv6Packet(source, destination, 50_000, 4444, ByteArray(1_232))
        val invoking = atomicFragment(normalized, 0x10203040)

        assertTrue(forwarder.handleIpv6Packet(
            normalized,
            normalized.size,
            reassembled = false,
            invokingPacket = invoking
        ))
        assertEquals(0, protectedSockets.get())
        assertEquals(1, replies.size)
        val reply = replies.single()
        assertEquals(2, reply[40].toInt() and 0xff)
        assertEquals(1_280, u32(reply, 44))
        assertArrayEquals(invoking.copyOf(reply.size - 48), reply.copyOfRange(48, reply.size))

        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun reusableIpv6BackingBufferDoesNotInventAnOversizedInvokingPacket() {
        val sent = CountDownLatch(1)
        val releaseReceive = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { true },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            mtu = 1_280,
            destinationCandidates = { listOf(InetAddress.getByName("127.0.0.1")) },
            clock = { System.nanoTime() / 1_000_000L },
            socketFactory = {
                ScriptedDatagramSocket(
                    onSend = { sent.countDown() },
                    onReceive = { releaseReceive.await(3, TimeUnit.SECONDS) },
                    onClose = { releaseReceive.countDown() }
                )
            }
        )
        val source = ByteArray(16).also { it[0] = 0x20; it[15] = 2 }
        val destination = ByteArray(16).also { it[0] = 0x20; it[15] = 1 }
        val request = ipv6Packet(source, destination, 50_000, 4_444, byteArrayOf(1, 2, 3))
        val reusableBuffer = ByteArray(4_096).also { request.copyInto(it) }

        try {
            assertEquals(request.size, TurboUdpForwarder.declaredIpv6PacketLength(reusableBuffer))
            assertTrue(forwarder.handleIpv6Packet(request, request.size, invokingPacket = reusableBuffer))
            assertTrue(sent.await(3, TimeUnit.SECONDS))
        } finally {
            releaseReceive.countDown()
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun rejectsMalformedIpv4WireLengths() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> false },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            mtu = 1500
        )
        val oversizedIp = ipv4Packet().also { put16(it, 2, it.size + 1) }
        val shortUdp = ipv4Packet().also { put16(it, 24, 7) }
        val oversizedUdp = ipv4Packet().also { put16(it, 24, 9) }

        assertFalse(forwarder.handleIpv4Packet(oversizedIp, oversizedIp.size))
        assertFalse(forwarder.handleIpv4Packet(shortUdp, shortUdp.size))
        assertFalse(forwarder.handleIpv4Packet(oversizedUdp, oversizedUdp.size))
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun rejectsMalformedIpv6WireLengths() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = { _: DatagramSocket -> false },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            mtu = 1500
        )
        val oversizedIp = ipv6Packet().also { put16(it, 4, 9) }
        val shortUdp = ipv6Packet().also { put16(it, 44, 7) }
        val oversizedUdp = ipv6Packet().also { put16(it, 44, 9) }

        assertFalse(forwarder.handleIpv6Packet(oversizedIp, oversizedIp.size))
        assertFalse(forwarder.handleIpv6Packet(shortUdp, shortUdp.size))
        assertFalse(forwarder.handleIpv6Packet(oversizedUdp, oversizedUdp.size))
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun rejectsZeroAndCorruptIpv6UdpChecksumsBeforeOpeningASocket() {
        val protectedSockets = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboUdpForwarder(
            protectSocket = {
                protectedSockets.incrementAndGet()
                false
            },
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = {},
            mtu = 1500
        )
        val valid = ipv6Packet(
            ByteArray(16).also { it[0] = 0x20; it[15] = 2 },
            ByteArray(16).also { it[0] = 0x20; it[15] = 1 },
            50_000,
            123,
            byteArrayOf(1, 2, 3)
        )
        val zero = valid.copyOf().also { put16(it, 46, 0) }
        val corrupt = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val linkLocal = valid.copyOf().also {
            it.fill(0, 24, 40)
            it[24] = 0xfe.toByte()
            it[25] = 0x80.toByte()
            it[39] = 1
            put16(it, 46, 0)
            put16(it, 46, ipv6ChecksumValue(it, 40, it.size - 40, 17))
        }
        val multicast = valid.copyOf().also {
            it.fill(0, 24, 40)
            it[24] = 0xff.toByte()
            it[25] = 2
            it[39] = 1
            put16(it, 46, 0)
            put16(it, 46, ipv6ChecksumValue(it, 40, it.size - 40, 17))
        }

        assertFalse(forwarder.handleIpv6Packet(zero, zero.size))
        assertFalse(forwarder.handleIpv6Packet(corrupt, corrupt.size))
        assertFalse(forwarder.handleIpv6Packet(linkLocal, linkLocal.size))
        assertFalse(forwarder.handleIpv6Packet(multicast, multicast.size))
        assertEquals(0, protectedSockets.get())
        forwarder.closeAll()
        scope.cancel()
    }

    private fun ipv4Packet(): ByteArray {
        val packet = ByteArray(28)
        packet[0] = 0x45
        put16(packet, 2, packet.size)
        packet[9] = 17
        byteArrayOf(10, 0, 0, 2).copyInto(packet, 12)
        byteArrayOf(1, 1, 1, 1).copyInto(packet, 16)
        put16(packet, 20, 50000)
        put16(packet, 22, 123)
        put16(packet, 24, 8)
        return packet
    }

    private fun ipv4Packet(destination: ByteArray, destinationPort: Int, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(20 + udpLength)
        packet[0] = 0x45
        put16(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 17
        byteArrayOf(10, 0, 0, 2).copyInto(packet, 12)
        destination.copyInto(packet, 16)
        put16(packet, 20, 50_000)
        put16(packet, 22, destinationPort)
        put16(packet, 24, udpLength)
        put16(packet, 26, 0xffff)
        payload.copyInto(packet, 28)
        return packet
    }

    private fun quicInitial(): ByteArray {
        val payload = ByteArray(1_200)
        payload[0] = 0xc0.toByte()
        payload[4] = 1
        payload[5] = 8
        for (index in 0 until 8) payload[6 + index] = (index + 1).toByte()
        payload[14] = 0
        payload[15] = 0
        val encodedLength = payload.size - 18
        payload[16] = (0x40 or (encodedLength ushr 8)).toByte()
        payload[17] = encodedLength.toByte()
        return payload
    }

    private fun futureQuicLongHeader(): ByteArray {
        return quicInitial().also { payload ->
            payload[0] = 0x80.toByte()
            put32(payload, 1, 0x1a2a3a4a)
        }
    }

    private fun quicServerInitial(): ByteArray {
        val serverConnectionId = byteArrayOf(9, 8, 7, 6)
        val payload = ByteArray(5 + 1 + 1 + serverConnectionId.size + 1 + 1 + 17)
        payload[0] = 0xc0.toByte()
        payload[4] = 1
        var cursor = 5
        payload[cursor++] = 0
        payload[cursor++] = serverConnectionId.size.toByte()
        serverConnectionId.copyInto(payload, cursor)
        cursor += serverConnectionId.size
        payload[cursor++] = 0
        payload[cursor++] = 17
        while (cursor < payload.size) payload[cursor++] = 1
        return payload
    }

    private fun quicServerRetry(): ByteArray {
        val serverConnectionId = byteArrayOf(9, 8, 7, 6)
        val retryWithoutTag = ByteArray(5 + 1 + 1 + serverConnectionId.size + 1)
        retryWithoutTag[0] = 0xf0.toByte()
        retryWithoutTag[4] = 1
        var cursor = 5
        retryWithoutTag[cursor++] = 0
        retryWithoutTag[cursor++] = serverConnectionId.size.toByte()
        serverConnectionId.copyInto(retryWithoutTag, cursor)
        cursor += serverConnectionId.size
        retryWithoutTag[cursor] = 1
        val originalDestinationConnectionId = ByteArray(8) { index -> (index + 1).toByte() }
        val tag = QuicServerResponseClassifier.retryIntegrityTag(
            1,
            originalDestinationConnectionId,
            retryWithoutTag
        )!!
        return retryWithoutTag + tag
    }

    private fun ipv6Packet(): ByteArray {
        val packet = ByteArray(48)
        packet[0] = 0x60
        put16(packet, 4, 8)
        packet[6] = 17
        packet[7] = 64
        packet[23] = 1
        packet[39] = 2
        put16(packet, 40, 50000)
        put16(packet, 42, 123)
        put16(packet, 44, 8)
        put16(packet, 46, ipv6ChecksumValue(packet, 40, 8, 17))
        return packet
    }

    private fun ipv6Packet(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)
        packet[0] = 0x60
        put16(packet, 4, udpLength)
        packet[6] = 17
        packet[7] = 64
        source.copyInto(packet, 8)
        destination.copyInto(packet, 24)
        put16(packet, 40, sourcePort)
        put16(packet, 42, destinationPort)
        put16(packet, 44, udpLength)
        payload.copyInto(packet, 48)
        put16(packet, 46, ipv6ChecksumValue(packet, 40, udpLength, 17))
        return packet
    }

    private fun ipv6ChecksumValue(packet: ByteArray, offset: Int, length: Int, protocol: Int): Int {
        val value = ipv6UpperLayerChecksum(packet, offset, length, protocol)
        return if (value == 0) 0xffff else value
    }

    private fun ipv4HeaderChecksum(packet: ByteArray): Int {
        var sum = checksumSum(packet, 0, 20)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun ipv4UdpChecksumValue(packet: ByteArray): Int {
        val value = ipv4UdpChecksum(packet)
        return if (value == 0) 0xffff else value
    }

    private fun ipv4UdpChecksum(packet: ByteArray): Int {
        val udpOffset = (packet[0].toInt() and 0x0f) * 4
        val udpLength = u16(packet, udpOffset + 4)
        var sum = checksumSum(packet, 12, 8)
        sum += 17L + udpLength.toLong()
        sum += checksumSum(packet, udpOffset, udpLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun atomicFragment(packet: ByteArray, identification: Int): ByteArray {
        val result = ByteArray(packet.size + 8)
        packet.copyInto(result, 0, 0, 40)
        result[6] = 44
        put16(result, 4, u16(packet, 4) + 8)
        result[40] = 17
        put32(result, 44, identification)
        packet.copyInto(result, 48, 40)
        return result
    }

    private fun ipv6UpperLayerChecksum(packet: ByteArray, offset: Int, length: Int, protocol: Int): Int {
        var sum = checksumSum(packet, 8, 32)
        sum += length.toLong()
        sum += protocol.toLong()
        sum += checksumSum(packet, offset, length)
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

    private fun u32(data: ByteArray, offset: Int): Int {
        return (u16(data, offset) shl 16) or u16(data, offset + 2)
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

    private fun awaitThreadWaiting(
        thread: Thread,
        completed: CountDownLatch,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline && completed.count != 0L) {
            when (thread.state) {
                Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING -> return true
                else -> Thread.yield()
            }
        }
        return false
    }

    private class ScriptedDatagramSocket(
        private val onSend: (ByteArray) -> Unit,
        private val onReceive: (DatagramPacket) -> Unit,
        private val onClose: () -> Unit
    ) : DatagramSocket() {
        override fun send(packet: DatagramPacket) {
            onSend(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
        }

        override fun receive(packet: DatagramPacket) {
            onReceive(packet)
        }

        override fun close() {
            onClose()
            super.close()
        }
    }
}
