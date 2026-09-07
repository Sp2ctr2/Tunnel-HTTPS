# Product-first website

The maintained site lives in `website/product/`. The prior Conduit/GPU renderer has been removed, not overlaid with more CSS.

## Design

The first screen presents the actual Android interface. There are no abstract hero objects, decorative network shapes, background grids, animated statistics, or repeated feature-card decks. White, charcoal, neutral surfaces and restrained amber references to the real app replace the prior cobalt identity. The optional dark theme uses neutral grays.

The app screen selector and full-screen image viewer are real controls. Screenshots come from `app/src/main/assets/index.html`, rendered disconnected without a native bridge. The page labels them as source-interface previews, not live traffic or Android acceptance tests.

Technical content has its own reading view: native hash links, keyboard-selectable topics, explicit implementation/platform boundaries, source links, and a searchable support table. Installation has a local SHA-256 tool, validation states and collapsed developer commands. A matching checksum is not Android signature validation.

## Navigation

Pages have ordinary HTML addresses and native anchors. Cross-document View Transitions add a short optical dissolve where supported; no sideward page motion, swipe requirement, SPA route interception or scroll hijacking is used. Native back/forward, modifier clicks and direct URLs keep working. Reduced motion disables transitions. English and Korean each have four routes.

## Build

The single maintained `.github/workflows/pages.yml` builds from this directory and captures the unmodified app UI. It tests the resulting artifact, deploys that exact artifact on main, and verifies normal public URLs anonymously. Branch and pull-request runs test without deployment.

```sh
python3 website/product/build.py --output _site --resolve-release
python3 website/product/capture.py --output _site/media
python3 website/product/test_site.py --root _site --out product-review
```

Capture and browser tests require Playwright with Chromium. Accessibility tests use axe when `AXE_PATH` points at axe.min.js. Screenshots include `media/provenance.json` with the app-source checksum and capture scope.

Public release lookup is unauthenticated, excludes drafts and unpublished metadata, and accepts only this repository's release URLs. No API token enters the website. A missing public APK produces a Releases/source-build path, not a fabricated download button.

CSS and JavaScript filenames are content-hashed. The browser loads no external fonts, analytics, runtime packages or remote scripts. The checksum file never uploads. No-JavaScript pages remain readable and navigable; the browser-only checksum tool is disabled with a local-tool alternative.

## References reviewed

- anthropics/skills: frontend-design guidance, subject-led design rather than stock visual templates.
- vercel-labs/web-interface-guidelines: native semantics, keyboard access, readable forms, focus and reduced motion.
- vercel-labs/open-agents: web-animation-design guidance on purposeful, short interaction feedback.
- MDN View Transition API: progressively enhanced native page navigation.
- Signal and Tailscale Android download pages: product-centered installation hierarchy; no copied assets or markup.

These are design references, not runtime dependencies. Android source, package configuration and signing material are outside the website deployment's scope.
