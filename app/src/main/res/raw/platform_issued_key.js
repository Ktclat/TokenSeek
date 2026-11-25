// Called only after the authenticated official form confirmed this create request.
(function (ticket, save) {
  if (window !== window.top || location.href !== 'https://platform.deepseek.com/api_keys') return null;
  const root = window.__tokenSeekKeyFormV1?.issuedRoot?.(ticket);
  if (!root || !root.isConnected) return null;
  const visible = el => {
    if (!el || !el.getClientRects().length) return false;
    for (let parent = el; parent; parent = parent.parentElement) {
      const css = getComputedStyle(parent);
      if (parent.hidden || parent.inert || parent.getAttribute('aria-hidden') === 'true' ||
          css.visibility !== 'visible' || css.display === 'none' || Number(css.opacity) === 0) return false;
    }
    return true;
  };
  const lines = [...root.querySelectorAll('.ds-copyable-text-line')].filter(visible);
  if (lines.length !== 1) return null;
  const line = lines[0], before = line.previousElementSibling, after = line.nextElementSibling;
  if (before?.childElementCount !== 0 || after?.childElementCount !== 0 || !visible(before) || !visible(after)) return null;
  if (!/^(请将此\s*API key\s*保存|Please save this API key somewhere safe)/i.test(before.textContent.trim()) ||
      !/^(提示[：:]\s*不要与他人共享你的\s*API key|Notice:\s*Do not share your API key)/i.test(after.textContent.trim())) return null;
  const actions = [...line.querySelectorAll('.ds-copyable-text-line__action button,.ds-copyable-text-line__action [role="button"],.ds-copyable-text-line__action .ds-button')].filter(visible);
  const fields = [...line.querySelectorAll('.ds-copyable-text-line__scroll > span.ds-copyable-text-line__text')].filter(visible);
  if (actions.length !== 1 || !/^(复制|已复制|copy|copied)$/i.test(actions[0].textContent.trim()) ||
      fields.length !== 1 || fields[0].childNodes.length !== 1 || fields[0].firstChild.nodeType !== Node.TEXT_NODE) return null;
  if (!save) return true;
  const secret = fields[0].firstChild.data;
  return /^sk-[a-zA-Z0-9_-]{16,256}$/.test(secret) ? secret : null;
})
