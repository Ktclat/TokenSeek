package io.tokenseek.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class NativeProfileEditor extends LinearLayout {
    interface Listener {
        void back();
        void pickPhoto();
        void save(String user, String nickname, String avatar);
    }

    private final Resources strings;
    private final Listener listener;
    private final float scale;
    private final int foreground, muted, surface, accent;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final FrameLayout portrait;
    private final LinearLayout content;
    private final Button save, reset;
    private final TextView status;
    private final EditText username, nickname;
    private String avatar;
    private boolean closed;
    private int version;

    NativeProfileEditor(Activity context, Resources strings, JSONObject profile, boolean dark, int percent, Listener listener) {
        super(context);
        this.strings = strings;
        this.listener = listener;
        scale = percent / 100f;
        foreground = dark ? 0xfff2f3f5 : 0xff202329;
        muted = dark ? 0xffb6bac4 : 0xff626875;
        surface = dark ? 0xff202125 : Color.WHITE;
        accent = dark ? 0xffa8b7ff : 0xff3758c9;
        avatar = profile == null ? null : profile.optString("avatar", null);
        setOrientation(VERTICAL);
        setBackgroundColor(dark ? 0xff16171a : 0xfff6f7f9);
        setFocusableInTouchMode(true);
        FrameLayout bar = new FrameLayout(context);
        bar.setMinimumHeight(dp(54));
        bar.setBackgroundColor(surface);
        addView(bar, -1, -2);
        TextView title = text(profile == null ? R.string.ac_newprofile : R.string.ac_profile, 18);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(54), dp(12), dp(54), dp(12));
        bar.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        ImageButton back = new ImageButton(context);
        back.setImageResource(R.drawable.platform_back);
        back.setColorFilter(foreground);
        back.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        back.setContentDescription(strings.getText(R.string.chat_keys_back));
        back.setOnClickListener(v -> listener.back());
        bar.addView(back, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.START | Gravity.CENTER_VERTICAL));

        ScrollView scroll = new ScrollView(context);
        addView(scroll, -1, -1);
        FrameLayout frame = new FrameLayout(context);
        scroll.addView(frame);
        content = new LinearLayout(context);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        content.setOrientation(VERTICAL);
        int width = getResources().getConfiguration().screenWidthDp > 600 ? dp(600) : -1;
        frame.addView(content, new FrameLayout.LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        LinearLayout form = new LinearLayout(context);
        form.setPadding(dp(16), dp(18), dp(16), dp(18));
        form.setBackground(round(surface, 16));
        form.setOrientation(VERTICAL);
        content.addView(form, -1, -2);
        portrait = new FrameLayout(context);
        portrait.setBackground(round(0xff4a64cb, 40));
        portrait.setClipToOutline(true);
        portrait.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout.LayoutParams imageBox = new LinearLayout.LayoutParams(dp(68), dp(68));
        imageBox.gravity = Gravity.CENTER_HORIZONTAL;
        form.addView(portrait, imageBox);
        Button pick = button(R.string.ac_choose_photo, false);
        pick.setOnClickListener(v -> listener.pickPhoto());
        form.addView(pick, -1, -2);
        reset = button(R.string.ac_reset_photo, false);
        reset.setOnClickListener(v -> { version++; avatar = null; statusText(""); updateImage(); });
        form.addView(reset, -1, -2);
        username = field(form, R.string.ac_username, profile == null ? "" : profile.optString("username"));
        nickname = field(form, R.string.ac_nickname, profile == null ? "" : profile.optString("nickname"));
        nickname.setHint(strings.getText(R.string.ac_nick_hint));
        status = text(0, 14);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(GONE);
        content.addView(status, -1, -2);
        save = button(profile == null ? R.string.ac_create : R.string.ac_save, true);
        save.setOnClickListener(v -> listener.save(username.getText().toString(), nickname.getText().toString(), avatar));
        LinearLayout.LayoutParams saveBox = new LinearLayout.LayoutParams(-1, -2);
        saveBox.topMargin = dp(12);
        content.addView(save, saveBox);
        TextWatcher initials = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (avatar == null) updateImage();
            }
            public void afterTextChanged(Editable s) { }
        };
        username.addTextChangedListener(initials);
        nickname.addTextChangedListener(initials);
        updateImage();
    }

    private EditText field(LinearLayout parent, int caption, String value) {
        TextView name = text(caption, 14);
        name.setTextColor(muted);
        name.setPadding(0, dp(10), 0, 0);
        EditText field = new EditText(getContext());
        field.setId(generateViewId());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setSingleLine(true);
        field.setTextSize(16 * scale);
        field.setTextColor(foreground);
        field.setHintTextColor(muted);
        field.setBackgroundTintList(ColorStateList.valueOf(muted));
        field.setMinHeight(dp(48));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        field.setText(value);
        name.setLabelFor(field.getId());
        parent.addView(name, -1, -2);
        parent.addView(field, -1, -2);
        return field;
    }

    private Button button(int label, boolean primary) {
        Button b = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        b.setText(strings.getText(label));
        b.setAllCaps(false);
        b.setTextSize(16 * scale);
        b.setTextColor(primary ? Color.WHITE : accent);
        b.setMinHeight(dp(48));
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x303355aa),
                round(primary ? 0xff435fda : Color.TRANSPARENT, 24), null));
        return b;
    }

    private void updateImage() {
        portrait.removeAllViews();
        Bitmap image = NativeProfilePhoto.decode(avatar);
        if (image != null) {
            ImageView photo = new ImageView(getContext());
            photo.setImageBitmap(image);
            portrait.addView(photo, -1, -1);
        } else {
            TextView initials = text(0, 23);
            String name = nickname.getText().toString().trim();
            if (name.isEmpty()) name = username.getText().toString().trim();
            String initial = name.isEmpty() ? "+" : name.substring(0, name.offsetByCodePoints(0, 1));
            initials.setText(initial.toUpperCase(java.util.Locale.ROOT));
            initials.setGravity(Gravity.CENTER);
            initials.setTextColor(Color.WHITE);
            portrait.addView(initials, -1, -1);
        }
        reset.setVisibility(avatar == null ? GONE : VISIBLE);
    }

    void onPhotoResult(Uri uri) {
        if (uri == null || closed) return;
        int token = ++version;
        save.setEnabled(false);
        reset.setEnabled(false);
        statusText(strings.getString(R.string.ac_photo_busy));
        images.execute(() -> {
            String result = null;
            try { result = NativeProfilePhoto.read(getContext().getContentResolver(), uri); }
            catch (IOException | RuntimeException ignored) { }
            final String value = result;
            ((Activity) getContext()).runOnUiThread(() -> {
                if (closed || token != version) return;
                save.setEnabled(true);
                reset.setEnabled(true);
                if (value == null) statusText(strings.getString(R.string.ac_photo_error));
                else {
                    avatar = value;
                    statusText("");
                    updateImage();
                }
            });
        });
    }

    void statusText(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    }

    void close() {
        closed = true;
        images.shutdownNow();
        InputMethodManager ime = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }
    private TextView text(int id, int size) {
        TextView view = new TextView(getContext());
        if (id != 0) view.setText(strings.getText(id));
        view.setTextSize(size * scale);
        view.setTextColor(foreground);
        return view;
    }
    private int dp(int n) {return Math.round(n * getResources().getDisplayMetrics().density);}
}
