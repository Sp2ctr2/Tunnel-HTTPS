"""Verify the exact website artifact, including live publication and tab behavior."""
from __future__ import annotations

import argparse
import functools
import hashlib
import http.server
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import urllib.request
from playwright.sync_api import sync_playwright, expect

ROUTES = {'home': '', 'controls': 'controls/', 'engineering': 'engineering/', 'privacy': 'privacy/', 'guide': 'guide/'}

class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass


def verify_public_files(root: Path, base: str) -> list:
    manifest = json.loads((root / 'manifest.json').read_text())
    files = dict(manifest['files'])
    files['manifest.json'] = hashlib.sha256((root / 'manifest.json').read_bytes()).hexdigest()
    verified = []
    for path, expected in files.items():
        if path in ('.nojekyll', '404.html'):
            continue
        route = path[:-10] if path.endswith('index.html') else path
        url = base + route
        for attempt in range(12):
            try:
                request = urllib.request.Request(url, headers={'User-Agent':'TunnelHTTPS-anonymous-publication-check', 'Cache-Control':'no-cache'})
                with urllib.request.urlopen(request, timeout=20) as response:
                    data = response.read(3*1024*1024)
                actual = hashlib.sha256(data).hexdigest()
                if actual == expected:
                    verified.append({'url':url, 'sha256':actual})
                    break
            except OSError:
                pass
            time.sleep(3)
        else:
            raise AssertionError('Public file differs from tested artifact: '+url)
    return verified


