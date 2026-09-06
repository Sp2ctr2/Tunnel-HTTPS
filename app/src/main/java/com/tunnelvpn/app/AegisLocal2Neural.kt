package com.tunnelvpn.app

import kotlin.math.exp

internal class AegisLocal2ContextSnapshot private constructor(
    private val contextFeatures: FloatArray,
    val contextClass: Long
) {
    fun inputFor(strategy: TurboStrategyId): FloatArray {
        val input = FloatArray(AegisLocal2NeuralModel.INPUT_SIZE)
        contextFeatures.copyInto(input)
        input[ACTION_OFFSET + strategy.ordinal] = 1.0f
        return input
    }

    companion object {
        private const val ACTION_OFFSET = 28

        fun from(context: TurboContext): AegisLocal2ContextSnapshot {
            val values = FloatArray(ACTION_OFFSET)
            values[0] = flag(context.validated)
            values[1] = flag(context.metered)
            values[2] = flag(context.roaming)
            values[3] = flag(context.batterySaver)
            values[4] = flag(context.clientHelloParsed)
            values[5] = flag(context.sniPresent)
            values[6] = flag(context.destinationPort == 443)
            values[7 + context.transport.ordinal.coerceIn(0, 4)] = 1.0f
            values[12] = bucket(context.approximateRttBucket)
            values[13] = bucket(context.recentRetryBucket)
            values[14] = bucket(context.recentSuccessBucket)
            values[15] = bucket(context.recentHandshakeBucket)
            values[16] = bucket(context.resourcePressureBucket)
            values[17 + context.previousFailure.ordinal.coerceIn(0, 9)] = 1.0f
            values[27] = flag(context.latencyProfile.equals("CONSERVATIVE", ignoreCase = true))

            var contextClass = 0L
            fun append(value: Int, width: Int) {
                contextClass = (contextClass shl width) or value.toLong()
            }
            append(if (context.validated) 1 else 0, 1)
            append(if (context.metered) 1 else 0, 1)
            append(if (context.roaming) 1 else 0, 1)
            append(if (context.batterySaver) 1 else 0, 1)
            append(if (context.clientHelloParsed) 1 else 0, 1)
            append(if (context.sniPresent) 1 else 0, 1)
            append(if (context.destinationPort == 443) 1 else 0, 1)
            append(context.transport.ordinal.coerceIn(0, 4), 3)
            append(context.approximateRttBucket.coerceIn(0, 4), 3)
            append(context.recentRetryBucket.coerceIn(0, 4), 3)
            append(context.recentSuccessBucket.coerceIn(0, 4), 3)
            append(context.recentHandshakeBucket.coerceIn(0, 4), 3)
            append(context.resourcePressureBucket.coerceIn(0, 4), 3)
            append(context.previousFailure.ordinal.coerceIn(0, 9), 4)
            append(if (context.latencyProfile.equals("CONSERVATIVE", ignoreCase = true)) 1 else 0, 1)
            return AegisLocal2ContextSnapshot(values, contextClass)
        }

        private fun flag(value: Boolean): Float = if (value) 1.0f else 0.0f

        private fun bucket(value: Int): Float = value.coerceIn(0, 4).toFloat() / 4.0f
    }
}

internal data class AegisLocal2NeuralOutput(
    val successProbability: Float,
    val latencyQuality: Float,
    val tailRisk: Float
) {
    val isFinite: Boolean
        get() = successProbability.isFinite() &&
            latencyQuality.isFinite() &&
            tailRisk.isFinite() &&
            successProbability in 0.0f..1.0f &&
            latencyQuality in 0.0f..1.0f &&
            tailRisk in 0.0f..1.0f

    fun score(): Float {
        return (0.64f * successProbability + 0.27f * latencyQuality - 0.09f * tailRisk)
            .coerceIn(-1.0f, 1.0f)
    }
}

