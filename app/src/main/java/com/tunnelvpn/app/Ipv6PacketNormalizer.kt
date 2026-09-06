package com.tunnelvpn.app

import java.util.LinkedHashMap
import java.util.TreeMap

internal class Ipv6PacketNormalizer(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val fragmentTimeoutMs: Long = 60_000L,
    private val maxAssemblies: Int = 64,
    private val maxBufferedBytes: Int = 2 * 1024 * 1024,
    private val maxFragmentsPerAssembly: Int = 64,
    private val icmpErrorRateLimiter: Ipv6IcmpErrorRateLimiter = Ipv6IcmpErrorRateLimiter(clock),
    icmpSourceAddress: ByteArray? = null
) {
    sealed class Result {
        data class Ready(
            val packet: ByteArray,
            val reassembled: Boolean = false,
            val invokingPacket: ByteArray = packet
        ) : Result()
        data object Pending : Result()
        data object Rejected : Result()
    }

    private data class FragmentKey(
        val source: AddressKey,
        val destination: AddressKey,
        val identification: Int
    )

    private class AddressKey(bytes: ByteArray) {
        private val value = bytes.copyOf()
        private val hash = value.contentHashCode()

        override fun equals(other: Any?): Boolean = other is AddressKey && value.contentEquals(other.value)

        override fun hashCode(): Int = hash
    }

    private data class Assembly(
        val expiresAtMs: Long,
        var firstFragment: FirstFragment? = null,
        var totalLength: Int? = null,
        var retainedBytes: Int = 0,
        var ecnMask: Int = 0,
        val fragments: TreeMap<Int, FragmentPiece> = TreeMap()
    )

    private data class FirstFragment(
        val packetPrefix: ByteArray,
        val previousNextHeaderOffset: Int,
        val nextHeader: Int,
        val originalPacket: ByteArray
    )

    private data class FragmentPiece(
        val payload: ByteArray,
        val more: Boolean,
        val originalPacket: ByteArray,
        val fragmentHeaderOffset: Int
    )

    private data class ParsedFragment(
        val key: FragmentKey,
        val packetPrefix: ByteArray,
        val previousNextHeaderOffset: Int,
        val nextHeader: Int,
        val extensionCountBefore: Int,
        val extensionBytesBefore: Int,
        val offset: Int,
        val more: Boolean,
        val payload: ByteArray,
        val originalPacket: ByteArray,
        val fragmentHeaderOffset: Int
    )

    private val assemblies = object : LinkedHashMap<FragmentKey, Assembly>(16, 0.75f, true) {}
    private val rejectedIdentities = LinkedHashMap<FragmentKey, Long>()
    private val icmpErrors = ArrayDeque<ByteArray>()
    private val icmpSourceAddress = icmpSourceAddress?.copyOf()
    private var bufferedBytes = 0
    private var fragmentQuarantineUntilMs = 0L

    init {
        require(fragmentTimeoutMs > 0L)
        require(maxAssemblies > 0)
        require(maxBufferedBytes > 0)
        require(maxFragmentsPerAssembly > 0)
        require(this.icmpSourceAddress == null || Ipv6IcmpErrorPolicy.isUsableUnicast(this.icmpSourceAddress))
    }

    @Synchronized
    fun process(packet: ByteArray, length: Int = packet.size): Result {
        val now = clock()
        cleanupExpired(now)
        val safePacket = validateBasePacket(packet, length) ?: return Result.Rejected
        return normalize(safePacket, permitFragment = true, now)
    }

    @Synchronized
    fun reset() {
        assemblies.clear()
        rejectedIdentities.clear()
        bufferedBytes = 0
        fragmentQuarantineUntilMs = 0L
        icmpErrors.clear()
    }

    @Synchronized
    internal fun drainIcmpErrors(): List<ByteArray> {
        cleanupExpired(clock())
        if (icmpErrors.isEmpty()) return emptyList()
        val result = icmpErrors.toList()
        icmpErrors.clear()
        return result
    }

    @Synchronized
    internal fun retainedFragmentBytes(): Int = bufferedBytes

    @Synchronized
    internal fun pendingAssemblies(): Int = assemblies.size

    private fun normalize(
        packet: ByteArray,
        permitFragment: Boolean,
        now: Long,
        reassembled: Boolean = false,
        invokingPacket: ByteArray = packet,
        atomicFragmentOffset: Int? = null,
        atomicRewrittenNextHeaderOffset: Int? = null
    ): Result {
        var protocol = packet[NEXT_HEADER_OFFSET].toInt() and 0xff
        var offset = IPV6_HEADER_LENGTH
        var previousNextHeaderOffset = if (atomicRewrittenNextHeaderOffset == NEXT_HEADER_OFFSET) {
            atomicFragmentOffset ?: NEXT_HEADER_OFFSET
        } else {
            NEXT_HEADER_OFFSET
        }
        var extensionCount = 0
        var extensionBytes = 0
        val errorPacket = if (atomicFragmentOffset == null) packet else invokingPacket
        fun errorOffset(localOffset: Int): Int {
            val fragmentOffset = atomicFragmentOffset ?: return localOffset
            return if (localOffset >= fragmentOffset) localOffset + FRAGMENT_HEADER_LENGTH else localOffset
        }

        while (isExtensionHeader(protocol)) {
            if (offset + 2 > packet.size) return Result.Rejected
            if (protocol == FRAGMENT) {
                if (!permitFragment) return Result.Rejected
                if (offset + FRAGMENT_HEADER_LENGTH <= packet.size) {
                    val field = u16(packet, offset + 2)
                    val fragmentOffset = ((field ushr 3) and 0x1fff) * 8
                    val more = field and 1 != 0
                    val fragmentPayloadLength = packet.size - offset - FRAGMENT_HEADER_LENGTH
                    if (fragmentOffset != 0 || more) {
                        val key = FragmentKey(
                            AddressKey(packet.copyOfRange(SOURCE_OFFSET, SOURCE_OFFSET + ADDRESS_LENGTH)),
                            AddressKey(packet.copyOfRange(DESTINATION_OFFSET, DESTINATION_OFFSET + ADDRESS_LENGTH)),
                            u32(packet, offset + 4)
                        )
                        if (now < fragmentQuarantineUntilMs || rejectedIdentities.containsKey(key)) {
                            return Result.Rejected
                        }
                    }
                    if (more && fragmentPayloadLength % 8 != 0) {
                        enqueueIcmp(Ipv6IcmpFragmentError.parameterProblem(packet, 0, PAYLOAD_LENGTH_OFFSET))
                        return Result.Rejected
                    }
                    if (fragmentOffset > MAX_IPV6_PAYLOAD_LENGTH - fragmentPayloadLength) {
                        enqueueIcmp(Ipv6IcmpFragmentError.parameterProblem(packet, 0, offset + 2))
                        return Result.Rejected
                    }
                }
                val fragment = parseFragment(
                    packet,
                    offset,
                    previousNextHeaderOffset,
                    extensionCount,
                    extensionBytes
                ) ?: return Result.Rejected
                if (fragment.offset == 0 && fragment.nextHeader == HOP_BY_HOP) {
                    enqueueIcmp(
                        Ipv6IcmpFragmentError.parameterProblem(
                            fragment.originalPacket,
                            1,
                            fragment.fragmentHeaderOffset
                        )
                    )
                    return Result.Rejected
                }
                if (fragment.offset == 0 && !fragment.more) {
                    val atomic = rebuildWithoutFragment(fragment, fragment.payload) ?: return Result.Rejected
                    return normalize(
                        atomic,
                        permitFragment = false,
                        now,
                        invokingPacket = packet,
                        atomicFragmentOffset = offset,
                        atomicRewrittenNextHeaderOffset = previousNextHeaderOffset
                    )
                }
                if (fragment.offset == 0 && !hasCompleteFirstFragmentHeader(fragment)) {
                    enqueueIcmp(Ipv6IcmpFragmentError.incompleteHeaderChain(packet))
                    return Result.Rejected
                }
                return acceptFragment(fragment, now)
            }
            if (extensionCount >= MAX_EXTENSION_HEADERS) return Result.Rejected
            if (protocol == AUTHENTICATION) return Result.Rejected
            if (protocol == HOP_BY_HOP && offset != IPV6_HEADER_LENGTH) {
                enqueueIcmp(Ipv6IcmpFragmentError.parameterProblem(errorPacket, 1, previousNextHeaderOffset))
                return Result.Rejected
            }
            val headerLength = ((packet[offset + 1].toInt() and 0xff) + 1) * 8
            if (headerLength < 8 || headerLength > packet.size - offset) return Result.Rejected
            extensionBytes += headerLength
            if (extensionBytes > MAX_EXTENSION_BYTES) return Result.Rejected
            if ((protocol == HOP_BY_HOP || protocol == DESTINATION_OPTIONS) &&
                !safeOptions(packet, offset + 2, offset + headerLength)
            ) {
                optionProblem(packet, offset + 2, offset + headerLength)?.let { problem ->
                    enqueueIcmp(
                        Ipv6IcmpFragmentError.parameterProblem(
                            errorPacket,
                            problem.code,
                            errorOffset(problem.pointer),
                            problem.allowMulticastDestination,
                            if (problem.allowMulticastDestination) icmpSourceAddress else null
                        )
                    )
                }
                return Result.Rejected
            }
            if (protocol == ROUTING && (packet[offset + 3].toInt() and 0xff) != 0) {
                enqueueIcmp(Ipv6IcmpFragmentError.parameterProblem(errorPacket, 0, errorOffset(offset + 2)))
                return Result.Rejected
            }
            protocol = packet[offset].toInt() and 0xff
            previousNextHeaderOffset = if (offset == atomicRewrittenNextHeaderOffset) {
                atomicFragmentOffset ?: errorOffset(offset)
            } else {
                errorOffset(offset)
            }
            offset += headerLength
            extensionCount += 1
        }

        val upperLength = packet.size - offset
        if (protocol == ESP || protocol == NO_NEXT_HEADER) return Result.Rejected
        if (minimumUpperLength(protocol) == Int.MAX_VALUE) {
            enqueueIcmp(Ipv6IcmpFragmentError.parameterProblem(errorPacket, 1, previousNextHeaderOffset))
            return Result.Rejected
        }
        if (!validUpperLayer(packet, offset, upperLength, protocol)) return Result.Rejected
        if (extensionCount == 0) return Result.Ready(packet, reassembled, invokingPacket)
        val normalized = ByteArray(IPV6_HEADER_LENGTH + upperLength)
        packet.copyInto(normalized, 0, 0, IPV6_HEADER_LENGTH)
        normalized[NEXT_HEADER_OFFSET] = protocol.toByte()
        put16(normalized, PAYLOAD_LENGTH_OFFSET, upperLength)
        packet.copyInto(normalized, IPV6_HEADER_LENGTH, offset, packet.size)
        return Result.Ready(normalized, reassembled, invokingPacket)
    }

    private fun parseFragment(
        packet: ByteArray,
        fragmentOffset: Int,
        previousNextHeaderOffset: Int,
        extensionCountBefore: Int,
        extensionBytesBefore: Int
    ): ParsedFragment? {
        if (fragmentOffset + FRAGMENT_HEADER_LENGTH > packet.size) return null
        val field = u16(packet, fragmentOffset + 2)
        val offset = ((field ushr 3) and 0x1fff) * 8
        val more = field and 1 != 0
        val payloadStart = fragmentOffset + FRAGMENT_HEADER_LENGTH
        val payloadLength = packet.size - payloadStart
        if (payloadLength == 0 || more && payloadLength % 8 != 0) return null
        if (offset > MAX_IPV6_PAYLOAD_LENGTH - payloadLength) return null
        val nextHeader = packet[fragmentOffset].toInt() and 0xff
        val id = u32(packet, fragmentOffset + 4)
        val prefix = packet.copyOfRange(0, fragmentOffset)
        return ParsedFragment(
            key = FragmentKey(
                AddressKey(packet.copyOfRange(SOURCE_OFFSET, SOURCE_OFFSET + ADDRESS_LENGTH)),
                AddressKey(packet.copyOfRange(DESTINATION_OFFSET, DESTINATION_OFFSET + ADDRESS_LENGTH)),
                id
            ),
            packetPrefix = prefix,
            previousNextHeaderOffset = previousNextHeaderOffset,
            nextHeader = nextHeader,
            extensionCountBefore = extensionCountBefore,
            extensionBytesBefore = extensionBytesBefore,
            offset = offset,
            more = more,
            payload = packet.copyOfRange(payloadStart, packet.size),
            originalPacket = packet,
            fragmentHeaderOffset = fragmentOffset
        )
    }

    private fun acceptFragment(fragment: ParsedFragment, now: Long): Result {
        if (rejectedIdentities.containsKey(fragment.key)) return Result.Rejected
        var assembly = assemblies[fragment.key]
        val start = fragment.offset
        val end = start + fragment.payload.size
        if (assembly != null) {
            val existing = assembly.fragments[start]
            if (existing != null) {
                if (existing.more == fragment.more && existing.payload.contentEquals(fragment.payload)) {
                    if (!mergeEcn(assembly, fragment)) {
                        rejectOverlappingAssembly(fragment.key, assembly)
                        return Result.Rejected
                    }
                    return Result.Pending
                }
                rejectOverlappingAssembly(fragment.key, assembly)
                return Result.Rejected
            }
            val floor = assembly.fragments.floorEntry(start)
            if (floor != null && floor.key + floor.value.payload.size > start) {
                rejectOverlappingAssembly(fragment.key, assembly)
                return Result.Rejected
            }
            val ceiling = assembly.fragments.ceilingEntry(start)
            if (ceiling != null && end > ceiling.key) {
                rejectOverlappingAssembly(fragment.key, assembly)
                return Result.Rejected
            }
        }
        if (fragment.payload.size > maxBufferedBytes || bufferedBytes > maxBufferedBytes - fragment.payload.size) {
            if (assembly != null) removeAssembly(fragment.key)
            return Result.Rejected
        }
        if (assembly == null) {
            ensureAssemblyCapacity()
            assembly = Assembly(
                expiresAtMs = deadline(now)
            )
            assemblies[fragment.key] = assembly
        }

        if (!mergeEcn(assembly, fragment)) {
            rejectOverlappingAssembly(fragment.key, assembly)
            return Result.Rejected
        }

        if (assembly.fragments.size >= maxFragmentsPerAssembly) {
            removeAssembly(fragment.key)
            return Result.Rejected
        }
        val knownTotal = assembly.totalLength
        if (knownTotal != null && end > knownTotal) {
            removeAssembly(fragment.key)
            return Result.Rejected
        }
        if (!fragment.more) {
            if (knownTotal != null && knownTotal != end ||
                assembly.fragments.any { it.key + it.value.payload.size > end }
            ) {
                removeAssembly(fragment.key)
                return Result.Rejected
            }
            assembly.totalLength = end
        }
        if (fragment.offset == 0) {
            assembly.firstFragment = FirstFragment(
                fragment.packetPrefix,
                fragment.previousNextHeaderOffset,
                fragment.nextHeader,
                fragment.originalPacket
            )
            val maximumLength = maxFragmentableLength(fragment.packetPrefix)
            val priorOverflow = assembly.fragments.entries.firstOrNull {
                it.key + it.value.payload.size > maximumLength
            }
            if (priorOverflow != null || end > maximumLength) {
                removeAssembly(fragment.key)
                val offendingPacket = priorOverflow?.value?.originalPacket ?: fragment.originalPacket
                val offendingHeaderOffset = priorOverflow?.value?.fragmentHeaderOffset
                    ?: fragment.fragmentHeaderOffset
                enqueueIcmp(
                    Ipv6IcmpFragmentError.parameterProblem(
                        offendingPacket,
                        0,
                        offendingHeaderOffset + 2
                    )
                )
                return Result.Rejected
            }
        } else {
            val first = assembly.firstFragment
            if (first != null && end > maxFragmentableLength(first.packetPrefix)) {
                removeAssembly(fragment.key)
                enqueueIcmp(
                    Ipv6IcmpFragmentError.parameterProblem(
                        fragment.originalPacket,
                        0,
                        fragment.fragmentHeaderOffset + 2
                    )
                )
                return Result.Rejected
            }
        }
        assembly.fragments[start] = FragmentPiece(
            fragment.payload,
            fragment.more,
            fragment.originalPacket,
            fragment.fragmentHeaderOffset
        )
        assembly.retainedBytes += fragment.payload.size
        bufferedBytes += fragment.payload.size

        val total = assembly.totalLength ?: return Result.Pending
        val first = assembly.firstFragment ?: return Result.Pending
        var cursor = 0
        for ((fragmentStart, piece) in assembly.fragments) {
            if (fragmentStart != cursor) return Result.Pending
            cursor += piece.payload.size
        }
        if (cursor != total) return Result.Pending
        val payload = ByteArray(total)
        assembly.fragments.forEach { (fragmentStart, piece) -> piece.payload.copyInto(payload, fragmentStart) }
        val rebuilt = rebuildWithoutFragment(
            ParsedFragment(
                fragment.key,
                first.packetPrefix,
                first.previousNextHeaderOffset,
                first.nextHeader,
                0,
                0,
                0,
                false,
                payload,
                first.originalPacket,
                0
            ),
            payload
        )
        val ecn = combinedEcn(assembly.ecnMask)
        removeAssembly(fragment.key)
        if (rebuilt == null || ecn == null) return Result.Rejected
        setEcn(rebuilt, ecn)
        return normalize(rebuilt, permitFragment = false, now, reassembled = true)
    }

    private fun mergeEcn(assembly: Assembly, fragment: ParsedFragment): Boolean {
        assembly.ecnMask = assembly.ecnMask or (1 shl ecn(fragment.packetPrefix))
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

    private fun ecn(packet: ByteArray): Int = (packet[1].toInt() ushr 4) and 0x03

    private fun setEcn(packet: ByteArray, value: Int) {
        packet[1] = ((packet[1].toInt() and 0xcf) or (value shl 4)).toByte()
    }

    private fun rebuildWithoutFragment(fragment: ParsedFragment, payload: ByteArray): ByteArray? {
        val totalLength = fragment.packetPrefix.size + payload.size
        if (totalLength !in IPV6_HEADER_LENGTH..MAX_IPV6_PACKET_LENGTH) return null
        val rebuilt = ByteArray(totalLength)
        fragment.packetPrefix.copyInto(rebuilt)
        rebuilt[fragment.previousNextHeaderOffset] = fragment.nextHeader.toByte()
        put16(rebuilt, PAYLOAD_LENGTH_OFFSET, totalLength - IPV6_HEADER_LENGTH)
        payload.copyInto(rebuilt, fragment.packetPrefix.size)
        return rebuilt
    }

    private fun hasCompleteFirstFragmentHeader(fragment: ParsedFragment): Boolean {
        var protocol = fragment.nextHeader
        var offset = 0
        var extensionCount = fragment.extensionCountBefore
        var extensionBytes = fragment.extensionBytesBefore
        while (isExtensionHeader(protocol)) {
            if (extensionCount >= MAX_EXTENSION_HEADERS || offset + 2 > fragment.payload.size) return false
            val headerLength = when (protocol) {
                FRAGMENT -> FRAGMENT_HEADER_LENGTH
                AUTHENTICATION -> ((fragment.payload[offset + 1].toInt() and 0xff) + 2) * 4
                else -> ((fragment.payload[offset + 1].toInt() and 0xff) + 1) * 8
            }
            if (headerLength < 8 || headerLength > fragment.payload.size - offset) return false
            extensionBytes += headerLength
            if (extensionBytes > MAX_EXTENSION_BYTES) return false
            protocol = fragment.payload[offset].toInt() and 0xff
            offset += headerLength
            extensionCount += 1
        }
        if (protocol == NO_NEXT_HEADER) return true
        if (protocol == ESP) return offset <= fragment.payload.size - ESP_HEADER_LENGTH
        return upperLayerHeaderLength(fragment.payload, offset, fragment.payload.size, protocol) != null
    }

    private fun validUpperLayer(packet: ByteArray, offset: Int, length: Int, protocol: Int): Boolean {
        val headerLength = upperLayerHeaderLength(packet, offset, packet.size, protocol) ?: return false
        if (headerLength > length) return false
        if (protocol == UDP && u16(packet, offset + UDP_LENGTH_OFFSET) != length) return false
        return true
    }

    private fun upperLayerHeaderLength(packet: ByteArray, offset: Int, end: Int, protocol: Int): Int? {
        val minimum = minimumUpperLength(protocol)
        if (minimum == Int.MAX_VALUE || offset > end - minimum) return null
        if (protocol != TCP) return minimum
        val length = ((packet[offset + TCP_DATA_OFFSET].toInt() ushr 4) and 0x0f) * 4
        return length.takeIf { it >= minimum && offset <= end - it }
    }

    private fun safeOptions(packet: ByteArray, start: Int, end: Int): Boolean {
        var offset = start
        while (offset < end) {
            val type = packet[offset].toInt() and 0xff
            if (type == PAD1) {
                offset += 1
                continue
            }
            if (offset + 2 > end) return false
            val length = packet[offset + 1].toInt() and 0xff
            if (offset + 2 + length > end) return false
            if (type ushr 6 != 0) return false
            offset += 2 + length
        }
        return offset == end
    }

    private fun optionProblem(packet: ByteArray, start: Int, end: Int): OptionProblem? {
        var offset = start
        while (offset < end) {
            val type = packet[offset].toInt() and 0xff
            if (type == PAD1) {
                offset += 1
                continue
            }
            if (offset + 2 > end) return OptionProblem(0, offset, false)
            val length = packet[offset + 1].toInt() and 0xff
            if (offset + 2 + length > end) return OptionProblem(0, offset + 1, false)
            when (type ushr 6) {
                1 -> return null
                2 -> return OptionProblem(2, offset, true)
                3 -> if (packet[DESTINATION_OFFSET].toInt() and 0xff != 0xff) {
                    return OptionProblem(2, offset, false)
                } else {
                    return null
                }
            }
            offset += 2 + length
        }
        return null
    }

    private data class OptionProblem(
        val code: Int,
        val pointer: Int,
        val allowMulticastDestination: Boolean
    )

    private fun ensureAssemblyCapacity() {
        while (assemblies.size >= maxAssemblies) {
            val eldest = assemblies.entries.firstOrNull()?.key ?: return
            removeAssembly(eldest)
        }
    }

    private fun cleanupExpired(now: Long) {
        val expired = assemblies.filterValues { it.expiresAtMs <= now }.toList()
        expired.forEach { (key, assembly) ->
            assembly.firstFragment?.originalPacket?.let { packet ->
                enqueueIcmp(Ipv6IcmpFragmentError.reassemblyTimeout(packet))
            }
            removeAssembly(key)
        }
        rejectedIdentities.entries.removeAll { it.value <= now }
        if (fragmentQuarantineUntilMs <= now) fragmentQuarantineUntilMs = 0L
    }

    private fun enqueueIcmp(packet: ByteArray?) {
        if (packet == null || !icmpErrorRateLimiter.tryAcquire()) return
        while (icmpErrors.size >= maxAssemblies) icmpErrors.removeFirst()
        icmpErrors.addLast(packet)
    }

    private fun rejectOverlappingAssembly(key: FragmentKey, assembly: Assembly) {
        val expiresAtMs = assembly.expiresAtMs
        removeAssembly(key)
        val tombstoneLimit = (maxAssemblies.toLong() * TOMBSTONE_CAPACITY_MULTIPLIER)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (rejectedIdentities.size >= tombstoneLimit) {
            fragmentQuarantineUntilMs = maxOf(
                fragmentQuarantineUntilMs,
                expiresAtMs,
                rejectedIdentities.values.maxOrNull() ?: 0L
            )
            assemblies.clear()
            rejectedIdentities.clear()
            bufferedBytes = 0
            return
        }
        rejectedIdentities[key] = expiresAtMs
    }

    private fun removeAssembly(key: FragmentKey) {
        val removed = assemblies.remove(key) ?: return
        bufferedBytes = (bufferedBytes - removed.retainedBytes).coerceAtLeast(0)
    }

    private fun validateBasePacket(packet: ByteArray, length: Int): ByteArray? {
        if (length !in IPV6_HEADER_LENGTH..minOf(packet.size, MAX_IPV6_PACKET_LENGTH)) return null
        if (((packet[0].toInt() ushr 4) and 0x0f) != IPV6_VERSION) return null
        if (u16(packet, PAYLOAD_LENGTH_OFFSET) != length - IPV6_HEADER_LENGTH) return null
        return packet.copyOf(length)
    }

    private fun maxFragmentableLength(packetPrefix: ByteArray): Int {
        return MAX_IPV6_PAYLOAD_LENGTH - (packetPrefix.size - IPV6_HEADER_LENGTH)
    }

    private fun deadline(now: Long): Long {
        return if (now > Long.MAX_VALUE - fragmentTimeoutMs) Long.MAX_VALUE else now + fragmentTimeoutMs
    }

    private fun isExtensionHeader(protocol: Int): Boolean {
        return protocol == HOP_BY_HOP || protocol == ROUTING || protocol == FRAGMENT ||
            protocol == AUTHENTICATION || protocol == DESTINATION_OPTIONS
    }

    private fun minimumUpperLength(protocol: Int): Int {
        return when (protocol) {
            TCP -> 20
            UDP -> 8
            ICMPV6 -> 8
            else -> Int.MAX_VALUE
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private companion object {
        const val IPV6_VERSION = 6
        const val IPV6_HEADER_LENGTH = 40
        const val MAX_IPV6_PAYLOAD_LENGTH = 65_535
        const val MAX_IPV6_PACKET_LENGTH = IPV6_HEADER_LENGTH + MAX_IPV6_PAYLOAD_LENGTH
        const val ADDRESS_LENGTH = 16
        const val PAYLOAD_LENGTH_OFFSET = 4
        const val NEXT_HEADER_OFFSET = 6
        const val HOP_LIMIT_OFFSET = 7
        const val SOURCE_OFFSET = 8
        const val DESTINATION_OFFSET = 24
        const val HOP_BY_HOP = 0
        const val TCP = 6
        const val UDP = 17
        const val ROUTING = 43
        const val FRAGMENT = 44
        const val ESP = 50
        const val AUTHENTICATION = 51
        const val ICMPV6 = 58
        const val NO_NEXT_HEADER = 59
        const val DESTINATION_OPTIONS = 60
        const val PAD1 = 0
        const val UDP_LENGTH_OFFSET = 4
        const val TCP_DATA_OFFSET = 12
        const val FRAGMENT_HEADER_LENGTH = 8
        const val ESP_HEADER_LENGTH = 8
        const val MAX_EXTENSION_HEADERS = 8
        const val MAX_EXTENSION_BYTES = 256
        const val TOMBSTONE_CAPACITY_MULTIPLIER = 16
        const val ECN_NOT_ECT = 0
        const val ECN_CE = 3
    }
}
