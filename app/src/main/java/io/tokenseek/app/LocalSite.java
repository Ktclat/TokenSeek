package io.tokenseek.app;

import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class LocalSite extends WebViewClient {
    static final String HOST = "appassets.androidplatform.net";
    static final String INDEX = "https://" + HOST + "/site/index.html";
    private static final String CSP = "default-src 'none'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src https: data: blob:; font-src 'self' data:; "
            + "connect-src 'self' https://api.deepseek.com blob: data:; media-src blob: data:; "
            + "worker-src 'self' blob:; frame-src 'none'; object-src 'none'; frame-ancestors 'none'; "
            + "form-action 'none'; base-uri 'none'";
    private final MainActivity activity;
    private final String capability;

    LocalSite(MainActivity activity, String capability) {
        this.activity = activity;
        this.capability = capability;
    }

    static boolean ownedOrigin(Uri uri) {
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getEncodedAuthority());
    }

    private static boolean isIndex(Uri uri) {
        return ownedOrigin(uri) && "/site/index.html".equals(uri.getEncodedPath()) && uri.getQuery() == null;
    }

    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (!request.isForMainFrame()) return true;
        if (isIndex(request.getUrl())) return false;
        activity.openExternal(request.getUrl());
        return true;
    }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!ownedOrigin(uri)) {
            return request.isForMainFrame() ? missing() : null;
        }
        if (request.isForMainFrame() && !isIndex(uri)) return missing();
        String path = uri.getPath();
        if (!"GET".equals(request.getMethod()) || path == null || !path.startsWith("/site/")
                || path.contains("..") || path.contains("\\") || path.indexOf(0) >= 0) {
            return missing();
        }
        try {
            if ("/site/native-bridge.js".equals(path)) {
                try (InputStream raw = activity.getResources().openRawResource(R.raw.native_bridge)) {
                    return content("application/javascript", bytes(read(raw).replace("__TOKEN__", capability)));
                }
            }
            InputStream stream = activity.getAssets().open(path.substring(1));
            if (isIndex(uri)) {
                try (InputStream input = stream) {
                    String html = read(input);
                    String withBridge = html.replace("<head>",
                            "<head><script src=\"./native-bridge.js\"></script>");
                    return content("text/html", bytes(withBridge));
                }
            }
            return content(mime(path), stream);
        } catch (IOException e) {
            return missing();
        }
    }

    private static String mime(String path) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(path).toLowerCase(Locale.ROOT);
        if (extension.equals("js")) return "application/javascript";
        if (extension.equals("css")) return "text/css";
        if (extension.equals("woff2")) return "font/woff2";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static WebResourceResponse content(String mime, InputStream data) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", CSP);
        headers.put("Cross-Origin-Resource-Policy", "same-origin");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Cache-Control", "no-store");
        String encoding = mime.startsWith("text/") || mime.equals("application/javascript") ? "utf-8" : null;
        return new WebResourceResponse(mime, encoding, 200, "OK", headers, data);
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                null, bytes("Not found"));
    }

    private static ByteArrayInputStream bytes(String data) {
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] part = new byte[8192];
        int size;
        while ((size = stream.read(part)) != -1) buffer.write(part, 0, size);
        return buffer.toString("UTF-8");
    }
}
