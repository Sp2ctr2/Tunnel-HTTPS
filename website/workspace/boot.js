/* Apply a saved preference before the first paint; storage may be unavailable. */
(() => {
  try {
    const theme = localStorage.getItem('tunnel.workspace.theme');
    if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
  } catch (_) {}
  const initialURL = location.href;
  // Relative links remain valid for static/no-JS pages. Enhanced navigation freezes
  // their resolved destinations so pushState cannot nest subsequent route paths.
  document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('a[data-route]').forEach(anchor => {
      anchor.href = new URL(anchor.getAttribute('href'), initialURL).href;
    });
  }, { once: true });
})();
