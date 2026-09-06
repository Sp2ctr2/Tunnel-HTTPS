package com.tunnelvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboDomainMapperTest {
    @Test
    fun expiredVirtualAddressIsQuarantinedBeforeReuse() {
        val mapper = TurboDomainMapper(maxHosts = 2, ttlMs = 5)
        val first = mapper.map("first.example", byteArrayOf(1, 1, 1, 1), nowMs = 1_000)!!
        mapper.map("second.example", byteArrayOf(2, 2, 2, 2), nowMs = 1_001)

        assertNull(mapper.map("third.example", byteArrayOf(3, 3, 3, 3), nowMs = 1_007))

        val newest = mapper.map("third.example", byteArrayOf(3, 3, 3, 3), nowMs = 1_012)!!

        assertEquals(first.virtualAddressString, newest.virtualAddressString)
        assertEquals("third.example", mapper.lookupVirtual(newest.virtualAddress, nowMs = 1_013)?.domain)
    }

    @Test
    fun mapsDomainToVirtualAddressAndRealAddress() {
        val mapper = TurboDomainMapper(ttlMs = 10_000)

        val mapping = mapper.map("Example.COM.", byteArrayOf(93, 184.toByte(), 216.toByte(), 34), nowMs = 1_000)

        assertNotNull(mapping)
        mapping!!
        assertEquals("example.com", mapping.domain)
        assertEquals("192.0.2.2", mapping.virtualAddressString)
        assertEquals("93.184.216.34", mapping.realInetAddress.hostAddress)
        assertEquals(mapping, mapper.lookupVirtual(mapping.virtualAddress, nowMs = 1_001))
        assertTrue(TurboTcpForwarder.isTurboVirtualIpv4(mapping.virtualAddress))
        assertFalse(TurboTcpForwarder.isTurboVirtualIpv4(byteArrayOf(10, 111, 1, 2)))
    }

    @Test
    fun expiredMappingsAreNotCandidates() {
        val mapper = TurboDomainMapper(ttlMs = 5)
        val mapping = mapper.map("example.com", byteArrayOf(1, 1, 1, 1), nowMs = 10)!!

        assertTrue(mapper.isVirtualAddress(mapping.virtualAddress, nowMs = 12))
        assertFalse(mapper.isVirtualAddress(mapping.virtualAddress, nowMs = 20))
    }

    @Test
    fun preservesMultipleRealAddressCandidates() {
        val mapper = TurboDomainMapper(ttlMs = 10_000)

        val mapping = mapper.map(
            "example.com",
            listOf(
                byteArrayOf(1, 1, 1, 1),
                byteArrayOf(2, 2, 2, 2),
                byteArrayOf(1, 1, 1, 1)
            ),
            nowMs = 1_000
        )!!

        assertEquals("1.1.1.1", mapping.realInetAddress.hostAddress)
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), mapping.realInetAddresses.map { it.hostAddress })
    }

    @Test
    fun liveVirtualAddressIsNeverReassignedToAnotherDomain() {
        val mapper = TurboDomainMapper(maxHosts = 2, ttlMs = 10_000)
        val first = mapper.map("first.example", byteArrayOf(1, 1, 1, 1), nowMs = 1_000)!!
        mapper.map("second.example", byteArrayOf(2, 2, 2, 2), nowMs = 1_000)

        assertNull(mapper.map("third.example", byteArrayOf(3, 3, 3, 3), nowMs = 1_000))
        assertEquals("first.example", mapper.lookupVirtual(first.virtualAddress, nowMs = 1_001)?.domain)
    }

    @Test
    fun networkGenerationChangeAtomicallyInvalidatesMappings() {
        val mapper = TurboDomainMapper(ttlMs = 10_000)
        val previous = mapper.map("nas.corp", byteArrayOf(10, 0, 0, 1), nowMs = 1_000)!!

        mapper.updateNetworkGeneration(2L)

        assertNull(mapper.lookupVirtual(previous.virtualAddress, nowMs = 1_001))
        assertNull(mapper.map(
            "nas.corp",
            byteArrayOf(10, 0, 0, 2),
            nowMs = 1_001,
            expectedNetworkGeneration = 0L
        ))
        val current = mapper.map(
            "nas.corp",
            byteArrayOf(10, 0, 0, 2),
            nowMs = 1_001,
            expectedNetworkGeneration = 2L
        )
        assertNotNull(current)
        assertEquals(2L, current!!.networkGeneration)
        assertFalse(previous.virtualAddress.contentEquals(current.virtualAddress))
    }

    @Test
    fun unstableNetworkGenerationCannotCreateMappings() {
        val mapper = TurboDomainMapper()
        mapper.updateNetworkGeneration(3L)

        assertNull(mapper.map(
            "example.com",
            byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
            expectedNetworkGeneration = 3L
        ))
    }

    @Test
    fun externalGenerationChangeBlocksLookupBeforeMapperUpdate() {
        var externalGeneration = 0L
        val mapper = TurboDomainMapper(networkGenerationProvider = { externalGeneration })
        val mapping = mapper.map("nas.corp", byteArrayOf(10, 0, 0, 1), nowMs = 1_000)!!

        externalGeneration = 1L

        assertNull(mapper.lookupVirtual(mapping.virtualAddress, nowMs = 1_001))
    }

    @Test
    fun retiredVirtualPoolCannotBeReusedBeforeItsTtl() {
        val mapper = TurboDomainMapper(maxHosts = 2, ttlMs = 100)
        mapper.map("first.example", byteArrayOf(1, 1, 1, 1), nowMs = 1_000)!!
        mapper.map("second.example", byteArrayOf(2, 2, 2, 2), nowMs = 1_000)!!

        mapper.updateNetworkGeneration(2L, nowMs = 1_001)

        assertNull(mapper.map(
            "third.example",
            byteArrayOf(3, 3, 3, 3),
            nowMs = 1_050,
            expectedNetworkGeneration = 2L
        ))
        assertNotNull(mapper.map(
            "third.example",
            byteArrayOf(3, 3, 3, 3),
            nowMs = 1_102,
            expectedNetworkGeneration = 2L
        ))
    }

    @Test
    fun oldGenerationLeaseCannotCommitAfterReset() {
        val mapper = TurboDomainMapper(ttlMs = 100)
        val oldLease = mapper.acquireLease(0L)!!

        mapper.updateNetworkGeneration(2L, nowMs = 1_001)

        assertNull(mapper.map(
            "old.example",
            listOf(byteArrayOf(1, 1, 1, 1)),
            nowMs = 1_002,
            lease = oldLease
        ))
        assertNull(mapper.lookupVirtual(byteArrayOf(192.toByte(), 0, 2, 2), nowMs = 1_002))
    }

    @Test
    fun fakeIpAbaAcrossGenerationsIsBoundedByQuarantine() {
        val mapper = TurboDomainMapper(maxHosts = 2, ttlMs = 100)
        val old = mapper.map("old.example", byteArrayOf(1, 1, 1, 1), nowMs = 1_000)!!

        mapper.updateNetworkGeneration(2L, nowMs = 1_001)
        val current = mapper.map(
            "current.example",
            byteArrayOf(2, 2, 2, 2),
            nowMs = 1_002,
            expectedNetworkGeneration = 2L
        )!!

        assertFalse(old.virtualAddress.contentEquals(current.virtualAddress))
        assertNull(mapper.lookupVirtual(old.virtualAddress, nowMs = 1_050))
        assertNull(mapper.map(
            "third.example",
            byteArrayOf(3, 3, 3, 3),
            nowMs = 1_050,
            expectedNetworkGeneration = 2L
        ))
        val reused = mapper.map(
            "third.example",
            byteArrayOf(3, 3, 3, 3),
            nowMs = 1_102,
            expectedNetworkGeneration = 2L
        )!!
        assertTrue(old.virtualAddress.contentEquals(reused.virtualAddress))
    }
}
