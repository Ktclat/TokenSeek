package io.tokenseek.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Shows balances returned by the official API and prepares a local CNY amount for checkout. */
final class NativeFunding extends FrameLayout {
    interface Listener { void action(String id, String amount); }

    static final class Amount {
        final String value;
        final String error;
        Amount(String value, String error) { this.value = value; this.error = error; }
    }

    static Amount parseAmount(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new Amount("", "");
        if (raw.length() > 64) return new Amount("", "overflow");
        String value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        if (!value.matches("(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})")) return new Amount("", "invalid");
        int dot = value.indexOf('.');
        String whole = (dot < 0 ? value : value.substring(0, dot)).replaceFirst("^0+", "");
        if (whole.length() > 12) return new Amount("", "overflow");
        String cents = (dot < 0 ? "" : value.substring(dot + 1)) + "00";
        String canonical = (whole.isEmpty() ? "0" : whole) + "." + cents.substring(0, 2);
        return "0.00".equals(canonical) ? new Amount("", "invalid") : new Amount(canonical, "");
    }

    private final Listener listener;
    private final List<Button> presets = new ArrayList<>();
    private JSONObject labels = new JSONObject();
    private String appearance = "";
    private boolean dark;
    private float scale;
    private LinearLayout balance;
    private LinearLayout body;
    private ScrollView scroll;
    private EditText input;
    private TextView error;
    private TextView hint;
    private Button checkout;
    private Button copy;
    private Button chat;

    NativeFunding(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClickable(true);
        setFocusableInTouchMode(true);
    }

    void back() { listener.action("back", ""); }

    void render(JSONObject screen) {
        JSONObject nextLabels = screen.optJSONObject("labels");
        if (nextLabels == null) return;
        labels = nextLabels;
        dark = screen.optBoolean("dark");
        int percent = Math.max(90, Math.min(130, screen.optInt("scale", 100)));
        scale = percent / 100f;
        JSONArray suggestions = screen.optJSONArray("presets");
        String key = dark + "/" + percent + "/" + labels + "/" + suggestions;
        if (!appearance.equals(key)) {
            appearance = key;
            String amount = input == null ? "" : input.getText().toString();
            boolean editing = input != null && input.hasFocus();
            int position = scroll == null ? 0 : scroll.getScrollY();
            build(suggestions == null ? new JSONArray() : suggestions, amount);
            if (editing) { input.requestFocus(); input.setSelection(amount.length()); }
            scroll.post(() -> scroll.scrollTo(0, position));
        }
        updateBalance(screen);
        hint.setText(screen.optString("accountHint"));
        chat.setVisibility(screen.optBoolean("canChat") ? VISIBLE : GONE);
    }

    private void build(JSONArray suggestions, String amount) {
        removeAllViews();
        presets.clear();
        setBackgroundColor(dark ? 0xff16171a : 0xfff6f7f9);
        LinearLayout column = stack();
        addView(column, new LayoutParams(-1, -1));
        LinearLayout bar = row();
        bar.setBackgroundColor(surface());
        column.addView(bar, -1, -2);
        ImageButton back = new ImageButton(getContext());
        back.setContentDescription(word("back"));
        back.setImageResource(R.drawable.platform_back);
        back.setColorFilter(ink());
        back.setBackground(ripple(Color.TRANSPARENT, false, 24));
        back.setOnClickListener(v -> back());
        bar.addView(back, dp(54), dp(54));
        TextView title = label(word("title"), 18, ink(), true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, weight());
        bar.addView(new View(getContext()), dp(54), 1);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
            setAccessibilityPaneTitle(word("title"));
        }
        scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout center = new FrameLayout(getContext());
        scroll.addView(center);
        body = stack();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        body.setFocusableInTouchMode(true);
        int width = getResources().getConfiguration().screenWidthDp > 620 ? dp(600) : -1;
        center.addView(body, new LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        balance = card();
        body.addView(balance, -1, -2);
        buildAmount(suggestions, amount);
        hint = label("", 13, muted(), false);
        body.addView(hint, spaced(12));
        chat = button(word("chat"), false, v -> listener.action("chat", ""));
        body.addView(chat, spaced(12));
        body.requestFocus();
    }

