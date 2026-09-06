# Korea network-resilience review

Reviewed: 2026-09-03

## Verified policy context

- Korea has used HTTPS SNI-based blocking since 2019. The Korea Communications Commission described the mechanism and its intended scope in its official briefing.
- On 2026-08-20 the government announced plans to review and develop blocking technology responsive to QUIC and ECH, encrypted-traffic analysis that does not decrypt content, and faster handling of domain-shifting or similar sites. The announcement is a research and policy plan; it is not evidence that one uniform nationwide QUIC/ECH system is already deployed.
- Copyright emergency blocking was first used on 2026-05-11 for 34 sites under the revised framework.
- CDN- and platform-side restrictions can occur independently of the local access network. A local, serverless VPN cannot change the subscriber's source country, restore provider-deleted content, or replace a remote egress service.

Primary references:

- [Korea Communications Commission, HTTPS blocking briefing](https://www.kmcc.go.kr/user.do?boardId=1113&boardSeq=46820&cp=1&ctx=ALL&dc=K05030000&mode=view&page=A05030000&searchKey=ALL&searchVal=https)
- [Government policy briefing, 2026-08-20](https://www.korea.kr/briefing/policyBriefingView.do?newsId=156775030)
- [Ministry of Culture, Sports and Tourism, emergency blocking implementation](https://www.mcst.go.kr/site/s_policy/govPolicy/performView.jsp?pSeq=1119)
- [RFC 9505, QUIC-aware manageability](https://www.rfc-editor.org/rfc/rfc9505.html)
- [RFC 7050, NAT64 prefix discovery](https://www.rfc-editor.org/rfc/rfc7050.html)
- [RFC 9849, TLS Encrypted Client Hello](https://www.rfc-editor.org/rfc/rfc9849.html)

## Implemented local defenses

- SNI-aware TLS ClientHello fragmentation remains available with bounded adaptive ordering and a plain compatibility fallback. TLS 1.3 HelloRetryRequest keeps the chosen fragmentation mode for the second ClientHello, rejects malformed server responses, and does not replay rejected 0-RTT data.
- Encrypted DNS uses independent threat-filtering providers and a diversity tie-breaker. A single block answer cannot veto two independent positive answers; two policy blocks can form consensus. DNSSEC-bogus, forged, and rebinding answers fail closed, including the tie-break path.
- Ordinary HTTPS/SVCB and AAAA answers are preserved so capable clients can negotiate modern encrypted protocols. Synthetic IPv4 split-domain mappings suppress incompatible HTTPS/SVCB data only for that mapped path.
- QUIC v1/v2 Initial packets are structurally classified. Unknown paths remain allowed; two explicit failed probes trigger a temporary per-address TCP fallback, successful responses restore QUIC, and network changes clear learned state. Arbitrary non-QUIC UDP/443 cannot poison this policy.
- Full-forward modes relay IPv4 and IPv6 TCP, UDP, and DNS through protected sockets. Safe Hop-by-Hop/Destination options and `segments-left=0` Routing headers are bounded-normalized, fragments use bounded reassembly, and AH/ESP or unsafe paths fail closed; local ICMPv6 echo/PTB, LinkProperties PREF64 plus RFC 7050 NAT64 discovery, generation-safe DNS64, local TCP DNS, and adaptive UDP/443 TCP fallback remain available.

## Claims that must not be made

- This application is not a remote VPN, anonymity network, or source-country changer.
- It cannot guarantee access against every network policy, provider-side geoblock, account restriction, content deletion, active TLS endpoint attack, or future protocol change.
- ECH records are preserved when supplied through DNS, but the application does not implement a browser TLS stack or force client ECH support.
- Device/OEM, IPv6-only/NAT64, captive-portal, lossy-network, and Korean carrier behavior still require physical-device testing.
