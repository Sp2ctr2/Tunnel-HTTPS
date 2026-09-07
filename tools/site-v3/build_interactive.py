#!/usr/bin/env python3
"""Build the static, dependency-free Tunnel HTTPS interactive website.

All routes are complete HTML documents. Inert templates additionally allow
same-origin navigation without fetching, eval, trackers, or a JavaScript router
package. The Android application and its signing identity are not modified.
"""
from __future__ import annotations

import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / 'docs'
ASSETS = DOCS / 'assets' / 'v3'
BASE = '/Tunnel-HTTPS/'
REPO = 'https://github.com/Sp2ctr2/Tunnel-HTTPS'
SITE = 'https://sp2ctr2.github.io' + BASE
SOURCE = REPO + '/blob/main/app/src/main/java/com/tunnelvpn/app/'
CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; upgrade-insecure-requests"

ICONS = {
    'arrow': '<path d="M5 12h14M13 6l6 6-6 6"/>',
    'external': '<path d="M14 4h6v6M20 4l-9 9"/><path d="M10 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5"/>',
    'download': '<path d="M12 3v12M7 10l5 5 5-5M5 16v4h14v-4"/>',
    'search': '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    'theme': '<path d="M20.8 13A9 9 0 0 1 11 3.2 9 9 0 1 0 20.8 13Z"/>',
    'menu': '<path d="M4 7h16M4 12h16M4 17h16"/>',
    'close': '<path d="m6 6 12 12M18 6 6 18"/>',
    'globe': '<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/>',
    'shield': '<path d="M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6Z"/><path d="m8 12 3 3 5-6"/>',
    'code': '<path d="m8 6-6 6 6 6M16 6l6 6-6 6M14 3l-4 18"/>',
    'layers': '<path d="m12 3 10 5-10 5L2 8Zm-9 10 9 5 9-5M3 17l9 5 9-5"/>',
    'phone': '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 5h4M11 19h2"/>',
    'play': '<path d="m8 4 12 8-12 8Z"/>',
    'previous': '<path d="m15 5-7 7 7 7"/>',
    'next': '<path d="m9 5 7 7-7 7"/>',
    'reset': '<path d="M3 11a9 9 0 1 1 2 7M3 4v7h7"/>',
    'chevron': '<path d="m6 9 6 6 6-6"/>',
    'file': '<path d="M14 2H5v20h14V7Zm0 0v5h5M8 12h8M8 16h8"/>',
    'lock': '<rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/>',
    'signal': '<path d="M4 17v3M9 12v8M14 7v13M19 2v18"/>',
}


def icon(name: str) -> str:
    return '<svg class="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.55" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + ICONS[name] + '</svg>'


def mark() -> str:
    return '<span class="ui-mark" aria-hidden="true"><i></i><i></i><i></i></span>'


def route(path: str, label: str, cls: str = '', arrow: bool = False) -> str:
    return f'<a data-route href="{BASE}{html.escape(path, quote=True)}" class="{cls}">{label}{icon("arrow") if arrow else ""}</a>'


def external(url: str, label: str, cls: str = 'ui-inline-link', glyph: str = 'external') -> str:
    return f'<a href="{html.escape(url, quote=True)}" class="{cls}" target="_blank" rel="noopener noreferrer">{label}{icon(glyph)}</a>'


def accordion(title: str, body: str) -> str:
    return f'<details><summary><span>{title}</span>{icon("chevron")}</summary><div class="ui-details">{body}</div></details>'


def title(eyebrow: str, heading: str, description: str) -> str:
    return f'<div class="ui-wrap ui-page-title"><p class="ui-eyebrow">{eyebrow}</p><h1>{heading}</h1><p>{description}</p></div>'


def section_title(eyebrow: str, heading: str, description: str) -> str:
    return f'<div class="ui-section-title"><div><p class="ui-eyebrow">{eyebrow}</p><h2>{heading}</h2></div><p>{description}</p></div>'


def code_block(identifier: str, label: str, value: str) -> str:
    return f'<div class="ui-code"><div><span>{label}</span><button type="button" data-copy="{identifier}" aria-label="Copy {label}">Copy</button></div><pre><code id="{identifier}">{html.escape(value)}</code></pre></div>'


def release_url() -> str:
    """Accept a public release URL only from the build-time verified snapshot."""
    path = ROOT / 'tools' / 'site-v3' / 'public-release.json'
    if path.is_file():
        data = json.loads(path.read_text(encoding='utf-8'))
        url = data.get('html_url', '')
        pattern = re.escape(REPO) + r'/releases/tag/[A-Za-z0-9._-]+'
        if data.get('verified_public') is True and re.fullmatch(pattern, url):
            return url
    return REPO + '/releases'


def preview_section() -> str:
    """Reuse actual existing app-asset previews, never fabricate device evidence."""
    candidates = []
    for path in sorted((DOCS / 'assets').rglob('*')):
        lower = path.name.lower()
        if path.suffix.lower() not in {'.webp', '.png', '.jpg'}:
            continue
        if not ('preview' in lower and ('app' in lower or 'interface' in lower)):
            continue
        if any(part in lower for part in ('home-', 'overflow', 'social', 'report', 'mobile')):
            continue
        if path.is_file() and path.stat().st_size < 1_500_000:
            candidates.append(path)
    if not candidates:
        return ''
    images = ''.join(f'<img src="{BASE}{p.relative_to(DOCS).as_posix()}" alt="Tunnel HTTPS application interface preview {i + 1}" width="390" height="844" loading="lazy" decoding="async">' for i, p in enumerate(candidates[:2]))
    return f'''<section class="ui-wrap ui-section ui-preview">
<div><p class="ui-eyebrow">04 / The interface</p><h2>Deep underneath.<br><em>Simple in your hand.</em></h2><p>A Korean-first Android interface with English localization, local diagnostics and an explicit VPN permission flow. The implementation stays inspectable beneath the interface.</p>{route('guide/', 'Installation and first connection', 'ui-inline-link', True)}<small>Application-asset previews from the repository. These images are not evidence of a live VPN session or physical-device validation.</small></div><div class="ui-phones">{images}</div></section>'''


