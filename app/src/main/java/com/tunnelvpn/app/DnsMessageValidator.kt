package com.tunnelvpn.app

import java.io.IOException
import java.net.InetAddress

enum class DnsSecurityMeaning {
    ACCEPTABLE,
    NXDOMAIN,
    ERROR,
    BLOCKED,
    FILTERED,
    CENSORED,
    FORGED,
    DNSSEC_BOGUS,
    REBINDING
}

data class ValidatedDnsMessage(
    val bytes: ByteArray,
    val rcode: Int,
    val authenticatedData: Boolean,
    val meaning: DnsSecurityMeaning,
    val minimumTtlSeconds: Long,
    val addressFingerprints: Set<String>,
    val cnameDepth: Int,
    val containsIpv6Address: Boolean = false,
    val hasRequestedAnswer: Boolean = false
)

object DnsMessageValidator {
    private const val HEADER_SIZE = 12
    private const val MAX_MESSAGE_SIZE = 65_535
    private const val MAX_RECORDS = 256
    private const val MAX_ADDRESS_VALUES = 256
    private const val MAX_POINTER_JUMPS = 32
    private const val MAX_CNAME_DEPTH = 16
    private const val TYPE_CNAME = 5
    private const val TYPE_SOA = 6
    private const val TYPE_PTR = 12
    private const val TYPE_DNAME = 39
    private const val TYPE_OPT = 41
    private const val TYPE_SVCB = 64
    private const val TYPE_HTTPS = 65
    private const val TYPE_ANY = 255
    private const val OPTION_EDE = 15
    private const val SVC_PARAM_IPV4_HINT = 4
    private const val SVC_PARAM_IPV6_HINT = 6

    internal fun isSecurityHardStop(meaning: DnsSecurityMeaning): Boolean {
        return meaning == DnsSecurityMeaning.BLOCKED ||
            meaning == DnsSecurityMeaning.CENSORED ||
            meaning == DnsSecurityMeaning.FILTERED ||
            meaning == DnsSecurityMeaning.FORGED ||
            meaning == DnsSecurityMeaning.DNSSEC_BOGUS ||
            meaning == DnsSecurityMeaning.REBINDING
    }

