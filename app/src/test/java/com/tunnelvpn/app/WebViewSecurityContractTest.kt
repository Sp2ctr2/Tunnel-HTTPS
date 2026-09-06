package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSecurityContractTest {
    private val html = File("src/main/assets/index.html").readText()
    private val activity = File("src/main/java/com/tunnelvpn/app/MainActivity.kt").readText()

    @Test
    fun contentSecurityPolicyDoesNotPermitArbitraryHttpsConnectionsOrFrames() {
        assertTrue(html.contains("connect-src 'none'"))
        assertFalse(html.contains("connect-src https://"))
        assertTrue(html.contains("frame-src 'none'"))
        assertTrue(html.contains("child-src 'none'"))
    }

    @Test
    fun nonAssetSubresourcesAreExplicitlyBlocked() {
        assertFalse(activity.contains("isAllowedWebDiagnosticRequest"))
        assertTrue(activity.contains("assetLoader.shouldInterceptRequest(url)?.let { return it }"))
        assertTrue(activity.contains("return blockedWebResource()"))
        assertTrue(activity.contains("blockedWebResource()"))
    }

    @Test
    fun nativeDiagnosticsAreSingleFlightAndAlwaysReleaseTheGuard() {
        assertTrue(activity.contains("diagnosticsRequestInFlight.compareAndSet(false, true)"))
        assertTrue(activity.contains("diagnosticsRequestInFlight.set(false)"))
        assertTrue(activity.contains("diagnosticsPendingRequest.set(request)"))
        assertTrue(activity.contains("diagnosticsPendingRequest.getAndSet(null) ?: break"))
        assertTrue(activity.contains("val payload = getNetworkSnapshot(request) ?: continue"))
        assertTrue(activity.contains("setNetworkSnapshot(\${request.requestId}"))
        assertTrue(activity.contains("\${request.serviceGeneration},\${request.epoch}"))
        assertFalse(activity.contains("@JavascriptInterface\n        fun getNetworkSnapshot"))
    }
}
