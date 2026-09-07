"""Static, bilingual product website. No application or signing changes."""
from pathlib import Path
from html import escape as esc
import argparse, hashlib, json, os, shutil, subprocess, urllib.request

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
REPO='https://github.com/Sp2ctr2/Tunnel-HTTPS'
BASE='https://sp2ctr2.github.io/Tunnel-HTTPS/'
REV=os.environ.get('GITHUB_SHA','local-review')
ROUTES={'home':'','engineering':'engineering/','guide':'guide/','privacy':'privacy/'}

def read_release():
    result={'available':False,'url':REPO+'/releases','revision':REV}
    try:
        request=urllib.request.Request('https://api.github.com/repos/Sp2ctr2/Tunnel-HTTPS/releases?per_page=30',headers={'User-Agent':'TunnelHTTPS-site','Accept':'application/vnd.github+json'})
        with urllib.request.urlopen(request,timeout=20) as response: releases=json.load(response)
        if not isinstance(releases,list): return result
        for release in releases:
            if not isinstance(release,dict): continue
            if release.get('draft') or not release.get('published_at'): continue
            url=release.get('html_url','')
            if not url.startswith(REPO+'/releases/tag/'): continue
            for asset in release.get('assets',[]):
                name=asset.get('name','')
                download=asset.get('browser_download_url','')
                if not name.endswith('.apk') or any(x in name.lower() for x in ('unsigned','debug','test')): continue
                if not download.startswith(REPO+'/releases/download/') or not 0<asset.get('size',0)<=128*1024*1024: continue
                result.update(available=True,url=url,download=download,version=release.get('tag_name',''),beta=release.get('prerelease',False),size=asset['size'])
                return result
    except (OSError,ValueError,TypeError): pass
    return result

