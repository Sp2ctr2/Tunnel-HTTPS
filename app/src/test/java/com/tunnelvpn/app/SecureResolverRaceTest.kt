package com.tunnelvpn.app

import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureResolverRaceTest {
    @Test
    fun everySecureRaceProviderHasDualStackBootstrapAddresses() {
        SecureResolverRace.PROVIDERS.forEach { provider ->
            assertTrue(provider.dohProvider.bootstrapAddresses.any { it.address.size == 4 })
            assertTrue(provider.dohProvider.bootstrapAddresses.any { it.address.size == 16 })
        }
    }

    @Test
    fun hedgeDelayUsesHalfOfTheObservedLatencySpread() {
        assertEquals(100L, calculateHedgeDelayMs(p50Ms = 100L, p95Ms = 300L))
        assertEquals(40L, calculateHedgeDelayMs(p50Ms = 100L, p95Ms = 120L))
        assertEquals(350L, calculateHedgeDelayMs(p50Ms = 10L, p95Ms = 1_000L))
    }

    @Test
    fun differentPublicAddressAnswersMateriallyDisagree() {
        val race = SecureResolverRace(DnsCache(), FakeTransport())
        val first = candidateWithAddresses(setOf("public:01010101"))
        val second = candidateWithAddresses(setOf("public:02020202"))

        assertTrue(race.materiallyDisagrees(listOf(first, second)))
        assertFalse(race.materiallyDisagrees(listOf(first, first.copy())))
    }

    @Test
    fun emptyAndPopulatedPositiveAnswersMateriallyDisagree() {
        val race = SecureResolverRace(DnsCache(), FakeTransport())
        val empty = candidateWithAddresses(emptySet())
        val populated = candidateWithAddresses(setOf("public:01010101"))

        assertTrue(race.materiallyDisagrees(listOf(empty, populated)))
    }

    @Test
    fun primarySecurityResolverWinsWithoutUnconditionalFanout() = runBlocking {
        val transport = FakeTransport().apply {
            answer(SecureResolverId.QUAD9_SECURE, DnsSecurityMeaning.ACCEPTABLE, 3)
        }
        val race = SecureResolverRace(DnsCache(), transport)

        val result = race.resolve(query(), question())

        assertEquals("quad9_secure", result.provider)
        assertEquals(listOf(SecureResolverId.QUAD9_SECURE), transport.calls)
    }

    @Test
    fun slowPrimaryTriggersOneSecondaryHedgeAndCancelsLoser() = runBlocking {
        val transport = FakeTransport().apply {
            answer(SecureResolverId.QUAD9_SECURE, DnsSecurityMeaning.ACCEPTABLE, 150)
            answer(SecureResolverId.CLOUDFLARE_SECURITY, DnsSecurityMeaning.ACCEPTABLE, 1)
        }
        val race = SecureResolverRace(DnsCache(), transport)

        val result = race.resolve(query(), question())

        assertEquals("cloudflare_security", result.provider)
        assertTrue(transport.calls.containsAll(listOf(SecureResolverId.QUAD9_SECURE, SecureResolverId.CLOUDFLARE_SECURITY)))
        assertFalse(transport.calls.contains(SecureResolverId.GOOGLE_DIVERSITY))
        assertEquals(1L, race.diagnostics().getValue("hedges"))
    }

    @Test
    fun loneSecurityBlockIsOverriddenByTwoIndependentPositiveAnswers() = runBlocking {
        val transport = FakeTransport().apply {
            answer(SecureResolverId.QUAD9_SECURE, DnsSecurityMeaning.BLOCKED, 1)
            answer(SecureResolverId.CLOUDFLARE_SECURITY, DnsSecurityMeaning.ACCEPTABLE, 1)
            answer(SecureResolverId.GOOGLE_DIVERSITY, DnsSecurityMeaning.ACCEPTABLE, 1)
        }
        val race = SecureResolverRace(DnsCache(), transport)

        val result = race.resolve(query(), question())

        assertEquals("cloudflare_security", result.provider)
        assertTrue(transport.calls.contains(SecureResolverId.GOOGLE_DIVERSITY))
        assertEquals(0L, race.diagnostics().getValue("explicitBlocks"))
        assertEquals(1L, race.diagnostics().getValue("disagreements"))
    }

    @Test
    fun twoIndependentPolicyBlocksStillWinTheConsensus() = runBlocking {
        val transport = FakeTransport().apply {
            answer(SecureResolverId.QUAD9_SECURE, DnsSecurityMeaning.BLOCKED, 1)
            answer(SecureResolverId.CLOUDFLARE_SECURITY, DnsSecurityMeaning.CENSORED, 1)
            answer(SecureResolverId.GOOGLE_DIVERSITY, DnsSecurityMeaning.ACCEPTABLE, 1)
        }
        val race = SecureResolverRace(DnsCache(), transport, riskProvider = { RiskLevel.HIGH })

        val result = race.resolve(query(), question())

        assertEquals("quad9_secure", result.provider)
        assertFalse(transport.calls.contains(SecureResolverId.GOOGLE_DIVERSITY))
        assertEquals(1L, race.diagnostics().getValue("explicitBlocks"))
    }

    @Test
    fun diversityBlockConfirmsOneSecurityBlockAgainstOnePositiveAnswer() = runBlocking {
        val transport = FakeTransport().apply {
            answer(SecureResolverId.QUAD9_SECURE, DnsSecurityMeaning.BLOCKED, 1)
            answer(SecureResolverId.CLOUDFLARE_SECURITY, DnsSecurityMeaning.ACCEPTABLE, 2)
            answer(SecureResolverId.GOOGLE_DIVERSITY, DnsSecurityMeaning.FILTERED, 3)
        }
        val race = SecureResolverRace(DnsCache(), transport, riskProvider = { RiskLevel.HIGH })

        val result = race.resolve(query(), question())

        assertEquals("quad9_secure", result.provider)
        assertTrue(transport.calls.contains(SecureResolverId.GOOGLE_DIVERSITY))
        assertEquals(1L, race.diagnostics().getValue("explicitBlocks"))
        assertEquals(1L, race.diagnostics().getValue("disagreements"))
    }

    @Test
    fun matchingPositiveMajorityWinsOverAFasterDissentingAddressAnswer() = runBlocking {
        val transport = FakeTransport().apply {
            answer(
                SecureResolverId.QUAD9_SECURE,
                DnsSecurityMeaning.ACCEPTABLE,
                1,
                setOf("public:01010101")
            )
            answer(
                SecureResolverId.CLOUDFLARE_SECURITY,
                DnsSecurityMeaning.ACCEPTABLE,
                2,
                setOf("public:02020202")
            )
            answer(
                SecureResolverId.GOOGLE_DIVERSITY,
                DnsSecurityMeaning.ACCEPTABLE,
                3,
                setOf("public:02020202")
            )
        }
        val race = SecureResolverRace(DnsCache(), transport, riskProvider = { RiskLevel.HIGH })

        val result = race.resolve(query(), question())

        assertEquals("cloudflare_security", result.provider)
        assertEquals(1L, race.diagnostics().getValue("tertiary"))
        assertEquals(1L, race.diagnostics().getValue("disagreements"))
    }

    @Test
    fun diversityProviderBreaksATieBetweenThreeDifferentPositiveAddressSets() = runBlocking {
        val transport = FakeTransport().apply {
            answer(SecureResolverId.QUAD9_SECURE, DnsSecurityMeaning.ACCEPTABLE, 1, setOf("public:01010101"))
            answer(SecureResolverId.CLOUDFLARE_SECURITY, DnsSecurityMeaning.ACCEPTABLE, 2, setOf("public:02020202"))
            answer(SecureResolverId.GOOGLE_DIVERSITY, DnsSecurityMeaning.ACCEPTABLE, 3, setOf("public:03030303"))
        }
        val race = SecureResolverRace(DnsCache(), transport, riskProvider = { RiskLevel.HIGH })

        val result = race.resolve(query(), question())

        assertEquals("google_diversity", result.provider)
        assertEquals(1L, race.diagnostics().getValue("tertiary"))
        assertEquals(1L, race.diagnostics().getValue("disagreements"))
    }

    @Test
    fun tertiaryIsUsedOnlyAfterBothSecurityProvidersFail() = runBlocking {
        val transport = FakeTransport().apply {
            fail(SecureResolverId.QUAD9_SECURE)
            fail(SecureResolverId.CLOUDFLARE_SECURITY)
            answer(SecureResolverId.GOOGLE_DIVERSITY, DnsSecurityMeaning.ACCEPTABLE, 1)
        }
        val race = SecureResolverRace(DnsCache(), transport, riskProvider = { RiskLevel.ELEVATED })

        val result = race.resolve(query(), question())

        assertEquals("google_diversity", result.provider)
        assertEquals(1L, race.diagnostics().getValue("tertiary"))
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun unsafeTertiaryAnswerIsNeverSelectedOrCached() {
        listOf(DnsSecurityMeaning.FORGED, DnsSecurityMeaning.REBINDING).forEach { meaning ->
            val transport = FakeTransport().apply {
                fail(SecureResolverId.QUAD9_SECURE)
                fail(SecureResolverId.CLOUDFLARE_SECURITY)
                answer(SecureResolverId.GOOGLE_DIVERSITY, meaning, 1)
            }
            val race = SecureResolverRace(DnsCache(), transport, riskProvider = { RiskLevel.ELEVATED })

            assertThrows(IOException::class.java) {
                runBlocking { race.resolve(query(), question()) }
            }
            assertEquals(3, transport.calls.size)
        }
    }

    @Test
    fun oldGenerationResponseCannotPopulateCache() {
        val transport = FakeTransport().apply {
            answer(SecureResolverId.QUAD9_SECURE, DnsSecurityMeaning.ACCEPTABLE, 100)
        }
        val race = SecureResolverRace(DnsCache(), transport)

        assertThrows(IOException::class.java) {
            runBlocking {
                val worker = async { race.resolve(query(), question()) }
                delay(10)
                race.onNetworkChanged()
                worker.await()
            }
        }
        assertTrue(race.healthSnapshots().all { it.failures == 0L && !it.cooldown })
    }

    @Test
    fun oldGenerationFailuresCannotPoisonNewNetworkHealth() {
        val transport = FakeTransport().apply {
            fail(SecureResolverId.QUAD9_SECURE, 100)
            fail(SecureResolverId.CLOUDFLARE_SECURITY, 100)
        }
        val race = SecureResolverRace(DnsCache(), transport, riskProvider = { RiskLevel.HIGH })

        assertThrows(IOException::class.java) {
            runBlocking {
                val worker = async { race.resolve(query(), question()) }
                delay(10)
                race.onNetworkChanged()
                worker.await()
            }
        }
        assertTrue(race.healthSnapshots().all { it.failures == 0L && !it.cooldown })
    }

    @Test
    fun localDiscoveryIsNeverSentToPublicResolvers() {
        val transport = FakeTransport()
        val race = SecureResolverRace(DnsCache(), transport)
        val localQuery = DnsPacket.query("printer.local", DnsPacket.TYPE_A)!!

        assertThrows(IOException::class.java) {
            runBlocking { race.resolve(localQuery, DnsPacket.parseQuestion(localQuery)!!) }
        }
        assertTrue(transport.calls.isEmpty())
    }

    private fun query(): ByteArray = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!

    private fun question(): DnsPacket.Question = DnsPacket.parseQuestion(query())!!

    private fun candidateWithAddresses(addresses: Set<String>): ResolverCandidate {
        return ResolverCandidate(
            SecureResolverProvider(
                SecureResolverId.QUAD9_SECURE,
                DohProvider("test", "https://example.com/dns-query"),
                threatBlocking = true
            ),
            ValidatedDnsMessage(
                bytes = DnsPacket.noDataResponse(query(), query().size),
                rcode = 0,
                authenticatedData = false,
                meaning = DnsSecurityMeaning.ACCEPTABLE,
                minimumTtlSeconds = 30,
                addressFingerprints = addresses,
                cnameDepth = 0
            ),
            elapsedMs = 1
        )
    }

    private class FakeTransport : SecureDnsTransport {
        private data class Behavior(
            val meaning: DnsSecurityMeaning?,
            val delayMs: Long,
            val addresses: Set<String> = emptySet()
        )

        val calls: MutableList<SecureResolverId> = Collections.synchronizedList(mutableListOf())
        private val behavior = mutableMapOf<SecureResolverId, Behavior>()

        fun answer(
            id: SecureResolverId,
            meaning: DnsSecurityMeaning,
            delayMs: Long,
            addresses: Set<String> = if (meaning == DnsSecurityMeaning.ACCEPTABLE) {
                setOf("public:01010101")
            } else {
                emptySet()
            }
        ) {
            behavior[id] = Behavior(meaning, delayMs, addresses)
        }

        fun fail(id: SecureResolverId, delayMs: Long = 1) {
            behavior[id] = Behavior(null, delayMs)
        }

        override suspend fun resolve(
            provider: SecureResolverProvider,
            query: ByteArray,
            question: DnsPacket.Question
        ): ResolverCandidate {
            calls += provider.id
            val selected = behavior[provider.id] ?: Behavior(null, 1)
            delay(selected.delayMs)
            val meaning = selected.meaning ?: throw IOException("transport-failure")
            return ResolverCandidate(
                provider,
                ValidatedDnsMessage(
                    DnsPacket.noDataResponse(query, query.size),
                    if (meaning == DnsSecurityMeaning.NXDOMAIN) 3 else 0,
                    true,
                    meaning,
                    30,
                    selected.addresses,
                    0
                ),
                selected.delayMs
            )
        }
    }
}
