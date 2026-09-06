package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBlockerTest {
    @Test
    fun exactBlockMatch() {
        val blocker = DomainBlocker(enabled = true, blockedExact = setOf("ads.example.com"), blockedSuffixes = emptySet())

        assertTrue(blocker.shouldBlock("ads.example.com"))
    }

    @Test
    fun suffixBlockMatch() {
        val blocker = DomainBlocker(enabled = true, blockedExact = emptySet(), blockedSuffixes = setOf("tracker.example"))

        assertTrue(blocker.shouldBlock("pixel.tracker.example"))
    }

    @Test
    fun allowlistOverridesBlock() {
        val blocker = DomainBlocker(
            enabled = true,
            blockedExact = emptySet(),
            blockedSuffixes = setOf("payments.example"),
            allowlist = setOf("api.payments.example")
        )

        assertFalse(blocker.shouldBlock("api.payments.example"))
    }

    @Test
    fun allowlistOverridesSuffixBlock() {
        val blocker = DomainBlocker(
            enabled = true,
            blockedExact = emptySet(),
            blockedSuffixes = setOf("highperformance.example"),
            allowlist = setOf("safe.highperformance.example")
        )

        assertFalse(blocker.shouldBlock("safe.highperformance.example"))
    }

    @Test
    fun suffixBlockDoesNotMatchPartialLabel() {
        val blocker = DomainBlocker(
            enabled = true,
            blockedExact = emptySet(),
            blockedSuffixes = setOf("popunder-example.test"),
            allowlist = emptySet()
        )

        assertTrue(blocker.shouldBlock("cdn.popunder-example.test"))
        assertFalse(blocker.shouldBlock("popular.example.test"))
    }

    @Test
    fun nonBlockedDomainPassesThrough() {
        val blocker = DomainBlocker(enabled = true, blockedExact = setOf("ads.example.com"), blockedSuffixes = emptySet())

        assertFalse(blocker.shouldBlock("news.example.com"))
    }

    @Test
    fun safeSuffixDoesNotFalsePositivePartialLabels() {
        val blocker = DomainBlocker(enabled = true, blockedExact = emptySet(), blockedSuffixes = setOf("ad.com"))

        assertFalse(blocker.shouldBlock("notad.com"))
        assertTrue(blocker.shouldBlock("cdn.ad.com"))
    }

    @Test
    fun defaultListBlocksCommonRedirectAdNetworks() {
        val blocker = assetBlocker()

        assertTrue(blocker.shouldBlock("serve.popads.net"))
        assertTrue(blocker.shouldBlock("go.pushnative.com"))
        assertTrue(blocker.shouldBlock("a.exosrv.com"))
    }

    @Test
    fun defaultListBlocksPopupAndGamblingStyleAdNetworks() {
        val blocker = assetBlocker()

        assertTrue(blocker.shouldBlock("syndication.exdynsrv.com"))
        assertTrue(blocker.shouldBlock("cdn.popunder.bid"))
        assertTrue(blocker.shouldBlock("serve.clickadilla.com"))
    }

    @Test
    fun defaultListBlocksModernRedirectGateways() {
        val blocker = assetBlocker()

        assertTrue(blocker.shouldBlock("cdn.magsrv.com"))
        assertTrue(blocker.shouldBlock("serve.popads.net"))
        assertTrue(blocker.shouldBlock("cdn.exdynsrv.com"))
    }

    @Test
    fun defaultListBlocksAggressiveMobileAndTrackerNetworks() {
        val blocker = assetBlocker()

        assertTrue(blocker.shouldBlock("serve.applovin.com"))
        assertTrue(blocker.shouldBlock("ads.vungle.com"))
        assertTrue(blocker.shouldBlock("config.inmobi.com"))
        assertTrue(blocker.shouldBlock("events.adjust.com"))
        assertTrue(blocker.shouldBlock("api2.appsflyer.com"))
        assertTrue(blocker.shouldBlock("cdn.segment.com"))
    }

    @Test
    fun defaultListBlocksAdShortenerRedirectDomains() {
        val blocker = assetBlocker()

        assertTrue(blocker.shouldBlock("ouo.io"))
        assertTrue(blocker.shouldBlock("go.ouo.press"))
        assertTrue(blocker.shouldBlock("clk.sh"))
        assertTrue(blocker.shouldBlock("api.shrinkearn.com"))
        assertTrue(blocker.shouldBlock("cuty.io"))
    }

    @Test
    fun youtubeMediaDomainsAreNotBlockedByAggressiveDefaults() {
        val blocker = assetBlocker()

        assertFalse(blocker.shouldBlock("rr1---sn-ab5l6n6s.googlevideo.com"))
        assertFalse(blocker.shouldBlock("i.ytimg.com"))
        assertFalse(blocker.shouldBlock("www.gstatic.com"))
    }

    @Test
    fun defaultListDoesNotBlockRequestedSiteDomainItself() {
        val blocker = assetBlocker()

        assertFalse(blocker.shouldBlock("pornhub.com"))
        assertFalse(blocker.shouldBlock("www.pornhub.com"))
    }

    @Test
    fun parseSuffixListHandlesBareAndHostsFormatAndComments() {
        val parsed = DomainBlocker.parseSuffixList(
            sequenceOf(
                "# comment line",
                "",
                "   ",
                "ads.example.com",
                "0.0.0.0 tracker.example.net",
                "127.0.0.1  metrics.example.org  # trailing comment",
                "not a domain",
                "bad_underscore.example"
            )
        )

        assertTrue("ads.example.com" in parsed)
        assertTrue("tracker.example.net" in parsed)
        assertTrue("metrics.example.org" in parsed)

        assertFalse("domain" in parsed)
        assertFalse("bad_underscore.example" in parsed)
    }

    @Test
    fun exactBlockWinsOverBroadAllowlistSuffix() {
        val blocker = DomainBlocker(
            enabled = true,
            blockedExact = setOf("adservice.google.com"),
            blockedSuffixes = emptySet(),
            allowlist = setOf("google.com")
        )

        assertTrue(blocker.shouldBlock("adservice.google.com"))

        assertFalse(blocker.shouldBlock("mail.google.com"))
    }

    @Test
    fun assetListBlocksSpecificEndpointsWithoutBlockingParentServices() {
        val blocker = assetBlocker()

        assertTrue(blocker.shouldBlock("adservice.google.com"))
        assertFalse(blocker.shouldBlock("mail.google.com"))
        assertFalse(blocker.shouldBlock("www.microsoft.com"))
    }

    @Test
    fun extraSuffixesFromAssetAreBlockedButAllowlistStillWins() {
        val blocker = DomainBlocker(
            enabled = true,
            blockedExact = emptySet(),
            blockedSuffixes = emptySet(),
            allowlist = setOf("safe.newtracker.example"),
            extraSuffixes = setOf("newtracker.example")
        )

        assertTrue(blocker.shouldBlock("pixel.newtracker.example"))
        assertFalse(blocker.shouldBlock("safe.newtracker.example"))
    }

    private fun assetBlocker(): DomainBlocker {
        val suffixes = File("src/main/assets/adblock_suffixes.txt").useLines {
            DomainBlocker.parseSuffixList(it)
        }
        return DomainBlocker(enabled = true, extraSuffixes = suffixes)
    }
}
