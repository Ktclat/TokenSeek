package io.tokenseek.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class NativeKeyEntryTest {
    @Test public void expandedActionsFollowTheSameKeyWhenTheDirectoryReorders() throws JSONException {
        JSONObject first = new JSONObject().put("id", "row-a").put("name", "Chat")
                .put("masked", "sk-****1234").put("rename", true).put("revoke", true);
        JSONObject second = new JSONObject().put("id", "row-b").put("name", "Chat")
                .put("masked", "sk-****5678").put("rename", true).put("revoke", true);
        JSONObject before = new JSONObject().put("create", true).put("rows", new JSONArray().put(first).put(second));
        JSONObject reordered = new JSONObject().put("create", true).put("rows", new JSONArray().put(second).put(first));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            FrameLayout layer = new FrameLayout(context);
            ArrayList<String> commands = new ArrayList<>();
            OfficialKeyPanel panel = new OfficialKeyPanel(context, commands::add);
            layer.addView(panel);
            try {
                panel.render(before);
                ArrayList<View> secondRow = new ArrayList<>();
                panel.findViewsWithText(secondRow, "5678", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertEquals(1, secondRow.size());
                assertTrue(secondRow.get(0).performClick());
                panel.render(reordered);
            } catch (JSONException ex) {
                throw new AssertionError(ex);
            }
            assertTrue(withText(layer, R.string.key_panel_rename).performClick());
            assertEquals(1, commands.size());
            assertEquals("rename:row-b", commands.get(0));
            assertTrue(panel.handleBack());
            ArrayList<View> actions = new ArrayList<>();
            layer.findViewsWithText(actions, context.getString(R.string.key_panel_rename), View.FIND_VIEWS_WITH_TEXT);
            assertTrue(actions.isEmpty());
            assertFalse(panel.handleBack());
        });
    }

    private static View withText(FrameLayout layer, int stringId) {
        ArrayList<View> matches = new ArrayList<>();
        layer.findViewsWithText(matches, layer.getResources().getText(stringId), View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    @Test public void directoryOpensBeforeTheWebsiteAndKeepsErrorsAndSignInNative() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            FrameLayout layer = new FrameLayout(context);
            Button returnButton = new Button(context);
            boolean[] nativeChrome = {false};
            OfficialKeyDirectory directory = new OfficialKeyDirectory(context, layer, returnButton,
                    new OfficialKeyDirectory.Host() {
                        public void nativeChrome(boolean visible) { nativeChrome[0] = visible; }
                        public void reloadDirectory() { }
                        public void leaveDirectory() { }
                    });
            try {
                String keys = PlatformNavigation.BASE + PlatformNavigation.KEYS;
                directory.route(null, keys, true, false, false, true);
                assertTrue(nativeChrome[0]);
                assertEquals(View.VISIBLE, layer.getVisibility());
                assertEquals(View.GONE, returnButton.getVisibility());
                assertTrue(layer.getChildAt(0) instanceof OfficialKeyPanel);
                assertEquals(View.VISIBLE, withText(layer, R.string.key_panel_loading).getVisibility());

                directory.route(null, keys, true, false, true, true);
                assertTrue(nativeChrome[0]);
                assertTrue(directory.nativeVisible());
                assertEquals(View.VISIBLE, withText(layer, R.string.key_panel_unavailable).getVisibility());

                directory.route(null, PlatformNavigation.BASE + PlatformNavigation.LOGIN, true, false, false, true);
                assertTrue(nativeChrome[0]);
                assertEquals(View.VISIBLE, layer.getVisibility());

                assertTrue(withText(layer, R.string.key_panel_login).performClick());
                assertFalse(directory.nativeVisible());

                directory.route(null, PlatformNavigation.BASE + PlatformNavigation.TOP_UP, true, false, false, false);
                assertFalse(nativeChrome[0]);
                assertEquals(View.GONE, layer.getVisibility());
            } finally {
                directory.destroy();
            }
        });
    }
}
