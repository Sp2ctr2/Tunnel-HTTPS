# Manual device test matrix

All rows are unchecked and `REQUIRES PHYSICAL DEVICE`. This project review does not use ADB. Collect only owner-approved, sanitized on-device diagnostics, system VPN state, resource counters, battery statistics, and external packet captures where indicated; never retain domains or payloads.

| Scenarios | Preconditions and steps | Expected result | Severity if failed |
|---|---|---|---|
| Fresh/upgrade install; VPN accept/deny; repeated permission | API 24/33/36/37 representative devices; install, trigger connect, accept/deny/retry | no start before permission; denial stays disconnected; one active service | Critical |
| Disclosure accept/decline | after disclosure implementation | decline never starts processing; accept version recorded; disclosure re-openable | Critical |
| Notification accept/deny | API 33+ | service remains legally/user-visibly represented; stop available through supported system surface | High |
| Normal, duplicate, rapid connect/disconnect; disconnect while connecting; Turbo spam | 20+ repetitions under active traffic | no duplicate loop, stale active UI, crash, leaked thread/socket, or post-close write | Critical |
| Background, screen off, swipe, process/service kill, reboot, always-on, competing VPN | toggle auto-start and Android VPN settings | intentional stop stays stopped; restart behavior is accurate; no illegal FGS start | High |
| Wi-Fi→mobile, mobile→Wi-Fi, airplane mode, captive/no-Internet/weak Wi-Fi, repeated handover | issue DNS/TCP/UDP during each transition | old flows close; cache resets; bounded reconnection; truthful failure state | High |
| IPv4-only, dual stack, IPv6-preferred, IPv6-only, NAT64 | browse and run DNS/TCP/UDP probes plus safe extension, fragment, echo, and MTU cases | all three presets relay dual-stack DNS/TCP/UDP and eligible TLS fragmentation; safe Hop-by-Hop/Destination and `segments-left=0` Routing headers normalize, bounded fragments reassemble, AH/ESP/unsafe paths fail closed, local echo/PTB and NAT64/DNS64 work, and TCP DNS/UDP upstream TCP fallback stay bounded without leaks or hangs | Critical |
| DNS normal, all providers timeout, malformed, pointer loop, transaction mismatch, rebinding ranges, >8 concurrent | network shaping/proxy and crafted packets | validated response or SERVFAIL; no plaintext fallback while DoH enabled; saturation gets SERVFAIL | Critical |
| Browser-only no browser, OEM browser, HTTPS-link non-browser, Work Profile, different APIs | enable browser-only and inspect allowed packages | zero applicable packages fails start; no silent all-app expansion | Critical |
| Missing bypass package; 200-package/220-domain boundaries; oversized bridge input | inject through UI/intent test harness | explicit rejection; no silent truncation or Binder failure | High |
| >220 distinct mapper requests | packet/unit harness plus live DNS/TCP | no stale virtual/domain association or wrong destination | Critical |
| 48+ TCP; zero/small receive window; delayed server; loss/reorder; repeated FIN/RST; invalid ACK; TUN write failure | network shaper + packet capture | bounded admission; zero window respected; correct sequence/FIN; failures close flow | Critical |
| 64+ UDP; response over MTU; QUIC v1/v2 Initial; malformed/non-QUIC UDP/443; games IPv4/IPv6 | traffic generator and capture | bounded flows; oversize counted/dropped; non-QUIC preserved; fallback only after two explicit QUIC probe failures and recovers after TTL/network change | Critical |
| Stop while TUN read blocked; executor shutdown during traffic | debugger/test hook | read releases; no new tasks/writes; every FD closes once | Critical |
| WebView external script/frame/image/fetch; malicious/oversized bridge input; repeated toggles | proxy/test HTML attempts | blocked except exact reachability path; no privileged external frame; bounded input | Critical |
| One/eight-hour/overnight, Doze, battery saver, low memory | record thermal, battery, FD/thread/heap hourly | no monotonic leak, busy loop, reconnection storm, or stale notification | High |
| Rotation, font/language/RTL, TalkBack, keyboard, notification reopen | accessibility test settings | state preserved; readable/focusable controls; disconnect discoverable | Medium |

Device coverage: Samsung, Pixel/AOSP-like, one additional OEM, low-memory device, Android 24, current Android, and a 16 KB page-size environment. Packet-level cases may require root, VPN-aware capture, or an external capture router.
