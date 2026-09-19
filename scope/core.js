/* Tunnel Scope core. Pure data/layout operations; source is data, never executable. */
(function (root) {
'use strict';
const C = {};
C.escape = s => String(s).replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
C.lines = s => {if (!s) return []; const a=s.split(/\r\n|[\n\r\v\f\x1c-\x1e\x85\u2028\u2029]/); if(a[a.length-1]==='') a.pop(); return a;};
C.number = n => Number(n||0).toLocaleString('en-US');
C.bytes = n => n>=1048576?(n/1048576).toFixed(2)+' MiB':n>=1024?(n/1024).toFixed(1)+' KiB':n+' B';
C.hash = s => {let h=2166136261;for(let i=0;i<s.length;i++)h=Math.imul(h^s.charCodeAt(i),16777619);return h>>>0;};
C.module = p => p.startsWith('app/src/main/')?'app · main':p.startsWith('app/src/test/')?'app · test':p.startsWith('docs/')?'docs':p.startsWith('tools/')?'tools':p.startsWith('website/')?'website':'configuration';
C.palette = {'app · main':'#83c3ae','app · test':'#9896c9','docs':'#bbac88','tools':'#82a9c8','website':'#bf8e9c','configuration':'#8a999f'};
C.languageColors = {'Kotlin':'#9e9bcc','Java':'#bd9b7d','Python':'#8dbfbe','JavaScript':'#c5b685','TypeScript':'#7aacc6','HTML':'#bf9291','CSS':'#959dcb','Markdown':'#90ada3','XML':'#b3ac88','JSON':'#a6b592','YAML':'#b69baf','Shell':'#8dacb9','Rust':'#be9681','Go':'#7dbbbc','C':'#99adc8','C++':'#a38bc0','Text':'#8a999f','Binary':'#626e7d','TOML':'#af95a7'};
C.hexRGB = h => [parseInt(h.slice(1,3),16),parseInt(h.slice(3,5),16),parseInt(h.slice(5,7),16)];
C.color = (f,mode='module') => {if(mode==='language')return C.languageColors[f.language]||'#8a999f';if(mode==='size'){const t=Math.min(1,Math.log2(1+f.bytes)/18),a=[102,149,168],b=[199,160,138];return '#'+a.map((v,i)=>Math.round(v+(b[i]-v)*t).toString(16).padStart(2,'0')).join('');}return C.palette[C.module(f.path)];};
C.tree = files => {
 const map=new Map(),root={path:'',name:'',children:[],parent:null,depth:0};map.set('',root);
 const dir=p=>{if(map.has(p))return map.get(p);const a=p.split('/'),name=a.pop(),parent=dir(a.join('/'));const n={path:p,name,children:[],parent,depth:parent.depth+1};map.set(p,n);parent.children.push(n);return n;};
 files.forEach((f,i)=>{f.id=i;const a=f.path.split('/'),name=a.pop(),parent=dir(a.join('/'));const n={path:f.path,name,file:f,parent,depth:parent.depth+1};map.set(f.path,n);parent.children.push(n);f.node=n;});
 function sum(n){if(n.file){n.lines=n.file.lines;n.bytes=n.file.bytes;n.count=1;n.symbols=n.file.symbols.length;return;} n.children.sort((a,b)=>(!!a.file)-(!!b.file)||a.name.localeCompare(b.name));n.lines=n.bytes=n.count=n.symbols=0;for(const c of n.children){sum(c);n.lines+=c.lines;n.bytes+=c.bytes;n.count+=c.count;n.symbols+=c.symbols;}}sum(root);return {root,map};
};
/* Bruls-style squarified treemap. Rectangle area tracks the selected weight. */
C.squarify = (items, rect, weight) => {
 if(!items.length)return [];const total=items.reduce((s,n)=>s+Math.max(1e-9,weight(n)),0),area=rect.w*rect.d;
 const todo=items.map((n,i)=>({n,i,a:area*Math.max(1e-9,weight(n))/total})).sort((a,b)=>b.a-a.a||a.i-b.i);
 const out=[];let x=rect.x,z=rect.z,w=rect.w,d=rect.d,row=[];
 const worst=(r,side)=>{if(!r.length)return Infinity;const s=r.reduce((v,t)=>v+t.a,0),mx=Math.max(...r.map(t=>t.a)),mn=Math.min(...r.map(t=>t.a));return Math.max(side*side*mx/(s*s),(s*s)/(side*side*mn));};
 function lay(){const sum=row.reduce((s,t)=>s+t.a,0);if(w>=d){const rw=sum/d;let zz=z;for(const t of row){const dd=t.a/rw;out.push({item:t.n,x,z:zz,w:rw,d:dd});zz+=dd;}x+=rw;w=Math.max(0,w-rw);}else{const rd=sum/w;let xx=x;for(const t of row){const ww=t.a/rd;out.push({item:t.n,x:xx,z,w:ww,d:rd});xx+=ww;}z+=rd;d=Math.max(0,d-rd);}row=[];}
 while(todo.length){const p=todo[0],side=Math.max(1e-9,Math.min(w,d));if(!row.length||worst(row.concat(p),side)<=worst(row,side)){row.push(todo.shift());}else lay();}if(row.length)lay();return out;
};
C.layout = (tree,metric='lines',width=144,depth=104) => {
 const files=[],dirs=[],pages=[];
 function weight(n){if(n.file)return metric==='equal'?1:Math.max(metric==='bytes'?128:8,n.file[metric]);return n.children.reduce((s,c)=>s+weight(c),0);}
 function visit(n,r,level){n.rect=r;if(n.file){const g=Math.min(.14,r.w*.025,r.d*.025);const q={x:r.x+g,z:r.z+g,w:Math.max(.01,r.w-2*g),d:Math.max(.01,r.d-2*g)};n.file.rect=q;files.push(n.file);const inner={x:q.x+.06,z:q.z+.06,w:Math.max(.005,q.w-.12),d:Math.max(.005,q.d-.12)};const sec=n.file.sections.length?n.file.sections:[{start:0,end:0,nonblank:0,name:n.file.binary?'Binary':'Empty'}];for(const v of C.squarify(sec,inner,s=>Math.max(1,s.end-s.start+1))){let gap=Math.min(.045,v.w*.025,v.d*.025);const pg={file:n.file,section:v.item,id:pages.length,x:v.x+gap,z:v.z+gap,w:Math.max(.001,v.w-2*gap),d:Math.max(.001,v.d-2*gap)};pages.push(pg);}return;}
  dirs.push(n);if(n.children.length===1){visit(n.children[0],r,level);return;}const pad=level===0?.65:Math.min(.26,r.w*.012,r.d*.012);const inner={x:r.x+pad,z:r.z+pad,w:Math.max(.001,r.w-2*pad),d:Math.max(.001,r.d-2*pad)};
  for(const a of C.squarify(n.children,inner,weight)){visit(a.item,{x:a.x,z:a.z,w:a.w,d:a.d},level+1);}
 }visit(tree.root,{x:-width/2,z:-depth/2,w:width,d:depth},0);return {files,dirs,pages,width,depth};
};
const keywords=new Set(('package import class interface object fun val var private public protected internal override open abstract final data sealed enum companion const return if else when while for do in is as null true false try catch finally throw throws new this super void static synchronized extends implements switch case break continue default boolean int long float double byte char short def async await from with lambda yield pass except raise None True False function let export typeof instanceof async await const constructor struct fn pub use mod impl match mut self crate type where select go defer range not and or').split(' '));
C.tokens = (line,state={comment:false,triple:false}) => {
 const out=[];let i=0;const emit=(v,t='')=>{if(v)out.push({text:v,type:t});};
 while(i<line.length){let rest=line.slice(i),m;
  if(state.comment){let e=rest.indexOf('*/');if(e<0){emit(rest,'comment');break;}emit(rest.slice(0,e+2),'comment');i+=e+2;state.comment=false;continue;}
  if(state.triple){let e=rest.indexOf('"""');if(e<0){emit(rest,'string');break;}emit(rest.slice(0,e+3),'string');i+=e+3;state.triple=false;continue;}
  if(rest.startsWith('/*')){state.comment=true;emit('/*','comment');i+=2;continue;}
  if(rest.startsWith('"""')){emit('"""','string');i+=3;state.triple=true;continue;}
  if(rest.startsWith('//')||(/^\s*#/.test(line)&&i===0)){emit(rest,'comment');break;}
  if((m=/^(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)/.exec(rest))){emit(m[0],'string');i+=m[0].length;continue;}
  if((m=/^@[\w.]+/.exec(rest))){emit(m[0],'annotation');i+=m[0].length;continue;}
  if((m=/^\b(?:0[xX][\da-fA-F_]+|\d[\d_.]*(?:[eE][+-]?\d+)?[fFlL]?)\b/.exec(rest))){emit(m[0],'number');i+=m[0].length;continue;}
  if((m=/^[A-Za-z_$][\w$]*/.exec(rest))){const s=m[0],type=keywords.has(s)?'keyword':/^[A-Z]/.test(s)?'type':/^\s*\(/.test(rest.slice(s.length))?'fn':'';emit(s,type);i+=s.length;continue;}
  if((m=/^[^A-Za-z_$\d@'"`/]+/.exec(rest))){emit(m[0]);i+=m[0].length;}else{emit(rest[0]);i++;}
 }return out;
};
C.tokenHTML = tokens => tokens.map(t=>t.type?'<span class="tok-'+t.type+'">'+C.escape(t.text)+'</span>':C.escape(t.text)).join('');
C.highlight = (line,query) => {if(!query)return C.escape(line);const text=String(line),at=text.toLowerCase().indexOf(query.toLowerCase());return at<0?C.escape(text):C.escape(text.slice(0,at))+'<mark>'+C.escape(text.slice(at,at+query.length))+'</mark>'+C.escape(text.slice(at+query.length));};
C.search = (files,query,mode='files',limit=250) => {
 const q=query.toLowerCase().trim(),matches=[];let count=0;
 for(const f of files){if(mode==='files'){const p=f.path.toLowerCase(),name=p.split('/').pop();let score=q?(name===q?0:name.startsWith(q)?1:name.includes(q)?2:p.includes(q)?3:-1):4;if(score>=0){count++;matches.push({f,line:0,title:f.path.split('/').pop(),score});}}
 else if(mode==='symbols'){for(const s of f.symbols){if(!q||s.name.toLowerCase().includes(q)){count++;if(matches.length<limit)matches.push({f,line:s.line,title:s.name,kind:s.kind,score:0});}}}
 else if(q&&!f.binary){const lines=f._lines||(f._lines=C.lines(f.source));for(let i=0;i<lines.length;i++){if(lines[i].toLowerCase().includes(q)){count++;if(matches.length<limit)matches.push({f,line:i+1,title:f.path.split('/').pop(),snippet:lines[i],score:0});}}}}
 if(mode==='files')matches.sort((a,b)=>a.score-b.score||a.f.path.localeCompare(b.f.path));return {results:matches.slice(0,limit),count};
};
C.indexLocal = (path,source,bytes) => {
 const ex=path.split('.').pop().toLowerCase(),langs={kt:'Kotlin',kts:'Kotlin',java:'Java',py:'Python',js:'JavaScript',mjs:'JavaScript',ts:'TypeScript',tsx:'TypeScript',jsx:'JavaScript',html:'HTML',htm:'HTML',css:'CSS',scss:'CSS',md:'Markdown',xml:'XML',svg:'XML',json:'JSON',yml:'YAML',yaml:'YAML',sh:'Shell',bat:'Shell',rs:'Rust',go:'Go',c:'C',h:'C',cpp:'C++',hpp:'C++',toml:'TOML'};
 const f={path,source,bytes,sha:'',language:source===null?'Binary':langs[ex]||'Text',binary:source===null,lines:0,nonblank:0,symbols:[],sections:[]};
 if(source===null)return f;const ls=C.lines(source);f.lines=ls.length;f.nonblank=ls.filter(l=>l.trim()).length;let masked=source.replace(/"""[\s\S]*?"""|\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`/g,m=>m.replace(/[^\r\n]/g,' '));
 C.lines(masked).forEach((l,i)=>{let m;if(['Kotlin','Java','JavaScript','TypeScript','C','C++','Rust','Go'].includes(f.language))m=/\b(class|interface|object|fun|function|struct|fn|func)\s+(?:<[^>]*>\s*)?(?:[\w<>?]+\.)?([A-Za-z_]\w*)/.exec(l);else if(f.language==='Python')m=/^\s*(?:async\s+)?(def|class)\s+([A-Za-z_]\w*)/.exec(l);if(m)f.symbols.push({name:m[2],kind:m[1],line:i+1});});
 for(let i=0;i<ls.length;i+=32){const end=Math.min(i+32,ls.length),near=f.symbols.find(s=>s.line>i&&s.line<=end),prior=f.symbols.filter(s=>s.line<=i).pop();f.sections.push({start:i+1,end,nonblank:ls.slice(i,end).filter(l=>l.trim()).length,name:(near||prior||{}).name||''});}return f;
};
C.metrics = files => ({files:files.length,textFiles:files.filter(f=>!f.binary).length,bytes:files.reduce((s,f)=>s+f.bytes,0),lines:files.reduce((s,f)=>s+f.lines,0),nonblank:files.reduce((s,f)=>s+f.nonblank,0),symbols:files.reduce((s,f)=>s+f.symbols.length,0),pages:files.reduce((s,f)=>s+f.sections.length,0)});
if(typeof module==='object'&&module.exports)module.exports=C;else root.ScopeCore=C;
})(typeof window==='object'?window:globalThis);
