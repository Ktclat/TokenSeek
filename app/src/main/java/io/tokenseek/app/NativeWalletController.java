package io.tokenseek.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
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
        panel = new NativeFunding(context, (action, amount) -> {
            if (closed) return;
            if ("refresh".equals(action)) refresh();
            else listener.action(action, amount);
        });
    }

    View view() { return panel; }
    String balanceLabel() { return sidebar; }

    Bundle saveForm() {
        Bundle state = panel.saveForm();
        state.putString("workspace", workspace);
        return state;
    }

    void restoreForm(Bundle state) {
        if (state == null || !workspace.equals(state.getString("workspace"))) return;
        panel.restoreForm(state);
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
        panel.resetAccount();
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
        try {
            JSONObject labels = new JSONObject()
                    .put("title", t("余额与充值", "Balance and top-up")).put("back", t("返回", "Back"))
                    .put("transactions", t("账单", "Bills")).put("details", t("余额明细", "Balance details"))
                    .put("customChoice", t("自定义", "Custom")).put("custom", t("自定义金额", "Custom amount"))
                    .put("total", t("账户总余额", "Total balance")).put("refresh", t("刷新余额", "Refresh balance"))
                    .put("refreshing", t("正在刷新…", "Refreshing…")).put("credit", t("充值余额", "Paid balance"))
                    .put("grant", t("赠送余额", "Granted balance")).put("chat", t("返回对话", "Return to chat"))
                    .put("amount", t("充值金额", "Top-up amount")).put("unit", t("人民币", "CNY"))
                    .put("copy", t("复制金额", "Copy amount")).put("checkout", t("核对充值金额", "Review top-up"))
                    .put("keys", t("聊天密钥", "Chat keys")).put("copied", t("已复制金额", "Amount copied"))
                    .put("copyFailed", t("请选中金额后手动复制。", "Select the amount to copy it manually."))
                    .put("invalid", t("请输入大于零的金额，最多两位小数。", "Enter a positive amount with up to two decimal places."))
                    .put("overflow", t("本机输入最多支持 12 位整数。", "This field supports up to 12 whole-number digits."));
            String message = "";
            switch (result.status) {
                case "invalid": message = t("此密钥无法查询余额，请重新绑定。", "This key cannot access the balance. Connect a valid key."); break;
                case "error": message = t("暂时无法查询余额，请重试。", "Could not retrieve your balance. Please try again."); break;
                case "ready":
                    if (result.balances.length() == 0) message = t("官方暂未返回余额明细。", "The platform returned no balance details.");
                    else if (!result.available) message = t("官方账户当前不可用于 API 调用。", "This platform account is currently unavailable for API calls.");
                    break;
            }
            String hint = key.isEmpty() ? t("充值将计入收银台登录的 DeepSeek 账户。", "Funds go to the DeepSeek account signed in at checkout.")
                    : t("请在收银台登录此密钥所属的 DeepSeek 账户。", "Use the DeepSeek account that owns this key at checkout.");
            panel.render(new JSONObject().put("labels", labels).put("presets", new JSONArray().put("10").put("50").put("100"))
                    .put("dark", dark).put("scale", scale).put("bound", !key.isEmpty()).put("accountHint", hint)
                    .put("status", result.status).put("refreshing", request != null).put("balances", result.balances)
                    .put("canChat", result.available).put("message", message));
            updateSidebar();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
