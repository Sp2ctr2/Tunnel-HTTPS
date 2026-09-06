package com.tunnelvpn.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val V4_BASELINE_ORDER = listOf(
    TurboStrategyId.TLS_SNI_MULTI_V1,
    TurboStrategyId.TLS_HOSTNAME_V1,
    TurboStrategyId.TLS_SINGLE_SAFE_V1,
    TurboStrategyId.TLS_PLAIN_V1
)

private enum class V4Scenario {
    COLD_START,
    WARM_RECURRING,
    DESTINATION_REVISIT,
    CHANGING_QUALITY,
    MISLEADING_TRANSIENT,
    STABLE_NO_ADVANTAGE
}

private enum class V4Split {
    TRAIN,
    EVAL
}

private data class V4Config(
    val trainSeed: Long = 0x56345F545241494EL,
    val evaluationSeed: Long = 0x56345F4556414C4CL,
    val trainingSamples: Int = 144,
    val evaluationSamples: Int = 144,
    val qualityChangePoints: List<Int> = listOf(48, 96),
    val emaAlpha: Double = 0.25,
    val minimumArmObservations: Int = 2
) {
    fun canonicalJson(): String {
        return buildString {
            append('{')
            append("\"schema\":\"aegis-local2-evaluation-v1\",")
            append("\"trainSeed\":").append(trainSeed).append(',')
            append("\"evaluationSeed\":").append(evaluationSeed).append(',')
            append("\"trainingSamples\":").append(trainingSamples).append(',')
            append("\"evaluationSamples\":").append(evaluationSamples).append(',')
            append("\"qualityChangePoints\":")
            append(qualityChangePoints.joinToString(prefix = "[", postfix = "]", separator = ","))
            append(',')
            append("\"emaAlpha\":").append(v4Number(emaAlpha)).append(',')
            append("\"minimumArmObservations\":").append(minimumArmObservations)
            append('}')
        }
    }
}

data class AegisLocal2EvaluationRequest(
    val destinationKey: String,
    val contextFamilyKey: String,
    val destinationPort: Int,
    val transport: String,
    val metered: Boolean,
    val roaming: Boolean,
    val validated: Boolean,
    val batterySaver: Boolean,
    val clientHelloParsed: Boolean,
    val sniPresent: Boolean,
    val latencyProfile: String,
    val approximateRttBucket: Int,
    val recentRetryBucket: Int,
    val recentSuccessBucket: Int,
    val recentHandshakeBucket: Int,
    val resourcePressureBucket: Int,
    val previousFailure: String,
    val allowedStrategyIds: List<String>
) {
    fun toTurboContext(): TurboContext {
        return TurboContext(
            destinationKey = destinationKey,
            destinationPort = destinationPort,
            transport = TurboNetworkTransport.valueOf(transport),
            metered = metered,
            roaming = roaming,
            validated = validated,
            batterySaver = batterySaver,
            clientHelloParsed = clientHelloParsed,
            sniPresent = sniPresent,
            latencyProfile = latencyProfile,
            approximateRttBucket = approximateRttBucket,
            recentRetryBucket = recentRetryBucket,
            recentSuccessBucket = recentSuccessBucket,
            recentHandshakeBucket = recentHandshakeBucket,
            resourcePressureBucket = resourcePressureBucket,
            previousFailure = TurboFailureCategory.valueOf(previousFailure)
        )
    }
}

data class AegisLocal2EvaluationObservation(
    val strategyId: String,
    val success: Boolean,
    val latencyMs: Long,
    val retries: Int,
    val timedOut: Boolean,
    val fallbackUsed: Boolean = false,
    val aegisToken: Long = 0L,
    val epoch: Long = 1L
)

data class AegisLocal2EvaluationDecision(
    val strategyId: String,
    val fallbackStrategyIds: List<String> = emptyList(),
    val policy: String = "D_AEGIS_LOCAL2",
    val confidence: String = "LOW",
    val reasonCode: String = "provider",
    val reportedInferenceNanos: Long? = null,
    val aegisToken: Long = 0L,
    val epoch: Long = 1L,
    val aegisMode: String? = null,
    val aegisKillSwitchTrips: Long? = null,
    val aegisTrainedObservations: Long? = null
)

interface AegisLocal2EvaluationAdapter {
    val status: String

    fun select(request: AegisLocal2EvaluationRequest): AegisLocal2EvaluationDecision

    fun observe(
        request: AegisLocal2EvaluationRequest,
        outcome: AegisLocal2EvaluationObservation
    )

    fun reset() = Unit
}

private data class V4Input(
    val sampleId: String,
    val split: V4Split,
    val index: Int,
    val destinationKey: String,
    val contextFamilyKey: String,
    val turboContext: TurboContext,
    val allowed: List<TurboStrategyId>
) {
    fun dRequest(): AegisLocal2EvaluationRequest {
        return AegisLocal2EvaluationRequest(
            destinationKey = destinationKey,
            contextFamilyKey = contextFamilyKey,
            destinationPort = turboContext.destinationPort,
            transport = turboContext.transport.name,
            metered = turboContext.metered,
            roaming = turboContext.roaming,
            validated = turboContext.validated,
            batterySaver = turboContext.batterySaver,
            clientHelloParsed = turboContext.clientHelloParsed,
            sniPresent = turboContext.sniPresent,
            latencyProfile = turboContext.latencyProfile,
            approximateRttBucket = turboContext.approximateRttBucket,
            recentRetryBucket = turboContext.recentRetryBucket,
            recentSuccessBucket = turboContext.recentSuccessBucket,
            recentHandshakeBucket = turboContext.recentHandshakeBucket,
            resourcePressureBucket = turboContext.resourcePressureBucket,
            previousFailure = turboContext.previousFailure.name,
            allowedStrategyIds = allowed.map { it.stableId }
        )
    }
}

private data class V4WorldOutcome(
    val success: Boolean,
    val latencyMs: Long,
    val retries: Int,
    val timedOut: Boolean
)

private data class V4Sample(
    val sampleId: String,
    val split: V4Split,
    val index: Int,
    val scenario: V4Scenario,
    val contextId: String,
    val destinationKey: String,
    val destinationRevisitGroup: String,
    val phase: Int,
    val warmStart: Boolean,
    val input: V4Input,
    val outcomes: Map<TurboStrategyId, V4WorldOutcome>,
    val bestStrategies: Set<TurboStrategyId>
)

private data class V4Choice(
    val strategy: TurboStrategyId,
    val fallbackOrder: List<TurboStrategyId>,
    val policy: String,
    val confidence: String,
    val reasonCode: String,
    val measuredInferenceNanos: Long,
    val reportedInferenceNanos: Long? = null,
    val invalidProviderOutput: Boolean = false,
    val engineDecision: TurboDecision? = null,
    val aegisToken: Long = 0L,
    val aegisEpoch: Long = 1L,
    val aegisMode: String? = null,
    val aegisKillSwitchTrips: Long? = null,
    val aegisTrainedObservations: Long? = null
)

private data class V4ObservedOutcome(
    val strategy: TurboStrategyId,
    val success: Boolean,
    val latencyMs: Long,
    val retries: Int,
    val timedOut: Boolean,
    val fallbackUsed: Boolean = false
)

private data class V4Execution(
    val initial: V4ObservedOutcome,
    val attempts: List<V4ObservedOutcome>,
    val servedSuccess: Boolean,
    val servedLatencyMs: Long,
    val fallbackUsed: Boolean,
    val fallbackRecovered: Boolean,
    val fallbackSteps: Int
)

private data class V4RunDecision(
    val sample: V4Sample,
    val choice: V4Choice,
    val execution: V4Execution,
    val wrongChoice: Boolean,
    val regretMs: Long,
    val bestCostMs: Long,
    val chosenCostMs: Long
)

private data class V4Metrics(
    val samples: Int,
    val initialSuccesses: Int,
    val servedSuccesses: Int,
    val wrongChoices: Int,
    val fallbackAttempts: Int,
    val fallbackRecoveries: Int,
    val invalidProviderOutputs: Int,
    val feedbackObservations: Int,
    val initialSuccessRate: Double,
    val servedSuccessRate: Double,
    val servedLatencyP95Ms: Long,
    val successfulLatencyP95Ms: Long,
    val wrongChoiceRate: Double,
    val fallbackRate: Double,
    val fallbackRecoveryRate: Double,
    val meanRegretMs: Double,
    val inferenceP50Nanos: Long,
    val inferenceP95Nanos: Long,
    val inferenceMeanNanos: Double,
    val reportedInferenceP95Nanos: Long?
)

private data class V4PolicyRun(
    val id: String,
    val status: String,
    val decisions: List<V4RunDecision>,
    val metrics: Map<String, V4Metrics>
)

