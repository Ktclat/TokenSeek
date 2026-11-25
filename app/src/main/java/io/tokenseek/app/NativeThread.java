package io.tokenseek.app;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.tokenseek.app.NativeAccountStore.object;

/** Owns one workspace's selected branch. A running request remains bound to the session that sent it. */
final class NativeThread {
    interface Listener { void changed(); void problem(String text); }
    private final NativeHistoryStore store;
    private final NativeAccountStore settings;
    private final NativeChatClient client;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject session;
    private JSONObject activeSession;
    private JSONObject pending;
    private NativeChatClient.Call call;
    private String model = NativeChatClient.FLASH;
    private boolean thinking, search, closed, stopRequested;
    private String draft;
    private boolean draftChanged;
    private JSONArray files = new JSONArray();
    private JSONArray prefixBlocks, previousUsage;
    private String initialTitle;
    private boolean allowGeneratedTitle, importPaused;
    private final Runnable writeDraft = () -> storeDraft();

    NativeThread(NativeHistoryStore store, NativeAccountStore settings, NativeChatClient client, Listener listener) {
        this.store = store; this.settings = settings; this.client = client; this.listener = listener;
        importPaused = NativeHistoryTransfers.importing(store.workspace);
        if (!importPaused) reloadFromStore();
    }

    static String id() { return UUID.randomUUID().toString(); }
    static void put(JSONObject target,String key,Object value) {
        try { target.put(key,value); } catch (JSONException invalid) { throw new IllegalArgumentException(invalid); }
    }
    private boolean en() { return settings.locale().startsWith("en"); }
    String sessionId() { return session == null ? "" : session.optString("id"); }
    JSONObject session() { return session; }
    String draft() { return draft == null ? "" : draft; }
    String model() { return model; }
    boolean thinking() { return thinking; }
    boolean search() { return search; }
    boolean generating() { return call != null; }
    boolean streamingHere() { return generating() && activeSession == session; }
    String fileName() {
        JSONObject first = files.optJSONObject(0);
        if (first == null) return "";
        String name = first.optString("name");
        return files.length() == 1 ? name : name + " (+" + (files.length() - 1) + ")";
    }

    void attach(JSONObject attachment) {
        if (importPaused || closed) return;
        if (attachment == null) files = new JSONArray();
        else {
            if (files.length() >= 50) {
                listener.problem(en() ? "Up to 50 files per message." : "每条消息最多可以添加 50 个附件。");
                return;
            }
            files.put(attachment);
            setOption("model", NativeChatClient.FLASH);
        }
        store.setDraftAttachments(session == null ? null : sessionId(), files);
    }
    void draft(String text) { if (importPaused || closed) return; draft = text == null ? "" : text; draftChanged = true; main.removeCallbacks(writeDraft); main.postDelayed(writeDraft, 350); }
    private void storeDraft() {
        if (!importPaused && !closed && draftChanged && draft != null) {
            store.setDraft(session == null ? null : sessionId(), draft);
            draftChanged = false;
        }
    }
    void persist() { main.removeCallbacks(writeDraft); if (importPaused || closed) return; storeDraft(); if(activeSession != null) store.saveSession(activeSession); }
    void close() {
        persist(); closed = true;
        if (call != null) {
            put(pending,"status","stopped");
            store.saveSession(activeSession); call.stop();
        }
        pending = null; call = null; activeSession = null;
    }
    void stop() {
        if (call != null) {
            stopRequested = true;
            call.stop();
        }
    }
    void pauseForImport() {
        if (closed || importPaused) return;
        persist();
        importPaused = true;
    }
    void finishImport() {
        if (closed || !importPaused) return;
        importPaused = false;
        reloadFromStore();
    }
    void reloadFromStore() {
        if (generating() || importPaused || closed) return;
        main.removeCallbacks(writeDraft);
        draft = null;
        draftChanged = false;
        files = new JSONArray();
        load(store.activeId(), false);
    }

