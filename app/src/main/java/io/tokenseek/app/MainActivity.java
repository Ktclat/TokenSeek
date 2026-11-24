package io.tokenseek.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int PICK_FILES = 40;
    private static final int REQUEST_MICROPHONE = 42;
    private static final int OPEN_PLATFORM = 43;
    private boolean platformOpen;
    private String platformParentHash;
    private boolean localPageReady;
    private String pendingPlatformPage;
    // The capability is inserted into a same-origin script only for the lifetime of this WebView.
    private final String capability = UUID.randomUUID().toString();
    private WebView web;
    private FrameLayout container;
    private Boolean pageDark;
    private KeyVault vault;
    private DocumentExport export;
    private PermissionRequest audioRequest;
    private ValueCallback<Uri[]> filesCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        vault = new KeyVault(this);
        export = new DocumentExport(this);
        container = new FrameLayout(this);
        web = new WebView(this);
        container.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
        applySystemBars();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            container.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return WindowInsets.CONSUMED;
            });
            container.requestApplyInsets();
        }
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        // This WebView only hosts the bundled chat. Official pages use their own activity.
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebViewClient(new LocalSite(this, capability));
        web.setWebChromeClient(new Chrome());
        web.addJavascriptInterface(new Bridge(), "TokenSeekHost");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        if (state != null) {
            platformOpen = state.getBoolean("platformOpen");
            platformParentHash = state.containsKey("platformParentHash")
                    ? state.getString("platformParentHash") : "#return-official-account";
            // The old chat WebView is gone after process recreation. Restore its screen only
            // when the official activity returns, unless the user explicitly selects binding.
            pendingPlatformPage = platformOpen ? platformParentHash : state.getString("pendingPlatformPage");
        }
        web.loadUrl(LocalSite.INDEX);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("platformOpen", platformOpen);
        state.putString("platformParentHash", platformParentHash);
        state.putString("pendingPlatformPage", pendingPlatformPage);
    }

    void onLocalPageReady() {
        localPageReady = true;
        deliverPlatformReturn();
    }

    private void deliverPlatformReturn() {
        if (platformOpen || !localPageReady || pendingPlatformPage == null) return;
        String destination = JSONObject.quote(LocalSite.INDEX + pendingPlatformPage);
        pendingPlatformPage = null;
        web.evaluateJavascript("location.replace(" + destination + ")", null);
    }

    private void applySystemBars() {
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = pageDark != null ? pageDark : systemDark;
        int background = dark ? Color.rgb(21, 21, 23) : Color.WHITE;
        container.setBackgroundColor(background);
        web.setBackgroundColor(background);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        View decor = getWindow().getDecorView();
        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~lightFlags
                : decor.getSystemUiVisibility() | lightFlags);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applySystemBars();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web == null) return;
        web.onResume();
        web.evaluateJavascript("window.dispatchEvent(new Event('tokenseek:resume'))", null);
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    void openExternal(Uri uri) {
        openExternal(uri, "");
    }

    private void openExternal(Uri uri, String fromPage) {
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || LocalSite.ownedOrigin(uri)) return;
        if (OfficialPlatformActivity.isPlatformUrl(uri)) {
            if (!platformOpen) {
                platformOpen = true;
                if ("wallet".equals(fromPage)) platformParentHash = "#return-official-wallet";
                else if ("keys".equals(fromPage)) platformParentHash = "#return-official-keys";
                else if ("account".equals(fromPage)) platformParentHash = "#return-official-account";
                else platformParentHash = null;
                startActivityForResult(OfficialPlatformActivity.createIntent(this, uri.toString()), OPEN_PLATFORM);
            }
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "没有可打开此链接的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    void pickerResult(String result) {
        if (!isDestroyed()) {
            web.evaluateJavascript("window.tokenSeekPickerResult?.(" + JSONObject.quote(result) + ")", null);
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                   FileChooserParams params) {
            if (filesCallback != null) {
                callback.onReceiveValue(null);
                return true;
            }
            filesCallback = callback;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            // Accept lists can contain extensions as well as MIME types. Android pickers only accept MIME.
            String[] types = Arrays.stream(params.getAcceptTypes())
                    .flatMap(type -> Arrays.stream(type.split(","))).map(String::trim)
                    .filter(type -> type.contains("/") && !type.contains("*/*"))
                    .toArray(String[]::new);
            if (types.length > 0 && Arrays.stream(params.getAcceptTypes()).noneMatch(s -> s.contains("."))) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
            }
            try {
                startActivityForResult(intent, PICK_FILES);
            } catch (ActivityNotFoundException unavailable) {
                filesCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override public void onPermissionRequest(PermissionRequest request) {
            if (!LocalSite.ownedOrigin(request.getOrigin())
                    || !Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    || audioRequest != null) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                return;
            }
            audioRequest = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
        }

        @Override public void onPermissionRequestCanceled(PermissionRequest request) {
            if (audioRequest == request) audioRequest = null;
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQUEST_MICROPHONE || audioRequest == null) return;
        PermissionRequest request = audioRequest;
        audioRequest = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            request.deny();
        }
    }

    @Override protected void onActivityResult(int request, int status, Intent data) {
        super.onActivityResult(request, status, data);
        if (request == OPEN_PLATFORM) {
            platformOpen = false;
            if (status == RESULT_OK && data != null
                    && data.getBooleanExtra(OfficialPlatformActivity.EXTRA_BIND, false)) {
                pendingPlatformPage = "#bind-official-key";
            }
            platformParentHash = null;
            deliverPlatformReturn();
        } else if (request == DocumentExport.PICK_DESTINATION) {
            export.result(status, data);
        } else if (request == PICK_FILES && filesCallback != null) {
            List<Uri> files = new ArrayList<>();
            if (status == RESULT_OK && data != null) {
                ClipData clips = data.getClipData();
                if (clips != null) {
                    for (int i = 0; i < clips.getItemCount(); i++) {
                        addDocument(files, clips.getItemAt(i).getUri());
                    }
                } else {
                    addDocument(files, data.getData());
                }
            }
            ValueCallback<Uri[]> callback = filesCallback;
            filesCallback = null;
            callback.onReceiveValue(files.isEmpty() ? null : files.toArray(new Uri[0]));
        }
    }

    private static void addDocument(List<Uri> files, Uri uri) {
        if (uri != null && "content".equals(uri.getScheme())) files.add(uri);
    }

    @Override public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        web.evaluateJavascript("window.tokenSeekHandleBack?.() === true", result -> {
            if (isFinishing() || isDestroyed() || "true".equals(result)) return;
            if (web.canGoBack()) web.goBack();
            else if (!moveTaskToBack(true)) finishAfterTransition();
        });
    }

    @Override protected void onDestroy() {
        if (filesCallback != null) filesCallback.onReceiveValue(null);
        if (audioRequest != null) audioRequest.deny();
        export.close();
        web.removeJavascriptInterface("TokenSeekHost");
        web.stopLoading();
        container.removeView(web);
        web.destroy();
        super.onDestroy();
    }

    private final class Bridge {
        @JavascriptInterface public void setDarkTheme(String token, boolean dark) {
            if (capability.equals(token)) {
                runOnUiThread(() -> {
                    pageDark = dark;
                    applySystemBars();
                });
            }
        }
        @JavascriptInterface public String manageKeys(String token, String action, String id, String name, String secret) {
            return capability.equals(token) ? vault.manage(action, id, name, secret) : "";
        }
        @JavascriptInterface public void openLink(String token, String url, String fromPage) {
            if (capability.equals(token) && url != null) {
                runOnUiThread(() -> openExternal(Uri.parse(url), fromPage));
            }
        }
        @JavascriptInterface public boolean chooseDocument(String token, String name, String mime) {
            return capability.equals(token) && export.choose(name, mime);
        }
        @JavascriptInterface public boolean writeDocument(String token, String chunk) {
            return capability.equals(token) && export.append(chunk);
        }
        @JavascriptInterface public boolean closeDocument(String token) {
            return capability.equals(token) && export.close();
        }
        @JavascriptInterface public void showError(String token, String message) {
            if (capability.equals(token) && message != null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
            }
        }
    }
}
