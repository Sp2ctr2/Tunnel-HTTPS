package com.tunnelvpn.app

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SecureResolverId {
    QUAD9_SECURE,
    CLOUDFLARE_SECURITY,
    GOOGLE_DIVERSITY
}

data class SecureResolverProvider(
    val id: SecureResolverId,
    val dohProvider: DohProvider,
    val threatBlocking: Boolean
)

data class ResolverCandidate(
    val provider: SecureResolverProvider,
    val message: ValidatedDnsMessage,
    val elapsedMs: Long
)

interface SecureDnsTransport {
    suspend fun resolve(
        provider: SecureResolverProvider,
        query: ByteArray,
        question: DnsPacket.Question
    ): ResolverCandidate

    fun onNetworkChanged() {}

    fun close() {}
}

class OkHttpSecureDnsTransport(
    providers: List<SecureResolverProvider>,
    protectSocket: (Socket) -> Boolean,
    bootstrapLookup: (String) -> List<InetAddress> = { hostname ->
        okhttp3.Dns.SYSTEM.lookup(hostname)
    },
    bootstrapAddressTransform: (List<InetAddress>) -> List<InetAddress> = { it },
    preferIpv6Bootstrap: () -> Boolean = { false }
) : SecureDnsTransport {
    private val clients = providers.associate { provider ->
        provider.id to DohResolver(
            cache = DnsCache(maxEntries = 1, successTtlMs = 0L, failureTtlMs = 0L),
            providers = listOf(provider.dohProvider),
            protectSocket = protectSocket,
            bootstrapLookup = bootstrapLookup,
            bootstrapAddressTransform = bootstrapAddressTransform,
            preferIpv6Bootstrap = preferIpv6Bootstrap
        )
    }

    override suspend fun resolve(
        provider: SecureResolverProvider,
        query: ByteArray,
        question: DnsPacket.Question
    ): ResolverCandidate {
        val started = System.nanoTime()
        val result = clients.getValue(provider.id).resolve(query, question)
        val parsed = DnsMessageValidator.validate(
            query,
            result.response,
            publicQuery = !isLocalDiscovery(question.domain)
        )
        val validated = if (provider.threatBlocking &&
            parsed.meaning == DnsSecurityMeaning.REBINDING &&
            parsed.addressFingerprints == setOf("local:00000000")
        ) {
            parsed.copy(meaning = DnsSecurityMeaning.BLOCKED)
        } else {
            parsed
        }
        return ResolverCandidate(provider, validated, (System.nanoTime() - started) / 1_000_000L)
    }

    override fun onNetworkChanged() {
        clients.values.forEach { it.onNetworkChanged() }
    }

    override fun close() {
        clients.values.forEach { it.close() }
    }

    private fun isLocalDiscovery(domain: String): Boolean {
        return DnsPacket.isLocalOnlyName(domain)
    }
}

data class ResolverHealthSnapshot(
    val provider: SecureResolverId,
    val successes: Long,
    val failures: Long,
    val timeouts: Long,
    val malformed: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val cooldown: Boolean
)

