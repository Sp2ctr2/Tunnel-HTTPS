# Community publication tools

This directory contains the one-time recovery and publication tooling for the first public beta. It does not change the Android networking implementation or create another signing key.

## Verification boundaries

The workflow verifies the previously built APK against a pinned SHA-256, the existing signing certificate, package identity and version. It then checks installation and three cold starts on an Android 15 emulator. These checks do not establish real-device, carrier, VPN throughput, handover or long-run reliability.

The website is recovered from an exact-checksum CI artifact through an explicit path allowlist. Current English and Korean launch articles are rendered into static HTML with raw HTML disabled. Documentation checks, responsive browser cases, Content Security Policy checks and positive/negative local APK hash checks run before publication.

The public PEM certificate is stored with normalized LF line endings. Its X.509 DER fingerprint is unchanged; the release's original certificate asset and APK are not rewritten.

## Publication behavior

Only documentation and website test files are staged. The workflow refuses to push if main changed during verification. It publishes the already signed beta as a prerelease, downloads it without authentication, compares its hash and deploys the tested Pages artifact.

The public articles and review invitation are first-party project announcements, not independent endorsements. External social posting requires an authorized connected account. Do not treat a prepared post or rejected API request as a completed publication.

After this one-time launch, maintain the static website directly. The seed artifact is temporary CI storage, not a permanent build dependency for the Android application.
