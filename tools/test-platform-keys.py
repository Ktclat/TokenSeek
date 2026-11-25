"""Run with Python Playwright and Chromium. All HTTPS responses are local test fixtures."""
import os
from pathlib import Path
import shutil
import sys

from playwright.sync_api import sync_playwright


SCRIPT = Path(sys.argv[1]) if len(sys.argv) > 1 else (
    Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/platform_keys.js'
)
URL = 'https://platform.deepseek.com/api_keys'
SNAPSHOT = '() => window.__tokenSeekKeyDirectoryV1.snapshot()'
ACT = 'command => window.__tokenSeekKeyDirectoryV1.act(command)'
HEAD = '<thead><tr><th>名称</th><th>API Key</th><th>创建时间</th><th>最后使用</th><th>操作</th></tr></thead>'
ROW = '''<tr><td>安卓</td><td title="fixture-private-attribute"><code>sk-AB12****CDEF</code>
<span hidden id="never-read">fixture-private-text</span></td><td>2026-11-24</td>
<td>从未使用</td><td><button id="delete">删除</button></td></tr>'''
HTML = '''<main><h1>API Keys</h1><button id="create">创建 API key</button><table>''' + HEAD + (
    '<tbody>' + ROW + '''</tbody></table>
<button id="prev" aria-label="上一页" disabled>‹</button>
<button id="next" aria-label="下一页">›</button></main>''')
STYLE = '''<style>table{border-collapse:collapse} th,td{padding:8px;border:1px solid #aaa}
[role=row]{display:flex}[role=columnheader],[role=cell]{min-width:130px;padding:5px}</style>'''
ARIA = '''<main><div role="table" aria-colcount="2" aria-rowcount="2"><div role="row">
<span role="columnheader">Secret key</span><span role="columnheader">Key name</span></div>
<div role="row"><span role="cell">sk-…AB12</span><span role="cell">dev phone</span></div>
</div><button aria-label="Create new secret key">+</button></main>'''


