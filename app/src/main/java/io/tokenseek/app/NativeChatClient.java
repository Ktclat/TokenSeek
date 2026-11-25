package io.tokenseek.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/** Android client for the same official Messages and Files APIs as deepseek-ai/chat-demo. */
final class NativeChatClient implements AutoCloseable {
    static final String FLASH = "deepseek-flash";
    static final String PRO = "deepseek-v4-pro";
    static final String FILES_BETA = "files-api-2025-04-14";
    private static final String API = "https://api.deepseek.com/anthropic/v1/";
    private static final String SYSTEM = "该助手为DeepSeek，由深度求索公司创造。";

    interface Listener { void update(JSONObject reply); }
    interface UploadListener { void complete(JSONObject file, String error); }
    interface AccountKey { String selected(String owner) throws JSONException; }
    interface Transport { HttpsURLConnection open(URL url) throws IOException; }

    private final Context context;
    private final AccountKey keys;
    private final Transport transport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Call> calls = new HashSet<>();
    private volatile boolean closed;

    NativeChatClient(Context context) {
        this(context, owner -> {
            String value = new KeyVault(context).manageProfile(owner, "read", "", "", "");
            if (value.isEmpty()) return "";
            JSONObject record = new JSONObject(value);
            return record.isNull("apiKey") ? "" : record.getString("apiKey");
        }, url -> (HttpsURLConnection) url.openConnection());
    }

    NativeChatClient(Context context, AccountKey keys, Transport transport) {
        this.context = context.getApplicationContext();
        this.keys = keys;
        this.transport = transport;
    }

    final class Call {
        private Runnable cancelled, pending, progress;
        private boolean terminal;
        private volatile boolean stopped;
        private HttpsURLConnection connection;

        private Call() { }

        private synchronized void attach(HttpsURLConnection active) throws InterruptedIOException {
            check();
            connection = active;
        }

        void check() throws InterruptedIOException {
            if (stopped || closed) throw new InterruptedIOException();
        }

        private synchronized void deliver(Runnable result, boolean done) {
            if (terminal) return;
            if (done) {
                terminal = true;
                if (progress != null) { main.removeCallbacks(progress); progress = null; }
            }
            if (pending == null) main.post(() -> {
                Runnable next;
                synchronized (Call.this) { next = pending; pending = null; }
                if (next != null && !closed) next.run();
            });
            pending = result;
        }

        private synchronized void scheduleProgress(Listener listener, NativeChatResponse reply) {
            if (progress != null || stopped || terminal || closed) return;
            // A burst can end with no more tokens for a long time. Flush even if the next frame is delayed.
            progress = () -> {
                synchronized (Call.this) {
                    progress = null;
                    if (stopped || terminal || closed) return;
                }
                listener.update(reply.result("streaming", ""));
            };
            main.postDelayed(progress, 50L);
        }

        private synchronized void reply(Listener listener, JSONObject state, boolean done) {
            if (stopped || terminal) return;
            deliver(() -> listener.update(state), done);
        }

        void stop() {
            HttpsURLConnection open;
            synchronized (this) {
                if (stopped || terminal) return;
                stopped = true;
                open = connection;
                if (cancelled != null) deliver(cancelled, true);
            }
            // Android connection teardown can wait for a blocked read. Keep it off the UI thread.
            if (open != null) disconnectAsync(open);
        }

        private void release() {
            HttpsURLConnection open;
            synchronized (this) { open = connection; connection = null; }
            if (open != null) open.disconnect();
            synchronized (NativeChatClient.this) { calls.remove(this); }
        }
    }

    private synchronized void disconnectAsync(HttpsURLConnection connection) {
        if (!workers.isShutdown()) workers.execute(connection::disconnect);
    }

    private synchronized void start(Call call, Runnable job) {
        if (closed) throw new IllegalStateException("Chat client is closed");
        calls.add(call);
        workers.execute(() -> {
            try { job.run(); }
            finally { call.release(); }
        });
    }

