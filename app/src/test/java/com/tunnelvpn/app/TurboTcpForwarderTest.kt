package com.tunnelvpn.app

import java.net.InetAddress
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboTcpForwarderTest {
    @Test
    fun nonTlsMappingTriesEveryPublishedAddress() {
        val responderAddress = InetAddress.getByName("127.0.0.1")
        val rejectedAddress = InetAddress.getByName("127.0.0.2")
        val server = ServerSocket(0, 1, responderAddress)
        val accepted = CountDownLatch(1)
        val serverThread = Thread {
            runCatching {
                server.accept().use { peer ->
                    accepted.countDown()
                    peer.getOutputStream().write(byteArrayOf(7, 8, 9))
                    peer.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }
        val mapper = TurboDomainMapper()
        val mapping = mapper.map(
            "multi.example",
            listOf(rejectedAddress.address, responderAddress.address)
        )!!
        val response = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { true },
            mapper = mapper,
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet ->
                val tcpHeader = ((packet[32].toInt() ushr 4) and 0x0f) * 4
                if (20 + tcpHeader < packet.size) response.countDown()
            }
        )
        val client = byteArrayOf(10, 0, 0, 2)
        val syn = tcpPacket(client, mapping.virtualAddress, 51_110, server.localPort, 500, 0x02)

        try {
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            assertTrue(response.await(3, TimeUnit.SECONDS))
        } finally {
            forwarder.closeAll()
            scope.cancel()
            runCatching { server.close() }
            serverThread.join(1_000L)
        }
    }

    @Test
    fun tcpDnsIsTerminatedLocallyWithoutOpeningAnUpstreamSocket() {
        val client = byteArrayOf(10, 0, 0, 2)
        val virtualDns = byteArrayOf(10, 111, 0, 1)
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1771)!!
        val expected = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val framedQuery = ByteArray(query.size + 2).also {
            it[0] = (query.size ushr 8).toByte()
            it[1] = query.size.toByte()
            query.copyInto(it, 2)
        }
        val response = AtomicReference<ByteArray>()
        val responseLatch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { error("TCP DNS must not open an upstream socket") },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet ->
                val tcpHeader = ((packet[32].toInt() ushr 4) and 0x0f) * 4
                val payloadOffset = 20 + tcpHeader
                if (payloadOffset < packet.size) {
                    response.set(packet.copyOfRange(payloadOffset, packet.size))
                    responseLatch.countDown()
                }
            },
            directHttpsForwarding = true,
            tcpDnsHandler = { received ->
                assertArrayEquals(query, received)
                expected
            }
        )

        try {
            val syn = tcpPacket(client, virtualDns, 51_111, 53, 900, 0x02)
            val data = tcpPacket(client, virtualDns, 51_111, 53, 901, 0x18, framedQuery)
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            assertTrue(forwarder.handleIpv4Packet(data, data.size))
            assertTrue(responseLatch.await(3, TimeUnit.SECONDS))
            val framedResponse = response.get()
            assertEquals(expected.size, u16(framedResponse, 0))
            assertArrayEquals(expected, framedResponse.copyOfRange(2, framedResponse.size))
        } finally {
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun truncatedTlsRecordIsRejectedBeforeAValidTlsAttemptIsCommitted() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 2, loopback)
        val attempts = AtomicInteger()
        val serverThread = Thread {
            repeat(2) { index ->
                runCatching {
                    server.accept().use { peer ->
                        attempts.incrementAndGet()
                        peer.soTimeout = 100
                        val input = peer.getInputStream()
                        val buffer = ByteArray(4_096)
                        while (true) {
                            try {
                                if (input.read(buffer) < 0) break
                            } catch (_: java.net.SocketTimeoutException) {
                                break
                            }
                        }
                        val response = if (index == 0) {
                            byteArrayOf(22, 3, 3, 0, 4)
                        } else {
                            testServerHello()
                        }
                        peer.getOutputStream().write(response)
                        peer.getOutputStream().flush()
                        Thread.sleep(100L)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val acceptedTls = AtomicReference<ByteArray>()
        val responseLatch = CountDownLatch(1)
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { true },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet ->
                packets += packet
                val headerLength = if (packet.size >= 40) ((packet[32].toInt() ushr 4) and 0x0f) * 4 else 0
                val payloadOffset = 20 + headerLength
                if (headerLength >= 20 && payloadOffset < packet.size && packet[payloadOffset] == 22.toByte()) {
                    acceptedTls.set(packet.copyOfRange(payloadOffset, packet.size))
                    responseLatch.countDown()
                }
            },
            directHttpsForwarding = true,
            httpsPort = server.localPort
        )
        val client = byteArrayOf(10, 0, 0, 2)
        val syn = tcpPacket(client, loopback.address, 50_010, server.localPort, 100, 0x02)
        val hello = testClientHello("example.com")
        val data = tcpPacket(client, loopback.address, 50_010, server.localPort, 101, 0x18, hello)

        try {
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            assertTrue(forwarder.handleIpv4Packet(data, data.size))
            assertTrue(responseLatch.await(8, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())
            assertArrayEquals(testServerHello(), acceptedTls.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            runCatching { server.close() }
            serverThread.join(1_000L)
        }
    }

    @Test
    fun helloRetryRequestKeepsFragmentationForTheSecondClientHello() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 1, loopback)
        val retryFlight = AtomicReference<ByteArray>()
        val retryReceived = CountDownLatch(1)
        val serverThread = Thread {
            runCatching {
                server.accept().use { peer ->
                    peer.soTimeout = 150
                    readUntilTimeout(peer.getInputStream())
                    peer.getOutputStream().write(testServerHello(HELLO_RETRY_RANDOM))
                    peer.getOutputStream().flush()
                    peer.soTimeout = 1_000
                    retryFlight.set(readUntilTimeout(peer.getInputStream()))
                    retryReceived.countDown()
                }
            }
        }.apply { isDaemon = true; start() }
        val hrrDelivered = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { true },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet ->
                val headerLength = if (packet.size >= 40) ((packet[32].toInt() ushr 4) and 0x0f) * 4 else 0
                val payloadOffset = 20 + headerLength
                if (headerLength >= 20 && payloadOffset < packet.size && packet[payloadOffset] == 22.toByte()) {
                    hrrDelivered.countDown()
                }
            },
            directHttpsForwarding = true,
            httpsPort = server.localPort
        )
        val client = byteArrayOf(10, 0, 0, 2)
        val firstHello = testClientHello("example.com")
        val secondHello = testClientHello("example.com")
        val ccs = byteArrayOf(20, 3, 3, 0, 1, 1)

        try {
            assertTrue(forwarder.handleIpv4Packet(
                tcpPacket(client, loopback.address, 50_011, server.localPort, 100, 0x02),
                40
            ))
            val firstPacket = tcpPacket(
                client,
                loopback.address,
                50_011,
                server.localPort,
                101,
                0x18,
                firstHello
            )
            assertTrue(forwarder.handleIpv4Packet(firstPacket, firstPacket.size))
            assertTrue(hrrDelivered.await(5, TimeUnit.SECONDS))
            val ccsPacket = tcpPacket(
                client,
                loopback.address,
                50_011,
                server.localPort,
                101 + firstHello.size,
                0x18,
                ccs
            )
            val retryPacket = tcpPacket(
                client,
                loopback.address,
                50_011,
                server.localPort,
                101 + firstHello.size + ccs.size,
                0x18,
                secondHello
            )
            assertTrue(forwarder.handleIpv4Packet(ccsPacket, ccsPacket.size))
            assertTrue(forwarder.handleIpv4Packet(retryPacket, retryPacket.size))
            assertTrue(retryReceived.await(5, TimeUnit.SECONDS))
            val relayed = retryFlight.get()
            assertTrue(relayed.size > secondHello.size)
            assertTrue(TlsClientHello.analyze(relayed).complete)
            assertFalse(relayed.contentEquals(secondHello))
        } finally {
            forwarder.closeAll()
            scope.cancel()
            runCatching { server.close() }
            serverThread.join(1_000L)
        }
    }

    @Test
    fun nat64HttpsSynIsConsumedAndReturnsAValidIpv6SynAck() {
        val client = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2
        )
        val destination = byteArrayOf(
            0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 93, 184.toByte(), 216.toByte(), 34
        )
        val packets = mutableListOf<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )
        val syn = ipv6TcpPacket(client, destination, 50_000, 443, 100, 0x02)

        assertTrue(forwarder.handleIpv6Packet(syn, syn.size))

        assertEquals(1, packets.size)
        val synAck = packets.single()
        assertEquals(6, (synAck[0].toInt() ushr 4) and 0x0f)
        assertEquals(6, synAck[6].toInt() and 0xff)
        assertArrayEquals(destination, synAck.copyOfRange(8, 24))
        assertArrayEquals(client, synAck.copyOfRange(24, 40))
        assertEquals(443, u16(synAck, 40))
        assertEquals(50_000, u16(synAck, 42))
        assertEquals(0x12, synAck[53].toInt() and 0xff)
        assertEquals(0, ipv6UpperLayerChecksum(synAck, 40, synAck.size - 40, 6))
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun ipv6TcpPayloadIsRelayedThroughAProtectedSocket() {
        val loopback = InetAddress.getByName("::1")
        val server = ServerSocket(0, 1, loopback)
        val received = AtomicReference<ByteArray>()
        val receivedLatch = CountDownLatch(1)
        val serverThread = Thread {
            runCatching {
                server.accept().use { peer ->
                    val bytes = ByteArray(4)
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = peer.getInputStream().read(bytes, offset, bytes.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    received.set(bytes.copyOf(offset))
                    receivedLatch.countDown()
                }
            }
        }.apply { isDaemon = true; start() }
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { true },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true,
            tlsFragmentationEnabled = false
        )
        val client = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val destination = loopback.address
        val syn = ipv6TcpPacket(client, destination, 50_001, server.localPort, 700, 0x02)
        val data = ipv6TcpPacket(
            client,
            destination,
            50_001,
            server.localPort,
            701,
            0x18,
            "ping".toByteArray()
        )

        try {
            assertTrue(forwarder.handleIpv6Packet(syn, syn.size))
            assertTrue(forwarder.handleIpv6Packet(data, data.size))
            assertTrue(receivedLatch.await(3, TimeUnit.SECONDS))
            assertArrayEquals("ping".toByteArray(), received.get())
        } finally {
            forwarder.closeAll()
            scope.cancel()
            runCatching { server.close() }
            serverThread.join(1_000L)
        }
    }

    @Test
    fun rejectsCorruptIpv6TcpChecksumBeforeCreatingFlowState() {
        val packets = mutableListOf<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )
        val valid = ipv6TcpPacket(
            ByteArray(16).also { it[0] = 0x20; it[15] = 2 },
            ByteArray(16).also { it[0] = 0x20; it[15] = 1 },
            50_000,
            443,
            100,
            0x02
        )
        val corrupt = valid.copyOf().also { it[54] = (it[54].toInt() xor 1).toByte() }
        val linkLocal = valid.copyOf().also {
            it.fill(0, 24, 40)
            it[24] = 0xfe.toByte()
            it[25] = 0x80.toByte()
            it[39] = 1
            put16(it, 56, 0)
            put16(it, 56, ipv6UpperLayerChecksum(it, 40, it.size - 40, 6))
        }
        val multicast = valid.copyOf().also {
            it.fill(0, 24, 40)
            it[24] = 0xff.toByte()
            it[25] = 2
            it[39] = 1
            put16(it, 56, 0)
            put16(it, 56, ipv6UpperLayerChecksum(it, 40, it.size - 40, 6))
        }

        assertFalse(forwarder.handleIpv6Packet(corrupt, corrupt.size))
        assertFalse(forwarder.handleIpv6Packet(linkLocal, linkLocal.size))
        assertFalse(forwarder.handleIpv6Packet(multicast, multicast.size))
        assertTrue(packets.isEmpty())
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun rejectsInvalidTcpFlagsReservedBitsPortsAndOptionsBeforeFlowCreation() {
        val packets = mutableListOf<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )
        val source = byteArrayOf(10, 0, 0, 2)
        val destination = byteArrayOf(1, 1, 1, 1)
        val synFin = tcpPacket(source, destination, 50_001, 443, 100, 0x03)
        val synRst = tcpPacket(source, destination, 50_002, 443, 100, 0x06)
        val reserved = tcpPacket(source, destination, 50_003, 443, 100, 0x02).also {
            it[32] = (it[32].toInt() or 0x02).toByte()
        }
        val zeroSourcePort = tcpPacket(source, destination, 0, 443, 100, 0x02)
        val malformedLength = tcpPacket(
            source,
            destination,
            50_004,
            443,
            100,
            0x02,
            options = byteArrayOf(2, 8, 0, 0)
        )
        val zeroMss = tcpPacket(
            source,
            destination,
            50_005,
            443,
            100,
            0x02,
            options = byteArrayOf(2, 4, 0, 0)
        )
        val duplicateWindowScale = tcpPacket(
            source,
            destination,
            50_006,
            443,
            100,
            0x02,
            options = byteArrayOf(3, 3, 1, 3, 3, 2, 0, 0)
        )

        listOf(synFin, synRst, reserved, zeroSourcePort, malformedLength, zeroMss, duplicateWindowScale)
            .forEach { packet -> assertFalse(forwarder.handleIpv4Packet(packet, packet.size)) }
        assertTrue(packets.isEmpty())
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun synToMappedVirtualHttpsAddressReturnsSynAck() {
        val mapper = TurboDomainMapper()
        val mapping = mapper.map("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))!!
        val packets = mutableListOf<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = mapper,
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it }
        )

        val consumed = forwarder.handleIpv4Packet(
            tcpPacket(
                source = byteArrayOf(10, 0, 0, 5),
                destination = mapping.virtualAddress,
                sourcePort = 50000,
                destinationPort = 443,
                sequence = 100,
                flags = 0x02
            ),
            40
        )

        assertTrue(consumed)
        assertEquals(1, packets.size)
        val synAck = packets.first()
        assertEquals(443, u16(synAck, 20))
        assertEquals(50000, u16(synAck, 22))
        assertEquals(0x12, synAck[33].toInt() and 0xff)
        assertEquals(24, ((synAck[32].toInt() ushr 4) and 0x0f) * 4)
        assertEquals(2, synAck[40].toInt() and 0xff)
        assertEquals(4, synAck[41].toInt() and 0xff)
        assertFalse(hasTcpOption(synAck, kind = 4, length = 2))
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun failedSynAckWriteRemovesTheUnpublishedConnection() {
        val mapper = TurboDomainMapper()
        val mapping = mapper.map("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))!!
        val diagnostics = DiagnosticsState()
        val packets = mutableListOf<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var failNextWrite = true
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = mapper,
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = {
                if (failNextWrite) {
                    failNextWrite = false
                    throw java.io.IOException("injected TUN failure")
                }
                packets += it
            }
        )
        val syn = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            mapping.virtualAddress,
            50000,
            443,
            100,
            0x02
        )

        assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
        assertEquals(1L, diagnostics.turboTcpConnectionsOpened.get())
        assertEquals(1L, diagnostics.turboTcpConnectionsClosed.get())
        assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
        assertEquals(2L, diagnostics.turboTcpConnectionsOpened.get())
        assertEquals(1, packets.size)

        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun unmappedHttpsAddressIsNotConsumed() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { error("should not write") }
        )

        val consumed = forwarder.handleIpv4Packet(
            tcpPacket(
                source = byteArrayOf(10, 0, 0, 5),
                destination = byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
                sourcePort = 50000,
                destinationPort = 443,
                sequence = 100,
                flags = 0x02
            ),
            40
        )

        assertFalse(consumed)
        scope.cancel()
    }

    @Test
    fun staleTurboVirtualAddressIsResetInsteadOfDirectlyForwarded() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val packets = mutableListOf<ByteArray>()
        var protected = false
        val forwarder = TurboTcpForwarder(
            protectSocket = { protected = true; false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )
        val syn = tcpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(192.toByte(), 0, 2, 2),
            sourcePort = 50000,
            destinationPort = 443,
            sequence = 100,
            flags = 0x02
        )

        assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
        assertFalse(protected)
        assertEquals(1, packets.size)
        assertEquals(0x14, packets.single()[33].toInt() and 0x3f)

        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun directHttpsForwardingConsumesUnmappedAddressWhenEnabled() {
        val packets = mutableListOf<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )

        val consumed = forwarder.handleIpv4Packet(
            tcpPacket(
                source = byteArrayOf(10, 0, 0, 5),
                destination = byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
                sourcePort = 50000,
                destinationPort = 443,
                sequence = 100,
                flags = 0x02
            ),
            40
        )

        assertTrue(consumed)
        assertEquals(1, packets.size)
        assertEquals(93, packets.first()[12].toInt() and 0xff)
        scope.cancel()
    }

    @Test
    fun synToNonHttpsPortReturnsSynAckWithMatchingSourcePort() {
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )

        val consumed = forwarder.handleIpv4Packet(
            tcpPacket(
                source = byteArrayOf(10, 0, 0, 5),
                destination = byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
                sourcePort = 50000,
                destinationPort = 80,
                sequence = 100,
                flags = 0x02
            ),
            40
        )

        assertTrue(consumed)
        assertTrue(packets.isNotEmpty())
        val synAck = packets.first()
        assertEquals(80, u16(synAck, 20))
        assertEquals(50000, u16(synAck, 22))
        assertEquals(0x12, synAck[33].toInt() and 0xff)
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun statelessAckToUnknownVirtualFlowIsResetNotSilentlyDropped() {


        val mapper = TurboDomainMapper()
        val mapping = mapper.map("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))!!
        val packets = mutableListOf<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = mapper,
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it }
        )

        val consumed = forwarder.handleIpv4Packet(
            tcpPacket(
                source = byteArrayOf(10, 0, 0, 5),
                destination = mapping.virtualAddress,
                sourcePort = 50000,
                destinationPort = 443,
                sequence = 500,
                flags = 0x10
            ),
            40
        )

        assertTrue(consumed)
        assertEquals(1, packets.size)
        assertEquals(0x04, packets.first()[33].toInt() and 0xff)
        scope.cancel()
    }

    @Test
    fun capacityResetAcknowledgesTheIncomingSynSequenceSpace() {
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )
        repeat(65) { index ->
            val syn = tcpPacket(
                byteArrayOf(10, 0, 0, 5),
                byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
                51000 + index,
                443,
                1000 + index,
                0x02
            )
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
        }

        val reset = packets.last()
        assertEquals(0x14, reset[33].toInt() and 0xff)
        assertEquals(1065L, u32(reset, 28))
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun trimsRetransmittedClientPayloadPrefix() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        val sequenced = TurboTcpForwarder.trimPayloadForExpectedSequence(
            expectedSequence = 103,
            packetSequence = 100,
            payload = payload
        )

        assertEquals(105L, sequenced!!.first)
        assertArrayEquals(byteArrayOf(4, 5), sequenced.second)
    }

    @Test
    fun dropsDuplicateOrOutOfOrderClientPayload() {
        val payload = byteArrayOf(1, 2, 3)

        assertNull(
            TurboTcpForwarder.trimPayloadForExpectedSequence(
                expectedSequence = 103,
                packetSequence = 100,
                payload = payload
            )
        )
        assertNull(
            TurboTcpForwarder.trimPayloadForExpectedSequence(
                expectedSequence = 100,
                packetSequence = 102,
                payload = payload
            )
        )
    }

    @Test
    fun clientIngressQueueAndGlobalBudgetBoundRetainedPayload() {
        val minimumBufferedBytes = TurboTcpForwarder.CLIENT_PAYLOAD_QUEUE_CAPACITY.toLong() *
            TurboTcpForwarder.MIN_TCP_PAYLOAD

        assertTrue(minimumBufferedBytes >= TurboTcpForwarder.CLIENT_RELAY_BUFFER_BYTES)
        assertTrue(
            TurboTcpForwarder.CLIENT_RELAY_BUFFER_BYTES <
                TurboTcpForwarder.GLOBAL_CLIENT_RELAY_BUFFER_BYTES
        )
    }

    @Test
    fun tcpOptionParserDistinguishesTlvsFromAdjacentOptionBytes() {
        val packet = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            byteArrayOf(192.toByte(), 0, 2, 1),
            50_000,
            443,
            100,
            0x02,
            options = byteArrayOf(2, 4, 0x02, 0x18, 4, 2, 0, 0)
        )
        val mssOnly = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            byteArrayOf(192.toByte(), 0, 2, 1),
            50_000,
            443,
            100,
            0x02,
            options = byteArrayOf(2, 4, 0x02, 0x18)
        )

        assertTrue(hasTcpOption(packet, kind = 2, length = 4))
        assertTrue(hasTcpOption(packet, kind = 4, length = 2))
        assertFalse(hasTcpOption(mssOnly, kind = 4, length = 2))
    }

    @Test
    fun adjacentClientSegmentsCoalesceIntoOneSocketWriteBatch() {
        val batch = TurboClientPayloadBatch(TurboTcpForwarder.CLIENT_WRITE_BATCH_BYTES)
        val segment = ByteArray(1_360) { index -> (index and 0xff).toByte() }

        repeat(48) { assertTrue(batch.append(segment)) }

        assertEquals(65_280, batch.size)
        assertFalse(batch.append(segment))
        assertArrayEquals(segment, batch.bytes.copyOfRange(0, segment.size))
        batch.clear()
        assertEquals(0, batch.size)
    }

    @Test
    fun advertisedWindowShrinksWithQueuedBytesAndCloseReleasesTheBudget() = kotlinx.coroutines.runBlocking {
        val mapper = TurboDomainMapper()
        val mapping = mapper.map("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))!!
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = mapper,
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it }
        )
        val syn = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            mapping.virtualAddress,
            50103,
            443,
            100,
            0x02,
            options = byteArrayOf(3, 3, 8, 0)
        )
        val incompleteClientHello = ByteArray(4_096).also {
            it[0] = 22
            it[1] = 3
            it[2] = 3
            put16(it, 3, 8_192)
        }
        val data = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            mapping.virtualAddress,
            50103,
            443,
            101,
            0x18,
            incompleteClientHello
        )

        assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
        assertTrue(forwarder.handleIpv4Packet(data, data.size))

        assertEquals(4_096, forwarder.retainedClientPayloadBytes())
        assertEquals(
            (TurboTcpForwarder.CLIENT_RELAY_BUFFER_BYTES - incompleteClientHello.size) ushr 8,
            u16(packets.last(), 34)
        )
        forwarder.closeAll()
        repeat(100) {
            if (forwarder.retainedClientPayloadBytes() == 0) return@repeat
            kotlinx.coroutines.delay(10)
        }
        assertEquals(0, forwarder.retainedClientPayloadBytes())
        scope.cancel()
    }

    @Test
    fun detectsTlsAlertAsFailedHandshakeResponse() {
        val alert = byteArrayOf(21, 3, 3, 0, 2, 2, 40)

        assertTrue(TurboTcpForwarder.isLikelyTlsAlertResponse(alert))
        assertFalse(TurboTcpForwarder.isLikelyTlsAlertResponse(byteArrayOf(22, 3, 3, 0, 4, 1, 2, 3, 4)))
        assertFalse(TurboTcpForwarder.isLikelyTlsAlertResponse(byteArrayOf(21, 2, 3, 0, 2, 2, 40)))
        assertFalse(TurboTcpForwarder.isLikelyTlsAlertResponse(byteArrayOf(21, 3, 3, 0, 0)))
    }

    @Test
    fun aiSuccessRequiresPlausibleTlsRecordMetadata() {
        assertTrue(TurboTcpForwarder.isPlausibleTlsServerResponse(testServerHello()))
        assertFalse(TurboTcpForwarder.isPlausibleTlsServerResponse(byteArrayOf(22, 3, 3, 0, 4)))
        assertFalse(TurboTcpForwarder.isPlausibleTlsServerResponse(byteArrayOf(22, 3, 3, 0, 4, 2, 0, 0)))
        assertFalse(TurboTcpForwarder.isPlausibleTlsServerResponse(byteArrayOf(22, 3, 3, 0, 4, 2, -1, -1, -1)))
        assertFalse(TurboTcpForwarder.isPlausibleTlsServerResponse("HTTP/1.1 403".toByteArray()))
        assertFalse(TurboTcpForwarder.isPlausibleTlsServerResponse(byteArrayOf(22, 3, 3, 0)))
        assertFalse(TurboTcpForwarder.isPlausibleTlsServerResponse(byteArrayOf(22, 3, 3, 0, 0)))
        assertFalse(TurboTcpForwarder.isPlausibleTlsServerResponse(byteArrayOf(23, 3, 3, 0, 4, 1, 2, 3, 4)))
        assertFalse(TurboTcpForwarder.isPlausibleTlsServerResponse(byteArrayOf(22, 3, 3, 0, 4, 8, 0, 0, 0)))
        assertFalse(TurboTcpForwarder.isSuccessfulAiConnection(false, TurboFailureCategory.NONE))
        assertFalse(TurboTcpForwarder.isSuccessfulAiConnection(true, TurboFailureCategory.NETWORK_CHANGED))
        assertTrue(TurboTcpForwarder.isSuccessfulAiConnection(true, TurboFailureCategory.NONE))
    }

    @Test
    fun serverHelloMaySpanMultipleCompleteTlsRecords() {
        val complete = testServerHello()
        val handshake = complete.copyOfRange(5, complete.size)
        val first = tlsHandshakeRecord(handshake.copyOfRange(0, 12))
        val second = tlsHandshakeRecord(handshake.copyOfRange(12, handshake.size))

        assertEquals(TlsServerHelloStatus.INCOMPLETE, TurboTcpForwarder.tlsServerHelloStatus(first))
        assertEquals(TlsServerHelloStatus.VALID, TurboTcpForwarder.tlsServerHelloStatus(first + second))
        assertTrue(TurboTcpForwarder.isPlausibleTlsServerResponse(first + second))
    }

    @Test
    fun plainCompatibilitySuccessIsNeverCachedAheadOfFragmentation() {
        assertFalse(
            TurboTcpForwarder.shouldCacheSuccessfulFragmentationMode(TlsClientHello.FragmentationMode.NONE)
        )
        assertTrue(
            TurboTcpForwarder.shouldCacheSuccessfulFragmentationMode(TlsClientHello.FragmentationMode.HOSTNAME)
        )
    }

    @Test
    fun tcpCloseStateDoesNotStartTimeWaitDuringAOneSidedHalfClose() {
        assertEquals(
            TurboTcpClosePhase.OPEN_OR_HALF_CLOSED,
            TurboTcpForwarder.tcpClosePhase(false, true, 10, 11)
        )
        assertEquals(
            TurboTcpClosePhase.WAITING_FOR_FINAL_ACK,
            TurboTcpForwarder.tcpClosePhase(true, true, 10, 11)
        )
        assertEquals(
            TurboTcpClosePhase.TIME_WAIT,
            TurboTcpForwarder.tcpClosePhase(true, true, 11, 11)
        )
    }

    @Test
    fun upstreamEofWaitsForSequenceSpaceBeforeSendingFin() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 1, loopback)
        val serverThread = Thread {
            runCatching {
                server.accept().use { peer ->
                    peer.getInputStream().read()
                    peer.getOutputStream().write(byteArrayOf(1, 2, 3, 4))
                    peer.getOutputStream().flush()
                    peer.shutdownOutput()
                }
            }
        }.apply { isDaemon = true; start() }
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val dataSent = CountDownLatch(1)
        val finSent = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { true },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packet ->
                packets += packet
                val flags = packet[33].toInt() and 0xff
                if (flags and 0x08 != 0) dataSent.countDown()
                if (flags and 0x01 != 0) finSent.countDown()
            },
            directHttpsForwarding = true,
            tlsFragmentationEnabled = false
        )
        val client = byteArrayOf(10, 0, 0, 2)
        val syn = tcpPacket(client, loopback.address, 50_104, server.localPort, 100, 0x02, window = 4)

        try {
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val serverInitial = u32(packets.single(), 24)
            val handshakeAck = tcpPacket(
                client,
                loopback.address,
                50_104,
                server.localPort,
                101,
                0x10,
                acknowledgement = (serverInitial + 1L).toInt(),
                window = 4
            )
            val request = tcpPacket(
                client,
                loopback.address,
                50_104,
                server.localPort,
                101,
                0x18,
                byteArrayOf(9),
                acknowledgement = (serverInitial + 1L).toInt(),
                window = 4
            )
            assertTrue(forwarder.handleIpv4Packet(handshakeAck, handshakeAck.size))
            assertTrue(forwarder.handleIpv4Packet(request, request.size))
            assertTrue(dataSent.await(3, TimeUnit.SECONDS))
            assertFalse(finSent.await(150, TimeUnit.MILLISECONDS))

            val dataAck = tcpPacket(
                client,
                loopback.address,
                50_104,
                server.localPort,
                102,
                0x10,
                acknowledgement = (serverInitial + 5L).toInt(),
                window = 4
            )
            assertTrue(forwarder.handleIpv4Packet(dataAck, dataAck.size))
            assertTrue(finSent.await(3, TimeUnit.SECONDS))
        } finally {
            forwarder.closeAll()
            scope.cancel()
            runCatching { server.close() }
            serverThread.join(1_000L)
        }
    }

    @Test
    fun finAdmissionAndAckSequenceChecksHandleFullWindowsAndWrap() {
        assertFalse(TurboTcpForwarder.hasSendWindow(100, 104, 4, 1))
        assertTrue(TurboTcpForwarder.hasSendWindow(101, 104, 4, 1))
        assertFalse(TurboTcpForwarder.hasSendWindow(0xffff_fffeL, 1, 3, 1))
        assertTrue(TurboTcpForwarder.hasSendWindow(0xffff_ffffL, 1, 3, 1))
        assertTrue(TurboTcpForwarder.isClientSegmentInReceiveWindow(200, 0, 200, 4))
        assertTrue(TurboTcpForwarder.isClientSegmentInReceiveWindow(203, 0, 200, 4))
        assertFalse(TurboTcpForwarder.isClientSegmentInReceiveWindow(204, 0, 200, 4))
        assertFalse(TurboTcpForwarder.isClientSegmentInReceiveWindow(199, 0, 200, 4))
        assertTrue(TurboTcpForwarder.isClientSegmentInReceiveWindow(0xffff_ffffL, 2, 0, 4))
        assertTrue(TurboTcpForwarder.isClientSegmentInReceiveWindow(200, 0, 200, 0))
        assertFalse(TurboTcpForwarder.isClientSegmentInReceiveWindow(200, 1, 200, 0))
    }

    @Test
    fun recognizesOnlyTheTls13HelloRetryRequestRandom() {
        val random = byteArrayOf(
            0xcf.toByte(), 0x21, 0xad.toByte(), 0x74, 0xe5.toByte(), 0x9a.toByte(), 0x61, 0x11,
            0xbe.toByte(), 0x1d, 0x8c.toByte(), 0x02, 0x1e, 0x65, 0xb8.toByte(), 0x91.toByte(),
            0xc2.toByte(), 0xa2.toByte(), 0x11, 0x16, 0x7a, 0xbb.toByte(), 0x8c.toByte(), 0x5e,
            0x07, 0x9e.toByte(), 0x09, 0xe2.toByte(), 0xc8.toByte(), 0xa8.toByte(), 0x33, 0x9c.toByte()
        )
        val record = testServerHello(random)

        assertTrue(TurboTcpForwarder.isHelloRetryRequest(record))
        record[20] = (record[20].toInt() xor 1).toByte()
        assertFalse(TurboTcpForwarder.isHelloRetryRequest(record))
    }

    @Test
    fun idleSynIsReapedAndCapacityIsReleased() = kotlinx.coroutines.runBlocking {
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true,
            clientHandshakeIdleTimeoutMs = 30L
        )

        assertTrue(forwarder.handleIpv4Packet(tcpPacket(byteArrayOf(10, 0, 0, 5), byteArrayOf(93, 184.toByte(), 216.toByte(), 34), 50100, 443, 100, 0x02), 40))
        kotlinx.coroutines.delay(120L)

        assertTrue(packets.size >= 2)
        assertEquals(0x14, packets.last()[33].toInt() and 0xff)
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun incompleteTlsPrefixCannotHoldAConnectionPastTheHandshakeDeadline() = kotlinx.coroutines.runBlocking {
        val mapper = TurboDomainMapper()
        val mapping = mapper.map("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))!!
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = mapper,
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            clientHandshakeIdleTimeoutMs = 30L
        )
        val syn = tcpPacket(byteArrayOf(10, 0, 0, 5), mapping.virtualAddress, 50102, 443, 100, 0x02)
        val prefix = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            mapping.virtualAddress,
            50102,
            443,
            101,
            0x18,
            byteArrayOf(22)
        )

        assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
        assertTrue(forwarder.handleIpv4Packet(prefix, prefix.size))
        kotlinx.coroutines.delay(120L)

        assertEquals(0x14, packets.last()[33].toInt() and 0xff)
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun disabledFragmentationRelaysWithoutWaitingForClientHelloParsing() = kotlinx.coroutines.runBlocking {
        val mapper = TurboDomainMapper()
        val mapping = mapper.map("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))!!
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = mapper,
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            tlsFragmentationEnabled = false,
            clientHandshakeIdleTimeoutMs = 5_000L
        )
        val syn = tcpPacket(byteArrayOf(10, 0, 0, 5), mapping.virtualAddress, 50103, 443, 100, 0x02)
        val prefix = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            mapping.virtualAddress,
            50103,
            443,
            101,
            0x18,
            byteArrayOf(22)
        )

        assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
        assertTrue(forwarder.handleIpv4Packet(prefix, prefix.size))
        kotlinx.coroutines.delay(120L)

        assertEquals(0x14, packets.last()[33].toInt() and 0xff)
        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun duplicateFinDoesNotAdvanceAcknowledgementTwice() {
        val mapper = TurboDomainMapper()
        val mapping = mapper.map("example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))!!
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = mapper,
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it }
        )
        val syn = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            mapping.virtualAddress,
            50101,
            443,
            100,
            0x02
        )
        val fin = tcpPacket(
            byteArrayOf(10, 0, 0, 5),
            mapping.virtualAddress,
            50101,
            443,
            101,
            0x11
        )

        assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
        assertTrue(forwarder.handleIpv4Packet(fin, fin.size))
        assertEquals(102L, u32(packets.last(), 28))
        assertTrue(forwarder.handleIpv4Packet(fin, fin.size))
        assertEquals(102L, u32(packets.last(), 28))

        forwarder.closeAll()
        scope.cancel()
    }

    @Test
    fun plainFallbackIsAttemptedNoLaterThanThird() {
        val modes = listOf(
            TlsClientHello.FragmentationMode.HOSTNAME,
            TlsClientHello.FragmentationMode.SINGLE_SAFE,
            TlsClientHello.FragmentationMode.SNI_MULTI,
            TlsClientHello.FragmentationMode.NONE
        )

        val ordered = TurboTcpForwarder.prioritizePlainFallback(modes)

        assertEquals(TlsClientHello.FragmentationMode.NONE, ordered[2])
        assertEquals(TlsClientHello.FragmentationMode.HOSTNAME, ordered.first())
        assertEquals(modes.toSet(), ordered.toSet())
    }

    @Test
    fun staleOrInvalidAckCannotReplaceTheCurrentSendWindow() {
        assertFalse(
            TurboTcpForwarder.shouldUpdateSendWindow(
                segmentSequence = 99,
                ackNumber = 150,
                sndUna = 100,
                sndNxt = 200,
                sndWl1 = 100,
                sndWl2 = 150
            )
        )
        assertFalse(
            TurboTcpForwarder.shouldUpdateSendWindow(
                segmentSequence = 101,
                ackNumber = 201,
                sndUna = 100,
                sndNxt = 200,
                sndWl1 = 100,
                sndWl2 = 150
            )
        )
        assertTrue(
            TurboTcpForwarder.shouldUpdateSendWindow(
                segmentSequence = 100,
                ackNumber = 151,
                sndUna = 100,
                sndNxt = 200,
                sndWl1 = 100,
                sndWl2 = 150
            )
        )
    }

    @Test
    fun retransmissionQueueIsBoundedByBytesAndSegments() {
        val byteCounter = AtomicInteger()
        val byteBounded = TurboTcpRetransmissionQueue(
            maxSegments = 3,
            perFlowCapacity = 8,
            globalBytes = byteCounter,
            globalCapacity = 8,
            initialRtoMs = 100,
            maxRtoMs = 400,
            maxRetransmissions = 3
        )
        assertTrue(byteBounded.tryEnqueue(10, 1, ByteArray(4), 0))
        assertTrue(byteBounded.tryEnqueue(11, 1, ByteArray(4), 0))
        assertFalse(byteBounded.tryEnqueue(12, 1, ByteArray(1), 0))
        assertEquals(8, byteBounded.retainedBytes())
        assertEquals(8, byteCounter.get())

        val segmentCounter = AtomicInteger()
        val segmentBounded = TurboTcpRetransmissionQueue(
            maxSegments = 1,
            perFlowCapacity = 16,
            globalBytes = segmentCounter,
            globalCapacity = 16,
            initialRtoMs = 100,
            maxRtoMs = 400,
            maxRetransmissions = 3
        )
        assertTrue(segmentBounded.tryEnqueue(20, 1, ByteArray(1), 0))
        assertFalse(segmentBounded.tryEnqueue(21, 1, ByteArray(1), 0))
        assertEquals(1, segmentBounded.size())
        assertEquals(1, segmentBounded.clear())
        assertEquals(0, segmentCounter.get())
    }

    @Test
    fun cumulativeAckReleasesRetainedPacketsAcrossSequenceWrap() {
        val globalBytes = AtomicInteger()
        val queue = TurboTcpRetransmissionQueue(
            maxSegments = 4,
            perFlowCapacity = 32,
            globalBytes = globalBytes,
            globalCapacity = 32,
            initialRtoMs = 100,
            maxRtoMs = 400,
            maxRetransmissions = 3
        )
        assertTrue(queue.tryEnqueue(0xffff_fffeL, 2, ByteArray(4), 0))
        assertTrue(queue.tryEnqueue(0, 3, ByteArray(5), 0))

        assertEquals(4, queue.acknowledge(0, 10))
        assertEquals(5, queue.retainedBytes())
        assertEquals(5, globalBytes.get())
        assertEquals(5, queue.acknowledge(3, 20))
        assertEquals(0, queue.size())
        assertEquals(0, globalBytes.get())
    }

    @Test
    fun retransmissionRtoBacksOffAndStopsAtTheRetryLimit() {
        val queue = TurboTcpRetransmissionQueue(
            maxSegments = 2,
            perFlowCapacity = 16,
            globalBytes = AtomicInteger(),
            globalCapacity = 16,
            initialRtoMs = 100,
            maxRtoMs = 400,
            maxRetransmissions = 3
        )
        val packet = byteArrayOf(1, 2, 3)
        assertTrue(queue.tryEnqueue(100, 1, packet, 0))
        assertNull(queue.pollTimeout(99))

        val first = queue.pollTimeout(100)!!
        assertFalse(first.exhausted)
        assertEquals(1, first.retransmissionCount)
        assertEquals(200L, first.nextRtoMs)
        assertArrayEquals(packet, first.packet)
        assertNull(queue.pollTimeout(299))

        val second = queue.pollTimeout(300)!!
        assertEquals(2, second.retransmissionCount)
        assertEquals(400L, second.nextRtoMs)
        val third = queue.pollTimeout(700)!!
        assertEquals(3, third.retransmissionCount)
        assertEquals(400L, third.nextRtoMs)
        val exhausted = queue.pollTimeout(1_100)!!
        assertTrue(exhausted.exhausted)
        assertEquals(3, exhausted.retransmissionCount)
        assertNull(exhausted.packet)
    }

    @Test
    fun zeroWindowPausesRtoAndRearmsItWhenTheWindowOpens() {
        val queue = TurboTcpRetransmissionQueue(
            maxSegments = 2,
            perFlowCapacity = 16,
            globalBytes = AtomicInteger(),
            globalCapacity = 16,
            initialRtoMs = 100,
            maxRtoMs = 400,
            maxRetransmissions = 3
        )
        assertTrue(queue.tryEnqueue(100, 1, byteArrayOf(1), 0))
        queue.pauseTimeouts()
        assertNull(queue.pollTimeout(10_000))
        queue.resumeTimeouts(10_000)
        assertNull(queue.pollTimeout(10_099))
        val retry = queue.pollTimeout(10_100)!!
        assertEquals(1, retry.retransmissionCount)
        assertFalse(retry.exhausted)
    }

    @Test
    fun persistTimerUsesBoundedBackoffAndCanBeFullyReset() {
        val timer = TurboTcpPersistTimer(initialDelayMs = 100, maxDelayMs = 400)
        assertFalse(timer.isArmed())
        assertEquals(100L, timer.millisUntilProbe(1_000))
        assertTrue(timer.isArmed())
        assertEquals(1L, timer.millisUntilProbe(1_099))
        assertEquals(0L, timer.millisUntilProbe(1_100))
        assertEquals(200L, timer.onProbe(1_100))
        assertEquals(200L, timer.millisUntilProbe(1_100))
        assertEquals(0L, timer.millisUntilProbe(1_300))
        assertEquals(400L, timer.onProbe(1_300))
        assertEquals(0L, timer.millisUntilProbe(1_700))
        assertEquals(400L, timer.onProbe(1_700))
        timer.reset()
        assertFalse(timer.isArmed())
        assertEquals(100L, timer.millisUntilProbe(5_000))
    }

    @Test
    fun persistProbeTracksTheFirstUnacknowledgedByteAcrossWrap() {
        val queue = TurboTcpRetransmissionQueue(
            maxSegments = 2,
            perFlowCapacity = 16,
            globalBytes = AtomicInteger(),
            globalCapacity = 16,
            initialRtoMs = 100,
            maxRtoMs = 400,
            maxRetransmissions = 3
        )
        val packet = byteArrayOf(10, 11)
        assertTrue(
            queue.tryEnqueue(
                sequence = 0xffff_ffffL,
                sequenceLength = 2,
                packet = packet,
                nowMs = 0,
                payloadOffset = 0,
                payloadLength = 2
            )
        )

        val probe = queue.persistProbe(0)!!
        assertEquals(0L, probe.sequence)
        assertEquals(11.toByte(), probe.value)
        assertNull(probe.packet)
    }

    @Test
    fun dataAdmissionPreservesFinBufferAndSegmentHeadroom() {
        val queue = TurboTcpRetransmissionQueue(
            maxSegments = 3,
            perFlowCapacity = 100,
            globalBytes = AtomicInteger(),
            globalCapacity = 100,
            initialRtoMs = 100,
            maxRtoMs = 400,
            maxRetransmissions = 3
        )
        assertTrue(queue.tryEnqueue(1, 1, ByteArray(10), 0, hasSyn = true))
        assertEquals(70, queue.availableDataPacketBytes(reservedSegments = 1, reservedBytes = 20))
        assertTrue(queue.tryEnqueue(2, 70, ByteArray(70), 0, payloadOffset = 0, payloadLength = 70))
        assertEquals(0, queue.availableDataPacketBytes(reservedSegments = 1, reservedBytes = 20))
        assertTrue(queue.tryEnqueue(72, 1, ByteArray(20), 0, hasFin = true))
        assertEquals(3, queue.size())
    }

    @Test
    fun acknowledgedTunPacketsReleaseMemoryAndHandoverClearsTheRest() {
        val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )
        val client = byteArrayOf(10, 0, 0, 5)
        val destination = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

        try {
            val syn = tcpPacket(client, destination, 50_200, 443, 100, 0x02)
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val serverSequence = u32(packets.single(), 24)
            assertTrue(forwarder.retainedTunRetransmissionBytes() > 0)
            val ack = tcpPacket(
                client,
                destination,
                50_200,
                443,
                101,
                0x10,
                acknowledgement = (serverSequence + 1L).toInt()
            )
            assertTrue(forwarder.handleIpv4Packet(ack, ack.size))
            assertEquals(0, forwarder.retainedTunRetransmissionBytes())

            val secondSyn = tcpPacket(client, destination, 50_201, 443, 200, 0x02)
            assertTrue(forwarder.handleIpv4Packet(secondSyn, secondSyn.size))
            assertTrue(forwarder.retainedTunRetransmissionBytes() > 0)
            forwarder.resetConnections()
            assertEquals(0, forwarder.retainedTunRetransmissionBytes())
        } finally {
            forwarder.closeAll()
            assertEquals(0, forwarder.retainedTunRetransmissionBytes())
            scope.cancel()
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
        options: ByteArray = ByteArray(0),
        acknowledgement: Int = 0,
        window: Int = 65_535
    ): ByteArray {
        require(options.size % 4 == 0)
        val tcpHeaderLength = 20 + options.size
        val packet = ByteArray(20 + tcpHeaderLength + payload.size)
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
        packet[32] = ((tcpHeaderLength / 4) shl 4).toByte()
        packet[33] = flags.toByte()
        put16(packet, 34, window)
        options.copyInto(packet, 40)
        payload.copyInto(packet, 20 + tcpHeaderLength)
        return packet
    }

    private fun ipv6TcpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Int,
        flags: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val tcpLength = 20 + payload.size
        val packet = ByteArray(40 + tcpLength)
        packet[0] = 0x60
        put16(packet, 4, tcpLength)
        packet[6] = 6
        packet[7] = 64
        source.copyInto(packet, 8)
        destination.copyInto(packet, 24)
        put16(packet, 40, sourcePort)
        put16(packet, 42, destinationPort)
        put32(packet, 44, sequence)
        packet[52] = 0x50
        packet[53] = flags.toByte()
        put16(packet, 54, 65_535)
        payload.copyInto(packet, 60)
        put16(packet, 56, ipv6UpperLayerChecksum(packet, 40, tcpLength, 6))
        return packet
    }

    private fun ipv6UpperLayerChecksum(packet: ByteArray, offset: Int, length: Int, protocol: Int): Int {
        var sum = checksumSum(packet, 8, 32)
        sum += length.toLong()
        sum += protocol.toLong()
        sum += checksumSum(packet, offset, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun testClientHello(host: String): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val sni = ByteArray(9 + hostBytes.size)
        put16(sni, 0, 0)
        put16(sni, 2, 5 + hostBytes.size)
        put16(sni, 4, 3 + hostBytes.size)
        put16(sni, 7, hostBytes.size)
        hostBytes.copyInto(sni, 9)
        val body = ByteArray(2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + sni.size)
        var offset = 0
        body[offset++] = 3
        body[offset++] = 3
        offset += 32
        body[offset++] = 0
        put16(body, offset, 2)
        offset += 2
        body[offset++] = 0x13
        body[offset++] = 0x01
        body[offset++] = 1
        body[offset++] = 0
        put16(body, offset, sni.size)
        offset += 2
        sni.copyInto(body, offset)
        val handshake = ByteArray(4 + body.size)
        handshake[0] = 1
        handshake[1] = (body.size ushr 16).toByte()
        handshake[2] = (body.size ushr 8).toByte()
        handshake[3] = body.size.toByte()
        body.copyInto(handshake, 4)
        val record = ByteArray(5 + handshake.size)
        record[0] = 22
        record[1] = 3
        record[2] = 3
        put16(record, 3, handshake.size)
        handshake.copyInto(record, 5)
        return record
    }

    private fun testServerHello(random: ByteArray = ByteArray(32)): ByteArray {
        require(random.size == 32)
        val body = ByteArray(40)
        body[0] = 3
        body[1] = 3
        random.copyInto(body, 2)
        body[34] = 0
        body[35] = 0x13
        body[36] = 0x01
        body[37] = 0
        put16(body, 38, 0)
        val handshake = ByteArray(4 + body.size)
        handshake[0] = 2
        handshake[3] = body.size.toByte()
        body.copyInto(handshake, 4)
        val record = ByteArray(5 + handshake.size)
        record[0] = 22
        record[1] = 3
        record[2] = 3
        put16(record, 3, handshake.size)
        handshake.copyInto(record, 5)
        return record
    }

    private fun tlsHandshakeRecord(body: ByteArray): ByteArray {
        val record = ByteArray(5 + body.size)
        record[0] = 22
        record[1] = 3
        record[2] = 3
        put16(record, 3, body.size)
        body.copyInto(record, 5)
        return record
    }

    private fun readUntilTimeout(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = try {
                input.read(buffer)
            } catch (_: java.net.SocketTimeoutException) {
                break
            }
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        val HELLO_RETRY_RANDOM = byteArrayOf(
            0xcf.toByte(), 0x21, 0xad.toByte(), 0x74, 0xe5.toByte(), 0x9a.toByte(), 0x61, 0x11,
            0xbe.toByte(), 0x1d, 0x8c.toByte(), 0x02, 0x1e, 0x65, 0xb8.toByte(), 0x91.toByte(),
            0xc2.toByte(), 0xa2.toByte(), 0x11, 0x16, 0x7a, 0xbb.toByte(), 0x8c.toByte(), 0x5e,
            0x07, 0x9e.toByte(), 0x09, 0xe2.toByte(), 0xc8.toByte(), 0xa8.toByte(), 0x33, 0x9c.toByte()
        )
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

    private fun hasTcpOption(packet: ByteArray, kind: Int, length: Int): Boolean {
        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < 20 || ipHeaderLength + 20 > packet.size) return false
        val tcpHeaderLength = ((packet[ipHeaderLength + 12].toInt() ushr 4) and 0x0f) * 4
        if (tcpHeaderLength < 20 || ipHeaderLength + tcpHeaderLength > packet.size) return false
        var offset = ipHeaderLength + 20
        val end = ipHeaderLength + tcpHeaderLength
        while (offset < end) {
            val optionKind = packet[offset].toInt() and 0xff
            when (optionKind) {
                0 -> return false
                1 -> offset += 1
                else -> {
                    if (offset + 1 >= end) return false
                    val optionLength = packet[offset + 1].toInt() and 0xff
                    if (optionLength < 2 || offset + optionLength > end) return false
                    if (optionKind == kind && optionLength == length) return true
                    offset += optionLength
                }
            }
        }
        return false
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
}
