# VpnService audit

## Confirmed controls

- `VpnService.prepare()` precedes startup from the activity and is checked again in the service.
- Service is non-exported and protected by `android.permission.BIND_VPN_SERVICE`.
- Foreground startup, immutable stop `PendingIntent`, notification channel, and API 34 special-use type are present.
- `establish()` null/failure is not converted to success.
- Every located upstream TCP/UDP/DoH/probe socket is protected before connection.
- Stop is idempotent. TUN resources are detached and closed before executor/forwarder teardown; new forwarder submissions are rejected once closing starts.
- State is broadcast after engine teardown on normal stop.

## Routing truth table

| Mode | Routes | DNS | TCP | UDP | IPv6 |
|---|---|---|---|---|---|
| FAST | `0.0.0.0/0`, `::/0` | local dual-stack parser/resolver | local IPv4/IPv6 TCP relay plus eligible TLS ClientHello fragmentation | local IPv4/IPv6 UDP relay; adaptive QUIC fallback | safe extensions normalized, bounded fragments reassembled; AH/ESP/unsafe paths fail closed; local echo/PTB and NAT64/DNS64 |
| BALANCED | same as FAST | same | same | same | same |
| STRONG | same as FAST | same | same | same | same |
| Turbo | forces STRONG, enables hedged providers and configured-domain virtual mapping | hedged DoH | same STRONG relay | same | same |

## Open release risks

| Priority | Finding | Evidence/impact | Required action |
|---|---|---|---|
| P0 | No repository evidence of a separate prominent VpnService disclosure and affirmative consent | Permission dialog alone does not describe DNS/domain processing | Owner-approved disclosure flow and device test before production; UI was not changed by explicit constraint |
| P0 | Release AAB is unsigned | release signing environment was absent | Configure upload key securely; rebuild and verify certificate |
| P1 | Full-forward IPv6 uses bounded safe-extension normalization and fragment reassembly; AH/ESP, unsafe paths, and non-echo ICMPv6 fail closed | TCP/UDP and DNS are implemented, including local TCP DNS, NAT64/DNS64, local echo/PTB, and adaptive UDP/443 TCP fallback | IPv6-only/NAT64 and packet-shaping device tests before production |
| P1 | QUIC fallback still needs real-network calibration | parser recognizes v1/v2 Initial and requires two explicit failed probes; shared-address behavior is time-bounded | packet-capture regression on representative Korean networks |
| P2 | TCP reliability remains intentionally bounded | SYN/data/FIN retransmission, cumulative ACK release, wrap handling, exponential RTO, and zero-window persist are implemented; SACK and RTT-derived RTO are not | physical packet-loss/reordering calibration before claiming carrier-grade equivalence |
| P1 | Boot legality and OEM behavior unverified | exported protected-broadcast receiver starts FGS | device matrix and Play declaration review |

## Resource ceilings

The relays use bounded executors and flow limits; tests cover 48+ TCP and 64+ UDP pressure. Exact native thread stack memory and OEM FD ceilings are environment dependent and remain device measurements. No unbounded DNS task admission remains: at most 32 resolver jobs are admitted.
