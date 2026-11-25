package io.tokenseek.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** User-initiated, local recognition of a payment QR displayed in the current merchant window. */
final class NativePaymentQr {
    private final Activity activity;
    private final Predicate<String> openAlipay;
    private final String locator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private long generation;
    private boolean busy;
    private AlertDialog dialog;

    NativePaymentQr(Activity activity, Predicate<String> openAlipay) {
        this.activity = activity;
        this.openAlipay = openAlipay;
        locator = LocalSite.bundledScript(activity, R.raw.platform_payment_qr);
    }

    static boolean supports(String url) {
        return PlatformNavigation.TOP_UP.equals(PlatformNavigation.platformPath(url))
                || PaymentHandoff.isAlipayCashier(url) || PaymentHandoff.isWechatH5(url);
    }

    void open(WebView view, BooleanSupplier isCurrent) {
        if (busy || view == null || !supports(view.getUrl()) || !isCurrent.getAsBoolean()) return;
        cancel();
        busy = true;
        String address = view.getUrl();
        long token = generation;
        BooleanSupplier current = () -> {
            if (token != generation) return false;
            boolean valid = !activity.isFinishing() && !activity.isDestroyed() && isCurrent.getAsBoolean()
                    && address.equals(view.getUrl()) && view.isShown();
            if (!valid) busy = false;
            return valid;
        };
        find(view, address, current, null);
    }

    private void find(WebView view, String address, BooleanSupplier current, PaymentQrCode previous) {
        String expression = "(window === top && location.href === " + JSONObject.quote(address)
                + ") ? (" + locator + "\n) : null";
        view.evaluateJavascript(expression, result -> {
            if (!current.getAsBoolean()) return;
            try {
                if (result == null) throw new JSONException("Missing QR region");
                JSONObject value = new JSONObject(result);
                if (value.optBoolean("wechatInApp") && merchantPage(address)) {
                    alternate(R.string.payment_qr_wechat_in_app);
                    return;
                }
                int w = view.getWidth(), h = view.getHeight();
                Rect area = new Rect((int) Math.floor(value.getDouble("l") * w), (int) Math.floor(value.getDouble("t") * h),
                        (int) Math.ceil(value.getDouble("r") * w), (int) Math.ceil(value.getDouble("b") * h));
                // The site may round at the viewport boundary by one CSS pixel.
                if (!area.intersect(0, 0, w, h)) { missing(address); return; }
                int[] at = new int[2];
                view.getLocationInWindow(at);
                area.offset(at[0], at[1]);
                Rect visible = new Rect();
                if (!view.getGlobalVisibleRect(visible)) { missing(address); return; }
                view.getRootView().getLocationInWindow(at);
                visible.offset(at[0], at[1]);
                if (!visible.contains(area) || area.width() < 48 || area.height() < 48) { missing(address); return; }
                String wallet = PlatformNavigation.TOP_UP.equals(PlatformNavigation.platformPath(address))
                        ? value.optString("wallet") : "";
                copy(view, address, current, previous, wallet, area);
            } catch (JSONException | IllegalArgumentException invalid) {
                missing(address);
            }
        });
    }

    private void copy(WebView view, String address, BooleanSupplier current, PaymentQrCode previous, String wallet, Rect area) {
        float scale = Math.min(1f, 768f / Math.max(area.width(), area.height()));
        Bitmap image = Bitmap.createBitmap(Math.round(area.width() * scale), Math.round(area.height() * scale), Bitmap.Config.ARGB_8888);
        PixelCopy.request(activity.getWindow(), area, image, result -> {
            if (!current.getAsBoolean() || result != PixelCopy.SUCCESS) {
                image.recycle();
                if (current.getAsBoolean()) missing(address);
                return;
            }
            decoder.execute(() -> {
                int w = image.getWidth(), h = image.getHeight();
                int[] pixels = new int[w * h];
                image.getPixels(pixels, 0, w, 0, 0, w, h);
                PaymentQrCode code = PaymentQrCode.read(pixels, w, h, wallet);
                main.post(() -> {
                    if (!current.getAsBoolean()) { image.recycle(); return; }
                    if (code == null || (previous != null && (!previous.value.equals(code.value) || previous.mode != code.mode))) {
                        image.recycle();
                        missing(address);
                    } else if (previous == null) {
                        // A payment modal may have closed, changed wallet or replaced its order while decoding.
                        image.recycle();
                        find(view, address, current, code);
                    } else {
                        busy = false;
                        if (code.mode == PaymentQrCode.Mode.ALIPAY_LINK && openAlipay.test(code.value)) image.recycle();
                        else present(image, code.mode);
                    }
                });
            });
        }, main);
    }

    private static boolean merchantPage(String address) {
        return PlatformNavigation.TOP_UP.equals(PlatformNavigation.platformPath(address));
    }

    private void missing(String address) {
        busy = false;
        if (merchantPage(address)) alternate(R.string.payment_qr_missing);
        else Toast.makeText(activity, R.string.payment_qr_missing, Toast.LENGTH_LONG).show();
    }

    private void alternate(int explanation) {
        busy = false;
        AlertDialog shown = new AlertDialog.Builder(activity)
                .setTitle(R.string.payment_qr_action)
                .setMessage(explanation)
                .setPositiveButton(R.string.payment_qr_wechat_open, (d, w) -> openWechatHome())
                .setNegativeButton(R.string.payment_qr_return, null)
                .create();
        dialog = shown;
        shown.setOnDismissListener(d -> { if (dialog == shown) dialog = null; });
        shown.show();
    }

    private void present(Bitmap bitmap, PaymentQrCode.Mode mode) {
        boolean wechat = mode == PaymentQrCode.Mode.WECHAT_SCAN;
        int dp = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp, dp / 2, dp, dp / 2);
        ImageView image = new ImageView(activity);
        image.setImageBitmap(bitmap);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int height = Math.min(dp * 12, activity.getResources().getDisplayMetrics().heightPixels / 3);
        body.addView(image, new LinearLayout.LayoutParams(-1, height));
        TextView note = new TextView(activity);
        note.setText(wechat ? R.string.payment_qr_wechat_scan : R.string.payment_qr_alipay_scan);
        body.addView(note);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setView(body)
                .setTitle(wechat ? R.string.payment_qr_wechat : R.string.payment_qr_alipay)
                .setNegativeButton(R.string.payment_qr_return, null);
        if (wechat) builder.setPositiveButton(R.string.payment_qr_wechat_open, (d, w) -> openWechatHome());
        AlertDialog shown = builder.create();
        dialog = shown;
        shown.setOnDismissListener(d -> {
            if (dialog == shown) dialog = null;
            image.setImageDrawable(null);
            bitmap.recycle();
        });
        shown.show();
    }

    private void openWechatHome() {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.payment_qr_merchant), PaymentHandoff.MERCHANT));
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(PaymentHandoff.Provider.WECHAT.packageName);
        if (launch != null) {
            try {
                activity.startActivity(launch);
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // The copied link can still be opened manually in WeChat.
            }
        }
        Toast.makeText(activity, R.string.payment_qr_wechat_unavailable, Toast.LENGTH_LONG).show();
    }

    void cancel() {
        generation++;
        busy = false;
        if (dialog != null) dialog.dismiss();
    }

    void destroy() {
        cancel();
        decoder.shutdownNow();
    }
}
