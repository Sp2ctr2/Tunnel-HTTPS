package com.tunnelvpn.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendResilienceTest {
    @Test
    fun safeBurstNeverExceedsHardLimitAndUsesBoundedTokens() {
        var now = 0L
        val controller = SafeBurstController(2, 4, 2) { now }
        val healthy = ResourcePressure(false, false, false, 0.1, 0.0, false, false, RiskLevel.NORMAL)

        assertTrue(controller.admit(1, healthy))
        assertTrue(controller.admit(2, healthy))
        assertTrue(controller.admit(3, healthy))
        assertFalse(controller.admit(4, healthy))
        assertEquals(0, controller.available())
        now += 2_000L
        assertEquals(2, controller.available())
    }

    @Test
    fun burstStopsImmediatelyUnderPressure() {
        val controller = SafeBurstController(2, 4, 2)
        val pressured = ResourcePressure(true, false, false, 0.1, 0.0, false, false, RiskLevel.NORMAL)

        assertFalse(controller.admit(2, pressured))
    }

    @Test
    fun burstCanActuallyOpenBetweenSoftAndHardCapacity() {
        val controller = SafeBurstController(28, 32, 4)
        val healthyAtSoftLimit = ResourcePressure(
            false,
            false,
            false,
            28.0 / 32.0,
            0.0,
            false,
            false,
            RiskLevel.NORMAL
        )

        assertTrue(controller.admit(28, healthyAtSoftLimit))
        assertTrue(controller.admit(29, healthyAtSoftLimit.copy(queueUtilization = 29.0 / 32.0)))
        assertTrue(controller.admit(30, healthyAtSoftLimit.copy(queueUtilization = 30.0 / 32.0)))
        assertTrue(controller.admit(31, healthyAtSoftLimit.copy(queueUtilization = 31.0 / 32.0)))
        assertFalse(controller.admit(32, healthyAtSoftLimit.copy(queueUtilization = 1.0)))
    }

    @Test
    fun networkGenerationRejectsOldIdentity() {
        val tracker = NetworkGenerationTracker()
        val wifi = tracker.onAvailable("wifi")
        val cellular = tracker.onAvailable("cell")

        assertFalse(tracker.isCurrent(wifi))
        assertTrue(tracker.isCurrent(cellular))
        assertEquals(cellular, tracker.onAvailable("cell"))
    }

    @Test
    fun networkGenerationCannotAbaAcrossLossAndReuse() {
        val tracker = NetworkGenerationTracker()
        val first = tracker.onAvailable("net-7")
        val lost = tracker.onLost("net-7")
        val reused = tracker.onAvailable("net-7")

        assertTrue(lost > first)
        assertTrue(reused > lost)
        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(reused))
        val invalidated = tracker.invalidate()
        assertTrue(invalidated > reused)
        assertFalse(tracker.isCurrent(reused))
    }

    @Test
    fun concurrentContextPublicationsHaveAUniqueMonotonicGeneration() {
        val tracker = NetworkGenerationTracker()
        tracker.onAvailable("net")
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        repeat(16) {
            Thread {
                assertTrue(start.await(2, TimeUnit.SECONDS))
                tracker.onContextChanged("net")
                done.countDown()
            }.apply { isDaemon = true }.start()
        }

        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(17L, tracker.current())
    }

    @Test
    fun riskEngineUsesHysteresisAndBoundedScore() {
        var now = 0L
        val engine = ExplainableRiskEngine(clock = { now }, windowMs = 1_000L)

        assertEquals(RiskLevel.ELEVATED, engine.record(RiskSignal.INVARIANT_VIOLATION).level)
        assertEquals(RiskLevel.HIGH, engine.record(RiskSignal.DNSSEC_BOGUS).level)
        now = 2_000L
        assertEquals(RiskLevel.ELEVATED, engine.current().level)
        assertEquals(RiskLevel.NORMAL, engine.current().level)
    }

}