private data class V4Evaluation(
    val config: V4Config,
    val configJson: String,
    val configHash: String,
    val trace: List<V4Sample>,
    val runs: List<V4PolicyRun>,
    val dStatus: String,
    val dFinalMode: String? = null,
    val dKillSwitchTrips: Long? = null,
    val dTrainedObservations: Long? = null,
    val promotionVerdict: String,
    val promotionReason: String
)

private interface V4PolicyRunner {
    val id: String
    val status: String
    val feedbackObservations: Int

    fun reset()

    fun select(input: V4Input): V4Choice

    fun observe(input: V4Input, choice: V4Choice, outcome: V4ObservedOutcome)
}

private fun v4FallbackOrder(selected: TurboStrategyId, allowed: List<TurboStrategyId>): List<TurboStrategyId> {
    return V4_BASELINE_ORDER.filter { it in allowed && it != selected }
}

private class V4FixedRunner : V4PolicyRunner {
    override val id: String = "A_FIXED_BASELINE"
    override val status: String = "REFERENCE"
    override var feedbackObservations: Int = 0
        private set

    override fun reset() {
        feedbackObservations = 0
    }

    override fun select(input: V4Input): V4Choice {
        val started = System.nanoTime()
        val selected = input.allowed.first()
        val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
        return V4Choice(
            strategy = selected,
            fallbackOrder = v4FallbackOrder(selected, input.allowed),
            policy = "BASELINE",
            confidence = "HIGH",
            reasonCode = "fixed-order",
            measuredInferenceNanos = elapsed
        )
    }

    override fun observe(input: V4Input, choice: V4Choice, outcome: V4ObservedOutcome) {
        feedbackObservations++
    }
}

private data class V4EmaArm(
    var observations: Int = 0,
    var successEma: Double = 0.5,
    var latencyEmaMs: Double = 1_000.0
)

private class V4HeuristicRunner(private val config: V4Config) : V4PolicyRunner {
    override val id: String = "B_RECENT_ADAPTIVE_HEURISTIC"
    override val status: String = "REFERENCE"
    override var feedbackObservations: Int = 0
        private set
    private val arms = LinkedHashMap<String, LinkedHashMap<TurboStrategyId, V4EmaArm>>()

    override fun reset() {
        arms.clear()
        feedbackObservations = 0
    }

    override fun select(input: V4Input): V4Choice {
        val started = System.nanoTime()
        val contextArms = arms.getOrPut(input.contextFamilyKey) { LinkedHashMap() }
        val underObserved = input.allowed.filter {
            (contextArms[it]?.observations ?: 0) < config.minimumArmObservations
        }
        val selected = if (underObserved.isNotEmpty()) {
            underObserved.minWithOrNull(compareBy<TurboStrategyId> {
                contextArms[it]?.observations ?: 0
            }.thenBy { V4_BASELINE_ORDER.indexOf(it) }) ?: input.allowed.first()
        } else {
            input.allowed.maxWithOrNull(compareBy<TurboStrategyId> { v4HeuristicScore(contextArms[it]) }
                .thenByDescending { V4_BASELINE_ORDER.indexOf(it) }) ?: input.allowed.first()
        }
        val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
        val observedCount = contextArms[selected]?.observations ?: 0
        return V4Choice(
            strategy = selected,
            fallbackOrder = v4FallbackOrder(selected, input.allowed),
            policy = if (observedCount < config.minimumArmObservations) "EXPLORATION" else "ADAPTIVE",
            confidence = when {
                observedCount >= 12 -> "HIGH"
                observedCount >= 4 -> "MEDIUM"
                else -> "LOW"
            },
            reasonCode = if (observedCount < config.minimumArmObservations) "bounded-recent-exploration" else "recent-ema-score",
            measuredInferenceNanos = elapsed
        )
    }

    override fun observe(input: V4Input, choice: V4Choice, outcome: V4ObservedOutcome) {
        val contextArms = arms.getOrPut(input.contextFamilyKey) { LinkedHashMap() }
        val arm = contextArms.getOrPut(outcome.strategy) { V4EmaArm() }
        val alpha = config.emaAlpha
        arm.observations++
        arm.successEma += alpha * ((if (outcome.success) 1.0 else 0.0) - arm.successEma)
        arm.latencyEmaMs += alpha * (outcome.latencyMs.toDouble() - arm.latencyEmaMs)
        feedbackObservations++
    }

    private fun v4HeuristicScore(arm: V4EmaArm?): Double {
        if (arm == null) return -1.0
        val latencyPenalty = (arm.latencyEmaMs / 2_500.0).coerceIn(0.0, 1.0) * 0.25
        return arm.successEma - latencyPenalty
    }
}

private class V4Clock : TurboClock {
    private var now = 1_000L

    override fun nowMs(): Long = now

    fun advance(index: Int) {
        now = 1_000L + index.toLong() * 1_000L
    }
}

private class V4Random(seed: Long) : TurboRandom {
    private var state = seed

    override fun nextDouble(): Double = v4Unit(v4Mix64(nextLong()))

    override fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((v4Mix64(nextLong()) ushr 1) % bound.toLong()).toInt()
    }

    private fun nextLong(): Long {
        state += -7046029254386353131L
        return state
    }
}

private class V4CurrentTurboRunner : V4PolicyRunner {
    override val id: String = "C_CURRENT_TURBO_AI_ENGINE"
    override val status: String = "CURRENT_UNTOUCHED"
    override var feedbackObservations: Int = 0
        private set
    private val clock = V4Clock()
    private val engine = TurboAiEngine(
        secret = ByteArray(32) { 0x5A },
        flags = TurboAiFlags(
            enabled = true,
            explorationEnabled = true,
            modelUpdatesEnabled = true,
            developerDiagnosticsEnabled = false,
            forceBaseline = false,
            aegisShadowEnabled = true
        ),
        clock = clock,
        random = V4Random(0x43555252454E545AL)
    )

    override fun reset() {
        feedbackObservations = 0
    }

    override fun select(input: V4Input): V4Choice {
        val started = System.nanoTime()
        val decision = engine.select(input.turboContext, V4_BASELINE_ORDER)
        val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
        return V4Choice(
            strategy = decision.strategy,
            fallbackOrder = decision.fallbackOrder.filter { it in input.allowed },
            policy = decision.policy.name,
            confidence = decision.reason.confidence.name,
            reasonCode = decision.reason.code,
            measuredInferenceNanos = elapsed,
            reportedInferenceNanos = decision.decisionOverheadNanos,
            engineDecision = decision
        )
    }

    override fun observe(input: V4Input, choice: V4Choice, outcome: V4ObservedOutcome) {
        val decision = choice.engineDecision ?: return
        clock.advance(input.index)
        engine.update(
            TurboOutcome(
                contextKey = decision.contextKey,
                strategy = outcome.strategy,
                policy = if (outcome.strategy == choice.strategy) decision.policy else TurboDecisionPolicy.BASELINE_FALLBACK,
                success = outcome.success,
                handshakeLatencyMs = outcome.latencyMs,
                connectionDurationMs = outcome.latencyMs,
                bytesUp = if (outcome.success) 64L * 1_024L else 0L,
                bytesDown = if (outcome.success) 64L * 1_024L else 0L,
                retries = outcome.retries,
                timedOut = outcome.timedOut,
                abruptDisconnect = !outcome.success,
                fallbackUsed = outcome.fallbackUsed,
                decisionOverheadNanos = choice.measuredInferenceNanos,
                batterySaver = input.turboContext.batterySaver,
                failureCategory = when {
                    outcome.success -> TurboFailureCategory.NONE
                    outcome.timedOut -> TurboFailureCategory.TIMEOUT
                    else -> TurboFailureCategory.TLS_ALERT
                },
                completedAtMs = clock.nowMs(),
                sharedContextKey = decision.sharedContextKey,
                aegisToken = 0L
            )
        )
        feedbackObservations++
    }
}

private class V4ProductionAegisLocal2Adapter : AegisLocal2EvaluationAdapter {
    private val epoch = 1L
    private val requestedMode = (System.getProperty("aegis.v4.d.mode") ?: System.getenv("AEGIS_V4_D_MODE"))
        ?.trim()
        ?.uppercase(Locale.US)
        ?.let { runCatching { AegisLocal2RequestedMode.valueOf(it) }.getOrNull() }
        ?: AegisLocal2RequestedMode.SHADOW
    private val engine = AegisLocal2Engine(
        AegisLocal2Config(requestedMode = requestedMode)
    )
    override val status: String
        get() {
            val stats = engine.stats()
            return when {
                stats.killSwitchTrips > 0L || stats.mode == AegisLocal2Mode.KILLED -> "KILLED"
                stats.mode == AegisLocal2Mode.OFF -> "OFF"
                requestedMode == AegisLocal2RequestedMode.ACTIVE -> "SHADOW_DOWNGRADED"
                else -> "SHADOW"
            }
        }

