package io.tokenseek.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONObject;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the Android wallet view and its active workspace's balance request, on the UI thread. */
final class NativeWalletController {
    private final NativeFunding panel;
    private final Runnable onBalanceChanged;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private NativeBalanceRequest request;
    private NativeBalanceRequest.Result result = NativeBalanceRequest.Result.empty("unbound");
    private String workspace = "";
    private String key = "";
    private boolean english;
    private boolean dark;
    private boolean closed;
    private int scale = 100;
    private String sidebar = "";
    private boolean boundOnce;

    NativeWalletController(Context context, NativeFunding.Listener listener) {
        this(context, listener, () -> {});
    }

    NativeWalletController(Context context, NativeFunding.Listener listener, Runnable onBalanceChanged) {
        this.onBalanceChanged = onBalanceChanged;
        panel = new NativeFunding(context, action -> {
            if (closed) return;
            if ("refresh".equals(action)) refresh();
            else listener.action(action);
        });
    }

    View view() { return panel; }
    String balanceLabel() { return sidebar; }

    Bundle saveState() {
        Bundle state = new Bundle();
        state.putBoolean("details", panel.detailsExpanded());
        state.putString("workspace", workspace);
        return state;
    }

    void restoreState(Bundle state) {
        if (state == null || !workspace.equals(state.getString("workspace"))) return;
        panel.setDetailsExpanded(state.getBoolean("details"));
        render();
    }

    void bind(String workspaceId, String apiKey, boolean english, boolean dark, int fontPercent) {
        if (closed) return;
        boolean restyle = !boundOnce || this.english != english || this.dark != dark || scale != fontPercent;
        boundOnce = true;
        this.english = english;
        this.dark = dark;
        scale = fontPercent;
        String next = apiKey == null ? "" : apiKey;
        if (Objects.equals(workspace, workspaceId) && key.equals(next)) {
            if (restyle) render();
            return;
        }
        cancel();
        panel.setDetailsExpanded(false);
        workspace = workspaceId;
        key = next;
        result = NativeBalanceRequest.Result.empty(key.isEmpty() ? "unbound" : "loading");
        if (key.isEmpty()) render();
        else refresh();
    }

    void refresh() {
        if (closed || request != null || key.isEmpty()) return;
        NativeBalanceRequest next = new NativeBalanceRequest();
        request = next;
        if (!"ready".equals(result.status)) result = NativeBalanceRequest.Result.empty("loading");
        String credential = key;
        render();
        network.execute(() -> {
            NativeBalanceRequest.Result received = next.fetch(credential);
            ui.post(() -> {
                if (request != next || closed) return;
                request = null;
                result = received;
                render();
            });
        });
    }

    private void cancel() {
        if (request != null) request.cancel();
        request = null;
    }

    void close() {
        closed = true;
        cancel();
        key = "";
        result = NativeBalanceRequest.Result.empty("unbound");
        network.shutdownNow();
    }

    private String t(String zh, String en) { return english ? en : zh; }

    private void updateSidebar() {
        String caption = "";
        if ("ready".equals(result.status)) {
            StringBuilder amounts = new StringBuilder();
            for (int i = 0; i < result.balances.length(); i++) {
                JSONObject row = result.balances.optJSONObject(i);
                if (i > 0) amounts.append("  ·  ");
                amounts.append(row.optString("total")).append(" ").append(row.optString("currency"));
            }
            caption = amounts.length() == 0 ? t("余额待确认", "Balance unavailable") : amounts.toString();
        } else if ("loading".equals(result.status)) caption = t("正在查询余额…", "Checking balance…");
        else if ("invalid".equals(result.status)) caption = t("密钥需重新绑定", "Reconnect API key");
        else if ("error".equals(result.status)) caption = t("余额暂不可用", "Balance unavailable");
        if (!sidebar.equals(caption)) {
            sidebar = caption;
            onBalanceChanged.run();
        }
    }

    private void render() {
        panel.render(english, dark, scale, !key.isEmpty(), request != null, result);
        updateSidebar();
    }
}
