# Next engine engineering journal

Mission: `/home/sp2ctr2/Downloads/TUNNEL_HTTPS_ASTRA_NEXT_GEN_ENGINE_MISSION_v2 (1).md` (latest download; identical to unsuffixed v2). User authorizes engineering judgment over speculative designs. UI unchanged; existing SNI strategies and MTU profiles are compatibility constraints.

## Baseline preservation

No Git repository exists in this project or its parent chain. No commit identifier is invented. Before edits, source/configuration/tools/docs/test XML and the known APKs were archived under `benchmark-results/baseline-20260905-1240/`.

- source-and-evidence.tar.gz SHA-256: `919a3139738371bbef4336d5c40bd7bc44f9f19c1155abeb2de9b3e7ed37eda8`
- debug APK SHA-256: `10d0ee6cb40c7d05be0b2f1b05463c3ec9ca959f876c59bdb1ed6840b940c0f7`
- validation release SHA-256: `afbd604a83ad1d796214bc676d5a2f2e37e0b71b6a7dc3ad0f18f788c5cb2c48`
- preserved evidence: 47 suites / 604 JVM tests, Node UI 2/2; this is the earlier validated baseline, not a newly executed next-engine test result.

## Execution and resource policy

Sol owns protocol/concurrency repairs and experimental architecture design. Luna Max owns bounded measurement plumbing and independent verification. Parent owns integration, risk decisions, evidence interpretation and fixture resources. One Gradle build and one constrained emulator maximum. No competing device controllers. Experiments follow P0 repair and baseline measurement; only one R2+ optimization at a time.

User subsequently enabled full access and requested no new code comments from parent/Sol and comment removal by Luna. Remove ordinary authored-source comments after write owners freeze files; retain legal notices and functional tool/compiler directives. Markdown engineering records remain documentation. Current emulator: read-only API36 AVD, two guest cores and host affinity 10/11, nice 10; guest minimum RAM 2560MiB. No second emulator.

## P0 UDP committed-payload race

- Hypothesis: a receive-side error can reuse a mutable last-payload reference after a newer send commits.
- Assigned: Sol/Mencius; `TurboUdpForwarder.kt` and its behavioral tests.
- Risk: R3 concurrency; high impact, bounded correction preferred over rewrite.
- Acceptance: deterministic old-error/new-payload barrier; no replay of an already committed payload; existing response publication, integrity and generation constraints hold.
- Benchmark before/after: pending; primarily correctness work.
- Decision: IMPLEMENTED, awaiting independent full gate. Baseline already prevented ambiguous-payload replay; the repair advances receive socket ownership after an ambiguous old error without replaying a committed payload, allowing the next unsent payload to use the next candidate. The new deterministic barrier asserts send history A/B/C on sockets 1/1/2. Do not describe this as a demonstrated baseline duplicate-send bug.

## P0 resolver and virtual mapping isolation

- Hypothesis: mapping publication can race network generation and fake-IP reuse can alias delayed traffic; EDE failure semantics may be lost in DNS64 fallback.
- Assigned: Sol/Mendel; mapper and DNS validation/protection files with tests.
- Risk: R3 parser/commit boundary; focused fixes only.
- Acceptance: G1→G2 barrier, fake-IP ABA, hard-stop EDE vs ordinary NODATA matrix; private compatibility and encrypted resolver semantics preserved.
- Benchmark before/after: pending; primarily correctness work.
- Decision: IMPLEMENTED, awaiting independent full gate. Added mapper commit leases, finite reuse quarantine on retirement, and preservation of security-hard-stop semantics before DNS64/mapping. Sol reports 135 direct JUnit cases passed; that self-check does not replace the Luna full Gradle gate. Quarantine does not guarantee isolation of packets delayed indefinitely past its configured interval.

## Measurement infrastructure

- Assigned: Luna Max/Maxwell; independent Android probe and benchmark orchestration/aggregation.
- Risk: R0, test-only. Preserve every sample and record warmup, failures, build hash, environment and mode.
- DIRECT/CURRENT/NEXT comparisons use the same endpoints, load and device settings. Samples under synthetic impairment are labelled lab evidence. Physical carrier claims remain unverified.
- Decision: IMPLEMENT harness, then MEASURE.

