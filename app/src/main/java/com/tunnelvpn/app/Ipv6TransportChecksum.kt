package com.tunnelvpn.app

internal object Ipv6TransportChecksum {
    fun isValid(
        packet: ByteArray,
        packetLength: Int,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int,
        rejectZeroChecksumAt: Int? = null
    ): Boolean {
        if (packetLength !in IPV6_HEADER_LENGTH..packet.size) return false
        if (transportOffset !in IPV6_HEADER_LENGTH..packetLength) return false
        if (transportLength < 0 || transportLength != packetLength - transportOffset) return false
        if (protocol !in 0..0xff) return false
        if (rejectZeroChecksumAt != null) {
            if (rejectZeroChecksumAt < 0 || transportLength < 2 || rejectZeroChecksumAt > transportLength - 2) {
                return false
            }
            if (u16(packet, transportOffset + rejectZeroChecksumAt) == 0) return false
        }
        var sum = wordSum(packet, SOURCE_OFFSET, ADDRESS_LENGTH * 2)
        sum += (transportLength ushr 16).toLong()
        sum += (transportLength and 0xffff).toLong()
        sum += (protocol and 0xff).toLong()
        sum += wordSum(packet, transportOffset, transportLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum == 0xffffL
    }

    fun value(
        packet: ByteArray,
        packetLength: Int,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int
    ): Int {
        require(packetLength in IPV6_HEADER_LENGTH..packet.size)
        require(transportOffset in IPV6_HEADER_LENGTH..packetLength)
        require(transportLength >= 0 && transportLength == packetLength - transportOffset)
        require(protocol in 0..0xff)
        var sum = wordSum(packet, SOURCE_OFFSET, ADDRESS_LENGTH * 2)
        sum += (transportLength ushr 16).toLong()
        sum += (transportLength and 0xffff).toLong()
        sum += (protocol and 0xff).toLong()
        sum += wordSum(packet, transportOffset, transportLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun wordSum(data: ByteArray, offset: Int, length: Int): Long {
        var sum0 = 0L
        var sum1 = 0L
        var sum2 = 0L
        var sum3 = 0L
        var index = offset
        val end = offset + length
        while (index + 7 < end) {
            sum0 += (((data[index].toInt() and 0xff) shl 8) or
                (data[index + 1].toInt() and 0xff)).toLong()
            sum1 += (((data[index + 2].toInt() and 0xff) shl 8) or
                (data[index + 3].toInt() and 0xff)).toLong()
            sum2 += (((data[index + 4].toInt() and 0xff) shl 8) or
                (data[index + 5].toInt() and 0xff)).toLong()
            sum3 += (((data[index + 6].toInt() and 0xff) shl 8) or
                (data[index + 7].toInt() and 0xff)).toLong()
            index += 8
        }
        var sum = sum0 + sum1 + sum2 + sum3
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

    private const val IPV6_HEADER_LENGTH = 40
    private const val ADDRESS_LENGTH = 16
    private const val SOURCE_OFFSET = 8
}