def home_page() -> str:
    return f'''<section class="ui-wrap ui-hero">
<div><div class="ui-pill"><span class="ui-dot" aria-hidden="true"></span>Open-source Android networking</div><h1>Network control.<br>On <em>your device.</em></h1><p class="ui-lead">A local engine beneath the connect button. Inspect packets, validate DNS and adapt connections — without a developer-operated remote VPN gateway.</p><div class="ui-buttons">{route('guide/', 'Get started', 'ui-button primary', True)}{route('engineering/', 'Explore the engine', 'ui-button', True)}</div><div class="ui-meta"><span>Android 7+</span><span>Kotlin</span><span>Apache-2.0</span></div></div>
<div class="ui-canvas" aria-label="Interactive architectural illustration">
<div class="ui-canvas-top"><span><span class="ui-dot" aria-hidden="true"></span>Inside the local path</span><span>Illustration · not live traffic</span></div>
<div class="ui-apps" aria-hidden="true"><span>{icon('globe')}</span><span>{icon('phone')}</span><span>{icon('signal')}</span></div><div class="ui-connector" aria-hidden="true"></div>
<div class="ui-engine-object"><div class="ui-object-heading"><span>TUNNEL / HTTPS</span><span>ON-DEVICE ENGINE</span></div><div class="ui-object-body"><span>VPNService / TUN</span><h2 data-home-title>DNS with scrutiny.</h2><p data-home-description>Parse names. Apply policy. Validate responses.</p></div><div class="ui-object-bottom"><span data-home-footer>DNS · DoH · DNS64</span><span>No remote gateway</span></div></div>
<div class="ui-connector" aria-hidden="true"></div><div class="ui-canvas-tabs" aria-label="Choose a protocol illustration"><button type="button" data-home-protocol="dns" aria-pressed="true">DNS<span>Names &amp; policy</span></button><button type="button" data-home-protocol="tcp" aria-pressed="false">TCP / TLS<span>Relay &amp; adaptation</span></button><button type="button" data-home-protocol="quic" aria-pressed="false">UDP / QUIC<span>Guard &amp; fallback</span></button></div>
<a class="ui-canvas-output" data-route data-home-explore href="{BASE}engineering/?protocol=dns#packet-explorer">Follow this path {icon('arrow')}</a><p class="ui-canvas-note">Choose a protocol to explore its role. No traffic is captured by this website.</p></div></section>
<div class="ui-wrap ui-principles"><div>{icon('phone')}<div><h2>On-device by design.</h2><p>Local packet processing and policy.</p></div></div><div>{icon('layers')}<div><h2>Dual-stack at the core.</h2><p>IPv4 and IPv6, with explicit boundaries.</p></div></div><div>{icon('code')}<div><h2>Open to inspection.</h2><p>Source, tests and engineering decisions.</p></div></div></div>
<section class="ui-wrap ui-section">{section_title('01 / Below the surface', 'Not just<br><em>a connect button.</em>', 'Four connected layers, built to be understood. Start with the overview; follow the source when you want the details.')}
<div class="ui-bento">
<a class="ui-feature" data-route href="{BASE}engineering/?protocol=tcp#packet-explorer"><span>01 / PACKET PROCESSING</span><div class="ui-feature-art ui-packet-bars" aria-hidden="true">{''.join('<i></i>' for _ in range(8))}</div><h3>Packets, with boundaries.</h3><p>IPv4 and IPv6 normalization, bounded fragment handling and validation before transport decisions.</p><strong>Explore the packet path {icon('arrow')}</strong></a>
<a class="ui-feature" data-route href="{BASE}engineering/?protocol=dns#packet-explorer"><span>02 / DNS</span><div class="ui-feature-art ui-dns-chips" aria-hidden="true"><span>example.com</span><b>→</b><span>validated answer</span></div><h3>Names deserve scrutiny.</h3><p>Message parsing, response checks, cache policy and encrypted resolver selection are first-class parts of the engine.</p><strong>Follow a DNS request {icon('arrow')}</strong></a>
<a class="ui-feature" data-route href="{BASE}engineering/?protocol=quic#packet-explorer"><span>03 / TRANSPORT</span><div class="ui-feature-art ui-wave" aria-hidden="true"><i></i><i></i><i></i></div><h3>State, all the way down.</h3><p>Local TCP and UDP relay paths with explicit resource ceilings and selected TLS / QUIC handling.</p><strong>Inspect transport boundaries {icon('arrow')}</strong></a>
<a class="ui-feature" data-route href="{BASE}engineering/#learning"><span>04 / LOCAL LEARNING</span><div class="ui-feature-art ui-loop" aria-hidden="true"><span>context</span><i></i><span>strategy</span><i></i><span>outcome</span></div><h3>Adaptive. Accountable.</h3><p>Turbo learns from local outcomes. Aegis evaluates a neural policy in shadow mode, without live routing authority.</p><strong>Understand the learning loop {icon('arrow')}</strong></a>
</div></section>
<section class="ui-boundary"><div class="ui-wrap"><div><p class="ui-eyebrow">02 / A different architecture</p><h2>Your packets do not<br>need <em>our VPN server.</em></h2><p>There isn’t one. Destination services and selected DNS providers still receive the requests sent to them. Local processing is not an anonymity guarantee.</p>{route('privacy/', 'Understand what leaves the device', 'ui-inline-link', True)}</div><div class="ui-boundary-path" aria-label="Your device connects through the underlying network to destination services"><span>Your device</span><i aria-hidden="true"></i><span>Destination</span></div></div></section>
{preview_section()}
<section class="ui-wrap ui-section ui-faq"><div><p class="ui-eyebrow">Before you connect</p><h2>Clear boundaries.<br><em>Better expectations.</em></h2></div><div class="ui-accordion">
{accordion('Will this change my public IP or country?', '<p>No. This is a local networking engine, not a remote exit-node VPN. It does not provide a different exit country or an anonymity guarantee.</p>')}
{accordion('Does it decrypt HTTPS content?', '<p>No. Selected TLS ClientHello metadata can inform connection strategy. That is not HTTPS content decryption or TLS termination.</p>')}
{accordion('Can I use it with another Android VPN?', '<p>Android normally permits one active VpnService per user or profile. Another VpnService-based VPN can replace the active service. Check the installation guide before combining network tools.</p>')}
{accordion('Is this a complete TCP or QUIC implementation?', '<p>No. It implements a bounded local relay and selected protocol handling. The Internet-facing TCP connection uses the platform stack; QUIC handling is deliberately partial.</p>')}
</div></section>
<section class="ui-wrap"><div class="ui-endcap"><p class="ui-eyebrow">Open source / Open for inspection</p><h2>Less black box.<br>More <em>your network.</em></h2><p>Read the source. Understand the boundaries. Start from the official release page.</p><div class="ui-buttons">{route('guide/', 'Install &amp; verify', 'ui-button primary', True)}{external(REPO, 'View source', 'ui-button')}</div><div class="ui-endcap-art" aria-hidden="true"><i></i><i></i><i></i></div></div></section>'''


