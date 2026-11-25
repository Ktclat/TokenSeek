package io.tokenseek.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Recycles message rows while a streamed answer grows and while reading long conversations. */
final class NativeMessageList extends BaseAdapter {
    private final NativeConversation ui;
    private final Set<String> expanded = new HashSet<>();
    private final Set<String> showingSources = new HashSet<>();
    private JSONArray rows = new JSONArray();
    private String tail = "";
    private boolean busy, changed;

    NativeMessageList(NativeConversation ui) { this.ui = ui; }

    void update(JSONArray messages, boolean streaming, boolean reset) {
        JSONObject last = messages.optJSONObject(messages.length()-1);
        String signature = last == null ? "" : last.toString();
        changed = reset || messages.length() != rows.length() || busy != streaming || !signature.equals(tail);
        boolean rebind = changed || messages != rows;
        rows = messages;
        tail = signature;
        busy = streaming;
        if (reset) { expanded.clear(); showingSources.clear(); }
        if (rebind) notifyDataSetChanged();
    }

    boolean changed() { return changed; }
    @Override public int getCount() { return rows.length(); }
    @Override public JSONObject getItem(int position) { return rows.optJSONObject(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) {
        JSONObject item = getItem(position);
        return item != null && "user".equals(item.optString("role")) ? 0 : 1;
    }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        Cell cell = recycled == null ? new Cell(getItemViewType(position) == 0) : (Cell)recycled;
        JSONObject item = getItem(position);
        if (item != null) cell.bind(item,position);
        return cell;
    }

    private final class Cell extends LinearLayout {
        final boolean user;
        final LinearLayout bubble, actions;
        final TextView body, files, reasoning, reasonToggle, sourcesToggle, sourcesView, error;
        NativeChatSources sources;
        String id = "", bodyValue = "", reasoningValue = "", actionKey = "", prev = "", next = "";
        boolean formatted;
        Cell(boolean user) {
            super(ui.getContext());
            this.user = user;
            setOrientation(VERTICAL); setGravity(user ? Gravity.END : Gravity.START);
            setPadding(ui.dp(user ? 24 : 0),ui.dp(4),0,ui.dp(5));
            bubble = ui.column();
            if (user) {
                bubble.setBackground(ui.fill(ui.subtle(),18,0));
                bubble.setPadding(ui.dp(14),ui.dp(8),ui.dp(14),ui.dp(8));
            }
            addView(bubble,new LayoutParams(user ? -2 : -1,-2));
            files = ui.text("",13,ui.muted(),false); files.setTextIsSelectable(true); bubble.addView(files);
            reasonToggle = ui.text("",13,ui.muted(),true);
            reasonToggle.setMinHeight(ui.dp(44)); reasonToggle.setGravity(Gravity.CENTER_VERTICAL);
            reasonToggle.setFocusable(true);
            reasonToggle.setOnClickListener(v -> {
                if (!expanded.remove(id)) expanded.add(id);
                showReasoning();
            });
            bubble.addView(reasonToggle);
            reasoning = ui.text("",14,ui.muted(),false); reasoning.setTextIsSelectable(true);
            reasoning.setPadding(ui.dp(12),0,0,ui.dp(6)); bubble.addView(reasoning);
            body = ui.text("",16,ui.ink(),false); body.setTextIsSelectable(true);
            body.setLineSpacing(ui.dp(2),1.04f); body.setLinkTextColor(ui.accent());
            if (!user) body.setMovementMethod(LinkMovementMethod.getInstance());
            bubble.addView(body);
            sourcesToggle = ui.text("",13,ui.accent(),true);
            sourcesToggle.setMinHeight(ui.dp(44)); sourcesToggle.setGravity(Gravity.CENTER_VERTICAL); sourcesToggle.setFocusable(true);
            sourcesToggle.setOnClickListener(v -> {
                if (!showingSources.remove(id)) showingSources.add(id);
                showSources();
            });
            bubble.addView(sourcesToggle);
            sourcesView = ui.text("",14,ui.ink(),false); sourcesView.setLinkTextColor(ui.accent());
            sourcesView.setMovementMethod(LinkMovementMethod.getInstance()); bubble.addView(sourcesView);
            error = ui.text("",14,ui.muted(),false); error.setTextIsSelectable(true); bubble.addView(error);
            actions = ui.row(); addView(actions,new LayoutParams(-2,-2));
        }

