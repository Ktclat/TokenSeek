(() => {
  if (window !== window.top || location.origin !== 'https://appassets.androidplatform.net') return;
  const host = window.TokenSeekHost;
  const token = '__TOKEN__';
  // Use the same theme for system bars when the user overrides the device theme.
  document.addEventListener('DOMContentLoaded', () => {
    const syncTheme = () => host.setDarkTheme?.(token, document.body.hasAttribute('data-ds-dark-theme'));
    new MutationObserver(syncTheme).observe(document.body, {
      attributes: true,
      attributeFilter: ['data-ds-dark-theme']
    });
    syncTheme();
    if (typeof host.setDrawerGesture === 'function') {
      const syncEdge = () => host.setDrawerGesture(token, document.body.hasAttribute('data-tokenseek-drawer-gesture'));
      new MutationObserver(syncEdge).observe(document.body, { attributes: true, attributeFilter: ['data-tokenseek-drawer-gesture'] });
      syncEdge();
    }
  }, { once: true });

  Object.defineProperty(window, 'tokenSeekSetWorkspace', {
    value: id => host.setWorkspace?.(token, id)
  });
  Object.defineProperty(window, 'TokenSeekSecrets', {
    value: Object.freeze({
      manageKeys: (action, id, name, secret) => host.manageKeys(token, action, id, name, secret),
      manageProfileKeys: (profile, action, id, name, secret) =>
        typeof host.manageProfileKeys === 'function'
          ? host.manageProfileKeys(token, profile, action, id, name, secret)
          : profile === 'default' ? host.manageKeys(token, action, id, name, secret) : ''
    })
  });

  if (typeof host.showPreferences === 'function' && typeof host.scaleText === 'function') {
    Object.defineProperty(window, 'TokenSeekSettings', {
      value: Object.freeze({
        present: screen => host.showPreferences(token, screen),
        setTextScale: percent => host.scaleText(token, percent)
      })
    });
  }
  if (typeof host.showFunding === 'function') {
    Object.defineProperty(window, 'TokenSeekFunding', {
      value: Object.freeze({
        present: screen => host.showFunding(token, screen),
        checkout: typeof host.openFunding === 'function' ? amount => host.openFunding(token, amount) : undefined
      })
    });
  }

  Object.defineProperty(window, 'tokenSeekHandleBack', {
    value: () => {
      if (document.querySelector('[role="dialog"], [role="listbox"], [role="menu"]')) {
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        return true;
      }
      return !window.dispatchEvent(new Event('tokenseek:back', { cancelable: true }));
    }
  });

  let picker;
  Object.defineProperty(window, 'tokenSeekPickerResult', {
    value: status => {
      if (picker) picker(status);
    }
  });
  async function saveBlob(blob, name) {
    if (picker) throw new Error('请先完成当前保存操作');
    try {
      const result = await new Promise(resolve => {
        picker = resolve;
        if (!host.chooseDocument(token, name, blob.type)) resolve('error');
      });
      if (result === 'cancelled') return;
      if (result !== 'selected') throw new Error('无法选择保存位置');
      // Keep encoded chunks within the JS bridge and Android binder limits.
      const size = 24 * 1024;
      for (let offset = 0; offset < blob.size; offset += size) {
        const chunk = new Uint8Array(await blob.slice(offset, offset + size).arrayBuffer());
        if (!host.writeDocument(token, btoa(String.fromCharCode(...chunk)))) {
          throw new Error('无法写入所选文件');
        }
      }
      if (!host.closeDocument(token)) throw new Error('无法完成保存');
    } finally {
      host.closeDocument(token);
      picker = undefined;
    }
  }
  Object.defineProperty(window, 'TokenSeekFiles', { value: Object.freeze({ saveBlob }) });

  function openWebLink(input) {
    try {
      const url = new URL(input, location.href);
      if (!['https:', 'http:'].includes(url.protocol) || url.origin === location.origin) return false;
      const fromPage = document.querySelector('[data-tokenseek-page]')?.dataset.tokenseekPage ?? '';
      host.openLink(token, url.href, fromPage);
      return true;
    } catch {
      return false;
    }
  }

  // Some reference buttons use window.open; ordinary navigation goes via WebViewClient.
  window.open = url => { if (url) openWebLink(url); return null; };
  document.addEventListener('click', event => {
    const anchor = event.target.closest?.('a[href]');
    if (!anchor) return;
    if (anchor.target === '_blank' && openWebLink(anchor.href)) {
      event.preventDefault();
    } else if (anchor.href.startsWith('blob:' + location.origin + '/') ||
               (anchor.hasAttribute('download') && anchor.href.startsWith('data:'))) {
      event.preventDefault();
      fetch(anchor.href).then(response => response.blob()).then(blob =>
        saveBlob(blob, anchor.download || anchor.textContent?.trim() || 'attachment')
      ).catch(error => host.showError(token, error.message));
    }
  }, true);
})();
