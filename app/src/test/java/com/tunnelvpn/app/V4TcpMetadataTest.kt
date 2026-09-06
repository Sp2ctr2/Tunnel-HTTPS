package com.tunnelvpn.app

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V4TcpMetadataTest {
    @Test
    fun directEstablishedAckKeepsExistingFlowBehavior() {
        val packets = CopyOnWriteArrayList<ByteArray>()
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
            val syn = tcpPacket(client, destination, 50_001, 443, 100, SYN)
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val serverInitial = u32(packets.single(), 24)
            val ack = tcpPacket(
                client,
                destination,
                50_001,
                443,
                101,
                ACK,
                acknowledgement = (serverInitial + 1L).toInt()
            )

            assertTrue(forwarder.handleIpv4Packet(ack, ack.size))
            assertEquals(1, packets.size)
            assertEquals(1, forwarder.activeConnectionCount())
        } finally {
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun unknownDirectAckStillReceivesReset() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = TurboDomainMapper(),
            diagnostics = DiagnosticsState(),
            scope = scope,
            writeTunPacket = { packets += it },
            directHttpsForwarding = true
        )

        try {
            val ack = tcpPacket(
                byteArrayOf(10, 0, 0, 6),
                byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
                50_002,
                443,
                200,
                ACK,
                acknowledgement = 900
            )

            assertTrue(forwarder.handleIpv4Packet(ack, ack.size))
            assertEquals(1, packets.size)
            assertEquals(RST, flags(packets.single()))
            assertEquals(0, forwarder.activeConnectionCount())
        } finally {
            forwarder.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun invalidatedVirtualMappingStillRejectsExistingTupleBeforeLookup() {
        var networkGeneration = 0L
        val mapper = TurboDomainMapper(networkGenerationProvider = { networkGeneration })
        val mapping = mapper.map(
            "metadata.example",
            byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
            expectedNetworkGeneration = networkGeneration
        )!!
        val packets = CopyOnWriteArrayList<ByteArray>()
        val diagnostics = DiagnosticsState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forwarder = TurboTcpForwarder(
            protectSocket = { false },
            mapper = mapper,
            diagnostics = diagnostics,
            scope = scope,
            writeTunPacket = { packets += it }
        )
        val client = byteArrayOf(10, 0, 0, 7)

        try {
            val syn = tcpPacket(client, mapping.virtualAddress, 50_003, 443, 300, SYN)
            assertTrue(forwarder.handleIpv4Packet(syn, syn.size))
            val serverInitial = u32(packets.single(), 24)
            networkGeneration = 2L
            mapper.updateNetworkGeneration(networkGeneration)
            val ack = tcpPacket(
                client,
                mapping.virtualAddress,
                50_003,
                443,
                301,
                ACK,
                acknowledgement = (serverInitial + 1L).toInt()
            )

            assertTrue(forwarder.handleIpv4Packet(ack, ack.size))
            assertEquals(2, packets.size)
            assertEquals(RST, flags(packets.last()))
            assertEquals(1L, diagnostics.turboTcpForwardingErrors.get())
        } finally {
            forwarder.closeAll()
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
        acknowledgement: Int = 0
    ): ByteArray {
        val packet = ByteArray(40)
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
        put16(packet, 34, 65_535)
        return packet
    }

    private fun flags(packet: ByteArray): Int = packet[33].toInt() and 0xff

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

    private companion object {
        const val SYN = 0x02
        const val RST = 0x04
        const val ACK = 0x10
    }
}