def run(root: Path, out: Path, live: str | None):
    out.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((root / 'manifest.json').read_text())
    report = {'status':'RUNNING', 'edition':manifest['edition'], 'revision':manifest['revision'], 'scope':'Website only. No Android runtime, signing or real network measurements.', 'source':'public HTTPS' if live else 'local HTTP artifact', 'layouts':[], 'accessibility':[], 'interactions':[], 'public_files':[]}
    server = None
    page = None
    try:
        with tempfile.TemporaryDirectory() as folder:
            if live:
                base = live.rstrip('/')+'/'
                report['public_files'] = verify_public_files(root, base)
            else:
                (Path(folder) / 'Tunnel-HTTPS').symlink_to(root.resolve(), target_is_directory=True)
                server = http.server.ThreadingHTTPServer(('127.0.0.1',0), functools.partial(Quiet,directory=folder))
                threading.Thread(target=server.serve_forever,daemon=True).start()
                base = f'http://127.0.0.1:{server.server_port}/Tunnel-HTTPS/'
            report['url'] = base
            with sync_playwright() as playwright:
                executable = os.environ.get('BROWSER_EXECUTABLE')
                browser = playwright.chromium.launch(**({'executable_path':executable} if executable else {}))
                widths = (390,1440) if live else (320,390,768,1440)
                axe = Path(os.environ['AXE_PATH']).read_text() if os.environ.get('AXE_PATH') else None
                for lang in ('en','ko'):
                    for theme in ('light','dark'):
                        for width in widths:
                            context = browser.new_context(viewport={'width':width,'height':960 if width>760 else 844}, reduced_motion='reduce')
                            context.add_init_script(f"localStorage.setItem('tunnel.workspace.theme','{theme}')")
                            page = context.new_page()
                            errors = []
                            page.on('pageerror', lambda error: errors.append(str(error)))
                            for name, route in ROUTES.items():
                                response = page.goto(base+('ko/' if lang=='ko' else '')+route,wait_until='networkidle')
                                assert response and response.status==200
                                expect(page.locator('html')).to_have_attribute('data-enhanced','true')
                                expect(page.locator('.pane:not([hidden])')).to_have_attribute('data-panel',name)
                                expect(page.locator('.pane:not([hidden]) h1')).to_be_visible()
                                expect(page.locator('.main-tab[aria-selected="true"]')).to_have_attribute('data-route',name)
                                assert page.locator('.pane:not([hidden])').count()==1
                                assert page.locator('[role="tablist"] .main-tab[tabindex="0"]').count()==1
                                dims=page.evaluate('''() => ({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,height:innerHeight,scrollHeight:document.documentElement.scrollHeight,paneWidth:document.querySelector('.pane:not([hidden])').clientWidth,paneScroll:document.querySelector('.pane:not([hidden])').scrollWidth})''')
                                assert dims['scrollWidth']<=width+1,('document overflow',dims)
                                assert dims['scrollHeight']<=dims['height']+1,('document vertical scroll',dims)
                                assert dims['paneScroll']<=dims['paneWidth']+1,('panel horizontal overflow',name,dims)
                                body=page.locator('body').inner_text()
                                assert 'flowchart LR' not in body and '@keyframes' not in body and './gradlew' not in body
                                assert page.locator('canvas,iframe').count()==0
                                if name == 'home':
                                    geometry=page.evaluate("""() => { const foot=document.querySelector('.overview-foot');return {visible:getComputedStyle(foot).display!=='none',stage:document.querySelector('.connection-stage').getBoundingClientRect().bottom,foot:foot.getBoundingClientRect().top}; }""")
                                    assert not geometry['visible'] or geometry['stage']<=geometry['foot']+1,('Home content overlaps summary',geometry)
                                if width in (390,1440):
                                    page.screenshot(path=str(out/f'{lang}-{name}-{theme}-{width}.png'))
                                if axe and width==1440:
                                    page.evaluate(axe)
                                    audit=page.evaluate("async () => (await axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}})).violations")
                                    report['accessibility'].append({'page':name,'lang':lang,'theme':theme,'violations':audit})
                                    assert not audit,(name,lang,theme,audit)
                                report['layouts'].append({'page':name,'lang':lang,'theme':theme,'width':width,'status':'PASS'})
                            assert not errors,errors
                            context.close()

                # Short and zoom-like viewports must scroll inside panes, never clip or overlap.
                for lang in ('en','ko'):
                    for width,height in ((1440,800),(1280,720),(1024,600),(390,568)):
                        context=browser.new_context(viewport={'width':width,'height':height},reduced_motion='reduce')
                        page=context.new_page()
                        for name,route in ROUTES.items():
                            page.goto(base+('ko/' if lang=='ko' else '')+route,wait_until='networkidle')
                            expect(page.locator('html')).to_have_attribute('data-enhanced','true')
                            bounds=page.evaluate("""() => {const p=document.querySelector('.pane:not([hidden])');return {height:document.documentElement.scrollHeight,width:document.documentElement.scrollWidth,client:p.clientWidth,scroll:p.scrollWidth};}""")
                            assert bounds['height']<=height+1 and bounds['width']<=width+1 and bounds['scroll']<=bounds['client']+1,(name,width,height,bounds)
                            if name=='home' and width==1440:
                                page.screenshot(path=str(out/f'{lang}-home-short-{width}-{height}.png'))
                            report['layouts'].append({'page':name,'lang':lang,'theme':'light','width':width,'height':height,'short_viewport':True,'status':'PASS'})
                        context.close()

                # Real interactions; video also records actual screen changes on the public URL.
                context=browser.new_context(viewport={'width':1440,'height':960},record_video_dir=str(out/'motion'),record_video_size={'width':1440,'height':960})
                page=context.new_page();errors=[]
                page.on('pageerror',lambda error: errors.append(str(error)))
                page.goto(base,wait_until='networkidle')
                expect(page.locator('html')).to_have_attribute('data-enhanced','true')
                nav_y=page.locator('#main-tabs').bounding_box()['y']
                page.locator('#tab-controls').click()
                expect(page.locator('html')).to_have_attribute('data-page','controls')
                page.wait_for_timeout(450)
                assert page.url.startswith(base+'controls/')
                assert abs(page.locator('#main-tabs').bounding_box()['y']-nav_y)<1
                assert page.evaluate('scrollY')==0
                page.locator('#control-filter').click()
                expect(page.locator('#control-filter')).to_have_attribute('aria-selected','true')
                expect(page.locator('#behavior-value')).to_have_text('Filtered locally')
                page.locator('#behavior-switch').click()
                expect(page.locator('#behavior-switch')).to_have_attribute('aria-checked','false')
                expect(page.locator('#behavior-value')).to_have_text('Not filtered by this rule')
                assert 'control=filter' in page.url
                page.locator('#control-filter').press('ArrowDown')
                expect(page.locator('#control-bypass')).to_have_attribute('aria-selected','true')
                report['interactions'].append('Stationary shell, tab replacement, controlled switch and keyboard sub-tabs')

                page.locator('#tab-engineering').click()
                expect(page.locator('html')).to_have_attribute('data-page','engineering')
                page.wait_for_timeout(420)
                page.locator('#proto-tcp').click()
                page.locator('[data-step="3"]').click()
                expect(page.locator('#engine-title')).to_have_text('Adapt the handshake, not the content.')
                assert page.locator('#engine-source').get_attribute('href').endswith('TlsClientHello.kt')
                assert 'protocol=tcp' in page.url and 'step=3' in page.url
                page.reload(wait_until='networkidle')
                expect(page.locator('#engine-title')).to_have_text('Adapt the handshake, not the content.')
                page.locator('#engine-play').click()
                expect(page.locator('#engine-step')).to_have_value('3',timeout=6000)
                expect(page.locator('#engine-play')).to_have_attribute('aria-pressed','false')
                report['interactions'].append('Engine stages, exact source links, query deep-link reload and finite playback')

                page.locator('#tab-privacy').click()
                expect(page.locator('html')).to_have_attribute('data-page','privacy')
                page.wait_for_timeout(420)
                page.locator('#privacy-resolver').click()
                expect(page.locator('#privacy-title')).to_have_text('Encrypted transport. A visible query.')
                page.go_back()
                expect(page.locator('html')).to_have_attribute('data-page','engineering')
                page.go_forward()
                expect(page.locator('html')).to_have_attribute('data-page','privacy')
                report['interactions'].append('Native history back/forward and recipient-specific privacy content')

                # Click before the previous transition is finished; the final choice wins.
                page.evaluate("document.querySelector('#tab-controls').click();document.querySelector('#tab-engineering').click();document.querySelector('#tab-guide').click()")
                expect(page.locator('html')).to_have_attribute('data-page','guide')
                page.wait_for_timeout(500)
                assert page.locator('.pane:not([hidden])').count()==1
                assert page.url.startswith(base+'guide/')
                report['interactions'].append('Interrupted transitions settle on the last selected tab')

                # Palette excludes arbitrary HTML and closes on one Escape, including search inputs.
                page.keyboard.press('Control+k')
                expect(page.locator('#search-dialog')).to_be_visible()
                page.locator('#site-search').fill('<img src=x onerror=alert(1)>')
                expect(page.locator('#search-empty')).to_be_visible()
                assert page.locator('#search-dialog img').count()==0
                page.keyboard.press('Escape')
                expect(page.locator('#search-dialog')).not_to_be_visible()
                page.keyboard.press('Control+k')
                page.locator('#site-search').fill('Engine')
                page.keyboard.press('Enter')
                expect(page.locator('html')).to_have_attribute('data-page','engineering')
                page.wait_for_timeout(420)
                report['interactions'].append('Search palette, keyboard selection, empty results and no HTML injection')

                page.locator('#theme-toggle').click()
                expect(page.locator('html')).to_have_attribute('data-theme','dark')
                page.reload(wait_until='networkidle')
                expect(page.locator('html')).to_have_attribute('data-theme','dark')
                page.locator('#motion-toggle').click()
                expect(page.locator('html')).to_have_attribute('data-motion','off')
                page.reload(wait_until='networkidle')
                expect(page.locator('html')).to_have_attribute('data-motion','off')
                report['interactions'].append('Theme and motion preferences persist across reloads')

                page.locator('#tab-guide').click()
                expect(page.locator('html')).to_have_attribute('data-page','guide')
                page.locator('#developer summary').click()
                expect(page.locator('#build-command')).to_be_visible()
                page.locator('[data-copy]').click()
                expect(page.locator('#toast')).to_be_visible(timeout=4000)
                sample=b'Tunnel HTTPS website checksum regression fixture. Not an APK.'
                digest=hashlib.sha256(sample).hexdigest()
                page.locator('#expected-hash').fill(digest)
                page.locator('#apk-file').set_input_files({'name':'fixture.apk','mimeType':'application/vnd.android.package-archive','buffer':sample})
                page.locator('#verify-button').click()
                expect(page.locator('#hash-result')).to_have_attribute('data-state','match')
                page.locator('#expected-hash').fill('0'*64)
                page.locator('#verify-button').click()
                expect(page.locator('#hash-result')).to_have_attribute('data-state','mismatch')
                page.locator('#expected-hash').fill('not-a-hash')
                page.locator('#verify-button').click()
                expect(page.locator('#hash-result')).to_have_attribute('data-state','error')
                expect(page.locator('#expected-hash')).to_have_attribute('aria-invalid','true')
                page.locator('#expected-hash').fill('')
                page.locator('#verify-button').click()
                expect(page.locator('#hash-result')).to_have_attribute('data-state','calculated')
                report['interactions'].append('Collapsed source commands, bounded copy feedback and real SHA-256 positive/negative/invalid/empty cases')
                assert not errors,errors
                context.close()

                # No View Transitions API: same state changes and ordinary addresses still work.
                context=browser.new_context(viewport={'width':1440,'height':960})
                context.add_init_script('document.startViewTransition=undefined')
                page=context.new_page();page.goto(base,wait_until='networkidle')
                page.locator('#tab-controls').click()
                expect(page.locator('html')).to_have_attribute('data-page','controls')
                page.evaluate("document.querySelector('#tab-privacy').click();document.querySelector('#tab-guide').click()")
                expect(page.locator('html')).to_have_attribute('data-page','guide')
                report['interactions'].append('Fallback animation without the View Transitions API, including interruption')
                context.close()

                context=browser.new_context(viewport={'width':390,'height':844},reduced_motion='reduce')
                page=context.new_page();page.goto(base+'ko/',wait_until='networkidle')
                expect(page.locator('html')).to_have_attribute('data-motion','off')
                page.locator('#tab-home').press('ArrowRight')
                expect(page.locator('html')).to_have_attribute('data-page','controls')
                page.locator('#tab-controls').press('End')
                expect(page.locator('html')).to_have_attribute('data-page','guide')
                assert page.url.startswith(base+'ko/guide/')
                page.locator('#language-link').click()
                expect(page.locator('html')).to_have_attribute('lang','en')
                expect(page.locator('html')).to_have_attribute('data-page','guide')
                assert page.url.startswith(base+'guide/')
                # Browser policy, tested on the deployed artifact as well.
                page.evaluate("const s=document.createElement('script');s.textContent='window.__cspProbe=1';document.body.appendChild(s)")
                assert page.evaluate('window.__cspProbe') is None
                assert page.evaluate("async () => { try {await fetch('https://example.com/'); return false;} catch (_) {return true;} }")
                report['interactions'].append('Mobile roving tabs, reduced motion, language route preservation and strict CSP')
                context.close()

                context=browser.new_context(java_script_enabled=False,viewport={'width':390,'height':844})
                page=context.new_page()
                for name, route in ROUTES.items():
                    page.goto(base+route,wait_until='networkidle')
                    expect(page.locator('.pane:not([hidden])')).to_have_attribute('data-panel',name)
                    expect(page.locator('.pane:not([hidden]) h1')).to_be_visible()
                    assert page.locator('.main-tab').count()==5
                page.locator('#developer summary').click()
                expect(page.locator('#build-command')).to_be_visible()
                report['interactions'].append('No-JavaScript direct pages, real navigation links and native disclosures')
                context.close()
                browser.close()
        report['status']='PASS'
    except Exception as error:
        report['status']='FAIL';report['error']=str(error)
        try:
            if page: page.screenshot(path=str(out/'FAILURE.png'))
        except Exception: pass
        raise
    finally:
        if server: server.shutdown()
        (out/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
        print(json.dumps({k:v for k,v in report.items() if k not in ('layouts','accessibility','public_files')}|{'layout_count':len(report['layouts']),'accessibility_count':len(report['accessibility']),'public_file_count':len(report['public_files'])},ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--root',type=Path,default=Path('_site'))
    parser.add_argument('--out',type=Path,default=Path('workspace-review'))
    parser.add_argument('--url')
    args=parser.parse_args()
    run(args.root,args.out,args.url)
