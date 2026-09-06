package com.tunnelvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

data class TunnelServiceStateSnapshot(
    val state: String,
    val connectedSinceElapsedMs: Long,
    val connectedSeconds: Long,
    val serviceGeneration: Long,
    val transitionReason: String
)

internal class ServiceInstanceOwnership {
    private var activeInstanceId = 0L

    @Synchronized
    fun claim(instanceId: Long) {
        activeInstanceId = instanceId
    }

    @Synchronized
    fun isOwner(instanceId: Long): Boolean = activeInstanceId == instanceId

    @Synchronized
    fun runIfOwner(instanceId: Long, block: () -> Unit): Boolean {
        if (activeInstanceId != instanceId) return false
        block()
        return true
    }
}

internal class ServiceLifecycleCommitGuard(
    private val instanceId: Long,
    private val ownership: ServiceInstanceOwnership
) {
    @Volatile private var destroyed = false

    @Synchronized
    fun destroy() {
        destroyed = true
    }

    fun isCurrent(): Boolean = !destroyed && ownership.isOwner(instanceId)

    fun runIfCurrent(block: () -> Unit): Boolean {
        if (destroyed) return false
        var committed = false
        ownership.runIfOwner(instanceId) {
            synchronized(this) {
                if (!destroyed) {
                    block()
                    committed = true
                }
            }
        }
        return committed
    }
}

internal class ProcessLifecycleWorkGate {
    private val lock = ReentrantLock(true)

    fun <T> run(block: () -> T): T = lock.withLock(block)
}

internal fun <T : Any> lifecycleResourcesToClose(
    current: T?,
    prepared: T?,
    previous: T?
): List<T> {
    val seen = IdentityHashMap<T, Boolean>()
    return listOfNotNull(current, prepared, previous).filter { value ->
        seen.put(value, true) == null
    }
}

private class SupersededServiceLifecycleException : Exception()

internal class PendingEngineFailureRegistry<E : Any> {
    private val failures = ConcurrentHashMap<E, String>()

    fun record(engine: E, reason: String) {
        failures.putIfAbsent(engine, reason)
    }

    fun consume(engine: E): String? = failures.remove(engine)

    fun discard(engine: E?) {
        if (engine != null) failures.remove(engine)
    }
}

internal fun <T : Any> isCurrentLifecycleCallback(
    activeSource: T?,
    activeEpoch: Long,
    callbackSource: T,
    callbackEpoch: Long,
    ownsServiceInstance: Boolean
): Boolean {
    return ownsServiceInstance && activeEpoch == callbackEpoch && activeSource === callbackSource
}

private data class ActiveDiagnosticsConfigSnapshot(
    val activeProtectionMode: ProtectionMode,
    val adBlockEnabled: Boolean,
    val bypassedPackages: Int,
    val turboModeEnabled: Boolean,
    val turboQuicGuardEnabled: Boolean,
    val routeMode: RouteMode,
    val turboRouteAllIPv4Requested: Boolean,
    val turboRouteAllIPv4Enabled: Boolean,
    val turboForwarderPreflightPassed: Boolean,
    val ipv6HandlingStatus: String,
    val httpsMetadataMode: String
) {
    fun restore(diagnostics: DiagnosticsState) {
        diagnostics.serviceRunning = true
        diagnostics.activeProtectionMode = activeProtectionMode
        diagnostics.adBlockEnabled = adBlockEnabled
        diagnostics.bypassedPackages = bypassedPackages
        diagnostics.turboModeEnabled = turboModeEnabled
        diagnostics.turboQuicGuardEnabled = turboQuicGuardEnabled
        diagnostics.routeMode = routeMode
        diagnostics.turboRouteAllIPv4Requested = turboRouteAllIPv4Requested
        diagnostics.turboRouteAllIPv4Enabled = turboRouteAllIPv4Enabled
        diagnostics.turboForwarderPreflightPassed = turboForwarderPreflightPassed
        diagnostics.ipv6HandlingStatus = ipv6HandlingStatus
        diagnostics.httpsMetadataMode = httpsMetadataMode
    }

    companion object {
        fun capture(diagnostics: DiagnosticsState) = ActiveDiagnosticsConfigSnapshot(
            activeProtectionMode = diagnostics.activeProtectionMode,
            adBlockEnabled = diagnostics.adBlockEnabled,
            bypassedPackages = diagnostics.bypassedPackages,
            turboModeEnabled = diagnostics.turboModeEnabled,
            turboQuicGuardEnabled = diagnostics.turboQuicGuardEnabled,
            routeMode = diagnostics.routeMode,
            turboRouteAllIPv4Requested = diagnostics.turboRouteAllIPv4Requested,
            turboRouteAllIPv4Enabled = diagnostics.turboRouteAllIPv4Enabled,
            turboForwarderPreflightPassed = diagnostics.turboForwarderPreflightPassed,
            ipv6HandlingStatus = diagnostics.ipv6HandlingStatus,
            httpsMetadataMode = diagnostics.httpsMetadataMode
        )
    }
}

