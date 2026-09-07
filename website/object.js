/* An original CPU-rendered conduit, not a network measurement. No WebGL dependency. */
(() => {
  'use strict';
  const canvas = document.getElementById('conduit');
  if (!canvas) return;
  const ctx = canvas.getContext('2d', { alpha: true });
  if (!ctx) return;
  const stage = canvas.parentElement;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let width=600,height=610,ratio=1,frame=0,angle=.12,target=.12,tilt=.02,targetTilt=.02;
  let visible=true, pointer=false, start=0;
  const faces=[];
  const norm=a=>{const d=Math.hypot(...a)||1;return a.map(v=>v/d)};
  const dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
  const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
  function center(t) {
    // A continuous arch with unequal legs; the shape echoes the tunnel opening.
    const theta = -0.39 + t * 3.92;
    return [Math.cos(theta)*1.13, Math.sin(theta)*1.35-.35, Math.sin(theta*1.3)*.28];
  }
  function ring(t,r) {
    const c=center(t), a=center(Math.max(0,t-.001)), b=center(Math.min(1,t+.001));
    const tangent=norm(b.map((v,i)=>v-a[i]));
    const side=norm(cross(tangent,[0,0,1]));
    const up=norm(cross(side,tangent));
    return Array.from({length:65},(_,j)=>{
      const v=j/64*Math.PI*2;
      const n=side.map((x,i)=>x*Math.cos(v)+up[i]*Math.sin(v));
      return {p:c.map((x,i)=>x+n[i]*r),n};
    });
  }
  const outer=[],inner=[];
  for(let i=0;i<=144;i++){outer.push(ring(i/144,.39));inner.push(ring(i/144,.267))}
  function surface(rings,invert=false){
    for(let i=0;i<144;i++) for(let j=0;j<64;j++) {
      const pts=[rings[i][j],rings[i+1][j],rings[i+1][j+1],rings[i][j+1]];
      faces.push({p:pts.map(v=>v.p),n:norm(pts.reduce((a,v)=>a.map((x,k)=>x+v.n[k]),[0,0,0])).map(v=>invert?-v:v),inside:invert});
    }
  }
  surface(outer);surface(inner,true);
  for(const i of [0,144]) for(let j=0;j<64;j++) {
    const a=center(i/144),b=center(i===0?.001:.999);
    faces.push({p:[outer[i][j].p,outer[i][j+1].p,inner[i][j+1].p,inner[i][j].p],n:norm(a.map((v,k)=>v-b[k])),rim:true});
  }
  function rotate(p){
    const rz=-.28, ry=angle-.55, rx=tilt+.26;
    let x=p[0]*Math.cos(rz)-p[1]*Math.sin(rz),y=p[0]*Math.sin(rz)+p[1]*Math.cos(rz),z=p[2];
    const xx=x*Math.cos(ry)+z*Math.sin(ry);z=-x*Math.sin(ry)+z*Math.cos(ry);x=xx;
    const yy=y*Math.cos(rx)-z*Math.sin(rx);z=y*Math.sin(rx)+z*Math.cos(rx);return [x,yy,z];
  }
  const light=norm([-1,1.7,2.8]),half=norm([-1,1.7,5.8]);
  function draw(){
    if(width<1||height<1)return;
    ctx.setTransform(ratio,0,0,ratio,0,0);ctx.clearRect(0,0,width,height);
    const scale=Math.min(width/3.64,height/3.55);
    const rendered=[];
    for(const f of faces){
      const n=rotate(f.n);
      if(n[2]<-.03)continue;
      const pts=f.p.map(rotate);
      const depth=pts.reduce((s,p)=>s+p[2],0)/4;
      const diffuse=Math.max(0,dot(n,light));
      const spec=Math.pow(Math.max(0,dot(n,half)),45)*.65;
      const rim=Math.pow(1-Math.abs(n[2]),3)*.18;
      const b=f.rim?[.67,.76,.97]:f.inside?[.013,.04,.27]:[.045,.17,.83];
      const lit=.3+diffuse*.86;
      const color=b.map((v,i)=>Math.min(255,Math.round((v*lit+spec+rim*[.4,.53,1][i])*255)));
      rendered.push({pts,depth,color:`rgb(${color.join(',')})`});
    }
    rendered.sort((a,b)=>a.depth-b.depth);
    for(const f of rendered){
      ctx.beginPath();f.pts.forEach((p,i)=>{const k=5.8/(5.8-p[2]);const x=width*.5+p[0]*scale*k,y=height*.46-p[1]*scale*k;i?ctx.lineTo(x,y):ctx.moveTo(x,y)});ctx.closePath();ctx.fillStyle=f.color;ctx.strokeStyle=f.color;ctx.lineWidth=1.2;ctx.fill();ctx.stroke();
    }
    canvas.dataset.ready='true';stage.classList.add('object-ready');
  }
  function tick(now){
    frame=0;if(!visible||document.hidden)return;
    angle+=(target-angle)*.13;tilt+=(targetTilt-tilt)*.13;draw();
    if(Math.abs(target-angle)>.0008||Math.abs(targetTilt-tilt)>.0008) frame=requestAnimationFrame(tick);
  }
  function request(){if(!frame&&visible&&!document.hidden) frame=requestAnimationFrame(tick)}
  function resize(){const rect=stage.getBoundingClientRect();width=rect.width;height=rect.height;ratio=Math.min(devicePixelRatio||1,1.5);canvas.width=Math.round(width*ratio);canvas.height=Math.round(height*ratio);draw()}
  new ResizeObserver(resize).observe(stage);
  stage.addEventListener('pointermove',e=>{if(reduced.matches||e.pointerType==='touch')return;const r=stage.getBoundingClientRect();target=(e.clientX-r.left)/r.width*.7-.23;targetTilt=((e.clientY-r.top)/r.height-.5)*.25;request()});
  stage.addEventListener('pointerleave',()=>{target=.12;targetTilt=.02;request()});
  document.querySelector('[data-object-rotate]')?.addEventListener('click',()=>{target+=.7;if(reduced.matches){angle=target;draw()}else request()});
  new IntersectionObserver(es=>{visible=es[0].isIntersecting;if(visible)request();else if(frame){cancelAnimationFrame(frame);frame=0}}).observe(stage);
  document.addEventListener('visibilitychange',()=>{if(document.hidden&&frame){cancelAnimationFrame(frame);frame=0}else request()});
  document.addEventListener('tunnel:route',()=>{target=.12;targetTilt=.02;request()});
  reduced.addEventListener('change',()=>{if(reduced.matches){if(frame)cancelAnimationFrame(frame);frame=0;angle=target;tilt=targetTilt;draw()}});
  resize();
})();
