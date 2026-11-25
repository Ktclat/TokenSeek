package io.tokenseek.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/** Opens the platform's sign-in page in a browser, which owns the form and its session. */
final class PlatformWebLogin {
    static final Uri SIGN_IN = Uri.parse("https://platform.deepseek.com/sign_in");
    private static final String TABS = "android.support.customtabs.extra.";
    private static final String TABS_ANDROIDX = "androidx.browser.customtabs.extra.";

    private PlatformWebLogin() {}

    static void open(Activity activity, Resources strings, boolean dark) {
        // Look up the system's web browsers, independent of apps handling DeepSeek links.
        // Only SIGN_IN is ever sent to the selected browser.
        Intent generic =
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"))
                        .addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager packages = activity.getPackageManager();
        Map<String, ResolveInfo> browsers = new LinkedHashMap<>();
        int flags = PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER;
        for (ResolveInfo match : packages.queryIntentActivities(generic, flags)) {
            ActivityInfo info = match.activityInfo;
            if (acceptsWebLinks(match.filter) && info != null && info.exported && info.enabled) {
                browsers.putIfAbsent(info.packageName, match);
            }
        }
        if (browsers.isEmpty()) {
            unavailable(activity, strings);
            return;
        }
        ResolveInfo preferred =
                packages.resolveActivity(generic, PackageManager.MATCH_DEFAULT_ONLY);
        ResolveInfo browser =
                preferred == null || preferred.activityInfo == null
                        ? null
                        : browsers.get(preferred.activityInfo.packageName);
        ResolveInfo[] choices = browsers.values().toArray(new ResolveInfo[0]);
        if (browser != null || choices.length == 1) {
            launch(activity, strings, browser != null ? browser : choices[0], dark);
            return;
        }
        String[] names = new String[choices.length];
        for (int i = 0; i < choices.length; i++)
            names[i] = choices[i].loadLabel(packages).toString();
        new AlertDialog.Builder(activity)
                .setTitle(strings.getString(R.string.ac_browser_picker))
                .setItems(names, (dialog, which) -> launch(activity, strings, choices[which], dark))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static boolean acceptsWebLinks(IntentFilter filter) {
        if (filter == null
                || !filter.hasDataScheme("https")
                || !filter.hasCategory(Intent.CATEGORY_BROWSABLE)
                || filter.countDataSchemeSpecificParts() != 0
                || filter.countDataPaths() != 0) return false;
        if (filter.countDataAuthorities() == 0) return true;
        for (int i = 0; i < filter.countDataAuthorities(); i++) {
            IntentFilter.AuthorityEntry authority = filter.getDataAuthority(i);
            if ("*".equals(authority.getHost()) && authority.getPort() == -1) return true;
        }
        return false;
    }

    private static void launch(
            Activity activity, Resources strings, ResolveInfo browser, boolean dark) {
        ActivityInfo info = browser.activityInfo;
        Intent tab =
                new Intent(Intent.ACTION_VIEW, SIGN_IN)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setComponent(new ComponentName(info.packageName, info.name));
        int surface = dark ? 0xff202125 : Color.WHITE;
        // Public Custom Tabs intent protocol. A regular browser can handle the same HTTPS VIEW.
        // The null binder requests a tab without establishing any browser-to-app session.
        Bundle extras = new Bundle();
        extras.putBinder(TABS + "SESSION", null);
        extras.putBoolean(TABS + "ENABLE_URLBAR_HIDING", false);
        extras.putInt(TABS + "TITLE_VISIBILITY", 1);
        extras.putInt(TABS_ANDROIDX + "COLOR_SCHEME", dark ? 2 : 1);
        extras.putInt(TABS + "TOOLBAR_COLOR", surface);
        extras.putInt(TABS_ANDROIDX + "NAVIGATION_BAR_COLOR", surface);
        Drawable back = activity.getDrawable(R.drawable.platform_back).mutate();
        back.setTint(dark ? 0xfff0f2f6 : 0xff20252c);
        Bitmap icon =
                Bitmap.createBitmap(
                        back.getIntrinsicWidth(),
                        back.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
        back.setBounds(0, 0, icon.getWidth(), icon.getHeight());
        back.draw(new Canvas(icon));
        extras.putParcelable(TABS + "CLOSE_BUTTON_ICON", icon);
        tab.putExtras(extras);
        try {
            activity.startActivity(tab);
        } catch (ActivityNotFoundException | SecurityException noBrowser) {
            unavailable(activity, strings);
        }
    }

    private static void unavailable(Activity activity, Resources strings) {
        Toast.makeText(
                        activity,
                        strings.getString(R.string.ac_browser_unavailable),
                        Toast.LENGTH_LONG)
                .show();
    }
}
