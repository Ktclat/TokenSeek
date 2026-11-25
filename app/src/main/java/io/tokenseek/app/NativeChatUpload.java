package io.tokenseek.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;

/** A user-selected document. Upload streams from the content provider rather than copying it into an Activity. */
final class NativeChatUpload {
    private interface Source { InputStream open() throws IOException; }
    private final Source source;
    private final long originalSize;
    private final String name, mime, purpose;
    private final boolean image;

    private NativeChatUpload(Source source, String name, String mime, long size, boolean image) {
        this.source = source;
        this.name = name;
        this.mime = mime;
        originalSize = size;
        this.image = image;
        String extension = extension(name);
        purpose = image ? "" : extension.matches("pdf|docx?|pptx?") ? "rich_layout_recognition" : "user_data";
    }

    static NativeChatUpload inspect(ContentResolver resolver, Uri uri) throws IOException {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) throw new IOException("请通过系统文件选择器重新选择附件。");
        String name = null;
        long size = -1;
        try (Cursor row = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (row != null && row.moveToFirst()) {
                int n = row.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = row.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !row.isNull(n)) name = row.getString(n);
                if (s >= 0 && !row.isNull(s)) size = row.getLong(s);
            }
        }
        if (name == null || name.isEmpty()) name = "attachment";
        String mime = mime(name, resolver.getType(uri));
        return checked(new NativeChatUpload(() -> resolver.openInputStream(uri), name, mime, size, mime.startsWith("image/")));
    }

    static NativeChatUpload archived(NativeHistoryStore store, JSONObject file) throws IOException {
        String id = file.isNull("id") ? "" : file.optString("id");
        if (id.isEmpty() || !store.hasAttachment(id)) throw new IOException("当前账户中找不到原件，请重新选择文件。");
        String name = file.isNull("name") ? "attachment" : file.optString("name", "attachment");
        String mime = mime(name, file.isNull("mimeType") ? null : file.optString("mimeType"));
        boolean image = file.optBoolean("isImage", mime.startsWith("image/"));
        return checked(new NativeChatUpload(() -> store.openAttachment(id), name, mime, file.optLong("size", -1), image));
    }

    private static String mime(String name, String mime) {
        if (mime == null || mime.equals("application/octet-stream")) mime = URLConnection.guessContentTypeFromName(name.toLowerCase(Locale.ROOT));
        if (mime == null || !mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) mime = "application/octet-stream";
        return mime;
    }
    private static NativeChatUpload checked(NativeChatUpload result) throws IOException {
        if (result.originalSize > result.limit()) throw result.oversize();
        return result;
    }

    private static String extension(String name) {
        int last = name.lastIndexOf('.');
        return last < 0 ? "" : name.substring(last + 1).toLowerCase(Locale.ROOT);
    }

    private long limit() { return (purpose.equals("user_data") ? 32L : 64L) * 1024 * 1024; }
    private IOException oversize() { return new IOException("附件大小超过 " + (limit() / (1024 * 1024)) + " MB。"); }
    private InputStream input() throws IOException {
        InputStream file = source.open();
        if (file == null) throw new IOException("无法读取所选文件。");
        return file;
    }

    void send(HttpsURLConnection request, NativeChatClient.Call call) throws IOException {
        String boundary = "TokenSeek-" + UUID.randomUUID();
        request.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        request.setChunkedStreamingMode(65536);
        call.check();
        Bitmap reduced = image ? smallerImage() : null;
        try (OutputStream out = request.getOutputStream()) {
            if (!purpose.isEmpty()) write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n" + purpose + "\r\n");
            String filename = name.replaceAll("[\\p{Cntrl}\\\\/\";]", "_");
            String format = mime;
            if (reduced != null) {
                format = mime.equals("image/jpeg") ? mime : "image/png";
                filename = filename.replaceFirst("\\.[^.]*$", "") + (format.equals("image/jpeg") ? ".jpg" : ".png");
            }
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: " + format + "\r\n\r\n");
            if (reduced == null) {
                try (InputStream in = input()) {
                    byte[] buffer = new byte[65536];
                    int n;
                    long sent = 0;
                    while ((n = in.read(buffer)) >= 0) {
                        call.check();
                        sent += n;
                        if (sent > limit()) throw oversize();
                        out.write(buffer, 0, n);
                    }
                }
            } else {
                call.check();
                Bitmap.CompressFormat codec = format.equals("image/jpeg") ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
                if (!reduced.compress(codec, 95, out)) throw new IOException("无法生成缩小后的图片。");
            }
            write(out, "\r\n--" + boundary + "--\r\n");
        } finally { if (reduced != null) reduced.recycle(); }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private Bitmap smallerImage() throws IOException {
        BitmapFactory.Options info = new BitmapFactory.Options();
        info.inJustDecodeBounds = true;
        try (InputStream in = input()) { BitmapFactory.decodeStream(in, null, info); }
        int longest = Math.max(info.outHeight, info.outWidth);
        // Some formats (e.g. SVG) go directly to the Files API as supplied by the picker.
        if (longest <= 4096) return null;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = input()) {
            orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException noExif) { /* Not all image formats carry Exif metadata. */ }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (longest / decode.inSampleSize > 4096) decode.inSampleSize *= 2;
        Bitmap image;
        try (InputStream in = input()) { image = BitmapFactory.decodeStream(in, null, decode); }
        if (image == null) throw new IOException("无法读取图片，请重新选择附件。");
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(-90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(-90); break;
            default: return image;
        }
        Bitmap oriented = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), transform, true);
        if (oriented != image) image.recycle();
        return oriented;
    }

    JSONObject attachment(String id, JSONObject result) throws JSONException {
        return new JSONObject().put("serverId", id).put("name", name).put("isImage", image)
                .put("mimeType", mime).put("size", originalSize >= 0 ? originalSize : result.optLong("size_bytes", 0));
    }
}
