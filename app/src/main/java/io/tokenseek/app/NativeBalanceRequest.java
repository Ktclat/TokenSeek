package io.tokenseek.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/** One cancellable GET of the active key's balance. No platform cookies or redirects are needed. */
final class NativeBalanceRequest {
    static final class Result {
        final String status;
        final boolean available;
        final JSONArray balances;

        Result(String status, boolean available, JSONArray balances) {
            this.status = status;
            this.available = available;
            this.balances = balances;
        }

        static Result empty(String status) { return new Result(status, false, new JSONArray()); }
    }

    private volatile HttpsURLConnection connection;
    private volatile boolean cancelled;

    Result fetch(String key) {
        try {
            HttpsURLConnection request = (HttpsURLConnection) new URL("https://api.deepseek.com/user/balance").openConnection();
            connection = request;
            if (cancelled) return Result.empty("error");
            request.setInstanceFollowRedirects(false);
            request.setRequestMethod("GET");
            request.setConnectTimeout(15000);
            request.setReadTimeout(15000);
            request.setUseCaches(false);
            request.setRequestProperty("Authorization", "Bearer " + key);
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("Cache-Control", "no-store");
            int status = request.getResponseCode();
            if (status == 401 || status == 403) return Result.empty("invalid");
            if (status != 200) return Result.empty("error");
            try (InputStream input = request.getInputStream()) {
                return parse(input);
            }
        } catch (IOException | JSONException | IllegalArgumentException failure) {
            return Result.empty("error");
        } finally {
            HttpsURLConnection request = connection;
            if (request != null) request.disconnect();
            connection = null;
        }
    }

    static Result parse(InputStream input) throws IOException, JSONException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (body.size() + count > 256 * 1024) throw new IOException("Oversized balance response");
            body.write(buffer, 0, count);
        }
        JSONObject json = new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
        Object available = json.get("is_available");
        if (!(available instanceof Boolean)) throw new JSONException("Invalid balance availability");
        JSONArray rows = json.getJSONArray("balance_infos");
        JSONArray balances = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Object currency = row.get("currency");
            if (!(currency instanceof String) || ((String) currency).trim().isEmpty()) throw new JSONException("Missing currency");
            balances.put(new JSONObject().put("currency", currency)
                    .put("total", decimal(row.get("total_balance")))
                    .put("credit", decimal(row.get("topped_up_balance")))
                    .put("grant", decimal(row.get("granted_balance"))));
        }
        return new Result("ready", (boolean) available, balances);
    }

    private static String decimal(Object value) throws JSONException {
        if (!(value instanceof String) || !((String) value).matches("-?[0-9]+(?:\\.[0-9]+)?"))
            throw new JSONException("Invalid balance amount");
        return (String) value;
    }

    void cancel() {
        cancelled = true;
        HttpsURLConnection request = connection;
        if (request != null) request.disconnect();
    }
}
