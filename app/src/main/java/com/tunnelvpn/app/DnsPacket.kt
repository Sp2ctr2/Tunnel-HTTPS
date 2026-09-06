package com.tunnelvpn.app

import java.security.MessageDigest

object DnsPacket {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_PTR = 12
    const val TYPE_SVCB = 64
    const val TYPE_HTTPS = 65

    data class Question(
        val domain: String,
        val key: String,
        val transactionId: Int,
        val type: Int,
        val qClass: Int,
        val questionEnd: Int,
        val ednsVersion: Int
    )

    fun parseQuestion(message: ByteArray, length: Int = message.size): Question? {
        return parseQuestion(message, length, strictQuery = true)
    }

    private fun parseQuestion(message: ByteArray, length: Int, strictQuery: Boolean): Question? {
        if (length !in MIN_QUERY_SIZE..minOf(message.size, MAX_MESSAGE_SIZE)) return null
        val flags = u16(message, 2)
        if (strictQuery && (flags and QUERY_ALLOWED_FLAGS.inv() and 0xffff) != 0) return null
        val qdCount = u16(message, 4)
        val answerCount = u16(message, 6)
        val authorityCount = u16(message, 8)
        val additionalCount = u16(message, 10)
        if (qdCount != 1) return null
        if (strictQuery && (answerCount != 0 || authorityCount != 0 || additionalCount !in 0..1)) return null
        val labels = mutableListOf<String>()
        val labelRanges = mutableListOf<IntRange>()
        var offset = 12
        var terminated = false
        while (offset < length) {
            val labelLength = message[offset].toInt() and 0xff
            offset += 1
            if (labelLength == 0) {
                terminated = true
                break
            }
            if (labelLength and 0xc0 != 0 || offset + labelLength > length) return null
            val labelBytes = message.copyOfRange(offset, offset + labelLength)
            if (labelBytes.any {
                    val value = it.toInt() and 0xff
                    value !in 0x21..0x7e || value == '.'.code || value == '\\'.code
                }
            ) return null
            labels += String(labelBytes, Charsets.US_ASCII).lowercase()
            labelRanges += offset until offset + labelLength
            offset += labelLength
        }
        if (!terminated || offset + 4 > length || labels.isEmpty()) return null
        val type = u16(message, offset)
        val qClass = u16(message, offset + 2)
        val domain = labels.joinToString(".")
        if (domain.length > 253) return null
        val questionEnd = offset + 4
        if (strictQuery && !validateAdditional(message, questionEnd, length, additionalCount)) return null
        val canonical = message.copyOf(length)
        canonical[0] = 0
        canonical[1] = 0
        labelRanges.forEach { range ->
            range.forEach { index ->
                val value = canonical[index].toInt() and 0xff
                if (value in 'A'.code..'Z'.code) canonical[index] = (value + ASCII_CASE_OFFSET).toByte()
            }
        }
        return Question(
            domain = domain,
            key = canonical.sha256Hex(),
            transactionId = u16(message, 0),
            type = type,
            qClass = qClass,
            questionEnd = questionEnd,
            ednsVersion = if (additionalCount == 1) {
                ((u32(message, questionEnd + 5) ushr 16) and 0xffL).toInt()
            } else {
                0
            }
        )
    }

