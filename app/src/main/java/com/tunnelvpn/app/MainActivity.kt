package com.tunnelvpn.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity() {
    private data class DiagnosticsRequest(
        val requestId: Long,
        val epoch: Long,
        val serviceGeneration: Long
    )

    private lateinit var webView: WebView
    private var pendingStart = false
    private var splitDomains = emptyList<String>()
    private var bypassPackages = emptyList<String>()
    private var protectionMode = TunnelVpnService.MODE_HTTPS_LOCAL
    private var routeAllTraffic = true
    private var dnsServer = "1.1.1.1"
    private var batterySaver = false
    private var dnsOverHttps = true
    private var adBlock = true
    private var turboMode = false
    private var browserTrafficOnly = false
    private var autoStart = false
    private val diagnosticsClient = OkHttpClient.Builder()
        .connectTimeout(2200, TimeUnit.MILLISECONDS)
        .readTimeout(2200, TimeUnit.MILLISECONDS)
        .writeTimeout(1800, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diagnosticsRequestInFlight = AtomicBoolean(false)
    private val diagnosticsPendingRequest = AtomicReference<DiagnosticsRequest?>(null)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TunnelVpnService.EXTRA_STATE)
                ?: TunnelVpnService.STATE_DISCONNECTED
            val seconds = intent?.getLongExtra(TunnelVpnService.EXTRA_CONNECTED_SECONDS, 0L) ?: 0L
            val error = intent?.getStringExtra(TunnelVpnService.EXTRA_ERROR).orEmpty()
            val generation = intent?.getLongExtra(
                TunnelVpnService.EXTRA_SERVICE_GENERATION,
                TunnelVpnService.currentServiceGeneration()
            ) ?: TunnelVpnService.currentServiceGeneration()
            val reason = intent?.getStringExtra(TunnelVpnService.EXTRA_TRANSITION_REASON)
                ?: TunnelVpnService.currentTransitionReason()
            val turbo = turboSnapshotFromIntent(intent)
            runOnUiThread {
                syncStateToWeb(state, seconds, error, reason, generation)
                syncTurboToWeb(turbo)
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        loadSavedConfig()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return true
                    return url.scheme != "https" ||
                        url.host != WebViewAssetLoader.DEFAULT_DOMAIN ||
                        url.port != -1 ||
                        !url.path.orEmpty().startsWith("/assets/")
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url ?: return blockedWebResource()
                    assetLoader.shouldInterceptRequest(url)?.let { return it }
                    return blockedWebResource()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    syncStateToWeb()
                    syncTurboToWeb()
                }
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            addJavascriptInterface(Bridge(), "TunnelAndroid")
            loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/index.html")
        }
        setContentView(webView)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncStateToWeb()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(TunnelVpnService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (::webView.isInitialized) syncStateToWeb()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(stateReceiver) }
        if (::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onDestroy() {
        diagnosticsScope.cancel()
        if (::webView.isInitialized) {
            runCatching {
                webView.removeJavascriptInterface("TunnelAndroid")
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
        diagnosticsClient.connectionPool.evictAll()
        diagnosticsClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startTunnelService()
            } else {
                pendingStart = false
                syncStateToWeb(TunnelVpnService.STATE_DISCONNECTED, 0L)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            requestVpnPermission()
        }
    }

    private fun requestStart() {
        if (!VpnDisclosureConsent.isAccepted(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.vpn_disclosure_title)
                .setMessage(R.string.vpn_disclosure_message)
                .setCancelable(false)
                .setNegativeButton(R.string.vpn_disclosure_decline) { dialog, _ ->
                    pendingStart = false
                    dialog.dismiss()
                    syncStateToWeb(TunnelVpnService.STATE_DISCONNECTED, 0L)
                }
                .setPositiveButton(R.string.vpn_disclosure_accept) { dialog, _ ->
                    VpnDisclosureConsent.accept(this)
                    dialog.dismiss()
                    requestStart()
                }
                .show()
            return
        }
        pendingStart = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VPN)
        } else {
            startTunnelService()
        }
    }

    private fun startTunnelService() {
        if (!pendingStart) return
        saveConfig()
        val intent = buildTunnelIntent()
        pendingStart = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun buildTunnelIntent(
        transitionReason: String = TunnelVpnService.TRANSITION_REASON_USER_START
    ): Intent {
        validateSplitDomainInput(splitDomains)
        validateBypassPackageInput(bypassPackages)
        turboMode = TurboRuntimeController.snapshot().desired
        return Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra(TunnelVpnService.EXTRA_DNS_SERVER, dnsServer)
            putExtra(TunnelVpnService.EXTRA_PROTECTION_MODE, protectionMode)
            putExtra(
                TunnelVpnService.EXTRA_MTU,
                if (batterySaver) TunnelVpnService.BATTERY_SAVER_TUN_MTU else TunnelVpnService.PERFORMANCE_TUN_MTU
            )
            putExtra(TunnelVpnService.EXTRA_ROUTE_ALL, routeAllTraffic)
            putExtra(TunnelVpnService.EXTRA_DNS_PROTECTION, true)
            putExtra(TunnelVpnService.EXTRA_SPLIT_DOMAINS, splitDomains.toTypedArray())
            putExtra(TunnelVpnService.EXTRA_BYPASS_PACKAGES, bypassPackages.toTypedArray())
            putExtra(TunnelVpnService.EXTRA_BATTERY_SAVER, batterySaver)
            putExtra(TunnelVpnService.EXTRA_DOH, dnsOverHttps)
            putExtra(TunnelVpnService.EXTRA_AD_BLOCK, adBlock)
            putExtra(TunnelVpnService.EXTRA_TURBO_MODE, turboMode)
            putExtra(TunnelVpnService.EXTRA_BROWSER_ONLY, browserTrafficOnly)
            putExtra(TunnelVpnService.EXTRA_TRANSITION_REASON, transitionReason)
        }
    }

    private fun restartTunnelServiceForConfigChange() {
        if (TunnelVpnService.currentState != TunnelVpnService.STATE_CONNECTED) return
        saveConfig()
        val intent = buildTunnelIntent(TunnelVpnService.TRANSITION_REASON_CONFIG_REFRESH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun requestStop() {
        pendingStart = false
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_STOP
            putExtra(
                TunnelVpnService.EXTRA_TRANSITION_REASON,
                TunnelVpnService.TRANSITION_REASON_USER_STOP
            )
        }
        startService(intent)
    }

    private fun syncStateToWeb() {
        val snapshot = TunnelVpnService.currentStateSnapshot()
        syncStateToWeb(
            snapshot.state,
            snapshot.connectedSeconds,
            reason = snapshot.transitionReason,
            generation = snapshot.serviceGeneration
        )
    }

    private fun syncStateToWeb(
        state: String,
        seconds: Long,
        error: String = "",
        reason: String? = null,
        generation: Long? = null
    ) {
        val snapshot = if (reason == null || generation == null) {
            TunnelVpnService.currentStateSnapshot()
        } else {
            null
        }
        val resolvedReason = reason ?: snapshot?.transitionReason.orEmpty()
        val resolvedGeneration = generation ?: snapshot?.serviceGeneration ?: 0L
        webView.evaluateJavascript(
            "window.setVpnState && window.setVpnState(${JSONObject.quote(state)},$seconds,${JSONObject.quote(error)},${JSONObject.quote(resolvedReason)},$resolvedGeneration)",
            null
        )
    }

    private fun syncTurboToWeb(snapshot: TurboRuntimeSnapshot = TurboRuntimeController.snapshot()) {
        turboMode = snapshot.desired
        val payload = JSONObject().apply {
            put("desired", snapshot.desired)
            put("state", snapshot.state.name)
            put("generation", snapshot.generation)
            put("reason", snapshot.reasonCode)
            put("transitioning", snapshot.transitionInProgress)
        }
        webView.evaluateJavascript(
            "window.setTurboState && window.setTurboState(${JSONObject.quote(payload.toString())})",
            null
        )
    }

    private fun turboSnapshotFromIntent(intent: Intent?): TurboRuntimeSnapshot {
        val fallback = TurboRuntimeController.snapshot()
        val stateName = intent?.getStringExtra(TunnelVpnService.EXTRA_TURBO_STATE) ?: return fallback
        val state = runCatching { TurboRuntimeState.valueOf(stateName) }.getOrDefault(fallback.state)
        return TurboRuntimeSnapshot(
            intent.getBooleanExtra(TunnelVpnService.EXTRA_TURBO_DESIRED, fallback.desired),
            state,
            intent.getLongExtra(TunnelVpnService.EXTRA_TURBO_GENERATION, fallback.generation),
            intent.getStringExtra(TunnelVpnService.EXTRA_TURBO_REASON) ?: fallback.reasonCode
        )
    }

    private fun loadSavedConfig() {
        val prefs = getSharedPreferences(TunnelVpnService.PREFS_NAME, MODE_PRIVATE)
        dnsServer = sanitizeDnsServer(prefs.getString(TunnelVpnService.EXTRA_DNS_SERVER, dnsServer).orEmpty())
        protectionMode = prefs.getString(TunnelVpnService.EXTRA_PROTECTION_MODE, TunnelVpnService.MODE_HTTPS_LOCAL)
            ?.takeIf { it == TunnelVpnService.MODE_HTTPS_LOCAL || it == TunnelVpnService.MODE_ENHANCED_TUNNEL }
            ?: TunnelVpnService.MODE_HTTPS_LOCAL
        routeAllTraffic = prefs.getBoolean(TunnelVpnService.EXTRA_ROUTE_ALL, routeAllTraffic)
        splitDomains = sanitizeSplitDomains(prefs.getString(TunnelVpnService.EXTRA_SPLIT_DOMAINS, "").orEmpty().lines()).valid
        bypassPackages = sanitizePackageNames(prefs.getString(TunnelVpnService.EXTRA_BYPASS_PACKAGES, "").orEmpty().lines(), packageName)
        batterySaver = prefs.getBoolean(TunnelVpnService.EXTRA_BATTERY_SAVER, batterySaver)
        dnsOverHttps = prefs.getBoolean(TunnelVpnService.PREF_DOH_ENABLED, dnsOverHttps)
        adBlock = prefs.getBoolean(TunnelVpnService.PREF_ADBLOCK_ENABLED, adBlock)
        turboMode = prefs.getBoolean(TunnelVpnService.PREF_TURBO_MODE, turboMode)
        TurboRuntimeController.initializePreference(
            turboMode,
            TunnelVpnService.currentState == TunnelVpnService.STATE_CONNECTED
        )
        browserTrafficOnly = prefs.getBoolean(TunnelVpnService.PREF_BROWSER_ONLY, browserTrafficOnly)
        autoStart = prefs.getBoolean(TunnelVpnService.PREF_AUTO_START, autoStart)
    }

    private fun saveConfig() {
        getSharedPreferences(TunnelVpnService.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(TunnelVpnService.EXTRA_DNS_SERVER, dnsServer)
            .putString(TunnelVpnService.EXTRA_PROTECTION_MODE, protectionMode)
            .putBoolean(TunnelVpnService.EXTRA_ROUTE_ALL, routeAllTraffic)
            .putString(TunnelVpnService.EXTRA_SPLIT_DOMAINS, splitDomains.joinToString("\n"))
            .putString(TunnelVpnService.EXTRA_BYPASS_PACKAGES, bypassPackages.joinToString("\n"))
            .putBoolean(TunnelVpnService.EXTRA_BATTERY_SAVER, batterySaver)
            .putBoolean(TunnelVpnService.EXTRA_DOH, dnsOverHttps)
            .putBoolean(TunnelVpnService.PREF_DOH_ENABLED, dnsOverHttps)
            .putBoolean(TunnelVpnService.PREF_ADBLOCK_ENABLED, adBlock)
            .putBoolean(TunnelVpnService.PREF_BROWSER_ONLY, browserTrafficOnly)
            .putBoolean(TunnelVpnService.PREF_AUTO_START, autoStart)
            .apply()
    }

    inner class Bridge {
        @JavascriptInterface
        fun toggleVpn(shouldStart: Boolean) {
            runOnUiThread {
                if (shouldStart) requestStart() else requestStop()
            }
        }

        @JavascriptInterface
        fun configureSplit(domains: String, packages: String, routeAll: Boolean) {
            validateBridgeConfigText(domains, "split domains")
            validateBridgeConfigText(packages, "bypass packages")
            val domainValues = domains.lines()
            val packageValues = packages.lines()
            validateSplitDomainInput(domainValues)
            validateBypassPackageInput(packageValues)
            val validDomains = sanitizeSplitDomains(domainValues).valid
            val validPackages = sanitizePackageNames(packageValues, packageName)
            runOnUiThread {
                splitDomains = validDomains
                bypassPackages = validPackages
                routeAllTraffic = routeAll
                saveConfig()
            }
        }

        @JavascriptInterface
        fun configureDns(server: String) {
            validateBridgeConfigText(server, "DNS server")
            val validServer = sanitizeDnsServer(server)
            runOnUiThread {
                dnsServer = validServer
                saveConfig()
            }
        }

        @JavascriptInterface
        fun configureAdvanced(
            battery: Boolean,
            doh: Boolean,
            adBlockEnabled: Boolean,
            _turboModeEnabled: Boolean,
            browserOnly: Boolean
        ) {
            runOnUiThread {
                batterySaver = battery
                dnsOverHttps = doh
                adBlock = adBlockEnabled
                browserTrafficOnly = browserOnly
                saveConfig()
            }
        }

        @JavascriptInterface
        fun setTurboEnabled(enabled: Boolean, requestId: Long) {
            if (requestId < 0L) return
            runOnUiThread {
                val intent = Intent(this@MainActivity, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_SET_TURBO
                    putExtra(TunnelVpnService.EXTRA_TURBO_DESIRED, enabled)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    TunnelVpnService.currentState == TunnelVpnService.STATE_CONNECTED
                ) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }

        @JavascriptInterface
        fun getTurboAiStatus(): String {
            val status = TurboAiPreferences.status(
                this@MainActivity,
                TurboRuntimeController.snapshot().desired
            )
            return JSONObject().apply {
                put("available", status.available)
                put("enabled", status.enabled)
                put("samples", status.samples)
                put("confidence", status.confidence.name.lowercase())
                put("state", status.state)
                put("fallbackReason", status.fallbackReason)
                put("aegisMode", status.aegisMode)
                put("aegisDecisions", status.aegisDecisions)
                put("aegisObservations", status.aegisObservations)
                put("aegisInferenceNanos", status.aegisInferenceNanos)
                put("aegisInferenceP50Nanos", status.aegisInferenceP50Nanos)
                put("aegisInferenceP95Nanos", status.aegisInferenceP95Nanos)
                put("aegisModelBytes", status.aegisModelBytes)
                put("aegisRuntimeDecisionCount", status.aegisRuntimeDecisionCount)
                put("aegisRuntimeDecisionNanos", status.aegisRuntimeDecisionNanos)
                put("aegisRuntimeDecisionMaxNanos", status.aegisRuntimeDecisionMaxNanos)
            }.toString()
        }

        fun resetTurboAiModel() {
            TurboAiPreferences.resetModel(this@MainActivity)
            runOnUiThread {
                if (TunnelVpnService.currentState == TunnelVpnService.STATE_CONNECTED) {
                    restartTunnelServiceForConfigChange()
                }
            }
        }

        @JavascriptInterface
        fun configureAutoStart(enabled: Boolean) {
            runOnUiThread {
                autoStart = enabled
                saveConfig()
            }
        }

        @JavascriptInterface
        fun refreshProtection() {
            runOnUiThread { restartTunnelServiceForConfigChange() }
        }

        @JavascriptInterface
        fun getSavedConfig(): String {
            return JSONObject().apply {
                put("routeAll", routeAllTraffic)
                put("dnsProtection", true)
                put("dns_protection_enabled", true)
                put("protectionMode", protectionMode)
                put("protection_mode", protectionMode)
                put("battery", batterySaver)
                put("battery_saver", batterySaver)
                put("doh", dnsOverHttps)
                put("doh_enabled", dnsOverHttps)
                put("adBlock", adBlock)
                put("adblock_enabled", adBlock)
                put("turboMode", turboMode)
                put("turbo_mode_enabled", turboMode)
                put("browserOnly", browserTrafficOnly)
                put("browser_traffic_only", browserTrafficOnly)
                put("autoStart", autoStart)
                put("auto_start", autoStart)
                put("dnsServer", dnsServer)
                put("dns_server", dnsServer)
                put("splitDomains", JSONArray(splitDomains))
                put("split_domains", JSONArray(splitDomains))
                put("bypassPackages", JSONArray(bypassPackages))
                put("bypass_packages", JSONArray(bypassPackages))
            }.toString()
        }

        @JavascriptInterface
        fun getCurrentState(): String = TunnelVpnService.currentState

        @JavascriptInterface
        fun getCurrentStateSnapshot(): String {
            val snapshot = TunnelVpnService.currentStateSnapshot()
            return JSONObject().apply {
                put("state", snapshot.state)
                put("seconds", snapshot.connectedSeconds)
                put("generation", snapshot.serviceGeneration)
                put("reason", snapshot.transitionReason)
            }.toString()
        }

        @JavascriptInterface
        fun getTurboState(): String {
            val snapshot = TurboRuntimeController.snapshot()
            return JSONObject().apply {
                put("desired", snapshot.desired)
                put("state", snapshot.state.name)
                put("generation", snapshot.generation)
                put("reason", snapshot.reasonCode)
                put("transitioning", snapshot.transitionInProgress)
            }.toString()
        }

        @JavascriptInterface
        fun getConnectedSeconds(): Long = TunnelVpnService.connectedSeconds()

        @JavascriptInterface
        fun getServiceGeneration(): Long = TunnelVpnService.currentServiceGeneration()

        @JavascriptInterface
        fun getTransitionReason(): String = TunnelVpnService.currentTransitionReason()

        private suspend fun getNetworkSnapshot(request: DiagnosticsRequest): String? = coroutineScope {
            val before = TunnelVpnService.currentStateSnapshot()
            if (before.state != TunnelVpnService.STATE_CONNECTED ||
                before.serviceGeneration != request.serviceGeneration
            ) return@coroutineScope null
            val json = JSONObject()
            val cloudflareRequest = async {
                measureUrl("https://cloudflare-dns.com/dns-query?name=example.com&type=A", "application/dns-json")
            }
            val googleRequest = async {
                measureUrl("https://dns.google/resolve?name=example.com&type=A")
            }
            val httpsRequest = async {
                measureAnyUrl(
                    listOf(
                        "https://www.google.com/generate_204",
                        "https://www.gstatic.com/generate_204",
                        "https://example.com"
                    )
                )
            }
            val cloudflare = cloudflareRequest.await()
            val google = googleRequest.await()
            val https = httpsRequest.await()
            val traffic = JSONObject(TunnelVpnService.trafficStatsJson())
            val stateSnapshot = TunnelVpnService.currentStateSnapshot()
            if (stateSnapshot.state != TunnelVpnService.STATE_CONNECTED ||
                stateSnapshot.serviceGeneration != request.serviceGeneration
            ) return@coroutineScope null
            val tunnelBytes = traffic.optLong("uplinkTotal") + traffic.optLong("downlinkTotal")
            val tunnelTrafficOk = stateSnapshot.state == TunnelVpnService.STATE_CONNECTED &&
                traffic.optBoolean("available") &&
                tunnelBytes > 0L
            json.put("cloudflareDohOk", cloudflare.ok)
            json.put("cloudflareDohMs", cloudflare.elapsedMs)
            json.put("googleDohOk", google.ok)
            json.put("googleDohMs", google.elapsedMs)
            json.put("httpsOk", https.ok)
            json.put("httpsMs", https.elapsedMs)
            json.put("tunnelTrafficOk", tunnelTrafficOk)
            json.put("tunnelBytes", tunnelBytes)
            json.put("message", listOf(cloudflare, google, https).firstOrNull { it.ok }?.message ?: "network check failed")
            json.put("state", stateSnapshot.state)
            json.put("seconds", stateSnapshot.connectedSeconds)
            json.put("generation", stateSnapshot.serviceGeneration)
            json.toString()
        }

        @JavascriptInterface
        fun requestNetworkSnapshot(requestId: Long, epoch: Long, serviceGeneration: Long) {
            if (requestId < 0L || epoch < 0L || serviceGeneration < 0L) return
            requestNetworkSnapshot(DiagnosticsRequest(requestId, epoch, serviceGeneration))
        }

        private fun requestNetworkSnapshot(request: DiagnosticsRequest) {
            diagnosticsPendingRequest.set(request)
            drainDiagnosticsRequests()
        }

        private fun drainDiagnosticsRequests() {
            if (!diagnosticsRequestInFlight.compareAndSet(false, true)) return
            diagnosticsScope.launch {
                try {
                    while (!isFinishing && !isDestroyed) {
                        val request = diagnosticsPendingRequest.getAndSet(null) ?: break
                        val payload = getNetworkSnapshot(request) ?: continue
                        withContext(Dispatchers.Main.immediate) {
                            val current = TunnelVpnService.currentStateSnapshot()
                            if (!isFinishing && !isDestroyed && ::webView.isInitialized &&
                                current.state == TunnelVpnService.STATE_CONNECTED &&
                                current.serviceGeneration == request.serviceGeneration
                            ) {
                                webView.evaluateJavascript(
                                    "window.setNetworkSnapshot && window.setNetworkSnapshot(${request.requestId},${JSONObject.quote(payload)},${request.serviceGeneration},${request.epoch})",
                                    null
                                )
                            }
                        }
                    }
                } finally {
                    diagnosticsRequestInFlight.set(false)
                    if (diagnosticsPendingRequest.get() != null && !isFinishing && !isDestroyed) {
                        drainDiagnosticsRequests()
                    }
                }
            }
        }

        @JavascriptInterface
        fun getTrafficStats(): String = TunnelVpnService.trafficStatsJson()

        @JavascriptInterface
        fun vibrate() {
            runOnUiThread {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(VIBRATOR_SERVICE) as? Vibrator
                } ?: return@runOnUiThread
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(18)
                }
            }
        }

        @JavascriptInterface
        fun getInstalledApps(): String {
            return runCatching {
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val installed = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                val array = JSONArray()
                installed
                    .mapNotNull { resolveInfo ->
                        val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                        if (appInfo.packageName == packageName) return@mapNotNull null
                        val label = resolveInfo.loadLabel(packageManager)?.toString()
                            ?: packageManager.getApplicationLabel(appInfo).toString()
                        val system = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        Triple(label, appInfo.packageName, system)
                    }
                    .distinctBy { it.second }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
                    .forEach { (label, packageName, system) ->
                        array.put(JSONObject().apply {
                            put("label", label)
                            put("packageName", packageName)
                            put("system", system)
                        })
                    }
                array.toString()
            }.getOrDefault("[]")
        }

        @JavascriptInterface
        fun notifyTurboEnabling() {
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.turbo_enabling_notice),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    }

    private data class MeasuredResponse(val ok: Boolean, val elapsedMs: Long, val message: String)

    private fun blockedWebResource(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun measureUrl(url: String, accept: String? = null): MeasuredResponse {
        val started = System.nanoTime()
        return runCatching {
            val builder = Request.Builder().url(url).header("Cache-Control", "no-cache")
            if (accept != null) builder.header("Accept", accept)
            val request = builder.build()
            diagnosticsClient.newCall(request).execute().use { response ->
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                MeasuredResponse(response.isSuccessful || response.code in 300..399, elapsed, "HTTP ${response.code}")
            }
        }.getOrElse { error ->
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            MeasuredResponse(false, elapsed, error.message ?: "network failed")
        }
    }

    private fun measureAnyUrl(urls: List<String>): MeasuredResponse {
        var last = MeasuredResponse(false, 0L, "network check failed")
        urls.forEach { url ->
            val measured = measureUrl(url)
            if (measured.ok) return measured
            last = measured
        }
        return last
    }

    private fun sanitizeDnsServer(server: String): String {
        val fallback = "1.1.1.1"
        val value = server.trim().ifBlank { fallback }
        val validIpv4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
        return if (validIpv4.matches(value) && runCatching { InetAddress.getByName(value) }.isSuccess) {
            value
        } else {
            fallback
        }
    }

    companion object {
        private const val REQ_VPN = 41
        private const val REQ_NOTIFICATION = 42
    }
}
