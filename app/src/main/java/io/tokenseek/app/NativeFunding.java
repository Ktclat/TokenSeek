package io.tokenseek.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Shows balances from the official API and access to the native usage screen. */
final class NativeFunding extends FrameLayout {
    interface Listener { void action(String id); }

    private final Listener listener;

    private boolean detailsExpanded;
    private boolean english;
    private boolean dark;
    private float scale;
    private LinearLayout balance;
    private Button chat;

    NativeFunding(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClickable(true);
        setFocusableInTouchMode(true);
    }

    boolean detailsExpanded() { return detailsExpanded; }

    void setDetailsExpanded(boolean expanded) { detailsExpanded = expanded; }

    void render(boolean english, boolean dark, int fontPercent, boolean bound, boolean refreshing,
                NativeBalanceRequest.Result result) {
        float nextScale = Math.max(90, Math.min(130, fontPercent)) / 100f;
        if (balance == null || this.english != english || this.dark != dark || scale != nextScale) {
            this.english = english;
            this.dark = dark;
            scale = nextScale;
            build();
        }
        updateBalance(bound, refreshing, result);
        chat.setVisibility(result.available ? VISIBLE : GONE);
    }

    private void build() {
        removeAllViews();
        setBackgroundColor(dark ? 0xff16171a : 0xfff6f7f9);
        LinearLayout column = stack();
        addView(column, new LayoutParams(-1, -1));
        LinearLayout bar = row();
        bar.setBackgroundColor(surface());
        column.addView(bar, -1, -2);
        ImageButton back = new ImageButton(getContext());
        back.setContentDescription(t("返回", "Back"));
        back.setImageResource(R.drawable.platform_back);
        back.setColorFilter(ink());
        back.setBackground(ripple(24));
        back.setOnClickListener(v -> listener.action("back"));
        bar.addView(back, dp(54), dp(54));
        TextView title = label(t("余额", "Balance"), 18, ink(), true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, weight());
        Button usage = textAction(t("用量明细", "Usage details"), v -> listener.action("usage"));
        bar.addView(usage, -2, -2);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
            setAccessibilityPaneTitle(t("余额", "Balance"));
        }
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout center = new FrameLayout(getContext());
        scroll.addView(center);
        LinearLayout body = stack();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        body.setFocusableInTouchMode(true);
        int width = getResources().getConfiguration().screenWidthDp > 620 ? dp(600) : -1;
        center.addView(body, new LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        balance = card();
        body.addView(balance, -1, -2);
        LinearLayout topUp = card();
        topUp.addView(label(t("充值 · 暂未接入官方下单", "Top-up · In-app ordering unavailable"), 15, muted(), false));
        body.addView(topUp, spaced(12));
        chat = textAction(t("返回对话", "Return to chat"), v -> listener.action("chat"));
        body.addView(chat, spaced(4));
        body.requestFocus();
    }

    private void updateBalance(boolean bound, boolean refreshing, NativeBalanceRequest.Result result) {
        balance.removeAllViews();
        LinearLayout header = row();
        balance.addView(header);
        header.addView(label(t("账户总余额", "Total balance"), 15, muted(), false), weight());
        if (bound) {
            String caption = refreshing ? t("正在刷新…", "Refreshing…") : t("刷新余额", "Refresh balance");
            Button refresh = textAction(caption, v -> listener.action("refresh"));
            refresh.setEnabled(!refreshing);
            refresh.setAlpha(refreshing ? 0.52f : 1f);
            header.addView(refresh);
        } else {
            header.addView(textAction(t("聊天密钥", "Chat keys"), v -> listener.action("keys")));
        }
        JSONArray values = result.balances;
        List<View> breakdowns = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
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
            details.addView(balancePart(t("充值余额", "Paid balance"), info.optString("credit")), weight());
            LinearLayout.LayoutParams second = weight();
            second.setMarginStart(dp(12));
            details.addView(balancePart(t("赠送余额", "Granted balance"), info.optString("grant")), second);
            balance.addView(details, spaced(12));
            details.setVisibility(detailsExpanded ? VISIBLE : GONE);
            breakdowns.add(details);
        }
        if (!breakdowns.isEmpty()) addDetailsToggle(breakdowns);
        String message = balanceMessage(result);
        if (!message.isEmpty()) {
            TextView status = label(message, 14, muted(), false);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            balance.addView(status, spaced(8));
        }
        if ("invalid".equals(result.status)) {
            balance.addView(textAction(t("聊天密钥", "Chat keys"), v -> listener.action("keys")), spaced(10));
        }
    }

    private String balanceMessage(NativeBalanceRequest.Result result) {
        switch (result.status) {
            case "unbound": return t("选择聊天密钥后可查询余额。", "Select a chat key to view the balance.");
            case "invalid": return t("此密钥无法查询余额，请重新绑定。", "Reconnect a valid key to view the balance.");
            case "error": return t("暂时无法查询余额，请重试。", "Could not retrieve the balance. Try again.");
            case "ready":
                if (result.balances.length() == 0) return t("官方暂未返回余额明细。", "No balance details were returned.");
                if (!result.available) return t("官方账户当前不可用于 API 调用。", "This account is currently unavailable for API calls.");
                break;
        }
        return "";
    }

    private void addDetailsToggle(List<View> breakdowns) {
        Button toggle = textAction(t("余额明细", "Balance details"), v -> {
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

    private LinearLayout balancePart(String name, String amount) {
        LinearLayout part = stack();
        part.addView(label(name, 13, muted(), false));
        TextView figure = label(amount, 16, ink(), false);
        figure.setFontFeatureSettings("tnum");
        part.addView(figure, spaced(2));
        return part;
    }

    private Button textAction(String caption, OnClickListener action) {
        Button button = new Button(getContext());
        button.setText(caption);
        button.setAllCaps(false);
        button.setMinimumWidth(0);
        button.setMinWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setTextSize(14 * scale);
        button.setTextColor(accent());
        button.setStateListAnimator(null);
        button.setBackground(ripple(12));
        button.setOnClickListener(action);
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = stack();
        card.setBackground(shape(surface(), 18));
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
    private String t(String zh, String en) { return english ? en : zh; }
    private int surface() { return dark ? 0xff202125 : Color.WHITE; }
    private int ink() { return dark ? 0xfff2f3f5 : 0xff202329; }
    private int muted() { return dark ? 0xffb3b6c0 : 0xff646976; }
    private int accent() { return dark ? 0xffa5b1ff : 0xff3456bc; }
    private int border() { return dark ? 0xff515560 : 0xffd5d8e0; }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private RippleDrawable ripple(int radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x18000000), shape(Color.TRANSPARENT, radius), null);
    }
}
