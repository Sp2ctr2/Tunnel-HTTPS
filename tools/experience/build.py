#!/usr/bin/env python3
"""Build Tunnel HTTPS Path Studio using only the Python standard library.

Content is pre-rendered. JavaScript enhances navigation and local tools; it is
not needed to read documentation. Release links never point to draft assets.
"""
from __future__ import annotations
import argparse
import html
import json
import os
from pathlib import Path

SOURCE = Path(__file__).resolve().parent
REPO = "https://github.com/Sp2ctr2/Tunnel-HTTPS"
SITE = "https://sp2ctr2.github.io/Tunnel-HTTPS"
CODE = REPO + "/blob/main/app/src/main/java/com/tunnelvpn/app/"
BUILD = "path-studio-20260907"
LANG = "en"

def t(en: str, ko: str) -> str:
    return ko if LANG == "ko" else en

def esc(value: object) -> str:
    return html.escape(str(value), quote=True)

def icon(name: str = "arrow") -> str:
    paths = {
        "arrow": '<path d="M5 12h14m-6-6 6 6-6 6"/>',
        "external": '<path d="M7 17 17 7M7 7h10v10"/>',
        "search": '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/>',
        "theme": '<path d="M20 15.5A8.5 8.5 0 0 1 8.5 4 8.5 8.5 0 1 0 20 15.5Z"/>',
        "motion": '<path d="M8 5v14M16 5v14"/>',
        "menu": '<path d="M4 8h16M4 16h16"/>',
        "close": '<path d="m6 6 12 12M18 6 6 18"/>',
        "nodes": '<circle cx="5" cy="12" r="2"/><circle cx="18" cy="5" r="2"/><circle cx="18" cy="19" r="2"/><path d="m7 11 9-5M7 13l9 5"/>',
    }
    return f'<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">{paths[name]}</svg>'

def btn(label: str, href: str, kind: str = "", external: bool = False) -> str:
    return f'<a class="button {kind}" href="{esc(href)}"{(" rel=\"noreferrer\"" if external else "")}>{label}{icon("external" if external else "arrow")}</a>'

def textlink(label: str, href: str) -> str:
    return f'<a class="text-link" href="{esc(href)}">{label}{icon("external" if href.startswith("https:") else "arrow")}</a>'

def root_prefix(page: str) -> str:
    return "../" * ((1 if LANG == "ko" else 0) + (1 if page else 0))

def route(page: str, destination: str = "", lang: str | None = None) -> str:
    language = LANG if lang is None else lang
    return root_prefix(page) + ("ko/" if language == "ko" else "") + (destination + "/" if destination else "") or "./"

def eyebrow(label: str) -> str:
    return f'<p class="eyebrow"><span class="dot" aria-hidden="true"></span>{label}</p>'

def logo() -> str:
    return '<span class="mark" aria-hidden="true"></span><span class="wordmark">tunnel <span class="slash">/</span> https</span>'

def navigation(page: str, mobile: bool = False) -> str:
    entries = [("", t("Overview", "소개")), ("engineering", t("Explore", "엔진 탐색")), ("guide", t("Get started", "시작하기")), ("privacy", t("Privacy", "개인정보"))]
    return "".join(f'<a href="{route(page, slug)}"{(" aria-current=\"page\"" if page == slug else "")}>{label}</a>' for slug, label in entries)

