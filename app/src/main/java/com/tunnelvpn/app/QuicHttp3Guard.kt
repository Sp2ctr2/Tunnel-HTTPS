package com.tunnelvpn.app

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AdaptiveUdp443Policy(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val tcpFallbackTtlMs: Long = 60_000L,
    private val probeLeaseMs: Long = 2_000L,
    private val reachableTtlMs: Long = 10 * 60_000L,
    private val maxDestinations: Int = 1_024
) {
    private enum class Status { PROBING, REACHABLE, BLOCKED }

    private class AddressKey(address: ByteArray) {
        val bytes = address.copyOf()
        private val hash = bytes.contentHashCode()

        override fun equals(other: Any?): Boolean =
            other is AddressKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = hash
    }

    private data class State(var status: Status, var untilMs: Long, var failedProbes: Int)

    private val states = object : LinkedHashMap<AddressKey, State>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AddressKey, State>?): Boolean {
            return size > maxDestinations
        }
    }

    init {
        require(tcpFallbackTtlMs >= 0L)
        require(probeLeaseMs > 0L)
        require(reachableTtlMs >= 0L)
        require(maxDestinations > 0)
    }

    @Synchronized
    fun requiresTcpPath(address: ByteArray): Boolean {
        val key = addressKey(address) ?: return true
        val now = clock()
        val state = states[key] ?: State(Status.PROBING, now + probeLeaseMs, 0).also {
            states[key] = it
        }
        return when (state.status) {
            Status.PROBING -> {
                if (state.untilMs > now) {
                    false
                } else {
                    state.untilMs = now + probeLeaseMs
                    false
                }
            }
            Status.REACHABLE -> {
                if (state.untilMs > now) {
                    false
                } else {
                    state.status = Status.PROBING
                    state.untilMs = now + probeLeaseMs
                    state.failedProbes = 0
                    false
                }
            }
            Status.BLOCKED -> {
                if (state.untilMs > now) {
                    true
                } else {
                    state.status = Status.PROBING
                    state.untilMs = now + probeLeaseMs
                    state.failedProbes = 0
                    false
                }
            }
        }
    }

    @Synchronized
    fun recordReachable(address: ByteArray) {
        addressKey(address)?.let { states[it] = State(Status.REACHABLE, clock() + reachableTtlMs, 0) }
    }

    @Synchronized
    fun recordUnreachable(address: ByteArray) {
        val key = addressKey(address) ?: return
        val failedProbes = (states[key]?.failedProbes ?: 0) + 1
        states[key] = if (failedProbes >= REQUIRED_FAILED_PROBES) {
            State(Status.BLOCKED, clock() + tcpFallbackTtlMs, failedProbes)
        } else {
            State(Status.PROBING, clock() + probeLeaseMs, failedProbes)
        }
    }

    @Synchronized
    fun reset() {
        states.clear()
    }

    private fun addressKey(address: ByteArray): AddressKey? {
        if (address.size != IPV4_ADDRESS_LENGTH && address.size != IPV6_ADDRESS_LENGTH) return null
        return AddressKey(address)
    }

    companion object {
        private const val IPV4_ADDRESS_LENGTH = 4
        private const val IPV6_ADDRESS_LENGTH = 16
        private const val REQUIRED_FAILED_PROBES = 2
    }
}

object QuicInitialClassifier {
    data class Probe(
        val version: Int,
        val destinationConnectionId: ByteArray,
        val sourceConnectionId: ByteArray,
        val knownInitial: Boolean,
        val token: ByteArray
    )

    fun isLikely(payload: ByteArray): Boolean = isLikely(payload, 0, payload.size)

    fun isLikely(data: ByteArray, offset: Int, length: Int): Boolean {
        return inspectProbe(data, offset, length)?.knownInitial == true
    }

    fun inspectProbe(payload: ByteArray): Probe? = inspectProbe(payload, 0, payload.size)

