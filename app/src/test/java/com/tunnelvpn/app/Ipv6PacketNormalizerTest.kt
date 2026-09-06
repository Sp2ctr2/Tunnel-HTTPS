package com.tunnelvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Ipv6PacketNormalizerTest {
    @Test
    fun stripsSafeDestinationOptionsWithoutChangingTheUdpChecksum() {
        val udp = udpDatagram(ByteArray(19) { (it + 1).toByte() })
        val extension = ByteArray(8).also { it[0] = UDP.toByte() }
        val packet = ipv6Packet(DESTINATION_OPTIONS, extension + udp)
        setUdpChecksum(packet, 48, udp.size)
        val expectedChecksum = u16(packet, 54)

        val result = Ipv6PacketNormalizer().process(packet)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        val normalized = (result as Ipv6PacketNormalizer.Result.Ready).packet
        assertEquals(UDP, normalized[6].toInt() and 0xff)
        assertEquals(udp.size, u16(normalized, 4))
        assertArrayEquals(packet.copyOfRange(48, packet.size), normalized.copyOfRange(40, normalized.size))
        assertEquals(expectedChecksum, u16(normalized, 46))
        assertEquals(0, upperLayerChecksum(normalized, 40, normalized.size - 40, UDP))
        assertArrayEquals(packet, (result as Ipv6PacketNormalizer.Result.Ready).invokingPacket)
    }

    @Test
    fun normalizesABoundedHopRoutingAndDestinationChainForDnsDispatch() {
        val udp = udpDatagram(ByteArray(12)).also { put16(it, 2, 53) }
        val destination = ByteArray(8).also { it[0] = UDP.toByte() }
        val routing = ByteArray(8).also { it[0] = DESTINATION_OPTIONS.toByte() }
        val hop = ByteArray(8).also { it[0] = ROUTING.toByte() }
        val packet = ipv6Packet(HOP_BY_HOP, hop + routing + destination + udp)
        setUdpChecksum(packet, 64, udp.size)

        val result = Ipv6PacketNormalizer().process(packet)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        val normalized = (result as Ipv6PacketNormalizer.Result.Ready).packet
        assertEquals(UDP, normalized[6].toInt() and 0xff)
        assertTrue(LocalProtectionEngine.isIpv6DnsTransportPacket(normalized, normalized.size))
        assertTrue(!LocalProtectionEngine.shouldDropIpv6BeforeForwarding(normalized, normalized.size))
    }

    @Test
    fun reassemblesOutOfOrderFragmentsAndThenRemovesTheFragmentHeader() {
        val udp = udpDatagram(ByteArray(16) { (0xa0 + it).toByte() })
        val complete = ipv6Packet(UDP, udp)
        setUdpChecksum(complete, 40, udp.size)
        val checksummedUdp = complete.copyOfRange(40, complete.size)
        val first = fragmentPacket(checksummedUdp.copyOfRange(0, 16), offset = 0, more = true, id = 0x10203040)
        val last = fragmentPacket(checksummedUdp.copyOfRange(16, checksummedUdp.size), offset = 16, more = false, id = 0x10203040)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Pending)
        val result = normalizer.process(first)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        val packet = (result as Ipv6PacketNormalizer.Result.Ready).packet
        assertArrayEquals(complete, packet)
        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
        assertEquals(0, upperLayerChecksum(packet, 40, packet.size - 40, UDP))
    }

    @Test
    fun reassemblesAcrossUnfragmentableAndFragmentableExtensionHeaders() {
        val udp = udpDatagram(ByteArray(16) { (0x30 + it).toByte() })
        val expected = ipv6Packet(UDP, udp)
        setUdpChecksum(expected, 40, udp.size)
        val destination = ByteArray(8).also { it[0] = UDP.toByte() }
        val fragmentable = destination + expected.copyOfRange(40, expected.size)
        val first = fragmentPacketWithHop(
            fragmentable.copyOfRange(0, 16),
            offset = 0,
            more = true,
            id = 0x50607080
        )
        val last = fragmentPacketWithHop(
            fragmentable.copyOfRange(16, fragmentable.size),
            offset = 16,
            more = false,
            id = 0x50607080
        )
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Pending)
        val result = normalizer.process(first)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(expected, (result as Ipv6PacketNormalizer.Result.Ready).packet)
        assertArrayEquals(
            packetWithExtensions(
                listOf(HOP_BY_HOP, DESTINATION_OPTIONS),
                expected.copyOfRange(40, expected.size)
            ),
            result.invokingPacket
        )
    }

    @Test
    fun removesAnAtomicFragmentHeaderWithoutBuffering() {
        val udp = udpDatagram(ByteArray(8))
        val expected = ipv6Packet(UDP, udp)
        setUdpChecksum(expected, 40, udp.size)
        val packet = fragmentPacket(
            expected.copyOfRange(40, expected.size),
            offset = 0,
            more = false,
            id = 0x11223344
        )
        val normalizer = Ipv6PacketNormalizer()

        val result = normalizer.process(packet)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        val ready = result as Ipv6PacketNormalizer.Result.Ready
        assertArrayEquals(expected, ready.packet)
        assertArrayEquals(packet, ready.invokingPacket)
        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun rejectsAnOverlappingFragmentSetAndReleasesItsBudget() {
        val bytes = ByteArray(24) { it.toByte() }
        val first = fragmentPacket(bytes.copyOfRange(0, 16), offset = 0, more = true, id = 7)
        val overlap = fragmentPacket(bytes.copyOfRange(8, 24), offset = 8, more = false, id = 7)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(overlap) is Ipv6PacketNormalizer.Result.Rejected)
        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun offsetZeroFragmentControlsNextHeaderAndPerFragmentHeaders() {
        val expected = ipv6Packet(UDP, udpDatagram(ByteArray(16) { (it + 7).toByte() }))
        setUdpChecksum(expected, 40, expected.size - 40)
        val upper = expected.copyOfRange(40, expected.size)
        val first = fragmentPacketWithHopOnly(upper.copyOfRange(0, 16), 0, true, 8, UDP)
        val conflict = fragmentPacket(upper.copyOfRange(16, upper.size), 16, false, 8, TCP).also {
            it[1] = 0xca.toByte()
            it[7] = 17
        }
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(conflict) is Ipv6PacketNormalizer.Result.Pending)
        val result = normalizer.process(first)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(expected, (result as Ipv6PacketNormalizer.Result.Ready).packet)
        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun fragmentIdentityKeepsEqualIdsFromDifferentAddressesSeparate() {
        val udpA = udpDatagram(ByteArray(8) { (it + 1).toByte() })
        val udpB = udpDatagram(ByteArray(8) { (it + 40).toByte() })
        val expectedA = ipv6Packet(UDP, udpA)
        val expectedB = ipv6Packet(UDP, udpB).also { it[23] = 9 }
        val firstA = fragmentPacket(udpA.copyOfRange(0, 8), 0, true, 80)
        val lastA = fragmentPacket(udpA.copyOfRange(8, udpA.size), 8, false, 80)
        val firstB = fragmentPacket(udpB.copyOfRange(0, 8), 0, true, 80).also { it[23] = 9 }
        val lastB = fragmentPacket(udpB.copyOfRange(8, udpB.size), 8, false, 80).also { it[23] = 9 }
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(firstA) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(lastB) is Ipv6PacketNormalizer.Result.Pending)
        val resultA = normalizer.process(lastA)
        val resultB = normalizer.process(firstB)

        assertTrue(resultA is Ipv6PacketNormalizer.Result.Ready)
        assertTrue(resultB is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(expectedA, (resultA as Ipv6PacketNormalizer.Result.Ready).packet)
        assertArrayEquals(expectedB, (resultB as Ipv6PacketNormalizer.Result.Ready).packet)
    }

    @Test
    fun rejectsFirstFragmentsWithAnIncompleteHeaderChain() {
        val shortTcp = fragmentPacket(ByteArray(16), 0, true, 81, TCP)
        val truncatedDestination = ByteArray(8).also {
            it[0] = UDP.toByte()
            it[1] = 1
        }
        val partialExtension = fragmentPacket(truncatedDestination, 0, true, 82, DESTINATION_OPTIONS)

        assertTrue(Ipv6PacketNormalizer().process(shortTcp) is Ipv6PacketNormalizer.Result.Rejected)
        assertTrue(Ipv6PacketNormalizer().process(partialExtension) is Ipv6PacketNormalizer.Result.Rejected)
    }

    @Test
    fun malformedFragmentLengthAndIncompleteFirstHeaderProduceIcmpv6Feedback() {
        val malformedLength = fragmentPacket(ByteArray(17), 0, true, 0x8201, TCP)
        val incompleteHeader = fragmentPacket(ByteArray(16), 0, true, 0x8202, TCP)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(malformedLength) is Ipv6PacketNormalizer.Result.Rejected)
        val lengthError = normalizer.drainIcmpErrors().single()
        assertEquals(4, lengthError[40].toInt() and 0xff)
        assertEquals(0, lengthError[41].toInt() and 0xff)
        assertEquals(0, upperLayerChecksum(lengthError, 40, lengthError.size - 40, 58))

        assertTrue(normalizer.process(incompleteHeader) is Ipv6PacketNormalizer.Result.Rejected)
        val headerError = normalizer.drainIcmpErrors().single()
        assertEquals(4, headerError[40].toInt() and 0xff)
        assertEquals(3, headerError[41].toInt() and 0xff)
        assertEquals(0, upperLayerChecksum(headerError, 40, headerError.size - 40, 58))
    }

    @Test
    fun oversizedNoninitialFragmentStillProducesRequiredParameterProblem() {
        val fragment = fragmentPacket(ByteArray(8), 65_528, false, 0x8203, UDP)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(fragment) is Ipv6PacketNormalizer.Result.Rejected)
        val error = normalizer.drainIcmpErrors().single()
        assertEquals(4, error[40].toInt() and 0xff)
        assertEquals(0, error[41].toInt() and 0xff)
        assertEquals(42, u32(error, 44))
        assertEquals(0, upperLayerChecksum(error, 40, error.size - 40, 58))
    }

    @Test
    fun overlapStaysSilentButTimedOutFirstFragmentProducesTimeExceeded() {
        var now = 0L
        val first = fragmentPacket(udpDatagram(ByteArray(0)), 0, true, 0x8301)
        val normalizer = Ipv6PacketNormalizer(clock = { now })
        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.drainIcmpErrors().isEmpty())

        now = 60_000L
        val timeout = normalizer.drainIcmpErrors().single()
        assertEquals(3, timeout[40].toInt() and 0xff)
        assertEquals(1, timeout[41].toInt() and 0xff)
        assertEquals(0, upperLayerChecksum(timeout, 40, timeout.size - 40, 58))

        val overlappingNormalizer = Ipv6PacketNormalizer()
        val overlapFirst = fragmentPacket(ByteArray(16), 0, true, 0x8302, TCP)
        val overlap = fragmentPacket(ByteArray(16), 8, false, 0x8302, TCP)
        overlappingNormalizer.process(overlapFirst)
        overlappingNormalizer.drainIcmpErrors()
        overlappingNormalizer.process(overlap)
        assertTrue(overlappingNormalizer.drainIcmpErrors().isEmpty())
    }

    @Test
    fun poisonedFragmentIdentitySuppressesLaterStructuralErrors() {
        val normalizer = Ipv6PacketNormalizer()
        val id = 0x8303
        val first = fragmentPacket(udpDatagram(ByteArray(8)), 0, true, id)
        val overlap = fragmentPacket(ByteArray(8), 8, false, id)

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(overlap) is Ipv6PacketNormalizer.Result.Rejected)
        assertTrue(normalizer.drainIcmpErrors().isEmpty())

        val malformed = fragmentPacket(ByteArray(7), 16, true, id)
        assertTrue(normalizer.process(malformed) is Ipv6PacketNormalizer.Result.Rejected)
        assertTrue(normalizer.drainIcmpErrors().isEmpty())
    }

    @Test
    fun completePostFragmentOptionsUseTheirOwnDiscardAction() {
        val silentOption = byteArrayOf(UDP.toByte(), 0, 0x40, 0, 0, 0, 0, 0)
        val reportableOption = byteArrayOf(UDP.toByte(), 0, 0x80.toByte(), 0, 0, 0, 0, 0)

        listOf(silentOption to false, reportableOption to true).forEachIndexed { index, (option, reports) ->
            val normalizer = Ipv6PacketNormalizer()
            val first = fragmentPacket(option + udpDatagram(ByteArray(8)), 0, true, 0x8310 + index,
                DESTINATION_OPTIONS)
            val last = fragmentPacket(ByteArray(8), 24, false, 0x8310 + index, DESTINATION_OPTIONS)
            assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
            assertTrue(normalizer.drainIcmpErrors().isEmpty())
            assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Rejected)
            val errors = normalizer.drainIcmpErrors()
            assertEquals(reports, errors.isNotEmpty())
            if (reports) {
                assertEquals(2, errors.single()[41].toInt() and 0xff)
                assertEquals(42, u32(errors.single(), 44))
            }
        }
    }

    @Test
    fun timeoutBehindAuthenticationHeaderNeverRepliesToAnIcmpv6Error() {
        var now = 0L
        val authentication = ByteArray(12).also {
            it[0] = 58
            it[1] = 1
        }
        val icmpError = ByteArray(12).also { it[0] = 1 }
        val first = fragmentPacket(authentication + icmpError, 0, true, 0x8320, AUTHENTICATION)
        val normalizer = Ipv6PacketNormalizer(clock = { now }, fragmentTimeoutMs = 10L)

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        now = 10L
        assertTrue(normalizer.drainIcmpErrors().isEmpty())
    }

    @Test
    fun timedOutFirstFragmentNeverRepliesToAnIcmpv6Redirect() {
        var now = 0L
        val redirect = ByteArray(16).also { it[0] = 137.toByte() }
        val first = fragmentPacket(redirect, 0, true, 0x8321, 58)
        val normalizer = Ipv6PacketNormalizer(clock = { now }, fragmentTimeoutMs = 10L)

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        now = 10L
        assertTrue(normalizer.drainIcmpErrors().isEmpty())
    }

    @Test
    fun maximumExtensionChainDoesNotBypassTimeoutErrorSuppression() {
        var now = 0L
        val icmpError = ByteArray(8).also { it[0] = 1 }
        val payload = extensionPayload(List(8) { DESTINATION_OPTIONS }, icmpError).also {
            it[7 * 8] = 58
        }
        val first = fragmentPacket(payload, 0, true, 0x8322, DESTINATION_OPTIONS)
        val normalizer = Ipv6PacketNormalizer(clock = { now }, fragmentTimeoutMs = 10L)

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        now = 10L
        assertTrue(normalizer.drainIcmpErrors().isEmpty())
    }

    @Test
    fun acceptsAFirstFragmentContainingTheCompleteTcpOptionsHeader() {
        val tcp = ByteArray(24).also { it[12] = 0x60 }

        val result = Ipv6PacketNormalizer().process(fragmentPacket(tcp, 0, true, 83, TCP))

        assertTrue(result is Ipv6PacketNormalizer.Result.Pending)
    }

    @Test
    fun exactDuplicateFragmentsAreIdempotent() {
        val expected = ipv6Packet(UDP, udpDatagram(ByteArray(16) { it.toByte() }))
        val upper = expected.copyOfRange(40, expected.size)
        val first = fragmentPacket(upper.copyOfRange(0, 16), 0, true, 84)
        val last = fragmentPacket(upper.copyOfRange(16, upper.size), 16, false, 84)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertEquals(16, normalizer.retainedFragmentBytes())
        val result = normalizer.process(last)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(expected, (result as Ipv6PacketNormalizer.Result.Ready).packet)
    }

    @Test
    fun conflictingDuplicatePoisonsTheIdentityUntilItsOriginalDeadline() {
        var now = 1_000L
        val udp = udpDatagram(ByteArray(16))
        val firstBytes = udp.copyOfRange(0, 16)
        val first = fragmentPacket(firstBytes, 0, true, 85)
        val conflict = fragmentPacket(firstBytes.copyOf().also { it[15] = 1 }, 0, true, 85)
        val last = fragmentPacket(udp.copyOfRange(16, udp.size), 16, false, 85)
        val normalizer = Ipv6PacketNormalizer(clock = { now }, fragmentTimeoutMs = 10)

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(conflict) is Ipv6PacketNormalizer.Result.Rejected)
        assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Rejected)
        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())

        now = 1_010L
        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
    }

    @Test
    fun resetDropsIncompleteAssembliesDuringNetworkHandover() {
        val fragment = fragmentPacket(ByteArray(16), offset = 0, more = true, id = 11)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(fragment) is Ipv6PacketNormalizer.Result.Pending)
        normalizer.reset()

        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun expiresIncompleteAssembliesBeforeAcceptingAnotherPacket() {
        var now = 100L
        val fragment = fragmentPacket(ByteArray(16), offset = 0, more = true, id = 12)
        val normalizer = Ipv6PacketNormalizer(clock = { now }, fragmentTimeoutMs = 10)

        assertTrue(normalizer.process(fragment) is Ipv6PacketNormalizer.Result.Pending)
        now = 110L
        assertTrue(
            normalizer.process(ipv6Packet(UDP, udpDatagram(ByteArray(0)))) is
                Ipv6PacketNormalizer.Result.Ready
        )

        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun fragmentActivityDoesNotExtendTheFirstArrivalDeadline() {
        var now = 100L
        val udp = udpDatagram(ByteArray(24))
        val first = fragmentPacket(udp.copyOfRange(0, 8), 0, true, 86)
        val lastWithGap = fragmentPacket(udp.copyOfRange(16, udp.size), 16, false, 86)
        val normalizer = Ipv6PacketNormalizer(clock = { now }, fragmentTimeoutMs = 10)

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        now = 109L
        assertTrue(normalizer.process(lastWithGap) is Ipv6PacketNormalizer.Result.Pending)
        now = 110L
        assertTrue(normalizer.process(ipv6Packet(UDP, udpDatagram(ByteArray(0)))) is Ipv6PacketNormalizer.Result.Ready)

        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun defaultReassemblyDeadlineIsSixtySeconds() {
        var now = 0L
        val fragment = fragmentPacket(udpDatagram(ByteArray(0)), 0, true, 87)
        val trigger = ipv6Packet(UDP, udpDatagram(ByteArray(0)))
        val normalizer = Ipv6PacketNormalizer(clock = { now })

        assertTrue(normalizer.process(fragment) is Ipv6PacketNormalizer.Result.Pending)
        now = 30_000L
        assertTrue(normalizer.process(trigger) is Ipv6PacketNormalizer.Result.Ready)
        assertEquals(1, normalizer.pendingAssemblies())
        now = 60_000L
        assertTrue(normalizer.process(trigger) is Ipv6PacketNormalizer.Result.Ready)
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun rejectsTheAssemblyThatWouldExceedTheGlobalByteBudget() {
        val normalizer = Ipv6PacketNormalizer(maxBufferedBytes = 16)
        val first = fragmentPacket(ByteArray(16), offset = 0, more = true, id = 13)
        val second = fragmentPacket(ByteArray(16), offset = 0, more = true, id = 14)

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(second) is Ipv6PacketNormalizer.Result.Rejected)

        assertEquals(16, normalizer.retainedFragmentBytes())
        assertEquals(1, normalizer.pendingAssemblies())
    }

    @Test
    fun oversizedNewIdentityDoesNotEvictAnExistingAssembly() {
        val normalizer = Ipv6PacketNormalizer(maxAssemblies = 1, maxBufferedBytes = 8)
        val retained = fragmentPacket(udpDatagram(ByteArray(0)), 0, true, 88)
        val oversized = fragmentPacket(ByteArray(16), 0, true, 89)

        assertTrue(normalizer.process(retained) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(oversized) is Ipv6PacketNormalizer.Result.Rejected)

        assertEquals(8, normalizer.retainedFragmentBytes())
        assertEquals(1, normalizer.pendingAssemblies())
    }

    @Test
    fun capacityEvictionIsLeastRecentlyUsedAndReleasesBytes() {
        val normalizer = Ipv6PacketNormalizer(maxAssemblies = 2, maxBufferedBytes = 64)
        val udp = udpDatagram(ByteArray(8))
        val firstA = fragmentPacket(udp.copyOfRange(0, 8), 0, true, 90)
        val firstB = fragmentPacket(udp.copyOfRange(0, 8), 0, true, 91)
        val firstC = fragmentPacket(udp.copyOfRange(0, 8), 0, true, 92)
        val lastA = fragmentPacket(udp.copyOfRange(8, udp.size), 8, false, 90)

        assertTrue(normalizer.process(firstA) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(firstB) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(firstA) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(firstC) is Ipv6PacketNormalizer.Result.Pending)
        assertEquals(16, normalizer.retainedFragmentBytes())

        assertTrue(normalizer.process(lastA) is Ipv6PacketNormalizer.Result.Ready)
        assertEquals(8, normalizer.retainedFragmentBytes())
        assertEquals(1, normalizer.pendingAssemblies())
    }

    @Test
    fun rejectsAnAssemblyThatExceedsItsFragmentCountLimit() {
        val normalizer = Ipv6PacketNormalizer(maxFragmentsPerAssembly = 1)
        val first = fragmentPacket(ByteArray(8), offset = 0, more = true, id = 15)
        val last = fragmentPacket(ByteArray(8), offset = 8, more = false, id = 15)

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Rejected)

        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun rejectsRoutingHeadersThatStillHaveSegmentsAndAuthenticationHeaders() {
        val udp = udpDatagram(ByteArray(0))
        val activeRouting = ByteArray(8).also {
            it[0] = UDP.toByte()
            it[3] = 1
        }
        val authentication = ByteArray(12).also {
            it[0] = UDP.toByte()
            it[1] = 1
        }

        val normalizer = Ipv6PacketNormalizer()
        assertTrue(normalizer.process(ipv6Packet(ROUTING, activeRouting + udp)) is
            Ipv6PacketNormalizer.Result.Rejected)
        val routingError = normalizer.drainIcmpErrors().single()
        assertEquals(0, routingError[41].toInt() and 0xff)
        assertEquals(42, u32(routingError, 44))
        assertTrue(
            Ipv6PacketNormalizer().process(ipv6Packet(AUTHENTICATION, authentication + udp)) is
                Ipv6PacketNormalizer.Result.Rejected
        )
    }

    @Test
    fun tombstoneCapacityFailsClosedUntilEveryPoisonedIdentityExpires() {
        fun poison(normalizer: Ipv6PacketNormalizer, id: Int) {
            normalizer.process(fragmentPacket(ByteArray(24).also { it[12] = 0x50 }, 0, true, id, TCP))
            normalizer.process(fragmentPacket(ByteArray(16), 8, false, id, TCP))
        }
        val normalizer = Ipv6PacketNormalizer(maxAssemblies = 1)
        poison(normalizer, 900)
        for (id in 901..916) poison(normalizer, id)

        assertTrue(normalizer.process(
            fragmentPacket(ByteArray(24).also { it[12] = 0x50 }, 0, true, 900, TCP)
        ) is
            Ipv6PacketNormalizer.Result.Rejected)
        assertTrue(normalizer.process(fragmentPacket(ByteArray(16), 24, false, 900, TCP)) is
            Ipv6PacketNormalizer.Result.Rejected)

        val expected = ipv6Packet(UDP, udpDatagram(ByteArray(8)))
        setUdpChecksum(expected, 40, expected.size - 40)
        val atomic = fragmentPacket(expected.copyOfRange(40, expected.size), 0, false, 9999)
        val atomicResult = normalizer.process(atomic)
        assertTrue(atomicResult is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(expected, (atomicResult as Ipv6PacketNormalizer.Result.Ready).packet)
    }

    @Test
    fun icmpv6ErrorsAreRateLimitedIndependentlyOfQueueDraining() {
        var now = 0L
        val normalizer = Ipv6PacketNormalizer(clock = { now })
        val unknown = ipv6Packet(250, ByteArray(8))

        repeat(40) { normalizer.process(unknown) }
        assertEquals(32, normalizer.drainIcmpErrors().size)
        repeat(10) { normalizer.process(unknown) }
        assertTrue(normalizer.drainIcmpErrors().isEmpty())
        now = 1_000L
        normalizer.process(unknown)
        assertEquals(1, normalizer.drainIcmpErrors().size)
    }

    @Test
    fun icmpv6RateBudgetCanBeSharedAcrossAllErrorProducers() {
        val limiter = Ipv6IcmpErrorRateLimiter(clock = { 0L }, maximumPerWindow = 2)
        val normalizer = Ipv6PacketNormalizer(icmpErrorRateLimiter = limiter)
        assertTrue(limiter.tryAcquire())

        normalizer.process(ipv6Packet(250, ByteArray(8)))
        normalizer.process(ipv6Packet(250, ByteArray(8)))

        assertEquals(1, normalizer.drainIcmpErrors().size)
    }

    @Test
    fun rejectsOptionsWhoseActionRequiresDiscardingThePacket() {
        val extension = byteArrayOf(UDP.toByte(), 0, 0x40, 0, 0, 0, 0, 0)
        val packet = ipv6Packet(DESTINATION_OPTIONS, extension + udpDatagram(ByteArray(0)))

        assertTrue(Ipv6PacketNormalizer().process(packet) is Ipv6PacketNormalizer.Result.Rejected)
    }

    @Test
    fun multicastOptionActionsFollowTheirIcmpv6ReportingBits() {
        val alwaysReport = byteArrayOf(UDP.toByte(), 0, 0x80.toByte(), 0, 0, 0, 0, 0)
        val suppressForMulticast = byteArrayOf(UDP.toByte(), 0, 0xc0.toByte(), 0, 0, 0, 0, 0)

        listOf(alwaysReport to true, suppressForMulticast to false).forEach { (extension, reports) ->
            val packet = ipv6Packet(DESTINATION_OPTIONS, extension + udpDatagram(ByteArray(0))).also {
                it[24] = 0xff.toByte()
            }
            val normalizer = Ipv6PacketNormalizer(icmpSourceAddress = DESTINATION)

            assertTrue(normalizer.process(packet) is Ipv6PacketNormalizer.Result.Rejected)
            val errors = normalizer.drainIcmpErrors()
            assertEquals(reports, errors.isNotEmpty())
            if (reports) {
                val response = errors.single()
                assertArrayEquals(DESTINATION, response.copyOfRange(8, 24))
                assertEquals(0, upperLayerChecksum(response, 40, response.size - 40, 58))
            }
        }
    }

    @Test
    fun multicastSilentDiscardOptionCannotBeOverriddenByALaterReportingOption() {
        val extension = byteArrayOf(
            UDP.toByte(), 0, 0xc0.toByte(), 0, 0x80.toByte(), 0, 0, 0
        )
        val packet = ipv6Packet(DESTINATION_OPTIONS, extension + udpDatagram(ByteArray(0))).also {
            it[24] = 0xff.toByte()
        }
        val normalizer = Ipv6PacketNormalizer(icmpSourceAddress = DESTINATION)

        assertTrue(normalizer.process(packet) is Ipv6PacketNormalizer.Result.Rejected)
        assertTrue(normalizer.drainIcmpErrors().isEmpty())
    }

    @Test
    fun unknownNextHeaderAndReportableOptionProduceParameterProblem() {
        val unknown = ipv6Packet(250, ByteArray(8))
        val option = byteArrayOf(UDP.toByte(), 0, 0x80.toByte(), 0, 0, 0, 0, 0)
        val badOption = ipv6Packet(DESTINATION_OPTIONS, option + udpDatagram(ByteArray(0)))
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(unknown) is Ipv6PacketNormalizer.Result.Rejected)
        val unknownError = normalizer.drainIcmpErrors().single()
        assertEquals(4, unknownError[40].toInt() and 0xff)
        assertEquals(1, unknownError[41].toInt() and 0xff)
        assertEquals(6, u32(unknownError, 44))

        assertTrue(normalizer.process(badOption) is Ipv6PacketNormalizer.Result.Rejected)
        val optionError = normalizer.drainIcmpErrors().single()
        assertEquals(4, optionError[40].toInt() and 0xff)
        assertEquals(2, optionError[41].toInt() and 0xff)
        assertEquals(42, u32(optionError, 44))
    }

    @Test
    fun unsupportedExtensionHeadersCannotTriggerErrorsForIcmpv6ErrorsOrRedirects() {
        listOf(135, 139, 140).forEach { extensionType ->
            listOf(1, 137).forEach { icmpType ->
                val extension = ByteArray(8).also { it[0] = 58 }
                val icmp = ByteArray(8).also { it[0] = icmpType.toByte() }
                val normalizer = Ipv6PacketNormalizer()

                assertTrue(
                    normalizer.process(ipv6Packet(extensionType, extension + icmp)) is
                        Ipv6PacketNormalizer.Result.Rejected
                )
                assertTrue(normalizer.drainIcmpErrors().isEmpty())
            }
        }
    }

    @Test
    fun knownEspAndNoNextHeaderAreDroppedWithoutParameterProblem() {
        listOf(50, 59).forEach { protocol ->
            val normalizer = Ipv6PacketNormalizer()
            assertTrue(normalizer.process(ipv6Packet(protocol, ByteArray(8))) is
                Ipv6PacketNormalizer.Result.Rejected)
            assertTrue(normalizer.drainIcmpErrors().isEmpty())
        }
    }

    @Test
    fun firstFragmentsWithEspOrNoNextHeaderAreSyntacticallyComplete() {
        listOf(50, 59).forEach { protocol ->
            val normalizer = Ipv6PacketNormalizer()
            assertTrue(normalizer.process(fragmentPacket(ByteArray(8), 0, true, 0x8400 + protocol,
                protocol)) is Ipv6PacketNormalizer.Result.Pending)
            assertTrue(normalizer.drainIcmpErrors().isEmpty())
        }
    }

    @Test
    fun acceptsSupportedExtensionHeadersInEveryOrderWithinTheBound() {
        val expected = ipv6Packet(UDP, udpDatagram(ByteArray(9) { (it * 3).toByte() }))
        setUdpChecksum(expected, 40, expected.size - 40)
        val udp = expected.copyOfRange(40, expected.size)

        for (mask in 0 until (1 shl 4)) {
            val headers = List(4) { index ->
                if (mask and (1 shl index) == 0) DESTINATION_OPTIONS else ROUTING
            }
            for (chain in listOf(headers, listOf(HOP_BY_HOP) + headers)) {
                val result = Ipv6PacketNormalizer().process(packetWithExtensions(chain, udp))
                assertTrue("chain=$chain", result is Ipv6PacketNormalizer.Result.Ready)
                assertArrayEquals(expected, (result as Ipv6PacketNormalizer.Result.Ready).packet)
            }
        }

        val misplacedHop = packetWithExtensions(listOf(DESTINATION_OPTIONS, HOP_BY_HOP), udp)
        assertTrue(Ipv6PacketNormalizer().process(misplacedHop) is Ipv6PacketNormalizer.Result.Rejected)
        assertTrue(
            Ipv6PacketNormalizer().process(
                packetWithExtensions(List(9) { DESTINATION_OPTIONS }, udp)
            ) is Ipv6PacketNormalizer.Result.Rejected
        )
    }

    @Test
    fun reassemblesEverySupportedPostFragmentExtensionOrder() {
        val expected = ipv6Packet(UDP, udpDatagram(ByteArray(16) { (it + 11).toByte() }))
        setUdpChecksum(expected, 40, expected.size - 40)
        val upper = expected.copyOfRange(40, expected.size)
        val orders = listOf(
            listOf(DESTINATION_OPTIONS, ROUTING),
            listOf(ROUTING, DESTINATION_OPTIONS),
            listOf(DESTINATION_OPTIONS, DESTINATION_OPTIONS),
            listOf(ROUTING, ROUTING)
        )

        for ((index, headers) in orders.withIndex()) {
            val fragmentable = extensionPayload(headers, upper)
            val first = fragmentPacketWithHopOnly(
                fragmentable.copyOfRange(0, 24),
                0,
                true,
                300 + index,
                headers.first()
            )
            val last = fragmentPacket(
                fragmentable.copyOfRange(24, fragmentable.size),
                24,
                false,
                300 + index,
                TCP
            )
            val normalizer = Ipv6PacketNormalizer()

            assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Pending)
            val result = normalizer.process(first)
            assertTrue("headers=$headers", result is Ipv6PacketNormalizer.Result.Ready)
            assertArrayEquals(expected, (result as Ipv6PacketNormalizer.Result.Ready).packet)
        }
    }

    @Test
    fun optionActionsAndMalformedLengthsAreHandledWithoutScanningPastTheHeader() {
        for (type in listOf(0x40, 0x80, 0xc0)) {
            val extension = byteArrayOf(UDP.toByte(), 0, type.toByte(), 4, 1, 2, 3, 4)
            assertTrue(
                Ipv6PacketNormalizer().process(
                    ipv6Packet(DESTINATION_OPTIONS, extension + udpDatagram(ByteArray(0)))
                ) is Ipv6PacketNormalizer.Result.Rejected
            )
        }
        val skipped = byteArrayOf(UDP.toByte(), 0, 0x20, 4, 1, 2, 3, 4)
        assertTrue(
            Ipv6PacketNormalizer().process(
                ipv6Packet(DESTINATION_OPTIONS, skipped + udpDatagram(ByteArray(0)))
            ) is Ipv6PacketNormalizer.Result.Ready
        )
        val truncated = byteArrayOf(UDP.toByte(), 0, 0x20, 5, 1, 2, 3, 4)
        assertTrue(
            Ipv6PacketNormalizer().process(
                ipv6Packet(DESTINATION_OPTIONS, truncated + udpDatagram(ByteArray(0)))
            ) is Ipv6PacketNormalizer.Result.Rejected
        )
    }

    @Test
    fun ignoresFragmentReservedFieldsOnReception() {
        val expected = ipv6Packet(UDP, udpDatagram(ByteArray(8) { it.toByte() }))
        val packet = fragmentPacket(expected.copyOfRange(40, expected.size), 0, false, 93).also {
            it[41] = 0x7f
            put16(it, 42, 0x0006)
        }

        val result = Ipv6PacketNormalizer().process(packet)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(expected, (result as Ipv6PacketNormalizer.Result.Ready).packet)
    }

    @Test
    fun rejectsTruncatedTransportAndJumbogramEncoding() {
        assertTrue(
            Ipv6PacketNormalizer().process(ipv6Packet(UDP, ByteArray(0))) is
                Ipv6PacketNormalizer.Result.Rejected
        )
        val jumbogram = ByteArray(48)
        jumbogram[0] = 0x60
        jumbogram[6] = HOP_BY_HOP.toByte()
        jumbogram[40] = UDP.toByte()
        jumbogram[42] = 0xc2.toByte()
        jumbogram[43] = 4
        put32(jumbogram, 44, 65_536)

        assertTrue(Ipv6PacketNormalizer().process(jumbogram) is Ipv6PacketNormalizer.Result.Rejected)
    }

    @Test
    fun reassemblesTheMaximumNonJumboIpv6Payload() {
        val udp = ByteArray(65_535)
        put16(udp, 0, 50_000)
        put16(udp, 2, 443)
        put16(udp, 4, udp.size)
        val expected = ipv6Packet(UDP, udp)
        val first = fragmentPacket(udp.copyOfRange(0, 8), 0, true, 94)
        val last = fragmentPacket(udp.copyOfRange(8, udp.size), 8, false, 94)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Pending)
        val result = normalizer.process(first)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertEquals(65_575, (result as Ipv6PacketNormalizer.Result.Ready).packet.size)
        assertArrayEquals(expected, result.packet)
    }

    @Test
    fun rejectsReassemblyWhosePrefixMakesThePayloadExceed65535() {
        val udp = ByteArray(65_528)
        put16(udp, 0, 50_000)
        put16(udp, 2, 443)
        put16(udp, 4, udp.size)
        val first = fragmentPacketWithHopOnly(udp.copyOfRange(0, 8), 0, true, 95, UDP)
        val last = fragmentPacket(udp.copyOfRange(8, udp.size), 8, false, 95)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Rejected)
        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
        val error = normalizer.drainIcmpErrors().single()
        assertEquals(42, u32(error, 44))
        assertEquals(FRAGMENT, error[48 + 6].toInt() and 0xff)
        assertEquals(8, u16(error, 48 + 42) and 0xfff8)
    }

    @Test
    fun nonzeroFragmentPrefixDoesNotReduceTheOffsetZeroPayloadLimit() {
        val udp = ByteArray(65_288).also {
            put16(it, 0, 50_000)
            put16(it, 2, 443)
            put16(it, 4, it.size)
        }
        val first = fragmentPacket(udp.copyOfRange(0, 65_280), 0, true, 951, UDP)
        val destination = ByteArray(8).also { it[0] = FRAGMENT.toByte() }
        val fragment = ByteArray(16).also {
            it[0] = UDP.toByte()
            put16(it, 2, 65_280 / 8 shl 3)
            put32(it, 4, 951)
            udp.copyInto(it, 8, 65_280)
        }
        val last = ipv6Packet(DESTINATION_OPTIONS, destination + fragment)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        val result = normalizer.process(last)

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(ipv6Packet(UDP, udp), (result as Ipv6PacketNormalizer.Result.Ready).packet)
        assertTrue(normalizer.drainIcmpErrors().isEmpty())
    }

    @Test
    fun atomicFragmentDoesNotInteractWithAQueuedIdentityCollision() {
        val fragmentedUdp = udpDatagram(ByteArray(16) { it.toByte() })
        val first = fragmentPacket(fragmentedUdp.copyOfRange(0, 16), 0, true, 96)
        val last = fragmentPacket(fragmentedUdp.copyOfRange(16, fragmentedUdp.size), 16, false, 96)
        val atomicUdp = udpDatagram(ByteArray(3) { (it + 20).toByte() })
        val atomic = fragmentPacket(atomicUdp, 0, false, 96)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        val atomicResult = normalizer.process(atomic)
        assertTrue(atomicResult is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(ipv6Packet(UDP, atomicUdp), (atomicResult as Ipv6PacketNormalizer.Result.Ready).packet)
        assertEquals(16, normalizer.retainedFragmentBytes())
        assertEquals(1, normalizer.pendingAssemblies())

        assertTrue(normalizer.process(last) is Ipv6PacketNormalizer.Result.Ready)
    }

    @Test
    fun atomicFragmentDoesNotInteractWithAPoisonedIdentity() {
        val udp = udpDatagram(ByteArray(16))
        val firstBytes = udp.copyOfRange(0, 16)
        val first = fragmentPacket(firstBytes, 0, true, 97)
        val overlap = fragmentPacket(udp.copyOfRange(8, udp.size), 8, false, 97)
        val atomicUdp = udpDatagram(ByteArray(5) { it.toByte() })
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(overlap) is Ipv6PacketNormalizer.Result.Rejected)
        val result = normalizer.process(fragmentPacket(atomicUdp, 0, false, 97))

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(ipv6Packet(UDP, atomicUdp), (result as Ipv6PacketNormalizer.Result.Ready).packet)
    }

    @Test
    fun rejectsHopByHopFollowingAnAtomicFragmentHeader() {
        val hop = ByteArray(8).also { it[0] = UDP.toByte() }
        val atomic = fragmentPacket(hop + udpDatagram(ByteArray(0)), 0, false, 98, HOP_BY_HOP)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(atomic) is Ipv6PacketNormalizer.Result.Rejected)
        val error = normalizer.drainIcmpErrors().single()
        assertEquals(1, error[41].toInt() and 0xff)
        assertEquals(40, u32(error, 44))
        assertArrayEquals(atomic, error.copyOfRange(48, error.size))
        assertEquals(0, upperLayerChecksum(error, 40, error.size - 40, 58))
    }

    @Test
    fun atomicFragmentUnknownHeaderErrorQuotesTheFragmentAndPointsToItsNextHeader() {
        val atomic = fragmentPacket(ByteArray(8), 0, false, 981, 250)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(atomic) is Ipv6PacketNormalizer.Result.Rejected)
        val error = normalizer.drainIcmpErrors().single()

        assertEquals(1, error[41].toInt() and 0xff)
        assertEquals(40, u32(error, 44))
        assertArrayEquals(atomic, error.copyOfRange(48, error.size))
        assertEquals(0, upperLayerChecksum(error, 40, error.size - 40, 58))
    }

    @Test
    fun atomicFragmentAfterDestinationOptionsKeepsTheOriginalErrorPointer() {
        val destination = ByteArray(8).also { it[0] = FRAGMENT.toByte() }
        val fragment = ByteArray(16).also {
            it[0] = 250.toByte()
            put32(it, 4, 982)
        }
        val atomic = ipv6Packet(DESTINATION_OPTIONS, destination + fragment)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(atomic) is Ipv6PacketNormalizer.Result.Rejected)
        val error = normalizer.drainIcmpErrors().single()

        assertEquals(48, u32(error, 44))
        assertArrayEquals(atomic, error.copyOfRange(48, error.size))
        assertEquals(0, upperLayerChecksum(error, 40, error.size - 40, 58))
    }

    @Test
    fun eightExtensionHeadersAreAcceptedOnEitherSideOfAnAtomicFragment() {
        val udp = udpDatagram(ByteArray(3))
        var payload = ByteArray(8).also {
            it[0] = UDP.toByte()
            put32(it, 4, 983)
        } + udp
        var nextHeader = FRAGMENT
        repeat(8) {
            payload = ByteArray(8).also { extension -> extension[0] = nextHeader.toByte() } + payload
            nextHeader = DESTINATION_OPTIONS
        }
        val before = ipv6Packet(nextHeader, payload)
        var afterPayload = udp
        var afterNextHeader = UDP
        repeat(8) {
            afterPayload = ByteArray(8).also { extension -> extension[0] = afterNextHeader.toByte() } + afterPayload
            afterNextHeader = DESTINATION_OPTIONS
        }
        val after = fragmentPacket(afterPayload, 0, false, 984, afterNextHeader)
        val expected = ipv6Packet(UDP, udp)

        listOf(before, after).forEach { packet ->
            val result = Ipv6PacketNormalizer().process(packet)
            assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
            assertArrayEquals(expected, (result as Ipv6PacketNormalizer.Result.Ready).packet)
        }
    }

    @Test
    fun nonzeroFragmentNextHeaderIsIgnoredInFavorOfOffsetZero() {
        val udp = udpDatagram(ByteArray(16))
        val first = fragmentPacket(udp.copyOfRange(0, 16), 0, true, 99)
        val nonzero = fragmentPacket(udp.copyOfRange(16, udp.size), 16, false, 99, HOP_BY_HOP)

        listOf(listOf(first, nonzero), listOf(nonzero, first)).forEach { order ->
            val normalizer = Ipv6PacketNormalizer()
            assertTrue(normalizer.process(order.first()) is Ipv6PacketNormalizer.Result.Pending)
            val result = normalizer.process(order.last())
            assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
            assertArrayEquals(ipv6Packet(UDP, udp), (result as Ipv6PacketNormalizer.Result.Ready).packet)
        }
    }

    @Test
    fun seededValidFragmentFuzzReassemblesDeterministically() {
        val random = Random(0x6f21a9)
        repeat(128) { caseIndex ->
            val data = ByteArray(random.nextInt(1, 257)).also(random::nextBytes)
            val expected = ipv6Packet(UDP, udpDatagram(data))
            setUdpChecksum(expected, 40, expected.size - 40)
            val upper = expected.copyOfRange(40, expected.size)
            val id = 1_000 + caseIndex
            val fragments = ArrayList<ByteArray>()
            var offset = 0
            while (offset < upper.size) {
                val remaining = upper.size - offset
                val length = if (offset == 0) {
                    8
                } else if (remaining <= 8 || random.nextBoolean()) {
                    remaining
                } else {
                    random.nextInt(1, (remaining - 1) / 8 + 1) * 8
                }
                val more = offset + length < upper.size
                val nextHeader = if (offset == 0 || random.nextBoolean()) UDP else TCP
                val fragment = if (random.nextBoolean()) {
                    fragmentPacketWithHopOnly(
                        upper.copyOfRange(offset, offset + length),
                        offset,
                        more,
                        id,
                        nextHeader
                    )
                } else {
                    fragmentPacket(
                        upper.copyOfRange(offset, offset + length),
                        offset,
                        more,
                        id,
                        nextHeader
                    )
                }
                if (offset != 0) {
                    fragment[1] = (
                        (random.nextInt(256) and 0xcf) or
                            (expected[1].toInt() and 0x30)
                        ).toByte()
                    fragment[7] = random.nextInt(1, 256).toByte()
                }
                fragments += fragment
                offset += length
            }
            val ordered = fragments.shuffled(random)
            val normalizer = Ipv6PacketNormalizer()
            assertTrue(normalizer.process(ordered.first()) is Ipv6PacketNormalizer.Result.Pending)
            var ready: Ipv6PacketNormalizer.Result.Ready? = null
            for (fragment in ordered.drop(1)) {
                when (val result = normalizer.process(fragment)) {
                    is Ipv6PacketNormalizer.Result.Ready -> ready = result
                    is Ipv6PacketNormalizer.Result.Pending -> Unit
                    is Ipv6PacketNormalizer.Result.Rejected -> throw AssertionError("case=$caseIndex")
                }
            }
            assertArrayEquals("case=$caseIndex", expected, requireNotNull(ready).packet)
            assertEquals(0, normalizer.retainedFragmentBytes())
            assertEquals(0, normalizer.pendingAssemblies())
        }
    }

    @Test
    fun seededOverlapFuzzAlwaysDiscardsAndPoisonsTheDatagram() {
        val random = Random(0x5722)
        repeat(96) { caseIndex ->
            val udp = udpDatagram(ByteArray(24).also(random::nextBytes))
            val id = 2_000 + caseIndex
            val first = fragmentPacket(udp.copyOfRange(0, 16), 0, true, id)
            val overlapLength = if (random.nextBoolean()) 8 else 16
            val overlap = fragmentPacket(
                ByteArray(overlapLength).also(random::nextBytes),
                8,
                false,
                id
            )
            val normalizer = Ipv6PacketNormalizer()

            assertTrue(normalizer.process(first) is Ipv6PacketNormalizer.Result.Pending)
            assertTrue(normalizer.process(overlap) is Ipv6PacketNormalizer.Result.Rejected)
            assertTrue(
                normalizer.process(fragmentPacket(udp.copyOfRange(16, udp.size), 16, false, id)) is
                    Ipv6PacketNormalizer.Result.Rejected
            )
            assertEquals(0, normalizer.retainedFragmentBytes())
            assertEquals(0, normalizer.pendingAssemblies())
        }
    }

    private fun fragmentPacket(
        payload: ByteArray,
        offset: Int,
        more: Boolean,
        id: Int,
        nextHeader: Int = UDP
    ): ByteArray {
        require(offset % 8 == 0)
        val fragment = ByteArray(8 + payload.size)
        fragment[0] = nextHeader.toByte()
        put16(fragment, 2, (offset / 8 shl 3) or if (more) 1 else 0)
        put32(fragment, 4, id)
        payload.copyInto(fragment, 8)
        return ipv6Packet(FRAGMENT, fragment)
    }

    private fun fragmentPacketWithHop(payload: ByteArray, offset: Int, more: Boolean, id: Int): ByteArray {
        require(offset % 8 == 0)
        val hop = ByteArray(8).also { it[0] = FRAGMENT.toByte() }
        val fragment = ByteArray(8 + payload.size)
        fragment[0] = DESTINATION_OPTIONS.toByte()
        put16(fragment, 2, (offset / 8 shl 3) or if (more) 1 else 0)
        put32(fragment, 4, id)
        payload.copyInto(fragment, 8)
        return ipv6Packet(HOP_BY_HOP, hop + fragment)
    }

    private fun fragmentPacketWithHopOnly(
        payload: ByteArray,
        offset: Int,
        more: Boolean,
        id: Int,
        nextHeader: Int
    ): ByteArray {
        require(offset % 8 == 0)
        val hop = ByteArray(8).also { it[0] = FRAGMENT.toByte() }
        val fragment = ByteArray(8 + payload.size)
        fragment[0] = nextHeader.toByte()
        put16(fragment, 2, (offset / 8 shl 3) or if (more) 1 else 0)
        put32(fragment, 4, id)
        payload.copyInto(fragment, 8)
        return ipv6Packet(HOP_BY_HOP, hop + fragment)
    }

    private fun packetWithExtensions(headers: List<Int>, upper: ByteArray): ByteArray {
        var nextHeader = UDP
        var payload = upper
        for (type in headers.asReversed()) {
            val extension = ByteArray(8).also { it[0] = nextHeader.toByte() }
            payload = extension + payload
            nextHeader = type
        }
        return ipv6Packet(nextHeader, payload)
    }

    private fun extensionPayload(headers: List<Int>, upper: ByteArray): ByteArray {
        var nextHeader = UDP
        var payload = upper
        for (type in headers.asReversed()) {
            val extension = ByteArray(8).also { it[0] = nextHeader.toByte() }
            payload = extension + payload
            nextHeader = type
        }
        return payload
    }

    private fun ipv6Packet(nextHeader: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(40 + payload.size)
        packet[0] = 0x60
        put16(packet, 4, payload.size)
        packet[6] = nextHeader.toByte()
        packet[7] = 64
        SOURCE.copyInto(packet, 8)
        DESTINATION.copyInto(packet, 24)
        payload.copyInto(packet, 40)
        return packet
    }

    private fun udpDatagram(payload: ByteArray): ByteArray {
        val udp = ByteArray(8 + payload.size)
        put16(udp, 0, 50_000)
        put16(udp, 2, 443)
        put16(udp, 4, udp.size)
        payload.copyInto(udp, 8)
        return udp
    }

    private fun setUdpChecksum(packet: ByteArray, offset: Int, length: Int) {
        put16(packet, offset + 6, 0)
        var sum = checksumSum(packet, 8, 32)
        sum += length.toLong()
        sum += UDP.toLong()
        sum += checksumSum(packet, offset, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        val value = sum.inv().toInt() and 0xffff
        put16(packet, offset + 6, if (value == 0) 0xffff else value)
    }

    private fun upperLayerChecksum(packet: ByteArray, offset: Int, length: Int, protocol: Int): Int {
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

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private companion object {
        const val TCP = 6
        const val UDP = 17
        const val HOP_BY_HOP = 0
        const val ROUTING = 43
        const val FRAGMENT = 44
        const val AUTHENTICATION = 51
        const val DESTINATION_OPTIONS = 60
        val SOURCE = byteArrayOf(0x20, 1, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
        val DESTINATION = byteArrayOf(0x20, 1, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    }
}
