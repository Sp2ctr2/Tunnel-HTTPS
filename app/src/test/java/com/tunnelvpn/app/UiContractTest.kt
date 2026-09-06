package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiContractTest {
    private val html by lazy { File("src/main/assets/index.html").readText() }
    private val script by lazy {
        Regex("""<script>([\s\S]*?)</script>""").find(html)?.groupValues?.get(1)
            ?: error("inline script missing")
    }
    private val reducedMotionCss by lazy {
        html.substringAfterLast("@media(prefers-reduced-motion:reduce)").substringBefore("</style>")
    }
    private val reducedTransparencyCss by lazy {
        html.substringAfter("@media(prefers-reduced-transparency:reduce)").substringBefore('\n')
    }
    private val mainActivity by lazy { File("src/main/java/com/tunnelvpn/app/MainActivity.kt").readText() }
    private val vpnService by lazy { File("src/main/java/com/tunnelvpn/app/TunnelVpnService.kt").readText() }
    private val theme by lazy { File("src/main/res/values/themes.xml").readText() }
    private val darkModeTheme by lazy { File("src/main/res/values-v29/themes.xml").readText() }
    private val splashTheme by lazy { File("src/main/res/values-v31/themes.xml").readText() }

    @Test
    fun turboThemeRemainsActiveForTheDisconnectAnimation() {
        assertTrue(html.contains("activePhase=phase===\"connected\"||phase===\"disconnecting\""))
    }

    private fun composite(foreground: Double, alpha: Double, background: Double): Double =
        foreground * alpha + background * (1.0 - alpha)

    private fun linear(value: Double): Double {
        val channel = value / 255.0
        return if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
    }

    private fun luminance(red: Double, green: Double, blue: Double): Double =
        0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)

    @Test
    fun existingTurboControlIsUniqueAndBackendBound() {
        assertEquals(1, Regex("""id="turboToggle"""").findAll(html).count())
        assertTrue(html.contains("TunnelAndroid.setTurboEnabled"))
        assertTrue(html.contains("TunnelAndroid.getTurboState"))
        assertTrue(html.contains("window.setTurboState"))
        assertFalse(html.contains("""s.id==="turboToggle"){s.classList.toggle"""))
    }

    @Test
    fun splitBypassAndAppBypassButtonsShareTheSameStyle() {
        assertTrue(html.contains("""<button type="submit" class="bypass-action">추가</button>"""))
        assertTrue(html.contains("""<button class="bypass-action" data-app="""))
        assertTrue(html.contains(".bypass-action{border-radius:7px;padding:4px 8px"))
        assertFalse(html.contains("settings-add"))
    }

    @Test
    fun protectionBarsMoveOnlyWithAnActiveConnection() {
        assertTrue(html.contains(".phone.on .signal-wrap .bar{animation:barPulse"))
        assertTrue(html.contains(".signal-wrap .bar{width:3px;transform-origin:50% 100%;border-radius:2px;animation:none"))
        assertTrue(html.contains(".signal-wrap .bar:nth-child(1){height:7px}"))
        assertTrue(html.contains(".signal-wrap .bar:nth-child(2){height:11px}"))
        assertTrue(html.contains(".signal-wrap .bar:nth-child(3){height:15px}"))
        assertTrue(html.contains(".grid{animation:gridScroll var(--grid-speed) linear infinite!important"))
        assertTrue(html.contains("--grid-speed:24s"))
        assertFalse(html.contains(".phone.on .signal-wrap .bar{animation:none!important"))
        assertTrue(reducedMotionCss.contains(".grid"))
        assertTrue(reducedMotionCss.contains(".phone.on .signal-wrap .bar"))
        assertTrue(reducedMotionCss.contains("animation:none!important"))
        assertTrue(html.contains("""aria-label="보호 연결 대기""""))
    }

    @Test
    fun disconnectedOrDegradedTurboPreferenceNeverDisplaysAsActive() {
        assertTrue(script.contains("""operational=enabled&&phase==="connected"&&!turboState.transitioning&&turboState.state==="ACTIVE"""))
        assertFalse(script.contains("shownActive"))
        assertTrue(script.contains("""pill.textContent=operational?"ACTIVE":enabled?"READY":"OFF"""))
        assertFalse(script.contains("""turboState.reason==="vpn-disconnected"""))
        assertTrue(script.contains("""const settingsRefresh=configRefresh!==null&&configRefresh.kind==="settings"""))
        assertTrue(script.contains("""turboState.desired&&turboState.state==="ACTIVE"""))
        assertTrue(script.contains("""(!turboState.transitioning||settingsRefresh)"""))
    }

    @Test
    fun diagnosticsAndTabsDoNotBlockNavigation() {
        assertTrue(script.contains("TunnelAndroid.requestNetworkSnapshot(requestId,requestEpoch,requestGeneration)"))
        assertTrue(mainActivity.contains("fun requestNetworkSnapshot(requestId: Long, epoch: Long, serviceGeneration: Long)"))
        assertTrue(mainActivity.contains("window.setNetworkSnapshot && window.setNetworkSnapshot(\${request.requestId},\${JSONObject.quote(payload)},\${request.serviceGeneration},\${request.epoch})"))
        assertTrue(mainActivity.contains("fun getServiceGeneration(): Long"))
        assertTrue(mainActivity.contains("fun getTransitionReason(): String"))
        assertTrue(mainActivity.contains("fun getCurrentStateSnapshot(): String"))
        assertTrue(script.contains("JSON.parse(TunnelAndroid.getCurrentStateSnapshot())"))
        assertTrue(script.contains("window.setVpnState(snapshot.state,snapshot.seconds,\"\",snapshot.reason,snapshot.generation)"))
        assertFalse(script.contains("window.setVpnState(TunnelAndroid.getCurrentState(),TunnelAndroid.getConnectedSeconds(),\"\",reason,generation)"))
        assertFalse(html.contains("TunnelAndroid.getNetworkSnapshot()"))
        assertFalse(html.contains("pc.classList.add(\"tab-soft-swap\")"))
        assertFalse(html.contains("behavior:\"smooth\""))
    }

    @Test
    fun latencyDiagnosticsResolveImmediatelyAndRefreshWhileInsightsStayVisible() {
        val latencyContract = Regex(
            """const latencyMs=value=>typeof value==="number"&&Number\.isFinite\(value\)&&value>=1&&value<=maxLatencyMs\?Math\.round\(value\):null"""
        )
        assertEquals(1, latencyContract.findAll(script).count())
        assertTrue(script.contains("""const flowing=s.tunnelTrafficOk===true,httpsOk=s.httpsOk===true,cloudflareOk=s.cloudflareDohOk===true,googleOk=s.googleDohOk===true"""))
        assertFalse(script.contains("""flowing?"정상"""))
        assertTrue(script.contains("""function resetStatsUi(){resetTrafficUi();resetDiagnosticsUi()}"""))
        assertTrue(script.contains("""if(!s.available){if(activeTab==="insights")resetTrafficUi();return}"""))
        assertFalse(script.contains("""if(!s.available){if(activeTab==="insights")resetStatsUi();return}"""))
        assertTrue(script.contains("""window.setNetworkSnapshot=(requestId,payload,generation=null,epoch=null)=>"""))
        assertTrue(script.contains("requestEpoch!==diagEpoch"))
        assertTrue(script.contains("requestGeneration!==nativeGeneration"))
        assertTrue(script.contains("responseGeneration!==nativeGeneration"))
        assertTrue(script.contains("responseEpoch===null||responseEpoch!==requestEpoch"))
        assertTrue(script.contains("requestGeneration===null"))
        assertTrue(script.contains("responseGeneration===null"))
        assertFalse(script.contains("generation===null?snapshot.generation:generation"))
        assertTrue(script.contains("diagRequestGeneration===null?normalizeGeneration(TunnelAndroid.getServiceGeneration()):diagRequestGeneration"))
        assertTrue(script.contains("diagRequestId+=1;logStatus(\"diagnostics\",\"보호 상태 재측정 대기\")"))
        assertTrue(script.contains("""return activeTab==="insights"?(eco?16000:8000):(eco?60000:30000)"""))
        assertTrue(script.contains("""scheduleDiag(activeTab==="insights")"""))
        assertFalse(script.contains("""if(activeTab!=="insights"||diagBusy)return"""))
    }

    @Test
    fun webViewNetworkSurfaceAndDiagnosticCopyMatchTheNativePath() {
        assertTrue(html.contains("connect-src 'none'"))
        assertFalse(html.contains("connect-src https://"))
        assertTrue(html.contains("브라우저 트래픽만 로컬 보호 경로로 처리합니다."))
        assertTrue(html.contains("DNS 요청을 HTTPS(DoH)로 암호화합니다."))
        assertTrue(html.contains("<div class=\"mini-label\">외부 HTTPS 도달성</div>"))
        assertTrue(html.contains("<div class=\"mini-label\">외부 DoH 도달성</div>"))
        assertFalse(html.contains("<div class=\"mini-label\">HTTPS 접속</div>"))
        assertFalse(html.contains("<div class=\"mini-label\">DoH 보호</div>"))
    }

    @Test
    fun liveSettingReconfigurationPreservesConnectedVisualState() {
        assertTrue(script.contains("configRefreshSequence=0,configRefresh=null"))
        assertTrue(script.contains("""configRefreshReasons=new Set(["config-refresh","settings-refresh","turbo-refresh"])"""))
        assertTrue(script.contains("""if(!configRefreshReasons.has(normalized))return"none"""))
        assertTrue(script.contains("""function beginConfigRefresh(kind){if(phase!=="connected"||configRefresh!==null)return null"""))
        assertTrue(script.contains("""baseGeneration:nativeGeneration,generation:null,sawConnecting:false"""))
        assertTrue(script.contains("""const refreshStage=configRefreshStage(s,reason,callbackGeneration,refreshToken)"""))
        assertTrue(script.contains("""if(refreshStage==="connecting"){setOn(true);syncTurboTheme();return}"""))
        assertTrue(script.contains("""if(refreshStage==="pending"){setOn(true);syncTurboTheme();return}"""))
        assertTrue(script.contains("""return configRefresh.sawConnecting||newerGeneration?"connected":"pending"""))
        assertTrue(script.contains("""window.setVpnState=(s,seconds=0,error="",reason="",generation=null,refreshToken=null)=>"""))
        assertTrue(script.contains("beginConfigRefresh(\"turbo\")"))
        assertTrue(script.contains("beginConfigRefresh(\"settings\")"))
        assertTrue(script.contains("haptic();clearConfigRefresh();if(!configureNative())return;advanceTransition()"))
        assertFalse(script.contains("visualRefreshUntil"))
        assertFalse(script.contains("retainConnectedVisuals"))
    }

    @Test
    fun repeatedSettingRefreshesAreSerializedAndLatestWins() {
        assertTrue(script.contains("settingsRefreshQueued=false,pendingTurboCommand=null"))
        assertTrue(script.contains("""function dispatchSettingsRefresh(){if(phase!=="connected")return;if(configRefresh!==null){settingsRefreshQueued=true;return}"""))
        assertTrue(script.contains("""function completeConfigRefresh(){configRefresh=null;return drainRefreshQueue()}"""))
        assertTrue(script.contains("""if(settingsRefreshQueued){settingsRefreshQueued=false;dispatchSettingsRefresh();return configRefresh!==null}"""))
        assertTrue(script.contains("""if(pendingTurboCommand!==null){const command=pendingTurboCommand;pendingTurboCommand=null;dispatchTurboCommand(command);return configRefresh!==null}"""))
        assertTrue(script.contains("""if(phase==="connected"&&configRefresh!==null){pendingTurboCommand=command;return}"""))
        assertTrue(script.contains("""if(turboState.transitioning||pendingTurboCommand!==null)return"""))
        assertEquals(1, Regex("""TunnelAndroid\.refreshProtection\(\)""").findAll(script).count())
        val switchBinding = script.lineSequence().first { it.contains("document.querySelectorAll(\".switch\")") }
        assertTrue(switchBinding.contains("dispatchSettingsRefresh()"))
        assertFalse(switchBinding.contains("TunnelAndroid.refreshProtection()"))
    }

    @Test
    fun splitAndAppChangesRefreshConnectedProtection() {
        assertTrue(script.contains("""function configureAndRefresh(){if(!configureNative())return false;if(phase==="connected")dispatchSettingsRefresh();return true}"""))
        assertTrue(script.contains("renderSplit();configureAndRefresh()"))
        assertTrue(script.contains("renderSelected();renderApps(\$(\"appSearch\").value);configureAndRefresh()"))
        assertTrue(mainActivity.contains("fun refreshProtection()"))
        assertTrue(mainActivity.contains("runOnUiThread { restartTunnelServiceForConfigChange() }"))
    }

    @Test
    fun delayedTransitionsAreGenerationOwnedAndTerminallyCancelled() {
        assertEquals(1, Regex("""transitionHandles=\{settle:null,complete:null,command:null\}""").findAll(script).count())
        assertTrue(script.contains("""if(generation!==transitionGeneration||phase!==expectedPhase||(expectedNativeState!==null&&nativeState!==expectedNativeState))return"""))
        assertTrue(script.contains("""if(s==="connecting"&&callbackGeneration!==null&&callbackGeneration===terminalNativeGeneration&&terminalNativeState!=="")return"""))
        assertTrue(script.contains("""if(callbackGeneration!==null&&(s==="connected"||s==="disconnected"||s==="error")){terminalNativeGeneration=callbackGeneration;terminalNativeState=s}"""))
        assertTrue(Regex("""function start\(seconds=0\)\{\s*advanceTransition\(\);clearConfigRefresh\(\);phase="connected"""").containsMatchIn(script))
        assertTrue(Regex("""function stop\(\)\{\s*advanceTransition\(\);clearConfigRefresh\(\);phase="idle"""").containsMatchIn(script))
        assertEquals(1, Regex("""scheduleTransition\("complete"""").findAll(script).count())
        assertEquals(2, Regex("""scheduleTransition\("settle"""").findAll(script).count())
        assertEquals(2, Regex("""scheduleTransition\("command"""").findAll(script).count())
        assertFalse(Regex("""setTimeout\(\(\)=>\s*(start|stop)\(""").containsMatchIn(script))
        assertFalse(script.contains("setTimeout(()=>phone.classList.remove(\"complete\")"))
    }

    @Test
    fun turboGridAndBottomGlassKeepTheirScopedVisualContracts() {
        val normalSeconds = Regex("""\.phone\{--grid-speed:([0-9.]+)s;""")
            .find(html)?.groupValues?.get(1)?.toDouble() ?: error("normal grid speed missing")
        val turboSeconds = Regex("""\.phone\.turbo\{--grid-speed:([0-9.]+)s;""")
            .find(html)?.groupValues?.get(1)?.toDouble() ?: error("turbo grid speed missing")
        assertEquals(1.3, normalSeconds / turboSeconds, 0.000001)
        assertTrue(html.contains("""background:rgba(8,14,12,.17)"""))
        assertTrue(html.contains("""backdrop-filter:blur(32px) saturate(1.9)"""))
        assertTrue(html.contains("""-webkit-backdrop-filter:blur(32px) saturate(1.9)"""))
        assertTrue(html.contains("""@supports not ((backdrop-filter:blur(1px)) or (-webkit-backdrop-filter:blur(1px)))"""))
        assertTrue(html.contains("""@media(prefers-reduced-transparency:reduce)"""))
        assertTrue(reducedTransparencyCss.contains("background:#0a0e0d"))
        assertTrue(reducedTransparencyCss.contains("background:#041811"))
        assertTrue(reducedTransparencyCss.contains("background:#120520"))
        assertTrue(reducedTransparencyCss.contains("background:#030a16"))
        assertTrue(reducedTransparencyCss.contains("backdrop-filter:none"))
        assertFalse(reducedTransparencyCss.contains("rgba("))
        assertEquals(1, Regex("""@media\(prefers-reduced-motion:reduce\)""").findAll(html).count())
        assertTrue(html.lastIndexOf("@media(prefers-reduced-motion:reduce)") > html.lastIndexOf(".phone.on .signal-wrap .bar{animation:barPulse"))
        assertTrue(reducedMotionCss.contains(".grid"))
        assertTrue(reducedMotionCss.contains("transition:none!important"))
    }

    @Test
    fun bottomNavigationIsKeyboardAccessibleAndMeetsBaseContrast() {
        assertEquals(1, Regex("""<nav class="bottom" role="tablist" aria-label="[^"]+">""").findAll(html).count())
        assertEquals(3, Regex("""<button type="button" id="nav-(secure|insights|settings)" class="nav-item(?: active)?" role="tab" aria-selected="(true|false)" aria-controls="(secure|insights|settings)" tabindex="(0|-1)" data-tab="(secure|insights|settings)">""").findAll(html).count())
        assertEquals(3, Regex("""role="tabpanel" aria-labelledby="nav-(secure|insights|settings)" aria-hidden="(true|false)" tabindex="0"""").findAll(html).count())
        listOf("ArrowRight", "ArrowDown", "ArrowLeft", "ArrowUp", "Home", "End").forEach {
            assertTrue(script.contains("event.key===\"$it\""))
        }
        assertTrue(script.contains("tab.setAttribute(\"aria-selected\",String(selected))"))
        assertTrue(script.contains("tab.setAttribute(\"aria-hidden\",String(!selected))"))
        val navAlpha = Regex("""\.nav-item\{[^}]*color:rgba\(255,255,255,([0-9.]+)\)""")
            .find(html)?.groupValues?.get(1)?.toDouble() ?: error("navigation alpha missing")
        val inactiveOpacity = Regex("""\.nav-item:not\(\.active\)\{opacity:([0-9.]+)}""")
            .find(html)?.groupValues?.get(1)?.toDouble() ?: error("navigation opacity missing")
        val panelRed = composite(8.0, 0.17, 12.0)
        val panelGreen = composite(14.0, 0.17, 8.0)
        val panelBlue = composite(12.0, 0.17, 0.0)
        val effectiveAlpha = navAlpha * inactiveOpacity
        val textRed = composite(255.0, effectiveAlpha, panelRed)
        val textGreen = composite(255.0, effectiveAlpha, panelGreen)
        val textBlue = composite(255.0, effectiveAlpha, panelBlue)
        val contrast = (luminance(textRed, textGreen, textBlue) + 0.05) /
            (luminance(panelRed, panelGreen, panelBlue) + 0.05)
        assertTrue("inactive navigation contrast was $contrast", contrast >= 4.5)
    }

    @Test
    fun timerAndTrafficScaleToLargeValues() {
        assertTrue(html.contains("node.style.fontSize="))
        assertTrue(html.contains("1099511627776"))
        assertTrue(html.contains("1073741824"))
        assertTrue(html.contains("""unit:"GB""""))
        assertTrue(html.contains("""id="totalUnit""""))
    }

    @Test
    fun trafficRateUsesCurrentValidatedNetworkStateWithoutStaleHold() {
        assertTrue(html.contains("networkValidated=s.networkValidated!==false"))
        assertFalse(html.contains("recentBps"))
        assertFalse(html.contains("recentBpsAt<4200"))
    }

    @Test
    fun trafficGraphRebuildsItsCoordinateSpaceAfterRotation() {
        assertTrue(html.contains("function renderTrafficGraph()"))
        assertTrue(html.contains("getBoundingClientRect()"))
        assertTrue(html.contains("graph.setAttribute(\"viewBox\""))
        assertTrue(html.contains("addEventListener(\"resize\",scheduleGraphResize"))
        assertTrue(html.contains("addEventListener(\"orientationchange\",scheduleGraphResize"))
    }

    @Test
    fun startupKeepsTheExistingTransparentWebViewOverTheBlackWindowTheme() {
        assertTrue(theme.contains("""android:windowBackground">#000000"""))
        assertTrue(darkModeTheme.contains("""android:forceDarkAllowed">false"""))
        assertTrue(splashTheme.contains("""android:windowSplashScreenBackground">#000000"""))
        assertTrue(mainActivity.contains("setBackgroundColor(android.graphics.Color.TRANSPARENT)"))
    }

    @Test
    fun webContentsDebuggingIsExplicitlyDisabledForNonDebuggableBuilds() {
        val debuggingCall = Regex(
            """WebView\.setWebContentsDebuggingEnabled\(\s*\(applicationInfo\.flags and android\.content\.pm\.ApplicationInfo\.FLAG_DEBUGGABLE\) != 0\s*\)"""
        )
        assertEquals(1, debuggingCall.findAll(mainActivity).count())
        assertTrue(
            mainActivity.indexOf("WebView.setWebContentsDebuggingEnabled") <
                mainActivity.indexOf("webView = WebView(this)")
        )
    }

    @Test
    fun onlyTurboEnableUsesTheUserToastBridge() {
        assertEquals(1, Regex("""notifyTurboEnabling\(\)""").findAll(html).count())
        assertFalse(html.contains("TunnelAndroid.toast"))
        assertFalse(mainActivity.contains("fun toast("))
        assertTrue(mainActivity.contains("fun notifyTurboEnabling()"))
    }

    @Test
    fun foregroundNotificationRemainsVisibleAndSilentWhileTheVpnRuns() {
        assertTrue(vpnService.contains("""CHANNEL_ID = "tunnel_https_quiet_v2""""))
        assertTrue(vpnService.contains(".setOnlyAlertOnce(true)"))
        assertTrue(vpnService.contains(".setOngoing(true)"))
        assertFalse(vpnService.contains("FOREGROUND_SERVICE_DEFERRED"))
        assertTrue(vpnService.contains(".setSound(null)"))
        assertTrue(vpnService.contains(".setVibrate(null)"))
        assertTrue(vpnService.contains(".setDefaults(0)"))
        assertTrue(vpnService.contains("setSound(null, null)"))
        assertTrue(vpnService.contains("enableVibration(false)"))
        assertTrue(vpnService.contains(".setLargeIcon("))
    }
}
