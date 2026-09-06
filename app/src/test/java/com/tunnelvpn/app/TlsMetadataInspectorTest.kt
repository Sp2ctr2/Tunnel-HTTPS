package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TlsMetadataInspectorTest {
    @Test
    fun detectsTlsClientHelloWithSniOnHttpsPacket() {
        val diagnostics = DiagnosticsState()
        val packet = ipv4TcpPacket(
            sourcePort = 51000,
            destinationPort = 443,
            payload = tlsClientHelloWithSni("example.com")
        )

        TlsMetadataInspector(diagnostics).inspectIpv4(packet, packet.size)

        assertEquals(1, diagnostics.httpsClientHelloInspected.get())
        assertEquals(1, diagnostics.sniSeen.get())
    }

    @Test
    fun ignoresNonHttpsPackets() {
        val diagnostics = DiagnosticsState()
        val packet = ipv4TcpPacket(
            sourcePort = 51000,
            destinationPort = 80,
            payload = tlsClientHelloWithSni("example.com")
        )

        TlsMetadataInspector(diagnostics).inspectIpv4(packet, packet.size)

        assertEquals(0, diagnostics.httpsClientHelloInspected.get())
        assertEquals(0, diagnostics.sniSeen.get())
    }

    private fun tlsClientHelloWithSni(host: String): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val serverName = ByteArray(3 + hostBytes.size)
        serverName[0] = 0
        put16(serverName, 1, hostBytes.size)
        hostBytes.copyInto(serverName, 3)

        val serverNameList = ByteArray(2 + serverName.size)
        put16(serverNameList, 0, serverName.size)
        serverName.copyInto(serverNameList, 2)

        val sniExtension = ByteArray(4 + serverNameList.size)
        put16(sniExtension, 0, 0)
        put16(sniExtension, 2, serverNameList.size)
        serverNameList.copyInto(sniExtension, 4)

        val clientHelloBody = mutableListOf<Byte>()
        clientHelloBody += 0x03
        clientHelloBody += 0x03
        repeat(32) { clientHelloBody += 0 }
        clientHelloBody += 0
        clientHelloBody += 0
        clientHelloBody += 2
        clientHelloBody += 0x13
        clientHelloBody += 0x01
        clientHelloBody += 1
        clientHelloBody += 0
        clientHelloBody += ((sniExtension.size ushr 8) and 0xff).toByte()
        clientHelloBody += (sniExtension.size and 0xff).toByte()
        clientHelloBody += sniExtension.toList()

        val handshakeLength = clientHelloBody.size
        val handshake = ByteArray(4 + handshakeLength)
        handshake[0] = 1
        handshake[1] = ((handshakeLength ushr 16) and 0xff).toByte()
        handshake[2] = ((handshakeLength ushr 8) and 0xff).toByte()
        handshake[3] = (handshakeLength and 0xff).toByte()
        clientHelloBody.toByteArray().copyInto(handshake, 4)

        val record = ByteArray(5 + handshake.size)
        record[0] = 22
        record[1] = 0x03
        record[2] = 0x03
        put16(record, 3, handshake.size)
        handshake.copyInto(record, 5)
        return record
    }

    private fun ipv4TcpPacket(sourcePort: Int, destinationPort: Int, payload: ByteArray): ByteArray {
        val totalLength = 20 + 20 + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        put16(packet, 2, totalLength)
        packet[8] = 64
        packet[9] = 6
        packet[12] = 10
        packet[13] = 0
        packet[14] = 0
        packet[15] = 5
        packet[16] = 93
        packet[17] = 184.toByte()
        packet[18] = 216.toByte()
        packet[19] = 34
        put16(packet, 20, sourcePort)
        put16(packet, 22, destinationPort)
        packet[32] = 0x50
        payload.copyInto(packet, 40)
        return packet
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }
}