    fun inspectProbe(data: ByteArray, offset: Int, length: Int): Probe? {
        if (offset < 0 || length < MINIMUM_LONG_HEADER || offset > data.size - length) return null
        val end = offset + length
        val first = data[offset].toInt() and 0xff
        if (first and HEADER_FORM_BIT == 0) return null
        val version = u32(data, offset + 1)
        if (version == 0) return null
        val packetType = (first ushr 4) and 0x03
        val knownInitial = when (version) {
            QUIC_V1 -> packetType == V1_INITIAL_TYPE
            QUIC_V2 -> packetType == V2_INITIAL_TYPE
            else -> false
        }
        if ((version == QUIC_V1 || version == QUIC_V2) && !knownInitial) return null
        if (knownInitial && first and FIXED_BIT == 0) return null
        if (knownInitial && length < MINIMUM_INITIAL_DATAGRAM) return null
        var cursor = offset + VERSIONED_HEADER_PREFIX
        if (cursor >= end) return null
        val destinationConnectionIdLength = data[cursor].toInt() and 0xff
        cursor += 1
        val maximumConnectionIdLength = if (knownInitial) MAX_CONNECTION_ID_LENGTH else MAX_INVARIANT_CONNECTION_ID_LENGTH
        if (destinationConnectionIdLength > maximumConnectionIdLength ||
            cursor > end - destinationConnectionIdLength
        ) return null
        val destinationConnectionId = data.copyOfRange(cursor, cursor + destinationConnectionIdLength)
        cursor += destinationConnectionIdLength
        if (cursor >= end) return null
        val sourceConnectionIdLength = data[cursor].toInt() and 0xff
        cursor += 1
        if (sourceConnectionIdLength > maximumConnectionIdLength || cursor > end - sourceConnectionIdLength) {
            return null
        }
        val sourceConnectionId = data.copyOfRange(cursor, cursor + sourceConnectionIdLength)
        cursor += sourceConnectionIdLength
        if (!knownInitial) {
            return Probe(version, destinationConnectionId, sourceConnectionId, false, ByteArray(0))
        }
        val tokenLength = readVarInt(data, cursor, end) ?: return null
        cursor = tokenLength.second
        if (tokenLength.first > (end - cursor).toLong()) return null
        val tokenEnd = cursor + tokenLength.first.toInt()
        val token = data.copyOfRange(cursor, tokenEnd)
        cursor = tokenEnd
        if (destinationConnectionIdLength < MINIMUM_CLIENT_DCID_LENGTH && token.isEmpty()) return null
        val encodedLength = readVarInt(data, cursor, end) ?: return null
        cursor = encodedLength.second
        if (encodedLength.first < MINIMUM_PROTECTED_PAYLOAD ||
            encodedLength.first > (end - cursor).toLong()
        ) return null
        return Probe(version, destinationConnectionId, sourceConnectionId, true, token)
    }

    private fun readVarInt(data: ByteArray, offset: Int, end: Int): Pair<Long, Int>? {
        if (offset >= end) return null
        val first = data[offset].toInt() and 0xff
        val byteCount = 1 shl (first ushr 6)
        if (offset > end - byteCount) return null
        var value = (first and 0x3f).toLong()
        for (index in 1 until byteCount) {
            value = (value shl 8) or (data[offset + index].toInt() and 0xff).toLong()
        }
        return value to offset + byteCount
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    private const val MINIMUM_INITIAL_DATAGRAM = 1_200
    private const val MINIMUM_LONG_HEADER = 7
    private const val MINIMUM_PROTECTED_PAYLOAD = 17L
    private const val MINIMUM_CLIENT_DCID_LENGTH = 8
    private const val HEADER_FORM_BIT = 0x80
    private const val FIXED_BIT = 0x40
    private const val VERSIONED_HEADER_PREFIX = 5
    private const val MAX_CONNECTION_ID_LENGTH = 20
    private const val MAX_INVARIANT_CONNECTION_ID_LENGTH = 255
    private const val QUIC_V1 = 0x00000001
    private const val QUIC_V2 = 0x6b3343cf
    private const val V1_INITIAL_TYPE = 0
    private const val V2_INITIAL_TYPE = 1
}

object QuicServerResponseClassifier {
    enum class ResponseType { INITIAL, RETRY, VERSION_NEGOTIATION, FUTURE_VERSION }

    fun classify(payload: ByteArray, probe: QuicInitialClassifier.Probe): ResponseType? {
        return classify(payload, 0, payload.size, probe)
    }

    fun isPlausible(payload: ByteArray, probe: QuicInitialClassifier.Probe): Boolean {
        return classify(payload, probe) != null
    }

    fun isPlausible(
        data: ByteArray,
        offset: Int,
        length: Int,
        probe: QuicInitialClassifier.Probe
    ): Boolean {
        return classify(data, offset, length, probe) != null
    }

