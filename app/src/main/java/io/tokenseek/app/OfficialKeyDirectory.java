package io.tokenseek.app;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns the native directory. The official window retains the authenticated account session. */
final class OfficialKeyDirectory {
    interface Host {
        void nativeChrome(boolean visible);
        void reloadDirectory();
        void leaveDirectory();
    }

    private enum Route { OTHER, KEYS, SIGN_IN, LOGIN_RETURN }
    private enum Screen { CLOSED, LOADING, LIST, UNAVAILABLE, SIGN_IN, ACTION, WEBSITE }

    private static final String KEY_PAGE = "window === window.top && location.origin === 'https://platform.deepseek.com'"
            + " && location.pathname === '/api_keys'";
    private final FrameLayout layer;
    private final Button returnButton;
    private final OfficialKeyPanel panel;
    private final OfficialKeyForm form;
    private final Host host;
    private final String adapter;
    private final Runnable poll = this::refresh;
    private final Runnable deadline = () -> timedOut();
    private Route route = Route.OTHER;
    private Screen screen = Screen.CLOSED;
    private WebView source;
    private boolean ready;
    private boolean paused;
    private boolean signInApproved;
    private long generation;
    private String lastSnapshot;
    private JSONArray rows;

    OfficialKeyDirectory(Context context, FrameLayout layer, Button returnButton, Host host) {
        this.layer = layer;
        this.returnButton = returnButton;
        this.host = host;
        adapter = LocalSite.bundledScript(context, R.raw.platform_keys);
        form = new OfficialKeyForm(context);
        panel = new OfficialKeyPanel(context, this::action);
        layer.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        layer.setClickable(true);
        layer.setVisibility(View.GONE);
        returnButton.setText(R.string.key_return_directory);
        returnButton.setOnClickListener(v -> resumeDirectory());
    }

    private void cancelWork() {
        generation++;
        layer.removeCallbacks(poll);
        layer.removeCallbacks(deadline);
        form.cancel();
    }

    private void forgetRows() {
        rows = null;
        lastSnapshot = null;
    }

    boolean nativeVisible() {
        return screen != Screen.CLOSED && screen != Screen.WEBSITE;
    }

    private void show(Screen next) {
        if (next == screen) return;
        screen = next;
        layer.setVisibility(nativeVisible() ? View.VISIBLE : View.GONE);
        returnButton.setVisibility(next == Screen.WEBSITE && route == Route.KEYS ? View.VISIBLE : View.GONE);
        host.nativeChrome(nativeVisible());
        switch (next) {
            case LOADING: panel.loading(); break;
            case SIGN_IN: panel.showSignInNeeded(); break;
            case UNAVAILABLE: panel.unavailable(); break;
            case ACTION: panel.awaitingConfirmation(); break;
            default: break;
        }
    }

    /** Called both before the first load and after a visible top-level document is verified. */
    void route(WebView view, String url, boolean root, boolean readyNow, boolean failed, boolean keyTask) {
        String path = PlatformNavigation.platformPath(url);
        Route next = Route.OTHER;
        if (root && PlatformNavigation.isKeyPage(url)) next = Route.KEYS;
        else if (root && keyTask) {
            if (PlatformNavigation.LOGIN.equals(path) || "/sign_up".equals(path)) next = Route.SIGN_IN;
            else if ("/".equals(path) && (route == Route.SIGN_IN || route == Route.LOGIN_RETURN)) next = Route.LOGIN_RETURN;
            else if (url == null) next = route;
        }

        if (next != route || source != view || (ready && !readyNow)) {
            boolean keepWebsite = next == route && source == view && screen == Screen.WEBSITE;
            cancelWork();
            forgetRows();
            source = view;
            route = next;
            ready = false;
            if (next == Route.KEYS) signInApproved = false;
            if (!keepWebsite) {
                if (next == Route.OTHER) show(Screen.CLOSED);
                else if (next == Route.SIGN_IN) show(signInApproved ? Screen.WEBSITE : Screen.SIGN_IN);
                else show(Screen.LOADING);
                if (screen == Screen.LOADING) startDeadline();
            }
        }
        if (next == Route.OTHER || next == Route.SIGN_IN) return;
        if (failed) {
            cancelWork();
            forgetRows();
            ready = false;
            if (screen != Screen.WEBSITE) show(Screen.UNAVAILABLE);
            return;
        }
        if (next != Route.KEYS || !readyNow || ready) return;
        ready = true;
        if (screen != Screen.WEBSITE && !paused) refresh();
    }

    private void startDeadline() {
        layer.removeCallbacks(deadline);
        if (!paused) layer.postDelayed(deadline, 9000);
    }

    private void timedOut() {
        if (screen == Screen.LOADING) {
            layer.removeCallbacks(poll);
            show(Screen.UNAVAILABLE);
        }
    }

