package com.tunnelvpn.app

enum class ProtectionMode(val prefValue: String) {
    FAST("fast"),
    BALANCED("balanced"),
    STRONG("strong");

    val protectsSni: Boolean
        get() = true

    val capturesAllTraffic: Boolean
        get() = true

    companion object {
        fun from(value: String?): ProtectionMode {
            return when (value?.trim()?.lowercase()) {
                FAST.prefValue -> FAST
                BALANCED.prefValue, "https_local", "local_https" -> BALANCED
                STRONG.prefValue, "enhanced_tunnel", "secure_tunnel" -> STRONG
                else -> BALANCED
            }
        }
    }
}