# Product claims follow the repository README and linked source, not marketing estimates.
SUBSYSTEMS=[
 ('packets','Packets','패킷','IPv4 / IPv6','Bounded','범위 제한',
  'Validate before forwarding.','전달하기 전에 검증합니다.',
  'Selected traffic enters the TUN interface. Lengths, headers and checksums are checked; supported fragments are normalized and reassembled within resource limits.',
  '설정에 따라 선택된 트래픽을 TUN 인터페이스로 받습니다. 길이·헤더·체크섬을 검사하고, 지원하는 조각은 자원 제한 안에서 정규화하고 재조립합니다.',
  'Packet parsing, supported fragment handling, checksums and dispatch.','패킷 파싱, 지원 범위 내 조각 처리, 체크섬과 분기.',
  'VpnService, TUN and the underlying Android/Linux network.','VpnService, TUN 및 Android/Linux 네트워크.',
  'IPv6 extension-header and fragment coverage is deliberately bounded. This is not an implementation of every IP protocol.',
  'IPv6 확장 헤더와 조각 처리는 의도적으로 범위가 제한됩니다. 모든 IP 프로토콜을 구현한 것은 아닙니다.',
  'Ipv6PacketNormalizer.kt','LocalProtectionEngine.kt'),
 ('dns','DNS','DNS','DNS / DoH / DNS64','Implemented','구현됨',
  'A DNS answer is input, not a guarantee.','DNS 응답도 검증할 입력입니다.',
  'Response validation and cache policy sit between the query and the answer. Encrypted resolution, resolver selection and DNS64/NAT64 handling have explicit paths.',
  '질의와 응답 사이에 응답 검증과 캐시 정책을 둡니다. 암호화된 DNS, 리졸버 선택, DNS64/NAT64 처리를 각각 명시적인 경로로 구현합니다.',
  'Message parsing, validation, cache policy, resolver racing and DNS64 handling.','메시지 파싱·검증, 캐시 정책, 리졸버 경쟁과 DNS64 처리.',
  'HTTPS transport and external resolver infrastructure.','HTTPS 전송과 외부 리졸버 인프라.',
  'The selected DNS provider still receives queries and may see your IP address. NAT64 depends on discoverable network support.',
  '선택한 DNS 제공자는 질의를 수신하며 IP 주소를 볼 수 있습니다. NAT64는 네트워크의 지원과 탐지 가능 여부에 영향을 받습니다.',
  'DnsMessageValidator.kt','DohResolver.kt'),
 ('tcp','TCP & TLS','TCP와 TLS','TCP / TLS ClientHello','Partial','부분 구현',
  'Local state. Platform sockets.','로컬 상태 관리와 플랫폼 소켓.',
  'The TUN-side relay tracks connection state and forwards through protected upstream sockets. For supported TLS traffic, it can adapt ClientHello fragmentation without decrypting the payload.',
  'TUN 측 릴레이는 연결 상태를 관리하고 VPN에서 제외한 업스트림 소켓으로 전달합니다. 지원하는 TLS 트래픽은 암호문을 복호화하지 않고 ClientHello 분할 전략을 적용할 수 있습니다.',
  'Bounded local relay state and TLS metadata/fragmentation strategies.','자원 제한이 있는 로컬 릴레이 상태와 TLS 메타데이터·분할 전략.',
  'Internet-facing kernel TCP sockets and TLS cryptography.','인터넷 측 커널 TCP 소켓과 TLS 암호화.',
  'This is not a complete replacement for the kernel TCP stack. It does not decrypt HTTPS or guarantee connectivity.',
  '커널 TCP 스택을 완전히 대체하지 않습니다. HTTPS를 복호화하거나 연결을 보장하지 않습니다.',
  'TurboTcpForwarder.kt','TlsClientHello.kt'),
 ('quic','UDP & QUIC','UDP와 QUIC','UDP / QUIC v1 & v2','Experimental','실험적',
  'Recognize the structure. Keep the limits.','구조를 식별하고, 한계를 유지합니다.',
  'UDP uses a bounded local relay. The QUIC-aware path classifies supported packet structures and checks selected responses to inform its local handling.',
  'UDP는 자원 제한이 있는 로컬 릴레이로 처리합니다. QUIC 경로는 지원하는 패킷 구조를 분류하고 일부 응답을 검증해 로컬 처리에 활용합니다.',
  'UDP relay, structural classification and selected QUIC response checks.','UDP 릴레이, 구조 분류 및 일부 QUIC 응답 검증.',
  'Upstream UDP sockets and the endpoint’s QUIC implementation.','업스트림 UDP 소켓과 상대 측 QUIC 구현.',
  'QUIC awareness is not a complete QUIC stack. Unsupported structures are not claimed as supported.',
  'QUIC 인지는 완전한 QUIC 스택이 아닙니다. 지원하지 않는 구조까지 처리한다고 주장하지 않습니다.',
  'QuicHttp3Guard.kt','TurboUdpForwarder.kt'),
 ('learning','Local learning','로컬 학습','Turbo / Aegis','Experimental','실험적',
  'Adapt locally. Keep experimental policy separate.','로컬에서 적응하고, 실험 정책은 분리합니다.',
  'Turbo uses local context and outcomes to select connection strategies. The Aegis neural policy is evaluated in shadow mode rather than given authority over live routing.',
  'Turbo는 로컬 맥락과 결과로 연결 전략을 선택합니다. Aegis 신경망 정책은 실제 라우팅을 제어하지 않고 섀도 모드에서 평가합니다.',
  'Contextual strategy selection, local learning and shadow inference.','맥락 기반 전략 선택, 로컬 학습과 섀도 추론.',
  'Android runtime, platform networking and local storage.','Android 런타임, 플랫폼 네트워크와 로컬 저장소.',
  'Experimental learning does not certify better performance on every network. Aegis has no active routing authority.',
  '실험적 학습이 모든 네트워크에서 더 좋은 성능을 보장하지 않습니다. Aegis는 실제 라우팅 제어 권한이 없습니다.',
  'AegisLocal2Engine.kt','AegisLocal2Neural.kt'),
]

