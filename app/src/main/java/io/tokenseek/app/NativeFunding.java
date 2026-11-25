package io.tokenseek.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
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

    private static final String CUSTOM = "custom";
    private final Listener listener;
    private final List<Button> choices = new ArrayList<>();
    private String selection = "";
    private boolean detailsExpanded;
    private JSONObject labels = new JSONObject();
    private String appearance = "";
    private boolean dark;
    private float scale;
    private LinearLayout balance;
    private LinearLayout body;
    private ScrollView scroll;
    private LinearLayout field;
    private EditText input;
    private TextView error;
    private TextView hint;
    private TextView manualCopy;
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

    Bundle saveForm() {
        Bundle state = new Bundle();
        state.putString("selection", selection);
        if (input != null && input.length() <= 64) state.putString("custom", input.getText().toString());
        state.putBoolean("details", detailsExpanded);
        return state;
    }

    void restoreForm(Bundle state) {
        if (state == null || input == null) return;
        String saved = state.getString("selection", "");
        selection = "";
        for (Button choice : choices) {
            if (saved.equals(choice.getTag())) {
                selection = saved;
                break;
            }
        }
        detailsExpanded = state.getBoolean("details");
        input.setText(state.getString("custom", ""));
        updateAmount();
    }

    void resetAccount() {
        selection = "";
        detailsExpanded = false;
        appearance = "";
        input = null;
        scroll = null;
    }

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
            if (editing && CUSTOM.equals(selection)) { input.requestFocus(); input.setSelection(amount.length()); }
            scroll.post(() -> scroll.scrollTo(0, position));
        }
        updateBalance(screen);
        hint.setText(screen.optString("accountHint"));
        chat.setVisibility(screen.optBoolean("canChat") ? VISIBLE : GONE);
    }

    private void build(JSONArray suggestions, String amount) {
        removeAllViews();
        choices.clear();
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
        Button bills = textAction(word("transactions"), v -> listener.action("transactions", ""));
        bills.setPadding(0, dp(4), dp(4), dp(4));
        bar.addView(bills, dp(54), -2);
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
        chat = textAction(word("chat"), v -> listener.action("chat", ""));
        body.addView(chat, spaced(4));
        body.requestFocus();
    }

    private void updateBalance(JSONObject screen) {
        balance.removeAllViews();
        LinearLayout header = row();
        balance.addView(header);
        header.addView(label(word("total"), 15, muted(), false), weight());
        if (screen.optBoolean("bound")) {
            boolean loading = screen.optBoolean("refreshing") || "loading".equals(screen.optString("status"));
            String caption = loading ? labels.optString("refreshing", word("refresh")) : word("refresh");
            Button refresh = textAction(caption, v -> listener.action("refresh", ""));
            enable(refresh, !loading);
            header.addView(refresh);
        } else {
            header.addView(textAction(word("keys"), v -> listener.action("keys", "")));
        }
        JSONArray values = screen.optJSONArray("balances");
        List<View> breakdowns = new ArrayList<>();
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
            String amount = info.optString("total");
            SpannableString total = new SpannableString(amount + "  " + info.optString("currency"));
            total.setSpan(new RelativeSizeSpan(0.5f), amount.length(), total.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            total.setSpan(new ForegroundColorSpan(muted()), amount.length(), total.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView figure = label("", 32, ink(), true);
            figure.setText(total);
            figure.setFontFeatureSettings("tnum");
            balance.addView(figure, spaced(i == 0 ? 0 : 8));
            LinearLayout details = row();
            details.setGravity(Gravity.TOP);
            details.addView(balancePart("credit", info.optString("credit")), weight());
            LinearLayout.LayoutParams second = weight();
            second.setMarginStart(dp(12));
            details.addView(balancePart("grant", info.optString("grant")), second);
            balance.addView(details, spaced(12));
            details.setVisibility(detailsExpanded ? VISIBLE : GONE);
            breakdowns.add(details);
        }
        if (!breakdowns.isEmpty()) addDetailsToggle(breakdowns);
        String message = screen.optString("message");
        if (!message.isEmpty() && screen.optBoolean("bound")) {
            TextView status = label(message, 14, muted(), false);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            balance.addView(status, spaced(8));
        }
        if ("invalid".equals(screen.optString("status"))) {
            balance.addView(button(word("keys"), false, v -> listener.action("keys", "")), spaced(10));
        }
    }

    private void addDetailsToggle(List<View> breakdowns) {
        Button toggle = textAction(word("details"), v -> {
            detailsExpanded = !detailsExpanded;
            for (View details : breakdowns) details.setVisibility(detailsExpanded ? VISIBLE : GONE);
            updateDetailsToggle((Button) v);
        });
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setPadding(0, 0, dp(4), 0);
        toggle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.addAction(detailsExpanded ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            }
            @Override public boolean performAccessibilityAction(View view, int action, Bundle args) {
                if (action == (detailsExpanded ? AccessibilityNodeInfo.ACTION_COLLAPSE : AccessibilityNodeInfo.ACTION_EXPAND))
                    return view.performClick();
                return super.performAccessibilityAction(view, action, args);
            }
        });
        updateDetailsToggle(toggle);
        balance.addView(toggle, spaced(2));
    }

    private void updateDetailsToggle(Button toggle) {
        Drawable arrow = getContext().getDrawable(detailsExpanded ? R.drawable.funding_collapse : R.drawable.keys_expand).mutate();
        arrow.setTint(accent());
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null);
    }

    private LinearLayout balancePart(String key, String amount) {
        LinearLayout part = stack();
        part.addView(label(word(key), 13, muted(), false));
        TextView figure = label(amount, 16, ink(), false);
        figure.setFontFeatureSettings("tnum");
        part.addView(figure, spaced(2));
        return part;
    }

    private void buildAmount(JSONArray suggestions, String raw) {
        LinearLayout form = card();
        body.addView(form, spaced(12));
        LinearLayout header = row();
        form.addView(header);
        TextView heading = label(word("amount"), 17, ink(), true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, weight());
        copy = textAction(word("copy"), v -> copyAmount());
        header.addView(copy);
        AmountChoices options = new AmountChoices();
        form.addView(options, spaced(8));
        for (int i = 0; i < suggestions.length(); i++) {
            String value = suggestions.optString(i);
            String canonical = parseAmount(value).value;
            if (!canonical.isEmpty()) addChoice(options, canonical, "¥" + value);
        }
        addChoice(options, CUSTOM, word("customChoice"));
        field = row();
        field.setBackground(shape(dark ? 0xff2a2b31 : 0xfff4f5f8, false, 12));
        field.setPadding(dp(14), 0, dp(10), 0);
        form.addView(field, spaced(12));
        input = new EditText(getContext());
        input.setId(View.generateViewId());
        input.setHint(word("custom"));
        input.setSingleLine(true);
        // A numeric IME is convenient; filtering pasted text could silently turn "-5" into "5".
        input.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setTextColor(ink());
        input.setHintTextColor(muted());
        input.setTextSize(22 * scale);
        input.setFontFeatureSettings("tnum");
        input.setMinHeight(dp(54));
        input.setPadding(0, dp(6), dp(6), dp(6));
        input.setText(raw);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.addView(input, weight());
        field.addView(label(word("unit"), 14, muted(), false));
        error = label("", 13, dark ? 0xffffa5a5 : 0xffa02a29, false);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(error, spaced(4));
        checkout = button(word("checkout"), true, v -> {
            String selected = selectedAmount().value;
            if (!selected.isEmpty()) listener.action("checkout", selected);
        });
        form.addView(checkout, spaced(16));
        hint = label("", 13, muted(), false);
        form.addView(hint, spaced(10));
        manualCopy = label("", 14, ink(), false);
        manualCopy.setTextIsSelectable(true);
        manualCopy.setVisibility(GONE);
        form.addView(manualCopy, spaced(8));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateAmount(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        updateAmount();
    }

    private void addChoice(AmountChoices options, String id, String caption) {
        Button choice = button(caption, false, v -> choose(id));
        choice.setTag(id);
        choices.add(choice);
        options.addView(choice);
    }

    private void choose(String id) {
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (!CUSTOM.equals(id) && CUSTOM.equals(selection)) {
            if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
            if (input.hasFocus()) body.requestFocus();
        }
        selection = id;
        updateAmount();
        if (CUSTOM.equals(id)) {
            EditText editor = input;
            editor.requestFocus();
            editor.setSelection(editor.length());
            editor.post(() -> {
                if (keyboard != null && editor == input && editor.hasFocus() && CUSTOM.equals(selection))
                    keyboard.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    private Amount selectedAmount() {
        return parseAmount(CUSTOM.equals(selection) ? input.getText().toString() : selection);
    }

    private void updateAmount() {
        Amount amount = selectedAmount();
        field.setVisibility(CUSTOM.equals(selection) ? VISIBLE : GONE);
        manualCopy.setVisibility(GONE);
        error.setText(word(amount.error));
        error.setVisibility(amount.error.isEmpty() ? GONE : VISIBLE);
        enable(checkout, !amount.value.isEmpty());
        String visibleAmount = amount.value.endsWith(".00") ? amount.value.substring(0, amount.value.length() - 3) : amount.value;
        checkout.setText(visibleAmount.isEmpty() ? word("checkout") : word("checkout") + " · ¥" + visibleAmount);
        enable(copy, !amount.value.isEmpty());
        copy.setVisibility(amount.value.isEmpty() ? INVISIBLE : VISIBLE);
        hint.setVisibility(amount.value.isEmpty() ? GONE : VISIBLE);
        for (Button preset : choices) {
            boolean selected = selection.equals(preset.getTag());
            if (selected == preset.isSelected()) continue;
            preset.setSelected(selected);
            preset.setTextColor(selected ? accent() : ink());
            GradientDrawable fill = shape(selected ? (dark ? 0xff283057 : 0xffeef1ff) : surface(), true, 12);
            if (selected) fill.setStroke(dp(2), accent());
            preset.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x18000000), fill, null));
        }
    }

    private void copyAmount() {
        String value = selectedAmount().value;
        if (value.isEmpty()) return;
        try {
            ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException();
            clipboard.setPrimaryClip(ClipData.newPlainText(word("amount"), value));
            // Android 13+ shows its own confirmation.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(getContext(), word("copied"), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unavailable) {
            if (CUSTOM.equals(selection)) {
                input.requestFocus();
                input.selectAll();
            } else {
                manualCopy.setText(value);
                manualCopy.setVisibility(VISIBLE);
            }
            Toast.makeText(getContext(), word("copyFailed"), Toast.LENGTH_SHORT).show();
        }
    }

    /** Keeps each row's targets equal as the viewport, locale, or preferred text size changes. */
    private final class AmountChoices extends GridLayout {
        private int columns;

        AmountChoices() { super(NativeFunding.this.getContext()); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int cell = dp(68);
            for (Button choice : choices) {
                int text = (int) Math.ceil(choice.getPaint().measureText(choice.getText().toString()));
                cell = Math.max(cell, text + choice.getPaddingStart() + choice.getPaddingEnd());
            }
            boolean largeType = scale > 1f || getResources().getConfiguration().fontScale > 1f;
            int available = MeasureSpec.getSize(widthSpec);
            int count = !largeType && available >= cell * 4 + dp(8) * 3 ? 4 : 2;
            if (columns != count) {
                columns = count;
                for (int i = 0; i < getChildCount(); i++) {
                    GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams(
                            GridLayout.spec(i / count, GridLayout.FILL), GridLayout.spec(i % count, GridLayout.FILL, 1f));
                    cellParams.width = 0;
                    cellParams.height = LayoutParams.WRAP_CONTENT;
                    cellParams.setMarginStart(i % count == 0 ? 0 : dp(8));
                    cellParams.topMargin = i < count ? 0 : dp(8);
                    getChildAt(i).setLayoutParams(cellParams);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private Button button(String text, boolean primary, OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setTextSize(14 * scale);
        button.setTextColor(primary ? Color.WHITE : ink());
        button.setStateListAnimator(null);
        button.setBackground(ripple(primary ? blue() : surface(), !primary, 12));
        button.setOnClickListener(listener);
        return button;
    }

    private Button textAction(String text, OnClickListener action) {
        Button link = button(text, false, action);
        link.setTextColor(accent());
        link.setMinWidth(dp(48));
        link.setBackground(ripple(Color.TRANSPARENT, false, 12));
        return link;
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
