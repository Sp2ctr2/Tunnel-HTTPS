package com.tunnelvpn.app

enum class RouteMode(val value: String) {
    NORMAL_DNS_ONLY("NORMAL_DNS_ONLY"),
    TURBO_SELECTIVE_FORWARD("TURBO_SELECTIVE_FORWARD"),
    TURBO_FULL_FORWARD("TURBO_FULL_FORWARD")
}