def capability_rows() -> str:
    rows = [
        ('network', 'IPv4 / IPv6', 'Bounded', 'Packet normalization, fragment handling and selected ICMPv6 behavior. Not a claim of every IP extension or network configuration.', 'Ipv6PacketNormalizer.kt'),
        ('network', 'TCP / UDP relay', 'Local transport', 'TUN-side state and resource budgets. Android / Linux sockets provide the upstream transport implementation.', 'TurboTcpForwarder.kt'),
        ('dns', 'DNS / DoH', 'Validated', 'Message checks, cache policy and resolver selection. The selected DNS provider receives the queries sent to it.', 'DnsMessageValidator.kt'),
        ('dns', 'DNS64 / NAT64', 'Network-dependent', 'Discovery and synthesis are conditional on configuration and support in the underlying network.', 'Dns64Packet.kt'),
        ('network', 'TLS / QUIC', 'Partial', 'ClientHello adaptation and selected QUIC structure checks, not TLS decryption or a complete QUIC stack.', 'QuicHttp3Guard.kt'),
        ('learning', 'Turbo', 'Contextual policy', 'On-device strategy selection and outcome feedback, within the available candidate set and fallback constraints.', 'TurboAiEngine.kt'),
        ('learning', 'Aegis Local 2', 'Shadow only', 'Experimental neural evaluation without authority to take over live routing decisions.', 'AegisLocal2Engine.kt'),
    ]
    return ''.join(f'<article data-category="{category}"><div><h3>{name}</h3><span class="ui-badge">{status}</span></div><p>{description}</p>{external(SOURCE + filename, "Code")}</article>' for category, name, status, description, filename in rows)


