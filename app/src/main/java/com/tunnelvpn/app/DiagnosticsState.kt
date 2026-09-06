package com.tunnelvpn.app

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class DiagnosticsState(
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @Volatile var serviceRunning: Boolean = false
    @Volatile var underlyingNetworkValidated: Boolean = false
    @Volatile var vpnPermissionGranted: Boolean = false
    @Volatile var activeProtectionMode: ProtectionMode = ProtectionMode.BALANCED
    @Volatile var dnsProviderInUse: String = "none"
    @Volatile var lastDnsError: String = ""
    @Volatile var ipv6HandlingStatus: String = "blocked"
    @Volatile var bypassedPackages: Int = 0
    @Volatile var lastProtectionFailureReason: String = ""
    @Volatile var httpsMetadataMode: String = "opportunistic"
    @Volatile var adBlockEnabled: Boolean = true
    @Volatile var lastBlockedDomain: String = ""
    @Volatile var turboModeEnabled: Boolean = false
    @Volatile var turboThemeActive: Boolean = false
    @Volatile var turboQuicGuardEnabled: Boolean = false
    @Volatile var turboTcpForwardingActive: Boolean = false
    @Volatile var turboFragmentationStrategy: String = "none"
    @Volatile var turboBypassActuallyActive: Boolean = false
    @Volatile var turboLastFailureReason: String = ""
    @Volatile var routeMode: RouteMode = RouteMode.NORMAL_DNS_ONLY
    @Volatile var turboRouteAllIPv4Requested: Boolean = false
    @Volatile var turboRouteAllIPv4Enabled: Boolean = false
    @Volatile var turboForwarderPreflightPassed: Boolean = false
    @Volatile var turboConnectivityCheckPassed: Boolean = false
    @Volatile var packetLoopActive: Boolean = false
    @Volatile var duplicateServiceDetected: Boolean = false

    private val dnsQueries = AtomicLong()
    private val dnsCacheHits = AtomicLong()
    private val dnsLatencyTotalMs = AtomicLong()
    private val sessionRxBytes = AtomicLong()
    private val sessionTxBytes = AtomicLong()
    private val currentRxBytesPerSecond = AtomicLong()
    private val currentTxBytesPerSecond = AtomicLong()
    private val packetLoopGeneration = AtomicLong()
    private val packetLoopOwner = AtomicLong()
    private val blockedDnsQueries = AtomicLong()
    val packetReadErrors = AtomicLong()
    val packetWriteErrors = AtomicLong()
    val dnsFailures = AtomicLong()
    val packetsRead = AtomicLong()
    val packetsWritten = AtomicLong()
    val httpsClientHelloInspected = AtomicLong()
    val sniSeen = AtomicLong()
    val ipv6PacketsBlocked = AtomicLong()
    val turboUdp443PacketsBlocked = AtomicLong()
    val turboHttpsSvcbRecordsSuppressed = AtomicLong()
    val turboTcp443PacketsSeen = AtomicLong()
    val turboTcpConnectionsOpened = AtomicLong()
    val turboTcpConnectionsClosed = AtomicLong()
    val turboTcpActiveConnections = AtomicLong()
    val turboTcpTimeWaitTombstones = AtomicLong()
    val turboTcpCapacityRejected = AtomicLong()
    val turboTcpTupleReuseAccepted = AtomicLong()
    val turboTcpTupleReuseGuarded = AtomicLong()
    val turboTcpForwardingErrors = AtomicLong()
    val turboClientHelloDetected = AtomicLong()
    val turboSniDetected = AtomicLong()
    val turboFragmentationApplied = AtomicLong()
    val turboForwardBytesUp = AtomicLong()
    val turboForwardBytesDown = AtomicLong()
    val turboHandshakeBudgetExhausted = AtomicLong()
    val tunnelRestartCount = AtomicLong()
    val networkChangeResets = AtomicLong()
    val udpPacketsDroppedNoHandler = AtomicLong()
    val icmpPacketsDroppedNoHandler = AtomicLong()
    val udpRelayFlowsOpened = AtomicLong()
    val udpRelayFlowsClosed = AtomicLong()
    val udpRelayFlowsDropped = AtomicLong()
    val udpRelayOversizeDropped = AtomicLong()
    val udpRelayBytesUp = AtomicLong()
    val udpRelayBytesDown = AtomicLong()
    private val sampleLock = Any()
    private var lastSampleAtMs: Long = elapsedRealtimeMs()
    private var lastSampleRxBytes: Long = 0L
    private var lastSampleTxBytes: Long = 0L

    fun resetSession(adBlock: Boolean, turboMode: Boolean = false) {
        serviceRunning = false
        underlyingNetworkValidated = false
        adBlockEnabled = adBlock
        turboModeEnabled = turboMode
        turboThemeActive = turboMode
        turboQuicGuardEnabled = turboMode
        turboTcpForwardingActive = false
        turboFragmentationStrategy = "none"
        turboBypassActuallyActive = false
        turboLastFailureReason = ""
        routeMode = RouteMode.NORMAL_DNS_ONLY
        turboRouteAllIPv4Requested = false
        turboRouteAllIPv4Enabled = false
        turboForwarderPreflightPassed = false
        turboConnectivityCheckPassed = false
        packetLoopActive = false
        duplicateServiceDetected = false
        lastBlockedDomain = ""
        lastDnsError = ""
        lastProtectionFailureReason = ""
        dnsQueries.set(0)
        dnsCacheHits.set(0)
        dnsLatencyTotalMs.set(0)
        sessionRxBytes.set(0)
        sessionTxBytes.set(0)
        currentRxBytesPerSecond.set(0)
        currentTxBytesPerSecond.set(0)
        blockedDnsQueries.set(0)
        packetReadErrors.set(0)
        packetWriteErrors.set(0)
        dnsFailures.set(0)
        packetsRead.set(0)
        packetsWritten.set(0)
        httpsClientHelloInspected.set(0)
        sniSeen.set(0)
        ipv6PacketsBlocked.set(0)
        turboUdp443PacketsBlocked.set(0)
        turboHttpsSvcbRecordsSuppressed.set(0)
        turboTcp443PacketsSeen.set(0)
        turboTcpConnectionsOpened.set(0)
        turboTcpConnectionsClosed.set(0)
        turboTcpActiveConnections.set(0)
        turboTcpTimeWaitTombstones.set(0)
        turboTcpCapacityRejected.set(0)
        turboTcpTupleReuseAccepted.set(0)
        turboTcpTupleReuseGuarded.set(0)
        turboTcpForwardingErrors.set(0)
        turboClientHelloDetected.set(0)
        turboSniDetected.set(0)
        turboFragmentationApplied.set(0)
        turboForwardBytesUp.set(0)
        turboForwardBytesDown.set(0)
        turboHandshakeBudgetExhausted.set(0)
        udpPacketsDroppedNoHandler.set(0)
        icmpPacketsDroppedNoHandler.set(0)
        udpRelayFlowsOpened.set(0)
        udpRelayFlowsClosed.set(0)
        udpRelayFlowsDropped.set(0)
        udpRelayOversizeDropped.set(0)
        udpRelayBytesUp.set(0)
        udpRelayBytesDown.set(0)
        synchronized(sampleLock) {
            lastSampleAtMs = elapsedRealtimeMs()
            lastSampleRxBytes = 0L
            lastSampleTxBytes = 0L
        }
    }

    fun recordUnderlyingNetworkValidated(validated: Boolean) {
        synchronized(sampleLock) {
            if (underlyingNetworkValidated == validated) return
            underlyingNetworkValidated = validated
            lastSampleAtMs = elapsedRealtimeMs()
            lastSampleRxBytes = sessionRxBytes.get()
            lastSampleTxBytes = sessionTxBytes.get()
            currentRxBytesPerSecond.set(0L)
            currentTxBytesPerSecond.set(0L)
        }
    }

    fun beginPacketLoop(): Long {
        val owner = packetLoopGeneration.incrementAndGet()
        packetLoopOwner.set(owner)
        packetLoopActive = true
        return owner
    }

    fun endPacketLoop(owner: Long) {
        if (packetLoopOwner.compareAndSet(owner, 0L)) {
            packetLoopActive = false
        }
    }

    fun recordTunRead(bytes: Int) {
        packetsRead.incrementAndGet()
        sessionTxBytes.addAndGet(bytes.coerceAtLeast(0).toLong())
    }

    fun recordTunWrite(bytes: Int) {
        packetsWritten.incrementAndGet()
        sessionRxBytes.addAndGet(bytes.coerceAtLeast(0).toLong())
    }

    fun recordBlockedDns(domain: String) {
        blockedDnsQueries.incrementAndGet()
        lastBlockedDomain = if (domain.isBlank()) "blocked" else "blocked-local-policy"
        dnsProviderInUse = "adblock"
        lastDnsError = ""
    }

    fun recordDnsQuery(latencyMs: Long, provider: String, cacheHit: Boolean) {
        dnsQueries.incrementAndGet()
        dnsLatencyTotalMs.addAndGet(latencyMs.coerceAtLeast(0L))
        dnsProviderInUse = provider
        if (cacheHit) dnsCacheHits.incrementAndGet()
        lastDnsError = ""
    }

    fun recordDnsFailure(error: String) {
        dnsFailures.incrementAndGet()
        lastDnsError = sanitize(error)
        lastProtectionFailureReason = "dns: $lastDnsError"
    }

    fun recordTurboUdp443Blocked() {
        turboUdp443PacketsBlocked.incrementAndGet()
    }

    fun recordTurboHttpsSvcbSuppressed() {
        turboHttpsSvcbRecordsSuppressed.incrementAndGet()
        dnsProviderInUse = "turbo-http3-guard"
        lastDnsError = ""
    }

    fun recordTurboTcpPacketSeen() {
        turboTcp443PacketsSeen.incrementAndGet()
    }

    fun recordTurboConnectionOpened() {
        turboTcpConnectionsOpened.incrementAndGet()
        turboTcpActiveConnections.incrementAndGet()
        turboTcpForwardingActive = true
        turboBypassActuallyActive = true
    }

    fun recordTurboConnectionClosed() {
        turboTcpConnectionsClosed.incrementAndGet()
        val active = turboTcpActiveConnections.updateAndGet { value -> (value - 1L).coerceAtLeast(0L) }
        turboTcpForwardingActive = active > 0L
    }

    fun recordTurboTimeWaitCreated() {
        turboTcpTimeWaitTombstones.incrementAndGet()
    }

    fun recordTurboTimeWaitRemoved() {
        turboTcpTimeWaitTombstones.updateAndGet { value -> (value - 1L).coerceAtLeast(0L) }
    }

    fun recordTurboCapacityRejected() {
        turboTcpCapacityRejected.incrementAndGet()
    }

    fun recordTurboTupleReuseAccepted() {
        turboTcpTupleReuseAccepted.incrementAndGet()
    }

    fun recordTurboTupleReuseGuarded() {
        turboTcpTupleReuseGuarded.incrementAndGet()
    }

    fun recordTurboForwardingError(reason: String) {
        turboTcpForwardingErrors.incrementAndGet()
        recordTurboHealthFailure(reason)
    }

    fun recordTurboHandshakeBudgetExhausted() {
        turboHandshakeBudgetExhausted.incrementAndGet()
    }

    fun recordTurboHealthFailure(reason: String) {
        turboLastFailureReason = sanitize(reason)
        turboConnectivityCheckPassed = false
    }

    fun recordTunnelRestart() {
        tunnelRestartCount.incrementAndGet()
    }

    fun recordNetworkChangeReset() {
        networkChangeResets.incrementAndGet()
    }

    fun recordTurboClientHello(sniDetected: Boolean) {
        turboClientHelloDetected.incrementAndGet()
        if (sniDetected) turboSniDetected.incrementAndGet()
    }

    fun recordTurboFragmentation(strategy: String) {
        turboFragmentationApplied.incrementAndGet()
        turboFragmentationStrategy = sanitize(strategy)
    }

    fun recordTurboForwardUp(bytes: Int) {
        val safeBytes = bytes.coerceAtLeast(0).toLong()
        turboForwardBytesUp.addAndGet(safeBytes)
        sessionTxBytes.addAndGet(safeBytes)
        packetsRead.incrementAndGet()
    }

    fun recordTurboForwardDown(bytes: Int) {
        val safeBytes = bytes.coerceAtLeast(0).toLong()
        turboForwardBytesDown.addAndGet(safeBytes)
        sessionRxBytes.addAndGet(safeBytes)
        packetsWritten.incrementAndGet()
    }

    fun recordUdpRelayFlowOpened() {
        udpRelayFlowsOpened.incrementAndGet()
    }

    fun recordUdpRelayFlowClosed() {
        udpRelayFlowsClosed.incrementAndGet()
    }

    fun recordUdpRelayFlowDropped() {
        udpRelayFlowsDropped.incrementAndGet()
    }

    fun recordUdpRelayOversizeDropped() {
        udpRelayOversizeDropped.incrementAndGet()
    }

    fun recordUdpRelayForwardUp(bytes: Int) {
        val safeBytes = bytes.coerceAtLeast(0).toLong()
        udpRelayBytesUp.addAndGet(safeBytes)
        sessionTxBytes.addAndGet(safeBytes)
        packetsRead.incrementAndGet()
    }

    fun recordUdpRelayForwardDown(bytes: Int) {
        val safeBytes = bytes.coerceAtLeast(0).toLong()
        udpRelayBytesDown.addAndGet(safeBytes)
        sessionRxBytes.addAndGet(safeBytes)
        packetsWritten.incrementAndGet()
    }

    fun dnsAverageLatencyMs(): Long {
        val count = dnsQueries.get()
        return if (count == 0L) 0L else dnsLatencyTotalMs.get() / count
    }

    fun dnsCacheHitRate(): Double {
        val count = dnsQueries.get()
        return if (count == 0L) 0.0 else dnsCacheHits.get().toDouble() / count.toDouble()
    }

    fun trafficSnapshotJson(): String {
        updateBandwidth()
        return """
            {
              "available": $serviceRunning,
              "networkValidated": $underlyingNetworkValidated,
              "uplink": ${currentTxBytesPerSecond.get()},
              "downlink": ${currentRxBytesPerSecond.get()},
              "uplinkTotal": ${sessionTxBytes.get()},
              "downlinkTotal": ${sessionRxBytes.get()},
              "packetsRead": ${packetsRead.get()},
              "packetsWritten": ${packetsWritten.get()},
              "scope": "protected_dns_tun",
              "adBlock": {
                "enabled": $adBlockEnabled,
                "blockedQueries": ${blockedDnsQueries.get()},
                "lastBlockedDomain": "${json(lastBlockedDomain)}"
              },
              "turbo": {
                "enabled": $turboModeEnabled,
                "handshakeBudgetExhausted": ${turboHandshakeBudgetExhausted.get()},
                "routeMode": "${routeMode.value}",
                "themeActive": $turboThemeActive,
                "quicGuardEnabled": $turboQuicGuardEnabled,
                "routeAllIPv4Requested": $turboRouteAllIPv4Requested,
                "routeAllIPv4Enabled": $turboRouteAllIPv4Enabled,
                "forwarderPreflightPassed": $turboForwarderPreflightPassed,
                "connectivityCheckPassed": $turboConnectivityCheckPassed,
                "udp443Blocked": ${turboUdp443PacketsBlocked.get()},
                "httpsSvcbSuppressed": ${turboHttpsSvcbRecordsSuppressed.get()},
                "tcp443PacketsSeen": ${turboTcp443PacketsSeen.get()},
                "tcpForwardingActive": $turboTcpForwardingActive,
                "tcpConnectionsOpened": ${turboTcpConnectionsOpened.get()},
                "tcpConnectionsClosed": ${turboTcpConnectionsClosed.get()},
                "tcpActiveConnections": ${turboTcpActiveConnections.get()},
                "tcpTimeWaitTombstones": ${turboTcpTimeWaitTombstones.get()},
                "tcpCapacityRejected": ${turboTcpCapacityRejected.get()},
                "tcpTupleReuseAccepted": ${turboTcpTupleReuseAccepted.get()},
                "tcpTupleReuseGuarded": ${turboTcpTupleReuseGuarded.get()},
                "tcpForwardingErrors": ${turboTcpForwardingErrors.get()},
                "clientHelloDetected": ${turboClientHelloDetected.get()},
                "sniDetected": ${turboSniDetected.get()},
                "fragmentationApplied": ${turboFragmentationApplied.get()},
                "fragmentationStrategy": "${json(turboFragmentationStrategy)}",
                "forwardBytesUp": ${turboForwardBytesUp.get()},
                "forwardBytesDown": ${turboForwardBytesDown.get()},
                "tunnelRestartCount": ${tunnelRestartCount.get()},
                "packetLoopActive": $packetLoopActive,
                "duplicateServiceDetected": $duplicateServiceDetected,
                "bypassActuallyActive": $turboBypassActuallyActive,
                "lastFailureReason": "${json(turboLastFailureReason)}"
              },
              "dns": {
                "queries": ${dnsQueries.get()},
                "provider": "${json(dnsProviderInUse)}",
                "averageLatencyMs": ${dnsAverageLatencyMs()},
                "cacheHitRate": ${String.format(Locale.US, "%.4f", dnsCacheHitRate())},
                "failures": ${dnsFailures.get()}
              },
              "diagnostics": ${toJson()}
            }
        """.trimIndent()
    }

    fun toJson(): String {
        val turboRuntime = TurboRuntimeController.snapshot()
        return """
            {
              "serviceRunning": $serviceRunning,
              "underlyingNetworkValidated": $underlyingNetworkValidated,
              "vpnPermissionGranted": $vpnPermissionGranted,
              "activeProtectionMode": "${activeProtectionMode.prefValue}",
              "dnsProviderInUse": "${json(dnsProviderInUse)}",
              "dnsQueries": ${dnsQueries.get()},
              "dnsAverageLatencyMs": ${dnsAverageLatencyMs()},
              "dnsCacheHitRate": ${String.format(Locale.US, "%.4f", dnsCacheHitRate())},
              "lastDnsError": "${json(lastDnsError)}",
              "ipv6HandlingStatus": "${json(ipv6HandlingStatus)}",
              "networkChangeResets": ${networkChangeResets.get()},
              "packetReadErrors": ${packetReadErrors.get()},
              "packetWriteErrors": ${packetWriteErrors.get()},
              "udpPacketsDroppedNoHandler": ${udpPacketsDroppedNoHandler.get()},
              "icmpPacketsDroppedNoHandler": ${icmpPacketsDroppedNoHandler.get()},
              "udpRelayFlowsOpened": ${udpRelayFlowsOpened.get()},
              "udpRelayFlowsClosed": ${udpRelayFlowsClosed.get()},
              "udpRelayFlowsDropped": ${udpRelayFlowsDropped.get()},
              "udpRelayOversizeDropped": ${udpRelayOversizeDropped.get()},
              "udpRelayBytesUp": ${udpRelayBytesUp.get()},
              "udpRelayBytesDown": ${udpRelayBytesDown.get()},
              "bypassedPackages": $bypassedPackages,
              "lastProtectionFailureReason": "${json(lastProtectionFailureReason)}",
              "httpsMetadataMode": "${json(httpsMetadataMode)}",
              "httpsClientHelloInspected": ${httpsClientHelloInspected.get()},
              "sniSeen": ${sniSeen.get()},
              "ipv6PacketsBlocked": ${ipv6PacketsBlocked.get()},
              "sessionRxBytes": ${sessionRxBytes.get()},
              "sessionTxBytes": ${sessionTxBytes.get()},
              "rxBytesPerSecond": ${currentRxBytesPerSecond.get()},
              "txBytesPerSecond": ${currentTxBytesPerSecond.get()},
              "adBlockEnabled": $adBlockEnabled,
              "blockedDnsQueries": ${blockedDnsQueries.get()},
              "lastBlockedDomain": "${json(lastBlockedDomain)}",
              "turboModeEnabled": $turboModeEnabled,
              "turboDesired": ${turboRuntime.desired},
              "turboRuntimeState": "${turboRuntime.state.name}",
              "turboRuntimeGeneration": ${turboRuntime.generation},
              "turboRuntimeReason": "${json(turboRuntime.reasonCode)}",
              "turboHandshakeBudgetExhausted": ${turboHandshakeBudgetExhausted.get()},
              "routeMode": "${routeMode.value}",
              "turboThemeActive": $turboThemeActive,
              "turboQuicGuardEnabled": $turboQuicGuardEnabled,
              "turboRouteAllIPv4Requested": $turboRouteAllIPv4Requested,
              "turboRouteAllIPv4Enabled": $turboRouteAllIPv4Enabled,
              "turboForwarderPreflightPassed": $turboForwarderPreflightPassed,
              "turboConnectivityCheckPassed": $turboConnectivityCheckPassed,
              "turboUdp443PacketsBlocked": ${turboUdp443PacketsBlocked.get()},
              "turboHttpsSvcbRecordsSuppressed": ${turboHttpsSvcbRecordsSuppressed.get()},
              "turboTcp443PacketsSeen": ${turboTcp443PacketsSeen.get()},
              "turboTcpForwardingActive": $turboTcpForwardingActive,
              "turboTcpConnectionsOpened": ${turboTcpConnectionsOpened.get()},
              "turboTcpConnectionsClosed": ${turboTcpConnectionsClosed.get()},
              "turboTcpActiveConnections": ${turboTcpActiveConnections.get()},
              "turboTcpTimeWaitTombstones": ${turboTcpTimeWaitTombstones.get()},
              "turboTcpCapacityRejected": ${turboTcpCapacityRejected.get()},
              "turboTcpTupleReuseAccepted": ${turboTcpTupleReuseAccepted.get()},
              "turboTcpTupleReuseGuarded": ${turboTcpTupleReuseGuarded.get()},
              "turboTcpForwardingErrors": ${turboTcpForwardingErrors.get()},
              "turboClientHelloDetected": ${turboClientHelloDetected.get()},
              "turboSniDetected": ${turboSniDetected.get()},
              "turboFragmentationApplied": ${turboFragmentationApplied.get()},
              "turboFragmentationStrategy": "${json(turboFragmentationStrategy)}",
              "turboForwardBytesUp": ${turboForwardBytesUp.get()},
              "turboForwardBytesDown": ${turboForwardBytesDown.get()},
              "tunnelRestartCount": ${tunnelRestartCount.get()},
              "packetLoopActive": $packetLoopActive,
              "duplicateServiceDetected": $duplicateServiceDetected,
              "turboBypassActuallyActive": $turboBypassActuallyActive,
              "turboLastFailureReason": "${json(turboLastFailureReason)}"
            }
        """.trimIndent()
    }

    companion object {
        fun sanitize(message: String): String {
            return message.lineSequence().firstOrNull().orEmpty().take(160)
        }

        private fun json(value: String): String {
            return buildString(value.length) {
                value.forEach { character ->
                    when (character) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\b' -> append("\\b")
                        '\u000c' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> {
                            if (character.code < 0x20) {
                                append("\\u")
                                append(character.code.toString(16).padStart(4, '0'))
                            } else {
                                append(character)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateBandwidth() {
        synchronized(sampleLock) {
            val now = elapsedRealtimeMs()
            val rx = sessionRxBytes.get()
            val tx = sessionTxBytes.get()
            if (!underlyingNetworkValidated) {
                currentRxBytesPerSecond.set(0L)
                currentTxBytesPerSecond.set(0L)
                lastSampleAtMs = now
                lastSampleRxBytes = rx
                lastSampleTxBytes = tx
                return
            }
            val elapsedMs = (now - lastSampleAtMs).coerceAtLeast(1L)
            if (elapsedMs < 350L) return
            currentRxBytesPerSecond.set(((rx - lastSampleRxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs)
            currentTxBytesPerSecond.set(((tx - lastSampleTxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs)
            lastSampleAtMs = now
            lastSampleRxBytes = rx
            lastSampleTxBytes = tx
        }
    }

}
