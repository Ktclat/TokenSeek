package io.tokenseek.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import java.io.IOException;
import java.io.OutputStream;

/** The selected URI and open stream belong to a single picker result. */
final class DocumentExport {
    static final int PICK_DESTINATION = 41;
    private final MainActivity activity;
    private boolean busy;
    private Uri destination;
    private OutputStream stream;

    DocumentExport(MainActivity activity) {
        this.activity = activity;
    }

    synchronized boolean choose(String name, String mime) {
        if (busy || activity.isDestroyed() || name == null || name.length() > 255) return false;
        String safeName = name.replace('/', '_').replace('\\', '_');
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime != null && mime.matches("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")
                        ? mime : "application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, safeName);
        busy = true;
        activity.runOnUiThread(() -> {
            try {
                activity.startActivityForResult(intent, PICK_DESTINATION);
            } catch (ActivityNotFoundException unavailable) {
                close();
                activity.pickerResult("error");
            }
        });
        return true;
    }

    synchronized void result(int status, Intent result) {
        if (!busy) return;
        if (status == Activity.RESULT_OK && result != null && result.getData() != null
                && "content".equals(result.getData().getScheme())) {
            destination = result.getData();
            activity.pickerResult("selected");
        } else {
            close();
            activity.pickerResult(status == Activity.RESULT_CANCELED ? "cancelled" : "error");
        }
    }

    synchronized boolean append(String chunk) {
        if (destination == null || chunk == null || chunk.length() > 65536) return false;
        try {
            if (stream == null) stream = activity.getContentResolver().openOutputStream(destination, "wt");
            if (stream == null) return false;
            stream.write(Base64.decode(chunk, Base64.NO_WRAP));
            return true;
        } catch (IOException | RuntimeException failure) {
            close();
            return false;
        }
    }

    synchronized boolean close() {
        boolean success = true;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException failure) {
                success = false;
            }
        }
        stream = null;
        destination = null;
        busy = false;
        return success;
    }
}
