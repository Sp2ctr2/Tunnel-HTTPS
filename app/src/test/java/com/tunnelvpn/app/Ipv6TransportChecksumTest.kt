package com.tunnelvpn.app

import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6TransportChecksumTest {
    @Test
    fun validatesOddLengthsAndExtensionNormalizedOffsetsUnderSeededMutation() {
        val random = Random(0x82004443)
        repeat(256) { iteration ->
            val extensionLength = (iteration % 5) * 8
            val transportOffset = 40 + extensionLength
            val transportLength = 8 + iteration % 97
            val packet = ByteArray(transportOffset + transportLength)
            random.nextBytes(packet)
            packet[0] = 0x60
            put16(packet, 4, packet.size - 40)
            put16(packet, transportOffset + 4, transportLength)
            put16(packet, transportOffset + 6, 0)
            val value = Ipv6TransportChecksum.value(
                packet,
                packet.size,
                transportOffset,
                transportLength,
                UDP
            )
            put16(packet, transportOffset + 6, if (value == 0) 0xffff else value)

            assertTrue(
                Ipv6TransportChecksum.isValid(
                    packet,
                    packet.size,
                    transportOffset,
                    transportLength,
                    UDP,
                    UDP_CHECKSUM_OFFSET
                )
            )

            val corrupted = packet.copyOf()
            val mutableIndex = if (iteration and 1 == 0) {
                8 + random.nextInt(32)
            } else {
                transportOffset + random.nextInt(transportLength)
            }
            corrupted[mutableIndex] = (corrupted[mutableIndex].toInt() xor (1 shl random.nextInt(8))).toByte()
            assertFalse(
                Ipv6TransportChecksum.isValid(
                    corrupted,
                    corrupted.size,
                    transportOffset,
                    transportLength,
                    UDP,
                    UDP_CHECKSUM_OFFSET
                )
            )
        }
    }

    @Test
    fun rejectsIpv6UdpZeroChecksumAndInvalidBounds() {
        val packet = ByteArray(48).also {
            it[0] = 0x60
            put16(it, 4, 8)
            it[6] = UDP.toByte()
            put16(it, 44, 8)
        }

        assertFalse(Ipv6TransportChecksum.isValid(packet, packet.size, 40, 8, UDP, UDP_CHECKSUM_OFFSET))
        assertFalse(Ipv6TransportChecksum.isValid(packet, packet.size - 1, 40, 8, UDP, UDP_CHECKSUM_OFFSET))
        assertFalse(Ipv6TransportChecksum.isValid(packet, packet.size, 39, 9, UDP, UDP_CHECKSUM_OFFSET))
        assertFalse(Ipv6TransportChecksum.isValid(packet, packet.size, 40, 8, 256, UDP_CHECKSUM_OFFSET))
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private companion object {
        const val UDP = 17
        const val UDP_CHECKSUM_OFFSET = 6
    }
}
