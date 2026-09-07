# Tunnel HTTPS workspace

A tab-native product site. The shell, navigation and footer remain stationary; selecting a tab replaces the central pane. It is not a landing page with anchor links to lower sections. On short screens the active pane can scroll, so content is not clipped and the main tabs remain reachable.

Five screens, in English and Korean: Overview, Controls, Engine, Privacy and Get app. Existing `/engineering/`, `/privacy/` and `/guide/` addresses remain valid. `/controls/` is new.

The visual language uses neutral surfaces, dark instrument panels and restrained amber drawn from the existing app. No app screenshots, background geometry, WebGL, fake traffic counters or invented performance charts are used. The connection desk and behavior switches are explicitly labeled as illustrations. They do not control an Android device.

## Interaction and motion

Same-document View Transitions preserve the shell and move the active tab selection while the content changes through a short depth dissolve, not a horizontal swipe. The fallback uses interruptible Web Animations. Last navigation wins, browser history works, modifier-clicks keep native link behavior, and each screen has a real static URL. Arrow keys/Home/End provide roving main tabs and sub-tabs. Reduced motion and a persisted motion preference suppress animation. Walkthrough playback is finite and stops when navigating or hiding the document.

Local search uses static labels and textContent. No remote search or runtime API calls are made. DNS, transport and privacy descriptions point to existing implementation files. Controls are illustrative, while the checksum tool genuinely computes SHA-256 locally. A pasted comparison checksum is user-supplied, not authenticated provenance; a byte match is not signature verification.

## Build and test

```sh
python3 website/workspace/build.py --output _site --resolve-release
python3 website/workspace/test_site.py --root _site --out workspace-review
```

Python standard library builds the site. Playwright/Chromium test the result. Set `AXE_PATH` to an installed `axe.min.js` to require accessibility audits. The Pages workflow always sets it before deployment. Test videos and screenshots are evidence of the website only, never Android/device testing.

Only anonymous, published release metadata is allowed into the generated HTML. No credentials, draft release data or private keys enter the site. Unavailable public APKs produce an honest Releases/source-build path. JavaScript/CSS names are content-hashed. `manifest.json` records the revision and output hashes; after deployment the workflow checks ordinary public URLs against that artifact and repeats browser interactions.

## References

- https://github.com/anthropics/skills/tree/main/skills/frontend-design
- https://github.com/vercel-labs/web-interface-guidelines
- https://github.com/vercel-labs/open-agents/blob/main/.agents/skills/web-animation-design/SKILL.md
- https://developer.mozilla.org/en-US/docs/Web/API/View_Transition_API

These inform interaction and review, not runtime dependencies. Android source, releases and signing material are outside this website change.
