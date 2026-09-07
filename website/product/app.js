'use strict';
(() => {
  const html=document.documentElement;
  const ko=html.lang==='ko';
  const t=(en,kr)=>ko?kr:en;
  const $=(s,p=document)=>p.querySelector(s);
  const $$=(s,p=document)=>Array.from(p.querySelectorAll(s));
  const reduced=matchMedia('(prefers-reduced-motion: reduce)');
  let toastTimer;
  function toast(text){const el=$('#toast');clearTimeout(toastTimer);el.textContent=text;el.hidden=false;toastTimer=setTimeout(()=>el.hidden=true,3500);}
  function reveal(el,cls='panel-reveal'){if(reduced.matches)return;el.classList.remove(cls);requestAnimationFrame(()=>{el.classList.add(cls);});}
  const theme=$('.theme-toggle');
  function syncTheme(){const dark=html.dataset.theme==='dark';theme.setAttribute('aria-pressed',String(dark));theme.setAttribute('aria-label',dark?t('Switch to light theme','밝은 테마로 전환'):t('Switch to dark theme','어두운 테마로 전환'));$('meta[name="theme-color"]').content=dark?'#171717':'#ffffff';}
  theme.addEventListener('click',()=>{html.dataset.theme=html.dataset.theme==='dark'?'light':'dark';try{localStorage.setItem('tunnel.product.theme',html.dataset.theme);}catch(_){}syncTheme();});syncTheme();
  const menu=$('.menu-toggle'),nav=$('#mobile-nav');
  function closeMenu(focus=false){nav.hidden=true;menu.setAttribute('aria-expanded','false');if(focus)menu.focus();}
  closeMenu();menu.addEventListener('click',()=>{const open=nav.hidden;nav.hidden=!open;menu.setAttribute('aria-expanded',String(open));});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!nav.hidden)closeMenu(true);});
  document.addEventListener('pointerdown',e=>{if(!nav.hidden&&!e.target.closest('.site-header'))closeMenu();});
  nav.addEventListener('click',e=>{if(e.target.closest('a'))closeMenu();});
  matchMedia('(min-width:761px)').addEventListener('change',e=>{if(e.matches)closeMenu();});
  // Normal anchors handle navigation, modifier clicks and history. CSS supplies native cross-document transitions.
  window.addEventListener('pageshow',()=>{closeMenu();syncTheme();});

  const image=$('#app-image');
  if(image){
    const tabs=$$('[data-shot]');const desc=$('#shot-description');const dialog=$('#image-dialog');let current='connection';let previousFocus=null;let shotSequence=0;
    const notes={connection:t('The connection screen, before you switch on.','연결을 시작하기 전의 메인 화면.'),activity:t('Local diagnostics. No traffic or measurements are simulated.','로컬 진단 화면입니다. 트래픽이나 측정값을 가상으로 만들지 않았습니다.'),settings:t('DNS encryption, domain filtering and app exclusions.','DNS 암호화, 도메인 필터링과 앱 제외 설정입니다.')};
    async function selectShot(name,updateURL=true){
      const item=tabs.find(b=>b.dataset.shot===name);if(!item)return;
      current=name;tabs.forEach(b=>{const selected=b===item;b.setAttribute('aria-selected',String(selected));b.tabIndex=selected?0:-1;});
      const number=++shotSequence;const loading=new Image();loading.src=item.dataset.src;
      try{await loading.decode();}catch(_){if(number===shotSequence)desc.textContent=t('Preview unavailable. Select the screen again to retry.','미리보기를 불러오지 못했습니다. 화면을 다시 선택해 재시도하세요.');return;}
      if(number!==shotSequence)return;image.src=item.dataset.src;image.alt=t('Tunnel HTTPS app: ','Tunnel HTTPS 앱: ')+item.dataset.label;
      $('#product-screen').setAttribute('aria-labelledby',item.id);desc.textContent=notes[name];reveal(image,'screenshot-changing');
      if(updateURL){const url=new URL(location.href);if(name==='connection')url.searchParams.delete('screen');else url.searchParams.set('screen',name);history.replaceState(history.state,'',url);}
    }
    tabs.forEach((tab,i)=>{tab.addEventListener('click',()=>selectShot(tab.dataset.shot));tab.addEventListener('keydown',e=>{let next;if(e.key==='ArrowRight')next=(i+1)%tabs.length;if(e.key==='ArrowLeft')next=(i+tabs.length-1)%tabs.length;if(e.key==='Home')next=0;if(e.key==='End')next=tabs.length-1;if(next===undefined)return;e.preventDefault();selectShot(tabs[next].dataset.shot);tabs[next].focus();});});
    selectShot(new URL(location.href).searchParams.get('screen')||'connection',false);
    $('[data-zoom]').addEventListener('click',()=>{previousFocus=document.activeElement;$('#dialog-image').src=image.src;$('#dialog-image').alt=image.alt;dialog.showModal();});
    $('[data-close]').addEventListener('click',()=>dialog.close());
    dialog.addEventListener('click',e=>{if(e.target===dialog){const r=dialog.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)dialog.close();}});
    dialog.addEventListener('close',()=>previousFocus?.focus());
  }
  const topics=$$('[data-topic]');
  if(topics.length){
    const panels=$$('[data-panel]');
    function selectTopic(id){const tab=topics.find(e=>e.dataset.topic===id)||topics[0];topics.forEach(e=>{const active=e===tab;e.setAttribute('aria-selected',String(active));e.tabIndex=active?0:-1;});panels.forEach(p=>{p.hidden=p.dataset.panel!==tab.dataset.topic;});const active=panels.find(p=>!p.hidden);reveal(active);}
    function topicFromHash(){let id;try{id=decodeURIComponent(location.hash.slice(1));}catch(_){id='packets';}selectTopic(id||'packets');}
    topics.forEach((tab,i)=>{tab.addEventListener('click',e=>{e.preventDefault();history.pushState(history.state,'',tab.hash);selectTopic(tab.dataset.topic);});tab.addEventListener('keydown',e=>{let n;if(e.key==='ArrowDown'||e.key==='ArrowRight')n=(i+1)%topics.length;if(e.key==='ArrowUp'||e.key==='ArrowLeft')n=(i+topics.length-1)%topics.length;if(e.key==='Home')n=0;if(e.key==='End')n=topics.length-1;if(n===undefined)return;e.preventDefault();history.replaceState(history.state,'',topics[n].hash);selectTopic(topics[n].dataset.topic);topics[n].focus();});});
    $$('[data-capability] a').forEach(a=>a.addEventListener('click',e=>{e.preventDefault();history.pushState(history.state,'',a.hash);selectTopic(a.hash.slice(1));$('.engineering-layout').scrollIntoView({block:'start',behavior:reduced.matches?'instant':'smooth'});}));
    window.addEventListener('hashchange',topicFromHash);window.addEventListener('popstate',topicFromHash);topicFromHash();
    const search=$('#capability-search');
    search.addEventListener('input',()=>{const term=search.value.toLocaleLowerCase().trim();let count=0;$$('[data-capability]').forEach(row=>{row.hidden=!row.textContent.toLocaleLowerCase().includes(term);if(!row.hidden)count++;});$('#no-results').hidden=count!==0;const url=new URL(location.href);if(term)url.searchParams.set('q',search.value);else url.searchParams.delete('q');history.replaceState(history.state,'',url);});
    search.value=new URL(location.href).searchParams.get('q')||'';if(search.value)search.dispatchEvent(new Event('input'));
  }
  $$('[data-copy]').forEach(button=>button.addEventListener('click',async()=>{
    const code=document.getElementById(button.dataset.copy);if(!code)return;
    button.disabled=true;const original=button.textContent;button.textContent=t('Copying…','복사 중…');let timeout;
    try{
      if(!navigator.clipboard?.writeText)throw new Error('Clipboard unavailable');
      await Promise.race([navigator.clipboard.writeText(code.textContent),new Promise((_,reject)=>timeout=setTimeout(()=>reject(new Error('Clipboard timed out')),1400))]);
      toast(t('Commands copied. Nothing was executed.','명령을 복사했습니다. 실행하지 않았습니다.'));
    }catch(_){const range=document.createRange();range.selectNodeContents(code);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);toast(t('Text selected. Copy it with your keyboard or browser menu.','텍스트를 선택했습니다. 키보드나 브라우저 메뉴로 복사하세요.'));}
    finally{clearTimeout(timeout);button.disabled=false;button.textContent=original;}
  }));
  const file=$('#apk-file');
  if(file){file.disabled=false;$('#expected-hash').disabled=false;let sequence=0,hash='';const expected=$('#expected-hash'),result=$('#hash-result');
    function compare(){if(!hash)return;const input=expected.value.trim().toLowerCase();if(input&&!/^[0-9a-f]{64}$/.test(input)){result.dataset.state='error';result.textContent=t('The comparison value must be 64 hexadecimal characters. Paste the SHA-256 from the exact release.','비교값은 16진수 64자여야 합니다. 정확한 릴리스의 SHA-256을 붙여넣으세요.');return;}const match=input===hash;result.dataset.state=input?(match?'match':'mismatch'):'calculated';result.textContent=(input?(match?t('Matches the checksum you entered.','입력한 체크섬과 일치합니다.'):t('Does not match the checksum you entered. Check the release version and file.','입력한 체크섬과 일치하지 않습니다. 릴리스 버전과 파일을 확인하세요.')):t('Calculated locally. Compare this with the release checksum.','로컬에서 계산했습니다. 릴리스 체크섬과 비교하세요.'))+'\n\nSHA-256\n'+hash;}
    expected.addEventListener('input',compare);
    file.addEventListener('change',async()=>{const id=++sequence;hash='';result.removeAttribute('data-state');const f=file.files[0];if(!f){result.textContent=t('Choose a file to calculate its checksum.','체크섬을 계산할 파일을 선택하세요.');return;}if(f.size>128*1024*1024){result.dataset.state='error';result.textContent=t('Choose a file smaller than 128 MiB, or use a local checksum tool.','128 MiB 이하의 파일을 선택하거나 로컬 체크섬 도구를 사용하세요.');return;}if(!crypto.subtle){result.dataset.state='error';result.textContent=t('Open this page over HTTPS or use a local SHA-256 tool.','HTTPS로 페이지를 열거나 로컬 SHA-256 도구를 사용하세요.');return;}result.textContent=t('Calculating locally…','로컬에서 계산 중…');try{const digest=await crypto.subtle.digest('SHA-256',await f.arrayBuffer());if(id!==sequence)return;hash=Array.from(new Uint8Array(digest),n=>n.toString(16).padStart(2,'0')).join('');compare();}catch(_){if(id!==sequence)return;result.dataset.state='error';result.textContent=t('The file could not be read. Choose it again or use a local checksum tool.','파일을 읽지 못했습니다. 다시 선택하거나 로컬 체크섬 도구를 사용하세요.');}});
  }
  html.dataset.enhanced='true';
})();
