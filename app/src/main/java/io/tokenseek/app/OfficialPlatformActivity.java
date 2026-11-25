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
import android.os.SystemClock;
import android.view.MotionEvent;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import static io.tokenseek.app.PlatformNavigation.https;

/** Browser window for the official site. It has no access to the local chat bridge or key store. */
public final class OfficialPlatformActivity extends Activity {
    static final String EXTRA_ROUTE = "io.tokenseek.app.platform.ROUTE";
    static final String EXTRA_BIND = "io.tokenseek.app.platform.BIND";
    static final String EXTRA_WORKSPACE = "io.tokenseek.app.platform.WORKSPACE";
    static final String EXTRA_AMOUNT = "io.tokenseek.app.platform.TOP_UP_AMOUNT";
    static final String EXTRA_DARK = "io.tokenseek.app.platform.DARK";
    static final String EXTRA_FONT = "io.tokenseek.app.platform.FONT";
    private static final String CONTINUATION_USED = "platform.continuationUsed";
    private static final String VIEWING_TRANSACTIONS = "platform.viewingTransactions";
    private static final String SHARED_SESSION_USED = "shared-session-used";
    private static final int PAYMENT_WINDOW = 2;

    private final List<WebView> windows = new ArrayList<>();
    private FrameLayout pages;
    private ProgressBar progress;
    private TextView origin;
    private TextView title;
    private View notice;
    private Button bind;
    private OfficialKeyDirectory keyDirectory;
    private String topUpAmount;
    private Button fillAmount;
    private NativePaymentQr paymentQr;
    private Button qrAction;
    private String mobileLayout;
    private String entryRoute;
    private String restartUrl;
    private String workspaceId;
    private CookieManager cookies;
    private PlatformNavigation navigation;
    private boolean keyboardVisible;
    private boolean paymentWindowOpen;

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
        String safePath = PlatformNavigation.isTaskPath(entryPath) || PlatformNavigation.LOGIN.equals(entryPath) ? entryPath : "/";
        entryRoute = "/".equals(safePath) ? "home" : safePath.substring(1);
        restartUrl = PlatformNavigation.BASE + safePath;
        boolean restoringBill = state != null && state.getBoolean(VIEWING_TRANSACTIONS);
        if (restoringBill) restartUrl = PlatformNavigation.BASE + PlatformNavigation.TRANSACTIONS;
        navigation = new PlatformNavigation(restoringBill ? restartUrl : initial.toString(),
                state != null && state.getBoolean(CONTINUATION_USED));
        setContentView(R.layout.activity_official_platform);
        View decor = getWindow().getDecorView();
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~lightBars
                : decor.getSystemUiVisibility() | lightBars);
        pages = findViewById(R.id.platform_windows);
        keyDirectory = new OfficialKeyDirectory(keyPanelContext(), findViewById(R.id.platform_key_host),
                findViewById(R.id.platform_key_view), new OfficialKeyDirectory.Host() {
                    @Override public void nativeChrome(boolean visible) { showNativeChrome(visible); }
                    @Override public void reloadDirectory() {
                        restartUrl = PlatformNavigation.BASE + PlatformNavigation.KEYS;
                        navigation = new PlatformNavigation(restartUrl, false);
                        openOfficialEntry(restartUrl);
                    }
                    @Override public void leaveDirectory() { back(); }
                }, workspaceId);
        mobileLayout = LocalSite.bundledScript(this, R.raw.platform_mobile);
        paymentQr = new NativePaymentQr(this, this::openAlipayQr);
        qrAction = findViewById(R.id.platform_qr);
        qrAction.setOnClickListener(v -> {
            WebView current = visibleWindow();
            if (current != null && canReadPaymentQr(current, current.getUrl())) {
                String address = current.getUrl();
                SiteClient site = (SiteClient) current.getWebViewClient();
                long revision = site.revision;
                paymentQr.open(current, () -> revision == site.revision && canReadPaymentQr(current, address));
            }
        });
        fillAmount = findViewById(R.id.platform_fill_amount);
        topUpAmount = PaymentHandoff.MERCHANT.equals(initial.toString())
                ? NativeFunding.parseAmount(getIntent().getStringExtra(EXTRA_AMOUNT)).value : "";
        if (!topUpAmount.isEmpty()) {
            fillAmount.setText(getString(R.string.funding_fill_amount, topUpAmount));
            fillAmount.setOnClickListener(v -> fillTopUpAmount());
        }
        progress = findViewById(R.id.platform_progress);
        origin = findViewById(R.id.platform_origin);
        title = findViewById(R.id.platform_title);
        notice = findViewById(R.id.platform_notice);
        bind = findViewById(R.id.platform_bind);
        findViewById(R.id.platform_back).setOnClickListener(v -> back());
        findViewById(R.id.platform_payment_browser).setOnClickListener(v -> offerPaymentBrowser());
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
        SiteClient client = new SiteClient();
        view.setWebViewClient(client);
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && view == visibleWindow()
                    && PaymentHandoff.isCheckout(view.getUrl())) client.tappedCheckout();
            return false;
        });
        view.setWebChromeClient(new SiteChrome());
        windows.add(view);
        pages.addView(view, new FrameLayout.LayoutParams(-1, -1));
        if (!keyDirectory.nativeVisible()) view.requestFocus();
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
        findViewById(R.id.platform_payment_browser).setVisibility(
                PaymentHandoff.canOpenPaymentBrowser(url) ? View.VISIBLE : View.GONE);
        WebView current = visibleWindow();
        boolean keyPage = PlatformNavigation.isKeyPage(url) && canBind(current);
        bind.setVisibility(keyPage ? View.VISIBLE : View.GONE);
        boolean root = current == null || windows.indexOf(current) == 0;
        boolean failed = current != null && ((SiteClient) current.getWebViewClient()).failed;
        keyDirectory.route(current, url, root, keyPage, failed, "api_keys".equals(entryRoute));
        qrAction.setVisibility(canReadPaymentQr(current, url) ? View.VISIBLE : View.GONE);
        fillAmount.setVisibility(!topUpAmount.isEmpty() && PaymentHandoff.MERCHANT.equals(url)
                && currentTopUp(current) ? View.VISIBLE : View.GONE);
    }

    private boolean canReadPaymentQr(WebView view, String address) {
        if (view == null || view != visibleWindow() || keyDirectory.nativeVisible()
                || !NativePaymentQr.supports(address) || !address.equals(view.getUrl())) return false;
        SiteClient site = (SiteClient) view.getWebViewClient();
        return !site.failed && address.equals(site.rendered);
    }

    private boolean currentTopUp(WebView view) {
        if (view == null || view != visibleWindow() || windows.indexOf(view) != 0
                || !PaymentHandoff.MERCHANT.equals(view.getUrl())) return false;
        SiteClient site = (SiteClient) view.getWebViewClient();
        return !site.failed && PaymentHandoff.MERCHANT.equals(site.rendered);
    }

    private void fillTopUpAmount() {
        WebView view = visibleWindow();
        if (topUpAmount.isEmpty() || !currentTopUp(view)) return;
        String script = LocalSite.bundledScript(this, R.raw.platform_amount);
        if (script.isEmpty()) return;
        String expression = "(" + script + "\n)(" + org.json.JSONObject.quote(topUpAmount) + ")";
        view.evaluateJavascript(expression, result -> {
            if (isFinishing() || isDestroyed() || !currentTopUp(view)) return;
            if ("true".equals(result)) {
                Toast.makeText(this, R.string.funding_filled, Toast.LENGTH_SHORT).show();
            } else {
                showNotice(getString(R.string.funding_manual, topUpAmount), getString(R.string.funding_copy), () -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            getString(R.string.funding_copy), topUpAmount));
                });
            }
        });
    }

    private static int pageTitle(String url, String host) {
        String path = PlatformNavigation.platformPath(url);
        if (PlatformNavigation.LOGIN.equals(path)) return R.string.platform_login;
        if ("/sign_up".equals(path)) return R.string.platform_signup;
        if (PlatformNavigation.TOP_UP.equals(path)) return R.string.platform_topup;
        if (PlatformNavigation.TRANSACTIONS.equals(path)) return R.string.platform_transactions;
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
        progress.setVisibility(keyDirectory.nativeVisible() ? View.GONE : value >= 100 ? View.INVISIBLE : View.VISIBLE);
    }

    private Context keyPanelContext() {
        android.view.ContextThemeWrapper context = new android.view.ContextThemeWrapper(this, R.style.TokenSeekTheme);
        Configuration override = new Configuration();
        if (getIntent().hasExtra(EXTRA_DARK)) override.uiMode = getIntent().getBooleanExtra(EXTRA_DARK, false)
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        int percent = getIntent().getIntExtra(EXTRA_FONT, 100);
        override.fontScale = getResources().getConfiguration().fontScale * Math.max(90, Math.min(percent, 130)) / 100f;
        context.applyOverrideConfiguration(override);
        return context;
    }

    private void showNativeChrome(boolean nativeScreen) {
        findViewById(R.id.platform_toolbar).setVisibility(nativeScreen ? View.GONE : View.VISIBLE);
        pages.setImportantForAccessibility(nativeScreen ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        pages.setDescendantFocusability(nativeScreen ? FrameLayout.FOCUS_BLOCK_DESCENDANTS : FrameLayout.FOCUS_AFTER_DESCENDANTS);
        if (nativeScreen) {
            hideNotice();
            progress.setVisibility(View.GONE);
            findViewById(R.id.platform_key_host).requestFocus();
        } else {
            WebView visible = visibleWindow();
            updateProgress(visible == null ? 0 : visible.getProgress());
        }
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (nativeScreen) dark = getIntent().getBooleanExtra(EXTRA_DARK, dark);
        getWindow().setStatusBarColor(dark ? 0xff17181c : 0xffffffff);
        getWindow().setNavigationBarColor(dark ? 0xff17181c : 0xffffffff);
        View decor = getWindow().getDecorView();
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~lightBars : decor.getSystemUiVisibility() | lightBars);
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
        private boolean unavailableAlipayAppLink;
        private long checkoutTap = -1;
        private String wechatH5Navigation;
        private String launchedLink;
        private long paymentEpoch;
        private String awaitingHistory;
        private WebView initialPaymentOpener;

        void tappedCheckout() {
            checkoutTap = SystemClock.uptimeMillis();
            paymentEpoch++;
            launchedLink = null;
        }

        boolean recentCheckoutTap() {
            return checkoutTap >= 0 && SystemClock.uptimeMillis() - checkoutTap <= 15000;
        }

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
                    view.getSettings().setJavaScriptCanOpenWindowsAutomatically(PaymentHandoff.isCheckout(address));
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
                    String path = PlatformNavigation.platformPath(address);
                    if ("/".equals(path) || PlatformNavigation.LOGIN.equals(path) || "/sign_up".equals(path)
                            || PlatformNavigation.isTaskPath(path)) {
                        view.evaluateJavascript(mobileLayout, null);
                    }
                    showPage(address);
                }
            });
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri target = request.getUrl();
            if (view == visibleWindow() && "GET".equals(request.getMethod())) {
                if (request.isForMainFrame() && !request.isRedirect()) {
                    String address = target.toString();
                    String referer = PaymentHandoff.wechatMerchantReferer(paymentSourceWindow(view).getUrl(), address);
                    if (referer != null && !address.equals(wechatH5Navigation)) {
                        wechatH5Navigation = address;
                        view.loadUrl(address, Collections.singletonMap("Referer", referer));
                        return true;
                    }
                }
                if (offerPaymentApp(view, request)) return true;
            }
            if (https(target.toString()) != null) return false;
            if ("about:blank".equals(target.toString())) {
                return request.isForMainFrame() && windows.indexOf(view) <= 0;
            }
            if (request.isForMainFrame() && view == visibleWindow()) {
                if (PaymentHandoff.canOpenPaymentBrowser(view.getUrl())) offerPaymentBrowser();
                else showNotice(getString(R.string.platform_unsupported), "", null);
            }
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (view == visibleWindow()) paymentQr.cancel();
            // An initial empty popup belongs to its opener until its first real document.
            boolean initialBlank = "about:blank".equals(url) && initialPaymentOpener != null;
            if (!initialBlank) {
                initialPaymentOpener = null;
                paymentEpoch++;
            }
            awaitingHistory = url;
            boolean checkout = PaymentHandoff.isCheckout(url);
            view.getSettings().setJavaScriptCanOpenWindowsAutomatically(checkout);
            if (!checkout) checkoutTap = -1;
            if (!PaymentHandoff.isWechatH5(url)) wechatH5Navigation = null;
            if (isPlatformUrl(Uri.parse(url))) {
                launchedLink = null;
                unavailableWallet = null;
                unavailableAlipayAppLink = false;
            }
            document.start(url);
            invalidate();
            finished = false;
            failed = false;
            if (view == visibleWindow()) {
                if (!initialBlank) hideNotice();
                showPage(url);
            }
        }

        @Override public void onPageCommitVisible(WebView view, String url) {
            if (view != visibleWindow()) return;
            showPage(view.getUrl());
            URI location = https(url);
            if (location != null && "accounts.google.com".equalsIgnoreCase(location.getHost())) {
                offerLoginBrowser();
            } else if (PaymentHandoff.isWechatH5(url)) {
                // Give H5 time to issue its wallet navigation. Offer a browser only if this
                // document stays on screen and no actionable wallet prompt has appeared.
                view.postDelayed(() -> {
                    if (view == visibleWindow() && url.equals(view.getUrl()) && !isDestroyed() && !isFinishing()
                            && !paymentWindowOpen && launchedLink == null && notice.getVisibility() != View.VISIBLE)
                        offerPaymentBrowser();
                }, 3500);
            }
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (failed || !document.finishedAt(url, view.getUrl())) return;
            awaitingHistory = null;
            finished = true;
            afterRender(view, view.getUrl());
        }

        @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            if (!"about:blank".equals(url) && !url.equals(awaitingHistory)) paymentEpoch++;
            awaitingHistory = null;
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

        private void markFailed(WebView view, String address, int message) {
            failed = true;
            invalidate();
            if (view == visibleWindow()) {
                bind.setVisibility(View.GONE);
                fillAmount.setVisibility(View.GONE);
                showPage(address);
                if (!keyDirectory.nativeVisible()) showFailure(message);
            }
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) markFailed(view, request.getUrl().toString(), R.string.platform_load_failed);
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (!request.isForMainFrame()) return;
            markFailed(view, request.getUrl().toString(), R.string.platform_load_failed);
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
            if (error.getUrl().equals(view.getUrl())) markFailed(view, error.getUrl(), R.string.platform_ssl_failed);
        }
    }

    private final class SiteChrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int value) {
            if (view == visibleWindow()) updateProgress(value);
        }

        @Override public boolean onCreateWindow(WebView opener, boolean dialog, boolean userGesture, Message result) {
            if (opener != visibleWindow()) return false;
            SiteClient owner = (SiteClient) opener.getWebViewClient();
            boolean fromCheckoutTap = PaymentHandoff.isCheckout(opener.getUrl()) && owner.recentCheckoutTap();
            if ((!userGesture && !fromCheckoutTap) || windows.size() >= 4) {
                if (PaymentHandoff.canOpenPaymentBrowser(opener.getUrl())) offerPaymentBrowser();
                return false;
            }
            owner.checkoutTap = -1;
            // Give Chromium a real secondary WebView: window.opener, target=_blank form POSTs,
            // and window.close() retain their normal browser semantics.
            hideNotice();
            WebView popup = createWindow();
            if (popup == null) return false;
            ((SiteClient) popup.getWebViewClient()).initialPaymentOpener = opener;
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

    private WebView paymentSourceWindow(WebView view) {
        String source = view.getUrl();
        WebView opener = ((SiteClient) view.getWebViewClient()).initialPaymentOpener;
        if ((source == null || "about:blank".equals(source)) && opener != null && windows.contains(opener)) return opener;
        return view;
    }

    private boolean offerPaymentApp(WebView view, WebResourceRequest request) {
        Uri requested = request.getUrl();
        WebView owner = paymentSourceWindow(view);
        String source = owner.getUrl();
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
        if (client.failed) return false;
        if (handoff.isHttpsAppLink() && client.unavailableAlipayAppLink) return false;
        if (client.unavailableWallet == handoff.provider) {
            if (https(requested.toString()) != null) return false;
            if (request.isForMainFrame()) offerPaymentBrowser();
            return true;
        }
        String frameCheck = null;
        if (!request.isForMainFrame()) {
            // WebResourceRequest doesn't identify the sending frame. The checkout document
            // must itself embed the exact wallet URL, both now and when the user approves.
            if (https(requested.toString()) != null) return false;
            if (!PaymentHandoff.isCheckout(source) || owner != view) return true;
            frameCheck = "(() => {if (window !== top || location.href !== "
                    + org.json.JSONObject.quote(source) + ") return false; const url = "
                    + org.json.JSONObject.quote(requested.toString()) + "; return Array.from(document.querySelectorAll('iframe,frame'))"
                    + ".some(frame => frame.src === url);})()";
        }
        SiteClient ownerClient = (SiteClient) owner.getWebViewClient();
        long ticket = ++client.paymentEpoch;
        long ownerEpoch = ownerClient.paymentEpoch;
        BooleanSupplier current = () -> view == visibleWindow() && !isFinishing() && !isDestroyed()
                && !client.failed && client.paymentEpoch == ticket && ownerClient.paymentEpoch == ownerEpoch
                && owner == paymentSourceWindow(view) && source.equals(owner.getUrl());
        String frame = frameCheck;
        boolean direct = request.isForMainFrame() && request.hasGesture() && client.recentCheckoutTap();
        whenWalletCurrent(view, current, frame, false, () -> presentWallet(view, client, handoff, direct, current, frame));
        return true;
    }

    private void whenWalletCurrent(WebView view, BooleanSupplier current, String frameCheck, boolean approved, Runnable action) {
        if (!current.getAsBoolean()) {
            if (approved) staleWallet(view);
            return;
        }
        if (frameCheck == null) {
            action.run();
            return;
        }
        view.evaluateJavascript(frameCheck, result -> {
            if ("true".equals(result) && current.getAsBoolean()) action.run();
            else if (approved) staleWallet(view);
        });
    }

    private void staleWallet(WebView view) {
        if (view == visibleWindow() && !isFinishing() && !isDestroyed()
                && !paymentWindowOpen && PaymentHandoff.isCheckout(paymentSourceWindow(view).getUrl())
                && notice.getVisibility() != View.VISIBLE)
            showNotice(getString(R.string.platform_wallet_changed), "", null);
    }

    private void presentWallet(WebView view, SiteClient client, PaymentHandoff handoff, boolean userTap,
                               BooleanSupplier current, String frameCheck) {
        if (paymentWindowOpen || handoff.appUrl.equals(client.launchedLink)) return;
        String name = getString(handoff.provider == PaymentHandoff.Provider.ALIPAY
                ? R.string.platform_alipay : R.string.platform_wechat);
        Runnable launch = () -> {
            if (paymentWindowOpen || handoff.appUrl.equals(client.launchedLink)) return;
            if (launchWallet(handoff)) {
                client.launchedLink = handoff.appUrl;
                client.checkoutTap = -1;
                return;
            }
            // If the wallet has no HTTPS handler, keep its web checkout available. A later
            // custom-scheme request from that page can still work on this installation.
            if (handoff.isHttpsAppLink()) client.unavailableAlipayAppLink = true;
            else client.unavailableWallet = handoff.provider;
            if (handoff.provider == PaymentHandoff.Provider.ALIPAY && PaymentHandoff.isAlipayCashier(view.getUrl())
                    && (handoff.fallback == null || handoff.fallback.equals(view.getUrl()))) {
                showNotice(getString(R.string.platform_alipay_stay), getString(R.string.platform_back_to_cashier),
                        () -> view.requestFocus());
            } else if (handoff.fallback == null || PaymentHandoff.isWechatH5(handoff.fallback)) {
                offerPaymentBrowser();
            } else {
                showNotice(getString(R.string.platform_wallet_missing, name),
                        getString(R.string.platform_web_cashier),
                        () -> { if (current.getAsBoolean()) view.loadUrl(handoff.fallback); });
            }
        };
        if (userTap) launch.run();
        else showNotice(getString(R.string.platform_payment_app, name), getString(R.string.platform_open_payment, name),
                () -> whenWalletCurrent(view, current, frameCheck, true, launch));
    }

    boolean openAlipayQr(String url) {
        WebView view = visibleWindow();
        if (view == null || !PaymentHandoff.isAlipayQr(url)) return false;
        SiteClient client = (SiteClient) view.getWebViewClient();
        if (client.failed || !PaymentHandoff.isCheckout(view.getUrl())) return false;
        PaymentHandoff handoff = PaymentHandoff.from(view.getUrl(), url, null, null);
        return handoff != null && launchWallet(handoff);
    }

    private boolean launchWallet(PaymentHandoff handoff) {
        // Construct a fresh, package-pinned intent. No extras or components supplied by the page travel with it.
        if (paymentWindowOpen) return false;
        if (PaymentHandoff.isAlipayQr(handoff.appUrl)) {
            // Alipay's documented H5 container opens the merchant-issued QR URL inside the
            // wallet even on installations that don't register for HTTPS App Links.
            Uri browser = Uri.parse("alipays://platformapi/startapp").buildUpon()
                    .appendQueryParameter("appId", "20000067")
                    .appendQueryParameter("url", handoff.appUrl).build();
            if (startPaymentWindow(walletIntent(handoff.provider, browser))) return true;
        }
        return startPaymentWindow(walletIntent(handoff.provider, Uri.parse(handoff.appUrl)));
    }

    private Intent walletIntent(PaymentHandoff.Provider provider, Uri address) {
        return new Intent(Intent.ACTION_VIEW, address).setPackage(provider.packageName)
                .addCategory(Intent.CATEGORY_BROWSABLE);
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
        if (paymentWindowOpen) return false;
        try {
            startActivityForResult(intent, PAYMENT_WINDOW);
            paymentWindowOpen = true;
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PAYMENT_WINDOW && !isFinishing()) {
            paymentWindowOpen = false;
            // A wallet can return for success, cancellation or failure. DeepSeek owns the order.
            showNotice(getString(R.string.platform_payment_return), getString(R.string.platform_check_transactions), () -> {
                restartUrl = PlatformNavigation.BASE + PlatformNavigation.TRANSACTIONS;
                navigation = new PlatformNavigation(restartUrl, false);
                openOfficialEntry(restartUrl);
            });
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
        if (keyboardVisible && current != null && !keyDirectory.nativeVisible()) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(current.getWindowToken(), 0);
            return;
        }
        if (keyDirectory.back()) return;
        if (keyDirectory.nativeVisible() && "api_keys".equals(entryRoute)) {
            returnToChat(false);
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
        state.putBoolean(VIEWING_TRANSACTIONS, (PlatformNavigation.BASE + PlatformNavigation.TRANSACTIONS).equals(restartUrl));
    }

    @Override protected void onResume() {
        super.onResume();
        for (WebView view : windows) view.onResume();
        if (keyDirectory != null) keyDirectory.setPaused(false);
    }

    @Override protected void onPause() {
        if (paymentQr != null) paymentQr.cancel();
        if (keyDirectory != null) keyDirectory.setPaused(true);
        for (WebView view : windows) {
            ((SiteClient) view.getWebViewClient()).paymentEpoch++;
            view.onPause();
        }
        if (cookies != null) cookies.flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (paymentQr != null) paymentQr.destroy();
        if (keyDirectory != null) keyDirectory.destroy();
        while (!windows.isEmpty()) removeWindow(visibleWindow());
        super.onDestroy();
    }
}
