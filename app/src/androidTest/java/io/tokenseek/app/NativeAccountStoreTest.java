package io.tokenseek.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeAccountStoreTest {
    @Test public void readAloudOnlyResolvesAnInstalledVoiceForTheCurrentLanguage() {
        Set<Voice> catalog = new HashSet<>();
        Voice local = new Voice("device-zh", Locale.CHINA, 300, 300, false,
                Collections.emptySet());
        catalog.add(local);
        catalog.add(new Voice("download", Locale.CHINA, 300, 300, false,
                Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)));
        catalog.add(new Voice("network", Locale.CHINA, 300, 300, true, Collections.emptySet()));
        assertSame(local, NativeVoiceOptions.find(catalog, "device-zh", Locale.CHINESE));
        assertNull(NativeVoiceOptions.find(catalog, "device-zh", Locale.ENGLISH));
        assertNull(NativeVoiceOptions.find(catalog, "download", Locale.CHINESE));
        assertNull(NativeVoiceOptions.find(catalog, "network", Locale.CHINESE));
        assertNull(NativeVoiceOptions.find(catalog, "", Locale.CHINESE));
    }

    @Test public void migrationAndAccountSwitchKeepNativeEditsAndScopedPreferences() {
        String unique = "test-accounts-" + UUID.randomUUID();
        Context isolated = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(unique, mode);
            }
        };
        try {
            NativeAccountStore store = new NativeAccountStore(isolated);
            String second = UUID.randomUUID().toString();
            JSONObject first = NativeAccountStore.object("id", "default", "username", "Lin", "nickname", "林");
            JSONObject other = NativeAccountStore.object("id", second, "username", "River", "nickname", "江");
            JSONObject archive = NativeAccountStore.object("active", second, "profiles", new JSONArray().put(first).put(other));
            JSONObject preferences = NativeAccountStore.object("global", NativeAccountStore.object("theme", "dark", "font", "large"),
                    second, NativeAccountStore.object("style", "brief"), "default", NativeAccountStore.object("style", "detailed"));

            assertTrue(store.importLegacy(archive, preferences));
            assertEquals(second, store.activeId());
            assertEquals("brief", store.preference("style"));
            assertEquals(115, store.fontScale());
            assertTrue(store.dark());
            assertEquals("", store.saveProfile(false, "River", "North", null));
            assertEquals("duplicate", store.saveProfile(true, "Ｌｉｎ", "another", null));
            assertTrue(store.select("default"));
            assertTrue(store.setPreference("style", "standard"));
            assertTrue(store.importLegacy(archive, preferences));

            NativeAccountStore reopened = new NativeAccountStore(isolated);
            assertEquals("default", reopened.activeId());
            assertEquals("standard", reopened.preference("style"));
            assertEquals(2, reopened.profiles().length());
            assertTrue(reopened.select(second));
            assertEquals("North", reopened.profile().optString("nickname"));
            assertEquals("brief", reopened.preference("style"));
            assertFalse(reopened.select(UUID.randomUUID().toString()));
        } finally {
            isolated.getSharedPreferences(unique, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
