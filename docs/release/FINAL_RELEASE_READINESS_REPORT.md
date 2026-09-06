> **Historical and superseded:** This 2026-09-04 snapshot does not describe the current implementation or current verification state. Its test counts, device/emulator status, blocker assessment, and artifact conclusions must not be used as present release evidence. See the forthcoming [Implementation Verification — 2026-09-05](IMPLEMENTATION_VERIFICATION_2026-09-05.md) for the current record.

# Final release readiness report

Date: 2026-09-04

Decision: **CODE-COMPLETE FOR PHYSICAL-DEVICE VALIDATION; NOT A SIGNED PRODUCTION ARTIFACT**

## Completed engineering work

- Preserved the existing SNI-aware TLS ClientHello fragmentation and its bounded fallback order.
- Added stateful IPv6 TCP, UDP, and DNS forwarding with protected sockets, IPv6 pseudo-header checksums, dual-stack secure-resolver bootstrap addresses, network-handover resets, bounded normalization of safe Hop-by-Hop/Destination and `segments-left=0` Routing headers, bounded fragment reassembly, and fail-closed AH/ESP/unsafe paths; local ICMPv6 echo/PTB, NAT64/DNS64, local TCP DNS, and adaptive UDP/443 TCP fallback are covered.
- Replaced blanket UDP/443 behavior with structural QUIC v1/v2 Initial classification and a bounded per-address policy: unknown paths remain open, two explicit failed probes cause temporary TCP fallback, successful response delivery restores QUIC, and arbitrary UDP/443 cannot alter state.
- Hardened secure-DNS consensus against a single-provider false block, mismatched positive answer sets, NODATA answer stripping, DNSSEC-bogus, forged, rebinding, stale-network callbacks, and cache poisoning. Three divergent positive CDN answers use the designated diversity provider as an explicit availability tie-break.
- Hardened TLS retry commitment so truncated records, HTTP block pages, alerts, malformed ServerHello messages, and incoherent lengths do not suppress fallback.
- Preserved fragmentation across a TLS 1.3 HelloRetryRequest. The second ClientHello uses the selected mode; bounded queued 0-RTT and compatibility CCS records are discarded; waiting is bounded to ten seconds.
- Corrected TCP handover publication, FIN/final-ACK handling, half-close draining, and TIME_WAIT scheduling so a valid one-sided shutdown is not cut off by the short TIME_WAIT timer.
- Added bounded SYN/data/FIN retransmission, cumulative ACK and sequence-wrap release, exponential RTO, zero-window persist, FIN reserve, and complete reliability-state cleanup on reset or handover.
- Added LinkProperties PREF64 plus RFC 7050 `ipv4only.arpa` discovery across all RFC 6052 prefix lengths, multiple-prefix publication, dual-well-known-address ambiguity rejection, TTL/negative-cache refresh, CNAME-aware DNS64 synthesis, and generation-safe NAT64/DNS64 publication.
- Hardened IPv6 fragment handling for RFC 8200/RFC 7112 first-fragment completeness, RFC 5722 overlap rejection, atomic-fragment isolation, fixed first-arrival expiry, exact-duplicate idempotence, extension-header ordering, and the 65,535-byte IPv6 payload limit.
- Enforced IPv6 TCP, UDP, and ICMPv6 pseudo-header checksums and transport validity before every forwarding consumer; corrected ICMPv6 error suppression and Packet Too Big quoting; and kept link-local/multicast traffic outside the TUN with API-compatible full-forward route complements.
- Bound asynchronous UDP errors to immutable outbound tickets and socket epochs. Ambiguous delayed ICMP errors can no longer replay a newer payload or quote the wrong IPv6 invoking packet; QUIC retry timers, responses, and flow closure are linearized under the same flow state.
- Bound Turbo virtual DNS mappings to the network generation, quarantined retired virtual addresses for their TTL, rejected stale unmapped virtual addresses instead of direct-forwarding them, and delayed mapping publication until the DNS generation checks pass.
- Serialized every TUN output write with shutdown, suppressed post-reset TCP ACK/data output, retained all published A-record candidates for ordinary TCP failover, and isolated UDP candidate/socket construction failures to their individual datagrams.
- Enforced network-generation validation for non-IN DNS classes instead of allowing their final response check to return early.
- Prevented dual-stack networks from launching RFC 7050 NAT64 discovery, eliminating DNS-generation churn that could discard valid concurrent A/AAAA answers, while retaining discovery on IPv6-only networks without a published PREF64.
- Kept protected DNS and forwarding sockets outside the VPN instead of rebinding them to the current default `Network`, which could otherwise route resolver traffic back into the tunnel.
- Moved serialized TUN output to a bounded dedicated writer so DNS, TCP, UDP, and ICMPv6 producers retain packet ordering without blocking one another on the device write; expanded bounded per-flow TCP relay and retransmission windows for high-bandwidth-delay paths.
- Stopped metadata-only capability updates from flushing secure-DNS connections or live relay flows, and accepted CID-bound QUIC Initial, Retry, Version Negotiation, and future-version responses as immediate path-reachability evidence.
- Prevented DNS64 synthesis from erasing or bypassing BLOCKED, CENSORED, FILTERED, FORGED, and DNSSEC-bogus EDE meanings, including combined IPv4-mapped AAAA and follow-up A responses.
- Removed non-license implementation comments from application source, ProGuard configuration, and Gradle wrapper scripts. Required Apache/SPDX license headers and the shell shebang remain.
- Updated packet-path, audit, Korean network-resilience, manual-test, and artifact documents. The visible UI and configured MTU values were not changed.

## Verification

The clean command `gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease` passed. The JVM suite contains 555 passing tests across 44 suites with no failures, errors, or skips. Lint reports zero errors and six dependency/platform version notices. R8 release APK and AAB generation passed. Exact artifacts and hashes are recorded in `24_ARTIFACT_VERIFICATION.md`; the complete IPv6 protocol matrix is recorded in `IPV6_FINAL_VERIFICATION_2026-09-03.md`.

No ADB, emulator, or device scan was used. Loopback socket integration tests cover TLS fallback, QUIC UDP callbacks, IPv6 relay, and HelloRetryRequest behavior, but they do not substitute for a physical carrier/OEM matrix.

## Remaining external gates

| Gate | Status | Required owner action |
|---|---|---|
| Release signing | BLOCKED EXTERNALLY | supply the four existing `TUNNELHTTPS_*` signing environment values from a secure keystore; rebuild and verify |
| Physical devices and Korean carriers | UNVERIFIED | run the manual matrix without ADB if that remains the owner constraint |
| IPv6-only/NAT64 and unusual IPv6 headers | LOCAL GATE PASSED; PHYSICAL MATRIX UNVERIFIED | validate carrier behavior; the complete locally executable protocol, fuzz, route-space, loopback, lifecycle, and build gates passed without ADB |
| Play policy/Console declarations | OWNER/LEGAL | verify disclosure, data-safety, foreground-service and VpnService declarations |
| Remote-egress claims | NOT APPLICABLE | this is a local serverless VPN and cannot change country/IP or reverse provider-side blocks/deletions |

## Release conclusion

No known blocker remains in the locally testable JVM/network core after the final adversarial review. Production release is still conditional on signing and physical-device/carrier verification. The generated unsigned release files are build evidence, not distributable deliverables.
