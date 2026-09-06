package com.tunnelvpn.app

internal enum class AegisLocal2RequestedMode {
    OFF,
    SHADOW,
    ACTIVE
}

internal enum class AegisLocal2Mode {
    OFF,
    SHADOW,
    KILLED
}

internal enum class AegisLocal2DecisionReason {
    OFF,
    MODEL_KILLED,
    NO_SAFE_CANDIDATES,
    STALE_EPOCH,
    SINGLE_CANDIDATE,
    COLD_START,
    UNSTABLE_NETWORK,
    BASELINE_BEST,
    LOW_CONFIDENCE,
    SHADOW_SUGGESTION,
    INFERENCE_LIMIT
}

internal enum class AegisLocal2FailureClass {
    SUCCESS,
    CONNECT,
    TIMEOUT,
    TLS_ALERT,
    WRITE,
    SERVER_CLOSED,
    HANDSHAKE_BUDGET,
    NETWORK_CHANGED,
    CANCELLED,
    OTHER;

    val isStrategyAttributable: Boolean
        get() = when (this) {
            SUCCESS,
            TIMEOUT,
            TLS_ALERT,
            HANDSHAKE_BUDGET -> true
            CONNECT,
            WRITE,
            SERVER_CLOSED,
            NETWORK_CHANGED,
            CANCELLED,
            OTHER -> false
        }
}

internal enum class AegisLocal2ObserveStatus {
    RECORDED_AND_TRAINED,
    RECORDED_NOT_ATTRIBUTABLE,
    IGNORED_ZERO_TOKEN,
    IGNORED_UNKNOWN_TOKEN,
    IGNORED_STALE_EPOCH,
    IGNORED_TOKEN_MISMATCH,
    IGNORED_STRATEGY_NOT_ALLOWED,
    IGNORED_DUPLICATE_STRATEGY,
    IGNORED_INCONSISTENT_OUTCOME,
    IGNORED_DISABLED,
    MODEL_KILLED
}

internal data class AegisLocal2Config(
    val requestedMode: AegisLocal2RequestedMode = AegisLocal2RequestedMode.SHADOW,
    val maxPendingTokens: Int = 256,
    val maxOutcomeBuckets: Int = 192,
    val pendingTokenTtlMs: Long = 120_000L,
    val outcomeBucketTtlMs: Long = 1_800_000L,
    val minimumTotalObservations: Int = 16,
    val minimumActionObservations: Int = 4,
    val confidenceThreshold: Float = 0.62f,
    val minimumScoreMargin: Float = 0.025f,
    val learningRate: Float = 0.015f,
    val gradientClip: Float = 1.0f,
    val softInferenceLimitNanos: Long = 1_500_000L,
    val hardInferenceLimitNanos: Long = 10_000_000L,
    val maxConsecutiveLatencyViolations: Int = 3,
    val modelSeed: Long = 0x41454749534C4F32L
) {
    init {
        require(maxPendingTokens in 1..1_024)
        require(maxOutcomeBuckets in 1..1_024)
        require(pendingTokenTtlMs in 1L..86_400_000L)
        require(outcomeBucketTtlMs in 1L..86_400_000L)
        require(minimumTotalObservations in 1..10_000)
        require(minimumActionObservations in 1..1_000)
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0.0f..1.0f)
        require(minimumScoreMargin.isFinite() && minimumScoreMargin in 0.0f..1.0f)
        require(learningRate.isFinite() && learningRate in 0.000001f..0.25f)
        require(gradientClip.isFinite() && gradientClip in 0.01f..10.0f)
        require(softInferenceLimitNanos in 1L..1_000_000_000L)
        require(hardInferenceLimitNanos in softInferenceLimitNanos..2_000_000_000L)
        require(maxConsecutiveLatencyViolations in 1..100)
    }
}

internal data class AegisLocal2CandidateScore(
    val strategy: TurboStrategyId,
    val score: Float,
    val successProbability: Float,
    val latencyQuality: Float,
    val tailRisk: Float,
    val observations: Int
)

internal data class AegisLocal2Decision(
    val token: Long,
    val suggestion: TurboStrategyId,
    val baseline: TurboStrategyId,
    val confidence: TurboConfidence,
    val confidenceValue: Float,
    val mode: AegisLocal2Mode,
    val reason: AegisLocal2DecisionReason,
    val inferenceNanos: Long,
    val candidateScores: List<AegisLocal2CandidateScore>,
    val stats: AegisLocal2Stats
)

internal data class AegisLocal2ObserveResult(
    val status: AegisLocal2ObserveStatus,
    val trained: Boolean,
    val tokenRetired: Boolean,
    val failureClass: AegisLocal2FailureClass?,
    val stats: AegisLocal2Stats
)

internal data class AegisLocal2OutcomeBucketSnapshot(
    val contextClass: Long,
    val strategy: TurboStrategyId,
    val observations: Int,
    val attributedObservations: Int,
    val successes: Int,
    val successEma: Float,
    val latencyQualityEma: Float,
    val tailRiskEma: Float,
    val failureCounts: Map<AegisLocal2FailureClass, Int>
)

internal data class AegisLocal2Stats(
    val requestedMode: AegisLocal2RequestedMode,
    val mode: AegisLocal2Mode,
    val epoch: Long,
    val decisions: Long,
    val observations: Long,
    val trainedObservations: Long,
    val rejectedObservations: Long,
    val duplicateObservations: Long,
    val staleObservations: Long,
    val pendingTokens: Int,
    val outcomeBuckets: Int,
    val pendingTokenEvictions: Long,
    val outcomeBucketEvictions: Long,
    val resetCount: Long,
    val killSwitchTrips: Long,
    val activeRequestsDowngraded: Long,
    val inferenceCount: Long,
    val inferenceNanos: Long,
    val lastInferenceNanos: Long,
    val maximumInferenceNanos: Long,
    val modelParameters: Int,
    val modelConfidence: TurboConfidence
)

internal fun interface AegisLocal2NanoClock {
    fun nowNanos(): Long
}
