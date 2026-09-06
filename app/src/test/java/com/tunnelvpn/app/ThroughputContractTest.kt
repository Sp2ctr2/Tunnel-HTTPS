package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputContractTest {
    private val tcpForwarder = File("src/main/java/com/tunnelvpn/app/TurboTcpForwarder.kt").readText()
    private val udpForwarder = File("src/main/java/com/tunnelvpn/app/TurboUdpForwarder.kt").readText()
    private val engine = File("src/main/java/com/tunnelvpn/app/LocalProtectionEngine.kt").readText()
    private val activity = File("src/main/java/com/tunnelvpn/app/MainActivity.kt").readText()
    private val service = File("src/main/java/com/tunnelvpn/app/TunnelVpnService.kt").readText()
    private val bootReceiver = File("src/main/java/com/tunnelvpn/app/BootReceiver.kt").readText()

    @Test
    fun establishedLocalRelayUsesJumboInternalMtuWithSafeFallback() {
        assertTrue(activity.contains("TunnelVpnService.PERFORMANCE_TUN_MTU"))
        assertTrue(activity.contains("TunnelVpnService.BATTERY_SAVER_TUN_MTU"))
        assertTrue(service.contains("const val PERFORMANCE_TUN_MTU = 32768"))
        assertTrue(service.contains("const val BATTERY_SAVER_TUN_MTU = 4096"))
        assertTrue(bootReceiver.contains("TunnelVpnService.PERFORMANCE_TUN_MTU"))
        assertTrue(engine.contains(".setMtu(config.mtu)"))
        assertTrue(engine.contains("listOf(config.mtu, COMPATIBILITY_TUN_MTU, FALLBACK_TUN_MTU)"))
        assertTrue(engine.contains("builder.setMtu(candidateMtu)"))
        assertTrue(engine.contains("private const val MAX_PACKET_SIZE = 65535"))
        assertTrue(engine.contains("private const val COMPATIBILITY_TUN_MTU = 9000"))
        assertTrue(engine.contains("private const val FALLBACK_TUN_MTU = 1500"))
    }

    @Test
    fun throughputPathRaisesBoundedConnectionAndDnsFanoutCapacity() {
        assertTrue(tcpForwarder.contains("private const val MAX_CONNECTIONS = 64"))
        assertTrue(tcpForwarder.contains("private const val TCP_SOFT_LIMIT = 56"))
        assertTrue(tcpForwarder.contains("tunMtu: Int = DEFAULT_TUN_MTU"))
        assertTrue(tcpForwarder.contains("clientMss ?: familyMaxPayload"))
        assertTrue(tcpForwarder.contains("tunMtu - IPV6_HEADER_LENGTH - TCP_MIN_HEADER_LENGTH"))
        assertTrue(tcpForwarder.contains("private const val MAX_TCP_PAYLOAD = 32728"))
        assertTrue(tcpForwarder.contains("CLIENT_RELAY_BUFFER_BYTES = 2 * 1024 * 1024"))
        assertTrue(tcpForwarder.contains("TUN_RETRANSMISSION_BUFFER_BYTES = 2 * 1024 * 1024"))
        assertTrue(tcpForwarder.contains("GLOBAL_TUN_RETRANSMISSION_BUFFER_BYTES = 64 * 1024 * 1024"))
        assertTrue(engine.contains("tunMtu = establishedMtu"))
        assertTrue(engine.contains("private const val MAX_CONCURRENT_DNS_QUERIES = 32"))
        assertTrue(engine.contains("Semaphore(MAX_CONCURRENT_DNS_QUERIES)"))
    }

    @Test
    fun serverReadHotPathDoesNotDuplicateTheWholeReadOrEverySegment() {
        assertTrue(tcpForwarder.contains("emitServerBytes(connection, buffer, read)"))
        assertTrue(tcpForwarder.contains("writeTcpPacket(connection, FLAG_ACK or FLAG_PSH, bytes, offset, chunkSize)"))
        assertFalse(tcpForwarder.contains("emitServerBytes(connection, buffer.copyOf(read))"))
        assertFalse(tcpForwarder.contains("bytes.copyOfRange(offset, offset + chunkSize)"))
    }

    @Test
    fun unbufferedTunAndSocketWritesDoNotFlushEveryPacket() {
        assertFalse(engine.contains("stream.flush()"))
        assertFalse(tcpForwarder.contains("output.flush()"))
        assertTrue(engine.contains("writeTunPacket = { packet -> writeTunPacketFast(packet) }"))
        assertTrue(engine.contains("private val tunOutputQueue = ArrayBlockingQueue<ByteArray>"))
        assertTrue(engine.contains("name = \"TunnelTunWriter\""))
        assertTrue(engine.contains("stream.write(packet)"))
    }

    @Test
    fun udpRelayRetainsOneOwnedPayloadInsteadOfDuplicatingEveryDatagram() {
        assertTrue(udpForwarder.contains("payload = packet.copyOfRange(payloadOffset, payloadEnd)"))
        assertTrue(udpForwarder.contains("++flow.nextOutboundSequence,\n                udp.payload,"))
        assertFalse(udpForwarder.contains("udp.payload.copyOf()"))
    }
}