    Call send(String workspace, String model, boolean thinking, boolean search, String system,
              JSONArray messages, boolean stream, Listener listener) {
        Call call = new Call();
        NativeChatResponse reply = new NativeChatResponse();
        call.cancelled = () -> listener.update(reply.result("stopped", ""));
        // The calling Activity may change branches or accounts as soon as send returns.
        String capturedMessages = messages.toString();
        start(call, () -> {
            try {
                String key = selected(call, workspace);
                JSONObject body = body(model, thinking, search, system, new JSONArray(capturedMessages), stream);
                HttpsURLConnection connection = open(call, "messages", key, hasFiles(body.getJSONArray("messages")));
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", stream ? "text/event-stream, application/json" : "application/json");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = connection.getOutputStream()) { call.check(); out.write(data); }
                checkStatus(connection);
                String type = connection.getContentType();
                try (InputStream input = connection.getInputStream();
                     Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    if (type != null && type.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                        reply.stream(reader, () -> {
                            call.check();
                            call.scheduleProgress(listener, reply);
                        });
                    } else if (type != null && type.toLowerCase(Locale.ROOT).contains("json")) {
                        reply.message(new JSONObject(read(reader, 32 * 1024 * 1024)));
                    } else {
                        throw new IOException("DeepSeek 返回了无法识别的响应格式。");
                    }
                }
                call.reply(listener, reply.result("done", ""), true);
            } catch (IOException | JSONException | RuntimeException failure) {
                call.reply(listener, reply.result("error", describe(failure)), true);
            }
        });
        return call;
    }

    private interface UploadSource { NativeChatUpload prepare() throws IOException; }

    Call upload(String workspace, Uri uri, UploadListener listener) {
        return upload(workspace, () -> NativeChatUpload.inspect(context.getContentResolver(), uri), listener);
    }

    Call upload(NativeHistoryStore accountStore, JSONObject file, UploadListener listener) {
        // Bind both the original and the credential to this immutable workspace before dispatch.
        JSONObject metadata = new JSONObject();
        for (String name : new String[]{"id", "name", "size", "isImage", "mimeType"})
            if (file.has(name)) NativeHistoryStore.put(metadata, name, file.opt(name));
        return upload(accountStore.workspace, () -> NativeChatUpload.archived(accountStore, metadata), listener);
    }

