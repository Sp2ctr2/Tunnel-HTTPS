# Contributing

Thank you for taking the time to improve Tunnel HTTPS.

## Before opening a change

- Explain the user-visible or engineering problem being solved.
- Keep networking behavior changes small and focused.
- Do not include private domains, browsing history, packet captures, tokens, keys, crash dumps, or machine-specific paths.
- Do not add analytics or remote telemetry without an explicit privacy review.
- Do not describe an unverified behavior as a supported protocol feature.

## Android and networking changes

Changes to VpnService lifecycle, packet parsing, DNS validation, TLS handling, TCP/UDP forwarding, the WebView bridge, or resource limits should include focused tests or a reproducible explanation of why a test is not practical.

When changing the UI or localization:

- keep the default Korean resources intact unless the change is intentionally Korean-specific;
- add or update the English resources and WebView translations;
- check long English labels, narrow screens, accessibility labels, and touch targets;
- avoid embedding user-visible strings in code when a resource or locale map is appropriate.

## Pull requests

Describe:

1. the behavior changed;
2. the files and surfaces affected;
3. the evidence used to evaluate the change;
4. known limitations or device-specific risks.

Reviewers may request a smaller patch when a change mixes productization, protocol behavior, and unrelated cleanup.

## License

This repository does not yet include a project license. Until the owner adds one, contributions are accepted for review but are not automatically granted permission for reuse or redistribution.
