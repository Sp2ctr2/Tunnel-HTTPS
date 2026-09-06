package com.tunnelvpn.app

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

data class UnderlyingNetworkContext(
    val generation: Long,
    val nat64Prefixes: List<Nat64Prefix>,
    val dnsServers: List<InetAddress>,
    val interfaceName: String?,
    val interfaceIndex: Int,
    val localDnsSuffixes: List<String> = emptyList(),
    val hasIpv4: Boolean = false,
    val network: Network? = null
)

class NetworkChangeHandler(
    private val connectivityManager: ConnectivityManager?,
    private val diagnostics: DiagnosticsState,
    private val onNetworkChanged: () -> Unit = {},
    private val onNetworkContextChanged: () -> Unit = {},
    private val onNat64PrefixChanged: (Nat64Prefix?) -> Unit = {},
    private val onDnsServersChanged: (List<InetAddress>) -> Unit = {},
    private val onUnderlyingContextChanged: (UnderlyingNetworkContext) -> Unit = {}
) {
    private data class CapabilitiesState(
        val internet: Boolean,
        val validated: Boolean,
        val notMetered: Boolean,
        val notRoaming: Boolean,
        val wifi: Boolean,
        val cellular: Boolean,
        val ethernet: Boolean
    )

    private data class LinkState(
        val interfaceName: String?,
        val interfaceIndex: Int,
        val linkAddresses: List<String>,
        val dnsServers: List<String>,
        val routes: List<String>,
        val mtu: Int,
        val nat64Prefix: Nat64Prefix?,
        val privateDnsActive: Boolean,
        val privateDnsServerName: String?,
        val localDnsSuffixes: List<String>,
        val hasIpv4: Boolean
    )

    private val stateLock = Any()
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private val generationTracker = NetworkGenerationTracker()
    private var lastCapabilitiesState: CapabilitiesState? = null
    private var lastLinkState: LinkState? = null
    private var lastCapabilitiesFingerprint = 0
    private var lastLinkFingerprint = 0
    private val context = AtomicReference(
        UnderlyingNetworkContext(0L, emptyList(), emptyList(), null, 0)
    )

    fun start() {
        val manager = connectivityManager ?: return
        val created = synchronized(stateLock) {
            if (callback != null) return
            diagnostics.recordUnderlyingNetworkValidated(false)
            createCallback().also { callback = it }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.registerBestMatchingNetworkCallback(
                    request,
                    created,
                    Handler(Looper.getMainLooper())
                )
            } else {
                manager.requestNetwork(request, created)
            }
        } catch (error: Exception) {
            synchronized(stateLock) {
                if (callback === created) callback = null
                currentNetwork = null
                lastCapabilitiesState = null
                lastLinkState = null
                lastCapabilitiesFingerprint = 0
                lastLinkFingerprint = 0
                generationTracker.invalidate()
                context.set(emptyContext())
            }
            throw error
        }
    }

    private fun createCallback(): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleAvailable(this, network)
            }

            override fun onLost(network: Network) {
                handleLost(this, network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handleCapabilities(this, network, networkCapabilities)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                handleLinkProperties(this, network, linkProperties)
            }
        }
    }

    private fun handleAvailable(owner: ConnectivityManager.NetworkCallback, network: Network) {
        val update = synchronized(stateLock) {
            if (callback !== owner) return
            diagnostics.lastProtectionFailureReason = ""
            val previous = currentNetwork
            currentNetwork = network
            val changed = previous != network
            if (previous != network) {
                lastCapabilitiesState = null
                lastLinkState = null
                lastCapabilitiesFingerprint = 0
                lastLinkFingerprint = 0
            }
            generationTracker.onAvailable(network.toString())
            val next = if (changed) emptyContext(network) else context.get()
            context.set(next)
            AvailableUpdate(changed, previous != null && changed, next)
        }
        diagnostics.recordUnderlyingNetworkValidated(false)
        if (update.changed) {
            runCatching { onNat64PrefixChanged(null) }
            runCatching { onDnsServersChanged(emptyList()) }
            runCatching { onUnderlyingContextChanged(update.context) }
        }
        if (update.reset) {
            diagnostics.recordNetworkChangeReset()
            runCatching { onNetworkChanged() }
                .onFailure { diagnostics.lastProtectionFailureReason = "network reset failed" }
        }
    }

    private fun handleLost(owner: ConnectivityManager.NetworkCallback, network: Network) {
        val next = synchronized(stateLock) {
            if (callback !== owner || network != currentNetwork) return
            currentNetwork = null
            lastCapabilitiesState = null
            lastLinkState = null
            lastCapabilitiesFingerprint = 0
            lastLinkFingerprint = 0
            generationTracker.onLost(network.toString())
            emptyContext().also(context::set)
        }
        diagnostics.lastProtectionFailureReason = "network lost"
        diagnostics.recordUnderlyingNetworkValidated(false)
        runCatching { onNat64PrefixChanged(null) }
        runCatching { onDnsServersChanged(emptyList()) }
        runCatching { onUnderlyingContextChanged(next) }
        diagnostics.recordNetworkChangeReset()
        runCatching { onNetworkChanged() }
            .onFailure { diagnostics.lastProtectionFailureReason = "network loss reset failed" }
    }

    private fun handleCapabilities(
        owner: ConnectivityManager.NetworkCallback,
        network: Network,
        value: NetworkCapabilities
    ) {
        val state = capabilitiesState(value)
        val update = synchronized(stateLock) {
            if (callback !== owner || network != currentNetwork || state == lastCapabilitiesState) return
            val prior = lastCapabilitiesState
            lastCapabilitiesState = state
            lastCapabilitiesFingerprint = capabilitiesFingerprint(value)
            if (prior != null) generationTracker.onContextChanged(network.toString())
            val current = context.get()
            val next = current.copy(generation = generationTracker.current())
            context.set(next)
            prior to next
        }
        val validated = state.internet && state.validated
        diagnostics.recordUnderlyingNetworkValidated(validated)
        diagnostics.lastProtectionFailureReason = if (validated) "" else "active network is not validated"
        runCatching { onUnderlyingContextChanged(update.second) }
            .onFailure { diagnostics.lastProtectionFailureReason = "network publication failed" }
        if (update.first != null) {
            runCatching { onNetworkContextChanged() }
                .onFailure { diagnostics.lastProtectionFailureReason = "network context refresh failed" }
        }
    }

    private fun handleLinkProperties(
        owner: ConnectivityManager.NetworkCallback,
        network: Network,
        linkProperties: LinkProperties
    ) {
        val discoveredPrefix = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            linkProperties.nat64Prefix?.let { prefix ->
                Nat64Prefix.from(prefix.address.address, prefix.prefixLength)
            }
        } else {
            null
        }
        val interfaceName = linkProperties.interfaceName
        val interfaceIndex = runCatching {
            interfaceName?.let(NetworkInterface::getByName)?.index ?: 0
        }.getOrDefault(0).coerceAtLeast(0)
        val discoveredDnsServers = scopeAddresses(linkProperties.dnsServers, interfaceIndex)
            .distinctBy { address -> AddressIdentity(address) }
            .take(MAX_DNS_SERVERS)
        val localDnsSuffixes = linkProperties.domains.orEmpty()
            .split(Regex("\\s+"))
            .mapNotNull(DomainBlocker::normalize)
            .distinct()
            .take(MAX_LOCAL_DNS_SUFFIXES)
        val hasIpv4 = linkProperties.linkAddresses.any { address -> address.address is Inet4Address }
        val state = linkState(
            linkProperties,
            discoveredPrefix,
            discoveredDnsServers,
            interfaceIndex,
            localDnsSuffixes,
            hasIpv4
        )
        val update = synchronized(stateLock) {
            if (callback !== owner || network != currentNetwork || state == lastLinkState) return
            val previous = lastLinkState
            val prior = context.get()
            lastLinkState = state
            lastLinkFingerprint = state.hashCode()
            generationTracker.onContextChanged(network.toString())
            val next = UnderlyingNetworkContext(
                generationTracker.current(),
                listOfNotNull(discoveredPrefix),
                discoveredDnsServers.toList(),
                interfaceName,
                interfaceIndex,
                localDnsSuffixes,
                hasIpv4,
                network
            )
            context.set(next)
            LinkUpdate(
                prefixChanged = prior.nat64Prefixes.firstOrNull() != discoveredPrefix,
                dnsChanged = prior.dnsServers != discoveredDnsServers,
                reset = requiresFlowReset(previous, state),
                context = next
            )
        }
        if (update.prefixChanged) {
            runCatching { onNat64PrefixChanged(discoveredPrefix) }
                .onFailure { diagnostics.lastProtectionFailureReason = "NAT64 prefix update failed" }
        }
        if (update.dnsChanged) {
            runCatching { onDnsServersChanged(discoveredDnsServers) }
                .onFailure { diagnostics.lastProtectionFailureReason = "DNS server update failed" }
        }
        runCatching { onUnderlyingContextChanged(update.context) }
            .onFailure { diagnostics.lastProtectionFailureReason = "network publication failed" }
        if (update.reset) {
            diagnostics.recordNetworkChangeReset()
            runCatching { onNetworkChanged() }
                .onFailure { diagnostics.lastProtectionFailureReason = "network link reset failed" }
        }
    }

    fun stop() {
        val manager = connectivityManager ?: return
        val stopped = synchronized(stateLock) {
            val value = callback
            callback = null
            currentNetwork = null
            lastCapabilitiesState = null
            lastLinkState = null
            lastCapabilitiesFingerprint = 0
            lastLinkFingerprint = 0
            generationTracker.invalidate()
            context.set(emptyContext())
            value
        }
        stopped?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        diagnostics.recordUnderlyingNetworkValidated(false)
        runCatching { onNat64PrefixChanged(null) }
        runCatching { onDnsServersChanged(emptyList()) }
        runCatching { onUnderlyingContextChanged(context.get()) }
    }

    fun generation(): Long = context.get().generation

    fun currentNat64Prefix(): Nat64Prefix? = context.get().nat64Prefixes.firstOrNull()

    fun currentDnsServers(): List<InetAddress> = context.get().dnsServers.toList()

    fun currentContext(): UnderlyingNetworkContext {
        val value = context.get()
        return value.copy(
            nat64Prefixes = value.nat64Prefixes.toList(),
            dnsServers = value.dnsServers.toList(),
            localDnsSuffixes = value.localDnsSuffixes.toList()
        )
    }

    private fun emptyContext(network: Network? = null): UnderlyingNetworkContext {
        return UnderlyingNetworkContext(generationTracker.current(), emptyList(), emptyList(), null, 0, network = network)
    }

    private fun capabilitiesState(value: NetworkCapabilities): CapabilitiesState {
        val notRoaming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            value.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            true
        }
        return CapabilitiesState(
            internet = value.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notMetered = value.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            notRoaming = notRoaming,
            wifi = value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = value.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )
    }

    private fun requiresFlowReset(previous: LinkState?, current: LinkState): Boolean {
        return previous != null && (
            previous.interfaceName != current.interfaceName ||
                previous.interfaceIndex != current.interfaceIndex ||
                previous.linkAddresses != current.linkAddresses
            )
    }

    private fun capabilitiesFingerprint(value: NetworkCapabilities): Int {
        return capabilitiesState(value).hashCode()
    }

    private fun linkState(
        value: LinkProperties,
        prefix: Nat64Prefix?,
        servers: List<InetAddress>,
        interfaceIndex: Int,
        localDnsSuffixes: List<String>,
        hasIpv4: Boolean
    ): LinkState {
        val mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) value.mtu else 0
        val privateDnsActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            value.isPrivateDnsActive
        } else {
            false
        }
        val privateDnsServerName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            value.privateDnsServerName
        } else {
            null
        }
        return LinkState(
            value.interfaceName,
            interfaceIndex,
            value.linkAddresses.map { address -> address.toString() },
            servers.map { address -> AddressIdentity(address).toString() },
            value.routes.map { route -> route.toString() },
            mtu,
            prefix,
            privateDnsActive,
            privateDnsServerName,
            localDnsSuffixes,
            hasIpv4
        )
    }

    private fun scopeAddresses(values: List<InetAddress>, interfaceIndex: Int): List<InetAddress> {
        if (interfaceIndex == 0) return values.toList()
        return values.map { address ->
            if (address is Inet6Address && address.scopeId == 0 && requiresScope(address)) {
                runCatching { Inet6Address.getByAddress(null, address.address, interfaceIndex) }
                    .getOrDefault(address)
            } else {
                address
            }
        }
    }

    private fun requiresScope(address: Inet6Address): Boolean {
        return address.isLinkLocalAddress || address.isMCNodeLocal || address.isMCLinkLocal ||
            address.isMCSiteLocal || address.isMCOrgLocal
    }

    private data class AddressIdentity(val bytes: List<Byte>, val scopeId: Int) {
        constructor(address: InetAddress) : this(
            address.address.toList(),
            (address as? Inet6Address)?.scopeId ?: 0
        )
    }

    private data class AvailableUpdate(
        val changed: Boolean,
        val reset: Boolean,
        val context: UnderlyingNetworkContext
    )

    private data class LinkUpdate(
        val prefixChanged: Boolean,
        val dnsChanged: Boolean,
        val reset: Boolean,
        val context: UnderlyingNetworkContext
    )

    companion object {
        private const val MAX_DNS_SERVERS = 4
        private const val MAX_LOCAL_DNS_SUFFIXES = 16
    }
}
