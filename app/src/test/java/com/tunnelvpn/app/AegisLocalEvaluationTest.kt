package com.tunnelvpn.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val AEGIS_BASELINE_ORDER = listOf(
    TurboStrategyId.TLS_SNI_MULTI_V1,
    TurboStrategyId.TLS_HOSTNAME_V1,
    TurboStrategyId.TLS_SINGLE_SAFE_V1,
    TurboStrategyId.TLS_PLAIN_V1
)

private enum class AegisWorldProfile {
    WIFI,
    CELLULAR,
    ETHERNET,
    ROAMING,
    NO_SNI
}

private data class AegisConfig(
    val seed: Long = 0x4A454749534C4F43L,
    val trainingSamples: Int = 360,
    val evaluationSamples: Int = 360,
    val trainingChangepoints: List<Int> = listOf(144),
    val evaluationChangepoints: List<Int> = listOf(120, 240),
    val emaAlpha: Double = 0.25,
    val emaCalibrationPerArm: Int = 2,
    val warmupIterations: Int = 256
) {
    fun canonicalJson(): String {
        return buildString {
            append('{')
            append("\"schema\":\"aegis-local-evaluation-v1\",")
            append("\"seed\":").append(seed).append(',')
            append("\"trainingSamples\":").append(trainingSamples).append(',')
            append("\"evaluationSamples\":").append(evaluationSamples).append(',')
            append("\"trainingChangepoints\":[")
            append(trainingChangepoints.joinToString(","))
            append("],\"evaluationChangepoints\":[")
            append(evaluationChangepoints.joinToString(","))
            append("],\"emaAlpha\":").append(emaAlpha).append(',')
            append("\"emaCalibrationPerArm\":").append(emaCalibrationPerArm).append(',')
            append("\"warmupIterations\":").append(warmupIterations)
            append('}')
        }
    }
}

private data class AegisCoarseContext(
    val id: String,
    val transport: TurboNetworkTransport,
    val metered: Boolean,
    val roaming: Boolean,
    val validated: Boolean,
    val batterySaver: Boolean,
    val clientHelloParsed: Boolean,
    val sniPresent: Boolean,
    val destinationPort: Int,
    val latencyProfile: String,
    val approximateRttBucket: Int,
    val resourcePressureBucket: Int,
    val profile: AegisWorldProfile,
    val heldOut: Boolean
) {
    fun turbo(destinationKey: String): TurboContext {
        return TurboContext(
            destinationKey = destinationKey,
            destinationPort = destinationPort,
            transport = transport,
            metered = metered,
            roaming = roaming,
            validated = validated,
            batterySaver = batterySaver,
            clientHelloParsed = clientHelloParsed,
            sniPresent = sniPresent,
            latencyProfile = latencyProfile,
            approximateRttBucket = approximateRttBucket,
            recentRetryBucket = 0,
            recentSuccessBucket = 0,
            recentHandshakeBucket = approximateRttBucket,
            resourcePressureBucket = resourcePressureBucket
        )
    }
}

private data class AegisSyntheticOutcome(
    val success: Boolean,
    val handshakeLatencyMs: Long,
    val retries: Int,
    val timedOut: Boolean,
    val utility: Double
)

private data class AegisSyntheticSample(
    val sampleId: String,
    val split: String,
    val index: Int,
    val phase: Int,
    val context: AegisCoarseContext,
    val destinationKey: String,
    val allowed: List<TurboStrategyId>,
    val outcomes: Map<TurboStrategyId, AegisSyntheticOutcome>,
    val hindsightStrategy: TurboStrategyId,
    val hindsightUtility: Double
)

private data class AegisChoice(
    val strategy: TurboStrategyId,
    val policy: String,
    val confidence: String,
    val fallbackOrder: List<TurboStrategyId>,
    val measuredOverheadNanos: Long,
    val engineReportedOverheadNanos: Long? = null,
    val enginePolicy: TurboDecisionPolicy? = null,
    val contextKey: String = "",
    val sharedContextKey: String = "",
    val reasonCode: String = ""
)

private data class AegisDecisionInput(
    val context: AegisCoarseContext,
    val destinationKey: String
)

private data class AegisRawDecision(
    val method: String,
    val sample: AegisSyntheticSample,
    val choice: AegisChoice,
    val outcome: AegisSyntheticOutcome,
    val chosenUtility: Double,
    val regret: Double
)

private data class AegisAggregate(
    val samples: Int,
    val failures: Int,
    val regretTotal: Double,
    val oracleHits: Int,
    val overheadP50Nanos: Long,
    val overheadP95Nanos: Long,
    val overheadP99Nanos: Long
) {
    val failureRate: Double
        get() = if (samples == 0) 0.0 else failures.toDouble() / samples.toDouble()

    val meanRegret: Double
        get() = if (samples == 0) 0.0 else regretTotal / samples.toDouble()

    val hindsightHitRate: Double
        get() = if (samples == 0) 0.0 else oracleHits.toDouble() / samples.toDouble()
}

private data class AegisPolicyMetrics(
    val method: String,
    val training: AegisAggregate,
    val evaluation: AegisAggregate,
    val heldOutEvaluation: AegisAggregate,
    val evaluationByPhase: Map<Int, AegisAggregate>,
    val policyCounts: Map<String, Int>,
    val confidenceCounts: Map<String, Int>
)

private data class AegisPolicyRun(
    val method: String,
    val decisions: List<AegisRawDecision>,
    val metrics: AegisPolicyMetrics
)

private data class AegisEvaluation(
    val config: AegisConfig,
    val configJson: String,
    val configHash: String,
    val samples: List<AegisSyntheticSample>,
    val runs: List<AegisPolicyRun>,
    val verdict: String,
    val finding: String
)

private data class AegisMutableEma(
    var observations: Int = 0,
    var successEma: Double = 0.0,
    var latencyEmaMs: Double = 0.0
) {
    fun observe(outcome: AegisSyntheticOutcome, alpha: Double) {
        val success = if (outcome.success) 1.0 else 0.0
        successEma = if (observations == 0) success else successEma + alpha * (success - successEma)
        val latency = outcome.handshakeLatencyMs.toDouble()
        latencyEmaMs = if (observations == 0) latency else latencyEmaMs + alpha * (latency - latencyEmaMs)
        observations += 1
    }

    fun score(): Double {
        val latencyScore = (1.0 - latencyEmaMs / 2_000.0).coerceIn(0.0, 1.0)
        return successEma * 0.75 + latencyScore * 0.25
    }
}

