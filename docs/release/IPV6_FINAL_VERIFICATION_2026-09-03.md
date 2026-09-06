# IPv6 final verification

Verified: 2026-09-04 (Asia/Seoul)

## Result

All IPv6 behavior that can be exercised in this Linux working copy without ADB or a physical Android device passed. The final clean build completed 555 JVM tests across 44 suites with zero failures, errors, or skips; Android lint reported zero errors; debug APK, R8 release APK, and release AAB generation completed.

This is a code and local-execution result, not a claim that every carrier, OEM kernel, captive portal, or hostile middlebox has been physically observed. No ADB, emulator, device enumeration, or device scan was used.

## Protocol coverage

| Area | Implemented and verified behavior |
|---|---|
| IPv6 parsing | Exact payload-length validation, bounded extension traversal, Hop-by-Hop placement, safe options, `segments-left=0` Routing acceptance, unsafe/AH/ESP rejection, and malformed upper-layer fail-closed behavior |
| Fragmentation | RFC 8200/RFC 7112 complete first fragment, correct placement of Hop-by-Hop and Routing per-fragment headers, final-destination options in the fragmentable part, RFC 5722 whole-datagram overlap rejection with tombstone, RFC 3168 CE preservation and invalid ECN-mixture rejection, exact-duplicate idempotence, atomic-fragment isolation, fixed first-arrival timeout, bounded assemblies/bytes/fragments, and maximum IPv6 payload enforcement |
| Transport | TCP/UDP/ICMPv6 pseudo-header checksum validation, mandatory nonzero IPv6 UDP checksum, TCP flags/reserved bits/options/ports validation, and checksum generation for locally produced packets |
| ICMPv6 and PMTU | Echo request/reply validation, RFC error-on-error suppression through extension headers, multicast-source restrictions, correct Parameter Problem/Time Exceeded pointers and original-packet quoting after atomic-fragment normalization, node-wide bounded error rate limiting shared by all generation paths, Packet Too Big only for an actually oversized invoking packet, MTU floor 1280, and a total response bounded to 1280 bytes |
| Full forwarding | Protected IPv6 TCP/UDP sockets, DNS UDP and TCP, legal source-fragmented UDP relay, MTU-bounded IPv6 response fragmentation, socket-epoch and immutable-ticket UDP retry, ambiguity-safe delayed ICMP handling, network-generation resets, handover-safe publication, scoped DNS addresses, and deterministic fail-closed handling of unscoped link-local traffic |
| Local traffic | `fe80::/10` link-local and `ff00::/8` multicast remain outside full-forward TUN routes; API 33+ exclusions and the older-API exact complement are equivalent across all 65,536 IPv6 leading words |
| NAT64 | LinkProperties PREF64 plus IPv6-only RFC 7050 discovery, every RFC 6052 length (`/32`, `/40`, `/48`, `/56`, `/64`, `/96`), split u-octet handling, all six permitted-position duplicate checks, multiple prefixes in stable order, both well-known IPv4 answers, ambiguity rejection, TTL expiry, and early refresh; dual-stack networks do not churn DNS generations with unnecessary discovery |
| DNS64 | A-to-AAAA synthesis across every active prefix, CNAME/DNAME chain following and preservation, IPv4-mapped AAAA exclusion for mapped-only and mixed responses, EDE security-policy preservation across exclusion and follow-up A resolution, minimum TTL propagation, RFC 2308 `min(SOA TTL, SOA.MINIMUM)` negative caching, DNSSEC/DO no-unsigned-synthesis policy, fresh query-derived OPT reconstruction on every rewrite, extended-RCODE preservation, EDNS/TC handling, and pointer-safe reserialization |
| Network lifecycle | One atomic generation-tagged publication of capabilities, underlying network, interface index, scoped DNS servers, and NAT64 prefixes; DNS64 validates the full network epoch across AAAA/A queries; IPv6 DNS endpoint identity includes scope ID; stale DNS sockets, discovery, and DoH work are closed, cancelled, or discarded on handover |
| NAT64 connection use | Virtual-IPv4 TCP connections try every valid published NAT64 destination candidate in deterministic order; the well-known prefix never translates non-global IPv4 space, network-specific prefixes remain eligible, and actual connection outcomes are used rather than probing the RFC 7050 discovery addresses |

## Adversarial verification

- Seeded fragment reordering/fuzz and overlap campaigns complete without accepting malformed datagrams or leaking rejected state.
- Fragment identities use the RFC 8200 source, destination, and identification tuple; identical IDs on different address pairs remain isolated.
- Corrupt IPv6 TCP and UDP checksums are rejected at all forwarding consumers, including DNS and security inspection paths.
- Delayed UDP ICMP errors after a newer same-flow datagram are deterministically prevented from retrying or quoting that newer payload; exact single-packet errors retain byte-for-byte invoking-packet attribution.
- Malformed, overlapping, truncated, oversized, misordered, and reserved-bit IPv6 inputs fail closed.
- Route-policy equivalence is exhaustive over the IPv6 high 16-bit space, not a sample.
- Local IPv6 loopback exercises TCP relay, UDP callbacks, DNS paths, fallback, shutdown, and network-reset behavior.
- Concurrent TUN writes use one bounded dedicated writer so packet boundaries remain serialized without making DNS, TCP, UDP, and ICMPv6 producer threads contend on the device write.
- A clean full build exposed a real concurrent-map snapshot race in TCP connection shutdown. TCP and UDP lifecycle snapshots now use stable copied collections; the exact failing test and the complete suite pass after the correction.

## Preserved constraints

- The SNI-aware TLS ClientHello fragmentation implementation and adaptive strategy remain enabled and tested.
- Visible UI resources, assets, and `MainActivity` were not changed during the IPv6 correction pass.
- Existing MTU choices remain unchanged: performance 32768, battery saver 4096, compatibility fallback 9000, and final fallback 1500. IPv6 PMTU handling independently enforces the protocol floor of 1280.
- Application Kotlin, Java, and XML source contains no standalone implementation comments after the requested comment-removal pass.

## External release gates

- Physical Korean-carrier/OEM validation remains external because device access and ADB were explicitly excluded.
- The release APK and AAB are unsigned because the existing release keystore credentials were not present. They must not be distributed as production artifacts until signed and reverified.
