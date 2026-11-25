package io.tokenseek.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.TreeMap;

/** Aggregates the official Messages API's indexed content blocks on the network thread. */
final class NativeChatResponse {
    interface Progress { void changed() throws IOException; }

    private final TreeMap<Integer, Block> blocks = new TreeMap<>();
    private JSONObject usage, details;
    private String usageKind = "missing", stopReason = "", title = "";
    private boolean stoppedByServer;

    private static final class Block {
        final JSONObject initial;
        final String type;
        final StringBuilder text, signature;
        final ArrayList<JSONObject> citations = new ArrayList<>();

        Block(JSONObject value) {
            initial = value;
            type = value.optString("type");
            text = new StringBuilder(value.optString(type.equals("thinking") ? "thinking" : "text", ""));
            signature = new StringBuilder(value.optString("signature", ""));
            JSONArray originals = value.optJSONArray("citations");
            for (int i = 0; originals != null && i < originals.length(); i++) {
                if (originals.optJSONObject(i) != null) citations.add(originals.optJSONObject(i));
            }
        }

        void append(JSONObject delta) {
            switch (delta.optString("type")) {
                case "text_delta": if (type.equals("text")) text.append(delta.optString("text")); break;
                case "thinking_delta": if (type.equals("thinking")) text.append(delta.optString("thinking")); break;
                case "signature_delta": signature.append(delta.optString("signature")); break;
                case "citations_delta":
                    JSONObject citation = delta.optJSONObject("citation");
                    if (type.equals("text") && citation != null) citations.add(citation);
                    break;
                default: break;
            }
        }

        JSONObject value() throws JSONException {
            JSONObject copy = new JSONObject();
            for (Iterator<String> it = initial.keys(); it.hasNext();) {
                String name = it.next();
                copy.put(name, initial.get(name));
            }
            if (type.equals("text") || type.equals("thinking")) copy.put(type, text.toString());
            if (signature.length() > 0) copy.put("signature", signature.toString());
            if (!citations.isEmpty()) copy.put("citations", new JSONArray(citations));
            return copy;
        }
    }

    synchronized JSONObject result(String status, String error) {
        try {
            JSONArray items = new JSONArray();
            StringBuilder text = new StringBuilder(), thinking = new StringBuilder();
            for (Block block : blocks.values()) {
                if ("refusal".equals(stopReason) && (block.type.equals("text") || block.type.equals("thinking"))) continue;
                JSONObject value = block.value();
                items.put(value);
                if (block.type.equals("text")) text.append(value.getString("text"));
                if (block.type.equals("thinking")) thinking.append(value.getString("thinking"));
            }
            return new JSONObject().put("blocks", items).put("text", text.toString()).put("thinking", thinking.toString())
                    .put("status", status).put("error", error).put("stopReason", stopReason).put("stopDetails", details).put("title", title)
                    .put("usage", usage == null ? JSONObject.NULL : new JSONObject(usage.toString())).put("usageKind", usageKind);
        } catch (JSONException malformed) { throw new IllegalStateException(malformed); }
    }

    private void usage(JSONObject update, String kind) throws JSONException {
        if (update == null) return;
        if (usage == null) usage = new JSONObject();
        // message_delta may contain just the output count. Counts are cumulative, never additive.
        for (Iterator<String> it = update.keys(); it.hasNext();) {
            String name = it.next();
            Object value = update.get(name);
            if (value instanceof Number && ((Number) value).longValue() >= 0 || value instanceof JSONObject)
                usage.put(name, value);
        }
        usageKind = kind;
    }

    private void stopDetails(JSONObject body) {
        if (!body.isNull("stop_reason")) stopReason = body.optString("stop_reason");
        if (body.optJSONObject("stop_details") != null) details = body.optJSONObject("stop_details");
        if (!body.isNull("title")) title = body.optString("title");
    }

    synchronized void message(JSONObject body) throws JSONException, IOException {
        checkError(body);
        JSONArray content = body.getJSONArray("content");
        for (int i = 0; i < content.length(); i++) blocks.put(i, new Block(content.getJSONObject(i)));
        stopDetails(body);
        usage(body.optJSONObject("usage"), "final");
        stoppedByServer = true;
    }

    static String safeMessage(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replaceAll("(?i)sk-[A-Za-z0-9_-]+", "••••").replaceAll("[\\p{Cntrl}]+"," ").trim();
    }

    private static void checkError(JSONObject data) throws IOException {
        JSONObject details = data.optJSONObject("error");
        if (details != null || "error".equals(data.optString("type"))) {
            String msg = details == null ? "" : safeMessage(details.optString("message"));
            throw new IOException(msg.isEmpty() ? "DeepSeek 返回了错误。" : msg);
        }
    }

    private synchronized boolean event(JSONObject data) throws JSONException, IOException {
        checkError(data);
        switch (data.optString("type")) {
            case "message_start":
                usage(data.getJSONObject("message").optJSONObject("usage"), "initial");
                break;
            case "content_block_start": {
                int index = data.getInt("index");
                if (index >= 0) blocks.put(index, new Block(data.getJSONObject("content_block")));
                break;
            }
            case "content_block_delta": {
                Block block = blocks.get(data.getInt("index"));
                if (block != null) block.append(data.getJSONObject("delta"));
                break;
            }
            case "message_delta":
                stopDetails(data.getJSONObject("delta"));
                usage(data.optJSONObject("usage"), "final");
                break;
            case "message_stop":
                stoppedByServer = true;
                break;
            default: return false;
        }
        return true;
    }

    void stream(Reader source, Progress progress) throws IOException, JSONException {
        BufferedReader reader = new BufferedReader(source);
        StringBuilder data = new StringBuilder();
        String eventName = "";
        String line;
        boolean firstLine = true;
        while (!stoppedByServer && (line = reader.readLine()) != null) {
            if (firstLine && line.startsWith("\uFEFF")) line = line.substring(1);
            firstLine = false;
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    JSONObject event;
                    try { event = new JSONObject(data.toString()); }
                    catch (JSONException invalid) {
                        if ("error".equals(eventName)) throw new IOException("DeepSeek 返回了流式错误。");
                        throw new IOException("无法读取 DeepSeek 流式响应。");
                    }
                    if (event(event)) progress.changed();
                }
                data.setLength(0);
                eventName = "";
                continue;
            }
            if (line.startsWith(":")) continue;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("event".equals(field)) eventName = value;
            if ("data".equals(field)) {
                if (data.length() > 0) data.append('\n');
                data.append(value);
            }
        }
        if (!stoppedByServer) throw new IOException("连接提前结束，已保留收到的回答。");
    }
}
