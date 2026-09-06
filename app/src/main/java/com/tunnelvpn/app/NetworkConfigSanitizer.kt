package com.tunnelvpn.app

data class SanitizedDomains(
    val valid: List<String>,
    val invalidCount: Int
)

class NetworkConfigLimitException(message: String) : IllegalArgumentException(message)

internal const val MAX_SPLIT_DOMAIN_COUNT = 220
internal const val MAX_BYPASS_PACKAGE_COUNT = 200
private const val MAX_SPLIT_DOMAIN_BYTES = 32 * 1024
private const val MAX_BYPASS_PACKAGE_BYTES = 32 * 1024
private const val MAX_BRIDGE_TEXT_CHARS = 64 * 1024
private const val MAX_BRIDGE_TEXT_BYTES = 64 * 1024

private val DOMAIN_LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

fun sanitizeSplitDomains(values: Iterable<String>): SanitizedDomains {
    val seen = linkedSetOf<String>()
    var invalid = 0
    values.forEach { raw ->
        val item = raw.trim()
        if (item.isEmpty()) return@forEach
        val domain = normalizeDomain(item)
        if (domain == null) {
            invalid += 1
        } else {
            seen += domain
        }
    }
    return SanitizedDomains(seen.toList(), invalid)
}

fun sanitizePackageNames(values: Iterable<String>, servicePackage: String? = null): List<String> {
    return values
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != servicePackage && PACKAGE_NAME.matches(it) }
        .distinct()
}

fun validateSplitDomainInput(values: Collection<String>) {
    validateInputLimits(values, MAX_SPLIT_DOMAIN_COUNT, MAX_SPLIT_DOMAIN_BYTES, "split domains")
}

fun validateBypassPackageInput(values: Collection<String>) {
    validateInputLimits(values, MAX_BYPASS_PACKAGE_COUNT, MAX_BYPASS_PACKAGE_BYTES, "bypass packages")
}

fun validateBridgeConfigText(value: String, fieldName: String) {
    if (value.length > MAX_BRIDGE_TEXT_CHARS) {
        throw NetworkConfigLimitException("$fieldName exceeds the character limit")
    }
    if (value.toByteArray(Charsets.UTF_8).size > MAX_BRIDGE_TEXT_BYTES) {
        throw NetworkConfigLimitException("$fieldName exceeds the encoded size limit")
    }
}

fun sanitizeTunMtu(value: Int): Int = value.coerceIn(MIN_TUN_MTU, MAX_TUN_MTU)

private fun validateInputLimits(
    values: Collection<String>,
    maxCount: Int,
    maxBytes: Int,
    fieldName: String
) {
    val nonEmptyValues = values.filter { it.isNotBlank() }
    if (nonEmptyValues.size > maxCount) {
        throw NetworkConfigLimitException("$fieldName exceeds the item limit")
    }
    var encodedBytes = 0
    nonEmptyValues.forEach { value ->
        encodedBytes += value.toByteArray(Charsets.UTF_8).size
        if (encodedBytes > maxBytes) {
            throw NetworkConfigLimitException("$fieldName exceeds the encoded size limit")
        }
    }
}

private const val MIN_TUN_MTU = 1280
private const val MAX_TUN_MTU = 32768

private fun normalizeDomain(raw: String): String? {
    var value = raw.lowercase()
        .replace(Regex("^[a-z][a-z0-9+.-]*://"), "")
        .substringBefore("/")
        .substringBefore("?")
        .substringBefore("#")
        .trim()
        .trimEnd('.')
        .removePrefix("*.")

    if (value.contains("@")) value = value.substringAfterLast("@")
    if (value.count { it == ':' } == 1) value = value.substringBefore(":")
    if (value.length !in 1..253 || !value.contains(".")) return null
    val labels = value.split(".")
    if (labels.any { it.isEmpty() || !DOMAIN_LABEL.matches(it) }) return null
    return value
}
