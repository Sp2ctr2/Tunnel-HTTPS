package com.tunnelvpn.app

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6FullForwardRoutePolicyTest {
    @Test
    fun capturesInternetUlaNat64LoopbackAndVirtualDnsRanges() {
        listOf(
            "::1",
            "2001:4860:4860::8888",
            "64:ff9b::c000:221",
            "fd00:1:11::1",
            "fec0::1"
        ).forEach { address ->
            val raw = InetAddress.getByName(address).address
            assertTrue(Ipv6FullForwardRoutePolicy.captures(raw))
            assertTrue(Ipv6FullForwardRoutePolicy.capturedByLegacyRoutes(raw))
        }
    }

    @Test
    fun leavesLinkLocalAndMulticastOnTheUnderlyingInterface() {
        listOf(
            "fe80::1",
            "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "ff01::1",
            "ff02::fb",
            "ff05::c",
            "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
        ).forEach { address ->
            val raw = InetAddress.getByName(address).address
            assertFalse(Ipv6FullForwardRoutePolicy.captures(raw))
            assertFalse(Ipv6FullForwardRoutePolicy.capturedByLegacyRoutes(raw))
        }
        assertFalse(Ipv6FullForwardRoutePolicy.isProxyableUnicast(InetAddress.getByName("::").address))
        assertFalse(Ipv6FullForwardRoutePolicy.isProxyableUnicast(InetAddress.getByName("fe80::1").address))
        assertFalse(Ipv6FullForwardRoutePolicy.isProxyableUnicast(InetAddress.getByName("ff02::fb").address))
        assertTrue(Ipv6FullForwardRoutePolicy.isProxyableUnicast(InetAddress.getByName("::1").address))
    }

    @Test
    fun legacyComplementMatchesModernExclusionPolicyForEveryIpv6LeadingWord() {
        for (leadingWord in 0..0xffff) {
            val address = ByteArray(16).also {
                it[0] = (leadingWord ushr 8).toByte()
                it[1] = leadingWord.toByte()
            }
            assertEquals(
                Ipv6FullForwardRoutePolicy.captures(address),
                Ipv6FullForwardRoutePolicy.capturedByLegacyRoutes(address)
            )
        }
    }
}