    private fun validateAdditional(message: ByteArray, start: Int, length: Int, count: Int): Boolean {
        if (count == 0) return start == length
        if (start + OPT_FIXED_SIZE > length || message[start].toInt() != 0) return false
        if (u16(message, start + 1) != TYPE_OPT) return false
        val ttl = u32(message, start + 5)
        if (ttl and EDNS_FORBIDDEN_TTL_BITS != 0L) return false
        val rdLength = u16(message, start + 9)
        val end = start + OPT_FIXED_SIZE + rdLength
        if (end != length) return false
        var offset = start + OPT_FIXED_SIZE
        while (offset < end) {
            if (offset + 4 > end) return false
            val optionLength = u16(message, offset + 2)
            offset += 4
            if (offset + optionLength > end) return false
            offset += optionLength
        }
        return offset == end
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        val chars = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    fun isLocalOnlyName(domain: String): Boolean {
        val normalized = domain.lowercase().trimEnd('.')
        if (!normalized.contains('.') || normalized == "local" || normalized.endsWith(".local")) return true
        if (normalized == "home.arpa" || normalized.endsWith(".home.arpa")) return true
        if (normalized == "10.in-addr.arpa" || normalized.endsWith(".10.in-addr.arpa")) return true
        if (normalized == "168.192.in-addr.arpa" || normalized.endsWith(".168.192.in-addr.arpa")) return true
        if (normalized == "127.in-addr.arpa" || normalized.endsWith(".127.in-addr.arpa")) return true
        if (normalized == "254.169.in-addr.arpa" || normalized.endsWith(".254.169.in-addr.arpa")) return true
        val labels = normalized.split('.')
        if (labels.size == 6 && labels[4] == "in-addr" && labels[5] == "arpa") {
            val octets = labels.take(4).map { it.toIntOrNull() ?: -1 }
            if (octets.all { it in 0..255 }) {
                val address = octets.reversed().map(Int::toByte).toByteArray()
                if (!DnsMessageValidator.isPublicAddress(address)) return true
            }
        }
        val inAddrIndex = labels.indexOf("in-addr")
        val secondOctet = labels.getOrNull(inAddrIndex - 2)?.toIntOrNull()
        val firstOctet = labels.getOrNull(inAddrIndex - 1)?.toIntOrNull()
        if (inAddrIndex >= 2 && inAddrIndex == labels.size - 2 && labels[inAddrIndex + 1] == "arpa" &&
            ((firstOctet == 172 && secondOctet != null && secondOctet in 16..31) ||
                (firstOctet == 100 && secondOctet != null && secondOctet in 64..127))
        ) return true
        if (!normalized.endsWith(".ip6.arpa")) return false
        val nibbles = labels.dropLast(2)
        if (nibbles.size != 32 || nibbles.any { it.length != 1 || it[0].digitToIntOrNull(16) == null }) return false
        val address = ByteArray(16)
        nibbles.reversed().forEachIndexed { index, nibble ->
            val value = nibble[0].digitToInt(16)
            val byteIndex = index / 2
            address[byteIndex] = if (index % 2 == 0) {
                (value shl 4).toByte()
            } else {
                (address[byteIndex].toInt() or value).toByte()
            }
        }
        return !DnsMessageValidator.isPublicAddress(address)
    }

    fun query(domain: String, type: Int, transactionId: Int = 0x4d21): ByteArray? {
        val normalized = DomainBlocker.normalize(domain) ?: return null
        val labels = normalized.split(".")
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        if (size > 512) return null
        val data = ByteArray(size)
        put16(data, 0, transactionId)
        data[2] = 0x01
        data[5] = 0x01
        var offset = 12
        labels.forEach { label ->
            data[offset++] = label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).copyInto(data, offset)
            offset += label.length
        }
        data[offset++] = 0
        put16(data, offset, type)
        put16(data, offset + 2, 1)
        return data
    }

    fun relatedNameQuery(query: ByteArray, domain: String, type: Int): ByteArray? {
        val original = parseQuestion(query) ?: return null
        val result = query(domain, type, original.transactionId) ?: return null
        put16(result, 2, u16(query, 2) and QUERY_ALLOWED_FLAGS)
        return appendFreshOpt(result, query)
    }


    fun aRecordResponse(query: ByteArray, length: Int, address: ByteArray = byteArrayOf(0, 0, 0, 0)): ByteArray {
        return addressRecordResponse(query, length, TYPE_A, address)
    }

    fun aaaaRecordResponse(query: ByteArray, length: Int, address: ByteArray): ByteArray {
        return addressRecordResponse(query, length, TYPE_AAAA, address)
    }