    fun validate(
        query: ByteArray,
        response: ByteArray,
        publicQuery: Boolean = true,
        allowedLocalAddress: (ByteArray) -> Boolean = { false },
        forbiddenAddress: (ByteArray) -> Boolean = { false }
    ): ValidatedDnsMessage {
        if (query.size !in HEADER_SIZE..MAX_MESSAGE_SIZE || response.size !in HEADER_SIZE..MAX_MESSAGE_SIZE) {
            throw IOException("invalid-dns-size")
        }
        DnsPacket.parseQuestion(query) ?: throw IOException("invalid-dns-query")
        if (u16(query, 0) != u16(response, 0)) throw IOException("transaction-mismatch")
        val queryFlags = u16(query, 2)
        val responseFlags = u16(response, 2)
        if (responseFlags and 0x8000 == 0) throw IOException("not-a-response")
        if ((responseFlags and 0x7800) != (queryFlags and 0x7800)) throw IOException("opcode-mismatch")
        if (responseFlags and 0x0200 != 0) throw IOException("truncated-response")
        if (responseFlags and 0x0040 != 0) throw IOException("reserved-flag-set")
        if (u16(query, 4) != 1) throw IOException("question-count-invalid")
        val answerCount = u16(response, 6)
        val authorityCount = u16(response, 8)
        val additionalCount = u16(response, 10)
        if (u16(response, 4) == 0) {
            val rcode = responseFlags and 0x000f
            if (rcode == 0 || answerCount != 0 || authorityCount != 0 || additionalCount != 0 ||
                response.size != HEADER_SIZE
            ) throw IOException("questionless-response-invalid")
            return ValidatedDnsMessage(
                response,
                rcode,
                responseFlags and 0x0020 != 0,
                if (rcode == 3) DnsSecurityMeaning.NXDOMAIN else DnsSecurityMeaning.ERROR,
                0L,
                emptySet(),
                0,
                false,
                false
            )
        }
        if (u16(response, 4) != 1) throw IOException("question-count-invalid")
        val queryQuestion = readQuestion(query)
        val responseQuestion = readQuestion(response)
        if (queryQuestion.name != responseQuestion.name ||
            queryQuestion.type != responseQuestion.type ||
            queryQuestion.qClass != responseQuestion.qClass
        ) {
            throw IOException("question-mismatch")
        }
        val totalRecords = answerCount.toLong() + authorityCount + additionalCount
        if (totalRecords > MAX_RECORDS) throw IOException("record-count-exceeded")
        var offset = responseQuestion.end
        var minimumTtl = Long.MAX_VALUE
        var optCount = 0
        var extendedRcode = 0
        val requestedOwners = linkedSetOf<String>()
        val cnameTargets = linkedMapOf<String, MutableSet<String>>()
        val dnameTargets = linkedMapOf<String, MutableSet<String>>()
        val authoritySoas = mutableListOf<SoaCandidate>()
        var forbiddenAliasTarget = false
        val addresses = linkedSetOf<String>()
        val rawAddresses = mutableListOf<ByteArray>()
        val edeCodes = linkedSetOf<Int>()
        repeat(totalRecords.toInt()) { recordIndex ->
            val owner = readName(response, offset)
            offset = owner.end
            if (offset + 10 > response.size) throw IOException("record-header-truncated")
            val type = u16(response, offset)
            val recordClass = u16(response, offset + 2)
            val ttl = u32(response, offset + 4)
            val rdLength = u16(response, offset + 8)
            val rdataStart = offset + 10
            val rdataEnd = rdataStart + rdLength
            if (rdataEnd < rdataStart || rdataEnd > response.size) throw IOException("rdata-out-of-bounds")
            if (type != TYPE_OPT) minimumTtl = minOf(minimumTtl, ttl)
            if (recordIndex < answerCount && recordClass == queryQuestion.qClass &&
                (type == queryQuestion.type || queryQuestion.type == TYPE_ANY)
            ) {
                requestedOwners += owner.value
            }
            when (type) {
                DnsPacket.TYPE_A -> {
                    if (recordClass != 1 || rdLength != 4) throw IOException("a-record-invalid")
                    val address = response.copyOfRange(rdataStart, rdataEnd)
                    addAddress(address, rawAddresses, addresses)
                }
                DnsPacket.TYPE_AAAA -> {
                    if (recordClass != 1 || rdLength != 16) throw IOException("aaaa-record-invalid")
                    val address = response.copyOfRange(rdataStart, rdataEnd)
                    addAddress(address, rawAddresses, addresses)
                }
                TYPE_CNAME -> {
                    if (recordClass != 1) throw IOException("cname-class-invalid")
                    val cname = readName(response, rdataStart)
                    if (cname.end != rdataEnd) throw IOException("cname-out-of-bounds")
                    if (recordIndex < answerCount) {
                        cnameTargets.getOrPut(owner.value) { linkedSetOf() }.add(cname.value)
                        forbiddenAliasTarget = forbiddenAliasTarget ||
                            publicQuery && cname.domain.isNotEmpty() && DnsPacket.isLocalOnlyName(cname.domain)
                    }
                }
                TYPE_PTR -> {
                    if (recordClass != 1) throw IOException("ptr-class-invalid")
                    val target = readName(response, rdataStart)
                    if (target.end != rdataEnd) throw IOException("ptr-rdata-invalid")
                }
                TYPE_DNAME -> {
                    if (recordClass != 1 || uncompressedNameEnd(response, rdataStart, rdataEnd) != rdataEnd) {
                        throw IOException("dname-rdata-invalid")
                    }
                    if (recordIndex < answerCount) {
                        val target = readName(response, rdataStart)
                        dnameTargets.getOrPut(owner.value) { linkedSetOf() }.add(target.value)
                        forbiddenAliasTarget = forbiddenAliasTarget ||
                            publicQuery && target.domain.isNotEmpty() && DnsPacket.isLocalOnlyName(target.domain)
                    }
                }
                TYPE_SOA -> {
                    if (recordClass != 1) throw IOException("soa-class-invalid")
                    val primary = readName(response, rdataStart)
                    val mailbox = readName(response, primary.end)
                    if (mailbox.end + SOA_INTEGER_BYTES != rdataEnd) throw IOException("soa-rdata-invalid")
                    val soaMinimum = u32(response, mailbox.end + SOA_MINIMUM_OFFSET)
                    if (recordIndex in answerCount until answerCount + authorityCount) {
                        authoritySoas += SoaCandidate(owner.value, minOf(ttl, soaMinimum))
                    }
                }
                TYPE_SVCB, TYPE_HTTPS -> {
                    if (recordClass != 1) throw IOException("svcb-class-invalid")
                    val target = parseSvcb(response, rdataStart, rdataEnd, rawAddresses, addresses)
                    forbiddenAliasTarget = forbiddenAliasTarget ||
                        publicQuery && target.isNotEmpty() && DnsPacket.isLocalOnlyName(target)
                }
                TYPE_OPT -> {
                    if (recordIndex < answerCount + authorityCount || owner.value.isNotEmpty()) {
                        throw IOException("opt-section-invalid")
                    }
                    optCount += 1
                    if (optCount > 1) throw IOException("duplicate-opt")
                    if (ttl and EDNS_FORBIDDEN_RESPONSE_TTL_BITS != 0L) throw IOException("opt-ttl-invalid")
                    extendedRcode = ((ttl ushr 24) and 0xffL).toInt()
                    parseOpt(response, rdataStart, rdataEnd, edeCodes)
                }
            }
            offset = rdataEnd
        }
        if (offset != response.size) throw IOException("unexpected-trailing-data")
        if (cnameTargets.values.any { it.size != 1 } || dnameTargets.values.any { it.size != 1 }) {
            throw IOException("alias-target-conflict")
        }
        val aliasResolution = resolveAlias(
            queryQuestion.name,
            requestedOwners,
            cnameTargets.mapValues { it.value.single() },
            dnameTargets.mapValues { it.value.single() }
        )
        forbiddenAliasTarget = forbiddenAliasTarget || publicQuery &&
            DnsPacket.isLocalOnlyName(canonicalDomain(aliasResolution.terminal))
        val hasRequestedAnswer = aliasResolution.hasRequestedAnswer
        val negativeSoaTtl = authoritySoas
            .filter { isAncestorName(it.owner, aliasResolution.terminal) }
            .groupBy { nameLabels(it.owner).size }
            .maxByOrNull { it.key }
            ?.value
            ?.minOf(SoaCandidate::ttl)
            ?: Long.MAX_VALUE
        val rcode = (extendedRcode shl 4) or (responseFlags and 0x000f)
        val discoveryWkaAllowed = DnsPacket.parseQuestion(query)?.let { question ->
            val localAddresses = rawAddresses.filterNot(::isPublicAddress)
            question.domain == IPV4_ONLY_ARPA && localAddresses.isNotEmpty() && when (question.type) {
                DnsPacket.TYPE_A -> localAddresses.all(::isIpv4OnlyWka)
                DnsPacket.TYPE_AAAA -> {
                    val ipv6Addresses = localAddresses.filter { it.size == 16 }
                    val prefixes = Nat64Prefix.discoverAll(ipv6Addresses)
                    ipv6Addresses.size == localAddresses.size && prefixes.isNotEmpty() &&
                        ipv6Addresses.all { address ->
                            prefixes.any { prefix ->
                                DISCOVERY_IPV4.any { ipv4 -> prefix.synthesize(ipv4).contentEquals(address) }
                            }
                        }
                }
                else -> false
            }
        } == true
        val localAddressException = rawAddresses.filterNot(::isPublicAddress).let { local ->
            local.isNotEmpty() && local.all(allowedLocalAddress)
        }
        val meaning = meaning(
            rcode,
            edeCodes,
            addresses,
            publicQuery,
            discoveryWkaAllowed || localAddressException,
            forbiddenAliasTarget || rawAddresses.any(forbiddenAddress)
        )
        val negativeResponse = rcode == 3 || rcode == 0 && !hasRequestedAnswer
        val cacheTtl = if (negativeResponse) {
            if (negativeSoaTtl == Long.MAX_VALUE) Long.MAX_VALUE else minOf(negativeSoaTtl, minimumTtl)
        } else {
            minimumTtl
        }
        return ValidatedDnsMessage(
            bytes = response,
            rcode = rcode,
            authenticatedData = responseFlags and 0x0020 != 0,
            meaning = meaning,
            minimumTtlSeconds = if (cacheTtl == Long.MAX_VALUE) 0L else cacheTtl,
            addressFingerprints = addresses,
            cnameDepth = aliasResolution.depth,
            containsIpv6Address = rawAddresses.any { it.size == 16 },
            hasRequestedAnswer = hasRequestedAnswer
        )
    }

