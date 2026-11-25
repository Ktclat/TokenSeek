package io.tokenseek.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Android views for the settings index, appearance, font size and response preferences. */
final class NativeSettings extends FrameLayout {
    interface Listener {
        void action(String page, String id);
    }
    private final Listener listener;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final LinearLayout bar;
    private final ImageButton back;
    private final TextView title;
    private String page = "";
    private boolean dark;
    private float scale = 1;

    NativeSettings(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClickable(true);
        LinearLayout column = column();
        addView(column, new LayoutParams(-1, -1));

        bar = new LinearLayout(getContext());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(54));
        column.addView(bar, -1, -2);
        back = new ImageButton(getContext());
        back.setImageResource(R.drawable.platform_back);
        back.setOnClickListener(v -> back());
        bar.addView(back, dp(54), dp(54));
        title = new TextView(getContext());
        title.setGravity(Gravity.CENTER);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading(title);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(new View(getContext()), dp(54), 1);

        scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout center = new FrameLayout(getContext());
        scroll.addView(center);
        content = column();
        int width = getResources().getConfiguration().screenWidthDp > 600 ? dp(600) : -1;
        center.addView(content, new LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        content.setPadding(dp(16), dp(12), dp(16), dp(28));
    }

    void back() { listener.action(page, "back"); }

    void render(JSONObject screen) {
        boolean newPage = !page.equals(screen.optString("page"));
        int position = newPage ? 0 : scroll.getScrollY();
        page = screen.optString("page");
        dark = screen.optBoolean("dark");
        scale = Math.max(90, Math.min(130, screen.optInt("scale", 100))) / 100f;
        int foreground = ink();
        setBackgroundColor(dark ? 0xff16171a : 0xfff6f7f9);
        bar.setBackgroundColor(surface());
        title.setText(screen.optString("title"));
        title.setTextColor(foreground);
        title.setTextSize(18 * scale);
        back.setColorFilter(foreground);
        back.setBackground(clickable(null));
        back.setContentDescription(screen.optString("back"));
        content.removeAllViews();
        if (screen.has("sample")) {
            TextView sample = label(screen.optString("sample"), 16, foreground);
            sample.setBackground(card());
            sample.setPadding(dp(18), dp(24), dp(18), dp(24));
            content.addView(sample, -1, -2);
        }
        JSONArray groups = screen.optJSONArray("groups");
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null) addGroup(group);
        }
        scroll.post(() -> scroll.scrollTo(0, position));
        if (newPage && Build.VERSION.SDK_INT >= 28) setAccessibilityPaneTitle(screen.optString("title"));
    }

    private void addGroup(JSONObject group) {
        if (group.has("label")) {
            TextView caption = label(group.optString("label"), 13, muted());
            caption.setPadding(dp(5), dp(12), dp(5), dp(7));
            heading(caption);
            content.addView(caption, -1, -2);
        }
        LinearLayout groupView = column();
        groupView.setBackground(card());
        groupView.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        content.addView(groupView, params);
        JSONArray items = group.optJSONArray("items");
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (i != 0) {
                View line = new View(getContext());
                line.setBackgroundColor(dark ? 0xff32343a : 0xffedeef1);
                groupView.addView(line, -1, dp(1));
            }
            groupView.addView(item.has("selected") ? choice(item) : link(item), -1, -2);
        }
    }

    private View choice(JSONObject item) {
        RadioButton row = new RadioButton(getContext());
        row.setMinHeight(dp(54));
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setTextSize(16 * scale);
        row.setTextColor(ink());
        row.setText(item.optString("label"));
        row.setButtonTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{accent(), muted()}));
        row.setChecked(item.optBoolean("selected"));
        row.setBackground(clickable(null));
        row.setOnClickListener(v -> listener.action(page, item.optString("id")));
        return row;
    }

    private View link(JSONObject item) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        row.setFocusable(true);
        row.setBackground(clickable(null));
        String name = item.optString("label");
        String value = item.optString("value");
        row.setContentDescription(value.isEmpty() ? name : name + ", " + value);
        row.setOnClickListener(v -> listener.action(page, item.optString("id")));
        row.addView(label(name, 16, ink()), new LinearLayout.LayoutParams(0, -2, 1));
        if (!value.isEmpty()) row.addView(label(value, 14, muted()));
        TextView arrow = label("›", 20, muted());
        arrow.setPadding(dp(9), 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        return row;
    }

    private TextView label(String text, int sp, int color) {
        TextView v = new TextView(getContext());
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale);
        v.setTextColor(color);
        return v;
    }

    private void heading(View view) {
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }
    private LinearLayout column() {
        LinearLayout result = new LinearLayout(getContext());
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }
    private int surface() { return dark ? 0xff202125 : Color.WHITE; }
    private int ink() { return dark ? 0xfff2f3f5 : 0xff202329; }
    private int muted() { return dark ? 0xffa9adb7 : 0xff646976; }
    private int accent() { return dark ? 0xff9faeff : 0xff3858c9; }
    private GradientDrawable card() {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(surface());
        return shape;
    }
    private RippleDrawable clickable(GradientDrawable underlay) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x30ffffff : 0x18000000), underlay, null);
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
