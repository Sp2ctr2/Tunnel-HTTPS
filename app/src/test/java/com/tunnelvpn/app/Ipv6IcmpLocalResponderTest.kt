package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6IcmpLocalResponderTest {
    @Test
    fun validEchoToVirtualDnsGetsAValidatedEchoReply() {
        val request = request(byteArrayOf(0xfd.toByte(), 0, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))

        val response = Ipv6IcmpLocalResponder.respond(request)!!

        assertTrue(response.copyOfRange(8, 24).contentEquals(request.copyOfRange(24, 40)))
        assertTrue(response.copyOfRange(24, 40).contentEquals(request.copyOfRange(8, 24)))
        assertTrue((response[40].toInt() and 0xff) == 129)
        assertTrue(validChecksum(response))
    }

    @Test
    fun remoteMalformedAndNonEchoPacketsAreNotAnswered() {
        val remote = request(ByteArray(16).also { it[0] = 0x20; it[1] = 1 })
        val malformed = request(byteArrayOf(0xfd.toByte(), 0, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
            .also { it[47] = (it[47].toInt() xor 1).toByte() }
        val wrongType = request(byteArrayOf(0xfd.toByte(), 0, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
            .also { it[40] = 1 }

        assertNull(Ipv6IcmpLocalResponder.respond(remote))
        assertNull(Ipv6IcmpLocalResponder.respond(malformed))
        assertNull(Ipv6IcmpLocalResponder.respond(wrongType))
    }

    @Test
    fun unspecifiedAndMulticastSourcesAreNotAnswered() {
        val destination = byteArrayOf(0xfd.toByte(), 0, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val unspecified = request(destination, ByteArray(16))
        val multicast = request(destination, ByteArray(16).also { it[0] = 0xff.toByte(); it[15] = 1 })

        assertNull(Ipv6IcmpLocalResponder.respond(unspecified))
        assertNull(Ipv6IcmpLocalResponder.respond(multicast))
    }

    @Test
    fun largeReassembledEchoReplyCanBeFragmentedToTheTunMtu() {
        val destination = byteArrayOf(
            0xfd.toByte(), 0, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        )
        val request = request(destination, payloadSize = 2_000)
        val response = Ipv6IcmpLocalResponder.respond(request)!!

        val fragments = Ipv6PacketFragmenter.fragment(response, 1_280, 0x12345678)!!

        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.size <= 1_280 })
        val normalizer = Ipv6PacketNormalizer()
        var result: Ipv6PacketNormalizer.Result = Ipv6PacketNormalizer.Result.Pending
        fragments.reversed().forEach { fragment -> result = normalizer.process(fragment) }
        assertTrue(result is Ipv6PacketNormalizer.Result.Ready)
        val ready = result as Ipv6PacketNormalizer.Result.Ready
        assertTrue(ready.reassembled)
        assertTrue(ready.packet.contentEquals(response))
        assertEquals(129, ready.packet[40].toInt() and 0xff)
        assertTrue(validChecksum(ready.packet))
    }

    private fun request(
        destination: ByteArray,
        source: ByteArray = byteArrayOf(
            0xfd.toByte(), 0, 1, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2
        ),
        payloadSize: Int = 0
    ): ByteArray {
        val packet = ByteArray(48 + payloadSize)
        packet[0] = 0x60
        write16(packet, 4, packet.size - 40)
        packet[6] = 58
        packet[7] = 64
        source.copyInto(packet, 8)
        destination.copyInto(packet, 24)
        packet[40] = 128.toByte()
        packet[44] = 0x12
        packet[45] = 0x34
        packet[46] = 0x56
        packet[47] = 0x78
        for (index in 48 until packet.size) packet[index] = index.toByte()
        write16(packet, 42, checksum(packet))
        return packet
    }

    private fun validChecksum(packet: ByteArray): Boolean = checksumSum(packet) == 0xffff

    private fun checksum(packet: ByteArray): Int = checksumSum(packet).inv() and 0xffff

    private fun checksumSum(packet: ByteArray): Int {
        var sum = 0L
        sum += wordSum(packet, 8, 32)
        val payloadLength = packet.size - 40
        sum += payloadLength.toLong()
        sum += 58
        sum += wordSum(packet, 40, payloadLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt() and 0xffff
    }

    private fun wordSum(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += (((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        return sum
    }

    private fun write16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }
}