internal data class AegisLocal2TrainingTarget(
    val successProbability: Float,
    val latencyQuality: Float,
    val tailRisk: Float
) {
    fun asArray(): FloatArray = floatArrayOf(successProbability, latencyQuality, tailRisk)

    val isFinite: Boolean
        get() = successProbability.isFinite() &&
            latencyQuality.isFinite() &&
            tailRisk.isFinite() &&
            successProbability in 0.0f..1.0f &&
            latencyQuality in 0.0f..1.0f &&
            tailRisk in 0.0f..1.0f
}

internal data class AegisLocal2PreparationResult(
    val ready: Boolean,
    val attemptedPredictions: Int,
    val elapsedNanos: Long
)

internal class AegisLocal2NeuralModel(
    private val seed: Long,
    private val learningRate: Float,
    private val gradientClip: Float
) {
    private data class ForwardPass(
        val hidden: FloatArray,
        val output: FloatArray
    )

    private var inputWeights = FloatArray(INPUT_SIZE * HIDDEN_SIZE)
    private var hiddenBias = FloatArray(HIDDEN_SIZE)
    private var outputWeights = FloatArray(HIDDEN_SIZE * OUTPUT_SIZE)
    private var outputBias = FloatArray(OUTPUT_SIZE)

    init {
        reset()
    }

    fun reset() {
        val random = AegisLocal2SeededRandom(seed)
        inputWeights = FloatArray(INPUT_SIZE * HIDDEN_SIZE) { random.symmetric(0.16f) }
        hiddenBias = FloatArray(HIDDEN_SIZE)
        outputWeights = FloatArray(HIDDEN_SIZE * OUTPUT_SIZE) { random.symmetric(0.20f) }
        outputBias = FloatArray(OUTPUT_SIZE)
    }

    fun predict(input: FloatArray): AegisLocal2NeuralOutput? {
        if (input.size != INPUT_SIZE || input.any { !it.isFinite() }) return null
        val output = forward(input).output
        val result = AegisLocal2NeuralOutput(output[0], output[1], output[2])
        return result.takeIf { it.isFinite }
    }

    fun prepare(
        predictionLimit: Int = PREPARATION_PREDICTION_LIMIT,
        wallLimitNanos: Long = PREPARATION_WALL_LIMIT_NANOS,
        nanoClock: AegisLocal2NanoClock = AegisLocal2NanoClock { System.nanoTime() }
    ): AegisLocal2PreparationResult {
        val boundedPredictionLimit = predictionLimit.coerceIn(0, PREPARATION_PREDICTION_LIMIT)
        val boundedWallLimitNanos = wallLimitNanos.coerceIn(0L, PREPARATION_WALL_LIMIT_NANOS)
        val startedNanos = nanoClock.nowNanos()
        var attemptedPredictions = 0
        fun result(ready: Boolean): AegisLocal2PreparationResult {
            return AegisLocal2PreparationResult(
                ready = ready,
                attemptedPredictions = attemptedPredictions,
                elapsedNanos = elapsedNanos(startedNanos, nanoClock.nowNanos())
            )
        }
        if (boundedPredictionLimit == 0 || boundedWallLimitNanos == 0L || !isFinite()) {
            return result(false)
        }
        val inputs = preparationInputs()
        while (attemptedPredictions < boundedPredictionLimit) {
            if (attemptedPredictions > 0 &&
                elapsedNanos(startedNanos, nanoClock.nowNanos()) >= boundedWallLimitNanos
            ) {
                break
            }
            val output = predict(inputs[attemptedPredictions % inputs.size])
            attemptedPredictions++
            if (output == null || !output.score().isFinite()) return result(false)
        }
        return result(attemptedPredictions > 0 && isFinite())
    }

    fun train(input: FloatArray, target: AegisLocal2TrainingTarget): Boolean {
        if (input.size != INPUT_SIZE || input.any { !it.isFinite() } || !target.isFinite) return false
        val pass = forward(input)
        if (pass.output.any { !it.isFinite() } || pass.hidden.any { !it.isFinite() }) return false
        val targets = target.asArray()
        val outputDelta = FloatArray(OUTPUT_SIZE) { index ->
            val prediction = pass.output[index]
            clip((prediction - targets[index]) * prediction * (1.0f - prediction))
        }
        if (outputDelta.any { !it.isFinite() }) return false
        val hiddenDelta = FloatArray(HIDDEN_SIZE)
        for (hiddenIndex in 0 until HIDDEN_SIZE) {
            var propagated = 0.0f
            for (outputIndex in 0 until OUTPUT_SIZE) {
                propagated += outputWeights[hiddenIndex * OUTPUT_SIZE + outputIndex] * outputDelta[outputIndex]
            }
            hiddenDelta[hiddenIndex] = if (pass.hidden[hiddenIndex] > 0.0f) clip(propagated) else 0.0f
        }
        if (hiddenDelta.any { !it.isFinite() }) return false

        val nextInputWeights = inputWeights.copyOf()
        val nextHiddenBias = hiddenBias.copyOf()
        val nextOutputWeights = outputWeights.copyOf()
        val nextOutputBias = outputBias.copyOf()

        for (hiddenIndex in 0 until HIDDEN_SIZE) {
            for (outputIndex in 0 until OUTPUT_SIZE) {
                val index = hiddenIndex * OUTPUT_SIZE + outputIndex
                val gradient = clip(outputDelta[outputIndex] * pass.hidden[hiddenIndex])
                nextOutputWeights[index] = updated(nextOutputWeights[index], gradient) ?: return false
            }
        }
        for (outputIndex in 0 until OUTPUT_SIZE) {
            nextOutputBias[outputIndex] = updated(nextOutputBias[outputIndex], outputDelta[outputIndex]) ?: return false
        }
        for (inputIndex in 0 until INPUT_SIZE) {
            for (hiddenIndex in 0 until HIDDEN_SIZE) {
                val index = inputIndex * HIDDEN_SIZE + hiddenIndex
                val gradient = clip(hiddenDelta[hiddenIndex] * input[inputIndex])
                nextInputWeights[index] = updated(nextInputWeights[index], gradient) ?: return false
            }
        }
        for (hiddenIndex in 0 until HIDDEN_SIZE) {
            nextHiddenBias[hiddenIndex] = updated(nextHiddenBias[hiddenIndex], hiddenDelta[hiddenIndex]) ?: return false
        }

        if (!allFinite(nextInputWeights, nextHiddenBias, nextOutputWeights, nextOutputBias)) return false
        inputWeights = nextInputWeights
        hiddenBias = nextHiddenBias
        outputWeights = nextOutputWeights
        outputBias = nextOutputBias
        return true
    }

    fun isFinite(): Boolean = allFinite(inputWeights, hiddenBias, outputWeights, outputBias)

    private fun forward(input: FloatArray): ForwardPass {
        val hidden = FloatArray(HIDDEN_SIZE)
        for (hiddenIndex in 0 until HIDDEN_SIZE) {
            var value = hiddenBias[hiddenIndex]
            for (inputIndex in 0 until INPUT_SIZE) {
                value += input[inputIndex] * inputWeights[inputIndex * HIDDEN_SIZE + hiddenIndex]
            }
            hidden[hiddenIndex] = if (value > 0.0f) value.coerceAtMost(ACTIVATION_LIMIT) else 0.0f
        }
        val output = FloatArray(OUTPUT_SIZE)
        for (outputIndex in 0 until OUTPUT_SIZE) {
            var value = outputBias[outputIndex]
            for (hiddenIndex in 0 until HIDDEN_SIZE) {
                value += hidden[hiddenIndex] * outputWeights[hiddenIndex * OUTPUT_SIZE + outputIndex]
            }
            output[outputIndex] = sigmoid(value)
        }
        return ForwardPass(hidden, output)
    }

    private fun updated(weight: Float, gradient: Float): Float? {
        if (!weight.isFinite() || !gradient.isFinite()) return null
        val value = (weight - learningRate * gradient).coerceIn(-WEIGHT_LIMIT, WEIGHT_LIMIT)
        return value.takeIf { it.isFinite() }
    }

    private fun clip(value: Float): Float = value.coerceIn(-gradientClip, gradientClip)

    private fun sigmoid(value: Float): Float {
        val bounded = value.coerceIn(-EXP_LIMIT, EXP_LIMIT).toDouble()
        return (1.0 / (1.0 + exp(-bounded))).toFloat()
    }

    private fun allFinite(vararg arrays: FloatArray): Boolean {
        return arrays.all { values -> values.all(Float::isFinite) }
    }

    private fun preparationInputs(): Array<FloatArray> {
        val strategies = TurboStrategyId.values()
        val actionOffset = INPUT_SIZE - strategies.size
        return Array(strategies.size + 1) { index ->
            FloatArray(INPUT_SIZE).apply {
                if (index > 0) this[actionOffset + strategies[index - 1].ordinal] = 1.0f
            }
        }
    }

    private fun elapsedNanos(startedNanos: Long, completedNanos: Long): Long {
        val elapsed = completedNanos - startedNanos
        return if (elapsed < 0L) Long.MAX_VALUE else elapsed
    }

    companion object {
        const val INPUT_SIZE = 32
        const val HIDDEN_SIZE = 16
        const val OUTPUT_SIZE = 3
        const val PARAMETER_COUNT = INPUT_SIZE * HIDDEN_SIZE + HIDDEN_SIZE +
            HIDDEN_SIZE * OUTPUT_SIZE + OUTPUT_SIZE
        internal const val PREPARATION_PREDICTION_LIMIT = 32
        internal const val PREPARATION_WALL_LIMIT_NANOS = 50_000_000L
        private const val WEIGHT_LIMIT = 4.0f
        private const val ACTIVATION_LIMIT = 16.0f
        private const val EXP_LIMIT = 12.0f
    }
}

