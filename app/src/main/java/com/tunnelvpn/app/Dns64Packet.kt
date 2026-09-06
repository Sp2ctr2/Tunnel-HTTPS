package com.tunnelvpn.app

import java.io.ByteArrayOutputStream

internal object Dns64Packet {
    private data class Name(
        val labels: List<ByteArray>,
        val value: String,
        val end: Int
    )

    private data class Record(
        val owner: Name,
        val type: Int,
        val qClass: Int,
        val ttl: Long,
        val rdataStart: Int,
        val rdataEnd: Int,
        val source: ByteArray
    )

    private data class Message(
        val question: Name,
        val questionType: Int,
        val questionClass: Int,
        val questionEnd: Int,
        val answers: List<Record>,
        val authorities: List<Record>,
        val additionals: List<Record>
    )

    private data class DnameAlias(
        val record: Record,
        val owner: String,
        val target: String,
        val ownerDepth: Int
    )

    fun hasDnssecOk(query: ByteArray): Boolean {
        val parsed = parse(query) ?: return false
        return parsed.additionals.any { record ->
            record.type == TYPE_OPT && record.owner.value.isEmpty() && record.ttl and DNSSEC_OK != 0L
        }
    }

    fun checkingDisabled(query: ByteArray): Boolean {
        return query.size >= HEADER_SIZE && u16(query, 2) and CHECKING_DISABLED != 0
    }

    fun authenticatedData(response: ByteArray): Boolean {
        return response.size >= HEADER_SIZE && u16(response, 2) and AUTHENTICATED_DATA != 0
    }

    fun responseCode(response: ByteArray): Int {
        val parsed = parse(response) ?: return if (response.size == HEADER_SIZE && u16(response, 4) == 0) {
            u16(response, 2) and RCODE_MASK
        } else {
            -1
        }
        val extended = parsed.additionals
            .firstOrNull { record -> record.type == TYPE_OPT && record.owner.value.isEmpty() }
            ?.let { record -> ((record.ttl ushr 24) and 0xffL).toInt() }
            ?: 0
        return (extended shl 4) or (u16(response, 2) and RCODE_MASK)
    }

    fun hasQuestion(response: ByteArray): Boolean = parse(response) != null

    fun negativeSoaTtl(response: ByteArray): Long? {
        val parsed = parse(response) ?: return null
        return parsed.authorities
            .asSequence()
            .filter { record -> record.type == TYPE_SOA && record.qClass == INTERNET_CLASS }
            .mapNotNull(::soaNegativeTtl)
            .minOrNull()
    }

    private fun soaNegativeTtl(record: Record): Long? {
        val primary = readName(record.source, record.rdataStart) ?: return null
        val mailbox = readName(record.source, primary.end) ?: return null
        if (mailbox.end + SOA_INTEGER_BYTES != record.rdataEnd) return null
        val minimum = u32(record.source, mailbox.end + SOA_MINIMUM_OFFSET)
        if (minimum < 0L) return null
        return minOf(record.ttl, minimum)
    }

    fun relatedQuery(query: ByteArray, type: Int): ByteArray? {
        val question = DnsPacket.parseQuestion(query) ?: return null
        val result = query.copyOf()
        put16(result, question.questionEnd - 4, type)
        return result
    }

