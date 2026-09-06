package com.tunnelvpn.app

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Nat64PrefixTest {
    @Test
    fun nat64DiscoveryRunsOnlyOnIpv6OnlyNetworksWithoutAPublishedPrefix() {
        assertFalse(LocalProtectionEngine.shouldAttemptNat64Discovery(true, false, true))
        assertFalse(LocalProtectionEngine.shouldAttemptNat64Discovery(false, true, true))
        assertFalse(LocalProtectionEngine.shouldAttemptNat64Discovery(false, false, false))
        assertTrue(LocalProtectionEngine.shouldAttemptNat64Discovery(false, false, true))
    }

    @Test
    fun synthesizesEveryRfc6052PrefixLength() {
        val ipv4 = InetAddress.getByName("192.0.2.33").address
        val cases = listOf(
            "2001:db8::" to (32 to "2001:db8:c000:221::"),
            "2001:db8:100::" to (40 to "2001:db8:1c0:2:21::"),
            "2001:db8:122::" to (48 to "2001:db8:122:c000:2:2100::"),
            "2001:db8:122:300::" to (56 to "2001:db8:122:3c0:0:221::"),
            "2001:db8:122:344::" to (64 to "2001:db8:122:344:c0:2:2100::"),
            "2001:db8:122:344::" to (96 to "2001:db8:122:344::c000:221")
        )

        cases.forEach { (prefixText, spec) ->
            val prefix = Nat64Prefix.from(InetAddress.getByName(prefixText).address, spec.first)!!
            assertArrayEquals(InetAddress.getByName(spec.second).address, prefix.synthesize(ipv4))
        }
    }

    @Test
    fun normalizesHostBitsAndRejectsInvalidPrefixes() {
        val raw = InetAddress.getByName("2001:db8:122:344::ffff").address
        val prefix = Nat64Prefix.from(raw, 96)!!

        assertArrayEquals(InetAddress.getByName("2001:db8:122:344::").address, prefix.addressBytes())
        assertNull(Nat64Prefix.from(raw, 72))
        assertNull(Nat64Prefix.from(ByteArray(4), 96))
    }

    @Test
    fun rejectsNonUnicastAndIpv4MappedTranslationPrefixes() {
        listOf("::", "::ffff:0:0", "fe80::", "ff00::").forEach { value ->
            assertNull(Nat64Prefix.from(InetAddress.getByName(value).address, 96))
        }
        assertNotNull(Nat64Prefix.from(InetAddress.getByName("fd00:64::").address, 96))
        assertNotNull(Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96))
    }

    @Test
    fun translatorOnlyMapsIpv4WhenAPrefixIsActive() {
        val translator = Nat64AddressTranslator()
        val ipv4 = InetAddress.getByName("93.184.216.34")
        val ipv6 = InetAddress.getByName("2001:db8::1")

        assertSame(ipv4, translator.translate(ipv4))
        translator.update(Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96))
        assertEquals("64:ff9b:0:0:0:0:5db8:d822", translator.translate(ipv4).hostAddress)
        assertSame(ipv6, translator.translate(ipv6))
    }

    @Test
    fun wellKnownPrefixNeverTranslatesNonGlobalIpv4() {
        val translator = Nat64AddressTranslator()
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val networkSpecific = Nat64Prefix.from(InetAddress.getByName("2001:470:64::").address, 96)!!
        val privateAddress = InetAddress.getByName("192.168.1.1")

        translator.updateAll(listOf(wellKnown))
        assertSame(privateAddress, translator.translateAll(privateAddress).single())

        translator.updateAll(listOf(wellKnown, networkSpecific))
        val translated = translator.translateAll(privateAddress)
        assertEquals(1, translated.size)
        assertArrayEquals(
            networkSpecific.synthesize(privateAddress.address),
            translated.single().address
        )
    }

    @Test
    fun translatorGenerationChangesOnlyWhenThePrefixChanges() {
        val translator = Nat64AddressTranslator()
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!

        assertEquals(0L, translator.generation())
        translator.update(prefix)
        assertEquals(1L, translator.generation())
        translator.update(prefix)
        assertEquals(1L, translator.generation())
        translator.update(null)
        assertEquals(2L, translator.generation())
    }

    @Test
    fun discoversRfc7050PrefixFromOneOrMoreIpv4OnlyArpaAnswers() {
        val prefix = Nat64Prefix.from(InetAddress.getByName("2001:db8:122:344::").address, 64)!!
        val first = prefix.synthesize(byteArrayOf(192.toByte(), 0, 0, 170.toByte()))
        val second = prefix.synthesize(byteArrayOf(192.toByte(), 0, 0, 171.toByte()))

        val discovered = Nat64Prefix.discover(listOf(first, second))!!

        assertEquals(64, discovered.length)
        assertArrayEquals(prefix.addressBytes(), discovered.addressBytes())
        assertEquals(prefix, Nat64Prefix.discover(listOf(first)))
        assertEquals(prefix, Nat64Prefix.discover(listOf(second)))
        assertNull(Nat64Prefix.discover(listOf(InetAddress.getByName("2001:db8::1").address)))
    }

    @Test
    fun discoversEitherWellKnownAddressAtEveryRfc6052PrefixLength() {
        val cases = listOf(
            "2001:db8::" to 32,
            "2001:db8:100::" to 40,
            "2001:db8:122::" to 48,
            "2001:db8:122:300::" to 56,
            "2001:db8:122:344::" to 64,
            "2001:db8:122:344::" to 96
        )
        val addresses = listOf(170, 171)

        cases.forEach { (text, length) ->
            val prefix = Nat64Prefix.from(InetAddress.getByName(text).address, length)!!
            addresses.forEach { suffix ->
                val wka = byteArrayOf(192.toByte(), 0, 0, suffix.toByte())
                assertEquals(prefix, Nat64Prefix.discover(listOf(prefix.synthesize(wka))))
            }
        }
    }

    @Test
    fun discoveryIgnoresNonzeroRfc6052SuffixBits() {
        val cases = listOf(
            "2001:db8::" to 32,
            "2001:db8:100::" to 40,
            "2001:db8:122::" to 48,
            "2001:db8:122:300::" to 56,
            "2001:db8:122:344::" to 64
        )
        val wka = byteArrayOf(192.toByte(), 0, 0, 170.toByte())

        cases.forEach { (text, length) ->
            val prefix = Nat64Prefix.from(InetAddress.getByName(text).address, length)!!
            val address = prefix.synthesize(wka).also { it[15] = 0x5a }

            assertEquals(prefix, Nat64Prefix.discover(listOf(address)))
        }
    }

    @Test
    fun discoveryRejectsANonzeroRfc6052ReservedOctet() {
        val cases = listOf(
            "2001:db8::" to 32,
            "2001:db8:100::" to 40,
            "2001:db8:122::" to 48,
            "2001:db8:122:300::" to 56,
            "2001:db8:122:344::" to 64
        )
        val wka = byteArrayOf(192.toByte(), 0, 0, 170.toByte())

        cases.forEach { (text, length) ->
            val prefix = Nat64Prefix.from(InetAddress.getByName(text).address, length)!!
            val address = prefix.synthesize(wka).also { it[8] = 1 }

            assertNull(Nat64Prefix.discover(listOf(address)))
        }
    }

    @Test
    fun slash96AlsoRequiresTheRfc6052ReservedOctetToBeZero() {
        val invalid = InetAddress.getByName("2001:db8:122:344:ab00::").address
        val valid = Nat64Prefix.from(InetAddress.getByName("2001:db8:122:344::").address, 96)!!
        val address = valid.synthesize(byteArrayOf(192.toByte(), 0, 0, 170.toByte()))

        assertNull(Nat64Prefix.from(invalid, 96))
        assertNull(valid.extractIpv4(address.also { it[8] = 1 }))
        assertNull(Nat64Prefix.discover(listOf(address)))
    }

    @Test
    fun discoversMultiplePrefixesInAnswerOrderWithoutDuplicates() {
        val firstPrefix = Nat64Prefix.from(InetAddress.getByName("2001:db8:1::").address, 48)!!
        val secondPrefix = Nat64Prefix.from(InetAddress.getByName("2001:db8:2:300::").address, 56)!!
        val wka = byteArrayOf(192.toByte(), 0, 0, 170.toByte())

        val discovered = Nat64Prefix.discoverAll(
            listOf(
                secondPrefix.synthesize(wka),
                firstPrefix.synthesize(wka),
                secondPrefix.synthesize(wka)
            )
        )

        assertEquals(listOf(secondPrefix, firstPrefix), discovered)
    }

    @Test
    fun eachExactLegalSynthesisCanAdvertiseADistinctPrefix() {
        val firstAddress = byteArrayOf(
            0x20, 0xc0.toByte(), 0x00, 0x00, 0xaa.toByte(), 0x01, 0x02, 0x03,
            0x00, 0x04, 0x05, 0x06, 0xc0.toByte(), 0x00, 0x00, 0xaa.toByte()
        )
        val firstPrefix = Nat64Prefix.from(firstAddress, 96)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("2001:db8:122:344::").address, 64)!!
        val other = prefix.synthesize(byteArrayOf(192.toByte(), 0, 0, 171.toByte()))

        assertEquals(firstPrefix, Nat64Prefix.discover(listOf(firstAddress)))
        assertEquals(listOf(firstPrefix, prefix), Nat64Prefix.discoverAll(listOf(firstAddress, other)))
    }

    @Test
    fun discoveryIgnoresIncidentalWellKnownBytesInsideAValidPrefix() {
        val prefix = Nat64Prefix.from(InetAddress.getByName("2001:c000:aa:c000:ab:0::").address, 96)!!
        val first = prefix.synthesize(byteArrayOf(192.toByte(), 0, 0, 170.toByte()))
        val second = prefix.synthesize(byteArrayOf(192.toByte(), 0, 0, 171.toByte()))

        assertEquals(listOf(prefix), Nat64Prefix.discoverAll(listOf(first, second)))
    }

    @Test
    fun pairedDiscoveryDoesNotMistakePrefixBytesForAShorterPrefix() {
        val prefix = Nat64Prefix.from(InetAddress.getByName("2001:db8:c000:aa::").address, 96)!!
        val first = prefix.synthesize(byteArrayOf(192.toByte(), 0, 0, 170.toByte()))
        val second = prefix.synthesize(byteArrayOf(192.toByte(), 0, 0, 171.toByte()))

        assertEquals(listOf(prefix), Nat64Prefix.discoverAll(listOf(first, second)))
    }

    @Test
    fun seededDiscoveryOnlyAcceptsExactRfc6052Embeddings() {
        val random = java.util.Random(0x70506052L)
        val lengths = listOf(32, 40, 48, 56, 64, 96)
        repeat(300) {
            val length = lengths[random.nextInt(lengths.size)]
            val raw = ByteArray(16).also(random::nextBytes)
            val prefixBytes = length / 8
            for (index in prefixBytes until raw.size) raw[index] = 0
            raw[8] = 0
            val prefix = Nat64Prefix.from(raw, length)
            if (prefix == null) return@repeat
            val wka = byteArrayOf(192.toByte(), 0, 0, (170 + random.nextInt(2)).toByte())
            val address = prefix.synthesize(wka)
            val embeddedOccurrences = lengths.count { candidateLength ->
                embedsAtRfc6052Position(address, candidateLength, wka)
            }
            val discovered = Nat64Prefix.discover(listOf(address))
            if (embeddedOccurrences == 1) {
                assertEquals(prefix, discovered)
            } else {
                assertNull(discovered)
            }
        }
    }

    @Test
    fun translatorPreservesPrefixOrderAndScopesLinkLocalLiterals() {
        val translator = Nat64AddressTranslator()
        val first = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val second = Nat64Prefix.from(InetAddress.getByName("2001:db8:64::").address, 96)!!
        translator.updateAll(listOf(second, first, second))

        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val translated = translator.translateAll(InetAddress.getByAddress(ipv4))

        assertEquals(2, translated.size)
        assertArrayEquals(second.synthesize(ipv4), translated[0].address)
        assertArrayEquals(first.synthesize(ipv4), translated[1].address)
        translator.updateScopeId(7)
        val scoped = translator.translate(InetAddress.getByName("fe80::1234"))
        assertTrue(scoped is java.net.Inet6Address)
        assertEquals(7, (scoped as java.net.Inet6Address).scopeId)
    }

    @Test
    fun translatorRetriesEveryPrefixForAlreadySynthesizedIpv6Destinations() {
        val translator = Nat64AddressTranslator()
        val first = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 96)!!
        val second = Nat64Prefix.from(InetAddress.getByName("2001:4860:65::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        translator.updateAll(listOf(first, second))

        val translated = translator.translateAll(InetAddress.getByAddress(first.synthesize(ipv4)))

        assertEquals(2, translated.size)
        assertArrayEquals(first.synthesize(ipv4), translated[0].address)
        assertArrayEquals(second.synthesize(ipv4), translated[1].address)
    }

    @Test
    fun translatorUsesTheLongestMatchingPrefixWhenPrefixesOverlap() {
        val shorter = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 64)!!
        val longer = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

        listOf(listOf(shorter, longer), listOf(longer, shorter)).forEach { prefixes ->
            val translator = Nat64AddressTranslator()
            translator.updateAll(prefixes)
            val translated = translator.translateAll(InetAddress.getByAddress(longer.synthesize(ipv4)))

            assertEquals(2, translated.size)
            assertArrayEquals(prefixes[0].synthesize(ipv4), translated[0].address)
            assertArrayEquals(prefixes[1].synthesize(ipv4), translated[1].address)
        }
    }

    @Test
    fun wellKnownPrefixRejectsAlreadySynthesizedPrivateIpv4() {
        val translator = Nat64AddressTranslator()
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        translator.update(wellKnown)
        val privateLiteral = InetAddress.getByAddress(
            wellKnown.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        )

        assertTrue(translator.translateAll(privateLiteral).isEmpty())
    }

    @Test
    fun inactiveTranslatorStillRejectsPrivateWellKnownPrefixLiterals() {
        val translator = Nat64AddressTranslator()
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val privateLiteral = InetAddress.getByAddress(
            wellKnown.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        )
        val publicLiteral = InetAddress.getByAddress(
            wellKnown.synthesize(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        )

        assertTrue(translator.translateAll(privateLiteral).isEmpty())
        assertSame(publicLiteral, translator.translateAll(publicLiteral).single())
    }

    @Test
    fun privateWellKnownPrefixLiteralIsNotRewrittenThroughAnotherPrefix() {
        val translator = Nat64AddressTranslator()
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val networkSpecific = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 96)!!
        translator.updateAll(listOf(wellKnown, networkSpecific))
        val privateLiteral = InetAddress.getByAddress(
            wellKnown.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        )

        assertTrue(translator.translateAll(privateLiteral).isEmpty())
    }

    @Test
    fun retiredPrefixesRemainBlockedUntilReactivatedAndAreBounded() {
        val prefixes = (0 until 35).map { index ->
            Nat64Prefix.from(
                InetAddress.getByName("2001:db8:${index.toString(16)}::").address,
                48
            )!!
        }
        val retired = Nat64RetiredPrefixes(maximumSize = 32)
        retired.transition(prefixes, emptyList())

        assertEquals(32, retired.current().size)
        assertTrue(retired.matches(prefixes.first().synthesize(byteArrayOf(8, 8, 8, 8))))
        assertTrue(!retired.matches(prefixes.last().synthesize(byteArrayOf(8, 8, 8, 8))))

        retired.transition(emptyList(), listOf(prefixes.first()))

        assertTrue(!retired.matches(prefixes.first().synthesize(byteArrayOf(8, 8, 8, 8))))
        assertTrue(prefixes.first() !in retired.current())
    }

    @Test
    fun activeOverlappingPrefixTakesPriorityOverARetiredPrefix() {
        val retiredPrefix = Nat64Prefix.from(InetAddress.getByName("2001:db8:122:344::").address, 64)!!
        val activePrefix = Nat64Prefix.from(InetAddress.getByName("2001:db8:122:344:c0:2::").address, 96)!!
        val retired = Nat64RetiredPrefixes()
        retired.transition(listOf(retiredPrefix), listOf(activePrefix))
        val fresh = activePrefix.synthesize(byteArrayOf(8, 8, 8, 8))

        assertTrue(retired.matches(fresh))
        assertTrue(!retired.matches(fresh, listOf(activePrefix)))
    }

    @Test
    fun implicitWellKnownPrefixIsNeverRetired() {
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val networkSpecific = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 96)!!
        val retired = Nat64RetiredPrefixes()

        retired.transition(listOf(wellKnown), listOf(networkSpecific))

        assertTrue(retired.current().isEmpty())
        assertTrue(!retired.matches(wellKnown.synthesize(byteArrayOf(8, 8, 8, 8))))
    }

    private fun embedsAtRfc6052Position(address: ByteArray, length: Int, ipv4: ByteArray): Boolean {
        if (length == 96) {
            return ipv4.indices.all { index -> address[12 + index] == ipv4[index] }
        }
        val prefixBytes = length / 8
        val beforeReservedOctet = (64 - length) / 8
        return ipv4.indices.all { index ->
            val addressIndex = if (index < beforeReservedOctet) {
                prefixBytes + index
            } else {
                9 + index - beforeReservedOctet
            }
            address[addressIndex] == ipv4[index]
        }
    }

    @Test
    fun discoveryCacheKeepsLastPrefixOnlyUntilItsTtlExpires() {
        var now = 10_000L
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val cache = Nat64DiscoveryCache(clock = { now })

        val accepted = cache.accept(listOf(prefix), ttlSeconds = 30L, refreshAdvanceSeconds = 10L)
        assertEquals(20_000L, accepted.nextDelayMs)
        now += 20_000L
        val failedRefresh = cache.reject(retryDelayMs = 5_000L)
        assertEquals(listOf(prefix), failedRefresh.prefixes)
        assertEquals(5_000L, failedRefresh.nextDelayMs)
        now += 10_000L
        val expired = cache.expire()
        assertTrue(expired.changed)
        assertTrue(expired.prefixes.isEmpty())
    }

    @Test
    fun discoveryCacheReplacesAndResetsStateDeterministically() {
        var now = 0L
        val first = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val second = Nat64Prefix.from(InetAddress.getByName("2001:470:64::").address, 96)!!
        val cache = Nat64DiscoveryCache(clock = { now })

        cache.accept(listOf(first), 60L, 10L)
        now = 5_000L
        val replaced = cache.accept(listOf(second, first), 40L, 10L)

        assertEquals(listOf(second, first), replaced.prefixes)
        assertTrue(replaced.changed)
        assertEquals(30_000L, replaced.nextDelayMs)
        assertTrue(cache.reset().changed)
        assertTrue(cache.current().isEmpty())
    }

    @Test
    fun zeroTtlDiscoveryIsNeverReusable() {
        var now = 0L
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val cache = Nat64DiscoveryCache(clock = { now })

        val result = cache.accept(listOf(prefix), 0L, 0L)

        assertTrue(result.prefixes.isEmpty())
        assertTrue(cache.current().isEmpty())
        assertNull(cache.remainingTtlSeconds())
        assertEquals(1_000L, result.nextDelayMs)
        assertTrue(!cache.shouldRetry())
        now = 1_000L
        assertTrue(cache.shouldRetry())
    }

    @Test
    fun rejectedDiscoveryHonorsItsRetryDeadline() {
        var now = 5_000L
        val cache = Nat64DiscoveryCache(clock = { now })

        assertTrue(cache.shouldRetry())
        cache.reject(4_000L)
        assertTrue(!cache.shouldRetry())
        now = 8_999L
        assertTrue(!cache.shouldRetry())
        now = 9_000L
        assertTrue(cache.shouldRetry())
    }

    @Test
    fun currentSnapshotExpiresWithoutAnExplicitSweep() {
        var now = 0L
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val cache = Nat64DiscoveryCache(clock = { now })
        cache.accept(listOf(prefix), 1L, 0L)

        now = 1_001L

        assertTrue(cache.current().isEmpty())
        assertEquals(0L, cache.remainingTtlSeconds())
        assertTrue(cache.shouldRetry())
    }

}
