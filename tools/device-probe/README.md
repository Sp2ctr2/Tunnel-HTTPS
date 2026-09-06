# Luna device-probe helper

This is an isolated, dependency-free Android APK for on-device network verification. It is
deliberately packaged as `com.luna.deviceprobe`, separate from the production package. The
activity uses ordinary `Socket`, `DatagramSocket`, `InetAddress`, and `HttpURLConnection` calls;
it never calls `VpnService.protect()`. A VPN that captures this package therefore sees the
probe traffic.

The helper is built without Gradle or network downloads:

```sh
cd /home/sp2ctr2/Projects/TunnelHTTPS_Competition_WORKING_COPY/project/tools/device-probe
./build.sh
```

`build.sh` uses the checked-in/local toolchain paths requested for this verification:

- `/home/sp2ctr2/.cache/latch-toolchain/android-sdk/platforms/android-36/android.jar`
- `/home/sp2ctr2/.cache/latch-toolchain/android-sdk/build-tools/36.0.0/{aapt2,d8,apksigner}`
- `/home/sp2ctr2/.cache/latch-toolchain/jdk17/{bin/javac,bin/java,bin/keytool}`

The output is `build/luna-device-probe.apk`. Generated files stay under this directory.

## Fixture probes

The parent fixture is `tools/network_fixture.py`: HTTP `:18080`, TCP echo `:18081`, and UDP
echo `:18082`, serving the deterministic byte stream `byte[i] = i % 251`. The emulator reaches
the host as `10.0.2.2` over IPv4 and `fec0::2` over IPv6 in the current baseline setup.

Install and run only when the emulator/VPN owner has coordinated the test window:

```sh
./run.sh install

# HTTP GET + Content-Length + byte count + SHA-256 check.
./run.sh launch --es probe http --ei fixture-size 65536

# TCP echo with a 64 KiB deterministic payload and exact reply digest.
./run.sh launch --es probe tcp4 --es tcp-family 4 --ei fixture-size 65536

# UDP echo with a 4 KiB deterministic datagram and exact reply digest.
./run.sh launch --es probe udp4 --es udp-family 4 --ei fixture-size 4096

# IPv6 equivalents. The explicit host avoids an IPv4 fallback.
./run.sh launch --es probe http \
  --es http-url 'http://[fec0::2]:18080/bytes?size={size}' \
  --ei fixture-size 65536
./run.sh launch --es probe tcp6 --es tcp-host fec0::2 --es tcp-family 6 \
  --ei fixture-size 65536
./run.sh launch --es probe udp6 --es udp-host fec0::2 --es udp-family 6 \
  --ei fixture-size 4096

# Wire DNS to the VPN virtual resolver. The query is ordinary UDP to 10.111.0.1:53;
# expected RCODE 3 is NXDOMAIN and 2 is SERVFAIL.
./run.sh launch --es run-id dns-wire-nxdomain --es probe dns-wire \
  --es dns-wire-name ads.example --ei expected-rcode 3
./run.sh launch --es run-id dns-wire-servfail --es probe dns-wire \
  --es dns-wire-name doh-fail.example --ei expected-rcode 2

# TCP FIN and receive-window pressure variants. Both remain bounded by timeout-ms.
./run.sh launch --es run-id tcp-fin --es probe tcp4 --es tcp-family 4 \
  --ei fixture-size 4096 --ez tcp-shutdown-output true
./run.sh launch --es run-id tcp-pressure --es probe tcp4 --es tcp-family 4 \
  --ei fixture-size 1048576 --ez tcp-slow-read true --ez tcp-shutdown-output true \
  --ei slow-read-delay-ms 2 --ei slow-read-chunk-bytes 1024 --ei timeout-ms 10000

./run.sh logcat
```

The activity logs status, selected address family, remote endpoint, byte counts, and SHA-256.
It never logs response bodies or payload contents. `fixture-size` generates the same pattern as
the fixture inside the APK, so no expected digest needs to be copied from the host.

## Repeated benchmark records

The helper supports bounded repeated runs without changing VPN state:

```sh
./run.sh launch --es run-id latency-example --es probe http \
  --es http-url 'http://10.0.2.2:18080/bytes?size={size}' \
  --el fixture-size 4096 --ei warmup-runs 10 --ei measured-runs 30 \
  --ei concurrency 1 --ei duration-cap-ms 30000
```

