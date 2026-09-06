package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestAndBootContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val bootReceiver = File("src/main/java/com/tunnelvpn/app/BootReceiver.kt").readText()
    private val legacyBackupRules = File("src/main/res/xml/backup_rules.xml").readText()
    private val extractionRules = File("src/main/res/xml/data_extraction_rules.xml").readText()

    @Test
    fun browserDiscoveryUsesNarrowHttpsPackageVisibility() {
        assertTrue(manifest.contains("android.intent.action.VIEW"))
        assertTrue(manifest.contains("android.intent.category.BROWSABLE"))
        assertTrue(manifest.contains("android:scheme=\"https\""))
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
    }

    @Test
    fun bootRestoresBrowserOnlyAndValidatesBinderInputs() {
        assertTrue(bootReceiver.contains("TRANSITION_REASON_AUTO_START"))
        assertTrue(bootReceiver.contains("EXTRA_BROWSER_ONLY"))
        assertTrue(bootReceiver.contains("validateSplitDomainInput(splitDomainInput)"))
        assertTrue(bootReceiver.contains("validateBypassPackageInput(bypassPackageInput)"))
        assertTrue(bootReceiver.contains("bootAction == Intent.ACTION_MY_PACKAGE_REPLACED"))
        assertTrue(bootReceiver.contains("PREF_ACTIVE"))
    }

    @Test
    fun alwaysOnIsDisabledUntilFailClosedRoutingIsDeviceValidated() {
        assertTrue(manifest.contains("android.net.VpnService.SUPPORTS_ALWAYS_ON"))
        assertTrue(manifest.contains("android:value=\"false\""))
    }

    @Test
    fun vpnPreferencesAndModelsAreExcludedFromEveryBackupMode() {
        val domains = listOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref"
        )
        domains.forEach { domain ->
            val rule = "<exclude domain=\"$domain\" path=\".\""
            assertEquals(1, legacyBackupRules.windowed(rule.length).count { it == rule })
            assertEquals(2, extractionRules.windowed(rule.length).count { it == rule })
        }
        assertTrue(extractionRules.contains("<cloud-backup>"))
        assertTrue(extractionRules.contains("<device-transfer>"))
    }
}