Fixture updated to stream at most 100MiB with a fixed approximately 64KiB pattern buffer; HTTP/TCP accept backlogs and worker limits are 64. Measured concurrency is capped at 32. The parent restarted the fixture after checking no established fixture TCP connections; baseline device measurement must use this same fixture version for every mode. Host-wide traffic shaping is not used.

## Architecture candidates

ShadowTwin, FlowSeal, Path Genome and AegisLocal are hypotheses, not completed features. Existing `TurboAiEngine` already implements bounded contextual learning for TLS strategies; evaluate reuse before adding a second model/runtime. Native code, new TLS stacks, Cronet and full epoch rewrites are deferred until evidence supports their cost.

### Initial component profile

`tools/EngineProfile.java` ran against the pre-edit compiled baseline classes, JDK17, 128MiB heap, host core8, ten warmup batches and thirty measured batches of 1000 operations. Every sample and class hash is in `benchmark-results/baseline-20260905-1240/component-profile.csv`. This isolates components on the host JVM; it is not an Android throughput measurement. At 32768-byte packets, observed sample medians were approximately 20µs for IPv6 checksum validation, 3.7µs for normalization, and 3.6µs for an array copy. Normalization allocated approximately one packet copy per operation. This supports investigating an exact checksum loop optimization before a flow-state rewrite. TLS ClientHello processing in the existing TCP relay is already phase-gated, so repeated TLS parsing of all application records is not established as a bottleneck.

### AI evaluation

Luna Max/Heisenberg owns an R0 evaluation of the existing learner against fixed order and a deterministic recent-observation heuristic on seeded synthetic scenarios. Synthetic latency labels must never be described as measured network latency. Actual selection overhead can be measured separately. No new runtime dependency or model is accepted before this comparison.

The first completed evaluation (`benchmark-results/aegis/aegis-20260905T040448Z/summary.md`) produced REWORK_C: on 360 held-out evaluation samples, the deterministic EMA reference had 12.5% synthetic failures versus 13.89% for the existing learner, and lower synthetic regret. These are generated scenario outcomes, not measured Internet failures. No new AI policy is promoted from this result. Sol is reviewing whether cold-start handling discards useful shared evidence; this is a design review, not an implemented path predictor.

## P0 independent full gate

Luna Max's full Gradle run in `benchmark-results/aegis/aegis-20260905T040448Z/raw-console.log` passed: `testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease`, 2m37s, 98 actionable tasks (26 executed). Parent independently parsed final XML: 49 suites, 617 tests, zero failure/error/skip. Failed/interrupted earlier harness runs remain preserved: one out-of-range synthetic seed constant and one incorrect corrupted-input expectation were corrected in the evaluation test. They were not production network regressions. This gate precedes comment cleanup and any optimization integration; it is not yet the final next-engine gate.

## Standards checked

- RFC 8914: https://www.rfc-editor.org/rfc/rfc8914.html — EDE semantics inform local synthesis policy; EDE itself does not change DNS RCODE semantics.
- RFC 9849: https://www.rfc-editor.org/rfc/rfc9849.html — ECH is standardized; this does not establish support in this app's current TLS stack or third-party sessions.
- RFC 1071: https://www.rfc-editor.org/rfc/inline-errata/rfc1071.html — parallel checksum summation is established prior art. No novelty claim is made for loop unrolling.
- Linux flowtable: https://www.kernel.org/doc/html/v5.10/networking/nf_flowtable.html — established-flow fast paths are prior art; the presence of a fast path alone is not an invention claim.

## R1 exact checksum loop

- Hypothesis: independent accumulators reduce dependency/loop overhead without omitting any packet byte or policy check.
- Assigned: Sol/Mencius; isolated Java prototype in `tools/checksum-prototype/`, then one private Kotlin checksum helper and an independent reference test.
- Risk: low-to-moderate R1, confined arithmetic change; no new state, socket, parser limit, TLS behavior or allocation pool.
- Differential evidence: `benchmark-results/checksum-prototype/differential-summary.csv`; fixed/random, corruption and invalid-policy cases agreed with an independent byte-wise folding oracle.
- Measurement: six packet sizes (1200/1201/4096/4097/32768/32769), ten warmup and thirty measured paired batches, alternating order, real baseline compiled class vs isolated candidate, 128MiB host JVM. Raw samples and class hashes preserved.
- Observed paired component ratios: approximately 1.42–1.72 baseline/candidate; control overhead approximately 7ns/op; no app-throughput or battery inference.
- Decision: KEEP for narrow integration and final regression measurement. Final Kotlin implementation must be checked independently; Java-prototype speed is not assumed to transfer unchanged to Android ART. Rollback is restoration of the single private word-sum helper; the slow oracle remains in tests. FlowSeal/NDK/concurrency rewrites are not justified by this measurement.

