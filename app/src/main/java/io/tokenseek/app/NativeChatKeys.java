package io.tokenseek.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android's chat-key settings. The device vault owns credentials and account isolation. */
final class NativeChatKeys extends LinearLayout {
    interface Listener { void action(String action, String argument); }
    interface Records { String call(String profile, String action, String id, String name, String key, String expected); }
    private enum Page { LIST, ADD, EDIT, REMOVE, OTHER }

    private final Records records;
    private final Listener listener;
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FrameLayout bar;
    private final ImageButton backButton;
    private final ImageButton chatButton;
    private final TextView title;
    private final ScrollView scroll;
    private final LinearLayout content;
    private Resources strings;
    private Page page = Page.LIST;
    private JSONObject ring, otherRing;
    private JSONArray accounts = new JSONArray();
    private String owner = "", nickname = "", locale = "", other = "", editingId = "";
    private String bridgeError = "";
    private boolean dark, focusedRequest, reading, writing, closed;
    private int percent = 100;
    private long readVersion;
    private NativeChatKeysVerification verification;
    private Runnable timeout;
    private EditText nameInput, secretInput;
    private TextView notice;
    private Button submit;

    NativeChatKeys(Context context, KeyVault vault, Listener listener) {
        this(context, vault::manageProfile, listener);
    }

