package io.tokenseek.app;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Presents the action the user just opened on the official API key page. */
final class OfficialKeyForm {
    private static final String PAGE = PlatformNavigation.BASE + PlatformNavigation.KEYS;
    private static final String API = "window.__tokenSeekKeyFormV1";
    private static final String ON_PAGE = "window === window.top && location.href === " + JSONObject.quote(PAGE);
    private final Context context;
    private final String script;
    private Attempt active;

    private static final class Attempt {
        final WebView view;
        final String command;
        final String name;
        final String identifier;
        final BooleanSupplier current;
        final Consumer<Boolean> complete;
        String ticket;
        AlertDialog dialog;
        Runnable pending;

        Attempt(WebView view, String command, String name, String identifier, BooleanSupplier current, Consumer<Boolean> complete) {
            this.view = view;
            this.command = command;
            this.name = name;
            this.identifier = identifier;
            this.current = current;
            this.complete = complete;
        }
    }

    OfficialKeyForm(Context context) {
        this.context = context;
        script = LocalSite.bundledScript(context, R.raw.platform_key_form);
    }

    /** complete(true) keeps the website in front. An external cancel invalidates the request silently. */
    void begin(WebView view, String command, String name, String identifier, BooleanSupplier isCurrent, Consumer<Boolean> complete) {
        cancel();
        if (script.isEmpty() || view == null || !PAGE.equals(view.getUrl()) || !isCurrent.getAsBoolean()
                || !("create".equals(command) || "rename".equals(command) || "revoke".equals(command))) {
            complete.accept(true);
            return;
        }
        active = new Attempt(view, command, name, identifier, isCurrent, complete);
        probe(active, 0);
    }

    private boolean current(Attempt request) {
        return active == request && PAGE.equals(request.view.getUrl()) && request.current.getAsBoolean();
    }

    private void probe(Attempt request, int attempts) {
        if (!current(request)) return;
        String expression = "(() => { if (!(" + ON_PAGE + ")) return null; if (!" + API + ") {" + script + "\n}\nreturn " + API + "?.probe("
                + JSONObject.quote(request.command) + "," + JSONObject.quote(request.name) + ","
                + JSONObject.quote(request.identifier) + ") ?? null; })()";
        try {
            request.view.evaluateJavascript(expression, result -> {
                String token = result != null && result.matches("\"[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}\"") ? result : null;
                if (!current(request)) {
                    if (token != null) discard(request.view, token);
                    return;
                }
                if (token != null) {
                    request.ticket = token;
                    present(request);
                } else if (attempts < 7) {
                    later(request, () -> probe(request, attempts + 1), 150);
                } else {
                    finish(request, true);
                }
            });
        } catch (RuntimeException unavailable) {
            finish(request, true);
        }
    }

    private void later(Attempt request, Runnable next, long delay) {
        request.pending = next;
        request.view.postDelayed(next, delay);
    }

    private void present(Attempt request) {
        boolean creating = "create".equals(request.command);
        boolean revoking = "revoke".equals(request.command);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(creating ? R.string.key_form_create : revoking ? R.string.key_form_revoke : R.string.key_form_rename);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * context.getResources().getDisplayMetrics().density);
        content.setPadding(inset, inset / 2, inset, 0);
        if (!creating) {
            TextView target = new TextView(context);
            target.setText(context.getString(R.string.key_form_target, request.name, request.identifier));
            content.addView(target);
        }
        EditText name = revoking ? null : new EditText(context);
        if (name != null) {
            name.setSingleLine(true);
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            name.setHint(R.string.key_form_name);
            name.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            name.setSaveEnabled(false);
            name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(96)});
            if (!creating) {
                name.setText(request.name);
                name.selectAll();
            }
            content.addView(name);
        } else {
            TextView consequence = new TextView(context);
            consequence.setText(R.string.key_form_revoke_effect);
            content.addView(consequence);
        }
        builder.setView(content);
        builder.setNegativeButton(android.R.string.cancel, (d, w) -> finish(request, false));
        builder.setNeutralButton(R.string.key_form_website, (d, w) -> finish(request, true));
        builder.setPositiveButton(creating ? R.string.key_form_create_action : revoking ? R.string.key_form_revoke_action
                : R.string.key_form_save, null);
        AlertDialog dialog = builder.create();
        request.dialog = dialog;
        dialog.setOnCancelListener(d -> finish(request, false));
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!current(request)) { cancel(); return; }
            String value = name == null ? "" : name.getText().toString().trim();
            if (name != null && (value.isEmpty() || value.equals(request.name) || value.matches("(?is).*sk-[a-z0-9_-]{12,}.*"))) {
                name.setError(context.getText(R.string.key_form_name_required));
                return;
            }
            // Only this explicit confirmation can stage the form. Dismiss before scheduling the single submit.
            v.setEnabled(false);
            dialog.dismiss();
            call(request, "stage(" + request.ticket + "," + JSONObject.quote(value) + ")", staged -> {
                if (!staged) { finish(request, true); return; }
                later(request, () -> submit(request), 120);
            });
        }));
        dialog.show();
    }

    private void submit(Attempt request) {
        call(request, "submit(" + request.ticket + ")", sent -> {
            // Any one-time credential stays in the website. Never examine the create result.
            if (!sent || "create".equals(request.command)) finish(request, true);
            else waitForResult(request, 0);
        });
    }

    private void waitForResult(Attempt request, int attempt) {
        later(request, () -> call(request, "completed(" + request.ticket + ")", confirmed -> {
            if (confirmed || attempt >= 6) finish(request, !confirmed);
            else waitForResult(request, attempt + 1);
        }), 180);
    }

    private void call(Attempt request, String method, Consumer<Boolean> callback) {
        if (!current(request)) return;
        try {
            request.view.evaluateJavascript("(" + ON_PAGE + ") && " + API + "?." + method + " === true", result -> {
                if (current(request)) callback.accept("true".equals(result));
            });
        } catch (RuntimeException unavailable) {
            finish(request, true);
        }
    }

    private void finish(Attempt request, boolean showWebsite) {
        if (active != request) return;
        boolean deliver = current(request);
        cancel();
        if (deliver) request.complete.accept(showWebsite);
    }

    private static void discard(WebView view, String quotedTicket) {
        if (!PAGE.equals(view.getUrl())) return;
        try {
            view.evaluateJavascript("(" + ON_PAGE + ") && " + API + "?.cancel(" + quotedTicket + ")", null);
        } catch (RuntimeException detached) {
            // A navigation or destruction also drops the document-local ticket.
        }
    }

    void cancel() {
        Attempt request = active;
        active = null;
        if (request == null) return;
        if (request.pending != null) request.view.removeCallbacks(request.pending);
        if (request.dialog != null) request.dialog.dismiss();
        if (request.ticket != null) discard(request.view, request.ticket);
    }
}