    fun classify(
        data: ByteArray,
        offset: Int,
        length: Int,
        probe: QuicInitialClassifier.Probe
    ): ResponseType? {
        if (offset < 0 || length < MINIMUM_LONG_HEADER || offset > data.size - length) return null
        val end = offset + length
        val first = data[offset].toInt() and 0xff
        if (first and HEADER_FORM_BIT == 0) return null
        val version = u32(data, offset + 1)
        var cursor = offset + VERSIONED_HEADER_PREFIX
        val maximumConnectionIdLength = if (version == QUIC_V1 || version == QUIC_V2) {
            MAX_CONNECTION_ID_LENGTH
        } else {
            MAX_INVARIANT_CONNECTION_ID_LENGTH
        }
        val destination = readConnectionId(data, cursor, end, maximumConnectionIdLength) ?: return null
        cursor = destination.second
        if (!destination.first.contentEquals(probe.sourceConnectionId)) return null
        val source = readConnectionId(data, cursor, end, maximumConnectionIdLength) ?: return null
        cursor = source.second

        if (version == VERSION_NEGOTIATION) {
            if (!source.first.contentEquals(probe.destinationConnectionId)) return null
            val versionBytes = end - cursor
            if (versionBytes < VERSION_FIELD_LENGTH || versionBytes % VERSION_FIELD_LENGTH != 0) return null
            var offeredCursor = cursor
            while (offeredCursor < end) {
                val offered = u32(data, offeredCursor)
                if (offered == VERSION_NEGOTIATION || offered == probe.version) return null
                offeredCursor += VERSION_FIELD_LENGTH
            }
            return ResponseType.VERSION_NEGOTIATION
        }
        if (version != probe.version) return null

        val packetType = (first ushr 4) and 0x03
        val initialType = when (version) {
            QUIC_V1 -> V1_INITIAL_TYPE
            QUIC_V2 -> V2_INITIAL_TYPE
            else -> return ResponseType.FUTURE_VERSION.takeIf { !probe.knownInitial }
        }
        if (first and FIXED_BIT == 0) return null
        val retryType = if (version == QUIC_V1) V1_RETRY_TYPE else V2_RETRY_TYPE
        return when (packetType) {
            initialType -> {
                val tokenLength = readVarInt(data, cursor, end) ?: return null
                if (tokenLength.first != 0L) return null
                cursor = tokenLength.second
                val encodedLength = readVarInt(data, cursor, end) ?: return null
                cursor = encodedLength.second
                ResponseType.INITIAL.takeIf {
                    encodedLength.first >= MINIMUM_PROTECTED_PAYLOAD &&
                        encodedLength.first <= (end - cursor).toLong()
                }
            }
            retryType -> {
                ResponseType.RETRY.takeIf {
                    probe.token.isEmpty() &&
                    !source.first.contentEquals(probe.destinationConnectionId) &&
                        end - cursor > RETRY_INTEGRITY_TAG_LENGTH &&
                        hasValidRetryIntegrityTag(data, offset, end, version, probe.destinationConnectionId)
                }
            }
            else -> null
        }
    }

    private fun readConnectionId(
        data: ByteArray,
        offset: Int,
        end: Int,
        maximumLength: Int
    ): Pair<ByteArray, Int>? {
        if (offset >= end) return null
        val length = data[offset].toInt() and 0xff
        val start = offset + 1
        if (length > maximumLength || start > end - length) return null
        return data.copyOfRange(start, start + length) to start + length
    }

