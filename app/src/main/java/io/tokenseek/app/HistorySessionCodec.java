package io.tokenseek.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static io.tokenseek.app.NativeHistoryStore.put;

/**
 * Maps the DeepSeek v3 tree to local sessions while keeping unmapped archive fragments lossless.
 */
final class HistorySessionCodec {

    private HistorySessionCodec() {
    }

    static long timestamp(String time) throws IOException {
        try {
            return Instant.parse(time).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IOException("Invalid history timestamp", e);
        }
    }

    private static String iso(long time) {
        return Instant.ofEpochMilli(time).toString();
    }

    private static JSONObject item(String key, Object value) {
        JSONObject result = new JSONObject();
        put(result, key, value);
        return result;
    }

    static JSONObject decode(JSONObject file, Map<String, String> originals) throws JSONException, IOException {
        String id = file.getString("id");
        if (!NativeHistoryArchive.safeId(id))
            throw new IOException("Invalid session ID");
        long created = timestamp(file.getString("inserted_at")), updated = timestamp(file.getString("updated_at"));
        JSONObject session = item("id", id);
        put(session, "title", file.isNull("title") ? "" : file.getString("title"));
        put(session, "createdAt", created);
        put(session, "updatedAt", updated);
        put(session, "model", file.opt("tokenseek_model"));
        JSONArray raw = file.getJSONArray("messages"), messages = new JSONArray();
        List<JSONObject> sorted = new ArrayList<>();
        for (int n = 0; n < raw.length(); n++) sorted.add(raw.getJSONObject(n));
        Collections.sort(sorted, Comparator.comparingLong(m -> m.optLong("id", -1)));
        Map<String, JSONObject> byId = new HashMap<>();
        for (JSONObject source : sorted) {
            long num = source.getLong("id");
            String mid = String.valueOf(num), role = source.getString("role");
            String parent = source.isNull("parent_id") ? null : String.valueOf(source.getLong("parent_id"));
            boolean user = "USER".equals(role);
            JSONObject p = byId.get(parent);
            if (num < 0 || byId.containsKey(mid) || (!user && !"ASSISTANT".equals(role)) || (parent == null ? !user : (p == null || user == "user".equals(p.optString("role")))))
                throw new IOException("Invalid message branch");
            JSONObject m = item("id", mid);
            put(m, "role", user ? "user" : "assistant");
            put(m, "parentId", parent == null ? JSONObject.NULL : parent);
            put(m, "createdAt", timestamp(source.getString("inserted_at")));
            if (p != null)
                put(p, "selectedChildId", mid);
            byId.put(mid, m);
            messages.put(m);
            JSONArray fragments = source.getJSONArray("fragments");
            put(m, "archiveMessage", source);
            if (user) {
                StringBuilder content = new StringBuilder();
                JSONArray files = new JSONArray();
                for (int j = 0; j < fragments.length(); j++) {
                    JSONObject f = fragments.getJSONObject(j);
                    if ("REQUEST".equals(f.optString("type")))
                        content.append(f.optString("content"));
                    if (!"FILE".equals(f.optString("type")))
                        continue;
                    JSONArray list = f.getJSONArray("files");
                    for (int k = 0; k < list.length(); k++) {
                        JSONObject a = list.getJSONObject(k);
                        String aid = a.getString("id");
                        JSONObject att = item("id", aid);
                        put(att, "name", a.getString("file_name"));
                        put(att, "size", a.getLong("file_size"));
                        put(att, "serverId", JSONObject.NULL);
                        put(att, "isImage", a.optBoolean("is_image"));
                        put(att, "mimeType", a.optString("tokenseek_mime", ""));
                        files.put(att);
                        if (!a.isNull("export_path")) {
                            String path = NativeHistoryArchive.normalPath(a.getString("export_path"));
                            if (!path.startsWith("chat_sessions/" + id + "_files/"))
                                throw new IOException("Invalid attachment path");
                            String previous = originals.put(path, aid);
                            if (previous != null && !previous.equals(aid))
                                throw new IOException("Conflicting attachment ID");
                        }
                    }
                }
                put(m, "content", content.toString());
                put(m, "files", files);
            } else {
                JSONArray blocks = new JSONArray();
                boolean deepSearch = false;
                for (int j = 0; j < fragments.length(); j++) {
                    String type = fragments.getJSONObject(j).optString("type");
                    if ("TOOL_OPEN".equals(type) || "TOOL_FIND".equals(type))
                        deepSearch = true;
                }
                for (int j = 0; j < fragments.length(); j++) {
                    JSONObject f = fragments.getJSONObject(j);
                    String type = f.optString("type");
                    JSONObject b;
                    if ("THINK".equals(type)) {
                        b = item("type", "thinking");
                        put(b, "thinking", f.optString("content"));
                        if (!f.isNull("elapsed_secs"))
                            put(b, "elapsedSeconds", f.getDouble("elapsed_secs"));
                    } else if ("RESPONSE".equals(type) || "TEMPLATE_RESPONSE".equals(type)) {
                        b = item("type", "text");
                        put(b, "text", f.optString("content"));
                        if ("TEMPLATE_RESPONSE".equals(type))
                            put(b, "isTemplate", true);
                    } else {
                        HistoryToolFragments.decode(blocks, f, "history-" + mid + "-" + j, deepSearch);
                        continue;
                    }
                    blocks.put(b);
                }
                put(m, "blocks", blocks);
                String status = source.getString("status");
                put(m, "status", "FINISHED".equals(status) || "CONTENT_FILTER".equals(status) ? "done" : "INCOMPLETE".equals(status) || "WIP".equals(status) ? "stopped" : "error");
                if ("CONTENT_FILTER".equals(status))
                    put(m, "stopReason", "refusal");
                put(m, "model", source.opt("tokenseek_model"));
                put(m, "prefix", false);
                put(m, "thinking", source.optBoolean("thinking_enabled"));
                put(m, "search", source.optBoolean("search_enabled"));
                JSONArray runs = source.optJSONArray("tokenseek_usage_runs");
                if (runs != null) {
                    put(m, "usageRuns", runs);
                    JSONObject last = runs.optJSONObject(runs.length() - 1);
                    if (last != null)
                        put(m, "usage", last.optJSONObject("usage"));
                }
            }
        }
        put(session, "messages", messages);
        if (messages.length() == 0)
            put(session, "rootMessageId", JSONObject.NULL);
        else {
            JSONObject current = byId.get(String.valueOf(file.getLong("current_message_id")));
            if (current == null)
                throw new IOException("Invalid selected message");
            current.remove("selectedChildId");
            while (!current.isNull("parentId")) {
                JSONObject parent = byId.get(current.getString("parentId"));
                put(parent, "selectedChildId", current.getString("id"));
                current = parent;
            }
            put(session, "rootMessageId", current.getString("id"));
        }
        JSONObject nativeSession = file.optJSONObject("tokenseek_native_session");
        if (nativeSession != null) {
            if (!id.equals(nativeSession.getString("id")) || nativeSession.getJSONArray("messages").length() != messages.length())
                throw new IOException("Invalid native session extension");
            orderedMessages(nativeSession);
            return nativeSession;
        }
        return session;
    }

