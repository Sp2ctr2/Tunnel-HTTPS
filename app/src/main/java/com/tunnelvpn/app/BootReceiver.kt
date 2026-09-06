package com.tunnelvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val bootAction = intent?.action ?: return
        if (bootAction != Intent.ACTION_BOOT_COMPLETED &&
            bootAction != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                restoreAfterBoot(appContext, bootAction)
            } finally {
                pendingResult.finish()
            }
        }.apply {
            name = "TunnelVpnBootRestore"
            isDaemon = true
            start()
        }
    }

    private fun restoreAfterBoot(context: Context, bootAction: String) {
        val prefs = context.getSharedPreferences(TunnelVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(TunnelVpnService.PREF_AUTO_START, false)
        if (!autoStart) return
        if (bootAction == Intent.ACTION_MY_PACKAGE_REPLACED &&
            !prefs.getBoolean(TunnelVpnService.PREF_ACTIVE, false)
        ) return
        if (!VpnDisclosureConsent.isAccepted(context)) {
            prefs.edit().putBoolean(TunnelVpnService.PREF_ACTIVE, false).apply()
            return
        }
        if (VpnService.prepare(context) != null) {
            prefs.edit().putBoolean(TunnelVpnService.PREF_ACTIVE, false).apply()
            return
        }

        val splitDomainInput = prefs.getString(TunnelVpnService.EXTRA_SPLIT_DOMAINS, "").orEmpty().lines()
        val bypassPackageInput = prefs.getString(TunnelVpnService.EXTRA_BYPASS_PACKAGES, "").orEmpty().lines()
        try {
            validateSplitDomainInput(splitDomainInput)
            validateBypassPackageInput(bypassPackageInput)
        } catch (_: NetworkConfigLimitException) {
            prefs.edit().putBoolean(TunnelVpnService.PREF_ACTIVE, false).apply()
            return
        }

        val serviceIntent = Intent(context, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra(
                TunnelVpnService.EXTRA_TRANSITION_REASON,
                TunnelVpnService.TRANSITION_REASON_AUTO_START
            )
            putExtra(TunnelVpnService.EXTRA_DNS_SERVER, prefs.getString(TunnelVpnService.EXTRA_DNS_SERVER, "1.1.1.1"))
            putExtra(TunnelVpnService.EXTRA_PROTECTION_MODE, prefs.getString(TunnelVpnService.EXTRA_PROTECTION_MODE, TunnelVpnService.MODE_HTTPS_LOCAL))
            putExtra(
                TunnelVpnService.EXTRA_MTU,
                if (prefs.getBoolean(TunnelVpnService.EXTRA_BATTERY_SAVER, false)) {
                    TunnelVpnService.BATTERY_SAVER_TUN_MTU
                } else {
                    TunnelVpnService.PERFORMANCE_TUN_MTU
                }
            )
            putExtra(TunnelVpnService.EXTRA_ROUTE_ALL, prefs.getBoolean(TunnelVpnService.EXTRA_ROUTE_ALL, true))
            putExtra(TunnelVpnService.EXTRA_DNS_PROTECTION, true)
            putExtra(TunnelVpnService.EXTRA_SPLIT_DOMAINS, sanitizeSplitDomains(splitDomainInput).valid.toTypedArray())
            putExtra(TunnelVpnService.EXTRA_BYPASS_PACKAGES, sanitizePackageNames(bypassPackageInput, context.packageName).toTypedArray())
            putExtra(TunnelVpnService.EXTRA_BATTERY_SAVER, prefs.getBoolean(TunnelVpnService.EXTRA_BATTERY_SAVER, false))
            putExtra(TunnelVpnService.EXTRA_DOH, prefs.getBoolean(TunnelVpnService.PREF_DOH_ENABLED, true))
            putExtra(TunnelVpnService.EXTRA_AD_BLOCK, prefs.getBoolean(TunnelVpnService.PREF_ADBLOCK_ENABLED, true))
            putExtra(TunnelVpnService.EXTRA_TURBO_MODE, prefs.getBoolean(TunnelVpnService.PREF_TURBO_MODE, false))
            putExtra(TunnelVpnService.EXTRA_BROWSER_ONLY, prefs.getBoolean(TunnelVpnService.PREF_BROWSER_ONLY, false))
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: RuntimeException) {
            prefs.edit().putBoolean(TunnelVpnService.PREF_ACTIVE, false).apply()
        }
    }

}
