package io.tokenseek.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native Android chat surface. The host owns account, history, drafts and active requests. */
final class NativeConversation extends FrameLayout {
    interface Listener { void action(String action, String value); }
    static final String FLASH = "deepseek-flash";
    static final String PRO = "deepseek-v4-pro";
    private final Listener listener;
    private final NativeConversationDrawer drawer;
    private final JSONArray emptyMessages = new JSONArray();
    private LinearLayout page, fileLine;
    private TextView title, greeting, flash, pro, constraint, filename;
    private TextView think, search;
    private NativeChatIcon meter, send, attach, voice;
    private EditText editor;
    private ListView list;
    private NativeMessageList adapter;
    private JSONObject state = new JSONObject();
    private String sessionId = "";
    private boolean dark, english, editing, streaming, requiresFlash, isPro, hasAttachment;
    private float scale = 1f;

    NativeConversation(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        drawer = new NativeConversationDrawer(context, listener);
        addView(drawer, new LayoutParams(-1,-1));
    }

    boolean back() { return drawer.back(); }
    String draft() { return editor == null ? "" : editor.getText().toString(); }

    void render(JSONObject next) {
        if (next == null) return;
        boolean nextDark = next.optBoolean("dark");
        boolean nextEnglish = next.has("locale") ? next.optString("locale").startsWith("en")
                : next.optBoolean("english", getResources().getConfiguration().getLocales().get(0).getLanguage().equals("en"));
        float nextScale = Math.max(0.9f,Math.min(1.3f,next.optInt("scale",100)/100f));
        String id = string(next,"sessionId");
        boolean newSession = page == null || !sessionId.equals(id);
        boolean rebuild = page == null || nextDark != dark || nextEnglish != english || nextScale != scale;
        boolean focused = editor != null && editor.hasFocus();
        String fallbackDraft = newSession ? "" : draft();
        int cursor = editor == null ? 0 : editor.getSelectionEnd();
        state = next;
        dark = nextDark;
        english = nextEnglish;
        scale = nextScale;
        sessionId = id;
        if (rebuild) build();
        streaming = state.optBoolean("streaming");
        requiresFlash = state.optBoolean("needsFlash");
        isPro = PRO.equals(state.optString("model"));
        String attachment = string(state, "attachment");
        hasAttachment = !attachment.isEmpty();
        String draft = state.isNull("draft") ? fallbackDraft : state.optString("draft");
        if (!draft.contentEquals(editor.getText())) {
            editing = true;
            editor.setText(draft);
            boolean keepPosition = focused && !newSession && cursor < fallbackDraft.length();
            editor.setSelection(keepPosition ? Math.min(Math.max(0,cursor),draft.length()) : draft.length());
            editing = false;
        }
        if (focused && rebuild && !newSession) editor.requestFocus();
        String name = string(state,"title");
        title.setText(name.isEmpty() ? "TokenSeek" : name);
        styleChip(flash,!isPro,true);
        styleChip(pro,isPro,true);
        enabled(flash,!streaming);
        enabled(pro,!streaming && !requiresFlash && !hasAttachment);
        constraint.setVisibility(requiresFlash || hasAttachment ? VISIBLE : GONE);
        filename.setText(attachment);
        fileLine.setVisibility(hasAttachment ? VISIBLE : GONE);
        styleChip(think,state.optBoolean("thinking"),false);
        styleChip(search,!isPro && state.optBoolean("search"),false);
        enabled(search,!isPro);
        enabled(attach,!isPro && !streaming);
        enabled(voice,!streaming);
        updateSend();
        String label = state.optString("contextLabel","");
        int remaining = state.optInt("contextPercent",-1);
        if (label.isEmpty()) label = remaining < 0 ? "—" : remaining + "%";
        if (remaining < 0 && label.endsWith("%")) {
            try { remaining = Integer.parseInt(label.substring(0,label.length()-1).replace("<","").replace(">","")); }
            catch (NumberFormatException ignored) { remaining = -1; }
        }
        meter.setMeter(label,Math.min(100,remaining));
        meter.setContentDescription(word("剩余上下文（估算）","Estimated context remaining") + " " + label + " · " + word("查看用量","View usage"));
        JSONArray messages = state.optJSONArray("messages");
        if (messages == null) messages = emptyMessages;
        boolean atEnd = !list.canScrollVertically(1);
        adapter.update(messages,streaming,newSession || rebuild);
        list.setVisibility(messages.length() == 0 ? GONE : VISIBLE);
        greeting.setVisibility(messages.length() == 0 ? VISIBLE : GONE);
        if ((newSession || (atEnd && adapter.changed())) && messages.length() > 0) list.setSelection(messages.length()-1);
        drawer.render(state);
    }

