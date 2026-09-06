package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboAiIntegrationContractTest {
    private val forwarder = File("src/main/java/com/tunnelvpn/app/TurboTcpForwarder.kt").readText()
    private val engine = File("src/main/java/com/tunnelvpn/app/LocalProtectionEngine.kt").readText()
    private val runtime = File("src/main/java/com/tunnelvpn/app/TurboAiRuntime.kt").readText()
    private val store = File("src/main/java/com/tunnelvpn/app/TurboModelStore.kt").readText()
    private val html = File("src/main/assets/index.html").readText()

    @Test
    fun aiIsConnectedToTheRealClientHelloFallbackPath() {
        assertTrue(forwarder.contains("turboAiRuntime?.select("))
        assertTrue(forwarder.contains("markSuccessfulAiAttempt"))
        assertTrue(forwarder.contains("recordFailedAiAttempt"))
        assertTrue(forwarder.contains("reportSuccessfulAiOutcome"))
        assertTrue(engine.contains("turboAiRuntime = turboAiRuntime"))
    }

    @Test
    fun suppressionAndAiShareTheRuntimeNetworkContext() {
        assertTrue(forwarder.contains("turboAiRuntime?.suppressionContextKey()"))
        assertTrue(forwarder.contains("suppressionNetworkGeneration.incrementAndGet()"))
        assertTrue(runtime.contains("suppressionNetworkGeneration.incrementAndGet()"))
        assertTrue(runtime.contains("network.transport.name"))
        assertTrue(forwarder.contains(".filter { it in baselineModes }"))
    }

    @Test
    fun predictionDoesNotReadPersistentStorage() {
        val selectBody = runtime.substringAfter("fun select(").substringBefore("fun recordOutcome")
        assertFalse(selectBody.contains("SharedPreferences"))
        assertFalse(selectBody.contains("store.load"))
        assertFalse(selectBody.contains("store.save"))
    }

    @Test
    fun rolloutControlsAreInternalAndProgressUsesExistingStatusLog() {
        assertTrue(store.contains("PREF_EXPLORATION_ENABLED"))
        assertTrue(store.contains("PREF_MODEL_UPDATES_ENABLED"))
        assertTrue(store.contains("PREF_DEVELOPER_DIAGNOSTICS"))
        assertTrue(store.contains("PREF_FORCE_BASELINE"))
        assertFalse(html.contains("""id="turboAiToggle""""))
        assertFalse(html.contains("""id="turboAiReset""""))
        assertTrue(html.contains("TunnelAndroid.getTurboAiStatus"))
        assertTrue(html.contains("""logStatus("turboAi""""))
        assertTrue(html.contains("Turbo AI가 네트워크 성능을 로컬에서 학습 중"))
        assertFalse(html.contains("AI 정확도"))
        assertFalse(html.contains("속도를 보장"))
    }
}
