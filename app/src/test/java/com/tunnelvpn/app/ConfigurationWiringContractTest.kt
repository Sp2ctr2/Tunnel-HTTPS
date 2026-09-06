package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationWiringContractTest {
    private val engine = File("src/main/java/com/tunnelvpn/app/LocalProtectionEngine.kt").readText()
    private val service = File("src/main/java/com/tunnelvpn/app/TunnelVpnService.kt").readText()
    private val modelStore = File("src/main/java/com/tunnelvpn/app/TurboModelStore.kt").readText()
    private val aiRuntime = File("src/main/java/com/tunnelvpn/app/TurboAiRuntime.kt").readText()
    private val networkHandler = File("src/main/java/com/tunnelvpn/app/NetworkChangeHandler.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun localRelayUsesSanitizedConfiguredMtuWithStandardFallback() {
        assertTrue(engine.contains(".setMtu(config.mtu)"))
        assertTrue(engine.contains("var establishedMtu = config.mtu"))
        assertTrue(engine.contains("builder.setMtu(candidateMtu)"))
        assertTrue(engine.contains("private const val FALLBACK_TUN_MTU = 1500"))
        assertTrue(engine.contains("mtu = establishedMtu"))
    }

    @Test
    fun ipv6IsCapturedForFullForwardAndDnsOnlyUsesTheVirtualResolverRoute() {
        assertTrue(engine.contains(".addAddress(TUN_IPV6_ADDRESS, 128)"))
        assertTrue(engine.contains("builder.addDnsServer(VIRTUAL_DNS_IPV6)"))
        assertTrue(engine.contains("builder.addRoute(\"::\", 0)"))
        assertTrue(engine.contains("builder.excludeRoute(IpPrefix(InetAddress.getByName(\"fe80::\"), 10))"))
        assertTrue(engine.contains("builder.excludeRoute(IpPrefix(InetAddress.getByName(\"ff00::\"), 8))"))
        assertTrue(engine.contains("Ipv6FullForwardRoutePolicy.legacyIncludedRoutes.forEach"))
        assertTrue(engine.contains("builder.addRoute(VIRTUAL_DNS_IPV6, 128)"))
        assertTrue(engine.contains("allowFamily(OsConstants.AF_INET6)"))
        assertTrue(engine.contains("ipv6-captured"))
        assertTrue(engine.contains("builder.addRoute(\"0.0.0.0\", 0)"))
    }

    @Test
    fun plaintextDnsAndQuicAreStoppedBeforeGenericForwarders() {
        val loop = engine.substringAfter("private suspend fun packetLoop")
        val ipv6Path = loop.substringAfter("val ready = normalized as Ipv6PacketNormalizer.Result.Ready")
            .substringBefore("val normalizedIpv4 = ipv4PacketNormalizer.process")
        val ipv4Path = loop.substringAfter("val normalizedIpv4 = ipv4PacketNormalizer.process")
            .substringBefore("} catch (_: Exception)")

        val ipv6Gate = ipv6Path.indexOf("shouldDropIpv6BeforeForwarding")
        val ipv6TcpForwarder = ipv6Path.indexOf("turboForwarder?.handleIpv6Packet")
        val ipv6Forwarder = ipv6Path.indexOf("udpForwarder?.handleIpv6Packet")
        val ipv4Gate = ipv4Path.indexOf("shouldDropIpv4BeforeForwarding")
        val tcpForwarder = ipv4Path.indexOf("turboForwarder?.handleIpv4Packet")
        val udpForwarder = ipv4Path.indexOf("udpForwarder?.handleIpv4Packet")
        val dnsEngineGate = ipv4Path.indexOf("isIpv4DnsPacket")
        val ipv4Ready = ipv4Path.indexOf("Ipv4PacketNormalizer.Result.Ready")

        assertTrue(ipv6Gate >= 0 && ipv6TcpForwarder > ipv6Gate)
        assertTrue(ipv6Forwarder > ipv6TcpForwarder)
        assertTrue(ipv4Gate >= 0 && tcpForwarder > ipv4Gate)
        assertTrue(udpForwarder >= 0 && dnsEngineGate > udpForwarder)
        assertTrue(engine.contains("private val udp443BlockPolicy = Udp443BlockPolicy("))
        assertTrue(engine.contains("Ipv4IcmpPortUnreachable.build(packet, ipv4Length)"))
        assertTrue(ipv4Ready >= 0 && ipv4Ready < ipv4Gate)
        assertTrue(ipv4Path.contains("readyIpv4.reassembled"))
        assertTrue(engine.contains("ipv4PacketNormalizer.expire()"))
        assertTrue(engine.contains("ipv4PacketNormalizer.reset()"))
        assertFalse(engine.contains("turboDomainMapper?.isVirtualAddress(destinationAddress)"))
    }

    @Test
    fun establishedInterfaceCommitIsObservableForTransactionalRollback() {
        val startBody = engine.substringAfter("fun start() {").substringBefore("} catch (error: Exception)")
        val establish = startBody.indexOf("builder.establish()")
        val committed = startBody.indexOf("interfaceEstablished.set(true)")
        val udpForwarder = startBody.indexOf("TurboUdpForwarder(")

        assertTrue(establish >= 0 && committed > establish)
        assertTrue(udpForwarder > committed)
        assertTrue(engine.contains("internal fun didEstablishInterface(): Boolean"))
    }

    @Test
    fun configuredSplitDomainsAreWiredWithoutBroadMapping() {
        assertTrue(engine.contains("turboDomainMapper = turboDomainMapper"))
        assertTrue(engine.contains("turboCandidateDomains = config.turboProtectedDomains"))
        assertFalse(engine.contains("localSuffixes = config.turboProtectedDomains"))
        assertFalse(engine.contains("turboMapBroadly"))
    }

    @Test
    fun allFullForwardModesUseTlsProtection() {
        assertTrue(engine.contains("tlsFragmentationEnabled = config.mode.protectsSni"))
        assertTrue(engine.contains("enabled = fullForwardEnabled && config.mode.protectsSni"))
        assertTrue(engine.contains("suppressHttpsSvcbRecords = false"))
        assertTrue(engine.contains("suppressIpv6Records = false"))
        assertTrue(engine.contains("adaptiveUdp443Policy::recordReachable"))
        assertTrue(engine.contains("adaptiveUdp443Policy::recordUnreachable"))
        val forwarder = File("src/main/java/com/tunnelvpn/app/TurboTcpForwarder.kt").readText()
        assertTrue(forwarder.contains("private const val MAX_CLIENT_HELLO_BUFFER = 65_699"))
    }

    @Test
    fun turboEffectiveModeNeverOverwritesTheUsersBaseMode() {
        assertTrue(service.contains("val mode = if (turboModeEnabled) ProtectionMode.STRONG else baseMode"))
        assertTrue(service.contains("baseMode = baseMode"))
        assertTrue(service.contains("putString(EXTRA_PROTECTION_MODE, config.baseMode.prefValue)"))
        assertFalse(service.contains("putString(EXTRA_PROTECTION_MODE, config.mode.prefValue)"))
    }

    @Test
    fun normalAndTurboDohUseTheSameWorkingSecureResolverPath() {
        val resolver = engine.substringAfter("private val resolver: DnsResolver")
            .substringBefore("private val fullForwardEnabled")

        assertTrue(resolver.contains("if (config.dnsOverHttpsEnabled)"))
        assertTrue(resolver.contains("SecureResolverRace.create("))
        assertTrue(resolver.contains("bootstrapLookup = { emptyList() }"))
        assertTrue(resolver.contains("bootstrapAddressTransform = ::transformDohBootstrapAddresses"))
        assertTrue(resolver.contains("preferIpv6Bootstrap = ::preferIpv6Bootstrap"))
        assertFalse(resolver.contains("if (config.turboModeEnabled)"))
        assertFalse(resolver.contains("publicResolver = DohResolver("))
    }

    @Test
    fun protectedSocketsAreBoundToThePublishedUnderlyingNetwork() {
        val tcp = engine.substringAfter("private fun protectTcpSocket")
            .substringBefore("private fun protectDatagramSocket")
        val udp = engine.substringAfter("private fun protectDatagramSocket")
            .substringBefore("private fun transformDohBootstrapAddresses")

        assertTrue(tcp.indexOf("service.protect(socket)") < tcp.indexOf("network.bindSocket(socket)"))
        assertTrue(udp.indexOf("service.protect(socket)") < udp.indexOf("network.bindSocket(socket)"))
        assertTrue(engine.contains("builder.setUnderlyingNetworks(arrayOf(network))"))
        assertTrue(engine.contains("publishVpnUnderlyingNetwork(publishedNetwork)"))
        assertTrue(engine.contains("service.setUnderlyingNetworks(networks)"))
    }

    @Test
    fun underlyingCallbackCannotSelectTheVpnItself() {
        assertTrue(networkHandler.contains("NetworkCapabilities.NET_CAPABILITY_NOT_VPN"))
        assertTrue(networkHandler.contains("registerBestMatchingNetworkCallback"))
        assertTrue(networkHandler.contains("manager.requestNetwork(request, created)"))
        assertTrue(manifest.contains("android.permission.CHANGE_NETWORK_STATE"))
        assertFalse(networkHandler.contains("registerDefaultNetworkCallback"))
        assertTrue(networkHandler.contains("val previous = currentNetwork"))
        assertFalse(networkHandler.contains("lastNetwork"))
    }

    @Test
    fun settingsRefreshPreservesOperationalTurboRuntime() {
        assertTrue(service.contains("transitionReason == TRANSITION_REASON_CONFIG_REFRESH"))
        assertTrue(service.contains("restoreTurboRuntime(retainedTurboSnapshot)"))
        assertTrue(service.contains("requestedConfig.turboModeEnabled && !preserveTurboRuntime"))
    }

    @Test
    fun internalTurboRestartRestoresPersistedPrivacyConfiguration() {
        assertTrue(service.contains("prefs.getString(EXTRA_BYPASS_PACKAGES"))
        assertTrue(service.contains("prefs.getString(EXTRA_SPLIT_DOMAINS"))
        assertTrue(service.contains("validateBypassPackageInput(bypassInput)"))
        assertTrue(service.contains("validateSplitDomainInput(turboProtectedDomainInput)"))
    }

    @Test
    fun modelResetGenerationRejectsStaleRuntimeSaves() {
        assertTrue(modelStore.contains("if (preferences.getLong(KEY_GENERATION, 0L) != generation) return false"))
        assertTrue(modelStore.contains(".putLong(KEY_GENERATION, nextGeneration)"))
        assertTrue(aiRuntime.contains("modelGeneration.set(model.generation)"))
        assertTrue(aiRuntime.contains("modelGeneration.get()"))
    }

    @Test
    fun capabilityRefreshDoesNotDecayTheWholeModelOrRepeatTransitionReset() {
        assertTrue(engine.contains("turboAiRuntime?.onNetworkContextChanged()"))
        assertTrue(aiRuntime.contains("fun onNetworkContextChanged()"))
        assertTrue(networkHandler.contains("if (previous != network)"))
        assertTrue(networkHandler.contains("lastCapabilitiesFingerprint = 0"))
        assertTrue(networkHandler.contains("lastLinkFingerprint = 0"))
        assertTrue(networkHandler.contains("reset = requiresFlowReset(previous, state)"))
        assertTrue(networkHandler.contains("previous.linkAddresses != current.linkAddresses"))
    }

    @Test
    fun metadataOnlyNetworkUpdatesDoNotFlushSecureDnsState() {
        val update = engine.substringAfter("fun updateUnderlyingNetworkContext")
            .substringBefore("private fun launchNat64Discovery")

        assertTrue(update.contains("if (discoveryInputsChanged) updateDns64Context"))
        assertTrue(update.contains("else {\n                underlyingContext = normalized"))
    }

    @Test
    fun socketBindingIgnoresCapabilitiesOnlyGenerationsAndBootstrapStaysPinned() {
        val binding = engine.substringAfter("private fun protectTcpSocket")
            .substringBefore("private inline fun <T> updateDns64Context")

        assertTrue(binding.contains("networkContextGeneration.get()"))
        assertFalse(binding.contains("underlyingContext.generation"))
        assertTrue(engine.contains("bootstrapLookup = { emptyList() }"))
        assertTrue(binding.contains("transformDohBootstrapAddresses"))
        assertFalse(binding.contains("getAllByName"))
    }

    @Test
    fun dns64PublicationIsBoundToBothNetworkAndPrefixGenerations() {
        val dnsEngine = File("src/main/java/com/tunnelvpn/app/DnsProtectionEngine.kt").readText()

        assertTrue(engine.contains("networkGenerationProvider = networkContextGeneration::get"))
        assertTrue(engine.contains("updateDns64Context {"))
        assertTrue(engine.contains("networkContextGeneration.incrementAndGet()"))
        assertTrue(dnsEngine.contains("val networkGeneration = networkGenerationProvider()"))
        assertTrue(dnsEngine.contains("val nat64Generation = nat64GenerationProvider()"))
        assertTrue(dnsEngine.contains("networkGenerationProvider() != networkGeneration"))
        assertTrue(dnsEngine.contains("nat64GenerationProvider() != nat64Generation"))
        assertTrue(networkHandler.contains("onUnderlyingContextChanged(update.second)"))
        assertTrue(engine.contains("dnsTransactionIdProvider() and 0xffff"))
        assertTrue(engine.contains("DISCOVERY_TRANSACTION_RANDOM.nextInt(DNS_TRANSACTION_ID_LIMIT)"))
    }

    @Test
    fun roamingCapabilityIsGuardedForAndroidSevenAndEight() {
        val capabilityState = networkHandler.substringAfter("private fun capabilitiesState")
            .substringBefore("private fun capabilitiesFingerprint")
        val versionGuard = capabilityState.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P")
        val capabilityRead = capabilityState.indexOf("NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING")

        assertTrue(versionGuard >= 0)
        assertTrue(capabilityRead > versionGuard)
    }
}