def shell(page: str, title: str, description: str, content: str) -> str:
    prefix = root_prefix(page)
    assets = prefix + "assets/"
    canonical = SITE + ("/ko" if LANG == "ko" else "") + ("/" + page if page else "") + "/"
    search_links = [
        (t("Overview", "프로젝트 소개"), "", "", "01"),
        (t("Packet explorer", "패킷 흐름 탐색"), "engineering", "", "02"),
        (t("DNS resolution", "DNS 해석"), "engineering", "#dns", "DNS"),
        (t("TLS ClientHello", "TLS ClientHello"), "engineering", "#tls", "TLS"),
        (t("QUIC behavior", "QUIC 동작"), "engineering", "#quic", "QUIC"),
        (t("Build & installation", "빌드 및 설치"), "guide", "#build", "03"),
        (t("Local file verification", "로컬 파일 검증"), "guide", "#verify", "SHA-256"),
        (t("Privacy & boundaries", "개인정보 및 동작 범위"), "privacy", "", "04"),
    ]
    items = "".join(f'<li><a href="{route(page,p)}{anchor}"><span>{label}</span><small>{key}</small></a></li>' for label,p,anchor,key in search_links)
    close = f'<button class="icon-btn" type="button" data-close aria-label="{t("Close", "닫기")}">{icon("close")}</button>'
    return f'''<!doctype html>
<html lang="{LANG}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="{esc(description)}">
<meta name="build" content="{BUILD}">
<meta name="source-commit" content="{esc(os.environ.get('GITHUB_SHA','local-preview'))}">
<meta name="color-scheme" content="light dark">
<meta name="referrer" content="no-referrer">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; worker-src 'none'">
<title>{esc(title)} · Tunnel HTTPS</title>
<link rel="canonical" href="{canonical}">
<link rel="alternate" hreflang="en" href="{SITE}/{page+'/' if page else ''}">
<link rel="alternate" hreflang="ko" href="{SITE}/ko/{page+'/' if page else ''}">
<link rel="icon" href="{assets}mark.svg" type="image/svg+xml">
<meta property="og:title" content="{esc(title)} · Tunnel HTTPS">
<meta property="og:description" content="{esc(description)}">
<meta property="og:type" content="website">
<meta property="og:url" content="{canonical}">
<script src="{assets}boot.js"></script>
<link rel="stylesheet" href="{assets}site.css">
<script src="{assets}site.js" defer></script>
</head>
<body data-page="{page or 'home'}">
<a class="skip" href="#main">{t('Skip to content','본문으로 이동')}</a>
<header class="site-header"><div class="wrap header-inner">
<a class="brand" href="{route(page)}" aria-label="Tunnel HTTPS {t('home','홈')}">{logo()}</a>
<nav class="nav" aria-label="{t('Primary','주 메뉴')}">{navigation(page)}</nav>
<div class="tools">
<button class="icon-btn search-trigger" data-open="search-dialog" type="button" aria-label="{t('Search pages','페이지 검색')}" hidden>{icon('search')}<kbd>⌘ K</kbd></button>
<button class="icon-btn theme-control" data-theme-toggle type="button" aria-label="{t('Toggle color theme','밝은 테마와 어두운 테마 전환')}" hidden>{icon('theme')}</button>
<button class="icon-btn motion-control" data-motion-toggle type="button" aria-label="{t('Pause motion','움직임 일시 정지')}" aria-pressed="false" hidden>{icon('motion')}</button>
<a class="lang-link" lang="{'en' if LANG=='ko' else 'ko'}" href="{route(page,page,'en' if LANG=='ko' else 'ko')}" aria-label="{'English' if LANG=='ko' else '한국어'}">{'EN' if LANG=='ko' else 'KO'}</a>
<button class="menu-trigger" type="button" data-open="menu-dialog" aria-label="{t('Open menu','메뉴 열기')}" hidden>{icon('menu')}</button>
</div></div></header>
<noscript><nav class="wrap subnav" aria-label="{t('Navigation','메뉴')}">{navigation(page)}</nav></noscript>
<main id="main" class="wrap">{content}</main>
<footer class="wrap footer"><div class="footer-top"><a class="brand" href="{route(page)}">{logo()}</a><nav class="footer-links" aria-label="{t('Footer','하단 메뉴')}">{navigation(page)}<a href="{REPO}">GitHub ↗</a></nav></div>
<div class="footer-bottom"><span>{t('Built in the open. Runs on your device.','열린 소스. 기기 안에서 동작하는 엔진.')} · Apache-2.0</span><span>{t('Local engine. Not an anonymity service.','로컬 엔진이며 익명화 서비스가 아닙니다.')} <a href="{REPO}/blob/main/SECURITY.md">{t('Security ↗','보안 ↗')}</a></span></div></footer>
<dialog id="search-dialog" aria-labelledby="search-title"><div class="dialog-head"><h2 id="search-title">{t('Find your way.','무엇을 찾으세요?')}</h2>{close}</div><div class="dialog-body">
<label class="sr-only" for="search-input">{t('Search pages and topics','페이지 및 주제 검색')}</label><input id="search-input" type="search" placeholder="{t('Try DNS, install, privacy…','DNS, 설치, 개인정보…')}" autocomplete="off" maxlength="100">
<ul class="search-items">{items}</ul><p class="small" id="search-empty" role="status" hidden>{t('No matching pages. Try another term.','검색 결과가 없습니다. 다른 단어를 입력해 주세요.')}</p><p class="small">{t('↑ ↓ to navigate · Enter to open · Esc to close. Searches this site only.','↑ ↓ 이동 · Enter 열기 · Esc 닫기. 이 사이트 안에서만 검색합니다.')}</p></div></dialog>
<dialog id="menu-dialog" aria-labelledby="menu-title"><div class="dialog-head"><h2 id="menu-title">{t('Explore Tunnel.','Tunnel 살펴보기')}</h2>{close}</div><div class="dialog-body"><nav class="mobile-links" aria-label="{t('Mobile navigation','모바일 메뉴')}">{navigation(page)}<a href="{REPO}">GitHub ↗</a></nav><button type="button" class="text-link" data-motion-toggle aria-pressed="false">{t('Pause / resume motion','움직임 정지 / 재개')}</button></div></dialog>
<div class="toast" role="status" aria-live="polite" hidden></div>
</body></html>'''

def faq(question: str, answer: str) -> str:
    return f'<details><summary>{question}</summary><div class="answer">{answer}</div></details>'

def cta(page: str) -> str:
    return f'''<section><div class="cta"><div>{eyebrow(t('OPEN SOURCE / ON DEVICE','오픈소스 / 온디바이스'))}<h2>{t('Go beneath<br>the connect button.','연결 버튼 너머의<br>엔진을 만나보세요.')}</h2></div><div><div class="button-row">{btn(t('Get started','시작하기'),route(page,'guide'),'lime')}{btn(t('View source','소스 보기'),REPO,'lime',True)}</div><p class="small">{t('Android 7.0+ · Kotlin · No developer-operated VPN gateway.','Android 7.0 이상 · Kotlin · 개발자 운영 원격 VPN 게이트웨이 없음.')}</p></div></div></section>'''

