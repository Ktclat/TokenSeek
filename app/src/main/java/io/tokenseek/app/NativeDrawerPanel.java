package io.tokenseek.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Drawer rows are recycled by ListView, including conversations inside expanded projects. */
final class NativeDrawerPanel extends LinearLayout {
    private enum Kind { HEADING, PROJECTS, FOLDER, CHAT, EMPTY }
    private static final class Row {
        final Kind kind;
        final String id, title;
        final boolean nested, pinned;
        Row(Kind kind, String id, String title, boolean nested, boolean pinned) {
            this.kind = kind;
            this.id = id;
            this.title = title;
            this.nested = nested;
            this.pinned = pinned;
        }
        Row(Kind kind, String title) { this(kind, "", title, false, false); }
    }

    private final NativeConversation.Listener listener;
    private final Set<String> expanded = new HashSet<>();
    private final List<Row> rows = new ArrayList<>();
    private final RowAdapter adapter = new RowAdapter();
    private final ListView list;
    private final LinearLayout toolbar;
    private final LinearLayout footer;
    private final LinearLayout commands;
    private JSONObject state = new JSONObject();
    private String historyVersion = "", profileVersion = "", active = "";
    private boolean english, dark;
    private float scale = 1f;
    private Row editing;

    NativeDrawerPanel(Context context, NativeConversation.Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setClickable(true);
        setPadding(dp(10), 0, dp(10), 0);
        toolbar = horizontal();
        addView(toolbar, -1, -2);
        list = new ListView(context);
        list.setDivider(null);
        list.setItemsCanFocus(true);
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(adapter);
        addView(list, new LayoutParams(-1, 0, 1));
        commands = new LinearLayout(context);
        commands.setOrientation(VERTICAL);
        commands.setVisibility(GONE);
        android.widget.ScrollView actionScroll = new android.widget.ScrollView(context);
        actionScroll.setVisibility(GONE);
        actionScroll.addView(commands);
        addView(actionScroll, new LayoutParams(-1, 0, 1));
        footer = horizontal();
        footer.setPadding(dp(4), dp(6), 0, dp(6));
        addView(footer, -1, -2);
    }

    void render(JSONObject next) {
        boolean nextEnglish = next.optBoolean("english", next.optString("locale", getResources().getConfiguration()
                .getLocales().get(0).getLanguage()).startsWith("en"));
        boolean nextDark = next.optBoolean("dark");
        float nextScale = Math.max(90, Math.min(130, next.optInt("scale", 100))) / 100f;
        boolean themeChanged = toolbar.getChildCount() == 0 || english != nextEnglish || dark != nextDark || scale != nextScale;
        state = next;
        english = nextEnglish;
        dark = nextDark;
        scale = nextScale;
        if (themeChanged) {
            setBackgroundColor(dark ? 0xff202124 : 0xfff7f8fa);
            renderToolbar();
            if (Build.VERSION.SDK_INT >= 28) setAccessibilityPaneTitle(word("对话侧栏", "Conversation sidebar"));
        }
        String nickname = next.optString("nickname", "TokenSeek"), balance = next.optString("balance");
        String avatar = next.optString("avatar");
        String profile = nickname + "\n" + balance + "\n" + avatar;
        if (themeChanged || !profile.equals(profileVersion)) {
            profileVersion = profile;
            renderFooter(nickname, balance, avatar);
        }
        active = next.optString("sessionId");
        String history = active + "/" + next.optJSONArray("sessions") + "/" + next.optJSONObject("organization")
                + "/" + next.optJSONArray("projects");
        if (themeChanged || !history.equals(historyVersion)) {
            historyVersion = history;
            rebuildRows();
            if (editing != null) {
                Row updated = null;
                for (Row row : rows) if (row.kind == editing.kind && row.id.equals(editing.id)) updated = row;
                if (updated == null) closeActions(); else showActions(updated);
            }
        }
    }

