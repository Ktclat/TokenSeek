// Present the currently rendered page of the official key directory. Never query the service.
(() => {
  'use strict';
  const inDirectory = () => window === window.top &&
    location.origin === 'https://platform.deepseek.com' && location.pathname === '/api_keys';
  if (!inDirectory() || window.__tokenSeekKeyDirectoryV1) return;

  const tableSelector = 'table,[role="table"],[role="grid"]';
  const rowSelector = 'tr,[role="row"]';
  const buttonSelector = 'button,[role="button"],a[href],[tabindex="0"],[onclick],.ds-link,.ds-button';
  const pager = 'nav[aria-label*="pag" i],nav[aria-label*="分页"],[class*="pagination" i],[class~="pager"]';
  const blockers = [
    'dialog[open]', '[role="dialog"]', '[role="alertdialog"]', '[aria-modal="true"]',
    'input:not([type="hidden"])', 'textarea', 'select', '[contenteditable]:not([contenteditable="false"])',
    'iframe', '[role="alert"]', 'output', '[role="progressbar"]', 'progress', '[aria-busy="true"]',
    '[class*="modal" i]', '[class*="overlay" i]', '[class*="backdrop" i]', '[class*="mask-" i]',
    'a[href="/sign_in"]', 'a[href^="/sign_in?"]', '[class*="skeleton" i]',  '[class*="loading" i]', '[class*="virtual-list" i]',
  ].join(',');
  const headers = {
    name: /^(?:name|key name|api key name|名称|名称\/备注|密钥名称|api\s*key\s*名称|名字)$/i,
    trackingId: /^(?:tracking[\s_-]*id|跟踪\s*id|追踪\s*id)$/i,
    masked: /^(?:key|api\s*key|secret key|api secret key|密钥|秘钥|api\s*[密秘]钥)$/i,
    created: /^(?:created(?: at| on| date)?|creation (?:date|time)|创建(?:日期|时间)?)$/i,
    lastUsed: /^(?:last used(?: at| on)?|last use(?:d)? (?:date|time)|最[后新]使用(?:日期|时间)?|上次使用(?:日期|时间)?|最近使用(?:日期|时间)?)$/i,
    actions: /^(?:actions?|操作)?$/i,
  };
  const actions = {
    create: /^(?:\+\s*)?(?:create (?:a |an )?(?:new )?(?:api |secret )?key|创建(?:新的?|一个)?\s*(?:api\s*(?:key|密钥)|[密秘]钥))$/i,
    usage: /^(?:按\s*api\s*key\s*(?:查看|查询)用量|(?:view |check )?usage (?:by|per) api key|api key usage)$/i,
    previous: /^(?:previous(?: page)?|prev(?:ious)?|go to (?:the )?previous page|上一页|前一页)$/i,
    next: /^(?:next(?: page)?|go to (?:the )?next page|下一页|后一页)$/i,
  };
  const rowActions = {
    rename: /^(?:rename|edit|edit name|rename (?:api )?key|edit (?:api )?key(?: name)?|重命名|编辑(?:名称|密钥名称)?|修改名称)$/i,
    revoke: /^(?:(?:delete|revoke|remove)(?: (?:api )?key)?|删除(?:密钥)?|撤销(?:密钥)?|吊销(?:密钥)?)$/i,
  };
  const empty = /^(?:暂无(?:数据|密钥|\s*api\s*(?:key[s]?|密钥))|尚未创建(?:任何)?\s*(?:api\s*key|密钥)|no data|no (?:api |secret )?keys(?: yet| found)?|you (?:do not|don't) have any api keys(?: yet)?)[。.!！]?$/i;
  const maskedKey = /^(?:(?:sk-[a-z\d_-]{0,8})?(?:\*{3,}|[•●·]{3,}|\.{3,}|…+)[a-z\d_-]{0,8})$/i;
  const maskMark = /[*•●·.…]/;
  const plainDate = /^(?:\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}日?|\d{1,2}[-/.]\d{1,2}[-/.]\d{4}|[a-z]{3,9} \d{1,2},? \d{4}|\d{1,2} [a-z]{3,9},? \d{4})(?:[T ,]+\d{1,2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?(?:\s*(?:AM|PM|UTC|Z|[+-]\d{2}:?\d{2}))?)?$/i;

  let visibility = new WeakMap();
  function visible(el) {
    if (!el) return false;
    if (!visibility.has(el)) visibility.set(el, isRendered(el));
    return visibility.get(el);
  }
  function isRendered(el) {
    if (!el || !el.getClientRects().length) return false;
    if (!painted(el)) return false;
    const box = el.getBoundingClientRect();
    return box.width > 1 && box.height > 1;
  }
  let paintCache = new WeakMap();
  function painted(p) {
    if (paintCache.has(p)) return paintCache.get(p);
    const result = (() => {
      if (p.hidden || p.inert || p.getAttribute('aria-hidden') === 'true') return false;
      const css = getComputedStyle(p);
      if (css.display === 'none' || css.visibility !== 'visible' || Number(css.opacity) === 0 ||
          css.contentVisibility === 'hidden' || css.clipPath === 'inset(50%)' ||
          (css.clip && css.clip !== 'auto')) return false;
      return !p.parentElement || painted(p.parentElement);
    })();
    paintCache.set(p, result);
    return result;
  }

  // Read only public display cells and control labels. Never read attributes holding key
  // data, input values, tooltips, hidden descendants, or a container's full text.
  function displayedText(el, limit, expectMask = false, dataCell = false) {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let out = '';
    let foundMask = false;
    let suffixLength = 0;
    for (let node; (node = walker.nextNode());) {
      const parent = node.parentElement;
      if (!parent || parent.closest('script,style,template,svg,[role="tooltip"]') || !visible(parent)) continue;
      const nestedControl = dataCell && parent.closest(buttonSelector);
      if (nestedControl && nestedControl !== el && el.contains(nestedControl)) continue;
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

  function covered(el, pinned = []) {
    const r = el.getBoundingClientRect();
    const left = Math.max(r.left, 0), right = Math.min(r.right, innerWidth);
    const top = Math.max(r.top, 0), bottom = Math.min(r.bottom, innerHeight);
    if (right <= left || bottom <= top) return false; // Normal scroll may place rows below the fold.
    const hit = document.elementFromPoint((left + right) / 2, (top + bottom) / 2);
    return !hit || (!el.contains(hit) && !pinned.some(part => part.table.contains(hit)));
  }

  // A site can also insert a one-time key inline without a dialog, role, or form. Reject
  // unfamiliar content around the directory; recognize bounded public help and paging text.
  const normalizeGuide = text => text.toLowerCase().replace(/[\s，。；：、,.!?？！;:（）()]/g, '')
    .replace(/帐/g, '账').replace(/漏/g, '露').replace(/您/g, '你');
  const guideLead = /^(?:列表(?:内|中)(?:展示|显示|列出|列有|包含|是)?(?:你|有|的|全部|所有)|我们未(?:对|跟踪|追踪)|单个[账帐](?:号|户)|每个[账帐](?:号|户))/;
  const platformGuide = normalizeGuide('列表内是你的全部 API key，API key 仅在创建时可见可复制，请妥善保存。' +
    '不要与他人共享你的 API key，或将其暴露在浏览器或其他客户端代码中。' +
    '为了保护你的帐户安全，我们可能会自动禁用我们发现已公开泄露的 API key。' +
    '我们未对 2024 年 4 月 25 日前创建的 API key 的使用情况进行追踪。单个账号最多可以保留 300 个 API Key。');
  // The platform may wrap each part of a sentence in a separate span. Match against
  // the same public guide regardless of DOM boundaries or spacing.
  function isPlatformGuide(block, tables) {
    if (!block || tables.some(table => block.contains(table)) || block.querySelector('input,textarea,output,pre,code')) return false;
    const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
    let part = '', tail = '';
    for (let node; (node = walker.nextNode());) {
      if (!visible(node.parentElement)) continue;
      for (let i = 0; i < node.length; i++) {
        const c = node.substringData(i, 1);
        tail = (tail + c.toLowerCase()).slice(-3);
        if (tail === 'sk-' || part.length > 1200) return false;
        part += normalizeGuide(c);
        if (part.length > 24 && !platformGuide.includes(part) && !guideLead.test(part)) return false;
      }
    }
    return part.length >= 4 && (platformGuide.includes(part) ||
      (guideLead.test(part) && /apikey|密钥|使用情况|追踪|跟踪/.test(part)));
  }

  function hasUnexpectedText(root, tables, explicitList, textRegions) {
    const chrome = 'nav,footer,aside,header,h1,h2,a,button,[role="navigation"],[role="button"]';
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (textRegions.has(node)) return NodeFilter.FILTER_REJECT;
        if (node.nodeType === Node.ELEMENT_NODE) {
          if (node.matches(chrome + ',' + pager + ',.ds-button,.ds-link,script,style,template,svg')) return NodeFilter.FILTER_REJECT;
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
      if (rect.width <= 8 || rect.height <= 1) continue;
      // Public directory instructions often sit in a plain div. Recognize ordinary
      // help and paging text; stop immediately at a possible secret prefix.
      if (node.length > 2400) return true;
      let prefix = '', tail = '';
      for (let i = 0; i < node.length; i++) {
        const char = node.substringData(i, 1);
        tail = (tail + char).slice(-3);
        if (tail.toLowerCase() === 'sk-') return true;
        if (prefix.length < 180) prefix += char;
      }
      const hint = prefix.trim();
      if (platformGuide.includes(normalizeGuide(hint)) || (guideLead.test(normalizeGuide(hint)) && /api\s*key|密钥|追踪|跟踪/i.test(hint)) ||
          isPlatformGuide(node.parentElement.closest('p,div,section,[role="note"]'), tables)) continue;
      if (/^(?:此(?:列表|页)|本(?:列表|页|平台)|以下|这些|每个账号|每个账户|每个帐号|[12]\d{3}[-/.年]\d{1,2}|this (?:page|list)|each account|keys created before)/i.test(hint) &&
          /api\s*keys?|密钥|跟踪|追踪|用量|tracking|usage|secret|额度/i.test(hint)) continue;
      if (/^(?:请勿|不要|请注意|为[了您]?(?:保障|保护|确保)|您(?:可以|的)|你(?:可以|的)|下(?:列|面|方)|这里|在[此这里]|密钥(?:是|需|仅)|(?:api\s*keys?)(?:\s|[是仅为用可，。])|为了您的|please (?:do not|note|keep)|do not (?:share|disclose)|keep your|your (?:secret |api )?keys|you can|manage (?:your|the) api keys|the following|tracking id|按\s*api\s*key|[，。；;]|(?:共|第)\s*\d|(?:total|page|showing)\s+\d|\d+\s*[/–-]\s*\d+)/i.test(hint)) continue;
      const paragraph = node.parentElement.closest('p,[role="note"]');
      if (explicitList && paragraph && (paragraph.compareDocumentPosition(tables[0]) & Node.DOCUMENT_POSITION_FOLLOWING) &&
          !paragraph.querySelector('code,pre,output,input') && !/confirm|issued|generated|成功|确认|已创建/i.test(hint)) continue;
      return true;
    }
    return false;
  }

  function hasFloatingPanel(table, tables) {
    const tree = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT, {
      acceptNode(el) {
        return tables.includes(el) || el.matches('script,style,template,svg') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
    const body = table.getBoundingClientRect();
    for (let el; (el = tree.nextNode());) {
      if (tables.some(part => el.contains(part))) continue;
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

  // The platform's current icon buttons have no accessible name until their tooltip opens.
  // These fingerprints identify the two published assets only inside the official actions cell.
  // New artwork stays unrecognized. The form and its owner are checked again before submission.
  function platformIcon(button) {
    if (!button.matches('.ds-button--icon[role="button"]') ||
        !button.closest('.ds-table--embedded > table > tbody > tr > td.ds-table-td:last-child')) return '';
    const icons = button.querySelectorAll('svg');
    const svg = icons[0];
    if (icons.length !== 1 || svg.getAttribute('viewBox') !== '0 0 16 16' ||
        svg.children.length !== 1 || svg.firstElementChild.localName !== 'path') return '';
    const path = svg.firstElementChild.getAttribute('d') || '';
    if (path.length !== 1531 && path.length !== 1695) return '';
    let hash = 2166136261;
    for (let i = 0; i < path.length; ++i) hash = Math.imul(hash ^ path.charCodeAt(i), 16777619);
    if (path.length === 1531 && (hash >>> 0) === 0xcd7976ad) return 'edit';
    if (path.length === 1695 && (hash >>> 0) === 0x3952e481) return 'delete';
    return '';
  }

  function namedIcon(button) {
    const icons = button.matches('svg') ? [button] : [...button.querySelectorAll('svg')];
    const paintedIcons = icons.filter(icon => icon.getClientRects().length && getComputedStyle(icon).visibility === 'visible');
    if (paintedIcons.length !== 1) return '';
    const svg = paintedIcons[0];
    const title = svg.querySelector('title');
    let label = svg.getAttribute('aria-label') || '';
    const text = title?.firstChild;
    if (!label && text?.nodeType === Node.TEXT_NODE && text.length <= 32) {
      for (let i = 0; i < text.length; i++) {
        label += text.substringData(i, 1);
        if (/sk-$/i.test(label)) return '';
      }
    }
    if (label) return label;
    const published = platformIcon(button);
    if (published) return published;
    const use = svg.querySelector('use');
    const symbol = use && (use.getAttribute('href') || use.getAttribute('xlink:href'));
    const names = [svg.getAttribute('data-icon') || '', ...(svg.getAttribute('class') || '').split(/\s+/)];
    if (symbol && symbol.startsWith('#')) names.push(symbol.slice(1));
    const recognized = new Set();
    for (const name of names) {
      if (/^(?:icon-|lucide-|anticon-|ds-icon-)?(?:pencil|pencil-line|square-pen|edit|edit-outlined|form)$/i.test(name)) recognized.add('edit');
      if (/^(?:icon-|lucide-|anticon-|ds-icon-)?(?:trash|trash-2|trash-can|delete|delete-outlined|revoke)$/i.test(name)) recognized.add('delete');
      if (/^(?:icon-|lucide-|anticon-|ds-icon-)?(?:chevron|arrow)-left$/i.test(name)) recognized.add('previous');
      if (/^(?:icon-|lucide-|anticon-|ds-icon-)?(?:chevron|arrow)-right$/i.test(name)) recognized.add('next');
    }
    return recognized.size === 1 ? [...recognized][0] : '';
  }

  function identify(button, choices, useIcon) {
    if (button.matches(':disabled,[aria-disabled="true"],.ds-button--disabled,.ds-button--loading') || getComputedStyle(button).pointerEvents === 'none') return null;
    if (button.matches('a[href]') && new URL(button.href).origin !== location.origin) return null;
    const caption = displayedText(button, 80);
    if (caption === null) return null;
    const label = button.getAttribute('aria-label') || '';
    const title = button.getAttribute('title') || '';
    const labels = [label, title, caption];
    if (useIcon) labels.push(namedIcon(button));
    if (choices === actions && button.closest(pager) && !button.closest('[dir="rtl"]')) {
      if (/^[‹«←<]$/.test(caption)) labels.push('previous');
      if (/^[›»→>]$/.test(caption)) labels.push('next');
      labels.push(namedIcon(button));
    }
    const words = labels.map(value => value.trim()).filter(value => value && !/^[\s+‹›«»←→<>]+$/.test(value));
    return words.length ? Object.keys(choices).find(key => words.every(value => choices[key].test(value))) : null;
  }

  function controls(root, tables) {
    const possible = { create: [], usage: [], previous: [], next: [] };
    for (const button of visibleAll(root, buttonSelector)) {
      if (tables.some(table => table.contains(button))) continue;
      const key = identify(button, actions, false);
      if (key && !covered(button)) possible[key].push(button);
    }
    return Object.fromEntries(Object.entries(possible).map(([key, list]) => [key, list.length === 1 ? list[0] : null]));
  }

  function actionButtons(row) {
    const buttons = visibleAll(row, buttonSelector);
    for (const svg of row.querySelectorAll('svg')) {
      if (svg.closest(buttonSelector) || !visible(svg.parentElement) || !namedIcon(svg)) continue;
      const hit = [svg.parentElement, svg].find(el => row.contains(el) &&
        el !== row && getComputedStyle(el).cursor === 'pointer' && el.querySelectorAll('svg').length <= 1);
      if (hit && !buttons.includes(hit)) buttons.push(hit);
    }
    const possible = { rename: [], revoke: [] };
    for (const button of buttons) {
      if (button.closest('form,dialog,[role="dialog"],[role="alertdialog"]')) continue;
      const type = identify(button, rowActions, true);
      if (type) possible[type].push(button);
    }
    return Object.fromEntries(Object.entries(possible).map(([key, list]) => [key, list.length === 1 ? list[0] : null]));
  }

  // Mounted row handles contain no website data. Recycled nodes receive a new handle if
  // their displayed identity changes. Compare exact public fields in ephemeral weak memory.
  const handles = new WeakMap();
  let serial = 0;
  function handle(row, entry) {
    const identity = JSON.stringify(entry);
    const last = handles.get(row);
    if (last && last.identity === identity) return last.id;
    const id = 'r' + (++serial).toString(36);
    handles.set(row, { identity, id });
    return id;
  }

  const headerCellsSelector = 'th,[role="columnheader"]';
  const dataCellsSelector = 'td,[role="cell"],[role="gridcell"]';
  const children = el => Array.from(el.children).filter(visible);
  const isPlaceholder = value => value !== null && /^(?:|—|–|-|n\/a)$/i.test(value);

  function columnsOf(cells) {
    if (cells.length < 1 || cells.length > 8) return null;
    const columns = cells.map(cell => {
      const value = displayedText(cell, 48);
      return value === null ? null : Object.keys(headers).find(key => headers[key].test(value.replace(/\s*[?？ⓘ]$/, '')));
    });
    return columns.every(Boolean) && new Set(columns).size === columns.length ? columns : null;
  }
  function hasIdentifiers(columns) {
    return columns && columns.includes('name') && (columns.includes('masked') || columns.includes('trackingId'));
  }

  function semanticLayout(table) {
    const all = visibleAll(table, rowSelector);
    if (!all.length && !table.matches('table')) return customLayout(table);
    if (!all.length || all.some(row => row.closest(tableSelector) !== table)) return null;
    const header = all.shift();
    const columns = columnsOf(visibleAll(header, headerCellsSelector));
    if (!columns) return null;
    return { semantic: true, table, header, columns, rows: all, cells: row => visibleAll(row, dataCellsSelector) };
  }

  // Custom renderers must still have one header and distinct row/cell elements. Anchor
  // discovery at an exact public "Name" heading. Never scan arbitrary cells for key-looking data.
  function customLayout(scope = document.body) {
    const labels = ['名称', '密钥名称', '名字', 'name', 'key name', 'api key name'];
    const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT);
    const candidates = new Map();
    for (let text; (text = walker.nextNode());) {
      if (text.length > 32 || !text.length || !visible(text.parentElement) ||
          text.parentElement.closest('script,style,template,svg,textarea,input')) continue;
      let prefix = '';
      for (let i = 0; i < text.length; i++) {
        prefix += text.substringData(i, 1).toLowerCase();
        if (!labels.some(label => label.startsWith(prefix.trim()))) break;
      }
      if (prefix.length !== text.length || !labels.includes(prefix.trim()) || !visible(text.parentElement)) continue;
      let header = text.parentElement;
      for (let i = 0; header && i < 5 && header !== document.body; i++, header = header.parentElement) {
        if (!header.matches('div,li,section,header') || header.childElementCount < 2 || header.childElementCount > 8) continue;
        const columns = columnsOf(children(header));
        if (!hasIdentifiers(columns)) continue;
        let table = header.parentElement;
        const peers = children(table);
        let rows = peers.slice(peers.indexOf(header) + 1);
        if (!rows.length) {
          const body = table.nextElementSibling;
          if (!body || (!visible(body) && !/body|rowgroup/i.test(body.className))) break;
          table = table.parentElement;
          rows = children(body);
        } else if (rows.length === 1) {
          const body = rows[0], nested = children(body);
          const grouped = /(?:body|rowgroup)/i.test(body.className) ||
            nested.length && nested.every(el => el.children.length >= columns.length && el.matches('div,li,section'));
          if (grouped) rows = nested;
        }
        if (rows.length === 1 && rows[0].childElementCount < 2 && findEmpty(rows[0])) rows = [];
        if (table && rows.every(el => el.matches('div,li,section'))) {
          candidates.set(header, { table, header, columns, rows, cells: children });
        }
        break;
      }
    }
    return candidates.size === 1 ? [...candidates.values()][0] : null;
  }

  const emptyLabels = ['暂无数据', '暂无密钥', '暂无 API Key', '暂无 API Keys', '暂无 API 密钥', '暂无已创建的 API key',
    '还没有 API Key', '尚未创建 API Key', '尚未创建任何 API Key', '尚未创建任何密钥', '还没有创建 API Key',
    '你还没有创建 API key', '您还没有创建 API key', '未创建 API Key', 'No data', 'No keys', 'No API keys',
    'No API keys yet', 'No API keys found', 'You do not have any API keys yet', "You don't have any API keys yet"]
    .map(value => value.replace(/\s/g, '').toLowerCase());
  function findEmpty(root) {
    if (!root || !visible(root)) return null;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node; (node = walker.nextNode());) {
      if (node.length > 80 || !visible(node.parentElement) || node.parentElement.closest('button,a,script,style,template,svg')) continue;
      let value = '', valid = true;
      for (let i = 0; i < node.length; i++) {
        const c = node.substringData(i, 1).toLowerCase();
        if (/\s|[.!。！]/.test(c)) continue;
        value += c;
        if (!emptyLabels.some(label => label.startsWith(value))) { valid = false; break; }
      }
      if (valid && emptyLabels.includes(value)) return { node, generic: /^(?:暂无数据|nodata)$/.test(value) };
    }
    return null;
  }
  function emptyDirectory() {
    const main = document.querySelector('main,[role="main"]') || document.body;
    const titles = visibleAll(main, 'h1,h2,[role="heading"]').filter(el =>
      /^(?:api\s*keys?|api\s*key\s*管理|api\s*密钥(?:管理)?|密钥管理)$/i.test(displayedText(el, 48) || ''));
    if (titles.length !== 1) return null;
    const marker = findEmpty(main);
    const buttons = controls(main, []);
    if (!marker || marker.generic || !buttons.create) return null;
    const regions = [marker.node, titles[0]];
    if (hasFloatingPanel(titles[0], regions) || hasUnexpectedText(document.body, regions, false, new Set(regions)) ||
        visibleAll(main, 'pre,code,output').length) return null;
    return { data: { rows: [], create: true, usage: !!buttons.usage, previous: false, next: false },
      buttons, targets: new Map() };
  }

  function validTracking(value) {
    if (value === null) return false;
    const format = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx';
    const matchesAt = (fragment, start) => [...fragment.toLowerCase()].every((ch, i) =>
      format[start + i] === '-' ? ch === '-' : /[a-f0-9]/.test(ch));
    if (value.length === format.length && /^[\da-f-]+$/i.test(value)) return matchesAt(value, 0);
    const parts = value.match(/^([\da-f-]{4,32})\s*(?:\.{3}|…)\s*([\da-f-]{1,24})$/i);
    return !!parts && parts[1].length + parts[2].length < format.length &&
      matchesAt(parts[1], 0) && matchesAt(parts[2], format.length - parts[2].length);
  }
  function cellText(cells, columns, key, limit) {
    const i = columns.indexOf(key);
    if (i < 0) return '';
    const cell = cells[i];
    const text = displayedText(cell, limit, key === 'masked', true);
    if (text !== '' || (key !== 'trackingId' && key !== 'masked')) return text;
    // Some renderers wrap the public identifier itself in a copy button. Read the
    // displayed characters only, never its clipboard action, title, or data attributes.
    return displayedText(cell, limit, key === 'masked');
  }

  // The design-system table represents an empty directory with three rows. Two spacers
  // are CSS-hidden and the visible text is itself aria-hidden. Match only this template.
  function platformEmpty(rows, count) {
    if (rows.length !== 3) return false;
    let shown = 0;
    for (const row of rows) {
      const cell = row.firstElementChild;
      if (row.children.length !== 1 || !cell.matches('td.ds-table-td--empty') || cell.colSpan !== count) return false;
      const marker = cell.querySelector(':scope > span.ds-table-empty');
      if (!marker || marker.querySelector('input,textarea,output,code,pre')) return false;
      if (getComputedStyle(marker).visibility !== 'visible' || !marker.getClientRects().length) continue;
      const label = "translate(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),' ','')";
      const test = label + "='暂无apikey，你可以创建apikey' or " + label + "='noapikey,youcancreatenewapikey.'";
      if (!document.evaluate(test, marker, null, XPathResult.BOOLEAN_TYPE, null).booleanValue) return false;
      shown++;
    }
    return shown === 1;
  }

  function platformListRoot(table) {
    if (!table.matches('.ds-table--embedded > table')) return null;
    for (let root = table.parentElement; root && root !== document.body; root = root.parentElement) {
      const buttons = controls(root, [table]);
      if (buttons.create || buttons.usage) return root;
    }
    return null;
  }

  function inspect(explicitList) {
    visibility = new WeakMap();
    paintCache = new WeakMap();
    if (!inDirectory() || document.readyState !== 'complete') return null;
    if (visibleAll(document, blockers).some(el => {
      if (!el.closest(pager) || !el.matches('select,input[type="number"]')) return true;
      return !/^(?:rows per page|page size|page number|jump to page|每页条数|每页数量|跳至页码|页码)$/i.test(el.getAttribute('aria-label') || '');
    })) return null;
    if (window.CSS?.supports('selector(:popover-open)') && document.querySelector(':popover-open')) return null;
    const tables = visibleAll(document, tableSelector);
    const layouts = tables.length ? tables.map(semanticLayout) : [customLayout()];
    if (!tables.length && !layouts[0]) return emptyDirectory();
    if (layouts.some(layout => !layout)) return null;
    const primary = layouts.filter(layout => hasIdentifiers(layout.columns));
    if (primary.length !== 1) return null;
    const layout = primary[0];
    const { table, header, columns, rows } = layout;
    const extras = layouts.filter(item => item !== layout);
    // A separate pinned action pane may shadow part of each row. Only explicit ARIA
    // ownership can join its action controls to the main row, never array position.
    if (extras.some(item => item.columns.join() !== 'actions' ||
        !item.table.closest('[class*="fixed" i],[class*="pinned" i],[class*="sticky" i]'))) return null;
    const parts = layouts.map(item => item.table);
    const platformRoot = platformListRoot(table);
    const root = platformRoot || table.closest('main,[role="main"]') || document.body;
    let externalEmpty = null;
    if (!rows.length) {
      const siblings = [table, table.nextElementSibling];
      if (table.parentElement?.childElementCount === 1) siblings.push(table.parentElement.nextElementSibling);
      for (const sibling of siblings) {
        const marker = findEmpty(sibling);
        if (marker) { externalEmpty = marker.node; break; }
      }
      if (!externalEmpty) return null;
    }
    const textRegions = new Set(layouts.flatMap(item => item.semantic ? [item.table] : [item.header, ...item.rows]));
    if (externalEmpty) textRegions.add(externalEmpty);
    const verification = platformRoot && visibleAll(platformRoot, '.ds-alert--warning').find(el =>
      /^(?:你还未完成实名认证，请认证后再创建 API Key|Please complete real-name verification to create API Key)$/i.test(
        displayedText(el, 180, false, true) || ''));
    if (verification) textRegions.add(verification);
    if (hasFloatingPanel(table, parts) || hasUnexpectedText(platformRoot || document.body, parts, explicitList === true, textRegions) ||
        visibleAll(root, 'pre,code,samp,kbd').some(el => !parts.some(part => part.contains(el)))) return null;
    if (rows.length > 300 || covered(header, extras)) return null;
    const count = table.getAttribute('aria-rowcount');
    if (count !== null && (!/^\d+$/.test(count) || Number(count) !== rows.length + 1)) return null;
    const announcedColumns = table.getAttribute('aria-colcount');
    if (announcedColumns !== null && Number(announcedColumns) !== columns.length) return null;
    const entries = [], targets = new Map();
    let explicitEmpty = !!externalEmpty || (!!platformRoot && platformEmpty(rows, columns.length));
    for (const row of explicitEmpty ? [] : rows) {
      if (covered(row, extras)) return null;
      const cells = layout.cells(row);
      if (cells.length === 1 && rows.length === 1 &&
          Number(cells[0].getAttribute('colspan') || cells[0].getAttribute('aria-colspan')) === columns.length) {
        const message = displayedText(cells[0], 80);
        if (!message || !empty.test(message)) return null;
        explicitEmpty = true;
        break;
      }
      const trailingAction = !columns.includes('actions') && cells.length === columns.length + 1 &&
        (cells.at(-1).querySelector(buttonSelector + ',svg') || cells.at(-1).matches(buttonSelector));
      if ((cells.length !== columns.length && !trailingAction) || cells.some(cell =>
          (layout.semantic && cell.closest(rowSelector) !== row) ||
          Number(cell.getAttribute('colspan') || cell.getAttribute('aria-colspan') || 1) !== 1 ||
          Number(cell.getAttribute('rowspan') || cell.getAttribute('aria-rowspan') || 1) !== 1)) return null;
      let name = cellText(cells, columns, 'name', 96);
      const maskedCell = cells[columns.indexOf('masked')];
      const managed = !!platformRoot && !!maskedCell && /^(?:DeepSeek Harness)\s*App-managed$/i.test(name || '') &&
        document.evaluate("normalize-space(.)='由 Harness 管理' or normalize-space(.)='Managed by Harness'",
          maskedCell, null, XPathResult.BOOLEAN_TYPE, null).booleanValue;
      if (managed) name = 'DeepSeek Harness';
      else if (platformRoot) {
        const named = visibleAll(cells[columns.indexOf('name')], 'span[title]');
        if (named.length === 1) name = displayedText(named[0], 96);
      }
      const masked = managed ? '' : cellText(cells, columns, 'masked', 128);
      const tracking = cellText(cells, columns, 'trackingId', 52);
      if (!name || masked === null || tracking === null) return null;
      const data = { name };
      if (managed) data.managed = true;
      if (!isPlaceholder(masked)) {
        if (!maskedKey.test(masked) || /^[.…]+$/.test(masked)) return null;
        data.masked = masked;
      }
      if (!isPlaceholder(tracking)) {
        if (!validTracking(tracking)) return null;
        data.trackingId = tracking;
      }
      if (!data.masked && !data.trackingId && !managed) return null;
      for (const key of ['created', 'lastUsed']) {
        const value = cellText(cells, columns, key, 64);
        if (value === null) return null;
        if (isPlaceholder(value) || value === '无') continue;
        if (!plainDate.test(value) && !(key === 'lastUsed' && /^(?:never(?: used)?|从未使用|未使用)$/i.test(value))) return null;
        data[key] = value;
      }
      const id = handle(row, data);
      const buttons = actionButtons(row);
      const actionRows = [row];
      const owned = (row.getAttribute('aria-owns') || '').split(/\s+/).filter(Boolean);
      for (const item of extras) {
        const linked = item.rows.filter(el => owned.includes(el.id));
        if (linked.length === 1) {
          actionRows.push(linked[0]);
          const external = actionButtons(linked[0]);
          for (const key of Object.keys(rowActions)) buttons[key] = buttons[key] || external[key];
        }
      }
      entries.push({ id, ...data, rename: !managed && !!buttons.rename, revoke: !managed && !!buttons.revoke });
      targets.set(id, { row, buttons, data, actionRows });
    }
    if (!entries.length && !explicitEmpty) return null;
    const buttons = controls(root, parts);
    if (externalEmpty && !buttons.create) return null;
    return { data: { rows: entries, create: !!buttons.create, verification: !!verification && !buttons.create, usage: !!buttons.usage,
      previous: !!buttons.previous, next: !!buttons.next }, buttons, targets };
  }

  function locate(el) {
    el.scrollIntoView({ block: 'center', inline: 'nearest' });
    if (covered(el)) return false;
    if (!el.hasAttribute('tabindex') && !el.matches('a,button')) el.setAttribute('tabindex', '-1');
    el.focus({ preventScroll: true });
    if (el.animate) el.animate([{ outline: '2px solid #4d6bfe' },
      { outline: '2px solid #4d6bfe', offset: 0.65 }, { outline: '2px solid transparent' }], { duration: 1600 });
    return true;
  }
  let owner = null;
  function releaseOwner() {
    owner?.watch?.disconnect();
    owner = null;
  }
  window.addEventListener('pagehide', releaseOwner);
  document.addEventListener('click', event => {
    if (owner && event.target instanceof Element && !owner.trigger.contains(event.target) &&
        !event.target.closest('form,dialog,[role="dialog"],[role="alertdialog"],[aria-modal="true"]')) releaseOwner();
  }, true);
  function dispatch(command, explicitList) {
    releaseOwner();
    try {
      if (typeof command !== 'string') return 'stale';
      const current = inspect(explicitList);
      if (!current) return 'stale';
      if (Object.hasOwn(actions, command)) {
        const button = current.buttons[command];
        if (!button || !locate(button)) return 'stale';
        button.click();
        return command === 'create' ? 'form' : command === 'usage' ? 'source' : 'page';
      }
      const match = command.match(/^(show|rename|revoke):(r[a-z\d]+)$/);
      const target = match && current.targets.get(match[2]);
      if (!target || target.data.managed) return 'stale';
      const [ , action ] = match;
      const identifier = target.data.trackingId || target.data.masked;
      const duplicate = Array.from(current.targets.values()).some(other => other !== target &&
        other.data.name === target.data.name && (other.data.trackingId || other.data.masked) === identifier);
      if (duplicate && action !== 'show') return locate(target.row) ? 'source' : 'stale';
      const button = target.buttons[action];
      if (!button || action === 'show') return locate(target.row) ? 'source' : 'stale';
      if (!locate(button)) return 'stale';
      const controls = (button.getAttribute('aria-controls') || '').split(/\s+/);
      const owns = (button.getAttribute('aria-owns') || '').split(/\s+/);
      const popover = button.getAttribute('popovertarget');
      const linked = new Set([...controls, ...owns, popover].filter(Boolean));
      const officialOpener = platformIcon(button) === (action === 'rename' ? 'edit' : 'delete');
      const opensDialog = officialOpener || button.getAttribute('aria-haspopup') === 'dialog' || [...linked].some(id =>
        document.getElementById(id)?.matches('dialog,[role="dialog"],[role="alertdialog"],[aria-modal="true"],form'));
      // A plain trash button may be an immediate deletion. Hand the focused button
      // to the user unless the official control declares that it opens a dialog.
      if (action === 'revoke' && !opensDialog) return 'source';
      // Names and clipped IDs can coincide. A reused portal cannot establish ownership by
      // name alone when other rows link the same dialog, even if it opened after this click.
      const uniqueLinks = new Set(linked);
      if (uniqueLinks.size) {
        for (const other of visibleAll(document, '[aria-controls],[aria-owns],[popovertarget]')) {
          if (target.actionRows.some(row => row.contains(other))) continue;
          for (const attr of ['aria-controls', 'aria-owns', 'popovertarget']) {
            for (const id of (other.getAttribute(attr) || '').split(/\s+/)) uniqueLinks.delete(id);
          }
        }
      }
      const uniqueName = !Array.from(current.targets.values()).some(item =>
        item !== target && item.data.name === target.data.name);
      owner = { action, data: target.data, row: target.row, trigger: button, linked, uniqueLinks,
        uniqueName, officialOpener, oldDialogs: officialOpener ? new Set(document.querySelectorAll('[role="dialog"]')) : null,
        since: performance.now() };
      if (officialOpener) {
        owner.watch = new MutationObserver(releaseOwner);
        for (const cell of target.row.cells) {
          if (!cell.contains(button)) owner.watch.observe(cell, { childList: true, characterData: true, subtree: true });
        }
      }
      button.click();
      return 'form';
    } catch { releaseOwner(); return 'stale'; }
  }
  function matchesFormOwner(command, name, identifier, dialog, requireUniqueOwner = false) {
    if (!inDirectory() || !owner || owner.action !== command || owner.data.name !== name ||
        !identifier || ![owner.data.trackingId, owner.data.masked].includes(identifier) ||
        !(dialog instanceof Element) || !dialog.isConnected || dialog.ownerDocument !== document ||
        !dialog.matches('dialog,[role="dialog"],[role="alertdialog"],[aria-modal="true"],form')) return false;
    visibility = new WeakMap();
    paintCache = new WeakMap();
    if (performance.now() - owner.since > 120000 || !visible(dialog)) return false;
    const modalSelector = 'dialog[open],[role="dialog"],[role="alertdialog"],[aria-modal="true"]';
    const modals = visibleAll(document, modalSelector);
    const candidates = modals.length ? modals.filter(el => !modals.some(other => other !== el && other.contains(el))) : visibleAll(document, 'form');
    if (candidates.length !== 1 || candidates[0] !== dialog) return false;
    if (owner.watch?.takeRecords().length) { releaseOwner(); return false; }
    if (owner.officialOpener && owner.uniqueName && dialog.matches('.ds-modal-content[role="dialog"]') &&
        owner.row.isConnected && owner.row.contains(owner.trigger) && !owner.oldDialogs.has(dialog)) {
      // A public icon opens a fresh portal for its own row. Reusing any dialog or replacing
      // the captured trigger invalidates this proof. Pin the portal for all later stages.
      if (owner.portal && owner.portal !== dialog) return false;
      owner.portal = dialog;
      return true;
    }
    if (owner.row.contains(dialog)) return true;
    if (requireUniqueOwner) return owner.uniqueName && !!dialog.id && owner.uniqueLinks.has(dialog.id);
    return !!dialog.id && owner.linked.has(dialog.id);
  }
  window.__tokenSeekKeyDirectoryV1 = Object.freeze({
    snapshot(explicitList) { try { return inspect(explicitList)?.data || null; } catch { return null; } },
    act(command, explicitList) { return dispatch(command, explicitList) !== 'stale'; },
    dispatch, matchesFormOwner,
  });
})();
