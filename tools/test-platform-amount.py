"""Exercise the optional checkout edit against local DOM fixtures in Chromium (Python Playwright)."""
import os
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/res/raw/platform_amount.js").read_text()
CHECKOUT = "https://platform.deepseek.com/top_up"
CN = '<label>充值金额（人民币）<input id="amount" inputmode="decimal"></label>'
USD = '<label>充值金额（美元）<input></label>'


def run(browser):
    context = browser.new_context()
    page = context.new_page()
    pending = {}
    context.route("**/*", lambda route: route.fulfill(
        body=CN if route.request.frame.parent_frame else pending.get("html", ""), content_type="text/html; charset=utf-8"))
    def fixture(html, url=CHECKOUT):
        pending["html"] = html
        page.goto(url)
        page.evaluate("""() => {
          window.events = [];
          for (const kind of ['input','change','click','submit'])
            document.addEventListener(kind, e => { events.push(kind); if (kind === 'submit') e.preventDefault() });
        }""")

    def fill(amount="12.34", frame=page):
        return frame.evaluate(SOURCE, amount)

    fixture('<form>' + CN + '<button>Pay</button></form><input id="other">')
    page.evaluate("""() => Object.defineProperty(document.getElementById('other'), 'value',
      { get() { throw Error('Read unrelated field') }, set() { throw Error('Wrote unrelated field') } })""")
    assert fill() and page.locator('#amount').input_value() == "12.34"
    assert page.evaluate('events') == ['input']

    fixture('<div><span id="desc">Top-up amount</span><input id="a" aria-labelledby="desc"><span>CNY</span></div>')
    assert fill() and page.locator('#a').input_value() == '12.34'
    fixture('<label>充值金额（人民币）<input id="a" type="number" min="10" max="30" step="5"></label>')
    assert not fill("3.00") and not fill("12.34") and not fill("40.00")
    assert fill("15.00")
    fixture('<label>充值金额（人民币）<input id="a" maxlength="2" pattern="[0-9]+"></label>')
    assert fill("10.00") and page.locator('#a').input_value() == "10"
    for html in [
            CN + CN, CN + USD, USD,
            '<label>充值金额（¥）<input></label>', '<label>充值金额（CNY/USD）<input></label>',
            '<label>CNY<input placeholder="Email" type="text"></label>',
            '<label>充值金额（人民币）<input autocomplete="one-time-code"></label>',
            '<div>充值金额（人民币）<input placeholder="手机号"></div>',
            '<div style="opacity:0">' + CN + '</div>',
            '<div aria-hidden="true">' + CN + '</div>', '<label>充值金额（人民币）<input readonly></label>',
            '<fieldset disabled>' + CN + '</fieldset>', '<input type="password" aria-label="充值金额（人民币）">',
            '<select><option>CNY 充值金额</option></select><input type="number">',
    ]:
        fixture(html)
        assert not fill() and page.evaluate('events') == [], html

    # No changes or input events behind a confirmation dialog, an iframe, or a masking layer.
    overlays = [
        '<div role="dialog" style="position:fixed;inset:0;background:white">Review</div>',
        '<div role="alertdialog" style="position:fixed;top:50px">Review</div>',
        '<div aria-modal="true">Confirm</div>',
        '<div role="dialog" style="display:contents"><div>Review</div></div>',
        '<dialog open>Confirm</dialog>',
        '<iframe style="position:fixed;inset:0;opacity:0.2" src="about:blank"></iframe>',
        '<div style="position:fixed;inset:0;background:transparent"></div>',
        '<div style="pointer-events:none;position:fixed;inset:0;background:white"></div>',
    ]
    for overlay in overlays:
        fixture(CN + overlay)
        assert not fill() and page.locator("#amount").input_value() == "" and page.evaluate('events') == [], overlay
    fixture('<div style="opacity:.03">' + CN + '</div>')
    assert not fill() and page.locator("#amount").input_value() == "" and page.evaluate('events') == []
    fixture(CN + '<aside hidden role="dialog"><iframe hidden src="about:blank"></iframe></aside>')
    assert fill()
    fixture(CN)
    for amount in ["0.00", "-1.00", "1e2", "10.123", "1000000000000.00", [], None]:
        assert not fill(amount)
    for url in ["https://example.invalid/top_up", "https://platform.deepseek.com/sign_in",
                "http://platform.deepseek.com/top_up", "https://other.platform.deepseek.com/top_up"]:
        fixture(CN, url)
        assert not fill()
    fixture('<iframe src="' + CHECKOUT + '"></iframe>', "https://example.invalid/")
    frame = page.frames[-1]
    frame.set_content(CN)
    assert not fill(frame=frame)
    context.close()


if __name__ == "__main__":
    with sync_playwright() as api:
        browser = api.chromium.launch(executable_path=os.getenv('CHROMIUM_PATH') or None)
        run(browser)
        browser.close()
    print("Official-origin checkout amount fixtures passed.")
