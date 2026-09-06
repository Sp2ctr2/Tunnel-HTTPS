package com.tunnelvpn.app

class TlsMetadataInspector(private val diagnostics: DiagnosticsState) {
    fun inspectIpv4(packet: ByteArray, length: Int) {
        if (length !in 60..packet.size) return
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (((packet[0].toInt() ushr 4) and 0x0f) != 4 || ihl < 20) return
        if ((packet[9].toInt() and 0xff) != TCP_PROTOCOL) return
        val tcpOffset = ihl
        if (tcpOffset + 20 > length) return
        if (u16(packet, tcpOffset + 2) != HTTPS_PORT) return
        val tcpHeaderLength = ((packet[tcpOffset + 12].toInt() ushr 4) and 0x0f) * 4
        if (tcpHeaderLength < 20) return
        val payloadOffset = tcpOffset + tcpHeaderLength
        if (payloadOffset >= length || packet[payloadOffset] != TLS_HANDSHAKE) return
        diagnostics.httpsClientHelloInspected.incrementAndGet()
        if (containsServerNameExtension(packet, payloadOffset, length)) diagnostics.sniSeen.incrementAndGet()
    }

    private fun containsServerNameExtension(data: ByteArray, offset: Int, length: Int): Boolean {
        var index = offset
        if (index + 5 > length || data[index] != TLS_HANDSHAKE) return false
        val recordLength = u16(data, index + 3)
        index += 5
        val recordEnd = (index + recordLength).coerceAtMost(length)
        if (index + 4 > recordEnd || data[index] != CLIENT_HELLO) return false
        index += 4
        if (index + 34 > recordEnd) return false
        index += 34
        if (index >= recordEnd) return false
        val sessionLength = data[index].toInt() and 0xff
        index += 1 + sessionLength
        if (index + 2 > recordEnd) return false
        val cipherLength = u16(data, index)
        index += 2 + cipherLength
        if (index >= recordEnd) return false
        val compressionLength = data[index].toInt() and 0xff
        index += 1 + compressionLength
        if (index + 2 > recordEnd) return false
        val extensionsEnd = (index + 2 + u16(data, index)).coerceAtMost(recordEnd)
        index += 2
        while (index + 4 <= extensionsEnd) {
            val type = u16(data, index)
            val extLength = u16(data, index + 2)
            if (type == SERVER_NAME_EXTENSION && index + 4 + extLength <= extensionsEnd) return true
            index += 4 + extLength
        }
        return false
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    companion object {
        private const val TCP_PROTOCOL = 6
        private const val HTTPS_PORT = 443
        private const val TLS_HANDSHAKE = 22.toByte()
        private const val CLIENT_HELLO = 1.toByte()
        private const val SERVER_NAME_EXTENSION = 0
    }
}
