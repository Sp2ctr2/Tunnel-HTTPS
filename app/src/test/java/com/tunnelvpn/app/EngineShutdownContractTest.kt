package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineShutdownContractTest {
    private val engine = File("src/main/java/com/tunnelvpn/app/LocalProtectionEngine.kt").readText()
    private val tcp = File("src/main/java/com/tunnelvpn/app/TurboTcpForwarder.kt").readText()
    private val udp = File("src/main/java/com/tunnelvpn/app/TurboUdpForwarder.kt").readText()
    private val activity = File("src/main/java/com/tunnelvpn/app/MainActivity.kt").readText()
    private val service = File("src/main/java/com/tunnelvpn/app/TunnelVpnService.kt").readText()

    @Test
    fun engineStopsAcceptingAndClosesTunBeforeForwarders() {
        val stopBody = engine.substringAfter("fun stop() {").substringBefore("private suspend fun packetLoop")
        assertTrue(stopBody.indexOf("stopping.compareAndSet") < stopBody.indexOf("closeResourcesQuietly()"))
        assertTrue(stopBody.indexOf("closeResourcesQuietly()") < stopBody.indexOf("turboForwarder?.closeAll()"))
        assertTrue(engine.contains("while (scope.isActive && !stopping.get())"))
    }

    @Test
    fun forwardersRejectWorkAfterIdempotentClose() {
        assertTrue(tcp.contains("if (closed.get()) return false"))
        assertTrue(tcp.contains("if (!closed.compareAndSet(false, true)) return"))
        assertTrue(udp.contains("if (closed.get()) return false"))
        assertTrue(udp.contains("if (!closed.compareAndSet(false, true)) return"))
    }

    @Test
    fun activityWaitsForTheServiceDisconnectBroadcast() {
        val stopBody = activity.substringAfter("private fun requestStop() {").substringBefore("private fun syncStateToWeb")
        assertTrue(!stopBody.contains("syncStateToWeb"))
        assertTrue(!stopBody.contains("postDelayed"))
    }

    @Test
    fun replacementStartsBeforeTheExistingEngineIsRetired() {
        val startBody = service.substringAfter("private fun startProtection(")
            .substringBefore("private fun stopProtection(")
        val commitBody = startBody.substringAfter("val candidate = LocalProtectionEngine")
            .substringBefore("return true")
        val candidateStart = commitBody.indexOf("candidate.start()")
        val publishCandidate = commitBody.indexOf("engine = candidate")
        val retirePrevious = commitBody.indexOf("previousEngine?.stop()")

        assertTrue(candidateStart >= 0)
        assertTrue(publishCandidate > candidateStart)
        assertTrue(retirePrevious > publishCandidate)
        assertTrue(commitBody.indexOf("candidate.activateRuntime()") > retirePrevious)
    }

    @Test
    fun failedReplacementIsFullyCleanedAndExistingProtectionIsRestored() {
        val startBody = service.substringAfter("private fun startProtection(")
            .substringBefore("private fun stopProtection(")
        val failureBody = startBody.substringAfter("} catch (error: Exception) {")
        val retainedBody = failureBody.substringAfter("if (previousEngine != null && !replacementCommitted) {")
            .substringBefore("return false")

        assertTrue(failureBody.contains("replacement?.didEstablishInterface() == true"))
        assertTrue(failureBody.indexOf("replacement?.stop()") >= 0)
        assertTrue(failureBody.indexOf("preparedNetworkHandler?.stop()") >= 0)
        assertTrue(retainedBody.contains("engine = previousEngine"))
        assertTrue(retainedBody.contains("networkHandler = previousNetworkHandler"))
        assertTrue(retainedBody.contains("putBoolean(PREF_ACTIVE, true)"))
        assertTrue(retainedBody.contains("STATE_CONNECTED"))
        assertTrue(retainedBody.contains("generation"))
        assertTrue(retainedBody.contains("transitionReason"))
        assertTrue(!retainedBody.contains("stopSelf()"))
        assertTrue(!retainedBody.contains("stopEngineOnly()"))
    }

    @Test
    fun committedReplacementFailureReestablishesThePreviousConfigBeforeConnected() {
        val startBody = service.substringAfter("private fun startProtection(")
            .substringBefore("private fun stopProtection(")
        val failureBody = startBody.substringAfter("} catch (error: Exception) {")
        val rollbackBody = failureBody.substringAfter("if (previousEngine != null && previousConfig != null) {")
            .substringBefore("} catch (rollbackError: Exception) {")
        val rollbackStart = rollbackBody.indexOf("rollbackCandidate.start()")
        val publishRollback = rollbackBody.indexOf("engine = rollbackCandidate")
        val retirePrevious = rollbackBody.indexOf("previousEngine.stop()")
        val connected = rollbackBody.indexOf("STATE_CONNECTED")

        assertTrue(rollbackStart >= 0)
        assertTrue(publishRollback > rollbackStart)
        assertTrue(retirePrevious > publishRollback)
        assertTrue(rollbackBody.indexOf("rollbackCandidate.activateRuntime()") > retirePrevious)
        assertTrue(connected > retirePrevious)
        assertTrue(rollbackBody.contains("activeConfig = previousConfig"))
        assertTrue(rollbackBody.contains("persistActiveConfig(previousConfig)"))
        assertTrue(failureBody.contains("STATE_ERROR, \"Local protection could not start.\", generation, transitionReason"))
    }

    @Test
    fun healthBroadcastDoesNotResetTheConnectedEpoch() {
        val broadcastBody = service.substringAfter("private fun broadcastState(")
            .substringBefore("companion object")

        assertTrue(broadcastBody.contains("state == STATE_CONNECTED && connectedSinceElapsedMs <= 0L"))
        assertTrue(!broadcastBody.contains("if (state == STATE_CONNECTED) connectedSinceElapsedMs"))
        assertTrue(broadcastBody.contains("putExtra(EXTRA_CONNECTED_SECONDS, snapshot.connectedSeconds)"))
        assertTrue(broadcastBody.contains("putExtra(EXTRA_SERVICE_GENERATION, snapshot.serviceGeneration)"))
        assertTrue(broadcastBody.contains("putExtra(EXTRA_TRANSITION_REASON, snapshot.transitionReason)"))
    }

    @Test
    fun startTransitionCarriesOneGenerationAndReasonToBothTerminalPaths() {
        val startBody = service.substringAfter("private fun startProtection(")
            .substringBefore("private fun stopProtection(")

        assertTrue(startBody.contains("val generation = beginServiceTransition(transitionReason)"))
        assertTrue(startBody.contains("STATE_CONNECTING, serviceGeneration = generation, transitionReason = transitionReason"))
        assertTrue(startBody.contains("STATE_CONNECTED, serviceGeneration = generation, transitionReason = transitionReason"))
        assertTrue(startBody.contains("STATE_ERROR, \"Local protection could not start.\", generation, transitionReason"))
    }

    @Test
    fun serviceGenerationIsMonotonicAcrossServiceInstancesAndWrapperExposesTheContract() {
        val wrapper = service.substringAfter("class TunnelVpnService : TunnelHttpsVpnService()")
        val beginTransition = service.substringAfter("private fun beginServiceTransition(")
            .substringBefore("private fun restoreTurboRuntime")

        assertTrue(service.contains("private val serviceGenerationSeed = AtomicLong(0L)"))
        assertTrue(service.contains("startGeneration = serviceGenerationSeed.incrementAndGet()"))
        assertTrue(!beginTransition.contains("currentStateSnapshotShared ="))
        assertTrue(wrapper.contains("const val EXTRA_SERVICE_GENERATION = TunnelHttpsVpnService.EXTRA_SERVICE_GENERATION"))
        assertTrue(wrapper.contains("const val EXTRA_TRANSITION_REASON = TunnelHttpsVpnService.EXTRA_TRANSITION_REASON"))
        assertTrue(wrapper.contains("TRANSITION_REASON_USER_START"))
        assertTrue(wrapper.contains("TRANSITION_REASON_CONFIG_REFRESH"))
        assertTrue(wrapper.contains("TRANSITION_REASON_TURBO_REFRESH"))
        assertTrue(wrapper.contains("TRANSITION_REASON_AUTO_START"))
        assertTrue(wrapper.contains("TRANSITION_REASON_ENGINE_FAILURE"))
        assertTrue(wrapper.contains("fun currentServiceGeneration(): Long = TunnelHttpsVpnService.currentServiceGeneration()"))
        assertTrue(wrapper.contains("fun currentTransitionReason(): String = TunnelHttpsVpnService.currentTransitionReason()"))
        assertTrue(wrapper.contains("fun currentStateSnapshot(): TunnelServiceStateSnapshot"))
        assertTrue(service.contains("data class TunnelServiceStateSnapshot("))
        assertTrue(service.contains("val connectedSinceElapsedMs: Long"))
        assertTrue(service.contains("val connectedSeconds: Long"))
        assertTrue(service.contains("currentStateSnapshotShared = snapshot"))
        assertTrue(service.contains("handleFatalPacketLoop(candidate, generation, reason)"))
        assertTrue(service.contains("if (engine !== failedEngine || serviceGeneration != startGeneration) return"))
        assertTrue(service.contains("STATE_ERROR,"))
        assertTrue(service.contains("putExtra(EXTRA_SERVICE_GENERATION, snapshot.serviceGeneration)"))
        assertTrue(service.contains("putExtra(EXTRA_TRANSITION_REASON, snapshot.transitionReason)"))
        assertTrue(service.contains("if (serviceGeneration != startGeneration) return"))
        assertTrue(service.contains("serviceGeneration < currentStateSnapshotShared.serviceGeneration"))
        val broadcastPrefix = service.substringBefore("private fun broadcastState(").takeLast(80)
        assertTrue(broadcastPrefix.contains("@Synchronized"))
    }
}
