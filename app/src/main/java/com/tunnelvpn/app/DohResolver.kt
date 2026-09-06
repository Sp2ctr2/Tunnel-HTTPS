package com.tunnelvpn.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

data class DohProvider(
    val name: String,
    val url: String,
    val bootstrapAddresses: List<InetAddress> = emptyList()
)

data class DnsResolveResult(
    val response: ByteArray,
    val provider: String,
    val elapsedMs: Long,
    val cacheHit: Boolean,
    val localScope: Boolean = false
)

interface DnsResolver {
    suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult

    fun onNetworkChanged() {}

    fun close() {}
}

class LocalFirstDnsResolver(
    private val publicResolver: DnsResolver,
    private val localResolver: DnsResolver,
    localSuffixes: List<String> = emptyList(),
    private val localSuffixProvider: () -> Set<String> = { emptySet() }
) : DnsResolver {
    private val localNames = localSuffixes.mapNotNull(DomainBlocker::normalize).toSet()

    override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
        val normalized = DomainBlocker.normalize(question.domain)
        val explicitlyLocal = normalized != null && (localNames + localSuffixProvider()).any {
            normalized == it || normalized.endsWith(".$it")
        }
        if (DnsPacket.isLocalOnlyName(question.domain)) {
            return localResolver.resolve(query, question).copy(localScope = true)
        }
        if (!explicitlyLocal) return resolvePublic(query, question)
        val publicResult = try {
            resolvePublic(query, question)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (publicResult != null) {
            val validation = runCatching {
                DnsMessageValidator.validate(query, publicResult.response)
            }.getOrNull()
            if (validation != null && (validation.hasRequestedAnswer || validation.cnameDepth > 0 ||
                    validation.meaning != DnsSecurityMeaning.ACCEPTABLE &&
                    validation.meaning != DnsSecurityMeaning.NXDOMAIN)
            ) return publicResult
        }
        return localResolver.resolve(query, question).copy(localScope = true)
    }

    private suspend fun resolvePublic(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
        return try {
            withTimeout(PUBLIC_RESOLVER_TIMEOUT_MS) {
                publicResolver.resolve(query, question)
            }
        } catch (error: TimeoutCancellationException) {
            throw SocketTimeoutException("encrypted DNS resolver timed out").apply {
                initCause(error)
            }
        } catch (error: CancellationException) {
            throw error
        }
    }

    override fun onNetworkChanged() {
        publicResolver.onNetworkChanged()
        localResolver.onNetworkChanged()
    }

    override fun close() {
        publicResolver.close()
        localResolver.close()
    }

    private companion object {
        const val PUBLIC_RESOLVER_TIMEOUT_MS = 7_000L
    }
}

