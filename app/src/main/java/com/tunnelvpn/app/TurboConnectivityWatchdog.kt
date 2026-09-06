package com.tunnelvpn.app

import android.net.VpnService
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class UnderlyingNetworkReachabilityResult(
    val passed: Boolean,
    val reason: String
)

internal fun classifyUnderlyingNetworkReachability(
    tcpReachable: Boolean,
    httpsReachable: Boolean
): UnderlyingNetworkReachabilityResult {
    return when {
        !tcpReachable -> UnderlyingNetworkReachabilityResult(false, "tcp-connect")
        !httpsReachable -> UnderlyingNetworkReachabilityResult(false, "https")
        else -> UnderlyingNetworkReachabilityResult(true, "ok")
    }
}

internal fun orderWatchdogProbeCandidates(
    pinned: List<InetAddress>,
    hasIpv4: Boolean,
    synthesizeIpv4: (InetAddress) -> List<InetAddress>,
    limit: Int
): List<InetAddress> {
    if (limit <= 0) return emptyList()
    val unique = pinned.distinctBy(::watchdogAddressIdentity)
    if (hasIpv4) {
        return interleaveWatchdogCandidates(
            unique.filter { address -> address.address.size == 4 },
            unique.filter { address -> address.address.size == 16 },
            limit
        )
    }
    val nativeIpv6 = unique.filter { address -> address.address.size == 16 }
    val synthesized = unique.asSequence()
        .filter { address -> address.address.size == 4 }
        .flatMap { address -> synthesizeIpv4(address).asSequence() }
        .filter { address -> address.address.size == 16 }
        .distinctBy(::watchdogAddressIdentity)
        .toList()
    return interleaveWatchdogCandidates(nativeIpv6, synthesized, limit)
}

private fun interleaveWatchdogCandidates(
    preferred: List<InetAddress>,
    fallback: List<InetAddress>,
    limit: Int
): List<InetAddress> {
    val candidates = ArrayList<InetAddress>(minOf(limit, preferred.size + fallback.size))
    repeat(maxOf(preferred.size, fallback.size)) { index ->
        preferred.getOrNull(index)?.let(candidates::add)
        fallback.getOrNull(index)?.let(candidates::add)
    }
    return candidates.distinctBy(::watchdogAddressIdentity).take(limit)
}

private fun watchdogAddressIdentity(address: InetAddress): Pair<List<Byte>, Int> {
    return address.address.toList() to if (address is Inet6Address) address.scopeId else 0
}

private fun recordWatchdogFailure(diagnostics: DiagnosticsState, detail: String) {
    synchronized(diagnostics) {
        val prior = diagnostics.lastProtectionFailureReason
            .takeIf { value -> value.startsWith(WATCHDOG_FAILURE_PREFIX) }
            ?.removePrefix(WATCHDOG_FAILURE_PREFIX)
            ?.split(',')
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val failures = (prior + detail).distinct().take(MAX_REPORTED_WATCHDOG_FAILURES)
        diagnostics.lastProtectionFailureReason =
            (WATCHDOG_FAILURE_PREFIX + failures.joinToString(",")).take(160)
    }
}

private const val WATCHDOG_FAILURE_PREFIX = "watchdog:"
private const val MAX_REPORTED_WATCHDOG_FAILURES = 8