def home() -> str:
    portal = f'''<div class="hero-visual"><div class="portal">
<div class="portal-head"><span>TUNNEL / LOCAL ENGINE</span><span><span class="dot"></span> {t('ON YOUR DEVICE','기기 내부에서')}</span></div>
<div class="portal-art" aria-hidden="true"><div class="tunnel">{''.join('<i class="gate"></i>' for _ in range(5))}</div><div class="floating-label a">01 / INGRESS<b>VpnService + TUN</b></div><div class="floating-label b">02 / EGRESS<b>{t('Direct sockets','직접 연결 소켓')}</b></div></div>
<div class="portal-bottom"><div class="portal-options" aria-label="{t('Protocol preview','프로토콜 미리보기')}" hidden>{''.join(f'<button type="button" data-preview="{p}" aria-pressed="{str(p=="dns").lower()}">{p.upper()}</button>' for p in ['dns','tls','quic'])}</div><p class="portal-summary" aria-live="polite">{t('DNS queries follow your local policy. Encrypted resolution goes to the configured provider.','DNS 질의에 로컬 정책을 적용합니다. 암호화 DNS는 설정한 해석기로 전송됩니다.')}</p></div></div><div class="portal-caption"><span>{t('Architecture illustration, not live traffic.','실시간 트래픽이 아닌 구조 시각화입니다.')}</span><a href="{route('','engineering')}">{t('Open explorer ↗','엔진 탐색 ↗')}</a></div></div>'''
    tiles = [
        ('PACKET ENGINE',t('A closer look<br>at every packet.','패킷 하나까지,<br>더 깊게.'),t('IPv4 and IPv6 normalization, bounded fragment handling, and local TCP/UDP relay state.','IPv4·IPv6 정규화, 한도가 있는 조각 처리, 로컬 TCP·UDP 릴레이 상태 관리.'),'<div class="tile-visual" aria-hidden="true"><span class="packet-block">IPv4</span><span class="packet-block active">TUN</span><span class="packet-block">IPv6</span></div>', 'engineering'),
        ('DNS POLICY',t('Names deserve<br>a second check.','이름 해석에도<br>검증이 필요하니까.'),t('Message validation, cache policy, resolver racing and encrypted DNS. With explicit failure paths.','메시지 검증, 캐시 정책, 해석기 경쟁 및 암호화 DNS. 실패 경로까지 명시적으로 처리합니다.'),'<div class="tile-visual dns-visual" aria-hidden="true"><div><span>example.org</span><b>QUESTION</b></div><div><span>response → validator</span><b>CHECK</b></div><div><span>policy → cache</span><b>TTL</b></div></div>','engineering#dns'),
        ('LOCAL LEARNING',t('Adapts locally.<br>Stays accountable.','기기에서 적응하고,<br>경계를 지킵니다.'),t('Turbo learns from connection outcomes. The experimental Aegis neural policy remains observation-only.','Turbo는 연결 결과에서 학습합니다. 실험적인 Aegis 신경망 정책은 관찰 전용으로 유지됩니다.'),f'<div class="tile-visual adapt-visual" aria-hidden="true"><div class="mini-orbit">{icon("nodes")}</div></div>','engineering#learning'),
    ]
    tilehtml = ''
    for kicker, title, desc, visual, target in tiles:
        dest, _, anchor = target.partition('#')
        tilehtml += f'<article class="tile" data-reveal>{visual}<p class="eyebrow">{kicker}</p><h3>{title}</h3><p>{desc}</p>{textlink(t("Explore the implementation","구현 살펴보기"),route("",dest)+("#"+anchor if anchor else ""))}</article>'
    return f'''<section class="hero"><div>{eyebrow(t('ANDROID NETWORK ENGINE / SOURCE AVAILABLE','안드로이드 네트워크 엔진 / 소스 공개'))}<h1>{t('Control the path.<br><em>Keep it local.</em>','네트워크의 흐름을,<br><em>기기 안에서.</em>')}</h1><p class="lead">{t('A network engine beneath the connect button. Inspect packets, validate DNS and adapt connections on Android — without a developer-operated remote VPN gateway.','연결 버튼 아래의 네트워크 엔진. 안드로이드에서 패킷을 검사하고 DNS를 검증하며 연결 전략을 조정합니다. 개발자 운영 원격 VPN 게이트웨이 없이.')}</p><div class="button-row">{btn(t('Explore the engine','엔진 직접 탐색'),route('','engineering'))}{btn(t('Get started','시작하기'),route('','guide'),'secondary')}</div><div class="hero-foot"><span>Android 7.0+</span><span>English / 한국어</span><span>Apache-2.0</span></div></div>{portal}</section>
<div class="facts">{''.join(f'<div class="fact"><span class="number">0{i+1}</span><div><b>{a}</b><small>{b}</small></div></div>' for i,(a,b) in enumerate([(t('On-device by design.','처리는 기기 안에서.'),t('Local packet and connection policy.','패킷과 연결 정책을 로컬에서 처리.')),(t('Open to inspection.','검증할 수 있는 소스.'),t('Follow decisions back to the code.','각 판단의 구현 코드를 직접 확인.')),(t('No remote VPN gateway.','별도 원격 VPN 서버 없이.'),t('DNS providers still receive queries.','DNS 해석기는 질의를 전달받습니다.'))]))}</div>
<section><div class="section-head"><div>{eyebrow('01 / BENEATH THE SURFACE')}<h2>{t('Small surface.<br><em>Deep engineering.</em>','간결한 화면.<br><em>깊이 있는 엔진.</em>')}</h2></div><p>{t('The interface is only the beginning. Follow the local packet path through the components that actually make the decisions.','인터페이스는 시작일 뿐입니다. 실제로 판단을 내리는 구성 요소를 따라 로컬 패킷 경로를 살펴보세요.')}</p></div><div class="bento">{tilehtml}</div>
<div class="feature-banner" data-reveal><div><h3>{t('Don’t just read the architecture.<br>Walk through it.','구조도를 읽는 대신,<br>직접 따라가 보세요.')}</h3><p>{t('Pick a protocol, move between stages, and see what happens at each boundary. No network traffic is sent.','프로토콜을 선택하고 각 단계를 이동하며 처리 범위를 확인하세요. 실제 네트워크 트래픽은 전송하지 않습니다.')}</p>{textlink(t('Enter the packet explorer','패킷 탐색기 열기'),route('','engineering'))}</div><div class="mini-flow" aria-hidden="true"><span>01<br>PACKET</span><span>02<br>POLICY</span><span>03<br>SOCKET</span></div></div></section>
<section class="rule"><div class="principle">{eyebrow('02 / A DIFFERENT NETWORK SHAPE')}<p>{t('<strong>Your packets don’t need our VPN server.</strong> There isn’t one. The local engine works with Android’s upstream sockets, your selected DNS provider and the destination.','<strong>패킷을 보낼 개발자 VPN 서버는 없습니다.</strong> 로컬 엔진이 안드로이드의 업스트림 소켓, 설정한 DNS 해석기, 목적지와 함께 동작합니다.')}</p>{textlink(t('See what leaves the device','기기 밖으로 나가는 데이터'),route('','privacy'))}</div></section>
<section class="split rule"><div>{eyebrow('03 / KNOW THE BOUNDARIES')}<h2>{t('Clarity is<br><em>part of the product.</em>','명확한 경계도<br><em>제품의 일부입니다.</em>')}</h2><p class="lead">{t('What it does. What it doesn’t. Before you install.','무엇을 하고, 무엇을 하지 않는지. 설치하기 전에 확인하세요.')}</p></div><div class="faq">{faq(t('Does it change my IP address or country?','IP 주소나 국가가 바뀌나요?'),t('No. There is no remote exit-node service. Destinations still see the public address of the network you use.','아닙니다. 원격 출구 노드 서비스가 없으므로 목적지에는 현재 네트워크의 공인 주소가 보입니다.'))}{faq(t('Does it decrypt HTTPS content?','HTTPS 내용을 복호화하나요?'),t('No. ClientHello metadata handling and fragmentation are not TLS interception or HTTPS content decryption.','아닙니다. ClientHello 메타데이터 처리와 분할은 TLS 가로채기나 HTTPS 본문 복호화가 아닙니다.'))}{faq(t('Where is the downloadable APK?','APK는 어디서 받나요?'),t(f'GitHub Releases is the distribution channel. <a class="text-link" href="{route("","guide")}">Check availability and installation</a>. Draft artifacts are not public downloads.',f'GitHub Releases에서 배포합니다. <a class="text-link" href="{route("","guide")}">공개 상태 및 설치 안내</a>를 확인하세요. 초안 파일은 공개 다운로드로 표시하지 않습니다.'))}{faq(t('Can I inspect how it works?','실제 구현을 확인할 수 있나요?'),t(f'Yes. <a class="text-link" href="{REPO}">Browse the repository</a>. Source, tests and dated engineering records are available to review.',f'네. <a class="text-link" href="{REPO}">저장소에서</a> 소스, 테스트 및 날짜별 기술 기록을 확인할 수 있습니다.'))}</div></section>{cta('')}'''

