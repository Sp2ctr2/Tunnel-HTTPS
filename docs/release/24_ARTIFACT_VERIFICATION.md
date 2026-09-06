# Artifact verification

> Historical artifact snapshot. The hashes and no-emulator constraint below belong to the 2026-09-04 run, not the current APKs. See [2026-09-05 implementation and verification](IMPLEMENTATION_VERIFICATION_2026-09-05.md) for the current evidence.

Verified: 2026-09-04 (Asia/Seoul)

## Commands and results

| Command/check | Result |
|---|---|
| `gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease` | PASS in 3m; 99 tasks, R8 and resource shrinking executed |
| JVM tests | PASS; 555 tests in 44 suites, 0 failures, 0 errors, 0 skipped |
| Android lint | PASS; 0 errors, 6 version-availability warnings |
| Turbo microbenchmark | PASS; latest clean full-suite run measured DNS validation p50/p95 5/6 µs and risk engine p50/p95 0/1 µs over 10,000 iterations |
| Physical-device/instrumentation testing | NOT RUN; ADB and emulator use explicitly excluded |
| Release signature | APK and AAB are unsigned because release signing secrets were not supplied |

The six lint notices are target/compile SDK 37 availability and newer AndroidX Core, WebKit, coroutines, and OkHttp versions. They are not correctness errors. Major platform/library upgrades were not mixed into the network-protocol change without device compatibility evidence.

## Artifacts

| Path | Bytes | SHA-256 | Status |
|---|---:|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 3,673,610 | `9ed307d9c555a8e1dced69bf4ec9ce9e1a60336fb758ef5e7b7897df1db4bee4` | v2 debug-signed; local testing only |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 538,524 | `2517d272390e03c80d894e5cd03619f2852a4f134ce6f8a13442d06c00874804` | unsigned |
| `app/build/outputs/bundle/release/app-release.aab` | 1,070,707 | `665b566392786e0d14fe3fbe26021f19948b17f3bc6bbf1fb5e27fcbe80201da` | unsigned |
| `app/build/outputs/mapping/release/mapping.txt` | 5,767,228 | `9e8f133151218326016b979a1311fd3acd07dd020ab6d39befcfbc974f773b3d` | R8 mapping |
| `app/build/reports/lint-results-debug.html` | 62,498 | `ccbd23eb9cc50c18b42f9d504d86f6b74d94cd5cff0f58dacf8d259fb0743472` | lint report |

Release identity: package `com.tunnelvpn.app`, version code 10, version name 1.0.0, minimum API 24, target/compile API 36, cleartext disabled, backup disabled, and a non-exported `BIND_VPN_SERVICE`-protected VPN service. No native `.so` libraries are packaged.
