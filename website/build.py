#!/usr/bin/env python3
"""Build the Conduit website. Standard library only; never modifies the Android app."""
from __future__ import annotations
import argparse, hashlib, html, json, os, shutil, urllib.request, urllib.parse
from pathlib import Path

SOURCE=Path(__file__).resolve().parent
REPO='https://github.com/Sp2ctr2/Tunnel-HTTPS'
BASE='https://sp2ctr2.github.io/Tunnel-HTTPS/'
ROUTES={'home':'','engineering':'engineering/','guide':'guide/','privacy':'privacy/'}
DESIGN='conduit-studio-v3'

def esc(value): return html.escape(str(value),quote=True)
def resolve_release():
    result={'published':False,'url':REPO+'/releases'}
    try:
        request=urllib.request.Request('https://api.github.com/repos/Sp2ctr2/Tunnel-HTTPS/releases?per_page=30',headers={'User-Agent':'TunnelHTTPS-website','Accept':'application/vnd.github+json'})
        with urllib.request.urlopen(request,timeout=20) as response: items=json.loads(response.read(2*1024*1024))
        for item in items:
            if item.get('draft') or not item.get('published_at'): continue
            for asset in item.get('assets',[]):
                name=asset.get('name','')
                url=asset.get('browser_download_url','')
                if not name.endswith('.apk') or any(w in name.lower() for w in ('debug','unsigned','validation')): continue
                if not url.startswith(REPO+'/releases/download/') or asset.get('state')!='uploaded': continue
                digest=asset.get('digest','')
                return {'published':True,'url':item['html_url'],'apk_url':url,'version':item['tag_name'],'prerelease':bool(item.get('prerelease')),'sha256':digest.removeprefix('sha256:') if digest.startswith('sha256:') else '', 'size':asset.get('size',0)}
    except (OSError,ValueError,TypeError): pass
    return result

ICON='<svg viewBox="0 0 28 28" aria-hidden="true"><path d="M5 23V12a9 9 0 0 1 18 0v11M10 23V12a4 4 0 0 1 8 0v11"/></svg>'
ARROW='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg>'
EXTERNAL='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 18 18 6M6 6h12v12"/></svg>'