    /** The v3 format uses ordered integer IDs. Validate every native branch, including inactive ones. */
    private static JSONArray orderedMessages(JSONObject session) throws JSONException, IOException {
        JSONArray messages = session.getJSONArray("messages");
        session.getLong("createdAt");
        session.getLong("updatedAt");
        Map<String,JSONObject> nodes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String id = message.getString("id"), role = message.getString("role");
            if (id.isEmpty() || nodes.put(id, message) != null ||
                    (!"user".equals(role) && !"assistant".equals(role))) throw new IOException("Invalid native message");
            message.getLong("createdAt");
        }
        if (!session.has("rootMessageId")) return messages;
        if (!nodes.isEmpty()) {
            JSONObject root = nodes.get(session.optString("rootMessageId"));
            if (root == null || !root.isNull("parentId")) throw new IOException("Missing native root");
        }
        JSONArray result = new JSONArray();
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (JSONObject node : nodes.values()) {
            if (!node.isNull("selectedChildId")) {
                JSONObject child = nodes.get(node.getString("selectedChildId"));
                if (child == null || !node.getString("id").equals(child.optString("parentId")))
                    throw new IOException("Invalid selected branch");
            }
            java.util.ArrayDeque<JSONObject> chain = new java.util.ArrayDeque<>();
            java.util.Set<String> pending = new java.util.HashSet<>();
            JSONObject next = node;
            while (next != null && !emitted.contains(next.getString("id"))) {
                if (!pending.add(next.getString("id"))) throw new IOException("Cyclic native branch");
                chain.push(next);
                boolean user = "user".equals(next.getString("role"));
                if (next.isNull("parentId")) {
                    if (!user) throw new IOException("Invalid native root role");
                    next = null;
                } else {
                    next = nodes.get(next.getString("parentId"));
                    if (next == null || user == "user".equals(next.getString("role")))
                        throw new IOException("Invalid native parent");
                }
            }
            while (!chain.isEmpty()) {
                JSONObject item = chain.pop();
                result.put(item); emitted.add(item.getString("id"));
            }
        }
        return result;
    }

    static JSONObject encode(NativeHistoryStore store, JSONObject session, Map<String, String> originals) throws JSONException, IOException {
        String id = session.getString("id");
        JSONArray local = orderedMessages(session);
        boolean linear = !session.has("rootMessageId");
        JSONObject file = item("id", id);
        put(file, "title", session.optString("title").isEmpty() ? JSONObject.NULL : session.optString("title"));
        put(file, "pinned", false);
        put(file, "inserted_at", iso(session.getLong("createdAt")));
        put(file, "updated_at", iso(session.getLong("updatedAt")));
        put(file, "tokenseek_model", session.opt("model"));
        Map<String, Integer> ids = new HashMap<>();
        JSONArray messages = new JSONArray();
        for (int i = 0; i < local.length(); i++) {
            JSONObject m = local.getJSONObject(i);
            String mid = m.getString("id");
            if (ids.put(mid, i + 1) != null)
                throw new IOException("Duplicate message");
            Integer parent = linear ? (i == 0 ? null : Integer.valueOf(i)) : m.isNull("parentId") ? null : ids.get(m.getString("parentId"));
            if (!linear && !m.isNull("parentId") && (parent == null || parent == i + 1))
                throw new IOException("Invalid parent order");
            JSONObject archived = m.optJSONObject("archiveMessage");
            JSONObject converted = archived == null ? new JSONObject() : new JSONObject(archived.toString());
            put(converted, "id", i + 1);
            put(converted, "parent_id", parent == null ? JSONObject.NULL : parent);
            boolean user = "user".equals(m.optString("role"));
            put(converted, "role", user ? "USER" : "ASSISTANT");
            put(converted, "inserted_at", iso(m.getLong("createdAt")));
            put(converted, "thinking_enabled", m.optBoolean("thinking"));
            put(converted, "search_enabled", m.optBoolean("search"));
            String status = m.optString("status", "done");
            String mapped = "refusal".equals(m.optString("stopReason")) ? "CONTENT_FILTER" : "done".equals(status) ? "FINISHED" : "error".equals(status) ? "FAILED" : "INCOMPLETE";
            put(converted, "status", archived == null ? mapped : archived.optString("status", mapped));
            JSONObject usage = m.optJSONObject("usage");
            long total = 0;
            if (usage != null)
                for (String k : new String[] { "input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens" }) total += usage.optLong(k);
            put(converted, "accumulated_token_usage", usage == null ? converted.optLong("accumulated_token_usage") : total);
            JSONArray runs = m.optJSONArray("usageRuns");
            if (runs == null && usage != null) {
                JSONObject run = item("kind", "legacy");
                put(run, "usage", usage);
                runs = new JSONArray().put(run);
            }
            put(converted, "tokenseek_usage_runs", runs);
            put(converted, "tokenseek_model", m.opt("model"));
            JSONArray fragments = new JSONArray();
            if (user) {
                JSONArray at = m.optJSONArray("files"), files = new JSONArray();
                for (int j = 0; at != null && j < at.length(); j++) {
                    JSONObject a = at.getJSONObject(j);
                    String aid = a.getString("id"), name = a.getString("name");
                    JSONObject f = item("id", aid);
                    put(f, "file_name", name);
                    put(f, "file_size", a.optLong("size"));
                    put(f, "inserted_at", converted.getString("inserted_at"));
                    put(f, "token_usage", JSONObject.NULL);
                    put(f, "width", JSONObject.NULL);
                    put(f, "height", JSONObject.NULL);
                    put(f, "is_image", a.optBoolean("isImage"));
                    put(f, "tokenseek_mime", a.optString("mimeType", ""));
                    String path = null;
                    if (store.has("file", aid)) {
                        path = NativeHistoryArchive.normalPath("chat_sessions/" + id + "_files/" + safeName(aid) + "_" + safeName(name));
                        String prev = originals.put(path, aid);
                        if (prev != null && !prev.equals(aid))
                            throw new IOException("Conflicting attachment path");
                    }
                    put(f, "export_path", path == null ? JSONObject.NULL : path);
                    files.put(f);
                }
                if (files.length() > 0) {
                    JSONObject f = item("type", "FILE");
                    put(f, "files", files);
                    fragments.put(f);
                }
                JSONObject f = item("type", "REQUEST");
                put(f, "content", m.optString("content"));
                fragments.put(f);
            } else if (archived != null) {
                fragments = archived.getJSONArray("fragments");
            } else {
                JSONArray blocks = m.optJSONArray("blocks");
                Map<String, JSONObject> results = HistoryToolFragments.results(blocks);
                for (int j = 0; blocks != null && j < blocks.length(); j++) {
                    JSONObject b = blocks.getJSONObject(j);
                    String type = b.optString("type");
                    JSONObject f;
                    if ("thinking".equals(type)) {
                        f = item("type", "THINK");
                        put(f, "content", b.optString("thinking"));
                        put(f, "elapsed_secs", b.has("elapsedSeconds") ? b.opt("elapsedSeconds") : JSONObject.NULL);
                    } else if ("text".equals(type)) {
                        f = item("type", b.optBoolean("isTemplate") ? "TEMPLATE_RESPONSE" : "RESPONSE");
                        put(f, "content", b.optString("text"));
                    } else if ("server_tool_use".equals(type)) {
                        f = HistoryToolFragments.encode(b, results.get(b.optString("id")));
                        if (f == null)
                            continue;
                    } else
                        continue;
                    fragments.put(f);
                }
            }
            put(converted, "fragments", fragments);
            messages.put(converted);
        }
        JSONArray visible = NativeHistoryStore.visibleMessages(session);
        JSONObject last = visible.optJSONObject(visible.length() - 1);
        if (messages.length() > 0 && last == null)
            throw new IOException("Missing current branch");
        put(file, "current_message_id", last == null ? 0 : ids.get(last.getString("id")));
        put(file, "messages", messages);
        put(file, "tokenseek_native_session", session);
        return file;
    }

    private static String safeName(String value) {
        return value.isEmpty() || ".".equals(value) || "..".equals(value) ? "unnamed" : value.replace('/', '_').replace('\\', '_');
    }
}
