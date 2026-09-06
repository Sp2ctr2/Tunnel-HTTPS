package com.tunnelvpn.app

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowTwinChecksumTest {
    @Test
    fun optimizedChecksumMatchesIndependentBytewiseOracle() {
        val random = Random(SEED)
        val offsets = intArrayOf(40, 41, 47, 48, 56, 72, 104)
        val protocols = intArrayOf(0, 6, 17, 58, 255)
        val lengths = intArrayOf(
            0, 1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33,
            255, 256, 257, 1_159, 1_160, 1_161, 4_055, 4_056,
            4_057, 8_959, 8_960, 8_961, 32_727, 32_728, 32_729,
            65_533, 65_534, 65_535
        )

        offsets.forEach { transportOffset ->
            val maximumLength = MAX_PACKET_LENGTH - transportOffset
            lengths.filter { it <= maximumLength }.forEach { transportLength ->
                protocols.forEach { protocol ->
                    verifyCase(random, transportOffset, transportLength, protocol)
                }
            }
            verifyCase(random, transportOffset, maximumLength, UDP)
        }

        repeat(1_024) { iteration ->
            val transportOffset = offsets[iteration % offsets.size]
            val maximumLength = MAX_PACKET_LENGTH - transportOffset
            var transportLength = when {
                iteration % 32 == 0 -> maximumLength
                iteration % 8 == 0 -> random.nextInt(minOf(maximumLength, 32_768) + 1)
                else -> random.nextInt(minOf(maximumLength, 4_097) + 1)
            }
            if ((transportLength and 1) != (iteration and 1)) {
                transportLength = if (transportLength < maximumLength) {
                    transportLength + 1
                } else {
                    transportLength - 1
                }
            }
            verifyCase(random, transportOffset, transportLength, protocols[iteration % protocols.size])
        }
    }

    @Test
    fun zeroUdpChecksumAndInvalidBoundsRemainRejected() {
        val packet = ByteArray(128) { index -> (index * 37).toByte() }
        packet[0] = 0x60
        put16(packet, 4, packet.size - IPV6_HEADER_LENGTH)
        packet[6] = UDP.toByte()
        put16(packet, 44, packet.size - IPV6_HEADER_LENGTH)
        put16(packet, 46, 0)

        assertFalse(referenceIsValid(packet, packet.size, 40, 88, UDP, UDP_CHECKSUM_OFFSET))
        assertFalse(Ipv6TransportChecksum.isValid(packet, packet.size, 40, 88, UDP, UDP_CHECKSUM_OFFSET))

        val invalidBounds = arrayOf(
            intArrayOf(39, 40, -1, UDP),
            intArrayOf(packet.size + 1, 40, 89, UDP),
            intArrayOf(packet.size, 39, 89, UDP),
            intArrayOf(packet.size, packet.size + 1, -1, UDP),
            intArrayOf(packet.size, 40, -1, UDP),
            intArrayOf(packet.size, 40, 87, UDP),
            intArrayOf(packet.size, 40, 88, -1),
            intArrayOf(packet.size, 40, 88, 256)
        )
        invalidBounds.forEach { values ->
            assertFalse(referenceIsValid(packet, values[0], values[1], values[2], values[3], null))
            assertFalse(Ipv6TransportChecksum.isValid(packet, values[0], values[1], values[2], values[3], null))
            assertThrows(IllegalArgumentException::class.java) {
                referenceValue(packet, values[0], values[1], values[2], values[3])
            }
            assertThrows(IllegalArgumentException::class.java) {
                Ipv6TransportChecksum.value(packet, values[0], values[1], values[2], values[3])
            }
        }

        assertFalse(referenceIsValid(packet, packet.size, 40, 88, UDP, -1))
        assertFalse(Ipv6TransportChecksum.isValid(packet, packet.size, 40, 88, UDP, -1))
        assertFalse(referenceIsValid(packet, packet.size, 40, 88, UDP, 87))
        assertFalse(Ipv6TransportChecksum.isValid(packet, packet.size, 40, 88, UDP, 87))
    }

    private fun verifyCase(
        random: Random,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int
    ) {
        val packetLength = transportOffset + transportLength
        val packet = ByteArray(packetLength + random.nextInt(17))
        random.nextBytes(packet)
        packet[0] = 0x60
        put16(packet, 4, packetLength - IPV6_HEADER_LENGTH)

        assertEquals(
            referenceValue(packet, packetLength, transportOffset, transportLength, protocol),
            Ipv6TransportChecksum.value(packet, packetLength, transportOffset, transportLength, protocol)
        )
        assertEquals(
            referenceIsValid(packet, packetLength, transportOffset, transportLength, protocol, null),
            Ipv6TransportChecksum.isValid(packet, packetLength, transportOffset, transportLength, protocol, null)
        )
        if (transportLength < 2) return

        val checksumOffset = if (transportLength >= UDP_HEADER_LENGTH) UDP_CHECKSUM_OFFSET else 0
        put16(packet, transportOffset + checksumOffset, 0)
        val checksum = referenceValue(packet, packetLength, transportOffset, transportLength, protocol)
        put16(packet, transportOffset + checksumOffset, if (checksum == 0) 0xffff else checksum)

        assertTrue(referenceIsValid(
            packet,
            packetLength,
            transportOffset,
            transportLength,
            protocol,
            checksumOffset
        ))
        assertTrue(Ipv6TransportChecksum.isValid(
            packet,
            packetLength,
            transportOffset,
            transportLength,
            protocol,
            checksumOffset
        ))

        val corrupted = packet.copyOf()
        val mutableIndex = if (random.nextBoolean()) {
            SOURCE_OFFSET + random.nextInt(IPV6_ADDRESS_BYTES)
        } else {
            transportOffset + random.nextInt(transportLength)
        }
        corrupted[mutableIndex] = (corrupted[mutableIndex].toInt() xor
            (1 shl random.nextInt(8))).toByte()
        assertFalse(referenceIsValid(
            corrupted,
            packetLength,
            transportOffset,
            transportLength,
            protocol,
            checksumOffset
        ))
        assertFalse(Ipv6TransportChecksum.isValid(
            corrupted,
            packetLength,
            transportOffset,
            transportLength,
            protocol,
            checksumOffset
        ))
    }

    private fun referenceIsValid(
        packet: ByteArray,
        packetLength: Int,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int,
        rejectZeroChecksumAt: Int?
    ): Boolean {
        if (packetLength !in IPV6_HEADER_LENGTH..packet.size) return false
        if (transportOffset !in IPV6_HEADER_LENGTH..packetLength) return false
        if (transportLength < 0 || transportLength != packetLength - transportOffset) return false
        if (protocol !in 0..0xff) return false
        if (rejectZeroChecksumAt != null) {
            if (rejectZeroChecksumAt < 0 || transportLength < 2 ||
                rejectZeroChecksumAt > transportLength - 2
            ) return false
            val absolute = transportOffset + rejectZeroChecksumAt
            if ((packet[absolute].toInt() and 0xff) == 0 &&
                (packet[absolute + 1].toInt() and 0xff) == 0
            ) return false
        }
        return referenceSum(packet, transportOffset, transportLength, protocol) == 0xffff
    }

    private fun referenceValue(
        packet: ByteArray,
        packetLength: Int,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int
    ): Int {
        require(packetLength in IPV6_HEADER_LENGTH..packet.size)
        require(transportOffset in IPV6_HEADER_LENGTH..packetLength)
        require(transportLength >= 0 && transportLength == packetLength - transportOffset)
        require(protocol in 0..0xff)
        return referenceSum(packet, transportOffset, transportLength, protocol) xor 0xffff
    }

    private fun referenceSum(
        packet: ByteArray,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int
    ): Int {
        var sum = foldBytes(0, packet, SOURCE_OFFSET, IPV6_ADDRESS_BYTES)
        sum = foldByte(sum, transportLength ushr 24, true)
        sum = foldByte(sum, transportLength ushr 16, false)
        sum = foldByte(sum, transportLength ushr 8, true)
        sum = foldByte(sum, transportLength, false)
        sum = foldByte(sum, 0, true)
        sum = foldByte(sum, 0, false)
        sum = foldByte(sum, 0, true)
        sum = foldByte(sum, protocol, false)
        sum = foldBytes(sum, packet, transportOffset, transportLength)
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum
    }

    private fun foldBytes(
        initial: Int,
        data: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = initial
        repeat(length) { index ->
            sum = foldByte(sum, data[offset + index].toInt(), index and 1 == 0)
        }
        return sum
    }

    private fun foldByte(sum: Int, value: Int, high: Boolean): Int {
        val next = sum + ((value and 0xff) shl if (high) 8 else 0)
        return (next and 0xffff) + (next ushr 16)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private companion object {
        const val SEED = 0x1071A57A
        const val IPV6_HEADER_LENGTH = 40
        const val SOURCE_OFFSET = 8
        const val IPV6_ADDRESS_BYTES = 32
        const val UDP_HEADER_LENGTH = 8
        const val UDP_CHECKSUM_OFFSET = 6
        const val UDP = 17
        const val MAX_PACKET_LENGTH = IPV6_HEADER_LENGTH + 65_535
    }
}
