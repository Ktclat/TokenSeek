package io.tokenseek.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts official archive search and link results into the same blocks used by native chat.
 */
final class HistoryToolFragments {

    private HistoryToolFragments() {
    }

    private static String webUrl(JSONObject item, String key) {
        if (item.isNull(key))
            return "";
        String raw = item.optString(key);
        try {
            URI url = URI.create(raw);
            return ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme())) && url.getHost() != null ? raw : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static void call(JSONArray blocks, String id, String kind, JSONObject input) throws JSONException {
        blocks.put(new JSONObject().put("type", "server_tool_use").put("id", id).put("kind", kind).put("name", "read_link".equals(kind) ? "deepseek_read_url" : "deepseek_web_search").put("caller", new JSONObject().put("type", "direct")).put("input", input));
    }

    private static JSONObject page(String url, Object title, Object date) throws JSONException {
        JSONObject source = new JSONObject().put("type", "text").put("media_type", "text/plain").put("data", "");
        return new JSONObject().put("type", "web_fetch_result").put("url", url).put("published_at", date).put("content", new JSONObject().put("type", "document").put("source", source).put("title", title));
    }

    static void decode(JSONArray blocks, JSONObject fragment, String id, boolean deepSearch) throws JSONException {
        String type = fragment.optString("type"), status = fragment.optString("status", "FINISHED");
        if ("READ_LINK".equals(type)) {
            JSONArray results = fragment.optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                String url = webUrl(item, "url"), signature = item.optString("signature", "");
                if (signature.isEmpty() && url.isEmpty())
                    continue;
                String callId = id + "-" + i;
                call(blocks, callId, "read_link", new JSONObject().put("action", "read_link").put("url", url));
                blocks.put(new JSONObject().put("type", "web_fetch_tool_result").put("tool_use_id", callId).put("kind", "read_link").put("signature", signature).put("content", page(url, item.opt("title"), JSONObject.NULL)));
            }
        } else if ("TOOL_SEARCH".equals(type)) {
            JSONArray queries = fragment.optJSONArray("queries");
            if (queries == null)
                queries = new JSONArray();
            String kind = deepSearch ? "deep_search" : "search";
            call(blocks, id, kind, new JSONObject().put("action", "search").put("queries", queries).put("query", queries.optString(0, "")));
            if ("WIP".equals(status))
                return;
            JSONArray content = new JSONArray(), results = fragment.optJSONArray("results");
            if ("FAILED".equals(status))
                content.put(new JSONObject().put("type", "web_search_tool_result_error").put("error_code", "unavailable"));
            else
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject entry = results.getJSONObject(i);
                    String url = webUrl(entry, "url");
                    if (url.isEmpty())
                        continue;
                    content.put(new JSONObject().put("type", "web_search_result").put("url", url).put("title", entry.optString("title", "")).put("snippet", entry.optString("snippet", "")).put("page_age", entry.opt("published_at")).put("site_name", entry.opt("site_name")).put("site_icon", webUrl(entry, "site_icon")).put("encrypted_content", ""));
                }
            blocks.put(new JSONObject().put("type", "web_search_tool_result").put("tool_use_id", id).put("kind", kind).put("content", content));
        } else if ("TOOL_OPEN".equals(type) || "TOOL_FIND".equals(type)) {
            boolean open = "TOOL_OPEN".equals(type);
            String url = webUrl(fragment, "url");
            if (open && url.isEmpty())
                return;
            JSONObject input = new JSONObject().put("action", open ? "open_page" : "find_in_page").put("url", url);
            if (!open)
                input.put("pattern", fragment.optString("pattern", ""));
            call(blocks, id, "deep_search", input);
            if ("WIP".equals(status))
                return;
            JSONObject content = "FINISHED".equals(status) ? page(url, fragment.opt("title"), fragment.opt("published_at")) : new JSONObject().put("type", "web_fetch_tool_result_error").put("error_code", "unavailable");
            blocks.put(new JSONObject().put("type", "web_fetch_tool_result").put("tool_use_id", id).put("kind", "deep_search").put("content", content));
        }
    }

    static Map<String, JSONObject> results(JSONArray blocks) {
        Map<String, JSONObject> result = new HashMap<>();
        for (int i = 0; blocks != null && i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block != null && block.has("tool_use_id"))
                result.put(block.optString("tool_use_id"), block);
        }
        return result;
    }

    static JSONObject encode(JSONObject call, JSONObject result) throws JSONException {
        JSONObject input = call.optJSONObject("input");
        if (input == null)
            return null;
        String action = input.optString("action"), status = result == null ? "WIP" : "FINISHED";
        JSONObject fetched = result == null ? null : result.optJSONObject("content");
        if (fetched != null && !"web_fetch_result".equals(fetched.optString("type")))
            status = "FAILED";
        JSONObject document = fetched == null ? null : fetched.optJSONObject("content");
        if ("read_link".equals(action)) {
            if (fetched == null || !"FINISHED".equals(status) || result.optString("signature", "").isEmpty())
                return null;
            JSONObject item = new JSONObject().put("signature", result.getString("signature")).put("url", fetched.optString("url", input.optString("url", ""))).put("title", document == null ? JSONObject.NULL : document.opt("title"));
            return new JSONObject().put("type", "READ_LINK").put("results", new JSONArray().put(item));
        }
        if ("open_page".equals(action))
            return new JSONObject().put("type", "TOOL_OPEN").put("status", status).put("url", input.optString("url", "")).put("title", document == null ? JSONObject.NULL : document.opt("title")).put("published_at", fetched == null || fetched.isNull("published_at") ? JSONObject.NULL : fetched.get("published_at"));
        if ("find_in_page".equals(action))
            return new JSONObject().put("type", "TOOL_FIND").put("status", status).put("pattern", input.optString("pattern", ""));
        if (!"search".equals(action))
            return null;
        JSONArray hits = result == null ? null : result.optJSONArray("content"), items = new JSONArray();
        for (int i = 0; hits != null && i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null)
                continue;
            if ("web_search_tool_result_error".equals(hit.optString("type")))
                status = "FAILED";
            if (!"web_search_result".equals(hit.optString("type")))
                continue;
            items.put(new JSONObject().put("url", hit.optString("url")).put("title", hit.optString("title", "")).put("snippet", hit.optString("snippet", "")).put("published_at", hit.isNull("page_age") ? JSONObject.NULL : hit.get("page_age")).put("site_name", hit.isNull("site_name") ? JSONObject.NULL : hit.get("site_name")).put("site_icon", hit.opt("site_icon")));
        }
        return new JSONObject().put("type", "TOOL_SEARCH").put("status", status).put("results", items).put("queries", input.optJSONArray("queries") != null ? input.getJSONArray("queries") : new JSONArray().put(input.optString("query", "")));
    }
}