    void setPaused(boolean value) {
        if (paused == value) return;
        paused = value;
        cancelWork();
        // An outstanding website action can have completed while the activity was backgrounded.
        // Never click it again or reload a one-time issuance result.
        if (!paused && (screen == Screen.LOADING || screen == Screen.LIST)) {
            if (screen == Screen.LOADING) startDeadline();
            if (ready) refresh();
        }
    }

    private boolean current(WebView view, long token) {
        return !paused && token == generation && view == source && ready && route == Route.KEYS
                && PlatformNavigation.isKeyPage(view.getUrl());
    }

    private void refresh() {
        WebView view = source;
        if (view == null || !ready || paused || route != Route.KEYS || screen == Screen.WEBSITE
                || screen == Screen.ACTION || !PlatformNavigation.isKeyPage(view.getUrl())) return;
        layer.removeCallbacks(poll);
        long token = ++generation;
        String expression = "(() => { if (!(" + KEY_PAGE + ")) return null; if (!window.__tokenSeekKeyDirectoryV1) {"
                + adapter + "\n} return window.__tokenSeekKeyDirectoryV1?.snapshot(true) ?? null; })()";
        view.evaluateJavascript(expression, raw -> {
            if (!current(view, token)) return;
            try {
                if (raw == null || raw.length() > 250000) throw new JSONException("Invalid directory size");
                if (!raw.equals(lastSnapshot)) {
                    JSONObject snapshot = new JSONObject(raw);
                    JSONArray entries = snapshot.getJSONArray("rows");
                    panel.render(snapshot);
                    rows = entries;
                    lastSnapshot = raw;
                }
                show(Screen.LIST);
                layer.removeCallbacks(deadline);
                layer.postDelayed(poll, 8000);
            } catch (JSONException unknownLayout) {
                forgetRows();
                if (screen == Screen.LIST) {
                    show(Screen.LOADING);
                    startDeadline();
                }
                if (screen == Screen.LOADING) layer.postDelayed(poll, 600);
            }
        });
    }

    private void resumeDirectory() {
        if (route != Route.KEYS) return;
        cancelWork();
        forgetRows();
        show(Screen.LOADING);
        startDeadline();
        if (ready) refresh();
    }

    private void action(String command) {
        if ("back".equals(command)) { if (!back()) host.leaveDirectory(); return; }
        if ("official".equals(command)) {
            cancelWork();
            forgetRows();
            if (route == Route.SIGN_IN) signInApproved = true;
            show(Screen.WEBSITE);
            if (source != null) source.requestFocus();
            return;
        }
        if ("retry".equals(command)) {
            cancelWork();
            forgetRows();
            ready = false;
            show(Screen.LOADING);
            startDeadline();
            host.reloadDirectory();
            return;
        }
        if (screen != Screen.LIST || source == null || paused || !ready) return;
        int separator = command.indexOf(':');
        String operation = separator < 0 ? command : command.substring(0, separator);
        JSONObject selected = null;
        if (separator >= 0) {
            String id = command.substring(separator + 1);
            if (!(operation.equals("rename") || operation.equals("revoke") || operation.equals("show"))) return;
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject entry = rows.optJSONObject(i);
                if (entry != null && id.equals(entry.optString("id"))) { selected = entry; break; }
            }
            if (selected == null) return;
        } else if (!(operation.equals("create") || operation.equals("usage") || operation.equals("next")
                || operation.equals("previous"))) return;

        String name = selected == null ? "" : selected.optString("name");
        String identifier = selected == null ? "" : selected.optString("trackingId");
        String identity = identifier.isEmpty() && selected != null ? selected.optString("masked") : identifier;
        cancelWork();
        WebView view = source;
        long token = generation;
        show(Screen.ACTION);
        String expression = "(" + KEY_PAGE + ") && window.__tokenSeekKeyDirectoryV1?.dispatch("
                + JSONObject.quote(command) + ",true)";
        view.evaluateJavascript(expression, result -> {
            if (!current(view, token)) return;
            switch (result == null ? "" : result) {
                case "\"form\"":
                    form.begin(view, operation, name, identity, () -> current(view, token), website -> {
                        if (!current(view, token)) return;
                        if (website) action("official");
                        else action("retry");
                    });
                    break;
                case "\"source\"": action("official"); break;
                default: resumeDirectory(); break; // Page advanced, or a row changed before the tap.
            }
        });
    }

    /** Consume only a local subpage. The native directory's own Back leaves the platform task. */
    boolean back() {
        if (nativeVisible() && panel.handleBack()) return true;
        if (screen == Screen.WEBSITE && route == Route.KEYS) {
            resumeDirectory();
            return true;
        }
        return false;
    }

    void destroy() {
        cancelWork();
        forgetRows();
        source = null;
    }
}
