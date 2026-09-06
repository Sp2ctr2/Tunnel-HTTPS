# Physical device execution matrix

Status: NOT-YET-PROVEN. No physical device result is inferred from the emulator.

## Reproducible setup

Record APK SHA-256, device model, Android build fingerprint/type, WebView version, VPN MTU, battery mode, radio/network type, test client version and fixture version. Store carrier name only in the manually entered lab report; do not collect SSID/BSSID, browsing history or packet bodies. Install the same candidate for all scenarios. Compare DIRECT, preserved CURRENT and NEXT in alternating order. Preserve all failures and raw timings.

| Device group | Required environment | Status |
|---|---|---|
| Samsung | production `user` OS, supported Android version | Pending hardware |
| Pixel | production `user` OS if available | Pending hardware |
| Other OEM | production `user` OS if available | Pending hardware |

## Scenarios and acceptance

| Scenario | Procedure | Required evidence |
|---|---|---|
| Consent and start | Decline once, accept once, grant Android VPN permission | Decline has no TUN; acceptance gives candidate UID VPN ownership |
| Dual stack | IPv4/IPv6 HTTP/TCP 1MiB and 10MiB, bounded UDP 1200/4096/60000 bytes | Byte count and SHA-256, selected interface, family, MTU; compare any failure with DIRECT |
| LTE/5G | Repeat on each available radio connection | Raw latency/goodput/resource samples; do not infer modem energy from emulator CPU |
| NAT64/464XLAT | Use a confirmed network; record prefix and CLAT interface presence as lab metadata | A/AAAA resolution, synthesized addresses, public HTTPS, no stale prefix after switch |
| Handoff | 20 Wi-Fi/cellular transitions during controlled traffic | Timestamped generations, rejection of stale results, fresh request success/recovery interval |
| Sleep/wake | Screen off 15 minutes then wake and make fresh requests | Service ownership, fresh encrypted DNS/TLS request, no duplicate service |
| Battery saver | Toggle system battery saver and app battery setting separately | Settings recorded; MTU profile preserved; bounded UDP and TCP success |
| Process death | Stop process through Android test tooling, reopen/reconnect | No stale mapping or instance publication, correct consent/persistence state |
| Soak | 2h, then 6h, then 12h with bounded periodic controlled transfers | PSS/threads/FDs trend, crashes, failures, reconnects, battery change with matched DIRECT control |
| WebView boundary | Release non-debuggable; inspect DevTools on `user` OS | No release DevTools endpoint; asset navigation/bridge restrictions remain |

## Sampling

Latency: ten warmups, thirty to one hundred measured trials, report p50/p95 and every failure. Report p99 only at adequate sample count and label estimator. Throughput: at least five repetitions per size and mode, median and range. Run 1/8/32 concurrent flows; attempt 128 only after lower levels remain bounded. Abort a load escalation on memory pressure, persistent failures or device thermal throttling and retain that event in the report.

## Result record

Each record carries `status`, `apk_sha256`, `device`, `scenario`, `mode`, `start_utc`, `end_utc`, `samples_file`, `failure_count`, `notes`. Allowed statuses: PHYSICAL-DEVICE-PROVEN, FAILED, UNAVAILABLE, NOT-YET-PROVEN. Empty results are not passes.