def engine_page() -> str:
    nodes = ''.join(f'<button class="ui-step" type="button" data-step="{i}" {"aria-current=step" if i == 0 else ""}><span>0{i + 1}</span><strong>{label}</strong></button>' for i, label in enumerate(['Application', 'VpnService / TUN', 'IP validation', 'DNS / cache', 'Protected DoH', 'DNS answer']))
    return title('Engineering / Interactive walkthrough', 'A packet.<br><em>All the way through.</em>', 'Choose a protocol. Step through the local path. Inspect the boundaries and the source behind each decision.') + f'''
<section class="ui-wrap" id="packet-explorer"><div class="ui-lab"><div class="ui-lab-toolbar"><div class="ui-segmented" role="tablist" aria-label="Protocol walkthrough"><button type="button" role="tab" data-protocol="dns" aria-selected="true" tabindex="0">DNS</button><button type="button" role="tab" data-protocol="tcp" aria-selected="false" tabindex="-1">TCP / TLS</button><button type="button" role="tab" data-protocol="quic" aria-selected="false" tabindex="-1">UDP / QUIC</button></div><span class="ui-lab-label">ARCHITECTURAL ILLUSTRATION · NO LIVE TRAFFIC</span></div>
<div class="ui-lab-body"><div class="ui-lab-track-wrap"><div class="ui-lab-track" aria-label="Select one of six processing steps">{nodes}</div><div class="ui-sidecar"><strong>POLICY IS A SIDECAR, NOT AN EXTRA NETWORK HOP</strong><p>Turbo can inform supported strategy choices. Aegis observes in shadow mode. Neither is a remote VPN server.</p></div></div>
<div class="ui-inspector"><div class="ui-inspector-head"><span>STEP INSPECTOR</span><span data-step-count>01 / 06</span></div><div class="ui-inspector-copy" aria-live="polite" aria-atomic="true"><p class="ui-eyebrow" data-step-owner>Android application</p><h2 data-step-title>An app needs a name</h2><p data-step-description>The application asks the system to resolve a host name. This illustrates the captured DNS path, not every resolver an Android application can use.</p><div class="ui-invariant"><strong data-boundary-title>Capture has a scope</strong><p data-boundary-copy>Applications with their own encrypted DNS may not use this DNS path.</p></div><a data-step-source class="ui-inline-link" href="{SOURCE}LocalProtectionEngine.kt" target="_blank" rel="noopener noreferrer">Inspect the implementation {icon('external')}</a></div>
<div class="ui-lab-controls"><button type="button" class="ui-icon-button" data-prev aria-label="Previous step" disabled>{icon('previous')}</button><button type="button" class="ui-button primary" data-play aria-pressed="false">{icon('play')}<span data-play-label>Play walkthrough</span></button><button type="button" class="ui-icon-button" data-next aria-label="Next step">{icon('next')}</button><button type="button" class="ui-icon-button" data-reset aria-label="Reset walkthrough">{icon('reset')}</button></div><p class="ui-lab-help">Click any step. Use arrow keys within the protocol tabs. Playback starts only when you ask.</p></div></div><div class="ui-progress" aria-hidden="true"><span data-progress="0"></span></div></div><noscript><p>The DNS path above remains readable without JavaScript. Enable JavaScript for protocol selection and the step inspector, or follow the source links below.</p></noscript></section>
<section class="ui-wrap ui-section">{section_title('01 / Know the boundary', 'Built here.<br><em>Provided by the platform.</em>', 'A serious systems project should make this distinction obvious. The local relay and the Internet-facing transport are not the same implementation.')}
<div class="ui-two-column"><article class="ui-info-panel"><h3>Implemented in this repository</h3><ul><li>Packet parsing, validation and normalization</li><li>Bounded fragment and local flow state</li><li>DNS validation, cache policy and resolver strategy</li><li>TLS ClientHello adaptation and QUIC-aware guard logic</li><li>Contextual learning and neural shadow evaluation</li></ul></article><article class="ui-info-panel"><h3>Provided by Android and libraries</h3><ul><li>VpnService and the operating-system TUN interface</li><li>Internet-facing kernel TCP and UDP sockets</li><li>Platform networking and VPN permission controls</li><li>HTTPS transport and TLS cryptography</li><li>The underlying network and external destination services</li></ul></article></div></section>
<section class="ui-wrap ui-section" id="capabilities">{section_title('02 / Implementation scope', 'Source links.<br><em>Not a badge wall.</em>', 'Filter the implementation areas. Each scope statement stays connected to the code, including the parts that are conditional or experimental.')}
<div class="ui-filter" aria-label="Filter capability areas"><button type="button" data-filter="all" aria-pressed="true">All areas</button><button type="button" data-filter="network" aria-pressed="false">Packet &amp; transport</button><button type="button" data-filter="dns" aria-pressed="false">DNS</button><button type="button" data-filter="learning" aria-pressed="false">Learning</button></div><p class="ui-sr-only" role="status" data-filter-count>7 capability areas shown</p><div class="ui-capability-list">{capability_rows()}</div></section>
<section class="ui-wrap ui-section" id="learning">{section_title('03 / Local feedback', 'Adaptive.<br><em>Not unaccountable.</em>', 'A learning policy should have a defined job. Here, the active contextual policy and the experimental neural evaluation remain separate.')}
<div class="ui-two-column"><article class="ui-info-panel"><p class="ui-eyebrow">Turbo / Local contextual policy</p><h3>Context → strategy → outcome.</h3><p>Turbo selects among supported candidate strategies and updates its local policy from observed outcomes. It does not turn a partial protocol implementation into a universal connectivity guarantee.</p>{external(REPO + '/blob/main/docs/TURBO_AI_ARCHITECTURE.md', 'Read the Turbo architecture')}</article><article class="ui-info-panel"><p class="ui-eyebrow">Aegis Local 2 / Experimental</p><h3>Observe before taking control.</h3><p>The neural policy remains shadow-only. It can be evaluated alongside the active path without being granted live routing authority. That distinction is preserved in the code and the public explanation.</p>{external(SOURCE + 'AegisLocal2Engine.kt', 'Inspect the shadow boundary')}</article></div></section>'''


