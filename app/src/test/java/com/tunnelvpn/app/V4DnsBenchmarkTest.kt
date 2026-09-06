package com.tunnelvpn.app

import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V4DnsBenchmarkTest {
    @Test
    fun boundedResolverWorkloadsRecordLatencyAndFanout() = runBlocking {
        val records = mutableListOf<String>()
        for (scenario in listOf("cache-hit", "normal", "hedged", "slow-primary", "primary-timeout")) {
            repeat(5) { sample ->
                val calls = AtomicInteger()
                val transport = object : SecureDnsTransport {
                    override suspend fun resolve(
                        provider: SecureResolverProvider,
                        query: ByteArray,
                        question: DnsPacket.Question
                    ): ResolverCandidate {
                        calls.incrementAndGet()
                        val primary = provider.id == SecureResolverId.QUAD9_SECURE
                        val waitMs = when {
                            !primary -> 4L
                            scenario == "slow-primary" -> 220L
                            scenario == "primary-timeout" -> 15L
                            scenario == "hedged" -> 160L
                            else -> 3L
                        }
                        delay(waitMs)
                        if (primary && scenario == "primary-timeout") throw SocketTimeoutException("fixture-timeout")
                        val response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(93, -72, -40, 34))
                        return ResolverCandidate(provider, DnsMessageValidator.validate(query, response), waitMs)
                    }
                }
                val resolver = SecureResolverRace(DnsCache(), transport)
                try {
                    val query = requireNotNull(DnsPacket.query("v4-dns-$sample.example.com", 1))
                    val question = requireNotNull(DnsPacket.parseQuestion(query))
                    if (scenario == "cache-hit") resolver.resolve(query, question)
                    val before = calls.get()
                    val started = System.nanoTime()
                    val response = resolver.resolve(query, question)
                    val elapsed = System.nanoTime() - started
                    val queries = calls.get() - before
                    assertEquals(0, DnsMessageValidator.validate(query, response.response).rcode)
                    assertTrue(queries in 0..2)
                    if (scenario == "cache-hit") {
                        assertTrue(response.cacheHit)
                        assertEquals(0, queries)
                    }
                    if (scenario == "normal") assertEquals(1, queries)
                    if (scenario == "hedged" || scenario == "slow-primary") assertEquals(2, queries)
                    records += "$scenario,$sample,$elapsed,$queries,${resolver.diagnostics().getValue("hedges")},${response.cacheHit}"
                } finally {
                    resolver.close()
                }
            }
        }
        val target = System.getenv("V4_ARTIFACT_DIR")
        if (!target.isNullOrBlank()) {
            val directory = File(target).apply { mkdirs() }
            File(directory, "b3-dns.csv").writeText(
                "scenario,sample,elapsed_nanos,upstream_queries,hedges,cache_hit\n" + records.joinToString("\n", postfix = "\n")
            )
        }
        println("B3 HOST CONTROLLED COROUTINE TRANSPORT: ${records.size} samples; no real resolver latency claim")
    }
}
