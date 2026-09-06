# Privacy Policy — draft

Effective date: `[VERIFY BEFORE PUBLICATION]`

Developer: `[VERIFY BEFORE PUBLICATION: legal entity]`  
Privacy contact: `[VERIFY BEFORE PUBLICATION: email and postal contact if required]`

Tunnel HTTPS is a local Android network utility that uses Android `VpnService` to create a local TUN interface. It does not connect all device traffic to a developer-operated remote VPN gateway. Depending on the selected mode, the app can process packet addresses and ports, DNS queries and domain names, and TLS ClientHello metadata such as SNI. It does not decrypt HTTPS content or install a user certificate.

The app stores configuration such as mode, DNS/DoH choices, split domains, selected bypass package names, browser-only choice, Turbo choice, and auto-start locally in Android SharedPreferences. Android backup and device transfer are disabled by repository configuration.

DNS queries can be transmitted over HTTPS to Cloudflare, Google, or Quad9 resolver endpoints. Reachability checks can contact Google/gstatic, IANA, or fixed public endpoints. These providers can receive network metadata such as the device’s public IP and request timing. `[VERIFY BEFORE PUBLICATION: provider roles, terms, retention, deletion, and jurisdictions]`.

First-party source contains no advertising, analytics, remote telemetry, account system, or crash-reporting SDK. Aggregate diagnostics are displayed locally and raw packet payloads/domains are not logged by first-party code. `[VERIFY BEFORE PUBLICATION: confirm release infrastructure outside this repository]`.

Security measures include TLS for DoH, platform certificate and hostname validation, protected upstream sockets, cleartext denial, bounded input/flow state, and local-only diagnostics. No software can promise absolute security or availability.

User choices include disconnecting, disabling DoH/ad blocking/Turbo, configuring bypass rules, and clearing app data or uninstalling. Provider-held data cannot be deleted by this app; `[VERIFY BEFORE PUBLICATION: provider/developer request process]`.

Retention: in-memory flow, DNS cache, and mapping data are bounded and normally end with engine/process lifecycle. Settings remain until changed, app data is cleared, or the app is uninstalled. `[VERIFY BEFORE PUBLICATION: legal retention and support records]`.

Children/target audience, international processing, policy update notice, and legal bases: `[VERIFY BEFORE PUBLICATION]`.
