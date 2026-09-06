package com.tunnelvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6PacketFragmenterTest {
    @Test
    fun mtuBoundedFragmentsReassembleWithCePreserved() {
        val original = udpPacket(ByteArray(2_452) { (it and 0xff).toByte() }, ecn = 2)
        val fragments = Ipv6PacketFragmenter.fragment(original, 1_280, 0x10203040)!!

        assertEquals(2, fragments.size)
        assertTrue(fragments.all { it.size <= 1_280 })
        assertTrue(fragments.dropLast(1).all { (it.size - 48) % 8 == 0 })
        fragments.forEach { fragment ->
            assertEquals(44, fragment[6].toInt() and 0xff)
            assertEquals(17, fragment[40].toInt() and 0xff)
            assertEquals(0x10203040, u32(fragment, 44))
        }
        setEcn(fragments.last(), 3)
        val normalizer = Ipv6PacketNormalizer()
        var result: Ipv6PacketNormalizer.Result = Ipv6PacketNormalizer.Result.Pending
        fragments.reversed().forEach { fragment -> result = normalizer.process(fragment) }

        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        val ready = result as Ipv6PacketNormalizer.Result.Ready
        assertTrue(ready.reassembled)
        assertEquals(3, ecn(ready.packet))
        val expected = original.copyOf().also { setEcn(it, 3) }
        assertArrayEquals(expected, ready.packet)
    }

    @Test
    fun notEctAndCeFragmentsRejectTheWholeDatagram() {
        val original = udpPacket(ByteArray(2_452), ecn = 0)
        val fragments = Ipv6PacketFragmenter.fragment(original, 1_280, 7)!!
        setEcn(fragments.last(), 3)
        val normalizer = Ipv6PacketNormalizer()

        assertTrue(normalizer.process(fragments.first()) is Ipv6PacketNormalizer.Result.Pending)
        assertTrue(normalizer.process(fragments.last()) is Ipv6PacketNormalizer.Result.Rejected)
        assertTrue(normalizer.process(fragments.first()) is Ipv6PacketNormalizer.Result.Rejected)
    }

    @Test
    fun hopByHopHeaderRemainsBeforeTheFragmentHeaderAndRoundTrips() {
        val direct = udpPacket(ByteArray(2_452) { (it * 7).toByte() }, ecn = 1)
        val original = withExtension(direct, 0)

        val fragments = Ipv6PacketFragmenter.fragment(original, 1_280, 0x55667788)!!

        assertEquals(0, fragments.first()[6].toInt() and 0xff)
        assertEquals(44, fragments.first()[40].toInt() and 0xff)
        assertEquals(17, fragments.first()[48].toInt() and 0xff)
        assertTrue(fragments.all { it.size <= 1_280 })
        val normalizer = Ipv6PacketNormalizer()
        var result: Ipv6PacketNormalizer.Result = Ipv6PacketNormalizer.Result.Pending
        fragments.reversed().forEach { result = normalizer.process(it) }
        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(direct, (result as Ipv6PacketNormalizer.Result.Ready).packet)
    }

    @Test
    fun finalDestinationOptionsStayInTheFragmentablePartAndRoundTrip() {
        val direct = udpPacket(ByteArray(2_452) { (it * 11).toByte() }, ecn = 2)
        val original = withExtension(direct, 60)

        val fragments = Ipv6PacketFragmenter.fragment(original, 1_280, 0x11223344)!!

        assertEquals(44, fragments.first()[6].toInt() and 0xff)
        assertEquals(60, fragments.first()[40].toInt() and 0xff)
        assertEquals(17, fragments.first()[48].toInt() and 0xff)
        val normalizer = Ipv6PacketNormalizer()
        var result: Ipv6PacketNormalizer.Result = Ipv6PacketNormalizer.Result.Pending
        fragments.forEach { result = normalizer.process(it) }
        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(direct, (result as Ipv6PacketNormalizer.Result.Ready).packet)
    }

    @Test
    fun routingHeaderRemainsBeforeTheFragmentHeaderAndRoundTrips() {
        val direct = udpPacket(ByteArray(2_452) { (it * 13).toByte() }, ecn = 3)
        val original = withExtension(direct, 43)

        val fragments = Ipv6PacketFragmenter.fragment(original, 1_280, 0x76543210)!!

        assertEquals(43, fragments.first()[6].toInt() and 0xff)
        assertEquals(44, fragments.first()[40].toInt() and 0xff)
        assertEquals(17, fragments.first()[48].toInt() and 0xff)
        val normalizer = Ipv6PacketNormalizer()
        var result: Ipv6PacketNormalizer.Result = Ipv6PacketNormalizer.Result.Pending
        fragments.reversed().forEach { result = normalizer.process(it) }
        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        assertArrayEquals(direct, (result as Ipv6PacketNormalizer.Result.Ready).packet)
    }

    private fun withExtension(packet: ByteArray, extensionProtocol: Int): ByteArray {
        val result = ByteArray(packet.size + 8)
        packet.copyInto(result, 0, 0, 40)
        result[6] = extensionProtocol.toByte()
        put16(result, 4, packet.size - 40 + 8)
        result[40] = 17
        packet.copyInto(result, 48, 40, packet.size)
        return result
    }

    private fun udpPacket(payload: ByteArray, ecn: Int): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)
        packet[0] = 0x60
        setEcn(packet, ecn)
        put16(packet, 4, udpLength)
        packet[6] = 17
        packet[7] = 64
        packet[8] = 0x20
        packet[9] = 1
        packet[23] = 2
        packet[24] = 0x20
        packet[25] = 1
        packet[39] = 1
        put16(packet, 40, 50_000)
        put16(packet, 42, 443)
        put16(packet, 44, udpLength)
        payload.copyInto(packet, 48)
        put16(packet, 46, Ipv6TransportChecksum.value(packet, packet.size, 40, udpLength, 17))
        return packet
    }

    private fun ecn(packet: ByteArray): Int = (packet[1].toInt() ushr 4) and 0x03

    private fun setEcn(packet: ByteArray, value: Int) {
        packet[1] = ((packet[1].toInt() and 0xcf) or (value shl 4)).toByte()
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }
}