def render(lang,page,release,assets):
    ko=lang=='ko'
    def t(a,b): return b if ko else a
    def href(name): return BASE+('ko/' if ko else '')+ROUTES[name]
    # Relative local URLs support GitHub project paths and local previews.
    depth=(1 if ko else 0)+(0 if page=='home' else 1)
    root='../'*depth or './'
    def local(name): return root+('ko/' if ko else '')+ROUTES[name]
    def image(name): return root+'media/'+lang+'-'+name+'.png'
    def source(name): return REPO+'/blob/main/app/src/main/java/com/tunnelvpn/app/'+name
    nav=[('home',t('Product','제품')),('engineering',t('Under the hood','기술 살펴보기')),('guide',t('Get started','시작하기'))]
    labels={name:label for name,label in nav};labels['privacy']=t('Privacy','개인정보')
    title={'home':t('Your connection. In your hands.','내 연결은, 내 손안에.'),'engineering':t('Inside the engine.','엔진의 안쪽까지.'),'guide':t('Make it yours.','직접 시작해 보세요.'),'privacy':t('Know the boundaries.','어디까지 보호하는지.') }[page]
    arrow='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14m-5-5 5 5-5 5"/></svg>'
    def link(name,text,cls='button primary'): return f'<a class="{cls}" href="{local(name)}">{text}{arrow}</a>'
    def details(label,body): return f'<details><summary>{label}<span aria-hidden="true">+</span></summary><div class="disclosure-body">{body}</div></details>'
    def field(label,body): return f'<div class="fact"><h3>{label}</h3><p>{body}</p></div>'
    def nav_html(): return ''.join(f'<a href="{local(n)}"'+(' aria-current="page"' if n==page else '')+f'>{label}</a>' for n,label in nav)
    gallery=''.join(f'<button type="button" role="tab" id="shot-{n}" aria-controls="product-screen" aria-selected="{str(i==0).lower()}" tabindex="{0 if i==0 else -1}" data-shot="{n}" data-src="{image(n)}" data-label="{t(a,b)}">{t(a,b)}</button>' for i,(n,a,b) in enumerate([('connection','Connection','연결'),('activity','Activity','활동'),('settings','Settings','설정')]))
    shotnote=t('App UI rendered from the repository. Disconnected, not a live network demo.','저장소의 앱 UI를 렌더링한 화면입니다. 연결 전 상태이며 실제 네트워크 시연이 아닙니다.')
    if page=='home':
        body=f'''<section class="hero wrap">
          <div class="hero-copy"><p class="product-label">{t('Tunnel HTTPS for Android','Android를 위한 Tunnel HTTPS')}</p>
          <h1>{t('Your connection.<br>In your hands.','내 연결은,<br>내 손안에.')}</h1>
          <p class="lead">{t('Encrypted DNS. Fewer unwanted domains.<br>A connection you can understand.','암호화된 DNS. 원하지 않는 도메인 차단.<br>내가 이해하고 선택하는 연결.')}</p>
          <p class="hero-description">{t('Local network controls for Android, without a remote VPN gateway. Open source, from the interface down to the packet path.','원격 VPN 게이트웨이 없이, Android 안에서 네트워크를 제어합니다. 화면부터 패킷 처리까지 코드를 직접 확인할 수 있습니다.')}</p>
          <div class="actions">{link('guide',t('Get Tunnel HTTPS','Tunnel HTTPS 시작하기'))}<a class="button quiet" href="{REPO}">{t('View source','소스 보기')}{arrow}</a></div>
          <p class="compatibility">{t('Android 7.0 or later. No root required.','Android 7.0 이상. 루팅 불필요.')}</p>
          <div class="app-tabs" role="tablist" aria-label="{t('Preview app screens','앱 화면 미리보기')}">{gallery}</div>
          <p id="shot-description" class="shot-description">{t('The connection screen, before you switch on.','연결을 시작하기 전의 메인 화면.')}</p></div>
          <figure class="product-figure"><div id="product-screen" role="tabpanel" aria-labelledby="shot-connection"><button class="phone-button" type="button" data-zoom aria-label="{t('Enlarge app screen','앱 화면 크게 보기')}"><span class="device"><img id="app-image" src="{image('connection')}" width="390" height="780" alt="{t('Tunnel HTTPS connection screen, ready to connect','연결 대기 상태의 Tunnel HTTPS 화면')}" fetchpriority="high"></span><span class="zoom-label">{t('View full screen','크게 보기')}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5"/></svg></span></button></div><figcaption>{shotnote}</figcaption></figure>
        </section>
        <section class="benefits wrap" aria-label="{t('Core capabilities','주요 기능')}">
        {field(t('DNS, encrypted.','DNS는 암호화.'),t('Send DNS queries over HTTPS to your selected provider.','선택한 제공자에게 DNS 질의를 HTTPS로 전송합니다.'))}
        {field(t('Traffic, your choice.','트래픽은 선택해서.'),t('Use domain filtering and choose which apps bypass the local path.','도메인을 필터링하고 로컬 경로에서 제외할 앱을 선택합니다.'))}
        {field(t('Decisions, on-device.','판단은 기기 안에서.'),t('Local policy adapts connection strategies to observed outcomes.','관측된 결과를 바탕으로 기기 안에서 연결 전략을 조정합니다.'))}
        </section>
        <section class="under-section"><div class="wrap under-grid"><div><p class="section-label">{t('Open all the way down','안쪽까지 열린 소프트웨어')}</p><h2>{t('A simple interface.<br>Substantial engineering.','화면은 간단하게.<br>구현은 깊이 있게.')}</h2><p>{t('The Android interface is only the beginning. Inspect the code that handles packets, checks DNS responses and manages local connections.','Android 화면은 시작일 뿐입니다. 패킷 처리, DNS 응답 검사, 로컬 연결 관리까지 실제 구현을 살펴보세요.')}</p>{link('engineering',t('See how it works','어떻게 작동하는지 보기'),'button quiet')}</div><div class="source-index">'''+''.join(f'<a href="{local("engineering")}#{s[0]}"><div><h3>{s[3]}</h3><p>{t(s[10],s[11])}</p></div>{arrow}</a>' for s in SUBSYSTEMS[:4])+f'''</div></div></section>
        <section class="wrap home-bottom"><div><h2>{t('Start with the facts.','분명히 알고 시작하세요.')}</h2><p>{t('Local processing is not anonymity. Tunnel HTTPS does not change your country or decrypt HTTPS content.','로컬 처리가 익명성을 뜻하지는 않습니다. Tunnel HTTPS는 국가를 바꾸거나 HTTPS 내용을 복호화하지 않습니다.')}</p></div>{link('privacy',t('Read the boundaries','보호 범위 확인하기'),'button quiet')}</section>'''
    elif page=='engineering':
        tabs=''.join(f'<a role="tab" id="tab-{s[0]}" href="#{s[0]}" aria-controls="panel-{s[0]}" aria-selected="{str(i==0).lower()}" tabindex="{0 if i==0 else -1}" data-topic="{s[0]}">{t(s[1],s[2])}<span aria-hidden="true">›</span></a>' for i,s in enumerate(SUBSYSTEMS))
        panels=''
        for s in SUBSYSTEMS:
            panels+=f'''<article class="topic-panel" id="panel-{s[0]}" data-panel="{s[0]}" role="tabpanel" aria-labelledby="tab-{s[0]}" tabindex="0"><span id="{s[0]}" class="anchor-target" aria-hidden="true"></span><div class="topic-heading"><span class="topic-name">{s[3]}</span><span class="scope-tag">{t(s[4],s[5])}</span></div><h2>{t(s[6],s[7])}</h2><p class="panel-lead">{t(s[8],s[9])}</p><dl class="ownership"><div><dt>{t('Implemented here','이 저장소의 구현')}</dt><dd>{t(s[10],s[11])}</dd></div><div><dt>{t('Provided by the platform','플랫폼이 제공하는 부분')}</dt><dd>{t(s[12],s[13])}</dd></div></dl><div class="boundary-note"><h3>{t('Important boundary','알아둘 한계')}</h3><p>{t(s[14],s[15])}</p></div><div class="source-links"><a href="{source(s[16])}">{s[16]}{arrow}</a><a href="{source(s[17])}">{s[17]}{arrow}</a></div></article>'''
        rows=''.join(f'<tr data-capability><th scope="row"><a href="#{s[0]}">{t(s[1],s[2])}</a></th><td>{t(s[4],s[5])}</td><td>{t(s[14],s[15])}</td></tr>' for s in SUBSYSTEMS)
        body=f'''<section class="wrap page-intro"><p class="section-label">{t('Under the hood','기술 살펴보기')}</p><h1>{title}</h1><p class="lead">{t('What runs locally. What Android provides.<br>Exactly where the boundaries are.','로컬에서 하는 일, Android가 제공하는 것.<br>그 경계를 명확하게 살펴보세요.')}</p></section><section class="wrap engineering-layout"><nav class="topic-tabs" role="tablist" aria-orientation="vertical" aria-label="{t('Engine subsystems','엔진 구성 요소')}">{tabs}</nav><div class="topic-panels">{panels}</div></section>
        <section class="wrap support-section"><div class="support-header"><div><h2>{t('Support at a glance.','지원 범위를 한눈에.')}</h2><p>{t('Implementation scope, not a compatibility guarantee.','구현 범위이며, 모든 환경에서의 호환성을 보장하는 표는 아닙니다.')}</p></div><label class="search-field">{t('Find a subsystem','구성 요소 검색')}<input type="search" name="capability" id="capability-search" placeholder="{t('Try DNS…','DNS 검색…')}" autocomplete="off" spellcheck="false"></label></div><div class="table-scroll" role="region" aria-label="{t('Support matrix','지원 범위 표')}" tabindex="0"><table><thead><tr><th>{t('Subsystem','구성 요소')}</th><th>{t('Scope','구현 범위')}</th><th>{t('Limitations','제약')}</th></tr></thead><tbody>{rows}</tbody></table></div><p id="no-results" role="status" hidden>{t('No matching subsystem. Try DNS, TCP or learning.','검색 결과가 없습니다. DNS, TCP 또는 학습을 검색해 보세요.')}</p></section>'''
    elif page=='guide':
        available=release.get('available',False)
        reltitle=esc(str(release.get('version',''))[:80]) if available else t('Find your build','빌드 선택하기')
        relnote=t('Review the release notes and known limitations before installing.','설치하기 전에 릴리스 노트와 알려진 제약을 확인하세요.') if available else t('Open GitHub Releases for the available builds. If no APK is published, use the source build instructions below.','공개된 빌드는 GitHub Releases에서 확인하세요. APK가 게시되지 않았다면 아래의 소스 빌드 안내를 이용하세요.')
        download=release.get('download',REPO+'/releases')
        install=[(t('Get the official build','공식 빌드 받기'),t('Download the APK from this repository’s Releases page. Read its version notes and verification information.','이 저장소의 Releases에서 APK를 받으세요. 버전별 안내와 검증 정보를 읽어 보세요.')),(t('Install on Android','Android에 설치하기'),t('Allow installation for the downloading app only if Android asks. Do not disable your device’s wider security protections.','Android가 요청할 때 다운로드에 사용한 앱에만 설치 권한을 허용하세요. 기기 전체의 보안 기능을 끄지 마세요.')),(t('Review, then connect','확인한 뒤 연결하기'),t('Read the app’s disclosure, choose your settings and approve the Android VPN request. Another active VPN may need to be disconnected.','앱의 안내를 읽고 설정을 선택한 뒤 Android VPN 요청을 승인하세요. 다른 VPN이 실행 중이면 먼저 연결을 해제해야 할 수 있습니다.'))]
        steps=''.join(f'<li><span class="step-number" aria-hidden="true">{i+1}</span><div><h3>{a}</h3><p>{b}</p></div></li>' for i,(a,b) in enumerate(install))
        commands='git clone https://github.com/Sp2ctr2/Tunnel-HTTPS.git\ncd Tunnel-HTTPS\n./gradlew :app:assembleDebug\n./gradlew :app:testDebugUnitTest :app:lintDebug'
        code=f'<div class="code-block"><button type="button" data-copy="build-command">{t("Copy commands","명령 복사")}</button><pre><code id="build-command">{esc(commands)}</code></pre></div><p>{t("Requires JDK 17 and Android SDK API 36. The debug APK is written to","JDK 17과 Android SDK API 36이 필요합니다. 디버그 APK 생성 경로:")} <code>app/build/outputs/apk/debug/app-debug.apk</code></p>'
        body=f'''<section class="wrap page-intro"><p class="section-label">{t('Get started','시작하기')}</p><h1>{title}</h1><p class="lead">{t('Get the build. Check the file.<br>Choose how you connect.','빌드를 받고, 파일을 확인하고.<br>내 연결 방식을 선택하세요.')}</p></section><div class="wrap install-layout"><aside class="install-sidebar"><a href="#download">{t('Download','다운로드')}</a><a href="#install">{t('First connection','첫 연결')}</a><a href="#verify">{t('Verify a file','파일 검증')}</a><a href="#developer">{t('Build from source','소스에서 빌드')}</a></aside><div class="install-content"><section id="download" class="download-sheet"><div class="download-header"><span class="android-label">Android</span><span>{t('7.0 or later','7.0 이상')}</span></div><h2>{reltitle}</h2><p>{relnote}</p><a class="button primary" href="{esc(download)}">{t('Download APK','APK 다운로드') if available else t('View GitHub releases','GitHub 릴리스 보기')}{arrow}</a><p class="small">{t('Beta release','베타 릴리스') if available and release.get('beta') else t('Source available under Apache-2.0.','소스는 Apache-2.0 라이선스로 공개됩니다.')}</p></section><section id="install" class="install-steps"><h2>{t('Your first connection.','처음 연결할 때.')}</h2><ol>{steps}</ol></section><section id="verify" class="verifier"><noscript><p>{t("The browser checker needs JavaScript. Use a local SHA-256 tool to compare your file with the release checksum.","브라우저 검증에는 JavaScript가 필요합니다. 로컬 SHA-256 도구로 파일과 릴리스 체크섬을 비교하세요.")}</p></noscript><h2>{t('Check before installing.','설치 전, 파일을 확인하세요.')}</h2><p>{t('Calculate SHA-256 in your browser and compare it with the checksum from the exact release you downloaded. Your file never leaves this page.','브라우저에서 SHA-256을 계산하고, 다운로드한 정확한 릴리스의 체크섬과 비교하세요. 파일은 이 페이지 밖으로 전송되지 않습니다.')}</p><label class="file-field" for="apk-file"><strong>{t('Choose an APK','APK 파일 선택')}</strong><span>{t('Processed locally. Up to 128 MiB.','로컬에서 처리합니다. 최대 128 MiB.')}</span><input type="file" id="apk-file" name="apk" disabled accept=".apk,application/vnd.android.package-archive"></label><label class="hash-label" for="expected-hash">{t('Expected SHA-256 (optional)','비교할 SHA-256 (선택)')}</label><input type="text" id="expected-hash" name="expected-hash" disabled inputmode="text" maxlength="128" spellcheck="false" autocomplete="off" placeholder="{t('Paste the 64-character release checksum…','릴리스 체크섬 64자 붙여넣기…')}"><output id="hash-result" aria-live="polite">{t('Choose a file to calculate its checksum.','체크섬을 계산할 파일을 선택하세요.')}</output><p class="small">{t('Matching bytes do not prove that an APK is safe. This tool does not verify Android signatures.','체크섬 일치가 APK의 안전성을 증명하지는 않습니다. 이 도구는 Android 서명을 검증하지 않습니다.')}</p></section><section id="developer"><h2>{t('Prefer the source?','소스 코드부터 보고 싶다면.')}</h2>{details(t('Build and test locally','로컬에서 빌드하고 테스트하기'),code)}{details(t('Install a local debug build with ADB','ADB로 로컬 디버그 빌드 설치하기'),'<div class="code-block"><button type="button" data-copy="adb-command">'+t('Copy command','명령 복사')+'</button><pre><code id="adb-command">adb install -r app/build/outputs/apk/debug/app-debug.apk</code></pre></div><p>'+t('A debug build is for development, not the official release signing identity.','디버그 빌드는 개발용이며 공식 릴리스의 서명 ID와 다릅니다.')+'</p>')}</section></div></div>'''
    else:
        faq=[(t('Does it change my public IP or country?','공인 IP나 국가를 바꾸나요?'),t('No. There is no developer-operated remote exit gateway. It is not a location-changing or anonymity service.','아니요. 개발자가 운영하는 원격 출구 게이트웨이가 없습니다. 위치 변경이나 익명화 서비스가 아닙니다.')),(t('Does it read encrypted HTTPS content?','암호화된 HTTPS 내용을 읽나요?'),t('It handles supported connection metadata, including TLS ClientHello information. It does not decrypt HTTPS application payloads.','TLS ClientHello 정보를 포함한 지원 범위의 연결 메타데이터를 처리합니다. HTTPS 애플리케이션 내용을 복호화하지 않습니다.')),(t('Who can see a DNS query?','DNS 질의는 누가 볼 수 있나요?'),t('An external DNS provider receives the queries sent to it and may see your source IP. DNS over HTTPS encrypts the transport to that provider; it does not hide the query from the provider.','외부 DNS 제공자는 전달된 질의를 수신하며 출발지 IP를 볼 수 있습니다. DoH는 제공자까지의 전송을 암호화하지만 제공자로부터 질의를 숨기지는 않습니다.')),(t('Can I use another Android VPN at the same time?','다른 Android VPN과 동시에 쓸 수 있나요?'),t('Android normally permits one active VpnService per user or profile. Tunnel HTTPS may conflict with another VPN or a VpnService-based firewall.','Android는 일반적으로 사용자 또는 프로필별로 활성 VpnService 하나를 허용합니다. 다른 VPN이나 VpnService 기반 방화벽과 충돌할 수 있습니다.')),(t('What does this website store or upload?','이 웹사이트는 무엇을 저장하거나 업로드하나요?'),t('A theme preference may be saved in your browser. The checksum tool reads your selected file locally and makes no upload. No analytics or remote font scripts are included. The website is hosted by GitHub Pages; the host processes ordinary web requests.','테마 설정을 브라우저에 저장할 수 있습니다. 체크섬 도구는 선택한 파일을 로컬에서 읽으며 업로드하지 않습니다. 분석 추적이나 외부 폰트 스크립트를 포함하지 않습니다. GitHub Pages에서 호스팅하므로 호스팅 제공자는 일반적인 웹 요청을 처리합니다.'))]
        body=f'''<section class="wrap page-intro"><p class="section-label">{t('Privacy & boundaries','개인정보와 보호 범위')}</p><h1>{title}</h1><p class="lead">{t('A local networking tool.<br>Not a promise of anonymity.','로컬 네트워크 도구입니다.<br>익명성을 약속하지 않습니다.')}</p></section><section class="wrap privacy-layout"><aside><h2>{t('Know what<br>leaves your device.','기기 밖으로<br>나가는 정보.')}</h2><p>{t('Network metadata is processed locally. Your destination and the DNS provider you choose still receive the requests sent to them.','네트워크 메타데이터는 기기 안에서 처리합니다. 접속 대상과 선택한 DNS 제공자는 전달된 요청을 수신합니다.')}</p><a class="text-link" href="{REPO}/blob/main/docs/release/03_NETWORK_SECURITY_AND_DATA_FLOW.md">{t('Read the data-flow review','데이터 흐름 검토 문서')}{arrow}</a></aside><div>{''.join(details(q,'<p>'+a+'</p>') for q,a in faq)}<div class="security-note"><h2>{t('Found a security issue?','보안 문제를 발견했나요?')}</h2><p>{t('Follow the repository’s security policy. Do not post private DNS logs, browsing data or signing material in a public issue.','저장소의 보안 정책을 따라 주세요. 개인 DNS 로그, 방문 기록, 서명 자료를 공개 이슈에 게시하지 마세요.')}</p><a class="text-link" href="{REPO}/blob/main/SECURITY.md">SECURITY.md{arrow}</a></div></div></section>'''
    other=root+('' if ko else 'ko/')+ROUTES[page]
    description=t('Local Android networking with encrypted DNS, domain filtering and open-source packet handling.','암호화된 DNS, 도메인 필터링과 오픈소스 패킷 처리를 제공하는 로컬 Android 네트워크 앱.')
    csp="default-src 'none'; img-src 'self'; style-src 'self'; script-src 'self'; connect-src 'none'; font-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'"
    dialog=f'''<dialog id="image-dialog" aria-labelledby="dialog-title"><div class="dialog-header"><h2 id="dialog-title">{t('App screen preview','앱 화면 미리보기')}</h2><button type="button" data-close aria-label="{t('Close preview','미리보기 닫기')}">×</button></div><img id="dialog-image" width="390" height="780" alt="{t('Enlarged app interface','확대된 앱 화면')}"><p>{shotnote}</p></dialog>''' if page=='home' else ''
    return f'''<!doctype html><html lang="{lang}" data-page="{page}" data-design="product-20260907" data-theme="light"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="{csp}"><meta name="description" content="{description}"><meta name="theme-color" content="#ffffff"><meta name="color-scheme" content="light dark"><title>{title} — Tunnel HTTPS</title><link rel="canonical" href="{href(page)}"><link rel="alternate" hreflang="en" href="{BASE+ROUTES[page]}"><link rel="alternate" hreflang="ko" href="{BASE+'ko/'+ROUTES[page]}"><meta property="og:title" content="Tunnel HTTPS"><meta property="og:description" content="{description}"><meta property="og:type" content="website"><meta property="og:image" content="{BASE}media/en-connection.png"><link rel="icon" href="{root}favicon.svg" type="image/svg+xml"><script src="{root+assets['boot.js']}"></script><link rel="stylesheet" href="{root+assets['style.css']}"><script defer src="{root+assets['app.js']}"></script></head><body><a class="skip-link" href="#main">{t('Skip to content','본문으로 건너뛰기')}</a><header class="site-header"><div class="wrap header-inner"><a class="brand" href="{local('home')}" translate="no">Tunnel HTTPS<span class="brand-caption">Android</span></a><nav class="desktop-nav" aria-label="{t('Main navigation','주 탐색')}">{nav_html()}</nav><div class="header-tools"><a class="locale-link" lang="{'en' if ko else 'ko'}" href="{other}">{t('한국어','English')}</a><button type="button" class="theme-toggle" aria-label="{t('Switch theme','테마 전환')}" aria-pressed="false"><svg class="moon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 15A8 8 0 0 1 9 4a8 8 0 1 0 11 11Z"/></svg><svg class="sun" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></svg></button><button type="button" class="menu-toggle" aria-controls="mobile-nav" aria-expanded="false">{t('Menu','메뉴')}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 8h16M4 16h16"/></svg></button></div></div><nav id="mobile-nav" class="wrap mobile-nav" aria-label="{t('Mobile navigation','모바일 탐색')}">{nav_html()}</nav></header><main id="main">{body}</main><footer class="wrap site-footer"><div class="footer-top"><a class="brand" href="{local('home')}">Tunnel HTTPS</a><nav aria-label="{t('Footer navigation','하단 탐색')}"><a href="{REPO}">GitHub</a><a href="{REPO}/releases">{t('Releases','릴리스')}</a><a href="{local('privacy')}">{t('Privacy','개인정보')}</a><a href="{REPO}/blob/main/SECURITY.md">{t('Security','보안')}</a></nav></div><div class="footer-bottom"><span>{t('Open source. On your device.','오픈소스. 당신의 기기 안에서.')}</span><a href="{REPO}/blob/main/LICENSE">Apache-2.0</a></div></footer>{dialog}<div id="toast" role="status" aria-live="polite" hidden></div></body></html>'''