    private void build() {
        page = column();
        page.setBackgroundColor(surface());
        page.setFocusableInTouchMode(true);
        drawer.setContent(page);
        LinearLayout header = row();
        header.setPadding(dp(4),0,dp(4),0);
        page.addView(header,-1,dp(52));
        header.addView(action("drawer",word("打开侧栏","Open sidebar"),view -> drawer.open()),dp(48),dp(48));
        title = text("",17,ink(),true);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(6),0,dp(8),0);
        header.addView(title,weight());
        header.addView(action("new",word("新对话","New chat"),view -> emit("new","")),dp(48),dp(48));
        FrameLayout transcript = new FrameLayout(getContext());
        page.addView(transcript,new LinearLayout.LayoutParams(-1,0,1));
        list = new ListView(getContext());
        list.setDivider(null); list.setCacheColorHint(Color.TRANSPARENT); list.setItemsCanFocus(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        list.setSelector(android.R.color.transparent);
        list.setPadding(dp(16),dp(12),dp(16),dp(8));
        list.setClipToPadding(false);
        list.setContentDescription(word("对话消息","Conversation messages"));
        transcript.addView(list,new LayoutParams(-1,-1));
        adapter = new NativeMessageList(this);
        list.setAdapter(adapter);
        greeting = text(word("今天有什么可以帮到你？","How can I help you?"),21,ink(),true);
        greeting.setPadding(dp(20),0,dp(20),0); greeting.setGravity(Gravity.CENTER);
        transcript.addView(greeting,new LayoutParams(-1,-2,Gravity.CENTER));
        buildComposer();
        page.requestFocus();
    }

    private void buildComposer() {
        LinearLayout composer = column();
        composer.setPadding(dp(8),dp(2),dp(8),dp(4));
        composer.setBackground(fill(surface(),20,outline()));
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1,-2);
        box.setMargins(dp(12),dp(3),dp(12),dp(8)); page.addView(composer,box);
        LinearLayout models = row();
        composer.addView(models,-1,-2);
        flash = chip("Flash",word("Flash 模型","Flash model"),v -> emit("model",FLASH));
        pro = chip("Pro",word("Pro 模型","Pro model"),v -> emit("model",PRO));
        models.addView(flash); models.addView(pro); models.addView(new View(getContext()),new LinearLayout.LayoutParams(0,1,1));
        meter = action("meter",word("对话用量","Conversation usage"),v -> emit("usage",""));
        meter.setIcon("meter",accent()); models.addView(meter,dp(44),dp(44));
        constraint = text(word("当前附件或联网记录需使用 Flash。","Use Flash with files or web results."),12,muted(),false);
        constraint.setPadding(dp(6),0,dp(6),dp(3)); composer.addView(constraint);