## Permission restart and resumed ownership

Three agents created before the permission change retained actual `approval_policy=on-request`, `sandbox_policy=workspace-write`, network restricted. The later fourth agent had `never/danger-full-access`. All four were closed, preserving files and evidence; newly spawned Sol/Cicero and Luna/Volta were independently verified from session turn-context metadata as `never/danger-full-access`, with disabled permission profile. Luna's default ADB devices call succeeded without escalation. Luna/Locke subsequently took bounded authored-source comment removal and the independent build gate. No global config was rewritten to mask a stale child policy.

## Baseline concurrency finding

DIRECT 32-way HTTP: 160/160 measured flows passed. CURRENT 32-way HTTP: 151/160 passed, with nine immediate ConnectExceptions in later batches, preserved under `benchmark-results/20260905T043756Z-current-concurrency/`. CURRENT single-flow latency and 1/10MiB throughput passed. Faster successful-flow percentiles do not establish an improvement when failures differ. Sol is reviewing bounded TCP capacity and close-state retention; a capacity cause is a hypothesis until supported by source and runtime evidence.

Both DIRECT and CURRENT small HTTP requests showed approximately one second per transfer. Do not call that VPN-added latency. The helper/lab path is under review. A summarizer defect filtered batch records before aggregation; Luna corrected the loader and regenerated derived summaries without deleting raw samples.

## Exact Kotlin R1 verification

Full gate `benchmark-results/aegis/final-next-20260905T044053Z/raw-console.log` succeeded in 2m57s; parent parsed 50 suites / 619 tests, zero failure/error/skip. This is an intermediate candidate if subsequent TCP work is required.

Parent compiled the isolated Kotlin comparison adapters against the preserved ABI and ran the actual archived/current Kotlin classes on JDK17, core8, nice10, heap128MiB after Gradle completed and before device timing resumed. `benchmark-results/checksum-final-kotlin-20260905-1344/` contains input class hashes, 5181 fixed/random cases, 5110 corruption cases, ten policy cases, and 240 paired batches (ten warmup plus thirty measured per size). No differential mismatch occurred. Median paired baseline/candidate ratios were 1.56–1.61; 32768-byte medians were 20977.748ns versus 13466.9415ns. Small-size p95 was noisy and is not claimed as a universal tail improvement. This is actual Kotlin host-JVM evidence, not Android ART or total VPN speed. KEEP the narrow checksum change; no additional state or allocations are introduced by the helper.

Candidate debug SHA-256 at this gate: `b2df74fddbdad67e558152c44928e910d9053bd23e019e3d7a0bdbdf2dc22f41`.

## TCP lifecycle REWORK gate

The b2df candidate reproduced nine failures in 160 measured 32-way flows (`benchmark-results/20260905T044706Z-next-concurrency/`). The checksum optimization is not a repair for this separate failure. Sol's source review found that any SYN on a retained tuple is answered as a retransmission, even with a different initial sequence, and that retransmitted SYN-ACK uses mutable `clientNext`. Completed connections retain heavy state during TIME_WAIT. Parent then found that runner `productionDiagnostics` was captured before the run: its zero-error/open-close counters cannot establish the cause of later failures or rule out capacity rejection. A true post-run snapshot is required. Tuple reincarnation and capacity retention remain hypotheses pending targeted regression and rerun.

