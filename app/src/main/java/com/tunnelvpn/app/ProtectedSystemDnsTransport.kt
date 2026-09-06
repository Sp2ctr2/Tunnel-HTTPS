package com.tunnelvpn.app

import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProtectedSystemDnsTransport(
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val protectSocket: (Socket) -> Boolean,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val dnsPort: Int = DNS_PORT,
    private val isLocalName: (String) -> Boolean = DnsPacket::isLocalOnlyName,
    private val isAllowedNat64Address: (DnsPacket.Question, ByteArray) -> Boolean = { _, _ -> false }
) {
    private val servers = AtomicReference<List<InetAddress>>(emptyList())
    private val generation = AtomicLong()
    private val activeSockets = ConcurrentHashMap.newKeySet<Closeable>()
    private val lifecycleLock = Any()

    init {
        require(dnsPort in 1..65_535)
    }

    fun updateServers(value: List<InetAddress>) {
        val normalized = value.distinctBy(::endpointIdentity).take(MAX_SERVERS)
        val socketsToClose = synchronized(lifecycleLock) {
            if (sameEndpoints(servers.get(), normalized)) return
            servers.set(normalized)
            generation.incrementAndGet()
            snapshotActiveSockets()
        }
        socketsToClose.forEach { socket -> runCatching { socket.close() } }
    }

    fun currentServers(): List<InetAddress> = servers.get().toList()

    fun onNetworkChanged() {
        val socketsToClose = synchronized(lifecycleLock) {
            generation.incrementAndGet()
            snapshotActiveSockets()
        }
        socketsToClose.forEach { socket -> runCatching { socket.close() } }
    }

    suspend fun resolve(query: ByteArray, question: DnsPacket.Question): ByteArray = withContext(Dispatchers.IO) {
        if (query.size !in 12..MAX_DNS_MESSAGE) throw IOException("system-dns-query-size")
        val expectedGeneration = generation.get()
        val failures = mutableListOf<Exception>()
        var protocolError: ByteArray? = null
        val serverSnapshot = servers.get()
        if (serverSnapshot.isEmpty()) throw IOException("system-dns-no-server")
        for (server in serverSnapshot) {
            try {
                val udp = queryUdp(server, query, expectedGeneration)
                val response = if (isTruncated(udp)) queryTcp(server, query, expectedGeneration) else udp
                val validated = DnsMessageValidator.validate(
                    query,
                    response,
                    publicQuery = !isLocalName(question.domain),
                    allowedLocalAddress = { address -> isAllowedNat64Address(question, address) }
                )
                when (validated.meaning) {
                    DnsSecurityMeaning.DNSSEC_BOGUS -> throw IOException("dnssec-bogus")
                    DnsSecurityMeaning.FORGED -> throw IOException("forged-answer")
                    DnsSecurityMeaning.REBINDING -> throw IOException("rebinding-answer")
                    DnsSecurityMeaning.ERROR -> {
                        if (protocolError == null) protocolError = validated.bytes
                        continue
                    }
                    else -> Unit
                }
                if (generation.get() != expectedGeneration) throw IOException("system-dns-network-changed")
                return@withContext validated.bytes
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += error
            }
        }
        protocolError?.let {
            if (generation.get() != expectedGeneration) throw IOException("system-dns-network-changed")
            return@withContext it
        }
        if (failures.isNotEmpty() && failures.all(DnsTimeoutClassifier::isTimeout)) {
            throw java.net.SocketTimeoutException("system-dns-timeout")
        }
        val failure = IOException("system-dns-unavailable")
        failures.forEach(failure::addSuppressed)
        throw failure
    }

    private fun queryUdp(server: InetAddress, query: ByteArray, expectedGeneration: Long): ByteArray {
        val socket = DatagramSocket()
        register(socket, expectedGeneration)
        return try {
            if (!protectDatagram(socket)) throw IOException("protect-system-dns-udp")
            socket.soTimeout = timeoutMs.coerceAtLeast(1)
            socket.connect(InetSocketAddress(server, dnsPort))
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(MAX_DNS_MESSAGE)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            if (response.address != server || response.port != dnsPort || response.length < 12) {
                throw IOException("system-dns-udp-source")
            }
            buffer.copyOf(response.length)
        } finally {
            activeSockets -= socket
            socket.close()
        }
    }

    private fun queryTcp(server: InetAddress, query: ByteArray, expectedGeneration: Long): ByteArray {
        val socket = Socket()
        register(socket, expectedGeneration)
        return try {
            if (!protectSocket(socket)) throw IOException("protect-system-dns-tcp")
            socket.soTimeout = timeoutMs.coerceAtLeast(1)
            socket.connect(InetSocketAddress(server, dnsPort), timeoutMs.coerceAtLeast(1))
            val output = socket.getOutputStream()
            output.write((query.size ushr 8) and 0xff)
            output.write(query.size and 0xff)
            output.write(query)
            output.flush()
            val input = socket.getInputStream()
            val size = (readByte(input) shl 8) or readByte(input)
            if (size !in 12..MAX_DNS_MESSAGE) throw IOException("system-dns-tcp-size")
            val response = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(response, offset, size - offset)
                if (count < 0) throw EOFException("system-dns-tcp-eof")
                if (count > 0) offset += count
            }
            response
        } finally {
            activeSockets -= socket
            socket.close()
        }
    }

    fun close() {
        val socketsToClose = synchronized(lifecycleLock) {
            servers.set(emptyList())
            generation.incrementAndGet()
            snapshotActiveSockets()
        }
        socketsToClose.forEach { socket -> runCatching { socket.close() } }
    }

    private fun sameEndpoints(left: List<InetAddress>, right: List<InetAddress>): Boolean {
        return left.size == right.size && left.indices.all { index ->
            endpointIdentity(left[index]) == endpointIdentity(right[index])
        }
    }

    private fun endpointIdentity(address: InetAddress): DnsEndpointIdentity {
        return DnsEndpointIdentity(
            address.address.toList(),
            (address as? Inet6Address)?.scopeId ?: 0
        )
    }

    private fun snapshotActiveSockets(): List<Closeable> {
        val snapshot = ArrayList<Closeable>(activeSockets.size)
        activeSockets.forEach(snapshot::add)
        return snapshot
    }

    private fun register(socket: Closeable, expectedGeneration: Long) {
        synchronized(lifecycleLock) {
            if (generation.get() != expectedGeneration) {
                socket.close()
                throw IOException("system-dns-network-changed")
            }
            activeSockets += socket
        }
    }

    private fun readByte(input: java.io.InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("system-dns-tcp-prefix")
        return value
    }

    private fun isTruncated(response: ByteArray): Boolean {
        return response.size >= 4 && response[2].toInt() and 0x02 != 0
    }

    private data class DnsEndpointIdentity(val bytes: List<Byte>, val scopeId: Int)

    companion object {
        private const val DNS_PORT = 53
        private const val MAX_DNS_MESSAGE = 65_535
        private const val MAX_SERVERS = 4
        private const val DEFAULT_TIMEOUT_MS = 2_500
    }
}