    private void renderToolbar() {
        toolbar.removeAllViews();
        TextView title = text(editing == null ? "TokenSeek" : editing.title, 20, true);
        title.setTextColor(editing == null ? brand() : ink());
        title.setPadding(dp(8), 0, 0, 0);
        toolbar.addView(title, weighted());
        String label = editing == null ? word("收起侧栏", "Close sidebar") : word("返回", "Back");
        toolbar.addView(icon(editing == null ? R.drawable.drawer_panel : R.drawable.platform_back, label,
                v -> { if (!closeActions()) listener.action("closeDrawer", ""); }), dp(48), dp(52));
    }

    private void rebuildRows() {
        JSONObject org = state.optJSONObject("organization");
        JSONObject assignments = org == null ? null : org.optJSONObject("assignments");
        JSONArray projects = org == null ? state.optJSONArray("projects") : org.optJSONArray("projects");
        JSONArray sessions = state.optJSONArray("sessions");
        JSONArray pinnedList = org == null ? null : org.optJSONArray("pinned");
        Map<String, Integer> pinOrder = new HashMap<>();
        if (pinnedList != null) for (int i = 0; i < pinnedList.length(); i++) pinOrder.put(pinnedList.optString(i), i);
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        Map<String, List<Row>> buckets = new HashMap<>();
        if (projects != null) for (int i = 0; i < projects.length(); i++) {
            JSONObject p = projects.optJSONObject(i);
            if (p == null || p.optString("id").isEmpty()) continue;
            names.put(p.optString("id"), p.optString("name"));
            buckets.put(p.optString("id"), new ArrayList<>());
        }
        expanded.retainAll(names.keySet());
        List<Row> pinned = new ArrayList<>(), recent = new ArrayList<>();
        if (sessions != null) for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s == null || s.optString("id").isEmpty()) continue;
            String id = s.optString("id");
            String folder = assignments == null ? s.optString("projectId") : assignments.optString(id);
            String title = s.isNull("title") ? "" : s.optString("title");
            boolean pin = pinOrder.containsKey(id) || s.optBoolean("pinned");
            List<Row> bucket = buckets.get(folder);
            Row row = new Row(Kind.CHAT, id, title.isEmpty() ? word("新对话", "New chat") : title, bucket != null, pin);
            if (bucket != null) bucket.add(row);
            else if (pin) pinned.add(row);
            else recent.add(row);
        }
        rows.clear();
        if (!pinned.isEmpty()) {
            pinned.sort((a, b) -> Integer.compare(pinOrder.getOrDefault(a.id, Integer.MAX_VALUE),
                    pinOrder.getOrDefault(b.id, Integer.MAX_VALUE)));
            rows.add(new Row(Kind.HEADING, word("置顶", "Pinned")));
            rows.addAll(pinned);
        }
        rows.add(new Row(Kind.PROJECTS, word("项目", "Projects")));
        for (Map.Entry<String, String> project : names.entrySet()) {
            String id = project.getKey();
            rows.add(new Row(Kind.FOLDER, id, project.getValue(), false, false));
            if (!expanded.contains(id)) continue;
            List<Row> chats = buckets.get(id);
            chats.sort((a, b) -> Boolean.compare(b.pinned, a.pinned));
            rows.addAll(chats);
            if (chats.isEmpty()) rows.add(new Row(Kind.EMPTY, "", word("暂无对话", "No chats yet"), true, false));
        }
        rows.add(new Row(Kind.HEADING, word("最近对话", "Recent chats")));
        rows.addAll(recent);
        if (recent.isEmpty()) rows.add(new Row(Kind.EMPTY, word("暂无对话", "No chats yet")));
        adapter.notifyDataSetChanged();
    }

    private void renderFooter(String nickname, String balance, String avatar) {
        footer.removeAllViews();
        if (nickname.isEmpty()) nickname = "TokenSeek";
        LinearLayout identity = horizontal();
        identity.setPadding(dp(4), dp(2), 0, dp(2));
        identity.setMinimumHeight(dp(52));
        identity.setBackground(ripple(Color.TRANSPARENT));
        identity.setFocusable(true);
        identity.setContentDescription(word("账户", "Account") + " · " + nickname + (balance.isEmpty() ? "" : " · " + balance));
        identity.setOnClickListener(v -> listener.action("account", ""));
        footer.addView(identity, weighted());
        FrameLayout badge = new FrameLayout(getContext());
        badge.setBackground(shape(0xff4559a6, 30));
        badge.setClipToOutline(true);
        badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        identity.addView(badge, dp(38), dp(38));
        Bitmap bitmap = photo(avatar);
        if (bitmap != null) {
            ImageView image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(bitmap);
            badge.addView(image, -1, -1);
        } else {
            TextView initials = text(nickname.substring(0, nickname.offsetByCodePoints(0, 1)), 16, true);
            initials.setTextColor(Color.WHITE);
            initials.setGravity(Gravity.CENTER);
            badge.addView(initials, -1, -1);
        }
        LinearLayout caption = new LinearLayout(getContext());
        caption.setOrientation(VERTICAL);
        caption.setPadding(dp(9), 0, dp(2), 0);
        caption.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        identity.addView(caption, weighted());
        caption.addView(text(nickname, 15, true));
        if (!balance.isEmpty()) {
            TextView amount = text(balance, 12, false);
            amount.setTextColor(muted());
            caption.addView(amount);
        }
        footer.addView(icon(R.drawable.drawer_more, word("设置", "Settings"), v -> listener.action("settings", "")), dp(48), dp(52));
    }

    private static Bitmap photo(String data) {
        int comma = data.indexOf(',');
        if (!data.startsWith("data:image/") || comma < 0 || !data.substring(0, comma).endsWith(";base64")
                || data.length() > 700000) return null;
        try {
            byte[] bytes = Base64.decode(data.substring(comma + 1), Base64.DEFAULT);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void activate(Row row) {
        if (row.kind == Kind.CHAT) listener.action("open", row.id);
        else if (row.kind == Kind.FOLDER) {
            if (!expanded.remove(row.id)) expanded.add(row.id);
            rebuildRows();
        }
    }

    private void showActions(Row row) {
        editing = row;
        list.setVisibility(GONE);
        ((View)commands.getParent()).setVisibility(VISIBLE);
        commands.setVisibility(VISIBLE);
        commands.removeAllViews();
        if (row.kind == Kind.FOLDER) {
            command("renameProject", word("重命名项目", "Rename project"), row);
            command("deleteProject", word("删除项目", "Delete project"), row);
        } else {
            command("togglePin", row.pinned ? word("取消置顶", "Unpin chat") : word("置顶对话", "Pin chat"), row);
            command("move", word("移入项目", "Move to project"), row);
            command("rename", word("重命名", "Rename"), row);
            command("exportChat", word("导出对话", "Export chat"), row);
            command("delete", word("删除对话", "Delete chat"), row);
        }
        renderToolbar();
        if (!isInTouchMode() && commands.getChildCount() > 0) commands.getChildAt(0).requestFocus();
        if (Build.VERSION.SDK_INT >= 28) commands.setAccessibilityPaneTitle(row.title);
    }

    private void command(String action, String label, Row row) {
        TextView item = text(label, 16, false);
        item.setPadding(dp(12), 0, dp(8), 0);
        item.setMinHeight(dp(48));
        item.setBackground(ripple(Color.TRANSPARENT));
        item.setFocusable(true);
        item.setOnClickListener(v -> {
            closeActions();
            listener.action(action, row.id);
        });
        commands.addView(item, -1, -2);
    }

    boolean closeActions() {
        if (editing == null) return false;
        editing = null;
        commands.setVisibility(GONE);
        ((View)commands.getParent()).setVisibility(GONE);
        list.setVisibility(VISIBLE);
        renderToolbar();
        return true;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) {
            Kind kind = rows.get(position).kind;
            return kind == Kind.CHAT || kind == Kind.FOLDER || kind == Kind.PROJECTS;
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ItemView item = recycled == null ? new ItemView() : (ItemView) recycled;
            item.bind(rows.get(position));
            return item;
        }
    }

    private final class ItemView extends LinearLayout {
        final TextView title;
        final ImageButton extra;
        ItemView() {
            super(NativeDrawerPanel.this.getContext());
            setGravity(Gravity.CENTER_VERTICAL);
            title = text("", 15, false);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setMinHeight(dp(46));
            addView(title, weighted());
            extra = icon(R.drawable.drawer_more, "", null);
            addView(extra, dp(48), dp(48));
        }
        void bind(Row row) {
            boolean folder = row.kind == Kind.FOLDER, chat = row.kind == Kind.CHAT;
            boolean header = row.kind == Kind.HEADING || row.kind == Kind.PROJECTS;
            boolean current = chat && active.equals(row.id);
            title.setText(row.title);
            title.setSelected(current);
            title.setTextColor(chat || folder ? ink() : muted());
            title.setTextSize((header || row.kind == Kind.EMPTY ? 13 : 15) * scale);
            title.setTypeface(null, header || current ? Typeface.BOLD : Typeface.NORMAL);
            title.setPadding(dp(row.nested ? 26 : 9), 0, 0, 0);
            title.setMinHeight(dp(header || row.kind == Kind.EMPTY ? 34 : 48));
            int icon = folder ? (expanded.contains(row.id) ? R.drawable.keys_expand : R.drawable.keys_chevron)
                    : row.pinned && row.nested ? R.drawable.drawer_pin : 0;
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            title.setCompoundDrawableTintList(ColorStateList.valueOf(muted()));
            title.setCompoundDrawablePadding(dp(5));
            title.setContentDescription(folder ? row.title + " · " + (expanded.contains(row.id)
                    ? word("收起项目", "Collapse project") : word("展开项目", "Expand project")) : null);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(header);
            setBackground(shape(current ? (dark ? 0xff323644 : 0xffe9edf7) : Color.TRANSPARENT, 10));
            boolean interactive = folder || chat;
            title.setBackground(interactive ? ripple(Color.TRANSPARENT) : null);
            title.setOnClickListener(interactive ? v -> activate(row) : null);
            title.setOnLongClickListener(interactive ? v -> { showActions(row); return true; } : null);
            title.setClickable(interactive);
            title.setLongClickable(interactive);
            title.setFocusable(interactive);
            if (interactive || row.kind == Kind.PROJECTS) {
                boolean add = row.kind == Kind.PROJECTS;
                extra.setVisibility(VISIBLE);
                extra.setImageResource(add ? R.drawable.keys_add : R.drawable.drawer_more);
                extra.setColorFilter(muted());
                extra.setContentDescription(add ? word("新建项目", "New project")
                        : word("更多选项", "More options") + " · " + row.title);
                extra.setOnClickListener(add ? v -> listener.action("createProject", "") : v -> showActions(row));
            } else {
                extra.setVisibility(GONE);
            }
        }
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private int ink() { return dark ? 0xfff1f2f4 : 0xff18191c; }
    private int muted() { return dark ? 0xffb2b4bb : 0xff62666e; }
    private int brand() { return dark ? 0xff9cacff : 0xff3c59c9; }
    private String word(String zh, String en) { return english ? en : zh; }
    private LayoutParams weighted() { return new LayoutParams(0, -2, 1); }
    private LinearLayout horizontal() {
        LinearLayout line = new LinearLayout(getContext());
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }
    private TextView text(String label, int size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextSize(size * scale);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTextColor(ink());
        view.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }
    private ImageButton icon(int resource, String label, View.OnClickListener tap) {
        ImageButton button = new ImageButton(getContext());
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setImageResource(resource);
        button.setColorFilter(ink());
        button.setContentDescription(label);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(ripple(Color.TRANSPARENT));
        button.setOnClickListener(tap);
        return button;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(radius));
        drawable.setColor(color);
        return drawable;
    }
    private RippleDrawable ripple(int color) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x28ffffff : 0x18000000),
                shape(color, 10), shape(Color.WHITE, 10));
    }
}
