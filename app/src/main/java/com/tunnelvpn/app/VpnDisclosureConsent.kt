package com.tunnelvpn.app

import android.content.Context
import android.content.pm.ApplicationInfo

object VpnDisclosureConsent {
    const val CURRENT_VERSION = 1
    const val PREFERENCE_KEY = "vpn_disclosure_version"

    fun isAccepted(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        return context.getSharedPreferences(TunnelVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREFERENCE_KEY, 0) == CURRENT_VERSION
    }

    fun accept(context: Context) {
        context.getSharedPreferences(TunnelVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREFERENCE_KEY, CURRENT_VERSION)
            .apply()
    }
}
