/* Progressive enhancement only. No remote requests and no URL-driven HTML. */
'use strict';
(() => {
  const root = document.documentElement;
  const ko = root.lang === 'ko';
  const t = (en, kr) => ko ? kr : en;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const dark = matchMedia('(prefers-color-scheme: dark)');
  const toast = document.querySelector('.toast');
  let toastTimer;
  const say = (message) => {
    if (!toast) return;
    clearTimeout(toastTimer);
    toast.textContent = message;
    toast.hidden = false;
    toastTimer = setTimeout(() => { toast.hidden = true; }, 2800);
  };
  const save = (key, value) => { try { localStorage.setItem(key, value); } catch { /* Optional preference. */ } };
  const motionEnabled = () => !reduced.matches && root.dataset.motion !== 'off';
  const animate = (element) => {
    if (!element || !motionEnabled() || typeof element.animate !== 'function') return;
    element.getAnimations().forEach(a => a.cancel());
    element.animate([{ opacity: .3, transform: 'translateY(7px)' }, { opacity: 1, transform: 'translateY(0)' }], {
      duration: 260, easing: 'cubic-bezier(.22,1,.36,1)'
    });
  };
  const themeButtons = [...document.querySelectorAll('[data-theme-toggle]')];
  const motionButtons = [...document.querySelectorAll('[data-motion-toggle]')];
  function syncPreferences() {
    const current = root.dataset.theme || (dark.matches ? 'dark' : 'light');
    for (const button of themeButtons) {
      button.hidden = false;
      button.setAttribute('aria-label', current === 'dark' ? t('Switch to light theme', '밝은 테마로 전환') : t('Switch to dark theme', '어두운 테마로 전환'));
      button.title = button.getAttribute('aria-label');
    }
    for (const button of motionButtons) {
      button.hidden = false;
      button.setAttribute('aria-pressed', String(!motionEnabled()));
      button.setAttribute('aria-label', motionEnabled() ? t('Pause motion', '움직임 정지') : t('Resume motion', '움직임 재개'));
      button.title = reduced.matches ? t('Reduced motion follows your system setting', '시스템의 동작 줄이기 설정을 따릅니다') : button.getAttribute('aria-label');
    }
  }
  for (const button of themeButtons) button.addEventListener('click', () => {
    const next = (root.dataset.theme || (dark.matches ? 'dark' : 'light')) === 'dark' ? 'light' : 'dark';
    const update = () => { root.dataset.theme = next; save('tunnel.theme', next); syncPreferences(); };
    if (document.startViewTransition && motionEnabled() && !document.hidden) {
      try { document.startViewTransition(update).finished.catch(() => {}); } catch { update(); }
    } else update();
  });
  for (const button of motionButtons) button.addEventListener('click', () => {
    if (reduced.matches) {
      root.dataset.motion = 'off';
      say(t('Your system preference keeps motion reduced.', '시스템 설정에 따라 움직임을 줄입니다.'));
    } else {
      root.dataset.motion = root.dataset.motion === 'off' ? 'on' : 'off';
      say(root.dataset.motion === 'off' ? t('Motion paused.', '움직임을 정지했습니다.') : t('Motion resumed.', '움직임을 재개했습니다.'));
    }
    save('tunnel.motion', root.dataset.motion);
    syncPreferences();
  });
  reduced.addEventListener('change', syncPreferences);
  dark.addEventListener('change', syncPreferences);
  syncPreferences();
  document.addEventListener('visibilitychange', () => { root.dataset.suspended = String(document.hidden); });

  // Native dialogs preserve focus return, Escape handling and background inertness.
  const searchDialog = document.getElementById('search-dialog');
  const searchInput = document.getElementById('search-input');
  const searchItems = [...document.querySelectorAll('.search-items li')];
  function openDialog(id) {
    const dialog = document.getElementById(id);
    if (!dialog || typeof dialog.showModal !== 'function') return;
    document.querySelectorAll('dialog[open]').forEach(d => d.close());
    dialog.showModal();
    if (id === 'search-dialog' && searchInput) { searchInput.value = ''; filterSearch(); searchInput.focus(); }
    animate(dialog);
  }
  for (const button of document.querySelectorAll('[data-open]')) {
    if (!('HTMLDialogElement' in window)) continue;
    button.hidden = false;
    button.addEventListener('click', () => openDialog(button.dataset.open));
  }
  for (const button of document.querySelectorAll('[data-close]')) button.addEventListener('click', () => button.closest('dialog').close());
  for (const dialog of document.querySelectorAll('dialog')) {
    dialog.addEventListener('click', event => {
      if (event.target.closest?.('a[href]')) { dialog.close(); return; }
      if (event.target !== dialog) return;
      const r = dialog.getBoundingClientRect();
      if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dialog.close();
    });
  }
  function filterSearch() {
    const query = (searchInput?.value || '').trim().toLocaleLowerCase();
    let matches = 0;
    for (const li of searchItems) {
      li.hidden = !li.textContent.toLocaleLowerCase().includes(query);
      if (!li.hidden) matches++;
    }
    const empty = document.getElementById('search-empty');
    if (empty) empty.hidden = matches > 0;
  }
  searchInput?.addEventListener('input', filterSearch);
  document.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && !event.altKey && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      if (searchDialog?.open) searchDialog.close(); else openDialog('search-dialog');
    }
  });
  searchDialog?.addEventListener('keydown', event => {
    const links = searchItems.filter(li => !li.hidden).map(li => li.querySelector('a'));
    if (!links.length) return;
    const index = links.indexOf(document.activeElement);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const next = event.key === 'ArrowDown' ? (index + 1) % links.length : (index < 0 ? links.length - 1 : (index - 1 + links.length) % links.length);
      links[next].focus();
    } else if (event.key === 'Enter' && document.activeElement === searchInput) {
      event.preventDefault(); links[0].click();
    }
  });

  // Entry animations never hide content before enhancement or require scrolling.
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) if (entry.isIntersecting) {
        entry.target.classList.add('in-view'); observer.unobserve(entry.target);
      }
    }, { threshold: .12 });
    document.querySelectorAll('[data-reveal]').forEach(el => observer.observe(el));
  }

  const previews = {
    dns: t('DNS queries follow your local policy. Encrypted resolution goes to the configured provider.', 'DNS 질의에 로컬 정책을 적용합니다. 암호화 DNS는 설정한 해석기로 전송됩니다.'),
    tls: t('ClientHello metadata guides adaptation. TLS content remains encrypted between the original endpoints.', 'ClientHello 메타데이터로 전략을 조정합니다. TLS 본문은 원래 양 끝점 사이에서 암호화됩니다.'),
    quic: t('Supported QUIC structures inform destination policy. This is not a complete QUIC implementation.', '지원하는 QUIC 구조로 목적지 정책을 판단합니다. 완전한 QUIC 구현은 아닙니다.')
  };
  document.querySelector('.portal-options')?.removeAttribute('hidden');
  for (const button of document.querySelectorAll('[data-preview]')) button.addEventListener('click', () => {
    const summary = document.querySelector('.portal-summary');
    if (!summary || !Object.hasOwn(previews, button.dataset.preview)) return;
    document.querySelectorAll('[data-preview]').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
    summary.textContent = previews[button.dataset.preview];
    animate(summary);
  });

  // Explorer reads its data from the pre-rendered, no-JS documentation.
  const fallback = document.querySelector('.protocol-fallback');
  if (fallback) {
    const model = new Map();
    fallback.querySelectorAll('[data-protocol]').forEach(article => {
      model.set(article.dataset.protocol, {
        title: article.querySelector('h2').textContent,
        note: article.querySelector('.protocol-note').textContent,
        steps: [...article.querySelectorAll('li')].map(li => ({
          title: li.querySelector('h3').textContent,
          subtitle: li.querySelector('small').textContent,
          description: li.querySelector('p').textContent,
          source: li.dataset.source
        }))
      });
    });
    const panel = document.getElementById('explorer-panel');
    const tablist = document.querySelector('[role="tablist"]');
    const tabs = [...document.querySelectorAll('[data-tab]')];
    const stages = [...document.querySelectorAll('[data-stage]')];
    let mode = model.has(location.hash.slice(1)) ? location.hash.slice(1) : 'dns';
    let step = 0;
    function render(announce = false) {
      const data = model.get(mode);
      const current = data.steps[step];
      tabs.forEach(tab => {
        const selected = tab.dataset.tab === mode;
        tab.setAttribute('aria-selected', String(selected));
        tab.tabIndex = selected ? 0 : -1;
      });
      panel.setAttribute('aria-labelledby', `tab-${mode}`);
      document.querySelector('[data-protocol-title]').textContent = `${data.title} / LOCAL PATH`;
      stages.forEach((button, i) => {
        button.querySelector('b').textContent = data.steps[i].title;
        button.querySelector('small').textContent = data.steps[i].subtitle;
        button.setAttribute('aria-label', `${i + 1}. ${data.steps[i].title}`);
        if (i === step) button.setAttribute('aria-current', 'step'); else button.removeAttribute('aria-current');
      });
      document.querySelector('[data-step-title]').textContent = current.title;
      document.querySelector('[data-step-description]').textContent = current.description;
      document.querySelector('[data-step-boundary]').textContent = data.note;
      document.querySelector('[data-step-source]').href = current.source;
      document.querySelector('[data-step-counter]').textContent = `0${step + 1} / 04`;
      document.querySelector('[data-step-caption]').textContent = `${data.title} / ${t('STEP', '단계')} 0${step + 1}`;
      document.querySelectorAll('.step-progress i').forEach((el, i) => el.classList.toggle('done', i <= step));
      const previous = document.querySelector('[data-prev]');
      previous.disabled = step === 0;
      const next = document.querySelector('[data-next]');
      next.firstChild.textContent = step === 3 ? t('Replay ', '처음부터 ') : t('Next step ', '다음 단계 ');
      if (announce) {
        document.querySelector('[data-lab-announcement]').textContent = `${data.title}, ${step + 1}/4. ${current.title}`;
        animate(document.querySelector('[data-inspector-copy]'));
      }
    }
    function select(protocol) {
      if (!model.has(protocol)) return;
      mode = protocol; step = 0;
      try { history.replaceState(null, '', `#${protocol}`); } catch { /* Readable without history permission. */ }
      render(true);
    }
    tabs.forEach(tab => tab.addEventListener('click', () => select(tab.dataset.tab)));
    tablist.addEventListener('keydown', event => {
      const index = tabs.indexOf(document.activeElement);
      if (index < 0 || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      tabs[next].focus(); select(tabs[next].dataset.tab);
    });
    stages.forEach((button, i) => button.addEventListener('click', () => { step = i; render(true); }));
    document.querySelector('[data-prev]').addEventListener('click', () => { step = Math.max(0, step - 1); render(true); });
    document.querySelector('[data-next]').addEventListener('click', () => { step = (step + 1) % 4; render(true); });
    window.addEventListener('hashchange', () => {
      if (model.has(location.hash.slice(1))) { mode = location.hash.slice(1); step = 0; render(true); }
    });
    render();
    panel.hidden = false; tablist.hidden = false; fallback.hidden = true;
  }

  for (const button of document.querySelectorAll('[data-copy]')) {
    button.hidden = false;
    button.addEventListener('click', async () => {
      const source = document.getElementById(button.dataset.copy);
      if (!source) return;
      try {
        await navigator.clipboard.writeText(source.textContent);
        say(t('Command copied.', '명령어를 복사했습니다.'));
      } catch {
        const selection = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(source); selection.removeAllRanges(); selection.addRange(range);
        say(t('Command selected. Use your device’s Copy action.', '명령어를 선택했습니다. 기기의 복사 기능을 사용하세요.'));
      }
    });
  }

  // A checksum is file equality, not an APK signature or safety assessment.
  const verifier = document.querySelector('[data-verifier]');
  if (verifier) {
    verifier.hidden = false;
    const expected = document.getElementById('expected-hash');
    const input = document.getElementById('check-file');
    const zone = document.querySelector('[data-dropzone]');
    const result = document.querySelector('[data-result]');
    const verify = document.querySelector('[data-verify]');
    let file = null;
    let operation = 0;
    function output(message, state, hash) {
      result.hidden = false;
      result.dataset.state = state;
      result.replaceChildren(document.createTextNode(message));
      if (hash) {
        const code = document.createElement('code');
        code.className = 'hash-output'; code.textContent = hash;
        result.appendChild(code);
      }
    }
    function invalidate() {
      operation++;
      verify.disabled = false;
      result.hidden = true;
    }
    function choose(value) {
      invalidate(); file = value;
      document.querySelector('[data-file-name]').textContent = file ? `${file.name} · ${(file.size / 1024).toFixed(1)} KiB` : t('No file selected.', '선택한 파일이 없습니다.');
    }
    input.addEventListener('change', () => choose(input.files[0] || null));
    expected.addEventListener('input', invalidate);
    ['dragenter','dragover'].forEach(name => zone.addEventListener(name, event => { event.preventDefault(); zone.classList.add('dragging'); }));
    ['dragleave','drop'].forEach(name => zone.addEventListener(name, event => { event.preventDefault(); zone.classList.remove('dragging'); }));
    zone.addEventListener('drop', event => {
      const files = event.dataTransfer?.files;
      if (files?.length === 1) choose(files[0]);
      else { choose(null); output(t('Choose one file at a time.', '파일을 하나씩 선택하세요.'), 'error'); }
    });
    verify.addEventListener('click', async () => {
      const digest = expected.value.trim().toLowerCase();
      if (!/^[a-f0-9]{64}$/.test(digest)) {
        output(t('Enter a 64-character SHA-256 hex digest from a trusted source.', '신뢰하는 출처의 64자리 SHA-256 16진수 값을 입력하세요.'), 'error');
        expected.focus(); return;
      }
      if (!file) { output(t('Choose a file first.', '파일을 먼저 선택하세요.'), 'error'); input.focus(); return; }
      if (!file.size || file.size > 64 * 1024 * 1024) { output(t('Choose a non-empty file no larger than 64 MiB.', '0바이트보다 크고 64 MiB 이하인 파일을 선택하세요.'), 'error'); return; }
      if (!globalThis.crypto?.subtle) { output(t('Local hashing requires a secure browser context (HTTPS or localhost).', '로컬 해시 계산에는 HTTPS 또는 localhost 보안 컨텍스트가 필요합니다.'), 'error'); return; }
      const id = ++operation;
      verify.disabled = true;
      output(t('Calculating locally. No upload.', '기기 안에서 계산 중입니다. 업로드하지 않습니다.'), 'pending');
      try {
        const bytes = await file.arrayBuffer();
        if (id !== operation) return;
        const buffer = await crypto.subtle.digest('SHA-256', bytes);
        if (id !== operation) return;
        const hash = Array.from(new Uint8Array(buffer), b => b.toString(16).padStart(2, '0')).join('');
        output(hash === digest ? t('Match. The file matches the checksum you supplied. This does not verify its signature or safety.', '일치합니다. 입력한 체크섬과 파일이 같습니다. 서명이나 안전성을 검증한 것은 아닙니다.') : t('Mismatch. Do not treat this file as the expected artifact.', '불일치합니다. 이 파일을 기대한 배포 파일로 신뢰하지 마세요.'), hash === digest ? 'success' : 'error', hash);
      } catch {
        if (id === operation) output(t('The file could not be read. Choose it again or use a terminal verifier.', '파일을 읽을 수 없습니다. 다시 선택하거나 터미널 검증 도구를 사용하세요.'), 'error');
      } finally {
        if (id === operation) verify.disabled = false;
      }
    });
  }
})();
