package io.tokenseek.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.tokenseek.app.PlatformNavigation.https;

/** Browser window for the official site. It has no access to the local chat bridge or key store. */
public final class OfficialPlatformActivity extends Activity {
    static final String EXTRA_ROUTE = "io.tokenseek.app.platform.ROUTE";
    static final String EXTRA_BIND = "io.tokenseek.app.platform.BIND";
    static final String EXTRA_WORKSPACE = "io.tokenseek.app.platform.WORKSPACE";
    private static final String CONTINUATION_USED = "platform.continuationUsed";
    private static final String SHARED_SESSION_USED = "shared-session-used";
    private static final int PAYMENT_WINDOW = 2;

    private final List<WebView> windows = new ArrayList<>();
    private FrameLayout pages;
    private ProgressBar progress;
    private TextView origin;
    private TextView title;
    private View notice;
    private Button bind;
    private String entryRoute;
    private String restartUrl;
    private String workspaceId;
    private CookieManager cookies;
    private PlatformNavigation navigation;
    private boolean keyboardVisible;

    static Intent createIntent(Context context, String address) {
        return createIntent(context, address, "default");
    }

    static boolean isValidWorkspaceId(String id) {
        return "default".equals(id) || (id != null && id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"));
    }

    static Intent createIntent(Context context, String address, String workspaceId) {
        Uri target = address == null ? null : Uri.parse(address);
        if (!isPlatformUrl(target)) throw new IllegalArgumentException("Expected a DeepSeek platform HTTPS URL");
        if (!isValidWorkspaceId(workspaceId)) throw new IllegalArgumentException("Expected a local workspace ID");
        return new Intent(context, OfficialPlatformActivity.class).setData(target).putExtra(EXTRA_WORKSPACE, workspaceId);
    }

    static boolean isPlatformUrl(Uri uri) {
        return uri != null && PlatformNavigation.platformPath(uri.toString()) != null;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri initial = getIntent().getData();
        workspaceId = getIntent().hasExtra(EXTRA_WORKSPACE) ? getIntent().getStringExtra(EXTRA_WORKSPACE) : "default";
        if (!isPlatformUrl(initial) || !isValidWorkspaceId(workspaceId)) {
            finish();
            return;
        }
        String entryPath = PlatformNavigation.platformPath(initial.toString());
        String safePath = PlatformNavigation.KEYS.equals(entryPath) || PlatformNavigation.TOP_UP.equals(entryPath)
                || PlatformNavigation.LOGIN.equals(entryPath) ? entryPath : "/";
        entryRoute = "/".equals(safePath) ? "home" : safePath.substring(1);
        restartUrl = PlatformNavigation.BASE + safePath;
        navigation = new PlatformNavigation(initial.toString(), state != null && state.getBoolean(CONTINUATION_USED));
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
        title = findViewById(R.id.platform_title);
        notice = findViewById(R.id.platform_notice);
        bind = findViewById(R.id.platform_bind);
        findViewById(R.id.platform_back).setOnClickListener(v -> back());
        bind.setOnClickListener(v -> {
            if (canBind(visibleWindow())) returnToChat(true);
        });
        findViewById(R.id.platform_dismiss).setOnClickListener(v -> hideNotice());
        View root = findViewById(R.id.platform_root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                keyboardVisible = windowInsets.isVisible(WindowInsets.Type.ime());
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        } else {
            Rect visible = new Rect();
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                root.getWindowVisibleDisplayFrame(visible);
                keyboardVisible = root.getRootView().getHeight() - visible.bottom
                        > 130 * getResources().getDisplayMetrics().density;
            });
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        String url = state == null ? initial.toString() : restartUrl;
        showPage(url);
        WebView rootWindow = createWindow();
        if (rootWindow != null) rootWindow.loadUrl(url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWindow() {
        WebView previous = visibleWindow();
        WebView view = new WebView(this);
        if (!"default".equals(workspaceId) && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                // Set the profile before accessing settings, cookies, or navigating any window.
                WebViewCompat.setProfile(view, "tokenseek-" + workspaceId);
                cookies = WebViewCompat.getProfile(view).getCookieManager();
            } catch (IllegalStateException | UnsupportedOperationException unavailable) {
                view.destroy();
                Toast.makeText(this, R.string.platform_profile_unavailable, Toast.LENGTH_LONG).show();
                finish();
                return null;
            }
        } else {
            // Keeping the default profile preserves the original account's official session on upgrade.
            cookies = CookieManager.getInstance();
            if (previous == null) showSharedSessionIfNeeded();
        }
        if (previous != null) previous.setVisibility(View.GONE);
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
        cookies.setAcceptThirdPartyCookies(view, true);
        view.setWebViewClient(new SiteClient());
        view.setWebChromeClient(new SiteChrome());
        windows.add(view);
        pages.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.requestFocus();
        return view;
    }

    private void showSharedSessionIfNeeded() {
        SharedPreferences history = getSharedPreferences("official-sessions", MODE_PRIVATE);
        boolean original = "default".equals(workspaceId);
        if (original && !history.getBoolean(SHARED_SESSION_USED, false)) return;
        if (!original) history.edit().putBoolean(SHARED_SESSION_USED, true).apply();
        TextView warning = findViewById(R.id.platform_session_warning);
        warning.setText(original ? R.string.platform_previous_shared_session : R.string.platform_shared_session);
        warning.setVisibility(View.VISIBLE);
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
        ((SiteClient) view.getWebViewClient()).afterRender(view, view.getUrl());
        updateProgress(view.getProgress());
    }

    private void restartEntry() {
        openOfficialEntry(restartUrl);
    }

    private void openOfficialEntry(String url) {
        while (windows.size() > 1) removeWindow(visibleWindow());
        hideNotice();
        WebView root = visibleWindow();
        root.setVisibility(View.VISIBLE);
        root.loadUrl(url);
    }

    private void showPage(String url) {
        URI location = https(url);
        if (location == null) {
            title.setText(R.string.platform_website);
            origin.setText(R.string.platform_loading);
        } else {
            String host = location.getHost().toLowerCase(Locale.ROOT);
            String port = location.getPort() == -1 || location.getPort() == 443 ? "" : ":" + location.getPort();
            origin.setText(getString(R.string.platform_https_origin, host + port));
            title.setText(pageTitle(url, host));
        }
        bind.setVisibility(canBind(visibleWindow()) ? View.VISIBLE : View.GONE);
    }

    private static int pageTitle(String url, String host) {
        String path = PlatformNavigation.platformPath(url);
        if (PlatformNavigation.LOGIN.equals(path)) return R.string.platform_login;
        if ("/sign_up".equals(path)) return R.string.platform_signup;
        if (PlatformNavigation.TOP_UP.equals(path)) return R.string.platform_topup;
        if (PlatformNavigation.KEYS.equals(path)) return R.string.platform_keys;
        if (path != null) return R.string.platform_home;
        if ("accounts.google.com".equals(host) || "appleid.apple.com".equals(host)) return R.string.platform_provider;
        if (within(host, "tenpay.com") || within(host, "alipay.com")) return R.string.platform_payment;
        return R.string.platform_website;
    }

    private boolean canBind(WebView view) {
        if (view == null || view != visibleWindow()) return false;
        SiteClient document = (SiteClient) view.getWebViewClient();
        return PlatformNavigation.canBind(view.getUrl(), document.rendered, windows.indexOf(view) == 0, document.failed);
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
        showNotice(getString(message), getString(R.string.platform_retry), this::restartEntry);
    }

    private final class SiteClient extends WebViewClient {
        private boolean finished;
        private boolean failed;
        private String rendered;
        private long revision;
        private int lastHistoryIndex = -1;
        private final PlatformNavigation.Document document = new PlatformNavigation.Document();
        private PaymentHandoff.Provider unavailableWallet;

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request.isForMainFrame()) {
                // Only the method and origin are needed. A POST or unknown document can never
                // trigger a new GET automatically, even if its destination is the homepage.
                document.observeRequest(request.getUrl().toString(), request.getMethod());
            }
            return null;
        }