private interface AegisPolicyRunner {
    val method: String
    fun select(input: AegisDecisionInput): AegisChoice
    fun observe(input: AegisDecisionInput, choice: AegisChoice, outcome: AegisSyntheticOutcome)
}

private class AegisFixedPolicy : AegisPolicyRunner {
    override val method: String = "A_CURRENT_FIXED_SAFE_ORDER"

    override fun select(input: AegisDecisionInput): AegisChoice {
        val started = System.nanoTime()
        val eligible = safeEligible(input.context.turbo(input.destinationKey))
        val selected = AEGIS_BASELINE_ORDER.first { it in eligible }
        val overhead = (System.nanoTime() - started).coerceAtLeast(0L)
        return AegisChoice(
            strategy = selected,
            policy = "current-fixed-safe-order",
            confidence = "LOW",
            fallbackOrder = eligible.filter { it != selected },
            measuredOverheadNanos = overhead,
            reasonCode = "fixed-safe-order"
        )
    }

    override fun observe(input: AegisDecisionInput, choice: AegisChoice, outcome: AegisSyntheticOutcome) = Unit
}

private class AegisEmaPolicy(private val config: AegisConfig) : AegisPolicyRunner {
    override val method: String = "B_DETERMINISTIC_EMA_HEURISTIC"
    private val arms = HashMap<String, AegisMutableEma>()

    override fun select(input: AegisDecisionInput): AegisChoice {
        val started = System.nanoTime()
        val eligible = safeEligible(input.context.turbo(input.destinationKey))
        val underSampled = eligible.filter {
            arms.getOrPut(emaKey(input, it)) { AegisMutableEma() }.observations < config.emaCalibrationPerArm
        }
        val selected = if (underSampled.isNotEmpty()) {
            underSampled.minWithOrNull(compareBy<TurboStrategyId> {
                arms.getValue(emaKey(input, it)).observations
            }.thenBy { AEGIS_BASELINE_ORDER.indexOf(it) }) ?: eligible.first()
        } else {
            var best = eligible.first()
            var bestScore = Double.NEGATIVE_INFINITY
            eligible.forEach { strategy ->
                val score = arms.getValue(emaKey(input, strategy)).score()
                if (score > bestScore + 0.000000001) {
                    best = strategy
                    bestScore = score
                }
            }
            best
        }
        val minimumObservations = eligible.minOf { arms.getValue(emaKey(input, it)).observations }
        val confidence = when {
            minimumObservations >= 8 -> "HIGH"
            minimumObservations >= config.emaCalibrationPerArm -> "MEDIUM"
            else -> "LOW"
        }
        val overhead = (System.nanoTime() - started).coerceAtLeast(0L)
        return AegisChoice(
            strategy = selected,
            policy = "deterministic-recent-observation-ema",
            confidence = confidence,
            fallbackOrder = eligible.filter { it != selected },
            measuredOverheadNanos = overhead,
            reasonCode = if (underSampled.isNotEmpty()) "deterministic-calibration" else "ema-score"
        )
    }

    override fun observe(input: AegisDecisionInput, choice: AegisChoice, outcome: AegisSyntheticOutcome) {
        arms.getOrPut(emaKey(input, choice.strategy)) { AegisMutableEma() }.observe(outcome, config.emaAlpha)
    }

    private fun emaKey(input: AegisDecisionInput, strategy: TurboStrategyId): String {
        return input.context.id + "|" + strategy.stableId
    }
}

private class AegisTurboPolicy(config: AegisConfig) : AegisPolicyRunner {
    override val method: String = "C_EXISTING_TURBO_AI_ENGINE"
    private val clock = AegisMutableClock()
    private val engine = TurboAiEngine(
        secret = ByteArray(32) { 0x4A },
        flags = TurboAiFlags(
            enabled = true,
            explorationEnabled = true,
            modelUpdatesEnabled = true,
            developerDiagnosticsEnabled = false,
            forceBaseline = false
        ),
        clock = clock,
        random = AegisSeededTurboRandom(config.seed xor 0x545552424F414547L)
    )
    private var decisions = 0

    override fun select(input: AegisDecisionInput): AegisChoice {
        clock.value = 1_000L + decisions.toLong() * 1_000L
        decisions += 1
        val context = input.context.turbo(input.destinationKey)
        val started = System.nanoTime()
        val decision = engine.select(context, AEGIS_BASELINE_ORDER)
        val overhead = (System.nanoTime() - started).coerceAtLeast(0L)
        return AegisChoice(
            strategy = decision.strategy,
            policy = "existing-turbo-ai-engine",
            confidence = decision.reason.confidence.name,
            fallbackOrder = decision.fallbackOrder,
            measuredOverheadNanos = overhead,
            engineReportedOverheadNanos = decision.decisionOverheadNanos,
            enginePolicy = decision.policy,
            contextKey = decision.contextKey,
            sharedContextKey = decision.sharedContextKey,
            reasonCode = decision.reason.code
        )
    }

    override fun observe(input: AegisDecisionInput, choice: AegisChoice, outcome: AegisSyntheticOutcome) {
        engine.update(
            TurboOutcome(
                contextKey = choice.contextKey,
                strategy = choice.strategy,
                policy = choice.enginePolicy ?: TurboDecisionPolicy.LEARNED,
                success = outcome.success,
                handshakeLatencyMs = outcome.handshakeLatencyMs,
                connectionDurationMs = if (outcome.success) 30_000L else outcome.handshakeLatencyMs,
                bytesUp = if (outcome.success) 100_000L else 0L,
                bytesDown = if (outcome.success) 1_000_000L else 0L,
                retries = outcome.retries,
                timedOut = outcome.timedOut,
                abruptDisconnect = false,
                fallbackUsed = false,
                decisionOverheadNanos = choice.measuredOverheadNanos,
                batterySaver = input.context.batterySaver,
                failureCategory = if (outcome.success) {
                    TurboFailureCategory.NONE
                } else {
                    TurboFailureCategory.TIMEOUT
                },
                completedAtMs = clock.value,
                sharedContextKey = choice.sharedContextKey
            )
        )
    }
}

private class AegisMutableClock : TurboClock {
    var value: Long = 1_000L
    override fun nowMs(): Long = value
}

private class AegisSeededTurboRandom(seed: Long) : TurboRandom {
    private val random = Random(seed)
    override fun nextDouble(): Double = random.nextDouble()
    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}

