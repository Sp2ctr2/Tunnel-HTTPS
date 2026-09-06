package com.tunnelvpn.app

import kotlin.math.ln

enum class TurboStrategyId(
    val stableId: String,
    val fragmentationMode: TlsClientHello.FragmentationMode
) {
    TLS_SNI_MULTI_V1("tls-sni-multi-v1", TlsClientHello.FragmentationMode.SNI_MULTI),
    TLS_HOSTNAME_V1("tls-hostname-v1", TlsClientHello.FragmentationMode.HOSTNAME),
    TLS_SINGLE_SAFE_V1("tls-single-safe-v1", TlsClientHello.FragmentationMode.SINGLE_SAFE),
    TLS_PLAIN_V1("tls-plain-v1", TlsClientHello.FragmentationMode.NONE);

    companion object {
        fun fromMode(mode: TlsClientHello.FragmentationMode): TurboStrategyId {
            return values().first { it.fragmentationMode == mode }
        }

        fun fromStableId(value: String): TurboStrategyId? = values().firstOrNull { it.stableId == value }
    }
}

enum class TurboNetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    UNKNOWN
}

enum class TurboFailureCategory {
    NONE,
    CONNECT,
    TIMEOUT,
    TLS_ALERT,
    WRITE,
    SERVER_CLOSED,
    HANDSHAKE_BUDGET,
    NETWORK_CHANGED,
    CANCELLED,
    OTHER
}

enum class TurboDecisionPolicy {
    LEARNED,
    EXPLORATION,
    BASELINE,
    DEFAULT_SAFE,
    BASELINE_FALLBACK
}

enum class TurboConfidence {
    LOW,
    MEDIUM,
    HIGH
}

data class TurboAiFlags(
    val enabled: Boolean,
    val explorationEnabled: Boolean = true,
    val modelUpdatesEnabled: Boolean = true,
    val developerDiagnosticsEnabled: Boolean = false,
    val forceBaseline: Boolean = false,
    val aegisShadowEnabled: Boolean = true
)

data class TurboContext(
    val destinationKey: String,
    val destinationPort: Int,
    val transport: TurboNetworkTransport,
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
    val previousFailure: TurboFailureCategory = TurboFailureCategory.NONE
)

data class TurboDecisionReason(
    val code: String,
    val confidence: TurboConfidence,
    val eligibleStrategyIds: List<String>,
    val excludedReasonCodes: List<String> = emptyList()
)

data class TurboDecision(
    val strategy: TurboStrategyId,
    val fallbackOrder: List<TurboStrategyId>,
    val policy: TurboDecisionPolicy,
    val reason: TurboDecisionReason,
    val contextKey: String,
    val sharedContextKey: String,
    val batterySaver: Boolean,
    val decisionOverheadNanos: Long,
    val aegisToken: Long = 0L,
    val networkGeneration: Long = -1L
)

data class TurboOutcome(
    val contextKey: String,
    val strategy: TurboStrategyId,
    val policy: TurboDecisionPolicy,
    val success: Boolean,
    val handshakeLatencyMs: Long,
    val connectionDurationMs: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val retries: Int,
    val timedOut: Boolean,
    val abruptDisconnect: Boolean,
    val fallbackUsed: Boolean,
    val decisionOverheadNanos: Long,
    val batterySaver: Boolean,
    val failureCategory: TurboFailureCategory,
    val completedAtMs: Long,
    val sharedContextKey: String = "",
    val aegisToken: Long = 0L,
    val networkGeneration: Long = -1L
)

data class TurboModelEntry(
    val contextKey: String,
    val strategy: TurboStrategyId,
    val weightedCount: Double,
    val meanReward: Double,
    val weightedSuccesses: Double,
    val latencyEmaMs: Double,
    val lastUpdatedMs: Long,
    val lastAccessMs: Long
)

data class TurboPersistedState(
    val schemaVersion: Int = TurboModelCodec.SCHEMA_VERSION,
    val totalSamples: Long = 0L,
    val entries: List<TurboModelEntry> = emptyList()
)

data class TurboDecodedState(
    val state: TurboPersistedState,
    val corrupted: Boolean
)

data class TurboEvaluationSnapshot(
    val policy: TurboDecisionPolicy,
    val samples: Long,
    val successes: Long,
    val medianHandshakeMs: Long,
    val retries: Long,
    val fallbacks: Long,
    val averageReward: Double,
    val averageDecisionOverheadMicros: Double
) {
    val successRate: Double
        get() = if (samples == 0L) 0.0 else successes.toDouble() / samples.toDouble()
}

