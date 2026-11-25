package io.tokenseek.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
public class NativeAccountNavigationTest {
    @Test public void keyBalanceAndUsageOpenAndReturnInsideTheApp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        NativeAccountStore accounts = new NativeAccountStore(context);
        SharedPreferences profiles = context.getSharedPreferences("native_accounts_v1", Context.MODE_PRIVATE);
        String previousDirectory = profiles.getString("directory", null);
        String nickname = "Navigation-" + UUID.randomUUID();
        boolean english = accounts.locale().startsWith("en");

        IntentFilter web = new IntentFilter(Intent.ACTION_VIEW);
        web.addCategory(Intent.CATEGORY_BROWSABLE);
        web.addCategory(Intent.CATEGORY_DEFAULT);
        web.addDataScheme("https");
        web.addDataScheme("http");
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                web, new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            // The new profile has no selected key, so opening Balance cannot query the network.
            assertEquals("", accounts.saveProfile(true, nickname, nickname, null));
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(app -> {
                    click(app, english ? "Open sidebar" : "打开侧栏");
                    // Opening the sidebar creates the identity row before its slide animation ends.
                    click(app, (english ? "Account" : "账户") + " · " + nickname);
                    String connect = english ? "Connect DeepSeek API" : "连接 DeepSeek API";
                    String balance = english ? "Balance" : "余额";
                    String usage = english ? "Platform usage" : "开放平台用量";
                    click(app, connect);
                    assertScreen(app, NativeChatKeys.class);
                    app.onBackPressed();
                    assertScreen(app, NativeAccountPages.class);
                    click(app, balance);
                    assertScreen(app, NativeFunding.class);
                    app.onBackPressed();
                    assertScreen(app, NativeAccountPages.class);
                    click(app, usage);
                    assertScreen(app, NativePlatformUsagePage.class);
                    app.onBackPressed();
                    assertScreen(app, NativeAccountPages.class);
                    assertNotNull(find(app.findViewById(android.R.id.content), view ->
                            view instanceof TextView && connect.contentEquals(((TextView) view).getText())));
                });
                assertEquals("A native account action launched a web URL", 0, monitor.getHits());
            }
        } finally {
            instrumentation.removeMonitor(monitor);
            assertTrue(profiles.edit().putString("directory", previousDirectory).commit());
        }
    }

    private static void assertScreen(Activity app, Class<? extends View> type) {
        assertNotNull(type.getSimpleName(), find(app.findViewById(android.R.id.content),
                view -> type.isInstance(view) && view.isShown()));
    }

    private static void click(Activity app, String label) {
        View row = find(app.findViewById(android.R.id.content), view ->
                label.equals(view.getContentDescription())
                        || view instanceof TextView && label.contentEquals(((TextView) view).getText()));
        assertNotNull(label, row);
        while (!row.isClickable() && row.getParent() instanceof View parent) row = parent;
        assertTrue(label, row.isEnabled() && row.performClick());
    }

    private static View find(View view, Predicate<View> matches) {
        if (matches.test(view)) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), matches);
                if (match != null) return match;
            }
        }
        return null;
    }
}
