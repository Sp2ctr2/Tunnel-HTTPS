package com.tunnelvpn.app

class DnsCache(
    private val maxEntries: Int = 512,
    private val maxCachedBytes: Int = 2 * 1024 * 1024,
    private val maxResponseBytes: Int = 16 * 1024,
    private val successTtlMs: Long = 60_000L,
    private val failureTtlMs: Long = 5_000L,
    private val clock: () -> Long = MonotonicClock::elapsedRealtimeMs
) {
    private data class Entry(
        val responseTemplate: ByteArray?,
        val cachedAtMs: Long,
        val expiresAtMs: Long,
        val failed: Boolean,
        val sizeBytes: Int
    )

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)
    private var cachedBytes = 0

    init {
        require(maxEntries > 0)
        require(maxCachedBytes >= 0)
        require(maxResponseBytes >= 0)
    }

    @Synchronized
    fun get(key: String, transactionId: Int): ByteArray? {
        return getInternal(key, transactionId, null)
    }

    @Synchronized
    fun get(key: String, query: ByteArray): ByteArray? {
        val question = DnsPacket.parseQuestion(query) ?: return null
        return getInternal(key, question.transactionId, query)
    }

    private fun getInternal(key: String, transactionId: Int, query: ByteArray?): ByteArray? {
        val entry = entries[key] ?: return null
        val now = clock()
        if (entry.expiresAtMs <= now) {
            remove(key)
            return null
        }
        if (entry.failed) return null
        val template = entry.responseTemplate ?: return null
        val elapsedSeconds = ((now - entry.cachedAtMs).coerceAtLeast(0L) / 1_000L)
        val remainingSeconds = ((entry.expiresAtMs - now).coerceAtLeast(0L) / 1_000L)
        val aged = DnsPacket.ageResponseTtls(template, elapsedSeconds, remainingSeconds) ?: run {
            remove(key)
            return null
        }
        val identified = DnsPacket.withTransactionId(aged, transactionId)
        return if (query == null) identified else DnsPacket.restoreOptForQuery(identified, query)
    }

    @Synchronized
    fun isFailureCached(key: String): Boolean {
        val entry = entries[key] ?: return false
        if (entry.expiresAtMs <= clock()) {
            remove(key)
            return false
        }
        return entry.failed
    }

    @Synchronized
    fun putSuccess(key: String, response: ByteArray) {
        putSuccess(key, response, successTtlMs)
    }

    @Synchronized
    fun putSuccess(key: String, response: ByteArray, ttlMs: Long) {
        if (ttlMs <= 0L) {
            remove(key)
            return
        }
        if (response.size > maxResponseBytes || response.size > maxCachedBytes) {
            remove(key)
            return
        }
        val template = DnsPacket.stripOptForCache(response) ?: run {
            remove(key)
            return
        }
        val boundedTtl = ttlMs.coerceAtMost(MAX_TTL_MS)
        val now = clock()
        put(key, Entry(DnsPacket.withTransactionId(template, 0), now, now + boundedTtl, false, template.size))
    }

    @Synchronized
    fun putFailure(key: String) {
        val now = clock()
        put(key, Entry(null, now, now + failureTtlMs, failed = true, sizeBytes = 0))
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun cachedBytes(): Int = cachedBytes
    @Synchronized
    fun clear() {
        entries.clear()
        cachedBytes = 0
    }

    private fun put(key: String, entry: Entry) {
        remove(key)
        entries[key] = entry
        cachedBytes += entry.sizeBytes
        while (entries.size > maxEntries || cachedBytes > maxCachedBytes) {
            val eldest = entries.entries.iterator().next()
            cachedBytes -= eldest.value.sizeBytes
            entries.remove(eldest.key)
        }
    }

    private fun remove(key: String) {
        cachedBytes -= entries.remove(key)?.sizeBytes ?: 0
    }

    companion object {
        private const val MAX_TTL_MS = 86_400_000L
    }
}
