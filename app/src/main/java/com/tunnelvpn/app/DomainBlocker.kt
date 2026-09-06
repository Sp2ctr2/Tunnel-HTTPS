package com.tunnelvpn.app

class DomainBlocker(
    private val enabled: Boolean,
    blockedExact: Set<String> = emptySet(),
    blockedSuffixes: Set<String> = emptySet(),
    allowlist: Set<String> = emptySet(),
    extraSuffixes: Set<String> = emptySet()
) {
    private val exactBlocks = blockedExact.mapNotNullTo(hashSetOf(), ::normalize)
    private val suffixBlocks = (blockedSuffixes + extraSuffixes).mapNotNullTo(hashSetOf(), ::normalize)
    private val allowed = allowlist.mapNotNullTo(hashSetOf(), ::normalize)
    private val decisions = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 4096
    }

    @Synchronized
    fun shouldBlock(domain: String): Boolean {
        if (!enabled) return false
        val normalized = normalize(domain) ?: return false
        return decisions.getOrPut(normalized) {
            normalized in exactBlocks || (!matches(normalized, allowed) && matches(normalized, suffixBlocks))
        }
    }

    private fun matches(domain: String, values: Set<String>): Boolean {
        if (domain in values) return true
        var offset = domain.indexOf('.')
        while (offset >= 0 && offset + 1 < domain.length) {
            if (domain.substring(offset + 1) in values) return true
            offset = domain.indexOf('.', offset + 1)
        }
        return false
    }

    companion object {
        fun normalize(raw: String): String? {
            val value = raw.lowercase()
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .trim()
                .trimEnd('.')
                .removePrefix("*.")
            if (value.length !in 1..253 || !value.contains('.')) return null
            if (!value.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return null
            if (value.split('.').any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }) return null
            return value
        }

        fun parseSuffixList(lines: Sequence<String>): Set<String> {
            val result = hashSetOf<String>()
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val token = line.split(Regex("\\s+")).lastOrNull() ?: continue
                normalize(token)?.let(result::add)
            }
            return result
        }
    }
}
