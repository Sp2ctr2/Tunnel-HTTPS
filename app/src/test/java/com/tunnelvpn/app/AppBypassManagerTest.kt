package com.tunnelvpn.app

import org.junit.Assert.assertThrows
import org.junit.Test

class AppBypassManagerTest {
    @Test
    fun ownPackageExclusionFailureIsFatal() {
        assertThrows(AppBypassConfigurationException::class.java) {
            requireOwnPackageDisallowed(
                packageName = "com.tunnelvpn.app",
                ownPackage = "com.tunnelvpn.app",
                exists = true,
                applied = false
            )
        }
    }

    @Test
    fun missingOwnPackageIsFatal() {
        assertThrows(AppBypassConfigurationException::class.java) {
            requireOwnPackageDisallowed(
                packageName = "com.tunnelvpn.app",
                ownPackage = "com.tunnelvpn.app",
                exists = false,
                applied = false
            )
        }
    }

    @Test
    fun optionalRequestedPackageFailureIsIgnored() {
        requireOwnPackageDisallowed(
            packageName = "optional.browser",
            ownPackage = "com.tunnelvpn.app",
            exists = true,
            applied = false
        )
    }
}