# Each stage is deliberately small, and references the implementation it describes.
def protocols() -> list[dict]:
    ingress = (t('Capture & normalize','수신 및 정규화'), 'VpnService / TUN', t('Android routes selected traffic into the local TUN interface. The engine validates IPv4 or IPv6 structure before protocol-specific handling.','안드로이드가 선택된 트래픽을 로컬 TUN 인터페이스로 보냅니다. 엔진은 프로토콜별 처리에 앞서 IPv4 또는 IPv6 구조를 검증합니다.'), 'LocalProtectionEngine.kt')
    return [
        dict(id='dns', title='DNS', note=t('Configured DNS mode determines the upstream path. Resolver providers still receive queries.','설정한 DNS 모드에 따라 업스트림 경로가 달라집니다. DNS 해석기는 질의를 전달받습니다.'), steps=[ingress,
            (t('Apply DNS policy','DNS 정책 적용'),'Policy / cache',t('Read the query, apply local domain policy and consult the cache. Cached answers are subject to the implementation’s TTL and size limits.','질의를 읽고 로컬 도메인 정책과 캐시를 확인합니다. 캐시 응답에는 구현된 TTL 및 크기 제한이 적용됩니다.'),'DnsProtectionEngine.kt'),
            (t('Choose a resolver path','해석 경로 선택'),'DoH / system DNS',t('Encrypted resolution uses the configured DoH path. Resolver racing and health logic can select responses. System DNS is a separate configured path, not an anonymity layer.','암호화 해석은 설정된 DoH 경로를 사용합니다. 해석기 경쟁과 상태 로직으로 응답을 선택할 수 있습니다. 시스템 DNS는 별도의 설정 경로이며 익명화 계층이 아닙니다.'),'SecureResolverRace.kt'),
            (t('Validate the response','응답 검증'),'Validation / reply',t('Inspect the response structure and question correspondence before accepting an answer. Policy and validation outcomes determine the reply returned through the local interface.','응답 구조와 질의 일치 여부를 검사한 후 응답을 수용합니다. 정책과 검증 결과에 따라 로컬 인터페이스로 반환할 응답을 결정합니다.'),'DnsMessageValidator.kt')]),
        dict(id='tcp',title='TCP',note=t('The local TCP relay is not a complete replacement for the Internet-facing Linux TCP stack.','로컬 TCP 릴레이는 인터넷측 리눅스 TCP 스택을 완전히 대체하지 않습니다.'), steps=[ingress,
            (t('Track local TCP state','로컬 TCP 상태 추적'),'Sequence / ACK / window',t('The userspace relay maintains local connection state, sequence and acknowledgement handling, windows and bounded retransmission state.','사용자 공간 릴레이가 로컬 연결 상태, 시퀀스와 ACK, 윈도 및 한도가 있는 재전송 상태를 관리합니다.'),'TurboTcpForwarder.kt'),
            (t('Forward through a socket','소켓으로 전달'),'Protected upstream',t('The upstream connection uses Android/Linux sockets protected from VPN recursion. The platform, rather than this local relay, supplies Internet-facing TCP transport.','업스트림 연결은 VPN 재귀를 방지하도록 보호된 안드로이드/리눅스 소켓을 사용합니다. 인터넷측 TCP 전송은 로컬 릴레이가 아닌 플랫폼이 제공합니다.'),'TurboTcpForwarder.kt'),
            (t('Close & reclaim state','종료 및 상태 회수'),'Lifecycle / budgets',t('Connection limits, timeout handling and cleanup bound retained state. These limits are part of the architecture, not a claim of unlimited concurrency.','연결 수 제한, 타임아웃 및 정리 로직으로 보유 상태에 한도를 둡니다. 이러한 제한은 구조의 일부이며 무제한 동시성을 의미하지 않습니다.'),'TurboTcpForwarder.kt')]),
        dict(id='tls',title='TLS',note=t('TLS metadata adaptation does not decrypt application payloads or change the destination identity.','TLS 메타데이터 조정은 애플리케이션 본문을 복호화하거나 목적지 신원을 변경하지 않습니다.'), steps=[ingress,
            (t('Establish the relay path','릴레이 경로 구성'),'Local TCP relay',t('The TCP relay handles the local leg of the connection and prepares the upstream socket. TLS processing occurs inside this transport path, not as a separate network protocol dispatcher.','TCP 릴레이가 로컬 구간을 처리하고 업스트림 소켓을 준비합니다. TLS 처리는 독립된 네트워크 분기가 아니라 이 전송 경로 안에서 수행됩니다.'),'TurboTcpForwarder.kt'),
            (t('Inspect ClientHello metadata','ClientHello 메타데이터 검사'),'Parser / strategy',t('Read supported TLS ClientHello metadata and determine a fragmentation strategy. Turbo can use local connection outcomes to adapt candidate selection.','지원하는 TLS ClientHello 메타데이터를 읽고 분할 전략을 결정합니다. Turbo는 로컬 연결 결과를 활용해 후보 선택을 조정할 수 있습니다.'),'TlsClientHello.kt'),
            (t('Continue toward the destination','목적지로 연결 지속'),'Original TLS endpoints',t('The selected strategy affects forwarding. TLS cryptography remains with the original endpoints. Aegis observes in shadow mode and does not receive live routing authority.','선택한 전략을 전달 과정에 적용합니다. TLS 암호화는 원래 양 끝점이 담당합니다. Aegis는 섀도 모드에서 관찰하며 실제 라우팅 권한은 갖지 않습니다.'),'TurboAiEngine.kt')]),
        dict(id='quic',title='QUIC',note=t('This is structural QUIC-aware handling, not a full QUIC stack or a QUIC-to-TCP translator.','완전한 QUIC 스택이나 QUIC-TCP 변환기가 아닌, 구조를 인식하는 제한적 처리입니다.'),steps=[ingress,
            (t('Enter the UDP relay','UDP 릴레이 진입'),'Bounded UDP flows',t('UDP datagrams use the local relay path with explicit flow limits. Port 443 alone is not sufficient evidence to treat an arbitrary datagram as QUIC.','UDP 데이터그램은 명시적 흐름 제한이 있는 로컬 릴레이를 거칩니다. 포트 443이라는 이유만으로 임의의 데이터그램을 QUIC로 취급하지 않습니다.'),'TurboUdpForwarder.kt'),
            (t('Inspect supported structures','지원 구조 검사'),'Initial / Retry / version',t('The guard recognizes supported QUIC Initial structures and selected server responses. Unsupported or malformed input is not treated as verified QUIC traffic.','가드가 지원하는 QUIC Initial 구조와 일부 서버 응답을 식별합니다. 지원하지 않거나 잘못된 입력을 검증된 QUIC 트래픽으로 간주하지 않습니다.'),'QuicHttp3Guard.kt'),
            (t('Apply destination policy','목적지별 정책 적용'),'Observation / fallback',t('Destination-specific evidence can influence temporary fallback behavior. A client may retry over TCP; the engine does not convert encrypted QUIC payloads into TCP.','목적지별 증거가 일시적인 폴백 동작에 영향을 줄 수 있습니다. 클라이언트가 TCP로 재시도할 수 있지만 엔진이 암호화된 QUIC 본문을 TCP로 변환하지는 않습니다.'),'QuicHttp3Guard.kt')]),
    ]