data class TurboAiStatus(
    val available: Boolean,
    val enabled: Boolean,
    val samples: Long,
    val confidence: TurboConfidence,
    val state: String,
    val fallbackReason: String = "",
    val aegisMode: String = "AI_DISABLED",
    val aegisDecisions: Long = 0L,
    val aegisObservations: Long = 0L,
    val aegisInferenceNanos: Long = 0L,
    val aegisInferenceP50Nanos: Long = 0L,
    val aegisInferenceP95Nanos: Long = 0L,
    val aegisModelBytes: Int = 0,
    val aegisRuntimeDecisionCount: Long = 0L,
    val aegisRuntimeDecisionNanos: Long = 0L,
    val aegisRuntimeDecisionMaxNanos: Long = 0L
)

fun interface TurboClock {
    fun nowMs(): Long
}

interface TurboRandom {
    fun nextDouble(): Double
    fun nextInt(bound: Int): Int
}

class DefaultTurboRandom(
    private val delegate: kotlin.random.Random = kotlin.random.Random.Default
) : TurboRandom {
    override fun nextDouble(): Double = delegate.nextDouble()
    override fun nextInt(bound: Int): Int = delegate.nextInt(bound)
}

class TurboRewardCalculator {
    fun calculate(outcome: TurboOutcome): Double {
        val retryPenalty = 0.08 * outcome.retries.coerceIn(0, 3) / 3.0
        val fallbackPenalty = if (outcome.fallbackUsed) 0.06 else 0.0
        val cpuPenalty = 0.06 * (outcome.decisionOverheadNanos.toDouble() / 1_000_000.0).coerceIn(0.0, 1.0)
        val batteryPenalty = if (outcome.batterySaver) 0.02 else 0.0
        if (!outcome.success) {
            val timeoutPenalty = if (outcome.timedOut) 0.15 else 0.0
            val abruptPenalty = if (outcome.abruptDisconnect) 0.08 else 0.0
            return (-0.70 - timeoutPenalty - abruptPenalty - retryPenalty - fallbackPenalty -
                cpuPenalty - batteryPenalty).coerceIn(-1.0, 1.0)
        }

        val latencyScore = 1.0 - (outcome.handshakeLatencyMs.toDouble() / 4_000.0).coerceIn(0.0, 1.0)
        var measuredScore = 0.20 * latencyScore
        var measuredWeight = 0.20
        val durationMs = outcome.connectionDurationMs.coerceAtLeast(0L)
        val stabilityMeasured = outcome.abruptDisconnect || durationMs >= MIN_STABILITY_DURATION_MS
        val stabilityScore = (durationMs.toDouble() / 30_000.0).coerceIn(0.0, 1.0)
        if (stabilityMeasured) {
            measuredScore += 0.15 * stabilityScore
            measuredWeight += 0.15
        }
        val transferredBytes = saturatingAdd(
            outcome.bytesUp.coerceAtLeast(0L),
            outcome.bytesDown.coerceAtLeast(0L)
        )
        if (durationMs >= MIN_THROUGHPUT_DURATION_MS && transferredBytes >= MIN_THROUGHPUT_BYTES) {
            val durationSeconds = durationMs.toDouble() / 1_000.0
            val bytesPerSecond = transferredBytes.toDouble() / durationSeconds
            val throughputScore = (ln(1.0 + bytesPerSecond) / ln(1.0 + 5_000_000.0)).coerceIn(0.0, 1.0)
            measuredScore += 0.12 * throughputScore
            measuredWeight += 0.12
        }
        val normalizedMeasuredScore = if (measuredWeight > 0.0) {
            measuredScore * TOTAL_QUALITY_WEIGHT / measuredWeight
        } else {
            0.0
        }
        val abruptPenalty = if (outcome.abruptDisconnect) 0.12 * (1.0 - stabilityScore) else 0.0
        return (0.45 +
            normalizedMeasuredScore -
            retryPenalty -
            fallbackPenalty -
            cpuPenalty -
            batteryPenalty -
            abruptPenalty).coerceIn(-1.0, 1.0)
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        return if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }

    companion object {
        private const val MIN_STABILITY_DURATION_MS = 5_000L
        private const val MIN_THROUGHPUT_DURATION_MS = 1_000L
        private const val MIN_THROUGHPUT_BYTES = 32L * 1_024L
        private const val TOTAL_QUALITY_WEIGHT = 0.47
    }
}
