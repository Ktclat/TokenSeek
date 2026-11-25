package io.tokenseek.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;

/** Native account and conversation views over the same on-device request records. */
final class NativeUsagePage extends LinearLayout {
    private final boolean english;
    private final boolean dark;
    private final float scale;
    private final NumberFormat numbers;
    private final TextView title;
    private final LinearLayout body;
    private final ScrollView scroll;
    private String conversationId;
    private String scope = "session";
    private int replyIndex;
    private int replyCount;
    private LinearLayout usageBlock;

    NativeUsagePage(Context context, boolean english, boolean dark, int fontPercent, Runnable back) {
        super(context);
        this.english = english;
        this.dark = dark;
        scale = Math.max(90, Math.min(130, fontPercent)) / 100f;
        numbers = NumberFormat.getIntegerInstance(english ? Locale.US : Locale.SIMPLIFIED_CHINESE);
        setOrientation(VERTICAL);
        setBackgroundColor(dark ? 0xff16171a : 0xfff6f7f9);
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(surface());
        addView(bar, -1, -2);
        ImageButton previous = new ImageButton(context);
        previous.setImageResource(R.drawable.platform_back);
        previous.setColorFilter(ink());
        previous.setContentDescription(t("返回", "Back"));
        previous.setBackground(touch(null));
        previous.setOnClickListener(v -> back.run());
        bar.addView(previous, dp(54), dp(54));
        title = text("", 18, ink(), true);
        title.setGravity(Gravity.CENTER);
        heading(title);
        bar.addView(title, new LayoutParams(0, -2, 1));
        bar.addView(new View(context), dp(54), 1);
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(-1, 0, 1));
        FrameLayout center = new FrameLayout(context);
        scroll.addView(center, -1, -2);
        body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        int width = getResources().getConfiguration().screenWidthDp > 620 ? dp(600) : -1;
        center.addView(body, new FrameLayout.LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        reset(t("账户用量", "Account usage"));
        body.addView(text(t("正在读取本机用量…", "Reading saved usage…"), 14, muted(), false));
    }

    void renderAccount(String accountName, JSONArray sessions) {
        renderAccountTotals(accountName, NativeUsageRecords.accountTotals(sessions));
    }

    void renderAccountTotals(String accountName, NativeUsageRecords.Totals totals) {
        reset(t("账户用量", "Account usage"));
        LinearLayout card = card();
        String caption = accountName == null || accountName.trim().isEmpty() ? t("当前本机账户", "Current device profile") : accountName;
        card.addView(text(caption, 18, ink(), true));
        note(card, t("统计此账户保存在本机的对话及所有分支，包含导入记录。其他设备或应用的请求没有计入。",
                "Based on all branches of conversations saved in this profile, including imports. Requests made elsewhere are outside this view."));
        if (totals == null) {
            note(card, t("暂时无法读取本机对话记录。", "Could not read the saved conversations."));
            return;
        }
        LinearLayout readings = card();
        addHeading(readings, t("已确认用量", "Confirmed usage"));
        showTotals(readings, totals);
    }

    void renderConversation(JSONObject session, String model, String draft, boolean streaming) {
        renderConversation(session, model, draft, streaming, false);
    }

    void renderConversation(JSONObject session, String model, String draft, boolean streaming, boolean hasAttachment) {
        reset(t("对话用量", "Conversation usage"));
        JSONArray all = session == null ? new JSONArray() : session.optJSONArray("messages");
        if (all == null) all = new JSONArray();
        JSONArray branch = visible(session);
        ArrayList<JSONObject> replies = new ArrayList<>();
        for (int i = 0; i < branch.length(); i++) {
            JSONObject message = branch.optJSONObject(i);
            if (message != null && "assistant".equals(message.optString("role"))) replies.add(message);
        }
        String id = session == null ? "" : session.optString("id");
        if (!id.equals(conversationId)) {
            scope = "session";
            replyIndex = Math.max(0, replies.size() - 1);
            conversationId = id;
        } else if (replyIndex >= replyCount - 1) {
            replyIndex = Math.max(0, replies.size() - 1);
        }
        replyCount = replies.size();
        replyIndex = Math.min(replyIndex, Math.max(0, replyCount - 1));
        if (replies.isEmpty() && "reply".equals(scope)) scope = "branch";
        showContext(NativeUsageRecords.context(branch, model, draft, streaming, hasAttachment), model);
        usageBlock = card();
        showScope(all, branch, replies);
    }

