/* Synchronous, self-hosted preference bootstrap. Storage is optional. */
(() => {
  const root = document.documentElement;
  try {
    const theme = localStorage.getItem('tunnel.theme');
    if (theme === 'light' || theme === 'dark') root.dataset.theme = theme;
    root.dataset.motion = localStorage.getItem('tunnel.motion') === 'off' ? 'off' : 'on';
  } catch { root.dataset.motion = 'on'; }
})();
