'use strict';
(() => {
  const html = document.documentElement;
  const ko = html.lang === 'ko';
  const t = (en, kr) => ko ? kr : en;
  const $ = (s, parent = document) => parent.querySelector(s);
  const $$ = (s, parent = document) => Array.from(parent.querySelectorAll(s));
  const root = new URL(html.dataset.root || './', location.href);
  const paths = { home: '', controls: 'controls/', engineering: 'engineering/', privacy: 'privacy/', guide: 'guide/' };
  const labels = { home: t('Product','제품'), controls: t('Features','기능'), engineering: t('Under the hood','기술'), privacy: t('Privacy','개인정보'), guide: t('Get started','시작하기') };
  const prefix = ko ? 'ko/' : '';
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let current = html.dataset.page, revision = 0, transition, fallback;
  const read = key => { try { return localStorage.getItem(key); } catch (_) { return null; } };
  const save = (key, value) => { try { localStorage.setItem(key,value); } catch (_) {} };
  const motion = () => !reduced.matches && html.dataset.motion !== 'off';
  const urlFor = (name, query = '', hash = '') => new URL(prefix + paths[name] + query + hash, root);
  // Absolute URLs are established before any History API update; assets never drift.
  $$('a[href]').forEach(a => { a.href = new URL(a.getAttribute('href'),location.href).href; });
  function nameFor(url) {
    if (url.origin !== root.origin) return null;
    const path = url.pathname.replace(/index\.html$/, '');
    return Object.keys(paths).find(name => path === urlFor(name).pathname) || null;
  }
  function syncNavigation(name) {
    $$('.page-tab').forEach(a => { if(a.dataset.route === name) a.setAttribute('aria-current','page'); else a.removeAttribute('aria-current'); });
    $('#locale-link').href = new URL((ko ? '' : 'ko/') + paths[name] + location.search + location.hash,root).href;
    $('link[rel="canonical"]').href = new URL(prefix + paths[name],root).href;
    document.title = (name === 'home' ? t('Your connection. In your hands.','내 연결은, 내 손안에.') : labels[name]) + ' — Tunnel HTTPS';
  }
  function apply(name, url, restore) {
    current=name; html.dataset.page=name;
    $$('.product-scene').forEach(scene => { scene.hidden=scene.dataset.scene!==name; scene.inert=scene.hidden; });
    syncNavigation(name);
    if(name==='home') selectExample(url.searchParams.get('feature')||'dns',false);
    if(name==='controls') selectChoice(url.searchParams.get('choice')||'dns',false);
    const scene=$('#scene-'+name);
    if (typeof restore === 'number') scene.scrollTop=restore;
    else if (!url.hash) scene.scrollTop=0;
    if(url.hash) {
      let id='';try{id=decodeURIComponent(url.hash.slice(1));}catch(_){}
      const anchor=id?document.getElementById(id):null;
      if(anchor && scene.contains(anchor)) {
        if(name==='engineering') window.dispatchEvent(new Event('hashchange'));
        anchor.scrollIntoView({block:'start',behavior:'instant'});
      }
    }
  }
  async function navigate(name,url,push=true) {
    if (!Object.hasOwn(paths,name)) return;
    const sequence=++revision;
    transition?.skipTransition(); fallback?.cancel();
    // Cancelled updates must never overwrite a later navigation.
    if(push){history.replaceState({...history.state,scroll:$('#scene-'+current).scrollTop},'',location.href);history.pushState({scene:name,scroll:0},'',url);}
    $('#mobile-nav').hidden=true;$('.menu-toggle').setAttribute('aria-expanded','false');
    const update=()=>{if(sequence===revision)apply(name,url,push?undefined:history.state?.scroll);};
    if (document.startViewTransition && motion()) {
      transition=document.startViewTransition(update);
      const thisTransition=transition;
      try { await thisTransition.updateCallbackDone; } catch(_){update();}
      thisTransition.finished.catch(()=>{}).finally(()=>{if(transition===thisTransition)transition=null;});
    } else {
      update();
      if(motion())fallback=$('#scene-'+name).animate([{opacity:0,transform:'translateY(12px)'},{opacity:1,transform:'none'}],{duration:340,easing:'cubic-bezier(.22,1,.36,1)'});
    }
    if(sequence===revision)$('#scene-'+name+' h1')?.focus({preventScroll:true});
  }
  document.addEventListener('click',event=>{
    if(event.defaultPrevented||event.button!==0||event.metaKey||event.ctrlKey||event.altKey||event.shiftKey)return;
    const a=event.target.closest('a[href]');if(!a||a.target==='_blank'||a.hasAttribute('download')||a.matches('[data-topic], [data-capability] a'))return;
    const url=new URL(a.href), name=nameFor(url);if(!name)return;
    event.preventDefault();navigate(name,url);
  });
  window.addEventListener('popstate',()=>{const name=nameFor(new URL(location.href));if(name)navigate(name,new URL(location.href),false);});
  const navLinks=$$('.desktop-nav .page-tab');
  navLinks.forEach((a,i)=>a.addEventListener('keydown',event=>{
    let index;if(event.key==='ArrowRight')index=(i+1)%navLinks.length;if(event.key==='ArrowLeft')index=(i+navLinks.length-1)%navLinks.length;if(event.key==='Home')index=0;if(event.key==='End')index=navLinks.length-1;
    if(index===undefined)return;event.preventDefault();navLinks[index].focus();
  }));
  const toggle=$('#motion-setting');
  function syncMotion(){const off=reduced.matches||read('tunnel.product.motion')==='off';html.dataset.motion=off?'off':'on';toggle.setAttribute('aria-pressed',String(off));toggle.textContent=off?t('Motion reduced','동작 줄임'):t('Reduce motion','동작 줄이기');if(off){transition?.skipTransition();fallback?.cancel();}}
  toggle.addEventListener('click',()=>{save('tunnel.product.motion',html.dataset.motion==='off'?'on':'off');syncMotion();});reduced.addEventListener('change',syncMotion);syncMotion();

  const examples={
    dns:['DNS over HTTPS',t('Every query.\nA little more private.','DNS 질의에도,\n한 겹 더 보호.'),t('Example DNS query','DNS 질의 예시'),'HTTPS','example.org',t('Encrypted to your chosen resolver','선택한 리졸버까지 전송 암호화'),t('Your provider. Your choice.','제공자도 직접 선택.'),t('The resolver receives the query. The transport to it is encrypted.','리졸버는 질의를 수신합니다. 그곳까지의 전송을 암호화합니다.'),t('No developer-operated VPN gateway','개발자 운영 VPN 게이트웨이 없음')],
    filter:[t('Domain filtering','도메인 필터링'),t('Less unwanted.\nMore intentional.','원하지 않는 것은 덜고.\n필요한 연결에 집중.'),t('Illustrative blocklist match','차단 목록 일치 예시'),t('Local rule','로컬 규칙'),'ads.example.org',t('A matching domain can be blocked locally','목록과 일치하는 도메인을 로컬에서 차단'),t('Filter the domain, not the page.','페이지가 아닌 도메인을 기준으로.'),t('Domain rules do not inspect encrypted page content. Not every ad shares a blockable domain.','암호화된 페이지 내용을 검사하지 않습니다. 모든 광고가 별도 차단 가능한 도메인을 쓰는 것은 아닙니다.'),t('HTTPS content is not decrypted','HTTPS 내용을 복호화하지 않습니다')],
    local:[t('On-device learning','기기 내 학습'),t('Your network changes.\nLocal policy adapts.','네트워크가 달라지면,\n로컬 정책도 적응하게.'),t('Connection strategy','연결 전략'),t('Local','로컬'),'Turbo',t('Context and outcomes inform the strategy','맥락과 결과를 연결 전략에 반영'),t('Learning stays on the device.','학습은 기기 안에서.'),t('Turbo selects bounded strategies. The experimental Aegis policy remains shadow-only.','Turbo는 제한된 전략 안에서 선택합니다. 실험적 Aegis 정책은 섀도 모드로 유지합니다.'),t('No cloud model API required','클라우드 모델 API 불필요')]
  };
  function animate(el){if(!motion())return;el.getAnimations().forEach(a=>a.cancel());el.animate([{opacity:.25,transform:'translateY(7px)'},{opacity:1,transform:'none'}],{duration:330,easing:'cubic-bezier(.22,1,.36,1)'});}
  function bindTabs(selector,handler){const tabs=$$(selector);tabs.forEach((button,i)=>{button.addEventListener('click',()=>handler(button));button.addEventListener('keydown',event=>{let next;if(event.key==='ArrowRight'||event.key==='ArrowDown')next=(i+1)%tabs.length;if(event.key==='ArrowLeft'||event.key==='ArrowUp')next=(i+tabs.length-1)%tabs.length;if(event.key==='Home')next=0;if(event.key==='End')next=tabs.length-1;if(next===undefined)return;event.preventDefault();tabs[next].focus();handler(tabs[next]);});});return tabs;}
  const exhibitTabs=bindTabs('[data-exhibit]',button=>selectExample(button.dataset.exhibit));
  function selectExample(name,updateURL=true){if(!Object.hasOwn(examples,name))return;const data=examples[name];exhibitTabs.forEach(b=>{const active=b.dataset.exhibit===name;b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1;});$('#exhibit-content').setAttribute('aria-labelledby','exhibit-'+name);$('#exhibit-content').dataset.example=name;['exhibit-kicker','exhibit-heading','query-label','query-tag','query-value','query-description','exhibit-detail-title','exhibit-detail','exhibit-foot'].forEach((id,i)=>$('#'+id).textContent=data[i]);animate($('#exhibit-content'));if(updateURL && current==='home'){const u=new URL(location.href);if(name==='dns')u.searchParams.delete('feature');else u.searchParams.set('feature',name);history.replaceState(history.state,'',u);}}
  selectExample(new URL(location.href).searchParams.get('feature')||'dns');

  const behavior={
    dns:{category:'DNS over HTTPS',title:t('Change the transport.\nKeep your choice.','전송은 암호화.\n선택은 그대로.'),description:t('DNS over HTTPS encrypts queries on their way to the configured resolver.','DNS over HTTPS는 설정한 리졸버까지 전송하는 질의를 암호화합니다.'),label:t('Encrypted DNS','DNS 암호화'),on:[t('HTTPS to your resolver','리졸버까지 HTTPS로'),t('The selected provider still receives the query. Encryption is not anonymity.','선택한 제공자는 여전히 질의를 수신합니다. 암호화가 익명화를 의미하지는 않습니다.')],off:[t('System resolver path','시스템 리졸버 경로'),t('Resolution uses the configured system path. Its privacy properties depend on the network and platform settings.','설정된 시스템 경로로 질의합니다. 개인정보 보호 특성은 네트워크와 플랫폼 설정에 영향을 받습니다.')],source:'DohResolver.kt'},
    filter:{category:t('Domain filtering','도메인 필터링'),title:t('Keep the useful.\nFilter the unwanted.','필요한 것은 남기고.\n원하지 않는 것은 걸러내고.'),description:t('Apply local domain rules before forwarding supported DNS requests.','지원하는 DNS 요청을 전달하기 전에 로컬 도메인 규칙을 적용합니다.'),label:t('Local blocklist','로컬 차단 목록'),on:[t('Matching domains are blocked','목록과 일치하면 차단'),t('A match in the local list is blocked. Unlisted domains are not assumed malicious or safe.','로컬 목록에 일치하면 차단합니다. 목록에 없다고 악성이거나 안전한 것으로 단정하지 않습니다.')],off:[t('Blocklist not applied','차단 목록 미적용'),t('This illustrative switch disables the blocklist, not DNS response validation.','이 예시 스위치는 차단 목록을 끕니다. DNS 응답 검증 자체를 끄는 것은 아닙니다.')],source:'DomainBlocker.kt'},
    bypass:{category:t('App routing','앱별 경로'),title:t('Not every app\nneeds the same path.','모든 앱이 같은\n경로일 필요는 없으니까.'),description:t('Choose apps to exclude from the local VpnService path.','로컬 VpnService 경로에서 제외할 앱을 선택할 수 있습니다.'),label:t('Exclude a selected app','선택한 앱 제외'),on:[t('Selected app uses its normal connection','선택한 앱은 원래 연결 사용'),t('The excluded app does not receive Tunnel HTTPS local filtering or transport handling.','제외된 앱에는 Tunnel HTTPS의 로컬 필터링과 전송 처리가 적용되지 않습니다.')],off:[t('App stays in the local path','앱을 로컬 경로에 유지'),t('Traffic follows the app’s current routing configuration. Android supplies the underlying VPN interface.','현재 라우팅 설정에 따라 처리합니다. 기반 VPN 인터페이스는 Android가 제공합니다.')],source:'AppBypassManager.kt'}
  };
  let choice='dns';const states={dns:true,filter:true,bypass:true};
  const choiceTabs=bindTabs('[data-choice]',button=>selectChoice(button.dataset.choice));
  function showBehavior(){const item=behavior[choice],on=states[choice];$('#behavior-switch').setAttribute('aria-checked',String(on));$('#behavior-title').textContent=item[on?'on':'off'][0];$('#behavior-description').textContent=item[on?'on':'off'][1];}
  function selectChoice(name,updateURL=true){if(!Object.hasOwn(behavior,name))return;choice=name;const item=behavior[name];choiceTabs.forEach(b=>{const selected=b.dataset.choice===name;b.setAttribute('aria-selected',String(selected));b.tabIndex=selected?0:-1;});$('#choice-panel').setAttribute('aria-labelledby','choice-'+name);$('#choice-category').textContent=item.category;$('#choice-title').textContent=item.title;$('#choice-description').textContent=item.description;$('#setting-label').textContent=item.label;$('#behavior-source').href='https://github.com/Sp2ctr2/Tunnel-HTTPS/blob/main/app/src/main/java/com/tunnelvpn/app/'+item.source;showBehavior();animate($('#choice-panel'));if(updateURL && current==='controls'){const u=new URL(location.href);u.searchParams.set('choice',name);history.replaceState(history.state,'',u);}}
  $('#behavior-switch').addEventListener('click',()=>{states[choice]=!states[choice];showBehavior();animate($('.behavior-result'));});selectChoice(new URL(location.href).searchParams.get('choice')||'dns');
  // Controls are examples; there is no native bridge, network fetch, telemetry or remote state.
  syncNavigation(current);
  if(!history.state)history.replaceState({scene:current,scroll:0},'',location.href);
  html.dataset.scenes='ready';
})();
