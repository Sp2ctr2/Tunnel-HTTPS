"""Browser QA for the white product refinement, not Android acceptance testing."""
from pathlib import Path
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
import argparse, hashlib, json, os, tempfile, time, urllib.request
from playwright.sync_api import sync_playwright, expect

ROUTES={'home':'','controls':'controls/','engineering':'engineering/','privacy':'privacy/','guide':'guide/'}
class Quiet(SimpleHTTPRequestHandler):
    def log_message(self,*args):pass

def run(root,out,public=None):
    out.mkdir(parents=True,exist_ok=True)
    report={'status':'RUNNING','scope':'Website UI and publication only. Not Android networking or release certification.','layouts':[],'interactions':[],'accessibility':[],'public_files':[]}
    server=None;temp=None
    try:
        if public:
            base=public.rstrip('/')+'/'
            manifest=json.loads((root/'manifest.json').read_text())
            for name,digest in manifest['files'].items():
                for attempt in range(12):
                    try:
                        with urllib.request.urlopen(urllib.request.Request(base+name,headers={'User-Agent':'TunnelHTTPS-public-verification','Cache-Control':'no-cache'}),timeout=20) as r:body=r.read(4*1024*1024)
                        if hashlib.sha256(body).hexdigest()==digest:break
                    except OSError:pass
                    time.sleep(5)
                else:raise AssertionError('Public file differs: '+name)
                report['public_files'].append({'path':name,'sha256':digest,'status':'PASS'})
        else:
            temp=tempfile.TemporaryDirectory();(Path(temp.name)/'Tunnel-HTTPS').symlink_to(root.resolve(),target_is_directory=True)
            server=ThreadingHTTPServer(('127.0.0.1',0),partial(Quiet,directory=temp.name));Thread(target=server.serve_forever,daemon=True).start()
            base=f'http://127.0.0.1:{server.server_port}/Tunnel-HTTPS/'
        with sync_playwright() as p:
            browser=p.chromium.launch()
            for lang in ('en','ko'):
                for theme in ('light','dark'):
                    for width in (320,390,768,1440):
                        context=browser.new_context(viewport={'width':width,'height':900},reduced_motion='reduce')
                        context.add_init_script("localStorage.setItem('tunnel.product.theme',"+json.dumps(theme)+");")
                        page=context.new_page();errors=[]
                        page.on('pageerror',lambda e:errors.append(str(e)))
                        for name,path in ROUTES.items():
                            response=page.goto(base+('ko/' if lang=='ko' else '')+path,wait_until='networkidle');assert response.status==200
                            expect(page.locator('html')).to_have_attribute('data-scenes','ready')
                            expect(page.locator('html')).to_have_attribute('data-page',name)
                            expect(page.locator('html')).to_have_attribute('data-theme',theme)
                            assert page.locator('.product-scene:visible').count()==1
                            assert page.locator('.product-scene:visible h1').is_visible()
                            assert page.locator('canvas,iframe,#app-image,.workspace').count()==0
                            dims=page.evaluate("({w:innerWidth,d:document.documentElement.scrollWidth,p:document.querySelector('.product-scene:not([hidden])').scrollWidth})")
                            assert dims['d']<=width+1 and dims['p']<=width+1,(lang,name,width,dims)
                            visible=page.locator('body').inner_text()
                            assert './gradlew' not in visible and 'flowchart LR' not in visible
                            assert not errors,errors
                            if width in (390,1440):page.screenshot(path=str(out/f'{lang}-{name}-{theme}-{width}.png'))
                            if width==1440 and theme=='light' and os.environ.get('AXE_PATH'):
                                page.evaluate(Path(os.environ['AXE_PATH']).read_text())
                                result=page.evaluate("async()=>{const r=await axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}});return r.violations.map(v=>({id:v.id,nodes:v.nodes.map(n=>n.target)}));}")
                                report['accessibility'].append({'lang':lang,'page':name,'violations':result})
                                assert not result,(lang,name,result)
                            report['layouts'].append({'lang':lang,'theme':theme,'width':width,'page':name,'status':'PASS'})
                        context.close()
            # Short desktop: the product exhibit and benefits must never overlap.
            context=browser.new_context(viewport={'width':1440,'height':800},reduced_motion='reduce')
            page=context.new_page();page.goto(base,wait_until='networkidle')
            extent=page.evaluate("()=>{let a=document.querySelector('.feature-exhibit').getBoundingClientRect(),b=document.querySelector('.benefits').getBoundingClientRect();return {bottom:a.bottom,top:b.top}}")
            assert extent['bottom']<=extent['top'],extent
            page.screenshot(path=str(out/'home-short-1440-800.png'));context.close()
            # Exercise actual navigation; a persistent DOM marker proves no reload.
            context=browser.new_context(viewport={'width':1440,'height':900},record_video_dir=str(out/'video'),record_video_size={'width':1440,'height':900})
            page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(base,wait_until='networkidle');page.evaluate('window.__routeProbe=1')
            page.locator('#exhibit-filter').click();expect(page.locator('#query-value')).to_have_text('ads.example.org')
            page.locator('#exhibit-filter').press('ArrowRight');expect(page.locator('#query-value')).to_have_text('Turbo')
            page.locator('.desktop-nav [data-route=controls]').click();expect(page.locator('html')).to_have_attribute('data-page','controls')
            expect(page).to_have_url(base+'controls/');assert page.evaluate('window.__routeProbe')==1
            page.locator('#choice-filter').click();expect(page.locator('#choice-panel')).to_have_attribute('aria-labelledby','choice-filter')
            page.locator('#behavior-switch').click();expect(page.locator('#behavior-switch')).to_have_attribute('aria-checked','false')
            expect(page.locator('#behavior-title')).to_have_text('Blocklist not applied')
            page.locator('.desktop-nav [data-route=engineering]').click();expect(page.locator('html')).to_have_attribute('data-page','engineering')
            page.locator('#tab-dns').click();expect(page.locator('#panel-dns')).to_be_visible();assert page.url.endswith('/engineering/#dns')
            page.locator('#capability-search').fill('nothingmatches');expect(page.locator('#no-results')).to_be_visible()
            page.locator('#capability-search').fill('DNS');assert page.locator('[data-capability]:visible').count()>=1
            page.locator('.desktop-nav [data-route=privacy]').click();expect(page.locator('html')).to_have_attribute('data-page','privacy')
            page.locator('.desktop-nav [data-route=guide]').click();expect(page.locator('html')).to_have_attribute('data-page','guide')
            page.go_back();expect(page.locator('html')).to_have_attribute('data-page','privacy')
            page.go_forward();expect(page.locator('html')).to_have_attribute('data-page','guide')
            assert page.locator('#locale-link').get_attribute('href').endswith('/ko/guide/')
            report['interactions'].append('In-place tabs without document reload, exhibits, keyboard feature tabs, toggles, engine/search, history and language route')
            page.locator('summary').filter(has_text='Build and test locally').click();expect(page.locator('#build-command')).to_be_visible()
            page.locator('[data-copy=build-command]').click();expect(page.locator('#toast')).to_be_visible(timeout=4000)
            fixture=b'test-fixture-not-an-apk';digest=hashlib.sha256(fixture).hexdigest()
            page.locator('#apk-file').set_input_files({'name':'fixture.apk','mimeType':'application/octet-stream','buffer':fixture})
            expect(page.locator('#hash-result')).to_have_attribute('data-state','calculated')
            page.locator('#expected-hash').fill(digest);expect(page.locator('#hash-result')).to_have_attribute('data-state','match')
            page.locator('#expected-hash').fill('0'*64);expect(page.locator('#hash-result')).to_have_attribute('data-state','mismatch')
            page.locator('#expected-hash').fill('invalid');expect(page.locator('#hash-result')).to_have_attribute('data-state','error')
            report['interactions'].append('Collapsed code, bounded copy feedback and synthetic local SHA-256 comparison states')
            page.locator('.theme-toggle').click();expect(page.locator('html')).to_have_attribute('data-theme','dark')
            page.reload(wait_until='networkidle');expect(page.locator('html')).to_have_attribute('data-theme','dark')
            page.locator('#motion-setting').click();expect(page.locator('html')).to_have_attribute('data-motion','off')
            # Rapid input must not queue stale navigation callbacks.
            page.evaluate("()=>{for(const n of ['home','privacy','engineering','controls'])document.querySelector('.desktop-nav [data-route='+n+']').click()}")
            expect(page.locator('html')).to_have_attribute('data-page','controls');page.wait_for_timeout(600);assert page.url.endswith('controls/')
            report['interactions'].append('Persisted theme, reduced-motion preference and last-navigation-wins')
            assert not errors,errors;context.close()
            # Touch layout, navigation and keyboard dismissal.
            context=browser.new_context(viewport={'width':390,'height':844},reduced_motion='reduce')
            page=context.new_page();page.goto(base+'ko/',wait_until='networkidle')
            page.locator('.menu-toggle').click();expect(page.locator('#mobile-nav')).to_be_visible()
            page.locator('#mobile-nav [data-route=controls]').click();expect(page.locator('html')).to_have_attribute('data-page','controls');expect(page.locator('#mobile-nav')).to_be_hidden()
            page.locator('.menu-toggle').click();page.keyboard.press('Escape');expect(page.locator('#mobile-nav')).to_be_hidden()
            expect(page.locator('html')).to_have_attribute('data-motion','off');context.close()
            report['interactions'].append('Korean mobile navigation, Escape and system reduced motion')
            context=browser.new_context(java_script_enabled=False,viewport={'width':390,'height':844})
            page=context.new_page()
            for name,path in ROUTES.items():
                page.goto(base+path,wait_until='networkidle');expect(page.locator('#scene-'+name+' h1')).to_be_visible();expect(page.locator('#mobile-nav')).to_be_visible()
            context.close();report['interactions'].append('Native direct routes and navigation with JavaScript disabled')
            context=browser.new_context();page=context.new_page();page.goto(base,wait_until='networkidle')
            page.evaluate("()=>{let e=document.createElement('script');e.textContent='window.__forbidden=1';document.body.appendChild(e)}")
            assert page.evaluate('window.__forbidden') is None
            assert page.evaluate("async()=>{try{await fetch('https://example.com');return false}catch(_){return true}}")
            context.close();report['interactions'].append('CSP rejects inline script injection and cross-origin runtime fetch')
            browser.close()
        report['status']='PASS'
    except Exception as e:report['status']='FAIL';report['failure']=str(e);raise
    finally:
        if server:server.shutdown()
        if temp:temp.cleanup()
        (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps({k:(len(v) if isinstance(v,list) else v) for k,v in report.items()},indent=2))
if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--root',type=Path,required=True);ap.add_argument('--out',type=Path,required=True);ap.add_argument('--url');a=ap.parse_args();run(a.root,a.out,a.url)
