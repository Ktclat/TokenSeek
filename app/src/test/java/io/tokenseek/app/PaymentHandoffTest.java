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