    override fun reset() {
        engine.reset(epoch)
    }

    override fun select(request: AegisLocal2EvaluationRequest): AegisLocal2EvaluationDecision {
        val context = request.toTurboContext()
        val allowed = request.allowedStrategyIds.mapNotNull { TurboStrategyId.fromStableId(it) }
        val baseline = allowed.first()
        val baselineDecision = TurboDecision(
            strategy = baseline,
            fallbackOrder = v4FallbackOrder(baseline, allowed),
            policy = TurboDecisionPolicy.BASELINE,
            reason = TurboDecisionReason(
                code = "v4-baseline",
                confidence = TurboConfidence.HIGH,
                eligibleStrategyIds = allowed.map { it.stableId }
            ),
            contextKey = request.contextFamilyKey,
            sharedContextKey = request.contextFamilyKey,
            batterySaver = request.batterySaver,
            decisionOverheadNanos = 0L,
            aegisToken = 0L
        )
        val decision = engine.decide(context, allowed, baselineDecision, epoch)
        return AegisLocal2EvaluationDecision(
            strategyId = decision.suggestion.stableId,
            fallbackStrategyIds = v4FallbackOrder(decision.suggestion, allowed).map { it.stableId },
            policy = "D_${decision.mode.name}",
            confidence = decision.confidence.name,
            reasonCode = decision.reason.name,
            reportedInferenceNanos = decision.inferenceNanos,
            aegisToken = decision.token,
            epoch = epoch,
            aegisMode = decision.stats.mode.name,
            aegisKillSwitchTrips = decision.stats.killSwitchTrips,
            aegisTrainedObservations = decision.stats.trainedObservations
        )
    }

    override fun observe(
        request: AegisLocal2EvaluationRequest,
        outcome: AegisLocal2EvaluationObservation
    ) {
        val strategy = TurboStrategyId.fromStableId(outcome.strategyId) ?: return
        val failureCategory = when {
            outcome.success -> TurboFailureCategory.NONE
            outcome.timedOut -> TurboFailureCategory.TIMEOUT
            else -> TurboFailureCategory.TLS_ALERT
        }
        engine.observe(
            token = outcome.aegisToken,
            outcome = TurboOutcome(
                contextKey = request.contextFamilyKey,
                strategy = strategy,
                policy = TurboDecisionPolicy.LEARNED,
                success = outcome.success,
                handshakeLatencyMs = outcome.latencyMs,
                connectionDurationMs = outcome.latencyMs,
                bytesUp = if (outcome.success) 64L * 1_024L else 0L,
                bytesDown = if (outcome.success) 64L * 1_024L else 0L,
                retries = outcome.retries,
                timedOut = outcome.timedOut,
                abruptDisconnect = !outcome.success,
                fallbackUsed = outcome.fallbackUsed,
                decisionOverheadNanos = 0L,
                batterySaver = request.batterySaver,
                failureCategory = failureCategory,
                completedAtMs = 1_000L,
                sharedContextKey = request.contextFamilyKey,
                aegisToken = outcome.aegisToken
            ),
            epoch = outcome.epoch
        )
    }
}

private class V4DRunner : V4PolicyRunner {
    override val id: String = "D_AEGIS_LOCAL2"
    private val adapter: AegisLocal2EvaluationAdapter = v4LoadAegisLocal2Adapter()
    private var errorStatus: String? = null
    override val status: String
        get() = errorStatus ?: adapter.status
    override var feedbackObservations: Int = 0
        private set

    override fun reset() {
        feedbackObservations = 0
        try {
            adapter.reset()
        } catch (_: Throwable) {
            errorStatus = "SHADOW_D_RESET_ERROR"
        }
    }

    override fun select(input: V4Input): V4Choice {
        val started = System.nanoTime()
        val request = input.dRequest()
        val decision = try {
            adapter.select(request)
        } catch (_: Throwable) {
            errorStatus = "SHADOW_D_SELECT_ERROR"
            AegisLocal2EvaluationDecision(
                strategyId = input.allowed.first().stableId,
                fallbackStrategyIds = v4FallbackOrder(input.allowed.first(), input.allowed).map { it.stableId },
                policy = "D_SAFE_FALLBACK",
                confidence = "LOW",
                reasonCode = "adapter-error"
            )
        }
        val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
        val proposed = TurboStrategyId.fromStableId(decision.strategyId)
        val valid = proposed != null && proposed in input.allowed
        val selected = if (valid) proposed!! else input.allowed.first()
        val requestedFallbacks = decision.fallbackStrategyIds.mapNotNull { TurboStrategyId.fromStableId(it) }
        val fallback = (requestedFallbacks + v4FallbackOrder(selected, input.allowed))
            .filter { it in input.allowed && it != selected }
            .distinct()
        return V4Choice(
            strategy = selected,
            fallbackOrder = fallback,
            policy = decision.policy,
            confidence = decision.confidence,
            reasonCode = decision.reasonCode,
            measuredInferenceNanos = elapsed,
            reportedInferenceNanos = decision.reportedInferenceNanos,
            invalidProviderOutput = !valid,
            aegisToken = decision.aegisToken,
            aegisEpoch = decision.epoch,
            aegisMode = decision.aegisMode,
            aegisKillSwitchTrips = decision.aegisKillSwitchTrips,
            aegisTrainedObservations = decision.aegisTrainedObservations
        )
    }

    override fun observe(input: V4Input, choice: V4Choice, outcome: V4ObservedOutcome) {
        try {
            adapter.observe(
                input.dRequest(),
                AegisLocal2EvaluationObservation(
                    strategyId = outcome.strategy.stableId,
                    success = outcome.success,
                    latencyMs = outcome.latencyMs,
                    retries = outcome.retries,
                    timedOut = outcome.timedOut,
                    fallbackUsed = outcome.fallbackUsed,
                    aegisToken = choice.aegisToken,
                    epoch = choice.aegisEpoch
                )
            )
            feedbackObservations++
        } catch (_: Throwable) {
            errorStatus = "SHADOW_D_OBSERVE_ERROR"
        }
    }
}

private fun v4LoadAegisLocal2Adapter(): AegisLocal2EvaluationAdapter {
    val className = (System.getProperty("aegis.v4.d.adapterClass") ?: System.getenv("AEGIS_V4_D_ADAPTER_CLASS"))
        ?.trim()
        .orEmpty()
    if (className.isEmpty()) return V4ProductionAegisLocal2Adapter()
    return try {
        val instance = Class.forName(className).getDeclaredConstructor().newInstance()
        instance as? AegisLocal2EvaluationAdapter ?: V4ProductionAegisLocal2Adapter()
    } catch (_: Throwable) {
        V4ProductionAegisLocal2Adapter()
    }
}

private fun buildV4Trace(config: V4Config): List<V4Sample> {
    return buildV4Split(config, V4Split.TRAIN, config.trainingSamples, config.trainSeed) +
        buildV4Split(config, V4Split.EVAL, config.evaluationSamples, config.evaluationSeed)
}

private fun buildV4Split(
    config: V4Config,
    split: V4Split,
    sampleCount: Int,
    seed: Long
): List<V4Sample> {
    val seenContexts = HashSet<String>()
    val seenDestinations = HashSet<String>()
    return (0 until sampleCount).map { index ->
        val scenario = v4Scenario(index)
        val contextId = v4ContextId(scenario, index)
        val destinationGroup = v4DestinationGroup(scenario, index)
        val destinationKey = v4DestinationKey(seed, split, scenario, index, destinationGroup)
        val phase = v4Phase(config, scenario, index)
        val warmStart = contextId in seenContexts || destinationKey in seenDestinations
        val turboContext = v4TurboContext(scenario, index, phase, destinationKey)
        val allowed = TurboSafetyPolicy().eligibleStrategies(turboContext).eligible
        val contextFamilyKey = v4ContextFamilyKey(turboContext)
        val input = V4Input(
            sampleId = "${split.name.lowercase(Locale.US)}-${index.toString().padStart(3, '0')}",
            split = split,
            index = index,
            destinationKey = destinationKey,
            contextFamilyKey = contextFamilyKey,
            turboContext = turboContext,
            allowed = allowed
        )
        val provisional = V4Sample(
            sampleId = input.sampleId,
            split = split,
            index = index,
            scenario = scenario,
            contextId = contextId,
            destinationKey = destinationKey,
            destinationRevisitGroup = destinationGroup,
            phase = phase,
            warmStart = warmStart,
            input = input,
            outcomes = emptyMap(),
            bestStrategies = emptySet()
        )
        val outcomes = v4WorldOutcomes(provisional, allowed, seed)
        val costs = outcomes.mapValues { (_, outcome) -> v4InitialCost(outcome) }
        val bestCost = costs.values.minOrNull() ?: Long.MAX_VALUE
        val best = costs.filterValues { it <= bestCost + 5L }.keys
        val sample = provisional.copy(outcomes = outcomes, bestStrategies = best)
        seenContexts += contextId
        seenDestinations += destinationKey
        sample
    }
}