    static String remainingLabel(JSONObject session, String model, String draft, boolean streaming) {
        return remainingLabel(session, model, draft, streaming, false);
    }

    static String remainingLabel(JSONObject session, String model, String draft, boolean streaming, boolean hasAttachment) {
        return NativeUsageRecords.context(visible(session), model, draft, streaming, hasAttachment).percent();
    }

    private static JSONArray visible(JSONObject session) {
        return session == null ? new JSONArray() : NativeHistoryStore.visibleMessages(session);
    }

    private void showContext(NativeUsageRecords.ContextSpace context, String model) {
        LinearLayout card = card();
        addHeading(card, t("本分支剩余上下文（估算）", "Estimated context left on this branch"));
        figure(card, context.remaining == null ? t("待统计", "Unknown") : context.percent());
        if (context.remaining != null) metric(card, t("可用空间", "Available"), format(context.remaining) + " tokens");
        String window = context.limit == 0 ? "—" : format(context.limit) + " tokens";
        metric(card, t("模型窗口", "Model window"), model == null || model.isEmpty() ? window : model + " · " + window);
        if (context.previous != null && context.previous.signum() > 0)
            metric(card, t("最近一次请求", "Last request"), format(context.previous));
        if (context.draft != null && context.draft > 0)
            metric(card, t("草稿估算", "Draft estimate"), "≈ " + format(context.draft));
        if (context.reason == null) return;
        switch (context.reason) {
            case "streaming": note(card, t("回答结束后更新估算。", "The estimate updates when the reply finishes.")); break;
            case "attachment": note(card, t("草稿含附件，用量待接口返回。", "The draft includes files. Their token count is pending.")); break;
            case "model": note(card, t("此模型的窗口大小未知。", "The context limit for this model is unknown.")); break;
            case "draft": note(card, t("这段草稿无法可靠估算。", "This draft cannot be reliably estimated.")); break;
            default: note(card, t("本分支最后一轮缺少最终用量。", "The latest turn on this branch has no final usage record."));
        }
    }

    private void showScope(JSONArray all, JSONArray branch, ArrayList<JSONObject> replies) {
        usageBlock.removeAllViews();
        addHeading(usageBlock, t("请求用量", "Request usage"));
        LinearLayout tabs = new LinearLayout(getContext());
        boolean narrow = getResources().getConfiguration().screenWidthDp <= 360 && (scale > 1f || getResources().getConfiguration().fontScale > 1f);
        if (narrow) tabs.setOrientation(VERTICAL);
        usageBlock.addView(tabs, gap(8));
        addScope(tabs, "session", t("整个对话", "All branches"), all, branch, replies);
        addScope(tabs, "branch", t("当前分支", "This branch"), all, branch, replies);
        if (!replies.isEmpty()) addScope(tabs, "reply", t("单次回复", "One reply"), all, branch, replies);

        JSONArray messages;
        if ("reply".equals(scope) && !replies.isEmpty()) {
            LinearLayout pager = new LinearLayout(getContext());
            pager.setGravity(Gravity.CENTER);
            usageBlock.addView(pager, gap(4));
            Button prev = button("‹", false, v -> { replyIndex--; showScope(all, branch, replies); });
            prev.setContentDescription(t("上一条回复", "Previous reply"));
            prev.setEnabled(replyIndex > 0);
            prev.setAlpha(prev.isEnabled() ? 1f : 0.45f);
            pager.addView(prev, dp(48), -2);
            TextView position = text(format(replyIndex + 1) + " / " + format(replies.size()), 14, ink(), false);
            position.setGravity(Gravity.CENTER);
            pager.addView(position, new LayoutParams(0, -2, 1));
            Button next = button("›", false, v -> { replyIndex++; showScope(all, branch, replies); });
            next.setContentDescription(t("下一条回复", "Next reply"));
            next.setEnabled(replyIndex + 1 < replies.size());
            next.setAlpha(next.isEnabled() ? 1f : 0.45f);
            pager.addView(next, dp(48), -2);
            JSONObject reply = replies.get(replyIndex);
            messages = new JSONArray().put(reply);
            if (reply.opt("model") instanceof String) note(usageBlock, reply.optString("model"));
        } else {
            messages = "branch".equals(scope) ? branch : all;
        }
        NativeUsageRecords.Totals totals = new NativeUsageRecords.Totals();
        totals.addMessages(messages);
        showTotals(usageBlock, totals);
    }