def engineering() -> str:
    data = protocols()
    fallback = ''
    for p in data:
        steps = ''.join(f'<li data-source="{CODE}{file}"><h3>{title}</h3><small>{sub}</small><p>{desc}</p>{textlink(t("Inspect source","소스 확인"),CODE+file)}</li>' for title,sub,desc,file in p['steps'])
        fallback += f'<article id="{p["id"]}" data-protocol="{p["id"]}"><h2>{p["title"]}</h2><p class="protocol-note">{p["note"]}</p><ol>{steps}</ol></article>'
    first = data[0]['steps']
    stage_buttons = ''.join(f'<button class="stage" id="stage-{i}" type="button" data-stage="{i}" aria-label="{esc(title)}"{(" aria-current=\"step\"" if i==0 else "")}><span class="stage-num">0{i+1}</span><span><b>{title}</b><small>{sub}</small></span>{icon("arrow")}</button>' for i,(title,sub,desc,file) in enumerate(first))
    rows = [
        ('IPv4 / IPv6',t('Bounded','제한적 지원'),t('Normalization and selected extension / fragment handling.','정규화 및 일부 확장 헤더·조각 처리.'),'Ipv6PacketNormalizer.kt'),
        ('TCP / UDP',t('Local relay','로컬 릴레이'),t('Local transport state; platform sockets upstream.','로컬 전송 상태를 관리하며 업스트림은 플랫폼 소켓 사용.'),'TurboTcpForwarder.kt'),
        ('DNS / DoH',t('Validated','검증 경로'),t('Message, cache and resolver policy; provider availability varies.','메시지·캐시·해석기 정책. 공급자 상태에 따라 가용성 변동.'),'DnsMessageValidator.kt'),
        ('DNS64 / NAT64',t('Conditional','조건부'),t('Discovery and synthesis depend on the underlying network.','발견 및 합성은 기반 네트워크 조건에 의존.'),'Dns64Packet.kt'),
        ('TLS / QUIC',t('Partial','부분 지원'),t('Metadata / structure handling, not content decryption.','메타데이터·구조 처리이며 본문 복호화가 아님.'),'QuicHttp3Guard.kt'),
        ('Aegis Local 2',t('Shadow','섀도 모드'),t('Experimental neural evaluation without live control authority.','실제 제어 권한 없이 실험적 신경망 평가.'),'AegisLocal2Engine.kt'),
    ]
    support = ''.join(f'<div class="support-row"><h3>{name}</h3><span class="pill">{state}</span><p>{note}</p>{textlink(t("Code","코드"),CODE+file)}</div>' for name,state,note,file in rows)
    return f'''<section class="page-head">{eyebrow('PATH STUDIO / 01')}<h1>{t('Follow a packet.<br><em>Understand the engine.</em>','패킷을 따라,<br><em>엔진의 안쪽으로.</em>')}</h1><p class="lead">{t('Choose a protocol and step through the local path. Every stage links to implementation code. This is an interactive explanation, not a packet capture or network test.','프로토콜을 골라 로컬 경로를 단계별로 살펴보세요. 각 단계는 구현 코드로 연결됩니다. 패킷 캡처나 네트워크 테스트가 아닌 인터랙티브 설명입니다.')}</p></section>
<section class="lab"><div class="lab-toolbar"><div class="segmented" role="tablist" aria-label="{t('Protocol','프로토콜')}" hidden>{''.join(f'<button id="tab-{p["id"]}" role="tab" aria-selected="{str(p["id"]=="dns").lower()}" aria-controls="explorer-panel" type="button" data-tab="{p["id"]}">{p["title"]}</button>' for p in data)}</div><span class="pill">{t('ILLUSTRATIVE / NO TRAFFIC SENT','설명용 / 트래픽 전송 없음')}</span></div>
<div class="lab-layout" id="explorer-panel" role="tabpanel" aria-labelledby="tab-dns" hidden><div class="lab-map"><p class="eyebrow"><span class="dot"></span><span data-protocol-title>DNS / LOCAL PATH</span></p><div class="stages">{stage_buttons}</div></div><div class="inspector"><div class="step-progress" aria-hidden="true">{''.join('<i></i>' for _ in range(4))}</div><p class="eyebrow" data-step-caption>STEP 01 / 04</p><div data-inspector-copy><h3 data-step-title>{first[0][0]}</h3><p class="description" data-step-description>{first[0][2]}</p><p class="boundary" data-step-boundary>{data[0]['note']}</p></div>{textlink(t('Inspect this implementation','이 단계의 구현 코드'),CODE+first[0][3]).replace('class="text-link"','class="text-link" data-step-source')}
<div class="inspector-actions"><button type="button" class="button secondary" data-prev>{t('Previous','이전')}</button><span class="small mono" data-step-counter>01 / 04</span><button type="button" class="button" data-next>{t('Next step','다음 단계')}{icon()}</button></div></div></div>
<div class="lab-footer"><span>{t('Local processing ≠ every request stays on the device.','로컬 처리 ≠ 모든 요청이 기기 안에 머무르는 것은 아닙니다.')}</span><a href="{route('engineering','privacy')}">{t('Data boundaries ↗','데이터 처리 범위 ↗')}</a></div><div class="protocol-fallback">{fallback}</div><div class="sr-only" role="status" data-lab-announcement></div></section>
<section class="rule" id="learning"><div class="section-head"><div>{eyebrow('BOUNDED ADAPTATION')}<h2>{t('Learning with<br><em>a safety boundary.</em>','학습에도<br><em>명확한 경계를.</em>')}</h2></div><p>{t('Turbo selects strategies from local context and outcomes. Aegis Local 2 evaluates an experimental neural policy in shadow mode. These are different responsibilities.','Turbo는 로컬 맥락과 결과로 전략을 선택합니다. Aegis Local 2는 실험적 신경망 정책을 섀도 모드로 평가합니다. 두 역할은 구분됩니다.')}</p></div><div class="feature-banner"><div><h3>Turbo ≠ Aegis</h3><p>{t('Connection strategy selection is not the same as giving an experimental model control of user traffic.','연결 전략을 선택하는 것과 실험 모델에 사용자 트래픽 제어를 맡기는 것은 다릅니다.')}</p>{textlink(t('Read the learning architecture','학습 구조 문서'),REPO+'/blob/main/docs/TURBO_AI_ARCHITECTURE.md')}</div><div class="mini-flow"><span>CONTEXT<br>Turbo</span><span>OUTCOME<br>Local learning</span><span>SHADOW<br>Aegis</span></div></div></section>
<section id="support"><div class="section-head"><div>{eyebrow('IMPLEMENTATION SCOPE')}<h2>{t('Explicit capabilities.<br><em>Explicit limits.</em>','구현된 범위.<br><em>명시적인 한계.</em>')}</h2></div><p>{t('A support label is not a compatibility guarantee. Read the source and dated verification records for the exact scope.','지원 표시는 호환성 보장이 아닙니다. 정확한 범위는 소스와 날짜별 검증 기록을 확인하세요.')}</p></div><div class="support-list">{support}</div></section>{cta('engineering')}'''

