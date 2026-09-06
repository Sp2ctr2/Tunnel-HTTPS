package com.tunnelvpn.app

import java.net.InetAddress
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemDnsResolverTest {
    @Test
    fun networkChangeInvalidatesTheRawTransport() {
        var changes = 0
        val resolver = SystemDnsResolver(networkChanged = { changes += 1 })

        resolver.onNetworkChanged()

        assertEquals(1, changes)
    }

    @Test
    fun aaaaQueryReturnsAnIpv6Answer() = runBlocking {
        val ipv6 = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1
        )
        val resolver = SystemDnsResolver(lookup = { arrayOf(InetAddress.getByAddress(ipv6)) })
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_AAAA)!!

        val result = resolver.resolve(query, DnsPacket.parseQuestion(query)!!)

        assertEquals(1, readUnsignedShort(result.response, 6))
        assertTrue(result.response.takeLast(16).toByteArray().contentEquals(ipv6))
    }

    @Test
    fun localNamesStayLocalAndConfiguredSuffixesUsePublicNegativeThenLocalFallback() = runBlocking {
        var publicCalls = 0
        var localCalls = 0
        val public = resolver("public") { publicCalls += 1 }
        val local = resolver("local") { localCalls += 1 }
        val resolver = LocalFirstDnsResolver(public, local, listOf("corp.example"))

        val localName = DnsPacket.query("printer.local", DnsPacket.TYPE_A)!!
        val configured = DnsPacket.query("service.corp.example", DnsPacket.TYPE_A)!!
        val internet = DnsPacket.query("example.com", DnsPacket.TYPE_A)!!

        assertEquals("local", resolver.resolve(localName, DnsPacket.parseQuestion(localName)!!).provider)
        val configuredResult = resolver.resolve(configured, DnsPacket.parseQuestion(configured)!!)
        assertEquals("local", configuredResult.provider)
        assertTrue(configuredResult.localScope)
        assertEquals("public", resolver.resolve(internet, DnsPacket.parseQuestion(internet)!!).provider)
        assertEquals(2, publicCalls)
        assertEquals(2, localCalls)
    }

    @Test
    fun publicPositiveAnswerWinsOverANetworkSearchSuffix() = runBlocking {
        var localCalls = 0
        val public = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                return DnsResolveResult(
                    DnsPacket.aRecordResponse(query, query.size, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)),
                    "public",
                    0L,
                    false
                )
            }
        }
        val local = resolver("local") { localCalls += 1 }
        val resolver = LocalFirstDnsResolver(public, local, listOf("co.kr"))
        val query = DnsPacket.query("shop.co.kr", DnsPacket.TYPE_A)!!

        val result = resolver.resolve(query, DnsPacket.parseQuestion(query)!!)

        assertEquals("public", result.provider)
        assertTrue(!result.localScope)
        assertEquals(0, localCalls)
    }

    @Test
    fun networkPublishedSearchSuffixesAreAppliedDynamically() = runBlocking {
        var suffixes = emptySet<String>()
        val public = resolver("public") {}
        val local = resolver("local") {}
        val resolver = LocalFirstDnsResolver(
            public,
            local,
            localSuffixProvider = { suffixes }
        )
        val query = DnsPacket.query("nas.corp.example", DnsPacket.TYPE_AAAA)!!
        val question = DnsPacket.parseQuestion(query)!!

        assertEquals("public", resolver.resolve(query, question).provider)
        suffixes = setOf("corp.example")
        assertEquals("local", resolver.resolve(query, question).provider)
    }

    @Test
    fun publicEncryptedDnsFailureNeverLeaksToSystemResolver() {
        var localCalls = 0
        val public = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                throw IOException("encrypted resolver unavailable")
            }
        }
        val local = resolver("local") { localCalls += 1 }
        val resolver = LocalFirstDnsResolver(public, local)
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A)!!

        assertThrows(IOException::class.java) {
            runBlocking { resolver.resolve(query, DnsPacket.parseQuestion(query)!!) }
        }
        assertEquals(0, localCalls)
    }

    @Test
    fun arbitraryInternetQtypeUsesTheValidatedRawTransport() = runBlocking {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_HTTPS, 0x4211)!!
        val expected = DnsPacket.noDataResponse(query, query.size)
        var rawCalls = 0
        val resolver = SystemDnsResolver(
            rawLookup = { received, question ->
                rawCalls += 1
                assertTrue(received.contentEquals(query))
                assertEquals(DnsPacket.TYPE_HTTPS, question.type)
                expected
            },
            lookup = { error("address lookup must not run") }
        )

        val result = resolver.resolve(query, DnsPacket.parseQuestion(query)!!)

        assertEquals("system-dns-raw", result.provider)
        assertTrue(expected.contentEquals(result.response))
        assertEquals(1, rawCalls)
    }

    @Test
    fun rawTransportValidationFailureCannotFallBackToUnvalidatedAddressLookup() {
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x4212)!!
        var fallbackCalls = 0
        val resolver = SystemDnsResolver(
            rawLookup = { _, _ -> throw IOException("transaction-mismatch") },
            lookup = {
                fallbackCalls += 1
                arrayOf(InetAddress.getByName("93.184.216.34"))
            }
        )

        assertThrows(IOException::class.java) {
            runBlocking { resolver.resolve(query, DnsPacket.parseQuestion(query)!!) }
        }
        assertEquals(0, fallbackCalls)
    }

    private fun resolver(provider: String, invoked: () -> Unit): DnsResolver {
        return object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                invoked()
                return DnsResolveResult(DnsPacket.noDataResponse(query, query.size), provider, 0L, false)
            }
        }
    }

    private fun readUnsignedShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }
}