    void open(String id) { if (!closed && !importPaused) load(id, true); }
    private void load(String id, boolean persistSelection) {
        main.removeCallbacks(writeDraft); storeDraft();
        session = id == null || id.isEmpty() ? null : activeSession != null && id.equals(activeSession.optString("id")) ? activeSession : store.session(id);
        if (persistSelection) store.setActiveId(session == null ? null : sessionId());
        model = session == null ? NativeChatClient.FLASH : session.optString("model",NativeChatClient.FLASH);
        thinking = session != null && session.optBoolean("thinking");
        search = session != null && session.optBoolean("search");
        String draftId = session == null ? null : sessionId();
        draft = store.draft(draftId);
        files = store.draftAttachments(draftId);
        if (session != null && session != activeSession) {
            boolean interrupted = false;
            for (int i=0; i<messages().length(); i++) {
                JSONObject m = messages().optJSONObject(i);
                if (m != null && ("pending".equals(m.optString("status")) || "streaming".equals(m.optString("status")))) {
                    put(m,"status","stopped"); interrupted = true;
                }
            }
            if (interrupted) store.saveSession(session);
        }
    }
    void setOption(String key, String value) {
        if (importPaused || closed) return;
        if ("model".equals(key)) {
            if (!NativeChatClient.PRO.equals(value) && !NativeChatClient.FLASH.equals(value)) return;
            if (NativeChatClient.PRO.equals(value) && (files.length() != 0 || needsFlash())) return;
            model = value;
        } else if ("thinking".equals(key)) thinking = Boolean.parseBoolean(value);
        else if ("search".equals(key)) search = Boolean.parseBoolean(value);
        if (session != null) { prefs(); store.saveSession(session); }
    }
    private void prefs() { put(session,"model",model); put(session,"thinking",thinking); put(session,"search",search); }
    private JSONArray messages() { return session.optJSONArray("messages"); }
    private JSONObject find(String id) {
        if (session != null) for(int i=0;i<messages().length();i++) {
            JSONObject m=messages().optJSONObject(i); if(m != null && id.equals(m.optString("id"))) return m;
        }
        return null;
    }
    JSONObject message(String id) { return find(id); }

    private boolean visible(String id) {
        JSONArray path = NativeHistoryStore.visibleMessages(session);
        for (int i = 0; i < path.length(); i++) {
            JSONObject entry = path.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id"))) return true;
        }
        return false;
    }