private class AegisSeededSequence(seed: Long) {
    private var state = seed

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound.toLong()).toInt()
    }

    private fun nextLong(): Long {
        state += -7046029254386353131L
        return aegisMix64(state)
    }
}

private fun safeEligible(context: TurboContext): List<TurboStrategyId> {
    val safety = TurboSafetyPolicy().eligibleStrategies(context)
    return AEGIS_BASELINE_ORDER.filter { it in safety.eligible }.distinct()
}

private fun aegisContexts(): List<AegisCoarseContext> {
    return listOf(
        AegisCoarseContext(
            id = "wifi-stable",
            transport = TurboNetworkTransport.WIFI,
            metered = false,
            roaming = false,
            validated = true,
            batterySaver = false,
            clientHelloParsed = true,
            sniPresent = true,
            destinationPort = 443,
            latencyProfile = "DEFAULT",
            approximateRttBucket = 1,
            resourcePressureBucket = 0,
            profile = AegisWorldProfile.WIFI,
            heldOut = false
        ),
        AegisCoarseContext(
            id = "cellular-metered",
            transport = TurboNetworkTransport.CELLULAR,
            metered = true,
            roaming = false,
            validated = true,
            batterySaver = false,
            clientHelloParsed = true,
            sniPresent = true,
            destinationPort = 443,
            latencyProfile = "CONSERVATIVE",
            approximateRttBucket = 2,
            resourcePressureBucket = 1,
            profile = AegisWorldProfile.CELLULAR,
            heldOut = false
        ),
        AegisCoarseContext(
            id = "wifi-battery",
            transport = TurboNetworkTransport.WIFI,
            metered = false,
            roaming = false,
            validated = true,
            batterySaver = true,
            clientHelloParsed = true,
            sniPresent = true,
            destinationPort = 443,
            latencyProfile = "CONSERVATIVE",
            approximateRttBucket = 2,
            resourcePressureBucket = 2,
            profile = AegisWorldProfile.WIFI,
            heldOut = false
        ),
        AegisCoarseContext(
            id = "ethernet-heldout",
            transport = TurboNetworkTransport.ETHERNET,
            metered = false,
            roaming = false,
            validated = true,
            batterySaver = false,
            clientHelloParsed = true,
            sniPresent = true,
            destinationPort = 443,
            latencyProfile = "DEFAULT",
            approximateRttBucket = 0,
            resourcePressureBucket = 0,
            profile = AegisWorldProfile.ETHERNET,
            heldOut = true
        ),
        AegisCoarseContext(
            id = "cellular-roaming-heldout",
            transport = TurboNetworkTransport.CELLULAR,
            metered = true,
            roaming = true,
            validated = true,
            batterySaver = false,
            clientHelloParsed = true,
            sniPresent = true,
            destinationPort = 443,
            latencyProfile = "CONSERVATIVE",
            approximateRttBucket = 3,
            resourcePressureBucket = 2,
            profile = AegisWorldProfile.ROAMING,
            heldOut = true
        ),
        AegisCoarseContext(
            id = "wifi-no-sni-heldout",
            transport = TurboNetworkTransport.WIFI,
            metered = false,
            roaming = false,
            validated = true,
            batterySaver = false,
            clientHelloParsed = true,
            sniPresent = false,
            destinationPort = 443,
            latencyProfile = "DEFAULT",
            approximateRttBucket = 1,
            resourcePressureBucket = 0,
            profile = AegisWorldProfile.NO_SNI,
            heldOut = true
        )
    )
}

private fun buildAegisTrace(config: AegisConfig): List<AegisSyntheticSample> {
    val contexts = aegisContexts()
    val trainingContexts = contexts.filterNot { it.heldOut }
    val evaluationContexts = contexts
    return buildAegisSplit(
        config = config,
        split = "train",
        count = config.trainingSamples,
        changePoints = config.trainingChangepoints,
        contexts = trainingContexts,
        seed = config.seed xor 0x545241494E4C4F43L
    ) + buildAegisSplit(
        config = config,
        split = "eval",
        count = config.evaluationSamples,
        changePoints = config.evaluationChangepoints,
        contexts = evaluationContexts,
        seed = config.seed xor 0x4556414C4C4F43L
    )
}

private fun buildAegisSplit(
    config: AegisConfig,
    split: String,
    count: Int,
    changePoints: List<Int>,
    contexts: List<AegisCoarseContext>,
    seed: Long
): List<AegisSyntheticSample> {
    val selector = AegisSeededSequence(seed)
    return buildList {
        repeat(count) { index ->
            val context = contexts[selector.nextInt(contexts.size)]
            val destinationSlot = selector.nextInt(8)
            val phase = changePoints.count { index >= it }
            val destinationKey = aegisOpaqueKey(seed, context.id, index, destinationSlot)
            val turboContext = context.turbo(destinationKey)
            val allowed = safeEligible(turboContext)
            val outcomes = LinkedHashMap<TurboStrategyId, AegisSyntheticOutcome>()
            allowed.forEach { strategy ->
                outcomes[strategy] = buildAegisOutcome(config.seed, split, index, phase, context, strategy)
            }
            var hindsightStrategy = allowed.first()
            allowed.drop(1).forEach { strategy ->
                if (outcomes.getValue(strategy).utility > outcomes.getValue(hindsightStrategy).utility + 0.000000001) {
                    hindsightStrategy = strategy
                }
            }
            add(
                AegisSyntheticSample(
                    sampleId = "$split-${index.toString().padStart(4, '0')}",
                    split = split,
                    index = index,
                    phase = phase,
                    context = context,
                    destinationKey = destinationKey,
                    allowed = allowed,
                    outcomes = outcomes,
                    hindsightStrategy = hindsightStrategy,
                    hindsightUtility = outcomes.getValue(hindsightStrategy).utility
                )
            )
        }
    }
}

