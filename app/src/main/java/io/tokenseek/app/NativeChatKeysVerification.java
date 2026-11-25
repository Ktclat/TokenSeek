package io.tokenseek.app;

import java.io.IOException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/** A single user-requested GET /models. Only the HTTP result is needed to validate the key. */
final class NativeChatKeysVerification {
    private volatile HttpsURLConnection connection;
    private volatile boolean cancelled;

    int verify(String key) {
        if (key.isEmpty() || key.length() > 4096) return R.string.chat_keys_invalid;
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) < 33 || key.charAt(i) > 126) return R.string.chat_keys_invalid;
        }
        try {
            HttpsURLConnection request = (HttpsURLConnection) new URL("https://api.deepseek.com/models").openConnection();
            connection = request;
            if (cancelled) return R.string.chat_keys_network;
            request.setRequestMethod("GET");
            request.setInstanceFollowRedirects(false);
            request.setUseCaches(false);
            request.setConnectTimeout(15000);
            request.setReadTimeout(15000);
            request.setRequestProperty("Authorization", "Bearer " + key);
            request.setRequestProperty("Cache-Control", "no-store");
            int status = request.getResponseCode();
            if (status >= 200 && status < 300) return 0;
            if (status == 401 || status == 403) return R.string.chat_keys_invalid;
            return R.string.chat_keys_limited;
        } catch (IOException | IllegalArgumentException failure) {
            return R.string.chat_keys_network;
        } finally {
            HttpsURLConnection request = connection;
            if (request != null) request.disconnect();
            connection = null;
        }
    }

    void cancel() {
        cancelled = true;
        HttpsURLConnection request = connection;
        if (request != null) request.disconnect();
    }
}