    private Call upload(String workspace, UploadSource source, UploadListener listener) {
        Call call = new Call();
        call.cancelled = () -> listener.complete(null, "已停止上传。");
        start(call, () -> {
            try {
                String key = selected(call, workspace);
                NativeChatUpload file = source.prepare();
                call.check();
                HttpsURLConnection connection = open(call, "files", key, true);
                file.send(connection, call);
                checkStatus(connection);
                JSONObject result;
                try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                    result = new JSONObject(read(reader, 1024 * 1024));
                }
                String id = result.optString("id");
                if (id.isEmpty() || result.isNull("id")) throw new IOException("DeepSeek 未返回附件编号。");
                JSONObject attachment = file.attachment(id, result);
                call.deliver(() -> listener.complete(attachment, ""), true);
            } catch (IOException | JSONException | RuntimeException failure) {
                String error = describe(failure);
                call.deliver(() -> listener.complete(null, error), true);
            }
        });
        return call;
    }

    private String selected(Call call, String owner) throws IOException, JSONException {
        call.check();
        if (!NativeAccountStore.validId(owner)) throw new IOException("当前账户无效。");
        String key = keys.selected(owner);
        if (key == null || key.isEmpty()) throw new IOException("请先在当前账户的「聊天密钥」中选择 API Key。");
        return key;
    }

    private HttpsURLConnection open(Call call, String resource, String key, boolean files) throws IOException {
        call.check();
        HttpsURLConnection connection = transport.open(new URL(API + resource));
        try { call.attach(connection); }
        catch (InterruptedIOException stopped) { connection.disconnect(); throw stopped; }
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(180000);
        connection.setRequestProperty("x-api-key", key);
        if (files) connection.setRequestProperty("anthropic-beta", FILES_BETA);
        return connection;
    }

    static JSONArray history(JSONArray visibleMessages) throws JSONException {
        return NativeChatHistory.messages(visibleMessages, false, false);
    }

    static JSONArray history(JSONArray visibleMessages, boolean continuation, boolean thinking) throws JSONException {
        return NativeChatHistory.messages(visibleMessages, continuation, thinking);
    }

    static JSONObject body(String model, boolean thinking, boolean search, String system, JSONArray messages, boolean stream)
            throws JSONException, IOException {
        if (!FLASH.equals(model) && !PRO.equals(model)) throw new IOException("请选择 Flash 或 Pro 模型。");
        if (messages.length() == 0) throw new IOException("请先输入消息。");
        boolean flash = FLASH.equals(model);
        JSONArray history = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.getString("role");
            if (role.equals("latest_reminder") && !flash) continue;
            if (!role.equals("user") && !role.equals("assistant") && !role.equals("latest_reminder"))
                throw new IOException("对话中有无法发送的消息。");
            JSONArray blocks = message.optJSONArray("content");
            if (!flash && blocks != null) {
                for (int j = 0; j < blocks.length(); j++) {
                    String type = blocks.getJSONObject(j).optString("type");
                    if (!type.equals("text") && !type.equals("thinking"))
                        throw new IOException("带附件或工具结果的对话需要使用 Flash。");
                }
            }
            history.put(message);
        }
        JSONObject body = new JSONObject().put("model", model).put("stream", stream)
                .put("thinking", new JSONObject().put("type", thinking ? "enabled" : "disabled"))
                .put("safety_check", new JSONObject().put("level", "high"))
                .put("system", system == null || system.trim().isEmpty() ? SYSTEM : SYSTEM + "\n" + system.trim())
                .put("messages", history);
        if (flash) {
            JSONArray tools = new JSONArray().put(tool("deepseek_read_url"));
            if (search) tools.put(tool("deepseek_web_search"));
            body.put("tools", tools);
            if (history.length() == 1) body.put("title", new JSONObject().put("enabled", true));
        }
        return body;
    }

    private static JSONObject tool(String name) throws JSONException {
        return new JSONObject().put("type", name).put("name", name);
    }

    private static boolean hasFiles(JSONArray messages) throws JSONException {
        for (int i = 0; i < messages.length(); i++) {
            JSONArray blocks = messages.getJSONObject(i).optJSONArray("content");
            for (int j = 0; blocks != null && j < blocks.length(); j++) {
                String type = blocks.getJSONObject(j).optString("type");
                if ("image".equals(type) || "document".equals(type)) return true;
            }
        }
        return false;
    }

    private static String read(Reader source, int limit) throws IOException {
        char[] chars = new char[8192];
        StringBuilder result = new StringBuilder();
        int count;
        while ((count = source.read(chars)) != -1) {
            if (result.length() > limit - count) throw new IOException("服务响应过大。");
            result.append(chars, 0, count);
        }
        return result.toString();
    }

    private static void checkStatus(HttpsURLConnection c) throws IOException {
        int status = c.getResponseCode();
        if (status >= 200 && status < 300) return;
        String message;
        switch (status) {
            case 401: case 403: message = "当前 API Key 未通过验证，请检查密钥。"; break;
            case 402: message = "官方账户余额不足。"; break;
            case 429: message = "请求过于频繁，请稍后再试。"; break;
            default: message = "DeepSeek 请求失败（HTTP " + status + "）。"; break;
        }
        InputStream error = c.getErrorStream();
        if (error != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(error, StandardCharsets.UTF_8))) {
                JSONObject details = new JSONObject(read(reader, 32768)).optJSONObject("error");
                if (details != null) {
                    String extra = NativeChatResponse.safeMessage(details.optString("message"));
                    if (!extra.isEmpty()) message += " " + extra.substring(0, Math.min(400, extra.length()));
                }
            } catch (IOException | JSONException invalidError) { /* Preserve the HTTP status. */ }
        }
        throw new IOException(message);
    }

    private static String describe(Exception error) {
        if (error instanceof SocketTimeoutException) return "与 DeepSeek 的连接超时。";
        if (error instanceof JSONException) return "无法读取 DeepSeek 返回的数据。";
        if (error instanceof SecurityException) return "无法读取所选文件，请重新选择。";
        String message = NativeChatResponse.safeMessage(error.getMessage());
        return message.isEmpty() ? "暂时无法连接 DeepSeek，请重试。" : message;
    }

    @Override public synchronized void close() {
        closed = true;
        for (Call call : new ArrayList<>(calls)) call.stop();
        workers.shutdown();
    }
}
