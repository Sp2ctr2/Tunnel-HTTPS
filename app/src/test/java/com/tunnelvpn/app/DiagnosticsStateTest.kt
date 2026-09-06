package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsStateTest {
    @Test
    fun invalidUnderlyingNetworkExcludesRetryPacketsFromThroughputSamples() {
        var nowMs = 0L
        val diagnostics = DiagnosticsState { nowMs }.apply {
            resetSession(adBlock = false)
            serviceRunning = true
            recordUnderlyingNetworkValidated(true)
        }

        diagnostics.recordTunRead(1_000)
        nowMs = 1_000L
        assertTrue(diagnostics.trafficSnapshotJson().contains("\"uplink\": 1000"))

        diagnostics.recordUnderlyingNetworkValidated(false)
        diagnostics.recordTunRead(500)
        nowMs = 2_000L
        val offline = diagnostics.trafficSnapshotJson()
        assertTrue(offline.contains("\"networkValidated\": false"))
        assertTrue(offline.contains("\"uplink\": 0"))

        diagnostics.recordUnderlyingNetworkValidated(true)
        diagnostics.recordTunRead(250)
        nowMs = 3_000L
        assertTrue(diagnostics.trafficSnapshotJson().contains("\"uplink\": 250"))
    }

    @Test
    fun healthFailureDoesNotClaimThatRoutingChanged() {
        val diagnostics = DiagnosticsState().apply {
            routeMode = RouteMode.TURBO_FULL_FORWARD
            turboRouteAllIPv4Enabled = true
            turboForwarderPreflightPassed = true
        }

        diagnostics.recordTurboHealthFailure("underlying-https")

        assertEquals(RouteMode.TURBO_FULL_FORWARD, diagnostics.routeMode)
        assertTrue(diagnostics.turboRouteAllIPv4Enabled)
        assertTrue(diagnostics.turboForwarderPreflightPassed)
        assertEquals("underlying-https", diagnostics.turboLastFailureReason)
    }

    @Test
    fun tracksDnsLatencyAndCacheHitRate() {
        val diagnostics = DiagnosticsState()

        diagnostics.recordDnsQuery(20, "cloudflare", cacheHit = false)
        diagnostics.recordDnsQuery(10, "cloudflare", cacheHit = true)

        assertEquals(15, diagnostics.dnsAverageLatencyMs())
        assertEquals(0.5, diagnostics.dnsCacheHitRate(), 0.0001)
        assertEquals("cloudflare", diagnostics.dnsProviderInUse)
    }

    @Test
    fun diagnosticsJsonContainsRequiredFields() {
        val diagnostics = DiagnosticsState()
        diagnostics.serviceRunning = true
        diagnostics.activeProtectionMode = ProtectionMode.STRONG
        diagnostics.recordDnsFailure("timeout\nfull details")

        val json = diagnostics.toJson()

        assertTrue(json.contains("\"serviceRunning\": true"))
        assertTrue(json.contains("\"activeProtectionMode\": \"strong\""))
        assertTrue(json.contains("\"lastDnsError\": \"timeout\""))
        assertTrue(json.contains("\"ipv6PacketsBlocked\": 0"))
    }

    @Test
    fun retiredPacketLoopCannotClearTheCurrentLoopState() {
        val diagnostics = DiagnosticsState()
        val first = diagnostics.beginPacketLoop()
        val second = diagnostics.beginPacketLoop()

        diagnostics.endPacketLoop(first)

        assertTrue(diagnostics.toJson().contains("\"packetLoopActive\": true"))
        diagnostics.endPacketLoop(second)
        assertTrue(diagnostics.toJson().contains("\"packetLoopActive\": false"))
    }
}
