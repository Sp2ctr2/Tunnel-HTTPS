package com.tunnelvpn.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

class TurboDestinationHasher(secret: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA256").apply {
        val keyBytes = secret.copyOf().also { require(it.size >= 16) }
        try {
            init(SecretKeySpec(keyBytes, "HmacSHA256"))
        } finally {
            keyBytes.fill(0)
        }
    }

    fun hash(destination: String): String {
        val normalized = destination.lowercase().trimEnd('.')
        val digest = synchronized(mac) {
            mac.doFinal(normalized.toByteArray(Charsets.UTF_8))
        }
        return TurboModelCodec.encodeHex(digest, byteLimit = 16)
    }
}

class TurboFeatureEncoder {
    fun encode(context: TurboContext): String = encode(context, context.destinationKey)

    fun encodeShared(context: TurboContext): String = encode(context, SHARED_CONTEXT_PREFIX)

    private fun encode(context: TurboContext, key: String): String {
        return buildString(96) {
            append(key)
            append('|').append(context.destinationPort.coerceIn(0, 65_535))
            append('|').append(context.transport.name)
            append('|').append(if (context.metered) 'M' else 'U')
            append(if (context.roaming) 'R' else 'N')
            append(if (context.validated) 'V' else 'X')
            append(if (context.batterySaver) 'B' else 'P')
            append('|').append(if (context.clientHelloParsed) 'H' else 'X')
            append(if (context.sniPresent) 'S' else 'N')
            append('|').append(context.latencyProfile.take(12))
            append('|').append(context.approximateRttBucket.coerceIn(0, 4))
            append(context.recentRetryBucket.coerceIn(0, 4))
            append(context.recentSuccessBucket.coerceIn(0, 4))
            append(context.recentHandshakeBucket.coerceIn(0, 4))
            append(context.resourcePressureBucket.coerceIn(0, 4))
            append('|').append(context.previousFailure.name)
        }
    }

    companion object {
        const val SHARED_CONTEXT_PREFIX = "shared"
    }
}

class TurboSafetyPolicy {
    data class Result(
        val eligible: List<TurboStrategyId>,
        val excludedReasonCodes: List<String>
    )

    fun eligibleStrategies(context: TurboContext): Result {
        if (context.destinationPort != HTTPS_PORT) {
            return Result(listOf(TurboStrategyId.TLS_PLAIN_V1), listOf("fragmentation-requires-tls-443"))
        }
        if (!context.clientHelloParsed) {
            return Result(listOf(TurboStrategyId.TLS_PLAIN_V1), listOf("fragmentation-requires-valid-clienthello"))
        }
        if (!context.sniPresent) {
            return Result(
                listOf(TurboStrategyId.TLS_SINGLE_SAFE_V1, TurboStrategyId.TLS_PLAIN_V1),
                listOf("hostname-strategies-require-sni")
            )
        }
        return Result(
            listOf(
                TurboStrategyId.TLS_SNI_MULTI_V1,
                TurboStrategyId.TLS_HOSTNAME_V1,
                TurboStrategyId.TLS_SINGLE_SAFE_V1,
                TurboStrategyId.TLS_PLAIN_V1
            ),
            listOf("plain-tls-is-emergency-fallback")
        )
    }

    companion object {
        private const val HTTPS_PORT = 443
    }
}

