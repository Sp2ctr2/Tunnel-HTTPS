package com.tunnelvpn.app

import android.os.SystemClock

internal object MonotonicClock {
    fun elapsedRealtimeMs(): Long {
        return runCatching { SystemClock.elapsedRealtime() }
            .getOrElse { System.nanoTime() / 1_000_000L }
    }
}
