# Tunnel HTTPS

[![Android CI](https://github.com/Sp2ctr2/Tunnel-HTTPS/actions/workflows/ci.yml/badge.svg)](https://github.com/Sp2ctr2/Tunnel-HTTPS/actions/workflows/ci.yml)

Tunnel HTTPS is a local Android VpnService and TUN utility for DNS protection, DNS-based ad and tracker blocking, selectable application bypass, and local IPv4/IPv6 connection handling.

It is not a conventional remote VPN. It does not change your public IP or apparent country, does not provide anonymity, does not decrypt HTTPS content, and does not install a user CA. Depending on the selected mode, it can inspect packet metadata locally, including addresses, ports, DNS names, and TLS ClientHello metadata such as SNI.

## Project status

This repository is a release-facing engineering copy, not a signed production release. The code and local verification records are useful for review, but physical-device/carrier validation, final signing, policy declarations, and a public privacy-policy contact remain owner gates.

## What it does

- Creates a local Android VPN interface; protected sockets stay outside the tunnel.
- Supports DNS-over-HTTPS resolution through supported external providers, including Cloudflare, Google, and Quad9.
- Applies DNS-domain blocking and lets users bypass selected apps or browser traffic.
- Handles bounded local TCP/UDP forwarding and IPv4/IPv6 paths, with fail-closed validation for unsupported or unsafe packet forms.
- Provides local diagnostics, foreground-service status, and an optional Turbo mode that keeps keyed identifiers and performance signals on the device.

## What it does not promise

- No remote full-traffic gateway, country/IP switching, or provider-side block circumvention.
- No HTTPS payload decryption, cookie inspection, application authentication inspection, or user certificate installation.
- No guarantee of support across every OEM, carrier, IPv6-only/NAT64 network, MTU, UDP/443 path, or battery policy.
- No absolute security, privacy, availability, speed, or leak-free guarantee.

DNS queries may be sent over HTTPS to a third-party resolver. Those providers can receive the device public IP and request metadata. Read the network and data-flow review at docs/release/03_NETWORK_SECURITY_AND_DATA_FLOW.md, the in-app disclosure record at docs/release/07_IN_APP_DISCLOSURE.md, and the privacy-policy draft at docs/release/06_PRIVACY_POLICY_DRAFT.md before treating the app as ready for public distribution.

## Install an APK

If you have an APK built from this source, install it directly with Android Debug Bridge:

~~~
adb install -r /path/to/TunnelHTTPS.apk
~~~

-r requests an in-place update and normally preserves app data. Android will reject the update when the existing installation was signed with a different key, such as a production build versus a debug or test-signed build. Do not uninstall a previous installation unless you accept losing its local settings and consent state.

The existing APKs and benchmark APKs in the work copy are dated build evidence, not a signed release of this localized pass. Verify the artifact's provenance and SHA-256 before installing; do not present a debug or test-signed APK as a production update.

## Build locally

Requirements: JDK 17, Android SDK with API 36, and a connected test device or emulator only when you choose to run device checks.

~~~
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
~~~

The debug APK is written to app/build/outputs/apk/debug/app-debug.apk. Build outputs and local caches are intentionally not source evidence. Do not commit signing keys, local.properties, emulator dumps, crash logs, or generated build/ directories.

## Evidence and limitations

The repository keeps dated records instead of turning one run into a product guarantee:

- Latest engine and measurement record: docs/release/NEXT_ENGINE_RESULTS_2026-09-05.md
- Final release-device verification summary: tools/verification-results/2026-09-05-final-release-9a36090f/summary.json
- Release UI validation record: tools/verification-results/2026-09-05-release-ui/release-ui-validation-2026-09-05.json
- Network security and data-flow review: docs/release/03_NETWORK_SECURITY_AND_DATA_FLOW.md
- Release checklist and outstanding gates: docs/release/RELEASE_CHECKLIST.md

These records are tied to specific source, device, environment, and artifact snapshots. They do not establish behavior on every physical device or authorize publication. The dated report files also contain historical sections; read their dates and scope before reusing a result.

## Contributing

See CONTRIBUTING.md. Changes to packet handling, VPN lifecycle, DNS/TLS validation, or the WebView bridge should include focused tests and must not be described as network improvements without reproducible evidence.

## Security

See SECURITY.md. Do not put private vulnerability details, packet captures, keys, or personal network metadata in a public issue.

## License and third-party notices

This work copy does not yet contain a project license grant. The repository owner must choose and add LICENSE before publishing it as an open-source project or accepting reusable contributions. Until then, source redistribution rights are not granted by this README. Dependency notices are in THIRD_PARTY_NOTICES.md.
