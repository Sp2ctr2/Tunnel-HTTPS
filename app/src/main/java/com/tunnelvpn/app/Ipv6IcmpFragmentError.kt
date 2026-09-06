package com.tunnelvpn.app

internal object Ipv6IcmpFragmentError {
    fun parameterProblem(
        original: ByteArray,
        code: Int,
        pointer: Int,
        allowMulticastDestination: Boolean = false,
        responseSource: ByteArray? = null
    ): ByteArray? {
        if (code !in 0..2 || pointer < 0) return null
        return build(original, PARAMETER_PROBLEM, code, pointer, allowMulticastDestination, responseSource)
    }

    fun incompleteHeaderChain(original: ByteArray): ByteArray? {
        return build(original, PARAMETER_PROBLEM, INCOMPLETE_HEADER_CHAIN, 0)
    }

    fun reassemblyTimeout(original: ByteArray): ByteArray? {
        return build(original, TIME_EXCEEDED, FRAGMENT_REASSEMBLY_TIMEOUT, 0)
    }

    private fun build(
        original: ByteArray,
        type: Int,
        code: Int,
        value: Int,
        allowMulticastDestination: Boolean = false,
        responseSource: ByteArray? = null
    ): ByteArray? {
        if (original.size < IPV6_HEADER_LENGTH || original.size > MAX_IPV6_PACKET_LENGTH) return null
        if ((original[0].toInt() ushr 4) and 0x0f != IPV6_VERSION) return null
        if (u16(original, PAYLOAD_LENGTH_OFFSET) != original.size - IPV6_HEADER_LENGTH) return null
        if (!Ipv6IcmpErrorPolicy.isUsableUnicast(original, SOURCE_OFFSET) || isIcmpv6ErrorFirstFragment(original)) {
            return null
        }
        val originalDestinationIsUnicast = Ipv6IcmpErrorPolicy.isUsableUnicast(original, DESTINATION_OFFSET)
        val source = when {
            originalDestinationIsUnicast -> original.copyOfRange(DESTINATION_OFFSET, DESTINATION_OFFSET + ADDRESS_LENGTH)
            allowMulticastDestination &&
                Ipv6IcmpErrorPolicy.isUsableDestination(original, DESTINATION_OFFSET, true) &&
                responseSource != null && Ipv6IcmpErrorPolicy.isUsableUnicast(responseSource) -> responseSource
            else -> return null
        }
        val quoteLength = minOf(original.size, MAX_QUOTED_PACKET_LENGTH)
        val icmpLength = ICMP_HEADER_LENGTH + quoteLength
        val result = ByteArray(IPV6_HEADER_LENGTH + icmpLength)
        result[0] = 0x60
        put16(result, PAYLOAD_LENGTH_OFFSET, icmpLength)
        result[NEXT_HEADER_OFFSET] = ICMPV6_PROTOCOL.toByte()
        result[HOP_LIMIT_OFFSET] = DEFAULT_HOP_LIMIT.toByte()
        source.copyInto(result, SOURCE_OFFSET)
        original.copyInto(result, DESTINATION_OFFSET, SOURCE_OFFSET, SOURCE_OFFSET + ADDRESS_LENGTH)
        result[IPV6_HEADER_LENGTH] = type.toByte()
        result[IPV6_HEADER_LENGTH + 1] = code.toByte()
        put32(result, IPV6_HEADER_LENGTH + 4, value)
        original.copyInto(result, IPV6_HEADER_LENGTH + ICMP_HEADER_LENGTH, 0, quoteLength)
        put16(result, IPV6_HEADER_LENGTH + 2, checksum(result, icmpLength))
        return result
    }

    private fun isIcmpv6ErrorFirstFragment(packet: ByteArray): Boolean {
        var protocol = packet[NEXT_HEADER_OFFSET].toInt() and 0xff
        var offset = IPV6_HEADER_LENGTH
        var count = 0
        var fragmentSeen = false
        while (true) {
            if (protocol == FRAGMENT) {
                if (fragmentSeen || offset + FRAGMENT_HEADER_LENGTH > packet.size) return false
                val fragmentField = u16(packet, offset + 2)
                if (fragmentField and FRAGMENT_OFFSET_MASK != 0) return false
                protocol = packet[offset].toInt() and 0xff
                offset += FRAGMENT_HEADER_LENGTH
                fragmentSeen = true
                continue
            }
            if (protocol != HOP_BY_HOP && protocol != ROUTING && protocol != AUTHENTICATION &&
                protocol != DESTINATION_OPTIONS && protocol != MOBILITY && protocol != HIP && protocol != SHIM6
            ) break
            if (count >= MAX_EXTENSION_HEADERS) return true
            if (offset + 2 > packet.size) return false
            val headerLength = if (protocol == AUTHENTICATION) {
                ((packet[offset + 1].toInt() and 0xff) + 2) * 4
            } else {
                ((packet[offset + 1].toInt() and 0xff) + 1) * 8
            }
            if (headerLength < 8 || headerLength > packet.size - offset) return false
            protocol = packet[offset].toInt() and 0xff
            offset += headerLength
            count += 1
        }
        if (protocol == HOP_BY_HOP || protocol == ROUTING || protocol == FRAGMENT ||
            protocol == AUTHENTICATION || protocol == DESTINATION_OPTIONS || protocol == MOBILITY ||
            protocol == HIP || protocol == SHIM6
        ) return true
        return protocol == ICMPV6_PROTOCOL && offset < packet.size &&
            Ipv6IcmpErrorPolicy.isErrorOrRedirect(packet[offset].toInt() and 0xff)
    }

    private fun checksum(packet: ByteArray, icmpLength: Int): Int {
        var sum = wordSum(packet, SOURCE_OFFSET, ADDRESS_LENGTH * 2)
        sum += (icmpLength ushr 16).toLong()
        sum += (icmpLength and 0xffff).toLong()
        sum += ICMPV6_PROTOCOL.toLong()
        sum += wordSum(packet, IPV6_HEADER_LENGTH, icmpLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        val value = sum.inv().toInt() and 0xffff
        return if (value == 0) 0xffff else value
    }

    private fun wordSum(data: ByteArray, offset: Int, length: Int): Long {
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
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private const val IPV6_VERSION = 6
    private const val IPV6_HEADER_LENGTH = 40
    private const val MAX_IPV6_PACKET_LENGTH = IPV6_HEADER_LENGTH + 65_535
    private const val ADDRESS_LENGTH = 16
    private const val ICMP_HEADER_LENGTH = 8
    private const val MAX_QUOTED_PACKET_LENGTH = 1_232
    private const val PAYLOAD_LENGTH_OFFSET = 4
    private const val NEXT_HEADER_OFFSET = 6
    private const val HOP_LIMIT_OFFSET = 7
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
    private const val TIME_EXCEEDED = 3
    private const val PARAMETER_PROBLEM = 4
    private const val FRAGMENT_REASSEMBLY_TIMEOUT = 1
    private const val INCOMPLETE_HEADER_CHAIN = 3
    private const val FRAGMENT_HEADER_LENGTH = 8
    private const val FRAGMENT_OFFSET_MASK = 0xfff8
    private const val DEFAULT_HOP_LIMIT = 64
    private const val MAX_EXTENSION_HEADERS = 8
}
