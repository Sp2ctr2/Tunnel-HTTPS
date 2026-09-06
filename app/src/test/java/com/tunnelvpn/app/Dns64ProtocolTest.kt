package com.tunnelvpn.app

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Dns64ProtocolTest {
    @Test
    fun rfc7050ExtractionFollowsCnameAndPreservesMultiplePrefixOrder() {
        val query = DnsPacket.query("ipv4only.arpa", DnsPacket.TYPE_AAAA, 0x7050)!!
        val alias = name("nat64.example")
        val unrelated = name("unrelated.example")
        val firstPrefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val secondPrefix = Nat64Prefix.from(InetAddress.getByName("2001:470:64::").address, 96)!!
        val wka = byteArrayOf(192.toByte(), 0, 0, 170.toByte())
        val response = response(
            query,
            answers = listOf(
                record(pointerToQuestion(), TYPE_CNAME, 300, alias),
                record(unrelated, DnsPacket.TYPE_AAAA, 300, firstPrefix.synthesize(wka)),
                record(alias, DnsPacket.TYPE_AAAA, 300, secondPrefix.synthesize(wka)),
                record(alias, DnsPacket.TYPE_AAAA, 300, firstPrefix.synthesize(wka))
            )
        )

        val addresses = DnsPacket.extractAaaaRecords(response)

        assertEquals(2, addresses.size)
        assertEquals(listOf(secondPrefix, firstPrefix), Nat64Prefix.discoverAll(addresses))
    }

    @Test
    fun dns64SynthesizesEveryAWithRfcTtlCnameAndPrefixOrdering() {
        val query = DnsPacket.query("www.example", DnsPacket.TYPE_AAAA, 0x6147)!!
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val alias = name("edge.example")
        val negative = response(
            query,
            authorities = listOf(record(name("example"), TYPE_SOA, 90, soaRdata()))
        )
        val firstAddress = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val secondAddress = byteArrayOf(8, 8, 8, 8)
        val aResponse = response(
            aQuery,
            flags = 0x81a0,
            answers = listOf(
                record(pointerToQuestion(), TYPE_CNAME, 45, alias),
                record(alias, DnsPacket.TYPE_A, 120, firstAddress),
                record(alias, DnsPacket.TYPE_A, 700, secondAddress)
            ),
            additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0, ByteArray(0), 1232))
        )
        val firstPrefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val secondPrefix = Nat64Prefix.from(InetAddress.getByName("2001:470:64::").address, 96)!!

        val synthesized = Dns64Packet.synthesize(
            query,
            negative,
            aResponse,
            listOf(firstPrefix, secondPrefix),
            authenticated = false
        )

        assertNotNull(synthesized)
        synthesized!!
        assertEquals(5, u16(synthesized, 6))
        assertEquals(0, u16(synthesized, 10))
        assertFalse(u16(synthesized, 2) and 0x20 != 0)
        assertEquals(listOf(90L, 90L, 90L, 90L), answerTtls(synthesized, DnsPacket.TYPE_AAAA))
        val expected = listOf(
            firstPrefix.synthesize(firstAddress),
            secondPrefix.synthesize(firstAddress),
            firstPrefix.synthesize(secondAddress),
            secondPrefix.synthesize(secondAddress)
        )
        val actual = DnsPacket.extractAaaaRecords(synthesized)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) -> assertArrayEquals(left, right) }
        val validated = DnsMessageValidator.validate(query, synthesized)
        assertEquals(1, validated.cnameDepth)
    }

    @Test
    fun synthesisUsesOnlyTheOriginalQueryForFreshEdnsState() {
        val query = withDnssecOk(
            DnsPacket.query("www.example", DnsPacket.TYPE_AAAA, 0x6148)!!,
            cd = false
        )
        val questionEnd = DnsPacket.parseQuestion(query)!!.questionEnd
        put32(query, questionEnd + 5, 0)
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val addressRecord = record(
            pointerToQuestion(),
            DnsPacket.TYPE_A,
            120,
            byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        )
        val responses = listOf(
            response(
                aQuery,
                answers = listOf(addressRecord),
                additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0, ByteArray(0), 4096))
            ),
            response(aQuery, answers = listOf(addressRecord))
        )

        responses.forEach { aResponse ->
            val result = Dns64Packet.synthesize(
                query,
                DnsPacket.noDataResponse(query, query.size),
                aResponse,
                listOf(prefix),
                authenticated = false
            )!!
            assertEquals(1, u16(result, 10))
            val optOffset = result.size - 11
            assertEquals(TYPE_OPT, u16(result, optOffset + 1))
            assertEquals(1232, u16(result, optOffset + 3))
            assertEquals(0L, u32(result, optOffset + 5))
        }
    }

    @Test
    fun negativeTtlUsesTheLowerOfSoaTtlAndMinimum() {
        val query = DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x2308)!!
        val response = response(
            query,
            authorities = listOf(record(name("example"), TYPE_SOA, 900, soaRdata(minimum = 37)))
        )

        assertEquals(37L, Dns64Packet.negativeSoaTtl(response))
    }

    @Test
    fun cachedNegativeTtlCannotBeExtendedByLaterDns64Synthesis() {
        var now = 1_000L
        val query = DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x2309)!!
        val response = response(
            query,
            authorities = listOf(record(name("example"), TYPE_SOA, 900, soaRdata(minimum = 37)))
        )
        val cache = DnsCache(clock = { now })
        cache.putSuccess(DnsPacket.parseQuestion(query)!!.key, response, 37_000L)

        now += 30_000L
        val cached = cache.get(DnsPacket.parseQuestion(query)!!.key, 0x2310)!!

        assertEquals(7L, Dns64Packet.negativeSoaTtl(cached))
    }

    @Test
    fun syntheticAaaaTtlIsCappedByRemainingPref64Lifetime() {
        val query = DnsPacket.query("www.example", DnsPacket.TYPE_AAAA, 0x2311)!!
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val aResponse = response(
            aQuery,
            answers = listOf(
                record(pointerToQuestion(), DnsPacket.TYPE_A, 120, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
            )
        )

        val synthesized = Dns64Packet.synthesize(
            query,
            DnsPacket.noDataResponse(query, query.size),
            aResponse,
            listOf(prefix),
            authenticated = false,
            maximumSyntheticTtl = 7
        )!!

        assertEquals(listOf(7L), answerTtls(synthesized, DnsPacket.TYPE_AAAA))
    }

    @Test
    fun discoveryPendingFailsClosedButConfirmedAbsencePreservesNodata() = runBlocking {
        val query = DnsPacket.query("native-v6-only.example", DnsPacket.TYPE_AAAA, 0x2312)!!
        val nodata = DnsPacket.noDataResponse(query, query.size)

        val pending = DnsProtectionEngine(
            resolver = fixedResolver(nodata),
            diagnostics = DiagnosticsState(),
            nat64DiscoveryRequiredProvider = { true }
        ).handleTcpMessage(query)!!
        val confirmedAbsent = DnsProtectionEngine(
            resolver = fixedResolver(nodata),
            diagnostics = DiagnosticsState(),
            nat64DiscoveryRequiredProvider = { false }
        ).handleTcpMessage(query)!!

        assertEquals(2, Dns64Packet.responseCode(pending))
        assertArrayEquals(nodata, confirmedAbsent)
    }

    @Test
    fun expiredPrefixCannotLeakNodataWhileRediscoveryIsPending() = runBlocking {
        val query = DnsPacket.query("expired-prefix.example", DnsPacket.TYPE_AAAA, 0x2313)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val result = DnsProtectionEngine(
            resolver = fixedResolver(DnsPacket.noDataResponse(query, query.size)),
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) },
            nat64RemainingTtlSecondsProvider = { 0L },
            nat64DiscoveryRequiredProvider = { true }
        ).handleTcpMessage(query)!!

        assertEquals(2, Dns64Packet.responseCode(result))
    }

    @Test
    fun prefixExpiryDuringAFallbackFailsClosed() = runBlocking {
        val query = DnsPacket.query("expires-during-a.example", DnsPacket.TYPE_AAAA, 0x231a)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        var remaining = 5L
        var pending = false
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                val response = if (question.type == DnsPacket.TYPE_AAAA) {
                    DnsPacket.noDataResponse(query, query.size)
                } else {
                    remaining = 0L
                    pending = true
                    DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 8, 8))
                }
                return DnsResolveResult(response, "test", 0L, false)
            }
        }
        val result = DnsProtectionEngine(
            resolver = resolver,
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) },
            nat64RemainingTtlSecondsProvider = { remaining },
            nat64DiscoveryRequiredProvider = { pending }
        ).handleTcpMessage(query)!!

        assertEquals(2, Dns64Packet.responseCode(result))
    }

    @Test
    fun engineSynthesizesThroughABareDnameChain() = runBlocking {
        val query = DnsPacket.query("www.old.example", DnsPacket.TYPE_AAAA, 0x2314)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                val answer = if (question.type == DnsPacket.TYPE_AAAA) {
                    DnsPacket.noDataResponse(query, query.size)
                } else {
                    response(
                        query,
                        answers = listOf(
                            record(name("old.example"), TYPE_DNAME, 120, name("new.example")),
                            record(name("www.new.example"), DnsPacket.TYPE_A, 120, ipv4)
                        )
                    )
                }
                return DnsResolveResult(answer, "test", 0L, false)
            }
        }
        val result = DnsProtectionEngine(
            resolver = resolver,
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) }
        ).handleTcpMessage(query)!!

        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(result).single())
    }

    @Test
    fun nativeAaaaBehindBareDnamePreventsDns64Synthesis() = runBlocking {
        val query = DnsPacket.query("www.old.example", DnsPacket.TYPE_AAAA, 0x2318)!!
        val native = InetAddress.getByName("2606:4700:4700::1111").address
        var calls = 0
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                calls += 1
                val answer = response(
                    query,
                    answers = listOf(
                        record(name("old.example"), TYPE_DNAME, 120, name("new.example")),
                        record(name("www.new.example"), DnsPacket.TYPE_AAAA, 120, native)
                    )
                )
                return DnsResolveResult(answer, "test", 0L, false)
            }
        }
        val result = DnsProtectionEngine(
            resolver = resolver,
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = {
                Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)
            }
        ).handleTcpMessage(query)!!

        assertEquals(1, calls)
        assertArrayEquals(native, DnsPacket.extractAaaaRecords(result).single())
    }

    @Test
    fun bareDnameToRootUsesTheCanonicalTerminalOwner() {
        val query = DnsPacket.query("www.old.example", DnsPacket.TYPE_AAAA, 0x2320)!!
        val native = InetAddress.getByName("2606:4700:4700::1001").address
        val answer = response(
            query,
            answers = listOf(
                record(name("old.example"), TYPE_DNAME, 120, byteArrayOf(0)),
                record(name("www"), DnsPacket.TYPE_AAAA, 120, native)
            )
        )

        assertArrayEquals(native, DnsPacket.extractAaaaRecords(answer).single())
    }

    @Test
    fun bareDnameCannotHidePrivateIpv4InsideAnActiveNat64Prefix() = runBlocking {
        val query = DnsPacket.query("www.old.example", DnsPacket.TYPE_AAAA, 0x2319)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("2001:470:64::").address, 96)!!
        val rebinding = prefix.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        val answer = response(
            query,
            answers = listOf(
                record(name("old.example"), TYPE_DNAME, 120, name("new.example")),
                record(name("www.new.example"), DnsPacket.TYPE_AAAA, 120, rebinding)
            )
        )
        val result = DnsProtectionEngine(
            resolver = fixedResolver(answer),
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) }
        ).handleTcpMessage(query)!!

        assertEquals(2, Dns64Packet.responseCode(result))
    }

    @Test
    fun wellKnownPrefixPrivateEmbeddingIsBlockedWithoutAnActiveWkp() = runBlocking {
        val query = DnsPacket.query("attacker.example", DnsPacket.TYPE_AAAA, 0x2321)!!
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val privateAddress = wellKnown.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        val unrelated = Nat64Prefix.from(InetAddress.getByName("2001:470:64::").address, 96)!!

        listOf(emptyList(), listOf(unrelated)).forEach { activePrefixes ->
            val result = DnsProtectionEngine(
                resolver = fixedResolver(DnsPacket.aaaaRecordResponse(query, query.size, privateAddress)),
                diagnostics = DiagnosticsState(),
                nat64PrefixesProvider = { activePrefixes }
            ).handleTcpMessage(query)!!
            assertEquals(2, Dns64Packet.responseCode(result))
        }
    }

    @Test
    fun headerOnlyASideErrorIsRetargetedToTheAaaaQuestion() = runBlocking {
        val query = withDnssecOk(
            DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x2315)!!,
            cd = false
        )
        put32(query, DnsPacket.parseQuestion(query)!!.questionEnd + 5, 0)
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                val answer = if (question.type == DnsPacket.TYPE_AAAA) {
                    DnsPacket.noDataResponse(query, query.size)
                } else {
                    query.copyOfRange(0, 12).also {
                        it[2] = 0x81.toByte()
                        it[3] = 0x83.toByte()
                        it.fill(0, 4, 12)
                    }
                }
                return DnsResolveResult(answer, "test", 0L, false)
            }
        }
        val result = DnsProtectionEngine(
            resolver = resolver,
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) }
        ).handleTcpMessage(query)!!

        assertEquals(3, Dns64Packet.responseCode(result))
        val questionEnd = DnsPacket.parseQuestion(query)!!.questionEnd
        assertEquals(DnsPacket.TYPE_AAAA, u16(result, questionEnd - 4))
        assertEquals(1, u16(result, 10))
        assertEquals(TYPE_OPT, u16(result, questionEnd + 1))
        assertEquals(1232, u16(result, questionEnd + 3))
        assertEquals(0L, u32(result, questionEnd + 5))
    }

    @Test
    fun headerOnlyRetargetRestoresFreshDnssecOkOpt() {
        val query = withDnssecOk(
            DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x2317)!!,
            cd = false
        )
        val error = query.copyOfRange(0, 12).also {
            it[2] = 0x81.toByte()
            it[3] = 0x83.toByte()
            it.fill(0, 4, 12)
        }

        val result = Dns64Packet.retargetResponse(query, error)!!
        val questionEnd = DnsPacket.parseQuestion(query)!!.questionEnd

        assertEquals(1, u16(result, 10))
        assertEquals(TYPE_OPT, u16(result, questionEnd + 1))
        assertEquals(1232, u16(result, questionEnd + 3))
        assertEquals(0x8000L, u32(result, questionEnd + 5))
    }

    @Test
    fun parsedRetargetPreservesExtendedRcodeInFreshOpt() {
        val query = withDnssecOk(
            DnsPacket.query("badvers.example", DnsPacket.TYPE_AAAA, 0x231c)!!,
            cd = false
        )
        put32(query, DnsPacket.parseQuestion(query)!!.questionEnd + 5, 0)
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val badvers = response(
            aQuery,
            additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0x01000000, ByteArray(0), 4096))
        )

        val result = Dns64Packet.retargetResponse(query, badvers)!!
        val questionEnd = DnsPacket.parseQuestion(query)!!.questionEnd

        assertEquals(16, Dns64Packet.responseCode(result))
        assertEquals(1232, u16(result, questionEnd + 3))
    }

    @Test
    fun headerOnlyAaaaNxdomainIsCorrelatedWithoutLosingItsRcode() = runBlocking {
        val query = DnsPacket.query("absent.example", DnsPacket.TYPE_AAAA, 0x2316)!!
        val headerOnlyNxdomain = query.copyOfRange(0, 12).also {
            it[2] = 0x81.toByte()
            it[3] = 0x83.toByte()
            it.fill(0, 4, 12)
        }
        val result = DnsProtectionEngine(
            resolver = fixedResolver(headerOnlyNxdomain),
            diagnostics = DiagnosticsState()
        ).handleTcpMessage(query)!!

        assertEquals(3, Dns64Packet.responseCode(result))
        assertTrue(Dns64Packet.hasQuestion(result))
    }

    @Test
    fun headerOnlyAaaaNxdomainCannotCrossANetworkEpoch() {
        val query = DnsPacket.query("stale-absent.example", DnsPacket.TYPE_AAAA, 0x231b)!!
        val generation = AtomicLong(2L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>()
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    val answer = query.copyOfRange(0, 12).also {
                        it[2] = 0x81.toByte()
                        it[3] = 0x83.toByte()
                        it.fill(0, 4, 12)
                    }
                    return DnsResolveResult(answer, "test", 0L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            networkGenerationProvider = generation::get
        )
        val worker = thread { result.set(runBlocking { engine.handleTcpMessage(query) }) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        generation.addAndGet(2L)
        release.countDown()
        worker.join(2_000L)

        assertEquals(2, Dns64Packet.responseCode(result.get()!!))
    }

    @Test
    fun dns64FollowsAndPreservesDnameAndSynthesizedCnameChain() {
        val query = DnsPacket.query("www.old.example", DnsPacket.TYPE_AAAA, 0x6672)!!
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val aResponse = response(
            aQuery,
            answers = listOf(
                record(name("old.example"), TYPE_DNAME, 120, name("new.example")),
                record(pointerToQuestion(), TYPE_CNAME, 120, name("www.new.example")),
                record(name("www.new.example"), DnsPacket.TYPE_A, 120, ipv4)
            )
        )

        val synthesized = Dns64Packet.synthesize(
            query,
            DnsPacket.noDataResponse(query, query.size),
            aResponse,
            listOf(prefix),
            authenticated = false
        )

        assertNotNull(synthesized)
        assertEquals(listOf(TYPE_DNAME, TYPE_CNAME, DnsPacket.TYPE_AAAA), answerTypes(synthesized!!))
        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(synthesized).single())
    }

    @Test
    fun dns64SynthesizesTheCnameRequiredByABareDnameAnswer() {
        val query = DnsPacket.query("www.old.example", DnsPacket.TYPE_AAAA, 0x6673)!!
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val aResponse = response(
            aQuery,
            answers = listOf(
                record(name("old.example"), TYPE_DNAME, 120, name("new.example")),
                record(name("www.new.example"), DnsPacket.TYPE_A, 120, ipv4)
            )
        )

        val synthesized = Dns64Packet.synthesize(
            query,
            DnsPacket.noDataResponse(query, query.size),
            aResponse,
            listOf(prefix),
            authenticated = false
        )

        assertNotNull(synthesized)
        assertEquals(listOf(TYPE_DNAME, TYPE_CNAME, DnsPacket.TYPE_AAAA), answerTypes(synthesized!!))
        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(synthesized).single())
        assertEquals(1, DnsMessageValidator.validate(query, synthesized).cnameDepth)
    }

    @Test
    fun privateIpv4UsesOnlyNetworkSpecificPrefixes() {
        val query = DnsPacket.query("private.example", DnsPacket.TYPE_AAAA, 0x6052)!!
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val networkSpecific = Nat64Prefix.from(InetAddress.getByName("2001:470:64::").address, 96)!!
        val privateAddress = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
        val publicAddress = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val negative = DnsPacket.noDataResponse(query, query.size)
        val privateResponse = response(
            aQuery,
            answers = listOf(record(pointerToQuestion(), DnsPacket.TYPE_A, 120, privateAddress))
        )

        assertNull(Dns64Packet.synthesize(query, negative, privateResponse, listOf(wellKnown), false))
        val privateSynthesized = Dns64Packet.synthesize(
            query,
            negative,
            privateResponse,
            listOf(wellKnown, networkSpecific),
            false
        )!!
        assertEquals(1, u16(privateSynthesized, 6))
        assertArrayEquals(
            networkSpecific.synthesize(privateAddress),
            DnsPacket.extractAaaaRecords(privateSynthesized).single()
        )

        val mixedResponse = response(
            aQuery,
            answers = listOf(
                record(pointerToQuestion(), DnsPacket.TYPE_A, 120, publicAddress),
                record(pointerToQuestion(), DnsPacket.TYPE_A, 120, privateAddress)
            )
        )
        val mixedSynthesized = Dns64Packet.synthesize(
            query,
            negative,
            mixedResponse,
            listOf(wellKnown, networkSpecific),
            false
        )!!
        val expected = listOf(
            wellKnown.synthesize(publicAddress),
            networkSpecific.synthesize(publicAddress),
            networkSpecific.synthesize(privateAddress)
        )
        val actual = DnsPacket.extractAaaaRecords(mixedSynthesized)
        assertEquals(3, u16(mixedSynthesized, 6))
        expected.zip(actual).forEach { (left, right) -> assertArrayEquals(left, right) }
    }

    @Test
    fun ipv4OnlyArpaWkaCanUseTheWellKnownPrefix() = runBlocking {
        val query = DnsPacket.query("ipv4only.arpa", DnsPacket.TYPE_AAAA, 0x7051)!!
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val wka = byteArrayOf(192.toByte(), 0, 0, 170.toByte())
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    val bytes = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.noDataResponse(query, query.size)
                    } else {
                        DnsPacket.aRecordResponse(query, query.size, wka)
                    }
                    return DnsResolveResult(bytes, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { wellKnown }
        )

        val result = engine.handleTcpMessage(query)!!

        assertArrayEquals(wellKnown.synthesize(wka), DnsPacket.extractAaaaRecords(result).single())
    }

    @Test
    fun publicAaaaCannotEmbedAPrivateIpv4TargetUnderAnActiveNsp() = runBlocking {
        val query = DnsPacket.query("attacker.example", DnsPacket.TYPE_AAAA, 0x6053)!!
        val prefixes = listOf(
            Nat64Prefix.from(InetAddress.getByName("2001:470:64::").address, 96)!!,
            Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        )
        prefixes.forEach { prefix ->
            val privateTarget = prefix.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
            val engine = DnsProtectionEngine(
                resolver = object : DnsResolver {
                    override suspend fun resolve(
                        query: ByteArray,
                        question: DnsPacket.Question
                    ): DnsResolveResult {
                        return DnsResolveResult(
                            DnsPacket.aaaaRecordResponse(query, query.size, privateTarget),
                            "test",
                            1L,
                            false
                        )
                    }
                },
                diagnostics = DiagnosticsState(),
                nat64PrefixesProvider = { listOf(prefix) }
            )

            val result = engine.handleTcpMessage(query)!!

            assertEquals(2, u16(result, 2) and 0x0f)
            assertTrue(DnsPacket.extractAaaaRecords(result).isEmpty())
        }
    }

    @Test
    fun nat64PtrQueriesMapEveryRfc6052LayoutToIpv4ReverseDns() = runBlocking {
        val cases = listOf(
            "2001:db8::" to 32,
            "2001:db8:100::" to 40,
            "2001:db8:122::" to 48,
            "2001:db8:122:300::" to 56,
            "2001:db8:122:344::" to 64,
            "2001:db8:122:344::" to 96
        )
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

        cases.forEach { (text, length) ->
            val prefix = Nat64Prefix.from(InetAddress.getByName(text).address, length)!!
            val reverseName = ip6Arpa(prefix.synthesize(ipv4))
            val query = DnsPacket.query(reverseName, DnsPacket.TYPE_PTR, 0x5300 + length)!!
            var resolverCalls = 0
            val engine = DnsProtectionEngine(
                resolver = object : DnsResolver {
                    override suspend fun resolve(
                        query: ByteArray,
                        question: DnsPacket.Question
                    ): DnsResolveResult {
                        resolverCalls += 1
                        return ptrResult(query)
                    }
                },
                diagnostics = DiagnosticsState(),
                nat64PrefixesProvider = { listOf(prefix) }
            )

            val result = engine.handleTcpMessage(query)!!

            assertEquals(1, resolverCalls)
            assertEquals(listOf(TYPE_CNAME, DnsPacket.TYPE_PTR), answerTypes(result))
            assertEquals("34.216.184.93.in-addr.arpa", firstAnswerNameRdata(result))
        }
    }

    @Test
    fun nat64PtrUsesLongestPrefixAndRejectsAnInProgressContext() = runBlocking {
        val short = Nat64Prefix.from(InetAddress.getByName("2001:db8::").address, 32)!!
        val long = Nat64Prefix.from(InetAddress.getByName("2001:db8::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val query = DnsPacket.query(ip6Arpa(long.synthesize(ipv4)), DnsPacket.TYPE_PTR, 0x5396)!!

        listOf(listOf(short, long), listOf(long, short)).forEach { prefixes ->
            val engine = DnsProtectionEngine(
                resolver = object : DnsResolver {
                    override suspend fun resolve(
                        query: ByteArray,
                        question: DnsPacket.Question
                    ): DnsResolveResult = ptrResult(query)
                },
                diagnostics = DiagnosticsState(),
                nat64PrefixesProvider = { prefixes }
            )
            assertEquals("34.216.184.93.in-addr.arpa", firstAnswerNameRdata(engine.handleTcpMessage(query)!!))
        }

        var resolverCalls = 0
        val updatingEngine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    resolverCalls += 1
                    return DnsResolveResult(DnsPacket.nxDomainResponse(query, query.size), "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(long) },
            networkGenerationProvider = { 1L }
        )
        val result = updatingEngine.handleTcpMessage(query)!!

        assertEquals(0, resolverCalls)
        assertEquals(2, u16(result, 2) and 0x0f)
    }

    @Test
    fun nat64PtrTtlCannotOutliveTheDiscoveredPrefix() = runBlocking {
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val query = DnsPacket.query(ip6Arpa(prefix.synthesize(ipv4)), DnsPacket.TYPE_PTR, 0x5397)!!
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult = ptrResult(query)
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) },
            nat64RemainingTtlSecondsProvider = { 7L }
        )

        val result = engine.handleTcpMessage(query)!!

        assertEquals(7L, firstAnswerTtl(result))
    }

    @Test
    fun ipv4MappedAaaaIsRemovedFromMixedNativeResponse() {
        val query = DnsPacket.query("mixed.example", DnsPacket.TYPE_AAAA, 0x6147)!!
        val mapped = ipv4Mapped(192, 0, 2, 1)
        val native = InetAddress.getByName("2001:4860:4860::8888").address
        val response = response(
            query,
            flags = 0x81a0,
            answers = listOf(
                record(pointerToQuestion(), DnsPacket.TYPE_AAAA, 60, mapped),
                record(pointerToQuestion(), DnsPacket.TYPE_AAAA, 60, native)
            )
        )

        val filtered = Dns64Packet.withoutIpv4MappedAaaa(query, response)

        assertNotNull(filtered)
        val addresses = DnsPacket.extractAaaaRecords(filtered!!)
        assertEquals(1, addresses.size)
        assertArrayEquals(native, addresses.single())
        assertFalse(u16(filtered, 2) and 0x20 != 0)
    }

    @Test
    fun mappedAaaaRewriteReplacesUpstreamOptWithFreshQueryOpt() {
        val query = withDnssecOk(
            DnsPacket.query("mixed-edns.example", DnsPacket.TYPE_AAAA, 0x6149)!!,
            cd = false
        )
        val questionEnd = DnsPacket.parseQuestion(query)!!.questionEnd
        put32(query, questionEnd + 5, 0)
        val native = InetAddress.getByName("2001:4860:4860::8888").address
        val answers = listOf(
            record(pointerToQuestion(), DnsPacket.TYPE_AAAA, 60, ipv4Mapped(192, 0, 2, 1)),
            record(pointerToQuestion(), DnsPacket.TYPE_AAAA, 60, native)
        )
        val upstream = response(
            query,
            answers = answers,
            additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0, byteArrayOf(0, 15, 0, 0), 4096))
        )
        val filtered = Dns64Packet.withoutIpv4MappedAaaa(query, upstream)!!
        val optOffset = filtered.size - 11

        assertEquals(1, u16(filtered, 10))
        assertEquals(TYPE_OPT, u16(filtered, optOffset + 1))
        assertEquals(1232, u16(filtered, optOffset + 3))
        assertEquals(0L, u32(filtered, optOffset + 5))
        assertEquals(0, u16(filtered, optOffset + 9))

        val plainQuery = DnsPacket.query("mixed-plain.example", DnsPacket.TYPE_AAAA, 0x614a)!!
        val plainUpstream = response(
            plainQuery,
            answers = answers,
            additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0, ByteArray(0), 4096))
        )
        assertEquals(0, u16(Dns64Packet.withoutIpv4MappedAaaa(plainQuery, plainUpstream)!!, 10))
    }

    @Test
    fun dnssecAwareMappedAaaaRewriteFailsClosed() {
        val query = withDnssecOk(DnsPacket.query("mapped.example", DnsPacket.TYPE_AAAA, 0x6415)!!, cd = true)
        val response = DnsPacket.aaaaRecordResponse(
            query,
            query.size,
            ipv4Mapped(192, 0, 2, 1)
        )

        assertNull(Dns64Packet.withoutIpv4MappedAaaa(query, response))
    }

    @Test
    fun mappedAaaaWithDnssecMaterialFailsClosedBeforeAQuery() = runBlocking {
        val query = DnsPacket.query("signed-mapped.example", DnsPacket.TYPE_AAAA, 0x6416)!!
        var calls = 0
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    calls += 1
                    val bytes = response(
                        query,
                        answers = listOf(
                            record(pointerToQuestion(), DnsPacket.TYPE_AAAA, 60, ipv4Mapped(192, 0, 2, 1)),
                            record(pointerToQuestion(), 46, 60, ByteArray(18))
                        )
                    )
                    return DnsResolveResult(bytes, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = {
                Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)
            }
        )

        val result = engine.handleTcpMessage(query)!!

        assertEquals(1, calls)
        assertEquals(2, u16(result, 2) and 0x0f)
        assertTrue(DnsPacket.extractAaaaRecords(result).isEmpty())
    }

    @Test
    fun mappedOnlyAaaaContinuesToDns64Synthesis() = runBlocking {
        val query = DnsPacket.query("mapped.example", DnsPacket.TYPE_AAAA, 0x6414)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    val response = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.aaaaRecordResponse(
                            query,
                            query.size,
                            ipv4Mapped(192, 0, 2, 1)
                        )
                    } else {
                        DnsPacket.aRecordResponse(query, query.size, ipv4)
                    }
                    return DnsResolveResult(response, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)

        assertNotNull(result)
        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(result!!).single())
    }

    @Test
    fun dns64RetargetsNegativeAResponseWithoutLosingAuthority() = runBlocking {
        val query = DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x6100)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    val bytes = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.noDataResponse(query, query.size)
                    } else {
                        response(
                            query,
                            flags = 0x8183,
                            authorities = listOf(record(name("example"), TYPE_SOA, 75, soaRdata()))
                        )
                    }
                    return DnsResolveResult(bytes, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)

        assertNotNull(result)
        result!!
        assertEquals(3, u16(result, 2) and 0x0f)
        assertEquals(1, u16(result, 8))
        assertEquals(DnsPacket.TYPE_AAAA, u16(result, DnsPacket.parseQuestion(query)!!.questionEnd - 4))
    }

    @Test
    fun nonNameErrorAaaaResponseStillAttemptsSynthesis() = runBlocking {
        val query = DnsPacket.query("legacy.example", DnsPacket.TYPE_AAAA, 0x6148)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    val bytes = if (question.type == DnsPacket.TYPE_AAAA) {
                        response(query, flags = 0x8182)
                    } else {
                        DnsPacket.aRecordResponse(query, query.size, ipv4)
                    }
                    return DnsResolveResult(bytes, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)

        assertNotNull(result)
        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(result!!).single())
    }

    @Test
    fun timedOutAaaaLookupStillAttemptsSynthesisFromA() = runBlocking {
        val query = DnsPacket.query("timeout.example", DnsPacket.TYPE_AAAA, 0x6150)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    if (question.type == DnsPacket.TYPE_AAAA) {
                        throw java.io.InterruptedIOException("timeout")
                    }
                    return DnsResolveResult(
                        DnsPacket.aRecordResponse(query, query.size, ipv4),
                        "test",
                        1L,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)

        assertNotNull(result)
        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(result!!).single())
    }

    @Test
    fun emptyAResponseKeepsTheOriginalAaaaResponse() = runBlocking {
        val query = DnsPacket.query("empty.example", DnsPacket.TYPE_AAAA, 0x6149)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val original = response(
            query,
            authorities = listOf(record(name("example"), TYPE_SOA, 75, soaRdata()))
        )
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    val bytes = if (question.type == DnsPacket.TYPE_AAAA) {
                        original
                    } else {
                        DnsPacket.noDataResponse(query, query.size)
                    }
                    return DnsResolveResult(bytes, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)

        assertNotNull(result)
        assertArrayEquals(original, result)
    }

    @Test
    fun extendedRcodeAResponseIsNeverUsedForSynthesis() = runBlocking {
        val query = DnsPacket.query("badvers.example", DnsPacket.TYPE_AAAA, 0x6892)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    val bytes = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.noDataResponse(query, query.size)
                    } else {
                        response(
                            query,
                            answers = listOf(record(pointerToQuestion(), DnsPacket.TYPE_A, 60, ipv4)),
                            additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0x01000000, ByteArray(0), 1232))
                        )
                    }
                    return DnsResolveResult(bytes, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = {
                Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)
            }
        )

        val result = engine.handleTcpMessage(query)!!

        assertEquals(2, Dns64Packet.responseCode(result))
        assertTrue(DnsPacket.extractAaaaRecords(result).isEmpty())
    }

    @Test
    fun dnssecOkRequestIsNeverGivenUnsignedSyntheticData() = runBlocking {
        val query = withDnssecOk(DnsPacket.query("secure.example", DnsPacket.TYPE_AAAA, 0x6155)!!, cd = true)
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        var calls = 0
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    calls += 1
                    return DnsResolveResult(DnsPacket.noDataResponse(query, query.size), "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)

        assertNotNull(result)
        assertEquals(1, calls)
        assertTrue(DnsPacket.extractAaaaRecords(result!!).isEmpty())
    }

    @Test
    fun unknownRecordRdataRemainsOpaqueDuringReserialization() {
        val query = DnsPacket.query("www.example", DnsPacket.TYPE_AAAA, 0x6166)!!
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val aResponse = response(
            aQuery,
            answers = listOf(
                record(pointerToQuestion(), DnsPacket.TYPE_A, 60, byteArrayOf(8, 8, 4, 4))
            ),
            additionals = listOf(record(pointerToQuestion(), 65_000, 60, pointerToQuestion()))
        )

        val synthesized = Dns64Packet.synthesize(
            query,
            DnsPacket.noDataResponse(query, query.size),
            aResponse,
            listOf(prefix),
            authenticated = false
        )

        assertNotNull(synthesized)
        synthesized!!
        assertEquals(1, u16(synthesized, 10))
        assertArrayEquals(pointerToQuestion(), synthesized.copyOfRange(synthesized.size - 2, synthesized.size))
    }

    @Test
    fun legacyNameBearingRdataIsDecompressedWhenRecordsMove() {
        val query = DnsPacket.query("www.example", DnsPacket.TYPE_AAAA, 0x6167)!!
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val aResponse = response(
            aQuery,
            answers = listOf(record(pointerToQuestion(), DnsPacket.TYPE_A, 60, byteArrayOf(8, 8, 4, 4))),
            additionals = listOf(
                record(pointerToQuestion(), 7, 60, pointerToQuestion()),
                record(pointerToQuestion(), 14, 60, pointerToQuestion() + pointerToQuestion())
            )
        )

        val synthesized = Dns64Packet.synthesize(
            query,
            DnsPacket.noDataResponse(query, query.size),
            aResponse,
            listOf(prefix),
            authenticated = false
        )!!

        val rdata = additionalRdatas(synthesized)
        assertArrayEquals(name("www.example"), rdata[0])
        assertArrayEquals(name("www.example") + name("www.example"), rdata[1])
    }

    @Test
    fun overlappingPrefixesUseTheLongestMatchForRebindingClassification() = runBlocking {
        val query = DnsPacket.query("overlap.example", DnsPacket.TYPE_AAAA, 0x6190)!!
        val short = Nat64Prefix.from(InetAddress.getByName("2001:db8:1:2::").address, 64)!!
        val long = Nat64Prefix.from(InetAddress.getByName("2001:db8:1:2::").address, 96)!!
        val publicIpv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val expected = long.synthesize(publicIpv4)
        val engine = DnsProtectionEngine(
            resolver = fixedResolver(DnsPacket.aaaaRecordResponse(query, query.size, expected)),
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(short, long) }
        )

        val result = engine.handleTcpMessage(query)!!

        assertArrayEquals(expected, DnsPacket.extractAaaaRecords(result).single())
    }

    @Test
    fun localSplitDnsMayReturnPrivateIpv4ThroughANetworkSpecificPrefix() = runBlocking {
        val query = DnsPacket.query("nas.corp", DnsPacket.TYPE_AAAA, 0x6191)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("2001:db8:64::").address, 96)!!
        val expected = prefix.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        val engine = DnsProtectionEngine(
            resolver = fixedResolver(DnsPacket.aaaaRecordResponse(query, query.size, expected)),
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) },
            isLocalName = { it.endsWith(".corp") }
        )

        val result = engine.handleTcpMessage(query)!!

        assertArrayEquals(expected, DnsPacket.extractAaaaRecords(result).single())
    }

    @Test
    fun extendedAaaaErrorWithAnswerDataStillFallsBackToA() = runBlocking {
        val query = DnsPacket.query("badvers-aaaa.example", DnsPacket.TYPE_AAAA, 0x6192)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    val value = if (question.type == DnsPacket.TYPE_AAAA) {
                        response(
                            query,
                            answers = listOf(record(pointerToQuestion(), DnsPacket.TYPE_AAAA, 60,
                                InetAddress.getByName("2001:4860:4860::8888").address)),
                            additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0x01000000, ByteArray(0), 1232))
                        )
                    } else {
                        DnsPacket.aRecordResponse(query, query.size, ipv4)
                    }
                    return DnsResolveResult(value, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)!!

        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(result).single())
    }

    @Test
    fun hardStopAaaaEdeMatrixCannotBeBypassedByDns64Synthesis() = runBlocking {
        listOf(4, 6, 15, 16, 17).forEach { ede ->
            val query = DnsPacket.query("hard-stop-$ede.example", DnsPacket.TYPE_AAAA, 0x6193 + ede)!!
            val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
            val calls = mutableListOf<Int>()
            val hardStop = response(
                query,
                additionals = listOf(
                    record(byteArrayOf(0), TYPE_OPT, 0, byteArrayOf(0, 15, 0, 2, 0, ede.toByte()), 1232)
                )
            )
            val engine = DnsProtectionEngine(
                resolver = object : DnsResolver {
                    override suspend fun resolve(
                        query: ByteArray,
                        question: DnsPacket.Question
                    ): DnsResolveResult {
                        calls += question.type
                        val value = if (question.type == DnsPacket.TYPE_AAAA) {
                            hardStop
                        } else {
                            DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 8, 8))
                        }
                        return DnsResolveResult(value, "test", 1L, false)
                    }
                },
                diagnostics = DiagnosticsState(),
                nat64PrefixProvider = { prefix }
            )

            val result = engine.handleTcpMessage(query)!!

            assertEquals(listOf(DnsPacket.TYPE_AAAA), calls)
            assertTrue(DnsPacket.extractAaaaRecords(result).isEmpty())
            assertEquals(2, Dns64Packet.responseCode(result))
        }
    }

    @Test
    fun mappedAaaaCannotEraseDnsSecurityEdeBeforeFallback() = runBlocking {
        for (ede in listOf(15, 16, 17)) {
            val query = DnsPacket.query("mapped-ede-$ede.example", DnsPacket.TYPE_AAAA, 0x6200 + ede)!!
            val calls = mutableListOf<Int>()
            val edeOption = byteArrayOf(0, 15, 0, 2, 0, ede.toByte())
            val upstream = response(
                query,
                answers = listOf(
                    record(pointerToQuestion(), DnsPacket.TYPE_AAAA, 60, ipv4Mapped(192, 0, 2, 1))
                ),
                additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0, edeOption, 1232))
            )
            val engine = DnsProtectionEngine(
                resolver = object : DnsResolver {
                    override suspend fun resolve(
                        query: ByteArray,
                        question: DnsPacket.Question
                    ): DnsResolveResult {
                        calls += question.type
                        val value = if (question.type == DnsPacket.TYPE_AAAA) upstream else
                            DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 8, 8))
                        return DnsResolveResult(value, "test", 1L, false)
                    }
                },
                diagnostics = DiagnosticsState(),
                nat64PrefixProvider = {
                    Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)
                }
            )

            val result = engine.handleTcpMessage(query)!!

            assertEquals(listOf(DnsPacket.TYPE_AAAA), calls)
            assertEquals(2, u16(result, 2) and 0x0f)
            assertTrue(DnsPacket.extractAaaaRecords(result).isEmpty())
        }
    }

    @Test
    fun positiveAddressWithSecurityEdeFailsClosed() = runBlocking {
        for (ede in listOf(4, 6, 15, 16, 17)) {
            val query = DnsPacket.query("positive-ede-$ede.example", DnsPacket.TYPE_AAAA, 0x6300 + ede)!!
            val edeOption = byteArrayOf(0, 15, 0, 2, 0, ede.toByte())
            val upstream = response(
                query,
                answers = listOf(
                    record(
                        pointerToQuestion(),
                        DnsPacket.TYPE_AAAA,
                        60,
                        InetAddress.getByName("2001:4860:4860::8888").address
                    )
                ),
                additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0, edeOption, 1232))
            )
            val engine = DnsProtectionEngine(
                resolver = fixedResolver(upstream),
                diagnostics = DiagnosticsState()
            )

            val result = engine.handleTcpMessage(query)!!

            assertEquals(2, u16(result, 2) and 0x0f)
            assertTrue(DnsPacket.extractAaaaRecords(result).isEmpty())
        }
    }

    @Test
    fun securityEdeOnDns64FollowupACannotBeSynthesized() = runBlocking {
        val query = DnsPacket.query("followup-ede.example", DnsPacket.TYPE_AAAA, 0x6340)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val calls = mutableListOf<Int>()
        val original = DnsPacket.noDataResponse(query, query.size)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    calls += question.type
                    val value = if (question.type == DnsPacket.TYPE_AAAA) {
                        original
                    } else {
                        response(
                            query,
                            answers = listOf(
                                record(pointerToQuestion(), DnsPacket.TYPE_A, 60, byteArrayOf(8, 8, 8, 8))
                            ),
                            additionals = listOf(
                                record(byteArrayOf(0), TYPE_OPT, 0, byteArrayOf(0, 15, 0, 2, 0, 15), 1232)
                            )
                        )
                    }
                    return DnsResolveResult(value, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)!!

        assertEquals(listOf(DnsPacket.TYPE_AAAA, DnsPacket.TYPE_A), calls)
        assertEquals(2, Dns64Packet.responseCode(result))
        assertTrue(DnsPacket.extractAaaaRecords(result).isEmpty())
    }

    @Test
    fun synthesisPrimitiveRejectsHardStopOnEitherInput() {
        val query = DnsPacket.query("primitive-hard-stop.example", DnsPacket.TYPE_AAAA, 0x6341)!!
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val address = byteArrayOf(8, 8, 8, 8)
        val ordinaryNegative = DnsPacket.noDataResponse(query, query.size)
        val ordinaryA = DnsPacket.aRecordResponse(aQuery, aQuery.size, address)

        listOf(4, 6, 15, 16, 17).forEach { ede ->
            val option = byteArrayOf(0, 15, 0, 2, 0, ede.toByte())
            val hardStopNegative = response(
                query,
                additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0, option, 1232))
            )
            val hardStopA = response(
                aQuery,
                answers = listOf(record(pointerToQuestion(), DnsPacket.TYPE_A, 60, address)),
                additionals = listOf(record(byteArrayOf(0), TYPE_OPT, 0, option, 1232))
            )

            assertNull(Dns64Packet.synthesize(query, hardStopNegative, ordinaryA, listOf(prefix), false))
            assertNull(Dns64Packet.synthesize(query, ordinaryNegative, hardStopA, listOf(prefix), false))
        }
    }

    @Test
    fun rebindingAdditionalCannotTriggerDns64Followup() = runBlocking {
        val query = DnsPacket.query("rebind-nodata.example", DnsPacket.TYPE_AAAA, 0x6343)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val calls = mutableListOf<Int>()
        val rebinding = response(
            query,
            additionals = listOf(
                record(
                    name("internal.example"),
                    DnsPacket.TYPE_AAAA,
                    60,
                    InetAddress.getByName("fd00::1").address
                )
            )
        )
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    calls += question.type
                    val value = if (question.type == DnsPacket.TYPE_AAAA) {
                        rebinding
                    } else {
                        DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 8, 8))
                    }
                    return DnsResolveResult(value, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)!!

        assertEquals(listOf(DnsPacket.TYPE_AAAA), calls)
        assertEquals(2, Dns64Packet.responseCode(result))
        assertTrue(DnsPacket.extractAaaaRecords(result).isEmpty())
    }

    @Test
    fun ordinaryRefusedWithoutEdePreservesExistingDns64Fallback() = runBlocking {
        val query = DnsPacket.query("ordinary-refused.example", DnsPacket.TYPE_AAAA, 0x6342)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    val value = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.noDataResponse(query, query.size, rcode = 5)
                    } else {
                        DnsPacket.aRecordResponse(query, query.size, ipv4)
                    }
                    return DnsResolveResult(value, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)!!

        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(result).single())
    }

    @Test
    fun nat64PtrWithDnssecOkStillStaysLocalUnlessCheckingIsDisabled() = runBlocking {
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val reverse = ip6Arpa(prefix.synthesize(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        var calls = 0
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                calls += 1
                return ptrResult(query)
            }
        }
        val engine = DnsProtectionEngine(
            resolver = resolver,
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) }
        )
        val doOnly = withDnssecOk(DnsPacket.query(reverse, DnsPacket.TYPE_PTR, 0x6193)!!, cd = false)
        val cdAndDo = withDnssecOk(DnsPacket.query(reverse, DnsPacket.TYPE_PTR, 0x6194)!!, cd = true)

        assertEquals(listOf(TYPE_CNAME, DnsPacket.TYPE_PTR), answerTypes(engine.handleTcpMessage(doOnly)!!))
        assertEquals(1, calls)
        assertEquals(listOf(DnsPacket.TYPE_PTR), answerTypes(engine.handleTcpMessage(cdAndDo)!!))
        assertEquals(2, calls)
    }

    @Test
    fun prefixExpiryDuringPtrLookupFailsClosed() = runBlocking {
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val reverse = ip6Arpa(prefix.synthesize(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        val query = DnsPacket.query(reverse, DnsPacket.TYPE_PTR, 0x6198)!!
        var remaining = 5L
        var pending = false
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    remaining = 0L
                    pending = true
                    return ptrResult(query)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) },
            nat64RemainingTtlSecondsProvider = { remaining },
            nat64DiscoveryRequiredProvider = { pending }
        )

        assertEquals(2, Dns64Packet.responseCode(engine.handleTcpMessage(query)!!))
    }

    @Test
    fun nat64PtrAcceptsUpstreamOptBeforeAnotherAdditionalRecord() = runBlocking {
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val reverse = ip6Arpa(prefix.synthesize(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        val query = withDnssecOk(DnsPacket.query(reverse, DnsPacket.TYPE_PTR, 0x6199)!!, cd = false)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        response(
                            query,
                            answers = listOf(
                                record(pointerToQuestion(), DnsPacket.TYPE_PTR, 120, name("host.example"))
                            ),
                            additionals = listOf(
                                record(byteArrayOf(0), TYPE_OPT, 0, ByteArray(0), 1232),
                                record(name("example"), 2, 120, name("ns.example"))
                            )
                        ),
                        "test",
                        0L,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) }
        )

        val result = engine.handleTcpMessage(query)!!

        assertEquals(listOf(TYPE_CNAME, DnsPacket.TYPE_PTR), answerTypes(result))
        assertEquals(2, u16(result, 10))
        assertEquals(DnsSecurityMeaning.ACCEPTABLE, DnsMessageValidator.validate(query, result).meaning)
    }

    @Test
    fun privateWellKnownPrefixPtrNeverFallsBackToAShorterStructuralMatch() = runBlocking {
        val short = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 64)!!
        val wellKnown = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val reverse = ip6Arpa(wellKnown.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))
        val query = DnsPacket.query(reverse, DnsPacket.TYPE_PTR, 0x6195)!!
        var calls = 0
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    calls += 1
                    return DnsResolveResult(DnsPacket.nxDomainResponse(query, query.size), "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(short, wellKnown) }
        )

        val result = engine.handleTcpMessage(query)!!

        assertEquals(1, calls)
        assertEquals(3, u16(result, 2) and 0x0f)
    }

    @Test
    fun nat64PtrDoesNotManufactureADanglingCname() = runBlocking {
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val reverse = ip6Arpa(prefix.synthesize(byteArrayOf(11, 22, 33, 44)))
        val query = DnsPacket.query(reverse, DnsPacket.TYPE_PTR, 0x6196)!!
        val calls = mutableListOf<String>()
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    calls += question.domain
                    return DnsResolveResult(DnsPacket.nxDomainResponse(query, query.size), "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) }
        )

        val result = engine.handleTcpMessage(query)!!

        assertEquals(listOf("44.33.22.11.in-addr.arpa", reverse), calls)
        assertEquals(3, u16(result, 2) and 0x0f)
        assertTrue(answerTypes(result).isEmpty())
    }

    @Test
    fun questionlessAaaaServerErrorStillFallsBackToA() = runBlocking {
        val query = DnsPacket.query("questionless.example", DnsPacket.TYPE_AAAA, 0x6197)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val ipv4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    val value = if (question.type == DnsPacket.TYPE_AAAA) {
                        query.copyOfRange(0, 12).also { header ->
                            put16(header, 2, 0x8182)
                            put16(header, 4, 0)
                            put16(header, 6, 0)
                            put16(header, 8, 0)
                            put16(header, 10, 0)
                        }
                    } else {
                        DnsPacket.aRecordResponse(query, query.size, ipv4)
                    }
                    return DnsResolveResult(value, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val result = engine.handleTcpMessage(query)!!

        assertArrayEquals(prefix.synthesize(ipv4), DnsPacket.extractAaaaRecords(result).single())
    }

    @Test
    fun networkGenerationChangeWhileAQueryIsBlockedCannotPublish() {
        generationChangeWhileAQueryIsBlockedCannotPublish(changeNetworkGeneration = true)
    }

    @Test
    fun nat64GenerationChangeWhileAQueryIsBlockedCannotPublish() {
        generationChangeWhileAQueryIsBlockedCannotPublish(changeNetworkGeneration = false)
    }

    private fun generationChangeWhileAQueryIsBlockedCannotPublish(changeNetworkGeneration: Boolean) {
        val query = DnsPacket.query("race.example", DnsPacket.TYPE_AAAA, 0x6177)!!
        val firstPrefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val generation = AtomicLong(4L)
        val prefixes = AtomicReference(listOf(firstPrefix))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(
                    query: ByteArray,
                    question: DnsPacket.Question
                ): DnsResolveResult {
                    val response = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.noDataResponse(query, query.size)
                    } else {
                        entered.countDown()
                        assertTrue(release.await(2, TimeUnit.SECONDS))
                        DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 8, 8))
                    }
                    return DnsResolveResult(response, "test", 1L, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { prefixes.get() },
            nat64GenerationProvider = if (changeNetworkGeneration) ({ 0L }) else generation::get,
            networkGenerationProvider = if (changeNetworkGeneration) generation::get else ({ 0L })
        )
        val result = AtomicReference<ByteArray?>()
        val worker = thread(start = true, isDaemon = true) {
            result.set(runBlocking { engine.handleTcpMessage(query) })
        }

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        generation.incrementAndGet()
        release.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(DnsPacket.extractAaaaRecords(result.get()!!).isEmpty())
    }

    private data class WireRecord(
        val owner: ByteArray,
        val type: Int,
        val ttl: Long,
        val rdata: ByteArray,
        val qClass: Int
    )

    private fun record(
        owner: ByteArray,
        type: Int,
        ttl: Long,
        rdata: ByteArray,
        qClass: Int = 1
    ): WireRecord = WireRecord(owner, type, ttl, rdata, qClass)

    private fun fixedResolver(response: ByteArray): DnsResolver {
        return object : DnsResolver {
            override suspend fun resolve(
                query: ByteArray,
                question: DnsPacket.Question
            ): DnsResolveResult = DnsResolveResult(response, "test", 1L, false)
        }
    }

    private fun ptrResult(query: ByteArray): DnsResolveResult {
        return DnsResolveResult(
            response(
                query,
                answers = listOf(
                    record(pointerToQuestion(), DnsPacket.TYPE_PTR, 120, name("host.example"))
                )
            ),
            "test",
            1L,
            false
        )
    }

    private fun response(
        query: ByteArray,
        flags: Int = 0x8180,
        answers: List<WireRecord> = emptyList(),
        authorities: List<WireRecord> = emptyList(),
        additionals: List<WireRecord> = emptyList()
    ): ByteArray {
        val question = DnsPacket.parseQuestion(query)!!
        val header = query.copyOfRange(0, question.questionEnd)
        put16(header, 2, flags)
        put16(header, 4, 1)
        put16(header, 6, answers.size)
        put16(header, 8, authorities.size)
        put16(header, 10, additionals.size)
        val output = ByteArrayOutputStream()
        output.write(header)
        (answers + authorities + additionals).forEach { value ->
            output.write(value.owner)
            write16(output, value.type)
            write16(output, value.qClass)
            write32(output, value.ttl)
            write16(output, value.rdata.size)
            output.write(value.rdata)
        }
        return output.toByteArray()
    }

    private fun withDnssecOk(query: ByteArray, cd: Boolean): ByteArray {
        val result = ByteArrayOutputStream()
        val header = query.copyOf()
        header[3] = if (cd) (header[3].toInt() or 0x10).toByte() else header[3]
        put16(header, 10, 1)
        result.write(header)
        result.write(0)
        write16(result, TYPE_OPT)
        write16(result, 1232)
        write32(result, 0x8000)
        write16(result, 0)
        return result.toByteArray()
    }

    private fun soaRdata(minimum: Long = 600): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(name("ns.example"))
        output.write(name("hostmaster.example"))
        repeat(4) { index -> write32(output, (index + 1).toLong()) }
        write32(output, minimum)
        return output.toByteArray()
    }

    private fun answerTtls(message: ByteArray, type: Int): List<Long> {
        var offset = skipName(message, 12) + 4
        val result = mutableListOf<Long>()
        repeat(u16(message, 6)) {
            offset = skipName(message, offset)
            val currentType = u16(message, offset)
            val ttl = u32(message, offset + 4)
            val size = u16(message, offset + 8)
            if (currentType == type) result += ttl
            offset += 10 + size
        }
        return result
    }

    private fun answerTypes(message: ByteArray): List<Int> {
        var offset = skipName(message, 12) + 4
        return List(u16(message, 6)) {
            offset = skipName(message, offset)
            val type = u16(message, offset)
            val size = u16(message, offset + 8)
            offset += 10 + size
            type
        }
    }

    private fun additionalRdatas(message: ByteArray): List<ByteArray> {
        var offset = skipName(message, 12) + 4
        repeat(u16(message, 6) + u16(message, 8)) {
            offset = skipName(message, offset)
            offset += 10 + u16(message, offset + 8)
        }
        return List(u16(message, 10)) {
            offset = skipName(message, offset)
            val size = u16(message, offset + 8)
            offset += 10
            message.copyOfRange(offset, offset + size).also { offset += size }
        }
    }

    private fun skipName(message: ByteArray, start: Int): Int {
        var offset = start
        while (true) {
            val size = message[offset].toInt() and 0xff
            if (size == 0) return offset + 1
            if (size and 0xc0 == 0xc0) return offset + 2
            offset += size + 1
        }
    }

    private fun name(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        value.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        return output.toByteArray()
    }

    private fun pointerToQuestion(): ByteArray = byteArrayOf(0xc0.toByte(), 0x0c)

    private fun ip6Arpa(address: ByteArray): String {
        val hex = address.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return hex.reversed().map { it.toString() }.joinToString(".") + ".ip6.arpa"
    }

    private fun firstAnswerNameRdata(message: ByteArray): String {
        var offset = skipName(message, 12) + 4
        offset = skipName(message, offset)
        val size = u16(message, offset + 8)
        offset += 10
        val end = offset + size
        val labels = mutableListOf<String>()
        while (offset < end) {
            val length = message[offset++].toInt() and 0xff
            if (length == 0) break
            labels += String(message, offset, length, Charsets.US_ASCII)
            offset += length
        }
        return labels.joinToString(".")
    }

    private fun firstAnswerTtl(message: ByteArray): Long {
        var offset = skipName(message, 12) + 4
        offset = skipName(message, offset)
        return u32(message, offset + 4)
    }

    private fun ipv4Mapped(a: Int, b: Int, c: Int, d: Int): ByteArray {
        return ByteArray(16).also { value ->
            value[10] = 0xff.toByte()
            value[11] = 0xff.toByte()
            value[12] = a.toByte()
            value[13] = b.toByte()
            value[14] = c.toByte()
            value[15] = d.toByte()
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xffL) shl 24) or
            ((data[offset + 1].toLong() and 0xffL) shl 16) or
            ((data[offset + 2].toLong() and 0xffL) shl 8) or
            (data[offset + 3].toLong() and 0xffL)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun write16(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun write32(output: ByteArrayOutputStream, value: Long) {
        output.write(((value ushr 24) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write((value and 0xff).toInt())
    }

    private companion object {
        const val TYPE_CNAME = 5
        const val TYPE_SOA = 6
        const val TYPE_DNAME = 39
        const val TYPE_OPT = 41
    }
}
