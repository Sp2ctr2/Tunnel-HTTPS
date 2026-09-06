package com.tunnelvpn.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboAiEngineTest {
    private val baseline = listOf(
        TurboStrategyId.TLS_SNI_MULTI_V1,
        TurboStrategyId.TLS_HOSTNAME_V1,
        TurboStrategyId.TLS_SINGLE_SAFE_V1,
        TurboStrategyId.TLS_PLAIN_V1
    )

    @Test
    fun deterministicSelectionWhenExplorationIsDisabled() {
        val engine = engine(exploration = false)
        val context = context()
        val contextKey = engine.select(context, baseline).contextKey
        repeat(16) {
            engine.update(outcome(contextKey, TurboStrategyId.TLS_SINGLE_SAFE_V1, success = true, latencyMs = 80))
            engine.update(outcome(contextKey, TurboStrategyId.TLS_SNI_MULTI_V1, success = false, latencyMs = 3_500))
        }

        val first = engine.select(context, baseline)
        val second = engine.select(context, baseline)

        assertEquals(TurboStrategyId.TLS_SINGLE_SAFE_V1, first.strategy)
        assertEquals(first.strategy, second.strategy)
        assertEquals(first.reason.code, second.reason.code)
    }

    @Test
    fun explorationDisabledNeverSelectsAnUnobservedArm() {
        val engine = engine(exploration = false)
        val first = engine.select(context(), baseline)
        repeat(12) {
            engine.update(outcome(first.contextKey, first.strategy, success = false, latencyMs = 2_000))
        }

        val next = engine.select(context(), baseline)

        assertEquals(first.strategy, next.strategy)
        assertNotEquals(TurboDecisionPolicy.EXPLORATION, next.policy)
    }

    @Test
    fun seededExplorationSelectsSafeNonBaselineArm() {
        val random = SequenceRandom(doubles = ArrayDeque(listOf(0.0)), ints = ArrayDeque(listOf(1)))
        val engine = engine(exploration = true, random = random)

        val decision = engine.select(context(), baseline)

        assertEquals(TurboDecisionPolicy.EXPLORATION, decision.policy)
        assertNotEquals(TurboStrategyId.TLS_SNI_MULTI_V1, decision.strategy)
        assertTrue(decision.strategy in baseline)
    }

    @Test
    fun coldStartUsesExistingBaseline() {
        val engine = engine(exploration = false)

        val decision = engine.select(context(), baseline)

        assertEquals(TurboStrategyId.TLS_SNI_MULTI_V1, decision.strategy)
        assertEquals(TurboDecisionPolicy.BASELINE, decision.policy)
        assertEquals("cold-start-baseline", decision.reason.code)
    }

    @Test
    fun successfulModelUpdateProducesPositiveReward() {
        val engine = engine(exploration = false)
        val decision = engine.select(context(), baseline)

        val reward = engine.update(outcome(decision.contextKey, decision.strategy, success = true, latencyMs = 100))

        assertTrue(reward > 0.0)
        assertEquals(1L, engine.totalSamples())
        assertEquals(1, engine.exportState().entries.size)
    }

    @Test
    fun failedConnectionProducesNegativeUpdate() {
        val engine = engine(exploration = false)
        val decision = engine.select(context(), baseline)

        val reward = engine.update(
            outcome(
                decision.contextKey,
                decision.strategy,
                success = false,
                latencyMs = 4_000,
                timedOut = true
            )
        )

        assertTrue(reward < 0.0)
        assertTrue(engine.exportState().entries.single().meanReward < 0.0)
    }

    @Test
    fun lowLatencySuccessOutranksHighLatencySuccess() {
        val calculator = TurboRewardCalculator()
        val low = calculator.calculate(outcome("c", baseline[0], true, 80))
        val high = calculator.calculate(outcome("c", baseline[0], true, 3_800))

        assertTrue(low > high)
        assertTrue(low <= 1.0)
        assertTrue(high >= -1.0)
    }

    @Test
    fun abruptShortConnectionScoresBelowStableConnection() {
        val calculator = TurboRewardCalculator()
        val stable = outcome("c", baseline[0], true, 100)
        val abrupt = stable.copy(connectionDurationMs = 100L, abruptDisconnect = true)

        assertTrue(calculator.calculate(abrupt) < calculator.calculate(stable))
    }

    @Test
    fun sharedContextCannotOverrideNewDestinationColdStart() {
        val engine = engine(exploration = false)
        val first = engine.select(context(), baseline)
        repeat(30) {
            engine.update(
                outcome(
                    first.contextKey,
                    TurboStrategyId.TLS_PLAIN_V1,
                    true,
                    80,
                    sharedContextKey = first.sharedContextKey
                )
            )
            engine.update(
                outcome(
                    first.contextKey,
                    TurboStrategyId.TLS_SNI_MULTI_V1,
                    false,
                    3_500,
                    timedOut = true,
                    sharedContextKey = first.sharedContextKey
                )
            )
        }

        val next = engine.select(context().copy(destinationKey = "fedcba9876543210fedcba9876543210"), baseline)

        assertEquals(TurboStrategyId.TLS_SNI_MULTI_V1, next.strategy)
        assertEquals("cold-start-baseline", next.reason.code)
    }

    @Test
    fun connectionFailureDoesNotBiasFragmentationStrategy() {
        val engine = engine(exploration = false)
        val decision = engine.select(context(), baseline)

        val reward = engine.update(
            outcome(
                decision.contextKey,
                decision.strategy,
                false,
                1_000,
                failureCategory = TurboFailureCategory.CONNECT
            )
        )

        assertTrue(reward < 0.0)
        assertEquals(0L, engine.totalSamples())
        assertTrue(engine.exportState().entries.isEmpty())
    }

    @Test
    fun postHandshakeTransportFailureDoesNotPoisonTheSelectedStrategy() {
        val engine = engine(exploration = false)
        val decision = engine.select(context(), baseline)

        engine.update(
            outcome(
                decision.contextKey,
                decision.strategy,
                false,
                1_000,
                failureCategory = TurboFailureCategory.SERVER_CLOSED
            )
        )

        assertEquals(0L, engine.totalSamples())
        assertTrue(engine.exportState().entries.isEmpty())
    }

    @Test
    fun unsafeFragmentationStrategiesAreExcluded() {
        val unsafe = context().copy(clientHelloParsed = false)

        val result = TurboSafetyPolicy().eligibleStrategies(unsafe)

        assertEquals(listOf(TurboStrategyId.TLS_PLAIN_V1), result.eligible)
        assertTrue(result.excludedReasonCodes.contains("fragmentation-requires-valid-clienthello"))
    }

    @Test
    fun hostnameStrategiesAreExcludedWithoutSni() {
        val result = TurboSafetyPolicy().eligibleStrategies(context().copy(sniPresent = false))

        assertEquals(listOf(TurboStrategyId.TLS_SINGLE_SAFE_V1, TurboStrategyId.TLS_PLAIN_V1), result.eligible)
        assertTrue(result.excludedReasonCodes.contains("hostname-strategies-require-sni"))
    }

    @Test
    fun aiDisabledPreservesDeterministicBaseline() {
        val engine = TurboAiEngine(
            secret = ByteArray(32) { 7 },
            flags = TurboAiFlags(enabled = false, explorationEnabled = true),
            clock = TurboClock { 1_000L },
            random = SequenceRandom(ArrayDeque(listOf(0.0)), ArrayDeque(listOf(0)))
        )

        val decision = engine.select(context(), baseline)

        assertEquals(TurboStrategyId.TLS_SNI_MULTI_V1, decision.strategy)
        assertEquals("ai-disabled-baseline", decision.reason.code)
    }

    @Test
    fun runtimeSuppressionRestrictsAiToTheActualBaseline() {
        val engine = engine(exploration = true)

        val decision = engine.select(context(), listOf(TurboStrategyId.TLS_PLAIN_V1))

        assertEquals(TurboStrategyId.TLS_PLAIN_V1, decision.strategy)
        assertEquals(listOf(TurboStrategyId.TLS_PLAIN_V1.stableId), decision.reason.eligibleStrategyIds)
        assertTrue("runtime-suppressed-strategy" in decision.reason.excludedReasonCodes)
    }

    @Test
    fun selectedStrategyHasBoundedFallbackOrderIncludingPlainRecovery() {
        val engine = engine(exploration = false)

        val decision = engine.select(context(), baseline)

        assertFalse(decision.strategy in decision.fallbackOrder)
        assertEquals(3, decision.fallbackOrder.size)
        assertTrue(TurboStrategyId.TLS_PLAIN_V1 in decision.fallbackOrder)
    }

    @Test
    fun staleEntriesAreRemovedDuringLoad() {
        val now = TurboAiEngine.STALE_ENTRY_MS + 10_000L
        val stale = TurboModelEntry(
            contextKey = "stale",
            strategy = baseline[0],
            weightedCount = 10.0,
            meanReward = 0.7,
            weightedSuccesses = 9.0,
            latencyEmaMs = 120.0,
            lastUpdatedMs = 0L,
            lastAccessMs = 0L
        )
        val engine = TurboAiEngine(
            secret = ByteArray(32) { 3 },
            initialState = TurboPersistedState(totalSamples = 10, entries = listOf(stale)),
            flags = TurboAiFlags(enabled = true, explorationEnabled = false),
            clock = TurboClock { now }
        )

        assertTrue(engine.exportState().entries.isEmpty())
    }

    @Test
    fun boundedCacheEvictsLeastRecentlyUsedContexts() {
        var now = 1_000L
        val engine = TurboAiEngine(
            secret = ByteArray(32) { 4 },
            flags = TurboAiFlags(enabled = true, explorationEnabled = false),
            clock = TurboClock { now },
            maxContexts = 1
        )
        repeat(8) { index ->
            now += 1
            val decision = engine.select(context().copy(destinationKey = "destination-$index"), baseline)
            engine.update(outcome(decision.contextKey, decision.strategy, true, 100, completedAtMs = now))
        }

        assertTrue(engine.exportState().entries.size <= TurboStrategyId.values().size)
    }

    @Test
    fun coarseNetworkProfileSeparatesTransportWithoutChangingOnReconnect() {
        val encoder = TurboFeatureEncoder()
        val wifi = context()
        val cellular = wifi.copy(transport = TurboNetworkTransport.CELLULAR, metered = true)

        assertEquals(encoder.encode(wifi), encoder.encode(wifi.copy()))
        assertNotEquals(encoder.encode(wifi), encoder.encode(cellular))
    }

    @Test
    fun shortNormalResponseDoesNotReceiveMissingMetricPenalties() {
        val calculator = TurboRewardCalculator()
        val short = outcome("c", baseline[0], true, 90).copy(
            connectionDurationMs = 120L,
            bytesUp = 180L,
            bytesDown = 40L
        )
        val long = short.copy(connectionDurationMs = 30_000L, bytesDown = 800_000L)

        assertTrue(calculator.calculate(short) > 0.0)
        assertTrue(calculator.calculate(short) >= calculator.calculate(long) - 0.25)
    }

    @Test
    fun concurrentUpdatesAreThreadSafe() {
        val engine = engine(exploration = false)
        val decision = engine.select(context(), baseline)
        val workers = 8
        val updatesPerWorker = 100
        val executor = Executors.newFixedThreadPool(workers)
        val done = CountDownLatch(workers)
        repeat(workers) {
            executor.execute {
                repeat(updatesPerWorker) {
                    engine.update(outcome(decision.contextKey, decision.strategy, true, 120))
                }
                done.countDown()
            }
        }

        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals((workers * updatesPerWorker).toLong(), engine.totalSamples())
        assertEquals(workers * updatesPerWorker.toDouble(), engine.exportState().entries.single().weightedCount, 0.001)
    }

    @Test
    fun concurrentExportNeverProducesAnInvalidArmSnapshot() {
        val engine = engine(exploration = false)
        val decision = engine.select(context(), baseline)
        val executor = Executors.newFixedThreadPool(5)
        val done = CountDownLatch(5)
        val violations = AtomicInteger()
        repeat(4) {
            executor.execute {
                repeat(500) {
                    engine.update(outcome(decision.contextKey, decision.strategy, true, 120))
                }
                done.countDown()
            }
        }
        executor.execute {
            repeat(1_000) {
                engine.exportState().entries.forEach { entry ->
                    if (entry.weightedSuccesses > entry.weightedCount + 0.000001) {
                        violations.incrementAndGet()
                    }
                    TurboModelCodec.decode(TurboModelCodec.encode(TurboPersistedState(entries = listOf(entry)))).let { decoded ->
                        if (decoded.corrupted) violations.incrementAndGet()
                    }
                }
            }
            done.countDown()
        }

        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(0, violations.get())
    }

    @Test
    fun restoredArmStatisticsPreserveTheLearnedDecision() {
        val original = engine(exploration = false)
        val first = original.select(context(), baseline)
        repeat(12) {
            original.update(outcome(first.contextKey, TurboStrategyId.TLS_SINGLE_SAFE_V1, true, 80))
            original.update(outcome(first.contextKey, TurboStrategyId.TLS_SNI_MULTI_V1, false, 2_000))
        }
        val before = original.select(context(), baseline)
        val decoded = TurboModelCodec.decode(TurboModelCodec.encode(original.exportState()))
        val restored = TurboAiEngine(
            secret = ByteArray(32) { 9 },
            initialState = decoded.state,
            flags = TurboAiFlags(enabled = true, explorationEnabled = false),
            clock = TurboClock { 1_000L },
            random = SequenceRandom(ArrayDeque(listOf(0.5)), ArrayDeque(listOf(0)))
        )

        val after = restored.select(context(), baseline)

        assertFalse(decoded.corrupted)
        assertEquals(TurboStrategyId.TLS_SINGLE_SAFE_V1, before.strategy)
        assertEquals(before.strategy, after.strategy)
    }

    @Test
    fun predictionUsesMemoryOnlyAndStaysBelowOneMillisecondP95() {
        val engine = engine(exploration = false)
        val context = context()
        repeat(1_000) { engine.select(context, baseline) }
        val samples = LongArray(5_000)
        repeat(samples.size) { index ->
            val started = System.nanoTime()
            engine.select(context, baseline)
            samples[index] = System.nanoTime() - started
        }
        samples.sort()
        val p95Nanos = samples[(samples.size * 95) / 100]
        println("BENCH turbo_ai_strategy_selection host_jvm_debug_unit iterations=${samples.size} p95_us=${p95Nanos / 1_000} excludes_dns_tcp_tls=true")

        assertTrue("p95 prediction overhead was ${p95Nanos / 1_000}us", p95Nanos < 1_000_000L)
    }

    @Test
    fun destinationHasherOwnsItsSecretAndNormalizesHostnames() {
        val secret = ByteArray(32) { it.toByte() }
        val hasher = TurboDestinationHasher(secret)
        val expected = hasher.hash("Example.COM.")

        secret.fill(0)

        assertEquals(expected, hasher.hash("example.com"))
        assertEquals(32, expected.length)
    }

    @Test
    fun destinationHasherIsDeterministicUnderConcurrentPrediction() {
        val hasher = TurboDestinationHasher(ByteArray(32) { (it * 3).toByte() })
        val expected = hasher.hash("speed.example")
        val executor = Executors.newFixedThreadPool(8)
        val mismatches = AtomicInteger()
        repeat(8) {
            executor.execute {
                repeat(2_000) {
                    if (hasher.hash("speed.example") != expected) mismatches.incrementAndGet()
                }
            }
        }
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(0, mismatches.get())
    }

    @Test
    fun destinationHashingStaysBelowOneMillisecondP95() {
        val hasher = TurboDestinationHasher(ByteArray(32) { (it * 7).toByte() })
        repeat(1_000) { hasher.hash("speed.example") }
        val samples = LongArray(5_000)
        repeat(samples.size) { index ->
            val started = System.nanoTime()
            hasher.hash("speed.example")
            samples[index] = System.nanoTime() - started
        }
        samples.sort()
        val p95Nanos = samples[(samples.size * 95) / 100]

        assertTrue("p95 destination hashing was ${p95Nanos / 1_000}us", p95Nanos < 1_000_000L)
    }

    private fun engine(
        exploration: Boolean,
        random: TurboRandom = SequenceRandom(ArrayDeque(listOf(0.5)), ArrayDeque(listOf(0)))
    ): TurboAiEngine {
        return TurboAiEngine(
            secret = ByteArray(32) { 9 },
            flags = TurboAiFlags(enabled = true, explorationEnabled = exploration),
            clock = TurboClock { 1_000L },
            random = random
        )
    }

    private fun context(): TurboContext {
        return TurboContext(
            destinationKey = "0123456789abcdef0123456789abcdef",
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
            recentSuccessBucket = 0,
            recentHandshakeBucket = 0,
            resourcePressureBucket = 0
        )
    }

    private fun outcome(
        contextKey: String,
        strategy: TurboStrategyId,
        success: Boolean,
        latencyMs: Long,
        timedOut: Boolean = false,
        completedAtMs: Long = 1_000L,
        sharedContextKey: String = "",
        failureCategory: TurboFailureCategory = if (success) {
            TurboFailureCategory.NONE
        } else {
            TurboFailureCategory.TIMEOUT
        }
    ): TurboOutcome {
        return TurboOutcome(
            contextKey = contextKey,
            strategy = strategy,
            policy = TurboDecisionPolicy.LEARNED,
            success = success,
            handshakeLatencyMs = latencyMs,
            connectionDurationMs = if (success) 20_000L else latencyMs,
            bytesUp = if (success) 200_000L else 0L,
            bytesDown = if (success) 800_000L else 0L,
            retries = if (success) 0 else 1,
            timedOut = timedOut,
            abruptDisconnect = false,
            fallbackUsed = false,
            decisionOverheadNanos = 50_000L,
            batterySaver = false,
            failureCategory = failureCategory,
            completedAtMs = completedAtMs,
            sharedContextKey = sharedContextKey
        )
    }

    private class SequenceRandom(
        private val doubles: ArrayDeque<Double>,
        private val ints: ArrayDeque<Int>
    ) : TurboRandom {
        override fun nextDouble(): Double = if (doubles.isEmpty()) 0.5 else doubles.removeFirst()
        override fun nextInt(bound: Int): Int {
            val value = if (ints.isEmpty()) 0 else ints.removeFirst()
            return value.mod(bound)
        }
    }
}
