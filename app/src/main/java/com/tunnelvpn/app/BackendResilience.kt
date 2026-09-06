package com.tunnelvpn.app

import kotlin.math.roundToLong

enum class RiskLevel {
    NORMAL,
    ELEVATED,
    HIGH
}

enum class RiskSignal(val weight: Int) {
    MALFORMED_PACKET(8),
    MALFORMED_DNS(12),
    DNS_TRANSACTION_MISMATCH(18),
    DNS_POINTER_LOOP(24),
    RESOLVER_DISAGREEMENT(14),
    DNSSEC_BOGUS(45),
    RESOLVER_TLS_FAILURE(25),
    HOSTNAME_VERIFICATION_FAILURE(50),
    QUEUE_SATURATION(12),
    PERMIT_EXHAUSTION(10),
    NETWORK_VALIDATION_LOSS(18),
    HANDOVER_CHURN(10),
    INVARIANT_VIOLATION(35),
    REBINDING_ATTEMPT(35),
    SNI_STRATEGY_ROLLBACK(12)
}

data class RiskSnapshot(
    val level: RiskLevel,
    val score: Int,
    val reasonCode: String,
    val changedAtMs: Long
)

class ExplainableRiskEngine(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = 30_000L
) {
    private data class Event(val atMs: Long, val signal: RiskSignal)

    private val events = ArrayDeque<Event>()
    private var snapshot = RiskSnapshot(RiskLevel.NORMAL, 0, "none", clock())

    @Synchronized
    fun record(signal: RiskSignal): RiskSnapshot {
        val now = clock()
        events.addLast(Event(now, signal))
        trim(now)
        return evaluate(now, signal.name.lowercase())
    }

    @Synchronized
    fun current(): RiskSnapshot {
        val now = clock()
        trim(now)
        return evaluate(now, snapshot.reasonCode)
    }

    private fun trim(now: Long) {
        while (events.firstOrNull()?.let { now - it.atMs > windowMs } == true) events.removeFirst()
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    private fun evaluate(now: Long, newestReason: String): RiskSnapshot {
        val score = events.sumOf { it.signal.weight }.coerceAtMost(100)
        val next = when (snapshot.level) {
            RiskLevel.NORMAL -> if (score >= HIGH_ENTER) RiskLevel.HIGH else if (score >= ELEVATED_ENTER) RiskLevel.ELEVATED else RiskLevel.NORMAL
            RiskLevel.ELEVATED -> if (score >= HIGH_ENTER) RiskLevel.HIGH else if (score <= NORMAL_REENTER) RiskLevel.NORMAL else RiskLevel.ELEVATED
            RiskLevel.HIGH -> if (score <= ELEVATED_REENTER) RiskLevel.ELEVATED else RiskLevel.HIGH
        }
        if (next != snapshot.level) snapshot = RiskSnapshot(next, score, newestReason, now)
        else snapshot = snapshot.copy(score = score)
        return snapshot
    }

    companion object {
        private const val MAX_EVENTS = 64
        private const val ELEVATED_ENTER = 30
        private const val HIGH_ENTER = 65
        private const val NORMAL_REENTER = 15
        private const val ELEVATED_REENTER = 35
    }
}

data class ResourcePressure(
    val lowMemory: Boolean,
    val thermalSevere: Boolean,
    val fileDescriptorPressure: Boolean,
    val queueUtilization: Double,
    val timeoutRate: Double,
    val handoverActive: Boolean,
    val recovering: Boolean,
    val riskLevel: RiskLevel
)

class SafeBurstController(
    private val softLimit: Int,
    private val hardLimit: Int,
    private val maxBurstTokens: Int,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var availableTokens = maxBurstTokens.toDouble()
    private var lastRefillMs = clock()
    private var cooldownUntilMs = 0L

    init {
        require(softLimit > 0 && hardLimit >= softLimit)
        require(maxBurstTokens in 0..(hardLimit - softLimit))
    }

    @Synchronized
    fun admit(active: Int, pressure: ResourcePressure): Boolean {
        if (active < softLimit) return true
        if (active >= hardLimit || !healthy(pressure)) {
            cooldownUntilMs = clock() + COOLDOWN_MS
            return false
        }
        refill()
        if (clock() < cooldownUntilMs || availableTokens < 1.0) return false
        availableTokens -= 1.0
        return true
    }

    @Synchronized
    fun available(): Int {
        refill()
        return availableTokens.toInt()
    }

    private fun healthy(value: ResourcePressure): Boolean {
        return !value.lowMemory && !value.thermalSevere && !value.fileDescriptorPressure &&
            value.queueUtilization.isFinite() && value.queueUtilization in 0.0..<1.0 &&
            value.timeoutRate < 0.10 && !value.handoverActive &&
            !value.recovering && value.riskLevel == RiskLevel.NORMAL
    }

    private fun refill() {
        val now = clock()
        val elapsed = (now - lastRefillMs).coerceAtLeast(0L)
        if (elapsed > 0L) {
            availableTokens = (availableTokens + elapsed.toDouble() / REFILL_INTERVAL_MS).coerceAtMost(maxBurstTokens.toDouble())
            lastRefillMs = now
        }
    }

    companion object {
        private const val COOLDOWN_MS = 5_000L
        private const val REFILL_INTERVAL_MS = 1_000L
    }
}

class NetworkGenerationTracker {
    private var generation = 0L
    private var networkId: String? = null

    @Synchronized
    fun onAvailable(id: String): Long {
        if (networkId != id) {
            networkId = id
            generation += 1
        }
        return generation
    }

    @Synchronized
    fun onLost(id: String): Long {
        if (networkId == id) {
            networkId = null
            generation += 1
        }
        return generation
    }

    @Synchronized
    fun onContextChanged(id: String): Long {
        if (networkId == id) generation += 1
        return generation
    }

    @Synchronized
    fun invalidate(): Long {
        networkId = null
        generation += 1
        return generation
    }

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = candidate == generation
}

class RollingLatency(private val capacity: Int = 64) {
    private val values = ArrayDeque<Long>()
    private var ewma = 0.0

    @Synchronized
    fun add(valueMs: Long) {
        val value = valueMs.coerceIn(0L, 60_000L)
        if (values.size == capacity) values.removeFirst()
        values.addLast(value)
        ewma = if (values.size == 1) value.toDouble() else ewma * 0.80 + value * 0.20
    }

    @Synchronized
    fun percentile(percentile: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = ((sorted.lastIndex * percentile.coerceIn(0.0, 1.0)).roundToLong()).toInt()
        return sorted[index]
    }

    @Synchronized
    fun ewmaMs(): Long = ewma.roundToLong()

    @Synchronized
    fun count(): Int = values.size
}
