package io.tokenseek.app;

import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonWriter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The optional v3 user.json entry is skipped by older clients. No credentials are archived.
 */
final class HistoryDraftFiles {

    private HistoryDraftFiles() {
    }

    private static Set<String> references(JSONArray drafts) throws JSONException {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < drafts.length(); i++) {
            JSONArray files = drafts.getJSONObject(i).optJSONArray("attachments");
            for (int j = 0; files != null && j < files.length(); j++) {
                JSONObject file = files.getJSONObject(j);
                String id = file.getString("id");
                if (!id.isEmpty())
                    ids.add(id);
            }
        }
        return ids;
    }

    static void write(NativeHistoryStore store, ZipOutputStream zip, JSONArray drafts) throws JSONException, IOException {
        Set<String> files = references(drafts);
        files.removeIf(id -> !store.hasAttachment(id));
        if (files.isEmpty())
            return;
        zip.putNextEntry(new ZipEntry("user.json"));
        JsonWriter json = new JsonWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        json.beginObject().name("tokenseek_draft_files").beginObject();
        for (String id : files) {
            json.name(id).beginArray();
            store.copyAttachment(id, new OutputStream() {

                @Override
                public void write(byte[] bytes, int off, int len) throws IOException {
                    json.value(Base64.encodeToString(bytes, off, len, Base64.NO_WRAP));
                }

                @Override
                public void write(int b) throws IOException {
                    write(new byte[] { (byte) b }, 0, 1);
                }
            });
            json.endArray();
        }
        json.endObject().endObject();
        json.flush();
        zip.closeEntry();
    }

    static void read(NativeHistoryStore store, InputStream source, JSONArray drafts) throws IOException, JSONException {
        Set<String> allowed = references(drafts);
        JsonReader json = new JsonReader(new InputStreamReader(source, StandardCharsets.UTF_8));
        json.beginObject();
        while (json.hasNext()) {
            if (!"tokenseek_draft_files".equals(json.nextName())) {
                json.skipValue();
                continue;
            }
            json.beginObject();
            while (json.hasNext()) {
                String id = json.nextName();
                if (!allowed.remove(id))
                    throw new IOException("Unreferenced or duplicate draft attachment");
                json.beginArray();
                if (store.hasAttachment(id)) {
                    while (json.hasNext()) json.skipValue();
                } else {
                    store.saveAttachment(id, new InputStream() {

                        byte[] chunk = new byte[0];

                        int position;

                        @Override
                        public int read(byte[] out, int off, int len) throws IOException {
                            while (position >= chunk.length) {
                                if (!json.hasNext())
                                    return -1;
                                String encoded = json.nextString();
                                if (encoded.length() > MAX_BASE64_CHUNK)
                                    throw new IOException("Invalid draft attachment chunk");
                                try {
                                    chunk = Base64.decode(encoded, Base64.NO_WRAP);
                                } catch (IllegalArgumentException e) {
                                    throw new IOException("Invalid draft attachment bytes", e);
                                }
                                position = 0;
                            }
                            int n = Math.min(len, chunk.length - position);
                            System.arraycopy(chunk, position, out, off, n);
                            position += n;
                            return n;
                        }

                        @Override
                        public int read() throws IOException {
                            byte[] one = new byte[1];
                            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
                        }
                    });
                }
                json.endArray();
            }
            json.endObject();
        }
        json.endObject();
        if (json.peek() != android.util.JsonToken.END_DOCUMENT)
            throw new IOException("Invalid draft attachment data");
    }

    // A serialized 64 KiB database chunk is 87,384 characters in base64.
    private static final int MAX_BASE64_CHUNK = ((64 * 1024 + 2) / 3) * 4;
}
