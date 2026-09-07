#!/usr/bin/env python3
"""Exercise the actual rendered website. Tests never certify Android networking."""
from __future__ import annotations
import argparse,functools,hashlib,http.server,json,os,re,tempfile,threading,time,urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright,expect

PAGES={'home':'','engineering':'engineering/','guide':'guide/','privacy':'privacy/'}
class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self,*args): pass

def static(root:Path):
    from html.parser import HTMLParser
    class Parser(HTMLParser):
        def handle_starttag(self,tag,attrs):
            a=dict(attrs)
            for key in ('href','src'):
                value=a.get(key,'')
                if value.startswith('/Tunnel-HTTPS/'):
                    path=value[len('/Tunnel-HTTPS/'):].split('#')[0].split('?')[0]
                    target=root/path
                    if value.endswith('/') or not path: target=target/'index.html'
                    assert target.exists(),str(target)
    count=0
    for f in root.rglob('*.html'):
        text=f.read_text();Parser().feed(text);count+=1
        assert '<h1' in text and 'width=device-width' in text
        assert 'maximum-scale=1' not in text
    assert count==9,count
    for f in root.glob('assets/conduit/*.js'):
        assert 'TurboConnectionLearning.kt' not in f.read_text()
    return count

def live_bytes(root,base,out):
    version=json.loads((root/'site-version.json').read_text())
    paths=['index.html','engineering/index.html','guide/index.html','privacy/index.html','ko/index.html','ko/engineering/index.html','ko/guide/index.html','ko/privacy/index.html','site-version.json']
    paths += ['assets/conduit/'+n for n in version['assets'].values()]
    checks=[]
    for path in paths:
        route=path.removesuffix('index.html') if path.endswith('index.html') else path
        expected=hashlib.sha256((root/path).read_bytes()).hexdigest()
        for attempt in range(20):
            try:
                req=urllib.request.Request(base+route,headers={'User-Agent':'TunnelHTTPS-public-website-verification','Cache-Control':'no-cache'})
                with urllib.request.urlopen(req,timeout=20) as r:
                    data=r.read(2*1024*1024);status=r.status
                actual=hashlib.sha256(data).hexdigest()
                if actual==expected:
                    checks.append({'url':base+route,'status':status,'sha256':actual});break
            except OSError:pass
            time.sleep(3)
        else: raise AssertionError('Public bytes do not match tested build: '+base+route)
    (out/'publication.json').write_text(json.dumps({'status':'PASS','design':version['design'],'source_commit':version['source_commit'],'anonymous_byte_checks':checks},indent=2)+'\n')
    return checks