def build(out,resolve=False):
    out.mkdir(parents=True,exist_ok=True)
    (out/'assets').mkdir(exist_ok=True)
    assets={}
    for name in ('boot.js','app.js','style.css'):
        data=(HERE/name).read_bytes();target='assets/'+name.rsplit('.',1)[0]+'.'+hashlib.sha256(data).hexdigest()[:12]+'.'+name.rsplit('.',1)[1]
        (out/target).write_bytes(data);assets[name]=target
    release=read_release() if resolve else {'available':False,'url':REPO+'/releases','revision':REV}
    for lang in ('en','ko'):
        for page,path in ROUTES.items():
            p=out/('ko/' if lang=='ko' else '')/path/'index.html';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(render(lang,page,release,assets),encoding='utf-8')
    (out/'favicon.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="#191919"/><path d="M16 19h32v7H36v23h-8V26H16Z" fill="white"/></svg>')
    (out/'.nojekyll').touch()
    (out/'404.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Page not found — Tunnel HTTPS</title><h1>Page not found</h1><p>This address is not part of the website.</p><a href="/Tunnel-HTTPS/">Return to Tunnel HTTPS</a></html>')
    (out/'build.json').write_text(json.dumps({'design':'product-20260907','commit':REV,'routes':8,'release':release,'assets':assets},indent=2)+'\n')
    (out/'sitemap.xml').write_text('<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'+''.join('<url><loc>'+BASE+lang+path+'</loc></url>' for lang in ('','ko/') for path in ROUTES.values())+'</urlset>')
    print(json.dumps({'output':str(out),'routes':8,'design':'product-20260907','release_available':release.get('available',False)}))
if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,default=ROOT/'_site');ap.add_argument('--resolve-release',action='store_true');args=ap.parse_args();build(args.output,args.resolve_release)
