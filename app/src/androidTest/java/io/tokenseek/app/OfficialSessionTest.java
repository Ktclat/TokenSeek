package io.tokenseek.app;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.Profile;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class OfficialSessionTest {
    private static final String FIRST = "b109cc33-76be-4c37-a1a1-df34be7d667a";
    private static final String SECOND = "bb563f88-4cd3-4c90-a1b7-89205de639af";
    private static final String COOKIE_URL = "https://tokenseek-session-test.invalid/";
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();
    private OfficialPlatformActivity activity;

    private void open(String workspace) {
        close();
        Intent intent = OfficialPlatformActivity.createIntent(device.getTargetContext(),
                PlatformNavigation.BASE + "/sign_in", workspace).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (OfficialPlatformActivity) device.startActivitySync(intent);
    }

    private WebView root() {
        return (WebView) ((FrameLayout) activity.findViewById(R.id.platform_windows)).getChildAt(0);
    }

    @After public void close() {
        if (activity == null) return;
        device.runOnMainSync(activity::finish);
        device.waitForIdleSync();
        activity = null;
    }

    @Test public void browserEngineIsolatesWorkspaceCookiesAndRetainsThePopupAndReopenedSession() {
        Assume.assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));
        String cookie = "tokenseek_" + UUID.randomUUID().toString().replace("-", "");
        open(FIRST);
        device.runOnMainSync(() -> {
            WebView parent = root();
            Profile first = WebViewCompat.getProfile(parent);
            assertEquals("tokenseek-" + FIRST, first.getName());
            first.getCookieManager().setCookie(COOKIE_URL, cookie + "=first; Path=/; Secure; Max-Age=300");
            first.getCookieManager().flush();
            WebView.WebViewTransport transport = parent.new WebViewTransport();
            Message result = Message.obtain(new Handler(Looper.getMainLooper()), 0, transport);
            assertTrue(parent.getWebChromeClient().onCreateWindow(parent, false, true, result));
            Profile child = WebViewCompat.getProfile(transport.getWebView());
            assertEquals(first.getName(), child.getName());
            assertTrue(child.getCookieManager().getCookie(COOKIE_URL).contains(cookie + "=first"));
        });
        open(SECOND);
        device.runOnMainSync(() -> {
            Profile second = WebViewCompat.getProfile(root());
            assertEquals("tokenseek-" + SECOND, second.getName());
            assertFalse(String.valueOf(second.getCookieManager().getCookie(COOKIE_URL)).contains(cookie + "="));
        });
        open("default");
        device.runOnMainSync(() -> {
            assertEquals(Profile.DEFAULT_PROFILE_NAME, WebViewCompat.getProfile(root()).getName());
            assertFalse(String.valueOf(CookieManager.getInstance().getCookie(COOKIE_URL)).contains(cookie + "="));
        });
        open(FIRST);
        device.runOnMainSync(() -> {
            CookieManager manager = WebViewCompat.getProfile(root()).getCookieManager();
            assertTrue(manager.getCookie(COOKIE_URL).contains(cookie + "=first"));
            manager.setCookie(COOKIE_URL, cookie + "=; Secure; Path=/; Max-Age=0");
        });
    }

    @Test public void olderProvidersWarnInBothTheNewWorkspaceAndTheReturningOriginal() {
        Assume.assumeFalse(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));
        SharedPreferences prefs = device.getTargetContext().getSharedPreferences("official-sessions", 0);
        boolean previouslyShared = prefs.getBoolean("shared-session-used", false);
        try {
            open(FIRST);
            device.runOnMainSync(() -> assertEquals(View.VISIBLE,
                    activity.findViewById(R.id.platform_session_warning).getVisibility()));
            open("default");
            device.runOnMainSync(() -> assertEquals(View.VISIBLE,
                    activity.findViewById(R.id.platform_session_warning).getVisibility()));
        } finally {
            if (!previouslyShared) prefs.edit().remove("shared-session-used").commit();
        }
    }
}
