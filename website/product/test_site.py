"""Website QA only: content, controls, access, and deployed bytes; not Android runtime QA."""
from pathlib import Path
from urllib.parse import urljoin, urlparse, unquote
from html.parser import HTMLParser
import argparse, contextlib, functools, hashlib, http.server, json, os, tempfile, threading, time, urllib.request
from playwright.sync_api import sync_playwright, expect

class Links(HTMLParser):
    def __init__(self):super().__init__();self.urls=[]
    def handle_starttag(self,tag,attrs):
        data=dict(attrs)
        for key in ('href','src','data-src'):
            if data.get(key):self.urls.append(data[key])

def check_files(root):
    count=0
    for file in root.rglob('*.html'):
        if file.name=='404.html':continue
        parser=Links();parser.feed(file.read_text())
        for url in parser.urls:
            parsed=urlparse(url)
            if parsed.scheme or url.startswith('#'):continue
            target=(file.parent/unquote(parsed.path)).resolve()
            if target.is_dir():target=target/'index.html'
            assert target.is_relative_to(root.resolve()),(file,url,'outside site')
            assert target.is_file(),(file,url,'missing')
            count+=1
    return count

@contextlib.contextmanager
def local_server(root):
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self,*_):pass
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp)/'Tunnel-HTTPS').symlink_to(root.resolve(),target_is_directory=True)
        srv=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Quiet,directory=tmp))
        threading.Thread(target=srv.serve_forever,daemon=True).start()
        try:yield f'http://127.0.0.1:{srv.server_port}/Tunnel-HTTPS/'
        finally:srv.shutdown()

def live_bytes(root,base):
    records=[]
    # Normal anonymous URLs, not cache-busting variants, must serve the tested files.
    for path in sorted(root.rglob('*')):
        if not path.is_file() or path.name in ('.nojekyll','404.html'):continue
        rel=path.relative_to(root).as_posix()
        route=rel[:-10] if rel.endswith('index.html') else rel
        expected=hashlib.sha256(path.read_bytes()).hexdigest()
        for attempt in range(12):
            try:
                request=urllib.request.Request(urljoin(base,route),headers={'User-Agent':'TunnelHTTPS-public-verification'})
                with urllib.request.urlopen(request,timeout=20) as response:actual=hashlib.sha256(response.read(8*1024*1024)).hexdigest()
                if actual==expected:break
            except OSError:pass
            time.sleep(4)
        else:raise AssertionError('Public file differs from tested output: '+route)
        records.append({'path':route,'sha256':expected,'status':'PASS'})
    return records