class UnderlyingNetworkReachabilityProbe internal constructor(
    private val diagnostics: DiagnosticsState,
    private val candidateProvider: (List<InetAddress>) -> List<InetAddress>,
    private val tcpConnector: (InetAddress, Int) -> Boolean,
    private val httpsConnector: (String, String, InetAddress) -> Boolean
) {
    constructor(
        service: VpnService,
        diagnostics: DiagnosticsState,
        candidateProvider: (List<InetAddress>) -> List<InetAddress> = { it },
        protectSocket: (Socket) -> Boolean = { socket -> service.protect(socket) }
    ) : this(
        diagnostics,
        candidateProvider,
        { address, port ->
            protectedTcpConnect(protectSocket, address, port) { failure ->
                recordWatchdogFailure(diagnostics, "tcp-${addressFamily(address)}-$failure")
            }
        },
        { host, path, address ->
            protectedHttpsHead(protectSocket, host, path, address) { failure ->
                recordWatchdogFailure(
                    diagnostics,
                    "https-${providerLabel(host)}-${addressFamily(address)}-$failure"
                )
            }
        }
    )

    fun check(): UnderlyingNetworkReachabilityResult {
        if (diagnostics.lastProtectionFailureReason.startsWith(WATCHDOG_FAILURE_PREFIX)) {
            diagnostics.lastProtectionFailureReason = ""
        }
        val tcpOk = tryCandidates(TCP_PROBE_ADDRESSES) { address ->
            tcpConnector(address, HTTPS_PORT)
        }
        if (!tcpOk) {
            val result = classifyUnderlyingNetworkReachability(tcpReachable = false, httpsReachable = false)
            diagnostics.recordTurboHealthFailure("underlying-${result.reason}")
            return result
        }
        if (diagnostics.lastProtectionFailureReason.startsWith(WATCHDOG_FAILURE_PREFIX)) {
            diagnostics.lastProtectionFailureReason = ""
        }

        val httpsOk = HTTPS_PROBES.any { probe ->
            tryCandidates(probe.addresses) { address ->
                httpsConnector(probe.host, probe.path, address)
            }
        }
        val result = classifyUnderlyingNetworkReachability(tcpReachable = true, httpsReachable = httpsOk)
        if (result.passed) {
            diagnostics.turboConnectivityCheckPassed = true
            if (diagnostics.lastProtectionFailureReason.startsWith(WATCHDOG_FAILURE_PREFIX)) {
                diagnostics.lastProtectionFailureReason = ""
            }
        } else diagnostics.recordTurboHealthFailure("underlying-${result.reason}")
        return result
    }

    private fun tryCandidates(
        pinned: List<InetAddress>,
        attempt: (InetAddress) -> Boolean
    ): Boolean {
        val transformed = runCatching { candidateProvider(pinned) }.getOrDefault(emptyList())
        return transformed.asSequence()
            .distinctBy(::watchdogAddressIdentity)
            .take(MAX_CANDIDATES_PER_PROBE)
            .any { address -> !Thread.currentThread().isInterrupted && attempt(address) }
    }

    private data class Probe(
        val host: String,
        val path: String,
        val addresses: List<InetAddress>
    )

    companion object {
        private const val HTTPS_PORT = 443
        private const val TIMEOUT_MS = 1_500
        private const val MAX_STATUS_LINE_BYTES = 1_024
        private const val STATUS_LINE_TIMEOUT_NANOS = 2_000_000_000L
        internal const val MAX_CANDIDATES_PER_PROBE = 4

        private val TCP_PROBE_ADDRESSES = DohResolver.DEFAULT_PROVIDERS.flatMap { provider ->
            listOfNotNull(
                provider.bootstrapAddresses.firstOrNull { address -> address.address.size == 4 },
                provider.bootstrapAddresses.firstOrNull { address -> address.address.size == 16 }
            )
        }

        private val HTTPS_PROBES = listOf(
            providerProbe("cloudflare", "/dns-query"),
            providerProbe("google", "/resolve?name=example.com&type=A"),
            providerProbe("quad9", "/dns-query")
        )

        private fun providerProbe(name: String, path: String): Probe {
            val provider = DohResolver.DEFAULT_PROVIDERS.first { it.name == name }
            val host = java.net.URI(provider.url).host ?: error("missing probe hostname")
            return Probe(host, path, provider.bootstrapAddresses)
        }

        private fun protectedTcpConnect(
            protectSocket: (Socket) -> Boolean,
            address: InetAddress,
            port: Int,
            reportFailure: (String) -> Unit
        ): Boolean {
            val socket = Socket()
            return try {
                socket.tcpNoDelay = true
                val protected = runCatching { protectSocket(socket) }.getOrDefault(false)
                if (!protected) {
                    reportFailure("protect-bind")
                    return false
                }
                try {
                    socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
                } catch (error: Exception) {
                    reportFailure(if (error is SocketTimeoutException) "connect-timeout" else "connect")
                    return false
                }
                true
            } finally {
                runCatching { socket.close() }
            }
        }

        private fun protectedHttpsHead(
            protectSocket: (Socket) -> Boolean,
            host: String,
            path: String,
            address: InetAddress,
            reportFailure: (String) -> Unit
        ): Boolean {
            val raw = Socket()
            var ssl: SSLSocket? = null
            return try {
                raw.tcpNoDelay = true
                val protected = runCatching { protectSocket(raw) }.getOrDefault(false)
                if (!protected) {
                    reportFailure("protect-bind")
                    return false
                }
                try {
                    raw.connect(InetSocketAddress(address, HTTPS_PORT), TIMEOUT_MS)
                } catch (error: Exception) {
                    reportFailure(if (error is SocketTimeoutException) "connect-timeout" else "connect")
                    return false
                }
                val secureSocket = try {
                    ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, host, HTTPS_PORT, true) as SSLSocket).also { socket ->
                        ssl = socket
                        socket.soTimeout = TIMEOUT_MS
                        socket.sslParameters = socket.sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                        }
                        socket.startHandshake()
                    }
                } catch (error: Exception) {
                    reportFailure(
                        when (error) {
                            is SSLPeerUnverifiedException -> "tls-peer-unverified"
                            is SSLHandshakeException -> "tls-handshake"
                            is SocketTimeoutException -> "tls-timeout"
                            else -> "tls-setup"
                        }
                    )
                    return false
                }
                try {
                    secureSocket.getOutputStream().write(
                        "HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nAccept: */*\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                    secureSocket.getOutputStream().flush()
                } catch (_: Exception) {
                    reportFailure("write")
                    return false
                }
                val status = readStatusLine(secureSocket)
                if (status.line?.startsWith("HTTP/") == true) {
                    true
                } else {
                    reportFailure(status.failureClass)
                    false
                }
            } finally {
                runCatching { ssl?.close() }
                runCatching { raw.close() }
            }
        }

        private data class StatusLineResult(val line: String?, val failureClass: String)

        private fun readStatusLine(socket: SSLSocket): StatusLineResult {
            val input = socket.getInputStream()
            val buffer = ByteArray(MAX_STATUS_LINE_BYTES)
            var size = 0
            val startedAt = System.nanoTime()
            val deadline = if (startedAt > Long.MAX_VALUE - STATUS_LINE_TIMEOUT_NANOS) {
                Long.MAX_VALUE
            } else {
                startedAt + STATUS_LINE_TIMEOUT_NANOS
            }
            while (size < buffer.size) {
                if (Thread.currentThread().isInterrupted) {
                    return StatusLineResult(null, "status-interrupted")
                }
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) return StatusLineResult(null, "status-timeout")
                val remainingMs = ((remainingNanos + 999_999L) / 1_000_000L)
                    .coerceAtMost(TIMEOUT_MS.toLong())
                    .coerceAtLeast(1L)
                socket.soTimeout = remainingMs.toInt()
                val value = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    return StatusLineResult(null, "status-read")
                }
                if (value < 0) return StatusLineResult(null, "status-eof")
                if (value == '\n'.code) {
                    return StatusLineResult(
                        String(buffer, 0, size, Charsets.US_ASCII),
                        "status-invalid"
                    )
                }
                if (value != '\r'.code) buffer[size++] = value.toByte()
            }
            return StatusLineResult(null, "status-oversized")
        }

        private fun addressFamily(address: InetAddress): String =
            if (address.address.size == 16) "v6" else "v4"

        private fun providerLabel(host: String): String = when (host) {
            "cloudflare-dns.com" -> "cf"
            "dns.google" -> "google"
            "dns.quad9.net" -> "quad9"
            else -> "other"
        }
    }
}
