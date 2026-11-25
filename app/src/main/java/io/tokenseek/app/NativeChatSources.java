package io.tokenseek.app;

import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.URLSpan;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses only the public titles and links in citations and search results returned by DeepSeek. */
final class NativeChatSources {
    private final Map<String,String> links = new LinkedHashMap<>();

    static NativeChatSources from(JSONArray blocks) {
        NativeChatSources result = new NativeChatSources();
        if (blocks == null) return result;
        for (int i=0;i<blocks.length();i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            JSONArray citations = block.optJSONArray("citations");
            if (citations != null) result.addEntries(citations);
        }
        for (int i=0;i<blocks.length();i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            if ("web_search_tool_result".equals(block.optString("type"))) {
                JSONArray results = block.optJSONArray("content");
                if (results != null) result.addEntries(results);
            } else if ("web_fetch_tool_result".equals(block.optString("type"))) {
                JSONObject hit = block.optJSONObject("content");
                if (hit == null) continue;
                JSONObject document = hit.optJSONObject("content");
                result.add(hit.optString("url"),document == null ? "" : NativeConversation.string(document,"title"));
            }
        }
        return result;
    }

    private void addEntries(JSONArray rows) {
        for (int i=0;i<rows.length();i++) {
            JSONObject entry = rows.optJSONObject(i);
            if (entry != null) add(entry.optString("url"),NativeConversation.string(entry,"title"));
        }
    }

    private void add(String url,String title) {
        Uri link = Uri.parse(url);
        if (!("https".equalsIgnoreCase(link.getScheme()) || "http".equalsIgnoreCase(link.getScheme()))
                || link.getHost() == null || link.getHost().isEmpty()) return;
        links.putIfAbsent(url,title.trim().isEmpty() ? link.getHost() : title.trim());
    }

    int count() { return links.size(); }

    CharSequence text() {
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (Map.Entry<String,String> entry : links.entrySet()) {
            if (text.length()>0) text.append("\n\n");
            int start = text.length();
            text.append(entry.getValue());
            text.setSpan(new URLSpan(entry.getKey()),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }
}
