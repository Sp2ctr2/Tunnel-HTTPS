/* Read only the locally saved preference before first paint. */
try { const theme=localStorage.getItem('tunnel.product.theme'); if(theme==='dark'||theme==='light') document.documentElement.dataset.theme=theme; } catch (_) {}
