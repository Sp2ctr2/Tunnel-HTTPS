package com.tunnelvpn.app

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DohResolverTest {
    @Test
    fun defaultFallbackOrderPrefersLowLatencyProviders() {
        val resolver = DohResolver(DnsCache())

        assertEquals(listOf("cloudflare", "google", "quad9"), resolver.providerNamesInFallbackOrder())
    }

    @Test
    fun everyDefaultProviderHasIpv4AndIpv6BootstrapAddresses() {
        DohResolver.DEFAULT_PROVIDERS.forEach { provider ->
            assertTrue(provider.bootstrapAddresses.any { it.address.size == 4 })
            assertTrue(provider.bootstrapAddresses.any { it.address.size == 16 })
        }
    }

    @Test
    fun pinnedBootstrapAvoidsSystemLookupOnHealthyPath() {
        val dynamic = InetAddress.getByName("2001:db8::53")
        val pinned = InetAddress.getByName("1.1.1.1")
        var lookupCount = 0
        val dns = DohResolver.BootstrapDns(
            listOf(DohProvider("test", "https://resolver.example/dns-query", listOf(pinned))),
            networkLookup = {
                lookupCount += 1
                listOf(dynamic)
            },
            addressTransform = { it },
            preferIpv6 = { false }
        )

        assertEquals(listOf(pinned), dns.lookup("resolver.example"))
        assertEquals(0, lookupCount)
    }

    @Test
    fun ipv6OnlyBootstrapTriesIpv6BeforePinnedIpv4() {
        val ipv4 = InetAddress.getByName("1.1.1.1")
        val ipv6 = InetAddress.getByName("2606:4700:4700::1111")
        val dns = DohResolver.BootstrapDns(
            listOf(
                DohProvider(
                    "test",
                    "https://resolver.example/dns-query",
                    listOf(ipv4, ipv6)
                )
            ),
            networkLookup = { throw UnknownHostException(it) },
            addressTransform = { it },
            preferIpv6 = { true }
        )

        assertEquals(listOf(ipv6, ipv4), dns.lookup("resolver.example"))
    }

    @Test
    fun transformedAndPinnedAddressesAreDeduplicatedWithoutLookup() {
        val address = InetAddress.getByName("2001:db8::53")
        var lookupCount = 0
        val dns = DohResolver.BootstrapDns(
            listOf(DohProvider("test", "https://resolver.example/dns-query", listOf(address))),
            networkLookup = {
                lookupCount += 1
                listOf(address)
            },
            addressTransform = { listOf(address) },
            preferIpv6 = { true }
        )

        assertEquals(listOf(address), dns.lookup("resolver.example"))
        assertEquals(0, lookupCount)
    }

    @Test
    fun ipv6OnlyBootstrapUsesBoundedSynthesizedCandidatesBeforeLookup() {
        val ipv4 = InetAddress.getByName("1.1.1.1")
        val synthesized = InetAddress.getByName("64:ff9b::101:101")
        var lookupCount = 0
        val dns = DohResolver.BootstrapDns(
            listOf(DohProvider("test", "https://resolver.example/dns-query", listOf(ipv4))),
            networkLookup = {
                lookupCount += 1
                emptyList()
            },
            addressTransform = { listOf(synthesized) },
            preferIpv6 = { true }
        )

        assertEquals(listOf(synthesized, ipv4), dns.lookup("resolver.example"))
        assertEquals(0, lookupCount)
    }

    @Test
    fun acceptsCorrelatedDnsResponse() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.noDataResponse(query, query.size)

        assertEquals(response, DohResolver.validateResponse("test", query, response))
    }

    @Test
    fun rejectsMismatchedTransaction() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.withTransactionId(
            DnsPacket.noDataResponse(query, query.size),
            0x4321
        )

        assertThrows(IOException::class.java) {
            DohResolver.validateResponse("test", query, response)
        }
    }

    @Test
    fun rejectsResponseWithDifferentQuestion() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val otherQuery = DnsPacket.query("attacker.example", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.noDataResponse(otherQuery, otherQuery.size)

        assertThrows(IOException::class.java) {
            DohResolver.validateResponse("test", query, response)
        }
    }

    @Test
    fun rejectsTruncatedResponse() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val response = DnsPacket.noDataResponse(query, query.size).also {
            it[2] = (it[2].toInt() or 0x02).toByte()
        }

        assertThrows(IOException::class.java) {
            DohResolver.validateResponse("test", query, response)
        }
    }

    @Test
    fun invalidQueryCannotPoisonFailureCache() {
        val cache = DnsCache()
        val resolver = DohResolver(cache, providers = emptyList())
        val valid = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val question = DnsPacket.parseQuestion(valid)!!
        val invalid = valid + byteArrayOf(0)

        assertThrows(IOException::class.java) {
            runBlocking { resolver.resolve(invalid, question) }
        }
        assertFalse(cache.isFailureCached(question.key))
        resolver.close()
    }

    @Test
    fun networkChangeCancelsAnInFlightRequestFromTheOldGeneration() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 1, loopback)
        val requestStarted = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val serverThread = Thread {
            runCatching {
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val input = peer.getInputStream()
                    var matched = 0
                    while (matched < 4) {
                        val value = input.read()
                        if (value < 0) return@use
                        matched = when {
                            matched == 0 && value == '\r'.code -> 1
                            matched == 1 && value == '\n'.code -> 2
                            matched == 2 && value == '\r'.code -> 3
                            matched == 3 && value == '\n'.code -> 4
                            value == '\r'.code -> 1
                            else -> 0
                        }
                    }
                    requestStarted.countDown()
                    while (input.read() >= 0) Unit
                    connectionClosed.countDown()
                }
            }
        }.apply { isDaemon = true; start() }
        val resolver = DohResolver(
            cache = DnsCache(),
            providers = listOf(
                DohProvider("local", "http://127.0.0.1:${server.localPort}/dns-query")
            )
        )
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x1234)!!
        val question = DnsPacket.parseQuestion(query)!!

        try {
            val result = async(Dispatchers.IO) { runCatching { resolver.resolve(query, question) }.exceptionOrNull() }
            assertTrue(requestStarted.await(3, TimeUnit.SECONDS))
            resolver.onNetworkChanged()
            assertTrue(withTimeout(3_000L) { result.await() } is IOException)
            assertTrue(connectionClosed.await(3, TimeUnit.SECONDS))
        } finally {
            resolver.close()
            runCatching { server.close() }
            serverThread.join(1_000L)
        }
    }
}
