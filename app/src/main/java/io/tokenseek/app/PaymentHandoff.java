package io.tokenseek.app;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Locale;

/** A payment link supplied by the official checkout. Order parameters stay opaque. */
final class PaymentHandoff {
    static final String MERCHANT = PlatformNavigation.BASE + PlatformNavigation.TOP_UP;

    enum Provider {
        ALIPAY("com.eg.android.AlipayGphone"), WECHAT("com.tencent.mm");

        final String packageName;

        Provider(String packageName) {
            this.packageName = packageName;
        }
    }

    final Provider provider;
    final String appUrl;
    final String fallback;

    private PaymentHandoff(Provider provider, String appUrl, String fallback) {
        this.provider = provider;
        this.appUrl = appUrl;
        this.fallback = fallback;
    }

    static PaymentHandoff from(String sourceUrl, String destination, String declaredPackage, String browserFallback) {
        URI target = parse(destination);
        if (target == null) return null;
        if (isAlipayQr(destination)) {
            if ((declaredPackage != null && !Provider.ALIPAY.packageName.equals(declaredPackage))
                    || !allowsSource(sourceUrl, Provider.ALIPAY)) return null;
            return new PaymentHandoff(Provider.ALIPAY, destination, destination);
        }
        String originalWebLink = null;
        if (isAlipayWrapper(target)) {
            originalWebLink = destination;
            String field = schemeField(target);
            if (field == null) return null;
            boolean literal = field.regionMatches(true, 0, "alipays://", 0, "alipays://".length());
            String nested = literal ? field : decodeField(field);
            // Alipay also publishes wrappers with unescaped separators. Only unwrap a single,
            // fully encoded field. Otherwise pass the original verified App Link to its wallet.
            if (!literal && target.getRawQuery().indexOf('&') < 0) destination = nested;
            target = parse(nested);
        }
        if (target == null || target.getRawQuery() == null || target.getRawQuery().isEmpty()) return null;

        Provider provider;
        if (("alipays".equalsIgnoreCase(target.getScheme()) || "alipay".equalsIgnoreCase(target.getScheme()))
                && "platformapi".equalsIgnoreCase(target.getRawAuthority())
                && "/startapp".equalsIgnoreCase(target.getRawPath())) {
            provider = Provider.ALIPAY;
        } else if ("weixin".equalsIgnoreCase(target.getScheme())
                && "wap".equalsIgnoreCase(target.getRawAuthority()) && "/pay".equals(target.getRawPath())
                && originalWebLink == null) {
            provider = Provider.WECHAT;
        } else {
            return null;
        }

        if (declaredPackage != null && !provider.packageName.equals(declaredPackage)) return null;
        if (!allowsSource(sourceUrl, provider)) return null;

        String fallback = originalWebLink;
        if (safeFallback(browserFallback, provider)) fallback = browserFallback;
        return new PaymentHandoff(provider, destination, fallback);
    }

    // A merchant-issued Alipay QR URL can be handed to Alipay exactly as displayed.
    static boolean isAlipayQr(String address) {
        URI uri = PlatformNavigation.https(address);
        return uri != null && "qr.alipay.com".equals(officialHost(address))
                && uri.getRawPath().matches("/[A-Za-z0-9_-]{5,128}/?") && uri.getRawFragment() == null;
    }

    static boolean isCheckout(String address) {
        return PlatformNavigation.TOP_UP.equals(PlatformNavigation.platformPath(address))
                || isAlipayCashier(address) || isAlipayQr(address) || isWechatH5(address)
                || "openapi.alipay.com".equals(officialHost(address))
                || "render.alipay.com".equals(officialHost(address));
    }

    static String wechatMerchantReferer(String source, String target) {
        // Forward only the origin of the actual merchant that initiated this H5 navigation.
        URI url = PlatformNavigation.https(target);
        return PlatformNavigation.TOP_UP.equals(PlatformNavigation.platformPath(source)) && isWechatH5(target)
                && url.getRawQuery() != null && !url.getRawQuery().isEmpty() ? PlatformNavigation.BASE + "/" : null;
    }

    static boolean isWechatH5(String address) {
        URI uri = parse(address);
        return "wx.tenpay.com".equals(officialHost(address))
                && "/cgi-bin/mmpayweb-bin/checkmweb".equals(uri.getRawPath());
    }

    static boolean isAlipayCashier(String address) {
        String host = officialHost(address);
        return "mclient.alipay.com".equals(host) || "wappaygw.alipay.com".equals(host)
                || "excashier.alipay.com".equals(host);
    }

    static boolean canOpenPaymentBrowser(String address) {
        // This action only opens a fresh merchant entry. It never forwards an order URL or POST.
        return PlatformNavigation.TOP_UP.equals(PlatformNavigation.platformPath(address))
                || within(officialHost(address), "alipay.com") || isWechatH5(address);
    }

    boolean isHttpsAppLink() {
        return PlatformNavigation.https(appUrl) != null;
    }

    private static boolean safeFallback(String address, Provider provider) {
        if (MERCHANT.equals(address)) return true;
        return provider == Provider.ALIPAY ? isAlipayCashier(address) : isWechatH5(address);
    }

    static boolean allowsSource(String url, Provider provider) {
        String host = officialHost(url);
        if (PlatformNavigation.TOP_UP.equals(PlatformNavigation.platformPath(url))) return true;
        if (provider == Provider.ALIPAY) return within(host, "alipay.com");
        return within(host, "tenpay.com") || within(host, "weixin.qq.com");
    }

    private static boolean within(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String officialHost(String address) {
        URI uri = PlatformNavigation.https(address);
        if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return "";
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean isAlipayWrapper(URI uri) {
        String path = uri.getRawPath();
        return "render.alipay.com".equals(officialHost(uri.toString()))
                && ("/p/s/i/".equals(path) || "/p/s/i".equals(path));
    }

    private static String schemeField(URI wrapper) {
        String query = wrapper.getRawQuery();
        if (query == null) return null;
        String encoded = null;
        for (String field : query.split("&")) {
            if (!field.startsWith("scheme=")) continue;
            if (encoded != null) return null;
            encoded = field.substring("scheme=".length());
        }
        return encoded;
    }

    private static String decodeField(String encoded) {
        try {
            // Decode exactly one wrapping layer, leaving the order's own escaping intact.
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static URI parse(String url) {
        if (url == null) return null;
        try {
            return new URI(url);
        } catch (URISyntaxException malformed) {
            return null;
        }
    }
}
