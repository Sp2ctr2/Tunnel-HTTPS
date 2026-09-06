package com.tunnelvpn.app

import java.util.LinkedHashMap
import java.util.TreeMap

internal class Ipv4PacketNormalizer(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val fragmentTimeoutMs: Long = DEFAULT_FRAGMENT_TIMEOUT_MS,
    private val maxAssemblies: Int = DEFAULT_MAX_ASSEMBLIES,
    private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
    private val maxFragmentsPerAssembly: Int = DEFAULT_MAX_FRAGMENTS_PER_ASSEMBLY
) {
    sealed class Result {
        data class Ready(val packet: ByteArray, val reassembled: Boolean = false) : Result()
        data object Pending : Result()
        data object Rejected : Result()
    }

    private data class FragmentKey(
        val source: AddressKey,
        val destination: AddressKey,
        val protocol: Int,
        val identification: Int
    )

    private class AddressKey(packet: ByteArray, offset: Int) {
        private val value = packet.copyOfRange(offset, offset + ADDRESS_LENGTH)
        private val hash = value.contentHashCode()

        override fun equals(other: Any?): Boolean = other is AddressKey && value.contentEquals(other.value)

        override fun hashCode(): Int = hash
    }

    private data class FragmentPiece(val payload: ByteArray, val more: Boolean)

    private data class FirstHeader(val bytes: ByteArray, val headerLength: Int)

    private data class Assembly(
        val expiresAtMs: Long,
        val dscp: Int,
        var ecnMask: Int = 0,
        var firstHeader: FirstHeader? = null,
        var totalPayloadLength: Int? = null,
        var retainedBytes: Int = 0,
        val fragments: TreeMap<Int, FragmentPiece> = TreeMap()
    )

    private data class ParsedFragment(
        val header: ByteArray?,
        val headerLength: Int,
        val fragmentField: Int,
        val key: FragmentKey,
        val offset: Int,
        val more: Boolean,
        val payload: ByteArray,
        val dscp: Int,
        val ecn: Int
    )

    private val assemblies = object : LinkedHashMap<FragmentKey, Assembly>(16, 0.75f, true) {}
    private val rejectedIdentities = LinkedHashMap<FragmentKey, Long>()
    private var bufferedBytes = 0
    private var fragmentQuarantineUntilMs = 0L

    init {
        require(fragmentTimeoutMs > 0L)
        require(maxAssemblies > 0)
        require(maxBufferedBytes > 0)
        require(maxFragmentsPerAssembly > 0)
    }

    fun process(packet: ByteArray, length: Int = packet.size): Result {
        val headerLength = validatedHeaderLength(packet, length)
        if (headerLength < 0) return Result.Rejected
        val fragmentField = u16(packet, FRAGMENT_FIELD_OFFSET)
        val offset = (fragmentField and FRAGMENT_OFFSET_MASK) * FRAGMENT_ALIGNMENT
        val more = fragmentField and MORE_FRAGMENTS_FLAG != 0
        if (!more && offset == 0) return Result.Ready(packet.copyOf(length))
        val protocol = packet[PROTOCOL_OFFSET].toInt() and 0xff
        val fragment = ParsedFragment(
            header = if (offset == 0) packet.copyOfRange(0, headerLength) else null,
            headerLength = headerLength,
            fragmentField = fragmentField,
            key = FragmentKey(
                AddressKey(packet, SOURCE_OFFSET),
                AddressKey(packet, DESTINATION_OFFSET),
                protocol,
                u16(packet, IDENTIFICATION_OFFSET)
            ),
            offset = offset,
            more = more,
            payload = packet.copyOfRange(headerLength, length),
            dscp = packet[TOS_OFFSET].toInt() and DSCP_MASK,
            ecn = packet[TOS_OFFSET].toInt() and ECN_MASK
        )
        return synchronized(this) {
            val now = clock()
            cleanupExpired(now)
            if (fragment.fragmentField and DONT_FRAGMENT_FLAG != 0) {
                rejectIdentity(fragment.key, assemblies[fragment.key], now)
                return@synchronized Result.Rejected
            }
            if (now < fragmentQuarantineUntilMs || rejectedIdentities.containsKey(fragment.key)) {
                return@synchronized Result.Rejected
            }
            if (fragment.payload.isEmpty() || fragment.more && fragment.payload.size % FRAGMENT_ALIGNMENT != 0 ||
                fragment.offset > MAX_IPV4_PAYLOAD_LENGTH - fragment.payload.size
            ) {
                rejectIdentity(fragment.key, assemblies[fragment.key], now)
                return@synchronized Result.Rejected
            }
            if (fragment.offset == 0 && !hasCompleteFirstHeader(fragment)) {
                rejectIdentity(fragment.key, assemblies[fragment.key], now)
                return@synchronized Result.Rejected
            }
            acceptFragment(fragment, now)
        }
    }

    @Synchronized
    fun reset() {
        assemblies.clear()
        rejectedIdentities.clear()
        bufferedBytes = 0
        fragmentQuarantineUntilMs = 0L
    }

    @Synchronized
    fun expire() {
        cleanupExpired(clock())
    }

    @Synchronized
    internal fun retainedFragmentBytes(): Int {
        cleanupExpired(clock())
        return bufferedBytes
    }

    @Synchronized
    internal fun pendingAssemblies(): Int {
        cleanupExpired(clock())
        return assemblies.size
    }

    private fun validatedHeaderLength(packet: ByteArray, length: Int): Int {
        if (length !in MIN_HEADER_LENGTH..minOf(packet.size, MAX_PACKET_LENGTH)) return -1
        if (((packet[0].toInt() ushr 4) and 0x0f) != IPV4_VERSION) return -1
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        if (headerLength !in MIN_HEADER_LENGTH..MAX_HEADER_LENGTH || headerLength > length) return -1
        if (u16(packet, TOTAL_LENGTH_OFFSET) != length) return -1
        if (!hasSafeOptions(packet, headerLength) || checksum(packet, 0, headerLength) != 0) return -1
        val fragmentField = u16(packet, FRAGMENT_FIELD_OFFSET)
        if (fragmentField and RESERVED_FLAG != 0) return -1
        return headerLength
    }

    private fun acceptFragment(fragment: ParsedFragment, now: Long): Result {
        var assembly = assemblies[fragment.key]
        val start = fragment.offset
        val end = start + fragment.payload.size
        if (assembly != null) {
            if (assembly.dscp != fragment.dscp || !mergeEcn(assembly, fragment.ecn)) {
                rejectIdentity(fragment.key, assembly, now)
                return Result.Rejected
            }
            val existing = assembly.fragments[start]
            if (existing != null) {
                if (existing.more == fragment.more && existing.payload.contentEquals(fragment.payload)) {
                    return Result.Pending
                }
                rejectIdentity(fragment.key, assembly, now)
                return Result.Rejected
            }
            val floor = assembly.fragments.floorEntry(start)
            if (floor != null && floor.key + floor.value.payload.size > start) {
                rejectIdentity(fragment.key, assembly, now)
                return Result.Rejected
            }
            val ceiling = assembly.fragments.ceilingEntry(start)
            if (ceiling != null && end > ceiling.key) {
                rejectIdentity(fragment.key, assembly, now)
                return Result.Rejected
            }
        }
        val headerBytesToRetain = if (fragment.offset == 0 && assembly?.firstHeader == null) {
            fragment.headerLength
        } else {
            0
        }
        val retainedByFragment = fragment.payload.size + headerBytesToRetain
        if (retainedByFragment > maxBufferedBytes || bufferedBytes > maxBufferedBytes - retainedByFragment) {
            rejectIdentity(fragment.key, assembly, now)
            return Result.Rejected
        }
        if (assembly == null) {
            ensureAssemblyCapacity(now)
            assembly = Assembly(deadline(now), fragment.dscp)
            if (!mergeEcn(assembly, fragment.ecn)) return Result.Rejected
            assemblies[fragment.key] = assembly
        }
        if (assembly.fragments.size >= maxFragmentsPerAssembly) {
            rejectIdentity(fragment.key, assembly, now)
            return Result.Rejected
        }
        val knownTotal = assembly.totalPayloadLength
        if (knownTotal != null && end > knownTotal) {
            rejectIdentity(fragment.key, assembly, now)
            return Result.Rejected
        }
        if (!fragment.more) {
            if (knownTotal != null && knownTotal != end ||
                assembly.fragments.any { entry -> entry.key + entry.value.payload.size > end }
            ) {
                rejectIdentity(fragment.key, assembly, now)
                return Result.Rejected
            }
            assembly.totalPayloadLength = end
        }
        if (fragment.offset == 0) {
            val first = FirstHeader(fragment.header ?: return Result.Rejected, fragment.headerLength)
            assembly.firstHeader = first
            val maximumPayload = MAX_PACKET_LENGTH - first.headerLength
            if (end > maximumPayload || assembly.fragments.any { entry ->
                    entry.key + entry.value.payload.size > maximumPayload
                }
            ) {
                rejectIdentity(fragment.key, assembly, now)
                return Result.Rejected
            }
        } else {
            val first = assembly.firstHeader
            if (first != null && end > MAX_PACKET_LENGTH - first.headerLength) {
                rejectIdentity(fragment.key, assembly, now)
                return Result.Rejected
            }
        }
        assembly.fragments[start] = FragmentPiece(fragment.payload, fragment.more)
        assembly.retainedBytes += retainedByFragment
        bufferedBytes += retainedByFragment

        val total = assembly.totalPayloadLength ?: return Result.Pending
        val first = assembly.firstHeader ?: return Result.Pending
        var cursor = 0
        for ((fragmentStart, piece) in assembly.fragments) {
            if (fragmentStart != cursor) return Result.Pending
            cursor += piece.payload.size
        }
        if (cursor != total) return Result.Pending
        val payload = ByteArray(total)
        assembly.fragments.forEach { (fragmentStart, piece) -> piece.payload.copyInto(payload, fragmentStart) }
        val ecn = combinedEcn(assembly.ecnMask)
        val rebuilt = ecn?.let { rebuild(first, payload, it, fragment.key.protocol) }
        if (rebuilt == null) {
            rejectIdentity(fragment.key, assembly, now)
            return Result.Rejected
        }
        removeAssembly(fragment.key)
        return Result.Ready(rebuilt, reassembled = true)
    }

    private fun rebuild(first: FirstHeader, payload: ByteArray, ecn: Int, protocol: Int): ByteArray? {
        val totalLength = first.headerLength + payload.size
        if (totalLength !in first.headerLength..MAX_PACKET_LENGTH) return null
        if (!validReassembledTransport(payload, protocol)) return null
        val rebuilt = ByteArray(totalLength)
        first.bytes.copyInto(rebuilt)
        payload.copyInto(rebuilt, first.headerLength)
        rebuilt[TOS_OFFSET] = ((rebuilt[TOS_OFFSET].toInt() and DSCP_MASK) or ecn).toByte()
        put16(rebuilt, TOTAL_LENGTH_OFFSET, totalLength)
        val preservedDf = u16(rebuilt, FRAGMENT_FIELD_OFFSET) and DONT_FRAGMENT_FLAG
        put16(rebuilt, FRAGMENT_FIELD_OFFSET, preservedDf)
        put16(rebuilt, HEADER_CHECKSUM_OFFSET, 0)
        put16(rebuilt, HEADER_CHECKSUM_OFFSET, checksum(rebuilt, 0, first.headerLength))
        return rebuilt
    }

    private fun hasCompleteFirstHeader(fragment: ParsedFragment): Boolean {
        val payload = fragment.payload
        return when (fragment.key.protocol) {
            UDP_PROTOCOL -> payload.size >= UDP_HEADER_LENGTH && u16(payload, UDP_LENGTH_OFFSET) >= UDP_HEADER_LENGTH
            TCP_PROTOCOL -> {
                if (payload.size < TCP_MIN_HEADER_LENGTH) false else {
                    val tcpHeaderLength = ((payload[TCP_DATA_OFFSET].toInt() ushr 4) and 0x0f) * 4
                    tcpHeaderLength in TCP_MIN_HEADER_LENGTH..payload.size
                }
            }
            ICMP_PROTOCOL -> payload.size >= ICMP_MIN_HEADER_LENGTH
            else -> false
        }
    }

    private fun validReassembledTransport(payload: ByteArray, protocol: Int): Boolean {
        return when (protocol) {
            UDP_PROTOCOL -> payload.size >= UDP_HEADER_LENGTH && u16(payload, UDP_LENGTH_OFFSET) == payload.size
            TCP_PROTOCOL -> {
                if (payload.size < TCP_MIN_HEADER_LENGTH) false else {
                    val tcpHeaderLength = ((payload[TCP_DATA_OFFSET].toInt() ushr 4) and 0x0f) * 4
                    tcpHeaderLength in TCP_MIN_HEADER_LENGTH..payload.size
                }
            }
            ICMP_PROTOCOL -> payload.size >= ICMP_MIN_HEADER_LENGTH
            else -> false
        }
    }

    private fun hasSafeOptions(packet: ByteArray, headerLength: Int): Boolean {
        var offset = MIN_HEADER_LENGTH
        while (offset < headerLength) {
            when (packet[offset].toInt() and 0xff) {
                END_OF_OPTIONS -> {
                    return (offset until headerLength).all { index -> packet[index].toInt() == 0 }
                }
                NO_OPERATION -> offset += 1
                else -> return false
            }
        }
        return true
    }

    private fun mergeEcn(assembly: Assembly, ecn: Int): Boolean {
        assembly.ecnMask = assembly.ecnMask or (1 shl ecn)
        return combinedEcn(assembly.ecnMask) != null
    }

    private fun combinedEcn(mask: Int): Int? {
        if (mask == 0) return null
        val hasNotEct = mask and (1 shl ECN_NOT_ECT) != 0
        val hasCe = mask and (1 shl ECN_CE) != 0
        if (hasNotEct && mask != 1 shl ECN_NOT_ECT) return null
        if (hasCe) return ECN_CE
        return if (mask and (mask - 1) == 0) Integer.numberOfTrailingZeros(mask) else null
    }

    private fun ensureAssemblyCapacity(now: Long) {
        while (assemblies.size >= maxAssemblies) {
            val eldest = assemblies.entries.firstOrNull() ?: return
            rejectIdentity(eldest.key, eldest.value, now)
        }
    }

    private fun cleanupExpired(now: Long) {
        val assemblyIterator = assemblies.entries.iterator()
        while (assemblyIterator.hasNext()) {
            val entry = assemblyIterator.next()
            if (entry.value.expiresAtMs <= now) {
                bufferedBytes = (bufferedBytes - entry.value.retainedBytes).coerceAtLeast(0)
                assemblyIterator.remove()
            }
        }
        val rejectedIterator = rejectedIdentities.entries.iterator()
        while (rejectedIterator.hasNext()) {
            if (rejectedIterator.next().value <= now) rejectedIterator.remove()
        }
        if (fragmentQuarantineUntilMs <= now) fragmentQuarantineUntilMs = 0L
    }

    private fun rejectIdentity(key: FragmentKey, assembly: Assembly?, now: Long) {
        val expiresAt = assembly?.expiresAtMs ?: deadline(now)
        removeAssembly(key)
        val tombstoneLimit = (maxAssemblies.toLong() * TOMBSTONE_CAPACITY_MULTIPLIER)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (rejectedIdentities.size >= tombstoneLimit) {
            fragmentQuarantineUntilMs = maxOf(
                fragmentQuarantineUntilMs,
                expiresAt,
                rejectedIdentities.values.maxOrNull() ?: 0L
            )
            assemblies.clear()
            rejectedIdentities.clear()
            bufferedBytes = 0
        } else {
            rejectedIdentities[key] = expiresAt
        }
    }

    private fun removeAssembly(key: FragmentKey) {
        val removed = assemblies.remove(key) ?: return
        bufferedBytes = (bufferedBytes - removed.retainedBytes).coerceAtLeast(0)
    }

    private fun deadline(now: Long): Long {
        return if (now > Long.MAX_VALUE - fragmentTimeoutMs) Long.MAX_VALUE else now + fragmentTimeoutMs
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
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    companion object {
        internal const val DEFAULT_FRAGMENT_TIMEOUT_MS = 60_000L
        internal const val DEFAULT_MAX_ASSEMBLIES = 64
        internal const val DEFAULT_MAX_BUFFERED_BYTES = 2 * 1024 * 1024
        internal const val DEFAULT_MAX_FRAGMENTS_PER_ASSEMBLY = 64
        private const val IPV4_VERSION = 4
        private const val MIN_HEADER_LENGTH = 20
        private const val MAX_HEADER_LENGTH = 60
        private const val MAX_PACKET_LENGTH = 65_535
        private const val MAX_IPV4_PAYLOAD_LENGTH = MAX_PACKET_LENGTH - MIN_HEADER_LENGTH
        private const val ADDRESS_LENGTH = 4
        private const val TOS_OFFSET = 1
        private const val TOTAL_LENGTH_OFFSET = 2
        private const val IDENTIFICATION_OFFSET = 4
        private const val FRAGMENT_FIELD_OFFSET = 6
        private const val PROTOCOL_OFFSET = 9
        private const val HEADER_CHECKSUM_OFFSET = 10
        private const val SOURCE_OFFSET = 12
        private const val DESTINATION_OFFSET = 16
        private const val RESERVED_FLAG = 0x8000
        private const val DONT_FRAGMENT_FLAG = 0x4000
        private const val MORE_FRAGMENTS_FLAG = 0x2000
        private const val FRAGMENT_OFFSET_MASK = 0x1fff
        private const val FRAGMENT_ALIGNMENT = 8
        private const val DSCP_MASK = 0xfc
        private const val ECN_MASK = 0x03
        private const val ECN_NOT_ECT = 0
        private const val ECN_CE = 3
        private const val END_OF_OPTIONS = 0
        private const val NO_OPERATION = 1
        private const val ICMP_PROTOCOL = 1
        private const val TCP_PROTOCOL = 6
        private const val UDP_PROTOCOL = 17
        private const val ICMP_MIN_HEADER_LENGTH = 8
        private const val TCP_MIN_HEADER_LENGTH = 20
        private const val TCP_DATA_OFFSET = 12
        private const val UDP_HEADER_LENGTH = 8
        private const val UDP_LENGTH_OFFSET = 4
        private const val TOMBSTONE_CAPACITY_MULTIPLIER = 16
    }
}