private fun buildAegisOutcome(
    seed: Long,
    split: String,
    index: Int,
    phase: Int,
    context: AegisCoarseContext,
    strategy: TurboStrategyId
): AegisSyntheticOutcome {
    val order = aegisPreferredOrder(context.profile, phase)
    val rank = order.indexOf(strategy).coerceAtLeast(0)
    val reliability = when (context.profile) {
        AegisWorldProfile.WIFI -> 0.035
        AegisWorldProfile.CELLULAR -> 0.010
        AegisWorldProfile.ETHERNET -> 0.050
        AegisWorldProfile.ROAMING -> -0.035
        AegisWorldProfile.NO_SNI -> 0.000
    }
    val probability = (
        0.785 + (3 - rank) * 0.055 + reliability -
            if (context.metered) 0.010 else 0.0 -
            if (context.batterySaver) 0.015 else 0.0
        ).coerceIn(0.60, 0.98)
    val sampleSeed = aegisMix64(
        seed xor
            aegisStableHash64(split) xor
            index.toLong() * -7046029254386353131L xor
            phase.toLong() * 2862933555777941757L xor
            aegisStableHash64(context.id) xor
            strategy.stableId.hashCode().toLong()
    )
    val success = aegisUnit(sampleSeed) < probability
    val latencyBase = when (context.profile) {
        AegisWorldProfile.WIFI -> 35L
        AegisWorldProfile.CELLULAR -> 105L
        AegisWorldProfile.ETHERNET -> 22L
        AegisWorldProfile.ROAMING -> 175L
        AegisWorldProfile.NO_SNI -> 90L
    }
    val jitter = aegisPositiveMod(aegisMix64(sampleSeed xor 0x6A09E667F3BCC909L), 41L) - 20L
    val handshakeLatency = (
        latencyBase +
            context.approximateRttBucket.toLong() * 18L +
            rank.toLong() * 125L +
            phase.toLong() * 9L +
            jitter +
            if (success) 0L else 650L
        ).coerceAtLeast(20L)
    val retryUnit = aegisUnit(aegisMix64(sampleSeed xor -4942790177534073029L))
    val retries = if (success) {
        if (retryUnit < 0.10) 1 else 0
    } else {
        1 + if (retryUnit < 0.18) 1 else 0
    }
    val utility = if (success) {
        0.70 + (1.0 - handshakeLatency.toDouble() / 2_000.0).coerceIn(0.0, 1.0) * 0.30 - retries * 0.015
    } else {
        -0.92 - (handshakeLatency.toDouble() / 40_000.0).coerceIn(0.0, 0.08)
    }
    return AegisSyntheticOutcome(
        success = success,
        handshakeLatencyMs = handshakeLatency,
        retries = retries,
        timedOut = !success,
        utility = utility
    )
}

private fun aegisPreferredOrder(profile: AegisWorldProfile, phase: Int): List<TurboStrategyId> {
    val phaseOrders = listOf(
        listOf(
            TurboStrategyId.TLS_SNI_MULTI_V1,
            TurboStrategyId.TLS_HOSTNAME_V1,
            TurboStrategyId.TLS_SINGLE_SAFE_V1,
            TurboStrategyId.TLS_PLAIN_V1
        ),
        listOf(
            TurboStrategyId.TLS_HOSTNAME_V1,
            TurboStrategyId.TLS_SINGLE_SAFE_V1,
            TurboStrategyId.TLS_PLAIN_V1,
            TurboStrategyId.TLS_SNI_MULTI_V1
        ),
        listOf(
            TurboStrategyId.TLS_PLAIN_V1,
            TurboStrategyId.TLS_SINGLE_SAFE_V1,
            TurboStrategyId.TLS_HOSTNAME_V1,
            TurboStrategyId.TLS_SNI_MULTI_V1
        ),
        listOf(
            TurboStrategyId.TLS_SINGLE_SAFE_V1,
            TurboStrategyId.TLS_SNI_MULTI_V1,
            TurboStrategyId.TLS_HOSTNAME_V1,
            TurboStrategyId.TLS_PLAIN_V1
        )
    )
    val offset = when (profile) {
        AegisWorldProfile.WIFI -> 0
        AegisWorldProfile.CELLULAR -> 1
        AegisWorldProfile.ETHERNET -> 2
        AegisWorldProfile.ROAMING -> 3
        AegisWorldProfile.NO_SNI -> 0
    }
    return phaseOrders[(phase + offset) % phaseOrders.size]
}

private fun runAegisPolicy(
    runner: AegisPolicyRunner,
    samples: List<AegisSyntheticSample>,
    warmupIterations: Int
): AegisPolicyRun {
    repeat(warmupIterations) {
        val sample = samples[it % samples.size]
        runner.select(AegisDecisionInput(sample.context, sample.destinationKey))
    }
    val decisions = ArrayList<AegisRawDecision>(samples.size)
    samples.forEach { sample ->
        val input = AegisDecisionInput(sample.context, sample.destinationKey)
        val choice = runner.select(input)
        check(choice.strategy in sample.allowed)
        check(choice.fallbackOrder.all { it in sample.allowed && it != choice.strategy })
        val outcome = sample.outcomes.getValue(choice.strategy)
        val regret = (sample.hindsightUtility - outcome.utility).coerceAtLeast(0.0)
        decisions += AegisRawDecision(
            method = runner.method,
            sample = sample,
            choice = choice,
            outcome = outcome,
            chosenUtility = outcome.utility,
            regret = regret
        )
        runner.observe(input, choice, outcome)
    }
    return AegisPolicyRun(
        method = runner.method,
        decisions = decisions,
        metrics = buildAegisPolicyMetrics(runner.method, decisions)
    )
}

private fun buildAegisPolicyMetrics(method: String, decisions: List<AegisRawDecision>): AegisPolicyMetrics {
    val training = decisions.filter { it.sample.split == "train" }
    val evaluation = decisions.filter { it.sample.split == "eval" }
    val heldOut = evaluation.filter { it.sample.context.heldOut }
    return AegisPolicyMetrics(
        method = method,
        training = aggregateAegis(training),
        evaluation = aggregateAegis(evaluation),
        heldOutEvaluation = aggregateAegis(heldOut),
        evaluationByPhase = evaluation.groupBy { it.sample.phase }.toSortedMap().mapValues { aggregateAegis(it.value) },
        policyCounts = decisions.groupingBy { it.choice.policy }.eachCount().toSortedMap(),
        confidenceCounts = decisions.groupingBy { it.choice.confidence }.eachCount().toSortedMap()
    )
}

private fun aggregateAegis(decisions: List<AegisRawDecision>): AegisAggregate {
    val overheads = decisions.map { it.choice.measuredOverheadNanos }.sorted()
    return AegisAggregate(
        samples = decisions.size,
        failures = decisions.count { !it.outcome.success },
        regretTotal = decisions.sumOf { it.regret },
        oracleHits = decisions.count { it.choice.strategy == it.sample.hindsightStrategy },
        overheadP50Nanos = aegisPercentile(overheads, 0.50),
        overheadP95Nanos = aegisPercentile(overheads, 0.95),
        overheadP99Nanos = aegisPercentile(overheads, 0.99)
    )
}

