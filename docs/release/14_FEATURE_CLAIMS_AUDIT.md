# Feature claims audit

| Claim/UI concept | Runtime evidence | Status |
|---|---|---|
| Local DNS/DoH protection | DNS captured locally; DoH uses HTTPS providers | supported with provider/privacy limitations |
| Ad blocking | bundled suffix matching returns DNS policy responses | supported; list coverage/effectiveness not benchmarked |
| Split bypass/unblocked sites | configured domains are mapped to a virtual address and handled by local TCP forwarder | wording is broader than proven behavior; not a general Internet bypass |
| Turbo | STRONG path + hedged resolver/domain mapping + underlying reachability health | deterministic/adaptive behavior; not AI |
| DoH “falls back to regular DNS on failure” | enabled DoH tries other DoH providers then SERVFAIL; system DNS is used only when DoH is disabled | unsupported UI promise; production P1. Text not changed due explicit UI constraint |
| FAST/BALANCED/STRONG | all three currently use full dual-stack forwarding and eligible TLS ClientHello fragmentation | compatibility preset names; no runtime security distinction should be claimed |
| Protected/secure | local packet policy exists | must not imply anonymity, remote VPN encryption, zero leaks, or complete traffic support |

No “AI-powered,” military-grade, anonymous, untraceable, zero-log, fastest, or 100%-secure claim was found in first-party visible text. Store material must avoid those claims and must state that there is no remote full-traffic VPN gateway.