    private data class Question(val name: String, val type: Int, val qClass: Int, val end: Int)
    private data class Name(val value: String, val end: Int, val domain: String)
    private data class SoaCandidate(val owner: String, val ttl: Long)
    private data class AliasResolution(
        val terminal: String,
        val hasRequestedAnswer: Boolean,
        val depth: Int
    )

    private fun resolveAlias(
        queryName: String,
        requestedOwners: Set<String>,
        cnameTargets: Map<String, String>,
        dnameTargets: Map<String, String>
    ): AliasResolution {
        val visited = linkedSetOf<String>()
        var current = queryName
        var depth = 0
        repeat(MAX_CNAME_DEPTH + 1) {
            if (!visited.add(current)) throw IOException("alias-loop")
            if (current in requestedOwners) return AliasResolution(current, true, depth)
            val cname = cnameTargets[current]
            if (cname != null) {
                current = cname
                depth += 1
                return@repeat
            }
            val currentLabels = nameLabels(current)
            val dname = dnameTargets.entries
                .filter { (owner) ->
                    val ownerLabels = nameLabels(owner)
                    currentLabels.size > ownerLabels.size && currentLabels.takeLast(ownerLabels.size) == ownerLabels
                }
                .maxByOrNull { (owner) -> nameLabels(owner).size }
            if (dname == null) return AliasResolution(current, false, depth)
            val ownerLabels = nameLabels(dname.key)
            val substituted = currentLabels.dropLast(ownerLabels.size) + nameLabels(dname.value)
            if (wireNameLength(substituted) > 255) throw IOException("dname-substitution-too-long")
            current = substituted.joinToString(".")
            depth += 1
        }
        throw IOException("alias-depth-exceeded")
    }