private fun v4Scenario(index: Int): V4Scenario {
    return when (index % 12) {
        0, 1 -> V4Scenario.COLD_START
        2, 3 -> V4Scenario.WARM_RECURRING
        4, 5 -> V4Scenario.DESTINATION_REVISIT
        6, 7 -> V4Scenario.CHANGING_QUALITY
        8, 9 -> V4Scenario.MISLEADING_TRANSIENT
        else -> V4Scenario.STABLE_NO_ADVANTAGE
    }
}

private fun v4ContextId(scenario: V4Scenario, index: Int): String {
    return when (scenario) {
        V4Scenario.COLD_START -> "cold-${index}"
        V4Scenario.WARM_RECURRING -> "warm-${index % 3}"
        V4Scenario.DESTINATION_REVISIT -> "revisit-${index % 3}"
        V4Scenario.CHANGING_QUALITY -> "changing-${index % 2}"
        V4Scenario.MISLEADING_TRANSIENT -> "misleading-${index % 2}"
        V4Scenario.STABLE_NO_ADVANTAGE -> "stable-${index % 2}"
    }
}

private fun v4DestinationGroup(scenario: V4Scenario, index: Int): String {
    return when (scenario) {
        V4Scenario.DESTINATION_REVISIT -> "revisit-destination-${index % 4}"
        V4Scenario.WARM_RECURRING -> "warm-destination-${index % 3}"
        else -> "unique-${scenario.name.lowercase(Locale.US)}-${index}"
    }
}

private fun v4DestinationKey(
    seed: Long,
    split: V4Split,
    scenario: V4Scenario,
    index: Int,
    destinationGroup: String
): String {
    val stableSeed = if (scenario == V4Scenario.DESTINATION_REVISIT) {
        0x524556495349545AL
    } else {
        seed
    }
    return v4OpaqueKey(stableSeed, "${split.name}|$destinationGroup|${scenario.name}", index % 7, 1)
}

private fun v4Phase(config: V4Config, scenario: V4Scenario, index: Int): Int {
    return when (scenario) {
        V4Scenario.CHANGING_QUALITY -> config.qualityChangePoints.count { index >= it }
        V4Scenario.MISLEADING_TRANSIENT -> index % 6
        else -> 0
    }
}

private fun v4TurboContext(
    scenario: V4Scenario,
    index: Int,
    phase: Int,
    destinationKey: String
): TurboContext {
    val cellular = scenario == V4Scenario.CHANGING_QUALITY || scenario == V4Scenario.MISLEADING_TRANSIENT
    val roaming = scenario == V4Scenario.MISLEADING_TRANSIENT && index % 5 == 0
    val validated = scenario != V4Scenario.COLD_START || index % 4 != 0
    val batterySaver = index % 17 == 0
    val qualityBucket = when (scenario) {
        V4Scenario.CHANGING_QUALITY -> phase.coerceIn(0, 4)
        V4Scenario.MISLEADING_TRANSIENT -> if (phase % 3 == 0) 0 else 3
        V4Scenario.STABLE_NO_ADVANTAGE -> 1
        V4Scenario.COLD_START -> 2
        else -> 1
    }
    val transport = when {
        roaming -> TurboNetworkTransport.CELLULAR
        cellular -> TurboNetworkTransport.CELLULAR
        index % 5 == 0 -> TurboNetworkTransport.ETHERNET
        else -> TurboNetworkTransport.WIFI
    }
    return TurboContext(
        destinationKey = destinationKey,
        destinationPort = 443,
        transport = transport,
        metered = cellular,
        roaming = roaming,
        validated = validated,
        batterySaver = batterySaver,
        clientHelloParsed = index % 19 != 0,
        sniPresent = index % 23 != 0,
        latencyProfile = when (qualityBucket) {
            0 -> "LOW"
            1 -> "NORMAL"
            2 -> "ELEVATED"
            else -> "DEGRADED"
        },
        approximateRttBucket = qualityBucket,
        recentRetryBucket = when (scenario) {
            V4Scenario.MISLEADING_TRANSIENT -> if (phase % 3 == 0) 0 else 3
            else -> qualityBucket.coerceIn(0, 4)
        },
        recentSuccessBucket = when (scenario) {
            V4Scenario.MISLEADING_TRANSIENT -> 4
            V4Scenario.COLD_START -> 0
            else -> (4 - qualityBucket).coerceIn(0, 4)
        },
        recentHandshakeBucket = qualityBucket,
        resourcePressureBucket = if (batterySaver) 2 else index % 3,
        previousFailure = if (scenario == V4Scenario.MISLEADING_TRANSIENT && phase % 4 == 3) {
            TurboFailureCategory.TIMEOUT
        } else {
            TurboFailureCategory.NONE
        }
    )
}

private fun v4ContextFamilyKey(context: TurboContext): String {
    return buildString {
        append(context.destinationPort).append('|')
        append(context.transport.name).append('|')
        append(if (context.metered) 'M' else 'U')
        append(if (context.roaming) 'R' else 'N')
        append(if (context.validated) 'V' else 'X')
        append(if (context.batterySaver) 'B' else 'P')
        append('|').append(if (context.clientHelloParsed) 'H' else 'X')
        append(if (context.sniPresent) 'S' else 'N')
    }
}

private fun v4WorldOutcomes(
    sample: V4Sample,
    allowed: List<TurboStrategyId>,
    seed: Long
): Map<TurboStrategyId, V4WorldOutcome> {
    if (sample.scenario == V4Scenario.STABLE_NO_ADVANTAGE) {
        return allowed.associateWith { V4WorldOutcome(true, 180L, 0, false) }
    }
    val preference = v4Preference(sample.scenario, sample.phase)
    val baseLatency = when (sample.scenario) {
        V4Scenario.COLD_START -> 420L
        V4Scenario.WARM_RECURRING -> 240L
        V4Scenario.DESTINATION_REVISIT -> 280L
        V4Scenario.CHANGING_QUALITY -> 260L + sample.phase * 130L
        V4Scenario.MISLEADING_TRANSIENT -> 330L
        V4Scenario.STABLE_NO_ADVANTAGE -> 180L
    }
    return allowed.associateWith { strategy ->
        val rank = preference.indexOf(strategy).coerceAtLeast(0)
        val threshold = when (rank) {
            0 -> 0.97
            1 -> 0.89
            2 -> 0.80
            else -> 0.72
        }
        val actionSeed = v4StableHash64(strategy.stableId)
        val sampleSeed = v4Mix64(seed xor actionSeed xor sample.index.toLong() * 31L xor sample.phase.toLong() * 997L)
        val success = v4Unit(sampleSeed) < threshold
        val jitter = (v4PositiveMod(v4Mix64(sampleSeed xor 0x6A09E667F3BCC909L), 61L) - 30L)
        val latency = (baseLatency + rank * 105L + jitter).coerceAtLeast(40L)
        V4WorldOutcome(
            success = success,
            latencyMs = if (success) latency else latency + 2_100L + rank * 120L,
            retries = if (success) rank.coerceAtMost(2) else 3,
            timedOut = !success && rank >= 2
        )
    }
}

private fun v4Preference(scenario: V4Scenario, phase: Int): List<TurboStrategyId> {
    val orders = listOf(
        listOf(
            TurboStrategyId.TLS_HOSTNAME_V1,
            TurboStrategyId.TLS_SNI_MULTI_V1,
            TurboStrategyId.TLS_SINGLE_SAFE_V1,
            TurboStrategyId.TLS_PLAIN_V1
        ),
        listOf(
            TurboStrategyId.TLS_SINGLE_SAFE_V1,
            TurboStrategyId.TLS_HOSTNAME_V1,
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
            TurboStrategyId.TLS_SNI_MULTI_V1,
            TurboStrategyId.TLS_PLAIN_V1,
            TurboStrategyId.TLS_HOSTNAME_V1,
            TurboStrategyId.TLS_SINGLE_SAFE_V1
        )
    )
    return when (scenario) {
        V4Scenario.COLD_START -> orders[0]
        V4Scenario.WARM_RECURRING -> orders[phase % 2]
        V4Scenario.DESTINATION_REVISIT -> orders[2]
        V4Scenario.CHANGING_QUALITY -> orders[phase % orders.size]
        V4Scenario.MISLEADING_TRANSIENT -> if (phase % 3 == 0) orders[3] else orders[1]
        V4Scenario.STABLE_NO_ADVANTAGE -> V4_BASELINE_ORDER
    }
}

