"""Check key-form adaptation without accessing a platform account or creating a real key."""
import os
import shutil
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/res/raw/platform_key_form.js').read_text()
HOST = (ROOT / 'app/src/main/java/io/tokenseek/app/OfficialKeyForm.java').read_text()
PAGE = 'https://platform.deepseek.com/api_keys'
NAME = 'Test key'
IDENT = 'da370001-101 ... 01'
TITLES = {'create': 'Create API key', 'rename': 'Rename API key', 'revoke': 'Delete API key'}
BUTTONS = {'create': 'Create', 'rename': 'Save', 'revoke': 'Delete'}


def markup(action, target=True, input_html=None, extra=''):
    fields = f'<p>{NAME}</p><p>{IDENT}</p>' if target else ''
    if input_html is None:
        input_html = '' if action == 'revoke' else '<label for="name">Key name</label><input id="name">'
    return (f'<div role="dialog" id="box"><h2>{TITLES[action]}</h2>{fields}{input_html}{extra}'
            f'<button id="yes">{BUTTONS[action]}</button><button>Cancel</button></div>')


def run(browser):
    ctx = browser.new_context(viewport={'width': 390, 'height': 760})
    page = ctx.new_page()
    content = {}
    ctx.route('**/*', lambda r: r.fulfill(body=content.get('html', ''), content_type='text/html; charset=utf-8'))

    def fixture(html, url=PAGE):
        content['html'] = html
        page.goto(url)
        page.evaluate(SRC)
        page.evaluate("""() => {
          window.calls=0;
          document.addEventListener('click', e => { if (e.target.id === 'yes') window.calls++ });
          document.addEventListener('submit', e => e.preventDefault());
        }""")

    def invoke(method, *args):
        return page.evaluate('([method,args]) => window.__tokenSeekKeyFormV1?.[method](...args) ?? null', [method, args])

    def probe(action):
        return invoke('probe', action, '' if action == 'create' else NAME, '' if action == 'create' else IDENT)

    for action in TITLES:
        fixture(markup(action))
        ticket = probe(action)
        assert ticket and len(ticket) == 36, action
        assert page.evaluate('calls') == 0 and not invoke('submit', ticket)
        assert invoke('stage', ticket, '' if action == 'revoke' else 'New name') and page.evaluate('calls') == 0
        assert invoke('submit', ticket) and page.evaluate('calls') == 1
        assert not invoke('submit', ticket) and not invoke('stage', ticket, 'Other')
        assert not invoke('completed', ticket)
        page.evaluate("() => { box.remove();document.body.insertAdjacentHTML('beforeend','<p role=\"status\">API key deleted successfully</p>') }")
        assert invoke('completed', ticket) == (action == 'revoke')

    fixture(markup('rename', input_html='<label>Key name<input id="name"></label>'))
    ticket = probe('rename')
    assert ticket and invoke('stage', ticket, 'Changed')
    page.locator('#name').fill('Someone else edited')
    assert not invoke('submit', ticket) and page.evaluate('calls') == 0

    fixture(markup('rename')); ticket = probe('rename')
    page.locator('#yes').evaluate('el => el.replaceWith(el.cloneNode(true))')
    assert not invoke('stage', ticket, 'Changed')
    fixture(markup('revoke')); ticket = probe('revoke'); assert invoke('stage', ticket, '')
    page.locator('#box p').last.evaluate("el => el.textContent='different-target'")
    assert not invoke('submit', ticket) and page.evaluate('calls') == 0
    fixture(markup('revoke')); ticket = probe('revoke'); invoke('cancel',ticket)
    assert not invoke('stage', ticket, '') and not invoke('submit', ticket)

    cases = [markup('revoke',False), markup('create'), markup('revoke')*2,
             markup('revoke',extra='<button>Confirm</button>'),markup('revoke',extra='<input type="checkbox">'),
             markup('revoke').replace(f'<p>{IDENT}</p>',f'<p hidden>{IDENT}</p>'),markup('revoke').replace('id="yes"','formaction="https://example.invalid" id="yes"')]
    for html in cases:
        fixture(html)
        assert probe('revoke') is None, html
    fixture(markup('revoke',False,extra=f'<p>{NAME}</p>'))
    page.evaluate("""() => { window.__tokenSeekKeyDirectoryV1={matchesFormOwner(c,n,i,d){return d.id==='box'&&c==='revoke'&&n==='Test key'&&i==='da370001-101 ... 01'}} }""")
    assert probe('revoke')

    # Two same-named keys can use one dialog. A displayed, different Tracking ID always wins
    # over an opener's ARIA relationship, including when the site reuses the same controls.
    display = f'<dl><dt>Name</dt><dd>{NAME}</dd><dt>Tracking ID</dt><dd id="shown-id">{IDENT}</dd></dl>'
    for changed_at in ['probe', 'stage', 'submit']:
        fixture(markup('revoke', False, extra=display))
        page.evaluate("window.__tokenSeekKeyDirectoryV1={matchesFormOwner(){return true}}")
        def replace_id():
            page.locator('#shown-id').evaluate("el => { el.textContent='da370002-102 ... 02' }")
        if changed_at == 'probe':
            replace_id()
            assert probe('revoke') is None
        else:
            ticket = probe('revoke')
            assert ticket
            if changed_at == 'stage':
                replace_id()
                assert not invoke('stage', ticket, '')
            else:
                assert invoke('stage', ticket, '')
                replace_id()
                assert not invoke('submit', ticket)
        assert page.evaluate('calls') == 0
    fixture(markup('revoke', False, extra=f'<p>{NAME}</p>'))
    page.evaluate("window.__tokenSeekKeyDirectoryV1={matchesFormOwner(c,n,i,d,unique){return !unique}}")
    assert probe('revoke') is None

    for body in [markup('create',input_html='<input type="password" aria-label="Name">'),
                 markup('create',extra='<textarea readonly id="secret"></textarea>'),markup('create',extra='<pre id="secret"></pre>'),
                 markup('create',input_html='<input aria-label="API key">')]:
        fixture(body)
        page.evaluate("""() => { window.reads=0;const node=document.querySelector('#secret,input');
          for(const prop of ['value','textContent','innerText'])Object.defineProperty(node,prop,{get(){window.reads++;throw Error('private field read')}}); }""")
        assert probe('create') is None and page.evaluate('reads') == 0
    fixture(markup('create')); ticket = probe('create'); assert invoke('stage',ticket,'Example')
    page.evaluate("""() => { yes.onclick=()=>{box.innerHTML='<h2>Copy your secret key</h2><input readonly id="secret">';
      Object.defineProperty(secret,'value',{get(){throw Error('read one-time credential')}})}}""")
    assert invoke('submit',ticket) and not invoke('completed',ticket)
    fixture(markup('rename')); ticket = probe('rename'); assert invoke('stage',ticket,'new')
    page.evaluate("history.replaceState(null,'','/sign_in')")
    assert not invoke('submit',ticket) and page.evaluate('calls') == 0
    fixture(markup('revoke')+'<iframe src="about:blank"></iframe>'); iframe=page.frames[-1]; iframe.evaluate(SRC)
    assert iframe.evaluate('typeof window.__tokenSeekKeyFormV1') == 'undefined'
    for url in [PAGE+'?other=1', PAGE+'#other','https://platform.deepseek.com/sign_in','https://other.example/api_keys']:
        fixture(markup('create'),url); assert probe('create') is None

    # The WebView can navigate between a host-side URL check and evaluation. Every Java invocation
    # tests the live origin/path in JavaScript before handing its ticket or label to the page API.
    assert '"window === window.top && location.href === " + JSONObject.quote(PAGE)' in HOST
    assert '"(" + ON_PAGE + ") && " + API + "?." + method' in HOST
    assert '"(" + ON_PAGE + ") && " + API + "?.cancel("' in HOST
    page.evaluate("() => { window.__tokenSeekKeyFormV1 = new Proxy({}, {get(){window.calls++; return () => true}}); }")
    for cmd in ['stage("test","label")','submit("test")','completed("test")','cancel("test")']:
        assert not page.evaluate(f'(window === window.top && location.href === "{PAGE}") && window.__tokenSeekKeyFormV1?.{cmd}')
    assert page.evaluate('calls') == 0
    ctx.close()


if __name__ == '__main__':
    with sync_playwright() as api:
        browser = api.chromium.launch(executable_path=os.getenv('CHROMIUM') or shutil.which('chromium'))
        run(browser)
        browser.close()
    print('Official key-form Chromium fixtures passed.')
