"""Build a bilingual, tab-native website. No APK, Android, or signing changes."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import urllib.request
from html import escape
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = 'https://github.com/Sp2ctr2/Tunnel-HTTPS'
BASE = 'https://sp2ctr2.github.io/Tunnel-HTTPS/'
ROUTES = {'home': '', 'controls': 'controls/', 'engineering': 'engineering/', 'privacy': 'privacy/', 'guide': 'guide/'}
ICONS = {
    'home': '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
    'controls': '<path d="M4 7h6m4 0h6M4 17h10m4 0h2"/><circle cx="12" cy="7" r="2"/><circle cx="16" cy="17" r="2"/>',
    'engineering': '<rect x="6" y="6" width="12" height="12" rx="3"/><path d="M9 3v3m6-3v3M9 18v3m6-3v3M3 9h3m-3 6h3m12-6h3m-3 6h3"/><path d="m10 10-2 2 2 2m4-4 2 2-2 2"/>',
    'privacy': '<path d="M12 3 4.5 6v5.5C4.5 16 8 19.3 12 21c4-1.7 7.5-5 7.5-9.5V6Z"/><path d="m8.5 12 2.3 2.3 4.8-5"/>',
    'guide': '<path d="M12 3v12m-4-4 4 4 4-4M5 16v4h14v-4"/>',
    'arrow': '<path d="M5 12h14m-5-5 5 5-5 5"/>',
    'back': '<path d="M19 12H5m5-5-5 5 5 5"/>',
    'external': '<path d="M14 4h6v6m0-6L10 14M10 4H4v16h16v-6"/>',
    'search': '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    'sun': '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1 1m12 12 1 1M5 19l1-1M18 6l1-1"/>',
    'moon': '<path d="M20.7 13.7A9 9 0 0 1 10.3 3.3 9 9 0 1 0 20.7 13.7Z"/>',
    'close': '<path d="m6 6 12 12M6 18 18 6"/>',
    'check': '<path d="m5 12 4 4L19 6"/>',
    'lock': '<rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3"/>',
    'globe': '<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/>',
    'filter': '<path d="M3 5h18l-7 8v6l-4 2v-8Z"/>',
    'apps': '<rect x="4" y="3" width="16" height="18" rx="4"/><path d="M9 7h6m-5 10h4"/>',
    'code': '<path d="m8 6-6 6 6 6m8-12 6 6-6 6M14 3l-4 18"/>',
    'copy': '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M15 8V4H4v11h4"/>',
    'play': '<path d="m8 4 12 8-12 8Z"/>',
    'pause': '<path d="M8 5v14m8-14v14"/>',
    'file': '<path d="M14 3H5v18h14V8Z"/><path d="M14 3v5h5M8 12h8m-8 4h5"/>',
}

def icon(name: str, cls: str = '') -> str:
    return f'<svg class="icon {cls}" viewBox="0 0 24 24" aria-hidden="true">{ICONS[name]}</svg>'


def release_metadata() -> dict:
    """Only anonymous, published release metadata is eligible. Never expose a draft."""
    result = {'available': False, 'url': REPO + '/releases'}
    try:
        req = urllib.request.Request('https://api.github.com/repos/Sp2ctr2/Tunnel-HTTPS/releases?per_page=30', headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'TunnelHTTPS-workspace-build'})
        with urllib.request.urlopen(req, timeout=20) as response:
            raw = response.read(2 * 1024 * 1024 + 1)
        if len(raw) > 2 * 1024 * 1024:
            return result
        releases = json.loads(raw)
        if not isinstance(releases, list):
            return result
        for release in releases:
            if not isinstance(release, dict) or release.get('draft') or not release.get('published_at'):
                continue
            url = release.get('html_url', '')
            if not isinstance(url, str) or not url.startswith(REPO + '/releases/tag/'):
                continue
            for asset in release.get('assets', []):
                name, download = asset.get('name', ''), asset.get('browser_download_url', '')
                size = asset.get('size', 0)
                if not isinstance(name, str) or not name.endswith('.apk') or any(x in name.lower() for x in ('debug', 'unsigned', 'validation', 'test')):
                    continue
                if not isinstance(download, str) or not download.startswith(REPO + '/releases/download/') or not isinstance(size, int) or not 0 < size <= 128 * 1024 * 1024:
                    continue
                return {'available': True, 'url': url, 'download': download, 'version': str(release.get('tag_name', ''))[:80], 'beta': bool(release.get('prerelease')), 'size': size}
    except (OSError, ValueError, TypeError):
        pass
    return result


def render(lang: str, start: str, assets: dict, release: dict, revision: str) -> str:
    ko = lang == 'ko'
    def t(en, kr): return kr if ko else en
    depth = (1 if ko else 0) + (0 if start == 'home' else 1)
    root = '../' * depth or './'
    prefix = 'ko/' if ko else ''
    labels = {'home': t('Overview', '소개'), 'controls': t('Controls', '기능'), 'engineering': t('Engine', '엔진'), 'privacy': t('Privacy', '개인정보'), 'guide': t('Get app', '시작하기')}
    def url(page): return root + prefix + ROUTES[page]
    def route(page, label, cls='button primary', extra=''):
        return f'<a href="{url(page)}" data-route="{page}" class="{cls}" {extra}>{label}{icon("arrow")}</a>'
    def source(file, label=None):
        return f'<a class="source-link" href="{REPO}/blob/main/app/src/main/java/com/tunnelvpn/app/{file}">{label or file}{icon("external")}</a>'
    def panel(name, content):
        return f'<section id="panel-{name}" class="pane pane-{name}" data-panel="{name}" aria-label="{labels[name]}"'+('' if name == start else ' hidden')+f'>{content}</section>'
    def smallfact(symbol, title, text):
        return f'<div class="smallfact">{icon(symbol)}<div><strong>{title}</strong><span>{text}</span></div></div>'
    tabs = ''.join(f'<a id="tab-{name}" class="main-tab" href="{url(name)}" data-route="{name}"'+(' aria-current="page"' if name == start else '')+f'>{icon(name)}<span>{label}</span></a>' for name, label in labels.items())
    mode_buttons = ''.join(f'<button type="button" data-preview="{name}" aria-pressed="{str(i == 0).lower()}">{label}</button>' for i, (name, label) in enumerate([('dns', 'DNS'), ('filter', t('Filtering', '필터링')), ('turbo', 'Turbo')]))
    overview = f'''
    <div class="overview-layout">
      <div class="overview-copy">
        <p class="overline">{t('A different kind of Android network app.', 'Android 네트워크를 다루는 다른 방식.')}</p>
        <h1 tabindex="-1">{t('Your network.<br>Your decisions.', '내 네트워크를,<br>내 방식대로.')}</h1>
        <p class="lead">{t('Less out of your hands.<br>More on your device.', '기기 밖에 맡기는 일은 줄이고,<br>내 손안에서 선택하는 일은 늘리고.')}</p>
        <p class="intro">{t('Encrypted DNS, domain filtering and adaptive connections. Built locally. Open all the way down.', '암호화된 DNS, 도메인 필터링, 적응형 연결.<br>기기 안에서 처리하고, 소스까지 열어 둡니다.')}</p>
        <div class="hero-actions">{route('guide', t('Get Tunnel HTTPS', 'Tunnel HTTPS 시작하기'))}{route('engineering', t('Explore the engine', '엔진 살펴보기'), 'button quiet')}</div>
        <div class="compatibility">{icon('apps')}<span>{t('Android 7+ · No root required', 'Android 7 이상 · 루팅 불필요')}</span></div>
      </div>
      <div class="connection-stage">
        <div class="stage-heading"><span>{t('A closer look at your connection', '연결의 안쪽을 살펴보세요')}</span><span class="small-pill">{t('Interactive guide', '인터랙티브 안내')}</span></div>
        <div class="connection-console" id="connection-console">
          <div class="console-bar"><span class="console-symbol">{icon('controls')}</span><div><strong>{t('Connection desk', '연결 살펴보기')}</strong><span>{t('Choose a path to explore', '살펴볼 경로를 선택하세요')}</span></div><span class="console-tag">{t('On-device', '기기 내부')}</span></div>
          <div class="preview-tabs" aria-label="{t('Connection walkthrough', '연결 과정 선택')}">{mode_buttons}</div>
          <div class="request-header"><span>{t('Example request', '예시 요청')}</span><code id="preview-request">example.org</code></div>
          <ol class="preview-path" id="preview-path">
            <li data-preview-step="0"><span class="path-symbol">{icon('apps')}</span><div><strong id="preview-title-0">{t('Your Android apps', 'Android 앱')}</strong><span id="preview-desc-0">{t('A DNS query enters the local path', 'DNS 질의가 로컬 경로로 들어옵니다')}</span></div><span class="path-label">{t('Device', '기기')}</span></li>
            <li data-preview-step="1"><span class="path-symbol">{icon('lock')}</span><div><strong id="preview-title-1">{t('DNS policy', 'DNS 정책')}</strong><span id="preview-desc-1">{t('Validate, apply policy, check the cache', '검증·정책 적용·캐시 확인')}</span></div><span class="path-label">{t('Local', '로컬')}</span></li>
            <li data-preview-step="2"><span class="path-symbol">{icon('globe')}</span><div><strong id="preview-title-2">{t('Your selected resolver', '선택한 리졸버')}</strong><span id="preview-desc-2">{t('An HTTPS request when DoH is used', 'DoH를 사용할 때 HTTPS로 질의')}</span></div><span class="path-label">{t('External', '외부')}</span></li>
          </ol>
          <div class="console-actions"><button id="preview-run" type="button">{icon('play')}<span>{t('Walk through', '순서대로 보기')}</span></button><span id="preview-status" role="status">{t('Ready to explore', '살펴볼 준비가 됐습니다')}</span></div>
        </div>
        <div class="stage-note">{icon('lock')}<p id="preview-note">{t('Your DNS provider still receives the query. This walkthrough sends no traffic.', 'DNS 제공자는 질의를 수신합니다. 이 안내 화면은 트래픽을 전송하지 않습니다.')}</p></div>
      </div>
    </div>
    <div class="overview-foot">
      {smallfact('lock',t('Encrypted DNS','암호화된 DNS'),t('Choose your resolver','리졸버를 직접 선택'))}
      {smallfact('filter',t('Selective filtering','선택적 필터링'),t('Control the local path','로컬 경로를 직접 제어'))}
      {smallfact('code',t('Open implementation','열린 구현'),t('Inspect the source','코드까지 직접 확인'))}
    </div>'''

    controls = f'''
    <div class="page-head"><div><p class="overline">{t('Small controls. Meaningful choices.', '작은 설정으로, 분명한 선택.')}</p><h1 tabindex="-1">{t('Make the path yours.', '내게 맞는 연결 경로.')}</h1></div><p>{t('Explore what changes with each control.<br>These are explanations, not settings for your phone.', '각 기능이 어떤 차이를 만드는지 살펴보세요.<br>이 화면에서 휴대폰 설정이 바뀌지는 않습니다.')}</p></div>
    <div class="controls-layout">
      <div class="control-menu" role="tablist" aria-label="{t('Network controls', '네트워크 기능')}">
        <button id="control-dns" role="tab" aria-selected="true" aria-controls="control-detail" data-control="dns">{icon('lock')}<span><strong>{t('Encrypted DNS', '암호화된 DNS')}</strong><small>{t('Names, over HTTPS', '이름을 HTTPS로 질의')}</small></span>{icon('arrow')}</button>
        <button id="control-filter" role="tab" aria-selected="false" aria-controls="control-detail" tabindex="-1" data-control="filter">{icon('filter')}<span><strong>{t('Domain filtering', '도메인 필터링')}</strong><small>{t('Fewer unwanted domains', '불필요한 도메인 줄이기')}</small></span>{icon('arrow')}</button>
        <button id="control-bypass" role="tab" aria-selected="false" aria-controls="control-detail" tabindex="-1" data-control="bypass">{icon('apps')}<span><strong>{t('App bypass', '앱별 제외')}</strong><small>{t('A path for selected apps', '선택한 앱의 경로')}</small></span>{icon('arrow')}</button>
        <button id="control-turbo" role="tab" aria-selected="false" aria-controls="control-detail" tabindex="-1" data-control="turbo">{icon('engineering')}<span><strong>{t('Adaptive strategy', '적응형 전략')}</strong><small>{t('Learning on your device', '내 기기에서 학습')}</small></span>{icon('arrow')}</button>
      </div>
      <div class="control-detail" id="control-detail" role="tabpanel" aria-labelledby="control-dns" tabindex="0">
        <div class="detail-heading"><span class="tag">{t('Behavior preview', '동작 미리보기')}</span><span id="control-scope">DNS over HTTPS</span></div>
        <h2 id="control-title">{t('A private transport for DNS.', 'DNS를 암호화해서 전달합니다.')}</h2>
        <p id="control-description">{t('Send queries through an HTTPS connection to your chosen resolver. The resolver still sees the query; encrypted transport is not anonymity.', '선택한 리졸버에게 HTTPS 연결로 질의합니다. 리졸버는 질의 내용을 수신하며, 전송 암호화가 익명성을 뜻하지는 않습니다.')}</p>
        <div class="behavior-switch-row"><div><strong id="control-switch-title">{t('Use encrypted DNS', '암호화된 DNS 사용')}</strong><span>{t('Change the illustration below', '아래 설명 화면에만 적용')}</span></div><button class="switch" id="behavior-switch" type="button" role="switch" aria-checked="true" aria-label="{t('Enable selected behavior in illustration', '선택한 기능을 설명 화면에 적용')}"><span></span></button></div>
        <div class="behavior-result" id="behavior-result" aria-live="polite"><div class="behavior-key">{t('DNS transport', 'DNS 전송')}</div><strong id="behavior-value">HTTPS</strong><p id="behavior-note">{t('Encrypted to the selected provider. Provider visibility remains.', '선택한 제공자까지 암호화합니다. 제공자는 질의를 볼 수 있습니다.')}</p></div>
        <div class="control-bottom"><a class="source-link" id="control-source" href="{REPO}/blob/main/app/src/main/java/com/tunnelvpn/app/DohResolver.kt">{t('Read the implementation', '실제 구현 보기')}{icon('external')}</a><span class="muted">{t('Nothing on your device is changed.', '기기의 설정은 바뀌지 않습니다.')}</span></div>
      </div>
    </div>'''

    engine = f'''
    <div class="page-head"><div><p class="overline">{t('Open the engine. Follow a request.', '엔진을 열고, 요청 하나를 따라가세요.')}</p><h1 tabindex="-1">{t('Depth, without the guesswork.', '구현의 안쪽까지, 분명하게.')}</h1></div><p>{t('Pick a protocol. Move through the stages.<br>Every explanation links back to the source.', '프로토콜을 고르고 단계를 따라가세요.<br>각 설명에서 실제 소스로 이어집니다.')}</p></div>
    <div class="engine-layout">
      <div class="engine-workbench">
        <div class="workbench-bar"><strong>{t('Request inspector', '요청 살펴보기')}</strong><span class="tag">{t('Illustration', '설명용')}</span></div>
        <div class="protocol-tabs" role="tablist" aria-label="{t('Protocol', '프로토콜')}">
          <button id="proto-dns" role="tab" aria-selected="true" aria-controls="protocol-detail" data-protocol="dns">DNS</button>
          <button id="proto-tcp" role="tab" aria-selected="false" aria-controls="protocol-detail" tabindex="-1" data-protocol="tcp">TCP / TLS</button>
          <button id="proto-quic" role="tab" aria-selected="false" aria-controls="protocol-detail" tabindex="-1" data-protocol="quic">UDP / QUIC</button>
        </div>
        <div class="engine-stages" aria-label="{t('Processing stage', '처리 단계')}">
          {''.join(f'<button type="button" data-step="{i}"'+(' aria-current="step"' if i == 0 else '')+f'><span class="step-num">{i+1}</span><span><strong>{label}</strong><small>{sub}</small></span>{icon("arrow")}</button>' for i, (label,sub) in enumerate([(t('Capture','받기'),'VpnService / TUN'), (t('Validate','검사'),'IPv4 / IPv6'), (t('Apply policy','정책 적용'), t('Protocol-specific','프로토콜별 처리')), (t('Forward','전달'),t('Protected sockets','VPN에서 제외한 소켓'))]))}
        </div>
        <div class="engine-toolbar"><button id="engine-play" type="button" class="button compact">{icon('play')}<span>{t('Play steps', '순서대로')}</span></button><label class="sr-only" for="engine-step">{t('Processing stage', '처리 단계')}</label><input id="engine-step" type="range" min="0" max="3" step="1" value="0"><span id="stage-counter">1 / 4</span></div>
      </div>
      <div class="protocol-detail" id="protocol-detail" role="tabpanel" aria-labelledby="proto-dns" tabindex="0">
        <div class="detail-heading"><span class="scope-label" id="engine-scope">{t('Platform interface', '플랫폼 인터페이스')}</span><span id="protocol-label">DNS</span></div>
        <h2 id="engine-title">{t('Start at the device.', '기기 안에서 시작합니다.')}</h2>
        <p id="engine-description">{t('Android supplies VpnService and TUN. Tunnel HTTPS reads the traffic selected by its routing configuration and begins local packet processing.', 'Android가 VpnService와 TUN을 제공합니다. Tunnel HTTPS는 라우팅 설정에 따라 선택된 트래픽을 읽고 로컬 패킷 처리를 시작합니다.')}</p>
        <dl class="ownership"><div><dt>{t('Implemented here', '프로젝트의 구현')}</dt><dd id="engine-owned">{t('Packet loop, routing decisions and dispatch.', '패킷 루프, 라우팅 판단과 분기.')}</dd></div><div><dt>{t('Provided by Android', 'Android의 역할')}</dt><dd id="engine-platform">{t('VpnService, TUN and the underlying network.', 'VpnService, TUN 및 기반 네트워크.')}</dd></div></dl>
        <div class="boundary-note">{icon('privacy')}<p id="engine-boundary">{t('Local processing is not a remote VPN tunnel.', '로컬 처리는 원격 VPN 터널이 아닙니다.')}</p></div>
        <a id="engine-source" class="source-link" href="{REPO}/blob/main/app/src/main/java/com/tunnelvpn/app/LocalProtectionEngine.kt">LocalProtectionEngine.kt{icon('external')}</a>
      </div>
    </div>'''

    privacy = f'''
    <div class="page-head"><div><p class="overline">{t('Know what stays. Know what leaves.', '남는 정보와, 전달되는 정보를 구분합니다.')}</p><h1 tabindex="-1">{t('Privacy needs clear boundaries.', '개인정보 보호의 분명한 경계.')}</h1></div><p>{t('Local processing does not mean invisible traffic.<br>See who receives what.', '로컬에서 처리한다고 트래픽이 보이지 않는 것은 아닙니다.<br>누가 무엇을 받는지 확인하세요.')}</p></div>
    <div class="privacy-layout">
      <div class="privacy-ledger">
        <div class="ledger-head"><strong>{t('Where information goes', '정보가 이동하는 곳')}</strong><span>{t('Select a destination', '각 항목을 선택하세요')}</span></div>
        <div class="privacy-options" role="tablist" aria-label="{t('Information recipient', '정보를 받는 주체')}">
          <button id="privacy-device" role="tab" aria-selected="true" aria-controls="privacy-detail" data-privacy="device">{icon('apps')}<span><strong>{t('Your device', '내 기기')}</strong><small>{t('Local controls and learning', '로컬 설정과 학습')}</small></span><span class="ledger-badge local">{t('Local', '로컬')}</span></button>
          <button id="privacy-resolver" role="tab" aria-selected="false" aria-controls="privacy-detail" tabindex="-1" data-privacy="resolver">{icon('lock')}<span><strong>{t('DNS provider', 'DNS 제공자')}</strong><small>{t('The resolver you select', '내가 선택한 리졸버')}</small></span><span class="ledger-badge">{t('External', '외부')}</span></button>
          <button id="privacy-destination" role="tab" aria-selected="false" aria-controls="privacy-detail" tabindex="-1" data-privacy="destination">{icon('globe')}<span><strong>{t('Destination service', '접속 대상 서비스')}</strong><small>{t('The site or service you use', '내가 사용하는 사이트와 서비스')}</small></span><span class="ledger-badge">{t('External', '외부')}</span></button>
        </div>
        <div class="privacy-detail" id="privacy-detail" role="tabpanel" aria-labelledby="privacy-device" tabindex="0"><h2 id="privacy-title">{t('Decisions stay close.', '판단은 가까운 곳에서.')}</h2><p id="privacy-description">{t('Network configuration, local strategy learning and supported packet decisions are handled on your device. There is no developer-operated remote full-traffic VPN gateway.', '네트워크 설정, 로컬 전략 학습과 지원 범위의 패킷 판단을 기기에서 처리합니다. 개발자가 운영하는 전체 트래픽용 원격 VPN 게이트웨이는 없습니다.')}</p></div>
      </div>
      <div class="privacy-aside"><div class="large-quote">{t('A local engine.<br>Not an invisibility cloak.', '로컬 엔진이지,<br>투명 망토는 아닙니다.')}</div><div class="boundary-list"><p>{icon('check')}{t('No HTTPS payload decryption', 'HTTPS 본문 복호화 없음')}</p><p>{icon('check')}{t('No remote exit country to choose', '원격 출구 국가 선택 기능 아님')}</p><p>{icon('check')}{t('No promise of anonymity', '익명성 보장 아님')}</p></div><a class="button quiet" href="{REPO}/blob/main/SECURITY.md">{t('Security & reporting', '보안과 취약점 제보')}{icon('external')}</a><p class="site-privacy">{t('This website has no analytics or third-party runtime requests. GitHub hosts the site. Theme preferences stay in your browser; files used by the checker are not uploaded.', '이 사이트는 분석 스크립트나 외부 런타임 요청을 사용하지 않습니다. GitHub에서 호스팅합니다. 테마 설정은 브라우저에 저장하고, 검사 파일은 업로드하지 않습니다.')}</p></div>
    </div>'''

    release_title = escape(release.get('version', t('From the official repository.', '공식 저장소에서 시작하세요.')))
    release_state = t('Published beta' if release.get('beta') else 'Published release', '공개 베타' if release.get('beta') else '공개 릴리스') if release.get('available') else t('Source available', '소스 공개')
    release_note = t('Read the release notes before installing. Device and network compatibility depend on your environment.', '설치 전에 릴리스 안내를 읽어 주세요. 기기와 네트워크에 따라 호환성이 달라질 수 있습니다.') if release.get('available') else t('No public APK was confirmed for this site build. Check Releases for updates, or build from the source below.', '이 사이트 빌드에서는 공개 APK를 확인하지 못했습니다. 릴리스를 확인하거나 아래 소스로 빌드할 수 있습니다.')
    release_href = escape(release.get('download', release.get('url', REPO+'/releases')), quote=True)
    release_cta = t('Download APK', 'APK 받기') if release.get('available') else t('Check GitHub Releases', 'GitHub 릴리스 확인')
    guide = f'''
    <div class="page-head"><div><p class="overline">{t('The source is yours to inspect.', '직접 확인하고 시작하는 소프트웨어.')}</p><h1 tabindex="-1">{t('Ready when you are.', '준비가 됐다면, 시작하세요.')}</h1></div><p>{t('Get the app from the repository.<br>Keep the trust chain visible.', '저장소에서 앱을 받아 시작하세요.<br>배포 출처와 검증 방법도 함께 확인하세요.')}</p></div>
    <div class="install-layout"><div class="install-main">
      <div class="release-card"><div class="release-top"><div class="app-letter">T</div><span class="tag">{release_state}</span></div><h2>Tunnel HTTPS</h2><p class="release-version">{release_title}</p><p>{release_note}</p><a class="button primary" id="release-download" href="{release_href}">{release_cta}{icon('guide')}</a><div class="release-specs"><span>Android 7+</span><span>Apache-2.0</span><span>{t('No root', '루팅 불필요')}</span></div></div>
      <div class="install-disclosures"><details><summary>{t('Installation notes', '설치할 때 알아둘 점')}<span aria-hidden="true">+</span></summary><div><p>{t('Allow APK installation for the downloading app only when Android prompts. Grant VPN consent inside Tunnel HTTPS. Android normally allows one active VpnService per profile.', 'Android가 요청할 때 다운로드한 앱의 APK 설치를 허용하세요. Tunnel HTTPS 안에서 VPN 사용을 승인합니다. Android는 보통 프로필마다 하나의 VpnService만 활성화할 수 있습니다.')}</p><p>{t('An APK signed with a different key may not update an existing installation. Do not uninstall blindly: doing so removes local settings.', '서명 키가 다른 APK는 기존 설치를 업데이트할 수 없을 수 있습니다. 삭제하면 로컬 설정이 사라지므로 무작정 삭제하지 마세요.')}</p></div></details><details id="developer"><summary>{t('Build from source', '소스에서 빌드하기')}<span aria-hidden="true">+</span></summary><div><p>{t('With Git, the Android SDK and the project’s required JDK configured:', 'Git, Android SDK와 프로젝트에 필요한 JDK를 준비한 뒤 실행하세요.')}</p><div class="code-block"><pre><code id="build-command">git clone https://github.com/Sp2ctr2/Tunnel-HTTPS.git
cd Tunnel-HTTPS
./gradlew :app:assembleDebug</code></pre><button type="button" class="copy-button" data-copy="build-command" aria-label="{t('Copy build commands', '빌드 명령 복사')}">{icon('copy')}</button></div><p>{t('Debug output is for development, not a signed public release.', '디버그 결과물은 개발용이며 서명된 공개 릴리스가 아닙니다.')}</p></div></details></div>
    </div><div class="verify-card"><div class="detail-heading"><span class="tag">{t('Local tool', '로컬 도구')}</span>{icon('lock')}</div><h2>{t('Check your download.', '다운로드한 파일 확인.')}</h2><p>{t('Calculate SHA-256 in your browser. Compare against the checksum published with the exact release.', '브라우저에서 SHA-256을 계산합니다. 해당 릴리스에 게시된 정확한 체크섬과 비교하세요.')}</p><form id="verify-form"><label for="expected-hash">{t('Expected SHA-256', '비교할 SHA-256')}<span>{t('Optional', '선택 사항')}</span></label><input id="expected-hash" name="expected-hash" placeholder="{t('Paste the release’s 64-character checksum…', '릴리스의 64자리 체크섬을 붙여 넣으세요…')}" type="text" maxlength="100" spellcheck="false" autocomplete="off" aria-describedby="hash-help"><p id="hash-help" class="field-note">{t('Use the checksum for the same version and file.', '동일한 버전과 파일의 체크섬을 사용하세요.')}</p><label for="apk-file" class="file-label">{icon('file')}<strong>{t('Choose a local APK', '로컬 APK 선택')}</strong><span>{t('Stays in your browser · up to 128 MiB', '업로드하지 않음 · 최대 128 MiB')}</span><input type="file" id="apk-file" name="apk-file" accept=".apk,application/vnd.android.package-archive"></label><button class="button dark" id="verify-button" type="submit">{t('Calculate & compare', '계산하고 비교하기')}{icon('check')}</button></form><output id="hash-result" aria-live="polite">{t('No file selected.', '선택한 파일이 없습니다.')}</output><p class="verification-limit">{t('A matching checksum confirms bytes, not Android signing identity or app safety.', '체크섬 일치는 파일 바이트를 확인할 뿐, Android 서명이나 앱의 안전성을 인증하지 않습니다.')}</p><noscript><p>{t('JavaScript is required for this local tool. Use your operating system’s SHA-256 tool instead.', '로컬 검사에는 JavaScript가 필요합니다. 운영체제의 SHA-256 도구를 사용하세요.')}</p></noscript></div></div>'''
    panels = ''.join(panel(n, value) for n, value in [('home', overview), ('controls', controls), ('engineering', engine), ('privacy', privacy), ('guide', guide)])
    search_items = ''.join(f'<a href="{url(n)}" data-route="{n}" data-search-item>{icon(n)}<span>{label}</span>{icon("arrow")}</a>' for n, label in labels.items())
    active_index = list(ROUTES).index(start)
    title = labels[start] + ' — Tunnel HTTPS'
    canonical = BASE + prefix + ROUTES[start]
    return f'''<!doctype html>
<html lang="{lang}" data-theme="light" data-root="{root}" data-page="{start}" data-revision="{escape(revision)}">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"><meta name="color-scheme" content="light dark"><meta name="theme-color" content="#eaedf0"><meta name="description" content="{t('Explore an open-source Android networking engine. Encrypted DNS, selective filtering and on-device strategy, with clear boundaries.', '암호화된 DNS, 선택적 필터링, 온디바이스 전략을 갖춘 오픈소스 Android 네트워크 엔진을 살펴보세요.')}"><meta name="referrer" content="strict-origin-when-cross-origin"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><title>{title}</title><link rel="canonical" href="{canonical}"><link rel="alternate" hreflang="en" href="{BASE+ROUTES[start]}"><link rel="alternate" hreflang="ko" href="{BASE+'ko/'+ROUTES[start]}"><link rel="icon" href="{root}favicon.svg" type="image/svg+xml"><link rel="stylesheet" href="{root}assets/{assets['site.css']}"><script src="{root}assets/{assets['boot.js']}"></script><script src="{root}assets/{assets['site.js']}" defer></script></head>
<body><a class="skip-link" href="#main">{t('Skip to content','본문 바로가기')}</a>
<div class="workspace">
<header class="masthead"><a href="{url('home')}" data-route="home" class="brand"><span class="brand-mark" aria-hidden="true">T<span></span></span><span>Tunnel HTTPS<small>{t('On-device networking', '기기 안의 네트워크 엔진')}</small></span></a><div class="header-tools"><button id="search-open" class="search-trigger" type="button" aria-label="{t('Search the site', '사이트 검색')}">{icon('search')}<span>{t('Find a page', '화면 찾기')}</span><kbd>⌘ K</kbd></button><a id="language-link" href="{root+('' if ko else 'ko/')+ROUTES[start]}" class="language-link" lang="{'en' if ko else 'ko'}">{t('한국어', 'English')}</a><button class="icon-button" id="theme-toggle" type="button" aria-label="{t('Switch color theme', '화면 테마 전환')}">{icon('moon','moon')}{icon('sun','sun')}</button><a class="github-link" href="{REPO}">GitHub{icon('external')}</a></div></header>
<div class="site-frame"><nav class="tabs-bar" aria-label="{t('Main pages', '주요 화면')}"><div class="main-tabs" id="main-tabs">{tabs}</div><span class="tabs-aside">{t('Made for Android','Android 전용')}</span></nav><main id="main" class="deck" tabindex="-1">{panels}</main><footer class="frame-footer"><div class="page-location"><span id="page-name">{labels[start]}</span><span id="page-index">{active_index+1} / 5</span></div><span class="footer-note">{t('No remote VPN gateway. The code is open.', '원격 VPN 게이트웨이 없이. 코드는 열어 두고.')}</span><div class="page-navigation"><a id="previous-page" href="{url(list(ROUTES)[(active_index-1)%5])}" data-route="{list(ROUTES)[(active_index-1)%5]}" aria-label="{t('Previous tab','이전 탭')}">{icon('back')}</a><a id="next-page" href="{url(list(ROUTES)[(active_index+1)%5])}" data-route="{list(ROUTES)[(active_index+1)%5]}"><span>{t('Next', '다음')}</span>{icon('arrow')}</a></div></footer></div>
<div class="workspace-foot"><span>Apache-2.0</span><button id="motion-toggle" type="button" aria-pressed="false">{t('Motion on', '전환 효과 켜짐')}</button><span>tunnel / https</span></div></div>
<dialog id="search-dialog" aria-labelledby="search-title"><div class="dialog-head"><h2 id="search-title">{t('Where to?', '어디로 이동할까요?')}</h2><button class="icon-button" id="search-close" aria-label="{t('Close search','검색 닫기')}">{icon('close')}</button></div><label for="site-search" class="sr-only">{t('Search pages','화면 검색')}</label><input id="site-search" type="search" placeholder="{t('Search pages…','화면 검색…')}" autocomplete="off" spellcheck="false"><div class="search-results">{search_items}</div><p id="search-empty" hidden>{t('No matching page. Try “Engine” or “Privacy”.','일치하는 화면이 없습니다. “엔진” 또는 “개인정보”를 검색하세요.')}</p><div class="dialog-foot">{t('↑ ↓ to move · Enter to open · Esc to close','↑ ↓ 이동 · Enter 열기 · Esc 닫기')}</div></dialog>
<div id="toast" role="status" hidden></div></body></html>'''


def build(output: Path, resolve: bool = False):
    output.mkdir(parents=True, exist_ok=True)
    assets = {}
    for name in ('site.css', 'site.js', 'boot.js'):
        data = (HERE / name).read_bytes()
        if name == 'site.css':
            data += b'\n' + (HERE / 'layout.css').read_bytes()
        digest = hashlib.sha256(data).hexdigest()[:12]
        stem, suffix = name.rsplit('.', 1)
        filename = f'{stem}.{digest}.{suffix}'
        target = output / 'assets' / filename
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(data)
        assets[name] = filename
    revision = os.environ.get('GITHUB_SHA', 'local-review')
    release = release_metadata() if resolve else {'available': False, 'url': REPO + '/releases'}
    for lang in ('en', 'ko'):
        for page, route in ROUTES.items():
            target = output / ('ko' if lang == 'ko' else '') / route / 'index.html'
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(render(lang, page, assets, release, revision), encoding='utf-8')
    (output / '.nojekyll').write_text('')
    (output / 'favicon.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#202023"/><path d="M15 17h34v9H37v25H27V26H15Z" fill="#f4c46b"/></svg>')
    (output / '404.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Page not found — Tunnel HTTPS</title><h1>Page not found</h1><p>This address does not exist.</p><a href="/Tunnel-HTTPS/">Return to Tunnel HTTPS</a></html>')
    (output / 'robots.txt').write_text('User-agent: *\nAllow: /\nSitemap: '+BASE+'sitemap.xml\n')
    locs = [BASE + ('ko/' if lang=='ko' else '') + route for lang in ('en','ko') for route in ROUTES.values()]
    (output / 'sitemap.xml').write_text('<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'+''.join('<url><loc>'+value+'</loc></url>' for value in locs)+'</urlset>')
    files = {p.relative_to(output).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'manifest.json'}
    (output / 'manifest.json').write_text(json.dumps({'edition': 'workspace-5', 'revision': revision, 'routes': 10, 'release_available': bool(release.get('available')), 'files': files}, indent=2)+'\n')
    print(json.dumps({'edition':'workspace-5', 'routes':10, 'release_available':bool(release.get('available')), 'output':str(output)}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path('_site'))
    parser.add_argument('--resolve-release', action='store_true')
    args = parser.parse_args()
    build(args.output, args.resolve_release)