    private fun isAncestorName(ancestor: String, name: String): Boolean {
        val ancestorLabels = nameLabels(ancestor)
        val nameLabels = nameLabels(name)
        return nameLabels.size >= ancestorLabels.size && nameLabels.takeLast(ancestorLabels.size) == ancestorLabels
    }

    private fun nameLabels(name: String): List<String> = if (name.isEmpty()) emptyList() else name.split('.')

    private fun canonicalDomain(name: String): String {
        return nameLabels(name).joinToString(".") { label ->
            val separator = label.indexOf(':')
            if (separator <= 0) throw IOException("canonical-name-invalid")
            val expectedLength = label.substring(0, separator).toIntOrNull()
                ?: throw IOException("canonical-name-invalid")
            val hex = label.substring(separator + 1)
            if (hex.length != expectedLength * 2) throw IOException("canonical-name-invalid")
            val bytes = ByteArray(expectedLength) { index ->
                val high = hex[index * 2].digitToIntOrNull(16) ?: throw IOException("canonical-name-invalid")
                val low = hex[index * 2 + 1].digitToIntOrNull(16) ?: throw IOException("canonical-name-invalid")
                ((high shl 4) or low).toByte()
            }
            String(bytes, Charsets.US_ASCII).lowercase()
        }
    }

