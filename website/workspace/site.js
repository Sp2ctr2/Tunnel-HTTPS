'use strict';
(() => {
  const html = document.documentElement;
  const ko = html.lang === 'ko';
  const t = (en, kr) => ko ? kr : en;
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const root = new URL(html.dataset.root || './', location.href);
  const names = ['home', 'controls', 'engineering', 'privacy', 'guide'];
  const paths = { home: '', controls: 'controls/', engineering: 'engineering/', privacy: 'privacy/', guide: 'guide/' };
  const labels = { home: t('Overview', '소개'), controls: t('Controls', '기능'), engineering: t('Engine', '엔진'), privacy: t('Privacy', '개인정보'), guide: t('Get app', '시작하기') };
  const prefix = ko ? 'ko/' : '';
  const codeRoot = 'https://github.com/Sp2ctr2/Tunnel-HTTPS/blob/main/app/src/main/java/com/tunnelvpn/app/';
  const media = matchMedia('(prefers-reduced-motion: reduce)');
  const storage = {
    get(key) { try { return localStorage.getItem('tunnel.workspace.' + key); } catch (_) { return null; } },
    set(key, value) { try { localStorage.setItem('tunnel.workspace.' + key, value); } catch (_) {} }
  };
  let active = html.dataset.page || 'home';
  let sequence = 0;
  let transition = null;
  let fallbackAnimations = [];
  let previewTimer = null;
  let engineTimer = null;
  let toastTimer = null;
  let fileSequence = 0;
  const scrollPositions = new Map();
  const panelURL = name => new URL(prefix + paths[name], root);
  const motionEnabled = () => !media.matches && html.dataset.motion !== 'off';

  function notify(text) {
    const toast = $('#toast');
    clearTimeout(toastTimer);
    toast.textContent = text;
    toast.hidden = false;
    toastTimer = setTimeout(() => { toast.hidden = true; }, 3200);
  }
  function updateTheme(theme) {
    html.dataset.theme = theme === 'dark' ? 'dark' : 'light';
    storage.set('theme', html.dataset.theme);
    $('meta[name="theme-color"]').content = html.dataset.theme === 'dark' ? '#181a1e' : '#eaedf0';
    $('#theme-toggle').setAttribute('aria-label', html.dataset.theme === 'dark' ? t('Switch to light theme', '밝은 테마로 전환') : t('Switch to dark theme', '어두운 테마로 전환'));
  }
  updateTheme(html.dataset.theme);
  $('#theme-toggle').addEventListener('click', () => updateTheme(html.dataset.theme === 'light' ? 'dark' : 'light'));

  function updateMotion() {
    const off = media.matches || storage.get('motion') === 'off';
    html.dataset.motion = off ? 'off' : 'on';
    $('#motion-toggle').textContent = off ? t('Motion off', '전환 효과 꺼짐') : t('Motion on', '전환 효과 켜짐');
    $('#motion-toggle').setAttribute('aria-pressed', String(off));
    if (off) { transition?.skipTransition(); stopPlayback(); }
  }
  $('#motion-toggle').addEventListener('click', () => {
    if (media.matches) { notify(t('Reduced motion is enabled in your system settings.', '운영체제의 동작 줄이기 설정이 적용되어 있습니다.')); return; }
    storage.set('motion', html.dataset.motion === 'off' ? 'on' : 'off');
    updateMotion();
  });
  media.addEventListener('change', updateMotion);

  function updatePage(name, focus) {
    active = name;
    html.dataset.page = name;
    $$('[data-panel]').forEach(pane => {
      pane.hidden = pane.dataset.panel !== name;
      pane.inert = pane.hidden;
    });
    $$('.main-tab').forEach(tab => {
      const selected = tab.dataset.route === name;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
      if (selected) tab.setAttribute('aria-current', 'page');
      else tab.removeAttribute('aria-current');
    });
    $('#page-name').textContent = labels[name];
    const index = names.indexOf(name);
    $('#page-index').textContent = `${index + 1} / 5`;
    [['#previous-page', (index + 4) % 5], ['#next-page', (index + 1) % 5]].forEach(([selector, i]) => {
      const anchor = $(selector);
      anchor.dataset.route = names[i];
      anchor.href = panelURL(names[i]).href;
    });
    $('#language-link').href = new URL((ko ? '' : 'ko/') + paths[name] + location.search + location.hash, root).href;
    document.title = labels[name] + ' — Tunnel HTTPS';
    const pane = $('#panel-' + name);
    pane.scrollTop = scrollPositions.get(name) || 0;
    if (focus) $('h1', pane)?.focus({ preventScroll: true });
  }

  async function navigate(name, target, options = {}) {
    if (!names.includes(name)) return;
    const id = ++sequence;
    const old = $('#panel-' + active);
    scrollPositions.set(active, old.scrollTop);
    stopPlayback();
    transition?.skipTransition();
    fallbackAnimations.forEach(animation => animation.cancel());
    fallbackAnimations = [];
    if (options.push !== false) {
      const nextURL = target || panelURL(name);
      if (nextURL.href !== location.href) history.pushState({ page: name }, '', nextURL);
    }
    const update = () => {
      if (id !== sequence) return;
      updatePage(name, Boolean(options.focus));
      restoreSubstate();
    };
    if (!motionEnabled() || options.instant || document.hidden) { update(); return; }
    if (typeof document.startViewTransition === 'function') {
      const current = document.startViewTransition(update);
      transition = current;
      current.ready.catch(() => {});
      try { await current.updateCallbackDone; } catch (_) { update(); }
      current.finished.catch(() => {}).finally(() => { if (transition === current) transition = null; });
    } else {
      // Optical depth change, never a horizontal swipe. New input interrupts it.
      const exit = old.animate([{ opacity: 1, transform: 'scale(1)' }, { opacity: 0, transform: 'scale(.988)' }], { duration: 130, easing: 'ease-out' });
      fallbackAnimations.push(exit);
      try { await exit.finished; } catch (_) {}
      if (id !== sequence) return;
      update();
      const enter = $('#panel-' + name).animate([{ opacity: 0, transform: 'translateY(8px) scale(1.014)' }, { opacity: 1, transform: 'translateY(0) scale(1)' }], { duration: 280, easing: 'cubic-bezier(.22,1,.36,1)' });
      fallbackAnimations = [enter];
      enter.finished.catch(() => {});
    }
  }
  function routeFromURL() {
    const basePath = new URL(prefix, root).pathname;
    if (!location.pathname.startsWith(basePath)) return 'home';
    const relative = location.pathname.slice(basePath.length).replace(/index\.html$/, '').replace(/\/+$/, '');
    return names.find(name => paths[name].replace(/\/$/, '') === relative) || 'home';
  }
  function setQuery(values) {
    const url = new URL(location.href);
    Object.entries(values).forEach(([key, value]) => url.searchParams.set(key, String(value)));
    history.replaceState({ page: active }, '', url);
    $('#language-link').href = new URL((ko ? '' : 'ko/') + paths[active] + url.search, root).href;
  }
  $('#main-tabs').setAttribute('role', 'tablist');
  $('#main-tabs').setAttribute('aria-label', t('Website sections', '사이트 화면'));
  $$('.main-tab').forEach(tab => {
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-controls', 'panel-' + tab.dataset.route);
  });
  $$('[data-panel]').forEach(pane => {
    pane.setAttribute('role', 'tabpanel');
    pane.setAttribute('aria-labelledby', 'tab-' + pane.dataset.panel);
    pane.tabIndex = 0;
  });
  document.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const anchor = event.target.closest('a[data-route]');
    if (!anchor || anchor.target === '_blank' || anchor.hasAttribute('download')) return;
    const target = new URL(anchor.href, location.href);
    if (target.origin !== root.origin || !names.includes(anchor.dataset.route)) return;
    event.preventDefault();
    closeSearch();
    navigate(anchor.dataset.route, target, { focus: !anchor.classList.contains('main-tab') });
  });
  $('#main-tabs').addEventListener('keydown', event => {
    const tabs = $$('.main-tab');
    const index = tabs.indexOf(event.target);
    if (index < 0) return;
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = tabs.length - 1;
    if (next === undefined) return;
    event.preventDefault();
    tabs[next].focus();
    navigate(tabs[next].dataset.route, new URL(tabs[next].href), { instant: true });
  });
  window.addEventListener('popstate', () => navigate(routeFromURL(), new URL(location.href), { push: false, instant: true }));
  window.addEventListener('hashchange', restoreSubstate);

  function animateReadout(element) {
    if (!motionEnabled()) return;
    element.getAnimations().forEach(animation => animation.cancel());
    element.animate([{ opacity: .3, transform: 'translateY(4px)' }, { opacity: 1, transform: 'translateY(0)' }], { duration: 230, easing: 'cubic-bezier(.22,1,.36,1)' });
  }
  function keyboardTabs(selector, callback) {
    const buttons = $$(selector);
    buttons.forEach((button, index) => {
      button.addEventListener('keydown', event => {
        let next;
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % buttons.length;
        if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (index + buttons.length - 1) % buttons.length;
        if (event.key === 'Home') next = 0;
        if (event.key === 'End') next = buttons.length - 1;
        if (next === undefined) return;
        event.preventDefault(); buttons[next].focus(); callback(buttons[next]);
      });
    });
  }
  function selectSubtab(selector, button, detail) {
    $$(selector).forEach(item => {
      item.setAttribute('aria-selected', String(item === button));
      item.tabIndex = item === button ? 0 : -1;
    });
    $(detail).setAttribute('aria-labelledby', button.id);
  }

  // These fixtures explain committed behavior. They never send requests.
  const previews = {
    dns: { request: 'example.org', rows: [
      [t('Your Android apps','Android 앱'),t('A DNS query enters the local path','DNS 질의가 로컬 경로로 들어옵니다')],
      [t('DNS policy','DNS 정책'),t('Validate, apply policy, check the cache','검증·정책 적용·캐시 확인')],
      [t('Your selected resolver','선택한 리졸버'),t('An HTTPS request when DoH is used','DoH를 사용할 때 HTTPS로 질의')]],
      note: t('Your DNS provider still receives the query. This walkthrough sends no traffic.','DNS 제공자는 질의를 수신합니다. 이 안내 화면은 트래픽을 전송하지 않습니다.') },
    filter: { request: 'blocked.example', rows: [
      [t('A domain request','도메인 요청'),t('A sample domain enters the local path','예시 도메인이 로컬 경로로 들어옵니다')],
      [t('Domain policy','도메인 정책'),t('Check the configured blocking rules','설정한 차단 규칙을 검사합니다')],
      [t('Local policy result','로컬 정책 결과'),t('A listed domain is filtered locally','차단 목록과 일치하면 로컬에서 필터링')]],
      note: t('Example domain only. Domain filtering does not remove every ad or inspect HTTPS content.','예시 도메인입니다. 도메인 필터링이 모든 광고를 제거하거나 HTTPS 본문을 검사하지는 않습니다.') },
    turbo: { request: 'TLS ClientHello', rows: [
      [t('Connection context','연결 맥락'),t('Use local context and observed outcomes','로컬 맥락과 관측 결과를 사용합니다')],
      [t('Bounded strategy','제한된 전략'),t('Choose from supported connection strategies','지원하는 연결 전략 중에서 선택')],
      [t('Protected upstream socket','VPN에서 제외한 업스트림 소켓'),t('The destination receives the connection','접속 대상에 연결합니다')]],
      note: t('Turbo learns locally. Aegis stays shadow-only. This is not a performance measurement.','Turbo는 로컬에서 학습하고 Aegis는 섀도 모드로 분리됩니다. 성능 측정 화면이 아닙니다.') }
  };
  let previewMode = 'dns';
  function setPreview(mode, sync = true) {
    if (!Object.hasOwn(previews, mode)) return;
    stopPreview();
    previewMode = mode;
    const value = previews[mode];
    $$('[data-preview]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.preview === mode)));
    $('#preview-request').textContent = value.request;
    value.rows.forEach((row, i) => { $('#preview-title-' + i).textContent = row[0]; $('#preview-desc-' + i).textContent = row[1]; });
    $('#preview-note').textContent = value.note;
    $$('.path-label').forEach((label, i) => { label.textContent = [t('Device','기기'), t('Local','로컬'), mode === 'filter' ? t('Local','로컬') : t('External','외부')][i]; });
    $('#preview-status').textContent = t('Ready to explore','살펴볼 준비가 됐습니다');
    if (sync && active === 'home') setQuery({ preview: mode });
    animateReadout($('#preview-path'));
  }
  $$('[data-preview]').forEach(button => button.addEventListener('click', () => setPreview(button.dataset.preview)));
  function stopPreview() {
    clearInterval(previewTimer); previewTimer = null;
    $$('#preview-path li').forEach(row => row.classList.remove('is-active'));
    $('#preview-run').setAttribute('aria-pressed', 'false');
  }
  $('#preview-run').addEventListener('click', () => {
    if (previewTimer) { stopPreview(); $('#preview-status').textContent = t('Paused','일시 정지'); return; }
    const rows = $$('#preview-path li');
    let index = 0;
    const update = () => {
      rows.forEach((row, i) => row.classList.toggle('is-active', i === index));
      $('#preview-status').textContent = `${index + 1} / 3 — ${previews[previewMode].rows[index][0]}`;
    };
    if (!motionEnabled()) { index = 2; update(); return; }
    $('#preview-run').setAttribute('aria-pressed', 'true');
    update();
    previewTimer = setInterval(() => {
      index++;
      if (index === rows.length) {
        clearInterval(previewTimer); previewTimer = null;
        $('#preview-run').setAttribute('aria-pressed', 'false');
        $('#preview-status').textContent = t('Walkthrough complete','안내 완료');
        return;
      }
      update();
    }, 650);
  });

  const controls = {
    dns: {
      scope: 'DNS over HTTPS', file: 'DohResolver.kt',
      title: t('A private transport for DNS.','DNS를 암호화해서 전달합니다.'),
      description: t('Send queries through an HTTPS connection to your chosen resolver. The resolver still sees the query; encrypted transport is not anonymity.','선택한 리졸버에게 HTTPS 연결로 질의합니다. 리졸버는 질의 내용을 수신하며, 전송 암호화가 익명성을 뜻하지는 않습니다.'),
      toggle: t('Use encrypted DNS','암호화된 DNS 사용'), key: t('DNS transport','DNS 전송'),
      yes: ['HTTPS',t('Encrypted to the selected provider. Provider visibility remains.','선택한 제공자까지 암호화합니다. 제공자는 질의를 볼 수 있습니다.')],
      no: [t('System resolver path','시스템 리졸버 경로'),t('Uses the configured system path; encryption depends on Android and network configuration.','시스템 경로를 사용합니다. 암호화 여부는 Android와 네트워크 설정에 따라 달라집니다.')]
    },
    filter: {
      scope: t('Domain policy','도메인 정책'), file: 'DomainBlocker.kt',
      title: t('Filter the domain, not the page.','페이지 대신 도메인을 필터링합니다.'),
      description: t('Local rules can block listed domains. This acts on domain requests, not the encrypted text, images or layout inside a web page.','로컬 규칙으로 목록에 포함된 도메인을 차단합니다. 암호화된 페이지의 글·이미지·레이아웃이 아니라 도메인 요청에 적용됩니다.'),
      toggle: t('Apply domain rules','도메인 규칙 적용'), key: 'blocked.example',
      yes: [t('Filtered locally','로컬에서 필터링'),t('In this example, the requested domain matches a configured blocking rule.','이 예시에서는 요청한 도메인이 설정된 차단 규칙과 일치합니다.')],
      no: [t('Not filtered by this rule','이 규칙으로 필터링하지 않음'),t('This illustration disables the domain rule. Other network policies may still apply.','이 설명 화면에서는 도메인 규칙을 해제합니다. 다른 네트워크 정책은 적용될 수 있습니다.')]
    },
    bypass: {
      scope: t('Per-app routing','앱별 라우팅'), file: 'AppBypassManager.kt',
      title: t('Choose which apps take this path.','이 경로를 사용할 앱을 선택합니다.'),
      description: t('Excluded apps use their normal network route instead of the local VPN path. Bypass does not grant anonymity or make traffic more private.','제외한 앱은 로컬 VPN 경로 대신 일반 네트워크 경로를 사용합니다. 제외한다고 익명성이나 더 강한 개인정보 보호가 제공되지는 않습니다.'),
      toggle: t('Exclude the example app','예시 앱을 경로에서 제외'), key: t('Example app route','예시 앱의 경로'),
      yes: [t('Normal Android route','일반 Android 경로'),t('The example app bypasses Tunnel HTTPS. Its traffic is outside these local controls.','예시 앱은 Tunnel HTTPS를 거치지 않습니다. 해당 트래픽에는 이 로컬 제어가 적용되지 않습니다.')],
      no: [t('Local VPN path','로컬 VPN 경로'),t('The example app is included in the local routing configuration.','예시 앱이 로컬 라우팅 설정에 포함됩니다.')]
    },
    turbo: {
      scope: t('On-device strategy','온디바이스 전략'), file: 'TurboAiEngine.kt',
      title: t('Learn from the connection.','연결 결과에서 학습합니다.'),
      description: t('Turbo uses local context and observed outcomes to choose among supported strategies. The experimental Aegis model has no active routing authority.','Turbo는 로컬 맥락과 관측 결과로 지원하는 전략을 선택합니다. 실험 모델 Aegis에는 실제 라우팅 제어 권한이 없습니다.'),
      toggle: t('Illustrate adaptive selection','적응형 선택 살펴보기'), key: t('Strategy selection','전략 선택'),
      yes: [t('Context-guided','맥락 기반'),t('Local learning informs bounded strategy selection. Better performance is not guaranteed.','로컬 학습을 제한된 전략 선택에 활용합니다. 더 좋은 성능을 보장하지는 않습니다.')],
      no: [t('Configured strategy','설정한 전략'),t('The explanation uses a fixed configured strategy. Aegis remains shadow-only.','설명 화면에서 고정 전략을 사용합니다. Aegis는 여전히 섀도 모드입니다.')]
    }
  };
  let control = 'dns';
  const enabledControls = new Map();
  function updateBehavior() {
    const data = controls[control];
    const enabled = enabledControls.get(control) !== false;
    const row = enabled ? data.yes : data.no;
    $('#behavior-switch').setAttribute('aria-checked', String(enabled));
    $('.behavior-key').textContent = data.key;
    $('#behavior-value').textContent = row[0];
    $('#behavior-note').textContent = row[1];
    animateReadout($('#behavior-result'));
  }
  function setControl(value, sync = true) {
    if (!Object.hasOwn(controls, value)) return;
    control = value;
    const data = controls[value];
    selectSubtab('[data-control]', $('#control-' + value), '#control-detail');
    $('#control-title').textContent = data.title;
    $('#control-description').textContent = data.description;
    $('#control-scope').textContent = data.scope;
    $('#control-switch-title').textContent = data.toggle;
    $('#control-source').href = codeRoot + data.file;
    updateBehavior();
    if (sync && active === 'controls') setQuery({ control: value });
  }
  $$('[data-control]').forEach(button => button.addEventListener('click', () => setControl(button.dataset.control)));
  keyboardTabs('[data-control]', button => setControl(button.dataset.control));
  $('#behavior-switch').addEventListener('click', () => { enabledControls.set(control, !(enabledControls.get(control) !== false)); updateBehavior(); });

  const commonStages = [
    {
      scope: t('Platform interface','플랫폼 인터페이스'), title: t('Start at the device.','기기 안에서 시작합니다.'),
      description: t('Android supplies VpnService and TUN. Tunnel HTTPS reads the traffic selected by its routing configuration and begins local packet processing.','Android가 VpnService와 TUN을 제공합니다. Tunnel HTTPS는 라우팅 설정에 따라 선택된 트래픽을 읽고 로컬 패킷 처리를 시작합니다.'),
      owned: t('Packet loop, routing decisions and dispatch.','패킷 루프, 라우팅 판단과 분기.'), platform: t('VpnService, TUN and the underlying network.','VpnService, TUN 및 기반 네트워크.'),
      boundary: t('Local processing is not a remote VPN tunnel.','로컬 처리는 원격 VPN 터널이 아닙니다.'), file: 'LocalProtectionEngine.kt'
    },
    {
      scope: t('Bounded packet processing','범위가 제한된 패킷 처리'), title: t('Validate before forwarding.','전달하기 전에 검사합니다.'),
      description: t('The local engine checks supported IPv4/IPv6 packet structures. Fragment normalization and reassembly have explicit resource ceilings and supported cases.','로컬 엔진이 지원하는 IPv4/IPv6 패킷 구조를 검사합니다. 조각 정규화와 재조립에는 명시적인 자원 제한과 지원 범위가 있습니다.'),
      owned: t('Header checks, supported fragment handling and checksums.','헤더 검사, 지원하는 조각 처리와 체크섬.'), platform: t('The original packet interface and network transport.','원본 패킷 인터페이스와 네트워크 전송.'),
      boundary: t('This does not claim support for every extension header or IP protocol.','모든 확장 헤더와 IP 프로토콜을 지원한다는 뜻은 아닙니다.'), file: 'Ipv6PacketNormalizer.kt'
    }
  ];
  const protocolStages = {
    dns: [
      { scope: t('Implemented DNS policy','구현된 DNS 정책'), title: t('An answer is still input.','응답도 검사할 입력입니다.'), description: t('DNS responses pass through parsing and validation. Cache policy, resolver selection and DNS64 handling are distinct parts of the local engine.','DNS 응답을 파싱하고 검증합니다. 캐시 정책, 리졸버 선택, DNS64 처리는 각각 로컬 엔진의 구성 요소입니다.'), owned: t('DNS parsing, validation and cache policy.','DNS 파싱·검증과 캐시 정책.'), platform: t('External DNS data and HTTPS transport.','외부 DNS 데이터와 HTTPS 전송.'), boundary: t('A valid DNS response does not prove a domain is safe.','유효한 DNS 응답이라고 도메인이 안전한 것은 아닙니다.'), file: 'DnsMessageValidator.kt' },
      { scope: t('Configured resolver path','설정된 리졸버 경로'), title: t('Resolve through the selected path.','선택한 경로로 이름을 찾습니다.'), description: t('When DoH is used, DNS travels over HTTPS to the configured provider. Protected upstream sockets avoid feeding the engine back into its own VPN.','DoH 사용 시 설정된 제공자에게 HTTPS로 질의합니다. 업스트림 소켓을 VPN에서 제외해 엔진의 트래픽이 다시 자신의 VPN으로 들어오지 않게 합니다.'), owned: t('Resolver strategy and protected socket integration.','리졸버 전략과 VPN 제외 소켓 연동.'), platform: t('HTTPS cryptography and resolver infrastructure.','HTTPS 암호화와 리졸버 인프라.'), boundary: t('The chosen provider receives your query and may see your source IP.','선택한 제공자는 질의를 수신하며 출발지 IP를 볼 수 있습니다.'), file: 'DohResolver.kt' }
    ],
    tcp: [
      { scope: t('Bounded local relay','자원 제한이 있는 로컬 릴레이'), title: t('Manage the local connection.','로컬 연결 상태를 관리합니다.'), description: t('The TUN-side relay tracks local connection state. Internet-facing TCP continues through Android/Linux sockets; these are separate sides of the connection.','TUN 측 릴레이가 로컬 연결 상태를 관리합니다. 인터넷 측 TCP는 Android/Linux 소켓을 사용하며, 둘은 연결의 서로 다른 구간입니다.'), owned: t('Local relay state, resource ceilings and cleanup.','로컬 릴레이 상태, 자원 제한과 정리.'), platform: t('Internet-facing kernel TCP sockets.','인터넷 측 커널 TCP 소켓.'), boundary: t('The relay is not a complete replacement for the kernel TCP stack.','릴레이는 커널 TCP 스택의 완전한 대체가 아닙니다.'), file: 'TurboTcpForwarder.kt' },
      { scope: t('Supported TLS metadata','지원하는 TLS 메타데이터'), title: t('Adapt the handshake, not the content.','내용 대신 핸드셰이크를 다룹니다.'), description: t('For supported traffic, ClientHello parsing and fragmentation strategy adapt the initial connection. Encrypted application payloads are not decrypted.','지원하는 트래픽에서 ClientHello 파싱과 분할 전략으로 초기 연결을 조정합니다. 암호화된 애플리케이션 본문을 복호화하지 않습니다.'), owned: t('ClientHello parsing and fragmentation strategy.','ClientHello 파싱과 분할 전략.'), platform: t('End-to-end TLS cryptography and upstream sockets.','종단 간 TLS 암호화와 업스트림 소켓.'), boundary: t('This is not HTTPS interception or a guarantee of connectivity.','HTTPS 가로채기나 연결 보장 기능이 아닙니다.'), file: 'TlsClientHello.kt' }
    ],
    quic: [
      { scope: t('Selected QUIC structures','일부 QUIC 구조'), title: t('Recognize what is supported.','지원하는 구조를 식별합니다.'), description: t('The QUIC-aware path classifies supported structures and selected responses. A UDP port number alone is not proof of a complete QUIC session.','QUIC 경로에서 지원하는 구조와 일부 응답을 분류합니다. UDP 포트 번호만으로 완전한 QUIC 세션이라고 판단하지 않습니다.'), owned: t('Structural classification and selected response checks.','구조 분류와 일부 응답 검사.'), platform: t('The endpoint’s complete QUIC implementation.','상대 측의 완전한 QUIC 구현.'), boundary: t('QUIC awareness is not a full QUIC transport stack.','QUIC 인지는 완전한 QUIC 전송 스택이 아닙니다.'), file: 'QuicHttp3Guard.kt' },
      { scope: t('Bounded UDP relay','자원 제한이 있는 UDP 릴레이'), title: t('Forward with explicit limits.','명시적인 한계 안에서 전달합니다.'), description: t('UDP traffic uses protected upstream sockets with flow limits and cleanup. Protocol-specific handling depends on supported state and configuration.','UDP 트래픽은 흐름 제한과 정리 로직을 갖춘 업스트림 소켓을 사용합니다. 프로토콜별 처리는 지원 상태와 설정에 따라 달라집니다.'), owned: t('Local UDP forwarding, timeouts and resource bounds.','로컬 UDP 전달, 시간 제한과 자원 한도.'), platform: t('Platform UDP sockets and destination services.','플랫폼 UDP 소켓과 접속 대상 서비스.'), boundary: t('This illustration neither measures nor sends network traffic.','이 설명 화면은 네트워크 트래픽을 측정하거나 전송하지 않습니다.'), file: 'TurboUdpForwarder.kt' }
    ]
  };
  let protocol = 'dns';
  let engineStep = 0;
  function showStage(value, sync = true) {
    engineStep = Math.min(3, Math.max(0, Number.isFinite(Number(value)) ? Math.trunc(Number(value)) : 0));
    const data = [...commonStages, ...protocolStages[protocol]][engineStep];
    $$('[data-step]').forEach(button => {
      if (Number(button.dataset.step) === engineStep) button.setAttribute('aria-current', 'step');
      else button.removeAttribute('aria-current');
    });
    ['scope','title','description','owned','platform','boundary'].forEach(key => $('#engine-' + key).textContent = data[key]);
    const source = $('#engine-source');
    source.href = codeRoot + data.file;
    source.firstChild.textContent = data.file;
    $('#engine-step').value = String(engineStep);
    $('#engine-step').setAttribute('aria-valuetext', t('Stage ', '단계 ') + (engineStep + 1) + ' / 4');
    $('#stage-counter').textContent = `${engineStep + 1} / 4`;
    if (sync && active === 'engineering') setQuery({ protocol, step: engineStep });
    animateReadout($('#engine-title'));
    animateReadout($('#engine-description'));
  }
  function selectProtocol(value, sync = true, step = 0) {
    if (!Object.hasOwn(protocolStages, value)) return;
    stopEngine(); protocol = value;
    selectSubtab('[data-protocol]', $('#proto-' + value), '#protocol-detail');
    $('#protocol-label').textContent = { dns: 'DNS', tcp: 'TCP / TLS', quic: 'UDP / QUIC' }[value];
    showStage(step, sync);
  }
  $$('[data-protocol]').forEach(button => button.addEventListener('click', () => selectProtocol(button.dataset.protocol)));
  keyboardTabs('[data-protocol]', button => selectProtocol(button.dataset.protocol));
  $$('[data-step]').forEach(button => button.addEventListener('click', () => { stopEngine(); showStage(button.dataset.step); }));
  $('#engine-step').addEventListener('input', event => { stopEngine(); showStage(event.target.value); });
  function stopEngine() {
    clearInterval(engineTimer); engineTimer = null;
    $('#engine-play').setAttribute('aria-pressed', 'false');
    $('#engine-play span').textContent = t('Play steps','순서대로');
  }
  $('#engine-play').addEventListener('click', () => {
    if (engineTimer) { stopEngine(); return; }
    if (!motionEnabled()) { showStage(3); return; }
    showStage(0);
    $('#engine-play').setAttribute('aria-pressed', 'true');
    $('#engine-play span').textContent = t('Pause','일시 정지');
    engineTimer = setInterval(() => {
      showStage(engineStep + 1);
      if (engineStep === 3) stopEngine();
    }, 650);
  });
  function stopPlayback() { stopPreview(); stopEngine(); }
  document.addEventListener('visibilitychange', () => { if (document.hidden) stopPlayback(); });

  const privacy = {
    device: [t('Decisions stay close.','판단은 가까운 곳에서.'),t('Network configuration, local strategy learning and supported packet decisions are handled on your device. There is no developer-operated remote full-traffic VPN gateway.','네트워크 설정, 로컬 전략 학습과 지원 범위의 패킷 판단을 기기에서 처리합니다. 개발자가 운영하는 전체 트래픽용 원격 VPN 게이트웨이는 없습니다.')],
    resolver: [t('Encrypted transport. A visible query.','전송은 암호화해도, 질의는 전달됩니다.'),t('The DNS provider you choose receives the domain queries sent to it and may see your IP address. DoH protects transport, not the query from the provider itself.','선택한 DNS 제공자는 전달된 도메인 질의를 수신하며 IP 주소를 볼 수 있습니다. DoH는 전송을 보호하지만 제공자로부터 질의 자체를 숨기지는 않습니다.')],
    destination: [t('The destination still receives the request.','접속 대상은 요청을 받습니다.'),t('The site or service you use receives the connection and can see the normal network source address and account data you submit. Tunnel HTTPS does not provide a remote exit node or change your country.','사용하는 사이트나 서비스는 연결을 수신하고 일반적인 네트워크 출발지 주소와 제출한 계정 정보를 볼 수 있습니다. Tunnel HTTPS는 원격 출구 노드나 국가 변경을 제공하지 않습니다.')]
  };
  function selectPrivacy(value, sync = true) {
    if (!Object.hasOwn(privacy, value)) return;
    selectSubtab('[data-privacy]', $('#privacy-' + value), '#privacy-detail');
    $('#privacy-title').textContent = privacy[value][0];
    $('#privacy-description').textContent = privacy[value][1];
    animateReadout($('#privacy-detail'));
    if (sync && active === 'privacy') setQuery({ recipient: value });
  }
  $$('[data-privacy]').forEach(button => button.addEventListener('click', () => selectPrivacy(button.dataset.privacy)));
  keyboardTabs('[data-privacy]', button => selectPrivacy(button.dataset.privacy));

  function restoreSubstate() {
    const params = new URL(location.href).searchParams;
    if (active === 'home' && params.has('preview')) setPreview(params.get('preview'), false);
    if (active === 'controls') setControl(params.get('control') || 'dns', false);
    if (active === 'engineering') {
      let selected = params.get('protocol') || location.hash.slice(1) || 'dns';
      if (!Object.hasOwn(protocolStages, selected)) selected = 'dns';
      selectProtocol(selected, false, params.get('step') || 0);
    }
    if (active === 'privacy') selectPrivacy(params.get('recipient') || 'device', false);
    if (active === 'guide' && location.hash === '#developer') {
      $('#developer').open = true;
      $('#developer').scrollIntoView({ block: 'nearest' });
    }
  }

  // The command palette indexes local page labels; never evaluates supplied HTML.
  const dialog = $('#search-dialog');
  let returnFocus = null;
  let searchIndex = 0;
  function visibleResults() { return $$('[data-search-item]').filter(item => !item.hidden); }
  function updateSearch() {
    const query = $('#site-search').value.trim().toLocaleLowerCase().slice(0, 100);
    $$('[data-search-item]').forEach(item => { item.hidden = !item.textContent.toLocaleLowerCase().includes(query); });
    $('#search-empty').hidden = visibleResults().length !== 0;
    searchIndex = 0;
    markResult();
  }
  function markResult() { visibleResults().forEach((item, i) => item.classList.toggle('is-selected', i === searchIndex)); }
  function openSearch() {
    if (dialog.open) return;
    returnFocus = document.activeElement;
    $('#site-search').value = '';
    updateSearch();
    dialog.showModal();
    $('#site-search').focus();
  }
  function closeSearch() { if (dialog.open) dialog.close(); }
  dialog.addEventListener('close', () => { if (returnFocus?.isConnected && !returnFocus.closest('[hidden]')) returnFocus.focus({ preventScroll: true }); });
  $('#search-open').addEventListener('click', openSearch);
  $('#search-close').addEventListener('click', closeSearch);
  $('#site-search').addEventListener('input', updateSearch);
  dialog.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); closeSearch(); return; }
    if (event.target !== $('#site-search')) return;
    const items = visibleResults();
    if (!items.length) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      searchIndex = (searchIndex + (event.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length;
      markResult();
    }
    if (event.key === 'Enter') { event.preventDefault(); items[searchIndex].click(); }
  });
  dialog.addEventListener('click', event => {
    if (event.target !== dialog) return;
    const rect = dialog.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closeSearch();
  });
  document.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); dialog.open ? closeSearch() : openSearch(); }
  });

  const fileInput = $('#apk-file');
  const expectedInput = $('#expected-hash');
  function fileFeedback(state, message) {
    $('#hash-result').dataset.state = state;
    $('#hash-result').textContent = message;
  }
  fileInput.addEventListener('change', () => {
    ++fileSequence;
    $('#verify-button').disabled = false;
    const file = fileInput.files?.[0];
    $('.file-label strong').textContent = file ? file.name.slice(0, 75) : t('Choose a local APK', '로컬 APK 선택');
    fileFeedback('ready', file ? t('Ready. Calculate the checksum to inspect this file.', '준비됐습니다. 체크섬을 계산해 파일을 확인하세요.') : t('No file selected.', '선택한 파일이 없습니다.'));
  });
  expectedInput.addEventListener('input', () => {
    ++fileSequence;
    $('#verify-button').disabled = false;
    expectedInput.removeAttribute('aria-invalid');
    fileFeedback('ready', t('Comparison value changed. Calculate again to compare.', '비교값이 바뀌었습니다. 다시 계산해 비교하세요.'));
  });
  $('#verify-form').addEventListener('submit', async event => {
    event.preventDefault();
    const id = ++fileSequence;
    const file = fileInput.files?.[0];
    const expected = expectedInput.value.trim().toLowerCase();
    if (expected && !/^[a-f0-9]{64}$/.test(expected)) {
      expectedInput.setAttribute('aria-invalid', 'true'); expectedInput.focus();
      fileFeedback('error', t('Expected SHA-256 must contain exactly 64 hexadecimal characters. Copy the checksum from the exact release.', 'SHA-256은 64자리 16진수여야 합니다. 해당 릴리스의 체크섬을 복사하세요.')); return;
    }
    expectedInput.removeAttribute('aria-invalid');
    if (!file) { fileFeedback('error', t('Choose a local file first.', '먼저 로컬 파일을 선택하세요.')); fileInput.focus(); return; }
    if (file.size > 128 * 1024 * 1024) { fileFeedback('error', t('File exceeds 128 MiB. Use your operating system’s SHA-256 tool.', '128 MiB를 초과합니다. 운영체제의 SHA-256 도구를 사용하세요.')); return; }
    if (!isSecureContext || !crypto.subtle) { fileFeedback('error', t('Local hashing requires HTTPS or localhost. Use your operating system’s SHA-256 tool.', '로컬 계산에는 HTTPS 또는 localhost가 필요합니다. 운영체제의 SHA-256 도구를 사용하세요.')); return; }
    $('#verify-button').disabled = true;
    fileFeedback('working', t('Calculating locally… Nothing is uploaded.', '기기에서 계산 중… 파일은 업로드하지 않습니다.'));
    try {
      const bytes = await file.arrayBuffer();
      const digest = await crypto.subtle.digest('SHA-256', bytes);
      if (id !== fileSequence) return;
      const actual = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
      const state = !expected ? 'calculated' : actual === expected ? 'match' : 'mismatch';
      const message = {
        calculated: t('SHA-256 calculated. No comparison value supplied.', 'SHA-256 계산 완료. 비교값은 입력되지 않았습니다.'),
        match: t('Matches the checksum you supplied. This does not verify the signing certificate.', '입력한 체크섬과 일치합니다. 서명 인증서를 검증한 것은 아닙니다.'),
        mismatch: t('Does not match the checksum you supplied. Check the version and do not assume this is that release.', '입력한 체크섬과 다릅니다. 버전을 확인하고 동일한 배포본이라고 판단하지 마세요.')
      }[state];
      fileFeedback(state, message + '\n\n' + actual);
    } catch (_) {
      if (id === fileSequence) fileFeedback('error', t('Could not read this file. Choose it again or use a local checksum tool.', '파일을 읽지 못했습니다. 다시 선택하거나 로컬 체크섬 도구를 사용하세요.'));
    } finally { if (id === fileSequence) $('#verify-button').disabled = false; }
  });

  $$('[data-copy]').forEach(button => button.addEventListener('click', async () => {
    const element = document.getElementById(button.dataset.copy);
    if (!element) return;
    let timeout;
    try {
      if (!navigator.clipboard?.writeText) throw new Error('No clipboard');
      await Promise.race([navigator.clipboard.writeText(element.textContent), new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('Timeout')), 1000); })]);
      notify(t('Copied. Nothing was executed.', '복사했습니다. 명령을 실행하지는 않았습니다.'));
    } catch (_) {
      const range = document.createRange(); range.selectNodeContents(element);
      const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
      notify(t('Text selected. Copy with your browser or keyboard.', '텍스트를 선택했습니다. 브라우저나 키보드로 복사하세요.'));
    } finally { clearTimeout(timeout); }
  }));

  updateMotion();
  updatePage(routeFromURL(), false);
  restoreSubstate();
  if (!history.state) history.replaceState({ page: active }, '', location.href);
  html.dataset.enhanced = 'true';
})();