class SecureResolverRace(
    private val cache: DnsCache,
    private val transport: SecureDnsTransport,
    private val riskProvider: () -> RiskLevel = { RiskLevel.NORMAL },
    private val clock: () -> Long = MonotonicClock::elapsedRealtimeMs,
    initialGeneration: Long = 0L
) : DnsResolver {
    private data class Health(
        val latency: RollingLatency = RollingLatency(),
        var successes: Long = 0,
        var failures: Long = 0,
        var timeouts: Long = 0,
        var malformed: Long = 0,
        var consecutiveFailures: Int = 0,
        var cooldownUntilMs: Long = 0
    )

    private data class Attempt(val candidate: ResolverCandidate?, val errorCode: String)

    private val generation = AtomicLong(initialGeneration)
    private val lifecycleLock = Any()
    private val healthLock = Any()
    private val health = linkedMapOf<SecureResolverId, Health>()
    private val hedgeCount = AtomicLong()
    private val tertiaryCount = AtomicLong()
    private val disagreementCount = AtomicLong()
    private val explicitBlockCount = AtomicLong()

    override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
        rejectLocalDiscovery(question)
        val requestGeneration = generation.get()
        val cacheKey = "$requestGeneration|turbo|${question.key}"
        synchronized(lifecycleLock) {
            if (generation.get() != requestGeneration) throw IOException("stale-network-generation")
            cache.get(cacheKey, query)?.let {
                return DnsResolveResult(it, "turbo-cache", 0L, true)
            }
        }
        val started = System.nanoTime()
        val securityProviders = eligibleSecurityProviders()
        if (securityProviders.isEmpty()) throw IOException("no-eligible-security-resolver")
        val attempts = when (riskProvider()) {
            RiskLevel.NORMAL -> stagedRace(query, question, requestGeneration, securityProviders)
            RiskLevel.ELEVATED, RiskLevel.HIGH -> concurrentSecurityRace(query, question, requestGeneration, securityProviders)
        }
        val candidates = attempts.mapNotNull(Attempt::candidate)
        if (candidates.isEmpty() && attempts.isNotEmpty() && attempts.all { isTimeoutCode(it.errorCode) }) {
            throw java.net.SocketTimeoutException("encrypted-resolvers-timeout")
        }
        val selected = selectSecurityCompliant(candidates, query, question, requestGeneration)
        return synchronized(lifecycleLock) {
            if (generation.get() != requestGeneration) throw IOException("stale-network-generation")
            if ((selected.message.meaning == DnsSecurityMeaning.ACCEPTABLE ||
                    selected.message.meaning == DnsSecurityMeaning.NXDOMAIN) &&
                selected.message.minimumTtlSeconds > 0L
            ) {
                val ttlMs = selected.message.minimumTtlSeconds.coerceAtMost(MAX_TTL_SECONDS) * 1_000L
                cache.putSuccess(cacheKey, selected.message.bytes, ttlMs)
            }
            DnsResolveResult(
                selected.message.bytes,
                selected.provider.id.name.lowercase(),
                (System.nanoTime() - started) / 1_000_000L,
                false
            )
        }
    }

    override fun onNetworkChanged() {
        synchronized(lifecycleLock) {
            synchronized(healthLock) {
                generation.incrementAndGet()
                health.clear()
            }
            cache.clear()
            transport.onNetworkChanged()
        }
    }

    override fun close() {
        cache.clear()
        transport.close()
    }

    fun currentGeneration(): Long = generation.get()

    fun diagnostics(): Map<String, Long> {
        return linkedMapOf(
            "hedges" to hedgeCount.get(),
            "tertiary" to tertiaryCount.get(),
            "disagreements" to disagreementCount.get(),
            "explicitBlocks" to explicitBlockCount.get(),
            "cacheSize" to cache.size().toLong()
        )
    }

    fun healthSnapshots(): List<ResolverHealthSnapshot> = synchronized(healthLock) {
        SecureResolverId.entries.map { id ->
            val value = health.getOrPut(id) { Health() }
            ResolverHealthSnapshot(
                id,
                value.successes,
                value.failures,
                value.timeouts,
                value.malformed,
                value.latency.percentile(0.50),
                value.latency.percentile(0.95),
                value.cooldownUntilMs > clock()
            )
        }
    }

    internal fun hedgeDelayMs(): Long {
        val provider = eligibleSecurityProviders().firstOrNull() ?: return DEFAULT_HEDGE_MS
        val value = synchronized(healthLock) { health.getOrPut(provider.id) { Health() } }
        if (value.latency.count() < MIN_HEDGE_SAMPLES) return DEFAULT_HEDGE_MS
        return calculateHedgeDelayMs(
            value.latency.percentile(0.50),
            value.latency.percentile(0.95)
        )
    }

    private suspend fun stagedRace(
        query: ByteArray,
        question: DnsPacket.Question,
        requestGeneration: Long,
        securityProviders: List<SecureResolverProvider>
    ): List<Attempt> = coroutineScope {
        val channel = Channel<Attempt>(capacity = securityProviders.size)
        val primary = securityProviders[0]
        val primaryJob = launchAttempt(channel, primary, query, question, requestGeneration)
        if (securityProviders.size == 1) {
            val only = channel.receive()
            primaryJob.cancel()
            channel.close()
            return@coroutineScope listOf(only)
        }
        val first = withTimeoutOrNull(hedgeDelayMs()) { channel.receive() }
        if (first?.candidate?.let(::immediatelyAcceptable) == true) {
            primaryJob.cancel()
            channel.close()
            return@coroutineScope listOf(first)
        }
        val results = mutableListOf<Attempt>()
        first?.let(results::add)
        if (securityProviders.size > 1) {
            hedgeCount.incrementAndGet()
            val secondaryJob = launchAttempt(channel, securityProviders[1], query, question, requestGeneration)
            val outstanding = if (first == null) 2 else 1
            repeat(outstanding) {
                val attempt = channel.receive()
                results += attempt
                attempt.candidate?.let { candidate ->
                    if (immediatelyAcceptable(candidate)) {
                        primaryJob.cancel()
                        secondaryJob.cancel()
                        channel.close()
                        return@coroutineScope results
                    }
                }
            }
            secondaryJob.cancel()
        }
        primaryJob.cancel()
        channel.close()
        results
    }

    private suspend fun concurrentSecurityRace(
        query: ByteArray,
        question: DnsPacket.Question,
        requestGeneration: Long,
        securityProviders: List<SecureResolverProvider>
    ): List<Attempt> = coroutineScope {
        securityProviders.take(MAX_SECURITY_REQUESTS).map { provider ->
            async { attempt(provider, query, question, requestGeneration) }
        }.map { it.await() }
    }

    private fun CoroutineScope.launchAttempt(
        channel: Channel<Attempt>,
        provider: SecureResolverProvider,
        query: ByteArray,
        question: DnsPacket.Question,
        requestGeneration: Long
    ) = launch {
        channel.send(attempt(provider, query, question, requestGeneration))
    }

    private suspend fun attempt(
        provider: SecureResolverProvider,
        query: ByteArray,
        question: DnsPacket.Question,
        requestGeneration: Long
    ): Attempt {
        if (generation.get() != requestGeneration) return Attempt(null, "stale-generation")
        return try {
            val candidate = transport.resolve(provider, query, question)
            if (generation.get() != requestGeneration) return Attempt(null, "stale-generation")
            val recorded = if (candidate.message.meaning == DnsSecurityMeaning.ERROR) {
                recordFailure(
                    provider.id,
                    IOException("dns-rcode-${candidate.message.rcode}"),
                    requestGeneration
                )
            } else {
                recordSuccess(candidate, requestGeneration)
            }
            if (!recorded) return Attempt(null, "stale-generation")
            Attempt(candidate, "none")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (generation.get() != requestGeneration) return Attempt(null, "stale-generation")
            if (!recordFailure(provider.id, error, requestGeneration)) {
                return Attempt(null, "stale-generation")
            }
            Attempt(null, classifyError(error))
        }
    }

    private suspend fun selectSecurityCompliant(
        candidates: List<ResolverCandidate>,
        query: ByteArray,
        question: DnsPacket.Question,
        requestGeneration: Long
    ): ResolverCandidate {
        candidates.firstOrNull { it.message.meaning == DnsSecurityMeaning.DNSSEC_BOGUS }?.let {
            throw IOException("dnssec-bogus")
        }
        candidates.firstOrNull { it.message.meaning == DnsSecurityMeaning.FORGED }?.let {
            throw IOException("forged-answer")
        }
        candidates.firstOrNull { it.message.meaning == DnsSecurityMeaning.REBINDING }?.let {
            throw IOException("rebinding-answer")
        }
        val acceptable = candidates.filter {
            it.message.meaning == DnsSecurityMeaning.ACCEPTABLE ||
                it.message.meaning == DnsSecurityMeaning.NXDOMAIN
        }
        val errors = candidates.filter { it.message.meaning == DnsSecurityMeaning.ERROR }
        val policyBlocks = candidates.filter { isExplicitSecurityBlock(it.message.meaning) }
        if (policyBlocks.isEmpty() && acceptable.isNotEmpty() && !materiallyDisagrees(acceptable)) {
            return acceptable.minBy { it.elapsedMs }
        }
        if (policyBlocks.isEmpty() && acceptable.size == 1 && candidates.size == 1) return acceptable[0]
        if (policyBlocks.size >= 2 && acceptable.isEmpty()) {
            explicitBlockCount.incrementAndGet()
            return policyBlocks.minBy { it.elapsedMs }
        }
        if (acceptable.isEmpty() && policyBlocks.isEmpty() && errors.isNotEmpty()) {
            return errors.minBy { it.elapsedMs }
        }
        disagreementCount.incrementAndGet()
        val google = PROVIDERS.first { it.id == SecureResolverId.GOOGLE_DIVERSITY }
        if (!eligible(google)) {
            return acceptable.minByOrNull { it.elapsedMs }
                ?: policyBlocks.minByOrNull { it.elapsedMs }
                ?: throw IOException("security-resolvers-unavailable")
        }
        tertiaryCount.incrementAndGet()
        val tieBreaker = attempt(google, query, question, requestGeneration).candidate
            ?: return acceptable.minByOrNull { it.elapsedMs }
                ?: policyBlocks.minByOrNull { it.elapsedMs }
                ?: throw IOException("encrypted-resolvers-unavailable")
        return consensusChoice(candidates + tieBreaker)
    }

    private fun consensusChoice(candidates: List<ResolverCandidate>): ResolverCandidate {
        candidates.firstOrNull { it.message.meaning == DnsSecurityMeaning.DNSSEC_BOGUS }?.let {
            throw IOException("dnssec-bogus")
        }
        candidates.firstOrNull { it.message.meaning == DnsSecurityMeaning.FORGED }?.let {
            throw IOException("forged-answer")
        }
        candidates.firstOrNull { it.message.meaning == DnsSecurityMeaning.REBINDING }?.let {
            throw IOException("rebinding-answer")
        }
        val nxdomain = candidates.count { it.message.meaning == DnsSecurityMeaning.NXDOMAIN }
        if (nxdomain >= 2) return candidates.first { it.message.meaning == DnsSecurityMeaning.NXDOMAIN }
        val positive = candidates.filter { it.message.meaning == DnsSecurityMeaning.ACCEPTABLE }
        val positiveConsensus = positive
            .groupBy { it.message.addressFingerprints }
            .values
            .filter { it.size >= 2 }
            .maxByOrNull { it.size }
        if (positiveConsensus != null) return positiveConsensus.minBy { it.elapsedMs }
        val policyBlocks = candidates.filter { isExplicitSecurityBlock(it.message.meaning) }
        if (policyBlocks.size >= 2) {
            explicitBlockCount.incrementAndGet()
            return policyBlocks.minBy { it.elapsedMs }
        }
        if (positive.size >= 2) {
            return positive.firstOrNull { it.provider.id == SecureResolverId.GOOGLE_DIVERSITY }
                ?: positive.minBy { it.elapsedMs }
        }
        if (positive.isNotEmpty()) return positive.minBy { it.elapsedMs }
        if (policyBlocks.isNotEmpty()) {
            explicitBlockCount.incrementAndGet()
            return policyBlocks.minBy { it.elapsedMs }
        }
        return candidates.firstOrNull() ?: throw IOException("no-valid-encrypted-answer")
    }

    internal fun materiallyDisagrees(candidates: List<ResolverCandidate>): Boolean {
        if (candidates.size < 2) return false
        val meanings = candidates.map { it.message.meaning }.toSet()
        if (meanings.size > 1) return true
        if (meanings.single() != DnsSecurityMeaning.ACCEPTABLE) return false
        val addressSets = candidates.map { it.message.addressFingerprints }
        return addressSets.distinct().size > 1
    }

    private fun immediatelyAcceptable(candidate: ResolverCandidate): Boolean {
        return candidate.message.meaning == DnsSecurityMeaning.ACCEPTABLE ||
            candidate.message.meaning == DnsSecurityMeaning.NXDOMAIN
    }

    private fun isExplicitSecurityBlock(meaning: DnsSecurityMeaning): Boolean {
        return meaning == DnsSecurityMeaning.BLOCKED ||
            meaning == DnsSecurityMeaning.FILTERED ||
            meaning == DnsSecurityMeaning.CENSORED
    }

    private fun eligibleSecurityProviders(): List<SecureResolverProvider> {
        return PROVIDERS.filter { it.threatBlocking && eligible(it) }
            .sortedBy { provider ->
                synchronized(healthLock) {
                    val value = health.getOrPut(provider.id) { Health() }
                    if (value.latency.count() < MIN_RANK_SAMPLES) DEFAULT_RANK_MS else value.latency.ewmaMs()
                }
            }
    }

    private fun eligible(provider: SecureResolverProvider): Boolean = synchronized(healthLock) {
        health.getOrPut(provider.id) { Health() }.cooldownUntilMs <= clock()
    }

    private fun recordSuccess(candidate: ResolverCandidate, requestGeneration: Long): Boolean = synchronized(healthLock) {
        if (generation.get() != requestGeneration) return@synchronized false
        val value = health.getOrPut(candidate.provider.id) { Health() }
        value.successes += 1
        value.consecutiveFailures = 0
        value.cooldownUntilMs = 0L
        value.latency.add(candidate.elapsedMs)
        true
    }

    private fun recordFailure(id: SecureResolverId, error: Exception, requestGeneration: Long): Boolean =
        synchronized(healthLock) {
        if (generation.get() != requestGeneration) return@synchronized false
        val value = health.getOrPut(id) { Health() }
        value.failures += 1
        value.consecutiveFailures += 1
        val code = classifyError(error)
        if (code.contains("timeout")) value.timeouts += 1
        if (code.contains("malformed") || code.contains("pointer") || code.contains("mismatch")) value.malformed += 1
        if (value.consecutiveFailures >= FAILURE_THRESHOLD) {
            value.cooldownUntilMs = clock() + COOLDOWN_MS
            value.consecutiveFailures = 0
        }
        true
    }

    private fun classifyError(error: Exception): String {
        return error.message.orEmpty().lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(64)
            .ifEmpty { error.javaClass.simpleName.lowercase() }
    }

    private fun isTimeoutCode(code: String): Boolean {
        return code.contains("timeout") || code.contains("timedout")
    }

    private fun rejectLocalDiscovery(question: DnsPacket.Question) {
        if (DnsPacket.isLocalOnlyName(question.domain)) throw IOException("local-discovery-not-public-doh")
    }

    companion object {
        private const val MIN_HEDGE_MS = 20L
        private const val MAX_HEDGE_MS = 100L
        private const val DEFAULT_HEDGE_MS = 45L
        private const val MIN_HEDGE_SAMPLES = 4
        private const val MIN_RANK_SAMPLES = 3
        private const val DEFAULT_RANK_MS = 250L
        private const val MAX_SECURITY_REQUESTS = 2
        private const val FAILURE_THRESHOLD = 3
        private const val COOLDOWN_MS = 15_000L
        private const val MAX_TTL_SECONDS = 86_400L

        private fun ipv4(hostname: String, a: Int, b: Int, c: Int, d: Int): InetAddress {
            return InetAddress.getByAddress(hostname, byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
        }

        private fun ipv6(hostname: String, vararg groups: Int): InetAddress {
            require(groups.size == 8)
            val address = ByteArray(16)
            groups.forEachIndexed { index, group ->
                require(group in 0..0xffff)
                address[index * 2] = (group ushr 8).toByte()
                address[index * 2 + 1] = group.toByte()
            }
            return InetAddress.getByAddress(hostname, address)
        }

        val PROVIDERS = listOf(
            SecureResolverProvider(
                SecureResolverId.QUAD9_SECURE,
                DohProvider(
                    "quad9-secure",
                    "https://dns.quad9.net/dns-query",
                    listOf(
                        ipv4("dns.quad9.net", 9, 9, 9, 9),
                        ipv4("dns.quad9.net", 149, 112, 112, 112),
                        ipv6("dns.quad9.net", 0x2620, 0x00fe, 0, 0, 0, 0, 0, 0x00fe),
                        ipv6("dns.quad9.net", 0x2620, 0x00fe, 0, 0, 0, 0, 0, 0x0009)
                    )
                ),
                true
            ),
            SecureResolverProvider(
                SecureResolverId.CLOUDFLARE_SECURITY,
                DohProvider(
                    "cloudflare-security",
                    "https://security.cloudflare-dns.com/dns-query",
                    listOf(
                        ipv4("security.cloudflare-dns.com", 1, 1, 1, 2),
                        ipv4("security.cloudflare-dns.com", 1, 0, 0, 2),
                        ipv6("security.cloudflare-dns.com", 0x2606, 0x4700, 0x4700, 0, 0, 0, 0, 0x1112),
                        ipv6("security.cloudflare-dns.com", 0x2606, 0x4700, 0x4700, 0, 0, 0, 0, 0x1002)
                    )
                ),
                true
            ),
            SecureResolverProvider(
                SecureResolverId.GOOGLE_DIVERSITY,
                DohProvider(
                    "google-diversity",
                    "https://dns.google/dns-query",
                    listOf(
                        ipv4("dns.google", 8, 8, 8, 8),
                        ipv4("dns.google", 8, 8, 4, 4),
                        ipv6("dns.google", 0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888),
                        ipv6("dns.google", 0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8844)
                    )
                ),
                false
            )
        )

        fun create(
            cache: DnsCache,
            protectSocket: (Socket) -> Boolean,
            riskProvider: () -> RiskLevel = { RiskLevel.NORMAL },
            bootstrapLookup: (String) -> List<InetAddress> = { hostname ->
                okhttp3.Dns.SYSTEM.lookup(hostname)
            },
            bootstrapAddressTransform: (List<InetAddress>) -> List<InetAddress> = { it },
            preferIpv6Bootstrap: () -> Boolean = { false }
        ): SecureResolverRace {
            return SecureResolverRace(
                cache,
                OkHttpSecureDnsTransport(
                    PROVIDERS,
                    protectSocket,
                    bootstrapLookup,
                    bootstrapAddressTransform,
                    preferIpv6Bootstrap
                ),
                riskProvider
            )
        }
    }
}

internal fun calculateHedgeDelayMs(p50Ms: Long, p95Ms: Long): Long {
    return ((p95Ms - p50Ms) / 2).coerceIn(40L, 350L)
}
