package io.tokenseek.app;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/** Native presentation of the signed-in platform's currently rendered, masked API-key list. */
final class OfficialKeyDirectory {
    private static final String KEY_PAGE = "window === window.top && location.origin === 'https://platform.deepseek.com'"
            + " && location.pathname === '/api_keys'";
    private static final java.util.regex.Pattern MASKED = java.util.regex.Pattern.compile(
            "(?i)(?:sk-[a-z0-9_-]{0,8})?(?:\\*{3,}|[•●·]{3,}|\\.{3,}|…+)[a-z0-9_-]{0,8}");
    private final FrameLayout windows;
    private final Button toggle;
    private final Context context;
    private final String script;
    private final Runnable poll = this::refresh;
    private WebView source;
    private ScrollView list;
    private String lastSnapshot;
    private boolean paused;
    private boolean officialView;
    private boolean manualAttempt;
    private boolean explicitList;
    private long revision;
    private int retries;

    OfficialKeyDirectory(FrameLayout windows, Button toggle) {
        this.windows = windows;
        this.toggle = toggle;
        context = windows.getContext();
        script = LocalSite.bundledScript(context, R.raw.platform_keys);
        toggle.setOnClickListener(v -> {
            if (list != null) {
                revealSource();
            } else {
                officialView = false;
                manualAttempt = true;
                explicitList = true;
                retries = 0;
                refresh();
            }
        });
    }

    void document(WebView view) {
        if (view == source) return;
        windows.removeCallbacks(poll);
        revision++;
        clearList();
        source = view;
        officialView = false;
        manualAttempt = false;
        explicitList = false;
        retries = 0;
        toggle.setVisibility(view != null && !script.isEmpty() ? View.VISIBLE : View.GONE);
        if (view != null) refresh();
    }

    void setPaused(boolean value) {
        paused = value;
        windows.removeCallbacks(poll);
        revision++;
        if (!paused && source != null && !officialView) refresh();
    }

    private boolean current(WebView view, long generation) {
        return !paused && revision == generation && source == view && PlatformNavigation.isKeyPage(view.getUrl());
    }

    private void refresh() {
        WebView view = source;
        if (view == null || paused || officialView || script.isEmpty() || !PlatformNavigation.isKeyPage(view.getUrl())) return;
        windows.removeCallbacks(poll);
        long generation = ++revision;
        String expression = "(() => { if (!(" + KEY_PAGE + ")) return null; if (!window.__tokenSeekKeyDirectoryV1) {"
                + script + "\n} return window.__tokenSeekKeyDirectoryV1?.snapshot(" + explicitList + ") ?? null; })()";
        view.evaluateJavascript(expression, raw -> {
            if (!current(view, generation)) return;
            try {
                if (raw == null || raw.length() > 250000) throw new JSONException("size");
                if (!raw.equals(lastSnapshot)) {
                    render(new JSONObject(raw));
                    lastSnapshot = raw;
                }
                retries = 0;
                manualAttempt = false;
                windows.postDelayed(poll, 2500);
            } catch (JSONException unrecognized) {
                clearList();
                explicitList = false;
                if (manualAttempt) {
                    manualAttempt = false;
                    Toast.makeText(context, R.string.key_view_unavailable, Toast.LENGTH_SHORT).show();
                }
                // An SPA may render its table after the document has finished. During that time
                // keep the underlying site usable, then let the user retry via the toolbar.
                if (++retries <= 8) windows.postDelayed(poll, 900);
            }
        });
    }

    private void revealSource() {
        officialView = true;
        explicitList = false;
        windows.removeCallbacks(poll);
        revision++;
        clearList();
        if (source != null) source.requestFocus();
    }

    private void act(String command) {
        WebView view = source;
        if (view == null || list == null || !PlatformNavigation.isKeyPage(view.getUrl())) return;
        boolean paging = command.equals("next") || command.equals("previous");
        boolean wasExplicit = explicitList;
        revealSource();
        long generation = revision;
        view.evaluateJavascript("(" + KEY_PAGE + ") && window.__tokenSeekKeyDirectoryV1?.act("
                + JSONObject.quote(command) + "," + wasExplicit + ") === true", result -> {
            if (!current(view, generation)) return;
            if (!"true".equals(result)) {
                Toast.makeText(context, R.string.key_view_unavailable, Toast.LENGTH_SHORT).show();
            } else if (paging) {
                officialView = false;
                explicitList = wasExplicit;
                retries = 0;
                windows.postDelayed(poll, 250);
            }
        });
    }

