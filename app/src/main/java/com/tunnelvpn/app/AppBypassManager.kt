package com.tunnelvpn.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService

class AppBypassManager(
    private val context: Context,
    private val requestedPackages: List<String>,
    private val browserTrafficOnly: Boolean = false
) {
    fun apply(builder: VpnService.Builder): Int {
        if (browserTrafficOnly) {
            val browsers = detectBrowserPackages()
            requireBrowserOnlyPackages(browsers.size)
            validateBypassPackageInput(browsers)
            val applied = applyAllowList(builder, browsers)
            requireBrowserOnlyPackages(browsers.size, applied)
            return applied
        }
        return applyDisallowList(builder)
    }

    private fun applyAllowList(builder: VpnService.Builder, browsers: List<String>): Int {
        var applied = 0
        browsers.distinct().forEach { packageName ->
            if (addAllowedApplication(builder, packageName)) applied += 1
        }
        return applied
    }

    private fun applyDisallowList(builder: VpnService.Builder): Int {
        var applied = 0
        val ownPackage = context.packageName.trim()
        if (ownPackage.isEmpty()) throw AppBypassConfigurationException("VPN application package is unavailable")
        candidates().forEach { packageName ->
            val exists = packageExists(packageName)
            if (!exists) {
                requireOwnPackageDisallowed(packageName, ownPackage, exists = false, applied = false)
                return@forEach
            }
            val added = addDisallowedApplication(builder, packageName)
            requireOwnPackageDisallowed(packageName, ownPackage, exists = true, applied = added)
            if (added) applied += 1
        }
        return applied
    }

    private fun addAllowedApplication(builder: VpnService.Builder, packageName: String): Boolean {
        return try {
            builder.addAllowedApplication(packageName)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun addDisallowedApplication(builder: VpnService.Builder, packageName: String): Boolean {
        return try {
            builder.addDisallowedApplication(packageName)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun detectBrowserPackages(): List<String> {
        val viewHttps = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(viewHttps, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != context.packageName }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun candidates(): List<String> {
        return (listOf(context.packageName) + requestedPackages)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun packageExists(packageName: String): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }
}

class BrowserOnlyConfigurationException(message: String) : IllegalStateException(message)
class AppBypassConfigurationException(message: String) : IllegalStateException(message)

internal fun requireOwnPackageDisallowed(
    packageName: String,
    ownPackage: String,
    exists: Boolean,
    applied: Boolean
) {
    if (packageName == ownPackage && (!exists || !applied)) {
        throw AppBypassConfigurationException("VPN application could not be excluded from its own tunnel")
    }
}

internal fun requireBrowserOnlyPackages(detectedCount: Int, appliedCount: Int? = null) {
    if (detectedCount <= 0) {
        throw BrowserOnlyConfigurationException("No browser application is available for browser-only routing")
    }
    if (appliedCount != null && appliedCount <= 0) {
        throw BrowserOnlyConfigurationException("Browser-only routing could not be applied")
    }
}
