package io.tokenseek.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class NativeChatKeysVaultTest {
    @Test public void staleDeviceEditorCannotDeselectOrRemoveAfterAnotherKeyWasBound() throws Exception {
        KeyVault vault = new KeyVault(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String owner = UUID.randomUUID().toString();
        try {
            String empty = vault.manageProfile(owner, "list", "", "", "");
            String first = vault.manageProfile(owner, "add", "", "Sample A", "device-fixture-one", empty);
            String firstId = new JSONObject(first).getString("activeId");
            String beforeBinding = vault.manageProfile(owner, "list", "", "", "");
            assertFalse(new JSONObject(beforeBinding).has("apiKey"));

            String second = vault.manageProfile(owner, "add", "", "Sample B", "device-fixture-two");
            String secondId = new JSONObject(second).getString("activeId");
            assertNotEquals(firstId, secondId);
            assertEquals("", vault.manageProfile(owner, "select", "", "", "", beforeBinding));
            assertEquals("", vault.manageProfile(owner, "remove", firstId, "", "", beforeBinding));

            String current = vault.manageProfile(owner, "list", "", "", "");
            assertEquals(secondId, new JSONObject(current).getString("activeId"));
            assertEquals(2, new JSONObject(current).getJSONArray("entries").length());
            String cleared = vault.manageProfile(owner, "select", "", "", "", current);
            assertTrue(new JSONObject(cleared).isNull("activeId"));
        } finally {
            JSONArray keys = new JSONObject(vault.manageProfile(owner, "list", "", "", "")).getJSONArray("entries");
            for (int i = 0; i < keys.length(); i++) {
                vault.manageProfile(owner, "remove", keys.getJSONObject(i).getString("id"), "", "");
            }
        }
    }
}