def guide_page() -> str:
    return title('Getting started / Android', 'Install with confidence.<br><em>Know what you run.</em>', 'Start from the official release page. Review the version and its limits, then use Android’s normal installation and VPN permission flow.') + f'''
<section class="ui-wrap"><div class="ui-install-panel"><div class="ui-package" aria-hidden="true">{mark()}</div><div><p class="ui-eyebrow">Canonical distribution / GitHub Releases</p><h2>One official starting point.</h2><p>Download only an APK attached to an official release. Check its release notes and verification information. A source archive is not an installable APK.</p><div class="ui-buttons">{external(release_url(), 'Open official releases', 'ui-button primary', 'download')}{external(REPO, 'View source', 'ui-button')}</div><small>Use the files actually listed on the release page. If no APK is attached, there is no installable release available there.</small></div></div></section>
<section class="ui-wrap ui-setup" aria-label="Installation steps"><article><span>01</span><div><h2>Choose the right artifact.</h2><p>Read the release notes before installing. Prefer the official APK, and compare the file checksum with the release information. Do not use repackaged binaries from unknown mirrors.</p></div></article><article><span>02</span><div><h2>Install through Android.</h2><p>Open the APK on your Android device. Android may ask you to allow this specific installation source. You do not need to disable global device-security protections or remove unrelated security controls.</p></div></article><article><span>03</span><div><h2>Read. Consent. Connect.</h2><p>Read the in-app disclosure and approve Android’s VPN prompt when requested. Another VpnService-based VPN may conflict. Test a familiar connection first; stop the service if connectivity becomes unreliable.</p></div></article></section>
<section class="ui-wrap ui-section ui-verify" id="verify"><div><p class="ui-eyebrow">Local verification / Web Crypto</p><h2>Your APK.<br><em>Your browser only.</em></h2><p>Compute the selected file’s SHA-256 locally. Nothing is uploaded. Compare it with a checksum you obtained from a trusted release source.</p><small>A matching hash compares file bytes. It does not independently establish who signed the APK or whether the source of the expected hash is trustworthy.</small></div><div class="ui-hash-tool"><label class="ui-file-picker" for="apk-file">{icon('file')}<strong>Choose an APK to verify</strong><span>Local only · non-empty APK · up to 128 MiB</span><input id="apk-file" type="file" accept=".apk,application/vnd.android.package-archive"></label><p class="ui-hash-status" id="hash-status" role="status" aria-live="polite" data-state="idle">Your file stays in this browser. Nothing is uploaded.</p><div class="ui-hash-result" hidden><label for="hash-output">Computed SHA-256</label><textarea id="hash-output" rows="3" readonly spellcheck="false"></textarea><label for="expected-hash">Expected SHA-256 from a trusted release source</label><input id="expected-hash" type="text" maxlength="128" placeholder="Paste the complete 64-character checksum" autocomplete="off" spellcheck="false"><button class="ui-button" type="button" data-copy="hash-output">Copy checksum</button></div><noscript><p>JavaScript is required for the local checksum tool. The command-line instructions below work without it.</p></noscript></div></section>
<section class="ui-wrap ui-section" id="developer">{section_title('For contributors', 'Commands when<br><em>you need them.</em>', 'The normal installation path stays simple. Build, install and signature-inspection commands are available below, without cluttering the overview.')}
<div class="ui-accordion">
{accordion('Build and run the local checks', '<p>From the repository root, use JDK 17 and the Android SDK required by the checked-in Gradle configuration. Keep local configuration and credentials out of Git.</p>' + code_block('build-commands', 'Gradle / repository root', './gradlew :app:assembleDebug\n./gradlew :app:testDebugUnitTest :app:lintDebug') + '<p>The debug APK is written to <code>app/build/outputs/apk/debug/app-debug.apk</code>. These commands produce a development build, not an official signed release.</p>')}
{accordion('Install a locally built debug APK with ADB', '<p>Connect an authorized Android device or emulator, then run from the repository root.</p>' + code_block('adb-command', 'ADB / local development', 'adb install -r app/build/outputs/apk/debug/app-debug.apk') + '<p>Do not uninstall an existing release merely to bypass a signature mismatch without first understanding the data-loss implications.</p>')}
{accordion('Inspect the checksum and signing certificate', '<p>For a downloaded release, replace <code>release.apk</code> below with the actual file name. <code>apksigner</code> is provided by Android SDK Build Tools. Compare the public certificate fingerprint with a trusted release record.</p>' + code_block('verify-commands', 'Linux / Android SDK Build Tools', 'sha256sum release.apk\napksigner verify --verbose --print-certs release.apk') + '<p>On macOS, use <code>shasum -a 256 release.apk</code> for the file checksum. A checksum and a signing certificate answer different questions; inspect both when verifying an update.</p>')}
{accordion('Recover from an installation or connection problem', '<p>For a signature mismatch, verify that both versions come from the same signing identity. Do not assume that deleting the existing app is harmless.</p><p>For a connection problem, stop Tunnel HTTPS, confirm the underlying network works, then inspect configuration and VPN conflicts. Report a sanitized reproduction without browsing history, private domains, tokens or packet captures.</p>' + external(REPO + '/issues', 'Report an ordinary bug'))}
</div></section>'''


def trust_page() -> str:
    return title('Trust / Clear data boundaries', 'Local by design.<br><em>Honest by default.</em>', 'An on-device engine still communicates with the network. Know which decisions stay local, which endpoints receive data, and what the website does not do.') + f'''
<section class="ui-wrap"><div class="ui-trust-panel"><div class="ui-segmented" role="tablist" aria-label="Explore a data boundary"><button type="button" role="tab" data-trust="device" aria-selected="true" tabindex="0">On your device</button><button type="button" role="tab" data-trust="network" aria-selected="false" tabindex="-1">On the network</button><button type="button" role="tab" data-trust="website" aria-selected="false" tabindex="-1">On this website</button></div><div class="ui-trust-content"><div class="ui-trust-art" aria-hidden="true"><span>{icon('shield')}</span></div><div aria-live="polite" aria-atomic="true"><p class="ui-eyebrow" data-trust-eyebrow>01 / On the device</p><h2 data-trust-title>Keep the decision close.</h2><p data-trust-copy>Packet metadata and selected protocol information are processed by the local engine. Turbo learns locally; Aegis is an experimental shadow policy without live routing authority.</p><a class="ui-inline-link" data-trust-link href="{REPO}/blob/main/docs/TURBO_AI_ARCHITECTURE.md" target="_blank" rel="noopener noreferrer"><span>Review the local architecture</span>{icon('external')}</a></div></div></div><noscript><p>Network boundary: destination services receive your traffic, and a selected DoH provider receives its DNS queries. Website boundary: this site does not capture traffic or upload APKs; normal hosting requests are handled by GitHub.</p></noscript></section>
<section class="ui-wrap ui-section">{section_title('01 / What this does not promise', 'Clarity is<br><em>part of the product.</em>', 'These are architectural boundaries, not fine print hidden behind a download button.')}
<div class="ui-two-column"><article class="ui-info-panel"><h3>No exit-country promise.</h3><p>This architecture does not provide remote exit locations or automatically change your public IP. Destination services still receive traffic from the underlying network.</p></article><article class="ui-info-panel"><h3>No HTTPS decryption.</h3><p>ClientHello metadata handling is distinct from decrypting application content. Tunnel HTTPS is not presented as an HTTPS interception or content-decryption product.</p></article><article class="ui-info-panel"><h3>No invisible DNS provider.</h3><p>DoH encrypts the transport to a resolver. The chosen provider still receives the DNS query and related connection metadata.</p></article><article class="ui-info-panel"><h3>No borrowed evidence.</h3><p>Source checks, unit tests, website tests and physical-device network validation are different evidence. A polished diagram is not a benchmark or proof of network compatibility.</p></article></div></section>
<section class="ui-wrap ui-section">{section_title('02 / This website', 'No packet capture.<br><em>No file upload.</em>', 'The interactive explorer is a local illustration. It is intentionally not a remote diagnostic endpoint or network testing service.')}
<div class="ui-two-column"><article class="ui-info-panel"><h3>Small, explicit browser state.</h3><ul><li>Theme preference may be stored locally in your browser.</li><li>Search filters a built-in index; it does not send queries.</li><li>Protocol walkthroughs use fixed explanatory scenarios.</li><li>APK checksums use Web Crypto on the selected local file.</li></ul></article><article class="ui-info-panel"><h3>Hosting is still a network service.</h3><ul><li>GitHub Pages receives normal page and asset requests.</li><li>Opening a repository or release link makes a request to GitHub.</li><li>External destinations have their own service policies.</li><li>The site does not add third-party fonts, analytics or trackers.</li></ul></article></div></section>
<section class="ui-wrap ui-section ui-faq"><div><p class="ui-eyebrow">03 / Responsible reporting</p><h2>Found a problem?<br><em>Report it safely.</em></h2></div><div class="ui-accordion">{accordion('Security vulnerabilities', '<p>Follow the repository’s security policy. Do not publish exploitable details or sensitive traffic in a public issue. Use synthetic test data and the minimum reproduction needed to explain the impact.</p>' + external(REPO + '/blob/main/SECURITY.md', 'Read SECURITY.md'))}{accordion('Ordinary bugs and compatibility problems', '<p>Include the version, Android version, device class and sanitized reproduction steps. Remove private domains, browsing history, tokens and signing material before sharing logs.</p>' + external(REPO + '/issues', 'Open the issue tracker'))}{accordion('Use and contribution boundaries', '<p>Use the software on devices and networks where you have authorization. No description, license statement or interface can guarantee that all misuse of open source is impossible.</p><p>Changes to packet parsing, networking or credentials need implementation review and focused tests, not just updated marketing copy.</p>' + external(REPO + '/blob/main/CONTRIBUTING.md', 'Contribution guide'))}</div></section>'''


