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
    assert entry == {'name': '安卓', 'masked': 'sk-AB12****CDEF', 'created': '2026-11-24', 'lastUsed': '从未使用', 'rename': False, 'revoke': True}
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
    assert snap()['rows'][0]['name'] == '安卓'
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
    # A screenshot-shaped fixture: two instructions, a known Tracking ID column and pinned
    # action icons. All identifiers are synthetic. Raw path geometry never grants an action.
    notes = '''<div>此列表列出了您的所有 API key。仅在创建时可以复制密钥，请妥善保存。</div>
      <div>2024-04-25 之前创建的 API key 不跟踪使用。每个账户最多可以创建 300 个 API key。</div>'''
    toolbar = '<button id="make">创建 API key</button><a id="usage" href="/transactions">按 API Key 查看用量</a>'
    glyph = '<path d="M3 3"/>'
    def icons(n):
        return (f'<button id="ed{n}" aria-controls="editor" title="编辑"><svg class="lucide-pencil">{glyph}</svg></button>'
                f'<button id="rm{n}" aria-haspopup="dialog" aria-controls="revoker"><svg><use href="#icon-trash"></use></svg></button>')
    def listing(rows, custom=False, actions_header=True):
        header = ['名称', 'Tracking ID'] + (['操作'] if actions_header else [])
        if custom:
            head = '<div class="row">' + ''.join('<div class="cell">' + col + '</div>' for col in header) + '</div>'
            data = ''.join(f'<div class="row" id="row{n}"><div class="cell">样例设备 {n}</div><div class="cell">{key}</div><div class="cell pinned">{icons(n)}</div></div>'
                           for n, key in enumerate(rows))
            body = '<div class="directory"><div class="header-group">' + head + '</div><div class="body">' + data + '</div></div>'
        else:
            head = '<tr>' + ''.join('<th>' + col + '</th>' for col in header) + '</tr>'
            body = '<table><thead>'+head+'</thead><tbody>' + ''.join(
                f'<tr id="row{n}"><td>样例设备 {n}</td><td>{key}</td><td class="pinned">{icons(n)}</td></tr>'
                for n,key in enumerate(rows)) + '</tbody></table>'
        return ('''<style>.row{display:grid;grid-template-columns:100px 185px 74px}.cell{padding:3px}
                   .pinned{position:sticky;right:0;background:white} svg{width:14px;height:14px}</style>''' +
                '<main><h1>API keys</h1>' + notes + toolbar + body + '</main>')
    ids = ['a1b2c3d4-222 ... 98', '7766bbaa-491…5c', '19ab2790-3521-44c2-b5c7-f5cea391ac21']
    def dispatch(cmd):
        return page.evaluate('c=>window.__tokenSeekKeyDirectoryV1.dispatch(c)', cmd)
    for custom in [False, True]:
        for actions_header in [True, False]:
            load(listing(ids, custom, actions_header))
            result = snap()
            assert result and result['create'] and result['usage'] and not result['previous'], (custom, actions_header, result)
            assert [r['trackingId'] for r in result['rows']] == ids and all('masked' not in r for r in result['rows'])
            assert all(r['rename'] and r['revoke'] for r in result['rows'])
    official_copy = [
        '列表内是你的全部 API key，API key 仅在创建时可见可复制，请妥善保存。',
        '不要与他人共享你的 API key，或将其暴露在浏览器或其他客户端代码中。',
        '为了保护你的帐户安全，我们可能会自动禁用我们发现已公开泄露的 API key。',
        '我们未对 2024 年 4 月 25 日前创建的 API key 的使用情况进行追踪。',
        '单个账号最多可以保留 300 个 API Key。']
    for intro in ['<div>'+''.join(official_copy)+'</div>',
                  ''.join('<div>'+line+'</div>' for line in official_copy),
                  '<div>'+''.join('<span>'+line[:3]+'</span>'+line[3:] for line in official_copy)+'</div>',
                  ''.join('<div>'+line.replace('API key','<span>API key</span>')+'</div>' for line in official_copy),
                  '<div>'+''.join(official_copy).replace('key，API','key。API').replace('帐户','账户').replace('泄露','泄漏')
                    .replace(' API key',' <span>API</span> key').replace('请妥善保存','请妥善保管')+'</div>']:
        load(listing(ids).replace(notes,intro))
        assert len(snap()['rows']) == len(ids)
        assert page.evaluate('window.__tokenSeekKeyDirectoryV1.snapshot(true).rows.length') == len(ids)
    blank_table = '<table><thead><tr><th>名称</th><th>Tracking ID</th></tr></thead><tbody></tbody></table>'
    load('<main><h1>API Keys</h1>'+toolbar+blank_table+'<div class="empty">暂无数据</div></main>')
    assert snap()['rows'] == [] and snap()['create']
    load('<main><h1>API Keys</h1>'+toolbar+'<p>尚未创建任何 API Key</p></main>')
    assert snap()['rows'] == [] and snap()['create'] and snap()['usage']
    load('<main><h1>API Keys</h1>'+toolbar+'尚未创建任何 API Key<div>sk-'+'a'*30+'</div></main>')
    assert snap() is None
    load('<main><h1>API Keys</h1>'+toolbar+'<p>暂无数据</p></main>')
    assert snap() is None  # a page-wide empty message without a recognized table is ambiguous
    load('<main><h1>API Keys</h1>'+toolbar+blank_table+'<div>Loading…</div></main>')
    assert snap() is None
    load('<main><h1>API Keys</h1>'+blank_table+'<div>暂无数据</div></main>')
    assert snap() is None  # an adjacent status alone cannot establish an actionable key directory
    # Verify no ambiguity when the viewport cannot fit all cells.
    page.set_viewport_size({'width': 340, 'height': 650})
    load(listing(ids))
    assert len(snap()['rows']) == 3
    page.evaluate('''() => {
      window.clicked=[];
      document.addEventListener('click', event => {
        const b=event.target.closest('button,a');if(!b)return; event.preventDefault();window.clicked.push(b.id);
        if(b.id.startsWith('ed')||b.id.startsWith('rm')){
          const dlg=document.createElement('div');dlg.id=b.getAttribute('aria-controls');
          dlg.setAttribute('role','dialog');dlg.innerHTML='<button id="final">Confirm</button>';
          document.body.append(dlg);
        }
      });
    }''')
    first, second, third = (r['id'] for r in snap()['rows'])
    assert dispatch('usage') == 'source' and dispatch('create') == 'form'
    page.evaluate("()=>{const b=document.querySelector('tbody');b.prepend(b.lastElementChild)}")
    assert dispatch('rename:' + second) == 'form' and page.evaluate('clicked.at(-1)') == 'ed1'
    assert page.evaluate("""([name,id])=>window.__tokenSeekKeyDirectoryV1.matchesFormOwner('rename',name,id,document.querySelector('#editor'))""",
                         ['样例设备 1', ids[1]])
    # These three different controls share one reusable portal. A name-only form cannot prove ownership.
    assert not page.evaluate("""([name,id])=>window.__tokenSeekKeyDirectoryV1.matchesFormOwner('rename',name,id,document.querySelector('#editor'),true)""",
                             ['样例设备 1', ids[1]])
    assert not page.evaluate("""([name,id])=>window.__tokenSeekKeyDirectoryV1.matchesFormOwner('rename',name,id,document.querySelector('#editor'))""",
                             ['样例设备 0', ids[0]])
    assert snap() is None and dispatch('revoke:' + second) == 'stale'
    page.locator('#editor').evaluate('el=>el.remove()')
    assert dispatch('revoke:' + third) == 'form' and page.evaluate('clicked.at(-1)') == 'rm2'
    assert page.evaluate("""([name,id])=>window.__tokenSeekKeyDirectoryV1.matchesFormOwner('revoke',name,id,document.querySelector('#revoker'))""",
                         ['样例设备 2', ids[2]])
    assert not act('revoke:' + third) and 'final' not in page.evaluate('clicked')
    load(listing(ids).replace('id="ed0" aria-controls="editor"', 'id="ed0" aria-controls="editor0"'))
    page.evaluate("""() => {document.querySelector('#ed0').addEventListener('click',()=> {
      const d=document.createElement('dialog');d.id='editor0';d.innerHTML='<button>Cancel</button>';
      document.body.append(d);d.showModal();
    })}""")
    first = snap()['rows'][0]
    assert dispatch('rename:'+first['id']) == 'form'
    assert page.evaluate("""([name,id])=>window.__tokenSeekKeyDirectoryV1.matchesFormOwner('rename',name,id,document.querySelector('#editor0'),true)""",
                         [first['name'],first['trackingId']])
    load(listing(ids).replace('aria-haspopup="dialog" aria-controls="revoker"', ''))
    row = snap()['rows'][0]
    page.evaluate("() => {window.clicks=[]; document.addEventListener('click',e=>clicks.push(e.target.id))}")
    assert row['revoke'] and dispatch('revoke:'+row['id']) == 'source'
    assert page.evaluate("document.activeElement.id") == 'rm0' and not page.evaluate('clicks')
    load(listing(ids).replace('title="编辑"', '').replace('class="lucide-pencil"', '').replace('<use href="#icon-trash"></use>',glyph))
    row = snap()['rows'][0]
    assert not row['rename'] and not row['revoke']
    assert dispatch('rename:'+row['id']) == 'source' and page.evaluate("document.activeElement.id") == 'row0'
    load(listing(ids).replace('id="ed0"', 'id="ed0" aria-label="删除"'))
    assert not snap()['rows'][0]['rename']
    for invalid_id in ['sk-'+'x'*32, 'id0123456789', 'aabbccdd-xxx ... ee', 'aabbccdd-123 .. ee', 'abc … zz']:
        load(listing([invalid_id]))
        assert snap() is None, invalid_id
    load(listing([ids[0],ids[0]]).replace('样例设备 1','样例设备 0'))
    ambiguous = snap()['rows'][0]['id']
    page.evaluate("() => {window.clicks=[];document.addEventListener('click',e=>clicks.push(e.target.id))}")
    assert dispatch('rename:'+ambiguous) == dispatch('revoke:'+ambiguous) == 'source' and not page.evaluate('clicks')
    # A separate pinned pane may sort in a different order. Join via explicit row ownership only.
    split = '<main><table><tr><th>Name</th><th>Tracking ID</th></tr>' + ''.join(
        f'<tr id="primary{n}" '+(f'aria-owns="pin{n}"' if n != 1 else '')+f'><td>example-{n}</td><td>{key}</td></tr>'
        for n,key in enumerate(ids)) + '</table><div class="fixed-right"><table><tr><th>Actions</th></tr>' + ''.join(
        f'<tr id="pin{n}"><td>{icons(n)}</td></tr>' for n in [2,0,1]) + '</table></div></main>'
    load(split)
    entries = snap()['rows']
    assert [r['rename'] for r in entries] == [True, False, True]
    page.evaluate("() => {window.clicks=[];document.addEventListener('click',e=>clicks.push(e.target.closest('button')?.id))}")
    assert dispatch('rename:'+entries[2]['id']) == 'form' and page.evaluate('clicks') == ['ed2']
    assert dispatch('revoke:'+entries[1]['id']) == 'source' and page.evaluate('clicks') == ['ed2']
    load(listing(ids).replace('</main>', '<nav aria-label="分页"><select aria-label="每页条数"><option>10</option></select>'
                              '<button id="p">‹</button><button id="n"><svg class="lucide-chevron-right"></svg></button></nav></main>'))
    assert snap()['previous'] and snap()['next']
    assert dispatch('previous') == dispatch('next') == 'page'
    load(listing(ids).replace('<td>'+ids[0]+'</td>', '<td><button>'+ids[0]+'</button></td>'))
    assert snap()['rows'][0]['trackingId'] == ids[0]
    # A projection can include both fields, or use the id when the optional mask is an empty placeholder.
    load(HTML.replace('<th>API Key</th>','<th>Tracking ID</th><th>API Key</th>').replace('<td title=', f'<td>{ids[0]}</td><td title='))
    assert snap()['rows'][0]['trackingId'] == ids[0] and snap()['rows'][0]['masked'].startswith('sk-')
    page.locator('td code').evaluate("el=>{el.textContent='—'}")
    assert 'masked' not in snap()['rows'][0]
    # Large, fully rendered page, including enough SVG descendants to exceed the old node cap.
    generated = [f'{n:08x}-f123-4a00-80ff-{n:012x}' for n in range(300)]
    load(listing(generated).replace(glyph, glyph*35))
    perf = page.evaluate("""() => {const t=performance.now();const data=window.__tokenSeekKeyDirectoryV1.snapshot();
        return [data?.rows.length, performance.now()-t, document.querySelectorAll('*').length]}""")
    assert perf[0] == 300 and perf[2] > 10000, perf
    print('300 rendered rows, %s DOM elements, snapshot %.0f ms' % (perf[2], perf[1]))
    assert snap()['rows'][-1]['trackingId'] == generated[-1]
    load(listing(generated+[ids[0]]))
    assert snap() is None
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