private fun aegisPercentile(values: List<Long>, percentile: Double): Long {
    if (values.isEmpty()) return 0L
    val index = ((values.size - 1).toDouble() * percentile).toInt().coerceIn(0, values.lastIndex)
    return values[index]
}

private fun buildAegisEvaluation(config: AegisConfig): AegisEvaluation {
    val configJson = config.canonicalJson()
    val configHash = aegisSha256(configJson)
    val samples = buildAegisTrace(config)
    check(samples.count { it.split == "train" } == config.trainingSamples)
    check(samples.count { it.split == "eval" } == config.evaluationSamples)
    val runs = listOf(
        runAegisPolicy(AegisFixedPolicy(), samples, config.warmupIterations),
        runAegisPolicy(AegisEmaPolicy(config), samples, config.warmupIterations),
        runAegisPolicy(AegisTurboPolicy(config), samples, config.warmupIterations)
    )
    val heuristic = runs.first { it.method == "B_DETERMINISTIC_EMA_HEURISTIC" }.metrics.evaluation
    val ai = runs.first { it.method == "C_EXISTING_TURBO_AI_ENGINE" }.metrics.evaluation
    val regretImprovement = heuristic.meanRegret - ai.meanRegret
    val failureImprovement = heuristic.failureRate - ai.failureRate
    val overheadAcceptable = ai.overheadP95Nanos <= maxOf(
        heuristic.overheadP95Nanos * 2L,
        heuristic.overheadP95Nanos + 100_000L
    )
    val keepsAi = regretImprovement >= 0.02 && failureImprovement >= -0.01 && overheadAcceptable
    val verdict = if (keepsAi) "KEEP_C" else "REWORK_C"
    val finding = if (keepsAi) {
        "C materially improved synthetic hindsight regret or failure rate against B within the bounded host-overhead gate; this is lab evidence only and makes no real-network claim."
    } else {
        "C did not materially beat B on the seeded synthetic evaluation under the bounded host-overhead gate; retain B as the deterministic reference and reject or rework the AI policy before any production recommendation."
    }
    return AegisEvaluation(config, configJson, configHash, samples, runs, verdict, finding)
}

private fun aegisContextsForSafety(): List<Pair<String, TurboContext>> {
    val base = aegisContexts().first()
    return listOf(
        "valid-sni" to base.turbo("00000000000000000000000000000001"),
        "missing-sni" to base.copy(sniPresent = false).turbo("00000000000000000000000000000002"),
        "incomplete-clienthello" to base.copy(clientHelloParsed = false).turbo("00000000000000000000000000000003"),
        "non-https-port" to base.copy(destinationPort = 8443).turbo("00000000000000000000000000000004")
    )
}

private fun aegisCoverageGapsJson(configHash: String, modelHash: String, sourceHash: String, runId: String): String {
    val covered = listOf(
        "TurboAiEngineTest.unsafeFragmentationStrategiesAreExcluded",
        "TurboAiEngineTest.hostnameStrategiesAreExcludedWithoutSni",
        "TurboAiEngineTest.runtimeSuppressionRestrictsAiToTheActualBaseline",
        "TurboModelCodecTest.corruptedStateRecoversToSafeEmptyModel",
        "TurboModelCodecTest.previousSchemaAndNonFiniteStatisticsAreRejected",
        "TurboModelCodecTest.oversizedCorruptedStateIsRejectedBeforeParsing",
        "AegisLocalEvaluationTest.adversarialSafetyInputsRemainBounded"
    )
    val gaps = listOf(
        "external-high-confidence-model-output-injection",
        "inference-timeout-at-an-injectable-model-boundary",
        "explicit-nan-or-infinite-model-output-sanitization",
        "out-of-distribution-feature-vector-policy"
    )
    return buildString {
        append('{')
        append("\"artifactKind\":\"aegis-adversarial-coverage\",")
        append("\"outcomeLabel\":\"synthetic-known-safe-lab\",")
        append("\"runId\":").append(aegisQuote(runId)).append(',')
        append("\"configHash\":").append(aegisQuote(configHash)).append(',')
        append("\"modelHash\":").append(aegisQuote(modelHash)).append(',')
        append("\"sourceHash\":").append(aegisQuote(sourceHash)).append(',')
        append("\"coveredByExistingOrAegisTests\":[")
        append(covered.joinToString(",") { aegisQuote(it) })
        append("],\"coverageGapsForParentOrSol\":[")
        append(gaps.joinToString(",") { aegisQuote(it) })
        append("],\"action\":")
        append(aegisQuote("Parent/Sol should add boundary-level injection tests only if a model-output boundary is introduced; this R0 evaluation does not redesign production AI."))
        append('}')
    }
}

private fun aegisJsonAggregate(value: AegisAggregate): String {
    return buildString {
        append('{')
        append("\"samples\":").append(value.samples).append(',')
        append("\"failures\":").append(value.failures).append(',')
        append("\"failureRate\":").append(value.failureRate).append(',')
        append("\"meanRegret\":").append(value.meanRegret).append(',')
        append("\"regretTotal\":").append(value.regretTotal).append(',')
        append("\"hindsightHits\":").append(value.oracleHits).append(',')
        append("\"hindsightHitRate\":").append(value.hindsightHitRate).append(',')
        append("\"decisionOverheadP50Nanos\":").append(value.overheadP50Nanos).append(',')
        append("\"decisionOverheadP95Nanos\":").append(value.overheadP95Nanos).append(',')
        append("\"decisionOverheadP99Nanos\":").append(value.overheadP99Nanos)
        append('}')
    }
}

private fun aegisJsonPolicyMetrics(value: AegisPolicyMetrics): String {
    return buildString {
        append('{')
        append("\"method\":").append(aegisQuote(value.method)).append(',')
        append("\"training\":").append(aegisJsonAggregate(value.training)).append(',')
        append("\"evaluation\":").append(aegisJsonAggregate(value.evaluation)).append(',')
        append("\"heldOutEvaluation\":").append(aegisJsonAggregate(value.heldOutEvaluation)).append(',')
        append("\"evaluationByPhase\":[")
        append(value.evaluationByPhase.entries.joinToString(",") { (phase, aggregate) ->
            "{\"phase\":$phase,\"aggregate\":${aegisJsonAggregate(aggregate)}}"
        })
        append("],\"policyCounts\":")
        append(aegisJsonIntMap(value.policyCounts))
        append(",\"confidenceCounts\":")
        append(aegisJsonIntMap(value.confidenceCounts))
        append('}')
    }
}