    fun cnameRecordResponse(query: ByteArray, target: String, ttlSeconds: Long = 600L): ByteArray? {
        val question = parseQuestion(query) ?: return null
        val labels = target.lowercase().trimEnd('.').split('.')
        if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return null
        val rdataSize = labels.sumOf { it.length + 1 } + 1
        if (rdataSize > 255) return null
        val response = ByteArray(question.questionEnd + RECORD_HEADER_SIZE + 2 + rdataSize)
        query.copyInto(response, 0, 0, question.questionEnd)
        val queryFlags = u16(query, 2)
        put16(response, 2, 0x8080 or (queryFlags and 0x0110))
        put16(response, 4, 1)
        put16(response, 6, 1)
        put16(response, 8, 0)
        put16(response, 10, 0)
        var offset = question.questionEnd
        response[offset++] = 0xc0.toByte()
        response[offset++] = 0x0c
        put16(response, offset, TYPE_CNAME)
        put16(response, offset + 2, 1)
        put32(response, offset + 4, ttlSeconds.coerceIn(0L, 0xffffffffL))
        put16(response, offset + 8, rdataSize)
        offset += RECORD_HEADER_SIZE
        labels.forEach { label ->
            val encoded = label.toByteArray(Charsets.US_ASCII)
            response[offset++] = encoded.size.toByte()
            encoded.copyInto(response, offset)
            offset += encoded.size
        }
        response[offset] = 0
        return restoreOptForQuery(response, query) ?: response
    }

    private fun addressRecordResponse(query: ByteArray, length: Int, recordType: Int, address: ByteArray): ByteArray {
        val question = parseQuestion(query, length)
        val expectedAddressSize = if (recordType == TYPE_A) 4 else 16
        if (question == null || question.type != recordType || question.qClass != 1 || address.size != expectedAddressSize) {
            return noDataResponse(query, length)
        }
        val end = question.questionEnd
        val response = ByteArray(end + 12 + address.size)
        System.arraycopy(query, 0, response, 0, end)
        put16(response, 2, 0x8080 or (u16(query, 2) and 0x0110))
        response[6] = 0
        response[7] = 1
        response[8] = 0
        response[9] = 0
        response[10] = 0
        response[11] = 0
        var offset = end
        response[offset++] = 0xc0.toByte()
        response[offset++] = 0x0c
        put16(response, offset, recordType)
        offset += 2
        put16(response, offset, 1)
        offset += 2
        put32(response, offset, 30)
        offset += 4
        put16(response, offset, address.size)
        offset += 2
        address.copyInto(response, offset)
        return restoreOptForQuery(response, query) ?: response
    }

    fun noDataResponse(query: ByteArray, length: Int, rcode: Int = 0): ByteArray {
        val safeLength = length.coerceIn(0, query.size)
        val question = parseQuestion(query, safeLength)
        val copiedLength = question?.questionEnd ?: safeLength.coerceAtMost(DNS_HEADER_LENGTH)
        val response = ByteArray(question?.questionEnd ?: DNS_HEADER_LENGTH)
        System.arraycopy(query, 0, response, 0, copiedLength)
        val queryFlags = if (query.size >= 4) u16(query, 2) else 0
        put16(response, 2, 0x8080 or (queryFlags and 0x0110) or (rcode and 0x0f))
        if (question == null) {
            response[4] = 0
            response[5] = 0
        }
        response[6] = 0
        response[7] = 0
        response[8] = 0
        response[9] = 0
        response[10] = 0
        response[11] = 0
        return restoreOptForQuery(response, query) ?: response
    }

    fun servFailResponse(query: ByteArray, length: Int): ByteArray = noDataResponse(query, length, rcode = 2)

    fun badVersionResponse(query: ByteArray, length: Int = query.size): ByteArray {
        val question = parseQuestion(query, length) ?: return servFailResponse(query, length)
        val response = ByteArray(question.questionEnd + OPT_FIXED_SIZE)
        query.copyInto(response, 0, 0, question.questionEnd)
        put16(response, 2, 0x8080 or (u16(query, 2) and 0x0110))
        put16(response, 4, 1)
        put16(response, 6, 0)
        put16(response, 8, 0)
        put16(response, 10, 1)
        var offset = question.questionEnd
        response[offset++] = 0
        put16(response, offset, TYPE_OPT)
        offset += 2
        put16(response, offset, u16(query, question.questionEnd + 3))
        offset += 2
        put32(response, offset, (1L shl 24) or (u32(query, question.questionEnd + 5) and DNSSEC_OK_FLAG))
        offset += 4
        put16(response, offset, 0)
        return response
    }

    fun nxDomainResponse(query: ByteArray, length: Int): ByteArray = noDataResponse(query, length, rcode = 3)

    fun truncatedResponse(query: ByteArray, length: Int = query.size): ByteArray {
        val response = noDataResponse(query, length)
        response[2] = (response[2].toInt() or 0x02).toByte()
        return response
    }