open class TunnelHttpsVpnService : VpnService() {
    private val inheritedStateSnapshot = currentStateSnapshot()
    private val serviceInstanceId = serviceInstanceSeed.incrementAndGet()
    private val lifecycleCommitGuard = ServiceLifecycleCommitGuard(serviceInstanceId, serviceInstanceOwnership)
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TunnelVpnLifecycle").apply { isDaemon = true }
    }
    @Volatile private var serviceDestroyed = false
    @Volatile private var engine: LocalProtectionEngine? = null
    @Volatile private var pendingEngine: LocalProtectionEngine? = null
    private val pendingEngineFailures = PendingEngineFailureRegistry<LocalProtectionEngine>()
    private val engineCommandStartIds = ConcurrentHashMap<LocalProtectionEngine, Int>()
    @Volatile private var activeConfig: LocalProtectionEngine.Config? = null
    private var networkHandler: NetworkChangeHandler? = null
    @Volatile private var networkHandlerEpoch = 0L
    private var networkHandlerEpochSeed = 0L
    @Volatile private var watchdogThread: Thread? = null
    private var connectedSinceElapsedMs: Long = inheritedStateSnapshot.connectedSinceElapsedMs
    @Volatile private var startGeneration: Long = inheritedStateSnapshot.serviceGeneration
    @Volatile private var activeTransitionReason: String = inheritedStateSnapshot.transitionReason

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP && !VpnDisclosureConsent.isAccepted(this)) {
            claimServiceInstance()
            dispatchLifecycle { rejectMissingDisclosure(startId) }
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SET_TURBO -> {
                if (!intent.hasExtra(EXTRA_TURBO_DESIRED)) return START_NOT_STICKY
                claimServiceInstance()
                if (currentState == STATE_CONNECTED) startForegroundCompat(buildNotification())
                val desired = intent.getBooleanExtra(EXTRA_TURBO_DESIRED, false)
                dispatchLifecycle {
                    setTurboDesired(desired, startId)
                    if (currentState != STATE_CONNECTED) stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                claimServiceInstance()
                val transitionReason = sanitizeTransitionReason(
                    intent.getStringExtra(EXTRA_TRANSITION_REASON),
                    TRANSITION_REASON_USER_STOP
                )
                dispatchLifecycle {
                    stopProtection(
                        clearActive = true,
                        transitionReason = transitionReason,
                        removeForeground = false
                    )
                    stopForegroundIfLatest(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                claimServiceInstance()
                startForegroundCompat(buildNotification())
                dispatchLifecycle { startProtection(intent, commandStartId = startId) }
            }
            null -> {
                if (!prefs().getBoolean(PREF_ACTIVE, false)) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                claimServiceInstance()
                startForegroundCompat(buildNotification())
                dispatchLifecycle {
                    startProtection(
                        intent = null,
                        explicitReason = TRANSITION_REASON_SERVICE_RESTORE,
                        commandStartId = startId
                    )
                }
            }
            else -> return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    @Synchronized
    private fun rejectMissingDisclosure(startId: Int) {
        val generation = beginServiceTransition(TRANSITION_REASON_DISCLOSURE_REQUIRED)
        prefs().edit().putBoolean(PREF_ACTIVE, false).apply()
        diagnostics.serviceRunning = false
        stopEngineOnly()
        activeConfig = null
        broadcastState(
            STATE_ERROR,
            "VPN disclosure consent is required.",
            generation,
            TRANSITION_REASON_DISCLOSURE_REQUIRED
        )
        stopForegroundIfLatest(startId)
    }

    override fun onRevoke() {
        dispatchLifecycle {
            stopProtection(clearActive = true, transitionReason = TRANSITION_REASON_VPN_REVOKED)
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceDestroyed = true
        lifecycleCommitGuard.destroy()
        try {
            lifecycleExecutor.execute {
                processLifecycleWorkGate.run {
                    destroyServiceInstance()
                }
            }
        } catch (_: RejectedExecutionException) {
        }
        lifecycleExecutor.shutdown()
        super.onDestroy()
    }

    @Synchronized
    private fun startProtection(
        intent: Intent?,
        explicitReason: String? = null,
        rollbackTurboSnapshot: TurboRuntimeSnapshot? = null,
        commandStartId: Int
    ): Boolean {
        if (!lifecycleCommitGuard.isCurrent()) return false
        val previousEngine = engine
        val previousConfig = activeConfig
        val previousNetworkHandler = networkHandler
        val previousDiagnostics = previousEngine?.let { ActiveDiagnosticsConfigSnapshot.capture(diagnostics) }
        val retainedTurboSnapshot = rollbackTurboSnapshot ?: TurboRuntimeController.snapshot()
        val transitionReason = sanitizeTransitionReason(
            explicitReason ?: intent?.getStringExtra(EXTRA_TRANSITION_REASON),
            when {
                previousEngine != null -> TRANSITION_REASON_CONFIG_REFRESH
                intent == null -> TRANSITION_REASON_SERVICE_RESTORE
                else -> TRANSITION_REASON_USER_START
            }
        )
        val generation = beginServiceTransition(transitionReason)

        if (previousEngine == null) {
            diagnostics.resetSession(
                intent?.takeIf { it.hasExtra(EXTRA_AD_BLOCK) }?.getBooleanExtra(EXTRA_AD_BLOCK, true)
                    ?: prefs().getBoolean(PREF_ADBLOCK_ENABLED, true),
                intent?.takeIf { it.hasExtra(EXTRA_TURBO_MODE) }?.getBooleanExtra(EXTRA_TURBO_MODE, false)
                    ?: prefs().getBoolean(PREF_TURBO_MODE, false)
            )
        }

        val vpnPermissionGranted = prepare(this) == null
        if (!lifecycleCommitGuard.isCurrent()) return false
        diagnostics.vpnPermissionGranted = vpnPermissionGranted
        if (!vpnPermissionGranted) {
            stopEngineOnly()
            activeConfig = null
            lifecycleCommitGuard.runIfCurrent {
                prefs().edit().putBoolean(PREF_ACTIVE, false).apply()
                diagnostics.lastProtectionFailureReason = "vpn permission missing"
                diagnostics.serviceRunning = false
                broadcastState(STATE_ERROR, "VPN permission is missing.", generation, transitionReason)
                stopForegroundIfLatest(commandStartId)
            }
            return false
        }

        broadcastState(STATE_CONNECTING, serviceGeneration = generation, transitionReason = transitionReason)
        if (previousEngine != null) {
            diagnostics.duplicateServiceDetected = true
            diagnostics.recordTunnelRestart()
        }
        var config: LocalProtectionEngine.Config? = null
        var replacement: LocalProtectionEngine? = null
        var preparedNetworkHandler: NetworkChangeHandler? = null
        var preparedNetworkHandlerEpoch = 0L
        try {
            val requestedConfig = readConfig(intent)
            config = requestedConfig
            diagnostics.serviceRunning = true
            diagnostics.activeProtectionMode = requestedConfig.mode
            diagnostics.adBlockEnabled = requestedConfig.adBlockEnabled
            if (previousNetworkHandler == null) {
                preparedNetworkHandlerEpoch = ++networkHandlerEpochSeed
                lateinit var candidateNetworkHandler: NetworkChangeHandler
                candidateNetworkHandler = NetworkChangeHandler(
                    getSystemService(ConnectivityManager::class.java),
                    diagnostics,
                    onNetworkChanged = {
                        dispatchNetworkReset(candidateNetworkHandler, preparedNetworkHandlerEpoch)
                    },
                    onNetworkContextChanged = {
                        dispatchCapabilityContext(candidateNetworkHandler, preparedNetworkHandlerEpoch)
                    },
                    onUnderlyingContextChanged = { context ->
                        dispatchUnderlyingContext(candidateNetworkHandler, preparedNetworkHandlerEpoch, context)
                    }
                )
                preparedNetworkHandler = candidateNetworkHandler
                candidateNetworkHandler.start()
            }
            lateinit var candidate: LocalProtectionEngine
            candidate = LocalProtectionEngine(this, requestedConfig, diagnostics) { reason ->
                handleFatalPacketLoop(candidate, generation, reason)
            }
            replacement = candidate
            engineCommandStartIds[candidate] = commandStartId
            pendingEngine = candidate
            val activeNetworkHandler = previousNetworkHandler ?: preparedNetworkHandler
            activeNetworkHandler?.currentContext()?.let(candidate::updateUnderlyingNetworkContext)
            if (!lifecycleCommitGuard.isCurrent()) throw SupersededServiceLifecycleException()
            candidate.start()
            if (!lifecycleCommitGuard.isCurrent()) throw SupersededServiceLifecycleException()
            pendingEngineFailures.consume(candidate)?.let { reason ->
                error("Engine failed during startup: ${DiagnosticsState.sanitize(reason)}")
            }
            engine = candidate
            pendingEngine = null
            activeConfig = requestedConfig
            networkHandler = previousNetworkHandler ?: preparedNetworkHandler
            if (previousNetworkHandler == null) networkHandlerEpoch = preparedNetworkHandlerEpoch
            preparedNetworkHandler = null
            runCatching { previousEngine?.stop() }
            if (previousEngine != null) engineCommandStartIds.remove(previousEngine)
            candidate.activateRuntime()
            if (!lifecycleCommitGuard.isCurrent()) throw SupersededServiceLifecycleException()
            val preserveTurboRuntime = transitionReason == TRANSITION_REASON_CONFIG_REFRESH &&
                requestedConfig.turboModeEnabled && retainedTurboSnapshot.desired
            lateinit var turboSnapshot: TurboRuntimeSnapshot
            val committed = lifecycleCommitGuard.runIfCurrent {
                turboSnapshot = if (preserveTurboRuntime) {
                    restoreTurboRuntime(retainedTurboSnapshot)
                } else {
                    TurboRuntimeController.restorePreference(requestedConfig.turboModeEnabled, true)
                }
                persistActiveConfig(requestedConfig)
                prefs().edit().putBoolean(PREF_ACTIVE, true).apply()
                if (!requestedConfig.turboModeEnabled) TurboRuntimeController.disabled(turboSnapshot.generation)
                broadcastState(STATE_CONNECTED, serviceGeneration = generation, transitionReason = transitionReason)
            }
            if (!committed) throw SupersededServiceLifecycleException()
            if (requestedConfig.turboModeEnabled && !preserveTurboRuntime) {
                runUnderlyingNetworkProbe(generation, turboSnapshot.generation)
            }
            return true
        } catch (_: SupersededServiceLifecycleException) {
            abandonSupersededStart(replacement, previousEngine, preparedNetworkHandler, previousNetworkHandler)
            return false
        } catch (error: Exception) {
            if (pendingEngine === replacement) pendingEngine = null
            pendingEngineFailures.discard(replacement)
            if (replacement != null) engineCommandStartIds.remove(replacement)
            val replacementCommitted = replacement?.didEstablishInterface() == true
            runCatching { replacement?.stop() }
            if (!lifecycleCommitGuard.isCurrent()) {
                abandonSupersededStart(replacement, previousEngine, preparedNetworkHandler, previousNetworkHandler)
                return false
            }
            diagnostics.lastProtectionFailureReason = DiagnosticsState.sanitize(error.message ?: error.javaClass.simpleName)
            if (previousEngine != null && !replacementCommitted) {
                runCatching { preparedNetworkHandler?.stop() }
                engine = previousEngine
                activeConfig = previousConfig
                networkHandler = previousNetworkHandler
                val retained = lifecycleCommitGuard.runIfCurrent {
                    previousDiagnostics?.restore(diagnostics)
                    previousConfig?.let { persistActiveConfig(it) }
                    prefs().edit().putBoolean(PREF_ACTIVE, true).apply()
                    restoreTurboRuntime(retainedTurboSnapshot)
                    broadcastState(
                        STATE_CONNECTED,
                        "New protection settings could not be applied; existing protection remains active.",
                        generation,
                        transitionReason
                    )
                }
                if (!retained) {
                    abandonSupersededStart(replacement, previousEngine, preparedNetworkHandler, previousNetworkHandler)
                }
                return false
            }
            if (previousEngine != null && previousConfig != null) {
                var rollbackEngine: LocalProtectionEngine? = null
                try {
                    lateinit var rollbackCandidate: LocalProtectionEngine
                    rollbackCandidate = LocalProtectionEngine(this, previousConfig, diagnostics) { reason ->
                        handleFatalPacketLoop(rollbackCandidate, generation, reason)
                    }
                    rollbackEngine = rollbackCandidate
                    engineCommandStartIds[rollbackCandidate] = commandStartId
                    pendingEngine = rollbackCandidate
                    diagnostics.adBlockEnabled = previousConfig.adBlockEnabled
                    val activeNetworkHandler = previousNetworkHandler ?: preparedNetworkHandler
                    activeNetworkHandler?.currentContext()?.let(rollbackCandidate::updateUnderlyingNetworkContext)
                    if (!lifecycleCommitGuard.isCurrent()) {
                        abandonSupersededStart(
                            rollbackCandidate,
                            previousEngine,
                            preparedNetworkHandler,
                            previousNetworkHandler
                        )
                        return false
                    }
                    rollbackCandidate.start()
                    if (!lifecycleCommitGuard.isCurrent()) {
                        abandonSupersededStart(
                            rollbackCandidate,
                            previousEngine,
                            preparedNetworkHandler,
                            previousNetworkHandler
                        )
                        return false
                    }
                    pendingEngineFailures.consume(rollbackCandidate)?.let { reason ->
                        error("Rollback engine failed during startup: ${DiagnosticsState.sanitize(reason)}")
                    }
                    engine = rollbackCandidate
                    pendingEngine = null
                    activeConfig = previousConfig
                    networkHandler = previousNetworkHandler ?: preparedNetworkHandler
                    if (previousNetworkHandler == null) networkHandlerEpoch = preparedNetworkHandlerEpoch
                    preparedNetworkHandler = null
                    runCatching { previousEngine.stop() }
                    engineCommandStartIds.remove(previousEngine)
                    rollbackCandidate.activateRuntime()
                    val rollbackCommitted = lifecycleCommitGuard.runIfCurrent {
                        previousDiagnostics?.restore(diagnostics)
                        persistActiveConfig(previousConfig)
                        prefs().edit().putBoolean(PREF_ACTIVE, true).apply()
                        restoreTurboRuntime(retainedTurboSnapshot)
                        broadcastState(
                            STATE_CONNECTED,
                            "New protection settings failed; the previous protection configuration was restored.",
                            generation,
                            transitionReason
                        )
                    }
                    if (!rollbackCommitted) {
                        abandonSupersededStart(
                            rollbackCandidate,
                            previousEngine,
                            preparedNetworkHandler,
                            previousNetworkHandler
                        )
                    }
                    return false
                } catch (rollbackError: Exception) {
                    if (pendingEngine === rollbackEngine) pendingEngine = null
                    pendingEngineFailures.discard(rollbackEngine)
                    if (rollbackEngine != null) engineCommandStartIds.remove(rollbackEngine)
                    runCatching { rollbackEngine?.stop() }
                    diagnostics.lastProtectionFailureReason = DiagnosticsState.sanitize(
                        rollbackError.message ?: rollbackError.javaClass.simpleName
                    )
                }
            }
            runCatching { previousEngine?.stop() }
            if (previousEngine != null) engineCommandStartIds.remove(previousEngine)
            runCatching { previousNetworkHandler?.stop() }
            runCatching { preparedNetworkHandler?.stop() }
            engine = null
            pendingEngine = null
            activeConfig = null
            networkHandler = null
            networkHandlerEpoch = 0L
            lifecycleCommitGuard.runIfCurrent {
                diagnostics.serviceRunning = false
                prefs().edit().putBoolean(PREF_ACTIVE, false).apply()
                val failedTurbo = TurboRuntimeController.restorePreference(
                    config?.turboModeEnabled ?: prefs().getBoolean(PREF_TURBO_MODE, false),
                    false
                )
                TurboRuntimeController.failed(failedTurbo.generation, "engine-start-failed")
                broadcastState(STATE_ERROR, "Local protection could not start.", generation, transitionReason)
                stopForegroundIfLatest(commandStartId)
            }
            return false
        }
    }

    @Synchronized
    private fun abandonSupersededStart(
        candidate: LocalProtectionEngine?,
        previousEngine: LocalProtectionEngine?,
        preparedNetworkHandler: NetworkChangeHandler?,
        previousNetworkHandler: NetworkChangeHandler?
    ) {
        val networkHandlers = lifecycleResourcesToClose(
            current = networkHandler,
            prepared = preparedNetworkHandler,
            previous = previousNetworkHandler
        )
        if (pendingEngine === candidate) pendingEngine = null
        pendingEngineFailures.discard(candidate)
        if (candidate != null) engineCommandStartIds.remove(candidate)
        runCatching { candidate?.stop() }
        previousEngine?.takeIf { it !== candidate }?.let { value ->
            engineCommandStartIds.remove(value)
            runCatching { value.stop() }
        }
        networkHandlers.forEach { value -> runCatching { value.stop() } }
        engine = null
        pendingEngine = null
        activeConfig = null
        networkHandler = null
        networkHandlerEpoch = 0L
    }

    @Synchronized
    private fun stopProtection(
        clearActive: Boolean,
        transitionReason: String,
        removeForeground: Boolean = true
    ) {
        if (engine == null && networkHandler == null &&
            (currentState == STATE_DISCONNECTED || currentState == STATE_ERROR)
        ) {
            if (clearActive) prefs().edit().putBoolean(PREF_ACTIVE, false).apply()
            diagnostics.serviceRunning = false
            if (removeForeground) removeForegroundIfOwner()
            return
        }
        val generation = beginServiceTransition(transitionReason)
        if (clearActive) prefs().edit().putBoolean(PREF_ACTIVE, false).apply()
        diagnostics.serviceRunning = false
        val desired = prefs().getBoolean(PREF_TURBO_MODE, false)
        TurboRuntimeController.restorePreference(desired, false)
        stopEngineOnly()
        activeConfig = null
        if (removeForeground) removeForegroundIfOwner()
        if (currentState != STATE_ERROR) {
            broadcastState(STATE_DISCONNECTED, serviceGeneration = generation, transitionReason = transitionReason)
        }
    }

    @Synchronized
    private fun stopEngineOnly() {
        watchdogThread?.interrupt()
        watchdogThread = null
        runCatching { networkHandler?.stop() }
        networkHandler = null
        networkHandlerEpoch = 0L
        val pending = pendingEngine
        val active = engine
        pendingEngine = null
        engine = null
        pendingEngineFailures.discard(pending)
        pendingEngineFailures.discard(active)
        if (pending != null) engineCommandStartIds.remove(pending)
        if (active != null) engineCommandStartIds.remove(active)
        runCatching { pending?.stop() }
        active?.takeIf { it !== pending }?.let { value -> runCatching { value.stop() } }
    }

    @Synchronized
    private fun publishUnderlyingContext(context: UnderlyingNetworkContext) {
        val pending = pendingEngine
        val active = engine
        pending?.updateUnderlyingNetworkContext(context)
        active?.takeIf { it !== pending }?.updateUnderlyingNetworkContext(context)
    }

    @Synchronized
    private fun publishNetworkReset() {
        val pending = pendingEngine
        val active = engine
        pending?.onNetworkChanged()
        active?.takeIf { it !== pending }?.onNetworkChanged()
    }

    @Synchronized
    private fun publishCapabilityContext() {
        val pending = pendingEngine
        val active = engine
        pending?.onNetworkContextChanged()
        active?.takeIf { it !== pending }?.onNetworkContextChanged()
    }

    private fun dispatchUnderlyingContext(
        source: NetworkChangeHandler,
        handlerEpoch: Long,
        context: UnderlyingNetworkContext
    ) {
        dispatchLifecycle {
            if (!isCurrentNetworkCallback(source, handlerEpoch)) return@dispatchLifecycle
            publishUnderlyingContext(context)
        }
    }

    private fun dispatchNetworkReset(source: NetworkChangeHandler, handlerEpoch: Long) {
        dispatchLifecycle {
            if (!isCurrentNetworkCallback(source, handlerEpoch)) return@dispatchLifecycle
            publishNetworkReset()
        }
    }

    private fun dispatchCapabilityContext(source: NetworkChangeHandler, handlerEpoch: Long) {
        dispatchLifecycle {
            if (!isCurrentNetworkCallback(source, handlerEpoch)) return@dispatchLifecycle
            publishCapabilityContext()
        }
    }

    private fun isCurrentNetworkCallback(source: NetworkChangeHandler, handlerEpoch: Long): Boolean {
        return isCurrentLifecycleCallback(
            activeSource = networkHandler,
            activeEpoch = networkHandlerEpoch,
            callbackSource = source,
            callbackEpoch = handlerEpoch,
            ownsServiceInstance = serviceInstanceOwnership.isOwner(serviceInstanceId)
        )
    }

    private fun runUnderlyingNetworkProbe(serviceGeneration: Long, turboGeneration: Long) {
        watchdogThread?.interrupt()
        val worker = Thread {
            try {
                Thread.sleep(900)
                if (serviceGeneration != startGeneration || currentState != STATE_CONNECTED) return@Thread
                val activeEngine = engine ?: return@Thread
                val result = UnderlyingNetworkReachabilityProbe(
                    this,
                    diagnostics,
                    candidateProvider = activeEngine::watchdogProbeCandidates,
                    protectSocket = activeEngine::protectWatchdogSocket
                ).check()
                if (serviceGeneration != startGeneration || currentState != STATE_CONNECTED) return@Thread
                if (result.passed) {
                    TurboRuntimeController.activated(turboGeneration)
                } else {
                    TurboRuntimeController.degraded(turboGeneration, "underlying-${result.reason}")
                }
                broadcastState(
                    currentState,
                    serviceGeneration = serviceGeneration,
                    transitionReason = TRANSITION_REASON_HEALTH_UPDATE
                )
            } catch (_: InterruptedException) {
                return@Thread
            } catch (error: Exception) {
                if (serviceGeneration != startGeneration || currentState != STATE_CONNECTED) return@Thread
                diagnostics.recordTurboHealthFailure("underlying-probe-exception")
                TurboRuntimeController.degraded(turboGeneration, "underlying-probe-exception")
                broadcastState(
                    currentState,
                    serviceGeneration = serviceGeneration,
                    transitionReason = TRANSITION_REASON_HEALTH_UPDATE
                )
            }
        }.apply {
            name = "UnderlyingNetworkReachabilityProbe"
            isDaemon = true
        }
        watchdogThread = worker
        worker.start()
    }

    private fun readConfig(intent: Intent?): LocalProtectionEngine.Config {
        val prefs = prefs()
        val turboModeEnabled = intent?.takeIf { it.hasExtra(EXTRA_TURBO_MODE) }?.getBooleanExtra(EXTRA_TURBO_MODE, false)
            ?: prefs.getBoolean(PREF_TURBO_MODE, false)
        val browserTrafficOnly = intent?.takeIf { it.hasExtra(EXTRA_BROWSER_ONLY) }?.getBooleanExtra(EXTRA_BROWSER_ONLY, false)
            ?: prefs.getBoolean(PREF_BROWSER_ONLY, false)
        val baseMode = ProtectionMode.from(
            intent?.getStringExtra(EXTRA_PROTECTION_MODE)
                ?: prefs.getString(EXTRA_PROTECTION_MODE, MODE_BALANCED)
        )
        val mode = if (turboModeEnabled) ProtectionMode.STRONG else baseMode
        val bypassInput = intent
            ?.takeIf { it.hasExtra(EXTRA_BYPASS_PACKAGES) }
            ?.getStringArrayExtra(EXTRA_BYPASS_PACKAGES)
            ?.toList()
            ?: prefs.getString(EXTRA_BYPASS_PACKAGES, "").orEmpty().lines()
        validateBypassPackageInput(bypassInput)
        val bypass = sanitizePackageNames(bypassInput, packageName)
        val mtu = sanitizeTunMtu(
            intent?.takeIf { it.hasExtra(EXTRA_MTU) }?.getIntExtra(EXTRA_MTU, PERFORMANCE_TUN_MTU)
                ?: prefs.getInt(EXTRA_MTU, PERFORMANCE_TUN_MTU)
        )
        val adBlockEnabled = intent?.takeIf { it.hasExtra(EXTRA_AD_BLOCK) }?.getBooleanExtra(EXTRA_AD_BLOCK, true)
            ?: prefs.getBoolean(PREF_ADBLOCK_ENABLED, true)
        val dnsOverHttpsEnabled = intent?.takeIf { it.hasExtra(EXTRA_DOH) }?.getBooleanExtra(EXTRA_DOH, true)
            ?: prefs.getBoolean(PREF_DOH_ENABLED, true)
        val turboProtectedDomainInput = intent
            ?.takeIf { it.hasExtra(EXTRA_SPLIT_DOMAINS) }
            ?.getStringArrayExtra(EXTRA_SPLIT_DOMAINS)
            ?.toList()
            ?: prefs.getString(EXTRA_SPLIT_DOMAINS, "").orEmpty().lines()
        validateSplitDomainInput(turboProtectedDomainInput)
        val turboProtectedDomains = sanitizeSplitDomains(turboProtectedDomainInput).valid
        return LocalProtectionEngine.Config(
            mode = mode,
            baseMode = baseMode,
            bypassPackages = bypass,
            adBlockEnabled = adBlockEnabled,
            dnsOverHttpsEnabled = dnsOverHttpsEnabled,
            turboModeEnabled = turboModeEnabled,
            turboAiFlags = TurboAiPreferences.flags(
                prefs,
                applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            ).let { flags -> flags.copy(enabled = turboModeEnabled && flags.enabled) },
            turboProtectedDomains = turboProtectedDomains,
            browserTrafficOnly = browserTrafficOnly,
            mtu = mtu
        )
    }

    private fun persistActiveConfig(config: LocalProtectionEngine.Config) {
        prefs().edit()
            .putString(EXTRA_PROTECTION_MODE, config.baseMode.prefValue)
            .putString(EXTRA_BYPASS_PACKAGES, config.bypassPackages.joinToString("\n"))
            .putString(EXTRA_SPLIT_DOMAINS, config.turboProtectedDomains.joinToString("\n"))
            .putInt(EXTRA_MTU, config.mtu)
            .putBoolean(PREF_ADBLOCK_ENABLED, config.adBlockEnabled)
            .putBoolean(PREF_DOH_ENABLED, config.dnsOverHttpsEnabled)
            .putBoolean(PREF_TURBO_MODE, config.turboModeEnabled)
            .putBoolean(PREF_BROWSER_ONLY, config.browserTrafficOnly)
            .apply()
    }

    @Synchronized
    private fun setTurboDesired(enabled: Boolean, commandStartId: Int) {
        val connected = currentState == STATE_CONNECTED && engine != null
        val previousSnapshot = TurboRuntimeController.snapshot()
        TurboRuntimeController.beginChange(enabled, connected)
        prefs().edit().putBoolean(PREF_TURBO_MODE, enabled).apply()
        diagnostics.turboModeEnabled = enabled
        if (!connected) {
            broadcastState(
                currentState,
                serviceGeneration = startGeneration,
                transitionReason = TRANSITION_REASON_TURBO_REFRESH
            )
            return
        }
        startProtection(
            intent = null,
            explicitReason = TRANSITION_REASON_TURBO_REFRESH,
            rollbackTurboSnapshot = previousSnapshot,
            commandStartId = commandStartId
        )
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TunnelVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val configuredBuilder = builder
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.drawable.ic_vpn)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.app_icon))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            configuredBuilder
                .setPriority(Notification.PRIORITY_LOW)
                .setSound(null)
                .setVibrate(null)
                .setDefaults(0)
        }
        return configuredBuilder
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_vpn),
                    getString(R.string.vpn_notification_stop),
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.vpn_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun beginServiceTransition(transitionReason: String): Long {
        startGeneration = serviceGenerationSeed.incrementAndGet()
        activeTransitionReason = transitionReason
        watchdogThread?.interrupt()
        watchdogThread = null
        return startGeneration
    }

    private fun handleFatalPacketLoop(
        failedEngine: LocalProtectionEngine,
        serviceGeneration: Long,
        reason: String
    ) {
        val commandStartId = engineCommandStartIds[failedEngine] ?: return
        dispatchFatalPacketLoop(failedEngine, serviceGeneration, commandStartId, reason)
    }

    private fun dispatchFatalPacketLoop(
        failedEngine: LocalProtectionEngine,
        serviceGeneration: Long,
        commandStartId: Int,
        reason: String
    ) {
        pendingEngineFailures.record(failedEngine, reason)
        dispatchLifecycle {
            pendingEngineFailures.discard(failedEngine)
            handleFatalPacketLoop(failedEngine, serviceGeneration, commandStartId, reason)
        }
    }

    @Synchronized
    private fun handleFatalPacketLoop(
        failedEngine: LocalProtectionEngine,
        serviceGeneration: Long,
        commandStartId: Int,
        reason: String
    ) {
        if (engine !== failedEngine || serviceGeneration != startGeneration) return
        val failureGeneration = beginServiceTransition(TRANSITION_REASON_ENGINE_FAILURE)
        diagnostics.lastProtectionFailureReason = DiagnosticsState.sanitize(reason)
        diagnostics.serviceRunning = false
        prefs().edit().putBoolean(PREF_ACTIVE, false).apply()
        stopEngineOnly()
        activeConfig = null
        broadcastState(
            STATE_ERROR,
            "Local protection stopped unexpectedly.",
            failureGeneration,
            TRANSITION_REASON_ENGINE_FAILURE
        )
        stopForegroundIfLatest(commandStartId)
    }

    private fun dispatchLifecycle(block: () -> Unit) {
        if (serviceDestroyed) return
        try {
            lifecycleExecutor.execute {
                processLifecycleWorkGate.run {
                    if (!serviceDestroyed) block()
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun stopForegroundIfLatest(startId: Int) {
        if (stopSelfResult(startId)) {
            removeForegroundIfOwner()
        }
    }

    private fun removeForegroundIfOwner() {
        serviceInstanceOwnership.runIfOwner(serviceInstanceId) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    @Synchronized
    private fun destroyServiceInstance() {
        stopEngineOnly()
        activeConfig = null
        serviceInstanceOwnership.runIfOwner(serviceInstanceId) {
            diagnostics.serviceRunning = false
            val desired = prefs().getBoolean(PREF_TURBO_MODE, false)
            TurboRuntimeController.restorePreference(desired, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (currentState != STATE_DISCONNECTED && currentState != STATE_ERROR) {
                val generation = beginServiceTransition(TRANSITION_REASON_SERVICE_DESTROYED)
                broadcastState(
                    STATE_DISCONNECTED,
                    serviceGeneration = generation,
                    transitionReason = TRANSITION_REASON_SERVICE_DESTROYED,
                    allowDestroyedOwner = true
                )
            }
        }
    }

    private fun claimServiceInstance() {
        serviceInstanceOwnership.claim(serviceInstanceId)
    }

    private fun restoreTurboRuntime(snapshot: TurboRuntimeSnapshot): TurboRuntimeSnapshot {
        val restored = TurboRuntimeController.restorePreference(snapshot.desired, true)
        return when (snapshot.state) {
            TurboRuntimeState.ACTIVE -> TurboRuntimeController.activated(restored.generation)
            TurboRuntimeState.DEGRADED -> TurboRuntimeController.degraded(restored.generation, snapshot.reasonCode)
            TurboRuntimeState.GUARDED -> TurboRuntimeController.guarded(restored.generation, snapshot.reasonCode)
            TurboRuntimeState.ERROR -> TurboRuntimeController.failed(restored.generation, snapshot.reasonCode)
            TurboRuntimeState.DISABLED -> TurboRuntimeController.disabled(restored.generation)
            else -> restored
        }
    }

    private fun sanitizeTransitionReason(value: String?, fallback: String): String {
        return when (value) {
            TRANSITION_REASON_USER_START,
            TRANSITION_REASON_CONFIG_REFRESH,
            TRANSITION_REASON_TURBO_REFRESH,
            TRANSITION_REASON_AUTO_START,
            TRANSITION_REASON_SERVICE_RESTORE,
            TRANSITION_REASON_USER_STOP,
            TRANSITION_REASON_VPN_REVOKED,
            TRANSITION_REASON_SERVICE_DESTROYED,
            TRANSITION_REASON_ENGINE_FAILURE,
            TRANSITION_REASON_DISCLOSURE_REQUIRED,
            TRANSITION_REASON_HEALTH_UPDATE -> value
            else -> fallback
        }
    }

    @Synchronized
    private fun broadcastState(
        state: String,
        error: String? = null,
        serviceGeneration: Long = startGeneration,
        transitionReason: String = activeTransitionReason,
        allowDestroyedOwner: Boolean = false
    ) {
        if (serviceGeneration != startGeneration) return
        val publication = {
            synchronized(stateSnapshotPublicationLock) {
                if (serviceGeneration < currentStateSnapshotShared.serviceGeneration) return@synchronized
                if (state == STATE_CONNECTED && connectedSinceElapsedMs <= 0L) {
                    connectedSinceElapsedMs = SystemClock.elapsedRealtime()
                }
                if (state == STATE_DISCONNECTED || state == STATE_ERROR) connectedSinceElapsedMs = 0L
                val snapshot = TunnelServiceStateSnapshot(
                    state = state,
                    connectedSinceElapsedMs = connectedSinceElapsedMs,
                    connectedSeconds = calculateConnectedSeconds(state, connectedSinceElapsedMs),
                    serviceGeneration = serviceGeneration,
                    transitionReason = transitionReason
                )
                currentStateSnapshotShared = snapshot
                sendBroadcast(Intent(ACTION_STATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_STATE, snapshot.state)
                    putExtra(EXTRA_CONNECTED_SECONDS, snapshot.connectedSeconds)
                    putExtra(EXTRA_SERVICE_GENERATION, snapshot.serviceGeneration)
                    putExtra(EXTRA_TRANSITION_REASON, snapshot.transitionReason)
                    error?.let { putExtra(EXTRA_ERROR, it) }
                    val turbo = TurboRuntimeController.snapshot()
                    putExtra(EXTRA_TURBO_DESIRED, turbo.desired)
                    putExtra(EXTRA_TURBO_STATE, turbo.state.name)
                    putExtra(EXTRA_TURBO_GENERATION, turbo.generation)
                    putExtra(EXTRA_TURBO_REASON, turbo.reasonCode)
                })
            }
        }
        if (allowDestroyedOwner) {
            serviceInstanceOwnership.runIfOwner(serviceInstanceId, publication)
        } else {
            lifecycleCommitGuard.runIfCurrent(publication)
        }
    }

    companion object {
        private const val CHANNEL_ID = "tunnel_https_quiet_v2"
        private const val NOTIFICATION_ID = 2001
        private val serviceGenerationSeed = AtomicLong(0L)
        private val serviceInstanceSeed = AtomicLong(0L)
        private val serviceInstanceOwnership = ServiceInstanceOwnership()
        private val processLifecycleWorkGate = ProcessLifecycleWorkGate()
        private val stateSnapshotPublicationLock = Any()

        const val ACTION_START = "com.tunnelvpn.app.START"
        const val ACTION_STOP = "com.tunnelvpn.app.STOP"
        const val ACTION_STATE = "com.tunnelvpn.app.STATE"
        const val ACTION_SET_TURBO = "com.tunnelvpn.app.SET_TURBO"

        const val EXTRA_DNS_SERVER = "dns_server"
        const val EXTRA_MTU = "mtu"
        const val PERFORMANCE_TUN_MTU = 32768
        const val BATTERY_SAVER_TUN_MTU = 4096
        const val EXTRA_ROUTE_ALL = "route_all"
        const val EXTRA_DNS_PROTECTION = "dns_protection_enabled"
        const val EXTRA_PROTECTION_MODE = "protection_mode"
        const val EXTRA_SPLIT_DOMAINS = "split_domains"
        const val EXTRA_BYPASS_PACKAGES = "bypass_packages"
        const val EXTRA_CONNECTED_SECONDS = "connected_seconds"
        const val EXTRA_BATTERY_SAVER = "battery_saver"
        const val EXTRA_DOH = "dns_over_https"
        const val PREF_DOH_ENABLED = "doh_enabled"
        const val PREF_ADBLOCK_ENABLED = "adblock_enabled"
        const val EXTRA_AD_BLOCK = PREF_ADBLOCK_ENABLED
        const val PREF_TURBO_MODE = "turbo_mode_enabled"
        const val EXTRA_TURBO_MODE = PREF_TURBO_MODE
        const val EXTRA_TURBO_DESIRED = "turbo_desired"
        const val EXTRA_TURBO_STATE = "turbo_runtime_state"
        const val EXTRA_TURBO_GENERATION = "turbo_generation"
        const val EXTRA_TURBO_REASON = "turbo_reason"
        const val PREF_BROWSER_ONLY = "browser_traffic_only"
        const val EXTRA_BROWSER_ONLY = PREF_BROWSER_ONLY
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"
        const val EXTRA_SERVICE_GENERATION = "service_generation"
        const val EXTRA_TRANSITION_REASON = "transition_reason"

        const val TRANSITION_REASON_USER_START = "USER_START"
        const val TRANSITION_REASON_CONFIG_REFRESH = "CONFIG_REFRESH"
        const val TRANSITION_REASON_TURBO_REFRESH = "TURBO_REFRESH"
        const val TRANSITION_REASON_AUTO_START = "AUTO_START"
        const val TRANSITION_REASON_SERVICE_RESTORE = "SERVICE_RESTORE"
        const val TRANSITION_REASON_USER_STOP = "USER_STOP"
        const val TRANSITION_REASON_VPN_REVOKED = "VPN_REVOKED"
        const val TRANSITION_REASON_SERVICE_DESTROYED = "SERVICE_DESTROYED"
        const val TRANSITION_REASON_ENGINE_FAILURE = "ENGINE_FAILURE"
        const val TRANSITION_REASON_DISCLOSURE_REQUIRED = "DISCLOSURE_REQUIRED"
        const val TRANSITION_REASON_HEALTH_UPDATE = "HEALTH_UPDATE"

        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"
        const val PREFS_NAME = "tunnel_vpn"
        const val PREF_ACTIVE = "active"
        const val PREF_AUTO_START = "auto_start"

        const val MODE_FAST = "fast"
        const val MODE_BALANCED = "balanced"
        const val MODE_STRONG = "strong"
        const val MODE_HTTPS_LOCAL = MODE_BALANCED
        const val MODE_ENHANCED_TUNNEL = MODE_STRONG

        val diagnostics = DiagnosticsState()

        @Volatile
        private var currentStateSnapshotShared = TunnelServiceStateSnapshot(
            state = STATE_DISCONNECTED,
            connectedSinceElapsedMs = 0L,
            connectedSeconds = 0L,
            serviceGeneration = 0L,
            transitionReason = TRANSITION_REASON_SERVICE_RESTORE
        )

        val currentState: String
            get() = currentStateSnapshotShared.state

        fun connectedSeconds(): Long {
            return currentStateSnapshot().connectedSeconds
        }

        fun currentStateSnapshot(): TunnelServiceStateSnapshot {
            val published = currentStateSnapshotShared
            val seconds = calculateConnectedSeconds(published.state, published.connectedSinceElapsedMs)
            return if (seconds == published.connectedSeconds) published else published.copy(connectedSeconds = seconds)
        }

        fun currentServiceGeneration(): Long = currentStateSnapshotShared.serviceGeneration

        fun currentTransitionReason(): String = currentStateSnapshotShared.transitionReason

        private fun calculateConnectedSeconds(state: String, connectedSinceElapsedMs: Long): Long {
            if (state != STATE_CONNECTED || connectedSinceElapsedMs <= 0L) return 0L
            return ((SystemClock.elapsedRealtime() - connectedSinceElapsedMs) / 1_000L).coerceAtLeast(0L)
        }

        fun trafficStatsJson(): String {
            return diagnostics.trafficSnapshotJson()
        }
    }
}

class TunnelVpnService : TunnelHttpsVpnService() {
    companion object {
        const val ACTION_START = TunnelHttpsVpnService.ACTION_START
        const val ACTION_STOP = TunnelHttpsVpnService.ACTION_STOP
        const val ACTION_STATE = TunnelHttpsVpnService.ACTION_STATE
        const val ACTION_SET_TURBO = TunnelHttpsVpnService.ACTION_SET_TURBO
        const val EXTRA_DNS_SERVER = TunnelHttpsVpnService.EXTRA_DNS_SERVER
        const val EXTRA_MTU = TunnelHttpsVpnService.EXTRA_MTU
        const val PERFORMANCE_TUN_MTU = TunnelHttpsVpnService.PERFORMANCE_TUN_MTU
        const val BATTERY_SAVER_TUN_MTU = TunnelHttpsVpnService.BATTERY_SAVER_TUN_MTU
        const val EXTRA_ROUTE_ALL = TunnelHttpsVpnService.EXTRA_ROUTE_ALL
        const val EXTRA_DNS_PROTECTION = TunnelHttpsVpnService.EXTRA_DNS_PROTECTION
        const val EXTRA_PROTECTION_MODE = TunnelHttpsVpnService.EXTRA_PROTECTION_MODE
        const val EXTRA_SPLIT_DOMAINS = TunnelHttpsVpnService.EXTRA_SPLIT_DOMAINS
        const val EXTRA_BYPASS_PACKAGES = TunnelHttpsVpnService.EXTRA_BYPASS_PACKAGES
        const val EXTRA_CONNECTED_SECONDS = TunnelHttpsVpnService.EXTRA_CONNECTED_SECONDS
        const val EXTRA_BATTERY_SAVER = TunnelHttpsVpnService.EXTRA_BATTERY_SAVER
        const val EXTRA_DOH = TunnelHttpsVpnService.EXTRA_DOH
        const val PREF_DOH_ENABLED = TunnelHttpsVpnService.PREF_DOH_ENABLED
        const val PREF_ADBLOCK_ENABLED = TunnelHttpsVpnService.PREF_ADBLOCK_ENABLED
        const val EXTRA_AD_BLOCK = TunnelHttpsVpnService.EXTRA_AD_BLOCK
        const val PREF_TURBO_MODE = TunnelHttpsVpnService.PREF_TURBO_MODE
        const val EXTRA_TURBO_MODE = TunnelHttpsVpnService.EXTRA_TURBO_MODE
        const val EXTRA_TURBO_DESIRED = TunnelHttpsVpnService.EXTRA_TURBO_DESIRED
        const val EXTRA_TURBO_STATE = TunnelHttpsVpnService.EXTRA_TURBO_STATE
        const val EXTRA_TURBO_GENERATION = TunnelHttpsVpnService.EXTRA_TURBO_GENERATION
        const val EXTRA_TURBO_REASON = TunnelHttpsVpnService.EXTRA_TURBO_REASON
        const val PREF_BROWSER_ONLY = TunnelHttpsVpnService.PREF_BROWSER_ONLY
        const val EXTRA_BROWSER_ONLY = TunnelHttpsVpnService.EXTRA_BROWSER_ONLY
        const val EXTRA_STATE = TunnelHttpsVpnService.EXTRA_STATE
        const val EXTRA_ERROR = TunnelHttpsVpnService.EXTRA_ERROR
        const val EXTRA_SERVICE_GENERATION = TunnelHttpsVpnService.EXTRA_SERVICE_GENERATION
        const val EXTRA_TRANSITION_REASON = TunnelHttpsVpnService.EXTRA_TRANSITION_REASON
        const val TRANSITION_REASON_USER_START = TunnelHttpsVpnService.TRANSITION_REASON_USER_START
        const val TRANSITION_REASON_CONFIG_REFRESH = TunnelHttpsVpnService.TRANSITION_REASON_CONFIG_REFRESH
        const val TRANSITION_REASON_TURBO_REFRESH = TunnelHttpsVpnService.TRANSITION_REASON_TURBO_REFRESH
        const val TRANSITION_REASON_AUTO_START = TunnelHttpsVpnService.TRANSITION_REASON_AUTO_START
        const val TRANSITION_REASON_SERVICE_RESTORE = TunnelHttpsVpnService.TRANSITION_REASON_SERVICE_RESTORE
        const val TRANSITION_REASON_USER_STOP = TunnelHttpsVpnService.TRANSITION_REASON_USER_STOP
        const val TRANSITION_REASON_VPN_REVOKED = TunnelHttpsVpnService.TRANSITION_REASON_VPN_REVOKED
        const val TRANSITION_REASON_SERVICE_DESTROYED = TunnelHttpsVpnService.TRANSITION_REASON_SERVICE_DESTROYED
        const val TRANSITION_REASON_ENGINE_FAILURE = TunnelHttpsVpnService.TRANSITION_REASON_ENGINE_FAILURE
        const val TRANSITION_REASON_DISCLOSURE_REQUIRED = TunnelHttpsVpnService.TRANSITION_REASON_DISCLOSURE_REQUIRED
        const val TRANSITION_REASON_HEALTH_UPDATE = TunnelHttpsVpnService.TRANSITION_REASON_HEALTH_UPDATE
        const val STATE_CONNECTING = TunnelHttpsVpnService.STATE_CONNECTING
        const val STATE_CONNECTED = TunnelHttpsVpnService.STATE_CONNECTED
        const val STATE_DISCONNECTED = TunnelHttpsVpnService.STATE_DISCONNECTED
        const val STATE_ERROR = TunnelHttpsVpnService.STATE_ERROR
        const val PREFS_NAME = TunnelHttpsVpnService.PREFS_NAME
        const val PREF_ACTIVE = TunnelHttpsVpnService.PREF_ACTIVE
        const val PREF_AUTO_START = TunnelHttpsVpnService.PREF_AUTO_START
        const val MODE_FAST = TunnelHttpsVpnService.MODE_FAST
        const val MODE_BALANCED = TunnelHttpsVpnService.MODE_BALANCED
        const val MODE_STRONG = TunnelHttpsVpnService.MODE_STRONG
        const val MODE_HTTPS_LOCAL = TunnelHttpsVpnService.MODE_HTTPS_LOCAL
        const val MODE_ENHANCED_TUNNEL = TunnelHttpsVpnService.MODE_ENHANCED_TUNNEL

        val currentState: String
            get() = TunnelHttpsVpnService.currentState

        fun connectedSeconds(): Long = TunnelHttpsVpnService.connectedSeconds()
        fun currentStateSnapshot(): TunnelServiceStateSnapshot = TunnelHttpsVpnService.currentStateSnapshot()
        fun currentServiceGeneration(): Long = TunnelHttpsVpnService.currentServiceGeneration()
        fun currentTransitionReason(): String = TunnelHttpsVpnService.currentTransitionReason()
        fun trafficStatsJson(): String = TunnelHttpsVpnService.trafficStatsJson()
    }
}