    private fun wireNameLength(labels: List<String>): Int {
        return 1 + labels.sumOf { label ->
            val separator = label.indexOf(':')
            if (separator <= 0) throw IOException("canonical-name-invalid")
            1 + (label.substring(0, separator).toIntOrNull() ?: throw IOException("canonical-name-invalid"))
        }
    }

    private fun readQuestion(message: ByteArray): Question {
        val name = readName(message, HEADER_SIZE)
        if (name.end + 4 > message.size) throw IOException("question-truncated")
        return Question(name.value, u16(message, name.end), u16(message, name.end + 2), name.end + 4)
    }

    private fun readName(message: ByteArray, start: Int): Name {
        if (start !in message.indices) throw IOException("name-out-of-bounds")
        val labels = ArrayList<String>(4)
        val domainLabels = ArrayList<String>(4)
        val visited = HashSet<Int>()
        var cursor = start
        var consumedEnd = -1
        var jumps = 0
        var totalLength = 0
        while (true) {
            if (cursor !in message.indices) throw IOException("name-out-of-bounds")
            if (!visited.add(cursor)) throw IOException("pointer-loop")
            val length = message[cursor].toInt() and 0xff
            when {
                length == 0 -> {
                    if (consumedEnd < 0) consumedEnd = cursor + 1
                    break
                }
                length and 0xc0 == 0xc0 -> {
                    if (cursor + 1 >= message.size) throw IOException("pointer-truncated")
                    val pointer = ((length and 0x3f) shl 8) or (message[cursor + 1].toInt() and 0xff)
                    if (pointer >= message.size) throw IOException("pointer-out-of-bounds")
                    if (consumedEnd < 0) consumedEnd = cursor + 2
                    cursor = pointer
                    jumps += 1
                    if (jumps > MAX_POINTER_JUMPS) throw IOException("pointer-depth-exceeded")
                }
                length and 0xc0 != 0 -> throw IOException("label-type-invalid")
                length > 63 -> throw IOException("label-too-long")
                else -> {
                    val next = cursor + 1 + length
                    if (next > message.size) throw IOException("label-truncated")
                    val labelBytes = message.copyOfRange(cursor + 1, next)
                    if (labelBytes.any { (it.toInt() and 0xff) !in 0x21..0x7e }) throw IOException("label-character-invalid")
                    val label = String(labelBytes, Charsets.US_ASCII).lowercase()
                    totalLength += label.length + if (labels.isEmpty()) 0 else 1
                    if (totalLength > 253) throw IOException("domain-too-long")
                    labels += "$length:${labelBytes.toCanonicalHex()}"
                    domainLabels += label
                    cursor = next
                }
            }
        }
        return Name(labels.joinToString("."), consumedEnd, domainLabels.joinToString("."))
    }

    private fun parseOpt(message: ByteArray, start: Int, end: Int, edeCodes: MutableSet<Int>) {
        var offset = start
        while (offset < end) {
            if (offset + 4 > end) throw IOException("opt-truncated")
            val code = u16(message, offset)
            val length = u16(message, offset + 2)
            val dataStart = offset + 4
            val dataEnd = dataStart + length
            if (dataEnd > end) throw IOException("opt-length-invalid")
            if (code == OPTION_EDE) {
                if (length < 2) throw IOException("ede-truncated")
                edeCodes += u16(message, dataStart)
            }
            offset = dataEnd
        }
    }

