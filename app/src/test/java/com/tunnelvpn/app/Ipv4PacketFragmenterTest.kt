package com.tunnelvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4PacketFragmenterTest {
    @Test
    fun mtuBoundedFragmentsPreserveTheCompleteUdpDatagram() {
        val original = udpPacket(ByteArray(3_000) { (it * 17).toByte() })

        val fragments = Ipv4PacketFragmenter.fragment(original, 1_280, 0x1234)!!

        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.size <= 1_280 })
        fragments.forEachIndexed { index, fragment ->
            assertEquals(fragment.size, u16(fragment, 2))
            assertEquals(0x1234, u16(fragment, 4))
            assertEquals(0, checksum(fragment, 0, 20))
            assertEquals(index != fragments.lastIndex, u16(fragment, 6) and 0x2000 != 0)
            if (index != fragments.lastIndex) assertEquals(0, (fragment.size - 20) % 8)
        }
        val rebuiltPayload = ByteArray(original.size - 20)
        fragments.reversed().forEach { fragment ->
            val offset = (u16(fragment, 6) and 0x1fff) * 8
            fragment.copyInto(rebuiltPayload, offset, 20)
        }
        assertArrayEquals(original.copyOfRange(20, original.size), rebuiltPayload)
        assertEquals(0, udpChecksum(original))
    }

    @Test
    fun unfragmentedPacketIsReturnedWithoutChangingItsDfBit() {
        val original = udpPacket(ByteArray(16)).also {
            put16(it, 6, 0x4000)
            setHeaderChecksum(it)
        }

        val result = Ipv4PacketFragmenter.fragment(original, 1_500, 9)!!

        assertEquals(1, result.size)
        assertTrue(result.single() === original)
        assertEquals(0x4000, u16(result.single(), 6))
    }

    @Test
    fun rejectsMalformedPrefragmentedOrOptionBearingPackets() {
        val valid = udpPacket(ByteArray(2_000))
        val prefragmented = valid.copyOf().also {
            put16(it, 6, 0x2000)
            setHeaderChecksum(it)
        }
        val corrupt = valid.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() }
        val options = valid.copyOf().also { it[0] = 0x46 }
        val oversizedDf = valid.copyOf().also {
            put16(it, 6, 0x4000)
            setHeaderChecksum(it)
        }

        assertNull(Ipv4PacketFragmenter.fragment(prefragmented, 1_280, 1))
        assertNull(Ipv4PacketFragmenter.fragment(corrupt, 1_280, 1))
        assertNull(Ipv4PacketFragmenter.fragment(options, 1_280, 1))
        assertNull(Ipv4PacketFragmenter.fragment(oversizedDf, 1_280, 1))
        assertNull(Ipv4PacketFragmenter.fragment(valid, 27, 1))
    }

    private fun udpPacket(payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        return ByteArray(20 + udpLength).also { packet ->
            packet[0] = 0x45
            put16(packet, 2, packet.size)
            put16(packet, 4, 7)
            put16(packet, 6, 0)
            packet[8] = 64
            packet[9] = 17
            byteArrayOf(93, 184.toByte(), 216.toByte(), 34).copyInto(packet, 12)
            byteArrayOf(10, 0, 0, 2).copyInto(packet, 16)
            put16(packet, 20, 18_082)
            put16(packet, 22, 50_000)
            put16(packet, 24, udpLength)
            payload.copyInto(packet, 28)
            put16(packet, 26, udpChecksumValue(packet))
            setHeaderChecksum(packet)
        }
    }

    private fun setHeaderChecksum(packet: ByteArray) {
        put16(packet, 10, 0)
        put16(packet, 10, checksum(packet, 0, 20))
    }

    private fun udpChecksum(packet: ByteArray): Int {
        var sum = 0L
        sum += u16(packet, 12).toLong() + u16(packet, 14).toLong()
        sum += u16(packet, 16).toLong() + u16(packet, 18).toLong()
        sum += 17L + (packet.size - 20).toLong()
        sum += checksumSum(packet, 20, packet.size - 20)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun udpChecksumValue(packet: ByteArray): Int {
        put16(packet, 26, 0)
        val value = udpChecksum(packet)
        return if (value == 0) 0xffff else value
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = checksumSum(data, offset, length)
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

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }
}