private fun aegisJsonIntMap(values: Map<String, Int>): String {
    return values.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
        aegisQuote(it.key) + ":" + it.value
    }
}

private fun aegisJsonSample(sample: AegisSyntheticSample, configHash: String, modelHash: String, sourceHash: String): String {
    return buildString {
        append('{')
        append("\"artifactKind\":\"synthetic-sample\",")
        append("\"outcomeLabel\":\"synthetic-known-safe-outcome\",")
        append("\"configHash\":").append(aegisQuote(configHash)).append(',')
        append("\"modelHash\":").append(aegisQuote(modelHash)).append(',')
        append("\"sourceHash\":").append(aegisQuote(sourceHash)).append(',')
        append("\"sampleId\":").append(aegisQuote(sample.sampleId)).append(',')
        append("\"split\":").append(aegisQuote(sample.split)).append(',')
        append("\"index\":").append(sample.index).append(',')
        append("\"phase\":").append(sample.phase).append(',')
        append("\"coarseContext\":").append(aegisQuote(sample.context.id)).append(',')
        append("\"heldOutCoarseContext\":").append(sample.context.heldOut).append(',')
        append("\"syntheticOpaqueDestinationKey\":").append(aegisQuote(sample.destinationKey)).append(',')
        append("\"allowedStrategies\":")
        append(sample.allowed.joinToString(prefix = "[", postfix = "]", separator = ",") { aegisQuote(it.stableId) })
        append(",\"hindsightStrategy\":").append(aegisQuote(sample.hindsightStrategy.stableId))
        append(",\"hindsightUtility\":").append(sample.hindsightUtility)
        append(",\"outcomes\":[")
        append(sample.outcomes.entries.joinToString(",") { (strategy, outcome) ->
            buildString {
                append('{')
                append("\"strategy\":").append(aegisQuote(strategy.stableId)).append(',')
                append("\"success\":").append(outcome.success).append(',')
                append("\"handshakeLatencyMs\":").append(outcome.handshakeLatencyMs).append(',')
                append("\"retries\":").append(outcome.retries).append(',')
                append("\"timedOut\":").append(outcome.timedOut).append(',')
                append("\"utility\":").append(outcome.utility)
                append('}')
            }
        })
        append("]}")
    }
}

private fun aegisJsonDecision(
    value: AegisRawDecision,
    configHash: String,
    modelHash: String,
    sourceHash: String
): String {
    val sample = value.sample
    val choice = value.choice
    return buildString {
        append('{')
        append("\"artifactKind\":\"synthetic-decision\",")
        append("\"outcomeLabel\":\"synthetic-known-safe-outcome\",")
        append("\"configHash\":").append(aegisQuote(configHash)).append(',')
        append("\"modelHash\":").append(aegisQuote(modelHash)).append(',')
        append("\"sourceHash\":").append(aegisQuote(sourceHash)).append(',')
        append("\"method\":").append(aegisQuote(value.method)).append(',')
        append("\"sampleId\":").append(aegisQuote(sample.sampleId)).append(',')
        append("\"split\":").append(aegisQuote(sample.split)).append(',')
        append("\"phase\":").append(sample.phase).append(',')
        append("\"coarseContext\":").append(aegisQuote(sample.context.id)).append(',')
        append("\"heldOutCoarseContext\":").append(sample.context.heldOut).append(',')
        append("\"allowedStrategies\":")
        append(sample.allowed.joinToString(prefix = "[", postfix = "]", separator = ",") { aegisQuote(it.stableId) })
        append(",\"selectedStrategy\":").append(aegisQuote(choice.strategy.stableId)).append(',')
        append("\"hindsightStrategy\":").append(aegisQuote(sample.hindsightStrategy.stableId)).append(',')
        append("\"policy\":").append(aegisQuote(choice.policy)).append(',')
        append("\"confidence\":").append(aegisQuote(choice.confidence)).append(',')
        append("\"reasonCode\":").append(aegisQuote(choice.reasonCode)).append(',')
        append("\"fallbackOrder\":")
        append(choice.fallbackOrder.joinToString(prefix = "[", postfix = "]", separator = ",") { aegisQuote(it.stableId) })
        append(",\"success\":").append(value.outcome.success).append(',')
        append("\"handshakeLatencyMs\":").append(value.outcome.handshakeLatencyMs).append(',')
        append("\"retries\":").append(value.outcome.retries).append(',')
        append("\"chosenUtility\":").append(value.chosenUtility).append(',')
        append("\"hindsightUtility\":").append(sample.hindsightUtility).append(',')
        append("\"regret\":").append(value.regret).append(',')
        append("\"decisionOverheadNanos\":").append(choice.measuredOverheadNanos).append(',')
        append("\"engineReportedOverheadNanos\":")
        if (choice.engineReportedOverheadNanos == null) append("null") else append(choice.engineReportedOverheadNanos)
        append('}')
    }
}

private fun aegisQuote(value: String): String {
    return buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}

private fun aegisWrite(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
}

private fun aegisRunId(): String {
    val supplied = System.getProperty("aegis.runId")?.takeIf { it.isNotBlank() }
        ?: System.getenv("AEGIS_RUN_ID")?.takeIf { it.isNotBlank() }
    val raw = supplied ?: "aegis-standalone-${System.currentTimeMillis()}-${ProcessHandle.current().pid()}"
    return raw.map { character ->
        if (character.isLetterOrDigit() || character == '.' || character == '_' || character == '-') character else '_'
    }.joinToString("")
}

private fun aegisArtifactPath(): Path {
    val configured = System.getProperty("aegis.artifactDir")?.takeIf { it.isNotBlank() }
        ?: System.getenv("AEGIS_ARTIFACT_DIR")?.takeIf { it.isNotBlank() }
    val path = configured?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.dir"), "benchmark-results", "aegis", aegisRunId())
    return path.toAbsolutePath().normalize()
}

private fun aegisProperty(name: String, environmentName: String): String {
    return System.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: "not-provided"
}

private fun aegisSha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

