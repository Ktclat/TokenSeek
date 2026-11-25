package io.tokenseek.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class PaymentHandoffTest {
    private static final String TOP_UP = "https://platform.deepseek.com/top_up";
    private static final String ALIPAY = "alipays://platformapi/startapp?appId=123&order=AZ%2B%2F%3D";
    private static final String ALIPAY_WRAPPER = "https://render.alipay.com/p/s/i/?scheme="
            + "alipays%3A%2F%2Fplatformapi%2Fstartapp%3FappId%3D123%26order%3DAZ%252B%252F%253D";
    private static final String CASHIER = "https://mclient.alipay.com/cashier/mobilepay.htm?order=sample";
    private static final String WECHAT = "weixin://wap/pay?prepayid=example&sign=AZ%2F%3D";
    private static final String MWEB = "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=example";

    @Test public void alipayDirectAndWrappedPreserveOpaqueOrderEncoding() {
        PaymentHandoff direct = PaymentHandoff.from(TOP_UP, ALIPAY, null, null);
        assertEquals("com.eg.android.AlipayGphone", direct.provider.packageName);
        assertEquals(ALIPAY, direct.appUrl);
        assertNull(direct.fallback);
        PaymentHandoff wrapped = PaymentHandoff.from(CASHIER, ALIPAY_WRAPPER, direct.provider.packageName, null);
        assertEquals(ALIPAY, wrapped.appUrl);
        assertEquals(ALIPAY_WRAPPER, wrapped.fallback);
    }

    @Test public void alipayWrapperWithLiteralSchemeIsForwardedIntactToTheOfficialPackage() {
        // This second published wrapper form has unescaped ampersands. Their ownership cannot
        // be determined by a generic query parser. The signed content must survive unchanged.
        String link = "https://render.alipay.com/p/s/i/?scheme=alipays://platformapi/startapp"
                + "?appId=123&page=%2Fcashier%2Findex&order=AZ%2B%2F%3D&chInfo=sample";
        PaymentHandoff handoff = PaymentHandoff.from(TOP_UP, link, null, null);
        assertEquals("com.eg.android.AlipayGphone", handoff.provider.packageName);
        assertTrue(handoff.isHttpsAppLink());
        assertEquals(link, handoff.appUrl);
        assertEquals(link, handoff.fallback);
        String extraParameters = ALIPAY_WRAPPER + "&chInfo=sample";
        assertEquals(extraParameters, PaymentHandoff.from(TOP_UP, extraParameters, null, null).appUrl);
        assertNull(PaymentHandoff.from("https://unrelated.example/", link, null, null));
        assertNull(PaymentHandoff.from(TOP_UP, link, "com.example.wallet", null));
    }

    @Test public void ordinaryCashierAndRechargeCanSwitchToTheBrowserWithoutHandingOffOrderData() {
        String gateway = "https://openapi.alipay.com/gateway.do";
        for (String page : new String[]{TOP_UP, CASHIER, gateway, ALIPAY_WRAPPER, MWEB}) {
            assertTrue(page, PaymentHandoff.canOpenPaymentBrowser(page));
        }
        assertNull(PaymentHandoff.from(TOP_UP, gateway, null, null));
        assertNull(PaymentHandoff.from(TOP_UP, CASHIER, null, null));
        for (String other : new String[]{"https://platform.deepseek.com/sign_in", "https://alipay.com.example.org/",
                "http://mclient.alipay.com/", "https://mclient.alipay.com:8443/", "about:blank", null}) {
            assertFalse(other, PaymentHandoff.canOpenPaymentBrowser(other));
        }
        assertEquals("https://platform.deepseek.com/top_up", PaymentHandoff.MERCHANT);
    }

    @Test public void wechatH5PagesStayInBrowserAndOnlyPaymentSchemesOpenWallet() {
        assertTrue(PaymentHandoff.isWechatH5(MWEB));
        assertNull(PaymentHandoff.from(TOP_UP, MWEB, null, null));
        assertNull(PaymentHandoff.from(TOP_UP, CASHIER, null, null));
        assertTrue(PaymentHandoff.isAlipayCashier(CASHIER));
        PaymentHandoff handoff = PaymentHandoff.from(MWEB, WECHAT, "com.tencent.mm", MWEB);
        assertEquals("com.tencent.mm", handoff.provider.packageName);
        assertEquals(WECHAT, handoff.appUrl);
        assertEquals(MWEB, handoff.fallback);
    }

    @Test public void alipayQrUsesOnlyTheOriginalHttpsOrderAndTrustedCheckout() {
        String qr = "https://qr.alipay.com/bax020000000001?order=AA%2B%2FB%3D&extra=x%26y";
        assertTrue(PaymentHandoff.isAlipayQr(qr));
        PaymentHandoff handoff = PaymentHandoff.from(TOP_UP, qr, null, null);
        assertEquals(PaymentHandoff.Provider.ALIPAY, handoff.provider);
        assertEquals(qr, handoff.appUrl);
        assertEquals(qr, handoff.fallback);
        assertNull(PaymentHandoff.from(TOP_UP, qr, "com.tencent.mm", null));
        assertNull(PaymentHandoff.from("https://platform.deepseek.com/sign_in", qr, null, null));
        for (String invalid : new String[]{"http://qr.alipay.com/bax020000000001",
                "https://qr.alipay.com.evil.example/bax020000000001", "https://name@qr.alipay.com/bax020000000001",
                "https://qr.alipay.com:444/bax020000000001", "https://qr.alipay.com/", qr + "#elsewhere",
                "https://qr.alipay.com/other/checkout", "https://qr.alipay.com/%2F%2Fother.example"}) {
            assertFalse(invalid, PaymentHandoff.isAlipayQr(invalid));
        }
    }

    @Test public void wechatH5ReceivesOnlyTheOriginFromTheActualMerchant() {
        assertEquals(PlatformNavigation.BASE + "/", PaymentHandoff.wechatMerchantReferer(TOP_UP, MWEB));
        assertEquals(PlatformNavigation.BASE + "/", PaymentHandoff.wechatMerchantReferer(TOP_UP + "?opaque=private", MWEB));
        assertNull(PaymentHandoff.wechatMerchantReferer(CASHIER, MWEB));
        assertNull(PaymentHandoff.wechatMerchantReferer(TOP_UP, MWEB.replace("wx.tenpay.com", "wx.tenpay.com.evil.example")));
        assertNull(PaymentHandoff.wechatMerchantReferer(TOP_UP, MWEB.split("[?]")[0]));
        assertNull(PaymentHandoff.wechatMerchantReferer(TOP_UP, WECHAT));
        assertNull(PaymentHandoff.wechatMerchantReferer("https://platform.deepseek.com.evil.example/top_up", MWEB));
        assertNull(PaymentHandoff.wechatMerchantReferer("https://platform.deepseek.com/api_keys", MWEB));
        assertNull(PaymentHandoff.from(TOP_UP, "weixin://wxpay/bizpayurl?pr=sample", null, null));
    }

    @Test public void supportsSingularAlipayStartAppAndRequiresTheMatchingPackage() {
        String scheme = ALIPAY.replace("alipays://", "alipay://");
        assertEquals(scheme, PaymentHandoff.from(CASHIER, scheme, "com.eg.android.AlipayGphone", null).appUrl);
        assertNull(PaymentHandoff.from(CASHIER, scheme, "com.fake.wallet", null));
    }

    @Test public void originatingPageAndAnyDeclaredPackageMustMatchTheProvider() {
        for (String source : new String[]{"https://wx.tenpay.com.example.org/", "https://example.org/",
                "https://guest@platform.deepseek.com/", "https://platform.deepseek.com:8443/",
                "http://platform.deepseek.com/", CASHIER}) {
            assertNull(source, PaymentHandoff.from(source, WECHAT, null, null));
        }
        assertNull(PaymentHandoff.from(MWEB, ALIPAY, null, null));
        assertNull(PaymentHandoff.from(TOP_UP, WECHAT, "com.example.wallet", null));
    }

    @Test public void arbitraryActivitiesAndMalformedWrapperInstructionsAreRejected() {
        for (String target : new String[]{"weixin://wap/profile?id=1", "weixin://wap/pay", "weixin://wap/pay?",
                "weixin://wap/%70ay?id=1", "weixin://other@wap/pay?id=1", "alipays://platformapi:443/startapp?id=1",
                "alipays://evil.example/startapp?id=1", ALIPAY_WRAPPER + "&scheme=again",
                "https://render.alipay.com/p/s/i/?scheme=weixin%3A%2F%2Fwap%2Fpay%3Fid%3D1",
                "https://render.alipay.com.evil.example/p/s/i/?scheme=example"}) {
            assertNull(target, PaymentHandoff.from(TOP_UP, target, null, null));
        }
    }

    @Test public void acceptsOnlyTheCorrectProvidersHttpsCashierOrCanonicalMerchantFallback() {
        assertEquals(TOP_UP, PaymentHandoff.from(TOP_UP, WECHAT, null, TOP_UP).fallback);
        assertEquals(CASHIER, PaymentHandoff.from(TOP_UP, ALIPAY, null, CASHIER).fallback);
        for (String untrusted : new String[]{CASHIER, "http://wx.tenpay.com/", MWEB.replace(".com/", ".com:444/"),
                MWEB.replace("checkmweb", "home"), TOP_UP + "?return=https://elsewhere.example",
                "https://wx.tenpay.com.example.org/", "javascript:alert(0)", "about:blank"}) {
            assertNull(untrusted, PaymentHandoff.from(TOP_UP, WECHAT, null, untrusted).fallback);
        }
    }
}