    fun udpPayloadSize(query: ByteArray, length: Int = query.size): Int {
        val question = parseQuestion(query, length) ?: return DEFAULT_UDP_PAYLOAD_SIZE
        if (u16(query, 10) == 0) return DEFAULT_UDP_PAYLOAD_SIZE
        return u16(query, question.questionEnd + 3).coerceIn(DEFAULT_UDP_PAYLOAD_SIZE, MAX_MESSAGE_SIZE)
    }

    fun withTransactionId(message: ByteArray, transactionId: Int): ByteArray {
        val copy = message.copyOf()
        if (copy.size >= 2) {
            copy[0] = ((transactionId ushr 8) and 0xff).toByte()
            copy[1] = (transactionId and 0xff).toByte()
        }
        return copy
    }

    internal fun ageResponseTtls(
        message: ByteArray,
        elapsedSeconds: Long,
        maximumTtlSeconds: Long
    ): ByteArray? {
        if (elapsedSeconds < 0L || maximumTtlSeconds < 0L ||
            message.size !in DNS_HEADER_LENGTH..MAX_MESSAGE_SIZE
        ) return null
        val question = parseQuestion(message, message.size, strictQuery = false) ?: return null
        val totalRecords = u16(message, 6).toLong() + u16(message, 8) + u16(message, 10)
        if (totalRecords > MAX_RECORDS) return null
        val result = message.copyOf()
        var offset = question.questionEnd
        repeat(totalRecords.toInt()) {
            val owner = readName(message, offset, message.size) ?: return null
            offset = owner.end
            if (offset + RECORD_HEADER_SIZE > message.size) return null
            val type = u16(message, offset)
            val ttl = u32(message, offset + 4)
            val dataLength = u16(message, offset + 8)
            val next = offset + RECORD_HEADER_SIZE + dataLength
            if (next < offset || next > message.size) return null
            if (type != TYPE_OPT) {
                val agedTtl = (ttl - elapsedSeconds).coerceIn(0L, maximumTtlSeconds)
                put32(result, offset + 4, agedTtl)
            }
            offset = next
        }
        return result.takeIf { offset == message.size }
    }

    internal fun stripOptForCache(message: ByteArray): ByteArray? {
        if (message.size !in DNS_HEADER_LENGTH..MAX_MESSAGE_SIZE) return null
        val question = parseQuestion(message, message.size, strictQuery = false) ?: return null
        val answerCount = u16(message, 6)
        val authorityCount = u16(message, 8)
        val additionalCount = u16(message, 10)
        val totalRecords = answerCount.toLong() + authorityCount + additionalCount
        if (totalRecords > MAX_RECORDS) return null
        var offset = question.questionEnd
        var optStart = -1
        repeat(totalRecords.toInt()) { recordIndex ->
            val recordStart = offset
            val owner = readName(message, offset, message.size) ?: return null
            offset = owner.end
            if (offset + RECORD_HEADER_SIZE > message.size) return null
            val type = u16(message, offset)
            val dataLength = u16(message, offset + 8)
            val next = offset + RECORD_HEADER_SIZE + dataLength
            if (next < offset || next > message.size) return null
            if (type == TYPE_OPT) {
                if (recordIndex < answerCount + authorityCount || optStart >= 0 ||
                    recordIndex != totalRecords.toInt() - 1
                ) return null
                optStart = recordStart
            }
            offset = next
        }
        if (offset != message.size) return null
        if (optStart < 0) return message.copyOf()
        val result = message.copyOf(optStart)
        put16(result, 10, additionalCount - 1)
        return result
    }

    internal fun restoreOptForQuery(message: ByteArray, query: ByteArray): ByteArray? {
        val question = parseQuestion(query) ?: return null
        val responseQuestion = parseQuestion(message, message.size, strictQuery = false) ?: return null
        if (question.domain != responseQuestion.domain || question.type != responseQuestion.type ||
            question.qClass != responseQuestion.qClass || question.questionEnd != responseQuestion.questionEnd
        ) return null
        val retargeted = message.copyOf()
        query.copyInto(retargeted, 0, 0, 2)
        query.copyInto(retargeted, DNS_HEADER_LENGTH, DNS_HEADER_LENGTH, question.questionEnd)
        return appendFreshOpt(retargeted, query)
    }

