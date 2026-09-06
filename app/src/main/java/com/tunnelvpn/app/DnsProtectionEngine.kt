package com.tunnelvpn.app

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

class DnsProtectionEngine(
    private val resolver: DnsResolver,
    private val diagnostics: DiagnosticsState,
    private val domainBlocker: DomainBlocker = DomainBlocker(enabled = false),
    private val suppressHttpsSvcbRecords: Boolean = false,
    private val suppressIpv6Records: Boolean = false,
    private val turboDomainMapper: TurboDomainMapper? = null,
    turboCandidateDomains: List<String> = emptyList(),
    private val nat64PrefixProvider: () -> Nat64Prefix? = { null },
    private val nat64PrefixesProvider: () -> List<Nat64Prefix> = { listOfNotNull(nat64PrefixProvider()) },
    private val nat64GenerationProvider: () -> Long = { 0L },
    private val networkGenerationProvider: () -> Long = nat64GenerationProvider,
    private val nat64RemainingTtlSecondsProvider: () -> Long? = { null },
    private val nat64DiscoveryRequiredProvider: () -> Boolean = { false },
    private val isLocalName: (String) -> Boolean = DnsPacket::isLocalOnlyName,
    private val maximumPacketSizeProvider: () -> Int = { MAXIMUM_IP_PACKET_SIZE },
    private val beforeTurboMappingCommit: (() -> Unit)? = null
) {
    private data class DnsRequest(
        val sourcePort: Int,
        val query: ByteArray,
        val question: DnsPacket.Question
    )

    private val turboCandidates = turboCandidateDomains.mapNotNull { DomainBlocker.normalize(it) }.toSet()

    suspend fun handleIpv4Packet(packet: ByteArray, length: Int): ByteArray? {
        val request = parseIpv4DnsRequest(packet, length) ?: return null
        val response = resolveResponse(request, maximumUdpPayload(request.query, IPV4_OVERHEAD))
        return buildUdpIpv4Response(packet, request.sourcePort, response)
    }

    suspend fun handleIpv6Packet(packet: ByteArray, length: Int): ByteArray? {
        val request = parseIpv6DnsRequest(packet, length) ?: return null
        val response = resolveResponse(request, maximumUdpPayload(request.query, IPV6_OVERHEAD))
        return buildUdpIpv6Response(packet, request.sourcePort, response)
    }

    suspend fun handleTcpMessage(query: ByteArray): ByteArray? {
        val question = DnsPacket.parseQuestion(query) ?: return null
        return resolveResponse(DnsRequest(0, query, question), MAX_TCP_DNS_PAYLOAD)
    }

    private suspend fun resolveResponse(request: DnsRequest, maximumPayload: Int): ByteArray {
        val query = request.query
        val question = request.question
        if (question.ednsVersion != 0) return DnsPacket.badVersionResponse(query, query.size)
        val requestNetworkGeneration = networkGenerationProvider()
        val requestNat64Generation = nat64GenerationProvider()
        val nat64DiscoveryRequired = nat64DiscoveryRequiredProvider()
        var responseLocalScope = isLocalName(question.domain)
        var pendingTurboAddresses: List<ByteArray>? = null
        var resolverHasRequestedAnswer = false
        if (requestNetworkGeneration and 1L != 0L) return DnsPacket.servFailResponse(query, query.size)
        val turboMappingLease = if (question.type == DnsPacket.TYPE_A && question.qClass == 1 &&
            isTurboCandidate(question.domain)
        ) {
            turboDomainMapper?.acquireLease(requestNetworkGeneration)
        } else {
            null
        }
        if (question.qClass == 1 && nat64DiscoveryRequired &&
            (question.type == DnsPacket.TYPE_AAAA || question.type == DnsPacket.TYPE_SVCB ||
                question.type == DnsPacket.TYPE_HTTPS)
        ) return DnsPacket.servFailResponse(query, query.size)
        nat64PtrResponse(query, question)?.let { response ->
            if (response.size > maximumPayload) return DnsPacket.truncatedResponse(query, query.size)
            return if (isAcceptableFinalResponse(
                    query,
                    question,
                    response,
                    requestNetworkGeneration,
                    requestNat64Generation,
                    nat64DiscoveryRequired,
                    responseLocalScope
                )
            ) response else DnsPacket.servFailResponse(query, query.size)
        }

        val response = try {
            if (domainBlocker.shouldBlock(question.domain)) {
                diagnostics.recordBlockedDns(question.domain)
                DnsPacket.nxDomainResponse(query, query.size)
            } else if ((question.type == DnsPacket.TYPE_SVCB || question.type == DnsPacket.TYPE_HTTPS) &&
                (suppressHttpsSvcbRecords || isTurboCandidate(question.domain))
            ) {
                diagnostics.recordTurboHttpsSvcbSuppressed()
                DnsPacket.noDataResponse(query, query.size)
            } else if (question.type == DnsPacket.TYPE_AAAA && suppressIpv6Records) {
                DnsPacket.noDataResponse(query, query.size)
            } else {
                val resolved = try {
                    resolver.resolve(query, question)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (question.type != DnsPacket.TYPE_AAAA || question.qClass != 1 || !isDnsTimeout(error)) {
                        throw error
                    }
                    diagnostics.recordDnsFailure(resolverFailureCode(error))
                    DnsResolveResult(
                        DnsPacket.servFailResponse(query, query.size),
                        "timeout",
                        0L,
                        false
                    )
                }
                diagnostics.recordDnsQuery(resolved.elapsedMs, resolved.provider, resolved.cacheHit)
                if (resolved.response.size > maximumPayload) {
                    diagnostics.recordDnsFailure("resolver-response-oversized")
                    return DnsPacket.truncatedResponse(query, query.size)
                }
                responseLocalScope = responseLocalScope || resolved.localScope
                val resolvedRcode = Dns64Packet.responseCode(resolved.response)
                val originalMeaning = if (question.type == DnsPacket.TYPE_AAAA &&
                    question.qClass == 1 && resolvedRcode == 0
                ) {
                    runCatching {
                        DnsMessageValidator.validate(query, resolved.response, publicQuery = false).meaning
                    }.getOrNull() ?: return DnsPacket.servFailResponse(query, query.size)
                } else {
                    null
                }
                val sanitizedResolverResponse = if (question.type == DnsPacket.TYPE_AAAA &&
                    question.qClass == 1 && resolvedRcode == 0
                ) {
                    Dns64Packet.withoutIpv4MappedAaaa(query, resolved.response)
                        ?: return DnsPacket.servFailResponse(query, query.size)
                } else {
                    resolved.response
                }
                if (sanitizedResolverResponse !== resolved.response && isDnsSecurityFailure(originalMeaning)) {
                    return DnsPacket.servFailResponse(query, query.size)
                }
                var resolverMeaning = DnsSecurityMeaning.ACCEPTABLE
                if (question.qClass == 1) {
                    val prefixes = (nat64PrefixesProvider() + WELL_KNOWN_NAT64_PREFIX).distinct()
                        .sortedByDescending(Nat64Prefix::length)
                    val localName = responseLocalScope
                    val validated = DnsMessageValidator.validate(
                        query,
                        sanitizedResolverResponse,
                        publicQuery = !localName,
                        allowedLocalAddress = { address ->
                            nat64Match(address, prefixes)?.let { (prefix, embedded) ->
                                DnsMessageValidator.isPublicAddress(embedded) || localName && !prefix.isWellKnown()
                            } == true
                        },
                        forbiddenAddress = { address ->
                            nat64Match(address, prefixes)?.let { (prefix, embedded) ->
                                !isIpv4OnlyDiscoveryAddress(question, embedded) &&
                                    !DnsMessageValidator.isPublicAddress(embedded) &&
                                    (!localName || prefix.isWellKnown())
                            } == true
                        }
                    )
                    resolverMeaning = validated.meaning
                    resolverHasRequestedAnswer = validated.hasRequestedAnswer
                    if (validated.meaning == DnsSecurityMeaning.REBINDING) {
                        return DnsPacket.servFailResponse(query, query.size)
                    }
                }
                val usableResponse = if (question.type == DnsPacket.TYPE_AAAA && question.qClass == 1) {
                    if (resolvedRcode == NAME_ERROR_RCODE && !Dns64Packet.hasQuestion(sanitizedResolverResponse)) {
                        Dns64Packet.retargetResponse(query, sanitizedResolverResponse)
                            ?: DnsPacket.servFailResponse(query, query.size)
                    } else if (resolvedRcode != 0) {
                        sanitizedResolverResponse
                    } else if (containsNat64Rebinding(question, sanitizedResolverResponse, responseLocalScope)) {
                        return DnsPacket.servFailResponse(query, query.size)
                    } else {
                        sanitizedResolverResponse
                    }
                } else {
                    sanitizedResolverResponse
                }
                if (question.type == DnsPacket.TYPE_A && resolverMeaning == DnsSecurityMeaning.ACCEPTABLE &&
                    resolverHasRequestedAnswer &&
                    isTurboCandidate(question.domain)
                ) {
                    pendingTurboAddresses = DnsPacket.extractARecords(usableResponse).takeIf { it.isNotEmpty() }
                }
                if (question.type == DnsPacket.TYPE_AAAA && question.qClass == 1 &&
                    (resolverMeaning == DnsSecurityMeaning.ACCEPTABLE ||
                        resolverMeaning == DnsSecurityMeaning.ERROR) &&
                    Dns64Packet.responseCode(usableResponse) != NAME_ERROR_RCODE &&
                    (Dns64Packet.responseCode(usableResponse) != 0 ||
                        DnsPacket.extractAaaaRecords(usableResponse).isEmpty())
                ) {
                    synthesizeDns64(query, question, usableResponse, responseLocalScope) ?: usableResponse
                } else {
                    usableResponse
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.recordDnsFailure(resolverFailureCode(error))
            DnsPacket.servFailResponse(query, query.size)
        }

        if (networkGenerationProvider() != requestNetworkGeneration ||
            nat64GenerationProvider() != requestNat64Generation
        ) return DnsPacket.servFailResponse(query, query.size)
        var finalResponse = response
        pendingTurboAddresses?.let { addresses ->
            beforeTurboMappingCommit?.invoke()
            val mapping = turboMappingLease?.let { lease ->
                turboDomainMapper?.map(question.domain, addresses, lease = lease)
            }
            if (mapping != null) {
                finalResponse = DnsPacket.aRecordResponse(query, query.size, mapping.virtualAddress)
            }
        }
        if (networkGenerationProvider() != requestNetworkGeneration ||
            nat64GenerationProvider() != requestNat64Generation
        ) return DnsPacket.servFailResponse(query, query.size)
        if (finalResponse.size > maximumPayload) {
            diagnostics.recordDnsFailure("resolver-response-oversized")
            return DnsPacket.truncatedResponse(query, query.size)
        }
        return if (isAcceptableFinalResponse(
                query,
                question,
                finalResponse,
                requestNetworkGeneration,
                requestNat64Generation,
                nat64DiscoveryRequired,
                responseLocalScope
            )
        ) finalResponse else DnsPacket.servFailResponse(query, query.size)
    }

    private fun isAcceptableFinalResponse(
        query: ByteArray,
        question: DnsPacket.Question,
        response: ByteArray,
        networkGeneration: Long,
        nat64Generation: Long,
        nat64DiscoveryWasRequired: Boolean,
        localScope: Boolean
    ): Boolean {
        if (networkGeneration and 1L != 0L ||
            networkGenerationProvider() != networkGeneration ||
            nat64GenerationProvider() != nat64Generation
        ) return false
        if (question.qClass != 1) return true
        val prefixes = (nat64PrefixesProvider() + WELL_KNOWN_NAT64_PREFIX).distinct()
            .sortedByDescending(Nat64Prefix::length)
        val localName = localScope
        val validated = runCatching {
            DnsMessageValidator.validate(
                query,
                response,
                publicQuery = !localName,
                allowedLocalAddress = { address ->
                    val generatedMapping = turboDomainMapper?.lookupVirtual(address)?.domain ==
                        DomainBlocker.normalize(question.domain)
                    generatedMapping || nat64Match(address, prefixes)?.let { (prefix, embedded) ->
                        DnsMessageValidator.isPublicAddress(embedded) || localName && !prefix.isWellKnown()
                    } == true
                },
                forbiddenAddress = { address ->
                    nat64Match(address, prefixes)?.let { (prefix, embedded) ->
                        !isIpv4OnlyDiscoveryAddress(question, embedded) &&
                            !DnsMessageValidator.isPublicAddress(embedded) &&
                            (!localName || prefix.isWellKnown())
                    } == true
                }
            )
        }.getOrNull() ?: return false
        val unsafeUnknownPrefixAddress = validated.containsIpv6Address &&
            (nat64DiscoveryWasRequired || nat64DiscoveryRequiredProvider())
        val unsafeSecurityMeaning = DnsMessageValidator.isSecurityHardStop(validated.meaning)
        return !unsafeUnknownPrefixAddress &&
            !unsafeSecurityMeaning &&
            networkGenerationProvider() == networkGeneration &&
            networkGeneration and 1L == 0L &&
            nat64GenerationProvider() == nat64Generation
    }

    private fun maximumUdpPayload(query: ByteArray, packetOverhead: Int): Int {
        val pathLimit = (maximumPacketSizeProvider() - packetOverhead).coerceIn(512, MAX_TCP_DNS_PAYLOAD)
        return minOf(pathLimit, DnsPacket.udpPayloadSize(query))
    }

    private fun containsNat64Rebinding(
        question: DnsPacket.Question,
        response: ByteArray,
        localScope: Boolean
    ): Boolean {
        val networkGeneration = networkGenerationProvider()
        val nat64Generation = nat64GenerationProvider()
        if (networkGeneration and 1L != 0L) return true
        val prefixes = (nat64PrefixesProvider() + WELL_KNOWN_NAT64_PREFIX).distinct()
        if (networkGenerationProvider() != networkGeneration ||
            nat64GenerationProvider() != nat64Generation
        ) return true
        val discoveryName = question.domain == IPV4_ONLY_ARPA
        val orderedPrefixes = prefixes.sortedByDescending(Nat64Prefix::length)
        val rebinding = DnsPacket.extractAaaaRecords(response).any { address ->
            val match = orderedPrefixes.firstNotNullOfOrNull { prefix ->
                prefix.extractIpv4(address)?.let { embedded -> prefix to embedded }
            }
                ?: return@any false
            val (prefix, embedded) = match
            val discoveryWka = discoveryName && embedded[0] == 192.toByte() &&
                embedded[1].toInt() == 0 && embedded[2].toInt() == 0 &&
                (embedded[3] == 170.toByte() || embedded[3] == 171.toByte())
            !discoveryWka && !DnsMessageValidator.isPublicAddress(embedded) &&
                (!localScope || prefix.isWellKnown())
        }
        return rebinding || networkGenerationProvider() != networkGeneration ||
            nat64GenerationProvider() != nat64Generation
    }

    private fun nat64Match(
        address: ByteArray,
        prefixes: List<Nat64Prefix>
    ): Pair<Nat64Prefix, ByteArray>? {
        if (address.size != 16) return null
        return prefixes.firstNotNullOfOrNull { prefix ->
            prefix.extractIpv4(address)?.let { embedded -> prefix to embedded }
        }
    }

    private fun isIpv4OnlyDiscoveryAddress(question: DnsPacket.Question, address: ByteArray): Boolean {
        return question.domain == IPV4_ONLY_ARPA && address.size == 4 &&
            address[0] == 192.toByte() && address[1].toInt() == 0 && address[2].toInt() == 0 &&
            (address[3] == 170.toByte() || address[3] == 171.toByte())
    }

    private suspend fun nat64PtrResponse(query: ByteArray, question: DnsPacket.Question): ByteArray? {
        if (question.type != DnsPacket.TYPE_PTR || question.qClass != 1 ||
            (Dns64Packet.hasDnssecOk(query) && Dns64Packet.checkingDisabled(query))
        ) return null
        val networkGeneration = networkGenerationProvider()
        val nat64Generation = nat64GenerationProvider()
        if (networkGeneration and 1L != 0L) return null
        val prefixes = nat64PrefixesProvider().distinct()
        val prefixTtl = nat64RemainingTtlSecondsProvider()
        if (prefixTtl != null && prefixTtl <= 0L) {
            return if (nat64DiscoveryRequiredProvider()) {
                DnsPacket.servFailResponse(query, query.size)
            } else {
                null
            }
        }
        if (networkGenerationProvider() != networkGeneration ||
            nat64GenerationProvider() != nat64Generation
        ) return null
        val address = parseIp6Arpa(question.domain) ?: return null
        val match = prefixes.sortedByDescending(Nat64Prefix::length)
            .firstNotNullOfOrNull { prefix ->
                prefix.extractIpv4(address)?.let { extracted -> prefix to extracted }
            }
            ?: return null
        val (matchedPrefix, ipv4) = match
        if (matchedPrefix.isWellKnown() && !DnsMessageValidator.isPublicAddress(ipv4)) return null
        if (networkGenerationProvider() != networkGeneration ||
            nat64GenerationProvider() != nat64Generation
        ) return null
        val target = ipv4.reversed().joinToString(".") { byte -> (byte.toInt() and 0xff).toString() } +
            ".in-addr.arpa"
        val targetQuery = DnsPacket.relatedNameQuery(query, target, DnsPacket.TYPE_PTR) ?: return null
        val targetQuestion = DnsPacket.parseQuestion(targetQuery) ?: return null
        val targetResult = try {
            resolver.resolve(targetQuery, targetQuestion)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        diagnostics.recordDnsQuery(targetResult.elapsedMs, targetResult.provider, targetResult.cacheHit)
        val latestPrefixTtl = nat64RemainingTtlSecondsProvider()
        if (prefixTtl != null && (latestPrefixTtl == null || latestPrefixTtl <= 0L)) {
            return if (nat64DiscoveryRequiredProvider()) {
                DnsPacket.servFailResponse(query, query.size)
            } else {
                null
            }
        }
        if (networkGenerationProvider() != networkGeneration ||
            nat64GenerationProvider() != nat64Generation
        ) return null
        val response = Dns64Packet.nat64PtrResponse(
            query,
            targetQuery,
            targetResult.response,
            target,
            minOf(prefixTtl ?: 600L, latestPrefixTtl ?: 600L, 600L)
        ) ?: return null
        return response.takeIf {
            networkGenerationProvider() == networkGeneration &&
                networkGeneration and 1L == 0L &&
                nat64GenerationProvider() == nat64Generation
        }
    }

    private fun parseIp6Arpa(domain: String): ByteArray? {
        val labels = domain.lowercase().trimEnd('.').split('.')
        if (labels.size != IPV6_REVERSE_LABELS + 2 ||
            labels[IPV6_REVERSE_LABELS] != "ip6" || labels[IPV6_REVERSE_LABELS + 1] != "arpa"
        ) return null
        val nibbles = labels.take(IPV6_REVERSE_LABELS)
        if (nibbles.any { it.length != 1 || it[0].digitToIntOrNull(16) == null }) return null
        val forward = nibbles.asReversed()
        return ByteArray(16) { index ->
            ((forward[index * 2][0].digitToInt(16) shl 4) or
                forward[index * 2 + 1][0].digitToInt(16)).toByte()
        }
    }

    private suspend fun synthesizeDns64(
        query: ByteArray,
        question: DnsPacket.Question,
        negativeAaaaResponse: ByteArray,
        localScope: Boolean
    ): ByteArray? {
        val networkGeneration = networkGenerationProvider()
        val nat64Generation = nat64GenerationProvider()
        val prefixes = nat64PrefixesProvider().distinct()
        val prefixTtl = nat64RemainingTtlSecondsProvider()
        if (prefixes.isEmpty()) {
            return if (nat64DiscoveryRequiredProvider()) {
                DnsPacket.servFailResponse(query, query.size)
            } else {
                null
            }
        }
        if (prefixTtl != null && prefixTtl <= 0L) {
            return if (nat64DiscoveryRequiredProvider()) {
                DnsPacket.servFailResponse(query, query.size)
            } else {
                null
            }
        }
        if (networkGeneration and 1L != 0L ||
            networkGenerationProvider() != networkGeneration ||
            nat64GenerationProvider() != nat64Generation
        ) return null
        if (Dns64Packet.hasDnssecOk(query)) return null
        val aQuery = Dns64Packet.relatedQuery(query, DnsPacket.TYPE_A) ?: return null
        val aQuestion = DnsPacket.parseQuestion(aQuery) ?: return null
        val resolved = resolver.resolve(aQuery, aQuestion)
        diagnostics.recordDnsQuery(resolved.elapsedMs, resolved.provider, resolved.cacheHit)
        if (networkGenerationProvider() != networkGeneration ||
            networkGeneration and 1L != 0L ||
            nat64GenerationProvider() != nat64Generation
        ) return null
        val latestPrefixTtl = nat64RemainingTtlSecondsProvider()
        if (prefixTtl != null && (latestPrefixTtl == null || latestPrefixTtl <= 0L)) {
            return if (nat64DiscoveryRequiredProvider()) {
                DnsPacket.servFailResponse(query, query.size)
            } else {
                null
            }
        }
        if (networkGenerationProvider() != networkGeneration ||
            nat64GenerationProvider() != nat64Generation
        ) return null
        val validatedA = runCatching {
            DnsMessageValidator.validate(aQuery, resolved.response, publicQuery = !localScope)
        }.getOrNull() ?: return negativeAaaaResponse
        if (DnsMessageValidator.isSecurityHardStop(validatedA.meaning)) {
            return DnsPacket.servFailResponse(query, query.size)
        }
        if (Dns64Packet.responseCode(resolved.response) != 0) {
            return Dns64Packet.retargetResponse(query, resolved.response)
                ?: DnsPacket.servFailResponse(query, query.size)
        }
        if (validatedA.meaning != DnsSecurityMeaning.ACCEPTABLE || !validatedA.hasRequestedAnswer) {
            return negativeAaaaResponse
        }
        val synthesized = Dns64Packet.synthesize(
            query = query,
            negativeAaaaResponse = negativeAaaaResponse,
            aResponse = resolved.response,
            prefixes = prefixes,
            authenticated = false,
            maximumSyntheticTtl = minOf(prefixTtl ?: Long.MAX_VALUE, latestPrefixTtl ?: Long.MAX_VALUE)
        )
        return synthesized?.takeIf {
            networkGenerationProvider() == networkGeneration &&
                networkGeneration and 1L == 0L &&
                nat64GenerationProvider() == nat64Generation
        } ?: negativeAaaaResponse
    }

    private fun isDnsTimeout(error: Throwable): Boolean {
        return DnsTimeoutClassifier.isTimeout(error)
    }

    private fun isDnsSecurityFailure(meaning: DnsSecurityMeaning?): Boolean {
        return meaning != null && DnsMessageValidator.isSecurityHardStop(meaning)
    }


    fun buildCapacityServFailIpv4Packet(packet: ByteArray, length: Int): ByteArray? {
        val request = parseIpv4DnsRequest(packet, length) ?: return null
        diagnostics.recordDnsFailure("resolver-capacity")
        return buildUdpIpv4Response(
            packet,
            request.sourcePort,
            DnsPacket.servFailResponse(request.query, request.query.size)
        )
    }

    fun buildCapacityServFailIpv6Packet(packet: ByteArray, length: Int): ByteArray? {
        val request = parseIpv6DnsRequest(packet, length) ?: return null
        diagnostics.recordDnsFailure("resolver-capacity")
        return buildUdpIpv6Response(
            packet,
            request.sourcePort,
            DnsPacket.servFailResponse(request.query, request.query.size)
        )
    }

    private fun parseIpv4DnsRequest(packet: ByteArray, length: Int): DnsRequest? {
        if (length !in 28..packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0x0f
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (version != 4 || ihl < 20 || length < ihl + 8) return null
        if ((packet[9].toInt() and 0xff) != UDP_PROTOCOL) return null
        val fragmentField = u16(packet, 6)
        if (fragmentField and 0x3fff != 0) return null
        val totalLength = u16(packet, 2)
        if (totalLength != length) return null
        val udpOffset = ihl
        val sourcePort = u16(packet, udpOffset)
        val destPort = u16(packet, udpOffset + 2)
        if (destPort != DNS_PORT) return null
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < 8 || udpLength != totalLength - udpOffset) return null
        val dnsOffset = udpOffset + 8
        val dnsLength = udpLength - 8
        val query = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val question = DnsPacket.parseQuestion(query) ?: return null
        return DnsRequest(sourcePort, query, question)
    }

    private fun parseIpv6DnsRequest(packet: ByteArray, length: Int): DnsRequest? {
        if (length !in (IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH)..packet.size) return null
        if (((packet[0].toInt() ushr 4) and 0x0f) != IPV6_VERSION) return null
        if ((packet[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != UDP_PROTOCOL) return null
        val payloadLength = u16(packet, IPV6_PAYLOAD_LENGTH_OFFSET)
        if (IPV6_HEADER_LENGTH + payloadLength != length || payloadLength < UDP_HEADER_LENGTH) return null
        if (!Ipv6FullForwardRoutePolicy.isProxyableUnicast(packet, IPV6_SOURCE_OFFSET) ||
            !Ipv6FullForwardRoutePolicy.isProxyableUnicast(packet, IPV6_DESTINATION_OFFSET)
        ) return null
        val udpOffset = IPV6_HEADER_LENGTH
        val sourcePort = u16(packet, udpOffset)
        if (u16(packet, udpOffset + 2) != DNS_PORT) return null
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_LENGTH || udpLength != payloadLength) return null
        if (!Ipv6TransportChecksum.isValid(
                packet = packet,
                packetLength = length,
                transportOffset = udpOffset,
                transportLength = udpLength,
                protocol = UDP_PROTOCOL,
                rejectZeroChecksumAt = UDP_CHECKSUM_OFFSET
            )
        ) return null
        val dnsOffset = udpOffset + UDP_HEADER_LENGTH
        val query = packet.copyOfRange(dnsOffset, udpOffset + udpLength)
        val question = DnsPacket.parseQuestion(query) ?: return null
        return DnsRequest(sourcePort, query, question)
    }

    private fun isTurboCandidate(domain: String): Boolean {
        if (turboDomainMapper == null) return false
        val normalized = DomainBlocker.normalize(domain) ?: return false
        return normalized in turboCandidates || turboCandidates.any { normalized.endsWith(".$it") }
    }

    private fun resolverFailureCode(error: Exception): String {
        return when (error) {
            is UnknownHostException -> "unknown-host"
            is SocketTimeoutException -> "resolver-timeout"
            is IOException -> "resolver-io"
            else -> "resolver-failure"
        }
    }

    private fun buildUdpIpv4Response(request: ByteArray, requestSourcePort: Int, dns: ByteArray): ByteArray {
        require(dns.size <= MAX_TCP_DNS_PAYLOAD - IPV4_OVERHEAD)
        val totalLength = IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH + dns.size
        val response = ByteArray(totalLength)
        response[0] = 0x45
        response[1] = 0
        put16(response, 2, totalLength)
        put16(response, 4, u16(request, 4))
        put16(response, 6, 0)
        response[8] = 64
        response[9] = UDP_PROTOCOL.toByte()
        System.arraycopy(request, 16, response, 12, 4)
        System.arraycopy(request, 12, response, 16, 4)
        put16(response, 10, checksum(response, 0, IPV4_HEADER_LENGTH))

        val udpOffset = IPV4_HEADER_LENGTH
        put16(response, udpOffset, DNS_PORT)
        put16(response, udpOffset + 2, requestSourcePort)
        put16(response, udpOffset + 4, UDP_HEADER_LENGTH + dns.size)
        put16(response, udpOffset + 6, 0)
        System.arraycopy(dns, 0, response, udpOffset + UDP_HEADER_LENGTH, dns.size)
        put16(response, udpOffset + 6, udpChecksum(response, udpOffset, UDP_HEADER_LENGTH + dns.size))
        return response
    }

    private fun buildUdpIpv6Response(request: ByteArray, requestSourcePort: Int, dns: ByteArray): ByteArray {
        require(dns.size <= MAX_TCP_DNS_PAYLOAD - IPV6_OVERHEAD)
        val udpLength = UDP_HEADER_LENGTH + dns.size
        val response = ByteArray(IPV6_HEADER_LENGTH + udpLength)
        response[0] = 0x60
        put16(response, IPV6_PAYLOAD_LENGTH_OFFSET, udpLength)
        response[IPV6_NEXT_HEADER_OFFSET] = UDP_PROTOCOL.toByte()
        response[IPV6_HOP_LIMIT_OFFSET] = DEFAULT_HOP_LIMIT.toByte()
        request.copyInto(
            response,
            IPV6_SOURCE_OFFSET,
            IPV6_DESTINATION_OFFSET,
            IPV6_DESTINATION_OFFSET + IPV6_ADDRESS_LENGTH
        )
        request.copyInto(
            response,
            IPV6_DESTINATION_OFFSET,
            IPV6_SOURCE_OFFSET,
            IPV6_SOURCE_OFFSET + IPV6_ADDRESS_LENGTH
        )

        val udpOffset = IPV6_HEADER_LENGTH
        put16(response, udpOffset, DNS_PORT)
        put16(response, udpOffset + 2, requestSourcePort)
        put16(response, udpOffset + 4, udpLength)
        put16(response, udpOffset + 6, 0)
        dns.copyInto(response, udpOffset + UDP_HEADER_LENGTH)
        put16(response, udpOffset + 6, ipv6UdpChecksum(response, udpOffset, udpLength))
        return response
    }

    private fun udpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        sum += u16(packet, 12).toLong()
        sum += u16(packet, 14).toLong()
        sum += u16(packet, 16).toLong()
        sum += u16(packet, 18).toLong()
        sum += UDP_PROTOCOL.toLong()
        sum += udpLength.toLong()
        sum += checksumSum(packet, udpOffset, udpLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        val value = sum.inv().toInt() and 0xffff
        return if (value == 0) 0xffff else value
    }

    private fun ipv6UdpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        sum += checksumSum(packet, IPV6_SOURCE_OFFSET, IPV6_ADDRESS_LENGTH * 2)
        sum += udpLength.toLong()
        sum += UDP_PROTOCOL.toLong()
        sum += checksumSum(packet, udpOffset, udpLength)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        val value = sum.inv().toInt() and 0xffff
        return if (value == 0) 0xffff else value
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = checksumSum(data, offset, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffffL) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun checksumSum(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += u16(data, index).toLong()
            index += 2
        }
        if (index < end) sum += ((data[index].toInt() and 0xff) shl 8).toLong()
        return sum
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    companion object {
        private val WELL_KNOWN_NAT64_PREFIX = Nat64Prefix.from(
            byteArrayOf(
                0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0
            ),
            96
        )!!
        private const val NAME_ERROR_RCODE = 3
        private const val IPV6_REVERSE_LABELS = 32
        private const val IPV4_ONLY_ARPA = "ipv4only.arpa"
        private const val UDP_PROTOCOL = 17
        private const val DNS_PORT = 53
        private const val IPV4_HEADER_LENGTH = 20
        private const val IPV6_VERSION = 6
        private const val IPV6_HEADER_LENGTH = 40
        private const val IPV6_ADDRESS_LENGTH = 16
        private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
        private const val IPV6_NEXT_HEADER_OFFSET = 6
        private const val IPV6_HOP_LIMIT_OFFSET = 7
        private const val IPV6_SOURCE_OFFSET = 8
        private const val IPV6_DESTINATION_OFFSET = 24
        private const val DEFAULT_HOP_LIMIT = 64
        private const val UDP_HEADER_LENGTH = 8
        private const val UDP_CHECKSUM_OFFSET = 6
        private const val MAX_TCP_DNS_PAYLOAD = 65_535
        private const val MAXIMUM_IP_PACKET_SIZE = 65_535
        private const val IPV4_OVERHEAD = IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH
        private const val IPV6_OVERHEAD = IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH
    }
}