    private void normalize() {
        if (session.has("rootMessageId")) return;
        String parent = null;
        for(int i=0;i<messages().length();i++) {
            JSONObject m=messages().optJSONObject(i); if(m==null) continue;
            if(parent==null) put(session,"rootMessageId",m.optString("id"));
            else put(find(parent),"selectedChildId",m.optString("id"));
            put(m,"parentId",parent==null?JSONObject.NULL:parent); parent=m.optString("id");
        }
    }
    private JSONObject append(String parent, JSONObject m) {
        put(m,"parentId",parent==null?JSONObject.NULL:parent);
        if(parent==null) put(session,"rootMessageId",m.optString("id"));
        else put(find(parent),"selectedChildId",m.optString("id"));
        messages().put(m); return m;
    }
    void branch(String target) {
        if (closed || importPaused) return;
        if (streamingHere() || session == null) return;
        normalize(); JSONObject m=find(target); if(m==null) return;
        if(m.isNull("parentId")) put(session,"rootMessageId",target);
        else {JSONObject parent=find(m.optString("parentId")); if(parent==null) return; put(parent,"selectedChildId",target);}
        store.saveSession(session);
    }
    boolean needsFlash() {
        if(session==null) return false;
        JSONArray path=NativeHistoryStore.visibleMessages(session);
        for(int i=0;i<path.length();i++) {
            JSONObject m=path.optJSONObject(i); if(m==null) continue;
            if(m.optJSONArray("files") != null && m.optJSONArray("files").length()>0) return true;
            JSONArray blocks=m.optJSONArray("blocks");
            if(blocks!=null) for(int j=0;j<blocks.length();j++) {
                JSONObject b=blocks.optJSONObject(j); if(b!=null && b.optString("type").contains("tool")) return true;
            }
        }
        return false;
    }
    JSONArray displayMessages() {
        if (session == null) return new JSONArray();
        JSONArray path=NativeHistoryStore.visibleMessages(session);
        Map<String,List<String>> versions=new LinkedHashMap<>();
        if(session.has("rootMessageId")) for(int i=0;i<messages().length();i++) {
            JSONObject m=messages().optJSONObject(i); if (m != null) versions.computeIfAbsent(m.optString("parentId"), k->new ArrayList<>()).add(m.optString("id"));
        }
        JSONArray out=new JSONArray();
        for(int i=0;i<path.length();i++) {
            JSONObject m=path.optJSONObject(i); if(m==null) continue;
            JSONObject v = new JSONObject(); for(java.util.Iterator<String> it=m.keys();it.hasNext();) {String k=it.next();put(v,k,m.opt(k));}
            List<String> siblings=versions.get(m.optString("parentId"));
            if(siblings!=null) {
                int at=siblings.indexOf(m.optString("id"));
                if(at>0) put(v,"prevId",siblings.get(at-1));
                if(at>=0 && at+1<siblings.size()) put(v,"nextId",siblings.get(at+1));
            }
            boolean user = "user".equals(m.optString("role"));
            put(v,"canEdit",user && !generating());
            put(v,"canContinue",!user && !generating() && i == path.length() - 1 && NativeChatHistory.continuable(m));
            if (!user) put(v,"canSpeak",true);
            out.put(v);
        }
        return out;
    }
    void send(String text) {
        if (importPaused || closed) return;
        if (generating()) {
            listener.problem(en() ? "A reply is still generating." : "有回答正在生成。");
            return;
        }
        if (text.trim().isEmpty() && files.length() == 0) return;
        long now = System.currentTimeMillis();
        boolean first = session == null;
        if (first) {
            String seed = text.trim().isEmpty() ? fileName() : text.trim();
            String title = seed.substring(0, seed.offsetByCodePoints(0, Math.min(seed.codePointCount(0, seed.length()), 42)));
            session = object("id", id(), "title", title, "messages", new JSONArray(), "rootMessageId", JSONObject.NULL,
                    "createdAt", now, "updatedAt", now);
            prefs();
        }
        normalize();
        JSONArray path = NativeHistoryStore.visibleMessages(session);
        JSONObject last = path.optJSONObject(path.length() - 1);
        JSONObject user = append(last == null ? null : last.optString("id"),
                object("id", id(), "role", "user", "createdAt", now, "content", text, "files", files));
        // Persist the message before clearing its draft (including a previously unattached new-chat draft).
        store.saveSession(session);
        if (first) {
            store.setDraft(null, "");
            store.setDraftAttachments(null, new JSONArray());
        }
        draft("");
        files = new JSONArray();
        storeDraft();
        store.setDraftAttachments(sessionId(), files);
        start(user, first);
    }

    void retry(String replyId) {
        if (importPaused || closed || generating() || session == null || !visible(replyId)) return;
        normalize();
        JSONObject old = find(replyId);
        if (old == null || !"assistant".equals(old.optString("role"))) return;
        JSONObject user = find(old.optString("parentId"));
        if (user != null) start(user, false);
    }

    void edit(String userId, String text) {
        if (importPaused || closed || generating() || session == null || !visible(userId)) return;
        normalize();
        JSONObject old = find(userId);
        if (old == null || !"user".equals(old.optString("role"))) return;
        JSONArray originals = old.optJSONArray("files");
        if (text.trim().isEmpty() && (originals == null || originals.length() == 0)) return;
        JSONArray copied = new JSONArray();
        if (originals != null) {
            try { copied = new JSONArray(originals.toString()); }
            catch (JSONException invalid) { throw new IllegalArgumentException(invalid); }
        }
        JSONObject replacement = object("id", id(), "role", "user", "createdAt", System.currentTimeMillis(),
                "content", text, "files", copied);
        start(append(old.isNull("parentId") ? null : old.optString("parentId"), replacement), false);
    }