def header(key: str) -> str:
    def nav_link(k: str, path: str, label: str) -> str:
        current = ' aria-current="page"' if key == k else ''
        return f'<a data-route data-nav="{k}" href="{BASE}{path}"{current}>{label}</a>'
    links = ''.join(nav_link(k, p, label) for k, p, label in [('home', '', 'Overview'), ('engine', 'engineering/', 'The engine'), ('guide', 'guide/', 'Install'), ('trust', 'privacy/', 'Trust')])
    return f'''<a class="ui-skip" href="#main">Skip to content</a><header class="ui-header"><div class="ui-nav"><a class="ui-brand" data-route href="{BASE}" aria-label="Tunnel HTTPS home">{mark()}<span>tunnel / https</span><small>ON DEVICE</small></a><nav class="ui-navlinks" aria-label="Primary navigation">{links}</nav><div class="ui-actions"><button class="ui-icon-button ui-search-trigger" type="button" data-open-search aria-label="Search website" aria-haspopup="dialog">{icon('search')}<kbd>⌘ K</kbd></button><button class="ui-icon-button ui-theme" type="button" data-theme-toggle aria-label="Switch color theme" aria-pressed="false">{icon('theme')}</button><a class="ui-github" href="{REPO}" target="_blank" rel="noopener noreferrer">GitHub ↗</a><button class="ui-icon-button ui-menu-trigger" type="button" data-menu-toggle aria-label="Open navigation menu" aria-expanded="false" aria-controls="mobile-navigation">{icon('menu')}</button></div></div><nav class="ui-mobile-nav" id="mobile-navigation" aria-label="Mobile navigation" hidden>{links}</nav></header>'''


def footer() -> str:
    korean = f'<a href="{REPO}/blob/main/README.ko.md" target="_blank" rel="noopener noreferrer" lang="ko">한국어 README ↗</a>' if (ROOT / 'README.ko.md').is_file() else ''
    return f'''<footer class="ui-wrap ui-footer"><div class="ui-footer-top"><div><a class="ui-brand" data-route href="{BASE}">{mark()}<span>tunnel / https</span></a><p>On-device Android networking.<br>Open to inspection.</p></div><div><h2>Explore</h2>{route('engineering/', 'Inside the engine')}{route('guide/', 'Install &amp; verify')}{route('privacy/', 'Privacy boundaries')}</div><div><h2>Project</h2><a href="{REPO}" target="_blank" rel="noopener noreferrer">Source code ↗</a><a href="{release_url()}" target="_blank" rel="noopener noreferrer">Official releases ↗</a><a href="{REPO}/issues" target="_blank" rel="noopener noreferrer">Issues ↗</a></div><div><h2>Built in the open</h2><a href="{REPO}/blob/main/SECURITY.md" target="_blank" rel="noopener noreferrer">Security policy ↗</a><a href="{REPO}/blob/main/LICENSE" target="_blank" rel="noopener noreferrer">Apache-2.0 ↗</a>{korean}</div></div><div class="ui-footer-bottom"><span>Tunnel HTTPS · Apache-2.0</span><span>Local control. Clear boundaries.</span><button type="button" data-top>Back to top ↑</button></div></footer>
<dialog class="ui-search-dialog" id="site-search" aria-labelledby="search-title"><div class="ui-search-heading"><h2 id="search-title">Find your way.</h2><button class="ui-icon-button" type="button" data-close-search aria-label="Close search">{icon('close')}</button></div><div class="ui-search-field">{icon('search')}<label class="ui-sr-only" for="search-input">Search website pages</label><input id="search-input" type="search" placeholder="Try DNS, install, privacy…" autocomplete="off" maxlength="120" role="combobox" aria-expanded="true" aria-controls="search-results" aria-autocomplete="list"></div><div class="ui-search-results" id="search-results" role="listbox" aria-label="Matching pages"></div><p class="ui-search-hint"><span>↑ ↓ to choose · Enter to open · Esc to close</span><span id="search-count" role="status">Local search</span></p></dialog><p class="ui-toast" id="site-toast" role="status" hidden></p><p class="ui-sr-only" id="route-announcement" role="status" aria-live="polite"></p>'''


