package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AegisLocal2Test {
    private val allStrategies = listOf(
        TurboStrategyId.TLS_SNI_MULTI_V1,
        TurboStrategyId.TLS_HOSTNAME_V1,
        TurboStrategyId.TLS_SINGLE_SAFE_V1,
        TurboStrategyId.TLS_PLAIN_V1
    )

    @Test
    fun defaultModeIsShadowAndColdStartKeepsCurrentDecision() {
        val engine = engine()

        val decision = engine.decide(context(), allStrategies, baseline(), epoch = 1L)

        assertEquals(AegisLocal2Mode.SHADOW, decision.mode)
        assertEquals(TurboStrategyId.TLS_SNI_MULTI_V1, decision.suggestion)
        assertEquals(AegisLocal2DecisionReason.COLD_START, decision.reason)
        assertTrue(decision.token > 0L)
        assertEquals(4, decision.candidateScores.size)
        assertTrue(decision.candidateScores.all { score ->
            score.score.isFinite() &&
                score.successProbability.isFinite() &&
                score.latencyQuality.isFinite() &&
                score.tailRisk.isFinite()
        })
        assertEquals(579, decision.stats.modelParameters)
        assertEquals(1L, decision.stats.inferenceCount)
        assertTrue(decision.inferenceNanos > 0L)
    }

    @Test
    fun activeRequestIsAlwaysDowngradedToShadow() {
        val engine = engine()

        val actual = engine.setRequestedMode(AegisLocal2RequestedMode.ACTIVE)

        assertEquals(AegisLocal2Mode.SHADOW, actual)
        assertEquals(AegisLocal2RequestedMode.ACTIVE, engine.stats().requestedMode)
        assertEquals(1L, engine.stats().activeRequestsDowngraded)
    }

    @Test
    fun safetyPolicyCanOnlyReduceParentCandidates() {
        val engine = engine()
        val noClientHello = context().copy(clientHelloParsed = false, sniPresent = false)
        val plainBaseline = baseline().copy(
            strategy = TurboStrategyId.TLS_PLAIN_V1,
            fallbackOrder = emptyList()
        )

        val decision = engine.decide(
            noClientHello,
            listOf(TurboStrategyId.TLS_SNI_MULTI_V1, TurboStrategyId.TLS_PLAIN_V1),
            plainBaseline,
            epoch = 1L
        )

        assertEquals(AegisLocal2DecisionReason.SINGLE_CANDIDATE, decision.reason)
        assertEquals(TurboStrategyId.TLS_PLAIN_V1, decision.suggestion)
        assertEquals(listOf(TurboStrategyId.TLS_PLAIN_V1), decision.candidateScores.map { it.strategy })
    }

    @Test
    fun actualFallbackAttemptsTrainOncePerStrategyAndSuccessRetiresToken() {
        val engine = engine()
        val allowed = listOf(TurboStrategyId.TLS_SNI_MULTI_V1, TurboStrategyId.TLS_PLAIN_V1)
        val decision = engine.decide(context(), allowed, baseline(), epoch = 7L)

        val first = engine.observe(
            decision.token,
            outcome(
                token = decision.token,
                strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
                success = false,
                failure = TurboFailureCategory.TIMEOUT,
                latencyMs = 3_000L,
                timedOut = true
            ),
            epoch = 7L
        )
        val duplicate = engine.observe(
            decision.token,
            outcome(
                token = decision.token,
                strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
                success = false,
                failure = TurboFailureCategory.TIMEOUT,
                latencyMs = 3_100L,
                timedOut = true
            ),
            epoch = 7L
        )
        val success = engine.observe(
            decision.token,
            outcome(
                token = decision.token,
                strategy = TurboStrategyId.TLS_PLAIN_V1,
                success = true,
                latencyMs = 90L,
                fallbackUsed = true
            ),
            epoch = 7L
        )

        assertEquals(AegisLocal2ObserveStatus.RECORDED_AND_TRAINED, first.status)
        assertFalse(first.tokenRetired)
        assertEquals(AegisLocal2ObserveStatus.IGNORED_DUPLICATE_STRATEGY, duplicate.status)
        assertEquals(AegisLocal2ObserveStatus.RECORDED_AND_TRAINED, success.status)
        assertTrue(success.tokenRetired)
        assertEquals(2L, engine.stats().observations)
        assertEquals(2L, engine.stats().trainedObservations)
        assertEquals(1L, engine.stats().duplicateObservations)
        assertEquals(0, engine.stats().pendingTokens)
    }

    @Test
    fun mismatchedTokenAndUnselectedCandidateCannotCreateFeedback() {
        val engine = engine()
        val allowed = listOf(TurboStrategyId.TLS_SNI_MULTI_V1, TurboStrategyId.TLS_PLAIN_V1)
        val decision = engine.decide(context(), allowed, baseline(), epoch = 3L)

        val mismatch = engine.observe(
            decision.token,
            outcome(decision.token + 1L, TurboStrategyId.TLS_SNI_MULTI_V1, success = true),
            epoch = 3L
        )
        val excluded = engine.observe(
            decision.token,
            outcome(decision.token, TurboStrategyId.TLS_HOSTNAME_V1, success = true),
            epoch = 3L
        )

        assertEquals(AegisLocal2ObserveStatus.IGNORED_TOKEN_MISMATCH, mismatch.status)
        assertEquals(AegisLocal2ObserveStatus.IGNORED_STRATEGY_NOT_ALLOWED, excluded.status)
        assertEquals(0L, engine.stats().observations)
        assertTrue(engine.outcomeMapSnapshot().isEmpty())
    }

    @Test
    fun outcomeMapUsesDestinationIndependentContext() {
        val engine = engine()
        listOf("destination-a", "destination-b").forEach { destination ->
            val decision = engine.decide(
                context(destinationKey = destination),
                allStrategies,
                baseline(),
                epoch = 2L
            )
            engine.observe(
                decision.token,
                outcome(
                    token = decision.token,
                    strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
                    success = false,
                    failure = TurboFailureCategory.TIMEOUT,
                    timedOut = true
                ),
                epoch = 2L
            )
        }

        val buckets = engine.outcomeMapSnapshot()
        assertEquals(1, buckets.size)
        assertEquals(2, buckets.single().observations)
        assertEquals(2, buckets.single().attributedObservations)
        assertEquals(2, buckets.single().failureCounts[AegisLocal2FailureClass.TIMEOUT])
    }

    @Test
    fun unknownFailureIsRetainedButNeverTrainsTheStrategy() {
        val engine = engine()
        val decision = engine.decide(context(), allStrategies, baseline(), epoch = 4L)

        val result = engine.observe(
            decision.token,
            outcome(
                token = decision.token,
                strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
                success = false,
                failure = TurboFailureCategory.OTHER
            ),
            epoch = 4L
        )

        assertEquals(AegisLocal2ObserveStatus.RECORDED_NOT_ATTRIBUTABLE, result.status)
        assertFalse(result.trained)
        assertEquals(0L, engine.stats().trainedObservations)
        assertEquals(1, engine.outcomeMapSnapshot().single().failureCounts[AegisLocal2FailureClass.OTHER])
    }

    @Test
    fun epochResetInvalidatesTokensOutcomeMapAndLearnedConfidence() {
        val engine = engine(
            minimumTotalObservations = 1,
            minimumActionObservations = 1
        )
        val decision = engine.decide(context(), allStrategies, baseline(), epoch = 11L)
        engine.observe(
            decision.token,
            outcome(
                token = decision.token,
                strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
                success = false,
                failure = TurboFailureCategory.TIMEOUT,
                timedOut = true
            ),
            epoch = 11L
        )
        assertFalse(engine.outcomeMapSnapshot().isEmpty())

        engine.reset(epoch = 12L)
        val stale = engine.observe(
            decision.token,
            outcome(decision.token, TurboStrategyId.TLS_PLAIN_V1, success = true),
            epoch = 11L
        )

        assertEquals(AegisLocal2ObserveStatus.IGNORED_STALE_EPOCH, stale.status)
        assertTrue(engine.outcomeMapSnapshot().isEmpty())
        assertEquals(TurboConfidence.LOW, engine.stats().modelConfidence)
        assertEquals(12L, engine.stats().epoch)
        assertEquals(0, engine.stats().pendingTokens)
    }

    @Test
    fun olderDecisionEpochCannotRollModelStateBack() {
        val engine = engine()
        val current = engine.decide(context(), allStrategies, baseline(), epoch = 20L)
        engine.observe(
            current.token,
            outcome(
                token = current.token,
                strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
                success = false,
                failure = TurboFailureCategory.TIMEOUT,
                timedOut = true
            ),
            epoch = 20L
        )
        val bucketsBefore = engine.outcomeMapSnapshot()

        val stale = engine.decide(context(), allStrategies, baseline(), epoch = 19L)

        assertEquals(AegisLocal2DecisionReason.STALE_EPOCH, stale.reason)
        assertEquals(0L, stale.token)
        assertEquals(20L, engine.stats().epoch)
        assertEquals(bucketsBefore, engine.outcomeMapSnapshot())
    }

    @Test
    fun pendingTokenRetentionIsBoundedAndOldestTokenIsEvicted() {
        val engine = engine(maxPendingTokens = 2)
        val first = engine.decide(context("a"), allStrategies, baseline(), epoch = 1L)
        engine.decide(context("b"), allStrategies, baseline(), epoch = 1L)
        engine.decide(context("c"), allStrategies, baseline(), epoch = 1L)

        val evicted = engine.observe(
            first.token,
            outcome(first.token, TurboStrategyId.TLS_SNI_MULTI_V1, success = true),
            epoch = 1L
        )

        assertEquals(AegisLocal2ObserveStatus.IGNORED_UNKNOWN_TOKEN, evicted.status)
        assertEquals(2, engine.stats().pendingTokens)
        assertEquals(1L, engine.stats().pendingTokenEvictions)
    }

    @Test
    fun outcomeMapRetentionIsBoundedAcrossContextAndActionBuckets() {
        val engine = engine(maxOutcomeBuckets = 2)
        repeat(3) { index ->
            val strategy = allStrategies[index]
            val decision = engine.decide(
                context(destinationKey = "d-$index").copy(approximateRttBucket = index),
                allStrategies,
                baseline(),
                epoch = 1L
            )
            engine.observe(
                decision.token,
                outcome(
                    token = decision.token,
                    strategy = strategy,
                    success = false,
                    failure = TurboFailureCategory.TIMEOUT,
                    timedOut = true
                ),
                epoch = 1L
            )
        }

        assertEquals(2, engine.outcomeMapSnapshot().size)
        assertEquals(2, engine.stats().outcomeBuckets)
        assertTrue(engine.stats().outcomeBucketEvictions >= 1L)
    }

    @Test
    fun pendingTokenExpiresUsingBoundedMonotonicTtl() {
        val clock = MutableNanoClock()
        val engine = engine(pendingTokenTtlMs = 1L, nanoClock = clock)
        val decision = engine.decide(context(), allStrategies, baseline(), epoch = 1L)
        clock.now += 1_000_000L

        val expired = engine.observe(
            decision.token,
            outcome(decision.token, TurboStrategyId.TLS_SNI_MULTI_V1, success = true),
            epoch = 1L
        )

        assertEquals(AegisLocal2ObserveStatus.IGNORED_UNKNOWN_TOKEN, expired.status)
        assertEquals(0, engine.stats().pendingTokens)
        assertTrue(engine.stats().pendingTokenEvictions >= 1L)
    }

    @Test
    fun extremeLatencyTripsKillSwitchAndResetReturnsToShadow() {
        val clock = SteppingNanoClock(stepNanos = 20_000_000L)
        val engine = AegisLocal2Engine(
            config = config(
                softInferenceLimitNanos = 1_000_000L,
                hardInferenceLimitNanos = 5_000_000L,
                maxConsecutiveLatencyViolations = 1
            ),
            nanoClock = clock
        )

        val decision = engine.decide(context(), allStrategies, baseline(), epoch = 1L)

        assertEquals(AegisLocal2Mode.KILLED, decision.mode)
        assertEquals(AegisLocal2DecisionReason.INFERENCE_LIMIT, decision.reason)
        assertEquals(0L, decision.token)
        assertEquals(1L, engine.stats().killSwitchTrips)
        engine.reset(epoch = 2L)
        assertEquals(AegisLocal2Mode.SHADOW, engine.stats().mode)
    }

    @Test
    fun corruptedWeightsKillPredictionBeforeClampingCanHideInvalidValues() {
        for (corruption in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val engine = engine()
            engine.reset(1L)
            val field = AegisLocal2Engine::class.java.getDeclaredField("model").apply { isAccessible = true }
            val model = field.get(engine)
            val weightsField = AegisLocal2NeuralModel::class.java.getDeclaredField("inputWeights")
                .apply { isAccessible = true }
            val weights = weightsField.get(model) as FloatArray
            weights[0] = corruption

            val decision = engine.decide(context(), allStrategies, baseline(), epoch = 1L)

            assertEquals(AegisLocal2Mode.KILLED, decision.mode)
            assertEquals(baseline().strategy, decision.suggestion)
            assertEquals(0L, decision.token)
            assertEquals(1L, engine.stats().killSwitchTrips)
        }
    }

    @Test
    fun extremeOutcomeValuesStayFiniteAndBounded() {
        val engine = engine()
        val decision = engine.decide(context(), allStrategies, baseline(), epoch = 1L)

        val observed = engine.observe(
            decision.token,
            outcome(
                token = decision.token,
                strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
                success = false,
                failure = TurboFailureCategory.TIMEOUT,
                latencyMs = Long.MAX_VALUE,
                timedOut = true,
                retries = Int.MAX_VALUE
            ),
            epoch = 1L
        )
        val next = engine.decide(context(), allStrategies, baseline(), epoch = 1L)

        assertEquals(AegisLocal2ObserveStatus.RECORDED_AND_TRAINED, observed.status)
        assertNotEquals(AegisLocal2Mode.KILLED, next.mode)
        assertTrue(next.candidateScores.all {
            it.score.isFinite() && it.successProbability.isFinite() &&
                it.latencyQuality.isFinite() && it.tailRisk.isFinite()
        })
    }

    @Test
    fun repeatedRealFallbackFeedbackProducesAConfidentShadowSuggestion() {
        val engine = engine(
            minimumTotalObservations = 4,
            minimumActionObservations = 2,
            confidenceThreshold = 0.45f,
            minimumScoreMargin = 0.01f
        )
        val allowed = listOf(TurboStrategyId.TLS_SNI_MULTI_V1, TurboStrategyId.TLS_PLAIN_V1)
        repeat(24) {
            val decision = engine.decide(context(destinationKey = "destination-$it"), allowed, baseline(), epoch = 9L)
            engine.observe(
                decision.token,
                outcome(
                    token = decision.token,
                    strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
                    success = false,
                    failure = TurboFailureCategory.TIMEOUT,
                    latencyMs = 4_000L,
                    timedOut = true
                ),
                epoch = 9L
            )
            engine.observe(
                decision.token,
                outcome(
                    token = decision.token,
                    strategy = TurboStrategyId.TLS_PLAIN_V1,
                    success = true,
                    latencyMs = 80L,
                    fallbackUsed = true
                ),
                epoch = 9L
            )
        }

        val learned = engine.decide(context("unseen-destination"), allowed, baseline(), epoch = 9L)

        assertEquals(AegisLocal2Mode.SHADOW, learned.mode)
        assertEquals(AegisLocal2DecisionReason.SHADOW_SUGGESTION, learned.reason)
        assertEquals(TurboStrategyId.TLS_PLAIN_V1, learned.suggestion)
        assertTrue(learned.confidenceValue >= 0.45f)
        assertTrue(learned.inferenceNanos > 0L)
        assertEquals(25L, learned.stats.inferenceCount)
        assertEquals(48L, learned.stats.observations)
    }

    private fun engine(
        maxPendingTokens: Int = 256,
        maxOutcomeBuckets: Int = 192,
        pendingTokenTtlMs: Long = 120_000L,
        minimumTotalObservations: Int = 16,
        minimumActionObservations: Int = 4,
        confidenceThreshold: Float = 0.62f,
        minimumScoreMargin: Float = 0.025f,
        nanoClock: AegisLocal2NanoClock = AegisLocal2NanoClock { System.nanoTime() }
    ): AegisLocal2Engine {
        return AegisLocal2Engine(
            config = config(
                maxPendingTokens = maxPendingTokens,
                maxOutcomeBuckets = maxOutcomeBuckets,
                pendingTokenTtlMs = pendingTokenTtlMs,
                minimumTotalObservations = minimumTotalObservations,
                minimumActionObservations = minimumActionObservations,
                confidenceThreshold = confidenceThreshold,
                minimumScoreMargin = minimumScoreMargin
            ),
            nanoClock = nanoClock
        )
    }

    private fun config(
        maxPendingTokens: Int = 256,
        maxOutcomeBuckets: Int = 192,
        pendingTokenTtlMs: Long = 120_000L,
        minimumTotalObservations: Int = 16,
        minimumActionObservations: Int = 4,
        confidenceThreshold: Float = 0.62f,
        minimumScoreMargin: Float = 0.025f,
        softInferenceLimitNanos: Long = 1_000_000_000L,
        hardInferenceLimitNanos: Long = 2_000_000_000L,
        maxConsecutiveLatencyViolations: Int = 3
    ): AegisLocal2Config {
        return AegisLocal2Config(
            maxPendingTokens = maxPendingTokens,
            maxOutcomeBuckets = maxOutcomeBuckets,
            pendingTokenTtlMs = pendingTokenTtlMs,
            minimumTotalObservations = minimumTotalObservations,
            minimumActionObservations = minimumActionObservations,
            confidenceThreshold = confidenceThreshold,
            minimumScoreMargin = minimumScoreMargin,
            softInferenceLimitNanos = softInferenceLimitNanos,
            hardInferenceLimitNanos = hardInferenceLimitNanos,
            maxConsecutiveLatencyViolations = maxConsecutiveLatencyViolations
        )
    }

    private fun context(destinationKey: String = "destination"): TurboContext {
        return TurboContext(
            destinationKey = destinationKey,
            destinationPort = 443,
            transport = TurboNetworkTransport.WIFI,
            metered = false,
            roaming = false,
            validated = true,
            batterySaver = false,
            clientHelloParsed = true,
            sniPresent = true,
            latencyProfile = "DEFAULT",
            approximateRttBucket = 1,
            recentRetryBucket = 0,
            recentSuccessBucket = 4,
            recentHandshakeBucket = 1,
            resourcePressureBucket = 0,
            previousFailure = TurboFailureCategory.NONE
        )
    }

    private fun baseline(): TurboDecision {
        return TurboDecision(
            strategy = TurboStrategyId.TLS_SNI_MULTI_V1,
            fallbackOrder = allStrategies.drop(1),
            policy = TurboDecisionPolicy.BASELINE,
            reason = TurboDecisionReason(
                code = "test-baseline",
                confidence = TurboConfidence.LOW,
                eligibleStrategyIds = allStrategies.map { it.stableId }
            ),
            contextKey = "legacy-context",
            sharedContextKey = "legacy-shared-context",
            batterySaver = false,
            decisionOverheadNanos = 0L
        )
    }

    private fun outcome(
        token: Long,
        strategy: TurboStrategyId,
        success: Boolean,
        failure: TurboFailureCategory = if (success) TurboFailureCategory.NONE else TurboFailureCategory.OTHER,
        latencyMs: Long = 100L,
        timedOut: Boolean = false,
        fallbackUsed: Boolean = false,
        retries: Int = 0
    ): TurboOutcome {
        return TurboOutcome(
            contextKey = "ignored-destination-context",
            strategy = strategy,
            policy = TurboDecisionPolicy.BASELINE,
            success = success,
            handshakeLatencyMs = latencyMs,
            connectionDurationMs = latencyMs,
            bytesUp = if (success) 1_024L else 0L,
            bytesDown = if (success) 2_048L else 0L,
            retries = retries,
            timedOut = timedOut,
            abruptDisconnect = false,
            fallbackUsed = fallbackUsed,
            decisionOverheadNanos = 0L,
            batterySaver = false,
            failureCategory = failure,
            completedAtMs = 1_000L,
            sharedContextKey = "ignored-shared-context",
            aegisToken = token
        )
    }

    private class MutableNanoClock(var now: Long = 1_000_000L) : AegisLocal2NanoClock {
        override fun nowNanos(): Long = now
    }

    private class SteppingNanoClock(private val stepNanos: Long) : AegisLocal2NanoClock {
        private var now = 0L

        override fun nowNanos(): Long {
            now += stepNanos
            return now
        }
    }
}
