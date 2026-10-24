(() => {
  'use strict';
  const root = document.documentElement;
  const button = document.getElementById('theme-toggle');
  const preference = matchMedia('(prefers-color-scheme: dark)');
  const isDark = () => root.dataset.theme ? root.dataset.theme === 'dark' : preference.matches;
  function syncTheme() {
    button.setAttribute('aria-pressed', String(isDark()));
    document.querySelector('meta[name="theme-color"]').content = isDark() ? '#121c2b' : '#edf4f7';
    window.dispatchEvent(new Event('aerove-theme'));
  }
  button.addEventListener('click', () => {
    root.dataset.theme = isDark() ? 'light' : 'dark';
    try { localStorage.setItem('aerove.theme', root.dataset.theme); } catch (_) { /* Storage is optional. */ }
    syncTheme();
  });
  preference.addEventListener('change', syncTheme);
  syncTheme();
})();
