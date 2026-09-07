/* Original conduit geometry. Native GPU shading with a Canvas fallback; no 3D library. */
(() => {
  'use strict';
  const canvas = document.getElementById('conduit');
  if (!canvas) return;
  let gl;
  try { gl=canvas.getContext('webgl',{alpha:true,antialias:true,premultipliedAlpha:false,powerPreference:'low-power'}); } catch (_) {}
  const ctx=gl?null:canvas.getContext('2d',{alpha:true});
  if(!gl&&!ctx)return;
  const stage = canvas.parentElement;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let width=600,height=610,ratio=1,frame=0,angle=.12,target=.12,tilt=.02,targetTilt=.02;
  let visible=true,gpu=null,lost=false;
  const faces=[];
  const norm=a=>{const d=Math.hypot(...a)||1;return a.map(v=>v/d)};
  const dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
  const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
  function center(t) {
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
      faces.push({p:pts.map(v=>v.p),normals:pts.map(v=>v.n.map(x=>invert?-x:x)),n:norm(pts.reduce((a,v)=>a.map((x,k)=>x+v.n[k]),[0,0,0])).map(v=>invert?-v:v),inside:invert});
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
  function initGPU(){
    if(!gl)return;
    const vs=`attribute vec3 position;attribute vec3 normal;attribute vec3 material;
      uniform mat3 rotation;uniform vec2 scale;varying vec3 N;varying vec3 P;varying vec3 M;
      void main(){P=rotation*position;N=rotation*normal;M=material;
        float w=5.8-P.z;gl_Position=vec4(P.x*scale.x*5.8,P.y*scale.y*5.8+.08*w,1.01005*w-.201005,w);}`;
    const fs=`precision highp float;varying vec3 N;varying vec3 P;varying vec3 M;
      void main(){vec3 n=normalize(N);vec3 v=normalize(vec3(0.,0.,5.8)-P);
        vec3 l=normalize(vec3(-1.,1.7,2.8));vec3 h=normalize(l+v);
        float d=.16+.92*max(dot(n,l),0.);float spec=pow(max(dot(n,h),0.),85.)*.5;
        float edge=pow(1.-max(dot(n,v),0.),4.)*.085;
        vec3 c=M*d+vec3(.88,.93,1.)*spec+vec3(.19,.3,.85)*edge;
        gl_FragColor=vec4(pow(max(c,vec3(0.)),vec3(.454545)),1.);}`;
    function shader(type,source){const sh=gl.createShader(type);gl.shaderSource(sh,source);gl.compileShader(sh);if(!gl.getShaderParameter(sh,gl.COMPILE_STATUS)){gl.deleteShader(sh);throw new Error('shader')}return sh}
    const vertex=shader(gl.VERTEX_SHADER,vs),fragment=shader(gl.FRAGMENT_SHADER,fs),program=gl.createProgram();
    gl.attachShader(program,vertex);gl.attachShader(program,fragment);gl.linkProgram(program);gl.deleteShader(vertex);gl.deleteShader(fragment);
    if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error('program');
    const data=[];
    for(const face of faces){const material=face.rim?[.4,.51,.8]:face.inside?[.002,.012,.13]:[.008,.045,.74];
      for(const i of [0,1,2,0,2,3])data.push(...face.p[i],...(face.normals?face.normals[i]:face.n),...material)}
    gl.useProgram(program);const buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(data),gl.STATIC_DRAW);
    ['position','normal','material'].forEach((name,i)=>{const at=gl.getAttribLocation(program,name);gl.enableVertexAttribArray(at);gl.vertexAttribPointer(at,3,gl.FLOAT,false,36,i*12)});
    gl.enable(gl.DEPTH_TEST);gl.depthFunc(gl.LEQUAL);gl.clearColor(0,0,0,0);
    gpu={program,count:data.length/9,rotation:gl.getUniformLocation(program,'rotation'),scale:gl.getUniformLocation(program,'scale')};
  }
  try{initGPU()}catch(_){gl=null;canvas.hidden=true;document.querySelector('[data-object-rotate]')?.setAttribute('hidden','');return}
  canvas.addEventListener('webglcontextlost',e=>{e.preventDefault();lost=true;stage.classList.remove('object-ready');if(frame)cancelAnimationFrame(frame);frame=0});
  canvas.addEventListener('webglcontextrestored',()=>{try{lost=false;initGPU();resize()}catch(_){lost=true;stage.classList.remove('object-ready')}});
  function draw(){
    if(width<1||height<1||lost)return;
    if(gpu){
      const scale=Math.min(width/3.64,height/3.55);
      gl.viewport(0,0,canvas.width,canvas.height);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);gl.useProgram(gpu.program);
      gl.uniformMatrix3fv(gpu.rotation,false,new Float32Array([...rotate([1,0,0]),...rotate([0,1,0]),...rotate([0,0,1])]));
      gl.uniform2f(gpu.scale,scale*2/width,scale*2/height);gl.drawArrays(gl.TRIANGLES,0,gpu.count);
      canvas.dataset.ready='true';canvas.dataset.renderer='webgl';stage.classList.add('object-ready');return;
    }
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
    canvas.dataset.ready='true';canvas.dataset.renderer='canvas';stage.classList.add('object-ready');
  }
  function tick(){
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
