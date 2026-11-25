// Present the currently rendered page of the official key directory. Never query the service.
(() => {
  'use strict';
  const inDirectory = () => window === window.top &&
    location.origin === 'https://platform.deepseek.com' && location.pathname === '/api_keys';
  if (!inDirectory() || window.__tokenSeekKeyDirectoryV1) return;

  const tableSelector = 'table,[role="table"]';
  const rowSelector = 'tr,[role="row"]';
  const buttonSelector = 'button,[role="button"],a[href]';
  const blockers = [
    'dialog[open]', '[role="dialog"]', '[role="alertdialog"]', '[aria-modal="true"]',
    'input:not([type="hidden"])', 'textarea', 'select', '[contenteditable]:not([contenteditable="false"])',
    'iframe', '[role="alert"]', 'output', '[role="progressbar"]', 'progress', '[aria-busy="true"]',
    '[class*="modal" i]', '[class*="overlay" i]', '[class*="backdrop" i]', '[class*="mask-" i]',
    'a[href="/sign_in"]', 'a[href^="/sign_in?"]', '[class*="skeleton" i]',  '[class*="loading" i]', '[class*="virtual-list" i]',
  ].join(',');
  const headers = {
    name: /^(?:name|key name|api key name|名称|名称\/备注|密钥名称|api\s*key\s*名称|名字)$/i,
    masked: /^(?:key|api\s*key|secret key|api secret key|密钥|秘钥|api\s*[密秘]钥)$/i,
    created: /^(?:created(?: at| on| date)?|creation (?:date|time)|创建(?:日期|时间)?)$/i,
    lastUsed: /^(?:last used(?: at| on)?|last use(?:d)? (?:date|time)|最后使用(?:日期|时间)?|上次使用(?:日期|时间)?|最近使用(?:日期|时间)?)$/i,
    actions: /^(?:actions?|操作)?$/i,
  };
  const actions = {
    create: /^(?:\+\s*)?(?:create (?:a |an )?(?:new )?(?:api |secret )?key|创建(?:新的?|一个)?\s*(?:api\s*(?:key|密钥)|[密秘]钥))$/i,
    previous: /^(?:previous(?: page)?|prev(?:ious)?|go to (?:the )?previous page|上一页|前一页)$/i,
    next: /^(?:next(?: page)?|go to (?:the )?next page|下一页|后一页)$/i,
  };
  const empty = /^(?:暂无(?:数据|密钥|\s*api\s*(?:key[s]?|密钥))|尚未创建(?:任何)?\s*(?:api\s*key|密钥)|no data|no (?:api |secret )?keys(?: yet| found)?|you (?:do not|don't) have any api keys(?: yet)?)[。.!！]?$/i;
  const maskedKey = /^(?:(?:sk-[a-z\d_-]{0,8})?(?:\*{3,}|[•●·]{3,}|\.{3,}|…+)[a-z\d_-]{0,8})$/i;
  const maskMark = /[*•●·.…]/;
  const plainDate = /^(?:\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}日?|\d{1,2}[-/.]\d{1,2}[-/.]\d{4}|[a-z]{3,9} \d{1,2},? \d{4}|\d{1,2} [a-z]{3,9},? \d{4})(?:[T ,]+\d{1,2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?(?:\s*(?:AM|PM|UTC|Z|[+-]\d{2}:?\d{2}))?)?$/i;

  function visible(el) {
    if (!el || !el.getClientRects().length) return false;
    for (let p = el; p; p = p.parentElement) {
      if (p.hidden || p.inert || p.getAttribute('aria-hidden') === 'true') return false;
      const css = getComputedStyle(p);
      if (css.display === 'none' || css.visibility !== 'visible' || Number(css.opacity) === 0 ||
          css.contentVisibility === 'hidden' || css.clipPath === 'inset(50%)' ||
          (css.clip && css.clip !== 'auto')) return false;
    }
    const box = el.getBoundingClientRect();
    return box.width > 1 && box.height > 1;
  }

  // Read only public display cells and control labels. Never read attributes holding key
  // data, input values, tooltips, hidden descendants, or a container's full text.
  function displayedText(el, limit, expectMask = false) {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let out = '';
    let foundMask = false;
    let suffixLength = 0;
    for (let node; (node = walker.nextNode());) {
      const parent = node.parentElement;
      if (!parent || parent.closest('script,style,template,svg,[role="tooltip"]') || !visible(parent)) continue;
      for (let i = 0; i < node.length; i++) {
        const char = node.substringData(i, 1);
        if (out.length >= limit || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2066-\u2069]/.test(char)) return null;
        if (expectMask && foundMask && !/\s/.test(char)) {
          if (maskMark.test(char)) {
            if (suffixLength) return null;
          } else if (++suffixLength > 8) return null;
        } else if (expectMask && !foundMask) {
          foundMask = maskMark.test(char);
          // Stop at the short public prefix, before reading a plaintext credential.
          if (!foundMask && out.trimStart().length >= 11 && !/\s/.test(char)) return null;
        }
        out += char;
        if (!expectMask && /sk-$/i.test(out)) return null;
      }
    }
    return out.trim().replace(/\s+/g, ' ');
  }

  function visibleAll(root, selector) {
    return Array.from(root.querySelectorAll(selector)).filter(visible);
  }

  function covered(el) {
    const r = el.getBoundingClientRect();
    const left = Math.max(r.left, 0), right = Math.min(r.right, innerWidth);
    const top = Math.max(r.top, 0), bottom = Math.min(r.bottom, innerHeight);
    if (right <= left || bottom <= top) return false; // Normal scroll may place rows below the fold.
    const hit = document.elementFromPoint((left + right) / 2, (top + bottom) / 2);
    return !hit || !el.contains(hit);
  }

  // A site can also insert a one-time key inline without a dialog, role, or form. Reject
  // unrecognized standalone text around the directory without reading its contents.
  function hasUnexpectedText(root, table, explicitList) {
    const chrome = 'nav,footer,aside,header,h1,h2,a,button,[role="navigation"],[role="button"]';
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (node.nodeType === Node.ELEMENT_NODE) {
          if (node === table || node.matches(chrome + ',script,style,template,svg')) return NodeFilter.FILTER_REJECT;
          // On request, show just the table even when the platform introduces it with help text.
          // Never inspect that text. Inline results after the list still belong to the site.
          if (explicitList && node.matches('p,[role="note"]') &&
              (node.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING) &&
              !node.contains(table) && !node.querySelector('code,pre,output,button,input')) return NodeFilter.FILTER_REJECT;
          return NodeFilter.FILTER_SKIP;
        }
        return node.length >= 3 ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      },
    });
    const range = document.createRange();
    for (let node; (node = walker.nextNode());) {
      if (!visible(node.parentElement)) continue;
      range.selectNodeContents(node);
      const rect = range.getBoundingClientRect();
      if (rect.width > 8 && rect.height > 1) return true;
    }
    return false;
  }

  function hasFloatingPanel(table) {
    const elements = document.getElementsByTagName('*');
    if (elements.length > 10000) return true;
    const body = table.getBoundingClientRect();
    for (const el of elements) {
      if (table.contains(el) || el.contains(table)) continue;
      const style = getComputedStyle(el);
      if (style.position !== 'fixed' && style.position !== 'absolute') continue;
      const r = el.getBoundingClientRect();
      if (r.width < 40 || r.height < 24 || !visible(el)) continue;
      if (r.left < Math.min(innerWidth, body.right) && r.right > Math.max(0, body.left) &&
          r.top < Math.min(innerHeight, body.bottom) && r.bottom > Math.max(0, body.top)) return true;
      // A floating sheet or toast can also appear below an otherwise untouched list.
      if (style.position === 'fixed' && !el.closest('header,nav,aside,footer,[role="navigation"]') && r.left < innerWidth && r.top < innerHeight &&
          r.right > 0 && r.bottom > 0) return true;
    }
    return false;
  }

  function controls(root, table) {
    const possible = { create: [], previous: [], next: [] };
    for (const button of visibleAll(root, buttonSelector)) {
      if (table.contains(button) || button.matches(':disabled,[aria-disabled="true"]') ||
          getComputedStyle(button).pointerEvents === 'none') continue;
      if (button.matches('a[href]') && new URL(button.href).origin !== location.origin) continue;
      const caption = displayedText(button, 80);
      if (caption === null) continue;
      const label = (button.getAttribute('aria-label') || '').trim();
      const title = (button.getAttribute('title') || '').trim();
      for (const [key, pattern] of Object.entries(actions)) {
        const labels = [label, title, caption].filter(value => value && !/^[\s+‹›«»←→<>]+$/.test(value));
        if (labels.length && labels.every(value => pattern.test(value))) possible[key].push(button);
      }
    }
    const result = {};
    for (const key of Object.keys(possible)) {
      const [button] = possible[key];
      result[key] = possible[key].length === 1 && !covered(button) ? button : null;
    }
    return result;
  }

  // Mounted row handles contain no website data. Recycled nodes receive a new handle if
  // their displayed identity changes. Retain only the fingerprint in a weak, document-local map.
  const handles = new WeakMap();
  let serial = 0;
  function handle(row, entry) {
    let hash = 2166136261;
    for (const char of JSON.stringify(entry)) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
    const last = handles.get(row);
    if (last && last.hash === hash) return last.id;
    const id = 'r' + (++serial).toString(36);
    handles.set(row, { hash, id });
    return id;
  }

  function inspect(explicitList) {
    if (!inDirectory() || document.readyState !== 'complete' ||
        visibleAll(document, blockers).length) return null;
    if (window.CSS?.supports('selector(:popover-open)') && document.querySelector(':popover-open')) return null;
    const tables = visibleAll(document, tableSelector);
    if (tables.length !== 1) return null;
    const table = tables[0];
    if (hasFloatingPanel(table) || hasUnexpectedText(document.body, table, explicitList === true)) return null;
    const root = table.closest('main,[role="main"]') || document.body;
    if (visibleAll(root, 'pre,code,samp,kbd').some(el => !table.contains(el))) return null;
    const rows = visibleAll(table, rowSelector);
    if (!rows.length || rows.length > 251 || rows.some(row => row.closest(tableSelector) !== table)) return null;
    const first = rows.shift();
    const headerCells = visibleAll(first, 'th,[role="columnheader"]');
    const columns = headerCells.map(cell => {
      const value = displayedText(cell, 48);
      return value === null ? null : Object.keys(headers).find(key => headers[key].test(value));
    });
    if (columns.some(key => !key) || new Set(columns).size !== columns.length ||
        !columns.includes('name') || !columns.includes('masked') || covered(first)) return null;

    // A virtual window must never be presented as a complete page of results.
    const count = table.getAttribute('aria-rowcount');
    if (count !== null && (!/^\d+$/.test(count) || Number(count) !== rows.length + 1)) return null;
    const announcedColumns = table.getAttribute('aria-colcount');
    if (announcedColumns !== null && Number(announcedColumns) !== columns.length) return null;

    const entries = [];
    const targets = new Map();
    let explicitEmpty = false;
    for (const row of rows) {
      if (covered(row)) return null;
      const cells = visibleAll(row, 'td,[role="cell"]');
      if (cells.length === 1 && rows.length === 1 &&
          Number(cells[0].getAttribute('colspan') || cells[0].getAttribute('aria-colspan')) === columns.length) {
        const message = displayedText(cells[0], 80);
        if (!message || !empty.test(message)) return null;
        explicitEmpty = true;
        break;
      }
      if (cells.length !== columns.length || cells.some(cell => cell.closest(rowSelector) !== row ||
          Number(cell.getAttribute('colspan') || cell.getAttribute('aria-colspan') || 1) !== 1 ||
          Number(cell.getAttribute('rowspan') || cell.getAttribute('aria-rowspan') || 1) !== 1)) return null;
      const name = displayedText(cells[columns.indexOf('name')], 96);
      const masked = displayedText(cells[columns.indexOf('masked')], 128, true);
      if (!name || !masked || !maskedKey.test(masked) || /^[.…]+$/.test(masked)) return null;
      const data = { name, masked };
      for (const column of ['created', 'lastUsed']) {
        const position = columns.indexOf(column);
        if (position < 0) continue;
        const date = displayedText(cells[position], 64);
        if (date === null) return null;
        if (!date || /^(?:-|—|–|n\/a|无)$/i.test(date)) continue;
        if (!plainDate.test(date) && !(column === 'lastUsed' && /^(?:never(?: used)?|从未使用|未使用)$/i.test(date))) return null;
        data[column] = date;
      }
      const id = handle(row, data);
      entries.push({ id, ...data });
      targets.set(id, row);
    }
    if (!entries.length && !explicitEmpty) return null;
    const buttons = controls(root, table);
    return { data: { rows: entries, create: !!buttons.create, previous: !!buttons.previous,
      next: !!buttons.next }, buttons, targets };
  }

  // act() is used only by an explicit native button tap. Always resolve against this render.
  function act(id, explicitList) {
    try {
      if (typeof id !== 'string') return false;
      const current = inspect(explicitList);
      if (!current) return false;
      if (id === 'create' || id === 'previous' || id === 'next') {
        const button = current.buttons[id];
        if (!button) return false;
        button.click();
        return true;
      }
      if (!/^show:r[a-z\d]+$/.test(id)) return false;
      const row = current.targets.get(id.slice(5));
      if (!row) return false;
      row.scrollIntoView({ block: 'center', inline: 'nearest' });
      if (covered(row)) return false;
      if (!row.hasAttribute('tabindex')) row.setAttribute('tabindex', '-1');
      row.focus({ preventScroll: true });
      if (row.animate) row.animate([{ outline: '2px solid #4d6bfe' },
        { outline: '2px solid #4d6bfe', offset: 0.65 }, { outline: '2px solid transparent' }],
        { duration: 1600 });
      return true;
    } catch { return false; }
  }
  window.__tokenSeekKeyDirectoryV1 = Object.freeze({
    snapshot(explicitList) { try { return inspect(explicitList)?.data || null; } catch { return null; } },
    act,
  });
})();
