package com.tunnelvpn.app

object TlsClientHello {
    data class Analysis(
        val complete: Boolean,
        val clientHello: Boolean,
        val sni: String?,
        val sniExtensionOffset: Int?,
        val sniHostNameOffset: Int? = null,
        val sniHostNameEnd: Int? = null,
        val sniHostNameWireOffsets: List<Int>? = null,
        val malformed: Boolean = false,
        val clientHelloRecordEnd: Int? = null
    )

    data class FragmentationPlan(
        val splitPoints: List<Int>,
        val strategy: String,
        val mode: FragmentationMode = FragmentationMode.SNI_MULTI
    )

    enum class FragmentationMode(
        val id: Int,
        val strategy: String
    ) {
        NONE(0, "clienthello-no-fragment"),
        SINGLE_SAFE(1, "clienthello-single-safe-split"),
        HOSTNAME(2, "clienthello-hostname-split"),
        SNI_MULTI(3, "clienthello-sni-multi-split")
    }

    fun analyze(data: ByteArray, length: Int = data.size): Analysis {
        if (length < 0 || length > data.size) return emptyAnalysis(malformed = true)
        if (length < TLS_RECORD_HEADER_LENGTH) return emptyAnalysis()
        if ((data[0].toInt() and 0xff) != TLS_HANDSHAKE) {
            return Analysis(complete = true, clientHello = false, sni = null, sniExtensionOffset = null)
        }
        val handshake = ByteArray(MAX_CLIENT_HELLO_BYTES)
        val sourceOffsets = IntArray(MAX_CLIENT_HELLO_BYTES)
        var handshakeSize = 0
        var handshakeEnd = -1
        var clientHelloRecordEnd = -1
        var recordOffset = 0
        var recordCount = 0
        while (true) {
            if (recordOffset + TLS_RECORD_HEADER_LENGTH > length) {
                return emptyAnalysis(clientHello = handshakeSize >= 1 && (handshake[0].toInt() and 0xff) == CLIENT_HELLO)
            }
            if ((data[recordOffset].toInt() and 0xff) != TLS_HANDSHAKE) {
                return emptyAnalysis(complete = true, clientHello = handshakeEnd >= 0, malformed = true)
            }
            recordCount += 1
            if (recordCount > MAX_HANDSHAKE_RECORDS) {
                return emptyAnalysis(complete = true, clientHello = handshakeEnd >= 0, malformed = true)
            }
            val recordLength = u16(data, recordOffset + 3)
            if (recordLength !in 1..MAX_TLS_RECORD_BYTES) {
                return emptyAnalysis(complete = true, clientHello = handshakeEnd >= 0, malformed = true)
            }
            val recordEnd = recordOffset + TLS_RECORD_HEADER_LENGTH + recordLength
            if (recordEnd > length) {
                return emptyAnalysis(clientHello = handshakeEnd >= 0)
            }
            var source = recordOffset + TLS_RECORD_HEADER_LENGTH
            while (source < recordEnd && (handshakeEnd < 0 || handshakeSize < handshakeEnd)) {
                if (handshakeSize >= MAX_CLIENT_HELLO_BYTES) {
                    return emptyAnalysis(complete = true, clientHello = true, malformed = true)
                }
                handshake[handshakeSize] = data[source]
                sourceOffsets[handshakeSize] = source
                handshakeSize += 1
                source += 1
                if (handshakeSize == 1 && (handshake[0].toInt() and 0xff) != CLIENT_HELLO) {
                    return Analysis(complete = true, clientHello = false, sni = null, sniExtensionOffset = null)
                }
                if (handshakeSize == HANDSHAKE_HEADER_LENGTH) {
                    handshakeEnd = HANDSHAKE_HEADER_LENGTH + u24(handshake, 1)
                    if (handshakeEnd !in MIN_CLIENT_HELLO_BYTES..MAX_CLIENT_HELLO_BYTES) {
                        return emptyAnalysis(complete = true, clientHello = true, malformed = true)
                    }
                }
            }
            if (handshakeEnd >= 0 && handshakeSize >= handshakeEnd) {
                clientHelloRecordEnd = recordEnd
                break
            }
            recordOffset = recordEnd
        }
        val parsed = parseSni(handshake, handshakeEnd)
        if (parsed.malformed) return emptyAnalysis(complete = true, clientHello = true, malformed = true)
        val sni = parsed.info
        return Analysis(
            complete = true,
            clientHello = true,
            sni = sni?.host,
            sniExtensionOffset = sni?.extensionOffset?.let { sourceOffsets[it] },
            sniHostNameOffset = sni?.hostNameOffset?.let { sourceOffsets[it] },
            sniHostNameEnd = sni?.hostNameEnd?.let { sourceOffsets[it - 1] + 1 },
            sniHostNameWireOffsets = sni?.let { info ->
                (info.hostNameOffset until info.hostNameEnd).map { sourceOffsets[it] }
            },
            clientHelloRecordEnd = clientHelloRecordEnd
        )
    }