    fun retargetResponse(query: ByteArray, response: ByteArray): ByteArray? {
        val queryMessage = parse(query) ?: return null
        val responseMessage = parse(response)
        if (responseMessage == null) {
            if (response.size != HEADER_SIZE || u16(response, 4) != 0 || u16(response, 6) != 0 ||
                u16(response, 8) != 0 || u16(response, 10) != 0 ||
                u16(response, 2) and RESPONSE == 0 || u16(response, 2) and RCODE_MASK == 0
            ) return null
            val header = response.copyOf()
            query.copyInto(header, 0, 0, 2)
            put16(header, 2, u16(header, 2) and AUTHENTICATED_DATA.inv())
            put16(header, 4, 1)
            val rebuilt = header + query.copyOfRange(HEADER_SIZE, queryMessage.questionEnd)
            return DnsPacket.restoreOptForQuery(rebuilt, query)
        }
        if (queryMessage.question.value != responseMessage.question.value ||
            queryMessage.questionClass != responseMessage.questionClass ||
            queryMessage.questionEnd != responseMessage.questionEnd
        ) return null
        val retainedAdditionals = responseMessage.additionals.filterNot { record -> record.type == TYPE_OPT }
        val header = response.copyOfRange(0, HEADER_SIZE)
        query.copyInto(header, 0, 0, 2)
        put16(header, 2, u16(header, 2) and AUTHENTICATED_DATA.inv())
        put16(header, 4, 1)
        put16(header, 6, responseMessage.answers.size)
        put16(header, 8, responseMessage.authorities.size)
        put16(header, 10, retainedAdditionals.size)
        val output = ByteArrayOutputStream()
        output.write(header)
        output.write(query, HEADER_SIZE, queryMessage.questionEnd - HEADER_SIZE)
        responseMessage.answers.forEach { record -> if (!writeRecord(output, record)) return null }
        responseMessage.authorities.forEach { record -> if (!writeRecord(output, record)) return null }
        retainedAdditionals.forEach { record -> if (!writeRecord(output, record)) return null }
        val fullRcode = responseCode(response)
        if (fullRcode < 0) return null
        val rebuilt = DnsPacket.restoreOptForQuery(output.toByteArray(), query) ?: return null
        if (fullRcode <= RCODE_MASK) return rebuilt
        if (u16(query, 10) == 0 || rebuilt.size < FRESH_OPT_SIZE) return null
        val optOffset = rebuilt.size - FRESH_OPT_SIZE
        if (rebuilt[optOffset].toInt() != 0 || u16(rebuilt, optOffset + 1) != TYPE_OPT) return null
        val optTtl = u32(rebuilt, optOffset + 5)
        put32(rebuilt, optOffset + 5, optTtl or ((fullRcode ushr 4).toLong() shl 24))
        return rebuilt
    }

    fun withoutIpv4MappedAaaa(query: ByteArray, response: ByteArray): ByteArray? {
        val queryMessage = parse(query) ?: return null
        val responseMessage = parse(response) ?: return null
        if (queryMessage.question.value != responseMessage.question.value ||
            queryMessage.questionType != responseMessage.questionType ||
            queryMessage.questionClass != responseMessage.questionClass
        ) return null
        val retainedAnswers = responseMessage.answers.filterNot { record ->
            record.type == DnsPacket.TYPE_AAAA && record.qClass == INTERNET_CLASS &&
                record.rdataEnd - record.rdataStart == IPV6_BYTES &&
                isIpv4Mapped(record.source, record.rdataStart)
        }
        if (retainedAnswers.size == responseMessage.answers.size) return response
        if (hasDnssecOk(query) ||
            (responseMessage.answers + responseMessage.authorities + responseMessage.additionals).any { record ->
                record.type == TYPE_DS || record.type == TYPE_RRSIG || record.type == TYPE_DNSKEY ||
                    record.type == TYPE_NSEC || record.type == TYPE_NSEC3 || record.type == TYPE_NSEC3PARAM
            }
        ) return null
        val retainedAdditionals = responseMessage.additionals.filterNot { record -> record.type == TYPE_OPT }
        val header = response.copyOfRange(0, HEADER_SIZE)
        query.copyInto(header, 0, 0, 2)
        put16(header, 2, u16(header, 2) and AUTHENTICATED_DATA.inv())
        put16(header, 4, 1)
        put16(header, 6, retainedAnswers.size)
        put16(header, 8, responseMessage.authorities.size)
        put16(header, 10, retainedAdditionals.size)
        val output = ByteArrayOutputStream()
        output.write(header)
        output.write(query, HEADER_SIZE, queryMessage.questionEnd - HEADER_SIZE)
        retainedAnswers.forEach { record -> if (!writeRecord(output, record)) return null }
        responseMessage.authorities.forEach { record -> if (!writeRecord(output, record)) return null }
        retainedAdditionals.forEach { record -> if (!writeRecord(output, record)) return null }
        val result = output.toByteArray()
        if (result.size > MAX_MESSAGE_SIZE) return null
        return DnsPacket.restoreOptForQuery(result, query)
    }

