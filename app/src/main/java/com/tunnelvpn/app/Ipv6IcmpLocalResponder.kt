package com.tunnelvpn.app

object Ipv6IcmpLocalResponder {
    private val localAddresses = setOf(
        address(0xfd, 0, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        address(0xfd, 0, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
    )

    fun respond(packet: ByteArray, length: Int = packet.size): ByteArray? {
        if (length !in MINIMUM_PACKET_SIZE..packet.size) return null
        if ((packet[0].toInt() ushr 4) != IPV6_VERSION) return null
        if (read16(packet, PAYLOAD_LENGTH_OFFSET) + IPV6_HEADER_SIZE != length) return null
        if ((packet[NEXT_HEADER_OFFSET].toInt() and 0xff) != ICMPV6_PROTOCOL) return null
        if ((packet[ICMP_OFFSET].toInt() and 0xff) != ECHO_REQUEST || packet[ICMP_OFFSET + 1].toInt() != 0) return null
        if (!Ipv6IcmpErrorPolicy.isUsableUnicast(packet, SOURCE_OFFSET)) return null
        val destination = packet.copyOfRange(DESTINATION_OFFSET, DESTINATION_OFFSET + ADDRESS_SIZE)
        if (localAddresses.none(destination::contentEquals)) return null
        if (checksumSum(packet, length) != 0xffff) return null
        val response = packet.copyOf(length)
        response.copyInto(response, SOURCE_OFFSET, DESTINATION_OFFSET, DESTINATION_OFFSET + ADDRESS_SIZE)
        packet.copyInto(response, DESTINATION_OFFSET, SOURCE_OFFSET, SOURCE_OFFSET + ADDRESS_SIZE)
        response[HOP_LIMIT_OFFSET] = DEFAULT_HOP_LIMIT.toByte()
        response[ICMP_OFFSET] = ECHO_REPLY.toByte()
        response[ICMP_OFFSET + 2] = 0
        response[ICMP_OFFSET + 3] = 0
        write16(response, ICMP_OFFSET + 2, checksum(response, length))
        return response
    }

    private fun checksum(packet: ByteArray, length: Int): Int {
        return checksumSum(packet, length).inv() and 0xffff
    }

    private fun checksumSum(packet: ByteArray, length: Int): Int {
        var sum = 0L
        sum += wordSum(packet, SOURCE_OFFSET, ADDRESS_SIZE * 2)
        val payloadLength = length - IPV6_HEADER_SIZE
        sum += (payloadLength ushr 16).toLong()
        sum += (payloadLength and 0xffff).toLong()
        sum += ICMPV6_PROTOCOL.toLong()
        sum += wordSum(packet, ICMP_OFFSET, payloadLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt() and 0xffff
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

    private fun address(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun read16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun write16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private const val IPV6_VERSION = 6
    private const val IPV6_HEADER_SIZE = 40
    private const val ADDRESS_SIZE = 16
    private const val MINIMUM_PACKET_SIZE = 48
    private const val PAYLOAD_LENGTH_OFFSET = 4
    private const val NEXT_HEADER_OFFSET = 6
    private const val HOP_LIMIT_OFFSET = 7
    private const val SOURCE_OFFSET = 8
    private const val DESTINATION_OFFSET = 24
    private const val ICMP_OFFSET = 40
    private const val ICMPV6_PROTOCOL = 58
    private const val ECHO_REQUEST = 128
    private const val ECHO_REPLY = 129
    private const val DEFAULT_HOP_LIMIT = 64
}
