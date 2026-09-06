package com.tunnelvpn.app

import java.net.InetAddress
import java.util.LinkedHashMap

class TurboDomainMapper(
    private val virtualBase: Int = ipv4ToInt(byteArrayOf(192.toByte(), 0, 2, 0)),
    private val firstHost: Int = 2,
    private val maxHosts: Int = 220,
    private val ttlMs: Long = 120_000L,
    private val reuseQuarantineMs: Long = ttlMs,
    private val networkGenerationProvider: (() -> Long)? = null
) {
    internal class MappingLease internal constructor(
        val networkGeneration: Long,
        internal val epoch: Long
    )

    data class Mapping(
        val domain: String,
        val virtualAddress: ByteArray,
        val realAddress: ByteArray,
        val realAddresses: List<ByteArray> = listOf(realAddress.copyOf()),
        val expiresAtMs: Long,
        val networkGeneration: Long = 0L
    ) {
        val virtualAddressString: String = ipv4ToString(virtualAddress)
        val realInetAddress: InetAddress = InetAddress.getByAddress(realAddresses.firstOrNull() ?: realAddress)
        val realInetAddresses: List<InetAddress> = realAddresses.map { InetAddress.getByAddress(it) }
    }

    private val byDomain = LinkedHashMap<String, Mapping>(256, 0.75f, true)
    private val byVirtual = LinkedHashMap<Int, Mapping>(256, 0.75f, true)
    private val suppressedUntil = LinkedHashMap<String, Long>(64, 0.75f, true)
    private val retiredVirtualUntil = LinkedHashMap<Int, Long>(256, 0.75f, true)
    private var nextHost = firstHost
    private var networkGeneration = 0L
    private var mappingEpoch = 0L

    fun map(
        domain: String,
        realAddress: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
        expectedNetworkGeneration: Long = networkGeneration
    ): Mapping? {
        return map(domain, listOf(realAddress), nowMs, expectedNetworkGeneration)
    }

    fun map(
        domain: String,
        realAddresses: List<ByteArray>,
        nowMs: Long = System.currentTimeMillis(),
        expectedNetworkGeneration: Long = networkGeneration
    ): Mapping? {
        val lease = acquireLease(expectedNetworkGeneration) ?: return null
        return map(domain, realAddresses, nowMs, lease)
    }

    internal fun map(
        domain: String,
        realAddresses: List<ByteArray>,
        nowMs: Long = System.currentTimeMillis(),
        lease: MappingLease
    ): Mapping? {
        val candidates = realAddresses
            .filter { it.size == 4 }
            .distinctBy { ipv4ToInt(it) }
            .map { it.copyOf() }
        if (candidates.isEmpty()) return null
        val normalized = DomainBlocker.normalize(domain) ?: return null
        return commit(normalized, candidates, nowMs, lease)
    }

    @Synchronized
    internal fun acquireLease(expectedNetworkGeneration: Long): MappingLease? {
        if (!isCurrentGeneration(expectedNetworkGeneration)) return null
        return MappingLease(networkGeneration, mappingEpoch)
    }

    @Synchronized
    private fun commit(
        normalized: String,
        candidates: List<ByteArray>,
        nowMs: Long,
        lease: MappingLease
    ): Mapping? {
        if (!isCurrentLease(lease)) return null
        cleanup(nowMs)
        if ((suppressedUntil[normalized] ?: 0L) > nowMs) return null
        byDomain[normalized]?.let { existing ->
            val refreshed = existing.copy(
                realAddress = candidates.first().copyOf(),
                realAddresses = candidates,
                expiresAtMs = nowMs + ttlMs,
                networkGeneration = networkGeneration
            )
            if (!isCurrentLease(lease)) return null
            byDomain[normalized] = refreshed
            byVirtual[ipv4ToInt(refreshed.virtualAddress)] = refreshed
            return refreshed
        }

        val host = allocateHost(nowMs) ?: return null
        val virtual = intToIpv4(virtualBase + host)
        val mapping = Mapping(
            domain = normalized,
            virtualAddress = virtual,
            realAddress = candidates.first().copyOf(),
            realAddresses = candidates,
            expiresAtMs = nowMs + ttlMs,
            networkGeneration = networkGeneration
        )
        val virtualKey = ipv4ToInt(virtual)
        if (!isCurrentLease(lease)) return null
        byVirtual[virtualKey]?.let { displaced ->
            if (displaced.domain != normalized && byDomain[displaced.domain] == displaced) {
                byDomain.remove(displaced.domain)
            }
        }
        byDomain[normalized] = mapping
        byVirtual[virtualKey] = mapping
        trim(nowMs)
        return mapping
    }

    @Synchronized
    fun lookupVirtual(address: ByteArray, nowMs: Long = System.currentTimeMillis()): Mapping? {
        if (address.size != 4) return null
        if (!isCurrentGeneration(networkGeneration)) return null
        cleanup(nowMs)
        val mapping = byVirtual[ipv4ToInt(address)]?.takeIf { it.networkGeneration == networkGeneration }
        return mapping?.takeIf { isCurrentGeneration(it.networkGeneration) }
    }

    @Synchronized
    fun updateNetworkGeneration(value: Long, nowMs: Long = System.currentTimeMillis()) {
        if (networkGeneration == value) return
        mappingEpoch += 1L
        byVirtual.keys.forEach { key -> quarantine(key, nowMs) }
        networkGeneration = value
        byDomain.clear()
        byVirtual.clear()
        suppressedUntil.clear()
        retiredVirtualUntil.entries.removeIf { it.value <= nowMs }
    }

    @Synchronized
    fun isVirtualAddress(address: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        return lookupVirtual(address, nowMs) != null
    }

    @Synchronized
    fun suppress(domain: String, durationMs: Long = 90_000L, nowMs: Long = System.currentTimeMillis()) {
        val normalized = DomainBlocker.normalize(domain) ?: return
        suppressedUntil[normalized] = nowMs + durationMs
        byDomain.remove(normalized)?.let { mapping ->
            retireVirtualIfCurrent(mapping, nowMs)
        }
    }

    @Synchronized
    private fun cleanup(nowMs: Long) {
        val expired = byDomain.values.filter { it.expiresAtMs <= nowMs }
        expired.forEach { mapping ->
            byDomain.remove(mapping.domain)
            retireVirtualIfCurrent(mapping, mapping.expiresAtMs)
        }
        suppressedUntil.entries.removeIf { it.value <= nowMs }
        retiredVirtualUntil.entries.removeIf { it.value <= nowMs }
    }

    private fun trim(nowMs: Long) {
        while (byDomain.size > maxHosts) {
            val eldest = byDomain.entries.iterator().next().value
            byDomain.remove(eldest.domain)
            retireVirtualIfCurrent(eldest, nowMs)
        }
    }

    private fun allocateHost(nowMs: Long): Int? {
        retiredVirtualUntil.entries.removeIf { it.value <= nowMs }
        repeat(maxHosts) {
            val host = nextHost
            nextHost += 1
            if (nextHost >= firstHost + maxHosts) nextHost = firstHost
            val key = virtualBase + host
            if (!byVirtual.containsKey(key) && !retiredVirtualUntil.containsKey(key)) return host
        }
        return null
    }

    private fun retireVirtualIfCurrent(mapping: Mapping, retiredAtMs: Long) {
        val virtualKey = ipv4ToInt(mapping.virtualAddress)
        if (byVirtual[virtualKey] == mapping) {
            byVirtual.remove(virtualKey)
            quarantine(virtualKey, retiredAtMs)
        }
    }

    private fun quarantine(virtualKey: Int, retiredAtMs: Long) {
        val until = if (reuseQuarantineMs <= 0L) {
            retiredAtMs
        } else if (retiredAtMs > Long.MAX_VALUE - reuseQuarantineMs) {
            Long.MAX_VALUE
        } else {
            retiredAtMs + reuseQuarantineMs
        }
        retiredVirtualUntil[virtualKey] = maxOf(retiredVirtualUntil[virtualKey] ?: Long.MIN_VALUE, until)
    }

    private fun isCurrentLease(lease: MappingLease): Boolean {
        return lease.epoch == mappingEpoch && isCurrentGeneration(lease.networkGeneration)
    }

    private fun isCurrentGeneration(expected: Long): Boolean {
        if (expected != networkGeneration || expected and 1L != 0L) return false
        return networkGenerationProvider?.invoke()?.let { it == expected && it and 1L == 0L } ?: true
    }

    companion object {
        fun ipv4ToString(address: ByteArray): String {
            return address.joinToString(".") { (it.toInt() and 0xff).toString() }
        }

        private fun intToIpv4(value: Int): ByteArray {
            return byteArrayOf(
                ((value ushr 24) and 0xff).toByte(),
                ((value ushr 16) and 0xff).toByte(),
                ((value ushr 8) and 0xff).toByte(),
                (value and 0xff).toByte()
            )
        }

        private fun ipv4ToInt(address: ByteArray): Int {
            return ((address[0].toInt() and 0xff) shl 24) or
                ((address[1].toInt() and 0xff) shl 16) or
                ((address[2].toInt() and 0xff) shl 8) or
                (address[3].toInt() and 0xff)
        }
    }
}