        fileLine = row(); fileLine.setPadding(dp(6),0,0,0);
        filename = text("",13,ink(),false); filename.setSingleLine(); filename.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        fileLine.addView(filename,weight());
        fileLine.addView(action("remove",word("移除附件","Remove attachment"),v -> emit("removeAttachment","")),dp(44),dp(44));
        composer.addView(fileLine,-1,-2);
        editor = new EditText(getContext());
        editor.setTextSize(16*scale); editor.setTextColor(ink()); editor.setHintTextColor(muted());
        editor.setHint(word("请输入消息","Message TokenSeek"));
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setMinLines(1); editor.setMaxLines(5); editor.setMinHeight(dp(48));
        editor.setGravity(Gravity.TOP|Gravity.START); editor.setPadding(dp(6),dp(8),dp(6),dp(8));
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE);
        editor.setOnEditorActionListener((v,id,event) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed())) {
                if (!streaming && send.isEnabled()) submit();
                return true;
            }
            return false;
        });
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value,int start,int count,int after) {}
            public void afterTextChanged(Editable value) {}
            public void onTextChanged(CharSequence value,int start,int before,int count) {
                if (!editing) { updateSend(); emit("draft",value.toString()); }
            }
        });
        composer.addView(editor,-1,-2);
        LinearLayout tools = row(); composer.addView(tools,-1,-2);
        boolean compact = getResources().getConfiguration().screenWidthDp < 380 || scale > 1.05f;
        think = chip(compact ? word("思考","Think") : word("深度思考","DeepThink"),word("深度思考","Deep thinking"),
                v -> emit("thinking",String.valueOf(!state.optBoolean("thinking"))));
        search = chip(compact ? word("搜索","Search") : word("联网搜索","Search"),word("联网搜索","Web search"),
                v -> emit("search",String.valueOf(!state.optBoolean("search"))));
        tools.addView(think); tools.addView(search);
        tools.addView(new View(getContext()),new LinearLayout.LayoutParams(0,1,1));
        voice = action("voice",word("语音输入","Voice input"),v -> emit("voice","")); tools.addView(voice,dp(42),dp(44));
        attach = action("attach",word("添加附件","Attach file"),v -> emit("attach","")); tools.addView(attach,dp(42),dp(44));
        send = action("send",word("发送","Send"),v -> submit()); tools.addView(send,dp(44),dp(44));
    }

    private void submit() {
        if (streaming) emit("stop","");
        else if (!draft().trim().isEmpty() || hasAttachment) emit("send",draft());
    }

    private void updateSend() {
        if (send == null) return;
        if (!Boolean.valueOf(streaming).equals(send.getTag())) {
            send.setTag(streaming);
            send.setContentDescription(streaming ? word("停止生成","Stop generating") : word("发送","Send"));
            send.setIcon(streaming ? "stop" : "send",Color.WHITE);
            GradientDrawable bg = fill(accent(),24,0);
            android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(bg,dp(4));
            send.setBackground(new RippleDrawable(ColorStateList.valueOf(0x224c6fff),inset,null));
        }
        enabled(send,streaming || ((!isPro || !(requiresFlash || hasAttachment)) && (!draft().trim().isEmpty() || hasAttachment)));
    }

    private TextView chip(String label, String description, OnClickListener click) {
        TextView view = text(label,14,ink(),false);
        view.setGravity(Gravity.CENTER); view.setMinWidth(dp(44)); view.setMinHeight(dp(44));
        view.setPadding(dp(6),0,dp(6),0); view.setMaxLines(1); view.setEllipsize(TextUtils.TruncateAt.END);
        view.setContentDescription(description); view.setFocusable(true); view.setOnClickListener(click);
        return view;
    }

    private void styleChip(TextView view, boolean active, boolean model) {
        if (view.getBackground() != null && view.isSelected() == active) return;
        view.setSelected(active); view.setTextColor(active ? accent() : model ? muted() : ink());
        view.setTypeface(Typeface.DEFAULT,active ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable bg = fill(active ? (dark ? 0xff242e50 : 0xffedf1ff) : Color.TRANSPARENT,18,model ? 0 : outline());
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x224c6fff),
                new android.graphics.drawable.InsetDrawable(bg,0,dp(6),dp(2),dp(6)),null));
        view.setPadding(dp(8),0,dp(8),0);
    }

    NativeChatIcon action(String icon, String name, OnClickListener click) {
        NativeChatIcon button = new NativeChatIcon(getContext(),icon,ink());
        button.setContentDescription(name);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x224c6fff),null,fill(Color.WHITE,24,0)));
        button.setOnClickListener(click);
        return button;
    }

    private void enabled(View view, boolean value) { view.setEnabled(value); view.setAlpha(value ? 1f : .43f); }
    void emit(String event, String value) { listener.action(event,value); }
    int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    int ink() { return dark ? 0xfff2f2f4 : 0xff1b1b20; }
    int muted() { return dark ? 0xffaaaab2 : 0xff656671; }
    int surface() { return dark ? 0xff171719 : Color.WHITE; }
    int subtle() { return dark ? 0xff282a32 : 0xffeef2fa; }
    int outline() { return dark ? 0xff3c3d46 : 0xffdfe1e7; }
    int accent() { return dark ? 0xff8ca4ff : 0xff405fdb; }
    String word(String zh,String en) { return english ? en : zh; }
    static String string(JSONObject obj, String key) { return obj.isNull(key) ? "" : obj.optString(key); }
    LinearLayout row() { LinearLayout out = new LinearLayout(getContext()); out.setGravity(Gravity.CENTER_VERTICAL); return out; }
    LinearLayout column() { LinearLayout out = new LinearLayout(getContext()); out.setOrientation(LinearLayout.VERTICAL); return out; }
    TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size*scale);
        view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    GradientDrawable fill(int color, int radius, int stroke) {
        GradientDrawable out = new GradientDrawable(); out.setColor(color); out.setCornerRadius(dp(radius));
        if (stroke != 0) out.setStroke(dp(1),stroke); return out;
    }
    static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0,-2,1); }
}
