(() => {
  'use strict';
  const root = document.documentElement;
  const modes = new Set(['auto', 'light', 'dark']);
  const accents = new Set(['blue', 'violet', 'emerald', 'slate']);
  const keys = { mode: 'litedata.color-mode', accent: 'litedata.accent', sidebar: 'litedata.sidebar' };
  const system = window.matchMedia('(prefers-color-scheme: dark)');
  const desktop = window.matchMedia('(min-width: 1024px)');
  const read = (key, fallback) => { try { return localStorage.getItem(key) || fallback; } catch { return fallback; } };
  const write = (key, value) => { try { localStorage.setItem(key, value); } catch { /* The current page still updates when storage is unavailable. */ } };
  let mode = read(keys.mode, 'auto');
  let accent = read(keys.accent, 'blue');
  let collapsed = read(keys.sidebar, 'expanded') === 'collapsed';

  function apply() {
    if (!modes.has(mode)) mode = 'auto';
    if (!accents.has(accent)) accent = 'blue';
    root.dataset.bsTheme = mode === 'auto' ? (system.matches ? 'dark' : 'light') : mode;
    root.dataset.themePreference = mode;
    root.dataset.accent = accent;
    root.dataset.sidebar = collapsed ? 'collapsed' : 'expanded';
    document.querySelectorAll('[data-theme-mode]').forEach(select => { select.value = mode; });
    document.querySelectorAll('[data-theme-accent]').forEach(select => { select.value = accent; });
    document.querySelectorAll('[data-sidebar-toggle]').forEach(button => {
      button.setAttribute('aria-expanded', String(!collapsed));
      button.setAttribute('aria-label', collapsed ? '展开侧栏' : '收起侧栏');
      button.title = collapsed ? '展开侧栏' : '收起侧栏';
    });
  }
  apply();
  system.addEventListener('change', () => { if (mode === 'auto') apply(); });
  window.addEventListener('storage', event => {
    if (!Object.values(keys).includes(event.key) && event.key !== null) return;
    mode = read(keys.mode, 'auto'); accent = read(keys.accent, 'blue');
    collapsed = read(keys.sidebar, 'expanded') === 'collapsed'; apply();
  });
  document.addEventListener('DOMContentLoaded', () => {
    apply();
    document.querySelectorAll('[data-theme-mode]').forEach(select => select.addEventListener('change', () => {
      mode = select.value; write(keys.mode, mode); apply();
    }));
    document.querySelectorAll('[data-theme-accent]').forEach(select => select.addEventListener('change', () => {
      accent = select.value; write(keys.accent, accent); apply();
    }));
    document.querySelectorAll('[data-sidebar-toggle]').forEach(button => button.addEventListener('click', () => {
      collapsed = !collapsed; write(keys.sidebar, collapsed ? 'collapsed' : 'expanded'); apply();
    }));
    const drawer = document.getElementById('mobile-navigation');
    document.querySelectorAll('[data-mobile-nav-open]').forEach(button => button.addEventListener('click', () => {
      if (drawer && !drawer.open) drawer.showModal();
    }));
    document.querySelectorAll('[data-mobile-nav-close]').forEach(button => button.addEventListener('click', () => drawer?.close()));
    drawer?.addEventListener('click', event => {
      const bounds = drawer.getBoundingClientRect();
      if (event.target === drawer && (event.clientX < bounds.left || event.clientX > bounds.right ||
          event.clientY < bounds.top || event.clientY > bounds.bottom)) drawer.close();
      if (event.target.closest('a')) drawer.close();
    });
    desktop.addEventListener('change', () => { if (desktop.matches && drawer?.open) drawer.close(); });
  });
})();