    private fun emptyAnalysis(
        complete: Boolean = false,
        clientHello: Boolean = false,
        malformed: Boolean = false
    ): Analysis {
        return Analysis(complete, clientHello, null, null, malformed = malformed)
    }

    fun fragmentationOffset(data: ByteArray, length: Int = data.size): Int? {
        val analysis = analyze(data, length)
        if (!analysis.complete || !analysis.clientHello || analysis.malformed || length < 2) return null
        return analysis.sniExtensionOffset
            ?.coerceIn(1, length - 1)
            ?: minOf(32, length - 1).coerceAtLeast(1)
    }

    fun splitIntoTlsRecords(data: ByteArray, length: Int = data.size, splitPoints: List<Int>): List<ByteArray>? {
        if (length !in (TLS_RECORD_HEADER_LENGTH + 1)..data.size) return null
        if ((data[0].toInt() and 0xff) != TLS_HANDSHAKE) return null
        val requestedBoundaries = splitPoints
            .filter { it in 1 until length }
            .distinct()
            .sorted()
        if (requestedBoundaries.isEmpty()) return null

        val records = ArrayList<ByteArray>(requestedBoundaries.size + 4)
        var recordOffset = 0
        val representedBoundaries = mutableSetOf<Int>()
        while (recordOffset + TLS_RECORD_HEADER_LENGTH <= length) {
            val recordLength = u16(data, recordOffset + 3)
            val recordEndLong = recordOffset.toLong() + TLS_RECORD_HEADER_LENGTH + recordLength.toLong()
            if (recordLength <= 0 || recordEndLong > length.toLong()) {
                if (recordOffset == 0) return null
                break
            }
            val recordEnd = recordEndLong.toInt()
            val bodyStart = recordOffset + TLS_RECORD_HEADER_LENGTH
            val contentType = data[recordOffset].toInt() and 0xff
            val boundaries = if (contentType == TLS_HANDSHAKE) {
                requestedBoundaries.filter { it in (bodyStart + 1) until recordEnd }
            } else {
                emptyList()
            }

            if (requestedBoundaries.any { it == bodyStart || it == recordEnd }) {
                requestedBoundaries.filterTo(representedBoundaries) { it == bodyStart || it == recordEnd }
            }
            if (boundaries.isEmpty()) {
                records += data.copyOfRange(recordOffset, recordEnd)
            } else {
                representedBoundaries += boundaries
                var bodyOffset = bodyStart
                for (boundary in boundaries + recordEnd) {
                    val fragmentLength = boundary - bodyOffset
                    if (fragmentLength <= 0) continue
                    val record = ByteArray(TLS_RECORD_HEADER_LENGTH + fragmentLength)
                    record[0] = data[recordOffset]
                    record[1] = data[recordOffset + 1]
                    record[2] = data[recordOffset + 2]
                    record[3] = ((fragmentLength ushr 8) and 0xff).toByte()
                    record[4] = (fragmentLength and 0xff).toByte()
                    System.arraycopy(data, bodyOffset, record, TLS_RECORD_HEADER_LENGTH, fragmentLength)
                    records += record
                    bodyOffset = boundary
                }
            }
            recordOffset = recordEnd
        }

        if (recordOffset < length) {
            records += data.copyOfRange(recordOffset, length)
        }
        return records.takeIf {
            representedBoundaries.containsAll(requestedBoundaries) && it.isNotEmpty()
        }
    }