    private void start(JSONObject user, boolean newSession) {
        long now = System.currentTimeMillis();
        JSONObject target = append(user.optString("id"), object("id", id(), "role", "assistant", "createdAt", now,
                "status", "pending", "model", model, "thinking", thinking, "search", NativeChatClient.FLASH.equals(model) && search,
                "blocks", new JSONArray(), "prefix", true));
        run(target, false, newSession || session.optString("title").trim().isEmpty());
    }

    void resume(String replyId) {
        if (importPaused || closed || generating() || session == null) return;
        JSONArray path = NativeHistoryStore.visibleMessages(session);
        JSONObject target = path.optJSONObject(path.length() - 1);
        if (target == null || !replyId.equals(target.optString("id")) || !NativeChatHistory.continuable(target)) return;
        run(target, true, false);
    }

    private static JSONArray join(JSONArray first, JSONArray second) {
        JSONArray both = new JSONArray();
        for (int i = 0; first != null && i < first.length(); i++) both.put(first.opt(i));
        for (int i = 0; second != null && i < second.length(); i++) both.put(second.opt(i));
        return both;
    }

    private void run(JSONObject target, boolean continuing, boolean requestTitle) {
        activeSession = session;
        pending = target;
        stopRequested = false;
        prefixBlocks = continuing ? target.optJSONArray("blocks") : null;
        previousUsage = continuing ? target.optJSONArray("usageRuns") : null;
        if (continuing && previousUsage == null) {
            JSONObject legacy = target.optJSONObject("usage");
            previousUsage = new JSONArray().put(legacy == null ? object("kind", "missing") : object("kind", "legacy", "usage", legacy));
        }
        String chosen = target.optString("model", model);
        boolean deep = target.optBoolean("thinking", thinking);
        boolean online = NativeChatClient.FLASH.equals(chosen) && target.optBoolean("search", search);
        initialTitle = session.optString("title", "");
        allowGeneratedTitle = requestTitle && NativeChatClient.FLASH.equals(chosen);
        try {
            // Continuation eligibility depends on the previous status and reason. Capture its request
            // before transitioning the same stored assistant message to pending.
            JSONArray path = join(null, NativeHistoryStore.visibleMessages(session));
            if (continuing) path.put(path.length() - 1, new JSONObject(target.toString()));
            put(target, "usageRuns", join(previousUsage, new JSONArray().put(object("kind", "missing"))));
            target.remove("usage"); target.remove("stopReason"); target.remove("stopDetails"); target.remove("error");
            put(target, "status", "pending");
            put(session, "updatedAt", System.currentTimeMillis());
            store.saveSession(session);
            store.setActiveId(sessionId());
            String style = settings.preference("style"), language = settings.preference("language");
            String system = ("brief".equals(style) ? "Answer concisely. " : "detailed".equals(style) ? "Give detailed answers. " : "")
                    + ("en".equals(language) ? "Reply in English." : "zh".equals(language) ? "请用中文回答。" : "");
            String location = settings.locationLabel();
            if (!location.isEmpty()) system += "\nUser-selected region: " + location + ".";
            new Request(target, path, continuing, chosen, deep, online, system).prepare();

        } catch (Exception ex) {
            received(object("status", "error", "error", en() ? "This conversation could not be sent." : "这段对话暂时无法发送。"));
        }
        listener.changed();
    }

    /** Holds only this request's visible file references. Switching the open thread cannot redirect a write. */
    private final class Request {
        final JSONObject target;
        final JSONArray path;
        final String chosen, system;
        final boolean continuation, deep, online;
        final Map<String,List<JSONObject>> missing = new LinkedHashMap<>();

        Request(JSONObject target, JSONArray path, boolean continuation, String chosen, boolean deep, boolean online, String system)
                throws JSONException {
            this.target = target;
            this.path = path;
            this.continuation = continuation;
            this.chosen = chosen;
            this.deep = deep;
            this.online = online;
            this.system = system;
            for (JSONObject file : NativeChatHistory.missingFiles(path, continuation, deep)) {
                String id = file.isNull("id") ? "" : file.optString("id");
                missing.computeIfAbsent(id, key -> new ArrayList<>()).add(file);
            }
        }

