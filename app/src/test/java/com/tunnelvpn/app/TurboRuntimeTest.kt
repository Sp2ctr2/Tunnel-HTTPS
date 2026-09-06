package com.tunnelvpn.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TurboRuntimeTest {
    @Before
    fun setUp() {
        TurboRuntimeController.resetForTest()
    }

    @After
    fun tearDown() {
        TurboRuntimeController.resetForTest()
    }

    @Test
    fun activationRequiresMatchingGenerationAndDesiredState() {
        val enabling = TurboRuntimeController.beginChange(true, true)
        assertEquals(TurboRuntimeState.ENABLING, enabling.state)

        val active = TurboRuntimeController.activated(enabling.generation)

        assertEquals(TurboRuntimeState.ACTIVE, active.state)
        assertTrue(active.desired)
    }

    @Test
    fun staleActivationCannotReactivateDisabledTurbo() {
        val enabling = TurboRuntimeController.beginChange(true, true)
        val disabling = TurboRuntimeController.beginChange(false, true)

        TurboRuntimeController.activated(enabling.generation)
        val result = TurboRuntimeController.disabled(disabling.generation)

        assertEquals(TurboRuntimeState.DISABLED, result.state)
        assertFalse(result.desired)
    }

    @Test
    fun repeatedRequestIsIdempotentWhenStable() {
        val first = TurboRuntimeController.beginChange(true, true)
        TurboRuntimeController.activated(first.generation)

        val repeated = TurboRuntimeController.beginChange(true, true)

        assertEquals(first.generation, repeated.generation)
        assertEquals(TurboRuntimeState.ACTIVE, repeated.state)
    }

    @Test
    fun desiredPreferenceWithoutVpnIsDegradedNotActive() {
        val result = TurboRuntimeController.restorePreference(true, false)

        assertEquals(TurboRuntimeState.DEGRADED, result.state)
        assertTrue(result.desired)
    }

    @Test
    fun failedUnderlyingNetworkProbeKeepsTurboDesiredButDegraded() {
        val enabling = TurboRuntimeController.beginChange(true, true)

        val result = TurboRuntimeController.degraded(enabling.generation, "underlying-https")

        assertEquals(TurboRuntimeState.DEGRADED, result.state)
        assertEquals("underlying-https", result.reasonCode)
        assertTrue(result.desired)
    }

    @Test
    fun retiredAiRuntimeCannotClearTheCurrentRuntimeStatus() {
        val previous = TurboAiStatus(true, true, 10, TurboConfidence.MEDIUM, "active")
        val current = TurboAiStatus(true, true, 20, TurboConfidence.HIGH, "active")
        TurboAiStatusRegistry.set(101L, previous)
        TurboAiStatusRegistry.set(202L, current)

        TurboAiStatusRegistry.clear(101L)

        assertEquals(current, TurboAiStatusRegistry.current())
        TurboAiStatusRegistry.clear(202L)
        assertNull(TurboAiStatusRegistry.current())
    }
}
