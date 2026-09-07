#!/usr/bin/env python3
"""Browser tests for presentation, navigation and illustrative UI, not Android networking."""
from __future__ import annotations
import functools
import hashlib
import http.server
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import urllib.parse
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'web2026-test-results'
DOCS = ROOT / 'docs'
BASE_PATH = '/Tunnel-HTTPS/'
PAGES = {'home': '', 'engineering': 'engineering/', 'guide': 'guide/', 'privacy': 'privacy/'}

def wait(page, expression: str, timeout: float = 8.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if page.evaluate(expression):
            return
        page.wait_for_timeout(50)
    raise AssertionError('Browser condition did not become true: ' + expression)

class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass

def run():
    OUT.mkdir(exist_ok=True)
    report = {'status': 'RUNNING', 'scope': 'Website layout, interactions, navigation, CSP and synthetic checksum comparison. Not Android runtime or physical-device testing.', 'pages': [], 'interactions': [], 'browser_errors': []}
    server = None
    try:
        with tempfile.TemporaryDirectory() as temporary:
            (Path(temporary) / 'Tunnel-HTTPS').symlink_to(DOCS, target_is_directory=True)
            handler = functools.partial(QuietHandler, directory=temporary)
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            origin = 'http://127.0.0.1:' + str(server.server_port)
            base = origin + BASE_PATH
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                for theme in ('dark', 'light'):
                    for width in (320, 390, 768, 1440, 1920):
                        context = browser.new_context(viewport={'width': width, 'height': 960}, color_scheme=theme, reduced_motion='reduce')
                        page = context.new_page()
                        errors = []
                        page.on('pageerror', lambda error: errors.append(str(error)))
                        for name, path in PAGES.items():
                            response = page.goto(base + path, wait_until='networkidle')
                            assert response and response.status == 200, (name, response.status if response else None)
                            wait(page, 'document.documentElement.dataset.enhanced === "true"')
                            assert page.locator('.screen.is-active').count() == 1
                            assert page.locator('.screen.is-active').get_attribute('data-screen') == name
                            assert page.locator('.screen.is-active h1').is_visible()
                            assert page.evaluate('document.documentElement.dataset.theme') == theme
                            dimensions = page.evaluate('({width:innerWidth,scroll:document.documentElement.scrollWidth})')
                            if dimensions['scroll'] > width + 1:
                                page.screenshot(path=str(OUT / f'overflow-{name}-{theme}-{width}.png'), full_page=True)
                                raise AssertionError(('Horizontal overflow', name, theme, width, dimensions))
                            visible = page.locator('body').inner_text()
                            assert '@@' not in visible and 'flowchart LR' not in visible and '@keyframes' not in visible
                            assert './gradlew' not in visible, ('Developer commands unexpectedly visible', name)
                            if width in (390, 1440):
                                page.screenshot(path=str(OUT / f'{name}-{theme}-{width}.png'), full_page=True)
                            report['pages'].append({'page': name, 'theme': theme, 'width': width, 'status': 'PASS'})
                        assert not errors, errors
                        context.close()
                context = browser.new_context(viewport={'width': 1440, 'height': 960}, color_scheme='dark')
                page = context.new_page()
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(base, wait_until='networkidle')
                page.locator('.desktop-nav [data-route="engineering"]').click()
                wait(page, 'document.body.dataset.current === "engineering"')
                assert page.url.endswith('/engineering/')
                page.locator('#tab-tcp').click()
                page.locator('[data-step="3"]').click()
                assert page.locator('#step-title').inner_text() == 'Adapt the connection, not the content.'
                assert 'TurboTcpForwarder.kt' in page.locator('#inspector-source').get_attribute('href')
                page.locator('#tab-tcp').press('ArrowRight')
                assert page.locator('#tab-quic').get_attribute('aria-selected') == 'true'
                assert page.locator('#path-progress').input_value() == '0'
                page.locator('#next-step').click()
                assert page.locator('#path-progress').input_value() == '1'
                page.locator('#play-path').click()
                wait(page, 'document.querySelector("#path-progress").value === "3"', 7)
                assert page.locator('#play-path').get_attribute('aria-pressed') == 'false'
                report['interactions'].append('Protocol selection, keyboard tabs, source links, step advance and finite playback')
                page.locator('#capability-search').fill('DNS64')
                assert page.locator('.capability-row:visible').count() == 1
                page.locator('#capability-search').fill('no such subsystem')
                assert page.locator('.capability-row:visible').count() == 0
                assert 'No matching' in page.locator('#filter-result').inner_text()
                page.locator('#capability-search').fill('')
                page.locator('[data-filter="experimental"]').click()
                assert page.locator('.capability-row:visible').count() == 2
                report['interactions'].append('Search, scope filters and no-results state')
                page.locator('[data-layer="output"]').click()
                assert 'No routing authority' in page.locator('#model-note h3').inner_text()
                report['interactions'].append('Interactive model layer explanation')
                page.locator('.desktop-nav [data-route="guide"]').click()
                wait(page, 'document.body.dataset.current === "guide"')
                assert page.url.endswith('/guide/')
                page.go_back()
                wait(page, 'document.body.dataset.current === "engineering"')
                page.go_forward()
                wait(page, 'document.body.dataset.current === "guide"')
                report['interactions'].append('Real route URLs, history Back and Forward')
                page.locator('.theme-toggle').click()
                assert page.evaluate('document.documentElement.dataset.theme') == 'light'
                page.reload(wait_until='networkidle')
                assert page.evaluate('document.documentElement.dataset.theme') == 'light'
                report['interactions'].append('Persisted manual theme')
                page.locator('summary').filter(has_text='Build and test locally').click()
                assert page.locator('#developer pre').first.is_visible()
                page.locator('#developer .copy-code').first.click()
                wait(page, '(() => { const t=document.querySelector("#toast"); return !t.hidden && /^(Copied\\.|Text selected\\.)/.test(t.textContent); })()')
                report['interactions'].append('Collapsed developer commands and safe copy feedback')
                page.locator('#apk-file').set_input_files({'name': 'synthetic-negative.apk', 'mimeType': 'application/vnd.android.package-archive', 'buffer': b'not-a-release-apk'})
                wait(page, '!!document.querySelector("#hash-result").dataset.result')
                assert page.locator('#hash-result').get_attribute('data-result') in ('calculated', 'mismatch')
                report['interactions'].append('Local file hashing without an upload')
                assert not errors, errors
                context.close()
                fixture = b'synthetic-positive-byte-comparison-not-an-android-apk'
                fixture_hash = hashlib.sha256(fixture).hexdigest()
                context = browser.new_context()
                context.route('**/assets/web2026/release.js', lambda route: route.fulfill(status=200, content_type='application/javascript', body='window.TUNNEL_RELEASE=' + json.dumps({'published': False, 'apk_sha256': fixture_hash}) + ';'))
                page = context.new_page()
                page.goto(base + 'guide/', wait_until='networkidle')
                page.locator('#apk-file').set_input_files({'name': 'synthetic-fixture.apk', 'mimeType': 'application/vnd.android.package-archive', 'buffer': fixture})
                wait(page, 'document.querySelector("#hash-result").dataset.result === "match"')
                page.locator('#apk-file').set_input_files({'name': 'synthetic-mismatch.apk', 'mimeType': 'application/vnd.android.package-archive', 'buffer': b'wrong'})
                wait(page, 'document.querySelector("#hash-result").dataset.result === "mismatch"')
                report['interactions'].append('Synthetic positive and negative checksum fixtures, not device/signature evidence')
                context.close()
                context = browser.new_context(viewport={'width': 390, 'height': 844}, reduced_motion='reduce')
                page = context.new_page()
                page.goto(base, wait_until='networkidle')
                page.locator('.menu-toggle').click()
                assert page.locator('#mobile-nav').is_visible()
                page.locator('#mobile-nav [data-route="engineering"]').click()
                wait(page, 'document.body.dataset.current === "engineering"')
                assert not page.locator('#mobile-nav').is_visible()
                page.locator('.menu-toggle').click()
                page.keyboard.press('Escape')
                assert not page.locator('#mobile-nav').is_visible()
                assert page.locator('.motion-button').get_attribute('aria-pressed') == 'true'
                report['interactions'].append('Mobile navigation, Escape and reduced-motion preference')
                page.evaluate("() => { const script=document.createElement('script'); script.textContent='window.__inlineExecution=true'; document.body.appendChild(script); }")
                assert page.evaluate('window.__inlineExecution') is None
                blocked = page.evaluate("async () => {try {await fetch('https://example.com/');return false;}catch(_){return true;}}")
                assert blocked
                report['interactions'].append('CSP blocks injected inline script and runtime cross-origin fetch')
                context.close()
                context = browser.new_context(java_script_enabled=False, viewport={'width': 390, 'height': 844})
                page = context.new_page()
                for name, path in PAGES.items():
                    page.goto(base + path, wait_until='networkidle')
                    assert page.locator('.screen.is-active').get_attribute('data-screen') == name
                    assert page.locator('.screen.is-active h1').is_visible()
                    assert page.locator('#mobile-nav').is_visible()
                page.goto(base + 'guide/', wait_until='networkidle')
                page.locator('summary').filter(has_text='Build and test locally').click()
                assert page.locator('#developer pre').first.is_visible()
                report['interactions'].append('No-JavaScript direct routes, mobile links and native details')
                context.close()
                browser.close()
        report['status'] = 'PASS'
        report['viewport_cases'] = len(report['pages'])
    except Exception as error:
        report['status'] = 'FAIL'
        report['failure'] = str(error)
        raise
    finally:
        if server:
            server.shutdown()
        (OUT / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'status': report['status'], 'viewport_cases': len(report['pages']), 'interaction_groups': len(report['interactions']), 'scope': report['scope']}, indent=2))

if __name__ == '__main__':
    run()
