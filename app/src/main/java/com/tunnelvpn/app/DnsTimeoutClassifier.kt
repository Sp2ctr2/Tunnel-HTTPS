package com.tunnelvpn.app

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

internal object DnsTimeoutClassifier {
    fun isTimeout(error: Throwable): Boolean {
        if (error is SocketTimeoutException) return true
        return error is InterruptedIOException && error.message.orEmpty().contains("timeout", ignoreCase = true)
    }
}
