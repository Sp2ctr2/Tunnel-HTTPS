package com.tunnelvpn.app

import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicReference

class Nat64Prefix private constructor(
    prefix: ByteArray,
    val length: Int
) {
    private val bytes = prefix.copyOf()

    fun synthesize(ipv4: ByteArray): ByteArray {
        require(ipv4.size == IPV4_BYTES)
        val result = ByteArray(IPV6_BYTES)
        val prefixBytes = length / 8
        bytes.copyInto(result, 0, 0, prefixBytes)
        if (length == 96) {
            ipv4.copyInto(result, 12)
            return result
        }
        val beforeReservedOctet = (64 - length) / 8
        ipv4.copyInto(result, prefixBytes, 0, beforeReservedOctet)
        ipv4.copyInto(result, 9, beforeReservedOctet, IPV4_BYTES)
        return result
    }

    fun addressBytes(): ByteArray = bytes.copyOf()

    fun extractIpv4(address: ByteArray): ByteArray? {
        if (address.size != IPV6_BYTES) return null
        val prefixBytes = length / 8
        if ((0 until prefixBytes).any { index -> address[index] != bytes[index] }) return null
        if (address[8].toInt() != 0) return null
        val ipv4 = ByteArray(IPV4_BYTES)
        if (length == 96) {
            address.copyInto(ipv4, 0, 12, 16)
        } else {
            val beforeReservedOctet = (64 - length) / 8
            address.copyInto(ipv4, 0, prefixBytes, prefixBytes + beforeReservedOctet)
            address.copyInto(ipv4, beforeReservedOctet, 9, 9 + IPV4_BYTES - beforeReservedOctet)
        }
        return ipv4
    }

    internal fun isWellKnown(): Boolean {
        return length == 96 && bytes.contentEquals(WELL_KNOWN_PREFIX)
    }

    override fun equals(other: Any?): Boolean {
        return other is Nat64Prefix && length == other.length && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * length + bytes.contentHashCode()

    companion object {
        private const val IPV4_BYTES = 4
        private const val IPV6_BYTES = 16
        private val VALID_LENGTHS = listOf(32, 40, 48, 56, 64, 96)

        fun from(address: ByteArray, length: Int): Nat64Prefix? {
            if (address.size != IPV6_BYTES || length !in VALID_LENGTHS) return null
            val normalized = address.copyOf()
            val prefixBytes = length / 8
            for (index in prefixBytes until normalized.size) normalized[index] = 0
            if (normalized[8].toInt() != 0) return null
            if (!isUsablePrefix(normalized)) return null
            return Nat64Prefix(normalized, length)
        }

        private fun isUsablePrefix(address: ByteArray): Boolean {
            if (address.all { it.toInt() == 0 }) return false
            if (address[0].toInt() and 0xff == 0xff) return false
            if ((address[0].toInt() and 0xff) == 0xfe && (address[1].toInt() and 0xc0) == 0x80) return false
            if ((0 until 10).all { index -> address[index].toInt() == 0 } &&
                address[10] == 0xff.toByte() && address[11] == 0xff.toByte()
            ) return false
            return true
        }

        fun discover(addresses: List<ByteArray>): Nat64Prefix? {
            return discoverAll(addresses).firstOrNull()
        }

        fun discoverAll(addresses: List<ByteArray>): List<Nat64Prefix> {
            val evidence = linkedMapOf<Nat64Prefix, MutableMap<Int, MutableSet<Int>>>()
            val candidatesByAddress = linkedMapOf<Int, LinkedHashSet<Nat64Prefix>>()
            addresses.forEachIndexed { addressIndex, address ->
                if (address.size != IPV6_BYTES || address[8].toInt() != 0) return@forEachIndexed
                extractUnambiguous(address)?.let { candidate ->
                    candidatesByAddress.getOrPut(addressIndex) { linkedSetOf() }.add(candidate)
                }
                DISCOVERY_IPV4.forEachIndexed { discoveryIndex, ipv4 ->
                    val positions = VALID_LENGTHS.filter { length ->
                        embedsAtRfc6052Position(address, length, ipv4)
                    }
                    val matches = positions.mapNotNull { length -> from(address, length) }
                    matches.forEach { candidate ->
                        evidence.getOrPut(candidate) { linkedMapOf() }
                            .getOrPut(discoveryIndex) { linkedSetOf() }
                            .add(addressIndex)
                    }
                }
            }
            val strong = evidence.filterValues { matches ->
                matches.size == DISCOVERY_IPV4.size &&
                    matches.values.fold(emptySet<Int>()) { union, indices -> union + indices }.size >= 2
            }.keys
            val result = linkedSetOf<Nat64Prefix>()
            evidence.keys.filterTo(result) { it in strong }
            val strongAddressIndices = strong.flatMapTo(linkedSetOf()) { candidate ->
                evidence.getValue(candidate).values.flatten()
            }
            candidatesByAddress.forEach { (addressIndex, candidates) ->
                if (addressIndex in strongAddressIndices) return@forEach
                if (candidates.size == 1) result.add(candidates.single())
            }
            return result.toList()
        }

        private fun extractUnambiguous(address: ByteArray): Nat64Prefix? {
            for (ipv4 in DISCOVERY_IPV4) {
                val positions = VALID_LENGTHS.filter { length ->
                    embedsAtRfc6052Position(address, length, ipv4)
                }
                if (positions.size != 1) continue
                val matches = positions.mapNotNull { length -> from(address, length) }
                if (matches.size == 1) return matches.single()
            }
            return null
        }

        private fun embedsAtRfc6052Position(address: ByteArray, length: Int, ipv4: ByteArray): Boolean {
            if (length == 96) {
                return ipv4.indices.all { index -> address[12 + index] == ipv4[index] }
            }
            val prefixBytes = length / 8
            val beforeReservedOctet = (64 - length) / 8
            return ipv4.indices.all { index ->
                val addressIndex = if (index < beforeReservedOctet) {
                    prefixBytes + index
                } else {
                    9 + index - beforeReservedOctet
                }
                address[addressIndex] == ipv4[index]
            }
        }

        private val DISCOVERY_IPV4 = listOf(
            byteArrayOf(192.toByte(), 0, 0, 170.toByte()),
            byteArrayOf(192.toByte(), 0, 0, 171.toByte())
        )
        private val WELL_KNOWN_PREFIX = byteArrayOf(
            0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        internal val WELL_KNOWN = Nat64Prefix(WELL_KNOWN_PREFIX, 96)
    }
}

class Nat64AddressTranslator {
    private data class State(
        val prefixes: List<Nat64Prefix>,
        val scopeId: Int,
        val generation: Long
    )

    private val state = AtomicReference(State(emptyList(), 0, 0L))

    fun update(value: Nat64Prefix?) {
        updateAll(listOfNotNull(value))
    }

    fun updateAll(values: List<Nat64Prefix>) {
        val normalizedPrefixes = values.distinct()
        while (true) {
            val current = state.get()
            if (current.prefixes == normalizedPrefixes) return
            if (state.compareAndSet(current, current.copy(
                    prefixes = normalizedPrefixes,
                    generation = current.generation + 1L
                ))
            ) return
        }
    }

    fun updateContext(values: List<Nat64Prefix>, scopeId: Int) {
        val normalizedPrefixes = values.distinct()
        val normalizedScopeId = scopeId.coerceAtLeast(0)
        while (true) {
            val current = state.get()
            if (current.prefixes == normalizedPrefixes && current.scopeId == normalizedScopeId) return
            if (state.compareAndSet(current, current.copy(
                    prefixes = normalizedPrefixes,
                    scopeId = normalizedScopeId,
                    generation = current.generation + 1L
                ))
            ) return
        }
    }

    fun updateScopeId(value: Int) {
        val normalizedScopeId = value.coerceAtLeast(0)
        while (true) {
            val current = state.get()
            if (current.scopeId == normalizedScopeId) return
            if (state.compareAndSet(current, current.copy(
                    scopeId = normalizedScopeId,
                    generation = current.generation + 1L
                ))
            ) return
        }
    }

    fun current(): Nat64Prefix? = state.get().prefixes.firstOrNull()

    fun currentAll(): List<Nat64Prefix> = state.get().prefixes.toList()

    fun generation(): Long = state.get().generation

    fun translate(address: InetAddress): InetAddress {
        return translateAll(address).first()
    }

    fun translateAll(address: InetAddress): List<InetAddress> {
        val snapshot = state.get()
        val raw = address.address
        if (raw.size == 4) {
            if (snapshot.prefixes.isEmpty()) return listOf(address)
            val prefixes = if (DnsMessageValidator.isPublicAddress(raw)) {
                snapshot.prefixes
            } else {
                snapshot.prefixes.filterNot(Nat64Prefix::isWellKnown)
            }
            if (prefixes.isEmpty()) return listOf(address)
            return prefixes.map { prefix -> InetAddress.getByAddress(prefix.synthesize(raw)) }
        }
        if (raw.size == 16) {
            val embedded = (snapshot.prefixes + Nat64Prefix.WELL_KNOWN)
                .distinct()
                .sortedByDescending(Nat64Prefix::length)
                .asSequence()
                .mapNotNull { prefix -> prefix.extractIpv4(raw)?.let { prefix to it } }
                .firstOrNull()
            if (embedded != null) {
                val publicIpv4 = DnsMessageValidator.isPublicAddress(embedded.second)
                if (embedded.first.isWellKnown() && !publicIpv4) return emptyList()
                val prefixes = if (publicIpv4) {
                    snapshot.prefixes
                } else {
                    snapshot.prefixes.filterNot(Nat64Prefix::isWellKnown)
                }
                if (prefixes.isNotEmpty()) {
                    return prefixes.map { prefix -> InetAddress.getByAddress(prefix.synthesize(embedded.second)) }
                        .distinctBy { candidate -> candidate.address.toList() }
                }
                return if (embedded.first.isWellKnown() && publicIpv4) listOf(address) else emptyList()
            }
        }
        if (address !is Inet6Address || address.scopeId != 0 || snapshot.scopeId == 0) {
            return listOf(address)
        }
        val scoped = address.isLinkLocalAddress || address.isMCLinkLocal || address.isMCNodeLocal ||
            address.isMCSiteLocal || address.isMCOrgLocal
        return if (scoped) {
            listOf(Inet6Address.getByAddress(null, raw, snapshot.scopeId))
        } else {
            listOf(address)
        }
    }
}

internal class Nat64RetiredPrefixes(private val maximumSize: Int = 32) {
    private val state = AtomicReference<List<Nat64Prefix>>(emptyList())

    init {
        require(maximumSize > 0)
    }

    fun transition(previous: List<Nat64Prefix>, active: List<Nat64Prefix>) {
        val normalizedActive = active.distinct()
        val removed = previous.filterNot(normalizedActive::contains).filterNot(Nat64Prefix::isWellKnown)
        while (true) {
            val current = state.get()
            val updated = (removed + current)
                .filterNot(normalizedActive::contains)
                .distinct()
                .take(maximumSize)
            if (current == updated || state.compareAndSet(current, updated)) return
        }
    }

    fun matches(address: ByteArray, active: List<Nat64Prefix> = emptyList()): Boolean {
        return address.size == 16 && active.none { prefix -> prefix.extractIpv4(address) != null } &&
            state.get().any { prefix -> prefix.extractIpv4(address) != null }
    }

    internal fun current(): List<Nat64Prefix> = state.get().toList()
}

internal class Nat64DiscoveryCache(
    private val clock: () -> Long,
    private val maximumTtlSeconds: Long = 86_400L
) {
    data class Result(
        val prefixes: List<Nat64Prefix>,
        val nextDelayMs: Long,
        val changed: Boolean
    )

    private var prefixes: List<Nat64Prefix> = emptyList()
    private var expiresAtMs = 0L
    private var retryAtMs = 0L

    @Synchronized
    fun reset(): Result {
        val changed = prefixes.isNotEmpty()
        prefixes = emptyList()
        expiresAtMs = 0L
        retryAtMs = 0L
        return Result(emptyList(), 0L, changed)
    }

    @Synchronized
    fun expire(): Result {
        val now = clock()
        val changed = prefixes.isNotEmpty() && now >= expiresAtMs
        if (changed) {
            prefixes = emptyList()
            expiresAtMs = 0L
            retryAtMs = 0L
        }
        return Result(prefixes, 0L, changed)
    }

    @Synchronized
    fun accept(values: List<Nat64Prefix>, ttlSeconds: Long, refreshAdvanceSeconds: Long): Result {
        val normalized = values.distinct()
        require(normalized.isNotEmpty())
        if (ttlSeconds <= 0L) {
            val now = clock()
            val changed = prefixes.isNotEmpty()
            prefixes = emptyList()
            expiresAtMs = 0L
            retryAtMs = now + ZERO_TTL_RETRY_DELAY_MS
            return Result(emptyList(), ZERO_TTL_RETRY_DELAY_MS, changed)
        }
        val ttl = ttlSeconds.coerceAtMost(maximumTtlSeconds)
        val advance = refreshAdvanceSeconds.coerceIn(0L, ttl - 1L)
        val changed = prefixes != normalized
        prefixes = normalized
        expiresAtMs = clock() + ttl * 1_000L
        retryAtMs = 0L
        return Result(normalized, (ttl - advance) * 1_000L, changed)
    }

    @Synchronized
    fun reject(retryDelayMs: Long): Result {
        val now = clock()
        if (prefixes.isNotEmpty() && now < expiresAtMs) {
            return Result(prefixes, minOf(retryDelayMs.coerceAtLeast(1L), expiresAtMs - now), false)
        }
        val changed = prefixes.isNotEmpty()
        prefixes = emptyList()
        expiresAtMs = 0L
        val delay = retryDelayMs.coerceAtLeast(1L)
        retryAtMs = now + delay
        return Result(emptyList(), delay, changed)
    }

    @Synchronized
    fun current(): List<Nat64Prefix> {
        return if (prefixes.isNotEmpty() && clock() < expiresAtMs) prefixes.toList() else emptyList()
    }

    @Synchronized
    fun remainingTtlSeconds(): Long? {
        if (prefixes.isEmpty()) return null
        return ((expiresAtMs - clock()).coerceAtLeast(0L) / 1_000L)
    }

    @Synchronized
    fun shouldRetry(): Boolean {
        val now = clock()
        return now >= retryAtMs && (prefixes.isEmpty() || now >= expiresAtMs)
    }

    private companion object {
        const val ZERO_TTL_RETRY_DELAY_MS = 1_000L
    }
}