Each run emits `BENCH_SAMPLE_JSON` and `BENCH_SAMPLE_CSV` records. A batch record reports
the wall time for a concurrency iteration. `warmup-runs`, `measured-runs`, `concurrency`,
`repeat-delay-ms`, and `duration-cap-ms` are bounded by the activity. TCP fixture writes use a
fixed 64 KiB buffer, and HTTP/TCP receive paths stream data; a 100 MiB transfer does not create
a 100 MiB payload object in the helper.

The host harness labels the parent-controlled mode and keeps the helper records under a new
`benchmark-results/` run directory:

```sh
python3 tools/benchmark-runner.py latency --mode direct
python3 tools/benchmark-runner.py throughput --mode direct
python3 tools/benchmark-runner.py concurrency --mode direct
```

Run the same commands with `--mode current` and `--mode next` only after the parent has prepared
that mode. The runner does not start or stop the production VPN, change configuration, or opt in
to public traffic. Its default target is the local fixture and its resource sampler observes the
configured production package (`com.tunnelvpn.app.turbo` by default), not the helper process.

`latency` defaults to 10 warmups and 30 measured runs. `throughput` defaults to five measured
runs at 1 MiB and 10 MiB. `concurrency` defaults to levels 1, 8, and 32 at 1 MiB with bounded
warmup/measured batches. Use `tools/benchmark-summarize.py` to regenerate `summary.json` and
`summary.md`; raw helper logs, JSONL records, CSV records, and resource observations are retained.

## DNS and HTTPS sanity

DNS and public HTTPS are opt-in. This avoids accidental public traffic during a local VPN test:

```sh
./run.sh launch --es probe dns --es dns-host example.com --ez allow-public true
./run.sh launch --es probe http --es http-url https://example.com/ --ez allow-public true

# Raw TLS 1.1.1.1 check: SNI/hostname is cloudflare-dns.com, address is used directly.
# The probe performs TLS endpoint identification, then sends HEAD / and records only
# handshake stages, exception class/message, and the HTTP status. Use 1500 ms explicitly.
./run.sh launch --es run-id tls-head-cloudflare --es probe tls-head \
  --es tls-host cloudflare-dns.com --es tls-address 1.1.1.1 --es tls-path / \
  --ez allow-public true --ei timeout-ms 1500
```

The HTTPS probe uses the platform's normal certificate validation, follows no redirects, and
redacts query strings/fragments from logs. A local/private host does not require
`allow-public`; any non-local hostname does.

## Arguments

All arguments are exported as `am start` extras:

- `--es probe dns|dns-wire|http|tls-head|tcp|tcp4|tcp6|udp|udp4|udp6|all`
- `--es dns-host HOST`
- `--es dns-wire-host HOST --ei dns-wire-port PORT --es dns-wire-name NAME`
- `--es dns-wire-type A|AAAA --ei expected-rcode 0..15`
- `--es http-url URL` (`{size}` is replaced by `--ei fixture-size N`)
- `--es tls-host HOST --es tls-address ADDRESS --es tls-path PATH` with explicit
  `--ez allow-public true`; the raw TLS HEAD timeout is capped at 1500 ms by default.
- `--es tcp-host HOST --ei tcp-port PORT --es tcp-family any|4|6`
- `--es udp-host HOST --ei udp-port PORT --es udp-family any|4|6`
- `--el fixture-size N` or `--ei fixture-size N` (streamed deterministic pattern; UDP maximum is
  65,507 bytes; stream cap is 128 MiB)
- `--ei warmup-runs N --ei measured-runs N --ei concurrency N` for bounded repeated probes
- `--ei repeat-delay-ms N --ei duration-cap-ms N` for inter-run delay and per-sample cap
- `--ez tcp-shutdown-output true` to half-close the TCP send side after the request
- `--ez tcp-slow-read true --ei slow-read-delay-ms MS --ei slow-read-chunk-bytes N`
  to pressure the TCP receive window while preserving an exact echo digest
- `--es run-id ID` to correlate activity/logcat lines with a fixture run
- `--es expected-sha256 HEX` and `--el expected-bytes N` for arbitrary controlled transfers
- `--ei timeout-ms MS` (clamped to 250..30,000 per blocking operation)
- `--ei max-body-bytes N` (HTTP cap)
- `--ez allow-public true` (required for non-local DNS/HTTP/TCP/UDP targets)

`probe all` runs every configured protocol/family in order. With no probe argument the activity
only prints usage and performs no network operation.
