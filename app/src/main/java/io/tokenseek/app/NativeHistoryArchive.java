package io.tokenseek.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.*;
import static io.tokenseek.app.NativeHistoryStore.put;

/**
 * Reads the ZIP central directory before applying an entire import in one SQLite transaction.
 */
final class NativeHistoryArchive {

    private static final String MANIFEST = "manifest.json", INDEX = "chat_session_index.json";

    private NativeHistoryArchive() {
    }

    static boolean safeId(String id) {
        return id != null && !id.isEmpty() && !".".equals(id) && !"..".equals(id) && id.indexOf('/') < 0 && id.indexOf('\\') < 0 && id.indexOf(0) < 0;
    }

    static String normalPath(String raw) throws IOException {
        String path = Normalizer.normalize(raw, Normalizer.Form.NFC);
        for (String part : path.split("/", -1)) if (!safeId(part))
            throw new IOException("Invalid ZIP entry");
        return path;
    }

    private static String sessionPath(String id) {
        return "chat_sessions/" + id + ".json";
    }

    private static Set<String> ids(JSONArray items) throws JSONException, IOException {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < items.length(); i++) {
            String id = items.getJSONObject(i).getString("id");
            if (!safeId(id) || !result.add(id))
                throw new IOException("Invalid or duplicate ID");
        }
        return result;
    }

    static JSONObject organization(JSONObject org, Set<String> allowed, boolean omitEmpty) throws JSONException, IOException {
        JSONObject result = NativeHistoryStore.emptyOrganization();
        if (org == null)
            return result;
        JSONArray projects = org.getJSONArray("projects");
        Set<String> projectIds = ids(projects), used = new HashSet<>(), pins = new HashSet<>();
        JSONObject assignments = org.getJSONObject("assignments"), dest = result.getJSONObject("assignments");
        for (Iterator<String> itr = assignments.keys(); itr.hasNext(); ) {
            String sid = itr.next(), pid = assignments.getString(sid);
            if (!projectIds.contains(pid))
                throw new IOException("Unknown assigned project");
            if (allowed.contains(sid)) {
                put(dest, sid, pid);
                used.add(pid);
            }
        }
        for (int i = 0; i < projects.length(); i++) {
            JSONObject project = projects.getJSONObject(i);
            if (project.getString("name").trim().isEmpty())
                throw new IOException("Missing project name");
            if (!omitEmpty || used.contains(project.getString("id")))
                result.getJSONArray("projects").put(project);
        }
        JSONArray ordered = org.getJSONArray("pinned");
        for (int i = 0; i < ordered.length(); i++) {
            String id = ordered.getString(i);
            if (allowed.contains(id) && pins.add(id))
                result.getJSONArray("pinned").put(id);
        }
        return result;
    }

    static JSONObject mergedOrganization(JSONObject local, JSONObject incoming, Set<String> added) throws JSONException, IOException {
        Set<String> projects = ids(local.getJSONArray("projects"));
        JSONArray incomingProjects = incoming.getJSONArray("projects");
        for (int i = 0; i < incomingProjects.length(); i++) {
            JSONObject p = incomingProjects.getJSONObject(i);
            if (projects.add(p.getString("id")))
                local.getJSONArray("projects").put(p);
        }
        JSONObject moved = incoming.getJSONObject("assignments");
        for (Iterator<String> it = moved.keys(); it.hasNext(); ) {
            String id = it.next();
            if (added.contains(id))
                put(local.getJSONObject("assignments"), id, moved.get(id));
        }
        JSONArray pins = incoming.getJSONArray("pinned");
        for (int i = 0; i < pins.length(); i++) if (added.contains(pins.getString(i)))
            local.getJSONArray("pinned").put(pins.getString(i));
        return local;
    }

    private static JSONObject resume(NativeHistoryStore store, Set<String> include, boolean single) throws JSONException {
        JSONObject resume = new JSONObject();
        JSONArray drafts = new JSONArray();
        JSONArray draftIds = store.keys("draft");
        for (int i = 0; i < draftIds.length(); i++) {
            String id = draftIds.getString(i);
            if (include.contains(id) || (id.isEmpty() && !single))
                drafts.put(store.object("draft", id));
        }
        put(resume, "drafts", drafts);
        String active = store.activeId();
        put(resume, "activeId", include.contains(active) ? active : JSONObject.NULL);
        JSONObject uploads = store.object("state", "draftUploads");
        if (uploads != null && !single)
            put(resume, "uploads", uploads);
        return resume;
    }

    static void restoreResume(NativeHistoryStore store, JSONObject resume, Set<String> ids, boolean replace) throws JSONException, IOException {
        if (resume == null)
            return;
        JSONArray drafts = resume.getJSONArray("drafts");
        for (int i = 0; i < drafts.length(); i++) {
            JSONObject draft = drafts.getJSONObject(i);
            String id = draft.isNull("sessionId") ? "" : draft.getString("sessionId");
            if (!id.isEmpty() && !ids.contains(id))
                throw new IOException("Draft references an unknown session");
            if (!store.has("draft", id))
                store.writeObject("draft", id, draft);
        }
        String active = resume.isNull("activeId") ? null : resume.getString("activeId");
        if (active != null && !ids.contains(active))
            throw new IOException("Active session is missing");
        if (replace || store.activeId() == null)
            store.setActiveId(active);
        JSONObject uploads = resume.optJSONObject("uploads");
        if (uploads != null) {
            JSONObject local = store.object("state", "draftUploads");
            if (local == null)
                local = new JSONObject();
            for (Iterator<String> it = uploads.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!local.has(k))
                    put(local, k, uploads.get(k));
            }
            store.writeObject("state", "draftUploads", local);
        }
    }

    private static void json(ZipOutputStream out, String path, Object object) throws IOException {
        out.putNextEntry(new ZipEntry(path));
        out.write(object.toString().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    static void write(NativeHistoryStore store, OutputStream stream, String single) throws IOException {
        if (stream == null)
            throw new IOException("No export destination");
        store.db.beginTransactionNonExclusive();
        // Keep ownership of the caller's SAF stream with the caller.
        try (ZipOutputStream out = new ZipOutputStream(new FilterOutputStream(stream) {

            @Override
            public void close() throws IOException {
                flush();
            }
        })) {
            Set<String> included = single == null ? ids(store.sessions()) : Collections.singleton(single);
            JSONObject manifest = new JSONObject();
            put(manifest, "export_format_version", 3);
            put(manifest, "tokenseek_organization", organization(store.organization(), included, single != null));
            JSONObject snapshot = single == null ? resume(store, included, false) : null;
            if (snapshot != null)
                put(manifest, "tokenseek_resume", snapshot);
            json(out, MANIFEST, manifest);
            if (snapshot != null)
                HistoryDraftFiles.write(store, out, snapshot.getJSONArray("drafts"));
            JSONArray index = new JSONArray();
            Map<String, String> originals = new LinkedHashMap<>();
            for (String id : included) {
                JSONObject session = store.session(id);
                if (session == null)
                    throw new IOException("Session unavailable");
                JSONObject file = HistorySessionCodec.encode(store, session, originals);
                json(out, sessionPath(id), file);
                JSONObject entry = new JSONObject();
                for (String key : new String[] { "id", "title", "pinned", "inserted_at", "updated_at" }) put(entry, key, file.get(key));
                put(entry, "message_count", file.getJSONArray("messages").length());
                index.put(entry);
            }
            json(out, INDEX, index);
            for (Map.Entry<String, String> f : originals.entrySet()) {
                out.putNextEntry(new ZipEntry(f.getKey()));
                if (!store.copy("file", f.getValue(), out))
                    throw new IOException("Attachment unavailable");
                out.closeEntry();
            }
            out.finish();
            store.db.setTransactionSuccessful();
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Invalid history", e);
        } finally {
            store.db.endTransaction();
        }
    }

    private static void transfer(InputStream from, OutputStream to) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int read;
        while ((read = from.read(buf)) != -1) to.write(buf, 0, read);
    }

    private static InputStream contents(ZipFile zip, ZipEntry e) throws IOException {
        if (e == null)
            throw new IOException("Missing ZIP entry");
        // ZipFile validates the directory; verify each data stream independently before committing.
        return new FilterInputStream(new CheckedInputStream(zip.getInputStream(e), new CRC32())) {

            long seen;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n >= 0) {
                    seen += n;
                    if (seen > e.getSize()) throw new IOException("Invalid ZIP entry length");
                } else
                    verify();
                return n;
            }

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0) {
                    seen++;
                    if (seen > e.getSize()) throw new IOException("Invalid ZIP entry length");
                } else
                    verify();
                return value;
            }

            private void verify() throws IOException {
                if (seen != e.getSize() || ((CheckedInputStream) in).getChecksum().getValue() != e.getCrc())
                    throw new IOException("Incomplete ZIP entry");
            }
        };
    }

    private static String text(ZipFile z, ZipEntry e) throws IOException {
        if (e == null)
            throw new IOException("Missing ZIP entry");
        // JSON needs to fit in memory; attachment bytes never enter the JSON buffers.
        long budget = Runtime.getRuntime().maxMemory() / 4;
        if (e.getSize() > budget)
            throw new IOException("History entry is too large for this device");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = contents(z, e)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if ((long) bytes.size() + n > budget)
                    throw new IOException("History entry exceeds available memory");
                bytes.write(buffer, 0, n);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    static int read(NativeHistoryStore store, InputStream stream, boolean replace) throws IOException {
        if (stream == null)
            throw new IOException("No import source");
        File temporary = File.createTempFile("history-import-", ".zip", store.context.getCacheDir());
        try {
            try (OutputStream dest = new FileOutputStream(temporary)) {
                transfer(stream, dest);
            }
            try (ZipFile zip = new ZipFile(temporary)) {
                Map<String, ZipEntry> entries = new LinkedHashMap<>();
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry e = all.nextElement();
                    if (e.isDirectory())
                        continue;
                    String path = normalPath(e.getName());
                    if (entries.put(path, e) != null)
                        throw new IOException("Duplicate archive entry");
                }
                if (entries.isEmpty() || !MANIFEST.equals(entries.keySet().iterator().next()))
                    throw new IOException("Not a DeepSeek backup");
                JSONObject manifest = new JSONObject(text(zip, entries.get(MANIFEST)));
                if (!(manifest.get("export_format_version") instanceof Number) || manifest.getDouble("export_format_version") != 3)
                    throw new IOException("Unsupported backup version");
                JSONArray index = new JSONArray(text(zip, entries.get(INDEX)));
                Set<String> listed = ids(index), added = new HashSet<>();
                for (String path : entries.keySet()) {
                    if (path.matches("chat_sessions/[^/]+\\.json") && !listed.contains(path.substring(14, path.length() - 5)))
                        throw new IOException("Session missing from index");
                }
                JSONObject organization = organization(manifest.has("tokenseek_organization") ? manifest.getJSONObject("tokenseek_organization") : null, listed, false);
                JSONObject resume = manifest.has("tokenseek_resume") ? manifest.getJSONObject("tokenseek_resume") : null;
                if (resume == null && entries.containsKey("tokenseek_resume.json"))
                    resume = new JSONObject(text(zip, entries.get("tokenseek_resume.json")));
                store.db.beginTransaction();
                try {
                    if (replace)
                        store.clearWorkspace();
                    for (int i = 0; i < index.length(); i++) {
                        JSONObject entry = index.getJSONObject(i);
                        String id = entry.getString("id");
                        Map<String, String> originals = new HashMap<>();
                        JSONObject file = new JSONObject(text(zip, entries.get(sessionPath(id))));
                        JSONObject session = HistorySessionCodec.decode(file, originals);
                        if (!id.equals(session.getString("id")) || entry.getInt("message_count") != session.getJSONArray("messages").length())
                            throw new IOException("Backup index does not match session");
                        boolean existing = store.has("session", id);
                        if (!existing) {
                            store.saveSession(session);
                            added.add(id);
                        }
                        for (Map.Entry<String, String> f : originals.entrySet()) {
                            ZipEntry blob = entries.get(f.getKey());
                            if (blob == null) {
                                if (manifest.has("tokenseek_organization"))
                                    throw new IOException("Missing attachment");
                                continue;
                            }
                            try (InputStream in = contents(zip, blob)) {
                                if (!existing && !store.has("file", f.getValue()))
                                    store.saveAttachment(f.getValue(), in);
                                else
                                    transfer(in, DISCARD);
                            }
                        }
                    }
                    store.saveOrganization(replace ? organization : mergedOrganization(store.organization(), organization, added));
                    restoreResume(store, resume, listed, replace);
                    if (resume != null && entries.containsKey("user.json")) {
                        try (InputStream input = contents(zip, entries.get("user.json"))) {
                            HistoryDraftFiles.read(store, input, resume.getJSONArray("drafts"));
                        }
                    }
                    store.db.setTransactionSuccessful();
                    return added.size();
                } finally {
                    store.db.endTransaction();
                }
            }
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Invalid backup data", e);
        } finally {
            if (!temporary.delete())
                temporary.deleteOnExit();
        }
    }

    private static final OutputStream DISCARD = new OutputStream() {

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] bytes, int off, int len) {
        }
    };
}
