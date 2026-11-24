package io.tokenseek.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class KeyVault {
    private static final String ALIAS = "tokenseek.api-key";
    private static final String RECORD = "encrypted-api-key";
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

    synchronized String read() {
        String record = prefs.getString(RECORD, null);
        if (record == null) return "";
        try {
            String[] fields = record.split("\\.", 2);
            byte[] iv = Base64.decode(fields[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(fields[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            // Rebinding in settings may replace an entry invalidated by the device.
            return "";
        }
    }

    synchronized boolean write(String value) {
        if (value == null || value.length() > 4096) return false;
        if (value.isEmpty()) return prefs.edit().remove(RECORD).commit();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String record = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            return prefs.edit().putString(RECORD, record).commit();
        } catch (Exception unavailable) {
            return false;
        }
    }
}
