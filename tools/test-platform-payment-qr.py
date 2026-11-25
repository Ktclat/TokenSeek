"""Exercise QR geometry against the official checkout's visible and diagnostic renderers."""
from pathlib import Path
import os
import shutil

from playwright.sync_api import sync_playwright

LOCATOR = (Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/platform_payment_qr.js').read_text()
CHECKOUT = 'https://platform.deepseek.com/top_up'


def run(page):
    def load(body):
        page.route('**/*', lambda route: route.fulfill(content_type='text/html; charset=utf-8', body=(
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<style>body{margin:0}canvas,svg,img{display:block}dialog{padding:12px}</style>' + body)))
        page.goto(CHECKOUT)
        page.unroute('**/*')

    def scan():
        return page.evaluate(LOCATOR)

    hidden = '<canvas width="160" height="160" aria-hidden="true" style="opacity:0;position:absolute"></canvas>'
    for element, wallet, label in [
            ('<canvas width="160" height="160"></canvas>', 'wechat', '微信支付'),
            ('<svg width="160" height="160"><rect width="160" height="160"/></svg>', 'alipay', 'Alipay')]:
        load(f'<h1>充值</h1><div role="dialog"><h2>{label}</h2><div>扫一扫付款</div>'
             f'<div>{hidden}{element}</div><p>二维码有效期 15 分钟</p></div>')
        box = scan()
        assert box and box['wallet'] == wallet, (wallet, box)
        assert abs((box['r'] - box['l']) * 390 - 160) < 1
        assert abs((box['b'] - box['t']) * 844 - 160) < 1
        # A replaced order or an opaque overlay must not reuse the old image bounds.
        page.evaluate('''() => {
          const overlay = document.createElement('div');
          overlay.style = 'position:fixed;inset:0;background:#fff;z-index:3000'; document.body.appendChild(overlay);
        }''')
        assert scan() is None
    load(f'<div role="dialog"><h2>微信支付</h2><p>扫一扫付款</p>{hidden}</div>')
    assert scan() is None
    load('<div role="dialog"><h2>微信支付</h2><p>扫一扫付款</p>'
         '<canvas width="160" height="160"></canvas><canvas width="160" height="160"></canvas></div>')
    assert scan() is None
    load('<div style="height:1100px"></div><div role="dialog"><h2>微信支付</h2><p>扫一扫付款</p>'
         '<canvas width="160" height="160"></canvas></div>')
    assert scan() is None
    page.evaluate('scrollTo(0, document.body.scrollHeight)')
    assert scan()['wallet'] == 'wechat'
    for title, note in [
            ('复制充值链接', '暂时不支持在移动端浏览器中完成微信支付。请在微信中打开 https://platform.deepseek.com/top_up。'),
            ('Copy the top-up link', 'WeChat Pay in mobile browsers is not supported. Open https://platform.deepseek.com/top_up in WeChat.')]:
        load(f'<div role="dialog"><div>{note}</div><div><button>{title}</button></div></div>')
        assert scan() == {'wechatInApp': True}
    load('<div><button>复制充值链接</button></div>')
    assert scan() is None
    load('<iframe srcdoc="<canvas width=160 height=160></canvas>"></iframe>')
    assert scan() is None
    assert page.frames[1].evaluate(LOCATOR) is None


if __name__ == '__main__':
    with sync_playwright() as api:
        browser = api.chromium.launch(executable_path=os.getenv('CHROMIUM') or shutil.which('chromium'))
        page = browser.new_page(viewport={'width': 390, 'height': 844}, is_mobile=True, device_scale_factor=2)
        run(page)
        browser.close()
    print('Official payment QR canvas/SVG, overlay, iframe, scroll and ambiguity cases passed.')
