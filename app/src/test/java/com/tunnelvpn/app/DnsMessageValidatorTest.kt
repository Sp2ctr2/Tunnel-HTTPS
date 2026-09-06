package com.tunnelvpn.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsMessageValidatorTest {
    @Test
    fun acceptsCorrelatedAddressResponse() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

        val parsed = DnsMessageValidator.validate(query, response)

        assertEquals(DnsSecurityMeaning.ACCEPTABLE, parsed.meaning)
        assertEquals(1, parsed.addressFingerprints.size)
    }

    @Test
    fun rejectsTruncatedHeaderAndTransactionMismatch() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!

        assertThrows(IOException::class.java) {
            DnsMessageValidator.validate(query, ByteArray(11))
        }
        assertThrows(IOException::class.java) {
            DnsMessageValidator.validate(query, DnsPacket.withTransactionId(DnsPacket.noDataResponse(query, query.size), 1))
        }
    }

    @Test
    fun rejectsCompressionPointerLoopWithinBoundedWork() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = answerWithRdata(query, 5, byteArrayOf(0xc0.toByte(), (query.size + 12).toByte()))

        assertThrows(IOException::class.java) {
            DnsMessageValidator.validate(query, response)
        }
    }

    @Test
    fun rejectsMalformedPtrAndCompressedDnameTargets() {
        val ptrQuery = DnsPacket.query("1.0.0.127.in-addr.arpa", DnsPacket.TYPE_PTR, 0x1235)!!
        val dnameQuery = DnsPacket.query("example.com", 39, 0x1236)!!

        assertThrows(IOException::class.java) {
            DnsMessageValidator.validate(ptrQuery, answerWithRdata(ptrQuery, DnsPacket.TYPE_PTR, byteArrayOf(0xc0.toByte())))
        }
        assertThrows(IOException::class.java) {
            DnsMessageValidator.validate(dnameQuery, answerWithRdata(dnameQuery, 39, byteArrayOf(0xc0.toByte(), 0x0c)))
        }
    }

    @Test
    fun classifiesPublicNameToLoopbackAsRebinding() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(127, 0, 0, 1))

        val parsed = DnsMessageValidator.validate(query, response)

        assertEquals(DnsSecurityMeaning.REBINDING, parsed.meaning)
    }

    @Test
    fun localResolverAnswerMayUsePrivateAddress() {
        val query = DnsPacket.query("service.corp.example", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(10, 20, 30, 40))

        val parsed = DnsMessageValidator.validate(query, response, publicQuery = false)

        assertEquals(DnsSecurityMeaning.ACCEPTABLE, parsed.meaning)
    }

    @Test
    fun classifiesCarrierBenchmarkAndUlaAddressesAsRebinding() {
        val ipv4Query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val ipv6Query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA, 0x1235)!!
        val addresses = listOf(
            DnsPacket.aRecordResponse(ipv4Query, ipv4Query.size, byteArrayOf(100, 64, 0, 1)) to ipv4Query,
            DnsPacket.aRecordResponse(ipv4Query, ipv4Query.size, byteArrayOf(198.toByte(), 18, 0, 1)) to ipv4Query,
            answerWithRdata(ipv6Query, DnsPacket.TYPE_AAAA, byteArrayOf(0xfc.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)) to ipv6Query
        )

        addresses.forEach { (response, query) ->
            assertEquals(DnsSecurityMeaning.REBINDING, DnsMessageValidator.validate(query, response).meaning)
        }
    }

    @Test
    fun onlyIpv4OnlyArpaMayReturnItsWellKnownDiscoveryAddresses() {
        val discovery = DnsPacket.query("ipv4only.arpa", DnsPacket.TYPE_A, 0x7050)!!
        val ordinary = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x7051)!!
        val wka = byteArrayOf(192.toByte(), 0, 0, 170.toByte())

        assertEquals(
            DnsSecurityMeaning.ACCEPTABLE,
            DnsMessageValidator.validate(
                discovery,
                DnsPacket.aRecordResponse(discovery, discovery.size, wka)
            ).meaning
        )
        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                ordinary,
                DnsPacket.aRecordResponse(ordinary, ordinary.size, wka)
            ).meaning
        )
    }

    @Test
    fun ipv4OnlyArpaMayDiscoverAnExactUlaNetworkSpecificPrefix() {
        val query = DnsPacket.query("ipv4only.arpa", DnsPacket.TYPE_AAAA, 0x7052)!!
        val ordinary = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA, 0x7053)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("fd00:64::").address, 96)!!
        val address = prefix.synthesize(byteArrayOf(192.toByte(), 0, 0, 170.toByte()))

        assertEquals(
            DnsSecurityMeaning.ACCEPTABLE,
            DnsMessageValidator.validate(
                query,
                DnsPacket.aaaaRecordResponse(query, query.size, address)
            ).meaning
        )
        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                ordinary,
                DnsPacket.aaaaRecordResponse(ordinary, ordinary.size, address)
            ).meaning
        )
    }

    @Test
    fun activeUlaNat64PrefixMayCarryOnlyPublicEmbeddedIpv4() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA, 0x7054)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("fd00:64::").address, 96)!!
        val publicAddress = prefix.synthesize(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val privateAddress = prefix.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        val allowed: (ByteArray) -> Boolean = { address ->
            prefix.extractIpv4(address)?.let(DnsMessageValidator::isPublicAddress) == true
        }

        assertEquals(
            DnsSecurityMeaning.ACCEPTABLE,
            DnsMessageValidator.validate(
                query,
                DnsPacket.aaaaRecordResponse(query, query.size, publicAddress),
                allowedLocalAddress = allowed
            ).meaning
        )
        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                query,
                DnsPacket.aaaaRecordResponse(query, query.size, privateAddress),
                allowedLocalAddress = allowed
            ).meaning
        )
    }

    @Test
    fun wellKnownNat64PrefixNeverCarriesPrivateIpv4ForAPublicName() {
        val query = DnsPacket.query("attacker.example", DnsPacket.TYPE_AAAA, 0x7056)!!
        val prefix = Nat64Prefix.from(InetAddress.getByName("64:ff9b::").address, 96)!!
        val address = prefix.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))

        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                query,
                DnsPacket.aaaaRecordResponse(query, query.size, address)
            ).meaning
        )
    }

    @Test
    fun httpsAndSvcbAddressHintsParticipateInRebindingValidation() {
        listOf(DnsPacket.TYPE_HTTPS, DnsPacket.TYPE_SVCB).forEachIndexed { index, type ->
            val query = DnsPacket.query("example.com", type, 0x8200 + index)!!
            val privateHint = svcbRdata(4, byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
            val publicHint = svcbRdata(4, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

            assertEquals(
                DnsSecurityMeaning.REBINDING,
                DnsMessageValidator.validate(query, answerWithRdata(query, type, privateHint)).meaning
            )
            assertEquals(
                DnsSecurityMeaning.ACCEPTABLE,
                DnsMessageValidator.validate(query, answerWithRdata(query, type, publicHint)).meaning
            )
        }
    }

    @Test
    fun httpsIpv6HintsRejectLocalTranslationAndCallerForbiddenRanges() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_HTTPS, 0x8210)!!
        val localTranslation = ByteArray(16).also {
            it[1] = 0x64
            it[2] = 0xff.toByte()
            it[3] = 0x9b.toByte()
            it[5] = 1
            it[15] = 1
        }
        val operatorPrefix = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 96)!!
        val privateEmbedded = operatorPrefix.synthesize(byteArrayOf(10, 0, 0, 1))

        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                query,
                answerWithRdata(query, DnsPacket.TYPE_HTTPS, svcbRdata(6, localTranslation))
            ).meaning
        )
        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                query,
                answerWithRdata(query, DnsPacket.TYPE_HTTPS, svcbRdata(6, privateEmbedded)),
                forbiddenAddress = { it.contentEquals(privateEmbedded) }
            ).meaning
        )
    }

    @Test
    fun currentNonGlobalIpv6SpecialPurposeRangesAreNeverPublic() {
        listOf(
            "100::",
            "100::ffff:ffff:ffff:ffff",
            "100:0:0:1::",
            "100:0:0:1:ffff:ffff:ffff:ffff",
            "2001:2::",
            "2001:2:ffff:ffff:ffff:ffff:ffff:ffff",
            "2001:5::1",
            "2001:100::1",
            "3fff::",
            "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff",
            "5f00::",
            "5f00:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
        ).forEach { text ->
            assertEquals(text, false, DnsMessageValidator.isPublicAddress(InetAddress.getByName(text).address))
        }
        listOf(
            "2001::1",
            "2001:1::1",
            "2001:1::2",
            "2001:1::3",
            "2001:3::1",
            "2001:4:112::1",
            "2001:20::1",
            "2001:30::1",
            "3ffe:ffff::1",
            "2606:4700:4700::1111"
        ).forEach { text ->
            assertEquals(text, true, DnsMessageValidator.isPublicAddress(InetAddress.getByName(text).address))
        }
    }

    @Test
    fun rejectsMalformedSvcbParameterEncoding() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_HTTPS, 0x8211)!!
        val badHintLength = byteArrayOf(0, 1, 0, 0, 4, 0, 3, 1, 2, 3)
        val compressedTarget = byteArrayOf(0, 1, 0xc0.toByte(), 0x0c)
        val duplicateKeys = byteArrayOf(0, 1, 0, 0, 4, 0, 4, 1, 1, 1, 1, 0, 4, 0, 4, 2, 2, 2, 2)

        listOf(badHintLength, compressedTarget, duplicateKeys).forEach { rdata ->
            assertThrows(IOException::class.java) {
                DnsMessageValidator.validate(query, answerWithRdata(query, DnsPacket.TYPE_HTTPS, rdata))
            }
        }
    }

    @Test
    fun boundsTheTotalNumberOfSvcbAddressHints() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_HTTPS, 0x8213)!!
        val accepted = ByteArray(256 * 4) { index -> if (index % 4 == 0) 93 else 1 }
        val rejected = ByteArray(257 * 4) { index -> if (index % 4 == 0) 93 else 1 }

        assertEquals(
            DnsSecurityMeaning.ACCEPTABLE,
            DnsMessageValidator.validate(
                query,
                answerWithRdata(query, DnsPacket.TYPE_HTTPS, svcbRdata(4, accepted))
            ).meaning
        )
        assertThrows(IOException::class.java) {
            DnsMessageValidator.validate(
                query,
                answerWithRdata(query, DnsPacket.TYPE_HTTPS, svcbRdata(4, rejected))
            )
        }
    }

    @Test
    fun extendedDnsErrorsCannotOverrideAddressRebindingRejection() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_HTTPS, 0x8212)!!
        val privateHint = svcbRdata(4, byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        val answer = answerWithRdata(query, DnsPacket.TYPE_HTTPS, privateHint)

        listOf(15, 16, 17).forEach { ede ->
            assertEquals(
                DnsSecurityMeaning.REBINDING,
                DnsMessageValidator.validate(query, withEde(answer, ede)).meaning
            )
        }
    }

    @Test
    fun acceptsOnlyBoundedQuestionlessProtocolErrors() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA, 0x7055)!!
        listOf(1, 2, 4).forEach { rcode ->
            val response = query.copyOfRange(0, 12).also {
                it[2] = 0x81.toByte()
                it[3] = (0x80 or rcode).toByte()
                it.fill(0, 4, 12)
            }
            val validated = DnsMessageValidator.validate(query, response)
            assertEquals(DnsSecurityMeaning.ERROR, validated.meaning)
            assertEquals(rcode, validated.rcode)
        }
        val malformed = query.copyOfRange(0, 12).also {
            it[2] = 0x81.toByte()
            it[3] = 0x82.toByte()
            it.fill(0, 4, 12)
            it[7] = 1
        }
        assertThrows(IOException::class.java) { DnsMessageValidator.validate(query, malformed) }
    }

    @Test
    fun classifiesExtendedDnsErrorWithoutCallingCensorshipMalware() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!

        val blocked = DnsMessageValidator.validate(query, responseWithEde(query, 15))
        assertEquals(0, blocked.rcode)
        assertEquals(DnsSecurityMeaning.BLOCKED, blocked.meaning)
        assertEquals(DnsSecurityMeaning.FILTERED, DnsMessageValidator.validate(query, responseWithEde(query, 17)).meaning)
        assertEquals(DnsSecurityMeaning.CENSORED, DnsMessageValidator.validate(query, responseWithEde(query, 16)).meaning)
        assertEquals(DnsSecurityMeaning.FORGED, DnsMessageValidator.validate(query, responseWithEde(query, 4)).meaning)
        assertEquals(DnsSecurityMeaning.DNSSEC_BOGUS, DnsMessageValidator.validate(query, responseWithEde(query, 6)).meaning)
    }

    @Test
    fun inspectsEveryEdeOptionInTheOptRecord() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA, 0x1235)!!
        val response = responseWithOptData(
            query,
            byteArrayOf(
                0, 15, 0, 2, 0, 0,
                0, 15, 0, 2, 0, 17
            )
        )

        assertEquals(DnsSecurityMeaning.FILTERED, DnsMessageValidator.validate(query, response).meaning)
    }

    @Test
    fun malformedLaterEdeCannotHideBehindEarlierHardStop() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA, 0x1236)!!
        val response = responseWithOptData(
            query,
            byteArrayOf(
                0, 15, 0, 2, 0, 15,
                0, 15, 0, 2, 0
            )
        )

        assertThrows(IOException::class.java) { DnsMessageValidator.validate(query, response) }
    }

    @Test
    fun preservesOrdinaryDnsErrorResponsesAsValidatedProtocolResults() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA, 0x6147)!!

        listOf(1, 2, 4, 5).forEach { rcode ->
            val response = DnsPacket.noDataResponse(query, query.size).also { value ->
                value[3] = (value[3].toInt() and 0xf0 or rcode).toByte()
            }
            val parsed = DnsMessageValidator.validate(query, response)

            assertEquals(rcode, parsed.rcode)
            assertEquals(DnsSecurityMeaning.ERROR, parsed.meaning)
        }
    }

    @Test
    fun negativeCacheTtlUsesTheLowerOfSoaTtlAndMinimum() {
        val query = DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x2308)!!
        val response = negativeWithSoa(query, ttl = 900, minimum = 37)

        val parsed = DnsMessageValidator.validate(query, response)

        assertEquals(37L, parsed.minimumTtlSeconds)
    }

    @Test
    fun negativeCacheTtlAlsoHonorsShorterAliasTtl() {
        val query = DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x230a)!!

        val parsed = DnsMessageValidator.validate(query, negativeWithCnameAndSoa(query, 5, 600, 600))

        assertEquals(5L, parsed.minimumTtlSeconds)
    }

    @Test
    fun negativeResponseWithoutSoaIsNotCacheable() {
        val query = DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x2309)!!

        listOf(false, true).forEach { nxdomain ->
            val parsed = DnsMessageValidator.validate(query, negativeWithNsOnly(query, nxdomain))
            assertEquals(0L, parsed.minimumTtlSeconds)
        }
    }

    @Test
    fun unrelatedAnswerDoesNotTurnNodataIntoAPositiveCacheEntry() {
        val query = DnsPacket.query("missing.example", DnsPacket.TYPE_A, 0x2310)!!

        val parsed = DnsMessageValidator.validate(query, negativeWithUnrelatedAAndSoa(query))

        assertEquals(5L, parsed.minimumTtlSeconds)
    }

    @Test
    fun additionalSoaCannotCreateANegativeCacheTtl() {
        val query = DnsPacket.query("missing.example", DnsPacket.TYPE_AAAA, 0x2311)!!
        val response = negativeWithSoa(query, 86_400, 86_400).also {
            it[8] = 0
            it[9] = 0
            it[10] = 0
            it[11] = 1
        }

        assertEquals(0L, DnsMessageValidator.validate(query, response).minimumTtlSeconds)
    }

    @Test
    fun publicAliasesCannotPivotIntoLocalOnlyNames() {
        val cnameQuery = DnsPacket.query("victim.example", DnsPacket.TYPE_AAAA, 0x2312)!!
        val dnameQuery = DnsPacket.query("www.victim.example", DnsPacket.TYPE_AAAA, 0x2313)!!
        val httpsQuery = DnsPacket.query("victim.example", DnsPacket.TYPE_HTTPS, 0x2314)!!

        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                cnameQuery,
                answerWithRdata(cnameQuery, 5, encodedName("printer.local"))
            ).meaning
        )
        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                dnameQuery,
                answerWithRdata(dnameQuery, 39, encodedName("home.arpa"))
            ).meaning
        )
        assertEquals(
            DnsSecurityMeaning.REBINDING,
            DnsMessageValidator.validate(
                httpsQuery,
                answerWithRdata(httpsQuery, DnsPacket.TYPE_HTTPS, svcbTargetRdata("printer"))
            ).meaning
        )
    }

    @Test
    fun localQueriesAndPublicAliasTargetsRemainAllowed() {
        val localQuery = DnsPacket.query("service.local", DnsPacket.TYPE_AAAA, 0x2315)!!
        val publicQuery = DnsPacket.query("victim.example", DnsPacket.TYPE_AAAA, 0x2316)!!

        assertEquals(
            DnsSecurityMeaning.ACCEPTABLE,
            DnsMessageValidator.validate(
                localQuery,
                answerWithRdata(localQuery, 5, encodedName("printer.local")),
                publicQuery = false
            ).meaning
        )
        assertEquals(
            DnsSecurityMeaning.ACCEPTABLE,
            DnsMessageValidator.validate(
                publicQuery,
                answerWithRdata(publicQuery, 5, encodedName("cdn.example.net"))
            ).meaning
        )
    }

    @Test
    fun combinesTheEdnsExtendedRcodeWithTheHeaderRcode() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA, 0x6891)!!
        val response = responseWithExtendedRcode(query, 1)

        val parsed = DnsMessageValidator.validate(query, response)

        assertEquals(16, parsed.rcode)
        assertEquals(DnsSecurityMeaning.ERROR, parsed.meaning)
    }

    @Test
    fun seededMalformedMessagesNeverEscapeAsValid() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x2222)!!
        val valid = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val random = java.util.Random(0x544852454154L)
        repeat(500) {
            val bytes = when (random.nextInt(6)) {
                0 -> valid.copyOf(random.nextInt(valid.size))
                1 -> valid.copyOf().also { value -> value[4] = 0; value[5] = 0 }
                2 -> valid.copyOf().also { value -> value[6] = 0x7f; value[7] = 0xff.toByte() }
                3 -> valid + random.nextInt(256).toByte()
                4 -> valid.copyOf().also { value -> value[12] = 0xc0.toByte(); value[13] = 0x0c }
                else -> valid.copyOf().also { value -> value[2] = (value[2].toInt() and 0x7f).toByte() }
            }
            assertThrows(IOException::class.java) {
                DnsMessageValidator.validate(query, bytes)
            }
        }
    }

    @Test
    fun rejectsMalformedQueryBeforeCorrelatingResponse() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.noDataResponse(query, query.size)
        val malformed = query + byteArrayOf(0)

        assertThrows(IOException::class.java) {
            DnsMessageValidator.validate(malformed, response)
        }
    }

    private fun answerWithRdata(query: ByteArray, type: Int, rdata: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val headerAndQuestion = DnsPacket.noDataResponse(query, query.size)
        headerAndQuestion[6] = 0
        headerAndQuestion[7] = 1
        output.write(headerAndQuestion)
        output.write(byteArrayOf(0xc0.toByte(), 0x0c))
        output.write(byteArrayOf(
            0,
            type.toByte(),
            0,
            1,
            0,
            0,
            0,
            30,
            (rdata.size ushr 8).toByte(),
            rdata.size.toByte()
        ))
        output.write(rdata)
        return output.toByteArray()
    }

    private fun svcbRdata(key: Int, value: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0, 1, 0))
        output.write(byteArrayOf((key ushr 8).toByte(), key.toByte()))
        output.write(byteArrayOf((value.size ushr 8).toByte(), value.size.toByte()))
        output.write(value)
        return output.toByteArray()
    }

    private fun svcbTargetRdata(target: String): ByteArray {
        return byteArrayOf(0, 0) + encodedName(target)
    }

    private fun encodedName(domain: String): ByteArray {
        val output = ByteArrayOutputStream()
        domain.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        return output.toByteArray()
    }

    private fun responseWithEde(query: ByteArray, infoCode: Int): ByteArray {
        return responseWithOptData(
            query,
            byteArrayOf(0, 15, 0, 2, (infoCode ushr 8).toByte(), infoCode.toByte())
        )
    }

    private fun responseWithOptData(query: ByteArray, data: ByteArray): ByteArray {
        val response = DnsPacket.noDataResponse(query, query.size)
        response[10] = 0
        response[11] = 1
        val output = ByteArrayOutputStream()
        output.write(response)
        output.write(0)
        output.write(byteArrayOf(0, 41))
        output.write(byteArrayOf(0x10, 0))
        output.write(byteArrayOf(0, 0, 0, 0))
        output.write(byteArrayOf((data.size ushr 8).toByte(), data.size.toByte()))
        output.write(data)
        return output.toByteArray()
    }

    private fun withEde(response: ByteArray, infoCode: Int): ByteArray {
        val header = response.copyOf()
        header[10] = 0
        header[11] = 1
        val output = ByteArrayOutputStream()
        output.write(header)
        output.write(0)
        output.write(byteArrayOf(0, 41, 0x10, 0))
        output.write(byteArrayOf(0, 0, 0, 0))
        output.write(byteArrayOf(0, 6, 0, 15, 0, 2, (infoCode ushr 8).toByte(), infoCode.toByte()))
        return output.toByteArray()
    }

    private fun negativeWithSoa(query: ByteArray, ttl: Int, minimum: Int): ByteArray {
        val header = DnsPacket.noDataResponse(query, query.size)
        header[8] = 0
        header[9] = 1
        val rdata = ByteArrayOutputStream()
        rdata.write(byteArrayOf(2, 'n'.code.toByte(), 's'.code.toByte(), 7))
        rdata.write("example".toByteArray())
        rdata.write(0)
        rdata.write(byteArrayOf(10))
        rdata.write("hostmaster".toByteArray())
        rdata.write(byteArrayOf(7))
        rdata.write("example".toByteArray())
        rdata.write(0)
        repeat(4) { write32(rdata, it + 1) }
        write32(rdata, minimum)
        val output = ByteArrayOutputStream()
        output.write(header)
        output.write(byteArrayOf(0xc0.toByte(), 0x14, 0, 6, 0, 1))
        write32(output, ttl)
        output.write(byteArrayOf((rdata.size() ushr 8).toByte(), rdata.size().toByte()))
        output.write(rdata.toByteArray())
        return output.toByteArray()
    }

    private fun negativeWithNsOnly(query: ByteArray, nxdomain: Boolean): ByteArray {
        val header = DnsPacket.noDataResponse(query, query.size)
        if (nxdomain) header[3] = (header[3].toInt() or 3).toByte()
        header[8] = 0
        header[9] = 1
        val output = ByteArrayOutputStream()
        output.write(header)
        output.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 2, 0, 1))
        write32(output, 86_400)
        output.write(byteArrayOf(0, 2, 0xc0.toByte(), 0x0c))
        return output.toByteArray()
    }

    private fun negativeWithCnameAndSoa(query: ByteArray, cnameTtl: Int, soaTtl: Int, minimum: Int): ByteArray {
        val response = negativeWithSoa(query, soaTtl, minimum)
        response[6] = 0
        response[7] = 1
        val output = ByteArrayOutputStream()
        output.write(response, 0, query.size)
        output.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 5, 0, 1))
        write32(output, cnameTtl)
        val target = byteArrayOf(
            6, 't'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), 'g'.code.toByte(), 'e'.code.toByte(),
            't'.code.toByte(), 7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0
        )
        output.write(byteArrayOf(0, target.size.toByte()))
        output.write(target)
        output.write(response, query.size, response.size - query.size)
        return output.toByteArray()
    }

    private fun negativeWithUnrelatedAAndSoa(query: ByteArray): ByteArray {
        val negative = negativeWithSoa(query, 86_400, 5)
        negative[6] = 0
        negative[7] = 1
        val output = ByteArrayOutputStream()
        output.write(negative, 0, query.size)
        output.write(encodedName("unrelated.example"))
        output.write(byteArrayOf(0, 1, 0, 1))
        write32(output, 86_400)
        output.write(byteArrayOf(0, 4, 93, 184.toByte(), 216.toByte(), 34))
        output.write(negative, query.size, negative.size - query.size)
        return output.toByteArray()
    }

    private fun responseWithExtendedRcode(query: ByteArray, extendedRcode: Int): ByteArray {
        val response = DnsPacket.noDataResponse(query, query.size)
        response[10] = 0
        response[11] = 1
        val output = ByteArrayOutputStream()
        output.write(response)
        output.write(0)
        output.write(byteArrayOf(0, 41, 0x04, 0xd0.toByte()))
        write32(output, extendedRcode shl 24)
        output.write(byteArrayOf(0, 0))
        return output.toByteArray()
    }

    private fun write32(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }
}