private fun runV4Policy(
    runner: V4PolicyRunner,
    trace: List<V4Sample>,
    config: V4Config
): V4PolicyRun {
    runner.reset()
    val decisions = ArrayList<V4RunDecision>(trace.size)
    trace.forEach { sample ->
        val choice = runner.select(sample.input)
        assertTrue("${runner.id} selected an unsafe strategy", choice.strategy in sample.input.allowed)
        val execution = v4Execute(sample, choice)
        execution.attempts.forEach { outcome -> runner.observe(sample.input, choice, outcome) }
        val chosenCost = if (execution.servedSuccess) {
            execution.servedLatencyMs
        } else {
            execution.servedLatencyMs + 5_000L
        }
        val bestCost = sample.outcomes.values.minOf { v4InitialCost(it) }
        decisions += V4RunDecision(
            sample = sample,
            choice = choice,
            execution = execution,
            wrongChoice = choice.strategy !in sample.bestStrategies,
            regretMs = (chosenCost - bestCost).coerceAtLeast(0L),
            bestCostMs = bestCost,
            chosenCostMs = chosenCost
        )
    }
    val metrics = linkedMapOf<String, V4Metrics>()
    metrics["train"] = v4Aggregate(decisions.filter { it.sample.split == V4Split.TRAIN })
    metrics["eval"] = v4Aggregate(decisions.filter { it.sample.split == V4Split.EVAL })
    V4Scenario.values().forEach { scenario ->
        metrics["eval:${scenario.name}"] = v4Aggregate(
            decisions.filter { it.sample.split == V4Split.EVAL && it.sample.scenario == scenario }
        )
    }
    assertEquals(decisions.sumOf { it.execution.attempts.size }, runner.feedbackObservations)
    assertEquals(config.trainingSamples + config.evaluationSamples, decisions.size)
    return V4PolicyRun(runner.id, runner.status, decisions, metrics)
}

private fun v4Execute(sample: V4Sample, choice: V4Choice): V4Execution {
    val initialWorld = sample.outcomes.getValue(choice.strategy)
    val initial = V4ObservedOutcome(
        strategy = choice.strategy,
        success = initialWorld.success,
        latencyMs = initialWorld.latencyMs,
        retries = initialWorld.retries,
        timedOut = initialWorld.timedOut
    )
    val attempts = mutableListOf(initial)
    var servedSuccess = initialWorld.success
    var servedLatency = initialWorld.latencyMs
    var fallbackUsed = false
    var fallbackRecovered = false
    var fallbackSteps = 0
    if (!servedSuccess) {
        choice.fallbackOrder.filter { it in sample.input.allowed }.distinct().forEach { strategy ->
            if (servedSuccess) return@forEach
            fallbackUsed = true
            fallbackSteps++
            val world = sample.outcomes.getValue(strategy)
            attempts += V4ObservedOutcome(
                strategy = strategy,
                success = world.success,
                latencyMs = world.latencyMs,
                retries = world.retries,
                timedOut = world.timedOut,
                fallbackUsed = true
            )
            servedLatency += world.latencyMs
            servedSuccess = world.success
            if (servedSuccess) fallbackRecovered = true
        }
    }
    return V4Execution(
        initial = initial,
        attempts = attempts.toList(),
        servedSuccess = servedSuccess,
        servedLatencyMs = servedLatency,
        fallbackUsed = fallbackUsed,
        fallbackRecovered = fallbackRecovered,
        fallbackSteps = fallbackSteps
    )
}

private fun v4InitialCost(outcome: V4WorldOutcome): Long {
    return if (outcome.success) outcome.latencyMs else outcome.latencyMs + 5_000L
}

private fun v4Aggregate(decisions: List<V4RunDecision>): V4Metrics {
    if (decisions.isEmpty()) {
        return V4Metrics(
            samples = 0,
            initialSuccesses = 0,
            servedSuccesses = 0,
            wrongChoices = 0,
            fallbackAttempts = 0,
            fallbackRecoveries = 0,
            invalidProviderOutputs = 0,
            feedbackObservations = 0,
            initialSuccessRate = 0.0,
            servedSuccessRate = 0.0,
            servedLatencyP95Ms = 0L,
            successfulLatencyP95Ms = 0L,
            wrongChoiceRate = 0.0,
            fallbackRate = 0.0,
            fallbackRecoveryRate = 0.0,
            meanRegretMs = 0.0,
            inferenceP50Nanos = 0L,
            inferenceP95Nanos = 0L,
            inferenceMeanNanos = 0.0,
            reportedInferenceP95Nanos = null
        )
    }
    val sampleCount = decisions.size.toDouble()
    val inference = decisions.map { it.choice.measuredInferenceNanos }.sorted()
    val successfulLatency = decisions.filter { it.execution.servedSuccess }
        .map { it.execution.servedLatencyMs }
        .sorted()
    val servedCost = decisions.map {
        if (it.execution.servedSuccess) it.execution.servedLatencyMs else it.execution.servedLatencyMs + 5_000L
    }.sorted()
    val reported = decisions.mapNotNull { it.choice.reportedInferenceNanos }.sorted()
    val fallbackAttempts = decisions.count { it.execution.fallbackUsed }
    return V4Metrics(
        samples = decisions.size,
        initialSuccesses = decisions.count { it.execution.initial.success },
        servedSuccesses = decisions.count { it.execution.servedSuccess },
        wrongChoices = decisions.count { it.wrongChoice },
        fallbackAttempts = fallbackAttempts,
        fallbackRecoveries = decisions.count { it.execution.fallbackRecovered },
        invalidProviderOutputs = decisions.count { it.choice.invalidProviderOutput },
        feedbackObservations = decisions.sumOf { it.execution.attempts.size },
        initialSuccessRate = decisions.count { it.execution.initial.success } / sampleCount,
        servedSuccessRate = decisions.count { it.execution.servedSuccess } / sampleCount,
        servedLatencyP95Ms = v4Percentile(servedCost, 0.95),
        successfulLatencyP95Ms = if (successfulLatency.isEmpty()) 0L else v4Percentile(successfulLatency, 0.95),
        wrongChoiceRate = decisions.count { it.wrongChoice } / sampleCount,
        fallbackRate = fallbackAttempts / sampleCount,
        fallbackRecoveryRate = decisions.count { it.execution.fallbackRecovered } / sampleCount,
        meanRegretMs = decisions.sumOf { it.regretMs }.toDouble() / sampleCount,
        inferenceP50Nanos = v4Percentile(inference, 0.50),
        inferenceP95Nanos = v4Percentile(inference, 0.95),
        inferenceMeanNanos = inference.average(),
        reportedInferenceP95Nanos = if (reported.isEmpty()) null else v4Percentile(reported, 0.95)
    )
}

private fun v4Percentile(values: List<Long>, percentile: Double): Long {
    if (values.isEmpty()) return 0L
    val index = ((values.size - 1).toDouble() * percentile).toInt().coerceIn(0, values.lastIndex)
    return values[index]
}

