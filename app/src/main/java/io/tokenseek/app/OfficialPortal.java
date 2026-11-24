package io.tokenseek.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/** Navigation shortcuts alongside the browser's own, authenticated DeepSeek pages. */
final class OfficialPortal {
    static final String TOP_UP = "io.tokenseek.app.portal.TOP_UP";
    static final String KEYS = "io.tokenseek.app.portal.KEYS";
    static final String BIND = "io.tokenseek.app.portal.BIND";
    static final Uri TOP_UP_URL = Uri.parse("https://platform.deepseek.com/top_up");
    static final Uri KEYS_URL = Uri.parse("https://platform.deepseek.com/api_keys");

    private OfficialPortal() {}

    static void addNextStep(Activity activity, Intent tab, Uri source) {
        String action;
        int label;
        String path = source.getPath();
        if (path == null || path.isEmpty() || "/".equals(path) || "/sign_in".equals(path)) {
            action = TOP_UP;
            label = R.string.portal_top_up;
        } else if ("/top_up".equals(path)) {
            action = KEYS;
            label = R.string.portal_keys;
        } else if ("/api_keys".equals(path)) {
            action = BIND;
            label = R.string.portal_bind;
        } else {
            return;
        }

        // Each shortcut has a fixed destination. No browser URL, credential or
        // payment result needs to be passed into the app when the user taps it.
        Intent next = new Intent(activity, MainActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent callback = PendingIntent.getActivity(activity, 0, next, PendingIntent.FLAG_IMMUTABLE);
        RemoteViews footer = new RemoteViews(activity.getPackageName(), R.layout.portal_shortcut);
        footer.setTextViewText(R.id.portal_next, activity.getText(label));
        tab.putExtra("android.support.customtabs.extra.SECONDARY_TOOLBAR_COLOR",
                activity.getColor(R.color.portal_background));
        tab.putExtra("android.support.customtabs.extra.EXTRA_REMOTEVIEWS", footer);
        tab.putExtra("android.support.customtabs.extra.EXTRA_REMOTEVIEWS_VIEW_IDS", new int[]{R.id.portal_next});
        tab.putExtra("android.support.customtabs.extra.EXTRA_REMOTEVIEWS_PENDINGINTENT", callback);
    }
}
