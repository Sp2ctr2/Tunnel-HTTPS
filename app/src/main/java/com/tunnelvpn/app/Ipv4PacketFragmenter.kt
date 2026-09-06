package com.tunnelvpn.app

internal object Ipv4PacketFragmenter {
    fun fragment(packet: ByteArray, mtu: Int, identification: Int): List<ByteArray>? {
        if (packet.size < IPV4_HEADER_LENGTH || mtu < IPV4_HEADER_LENGTH + MIN_FRAGMENT_DATA) return null
        if (((packet[0].toInt() ushr 4) and 0x0f) != IPV4_VERSION) return null
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        if (headerLength != IPV4_HEADER_LENGTH) return null
        if (u16(packet, TOTAL_LENGTH_OFFSET) != packet.size) return null
        val fragmentField = u16(packet, FRAGMENT_OFFSET)
        if (fragmentField and (RESERVED_FLAG or MORE_FRAGMENTS or OFFSET_MASK) != 0) return null
        if (checksum(packet, 0, headerLength) != 0) return null
        if (packet.size <= mtu) return listOf(packet)
        if (fragmentField and DONT_FRAGMENT != 0) return null

        val fragmentableLength = packet.size - headerLength
        val maximumFragmentData = ((mtu - headerLength) / 8) * 8
        if (maximumFragmentData < MIN_FRAGMENT_DATA) return null
        val fragments = ArrayList<ByteArray>()
        var offset = 0
        while (offset < fragmentableLength) {
            val remaining = fragmentableLength - offset
            val fragmentDataLength = minOf(remaining, maximumFragmentData)
            val more = fragmentDataLength < remaining
            if (more && fragmentDataLength % 8 != 0) return null
            val fragment = ByteArray(headerLength + fragmentDataLength)
            packet.copyInto(fragment, 0, 0, headerLength)
            put16(fragment, TOTAL_LENGTH_OFFSET, fragment.size)
            put16(fragment, IDENTIFICATION_OFFSET, identification and 0xffff)
            put16(
                fragment,
                FRAGMENT_OFFSET,
                (offset / 8) or if (more) MORE_FRAGMENTS else 0
            )
            put16(fragment, CHECKSUM_OFFSET, 0)
            packet.copyInto(
                fragment,
                headerLength,
                headerLength + offset,
                headerLength + offset + fragmentDataLength
            )
            put16(fragment, CHECKSUM_OFFSET, checksum(fragment, 0, headerLength))
            fragments += fragment
            offset += fragmentDataLength
        }
        return fragments
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += u16(data, index).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private const val IPV4_VERSION = 4
    private const val IPV4_HEADER_LENGTH = 20
    private const val MIN_FRAGMENT_DATA = 8
    private const val TOTAL_LENGTH_OFFSET = 2
    private const val IDENTIFICATION_OFFSET = 4
    private const val FRAGMENT_OFFSET = 6
    private const val CHECKSUM_OFFSET = 10
    private const val RESERVED_FLAG = 0x8000
    private const val DONT_FRAGMENT = 0x4000
    private const val MORE_FRAGMENTS = 0x2000
    private const val OFFSET_MASK = 0x1fff
}
