package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class V4AiOwnershipTest {
    @Test
    fun latePublicationAndCloseCannotReplaceNewerRuntimeStatus() {
        val old = TurboAiStatus(true, true, 1L, TurboConfidence.LOW, "old")
        val current = old.copy(samples = 2L, state = "current")
        TurboAiStatusRegistry.set(old)
        try {
            TurboAiStatusRegistry.set(100L, old)
            TurboAiStatusRegistry.set(101L, current)
            TurboAiStatusRegistry.set(100L, old.copy(state = "late-model-load"))
            TurboAiStatusRegistry.clear(100L)
            assertEquals(current, TurboAiStatusRegistry.current())
        } finally {
            TurboAiStatusRegistry.clear(101L)
        }
    }
}
