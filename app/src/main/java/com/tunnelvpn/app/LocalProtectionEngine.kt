package com.tunnelvpn.app

import android.net.VpnService
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

class LocalProtectionEngine(
    private val service: VpnService,
    private val config: Config,
    private val diagnostics: DiagnosticsState,
    private val dnsTransactionIdProvider: () -> Int = {
        DISCOVERY_TRANSACTION_RANDOM.nextInt(DNS_TRANSACTION_ID_LIMIT)
    },
    private val onFatalPacketLoop: (String) -> Unit = {}
) {
    internal data class Ipv6TransportView(
        val protocol: Int,
        val destinationPort: Int?,
        val transportOffset: Int,
        val extensionCount: Int
    )

    private data class Ipv4TransportView(
        val protocol: Int,
        val destinationPort: Int?
    )

    data class Config(
        val mode: ProtectionMode,
        val baseMode: ProtectionMode = mode,
        val bypassPackages: List<String>,
        val adBlockEnabled: Boolean,
        val dnsOverHttpsEnabled: Boolean,
        val turboModeEnabled: Boolean = false,
        val turboAiFlags: TurboAiFlags = TurboAiFlags(enabled = false),
        val turboProtectedDomains: List<String> = emptyList(),
        val browserTrafficOnly: Boolean = false,
        val mtu: Int = TunnelVpnService.PERFORMANCE_TUN_MTU
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val riskEngine = ExplainableRiskEngine()
    private val nat64Translator = Nat64AddressTranslator()
    private val retiredNat64Prefixes = Nat64RetiredPrefixes(MAX_RETIRED_NAT64_PREFIXES)
    private val networkContextGeneration = AtomicLong(0L)
    private val localDnsSuffixes = AtomicReference<Set<String>>(emptySet())
    private val underlyingContextLock = Any()
    private var underlyingContext = UnderlyingNetworkContext(-1L, emptyList(), emptyList(), null, 0)
    private val nat64DiscoveryCache = Nat64DiscoveryCache(android.os.SystemClock::elapsedRealtime)
    private val nat64DiscoveryQueryGeneration = AtomicLong(-1L)
    private val nat64DiscoveryPendingGeneration = AtomicLong(-1L)
    private val nat64DiscoveryConfirmedAbsentGeneration = AtomicLong(-1L)
    private val nat64RediscoveryPending = AtomicBoolean(false)
    private val tunOutputLock = Any()
    private val tunOutputQueue = ArrayBlockingQueue<ByteArray>(TUN_OUTPUT_QUEUE_CAPACITY)
    private val tunWriterFailure = AtomicReference<Throwable?>(null)
    @Volatile private var tunWriterThread: Thread? = null
    private val systemDnsTransport = ProtectedSystemDnsTransport(
        protectDatagram = ::protectDatagramSocket,
        protectSocket = ::protectTcpSocket,
        isLocalName = ::isUnderlyingLocalName,
        isAllowedNat64Address = ::isAllowedSystemNat64Address
    )
    private val systemDnsResolver = SystemDnsResolver(
        rawLookup = systemDnsTransport::resolve,
        networkChanged = systemDnsTransport::onNetworkChanged
    )
    private val resolver: DnsResolver = if (config.dnsOverHttpsEnabled) {
        LocalFirstDnsResolver(
            publicResolver = SecureResolverRace.create(
                DnsCache(maxEntries = 512),
                protectSocket = ::protectTcpSocket,
                riskProvider = { riskEngine.current().level },
                bootstrapLookup = { emptyList() },
                bootstrapAddressTransform = ::transformDohBootstrapAddresses,
                preferIpv6Bootstrap = ::preferIpv6Bootstrap
            ),
            localResolver = systemDnsResolver,
            localSuffixProvider = localDnsSuffixes::get
        )
    } else {
        systemDnsResolver
    }

    private val fullForwardEnabled = config.mode.capturesAllTraffic
    private val turboDomainMapper = if (fullForwardEnabled) {
        TurboDomainMapper(networkGenerationProvider = networkContextGeneration::get)
    } else null
    private val domainBlocker = DomainBlocker(
        enabled = config.adBlockEnabled,
        extraSuffixes = if (config.adBlockEnabled) loadBundledBlockSuffixes() else emptySet()
    )
    @Volatile private var establishedTunMtu = config.mtu
    private val dnsEngine = DnsProtectionEngine(
        resolver = resolver,
        diagnostics = diagnostics,
        domainBlocker = domainBlocker,
        suppressHttpsSvcbRecords = false,
        suppressIpv6Records = false,
        turboDomainMapper = turboDomainMapper,
        turboCandidateDomains = config.turboProtectedDomains,
        nat64PrefixProvider = nat64Translator::current,
        nat64PrefixesProvider = nat64Translator::currentAll,
        nat64GenerationProvider = nat64Translator::generation,
        networkGenerationProvider = networkContextGeneration::get,
        nat64RemainingTtlSecondsProvider = ::nat64RemainingTtlSeconds,
        nat64DiscoveryRequiredProvider = ::isNat64DiscoveryRequired,
        isLocalName = ::isUnderlyingLocalName,
        maximumPacketSizeProvider = { establishedTunMtu }
    )
    private val tlsInspector = TlsMetadataInspector(diagnostics)
    private val ipv6IcmpErrorRateLimiter = Ipv6IcmpErrorRateLimiter()
    private val ipv6PacketNormalizer = Ipv6PacketNormalizer(
        icmpErrorRateLimiter = ipv6IcmpErrorRateLimiter,
        icmpSourceAddress = InetAddress.getByName(TUN_IPV6_ADDRESS).address
    )
    private val ipv4PacketNormalizer = Ipv4PacketNormalizer()
    private val ipv6FragmentIdentification = AtomicInteger(SecureRandom().nextInt())
    private val adaptiveUdp443Policy = AdaptiveUdp443Policy()
    private val udp443BlockPolicy = Udp443BlockPolicy(
        enabled = fullForwardEnabled && config.mode.protectsSni,
        requiresTcpPath = { destination ->
            TurboTcpForwarder.isTurboVirtualIpv4(destination) ||
                adaptiveUdp443Policy.requiresTcpPath(destination)
        }
    )
    private val turboAiRuntime = if (fullForwardEnabled && config.turboModeEnabled) {
        TurboAiRuntime(
            context = service,
            scope = scope,
            flags = config.turboAiFlags,
            latencyProfile = TurboTcpForwarder.LatencyProfile.DEFAULT
        )
    } else null
    private val dnsConcurrency = Semaphore(MAX_CONCURRENT_DNS_QUERIES)
    private val stopping = AtomicBoolean(false)
    private val nat64DiscoveryGeneration = AtomicLong()
    private val interfaceEstablished = AtomicBoolean(false)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetJob: Job? = null
    private var input: FileInputStream? = null
    @Volatile private var output: FileOutputStream? = null
    @Volatile private var turboForwarder: TurboTcpForwarder? = null
    @Volatile private var udpForwarder: TurboUdpForwarder? = null

    fun start() {
        diagnostics.serviceRunning = true
        diagnostics.activeProtectionMode = config.mode
        diagnostics.turboModeEnabled = config.turboModeEnabled
        diagnostics.turboQuicGuardEnabled = fullForwardEnabled && config.mode.protectsSni
        diagnostics.routeMode = when {
            !fullForwardEnabled -> RouteMode.NORMAL_DNS_ONLY
            else -> RouteMode.TURBO_FULL_FORWARD
        }
        diagnostics.turboRouteAllIPv4Requested = fullForwardEnabled
        diagnostics.turboRouteAllIPv4Enabled = false
        diagnostics.turboForwarderPreflightPassed = !fullForwardEnabled
        diagnostics.ipv6HandlingStatus =
            "ipv6-captured: TCP/UDP forwarded; DNS protected; bounded extension/fragment normalization"
        diagnostics.httpsMetadataMode = when {
            !fullForwardEnabled -> "dns-only: IPv4/IPv6 UDP/53 protected"
            config.mode.protectsSni -> "dual-stack capture: TCP/443 clienthello protection"
            else -> "dual-stack capture"
        }
        val builder = service.Builder()
            .setSession("Tunnel HTTPS")
            .setMtu(config.mtu)
            .addAddress(TUN_ADDRESS, 32)
            .addAddress(TUN_IPV6_ADDRESS, 128)
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .setBlocking(true)

        builder.addDnsServer(VIRTUAL_DNS)
        builder.addDnsServer(VIRTUAL_DNS_IPV6)
        if (fullForwardEnabled) {
            builder.addRoute("0.0.0.0", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.addRoute("::", 0)
                builder.excludeRoute(IpPrefix(InetAddress.getByName("fe80::"), 10))
                builder.excludeRoute(IpPrefix(InetAddress.getByName("ff00::"), 8))
            } else {
                Ipv6FullForwardRoutePolicy.legacyIncludedRoutes.forEach { route ->
                    builder.addRoute(route.address, route.prefixLength)
                }
            }
            diagnostics.turboRouteAllIPv4Enabled = true
        } else {
            builder.addRoute(VIRTUAL_DNS, 32)
            builder.addRoute(VIRTUAL_DNS_IPV6, 128)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectivityManager = service.getSystemService(ConnectivityManager::class.java)
            builder.setMetered(connectivityManager?.isActiveNetworkMetered != false)
        }
        synchronized(underlyingContextLock) {
            underlyingContext.network?.let { network ->
                builder.setUnderlyingNetworks(arrayOf(network))
            }
        }

        diagnostics.bypassedPackages = AppBypassManager(
            service,
            config.bypassPackages,
            browserTrafficOnly = config.browserTrafficOnly
        ).apply(builder)
        try {
            var establishedMtu = config.mtu
            var attempt: ParcelFileDescriptor? = null
            val mtuCandidates = listOf(config.mtu, COMPATIBILITY_TUN_MTU, FALLBACK_TUN_MTU).distinct()
            for (candidateMtu in mtuCandidates) {
                builder.setMtu(candidateMtu)
                attempt = builder.establish()
                if (attempt != null) {
                    establishedMtu = candidateMtu
                    break
                }
            }
            val tun = attempt ?: error("VPN interface could not be established")
            establishedTunMtu = establishedMtu
            vpnInterface = tun
            interfaceEstablished.set(true)
            synchronized(underlyingContextLock) {
                underlyingContext.network
            }?.let(::publishVpnUnderlyingNetwork)
            val packetInput = FileInputStream(tun.fileDescriptor)
            val packetOutput = FileOutputStream(tun.fileDescriptor)
            input = packetInput
            output = packetOutput
            startTunWriter(packetOutput)
            turboForwarder = (turboDomainMapper ?: TurboDomainMapper()).let { mapper ->
                TurboTcpForwarder(
                    protectSocket = ::protectTcpSocket,
                    mapper = mapper,
                    diagnostics = diagnostics,
                    scope = scope,
                    writeTunPacket = { packet -> writeTunPacketFast(packet) },
                    directHttpsForwarding = true,
                    tlsFragmentationEnabled = config.mode.protectsSni,
                    domainBlocker = domainBlocker,
                    latencyProfile = TurboTcpForwarder.LatencyProfile.DEFAULT,
                    turboAiRuntime = turboAiRuntime,
                    destinationTranslator = { address -> translateDestinationCandidates(address).first() },
                    destinationCandidates = ::translateDestinationCandidates,
                    tcpDnsHandler = { query ->
                        dnsConcurrency.acquire()
                        try {
                            dnsEngine.handleTcpMessage(query)
                        } finally {
                            dnsConcurrency.release()
                        }
                    },
                    tunMtu = establishedMtu
                )
            }
            udpForwarder = if (fullForwardEnabled) {
                TurboUdpForwarder(
                    protectSocket = ::protectDatagramSocket,
                    diagnostics = diagnostics,
                    scope = scope,
                    writeTunPacket = { packet -> writeTunPacketFast(packet) },
                    mtu = establishedMtu,
                    idleTimeoutMs = TurboUdpForwarder.DEFAULT_IDLE_TIMEOUT_MS,
                    onUdp443Reachable = adaptiveUdp443Policy::recordReachable,
                    onUdp443Unreachable = adaptiveUdp443Policy::recordUnreachable,
                    destinationTranslator = { address -> translateDestinationCandidates(address).first() },
                    destinationCandidates = ::translateDestinationCandidates,
                    allowIcmpv6Error = ipv6IcmpErrorRateLimiter::tryAcquire
                )
            } else null
            diagnostics.turboForwarderPreflightPassed = !fullForwardEnabled ||
                (turboForwarder != null && udpForwarder != null)
            packetJob = scope.launch { packetLoop(packetInput) }
            scope.launch {
                while (isActive && !stopping.get()) {
                    delay(IPV6_REASSEMBLY_SWEEP_INTERVAL_MS)
                    ipv4PacketNormalizer.expire()
                    ipv6PacketNormalizer.drainIcmpErrors().forEach { response ->
                        runCatching { writeTunPacket(response) }
                            .onFailure { diagnostics.packetWriteErrors.incrementAndGet() }
                    }
                }
            }
        } catch (error: Exception) {
            closeResourcesQuietly()
            runCatching { turboForwarder?.closeAll() }
            turboForwarder = null
            runCatching { udpForwarder?.closeAll() }
            udpForwarder = null
            runCatching { turboAiRuntime?.close() }
            runCatching { resolver.close() }
            runCatching { systemDnsTransport.close() }
            scope.cancel()
            throw error
        }
    }

    fun activateRuntime() {
        turboAiRuntime?.start()
    }

    fun onNetworkChanged() {
        if (stopping.get()) return
        synchronized(underlyingContextLock) {
            updateDns64Context { runCatching { resolver.onNetworkChanged() } }
        }
        runCatching { turboAiRuntime?.onNetworkChanged() }
        runCatching { ipv4PacketNormalizer.reset() }
        runCatching { ipv6PacketNormalizer.reset() }
        runCatching { adaptiveUdp443Policy.reset() }
        runCatching { turboForwarder?.resetConnections() }
        runCatching { udpForwarder?.resetFlows() }
    }

    internal fun didEstablishInterface(): Boolean = interfaceEstablished.get()

    internal fun protectWatchdogSocket(socket: java.net.Socket): Boolean {
        if (!service.protect(socket)) return false
        val snapshot = synchronized(underlyingContextLock) {
            underlyingContext.network to networkContextGeneration.get()
        }
        val network = snapshot.first ?: return false
        return runCatching {
            network.bindSocket(socket)
            synchronized(underlyingContextLock) {
                underlyingContext.network == network && networkContextGeneration.get() == snapshot.second
            }
        }.getOrDefault(false)
    }

    internal fun watchdogProbeCandidates(addresses: List<InetAddress>): List<InetAddress> {
        return synchronized(underlyingContextLock) {
            orderWatchdogProbeCandidates(
                addresses,
                underlyingContext.hasIpv4,
                nat64Translator::translateAll,
                MAX_DOH_BOOTSTRAP_ADDRESSES
            )
        }
    }

    fun onNetworkContextChanged() {
        if (stopping.get()) return
        runCatching { turboAiRuntime?.onNetworkContextChanged() }
    }

    fun updateUnderlyingNetworkContext(value: UnderlyingNetworkContext) {
        if (stopping.get()) return
        var underlyingNetworkChanged = false
        var publishedNetwork: android.net.Network? = null
        val discovery = synchronized(underlyingContextLock) {
            if (value.generation < underlyingContext.generation) return
            if (value.generation == underlyingContext.generation) return
            val normalized = value.copy(
                nat64Prefixes = value.nat64Prefixes.distinct().take(MAX_ACTIVE_NAT64_PREFIXES),
                dnsServers = value.dnsServers.toList(),
                interfaceIndex = value.interfaceIndex.coerceAtLeast(0)
            )
            val previous = underlyingContext
            underlyingNetworkChanged = previous.network != normalized.network
            publishedNetwork = normalized.network
            val discoveryInputsChanged = previous.nat64Prefixes != normalized.nat64Prefixes ||
                previous.dnsServers != normalized.dnsServers ||
                previous.interfaceIndex != normalized.interfaceIndex ||
                previous.hasIpv4 != normalized.hasIpv4 ||
                previous.network != normalized.network
            val generation = if (discoveryInputsChanged) {
                nat64DiscoveryGeneration.incrementAndGet()
            } else {
                nat64DiscoveryGeneration.get()
            }
            if (discoveryInputsChanged) updateDns64Context {
                resolver.onNetworkChanged()
                underlyingContext = normalized
                nat64DiscoveryCache.reset()
                updateNat64TranslatorLocked(normalized.nat64Prefixes, normalized.interfaceIndex)
                nat64DiscoveryConfirmedAbsentGeneration.set(-1L)
                localDnsSuffixes.set(normalized.localDnsSuffixes.toSet())
                systemDnsTransport.updateServers(normalized.dnsServers)
            } else {
                underlyingContext = normalized
                localDnsSuffixes.set(normalized.localDnsSuffixes.toSet())
            }
            if (discoveryInputsChanged && shouldAttemptNat64Discovery(
                    normalized.hasIpv4,
                    normalized.nat64Prefixes.isNotEmpty(),
                    normalized.dnsServers.isNotEmpty()
                )
            ) {
                nat64DiscoveryPendingGeneration.updateAndGet { pending -> maxOf(pending, generation) }
                generation to normalized.dnsServers
            } else {
                null
            }
        }
        if (underlyingNetworkChanged && interfaceEstablished.get()) {
            publishVpnUnderlyingNetwork(publishedNetwork)
        }
        discovery?.let { (generation, servers) -> launchNat64Discovery(generation, servers) }
    }

    private fun launchNat64Discovery(generation: Long, servers: List<java.net.InetAddress>) {
        if (stopping.get() || servers.isEmpty()) return
        nat64DiscoveryPendingGeneration.updateAndGet { pending -> maxOf(pending, generation) }
        scope.launch {
            val expiredChanged = synchronized(underlyingContextLock) {
                if (stopping.get() || nat64DiscoveryGeneration.get() != generation ||
                    !shouldAttemptNat64Discovery(
                        underlyingContext.hasIpv4,
                        underlyingContext.nat64Prefixes.isNotEmpty(),
                        underlyingContext.dnsServers.isNotEmpty()
                    )
                ) return@launch
                lateinit var expired: Nat64DiscoveryCache.Result
                updateDns64Context {
                    expired = nat64DiscoveryCache.expire()
                    if (expired.changed) {
                        updateNat64TranslatorLocked(emptyList(), underlyingContext.interfaceIndex)
                        nat64DiscoveryConfirmedAbsentGeneration.set(-1L)
                    }
                }
                expired.changed
            }
            if (expiredChanged) resetNat64Consumers()
            val query = DnsPacket.query(
                IPV4_ONLY_ARPA,
                DnsPacket.TYPE_AAAA,
                dnsTransactionIdProvider() and 0xffff
            ) ?: return@launch
            val question = DnsPacket.parseQuestion(query) ?: return@launch
            if (!nat64DiscoveryQueryGeneration.compareAndSet(-1L, generation)) {
                queueNat64DiscoveryHandoff()
                return@launch
            }
            val decision = try {
                val response = runCatching { systemDnsTransport.resolve(query, question) }.getOrNull()
                val validatedResponse = response?.let { value ->
                    runCatching { DnsMessageValidator.validate(query, value) }.getOrNull()
                }
                val prefixes = if (validatedResponse?.rcode == 0 &&
                    validatedResponse.meaning == DnsSecurityMeaning.ACCEPTABLE
                ) {
                    Nat64Prefix.discoverAll(DnsPacket.extractAaaaRecords(response))
                        .take(MAX_ACTIVE_NAT64_PREFIXES)
                } else {
                    emptyList()
                }
                val positiveTtl = if (prefixes.isNotEmpty() && validatedResponse != null) {
                    DnsPacket.minimumAnswerTtlSeconds(
                        response,
                        setOf(DnsPacket.TYPE_AAAA, DNS_TYPE_CNAME, DNS_TYPE_DNAME)
                    )?.coerceAtMost(MAX_NAT64_DISCOVERY_TTL_SECONDS)
                } else {
                    null
                }
                val validatedNegative = validatedResponse != null && prefixes.isEmpty() &&
                    (validatedResponse.rcode == NAME_ERROR_RCODE ||
                        (validatedResponse.rcode == 0 &&
                            DnsPacket.minimumAnswerTtlSeconds(
                                response,
                                setOf(DnsPacket.TYPE_AAAA)
                            ) == null)) &&
                    (validatedResponse.meaning == DnsSecurityMeaning.ACCEPTABLE ||
                        validatedResponse.meaning == DnsSecurityMeaning.NXDOMAIN)
                val retryDelayMs = validatedResponse?.takeIf { validatedNegative }
                    ?.minimumTtlSeconds
                    ?.takeIf { it > 0L }
                    ?.coerceIn(1L, MAX_NAT64_DISCOVERY_TTL_SECONDS)
                    ?.times(1_000L)
                    ?: NAT64_RETRY_SECONDS * 1_000L
                val result = synchronized(underlyingContextLock) {
                    if (stopping.get() || nat64DiscoveryGeneration.get() != generation ||
                        !shouldAttemptNat64Discovery(
                            underlyingContext.hasIpv4,
                            underlyingContext.nat64Prefixes.isNotEmpty(),
                            underlyingContext.dnsServers.isNotEmpty()
                        )
                    ) {
                        null
                    } else {
                        lateinit var cacheResult: Nat64DiscoveryCache.Result
                        updateDns64Context {
                            cacheResult = if (prefixes.isNotEmpty() && positiveTtl != null) {
                                nat64DiscoveryCache.accept(
                                    prefixes,
                                    positiveTtl,
                                    NAT64_REFRESH_ADVANCE_SECONDS
                                )
                            } else {
                                nat64DiscoveryCache.reject(retryDelayMs)
                            }
                            updateNat64TranslatorLocked(
                                cacheResult.prefixes,
                                underlyingContext.interfaceIndex
                            )
                            nat64DiscoveryConfirmedAbsentGeneration.set(
                                if (prefixes.isEmpty() && validatedNegative) generation else -1L
                            )
                        }
                        cacheResult
                    }
                }
                result
            } finally {
                if (nat64DiscoveryQueryGeneration.compareAndSet(generation, -1L)) {
                    launchQueuedNat64Discovery()
                }
            }
            if (decision == null) return@launch
            nat64DiscoveryPendingGeneration.compareAndSet(generation, -1L)
            if (decision.changed) resetNat64Consumers()
            delay(decision.nextDelayMs)
            val retry = synchronized(underlyingContextLock) {
                if (stopping.get() || nat64DiscoveryGeneration.get() != generation ||
                    !shouldAttemptNat64Discovery(
                        underlyingContext.hasIpv4,
                        underlyingContext.nat64Prefixes.isNotEmpty(),
                        underlyingContext.dnsServers.isNotEmpty()
                    )
                ) {
                    null
                } else {
                    val nextGeneration = nat64DiscoveryGeneration.incrementAndGet()
                    nat64DiscoveryPendingGeneration.updateAndGet { pending ->
                        maxOf(pending, nextGeneration)
                    }
                    nextGeneration to underlyingContext.dnsServers
                }
            }
            retry?.let { (nextGeneration, nextServers) ->
                launchNat64Discovery(nextGeneration, nextServers)
            }
        }
    }

    private fun writeTunPacket(packet: ByteArray) {
        writeTunPacketFast(packet)
        diagnostics.recordTunWrite(packet.size)
    }

    private fun resetNat64Consumers() {
        runCatching { turboForwarder?.resetConnections() }
        runCatching { udpForwarder?.resetFlows() }
    }

    private fun translateDestinationCandidates(address: InetAddress): List<InetAddress> {
        var rediscoveryRequired = false
        var consumersRequireReset = false
        val snapshot = synchronized(underlyingContextLock) {
            val cachedPrefixes = nat64DiscoveryCache.current()
            val activePrefixes = underlyingContext.nat64Prefixes.ifEmpty { cachedPrefixes }
            val previousPrefixes = nat64Translator.currentAll()
            if (previousPrefixes != activePrefixes) {
                updateDns64Context {
                    updateNat64TranslatorLocked(activePrefixes, underlyingContext.interfaceIndex)
                    if (activePrefixes.isEmpty() && underlyingContext.nat64Prefixes.isEmpty()) {
                        nat64DiscoveryConfirmedAbsentGeneration.set(-1L)
                    }
                }
                consumersRequireReset = true
            }
            val staleLiteral = retiredNat64Prefixes.matches(address.address, activePrefixes)
            rediscoveryRequired = activePrefixes.isEmpty() && underlyingContext.dnsServers.isNotEmpty()
            val translated = when {
                staleLiteral -> emptyList()
                address.address.size == 4 && activePrefixes.isEmpty() -> listOf(address)
                else -> nat64Translator.translateAll(address)
            }
            translated to underlyingContext.hasIpv4
        }
        if (consumersRequireReset) resetNat64Consumers()
        if (rediscoveryRequired) requestExpiredNat64Rediscovery()
        val (translated, hasIpv4) = snapshot
        if (address.address.size != 4 || !hasIpv4) return translated
        return (listOf(address) + translated).distinctBy { candidate -> candidate.address.toList() }
    }

    private fun updateNat64TranslatorLocked(prefixes: List<Nat64Prefix>, scopeId: Int): Boolean {
        val normalized = prefixes.distinct().take(MAX_ACTIVE_NAT64_PREFIXES)
        val previous = nat64Translator.currentAll()
        retiredNat64Prefixes.transition(previous, normalized)
        val generation = nat64Translator.generation()
        nat64Translator.updateContext(normalized, scopeId)
        return nat64Translator.generation() != generation
    }

    private fun isUnderlyingLocalName(domain: String): Boolean {
        if (DnsPacket.isLocalOnlyName(domain)) return true
        val normalized = DomainBlocker.normalize(domain) ?: return false
        return localDnsSuffixes.get().any { suffix ->
            normalized == suffix || normalized.endsWith(".$suffix")
        }
    }

    private fun nat64RemainingTtlSeconds(): Long? {
        val remaining = synchronized(underlyingContextLock) {
            if (!shouldAttemptNat64Discovery(
                    underlyingContext.hasIpv4,
                    underlyingContext.nat64Prefixes.isNotEmpty(),
                    underlyingContext.dnsServers.isNotEmpty()
                )
            ) null
            else nat64DiscoveryCache.remainingTtlSeconds() ?: 0L
        }
        if (remaining == 0L) requestExpiredNat64Rediscovery()
        return remaining
    }

    private fun requestExpiredNat64Rediscovery() {
        if (stopping.get()) return
        if (nat64DiscoveryQueryGeneration.get() >= 0L) return
        if (!nat64RediscoveryPending.compareAndSet(false, true)) return
        val request = synchronized(underlyingContextLock) {
            if (!shouldAttemptNat64Discovery(
                    underlyingContext.hasIpv4,
                    underlyingContext.nat64Prefixes.isNotEmpty(),
                    underlyingContext.dnsServers.isNotEmpty()
                ) || !nat64DiscoveryCache.shouldRetry()
            ) {
                null
            } else {
                val nextGeneration = nat64DiscoveryGeneration.incrementAndGet()
                nat64DiscoveryPendingGeneration.updateAndGet { pending -> maxOf(pending, nextGeneration) }
                nextGeneration to underlyingContext.dnsServers
            }
        }
        if (request == null) {
            nat64RediscoveryPending.set(false)
        } else {
            nat64RediscoveryPending.set(false)
            launchNat64Discovery(request.first, request.second)
        }
    }

    private fun queueNat64DiscoveryHandoff() {
        if (stopping.get()) return
        nat64RediscoveryPending.set(true)
        if (nat64DiscoveryQueryGeneration.get() == -1L) launchQueuedNat64Discovery()
    }

    private fun launchQueuedNat64Discovery() {
        if (!nat64RediscoveryPending.compareAndSet(true, false)) return
        val request = synchronized(underlyingContextLock) {
            if (stopping.get() || !shouldAttemptNat64Discovery(
                    underlyingContext.hasIpv4,
                    underlyingContext.nat64Prefixes.isNotEmpty(),
                    underlyingContext.dnsServers.isNotEmpty()
                ) || !nat64DiscoveryCache.shouldRetry()
            ) {
                null
            } else {
                nat64DiscoveryGeneration.get() to underlyingContext.dnsServers
            }
        }
        request?.let { (generation, servers) -> launchNat64Discovery(generation, servers) }
    }

    private fun isAllowedSystemNat64Address(question: DnsPacket.Question, address: ByteArray): Boolean {
        if ((question.type != DnsPacket.TYPE_AAAA && question.type != DnsPacket.TYPE_SVCB &&
                question.type != DnsPacket.TYPE_HTTPS) || address.size != 16
        ) return false
        val generation = networkContextGeneration.get()
        if (generation and 1L != 0L) return false
        val match = nat64Translator.currentAll().sortedByDescending(Nat64Prefix::length)
            .firstNotNullOfOrNull { prefix ->
                prefix.extractIpv4(address)?.let { ipv4 -> prefix to ipv4 }
            } ?: return false
        val allowed = DnsMessageValidator.isPublicAddress(match.second) ||
            (isUnderlyingLocalName(question.domain) && !match.first.isWellKnown())
        return allowed && networkContextGeneration.get() == generation
    }

    private fun isNat64DiscoveryRequired(): Boolean = synchronized(underlyingContextLock) {
        !underlyingContext.hasIpv4 && underlyingContext.nat64Prefixes.isEmpty() &&
            nat64DiscoveryCache.current().isEmpty() && underlyingContext.dnsServers.isNotEmpty() &&
            nat64DiscoveryConfirmedAbsentGeneration.get() != nat64DiscoveryGeneration.get()
    }

    private fun protectTcpSocket(socket: java.net.Socket): Boolean {
        if (!service.protect(socket)) return false
        val snapshot = synchronized(underlyingContextLock) {
            underlyingContext.network to networkContextGeneration.get()
        }
        val network = snapshot.first ?: return true
        return runCatching {
            network.bindSocket(socket)
            synchronized(underlyingContextLock) {
                underlyingContext.network == network && networkContextGeneration.get() == snapshot.second
            }
        }.getOrDefault(false)
    }

    private fun protectDatagramSocket(socket: java.net.DatagramSocket): Boolean {
        if (!service.protect(socket)) return false
        val snapshot = synchronized(underlyingContextLock) {
            underlyingContext.network to networkContextGeneration.get()
        }
        val network = snapshot.first ?: return true
        return runCatching {
            network.bindSocket(socket)
            synchronized(underlyingContextLock) {
                underlyingContext.network == network && networkContextGeneration.get() == snapshot.second
            }
        }.getOrDefault(false)
    }

    private fun transformDohBootstrapAddresses(addresses: List<InetAddress>): List<InetAddress> {
        return synchronized(underlyingContextLock) {
            if (underlyingContext.hasIpv4) return@synchronized addresses
            val nativeIpv6 = addresses.filter { address -> address.address.size == 16 }
            val synthesized = addresses.asSequence()
                .filter { address -> address.address.size == 4 }
                .flatMap { address -> nat64Translator.translateAll(address).asSequence() }
                .filter { address -> address.address.size == 16 }
                .toList()
            (nativeIpv6 + synthesized)
                .distinctBy { address -> address.address.toList() }
                .take(MAX_DOH_BOOTSTRAP_ADDRESSES)
        }
    }

    private fun preferIpv6Bootstrap(): Boolean = synchronized(underlyingContextLock) {
        !underlyingContext.hasIpv4
    }

    private fun publishVpnUnderlyingNetwork(network: android.net.Network?) {
        val networks = network?.let { arrayOf(it) } ?: emptyArray()
        runCatching { service.setUnderlyingNetworks(networks) }
            .onSuccess { published ->
                if (!published) diagnostics.lastProtectionFailureReason = "underlying network publication failed"
            }
            .onFailure { diagnostics.lastProtectionFailureReason = "underlying network publication failed" }
    }

    private inline fun <T> updateDns64Context(block: () -> T): T {
        val unstableGeneration = networkContextGeneration.incrementAndGet()
        turboDomainMapper?.updateNetworkGeneration(unstableGeneration)
        return try {
            block()
        } finally {
            val stableGeneration = unstableGeneration + 1L
            turboDomainMapper?.updateNetworkGeneration(stableGeneration)
            networkContextGeneration.incrementAndGet()
        }
    }

    private fun writeTunPacketFast(packet: ByteArray) {
        check(!stopping.get()) { "TUN is stopping" }
        tunWriterFailure.get()?.let { throw java.io.IOException("TUN writer failed", it) }
        check(tunWriterThread != null) { "TUN output is closed" }
        val accepted = try {
            tunOutputQueue.offer(packet, TUN_OUTPUT_ENQUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.IOException("TUN write interrupted", error)
        }
        check(accepted) { "TUN output queue is saturated" }
        tunWriterFailure.get()?.let { throw java.io.IOException("TUN writer failed", it) }
    }

    private fun startTunWriter(stream: FileOutputStream) {
        synchronized(tunOutputLock) {
            check(tunWriterThread == null)
            tunWriterFailure.set(null)
            tunOutputQueue.clear()
            val writer = Thread {
                try {
                    while (!stopping.get()) {
                        val packet = tunOutputQueue.take()
                        stream.write(packet)
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Throwable) {
                    tunWriterFailure.compareAndSet(null, error)
                    if (!stopping.get()) onFatalPacketLoop("tun-write-failed")
                }
            }.apply {
                name = "TunnelTunWriter"
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
            tunWriterThread = writer
            writer.start()
        }
    }

    private fun closeResourcesQuietly() {
        val packetInput = input
        input = null
        runCatching { packetInput?.close() }
        val writer = synchronized(tunOutputLock) {
            tunWriterThread.also { tunWriterThread = null }
        }
        writer?.interrupt()
        if (writer !== Thread.currentThread()) runCatching { writer?.join(TUN_WRITER_JOIN_TIMEOUT_MS) }
        tunOutputQueue.clear()
        synchronized(tunOutputLock) {
            val packetOutput = output
            val tun = vpnInterface
            output = null
            vpnInterface = null
            runCatching { packetOutput?.close() }
            runCatching { tun?.close() }
        }
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        val job = packetJob
        packetJob = null
        job?.cancel()
        closeResourcesQuietly()
        ipv4PacketNormalizer.reset()
        ipv6PacketNormalizer.reset()
        runCatching { turboForwarder?.closeAll() }
        turboForwarder = null
        runCatching { turboAiRuntime?.close() }
        runCatching { udpForwarder?.closeAll() }
        udpForwarder = null
        runCatching { resolver.close() }
        runCatching { systemDnsTransport.close() }

        scope.cancel()
    }

    private suspend fun packetLoop(input: FileInputStream) {
        val packetLoopOwner = diagnostics.beginPacketLoop()
        val buffer = ByteArray(MAX_PACKET_SIZE)
        var fatalReason: String? = null
        try {
            while (scope.isActive && !stopping.get()) {
                val length = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    if (stopping.get()) break
                    diagnostics.packetReadErrors.incrementAndGet()
                    riskEngine.record(RiskSignal.MALFORMED_PACKET)
                    fatalReason = "tun-read-failed"
                    break
                }
                if (length < 0) {
                    if (!stopping.get()) fatalReason = "tun-read-ended"
                    break
                }
                if (length == 0) continue
                try {
                    val version = (buffer[0].toInt() ushr 4) and 0x0f
                    if (version == IPV6_VERSION) {
                        val normalized = ipv6PacketNormalizer.process(buffer, length)
                        ipv6PacketNormalizer.drainIcmpErrors().forEach { response ->
                            runCatching { writeTunPacket(response) }
                                .onFailure { diagnostics.packetWriteErrors.incrementAndGet() }
                        }
                        if (normalized is Ipv6PacketNormalizer.Result.Pending) continue
                        if (normalized is Ipv6PacketNormalizer.Result.Rejected) {
                            diagnostics.ipv6PacketsBlocked.incrementAndGet()
                            continue
                        }
                        val ready = normalized as Ipv6PacketNormalizer.Result.Ready
                        val v6packet = ready.packet
                        val v6length = v6packet.size
                        Ipv6IcmpLocalResponder.respond(v6packet, v6length)?.let { response ->
                            val replies = Ipv6PacketFragmenter.fragment(
                                response,
                                establishedTunMtu,
                                ipv6FragmentIdentification.getAndIncrement()
                            )
                            if (replies == null) {
                                diagnostics.packetWriteErrors.incrementAndGet()
                            } else {
                                runCatching { replies.forEach(::writeTunPacket) }
                                    .onFailure { diagnostics.packetWriteErrors.incrementAndGet() }
                            }
                            continue
                        }
                        if (shouldDropIpv6BeforeForwarding(v6packet, v6length)) {
                            diagnostics.ipv6PacketsBlocked.incrementAndGet()
                            continue
                        }
                        if (fullForwardEnabled && udp443BlockPolicy.shouldDropIpv6Packet(v6packet, v6length)) {
                            diagnostics.recordTurboUdp443Blocked()
                            if (ipv6IcmpErrorRateLimiter.tryAcquire()) {
                                Ipv6IcmpPortUnreachable.build(
                                    v6packet,
                                    v6length,
                                    ready.invokingPacket
                                )?.let { rejection ->
                                    runCatching { writeTunPacket(rejection) }
                                        .onFailure { diagnostics.packetWriteErrors.incrementAndGet() }
                                }
                            }
                            continue
                        }
                        if (turboForwarder?.handleIpv6Packet(v6packet, v6length) == true) {
                            continue
                        }
                        if (fullForwardEnabled &&
                            udpForwarder?.handleIpv6Packet(
                                v6packet,
                                v6length,
                                ready.reassembled,
                                ready.invokingPacket
                            ) == true
                        ) {
                            continue
                        }
                        if (isIpv6DnsPacket(v6packet, v6length)) {
                            if (!dnsConcurrency.tryAcquire()) {
                                dnsEngine.buildCapacityServFailIpv6Packet(v6packet, v6length)?.let { response ->
                                    try {
                                        writeTunPacket(response)
                                    } catch (_: Exception) {
                                        diagnostics.packetWriteErrors.incrementAndGet()
                                    }
                                }
                                continue
                            }
                            scope.launch {
                                try {
                                    val response = dnsEngine.handleIpv6Packet(v6packet, v6length) ?: return@launch
                                    if (stopping.get()) return@launch
                                    try {
                                        writeTunPacket(response)
                                    } catch (_: Exception) {
                                        diagnostics.packetWriteErrors.incrementAndGet()
                                    }
                                } finally {
                                    dnsConcurrency.release()
                                }
                            }
                            continue
                        }
                        diagnostics.ipv6PacketsBlocked.incrementAndGet()
                        continue
                    }
                    val normalizedIpv4 = ipv4PacketNormalizer.process(buffer, length)
                    if (normalizedIpv4 is Ipv4PacketNormalizer.Result.Pending) continue
                    if (normalizedIpv4 is Ipv4PacketNormalizer.Result.Rejected) continue
                    val readyIpv4 = normalizedIpv4 as Ipv4PacketNormalizer.Result.Ready
                    val packet = readyIpv4.packet
                    val ipv4Length = packet.size
                    if (shouldDropIpv4BeforeForwarding(packet, ipv4Length)) {
                        continue
                    }
                    if (fullForwardEnabled && udp443BlockPolicy.shouldDropIpv4Packet(packet, ipv4Length)) {
                        diagnostics.recordTurboUdp443Blocked()
                        Ipv4IcmpPortUnreachable.build(packet, ipv4Length)?.let { rejection ->
                            runCatching { writeTunPacket(rejection) }
                                .onFailure { diagnostics.packetWriteErrors.incrementAndGet() }
                        }
                        continue
                    }
                    if (turboForwarder?.handleIpv4Packet(packet, ipv4Length) == true) {
                        continue
                    }
                    if (fullForwardEnabled && udpForwarder?.handleIpv4Packet(
                            packet,
                            ipv4Length,
                            readyIpv4.reassembled
                        ) == true
                    ) {
                        continue
                    }
                    diagnostics.recordTunRead(ipv4Length)
                    when (packet[9].toInt() and 0xff) {
                        1 -> diagnostics.icmpPacketsDroppedNoHandler.incrementAndGet()
                        17 -> {
                            val ihl = (packet[0].toInt() and 0x0f) * 4
                            if (ihl in 20..(ipv4Length - 4)) {
                                val dstPort = ((packet[ihl + 2].toInt() and 0xff) shl 8) or
                                    (packet[ihl + 3].toInt() and 0xff)
                                if (dstPort != 53) diagnostics.udpPacketsDroppedNoHandler.incrementAndGet()
                            }
                        }
                    }
                    if (config.mode.protectsSni) tlsInspector.inspectIpv4(packet, ipv4Length)
                    if (!isIpv4DnsPacket(packet, ipv4Length)) continue
                    if (!dnsConcurrency.tryAcquire()) {
                        dnsEngine.buildCapacityServFailIpv4Packet(packet, ipv4Length)?.let { response ->
                            try {
                                writeTunPacket(response)
                            } catch (_: Exception) {
                                diagnostics.packetWriteErrors.incrementAndGet()
                            }
                        }
                        continue
                    }
                    scope.launch {
                        try {
                            val response = dnsEngine.handleIpv4Packet(packet, ipv4Length) ?: return@launch
                            if (stopping.get()) return@launch
                            try {
                                writeTunPacket(response)
                            } catch (_: Exception) {
                                diagnostics.packetWriteErrors.incrementAndGet()
                            }
                        } finally {
                            dnsConcurrency.release()
                        }
                    }
                } catch (_: Exception) {
                    diagnostics.packetReadErrors.incrementAndGet()
                    riskEngine.record(RiskSignal.MALFORMED_PACKET)
                }
            }
        } finally {
            diagnostics.endPacketLoop(packetLoopOwner)
            fatalReason?.let { reason ->
                if (!stopping.get()) runCatching { onFatalPacketLoop(reason) }
            }
        }
    }

    private fun loadBundledBlockSuffixes(): Set<String> {
        return runCatching {
            service.assets.open(BLOCK_LIST_ASSET).bufferedReader().useLines { lines ->
                DomainBlocker.parseSuffixList(lines)
            }
        }.getOrDefault(emptySet())
    }

    private fun isIpv4DnsPacket(packet: ByteArray, length: Int): Boolean {
        val transport = parseIpv4TransportForSecurity(packet, length) ?: return false
        return transport.protocol == UDP_PROTOCOL && transport.destinationPort == DNS_PORT
    }

    private fun isIpv6DnsPacket(packet: ByteArray, length: Int): Boolean {
        val transport = parseIpv6TransportForSecurity(packet, length) ?: return false
        return transport.extensionCount == 0 &&
            transport.protocol == UDP_PROTOCOL && transport.destinationPort == DNS_PORT
    }

    companion object {
        private const val TUN_ADDRESS = "10.111.0.2"
        private const val VIRTUAL_DNS = "10.111.0.1"
        private const val TUN_IPV6_ADDRESS = "fd00:111::2"
        private const val VIRTUAL_DNS_IPV6 = "fd00:111::1"
        private const val MAX_PACKET_SIZE = 65535
        private const val COMPATIBILITY_TUN_MTU = 9000
        private const val FALLBACK_TUN_MTU = 1500
        private const val TUN_OUTPUT_QUEUE_CAPACITY = 1_024
        private const val TUN_OUTPUT_ENQUEUE_TIMEOUT_MS = 1_000L
        private const val TUN_WRITER_JOIN_TIMEOUT_MS = 2_000L
        private const val IPV6_VERSION = 6
        private const val IPV6_HEADER_SIZE = 40
        private const val IPV6_HOP_BY_HOP = 0
        private const val IPV6_ROUTING = 43
        private const val IPV6_FRAGMENT = 44
        private const val IPV6_ESP = 50
        private const val IPV6_AUTHENTICATION = 51
        private const val IPV6_NO_NEXT_HEADER = 59
        private const val IPV6_DESTINATION_OPTIONS = 60
        private const val IPV6_MOBILITY = 135
        private const val IPV6_HIP = 139
        private const val IPV6_SHIM6 = 140
        private const val MAX_IPV6_EXTENSION_HEADERS = 8
        private const val MAX_IPV6_EXTENSION_BYTES = 256
        private const val BLOCK_LIST_ASSET = "adblock_suffixes.txt"
        private const val IPV4_ONLY_ARPA = "ipv4only.arpa"
        private const val DNS_TRANSACTION_ID_LIMIT = 65_536
        private const val IPV6_REASSEMBLY_SWEEP_INTERVAL_MS = 1_000L
        private val DISCOVERY_TRANSACTION_RANDOM = SecureRandom()
        private const val NAT64_REFRESH_ADVANCE_SECONDS = 10L
        private const val NAT64_RETRY_SECONDS = 5L
        private const val MAX_NAT64_DISCOVERY_TTL_SECONDS = 86_400L
        private const val MAX_ACTIVE_NAT64_PREFIXES = 32
        private const val MAX_RETIRED_NAT64_PREFIXES = 256
        private const val DNS_TYPE_CNAME = 5
        private const val DNS_TYPE_DNAME = 39
        private const val NAME_ERROR_RCODE = 3
        private const val IPV4_HEADER_SIZE = 20
        private const val UDP_HEADER_SIZE = 8
        private const val TCP_MIN_HEADER_SIZE = 20
        private const val UDP_PROTOCOL = 17
        private const val TCP_PROTOCOL = 6
        private const val DNS_PORT = 53
        private const val DNS_OVER_TLS_PORT = 853
        private const val DNS_OVER_QUIC_PORT = 784
        private const val DNS_OVER_QUIC_ALTERNATE_PORT = 8853
        private const val MAX_CONCURRENT_DNS_QUERIES = 32
        private const val MAX_DOH_BOOTSTRAP_ADDRESSES = 16

        internal fun shouldDropIpv4BeforeForwarding(packet: ByteArray, length: Int): Boolean {
            val transport = parseIpv4TransportForSecurity(packet, length) ?: return true
            return isBlockedDnsBypassTransport(transport.protocol, transport.destinationPort)
        }

        internal fun shouldAttemptNat64Discovery(
            hasIpv4: Boolean,
            hasNat64Prefix: Boolean,
            hasDnsServer: Boolean
        ): Boolean = !hasIpv4 && !hasNat64Prefix && hasDnsServer

        internal fun isIpv4TcpDnsPacket(packet: ByteArray, length: Int): Boolean {
            val transport = parseIpv4TransportForSecurity(packet, length) ?: return false
            return transport.protocol == TCP_PROTOCOL && transport.destinationPort == DNS_PORT
        }

        internal fun shouldDropIpv6BeforeForwarding(packet: ByteArray, length: Int): Boolean {
            val transport = parseIpv6TransportForSecurity(packet, length) ?: return true
            return transport.extensionCount != 0 ||
                (transport.protocol != TCP_PROTOCOL && transport.protocol != UDP_PROTOCOL) ||
                isBlockedDnsBypassTransport(transport.protocol, transport.destinationPort)
        }

        internal fun isIpv6DnsTransportPacket(packet: ByteArray, length: Int): Boolean {
            val transport = parseIpv6TransportForSecurity(packet, length) ?: return false
            return (transport.protocol == TCP_PROTOCOL || transport.protocol == UDP_PROTOCOL) &&
                transport.destinationPort == DNS_PORT
        }

        private fun isBlockedDnsBypassTransport(protocol: Int, destinationPort: Int?): Boolean {
            return when (protocol) {
                TCP_PROTOCOL -> destinationPort == DNS_OVER_TLS_PORT
                UDP_PROTOCOL -> destinationPort == DNS_OVER_TLS_PORT ||
                    destinationPort == DNS_OVER_QUIC_PORT ||
                    destinationPort == DNS_OVER_QUIC_ALTERNATE_PORT
                else -> false
            }
        }

        internal fun parseIpv6TransportForSecurity(packet: ByteArray, length: Int): Ipv6TransportView? {
            if (length !in IPV6_HEADER_SIZE..packet.size) return null
            if (((packet[0].toInt() ushr 4) and 0x0f) != IPV6_VERSION) return null
            if (IPV6_HEADER_SIZE + readUnsignedShortStatic(packet, 4) != length) return null
            if (!Ipv6FullForwardRoutePolicy.isProxyableUnicast(packet, 8) ||
                !Ipv6FullForwardRoutePolicy.isProxyableUnicast(packet, 24)
            ) return null
            var protocol = packet[6].toInt() and 0xff
            var offset = IPV6_HEADER_SIZE
            var extensionCount = 0
            var extensionBytes = 0
            while (isIpv6ExtensionHeader(protocol)) {
                if (extensionCount >= MAX_IPV6_EXTENSION_HEADERS) return null
                if (protocol == IPV6_FRAGMENT) return null
                if (protocol == IPV6_HOP_BY_HOP && offset != IPV6_HEADER_SIZE) return null
                if (offset + 2 > length) return null
                val nextProtocol = packet[offset].toInt() and 0xff
                val headerLength = if (protocol == IPV6_AUTHENTICATION) {
                    ((packet[offset + 1].toInt() and 0xff) + 2) * 4
                } else {
                    ((packet[offset + 1].toInt() and 0xff) + 1) * 8
                }
                if (headerLength < 8 || headerLength > length - offset) return null
                extensionBytes += headerLength
                if (extensionBytes > MAX_IPV6_EXTENSION_BYTES) return null
                offset += headerLength
                protocol = nextProtocol
                extensionCount += 1
            }
            val minimumTransportLength = when (protocol) {
                TCP_PROTOCOL -> TCP_MIN_HEADER_SIZE
                UDP_PROTOCOL -> UDP_HEADER_SIZE
                else -> 0
            }
            if (offset + minimumTransportLength > length) return null
            val destinationPort = if (protocol == TCP_PROTOCOL || protocol == UDP_PROTOCOL) {
                readUnsignedShortStatic(packet, offset + 2)
            } else {
                null
            }
            if (protocol == TCP_PROTOCOL) {
                val tcpHeaderLength = ((packet[offset + 12].toInt() ushr 4) and 0x0f) * 4
                if (tcpHeaderLength < TCP_MIN_HEADER_SIZE || offset + tcpHeaderLength > length) return null
                if (!Ipv6TransportChecksum.isValid(
                        packet = packet,
                        packetLength = length,
                        transportOffset = offset,
                        transportLength = length - offset,
                        protocol = TCP_PROTOCOL
                    )
                ) return null
            }
            if (protocol == UDP_PROTOCOL) {
                val udpLength = readUnsignedShortStatic(packet, offset + 4)
                if (udpLength < UDP_HEADER_SIZE || udpLength != length - offset) return null
                if (!Ipv6TransportChecksum.isValid(
                        packet = packet,
                        packetLength = length,
                        transportOffset = offset,
                        transportLength = udpLength,
                        protocol = UDP_PROTOCOL,
                        rejectZeroChecksumAt = UDP_CHECKSUM_OFFSET
                    )
                ) return null
            }
            if (protocol == IPV6_ESP || protocol == IPV6_NO_NEXT_HEADER) return null
            return Ipv6TransportView(protocol, destinationPort, offset, extensionCount)
        }

        private fun parseIpv4TransportForSecurity(packet: ByteArray, length: Int): Ipv4TransportView? {
            if (length !in IPV4_HEADER_SIZE..packet.size) return null
            if (((packet[0].toInt() ushr 4) and 0x0f) != 4) return null
            val headerLength = (packet[0].toInt() and 0x0f) * 4
            if (headerLength !in IPV4_HEADER_SIZE..60 || headerLength > length) return null
            if (readUnsignedShortStatic(packet, 2) != length) return null
            val fragmentField = readUnsignedShortStatic(packet, 6)
            if (fragmentField and 0x8000 != 0 || fragmentField and 0x2000 != 0 || fragmentField and 0x1fff != 0) {
                return null
            }
            val protocol = packet[9].toInt() and 0xff
            val minimumTransportLength = when (protocol) {
                TCP_PROTOCOL -> TCP_MIN_HEADER_SIZE
                UDP_PROTOCOL -> UDP_HEADER_SIZE
                else -> 0
            }
            if (headerLength + minimumTransportLength > length) return null
            val destinationPort = if (protocol == TCP_PROTOCOL || protocol == UDP_PROTOCOL) {
                readUnsignedShortStatic(packet, headerLength + 2)
            } else {
                null
            }
            if (protocol == TCP_PROTOCOL) {
                val tcpHeaderLength = ((packet[headerLength + 12].toInt() ushr 4) and 0x0f) * 4
                if (tcpHeaderLength < TCP_MIN_HEADER_SIZE || headerLength + tcpHeaderLength > length) return null
            }
            if (protocol == UDP_PROTOCOL) {
                val udpLength = readUnsignedShortStatic(packet, headerLength + 4)
                if (udpLength < UDP_HEADER_SIZE || udpLength != length - headerLength) return null
            }
            return Ipv4TransportView(protocol, destinationPort)
        }

        private fun isIpv6ExtensionHeader(protocol: Int): Boolean {
            return protocol == IPV6_HOP_BY_HOP || protocol == IPV6_ROUTING ||
                protocol == IPV6_FRAGMENT || protocol == IPV6_AUTHENTICATION ||
                protocol == IPV6_DESTINATION_OPTIONS || protocol == IPV6_MOBILITY ||
                protocol == IPV6_HIP || protocol == IPV6_SHIM6
        }

        private fun readUnsignedShortStatic(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        }

        private const val UDP_CHECKSUM_OFFSET = 6
    }
}