        private void invalidate() {
            rendered = null;
            revision++;
        }

        private void afterRender(WebView view, String address) {
            if (!finished || failed || view != visibleWindow()) return;
            if (https(address) == null) return;
            long id = ++revision;
            view.postVisualStateCallback(id, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    if (isFinishing() || isDestroyed() || requestId != revision || failed
                            || view != visibleWindow() || !address.equals(view.getUrl())) return;
                    rendered = address;
                    if (windows.indexOf(view) == 0) {
                        int index = view.copyBackForwardList().getCurrentIndex();
                        String resume = navigation.onVisibleRoot(address, document.isOfficialGet(address), index);
                        if (resume != null) {
                            invalidate();
                            finished = false;
                            bind.setVisibility(View.GONE);
                            view.loadUrl(resume);
                            return;
                        }
                    }
                    showPage(address);
                }
            });
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri target = request.getUrl();
            if (request.isForMainFrame() && view == visibleWindow() && "GET".equals(request.getMethod())
                    && offerPaymentApp(view, target)) return true;
            if (https(target.toString()) != null) return false;
            if ("about:blank".equals(target.toString())) {
                return request.isForMainFrame() && windows.indexOf(view) <= 0;
            }
            if (request.isForMainFrame() && view == visibleWindow()) {
                showNotice(getString(R.string.platform_unsupported), "", null);
            }
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (isPlatformUrl(Uri.parse(url))) unavailableWallet = null;
            document.start(url);
            invalidate();
            finished = false;
            failed = false;
            if (view == visibleWindow()) {
                hideNotice();
                showPage(url);
            }
        }

        @Override public void onPageCommitVisible(WebView view, String url) {
            if (view != visibleWindow()) return;
            showPage(view.getUrl());
            URI location = https(url);
            if (location != null && "accounts.google.com".equalsIgnoreCase(location.getHost())) {
                offerLoginBrowser();
            } else if (PaymentHandoff.isWechatH5(url) && notice.getVisibility() != View.VISIBLE) {
                // WeChat documents H5 as a mobile-browser product. Open a fresh merchant page
                // on explicit request so the browser can supply its own login session and Referer.
                offerPaymentBrowser();
            }
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (failed || !document.finishedAt(url, view.getUrl())) return;
            finished = true;
            afterRender(view, view.getUrl());
        }

        @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            int index = view.copyBackForwardList().getCurrentIndex();
            if (windows.indexOf(view) == 0) {
                if (index < lastHistoryIndex) navigation.cancelContinuation();
                navigation.noteLoginHistory(url, index);
            }
            lastHistoryIndex = index;
            invalidate();
            if (view == visibleWindow()) {
                showPage(view.getUrl());
                // pushState/replaceState can change an SPA route without another onPageFinished.
                afterRender(view, url);
            }
        }

        private void markFailed(WebView view, int message) {
            failed = true;
            invalidate();
            if (view == visibleWindow()) {
                bind.setVisibility(View.GONE);
                showFailure(message);
            }
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) markFailed(view, R.string.platform_load_failed);
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (!request.isForMainFrame()) return;
            markFailed(view, R.string.platform_load_failed);
            if (view != visibleWindow()) return;
            String address = request.getUrl().toString();
            if (PaymentHandoff.isWechatH5(address) || PaymentHandoff.isAlipayCashier(address)) {
                offerPaymentBrowser();
            } else if ("accounts.google.com".equalsIgnoreCase(request.getUrl().getHost())) {
                offerLoginBrowser();
            }
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            if (error.getUrl().equals(view.getUrl())) markFailed(view, R.string.platform_ssl_failed);
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
            if (popup == null) return false;
            showPage(null);
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
        String source = view.getUrl();
        int index = windows.indexOf(view);
        if ((source == null || "about:blank".equals(source)) && index > 0) source = windows.get(index - 1).getUrl();
        String data = requested.toString();
        String declaredPackage = null;
        String fallback = null;
        if ("intent".equalsIgnoreCase(requested.getScheme())) {
            try {
                Intent parsed = Intent.parseUri(data, Intent.URI_INTENT_SCHEME);
                data = parsed.getDataString();
                declaredPackage = parsed.getPackage();
                fallback = parsed.getStringExtra("browser_fallback_url");
            } catch (URISyntaxException malformed) {
                return false;
            }
        }
        PaymentHandoff handoff = PaymentHandoff.from(source, data, declaredPackage, fallback);
        if (handoff == null) return false;
        SiteClient client = (SiteClient) view.getWebViewClient();
        if (client.unavailableWallet == handoff.provider) {
            // A cashier can issue its app link again after opening a web fallback. Keep HTTPS
            // navigation intact and never present the same unavailable wallet in a loop.
            if (https(requested.toString()) != null) return false;
            offerPaymentBrowser();
            return true;
        }
        String name = getString(handoff.provider == PaymentHandoff.Provider.ALIPAY
                ? R.string.platform_alipay : R.string.platform_wechat);
        showNotice(getString(R.string.platform_payment_app, name), getString(R.string.platform_open_payment, name), () -> {
            // Fresh, package-pinned intent. No component, selector, flags, or other web-supplied
            // extras are relayed to the external app.
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(handoff.appUrl))
                    .setPackage(handoff.provider.packageName).addCategory(Intent.CATEGORY_BROWSABLE);
            if (!startPaymentWindow(intent)) {
                client.unavailableWallet = handoff.provider;
                // WeChat's H5 entry must be reached from its registered merchant and can be
                // short lived. A native loadUrl cannot reproduce that browser navigation.
                if (handoff.fallback == null || PaymentHandoff.isWechatH5(handoff.fallback)) {
                    offerPaymentBrowser();
                } else {
                    showNotice(getString(R.string.platform_wallet_missing, name),
                            getString(R.string.platform_web_cashier), () -> view.loadUrl(handoff.fallback));
                }
            }
        });
        return true;
    }

    private void offerLoginBrowser() {
        showNotice(getString(R.string.platform_google), getString(R.string.platform_browser),
                () -> launch(new Intent(Intent.ACTION_VIEW, Uri.parse(restartUrl))
                        .addCategory(Intent.CATEGORY_BROWSABLE)));
    }

    private void offerPaymentBrowser() {
        showNotice(getString(R.string.platform_cashier_browser), getString(R.string.platform_browser),
                () -> {
                    Intent merchant = new Intent(Intent.ACTION_VIEW, Uri.parse(PaymentHandoff.MERCHANT))
                            .addCategory(Intent.CATEGORY_BROWSABLE);
                    if (!startPaymentWindow(merchant)) {
                        showNotice(getString(R.string.platform_no_handler), "", null);
                    }
                });
    }

    private boolean startPaymentWindow(Intent intent) {
        try {
            startActivityForResult(intent, PAYMENT_WINDOW);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PAYMENT_WINDOW && !isFinishing()) {
            // A wallet can return for success, cancellation or failure. DeepSeek owns the order.
            showNotice(getString(R.string.platform_payment_return), getString(R.string.platform_check_topup),
                    () -> openOfficialEntry(PaymentHandoff.MERCHANT));
        }
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
        if (keyboardVisible && current != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(current.getWindowToken(), 0);
            return;
        }
        if (current != null && windows.size() > 1) {
            if (current.canGoBack()) {
                hideNotice();
                current.goBack();
            } else {
                removeWindow(current);
                revealWindow();
            }
            return;
        }
        navigation.cancelContinuation();
        if (current != null && current.canGoBack()
                && navigation.canGoBackWithinTask(current.copyBackForwardList().getCurrentIndex())) {
            hideNotice();
            current.goBack();
        } else {
            returnToChat(false);
        }
    }

    @Override public void onBackPressed() {
        back();
    }

    private void returnToChat(boolean bindKey) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ROUTE, entryRoute).putExtra(EXTRA_BIND, bindKey)
                .putExtra(EXTRA_WORKSPACE, workspaceId));
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        // Restore the official entry with a fresh GET. An interrupted payment form must not be reposted.
        if (navigation != null) state.putBoolean(CONTINUATION_USED, navigation.continuationConsumed());
    }

    @Override protected void onResume() {
        super.onResume();
        for (WebView view : windows) view.onResume();
    }

    @Override protected void onPause() {
        for (WebView view : windows) view.onPause();
        if (cookies != null) cookies.flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        while (!windows.isEmpty()) removeWindow(visibleWindow());
        super.onDestroy();
    }
}
