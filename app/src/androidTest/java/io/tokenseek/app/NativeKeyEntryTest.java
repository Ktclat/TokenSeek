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

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class NativeKeyEntryTest {
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
