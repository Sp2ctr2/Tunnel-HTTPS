# In-app VpnService disclosure

Status: `IMPLEMENTED; NON-DEBUGGABLE RELEASE DIALOG/DECLINE/ACCEPT/TUN FLOW VERIFIED ON API 36 EMULATOR`.

## Implemented behavior

- `MainActivity.requestStart()` checks versioned local consent before setting a start pending, requesting notification permission, or calling `VpnService.prepare()`.
- When consent is absent in a non-debuggable build, a non-cancelable disclosure dialog displays `vpn_disclosure_message`. It provides explicit “동의하지 않음” and “동의하고 계속” actions.
- Declining clears the pending start and leaves the UI disconnected. Accepting stores consent version `1` in the app's `tunnel_vpn` shared preferences and resumes the normal start flow.
- `BootReceiver` refuses auto-start without accepted consent, clears the stored active flag, and does not launch the service.
- `TunnelVpnService` independently rejects every non-stop command without accepted consent, clears the active flag, publishes a disclosure-required error when it owns the service state, and stops that service start.
- Debuggable builds intentionally bypass the consent check in `VpnDisclosureConsent.isAccepted()`. The real disclosure path must therefore be verified with a non-debuggable release build.

The implemented Korean disclosure text is maintained in `app/src/main/res/values/strings.xml`. It describes local packet and metadata processing, optional external DoH providers, locally stored Turbo identifiers and performance data, and protection limitations. No separate settings action for reopening the disclosure was found in the current source.

## Verification status

Source contract tests cover ordering before VPN permission, affirmative/decline behavior, versioned consent, disclosure content, and boot/service enforcement. On 2026-09-05, Luna verified the actual dialog in a minified non-debuggable, test-key-signed release APK: decline left the UI disconnected without starting the VPN; accept proceeded through permission approval and established the release package's TUN. A separate-UID helper then completed HTTP and a 60,000-byte UDP echo through that TUN. Screenshots, UI XML, APK hash and observations are in [the release UI evidence](../../tools/verification-results/2026-09-05-release-ui/release-ui-validation-2026-09-05.json).

Physical-device accessibility, consent persistence across all restart/update cases, and OEM boot/background-start behavior remain separate acceptance checks. The emulator's `userdebug` WebView behavior is documented in [the current implementation report](IMPLEMENTATION_VERIFICATION_2026-09-05.md); it is not a reason to bypass the disclosure in release builds.

This document records implementation state only. It does not assert Google Play policy compliance or replace owner/legal review. Policy reference: [Understanding Google Play's VpnService policy](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en-GB).