def codeblock(name: str, text: str) -> str:
    return f'<div class="code-wrap"><button type="button" class="copy" data-copy="{name}" hidden>{t("Copy","복사")}</button><pre><code id="{name}">{esc(text)}</code></pre></div>'

def guide(release: dict | None) -> str:
    if release:
        availability = f'<strong>{t("Published release","공개 릴리스")}: {esc(release["name"])}</strong><p>{t("Review the release notes and known limitations before installing.","설치 전 릴리스 노트와 알려진 제한사항을 확인하세요.")}</p>' + textlink(t('Open release & assets','릴리스 및 파일 열기'),release['url'])
    else:
        availability = f'<strong>{t("Source available. APK publication pending.","소스는 공개되어 있습니다. APK 공개는 대기 중입니다.")}</strong><p>{t("No published APK was selected for this site build. Draft files are not shown as public downloads. Build locally, or check GitHub Releases for later publications.","이 사이트 빌드 시점에 공개 APK가 선택되지 않았습니다. 초안 파일은 공개 다운로드로 표시하지 않습니다. 로컬에서 빌드하거나 GitHub Releases의 후속 공개를 확인하세요.")}</p>' + textlink(t('Check GitHub Releases','GitHub Releases 확인'),REPO+'/releases')
    build_cmd = 'git clone https://github.com/Sp2ctr2/Tunnel-HTTPS.git\ncd Tunnel-HTTPS\n./gradlew :app:assembleDebug\n./gradlew :app:testDebugUnitTest :app:lintDebug'
    return f'''<section class="page-head">{eyebrow('PATH STUDIO / 02')}<h1>{t('From source<br><em>to your device.</em>','소스에서<br><em>내 기기까지.</em>')}</h1><p class="lead">{t('A clear installation path, inspectable build commands and a file verifier that runs entirely in your browser.','명확한 설치 경로, 확인 가능한 빌드 명령어, 브라우저 안에서만 동작하는 파일 검증 도구.')}</p><div class="subnav"><a class="pill" href="#install">{t('Installation','설치')}</a><a class="pill" href="#build">{t('Build','빌드')}</a><a class="pill" href="#verify">{t('Verify a file','파일 검증')}</a></div></section>
<section class="split" id="install"><div>{eyebrow('START HERE')}<h2>{t('Know what<br><em>you’re installing.</em>','어떤 파일인지,<br><em>먼저 확인하세요.</em>')}</h2><p class="lead">{t('Requires Android 7.0 or later. Start with the source or a published release — never an unknown mirror.','Android 7.0 이상이 필요합니다. 출처를 알 수 없는 미러 대신 소스 또는 공개 릴리스에서 시작하세요.')}</p><div class="notice">{availability}</div></div><ol class="steps-list"><li><div><h3>{t('Choose a trusted artifact.','출처가 확인된 파일 선택.')}</h3><p>{t('Get the APK from an official release, or build it yourself. Read whether it is a beta or a development build.','공식 릴리스의 APK를 받거나 직접 빌드하세요. 베타인지 개발 빌드인지 확인하세요.')}</p></div></li><li><div><h3>{t('Install with explicit consent.','설치와 권한은 직접 승인.')}</h3><p>{t('Approve installation for the downloading app only when Android asks. Grant VPN consent inside Tunnel HTTPS. Do not disable device security globally.','안드로이드가 요청할 때 다운로드에 사용한 앱에 대해서만 설치를 승인하세요. Tunnel HTTPS 안에서 VPN 권한을 허용하고 기기 보안을 전역으로 끄지 마세요.')}</p></div></li><li><div><h3>{t('Start small. Keep a way back.','작게 시작하고 복구 경로 확보.')}</h3><p>{t('Try a few normal connections first. Disconnect if connectivity regresses. Keep a record of the version and network conditions, not browsing history.','일반적인 연결 몇 가지부터 확인하세요. 연결 문제가 생기면 해제하세요. 방문 기록 대신 버전과 네트워크 조건을 남겨 주세요.')}</p></div></li></ol></section>
<section class="rule" id="build"><div class="section-head"><div>{eyebrow('FOR DEVELOPERS')}<h2>{t('Build it.<br><em>Inspect the result.</em>','직접 빌드하고,<br><em>결과를 확인하세요.</em>')}</h2></div><p>{t('JDK 17 and Android SDK API 36. Run from the repository root. Debug builds do not establish the official release signing identity.','JDK 17과 Android SDK API 36을 사용합니다. 저장소 루트에서 실행하세요. 디버그 빌드는 공식 릴리스 서명 신원이 아닙니다.')}</p></div>
{faq(t('Clone, build & test','복제, 빌드 및 테스트'),codeblock('build-code',build_cmd)+f'<p>{t("Output","출력")}: <code>app/build/outputs/apk/debug/app-debug.apk</code></p>')}
{faq(t('Install a local debug APK with ADB','ADB로 로컬 디버그 APK 설치'),'<p>'+t('Connect an authorized device or emulator first.','승인된 기기나 에뮬레이터를 먼저 연결하세요.')+'</p>'+codeblock('adb-code','adb install -r app/build/outputs/apk/debug/app-debug.apk'))}
{faq(t('Updates & signing identity','업데이트 및 서명 신원'),'<p>'+t('Updates to an installed Android package require a compatible signing identity. A differently signed build can require uninstalling the existing app, which removes local data. Do not uninstall merely to bypass a signature warning without checking the source.','설치된 안드로이드 패키지를 업데이트하려면 호환되는 서명 신원이 필요합니다. 다른 키로 서명한 빌드는 기존 앱 삭제가 필요할 수 있으며 로컬 데이터가 지워집니다. 출처를 확인하지 않고 서명 경고를 피하려고 앱을 삭제하지 마세요.')+'</p>')}</section>
<section class="split rule" id="verify"><div>{eyebrow('LOCAL UTILITY / NO UPLOAD')}<h2>{t('Check the file.<br><em>Keep the file.</em>','파일을 확인해도,<br><em>전송하진 않습니다.</em>')}</h2><p class="lead">{t('Compare a file with a SHA-256 checksum from a trusted release. The file is read locally using your browser. It is never uploaded.','신뢰하는 릴리스의 SHA-256 체크섬과 파일을 비교하세요. 브라우저가 로컬에서 파일을 읽으며 업로드하지 않습니다.')}</p><p class="small">{t('A matching hash proves equality to the supplied digest, not who signed the APK, whether it is safe, or whether the checksum source is trustworthy. Android package signature verification is a separate step.','해시 일치는 입력한 체크섬과 파일이 같다는 뜻일 뿐, APK 서명자·안전성·체크섬 출처를 보증하지 않습니다. 안드로이드 패키지 서명 검증은 별도 단계입니다.')}</p></div><div class="verifier"><h3>{t('SHA-256 file check','SHA-256 파일 검증')}</h3><p class="small">{t('Maximum 64 MiB. A trusted 64-character hex digest is required.','최대 64 MiB. 신뢰하는 64자리 16진수 체크섬이 필요합니다.')}</p>
<div data-verifier hidden><label for="expected-hash">{t('Expected SHA-256','기대 SHA-256')}</label><input class="hash-input" id="expected-hash" type="text" maxlength="64" spellcheck="false" autocomplete="off" placeholder="{t('Paste the trusted release checksum','신뢰하는 릴리스 체크섬 붙여넣기')}" aria-describedby="hash-help"><p class="small" id="hash-help">{t('Copied from the release you intend to install.','설치할 릴리스에서 복사한 값이어야 합니다.')}</p><div class="dropzone" data-dropzone><strong>{t('Drop a file here','파일을 여기에 놓으세요')}</strong><label class="sr-only" for="check-file">{t('Choose a file to verify','검증할 파일 선택')}</label><input type="file" id="check-file"><p class="small" data-file-name>{t('…or choose a file from this device.','또는 이 기기에서 파일을 선택하세요.')}</p></div><button class="button" type="button" data-verify>{t('Calculate & compare','계산하고 비교')}{icon()}</button><div class="result" role="status" aria-live="polite" data-result hidden></div></div>
<noscript><p>{t('This local utility requires JavaScript. You can still verify a file with sha256sum in your terminal.','이 로컬 도구는 JavaScript가 필요합니다. 터미널의 sha256sum으로도 검증할 수 있습니다.')}</p></noscript></div></section>'''

