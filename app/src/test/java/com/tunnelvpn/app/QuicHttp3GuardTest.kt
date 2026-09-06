package com.tunnelvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Udp443BlockPolicyTest {
    @Test
    fun adaptivePolicyAllowsAProbeBurstAndFallsBackOnlyAfterTwoFailures() {
        var now = 1_000L
        val address = byteArrayOf(203.toByte(), 0, 113, 7)
        val policy = AdaptiveUdp443Policy(clock = { now }, tcpFallbackTtlMs = 700L, probeLeaseMs = 500L)

        assertFalse(policy.requiresTcpPath(address))
        assertFalse(policy.requiresTcpPath(address))
        policy.recordUnreachable(address)
        assertFalse(policy.requiresTcpPath(address))
        assertFalse(policy.requiresTcpPath(address))
        policy.recordUnreachable(address)
        assertTrue(policy.requiresTcpPath(address))
        now += 700L
        assertFalse(policy.requiresTcpPath(address))
        policy.recordReachable(address)
        assertFalse(policy.requiresTcpPath(address))
        policy.reset()
        assertFalse(policy.requiresTcpPath(address))
    }

    @Test
    fun adaptiveProbeLeaseDoesNotInventIpv6Failures() {
        var now = 5_000L
        val address = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 9 }
        val policy = AdaptiveUdp443Policy(
            clock = { now },
            tcpFallbackTtlMs = 700L,
            probeLeaseMs = 500L
        )

        assertFalse(policy.requiresTcpPath(address))
        now += 500L
        assertFalse(policy.requiresTcpPath(address))
        now += 500L
        assertFalse(policy.requiresTcpPath(address))
        policy.recordUnreachable(address)
        assertFalse(policy.requiresTcpPath(address))
        policy.recordUnreachable(address)
        assertTrue(policy.requiresTcpPath(address))
        assertTrue(policy.requiresTcpPath(ByteArray(3)))
    }

    @Test
    fun disabledGuardDoesNotDropUdp443() {
        assertFalse(Udp443BlockPolicy(enabled = false).shouldDropIpv4Packet(ipv4Packet(protocol = 17, destinationPort = 443), 28))
    }

    @Test
    fun enabledGuardDropsOnlyUdp443() {
        val guard = Udp443BlockPolicy(enabled = true)

        val initial = ipv4Packet(protocol = 17, destinationPort = 443, payload = quicInitial())
        assertTrue(guard.shouldDropIpv4Packet(initial, initial.size))
        assertFalse(guard.shouldDropIpv4Packet(ipv4Packet(protocol = 17, destinationPort = 53), 28))
        assertFalse(guard.shouldDropIpv4Packet(ipv4Packet(protocol = 6, destinationPort = 443), 28))
        val arbitrary = ipv4Packet(protocol = 17, destinationPort = 443, payload = ByteArray(1_200) { 0x16 })
        assertFalse(guard.shouldDropIpv4Packet(arbitrary, arbitrary.size))
    }

    @Test
    fun adaptiveFullForwardPolicyAllowsUnmappedUdp443() {
        val guard = Udp443BlockPolicy(enabled = true, requiresTcpPath = { false })

        assertFalse(guard.shouldDropIpv4Packet(ipv4Packet(protocol = 17, destinationPort = 443), 28))
    }

    @Test
    fun mappedUdp443StillUsesTcpPath() {
        val guard = Udp443BlockPolicy(enabled = true, requiresTcpPath = { address ->
            address.contentEquals(byteArrayOf(10, 111, 1, 7))
        })
        val packet = ipv4Packet(protocol = 17, destinationPort = 443, payload = quicInitial()).also {
            byteArrayOf(10, 111, 1, 7).copyInto(it, destinationOffset = 16)
        }

        assertTrue(guard.shouldDropIpv4Packet(packet, packet.size))
    }

    @Test
    fun defaultFullForwardPolicyDropsIpv6QuicInitial() {
        val guard = Udp443BlockPolicy(enabled = true)
        val packet = ipv6Packet(protocol = 17, destinationPort = 443, payload = quicInitial(version = 0x6b3343cf, type = 1))

        assertTrue(guard.shouldDropIpv6Packet(packet, packet.size))
        assertFalse(guard.shouldDropIpv6Packet(ipv6Packet(protocol = 17, destinationPort = 53), 48))
        assertFalse(guard.shouldDropIpv6Packet(ipv6Packet(protocol = 6, destinationPort = 443), 48))
    }

    @Test
    fun malformedPacketsAreLeftForTheOuterFailClosedPacketGate() {
        val guard = Udp443BlockPolicy(enabled = true)

        assertFalse(guard.shouldDropIpv4Packet(ByteArray(8), 8))
        assertFalse(guard.shouldDropIpv4Packet(ipv4Packet(protocol = 17, destinationPort = 443), 12))
    }

    @Test
    fun classifierAcceptsOnlyStructurallyValidV1AndV2InitialDatagrams() {
        assertTrue(QuicInitialClassifier.isLikely(quicInitial()))
        assertTrue(QuicInitialClassifier.isLikely(quicInitial(version = 0x6b3343cf, type = 1)))
        assertFalse(QuicInitialClassifier.isLikely(quicInitial(version = 0, type = 0)))
        assertFalse(QuicInitialClassifier.isLikely(quicInitial(type = 2)))
        assertFalse(QuicInitialClassifier.isLikely(ByteArray(1_199) { 0xc0.toByte() }))
        assertFalse(QuicInitialClassifier.isLikely(ByteArray(1_200) { 0x16 }))
    }

    @Test
    fun futureVersionFirstFlightIsObservedWithoutBeingForceBlocked() {
        val future = quicInitial(version = 0x1a2a3a4a, type = 3).also { it[0] = 0xb0.toByte() }
        val invariantOnly = byteArrayOf(
            0x80.toByte(), 0x1a, 0x2a, 0x3a, 0x4a, 0, 0
        )

        val probe = QuicInitialClassifier.inspectProbe(future)

        assertNotNull(probe)
        assertFalse(probe!!.knownInitial)
        assertFalse(QuicInitialClassifier.isLikely(future))
        assertNotNull(QuicInitialClassifier.inspectProbe(invariantOnly))
        assertFalse(QuicInitialClassifier.isLikely(invariantOnly))
    }

    @Test
    fun shortConnectionIdRequiresTheRetryTokenOnKnownInitials() {
        val firstFlight = quicInitialWithConnectionIds(
            destinationConnectionId = byteArrayOf(1, 2, 3, 4),
            sourceConnectionId = byteArrayOf(5, 6, 7, 8)
        )
        val postRetry = quicInitialWithConnectionIds(
            destinationConnectionId = byteArrayOf(1, 2, 3, 4),
            sourceConnectionId = byteArrayOf(5, 6, 7, 8),
            token = byteArrayOf(9, 10, 11)
        )

        assertFalse(QuicInitialClassifier.isLikely(firstFlight))
        assertTrue(QuicInitialClassifier.isLikely(postRetry))
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4),
            QuicInitialClassifier.inspectProbe(postRetry)!!.destinationConnectionId
        )
        assertArrayEquals(byteArrayOf(9, 10, 11), QuicInitialClassifier.inspectProbe(postRetry)!!.token)
    }

    @Test
    fun serverInitialVersionNegotiationAndRetryArePlausibleProbeResponses() {
        val probe = QuicInitialClassifier.inspectProbe(quicInitial())!!
        val initial = quicServerInitial(probe)
        val negotiation = quicVersionNegotiation(probe, 0x6b3343cf)
        val retry = quicRetry(probe)

        assertEquals(QuicServerResponseClassifier.ResponseType.INITIAL, QuicServerResponseClassifier.classify(initial, probe))
        assertEquals(
            QuicServerResponseClassifier.ResponseType.VERSION_NEGOTIATION,
            QuicServerResponseClassifier.classify(negotiation, probe)
        )
        assertEquals(QuicServerResponseClassifier.ResponseType.RETRY, QuicServerResponseClassifier.classify(retry, probe))
        assertTrue(QuicServerResponseClassifier.isPlausible(initial, probe))
        assertTrue(QuicServerResponseClassifier.isPlausible(negotiation, probe))
        assertTrue(QuicServerResponseClassifier.isPlausible(retry, probe))
    }

    @Test
    fun retryMustCarryAValidVersionSpecificIntegrityTag() {
        val v1Probe = QuicInitialClassifier.inspectProbe(quicInitial())!!
        val v2Probe = QuicInitialClassifier.inspectProbe(quicInitial(version = 0x6b3343cf, type = 1))!!
        val postRetryProbe = QuicInitialClassifier.inspectProbe(
            quicInitialWithConnectionIds(
                destinationConnectionId = byteArrayOf(1, 2, 3, 4),
                sourceConnectionId = byteArrayOf(5, 6, 7, 8),
                token = byteArrayOf(9)
            )
        )!!
        val corruptV1 = quicRetry(v1Probe).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val corruptV2 = quicRetry(v2Probe).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertEquals(QuicServerResponseClassifier.ResponseType.RETRY, QuicServerResponseClassifier.classify(quicRetry(v1Probe), v1Probe))
        assertEquals(QuicServerResponseClassifier.ResponseType.RETRY, QuicServerResponseClassifier.classify(quicRetry(v2Probe), v2Probe))
        assertFalse(QuicServerResponseClassifier.isPlausible(corruptV1, v1Probe))
        assertFalse(QuicServerResponseClassifier.isPlausible(corruptV2, v2Probe))
        assertFalse(QuicServerResponseClassifier.isPlausible(quicRetry(postRetryProbe), postRetryProbe))
    }

    @Test
    fun retryIntegrityMatchesThePublishedQuicV2Vector() {
        val tag = QuicServerResponseClassifier.retryIntegrityTag(
            0x6b3343cf,
            hex("8394c8f03e515708"),
            hex("cf6b3343cf0008f067a5502a4262b5746f6b656e")
        )

        assertArrayEquals(hex("c8646ce8bfe33952d955543665dcc7b6"), tag!!)
    }

    @Test
    fun arbitraryOrConnectionIdMismatchedUdpCannotProveQuicReachability() {
        val probe = QuicInitialClassifier.inspectProbe(quicInitial())!!
        val mismatched = quicServerInitial(probe, destinationConnectionId = byteArrayOf(91, 92))
        val oversizedConnectionId = quicServerInitial(probe, serverConnectionId = ByteArray(21))

        assertFalse(QuicServerResponseClassifier.isPlausible(byteArrayOf(1), probe))
        assertFalse(QuicServerResponseClassifier.isPlausible("HTTP/1.1 403".toByteArray(), probe))
        assertFalse(QuicServerResponseClassifier.isPlausible(mismatched, probe))
        assertFalse(QuicServerResponseClassifier.isPlausible(oversizedConnectionId, probe))
        assertFalse(QuicServerResponseClassifier.isPlausible(quicVersionNegotiation(probe, probe.version), probe))
    }

    @Test
    fun responseVersionMustBeCoherentWithTheClientProbe() {
        val v1Probe = QuicInitialClassifier.inspectProbe(quicInitial())!!
        val v2Probe = QuicInitialClassifier.inspectProbe(quicInitial(version = 0x6b3343cf, type = 1))!!
        val futureProbe = QuicInitialClassifier.inspectProbe(quicInitial(version = 0x1a2a3a4a, type = 3))!!

        assertFalse(QuicServerResponseClassifier.isPlausible(quicServerInitial(v1Probe, 0x6b3343cf), v1Probe))
        assertFalse(QuicServerResponseClassifier.isPlausible(quicServerInitial(v2Probe, 1), v2Probe))
        assertFalse(QuicServerResponseClassifier.isPlausible(quicServerInitial(v1Probe, 0x1a2a3a4a), v1Probe))
        assertEquals(
            QuicServerResponseClassifier.ResponseType.FUTURE_VERSION,
            QuicServerResponseClassifier.classify(quicServerInitial(futureProbe), futureProbe)
        )
        assertFalse(
            QuicServerResponseClassifier.isPlausible(
                quicServerInitial(futureProbe, 0x2a3a4a5a),
                futureProbe
            )
        )
    }

    @Test
    fun versionNegotiationAndRetryRequireExactConnectionIdBinding() {
        val probe = QuicInitialClassifier.inspectProbe(quicInitial())!!
        val wrongDestination = byteArrayOf(31, 32, 33, 34)
        val wrongSource = byteArrayOf(41, 42, 43, 44, 45, 46, 47, 48)

        assertFalse(
            QuicServerResponseClassifier.isPlausible(
                quicVersionNegotiation(probe, 0x6b3343cf, destinationConnectionId = wrongDestination),
                probe
            )
        )
        assertFalse(
            QuicServerResponseClassifier.isPlausible(
                quicVersionNegotiation(probe, 0x6b3343cf, sourceConnectionId = wrongSource),
                probe
            )
        )
        assertFalse(
            QuicServerResponseClassifier.isPlausible(
                quicRetry(probe, destinationConnectionId = wrongDestination),
                probe
            )
        )
    }

    @Test
    fun blockedQuicReturnsAValidIcmpPortUnreachableForImmediateTcpFallback() {
        val original = validIpv4UdpPacket()

        val rejection = Ipv4IcmpPortUnreachable.build(original, original.size)

        assertNotNull(rejection)
        rejection!!
        assertEquals(1, rejection[9].toInt() and 0xff)
        assertEquals(3, rejection[20].toInt() and 0xff)
        assertEquals(3, rejection[21].toInt() and 0xff)
        assertArrayEquals(original.copyOfRange(16, 20), rejection.copyOfRange(12, 16))
        assertArrayEquals(original.copyOfRange(12, 16), rejection.copyOfRange(16, 20))
        assertArrayEquals(original, rejection.copyOfRange(28, rejection.size))
        assertEquals(0, checksum(rejection, 0, 20))
        assertEquals(0, checksum(rejection, 20, rejection.size - 20))
    }

    @Test
    fun blockedIpv6QuicReturnsAValidIcmpv6PortUnreachable() {
        val original = validIpv6UdpPacket()

        val rejection = Ipv6IcmpPortUnreachable.build(original, original.size)

        assertNotNull(rejection)
        rejection!!
        assertEquals(6, (rejection[0].toInt() ushr 4) and 0x0f)
        assertEquals(58, rejection[6].toInt() and 0xff)
        assertEquals(1, rejection[40].toInt() and 0xff)
        assertEquals(4, rejection[41].toInt() and 0xff)
        assertArrayEquals(original.copyOfRange(24, 40), rejection.copyOfRange(8, 24))
        assertArrayEquals(original.copyOfRange(8, 24), rejection.copyOfRange(24, 40))
        assertArrayEquals(original, rejection.copyOfRange(48, rejection.size))
        assertEquals(0, ipv6UpperLayerChecksum(rejection, 40, rejection.size - 40, 58))
    }

    @Test
    fun ipv6PortUnreachableSuppressesInvalidSourcesMulticastAndBadChecksums() {
        val valid = validIpv6UdpPacket()
        val zeroChecksum = valid.copyOf().also { put16(it, 46, 0) }
        val unspecifiedSource = valid.copyOf().also {
            it.fill(0, 8, 24)
            setIpv6UdpChecksum(it)
        }
        val multicastSource = valid.copyOf().also {
            it[8] = 0xff.toByte()
            setIpv6UdpChecksum(it)
        }
        val multicastDestination = valid.copyOf().also {
            it[24] = 0xff.toByte()
            setIpv6UdpChecksum(it)
        }

        assertNull(Ipv6IcmpPortUnreachable.build(zeroChecksum, zeroChecksum.size))
        assertNull(Ipv6IcmpPortUnreachable.build(unspecifiedSource, unspecifiedSource.size))
        assertNull(Ipv6IcmpPortUnreachable.build(multicastSource, multicastSource.size))
        assertNull(Ipv6IcmpPortUnreachable.build(multicastDestination, multicastDestination.size))
    }

    @Test
    fun ipv6PortUnreachableQuotesTheOriginalExtensionChainAfterNormalizedValidation() {
        val normalized = validIpv6UdpPacket()
        val invoking = ByteArray(normalized.size + 8)
        normalized.copyInto(invoking, 0, 0, 40)
        invoking[6] = 60
        put16(invoking, 4, invoking.size - 40)
        invoking[40] = 17
        normalized.copyInto(invoking, 48, 40)

        val rejection = Ipv6IcmpPortUnreachable.build(normalized, normalized.size, invoking)!!

        assertArrayEquals(invoking, rejection.copyOfRange(48, rejection.size))
        assertEquals(0, ipv6UpperLayerChecksum(rejection, 40, rejection.size - 40, 58))
    }

    @Test
    fun ipv6PortUnreachableRejectsAnUnrelatedOrMalformedInvokingPacket() {
        val normalized = validIpv6UdpPacket()
        val unrelated = normalized.copyOf().also { it[39] = 2 }
        val malformed = normalized.copyOf().also { put16(it, 4, it.size - 39) }

        assertNull(Ipv6IcmpPortUnreachable.build(normalized, normalized.size, unrelated))
        assertNull(Ipv6IcmpPortUnreachable.build(normalized, normalized.size, malformed))
    }

    private fun ipv4Packet(protocol: Int, destinationPort: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val packet = ByteArray(28 + payload.size)
        packet[0] = 0x45
        put16(packet, 2, packet.size)
        packet[9] = protocol.toByte()
        put16(packet, 20, 53000)
        put16(packet, 22, destinationPort)
        put16(packet, 24, 8 + payload.size)
        payload.copyInto(packet, 28)
        return packet
    }

    private fun ipv6Packet(protocol: Int, destinationPort: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val packet = ByteArray(48 + payload.size)
        packet[0] = 0x60
        put16(packet, 4, 8 + payload.size)
        packet[6] = protocol.toByte()
        packet[8] = 0x20
        packet[9] = 0x01
        packet[23] = 2
        packet[24] = 0x20
        packet[25] = 0x01
        packet[39] = 1
        put16(packet, 40, 53000)
        put16(packet, 42, destinationPort)
        put16(packet, 44, 8 + payload.size)
        payload.copyInto(packet, 48)
        if (protocol == 17) setIpv6UdpChecksum(packet)
        return packet
    }

    private fun quicInitial(version: Int = 1, type: Int = 0): ByteArray {
        return quicInitialWithConnectionIds(
            destinationConnectionId = ByteArray(8) { index -> (index + 1).toByte() },
            sourceConnectionId = byteArrayOf(11, 12, 13, 14),
            version = version,
            type = type
        )
    }

    private fun quicInitialWithConnectionIds(
        destinationConnectionId: ByteArray,
        sourceConnectionId: ByteArray,
        version: Int = 1,
        type: Int = 0,
        token: ByteArray = ByteArray(0)
    ): ByteArray {
        val payload = ByteArray(1_200)
        payload[0] = (0xc0 or (type shl 4)).toByte()
        put32(payload, 1, version)
        var cursor = 5
        payload[cursor++] = destinationConnectionId.size.toByte()
        destinationConnectionId.copyInto(payload, cursor)
        cursor += destinationConnectionId.size
        payload[cursor++] = sourceConnectionId.size.toByte()
        sourceConnectionId.copyInto(payload, cursor)
        cursor += sourceConnectionId.size
        payload[cursor++] = token.size.toByte()
        token.copyInto(payload, cursor)
        cursor += token.size
        val encodedLength = payload.size - cursor - 2
        payload[cursor++] = (0x40 or (encodedLength ushr 8)).toByte()
        payload[cursor] = encodedLength.toByte()
        return payload
    }

    private fun quicServerInitial(
        probe: QuicInitialClassifier.Probe,
        version: Int = probe.version,
        destinationConnectionId: ByteArray = probe.sourceConnectionId,
        serverConnectionId: ByteArray = byteArrayOf(9, 8, 7, 6)
    ): ByteArray {
        val type = if (version == 0x6b3343cf) 1 else 0
        val packet = ByteArray(
            5 + 1 + destinationConnectionId.size + 1 + serverConnectionId.size + 1 + 1 + 17
        )
        packet[0] = (0xc0 or (type shl 4)).toByte()
        put32(packet, 1, version)
        var cursor = 5
        packet[cursor++] = destinationConnectionId.size.toByte()
        destinationConnectionId.copyInto(packet, cursor)
        cursor += destinationConnectionId.size
        packet[cursor++] = serverConnectionId.size.toByte()
        serverConnectionId.copyInto(packet, cursor)
        cursor += serverConnectionId.size
        packet[cursor++] = 0
        packet[cursor++] = 17
        while (cursor < packet.size) packet[cursor++] = 1
        return packet
    }

    private fun quicVersionNegotiation(
        probe: QuicInitialClassifier.Probe,
        version: Int,
        destinationConnectionId: ByteArray = probe.sourceConnectionId,
        sourceConnectionId: ByteArray = probe.destinationConnectionId
    ): ByteArray {
        val packet = ByteArray(
            5 + 1 + destinationConnectionId.size + 1 + sourceConnectionId.size + 4
        )
        packet[0] = 0x80.toByte()
        var cursor = 5
        packet[cursor++] = destinationConnectionId.size.toByte()
        destinationConnectionId.copyInto(packet, cursor)
        cursor += destinationConnectionId.size
        packet[cursor++] = sourceConnectionId.size.toByte()
        sourceConnectionId.copyInto(packet, cursor)
        cursor += sourceConnectionId.size
        put32(packet, cursor, version)
        return packet
    }

    private fun quicRetry(
        probe: QuicInitialClassifier.Probe,
        destinationConnectionId: ByteArray = probe.sourceConnectionId
    ): ByteArray {
        val serverConnectionId = byteArrayOf(6, 7, 8, 9)
        val type = if (probe.version == 0x6b3343cf) 0 else 3
        val packetWithoutTag = ByteArray(
            5 + 1 + destinationConnectionId.size + 1 + serverConnectionId.size + 1
        )
        packetWithoutTag[0] = (0xc0 or (type shl 4)).toByte()
        put32(packetWithoutTag, 1, probe.version)
        var cursor = 5
        packetWithoutTag[cursor++] = destinationConnectionId.size.toByte()
        destinationConnectionId.copyInto(packetWithoutTag, cursor)
        cursor += destinationConnectionId.size
        packetWithoutTag[cursor++] = serverConnectionId.size.toByte()
        serverConnectionId.copyInto(packetWithoutTag, cursor)
        cursor += serverConnectionId.size
        packetWithoutTag[cursor] = 1
        val tag = QuicServerResponseClassifier.retryIntegrityTag(
            probe.version,
            probe.destinationConnectionId,
            packetWithoutTag
        )!!
        return packetWithoutTag + tag
    }

    private fun validIpv4UdpPacket(): ByteArray {
        return ByteArray(28).also { packet ->
            packet[0] = 0x45
            put16(packet, 2, packet.size)
            packet[8] = 64
            packet[9] = 17
            byteArrayOf(10, 0, 0, 2).copyInto(packet, 12)
            byteArrayOf(93, 184.toByte(), 216.toByte(), 34).copyInto(packet, 16)
            put16(packet, 20, 53000)
            put16(packet, 22, 443)
            put16(packet, 24, 8)
        }
    }

    private fun validIpv6UdpPacket(): ByteArray {
        return ByteArray(48).also { packet ->
            packet[0] = 0x60
            put16(packet, 4, 8)
            packet[6] = 17
            packet[7] = 64
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
                .copyInto(packet, 8)
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
                .copyInto(packet, 24)
            put16(packet, 40, 53000)
            put16(packet, 42, 443)
            put16(packet, 44, 8)
            setIpv6UdpChecksum(packet)
        }
    }

    private fun setIpv6UdpChecksum(packet: ByteArray) {
        put16(packet, 46, 0)
        val value = ipv6UpperLayerChecksum(packet, 40, packet.size - 40, 17)
        put16(packet, 46, if (value == 0) 0xffff else value)
    }

    private fun ipv6UpperLayerChecksum(packet: ByteArray, offset: Int, length: Int, protocol: Int): Int {
        var sum = 0L
        sum += checksumSum(packet, 8, 32)
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
            sum += (((data[index].toInt() and 0xff) shl 8) or
                (data[index + 1].toInt() and 0xff)).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        return sum
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += (((data[index].toInt() and 0xff) shl 8) or
                (data[index + 1].toInt() and 0xff)).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
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

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