        void bind(JSONObject message, int position) {
            String previousId = id;
            id = message.optString("id",String.valueOf(position));
            String status = NativeConversation.string(message,"status");
            boolean live = "streaming".equals(status) || "pending".equals(status)
                    || (!user && busy && position == getCount()-1);
            StringBuilder answer = new StringBuilder(NativeConversation.string(message,"content"));
            StringBuilder thought = new StringBuilder(NativeConversation.string(message,"reasoning"));
            JSONArray blocks = message.optJSONArray("blocks");
            if (!user && blocks != null) {
                if (blocks.length() > 0) { answer.setLength(0); thought.setLength(0); }
                for (int i=0;i<blocks.length();i++) {
                    JSONObject block = blocks.optJSONObject(i);
                    if (block == null) continue;
                    if ("text".equals(block.optString("type"))) appendPart(answer,NativeConversation.string(block,"text"));
                    if ("thinking".equals(block.optString("type"))) appendPart(thought,NativeConversation.string(block,"thinking"));
                }
            }
            String content = answer.toString();
            boolean useMarkdown = !user && !live;
            if (!content.equals(bodyValue) || useMarkdown != formatted || !previousId.equals(id)) {
                if (!useMarkdown && !formatted && !bodyValue.isEmpty() && previousId.equals(id) && content.startsWith(bodyValue)) {
                    body.append(content.substring(bodyValue.length()));
                } else body.setText(useMarkdown ? NativeChatMarkdown.render(content,ui.subtle()) : content);
                bodyValue = content;
                formatted = useMarkdown;
            }
            body.setVisibility(content.isEmpty() ? GONE : VISIBLE);
            sources = NativeChatSources.from(blocks);
            showSources();
            reasoningValue = thought.toString();
            showReasoning();
            StringBuilder list = new StringBuilder();
            JSONArray attached = message.optJSONArray("files");
            if (attached != null) for (int i=0;i<attached.length();i++) {
                JSONObject file = attached.optJSONObject(i);
                if (file != null) appendPart(list,NativeConversation.string(file,"name"));
            }
            files.setText(list); files.setVisibility(list.length() == 0 ? GONE : VISIBLE);
            JSONObject fault = message.optJSONObject("error");
            String note = fault == null ? NativeConversation.string(message,"error") : NativeConversation.string(fault,"message");
            if (note.isEmpty() && "error".equals(status)) note = ui.word("生成失败","Couldn’t generate a reply");
            if (note.isEmpty() && live && content.isEmpty() && thought.length()==0) note = ui.word("正在生成…","Generating…");
            if (note.isEmpty() && "stopped".equals(status) && content.isEmpty()) note = ui.word("已停止生成","Generation stopped");
            error.setText(note); error.setVisibility(note.isEmpty() ? GONE : VISIBLE);
            prev = NativeConversation.string(message,"prevId"); next = NativeConversation.string(message,"nextId");
            boolean canRetry = !user && !busy && message.optBoolean("canRetry",true);
            boolean canContinue = !busy && message.optBoolean("canContinue");
            boolean canSpeak = !content.isEmpty() && message.optBoolean("canSpeak");
            boolean canEdit = user && !busy && message.optBoolean("canEdit");
            String key = content.isEmpty()+"/"+canRetry+"/"+canContinue+"/"+canSpeak+"/"+canEdit+"/"+prev+"/"+next;
            if (!key.equals(actionKey)) {
                actionKey = key;
                actions.removeAllViews();
                if (!content.isEmpty()) tool("copy",ui.word("复制消息","Copy message"),v -> copy());
                if (canEdit) tool("edit",ui.word("编辑消息","Edit message"),v -> ui.emit("edit",id));
                if (canRetry) tool("retry",ui.word("重新生成","Regenerate"),v -> ui.emit("retry",id));
                if (canSpeak) tool("speak",ui.word("朗读","Read aloud"),v -> ui.emit("speak",id));
                if (!prev.isEmpty()) tool("prev",ui.word("上一个版本","Previous version"),v -> ui.emit("prev",prev));
                if (!next.isEmpty()) tool("next",ui.word("下一个版本","Next version"),v -> ui.emit("next",next));
                if (canContinue) {
                    TextView more = ui.text(ui.word("继续生成","Continue"),14,ui.accent(),true);
                    more.setGravity(Gravity.CENTER); more.setMinHeight(ui.dp(44)); more.setFocusable(true);
                    more.setOnClickListener(v -> ui.emit("continue",id)); actions.addView(more);
                }
                actions.setVisibility(actions.getChildCount() == 0 ? GONE : VISIBLE);
            }
        }

        void showSources() {
            int count = sources.count();
            boolean open = count > 0 && showingSources.contains(id);
            sourcesToggle.setVisibility(count > 0 ? VISIBLE : GONE);
            sourcesToggle.setText((open ? "▾  " : "▸  ") + ui.word("来源","Sources") + " · " + count);
            sourcesView.setVisibility(open ? VISIBLE : GONE);
            if (open) sourcesView.setText(sources.text());
        }

        void showReasoning() {
            boolean has = !reasoningValue.isEmpty(), open = expanded.contains(id);
            reasonToggle.setVisibility(has ? VISIBLE : GONE);
            reasoning.setVisibility(has && open ? VISIBLE : GONE);
            reasonToggle.setText((open ? "▾  " : "▸  ") + ui.word("思考过程","Reasoning"));
            if (has && open && !TextUtils.equals(reasoning.getText(),reasoningValue)) reasoning.setText(reasoningValue);
        }

        void tool(String icon,String name,OnClickListener action) {
            NativeChatIcon view = ui.action(icon,name,action);
            view.setIcon(icon,ui.muted());
            actions.addView(view,ui.dp(44),ui.dp(44));
        }

        void copy() {
            ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
            if (clipboard == null || bodyValue.isEmpty()) return;
            try { clipboard.setPrimaryClip(ClipData.newPlainText(ui.word("消息","Message"),bodyValue)); }
            catch (RuntimeException unavailable) {
                Toast.makeText(getContext(),ui.word("请长按正文选取需要复制的文字。","Select the text to copy a smaller portion."),Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(getContext(),ui.word("已复制","Copied"),Toast.LENGTH_SHORT).show();
        }
    }

    private static void appendPart(StringBuilder buffer, String value) {
        if (value.isEmpty()) return;
        if (buffer.length() > 0) buffer.append('\n');
        buffer.append(value);
    }
}