private class AegisLocal2SeededRandom(seed: Long) {
    private var state = if (seed == 0L) 0x51A7E2D39B4C6F01L else seed

    fun symmetric(limit: Float): Float {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        val fraction = (state ushr 40).toDouble() / 16_777_215.0
        return ((fraction * 2.0 - 1.0) * limit).toFloat()
    }
}

internal data class AegisLocal2OutcomeEstimate(
    val observations: Int,
    val score: Float,
    val successProbability: Float,
    val latencyQuality: Float,
    val tailRisk: Float
)

internal class AegisLocal2OutcomeMap(
    private val maximumSize: Int,
    private val ttlNanos: Long
) {
    private data class Key(
        val contextClass: Long,
        val strategy: TurboStrategyId
    )

    private class Bucket(
        val contextClass: Long,
        val strategy: TurboStrategyId,
        nowNanos: Long
    ) {
        var observations = 0
        var attributedObservations = 0
        var successes = 0
        var successEma = 0.0f
        var latencyQualityEma = 0.0f
        var tailRiskEma = 0.0f
        val failureCounts = IntArray(AegisLocal2FailureClass.values().size)
        var lastUpdatedNanos = nowNanos
    }

    private val buckets = LinkedHashMap<Key, Bucket>(16, 0.75f, true)
    var evictions: Long = 0L
        private set

    val size: Int
        get() = buckets.size

    fun clear() {
        buckets.clear()
    }

    fun purgeExpired(nowNanos: Long) {
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next().value
            if (expired(nowNanos, bucket.lastUpdatedNanos)) {
                iterator.remove()
                evictions = saturatingIncrement(evictions)
            }
        }
    }

    fun record(
        contextClass: Long,
        strategy: TurboStrategyId,
        target: AegisLocal2TrainingTarget,
        failureClass: AegisLocal2FailureClass,
        attributable: Boolean,
        nowNanos: Long
    ) {
        purgeExpired(nowNanos)
        val key = Key(contextClass, strategy)
        var bucket = buckets[key]
        if (bucket == null) {
            while (buckets.size >= maximumSize) {
                val iterator = buckets.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
                evictions = saturatingIncrement(evictions)
            }
            bucket = Bucket(contextClass, strategy, nowNanos)
            buckets[key] = bucket
        }
        bucket.observations = saturatingIncrement(bucket.observations)
        if (failureClass == AegisLocal2FailureClass.SUCCESS) {
            bucket.successes = saturatingIncrement(bucket.successes)
        }
        val failureIndex = failureClass.ordinal
        bucket.failureCounts[failureIndex] = saturatingIncrement(bucket.failureCounts[failureIndex])
        if (attributable) {
            val first = bucket.attributedObservations == 0
            bucket.attributedObservations = saturatingIncrement(bucket.attributedObservations)
            bucket.successEma = ema(bucket.successEma, target.successProbability, first)
            bucket.latencyQualityEma = ema(bucket.latencyQualityEma, target.latencyQuality, first)
            bucket.tailRiskEma = ema(bucket.tailRiskEma, target.tailRisk, first)
        }
        bucket.lastUpdatedNanos = nowNanos
    }

    fun estimate(
        contextClass: Long,
        strategy: TurboStrategyId,
        nowNanos: Long
    ): AegisLocal2OutcomeEstimate? {
        purgeExpired(nowNanos)
        val bucket = buckets[Key(contextClass, strategy)] ?: return null
        if (bucket.attributedObservations == 0) return null
        val score = (0.64f * bucket.successEma +
            0.27f * bucket.latencyQualityEma -
            0.09f * bucket.tailRiskEma).coerceIn(-1.0f, 1.0f)
        return AegisLocal2OutcomeEstimate(
            observations = bucket.attributedObservations,
            score = score,
            successProbability = bucket.successEma,
            latencyQuality = bucket.latencyQualityEma,
            tailRisk = bucket.tailRiskEma
        )
    }

    fun snapshots(nowNanos: Long): List<AegisLocal2OutcomeBucketSnapshot> {
        purgeExpired(nowNanos)
        return buckets.values.map { bucket ->
            val failures = linkedMapOf<AegisLocal2FailureClass, Int>()
            AegisLocal2FailureClass.values().forEach { failureClass ->
                val count = bucket.failureCounts[failureClass.ordinal]
                if (count > 0) failures[failureClass] = count
            }
            AegisLocal2OutcomeBucketSnapshot(
                contextClass = bucket.contextClass,
                strategy = bucket.strategy,
                observations = bucket.observations,
                attributedObservations = bucket.attributedObservations,
                successes = bucket.successes,
                successEma = finiteUnit(bucket.successEma),
                latencyQualityEma = finiteUnit(bucket.latencyQualityEma),
                tailRiskEma = finiteUnit(bucket.tailRiskEma),
                failureCounts = failures
            )
        }.sortedWith(compareBy<AegisLocal2OutcomeBucketSnapshot> { it.contextClass }
            .thenBy { it.strategy.stableId })
    }

    private fun ema(previous: Float, observed: Float, first: Boolean): Float {
        if (first) return finiteUnit(observed)
        return finiteUnit(previous + EMA_ALPHA * (observed - previous))
    }

    private fun finiteUnit(value: Float): Float {
        return if (value.isFinite()) value.coerceIn(0.0f, 1.0f) else 0.0f
    }

    private fun expired(nowNanos: Long, updatedNanos: Long): Boolean {
        val elapsed = nowNanos - updatedNanos
        return elapsed < 0L || elapsed >= ttlNanos
    }

    private fun saturatingIncrement(value: Int): Int {
        return if (value == MAX_BUCKET_COUNT) value else value + 1
    }

    private fun saturatingIncrement(value: Long): Long {
        return if (value == Long.MAX_VALUE) value else value + 1L
    }

    companion object {
        private const val EMA_ALPHA = 0.20f
        private const val MAX_BUCKET_COUNT = 1_000_000
    }
}
