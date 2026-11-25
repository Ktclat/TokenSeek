"""Verify a device imports only the key from its own completed create form, after consent."""
import os
import shutil
from pathlib import Path
from playwright.sync_api import sync_playwright

raw = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
form = (raw / 'platform_key_form.js').read_text()
read_result = (raw / 'platform_issued_key.js').read_text()
address = 'https://platform.deepseek.com/api_keys'
# Synthetic credential-shaped sample. All browser requests are fulfilled locally.
sample = 'sk-' + 'test' * 8
markup = '<div id="form" role="dialog"><h2>Create API key</h2><label>Name<input id="name"></label><button id="create">Create</button></div>'
result = '''<h2>Create API key</h2>
<div>Please save this API key somewhere safe and accessible.</div>
<div class="ds-copyable-text-line">
  <div class="ds-copyable-text-line__scroll"><span class="ds-copyable-text-line__text"></span></div>
  <div class="ds-copyable-text-line__action"><button>Copy</button></div>
</div><div>Notice: Do not share your API key with others.</div>'''

with sync_playwright() as pw:
    browser = pw.chromium.launch(executable_path=os.getenv('CHROMIUM') or shutil.which('chromium'))
    context = browser.new_context()
    context.route('**/*', lambda r: r.fulfill(body=markup, content_type='text/html'))
    page = context.new_page()
    page.goto(address)
    page.evaluate(form)
    ticket = page.evaluate("window.__tokenSeekKeyFormV1.probe('create','','')")
    assert ticket

    def issued(save, key_ticket=ticket):
        return page.evaluate('args => (' + read_result + ')(...args)', [key_ticket, save])

    assert issued(False) is None
    assert page.evaluate("t => window.__tokenSeekKeyFormV1.stage(t,'Device')", ticket)
    page.evaluate("""([html, sample]) => {
      document.querySelector('#create').onclick = () => {
        document.querySelector('#form').innerHTML = html;
        const node = document.querySelector('.ds-copyable-text-line__text').appendChild(document.createTextNode(sample));
        window.reads = 0;
        Object.defineProperty(node, 'data', {get() {window.reads++; return sample;}});
      };
    }""", [result, sample])
    assert page.evaluate("t => window.__tokenSeekKeyFormV1.submit(t)", ticket)
    assert issued(False) is True and page.evaluate('window.reads') == 0
    assert issued(True, 'different-ticket') is None and page.evaluate('window.reads') == 0
    assert issued(True) == sample and page.evaluate('window.reads') == 1
    page.locator('.ds-copyable-text-line__action').evaluate("el => el.style.display = 'none'")
    assert issued(True) is None
    page.locator('.ds-copyable-text-line__action').evaluate("el => el.style.display = ''")
    page.locator('#form').evaluate("el => el.replaceWith(el.cloneNode(true))")
    assert issued(True) is None
    page.goto('https://example.invalid/api_keys')
    assert issued(True) is None
    browser.close()
print('Verified creation receipt, explicit import, hidden/replaced controls and wrong origin.')