def render(lang,page,assets,release,revision):
    def t(en,ko): return ko if lang=='ko' else en
    def href(name,hash=''): return ('/Tunnel-HTTPS/ko/' if lang=='ko' else '/Tunnel-HTTPS/')+ROUTES[name]+hash
    def link(name,text,cls='',extra=''): return f'<a href="{href(name)}" data-route="{name}" class="{cls}" {extra}>{text}</a>'
    def source(name,label=None): return f'<a class="source-link" href="{REPO}/blob/main/app/src/main/java/com/tunnelvpn/app/{name}" target="_blank" rel="noopener noreferrer">{label or name}{EXTERNAL}</a>'
    def section(name,body): return f'<section class="view view-{name}" data-view="{name}"{ "" if page==name else " hidden inert"}>{body}</section>'
    labels={'home':t('Product','제품'),'engineering':t('Inside','엔진'),'guide':t('Get Tunnel','시작하기'),'privacy':t('Boundaries','보호 범위')}
    nav=''.join(link(n,f'<span>{labels[n]}</span>','nav-link', 'aria-current="page"' if page==n else '') for n in ROUTES)
    title={'home':t('Take the local route','연결을 내 기기 안으로'),'engineering':t('See what happens inside','연결의 내부를 살펴보세요'),'guide':t('Make the connection yours','내 기기에서 시작하세요'),'privacy':t('Local does not mean invisible','로컬 처리는 익명이 아닙니다')}[page]
    home=section('home',f'''
      <div class="home-hero">
        <div class="hero-copy">
          <p class="product-label">{t('Tunnel HTTPS for Android','Android용 Tunnel HTTPS')}</p>
          <h1 tabindex="-1">{t('Take the<br>local route.','연결을<br>내 기기 안으로.')}</h1>
          <p class="hero-description">{t('An open-source network engine that puts DNS, packet handling and connection strategy on your device. Not on our VPN server.','DNS, 패킷 처리, 연결 전략을 내 기기에서 실행하는 오픈소스 네트워크 엔진. 개발자가 운영하는 원격 VPN 서버를 거치지 않습니다.')}</p>
          <div class="hero-actions">{link('engineering',t('See inside','엔진 살펴보기')+ARROW,'button button-blue')}{link('guide',t('Get Tunnel HTTPS','Tunnel HTTPS 시작하기'),'quiet-link')}</div>
          <div class="hero-footnote"><span>{t('Open source','오픈소스')}</span><span>Android 7.0+</span><a href="{REPO}/blob/main/LICENSE">Apache-2.0</a></div>
        </div>
        <div class="object-wrap">
          <div class="object-stage">
            <svg class="object-fallback" viewBox="0 0 600 580" aria-hidden="true"><defs><linearGradient id="pipe" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#80a3ff"/><stop offset=".45" stop-color="#2854ee"/><stop offset="1" stop-color="#0d2093"/></linearGradient></defs><path d="M167 443V249c0-180 280-180 280 0v116" fill="none" stroke="url(#pipe)" stroke-width="100" stroke-linecap="butt" transform="rotate(19 300 290)"/></svg>
            <canvas id="conduit" width="700" height="650" aria-hidden="true"></canvas>
          </div>
          <div class="object-shadow" aria-hidden="true"></div>
          <div class="object-caption"><span>{t('A local path. An open possibility.','기기 안의 경로. 열린 가능성.')}</span><button type="button" class="rotate-control" data-object-rotate aria-label="{t('Rotate the conduit illustration','입체 일러스트 회전')}"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 10a8 8 0 1 0-1 7M20 4v6h-6"/></svg><span>{t('Rotate','회전')}</span></button></div>
        </div>
      </div>
      <div class="home-bottom">
        <div class="home-statement"><p>{t('Less distance.<br>More understanding.','더 가까운 연결.<br>더 명확한 이해.')}</p></div>
        {link('engineering',f'<span class="bottom-icon">{ICON}</span><span><strong>{t("Follow a packet","패킷 경로 탐색")}</strong><small>{t("Explore DNS, TCP and QUIC handling.","DNS, TCP, QUIC 처리 과정을 살펴보세요.")}</small></span>'+ARROW,'feature-link')}
        {link('privacy',f'<span class="bottom-icon"><svg viewBox="0 0 28 28" aria-hidden="true"><path d="M5 8h18v15H5zM10 8V6a4 4 0 0 1 8 0v2M14 14v4"/></svg></span><span><strong>{t("Know the boundaries","보호 범위 확인")}</strong><small>{t("What stays local. What still leaves.","기기에 남는 정보와 외부로 나가는 정보.")}</small></span>'+ARROW,'feature-link')}
      </div>''')
    tabs=''.join(f'<button type="button" role="tab" id="tab-{key}" aria-controls="path-panel" aria-selected="{str(key=="dns").lower()}" tabindex="{0 if key=="dns" else -1}" data-protocol="{key}">{name}</button>' for key,name in [('dns','DNS'),('tcp','TCP / TLS'),('quic','UDP / QUIC'),('learning',t('Learning','학습'))])
    steps=''.join(f'<button type="button" class="path-step" data-step="{i}" aria-label="{t("Step","단계")} {i+1}"{ " aria-current=step" if i==0 else ""}><span class="step-index">{i+1}</span><span class="step-name">{[t("Capture","수신"),t("Normalize","정규화"),t("Validate","검증"),t("Resolve","해석")][i]}</span></button>' for i in range(4))
    engineering=section('engineering',f'''
      <div class="page-heading"><div><p class="product-label">{t('Inside the engine','네트워크 엔진 내부')}</p><h1 tabindex="-1">{t('Nothing hidden.<br>Not even the plumbing.','연결의 내부까지,<br>직접 확인하세요.')}</h1></div><p>{t('Choose a protocol. Follow the request. Inspect the implementation behind each decision.','프로토콜을 고르고 요청의 흐름을 따라가세요. 각 판단을 담당하는 실제 구현으로 연결됩니다.')}</p></div>
      <div class="engine-workbench">
        <div class="workbench-toolbar"><div class="protocol-tabs" role="tablist" aria-label="{t('Protocol path','프로토콜 경로')}">{tabs}</div><span class="illustration-note">{t('Interactive explanation, not live traffic','실제 트래픽이 아닌 구조 설명')}</span></div>
        <div id="path-panel" role="tabpanel" aria-labelledby="tab-dns">
          <div class="path-track">{steps}</div>
          <div class="path-body"><div class="path-copy" aria-live="polite"><span class="step-position" id="step-position">01 / 04</span><h2 id="step-title">{t('Start at the device.','기기에서 시작합니다.')}</h2><p id="step-description">{t('Android provides the VpnService and TUN interface. Tunnel HTTPS reads packets selected by the local routing configuration.','Android의 VpnService와 TUN 인터페이스를 통해 라우팅 설정에 포함된 패킷을 읽습니다.')}</p></div><aside class="implementation"><span>{t('Implementation','구현')}</span><a id="step-source" class="source-link" href="{REPO}/blob/main/app/src/main/java/com/tunnelvpn/app/LocalProtectionEngine.kt" target="_blank" rel="noopener noreferrer"><span>LocalProtectionEngine.kt</span>{EXTERNAL}</a><p id="step-boundary">{t('Android supplies the interface; this repository supplies the local packet loop.','인터페이스는 Android가, 로컬 패킷 루프는 이 저장소가 구현합니다.')}</p><a href="{REPO}/blob/main/docs/release/01_ARCHITECTURE_AND_VPN_FLOW.md" class="quiet-link">{t('Read the architecture','전체 구조 문서 읽기')}</a></aside></div>
          <div class="playback"><button type="button" id="play-path" class="button button-small" aria-pressed="false">{t('Play path','경로 재생')}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 11 7-11 7z"/></svg></button><label for="path-progress" class="sr-only">{t('Path step','경로 단계')}</label><input id="path-progress" type="range" min="0" max="3" value="0" step="1"><button type="button" id="next-step" class="step-next" aria-label="{t('Next path step','다음 단계')}">{ARROW}</button></div>
        </div>
      </div>
      <div class="engine-details"><h2>{t('Built here.<br>Bounded by design.','직접 구현하고,<br>한계를 명시합니다.')}</h2><div class="technical-rows">
        <details><summary>{t('Packet & transport','패킷과 전송')}<span>IPv4 / IPv6 / TCP / UDP</span></summary><p>{t('Packet normalization, bounded fragment handling and local relay state are implemented here. Upstream TCP sockets still use Android/Linux. This is not a complete kernel TCP replacement.','패킷 정규화, 제한된 조각 처리, 로컬 릴레이 상태를 직접 구현합니다. 외부 TCP 소켓은 Android/Linux를 사용하며, 커널 TCP 스택 전체를 대체하지는 않습니다.')}</p>{source('TurboTcpForwarder.kt')}</details>
        <details><summary>{t('Names & trust','이름 해석과 신뢰')}<span>DNS / DoH / DNS64</span></summary><p>{t('DNS parsing, validation, cache policy and resolver selection have separate implementations. DNS64/NAT64 behavior depends on a compatible, discoverable network.','DNS 파싱·응답 검증·캐시 정책·리졸버 선택을 별도 구현합니다. DNS64/NAT64 동작에는 호환되는 네트워크와 프리픽스 탐색이 필요합니다.')}</p>{source('DnsMessageValidator.kt')}</details>
        <details><summary>{t('Adaptive, not autonomous','적응형 정책과 안전 경계')}<span>Turbo / Aegis</span></summary><p>{t('Turbo uses local context and observed outcomes. The experimental Aegis neural model remains shadow-only: it observes, but has no routing authority.','Turbo는 로컬 문맥과 관측한 결과를 활용합니다. 실험적 신경망 Aegis는 shadow 모드이며 관측만 하고 라우팅 권한은 갖지 않습니다.')}</p>{source('AegisLocal2Engine.kt')}</details>
      </div></div>''')
    release_label=t('Published beta','공개 베타') if release.get('prerelease') else t('Published release','공개 릴리스')
    release_detail=t('Check the release notes and limitations before installing.','설치 전 릴리스 노트와 제한 사항을 확인하세요.') if release.get('published') else t('A public installable APK was not verified during this site build. The source is available now; check Releases for publication updates.','이 사이트 빌드에서는 공개 설치 APK를 확인하지 못했습니다. 소스는 공개되어 있으며 배포 상태는 Releases에서 확인할 수 있습니다.')
    release_url=release.get('apk_url') or REPO+'/releases'
    guide=section('guide',f'''
      <div class="install-layout"><div class="install-intro"><p class="product-label">{t('Get Tunnel HTTPS','Tunnel HTTPS 시작하기')}</p><h1 tabindex="-1">{t('Your device.<br>Your next move.','내 기기에서,<br>직접 시작하세요.')}</h1><p>{t('Install from GitHub. Read the source. Or build it yourself. The choice stays yours.','GitHub에서 설치하거나 소스를 읽고 직접 빌드하세요. 선택은 사용자에게 있습니다.')}</p><a class="quiet-link" href="{REPO}">{t('Open the repository','저장소 열기')}{EXTERNAL}</a></div>
      <div class="install-content"><div class="release-panel"><div class="release-head"><span class="release-status">{release_label if release.get('published') else t('Source available','소스 공개')}</span><span>Android 7.0+</span></div><h2>{esc(release.get('version') or 'Tunnel HTTPS')}</h2><p>{release_detail}</p><a class="button button-blue" href="{esc(release_url)}" id="download-link">{t('Download APK','APK 다운로드') if release.get('apk_url') else t('View GitHub releases','GitHub Releases 확인')}{ARROW}</a><p class="release-small">{t('Direct distribution. No Play Store required.','직접 배포. Play 스토어가 필요하지 않습니다.')}</p></div>
      <ol class="install-steps"><li><span>1</span><div><h3>{t('Choose the official build','공식 빌드 선택')}</h3><p>{t('Use the APK attached to a published release in this repository. Read its known limitations.','이 저장소의 공개 릴리스에 첨부된 APK를 사용하고 알려진 제한 사항을 확인하세요.')}</p></div></li><li><span>2</span><div><h3>{t('Approve only what is needed','필요한 권한만 허용')}</h3><p>{t('If Android asks, allow installation for the downloading app. Review the VPN consent prompt inside Tunnel HTTPS.','Android가 요청할 때 다운로드에 사용한 앱의 설치 권한을 허용하세요. Tunnel HTTPS 내부의 VPN 동의 화면을 확인하세요.')}</p></div></li><li><span>3</span><div><h3>{t('Keep the boundaries in view','보호 범위 확인')}</h3><p>{t('This does not change your country or public IP. Another active Android VPN may conflict.','국가나 공인 IP를 변경하지 않습니다. 다른 Android VPN과 동시에 사용하면 충돌할 수 있습니다.')}</p></div></li></ol>
      <details class="verify-drawer" id="verify-drawer"><summary>{t('Verify a file locally','파일을 로컬에서 검증')}<span>SHA-256</span></summary><div class="drawer-body"><p>{t('Select an APK and compare its checksum. No file is uploaded. A checksum match is not a malware scan or a signature audit.','APK의 체크섬을 비교합니다. 파일은 업로드되지 않습니다. 체크섬 일치는 악성코드 검사나 서명 감사를 의미하지 않습니다.')}</p><label for="expected-hash">{t('Expected SHA-256 from the release','릴리스에 게시된 SHA-256')}</label><input type="text" id="expected-hash" name="expected-hash" autocomplete="off" spellcheck="false" maxlength="64" placeholder="{t('Paste 64 hexadecimal characters…','16진수 64자 붙여넣기…')}" value="{esc(release.get('sha256',''))}"><label class="file-label" for="apk-file">{t('Choose a local APK','로컬 APK 선택')}<span>{t('Up to 128 MiB','최대 128 MiB')}</span></label><input id="apk-file" name="apk-file" type="file" accept=".apk,application/vnd.android.package-archive"><output id="hash-result" aria-live="polite">{t('The result will appear here.','검증 결과가 여기에 표시됩니다.')}</output></div></details>
      <details class="developer-drawer"><summary>{t('Build from source','소스에서 빌드')}<span>{t('For developers','개발자용')}</span></summary><div class="drawer-body"><p>{t('Requires JDK 17, the Android SDK with API 36 and Git. These commands build a debug APK, not an official signed release.','JDK 17, API 36 Android SDK와 Git이 필요합니다. 아래 명령은 공식 서명 릴리스가 아닌 디버그 APK를 빌드합니다.')}</p><div class="code-block"><button class="copy-code" type="button">{t('Copy','복사')}</button><pre><code>git clone https://github.com/Sp2ctr2/Tunnel-HTTPS.git\ncd Tunnel-HTTPS\n./gradlew :app:assembleDebug\n./gradlew :app:testDebugUnitTest :app:lintDebug</code></pre></div></div></details>
      </div></div>''')
    privacy=section('privacy',f'''
      <div class="page-heading trust-heading"><div><p class="product-label">{t('Privacy & boundaries','개인정보와 보호 범위')}</p><h1 tabindex="-1">{t('Local doesn’t<br>mean invisible.','로컬 처리는<br>익명이 아닙니다.')}</h1></div><p>{t('Good networking software should explain where its responsibility ends. Here is ours.','네트워크 소프트웨어는 자신이 보호하는 범위와 그 한계를 명확히 설명해야 합니다.')}</p></div>
      <div class="trust-layout"><div class="trust-note"><div class="trust-emblem" aria-hidden="true">{ICON}</div><p>{t('No developer-operated<br>remote VPN gateway.','개발자 운영<br>원격 VPN 게이트웨이 없음.')}</p><span>{t('Your traffic still reaches the services you use.','트래픽은 사용자가 접속한 서비스에 도달합니다.')}</span></div><div class="trust-rows">
      <article><h2>{t('Processed on your device','기기에서 처리하는 정보')}</h2><p>{t('Depending on your configuration, the engine processes DNS names, destination addresses, ports and supported TLS ClientHello metadata to make local decisions.','설정에 따라 DNS 이름, 목적지 주소·포트, 지원되는 TLS ClientHello 메타데이터를 로컬 판단에 사용합니다.')}</p></article>
      <article><h2>{t('Still visible outside it','외부에 전달되는 정보')}</h2><p>{t('The DNS provider receives the queries sent to it. Destination services can see connections and the public IP used to reach them. Local processing is not anonymity.','선택한 DNS 제공자는 전송된 질의를 받습니다. 목적지 서비스는 연결과 공인 IP를 볼 수 있습니다. 로컬 처리는 익명성을 의미하지 않습니다.')}</p></article>
      <article><h2>{t('Not an HTTPS decryption tool','HTTPS 복호화 도구가 아닙니다')}</h2><p>{t('ClientHello handling is not payload decryption. The app is not presented as a country switcher, a complete QUIC stack or a guarantee that every network will work.','ClientHello 처리는 암호화된 본문 복호화와 다릅니다. 국가 변경, 완전한 QUIC 스택, 모든 네트워크에서의 연결을 보장하지 않습니다.')}</p></article>
      <article><h2>{t('A website, not a traffic collector','트래픽 수집기가 아닌 웹사이트')}</h2><p>{t('This site is hosted by GitHub Pages and loads typefaces from Google Fonts. There is no analytics or file-upload endpoint. The file checker works in your browser.','이 사이트는 GitHub Pages에서 제공되며 Google Fonts에서 글꼴을 불러옵니다. 분석 추적기나 파일 업로드 엔드포인트는 없습니다. 파일 검증은 브라우저 내부에서 실행됩니다.')}</p></article>
      <a href="{REPO}/blob/main/SECURITY.md" class="button button-line">{t('Read the security policy','보안 정책 읽기')}{ARROW}</a>
      </div></div>''')
    other_lang='en' if lang=='ko' else 'ko'
    other_url=('/Tunnel-HTTPS/' if other_lang=='en' else '/Tunnel-HTTPS/ko/')+ROUTES[page]
    root=('/Tunnel-HTTPS/ko/' if lang=='ko' else '/Tunnel-HTTPS/')
    style=assets['style.css'];app=assets['app.js'];obj=assets['object.js'];boot=assets['boot.js']
    return f'''<!doctype html>
<html lang="{lang}" data-design="{DESIGN}" data-root="{root}">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'; worker-src 'none'">
<meta name="referrer" content="strict-origin-when-cross-origin"><meta name="theme-color" content="#f7f8fa"><meta name="color-scheme" content="light dark">
<title>{esc(title)} — Tunnel HTTPS</title>
<meta name="description" content="{esc(t('An open-source Android networking engine. Local DNS policy, packet handling and adaptive connection strategy, with no developer-operated remote VPN gateway.','Android용 오픈소스 네트워크 엔진. 원격 VPN 게이트웨이 없이 로컬 DNS 정책, 패킷 처리, 적응형 연결 전략을 실행합니다.'))}">
<link rel="canonical" href="{BASE+('ko/' if lang=='ko' else '')+ROUTES[page]}">
<link rel="alternate" hreflang="en" href="{BASE+ROUTES[page]}"><link rel="alternate" hreflang="ko" href="{BASE+'ko/'+ROUTES[page]}">
<meta property="og:title" content="Tunnel HTTPS — {esc(title)}"><meta property="og:description" content="On-device Android networking. Open to inspection."><meta property="og:type" content="website"><meta property="og:image" content="{BASE}assets/conduit/social.svg">
<link rel="icon" href="/Tunnel-HTTPS/assets/conduit/favicon.svg" type="image/svg+xml">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&family=Noto+Sans+KR:wght@400;500;600;700&display=swap" rel="stylesheet">
<link rel="stylesheet" href="/Tunnel-HTTPS/assets/conduit/{style}"><script src="/Tunnel-HTTPS/assets/conduit/{boot}"></script>
<script defer src="/Tunnel-HTTPS/assets/conduit/{app}"></script><script defer src="/Tunnel-HTTPS/assets/conduit/{obj}"></script>
</head>
<body data-page="{page}" data-revision="{revision[:12]}">
<a class="skip-link" href="#main">{t('Skip to content','본문으로 이동')}</a>
<header class="site-header"><div class="header-inner"><a class="brand" href="{href('home')}" data-route="home" aria-label="Tunnel HTTPS {t('home','홈')}">{ICON}<span>tunnel<span class="brand-sub">HTTPS</span></span></a><nav class="desktop-nav" aria-label="{t('Main navigation','주 메뉴')}">{nav}</nav><div class="header-tools"><a id="language-link" class="language-link" href="{other_url}" lang="{other_lang}" hreflang="{other_lang}">{t('한국어','EN')}</a><button type="button" class="theme-button" aria-label="{t('Toggle color theme','밝은 테마와 어두운 테마 전환')}"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7"/><path d="M12 5v14"/></svg></button><a class="github-link" href="{REPO}" target="_blank" rel="noopener noreferrer">GitHub{EXTERNAL}</a><button class="menu-button" type="button" aria-label="{t('Open navigation','메뉴 열기')}" aria-expanded="false" aria-controls="mobile-nav"><span></span><span></span></button></div></div><nav id="mobile-nav" class="mobile-nav" aria-label="{t('Mobile navigation','모바일 메뉴')}" hidden>{nav}</nav></header>
<main id="main">{home}{engineering}{guide}{privacy}</main>
<footer class="site-footer"><a href="{REPO}">Tunnel HTTPS</a><p>{t('Open source. On your device.','오픈소스. 내 기기에서.')}</p><div><a href="{REPO}/blob/main/LICENSE">Apache-2.0</a>{link('privacy',t('Privacy','개인정보'))}<a href="{REPO}/issues">{t('Feedback','피드백')}</a></div></footer>
<div id="route-curtain" aria-hidden="true"><span id="curtain-name"></span><span class="curtain-mark">{ICON}</span></div>
<div id="toast" role="status" aria-live="polite" hidden></div><div class="sr-only" id="route-announcement" aria-live="polite"></div>
</body></html>'''