    private fun isIpv4Mapped(data: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset > data.size - IPV6_BYTES) return false
        for (index in 0 until IPV4_MAPPED_ZERO_BYTES) {
            if (data[offset + index].toInt() != 0) return false
        }
        return data[offset + 10] == 0xff.toByte() && data[offset + 11] == 0xff.toByte()
    }

    private fun isDiscoveryWka(address: ByteArray): Boolean {
        return address.size == IPV4_BYTES && address[0] == 192.toByte() &&
            address[1].toInt() == 0 && address[2].toInt() == 0 &&
            (address[3] == 170.toByte() || address[3] == 171.toByte())
    }

    fun synthesize(
        query: ByteArray,
        negativeAaaaResponse: ByteArray,
        aResponse: ByteArray,
        prefixes: List<Nat64Prefix>,
        authenticated: Boolean,
        maximumSyntheticTtl: Long = Long.MAX_VALUE
    ): ByteArray? {
        if (prefixes.isEmpty() || maximumSyntheticTtl <= 0L) return null
        val queryMessage = parse(query) ?: return null
        val aMessage = parse(aResponse) ?: return null
        val negativeMeaning = runCatching {
            DnsMessageValidator.validate(query, negativeAaaaResponse, publicQuery = false).meaning
        }.getOrNull() ?: return null
        val aQuery = relatedQuery(query, DnsPacket.TYPE_A) ?: return null
        val aValidation = runCatching {
            DnsMessageValidator.validate(aQuery, aResponse, publicQuery = false)
        }.getOrNull() ?: return null
        if (DnsMessageValidator.isSecurityHardStop(negativeMeaning) ||
            DnsMessageValidator.isSecurityHardStop(aValidation.meaning) ||
            negativeMeaning != DnsSecurityMeaning.ACCEPTABLE && negativeMeaning != DnsSecurityMeaning.ERROR ||
            aValidation.meaning != DnsSecurityMeaning.ACCEPTABLE || !aValidation.hasRequestedAnswer
        ) return null
        if (queryMessage.question.value != aMessage.question.value ||
            queryMessage.questionClass != INTERNET_CLASS ||
            aMessage.questionClass != INTERNET_CLASS ||
            queryMessage.questionType != DnsPacket.TYPE_AAAA ||
            aMessage.questionType != DnsPacket.TYPE_A ||
            u16(aResponse, 2) and RESPONSE == 0 ||
            u16(aResponse, 2) and TRUNCATED != 0 ||
            u16(aResponse, 2) and RCODE_MASK != 0
        ) return null
        val chain = aliasChain(aMessage) ?: return null
        if (aMessage.answers.any { record ->
                record.type != DnsPacket.TYPE_A && record.type != TYPE_CNAME && record.type != TYPE_DNAME
            }
        ) return null
        if ((aMessage.authorities + aMessage.additionals).any { record ->
                record.type == TYPE_DS || record.type == TYPE_RRSIG || record.type == TYPE_DNSKEY ||
                    record.type == TYPE_NSEC || record.type == TYPE_NSEC3 || record.type == TYPE_NSEC3PARAM
            }
        ) return null
        val addresses = aMessage.answers.mapNotNull { record ->
            if (record.owner.value != chain.second || record.type != DnsPacket.TYPE_A ||
                record.qClass != INTERNET_CLASS || record.rdataEnd - record.rdataStart != IPV4_BYTES
            ) return@mapNotNull null
            val address = record.source.copyOfRange(record.rdataStart, record.rdataEnd)
            val discoveryWka = queryMessage.question.value == IPV4_ONLY_ARPA && isDiscoveryWka(address)
            val eligiblePrefixes = if (DnsMessageValidator.isPublicAddress(address) || discoveryWka) {
                prefixes
            } else {
                prefixes.filterNot(Nat64Prefix::isWellKnown)
            }
            eligiblePrefixes.takeIf { it.isNotEmpty() }?.let { record to it }
        }
        if (addresses.isEmpty()) return null
        if (chain.first.size.toLong() + addresses.sumOf { it.second.size.toLong() } > MAX_RECORDS) return null
        val soaTtl = negativeSoaTtl(negativeAaaaResponse)
        val retainedAdditionals = aMessage.additionals.filterNot { record -> record.type == TYPE_OPT }
        val output = ByteArrayOutputStream()
        val header = aResponse.copyOfRange(0, HEADER_SIZE)
        query.copyInto(header, 0, 0, 2)
        var flags = u16(header, 2) or RESPONSE
        flags = flags and TRUNCATED.inv()
        flags = if (authenticated) flags or AUTHENTICATED_DATA else flags and AUTHENTICATED_DATA.inv()
        put16(header, 2, flags)
        put16(header, 4, 1)
        put16(header, 6, chain.first.size + addresses.sumOf { it.second.size })
        put16(header, 8, aMessage.authorities.size)
        put16(header, 10, retainedAdditionals.size)
        output.write(header)
        output.write(query, HEADER_SIZE, queryMessage.questionEnd - HEADER_SIZE)
        chain.first.forEach { record -> if (!writeRecord(output, record)) return null }
        addresses.forEach { (record, eligiblePrefixes) ->
            val original = record.source.copyOfRange(record.rdataStart, record.rdataEnd)
            eligiblePrefixes.forEach { prefix ->
                val ttl = minOf(record.ttl, soaTtl ?: DEFAULT_SYNTHETIC_TTL, maximumSyntheticTtl)
                if (!writeRecord(
                    output,
                    record,
                    type = DnsPacket.TYPE_AAAA,
                    ttl = ttl,
                    rdata = prefix.synthesize(original)
                )) return null
            }
        }
        aMessage.authorities.forEach { record -> if (!writeRecord(output, record)) return null }
        retainedAdditionals.forEach { record -> if (!writeRecord(output, record)) return null }
        val result = output.toByteArray()
        if (result.size > MAX_MESSAGE_SIZE) return null
        return DnsPacket.restoreOptForQuery(result, query)
    }

    fun nat64PtrResponse(
        originalQuery: ByteArray,
        targetQuery: ByteArray,
        targetResponse: ByteArray,
        target: String,
        syntheticTtl: Long
    ): ByteArray? {
        val original = parse(originalQuery) ?: return null
        val targetRequest = parse(targetQuery) ?: return null
        val upstream = parse(targetResponse) ?: return null
        if (original.questionType != DnsPacket.TYPE_PTR || original.questionClass != INTERNET_CLASS ||
            targetRequest.question.value != target || targetRequest.questionType != DnsPacket.TYPE_PTR ||
            upstream.question.value != target || upstream.questionType != DnsPacket.TYPE_PTR ||
            upstream.questionClass != INTERNET_CLASS || responseCode(targetResponse) != 0
        ) return null
        if (upstream.answers.isEmpty() || upstream.answers.any { record ->
                record.owner.value != target || record.type != DnsPacket.TYPE_PTR ||
                    record.qClass != INTERNET_CLASS || readName(record.source, record.rdataStart)?.end != record.rdataEnd
            }
        ) return null
        if ((upstream.answers + upstream.authorities + upstream.additionals).any { record ->
                record.type == TYPE_DS || record.type == TYPE_RRSIG || record.type == TYPE_DNSKEY ||
                    record.type == TYPE_NSEC || record.type == TYPE_NSEC3 || record.type == TYPE_NSEC3PARAM
            }
        ) return null
        val cnameOwner = nameFromValue(original.question.value) ?: return null
        val cnameTarget = nameFromValue(target) ?: return null
        val encodedTarget = ByteArrayOutputStream().also { writeName(it, cnameTarget) }.toByteArray()
        val cname = Record(
            cnameOwner,
            TYPE_CNAME,
            INTERNET_CLASS,
            minOf(syntheticTtl, upstream.answers.minOf(Record::ttl)),
            0,
            encodedTarget.size,
            encodedTarget
        )
        val header = targetResponse.copyOfRange(0, HEADER_SIZE)
        val retainedAdditionals = upstream.additionals.filterNot { record -> record.type == TYPE_OPT }
        originalQuery.copyInto(header, 0, 0, 2)
        put16(header, 2, u16(header, 2) and AUTHENTICATED_DATA.inv())
        put16(header, 4, 1)
        put16(header, 6, upstream.answers.size + 1)
        put16(header, 10, retainedAdditionals.size)
        val output = ByteArrayOutputStream()
        output.write(header)
        output.write(originalQuery, HEADER_SIZE, original.questionEnd - HEADER_SIZE)
        if (!writeRecord(output, cname)) return null
        upstream.answers.forEach { record -> if (!writeRecord(output, record)) return null }
        upstream.authorities.forEach { record -> if (!writeRecord(output, record)) return null }
        retainedAdditionals.forEach { record -> if (!writeRecord(output, record)) return null }
        val combined = output.toByteArray().takeIf { it.size <= MAX_MESSAGE_SIZE } ?: return null
        return DnsPacket.restoreOptForQuery(combined, originalQuery)
    }

    private fun aliasChain(message: Message): Pair<List<Record>, String>? {
        val aliases = message.answers
            .filter { record -> record.type == TYPE_CNAME && record.qClass == INTERNET_CLASS }
            .groupBy { record -> record.owner.value }
        val dnames = message.answers
            .filter { record -> record.type == TYPE_DNAME && record.qClass == INTERNET_CLASS }
            .map { record ->
                val target = readName(record.source, record.rdataStart) ?: return null
                if (target.end != record.rdataEnd || record.owner.value.isEmpty()) return null
                DnameAlias(
                    record,
                    record.owner.value,
                    target.value,
                    record.owner.labels.size
                )
            }
        if (dnames.groupBy { it.owner }.any { (_, records) ->
                records.map { it.target }.distinct().size != 1
            }
        ) return null
        val result = mutableListOf<Record>()
        val visited = linkedSetOf<String>()
        var current = message.question.value
        repeat(MAX_CNAME_DEPTH + 1) {
            if (!visited.add(current)) return null
            val records = aliases[current].orEmpty()
            if (records.isNotEmpty()) {
                val targets = records.map { record ->
                    val target = readName(record.source, record.rdataStart) ?: return null
                    if (target.end != record.rdataEnd) return null
                    target.value
                }.distinct()
                if (targets.size != 1) return null
                val target = targets.single()
                val dname = closestDname(dnames, current, target)
                if (dname != null && dname.record !in result) result += dname.record
                result += records.first()
                current = target
            } else {
                val dname = closestDname(dnames, current, null) ?: return result to current
                val target = substituteDname(current, dname) ?: return null
                if (dname.record !in result) result += dname.record
                result += syntheticCname(current, target, dname.record) ?: return null
                current = target
            }
        }
        return null
    }

    private fun closestDname(
        dnames: List<DnameAlias>,
        current: String,
        requiredTarget: String?
    ): DnameAlias? {
        val applicable = dnames.mapNotNull { dname ->
            val target = substituteDname(current, dname) ?: return@mapNotNull null
            if (requiredTarget != null && target != requiredTarget) return@mapNotNull null
            dname to target
        }
        val maximumDepth = applicable.maxOfOrNull { it.first.ownerDepth } ?: return null
        val closest = applicable.filter { it.first.ownerDepth == maximumDepth }
        if (closest.map { it.second }.distinct().size != 1) return null
        return closest.first().first
    }

    private fun substituteDname(current: String, dname: DnameAlias): String? {
        if (current == dname.owner || !current.endsWith(".${dname.owner}")) return null
        val prefix = current.dropLast(dname.owner.length + 1)
        val target = if (dname.target.isEmpty()) prefix else "$prefix.${dname.target}"
        return target.takeIf { it.isNotEmpty() && it.length <= MAX_DNS_NAME_LENGTH }
    }

    private fun syntheticCname(owner: String, target: String, dname: Record): Record? {
        val ownerName = nameFromValue(owner) ?: return null
        val targetName = nameFromValue(target) ?: return null
        val rdata = ByteArrayOutputStream()
        writeName(rdata, targetName)
        val bytes = rdata.toByteArray()
        return Record(ownerName, TYPE_CNAME, dname.qClass, dname.ttl, 0, bytes.size, bytes)
    }

    private fun nameFromValue(value: String): Name? {
        if (value.isEmpty() || value.length > MAX_DNS_NAME_LENGTH) return null
        val labels = value.split('.').map { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > MAX_LABEL_LENGTH) return null
            bytes
        }
        val wireLength = labels.sumOf { it.size + 1 } + 1
        if (wireLength > MAX_WIRE_NAME_LENGTH) return null
        return Name(labels, value, wireLength)
    }

    private fun parse(message: ByteArray): Message? {
        if (message.size !in HEADER_SIZE..MAX_MESSAGE_SIZE || u16(message, 4) != 1) return null
        val question = readName(message, HEADER_SIZE) ?: return null
        if (question.end + 4 > message.size) return null
        val questionType = u16(message, question.end)
        val questionClass = u16(message, question.end + 2)
        val questionEnd = question.end + 4
        val answerCount = u16(message, 6)
        val authorityCount = u16(message, 8)
        val additionalCount = u16(message, 10)
        if (answerCount.toLong() + authorityCount + additionalCount > MAX_RECORDS) return null
        var offset = questionEnd
        fun records(count: Int): List<Record>? {
            val result = ArrayList<Record>(count)
            repeat(count) {
                val owner = readName(message, offset) ?: return null
                offset = owner.end
                if (offset + RECORD_HEADER_SIZE > message.size) return null
                val type = u16(message, offset)
                val qClass = u16(message, offset + 2)
                val ttl = u32(message, offset + 4)
                val size = u16(message, offset + 8)
                val start = offset + RECORD_HEADER_SIZE
                val end = start + size
                if (end < start || end > message.size) return null
                result += Record(owner, type, qClass, ttl, start, end, message)
                offset = end
            }
            return result
        }
        val answers = records(answerCount) ?: return null
        val authorities = records(authorityCount) ?: return null
        val additionals = records(additionalCount) ?: return null
        if (offset != message.size) return null
        return Message(
            question,
            questionType,
            questionClass,
            questionEnd,
            answers,
            authorities,
            additionals
        )
    }

    private fun readName(message: ByteArray, start: Int): Name? {
        if (start !in message.indices) return null
        val labels = mutableListOf<ByteArray>()
        val visited = mutableSetOf<Int>()
        var cursor = start
        var consumedEnd = -1
        var jumps = 0
        var totalLength = 0
        while (true) {
            if (cursor !in message.indices || !visited.add(cursor)) return null
            val length = message[cursor].toInt() and 0xff
            when {
                length == 0 -> {
                    if (consumedEnd < 0) consumedEnd = cursor + 1
                    val value = labels.joinToString(".") { label ->
                        String(label, Charsets.US_ASCII).lowercase()
                    }
                    return Name(labels, value, consumedEnd)
                }
                length and 0xc0 == 0xc0 -> {
                    if (cursor + 1 >= message.size) return null
                    val pointer = ((length and 0x3f) shl 8) or (message[cursor + 1].toInt() and 0xff)
                    if (pointer >= message.size) return null
                    if (consumedEnd < 0) consumedEnd = cursor + 2
                    cursor = pointer
                    jumps += 1
                    if (jumps > MAX_POINTER_JUMPS) return null
                }
                length and 0xc0 != 0 || length > 63 -> return null
                else -> {
                    val end = cursor + 1 + length
                    if (end > message.size) return null
                    val label = message.copyOfRange(cursor + 1, end)
                    if (label.any { byte ->
                            val value = byte.toInt() and 0xff
                            value !in 0x21..0x7e || value == '.'.code || value == '\\'.code
                        }
                    ) return null
                    totalLength += label.size + if (labels.isEmpty()) 0 else 1
                    if (totalLength > 253) return null
                    labels += label
                    cursor = end
                }
            }
        }
    }

    private fun writeRecord(
        output: ByteArrayOutputStream,
        record: Record,
        type: Int = record.type,
        ttl: Long = record.ttl,
        rdata: ByteArray? = null
    ): Boolean {
        writeName(output, record.owner)
        write16(output, type)
        write16(output, record.qClass)
        write32(output, ttl)
        val encoded = rdata ?: encodeRdata(record) ?: return false
        write16(output, encoded.size)
        output.write(encoded)
        return true
    }

    private fun encodeRdata(record: Record): ByteArray? {
        val output = ByteArrayOutputStream()
        when (record.type) {
            TYPE_NS, TYPE_MD, TYPE_MF, TYPE_CNAME, TYPE_MB, TYPE_MG, TYPE_MR, TYPE_PTR, TYPE_DNAME -> {
                val name = readName(record.source, record.rdataStart)
                if (name == null || name.end != record.rdataEnd) return null
                writeName(output, name)
            }
            TYPE_MINFO, TYPE_RP -> {
                val first = readName(record.source, record.rdataStart) ?: return null
                val second = readName(record.source, first.end) ?: return null
                if (second.end != record.rdataEnd) return null
                writeName(output, first)
                writeName(output, second)
            }
            TYPE_SOA -> {
                val primary = readName(record.source, record.rdataStart) ?: return null
                val mailbox = readName(record.source, primary.end) ?: return null
                if (mailbox.end + SOA_INTEGER_BYTES != record.rdataEnd) return null
                writeName(output, primary)
                writeName(output, mailbox)
                output.write(record.source, mailbox.end, SOA_INTEGER_BYTES)
            }
            TYPE_MX, TYPE_AFSDB, TYPE_RT, TYPE_KX -> {
                if (record.rdataStart + 2 > record.rdataEnd) return null
                val exchange = readName(record.source, record.rdataStart + 2) ?: return null
                if (exchange.end != record.rdataEnd) return null
                output.write(record.source, record.rdataStart, 2)
                writeName(output, exchange)
            }
            TYPE_PX -> {
                if (record.rdataStart + 2 > record.rdataEnd) return null
                val map822 = readName(record.source, record.rdataStart + 2) ?: return null
                val mapX400 = readName(record.source, map822.end) ?: return null
                if (mapX400.end != record.rdataEnd) return null
                output.write(record.source, record.rdataStart, 2)
                writeName(output, map822)
                writeName(output, mapX400)
            }
            TYPE_SRV -> {
                if (record.rdataStart + 6 > record.rdataEnd) return null
                val target = readName(record.source, record.rdataStart + 6) ?: return null
                if (target.end != record.rdataEnd) return null
                output.write(record.source, record.rdataStart, 6)
                writeName(output, target)
            }
            TYPE_NAPTR -> {
                if (record.rdataStart + 4 > record.rdataEnd) return null
                output.write(record.source, record.rdataStart, 4)
                var offset = record.rdataStart + 4
                repeat(3) {
                    if (offset >= record.rdataEnd) return null
                    val size = record.source[offset].toInt() and 0xff
                    if (offset + 1 + size > record.rdataEnd) return null
                    output.write(record.source, offset, size + 1)
                    offset += size + 1
                }
                val replacement = readName(record.source, offset) ?: return null
                if (replacement.end != record.rdataEnd) return null
                writeName(output, replacement)
            }
            TYPE_SIG, TYPE_RRSIG -> {
                if (record.rdataStart + RRSIG_FIXED_BYTES > record.rdataEnd) return null
                val signer = readName(record.source, record.rdataStart + RRSIG_FIXED_BYTES) ?: return null
                if (signer.end > record.rdataEnd) return null
                output.write(record.source, record.rdataStart, RRSIG_FIXED_BYTES)
                writeName(output, signer)
                output.write(record.source, signer.end, record.rdataEnd - signer.end)
            }
            TYPE_NXT, TYPE_NSEC -> {
                val next = readName(record.source, record.rdataStart) ?: return null
                if (next.end >= record.rdataEnd) return null
                writeName(output, next)
                output.write(record.source, next.end, record.rdataEnd - next.end)
            }
            TYPE_SVCB, TYPE_HTTPS -> {
                if (record.rdataStart + 2 > record.rdataEnd) return null
                output.write(record.source, record.rdataStart, 2)
                val target = readName(record.source, record.rdataStart + 2) ?: return null
                if (target.end > record.rdataEnd) return null
                writeName(output, target)
                output.write(record.source, target.end, record.rdataEnd - target.end)
            }
            TYPE_A6 -> {
                if (record.rdataStart >= record.rdataEnd) return null
                val prefixLength = record.source[record.rdataStart].toInt() and 0xff
                if (prefixLength > 128) return null
                val suffixBytes = (128 - prefixLength + 7) / 8
                val suffixEnd = record.rdataStart + 1 + suffixBytes
                if (suffixEnd > record.rdataEnd) return null
                output.write(record.source, record.rdataStart, 1 + suffixBytes)
                if (prefixLength == 0) {
                    if (suffixEnd != record.rdataEnd) return null
                } else {
                    val prefixName = readName(record.source, suffixEnd) ?: return null
                    if (prefixName.end != record.rdataEnd) return null
                    writeName(output, prefixName)
                }
            }
            TYPE_A, TYPE_AAAA, TYPE_TXT, TYPE_OPT, TYPE_DS, TYPE_DNSKEY,
            TYPE_NSEC3, TYPE_NSEC3PARAM, TYPE_TLSA, TYPE_CAA -> return rawRdata(record)
            else -> return rawRdata(record)
        }
        return output.toByteArray()
    }

    private fun rawRdata(record: Record): ByteArray {
        return record.source.copyOfRange(record.rdataStart, record.rdataEnd)
    }

    private fun writeName(output: ByteArrayOutputStream, name: Name) {
        name.labels.forEach { label ->
            output.write(label.size)
            output.write(label)
        }
        output.write(0)
    }

    private fun write16(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun write32(output: ByteArrayOutputStream, value: Long) {
        output.write(((value ushr 24) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write((value and 0xff).toInt())
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > data.size) return -1
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) return -1L
        return ((data[offset].toLong() and 0xffL) shl 24) or
            ((data[offset + 1].toLong() and 0xffL) shl 16) or
            ((data[offset + 2].toLong() and 0xffL) shl 8) or
            (data[offset + 3].toLong() and 0xffL)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xff).toByte()
        data[offset + 1] = ((value ushr 16) and 0xff).toByte()
        data[offset + 2] = ((value ushr 8) and 0xff).toByte()
        data[offset + 3] = (value and 0xff).toByte()
    }

    private const val HEADER_SIZE = 12
    private const val RECORD_HEADER_SIZE = 10
    private const val MAX_MESSAGE_SIZE = 65_535
    private const val MAX_RECORDS = 256
    private const val MAX_POINTER_JUMPS = 32
    private const val MAX_CNAME_DEPTH = 16
    private const val MAX_DNS_NAME_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_WIRE_NAME_LENGTH = 255
    private const val FRESH_OPT_SIZE = 11
    private const val IPV4_ONLY_ARPA = "ipv4only.arpa"
    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val IPV4_MAPPED_ZERO_BYTES = 10
    private const val INTERNET_CLASS = 1
    private const val RESPONSE = 0x8000
    private const val TRUNCATED = 0x0200
    private const val AUTHENTICATED_DATA = 0x0020
    private const val CHECKING_DISABLED = 0x0010
    private const val RCODE_MASK = 0x000f
    private const val DNSSEC_OK = 0x00008000L
    private const val DEFAULT_SYNTHETIC_TTL = 600L
    private const val TYPE_NS = 2
    private const val TYPE_MD = 3
    private const val TYPE_MF = 4
    private const val TYPE_CNAME = 5
    private const val TYPE_SOA = 6
    private const val TYPE_MB = 7
    private const val TYPE_MG = 8
    private const val TYPE_MR = 9
    private const val TYPE_PTR = 12
    private const val TYPE_MINFO = 14
    private const val TYPE_TXT = 16
    private const val TYPE_MX = 15
    private const val TYPE_RP = 17
    private const val TYPE_AFSDB = 18
    private const val TYPE_RT = 21
    private const val TYPE_SIG = 24
    private const val TYPE_PX = 26
    private const val TYPE_NXT = 30
    private const val TYPE_SRV = 33
    private const val TYPE_NAPTR = 35
    private const val TYPE_KX = 36
    private const val TYPE_A6 = 38
    private const val TYPE_DNAME = 39
    private const val TYPE_OPT = 41
    private const val TYPE_DS = 43
    private const val TYPE_RRSIG = 46
    private const val TYPE_DNSKEY = 48
    private const val TYPE_NSEC = 47
    private const val TYPE_NSEC3 = 50
    private const val TYPE_NSEC3PARAM = 51
    private const val TYPE_TLSA = 52
    private const val TYPE_SVCB = 64
    private const val TYPE_HTTPS = 65
    private const val TYPE_CAA = 257
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val SOA_INTEGER_BYTES = 20
    private const val SOA_MINIMUM_OFFSET = 16
    private const val RRSIG_FIXED_BYTES = 18
}
