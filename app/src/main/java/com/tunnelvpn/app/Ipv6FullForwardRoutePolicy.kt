package com.tunnelvpn.app

import java.net.InetAddress

internal data class Ipv6RouteSpec(val address: String, val prefixLength: Int) {
    val bytes: ByteArray = InetAddress.getByName(address).address
}

internal object Ipv6FullForwardRoutePolicy {
    val legacyIncludedRoutes = listOf(
        Ipv6RouteSpec("::", 1),
        Ipv6RouteSpec("8000::", 2),
        Ipv6RouteSpec("c000::", 3),
        Ipv6RouteSpec("e000::", 4),
        Ipv6RouteSpec("f000::", 5),
        Ipv6RouteSpec("f800::", 6),
        Ipv6RouteSpec("fc00::", 7),
        Ipv6RouteSpec("fe00::", 9),
        Ipv6RouteSpec("fec0::", 10)
    )

    fun captures(address: ByteArray): Boolean {
        if (address.size != IPV6_ADDRESS_LENGTH) return false
        val first = address[0].toInt() and 0xff
        val second = address[1].toInt() and 0xff
        val linkLocal = first == 0xfe && second and 0xc0 == 0x80
        val multicast = first == 0xff
        return !linkLocal && !multicast
    }

    fun capturedByLegacyRoutes(address: ByteArray): Boolean {
        if (address.size != IPV6_ADDRESS_LENGTH) return false
        return legacyIncludedRoutes.any { route ->
            prefixMatches(address, route.bytes, route.prefixLength)
        }
    }

    fun isProxyableUnicast(address: ByteArray): Boolean {
        return address.size == IPV6_ADDRESS_LENGTH && isProxyableUnicast(address, 0)
    }

    fun isProxyableUnicast(packet: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset > packet.size - IPV6_ADDRESS_LENGTH) return false
        val first = packet[offset].toInt() and 0xff
        val second = packet[offset + 1].toInt() and 0xff
        if (first == 0xff || first == 0xfe && second and 0xc0 == 0x80) return false
        for (index in offset until offset + IPV6_ADDRESS_LENGTH) {
            if (packet[index].toInt() != 0) return true
        }
        return false
    }

    private fun prefixMatches(address: ByteArray, network: ByteArray, prefixLength: Int): Boolean {
        val fullBytes = prefixLength / 8
        for (index in 0 until fullBytes) {
            if (address[index] != network[index]) return false
        }
        val remaining = prefixLength % 8
        if (remaining == 0) return true
        val mask = (0xff shl (8 - remaining)) and 0xff
        return address[fullBytes].toInt() and mask == network[fullBytes].toInt() and mask
    }

    private const val IPV6_ADDRESS_LENGTH = 16
}
