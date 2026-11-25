package io.tokenseek.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only presentation of one official directory page. The controller performs all navigation. */
final class OfficialKeyPanel extends LinearLayout {
    interface Listener { void action(String command); }

    private static final int FILL = LayoutParams.MATCH_PARENT;
    private static final int WRAP = LayoutParams.WRAP_CONTENT;
    private static final Pattern MASK = Pattern.compile(
            "(?i)(?:sk-[a-z0-9_-]{0,8})?(?:\\*{3,}|[•●·]{3,}|\\.{3,}|…+)[a-z0-9_-]{0,8}");
    private static final Pattern SECRET = Pattern.compile("(?i)sk-[a-z0-9_-]{16,}");
    private static final String UUID_TEMPLATE = "00000000-0000-0000-0000-000000000000";
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final Pattern SHORT_TRACKING = Pattern.compile("(?i)([a-f0-9-]{4,32}) *(?:\\.{3}|…) *([a-f0-9-]{1,24})");
    private static final Pattern ROW_ID = Pattern.compile("[a-z0-9-]{1,40}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069]");

    private final Listener listener;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final Button usage;
    private final int ink, muted, accent, surface, divider;
    private String expandedId;
    private Runnable closeExpanded;
    private AlertDialog confirmation;
    private boolean resetScroll = true;
    private Runnable restoreScroll;

    OfficialKeyPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        ink = context.getColor(R.color.platform_ink);
        muted = context.getColor(R.color.platform_muted);
        accent = context.getColor(R.color.platform_accent);
        surface = dark ? 0xff222328 : Color.WHITE;
        divider = dark ? 0xff393a41 : 0xffe7e9ee;
        setBackgroundColor(dark ? 0xff17181c : 0xfff5f6f8);
        setOrientation(VERTICAL);
        setClickable(true);
        if (Build.VERSION.SDK_INT >= 28) setAccessibilityPaneTitle(str(R.string.key_panel_title));

