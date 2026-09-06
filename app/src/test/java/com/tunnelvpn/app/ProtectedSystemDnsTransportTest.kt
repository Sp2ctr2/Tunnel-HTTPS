package com.tunnelvpn.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.net.ServerSocket
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtectedSystemDnsTransportTest {
    @Test
    fun linkLocalServerScopeChangeReplacesTheEndpoint() {
        val raw = InetAddress.getByName("fe80::1").address
        val first = Inet6Address.getByAddress(null, raw, 7)
        val second = Inet6Address.getByAddress(null, raw, 11)
        val transport = ProtectedSystemDnsTransport(
            protectDatagram = { true },
            protectSocket = { true }
        )

        transport.updateServers(listOf(first))
        transport.updateServers(listOf(second))

        assertEquals(11, (transport.currentServers().single() as Inet6Address).scopeId)
    }

    @Test
    fun publicNameRebindingAnswerIsRejected() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback).apply { soTimeout = 3_000 }
        val serverThread = thread(start = true, isDaemon = true) {
            runCatching {
                val buffer = ByteArray(2048)
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                val query = buffer.copyOf(request.length)
                val response = DnsPacket.aRecordResponse(query, query.size, byteArrayOf(127, 0, 0, 1))
                server.send(DatagramPacket(response, response.size, request.address, request.port))
            }
        }
        try {
            val transport = ProtectedSystemDnsTransport(
                protectDatagram = { true },
                protectSocket = { true },
                timeoutMs = 1_000,
                dnsPort = server.localPort
            )
            transport.updateServers(listOf(loopback))
            val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x3345)!!

            assertThrows(java.io.IOException::class.java) {
                runBlocking { transport.resolve(query, DnsPacket.parseQuestion(query)!!) }
            }
        } finally {
            server.close()
            serverThread.join(1_500)
        }
    }

    @Test
    fun networkChangeCancelsInFlightQueryWhenDnsEndpointIsUnchanged() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loopback).apply { soTimeout = 3_000 }
        val requestReceived = CountDownLatch(1)
        val serverThread = thread(start = true, isDaemon = true) {
            runCatching {
                val request = DatagramPacket(ByteArray(2048), 2048)
                server.receive(request)
                requestReceived.countDown()
                Thread.sleep(2_000L)
            }
        }
        val transport = ProtectedSystemDnsTransport(
            protectDatagram = { true },
            protectSocket = { true },
            timeoutMs = 5_000,
            dnsPort = server.localPort
        )
        transport.updateServers(listOf(loopback))
        val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x3346)!!

        try {
            val pending = async(Dispatchers.IO) {
                runCatching { transport.resolve(query, DnsPacket.parseQuestion(query)!!) }.exceptionOrNull()
            }
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS))
            transport.onNetworkChanged()
            assertTrue(withTimeout(2_000L) { pending.await() } is IOException)
            assertEquals(listOf(loopback), transport.currentServers())
        } finally {
            transport.close()
            server.close()
            serverThread.interrupt()
            serverThread.join(1_000L)
        }
    }

    @Test
    fun truncatedUdpResponseRetriesOverTcpAndValidatesTheAnswer() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val tcpServer = ServerSocket(0, 1, loopback).apply { soTimeout = 3_000 }
        val udpServer = DatagramSocket(tcpServer.localPort, loopback).apply { soTimeout = 3_000 }
        val failure = AtomicReference<Throwable?>()
        val udpThread = thread(start = true, isDaemon = true) {
            try {
                val buffer = ByteArray(2048)
                val request = DatagramPacket(buffer, buffer.size)
                udpServer.receive(request)
                val query = buffer.copyOf(request.length)
                val response = DnsPacket.noDataResponse(query, query.size).also {
                    it[2] = (it[2].toInt() or 0x02).toByte()
                }
                udpServer.send(DatagramPacket(response, response.size, request.address, request.port))
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val tcpThread = thread(start = true, isDaemon = true) {
            try {
                tcpServer.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val size = input.readUnsignedShort()
                    val query = ByteArray(size)
                    input.readFully(query)
                    val response = DnsPacket.aRecordResponse(
                        query,
                        query.size,
                        byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
                    )
                    DataOutputStream(socket.getOutputStream()).use { output ->
                        output.writeShort(response.size)
                        output.write(response)
                        output.flush()
                    }
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }

        try {
            val transport = ProtectedSystemDnsTransport(
                protectDatagram = { true },
                protectSocket = { true },
                timeoutMs = 2_000,
                dnsPort = tcpServer.localPort
            )
            transport.updateServers(listOf(loopback))
            val query = DnsPacket.query("example.com", DnsPacket.TYPE_A, 0x3344)!!

            val response = transport.resolve(query, DnsPacket.parseQuestion(query)!!)

            assertEquals(1, DnsPacket.extractARecords(response).size)
            assertTrue(DnsPacket.extractARecords(response).single().contentEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            udpThread.join(2_500)
            tcpThread.join(2_500)
            assertNull(failure.get())
        } finally {
            udpServer.close()
            tcpServer.close()
        }
    }
}
