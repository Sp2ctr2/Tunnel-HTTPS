#!/usr/bin/env python3
"""Static and browser regression checks for the generated public site.

Run locally with --static-only, or install Playwright browsers for the full
suite. --url verifies an actual deployment instead of starting a local server.
No Android, throughput, physical-device or APK-signature claims are made here.
"""
from __future__ import annotations
import argparse
from functools import partial
import hashlib
from html.parser import HTMLParser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
from threading import Thread
import time
from urllib.parse import unquote, urlparse

BUILD = 'path-studio-20260907'
PAGES = ['', 'engineering/', 'guide/', 'privacy/']

class Document(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.nodes = []
        self.ids = set()
    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        self.nodes.append((tag, attrs))
        if 'id' in attrs:
            assert attrs['id'] not in self.ids, f'Duplicate id: {attrs["id"]}'
            self.ids.add(attrs['id'])

def static_checks(root: Path) -> dict:
    docs = {}
    for path in root.rglob('*.html'):
        doc = Document()
        text = path.read_text(encoding='utf-8')
        doc.feed(text)
        assert '```' not in text and '\\`' not in text, f'Leaked Markdown in {path}'
        assert 'https://fonts.googleapis' not in text
        assert f'content="{BUILD}"' in text
        assert 'unsafe-inline' not in text and 'unsafe-eval' not in text
        assert 'connect-src \'none\'' in text
        for tag, attrs in doc.nodes:
            assert 'style' not in attrs, f'Inline style in {path}'
            assert not any(key.startswith('on') for key in attrs), f'Inline handler in {path}'
            if tag == 'script':
                assert attrs.get('src'), f'Inline script in {path}'
            if tag == 'button':
                assert attrs.get('type') == 'button', f'Implicit submit in {path}'
            if tag == 'a':
                assert not attrs.get('href','').startswith('javascript:'), f'Unsafe link in {path}'
        docs[path.resolve()] = doc
    assert len(docs) == 9, f'Expected 8 localized pages and one 404, got {len(docs)}'
    links = 0
    for path, doc in docs.items():
        for tag, attrs in doc.nodes:
            ref = attrs.get('href') if tag in ['a','link'] else attrs.get('src')
            if not ref or ref.startswith(('https:','http:','data:')):
                continue
            parsed = urlparse(ref)
            rel = unquote(parsed.path)
            target = (root / rel.removeprefix('/Tunnel-HTTPS/')) if rel.startswith('/Tunnel-HTTPS/') else path.parent / rel
            if not rel:
                target = path
            elif rel.endswith('/'):
                target /= 'index.html'
            target = target.resolve()
            assert target.is_relative_to(root.resolve()), f'Path escapes generated site: {ref}'
            assert target.exists(), f'{path}: missing {ref}'
            if parsed.fragment and target in docs:
                assert parsed.fragment in docs[target].ids, f'{path}: unknown anchor {ref}'
            links += 1
    return {'status':'PASS','html_pages':len(docs),'internal_links_and_assets':links}

def until(page, expression: str, timeout: float = 10):
    """CDP evaluation polling, without Playwright's eval-based page predicate."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if page.evaluate(expression):
            return
        page.wait_for_timeout(50)
    raise AssertionError('Timed out: '+expression)

class Quiet(SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass

def browser_checks(root: Path, report_dir: Path, engines: list[str], base_url: str | None) -> dict:
    from playwright.sync_api import sync_playwright
    server = None
    if not base_url:
        server = ThreadingHTTPServer(('127.0.0.1',0),partial(Quiet,directory=str(root.resolve())))
        Thread(target=server.serve_forever,daemon=True).start()
        base_url = f'http://127.0.0.1:{server.server_port}/'
    base_url = base_url.rstrip('/') + '/'
    result = {'status':'RUNNING','base_url':base_url,'cases':[],'interactions':[], 'scope':'Website only; not Android/network certification'}
    report_dir.mkdir(parents=True,exist_ok=True)
    try:
        with sync_playwright() as p:
            for engine in engines:
                options = {}
                exe = os.environ.get('PLAYWRIGHT_CHROMIUM_EXECUTABLE')
                if exe and engine == 'chromium': options['executable_path'] = exe
                browser = getattr(p,engine).launch(**options)
                try:
                    for locale in ['', 'ko/']:
                        for path in PAGES:
                            for width in [320,390,768,1440]:
                                for scheme in ['light','dark']:
                                    context = browser.new_context(viewport={'width':width,'height':960},color_scheme=scheme)
                                    page = context.new_page()
                                    errors = []
                                    page.on('pageerror',lambda error: errors.append(str(error)))
                                    response = page.goto(base_url+locale+path,wait_until='load')
                                    assert response and response.status == 200, (path,response.status if response else None)
                                    assert page.locator('meta[name="build"]').get_attribute('content') == BUILD
                                    until(page,'!document.querySelector("[data-theme-toggle]").hidden')
                                    assert not errors, errors
                                    if page.evaluate('document.documentElement.scrollWidth > innerWidth + 1'):
                                        page.screenshot(path=str(report_dir/'overflow.png'),full_page=True)
                                        print(page.evaluate('''() => [...document.querySelectorAll('body *')].map(e=>({tag:e.tagName,c:String(e.className),right:e.getBoundingClientRect().right,text:e.textContent.slice(0,50)})).filter(e=>e.right>innerWidth+1).slice(0,25)'''),flush=True)
                                        raise AssertionError((engine,locale,path,width,scheme,'horizontal overflow'))
                                    assert page.locator('h1').is_visible()
                                    assert page.locator('.skip').count() == 1
                                    if path == 'engineering/':
                                        assert page.locator('#explorer-panel').is_visible()
                                        assert not page.locator('.protocol-fallback').is_visible()
                                    if path == 'guide/':
                                        assert not page.locator('#build-code').is_visible()
                                        assert page.locator('[data-verifier]').is_visible()
                                    if engine == engines[0] and locale == '' and width in [390,1440] and path in ['', 'engineering/']:
                                        name = f'{path.strip("/") or "home"}-{scheme}-{width}.png'
                                        page.screenshot(path=str(report_dir/name),full_page=True,animations='disabled')
                                    result['cases'].append({'engine':engine,'locale':locale or 'en','path':path or '/','width':width,'theme':scheme,'status':'PASS'})
                                    context.close()
                    context = browser.new_context(viewport={'width':1440,'height':1000},color_scheme='light')
                    page = context.new_page()
                    page.goto(base_url+'engineering/')
                    transition_names = page.evaluate('''() => [...document.querySelectorAll('*')].map(e => getComputedStyle(e).viewTransitionName).filter(n => n && n !== 'none' && n !== 'match-element')''')
                    assert len(transition_names) == len(set(transition_names)), 'Duplicate view-transition names'
                    for mode in ['dns','tcp','tls','quic']:
                        page.locator(f'[data-tab="{mode}"]').click()
                        assert page.locator(f'[data-tab="{mode}"]').get_attribute('aria-selected') == 'true'
                        for stage in range(4):
                            page.locator(f'[data-stage="{stage}"]').click()
                            assert page.locator(f'[data-stage="{stage}"]').get_attribute('aria-current') == 'step'
                            title = page.locator(f'[data-stage="{stage}"] b').inner_text()
                            assert page.locator('[data-step-title]').inner_text() == title
                            assert page.locator('[data-step-source]').get_attribute('href').startswith('https://github.com/Sp2ctr2/Tunnel-HTTPS/blob/main/app/')
                    page.locator('[data-next]').click()
                    assert page.locator('[data-prev]').is_disabled()
                    page.locator('[data-tab="quic"]').focus()
                    page.keyboard.press('Home')
                    assert page.locator('[data-tab="dns"]').get_attribute('aria-selected') == 'true'
                    page.keyboard.press('ArrowRight')
                    assert page.locator('[data-tab="tcp"]').get_attribute('aria-selected') == 'true'
                    result['interactions'].append({'engine':engine,'test':'16 protocol/stage states, replay and keyboard tabs','status':'PASS'})
                    page.goto(base_url+'engineering/#tls')
                    assert page.locator('[data-tab="tls"]').get_attribute('aria-selected') == 'true'
                    page.goto(base_url)
                    page.locator('[data-preview="quic"]').click()
                    assert 'QUIC' in page.locator('.portal-summary').inner_text()
                    page.locator('[data-theme-toggle]').click()
                    until(page,'document.documentElement.dataset.theme === "dark"')
                    page.locator('.nav a').filter(has_text='Get started').click()
                    assert '/guide/' in page.url
                    assert page.evaluate('document.documentElement.dataset.theme') == 'dark'
                    page.go_back()
                    assert page.locator('body').get_attribute('data-page') == 'home'
                    page.locator('.motion-control').click()
                    assert page.locator('.motion-control').get_attribute('aria-pressed') == 'true'
                    page.reload()
                    assert page.evaluate('document.documentElement.dataset.motion') == 'off'
                    result['interactions'].append({'engine':engine,'test':'navigation, browser back, theme/motion persistence and hero tabs','status':'PASS'})
                    page.keyboard.press('Control+k')
                    assert page.locator('#search-dialog').is_visible()
                    page.locator('#search-input').fill('DNS')
                    assert page.locator('.search-items li:visible').count() == 1
                    page.keyboard.press('ArrowDown'); page.keyboard.press('Enter')
                    page.wait_for_url('**/engineering/#dns', wait_until='load')
                    until(page,'location.hash === "#dns"')
                    assert '/engineering/' in page.url
                    page.keyboard.press('Control+k')
                    page.locator('#search-input').fill('TLS')
                    page.keyboard.press('Enter')
                    until(page, 'location.hash === "#tls" && !document.querySelector("#search-dialog").open && document.querySelector("#tab-tls").getAttribute("aria-selected") === "true"')
                    page.keyboard.press('Control+k')
                    page.locator('#search-input').fill('<img src=x onerror=alert(1)>')
                    assert page.locator('#search-empty').is_visible()
                    assert page.locator('#search-dialog img').count() == 0
                    page.keyboard.press('Escape')
                    assert not page.locator('#search-dialog').is_visible()
                    result['interactions'].append({'engine':engine,'test':'command palette, same-page links, keyboard search, empty state and no HTML injection','status':'PASS'})
                    page.goto(base_url+'guide/')
                    page.locator('summary').filter(has_text='Clone, build').click()
                    assert page.locator('#build-code').is_visible()
                    page.locator('[data-copy="build-code"]').click()
                    assert page.locator('.toast').is_visible()
                    data = b'Tunnel HTTPS checksum regression fixture\n'
                    digest = hashlib.sha256(data).hexdigest()
                    page.locator('#expected-hash').fill(digest)
                    page.locator('#check-file').set_input_files({'name':'fixture.apk','mimeType':'application/octet-stream','buffer':data})
                    requests = []
                    page.on('request', lambda req: requests.append(req.url))
                    page.locator('[data-verify]').click()
                    until(page,'document.querySelector("[data-result]").dataset.state === "success"')
                    assert digest in page.locator('[data-result]').inner_text()
                    assert requests == [], 'Verifier unexpectedly sent a request'
                    page.locator('#expected-hash').fill('0'*64)
                    page.locator('[data-verify]').click()
                    until(page,'document.querySelector("[data-result]").dataset.state === "error"')
                    assert 'Mismatch' in page.locator('[data-result]').inner_text()
                    page.locator('#expected-hash').fill('invalid')
                    page.locator('[data-verify]').click()
                    assert '64-character' in page.locator('[data-result]').inner_text()
                    page.locator('#expected-hash').fill(digest)
                    page.locator('#check-file').set_input_files({'name':'empty.apk','mimeType':'application/octet-stream','buffer':b''})
                    page.locator('[data-verify]').click()
                    assert 'non-empty' in page.locator('[data-result]').inner_text()
                    result['interactions'].append({'engine':engine,'test':'expand/copy commands, hash match/mismatch, invalid/empty input and no uploads','status':'PASS'})
                    assert page.evaluate('''() => { const s=document.createElement('script'); s.textContent='window.__injected=true'; document.body.append(s); return window.__injected !== true; }''')
                    assert page.evaluate('''async () => { try { await fetch('https://example.com/blocked'); return false; } catch { return true; } }''')
                    page.goto(base_url+'?next=https://example.com/#%3Cimg%3E')
                    assert page.url.startswith(base_url)
                    assert page.locator('body').get_attribute('data-page') == 'home'
                    result['interactions'].append({'engine':engine,'test':'strict CSP script/fetch blocking and no URL-driven redirect','status':'PASS'})
                    axe_path = os.environ.get('AXE_PATH')
                    if axe_path:
                        for locale in ['', 'ko/']:
                            for scheme in ['light', 'dark']:
                                audit_context = browser.new_context(color_scheme=scheme, reduced_motion='reduce')
                                audit_page = audit_context.new_page()
                                for path in PAGES:
                                    audit_page.goto(base_url+locale+path)
                                    audit_page.evaluate(Path(axe_path).read_text())
                                    audit = audit_page.evaluate('async () => await axe.run(document, {runOnly:{type:"tag", values:["wcag2a","wcag2aa","wcag21aa"]}})')
                                    violations = audit['violations']
                                    (report_dir/f'axe-{engine}-{locale.strip("/") or "en"}-{scheme}-{path.strip("/") or "home"}.json').write_text(json.dumps(violations,indent=2))
                                    assert not violations, [(v['id'],v['help']) for v in violations]
                                audit_context.close()
                        result['interactions'].append({'engine':engine,'test':'axe WCAG 2.1 AA automated checks / four pages, two languages, two themes','status':'PASS'})
                    context.close()
                    mobile = browser.new_context(viewport={'width':390,'height':844},reduced_motion='reduce')
                    mp = mobile.new_page(); mp.goto(base_url)
                    mp.locator('[data-open="menu-dialog"]').click()
                    assert mp.locator('#menu-dialog').is_visible()
                    mp.locator('#menu-dialog a').filter(has_text='Explore').click()
                    assert '/engineering/' in mp.url
                    mp.goto(base_url)
                    assert mp.evaluate('getComputedStyle(document.querySelector(".gate")).animationName') == 'none'
                    result['interactions'].append({'engine':engine,'test':'mobile navigation and reduced-motion behavior','status':'PASS'})
                    mobile.close()
                    nojs = browser.new_context(java_script_enabled=False,viewport={'width':390,'height':844})
                    np = nojs.new_page(); np.goto(base_url+'engineering/')
                    assert np.locator('.protocol-fallback').is_visible()
                    assert np.locator('.protocol-fallback article').count() == 4
                    np.goto(base_url+'guide/')
                    np.locator('summary').filter(has_text='Clone, build').click()
                    assert np.locator('#build-code').is_visible()
                    result['interactions'].append({'engine':engine,'test':'no-JS architecture, navigation and expandable build instructions','status':'PASS'})
                    nojs.close()
                finally:
                    browser.close()
        result['status'] = 'PASS'
        return result
    except Exception as error:
        result['status'] = 'FAIL'
        result['error'] = str(error)
        raise
    finally:
        (report_dir/'browser-report.json').write_text(json.dumps(result,indent=2)+'\n')
        if server:
            server.shutdown()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('_site'))
    parser.add_argument('--report',type=Path,default=Path('experience-test-results'))
    parser.add_argument('--browsers',nargs='+',default=['chromium','webkit'])
    parser.add_argument('--url')
    parser.add_argument('--static-only',action='store_true')
    args = parser.parse_args()
    args.report.mkdir(parents=True,exist_ok=True)
    report = static_checks(args.root)
    (args.report/'static-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
    if not args.static_only:
        results = browser_checks(args.root,args.report,args.browsers,args.url)
        print(json.dumps({'status':results['status'],'page_cases':len(results['cases']),'interaction_groups':len(results['interactions'])}))
