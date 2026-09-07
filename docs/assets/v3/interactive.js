/* Tunnel HTTPS interactive edition. All simulations are architectural, not live traffic. */
(() => {
  'use strict';
  const root = document.documentElement;
  root.classList.add('ui-js');
  const base = '/Tunnel-HTTPS/';
  const routes = {
    home: {path: '', title: 'Tunnel HTTPS — Network control. On your device.'},
    engine: {path: 'engineering/', title: 'Inside the engine — Tunnel HTTPS'},
    guide: {path: 'guide/', title: 'Install and verify — Tunnel HTTPS'},
    trust: {path: 'privacy/', title: 'Privacy and boundaries — Tunnel HTTPS'}
  };
  const main = document.querySelector('#main');
  if (!main) return;
  const $ = (s, parent = document) => parent.querySelector(s);
  const $$ = (s, parent = document) => [...parent.querySelectorAll(s)];
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let cleanup = () => {};
  let navigating = false;
  let toastTimer;
  let searchOrigin;
  let searchIndex = 0;
  const scrollPositions = new Map();
  const themeButtons = $$('[data-theme-toggle]');
  const menu = $('#mobile-navigation');
  const menuButton = $('[data-menu-toggle]');
  const dialog = $('#site-search');
  const searchInput = $('#search-input');
  const results = $('#search-results');
  const themeMedia = matchMedia('(prefers-color-scheme: dark)');
  const storedTheme = () => { try { return localStorage.getItem('tunnel-theme'); } catch (_) { return null; } };
  function syncTheme() {
    const selected = storedTheme();
    const next = selected === 'dark' || selected === 'light' ? selected : themeMedia.matches ? 'dark' : 'light';
    root.dataset.theme = next;
    for (const b of themeButtons) {
      b.setAttribute('aria-label', `Switch to ${next === 'dark' ? 'light' : 'dark'} theme`);
      b.setAttribute('aria-pressed', String(next === 'dark'));
    }
  }
  syncTheme();
  themeMedia.addEventListener('change', syncTheme);
  themeButtons.forEach(b => b.addEventListener('click', () => {
    const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem('tunnel-theme', next); } catch (_) {}
    root.dataset.theme = next;
    themeButtons.forEach(x => {
      x.setAttribute('aria-pressed', String(next === 'dark'));
      x.setAttribute('aria-label', `Switch to ${next === 'dark' ? 'light' : 'dark'} theme`);
    });
  }));
  function closeMenu(focus = false) {
    if (!menu || !menuButton) return;
    menu.hidden = true;
    menuButton.setAttribute('aria-expanded', 'false');
    if (focus) menuButton.focus();
  }
  menuButton?.addEventListener('click', () => {
    menu.hidden = !menu.hidden;
    menuButton.setAttribute('aria-expanded', String(!menu.hidden));
  });
  document.addEventListener('pointerdown', e => {
    if (menu && !menu.hidden && !menu.contains(e.target) && !menuButton?.contains(e.target)) closeMenu();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && menu && !menu.hidden) closeMenu(true);
    const tag = document.activeElement?.tagName;
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      if (dialog?.open) dialog.close(); else openSearch();
    } else if (e.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) && !document.activeElement?.isContentEditable && !dialog?.open) {
      e.preventDefault(); openSearch();
    }
  });
  function routeFor(url) {
    if (url.origin !== location.origin || !url.pathname.startsWith(base)) return null;
    const relative = url.pathname.slice(base.length).replace(/index\.html$/, '');
    return Object.keys(routes).find(k => routes[k].path === relative) || null;
  }
  function announce(text) { const live = $('#route-announcement'); if (live) live.textContent = text; }
  function toast(text) {
    const el = $('#site-toast');
    if (!el) return;
    clearTimeout(toastTimer); el.textContent = text; el.hidden = false;
    toastTimer = setTimeout(() => { el.hidden = true; }, 2800);
  }
  function activeNav(key) {
    $$('[data-nav]').forEach(a => {
      if (a.dataset.nav === key) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
  }
  function moveTo(url, restore) {
    if (Number.isFinite(restore)) { window.scrollTo({top: restore, behavior: 'instant'}); return; }
    if (url.hash) {
      let id; try { id = decodeURIComponent(url.hash.slice(1)); } catch (_) { id = ''; }
      const target = id ? document.getElementById(id) : null;
      if (target && main.contains(target)) { target.scrollIntoView({behavior: 'instant', block: 'start'}); return; }
    }
    window.scrollTo({top: 0, behavior: 'instant'});
  }
  async function navigate(url, {push = true, restore} = {}) {
    const key = routeFor(url);
    const template = key ? document.getElementById(`screen-${key}`) : null;
    if (!key || !template || navigating) return false;
    navigating = true;
    scrollPositions.set(location.href, window.scrollY);
    closeMenu();
    if (dialog?.open) { searchOrigin = null; dialog.close(); }
    const apply = () => {
      cleanup();
      main.replaceChildren(template.content.cloneNode(true));
      main.dataset.screen = key;
      document.title = routes[key].title;
      const canonical = $('link[rel="canonical"]');
      if (canonical) canonical.href = location.origin + base + routes[key].path;
      if (push) history.pushState({screen: key}, '', url);
      activeNav(key);
      initPage(url);
      main.focus({preventScroll: true});
      moveTo(url, restore);
      announce(routes[key].title);
    };
    try {
      if (typeof document.startViewTransition === 'function' && !reduced.matches) {
        const transition = document.startViewTransition(apply);
        await transition.finished.catch(() => {});
      } else {
        apply(); main.classList.remove('ui-enter'); void main.offsetWidth; main.classList.add('ui-enter');
      }
      return true;
    } finally { navigating = false; }
  }
  document.addEventListener('click', e => {
    const a = e.target.closest('a[data-route]');
    if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.altKey || e.shiftKey || a.hasAttribute('download') || a.target === '_blank') return;
    let url; try { url = new URL(a.href); } catch (_) { return; }
    if (!routeFor(url) || !document.getElementById(`screen-${routeFor(url)}`)) return;
    e.preventDefault();
    if (!navigating) void navigate(url);
  });
  if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
  window.addEventListener('popstate', () => { void navigate(new URL(location.href), {push: false, restore: scrollPositions.get(location.href) ?? 0}); });
  $('[data-top]')?.addEventListener('click', () => { window.scrollTo({top: 0, behavior: reduced.matches ? 'instant' : 'smooth'}); });
  const searchItems = [
    ['Overview', 'What Tunnel HTTPS does', '', 'home android open source network'],
    ['Packet explorer', 'Follow DNS, TCP and QUIC through the engine', 'engineering/', 'architecture packet walkthrough'],
    ['DNS validation', 'Message checks, cache policy and DoH', 'engineering/?protocol=dns#packet-explorer', 'dns doh cache resolver dns64 nat64'],
    ['TCP and TLS', 'Local relay state and ClientHello adaptation', 'engineering/?protocol=tcp#packet-explorer', 'tcp tls fragmentation clienthello sni'],
    ['QUIC-aware UDP', 'A bounded guard, not a complete QUIC stack', 'engineering/?protocol=quic#packet-explorer', 'udp quic retry http3'],
    ['Capability boundaries', 'Implementation scope and source links', 'engineering/#capabilities', 'support limitations features'],
    ['Install the Android app', 'Official release, Android consent and first connection', 'guide/', 'download apk install android'],
    ['Verify an APK locally', 'Compute SHA-256 without uploading your file', 'guide/#verify', 'sha256 checksum fingerprint hash integrity'],
    ['Build from source', 'Gradle and ADB commands for contributors', 'guide/#developer', 'build gradle adb commands developer'],
    ['Privacy and trust', 'What stays local and what reaches the network', 'privacy/', 'privacy security trust data encrypted ip country']
  ];
  function renderSearch() {
    if (!results || !searchInput) return;
    const terms = searchInput.value.trim().toLowerCase().split(/\s+/).filter(Boolean).slice(0, 12);
    const visible = searchItems.filter(x => terms.every(q => (x[0] + ' ' + x[1] + ' ' + x[3]).toLowerCase().includes(q)));
    results.replaceChildren(); searchIndex = 0;
    visible.forEach((entry, index) => {
      const a = document.createElement('a');
      a.href = base + entry[2]; a.dataset.route = ''; a.setAttribute('role', 'option');
      a.id = `search-option-${index}`; a.setAttribute('aria-selected', String(index === 0));
      const text = document.createElement('div');
      const strong = document.createElement('strong'); strong.textContent = entry[0];
      const description = document.createElement('span'); description.textContent = entry[1];
      text.append(strong, description); a.append(text); results.append(a);
    });
    if (!visible.length) {
      const p = document.createElement('p'); p.className = 'ui-search-empty'; p.textContent = 'No matching page. Try “DNS”, “install” or “privacy”.'; results.append(p);
      searchInput.removeAttribute('aria-activedescendant');
    } else searchInput.setAttribute('aria-activedescendant', 'search-option-0');
    const count = $('#search-count'); if (count) count.textContent = `${visible.length} ${visible.length === 1 ? 'result' : 'results'}`;
  }
  function openSearch() {
    if (!dialog || typeof dialog.showModal !== 'function') return;
    searchOrigin = document.activeElement;
    closeMenu(); searchInput.value = ''; renderSearch(); dialog.showModal(); searchInput.focus();
  }
  $$('[data-open-search]').forEach(b => b.addEventListener('click', openSearch));
  $('[data-close-search]')?.addEventListener('click', () => dialog.close());
  dialog?.addEventListener('click', e => {
    if (e.target !== dialog) return;
    const r = dialog.getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) dialog.close();
  });
  dialog?.addEventListener('close', () => { if (searchOrigin?.isConnected) searchOrigin.focus({preventScroll: true}); searchOrigin = null; });
  searchInput?.addEventListener('input', renderSearch);
  searchInput?.addEventListener('keydown', e => {
    const options = $$('[role="option"]', results);
    if (!options.length) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault(); searchIndex = (searchIndex + (e.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
      options.forEach((a, i) => a.setAttribute('aria-selected', String(i === searchIndex)));
      searchInput.setAttribute('aria-activedescendant', options[searchIndex].id);
      options[searchIndex].scrollIntoView({block: 'nearest'});
    } else if (e.key === 'Enter') { e.preventDefault(); options[searchIndex].click(); }
  });
  function tabsKeyboard(buttons, activate) {
    buttons.forEach((button, index) => button.addEventListener('keydown', e => {
      let next;
      if (e.key === 'ArrowRight') next = (index + 1) % buttons.length;
      else if (e.key === 'ArrowLeft') next = (index + buttons.length - 1) % buttons.length;
      else if (e.key === 'Home') next = 0;
      else if (e.key === 'End') next = buttons.length - 1;
      else return;
      e.preventDefault(); buttons[next].focus(); activate(buttons[next]);
    }));
  }
  const sourceRoot = 'https://github.com/Sp2ctr2/Tunnel-HTTPS/blob/main/app/src/main/java/com/tunnelvpn/app/';
  const paths = {
    dns: [
      ['An app needs a name', 'Android application', 'The application asks the system to resolve a host name. This walkthrough illustrates the app’s captured DNS path, not every possible resolver used by every Android application.', 'Capture has a scope', 'Applications with their own encrypted DNS may not use this DNS path.', 'LocalProtectionEngine.kt'],
      ['Into the local interface', 'VpnService / TUN', 'Android exposes traffic routed to the VPN through a local TUN interface. The application reads packets; there is no developer-operated remote tunnel at this stage.', 'Consent before capture', 'The Android VPN permission and configured routing determine which traffic reaches the engine.', 'TunnelVpnService.kt'],
      ['Validate the packet', 'IPv4 / IPv6 normalization', 'The local packet layer checks structure before protocol handling. Fragment handling and retained state have explicit bounds.', 'Malformed is not a feature', 'Rejecting invalid input and bounding retained memory are separate from claiming support for every IP extension.', 'Ipv6PacketNormalizer.kt'],
      ['Inspect the DNS message', 'DNS parser, validator and cache', 'DNS handling parses messages, applies local policy and checks eligible cache entries. Upstream responses are checked before being accepted into the local path.', 'A response must match its request', 'Message validation is not the same as performing a complete local DNSSEC chain-of-trust validation.', 'DnsMessageValidator.kt'],
      ['Resolve over HTTPS', 'Protected DoH transport', 'When configured, queries go to an external DNS-over-HTTPS provider using protected upstream networking. Resolver policy and network conditions still affect availability.', 'Encrypted transport has an endpoint', 'The selected provider receives the DNS query. DoH does not hide the query from that provider.', 'DohResolver.kt'],
      ['Return the answer', 'Local response path', 'An accepted answer returns through the local DNS path to the application. DNS64 synthesis and NAT64 behavior depend on configuration and the underlying network.', 'The diagram is not telemetry', 'These steps explain architecture. This web page sends no DNS probe and captures no traffic.', 'Dns64Packet.kt']
    ],
    tcp: [
      ['A connection begins', 'Android application', 'An application starts a TCP connection. Packets selected by the VPN routing configuration enter the local path.', 'No claim of universal capture', 'App bypass and the configured VPN routes change which traffic the engine processes.', 'LocalProtectionEngine.kt'],
      ['Read from TUN', 'VpnService / TUN', 'The Android VPN interface provides packet ingress. Lifecycle ownership and network changes are handled by the service and engine.', 'Local interface, not a remote VPN', 'The operating-system VPN indicator does not imply that a remote exit server exists.', 'TunnelVpnService.kt'],
      ['Normalize the packet', 'IP validation', 'IPv4 or IPv6 structure is checked before transport state is advanced. Resource ceilings bound retained packets and fragments.', 'Bounded packet handling', 'Protocol completeness and measured device compatibility are different questions.', 'Ipv4PacketNormalizer.kt'],
      ['Terminate and relay locally', 'Userspace TCP / TLS handling', 'The local relay manages TUN-side connection state and selected TCP semantics. TLS ClientHello metadata can inform a bounded fragmentation strategy.', 'No TLS decryption', 'ClientHello inspection is not TLS termination. The project does not decrypt application HTTPS payloads.', 'TurboTcpForwarder.kt'],
      ['Open a protected socket', 'Android / Linux TCP', 'The upstream connection uses the platform’s kernel TCP implementation. Socket protection keeps the connection from being captured recursively by the VPN.', 'Know which stack does the work', 'The repository’s local relay is not a replacement for the Internet-facing Linux TCP stack.', 'TurboTcpForwarder.kt'],
      ['Reach the destination', 'Destination service', 'Traffic goes to the destination through the underlying network. The destination sees the network’s public source IP, not a Tunnel HTTPS exit address.', 'No country or IP relocation', 'This architecture does not provide remote VPN exit locations or an anonymity guarantee.', 'LocalProtectionEngine.kt']
    ],
    quic: [
      ['An app sends UDP', 'Android application', 'An application may use QUIC over UDP. UDP port 443 alone is not sufficient to identify every datagram as a supported QUIC exchange.', 'Classify, do not assume', 'The walkthrough distinguishes protocol structure from a port-number guess.', 'QuicHttp3Guard.kt'],
      ['Capture the local path', 'VpnService / TUN', 'UDP packets selected by the VPN enter the same local interface used by the other supported packet paths.', 'Routing still applies', 'The VPN’s configured scope and bypass rules remain relevant.', 'TunnelVpnService.kt'],
      ['Check the IP envelope', 'Packet normalization', 'The IP layer is validated before the UDP flow is dispatched. Malformed packet handling and retained state remain bounded.', 'Resource limits are deliberate', 'The website does not claim an unlimited number of simultaneous flows.', 'Ipv6PacketNormalizer.kt'],
      ['Inspect supported structure', 'UDP relay and QUIC guard', 'Selected QUIC Initial and response structures are inspected. Guard state can inform constrained fallback behavior without implementing the entire QUIC transport.', 'Partial by design', 'This is QUIC-aware handling, not a complete QUIC stack, application decryptor or universal HTTP/3 guarantee.', 'QuicHttp3Guard.kt'],
      ['Use protected UDP', 'Platform datagram socket', 'Eligible UDP traffic is relayed using upstream sockets that are protected from VPN recapture. Local flow state has explicit limits and cleanup.', 'Keep the loop outside itself', 'Protected sockets are a routing primitive, not an additional encryption layer.', 'TurboUdpForwarder.kt'],
      ['Observe the response', 'Destination and local guard', 'Responses return through the relay. Supported response checks inform local state; ambiguous or unsupported inputs do not become proof of successful QUIC support.', 'No live network requests here', 'The explorer is a controlled illustration. Use the source and tests to inspect implementation details.', 'QuicHttp3Guard.kt']
    ]
  };
  const labels = {
    dns: ['Application', 'VpnService / TUN', 'IP validation', 'DNS / cache', 'Protected DoH', 'DNS answer'],
    tcp: ['Application', 'VpnService / TUN', 'IP validation', 'TCP / TLS relay', 'Protected socket', 'Destination'],
    quic: ['Application', 'VpnService / TUN', 'IP validation', 'UDP / QUIC guard', 'Protected UDP', 'Response']
  };
  function initPage(url) {
    const disposers = [];
    const homeButtons = $$('[data-home-protocol]', main);
    const homeCopy = {
      dns: ['DNS with scrutiny.', 'Parse names. Apply policy. Validate responses.', 'DNS · DoH · DNS64'],
      tcp: ['Transport, locally.', 'Local relay state. Platform TCP upstream.', 'TCP · TLS ClientHello'],
      quic: ['QUIC-aware by design.', 'Inspect supported structures. Keep limits explicit.', 'UDP · QUIC guard']
    };
    homeButtons.forEach(b => b.addEventListener('click', () => {
      const key = b.dataset.homeProtocol; if (!Object.hasOwn(homeCopy, key)) return;
      homeButtons.forEach(x => x.setAttribute('aria-pressed', String(x === b)));
      const [title, description, footer] = homeCopy[key];
      $('[data-home-title]', main).textContent = title;
      $('[data-home-description]', main).textContent = description;
      $('[data-home-footer]', main).textContent = footer;
      $('[data-home-explore]', main).href = base + `engineering/?protocol=${key}#packet-explorer`;
      const canvas = $('.ui-canvas', main); canvas.classList.remove('is-active'); void canvas.offsetWidth; canvas.classList.add('is-active');
    }));
    const lab = $('.ui-lab', main);
    if (lab) {
      let protocol = Object.hasOwn(paths, url.searchParams.get('protocol')) ? url.searchParams.get('protocol') : 'dns';
      let step = 0;
      let timer = null;
      const buttons = $$('[data-protocol]', lab);
      const nodes = $$('[data-step]', lab);
      const play = $('[data-play]', lab);
      function pause() { if (timer) clearInterval(timer); timer = null; play.setAttribute('aria-pressed', 'false'); $('[data-play-label]', play).textContent = 'Play walkthrough'; }
      function render() {
        const p = paths[protocol][step];
        buttons.forEach(b => { const active = b.dataset.protocol === protocol; b.setAttribute('aria-selected', String(active)); b.tabIndex = active ? 0 : -1; });
        nodes.forEach((n, i) => { $('strong', n).textContent = labels[protocol][i]; if (i === step) n.setAttribute('aria-current', 'step'); else n.removeAttribute('aria-current'); });
        $('[data-step-count]', lab).textContent = `${String(step + 1).padStart(2, '0')} / 06`;
        $('[data-step-owner]', lab).textContent = p[1];
        $('[data-step-title]', lab).textContent = p[0];
        $('[data-step-description]', lab).textContent = p[2];
        $('[data-boundary-title]', lab).textContent = p[3];
        $('[data-boundary-copy]', lab).textContent = p[4];
        const link = $('[data-step-source]', lab); link.href = sourceRoot + p[5];
        $('[data-progress]', lab).dataset.progress = String(step);
        $('[data-prev]', lab).disabled = step === 0;
        $('[data-next]', lab).disabled = step === 5;
        const copy = $('.ui-inspector-copy', lab); copy.classList.remove('ui-update'); void copy.offsetWidth; copy.classList.add('ui-update');
      }
      function choose(button) {
        pause(); protocol = button.dataset.protocol; step = 0; render();
        const current = new URL(location.href); current.searchParams.set('protocol', protocol); history.replaceState(history.state, '', current);
      }
      buttons.forEach(b => b.addEventListener('click', () => choose(b)));
      tabsKeyboard(buttons, choose);
      nodes.forEach((n, i) => n.addEventListener('click', () => { pause(); step = i; render(); }));
      $('[data-prev]', lab).addEventListener('click', () => { pause(); step = Math.max(0, step - 1); render(); });
      $('[data-next]', lab).addEventListener('click', () => { pause(); step = Math.min(5, step + 1); render(); });
      $('[data-reset]', lab).addEventListener('click', () => { pause(); step = 0; render(); });
      play.addEventListener('click', () => {
        if (timer) { pause(); return; }
        if (step === 5) { step = 0; render(); }
        play.setAttribute('aria-pressed', 'true'); $('[data-play-label]', play).textContent = 'Pause walkthrough';
        timer = setInterval(() => { step = Math.min(5, step + 1); render(); if (step === 5) pause(); }, 2200);
      });
      const visibility = () => { if (document.hidden) pause(); };
      document.addEventListener('visibilitychange', visibility);
      disposers.push(() => { pause(); document.removeEventListener('visibilitychange', visibility); });
      render();
    }
    const filters = $$('[data-filter]', main);
    const rows = $$('[data-category]', main);
    filters.forEach(b => b.addEventListener('click', () => {
      filters.forEach(x => x.setAttribute('aria-pressed', String(x === b)));
      let count = 0;
      rows.forEach(r => { r.hidden = b.dataset.filter !== 'all' && r.dataset.category !== b.dataset.filter; if (!r.hidden) count++; });
      const live = $('[data-filter-count]', main); if (live) live.textContent = `${count} capability areas shown`;
    }));
    const trust = {
      device: ['01 / On the device', 'Keep the decision close.', 'Packet metadata and selected protocol information are processed by the local engine. Turbo learns locally; Aegis is an experimental shadow policy without live routing authority.', 'Review the local architecture', 'https://github.com/Sp2ctr2/Tunnel-HTTPS/blob/main/docs/TURBO_AI_ARCHITECTURE.md'],
      network: ['02 / On the network', 'Local does not mean invisible.', 'Destination services still receive your traffic. A selected DoH provider receives DNS queries. The underlying network can still observe endpoints and traffic characteristics; this is not an anonymity promise.', 'Read the network data flow', 'https://github.com/Sp2ctr2/Tunnel-HTTPS/blob/main/docs/release/03_NETWORK_SECURITY_AND_DATA_FLOW.md'],
      website: ['03 / On this website', 'An explorer. Not a probe.', 'This site does not capture packets, run a DNS probe or upload the APK you select. Search, theme selection and checksum tools run in the browser. GitHub still hosts the site and handles normal hosting requests.', 'Review website source', 'https://github.com/Sp2ctr2/Tunnel-HTTPS/tree/main/tools/site-v3']
    };
    const trustButtons = $$('[data-trust]', main);
    function chooseTrust(b) {
      const info = trust[b.dataset.trust]; if (!info) return;
      trustButtons.forEach(x => { const active = x === b; x.setAttribute('aria-selected', String(active)); x.tabIndex = active ? 0 : -1; });
      $('[data-trust-eyebrow]', main).textContent = info[0]; $('[data-trust-title]', main).textContent = info[1]; $('[data-trust-copy]', main).textContent = info[2];
      const link = $('[data-trust-link]', main); $('span', link).textContent = info[3]; link.href = info[4];
    }
    trustButtons.forEach(b => b.addEventListener('click', () => chooseTrust(b))); tabsKeyboard(trustButtons, chooseTrust);
    $$('[data-copy]', main).forEach(b => b.addEventListener('click', async () => {
      const target = document.getElementById(b.dataset.copy); if (!target || !main.contains(target)) return;
      const value = target.value ?? target.textContent;
      try { await navigator.clipboard.writeText(value); toast('Copied to clipboard'); }
      catch (_) { if (target.select) target.select(); else { const range = document.createRange(); range.selectNodeContents(target); const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range); } toast('Select and copy the highlighted text'); }
    }));
    const fileInput = $('#apk-file', main);
    if (fileInput) {
      let generation = 0;
      let actual = '';
      const status = $('#hash-status', main);
      const output = $('#hash-output', main);
      const expected = $('#expected-hash', main);
      const result = $('.ui-hash-result', main);
      const hashState = (text, state) => { status.textContent = text; status.dataset.state = state; };
      function compare() {
        if (!actual) return;
        const requested = expected.value.trim().toLowerCase();
        if (!requested) { hashState('Checksum ready. Compare it with an independently trusted release checksum; a hash alone does not verify the signer.', 'ready'); return; }
        if (!/^[0-9a-f]{64}$/.test(requested)) { hashState('Enter a complete 64-character SHA-256 value.', 'error'); return; }
        hashState(actual === requested ? 'SHA-256 matches the expected value. This compares bytes, not the APK signer.' : 'SHA-256 does not match. Do not treat this file as the expected release.', actual === requested ? 'match' : 'mismatch');
      }
      fileInput.addEventListener('change', async () => {
        const run = ++generation; const file = fileInput.files?.[0]; actual = ''; output.value = ''; result.hidden = true;
        if (!file) { hashState('Your file stays in this browser. Nothing is uploaded.', 'idle'); return; }
        if (!/\.apk$/i.test(file.name)) { hashState('Choose an APK file. Other file types are not accepted.', 'error'); return; }
        if (!file.size || file.size > 128 * 1024 * 1024) { hashState('Choose a non-empty APK smaller than 128 MiB.', 'error'); return; }
        if (!globalThis.crypto?.subtle) { hashState('This browser cannot use Web Crypto here. Use the command-line verification instructions below.', 'error'); return; }
        hashState(`Computing SHA-256 for ${file.name} locally…`, 'busy');
        try {
          const data = await file.arrayBuffer(); const hash = await crypto.subtle.digest('SHA-256', data);
          if (run !== generation) return;
          actual = [...new Uint8Array(hash)].map(b => b.toString(16).padStart(2, '0')).join('');
          output.value = actual; result.hidden = false; compare();
        } catch (_) { if (run === generation) hashState('The file could not be read. Try again or use the command-line verification instructions.', 'error'); }
      });
      expected.addEventListener('input', compare);
      disposers.push(() => { generation++; actual = ''; });
    }
    cleanup = () => disposers.forEach(fn => fn());
  }
  activeNav(main.dataset.screen);
  initPage(new URL(location.href));
})();