        void prepare() {
            if (closed || target != pending) return;
            if (stopRequested) {
                received(object("status", "stopped"));
                return;
            }
            if (missing.isEmpty()) {
                try {
                    JSONArray history = NativeChatClient.history(path, continuation, deep);
                    call = client.send(store.workspace, chosen, deep, online, system, history, true,
                            reply -> { if (pending == target) received(reply); });
                } catch (JSONException invalid) {
                    received(object("status", "error", "error", invalid.getMessage()));
                }
                return;
            }
            String id = missing.keySet().iterator().next();
            List<JSONObject> refs = missing.remove(id);
            JSONObject file = refs.get(0);
            call = client.upload(store, file, (uploaded, error) -> {
                if (closed || pending != target) return;
                if (stopRequested) {
                    received(object("status", "stopped"));
                    return;
                }
                if (uploaded == null) {
                    String name = file.optString("name", "attachment");
                    String message = en() ? "Could not restore “" + name + "”. " : "无法恢复附件「" + name + "」。";
                    received(object("status", "error", "error", message + error));
                    return;
                }
                for (JSONObject ref : refs) {
                    put(ref, "serverId", uploaded.optString("serverId"));
                    if (ref.optBoolean("isImage")) put(ref, "imageSizeChecked", true);
                }
                // Save repaired metadata immediately, including if the next file fails or the user stops.
                JSONObject saved = store.session(activeSession.optString("id"));
                if (saved != null && !initialTitle.equals(saved.optString("title"))) put(activeSession, "title", saved.optString("title"));
                store.saveSession(activeSession);
                prepare();
            });
        }
    }

    private void received(JSONObject reply) {
        if (closed || pending == null) return;
        String status = reply.optString("status", "error");
        put(pending, "status", status);
        for (String field : new String[]{"usage", "stopReason", "stopDetails"}) {
            if (!reply.isNull(field)) put(pending, field, reply.opt(field));
        }
        JSONArray added = reply.optJSONArray("blocks");
        if (added != null) {
            JSONArray all = join(prefixBlocks, added);
            if ("refusal".equals(reply.optString("stopReason"))) {
                JSONArray allowed = new JSONArray();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject b = all.optJSONObject(i);
                    if (b != null && !"text".equals(b.optString("type")) && !"thinking".equals(b.optString("type"))) allowed.put(b);
                }
                all = allowed;
            }
            put(pending, "blocks", all);
        }
        String error = reply.optString("error");
        if (!error.isEmpty()) put(pending, "error", object("message", error, "kind", "api"));
        JSONObject usage = reply.optJSONObject("usage");
        if (usage != null) {
            JSONObject run = object("kind", reply.optString("usageKind", "missing"), "usage", usage);
            put(pending, "usageRuns", join(previousUsage, new JSONArray().put(run)));
        }
        if (!"streaming".equals(status)) {
            String generated = reply.optString("title", "").trim();
            // A manual rename on another screen may have updated the persisted session meanwhile.
            JSONObject saved = store.session(activeSession.optString("id"));
            String latestName = saved == null ? initialTitle : saved.optString("title", "");
            if (!initialTitle.equals(latestName)) put(activeSession, "title", latestName);
            else if (allowGeneratedTitle && initialTitle.equals(activeSession.optString("title")) && "done".equals(status)
                    && !generated.isEmpty() && ("end_turn".equals(reply.optString("stopReason")) || "max_tokens".equals(reply.optString("stopReason"))))
                put(activeSession, "title", generated);
            put(activeSession, "updatedAt", System.currentTimeMillis());
            store.saveSession(activeSession);
            call = null; pending = null; activeSession = null;
            prefixBlocks = null; previousUsage = null; initialTitle = null;
        }
        listener.changed();
    }
}
