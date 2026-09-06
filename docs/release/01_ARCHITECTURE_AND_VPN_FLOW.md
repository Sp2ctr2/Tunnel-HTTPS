# Architecture and VPN flow

## Module and responsibility map

| Component | Owner/lifecycle | Responsibility | Threads/resources |
|---|---|---|---|
| `MainActivity` | Activity | Owns WebView, JS bridge, permission flow, preferences, UI state receiver | Main thread; lifecycle-owned IO diagnostics scope; WebView and receiver |
| `BootReceiver` | system broadcast | Reconstructs validated configuration and starts the service after boot/package replacement | Main receiver callback calls `goAsync()`; a short-lived daemon worker retains the `PendingResult` through validation/service launch and always finishes it |
| `TunnelVpnService` | Android `VpnService` | Foreground lifecycle, serialized engine ownership and replacement/rollback, state broadcasts, network callback dispatch, Turbo reachability probe | Main service callbacks perform immediate foreground promotion and enqueue work; one single-thread lifecycle executor owns start/stop/reset work; one bounded probe thread; notification |
| `LocalProtectionEngine` | service | TUN builder, packet dispatch, DNS/TCP/UDP ownership and shutdown ordering | IO coroutine scope; blocking TUN read; TUN PFD/streams |
| `DnsProtectionEngine` | engine | Parses IPv4/IPv6 UDP/53 and local TCP/53 messages, ad blocking, selective HTTPS/SVCB handling, DNS64, split-domain virtual mapping, DNS response creation | resolver coroutine; no persistent FD |
| `DohResolver` / `SecureResolverRace` | engine | TLS-protected DNS providers, validation, cache, hedged requests | OkHttp dispatcher/sockets; closed by engine |
| `TurboTcpForwarder` | engine | Local TCP relay, local TCP DNS termination, TLS ClientHello fragmentation, and bounded reliability state | bounded elastic executor/dispatcher; protected sockets; per-flow buffers |
| `TurboUdpForwarder` | engine | Local IPv4/IPv6 UDP relay with structural QUIC Initial classification and adaptive TCP fallback | bounded executor/dispatcher; protected datagram sockets |
| `TurboDomainMapper` | engine | Bounded domain-to-virtual-IPv4 mappings for configured split domains | synchronized in-memory maps |
| `AppBypassManager` | TUN setup | Applies disallowed packages or browser-only allowed packages | PackageManager query only |
| `DiagnosticsState` | process singleton | Runtime counters and verified snapshots exposed to local UI | atomics/volatile fields |

## Start-to-stop call graph

1. Local `index.html` calls a `@JavascriptInterface` method on `MainActivity.TunnelBridge`.
2. Bridge configuration changes are posted to the main thread and stored in `SharedPreferences`; list inputs are bounded before parsing.
3. Connect calls `VpnService.prepare()` on the main thread. Permission rejection returns UI to disconnected without starting service.
4. Permission success constructs an explicit `ACTION_START` intent and calls `startForegroundService` on API 26+.
5. `TunnelVpnService.onStartCommand()` checks disclosure/command state, immediately promotes valid start/restore commands to the foreground, and enqueues lifecycle work. The single-thread lifecycle worker reads and validates configuration, broadcasts `connecting`, starts a prepared replacement while the prior engine remains active, then atomically publishes the replacement and retires the prior engine. A pre-commit failure retains the prior engine; a post-establishment failure attempts to re-establish the previous configuration before reporting a terminal error.
6. `LocalProtectionEngine.start()` configures the builder. FAST, BALANCED, and STRONG currently capture the IPv4 and IPv6 default routes and enable SNI protection; their names are compatibility presets. Browser/app rules are applied before `establish()`.
7. `establish()` yields one `ParcelFileDescriptor`; the engine owns its input/output streams. A blocking IO coroutine starts the packet loop.
8. IPv4/IPv6 UDP/53 enters `DnsProtectionEngine`; TCP/53 is framed and terminated locally through `TurboTcpForwarder`. Capacity is 32 concurrent resolver jobs and saturation returns immediate SERVFAIL. Other TCP enters the same local relay and other UDP enters `TurboUdpForwarder`. Structurally valid QUIC v1/v2 Initial flows are observed per destination and use a bounded, temporary TCP fallback only after two explicit failed probes. Non-QUIC UDP/443 is not classified as QUIC. Local ICMPv6 echo is answered; other ICMP has no forwarder.
9. All upstream sockets are protected and bound to the selected non-VPN underlying `Network` before connect. DoH uses pinned addresses first, preferring native IPv6 on IPv6-only links and deriving bounded NAT64 candidates from pinned IPv4 when needed; application DNS questions remain on the validated encrypted-resolver path and are never used for provider bootstrap.
10. `NetworkChangeHandler` receives connectivity callbacks on its main-looper handler but only enqueues tagged events there. The lifecycle worker rejects events from a retired handler epoch or non-owning service instance, then publishes current context/reset events to the pending and active engines; resolver cache and TCP/UDP flow resets therefore do not run on the main thread. Engine shutdown still rejects work after its stopping flag is set.
11. Turbo desired state restarts configuration. The post-start probe uses protected sockets and therefore measures underlying reachability, not the TUN data path. It only changes Turbo runtime health state, not routing.
12. Stop action reaches the service. It invalidates the generation, interrupts the probe, unregisters the network callback, marks the engine stopping, cancels the packet job, closes TUN streams/PFD to release blocking read, closes forwarders/resolvers/dispatchers, removes the notification, then broadcasts disconnected.

## Failure semantics

- TUN establishment failure: fail closed; service reports error and stops.
- DoH provider failure: tries configured DoH providers; when all fail returns DNS SERVFAIL. It does not silently use plaintext DNS.
- DNS saturation: immediate SERVFAIL and a failure metric.
- Browser-only with no applicable browsers: start fails; it does not expand capture to all apps.
- IPv6 safe Hop-by-Hop/Destination options and Routing headers with `segments-left=0` are normalized within bounded limits; fragments use bounded reassembly. AH/ESP, unsafe or unsupported headers, invalid fragments, and non-echo ICMPv6 fail closed. Local ICMPv6 echo and bounded Packet Too Big responses for oversized IPv6 UDP are handled locally; NAT64/DNS64, local TCP DNS, and adaptive UDP/443 TCP fallback remain supported.
- IPv4 fragments are validated and reassembled before the common security and forwarding path, with fixed-lifetime overlap tombstones, 64 contexts, a 2 MiB retained-byte cap, and no forwarding of partial packets or unsafe IP options.
- Turbo reachability probe failure: connection remains established and Turbo state becomes degraded; route/forwarder state is unchanged.

Device verification remains required for blocking-read release, process death, rapid toggle, network handover, and OS/OEM foreground-service behavior.
