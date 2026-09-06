package com.tunnelvpn.app

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboBenchmarkTest {
    @Test
    fun deterministicDnsValidationMicrobenchmark() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x4242)!!
        val response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        repeat(1_000) { DnsMessageValidator.validate(query, response) }
        val samples = LongArray(10_000)
        repeat(samples.size) { index ->
            val started = System.nanoTime()
            DnsMessageValidator.validate(query, response)
            samples[index] = System.nanoTime() - started
        }
        samples.sort()
        val p50Us = TimeUnit.NANOSECONDS.toMicros(samples[samples.size / 2])
        val p95Us = TimeUnit.NANOSECONDS.toMicros(samples[(samples.size * 95) / 100])
        println("BENCH dns_validation iterations=10000 p50_us=$p50Us p95_us=$p95Us")

        assertTrue(p50Us >= 0)
        assertTrue(p95Us < 10_000)
    }

    @Test
    fun deterministicRiskEngineMicrobenchmark() {
        var now = 0L
        val engine = ExplainableRiskEngine(clock = { now })
        val samples = LongArray(10_000)
        repeat(samples.size) { index ->
            now += 1
            val started = System.nanoTime()
            engine.record(if (index % 7 == 0) RiskSignal.MALFORMED_DNS else RiskSignal.MALFORMED_PACKET)
            samples[index] = System.nanoTime() - started
        }
        samples.sort()
        val p50Us = TimeUnit.NANOSECONDS.toMicros(samples[samples.size / 2])
        val p95Us = TimeUnit.NANOSECONDS.toMicros(samples[(samples.size * 95) / 100])
        println("BENCH risk_engine iterations=10000 p50_us=$p50Us p95_us=$p95Us")

        assertTrue(p95Us < 10_000)
    }
}