def check_browser(base,out,report,live=False):
    with sync_playwright() as p:
        executable=os.environ.get('CHROMIUM_PATH')
        browser=p.chromium.launch(**({'executable_path':executable} if executable else {}))
        widths=(390,1440) if live else (320,390,768,1440)
        axe=Path(os.environ['AXE_PATH']).read_text() if os.environ.get('AXE_PATH') and not live else None
        for lang in ('en','ko'):
            for theme in ('light','dark'):
                for width in widths:
                    ctx=browser.new_context(viewport={'width':width,'height':950},reduced_motion='reduce')
                    ctx.set_default_timeout(8000)
                    ctx.add_init_script(f"localStorage.setItem('tunnel.product.theme','{theme}')")
                    page=ctx.new_page();errors=[]
                    page.on('pageerror',lambda e:errors.append(str(e)))
                    for name,route in (('home',''),('engineering','engineering/'),('guide','guide/'),('privacy','privacy/')):
                        response=page.goto(base+('ko/' if lang=='ko' else '')+route,wait_until='load')
                        assert response and response.status==200
                        page.wait_for_function("document.documentElement.dataset.enhanced==='true'")
                        assert page.locator('html').get_attribute('data-design')=='product-20260907'
                        assert page.locator('html').get_attribute('data-theme')==theme
                        expect(page.locator('h1')).to_be_visible()
                        assert page.locator('canvas').count()==0,'Abstract canvas must not ship'
                        assert page.evaluate('document.documentElement.scrollWidth')<=width+1,(name,theme,width,'overflow')
                        assert './gradlew' not in page.locator('body').inner_text(),'Commands visible outside disclosure'
                        assert 'flowchart LR' not in page.locator('body').inner_text()
                        if name=='home':page.wait_for_function("document.querySelector('#app-image').complete && document.querySelector('#app-image').naturalWidth>0")
                        if width==1440 and axe:
                            page.evaluate(axe)
                            results=page.evaluate("async()=>{const r=await axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}});return r.violations.map(x=>({id:x.id,nodes:x.nodes.map(n=>n.target)}))}")
                            report['a11y'].append({'locale':lang,'page':name,'theme':theme,'violations':results})
                            assert not results,(lang,name,theme,results)
                        if width in (390,1440):page.screenshot(path=str(out/f'{"LIVE-" if live else ""}{lang}-{name}-{theme}-{width}.png'),full_page=True)
                        report['layouts'].append({'locale':lang,'page':name,'theme':theme,'width':width,'status':'PASS'})
                    assert not errors,errors
                    ctx.close()
        # Real actions, URL navigation and keyboard interaction in both languages.
        for lang in ('en','ko'):
            prefix=base+('ko/' if lang=='ko' else '')
            ctx=browser.new_context(viewport={'width':1440,'height':950})
            ctx.set_default_timeout(10000);page=ctx.new_page();errors=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(prefix,wait_until='load')
            page.locator('[data-shot="settings"]').click()
            expect(page.locator('#app-image')).to_have_attribute('src',__import__('re').compile('.*settings.png$'))
            page.locator('[data-shot="settings"]').press('ArrowLeft')
            expect(page.locator('[data-shot="activity"]')).to_have_attribute('aria-selected','true')
            expect(page.locator('#app-image')).to_have_attribute('src',__import__('re').compile('.*activity.png$'))
            page.locator('[data-zoom]').click();expect(page.locator('#image-dialog')).to_be_visible()
            page.keyboard.press('Escape');expect(page.locator('#image-dialog')).to_be_hidden()
            expect(page.locator('[data-zoom]')).to_be_focused()
            report['interactions'].append(lang+': real-screen tabs, keyboard selection, image viewer and focus restoration')
            page.locator('.desktop-nav a[href*="engineering/"]').click();page.wait_for_url('**/engineering/')
            page.locator('#tab-dns').click();expect(page.locator('#panel-dns')).to_be_visible()
            page.locator('#tab-dns').press('ArrowDown');expect(page.locator('#panel-tcp')).to_be_visible()
            assert page.url.endswith('#tcp')
            page.locator('#capability-search').fill('<img src=x onerror=alert(1)>')
            expect(page.locator('#no-results')).to_be_visible()
            assert page.locator('table img').count()==0
            page.locator('#capability-search').fill('DNS');expect(page.locator('[data-capability]:visible')).to_have_count(1)
            page.locator('#capability-search').fill('')
            report['interactions'].append(lang+': deep-linked topics, keyboard tabs, search and safe empty state')
            page.locator('.desktop-nav a[href*="guide/"]').click();page.wait_for_url('**/guide/')
            page.go_back();page.wait_for_url('**/engineering/**');expect(page.locator('#panel-tcp')).to_be_visible()
            page.go_forward();page.wait_for_url('**/guide/')
            page.locator('.theme-toggle').click();theme=page.locator('html').get_attribute('data-theme');page.reload();expect(page.locator('html')).to_have_attribute('data-theme',theme)
            report['interactions'].append(lang+': native route navigation, back/forward and theme persistence')
            page.locator('#developer summary').first.click();expect(page.locator('#build-command')).to_be_visible()
            page.locator('[data-copy="build-command"]').click();expect(page.locator('#toast')).to_be_visible(timeout=5000)
            report['interactions'].append(lang+': collapsed commands and bounded clipboard feedback')
            fixture=b'website-test-fixture-not-an-android-apk'
            page.locator('#apk-file').set_input_files({'name':'fixture.apk','mimeType':'application/vnd.android.package-archive','buffer':fixture})
            expect(page.locator('#hash-result')).to_have_attribute('data-state','calculated')
            page.locator('#expected-hash').fill(hashlib.sha256(fixture).hexdigest());expect(page.locator('#hash-result')).to_have_attribute('data-state','match')
            page.locator('#expected-hash').fill('0'*64);expect(page.locator('#hash-result')).to_have_attribute('data-state','mismatch')
            page.locator('#expected-hash').fill('not a sha256');expect(page.locator('#hash-result')).to_have_attribute('data-state','error')
            report['interactions'].append(lang+': local file hashing, positive/negative comparison and invalid input')
            assert not errors,errors;ctx.close()
        ctx=browser.new_context(viewport={'width':390,'height':844},reduced_motion='reduce')
        page=ctx.new_page();page.goto(base)
        page.locator('.menu-toggle').click();expect(page.locator('#mobile-nav')).to_be_visible()
        page.keyboard.press('Escape');expect(page.locator('#mobile-nav')).to_be_hidden()
        page.locator('.menu-toggle').click();page.locator('#mobile-nav a[href*="guide/"]').click();page.wait_for_url('**/guide/');expect(page.locator('#mobile-nav')).to_be_hidden()
        # Test the site's security boundary rather than claiming safety from design alone.
        page.evaluate("()=>{const script=document.createElement('script');script.textContent='window.inlineExecuted=true';document.body.appendChild(script);}")
        assert page.evaluate('window.inlineExecuted') is None
        assert page.evaluate("async()=>{try{await fetch('https://example.com/');return false;}catch(_){return true;}}")
        report['interactions'].append('Mobile navigation, first-Escape dismissal, reduced motion and CSP')
        ctx.close()
        ctx=browser.new_context(java_script_enabled=False,viewport={'width':390,'height':844})
        page=ctx.new_page()
        for route in ('','engineering/','guide/','ko/','ko/engineering/','ko/guide/'):
            page.goto(base+route);expect(page.locator('h1')).to_be_visible();expect(page.locator('#mobile-nav')).to_be_visible()
        expect(page.locator('#apk-file')).to_be_disabled();page.locator('#developer summary').first.click();expect(page.locator('#build-command')).to_be_visible()
        report['interactions'].append('No-JavaScript reading, native navigation, disclosures and disabled browser-only tool')
        ctx.close();browser.close()

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--root',type=Path,required=True);ap.add_argument('--out',type=Path,required=True);ap.add_argument('--url');args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    report={'status':'RUNNING','design':'product-20260907','scope':'Website only. Screens are renders of app source without Android bridge or network activity.','layouts':[],'interactions':[],'a11y':[]}
    try:
        report['local_links']=check_files(args.root)
        if args.url:
            base=args.url.rstrip('/')+'/'
            report['public_files']=live_bytes(args.root,base)
            check_browser(base,args.out,report,live=True)
        else:
            with local_server(args.root) as base:check_browser(base,args.out,report)
        report['status']='PASS'
    except Exception as e:
        report['status']='FAIL';report['failure']=repr(e);raise
    finally:
        (args.out/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
        print(json.dumps({'status':report['status'],'layouts':len(report['layouts']),'a11y_audits':len(report['a11y']),'interaction_groups':len(report['interactions']),'public_files':len(report.get('public_files',[]))}))
if __name__=='__main__':main()