def build(output, release):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    dest=output/'assets/conduit';dest.mkdir(parents=True,exist_ok=True)
    assets={}
    for name in ['style.css','app.js','object.js','boot.js']:
        content=(SOURCE/name).read_bytes();digest=hashlib.sha256(content).hexdigest()[:12]
        hashed=f'{Path(name).stem}.{digest}{Path(name).suffix}'
        (dest/hashed).write_bytes(content);assets[name]=hashed
    revision=os.environ.get('GITHUB_SHA','local-review')
    for lang in ['en','ko']:
        for page,route in ROUTES.items():
            path=output/('ko' if lang=='ko' else '')/route/'index.html';path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(render(lang,page,assets,release,revision),encoding='utf-8')
    (output/'.nojekyll').write_text('')
    (dest/'favicon.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40"><rect width="40" height="40" rx="9" fill="#2854ee"/><path d="M10 32V17a10 10 0 0 1 20 0v15M16 32V17a4 4 0 0 1 8 0v15" fill="none" stroke="white" stroke-width="2.5"/></svg>')
    (dest/'social.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630"><rect width="1200" height="630" fill="#f7f8fa"/><text x="80" y="105" font-family="sans-serif" font-size="34" fill="#242628">tunnel / HTTPS</text><text x="80" y="300" font-family="sans-serif" font-weight="700" font-size="108" fill="#18212c">Take the</text><text x="80" y="416" font-family="sans-serif" font-weight="700" font-size="108" fill="#18212c">local route.</text><path d="M865 470V240a90 90 0 0 1 180 0v170" fill="none" stroke="#2854ee" stroke-width="65"/><text x="80" y="546" font-family="sans-serif" font-size="25" fill="#5a626e">On-device Android networking. Open source.</text></svg>')
    (output/'404.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Page not found — Tunnel HTTPS</title><link rel="stylesheet" href="/Tunnel-HTTPS/assets/conduit/'+assets['style.css']+'"><body><main class="not-found"><p>Tunnel HTTPS</p><h1>This path ends here.</h1><p>The page could not be found. Return to the product or inspect the source.</p><a class="button button-blue" href="/Tunnel-HTTPS/">Return home</a></main></body></html>')
    manifest={'design':DESIGN,'source_commit':revision,'assets':assets,'routes':8,'release_available':bool(release.get('published'))}
    (output/'site-version.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(manifest,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='_site');parser.add_argument('--resolve-release',action='store_true');args=parser.parse_args()
    build(args.output,resolve_release() if args.resolve_release else {'published':False,'url':REPO+'/releases'})