    fun fragmentationPlan(data: ByteArray, length: Int = data.size): FragmentationPlan? {
        return fragmentationPlan(data, length, FragmentationMode.SNI_MULTI)
    }

    fun fragmentationPlan(
        data: ByteArray,
        length: Int = data.size,
        mode: FragmentationMode
    ): FragmentationPlan? {
        val analysis = analyze(data, length)
        if (!analysis.complete || !analysis.clientHello || analysis.malformed || length < 2) return null

        val splitPoints = linkedSetOf<Int>()
        fun add(point: Int?) {
            if (point != null && point in MIN_SPLIT_OFFSET until length) splitPoints += point
        }

        if (mode == FragmentationMode.NONE) {
            return FragmentationPlan(emptyList(), mode.strategy, mode)
        }

        if (mode == FragmentationMode.SINGLE_SAFE) {
            add(analysis.sniExtensionOffset ?: minOf(DEFAULT_SAFE_SPLIT_OFFSET, length - 1))
            return FragmentationPlan(splitPoints.sorted(), mode.strategy, mode)
        }

        if (mode == FragmentationMode.HOSTNAME) {
            val hostOffsets = analysis.sniHostNameWireOffsets
            if (hostOffsets != null) {
                if (hostOffsets.size > 1) add(hostOffsets[1])
            } else {
                add(analysis.sniExtensionOffset ?: minOf(DEFAULT_SAFE_SPLIT_OFFSET, length - 1))
            }
            return FragmentationPlan(splitPoints.sorted(), mode.strategy, mode)
        }

        val hostOffsets = analysis.sniHostNameWireOffsets
        if (analysis.sniExtensionOffset != null && !hostOffsets.isNullOrEmpty()) {
            add(analysis.sniExtensionOffset)
            if (hostOffsets.size > 1) add(hostOffsets[1])
            if (hostOffsets.size > 9) add(hostOffsets[hostOffsets.size / 2])
            add(hostOffsets.last() + 1)
            return FragmentationPlan(splitPoints.sorted(), mode.strategy, mode)
        }

        add(minOf(32, length - 1))
        add(minOf(96, length - 1))
        return FragmentationPlan(splitPoints.sorted(), "clienthello-generic-multi-split", mode)
    }

    fun adaptiveModeOrder(preferred: FragmentationMode? = null): List<FragmentationMode> {
        val defaultOrder = listOf(
            FragmentationMode.HOSTNAME,
            FragmentationMode.SINGLE_SAFE,
            FragmentationMode.SNI_MULTI,
            FragmentationMode.NONE
        )
        return if (preferred != null && preferred in defaultOrder) {
            listOf(preferred) + defaultOrder.filterNot { it == preferred }
        } else {
            defaultOrder
        }
    }

    private data class SniInfo(
        val host: String,
        val extensionOffset: Int,
        val hostNameOffset: Int,
        val hostNameEnd: Int
    )

    private data class SniParseResult(val info: SniInfo?, val malformed: Boolean)