        FrameLayout bar = new FrameLayout(context);
        bar.setMinimumHeight(dp(56));
        bar.setBackgroundColor(surface);
        addView(bar, FILL, WRAP);
        TextView title = label(str(R.string.key_panel_title), 18, ink);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(12));
        heading(title);
        FrameLayout.LayoutParams titleBox = new FrameLayout.LayoutParams(FILL, WRAP, Gravity.CENTER);
        titleBox.setMargins(dp(68), 0, dp(68), 0);
        bar.addView(title, titleBox);
        ImageButton back = new ImageButton(context);
        back.setImageResource(R.drawable.platform_back);
        back.setContentDescription(str(R.string.platform_back));
        back.setBackground(ripple(Color.TRANSPARENT, 24));
        back.setOnClickListener(v -> { if (!handleBack()) listener.action("back"); });
        bar.addView(back, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START | Gravity.CENTER_VERTICAL));
        usage = button(R.string.key_panel_usage, false, () -> listener.action("usage"));
        usage.setVisibility(GONE);
        bar.addView(usage, new FrameLayout.LayoutParams(dp(68), WRAP, Gravity.END | Gravity.CENTER_VERTICAL));
        addLine(this);

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(FILL, 0, 1));
        FrameLayout center = new FrameLayout(context);
        scroll.addView(center);
        content = column();
        content.setPadding(dp(16), dp(12), dp(16), dp(28));
        int width = getResources().getConfiguration().screenWidthDp > 620 ? dp(620) : FILL;
        center.addView(content, new FrameLayout.LayoutParams(width, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    void loading() {
        LinearLayout card = stateCard(R.string.key_panel_loading, 0);
        ProgressBar progress = new ProgressBar(getContext());
        progress.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams box = new LayoutParams(dp(28), dp(28));
        box.topMargin = dp(14);
        card.addView(progress, box);
    }

    void unavailable() {
        LinearLayout card = stateCard(R.string.key_panel_unavailable, 0);
        addPrimary(card, R.string.key_panel_retry, "retry");
        card.addView(button(R.string.key_panel_official, false, () -> listener.action("official")), FILL, WRAP);
    }

    void showSignInNeeded() {
        LinearLayout card = stateCard(R.string.key_panel_login_title, R.string.key_panel_login_detail);
        addPrimary(card, R.string.key_panel_login, "official");
    }

    void awaitingConfirmation() {
        LinearLayout card = stateCard(R.string.key_panel_pending_title, 0);
        addPrimary(card, R.string.key_panel_official, "official");
    }

    void reviewOnPlatform() {
        LinearLayout card = stateCard(R.string.key_panel_review, 0);
        addPrimary(card, R.string.key_panel_official, "official");
    }

    private LinearLayout stateCard(int heading, int explanation) {
        closeTransient();
        resetScroll = true;
        usage.setVisibility(GONE);
        content.removeAllViews();
        LinearLayout card = card();
        card.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(card, FILL, WRAP);
        TextView status = label(str(heading), 17, ink);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(status, FILL, WRAP);
        if (explanation != 0) addDetail(card, str(explanation), false);
        updateScroll(0);
        return card;
    }

    void render(JSONObject snapshot) throws JSONException {
        JSONArray rows = snapshot.getJSONArray("rows");
        if (rows.length() > 300) throw new JSONException("Too many key rows");
        Set<String> ids = new HashSet<>();
        String restoreKey = expandedId;
        closeTransient();
        boolean previous = snapshot.optBoolean("previous");
        boolean next = snapshot.optBoolean("next");
        LinearLayout header = new LinearLayout(getContext());
        header.setPadding(dp(4), 0, 0, 0);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams headerBox = new LayoutParams(FILL, WRAP);
        headerBox.bottomMargin = dp(6);
        if (rows.length() > 0) {
            int countText = previous || next ? R.string.key_view_page_count : R.string.key_view_count;
            TextView count = label(getContext().getString(countText, rows.length()), 14, muted);
            heading(count);
            header.addView(count, new LayoutParams(0, WRAP, 1));
        }
        boolean canCreate = snapshot.getBoolean("create");
        if (canCreate && rows.length() > 0) {
            Button create = button(R.string.key_panel_create, true, () -> listener.action("create"));
            header.addView(create, WRAP, WRAP);
        }
        LinearLayout card = card();
        if (rows.length() == 0) {
            TextView empty = label(str(R.string.key_panel_empty), 17, ink);
            empty.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(empty, FILL, WRAP);
            if (canCreate) addPrimary(card, R.string.key_panel_create, "create");
        }
        if (!canCreate && snapshot.optBoolean("verification")) {
            addDetail(card, str(R.string.key_panel_verify_detail), false);
            addPrimary(card, R.string.key_panel_verify, "official");
            if (rows.length() > 0) addLine(card);
        }
        if (rows.length() > 0) card.setPadding(dp(16), dp(4), dp(16), dp(4));
        for (int i = 0; i < rows.length(); i++) {
            if (i > 0) addLine(card);
            addRow(card, rows.getJSONObject(i), ids, restoreKey);
        }
        int position = resetScroll ? 0 : scroll.getScrollY();
        resetScroll = false;
        usage.setVisibility(snapshot.optBoolean("usage") ? VISIBLE : GONE);
        content.removeAllViews();
        if (header.getChildCount() > 0) content.addView(header, headerBox);
        content.addView(card, FILL, WRAP);
        if (previous || next) {
            LinearLayout pages = new LinearLayout(getContext());
            pages.setGravity(Gravity.END);
            if (previous) addPage(pages, R.string.key_view_previous, "previous");
            if (next) addPage(pages, R.string.key_view_next, "next");
            content.addView(pages, FILL, WRAP);
        }
        updateScroll(position);
    }

    private void updateScroll(int position) {
        if (restoreScroll != null) scroll.removeCallbacks(restoreScroll);
        if (position == 0) {
            restoreScroll = null;
            scroll.scrollTo(0, 0);
        } else {
            restoreScroll = () -> scroll.scrollTo(0, position);
            scroll.post(restoreScroll);
        }
    }

    private void addRow(LinearLayout card, JSONObject row, Set<String> ids, String restoreKey) throws JSONException {
        String id = row.getString("id");
        String name = field(row, "name", 256);
        String tracking = field(row, "trackingId", 52);
        String masked = field(row, "masked", 128);
        String created = field(row, "created", 80);
        String used = field(row, "lastUsed", 80);
        boolean managed = row.optBoolean("managed") && "DeepSeek Harness".equals(name);
        if (name.trim().isEmpty() || !ROW_ID.matcher(id).matches() || id.startsWith("sk-") || !ids.add(id)
                || (tracking.isEmpty() && masked.isEmpty() && !managed)
                || (!tracking.isEmpty() && !validTrackingId(tracking))
                || (!masked.isEmpty() && (!MASK.matcher(masked).matches() || masked.matches("[.…]+")))) {
            throw new JSONException("Invalid key display metadata");
        }
        boolean rename = row.optBoolean("rename");
        boolean revoke = row.optBoolean("revoke");
        LinearLayout item = column();
        card.addView(item, FILL, WRAP);
        LinearLayout lead = new LinearLayout(getContext());
        lead.setMinimumHeight(dp(62));
        lead.setPadding(0, dp(10), 0, dp(10));
        lead.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(lead, FILL, WRAP);
        LinearLayout summary = column();
        lead.addView(summary, new LayoutParams(0, WRAP, 1));
        TextView title = label(name, 16, ink);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        summary.addView(title, FILL, WRAP);
        String metadata = tracking.isEmpty() ? masked : getContext().getString(R.string.key_panel_tracking, tracking);
        if (managed) addDetail(summary, str(R.string.key_panel_harness), false);
        if (!metadata.isEmpty()) addDetail(summary, metadata, true);
        boolean hasDetails = !managed || (!tracking.isEmpty() && !masked.isEmpty()) || !created.isEmpty() || !used.isEmpty();
        if (!hasDetails) {
            title.setMaxLines(Integer.MAX_VALUE);
            return;
        }
        ImageView arrow = new ImageView(getContext());
        arrow.setImageResource(R.drawable.keys_expand);
        arrow.setColorFilter(muted);
        arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams arrowBox = new LayoutParams(dp(20), dp(20));
        arrowBox.setMarginStart(dp(8));
        lead.addView(arrow, arrowBox);
        lead.setFocusable(true);
        lead.setBackground(ripple(Color.TRANSPARENT, 8));
        String description = name + (metadata.isEmpty() ? "" : ", " + metadata)
                + (managed ? ", " + str(R.string.key_panel_harness) : "");
        lead.setContentDescription(getContext().getString(R.string.key_panel_expand, description));
        Runnable expand = () -> {
            collapseRow();
            LinearLayout details = column();
            details.setPadding(0, 0, 0, dp(10));
            if (!masked.isEmpty() && !tracking.isEmpty()) addDetail(details, masked, true);
            if (!created.isEmpty()) addDetail(details, getContext().getString(R.string.key_view_created, created), false);
            if (!used.isEmpty()) addDetail(details, getContext().getString(R.string.key_view_last_used, used), false);
            if (!managed) addActions(details, id, name, rename, revoke);
            item.addView(details, FILL, WRAP);
            title.setMaxLines(Integer.MAX_VALUE);
            arrow.setRotation(180);
            lead.setContentDescription(getContext().getString(R.string.key_panel_collapse, description));
            expandedId = id;
            closeExpanded = () -> {
                item.removeView(details);
                title.setMaxLines(2);
                arrow.setRotation(0);
                lead.setContentDescription(getContext().getString(R.string.key_panel_expand, description));
            };
        };
        lead.setOnClickListener(v -> {
            if (id.equals(expandedId)) collapseRow();
            else expand.run();
        });
        if (id.equals(restoreKey)) expand.run();
    }

    private void addActions(LinearLayout details, String id, String name, boolean rename, boolean revoke) {
        LinearLayout actions = new LinearLayout(getContext());
        if (rename) actions.addView(button(R.string.key_panel_rename, false, () -> listener.action("rename:" + id)),
                new LayoutParams(0, WRAP, 1));
        if (revoke) {
            Button delete = button(R.string.key_panel_revoke, false, () -> confirmRevoke(id, name));
            boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            delete.setTextColor(dark ? 0xffffa29b : 0xffa62d26);
            actions.addView(delete, new LayoutParams(0, WRAP, 1));
        }
        if (!rename || !revoke) actions.addView(button(R.string.key_panel_show, false, () -> listener.action("show:" + id)),
                new LayoutParams(0, WRAP, 1));
        LayoutParams box = new LayoutParams(FILL, WRAP);
        box.topMargin = dp(4);
        details.addView(actions, box);
    }

    private static boolean validTrackingId(String text) {
        if (UUID_TEXT.matcher(text).matches()) return true;
        Matcher parts = SHORT_TRACKING.matcher(text);
        if (!parts.matches()) return false;
        String prefix = parts.group(1);
        String suffix = parts.group(2);
        int end = UUID_TEMPLATE.length() - suffix.length();
        return prefix.length() < end
                && UUID_TEXT.matcher(prefix + UUID_TEMPLATE.substring(prefix.length(), end) + suffix).matches();
    }

    private static String field(JSONObject row, String key, int limit) throws JSONException {
        if (row.isNull(key)) return "";
        Object raw = row.get(key);
        if (!(raw instanceof String)) throw new JSONException("Invalid key metadata type");
        String value = (String) raw;
        if (value.length() > limit || SECRET.matcher(value).find() || CONTROL.matcher(value).find()) {
            throw new JSONException("Invalid key display text");
        }
        return value;
    }

    private void confirmRevoke(String id, String name) {
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(R.string.key_panel_revoke_title)
                .setMessage(getContext().getString(R.string.key_panel_revoke_message, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.key_panel_confirm, (ignored, which) -> listener.action("revoke:" + id))
                .create();
        dialog.setOnDismissListener(ignored -> { if (confirmation == dialog) confirmation = null; });
        confirmation = dialog;
        dialog.show();
    }

    boolean handleBack() {
        if (confirmation != null) { confirmation.dismiss(); confirmation = null; return true; }
        if (closeExpanded != null) { collapseRow(); return true; }
        return false;
    }

    private void collapseRow() {
        if (closeExpanded != null) closeExpanded.run();
        closeExpanded = null;
        expandedId = null;
    }

    private void closeTransient() {
        if (confirmation != null) confirmation.dismiss();
        confirmation = null;
        collapseRow();
    }

    @Override protected void onDetachedFromWindow() {
        closeTransient();
        super.onDetachedFromWindow();
    }

    private void addPage(LinearLayout pages, int label, String command) {
        pages.addView(button(label, false, () -> { resetScroll = true; listener.action(command); }), WRAP, WRAP);
    }

    private void addPrimary(LinearLayout card, int label, String command) {
        LayoutParams params = new LayoutParams(FILL, WRAP);
        params.topMargin = dp(16);
        card.addView(button(label, true, () -> listener.action(command)), params);
    }

    private Button button(int label, boolean primary, Runnable action) {
        Button b = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextSize(primary ? 16 : 14);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(primary ? 16 : 8), dp(8), dp(primary ? 16 : 8), dp(8));
        b.setTextColor(primary ? Color.WHITE : accent);
        b.setBackground(ripple(primary ? 0xff415ed7 : Color.TRANSPARENT, 28));
        if (action != null) b.setOnClickListener(v -> action.run());
        return b;
    }

    private void addDetail(LinearLayout parent, String value, boolean code) {
        TextView detail = label(value, 13, muted);
        if (code) {
            detail.setTypeface(Typeface.MONOSPACE);
            detail.setTextDirection(TEXT_DIRECTION_LTR);
        }
        detail.setPadding(0, dp(4), 0, 0);
        parent.addView(detail, FILL, WRAP);
    }

    private void addLine(LinearLayout parent) {
        View line = new View(getContext());
        line.setBackgroundColor(divider);
        line.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(line, FILL, dp(1));
    }

    private LinearLayout card() {
        LinearLayout box = column();
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(shape(surface, 18));
        return box;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        return column;
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x293d59c4), shape(color, radius), null);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        return result;
    }

    private TextView label(String value, int size, int color) {
        TextView result = new TextView(getContext());
        result.setTextSize(size);
        result.setTextColor(color);
        result.setText(value);
        return result;
    }

    private void heading(TextView v) {
        if (Build.VERSION.SDK_INT >= 28) v.setAccessibilityHeading(true);
    }

    private String str(int resource) { return getContext().getString(resource); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