    private void updateBalance(JSONObject screen) {
        balance.removeAllViews();
        LinearLayout header = row();
        balance.addView(header);
        header.addView(label(word("total"), 17, ink(), true), weight());
        if (screen.optBoolean("bound")) {
            Button refresh = button(word("refresh"), false, v -> listener.action("refresh", ""));
            enable(refresh, !"loading".equals(screen.optString("status")));
            header.addView(refresh);
        }
        JSONArray values = screen.optJSONArray("balances");
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject info = values.optJSONObject(i);
            if (info == null) continue;
            if (i > 0) {
                View line = new View(getContext());
                line.setBackgroundColor(border());
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, dp(1));
                gap.topMargin = dp(14);
                balance.addView(line, gap);
            }
            balance.addView(label(info.optString("currency") + "  " + info.optString("total"), 25, ink(), true), spaced(8));
            addBalanceLine("credit", info.optString("credit"));
            addBalanceLine("grant", info.optString("grant"));
        }
        String message = screen.optString("message");
        if (!message.isEmpty()) {
            TextView status = label(message, 14, muted(), false);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            balance.addView(status, spaced(8));
        }
        if (!screen.optBoolean("bound") || "invalid".equals(screen.optString("status"))) {
            balance.addView(button(word("keys"), false, v -> listener.action("keys", "")), spaced(10));
        }
    }

    private void addBalanceLine(String key, String value) {
        LinearLayout pair = row();
        balance.addView(pair, spaced(4));
        pair.addView(label(word(key), 14, muted(), false), weight());
        TextView figure = label(value, 14, ink(), false);
        figure.setGravity(Gravity.END);
        pair.addView(figure, weight());
    }

    private void buildAmount(JSONArray suggestions, String raw) {
        LinearLayout form = card();
        body.addView(form, spaced(16));
        TextView heading = label(word("amount"), 17, ink(), true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        form.addView(heading);
        LinearLayout options = row();
        form.addView(options, spaced(10));
        for (int i = 0; i < suggestions.length(); i++) {
            String value = suggestions.optString(i);
            if (parseAmount(value).value.isEmpty()) continue;
            Button choice = button("¥" + value, false, v -> {
                input.setText(value);
                input.setSelection(value.length());
            });
            choice.setTag(parseAmount(value).value);
            presets.add(choice);
            LinearLayout.LayoutParams cell = weight();
            if (i > 0) cell.setMarginStart(dp(8));
            options.addView(choice, cell);
        }
        TextView caption = label(word("custom"), 14, muted(), false);
        form.addView(caption, spaced(14));
        LinearLayout field = row();
        field.setBackground(shape(surface(), true, 10));
        field.setPadding(dp(12), 0, dp(12), 0);
        form.addView(field, spaced(5));
        input = new EditText(getContext());
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setSingleLine(true);
        // A numeric IME is convenient; filtering pasted text could silently turn "-5" into "5".
        input.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setTextColor(ink());
        input.setHintTextColor(muted());
        input.setTextSize(18 * scale);
        input.setMinHeight(dp(48));
        input.setPadding(0, dp(6), dp(6), dp(6));
        input.setHint("0.00");
        input.setText(raw);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.addView(input, weight());
        field.addView(label(word("unit"), 14, muted(), false));
        error = label("", 13, dark ? 0xffffa5a5 : 0xffa02a29, false);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(error, spaced(4));
        form.addView(label(word("instructions"), 13, muted(), false), spaced(8));
        checkout = button(word("checkout"), true, v -> {
            String selected = parseAmount(input.getText().toString()).value;
            if (!selected.isEmpty()) listener.action("checkout", selected);
        });
        copy = button(word("copy"), false, v -> copyAmount());
        form.addView(checkout, spaced(12));
        form.addView(copy, spaced(8));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateAmount(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        updateAmount(raw);
    }

    private void updateAmount(String raw) {
        Amount amount = parseAmount(raw);
        error.setText(word(amount.error));
        error.setVisibility(amount.error.isEmpty() ? GONE : VISIBLE);
        enable(checkout, !amount.value.isEmpty());
        enable(copy, !amount.value.isEmpty());
        for (Button preset : presets) {
            boolean selected = amount.value.equals(preset.getTag());
            if (selected == preset.isSelected()) continue;
            preset.setSelected(selected);
            preset.setTextColor(selected ? Color.WHITE : accent());
            preset.setBackground(ripple(selected ? blue() : surface(), !selected, 12));
        }
    }

    private void copyAmount() {
        String value = parseAmount(input.getText().toString()).value;
        if (value.isEmpty()) return;
        try {
            ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException();
            clipboard.setPrimaryClip(ClipData.newPlainText(word("amount"), value));
            // Android 13+ shows its own confirmation.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(getContext(), word("copied"), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unavailable) {
            input.requestFocus();
            input.selectAll();
            Toast.makeText(getContext(), word("copyFailed"), Toast.LENGTH_SHORT).show();
        }
    }

    private Button button(String text, boolean primary, OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(46));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setTextSize(14 * scale);
        button.setTextColor(primary ? Color.WHITE : accent());
        button.setStateListAnimator(null);
        button.setBackground(ripple(primary ? blue() : surface(), !primary, 12));
        button.setOnClickListener(listener);
        return button;
    }

    private void enable(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.52f);
    }

    private LinearLayout card() {
        LinearLayout card = stack();
        card.setBackground(shape(surface(), false, 18));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        return card;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(size * scale);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private LinearLayout stack() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(getContext());
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams spaced(int marginTop) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(marginTop);
        return p;
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String word(String key) { return labels.optString(key); }
    private int surface() { return dark ? 0xff202125 : Color.WHITE; }
    private int ink() { return dark ? 0xfff2f3f5 : 0xff202329; }
    private int muted() { return dark ? 0xffb3b6c0 : 0xff646976; }
    private int accent() { return dark ? 0xffa5b1ff : 0xff3456bc; }
    private int blue() { return dark ? 0xff445cc7 : 0xff3956c5; }
    private int border() { return dark ? 0xff515560 : 0xffd5d8e0; }

    private GradientDrawable shape(int fill, boolean stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke) shape.setStroke(dp(1), border());
        return shape;
    }
    private RippleDrawable ripple(int fill, boolean stroke, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x18000000), shape(fill, stroke, radius), null);
    }
}
