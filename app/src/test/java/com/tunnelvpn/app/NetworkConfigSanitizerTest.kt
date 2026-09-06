package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkConfigSanitizerTest {
    @Test
    fun tunMtuIsBoundedToLocalRelayValues() {
        assertEquals(1280, sanitizeTunMtu(1000))
        assertEquals(1400, sanitizeTunMtu(1400))
        assertEquals(16_384, sanitizeTunMtu(16_384))
        assertEquals(32_768, sanitizeTunMtu(65_535))
    }

    @Test
    fun splitDomainLimitAcceptsTheMapperCapacity() {
        val values = (1..MAX_SPLIT_DOMAIN_COUNT).map { "host$it.example" }

        validateSplitDomainInput(values)

        assertEquals(MAX_SPLIT_DOMAIN_COUNT, sanitizeSplitDomains(values).valid.size)
    }

    @Test
    fun splitDomainLimitRejectsAnAdditionalEntry() {
        val values = (1..MAX_SPLIT_DOMAIN_COUNT + 1).map { "host$it.example" }

        assertThrows(NetworkConfigLimitException::class.java) {
            validateSplitDomainInput(values)
        }
    }

    @Test
    fun bypassPackageLimitRejectsAnAdditionalEntry() {
        val values = (1..MAX_BYPASS_PACKAGE_COUNT + 1).map { "com.example.app$it" }

        assertThrows(NetworkConfigLimitException::class.java) {
            validateBypassPackageInput(values)
        }
    }

    @Test
    fun bridgeLimitRejectsOversizedEncodedText() {
        val value = "가".repeat(30_000)

        assertThrows(NetworkConfigLimitException::class.java) {
            validateBridgeConfigText(value, "test")
        }
    }
}
