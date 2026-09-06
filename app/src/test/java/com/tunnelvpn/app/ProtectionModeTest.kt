package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionModeTest {
    @Test
    fun everyModeCapturesTrafficForSniProtection() {
        assertTrue(ProtectionMode.FAST.capturesAllTraffic)
        assertTrue(ProtectionMode.BALANCED.capturesAllTraffic)
        assertTrue(ProtectionMode.STRONG.capturesAllTraffic)
    }
    @Test
    fun defaultsToBalancedAndMapsLegacyModes() {
        assertEquals(ProtectionMode.BALANCED, ProtectionMode.from(null))
        assertEquals(ProtectionMode.BALANCED, ProtectionMode.from("https_local"))
        assertEquals(ProtectionMode.STRONG, ProtectionMode.from("enhanced_tunnel"))
    }

    @Test
    fun everyModeEnablesSniFragmentation() {
        assertTrue(ProtectionMode.FAST.protectsSni)
        assertTrue(ProtectionMode.BALANCED.protectsSni)
        assertTrue(ProtectionMode.STRONG.protectsSni)
    }
}
