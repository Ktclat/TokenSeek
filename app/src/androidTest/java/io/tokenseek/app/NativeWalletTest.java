package io.tokenseek.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class NativeWalletTest {
    @Test public void showsOfficialBalancesByCurrencyAndTogglesDetailsWithoutCombiningThem() throws JSONException {
        JSONArray rows = new JSONArray()
                .put(row("CNY", "38.60", "30.00", "8.60"))
                .put(row("USD", "-0.1200", "-0.2200", "0.1000"));
        NativeBalanceRequest.Result result = new NativeBalanceRequest.Result("ready", true, rows);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            ArrayList<String> actions = new ArrayList<>();
            NativeFunding panel = new NativeFunding(context, actions::add);
            panel.render(false, false, 100, true, false, result);
            assertNotNull(visible(panel, "38.60  CNY"));
            assertNotNull(visible(panel, "-0.1200  USD"));
            assertNull(visible(panel, "-0.2200"));
            press(panel, "余额明细");
            assertNotNull(visible(panel, "-0.2200"));
            press(panel, "刷新余额");
            panel.render(false, true, 130, true, true, result);
            assertFalse(visible(panel, "正在刷新…").isEnabled());
            assertNotNull(visible(panel, "8.60"));
            press(panel, "余额明细");
            assertNull(visible(panel, "8.60"));
            press(panel, "用量明细");
            press(panel, "返回对话");
            assertEquals(Arrays.asList("refresh", "usage", "chat"), actions);
            assertFalse(visible(panel, "充值 · 暂未接入官方下单").isClickable());
        });
    }

    @Test public void unboundWalletKeepsOnlyNativeDestinationsAndScopesRestoredDetailsToTheProfile() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            ArrayList<String> actions = new ArrayList<>();
            NativeWalletController controller = new NativeWalletController(context, actions::add);
            try {
                controller.bind("first", "", false, false, 100);
                View wallet = controller.view();
                assertNotNull(visible(wallet, "余额"));
                press(wallet, "聊天密钥");
                press(wallet, "用量明细");
                assertEquals(Arrays.asList("keys", "usage"), actions);
                assertNull(visible(wallet, "刷新余额"));
                assertNull(visible(wallet, "返回对话"));
                assertEquals("", controller.balanceLabel());
                assertFalse(containsEditor(wallet));

                Bundle old = new Bundle();
                old.putString("workspace", "first");
                old.putBoolean("details", true);
                old.putString("selection", "custom");
                old.putString("custom", "123.45");
                controller.restoreState(old);
                assertTrue(controller.saveState().getBoolean("details"));
                assertFalse(controller.saveState().containsKey("selection"));
                controller.bind("second", "", true, true, 130);
                controller.restoreState(old);
                assertFalse(controller.saveState().getBoolean("details"));
                assertNotNull(visible(wallet, "Balance"));
                press(wallet, "Usage details");
                assertFalse(visible(wallet, "Top-up · In-app ordering unavailable").isClickable());
            } finally {
                controller.close();
            }
        });
    }

    private static JSONObject row(String currency, String total, String credit, String grant) throws JSONException {
        return new JSONObject().put("currency", currency).put("total", total).put("credit", credit).put("grant", grant);
    }

    private static void press(View root, String caption) {
        View action = visible(root, caption);
        assertTrue(caption, action instanceof Button && action.performClick());
    }

    private static View visible(View view, String caption) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView label && caption.contentEquals(label.getText())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = visible(group.getChildAt(i), caption);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsEditor(View view) {
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) if (containsEditor(group.getChildAt(i))) return true;
        return false;
    }
}