    private fun appendFreshOpt(message: ByteArray, query: ByteArray): ByteArray? {
        if (u16(query, 10) == 0) return message
        val question = parseQuestion(query) ?: return null
        if (message.size > MAX_MESSAGE_SIZE - OPT_FIXED_SIZE || u16(message, 10) == 0xffff) return null
        val optOffset = question.questionEnd
        if (optOffset + OPT_FIXED_SIZE > query.size || query[optOffset].toInt() != 0 ||
            u16(query, optOffset + 1) != TYPE_OPT
        ) return null
        val result = message.copyOf(message.size + OPT_FIXED_SIZE)
        var offset = message.size
        result[offset++] = 0
        put16(result, offset, TYPE_OPT)
        offset += 2
        put16(result, offset, u16(query, optOffset + 3))
        offset += 2
        put32(result, offset, u32(query, optOffset + 5) and DNSSEC_OK_FLAG)
        offset += 4
        put16(result, offset, 0)
        put16(result, 10, u16(message, 10) + 1)
        return result
    }

    internal fun minimumAnswerTtlSeconds(message: ByteArray, acceptedTypes: Set<Int>): Long? {
        if (message.size !in DNS_HEADER_LENGTH..MAX_MESSAGE_SIZE || acceptedTypes.isEmpty()) return null
        val question = parseQuestion(message, message.size, strictQuery = false) ?: return null
        var offset = question.questionEnd
        var minimum = Long.MAX_VALUE
        repeat(u16(message, 6)) {
            val owner = readName(message, offset, message.size) ?: return null
            offset = owner.end
            if (offset + RECORD_HEADER_SIZE > message.size) return null
            val type = u16(message, offset)
            val ttl = u32(message, offset + 4)
            val dataLength = u16(message, offset + 8)
            val next = offset + RECORD_HEADER_SIZE + dataLength
            if (next < offset || next > message.size) return null
            if (type in acceptedTypes) minimum = minOf(minimum, ttl)
            offset = next
        }
        return minimum.takeIf { it != Long.MAX_VALUE }
    }

    fun extractARecords(message: ByteArray, length: Int = message.size): List<ByteArray> {
        return extractAddressRecords(message, length, TYPE_A, 4)
    }

    fun extractAaaaRecords(message: ByteArray, length: Int = message.size): List<ByteArray> {
        return extractAddressRecords(message, length, TYPE_AAAA, 16)
    }

    private fun extractAddressRecords(
        message: ByteArray,
        length: Int,
        expectedType: Int,
        expectedLength: Int
    ): List<ByteArray> {
        if (length !in DNS_HEADER_LENGTH..message.size) return emptyList()
        val flags = u16(message, 2)
        if (flags and 0x8000 == 0 || flags and 0x0200 != 0 || flags and 0x000f != 0) return emptyList()
        val question = parseQuestion(message, length, strictQuery = false) ?: return emptyList()
        val answerCount = u16(message, 6)
        var offset = question.questionEnd
        val addresses = mutableListOf<AddressAnswer>()
        val aliases = linkedMapOf<String, String>()
        val dnames = linkedMapOf<String, String>()
        repeat(answerCount) {
            val owner = readName(message, offset, length) ?: return emptyList()
            offset = owner.end
            if (offset + 10 > length) return emptyList()
            val type = u16(message, offset)
            val qClass = u16(message, offset + 2)
            val rdLength = u16(message, offset + 8)
            offset += 10
            if (offset + rdLength > length) return emptyList()
            if (type == expectedType && qClass == 1 && rdLength == expectedLength) {
                addresses += AddressAnswer(owner.value, message.copyOfRange(offset, offset + expectedLength))
            } else if (type == TYPE_CNAME && qClass == 1) {
                val target = readName(message, offset, length) ?: return emptyList()
                if (target.end != offset + rdLength) return emptyList()
                val previous = aliases.putIfAbsent(owner.value, target.value)
                if (previous != null && previous != target.value) return emptyList()
            } else if (type == TYPE_DNAME && qClass == 1) {
                val target = readName(message, offset, length) ?: return emptyList()
                if (target.end != offset + rdLength || owner.value.isEmpty()) return emptyList()
                val previous = dnames.putIfAbsent(owner.value, target.value)
                if (previous != null && previous != target.value) return emptyList()
            }
            offset += rdLength
        }
        val seen = linkedSetOf<String>()
        var terminal = question.domain
        repeat(MAX_CNAME_DEPTH + 1) {
            if (!seen.add(terminal)) return emptyList()
            val alias = aliases[terminal]
            if (alias != null) {
                terminal = alias
            } else {
                val dname = dnames.entries
                    .filter { (owner, _) -> terminal != owner && terminal.endsWith(".$owner") }
                    .maxByOrNull { (owner, _) -> owner.count { it == '.' } }
                if (dname == null) {
                    return addresses.filter { answer -> answer.owner == terminal }
                        .map { answer -> answer.address }
                }
                val prefix = terminal.removeSuffix(".${dname.key}")
                terminal = if (dname.value.isEmpty()) prefix else "$prefix.${dname.value}"
                if (terminal.length > 253) return emptyList()
            }
        }
        return emptyList()
    }

