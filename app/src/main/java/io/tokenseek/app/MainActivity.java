package io.tokenseek.app;

import static io.tokenseek.app.NativeAccountStore.object;
import static io.tokenseek.app.NativeThread.put;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputFilter;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_PHOTO = 510, PICK_FILE = 511, VOICE = 512, PICK_USAGE = 513;
    private final ArrayDeque<String> trail = new ArrayDeque<>();
    private final ArrayDeque<Bundle> settingsTrail = new ArrayDeque<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private NativeAccountStore accounts;
    private KeyVault vault;
    private NativeAccountPages settings;
    private NativeHistoryStore store;
    private NativeChatClient client;
    private NativeThread thread;
    private NativeConversation chat;
    private NativeWalletController wallet;
    private NativeDataPage data;
    private NativeUsagePage usage;
    private NativePlatformUsagePage platformUsage;
    private NativeChatClient.Call upload;
    private TextToSpeech speech;
    private String page = "chat",
            owner = "",
            key = "",
            pickerOwner = "",
            pickerThread = "";
    private JSONArray sections = new JSONArray();
    private JSONObject organization = new JSONObject();
    private String listStamp = "";
    private long usageRevision;
    private boolean refreshOnReturn, preparingAttachment;
    private AlertDialog importProgress;
    private final NativeHistoryTransfers.Observer transfers =
            new NativeHistoryTransfers.Observer() {
                @Override
                public boolean allowsImport(NativeHistoryTransfers.Job job) {
                    return !job.affects(owner)
                            || thread == null
                            || (!thread.generating() && !preparingAttachment);
                }

                @Override
                public void changed(NativeHistoryTransfers.Job job) {
                    transferChanged(job);
                }
            };
    private int attachmentRevision;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        accounts = new NativeAccountStore(this);
        vault = new KeyVault(this);
        client = new NativeChatClient(this);
        root = new FrameLayout(this);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 35)
            root.setOnApplyWindowInsetsListener(
                    (view, insets) -> {
                        android.graphics.Insets pad =
                                insets.getInsets(
                                        WindowInsets.Type.systemBars()
                                                | WindowInsets.Type.displayCutout()
                                                | WindowInsets.Type.ime());
                        view.setPadding(pad.left, pad.top, pad.right, pad.bottom);
                        return WindowInsets.CONSUMED;
                    });
        chat = new NativeConversation(this, this::action);
        wallet =
                new NativeWalletController(
                        this,
                        this::walletAction,
                        () -> {
                            if (settings != null) settings.setBalance(owner, wallet.balanceLabel());
                            renderChat();
                        });
        settings =
                new NativeAccountPages(
                        this,
                        vault,
                        new NativeAccountPages.Host() {
                            public void open(String destination) {
                                if ("chat".equals(destination)) home();
                                else if ("back".equals(destination)) popPage();
                                else go(destination);
                            }

                            public void changed(String reason) {
                                if (!accounts.activeId().equals(owner)) switchWorkspace();
                                else {
                                    if ("keys".equals(reason)) readKey();
                                    bindWallet();
                                    renderChat();
                                    colors();
                                }
                            }

                            public void pickPhoto() {
                                pick(PICK_PHOTO, "image/*");
                            }
                        });
        NativeHistoryTransfers.observe(transfers);
        switchWorkspace();
        if (state != null && owner.equals(state.getString("owner"))) {
            wallet.restoreState(
                    state.containsKey("wallet")
                            ? state.getBundle("wallet")
                            : state.getBundle("walletForm"));
            pickerOwner = state.getString("pickerOwner", "");
            pickerThread = state.getString("pickerThread", "");
            ArrayList<String> saved = state.getStringArrayList("trail");
            page = nativeDestination(state.getString("page", "chat"));
            if (saved != null) {
                String previous = page;
                for (String entry : saved) {
                    String restored = nativeDestination(entry);
                    if (restored.equals(previous) && !"settings".equals(restored)) continue;
                    trail.addLast(restored);
                    previous = restored;
                }
            }
            ArrayList<Bundle> savedSettings = state.getParcelableArrayList("settingsTrail");
            if (savedSettings != null) settingsTrail.addAll(savedSettings);
            showPage(state.getBundle("settingsNavigation"));
            if ("data".equals(page) && data != null) data.restoreState(state.getBundle("data"));
        } else home();
        NativeHistoryTransfers.Job ongoing = NativeHistoryTransfers.active(owner);
        if (ongoing != null) transferChanged(ongoing);
        root.post(this::offerLegacyData);
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher()
                    .registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
    }

    private boolean en() {
        return accounts.locale().startsWith("en");
    }

    private String t(String zh, String english) {
        return en() ? english : zh;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void readKey() {
        String snapshot = vault.manageProfile(owner, "read", "", "", "");
        try {
            JSONObject keys = new JSONObject(snapshot);
            key = keys.isNull("apiKey") ? "" : keys.optString("apiKey");
            settings.setKeySummary(
                    owner,
                    keys.optJSONArray("entries"),
                    keys.isNull("activeId") ? "" : keys.optString("activeId"));
        } catch (JSONException invalid) {
            key = "";
            settings.setKeySummary(owner, null, "");
        }
    }

    private void bindWallet() {
        wallet.bind(owner, key, en(), accounts.dark(), accounts.fontScale());
    }

    private void switchWorkspace() {
        cancelUpload();
        if (thread != null) thread.close();
        owner = accounts.activeId();
        store = new NativeHistoryStore(this, owner);
        thread =
                new NativeThread(
                        store,
                        accounts,
                        client,
                        new NativeThread.Listener() {
                            public void changed() {
                                renderChat();
                                if ("threadUsage".equals(page)) renderThreadUsage();
                            }

                            public void problem(String message) {
                                toast(message);
                            }
                        });
        data = null;
        listStamp = "";
        refreshSections();
        readKey();
        bindWallet();
        colors();
        settings.setBalance(owner, wallet.balanceLabel());
        renderChat();
    }

    private void colors() {
        boolean dark = accounts.dark();
        int color = dark ? 0xff16171a : 0xffffffff;
        root.setBackgroundColor(color);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                dark
                        ? decor.getSystemUiVisibility() & ~flags
                        : decor.getSystemUiVisibility() | flags);
    }

    private void refreshSections() {
        if (NativeHistoryTransfers.importing(owner)) {
            sections = new JSONArray();
            organization = new JSONObject();
            return;
        }
        sections = store.sessions();
        organization = store.organization();
    }

    private void renderChat() {
        if (thread == null
                || chat == null
                || !"chat".equals(page)
                || NativeHistoryTransfers.importing(owner)) return;
        JSONObject session = thread.session(), profile = accounts.profile();
        String stamp =
                session == null ? "" : thread.sessionId() + "/" + session.optLong("updatedAt");
        if (!stamp.equals(listStamp)) {
            listStamp = stamp;
            refreshSections();
        }
        chat.render(
                object(
                        "sessionId",
                        thread.sessionId(),
                        "title",
                        session == null ? "" : session.optString("title"),
                        "draft",
                        thread.draft(),
                        "model",
                        thread.model(),
                        "thinking",
                        thread.thinking(),
                        "search",
                        thread.search(),
                        "streaming",
                        thread.streamingHere(),
                        "needsFlash",
                        thread.needsFlash(),
                        "attachment",
                        thread.fileName(),
                        "contextLabel",
                        NativeUsagePage.remainingLabel(
                                session,
                                thread.model(),
                                thread.draft(),
                                thread.streamingHere(),
                                !thread.fileName().isEmpty()),
                        "nickname",
                        profile.optString("nickname"),
                        "avatar",
                        profile.optString("avatar", ""),
                        "balance",
                        wallet.balanceLabel(),
                        "dark",
                        accounts.dark(),
                        "locale",
                        accounts.locale(),
                        "scale",
                        accounts.fontScale(),
                        "messages",
                        thread.displayMessages(),
                        "sessions",
                        sections,
                        "organization",
                        organization));
    }

    private void setView(View v) {
        if (v.getParent() == root && root.getChildCount() == 1) return;
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        root.removeAllViews();
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private static String nativeDestination(String saved) {
        if ("checkout".equals(saved)) return "wallet";
        if ("transactions".equals(saved)) return "usage";
        if ("officialKeys".equals(saved)) return "keys";
        return saved == null ? "chat" : saved;
    }

    private void go(String destination) {
        destination = nativeDestination(destination);
        if (destination.equals(page)) return;
        if ("settings".equals(page)) settingsTrail.push(settings.navigation());
        trail.push(page);
        page = destination;
        showPage();
    }

    private void home() {
        trail.clear();
        settingsTrail.clear();
        page = "chat";
        showPage();
    }

    private void back() {
        if ("settings".equals(page)) {
            settings.back();
            return;
        }
        if ("chat".equals(page)) {
            if (!chat.back()) super.finish();
            return;
        }
        if ("data".equals(page) && data != null && data.executing()) return;
        if ("usage".equals(page) && platformUsage != null && platformUsage.backToOverview()) return;
        popPage();
    }

    private void popPage() {
        page = trail.isEmpty() ? "chat" : trail.pop();
        showPage("settings".equals(page) ? settingsTrail.pollFirst() : null);
    }

    private void showPage() {
        showPage(null);
    }

    private void showPage(Bundle settingsNavigation) {
        usageRevision++;
        if (page.startsWith("settings/")
                || "account".equals(page)
                || "keys".equals(page)
                || "addKey".equals(page)
                || "settings".equals(page)) {
            String section =
                    page.startsWith("settings/")
                            ? page.substring(9)
                            : "settings".equals(page) ? "home" : page;
            boolean direct = "account".equals(page) || "keys".equals(page) || "addKey".equals(page);
            page = "settings";
            if (settingsNavigation != null) settings.restoreNavigation(settingsNavigation);
            else if (direct) settings.enter(section);
            else settings.render(section);
            setView(settings);
            return;
        }
        switch (page) {
            case "wallet":
                bindWallet();
                setView(wallet.view());
                break;
            case "usage":
                showPlatformUsage();
                break;
            case "localUsage":
            case "threadUsage":
                usage =
                        new NativeUsagePage(
                                this, en(), accounts.dark(), accounts.fontScale(), this::back);
                setView(usage);
                if ("threadUsage".equals(page)) renderThreadUsage();
                else {
                    long rev = usageRevision;
                    NativeHistoryStore source = store;
                    String name = accounts.profile().optString("nickname");
                    io.execute(
                            () -> {
                                NativeUsageRecords.Totals readings;
                                try {
                                    readings =
                                            NativeUsageRecords.accountTotals(source.allSessions());
                                } catch (RuntimeException failure) {
                                    readings = null;
                                }
                                NativeUsageRecords.Totals totals = readings;
                                main.post(
                                        () -> {
                                            if (!isDestroyed() && rev == usageRevision)
                                                usage.renderAccountTotals(name, totals);
                                        });
                            });
                }
                break;
            case "data":
                if (data == null || !data.isBusy()) data = makeData();
                setView(data);
                break;
            default:
                page = "chat";
                setView(chat);
                renderChat();
        }
    }

    private void showPlatformUsage() {
        platformUsage =
                new NativePlatformUsagePage(
                        this,
                        en(),
                        accounts.dark(),
                        accounts.fontScale(),
                        accounts.profile().optString("nickname"),
                        this::back,
                        new NativePlatformUsagePage.Actions() {
                            public void pick() {
                                MainActivity.this.pick(PICK_USAGE, "*/*");
                            }

                            public void select(String id) {
                                updatePlatformUsage(null, id);
                            }

                            public void local() {
                                go("localUsage");
                            }
                        });
        setView(platformUsage);
        updatePlatformUsage(null, null);
    }

    private void updatePlatformUsage(Uri input, String selectId) {
        NativePlatformUsagePage view = platformUsage;
        if (!"usage".equals(page) || view == null) return;
        final String profile = owner;
        final long revision = ++usageRevision;
        final boolean isImport = input != null;
        if (isImport && !android.content.ContentResolver.SCHEME_CONTENT.equals(input.getScheme())) {
            view.show(
                    null,
                    t("请选择系统文件选择器中的用量报表。", "Choose a usage report from the document picker."));
            return;
        }
        view.loading(
                isImport ? t("正在导入报表…", "Importing report…") : t("正在读取报表…", "Reading reports…"));
        android.content.Context app = getApplicationContext();
        String success = isImport ? t("已导入报表。", "Report imported.") : "";
        String failure =
                isImport
                        ? t(
                                "导入失败。请选择开放平台的用量 ZIP 或用量明细 CSV，已有报表会保留。",
                                "Could not import this file. Choose a platform usage ZIP or"
                                    + " usage-amount CSV. Previously imported reports are kept.")
                        : t("无法读取已保存的报表。", "Could not read the saved report.");
        NativePlatformUsageStore.worker.execute(
                () -> {
                    NativePlatformUsageStore reports = new NativePlatformUsageStore(app, profile);
                    NativePlatformUsageStore.Snapshot result = null;
                    String status = success;
                    try {
                        if (isImport) {
                            try (InputStream source =
                                    app.getContentResolver().openInputStream(input)) {
                                if (source == null) throw new java.io.IOException();
                                reports.importReport(source);
                            }
                        } else if (selectId != null) reports.select(selectId);
                        result = reports.read();
                    } catch (java.io.IOException | RuntimeException problem) {
                        status = failure;
                    }
                    final NativePlatformUsageStore.Snapshot snapshot = result;
                    final String message = status;
                    main.post(
                            () -> {
                                if (!isDestroyed()
                                        && revision == usageRevision
                                        && owner.equals(profile)
                                        && "usage".equals(page)) {
                                    view.show(snapshot, message);
                                }
                            });
                });
    }

    private void renderThreadUsage() {
        if (usage != null)
            usage.renderConversation(
                    thread.session(),
                    thread.model(),
                    thread.draft(),
                    thread.streamingHere(),
                    !thread.fileName().isEmpty());
    }

    private NativeDataPage makeData() {
        NativeDataPage panel =
                new NativeDataPage(
                        this,
                        store,
                        en(),
                        accounts.dark(),
                        accounts.fontScale(),
                        this::back,
                        () -> {});
        panel.onLegacyImport(this::confirmLegacy);
        panel.guardImport(() -> !thread.generating() && !preparingAttachment);
        return panel;
    }

    private void transferChanged(NativeHistoryTransfers.Job job) {
        if (thread == null || !job.affects(owner) || job.kind == NativeHistoryTransfers.Kind.EXPORT)
            return;
        if (!job.finished) {
            thread.pauseForImport();
            main.removeCallbacks(meterUpdate);
            if ((!"data".equals(page) || job.kind == NativeHistoryTransfers.Kind.LEGACY)
                    && importProgress == null
                    && !isFinishing()) {
                android.widget.ProgressBar progress =
                        new android.widget.ProgressBar(
                                this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setIndeterminate(true);
                int inset = Math.round(24 * getResources().getDisplayMetrics().density);
                progress.setPadding(inset, inset / 2, inset, inset);
                importProgress =
                        new AlertDialog.Builder(this)
                                .setTitle(t("正在导入数据…", "Importing data…"))
                                .setView(progress)
                                .setCancelable(false)
                                .show();
            }
            return;
        }
        if (importProgress != null) {
            importProgress.dismiss();
            importProgress = null;
        }
        thread.finishImport();
        listStamp = "";
        if (job.kind == NativeHistoryTransfers.Kind.LEGACY && job.changed) {
            switchWorkspace();
            home();
            toast(job.message);
        } else {
            refreshSections();
            renderChat();
            if ("threadUsage".equals(page)) renderThreadUsage();
            if ("usage".equals(page)) showPage();
            if (job.kind == NativeHistoryTransfers.Kind.LEGACY) toast(job.message);
        }
    }

    private void offerLegacyData() {
        if (isFinishing() || isDestroyed() || NativeHistoryTransfers.importing(owner)) return;
        if (NativeLegacyImport.pending(this)) {
            confirmLegacy();
            return;
        }
        android.content.SharedPreferences notices =
                getSharedPreferences("upgrade_notices", MODE_PRIVATE);
        if (!"chat".equals(page) || notices.getBoolean("legacy", false)) return;
        io.execute(
                () -> {
                    if (!NativeHistoryStore.hasLegacyStorage(this)
                            || NativeLegacyImport.completed(this)) return;
                    main.post(
                            () -> {
                                if (isFinishing()
                                        || isDestroyed()
                                        || !"chat".equals(page)
                                        || notices.getBoolean("legacy", false)) return;
                                new AlertDialog.Builder(this)
                                        .setTitle(t("发现旧版对话数据", "Previous chat data found"))
                                        .setMessage(
                                                t(
                                                        "旧版数据仍在此设备上。已有聊天备份可直接导入。要保留账户、草稿和附件，请使用离线迁移工具。",
                                                        "Your previous data is still on this"
                                                            + " device. Import a chat backup, or"
                                                            + " use the offline migration tool to"
                                                            + " keep profiles, drafts and files."))
                                        .setNegativeButton(t("稍后", "Later"), null)
                                        .setPositiveButton(
                                                t("数据管理", "Data management"),
                                                (dialog, which) -> go("data"))
                                        .show();
                                notices.edit().putBoolean("legacy", true).apply();
                            });
                });
    }

    private void confirmLegacy() {
        if (NativeHistoryTransfers.active(owner) != null || !NativeLegacyImport.pending(this))
            return;
        new AlertDialog.Builder(this)
                .setTitle(t("导入旧版数据", "Import previous data"))
                .setMessage(
                        t(
                                "合并此设备已准备好的旧版账户、对话和附件，保留已有记录。",
                                "Merge the profiles, chats and files prepared on this device,"
                                    + " keeping existing records."))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        t("合并导入", "Merge"),
                        (d, w) -> {
                            NativeHistoryTransfers.Job job =
                                    NativeHistoryTransfers.begin(
                                            owner, NativeHistoryTransfers.Kind.LEGACY);
                            if (job == null) {
                                toast(
                                        t(
                                                "请先停止生成，或等待文件传输完成。",
                                                "Stop the active reply or wait for the file"
                                                    + " transfer."));
                                return;
                            }
                            android.content.Context app = getApplicationContext();
                            boolean english = en();
                            io.execute(
                                    () -> {
                                        boolean merged;
                                        try {
                                            NativeLegacyImport.merge(
                                                    app, new NativeAccountStore(app));
                                            merged = true;
                                        } catch (Exception failure) {
                                            merged = false;
                                        }
                                        boolean success = merged;
                                        String message =
                                                success
                                                        ? (english
                                                                ? "Previous data imported."
                                                                : "已导入旧版数据。")
                                                        : (english
                                                                ? "Import incomplete. Retry in Data"
                                                                      + " management."
                                                                : "导入未完成，可在数据管理中重试。");
                                        new Handler(Looper.getMainLooper())
                                                .post(
                                                        () ->
                                                                NativeHistoryTransfers.finish(
                                                                        job, success, message));
                                    });
                        })
                .show();
    }

    private void walletAction(String id) {
        switch (id) {
            case "back":
                back();
                break;
            case "chat":
                home();
                break;
            case "keys":
                go("keys");
                break;
            case "usage":
                go("usage");
                break;
        }
    }

    private boolean requireKey() {
        if (!key.isEmpty()) return true;
        new AlertDialog.Builder(this)
                .setMessage(t("选用聊天密钥后即可发送。", "Select a chat key to send messages."))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(t("聊天密钥", "Chat keys"), (d, w) -> go("keys"))
                .show();
        return false;
    }

    private void action(String name, String value) {
        if (NativeHistoryTransfers.importing(owner)) return;
        switch (name) {
            case "draft":
                thread.draft(value);
                renderDraftMeter();
                return;
            case "new":
                cancelUpload();
                thread.open(null);
                break;
            case "open":
                cancelUpload();
                thread.open(value);
                break;
            case "send":
                if (preparingAttachment) {
                    toast(t("附件正在上传。", "Uploading attachment."));
                    return;
                }
                if (requireKey()) thread.send(value);
                break;
            case "stop":
                thread.stop();
                return;
            case "retry":
                if (requireKey()) thread.retry(value);
                break;
            case "settings":
            case "account":
                go(name);
                return;
            case "usage":
                go("threadUsage");
                return;
            case "model":
            case "thinking":
            case "search":
                thread.setOption(name, value);
                break;
            case "prev":
            case "next":
                thread.branch(value);
                break;
            case "continue":
                if (requireKey()) thread.resume(value);
                break;
            case "edit":
                editMessage(value);
                return;
            case "speak":
                speak(value);
                return;
            case "attach":
                if (requireKey()) pick(PICK_FILE, "*/*");
                return;
            case "removeAttachment":
                cancelUpload();
                thread.attach(null);
                break;
            case "voice":
                voice();
                return;
            case "exportChat":
                go("data");
                data.exportSingle(value);
                return;
            default:
                organize(name, value);
                return;
        }
        renderChat();
    }

    private final Runnable meterUpdate = this::renderChat;

    private void renderDraftMeter() {
        main.removeCallbacks(meterUpdate);
        main.postDelayed(meterUpdate, 120);
    }

    private void prompt(String title, String initial, java.util.function.Consumer<String> action) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(initial);
        field.setSelectAllOnFocus(true);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(100)});
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(field)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        android.R.string.ok,
                        (d, w) -> {
                            String v = field.getText().toString().trim();
                            if (!v.isEmpty()) action.accept(v);
                        })
                .show();
    }

    private void editMessage(String id) {
        JSONObject original = thread.message(id);
        if (original == null || thread.generating() || !requireKey()) return;
        EditText text = new EditText(this);
        text.setText(original.optString("content"));
        text.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle(t("编辑消息", "Edit message"))
                .setView(text)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        t("重新发送", "Send again"),
                        (d, w) -> {
                            String s = text.getText().toString();
                            if (!s.trim().isEmpty()) thread.edit(id, s);
                        })
                .show();
    }

    private void organize(String action, String id) {
        if (NativeHistoryTransfers.importing(owner)) return;
        JSONObject org = store.organization(), assignments = org.optJSONObject("assignments");
        JSONArray projects = org.optJSONArray("projects"), pins = org.optJSONArray("pinned");
        switch (action) {
            case "rename":
                {
                    JSONObject target = store.session(id);
                    if (target == null) return;
                    prompt(
                            t("重命名对话", "Rename chat"),
                            target.optString("title"),
                            name -> {
                                JSONObject latest =
                                        id.equals(thread.sessionId())
                                                ? thread.session()
                                                : store.session(id);
                                if (latest == null) return;
                                put(latest, "title", name);
                                store.saveSession(latest);
                                refreshSections();
                                renderChat();
                            });
                    return;
                }
            case "delete":
                if (thread.generating()) {
                    toast(t("请先停止生成。", "Stop the active reply first."));
                    return;
                }
                new AlertDialog.Builder(this)
                        .setMessage(t("删除这条对话及其所有分支？", "Delete this chat and all its branches?"))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                                t("删除", "Delete"),
                                (d, w) -> {
                                    if (id.equals(thread.sessionId())) thread.open(null);
                                    store.deleteSession(id);
                                    refreshSections();
                                    renderChat();
                                })
                        .show();
                return;
            case "togglePin":
                {
                    int existing = -1;
                    for (int i = 0; i < pins.length(); i++)
                        if (id.equals(pins.optString(i))) existing = i;
                    if (existing < 0) pins.put(id);
                    else pins.remove(existing);
                    break;
                }
            case "move":
                {
                    String[] names = new String[projects.length() + 1];
                    names[0] = t("最近对话", "Recent chats");
                    for (int i = 0; i < projects.length(); i++)
                        names[i + 1] = projects.optJSONObject(i).optString("name");
                    new AlertDialog.Builder(this)
                            .setTitle(t("移动到", "Move to"))
                            .setItems(
                                    names,
                                    (d, w) -> {
                                        if (w == 0) assignments.remove(id);
                                        else
                                            put(
                                                    assignments,
                                                    id,
                                                    projects.optJSONObject(w - 1).optString("id"));
                                        saveOrg(org);
                                    })
                            .show();
                    return;
                }
            case "createProject":
                prompt(
                        t("新建项目", "New project"),
                        "",
                        name -> {
                            projects.put(object("id", NativeThread.id(), "name", name));
                            saveOrg(org);
                        });
                return;
            case "renameProject":
            case "deleteProject":
                {
                    int idx = -1;
                    for (int i = 0; i < projects.length(); i++)
                        if (id.equals(projects.optJSONObject(i).optString("id"))) idx = i;
                    if (idx < 0) return;
                    final int at = idx;
                    JSONObject project = projects.optJSONObject(at);
                    if ("renameProject".equals(action))
                        prompt(
                                t("项目名称", "Project name"),
                                project.optString("name"),
                                name -> {
                                    put(project, "name", name);
                                    saveOrg(org);
                                });
                    else
                        new AlertDialog.Builder(this)
                                .setMessage(
                                        t(
                                                "删除项目？其中的对话会保留。",
                                                "Delete this project and keep its chats?"))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(
                                        t("删除", "Delete"),
                                        (d, w) -> {
                                            projects.remove(at);
                                            JSONArray ids = assignments.names();
                                            for (int i = 0; ids != null && i < ids.length(); i++)
                                                if (id.equals(
                                                        assignments.optString(ids.optString(i))))
                                                    assignments.remove(ids.optString(i));
                                            saveOrg(org);
                                        })
                                .show();
                    return;
                }
            default:
                return;
        }
        saveOrg(org);
    }

    private void saveOrg(JSONObject org) {
        store.saveOrganization(org);
        refreshSections();
        renderChat();
    }

    private void pick(int request, String mime) {
        pickerOwner = owner;
        pickerThread = thread.sessionId();
        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(mime);
        if (request == PICK_USAGE) {
            intent.putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    new String[] {
                        "application/zip",
                        "application/x-zip-compressed",
                        "text/csv",
                        "text/comma-separated-values",
                        "application/csv",
                        "text/plain",
                        "application/vnd.ms-excel",
                        "application/octet-stream"
                    });
        }
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException missing) {
            toast(t("没有可用的文件选择器。", "No document picker available."));
        }
    }

    private void voice() {
        pickerOwner = owner;
        pickerThread = thread.sessionId();
        Intent intent =
                new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            startActivityForResult(intent, VOICE);
        } catch (ActivityNotFoundException missing) {
            toast(t("设备未提供语音输入。", "Speech input is unavailable on this device."));
        }
    }

    private void speak(String id) {
        JSONObject m = thread.message(id);
        if (m == null) return;
        StringBuilder spoken = new StringBuilder();
        JSONArray blocks = m.optJSONArray("blocks");
        for (int i = 0; blocks != null && i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b != null && "text".equals(b.optString("type")))
                spoken.append(b.optString("text")).append('\n');
        }
        if (spoken.length() == 0) return;
        if (speech == null)
            speech =
                    new TextToSpeech(
                            this,
                            status -> {
                                if (status == TextToSpeech.SUCCESS && !isDestroyed())
                                    readAloud(spoken.toString());
                                else toast(t("设备未提供朗读。", "Speech playback is unavailable."));
                            });
        else readAloud(spoken.toString());
    }

    private void readAloud(String text) {
        Locale language = en() ? Locale.US : Locale.SIMPLIFIED_CHINESE;
        speech.setLanguage(language);
        android.speech.tts.Voice choice =
                NativeVoiceOptions.find(
                        speech.getVoices(), accounts.preference("voiceName"), language);
        if (choice != null) speech.setVoice(choice);
        speech.stop();
        int limit = TextToSpeech.getMaxSpeechInputLength();
        for (int start = 0; start < text.length(); start += limit)
            speech.speak(
                    text.substring(start, Math.min(start + limit, text.length())),
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "chat-" + start);
    }

    @Override
    public void onActivityResult(int request, int status, Intent result) {
        super.onActivityResult(request, status, result);
        if (request == 610 || request == 611) {
            if (data != null) data.result(request, status, result);
            else toast(t("请重新选择备份文件。", "Choose the backup file again."));
            return;
        }
        if (status != RESULT_OK || result == null || !owner.equals(pickerOwner)) return;
        if (request == PICK_USAGE) {
            if (result.getData() != null && "usage".equals(page))
                updatePlatformUsage(result.getData(), null);
            return;
        }
        if (request == PICK_PHOTO) {
            settings.onPhotoResult(result.getData());
            return;
        }
        if (!thread.sessionId().equals(pickerThread)) return;
        if (request == VOICE) {
            ArrayList<String> words =
                    result.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (words != null && !words.isEmpty()) {
                thread.draft(thread.draft() + (thread.draft().isEmpty() ? "" : " ") + words.get(0));
                renderChat();
            }
        } else if (request == PICK_FILE && result.getData() != null) attach(result.getData());
    }

    private void cancelUpload() {
        attachmentRevision++;
        if (upload != null) upload.stop();
        upload = null;
        preparingAttachment = false;
    }

    private void attach(Uri uri) {
        cancelUpload();
        int revision = attachmentRevision;
        preparingAttachment = true;
        NativeThread target = thread;
        String sid = target.sessionId();
        NativeHistoryStore destination = store;
        upload =
                client.upload(
                        owner,
                        uri,
                        (file, error) -> {
                            if (revision != attachmentRevision
                                    || target != thread
                                    || !sid.equals(thread.sessionId())
                                    || isDestroyed()) return;
                            upload = null;
                            if (file == null) {
                                preparingAttachment = false;
                                toast(error);
                                return;
                            }
                            String id = NativeThread.id();
                            put(file, "id", id);
                            NativeHistoryTransfers.writingAttachment(destination.workspace);
                            Handler completion = new Handler(Looper.getMainLooper());
                            io.execute(
                                    () -> {
                                        boolean saved = false;
                                        try (InputStream input =
                                                getContentResolver().openInputStream(uri)) {
                                            if (input == null) throw new java.io.IOException();
                                            destination.saveAttachment(id, input);
                                            saved = true;
                                        } catch (Exception failure) {
                                            saved = false;
                                        }
                                        boolean copied = saved;
                                        completion.post(
                                                () -> {
                                                    NativeHistoryTransfers.finishedAttachment(
                                                            destination.workspace);
                                                    if (isDestroyed()
                                                            || revision != attachmentRevision
                                                            || target != thread
                                                            || !sid.equals(thread.sessionId()))
                                                        return;
                                                    preparingAttachment = false;
                                                    if (copied) {
                                                        thread.attach(file);
                                                        renderChat();
                                                    } else
                                                        toast(
                                                                t(
                                                                        "无法保存附件原件。",
                                                                        "Could not save the"
                                                                            + " attachment."));
                                                });
                                    });
                        });
        toast(t("正在上传附件…", "Uploading attachment…"));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        thread.persist();
        state.putBundle("wallet", wallet.saveState());
        state.putString("owner", owner);
        state.putString("page", page);
        if ("settings".equals(page)) state.putBundle("settingsNavigation", settings.navigation());
        state.putParcelableArrayList("settingsTrail", new ArrayList<>(settingsTrail));
        if (data != null && "data".equals(page)) state.putBundle("data", data.state());
        state.putStringArrayList("trail", new ArrayList<>(trail));
        state.putString("pickerOwner", pickerOwner);
        state.putString("pickerThread", pickerThread);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (thread != null) thread.persist();
        refreshOnReturn = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (refreshOnReturn && wallet != null) {
            refreshOnReturn = false;
            wallet.refresh();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        colors();
        bindWallet();
        if ("settings".equals(page)) settings.refresh();
        else showPage();
    }

    @Override
    public void onBackPressed() {
        back();
    }

    @Override
    protected void onDestroy() {
        NativeHistoryTransfers.forget(transfers);
        if (importProgress != null) {
            importProgress.dismiss();
            importProgress = null;
        }
        main.removeCallbacksAndMessages(null);
        if (thread != null) thread.close();
        if (settings != null) settings.close();
        if (wallet != null) wallet.close();
        if (client != null) client.close();
        if (speech != null) speech.shutdown();
        io.shutdown();
        super.onDestroy();
    }
}
