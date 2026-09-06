# Tunnel HTTPS Turbo AI

## Scope

Turbo AI is an on-device connection-strategy optimizer. It runs only when the existing Turbo Mode is enabled. It never classifies packet payloads, detects censorship, profiles users, or sends model data to a server.

The only learned strategy arms are the four ClientHello modes already implemented and tested by the forwarding engine:

1. `tls-sni-multi-v1`
2. `tls-hostname-v1`
3. `tls-single-safe-v1`
4. `tls-plain-v1`

Socket buffers, TCP_NODELAY, TUN MTU, UDP/443 policy, DNS provider behavior, address families, and retry budgets are not model-controlled.

## Model

The model is a contextual UCB bandit with bounded epsilon exploration.

- Cold start: the existing deterministic Turbo order is selected.
- Exploitation: each eligible arm is scored using its recency-weighted mean reward plus a UCB confidence bonus.
- Generalization: a bounded coarse-network prior is shared across destinations. It is used only after 24 relevant samples and is capped so destination-specific evidence takes priority.
- Exploration: starts at no more than 8%, decays toward 1%, is capped at 2% on metered networks or in battery saver, and is disabled on roaming or unvalidated networks.
- Exploration balance: explicit exploration chooses among the least-observed safe non-baseline arms.
- Adaptation: observations have a seven-day half-life. A meaningful network transition halves arm confidence. Entries older than 30 days are removed.
- Capacity: at most 512 destination-context groups are retained. Least-recently-used groups are evicted first.

The prediction target is connection behavior, not content. Decisions occur once per TLS connection after a complete supported ClientHello is available.

## Context features

Only coarse operational data is encoded:

- HMAC-SHA256 destination key truncated to 128 bits
- destination port
- Wi-Fi, cellular, Ethernet, other, or unknown transport
- metered state
- roaming state
- validated-internet state
- battery-saver state
- valid ClientHello and local SNI-presence booleans
- selected latency profile
- coarse recent handshake/RTT bucket
- coarse recent retry bucket
- coarse recent success-ratio bucket
- coarse network-change bucket
- coarse connection-pressure bucket
- broad previous-failure category

No SSID, BSSID, SIM identifier, IP address, precise cell data, URL, hostname, cookie, account value, session identifier, DNS response, or packet payload is stored in model state.

The destination HMAC key is a random 32-byte app-private secret. Raw hostnames exist only transiently in the already-required TLS/DNS routing path and are never written by Turbo AI.
Recent success, retry, and handshake features use an exponential moving average instead of a full-session average. The recent window is reset on meaningful network transitions.

## Reward

Rewards are clamped to `[-1.0, 1.0]`.

For successful connections:

```
0.45
+ 0.20 * latencyScore
+ 0.15 * stabilityScore
+ 0.12 * throughputScore
- retryPenalty
- fallbackPenalty
- decisionCpuPenalty
- batterySaverPenalty
- shortAbruptDisconnectPenalty
```

- `latencyScore = 1 - clamp(handshakeMs / 4000, 0, 1)`
- `stabilityScore = clamp(connectionDurationMs / 30000, 0, 1)`
- `throughputScore = clamp(log(1 + bytesPerSecond) / log(1 + 5,000,000), 0, 1)`
- `retryPenalty` is up to `0.08` for three or more retries
- `fallbackPenalty` is `0.06`
- `decisionCpuPenalty` is up to `0.06`, normalized at one millisecond of prediction time
- `batterySaverPenalty` is `0.02`
- `shortAbruptDisconnectPenalty` is up to `0.12` and decays to zero as the stable connection duration approaches 30 seconds

For failures:

```
-0.70
- 0.15 if timed out
- 0.08 if disconnected abruptly
- retryPenalty
- fallbackPenalty
- decisionCpuPenalty
- batterySaverPenalty
```

Strategy-dependent failures receive an explicitly negative update while keeping extreme latency and throughput values bounded. Failures before a strategy is applied, such as upstream TCP connect failure or network transition cancellation, are retained in aggregate evaluation but do not bias a fragmentation arm.

## Persistence and performance

The model uses a versioned compact line codec stored in app-private `SharedPreferences`.

- Prediction reads only in-memory concurrent maps and an atomic coarse-network snapshot.
- Outcome updates are batched through one asynchronous drain rather than launching work for every packet.
- Persistence is debounced for 15 seconds and flushed at engine shutdown.
- Missing state starts cold.
- Invalid schema or entries recover to a safe empty model.
- The HMAC secret is regenerated if invalid; incompatible learned entries are discarded.

The target p95 prediction overhead is below one millisecond after initialization. A deterministic JVM microbenchmark enforces this target.

## Safety and fallback

The safety policy runs before the model:

- Fragmentation arms require port 443 and a complete supported TLS ClientHello.
- Invalid/non-TLS input has only the plain strategy available.
- Split offsets continue to be produced solely by `TlsClientHello.fragmentationPlan`.
- Total upstream handshake attempts are capped at eight and remain inside the existing time budget.
- An AI initialization, prediction, update, or persistence failure does not stop forwarding.
- If AI is unavailable, disabled internally, or forced to baseline, the existing adaptive deterministic Turbo path is used.
- A failed learned strategy proceeds through the existing safe fallback arms, ending with the plain ClientHello strategy.

Full-forward modes provide stateful IPv4 and IPv6 TCP/UDP relay. Safe Hop-by-Hop/Destination options and Routing headers with `segments-left=0` are normalized within bounded limits, and fragments use bounded reassembly; AH/ESP, unsafe or unsupported headers, and invalid fragments fail closed. Local ICMPv6 echo is answered, oversized IPv6 UDP can receive a bounded Packet Too Big response, NAT64/DNS64 and local TCP DNS are supported, and adaptive UDP/443 probes can mark a destination for temporary upstream TCP fallback. UDP/443 fallback is driven only by structurally valid QUIC v1/v2 Initial traffic and explicit per-address probe outcomes.

## Evaluation

Naturally occurring connections are labeled as learned, exploration, baseline, baseline fallback, or default safe. No duplicate connections are created. Per-policy aggregates retain:

- samples and success count
- median handshake latency from a bounded 128-sample window
- retry and fallback counts
- average normalized reward
- average decision overhead

At least 30 learned and 30 baseline samples are required before the evaluator considers a policy comparison sufficiently populated. The user interface does not claim AI accuracy or guaranteed improvement.
