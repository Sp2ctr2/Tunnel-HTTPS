package com.tunnelvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsClientHelloTest {
    @Test
    fun detectsCompleteClientHelloAndExtractsSni() {
        val hello = clientHello("example.com")

        val analysis = TlsClientHello.analyze(hello)

        assertTrue(analysis.complete)
        assertTrue(analysis.clientHello)
        assertEquals("example.com", analysis.sni)
        assertNotNull(analysis.sniExtensionOffset)
        assertNotNull(analysis.sniHostNameOffset)
        assertNotNull(analysis.sniHostNameEnd)
        assertNotNull(TlsClientHello.fragmentationOffset(hello))
    }

    @Test
    fun reportsTheRetryableRecordBoundaryBeforeTrailingEarlyData() {
        val hello = clientHello("example.com")
        val earlyData = byteArrayOf(23, 3, 3, 0, 3, 1, 2, 3)

        val analysis = TlsClientHello.analyze(hello + earlyData)

        assertTrue(analysis.complete)
        assertFalse(analysis.malformed)
        assertEquals(hello.size, analysis.clientHelloRecordEnd)
    }

    @Test
    fun fragmentationPlanSplitsAroundSniHostName() {
        val hello = clientHello("restricted.example")

        val analysis = TlsClientHello.analyze(hello)
        val plan = TlsClientHello.fragmentationPlan(hello)

        assertNotNull(plan)
        plan!!
        assertTrue(plan.splitPoints.contains(analysis.sniExtensionOffset))
        assertTrue(plan.splitPoints.any { point ->
            point > analysis.sniHostNameOffset!! && point < analysis.sniHostNameEnd!!
        })
        assertTrue(plan.splitPoints.zipWithNext().all { (left, right) -> left < right })
        assertEquals("clienthello-sni-multi-split", plan.strategy)
    }

    @Test
    fun coldStartUsesTheMostCompatibleFragmentationBeforeStrongerFallbacks() {
        val order = TlsClientHello.adaptiveModeOrder()

        assertEquals(
            listOf(
                TlsClientHello.FragmentationMode.HOSTNAME,
                TlsClientHello.FragmentationMode.SINGLE_SAFE,
                TlsClientHello.FragmentationMode.SNI_MULTI,
                TlsClientHello.FragmentationMode.NONE
            ),
            order
        )

        assertEquals(
            TlsClientHello.FragmentationMode.SINGLE_SAFE,
            TlsClientHello.adaptiveModeOrder(TlsClientHello.FragmentationMode.SINGLE_SAFE).first()
        )
    }

    @Test
    fun allFragmentationModesAvoidTlsRecordHeader() {
        val hello = clientHello("restricted.example")

        TlsClientHello.FragmentationMode.values().forEach { mode ->
            val plan = TlsClientHello.fragmentationPlan(hello, hello.size, mode)

            assertNotNull(plan)
            assertTrue(plan!!.splitPoints.all { it > 5 })
            assertTrue(plan.splitPoints.zipWithNext().all { (left, right) -> left < right })
        }
    }

    @Test
    fun noFragmentationModeKeepsClientHelloIntact() {
        val hello = clientHello("restricted.example")
        val plan = TlsClientHello.fragmentationPlan(
            hello,
            hello.size,
            TlsClientHello.FragmentationMode.NONE
        )

        assertNotNull(plan)
        assertTrue(plan!!.splitPoints.isEmpty())
        assertEquals("clienthello-no-fragment", plan.strategy)
    }

    @Test
    fun waitsForFragmentedClientHello() {
        val partial = clientHello("example.com").copyOfRange(0, 12)

        val analysis = TlsClientHello.analyze(partial)

        assertFalse(analysis.complete)
        assertFalse(analysis.clientHello)
        assertNull(analysis.sni)
        assertNull(TlsClientHello.fragmentationOffset(partial))
    }

    @Test
    fun waitsForClientHelloThatSpansMultipleTlsRecords() {
        val hello = clientHello("example.com")
        val body = hello.copyOfRange(5, hello.size)
        val first = ByteArray(9).also {
            it[0] = 22
            it[1] = 3
            it[2] = 3
            put16(it, 3, 4)
            body.copyInto(it, 5, 0, 4)
        }
        val second = ByteArray(5 + body.size - 4).also {
            it[0] = 22
            it[1] = 3
            it[2] = 3
            put16(it, 3, body.size - 4)
            body.copyInto(it, 5, 4, body.size)
        }

        val partial = TlsClientHello.analyze(first)
        val complete = TlsClientHello.analyze(first + second)

        assertFalse(partial.complete)
        assertTrue(partial.clientHello)
        assertTrue(complete.complete)
        assertTrue(complete.clientHello)
        assertEquals("example.com", complete.sni)
        assertFalse(complete.malformed)
    }

    @Test
    fun extractsSniAcrossThreeBoundedTlsRecords() {
        val hello = clientHello("three.records.example")
        val body = hello.copyOfRange(5, hello.size)
        val first = tlsRecord(body.copyOfRange(0, 7))
        val second = tlsRecord(body.copyOfRange(7, 39))
        val third = tlsRecord(body.copyOfRange(39, body.size))

        val analysis = TlsClientHello.analyze(first + second + third)

        assertTrue(analysis.complete)
        assertTrue(analysis.clientHello)
        assertFalse(analysis.malformed)
        assertEquals("three.records.example", analysis.sni)
        assertTrue(analysis.sniHostNameOffset!! >= first.size + second.size)
    }

    @Test
    fun fragmentsAThreeRecordClientHelloWithoutChangingItsHandshakeBytes() {
        val hello = clientHello("three.records.example")
        val body = hello.copyOfRange(5, hello.size)
        val input = tlsRecord(body.copyOfRange(0, 7)) +
            tlsRecord(body.copyOfRange(7, 39)) +
            tlsRecord(body.copyOfRange(39, body.size))
        val plan = TlsClientHello.fragmentationPlan(input)!!

        val records = TlsClientHello.splitIntoTlsRecords(input, input.size, plan.splitPoints)

        assertNotNull(records)
        records!!
        assertTrue(records.size > 3)
        assertArrayEquals(body, reassembleRecordBodies(records))
        assertFalse(records.any { indexOf(it, 5, "three.records.example".toByteArray()) >= 0 })
    }

    @Test
    fun hostnameSplitPointsNeverLandInsideContinuationRecordHeaders() {
        val host = "crossing-record-boundary.example"
        val hello = clientHello(host)
        val originalAnalysis = TlsClientHello.analyze(hello)
        val hostBodyOffset = originalAnalysis.sniHostNameOffset!! - 5
        val body = hello.copyOfRange(5, hello.size)
        val first = tlsRecord(body.copyOfRange(0, hostBodyOffset + 4))
        val second = tlsRecord(body.copyOfRange(hostBodyOffset + 4, hostBodyOffset + 10))
        val third = tlsRecord(body.copyOfRange(hostBodyOffset + 10, body.size))
        val input = first + second + third
        val plan = TlsClientHello.fragmentationPlan(input)!!
        val continuationHeaders = listOf(first.size, first.size + second.size)

        assertTrue(plan.splitPoints.none { point ->
            continuationHeaders.any { header -> point in (header + 1) until (header + 5) }
        })
        val records = TlsClientHello.splitIntoTlsRecords(input, input.size, plan.splitPoints)
        assertNotNull(records)
        assertArrayEquals(body, reassembleRecordBodies(records!!))
        assertFalse(records.any { indexOf(it, 5, host.toByteArray()) >= 0 })
    }

    @Test
    fun signalsMalformedContinuationRecord() {
        val hello = clientHello("example.com")
        val body = hello.copyOfRange(5, hello.size)
        val first = tlsRecord(body.copyOfRange(0, 8))
        val invalidContinuation = tlsRecord(body.copyOfRange(8, body.size)).also { it[0] = 23 }

        val analysis = TlsClientHello.analyze(first + invalidContinuation)

        assertTrue(analysis.complete)
        assertTrue(analysis.clientHello)
        assertTrue(analysis.malformed)
        assertNull(analysis.sni)
    }

    @Test
    fun nonTls443PayloadIsNotClientHello() {
        val payload = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.US_ASCII)

        val analysis = TlsClientHello.analyze(payload)

        assertTrue(analysis.complete)
        assertFalse(analysis.clientHello)
        assertNull(analysis.sni)
    }

    @Test
    fun splitIntoTlsRecordsReframesClientHelloAndBreaksSniAcrossRecords() {
        val host = "restricted.example"
        val hello = clientHello(host)
        val plan = TlsClientHello.fragmentationPlan(hello)!!

        val records = TlsClientHello.splitIntoTlsRecords(hello, hello.size, plan.splitPoints)

        assertNotNull(records)
        records!!

        assertTrue(records.size >= 2)


        val reassembled = java.io.ByteArrayOutputStream()
        records.forEach { record ->
            assertEquals(22, record[0].toInt() and 0xff)
            assertEquals(0x03, record[1].toInt() and 0xff)
            val declared = ((record[3].toInt() and 0xff) shl 8) or (record[4].toInt() and 0xff)
            assertEquals(record.size - 5, declared)
            reassembled.write(record, 5, record.size - 5)
        }

        val originalBody = hello.copyOfRange(5, hello.size)
        assertArrayEquals(originalBody, reassembled.toByteArray())
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val hostInSomeRecord = records.any { record ->
            indexOf(record, 5, hostBytes) >= 0
        }
        assertFalse("SNI hostname must be split across TLS records", hostInSomeRecord)
    }

    @Test
    fun splitIntoTlsRecordsReturnsNullForNonClientHello() {
        val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray(Charsets.US_ASCII)
        assertNull(TlsClientHello.splitIntoTlsRecords(payload, payload.size, listOf(6, 10)))
    }

    @Test
    fun rejectsCallerLengthBeyondBackingArray() {
        val hello = clientHello("example.com")

        val analysis = TlsClientHello.analyze(hello, hello.size + 1)

        assertFalse(analysis.complete)
        assertFalse(analysis.clientHello)
        assertNull(TlsClientHello.splitIntoTlsRecords(hello, hello.size + 1, listOf(10)))
    }

    private fun indexOf(haystack: ByteArray, from: Int, needle: ByteArray): Int {
        if (needle.isEmpty()) return -1
        var i = from
        while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun reassembleRecordBodies(records: List<ByteArray>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        records.forEach { record ->
            assertTrue(record.size >= 5)
            val declared = ((record[3].toInt() and 0xff) shl 8) or (record[4].toInt() and 0xff)
            assertEquals(record.size - 5, declared)
            output.write(record, 5, declared)
        }
        return output.toByteArray()
    }

    private fun clientHello(host: String): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val sni = ByteArray(9 + hostBytes.size)
        put16(sni, 0, 0)
        put16(sni, 2, 5 + hostBytes.size)
        put16(sni, 4, 3 + hostBytes.size)
        sni[6] = 0
        put16(sni, 7, hostBytes.size)
        hostBytes.copyInto(sni, 9)

        val handshakeBody = ByteArray(2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + sni.size)
        var offset = 0
        handshakeBody[offset++] = 0x03
        handshakeBody[offset++] = 0x03
        offset += 32
        handshakeBody[offset++] = 0
        put16(handshakeBody, offset, 2)
        offset += 2
        handshakeBody[offset++] = 0x13
        handshakeBody[offset++] = 0x01
        handshakeBody[offset++] = 1
        handshakeBody[offset++] = 0
        put16(handshakeBody, offset, sni.size)
        offset += 2
        sni.copyInto(handshakeBody, offset)

        val handshake = ByteArray(4 + handshakeBody.size)
        handshake[0] = 1
        handshake[1] = ((handshakeBody.size ushr 16) and 0xff).toByte()
        handshake[2] = ((handshakeBody.size ushr 8) and 0xff).toByte()
        handshake[3] = (handshakeBody.size and 0xff).toByte()
        handshakeBody.copyInto(handshake, 4)

        val record = ByteArray(5 + handshake.size)
        record[0] = 22
        record[1] = 0x03
        record[2] = 0x03
        put16(record, 3, handshake.size)
        handshake.copyInto(record, 5)
        return record
    }

    private fun tlsRecord(payload: ByteArray): ByteArray {
        return ByteArray(5 + payload.size).also {
            it[0] = 22
            it[1] = 3
            it[2] = 3
            put16(it, 3, payload.size)
            payload.copyInto(it, 5)
        }
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }
}
