package io.tokenseek.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Convert the visible branch of archived/native messages to the official request format. */
final class NativeChatHistory {
    private NativeChatHistory() { }

    static JSONArray messages(JSONArray path, boolean continuation, boolean thinking) throws JSONException {
        return messages(path, continuation, thinking, null);
    }

    static List<JSONObject> missingFiles(JSONArray path, boolean continuation, boolean thinking) throws JSONException {
        List<JSONObject> files = new ArrayList<>();
        messages(path, continuation, thinking, files);
        return files;
    }

    private static JSONArray messages(JSONArray path, boolean continuation, boolean thinking, List<JSONObject> missing) throws JSONException {
        JSONArray history = new JSONArray();
        String lastReminder = "";
        for (int i = 0; i < path.length(); i++) {
            JSONObject user = path.optJSONObject(i);
            if (user == null || !"user".equals(user.optString("role"))) continue;
            JSONObject answer = path.optJSONObject(i + 1);
            if (answer != null && !"assistant".equals(answer.optString("role"))) answer = null;
            boolean last = i == path.length() - 1 || (answer != null && i == path.length() - 2);
            boolean draft = answer == null || "pending".equals(answer.optString("status"))
                    || "streaming".equals(answer.optString("status"));
            boolean prefix = continuation && last && answer != null;
            if ((draft || answer == null) && !last) continue;
            if (!draft && !prefix && !usable(answer)) continue;
            if (prefix && !continuable(answer)) throw new JSONException("这条回答无法续写。");
            Object content = question(user, missing);
            if (content == null) continue;
            String reminder = answer == null ? "" : answer.optString("latestReminder", "");
            if (!reminder.isEmpty() && !reminder.equals(lastReminder)) {
                lastReminder = reminder;
                history.put(new JSONObject().put("role", "latest_reminder").put("content", reminder));
            }
            history.put(new JSONObject().put("role", "user").put("content", content));
            if (answer != null && (prefix || !draft)) {
                JSONArray reply = response(answer, prefix, thinking);
                if (reply.length() > 0) history.put(new JSONObject().put("role", "assistant").put("content", reply));
            }
        }
        return history;
    }

    private static Object question(JSONObject message, List<JSONObject> missing) throws JSONException {
        if (message.optJSONArray("content") != null) return message.getJSONArray("content");
        String text = message.optString("content", "");
        JSONArray files = message.optJSONArray("files");
        JSONArray content = new JSONArray();
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String serverId = file.isNull("serverId") ? "" : file.optString("serverId");
            if (serverId.isEmpty()) {
                if (missing == null) throw new JSONException("附件「" + file.optString("name", "attachment") + "」尚未上传。");
                missing.add(file);
                continue;
            }
            content.put(new JSONObject().put("type", file.optBoolean("isImage") ? "image" : "document")
                    .put("source", new JSONObject().put("type", "file").put("file_id", serverId)));
        }
        if (content.length() == 0) return text.isEmpty() ? null : text;
        if (!text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
        return content;
    }

    private static boolean usable(JSONObject message) {
        String status = message.optString("status", "done");
        if ((!status.equals("done") && !status.equals("stopped")) || "refusal".equals(message.optString("stopReason"))) return false;
        JSONArray blocks = message.optJSONArray("blocks");
        if (blocks == null) return !message.optString("text", message.optString("content", "")).isEmpty();
        boolean hasText = false;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null || !"text".equals(block.optString("type"))) continue;
            if (block.optBoolean("isTemplate")) return false;
            if (!block.optString("text", "").isEmpty()) hasText = true;
        }
        return hasText;
    }

    static boolean continuable(JSONObject message) {
        if (message == null || !message.optBoolean("prefix", true)) return false;
        String status = message.optString("status"), reason = message.optString("stopReason");
        if ("tool_use".equals(reason) || "refusal".equals(reason)
                || !("stopped".equals(status) || ("done".equals(status) && "max_tokens".equals(reason)))) return false;
        JSONArray blocks = message.optJSONArray("blocks");
        for (int i = 0; blocks != null && i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null || block.optBoolean("isTemplate")) return false;
            String type = block.optString("type");
            if (!type.equals("thinking") && !type.equals("text")) return false;
        }
        return true;
    }

    private static JSONArray response(JSONObject message, boolean prefix, boolean thinking) throws JSONException {
        JSONArray result = new JSONArray(), blocks = message.optJSONArray("blocks");
        if (blocks == null || blocks.length() == 0) {
            String value = message.optString("text", message.optString("content", ""));
            if (!value.isEmpty()) result.put(new JSONObject().put("type", "text").put("text", value));
            return result;
        }
        Set<String> eligible = new HashSet<>();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null || !"web_fetch_tool_result".equals(block.optString("type")) || !"read_link".equals(block.optString("kind"))) continue;
            JSONObject content = block.optJSONObject("content");
            if (content != null && ("web_fetch_tool_result_error".equals(content.optString("type"))
                    || ("web_fetch_result".equals(content.optString("type")) && !block.optString("signature").trim().isEmpty())))
                eligible.add(block.optString("tool_use_id"));
        }
        JSONObject currentText = null;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type");
            if (type.equals("text")) {
                String value = block.optString("text");
                if (value.isEmpty()) continue;
                if (currentText == null) {
                    JSONObject previous = result.optJSONObject(result.length() - 1);
                    currentText = new JSONObject().put("type", type)
                            .put("text", previous != null && type.equals(previous.optString("type")) ? "\n\n" : "");
                    result.put(currentText);
                }
                currentText.put("text", currentText.optString("text") + value);
            } else {
                if (prefix && type.equals("thinking") && block.optString("thinking").trim().isEmpty()) continue;
                currentText = null;
                if (prefix && thinking && type.equals("thinking")) {
                    result.put(new JSONObject().put("type", type).put("thinking", block.optString("thinking"))
                            .put("signature", block.optString("signature", "")));
                } else if (type.equals("server_tool_use") && "read_link".equals(block.optString("kind")) && eligible.contains(block.optString("id"))) {
                    result.put(new JSONObject(block, new String[]{"type","id","name","input","kind"}));
                } else if (type.equals("web_fetch_tool_result") && eligible.contains(block.optString("tool_use_id"))) {
                    result.put(block);
                }
            }
        }
        return result;
    }
}
