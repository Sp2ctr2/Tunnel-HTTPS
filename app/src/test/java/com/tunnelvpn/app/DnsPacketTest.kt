package com.tunnelvpn.app

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {
    private fun readUnsignedShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    @Test
    fun buildsAaaaResponseForAaaaQuestion() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA)!!
        val address = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1
        )

        val response = DnsPacket.aaaaRecordResponse(query, query.size, address)

        assertEquals(1, readUnsignedShort(response, 6))
        assertTrue(response.takeLast(16).toByteArray().contentEquals(address))
    }

    @Test
    fun malformedQueryProducesBoundedFailureResponse() {
        val malformed = byteArrayOf(0x12, 0x34, 0x01)

        val response = DnsPacket.servFailResponse(malformed, malformed.size)

        assertEquals(12, response.size)
        assertEquals(0x12, response[0].toInt() and 0xff)
        assertEquals(0x34, response[1].toInt() and 0xff)
        assertEquals(2, response[3].toInt() and 0x0f)
    }

    @Test
    fun parserRejectsLengthBeyondArray() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A)!!

        assertNull(DnsPacket.parseQuestion(query, query.size + 1))
    }

    @Test
    fun parserRejectsResponseFlagsAndNonQuerySectionCounts() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A)!!

        assertNull(DnsPacket.parseQuestion(query.copyOf().also { it[2] = 0x81.toByte() }))
        assertNull(DnsPacket.parseQuestion(query.copyOf().also { it[7] = 1 }))
        assertNull(DnsPacket.parseQuestion(query.copyOf().also { it[9] = 1 }))
        assertNull(DnsPacket.parseQuestion(query.copyOf().also { it[11] = 2 }))
    }

    @Test
    fun presentationSeparatorsInsideWireLabelsAreRejected() {
        val embeddedDot = wireQuery(listOf("a.b", "example"))

        assertNull(DnsPacket.parseQuestion(embeddedDot))
    }

    @Test
    fun ednsAndDnssecSettingsAreIsolatedInCacheKey() {
        val plain = DnsPacket.query("example.com", DnsPacket.TYPE_A)!!
        val checkingDisabled = plain.copyOf().also { it[3] = 0x10 }
        val edns = plain.copyOf(plain.size + 11).also {
            it[11] = 1
            val offset = plain.size
            it[offset] = 0
            it[offset + 1] = 0
            it[offset + 2] = 41
            it[offset + 3] = 4
            it[offset + 4] = 0
        }
        val dnssec = edns.copyOf().also {
            val offset = plain.size
            it[offset + 7] = 0x80.toByte()
        }

        val plainQuestion = DnsPacket.parseQuestion(plain)!!
        val checkingDisabledQuestion = DnsPacket.parseQuestion(checkingDisabled)!!
        val ednsQuestion = DnsPacket.parseQuestion(edns)!!
        val dnssecQuestion = DnsPacket.parseQuestion(dnssec)!!

        assertFalse(plainQuestion.key == checkingDisabledQuestion.key)
        assertFalse(plainQuestion.key == ednsQuestion.key)
        assertFalse(ednsQuestion.key == dnssecQuestion.key)
    }

    @Test
    fun malformedEdnsAndTrailingBytesAreRejected() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A)!!
        assertNull(DnsPacket.parseQuestion(query + byteArrayOf(0)))
        val truncatedOpt = query.copyOf(query.size + 5).also { it[11] = 1 }
        assertNull(DnsPacket.parseQuestion(truncatedOpt))
    }

    @Test
    fun unsupportedEdnsVersionProducesBadversWithVersionZeroOpt() {
        val base = DnsPacket.query("example.com", DnsPacket.TYPE_A)!!
        val query = base.copyOf(base.size + 11).also {
            it[11] = 1
            val offset = base.size
            it[offset] = 0
            it[offset + 2] = 41
            it[offset + 3] = 0x04
            it[offset + 4] = 0xd0.toByte()
            it[offset + 6] = 1
        }

        val question = DnsPacket.parseQuestion(query)!!
        val response = DnsPacket.badVersionResponse(query)
        val opt = question.questionEnd

        assertEquals(1, question.ednsVersion)
        assertEquals(0, response[3].toInt() and 0x0f)
        assertEquals(1, response[opt + 5].toInt() and 0xff)
        assertEquals(0, response[opt + 6].toInt() and 0xff)
        assertEquals(41, readUnsignedShort(response, opt + 1))
    }

    @Test
    fun udpPayloadLimitUsesEdnsAndDefaultsTo512() {
        val plain = DnsPacket.query("example.com", DnsPacket.TYPE_A)!!
        val edns = plain.copyOf(plain.size + 11).also {
            it[11] = 1
            val offset = plain.size
            it[offset] = 0
            it[offset + 1] = 0
            it[offset + 2] = 41
            it[offset + 3] = 0x04
            it[offset + 4] = 0xd0.toByte()
        }

        assertEquals(512, DnsPacket.udpPayloadSize(plain))
        assertEquals(1232, DnsPacket.udpPayloadSize(edns))
    }

    @Test
    fun truncatedResponsePreservesQuestionAndRequestsTcpRetry() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x7182)!!

        val response = DnsPacket.truncatedResponse(query)

        assertEquals(0x7182, readUnsignedShort(response, 0))
        assertEquals(0x02, response[2].toInt() and 0x02)
        assertEquals(1, readUnsignedShort(response, 4))
        assertEquals(0, readUnsignedShort(response, 6))
        assertEquals("example.com", DnsPacket.parseQuestion(query)!!.domain)
    }

    @Test
    fun localResponsesEchoRecursionDesiredAndCheckingDisabledFlags() {
        val aQuery = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x4035)!!.also {
            it[2] = 0
            it[3] = 0x10
        }
        val ptrQuery = DnsPacket.query("1.0.0.127.in-addr.arpa", DnsPacket.TYPE_PTR, 0x4036)!!.also {
            it[2] = 0
            it[3] = 0x10
        }
        val responses = listOf(
            DnsPacket.aRecordResponse(aQuery, aQuery.size, byteArrayOf(1, 1, 1, 1)),
            DnsPacket.noDataResponse(aQuery, aQuery.size),
            DnsPacket.nxDomainResponse(aQuery, aQuery.size),
            DnsPacket.servFailResponse(aQuery, aQuery.size),
            DnsPacket.truncatedResponse(aQuery),
            DnsPacket.cnameRecordResponse(ptrQuery, "localhost")!!
        )

        responses.forEach { response ->
            val flags = readUnsignedShort(response, 2)
            assertEquals(0, flags and 0x0100)
            assertEquals(0x0010, flags and 0x0010)
        }
    }

    @Test
    fun localResponseRebuildsMinimalEdnsWithoutReplayingOptions() {
        val plain = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x6891)!!
        val query = plain.copyOf(plain.size + 17).also {
            it[10] = 0
            it[11] = 1
            var offset = plain.size
            it[offset++] = 0
            it[offset++] = 0
            it[offset++] = 41
            it[offset++] = 0x04
            it[offset++] = 0xd0.toByte()
            it[offset++] = 0
            it[offset++] = 0
            it[offset++] = 0x80.toByte()
            it[offset++] = 0
            it[offset++] = 0
            it[offset++] = 6
            it[offset++] = 0
            it[offset++] = 12
            it[offset++] = 0
            it[offset++] = 2
            it[offset++] = 1
            it[offset] = 2
        }

        val response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(1, 1, 1, 1))

        assertEquals(1, readUnsignedShort(response, 10))
        val opt = response.size - 11
        assertEquals(41, readUnsignedShort(response, opt + 1))
        assertEquals(1232, readUnsignedShort(response, opt + 3))
        assertEquals(0x8000, readUnsignedShort(response, opt + 7))
        assertEquals(0, readUnsignedShort(response, opt + 9))
    }

    @Test
    fun localOnlyNamesAreNeverEligibleForPublicDoh() {
        assertTrue(DnsPacket.isLocalOnlyName("printer.local"))
        assertTrue(DnsPacket.isLocalOnlyName("router"))
        assertTrue(DnsPacket.isLocalOnlyName("host.home.arpa"))
        assertTrue(DnsPacket.isLocalOnlyName("1.0.10.in-addr.arpa"))
        assertTrue(DnsPacket.isLocalOnlyName("1.20.172.in-addr.arpa"))
        assertTrue(DnsPacket.isLocalOnlyName("1.0.168.192.in-addr.arpa"))
        assertTrue(DnsPacket.isLocalOnlyName("1.64.100.in-addr.arpa"))
        assertTrue(DnsPacket.isLocalOnlyName("1.127.100.in-addr.arpa"))
        assertTrue(DnsPacket.isLocalOnlyName("1.0.18.198.in-addr.arpa"))
        assertTrue(DnsPacket.isLocalOnlyName("1.0.0.0.in-addr.arpa"))
        assertTrue(DnsPacket.isLocalOnlyName(ip6Arpa("fd00::1")))
        assertTrue(DnsPacket.isLocalOnlyName(ip6Arpa("fe80::1")))
        assertTrue(DnsPacket.isLocalOnlyName(ip6Arpa("::1")))
        assertFalse(DnsPacket.isLocalOnlyName("example.com"))
        assertFalse(DnsPacket.isLocalOnlyName("8.8.8.8.in-addr.arpa"))
        assertFalse(DnsPacket.isLocalOnlyName("64.100.in-addr.arpa.evil.com"))
        assertFalse(DnsPacket.isLocalOnlyName(ip6Arpa("2606:4700:4700::1111")))
    }

    private fun ip6Arpa(address: String): String {
        return InetAddress.getByName(address).address.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .reversed()
            .toCharArray()
            .joinToString(".") + ".ip6.arpa"
    }

    private fun wireQuery(labels: List<String>): ByteArray {
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val data = ByteArray(size)
        data[2] = 1
        data[5] = 1
        var offset = 12
        labels.forEach { label ->
            data[offset++] = label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).copyInto(data, offset)
            offset += label.length
        }
        data[offset++] = 0
        data[offset + 1] = DnsPacket.TYPE_A.toByte()
        data[offset + 3] = 1
        return data
    }
}
