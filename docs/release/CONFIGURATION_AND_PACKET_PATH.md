# Configuration and packet-path contracts

## Configuration-to-runtime

| Setting | Runtime consumer/effect | Status/test |
|---|---|---|
| DNS server | stored/passed but resolver endpoints are fixed | NO-OP; compatibility key only |
| DNS protection | always-on local DNS route; old extra retained but not consumed | PARTIAL/compatibility |
| DoH | chooses DoH versus system resolver | ACTIVE; resolver tests |
| Ad blocking | `DomainBlocker` in DNS engine | ACTIVE; blocker/DNS tests |
| Route all | stored/passed; actual routes derive from protection mode | NO-OP; unsupported setting |
| Protection mode | FAST, BALANCED, and STRONG currently share full dual-stack capture and eligible TLS fragmentation; names remain compatibility presets | ACTIVE; policy tests |
| MTU | sanitized 1280–32768 and passed unchanged to the builder and relays; establish fallbacks remain 9000 then 1500 | ACTIVE; sanitizer/wiring tests |
| Battery saver | selects the existing 4096 profile rather than the existing 32768 performance profile | ACTIVE through MTU; contract tests |
| Split domains | DNS virtual mapping then local TCP domain forwarder | ACTIVE for A/TCP path; mapping tests; not general bypass |
| Bypass packages | builder disallowed applications | ACTIVE; device application of package rules pending |
| Browser-only | builder allowed applications; zero applied packages fail | ACTIVE; policy tests/device pending |
| Auto-start | boot receiver rebuilds validated config and starts service after permission | ACTIVE; static contract/device pending |
| Turbo desired | preference and mode restart | ACTIVE; runtime tests |
| Turbo runtime | enabling/active/degraded derived from generation and protected-socket reachability | ACTIVE health state; not a route proof |

## Mode-to-packet-path

| Mode | IPv4/IPv6 route | TCP | UDP | DNS/fallback | Failure policy |
|---|---|---|---|---|---|
| FAST | IPv4/IPv6 defaults | local dual-stack relay with eligible TLS ClientHello fragmentation and local TCP DNS | local dual-stack relay; adaptive QUIC fallback | local dual-stack DNS; DoH chain or validated system DNS with UDP-to-TCP fallback | bounded TC response and local TCP retry; safe IPv6 normalization/reassembly; AH/ESP/unsafe/non-echo ICMPv6 fail closed; local echo/PTB and NAT64/DNS64 |
| BALANCED | same as FAST | same | same | same | same |
| STRONG | same as FAST | same | same | same | same |
| Turbo disabled | selected base mode | base mode | base mode | regular DoH chain | base policy |
| Turbo enabled | forces STRONG | STRONG + configured-domain mapping | STRONG | hedged DoH providers | probe failure degrades state only |
| Browser-only | mode routes only for applied allowed apps | selected mode | selected mode | selected mode | zero browser packages fails start |
| Split configured | selected full-forward mode | configured A records map to virtual TCP target | unchanged | selected domains mapped; AAAA/HTTPS handled by policy | bounded mappings |

Unsupported user-visible settings after refactoring: custom DNS server and route-all toggle. The DNS protection toggle is locked/always on rather than a runtime off switch. Existing visible wording was not changed.
