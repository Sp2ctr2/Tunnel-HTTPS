/* Conduit interaction layer. All scenes are authored HTML; no fetched HTML or user HTML. */
(() => {
  'use strict';
  const html=document.documentElement, ko=html.lang==='ko';
  const t=(en,kr)=>ko?kr:en;
  const $=(s,r=document)=>r.querySelector(s), $$=(s,r=document)=>Array.from(r.querySelectorAll(s));
  const root=new URL(html.dataset.root,location.origin);
  const routes=Object.freeze({home:'',engineering:'engineering/',guide:'guide/',privacy:'privacy/'});
  const titles={home:t('Product','제품'),engineering:t('Inside the engine','엔진 내부'),guide:t('Get Tunnel HTTPS','시작하기'),privacy:t('Privacy & boundaries','보호 범위')};
  const reduced=matchMedia('(prefers-reduced-motion: reduce)');
  const sourceRoot='https://github.com/Sp2ctr2/Tunnel-HTTPS/blob/main/app/src/main/java/com/tunnelvpn/app/';
  let current=document.body.dataset.page, navSequence=0, animations=[], playTimer=null, toastTimer=null;
  const storage={get(k){try{return localStorage.getItem(k)}catch(_){return null}},set(k,v){try{localStorage.setItem(k,v)}catch(_){}}};
  function notify(message){clearTimeout(toastTimer);const box=$('#toast');box.textContent=message;box.hidden=false;toastTimer=setTimeout(()=>box.hidden=true,3500)}
  function theme(value){html.dataset.theme=value;storage.set('tunnel.conduit.theme',value);$('meta[name="theme-color"]').content=value==='dark'?'#10141b':'#f7f8fa';$('.theme-button').setAttribute('aria-label',value==='dark'?t('Switch to light theme','밝은 테마로 전환'):t('Switch to dark theme','어두운 테마로 전환'))}
  theme(html.dataset.theme==='dark'?'dark':'light');
  $('.theme-button').addEventListener('click',()=>theme(html.dataset.theme==='light'?'dark':'light'));
  const menu=$('#mobile-nav'),menuButton=$('.menu-button');
  function closeMenu(focus=false){menu.hidden=true;menuButton.setAttribute('aria-expanded','false');menuButton.setAttribute('aria-label',t('Open navigation','메뉴 열기'));if(focus)menuButton.focus()}
  menuButton.addEventListener('click',()=>{const open=menu.hidden;menu.hidden=!open;menuButton.setAttribute('aria-expanded',String(open));menuButton.setAttribute('aria-label',open?t('Close navigation','메뉴 닫기'):t('Open navigation','메뉴 열기'))});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!menu.hidden)closeMenu(true)});
  document.addEventListener('pointerdown',e=>{if(!menu.hidden&&!e.target.closest('.site-header'))closeMenu()});
  matchMedia('(min-width:641px)').addEventListener('change',e=>{if(e.matches)closeMenu()});
  function nameAtLocation(){const path=location.pathname.replace(root.pathname,'').replace(/index\.html$/,'').replace(/\/$/,'');return Object.keys(routes).find(k=>routes[k].replace(/\/$/,'')===path)||document.body.dataset.page}
  function show(name){
    current=name;
    $$('.view').forEach(el=>{const active=el.dataset.view===name;el.hidden=!active;el.inert=!active;el.classList.remove('entering')});
    $$('a[data-route]').forEach(el=>el.dataset.route===name?el.setAttribute('aria-current','page'):el.removeAttribute('aria-current'));
    document.body.dataset.page=name;document.title=titles[name]+' — Tunnel HTTPS';
    const languageBase=ko?'/Tunnel-HTTPS/':'/Tunnel-HTTPS/ko/';$('#language-link').href=languageBase+routes[name]+(name==='engineering'?location.search:'');
    $('link[rel=canonical]').href=new URL(routes[name],root).href;
    $('#route-announcement').textContent=titles[name];document.dispatchEvent(new Event('tunnel:route'));
    if(name==='engineering')readPathURL();
  }
  function position(url,y){
    if(url.hash){let id;try{id=decodeURIComponent(url.hash.slice(1))}catch(_){id=''}const target=id&&document.getElementById(id);if(target&&!target.closest('[hidden]')){if(target.tagName==='DETAILS')target.open=true;target.scrollIntoView({block:'start',behavior:'instant'});return}}
    window.scrollTo({top:Number.isFinite(y)?y:0,behavior:'instant'});
  }
  async function navigate(name,url,push=true,y=0){
    if(!Object.hasOwn(routes,name))return;
    const seq=++navSequence;stopPlay();closeMenu();
    for(const animation of animations)animation.cancel();animations=[];
    const curtain=$('#route-curtain');curtain.style.transform='scaleY(0)';
    if(push){history.replaceState({...history.state,scrollY:window.scrollY},'',location.href);history.pushState({route:name,scrollY:y},'',url)}
    const update=()=>{show(name);position(url,y);$(`[data-view="${name}"] h1`)?.focus({preventScroll:true})};
    if(reduced.matches||current===name){update();html.dataset.transition='idle';return}
    $('#curtain-name').textContent=titles[name];curtain.style.transformOrigin='bottom';
    try{
      const cover=curtain.animate([{transform:'scaleY(0)'},{transform:'scaleY(1)'}],{duration:250,easing:'cubic-bezier(.65,0,.35,1)',fill:'forwards'});animations.push(cover);await cover.finished;
      if(seq!==navSequence)return;
      curtain.style.transform='scaleY(1)';cover.cancel();update();curtain.style.transformOrigin='top';
      const reveal=curtain.animate([{transform:'scaleY(1)'},{transform:'scaleY(0)'}],{duration:410,easing:'cubic-bezier(.22,.7,.1,1)',fill:'forwards'});animations=[reveal];
      $(`[data-view="${name}"]`).classList.add('entering');await reveal.finished;
    }catch(_){if(seq===navSequence)update()}
    finally{if(seq===navSequence){curtain.style.transform='scaleY(0)';animations.forEach(a=>a.cancel());animations=[];html.dataset.transition='idle'}}
  }
  document.addEventListener('click',e=>{
    if(e.defaultPrevented||e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;
    const a=e.target.closest('a[data-route]');if(!a||a.target==='_blank'||a.hasAttribute('download'))return;
    const url=new URL(a.href);if(url.origin!==location.origin)return;
    e.preventDefault();html.dataset.transition='running';navigate(a.dataset.route,url,true);
  });
  if('scrollRestoration' in history)history.scrollRestoration='manual';
  window.addEventListener('popstate',()=>navigate(nameAtLocation(),new URL(location.href),false,history.state?.scrollY||0));
  reduced.addEventListener('change',()=>{if(reduced.matches){animations.forEach(a=>a.cancel());stopPlay()}});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stopPlay()});

  const capture={label:t('Capture','수신'),title:t('Start at the device.','기기에서 시작합니다.'),description:t('Android provides the VpnService and TUN interface. Tunnel HTTPS reads packets selected by the local routing configuration.','Android의 VpnService와 TUN 인터페이스를 통해 라우팅 설정에 포함된 패킷을 읽습니다.'),boundary:t('Android supplies the interface; this repository supplies the local packet loop.','인터페이스는 Android가, 로컬 패킷 루프는 이 저장소가 구현합니다.'),file:'LocalProtectionEngine.kt'};
  const normalization={label:t('Normalize','정규화'),title:t('Check before forwarding.','전달하기 전에 검증합니다.'),description:t('Lengths, headers and supported fragment behavior are checked at the packet boundary. Unsupported or malformed input is not assumed safe.','패킷 경계에서 길이, 헤더, 지원되는 조각 처리 조건을 검증합니다. 지원하지 않거나 잘못된 입력을 안전하다고 가정하지 않습니다.'),boundary:t('IPv4 and IPv6 handling have explicit limits. This is not unlimited protocol coverage.','IPv4와 IPv6 처리에는 명시적인 제한이 있습니다. 모든 프로토콜을 무제한 지원하지 않습니다.'),file:'Ipv6PacketNormalizer.kt'};
  const paths={
    dns:[capture,normalization,{label:t('Validate','검증'),title:t('An answer is still untrusted input.','응답도 검증할 입력입니다.'),description:t('The DNS validator checks response structure and semantics before an answer is used. Cache policy and resolver selection are separate parts of the path.','DNS 검증기는 응답을 사용하기 전에 구조와 의미를 검사합니다. 캐시 정책과 리졸버 선택은 별도로 구현됩니다.'),boundary:t('DNS validation does not prove that every domain is safe.','DNS 응답 검증이 모든 도메인의 안전성을 보장하지는 않습니다.'),file:'DnsMessageValidator.kt'},{label:t('Resolve','해석'),title:t('A deliberate resolver path.','설정에 맞는 리졸버 경로.'),description:t('Encrypted DNS uses the configured DoH path. Selected providers still receive the queries sent to them. DNS64/NAT64 behavior depends on the underlying network.','암호화 DNS는 설정된 DoH 경로를 사용합니다. 선택한 제공자는 전송된 질의를 받습니다. DNS64/NAT64 동작은 네트워크 조건에 따라 달라집니다.'),boundary:t('Encrypted transport is not anonymity from the DNS provider.','암호화 전송이 DNS 제공자로부터의 익명성을 의미하지는 않습니다.'),file:'DohResolver.kt'}],
    tcp:[capture,normalization,{label:t('Relay','릴레이'),title:t('Two sides of one connection.','연결의 두 경계를 다룹니다.'),description:t('The userspace relay maintains local TUN-side TCP state and bounded buffers. Internet-facing connections still use Android/Linux sockets.','사용자 공간 릴레이가 TUN 쪽 TCP 상태와 제한된 버퍼를 관리합니다. 인터넷 쪽 연결은 Android/Linux 소켓을 사용합니다.'),boundary:t('A bounded local relay, not a replacement for the full kernel TCP stack.','완전한 커널 TCP 스택 대체가 아닌 제한된 로컬 릴레이입니다.'),file:'TurboTcpForwarder.kt'},{label:t('Adapt','적응'),title:t('Change the strategy. Not the payload.','본문이 아닌 연결 전략을 조정합니다.'),description:t('For supported TLS traffic, the relay inspects ClientHello metadata and can apply a fragmentation strategy. Turbo can guide strategy selection from local context.','지원되는 TLS 트래픽의 ClientHello 메타데이터를 확인하고 파편화 전략을 적용합니다. Turbo는 로컬 문맥을 바탕으로 전략 선택을 보조합니다.'),boundary:t('No HTTPS payload decryption. Aegis remains shadow-only.','HTTPS 본문을 복호화하지 않습니다. Aegis는 shadow 모드를 유지합니다.'),file:'TurboTcpForwarder.kt'}],
    quic:[capture,normalization,{label:t('Classify','분류'),title:t('Recognize the structure.','패킷 구조를 식별합니다.'),description:t('The guard examines supported QUIC packet structures and selected responses. A UDP port alone is not proof of a valid QUIC exchange.','지원되는 QUIC 패킷 구조와 일부 응답을 검사합니다. UDP 포트 번호만으로 유효한 QUIC 교환이라고 판단하지 않습니다.'),boundary:t('Structural classification is not a complete QUIC implementation.','구조 분류는 완전한 QUIC 구현과 다릅니다.'),file:'QuicHttp3Guard.kt'},{label:t('Forward','전달'),title:t('Keep the relay bounded.','릴레이의 한도를 유지합니다.'),description:t('UDP uses protected upstream sockets with lifecycle cleanup and resource limits. Fallback behavior depends on the supported state and configured policy.','UDP는 보호된 업스트림 소켓, 생명주기 정리, 자원 제한을 사용합니다. 폴백 동작은 지원 상태와 설정된 정책에 따릅니다.'),boundary:t('This explanation does not send traffic or test your connection.','이 설명 화면은 트래픽을 전송하거나 연결을 측정하지 않습니다.'),file:'TurboUdpForwarder.kt'}],
    learning:[{label:t('Context','문맥'),title:t('Observe local conditions.','로컬 조건을 관측합니다.'),description:t('Turbo builds a local context for choosing among supported connection strategies. It does not need a remote prediction service.','Turbo는 지원하는 연결 전략을 선택하기 위한 로컬 문맥을 구성합니다. 원격 예측 서비스가 필요하지 않습니다.'),boundary:t('Local adaptation is not a guarantee of better performance on every network.','로컬 적응이 모든 네트워크에서의 성능 향상을 보장하지는 않습니다.'),file:'TurboAiEngine.kt'},{label:t('Select','선택'),title:t('Choose within the constraints.','제약 안에서 선택합니다.'),description:t('The contextual learner balances candidate strategies and observed outcomes. Safety constraints and fallbacks remain separate from a prediction.','문맥 학습기가 후보 전략과 관측 결과를 활용합니다. 안전 제약과 폴백은 예측과 별도로 유지됩니다.'),boundary:t('A prediction is not permission to ignore safety boundaries.','예측 결과가 안전 경계를 무시할 권한은 아닙니다.'),file:'TurboAiEngine.kt'},{label:t('Observe','관측'),title:t('Keep the experimental model in shadow.','실험 모델은 shadow 모드에 둡니다.'),description:t('Aegis runs an experimental local neural policy. Its predictions can be observed, but it is not given active routing authority.','Aegis는 실험적인 로컬 신경망 정책을 실행합니다. 예측을 관측할 수 있지만 실제 라우팅 권한은 부여하지 않습니다.'),boundary:t('Shadow-only means no active control over live routes.','Shadow 모드는 실제 경로를 능동 제어하지 않는다는 뜻입니다.'),file:'AegisLocal2Engine.kt'},{label:t('Update','갱신'),title:t('Learn from the outcome.','결과에서 학습합니다.'),description:t('Observed outcomes feed the local learner. The model and its evaluation can be inspected in the repository instead of being hidden behind an AI label.','관측한 결과를 로컬 학습기에 반영합니다. AI라는 이름 뒤에 감추지 않고 저장소에서 모델과 평가 구현을 확인할 수 있습니다.'),boundary:t('Inspect the implementation and test evidence before relying on a claim.','기능 설명에 의존하기 전에 구현과 테스트 근거를 확인하세요.'),file:'AegisLocal2Neural.kt'}]
  };
  let protocol='dns',step=0;
  function syncPathURL(){if(current!=='engineering')return;const url=new URL(location.href);url.searchParams.set('path',protocol);url.searchParams.set('step',String(step+1));history.replaceState({...history.state},'',url);$('#language-link').search=url.search}
  function showStep(index,sync=true){
    step=Math.max(0,Math.min(3,Number(index)||0));const data=paths[protocol][step];
    $$('.path-step').forEach((b,i)=>{b.classList.toggle('is-complete',i<step);if(i===step)b.setAttribute('aria-current','step');else b.removeAttribute('aria-current');$('.step-name',b).textContent=paths[protocol][i].label;b.setAttribute('aria-label',t('Step ','단계 ')+(i+1)+': '+paths[protocol][i].label)});
    $('#step-position').textContent=`0${step+1} / 04`;$('#step-title').textContent=data.title;$('#step-description').textContent=data.description;$('#step-boundary').textContent=data.boundary;$('#step-source').href=sourceRoot+data.file;$('#step-source span').textContent=data.file;$('#path-progress').value=String(step);$('#path-progress').setAttribute('aria-valuetext',t('Step ','단계 ')+(step+1)+': '+data.label);
    if(!reduced.matches){const el=$('.path-copy');el.getAnimations().forEach(a=>a.cancel());el.animate([{opacity:.35,transform:'translateY(5px)'},{opacity:1,transform:'translateY(0)'}],{duration:240,easing:'ease-out'})}
    if(sync)syncPathURL();
  }
  function selectPath(name,sync=true){if(!Object.hasOwn(paths,name))name='dns';stopPlay();protocol=name;$$('[data-protocol]').forEach(b=>{const active=b.dataset.protocol===name;b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1});$('#path-panel').setAttribute('aria-labelledby','tab-'+name);showStep(0,sync)}
  function readPathURL(){const url=new URL(location.href),name=url.searchParams.get('path'),value=url.searchParams.get('step');selectPath(Object.hasOwn(paths,name)?name:'dns',false);showStep(/^\d$/.test(value||'')?Number(value)-1:0,false)}
  function stopPlay(){clearInterval(playTimer);playTimer=null;const b=$('#play-path');if(b){b.setAttribute('aria-pressed','false');b.firstChild.nodeValue=t('Play path ','경로 재생 ')}}
  $$('[data-protocol]').forEach((b,i,buttons)=>{b.addEventListener('click',()=>selectPath(b.dataset.protocol));b.addEventListener('keydown',e=>{let n;if(e.key==='ArrowRight')n=(i+1)%buttons.length;else if(e.key==='ArrowLeft')n=(i+buttons.length-1)%buttons.length;else if(e.key==='Home')n=0;else if(e.key==='End')n=buttons.length-1;if(n!==undefined){e.preventDefault();selectPath(buttons[n].dataset.protocol);buttons[n].focus()}})});
  $$('[data-step]').forEach(b=>b.addEventListener('click',()=>{stopPlay();showStep(b.dataset.step)}));
  $('#path-progress').addEventListener('input',e=>{stopPlay();showStep(e.target.value)});
  $('#next-step').addEventListener('click',()=>{stopPlay();showStep((step+1)%4)});
  $('#play-path').addEventListener('click',()=>{if(playTimer){stopPlay();return}if(step===3)showStep(0);const b=$('#play-path');b.setAttribute('aria-pressed','true');b.firstChild.nodeValue=t('Pause path ','일시정지 ');playTimer=setInterval(()=>{showStep(step+1);if(step===3)stopPlay()},1000)});

  let fileSeq=0,calculated='';
  function compareHash(){
    const result=$('#hash-result'),expected=$('#expected-hash').value.trim().toLowerCase();
    if(expected&&!/^[a-f0-9]{64}$/.test(expected)){result.dataset.result='error';result.textContent=t('Enter exactly 64 hexadecimal characters from the release, or leave the comparison field empty.','릴리스에 게시된 16진수 64자를 입력하거나 비교값을 비워두세요.');return}
    if(!calculated){result.removeAttribute('data-result');result.textContent=t('Choose a local file to compare.','비교할 로컬 파일을 선택하세요.');return}
    result.dataset.result=expected?(calculated===expected?'match':'mismatch'):'calculated';
    const message=expected?(calculated===expected?t('Checksum matches the comparison value. This is not a signature or malware check.','체크섬이 비교값과 일치합니다. 서명 검사나 악성코드 검사가 아닙니다.'):t('The checksums do not match. Check the exact release and do not treat this file as that build.','체크섬이 일치하지 않습니다. 릴리스 버전을 확인하고 이 파일을 해당 빌드로 간주하지 마세요.')):t('Calculated locally. Compare with the SHA-256 on the exact release.','로컬에서 계산했습니다. 해당 릴리스의 SHA-256과 비교하세요.');
    result.textContent=message+'\n\nSHA-256\n'+calculated;
  }
  $('#expected-hash').addEventListener('input',compareHash);
  $('#apk-file').addEventListener('change',async e=>{
    const seq=++fileSeq,file=e.target.files?.[0],out=$('#hash-result');calculated='';out.removeAttribute('data-result');
    if(!file){out.textContent=t('Choose a local file.','로컬 파일을 선택하세요.');return}
    if(file.size>128*1024*1024){out.dataset.result='error';out.textContent=t('The limit is 128 MiB. Use an operating-system checksum tool for larger files.','최대 크기는 128 MiB입니다. 더 큰 파일은 운영체제의 체크섬 도구를 사용하세요.');return}
    if(!window.isSecureContext||!crypto.subtle){out.dataset.result='error';out.textContent=t('Open the HTTPS site to use local hashing.','로컬 해시 계산을 사용하려면 HTTPS 사이트에서 여세요.');return}
    out.textContent=t('Calculating locally…','로컬에서 계산 중…');
    try{const buffer=await file.arrayBuffer(),hash=await crypto.subtle.digest('SHA-256',buffer);if(seq!==fileSeq)return;calculated=Array.from(new Uint8Array(hash),x=>x.toString(16).padStart(2,'0')).join('');compareHash()}catch(_){if(seq===fileSeq){out.dataset.result='error';out.textContent=t('The file could not be read. Select a local file and try again.','파일을 읽을 수 없습니다. 로컬 파일을 선택하고 다시 시도하세요.')}}
  });
  $$('.copy-code').forEach(b=>b.addEventListener('click',async()=>{
    const code=$('code',b.closest('.code-block'));let timer;notify(t('Copying…','복사 중…'));
    try{
      if(!navigator.clipboard?.writeText)throw new Error('unavailable');
      await Promise.race([navigator.clipboard.writeText(code.textContent),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('timeout')),1200)})]);notify(t('Copied. Nothing was executed.','복사했습니다. 명령을 실행하지 않았습니다.'));
    }catch(_){const range=document.createRange();range.selectNodeContents(code);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);notify(t('Text selected. Use your browser’s Copy command.','텍스트를 선택했습니다. 브라우저의 복사 기능을 사용하세요.'))}finally{clearTimeout(timer)}
  }));
  show(nameAtLocation());html.dataset.transition='idle';
  if(!history.state)history.replaceState({route:current,scrollY:0},'',location.href);
  if(location.hash)position(new URL(location.href),0);
  html.dataset.ready='true';
  if(!reduced.matches)$(`[data-view="${current}"]`).classList.add('entering');
})();