private fun buildV4Evaluation(config: V4Config): V4Evaluation {
    val configJson = config.canonicalJson()
    val trace = buildV4Trace(config)
    val runs = listOf(
        runV4Policy(V4FixedRunner(), trace, config),
        runV4Policy(V4HeuristicRunner(config), trace, config),
        runV4Policy(V4CurrentTurboRunner(), trace, config),
        runV4Policy(V4DRunner(), trace, config)
    )
    val dRun = runs.first { it.id == "D_AEGIS_LOCAL2" }
    val bEval = runs.first { it.id == "B_RECENT_ADAPTIVE_HEURISTIC" }.metrics.getValue("eval")
    val dEval = dRun.metrics.getValue("eval")
    val dAvailable = dRun.status == "ACTIVE" || dRun.status == "SHADOW" || dRun.status == "SHADOW_DOWNGRADED"
    val dSuggestedNonBaseline = dRun.decisions.any { decision ->
        decision.choice.strategy != decision.sample.input.allowed.first()
    }
    val invalidFree = dEval.invalidProviderOutputs == 0
    val noScenarioRegression = V4Scenario.values().all { scenario ->
        val b = runs.first { it.id == "B_RECENT_ADAPTIVE_HEURISTIC" }.metrics.getValue("eval:${scenario.name}")
        val d = dRun.metrics.getValue("eval:${scenario.name}")
        d.servedSuccessRate >= b.servedSuccessRate - 0.03 &&
            d.servedLatencyP95Ms <= (b.servedLatencyP95Ms * 1.15).toLong().coerceAtLeast(b.servedLatencyP95Ms + 1L)
    }
    val measurableAdvantage = dEval.servedSuccessRate >= bEval.servedSuccessRate + 0.01 ||
        dEval.servedLatencyP95Ms <= (bEval.servedLatencyP95Ms * 0.95).toLong() ||
        dEval.wrongChoiceRate <= bEval.wrongChoiceRate - 0.02
    val overheadWithinTarget = dEval.inferenceP95Nanos <= 1_500_000L
    val promotionVerdict: String
    val promotionReason: String
    when {
        dRun.status == "KILLED" -> {
            promotionVerdict = "SHADOW_D_MODEL_KILLED"
            promotionReason = "D's kill switch was tripped during evaluation."
        }
        dRun.status == "OFF" -> {
            promotionVerdict = "SHADOW_D_DISABLED"
            promotionReason = "D was disabled during evaluation."
        }
        !dAvailable || dRun.status.startsWith("SHADOW_D_") || dRun.status == "SHADOW_UNWIRED" -> {
            promotionVerdict = "SHADOW_D_API_PENDING"
            promotionReason = "D adapter is not active; metrics are retained as a shadow reference."
        }
        !dSuggestedNonBaseline -> {
            promotionVerdict = "SHADOW_D_NO_SUGGESTION"
            promotionReason = "D never produced a non-baseline suggestion in the evaluated trace."
        }
        !invalidFree -> {
            promotionVerdict = "SHADOW_D_INVALID_OUTPUT"
            promotionReason = "D produced invalid strategy output or did not remain in a safe state."
        }
        !noScenarioRegression -> {
            promotionVerdict = "SHADOW_D_SCENARIO_REGRESSION"
            promotionReason = "D regressed a held-out scenario against B beyond the allowed margin."
        }
        !overheadWithinTarget -> {
            promotionVerdict = "SHADOW_D_INFERENCE_OVERHEAD"
            promotionReason = "D p95 inference overhead exceeded 1.5 ms on the host JVM benchmark."
        }
        !measurableAdvantage -> {
            promotionVerdict = "SHADOW_D_NO_PROVEN_ADVANTAGE"
            promotionReason = "D did not show a meaningful held-out improvement over B."
        }
        dRun.status != "ACTIVE" -> {
            promotionVerdict = "SHADOW_D_READY_NOT_ACTIVATED"
            promotionReason = "D passed the benchmark gates while its runtime mode remains shadow."
        }
        dEval.servedSuccessRate < bEval.servedSuccessRate ||
            dEval.servedLatencyP95Ms > (bEval.servedLatencyP95Ms * 1.02).toLong() -> {
            promotionVerdict = "SHADOW_D_REGRESSION"
            promotionReason = "D failed the aggregate success or p95 comparison against B."
        }
        else -> {
            promotionVerdict = "KEEP_D_ACTIVE"
            promotionReason = "D passed the aggregate, scenario, overhead, and meaningful-advantage gates."
        }
    }
    return V4Evaluation(
        config = config,
        configJson = configJson,
        configHash = v4Sha256(configJson),
        trace = trace,
        runs = runs,
        dStatus = dRun.status,
        dFinalMode = dRun.decisions.lastOrNull()?.choice?.aegisMode,
        dKillSwitchTrips = dRun.decisions.lastOrNull()?.choice?.aegisKillSwitchTrips,
        dTrainedObservations = dRun.decisions.lastOrNull()?.choice?.aegisTrainedObservations,
        promotionVerdict = promotionVerdict,
        promotionReason = promotionReason
    )
}

private fun writeV4Evaluation(evaluation: V4Evaluation) {
    val artifactDir = v4ArtifactPath()
    Files.createDirectories(artifactDir)
    val sourceHash = v4SourceHash()
    v4Write(artifactDir.resolve("config.json"), evaluation.configJson + "\n")
    v4Write(
        artifactDir.resolve("metadata.json"),
        buildString {
            append('{')
            append("\"schema\":\"aegis-local2-metadata-v1\",")
            append("\"measurementDomain\":\"host-local-jvm-synthetic\",")
            append("\"realNetworkClaims\":false,")
            append("\"deviceEvidence\":false,")
            append("\"selectedFeedbackOnly\":true,")
            append("\"counterfactualCostsBenchmarkOnly\":true,")
            append("\"outcomeTruthVisibleToPolicies\":false,")
            append("\"configHash\":").append(v4Quote(evaluation.configHash)).append(',')
            append("\"sourceHash\":").append(v4Quote(sourceHash)).append(',')
            append("\"dAdapterStatus\":").append(v4Quote(evaluation.dStatus)).append(',')
            append("\"dFinalMode\":").append(evaluation.dFinalMode?.let(::v4Quote) ?: "null").append(',')
            append("\"dKillSwitchTrips\":").append(evaluation.dKillSwitchTrips ?: "null").append(',')
            append("\"dTrainedObservations\":").append(evaluation.dTrainedObservations ?: "null").append(',')
            append("\"files\":[\"config.json\",\"metadata.json\",\"raw-trace.jsonl\",\"raw-decisions.jsonl\",\"summary.json\",\"summary.csv\",\"summary.md\"]")
            append('}')
        } + "\n"
    )
    val traceLines = evaluation.trace.joinToString("\n") { v4TraceJson(it, evaluation.configHash, sourceHash) }
    v4Write(artifactDir.resolve("raw-trace.jsonl"), if (traceLines.isEmpty()) "" else traceLines + "\n")
    val decisionLines = evaluation.runs.flatMap { run ->
        run.decisions.map { decision -> v4DecisionJson(run, decision, evaluation.configHash, sourceHash) }
    }.joinToString("\n")
    v4Write(artifactDir.resolve("raw-decisions.jsonl"), if (decisionLines.isEmpty()) "" else decisionLines + "\n")
    v4Write(artifactDir.resolve("summary.json"), v4SummaryJson(evaluation, sourceHash) + "\n")
    v4Write(artifactDir.resolve("summary.csv"), v4SummaryCsv(evaluation))
    v4Write(artifactDir.resolve("summary.md"), v4SummaryMarkdown(evaluation, sourceHash))
}

private fun v4SummaryJson(evaluation: V4Evaluation, sourceHash: String): String {
    return buildString {
        append('{')
        append("\"schema\":\"aegis-local2-summary-v1\",")
        append("\"measurementDomain\":\"host-local-jvm-synthetic\",")
        append("\"realNetworkClaims\":false,")
        append("\"selectedFeedbackOnly\":true,")
        append("\"counterfactualCostsBenchmarkOnly\":true,")
        append("\"configHash\":").append(v4Quote(evaluation.configHash)).append(',')
        append("\"sourceHash\":").append(v4Quote(sourceHash)).append(',')
        append("\"dAdapterStatus\":").append(v4Quote(evaluation.dStatus)).append(',')
        append("\"dFinalMode\":").append(evaluation.dFinalMode?.let(::v4Quote) ?: "null").append(',')
        append("\"dKillSwitchTrips\":").append(evaluation.dKillSwitchTrips ?: "null").append(',')
        append("\"dTrainedObservations\":").append(evaluation.dTrainedObservations ?: "null").append(',')
        append("\"promotionVerdict\":").append(v4Quote(evaluation.promotionVerdict)).append(',')
        append("\"promotionReason\":").append(v4Quote(evaluation.promotionReason)).append(',')
        append("\"policies\":[")
        append(evaluation.runs.joinToString(",") { run ->
            buildString {
                append('{')
                append("\"id\":").append(v4Quote(run.id)).append(',')
                append("\"status\":").append(v4Quote(run.status)).append(',')
                append("\"metrics\":{")
                append(run.metrics.entries.joinToString(",") { (key, value) ->
                    v4Quote(key) + ":" + v4MetricsJson(value)
                })
                append("}}")
            }
        })
        append("]}")
    }
}

private fun v4MetricsJson(metrics: V4Metrics): String {
    return buildString {
        append('{')
        append("\"samples\":").append(metrics.samples).append(',')
        append("\"initialSuccesses\":").append(metrics.initialSuccesses).append(',')
        append("\"servedSuccesses\":").append(metrics.servedSuccesses).append(',')
        append("\"wrongChoices\":").append(metrics.wrongChoices).append(',')
        append("\"fallbackAttempts\":").append(metrics.fallbackAttempts).append(',')
        append("\"fallbackRecoveries\":").append(metrics.fallbackRecoveries).append(',')
        append("\"invalidProviderOutputs\":").append(metrics.invalidProviderOutputs).append(',')
        append("\"feedbackObservations\":").append(metrics.feedbackObservations).append(',')
        append("\"initialSuccessRate\":").append(v4Number(metrics.initialSuccessRate)).append(',')
        append("\"servedSuccessRate\":").append(v4Number(metrics.servedSuccessRate)).append(',')
        append("\"servedLatencyP95Ms\":").append(metrics.servedLatencyP95Ms).append(',')
        append("\"successfulLatencyP95Ms\":").append(metrics.successfulLatencyP95Ms).append(',')
        append("\"wrongChoiceRate\":").append(v4Number(metrics.wrongChoiceRate)).append(',')
        append("\"fallbackRate\":").append(v4Number(metrics.fallbackRate)).append(',')
        append("\"fallbackRecoveryRate\":").append(v4Number(metrics.fallbackRecoveryRate)).append(',')
        append("\"meanRegretMs\":").append(v4Number(metrics.meanRegretMs)).append(',')
        append("\"inferenceP50Nanos\":").append(metrics.inferenceP50Nanos).append(',')
        append("\"inferenceP95Nanos\":").append(metrics.inferenceP95Nanos).append(',')
        append("\"inferenceMeanNanos\":").append(v4Number(metrics.inferenceMeanNanos)).append(',')
        append("\"reportedInferenceP95Nanos\":")
        if (metrics.reportedInferenceP95Nanos == null) append("null") else append(metrics.reportedInferenceP95Nanos)
        append('}')
    }
}

