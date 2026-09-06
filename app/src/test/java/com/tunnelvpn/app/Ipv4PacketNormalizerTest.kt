package com.tunnelvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4PacketNormalizerTest {
    @Test
    fun reassemblesSixtyKilobyteUdpDatagramOutOfOrderForTheCommonSecurityPath() {
        val udp = udpDatagram(ByteArray(59_992) { (it * 31).toByte() })
        val expected = ipv4Packet(udp, identification = 0x1234)
        val split = 32_744
        val first = ipv4Packet(
            udp.copyOfRange(0, split),
            identification = 0x1234,
            fragmentField = MORE_FRAGMENTS
        )
        val last = ipv4Packet(
            udp.copyOfRange(split, udp.size),
            identification = 0x1234,
            fragmentField = split / 8
        )
        val normalizer = Ipv4PacketNormalizer()

        assertTrue(normalizer.process(last) is Ipv4PacketNormalizer.Result.Pending)
        val result = normalizer.process(first)

        assertTrue(result is Ipv4PacketNormalizer.Result.Ready)
        val ready = result as Ipv4PacketNormalizer.Result.Ready
        assertTrue(ready.reassembled)
        assertArrayEquals(expected, ready.packet)
        assertEquals(60_020, ready.packet.size)
        assertEquals(ready.packet.size, u16(ready.packet, 2))
        assertEquals(0, u16(ready.packet, 6))
        assertEquals(0, checksum(ready.packet, 0, 20))
        assertTrue(!LocalProtectionEngine.shouldDropIpv4BeforeForwarding(ready.packet, ready.packet.size))
        assertEquals(0, normalizer.retainedFragmentBytes())
        assertEquals(0, normalizer.pendingAssemblies())
    }

    @Test
    fun unfragmentedFastPathReturnsOneOwnedPacketAndPreservesDf() {
        val input = ipv4Packet(udpDatagram(ByteArray(24)), fragmentField = DONT_FRAGMENT)
        val expected = input.copyOf()

        val result = Ipv4PacketNormalizer().process(input)

        assertTrue(result is Ipv4PacketNormalizer.Result.Ready)
        val ready = result as Ipv4PacketNormalizer.Result.Ready
        assertTrue(!ready.reassembled)
        assertNotSame(input, ready.packet)
        input.fill(0)
        assertArrayEquals(expected, ready.packet)
        assertEquals(DONT_FRAGMENT, u16(ready.packet, 6))
    }

    @Test
    fun validatesHeaderChecksumLengthsReservedBitAndIllegalDfFragments() {
        val udp = udpDatagram(ByteArray(16))
        val badChecksum = ipv4Packet(udp).also { it[8] = (it[8].toInt() xor 1).toByte() }
        val badTotalLength = ipv4Packet(udp).also {
            put16(it, 2, it.size - 1)
            rewriteChecksum(it, 20)
        }
        val badHeaderLength = ipv4Packet(udp).also {
            it[0] = 0x44
            rewriteChecksum(it, 20)
        }
        val reserved = ipv4Packet(udp.copyOfRange(0, 16), fragmentField = RESERVED or MORE_FRAGMENTS)
        val dfFragment = ipv4Packet(
            udp.copyOfRange(0, 16),
            fragmentField = DONT_FRAGMENT or MORE_FRAGMENTS
        )

        listOf(badChecksum, badTotalLength, badHeaderLength, reserved, dfFragment).forEach { packet ->
            assertTrue(Ipv4PacketNormalizer().process(packet) is Ipv4PacketNormalizer.Result.Rejected)
        }
    }

    @Test
    fun rejectsUnsafeOptionsMisalignedMoreFragmentsAndOffsetOverflow() {
        val unsafeOptions = ipv4Packet(
            udpDatagram(ByteArray(8)),
            options = byteArrayOf(7, 4, 0, 0)
        )
        val misaligned = ipv4Packet(
            ByteArray(15),
            protocol = UDP,
            fragmentField = MORE_FRAGMENTS
        )
        val overflow = ipv4Packet(
            ByteArray(8),
            protocol = UDP,
            fragmentField = MAX_FRAGMENT_OFFSET
        )

        assertTrue(Ipv4PacketNormalizer().process(unsafeOptions) is Ipv4PacketNormalizer.Result.Rejected)
        assertTrue(Ipv4PacketNormalizer().process(misaligned) is Ipv4PacketNormalizer.Result.Rejected)
        assertTrue(Ipv4PacketNormalizer().process(overflow) is Ipv4PacketNormalizer.Result.Rejected)
    }

    @Test
    fun exactDuplicateIsIdempotentButOverlapTombstonesIdentityUntilOriginalExpiry() {
        var now = 100L
        val udp = udpDatagram(ByteArray(24) { it.toByte() })
        val first = ipv4Packet(
            udp.copyOfRange(0, 16),
            identification = 77,
            fragmentField = MORE_FRAGMENTS
        )
        val overlap = ipv4Packet(
            udp.copyOfRange(8, udp.size),
            identification = 77,
            fragmentField = 1
        )
        val normalizer = Ipv4PacketNormalizer(clock = { now }, fragmentTimeoutMs = 1_000L)

        assertTrue(normalizer.process(first) is Ipv4PacketNormalizer.Result.Pending)
        val retained = normalizer.retainedFragmentBytes()
        assertTrue(normalizer.process(first) is Ipv4PacketNormalizer.Result.Pending)
        assertEquals(retained, normalizer.retainedFragmentBytes())
        assertTrue(normalizer.process(overlap) is Ipv4PacketNormalizer.Result.Rejected)
        assertEquals(0, normalizer.retainedFragmentBytes())

        now = 1_099L
        assertTrue(normalizer.process(first) is Ipv4PacketNormalizer.Result.Rejected)
        now = 1_100L
        assertTrue(normalizer.process(first) is Ipv4PacketNormalizer.Result.Pending)
    }

    @Test
    fun resetAndFixedExpiryReleaseQueuedFragmentGenerations() {
        var now = 500L
        val first = firstUdpFragment(90)
        val normalizer = Ipv4PacketNormalizer(clock = { now }, fragmentTimeoutMs = 250L)

        assertTrue(normalizer.process(first) is Ipv4PacketNormalizer.Result.Pending)
        assertEquals(1, normalizer.pendingAssemblies())
        now = 750L
        normalizer.expire()
        assertEquals(0, normalizer.pendingAssemblies())
        assertEquals(0, normalizer.retainedFragmentBytes())

        assertTrue(normalizer.process(first) is Ipv4PacketNormalizer.Result.Pending)
        normalizer.reset()
        assertEquals(0, normalizer.pendingAssemblies())
        assertEquals(0, normalizer.retainedFragmentBytes())
    }

    @Test
    fun contextByteAndFragmentCapsFailClosed() {
        assertEquals(64, Ipv4PacketNormalizer.DEFAULT_MAX_ASSEMBLIES)
        assertEquals(2 * 1024 * 1024, Ipv4PacketNormalizer.DEFAULT_MAX_BUFFERED_BYTES)

        val contextBounded = Ipv4PacketNormalizer(maxAssemblies = 2)
        assertTrue(contextBounded.process(firstUdpFragment(1)) is Ipv4PacketNormalizer.Result.Pending)
        assertTrue(contextBounded.process(firstUdpFragment(2)) is Ipv4PacketNormalizer.Result.Pending)
        assertTrue(contextBounded.process(firstUdpFragment(3)) is Ipv4PacketNormalizer.Result.Pending)
        assertEquals(2, contextBounded.pendingAssemblies())
        assertTrue(contextBounded.process(firstUdpFragment(1)) is Ipv4PacketNormalizer.Result.Rejected)

        val byteBounded = Ipv4PacketNormalizer(maxBufferedBytes = 55)
        assertTrue(byteBounded.process(firstUdpFragment(10)) is Ipv4PacketNormalizer.Result.Pending)
        assertTrue(byteBounded.process(firstUdpFragment(11)) is Ipv4PacketNormalizer.Result.Rejected)
        assertTrue(byteBounded.retainedFragmentBytes() <= 55)

        val fragmentBounded = Ipv4PacketNormalizer(maxFragmentsPerAssembly = 2)
        val udp = udpDatagram(ByteArray(16))
        assertTrue(fragmentBounded.process(ipv4Packet(
            udp.copyOfRange(0, 8), 12, MORE_FRAGMENTS
        )) is Ipv4PacketNormalizer.Result.Pending)
        assertTrue(fragmentBounded.process(ipv4Packet(
            udp.copyOfRange(8, 16), 12, MORE_FRAGMENTS or 1
        )) is Ipv4PacketNormalizer.Result.Pending)
        assertTrue(fragmentBounded.process(ipv4Packet(
            udp.copyOfRange(16, 24), 12, 2
        )) is Ipv4PacketNormalizer.Result.Rejected)
        assertEquals(0, fragmentBounded.pendingAssemblies())
    }

    @Test
    fun safePaddingOptionsRemainInCanonicalFirstHeaderWithFreshChecksum() {
        val udp = udpDatagram(ByteArray(16))
        val first = ipv4Packet(
            udp.copyOfRange(0, 16),
            identification = 31,
            fragmentField = MORE_FRAGMENTS,
            options = byteArrayOf(1, 0, 0, 0)
        )
        val last = ipv4Packet(
            udp.copyOfRange(16, udp.size),
            identification = 31,
            fragmentField = 2
        )
        val normalizer = Ipv4PacketNormalizer()

        assertTrue(normalizer.process(last) is Ipv4PacketNormalizer.Result.Pending)
        val result = normalizer.process(first)

        assertTrue(result is Ipv4PacketNormalizer.Result.Ready)
        val packet = (result as Ipv4PacketNormalizer.Result.Ready).packet
        assertEquals(24, (packet[0].toInt() and 0x0f) * 4)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), packet.copyOfRange(20, 24))
        assertEquals(packet.size, u16(packet, 2))
        assertEquals(0, u16(packet, 6))
        assertEquals(0, checksum(packet, 0, 24))
    }

    private fun firstUdpFragment(identification: Int): ByteArray {
        val udp = udpDatagram(ByteArray(16))
        return ipv4Packet(
            udp.copyOfRange(0, 16),
            identification = identification,
            fragmentField = MORE_FRAGMENTS
        )
    }

    private fun udpDatagram(payload: ByteArray): ByteArray {
        return ByteArray(8 + payload.size).also { udp ->
            put16(udp, 0, 12_345)
            put16(udp, 2, 4_321)
            put16(udp, 4, udp.size)
            payload.copyInto(udp, 8)
        }
    }

    private fun ipv4Packet(
        payload: ByteArray,
        identification: Int = 1,
        fragmentField: Int = 0,
        protocol: Int = UDP,
        options: ByteArray = ByteArray(0)
    ): ByteArray {
        require(options.size % 4 == 0)
        val headerLength = 20 + options.size
        return ByteArray(headerLength + payload.size).also { packet ->
            packet[0] = ((4 shl 4) or (headerLength / 4)).toByte()
            put16(packet, 2, packet.size)
            put16(packet, 4, identification)
            put16(packet, 6, fragmentField)
            packet[8] = 64
            packet[9] = protocol.toByte()
            byteArrayOf(10, 0, 0, 2).copyInto(packet, 12)
            byteArrayOf(10, 0, 0, 3).copyInto(packet, 16)
            options.copyInto(packet, 20)
            payload.copyInto(packet, headerLength)
            rewriteChecksum(packet, headerLength)
        }
    }

    private fun rewriteChecksum(packet: ByteArray, headerLength: Int) {
        put16(packet, 10, 0)
        put16(packet, 10, checksum(packet, 0, headerLength))
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)
            index += 2
        }
        if (index < end) sum += (data[index].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private companion object {
        const val UDP = 17
        const val RESERVED = 0x8000
        const val DONT_FRAGMENT = 0x4000
        const val MORE_FRAGMENTS = 0x2000
        const val MAX_FRAGMENT_OFFSET = 0x1fff
    }
}
