# Release checklist

> Historical checklist: checked items and test counts below describe an earlier snapshot, not a new release approval. Current build/device evidence is recorded in [2026-09-05 implementation and verification](IMPLEMENTATION_VERIFICATION_2026-09-05.md). Production signing and physical-device gates remain separate.

## Code and artifacts

- [x] Clean debug build.
- [x] 544 JVM tests in 44 suites pass with no failures, errors, or skips.
- [x] Lint completes with no errors.
- [x] R8 release APK and AAB generation completes.
- [x] Release manifest, permissions, cleartext, backup and native inventory inspected.
- [ ] Release AAB signed with the existing upload key and certificate verified.
- [ ] Signed artifact SHA-256 recorded.
- [ ] Signed build uploaded only to internal testing first.

## Security and network

- [x] Upstream socket protection paths audited.
- [x] DNS overload has explicit SERVFAIL.
- [x] Browser-only empty discovery fails safely.
- [x] WebView external resource boundary and input limits hardened.
- [x] IPv4/dual-stack/IPv6-only/NAT64 local protocol, route-space, fuzz, and loopback gates pass.
- [ ] IPv4/dual-stack/IPv6-only/NAT64 physical-device tests pass.
- [x] Deterministic TCP retransmission, ACK/wrap, zero-window, FIN reserve, RST/TUN-failure, and handover-cleanup tests pass.
- [ ] Physical-device TCP loss/reordering calibration passes.
- [ ] UDP load/oversize/non-QUIC-443/game IPv4+IPv6 tests pass.
- [ ] Rapid lifecycle/handover/blocked-read/FD leak matrix passes.
- [ ] DoH UI contract is reconciled with fail-closed runtime behavior.

## Privacy and policy

- [ ] Organization Play account and D-U-N-S/identity status confirmed.
- [ ] Owner/provider facts complete.
- [ ] Prominent VpnService disclosure and affirmative consent implemented/tested.
- [ ] Hosted privacy policy legally reviewed and published.
- [ ] Data Safety, VpnService and FGS declarations submitted accurately.
- [ ] Demonstration videos produced from signed release candidate.
- [ ] Store claims/assets reviewed; no remote-VPN/anonymity/AI/zero-leak claims.
- [ ] Closed-test requirement checked and completed if applicable.

## Decision gate

- [x] No known P0 issue remains in the locally testable code path.
- [x] No known security-critical P1 issue remains in the locally testable code path.
- [ ] Manual critical matrix is complete.
- [ ] Owner signs the production risk acceptance.

Current gate: **NO-GO — RELEASE BLOCKED**.
