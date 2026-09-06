# Tunnel HTTPS

[![Android CI](https://github.com/Sp2ctr2/Tunnel-HTTPS/actions/workflows/ci.yml/badge.svg)](https://github.com/Sp2ctr2/Tunnel-HTTPS/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/platform-Android-3ddc84.svg)](https://developer.android.com/)

## On-device Android networking

Tunnel HTTPS is a local Android networking engine built around \`VpnService\` and TUN. It keeps DNS policy, packet validation, selected transport handling, and connection strategy decisions on the device. It does not run a developer-operated remote VPN gateway.

The repository contains the networking code, the tests, the Android app, and the evidence used to describe it. That makes it possible to inspect the path instead of taking a feature list on trust.

[Explore the architecture](docs/release/01_ARCHITECTURE_AND_VPN_FLOW.md) | [Build from source](#build-from-source) | [View releases](https://github.com/Sp2ctr2/Tunnel-HTTPS/releases)

> Current status: public beta preparation. The source is Apache-2.0 licensed. A signed production APK has not been published yet.

## What is in the box

- A local \`VpnService\` and TUN packet loop
- IPv4 and IPv6 normalization, selected fragment handling, checksums, and ICMPv6 policies
- DNS parsing, response validation, caching, encrypted DNS over HTTPS, DNS64, and NAT64 support
- Bounded local TCP and UDP relay paths
- TLS ClientHello metadata inspection and adaptive fragmentation strategies
- Structural QUIC v1/v2 classification with fail-closed handling for unsupported input
- An on-device contextual Turbo policy and an experimental Aegis neural policy that remains shadow-only

The app can block ad and tracker domains at the DNS layer, bypass selected applications, and show local diagnostics. It is not an anonymity service, an IP or country switcher, or a tool for decrypting HTTPS payloads.

## The path through the app

\`\`\`mermaid
flowchart LR
    A[Android applications] --> B[VpnService and TUN]
    B --> C[Packet normalization]
    C --> D{Protocol dispatch}
    D --> E[DNS validation and cache]
    D --> F[TCP and UDP relay]
    D --> G[TLS and QUIC inspection]
    E --> H[DoH providers or system DNS]
    F --> I[Protected upstream sockets]
    G --> F
    I --> J[Destination network]
    K[Turbo policy] -. local decision .-> D
    L[Aegis shadow policy] -. observation only .-> D
\`\`\`

The Android VPN interface and local policy stay on the device. Upstream sockets use the platform network stack after the repository's packet and policy layers make their decisions.

## What Tunnel HTTPS implements itself

| Area | In this repository | Platform or library |
| --- | --- | --- |
| TUN | VPN configuration, packet loop, dispatch, lifecycle handling | Android \`VpnService\` and the OS TUN interface |
| IPv4 and IPv6 | Normalization, checksums, selected fragment handling, ICMPv6 policies | The underlying Android/Linux network stack |
| DNS | Parsing, validation, cache policy, resolver racing, DNS64, NAT64 | HTTPS transport and resolver infrastructure |
| TCP and UDP | Bounded local relay state, forwarding decisions, cleanup, and resource limits | Upstream Android/Linux sockets |
| TLS | ClientHello metadata inspection and fragmentation strategy | TLS cryptography and the remote endpoint |
| QUIC | Structural classification and selected response validation | A complete QUIC implementation is not claimed |
| Learning | Contextual Turbo decisions and Aegis shadow inference | Android runtime and local storage |

This distinction matters. The project does not reimplement the entire Internet stack, and it is not just a screen around a third-party VPN library.

## Support matrix

| Area | Status | Notes |
| --- | --- | --- |
| IPv4 packet path | Supported | Validation and normalization are bounded |
| IPv6 packet path | Supported, partial | Extension and fragment behavior is deliberately limited |
| TCP relay | Partial | This is a bounded userspace relay, not a replacement for the kernel TCP stack |
| UDP relay | Supported, bounded | Resource limits and cleanup are part of the path |
| DNS parsing and validation | Supported | Malformed and unsafe responses fail closed |
| DNS over HTTPS | Supported | Provider and network behavior still affect availability |
| DNS64 and NAT64 | Supported where discoverable | IPv6-only behavior depends on network conditions |
| TLS ClientHello | Partial | Metadata and strategy handling, not HTTPS decryption |
| QUIC | Experimental | Structural classification, not a complete QUIC implementation |
| Turbo | Experimental | On-device contextual policy with bounded fallback |
| Aegis | Shadow | Neural policy observes; it does not control routing |

The detailed limits and evidence are documented in [the release notes](docs/release/RELEASE_CHECKLIST.md), [the network security review](docs/release/03_NETWORK_SECURITY_AND_DATA_FLOW.md), and [the feature claims audit](docs/release/14_FEATURE_CLAIMS_AUDIT.md).

## Privacy and data flow

Network metadata is processed locally to make routing and filtering decisions. Depending on the selected features, that can include DNS names, destination addresses and ports, and TLS ClientHello metadata such as SNI. DNS over HTTPS can send queries to an external provider, which may see the device IP address and request metadata.

The app does not claim absolute privacy, anonymity, universal compatibility, or guaranteed connectivity. Read the [in-app disclosure record](docs/release/07_IN_APP_DISCLOSURE.md) and [privacy policy draft](docs/release/06_PRIVACY_POLICY_DRAFT.md) before treating the app as ready for public distribution.

## Download and install

The [Releases page](https://github.com/Sp2ctr2/Tunnel-HTTPS/releases) is the planned distribution point for signed APKs. No signed production APK is published yet. Do not present a debug or test-signed build as a release.

When installing a locally built APK:

\`\`\`sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
\`\`\`

Android may require approval for installations from outside the Play Store. The app requests VPN consent when it needs it. Do not disable broader device security controls.

## Build from source

Requirements:

- JDK 17
- Android SDK with API 36
- An Android device or emulator for device checks

\`\`\`sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
\`\`\`

The debug APK is written to \`app/build/outputs/apk/debug/app-debug.apk\`. Local caches, generated reports, signing keys, and machine-specific settings do not belong in a public commit.

## Evidence

The repository keeps dated records rather than turning one test run into a blanket promise:

- [Architecture and VPN flow](docs/release/01_ARCHITECTURE_AND_VPN_FLOW.md)
- [Network security and data flow](docs/release/03_NETWORK_SECURITY_AND_DATA_FLOW.md)
- [IPv6 verification](docs/release/IPV6_FINAL_VERIFICATION_2026-09-03.md)
- [Latest engine results](docs/release/NEXT_ENGINE_RESULTS_2026-09-05.md)
- [Manual test matrix](docs/release/16_MANUAL_TEST_MATRIX.md)
- [Release checklist](docs/release/RELEASE_CHECKLIST.md)
- [Turbo architecture](docs/TURBO_AI_ARCHITECTURE.md)

Each record is tied to a source snapshot, environment, device, or artifact. Read the scope and date before reusing a result.

## Localization

Korean remains the default application language. English resources and WebView localization are included for international users. User-visible strings should be added to the appropriate Android resource or locale map, not scattered through networking code.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a change. Network behavior changes need focused tests or a reproducible explanation. UI changes should cover long English labels, narrow screens, accessibility labels, and touch targets.

## Security

Use the process in [SECURITY.md](SECURITY.md) for vulnerability reports. Do not publish real browsing history, private domains, packet captures, credentials, or signing material in an issue or pull request.

## License

Tunnel HTTPS is released under the [Apache License 2.0](LICENSE). Third-party notices and data provenance are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
