package com.tunnelvpn.app

import org.junit.Assert.assertThrows
import org.junit.Test

class AppBypassPolicyTest {
    @Test
    fun browserOnlyRejectsEmptyDiscoveryInsteadOfCapturingEveryApplication() {
        assertThrows(BrowserOnlyConfigurationException::class.java) {
            requireBrowserOnlyPackages(detectedCount = 0)
        }
    }

    @Test
    fun browserOnlyRejectsZeroSuccessfullyAppliedPackages() {
        assertThrows(BrowserOnlyConfigurationException::class.java) {
            requireBrowserOnlyPackages(detectedCount = 2, appliedCount = 0)
        }
    }

    @Test
    fun browserOnlyAcceptsAtLeastOneAppliedPackage() {
        requireBrowserOnlyPackages(detectedCount = 2, appliedCount = 1)
    }
}
