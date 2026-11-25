package io.tokenseek.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Isolated per-profile history. Large records are split below Android's CursorWindow limit.
 */
final class NativeHistoryStore {

    private static final int CHUNK = 64 * 1024;

    private static Database helper;

    final SQLiteDatabase db;

    final String workspace;

    final Context context;

    private static final class Database extends SQLiteOpenHelper {

        Database(Context context) {
            super(context, "tokenseek-history.db", null, 1);
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE sessions (workspace TEXT NOT NULL, id TEXT NOT NULL, title TEXT, created INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(workspace,id))");
            db.execSQL("CREATE INDEX session_dates ON sessions(workspace,updated DESC)");
            db.execSQL("CREATE TABLE chunks (workspace TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, seq INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(workspace,kind,id,seq))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported native history database version");
        }
    }

    NativeHistoryStore(Context context, String workspace) {
        if (workspace == null || workspace.isEmpty())
            throw new IllegalArgumentException("Missing workspace");
        this.workspace = workspace;
        this.context = context.getApplicationContext();
        synchronized (NativeHistoryStore.class) {
            if (helper == null)
                helper = new Database(this.context);
            db = helper.getWritableDatabase();
        }
    }

    static boolean hasLegacyStorage(Context context) {
        File data = new File(context.getApplicationInfo().dataDir, "app_webview");
        for (String dir : new String[] { "Default/IndexedDB", "IndexedDB" }) {
            File[] files = new File(data, dir).listFiles();
            if (files == null)
                continue;
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith("https_appassets.androidplatform.net_") && name.contains(".indexeddb.") && file.isDirectory())
                    return true;
            }
        }
        return false;
    }

    JSONArray sessions() {
        JSONArray result = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT id,title,created,updated FROM sessions WHERE workspace=? ORDER BY updated DESC,id", new String[] { workspace })) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                put(row, "id", c.getString(0));
                put(row, "title", c.getString(1));
                put(row, "createdAt", c.getLong(2));
                put(row, "updatedAt", c.getLong(3));
                result.put(row);
            }
        }
        return result;
    }

    JSONArray allSessions() {
        db.beginTransactionNonExclusive();
        try {
            JSONArray result = new JSONArray(), rows = sessions();
            for (int i = 0; i < rows.length(); i++) result.put(session(rows.optJSONObject(i).optString("id")));
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    JSONObject session(String id) {
        return id == null ? null : object("session", id);
    }

    void saveSession(JSONObject session) {
        String id = session.optString("id");
        if (!NativeHistoryArchive.safeId(id) || session.optJSONArray("messages") == null)
            throw new IllegalArgumentException("Invalid session");
        db.beginTransaction();
        try {
            ContentValues metadata = new ContentValues();
            metadata.put("workspace", workspace);
            metadata.put("id", id);
            metadata.put("title", session.optString("title", ""));
            metadata.put("created", session.optLong("createdAt"));
            metadata.put("updated", session.optLong("updatedAt"));
            if (db.insertWithOnConflict("sessions", null, metadata, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                throw new android.database.SQLException("Could not save session metadata");
            writeObject("session", id, session);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void deleteSession(String id) {
        if (id == null)
            return;
        db.beginTransaction();
        try {
            db.delete("sessions", "workspace=? AND id=?", new String[] { workspace, id });
            remove("session", id);
            remove("draft", id);
            if (id.equals(activeId()))
                setActiveId(null);
            JSONObject org = organization();
            org.optJSONObject("assignments").remove(id);
            JSONArray pins = org.optJSONArray("pinned");
            for (int i = pins.length() - 1; i >= 0; i--) if (id.equals(pins.optString(i)))
                pins.remove(i);
            saveOrganization(org);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    static JSONObject emptyOrganization() {
        JSONObject obj = new JSONObject();
        put(obj, "projects", new JSONArray());
        put(obj, "assignments", new JSONObject());
        put(obj, "pinned", new JSONArray());
        return obj;
    }

    JSONObject organization() {
        JSONObject found = object("state", "organization");
        return found == null ? emptyOrganization() : found;
    }

    void saveOrganization(JSONObject value) {
        if (value == null || value.optJSONArray("projects") == null || value.optJSONObject("assignments") == null || value.optJSONArray("pinned") == null)
            throw new IllegalArgumentException("Invalid organization");
        writeObject("state", "organization", value);
    }

    String activeId() {
        JSONObject state = object("state", "active");
        return state == null ? null : state.optString("id", null);
    }

    void setActiveId(String id) {
        JSONObject state = new JSONObject();
        put(state, "id", id);
        writeObject("state", "active", state);
    }

    String draft(String id) {
        JSONObject value = object("draft", id == null ? "" : id);
        return value == null ? "" : value.optString("text", "");
    }

    JSONArray draftAttachments(String id) {
        JSONObject draft = object("draft", id == null ? "" : id);
        JSONArray list = draft == null ? null : draft.optJSONArray("attachments");
        return list == null ? new JSONArray() : list;
    }

    void setDraftAttachments(String id, JSONArray attachments) {
        changeDraft(id, "attachments", attachments == null ? new JSONArray() : attachments);
    }

    void setDraft(String id, String text) {
        changeDraft(id, "text", text == null ? "" : text);
    }

    private void changeDraft(String id, String field, Object data) {
        String key = id == null ? "" : id;
        db.beginTransaction();
        try {
            JSONObject value = object("draft", key);
            if (value == null)
                value = new JSONObject();
            put(value, field, data);
            put(value, "sessionId", id == null ? JSONObject.NULL : id);
            if (value.optString("text").isEmpty() && (value.optJSONArray("attachments") == null || value.optJSONArray("attachments").length() == 0))
                remove("draft", key);
            else
                writeObject("draft", key, value);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    static JSONArray visibleMessages(JSONObject session) {
        JSONArray visible = new JSONArray();
        if (session == null)
            return visible;
        JSONArray all = session.optJSONArray("messages");
        if (all == null)
            return visible;
        if (!session.has("rootMessageId"))
            return all;
        Map<String, JSONObject> messages = new HashMap<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m != null)
                messages.put(m.optString("id"), m);
        }
        Set<String> seen = new HashSet<>();
        String id = session.optString("rootMessageId", null);
        while (id != null && seen.add(id)) {
            JSONObject m = messages.get(id);
            if (m == null)
                break;
            visible.put(m);
            id = m.optString("selectedChildId", null);
        }
        return visible;
    }

    void saveAttachment(String id, InputStream source) throws IOException {
        if (id == null || id.isEmpty() || source == null)
            throw new IOException("Invalid attachment");
        write("file", id, source);
    }

    boolean copyAttachment(String id, OutputStream destination) throws IOException {
        return copy("file", id, destination);
    }

    /** A stable stream for an upload, including when another operation replaces the workspace. */
    InputStream openAttachment(String id) throws IOException {
        if (id == null) return null;
        File temporary = File.createTempFile("history-attachment-", ".tmp", context.getCacheDir());
        boolean opened = false;
        try {
            db.beginTransactionNonExclusive();
            try (OutputStream output = new java.io.FileOutputStream(temporary)) {
                if (!copyAttachment(id, output)) return null;
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            java.io.FileInputStream input = new java.io.FileInputStream(temporary);
            opened = true;
            if (temporary.delete()) return input; // Android's filesystem keeps the open descriptor alive.
            return new java.io.FilterInputStream(input) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { temporary.delete(); }
                }
            };
        } finally {
            if (!opened) temporary.delete();
        }
    }

    boolean hasAttachment(String id) {
        return id != null && has("file", id);
    }

    void exportZip(OutputStream stream, String singleId) throws IOException {
        NativeHistoryArchive.write(this, stream, singleId);
    }

    int importZip(InputStream stream, boolean replace) throws IOException {
        return NativeHistoryArchive.read(this, stream, replace);
    }

    static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    JSONArray keys(String kind) {
        JSONArray result = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT DISTINCT id FROM chunks WHERE workspace=? AND kind=? ORDER BY id", new String[] { workspace, kind })) {
            while (c.moveToNext()) result.put(c.getString(0));
        }
        return result;
    }

    JSONObject object(String kind, String id) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (!copy(kind, id, output))
                return null;
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Could not read native history", e);
        }
    }

    boolean copy(String kind, String id, OutputStream to) throws IOException {
        try (Cursor c = db.rawQuery("SELECT data FROM chunks WHERE workspace=? AND kind=? AND id=? ORDER BY seq", new String[] { workspace, kind, id })) {
            if (!c.moveToFirst())
                return false;
            do {
                to.write(c.getBlob(0));
            } while (c.moveToNext());
            return true;
        }
    }

    boolean has(String kind, String id) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM chunks WHERE workspace=? AND kind=? AND id=? LIMIT 1", new String[] { workspace, kind, id })) {
            return c.moveToFirst();
        }
    }

    void clearWorkspace() {
        db.delete("chunks", "workspace=?", new String[] { workspace });
        db.delete("sessions", "workspace=?", new String[] { workspace });
    }

    private void remove(String kind, String id) {
        db.delete("chunks", "workspace=? AND kind=? AND id=?", new String[] { workspace, kind, id });
    }

    void writeObject(String kind, String id, JSONObject value) {
        try {
            write(kind, id, new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void write(String kind, String id, InputStream from) throws IOException {
        db.beginTransaction();
        try {
            remove(kind, id);
            byte[] buffer = new byte[CHUNK];
            int index = 0;
            while (true) {
                int n = 0, r;
                while (n < buffer.length && (r = from.read(buffer, n, buffer.length - n)) != -1) n += r;
                if (n == 0 && index > 0)
                    break;
                ContentValues v = new ContentValues();
                v.put("workspace", workspace);
                v.put("kind", kind);
                v.put("id", id);
                v.put("seq", index++);
                v.put("data", n == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, n));
                db.insertOrThrow("chunks", null, v);
                if (n < buffer.length)
                    break;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
