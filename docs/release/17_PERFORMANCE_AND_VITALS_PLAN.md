# Performance and vitals plan

Static findings: packet loops reuse the read buffer but copy admitted packets per dispatch; DNS work is capped at eight; resolver caches and mapper are bounded; TCP/UDP executors and flow counts are bounded; no packet logging exists. Relays still allocate per-flow buffers and use blocking sockets. The resource-pressure model does not yet consume complete live thermal/FD/heap signals, so no adaptive-memory claim is justified.

Measure on device:

- thread/FD/heap counts at idle, 48 TCP, 64 UDP, one hour, eight hours, and after every handover/stop;
- packet-loop CPU and allocation rate; TUN write latency; DNS p50/p95/failure/saturation;
- foreground battery use, Doze wakeups, thermal state, and background network bytes;
- cold/warm startup, connect success/latency, unexpected disconnects and recovery;
- Play user-perceived crash and ANR rates, excessive wake locks, low-memory kills.

Do not transmit domain/IP/payload diagnostics. If production telemetry is later proposed, complete a fresh privacy/Data Safety/disclosure review before implementation.