    private fun readVarInt(data: ByteArray, offset: Int, end: Int): Pair<Long, Int>? {
        if (offset >= end) return null
        val first = data[offset].toInt() and 0xff
        val byteCount = 1 shl (first ushr 6)
        if (offset > end - byteCount) return null
        var value = (first and 0x3f).toLong()
        for (index in 1 until byteCount) {
            value = (value shl 8) or (data[offset + index].toInt() and 0xff).toLong()
        }
        return value to offset + byteCount
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    private fun hasValidRetryIntegrityTag(
        data: ByteArray,
        offset: Int,
        end: Int,
        version: Int,
        originalDestinationConnectionId: ByteArray
    ): Boolean {
        val retryEnd = end - RETRY_INTEGRITY_TAG_LENGTH
        if (retryEnd <= offset) return false
        val expected = retryIntegrityTag(
            version,
            originalDestinationConnectionId,
            data.copyOfRange(offset, retryEnd)
        ) ?: return false
        return MessageDigest.isEqual(expected, data.copyOfRange(retryEnd, end))
    }

    internal fun retryIntegrityTag(
        version: Int,
        originalDestinationConnectionId: ByteArray,
        retryWithoutIntegrityTag: ByteArray
    ): ByteArray? {
        if (originalDestinationConnectionId.size > 255) return null
        val key: ByteArray
        val nonce: ByteArray
        when (version) {
            QUIC_V1 -> {
                key = RETRY_KEY_V1
                nonce = RETRY_NONCE_V1
            }
            QUIC_V2 -> {
                key = RETRY_KEY_V2
                nonce = RETRY_NONCE_V2
            }
            else -> return null
        }
        val pseudoPacket = ByteArray(1 + originalDestinationConnectionId.size + retryWithoutIntegrityTag.size)
        pseudoPacket[0] = originalDestinationConnectionId.size.toByte()
        originalDestinationConnectionId.copyInto(pseudoPacket, 1)
        retryWithoutIntegrityTag.copyInto(pseudoPacket, 1 + originalDestinationConnectionId.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(RETRY_INTEGRITY_TAG_LENGTH * 8, nonce)
            )
            cipher.updateAAD(pseudoPacket)
            cipher.doFinal()
        }.getOrNull()?.takeIf { it.size == RETRY_INTEGRITY_TAG_LENGTH }
    }