    private data class AddressAnswer(val owner: String, val address: ByteArray)
    private data class ParsedName(val value: String, val end: Int)

    private fun readName(message: ByteArray, start: Int, length: Int): ParsedName? {
        if (start !in 0 until length) return null
        val labels = mutableListOf<String>()
        val visited = mutableSetOf<Int>()
        var cursor = start
        var consumedEnd = -1
        var jumps = 0
        var totalLength = 0
        while (true) {
            if (cursor !in 0 until length || !visited.add(cursor)) return null
            val labelLength = message[cursor].toInt() and 0xff
            when {
                labelLength == 0 -> {
                    if (consumedEnd < 0) consumedEnd = cursor + 1
                    return ParsedName(labels.joinToString("."), consumedEnd)
                }
                labelLength and 0xc0 == 0xc0 -> {
                    if (cursor + 1 >= length) return null
                    val pointer = ((labelLength and 0x3f) shl 8) or
                        (message[cursor + 1].toInt() and 0xff)
                    if (pointer >= length) return null
                    if (consumedEnd < 0) consumedEnd = cursor + 2
                    cursor = pointer
                    jumps += 1
                    if (jumps > MAX_POINTER_JUMPS) return null
                }
                labelLength and 0xc0 != 0 || labelLength > 63 -> return null
                else -> {
                    val next = cursor + 1 + labelLength
                    if (next > length) return null
                    val label = message.copyOfRange(cursor + 1, next)
                    if (label.any {
                            val value = it.toInt() and 0xff
                            value !in 0x21..0x7e || value == '.'.code || value == '\\'.code
                        }
                    ) return null
                    val text = String(label, Charsets.US_ASCII).lowercase()
                    totalLength += text.length + if (labels.isEmpty()) 0 else 1
                    if (totalLength > 253) return null
                    labels += text
                    cursor = next
                }
            }
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xffL) shl 24) or
            ((data[offset + 1].toLong() and 0xffL) shl 16) or
            ((data[offset + 2].toLong() and 0xffL) shl 8) or
            (data[offset + 3].toLong() and 0xffL)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 24) and 0xff).toByte()
        data[offset + 1] = ((value ushr 16) and 0xff).toByte()
        data[offset + 2] = ((value ushr 8) and 0xff).toByte()
        data[offset + 3] = (value and 0xff).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xff).toByte()
        data[offset + 1] = ((value ushr 16) and 0xff).toByte()
        data[offset + 2] = ((value ushr 8) and 0xff).toByte()
        data[offset + 3] = (value and 0xff).toByte()
    }

    private const val DNS_HEADER_LENGTH = 12
    private const val MIN_QUERY_SIZE = 17
    private const val MAX_MESSAGE_SIZE = 65_535
    private const val TYPE_OPT = 41
    private const val OPT_FIXED_SIZE = 11
    private const val QUERY_ALLOWED_FLAGS = 0x0130
    private const val ASCII_CASE_OFFSET = 32
    private const val EDNS_FORBIDDEN_TTL_BITS = 0xff007fffL
    private const val DNSSEC_OK_FLAG = 0x00008000L
    private const val DEFAULT_UDP_PAYLOAD_SIZE = 512
    private const val TYPE_CNAME = 5
    private const val TYPE_DNAME = 39
    private const val MAX_CNAME_DEPTH = 16
    private const val MAX_POINTER_JUMPS = 32
    private const val MAX_RECORDS = 256
    private const val RECORD_HEADER_SIZE = 10
    private const val HEX = "0123456789abcdef"
}