def privacy() -> str:
    sections = [
      (t('What is processed locally','로컬에서 처리하는 정보'),t('The packet and policy layers can process DNS names, destination IP addresses and ports, and supported TLS ClientHello metadata. Local processing does not make these data disappear from the network.','패킷 및 정책 계층은 DNS 이름, 목적지 IP 주소와 포트, 지원하는 TLS ClientHello 메타데이터를 처리할 수 있습니다. 로컬 처리가 해당 데이터를 네트워크에서 사라지게 만드는 것은 아닙니다.')),
      (t('What leaves the device','기기 밖으로 나가는 정보'),t('Requests still reach their destinations. If DoH is enabled, queries go to the configured DNS provider, which can observe request metadata and the public source IP. The project does not operate a remote full-traffic VPN gateway.','요청은 여전히 목적지에 도달합니다. DoH를 사용하면 설정한 DNS 공급자에게 질의가 전송되고, 공급자는 요청 메타데이터와 공인 출발지 IP를 볼 수 있습니다. 이 프로젝트는 전체 트래픽을 중계하는 원격 VPN 게이트웨이를 운영하지 않습니다.')),
      (t('What this website does','이 웹사이트의 동작'),t('This website serves static pages, styles and scripts. Page search runs locally. The optional verifier reads the file you select and computes its hash locally; it does not upload files. Theme and motion preferences may be stored in your browser. GitHub’s hosting infrastructure can still process ordinary request logs.','이 웹사이트는 정적 페이지, 스타일과 스크립트를 제공합니다. 페이지 검색은 로컬에서 동작합니다. 선택형 검증 도구는 선택한 파일을 로컬에서 읽고 해시를 계산하며 업로드하지 않습니다. 테마와 움직임 설정은 브라우저에 저장될 수 있습니다. GitHub 호스팅 인프라는 일반적인 요청 로그를 처리할 수 있습니다.')),
      (t('Illustration, not measurement','측정이 아닌 설명'),t('The animated tunnel and packet explorer explain the architecture. They do not connect to your VPN, capture traffic, measure throughput, or report live device state. No performance number is inferred from these visuals.','애니메이션 터널과 패킷 탐색기는 구조를 설명합니다. VPN 연결, 트래픽 캡처, 처리량 측정 또는 실시간 기기 상태 보고를 하지 않습니다. 시각화로 성능 수치를 주장하지 않습니다.')),
      (t('Limitations remain visible','제한사항도 명확하게'),t('This is not an anonymity service, a public-IP changer, a full QUIC stack or an Internet-facing replacement for the Linux TCP stack. Compatibility depends on Android, the device, network conditions and the selected features. Review dated test evidence rather than assuming universal support.','익명화 서비스, 공인 IP 변경기, 완전한 QUIC 스택 또는 인터넷측 리눅스 TCP 스택 대체물이 아닙니다. 호환성은 안드로이드, 기기, 네트워크 조건 및 선택한 기능에 따라 달라집니다. 범용 지원을 가정하지 말고 날짜별 검증 근거를 확인하세요.')),
      (t('Report issues without exposing traffic','트래픽을 공개하지 않고 문제 제보'),t('Use synthetic domains and minimal reproduction details. Do not post browsing history, private DNS names, packet captures, tokens or signing keys in public issues. For exploitable vulnerabilities, follow the repository’s security reporting process.','가상 도메인과 최소한의 재현 정보로 제보하세요. 방문 기록, 비공개 DNS 이름, 패킷 캡처, 토큰 또는 서명 키를 공개 이슈에 게시하지 마세요. 악용 가능한 취약점은 저장소의 보안 제보 절차를 따르세요.')),
    ]
    return f'''<section class="page-head">{eyebrow('PATH STUDIO / 03')}<h1>{t('Local doesn’t mean<br><em>invisible.</em>','로컬이라고 해서<br><em>보이지 않는 건 아닙니다.</em>')}</h1><p class="lead">{t('Clear data boundaries. Honest expectations. Read this before using the engine for sensitive traffic.','명확한 데이터 처리 범위와 정확한 기대치. 민감한 트래픽에 사용하기 전에 읽어 주세요.')}</p></section><div class="reading">{''.join(f'<section class="rule"><h2>{title}</h2><p>{body}</p></section>' for title,body in sections)}<div class="inline-links">{textlink(t('Security policy','보안 정책'),REPO+'/blob/main/SECURITY.md')}{textlink(t('Detailed data flow','상세 데이터 흐름'),REPO+'/blob/main/docs/release/03_NETWORK_SECURITY_AND_DATA_FLOW.md')}</div></div>'''

