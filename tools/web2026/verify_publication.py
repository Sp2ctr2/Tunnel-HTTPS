#!/usr/bin/env python3
"""Verify anonymously served Pages bytes and exercise the actual deployed UI."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import time
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright, expect

ROUTES = {'home': '', 'engineering': 'engineering/', 'guide': 'guide/', 'privacy': 'privacy/'}

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='https://sp2ctr2.github.io/Tunnel-HTTPS/')
    parser.add_argument('--root', type=Path, default=Path('docs'))
    parser.add_argument('--report', type=Path, default=Path('web2026-test-results/live'))
    args = parser.parse_args()
    base = args.url.rstrip('/') + '/'
    parsed = urlparse(base)
    if (parsed.scheme, parsed.netloc, parsed.path) != ('https', 'sp2ctr2.github.io', '/Tunnel-HTTPS/'):
        raise ValueError('Refusing to test an unexpected deployment origin')
    args.report.mkdir(parents=True, exist_ok=True)
    proof = {'status': 'RUNNING', 'url': base, 'anonymous_bytes': [], 'live_views': [], 'interactions': [],
             'scope': 'Website deployment and UI only; not Android, APK signing, or network certification.'}
    paths = {route: route + 'index.html' for route in ROUTES.values()}
    paths.update({p: p for p in ['assets/web2026/site.css', 'assets/web2026/site.js',
                                  'assets/web2026/theme.js', 'assets/web2026/release.js', 'site-version.json']})
    try:
        for route, relative in paths.items():
            expected = hashlib.sha256((args.root / relative).read_bytes()).hexdigest()
            deadline = time.monotonic() + 150
            last_error = ''
            while True:
                try:
                    request = Request(base + route, headers={'User-Agent': 'TunnelHTTPS-Pages-verification', 'Cache-Control': 'no-cache'})
                    with urlopen(request, timeout=20) as response:
                        body = response.read(2 * 1024 * 1024 + 1)
                        status = response.status
                    if status == 200 and hashlib.sha256(body).hexdigest() == expected:
                        proof['anonymous_bytes'].append({'url': base + route, 'sha256': expected, 'status': 'PASS'})
                        break
                    last_error = 'Public content differs from the tested build'
                except OSError as error:
                    last_error = str(error)
                if time.monotonic() >= deadline:
                    raise AssertionError(f'{route}: {last_error}')
                time.sleep(5)
        with sync_playwright() as p:
            browser = p.chromium.launch()
            try:
                for theme, width in [('dark', 1440), ('light', 1440), ('dark', 390)]:
                    context = browser.new_context(viewport={'width': width, 'height': 960}, color_scheme=theme, reduced_motion='reduce')
                    page = context.new_page()
                    errors: list[str] = []
                    page.on('pageerror', lambda e: errors.append(str(e)))
                    for name, route in ROUTES.items():
                        response = page.goto(base + route, wait_until='networkidle')
                        assert response is not None and response.status == 200
                        page.wait_for_function('document.documentElement.dataset.enhanced === "true"')
                        expect(page.locator('.screen.is-active')).to_have_attribute('data-screen', name)
                        expect(page.locator('.screen.is-active h1')).to_be_visible()
                        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
                        filename = f'{name}-{theme}-{width}.png'
                        page.screenshot(path=str(args.report / filename), full_page=True)
                        proof['live_views'].append({'page': name, 'theme': theme, 'width': width, 'screenshot': filename, 'status': 'PASS'})
                    if width == 390:
                        page.locator('.menu-toggle').click()
                        page.locator('#mobile-nav [data-route="engineering"]').click()
                    else:
                        page.locator('.desktop-nav [data-route="engineering"]').click()
                    page.wait_for_function('document.body.dataset.current === "engineering"')
                    page.locator('#tab-tcp').click()
                    page.locator('[data-step="3"]').click()
                    expect(page.locator('#step-title')).to_contain_text('Adapt the connection')
                    assert 'TurboTcpForwarder.kt' in page.locator('#inspector-source').get_attribute('href')
                    proof['interactions'].append({'theme': theme, 'width': width, 'test': 'navigation and TCP/TLS explorer', 'status': 'PASS'})
                    assert not errors, errors
                    context.close()
            finally:
                browser.close()
        proof['site_version'] = json.loads((args.root / 'site-version.json').read_text())
        proof['status'] = 'PASS'
    except Exception as error:
        proof['status'] = 'FAIL'
        proof['failure'] = str(error)
        raise
    finally:
        (args.report / 'publication.json').write_text(json.dumps(proof, indent=2) + '\n')
        print(json.dumps({k: proof[k] for k in ('status', 'url', 'scope')}, indent=2))

if __name__ == '__main__':
    main()
