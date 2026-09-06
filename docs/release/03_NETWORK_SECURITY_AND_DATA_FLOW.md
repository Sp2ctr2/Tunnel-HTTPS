# Network security and data flow

## Data visible and processed

The app receives raw IP packets through the local TUN in captured modes. It parses IP version, addresses, ports, TCP flags/sequence/window, UDP metadata, DNS names/types/answers, and TLS ClientHello metadata including SNI when available. It does not install a user CA, decrypt HTTPS payloads, parse cookies, or inspect application authentication data.

Runtime diagnostics keep aggregate counters and sanitized reason codes. Source search found no Android logging calls or packet/domain payload logging. Diagnostics intentionally do not expose raw last-domain values.

## External endpoints

| Endpoint family | Purpose | Data sent | Security/failure |
|---|---|---|---|
| Cloudflare, Google, Quad9 DoH endpoints | DNS resolution and Turbo hedging | DNS wire query, source IP/TLS metadata visible to provider | HTTPS platform trust; strict DNS response validation; all-provider failure becomes SERVFAIL |
| `google.com` / `gstatic.com` `generate_204` | UI reachability measurement | ordinary HTTPS request metadata | external WebView requests restricted to exact host/path |
| IANA probe and fixed public IPs | underlying-network Turbo reachability | TCP/TLS connection metadata | sockets are protected; result is not TUN-path proof |
| `example.com` | diagnostics/browser discovery constant | only diagnostics request when invoked or intent matching | HTTPS; not a VPN endpoint |

There is no remote full-traffic tunnel endpoint, account backend, analytics SDK, ads SDK, telemetry SDK, certificate interception, or embedded credential in repository evidence.

## Trust boundaries

- WebView bridge: privileged local network control. CSP denies frames/objects/base/form actions; external subresources are blocked except exact reachability URLs. Oversized bridge inputs are rejected.
- Android VPN permission: OS-controlled authorization boundary.
- TUN/relay: untrusted packet input; lengths, DNS pointers, transactions, and special ranges are validated.
- Resolver providers: external processors of DNS queries. Provider retention and jurisdiction are `REQUIRES OWNER INPUT` and must not be invented.
- PackageManager: package names and labels are processed locally for bypass selection; not logged or transmitted by first-party code.

## TLS

No permissive trust manager, hostname verifier bypass, `onReceivedSslError` bypass, or cleartext network policy was found. Network security config trusts system roots only and denies cleartext. No pinning is used; platform CA trust and provider hostname validation apply.
