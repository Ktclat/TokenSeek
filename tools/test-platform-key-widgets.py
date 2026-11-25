"""Offline integration of the public platform's key-table and form presentation."""
import os
from pathlib import Path
import shutil

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
TABLE = (ROOT / 'tools/fixtures/platform-key-widgets.html').read_text()
PLATFORM = 'https://platform.deepseek.com/api_keys'
ASSETS = ROOT / 'app/src/main/res/raw'
NAME = '示例'
TRACKING = 'da370009-103 … 000000000001'
DELETE_NOTICE = ('该 API key 将立即被禁用。使用此密钥发出的 API 请求将被拒绝，这可能会导致仍然依赖它的任何系统崩溃。 '
                 '一旦删除，你将无法再查看或修改此 API key。')


def run(browser):
    page = browser.new_page(viewport={'width': 390, 'height': 810})
    page.route('**/*', lambda route: route.fulfill(body=TABLE, content_type='text/html; charset=utf-8'))

    def setup():
        page.goto(PLATFORM)
        for name in ('platform_keys.js', 'platform_key_form.js'):
            page.evaluate((ASSETS / name).read_text())

    def directory(method, *args):
        return page.evaluate('([m, a]) => window.__tokenSeekKeyDirectoryV1[m](...a)', [method, args])

    def form(method, *args):
        return page.evaluate('([m, a]) => window.__tokenSeekKeyFormV1[m](...a)', [method, args])

    setup()
    data = directory('snapshot', True)
    assert data and len(data['rows']) == 2 and data['create'] and data['usage'], data
    first, managed = data['rows']
    assert first['name'] == NAME and first['trackingId'] == TRACKING
    assert first['lastUsed'] == '2026-01-14' and first['rename'] and first['revoke']
    assert managed['managed'] and managed['name'] == 'DeepSeek Harness' and not managed['revoke']
    assert directory('dispatch', 'revoke:' + managed['id'], True) == 'stale'
    page.locator('#create').evaluate("""el => {
      el.classList.add('ds-button--disabled');
      el.parentElement.insertAdjacentHTML('beforebegin','<div class="ds-alert ds-alert--warning">你还未完成实名认证，请认证后再创建 API Key<div role="button" class="ds-button">实名认证</div></div>');
    }""")
    gated = directory('snapshot', True)
    assert gated and gated['verification'] and not gated['create'] and len(gated['rows']) == 2
    page.locator('#remove path').evaluate("p => p.setAttribute('d', p.getAttribute('d').replace('4.84067', '4.84068'))")
    assert not directory('snapshot', True)['rows'][0]['revoke']

    for language, message in [('zh','暂无 API key，你可以 <span>创建 API key</span>'),
                              ('en','No API key, you can <span>Create new API key</span>.')]:
        setup()
        if language == 'en':
            for i, title in enumerate(['Name','Tracking ID','Key','Created','Last used','']):
                page.locator('th').nth(i).evaluate('(e, txt) => e.textContent=txt', title)
            page.locator('#create').evaluate("e => e.textContent='Create new API key'")
            page.locator('#usage').evaluate("e => e.textContent='API Key Usage'")
        page.locator('tbody').evaluate('''(el, message) => {
          el.innerHTML = [0,1,2].map(i => '<tr><td class="ds-table-td ds-table-td--empty" colspan="6"><span '+
            'class="ds-table-empty" style="visibility:'+(i===1?'visible':'hidden')+'" aria-hidden="'+(i===1)+'">'+
            message+'</span></td></tr>').join('');
        }''', message)
        data = directory('snapshot', True)
        assert data and data['rows'] == [] and data['create'] and data['usage'], data
        page.locator('.ds-table-empty').nth(1).evaluate("e => e.textContent='加载失败，请重试'")
        assert directory('snapshot', True) is None

    for kind, source, title, submit in [('create','create','创建 API key','创建'),
                                        ('rename','change','修改 API key','保存'), ('revoke','remove','删除 API key','删除')]:
        setup()
        first = directory('snapshot', True)['rows'][0]
        inner = DELETE_NOTICE if kind == 'revoke' else (
            '<div class="ds-form-item"><div><span class="ds-form-item__label-text">'
            + ('名称' if kind == 'rename' else '单个账号最多可以保留 300 个 API Key。') +
            '</span></div><input placeholder="输入 API key 的名称" value="'+(NAME if kind == 'rename' else '')+'"></div>')
        markup = ('<div role="dialog" class="ds-modal-content ds-modal-content--dialog"><div class="ds-modal-content__header-wrapper">'
                  '<div class="ds-modal-content__title">'+title+'</div><div role="button" class="ds-modal-content__close"></div></div>'
                  '<div class="ds-modal-content__main">'+inner+'</div><div class="ds-modal-content__footer">'
                  '<div role="button">取消</div><div role="button" id="approve">'+submit+'</div></div></div>')
        page.locator('#'+source).evaluate('''(el, html) => {
          window.sent=0;
          el.addEventListener('click', () => {
            document.body.insertAdjacentHTML('beforeend',html);
            document.querySelector('#approve').addEventListener('click', () => window.sent++);
          });
        }''', markup)
        action = kind if kind == 'create' else kind+':'+first['id']
        assert directory('dispatch', action, True) == 'form', kind
        ticket = form('probe', kind, '' if kind=='create' else NAME, '' if kind=='create' else TRACKING)
        assert ticket, kind
        if kind == 'create':
            assert form('issuedRoot', ticket) is None
        assert form('stage', ticket, '' if kind=='revoke' else '新名称'), kind
        assert form('submit', ticket) and not form('submit', ticket) and page.evaluate('sent') == 1
        if kind == 'create':
            assert page.evaluate('t=>window.__tokenSeekKeyFormV1.issuedRoot(t)===document.querySelector("[role=dialog]")', ticket)
            page.locator('[role=dialog]').evaluate("e => e.replaceWith(e.cloneNode(true))")
            assert form('issuedRoot', ticket) is None
        else:
            assert form('issuedRoot', ticket) is None
            page.locator('[role=dialog]').evaluate("e=>e.remove()")
            status = 'API key 已删除' if kind == 'revoke' else 'API key 已修改'
            page.evaluate('s => document.body.insertAdjacentHTML("beforeend", \'<div class="ds-toast ds-toast--success"><div class="ds-toast__content">\'+s+\'</div></div>\')', status)
            assert form('completed', ticket)
    page.close()
    print('Verified official CN/EN table, empty and error templates, managed keys, current icons and native form tickets.')


if __name__ == '__main__':
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=os.getenv('CHROMIUM') or shutil.which('chromium'))
        try:
            run(browser)
        finally:
            browser.close()
