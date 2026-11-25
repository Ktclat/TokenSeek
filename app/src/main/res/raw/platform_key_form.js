// Operate only on an already-open official form. No order, credential or service API is used.
(() => {
  'use strict';
  const here = () => window === top && location.href === 'https://platform.deepseek.com/api_keys';
  if (!here() || window.__tokenSeekKeyFormV1) return;

  const wording = {
    create: {
      headings: ['Create API key', 'Create new API key', 'Create a new API key', 'Create new secret key', '创建 API Key', '创建 API 密钥', '创建密钥', '创建新的 API Key'],
      buttons: ['Create', 'Create key', 'Create API key', '创建', '创建密钥', '创建 API Key']
    },
    rename: {
      headings: ['Rename API key', 'Edit API key', 'Edit API key name', 'Rename key', '重命名 API Key', '重命名密钥', '编辑 API Key', '修改 API Key 名称', '修改密钥名称'],
      buttons: ['Save', 'Rename', 'Save changes', '保存', '重命名', '保存修改'],
      success: ['API key renamed', 'API key renamed successfully', 'API key updated successfully', 'API Key 重命名成功', '密钥重命名成功', 'API Key 名称已更新']
    },
    revoke: {
      headings: ['Revoke API key', 'Delete API key', 'Delete key', 'Revoke key', 'Revoke API key?', 'Delete API key?', '删除 API Key', '撤销 API Key', '删除密钥', '撤销密钥'],
      buttons: ['Revoke', 'Delete', 'Revoke key', 'Delete key', 'Delete API key', '删除', '撤销', '确认删除', '确认撤销'],
      success: ['API key deleted', 'API key revoked', 'API key deleted successfully', 'API key revoked successfully', 'API Key 删除成功', '密钥已删除', '密钥已撤销', '密钥撤销成功']
    }
  };
  const genericConfirm = ['Confirm', 'OK', '确定', '确认'];
  const cancel = ['Cancel', 'Close', 'Back', '取消', '关闭', '返回', '×'];
  const nameLabels = ['Name', 'Key name', 'API key name', 'New name', '名称', '新名称', '密钥名称', 'API Key 名称'];
  const identityLabels = ['Tracking ID','Key ID','Identifier','Masked key','Masked API key','API key','标识','跟踪 ID','追踪 ID','密钥标识','密钥掩码','API 密钥'];
  const dialogSelector = 'dialog[open],[role="dialog"],[role="alertdialog"],[aria-modal="true"]';
  const controlSelector = 'button,[role="button"],input[type="submit"]';
  let session = null;

  function shown(el) {
    if (!el || !el.isConnected || !el.getClientRects().length) return false;
    for (let node = el; node; node = node.parentElement) {
      const css = getComputedStyle(node);
      if (node.hidden || node.inert || node.getAttribute('aria-hidden') === 'true' ||
          css.display === 'none' || css.visibility !== 'visible' || Number(css.opacity) === 0) return false;
    }
    return true;
  }
  const visible = (root, selector) => Array.from(root.querySelectorAll(selector)).filter(shown);
  function available(el) {
    if (!shown(el) || el.matches(':disabled,[aria-disabled="true"]') || getComputedStyle(el).pointerEvents === 'none') return false;
    const r = el.getBoundingClientRect();
    const x = (Math.max(0,r.left) + Math.min(innerWidth,r.right))/2;
    const y = (Math.max(0,r.top) + Math.min(innerHeight,r.bottom))/2;
    return el.contains(document.elementFromPoint(x, y));
  }
  const quoted = value => "concat('" + value.split("'").join("',\"'\",'") + "','')";
  // Compare with known captions or the user's selected public row metadata inside the DOM.
  // Do not return arbitrary page text or read values from input fields, output, or the clipboard.
  function equalText(el, words, fold = true) {
    if (!shown(el) || el.querySelector('[hidden],[aria-hidden="true"]:not(svg),input:not([type="hidden"]),textarea,code,pre,output')) return false;
    const text = fold ? "translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')" : 'normalize-space(.)';
    const test = words.map(word => text + '=' + quoted(fold ? word.toLowerCase() : word)).join(' or ');
    return document.evaluate(test, el, null, XPathResult.BOOLEAN_TYPE, null).booleanValue;
  }
  function caption(el, words) {
    const aria = el.hasAttribute('aria-label');
    if (aria && !attributeMatches(el, 'aria-label', words)) return false;
    if (el.matches('input[type="submit"]')) return attributeMatches(el, 'value', words);
    return equalText(el, words) || (aria && equalText(el, ['']));
  }
  function attributeMatches(el, attr, words) {
    const test = words.map(word => "translate(normalize-space(@" + attr + "),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=" + quoted(word.toLowerCase())).join(' or ');
    return document.evaluate(test,el,null,XPathResult.BOOLEAN_TYPE,null).booleanValue;
  }
  function nameField(el) {
    if (!(el instanceof HTMLInputElement) || el.type !== 'text' || el.readOnly || !available(el)) return false;
    if (el.hasAttribute('aria-label')) return attributeMatches(el, 'aria-label', nameLabels);
    const by = el.getAttribute('aria-labelledby');
    if (by) return !/\s/.test(by) && equalText(document.getElementById(by), nameLabels);
    // A wrapping label may contain this field. Match only its visible label text.
    const labels = Array.from(el.labels || []).filter(shown);
    if (labels.length !== 1) return false;
    const label = labels[0];
    if (!label.contains(el)) return equalText(label, nameLabels);
    return label.querySelectorAll('input').length === 1 && !label.querySelector('textarea,code,pre,output,[hidden],[aria-hidden="true"]') &&
      document.evaluate(nameLabels.map(word => "translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=" + quoted(word.toLowerCase())).join(' or '),
        label,null,XPathResult.BOOLEAN_TYPE,null).booleanValue;
  }
  function inlineFieldAgrees(el, labels, expected) {
    // Some mobile confirmations put the caption and reference in a single text node.
    const text = "translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
    for (const delimiter of [':','：']) {
      const prefix = labels.map(label => 'starts-with(' + text + ',' + quoted(label.toLowerCase() + delimiter) + ')').join(' or ');
      if (document.evaluate(prefix,el,null,XPathResult.BOOLEAN_TYPE,null).booleanValue) {
        const match = 'normalize-space(substring-after(normalize-space(.),' + quoted(delimiter) + '))=' + quoted(expected);
        return document.evaluate(match,el,null,XPathResult.BOOLEAN_TYPE,null).booleanValue;
      }
    }
    return null;
  }
  function identity(root, task) {
    if (task.command === 'create') return true;
    if (visible(root,'table,ul,ol,[role="list"],[role="grid"]').length) return false;
    const fields = visible(root, 'p,span,dt,dd,strong,b,li,div,label').filter(el => el.childElementCount === 0 && !el.closest('button,[role="button"],h1,h2,h3,h4,h5,h6,[role="heading"]'));
    if (fields.length > 100) return false;
    let inlineId = 0, inlineName = 0;
    for (const el of fields) {
      const idLine = inlineFieldAgrees(el, identityLabels, task.id);
      const nameLine = inlineFieldAgrees(el, nameLabels, task.name);
      if (idLine === false || nameLine === false) return false;
      if (idLine) inlineId++;
      if (nameLine) inlineName++;
      const identifies = equalText(el, identityLabels);
      const names = equalText(el, nameLabels);
      if (!identifies && !names) continue;
      // A name label associated with an edit input is not a statement of the old row's identity.
      if (names && el.matches('label') && el.control) continue;
      const value = el.nextElementSibling;
      if (names && value?.matches('input[type="text"],input:not([type])')) continue;
      if (!value || !equalText(value, [identifies ? task.id : task.name], false)) return false;
    }
    const count = value => fields.filter(el => !el.closest('label') && equalText(el, [value], false)).length;
    const name = inlineName + count(task.name) === 1, id = inlineId + count(task.id) === 1;
    const distinctive = !/^(?:sk-)?(?:[*•●·.…-]+)$/i.test(task.id);
    if (name && id && distinctive && task.name !== task.id) return true;
    const requireUniqueOwner = !id || !distinctive;
    return (name || (id && distinctive)) && window.__tokenSeekKeyDirectoryV1?.matchesFormOwner?.(
      task.command, task.name, task.id, root, requireUniqueOwner) === true;
  }
  function inspect(task) {
    if (!here() || !Object.hasOwn(wording, task.command)) return null;
    const dialogs = visible(document, dialogSelector);
    const roots = dialogs.length ? dialogs : visible(document, 'form');
    if (roots.length !== 1) return null;
    const root = roots[0];
    if (root.matches('[aria-busy="true"]') || visible(root,'[aria-busy="true"],[role="progressbar"],progress').length) return null;
    if (visible(root, 'iframe,textarea,select,pre,code,samp,kbd,output,[readonly],[contenteditable="true"],input[type="password"],[data-clipboard-text]').length) return null;
    const forms = root.matches('form') ? [root] : visible(root, 'form');
    if (forms.length > 1 || forms.some(f => new URL(f.action,location.href).origin !== location.origin || (f.target && f.target !== '_self'))) return null;
    let headings = visible(root, 'h1,h2,h3,h4,h5,h6,[role="heading"]');
    if (!headings.length) {
      const label = root.getAttribute('aria-labelledby');
      if (label && !/\s/.test(label)) {
        const head = document.getElementById(label);
        if (head && root.contains(head)) headings = [head];
      }
    }
    const words = wording[task.command];
    if (!(headings.length === 1 && caption(headings[0], words.headings)) &&
        !(headings.length === 0 && attributeMatches(root,'aria-label',words.headings))) return null;
    const inputs = visible(root, 'input:not([type="hidden"]):not([type="submit"]),[role="textbox"]');
    if (task.command === 'revoke' ? inputs.length !== 0 : inputs.length !== 1 || !nameField(inputs[0])) return null;
    if (!identity(root, task)) return null;
    const buttons = visible(root, controlSelector);
    const confirms = buttons.filter(el => caption(el, [...words.buttons, ...genericConfirm]));
    if (confirms.length !== 1 || buttons.some(el => el !== confirms[0] && !caption(el, cancel))) return null;
    const confirm = confirms[0];
    if (confirm.hasAttribute('formaction') || confirm.hasAttribute('formtarget') || confirm.hasAttribute('form') ||
        confirm.closest('a[href]') || (confirm.form && !root.contains(confirm.form) && root !== confirm.form)) return null;
    return {root, confirm, input: inputs[0]};
  }
  function validName(s) {
    return typeof s === 'string' && s.trim() === s && s.length > 0 && s.length <= 96 &&
      !/[\u0000-\u001f\u007f]/.test(s) && !/sk-[a-z\d_-]{12,}/i.test(s);
  }
  function current(ticket, phase) {
    return here() && session && session.ticket === ticket && session.phase === phase && Date.now() < session.expires;
  }
  function unchanged(s) {
    const now = inspect(s);
    return now && now.root === s.root && now.input === s.input && now.confirm === s.confirm;
  }
  function successes(s) {
    return visible(document, '[role="status"],[role="alert"]').filter(el => equalText(el, wording[s.command].success));
  }
  function probe(command, name, id) {
    try {
      if (session && Date.now() < session.expires) return null;
      if (session) clear(session.ticket);
      if (command !== 'create' && (!validName(name) || typeof id !== 'string' || !id || id.length > 128 ||
          /[\u0000-\u001f\u007f]/.test(id) || /sk-[a-z\d_-]{9,}/i.test(id))) return null;
      const task = {command, name, id};
      const form = inspect(task);
      if (!form) return null;
      session = {...task,...form,ticket:crypto.randomUUID(),phase:'ready',expires:Date.now()+120000};
      return session.ticket;
    } catch { return null; }
  }
  function stage(ticket, value) {
    try {
      if (!current(ticket,'ready') || !unchanged(session)) return false;
      const s = session;
      s.phase = 'staging';
      if (s.input) {
        if (!validName(value) || (s.command === 'rename' && value === s.name) ||
            (s.input.maxLength >= 0 && value.length > s.input.maxLength) || value.length < s.input.minLength) return false;
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(s.input,value);
        s.input.dispatchEvent(new Event('input',{bubbles:true}));
        s.input.dispatchEvent(new Event('change',{bubbles:true}));
      }
      if (!unchanged(s)) return false;
      // Any subsequent edit invalidates this particular approval.
      if (s.input) {
        s.changed = () => { s.phase = 'changed'; };
        for (const event of ['input','change']) s.input.addEventListener(event,s.changed);
      }
      s.phase = 'staged';
      return true;
    } catch { return false; }
  }
  function submit(ticket) {
    try {
      if (!current(ticket,'staged')) return false;
      const s = session;
      s.phase = 'consumed';
      if (!unchanged(s) || !available(s.confirm) || (s.input && !s.input.validity.valid)) return false;
      if (s.command !== 'create') s.before = new Set(successes(s));
      s.confirm.click();
      return true;
    } catch { return false; }
  }
  function completed(ticket) {
    try {
      if (!current(ticket,'consumed') || session.command === 'create' || !session.before || shown(session.root)) return false;
      return successes(session).some(el => !session.before.has(el));
    } catch { return false; }
  }
  function clear(ticket) {
    if (!session || session.ticket !== ticket) return;
    if (session.changed && session.input) for (const event of ['input','change']) session.input.removeEventListener(event,session.changed);
    session = null;
  }
  window.addEventListener('pagehide', () => { if (session) clear(session.ticket); });
  window.__tokenSeekKeyFormV1 = Object.freeze({probe,stage,submit,completed,cancel:clear});
})();
