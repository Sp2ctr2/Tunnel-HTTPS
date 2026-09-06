# Repository baseline

Status date: 2026-07-28 (Asia/Seoul)

## Reproducibility

- Root: `TunnelHTTPS_Competition/project`
- Git: no `.git` metadata and no Git executable were available. Branch, commit, dirty state, and a trustworthy repository diff are therefore `NOT AVAILABLE`.
- Pre-document inventory: 81 files, 54 Kotlin files, 27 JVM test source files, no instrumentation source files.
- Module: one Android application module, `:app`; no product flavors.
- Gradle wrapper: 9.4.1 with distribution SHA-256 pin.
- Android Gradle Plugin: 9.2.1. Kotlin support is provided by the Android plugin; no standalone Kotlin plugin version is declared.
- JDK used: Android Studio JBR 21.0.10. Bytecode target: Java/JVM 11.
- SDK: compile 36, target 36, minimum 24.
- Identity: namespace/application ID `com.tunnelvpn.app`; debug suffix `.turbo`; version code 1; version name 1.0.
- UI: View/WebView with local HTML/CSS/JavaScript. No Compose.
- NDK/JNI/native source: none found. Generated APK and AAB contain zero `.so` files.
- CI: none found.
- Release signing: environment-variable configuration exists; no release credentials were supplied during this audit.

## Baseline build evidence

Before the Phase 5 corrections, the JVM suite executed 104 tests and had three UI-contract failures. The original failures were recorded before changing sources. No immutable pre-change source snapshot can be reconstructed because Git metadata is absent.

After corrections, the clean verification is recorded in `24_ARTIFACT_VERIFICATION.md`. Build output paths under `app/build` are reproducible and not treated as source-controlled release artifacts.

## Limitations

- Branch/commit and unified pre/post diff: `NOT AVAILABLE`.
- Physical-device smoke-test baseline was supplied by the owner, not rerun in this environment.
- Play Console, signing key, legal identity, and hosted policy information: `REQUIRES OWNER INPUT`.
