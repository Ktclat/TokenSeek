package io.tokenseek.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class KeyVault {
    private static final String ALIAS = "tokenseek.api-key";
    private static final String RECORD = "encrypted-api-key";
    private static final String RING = "encrypted-api-keys-v1";
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;

    KeyVault(Context context) {
        prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE);
    }

    private static SecretKey getKey(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(ALIAS, null);
        if (key != null) return key;
        if (!create) throw new GeneralSecurityException("Missing keystore entry");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String decrypt(String record) throws Exception {
        String[] fields = record.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(false),
                new GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static JSONObject entry(String id, String name, String secret) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("secret", secret);
    }

    private JSONObject load(String ringName, boolean useLegacy) throws Exception {
        String ring = prefs.getString(ringName, null);
        if (ring != null) return new JSONObject(decrypt(ring));
        JSONArray entries = new JSONArray();
        JSONObject state = new JSONObject().put("entries", entries).put("activeId", "");
        String legacy = useLegacy ? prefs.getString(RECORD, null) : null;
        if (legacy != null) {
            entries.put(entry("imported", "", decrypt(legacy)));
            state.put("activeId", "imported");
        }
        return state;
    }

    private boolean save(JSONObject state, String ringName, boolean useLegacy) throws Exception {
        String previousRing = prefs.getString(ringName, null);
        String previousLegacy = useLegacy ? prefs.getString(RECORD, null) : null;
        String next = state.getJSONArray("entries").length() == 0 ? null : encrypt(state.toString());
        SharedPreferences.Editor editor = prefs.edit().putString(ringName, next);
        if (useLegacy) editor.remove(RECORD);
        if (editor.commit()) return true;
        // Android can publish a failed commit to the in-process preferences map first.
        editor = prefs.edit().putString(ringName, previousRing);
        if (useLegacy) editor.putString(RECORD, previousLegacy);
        editor.commit();
        return false;
    }

    private static int find(JSONArray entries, String field, String target) throws Exception {
        for (int i = 0; i < entries.length(); i++) {
            if (target.equals(entries.getJSONObject(i).getString(field))) return i;
        }
        return -1;
    }

    private static boolean update(JSONObject state, String action, String id, String name, String key) throws Exception {
        JSONArray entries = state.getJSONArray("entries");
        if ("add".equals(action)) {
            if (key == null || key.isEmpty() || key.length() > 4096 || name == null || name.length() > 64) return false;
            int index = find(entries, "secret", key);
            if (index < 0) {
                id = UUID.randomUUID().toString();
                entries.put(entry(id, name.trim(), key));
            } else {
                JSONObject existing = entries.getJSONObject(index);
                id = existing.getString("id");
                if (!name.trim().isEmpty()) existing.put("name", name.trim());
            }
            state.put("activeId", id);
            return true;
        }
        if (id == null) return false;
        if ("select".equals(action) && id.isEmpty()) {
            state.put("activeId", "");
            return true;
        }
        int index = find(entries, "id", id);
        if (index < 0) return false;
        switch (action) {
            case "select":
                state.put("activeId", id);
                return true;
            case "rename":
                if (name == null || name.length() > 64) return false;
                entries.getJSONObject(index).put("name", name.trim());
                return true;
            case "remove":
                entries.remove(index);
                if (id.equals(state.getString("activeId"))) state.put("activeId", "");
                return true;
            default:
                return false;
        }
    }

    private static JSONObject snapshot(JSONObject state, boolean includeActiveKey) throws Exception {
        String active = state.getString("activeId");
        JSONArray entries = state.getJSONArray("entries");
        JSONArray visible = new JSONArray();
        JSONObject result = new JSONObject().put("entries", visible)
                .put("activeId", active.isEmpty() ? JSONObject.NULL : active);
        if (includeActiveKey) result.put("apiKey", JSONObject.NULL);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.getJSONObject(i);
            String id = item.getString("id");
            String secret = item.getString("secret");
            visible.put(new JSONObject().put("id", id).put("name", item.getString("name"))
                    .put("suffix", secret.substring(Math.max(0, secret.length() - 4))));
            if (includeActiveKey && id.equals(active)) result.put("apiKey", secret);
        }
        return result;
    }

    String manage(String action, String id, String name, String secret) {
        return manageProfile("default", action, id, name, secret);
    }

    String manageProfile(String profile, String action, String id, String name, String secret) {
        boolean original = "default".equals(profile);
        if (!original && (profile == null || !profile.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) return "";
        String ringName = original ? RING : RING + "." + profile;
        synchronized (LOCK) {
            try {
                JSONObject state = load(ringName, original);
                boolean listing = "list".equals(action);
                if (listing || "read".equals(action)) {
                    // If an upgrade cannot be committed, the old encrypted key still works.
                    if (original && !prefs.contains(ringName) && prefs.contains(RECORD)) {
                        try { save(state, ringName, original); } catch (Exception retryOnNextRead) {
                            // The original record has already been decrypted and is still usable.
                        }
                    }
                } else if (!update(state, action, id, name, secret) || !save(state, ringName, original)) {
                    return "";
                }
                return snapshot(state, !listing).toString();
            } catch (Exception unavailable) {
                return "";
            }
        }
    }
}
