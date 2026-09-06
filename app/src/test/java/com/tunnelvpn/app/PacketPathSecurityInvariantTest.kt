package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketPathSecurityInvariantTest {
    @Test
    fun onlyIpv4TcpDnsMatchesThePlaintextTcpBlocker() {
        val tcpDns = ipv4Packet(protocol = 6, destinationPort = 53, length = 40)

        assertTrue(LocalProtectionEngine.isIpv4TcpDnsPacket(tcpDns, tcpDns.size))
        assertFalse(
            LocalProtectionEngine.isIpv4TcpDnsPacket(
                ipv4Packet(protocol = 17, destinationPort = 53, length = 28),
                28
            )
        )
        assertFalse(
            LocalProtectionEngine.isIpv4TcpDnsPacket(
                ipv4Packet(protocol = 6, destinationPort = 443, length = 40),
                40
            )
        )
        assertFalse(LocalProtectionEngine.isIpv4TcpDnsPacket(tcpDns, tcpDns.size - 1))
    }

    @Test
    fun ipv6TcpAndUdpDnsBothMatchTheFailClosedGate() {
        val tcpDns = ipv6Packet(protocol = 6, destinationPort = 53, length = 60)
        val udpDns = ipv6Packet(protocol = 17, destinationPort = 53, length = 48)

        assertTrue(LocalProtectionEngine.isIpv6DnsTransportPacket(tcpDns, tcpDns.size))
        assertTrue(LocalProtectionEngine.isIpv6DnsTransportPacket(udpDns, udpDns.size))
        assertFalse(
            LocalProtectionEngine.isIpv6DnsTransportPacket(
                ipv6Packet(protocol = 17, destinationPort = 443, length = 48),
                48
            )
        )
        assertFalse(LocalProtectionEngine.isIpv6DnsTransportPacket(udpDns, udpDns.size - 1))
    }

    @Test
    fun ipv4DedicatedEncryptedDnsTransportsFailClosedBeforeAnyForwarder() {
        assertFalse(shouldDropIpv4(protocol = 6, destinationPort = 53))
        assertTrue(shouldDropIpv4(protocol = 6, destinationPort = 853))
        assertTrue(shouldDropIpv4(protocol = 17, destinationPort = 784))
        assertTrue(shouldDropIpv4(protocol = 17, destinationPort = 853))
        assertTrue(shouldDropIpv4(protocol = 17, destinationPort = 8853))
        assertFalse(shouldDropIpv4(protocol = 17, destinationPort = 53))
        assertFalse(shouldDropIpv4(protocol = 6, destinationPort = 443))
        assertFalse(shouldDropIpv4(protocol = 17, destinationPort = 443))
    }

    @Test
    fun ipv6DnsIsInterceptedWhileDedicatedEncryptedDnsTransportsFailClosed() {
        assertFalse(shouldDropIpv6(protocol = 6, destinationPort = 53))
        assertTrue(shouldDropIpv6(protocol = 6, destinationPort = 853))
        assertFalse(shouldDropIpv6(protocol = 17, destinationPort = 53))
        assertTrue(shouldDropIpv6(protocol = 17, destinationPort = 784))
        assertTrue(shouldDropIpv6(protocol = 17, destinationPort = 853))
        assertTrue(shouldDropIpv6(protocol = 17, destinationPort = 8853))
        assertFalse(shouldDropIpv6(protocol = 6, destinationPort = 443))
        assertFalse(shouldDropIpv6(protocol = 17, destinationPort = 443))
        assertFalse(shouldDropIpv6(protocol = 17, destinationPort = 5353))
    }

    @Test
    fun ipv4FragmentsMalformedHeadersAndOversizeLengthsFailClosed() {
        val validTcp = ipv4Packet(protocol = 6, destinationPort = 443, length = 40)
        val validUdpDns = ipv4Packet(protocol = 17, destinationPort = 53, length = 28)
        val maximumIhl = ByteArray(80).also {
            it[0] = 0x4f
            it[9] = 6
            put16(it, 2, it.size)
            put16(it, 60, 53000)
            put16(it, 62, 443)
            it[72] = 0x50
        }
        val maximumIhlEncryptedDns = maximumIhl.copyOf().also { put16(it, 62, 853) }
        val invalidIhl = validTcp.copyOf().also { it[0] = 0x44 }
        val oversizedIhl = validTcp.copyOf().also { it[0] = 0x4f }
        val reservedFragmentFlag = validTcp.copyOf().also { put16(it, 6, 0x8000) }
        val moreFragments = validTcp.copyOf().also { put16(it, 6, 0x2000) }
        val nonInitialFragment = validTcp.copyOf().also { put16(it, 6, 1) }
        val oversizedTotalLength = validTcp.copyOf().also { put16(it, 2, it.size + 1) }

        assertFalse(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(validTcp, validTcp.size))
        assertFalse(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(validUdpDns, validUdpDns.size))
        assertFalse(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(maximumIhl, maximumIhl.size))
        assertTrue(
            LocalProtectionEngine.shouldDropIpv4BeforeForwarding(
                maximumIhlEncryptedDns,
                maximumIhlEncryptedDns.size
            )
        )
        assertTrue(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(invalidIhl, invalidIhl.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(oversizedIhl, oversizedIhl.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(reservedFragmentFlag, reservedFragmentFlag.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(moreFragments, moreFragments.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(nonInitialFragment, nonInitialFragment.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv4BeforeForwarding(oversizedTotalLength, oversizedTotalLength.size))
    }

    @Test
    fun ipv6ExtensionChainsAreBoundedAndNeverEnterTheDirectUdpRelay() {
        val maximumChain = ipv6DestinationOptionsChain(8, destinationPort = 443)
        val overlongChain = ipv6DestinationOptionsChain(9, destinationPort = 443)
        val parsedMaximum = requireNotNull(
            LocalProtectionEngine.parseIpv6TransportForSecurity(maximumChain, maximumChain.size)
        )

        assertEquals(8, parsedMaximum.extensionCount)
        assertTrue(LocalProtectionEngine.shouldDropIpv6BeforeForwarding(maximumChain, maximumChain.size))
        assertNull(LocalProtectionEngine.parseIpv6TransportForSecurity(overlongChain, overlongChain.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv6BeforeForwarding(overlongChain, overlongChain.size))
    }

    @Test
    fun ipv6FragmentsTruncatedChainsAndOversizeExtensionsFailClosed() {
        val fragment = ByteArray(56).also {
            it[0] = 0x60
            it[6] = 44
            put16(it, 4, 16)
            it[40] = 17
            put16(it, 50, 443)
        }
        val oversizedExtension = ByteArray(48).also {
            it[0] = 0x60
            it[6] = 60
            put16(it, 4, 8)
            it[40] = 17
            it[41] = 0xff.toByte()
        }
        val truncatedPayload = ipv6Packet(protocol = 17, destinationPort = 443, length = 48).also {
            put16(it, 4, 16)
        }
        val maximumExtensionBytes = ipv6SingleDestinationOption(256, destinationPort = 443)
        val overMaximumExtensionBytes = ipv6SingleDestinationOption(264, destinationPort = 443)

        assertNull(LocalProtectionEngine.parseIpv6TransportForSecurity(fragment, fragment.size))
        assertNull(LocalProtectionEngine.parseIpv6TransportForSecurity(oversizedExtension, oversizedExtension.size))
        assertNull(LocalProtectionEngine.parseIpv6TransportForSecurity(truncatedPayload, truncatedPayload.size))
        requireNotNull(
            LocalProtectionEngine.parseIpv6TransportForSecurity(
                maximumExtensionBytes,
                maximumExtensionBytes.size
            )
        )
        assertNull(
            LocalProtectionEngine.parseIpv6TransportForSecurity(
                overMaximumExtensionBytes,
                overMaximumExtensionBytes.size
            )
        )
        assertTrue(LocalProtectionEngine.shouldDropIpv6BeforeForwarding(fragment, fragment.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv6BeforeForwarding(oversizedExtension, oversizedExtension.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv6BeforeForwarding(truncatedPayload, truncatedPayload.size))
        assertTrue(
            LocalProtectionEngine.shouldDropIpv6BeforeForwarding(
                maximumExtensionBytes,
                maximumExtensionBytes.size
            )
        )
        assertTrue(
            LocalProtectionEngine.shouldDropIpv6BeforeForwarding(
                overMaximumExtensionBytes,
                overMaximumExtensionBytes.size
            )
        )
    }

    @Test
    fun ipv6TransportChecksumsFailClosedAtTheSecurityGate() {
        val udp = ipv6Packet(protocol = 17, destinationPort = 443, length = 49)
        val tcp = ipv6Packet(protocol = 6, destinationPort = 443, length = 61)
        val zeroUdp = udp.copyOf().also { put16(it, 46, 0) }
        val corruptUdp = udp.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val corruptTcp = tcp.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertTrue(LocalProtectionEngine.shouldDropIpv6BeforeForwarding(zeroUdp, zeroUdp.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv6BeforeForwarding(corruptUdp, corruptUdp.size))
        assertTrue(LocalProtectionEngine.shouldDropIpv6BeforeForwarding(corruptTcp, corruptTcp.size))
        assertNull(LocalProtectionEngine.parseIpv6TransportForSecurity(zeroUdp, zeroUdp.size))
        assertNull(LocalProtectionEngine.parseIpv6TransportForSecurity(corruptUdp, corruptUdp.size))
        assertNull(LocalProtectionEngine.parseIpv6TransportForSecurity(corruptTcp, corruptTcp.size))
    }

    private fun ipv4Packet(protocol: Int, destinationPort: Int, length: Int): ByteArray {
        val packet = ByteArray(length)
        packet[0] = 0x45
        packet[9] = protocol.toByte()
        put16(packet, 2, length)
        put16(packet, 20, 53000)
        put16(packet, 22, destinationPort)
        if (protocol == 6) packet[32] = 0x50
        if (protocol == 17) put16(packet, 24, length - 20)
        return packet
    }

    private fun shouldDropIpv4(protocol: Int, destinationPort: Int): Boolean {
        val length = if (protocol == 6) 40 else 28
        val packet = ipv4Packet(protocol, destinationPort, length)
        return LocalProtectionEngine.shouldDropIpv4BeforeForwarding(packet, packet.size)
    }

    private fun shouldDropIpv6(protocol: Int, destinationPort: Int): Boolean {
        val length = if (protocol == 6) 60 else 48
        val packet = ipv6Packet(protocol, destinationPort, length)
        return LocalProtectionEngine.shouldDropIpv6BeforeForwarding(packet, packet.size)
    }

    private fun ipv6Packet(protocol: Int, destinationPort: Int, length: Int): ByteArray {
        val packet = ByteArray(length)
        packet[0] = 0x60
        packet[6] = protocol.toByte()
        put16(packet, 4, length - 40)
        setIpv6Addresses(packet)
        put16(packet, 40, 53000)
        put16(packet, 42, destinationPort)
        if (protocol == 6) packet[52] = 0x50
        if (protocol == 17) put16(packet, 44, length - 40)
        setIpv6TransportChecksum(packet, 40, protocol)
        return packet
    }

    private fun ipv6DestinationOptionsChain(count: Int, destinationPort: Int): ByteArray {
        val udpOffset = 40 + count * 8
        val packet = ByteArray(udpOffset + 8)
        packet[0] = 0x60
        packet[6] = if (count == 0) 17 else 60
        put16(packet, 4, packet.size - 40)
        setIpv6Addresses(packet)
        repeat(count) { index ->
            val offset = 40 + index * 8
            packet[offset] = if (index == count - 1) 17 else 60
            packet[offset + 1] = 0
        }
        put16(packet, udpOffset, 53000)
        put16(packet, udpOffset + 2, destinationPort)
        put16(packet, udpOffset + 4, 8)
        setIpv6TransportChecksum(packet, udpOffset, 17)
        return packet
    }

    private fun ipv6SingleDestinationOption(headerLength: Int, destinationPort: Int): ByteArray {
        require(headerLength >= 8 && headerLength % 8 == 0)
        val udpOffset = 40 + headerLength
        val packet = ByteArray(udpOffset + 8)
        packet[0] = 0x60
        packet[6] = 60
        put16(packet, 4, packet.size - 40)
        setIpv6Addresses(packet)
        packet[40] = 17
        packet[41] = (headerLength / 8 - 1).toByte()
        put16(packet, udpOffset, 53000)
        put16(packet, udpOffset + 2, destinationPort)
        put16(packet, udpOffset + 4, 8)
        setIpv6TransportChecksum(packet, udpOffset, 17)
        return packet
    }

    private fun setIpv6TransportChecksum(packet: ByteArray, offset: Int, protocol: Int) {
        val checksumOffset = if (protocol == 6) offset + 16 else offset + 6
        put16(packet, checksumOffset, 0)
        val value = Ipv6TransportChecksum.value(packet, packet.size, offset, packet.size - offset, protocol)
        put16(packet, checksumOffset, if (protocol == 17 && value == 0) 0xffff else value)
    }

    private fun setIpv6Addresses(packet: ByteArray) {
        packet[8] = 0x20
        packet[9] = 0x01
        packet[23] = 2
        packet[24] = 0x20
        packet[25] = 0x01
        packet[39] = 1
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }
}
