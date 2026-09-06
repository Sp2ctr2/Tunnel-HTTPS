package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6IcmpPacketTooBigTest {
    @Test
    fun responseIsBoundedReportsMtuAndHasAValidChecksum() {
        val original = ByteArray(1600)
        original[0] = 0x60
        write16(original, 4, original.size - 40)
        original[6] = 17
        original[7] = 64
        repeat(16) { index ->
            original[8 + index] = index.toByte()
            original[24 + index] = (32 + index).toByte()
        }

        val response = Ipv6IcmpPacketTooBig.build(original, original.size, 1280)!!

        assertEquals(1280, response.size)
        assertEquals(2, response[40].toInt() and 0xff)
        assertEquals(1280, read32(response, 44))
        assertTrue(response.copyOfRange(8, 24).contentEquals(original.copyOfRange(24, 40)))
        assertTrue(response.copyOfRange(24, 40).contentEquals(original.copyOfRange(8, 24)))
        assertEquals(0, checksum(response))
    }

    @Test
    fun quoteNeverExceedsTheIpv6MinimumMtuAtLargeConfiguredMtu() {
        val original = packet(40_000, 17)

        val response = Ipv6IcmpPacketTooBig.build(original, original.size, 32_768)!!

        assertEquals(1_280, response.size)
        assertEquals(32_768, read32(response, 44))
        assertEquals(0, checksum(response))
    }

    @Test
    fun suppressesErrorsForNonOversizeInvalidSourceAndIcmpv6Errors() {
        val original = packet(1_600, 17)
        val unspecifiedSource = original.copyOf().also { it.fill(0, 8, 24) }
        val multicastSource = original.copyOf().also { it[8] = 0xff.toByte() }
        val icmpError = packet(1_600, 58).also { it[40] = 1 }

        assertNull(Ipv6IcmpPacketTooBig.build(original, original.size, 2_000))
        assertNull(Ipv6IcmpPacketTooBig.build(unspecifiedSource, unspecifiedSource.size, 1_280))
        assertNull(Ipv6IcmpPacketTooBig.build(multicastSource, multicastSource.size, 1_280))
        assertNull(Ipv6IcmpPacketTooBig.build(icmpError, icmpError.size, 1_280))
    }

    @Test
    fun suppressesErrorsForIcmpv6Redirects() {
        val redirect = packet(1_600, 58).also { it[40] = 137.toByte() }

        assertNull(Ipv6IcmpPacketTooBig.build(redirect, redirect.size, 1_280))
    }

    @Test
    fun multicastInvokingDestinationRequiresAnExplicitUnicastErrorSource() {
        val original = packet(1_600, 17).also { it[24] = 0xff.toByte() }
        val source = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }

        assertNull(Ipv6IcmpPacketTooBig.build(original, original.size, 1_280))
        val response = Ipv6IcmpPacketTooBig.build(original, original.size, 1_280, source)!!
        assertTrue(response.copyOfRange(8, 24).contentEquals(source))
        assertEquals(0, checksum(response))
    }

    @Test
    fun validatesTheNormalizedPacketButQuotesTheOriginalExtensionChain() {
        val normalized = packet(1_600, 17)
        val invoking = withDestinationOptions(normalized)

        val response = Ipv6IcmpPacketTooBig.build(
            normalized,
            normalized.size,
            1_280,
            invokingPacket = invoking
        )!!

        assertEquals(60, response[48 + 6].toInt() and 0xff)
        assertTrue(response.copyOfRange(48, response.size).contentEquals(invoking.copyOf(1_232)))
        assertEquals(0, checksum(response))
    }

    @Test
    fun rejectsAnUnrelatedOrMalformedInvokingPacket() {
        val normalized = packet(1_600, 17)
        val unrelated = withDestinationOptions(normalized).also { it[39] = 2 }
        val truncated = withDestinationOptions(normalized).copyOf(normalized.size)

        assertNull(Ipv6IcmpPacketTooBig.build(
            normalized,
            normalized.size,
            1_280,
            invokingPacket = unrelated
        ))
        assertNull(Ipv6IcmpPacketTooBig.build(
            normalized,
            normalized.size,
            1_280,
            invokingPacket = truncated
        ))
    }

    private fun packet(size: Int, protocol: Int): ByteArray {
        return ByteArray(size).also { packet ->
            packet[0] = 0x60
            write16(packet, 4, size - 40)
            packet[6] = protocol.toByte()
            packet[7] = 64
            packet[8] = 0x20
            packet[9] = 0x01
            packet[23] = 2
            packet[24] = 0x20
            packet[25] = 0x01
            packet[39] = 1
        }
    }

    private fun withDestinationOptions(packet: ByteArray): ByteArray {
        val result = ByteArray(packet.size + 8)
        packet.copyInto(result, 0, 0, 40)
        result[6] = 60
        write16(result, 4, result.size - 40)
        result[40] = packet[6]
        result[41] = 0
        packet.copyInto(result, 48, 40)
        return result
    }

    private fun checksum(packet: ByteArray): Int {
        val length = packet.size - 40
        var sum = wordSum(packet, 8, 32) + length + 58
        sum += wordSum(packet, 40, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun wordSum(data: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        var index = offset
        while (index + 1 < offset + length) {
            result += (((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)).toLong()
            index += 2
        }
        return result
    }

    private fun write16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun read32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }
}
