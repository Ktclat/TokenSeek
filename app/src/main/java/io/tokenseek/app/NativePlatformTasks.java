package io.tokenseek.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

import static io.tokenseek.app.NativeAccountStore.object;

/** The public API cannot create a merchant order. No external action occurs until the user taps its link. */
final class NativePlatformTasks {
    private static final String CASHIER = "https://platform.deepseek.com/top_up";
    private static final String DIRECTORY = "https://platform.deepseek.com/api_keys";
    private static final String BILLS = "https://platform.deepseek.com/transactions";

    private final Activity activity;
    private final boolean english, dark;
    private final int scale;
    private final Runnable back;

    NativePlatformTasks(Activity activity, boolean english, boolean dark, int scale, Runnable back) {
        this.activity = activity;
        this.english = english;
        this.dark = dark;
        this.scale = scale;
        this.back = back;
    }

    View checkout(String raw) {
        String amount = NativeFunding.parseAmount(raw).value;
        if (amount.isEmpty()) {
            NativeSettings view = new NativeSettings(activity, (page, id) -> back.run());
            view.render(screen("checkout", text("充值", "Top-up"), text("请输入有效的充值金额。", "Enter a valid top-up amount."),
                    group(null, link("back", text("修改金额", "Edit amount")))));
            return view;
        }
        NativeSettings view = new NativeSettings(activity, (page, id) -> {
            switch (id) {
                case "back": back.run(); break;
                case "copy":
                    ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(text("充值金额", "Top-up amount"), amount));
                        if (android.os.Build.VERSION.SDK_INT < 33) toast(text("已复制金额", "Amount copied"));
                    }
                    break;
                case "official": open(CASHIER); break;
                default: break;
            }
        });
        view.render(screen("checkout", text("充值确认", "Confirm top-up"),
                text("请在 DeepSeek 官方收银台确认实际充值金额和付款方式。", "Confirm the amount and payment method in the official DeepSeek cashier."),
                group(null, object("id", "copy", "label", text("复制充值金额", "Copy top-up amount"), "value", "¥" + amount)),
                group(null, link("official", text("在浏览器打开官方收银台", "Open the official cashier in browser")))));
        return view;
    }

    View directory() { return directory(null); }
    View directory(Runnable bindKey) {
        NativeSettings view = new NativeSettings(activity, (page, id) -> {
            if ("back".equals(id)) back.run();
            if ("official".equals(id)) open(DIRECTORY);
            if ("bind".equals(id) && bindKey != null) bindKey.run();
        });
        JSONObject official = link("official", text("在浏览器管理官方密钥", "Manage platform keys in browser"));
        JSONObject actions = bindKey == null ? group(null, official)
                : group(null, official, link("bind", text("绑定已有聊天密钥", "Connect an existing chat key")));
        view.render(screen("officialKeys", text("开放平台密钥", "Platform API keys"),
                text("登录 DeepSeek 开放平台，可查看全部密钥，或创建、重命名和删除密钥。",
                        "Sign in to DeepSeek Platform to view all keys, create one, rename it or delete it."), actions));
        return view;
    }

    View bills() {
        NativeSettings view = new NativeSettings(activity, (page, id) -> {
            if ("back".equals(id)) back.run();
            if ("official".equals(id)) open(BILLS);
        });
        view.render(screen("bills", text("账单", "Bills"),
                text("充值记录和支付状态可在 DeepSeek 开放平台的账单中查询。", "DeepSeek Platform lists your top-ups and payment status."),
                group(null, link("official", text("在浏览器查看官方账单", "View official bills in browser")))));
        return view;
    }

    private void open(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            toast(text("无法打开官方平台，请检查浏览器。", "The official platform could not be opened. Check your browser."));
        }
    }
    private JSONObject screen(String page, String title, String message, JSONObject... groups) {
        return object("page", page, "title", title, "dark", dark, "scale", scale, "back", text("返回", "Back"),
                "sample", message, "groups", new JSONArray(Arrays.asList(groups)));
    }
    private static JSONObject group(String name, JSONObject... rows) {
        return object("label", name, "items", new JSONArray(Arrays.asList(rows)));
    }
    private static JSONObject link(String id, String title) { return object("id", id, "label", title); }
    private String text(String zh, String en) { return english ? en : zh; }
    private void toast(String msg) { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show(); }
}
