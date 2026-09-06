# Google Play Data Safety draft

This is a repository-grounded draft, not a submitted declaration.

- Data processed only on device: raw packet metadata, SNI, installed-app information, settings, aggregate diagnostics.
- Data transmitted for core functionality: DNS query contents and network metadata to configured DoH providers; ordinary connection metadata to destination services and reachability endpoints.
- Sharing for advertising/monetization: none found.
- Analytics/crash SDK collection: none found.
- Encryption in transit: DoH and reachability requests use TLS; destination application traffic retains its original protocol. The app does not add a remote full-traffic encryption tunnel.
- Deletion: settings can be removed by clearing app data/uninstalling; in-memory caches end with process/engine lifecycle. Provider deletion behavior is unknown.

## Proposed form treatment

Google’s form says ephemeral off-device processing must still be considered in form answers even when it may not be displayed publicly. Therefore DNS queries, domains, and IP/network metadata require explicit Play Console review. `REQUIRES OWNER INPUT`: whether any external provider is a service provider under the developer’s contract, provider retention, developer access to provider logs, target audience, and any behavior outside this repository.

Official basis: [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).
