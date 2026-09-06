package com.tunnelvpn.app

enum class TurboRuntimeState {
    DISABLED,
    ENABLING,
    ACTIVE,
    DEGRADED,
    GUARDED,
    RECOVERING,
    COOLING_DOWN,
    DISABLING,
    ERROR
}

data class TurboRuntimeSnapshot(
    val desired: Boolean,
    val state: TurboRuntimeState,
    val generation: Long,
    val reasonCode: String
) {
    val transitionInProgress: Boolean
        get() = state == TurboRuntimeState.ENABLING || state == TurboRuntimeState.DISABLING ||
            state == TurboRuntimeState.RECOVERING || state == TurboRuntimeState.COOLING_DOWN
}

object TurboRuntimeController {
    private val lock = Any()
    private var desired = false
    private var state = TurboRuntimeState.DISABLED
    private var generation = 0L
    private var reasonCode = "none"

    fun initializePreference(enabled: Boolean, vpnConnected: Boolean): TurboRuntimeSnapshot = synchronized(lock) {
        if (generation != 0L) return@synchronized snapshotLocked()
        desired = enabled
        state = if (enabled && vpnConnected) TurboRuntimeState.ENABLING else if (enabled) TurboRuntimeState.DEGRADED else TurboRuntimeState.DISABLED
        reasonCode = if (enabled && vpnConnected) "restoring" else if (enabled) "vpn-disconnected" else "user-disabled"
        snapshotLocked()
    }

    fun restorePreference(enabled: Boolean, vpnConnected: Boolean): TurboRuntimeSnapshot = synchronized(lock) {
        desired = enabled
        generation += 1
        state = when {
            !enabled -> TurboRuntimeState.DISABLED
            vpnConnected -> TurboRuntimeState.ENABLING
            else -> TurboRuntimeState.DEGRADED
        }
        reasonCode = when {
            !enabled -> "user-disabled"
            vpnConnected -> "restoring"
            else -> "vpn-disconnected"
        }
        snapshotLocked()
    }

    fun beginChange(enabled: Boolean, vpnConnected: Boolean): TurboRuntimeSnapshot = synchronized(lock) {
        if (desired == enabled && !isTransitionLocked()) return@synchronized snapshotLocked()
        desired = enabled
        generation += 1
        state = when {
            enabled && vpnConnected -> TurboRuntimeState.ENABLING
            enabled -> TurboRuntimeState.DEGRADED
            vpnConnected -> TurboRuntimeState.DISABLING
            else -> TurboRuntimeState.DISABLED
        }
        reasonCode = when {
            enabled && vpnConnected -> "initializing"
            enabled -> "vpn-disconnected"
            vpnConnected -> "releasing"
            else -> "user-disabled"
        }
        snapshotLocked()
    }

    fun activated(expectedGeneration: Long): TurboRuntimeSnapshot = synchronized(lock) {
        if (expectedGeneration != generation || !desired) return@synchronized snapshotLocked()
        state = TurboRuntimeState.ACTIVE
        reasonCode = "operational"
        snapshotLocked()
    }

    fun degraded(expectedGeneration: Long, reason: String): TurboRuntimeSnapshot = synchronized(lock) {
        if (expectedGeneration != generation || !desired) return@synchronized snapshotLocked()
        state = TurboRuntimeState.DEGRADED
        reasonCode = safeReason(reason)
        snapshotLocked()
    }

    fun guarded(expectedGeneration: Long, reason: String): TurboRuntimeSnapshot = synchronized(lock) {
        if (expectedGeneration != generation || !desired) return@synchronized snapshotLocked()
        state = TurboRuntimeState.GUARDED
        reasonCode = safeReason(reason)
        snapshotLocked()
    }

    fun failed(expectedGeneration: Long, reason: String): TurboRuntimeSnapshot = synchronized(lock) {
        if (expectedGeneration != generation) return@synchronized snapshotLocked()
        state = if (desired) TurboRuntimeState.ERROR else TurboRuntimeState.DISABLED
        reasonCode = safeReason(reason)
        snapshotLocked()
    }

    fun disabled(expectedGeneration: Long): TurboRuntimeSnapshot = synchronized(lock) {
        if (expectedGeneration != generation || desired) return@synchronized snapshotLocked()
        state = TurboRuntimeState.DISABLED
        reasonCode = "user-disabled"
        snapshotLocked()
    }

    fun snapshot(): TurboRuntimeSnapshot = synchronized(lock) { snapshotLocked() }

    internal fun resetForTest() = synchronized(lock) {
        desired = false
        state = TurboRuntimeState.DISABLED
        generation = 0L
        reasonCode = "none"
    }

    private fun isTransitionLocked(): Boolean {
        return state == TurboRuntimeState.ENABLING || state == TurboRuntimeState.DISABLING ||
            state == TurboRuntimeState.RECOVERING || state == TurboRuntimeState.COOLING_DOWN
    }

    private fun snapshotLocked(): TurboRuntimeSnapshot {
        return TurboRuntimeSnapshot(desired, state, generation, reasonCode)
    }

    private fun safeReason(value: String): String {
        return value.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "unspecified" }
    }
}