def run(root:Path,out:Path,url=None):
    out.mkdir(parents=True,exist_ok=True)
    result={'status':'RUNNING','scope':'Website presentation, navigation and browser-only interactions. Not APK signing, device tests or network benchmarks.','layouts':[],'interactions':[],'accessibility':[]}
    server=None
    try:
        result['static_pages']=static(root)
        if url:result['public_bytes']=live_bytes(root,url,out)
        with tempfile.TemporaryDirectory() as d:
            if not url:
                (Path(d)/'Tunnel-HTTPS').symlink_to(root.resolve(),target_is_directory=True)
                server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Quiet,directory=d));threading.Thread(target=server.serve_forever,daemon=True).start()
                base=f'http://127.0.0.1:{server.server_port}/Tunnel-HTTPS/'
            else: base=url
            with sync_playwright() as pw:
                executable=os.getenv('BROWSER_EXECUTABLE')
                browser=pw.chromium.launch(**({'executable_path':executable,'args':['--no-sandbox']} if executable else {}))
                for lang in ('en','ko'):
                    for theme in ('light','dark'):
                        for width in ((390,1440) if url else (320,390,768,1440)):
                            context=browser.new_context(viewport={'width':width,'height':960},reduced_motion='reduce')
                            context.add_init_script(f"localStorage.setItem('tunnel.conduit.theme','{theme}')")
                            page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
                            for name,path in PAGES.items():
                                r=page.goto(base+('ko/' if lang=='ko' else '')+path,wait_until='networkidle',timeout=30000)
                                assert r and r.status==200,(name,r.status if r else None)
                                expect(page.locator('html')).to_have_attribute('data-ready','true')
                                expect(page.locator('html')).to_have_attribute('data-design','conduit-studio-v3')
                                expect(page.locator('body')).to_have_attribute('data-page',name)
                                assert page.locator('.view:not([hidden])').count()==1
                                assert page.locator('.view:not([hidden]) h1').is_visible()
                                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth+1'),(name,theme,width)
                                text=page.locator('body').inner_text()
                                assert 'flowchart LR' not in text and './gradlew' not in text and '@keyframes' not in text
                                if width in (390,1440):page.screenshot(path=str(out/f'{lang}-{name}-{theme}-{width}.png'),full_page=True)
                                if os.getenv('AXE_PATH') and width==1440:
                                    page.evaluate(Path(os.environ['AXE_PATH']).read_text())
                                    audit=page.evaluate("async () => {const r=await axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}});return r.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.map(n=>n.target)}))}")
                                    result['accessibility'].append({'lang':lang,'page':name,'theme':theme,'violations':audit})
                                    assert not audit,(name,lang,theme,audit)
                                result['layouts'].append({'lang':lang,'page':name,'theme':theme,'width':width,'status':'PASS'})
                            assert not errors,errors
                            context.close()
                context=browser.new_context(viewport={'width':1440,'height':960})
                page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
                page.goto(base,wait_until='networkidle')
                page.locator('.desktop-nav [data-route="engineering"]').click()
                expect(page.locator('html')).to_have_attribute('data-transition','idle',timeout=10000)
                expect(page.locator('body')).to_have_attribute('data-page','engineering')
                assert page.url.endswith('engineering/')
                page.locator('#tab-tcp').click();page.locator('[data-step="3"]').click()
                expect(page.locator('#step-source span')).to_have_text('TurboTcpForwarder.kt')
                assert 'path=tcp' in page.url and 'step=4' in page.url
                page.reload(wait_until='networkidle');expect(page.locator('#tab-tcp')).to_have_attribute('aria-selected','true')
                expect(page.locator('#path-progress')).to_have_value('3')
                page.locator('#tab-tcp').press('ArrowRight');expect(page.locator('#tab-quic')).to_have_attribute('aria-selected','true')
                page.locator('#play-path').click();expect(page.locator('#path-progress')).to_have_value('3',timeout=7000);expect(page.locator('#play-path')).to_have_attribute('aria-pressed','false')
                page.locator('#tab-learning').click();page.locator('[data-step="2"]').click();expect(page.locator('#step-source span')).to_have_text('AegisLocal2Engine.kt')
                result['interactions'].append('Protocol tabs, keyboard, deep links, reload, finite replay and source links')
                # Interrupt a genuine curtain transition; the last navigation must win.
                page.locator('.desktop-nav [data-route="home"]').click();page.wait_for_timeout(50)
                page.locator('.desktop-nav [data-route="guide"]').click();expect(page.locator('html')).to_have_attribute('data-transition','idle',timeout=10000)
                expect(page.locator('body')).to_have_attribute('data-page','guide');assert page.url.endswith('/guide/')
                page.go_back();expect(page.locator('body')).to_have_attribute('data-page','home',timeout=10000)
                page.go_forward();expect(page.locator('body')).to_have_attribute('data-page','guide',timeout=10000)
                expect(page.locator('html')).to_have_attribute('data-transition','idle',timeout=10000)
                result['interactions'].append('Interruptible shutter navigation, real route URLs, browser Back and Forward')
                page.locator('.theme-button').click();expect(page.locator('html')).to_have_attribute('data-theme','dark');page.reload(wait_until='networkidle');expect(page.locator('html')).to_have_attribute('data-theme','dark')
                page.locator('.developer-drawer summary').click();page.locator('.copy-code').click();expect(page.locator('#toast')).to_be_visible(timeout=5000)
                result['interactions'].append('Persisted theme, native developer drawer and clipboard feedback')
                page.locator('#verify-drawer summary').click();fixture=b'conduit-browser-test-not-an-apk';digest=hashlib.sha256(fixture).hexdigest()
                page.locator('#expected-hash').fill(digest)
                page.locator('#apk-file').set_input_files({'name':'fixture.apk','mimeType':'application/vnd.android.package-archive','buffer':fixture})
                expect(page.locator('#hash-result')).to_have_attribute('data-result','match')
                page.locator('#expected-hash').fill('0'*64);expect(page.locator('#hash-result')).to_have_attribute('data-result','mismatch')
                page.locator('#expected-hash').fill('<img src=x onerror=alert(1)>');expect(page.locator('#hash-result')).to_have_attribute('data-result','error');assert page.locator('#hash-result img').count()==0
                page.locator('#expected-hash').fill('');expect(page.locator('#hash-result')).to_have_attribute('data-result','calculated')
                result['interactions'].append('Synthetic local checksum match, mismatch, invalid input and calculation-only states')
                page.evaluate("() => {let s=document.createElement('script');s.textContent='window.__unexpectedInline=true';document.body.appendChild(s)}")
                assert page.evaluate('window.__unexpectedInline') is None
                assert page.evaluate("async()=>{try{await fetch('https://example.com/');return false}catch(_){return true}}")
                result['interactions'].append('CSP rejects inline script injection and external runtime fetch')
                assert not errors,errors;context.close()
                context=browser.new_context(viewport={'width':390,'height':844},reduced_motion='reduce');page=context.new_page();page.goto(base,wait_until='networkidle')
                page.locator('.menu-button').click();expect(page.locator('#mobile-nav')).to_be_visible();page.keyboard.press('Escape');expect(page.locator('#mobile-nav')).to_be_hidden()
                page.locator('.menu-button').click();page.locator('#mobile-nav [data-route="engineering"]').click();expect(page.locator('body')).to_have_attribute('data-page','engineering');expect(page.locator('#mobile-nav')).to_be_hidden()
                assert page.evaluate("getComputedStyle(document.querySelector('#route-curtain')).display==='none'")
                page.locator('#language-link').click();expect(page.locator('html')).to_have_attribute('lang','ko');assert '/ko/engineering/' in page.url
                result['interactions'].append('Mobile menu, Escape, reduced motion and equivalent Korean route');context.close()
                context=browser.new_context(java_script_enabled=False,viewport={'width':390,'height':844});page=context.new_page()
                for name,path in PAGES.items():
                    page.goto(base+path,wait_until='networkidle');assert page.locator('.view:not([hidden]) h1').is_visible();assert page.locator('#mobile-nav').is_visible()
                page.goto(base+'guide/',wait_until='networkidle');page.locator('.developer-drawer summary').click();assert page.locator('.developer-drawer pre').is_visible()
                result['interactions'].append('No-JavaScript direct routes, mobile navigation and native source instructions');context.close();browser.close()
        result['status']='PASS'
    except Exception as e:
        result['status']='FAIL';result['failure']=str(e);raise
    finally:
        if server:server.shutdown()
        (out/'report.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
        print(json.dumps({'status':result['status'],'layouts':len(result['layouts']),'interaction_groups':len(result['interactions']),'accessibility_audits':len(result['accessibility'])},indent=2))

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('--root',default='_site',type=Path);a.add_argument('--out',default='conduit-results',type=Path);a.add_argument('--url');x=a.parse_args();run(x.root,x.out,x.url)
