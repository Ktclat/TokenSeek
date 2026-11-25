(amount => {
  'use strict';
  if (window !== window.top || location.origin !== 'https://platform.deepseek.com'
      || location.pathname !== '/top_up' || typeof amount !== 'string'
      || !/^(?:0|[1-9][0-9]{0,11})\.[0-9]{2}$/.test(amount) || amount === '0.00') return false;

  const topUp = /充值金额|自定义金额|其他金额|其它金额|(?:top[\s-]?up|recharge|custom)\s+amount|amount\s+to\s+(?:top[\s-]?up|recharge)/iu;
  const cny = /人民币|\b(?:CNY|RMB|Chinese\s+yuan)\b/iu;
  const otherCurrency = /美元|美金|日元|日圆|港币|港元|台币|臺幣|欧元|英镑|\b(?:USD|JPY|HKD|TWD|EUR|GBP|US\s+dollars?)\b|[$€£]/iu;
  const interactive = 'input,textarea,select,option,button,a,script,style,noscript,[contenteditable]';

  function visible(element) {
    if (!element.isConnected || element.closest('[hidden],[inert],[aria-hidden="true"]')
        || !element.getClientRects().length) return false;
    for (let node = element; node; node = node.parentElement) {
      const css = getComputedStyle(node);
      if (css.display === 'none' || css.visibility !== 'visible' || css.opacity === '0') return false;
    }
    return true;
  }

  function overlaps(a, b) {
    return Math.min(a.right, b.right) > Math.max(a.left, b.left)
      && Math.min(a.bottom, b.bottom) > Math.max(a.top, b.top);
  }

  function blockedByDialog(viewport) {
    // An iframe can contain payment or identity verification, including on another origin.
    // Examine only its outer rectangle. Never access its document or URL.
    const blocking = 'dialog[open],[role="dialog"],[role="alertdialog"],[aria-modal="true"],iframe';
    const onScreen = node => visible(node) && overlaps(node.getBoundingClientRect(), viewport);
    return Array.from(document.querySelectorAll(blocking)).some(node =>
      onScreen(node) || (node.tagName !== 'IFRAME' && Array.from(node.querySelectorAll('*')).some(onScreen)));
  }

  function unobscured(field, viewport) {
    const rect = field.getBoundingClientRect();
    if (rect.width < 24 || rect.height < 16 || rect.left < viewport.left || rect.top < viewport.top
        || rect.right > viewport.right || rect.bottom > viewport.bottom) return false;
    for (let n = field; n; n = n.parentElement) {
      const css = getComputedStyle(n);
      if (Number(css.opacity) < 0.98 || css.filter !== 'none' || css.clipPath !== 'none') return false;
    }
    // Cover the middle and each side, including ancestors clipping a still-laid-out field.
    for (const x of [0.1, 0.5, 0.9]) for (const y of [0.15, 0.5, 0.85]) {
      if (document.elementFromPoint(rect.left + rect.width * x, rect.top + rect.height * y) !== field) return false;
    }
    // Hit-testing skips pointer-events:none. Decline if a floating layer of that kind covers
    // the field; there is no need to change the page's styles to inspect what is underneath.
    for (const node of document.querySelectorAll('*')) {
      if (node === field || node.contains(field) || field.contains(node)) continue;
      const css = getComputedStyle(node);
      if (css.pointerEvents !== 'none' || !['absolute', 'fixed', 'sticky'].includes(css.position)) continue;
      if (visible(node) && overlaps(rect, node.getBoundingClientRect())) return false;
    }
    return true;
  }

  // Read nearby captions only. Never read values from any other form control.
  function caption(node) {
    if (!node || node.matches(interactive) || !visible(node)) return '';
    let text = '';
    for (const child of node.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) text += ' ' + child.textContent;
      else if (child.nodeType === Node.ELEMENT_NODE) text += ' ' + caption(child);
      if (text.length > 240) return '';
    }
    return text.trim();
  }

  function describe(input) {
    if (!['text', 'number', 'tel'].includes(input.type)
        || input.matches(':disabled,[readonly]') || input.closest('[aria-disabled="true"],fieldset[disabled]')
        || !visible(input)) return null;
    if (input.autocomplete && !['off', 'on', 'transaction-amount'].includes(input.autocomplete)) return null;
    const names = [input.getAttribute('aria-label') || '', input.placeholder];
    for (const id of (input.getAttribute('aria-labelledby') || '').split(/\s+/)) {
      if (id) names.push(caption(document.getElementById(id)));
    }
    for (const label of input.labels || []) names.push(caption(label));
    const fieldIdentity = [input.name, input.id, ...names].join(' ');
    if (/邮箱|手机号|验证码|密码|卡号|优惠码|身份证|email|phone|password|coupon|verification|card[\s_-]*number/iu.test(fieldIdentity)) return null;

    // A field wrapper may contain a separate visible label and currency suffix. Stop at a form
    // boundary or another control so that a different field's purpose/currency cannot be borrowed.
    let group = input.parentElement;
    for (let i = 0; group && i < 2; i++, group = group.parentElement) {
      if (group.matches('form,main,body,html') || group.querySelectorAll('input,textarea,select').length !== 1) break;
      names.push(caption(group));
    }
    const description = names.join(' ');
    return topUp.test(description) ? { input, cny: cny.test(description) && !otherCurrency.test(description) } : null;
  }

  try {
    const viewport = { left: 0, top: 0, right: document.documentElement.clientWidth, bottom: document.documentElement.clientHeight };
    if (blockedByDialog(viewport)) return false;
    const fields = Array.from(document.querySelectorAll('input'), describe).filter(Boolean);
    if (fields.length !== 1 || !fields[0].cny) return false;
    const field = fields[0].input;
    if (!unobscured(field, viewport)) return false;
    // A whole-yuan checkout may require digits only. Both representations preserve the exact cents.
    const compact = amount.replace(/\.?0+$/, '');
    const probe = document.createElement('input');
    for (const key of ['type', 'min', 'max', 'step', 'pattern']) {
      if (field.hasAttribute(key)) probe.setAttribute(key, field.getAttribute(key));
    }
    const accepted = [amount, compact].find(value => {
      if (field.maxLength >= 0 && value.length > field.maxLength) return false;
      if (field.minLength > 0 && value.length < field.minLength) return false;
      probe.value = value;
      return probe.value === value && probe.validity.valid;
    });
    if (!accepted) return false;
    // Use the native setter then the same input event a typed edit generates for controlled inputs.
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(field, accepted);
    field.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    return field.value === accepted;
  } catch {
    return false;
  }
})
