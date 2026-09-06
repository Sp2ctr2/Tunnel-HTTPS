# Google Play VpnService declaration draft

## Proposed answers

- VPN core functionality: `Yes — local device network utility`, subject to owner/reviewer confirmation. It is not a conventional remote VPN and must not be represented as one.
- Remote device-level tunnel: `No`. No remote full-traffic gateway exists.
- Processing: local TUN DNS/TCP/UDP processing; original traffic is relayed directly through protected sockets.
- Traffic modification: DNS blocking/response synthesis, configured-domain virtual mapping, TLS ClientHello fragmentation on eligible flows, and UDP/443 blocking policy.
- Monetization traffic manipulation: `No` based on repository evidence; owner must confirm business behavior.
- Personal/sensitive access: DNS/domain and network metadata are accessed; DoH queries leave the device. Prominent disclosure is required.
- Collection/sharing: no first-party analytics/ads/telemetry found; provider processing needs Data Safety/legal review.

Reviewer explanation: “Tunnel HTTPS creates a local Android VpnService interface. It does not send all traffic to our server and does not decrypt HTTPS. The interface enables on-device DNS filtering and direct TCP/UDP relay behavior. DNS over HTTPS may send DNS queries to the resolver selected by the application.”

Video: opening, full disclosure, reject/reopen/accept, Android VPN permission, connection indicator, DNS protection demonstration, split/bypass demonstration, Turbo state, foreground notification, stop. Keep under the current Play limit shown in Console.

Official basis: [Understanding Google Play's VpnService policy](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en-GB).
