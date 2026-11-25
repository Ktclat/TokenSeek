package io.tokenseek.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Only a small square JPEG without original photo metadata is stored. */
final class NativeProfilePhoto {
    private static final String PREFIX = "data:image/jpeg;base64,";
    private static final int SIZE = 160;
    private static final int MAX_SOURCE = 16 * 1024 * 1024;

    static boolean valid(String uri) {
        return uri != null && uri.length() <= 48 * 1024 && uri.startsWith(PREFIX + "/9j/")
                && uri.substring(PREFIX.length()).matches("[A-Za-z0-9+/]+={0,2}");
    }

    static Bitmap decode(String uri) {
        if (!valid(uri)) return null;
        try {
            byte[] bytes = Base64.decode(uri.substring(PREFIX.length()), Base64.DEFAULT);
            BitmapFactory.Options check = new BitmapFactory.Options();
            check.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, check);
            if (check.outWidth != SIZE || check.outHeight != SIZE) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    static String read(ContentResolver resolver, Uri uri) throws IOException {
        if (!"content".equals(uri.getScheme())) throw new IOException("Expected selected photo");
        byte[] bytes;
        try (InputStream input = resolver.openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("No image stream");
            byte[] buffer = new byte[16 * 1024];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (out.size() + size > MAX_SOURCE) throw new IOException("Photo too large");
                out.write(buffer, 0, size);
            }
            bytes = out.toByteArray();
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth < 1 || options.outHeight < 1 || (long) options.outWidth * options.outHeight > 64_000_000
                || !("image/jpeg".equals(options.outMimeType) || "image/png".equals(options.outMimeType)
                || "image/webp".equals(options.outMimeType))) throw new IOException("Unsupported image");
        options.inJustDecodeBounds = false;
        int sample = 1;
        while (Math.max(options.outHeight, options.outWidth) / sample > 1024) sample *= 2;
        options.inSampleSize = sample;
        Bitmap source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (source == null) throw new IOException("Invalid image");
        Bitmap square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(square);
        canvas.drawColor(Color.WHITE);
        int edge = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - edge) / 2, y = (source.getHeight() - edge) / 2;
        canvas.drawBitmap(source, new Rect(x, y, x + edge, y + edge), new Rect(0, 0, SIZE, SIZE), new Paint(Paint.FILTER_BITMAP_FLAG));
        source.recycle();
        // EXIF orientation is applied to the cropped square before discarding the source metadata.
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes));
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, orientation);
        } catch (IOException ignored) { }
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(270); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(270); break;
            default: break;
        }
        if (!transform.isIdentity()) {
            Bitmap rotated = Bitmap.createBitmap(square, 0, 0, SIZE, SIZE, transform, true);
            square.recycle();
            square = rotated;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok = square.compress(Bitmap.CompressFormat.JPEG, 80, out);
        square.recycle();
        if (!ok || out.size() > 36_800) throw new IOException("Cannot save thumbnail");
        return PREFIX + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private NativeProfilePhoto() {}
}