private fun aegisHashFiles(candidates: List<Path>): String {
    val files = candidates.filter { Files.isRegularFile(it) }
    if (files.isEmpty()) return "not-provided"
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.toString() }.forEach { file ->
        digest.update(file.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(Files.readAllBytes(file))
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

private fun aegisProjectCandidates(relativePath: String): List<Path> {
    val workingDirectory = Paths.get(System.getProperty("user.dir"))
    return listOf(
        workingDirectory.resolve(relativePath),
        workingDirectory.resolve("..").normalize().resolve(relativePath)
    )
}

private fun aegisSourceHash(): String {
    val supplied = aegisProperty("aegis.sourceHash", "AEGIS_SOURCE_HASH")
    return if (supplied != "not-provided") supplied else aegisHashFiles(
        aegisProjectCandidates("app/src/test/java/com/tunnelvpn/app/AegisLocalEvaluationTest.kt") +
            aegisProjectCandidates("tools/aegis-evaluate.sh")
    )
}

private fun aegisModelHash(): String {
    val supplied = aegisProperty("aegis.modelHash", "AEGIS_MODEL_HASH")
    return if (supplied != "not-provided") supplied else aegisHashFiles(
        aegisProjectCandidates("app/src/main/java/com/tunnelvpn/app/TurboAiEngine.kt") +
            aegisProjectCandidates("app/src/main/java/com/tunnelvpn/app/TurboAiModels.kt") +
            aegisProjectCandidates("app/src/main/java/com/tunnelvpn/app/TurboModelStore.kt")
    )
}

private fun aegisStableHash64(value: String): Long {
    var hash = -3750763034362895579L
    value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        hash = (hash xor (byte.toLong() and 0xFFL)) * 1099511628211L
    }
    return aegisMix64(hash)
}

private fun aegisMix64(value: Long): Long {
    var mixed = value
    mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
    mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
    return mixed xor (mixed ushr 31)
}

private fun aegisUnit(value: Long): Double {
    return (value ushr 11).toDouble() / 9_007_199_254_740_992.0
}

private fun aegisPositiveMod(value: Long, bound: Long): Long {
    return (value ushr 1) % bound
}

private fun aegisOpaqueKey(seed: Long, contextId: String, index: Int, slot: Int): String {
    val first = aegisMix64(seed xor aegisStableHash64(contextId) xor index.toLong() * 31L xor slot.toLong())
    val second = aegisMix64(first xor -7046029254386353131L)
    return java.lang.Long.toUnsignedString(first, 16).padStart(16, '0') +
        java.lang.Long.toUnsignedString(second, 16).padStart(16, '0')
}

private fun aegisWarmupContext(): TurboContext {
    return AegisCoarseContext(
        id = "warmup",
        transport = TurboNetworkTransport.WIFI,
        metered = false,
        roaming = false,
        validated = true,
        batterySaver = false,
        clientHelloParsed = true,
        sniPresent = true,
        destinationPort = 443,
        latencyProfile = "DEFAULT",
        approximateRttBucket = 1,
        resourcePressureBucket = 0,
        profile = AegisWorldProfile.WIFI,
        heldOut = false
    ).turbo("00000000000000000000000000000010")
}

private fun aegisWriteEvaluation(evaluation: AegisEvaluation) {
    val artifactDir = aegisArtifactPath()
    val modelHash = aegisModelHash()
    val sourceHash = aegisSourceHash()
    val runId = aegisRunId()
    Files.createDirectories(artifactDir)
    aegisWrite(artifactDir.resolve("config.json"), evaluation.configJson + "\n")
    val metadata = buildString {
        append('{')
        append("\"artifactKind\":\"aegis-metadata\",")
        append("\"measurementDomain\":\"host-local-jvm-synthetic\",")
        append("\"outcomeLabel\":\"synthetic-known-safe-outcome\",")
        append("\"realNetworkClaims\":false,")
        append("\"rawBrowsingDataStored\":false,")
        append("\"networkEvidence\":\"none\",")
        append("\"deviceEvidence\":\"none\",")
        append("\"runId\":").append(aegisQuote(runId)).append(',')
        append("\"configHash\":").append(aegisQuote(evaluation.configHash)).append(',')
        append("\"modelHash\":").append(aegisQuote(modelHash)).append(',')
        append("\"sourceHash\":").append(aegisQuote(sourceHash)).append(',')
        append("\"modelUnderTest\":\"existing-TurboAiEngine\",")
        append("\"mlRuntime\":\"none\",")
        append("\"warmupIterations\":").append(evaluation.config.warmupIterations).append(',')
        append("\"trainingChangepoints\":")
        append(evaluation.config.trainingChangepoints.joinToString(prefix = "[", postfix = "]", separator = ","))
        append(",\"evaluationChangepoints\":")
        append(evaluation.config.evaluationChangepoints.joinToString(prefix = "[", postfix = "]", separator = ","))
        append(",\"heldOutCoarseContexts\":")
        append(aegisContexts().filter { it.heldOut }.joinToString(prefix = "[", postfix = "]", separator = ",") { aegisQuote(it.id) })
        append(",\"files\":[\"config.json\",\"metadata.json\",\"raw-samples.jsonl\",\"raw-decisions.jsonl\",\"summary.json\",\"summary.md\",\"coverage-gaps.json\"]")
        append('}')
    }
    aegisWrite(artifactDir.resolve("metadata.json"), metadata + "\n")
    val sampleLines = evaluation.samples.joinToString("\n") {
        aegisJsonSample(it, evaluation.configHash, modelHash, sourceHash)
    }
    aegisWrite(artifactDir.resolve("raw-samples.jsonl"), if (sampleLines.isEmpty()) "" else sampleLines + "\n")
    val decisionLines = evaluation.runs.flatMap { it.decisions }.joinToString("\n") {
        aegisJsonDecision(it, evaluation.configHash, modelHash, sourceHash)
    }
    aegisWrite(artifactDir.resolve("raw-decisions.jsonl"), if (decisionLines.isEmpty()) "" else decisionLines + "\n")
    val summaryJson = buildString {
        append('{')
        append("\"artifactKind\":\"aegis-summary\",")
        append("\"outcomeLabel\":\"synthetic-known-safe-outcome\",")
        append("\"realNetworkClaims\":false,")
        append("\"runId\":").append(aegisQuote(runId)).append(',')
        append("\"configHash\":").append(aegisQuote(evaluation.configHash)).append(',')
        append("\"modelHash\":").append(aegisQuote(modelHash)).append(',')
        append("\"sourceHash\":").append(aegisQuote(sourceHash)).append(',')
        append("\"verdict\":").append(aegisQuote(evaluation.verdict)).append(',')
        append("\"finding\":").append(aegisQuote(evaluation.finding)).append(',')
        append("\"overheadUnit\":\"nanoseconds measured with host System.nanoTime\",")
        append("\"policies\":[")
        append(evaluation.runs.joinToString(",") { aegisJsonPolicyMetrics(it.metrics) })
        append("]}")
    }
    aegisWrite(artifactDir.resolve("summary.json"), summaryJson + "\n")
    val heuristic = evaluation.runs.first { it.method == "B_DETERMINISTIC_EMA_HEURISTIC" }.metrics.evaluation
    val ai = evaluation.runs.first { it.method == "C_EXISTING_TURBO_AI_ENGINE" }.metrics.evaluation
    val summaryMarkdown = buildString {
        append("# AegisLocal synthetic evaluation\n\n")
        append("Outcome label: synthetic known-safe lab outcomes.\n\n")
        append("Real-network claims: none. Device/emulator evidence: none.\n\n")
        append("Decision overhead is host-JVM `System.nanoTime()` timing around each policy decision; it is not network latency.\n\n")
        append("| Policy | Eval samples | Failure rate | Mean regret | Overhead p50 us | Overhead p95 us | Overhead p99 us |\n")
        append("|---|---:|---:|---:|---:|---:|---:|\n")
        evaluation.runs.forEach { run ->
            val metrics = run.metrics.evaluation
            append('|').append(run.method).append('|').append(metrics.samples)
            append('|').append(metrics.failureRate).append('|').append(metrics.meanRegret)
            append('|').append(metrics.overheadP50Nanos / 1_000.0)
            append('|').append(metrics.overheadP95Nanos / 1_000.0)
            append('|').append(metrics.overheadP99Nanos / 1_000.0).append("|\n")
        }
        append("\nVerdict: `").append(evaluation.verdict).append("`. ")
        append(evaluation.finding).append("\n\n")
        append("B versus C regret delta (B mean minus C mean): ")
        append(heuristic.meanRegret - ai.meanRegret).append(".\n")
        append("B versus C failure-rate delta (B rate minus C rate): ")
        append(heuristic.failureRate - ai.failureRate).append(".\n")
    }
    aegisWrite(artifactDir.resolve("summary.md"), summaryMarkdown)
    aegisWrite(
        artifactDir.resolve("coverage-gaps.json"),
        aegisCoverageGapsJson(evaluation.configHash, modelHash, sourceHash, runId) + "\n"
    )
}

private fun aegisWriteCoverageGaps() {
    val artifactDir = aegisArtifactPath()
    val config = AegisConfig()
    aegisWrite(
        artifactDir.resolve("coverage-gaps.json"),
        aegisCoverageGapsJson(
            configHash = aegisSha256(config.canonicalJson()),
            modelHash = aegisModelHash(),
            sourceHash = aegisSourceHash(),
            runId = aegisRunId()
        ) + "\n"
    )
}

class AegisLocalEvaluationTest {
    @Test
    fun syntheticEvaluationComparesCurrentOrderEmaHeuristicAndExistingTurboAi() {
        val config = AegisConfig()
        val trace = buildAegisTrace(config)
        assertEquals(config.trainingSamples + config.evaluationSamples, trace.size)
        assertEquals(trace, buildAegisTrace(config))
        assertTrue(trace.any { it.context.heldOut })
        assertTrue(trace.any { it.phase > 0 })
        trace.forEach { sample ->
            assertTrue(sample.allowed.isNotEmpty())
            assertTrue(sample.outcomes.keys == sample.allowed.toSet())
            assertTrue(sample.hindsightStrategy in sample.allowed)
        }
        val evaluation = buildAegisEvaluation(config)
        assertEquals(3, evaluation.runs.size)
        evaluation.runs.forEach { run ->
            assertEquals(trace.size, run.decisions.size)
            assertTrue(run.decisions.all { it.choice.strategy in it.sample.allowed })
            assertTrue(run.decisions.all { it.choice.measuredOverheadNanos >= 0L })
            assertEquals(config.evaluationSamples, run.metrics.evaluation.samples)
        }
        aegisWriteEvaluation(evaluation)
        println(
            "AEGIS synthetic_known_safe_lab verdict=${evaluation.verdict} " +
                "B_mean_regret=${evaluation.runs[1].metrics.evaluation.meanRegret} " +
                "C_mean_regret=${evaluation.runs[2].metrics.evaluation.meanRegret} " +
                "B_failure_rate=${evaluation.runs[1].metrics.evaluation.failureRate} " +
                "C_failure_rate=${evaluation.runs[2].metrics.evaluation.failureRate} " +
                "artifactDir=${aegisArtifactPath()}"
        )
    }

    @Test
    fun adversarialSafetyInputsRemainBounded() {
        val engine = TurboAiEngine(
            secret = ByteArray(32) { 0x37 },
            flags = TurboAiFlags(enabled = true, explorationEnabled = true),
            clock = AegisMutableClock(),
            random = AegisSeededTurboRandom(0x41444745534953L)
        )
        aegisContextsForSafety().forEach { (_, context) ->
            val eligible = safeEligible(context)
            val decision = engine.select(context, AEGIS_BASELINE_ORDER)
            assertTrue(decision.strategy in eligible)
            assertTrue(decision.fallbackOrder.all { it in eligible })
            assertEquals(eligible.map { it.stableId }, decision.reason.eligibleStrategyIds)
        }
        aegisWriteCoverageGaps()
    }

    @Test
    fun corruptedExistingModelStateIsRejectedForEvaluation() {
        val valid = TurboModelCodec.encode(
            TurboPersistedState(
                totalSamples = 1,
                entries = listOf(
                    TurboModelEntry(
                        contextKey = "synthetic",
                        strategy = TurboStrategyId.TLS_PLAIN_V1,
                        weightedCount = 1.0,
                        meanReward = 0.2,
                        weightedSuccesses = 1.0,
                        latencyEmaMs = 100.0,
                        lastUpdatedMs = 1L,
                        lastAccessMs = 1L
                    )
                )
            )
        )
        val corruptedInputs = listOf(
            "not-a-model",
            "TURBO_AI_MODEL|1\nS|0",
            "TURBO_AI_MODEL|2\nS|1\nE|73616665|tls-plain-v1|NaN|0.5|1.0|100.0|1|1",
            "TURBO_AI_MODEL|2\n" + "x".repeat(1_048_576),
            valid + "\nE|broken"
        )
        corruptedInputs.forEach { input ->
            assertTrue(TurboModelCodec.decode(input).corrupted)
        }
        assertFalse(TurboModelCodec.decode(valid).corrupted)
        aegisWriteCoverageGaps()
    }
}