def run(browser):
    source = SCRIPT.read_text()
    page = browser.new_page(viewport={'width': 760, 'height': 620})
    # The page and nested frame both use fixture markup, never a connected platform session.
    page.route('**/*', lambda route: route.fulfill(content_type='text/html', body='<!doctype html>'))
    def load(markup=HTML, url=URL):
        page.goto(url)
        page.set_content('<!doctype html>' + STYLE + markup)
        page.evaluate(source)
    def snap():
        return page.evaluate(SNAPSHOT)
    def act(command):
        return page.evaluate(ACT, command)
    load()
    # Fail the whole test if the adapter even attempts a sensitive property access.
    page.evaluate('''() => {
      window.privateReads = [];
      const original = Element.prototype.getAttribute;
      Element.prototype.getAttribute = function(name) {
        if (this.matches('td') && name === 'title') {
          privateReads.push('cell attribute'); throw new Error('fixture assertion');
        }
        return original.call(this, name);
      };
      const read = CharacterData.prototype.substringData;
      CharacterData.prototype.substringData = function(start, n) {
        if (this.parentElement?.closest('#never-read') ||
            (this.parentElement?.id === 'plaintext' && start >= 12)) {
          privateReads.push('private text'); throw new Error('fixture assertion');
        }
        return read.call(this, start, n);
      };
      Object.defineProperty(HTMLInputElement.prototype, 'value', {get(){
        privateReads.push('form');throw new Error('fixture assertion');
      }});
      window.clicks = [];
      document.addEventListener('click', event => clicks.push(event.target.id));
    }''')
    current = snap()
    assert current and current['create'] and current['next'] and not current['previous'], current
    entry = current['rows'][0]
    handle = entry.pop('id')
    assert entry == {'name': '安卓', 'masked': 'sk-AB12****CDEF', 'created': '2026-11-24', 'lastUsed': '从未使用'}
    assert page.evaluate('clicks') == []
    assert act('show:' + handle) and page.evaluate('document.activeElement.tagName') == 'TR'
    assert page.evaluate('clicks') == []
    assert not act('delete') and not act('previous') and not act('show:__proto__')
    assert not act('constructor') and not act('toString')
    assert act('next') and act('create')
    assert page.evaluate('clicks') == ['next', 'create']
    # A platform render may recycle the same row element for the next page.
    page.locator('td').first.evaluate("(el) => el.textContent = '新的名称'")
    assert not act('show:' + handle)
    assert snap()['rows'][0]['name'] == '新的名称'
    page.locator('td code').evaluate("(el) => {el.id='plaintext';el.textContent='sk-' + 'q'.repeat(36)}")
    assert snap() is None and not act('create')
    assert page.evaluate('privateReads') == []
    load(ARIA)
    data = snap()
    assert data and data['rows'][0]['masked'] == 'sk-…AB12' and data['create'], data
    load(HTML.replace(ROW, '<tr><td colspan=5>暂无数据</td></tr>'))
    assert snap()['rows'] == [] and act('create')
    load(HTML.replace('<table>', '<p>Manage the API keys you created for your applications.</p><table>'))
    assert snap() is None
    assert page.evaluate('window.__tokenSeekKeyDirectoryV1.snapshot(true).rows[0].name') == '安卓'
    assert page.evaluate("window.__tokenSeekKeyDirectoryV1.act('next', true)")
    page.evaluate("document.body.insertAdjacentHTML('beforeend','<p>Newly issued key</p>')")
    assert page.evaluate('window.__tokenSeekKeyDirectoryV1.snapshot(true)') is None
    for invalid in [
        HTML.replace(ROW, ''), HTML.replace(ROW, '<tr><td colspan=5>Loading</td></tr>'),
        HTML.replace('<th>名称</th>', '<th>Other</th>'),
        HTML.replace('<td>2026-11-24</td>', '<td>unexpected text</td>'),
        HTML.replace('sk-AB12****CDEF', 'sk-' + 'a' * 32),
        HTML.replace('sk-AB12****CDEF', '…'), HTML.replace('sk-AB12****CDEF', 'sk-****'+'0'*25),
        HTML.replace('<td>安卓</td>', '<td>sk-'+'z'*32+'</td>'),
        HTML + '<dialog open>Generated</dialog>', HTML + '<div role="dialog">Create key</div>',
        HTML + '<div role="alert">Generated</div>', HTML + '<div aria-busy="true">Loading</div>',
        HTML + '<div>'+'sk-'+'a'*32+'</div>', HTML.replace('<table>', '<p>Unrecognized confirmation</p><table>'),
        HTML + '<input value="private fixture">', HTML + '<a href="/sign_in">Sign in</a>',
        HTML + '<div style="position:fixed;top:0;left:0;width:100%;height:100%;pointer-events:none"></div>',
        HTML + '<div style="position:fixed;bottom:0;right:0;width:100px;height:50px">Notice</div>',
        HTML.replace('</main>', '<pre>Generated key</pre></main>'),
        HTML.replace('<table>', '<table aria-rowcount="20">'), HTML + '<table><tr><td>?</td></tr></table>',
        ARIA.replace('role="columnheader"', 'role="heading"', 1),
    ]:
        load(invalid)
        assert snap() is None and not act('create'), invalid
    load(HTML + '<button hidden>Create key</button><dialog>Hidden modal</dialog>')
    assert snap() is not None
    load(HTML.replace('id="next"', 'id="next" title="Delete"'))
    assert not snap()['next'] and not act('next')
    load(HTML.replace('</main>', '<button>创建 API Key</button></main>'))
    assert not snap()['create'] and not act('create')
    load(HTML.replace('id="create"', 'id="create" hidden'))
    assert not snap()['create'] and not act('create')
    load(HTML.replace('disabled>‹', '>‹'))
    assert snap()['previous'] and act('previous')
    load()
    page.evaluate("history.pushState({}, '', '/sign_in')")
    assert snap() is None and not act('create')
    for other in [URL + '/other', URL+'/', 'https://platform.deepseek.com.evil.test/api_keys',
                  'http://platform.deepseek.com/api_keys', 'https://platform.deepseek.com:8443/api_keys']:
        load(url=other)
        assert not page.evaluate('Boolean(window.__tokenSeekKeyDirectoryV1)'), other
    load()
    with page.expect_event('framenavigated'):
        page.evaluate("() => {const f=document.createElement('iframe');f.src='/api_keys';document.body.append(f)}")
    child = page.frames[-1]
    child.evaluate(source)
    assert not child.evaluate('Boolean(window.__tokenSeekKeyDirectoryV1)')
    page.close()
    print('PASS: official current page, CN/EN tables, permitted user actions, empty/unknown, overlays, credential guards, origin and frame isolation')


if __name__ == '__main__':
    with sync_playwright() as pw:
        chromium = os.environ.get('CHROMIUM') or shutil.which('chromium')
        browser = pw.chromium.launch(executable_path=chromium, args=['--no-sandbox'])
        try:
            run(browser)
        finally:
            browser.close()
