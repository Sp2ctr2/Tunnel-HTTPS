package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnDisclosureContractTest {
    private val activity = File("src/main/java/com/tunnelvpn/app/MainActivity.kt").readText()
    private val consent = File("src/main/java/com/tunnelvpn/app/VpnDisclosureConsent.kt").readText()
    private val bootReceiver = File("src/main/java/com/tunnelvpn/app/BootReceiver.kt").readText()
    private val service = File("src/main/java/com/tunnelvpn/app/TunnelVpnService.kt").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()

    @Test
    fun disclosurePrecedesVpnPermissionAndRequiresAffirmativeConsent() {
        val start = activity.substringAfter("private fun requestStart()")
            .substringBefore("private fun requestVpnPermission()")
        assertTrue(start.indexOf("VpnDisclosureConsent.isAccepted(this)") < start.indexOf("pendingStart = true"))
        assertTrue(start.contains(".setCancelable(false)"))
        assertTrue(start.contains(".setNegativeButton(R.string.vpn_disclosure_decline)"))
        assertTrue(start.contains(".setPositiveButton(R.string.vpn_disclosure_accept)"))
        assertTrue(start.contains("VpnDisclosureConsent.accept(this)"))
        assertTrue(consent.contains("getInt(PREFERENCE_KEY, 0) == CURRENT_VERSION"))
        assertTrue(consent.contains("ApplicationInfo.FLAG_DEBUGGABLE"))
    }

    @Test
    fun decliningDisclosureLeavesTheUiDisconnectedAndNoStartPending() {
        val start = activity.substringAfter("private fun requestStart()")
            .substringBefore("private fun requestVpnPermission()")
        val decline = start.substringAfter(".setNegativeButton(R.string.vpn_disclosure_decline)")
            .substringBefore(".setPositiveButton(R.string.vpn_disclosure_accept)")
        assertTrue(decline.contains("pendingStart = false"))
        assertTrue(decline.contains("syncStateToWeb(TunnelVpnService.STATE_DISCONNECTED, 0L)"))
    }

    @Test
    fun autoStartAndServiceRestoreCannotBypassDisclosure() {
        assertTrue(bootReceiver.contains("if (!VpnDisclosureConsent.isAccepted(context))"))
        assertTrue(service.contains("intent?.action != ACTION_STOP && !VpnDisclosureConsent.isAccepted(this)"))
        assertTrue(service.contains("TRANSITION_REASON_DISCLOSURE_REQUIRED"))
        assertTrue(service.contains("prefs().edit().putBoolean(PREF_ACTIVE, false)"))
    }

    @Test
    fun disclosureStatesLocalProcessingDataUseAndProtectionLimits() {
        assertTrue(strings.contains("Android VpnService"))
        assertTrue(strings.contains("기기 안에서 처리"))
        assertTrue(strings.contains("DNS 질의"))
        assertTrue(strings.contains("TLS 호스트 이름"))
        assertTrue(strings.contains("Cloudflare, Google 또는 Quad9"))
        assertTrue(strings.contains("IP 주소와 요청 메타데이터를 처리할 수 있습니다"))
        assertTrue(strings.contains("목적지 원문 대신 키 기반 HMAC 식별자"))
        assertTrue(strings.contains("성능 지표를 기기에만 저장"))
        assertTrue(strings.contains("DNS 암호화를 끄면 기기 또는 현재 네트워크의 DNS 확인자"))
        assertTrue(strings.contains("당사 서버로 전송하거나 광고 목적으로 판매하지 않습니다"))
        assertTrue(strings.contains("원격 VPN 또는 IP 익명화 서비스가 아니며"))
        assertTrue(strings.contains("비TLS 트래픽 자체를 암호화하지 않습니다"))
        assertTrue(strings.contains("동의하고 계속"))
    }
}