    private fun parseSvcb(
        message: ByteArray,
        start: Int,
        end: Int,
        rawAddresses: MutableList<ByteArray>,
        addresses: MutableSet<String>
    ): String {
        if (start + 3 > end) throw IOException("svcb-rdata-truncated")
        val priority = u16(message, start)
        val targetStart = start + 2
        var offset = uncompressedNameEnd(message, targetStart, end)
        val target = readName(message, targetStart)
        if (target.end != offset) throw IOException("svcb-target-invalid")
        if (priority == 0 && offset != end) throw IOException("svcb-alias-parameters")
        var previousKey = -1
        while (offset < end) {
            if (offset + 4 > end) throw IOException("svcb-parameter-truncated")
            val key = u16(message, offset)
            val length = u16(message, offset + 2)
            val valueStart = offset + 4
            val valueEnd = valueStart + length
            if (key <= previousKey) throw IOException("svcb-parameter-order")
            if (valueEnd < valueStart || valueEnd > end) throw IOException("svcb-parameter-length")
            when (key) {
                SVC_PARAM_IPV4_HINT -> collectAddressHints(
                    message,
                    valueStart,
                    valueEnd,
                    4,
                    rawAddresses,
                    addresses
                )
                SVC_PARAM_IPV6_HINT -> collectAddressHints(
                    message,
                    valueStart,
                    valueEnd,
                    16,
                    rawAddresses,
                    addresses
                )
            }
            previousKey = key
            offset = valueEnd
        }
        return target.domain
    }

    private fun collectAddressHints(
        message: ByteArray,
        start: Int,
        end: Int,
        addressLength: Int,
        rawAddresses: MutableList<ByteArray>,
        addresses: MutableSet<String>
    ) {
        val length = end - start
        if (length == 0 || length % addressLength != 0) throw IOException("svcb-address-hint-length")
        var offset = start
        while (offset < end) {
            val address = message.copyOfRange(offset, offset + addressLength)
            addAddress(address, rawAddresses, addresses)
            offset += addressLength
        }
    }

    private fun addAddress(
        address: ByteArray,
        rawAddresses: MutableList<ByteArray>,
        addresses: MutableSet<String>
    ) {
        if (rawAddresses.size >= MAX_ADDRESS_VALUES) throw IOException("address-count-exceeded")
        rawAddresses += address
        addresses += addressFingerprint(address)
    }

    private fun uncompressedNameEnd(message: ByteArray, start: Int, limit: Int): Int {
        var offset = start
        var totalLength = 0
        while (offset < limit) {
            val length = message[offset].toInt() and 0xff
            if (length == 0) return offset + 1
            if (length > 63) throw IOException("svcb-target-compressed")
            val next = offset + 1 + length
            if (next > limit) throw IOException("svcb-target-truncated")
            totalLength += length + if (totalLength == 0) 0 else 1
            if (totalLength > 253) throw IOException("svcb-target-too-long")
            offset = next
        }
        throw IOException("svcb-target-unterminated")
    }

    private fun meaning(
        rcode: Int,
        edeCodes: Set<Int>,
        addresses: Set<String>,
        publicQuery: Boolean,
        discoveryWkaAllowed: Boolean,
        forbiddenAddress: Boolean
    ): DnsSecurityMeaning {
        val localAddresses = addresses.filter { it.startsWith("local:") }
        val onlyDiscoveryWka = discoveryWkaAllowed && localAddresses.isNotEmpty()
        if (forbiddenAddress || publicQuery && localAddresses.isNotEmpty() && !onlyDiscoveryWka) {
            return DnsSecurityMeaning.REBINDING
        }
        if (6 in edeCodes) return DnsSecurityMeaning.DNSSEC_BOGUS
        if (4 in edeCodes) return DnsSecurityMeaning.FORGED
        if (15 in edeCodes) return DnsSecurityMeaning.BLOCKED
        if (16 in edeCodes) return DnsSecurityMeaning.CENSORED
        if (17 in edeCodes) return DnsSecurityMeaning.FILTERED
        if (rcode == 3) return DnsSecurityMeaning.NXDOMAIN
        if (rcode != 0) return DnsSecurityMeaning.ERROR
        return DnsSecurityMeaning.ACCEPTABLE
    }

