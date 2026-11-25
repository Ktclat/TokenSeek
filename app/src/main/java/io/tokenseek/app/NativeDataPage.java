package io.tokenseek.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.sqlite.SQLiteFullException;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Document-picker requests and import/export jobs stay bound to this page's original profile. */
final class NativeDataPage extends FrameLayout {
    static final int OPEN = 610;
    static final int CREATE = 611;
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor();
    private static final String IMPORTED = "history_imported_accounts";
    private final NativeHistoryStore store;
    private final Activity activity;
    private final Runnable imported;
    private final boolean english;
    private final boolean dark;
    private final float scale;
    private final TextView status;
    private final TextView migration;
    private final Button legacyButton;
    private Runnable importLegacy;
    private BooleanSupplier importAllowed = () -> true;
    private boolean hasHandoff;
    private final LinearLayout confirmation;
    private final ArrayList<Button> actions = new ArrayList<>();
    private int waitingFor;
    private String singleId;
    private Uri selected;
    private boolean replacing;
    private NativeHistoryTransfers.Job transfer;
    private final NativeHistoryTransfers.Observer observer = this::transferChanged;

    NativeDataPage(Activity activity, NativeHistoryStore store, boolean english, boolean dark, int fontPercent,
                   Runnable back, Runnable imported) {
        super(activity);
        this.activity = activity;
        this.store = store;
        this.english = english;
        this.dark = dark;
        this.imported = imported;
        scale = Math.max(90, Math.min(130, fontPercent)) / 100f;
        setBackgroundColor(dark ? 0xff16171a : 0xfff6f7f9);
        LinearLayout root = column();
        addView(root, -1, -1);
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(surface());
        root.addView(bar, -1, -2);
        ImageButton previous = new ImageButton(activity);
        previous.setImageResource(R.drawable.platform_back);
        previous.setColorFilter(ink());
        previous.setContentDescription(t("返回", "Back"));
        previous.setBackground(touch(null));
        previous.setOnClickListener(v -> back.run());
        bar.addView(previous, dp(54), dp(54));
        TextView title = text(t("数据管理", "Data management"), 18, ink(), true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(new View(activity), dp(54), 1);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
            setAccessibilityPaneTitle(title.getText());
        }
        ScrollView scroll = new ScrollView(activity);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout center = new FrameLayout(activity);
        scroll.addView(center);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        int width = getResources().getConfiguration().screenWidthDp > 620 ? dp(600) : -1;
        center.addView(body, new LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        LinearLayout backup = card(body);
        backup.addView(text(t("导出备份", "Export backup"), 16, ink(), true));
        backup.addView(text(t("本账户的对话、附件和项目将保存为 ZIP 文件。",
                "Save this profile’s conversations, attachments and projects in a ZIP file."), 14, muted(), false), gap(6));
        action(backup, t("导出全部对话", "Export all conversations"), false, v -> start(CREATE, false, null));

        LinearLayout restore = card(body);
        restore.addView(text(t("导入备份", "Import backup"), 16, ink(), true));
        restore.addView(text(t("合并时保留已有对话，跳过相同 ID 的记录。",
                "Merging keeps existing conversations and skips matching IDs."), 14, muted(), false), gap(6));
        action(restore, t("选择 ZIP 并合并", "Choose ZIP to merge"), false, v -> start(OPEN, false, null));
        action(restore, t("从 ZIP 替换全部对话…", "Replace all from ZIP…"), true, v -> start(OPEN, true, null));
        confirmation = column();
        confirmation.setVisibility(GONE);
        restore.addView(confirmation, gap(8));
        confirmation.addView(text(t("替换当前账户的全部对话、项目和草稿？已有数据将被备份内容覆盖。",
                "Replace all conversations, projects and drafts in this profile with the selected backup?"), 15, ink(), true));
        Button confirm = button(t("确认替换", "Confirm replacement"), true, v -> {
            if (executing() || selected == null) return;
            Uri uri = selected;
            clearSelection();
            perform(OPEN, uri, true, null);
        });
        confirmation.addView(confirm, gap(4));
        confirmation.addView(button(t("取消", "Cancel"), false, v -> {
            clearSelection();
            setControls(true);
        }), gap(2));
        status = text("", 14, muted(), false);
        status.setVisibility(GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status, gap(12));
        migration = text(t("旧版记录仍在这台设备。覆盖安装迁移版，准备迁移后再覆盖回原生版；已有 ZIP 可直接合并。",
                "Earlier records are on this device. Install the migration build over this app, prepare your data, then reinstall the native build. You can also merge an existing ZIP here."),
                14, muted(), false);
        migration.setVisibility(GONE);
        body.addView(migration, gap(12));
        legacyButton = button(t("导入旧版数据", "Import previous app data"), false, v -> {
            if (!isBusy() && importLegacy != null && accountMatches() && mayImport()) importLegacy.run();
        });
        legacyButton.setVisibility(GONE);
        actions.add(legacyButton);
        body.addView(legacyButton, gap(4));
        FILES.execute(() -> {
            boolean reminder = NativeHistoryStore.hasLegacyStorage(activity)
                    && !NativeLegacyImport.completed(activity)
                    && !activity.getSharedPreferences(IMPORTED, Context.MODE_PRIVATE).getBoolean(store.workspace, false);
            boolean handoff = NativeLegacyImport.pending(activity);
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                hasHandoff = handoff;
                migration.setVisibility(reminder && !handoff ? VISIBLE : GONE);
                legacyButton.setVisibility(handoff && importLegacy != null ? VISIBLE : GONE);
            });
        });
    }

    void onLegacyImport(Runnable callback) {
        importLegacy = callback;
        legacyButton.setVisibility(hasHandoff && callback != null ? VISIBLE : GONE);
    }

    void guardImport(BooleanSupplier allowed) { importAllowed = allowed; }
    boolean executing() { return NativeHistoryTransfers.active(store.workspace) != null; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NativeHistoryTransfers.observe(observer);
        NativeHistoryTransfers.Job job = NativeHistoryTransfers.active(store.workspace);
        if (job != null) transferChanged(job);
        else if (transfer != null) transferChanged(transfer);
    }

    @Override protected void onDetachedFromWindow() {
        NativeHistoryTransfers.forget(observer);
        super.onDetachedFromWindow();
    }

    private void transferChanged(NativeHistoryTransfers.Job job) {
        if (!job.affects(store.workspace)) return;
        if (!job.finished) {
            transfer = job;
            setControls(false);
            feedback(job.kind == NativeHistoryTransfers.Kind.EXPORT ? t("正在导出…", "Exporting…") : t("正在导入…", "Importing…"));
        } else if (transfer == job) {
            transfer = null;
            setControls(true);
            feedback(job.message);
            if (job.changed) {
                migration.setVisibility(GONE);
                imported.run();
            }
        }
    }

    private boolean mayImport() {
        if (importAllowed.getAsBoolean()) return true;
        feedback(t("请先停止正在生成的回复，或等待附件传输完成。", "Stop the current reply or wait for the file transfer to finish."));
        return false;
    }

    void exportSingle(String id) {
        if (id == null || id.isEmpty()) {
            feedback(t("请选择要导出的对话。", "Choose a conversation to export."));
            return;
        }
        start(CREATE, false, id);
    }

    boolean isBusy() { return executing() || waitingFor != 0 || selected != null; }

    Bundle state() {
        Bundle saved = new Bundle();
        saved.putString("workspace", store.workspace);
        saved.putInt("picker", waitingFor);
        saved.putString("single", singleId);
        saved.putBoolean("replace", replacing);
        NativeHistoryTransfers.Job job = NativeHistoryTransfers.active(store.workspace);
        if (job != null) saved.putString("operation", job.id);
        if (selected != null) saved.putString("confirmation", selected.toString());
        return saved;
    }

    void restoreState(Bundle saved) {
        if (saved == null || !store.workspace.equals(saved.getString("workspace")) || isBusy()) return;
        String operation = saved.getString("operation");
        if (operation != null || saved.getBoolean("running")) {
            transfer = NativeHistoryTransfers.find(store.workspace, operation);
            if (transfer != null) { transferChanged(transfer); return; }
            feedback(t("请检查上次导入或导出的结果，再决定是否重试。", "Check the result of the last transfer before retrying."));
            return;
        }
        int picker = saved.getInt("picker");
        if (picker == CREATE || picker == OPEN) {
            waitingFor = picker;
            replacing = picker == OPEN && saved.getBoolean("replace");
            singleId = picker == CREATE ? saved.getString("single") : null;
            setControls(false);
        } else if (saved.getBoolean("replace") && saved.containsKey("confirmation")) {
            Uri candidate = Uri.parse(saved.getString("confirmation"));
            if (!ContentResolver.SCHEME_CONTENT.equals(candidate.getScheme())) return;
            replacing = true;
            selected = candidate;
            setControls(false);
            confirmation.setVisibility(VISIBLE);
        }
    }

    private void start(int request, boolean replace, String single) {
        if (executing() || waitingFor != 0 || selected != null || !accountMatches()) return;
        if (request == OPEN && !mayImport()) return;
        String title = single == null ? "TokenSeek-backup.zip" : "TokenSeek-conversation.zip";
        Intent picker = request == OPEN
                ? new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                : new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").putExtra(Intent.EXTRA_TITLE, title);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        waitingFor = request;
        replacing = replace;
        singleId = single;
        setControls(false);
        feedback("");
        try { activity.startActivityForResult(picker, request); }
        catch (ActivityNotFoundException | SecurityException failed) {
            waitingFor = 0;
            setControls(true);
            feedback(t("无法打开系统文件选择器。", "Could not open the system file picker."));
        }
    }

    void result(int requestCode, int result, Intent data) {
        if ((requestCode != CREATE && requestCode != OPEN) || requestCode != waitingFor) return;
        waitingFor = 0;
        setControls(true);
        if (result == Activity.RESULT_CANCELED || !accountMatches()) return;
        Uri uri = data == null ? null : data.getData();
        if (result != Activity.RESULT_OK || uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            feedback(t("无法访问所选文件，请重新选择。", "The selected file is unavailable. Choose it again."));
            return;
        }
        if (requestCode == OPEN && replacing) {
            selected = uri;
            setControls(false);
            confirmation.setVisibility(VISIBLE);
            confirmation.getChildAt(0).announceForAccessibility(((TextView) confirmation.getChildAt(0)).getText());
        } else {
            perform(requestCode, uri, false, singleId);
        }
    }

    private void perform(int operation, Uri uri, boolean replace, String single) {
        if (!accountMatches()) return;
        if (operation == OPEN && !mayImport()) {
            setControls(true);
            return;
        }
        NativeHistoryTransfers.Job job = NativeHistoryTransfers.begin(store.workspace,
                operation == OPEN ? NativeHistoryTransfers.Kind.IMPORT : NativeHistoryTransfers.Kind.EXPORT);
        if (job == null) {
            setControls(!executing());
            feedback(t("请等待当前传输完成，或先停止正在生成的回复。", "Wait for the current transfer or stop the active reply first."));
            return;
        }
        transferChanged(job);
        Context context = activity.getApplicationContext();
        NativeHistoryStore destination = store;
        boolean useEnglish = english;
        FILES.execute(() -> transfer(context, destination, job, uri, replace, single, useEnglish));
    }

    private static void transfer(Context context, NativeHistoryStore store, NativeHistoryTransfers.Job job, Uri uri,
                                 boolean replace, String single, boolean english) {
        String outcome;
        boolean changed = false;
        try {
            ContentResolver documents = context.getContentResolver();
            if (job.kind == NativeHistoryTransfers.Kind.IMPORT) {
                try (InputStream input = documents.openInputStream(uri)) {
                    if (input == null) throw new IOException("Unavailable input");
                    int count = store.importZip(input, replace);
                    outcome = replace ? choose(english, "已替换，恢复了 " + count + " 个对话。", "Backup restored. Conversations restored: " + count)
                            : choose(english, "已合并，新增 " + count + " 个对话。", "Merge complete. Conversations added: " + count);
                    changed = true;
                }
                context.getSharedPreferences(IMPORTED, Context.MODE_PRIVATE).edit().putBoolean(store.workspace, true).apply();
            } else {
                export(context, store, documents, uri, single);
                outcome = choose(english, "备份已保存。", "Backup saved.");
            }
        } catch (SQLiteFullException noSpace) {
            outcome = choose(english, "存储空间不足，请腾出空间后重试。", "Storage is full. Free up space and try again.");
        } catch (SecurityException denied) {
            outcome = choose(english, "文件授权已失效，请重新选择文件。", "File access expired. Choose the file again.");
        } catch (IOException | RuntimeException failed) {
            outcome = job.kind == NativeHistoryTransfers.Kind.IMPORT
                    ? choose(english, "导入失败，请检查 ZIP 格式、文件完整性和可用存储空间。", "Import failed. Check the backup format, file integrity and available storage.")
                    : choose(english, "导出失败，请检查保存位置和可用存储空间。", "Export failed. Check the destination and available storage.");
        }
        String message = outcome;
        boolean success = changed;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> NativeHistoryTransfers.finish(job, success, message));
    }

    private static void export(Context context, NativeHistoryStore store, ContentResolver documents, Uri target, String single) throws IOException {
        // Finish encoding locally before opening the user's chosen destination for writing.
        File temp = File.createTempFile("history-export-", ".zip", context.getCacheDir());
        try {
            try (OutputStream cache = new FileOutputStream(temp)) { store.exportZip(cache, single); }
            try (InputStream in = new FileInputStream(temp); OutputStream out = documents.openOutputStream(target, "wt")) {
                if (out == null) throw new IOException("Unavailable destination");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        } finally {
            if (!temp.delete()) temp.deleteOnExit();
        }
    }

    private boolean accountMatches() {
        if (store.workspace.equals(new NativeAccountStore(activity).activeId())) return true;
        clearSelection();
        setControls(true);
        feedback(t("本机账户已切换，请返回数据管理重新选择文件。", "The device profile changed. Open Data management and choose the file again."));
        return false;
    }
    private void clearSelection() { selected = null; replacing = false; confirmation.setVisibility(GONE); }
    private void feedback(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    }
    private void setControls(boolean enabled) {
        for (Button action : actions) { action.setEnabled(enabled); action.setAlpha(enabled ? 1f : 0.5f); }
    }
    private void action(LinearLayout parent, String label, boolean destructive, OnClickListener click) {
        Button b = button(label, destructive, click);
        actions.add(b);
        parent.addView(b, gap(4));
    }
    private Button button(String label, boolean destructive, OnClickListener click) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTextColor(destructive ? (dark ? 0xffffaca7 : 0xffaa3030) : (dark ? 0xffa9b7ff : 0xff3456bc));
        b.setTextSize(15 * scale);
        b.setMinHeight(dp(48));
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(touch(null));
        b.setOnClickListener(click);
        return b;
    }
    private LinearLayout card(LinearLayout body) {
        LinearLayout card = column();
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(surface());
        fill.setCornerRadius(dp(16));
        card.setBackground(fill);
        card.setPadding(dp(16), dp(16), dp(16), dp(12));
        body.addView(card, gap(body.getChildCount() == 0 ? 0 : 12));
        return card;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size * scale);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
    private RippleDrawable touch(GradientDrawable fill) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x18000000), fill, null);
    }
    private static String choose(boolean english, String zh, String en) { return english ? en : zh; }
    private String t(String zh, String en) { return choose(english, zh, en); }
    private int dp(int size) { return Math.round(getResources().getDisplayMetrics().density * size); }
    private int surface() { return dark ? 0xff202125 : Color.WHITE; }
    private int ink() { return dark ? 0xfff2f3f5 : 0xff202329; }
    private int muted() { return dark ? 0xffb3b6c0 : 0xff646976; }
}
