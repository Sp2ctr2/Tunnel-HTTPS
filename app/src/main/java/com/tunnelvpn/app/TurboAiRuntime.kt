package com.tunnelvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class TurboCoarseNetworkSnapshot(
    val transport: TurboNetworkTransport = TurboNetworkTransport.UNKNOWN,
    val metered: Boolean = true,
    val roaming: Boolean = false,
    val validated: Boolean = false,
    val batterySaver: Boolean = false
)

class TurboNetworkContextProvider(
    private val context: Context,
    private val connectivityManager: ConnectivityManager?
) {
    private val value = AtomicReference(TurboCoarseNetworkSnapshot())
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun refresh() {
        value.set(readSnapshot())
    }

    fun snapshot(): TurboCoarseNetworkSnapshot = value.get()

    fun refreshBatterySaver() {
        val current = value.get()
        val batterySaver = powerManager?.isPowerSaveMode == true
        if (current.batterySaver != batterySaver) {
            value.set(current.copy(batterySaver = batterySaver))
        }
    }

    private fun readSnapshot(): TurboCoarseNetworkSnapshot {
        val capabilities = connectivityManager?.activeNetwork?.let(connectivityManager::getNetworkCapabilities)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> TurboNetworkTransport.WIFI
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> TurboNetworkTransport.CELLULAR
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> TurboNetworkTransport.ETHERNET
            capabilities != null -> TurboNetworkTransport.OTHER
            else -> TurboNetworkTransport.UNKNOWN
        }
        val metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val roaming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false
        } else {
            false
        }
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val batterySaver = powerManager?.isPowerSaveMode == true
        return TurboCoarseNetworkSnapshot(transport, metered, roaming, validated, batterySaver)
    }
}

object TurboAiStatusRegistry {
    private data class Entry(val owner: Long, val status: TurboAiStatus)

    private val value = AtomicReference<Entry?>()

    fun set(status: TurboAiStatus) {
        value.set(Entry(0L, status))
    }

    fun set(owner: Long, status: TurboAiStatus) {
        while (true) {
            val previous = value.get()
            if (previous != null && previous.owner > owner) return
            if (value.compareAndSet(previous, Entry(owner, status))) return
        }
    }

    fun current(): TurboAiStatus? = value.get()?.status

    fun clear(owner: Long) {
        while (true) {
            val current = value.get() ?: return
            if (current.owner != owner) return
            if (value.compareAndSet(current, null)) return
        }
    }
}

internal class BoundedTurboOutcomeQueue<T>(private val capacity: Int) {
    private val values = ConcurrentLinkedQueue<T>()
    private val count = AtomicInteger()

    init {
        require(capacity > 0)
    }

    fun offer(value: T): Boolean {
        while (true) {
            val current = count.get()
            if (current >= capacity) return false
            if (!count.compareAndSet(current, current + 1)) continue
            return runCatching {
                values.add(value)
                true
            }.getOrElse {
                count.decrementAndGet()
                false
            }
        }
    }

    fun poll(): T? {
        val value = values.poll() ?: return null
        count.decrementAndGet()
        return value
    }

    fun isNotEmpty(): Boolean = values.isNotEmpty()

    fun size(): Int = count.get()
}

class TurboAiRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val flags: TurboAiFlags,
    private val latencyProfile: TurboTcpForwarder.LatencyProfile,
    private val clock: TurboClock = TurboClock { System.currentTimeMillis() },
    private val randomFactory: () -> TurboRandom = { DefaultTurboRandom() }
) {
    private val ownerId = runtimeOwnerSeed.incrementAndGet()
    private data class RecentMetrics(
        val samples: Long = 0L,
        val successRatio: Double = 0.0,
        val retryRatio: Double = 0.0,
        val handshakeMs: Double = 0.0
    )

    private val appContext = context.applicationContext
    private val store: TurboModelStore = SharedPreferencesTurboModelStore(context)
    private val networkProvider = TurboNetworkContextProvider(
        context,
        context.getSystemService(ConnectivityManager::class.java)
    )
    private val engine = AtomicReference<TurboAiEngine?>()
    private val persistScheduled = AtomicBoolean(false)
    private val outcomeDrainScheduled = AtomicBoolean(false)
    private val outcomeDrainLock = Any()
    private data class PendingOutcome(val value: TurboOutcome, val updateLegacy: Boolean, val updateAegis: Boolean)
    private val aegis = if (flags.enabled && flags.aegisShadowEnabled && !flags.forceBaseline) {
        runCatching { AegisLocal2Engine() }.getOrNull()
    } else null
    private var aegisReady = false
    private var aegisFailed = false
    private var lastPublishedStatus: TurboAiStatus? = null
    private val aegisInferenceSamples = LongArray(128)
    private var aegisInferenceSampleCount = 0
    private var aegisInferenceCursor = 0
    private var runtimeDecisionCount = 0L
    private var runtimeDecisionNanos = 0L
    private var runtimeDecisionMaxNanos = 0L
    private val pendingOutcomes = BoundedTurboOutcomeQueue<PendingOutcome>(MAX_PENDING_OUTCOMES)
    private val recentMetrics = AtomicReference(RecentMetrics())
    private val closed = AtomicBoolean(false)
    private val modelGeneration = AtomicLong(-1L)
    private val suppressionNetworkGeneration = AtomicLong()
    private val receiverRegistered = AtomicBoolean(false)
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                networkProvider.refreshBatterySaver()
            }
        }
    }

    fun start() {
        if (closed.get()) return
        networkProvider.refresh()
        registerPowerSaveReceiver()
        TurboAiStatusRegistry.set(
            ownerId,
            TurboAiStatus(
                available = false,
                enabled = flags.enabled,
                samples = 0L,
                confidence = TurboConfidence.LOW,
                state = if (flags.enabled) "initializing" else "disabled",
                fallbackReason = if (flags.enabled) "model-loading" else ""
            )
        )
        scope.launch {
            val loaded = runCatching { store.load() }
            loaded.onSuccess { model ->
                val loadedEngine = TurboAiEngine(
                    secret = model.secret,
                    initialState = model.state,
                    flags = flags,
                    clock = clock,
                    random = randomFactory()
                )
                val fallbackReason = if (model.recoveredFromCorruption) "corrupted-state-recovered" else ""
                synchronized(outcomeDrainLock) {
                    if (closed.get()) return@onSuccess
                    modelGeneration.set(model.generation)
                    engine.set(loadedEngine)
                    publishStatus(
                        available = true,
                        fallbackReason = fallbackReason
                    )
                }
                val aegisPreparation = aegis?.let { candidate ->
                    runCatching { candidate.prepare() }.getOrNull()
                }
                synchronized(outcomeDrainLock) {
                    if (closed.get()) return@onSuccess
                    if (aegis != null) {
                        aegisReady = aegisPreparation?.ready == true
                        aegisFailed = !aegisReady
                        publishStatus(available = true, fallbackReason = fallbackReason)
                    }
                }
            }.onFailure {
                publishStatus(available = false, fallbackReason = "model-initialization-failed")
            }
        }
    }

    fun select(
        destination: String,
        destinationPort: Int,
        analysis: TlsClientHello.Analysis,
        baselineModes: List<TlsClientHello.FragmentationMode>,
        resourcePressureBucket: Int,
        previousFailure: TurboFailureCategory = TurboFailureCategory.NONE
    ): TurboDecision? = synchronized(outcomeDrainLock) {
        if (!flags.enabled || flags.forceBaseline || closed.get()) return@synchronized null
        val currentEngine = engine.get() ?: return@synchronized null
        runCatching {
            val started = System.nanoTime()
            val network = networkProvider.snapshot()
            val recent = recentMetrics.get()
            val averageHandshake = recent.handshakeMs.toLong().coerceAtLeast(0L)
            val context = TurboContext(
                destinationKey = currentEngine.destinationKey(destination),
                destinationPort = destinationPort,
                transport = network.transport,
                metered = network.metered,
                roaming = network.roaming,
                validated = network.validated,
                batterySaver = network.batterySaver,
                clientHelloParsed = analysis.complete && analysis.clientHello,
                sniPresent = analysis.sni != null,
                latencyProfile = latencyProfile.name,
                approximateRttBucket = latencyBucket(averageHandshake),
                recentRetryBucket = ratioBucket(recent.retryRatio),
                recentSuccessBucket = ratioBucket(recent.successRatio),
                recentHandshakeBucket = latencyBucket(averageHandshake),
                resourcePressureBucket = resourcePressureBucket.coerceIn(0, 4),
                previousFailure = previousFailure
            )
            val candidates = baselineModes.map(TurboStrategyId::fromMode)
            val selected = currentEngine.select(context, candidates)
            val legacyDecisionNanos = (System.nanoTime() - started).coerceAtLeast(0L)
            val shadow = withAegis { it.decide(context, candidates, selected, suppressionNetworkGeneration.get()) }
            if (shadow != null && shadow.inferenceNanos > 0L) recordAegisInference(shadow.inferenceNanos)
            publishStatus(available = true, refreshLegacy = false)
            val result = selected.copy(
                decisionOverheadNanos = legacyDecisionNanos,
                aegisToken = shadow?.token ?: 0L,
                networkGeneration = suppressionNetworkGeneration.get()
            )
            val runtimeNanos = (System.nanoTime() - started).coerceAtLeast(0L)
            runtimeDecisionCount++
            runtimeDecisionNanos += runtimeNanos
            runtimeDecisionMaxNanos = maxOf(runtimeDecisionMaxNanos, runtimeNanos)
            result
        }.getOrElse {
            publishStatus(available = false, fallbackReason = "prediction-failed")
            null
        }
    }

    fun recordOutcome(outcome: TurboOutcome) {
        if (closed.get()) return
        if (engine.get() == null) return
        if (!pendingOutcomes.offer(PendingOutcome(outcome, updateLegacy = true, updateAegis = !outcome.success))) return
        scheduleOutcomeDrain()
    }

    fun recordHandshakeOutcome(outcome: TurboOutcome) {
        if (closed.get() || outcome.aegisToken == 0L || !flags.modelUpdatesEnabled) return
        if (!pendingOutcomes.offer(PendingOutcome(outcome, updateLegacy = false, updateAegis = true))) return
        scheduleOutcomeDrain()
    }

    private fun scheduleOutcomeDrain() {
        if (!outcomeDrainScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                drainOutcomes()
            } finally {
                outcomeDrainScheduled.set(false)
                if (pendingOutcomes.isNotEmpty() && !closed.get()) {
                    scheduleOutcomeDrain()
                }
            }
        }
    }

    fun onNetworkChanged() = synchronized(outcomeDrainLock) {
        if (closed.get()) return@synchronized
        val epoch = suppressionNetworkGeneration.incrementAndGet()
        withAegis { it.reset(epoch) }
        networkProvider.refresh()
        recentMetrics.set(RecentMetrics())
        publishStatus(available = engine.get() != null)
    }

    fun onNetworkContextChanged() {
        if (closed.get()) return
        networkProvider.refresh()
    }

    internal fun suppressionContextKey(): String {
        val network = networkProvider.snapshot()
        return buildString(48) {
            append(suppressionNetworkGeneration.get())
            append('|').append(network.transport.name)
            append('|').append(if (network.metered) 'M' else 'U')
            append(if (network.roaming) 'R' else 'N')
            append(if (network.validated) 'V' else 'X')
        }
    }

    fun close() {
        val currentEngine = synchronized(outcomeDrainLock) {
            if (!closed.compareAndSet(false, true)) return
            drainOutcomes()
            engine.getAndSet(null)
        }
        if (currentEngine != null) {
            runCatching {
                store.save(currentEngine.exportState(), currentEngine.overallConfidence(), modelGeneration.get())
            }
        }
        if (receiverRegistered.compareAndSet(true, false)) {
            runCatching { appContext.unregisterReceiver(powerSaveReceiver) }
        }
        TurboAiStatusRegistry.clear(ownerId)
    }

    private fun drainOutcomes() {
        synchronized(outcomeDrainLock) {
            val currentEngine = engine.get() ?: return
            var outcomeRecorded = false
            var modelUpdated = false
            while (true) {
                val pending = pendingOutcomes.poll() ?: break
                val outcome = pending.value
                val epoch = suppressionNetworkGeneration.get()
                if (outcome.networkGeneration >= 0L && outcome.networkGeneration != epoch) continue
                if (pending.updateAegis && flags.modelUpdatesEnabled && outcome.aegisToken != 0L) {
                    withAegis { it.observe(outcome.aegisToken, outcome, epoch) }
                    outcomeRecorded = true
                }
                if (!pending.updateLegacy) continue
                runCatching {
                    val samplesBefore = currentEngine.totalSamples()
                    currentEngine.update(outcome)
                    recordRecentMetrics(outcome)
                    outcomeRecorded = true
                    if (currentEngine.totalSamples() != samplesBefore) {
                        modelUpdated = true
                    }
                }.onFailure {
                    publishStatus(available = false, fallbackReason = "model-update-failed")
                }
            }
            if (outcomeRecorded) {
                publishStatus(available = true)
                if (modelUpdated && !closed.get()) schedulePersist(currentEngine)
            }
        }
    }

    private fun recordRecentMetrics(outcome: TurboOutcome) {
        val previous = recentMetrics.get()
        val alpha = if (previous.samples < 8L) 1.0 / (previous.samples + 1.0) else RECENT_EMA_ALPHA
        val success = if (outcome.success) 1.0 else 0.0
        val retry = outcome.retries.coerceIn(0, 3) / 3.0
        val handshake = outcome.handshakeLatencyMs.coerceAtLeast(0L).toDouble()
        recentMetrics.set(
            RecentMetrics(
                samples = previous.samples + 1L,
                successRatio = previous.successRatio + alpha * (success - previous.successRatio),
                retryRatio = previous.retryRatio + alpha * (retry - previous.retryRatio),
                handshakeMs = if (outcome.success) {
                    if (previous.handshakeMs <= 0.0) handshake
                    else previous.handshakeMs + alpha * (handshake - previous.handshakeMs)
                } else {
                    previous.handshakeMs
                }
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun registerPowerSaveReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val registered = runCatching {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(powerSaveReceiver, filter)
            }
        }.isSuccess
        if (!registered) {
            receiverRegistered.set(false)
        }
    }

    private fun schedulePersist(currentEngine: TurboAiEngine) {
        if (!persistScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            try {
                if (!closed.get() && engine.get() === currentEngine) {
                    store.save(currentEngine.exportState(), currentEngine.overallConfidence(), modelGeneration.get())
                }
            } finally {
                persistScheduled.set(false)
            }
        }
    }

    private fun publishStatus(
        available: Boolean,
        fallbackReason: String = "",
        refreshLegacy: Boolean = true
    ) = synchronized(outcomeDrainLock) {
        if (closed.get()) return@synchronized
        val currentEngine = engine.get()
        val samples = currentEngine?.totalSamples() ?: 0L
        val shadow = withAegis { it.stats() }
        val inferenceSamples = aegisInferenceSamples.copyOf(aegisInferenceSampleCount).apply { sort() }
        val status = TurboAiStatus(
                available = available,
                enabled = flags.enabled,
                samples = samples,
                confidence = if (refreshLegacy) currentEngine?.overallConfidence() ?: TurboConfidence.LOW
                    else lastPublishedStatus?.confidence ?: TurboConfidence.LOW,
                state = when {
                    !flags.enabled -> "disabled"
                    !available -> "fallback"
                    samples == 0L -> "learning"
                    else -> "active"
                },
                fallbackReason = fallbackReason,
                aegisMode = when (shadow?.mode) {
                    AegisLocal2Mode.SHADOW -> "AI_SHADOW"
                    AegisLocal2Mode.KILLED -> "AI_REWORK_REQUIRED"
                    else -> "AI_DISABLED"
                },
                aegisDecisions = shadow?.decisions ?: 0L,
                aegisObservations = shadow?.observations ?: 0L,
                aegisInferenceNanos = shadow?.inferenceNanos ?: 0L,
                aegisInferenceP50Nanos = aegisInferencePercentile(inferenceSamples, 50),
                aegisInferenceP95Nanos = aegisInferencePercentile(inferenceSamples, 95),
                aegisModelBytes = (shadow?.modelParameters ?: 0) * Float.SIZE_BYTES,
                aegisRuntimeDecisionCount = runtimeDecisionCount,
                aegisRuntimeDecisionNanos = runtimeDecisionNanos,
                aegisRuntimeDecisionMaxNanos = runtimeDecisionMaxNanos
            )
        lastPublishedStatus = status
        TurboAiStatusRegistry.set(ownerId, status)
    }

    private fun recordAegisInference(nanos: Long) {
        aegisInferenceSamples[aegisInferenceCursor] = nanos.coerceAtLeast(0L)
        aegisInferenceCursor = (aegisInferenceCursor + 1) % aegisInferenceSamples.size
        aegisInferenceSampleCount = minOf(aegisInferenceSampleCount + 1, aegisInferenceSamples.size)
    }

    private fun aegisInferencePercentile(samples: LongArray, percent: Int): Long {
        if (samples.isEmpty()) return 0L
        val rank = ((samples.size * percent + 99) / 100 - 1).coerceIn(0, samples.lastIndex)
        return samples[rank]
    }

    private fun <T> withAegis(action: (AegisLocal2Engine) -> T): T? {
        val current = aegis ?: return null
        if (!aegisReady || aegisFailed) return null
        return runCatching { action(current) }.getOrElse {
            aegisFailed = true
            null
        }
    }

    private fun latencyBucket(valueMs: Long): Int = when {
        valueMs <= 0L -> 0
        valueMs < 150L -> 1
        valueMs < 500L -> 2
        valueMs < 1_500L -> 3
        else -> 4
    }

    private fun ratioBucket(value: Double): Int = when {
        value <= 0.0 -> 0
        value < 0.25 -> 1
        value < 0.50 -> 2
        value < 0.80 -> 3
        else -> 4
    }

    companion object {
        private val runtimeOwnerSeed = AtomicLong()
        private const val PERSIST_DEBOUNCE_MS = 15_000L
        private const val RECENT_EMA_ALPHA = 0.12
        private const val MAX_PENDING_OUTCOMES = 512
    }
}
