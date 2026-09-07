"""Refine the approved white product site into addressable, in-place scenes.
The product copy and technical boundaries come from the original product builder.
No Android, signing or network execution code is changed by this build.
"""
from pathlib import Path
from html import escape
import argparse, hashlib, importlib.util, json, os, re

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('product_baseline', HERE/'build.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
ROUTES={'home':'','controls':'controls/','engineering':'engineering/','privacy':'privacy/','guide':'guide/'}
DESIGN='white-product-scenes-1'

def element(text,start,end):
    a=text.index(start);b=text.index(end,a)
    return text[a:b]

def showpiece(ko):
    def t(en,kr):return kr if ko else en
    lock='<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/></svg>'
    return f'''<aside class="feature-exhibit" aria-label="{t('Explore the product','제품 기능 살펴보기')}">
      <div class="exhibit-tabs" role="tablist" aria-label="{t('Feature preview','기능 미리보기')}">
      <button id="exhibit-dns" role="tab" type="button" aria-controls="exhibit-content" aria-selected="true" data-exhibit="dns">{t('Encrypted DNS','암호화 DNS')}</button>
      <button id="exhibit-filter" role="tab" type="button" aria-controls="exhibit-content" aria-selected="false" tabindex="-1" data-exhibit="filter">{t('Filtering','필터링')}</button>
      <button id="exhibit-local" role="tab" type="button" aria-controls="exhibit-content" aria-selected="false" tabindex="-1" data-exhibit="local">{t('Local learning','로컬 학습')}</button></div>
      <div id="exhibit-content" class="exhibit-content" role="tabpanel" aria-labelledby="exhibit-dns" tabindex="0" data-example="dns">
        <div class="exhibit-back"><span>{t('Your connection, considered.','내 연결을 위한 선택.')}</span><span>Tunnel HTTPS</span></div>
        <div class="exhibit-sheet">
          <div class="sheet-top"><span class="sheet-icon">{lock}</span><span id="exhibit-kicker">DNS over HTTPS</span><span class="sheet-scope">{t('On-device policy','기기 내 정책')}</span></div>
          <h2 id="exhibit-heading">{t('Every query.\nA little more private.','DNS 질의에도,\n한 겹 더 보호.')}</h2>
          <div class="query-card"><div class="query-top"><span id="query-label">{t('Example DNS query','DNS 질의 예시')}</span><span id="query-tag">HTTPS</span></div><strong id="query-value" translate="no">example.org</strong><div class="query-bottom"><span class="query-lock">{lock}</span><span id="query-description">{t('Encrypted to your chosen resolver','선택한 리졸버까지 전송 암호화')}</span></div></div>
          <div class="sheet-detail"><span id="exhibit-detail-title">{t('Your provider. Your choice.','제공자도 직접 선택.')}</span><p id="exhibit-detail">{t('The resolver receives the query. The transport to it is encrypted.','리졸버는 질의를 수신합니다. 그곳까지의 전송을 암호화합니다.')}</p></div>
        </div>
        <div class="exhibit-receipt"><span>{lock}<span id="exhibit-foot">{t('No developer-operated VPN gateway','개발자 운영 VPN 게이트웨이 없음')}</span></span><span aria-hidden="true">↗</span></div>
      </div>
      <p class="exhibit-note">{t('A feature illustration. No traffic is sent.','기능을 설명하는 예시입니다. 트래픽을 전송하지 않습니다.')}</p>
    </aside>'''

def features(ko):
    def t(a,b):return b if ko else a
    choices=[('dns',t('Encrypt DNS','DNS 암호화'),t('Choose how names are resolved.','이름을 찾는 방법부터 선택하세요.')),
             ('filter',t('Filter domains','도메인 필터링'),t('Apply the local blocklist.','로컬 차단 목록을 적용하세요.')),
             ('bypass',t('Choose the apps','앱별 경로 선택'),t('Keep selected apps outside the local path.','선택한 앱은 로컬 경로 밖으로.'))]
    return f'''<section class="wrap feature-page page-intro"><div class="feature-copy"><p class="section-label">{t('Make the connection yours','내 연결을 직접 선택하세요')}</p><h1>{t('Small choices.<br>More control.','작은 선택이,<br>더 큰 제어로.')}</h1><p class="lead">{t('Choose what takes the local path.<br>Understand what changes.','로컬 경로로 보낼 것을 선택하고.<br>무엇이 달라지는지 확인하세요.')}</p><p class="feature-intro">{t('Explore the behavior below. These examples do not change a device or make network requests.','아래에서 동작 차이를 살펴보세요. 기기 설정을 바꾸거나 네트워크를 요청하지 않는 예시입니다.')}</p><div class="choice-list" role="tablist" aria-label="{t('Network features','네트워크 기능')}">'''+''.join(f'<button type="button" role="tab" id="choice-{key}" aria-controls="choice-panel" aria-selected="{str(i==0).lower()}" tabindex="{0 if i==0 else -1}" data-choice="{key}"><span><strong>{name}</strong><small>{desc}</small></span><span class="choice-arrow" aria-hidden="true">↗</span></button>' for i,(key,name,desc) in enumerate(choices))+f'''</div></div><article id="choice-panel" class="choice-panel" role="tabpanel" aria-labelledby="choice-dns" tabindex="0"><div class="choice-panel-top"><span id="choice-category">DNS over HTTPS</span><span class="illustration-label">{t('Interactive example','인터랙티브 예시')}</span></div><h2 id="choice-title">{t('Change the transport.<br>Keep your choice.','전송은 암호화.<br>선택은 그대로.')}</h2><p id="choice-description">{t('DNS over HTTPS encrypts queries on their way to the configured resolver.','DNS over HTTPS는 설정한 리졸버까지 전송하는 질의를 암호화합니다.')}</p><div class="setting-row"><div><strong id="setting-label">{t('Encrypted DNS','DNS 암호화')}</strong><small id="setting-note">{t('Illustrative setting','동작 설명용 설정')}</small></div><button type="button" role="switch" aria-checked="true" id="behavior-switch" aria-labelledby="setting-label"><span></span></button></div><div class="behavior-result" role="status"><span class="result-label">{t('What changes','달라지는 점')}</span><h3 id="behavior-title">{t('HTTPS to your resolver','리졸버까지 HTTPS로')}</h3><p id="behavior-description">{t('The selected provider still receives the query. Encryption is not anonymity.','선택한 제공자는 여전히 질의를 수신합니다. 암호화가 익명화를 의미하지는 않습니다.')}</p></div><a id="behavior-source" class="source-reading" href="{base.REPO}/blob/main/app/src/main/java/com/tunnelvpn/app/DohResolver.kt">{t('Read the implementation','실제 구현 읽기')}<span aria-hidden="true">↗</span></a></article></section>'''

def render(lang,page,release,assets):
    ko=lang=='ko'
    def t(a,b):return b if ko else a
    depth=(1 if ko else 0)+(0 if page=='home' else 1)
    root='../'*depth or './'
    local=lambda name: root+('ko/' if ko else '')+ROUTES[name]
    baseline_assets={n:assets[n] for n in ('boot.js','app.js','style.css')}
    main={}
    for name in ('home','engineering','privacy','guide'):
        doc=base.render(lang,name,release,baseline_assets)
        content=element(doc,'<main id="main">','</main>')[len('<main id="main">'):]
        # URLs are frozen against each baseline route before combining its content.
        from urllib.parse import urljoin
        origin='/Tunnel-HTTPS/'+('ko/' if ko else '')+ROUTES[name]
        content=re.sub(r'href="([^"]+)"',lambda m:'href="'+escape(urljoin(origin,m[1]),quote=True)+'"',content)
        if name=='home':
            content=content[:content.index('<section class="under-section">')]
            start=content.index('<div class="app-tabs"');end=content.index('</div>\n          <figure',start)
            content=content[:start]+content[end:]
            start=content.index('<figure class="product-figure">');end=content.index('</figure>',start)+len('</figure>')
            content=content[:start]+showpiece(ko)+content[end:]
            content=content.replace('Encrypted DNS. Fewer unwanted domains.<br>A connection you can understand.','Encrypted DNS. Thoughtful filtering.<br>More control, right on your device.').replace('암호화된 DNS. 원하지 않는 도메인 차단.<br>내가 이해하고 선택하는 연결.','암호화된 DNS. 필요한 만큼의 필터링.<br>내 기기 안에서, 내가 선택하는 연결.')
        content=re.sub(r'<h1>', '<h1 tabindex="-1">',content)
        main[name]=content
    main['controls']=features(ko).replace('<h1>','<h1 tabindex="-1">')
    labels={'home':t('Product','제품'),'controls':t('Features','기능'),'engineering':t('Under the hood','기술'),'privacy':t('Privacy','개인정보'),'guide':t('Get started','시작하기')}
    tabs=''.join(f'<a class="page-tab" data-route="{n}" href="{local(n)}"'+(' aria-current="page"' if n==page else '')+f'><span>{labels[n]}</span></a>' for n in ROUTES)
    scenes=''.join(f'<div class="product-scene" id="scene-{n}" data-scene="{n}"'+('' if n==page else ' hidden inert')+'>'+main[n]+'</div>' for n in ROUTES)
    csp="default-src 'none'; img-src 'self'; style-src 'self'; script-src 'self'; connect-src 'none'; font-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'"
    title=t('Your connection. In your hands.','내 연결은, 내 손안에.') if page=='home' else labels[page]
    description=t('Local network controls for Android. Encrypted DNS, domain filtering and open-source packet handling.','Android 기기 안에서 연결을 제어하세요. 암호화된 DNS, 도메인 필터링과 오픈소스 패킷 처리.')
    return f'''<!doctype html><html lang="{lang}" data-page="{page}" data-root="{root}" data-design="{DESIGN}" data-theme="light"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="{csp}"><meta name="description" content="{description}"><meta name="theme-color" content="#ffffff"><meta name="color-scheme" content="light dark"><title>{title} — Tunnel HTTPS</title><link rel="canonical" href="{base.BASE+('ko/' if ko else '')+ROUTES[page]}"><link rel="alternate" hreflang="en" href="{base.BASE+ROUTES[page]}"><link rel="alternate" hreflang="ko" href="{base.BASE+'ko/'+ROUTES[page]}"><meta property="og:title" content="Tunnel HTTPS"><meta property="og:description" content="{description}"><meta property="og:type" content="website"><link rel="icon" href="{root}favicon.svg"><script src="{root+assets['boot.js']}"></script><link rel="stylesheet" href="{root+assets['style.css']}"><script defer src="{root+assets['app.js']}"></script><script defer src="{root+assets['scenes.js']}"></script></head><body><a class="skip-link" href="#main">{t('Skip to content','본문으로 건너뛰기')}</a><header class="site-header"><div class="wrap header-inner"><a class="brand" data-route="home" href="{local('home')}" translate="no">Tunnel HTTPS<span class="brand-caption">Android</span></a><nav class="desktop-nav" aria-label="{t('Main navigation','주 탐색')}">{tabs}</nav><div class="header-tools"><a class="locale-link" id="locale-link" lang="{'en' if ko else 'ko'}" href="{root+('' if ko else 'ko/')+ROUTES[page]}">{t('한국어','English')}</a><button type="button" class="theme-toggle" aria-label="{t('Switch theme','테마 전환')}" aria-pressed="false"><svg class="moon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 15A8 8 0 0 1 9 4a8 8 0 1 0 11 11Z"/></svg><svg class="sun" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></svg></button><button type="button" class="menu-toggle" aria-controls="mobile-nav" aria-expanded="false">{t('Menu','메뉴')}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 8h16M4 16h16"/></svg></button></div></div><nav id="mobile-nav" class="wrap mobile-nav" aria-label="{t('Mobile navigation','모바일 탐색')}">{tabs}</nav></header><main id="main">{scenes}</main><footer class="wrap product-footer"><span>{t('Open source. On your device.','오픈소스. 당신의 기기 안에서.')}</span><div><button type="button" id="motion-setting" aria-pressed="false">{t('Reduce motion','동작 줄이기')}</button><a href="{base.REPO}/blob/main/LICENSE">Apache-2.0</a><a href="{base.REPO}">GitHub <span aria-hidden="true">↗</span></a></div></footer><div id="toast" role="status" aria-live="polite" hidden></div></body></html>'''

def build(out,resolve):
    out.mkdir(parents=True,exist_ok=True);(out/'assets').mkdir(exist_ok=True)
    assets={}
    for name in ('boot.js','app.js','style.css','scenes.js'):
        data=(HERE/name).read_bytes()
        if name=='style.css':data+=b'\n'+(HERE/'refinement.css').read_bytes()
        digest=hashlib.sha256(data).hexdigest()[:12];stem,ext=name.rsplit('.',1);target=f'assets/{stem}.{digest}.{ext}'
        (out/target).write_bytes(data);assets[name]=target
    release=base.read_release() if resolve else {'available':False,'url':base.REPO+'/releases'}
    for lang in ('en','ko'):
        for name,path in ROUTES.items():
            p=out/('ko' if lang=='ko' else '')/path/'index.html';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(render(lang,name,release,assets),encoding='utf-8')
    (out/'favicon.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="#202020"/><path d="M16 19h32v7H36v23h-8V26H16Z" fill="white"/></svg>')
    (out/'.nojekyll').touch()
    (out/'404.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Page not found — Tunnel HTTPS</title><h1>Page not found</h1><p>This address is not part of the website.</p><a href="/Tunnel-HTTPS/">Return to Tunnel HTTPS</a></html>')
    (out/'sitemap.xml').write_text('<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'+''.join('<url><loc>'+base.BASE+lang+path+'</loc></url>' for lang in ('','ko/') for path in ROUTES.values())+'</urlset>')
    report={'design':DESIGN,'commit':os.environ.get('GITHUB_SHA','local'),'baseline':'8738f3a7 white product design','routes':10,'release_available':release.get('available',False),'files':{p.relative_to(out).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in out.rglob('*') if p.is_file() and p.name!='manifest.json'}}
    (out/'manifest.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'design':DESIGN,'routes':10,'release_available':release.get('available',False)}))
if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,default=HERE.parents[1]/'_site');ap.add_argument('--resolve-release',action='store_true');args=ap.parse_args();build(args.output,args.resolve_release)
