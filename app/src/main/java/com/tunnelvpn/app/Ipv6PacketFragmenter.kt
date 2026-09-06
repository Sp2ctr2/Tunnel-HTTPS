package com.tunnelvpn.app

internal object Ipv6PacketFragmenter {
    fun fragment(packet: ByteArray, mtu: Int, identification: Int): List<ByteArray>? {
        if (mtu < MINIMUM_IPV6_MTU || packet.size < IPV6_HEADER_LENGTH) return null
        if (((packet[0].toInt() ushr 4) and 0x0f) != IPV6_VERSION) return null
        val payloadLength = u16(packet, PAYLOAD_LENGTH_OFFSET)
        if (payloadLength != packet.size - IPV6_HEADER_LENGTH) return null
        if (packet.size <= mtu) return listOf(packet)
        val layout = layout(packet) ?: return null
        val perFragmentLength = layout.fragmentInsertionOffset
        val fragmentableLength = packet.size - perFragmentLength
        val maximumFragmentData = ((mtu - perFragmentLength - FRAGMENT_HEADER_LENGTH) / 8) * 8
        if (maximumFragmentData < 8) return null
        if (layout.requiredFirstFragmentBytes > maximumFragmentData) return null
        val result = ArrayList<ByteArray>()
        var offset = 0
        while (offset < fragmentableLength) {
            val remaining = fragmentableLength - offset
            val fragmentDataLength = minOf(remaining, maximumFragmentData)
            val more = fragmentDataLength < remaining
            if (more && fragmentDataLength % 8 != 0) return null
            val fragmentPayloadLength = perFragmentLength - IPV6_HEADER_LENGTH +
                FRAGMENT_HEADER_LENGTH + fragmentDataLength
            val fragment = ByteArray(IPV6_HEADER_LENGTH + fragmentPayloadLength)
            packet.copyInto(fragment, 0, 0, perFragmentLength)
            put16(fragment, PAYLOAD_LENGTH_OFFSET, fragmentPayloadLength)
            fragment[layout.previousNextHeaderOffset] = FRAGMENT_HEADER.toByte()
            fragment[perFragmentLength] = layout.fragmentNextHeader.toByte()
            put16(fragment, perFragmentLength + 2, offset or if (more) 1 else 0)
            put32(fragment, perFragmentLength + 4, identification)
            packet.copyInto(
                fragment,
                perFragmentLength + FRAGMENT_HEADER_LENGTH,
                perFragmentLength + offset,
                perFragmentLength + offset + fragmentDataLength
            )
            result += fragment
            offset += fragmentDataLength
        }
        return result
    }

    private data class Layout(
        val fragmentInsertionOffset: Int,
        val previousNextHeaderOffset: Int,
        val fragmentNextHeader: Int,
        val requiredFirstFragmentBytes: Int
    )

    private data class Header(
        val protocol: Int,
        val offset: Int,
        val length: Int
    )

    private fun layout(packet: ByteArray): Layout? {
        val headers = ArrayList<Header>()
        var protocol = packet[NEXT_HEADER_OFFSET].toInt() and 0xff
        var offset = IPV6_HEADER_LENGTH
        var headerCount = 0
        while (isExtensionHeader(protocol)) {
            if (protocol == FRAGMENT_HEADER || headerCount >= MAX_EXTENSION_HEADERS || offset + 2 > packet.size) {
                return null
            }
            if (protocol == HOP_BY_HOP && offset != IPV6_HEADER_LENGTH) return null
            val headerLength = if (protocol == AUTHENTICATION) {
                ((packet[offset + 1].toInt() and 0xff) + 2) * 4
            } else {
                ((packet[offset + 1].toInt() and 0xff) + 1) * 8
            }
            if (headerLength < 8 || headerLength > packet.size - offset) return null
            headers += Header(protocol, offset, headerLength)
            protocol = packet[offset].toInt() and 0xff
            offset += headerLength
            headerCount += 1
        }

        val routingIndex = headers.indexOfFirst { it.protocol == ROUTING }
        val includedIndex = when {
            routingIndex >= 0 -> routingIndex
            headers.firstOrNull()?.protocol == HOP_BY_HOP -> 0
            else -> -1
        }
        val insertionOffset = if (includedIndex >= 0) {
            headers[includedIndex].offset + headers[includedIndex].length
        } else {
            IPV6_HEADER_LENGTH
        }
        val insertionPreviousNextHeaderOffset = if (includedIndex >= 0) {
            headers[includedIndex].offset
        } else {
            NEXT_HEADER_OFFSET
        }
        val insertionNextHeader = packet[insertionPreviousNextHeaderOffset].toInt() and 0xff
        val upperHeaderLength = when (protocol) {
            TCP -> {
                if (offset + TCP_MIN_HEADER_LENGTH > packet.size) return null
                val length = ((packet[offset + TCP_DATA_OFFSET].toInt() ushr 4) and 0x0f) * 4
                if (length < TCP_MIN_HEADER_LENGTH || length > packet.size - offset) return null
                length
            }
            UDP -> UDP_HEADER_LENGTH.takeIf { offset + it <= packet.size } ?: return null
            ICMPV6 -> ICMPV6_HEADER_LENGTH.takeIf { offset + it <= packet.size } ?: return null
            NO_NEXT_HEADER -> 0
            else -> return null
        }
        val requiredFirstFragmentBytes = offset - insertionOffset + upperHeaderLength
        return Layout(
            fragmentInsertionOffset = insertionOffset,
            previousNextHeaderOffset = insertionPreviousNextHeaderOffset,
            fragmentNextHeader = insertionNextHeader,
            requiredFirstFragmentBytes = requiredFirstFragmentBytes
        )
    }

    private fun isExtensionHeader(protocol: Int): Boolean {
        return protocol == HOP_BY_HOP || protocol == ROUTING || protocol == FRAGMENT_HEADER ||
            protocol == AUTHENTICATION || protocol == DESTINATION_OPTIONS
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xff).toByte()
        data[offset + 1] = ((value ushr 16) and 0xff).toByte()
        data[offset + 2] = ((value ushr 8) and 0xff).toByte()
        data[offset + 3] = (value and 0xff).toByte()
    }

    private const val IPV6_VERSION = 6
    private const val IPV6_HEADER_LENGTH = 40
    private const val FRAGMENT_HEADER_LENGTH = 8
    private const val PAYLOAD_LENGTH_OFFSET = 4
    private const val NEXT_HEADER_OFFSET = 6
    private const val HOP_BY_HOP = 0
    private const val TCP = 6
    private const val UDP = 17
    private const val ROUTING = 43
    private const val FRAGMENT_HEADER = 44
    private const val AUTHENTICATION = 51
    private const val ICMPV6 = 58
    private const val NO_NEXT_HEADER = 59
    private const val DESTINATION_OPTIONS = 60
    private const val TCP_DATA_OFFSET = 12
    private const val TCP_MIN_HEADER_LENGTH = 20
    private const val UDP_HEADER_LENGTH = 8
    private const val ICMPV6_HEADER_LENGTH = 8
    private const val MAX_EXTENSION_HEADERS = 8
    private const val MINIMUM_IPV6_MTU = 1280
}
