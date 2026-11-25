package io.tokenseek.app;

import static io.tokenseek.app.NativeAccountStore.object;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Immutable, per-profile report snapshots. The only mutable file is the selected report id. */
final class NativePlatformUsageStore {
    // Serialize picker imports, reads and selections across Activity recreation.
    static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final String ID = "[0-9a-f]{64}";
    private final File dir, cache;

    NativePlatformUsageStore(Context context, String owner) {
        if (!NativeAccountStore.validId(owner))
            throw new IllegalArgumentException("Invalid workspace");
        cache = context.getCacheDir();
        dir = new File(new File(context.getNoBackupFilesDir(), "platform-usage"), owner);
    }

    static final class Snapshot {
        final JSONArray library;
        final String selected;
        final JSONObject report;

        Snapshot(JSONArray library, String selected, JSONObject report) {
            this.library = library;
            this.selected = selected;
            this.report = report;
        }
    }

    String importReport(InputStream source) throws IOException {
        // The content fingerprint also distinguishes overlapping exports with different key
        // filters.
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        DigestInputStream input = new DigestInputStream(source, digest);
        JSONObject report = NativePlatformUsageReport.parse(input, cache);
        if (input.read() != -1) throw new IOException("Incomplete report");
        StringBuilder hash = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hash.append(Character.forDigit((b >> 4) & 15, 16));
            hash.append(Character.forDigit(b & 15, 16));
        }
        String id = hash.toString();
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Could not create report folder");
        File target = new File(dir, id + ".json");
        if (!target.isFile()) {
            JSONObject header =
                    object(
                            "id",
                            id,
                            "startUtc",
                            report.optString("startUtc"),
                            "endUtc",
                            report.optString("endUtc"),
                            "importedAt",
                            System.currentTimeMillis());
            write(target, header.toString() + "\n" + report);
        }
        select(id);
        return id;
    }

    void select(String id) throws IOException {
        if (id == null || !id.matches(ID) || !new File(dir, id + ".json").isFile())
            throw new IOException("Unknown report");
        write(new File(dir, "selected"), id);
    }

    Snapshot read() throws IOException {
        File[] files = dir.listFiles((folder, name) -> name.matches(ID + "\\.json"));
        if (files == null && dir.exists()) throw new IOException("Could not list saved reports");
        if (files == null || files.length == 0) return new Snapshot(new JSONArray(), "", null);
        Arrays.sort(
                files,
                Comparator.comparingLong(File::lastModified)
                        .reversed()
                        .thenComparing(File::getName));
        JSONArray library = new JSONArray();
        String selected;
        try (FileInputStream is = new FileInputStream(new File(dir, "selected"));
                BufferedReader r = reader(is)) {
            selected = r.readLine();
        } catch (java.io.FileNotFoundException missing) {
            selected = null;
        }
        JSONObject report = null;
        try {
            for (File file : files) {
                try (BufferedReader r = reader(new FileInputStream(file))) {
                    String header = r.readLine();
                    if (header == null) throw new IOException("Missing saved report");
                    JSONObject record = new JSONObject(header);
                    if (!file.getName().equals(record.getString("id") + ".json"))
                        throw new IOException("Invalid saved report");
                    library.put(record);
                    if (record.optString("id").equals(selected))
                        report = new JSONObject(r.readLine());
                }
            }
            if (report == null) {
                selected = library.getJSONObject(0).getString("id");
                try (BufferedReader r =
                        reader(new FileInputStream(new File(dir, selected + ".json")))) {
                    r.readLine();
                    report = new JSONObject(r.readLine());
                }
            }
        } catch (JSONException | NullPointerException invalid) {
            throw new IOException("Damaged saved report", invalid);
        }
        return new Snapshot(library, selected, report);
    }

    private static BufferedReader reader(InputStream input) {
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static void write(File target, String value) throws IOException {
        // Publish a complete new file or preserve the previous one, including on Android 8.
        File temporary = File.createTempFile("writing-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temporary.delete();
        }
    }
}