private fun v4SummaryCsv(evaluation: V4Evaluation): String {
    val header = "policy,status,scope,samples,initial_success_rate,served_success_rate,served_latency_p95_ms,successful_latency_p95_ms,wrong_choice_rate,fallback_rate,fallback_recovery_rate,mean_regret_ms,inference_p50_us,inference_p95_us,inference_mean_us,reported_inference_p95_us,invalid_provider_outputs,feedback_observations,promotion_verdict\n"
    val rows = evaluation.runs.flatMap { run ->
        run.metrics.entries.map { (scope, metrics) ->
            listOf(
                run.id,
                run.status,
                scope,
                metrics.samples.toString(),
                v4Number(metrics.initialSuccessRate),
                v4Number(metrics.servedSuccessRate),
                metrics.servedLatencyP95Ms.toString(),
                metrics.successfulLatencyP95Ms.toString(),
                v4Number(metrics.wrongChoiceRate),
                v4Number(metrics.fallbackRate),
                v4Number(metrics.fallbackRecoveryRate),
                v4Number(metrics.meanRegretMs),
                v4Number(metrics.inferenceP50Nanos / 1_000.0),
                v4Number(metrics.inferenceP95Nanos / 1_000.0),
                v4Number(metrics.inferenceMeanNanos / 1_000.0),
                metrics.reportedInferenceP95Nanos?.let { v4Number(it / 1_000.0) } ?: "",
                metrics.invalidProviderOutputs.toString(),
                metrics.feedbackObservations.toString(),
                evaluation.promotionVerdict
            ).joinToString(",") { v4Csv(it) }
        }
    }
    return header + rows.joinToString("\n") + "\n"
}

private fun v4SummaryMarkdown(evaluation: V4Evaluation, sourceHash: String): String {
    val evalRows = evaluation.runs.map { run ->
        val metrics = run.metrics.getValue("eval")
        "| ${run.id} | ${run.status} | ${metrics.servedSuccessRate} | ${metrics.servedLatencyP95Ms} | ${metrics.wrongChoiceRate} | ${metrics.fallbackRate} | ${v4Number(metrics.inferenceP95Nanos / 1_000.0)} |"
    }.joinToString("\n")
    return buildString {
        append("# AegisLocal 2 evaluation\n\n")
        append("This artifact is a deterministic host JVM synthetic benchmark. It is not emulator or physical-device evidence.\n\n")
        append("The policy receives only public context and the outcome of its selected action. Counterfactual outcomes are used after selection for benchmark cost and wrong-choice metrics.\n\n")
        append("Config hash: `").append(evaluation.configHash).append("`  \n")
        append("Source hash: `").append(sourceHash).append("`  \n")
        append("D adapter status: `").append(evaluation.dStatus).append("`  \n")
        append("D final mode: `").append(evaluation.dFinalMode ?: "unknown").append("`, kill-switch trips: `")
            .append(evaluation.dKillSwitchTrips ?: "unknown").append("`, trained observations: `")
            .append(evaluation.dTrainedObservations ?: "unknown").append("`  \n")
        append("Promotion verdict: `").append(evaluation.promotionVerdict).append("`  \n")
        append("Promotion reason: ").append(evaluation.promotionReason).append("\n\n")
        append("| Policy | Status | Served success | Served p95 cost ms | Wrong choice | Fallback | Inference p95 us |\n")
        append("|---|---|---:|---:|---:|---:|---:|\n")
        append(evalRows).append("\n\n")
        append("The trace has ").append(evaluation.config.trainingSamples).append(" training samples and ")
            .append(evaluation.config.evaluationSamples).append(" evaluation samples across cold start, recurring context, destination revisit, changing quality, misleading transient, and stable no-advantage scenarios.\n\n")
        append("The existing `TurboAiEngine` is used through its current public selection and update API. The default D adapter calls the production `AegisLocal2Engine`; `-Daegis.v4.d.adapterClass` is an optional test override. Runtime C selection and counterfactual D suggestion are reported separately.\n")
    }
}

private fun v4TraceJson(sample: V4Sample, configHash: String, sourceHash: String): String {
    return buildString {
        append('{')
        append("\"artifactKind\":\"synthetic-trace\",")
        append("\"configHash\":").append(v4Quote(configHash)).append(',')
        append("\"sourceHash\":").append(v4Quote(sourceHash)).append(',')
        append("\"sampleId\":").append(v4Quote(sample.sampleId)).append(',')
        append("\"split\":").append(v4Quote(sample.split.name.lowercase(Locale.US))).append(',')
        append("\"index\":").append(sample.index).append(',')
        append("\"scenario\":").append(v4Quote(sample.scenario.name)).append(',')
        append("\"contextId\":").append(v4Quote(sample.contextId)).append(',')
        append("\"destinationKey\":").append(v4Quote(sample.destinationKey)).append(',')
        append("\"destinationRevisitGroup\":").append(v4Quote(sample.destinationRevisitGroup)).append(',')
        append("\"phase\":").append(sample.phase).append(',')
        append("\"warmStart\":").append(sample.warmStart).append(',')
        append("\"allowedStrategyIds\":")
        append(sample.input.allowed.joinToString(prefix = "[", postfix = "]", separator = ",") { v4Quote(it.stableId) })
        append(",\"outcomes\":[")
        append(sample.outcomes.entries.joinToString(",") { (strategy, outcome) ->
            "{\"strategyId\":${v4Quote(strategy.stableId)},\"success\":${outcome.success},\"latencyMs\":${outcome.latencyMs},\"retries\":${outcome.retries},\"timedOut\":${outcome.timedOut}}"
        })
        append("]}")
    }
}

private fun v4DecisionJson(
    run: V4PolicyRun,
    decision: V4RunDecision,
    configHash: String,
    sourceHash: String
): String {
    return buildString {
        append('{')
        append("\"artifactKind\":\"synthetic-decision\",")
        append("\"configHash\":").append(v4Quote(configHash)).append(',')
        append("\"sourceHash\":").append(v4Quote(sourceHash)).append(',')
        append("\"policy\":").append(v4Quote(run.id)).append(',')
        append("\"status\":").append(v4Quote(run.status)).append(',')
        append("\"sampleId\":").append(v4Quote(decision.sample.sampleId)).append(',')
        append("\"split\":").append(v4Quote(decision.sample.split.name.lowercase(Locale.US))).append(',')
        append("\"scenario\":").append(v4Quote(decision.sample.scenario.name)).append(',')
        append("\"selectedStrategyId\":").append(v4Quote(decision.choice.strategy.stableId)).append(',')
        append("\"fallbackStrategyIds\":")
        append(decision.choice.fallbackOrder.joinToString(prefix = "[", postfix = "]", separator = ",") { v4Quote(it.stableId) })
        append(',')
        append("\"policyDecision\":").append(v4Quote(decision.choice.policy)).append(',')
        append("\"confidence\":").append(v4Quote(decision.choice.confidence)).append(',')
        append("\"reasonCode\":").append(v4Quote(decision.choice.reasonCode)).append(',')
        append("\"initialSuccess\":").append(decision.execution.initial.success).append(',')
        append("\"observedAttempts\":[")
        append(decision.execution.attempts.joinToString(",") { attempt ->
            "{\"strategy\":${v4Quote(attempt.strategy.stableId)},\"success\":${attempt.success}," +
                "\"latencyMs\":${attempt.latencyMs},\"timedOut\":${attempt.timedOut}," +
                "\"fallbackUsed\":${attempt.fallbackUsed}}"
        })
        append("],")
        append("\"servedSuccess\":").append(decision.execution.servedSuccess).append(',')
        append("\"servedLatencyMs\":").append(decision.execution.servedLatencyMs).append(',')
        append("\"fallbackUsed\":").append(decision.execution.fallbackUsed).append(',')
        append("\"fallbackRecovered\":").append(decision.execution.fallbackRecovered).append(',')
        append("\"fallbackSteps\":").append(decision.execution.fallbackSteps).append(',')
        append("\"wrongChoice\":").append(decision.wrongChoice).append(',')
        append("\"regretMs\":").append(decision.regretMs).append(',')
        append("\"bestCostMs\":").append(decision.bestCostMs).append(',')
        append("\"chosenCostMs\":").append(decision.chosenCostMs).append(',')
        append("\"measuredInferenceNanos\":").append(decision.choice.measuredInferenceNanos).append(',')
        append("\"reportedInferenceNanos\":")
        if (decision.choice.reportedInferenceNanos == null) append("null") else append(decision.choice.reportedInferenceNanos)
        append(',')
        append("\"aegisMode\":")
        if (decision.choice.aegisMode == null) append("null") else append(v4Quote(decision.choice.aegisMode))
        append(',')
        append("\"aegisKillSwitchTrips\":")
        if (decision.choice.aegisKillSwitchTrips == null) append("null") else append(decision.choice.aegisKillSwitchTrips)
        append(',')
        append("\"aegisTrainedObservations\":")
        if (decision.choice.aegisTrainedObservations == null) append("null") else append(decision.choice.aegisTrainedObservations)
        append(',')
        append("\"invalidProviderOutput\":").append(decision.choice.invalidProviderOutput)
        append('}')
    }
}

