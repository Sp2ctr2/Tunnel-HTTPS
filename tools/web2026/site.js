'use strict';
(() => {
  const html = document.documentElement;
  const root = new URL(html.dataset.siteRoot || './', location.href);
  const routes = Object.freeze({ home: '', engineering: 'engineering/', guide: 'guide/', privacy: 'privacy/' });
  const titles = Object.freeze({ home: 'Your network. A more direct path.', engineering: 'Inside the engine', guide: 'Get started', privacy: 'Privacy & boundaries' });
  const sourceRoot = 'https://github.com/Sp2ctr2/Tunnel-HTTPS/blob/main/app/src/main/java/com/tunnelvpn/app/';
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const $ = (query, parent = document) => parent.querySelector(query);
  const $$ = (query, parent = document) => Array.from(parent.querySelectorAll(query));
  const storage = {
    get(key) { try { return localStorage.getItem(key); } catch (_) { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch (_) {} }
  };
  let route = document.body.dataset.start || 'home';
  let transition = null;
  let navigationSequence = 0;
  let fallbackTimer;
  let toastTimer;
  let playbackTimer;
  let protocol = 'dns';
  let step = 0;
  let filter = 'all';
  let fileSequence = 0;
  html.dataset.enhanced = 'true';

  function notify(message) {
    const toast = $('#toast');
    clearTimeout(toastTimer);
    toast.textContent = message;
    toast.hidden = false;
    toastTimer = setTimeout(() => { toast.hidden = true; }, 2800);
  }

  function applyTheme(theme) {
    const next = theme === 'light' ? 'light' : 'dark';
    html.dataset.theme = next;
    storage.set('tunnel2026.theme', next);
    $('.theme-toggle').setAttribute('aria-label', next === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
    $('meta[name="theme-color"]').content = next === 'dark' ? '#0b100d' : '#f4f6f0';
  }
  applyTheme(html.dataset.theme);
  $('.theme-toggle').addEventListener('click', () => applyTheme(html.dataset.theme === 'dark' ? 'light' : 'dark'));

  function applyMotion() {
    const paused = reducedMotion.matches || storage.get('tunnel2026.motion') === 'paused';
    html.dataset.motion = paused ? 'paused' : 'running';
    const button = $('.motion-button');
    button.setAttribute('aria-pressed', String(paused));
    button.textContent = reducedMotion.matches ? 'Reduced motion enabled' : paused ? 'Resume animation' : 'Pause animation';
    button.disabled = reducedMotion.matches;
    if (reducedMotion.matches) stopPlayback();
  }
  $('.motion-button').addEventListener('click', () => {
    storage.set('tunnel2026.motion', html.dataset.motion === 'paused' ? 'running' : 'paused');
    applyMotion();
  });
  reducedMotion.addEventListener('change', applyMotion);
  applyMotion();
  document.addEventListener('visibilitychange', () => {
    html.dataset.visibility = document.hidden ? 'hidden' : 'visible';
    if (document.hidden) stopPlayback();
  });
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(entries => {
      const visible = entries.some(entry => entry.isIntersecting);
      html.dataset.visibility = visible && !document.hidden ? 'visible' : 'hidden';
    }, { threshold: 0.05 }).observe($('.hero-stage'));
  }

  const menuButton = $('.menu-toggle');
  const mobileNav = $('#mobile-nav');
  function setMenu(open, restoreFocus = false) {
    menuButton.setAttribute('aria-expanded', String(open));
    menuButton.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    mobileNav.hidden = !open;
    if (restoreFocus) menuButton.focus();
  }
  menuButton.addEventListener('click', () => setMenu(mobileNav.hidden));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !mobileNav.hidden) setMenu(false, true);
  });
  document.addEventListener('pointerdown', event => {
    if (!mobileNav.hidden && !event.target.closest('.site-header')) setMenu(false);
  });
  matchMedia('(min-width: 681px)').addEventListener('change', event => { if (event.matches) setMenu(false); });

  $$('a[data-route]').forEach(anchor => {
    const name = anchor.dataset.route;
    if (!Object.hasOwn(routes, name)) return;
    const hash = new URL(anchor.getAttribute('href'), location.href).hash;
    anchor.href = new URL(routes[name] + hash, root).href;
  });

  function routeFromLocation() {
    const path = location.pathname.startsWith(root.pathname) ? location.pathname.slice(root.pathname.length) : '';
    const normalized = path.replace(/index\.html$/, '').replace(/\/+$/, '');
    return Object.keys(routes).find(name => routes[name].replace(/\/$/, '') === normalized) || document.body.dataset.start || 'home';
  }
  function setScreen(name) {
    route = name;
    $$('.screen').forEach(screen => {
      const active = screen.dataset.screen === name;
      screen.classList.toggle('is-active', active);
      if ('inert' in screen) screen.inert = !active;
    });
    $$('a[data-route]').forEach(anchor => {
      if (anchor.dataset.route === name) anchor.setAttribute('aria-current', 'page');
      else anchor.removeAttribute('aria-current');
    });
    document.title = titles[name] + ' — Tunnel HTTPS';
    document.body.dataset.current = name;
  }
  function positionPage(hash, restoredY) {
    if (hash) {
      let id;
      try { id = decodeURIComponent(hash.slice(1)); } catch (_) { id = ''; }
      const target = id ? document.getElementById(id) : null;
      if (target && target.closest('.screen.is-active')) { target.scrollIntoView({ behavior: 'instant', block: 'start' }); return; }
    }
    window.scrollTo({ top: Number.isFinite(restoredY) ? restoredY : 0, behavior: 'instant' });
  }
  async function navigate(name, hash = '', push = true, restoredY) {
    if (!Object.hasOwn(routes, name)) return;
    const sequence = ++navigationSequence;
    stopPlayback();
    setMenu(false);
    if (push) {
      history.replaceState({ ...(history.state || {}), scrollY: window.scrollY }, '', location.href);
      history.pushState({ route: name, scrollY: 0 }, '', new URL(routes[name] + hash, root));
    }
    const update = () => { setScreen(name); positionPage(hash, restoredY); };
    if (transition) transition.skipTransition();
    if (typeof document.startViewTransition === 'function' && !reducedMotion.matches) {
      transition = document.startViewTransition(update);
      try { await transition.updateCallbackDone; } catch (_) { update(); }
      transition.finished.catch(() => {}).finally(() => { if (sequence === navigationSequence) transition = null; });
    } else {
      update();
      clearTimeout(fallbackTimer);
      html.classList.remove('is-navigating');
      if (!reducedMotion.matches) {
        void document.body.offsetWidth;
        html.classList.add('is-navigating');
        fallbackTimer = setTimeout(() => html.classList.remove('is-navigating'), 400);
      }
    }
    if (sequence === navigationSequence) {
      const heading = $(`[data-screen="${name}"] h1`);
      if (heading) heading.focus({ preventScroll: true });
    }
  }
  document.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return;
    const anchor = event.target.closest('a[data-route]');
    if (!anchor || anchor.hasAttribute('download') || anchor.target === '_blank') return;
    const url = new URL(anchor.href);
    if (url.origin !== root.origin || !Object.hasOwn(routes, anchor.dataset.route)) return;
    event.preventDefault();
    const next = anchor.dataset.route;
    if (route === next && url.hash) { history.pushState({ route, scrollY: 0 }, '', url); positionPage(url.hash); return; }
    navigate(next, url.hash);
  });
  window.addEventListener('popstate', () => navigate(routeFromLocation(), location.hash, false, history.state?.scrollY));
  setScreen(routeFromLocation());
  if (!history.state) history.replaceState({ route, scrollY: window.scrollY }, '', location.href);
  if (location.hash) requestAnimationFrame(() => positionPage(location.hash));

  const capture = Object.freeze({
    label: 'Capture', sub: 'TUN input', title: 'Capture the request.',
    description: 'The local VpnService exposes a TUN interface. The engine reads packets selected by the app’s routing configuration.',
    noteTitle: 'A local interface.', note: 'Android supplies VpnService and TUN. Tunnel HTTPS implements the packet loop, dispatch and policy around them.',
    boundary: 'Local processing is not a remote VPN tunnel.', file: 'LocalProtectionEngine.kt'
  });
  const normalization = Object.freeze({
    label: 'Normalize', sub: 'Packet checks', title: 'Check the packet boundary.',
    description: 'The packet path validates lengths and headers and applies the supported IPv4 or IPv6 normalization and bounded fragment-handling rules.',
    noteTitle: 'Reject unsafe input.', note: 'Packet normalization is an explicit subsystem. Its supported cases and resource ceilings are part of the design, not a claim of unlimited protocol coverage.',
    boundary: 'Malformed or unsupported input is not assumed safe.', file: 'Ipv6PacketNormalizer.kt'
  });
  const paths = Object.freeze({
    dns: [capture, normalization, {
      label: 'Validate', sub: 'DNS policy', title: 'Treat a DNS answer as input.',
      description: 'DNS parsing, response checks and cache policy sit between the request and the answer used by the local engine.',
      noteTitle: 'More than an endpoint.', note: 'Validation considers DNS message structure and response semantics. The repository also contains cache and resolver-selection logic.',
      boundary: 'DNS validation is not a promise that every domain is safe.', file: 'DnsMessageValidator.kt'
    }, {
      label: 'Resolve', sub: 'Resolver path', title: 'Use the configured resolver path.',
      description: 'Depending on configuration and request type, resolution uses the appropriate protected upstream path. Encrypted resolution does not make the selected DNS provider blind to a query.',
      noteTitle: 'A clear trust boundary.', note: 'DoH provides an HTTPS transport for DNS. External providers still receive the queries sent to them; local handling and DNS64/NAT64 behavior have their own conditions.',
      boundary: 'The selected resolver may see the request and source IP.', file: 'DohResolver.kt'
    }],
    tcp: [capture, normalization, {
      label: 'Relay', sub: 'Local TCP state', title: 'Maintain local transport state.',
      description: 'The bounded userspace relay manages the TUN-side connection and its forwarding state. Upstream transport continues through platform sockets.',
      noteTitle: 'Two sides of the connection.', note: 'The local relay and the Internet-facing Android/Linux TCP socket are different layers. The project does not claim to replace the complete kernel TCP stack.',
      boundary: 'A local relay is not a Linux-equivalent TCP implementation.', file: 'TurboTcpForwarder.kt'
    }, {
      label: 'Adapt', sub: 'TLS metadata', title: 'Adapt the connection, not the content.',
      description: 'For supported TLS traffic, the engine can inspect ClientHello metadata and apply its configured fragmentation strategy before forwarding onward.',
      noteTitle: 'No HTTPS decryption.', note: 'Turbo can guide local strategy selection using context and outcomes. Aegis remains a separate shadow policy, not an active authority over routing.',
      boundary: 'Encrypted application payloads are not decrypted here.', file: 'TurboTcpForwarder.kt'
    }],
    quic: [capture, normalization, {
      label: 'Classify', sub: 'QUIC structure', title: 'Recognize supported QUIC structures.',
      description: 'The QUIC-aware path examines supported packet structures and selected responses. A UDP port number by itself is not treated as proof of a complete QUIC session.',
      noteTitle: 'Structural awareness.', note: 'Classification and selected response handling help inform local behavior. They are not an implementation of the entire QUIC transport protocol.',
      boundary: 'This is not a complete QUIC stack.', file: 'QuicHttp3Guard.kt'
    }, {
      label: 'Forward', sub: 'UDP relay', title: 'Keep forwarding decisions bounded.',
      description: 'UDP traffic uses the local relay and protected upstream sockets. QUIC-related fallback depends on the supported state and configured policy, not a blanket claim that every datagram becomes TCP.',
      noteTitle: 'Protocol-specific limits.', note: 'Resource ceilings, timeouts and cleanup remain relevant even when traffic is classified. Network-dependent behavior needs testing outside this illustration.',
      boundary: 'The explorer does not send traffic or measure your network.', file: 'TurboUdpForwarder.kt'
    }]
  });
  function animateReadout(element) {
    if (reducedMotion.matches) return;
    element.classList.remove('readout-updated');
    void element.offsetWidth;
    element.classList.add('readout-updated');
  }
  function showStep(index) {
    step = Math.max(0, Math.min(3, Number(index) || 0));
    const current = paths[protocol][step];
    $$('.path-stop').forEach((button, i) => {
      const item = paths[protocol][i];
      $('.stop-title', button).textContent = item.label;
      $('small', button).textContent = item.sub;
      button.classList.toggle('is-current', i === step);
      button.classList.toggle('is-complete', i < step);
      button.setAttribute('aria-label', `Step ${i + 1}: ${item.label}`);
      if (i === step) button.setAttribute('aria-current', 'step'); else button.removeAttribute('aria-current');
    });
    $('#step-title').textContent = current.title;
    $('#step-description').textContent = current.description;
    $('#inspector-title').textContent = current.noteTitle;
    $('#inspector-description').textContent = current.note;
    $('#inspector-boundary').textContent = current.boundary;
    $('#inspector-source').href = sourceRoot + current.file;
    $('#path-progress').value = String(step);
    $('#path-progress').setAttribute('aria-valuetext', `Step ${step + 1} of 4: ${current.label}`);
    $('#step-count').textContent = `0${step + 1} / 04`;
    animateReadout($('.path-readout'));
  }
  function stopPlayback() {
    if (playbackTimer) clearInterval(playbackTimer);
    playbackTimer = null;
    const button = $('#play-path');
    if (button) { button.setAttribute('aria-pressed', 'false'); button.textContent = 'Play sequence ▷'; }
  }
  function selectProtocol(name) {
    if (!Object.hasOwn(paths, name)) return;
    stopPlayback();
    protocol = name;
    $$('.protocol-tabs button').forEach(button => {
      const active = button.dataset.protocol === name;
      button.setAttribute('aria-selected', String(active));
      button.tabIndex = active ? 0 : -1;
    });
    $('#protocol-panel').setAttribute('aria-labelledby', 'tab-' + name);
    showStep(0);
  }
  $$('.protocol-tabs button').forEach((button, index, buttons) => {
    button.addEventListener('click', () => selectProtocol(button.dataset.protocol));
    button.addEventListener('keydown', event => {
      let target;
      if (event.key === 'ArrowRight') target = (index + 1) % buttons.length;
      if (event.key === 'ArrowLeft') target = (index + buttons.length - 1) % buttons.length;
      if (event.key === 'Home') target = 0;
      if (event.key === 'End') target = buttons.length - 1;
      if (target === undefined) return;
      event.preventDefault();
      selectProtocol(buttons[target].dataset.protocol);
      buttons[target].focus();
    });
  });
  $$('.path-stop').forEach(button => button.addEventListener('click', () => { stopPlayback(); showStep(button.dataset.step); }));
  $('#path-progress').addEventListener('input', event => { stopPlayback(); showStep(event.target.value); });
  $('#next-step').addEventListener('click', () => { stopPlayback(); showStep((step + 1) % 4); });
  $('#play-path').addEventListener('click', () => {
    if (playbackTimer) { stopPlayback(); return; }
    if (step === 3) showStep(0);
    $('#play-path').textContent = 'Pause sequence Ⅱ';
    $('#play-path').setAttribute('aria-pressed', 'true');
    playbackTimer = setInterval(() => {
      if (document.hidden || route !== 'engineering') { stopPlayback(); return; }
      showStep(step + 1);
      if (step === 3) stopPlayback();
    }, 1800);
  });
  showStep(0);

  const rows = $$('.capability-row');
  const search = $('#capability-search');
  search.maxLength = 120;
  function filterCapabilities() {
    const query = search.value.trim().toLocaleLowerCase();
    let count = 0;
    rows.forEach(row => {
      const matches = (filter === 'all' || row.dataset.scope === filter) && row.textContent.toLocaleLowerCase().includes(query);
      row.hidden = !matches;
      if (matches) count++;
    });
    $('#filter-result').textContent = count ? `${count} subsystem${count === 1 ? '' : 's'}` : 'No matching subsystems. Try another term or choose All.';
  }
  search.addEventListener('input', filterCapabilities);
  $$('.filter-group button').forEach(button => button.addEventListener('click', () => {
    filter = button.dataset.filter;
    $$('.filter-group button').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    filterCapabilities();
  }));
  const layers = Object.freeze({
    input: ['Context, encoded locally.', 'The input layer represents local connection context. This diagram explains the model shape; it does not run the Android model in your browser.'],
    hidden: ['A deliberately small hidden layer.', 'Sixteen hidden units form the model’s intermediate representation. A small model does not remove the need to validate its behavior and failure cases.'],
    output: ['Three predictions. No routing authority.', 'The outputs concern success, latency quality and tail risk. Aegis remains shadow-only: predicted scores are not permission to control live connections.']
  });
  $$('.model-layers button').forEach(button => button.addEventListener('click', () => {
    const item = layers[button.dataset.layer];
    if (!item) return;
    $$('.model-layers button').forEach(other => other.setAttribute('aria-pressed', String(other === button)));
    $('#model-note h3').textContent = item[0];
    $('#model-note p').textContent = item[1];
    animateReadout($('#model-note'));
  }));

  const release = window.TUNNEL_RELEASE && typeof window.TUNNEL_RELEASE === 'object' ? window.TUNNEL_RELEASE : {};
  function officialURL(value) {
    if (typeof value !== 'string') return null;
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && url.hostname === 'github.com' && !url.username && !url.password && url.pathname.startsWith('/Sp2ctr2/Tunnel-HTTPS/releases/') ? url.href : null;
    } catch (_) { return null; }
  }
  const apkURL = officialURL(release.apk_url);
  const releaseURL = officialURL(release.url);
  const expectedHash = typeof release.apk_sha256 === 'string' && /^[a-f0-9]{64}$/i.test(release.apk_sha256) ? release.apk_sha256.toLowerCase() : null;
  if (release.published && releaseURL) {
    $('#release-status').textContent = release.prerelease ? 'Published beta' : 'Published release';
    $('#release-version').textContent = String(release.version || 'Published build').slice(0, 100) + (Number.isSafeInteger(release.apk_size) ? ` · ${(release.apk_size / 1048576).toFixed(1)} MiB APK` : '');
    $('#release-notes').href = releaseURL;
    $('#download-apk').href = apkURL || releaseURL;
    $('#download-apk').textContent = apkURL ? 'Download APK ↓' : 'Open release ↗';
    $('#release-detail').textContent = (release.prerelease ? 'Beta build. ' : '') + 'Review the release notes and known limitations before installing. The download comes directly from the official GitHub repository.';
  }
  $('#apk-file').addEventListener('change', async event => {
    const sequence = ++fileSequence;
    const output = $('#hash-result');
    const file = event.target.files?.[0];
    output.removeAttribute('data-result');
    if (!file) { output.textContent = 'Choose a file to calculate its SHA-256. Maximum 128 MiB.'; return; }
    if (file.size > 128 * 1024 * 1024) { output.dataset.result = 'error'; output.textContent = 'This checker accepts files up to 128 MiB. Use a local checksum tool for larger files.'; return; }
    if (!window.isSecureContext || !crypto.subtle) { output.dataset.result = 'error'; output.textContent = 'Local hashing requires a secure HTTPS page or localhost. Use your operating system’s SHA-256 tool instead.'; return; }
    output.textContent = 'Reading locally and calculating SHA-256…';
    try {
      const buffer = await file.arrayBuffer();
      const digest = await crypto.subtle.digest('SHA-256', buffer);
      if (sequence !== fileSequence) return;
      const hash = Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('');
      if (expectedHash) {
        const matches = hash === expectedHash;
        output.dataset.result = matches ? 'match' : 'mismatch';
        output.textContent = (matches ? 'MATCH — bytes match the published release checksum.' : 'NO MATCH — this file differs from the release currently shown. Do not treat it as that release.') + '\n\nSHA-256\n' + hash;
      } else {
        output.dataset.result = 'calculated';
        output.textContent = 'SHA-256 calculated locally. No verified comparison value is available here; compare this with the exact release’s published checksum.\n\n' + hash;
      }
    } catch (_) {
      if (sequence === fileSequence) { output.dataset.result = 'error'; output.textContent = 'This file could not be read or hashed. Retry with a local file or use an operating-system checksum tool.'; }
    }
  });
  $$('.copy-code').forEach(button => button.addEventListener('click', async () => {
    const code = $('code', button.closest('.code-block'));
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(code.textContent);
      notify('Copied. Nothing was executed.');
    } catch (_) {
      const range = document.createRange();
      range.selectNodeContents(code);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      notify('Text selected. Copy it using your browser or keyboard.');
    }
  }));
  if ('IntersectionObserver' in window) {
    const observed = ['download', 'verification', 'first-connection'].map(id => document.getElementById(id)).filter(Boolean);
    new IntersectionObserver(entries => {
      const entry = entries.find(item => item.isIntersecting);
      if (!entry || route !== 'guide') return;
      $$('.install-sidebar a').forEach(anchor => anchor.classList.toggle('is-current', anchor.hash === '#' + entry.target.id));
    }, { rootMargin: '-90px 0px -60% 0px', threshold: 0 }).observe(observed[0]);
    // Each observed section gets the same small, independent observer to avoid retaining page state.
    observed.slice(1).forEach(section => new IntersectionObserver(entries => {
      if (route !== 'guide' || !entries[0].isIntersecting) return;
      $$('.install-sidebar a').forEach(anchor => anchor.classList.toggle('is-current', anchor.hash === '#' + section.id));
    }, { rootMargin: '-90px 0px -60% 0px', threshold: 0 }).observe(section));
  }
})();