    private void addScope(LinearLayout tabs, String key, String label, JSONArray all, JSONArray branch, ArrayList<JSONObject> replies) {
        boolean selected = key.equals(scope);
        Button tab = button(label, selected, v -> {
            scope = key;
            showScope(all, branch, replies);
        });
        tab.setSelected(selected);
        tabs.addView(tab, tabs.getOrientation() == VERTICAL ? new LayoutParams(-1, -2) : new LayoutParams(0, -2, 1));
    }

    private void showTotals(LinearLayout card, NativeUsageRecords.Totals totals) {
        if (totals.confirmed == 0) {
            note(card, t("尚无请求返回最终用量。", "No final usage records yet."));
        } else {
            figure(card, format(totals.total()) + " tokens");
            metric(card, t("输入", "Input"), format(totals.input));
            metric(card, t("输出", "Output"), format(totals.output));
            metric(card, t("输入中命中缓存", "Cached input"), totals.missingCache == totals.confirmed ? "—" : format(totals.cached));
            metric(card, t("已确认请求", "Requests with final usage"), format(totals.confirmed));
            if (totals.missingCache > 0)
                note(card, english ? format(totals.missingCache) + " final records lack cache details."
                        : format(totals.missingCache) + " 次已确认请求缺少缓存明细。");
        }
        if (totals.incomplete > 0)
            note(card, english ? "Requests without final usage: " + format(totals.incomplete) + ". Excluded from totals."
                    : format(totals.incomplete) + " 次请求缺少最终用量，未计入合计。");
        if (totals.snapshots > 0)
            metric(card, t("非最终快照", "Non-final snapshots"), format(totals.snapshotTokens) + " tokens");
    }

    private void reset(String caption) {
        title.setText(caption);
        if (Build.VERSION.SDK_INT >= 28) setAccessibilityPaneTitle(caption);
        body.removeAllViews();
    }

    private void addHeading(LinearLayout card, String caption) {
        TextView view = text(caption, 16, ink(), true);
        heading(view);
        card.addView(view);
    }

    private void heading(View view) {
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }

    private void figure(LinearLayout card, String value) {
        TextView figure = text(value, 27, ink(), true);
        figure.setFontFeatureSettings("tnum");
        card.addView(figure, gap(8));
    }

    private void metric(LinearLayout card, String name, String value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(name, 14, muted(), false);
        TextView number = text(value, 15, ink(), false);
        number.setFontFeatureSettings("tnum");
        int available = dp(Math.min(600, getResources().getConfiguration().screenWidthDp) - 82);
        boolean stack = label.getPaint().measureText(name) + number.getPaint().measureText(value) > available;
        if (stack) row.setOrientation(VERTICAL);
        row.addView(label, stack ? new LayoutParams(-1, -2) : new LayoutParams(0, -2, 1));
        row.addView(number, -2, -2);
        card.addView(row, gap(8));
    }

    private void note(LinearLayout card, String caption) {
        card.addView(text(caption, 14, muted(), false), gap(8));
    }

    private String format(Number value) { return numbers.format(value); }
    private String t(String zh, String en) { return english ? en : zh; }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(fill(surface(), 16));
        body.addView(card, gap(body.getChildCount() == 0 ? 0 : 12));
        return card;
    }

    private Button button(String label, boolean selected, OnClickListener action) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(selected ? (dark ? 0xffbdc9ff : 0xff274eb6) : ink());
        button.setTextSize(14 * scale);
        button.setAllCaps(false);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinHeight(dp(48));
        button.setStateListAnimator(null);
        button.setPadding(dp(4), dp(6), dp(4), dp(6));
        button.setBackground(touch(fill(selected ? (dark ? 0xff303b60 : 0xffedf1ff) : surface(), 10)));
        button.setOnClickListener(action);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size * scale);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        return column;
    }

    private LayoutParams gap(int top) { LayoutParams p = new LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private RippleDrawable touch(GradientDrawable fill) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x18000000), fill, null);
    }
    private GradientDrawable fill(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private int surface() { return dark ? 0xff202125 : Color.WHITE; }
    private int ink() { return dark ? 0xfff2f3f5 : 0xff202329; }
    private int muted() { return dark ? 0xffb3b6c0 : 0xff646976; }
}
