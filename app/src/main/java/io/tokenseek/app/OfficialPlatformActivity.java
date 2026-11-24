package io.tokenseek.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Browser window for the official site. It has no access to the local chat bridge or key store. */
public final class OfficialPlatformActivity extends Activity {
    static final String EXTRA_ROUTE = "io.tokenseek.app.platform.ROUTE";
    static final String EXTRA_BIND = "io.tokenseek.app.platform.BIND";
    private static final String BASE = "https://platform.deepseek.com";
    private static final String HOME = "home";
    private static final String TOP_UP = "top_up";
    private static final String KEYS = "api_keys";
    private static final String SIGN_IN = "sign_in";

    private final List<WebView> windows = new ArrayList<>();
    private FrameLayout pages;
    private ProgressBar progress;
    private TextView origin;
    private View notice;
    private Button bind;
    private String entryRoute;
    private String section;

    static Intent createIntent(Context context, String address) {
        Uri target = address == null ? null : Uri.parse(address);
        if (!isPlatformUrl(target)) throw new IllegalArgumentException("Expected a DeepSeek platform HTTPS URL");
        return new Intent(context, OfficialPlatformActivity.class).setData(target);
    }

    private static URI https(String address) {
        if (address == null) return null;
        try {
            URI uri = new URI(address);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getPort() <= 65535) return uri;
        } catch (URISyntaxException ignored) {
            // WebView can also report transient non-network URLs such as about:blank.
        }
        return null;
    }

    static boolean isPlatformUrl(Uri uri) {
        URI parsed = uri == null ? null : https(uri.toString());
        return parsed != null && "platform.deepseek.com".equalsIgnoreCase(parsed.getHost())
                && (parsed.getPort() == -1 || parsed.getPort() == 443);
    }

    private static String route(Uri uri) {
        String path = uri.getEncodedPath();
        if (("/" + TOP_UP).equals(path)) return TOP_UP;
        if (("/" + KEYS).equals(path)) return KEYS;
        if (("/" + SIGN_IN).equals(path)) return SIGN_IN;
        return HOME;
    }

    private static String sectionUrl(String route) {
        return BASE + "/" + ((TOP_UP.equals(route) || KEYS.equals(route) || SIGN_IN.equals(route)) ? route : "");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri initial = getIntent().getData();
        if (!isPlatformUrl(initial)) {
            finish();
            return;
        }
        entryRoute = route(initial);
        section = state == null ? entryRoute : state.getString(EXTRA_ROUTE, entryRoute);
        setContentView(R.layout.activity_official_platform);
        View decor = getWindow().getDecorView();
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~lightBars
                : decor.getSystemUiVisibility() | lightBars);
        pages = findViewById(R.id.platform_windows);
        progress = findViewById(R.id.platform_progress);
        origin = findViewById(R.id.platform_origin);
        notice = findViewById(R.id.platform_notice);
        bind = findViewById(R.id.platform_bind);
        findViewById(R.id.platform_close).setOnClickListener(v -> returnToChat(false));
        bind.setOnClickListener(v -> returnToChat(true));
        findViewById(R.id.platform_dismiss).setOnClickListener(v -> hideNotice());
        findViewById(R.id.platform_home).setOnClickListener(v -> openSection(HOME));
        findViewById(R.id.platform_topup).setOnClickListener(v -> openSection(TOP_UP));
        findViewById(R.id.platform_keys).setOnClickListener(v -> openSection(KEYS));
        arrangeHeader();
        View root = findViewById(R.id.platform_root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                findViewById(R.id.platform_tabs).setVisibility(
                        windowInsets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE);
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        } else {
            Rect visible = new Rect();
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                root.getWindowVisibleDisplayFrame(visible);
                boolean keyboard = root.getRootView().getHeight() - visible.bottom > dp(130);
                findViewById(R.id.platform_tabs).setVisibility(keyboard ? View.GONE : View.VISIBLE);
            });
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        updateTabs();
        String url = state == null ? initial.toString() : sectionUrl(section);
        showOrigin(url);
        createWindow().loadUrl(url);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void arrangeHeader() {
        LinearLayout root = findViewById(R.id.platform_root);
        LinearLayout header = findViewById(R.id.platform_header);
        View tabs = findViewById(R.id.platform_tabs);
        Configuration screen = getResources().getConfiguration();
        boolean inline = screen.screenWidthDp >= 560 && screen.screenHeightDp < 500;
        if (inline && tabs.getParent() == root) {
            root.removeView(tabs);
            header.addView(tabs, new LinearLayout.LayoutParams(dp(220), -2));
        } else if (!inline && tabs.getParent() == header) {
            header.removeView(tabs);
            root.addView(tabs, 1, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        arrangeHeader();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWindow() {
        WebView previous = visibleWindow();
        if (previous != null) previous.setVisibility(View.GONE);
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setWebViewClient(new SiteClient());
        view.setWebChromeClient(new SiteChrome());
        windows.add(view);
        pages.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.requestFocus();
        return view;
    }

    private WebView visibleWindow() {
        return windows.isEmpty() ? null : windows.get(windows.size() - 1);
    }

    private void removeWindow(WebView view) {
        windows.remove(view);
        pages.removeView(view);
        view.stopLoading();
        view.destroy();
    }

    private void revealWindow() {
        WebView view = visibleWindow();
        if (view == null) return;
        view.setVisibility(View.VISIBLE);
        view.requestFocus();
        hideNotice();
        showPage(view.getUrl());
        updateProgress(view.getProgress());
    }

    private void openSection(String requested) {
        while (windows.size() > 1) removeWindow(visibleWindow());
        section = requested;
        hideNotice();
        updateTabs();
        WebView root = visibleWindow();
        root.setVisibility(View.VISIBLE);
        root.loadUrl(sectionUrl(section));
    }

    private void updateTabs() {
        tab(R.id.platform_home, HOME.equals(section) || SIGN_IN.equals(section));
        tab(R.id.platform_topup, TOP_UP.equals(section));
        tab(R.id.platform_keys, KEYS.equals(section));
        bind.setVisibility(KEYS.equals(section) ? View.VISIBLE : View.GONE);
    }

    private void tab(int id, boolean selected) {
        Button tab = findViewById(id);
        tab.setSelected(selected);
        tab.setTextColor(getColor(selected ? R.color.platform_accent : R.color.platform_muted));
        tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void showOrigin(String url) {
        URI location = https(url);
        if (location == null) {
            origin.setText(R.string.platform_loading);
        } else {
            String host = location.getHost().toLowerCase(Locale.ROOT);
            String port = location.getPort() == -1 || location.getPort() == 443 ? "" : ":" + location.getPort();
            origin.setText(getString(R.string.platform_https_origin, host + port));
        }
    }

    private void showPage(String url) {
        showOrigin(url);
        if (url == null) return;
        Uri site = Uri.parse(url);
        if (isPlatformUrl(site)) {
            String actual = route(site);
            // A login redirect must preserve the user's intended destination.
            if (!SIGN_IN.equals(actual)) {
                section = actual;
                updateTabs();
            }
        }
    }

    private void updateProgress(int value) {
        progress.setProgress(value);
        progress.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE);
    }

    private void hideNotice() {
        notice.setVisibility(View.GONE);
        findViewById(R.id.platform_notice_action).setOnClickListener(null);
    }

    private void showNotice(String message, String label, Runnable action) {
        ((TextView) findViewById(R.id.platform_notice_text)).setText(message);
        Button button = findViewById(R.id.platform_notice_action);
        button.setVisibility(action == null ? View.GONE : View.VISIBLE);
        button.setText(label);
        button.setOnClickListener(action == null ? null : v -> {
            hideNotice();
            action.run();
        });
        notice.setVisibility(View.VISIBLE);
    }

    private void showFailure(int message) {
        showNotice(getString(message), getString(R.string.platform_retry), () -> openSection(section));
    }

    private final class SiteClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri target = request.getUrl();
            if (https(target.toString()) != null) return false;
            if ("about:blank".equals(target.toString())) {
                return request.isForMainFrame() && windows.indexOf(view) <= 0;
            }
            if (!request.isForMainFrame()) return true;
            if (view == visibleWindow() && !offerPaymentApp(view, target)) {
                showNotice(getString(R.string.platform_unsupported), "", null);
            }
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (view != visibleWindow()) return;
            hideNotice();
            showPage(url);
        }

        @Override public void onPageCommitVisible(WebView view, String url) {
            if (view != visibleWindow()) return;
            showPage(url);
            URI location = https(url);
            if (location != null && "accounts.google.com".equalsIgnoreCase(location.getHost())) {
                showNotice(getString(R.string.platform_google), getString(R.string.platform_browser),
                        () -> launch(new Intent(Intent.ACTION_VIEW, Uri.parse(sectionUrl(section)))
                                .addCategory(Intent.CATEGORY_BROWSABLE)));
            }
        }

        @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            if (view == visibleWindow()) showPage(url);
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (view == visibleWindow() && request.isForMainFrame()) showFailure(R.string.platform_load_failed);
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            if (view == visibleWindow() && error.getUrl().equals(view.getUrl())) {
                showFailure(R.string.platform_ssl_failed);
            }
        }
    }

    private final class SiteChrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int value) {
            if (view == visibleWindow()) updateProgress(value);
        }

        @Override public boolean onCreateWindow(WebView opener, boolean dialog, boolean userGesture, Message result) {
            if (!userGesture || opener != visibleWindow() || windows.size() >= 4) return false;
            // Give Chromium a real secondary WebView: window.opener, target=_blank form POSTs,
            // and window.close() retain their normal browser semantics.
            hideNotice();
            WebView popup = createWindow();
            showOrigin(null);
            ((WebView.WebViewTransport) result.obj).setWebView(popup);
            result.sendToTarget();
            return true;
        }

        @Override public void onCloseWindow(WebView view) {
            if (windows.indexOf(view) <= 0) return;
            boolean wasVisible = view == visibleWindow();
            removeWindow(view);
            if (wasVisible) revealWindow();
        }
    }

    private static boolean within(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private boolean offerPaymentApp(WebView view, Uri requested) {
        URI source = https(view.getUrl());
        if (source == null && windows.indexOf(view) > 0) {
            source = https(windows.get(windows.indexOf(view) - 1).getUrl());
        }
        if (source == null) return false;
        String host = source.getHost().toLowerCase(Locale.ROOT);
        Uri target = requested;
        String designatedPackage = null;
        if ("intent".equalsIgnoreCase(requested.getScheme())) {
            try {
                Intent parsed = Intent.parseUri(requested.toString(), Intent.URI_INTENT_SCHEME);
                target = parsed.getData();
                designatedPackage = parsed.getPackage();
            } catch (URISyntaxException malformed) {
                return false;
            }
        }
        if (target == null || target.getUserInfo() != null || target.getPort() != -1) return false;
        String providerPackage;
        int name;
        if ("alipays".equalsIgnoreCase(target.getScheme()) && "platformapi".equalsIgnoreCase(target.getHost())
                && "/startapp".equalsIgnoreCase(target.getPath())
                && (within(host, "alipay.com") || within(host, "deepseek.com"))) {
            providerPackage = "com.eg.android.AlipayGphone";
            name = R.string.platform_alipay;
        } else if ("weixin".equalsIgnoreCase(target.getScheme()) && "wap".equalsIgnoreCase(target.getHost())
                && "/pay".equals(target.getPath())
                && (within(host, "tenpay.com") || within(host, "weixin.qq.com") || within(host, "deepseek.com"))) {
            providerPackage = "com.tencent.mm";
            name = R.string.platform_wechat;
        } else {
            return false;
        }
        if (designatedPackage != null && !providerPackage.equals(designatedPackage)) return false;
        // Reconstruct a browsable, package-pinned VIEW intent. Never relay supplied extras,
        // components, selectors, flags, or fallback URLs from a web page.
        Intent open = new Intent(Intent.ACTION_VIEW, target).setPackage(providerPackage)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        String provider = getString(name);
        showNotice(getString(R.string.platform_payment_app, provider),
                getString(R.string.platform_open_payment, provider), () -> launch(open));
        return true;
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            showNotice(getString(R.string.platform_no_handler), "", null);
        }
    }

    private void back() {
        WebView current = visibleWindow();
        if (current != null && current.canGoBack()) {
            hideNotice();
            current.goBack();
        } else if (windows.size() > 1) {
            removeWindow(current);
            revealWindow();
        } else {
            returnToChat(false);
        }
    }

    @Override public void onBackPressed() {
        back();
    }

    private void returnToChat(boolean bindKey) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ROUTE, entryRoute).putExtra(EXTRA_BIND, bindKey));
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        // Restore the official entry with a fresh GET. An interrupted payment form must not be reposted.
        state.putString(EXTRA_ROUTE, section);
    }

    @Override protected void onResume() {
        super.onResume();
        for (WebView view : windows) view.onResume();
    }

    @Override protected void onPause() {
        for (WebView view : windows) view.onPause();
        if (!windows.isEmpty()) CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        while (!windows.isEmpty()) removeWindow(visibleWindow());
        super.onDestroy();
    }
}