    private const val MINIMUM_LONG_HEADER = 7
    private const val VERSIONED_HEADER_PREFIX = 5
    private const val VERSION_FIELD_LENGTH = 4
    private const val MINIMUM_PROTECTED_PAYLOAD = 17L
    private const val RETRY_INTEGRITY_TAG_LENGTH = 16
    private const val MAX_CONNECTION_ID_LENGTH = 20
    private const val MAX_INVARIANT_CONNECTION_ID_LENGTH = 255
    private const val HEADER_FORM_BIT = 0x80
    private const val FIXED_BIT = 0x40
    private const val VERSION_NEGOTIATION = 0
    private const val QUIC_V1 = 0x00000001
    private const val QUIC_V2 = 0x6b3343cf
    private const val V1_INITIAL_TYPE = 0
    private const val V1_RETRY_TYPE = 3
    private const val V2_INITIAL_TYPE = 1
    private const val V2_RETRY_TYPE = 0
    private val RETRY_KEY_V1 = byteArrayOf(
        0xbe.toByte(), 0x0c, 0x69, 0x0b, 0x9f.toByte(), 0x66, 0x57, 0x5a,
        0x1d, 0x76, 0x6b, 0x54, 0xe3.toByte(), 0x68, 0xc8.toByte(), 0x4e
    )
    private val RETRY_NONCE_V1 = byteArrayOf(
        0x46, 0x15, 0x99.toByte(), 0xd3.toByte(), 0x5d, 0x63, 0x2b, 0xf2.toByte(),
        0x23, 0x98.toByte(), 0x25, 0xbb.toByte()
    )
    private val RETRY_KEY_V2 = byteArrayOf(
        0x8f.toByte(), 0xb4.toByte(), 0xb0.toByte(), 0x1b, 0x56, 0xac.toByte(), 0x48, 0xe2.toByte(),
        0x60, 0xfb.toByte(), 0xcb.toByte(), 0xce.toByte(), 0xad.toByte(), 0x7c, 0xcc.toByte(), 0x92.toByte()
    )
    private val RETRY_NONCE_V2 = byteArrayOf(
        0xd8.toByte(), 0x69, 0x69, 0xbc.toByte(), 0x2d, 0x7c, 0x6d, 0x99.toByte(),
        0x90.toByte(), 0xef.toByte(), 0xb0.toByte(), 0x4a
    )
}

class Udp443BlockPolicy(
    private val enabled: Boolean,
    private val requiresTcpPath: (ByteArray) -> Boolean = { true }
) {
    fun shouldDropIpv4Packet(packet: ByteArray, length: Int): Boolean {
        if (!enabled || length !in MIN_IPV4_UDP_LENGTH..packet.size) return false
        val version = (packet[0].toInt() ushr 4) and 0x0f
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (version != IPV4_VERSION || ihl < IPV4_MIN_HEADER_LENGTH || length < ihl + UDP_HEADER_LENGTH) return false
        if ((packet[9].toInt() and 0xff) != UDP_PROTOCOL) return false
        if (u16(packet, IPV4_TOTAL_LENGTH_OFFSET) != length) return false
        if (u16(packet, IPV4_FRAGMENT_OFFSET) and IPV4_FRAGMENT_MASK != 0) return false
        val destinationPort = u16(packet, ihl + 2)
        if (destinationPort != HTTPS_PORT) return false
        val udpLength = u16(packet, ihl + UDP_LENGTH_OFFSET)
        if (udpLength != length - ihl || udpLength < UDP_HEADER_LENGTH) return false
        if (!QuicInitialClassifier.isLikely(packet, ihl + UDP_HEADER_LENGTH, udpLength - UDP_HEADER_LENGTH)) return false
        return requiresTcpPath(packet.copyOfRange(IPV4_DESTINATION_OFFSET, IPV4_DESTINATION_OFFSET + IPV4_ADDRESS_LENGTH))
    }

    fun shouldDropIpv6Packet(packet: ByteArray, length: Int): Boolean {
        if (!enabled || length !in MIN_IPV6_UDP_LENGTH..packet.size) return false
        val version = (packet[0].toInt() ushr 4) and 0x0f
        if (version != IPV6_VERSION) return false
        if ((packet[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != UDP_PROTOCOL) return false
        if (u16(packet, IPV6_PAYLOAD_LENGTH_OFFSET) != length - IPV6_HEADER_LENGTH) return false
        if (!Ipv6FullForwardRoutePolicy.isProxyableUnicast(packet, IPV6_SOURCE_OFFSET) ||
            !Ipv6FullForwardRoutePolicy.isProxyableUnicast(packet, IPV6_DESTINATION_OFFSET)
        ) return false
        val destinationPort = u16(packet, IPV6_HEADER_LENGTH + 2)
        if (destinationPort != HTTPS_PORT) return false
        val udpLength = u16(packet, IPV6_HEADER_LENGTH + UDP_LENGTH_OFFSET)
        if (udpLength != length - IPV6_HEADER_LENGTH || udpLength < UDP_HEADER_LENGTH) return false
        if (!Ipv6TransportChecksum.isValid(
                packet = packet,
                packetLength = length,
                transportOffset = IPV6_HEADER_LENGTH,
                transportLength = udpLength,
                protocol = UDP_PROTOCOL,
                rejectZeroChecksumAt = UDP_CHECKSUM_OFFSET
            )
        ) return false
        if (!QuicInitialClassifier.isLikely(
                packet,
                IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH,
                udpLength - UDP_HEADER_LENGTH
            )
        ) return false
        return requiresTcpPath(packet.copyOfRange(IPV6_DESTINATION_OFFSET, IPV6_DESTINATION_OFFSET + IPV6_ADDRESS_LENGTH))
    }

    companion object {
        private const val IPV4_VERSION = 4
        private const val IPV4_TOTAL_LENGTH_OFFSET = 2
        private const val IPV4_FRAGMENT_OFFSET = 6
        private const val IPV4_FRAGMENT_MASK = 0x3fff
        private const val IPV4_DESTINATION_OFFSET = 16
        private const val IPV4_ADDRESS_LENGTH = 4
        private const val IPV4_MIN_HEADER_LENGTH = 20
        private const val UDP_HEADER_LENGTH = 8
        private const val MIN_IPV4_UDP_LENGTH = IPV4_MIN_HEADER_LENGTH + UDP_HEADER_LENGTH
        private const val IPV6_VERSION = 6
        private const val IPV6_SOURCE_OFFSET = 8
        private const val IPV6_DESTINATION_OFFSET = 24
        private const val IPV6_ADDRESS_LENGTH = 16
        private const val IPV6_HEADER_LENGTH = 40
        private const val IPV6_NEXT_HEADER_OFFSET = 6
        private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
        private const val MIN_IPV6_UDP_LENGTH = IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH
        private const val UDP_LENGTH_OFFSET = 4
        private const val UDP_CHECKSUM_OFFSET = 6
        private const val UDP_PROTOCOL = 17
        private const val HTTPS_PORT = 443

        private fun u16(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        }
    }
}

object Ipv4IcmpPortUnreachable {
    fun build(original: ByteArray, length: Int): ByteArray? {
        if (length !in MIN_ORIGINAL_LENGTH..original.size) return null
        val version = (original[0].toInt() ushr 4) and 0x0f
        val originalHeaderLength = (original[0].toInt() and 0x0f) * 4
        if (version != IPV4_VERSION || originalHeaderLength < IPV4_HEADER_LENGTH) return null
        if (length < originalHeaderLength + UDP_HEADER_LENGTH) return null
        if ((original[PROTOCOL_OFFSET].toInt() and 0xff) != UDP_PROTOCOL) return null
        if (u16(original, TOTAL_LENGTH_OFFSET) != length) return null
        if (u16(original, FRAGMENT_OFFSET) and FRAGMENT_MASK != 0) return null

        val quotedLength = originalHeaderLength + UDP_HEADER_LENGTH
        val result = ByteArray(IPV4_HEADER_LENGTH + ICMP_HEADER_LENGTH + quotedLength)
        result[0] = 0x45
        put16(result, TOTAL_LENGTH_OFFSET, result.size)
        result[TTL_OFFSET] = DEFAULT_TTL.toByte()
        result[PROTOCOL_OFFSET] = ICMP_PROTOCOL.toByte()
        original.copyInto(result, IPV4_SOURCE_OFFSET, IPV4_DESTINATION_OFFSET, IPV4_DESTINATION_OFFSET + 4)
        original.copyInto(result, IPV4_DESTINATION_OFFSET, IPV4_SOURCE_OFFSET, IPV4_SOURCE_OFFSET + 4)
        put16(result, IPV4_CHECKSUM_OFFSET, checksum(result, 0, IPV4_HEADER_LENGTH))

        val icmpOffset = IPV4_HEADER_LENGTH
        result[icmpOffset] = DESTINATION_UNREACHABLE.toByte()
        result[icmpOffset + 1] = PORT_UNREACHABLE.toByte()
        original.copyInto(result, icmpOffset + ICMP_HEADER_LENGTH, 0, quotedLength)
        put16(
            result,
            icmpOffset + ICMP_CHECKSUM_OFFSET,
            checksum(result, icmpOffset, result.size - icmpOffset)
        )
        return result
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += u16(data, index).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private const val IPV4_VERSION = 4
    private const val IPV4_HEADER_LENGTH = 20
    private const val UDP_HEADER_LENGTH = 8
    private const val ICMP_HEADER_LENGTH = 8
    private const val MIN_ORIGINAL_LENGTH = IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH
    private const val TOTAL_LENGTH_OFFSET = 2
    private const val FRAGMENT_OFFSET = 6
    private const val TTL_OFFSET = 8
    private const val PROTOCOL_OFFSET = 9
    private const val IPV4_CHECKSUM_OFFSET = 10
    private const val IPV4_SOURCE_OFFSET = 12
    private const val IPV4_DESTINATION_OFFSET = 16
    private const val ICMP_CHECKSUM_OFFSET = 2
    private const val FRAGMENT_MASK = 0x3fff
    private const val DEFAULT_TTL = 64
    private const val ICMP_PROTOCOL = 1
    private const val UDP_PROTOCOL = 17
    private const val DESTINATION_UNREACHABLE = 3
    private const val PORT_UNREACHABLE = 3
}

object Ipv6IcmpPortUnreachable {
    fun build(
        validatedPacket: ByteArray,
        length: Int,
        invokingPacket: ByteArray = validatedPacket
    ): ByteArray? {
        if (length !in MIN_ORIGINAL_LENGTH..validatedPacket.size) return null
        if (((validatedPacket[0].toInt() ushr 4) and 0x0f) != IPV6_VERSION) return null
        if ((validatedPacket[NEXT_HEADER_OFFSET].toInt() and 0xff) != UDP_PROTOCOL) return null
        if (IPV6_HEADER_LENGTH + u16(validatedPacket, PAYLOAD_LENGTH_OFFSET) != length) return null
        val udpLength = u16(validatedPacket, IPV6_HEADER_LENGTH + UDP_LENGTH_OFFSET)
        if (udpLength != length - IPV6_HEADER_LENGTH || udpLength < UDP_HEADER_LENGTH) return null
        if (!Ipv6TransportChecksum.isValid(
                packet = validatedPacket,
                packetLength = length,
                transportOffset = IPV6_HEADER_LENGTH,
                transportLength = udpLength,
                protocol = UDP_PROTOCOL,
                rejectZeroChecksumAt = UDP_CHECKSUM_OFFSET
            )
        ) return null
        if (!Ipv6IcmpErrorPolicy.permits(validatedPacket, length, allowMulticastDestination = false)) return null
        val invokingLength = invokingPacketLength(invokingPacket) ?: return null
        if (!sameAddresses(validatedPacket, invokingPacket)) return null

        val quotedLength = minOf(invokingLength, MAX_QUOTED_PACKET_LENGTH)
        val icmpLength = ICMP_HEADER_LENGTH + quotedLength
        val result = ByteArray(IPV6_HEADER_LENGTH + icmpLength)
        result[0] = 0x60
        put16(result, PAYLOAD_LENGTH_OFFSET, icmpLength)
        result[NEXT_HEADER_OFFSET] = ICMPV6_PROTOCOL.toByte()
        result[HOP_LIMIT_OFFSET] = DEFAULT_HOP_LIMIT.toByte()
        validatedPacket.copyInto(
            result,
            SOURCE_OFFSET,
            DESTINATION_OFFSET,
            DESTINATION_OFFSET + IPV6_ADDRESS_LENGTH
        )
        validatedPacket.copyInto(
            result,
            DESTINATION_OFFSET,
            SOURCE_OFFSET,
            SOURCE_OFFSET + IPV6_ADDRESS_LENGTH
        )

        val icmpOffset = IPV6_HEADER_LENGTH
        result[icmpOffset] = DESTINATION_UNREACHABLE.toByte()
        result[icmpOffset + 1] = PORT_UNREACHABLE.toByte()
        invokingPacket.copyInto(result, icmpOffset + ICMP_HEADER_LENGTH, 0, quotedLength)
        put16(result, icmpOffset + CHECKSUM_OFFSET, icmpv6Checksum(result, icmpOffset, icmpLength))
        return result
    }

    private fun invokingPacketLength(packet: ByteArray): Int? {
        if (packet.size < IPV6_HEADER_LENGTH) return null
        if (((packet[0].toInt() ushr 4) and 0x0f) != IPV6_VERSION) return null
        val declaredLength = IPV6_HEADER_LENGTH + u16(packet, PAYLOAD_LENGTH_OFFSET)
        return declaredLength.takeIf { it == packet.size }
    }

    private fun sameAddresses(first: ByteArray, second: ByteArray): Boolean {
        return (SOURCE_OFFSET until DESTINATION_OFFSET + IPV6_ADDRESS_LENGTH).all { index ->
            first[index] == second[index]
        }
    }

    private fun icmpv6Checksum(packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        sum += checksumSum(packet, SOURCE_OFFSET, IPV6_ADDRESS_LENGTH * 2)
        sum += length.toLong()
        sum += ICMPV6_PROTOCOL.toLong()
        sum += checksumSum(packet, offset, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        val value = sum.inv().toInt() and 0xffff
        return if (value == 0) 0xffff else value
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

    private const val IPV6_VERSION = 6
    private const val IPV6_HEADER_LENGTH = 40
    private const val IPV6_ADDRESS_LENGTH = 16
    private const val PAYLOAD_LENGTH_OFFSET = 4
    private const val NEXT_HEADER_OFFSET = 6
    private const val HOP_LIMIT_OFFSET = 7
    private const val SOURCE_OFFSET = 8
    private const val DESTINATION_OFFSET = 24
    private const val UDP_LENGTH_OFFSET = 4
    private const val UDP_CHECKSUM_OFFSET = 6
    private const val UDP_HEADER_LENGTH = 8
    private const val ICMP_HEADER_LENGTH = 8
    private const val CHECKSUM_OFFSET = 2
    private const val MIN_ORIGINAL_LENGTH = IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH
    private const val MAX_QUOTED_PACKET_LENGTH = 1_280 - IPV6_HEADER_LENGTH - ICMP_HEADER_LENGTH
    private const val DEFAULT_HOP_LIMIT = 64
    private const val UDP_PROTOCOL = 17
    private const val ICMPV6_PROTOCOL = 58
    private const val DESTINATION_UNREACHABLE = 1
    private const val PORT_UNREACHABLE = 4
}
