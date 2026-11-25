package io.tokenseek.app;

import static org.junit.Assert.*;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.Test;

import java.util.Arrays;

public class PaymentQrCodeTest {
    private static final String ALIPAY = "https://qr.alipay.com/bax0123EXAMPLE?opaque=a%2Bb%26c";
    private static final String WECHAT = "weixin://pay.weixin.qq.com/bizpayurl/up?pr=example&groupid=00";

    @Test public void alipayOrderPreservesItsQueryAndOnlyHttpsCanLeaveTheCashier() {
        assertEquals(ALIPAY, PaymentQrCode.from(ALIPAY, "alipay").value);
        assertEquals(PaymentQrCode.Mode.ALIPAY_LINK, PaymentQrCode.from(ALIPAY, "").mode);
        assertEquals(PaymentQrCode.Mode.ALIPAY_SCAN, PaymentQrCode.from(ALIPAY.replace("https:", "http:"), "").mode);
        assertNull(PaymentQrCode.from(ALIPAY, "wechat"));
    }

    @Test public void nativeWechatCodesStayScannableAndH5IsHandledSeparately() {
        assertEquals(PaymentQrCode.Mode.WECHAT_SCAN, PaymentQrCode.from(WECHAT, "").mode);
        assertEquals(PaymentQrCode.Mode.WECHAT_SCAN, PaymentQrCode.from("weixin://wxpay/bizpayurl?pr=order", "").mode);
        assertNull(PaymentQrCode.from("weixin://wap/pay?prepayid=order", ""));
        assertNull(PaymentQrCode.from(WECHAT, "alipay"));
    }

    @Test public void unrecognizedCheckoutIsShownOnlyInItsLabelledMerchantModalAndNeverLaunched() {
        String bank = "https://acquirer.example/order/123";
        assertEquals(PaymentQrCode.Mode.ALIPAY_SCAN, PaymentQrCode.from(bank, "alipay").mode);
        assertNull(PaymentQrCode.from(bank, ""));
        for (String value : new String[] {"about:blank", "intent://pay#Intent;end", "https://a@qr.alipay.com/baxEXAMPLE",
                "https://qr.alipay.com:8443/baxEXAMPLE", "https://qr.alipay.com/baxEXAMPLE#other"}) {
            assertNull(value, PaymentQrCode.from(value, "alipay"));
        }
        assertNull(PaymentQrCode.from("https://qr.alipay.com.invalid/baxEXAMPLE", ""));
        assertNull(PaymentQrCode.from("weixin://pay.weixin.qq.com/other?pr=order", ""));
    }

    @Test public void renderedPixelsDecodeOnlyOneRealPaymentQr() throws Exception {
        int w = 320;
        int[] pixels = new int[w * w];
        BitMatrix qr = new QRCodeWriter().encode(ALIPAY, BarcodeFormat.QR_CODE, w, w);
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) pixels[y*w+x] = qr.get(x,y) ? 0xff000000 : 0xffffffff;
        }
        PaymentQrCode code = PaymentQrCode.read(pixels, w, w, "alipay");
        assertNotNull(code);
        assertEquals(ALIPAY, code.value);

        int[] pair = new int[w * w * 2];
        for (int y = 0; y < w; y++) {
            System.arraycopy(pixels, y*w, pair, y*w*2, w);
            System.arraycopy(pixels, y*w, pair, y*w*2 + w, w);
        }
        assertNull(PaymentQrCode.read(pair, w*2, w, "alipay"));
        Arrays.fill(pixels, 0xffffffff);
        assertNull(PaymentQrCode.read(pixels, w, w, "alipay"));
    }
}