    NativeChatKeys(Context context, Records records, Listener listener) {
        super(context);
        this.records = records;
        this.listener = listener;
        strings = getResources();
        setOrientation(VERTICAL);
        setClickable(true);
        setFocusableInTouchMode(true);
        bar = new FrameLayout(context);
        bar.setMinimumHeight(dp(56));
        addView(bar, -1, -2);
        title = new TextView(context);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(52), dp(14), dp(52), dp(14));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        bar.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        backButton = new ImageButton(context);
        backButton.setImageResource(R.drawable.platform_back);
        backButton.setOnClickListener(v -> back());
        bar.addView(backButton, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START | Gravity.CENTER_VERTICAL));
        chatButton = new ImageButton(context);
        chatButton.setImageResource(R.drawable.chat_keys_conversation);
        chatButton.setOnClickListener(v -> listener.action("chat", owner));
        bar.addView(chatButton, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END | Gravity.CENTER_VERTICAL));
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(-1, 0, 1));
        FrameLayout center = new FrameLayout(context);
        scroll.addView(center);
        content = column();
        content.setPadding(dp(16), dp(16), dp(16), dp(28));
        int width = getResources().getConfiguration().screenWidthDp > 620 ? dp(620) : -1;
        center.addView(content, new FrameLayout.LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    void render(JSONObject screen) {
        if (closed) return;
        String nextOwner = screen.optString("profile");
        if (!OfficialPlatformActivity.isValidWorkspaceId(nextOwner)) return;
        boolean switched = !owner.equals(nextOwner);
        String nextName = screen.optString("nickname", nextOwner);
        String nextLocale = screen.optString("locale", "zh-CN");
        JSONArray nextAccounts = screen.optJSONArray("profiles");
        if (nextAccounts == null) nextAccounts = new JSONArray();
        int size = Math.max(90, Math.min(130, screen.optInt("scale", 100)));
        boolean nextDark = screen.optBoolean("dark");
        boolean changed = switched || !nickname.equals(nextName) || !locale.equals(nextLocale)
                || dark != nextDark || percent != size || !accounts.toString().equals(nextAccounts.toString());
        if (!nextLocale.equals(locale)) {
            Configuration config = new Configuration(getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(nextLocale));
            strings = getContext().createConfigurationContext(config).getResources();
        }
        owner = nextOwner;
        nickname = nextName;
        accounts = nextAccounts;
        locale = nextLocale;
        dark = nextDark;
        percent = size;
        boolean focus = screen.optBoolean("focusNewKey");
        boolean openAdd = focus && (!focusedRequest || switched);
        focusedRequest = focus;
        if (switched) {
            cancelVerification();
            forgetFields();
            page = Page.LIST;
            other = "";
            ring = null;
            otherRing = null;
            writing = false;
            read(owner);
        }
        if (openAdd && !writing) {
            go(Page.ADD);
            focusSecret();
        } else if (changed) {
            // An unrelated re-render must not discard an in-progress paste or rename.
            redrawWithDraft();
        }
        String error = screen.optString("error");
        if (!error.isEmpty() && !error.equals(bridgeError)) message(error);
        bridgeError = error;
    }

    private void redrawWithDraft() {
        String draftName = nameInput == null ? null : nameInput.getText().toString();
        String draftKey = secretInput == null ? null : secretInput.getText().toString();
        boolean editingName = nameInput != null && nameInput.hasFocus();
        boolean editingKey = secretInput != null && secretInput.hasFocus();
        draw();
        if (nameInput != null && draftName != null) nameInput.setText(draftName);
        if (secretInput != null && draftKey != null) secretInput.setText(draftKey);
        if (editingKey && secretInput != null) secretInput.requestFocus();
        if (editingName && nameInput != null) nameInput.requestFocus();
    }

    void refresh() {
        if (!closed && !writing && !owner.isEmpty()) read(page == Page.OTHER ? other : owner);
    }

    private JSONObject load(String profile, String op, String id, String name, String key, String expected) {
        try {
            JSONObject result = new JSONObject(records.call(profile, op, id, name, key, expected));
            result.remove("apiKey");
            result.getJSONArray("entries");
            return result;
        } catch (JSONException | RuntimeException unavailable) {
            return null;
        }
    }

    private void read(String profile) {
        long version = ++readVersion;
        reading = true;
        storage.execute(() -> {
            JSONObject result = load(profile, "list", "", "", "", null);
            ui.post(() -> {
                if (closed || readVersion != version) return;
                reading = false;
                boolean sameOwner = profile.equals(owner);
                JSONObject previous = sameOwner ? ring : otherRing;
                if (sameOwner) ring = result;
                else otherRing = result;
                if (page == Page.LIST || page == Page.OTHER) {
                    draw();
                } else if (result == null) {
                    updateSubmit();
                    message(text(R.string.chat_keys_storage));
                } else if ((page == Page.EDIT || page == Page.REMOVE) && findEditedKey() == null) {
                    go(Page.LIST);
                    message(text(R.string.chat_keys_changed));
                } else if (previous != null && !result.toString().equals(previous.toString())
                        && (page == Page.EDIT || page == Page.REMOVE)) {
                    redrawWithDraft();
                    message(text(R.string.chat_keys_changed));
                } else {
                    updateSubmit();
                }
            });
        });
    }

    private void write(String action, String id, String name, String key, String expected) {
        if (closed || writing) return;
        if (expected == null) {
            message(text(R.string.chat_keys_storage));
            refresh();
            return;
        }
        String profile = owner;
        writing = true;
        if (submit != null) submit.setEnabled(false);
        storage.execute(() -> {
            JSONObject result = load(profile, action, id, name, key, expected);
            JSONObject current = result == null ? load(profile, "list", "", "", "", null) : result;
            ui.post(() -> {
                if (result != null) listener.action("changed", profile);
                if (closed || !owner.equals(profile)) return;
                writing = false;
                if (result == null) {
                    ring = current;
                    boolean stale = current != null && !current.toString().equals(expected);
                    if (page != Page.ADD && (current == null || findEditedKey() == null)) go(Page.LIST);
                    else if (stale) redrawWithDraft();
                    updateSubmit();
                    message(text(stale ? R.string.chat_keys_changed : R.string.chat_keys_storage));
                } else {
                    ring = result;
                    go(Page.LIST);
                }
            });
        });
    }

    private void verify() {
        if (verification != null || writing || secretInput == null) return;
        String key = secretInput.getText().toString().trim(), name = nameInput.getText().toString().trim();
        if (key.isEmpty()) {
            secretInput.setError(text(R.string.chat_keys_paste));
            secretInput.requestFocus();
            return;
        }
        if (ring == null) {
            message(text(R.string.chat_keys_storage));
            refresh();
            return;
        }
        String expected = ring.toString();
        String profile = owner;
        NativeChatKeysVerification attempt = new NativeChatKeysVerification();
        verification = attempt;
        updateSubmit();
        message(text(R.string.chat_keys_verifying));
        timeout = () -> {
            if (verification != attempt) return;
            cancelVerification();
            updateSubmit();
            message(text(R.string.chat_keys_network));
        };
        ui.postDelayed(timeout, 15000);
        network.execute(() -> {
            int error = attempt.verify(key);
            ui.post(() -> {
                if (closed || attempt != verification || !owner.equals(profile) || page != Page.ADD) return;
                ui.removeCallbacks(timeout);
                verification = null;
                timeout = null;
                if (error == 0) write("add", "", name, key, expected);
                else {
                    updateSubmit();
                    message(text(error));
                }
            });
        });
    }

    private void updateSubmit() {
        boolean enabled = !writing && verification == null;
        if (submit != null) submit.setEnabled(enabled && (ring != null || !reading));
        if (secretInput != null) secretInput.setEnabled(enabled);
        if (nameInput != null) nameInput.setEnabled(enabled);
    }

    private void cancelVerification() {
        if (timeout != null) ui.removeCallbacks(timeout);
        timeout = null;
        NativeChatKeysVerification attempt = verification;
        verification = null;
        if (attempt != null) network.execute(attempt::cancel);
    }

    private void forgetFields() {
        if (secretInput != null) secretInput.getText().clear();
        secretInput = null;
        nameInput = null;
        submit = null;
        notice = null;
    }

    private void go(Page next) {
        cancelVerification();
        View focus = findFocus();
        if (focus instanceof EditText) {
            getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            requestFocus();
        }
        forgetFields();
        page = next;
        draw();
        scroll.scrollTo(0, 0);
    }

    void back() {
        if (writing || closed) return;
        if (page == Page.LIST) listener.action("back", owner);
        else if (page == Page.REMOVE) go(Page.EDIT);
        else if (page == Page.OTHER) {
            go(Page.LIST);
            read(owner);
        } else go(Page.LIST);
    }

    void close() {
        if (closed) return;
        closed = true;
        cancelVerification();
        forgetFields();
        storage.shutdown();
        network.shutdown();
    }

    private void draw() {
        forgetFields();
        setBackgroundColor(dark ? 0xff16171a : 0xfff6f7f9);
        bar.setBackgroundColor(surface());
        backButton.setColorFilter(ink());
        backButton.setBackground(ripple(null));
        backButton.setContentDescription(text(R.string.chat_keys_back));
        chatButton.setVisibility(page == Page.LIST ? VISIBLE : GONE);
        chatButton.setColorFilter(ink());
        chatButton.setBackground(ripple(null));
        chatButton.setContentDescription(text(R.string.chat_keys_return));
        chatButton.setTooltipText(text(R.string.chat_keys_return));
        title.setTextColor(ink());
        title.setTextSize(18f * percent / 100);
        content.removeAllViews();
        int heading = R.string.chat_keys_title;
        if (page == Page.ADD) heading = R.string.chat_keys_add;
        if (page == Page.EDIT || page == Page.REMOVE) heading = R.string.chat_keys_manage;
        title.setText(text(heading));
        if (Build.VERSION.SDK_INT >= 28) setAccessibilityPaneTitle(title.getText());
        switch (page) {
            case ADD: addForm(); break;
            case EDIT: case REMOVE: editForm(); break;
            case OTHER: otherProfile(); break;
            case LIST: list(); break;
        }
        notice = label("", 14, muted());
        notice.setPadding(dp(4), dp(8), dp(4), 0);
        notice.setVisibility(GONE);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(notice);
        if (verification != null) message(text(R.string.chat_keys_verifying));
        updateSubmit();
    }

    private void message(String value) {
        if (notice == null) return;
        notice.setVisibility(VISIBLE);
        notice.setText(value);
        notice.announceForAccessibility(value);
    }

    private void list() {
        LinearLayout official = card();
        linkRow(official, text(R.string.chat_keys_official), () -> listener.action("official", owner));
        section(text(R.string.chat_keys_account, nickname));
        rows(ring, true);
        if (accounts.length() > 1) {
            section(text(R.string.chat_keys_other));
            LinearLayout others = card();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject profile = accounts.optJSONObject(i);
                if (profile == null || owner.equals(profile.optString("id"))
                        || !OfficialPlatformActivity.isValidWorkspaceId(profile.optString("id"))) continue;
                String id = profile.optString("id");
                if (others.getChildCount() > 0) divider(others);
                linkRow(others, profile.optString("nickname", id), () -> {
                    other = id;
                    otherRing = null;
                    read(id);
                    go(Page.OTHER);
                });
            }
        }
    }

    private ImageView chevron() {
        ImageView arrow = new ImageView(getContext());
        arrow.setImageResource(R.drawable.keys_chevron);
        arrow.setColorFilter(muted());
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return arrow;
    }

    private void linkRow(LinearLayout parent, String name, Runnable open) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.addView(label(name, 16, ink()), new LayoutParams(0, -2, 1));
        row.addView(chevron(), dp(20), dp(20));
        row.setFocusable(true);
        row.setBackground(ripple(null));
        row.setOnClickListener(v -> open.run());
        parent.addView(row, -1, -2);
    }

    private void addKeyAction(LinearLayout group) {
        Button add = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        add.setText(text(R.string.chat_keys_add));
        add.setAllCaps(false);
        add.setTextColor(accent());
        add.setTextSize(16f * percent / 100);
        add.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        add.setMinHeight(dp(48));
        add.setMinimumHeight(dp(48));
        add.setPadding(0, dp(6), 0, dp(6));
        add.setBackground(ripple(null));
        add.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.keys_add, 0, 0, 0);
        add.setCompoundDrawableTintList(ColorStateList.valueOf(accent()));
        add.setCompoundDrawablePadding(dp(8));
        add.setOnClickListener(v -> { go(Page.ADD); focusSecret(); });
        group.addView(add, -1, -2);
    }

    private void rows(JSONObject data, boolean editable) {
        LinearLayout group = card();
        if (data == null) {
            group.addView(label(text(reading ? R.string.chat_keys_reading : R.string.chat_keys_storage), 15, muted()));
            if (!reading) button(group, R.string.chat_keys_retry, false, v -> {
                read(editable ? owner : other);
                draw();
            });
            if (editable) addKeyAction(group);
            return;
        }
        JSONArray entries = data.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            TextView empty = label(text(R.string.chat_keys_empty), 15, muted());
            empty.setPadding(0, dp(10), 0, dp(10));
            group.addView(empty);
            if (editable) addKeyAction(group);
            return;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject key = entries.optJSONObject(i);
            if (key == null) continue;
            if (i != 0) divider(group);
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(62));
            LinearLayout info = column();
            TextView name = label(display(key), 16, ink());
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            info.addView(name);
            boolean active = key.optString("id").equals(data.optString("activeId"));
            String detail = masked(key) + (active ? " · " + text(R.string.chat_keys_active) : "");
            info.addView(label(detail, 14, active ? accent() : muted()));
            row.addView(info, new LayoutParams(0, -2, 1));
            if (editable) {
                row.addView(chevron(), dp(20), dp(20));
                row.setFocusable(true);
                row.setBackground(ripple(null));
                row.setContentDescription(display(key) + ", " + detail + ", " + text(R.string.chat_keys_manage));
                row.setOnClickListener(v -> { editingId = key.optString("id"); go(Page.EDIT); });
            }
            group.addView(row, -1, -2);
        }
        if (editable) {
            divider(group);
            addKeyAction(group);
        }
    }

    private void otherProfile() {
        String name = "";
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (account != null && other.equals(account.optString("id"))) name = account.optString("nickname");
        }
        if (name.isEmpty()) return;
        section(text(R.string.chat_keys_account, name));
        rows(otherRing, false);
        button(content, R.string.chat_keys_switch, true, v -> listener.action("switch", other));
    }

    private void addForm() {
        section(text(R.string.chat_keys_account, nickname));
        LinearLayout form = card();
        secretInput = field(form, R.string.chat_keys_value, true);
        secretInput.setHint(text(R.string.chat_keys_paste));
        nameInput = field(form, R.string.chat_keys_name, false);
        submit = button(content, R.string.chat_keys_verify, true, v -> verify());
    }

    private JSONObject findEditedKey() {
        JSONArray entries = ring == null ? null : ring.optJSONArray("entries");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject key = entries.optJSONObject(i);
            if (key != null && editingId.equals(key.optString("id"))) return key;
        }
        return null;
    }

    private void editForm() {
        JSONObject key = findEditedKey();
        if (key == null) return;
        String expected = ring.toString();
        boolean active = editingId.equals(ring.optString("activeId"));
        LinearLayout form = card();
        if (page == Page.REMOVE) {
            form.addView(label(text(R.string.chat_keys_remove_question, display(key)), 18, ink()));
            form.addView(label(masked(key), 14, muted()));
            form.addView(label(text(R.string.chat_keys_remove_hint), 15, muted()));
            submit = button(content, R.string.chat_keys_remove_confirm, true, v -> write("remove", editingId, "", "", expected));
            button(content, R.string.chat_keys_cancel, false, v -> back());
            return;
        }
        form.addView(label(display(key) + "   " + masked(key), 17, ink()));
        nameInput = field(form, R.string.chat_keys_name, false);
        nameInput.setText(key.optString("name"));
        submit = button(content, R.string.chat_keys_rename, true,
                v -> write("rename", editingId, nameInput.getText().toString().trim(), "", expected));
        button(content, active ? R.string.chat_keys_clear : R.string.chat_keys_use, false,
                v -> write("select", active ? "" : editingId, "", "", expected));
        Button remove = button(content, R.string.chat_keys_remove, false, v -> { if (!writing) go(Page.REMOVE); });
        remove.setTextColor(dark ? 0xffffa29b : 0xffa62d26);
    }

    private EditText field(LinearLayout parent, int labelId, boolean secret) {
        TextView caption = label(text(labelId), 14, muted());
        caption.setPadding(0, dp(12), 0, 0);
        EditText input = new EditText(getContext());
        input.setId(View.generateViewId());
        input.setSingleLine(true);
        int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if (secret) type |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
        input.setInputType(type);
        input.setTextColor(ink());
        input.setHintTextColor(muted());
        input.setBackgroundTintList(ColorStateList.valueOf(muted()));
        input.setTextSize(16f * percent / 100);
        input.setMinHeight(dp(48));
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | (secret ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE));
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(secret ? 4096 : 64)});
        caption.setLabelFor(input.getId());
        parent.addView(caption, -1, -2);
        parent.addView(input, -1, -2);
        return input;
    }

    private void focusSecret() {
        if (secretInput == null) return;
        EditText input = secretInput;
        input.requestFocus();
        input.post(() -> {
            if (!closed && input == secretInput && input.hasFocus()) {
                getContext().getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private String display(JSONObject row) {
        String name = row.optString("name");
        return name.isEmpty() ? text(R.string.chat_keys_default) : name;
    }

    private static String masked(JSONObject row) { return "•••• " + row.optString("suffix"); }

    private void section(String value) {
        TextView heading = label(value, 14, muted());
        heading.setPadding(dp(4), dp(12), dp(4), dp(7));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        content.addView(heading);
    }

    private LinearLayout card() {
        LinearLayout group = column();
        group.setBackground(shape(surface(), 14));
        int vertical = page == Page.LIST || page == Page.OTHER ? dp(2) : dp(12);
        group.setPadding(dp(16), vertical, dp(16), vertical);
        LayoutParams p = new LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        content.addView(group, p);
        return group;
    }

    private Button button(LinearLayout parent, int label, boolean primary, OnClickListener click) {
        Button b = new Button(getContext());
        b.setAllCaps(false);
        b.setText(text(label));
        b.setTextSize(16f * percent / 100);
        b.setTextColor(primary ? Color.WHITE : accent());
        b.setMinHeight(dp(48));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setStateListAnimator(null);
        b.setBackground(ripple(shape(primary ? 0xff435fda : surface(), 24)));
        b.setOnClickListener(click);
        LayoutParams p = new LayoutParams(-1, -2);
        p.topMargin = dp(8);
        parent.addView(b, p);
        return b;
    }

    private void divider(LinearLayout group) {
        View line = new View(getContext());
        line.setBackgroundColor(dark ? 0xff35373d : 0xffeaebef);
        group.addView(line, -1, dp(1));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(VERTICAL);
        return layout;
    }

    private TextView label(String value, int size, int color) {
        TextView v = new TextView(getContext());
        v.setText(value);
        v.setTextSize(size * percent / 100f);
        v.setTextColor(color);
        return v;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable ripple(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x30ffffff : 0x18000000), shape, null);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int ink() { return dark ? 0xfff2f3f5 : 0xff202329; }
    private int surface() { return dark ? 0xff202125 : Color.WHITE; }
    private int muted() { return dark ? 0xffb6bac4 : 0xff626875; }
    private int accent() { return dark ? 0xffa8b7ff : 0xff3758c9; }
    private String text(int id, Object... args) { return strings.getString(id, args); }
}