class DohResolver(
    private val cache: DnsCache,
    providers: List<DohProvider> = DEFAULT_PROVIDERS,
    protectSocket: (Socket) -> Boolean = { true },
    bootstrapLookup: (String) -> List<InetAddress> = { hostname -> Dns.SYSTEM.lookup(hostname) },
    bootstrapAddressTransform: (List<InetAddress>) -> List<InetAddress> = { it },
    preferIpv6Bootstrap: () -> Boolean = { false }
) : DnsResolver {
    private class StaleNetworkException : IOException("stale-network-generation")

    private val providers = providers.toList()
    private val bootstrapDns = BootstrapDns(
        this.providers,
        bootstrapLookup,
        bootstrapAddressTransform,
        preferIpv6Bootstrap
    )
    private val client = OkHttpClient.Builder()
        .dns(bootstrapDns)
        .socketFactory(ProtectedSocketFactory(SocketFactory.getDefault(), protectSocket))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .writeTimeout(3000, TimeUnit.MILLISECONDS)
        .callTimeout(6000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var activeProviderIndex = 0
    private val networkGeneration = AtomicLong()
    private val networkLock = Any()

    val activeProviderName: String
        get() = providers.getOrNull(activeProviderIndex)?.name ?: "none"

    override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult {
        val parsedQuestion = DnsPacket.parseQuestion(query) ?: throw IOException("invalid-dns-query")
        if (parsedQuestion != question) throw IOException("dns-question-mismatch")
        if (DnsPacket.isLocalOnlyName(parsedQuestion.domain)) throw IOException("local-name-not-public-doh")
        val requestGeneration = networkGeneration.get()
        synchronized(networkLock) {
            if (networkGeneration.get() != requestGeneration) throw StaleNetworkException()
            cache.get(parsedQuestion.key, query)?.let {
                return DnsResolveResult(it, activeProviderName, 0L, cacheHit = true)
            }
            if (cache.isFailureCached(parsedQuestion.key)) throw IOException("cached DNS failure")
        }

        val start = System.nanoTime()
        val failures = mutableListOf<Exception>()
        var protocolError: DnsResolveResult? = null
        for (index in providerOrder(activeProviderIndex)) {
            val provider = providers[index]
            try {
                if (networkGeneration.get() != requestGeneration) throw StaleNetworkException()
                val response = execute(provider, query, requestGeneration)
                val validated = DnsMessageValidator.validate(query, response)
                if (validated.meaning == DnsSecurityMeaning.ERROR) {
                    if (protocolError == null) {
                        protocolError = DnsResolveResult(
                            response,
                            provider.name,
                            (System.nanoTime() - start) / 1_000_000L,
                            false
                        )
                    }
                    continue
                }
                return synchronized(networkLock) {
                    if (networkGeneration.get() != requestGeneration) throw StaleNetworkException()
                    activeProviderIndex = index
                    if ((validated.meaning == DnsSecurityMeaning.ACCEPTABLE ||
                            validated.meaning == DnsSecurityMeaning.NXDOMAIN) &&
                        validated.minimumTtlSeconds > 0L
                    ) {
                        cache.putSuccess(
                            parsedQuestion.key,
                            response,
                            validated.minimumTtlSeconds.coerceAtMost(MAX_CACHE_TTL_SECONDS) * 1_000L
                        )
                    }
                    DnsResolveResult(
                        response,
                        provider.name,
                        (System.nanoTime() - start) / 1_000_000L,
                        cacheHit = false
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: StaleNetworkException) {
                throw error
            } catch (error: Exception) {
                failures += error
            }
        }
        protocolError?.let {
            return synchronized(networkLock) {
                if (networkGeneration.get() != requestGeneration) throw StaleNetworkException()
                it
            }
        }
        synchronized(networkLock) {
            if (networkGeneration.get() != requestGeneration) throw StaleNetworkException()
            cache.putFailure(parsedQuestion.key)
        }
        if (failures.isNotEmpty() && failures.all(DnsTimeoutClassifier::isTimeout)) {
            throw SocketTimeoutException("all DoH providers timed out")
        }
        val failure = IOException("all DoH providers failed")
        failures.forEach(failure::addSuppressed)
        throw failure
    }

    fun providerNamesInFallbackOrder(): List<String> {
        return providerOrder(activeProviderIndex).map { providers[it].name }
    }

    override fun onNetworkChanged() {
        synchronized(networkLock) {
            networkGeneration.incrementAndGet()
            cache.clear()
            runCatching { client.dispatcher.cancelAll() }
            runCatching { client.connectionPool.evictAll() }
        }
    }

    override fun close() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.cache?.close() }
    }

    private fun providerOrder(startIndex: Int): List<Int> {
        if (providers.isEmpty()) return emptyList()
        return providers.indices.map { (startIndex + it) % providers.size }
    }

    private suspend fun execute(
        provider: DohProvider,
        query: ByteArray,
        requestGeneration: Long
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(provider.url)
            .header("Accept", "application/dns-message")
            .post(query.toRequestBody(DNS_MESSAGE))
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use { readValidatedResponse(provider, query, it) }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
        synchronized(networkLock) {
            if (!continuation.isActive) return@synchronized
            if (networkGeneration.get() != requestGeneration) {
                continuation.resumeWithException(StaleNetworkException())
            } else {
                call.enqueue(callback)
            }
        }
    }

    private fun readValidatedResponse(provider: DohProvider, query: ByteArray, response: Response): ByteArray {
        if (!response.isSuccessful) throw IOException("${provider.name} HTTP ${response.code}")
        val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != "application/dns-message") throw IOException("${provider.name} invalid content type")
        val responseBody = response.body ?: throw IOException("${provider.name} empty response")
        val declaredLength = responseBody.contentLength()
        if (declaredLength > MAX_DNS_MESSAGE_SIZE) {
            throw IOException("${provider.name} oversized DNS message")
        }
        val body = readBounded(responseBody.byteStream()).takeIf { it.isNotEmpty() }
            ?: throw IOException("${provider.name} empty response")
        return validateResponse(provider.name, query, body)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(512)
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_DNS_MESSAGE_SIZE) throw IOException("oversized DNS message")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class ProtectedSocketFactory(
        private val delegate: SocketFactory,
        private val protectSocket: (Socket) -> Boolean
    ) : SocketFactory() {
        override fun createSocket(): Socket = protectedSocket()
        override fun createSocket(host: String, port: Int): Socket =
            protectedSocket().connectOrClose(InetSocketAddress(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            protectedSocket(localHost, localPort).connectOrClose(InetSocketAddress(host, port))
        override fun createSocket(host: InetAddress, port: Int): Socket =
            protectedSocket().connectOrClose(InetSocketAddress(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            protectedSocket(localAddress, localPort).connectOrClose(InetSocketAddress(address, port))

        private fun protectedSocket(localAddress: InetAddress? = null, localPort: Int = 0): Socket {
            val socket = delegate.createSocket()
            try {
                socket.bind(InetSocketAddress(localAddress, localPort))
                if (!protectSocket(socket)) throw IOException("could not protect DoH socket")
                return socket
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }

        private fun Socket.connectOrClose(endpoint: InetSocketAddress): Socket {
            try {
                connect(endpoint)
                return this
            } catch (error: Exception) {
                runCatching { close() }
                throw error
            }
        }
    }

    internal class BootstrapDns(
        providers: List<DohProvider>,
        private val networkLookup: (String) -> List<InetAddress>,
        private val addressTransform: (List<InetAddress>) -> List<InetAddress>,
        private val preferIpv6: () -> Boolean
    ) : Dns {
        private val addressesByHost = providers
            .mapNotNull { provider ->
                val host = runCatching { provider.url.toHttpUrl().host }.getOrNull()
                host?.lowercase()?.takeIf { provider.bootstrapAddresses.isNotEmpty() }
                    ?.let { it to provider.bootstrapAddresses }
            }
            .toMap()

        override fun lookup(hostname: String): List<InetAddress> {
            val pinned = addressesByHost[hostname.lowercase()].orEmpty()
            val transformed = runCatching { addressTransform(pinned) }.getOrDefault(emptyList())
            val pinnedCandidates = order((transformed + pinned).distinctBy(::addressIdentity))
            val requiresLookup = pinnedCandidates.isEmpty() ||
                preferIpv6() && pinnedCandidates.none { it.address.size == IPV6_ADDRESS_BYTES }
            if (!requiresLookup) return pinnedCandidates.take(MAX_BOOTSTRAP_ADDRESSES)
            val resolved = runCatching { networkLookup(hostname) }.getOrDefault(emptyList())
            val combined = order((transformed + resolved + pinned).distinctBy(::addressIdentity))
            if (combined.isEmpty()) throw UnknownHostException(hostname)
            return combined.take(MAX_BOOTSTRAP_ADDRESSES)
        }

        private fun order(addresses: List<InetAddress>): List<InetAddress> {
            return if (preferIpv6()) {
                addresses.sortedBy { address ->
                    if (address.address.size == IPV6_ADDRESS_BYTES) 0 else 1
                }
            } else {
                addresses
            }
        }

        private fun addressIdentity(address: InetAddress): List<Byte> = address.address.toList()
    }

    companion object {
        private val DNS_MESSAGE = "application/dns-message".toMediaType()
        private const val DNS_HEADER_SIZE = 12
        private const val MAX_DNS_MESSAGE_SIZE = 65_535
        private const val MAX_CACHE_TTL_SECONDS = 86_400L
        private const val IPV6_ADDRESS_BYTES = 16
        private const val MAX_BOOTSTRAP_ADDRESSES = 16

        internal fun validateResponse(providerName: String, query: ByteArray, response: ByteArray): ByteArray {
            if (query.size < DNS_HEADER_SIZE || response.size < DNS_HEADER_SIZE) {
                throw IOException("$providerName truncated DNS message")
            }
            if (response.size > MAX_DNS_MESSAGE_SIZE) throw IOException("$providerName oversized DNS message")
            if (response[0] != query[0] || response[1] != query[1]) {
                throw IOException("$providerName mismatched DNS transaction")
            }
            if ((response[2].toInt() and DNS_RESPONSE_FLAG) == 0) {
                throw IOException("$providerName returned a DNS query")
            }
            if ((response[2].toInt() and DNS_TRUNCATED_FLAG) != 0) {
                throw IOException("$providerName truncated DNS response")
            }
            DnsMessageValidator.validate(query, response)
            return response
        }

        private const val DNS_RESPONSE_FLAG = 0x80
        private const val DNS_TRUNCATED_FLAG = 0x02

        private fun ipv4(hostname: String, a: Int, b: Int, c: Int, d: Int): InetAddress {
            return InetAddress.getByAddress(hostname, byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
        }

        private fun ipv6(hostname: String, vararg groups: Int): InetAddress {
            require(groups.size == 8)
            val address = ByteArray(16)
            groups.forEachIndexed { index, group ->
                require(group in 0..0xffff)
                address[index * 2] = (group ushr 8).toByte()
                address[index * 2 + 1] = group.toByte()
            }
            return InetAddress.getByAddress(hostname, address)
        }

        val DEFAULT_PROVIDERS = listOf(
            DohProvider(
                "cloudflare",
                "https://cloudflare-dns.com/dns-query",
                listOf(
                    ipv4("cloudflare-dns.com", 1, 1, 1, 1),
                    ipv4("cloudflare-dns.com", 1, 0, 0, 1),
                    ipv6("cloudflare-dns.com", 0x2606, 0x4700, 0x4700, 0, 0, 0, 0, 0x1111),
                    ipv6("cloudflare-dns.com", 0x2606, 0x4700, 0x4700, 0, 0, 0, 0, 0x1001)
                )
            ),
            DohProvider(
                "google",
                "https://dns.google/dns-query",
                listOf(
                    ipv4("dns.google", 8, 8, 8, 8),
                    ipv4("dns.google", 8, 8, 4, 4),
                    ipv6("dns.google", 0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888),
                    ipv6("dns.google", 0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8844)
                )
            ),
            DohProvider(
                "quad9",
                "https://dns.quad9.net/dns-query",
                listOf(
                    ipv4("dns.quad9.net", 9, 9, 9, 9),
                    ipv4("dns.quad9.net", 149, 112, 112, 112),
                    ipv6("dns.quad9.net", 0x2620, 0x00fe, 0, 0, 0, 0, 0, 0x00fe),
                    ipv6("dns.quad9.net", 0x2620, 0x00fe, 0, 0, 0, 0, 0, 0x0009)
                )
            )
        )
    }
}

class SystemDnsResolver(
    private val rawLookup: (suspend (ByteArray, DnsPacket.Question) -> ByteArray)? = null,
    private val lookup: (String) -> Array<InetAddress> = { domain -> InetAddress.getAllByName(domain) },
    private val networkChanged: () -> Unit = {}
) : DnsResolver {
    override suspend fun resolve(query: ByteArray, question: DnsPacket.Question): DnsResolveResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        if (question.qClass != 1) {
            return@withContext DnsResolveResult(
                DnsPacket.noDataResponse(query, query.size),
                "system-dns",
                (System.nanoTime() - start) / 1_000_000L,
                cacheHit = false
            )
        }
        if (rawLookup != null) {
            return@withContext DnsResolveResult(
                rawLookup.invoke(query, question),
                "system-dns-raw",
                (System.nanoTime() - start) / 1_000_000L,
                cacheHit = false
            )
        }
        val addressSize = when (question.type) {
            DnsPacket.TYPE_A -> 4
            DnsPacket.TYPE_AAAA -> 16
            else -> 0
        }
        if (addressSize == 0) throw IOException("system-dns-raw-required")
        val address = lookup(question.domain)
            .firstOrNull { it.address.size == addressSize }
            ?.address
            ?: return@withContext DnsResolveResult(
                DnsPacket.noDataResponse(query, query.size),
                "system-dns",
                (System.nanoTime() - start) / 1_000_000L,
                cacheHit = false
            )
        val response = if (question.type == DnsPacket.TYPE_A) {
            DnsPacket.aRecordResponse(query, query.size, address)
        } else {
            DnsPacket.aaaaRecordResponse(query, query.size, address)
        }
        DnsResolveResult(
            response,
            "system-dns",
            (System.nanoTime() - start) / 1_000_000L,
            cacheHit = false
        )
    }

    override fun onNetworkChanged() {
        networkChanged()
    }
}