class TurboAiEngine(
    secret: ByteArray,
    initialState: TurboPersistedState = TurboPersistedState(),
    private val flags: TurboAiFlags,
    private val clock: TurboClock = TurboClock { System.currentTimeMillis() },
    private val random: TurboRandom = DefaultTurboRandom(),
    private val rewardCalculator: TurboRewardCalculator = TurboRewardCalculator(),
    private val featureEncoder: TurboFeatureEncoder = TurboFeatureEncoder(),
    private val safetyPolicy: TurboSafetyPolicy = TurboSafetyPolicy(),
    private val maxContexts: Int = DEFAULT_MAX_CONTEXTS
) {
    private class MutableEntry(value: TurboModelEntry) {
        @Volatile var weightedCount = value.weightedCount
        @Volatile var meanReward = value.meanReward
        @Volatile var weightedSuccesses = value.weightedSuccesses
        @Volatile var latencyEmaMs = value.latencyEmaMs
        @Volatile var lastUpdatedMs = value.lastUpdatedMs
        @Volatile var lastAccessMs = value.lastAccessMs
    }

    private data class ArmScore(
        val strategy: TurboStrategyId,
        val directCount: Double,
        val sharedCount: Double,
        val meanReward: Double
    ) {
        val evidenceCount: Double
            get() = directCount + minOf(sharedCount, SHARED_PRIOR_COUNT_CAP) * SHARED_PRIOR_WEIGHT
    }

    private val hasher = TurboDestinationHasher(secret)
    private val entries = ConcurrentHashMap<String, MutableEntry>()
    private val totalSamples = AtomicLong(initialState.totalSamples.coerceAtLeast(0L))
    private val evictionLock = Any()
    val evaluation = TurboEvaluationTracker(rewardCalculator)

    init {
        val now = clock.nowMs()
        initialState.entries
            .asSequence()
            .filter { it.weightedCount.isFinite() && it.weightedCount >= 0.0 }
            .filter { now - it.lastUpdatedMs <= STALE_ENTRY_MS }
            .sortedByDescending { it.lastAccessMs }
            .take(maxContexts * TurboStrategyId.values().size)
            .forEach { entries[entryKey(it.contextKey, it.strategy)] = MutableEntry(it) }
        evictIfNeeded(now)
    }

    fun destinationKey(destination: String): String = hasher.hash(destination)

    fun select(
        context: TurboContext,
        baselineOrder: List<TurboStrategyId>
    ): TurboDecision {
        val started = System.nanoTime()
        require(baselineOrder.isNotEmpty()) { "baseline-order-empty" }
        val safety = safetyPolicy.eligibleStrategies(context)
        val eligible = safety.eligible.filter { it in baselineOrder }.distinct()
        require(eligible.isNotEmpty()) { "no-safe-runtime-eligible-strategy" }
        val safeBaseline = baselineOrder.first { it in eligible }
        val contextKey = featureEncoder.encode(context)
        val sharedContextKey = featureEncoder.encodeShared(context)
        val now = clock.nowMs()
        val scored = eligible.map { strategy ->
            val direct = entries[entryKey(contextKey, strategy)]
            val shared = entries[entryKey(sharedContextKey, strategy)]
            val directCount = direct?.let { effectiveCount(it.weightedCount, now - it.lastUpdatedMs) } ?: 0.0
            val sharedCount = shared?.let { effectiveCount(it.weightedCount, now - it.lastUpdatedMs) } ?: 0.0
            val sharedPriorCount = minOf(sharedCount, SHARED_PRIOR_COUNT_CAP) * SHARED_PRIOR_WEIGHT
            val combinedCount = directCount + sharedPriorCount
            val combinedMean = if (combinedCount > 0.0) {
                ((direct?.meanReward ?: 0.0) * directCount +
                    (shared?.meanReward ?: 0.0) * sharedPriorCount) / combinedCount
            } else {
                0.0
            }
            ArmScore(strategy, directCount, sharedCount, combinedMean)
        }
        val directSamples = scored.sumOf { it.directCount }
        val sharedSamples = scored.sumOf { it.sharedCount }
        val evidenceSamples = scored.sumOf { it.evidenceCount }
        val coldStart = directSamples < COLD_START_SAMPLES
        val explorationRate = explorationRate(context, evidenceSamples)
        val canExplore = flags.enabled && flags.explorationEnabled && !flags.forceBaseline && eligible.size > 1 &&
            context.validated && !context.roaming && (!context.metered || evidenceSamples >= COLD_START_SAMPLES)
        val explore = canExplore && random.nextDouble() < explorationRate

        val selected: TurboStrategyId
        val policy: TurboDecisionPolicy
        val reasonCode: String
        if (!flags.enabled || flags.forceBaseline) {
            selected = safeBaseline
            policy = TurboDecisionPolicy.BASELINE
            reasonCode = if (!flags.enabled) "ai-disabled-baseline" else "forced-baseline"
        } else if (coldStart && !explore) {
            selected = safeBaseline
            policy = TurboDecisionPolicy.BASELINE
            reasonCode = "cold-start-baseline"
        } else if (explore) {
            val alternatives = scored.filterNot { it.strategy == safeBaseline }
            val minimumEvidence = alternatives.minOf { it.evidenceCount }
            val leastObserved = alternatives.filter { it.evidenceCount <= minimumEvidence + SCORE_EPSILON }
            selected = leastObserved[random.nextInt(leastObserved.size)].strategy
            policy = TurboDecisionPolicy.EXPLORATION
            reasonCode = "bounded-exploration"
        } else {
            val total = evidenceSamples.coerceAtLeast(1.0)
            val optimisticScoring = flags.explorationEnabled && context.validated && !context.roaming &&
                (!context.metered || evidenceSamples >= COLD_START_SAMPLES)
            val scoringCandidates = if (optimisticScoring) {
                scored
            } else {
                scored.filter { it.strategy == safeBaseline || it.evidenceCount > SCORE_EPSILON }
            }
            selected = scoringCandidates
                .sortedBy { it.strategy.stableId }
                .maxByOrNull { arm ->
                    val bonus = if (optimisticScoring) {
                        UCB_EXPLORATION_WEIGHT * sqrt(ln(total + 1.0) / (arm.evidenceCount + 1.0))
                    } else {
                        0.0
                    }
                    arm.meanReward + bonus + if (arm.strategy == safeBaseline) BASELINE_PRIOR else 0.0
                }!!
                .strategy
            policy = if (selected == TurboStrategyId.TLS_PLAIN_V1 && context.previousFailure != TurboFailureCategory.NONE) {
                TurboDecisionPolicy.DEFAULT_SAFE
            } else {
                TurboDecisionPolicy.LEARNED
            }
            reasonCode = "contextual-ucb"
        }

        entries[entryKey(contextKey, selected)]?.lastAccessMs = now
        entries[entryKey(sharedContextKey, selected)]?.lastAccessMs = now
        val orderedFallbacks = baselineOrder.filter { it in eligible && it != selected } +
            eligible.filter { it != selected && it !in baselineOrder }
        val confidence = confidence(
            scored.firstOrNull { it.strategy == selected }?.evidenceCount ?: 0.0,
            evidenceSamples
        )
        return TurboDecision(
            strategy = selected,
            fallbackOrder = orderedFallbacks.distinct(),
            policy = policy,
            reason = TurboDecisionReason(
                code = reasonCode,
                confidence = confidence,
                eligibleStrategyIds = eligible.map { it.stableId },
                excludedReasonCodes = safety.excludedReasonCodes +
                    if (eligible.size < safety.eligible.size) listOf("runtime-suppressed-strategy") else emptyList()
            ),
            contextKey = contextKey,
            sharedContextKey = sharedContextKey,
            batterySaver = context.batterySaver,
            decisionOverheadNanos = (System.nanoTime() - started).coerceAtLeast(0L)
        )
    }

    fun update(outcome: TurboOutcome): Double {
        val reward = rewardCalculator.calculate(outcome)
        if (outcome.failureCategory in NON_STRATEGY_FAILURES) {
            evaluation.record(outcome, reward)
            return reward
        }
        if (!flags.modelUpdatesEnabled) {
            evaluation.record(outcome, reward)
            return reward
        }
        val now = outcome.completedAtMs.coerceAtLeast(0L)
        var created = updateEntry(outcome.contextKey, outcome, reward, now)
        if (outcome.sharedContextKey.isNotBlank() && outcome.sharedContextKey != outcome.contextKey) {
            created = updateEntry(outcome.sharedContextKey, outcome, reward, now) || created
        }
        totalSamples.incrementAndGet()
        evaluation.record(outcome, reward)
        if (created) evictIfNeeded(now)
        return reward
    }

    private fun updateEntry(
        contextKey: String,
        outcome: TurboOutcome,
        reward: Double,
        now: Long
    ): Boolean {
        val key = entryKey(contextKey, outcome.strategy)
        var created = false
        val entry = entries.computeIfAbsent(key) {
            created = true
            MutableEntry(
                TurboModelEntry(
                    contextKey = contextKey,
                    strategy = outcome.strategy,
                    weightedCount = 0.0,
                    meanReward = 0.0,
                    weightedSuccesses = 0.0,
                    latencyEmaMs = 0.0,
                    lastUpdatedMs = now,
                    lastAccessMs = now
                )
            )
        }
        synchronized(entry) {
            val decay = decayFactor(now - entry.lastUpdatedMs)
            val oldCount = entry.weightedCount * decay
            val newCount = oldCount + 1.0
            entry.meanReward = ((entry.meanReward * oldCount) + reward) / newCount
            entry.weightedSuccesses = entry.weightedSuccesses * decay + if (outcome.success) 1.0 else 0.0
            entry.weightedCount = newCount
            if (outcome.success) {
                entry.latencyEmaMs = if (entry.latencyEmaMs <= 0.0) {
                    outcome.handshakeLatencyMs.toDouble()
                } else {
                    entry.latencyEmaMs * 0.8 + outcome.handshakeLatencyMs * 0.2
                }
            }
            entry.lastUpdatedMs = now
            entry.lastAccessMs = now
        }
        return created
    }

    fun totalSamples(): Long = totalSamples.get()

    fun overallConfidence(): TurboConfidence = when {
        effectiveSampleCount() >= HIGH_CONFIDENCE_SAMPLES -> TurboConfidence.HIGH
        effectiveSampleCount() >= MEDIUM_CONFIDENCE_SAMPLES -> TurboConfidence.MEDIUM
        else -> TurboConfidence.LOW
    }

    fun exportState(): TurboPersistedState {
        val now = clock.nowMs()
        val snapshot = entries.entries.mapNotNull { (key, value) ->
            val modelEntry = synchronized(value) {
                if (now - value.lastUpdatedMs > STALE_ENTRY_MS) {
                    null
                } else {
                    val split = key.lastIndexOf(ENTRY_SEPARATOR)
                    val contextKey = key.substring(0, split)
                    val strategy = TurboStrategyId.fromStableId(key.substring(split + 1)) ?: return@synchronized null
                    val decay = decayFactor(now - value.lastUpdatedMs)
                    TurboModelEntry(
                        contextKey = contextKey,
                        strategy = strategy,
                        weightedCount = value.weightedCount * decay,
                        meanReward = value.meanReward.coerceIn(-1.0, 1.0),
                        weightedSuccesses = (value.weightedSuccesses * decay).coerceAtLeast(0.0),
                        latencyEmaMs = value.latencyEmaMs.coerceAtLeast(0.0),
                        lastUpdatedMs = now,
                        lastAccessMs = value.lastAccessMs
                    )
                }
            }
            if (modelEntry == null) {
                entries.remove(key, value)
                null
            } else {
                modelEntry
            }
        }.sortedByDescending { it.lastAccessMs }
            .take(maxContexts * TurboStrategyId.values().size)
        return TurboPersistedState(totalSamples = totalSamples.get(), entries = snapshot)
    }

    private fun explorationRate(context: TurboContext, samples: Double): Double {
        if (!flags.explorationEnabled) return 0.0
        val adaptive = (MAX_EXPLORATION / sqrt(1.0 + samples / 8.0)).coerceAtLeast(MIN_EXPLORATION)
        return when {
            context.roaming || !context.validated -> 0.0
            context.metered || context.batterySaver -> minOf(adaptive, CONSERVATIVE_EXPLORATION)
            else -> adaptive
        }
    }

    private fun confidence(selectedCount: Double, totalCount: Double): TurboConfidence = when {
        selectedCount >= 20.0 && totalCount >= 40.0 -> TurboConfidence.HIGH
        selectedCount >= 6.0 && totalCount >= 12.0 -> TurboConfidence.MEDIUM
        else -> TurboConfidence.LOW
    }

    private fun evictIfNeeded(now: Long) {
        synchronized(evictionLock) {
            entries.entries
                .filter { now - it.value.lastUpdatedMs > STALE_ENTRY_MS }
                .forEach { entries.remove(it.key, it.value) }
            val contextAccess = entries.entries
                .groupBy { contextFromEntryKey(it.key) }
                .mapValues { (_, values) -> values.maxOf { it.value.lastAccessMs } }
            val contextOverflow = contextAccess.size - maxContexts
            if (contextOverflow > 0) {
                val evictedContexts = contextAccess.entries.sortedBy { it.value }
                    .take(contextOverflow)
                    .mapTo(HashSet()) { it.key }
                entries.entries
                    .filter { contextFromEntryKey(it.key) in evictedContexts }
                    .forEach { entries.remove(it.key, it.value) }
            }
        }
    }

    private fun effectiveSampleCount(): Double {
        val now = clock.nowMs()
        return entries.entries
            .asSequence()
            .filterNot { contextFromEntryKey(it.key).startsWith(TurboFeatureEncoder.SHARED_CONTEXT_PREFIX) }
            .sumOf { effectiveCount(it.value.weightedCount, now - it.value.lastUpdatedMs) }
    }

    private fun effectiveCount(count: Double, ageMs: Long): Double = count * decayFactor(ageMs)

    private fun decayFactor(ageMs: Long): Double {
        if (ageMs <= 0L) return 1.0
        return 0.5.pow(ageMs.toDouble() / HALF_LIFE_MS.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun entryKey(contextKey: String, strategy: TurboStrategyId): String {
        return "$contextKey$ENTRY_SEPARATOR${strategy.stableId}"
    }

    private fun contextFromEntryKey(key: String): String = key.substringBeforeLast(ENTRY_SEPARATOR)

    companion object {
        const val DEFAULT_MAX_CONTEXTS = 512
        const val STALE_ENTRY_MS = 30L * 24L * 60L * 60L * 1_000L
        const val HALF_LIFE_MS = 7L * 24L * 60L * 60L * 1_000L
        private const val ENTRY_SEPARATOR = '#'
        private const val COLD_START_SAMPLES = 8.0
        private const val SHARED_PRIOR_COUNT_CAP = 12.0
        private const val SHARED_PRIOR_WEIGHT = 0.35
        private const val MAX_EXPLORATION = 0.08
        private const val MIN_EXPLORATION = 0.01
        private const val CONSERVATIVE_EXPLORATION = 0.02
        private const val UCB_EXPLORATION_WEIGHT = 0.22
        private const val BASELINE_PRIOR = 0.02
        private const val MEDIUM_CONFIDENCE_SAMPLES = 24L
        private const val HIGH_CONFIDENCE_SAMPLES = 100L
        private const val SCORE_EPSILON = 0.000001
        private val NON_STRATEGY_FAILURES = setOf(
            TurboFailureCategory.CONNECT,
            TurboFailureCategory.WRITE,
            TurboFailureCategory.SERVER_CLOSED,
            TurboFailureCategory.NETWORK_CHANGED,
            TurboFailureCategory.CANCELLED
        )
    }
}

class TurboEvaluationTracker(
    private val rewardCalculator: TurboRewardCalculator = TurboRewardCalculator()
) {
    private class Aggregate {
        var samples = 0L
        var successes = 0L
        var retries = 0L
        var fallbacks = 0L
        var rewardTotal = 0.0
        var overheadNanosTotal = 0L
        val latencies = ArrayDeque<Long>()
    }

    private val values = ConcurrentHashMap<TurboDecisionPolicy, Aggregate>()

    fun record(outcome: TurboOutcome, reward: Double = rewardCalculator.calculate(outcome)) {
        val aggregate = values.computeIfAbsent(outcome.policy) { Aggregate() }
        synchronized(aggregate) {
            aggregate.samples += 1
            if (outcome.success) aggregate.successes += 1
            aggregate.retries += outcome.retries.coerceAtLeast(0)
            if (outcome.fallbackUsed) aggregate.fallbacks += 1
            aggregate.rewardTotal += reward.coerceIn(-1.0, 1.0)
            aggregate.overheadNanosTotal += outcome.decisionOverheadNanos.coerceAtLeast(0L)
            if (outcome.success) {
                if (aggregate.latencies.size >= MAX_LATENCY_SAMPLES) aggregate.latencies.removeFirst()
                aggregate.latencies.addLast(outcome.handshakeLatencyMs.coerceAtLeast(0L))
            }
        }
    }

    fun snapshots(): List<TurboEvaluationSnapshot> {
        return TurboDecisionPolicy.values().map { policy ->
            val aggregate = values[policy] ?: Aggregate()
            synchronized(aggregate) {
                val sorted = aggregate.latencies.sorted()
                TurboEvaluationSnapshot(
                    policy = policy,
                    samples = aggregate.samples,
                    successes = aggregate.successes,
                    medianHandshakeMs = if (sorted.isEmpty()) 0L else sorted[sorted.size / 2],
                    retries = aggregate.retries,
                    fallbacks = aggregate.fallbacks,
                    averageReward = if (aggregate.samples == 0L) 0.0 else aggregate.rewardTotal / aggregate.samples,
                    averageDecisionOverheadMicros = if (aggregate.samples == 0L) 0.0 else
                        aggregate.overheadNanosTotal.toDouble() / aggregate.samples / 1_000.0
                )
            }
        }
    }

    fun learnedPolicyHasEnoughEvidence(): Boolean {
        val learned = snapshots().filter { it.policy == TurboDecisionPolicy.LEARNED || it.policy == TurboDecisionPolicy.EXPLORATION }
            .sumOf { it.samples }
        val baseline = snapshots().filter { it.policy == TurboDecisionPolicy.BASELINE }.sumOf { it.samples }
        return learned >= MIN_COMPARISON_SAMPLES && baseline >= MIN_COMPARISON_SAMPLES
    }

    companion object {
        private const val MAX_LATENCY_SAMPLES = 128
        private const val MIN_COMPARISON_SAMPLES = 30L
    }
}
