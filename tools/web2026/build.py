#!/usr/bin/env python3
"""Build the authored website without changing Android code or signing material."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent
REPO = 'Sp2ctr2/Tunnel-HTTPS'
PAGES = {'home': ('index.html', './', 'Your network. A more direct path.'), 'engineering': ('engineering/index.html', '../', 'Inside the engine'), 'guide': ('guide/index.html', '../', 'Get started'), 'privacy': ('privacy/index.html', '../', 'Privacy & boundaries')}
LIMIT = 128 * 1024 * 1024

def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + '\n', encoding='utf-8')

def request_json(url: str, authenticated: bool = False, maximum: int = 4 * 1024 * 1024):
    headers = {'User-Agent': 'TunnelHTTPS-static-site-builder', 'Accept': 'application/vnd.github+json'}
    token = os.environ.get('GH_TOKEN')
    if authenticated and token:
        headers['Authorization'] = 'Bearer ' + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        data = response.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError('Metadata response exceeded the permitted size')
    return json.loads(data)

def public_release() -> dict:
    """Only non-draft releases with a public publication timestamp are eligible."""
    result = {'published': False, 'url': 'https://github.com/' + REPO + '/releases', 'verification_scope': 'No public release was verified during this build.'}
    try:
        releases = request_json('https://api.github.com/repos/' + REPO + '/releases?per_page=20', authenticated=True)
        if not isinstance(releases, list):
            raise ValueError('Unexpected release metadata')
        for item in releases:
            if item.get('draft') or not item.get('published_at'):
                continue
            parsed = urllib.parse.urlparse(str(item.get('html_url', '')))
            if parsed.scheme != 'https' or parsed.netloc != 'github.com' or not parsed.path.startswith('/' + REPO + '/releases/tag/'):
                continue
            assets = [asset for asset in item.get('assets', []) if str(asset.get('name', '')).lower().endswith('.apk') and not any(word in str(asset.get('name', '')).lower() for word in ('unsigned', 'debug', 'validation'))]
            if not assets:
                continue
            asset = assets[0]
            url = str(asset.get('browser_download_url', ''))
            parsed_asset = urllib.parse.urlparse(url)
            if parsed_asset.scheme != 'https' or parsed_asset.netloc != 'github.com' or not parsed_asset.path.startswith('/' + REPO + '/releases/download/'):
                continue
            declared_size = asset.get('size')
            if not isinstance(declared_size, int) or declared_size < 1 or declared_size > LIMIT:
                continue
            # The artifact must be anonymously downloadable; no token goes to a download URL.
            digest = hashlib.sha256()
            total = 0
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'TunnelHTTPS-public-artifact-verifier'}), timeout=45) as response:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > LIMIT:
                        raise ValueError('APK exceeded size limit')
                    digest.update(chunk)
            if total != declared_size:
                raise ValueError('APK size does not match GitHub metadata')
            expected = asset.get('digest', '')
            actual = digest.hexdigest()
            if isinstance(expected, str) and expected.startswith('sha256:') and expected[7:].lower() != actual:
                raise ValueError('APK hash does not match GitHub asset digest')
            result = {'published': True, 'url': item['html_url'], 'version': str(item.get('tag_name', ''))[:100], 'prerelease': bool(item.get('prerelease')), 'apk_url': url, 'apk_size': total, 'apk_sha256': actual, 'verification_scope': 'Anonymous download, byte length and SHA-256. This website build does not claim a new Android signature or device test.'}
            break
    except (OSError, ValueError, urllib.error.URLError) as error:
        # Never turn an unavailable API or download into a fabricated release CTA.
        result['verification_scope'] = 'Public artifact verification unavailable; the site links to Releases without asserting a downloadable APK.'
        print('Release lookup did not yield a verified APK:', type(error).__name__)
    return result

def build(output: Path, resolve_release: bool = False) -> dict:
    template = (SOURCE / 'template.html').read_text(encoding='utf-8')
    css = (SOURCE / 'site.css').read_text(encoding='utf-8')
    javascript = (SOURCE / 'site.js').read_text(encoding='utf-8')
    try:
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        revision = 'local-preview'
    release = public_release() if resolve_release else {'published': False, 'verification_scope': 'Local preview; release availability is not asserted.'}
    assets = output / 'assets' / 'web2026'
    write(assets / 'site.css', css + '\n@media (max-width:680px){html:not([data-enhanced]) .mobile-nav[hidden]{display:block!important}html:not([data-enhanced]) .menu-toggle{display:none}}\n@media (forced-colors:active){.button,.feature-card,.core-chip,.scope-tag,.path-stop{border:1px solid ButtonText}.path-particle,.orbit{display:none}}')
    write(assets / 'site.js', javascript)
    write(assets / 'theme.js', "'use strict';\n(()=>{let theme='dark',motion='running';try{const saved=localStorage.getItem('tunnel2026.theme');if(saved==='dark'||saved==='light')theme=saved;else if(matchMedia('(prefers-color-scheme: light)').matches)theme='light';if(localStorage.getItem('tunnel2026.motion')==='paused')motion='paused';}catch(_){}if(matchMedia('(prefers-reduced-motion: reduce)').matches)motion='paused';document.documentElement.dataset.theme=theme;document.documentElement.dataset.motion=motion;})();")
    write(assets / 'release.js', "'use strict';\nwindow.TUNNEL_RELEASE = Object.freeze(" + json.dumps(release, ensure_ascii=True, separators=(',', ':')) + ');')
    write(assets / 'release.json', json.dumps(release, indent=2))
    write(assets / 'mark.svg', '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40"><rect width="40" height="40" rx="11" fill="#0b100d"/><path d="M10 29V18a10 10 0 0 1 20 0v11M15 29V18a5 5 0 0 1 10 0v11M20 23v6" fill="none" stroke="#c3ee89" stroke-width="1.8" stroke-linecap="round"/></svg>')
    outputs = []
    for name, (relative, prefix, title) in PAGES.items():
        page = template.replace('@@ROOT@@', prefix).replace('@@PAGE@@', name).replace('@@TITLE@@', title)
        for key in PAGES:
            page = page.replace('@@' + key.upper() + '@@', 'is-active' if key == name else '')
        page = page.replace('<meta name="referrer"', '<meta name="tunnel-site-revision" content="' + revision + '">\n<meta name="referrer"', 1)
        if re.search(r'@@[A-Z_]+@@', page):
            raise ValueError('An unexpanded template token remains')
        write(output / relative, page)
        outputs.append(relative)
    # A self-contained 404 works at arbitrary path depths and does not redirect from untrusted URL input.
    write(output / '404.html', '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>Page not found — Tunnel HTTPS</title><link rel="stylesheet" href="/Tunnel-HTTPS/assets/web2026/site.css"></head><body><main class="page-intro shell"><span class="eyebrow">404 / Lost packet</span><h1>This path ends here.</h1><p>The page may have moved. Continue from the overview or inspect the source.</p><div class="action-row"><a class="button primary" href="/Tunnel-HTTPS/">Back to the overview</a><a class="text-link" href="https://github.com/Sp2ctr2/Tunnel-HTTPS">GitHub ↗</a></div></main></body></html>')
    write(output / '.nojekyll', '')
    manifest = {'site_revision': revision, 'routes': outputs, 'runtime_dependencies': 0, 'release': release, 'scope': 'Website presentation and interactive explanatory UI; no changes to Android runtime or signing identity.'}
    write(assets / 'build.json', json.dumps(manifest, indent=2))
    return manifest

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'docs')
    parser.add_argument('--resolve-release', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or not output.is_relative_to(ROOT):
        raise SystemExit('Output must be a subdirectory of this repository')
    report = build(output, args.resolve_release)
    print(json.dumps({'site_revision': report['site_revision'], 'pages': len(report['routes']), 'public_apk_verified': bool(report['release'].get('published'))}, indent=2))