def select_release(path: Path | None) -> dict | None:
    if path is None:
        return None
    entries = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(entries, list):
        raise ValueError('Expected a releases array')
    for r in entries:
        if r.get('draft') or not r.get('published_at'):
            continue
        url = r.get('html_url', '')
        if not url.startswith(REPO + '/releases/tag/'):
            continue
        apks = [a for a in r.get('assets', []) if a.get('name', '').endswith('.apk') and a.get('state') == 'uploaded']
        if apks:
            return {'name': r.get('name') or r['tag_name'], 'url': url, 'prerelease': bool(r.get('prerelease'))}
    return None

def build(output: Path, release_file: Path | None = None) -> None:
    global LANG
    output.mkdir(parents=True, exist_ok=True)
    assets = output / 'assets'
    assets.mkdir(exist_ok=True)
    for name in ['site.css', 'site.js', 'boot.js']:
        (assets / name).write_bytes((SOURCE / name).read_bytes())
    (assets/'mark.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="18" fill="#152f23"/><g fill="none" stroke="#d1f498" stroke-width="2"><path d="M16 48V28a16 16 0 0 1 32 0v20M23 48V28a9 9 0 0 1 18 0v20M30 48V28a2 2 0 0 1 4 0v20"/></g></svg>',encoding='utf-8')
    release = select_release(release_file)
    for LANG in ['en','ko']:
        base = output / ('ko' if LANG == 'ko' else '')
        for page, renderer, title in [('',home,t('Control the path. Keep it local.','네트워크의 흐름을, 기기 안에서.')),('engineering',engineering,t('Packet explorer','패킷 탐색기')),('guide',lambda: guide(release),t('Build, install & verify','빌드, 설치 및 검증')),('privacy',privacy,t('Privacy & boundaries','개인정보 및 동작 범위'))]:
            target = base / page / 'index.html'
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(shell(page,title,t('An on-device Android network engine. Follow the code, explore packet paths and get started.','온디바이스 안드로이드 네트워크 엔진. 소스와 패킷 경로를 탐색하고 직접 시작하세요.'),renderer()).rstrip()+'\n',encoding='utf-8')
    LANG = 'en'
    # Root-relative links ensure the 404 page also works for deeply nested misses.
    missing = shell('', 'Page not found', 'Return to Tunnel HTTPS.', '<section class="page-head">'+eyebrow('404 / OFF THE PATH')+'<h1>A different turn.<br><em>Same way home.</em></h1><p class="lead">This page does not exist. Return to the overview or open the packet explorer.</p>'+btn('Back to the overview','/Tunnel-HTTPS/')+'</section>')
    missing = missing.replace('href="assets/','href="/Tunnel-HTTPS/assets/').replace('src="assets/','src="/Tunnel-HTTPS/assets/')
    for dest in ['engineering/','guide/','privacy/','ko/']:
        missing = missing.replace(f'href="{dest}',f'href="/Tunnel-HTTPS/{dest}')
    missing = missing.replace('href="./"','href="/Tunnel-HTTPS/"')
    (output/'404.html').write_text(missing+'\n',encoding='utf-8')
    (output/'.nojekyll').touch()
    (output/'site-manifest.json').write_text(json.dumps({'design':BUILD,'source_commit':os.environ.get('GITHUB_SHA','local-preview'),'languages':['en','ko'],'pages':8,'published_apk_selected':bool(release),'release':release},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    urls = [SITE+'/'+('ko/' if lang=='ko' else '')+(p+'/' if p else '') for lang in ['en','ko'] for p in ['','engineering','guide','privacy']]
    (output/'sitemap.xml').write_text('<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'+''.join('<url><loc>'+u+'</loc></url>' for u in urls)+'</urlset>\n',encoding='utf-8')
    (output/'robots.txt').write_text('User-agent: *\nAllow: /\nSitemap: '+SITE+'/sitemap.xml\n',encoding='utf-8')
    print(json.dumps({'output':str(output),'pages':8,'design':BUILD,'published_apk_selected':bool(release)}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=Path('_site'))
    parser.add_argument('--releases',type=Path)
    args = parser.parse_args()
    build(args.output, args.releases)
