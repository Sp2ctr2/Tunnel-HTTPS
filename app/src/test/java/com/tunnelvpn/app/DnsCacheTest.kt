package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCacheTest {
    private var now = 1_000L

    @Test
    fun cachedResponseUsesCallerTransactionId() {
        val cache = DnsCache(clock = { now })
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.noDataResponse(query, query.size)

        cache.putSuccess("example.com|1|1", response)

        val cached = cache.get("example.com|1|1", 0xabcd)!!
        assertEquals(0xab.toByte(), cached[0])
        assertEquals(0xcd.toByte(), cached[1])
    }

    @Test
    fun entriesExpire() {
        val cache = DnsCache(successTtlMs = 10, clock = { now })
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 1)!!
        cache.putSuccess("example.com|1|1", DnsPacket.noDataResponse(query, query.size))

        now += 11

        assertNull(cache.get("example.com|1|1", 1))
        assertEquals(0, cache.size())
    }

    @Test
    fun failedLookupsAreCachedBriefly() {
        val cache = DnsCache(failureTtlMs = 10, clock = { now })
        cache.putFailure("bad.example|1|1")

        assertTrue(cache.isFailureCached("bad.example|1|1"))

        now += 11

        assertFalse(cache.isFailureCached("bad.example|1|1"))
    }

    @Test
    fun clearDropsAllEntriesOnNetworkChange() {
        val cache = DnsCache(clock = { now })
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 1)!!
        cache.putSuccess("example.com|1|1", DnsPacket.noDataResponse(query, query.size))
        cache.putFailure("bad.example|1|1")
        assertEquals(2, cache.size())
        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("example.com|1|1", 1))
        assertFalse(cache.isFailureCached("bad.example|1|1"))
    }

    @Test
    fun responseCacheEnforcesPerEntryAndTotalByteBudgets() {
        val query = DnsPacket.query("x.example", DnsPacket.TYPE_A, 1)!!
        val response = DnsPacket.noDataResponse(query, query.size)
        val cache = DnsCache(
            maxEntries = 10,
            maxCachedBytes = response.size,
            maxResponseBytes = response.size,
            clock = { now }
        )

        cache.putSuccess("a", response)
        cache.putSuccess("b", response)

        assertNull(cache.get("a", 1))
        assertEquals(response.size, cache.cachedBytes())
        assertTrue(cache.get("b", 1) != null)

        cache.putSuccess("oversized", response + 0)
        assertNull(cache.get("oversized", 1))
        assertEquals(response.size, cache.cachedBytes())
    }

    @Test
    fun cachedWireTtlsDecreaseWithElapsedTime() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.aRecordResponse(
            query,
            query.size,
            byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        )
        val cache = DnsCache(successTtlMs = 30_000L, clock = { now })
        cache.putSuccess("example.com|1|1", response)

        now += 29_000L
        val cached = cache.get("example.com|1|1", 0xabcd)!!

        val ttlOffset = DnsPacket.parseQuestion(query)!!.questionEnd + 6
        assertEquals(1L, u32(cached, ttlOffset))
    }

    @Test
    fun optPseudoRecordIsNeverReplayedFromCache() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val base = DnsPacket.noDataResponse(query, query.size)
        base[10] = 0
        base[11] = 1
        val response = base + byteArrayOf(0, 0, 41, 0x04, 0xd0.toByte(), 0, 0, 0, 0, 0, 0)
        val cache = DnsCache(clock = { now })

        cache.putSuccess("example.com|1|1", response)
        val cached = cache.get("example.com|1|1", 0x4321)!!

        assertEquals(0, u16(cached, 10))
        assertEquals(base.size, cached.size)
    }

    @Test
    fun zeroTtlResponseIsNeverCached() {
        val query = DnsPacket.query("zero.example", DnsPacket.TYPE_A, 0x1234)!!
        val cache = DnsCache(clock = { now })

        cache.putSuccess("zero.example|1|1", DnsPacket.noDataResponse(query, query.size), 0L)

        assertEquals(0, cache.size())
        assertNull(cache.get("zero.example|1|1", 0x4321))
    }

    @Test
    fun cacheHitRestoresOnlyAFreshOptForTheCurrentEdnsQuery() {
        val baseQuery = DnsPacket.query("edns.example", DnsPacket.TYPE_A, 0x1234)!!
        val query = withOpt(baseQuery, 1232, dnssecOk = true, byteArrayOf(0, 12, 0, 2, 1, 2))
        val upstreamResponse = withOpt(
            DnsPacket.noDataResponse(baseQuery, baseQuery.size),
            4096,
            dnssecOk = false,
            byteArrayOf(0, 15, 0, 2, 0, 15)
        )
        val cache = DnsCache(clock = { now })
        cache.putSuccess("edns", upstreamResponse)

        val cached = cache.get("edns", query)!!

        assertEquals(1, u16(cached, 10))
        assertEquals(DnsPacket.parseQuestion(baseQuery)!!.questionEnd + 11, cached.size)
        val opt = cached.size - 11
        assertEquals(41, u16(cached, opt + 1))
        assertEquals(1232, u16(cached, opt + 3))
        assertEquals(0x8000L, u32(cached, opt + 5))
        assertEquals(0, u16(cached, opt + 9))
    }

    @Test
    fun cacheHitEchoesTheCurrentQuestionWireCasing() {
        val base = DnsPacket.query("case.example", DnsPacket.TYPE_A, 0x1234)!!
        val first = recaseQuestion(base, uppercaseEven = true)
        val second = recaseQuestion(base, uppercaseEven = false).also { it[0] = 0x43; it[1] = 0x21 }
        val firstQuestion = DnsPacket.parseQuestion(first)!!
        val secondQuestion = DnsPacket.parseQuestion(second)!!
        assertEquals(firstQuestion.key, secondQuestion.key)
        val cache = DnsCache(clock = { now })
        cache.putSuccess(firstQuestion.key, DnsPacket.noDataResponse(first, first.size))

        val cached = cache.get(secondQuestion.key, second)!!

        assertTrue(cached.copyOfRange(12, secondQuestion.questionEnd)
            .contentEquals(second.copyOfRange(12, secondQuestion.questionEnd)))
        assertEquals(0x43, cached[0].toInt() and 0xff)
        assertEquals(0x21, cached[1].toInt() and 0xff)
    }

    private fun recaseQuestion(query: ByteArray, uppercaseEven: Boolean): ByteArray {
        val result = query.copyOf()
        var letterIndex = 0
        var offset = 12
        while (result[offset].toInt() != 0) {
            val length = result[offset++].toInt() and 0xff
            repeat(length) {
                val value = result[offset].toInt() and 0xff
                if (value in 'a'.code..'z'.code && (letterIndex++ % 2 == 0) == uppercaseEven) {
                    result[offset] = (value - 32).toByte()
                }
                offset += 1
            }
        }
        return result
    }

    private fun withOpt(base: ByteArray, udpSize: Int, dnssecOk: Boolean, options: ByteArray): ByteArray {
        val result = base.copyOf(base.size + 11 + options.size)
        result[10] = 0
        result[11] = 1
        var offset = base.size
        result[offset++] = 0
        result[offset++] = 0
        result[offset++] = 41
        result[offset++] = (udpSize ushr 8).toByte()
        result[offset++] = udpSize.toByte()
        result[offset++] = 0
        result[offset++] = 0
        result[offset++] = if (dnssecOk) 0x80.toByte() else 0
        result[offset++] = 0
        result[offset++] = (options.size ushr 8).toByte()
        result[offset++] = options.size.toByte()
        options.copyInto(result, offset)
        return result
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
}
