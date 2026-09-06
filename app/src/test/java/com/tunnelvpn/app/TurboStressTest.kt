package com.tunnelvpn.app

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboStressTest {
    @Test
    fun fixedSeedMalformedDnsStressIsBoundedAndCrashFree() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x5151)!!
        val random = java.util.Random(0x534543555245L)
        val started = System.nanoTime()
        repeat(20_000) {
            val bytes = ByteArray(12 + random.nextInt(1_012))
            random.nextBytes(bytes)
            bytes[0] = query[0]
            bytes[1] = query[1]
            runCatching { DnsMessageValidator.validate(query, bytes) }
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue("parser stress exceeded local safety bound: $elapsedMs ms", elapsedMs < 10_000L)
    }

    @Test
    fun concurrentCacheAccessRemainsBounded() {
        val cache = DnsCache(maxEntries = 64)
        val executor = Executors.newFixedThreadPool(8)
        val failures = AtomicInteger()
        repeat(8) { worker ->
            executor.execute {
                runCatching {
                    repeat(5_000) { index ->
                        val key = "key-${(index + worker) % 128}"
                        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, index and 0xffff)!!
                        cache.putSuccess(key, DnsPacket.noDataResponse(query, query.size))
                        cache.get(key, index)
                        if (index % 17 == 0) cache.isFailureCached(key)
                    }
                }.onFailure { failures.incrementAndGet() }
            }
        }
        executor.shutdown()

        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        assertEquals(0, failures.get())
        assertTrue(cache.size() <= 64)
    }

    @Test
    fun permitConservationUnderConcurrentAdmission() {
        val controller = SafeBurstController(32, 40, 8)
        val pressure = ResourcePressure(false, false, false, 0.2, 0.0, false, false, RiskLevel.NORMAL)
        val executor = Executors.newFixedThreadPool(8)
        val admitted = AtomicInteger()
        repeat(64) {
            executor.execute {
                if (controller.admit(32 + admitted.get().coerceAtMost(8), pressure)) admitted.incrementAndGet()
            }
        }
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(admitted.get() <= 8)
        assertTrue(controller.available() >= 0)
    }

    @Test
    fun concurrentAiOutcomeQueueNeverExceedsCapacity() {
        val queue = BoundedTurboOutcomeQueue<Int>(128)
        val executor = Executors.newFixedThreadPool(8)
        repeat(8) { worker ->
            executor.execute {
                repeat(2_000) { index -> queue.offer(worker * 2_000 + index) }
            }
        }
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(queue.size() <= 128)
        var drained = 0
        while (queue.poll() != null) drained += 1
        assertTrue(drained <= 128)
        assertEquals(0, queue.size())
    }

    @Test
    fun concurrentClientPayloadBudgetsEnforcePerFlowAndGlobalByteCaps() {
        val globalBytes = AtomicInteger()
        val budgets = List(8) { TurboPayloadBudget(512, globalBytes, 2_048) }
        val executor = Executors.newFixedThreadPool(8)
        val accepted = AtomicInteger()
        budgets.forEach { budget ->
            executor.execute {
                repeat(16) {
                    if (budget.tryReserve(64)) accepted.addAndGet(64)
                }
            }
        }
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(globalBytes.get() <= 2_048)
        assertTrue(budgets.all { it.retainedBytes() <= 512 })
        assertEquals(globalBytes.get(), budgets.sumOf { it.retainedBytes() })
        budgets.forEach { budget -> budget.release(budget.retainedBytes()) }
        assertEquals(0, globalBytes.get())
        assertTrue(accepted.get() <= 2_048)
    }
}
