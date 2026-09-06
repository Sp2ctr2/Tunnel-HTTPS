package com.tunnelvpn.app

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProtectionEngineTest {
    @Test
    fun malformedIpv4AndUdpDeclaredLengthsAreRejected() = runBlocking {
        val query = dnsQuery("example.com", DnsPacket.TYPE_A)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43010,
            destinationPort = 53,
            payload = query
        )
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                error("resolver must not receive malformed packets")
            }
        }
        val engine = DnsProtectionEngine(resolver, DiagnosticsState())
        val wrongIpLength = packet.copyOf().also { put16(it, 2, it.size - 1) }
        val wrongUdpLength = packet.copyOf().also { put16(it, 24, u16(it, 24) - 1) }

        assertNull(engine.handleIpv4Packet(wrongIpLength, wrongIpLength.size))
        assertNull(engine.handleIpv4Packet(wrongUdpLength, wrongUdpLength.size))
    }

    @Test
    fun resolverFailureDoesNotRetainRawHostname() = runBlocking {
        val diagnostics = DiagnosticsState()
        val query = dnsQuery("sensitive.private.example", DnsPacket.TYPE_A)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43011,
            destinationPort = 53,
            payload = query
        )
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                throw java.io.IOException("sensitive.private.example failed")
            }
        }

        DnsProtectionEngine(resolver, diagnostics).handleIpv4Packet(packet, packet.size)

        assertFalse(diagnostics.toJson().contains("sensitive.private.example"))
        assertTrue(diagnostics.toJson().contains("resolver-io"))
    }

    @Test
    fun resolverCapacityReturnsServFailInsteadOfDroppingTheQuery() {
        val diagnostics = DiagnosticsState()
        val query = dnsQuery("example.com", DnsPacket.TYPE_A)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43010,
            destinationPort = 53,
            payload = query
        )
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    error("capacity response must not call the resolver")
                }
            },
            diagnostics = diagnostics
        )

        val response = engine.buildCapacityServFailIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertEquals(2, response!![31].toInt() and 0x0f)
        assertEquals(1L, diagnostics.dnsFailures.get())
    }

    @Test
    fun dnsQueryIsAnsweredWithReversedUdpFlow() = runBlocking {
        val diagnostics = DiagnosticsState()
        val query = dnsQuery("example.com", 1)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43000,
            destinationPort = 53,
            payload = query
        )
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                return DnsResolveResult(DnsPacket.noDataResponse(query, query.size), "fake-doh", 7, cacheHit = false)
            }
        }

        val response = DnsProtectionEngine(resolver, diagnostics).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        response!!
        assertEquals(53, u16(response, 20))
        assertEquals(43000, u16(response, 22))
        assertEquals(10, response[12].toInt() and 0xff)
        assertEquals(111, response[13].toInt() and 0xff)
        assertEquals(0, response[14].toInt() and 0xff)
        assertEquals(1, response[15].toInt() and 0xff)
        assertEquals("fake-doh", diagnostics.dnsProviderInUse)
    }

    @Test
    fun unsupportedEdnsVersionReturnsBadversWithoutCallingResolver() = runBlocking {
        val base = dnsQuery("example.com", DnsPacket.TYPE_A)
        val query = base.copyOf(base.size + 11).also {
            it[11] = 1
            val offset = base.size
            it[offset + 2] = 41
            it[offset + 3] = 0x04
            it[offset + 4] = 0xd0.toByte()
            it[offset + 6] = 1
        }
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43020,
            destinationPort = 53,
            payload = query
        )
        var resolverCalled = false
        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalled = true
                    error("unsupported EDNS must be answered locally")
                }
            },
            diagnostics = DiagnosticsState()
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertFalse(resolverCalled)
        assertEquals(16, Dns64Packet.responseCode(response!!.copyOfRange(28, response.size)))
    }

    @Test
    fun oversizedResolverResponseBecomesBoundedTruncatedReply() = runBlocking {
        val diagnostics = DiagnosticsState()
        val query = dnsQuery("example.com", DnsPacket.TYPE_A)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43012,
            destinationPort = 53,
            payload = query
        )
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                return DnsResolveResult(ByteArray(65_508), "oversized", 1, cacheHit = false)
            }
        }

        val response = DnsProtectionEngine(resolver, diagnostics).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertTrue(response!!.size < 512)
        assertEquals(0, response[31].toInt() and 0x0f)
        assertEquals(0x02, response[30].toInt() and 0x02)
        assertTrue(diagnostics.toJson().contains("resolver-response-oversized"))
    }

    @Test
    fun oversizedPrivateTurboAnswerCannotPublishAMapping() = runBlocking {
        val query = dnsQuery("example.com", DnsPacket.TYPE_A)
        val mapper = TurboDomainMapper()
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43013,
            destinationPort = 53,
            payload = query
        )
        val oversized = oversizedAResponse(query, byteArrayOf(10, 0, 0, 1))
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(oversized, "oversized", 1, cacheHit = false)
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = mapper,
            turboCandidateDomains = listOf("example.com")
        )

        val response = engine.handleIpv4Packet(packet, packet.size)!!

        assertEquals(0x02, response[30].toInt() and 0x02)
        assertNull(mapper.lookupVirtual(byteArrayOf(192.toByte(), 0, 2, 2)))
    }

    @Test
    fun nonTurboAaaaQueryUsesResolver() = runBlocking {
        val diagnostics = DiagnosticsState()
        var resolverCalled = false
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43001,
            destinationPort = 53,
            payload = dnsQuery("example.com", DnsPacket.TYPE_AAAA)
        )
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                resolverCalled = true
                return DnsResolveResult(
                    DnsPacket.noDataResponse(query, query.size),
                    "fake-doh",
                    3,
                    cacheHit = false
                )
            }
        }

        val response = DnsProtectionEngine(resolver, diagnostics).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertTrue(resolverCalled)
        assertEquals("fake-doh", diagnostics.dnsProviderInUse)
    }

    @Test
    fun ipv6DnsQueryUsesTheSameProtectedResolverAndReturnsAValidIpv6Datagram() = runBlocking {
        val source = byteArrayOf(0xfd.toByte(), 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
        val destination = byteArrayOf(0xfd.toByte(), 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val query = dnsQuery("example.com", DnsPacket.TYPE_AAAA)
        val packet = ipv6UdpPacket(source, destination, 43014, 53, query)
        val diagnostics = DiagnosticsState()
        val expectedAddress = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9
        )

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        DnsPacket.aaaaRecordResponse(query, query.size, expectedAddress),
                        "fake-doh-v6",
                        4,
                        cacheHit = false
                    )
                }
            },
            diagnostics = diagnostics
        ).handleIpv6Packet(packet, packet.size)

        assertNotNull(response)
        response!!
        assertEquals(6, (response[0].toInt() ushr 4) and 0x0f)
        assertEquals(17, response[6].toInt() and 0xff)
        assertEquals(53, u16(response, 40))
        assertEquals(43014, u16(response, 42))
        assertTrue(response.copyOfRange(8, 24).contentEquals(destination))
        assertTrue(response.copyOfRange(24, 40).contentEquals(source))
        assertEquals(0, ipv6UpperLayerChecksum(response, 40, response.size - 40, 17))
        assertEquals("fake-doh-v6", diagnostics.dnsProviderInUse)
    }

    @Test
    fun ipv6DnsRejectsZeroAndCorruptUdpChecksumsBeforeResolution() = runBlocking {
        var resolverCalls = 0
        val query = dnsQuery("example.com", DnsPacket.TYPE_AAAA)
        val valid = ipv6UdpPacket(
            ByteArray(16).also { it[0] = 0x20; it[15] = 2 },
            ByteArray(16).also { it[0] = 0x20; it[15] = 1 },
            43_014,
            53,
            query
        )
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalls += 1
                    error("invalid datagram reached resolver")
                }
            },
            diagnostics = DiagnosticsState()
        )
        val zero = valid.copyOf().also { put16(it, 46, 0) }
        val corrupt = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertNull(engine.handleIpv6Packet(zero, zero.size))
        assertNull(engine.handleIpv6Packet(corrupt, corrupt.size))
        assertEquals(0, resolverCalls)
    }

    @Test
    fun turboCandidateAaaaIsPreservedForIpv6OnlyAndNat64Networks() = runBlocking {
        val query = dnsQuery("example.com", DnsPacket.TYPE_AAAA)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43015,
            destinationPort = 53,
            payload = query
        )
        var resolverCalled = false
        val address = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 7 }

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalled = true
                    return DnsResolveResult(
                        DnsPacket.aaaaRecordResponse(query, query.size, address),
                        "fake-doh",
                        2,
                        cacheHit = false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = TurboDomainMapper(),
            turboCandidateDomains = listOf("example.com")
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertTrue(resolverCalled)
        assertEquals(1, u16(response!!, 28 + 6))
    }

    @Test
    fun dns64SynthesizesAaaaOnlyFromARealLinkPrefixAndPublicARecord() = runBlocking {
        val query = dnsQuery("example.com", DnsPacket.TYPE_AAAA)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43016,
            destinationPort = 53,
            payload = query
        )
        val prefix = Nat64Prefix.from(java.net.InetAddress.getByName("64:ff9b::").address, 96)!!
        val queries = mutableListOf<Int>()
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    queries += question.type
                    val response = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.noDataResponse(query, query.size)
                    } else {
                        DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 8, 8))
                    }
                    return DnsResolveResult(response, "fake-doh", 1, cacheHit = false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix }
        )

        val response = engine.handleIpv4Packet(packet, packet.size)!!
        val dns = response.copyOfRange(28, response.size)

        assertEquals(listOf(DnsPacket.TYPE_AAAA, DnsPacket.TYPE_A), queries)
        assertTrue(
            DnsPacket.extractAaaaRecords(dns).single()
                .contentEquals(java.net.InetAddress.getByName("64:ff9b::808:808").address)
        )
    }

    @Test
    fun dns64RejectsPrivatePref64HintsCopiedFromTheFollowupAResponse() = runBlocking {
        val query = dnsQuery("victim.example", DnsPacket.TYPE_AAAA)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43021,
            destinationPort = 53,
            payload = query
        )
        val prefix = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 96)!!
        val privateHint = prefix.synthesize(byteArrayOf(10, 0, 0, 1))
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    val response = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.noDataResponse(query, query.size)
                    } else {
                        aResponseWithAdditionalHttpsHint(
                            query,
                            byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
                            privateHint
                        )
                    }
                    return DnsResolveResult(response, "fake-doh", 1, false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) }
        )

        val response = engine.handleIpv4Packet(packet, packet.size)!!

        assertEquals(2, Dns64Packet.responseCode(response.copyOfRange(28, response.size)))
    }

    @Test
    fun unknownPref64StateRejectsAddressBearingAnyResponses() = runBlocking {
        val query = dnsQuery("victim.example", 255)
        val unknownPrefix = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 96)!!
        val hiddenPrivate = unknownPrefix.synthesize(byteArrayOf(10, 0, 0, 1))
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        singleRecordResponse(query, DnsPacket.TYPE_AAAA, hiddenPrivate),
                        "fake-doh",
                        1,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            nat64DiscoveryRequiredProvider = { true }
        )

        val response = engine.handleTcpMessage(query)!!

        assertEquals(2, Dns64Packet.responseCode(response))
    }

    @Test
    fun unknownPref64StateStillAllowsAddressFreeAndIpv4OnlyAnswers() = runBlocking {
        val query = dnsQuery("victim.example", DnsPacket.TYPE_A)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        DnsPacket.aRecordResponse(query, query.size, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)),
                        "fake-doh",
                        1,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            nat64DiscoveryRequiredProvider = { true }
        )

        val response = engine.handleTcpMessage(query)!!

        assertEquals(0, Dns64Packet.responseCode(response))
        assertEquals(1, DnsPacket.extractARecords(response).size)
    }

    @Test
    fun validatedNetworkSuffixFallbackMayReturnPrivateEnterpriseAddresses() = runBlocking {
        val query = dnsQuery("service.corp.example", DnsPacket.TYPE_A)
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        DnsPacket.aRecordResponse(query, query.size, byteArrayOf(10, 20, 30, 40)),
                        "system-dns-raw",
                        1,
                        false,
                        localScope = true
                    )
                }
            },
            diagnostics = DiagnosticsState()
        )

        val response = engine.handleTcpMessage(query)!!

        assertEquals(0, Dns64Packet.responseCode(response))
        assertEquals(10, DnsPacket.extractARecords(response).single()[0].toInt() and 0xff)
    }

    @Test
    fun nonInternetClassResponseCannotCrossANetworkGenerationBoundary() = runBlocking {
        val query = dnsQuery("version.bind", DnsPacket.TYPE_A).also { put16(it, it.size - 2, 3) }
        var generationReads = 0
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        DnsPacket.noDataResponse(query, query.size),
                        "test",
                        0L,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            networkGenerationProvider = {
                generationReads += 1
                if (generationReads <= 3) 0L else 2L
            }
        )

        val response = engine.handleTcpMessage(query)!!

        assertEquals(2, Dns64Packet.responseCode(response))
    }

    @Test
    fun dns64DoesNotPublishAnAddressAcrossAPrefixGenerationChange() = runBlocking {
        val query = dnsQuery("example.com", DnsPacket.TYPE_AAAA)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43017,
            destinationPort = 53,
            payload = query
        )
        var generation = 4L
        val prefix = Nat64Prefix.from(java.net.InetAddress.getByName("64:ff9b::").address, 96)!!
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    val response = if (question.type == DnsPacket.TYPE_AAAA) {
                        DnsPacket.noDataResponse(query, query.size)
                    } else {
                        generation += 1L
                        DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 8, 8))
                    }
                    return DnsResolveResult(response, "handover", 1, cacheHit = false)
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixProvider = { prefix },
            nat64GenerationProvider = { generation }
        )

        val response = engine.handleIpv4Packet(packet, packet.size)!!
        val dns = response.copyOfRange(28, response.size)

        assertTrue(DnsPacket.extractAaaaRecords(dns).isEmpty())
        assertEquals(0, u16(dns, 6))
    }

    @Test
    fun sniProtectionSuppressesAaaaWithoutCallingResolver() = runBlocking {
        val diagnostics = DiagnosticsState()
        var resolverCalled = false
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43013,
            destinationPort = 53,
            payload = dnsQuery("example.com", DnsPacket.TYPE_AAAA)
        )
        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalled = true
                    error("AAAA should be suppressed locally while SNI protection is active")
                }
            },
            diagnostics = diagnostics,
            suppressIpv6Records = true
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertFalse(resolverCalled)
        val dns = response!!.copyOfRange(28, response.size)
        assertEquals(0, u16(dns, 6))
        assertEquals(0, u16(dns, 8))
    }

    @Test
    fun blockedAQueryReturnsNxDomainWithoutResolver() = runBlocking {
        val diagnostics = DiagnosticsState()
        var resolverCalled = false
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43002,
            destinationPort = 53,
            payload = dnsQuery("ads.example.com", DnsPacket.TYPE_A)
        )
        val resolver = object : DnsResolver {
            override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                resolverCalled = true
                error("blocked query should not use DoH")
            }
        }

        val response = DnsProtectionEngine(
            resolver,
            diagnostics,
            DomainBlocker(enabled = true, blockedExact = setOf("ads.example.com"), blockedSuffixes = emptySet())
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertTrue(!resolverCalled)
        response!!
        val dnsOffset = 28


        assertEquals(3, response[dnsOffset + 3].toInt() and 0x0f)
        assertEquals(0, u16(response, dnsOffset + 6))
        assertTrue(diagnostics.toJson().contains("\"blockedDnsQueries\": 1"))
    }

    @Test
    fun blockedAaaaQueryReturnsNoData() = runBlocking {
        val diagnostics = DiagnosticsState()
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43003,
            destinationPort = 53,
            payload = dnsQuery("pixel.tracker.example", DnsPacket.TYPE_AAAA)
        )

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    error("blocked query should not use DoH")
                }
            },
            diagnostics = diagnostics,
            domainBlocker = DomainBlocker(enabled = true, blockedExact = emptySet(), blockedSuffixes = setOf("tracker.example"))
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        response!!
        val dnsOffset = 28
        assertEquals(0, u16(response, dnsOffset + 6))
        assertTrue(diagnostics.toJson().contains("\"blockedDnsQueries\": 1"))
    }

    @Test
    fun turboSuppressesHttpsSvcbWithoutCallingResolver() = runBlocking {
        val diagnostics = DiagnosticsState()
        var resolverCalled = false
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43004,
            destinationPort = 53,
            payload = dnsQuery("example.com", DnsPacket.TYPE_HTTPS)
        )
        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalled = true
                    error("HTTPS/SVCB should be suppressed locally in Turbo mode")
                }
            },
            diagnostics = diagnostics,
            suppressHttpsSvcbRecords = true
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertFalse(resolverCalled)
        assertTrue(diagnostics.toJson().contains("\"turboHttpsSvcbRecordsSuppressed\": 1"))
    }

    @Test
    fun ordinaryHttpsSvcbUsesResolverSoEchMetadataIsPreserved() = runBlocking {
        var resolverCalled = false
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43016,
            destinationPort = 53,
            payload = dnsQuery("www.example.com", DnsPacket.TYPE_HTTPS)
        )

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalled = true
                    return DnsResolveResult(DnsPacket.noDataResponse(query, query.size), "fake-doh", 2, false)
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = TurboDomainMapper(),
            turboCandidateDomains = listOf("warning.or.kr")
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertTrue(resolverCalled)
    }

    @Test
    fun activeGlobalPref64CannotHidePrivateIpv4InHttpsHints() = runBlocking {
        val prefix = Nat64Prefix.from(InetAddress.getByName("2001:4860:64::").address, 96)!!
        val privateHint = prefix.synthesize(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        val query = dnsQuery("www.example.com", DnsPacket.TYPE_HTTPS)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43019,
            destinationPort = 53,
            payload = query
        )
        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        singleRecordResponse(query, DnsPacket.TYPE_HTTPS, svcbHintRdata(6, privateHint)),
                        "fake-doh",
                        1,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            nat64PrefixesProvider = { listOf(prefix) }
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertEquals(2, Dns64Packet.responseCode(response!!.copyOfRange(28, response.size)))
    }

    @Test
    fun ordinarySvcbUsesResolverSoDesignatedResolverMetadataIsPreserved() = runBlocking {
        var resolverCalled = false
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43017,
            destinationPort = 53,
            payload = dnsQuery("_dns.resolver.example", DnsPacket.TYPE_SVCB)
        )

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalled = question.type == DnsPacket.TYPE_SVCB
                    return DnsResolveResult(DnsPacket.noDataResponse(query, query.size), "fake-doh", 2, false)
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = TurboDomainMapper(),
            turboCandidateDomains = listOf("warning.or.kr")
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertTrue(resolverCalled)
    }

    @Test
    fun configuredCandidateHttpsSvcbIsSuppressedWhenSelectiveMapperIsAvailable() = runBlocking {
        val diagnostics = DiagnosticsState()
        var resolverCalled = false
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43009,
            destinationPort = 53,
            payload = dnsQuery("warning.or.kr", DnsPacket.TYPE_HTTPS)
        )

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalled = true
                    error("warning HTTPS/SVCB should be suppressed locally")
                }
            },
            diagnostics = diagnostics,
            turboDomainMapper = TurboDomainMapper(),
            turboCandidateDomains = listOf("warning.or.kr")
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertFalse(resolverCalled)
        val dns = response!!.copyOfRange(28, response.size)
        assertEquals(0, u16(dns, 6))
        assertTrue(diagnostics.toJson().contains("\"turboHttpsSvcbRecordsSuppressed\": 1"))
    }

    @Test
    fun configuredCandidateSvcbIsSuppressedWithItsHttpsRecord() = runBlocking {
        var resolverCalled = false
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43018,
            destinationPort = 53,
            payload = dnsQuery("warning.or.kr", DnsPacket.TYPE_SVCB)
        )

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    resolverCalled = true
                    return DnsResolveResult(DnsPacket.noDataResponse(query, query.size), "unexpected", 0, false)
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = TurboDomainMapper(),
            turboCandidateDomains = listOf("warning.or.kr")
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        assertFalse(resolverCalled)
        assertEquals(0, u16(response!!, 28 + 6))
    }

    @Test
    fun turboAQueryMapsRealAddressToVirtualAddress() = runBlocking {
        val diagnostics = DiagnosticsState()
        val mapper = TurboDomainMapper()
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43005,
            destinationPort = 53,
            payload = dnsQuery("example.com", DnsPacket.TYPE_A)
        )
        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        response = multiARecordResponse(
                            query,
                            byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
                            byteArrayOf(2, 2, 2, 2)
                        ),
                        provider = "fake-doh",
                        elapsedMs = 3,
                        cacheHit = false
                    )
                }
            },
            diagnostics = diagnostics,
            turboDomainMapper = mapper,
            turboCandidateDomains = listOf("example.com")
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        response!!
        val dns = response.copyOfRange(28, response.size)
        val virtual = DnsPacket.extractARecords(dns).first()
        assertEquals(192, virtual[0].toInt() and 0xff)
        assertEquals(0, virtual[1].toInt() and 0xff)
        assertEquals(2, virtual[2].toInt() and 0xff)
        val mapping = mapper.lookupVirtual(virtual)
        assertNotNull(mapping)
        assertEquals(listOf("93.184.216.34", "2.2.2.2"), mapping!!.realInetAddresses.map { it.hostAddress })
    }

    @Test
    fun networkChangeDuringTurboResolutionCannotPublishAMapping() = runBlocking {
        val generation = java.util.concurrent.atomic.AtomicLong(0L)
        val mapper = TurboDomainMapper(networkGenerationProvider = generation::get)
        val query = dnsQuery("example.com", DnsPacket.TYPE_A)
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43014,
            destinationPort = 53,
            payload = query
        )
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    generation.set(2L)
                    return DnsResolveResult(
                        DnsPacket.aRecordResponse(query, query.size, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)),
                        "test",
                        1L,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = mapper,
            turboCandidateDomains = listOf("example.com"),
            networkGenerationProvider = generation::get
        )

        val response = engine.handleIpv4Packet(packet, packet.size)!!

        assertEquals(2, response[31].toInt() and 0x0f)
        assertNull(mapper.lookupVirtual(byteArrayOf(192.toByte(), 0, 2, 2)))
    }

    @Test
    fun parsedTurboAnswerCannotCommitAfterGenerationReset() {
        val generation = AtomicLong(0L)
        val mapper = TurboDomainMapper(networkGenerationProvider = generation::get)
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x7151)!!
        val beforeCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>()
        val engine = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        DnsPacket.aRecordResponse(
                            query,
                            query.size,
                            byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
                        ),
                        "test",
                        1L,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = mapper,
            turboCandidateDomains = listOf("example.com"),
            networkGenerationProvider = generation::get,
            beforeTurboMappingCommit = {
                beforeCommit.countDown()
                assertTrue(releaseCommit.await(2, TimeUnit.SECONDS))
            }
        )
        val worker = thread(start = true, isDaemon = true) {
            result.set(runBlocking { engine.handleTcpMessage(query) })
        }

        assertTrue(beforeCommit.await(2, TimeUnit.SECONDS))
        generation.set(2L)
        mapper.updateNetworkGeneration(2L, nowMs = 1_001)
        releaseCommit.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertEquals(2, Dns64Packet.responseCode(result.get()!!))
        assertNull(mapper.lookupVirtual(byteArrayOf(192.toByte(), 0, 2, 2), nowMs = 1_002))
    }

    @Test
    fun securityHardStopsNeverPublishTurboMappings() = runBlocking {
        listOf(4, 6, 15, 16, 17).forEach { ede ->
            val query = DnsPacket.query("hard-stop-$ede.example", DnsPacket.TYPE_A, 0x7200 + ede)!!
            val mapper = TurboDomainMapper()
            val upstream = withEde(
                DnsPacket.aRecordResponse(
                    query,
                    query.size,
                    byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
                ),
                ede
            )
            val result = DnsProtectionEngine(
                resolver = object : DnsResolver {
                    override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                        return DnsResolveResult(upstream, "test", 1L, false)
                    }
                },
                diagnostics = DiagnosticsState(),
                turboDomainMapper = mapper,
                turboCandidateDomains = listOf("hard-stop-$ede.example")
            ).handleTcpMessage(query)!!

            assertEquals(2, Dns64Packet.responseCode(result))
            assertNull(mapper.lookupVirtual(byteArrayOf(192.toByte(), 0, 2, 2)))
        }

        val query = DnsPacket.query("rebind.example", DnsPacket.TYPE_A, 0x7250)!!
        val mapper = TurboDomainMapper()
        val result = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        DnsPacket.aRecordResponse(query, query.size, byteArrayOf(192.toByte(), 168.toByte(), 1, 1)),
                        "test",
                        1L,
                        false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = mapper,
            turboCandidateDomains = listOf("rebind.example")
        ).handleTcpMessage(query)!!

        assertEquals(2, Dns64Packet.responseCode(result))
        assertNull(mapper.lookupVirtual(byteArrayOf(192.toByte(), 0, 2, 2)))
    }

    @Test
    fun turboAQueryLeavesNonCandidateAddressUnchanged() = runBlocking {
        val diagnostics = DiagnosticsState()
        val mapper = TurboDomainMapper()
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43006,
            destinationPort = 53,
            payload = dnsQuery("google.com", DnsPacket.TYPE_A)
        )
        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 8, 8)),
                        provider = "fake-doh",
                        elapsedMs = 3,
                        cacheHit = false
                    )
                }
            },
            diagnostics = diagnostics,
            turboDomainMapper = mapper,
            turboCandidateDomains = listOf("example.com")
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        response!!
        val dns = response.copyOfRange(28, response.size)
        val real = DnsPacket.extractARecords(dns).first()
        assertEquals(8, real[0].toInt() and 0xff)
        assertEquals(8, real[1].toInt() and 0xff)
        assertEquals(8, real[2].toInt() and 0xff)
        assertEquals(8, real[3].toInt() and 0xff)
        assertFalse(mapper.isVirtualAddress(real))
    }

    @Test
    fun nonCandidateDomainKeepsResolverAddress() = runBlocking {
        val mapper = TurboDomainMapper()
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43007,
            destinationPort = 53,
            payload = dnsQuery("restricted.example", DnsPacket.TYPE_A)
        )

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(2, 2, 2, 2)),
                        provider = "fake-doh",
                        elapsedMs = 2,
                        cacheHit = false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = mapper
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        val real = DnsPacket.extractARecords(response!!.copyOfRange(28, response.size)).first()
        assertEquals(2, real[0].toInt() and 0xff)
        assertEquals(2, real[1].toInt() and 0xff)
        assertEquals(2, real[2].toInt() and 0xff)
        assertEquals(2, real[3].toInt() and 0xff)
    }

    @Test
    fun ordinaryGoogleDomainKeepsResolverAddress() = runBlocking {
        val mapper = TurboDomainMapper()
        val packet = ipv4UdpPacket(
            source = byteArrayOf(10, 0, 0, 5),
            destination = byteArrayOf(10, 111, 0, 1),
            sourcePort = 43008,
            destinationPort = 53,
            payload = dnsQuery("www.google.com", DnsPacket.TYPE_A)
        )

        val response = DnsProtectionEngine(
            resolver = object : DnsResolver {
                override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
                    return DnsResolveResult(
                        response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(8, 8, 4, 4)),
                        provider = "fake-doh",
                        elapsedMs = 2,
                        cacheHit = false
                    )
                }
            },
            diagnostics = DiagnosticsState(),
            turboDomainMapper = mapper
        ).handleIpv4Packet(packet, packet.size)

        assertNotNull(response)
        val real = DnsPacket.extractARecords(response!!.copyOfRange(28, response.size)).first()
        assertEquals(8, real[0].toInt() and 0xff)
        assertEquals(8, real[1].toInt() and 0xff)
        assertEquals(4, real[2].toInt() and 0xff)
        assertEquals(4, real[3].toInt() and 0xff)
    }

    private fun dnsQuery(host: String, type: Int): ByteArray {
        val labels = host.split(".")
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val data = ByteArray(size)
        data[0] = 0x12
        data[1] = 0x34
        data[2] = 0x01
        data[5] = 0x01
        var offset = 12
        labels.forEach { label ->
            data[offset++] = label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).copyInto(data, offset)
            offset += label.length
        }
        data[offset++] = 0
        put16(data, offset, type)
        put16(data, offset + 2, 1)
        return data
    }

    private fun ipv4UdpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        put16(packet, 2, totalLength)
        packet[8] = 64
        packet[9] = 17
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)
        put16(packet, 20, sourcePort)
        put16(packet, 22, destinationPort)
        put16(packet, 24, 8 + payload.size)
        payload.copyInto(packet, 28)
        return packet
    }

    private fun ipv6UdpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)
        packet[0] = 0x60
        put16(packet, 4, udpLength)
        packet[6] = 17
        packet[7] = 64
        source.copyInto(packet, 8)
        destination.copyInto(packet, 24)
        put16(packet, 40, sourcePort)
        put16(packet, 42, destinationPort)
        put16(packet, 44, udpLength)
        payload.copyInto(packet, 48)
        val checksum = ipv6UpperLayerChecksum(packet, 40, udpLength, 17)
        put16(packet, 46, if (checksum == 0) 0xffff else checksum)
        return packet
    }

    private fun ipv6UpperLayerChecksum(packet: ByteArray, offset: Int, length: Int, protocol: Int): Int {
        var sum = checksumSum(packet, 8, 32)
        sum += length.toLong()
        sum += protocol.toLong()
        sum += checksumSum(packet, offset, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun checksumSum(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += u16(data, index).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        return sum
    }

    private fun multiARecordResponse(query: ByteArray, vararg addresses: ByteArray): ByteArray {
        val question = DnsPacket.parseQuestion(query)!!
        val answerSize = 16
        val response = ByteArray(question.questionEnd + (addresses.size * answerSize))
        query.copyInto(response, 0, endIndex = question.questionEnd)
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        put16(response, 6, addresses.size)
        var offset = question.questionEnd
        addresses.forEach { address ->
            response[offset++] = 0xc0.toByte()
            response[offset++] = 0x0c
            put16(response, offset, DnsPacket.TYPE_A)
            offset += 2
            put16(response, offset, 1)
            offset += 2
            put32(response, offset, 30)
            offset += 4
            put16(response, offset, 4)
            offset += 2
            address.copyInto(response, offset)
            offset += 4
        }
        return response
    }

    private fun singleRecordResponse(query: ByteArray, type: Int, rdata: ByteArray): ByteArray {
        val question = DnsPacket.parseQuestion(query)!!
        val response = ByteArray(question.questionEnd + 12 + rdata.size)
        query.copyInto(response, 0, endIndex = question.questionEnd)
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        put16(response, 6, 1)
        var offset = question.questionEnd
        response[offset++] = 0xc0.toByte()
        response[offset++] = 0x0c
        put16(response, offset, type)
        offset += 2
        put16(response, offset, 1)
        offset += 2
        put32(response, offset, 30)
        offset += 4
        put16(response, offset, rdata.size)
        offset += 2
        rdata.copyInto(response, offset)
        return response
    }

    private fun oversizedAResponse(query: ByteArray, address: ByteArray): ByteArray {
        val answer = singleRecordResponse(query, DnsPacket.TYPE_A, address)
        val padding = ByteArray(480)
        val response = answer.copyOf(answer.size + 12 + padding.size)
        put16(response, 10, 1)
        var offset = answer.size
        response[offset++] = 0xc0.toByte()
        response[offset++] = 0x0c
        put16(response, offset, 16)
        offset += 2
        put16(response, offset, 1)
        offset += 2
        put32(response, offset, 30)
        offset += 4
        put16(response, offset, padding.size)
        offset += 2
        padding.copyInto(response, offset)
        return response
    }

    private fun aResponseWithAdditionalHttpsHint(
        query: ByteArray,
        address: ByteArray,
        hint: ByteArray
    ): ByteArray {
        val answer = singleRecordResponse(query, DnsPacket.TYPE_A, address)
        val rdata = svcbHintRdata(6, hint)
        val response = answer.copyOf(answer.size + 12 + rdata.size)
        put16(response, 10, 1)
        var offset = answer.size
        response[offset++] = 0xc0.toByte()
        response[offset++] = 0x0c
        put16(response, offset, DnsPacket.TYPE_HTTPS)
        offset += 2
        put16(response, offset, 1)
        offset += 2
        put32(response, offset, 30)
        offset += 4
        put16(response, offset, rdata.size)
        offset += 2
        rdata.copyInto(response, offset)
        return response
    }

    private fun svcbHintRdata(key: Int, value: ByteArray): ByteArray {
        val rdata = ByteArray(7 + value.size)
        rdata[1] = 1
        put16(rdata, 3, key)
        put16(rdata, 5, value.size)
        value.copyInto(rdata, 7)
        return rdata
    }

    private fun withEde(response: ByteArray, infoCode: Int): ByteArray {
        val result = response.copyOf(response.size + 17)
        put16(result, 10, 1)
        var offset = response.size
        result[offset++] = 0
        put16(result, offset, 41)
        offset += 2
        put16(result, offset, 1232)
        offset += 2
        put32(result, offset, 0)
        offset += 4
        put16(result, offset, 6)
        offset += 2
        put16(result, offset, 15)
        offset += 2
        put16(result, offset, 2)
        offset += 2
        put16(result, offset, infoCode)
        return result
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xff).toByte()
        data[offset + 1] = ((value ushr 16) and 0xff).toByte()
        data[offset + 2] = ((value ushr 8) and 0xff).toByte()
        data[offset + 3] = (value and 0xff).toByte()
    }
}
