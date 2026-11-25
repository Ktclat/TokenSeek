package io.tokenseek.app;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;

import java.net.URI;
import java.util.Collections;
import java.util.Locale;

/** Only an order's recognized Alipay HTTPS link may leave the cashier as a wallet Intent. */
final class PaymentQrCode {
    enum Mode { ALIPAY_LINK, ALIPAY_SCAN, WECHAT_SCAN }

    final Mode mode;
    final String value;

    private PaymentQrCode(Mode mode, String value) {
        this.mode = mode;
        this.value = value;
    }

    static PaymentQrCode read(int[] pixels, int width, int height, String caption) {
        try {
            BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
            Result[] codes = new QRCodeMultiReader().decodeMultiple(image,
                    Collections.singletonMap(DecodeHintType.TRY_HARDER, Boolean.TRUE));
            return codes.length == 1 ? from(codes[0].getText(), caption) : null;
        } catch (NotFoundException missing) {
            return null;
        }
    }

    static PaymentQrCode from(String text, String caption) {
        if (text == null || text.isEmpty() || text.length() > 4096) return null;
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (uri.getRawUserInfo() != null || uri.getHost() == null || uri.getRawFragment() != null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        boolean https = "https".equalsIgnoreCase(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 443);
        boolean http = "http".equalsIgnoreCase(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 80);
        if (PaymentHandoff.isAlipayQr(text)) {
            return "wechat".equals(caption) ? null : new PaymentQrCode(Mode.ALIPAY_LINK, text);
        }
        if (http && host.equals("qr.alipay.com") && path.matches("/[A-Za-z0-9_-]{5,128}/?")) {
            return "wechat".equals(caption) ? null : new PaymentQrCode(Mode.ALIPAY_SCAN, text);
        }
        boolean wxHost = host.equals("wxpay") || host.equals("pay.weixin.qq.com") || host.equals("pay.wechatpay.cn");
        if ("weixin".equalsIgnoreCase(uri.getScheme()) && wxHost && uri.getPort() == -1
                && ("/bizpayurl".equals(path) || "/bizpayurl/up".equals(path))
                && uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            return "alipay".equals(caption) ? null : new PaymentQrCode(Mode.WECHAT_SCAN, text);
        }
        // An acquiring bank can issue a different QR. Keep it on screen only if the official
        // payment modal (not an unrelated picture) names its wallet. Never navigate to this URI.
        if (https && "wechat".equals(caption)) return new PaymentQrCode(Mode.WECHAT_SCAN, text);
        if (https && "alipay".equals(caption)) return new PaymentQrCode(Mode.ALIPAY_SCAN, text);
        return null;
    }
}
