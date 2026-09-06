package com.tunnelvpn.app

internal class AegisLocal2Engine(
    private val config: AegisLocal2Config = AegisLocal2Config(),
    private val nanoClock: AegisLocal2NanoClock = AegisLocal2NanoClock { System.nanoTime() }
) {
    private class PendingDecision(
        val epoch: Long,
        val context: AegisLocal2ContextSnapshot,
        val allowedMask: Int,
        val baseline: TurboStrategyId,
        val suggestion: TurboStrategyId,
        val issuedAtNanos: Long
    ) {
        var observedMask: Int = 0
    }

    private val safetyPolicy = TurboSafetyPolicy()
    private val model = AegisLocal2NeuralModel(
        seed = config.modelSeed,
        learningRate = config.learningRate,
        gradientClip = config.gradientClip
    )
    private val outcomeMap = AegisLocal2OutcomeMap(
        maximumSize = config.maxOutcomeBuckets,
        ttlNanos = config.outcomeBucketTtlMs * NANOS_PER_MILLISECOND
    )
    private val pending = LinkedHashMap<Long, PendingDecision>()
    private val actionObservations = IntArray(TurboStrategyId.values().size)
    private var requestedMode = config.requestedMode
    private var mode = if (requestedMode == AegisLocal2RequestedMode.OFF) {
        AegisLocal2Mode.OFF
    } else {
        AegisLocal2Mode.SHADOW
    }
    private var hasEpoch = false
    private var currentEpoch = 0L
    private var nextToken = 0L
    private var epochTrainedObservations = 0
    private var consecutiveLatencyViolations = 0
    private var decisions = 0L
    private var observations = 0L
    private var trainedObservations = 0L
    private var rejectedObservations = 0L
    private var duplicateObservations = 0L
    private var staleObservations = 0L
    private var pendingTokenEvictions = 0L
    private var resetCount = 0L
    private var killSwitchTrips = 0L
    private var activeRequestsDowngraded = if (requestedMode == AegisLocal2RequestedMode.ACTIVE) 1L else 0L
    private var inferenceCount = 0L
    private var inferenceNanos = 0L
    private var lastInferenceNanos = 0L
    private var maximumInferenceNanos = 0L

    @Synchronized
    fun prepare(): AegisLocal2PreparationResult {
        return model.prepare(nanoClock = nanoClock)
    }

    @Synchronized
    fun decide(
        context: TurboContext,
        allowedCandidates: List<TurboStrategyId>,
        baselineDecision: TurboDecision,
        epoch: Long
    ): AegisLocal2Decision {
        val nowNanos = nanoClock.nowNanos()
        val epochAccepted = ensureEpochLocked(epoch)
        purgePendingLocked(nowNanos)
        outcomeMap.purgeExpired(nowNanos)
        decisions = saturatingIncrement(decisions)
        val baseline = baselineDecision.strategy
        if (!epochAccepted) {
            return decisionWithoutInference(baseline, AegisLocal2DecisionReason.STALE_EPOCH)
        }
        if (mode == AegisLocal2Mode.OFF) {
            return decisionWithoutInference(baseline, AegisLocal2DecisionReason.OFF)
        }
        if (mode == AegisLocal2Mode.KILLED) {
            return decisionWithoutInference(baseline, AegisLocal2DecisionReason.MODEL_KILLED)
        }

        val safety = safetyPolicy.eligibleStrategies(context)
        val eligibleSet = safety.eligible.toHashSet()
        val candidates = allowedCandidates.asSequence()
            .distinct()
            .filter { it in eligibleSet }
            .toList()
        if (candidates.isEmpty() || baseline !in candidates) {
            return decisionWithoutInference(baseline, AegisLocal2DecisionReason.NO_SAFE_CANDIDATES)
        }
        val snapshot = AegisLocal2ContextSnapshot.from(context)
        val inferenceStartedNanos = nanoClock.nowNanos()
        if (!model.isFinite()) {
            val elapsed = elapsedNanos(inferenceStartedNanos, nanoClock.nowNanos())
            recordInferenceLocked(elapsed)
            tripKillSwitchLocked()
            return decisionWithoutInference(
                baseline = baseline,
                reason = AegisLocal2DecisionReason.MODEL_KILLED,
                inferenceNanosForDecision = elapsed
            )
        }
        val candidateScores = ArrayList<AegisLocal2CandidateScore>(candidates.size)
        for (strategy in candidates) {
            val prediction = model.predict(snapshot.inputFor(strategy))
            if (prediction == null) {
                val elapsed = elapsedNanos(inferenceStartedNanos, nanoClock.nowNanos())
                recordInferenceLocked(elapsed)
                tripKillSwitchLocked()
                return decisionWithoutInference(
                    baseline = baseline,
                    reason = AegisLocal2DecisionReason.MODEL_KILLED,
                    inferenceNanosForDecision = elapsed
                )
            }
            val estimate = outcomeMap.estimate(snapshot.contextClass, strategy, nowNanos)
            val empiricalWeight = estimate?.observations
                ?.toFloat()
                ?.div(16.0f)
                ?.coerceIn(0.0f, MAX_EMPIRICAL_WEIGHT)
                ?: 0.0f
            val modelWeight = 1.0f - empiricalWeight
            val success = blend(prediction.successProbability, estimate?.successProbability, modelWeight)
            val latency = blend(prediction.latencyQuality, estimate?.latencyQuality, modelWeight)
            val tail = blend(prediction.tailRisk, estimate?.tailRisk, modelWeight)
            val score = (modelWeight * prediction.score() +
                empiricalWeight * (estimate?.score ?: prediction.score()) +
                if (strategy == baseline) BASELINE_PRIOR else 0.0f).coerceIn(-1.0f, 1.0f)
            if (!success.isFinite() || !latency.isFinite() || !tail.isFinite() || !score.isFinite()) {
                val elapsed = elapsedNanos(inferenceStartedNanos, nanoClock.nowNanos())
                recordInferenceLocked(elapsed)
                tripKillSwitchLocked()
                return decisionWithoutInference(
                    baseline = baseline,
                    reason = AegisLocal2DecisionReason.MODEL_KILLED,
                    inferenceNanosForDecision = elapsed
                )
            }
            candidateScores += AegisLocal2CandidateScore(
                strategy = strategy,
                score = score,
                successProbability = success,
                latencyQuality = latency,
                tailRisk = tail,
                observations = estimate?.observations ?: 0
            )
        }
        val measuredInferenceNanos = elapsedNanos(inferenceStartedNanos, nanoClock.nowNanos())
        recordInferenceLocked(measuredInferenceNanos)
        val overSoftLimit = measuredInferenceNanos > config.softInferenceLimitNanos
        if (overSoftLimit) {
            consecutiveLatencyViolations = saturatingIncrement(consecutiveLatencyViolations)
        } else {
            consecutiveLatencyViolations = 0
        }
        if (measuredInferenceNanos > config.hardInferenceLimitNanos ||
            consecutiveLatencyViolations >= config.maxConsecutiveLatencyViolations
        ) {
            tripKillSwitchLocked()
            return AegisLocal2Decision(
                token = 0L,
                suggestion = baseline,
                baseline = baseline,
                confidence = TurboConfidence.LOW,
                confidenceValue = 0.0f,
                mode = mode,
                reason = AegisLocal2DecisionReason.INFERENCE_LIMIT,
                inferenceNanos = measuredInferenceNanos,
                candidateScores = candidateScores,
                stats = statsLocked()
            )
        }

        val baselineScore = candidateScores.first { it.strategy == baseline }
        var best = baselineScore
        candidateScores.forEach { candidate ->
            if (candidate.score > best.score + SCORE_EPSILON) best = candidate
        }
        val margin = (best.score - baselineScore.score).coerceAtLeast(0.0f)
        val confidenceValue = confidenceValueLocked(best, baselineScore, context, margin)
        val confidence = confidenceFromValue(confidenceValue)
        val reason: AegisLocal2DecisionReason
        val suggestion: TurboStrategyId
        when {
            overSoftLimit -> {
                suggestion = baseline
                reason = AegisLocal2DecisionReason.INFERENCE_LIMIT
            }
            candidates.size == 1 -> {
                suggestion = baseline
                reason = AegisLocal2DecisionReason.SINGLE_CANDIDATE
            }
            !context.validated || context.roaming -> {
                suggestion = baseline
                reason = AegisLocal2DecisionReason.UNSTABLE_NETWORK
            }
            epochTrainedObservations < config.minimumTotalObservations ||
                actionObservations[best.strategy.ordinal] < config.minimumActionObservations ||
                actionObservations[baseline.ordinal] < config.minimumActionObservations -> {
                suggestion = baseline
                reason = AegisLocal2DecisionReason.COLD_START
            }
            best.strategy == baseline -> {
                suggestion = baseline
                reason = AegisLocal2DecisionReason.BASELINE_BEST
            }
            confidenceValue < config.confidenceThreshold || margin < config.minimumScoreMargin -> {
                suggestion = baseline
                reason = AegisLocal2DecisionReason.LOW_CONFIDENCE
            }
            else -> {
                suggestion = best.strategy
                reason = AegisLocal2DecisionReason.SHADOW_SUGGESTION
            }
        }
        val token = issueTokenLocked(
            epoch = epoch,
            context = snapshot,
            candidates = candidates,
            baseline = baseline,
            suggestion = suggestion,
            nowNanos = nanoClock.nowNanos()
        )
        return AegisLocal2Decision(
            token = token,
            suggestion = suggestion,
            baseline = baseline,
            confidence = confidence,
            confidenceValue = confidenceValue,
            mode = mode,
            reason = reason,
            inferenceNanos = measuredInferenceNanos,
            candidateScores = candidateScores,
            stats = statsLocked()
        )
    }

    @Synchronized
    fun observe(token: Long, outcome: TurboOutcome, epoch: Long): AegisLocal2ObserveResult {
        val nowNanos = nanoClock.nowNanos()
        purgePendingLocked(nowNanos)
        outcomeMap.purgeExpired(nowNanos)
        if (token == 0L) {
            return rejectedResult(AegisLocal2ObserveStatus.IGNORED_ZERO_TOKEN)
        }
        if (!hasEpoch || epoch != currentEpoch) {
            pending.remove(token)
            rejectedObservations = saturatingIncrement(rejectedObservations)
            staleObservations = saturatingIncrement(staleObservations)
            return observeResult(AegisLocal2ObserveStatus.IGNORED_STALE_EPOCH)
        }
        if (mode == AegisLocal2Mode.OFF) {
            return rejectedResult(AegisLocal2ObserveStatus.IGNORED_DISABLED)
        }
        if (mode == AegisLocal2Mode.KILLED) {
            return rejectedResult(AegisLocal2ObserveStatus.MODEL_KILLED)
        }
        val pendingDecision = pending[token]
            ?: return rejectedResult(AegisLocal2ObserveStatus.IGNORED_UNKNOWN_TOKEN)
        if (pendingDecision.epoch != epoch) {
            pending.remove(token)
            rejectedObservations = saturatingIncrement(rejectedObservations)
            staleObservations = saturatingIncrement(staleObservations)
            return observeResult(AegisLocal2ObserveStatus.IGNORED_STALE_EPOCH)
        }
        if (outcome.aegisToken != token) {
            return rejectedResult(AegisLocal2ObserveStatus.IGNORED_TOKEN_MISMATCH)
        }
        if (outcome.success && (outcome.timedOut || outcome.failureCategory != TurboFailureCategory.NONE)) {
            return rejectedResult(AegisLocal2ObserveStatus.IGNORED_INCONSISTENT_OUTCOME)
        }
        val strategyBit = strategyBit(outcome.strategy)
        if (pendingDecision.allowedMask and strategyBit == 0) {
            return rejectedResult(AegisLocal2ObserveStatus.IGNORED_STRATEGY_NOT_ALLOWED)
        }
        if (pendingDecision.observedMask and strategyBit != 0) {
            rejectedObservations = saturatingIncrement(rejectedObservations)
            duplicateObservations = saturatingIncrement(duplicateObservations)
            return observeResult(AegisLocal2ObserveStatus.IGNORED_DUPLICATE_STRATEGY)
        }
        pendingDecision.observedMask = pendingDecision.observedMask or strategyBit
        val failureClass = failureClass(outcome)
        val target = trainingTarget(outcome, failureClass)
        val attributable = failureClass.isStrategyAttributable
        outcomeMap.record(
            contextClass = pendingDecision.context.contextClass,
            strategy = outcome.strategy,
            target = target,
            failureClass = failureClass,
            attributable = attributable,
            nowNanos = nowNanos
        )
        observations = saturatingIncrement(observations)

        var trained = false
        if (attributable) {
            trained = model.train(pendingDecision.context.inputFor(outcome.strategy), target)
            if (!trained || !model.isFinite()) {
                tripKillSwitchLocked()
                return AegisLocal2ObserveResult(
                    status = AegisLocal2ObserveStatus.MODEL_KILLED,
                    trained = false,
                    tokenRetired = true,
                    failureClass = failureClass,
                    stats = statsLocked()
                )
            }
            actionObservations[outcome.strategy.ordinal] = saturatingIncrement(
                actionObservations[outcome.strategy.ordinal]
            )
            epochTrainedObservations = saturatingIncrement(epochTrainedObservations)
            trainedObservations = saturatingIncrement(trainedObservations)
        }

        val terminalFailure = failureClass == AegisLocal2FailureClass.SERVER_CLOSED ||
            failureClass == AegisLocal2FailureClass.NETWORK_CHANGED ||
            failureClass == AegisLocal2FailureClass.CANCELLED
        val allCandidatesObserved = pendingDecision.observedMask and pendingDecision.allowedMask ==
            pendingDecision.allowedMask
        val retire = outcome.success || terminalFailure || allCandidatesObserved
        if (retire) pending.remove(token)
        return AegisLocal2ObserveResult(
            status = if (trained) {
                AegisLocal2ObserveStatus.RECORDED_AND_TRAINED
            } else {
                AegisLocal2ObserveStatus.RECORDED_NOT_ATTRIBUTABLE
            },
            trained = trained,
            tokenRetired = retire,
            failureClass = failureClass,
            stats = statsLocked()
        )
    }

    @Synchronized
    fun reset(epoch: Long) {
        if (!hasEpoch || epoch >= currentEpoch) resetLocked(epoch)
    }

    @Synchronized
    fun stats(): AegisLocal2Stats = statsLocked()

    @Synchronized
    fun outcomeMapSnapshot(): List<AegisLocal2OutcomeBucketSnapshot> {
        return outcomeMap.snapshots(nanoClock.nowNanos())
    }

    @Synchronized
    fun setRequestedMode(mode: AegisLocal2RequestedMode): AegisLocal2Mode {
        requestedMode = mode
        if (mode == AegisLocal2RequestedMode.ACTIVE) {
            activeRequestsDowngraded = saturatingIncrement(activeRequestsDowngraded)
        }
        this.mode = when {
            this.mode == AegisLocal2Mode.KILLED -> AegisLocal2Mode.KILLED
            mode == AegisLocal2RequestedMode.OFF -> AegisLocal2Mode.OFF
            else -> AegisLocal2Mode.SHADOW
        }
        if (this.mode == AegisLocal2Mode.OFF) pending.clear()
        return this.mode
    }

    private fun ensureEpochLocked(epoch: Long): Boolean {
        if (!hasEpoch || epoch > currentEpoch) {
            resetLocked(epoch)
            return true
        }
        return epoch == currentEpoch
    }

    private fun resetLocked(epoch: Long) {
        pending.clear()
        outcomeMap.clear()
        actionObservations.fill(0)
        model.reset()
        currentEpoch = epoch
        hasEpoch = true
        epochTrainedObservations = 0
        consecutiveLatencyViolations = 0
        mode = if (requestedMode == AegisLocal2RequestedMode.OFF) {
            AegisLocal2Mode.OFF
        } else {
            AegisLocal2Mode.SHADOW
        }
        resetCount = saturatingIncrement(resetCount)
    }

    private fun issueTokenLocked(
        epoch: Long,
        context: AegisLocal2ContextSnapshot,
        candidates: List<TurboStrategyId>,
        baseline: TurboStrategyId,
        suggestion: TurboStrategyId,
        nowNanos: Long
    ): Long {
        purgePendingLocked(nowNanos)
        while (pending.size >= config.maxPendingTokens) {
            val iterator = pending.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
            pendingTokenEvictions = saturatingIncrement(pendingTokenEvictions)
        }
        var token: Long
        do {
            nextToken = if (nextToken == Long.MAX_VALUE) 1L else nextToken + 1L
            token = nextToken
        } while (token == 0L || token in pending)
        val allowedMask = candidates.fold(0) { mask, strategy -> mask or strategyBit(strategy) }
        pending[token] = PendingDecision(
            epoch = epoch,
            context = context,
            allowedMask = allowedMask,
            baseline = baseline,
            suggestion = suggestion,
            issuedAtNanos = nowNanos
        )
        return token
    }

    private fun purgePendingLocked(nowNanos: Long) {
        val ttlNanos = config.pendingTokenTtlMs * NANOS_PER_MILLISECOND
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val issuedAtNanos = iterator.next().value.issuedAtNanos
            val elapsed = nowNanos - issuedAtNanos
            if (elapsed < 0L || elapsed >= ttlNanos) {
                iterator.remove()
                pendingTokenEvictions = saturatingIncrement(pendingTokenEvictions)
            }
        }
    }

    private fun confidenceValueLocked(
        best: AegisLocal2CandidateScore,
        baseline: AegisLocal2CandidateScore,
        context: TurboContext,
        margin: Float
    ): Float {
        val totalEvidence = epochTrainedObservations.toFloat()
            .div(config.minimumTotalObservations.toFloat())
            .coerceIn(0.0f, 1.0f)
        val bestEvidence = actionObservations[best.strategy.ordinal].toFloat()
            .div(config.minimumActionObservations.toFloat())
            .coerceIn(0.0f, 1.0f)
        val baselineEvidence = actionObservations[baseline.strategy.ordinal].toFloat()
            .div(config.minimumActionObservations.toFloat())
            .coerceIn(0.0f, 1.0f)
        val actionCoverage = minOf(bestEvidence, baselineEvidence)
        val localEvidence = minOf(best.observations, baseline.observations).toFloat()
            .div(config.minimumActionObservations.toFloat())
            .coerceIn(0.0f, 1.0f)
        val marginEvidence = margin.div(0.15f).coerceIn(0.0f, 1.0f)
        val networkFactor = if (context.validated && !context.roaming) 1.0f else 0.25f
        val value = (0.45f * actionCoverage +
            0.25f * totalEvidence +
            0.20f * localEvidence +
            0.10f * marginEvidence) * networkFactor
        return if (value.isFinite()) value.coerceIn(0.0f, 1.0f) else 0.0f
    }

    private fun confidenceFromValue(value: Float): TurboConfidence = when {
        value >= 0.80f -> TurboConfidence.HIGH
        value >= 0.50f -> TurboConfidence.MEDIUM
        else -> TurboConfidence.LOW
    }

    private fun blend(modelValue: Float, empiricalValue: Float?, modelWeight: Float): Float {
        val observed = empiricalValue ?: modelValue
        return (modelValue * modelWeight + observed * (1.0f - modelWeight)).coerceIn(0.0f, 1.0f)
    }

    private fun trainingTarget(
        outcome: TurboOutcome,
        failureClass: AegisLocal2FailureClass
    ): AegisLocal2TrainingTarget {
        val latencyMs = outcome.handshakeLatencyMs.coerceIn(0L, MAX_RECORDED_LATENCY_MS).toFloat()
        val latencyQuality = (1.0f / (1.0f + latencyMs / LATENCY_SCALE_MS)).coerceIn(0.0f, 1.0f)
        val retryRisk = outcome.retries.coerceIn(0, 4).toFloat() / 4.0f
        val latencyRisk = (latencyMs / TAIL_LATENCY_MS).coerceIn(0.0f, 1.0f)
        val failureRisk = when {
            outcome.timedOut -> 1.0f
            failureClass == AegisLocal2FailureClass.TIMEOUT -> 1.0f
            failureClass == AegisLocal2FailureClass.HANDSHAKE_BUDGET -> 1.0f
            outcome.abruptDisconnect -> 0.9f
            !outcome.success -> 0.65f
            else -> 0.0f
        }
        val fallbackRisk = if (outcome.fallbackUsed) 0.25f else 0.0f
        return AegisLocal2TrainingTarget(
            successProbability = if (outcome.success) 1.0f else 0.0f,
            latencyQuality = latencyQuality,
            tailRisk = maxOf(failureRisk, retryRisk, latencyRisk, fallbackRisk).coerceIn(0.0f, 1.0f)
        )
    }

    private fun failureClass(outcome: TurboOutcome): AegisLocal2FailureClass {
        if (outcome.success) return AegisLocal2FailureClass.SUCCESS
        return when (outcome.failureCategory) {
            TurboFailureCategory.CONNECT -> AegisLocal2FailureClass.CONNECT
            TurboFailureCategory.TIMEOUT -> AegisLocal2FailureClass.TIMEOUT
            TurboFailureCategory.TLS_ALERT -> AegisLocal2FailureClass.TLS_ALERT
            TurboFailureCategory.WRITE -> AegisLocal2FailureClass.WRITE
            TurboFailureCategory.SERVER_CLOSED -> AegisLocal2FailureClass.SERVER_CLOSED
            TurboFailureCategory.HANDSHAKE_BUDGET -> AegisLocal2FailureClass.HANDSHAKE_BUDGET
            TurboFailureCategory.NETWORK_CHANGED -> AegisLocal2FailureClass.NETWORK_CHANGED
            TurboFailureCategory.CANCELLED -> AegisLocal2FailureClass.CANCELLED
            TurboFailureCategory.NONE,
            TurboFailureCategory.OTHER -> AegisLocal2FailureClass.OTHER
        }
    }

    private fun recordInferenceLocked(value: Long) {
        val bounded = value.coerceAtLeast(0L)
        inferenceCount = saturatingIncrement(inferenceCount)
        inferenceNanos = saturatingAdd(inferenceNanos, bounded)
        lastInferenceNanos = bounded
        maximumInferenceNanos = maxOf(maximumInferenceNanos, bounded)
    }

    private fun tripKillSwitchLocked() {
        if (mode != AegisLocal2Mode.KILLED) {
            killSwitchTrips = saturatingIncrement(killSwitchTrips)
        }
        mode = AegisLocal2Mode.KILLED
        pending.clear()
    }

    private fun decisionWithoutInference(
        baseline: TurboStrategyId,
        reason: AegisLocal2DecisionReason,
        inferenceNanosForDecision: Long = 0L
    ): AegisLocal2Decision {
        return AegisLocal2Decision(
            token = 0L,
            suggestion = baseline,
            baseline = baseline,
            confidence = TurboConfidence.LOW,
            confidenceValue = 0.0f,
            mode = mode,
            reason = reason,
            inferenceNanos = inferenceNanosForDecision,
            candidateScores = emptyList(),
            stats = statsLocked()
        )
    }

    private fun rejectedResult(status: AegisLocal2ObserveStatus): AegisLocal2ObserveResult {
        rejectedObservations = saturatingIncrement(rejectedObservations)
        return observeResult(status)
    }

    private fun observeResult(status: AegisLocal2ObserveStatus): AegisLocal2ObserveResult {
        return AegisLocal2ObserveResult(
            status = status,
            trained = false,
            tokenRetired = false,
            failureClass = null,
            stats = statsLocked()
        )
    }

    private fun statsLocked(): AegisLocal2Stats {
        val confidence = when {
            epochTrainedObservations >= config.minimumTotalObservations * 4 -> TurboConfidence.HIGH
            epochTrainedObservations >= config.minimumTotalObservations -> TurboConfidence.MEDIUM
            else -> TurboConfidence.LOW
        }
        return AegisLocal2Stats(
            requestedMode = requestedMode,
            mode = mode,
            epoch = if (hasEpoch) currentEpoch else 0L,
            decisions = decisions,
            observations = observations,
            trainedObservations = trainedObservations,
            rejectedObservations = rejectedObservations,
            duplicateObservations = duplicateObservations,
            staleObservations = staleObservations,
            pendingTokens = pending.size,
            outcomeBuckets = outcomeMap.size,
            pendingTokenEvictions = pendingTokenEvictions,
            outcomeBucketEvictions = outcomeMap.evictions,
            resetCount = resetCount,
            killSwitchTrips = killSwitchTrips,
            activeRequestsDowngraded = activeRequestsDowngraded,
            inferenceCount = inferenceCount,
            inferenceNanos = inferenceNanos,
            lastInferenceNanos = lastInferenceNanos,
            maximumInferenceNanos = maximumInferenceNanos,
            modelParameters = AegisLocal2NeuralModel.PARAMETER_COUNT,
            modelConfidence = confidence
        )
    }

    private fun strategyBit(strategy: TurboStrategyId): Int = 1 shl strategy.ordinal

    private fun elapsedNanos(startedNanos: Long, completedNanos: Long): Long {
        val elapsed = completedNanos - startedNanos
        return if (elapsed < 0L) Long.MAX_VALUE else elapsed
    }

    private fun saturatingIncrement(value: Int): Int {
        return if (value == Int.MAX_VALUE) value else value + 1
    }

    private fun saturatingIncrement(value: Long): Long {
        return if (value == Long.MAX_VALUE) value else value + 1L
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        return if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_EMPIRICAL_WEIGHT = 0.70f
        private const val BASELINE_PRIOR = 0.01f
        private const val SCORE_EPSILON = 0.000001f
        private const val MAX_RECORDED_LATENCY_MS = 60_000L
        private const val LATENCY_SCALE_MS = 500.0f
        private const val TAIL_LATENCY_MS = 3_000.0f
    }
}
