package io.tokenseek.app;

import android.app.AlertDialog;
import android.content.Context;
import android.webkit.WebView;
import android.widget.Toast;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Imports a single freshly issued key after the user chooses to save it in their device account. */
final class OfficialIssuedKey {
    private static final String PAGE = PlatformNavigation.BASE + PlatformNavigation.KEYS;
    private final Context context;
    private final String workspaceId;
    private final String script;
    private WebView source;
    private String ticket;
    private String label;
    private BooleanSupplier current;
    private Consumer<Boolean> complete;
    private Runnable pending;
    private AlertDialog dialog;
    private long generation;

    OfficialIssuedKey(Context context, String workspaceId) {
        this.context = context;
        this.workspaceId = workspaceId;
        script = LocalSite.bundledScript(context, R.raw.platform_issued_key);
    }

    void begin(WebView view, String quotedTicket, String chosenName, BooleanSupplier isCurrent, Consumer<Boolean> finish) {
        cancel();
        if (!OfficialPlatformActivity.isValidWorkspaceId(workspaceId) || script.isEmpty() || view == null
                || quotedTicket == null || !quotedTicket.matches("\"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\"")) {
            finish.accept(true);
            return;
        }
        source = view;
        ticket = quotedTicket;
        label = chosenName == null ? "" : chosenName;
        if (label.length() > 64) {
            int end = Character.isHighSurrogate(label.charAt(63)) ? 63 : 64;
            label = label.substring(0, end);
        }
        current = isCurrent;
        complete = finish;
        probe(generation, 0);
    }

    private boolean active(long token) {
        return generation == token && source != null && PAGE.equals(source.getUrl()) && current.getAsBoolean();
    }

    private void evaluate(long token, boolean save, Consumer<String> callback) {
        if (!active(token)) return;
        try {
            source.evaluateJavascript("(" + script + ")(" + ticket + "," + save + ")", value -> {
                if (active(token)) callback.accept(value);
            });
        } catch (RuntimeException detached) {
            finish(true);
        }
    }

    private void probe(long token, int attempt) {
        evaluate(token, false, value -> {
            if ("true".equals(value)) present(token);
            else if (attempt >= 20) finish(true);
            else {
                pending = () -> probe(token, attempt + 1);
                source.postDelayed(pending, 250);
            }
        });
    }

    private void present(long token) {
        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.issued_title)
                .setMessage(R.string.issued_explanation)
                .setPositiveButton(R.string.issued_save, null)
                .setNegativeButton(R.string.issued_review, (d, w) -> finish(true))
                .setOnCancelListener(d -> finish(true))
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (!active(token)) return;
            button.setEnabled(false);
            evaluate(token, true, raw -> {
                // evaluateJavascript JSON-quotes a string. Only the exact key alphabet is accepted.
                boolean saved = false;
                if (raw != null && raw.matches("\"sk-[a-zA-Z0-9_-]{16,256}\"")) {
                    String key = raw.substring(1, raw.length() - 1);
                    saved = !new KeyVault(context).manageProfile(workspaceId, "add", "", label, key).isEmpty();
                }
                Toast.makeText(context, saved ? R.string.issued_saved : R.string.issued_save_failed, Toast.LENGTH_SHORT).show();
                finish(!saved);
            });
        }));
        dialog.show();
    }

    private void finish(boolean website) {
        Consumer<Boolean> callback = complete;
        cancel();
        if (callback != null) callback.accept(website);
    }

    void cancel() {
        generation++;
        if (source != null && pending != null) source.removeCallbacks(pending);
        if (dialog != null) dialog.dismiss();
        source = null;
        ticket = null;
        label = null;
        current = null;
        complete = null;
        pending = null;
        dialog = null;
    }
}
