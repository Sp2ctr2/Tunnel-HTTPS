# Website maintenance

The public GitHub Pages website is generated from `tools/experience/`. The historical single-page `docs/index.html` is not the publication source anymore. The Pages workflow builds an isolated `_site/` directory and deploys that directory only after its checks pass.

## Routes

| English | Korean | Purpose |
| --- | --- | --- |
| `/` | `/ko/` | Product overview and illustrative CSS tunnel |
| `/engineering/` | `/ko/engineering/` | Interactive DNS, TCP, TLS and QUIC explanation |
| `/guide/` | `/ko/guide/` | Release availability, build instructions and local checksum comparison |
| `/privacy/` | `/ko/privacy/` | Data boundaries, limitations and reporting guidance |

The paths are relative to the repository Pages base `/Tunnel-HTTPS/`. A language change keeps the current page. Ordinary links work without JavaScript, and there is no client-side router to break browser history or direct page loading.

## Source and build

`build.py` renders the HTML using the Python standard library. It requires Python 3.12 or newer. `site.css` owns the design system, responsive layouts and progressive view transitions. `boot.js` applies optional saved preferences before paint. `site.js` enhances the pre-rendered pages.

```sh
python3 tools/experience/build.py --output _site
python3 tools/experience/test_site.py --root _site --static-only
python3 -m unittest discover -s tools/experience -p test_build.py -v
python3 -m http.server 8000 --directory _site
```

For browser checks, install the pinned Playwright version from the Pages workflow and its Chromium/WebKit browsers, then run `test_site.py` without `--static-only`. The workflow also supplies axe-core for automated accessibility checks. These checks are scoped to the website, not the Android network engine or physical-device compatibility.

## Progressive interactions

The explorer reads its content from pre-rendered protocol articles. Tabs, stages, source links, and the inspector update locally. Without JavaScript, those articles remain readable. The animated arch and explorer are illustrations, not live traffic, a connected VPN dashboard, or benchmark results.

Search is a local page index inside a native dialog. It supports Ctrl/Cmd+K, arrow keys, Enter and Escape. Theme and motion preferences are optional localStorage values. System reduced-motion preferences take precedence. View transitions enhance supported browsers without intercepting navigation.

The optional SHA-256 utility accepts a user-selected file of at most 64 MiB and a user-supplied trusted checksum. It never uploads the file. A checksum match is not an APK-signature check, a security assessment or proof that the checksum source is trustworthy. Input values must never be inserted as HTML.

## Release availability

The build reads GitHub release metadata, filters out drafts and unpublished entries, and requires an uploaded APK before presenting a published release. The site does not link to `/releases/latest`, which is not a reliable beta-discovery mechanism. Without an eligible release, the guide states that APK publication is pending and links to source-build instructions and the Releases page.

The browser does not query GitHub APIs. Re-run the Pages workflow on `main` after publishing a release to refresh the guide.

## Deployment and security

The Pages workflow tests the branch before publication. Production publication runs only from `main`, using a separate deployment job. The tested generated artifact is the artifact that gets published. A post-deployment job verifies the exact source revision over unauthenticated HTTPS and repeats browser checks against the public site.

There are no third-party runtime scripts, externally loaded fonts, analytics, forms or upload endpoints. The Content Security Policy disallows inline scripts, eval, outgoing fetch requests, objects, frames and form submissions. This reduces attack surface; it is not a claim that abuse or all vulnerabilities are impossible. GitHub hosting can still process normal request logs.

Keep signing keys, credentials and private user traffic out of site source, test fixtures and uploaded test artifacts.