    private fun parseSni(data: ByteArray, handshakeEnd: Int): SniParseResult {
        var offset = HANDSHAKE_HEADER_LENGTH
        if (offset + 2 + 32 > handshakeEnd) return SniParseResult(null, true)
        offset += 2 + 32
        if (offset + 1 > handshakeEnd) return SniParseResult(null, true)
        val sessionIdLength = data[offset].toInt() and 0xff
        offset += 1 + sessionIdLength
        if (offset + 2 > handshakeEnd) return SniParseResult(null, true)
        val cipherLength = u16(data, offset)
        if (cipherLength < 2 || cipherLength and 1 != 0) return SniParseResult(null, true)
        offset += 2 + cipherLength
        if (offset + 1 > handshakeEnd) return SniParseResult(null, true)
        val compressionLength = data[offset].toInt() and 0xff
        if (compressionLength < 1) return SniParseResult(null, true)
        offset += 1 + compressionLength
        if (offset == handshakeEnd) return SniParseResult(null, false)
        if (offset + 2 > handshakeEnd) return SniParseResult(null, true)
        val extensionsEnd = offset + 2 + u16(data, offset)
        offset += 2
        if (extensionsEnd != handshakeEnd) return SniParseResult(null, true)

        var foundSni: SniInfo? = null
        var sniSeen = false
        while (offset + 4 <= extensionsEnd) {
            val extensionOffset = offset
            val type = u16(data, offset)
            val length = u16(data, offset + 2)
            offset += 4
            if (offset + length > extensionsEnd) return SniParseResult(null, true)
            if (type == SNI_EXTENSION_TYPE) {
                if (sniSeen) return SniParseResult(null, true)
                sniSeen = true
                if (length < 5) return SniParseResult(null, true)
                val listLength = u16(data, offset)
                if (listLength != length - 2) return SniParseResult(null, true)
                var nameOffset = offset + 2
                val listEnd = nameOffset + listLength
                var hostInfo: SniInfo? = null
                var hostNameCount = 0
                while (nameOffset < listEnd) {
                    if (nameOffset + 3 > listEnd) return SniParseResult(null, true)
                    val nameType = data[nameOffset].toInt() and 0xff
                    val nameLength = u16(data, nameOffset + 1)
                    nameOffset += 3
                    if (nameLength == 0 || nameOffset + nameLength > listEnd) return SniParseResult(null, true)
                    if (nameType == HOST_NAME_TYPE) {
                        hostNameCount += 1
                        if (hostNameCount > 1) return SniParseResult(null, true)
                        val hostOffset = nameOffset
                        val hostEnd = nameOffset + nameLength
                        if ((hostOffset until hostEnd).any { (data[it].toInt() and 0xff) !in 0x21..0x7e }) {
                            return SniParseResult(null, true)
                        }
                        val host = String(data, nameOffset, nameLength, Charsets.US_ASCII)
                            .lowercase()
                            .trimEnd('.')
                        if (DomainBlocker.normalize(host) != null) {
                            hostInfo = SniInfo(
                                host = host,
                                extensionOffset = extensionOffset,
                                hostNameOffset = hostOffset,
                                hostNameEnd = hostEnd
                            )
                        }
                    }
                    nameOffset += nameLength
                }
                foundSni = hostInfo
            }
            offset += length
        }
        return SniParseResult(foundSni, offset != extensionsEnd)
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun u24(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 16) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            (data[offset + 2].toInt() and 0xff)
    }

    private const val TLS_RECORD_HEADER_LENGTH = 5
    private const val HANDSHAKE_HEADER_LENGTH = 4
    private const val MIN_CLIENT_HELLO_BYTES = 42
    private const val MAX_CLIENT_HELLO_BYTES = 65_539
    private const val MAX_TLS_RECORD_BYTES = 18_432
    private const val MAX_HANDSHAKE_RECORDS = 32
    private const val MIN_SPLIT_OFFSET = TLS_RECORD_HEADER_LENGTH + 1
    private const val DEFAULT_SAFE_SPLIT_OFFSET = 96
    private const val TLS_HANDSHAKE = 22
    private const val CLIENT_HELLO = 1
    private const val SNI_EXTENSION_TYPE = 0
    private const val HOST_NAME_TYPE = 0
}