    private void clearList() {
        if (list != null) {
            windows.removeView(list);
            list = null;
        }
        if (source != null) source.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        lastSnapshot = null;
        toggle.setText(R.string.key_view_native);
    }

    private void render(JSONObject data) throws JSONException {
        JSONArray rows = data.getJSONArray("rows");
        if (rows.length() > 500) throw new JSONException("oversized list");
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(24));
        if (data.getBoolean("create")) column.addView(action(R.string.key_view_create, "create"), -1, -2);
        TextView count = text(context.getString(R.string.key_view_count, rows.length()), 14, R.color.platform_muted);
        count.setPadding(0, dp(14), 0, dp(6));
        if (Build.VERSION.SDK_INT >= 28) count.setAccessibilityHeading(true);
        column.addView(count);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String id = row.getString("id");
            if (!id.matches("[a-z0-9-]{1,40}")) throw new JSONException("row");
            String name = row.getString("name");
            String masked = row.getString("masked");
            String created = row.optString("created");
            String lastUsed = row.optString("lastUsed");
            if (name.length() > 256 || masked.length() > 128 || created.length() > 80 || lastUsed.length() > 80
                    || !MASKED.matcher(masked).matches()
                    || (name + " " + masked + " " + created + " " + lastUsed).matches("(?si).*sk-[a-z0-9_-]{16,}.*")) {
                throw new JSONException("unrecognized display fields");
            }
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            TypedArray style = context.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
            item.setBackgroundResource(style.getResourceId(0, 0));
            style.recycle();
            item.setFocusable(true);
            item.setOnClickListener(v -> act("show:" + id));
            TextView title = text(name, 17, R.color.platform_ink);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            item.addView(title);
            TextView key = text(masked, 15, R.color.platform_ink);
            key.setTypeface(Typeface.MONOSPACE);
            item.addView(key);
            if (!created.isEmpty()) item.addView(text(context.getString(R.string.key_view_created, created), 13, R.color.platform_muted));
            if (!lastUsed.isEmpty()) item.addView(text(context.getString(R.string.key_view_last_used, lastUsed), 13, R.color.platform_muted));
            column.addView(item, -1, -2);
            View divider = new View(context);
            divider.setBackgroundColor(context.getColor(R.color.platform_hint_background));
            column.addView(divider, -1, dp(1));
        }
        LinearLayout pagination = new LinearLayout(context);
        pagination.setGravity(Gravity.END);
        if (data.getBoolean("previous")) pagination.addView(action(R.string.key_view_previous, "previous"));
        if (data.getBoolean("next")) pagination.addView(action(R.string.key_view_next, "next"));
        if (pagination.getChildCount() > 0) column.addView(pagination, -1, -2);
        if (rows.length() > 0) column.addView(text(context.getString(R.string.key_view_actions), 13, R.color.platform_muted));

        int position = list == null ? 0 : list.getScrollY();
        if (list == null) {
            list = new ScrollView(context);
            list.setFillViewport(true);
            list.setClickable(true);
            TypedArray colors = context.obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
            list.setBackgroundColor(colors.getColor(0, android.graphics.Color.WHITE));
            colors.recycle();
            windows.addView(list, new FrameLayout.LayoutParams(-1, -1));
            source.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        } else {
            list.removeAllViews();
        }
        list.addView(column);
        ScrollView updated = list;
        updated.post(() -> updated.scrollTo(0, position));
        toggle.setText(R.string.key_view_original);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(context.getColor(color));
        return view;
    }

    private Button action(int label, String command) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(context.getColor(R.color.platform_accent));
        button.setMinHeight(dp(48));
        button.setOnClickListener(v -> act(command));
        return button;
    }
}
