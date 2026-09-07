/* Apply a saved preference before the first paint; storage may be unavailable. */
(() => {
  try {
    const theme = localStorage.getItem('tunnel.workspace.theme');
    if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
  } catch (_) {}
})();
