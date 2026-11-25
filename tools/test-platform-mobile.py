"""Mobile layout checks against intercepted HTML fixtures. Requires Python Playwright and Chromium."""
import os
from pathlib import Path
import shutil
import sys

from playwright.sync_api import sync_playwright


SCRIPT = Path(sys.argv[1]) if len(sys.argv) > 1 else (
    Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/platform_mobile.js'
)
SITE = 'https://platform.deepseek.com'
PAGE = '''<!doctype html><html><head><meta name="viewport" content="width=980, maximum-scale=1, user-scalable=no, viewport-fit=cover">
<style>
body {font:16px sans-serif;margin:8px}
table {width:850px;min-width:850px;border-collapse:collapse}
th,td {white-space:nowrap;min-width:200px;border:1px solid}
[role=dialog] {position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);
width:920px;height:1100px;min-height:1100px;background:white;border:2px solid}
.panel-content {height:1250px}
</style></head><body><nav><a href="/api_keys">Keys</a></nav>
<table><tr><th>Name</th><th>Created</th><th>Last used</th><th>Actions</th></tr>
<tr><td>test</td><td>Yesterday</td><td>Today</td><td><button id="right">Details</button></td></tr></table>
<button id="open" onclick="document.getElementById('panel').hidden=false">Show</button>
<section role="dialog" hidden id="panel">
<form><label>Name <input name="name"></label><div class="panel-content"></div>
<button id="confirm" type="button" onclick="this.dataset.clicked='yes'">Confirm</button></form>
</section></body></html>'''


def run(browser):
    source = SCRIPT.read_text()
    context = browser.new_context(is_mobile=True, has_touch=True, viewport={'width': 320, 'height': 580})
    context.route('**/*', lambda r: r.fulfill(body=PAGE, content_type='text/html'))
    page = context.new_page()
    for path in ['/', '/sign_in', '/sign_up', '/api_keys', '/top_up', '/transactions']:
        page.goto(SITE + path)
        page.evaluate(source)
        page.wait_for_function("document.querySelector('table').hasAttribute('data-tokenseek-wide-table')")
        meta = page.locator('meta[name=viewport]').get_attribute('content')
        assert 'width=device-width' in meta and 'maximum-scale' not in meta and 'viewport-fit=cover' in meta
    table = page.locator('table')
    assert table.evaluate('el=>el.scrollWidth>el.clientWidth && el.getBoundingClientRect().right<=321')
    table.evaluate('el=>{el.scrollLeft=el.scrollWidth}')
    page.locator('#right').tap()
    page.locator('#open').tap()
    panel = page.get_by_role('dialog')
    page.wait_for_function("document.querySelector('[role=dialog]').hasAttribute('data-tokenseek-large-dialog')")
    assert panel.evaluate('el=>{const r=el.getBoundingClientRect();return r.width<=320&&r.height<=580&&r.left>=0&&r.top>=0}')
    assert panel.evaluate('el=>el.scrollHeight>el.clientHeight')
    panel.get_by_role('textbox').fill('Example')
    panel.evaluate('el=>{el.scrollTop=el.scrollHeight}')
    panel.locator('#confirm').tap()
    assert panel.locator('#confirm').get_attribute('data-clicked') == 'yes'
    assert panel.get_by_role('textbox').input_value() == 'Example'
    page.evaluate(source)
    assert page.locator('style').count() == 2
    page.evaluate("history.pushState({}, '', '/some-other-service')")
    page.wait_for_function("!document.querySelector('[data-tokenseek-wide-table]')")
    assert page.locator('meta[name=viewport]').get_attribute('content').startswith('width=980')
    page.evaluate("history.pushState({}, '', '/top_up')")
    page.wait_for_function("document.querySelector('[data-tokenseek-wide-table]')")
    # Site-provided scrolling remains in charge; the table itself retains its original display.
    page.goto(SITE+'/api_keys')
    page.evaluate('''() => {const table=document.querySelector('table'), pane=document.createElement('div');
      pane.style.cssText='width:280px;overflow-x:auto';table.before(pane);pane.append(table)}''')
    page.evaluate(source)
    assert not page.locator('table').evaluate('el=>el.hasAttribute("data-tokenseek-wide-table")')
    # A compact responsive layout is unchanged.
    page.goto(SITE+'/sign_in')
    page.set_content('<meta name="viewport" content="width=device-width, initial-scale=1"><form><input><button>Sign in</button></form>')
    page.evaluate(source)
    assert not page.locator('[data-tokenseek-wide-table],[data-tokenseek-large-dialog]').count()
    assert page.locator('meta').first.get_attribute('content') == 'width=device-width, initial-scale=1'
    for url in ['https://pay.example.test/pay', SITE+'/other', 'http://platform.deepseek.com/top_up']:
        page.goto(url)
        page.evaluate(source)
        assert not page.evaluate('!!window.__tokenSeekMobilePlatformV1'), url
    page.goto(SITE+'/top_up')
    with page.expect_event('framenavigated'):
        page.evaluate("() => {const f=document.createElement('iframe');f.src='/api_keys';document.body.append(f)}")
    child = page.frames[-1]
    child.evaluate(source)
    assert not child.evaluate('!!window.__tokenSeekMobilePlatformV1')
    context.close()
    print('PASS: six allowlisted routes, 320px table and keyboard-preserving dialog scroll, actual taps, zoom, SPA cleanup and isolated payment/frame origins')


if __name__ == '__main__':
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=os.environ.get('CHROMIUM') or shutil.which('chromium'),
                                     args=['--no-sandbox'])
        try:
            run(browser)
        finally:
            browser.close()