Parent approved one R3 lifecycle candidate: immutable initial sequence, bounded terminal-state handling and capacity release, correct stale-packet/RST ownership, no increased active-flow limit and no reclamation of half-closed flows. Scope is TCP forwarder, focused tests, and additive diagnostics only if necessary. This follows the exact-checksum R1 gate rather than overlapping two architectural rewrites. Any implementation must respect serial sequence arithmetic and not accept every different SYN as fresh. [RFC 9293 §3.6](https://www.rfc-editor.org/rfc/rfc9293.html#section-3.6) informs TIME_WAIT reopening safeguards. No claim is made that the existing short local-relay timeout implements full Internet-host 2MSL behavior.

The true cumulative post-run snapshot (`benchmark-results/20260905T044706Z-next-concurrency/post-run-diagnostics-20260905T045147Z.json`) reports exactly nine forwarding errors and `lastFailureReason=connection-capacity`, after the failed concurrency run and successful latency/throughput runs. This supports capacity admission as the proximate failure; do not blame the nine observed failures on unobserved tuple reuse. Sol was notified to prioritize bounded terminal-state capacity release. Immutable SYN acknowledgement is a separately source-confirmed defect.

## AI fairness review and production decision

Sol/Bohr's read-only audit found 720 unique destination keys across 720 samples, no reuse; C chose cold-start baseline 696 times and bounded exploration 24 times. Shared evidence alone cannot reach MEDIUM confidence under current caps. Outcomes depend on coarse profile/phase/strategy rather than destination, favoring B's pooling assumption without direct label leakage. This is a 100%-destination-churn, destination-independent synthetic online evaluation, not a general model ranking or frozen holdout: policies continue receiving chosen-outcome feedback. KEEP current production behavior. Retain REWORK_C as this scenario's outcome only. Future benchmark-only work should preserve this track and add recurring/mixed destination tracks and destination-dependent outcomes before proposing shared warm-start. No new AI policy or relaxed safety gate is shipped.

## Intermediate NEXT device evidence

The candidate debug APK `b2df74fddbdad67e558152c44928e910d9053bd23e019e3d7a0bdbdf2dc22f41` was installed on the sole `emulator-5554` device with the preserved saved configuration: route-all enabled, balanced protection, and Turbo disabled. The helper APK remained `64a16fe15dec3d3bac560fee4bc15b3d62f89c4a8a52ac3ea5f3f7669262ffea`. All NEXT cases below recorded VPN presence, tun0 MTU 32768 and matching APK provenance.

- NEXT 32-way HTTP concurrency: 151/160 measured flows passed; nine immediate `ConnectException` failures remained across batches 1–5. Raw records and per-batch counts are in `benchmark-results/20260905T044706Z-next-concurrency/`; this reproduces the CURRENT capacity symptom and is not attributed to the checksum change.
- NEXT local HTTP latency: 30/30 measured 4KiB flows passed, p50 1001.0ms and p95 1005.65ms; no p99 is claimed.
- NEXT local HTTP throughput: 1MiB 5/5, p50 7.661Mbps; 10MiB 5/5, p50 45.615Mbps.
- NEXT public HTTPS correctness: `https://example.com/` passed 5/5 measured requests after two warmups. This is bounded correctness evidence, not a public Internet speed claim.
- NEXT IPv6 TCP: `fec0::2:18081`, 1MiB, passed 5/5 with p50 7.544Mbps. NEXT IPv6 HTTP: `http://[fec0::2]:18080/bytes?size=4096`, passed 5/5 after two warmups; p50 1000.0ms. These are intermediate candidate checks, not the final release matrix.

The post-run NEXT CDP snapshot in `benchmark-results/20260905T044706Z-next-concurrency/post-run-diagnostics-20260905T045147Z.json` is cumulative after the NEXT concurrency, latency and throughput cases: 234 TCP opens, 233 closes, nine forwarding errors and `lastFailureReason=connection-capacity`, with zero packet read/write errors. The fixed runner now records `productionDiagnosticsAfter` after each timed case and captures `startedAt` before launch; older CURRENT records retain their original pre-run-only diagnostics and are not relabeled.

The approximately one-second 4KiB latency is not evidence of a VPN speed win. The helper starts its timer before URL connection, response and body reads and logs elapsed time before `disconnect()` in `finally`; it has no one-second delay. Host loopback to the fixture was sub-millisecond, while the emulator's selected Wi-Fi network advertised 1Mbps link speed, 2Mbps receive speed and 11Mbps maximum. DIRECT and CURRENT were both approximately 1.0s, so the fixed cost is classified as a lab emulator/network-path artifact pending packet-level confirmation.

The benchmark summarizer now loads both sample and batch records, computes batch aggregate throughput from successfully completed payload bytes divided by batch wall time, and preserves failed-batch counts. The MTU parser is anchored to the interface `MTU:` field rather than route `mtu 0`; derived case/run metadata was regenerated from saved active-link strings while 60 raw files remained byte-identical. Tool regressions cover both fixes.

## TCP candidate independent build gate

`benchmark-results/aegis/final-tcp-20260905T050333Z/raw-console.log` completed successfully in 3m1s: 98 tasks, 28 executed. Parent independently parsed 51 suites / 626 tests, zero failure/error/skip. New `TurboTcpLifecycleTest` has seven passing tests including 32-flow batches, immutable SYN acknowledgement, guarded tuple reuse/stale reset, half-close, wraparound, reset emission ordering and reset-vs-detach. Final Kotlin checksum class SHA remains `a60cc9f2f110cf7bc576fcd1d19c9027b57acf8ff7af2b9031415a46e7e41a29`, identical to the measured R1 candidate.

The code keeps 64 active flows, at most 256 compact terminal tombstones, and a bounded legacy fallback when terminal retention is full. Reset/close marking is serialized before replacement publication, cleanup runs after ownership is settled, and obsolete connection generations cannot publish tombstones. This is a narrow TCP lifecycle correction, not a native engine or new protocol.

New debug SHA-256: `20465af20496300ebc4cd1b260b0da32b795509a030f55c8738976aea559d3fc`; unsigned release: `bdd75d9967a96694ebc8bd197ee0872c40e2e2207fa6028f3a1629c8d2e3bbde`; AAB: `b1802f78699939f85dc37b856f06528112c231b256a0a4dfc4dc723274ed43d7`. Debug artifact is copied into the gate directory. Device validation of this exact candidate is pending; earlier b2df results are intermediate evidence only.

## Final measured decision and handoff

KEEP the TCP correction: final 20465 debug passed the same 32-way workload 160/160 with zero capacity errors (`20260905T050854Z-next-concurrency`), then full 1/8/32 loads passed 5/5, 40/40 and 160/160 (`20260905T050947Z-next-concurrency`). The two 32-way runs total 320/320; this is not 320 independent device sessions. Terminal records expired to zero under the same connection generation while one expected background flow remained. Final latency run `051340`, throughput `051429` and public HTTPS `051503` are version-separated from intermediate b2df runs. Public HTTPS passed 30/30 after ten warmups. Actual lifecycle twenty cycles and stop/start burst passed, final generation43 connected.

Single-flow throughput is broadly similar; 10MiB NEXT sample median is about 2.3% below DIRECT and 1.1% below CURRENT. Do not claim a universal Internet speedup. The 32-way observed PSS peak increased by about 7.1MiB and peak threads by eight while failed flows fell from nine to zero; no memory/battery-saving claim is made. Host exact-checksum component improvement remains separately measured at median 1.56–1.61x.

Validation release `9a36090f6e597102039fae5624b8fe615ab496fb12743828965099c1b797a3cb` passed v2/v3 signing and 16KiB zip alignment. Parent independently hashed the on-device APK and checked VPN owner `com.tunnelvpn.app` and Turbo ACTIVE/operational/gen4. Release matrix passed 15/15 protocol checks across MTU32768/Turbo-off, MTU4096/Turbo-off and MTU4096/Turbo-active: IPv6 HTTP/TCP1MiB, IPv4 UDP60000, IPv6 UDP1200 and public HTTPS. Raw logs and exact payload hashes are under `tools/verification-results/2026-09-05-final-release-9a36090f/`. The legacy duplicateServiceDetected flag also marks ordinary prior-engine replacement and is retained, not presented as proof of duplicate live services. Userdebug WebView limitations and physical carrier/NAT64/OEM/soak tests remain unproven.

Final source/build/tools archive SHA-256: `ff4b82bdb23ae8e7010e3ef8518df23a29ff33167cabf357a6410ba811c39412`; tar comparison matched current files. Final authored-source comments were removed by Luna with functional/legal directives preserved; docs remain. Final independent audit matched paths, 626 tests, metric tables, APK hashes and limitations. Root README links the detailed Korean handoff.

After verification the crash buffer was empty, the owned emulator and fixture were gracefully stopped, forwarding18333 removed, all owned test ports absent, and MemAvailable was 12,484,824KiB. ADB server and unrelated user processes were not killed. All agents were closed. Evidence, including failures and intermediate APKs, remains preserved. No production key or external store publication was used.
