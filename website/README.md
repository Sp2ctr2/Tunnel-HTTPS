# Conduit — Tunnel HTTPS website

The public website is generated from this directory, not from `docs/index.html` or the retired `tools/web2026` design. The Android app and its signing identity are outside this change.

## Design direction

A light, cobalt-blue product showroom built around one subject-specific object: a hollow conduit that echoes the tunnel opening. The object is an original interactive Canvas renderer. It is not live network telemetry or a screenshot of the Android app. The rest of the composition is deliberately quiet: short descriptions, direct links and four distinct scenes. No acid-green dashboard, orbiting chip, badge wall, decorative counters or repeated feature-card grid.

The primary views are Product, Inside, Get Tunnel and Boundaries. Each has an English and Korean URL. Navigation is a two-phase shutter transition, not a disguised scroll or wheel hijack. Keyboard focus, browser history, reduced motion, direct links and no-JavaScript content are preserved.

## References reviewed

- Anthropic frontend-design skill: https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md — read revision `a5333457c414d20d625f307df945842c0952ecc3`. The design follows subject-specific visual identity, one deliberate focal point, non-templated typography and screenshot-based critique.
- Vercel Web Interface Guidelines: https://github.com/vercel-labs/web-interface-guidelines/blob/main/command.md — read revision `e1e8e3460db7c1440e34642c4f7b885185ca5366`. Used for semantics, focus, touch targets, reduced motion, async feedback and stateful URLs.
- basement.studio's real website: https://github.com/basementstudio/website-2k25 — reviewed its camera-transition implementation to study stateful scene changes and user-triggered spatial response, not to copy its identity or code.
- Swup accessibility and parallel-transition references: https://github.com/swup/a11y-plugin and https://github.com/swup/parallel-plugin — reviewed the separation of scene replacement, focus restoration and transition choreography. Swup is not a runtime dependency.

No downloaded skill scripts or third-party website code run in this project. These were research references. The maintained implementation uses native browser APIs and a standard-library Python builder.

## Build and review

Run from the repository root:

```sh
python3 website/build.py --output _site --resolve-release
python3 -m pip install playwright==1.57.0
python3 -m playwright install chromium
python3 website/test_site.py --root _site
```

`--resolve-release` queries public GitHub metadata without a token. Drafts and unsigned/debug-named artifacts are not advertised. If no public APK is found, the site links to Releases and explicitly labels the source as available instead of inventing a working download. The website does not certify APK signatures.

CSS and JavaScript assets are content-hashed. The only publisher is `.github/workflows/pages.yml`. It builds `_site`, runs 64 layout combinations, 16 accessibility audits and interaction tests, deploys that exact artifact, then compares 13 ordinary public URLs/assets byte-for-byte and exercises the live pages again. Test outputs and screenshots remain Actions artifacts rather than cluttering Git history.

## Accessibility and privacy

Manrope and Noto Sans KR load from Google Fonts with local fallbacks. Font delivery is disclosed on the Boundaries page. There is no analytics, upload endpoint, third-party runtime JavaScript or network-testing widget. CSP permits only the local scripts and the explicit font origins. The SHA-256 calculator reads at most 128 MiB locally. It distinguishes a checksum match from signature verification and malware scanning.

Motion is user-triggered or finite. The conduit redraws only when its orientation changes and stops off-screen or in a hidden tab. Reduced-motion users get immediate scene changes and static object updates.
