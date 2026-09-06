package com.tunnelvpn.app

internal class Ipv6IcmpErrorRateLimiter(
    private val clock: () -> Long = MonotonicClock::elapsedRealtimeMs,
    private val maximumPerWindow: Int = 32,
    private val windowMs: Long = 1_000L
) {
    private var windowStartedMs = Long.MIN_VALUE
    private var emitted = 0

    init {
        require(maximumPerWindow > 0)
        require(windowMs > 0L)
    }

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (windowStartedMs == Long.MIN_VALUE || now < windowStartedMs || now - windowStartedMs >= windowMs) {
            windowStartedMs = now
            emitted = 0
        }
        if (emitted >= maximumPerWindow) return false
        emitted += 1
        return true
    }
}
