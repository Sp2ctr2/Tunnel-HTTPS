package com.tunnelvpn.app

internal object Ipv6IcmpErrorPolicy {
    fun permits(original: ByteArray, length: Int, allowMulticastDestination: Boolean): Boolean {
        if (length !in IPV6_HEADER_LENGTH..original.size) return false
        if (((original[0].toInt() ushr 4) and 0x0f) != IPV6_VERSION) return false
        if (u16(original, PAYLOAD_LENGTH_OFFSET) != length - IPV6_HEADER_LENGTH) return false
        if (!isUsableUnicast(original, SOURCE_OFFSET)) return false
        if (!isUsableDestination(original, DESTINATION_OFFSET, allowMulticastDestination)) return false
        val terminal = terminalHeader(original, length) ?: return false
        if (terminal.first == ICMPV6_PROTOCOL) {
            if (terminal.second >= length) return false
            if (isErrorOrRedirect(original[terminal.second].toInt() and 0xff)) return false
        }
        return true
    }

    fun isErrorOrRedirect(type: Int): Boolean {
        return type in 0 until ICMPV6_INFORMATIONAL_TYPE || type == ICMPV6_REDIRECT
    }

    fun isUsableUnicast(address: ByteArray): Boolean {
        return address.size == IPV6_ADDRESS_LENGTH && isUsableUnicast(address, 0)
    }

    fun isUsableUnicast(packet: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset > packet.size - IPV6_ADDRESS_LENGTH) return false
        return !isUnspecified(packet, offset) && !isMulticast(packet, offset)
    }

    fun isUsableDestination(packet: ByteArray, offset: Int, allowMulticast: Boolean): Boolean {
        if (offset < 0 || offset > packet.size - IPV6_ADDRESS_LENGTH) return false
        return !isUnspecified(packet, offset) && (allowMulticast || !isMulticast(packet, offset))
    }

    private fun terminalHeader(packet: ByteArray, length: Int): Pair<Int, Int>? {
        var protocol = packet[NEXT_HEADER_OFFSET].toInt() and 0xff
        var offset = IPV6_HEADER_LENGTH
        var count = 0
        while (isExtensionHeader(protocol)) {
            if (count >= MAX_EXTENSION_HEADERS || offset + 2 > length) return null
            if (protocol == FRAGMENT) return null
            val headerLength = if (protocol == AUTHENTICATION) {
                ((packet[offset + 1].toInt() and 0xff) + 2) * 4
            } else {
                ((packet[offset + 1].toInt() and 0xff) + 1) * 8
            }
            if (headerLength < 8 || headerLength > length - offset) return null
            protocol = packet[offset].toInt() and 0xff
            offset += headerLength
            count += 1
        }
        return protocol to offset
    }

    private fun isExtensionHeader(protocol: Int): Boolean {
        return protocol == HOP_BY_HOP || protocol == ROUTING || protocol == FRAGMENT ||
            protocol == AUTHENTICATION || protocol == DESTINATION_OPTIONS ||
            protocol == MOBILITY || protocol == HIP || protocol == SHIM6
    }

    private fun isUnspecified(data: ByteArray, offset: Int): Boolean {
        for (index in offset until offset + IPV6_ADDRESS_LENGTH) {
            if (data[index].toInt() != 0) return false
        }
        return true
    }

    private fun isMulticast(data: ByteArray, offset: Int): Boolean {
        return data[offset].toInt() and 0xff == 0xff
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private const val IPV6_VERSION = 6
    private const val IPV6_HEADER_LENGTH = 40
    private const val IPV6_ADDRESS_LENGTH = 16
    private const val PAYLOAD_LENGTH_OFFSET = 4
    private const val NEXT_HEADER_OFFSET = 6
    private const val SOURCE_OFFSET = 8
    private const val DESTINATION_OFFSET = 24
    private const val HOP_BY_HOP = 0
    private const val ROUTING = 43
    private const val FRAGMENT = 44
    private const val AUTHENTICATION = 51
    private const val ICMPV6_PROTOCOL = 58
    private const val DESTINATION_OPTIONS = 60
    private const val MOBILITY = 135
    private const val HIP = 139
    private const val SHIM6 = 140
    private const val ICMPV6_INFORMATIONAL_TYPE = 128
    private const val ICMPV6_REDIRECT = 137
    private const val MAX_EXTENSION_HEADERS = 8
}
