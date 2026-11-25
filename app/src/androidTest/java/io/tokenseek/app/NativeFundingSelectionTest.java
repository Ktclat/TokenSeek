package io.tokenseek.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
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

@RunWith(AndroidJUnit4.class)
public class NativeFundingSelectionTest {
    @Test public void restoresUnsubmittedAmountOnlyToItsOwnAccount() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            ArrayList<String> actions = new ArrayList<>();
            NativeWalletController first = new NativeWalletController(context, (id, value) -> actions.add(id));
            NativeWalletController restored = new NativeWalletController(context, (id, value) -> actions.add(id));
            try {
                first.bind("alice", "", false, false, 100);
                button(first.view(), "自定义").performClick();
                editor(first.view()).setText("100.06");
                android.os.Bundle form = first.saveForm();

                restored.bind("bob", "", false, false, 100);
                restored.restoreForm(form);
                assertNull(editor(restored.view()));
                assertFalse(button(restored.view(), "核对充值金额").isEnabled());

                restored.bind("alice", "", false, false, 100);
                restored.restoreForm(form);
                assertEquals("100.06", editor(restored.view()).getText().toString());
                assertTrue(button(restored.view(), "核对充值金额 · ¥100.06").isEnabled());
                assertTrue(actions.isEmpty());
            } finally {
                first.close();
                restored.close();
            }
        });
    }

    @Test public void unboundWalletOpensChatKeysAndReviewsTheSelectedAmount() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            ArrayList<String> actions = new ArrayList<>();
            NativeWalletController controller = new NativeWalletController(context, (id, amount) -> actions.add(id + "/" + amount));
            try {
                controller.bind("default", "", false, false, 100);
                View wallet = controller.view();
                button(wallet, "聊天密钥").performClick();
                button(wallet, "¥50").performClick();
                button(wallet, "核对充值金额 · ¥50").performClick();
                assertEquals("keys/", actions.get(0));
                assertEquals("checkout/50.00", actions.get(1));
                controller.bind("default", "", true, false, 100);
                assertTrue(button(wallet, "Chat keys").isEnabled());
                assertTrue(button(wallet, "Review top-up · ¥50").isEnabled());
            } finally {
                controller.close();
            }
        });
    }

    @Test public void customDraftAndPresetUseTheirOwnCheckoutAmountsAcrossRefresh() throws JSONException {
        JSONObject labels = new JSONObject().put("customChoice", "Custom").put("custom", "Custom amount")
                .put("checkout", "Checkout").put("refresh", "Refresh").put("refreshing", "Refreshing");
        JSONObject screen = new JSONObject().put("labels", labels).put("bound", true)
                .put("presets", new JSONArray().put("10").put("50").put("100"));
        JSONObject refreshed = new JSONObject(screen.toString()).put("refreshing", true);
        JSONObject large = new JSONObject(screen.toString()).put("scale", 130);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            ArrayList<String> actions = new ArrayList<>();
            NativeFunding panel = new NativeFunding(context, (action, amount) -> actions.add(action + "/" + amount));
            panel.render(screen);
            assertFalse(button(panel, "Checkout").isEnabled());
            assertNull(editor(panel));

            button(panel, "Custom").performClick();
            editor(panel).setText("50.00");
            assertTrue(button(panel, "Custom").isSelected());
            assertFalse(button(panel, "¥50").isSelected());
            editor(panel).setText("999999999999.07");
            button(panel, "¥50").performClick();
            assertNull(editor(panel));
            panel.render(refreshed);
            assertTrue(button(panel, "¥50").isSelected());
            assertFalse(button(panel, "Refreshing").isEnabled());
            button(panel, "Checkout · ¥50").performClick();

            button(panel, "Custom").performClick();
            panel.render(large);
            assertEquals("999999999999.07", editor(panel).getText().toString());
            assertTrue(button(panel, "Custom").isSelected());
            button(panel, "Checkout · ¥999999999999.07").performClick();
            assertEquals(2, actions.size());
            assertEquals("checkout/50.00", actions.get(0));
            assertEquals("checkout/999999999999.07", actions.get(1));
        });
    }

    private static View button(View panel, String caption) {
        ArrayList<View> matches = new ArrayList<>();
        panel.findViewsWithText(matches, caption, View.FIND_VIEWS_WITH_TEXT);
        for (View match : matches)
            if (match instanceof TextView && caption.contentEquals(((TextView) match).getText())) return match;
        throw new AssertionError("Missing button: " + caption);
    }

    private static EditText editor(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText match = editor(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }
}
