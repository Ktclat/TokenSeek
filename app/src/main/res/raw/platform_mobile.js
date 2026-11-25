// Size only the official site's overflowing tables and dialogs. Their controls remain site-owned.
(() => {
  'use strict';
  const paths = ['/', '/sign_in', '/sign_up', '/api_keys', '/top_up', '/transactions'];
  const allowed = () => window === window.top && location.origin === 'https://platform.deepseek.com' &&
    paths.includes(location.pathname);
  if (!allowed() || window.__tokenSeekMobilePlatformV1) return;
  window.__tokenSeekMobilePlatformV1 = true;

  const tableMark = 'data-tokenseek-wide-table';
  const dialogMark = 'data-tokenseek-large-dialog';
  const dialogs = 'dialog[open],[role="dialog"],[role="alertdialog"],[aria-modal="true"],[class*="modal-content" i],[class*="dialog-content" i]';
  const css = document.createElement('style');
  css.textContent = `
    [${tableMark}] {
      display: block !important;
      box-sizing: border-box !important;
      min-inline-size: 0 !important;
      max-inline-size: min(100%, calc(100vw - 16px)) !important;
      overflow-x: auto !important;
    }
    [${dialogMark}] {
      box-sizing: border-box !important;
      min-inline-size: 0 !important;
      min-block-size: 0 !important;
      max-inline-size: min(100%, calc(100vw - 16px)) !important;
      max-block-size: calc(100vh - 16px) !important;
      max-block-size: calc(100dvh - 16px) !important;
      overflow: auto !important;
    }`;

  let pending = false;
  let viewport = null;
  let original = null;
  let adjusted = null;
  let added = false;
  const marked = new Set();
  function clear() {
    for (const el of marked) {
      el.removeAttribute(tableMark);
      el.removeAttribute(dialogMark);
    }
    marked.clear();
    css.remove();
    // The SPA may have changed its own viewport since we last touched it.
    if (viewport && viewport.getAttribute('content') === adjusted) {
      if (added) viewport.remove();
      else if (original === null) viewport.removeAttribute('content');
      else viewport.setAttribute('content', original);
    }
    viewport = null;
    added = false;
  }

  function ensureViewport() {
    let meta = document.head.querySelector('meta[name="viewport" i]');
    if (meta && viewport === meta && adjusted === meta.getAttribute('content')) return;
    added = !meta;
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'viewport';
    }
    viewport = meta;
    original = meta.getAttribute('content');
    // Keep keyboard, safe-area and density directives. Permit user zoom and a device-width layout.
    if (original && /(?:^|[,;])\s*width\s*=\s*device-width(?:\s*[,;]|\s*$)/i.test(original) &&
        !/(?:^|[,;])\s*(?:maximum-scale\s*=|user-scalable\s*=\s*(?:no|0)(?:\s*[,;]|\s*$))/i.test(original)) {
      adjusted = original;
      return;
    }
    const parts = (original || '').split(/[,;]/).map(s => s.trim()).filter(Boolean);
    const output = [];
    let width = false;
    for (const part of parts) {
      if (/^width\s*=/i.test(part)) { width = true; output.push('width=device-width'); }
      else if (/^maximum-scale\s*=|^user-scalable\s*=\s*(?:no|0)$/i.test(part)) continue;
      else output.push(part);
    }
    if (!width) output.unshift('width=device-width');
    if (!parts.length) output.push('initial-scale=1');
    adjusted = output.join(', ');
    if (adjusted !== original) meta.setAttribute('content', adjusted);
    if (added) document.head.append(meta);
  }

  function alreadyScrollable(el, width) {
    for (let p = el; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      const overflow = getComputedStyle(p).overflowX;
      if ((overflow === 'auto' || overflow === 'scroll') && p.clientWidth > 0 &&
          p.clientWidth <= width && p.scrollWidth > p.clientWidth) return true;
    }
    return false;
  }

  function update() {
    pending = false;
    if (!document.head || !document.body) return;
    const narrow = Math.min(screen.width || Infinity, innerWidth) <= 700;
    if (!allowed() || !narrow) { clear(); return; }
    ensureViewport();
    if (!css.isConnected) document.head.append(css);
    const width = document.documentElement.clientWidth;
    const height = document.documentElement.clientHeight;
    for (const el of marked) if (!el.isConnected) marked.delete(el);
    for (const el of document.querySelectorAll('table,[role="table"],' + dialogs)) {
      if (marked.has(el)) continue;
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) continue;
      if (el.matches('table,[role="table"]')) {
        // Respect a working site-provided horizontally scrollable wrapper.
        if (r.width <= width - Math.max(0, r.left) + 1 || alreadyScrollable(el, width)) continue;
        el.setAttribute(tableMark, '');
      } else {
        if (r.width <= width && r.height <= height) continue;
        el.setAttribute(dialogMark, '');
      }
      marked.add(el);
    }
  }

  function schedule() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(update);
  }
  const observer = new MutationObserver(schedule);
  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true,
    attributeFilter: ['class', 'style', 'open', 'hidden', 'aria-hidden', 'aria-modal', 'role', 'content'] });
  addEventListener('resize', schedule);
  addEventListener('pageshow', schedule);
  addEventListener('popstate', schedule);
  window.navigation?.addEventListener('currententrychange', schedule);
  update();
})();
