package com.tunnelvpn.app

import java.io.File
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkReachabilityTest {
    @Test
    fun tcpFailureIsNotReportedAsSuccess() {
        val result = classifyUnderlyingNetworkReachability(false, false)

        assertFalse(result.passed)
        assertEquals("tcp-connect", result.reason)
    }

    @Test
    fun httpsFailureIsNotReportedAsTcpOnlySuccess() {
        val result = classifyUnderlyingNetworkReachability(true, false)

        assertFalse(result.passed)
        assertEquals("https", result.reason)
    }

    @Test
    fun bothChecksMustPass() {
        assertTrue(classifyUnderlyingNetworkReachability(true, true).passed)
    }

    @Test
    fun dualStackProbeFallsBackFromBrokenIpv4ToPinnedIpv6ForTcpAndTls() {
        val ipv6 = InetAddress.getByName("2001:db8::53")
        val ipv4 = InetAddress.getByName("192.0.2.53")
        val tcpAttempts = mutableListOf<InetAddress>()
        val httpsAttempts = mutableListOf<InetAddress>()
        val diagnostics = DiagnosticsState()
        val probe = UnderlyingNetworkReachabilityProbe(
            diagnostics,
            candidateProvider = { listOf(ipv4, ipv6) },
            tcpConnector = { address, _ ->
                tcpAttempts += address
                address == ipv6
            },
            httpsConnector = { _, _, address ->
                httpsAttempts += address
                address == ipv6
            }
        )

        val result = probe.check()

        assertTrue(result.passed)
        assertEquals(listOf(ipv4, ipv6), tcpAttempts)
        assertEquals(listOf(ipv4, ipv6), httpsAttempts)
        assertTrue(diagnostics.turboConnectivityCheckPassed)
    }

    @Test
    fun ipv6OnlyProbeFallsBackFromNativeIpv6ToNat64ForTcpAndTls() {
        val nat64 = InetAddress.getByName("64:ff9b::c000:235")
        val tcpAttempts = mutableListOf<InetAddress>()
        val httpsAttempts = mutableListOf<InetAddress>()
        val probe = UnderlyingNetworkReachabilityProbe(
            DiagnosticsState(),
            candidateProvider = { pinned ->
                orderWatchdogProbeCandidates(
                    pinned,
                    hasIpv4 = false,
                    synthesizeIpv4 = { listOf(nat64) },
                    limit = UnderlyingNetworkReachabilityProbe.MAX_CANDIDATES_PER_PROBE
                )
            },
            tcpConnector = { address, _ -> tcpAttempts += address; address == nat64 },
            httpsConnector = { _, _, address -> httpsAttempts += address; address == nat64 }
        )

        assertTrue(probe.check().passed)
        assertTrue(tcpAttempts.first().address.size == 16)
        assertEquals(nat64, tcpAttempts.last())
        assertEquals(nat64, httpsAttempts.last())
        assertTrue(tcpAttempts.size <= UnderlyingNetworkReachabilityProbe.MAX_CANDIDATES_PER_PROBE)
        assertTrue(httpsAttempts.size <= UnderlyingNetworkReachabilityProbe.MAX_CANDIDATES_PER_PROBE)
    }

    @Test
    fun successfulTcpWithoutVerifiedHttpsRemainsDegraded() {
        val address = InetAddress.getByName("192.0.2.80")
        val diagnostics = DiagnosticsState()
        val probe = UnderlyingNetworkReachabilityProbe(
            diagnostics,
            candidateProvider = { listOf(address) },
            tcpConnector = { _, _ -> true },
            httpsConnector = { _, _, _ -> false }
        )

        val result = probe.check()

        assertFalse(result.passed)
        assertEquals("https", result.reason)
        assertFalse(diagnostics.turboConnectivityCheckPassed)
    }

    @Test
    fun ipv6OnlyOrderingInterleavesNativeAndNat64WithoutRawIpv4() {
        val ipv4a = InetAddress.getByName("192.0.2.1")
        val ipv4b = InetAddress.getByName("192.0.2.2")
        val ipv6a = InetAddress.getByName("2001:db8::1")
        val ipv6b = InetAddress.getByName("2001:db8::2")
        val nat64a = InetAddress.getByName("64:ff9b::c000:201")
        val nat64b = InetAddress.getByName("64:ff9b::c000:202")

        val ordered = orderWatchdogProbeCandidates(
            listOf(ipv4a, ipv4b, ipv6a, ipv6b),
            hasIpv4 = false,
            synthesizeIpv4 = { address -> if (address == ipv4a) listOf(nat64a) else listOf(nat64b) },
            limit = 4
        )

        assertEquals(listOf(ipv6a, nat64a, ipv6b, nat64b), ordered)
        assertTrue(ordered.all { it.address.size == 16 })
    }

    @Test
    fun dualStackOrderingKeepsWorkingIpv6InsideFourAttemptCap() {
        val ipv4a = InetAddress.getByName("192.0.2.1")
        val ipv6a = InetAddress.getByName("2001:db8::1")
        val ipv4b = InetAddress.getByName("192.0.2.2")
        val ipv6b = InetAddress.getByName("2001:db8::2")

        val ordered = orderWatchdogProbeCandidates(
            listOf(ipv4a, ipv6a, ipv4b, ipv6b),
            hasIpv4 = true,
            synthesizeIpv4 = { emptyList() },
            limit = 4
        )

        assertEquals(listOf(ipv4a, ipv6a, ipv4b, ipv6b), ordered)
        assertTrue(ordered.take(2).any { it.address.size == 4 })
        assertTrue(ordered.take(2).any { it.address.size == 16 })
    }

    @Test
    fun candidateAttemptsAreBounded() {
        val candidates = (1..8).map { suffix -> InetAddress.getByName("192.0.2.$suffix") }
        var attempts = 0
        val probe = UnderlyingNetworkReachabilityProbe(
            DiagnosticsState(),
            candidateProvider = { candidates },
            tcpConnector = { _, _ -> attempts += 1; false },
            httpsConnector = { _, _, _ -> error("HTTPS must not run after TCP failure") }
        )

        assertFalse(probe.check().passed)
        assertEquals(UnderlyingNetworkReachabilityProbe.MAX_CANDIDATES_PER_PROBE, attempts)
    }

    @Test
    fun productionProbeUsesLiteralPinsButRetainsTlsHostnameVerification() {
        val source = File("src/main/java/com/tunnelvpn/app/TurboConnectivityWatchdog.kt").readText()
        val engine = File("src/main/java/com/tunnelvpn/app/LocalProtectionEngine.kt").readText()
        val service = File("src/main/java/com/tunnelvpn/app/TunnelVpnService.kt").readText()

        assertTrue(source.contains("DohResolver.DEFAULT_PROVIDERS"))
        assertTrue(source.contains("InetSocketAddress(address, HTTPS_PORT)"))
        assertTrue(source.contains("createSocket(raw, host, HTTPS_PORT, true)"))
        assertTrue(source.contains("endpointIdentificationAlgorithm = \"HTTPS\""))
        assertFalse(source.contains("getAllByName"))
        assertFalse(source.contains("InetSocketAddress(host"))
        assertTrue(source.contains("STATUS_LINE_TIMEOUT_NANOS"))
        assertTrue(source.contains("socket.soTimeout = remainingMs.toInt()"))
        assertTrue(source.contains("StatusLineResult(null, \"status-timeout\")"))
        assertTrue(source.contains("is SSLPeerUnverifiedException -> \"tls-peer-unverified\""))
        assertTrue(source.contains("providerProbe(\"quad9\", \"/dns-query\")"))
        assertTrue(source.contains("WATCHDOG_FAILURE_PREFIX = \"watchdog:\""))
        assertTrue(source.contains("MAX_REPORTED_WATCHDOG_FAILURES = 8"))
        val httpsProbe = source.substringAfter("private fun protectedHttpsHead")
            .substringBefore("private data class StatusLineResult")
        val socketInitialization = httpsProbe.indexOf("raw.tcpNoDelay = true")
        val socketProtection = httpsProbe.indexOf("protectSocket(raw)")
        assertTrue(socketInitialization >= 0 && socketInitialization < socketProtection)
        assertTrue(engine.substringAfter("internal fun protectWatchdogSocket")
            .substringBefore("internal fun watchdogProbeCandidates")
            .contains("network.bindSocket(socket)"))
        assertTrue(service.contains("candidateProvider = activeEngine::watchdogProbeCandidates"))
        assertTrue(service.contains("protectSocket = activeEngine::protectWatchdogSocket"))
    }
}