    private fun addressFingerprint(bytes: ByteArray): String {
        val address = InetAddress.getByAddress(bytes)
        val local = address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress || isSpecialUseAddress(bytes)
        return (if (local) "local:" else "public:") + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal fun isPublicAddress(bytes: ByteArray): Boolean {
        return runCatching { addressFingerprint(bytes).startsWith("public:") }.getOrDefault(false)
    }

    private fun isIpv4OnlyWka(bytes: ByteArray): Boolean {
        return bytes.size == 4 && bytes[0] == 192.toByte() && bytes[1].toInt() == 0 &&
            bytes[2].toInt() == 0 && (bytes[3] == 170.toByte() || bytes[3] == 171.toByte())
    }

    private val DISCOVERY_IPV4 = listOf(
        byteArrayOf(192.toByte(), 0, 0, 170.toByte()),
        byteArrayOf(192.toByte(), 0, 0, 171.toByte())
    )

    private fun ByteArray.toCanonicalHex(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            var value = byte.toInt() and 0xff
            if (value in 'A'.code..'Z'.code) value += 32
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    private fun isSpecialUseAddress(bytes: ByteArray): Boolean {
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            return a == 0 ||
                (a == 100 && b in 64..127) ||
                (a == 192 && b == 0 && c == 0 && (bytes[3].toInt() and 0xff) !in 9..10) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 192 && b == 88 && c == 99) ||
                (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113) ||
                a >= 240
        }
        if (bytes.size != 16) return true
        val ipv4Mapped = bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        val wellKnownNat64 = bytes[0].toInt() == 0 && bytes[1] == 0x64.toByte() &&
            bytes[2] == 0xff.toByte() && bytes[3] == 0x9b.toByte() &&
            bytes.copyOfRange(4, 12).all { it == 0.toByte() }
        if (ipv4Mapped || wellKnownNat64) return !isPublicAddress(bytes.copyOfRange(12, 16))
        val first = bytes[0].toInt() and 0xff
        if (first and 0xe0 != 0x20) return true
        if (matchesPrefix(bytes, byteArrayOf(0x20, 0x01, 0x00), 23) && !isIetfGlobalException(bytes)) {
            return true
        }
        if (matchesPrefix(bytes, byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()), 32)) return true
        if (matchesPrefix(bytes, byteArrayOf(0x3f, 0xff.toByte(), 0x00), 20)) return true
        return false
    }

    private fun isIetfGlobalException(address: ByteArray): Boolean {
        if (matchesPrefix(address, byteArrayOf(0x20, 0x01, 0x00, 0x00), 32)) return true
        if (matchesPrefix(address, byteArrayOf(0x20, 0x01, 0x00, 0x03), 32)) return true
        if (matchesPrefix(address, byteArrayOf(0x20, 0x01, 0x00, 0x04, 0x01, 0x12), 48)) return true
        if (matchesPrefix(address, byteArrayOf(0x20, 0x01, 0x00, 0x20), 28)) return true
        if (matchesPrefix(address, byteArrayOf(0x20, 0x01, 0x00, 0x30), 28)) return true
        return address[0] == 0x20.toByte() && address[1] == 0x01.toByte() &&
            address[2].toInt() == 0 && address[3].toInt() == 1 &&
            (4 until 15).all { address[it].toInt() == 0 } &&
            (address[15].toInt() and 0xff) in 1..3
    }

    private fun matchesPrefix(address: ByteArray, prefix: ByteArray, bits: Int): Boolean {
        val wholeBytes = bits / 8
        for (index in 0 until wholeBytes) {
            if (address[index] != prefix[index]) return false
        }
        val remainingBits = bits % 8
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (address[wholeBytes].toInt() and mask) == (prefix[wholeBytes].toInt() and mask)
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > data.size) throw IOException("integer-out-of-bounds")
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) throw IOException("integer-out-of-bounds")
        return ((data[offset].toLong() and 0xffL) shl 24) or
            ((data[offset + 1].toLong() and 0xffL) shl 16) or
            ((data[offset + 2].toLong() and 0xffL) shl 8) or
            (data[offset + 3].toLong() and 0xffL)
    }

    private const val EDNS_FORBIDDEN_RESPONSE_TTL_BITS = 0x00ff7fffL
    private const val SOA_INTEGER_BYTES = 20
    private const val SOA_MINIMUM_OFFSET = 16
    private const val HEX = "0123456789abcdef"
    private const val IPV4_ONLY_ARPA = "ipv4only.arpa"
}
