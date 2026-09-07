(() => {
  let theme='light';
  try{const saved=localStorage.getItem('tunnel.conduit.theme');if(saved==='dark'||saved==='light')theme=saved}catch(_){}
  document.documentElement.dataset.theme=theme;
  document.documentElement.dataset.enhanced='true';
})();
