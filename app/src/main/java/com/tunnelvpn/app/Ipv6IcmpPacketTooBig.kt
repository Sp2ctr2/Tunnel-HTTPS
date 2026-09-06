package com.tunnelvpn.app

object Ipv6IcmpPacketTooBig {
    fun build(
        validatedPacket: ByteArray,
        length: Int,
        mtu: Int,
        sourceAddress: ByteArray? = null,
        invokingPacket: ByteArray = validatedPacket
    ): ByteArray? {
        if (length !in IPV6_HEADER_SIZE..validatedPacket.size || mtu < MINIMUM_IPV6_MTU) return null
        if ((validatedPacket[0].toInt() ushr 4) != IPV6_VERSION) return null
        if (read16(validatedPacket, PAYLOAD_LENGTH_OFFSET) + IPV6_HEADER_SIZE != length) return null
        val invokingLength = invokingPacketLength(invokingPacket) ?: return null
        if (invokingLength <= mtu || !sameAddresses(validatedPacket, invokingPacket)) return null
        val responseSource = sourceAddress ?: validatedPacket.copyOfRange(
            DESTINATION_OFFSET,
            DESTINATION_OFFSET + ADDRESS_SIZE
        )
        if (!Ipv6IcmpErrorPolicy.isUsableUnicast(responseSource)) return null
        if (!Ipv6IcmpErrorPolicy.permits(
                validatedPacket,
                length,
                allowMulticastDestination = sourceAddress != null
            )
        ) return null
        val quoteLength = minOf(
            invokingLength,
            MINIMUM_IPV6_MTU - IPV6_HEADER_SIZE - ICMP_HEADER_SIZE
        )
        if (quoteLength < IPV6_HEADER_SIZE) return null
        val icmpLength = ICMP_HEADER_SIZE + quoteLength
        val response = ByteArray(IPV6_HEADER_SIZE + icmpLength)
        response[0] = 0x60
        write16(response, PAYLOAD_LENGTH_OFFSET, icmpLength)
        response[NEXT_HEADER_OFFSET] = ICMPV6_PROTOCOL.toByte()
        response[HOP_LIMIT_OFFSET] = DEFAULT_HOP_LIMIT.toByte()
        responseSource.copyInto(response, SOURCE_OFFSET)
        validatedPacket.copyInto(response, DESTINATION_OFFSET, SOURCE_OFFSET, SOURCE_OFFSET + ADDRESS_SIZE)
        response[ICMP_OFFSET] = PACKET_TOO_BIG.toByte()
        write32(response, ICMP_OFFSET + 4, mtu)
        invokingPacket.copyInto(response, ICMP_OFFSET + ICMP_HEADER_SIZE, 0, quoteLength)
        write16(response, ICMP_OFFSET + 2, checksum(response, icmpLength))
        return response
    }

    private fun invokingPacketLength(packet: ByteArray): Int? {
        if (packet.size < IPV6_HEADER_SIZE) return null
        if ((packet[0].toInt() ushr 4) != IPV6_VERSION) return null
        val declaredLength = read16(packet, PAYLOAD_LENGTH_OFFSET) + IPV6_HEADER_SIZE
        return declaredLength.takeIf { it == packet.size }
    }

    private fun sameAddresses(first: ByteArray, second: ByteArray): Boolean {
        return (SOURCE_OFFSET until DESTINATION_OFFSET + ADDRESS_SIZE).all { index ->
            first[index] == second[index]
        }
    }

    private fun checksum(packet: ByteArray, icmpLength: Int): Int {
        var sum = wordSum(packet, SOURCE_OFFSET, ADDRESS_SIZE * 2)
        sum += (icmpLength ushr 16).toLong()
        sum += (icmpLength and 0xffff).toLong()
        sum += ICMPV6_PROTOCOL.toLong()
        sum += wordSum(packet, ICMP_OFFSET, icmpLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val value = sum.inv().toInt() and 0xffff
        return if (value == 0) 0xffff else value
    }

    private fun wordSum(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += read16(data, index).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        return sum
    }

    private fun read16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun write16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun write32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private const val IPV6_VERSION = 6
    private const val IPV6_HEADER_SIZE = 40
    private const val ADDRESS_SIZE = 16
    private const val ICMP_HEADER_SIZE = 8
    private const val PAYLOAD_LENGTH_OFFSET = 4
    private const val NEXT_HEADER_OFFSET = 6
    private const val HOP_LIMIT_OFFSET = 7
    private const val SOURCE_OFFSET = 8
    private const val DESTINATION_OFFSET = 24
    private const val ICMP_OFFSET = 40
    private const val ICMPV6_PROTOCOL = 58
    private const val PACKET_TOO_BIG = 2
    private const val DEFAULT_HOP_LIMIT = 64
    private const val MINIMUM_IPV6_MTU = 1280
}
