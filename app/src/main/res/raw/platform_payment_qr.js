// Locate a visible payment image. Return geometry only; the host reads the on-screen pixels.
(() => {
  if (window !== top) return null;
  const visible = (el, ignoreAria = false) => {
    if (!el || !el.getClientRects().length) return false;
    for (let node = el; node; node = node.parentElement) {
      const css = getComputedStyle(node);
      if (node.hidden || (!ignoreAria && (node.inert || node.getAttribute('aria-hidden') === 'true')) ||
          css.display === 'none' || css.visibility !== 'visible' || Number(css.opacity) < 0.1) return false;
    }
    return true;
  };
  const viewport = window.visualViewport;
  const x = viewport?.offsetLeft || 0, y = viewport?.offsetTop || 0;
  const w = viewport?.width || innerWidth, h = viewport?.height || innerHeight;
  if (!w || !h) return null;
  const caption = value => {
    if (/^(?:微信支付|WeChat Pay)$/i.test(value)) return 'wechat';
    if (/^(?:支付宝支付|支付宝|Alipay)$/i.test(value)) return 'alipay';
    return '';
  };
  // The platform renders the selected wallet's name, a 160px canvas/SVG and a short expiry notice.
  // Its hidden diagnostic canvas and other page widgets can coexist with the actual order.
  const providerIn = node => {
    if (!node || node === document.body || node === document.documentElement) return '';
    const parts = (node.innerText || '').split(/\n/).map(s => s.trim()).filter(Boolean);
    if (!parts.some(s => /扫一扫付款|扫码付款|扫码支付|二维码有效期|scan (?:the )?qr code to pay|qr code is valid/i.test(s))) return '';
    const providers = new Set(parts.map(caption).filter(Boolean));
    return providers.size === 1 ? [...providers][0] : '';
  };
  if (location.origin === 'https://platform.deepseek.com' && location.pathname === '/top_up') {
    // Mobile WeChat can show this official in-WeChat instruction without issuing a QR order.
    for (const action of document.querySelectorAll('button,[role="button"],.ds-button')) {
      if (!visible(action) || !/^(?:复制充值链接|Copy the top-up link)$/i.test((action.innerText || '').trim())) continue;
      for (let node = action.parentElement, level = 0; node && level < 10; node = node.parentElement, level++) {
        if (node === document.body) break;
        const text = node.innerText || '';
        if (text.includes('https://platform.deepseek.com/top_up') &&
            /移动端浏览器中完成微信支付|WeChat Pay in mobile browsers is not supported/i.test(text)) {
          return {wechatInApp: true};
        }
      }
    }
  }
  const candidates = [];
  for (const el of document.querySelectorAll('canvas,svg,img')) {
    const r = el.getBoundingClientRect();
    if (r.width < 72 || r.height < 72 || r.width > r.height * 1.25 || r.height > r.width * 1.25 ||
        r.left < x - 1 || r.top < y - 1 || r.right > x + w + 1 || r.bottom > y + h + 1 || !visible(el)) continue;
    const exposed = [[.24,.24],[.76,.24],[.24,.76],[.76,.76],[.5,.5]].filter(([px,py]) => {
      const hit = document.elementsFromPoint(r.left + px*r.width, r.top + py*r.height).find(node => visible(node, true));
      return hit && (el.contains(hit) || (hit.contains(el) && getComputedStyle(el).pointerEvents === 'none'));
    });
    if (exposed.length < 4) continue;
    let wallet = '';
    for (let node = el.parentElement, level = 0; node && !wallet && level < 12; node = node.parentElement, level++) {
      wallet = providerIn(node);
      if (node.matches('dialog,[role="dialog"],[aria-modal="true"]') || node === document.body) break;
    }
    candidates.push({ wallet, l:(r.left-x)/w, t:(r.top-y)/h, r:(r.right-x)/w, b:(r.bottom-y)/h });
  }
  const labelled = candidates.filter(c => c.wallet);
  const rows = labelled.length ? labelled : candidates;
  return rows.length === 1 ? rows[0] : null;
})()