private fun v4ArtifactPath(): Path {
    val configured = System.getProperty("aegis.v4.artifactDir")?.takeIf { it.isNotBlank() }
        ?: System.getenv("AEGIS_V4_ARTIFACT_DIR")?.takeIf { it.isNotBlank() }
    return (configured?.let { Paths.get(it) } ?: Paths.get("benchmark-results", "aegis-local2", "latest"))
        .toAbsolutePath()
        .normalize()
}

private fun v4SourceHash(): String {
    val working = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
    val root = if (Files.isDirectory(working.resolve("src/test"))) working.parent else working
    val candidates = listOf(
        "app/src/test/java/com/tunnelvpn/app/AegisLocal2EvaluationTest.kt",
        "app/src/main/java/com/tunnelvpn/app/AegisLocal2Engine.kt",
        "app/src/main/java/com/tunnelvpn/app/AegisLocal2Neural.kt",
        "app/src/main/java/com/tunnelvpn/app/AegisLocal2Models.kt",
        "app/src/main/java/com/tunnelvpn/app/TurboAiEngine.kt",
        "app/src/main/java/com/tunnelvpn/app/TurboAiModels.kt",
        "tools/v4-aegis-local2-evaluation.md",
        "tools/v4-aegis-local2-contract.md"
    ).sorted()
    val digest = MessageDigest.getInstance("SHA-256")
    candidates.forEach { relative ->
        val path = root.resolve(relative)
        require(Files.isRegularFile(path)) { "Missing benchmark provenance source: $relative" }
        digest.update(relative.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(Files.readAllBytes(path))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

private fun v4Write(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
}

private fun v4Sha256(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

private fun v4StableHash64(value: String): Long {
    var hash = -3750763034362895579L
    value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        hash = (hash xor (byte.toLong() and 0xFFL)) * 1099511628211L
    }
    return v4Mix64(hash)
}

private fun v4Mix64(value: Long): Long {
    var mixed = value
    mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
    mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
    return mixed xor (mixed ushr 31)
}

private fun v4Unit(value: Long): Double {
    return (value ushr 11).toDouble() / 9_007_199_254_740_992.0
}

private fun v4PositiveMod(value: Long, bound: Long): Long {
    return (value ushr 1) % bound
}

private fun v4OpaqueKey(seed: Long, context: String, index: Int, slot: Int): String {
    val first = v4Mix64(seed xor v4StableHash64(context) xor index.toLong() * 31L xor slot.toLong())
    val second = v4Mix64(first xor -7046029254386353131L)
    return java.lang.Long.toUnsignedString(first, 16).padStart(16, '0') +
        java.lang.Long.toUnsignedString(second, 16).padStart(16, '0')
}

private fun v4Number(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}

private fun v4Csv(value: String): String {
    return if (value.any { it == ',' || it == '"' || it == '\n' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
}

private fun v4Quote(value: String): String {
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

class AegisLocal2EvaluationTest {
    @Test
    fun fallbackSuccessCannotBeCreditedToFailedInitialStrategy() {
        val base = buildV4Trace(V4Config()).first { it.input.allowed.size > 1 }
        val choice = V4FixedRunner().select(base.input)
        val second = choice.fallbackOrder.first()
        val sample = base.copy(outcomes = base.outcomes.mapValues { (strategy, _) ->
            V4WorldOutcome(strategy == second, if (strategy == second) 40L else 2000L, 0, strategy != second)
        })
        val execution = v4Execute(sample, choice)

        assertTrue(execution.servedSuccess)
        assertEquals(2040L, execution.servedLatencyMs)
        assertEquals(listOf(choice.strategy, second), execution.attempts.map { it.strategy })
        assertEquals(listOf(false, true), execution.attempts.map { it.success })
        assertEquals(listOf(2000L, 40L), execution.attempts.map { it.latencyMs })
    }

    @Test
    fun deterministicFairAbcdEvaluationProducesMachineReadableMetrics() {
        val config = V4Config()
        val trace = buildV4Trace(config)
        assertEquals(config.trainingSamples + config.evaluationSamples, trace.size)
        assertEquals(trace, buildV4Trace(config))
        assertTrue(trace.any { it.scenario == V4Scenario.COLD_START })
        assertTrue(trace.any { it.scenario == V4Scenario.WARM_RECURRING && it.warmStart })
        assertTrue(trace.any { it.scenario == V4Scenario.DESTINATION_REVISIT })
        assertTrue(trace.any { it.scenario == V4Scenario.CHANGING_QUALITY && it.phase > 0 })
        assertTrue(trace.any { it.scenario == V4Scenario.MISLEADING_TRANSIENT })
        assertTrue(trace.any { it.scenario == V4Scenario.STABLE_NO_ADVANTAGE })
        trace.forEach { sample ->
            assertTrue(sample.input.allowed.isNotEmpty())
            assertEquals(sample.input.allowed.toSet(), sample.outcomes.keys)
            assertTrue(sample.bestStrategies.isNotEmpty())
        }
        val evaluation = buildV4Evaluation(config)
        assertEquals(4, evaluation.runs.size)
        evaluation.runs.forEach { run ->
            assertEquals(trace.size, run.decisions.size)
            assertEquals(trace.size, run.metrics.getValue("train").samples + run.metrics.getValue("eval").samples)
            assertEquals(run.decisions.sumOf { it.execution.attempts.size }, run.metrics.getValue("train").feedbackObservations + run.metrics.getValue("eval").feedbackObservations)
            assertTrue(run.decisions.all { it.choice.strategy in it.sample.input.allowed })
            assertTrue(run.decisions.all { it.choice.measuredInferenceNanos >= 0L })
        }
        writeV4Evaluation(evaluation)
        println(
            "AEGIS_LOCAL2 verdict=${evaluation.promotionVerdict} " +
                "B_eval_success=${evaluation.runs.first { it.id == "B_RECENT_ADAPTIVE_HEURISTIC" }.metrics.getValue("eval").servedSuccessRate} " +
                "D_eval_success=${evaluation.runs.first { it.id == "D_AEGIS_LOCAL2" }.metrics.getValue("eval").servedSuccessRate} " +
                "D_status=${evaluation.dStatus} " +
                "artifactDir=${v4ArtifactPath()}"
        )
    }

    @Test
    fun policiesReceiveOnlySelectedFeedbackAndRemainSafetyBounded() {
        val evaluation = buildV4Evaluation(V4Config())
        evaluation.runs.forEach { run ->
            assertTrue(run.decisions.all { decision ->
                decision.execution.initial.strategy == decision.choice.strategy &&
                    decision.choice.strategy in decision.sample.input.allowed
            })
            assertTrue(run.decisions.all { decision -> decision.execution.initial.latencyMs > 0L })
            assertEquals(run.decisions.sumOf { it.execution.attempts.size }, run.metrics.getValue("train").feedbackObservations + run.metrics.getValue("eval").feedbackObservations)
        }
    }

    @Test
    fun currentTurboEngineRemainsTheUnmodifiedCReference() {
        val run = runV4Policy(V4CurrentTurboRunner(), buildV4Trace(V4Config()), V4Config())
        assertEquals("C_CURRENT_TURBO_AI_ENGINE", run.id)
        assertEquals("CURRENT_UNTOUCHED", run.status)
        assertEquals(288, run.decisions.size)
        assertTrue(run.decisions.any { it.choice.engineDecision != null })
    }
}