def main() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    for required in ['interactive.css', 'interactive.js']:
        if not (ASSETS / required).is_file():
            raise SystemExit(f'Missing required source asset: {required}')
    # The synchronous, self-hosted theme initializer avoids an initial flash.
    (ASSETS / 'theme.js').write_text("try{const t=localStorage.getItem('tunnel-theme');if(t==='dark'||t==='light')document.documentElement.dataset.theme=t}catch(_){}\n", encoding='utf-8')
    # Readability and progressive enhancement refinements; no content is hidden to fix overflow.
    (ASSETS / 'readability.css').write_text('''
.ui-lead{font-size:17px;line-height:1.85}.ui-navlinks a{font-size:13px;min-height:38px;display:inline-flex;align-items:center}.ui-icon-button{min-width:44px;min-height:44px}.ui-section-title>p,.ui-preview p:not(.ui-eyebrow),.ui-verify>div>p:not(.ui-eyebrow){font-size:15px;line-height:1.9}.ui-feature p,.ui-details,.ui-capability-list p,.ui-info-panel p,.ui-info-panel li,.ui-setup p{font-size:14px;line-height:1.85}.ui-feature h3{line-height:1.17}.ui-install-panel p,.ui-trust-content p:not(.ui-eyebrow),.ui-inspector-copy>p:not(.ui-eyebrow){font-size:14px;line-height:1.9}.ui-accordion summary{font-size:14px}.ui-invariant p{font-size:12px}.ui-inspector-head,.ui-eyebrow{font-size:10px}.ui-main>section,.ui-main>.ui-wrap,.ui-lab-body>*,.ui-two-column>*{min-width:0}.ui-main{outline:none}button:disabled{cursor:not-allowed;opacity:.38}.ui-skip:focus{outline:3px solid #bff17c;outline-offset:3px}.ui-segmented button,.ui-filter button{min-height:44px}.ui-endcap em{font-family:Georgia,serif;font-weight:400}.ui-hero h1{font-weight:650}.ui-capability-list p{overflow-wrap:break-word}.ui-details code{overflow-wrap:anywhere}.ui-code code{overflow-wrap:normal}.ui-hash-tool,.ui-trust-panel,.ui-inspector{min-width:0}.ui-object-heading{font-size:9px}.ui-object-body p{font-size:12px;min-height:44px}.ui-canvas-note{font-size:9px}.ui-canvas-tabs button{min-height:52px;font-size:12px}.ui-canvas-tabs button span{font-size:9px}.ui-canvas-output{font-size:11px}.ui-lab-label{font-size:9px}.ui-step strong{font-size:12px}.ui-step span{font-size:9px}.ui-sidecar p{font-size:11px}.ui-lab-help{font-size:10px}.ui-inspector .ui-inline-link{font-size:12px}.ui-lab-controls .ui-button,.ui-lab-controls .ui-icon-button{min-height:44px}.ui-trust-panel .ui-segmented{flex-wrap:wrap}.ui-trust-panel .ui-segmented button{font-size:12px}.ui-search-results strong{font-size:14px}.ui-search-results span{font-size:12px}.ui-search-results a{min-height:66px}.ui-search-field input{font-size:16px}.ui-file-picker span{font-size:12px}.ui-hash-status{font-size:12px}.ui-footer-top>div>a:not(.ui-brand){font-size:12px}.ui-footer-top h2{font-size:12px}.ui-boundary p:not(.ui-eyebrow){font-size:15px}
@media(max-width:1100px){.ui-hero{gap:36px}.ui-lead{font-size:15px}.ui-inspector-copy>p:not(.ui-eyebrow){font-size:13px}.ui-feature p,.ui-info-panel li,.ui-info-panel p{font-size:13px}.ui-canvas-tabs button{font-size:11px}.ui-canvas-tabs button span{font-size:8px}.ui-object-body p{font-size:11px}.ui-lab-label{font-size:8px}.ui-step strong{font-size:11px}}
@media(max-width:850px){.ui-hero{grid-template-columns:1fr;max-width:690px;padding-top:48px;gap:45px}.ui-hero h1{font-size:clamp(50px,8.6vw,72px)}.ui-hero .ui-lead{font-size:16px;max-width:47ch}.ui-canvas{max-width:510px;width:100%;justify-self:center;padding:25px}.ui-object-body h2{font-size:33px}.ui-object-body p{font-size:12px}.ui-object-heading>span:last-child{display:inline}.ui-canvas-top>span:last-child{display:inline}.ui-canvas-tabs button{font-size:12px}.ui-canvas-tabs button span{font-size:9px}.ui-section-title>p{font-size:14px}.ui-feature p{font-size:13px}.ui-trust-content p:not(.ui-eyebrow){font-size:13px}.ui-verify{grid-template-columns:1fr;gap:29px}.ui-verify>div>p:not(.ui-eyebrow){max-width:65ch}.ui-hash-tool{max-width:650px;width:100%}.ui-boundary p:not(.ui-eyebrow){font-size:14px}}
@media(max-width:620px){.ui-nav{gap:6px}.ui-brand{font-size:16px}.ui-brand small{display:none}.ui-actions{gap:0}.ui-actions .ui-icon-button{min-width:40px;min-height:44px}.ui-hero{padding-top:33px;gap:37px}.ui-hero h1{font-size:clamp(41px,10vw,60px)}.ui-hero .ui-lead{font-size:15px}.ui-canvas{padding:22px 18px}.ui-canvas-top{font-size:8px;letter-spacing:.07em}.ui-canvas-top>span:last-child{display:none}.ui-object-body h2{font-size:29px}.ui-object-body p{font-size:11px;min-height:42px}.ui-object-heading{font-size:8px}.ui-canvas-tabs button{font-size:11px;padding:10px 7px}.ui-canvas-tabs button span{font-size:8px}.ui-canvas-output{font-size:10px}.ui-canvas-note{font-size:8px}.ui-feature p,.ui-info-panel p,.ui-info-panel li,.ui-capability-list p,.ui-details,.ui-setup p,.ui-install-panel p{font-size:13px}.ui-accordion summary{font-size:13px}.ui-section-title>p,.ui-preview p:not(.ui-eyebrow){font-size:14px}.ui-inspector-copy>p:not(.ui-eyebrow){font-size:13px}.ui-invariant p{font-size:11px}.ui-lab-label{font-size:8px;letter-spacing:0}.ui-step strong{font-size:11px}.ui-step span{font-size:8px}.ui-sidecar strong{font-size:8px;line-height:1.7;display:block}.ui-sidecar p{font-size:10px}.ui-trust-panel .ui-segmented button{font-size:10px}.ui-filter button{font-size:11px}.ui-lab-controls .ui-button{font-size:11px}.ui-footer-top>div>a:not(.ui-brand){font-size:12px}.ui-footer-top h2{font-size:11px}.ui-endcap h2{font-size:38px}.ui-endcap p{font-size:13px}.ui-file-picker span{font-size:11px}.ui-search-results span{font-size:11px}.ui-boundary p:not(.ui-eyebrow){font-size:14px}}
@media(prefers-reduced-motion:reduce){::view-transition-old(*),::view-transition-new(*),::view-transition-group(*){animation:none!important}}
'''.strip() + '\n', encoding='utf-8')
    (ASSETS / 'favicon.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40"><rect width="40" height="40" rx="11" fill="#193524"/><g fill="none" stroke="#c1ef7b" stroke-width="1.7"><path d="M9 30V18a11 11 0 0 1 22 0v12"/><path d="M15 30V18a5 5 0 0 1 10 0v12"/><path d="M20 30V18"/></g></svg>\n', encoding='utf-8')
    pages = {'home': home_page(), 'engine': engine_page(), 'guide': guide_page(), 'trust': trust_page()}
    routes = {
        'home': ('index.html', '', 'Tunnel HTTPS — Network control. On your device.', 'An open-source, on-device Android networking engine. Explore its packet paths, privacy boundaries and official installation instructions.'),
        'engine': ('engineering/index.html', 'engineering/', 'Inside the engine — Tunnel HTTPS', 'An interactive architectural walkthrough of DNS, TCP, TLS and QUIC handling, with explicit scope and source links.'),
        'guide': ('guide/index.html', 'guide/', 'Install and verify — Tunnel HTTPS', 'Start from official releases, install through Android and compute APK checksums locally without uploading a file.'),
        'trust': ('privacy/index.html', 'privacy/', 'Privacy and boundaries — Tunnel HTTPS', 'Understand what stays on the device, what reaches the network and how this website handles local interactions.')
    }
    templates = '\n'.join(f'<template id="screen-{key}">{content}</template>' for key, content in pages.items())
    for key, (filename, path, page_title, description) in routes.items():
        document = f'''<!doctype html>
<html lang="en" data-site-version="interactive-3.0">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="{CSP}"><meta name="referrer" content="no-referrer"><meta name="color-scheme" content="light dark"><meta name="theme-color" content="#193524"><title>{html.escape(page_title)}</title><meta name="description" content="{html.escape(description, quote=True)}"><link rel="canonical" href="{SITE}{path}"><meta property="og:type" content="website"><meta property="og:title" content="{html.escape(page_title, quote=True)}"><meta property="og:description" content="{html.escape(description, quote=True)}"><meta property="og:url" content="{SITE}{path}"><meta name="twitter:card" content="summary"><link rel="icon" href="{BASE}assets/v3/favicon.svg" type="image/svg+xml"><script src="{BASE}assets/v3/theme.js"></script><link rel="stylesheet" href="{BASE}assets/v3/interactive.css"><link rel="stylesheet" href="{BASE}assets/v3/readability.css"><script src="{BASE}assets/v3/interactive.js" defer></script></head>
<body>{header(key)}<main class="ui-main" id="main" data-screen="{key}" tabindex="-1">{pages[key]}</main>{footer()}{templates}</body>
</html>\n'''
        target = DOCS / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(document, encoding='utf-8')
    missing = title('404 / That path is not here', 'A wrong turn.<br><em>A clear way back.</em>', 'The page may have moved. The overview, engine walkthrough and installation guide are still here.') + '<section class="ui-wrap ui-section"><div class="ui-buttons">' + route('', 'Back to overview', 'ui-button primary', True) + route('guide/', 'Installation guide', 'ui-button', True) + '</div></section>'
    not_found = (DOCS / 'index.html').read_text(encoding='utf-8')
    start = not_found.index('<main ')
    end = not_found.index('</main>', start) + len('</main>')
    not_found = not_found[:start] + f'<main class="ui-main" id="main" data-screen="not-found" tabindex="-1">{missing}</main>' + not_found[end:]
    not_found = not_found.replace('<title>Tunnel HTTPS — Network control. On your device.</title>', '<title>Page not found — Tunnel HTTPS</title>')
    not_found = not_found.replace(f'<link rel="canonical" href="{SITE}">', '<meta name="robots" content="noindex">')
    (DOCS / '404.html').write_text(not_found, encoding='utf-8')
    (DOCS / '.nojekyll').touch()
    print(json.dumps({'status': 'BUILT', 'routes': [row[0] for row in routes.values()] + ['404.html'], 'version': 'interactive-3.0', 'external_runtime_dependencies': 0, 'release_url': release_url(), 'scope': 'Website only; no Android networking or signing changes.'}, indent=2))


if __name__ == '__main__':
    main()
