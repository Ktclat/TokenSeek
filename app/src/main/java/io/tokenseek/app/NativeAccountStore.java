package io.tokenseek.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Profile IDs also identify the existing Keystore key ring and conversation database. */
final class NativeAccountStore {
    private static final String DIRECTORY = "directory";
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    private final Context context;

    NativeAccountStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("native_accounts_v1", Context.MODE_PRIVATE);
    }

    static JSONObject object(Object... pairs) {
        JSONObject out = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        } catch (JSONException ex) { throw new IllegalArgumentException(ex); }
        return out;
    }

    static boolean validId(String id) {
        return "default".equals(id) || id != null && id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    }

    private JSONObject directory() {
        String raw = prefs.getString(DIRECTORY, null);
        if (raw != null) {
            try { return new JSONObject(raw); }
            catch (JSONException e) { throw new IllegalStateException("Unreadable account directory", e); }
        }
        JSONObject original = object("id", "default", "username", "TokenSeek", "nickname", "TokenSeek");
        return object("active", "default", "profiles", new JSONArray().put(original));
    }

    String activeId() { return directory().optString("active"); }
    JSONArray profiles() { return directory().optJSONArray("profiles"); }
    JSONObject profile() {
        JSONObject data = directory();
        return lookup(data.optJSONArray("profiles"), data.optString("active"));
    }

    private static JSONObject lookup(JSONArray rows, String id) {
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) return row;
        }
        return null;
    }

    String saveProfile(boolean create, String username, String nickname, String avatar) {
        synchronized (LOCK) {
            JSONObject d = directory();
            JSONArray rows = d.optJSONArray("profiles");
            String id = create ? UUID.randomUUID().toString() : d.optString("active");
            username = username.trim();
            nickname = nickname.trim();
            if (nickname.isEmpty()) nickname = username;
            if (username.isEmpty() || username.length() > 64 || nickname.length() > 64) return "invalid";
            if (avatar != null && !NativeProfilePhoto.valid(avatar)) return "photo";
            JSONObject updated = object("id", id, "username", username, "nickname", nickname, "avatar", avatar);
            JSONArray next = new JSONArray();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject other = rows.optJSONObject(i);
                if (!id.equals(other.optString("id")) && canonical(username).equals(canonical(other.optString("username"))))
                    return "duplicate";
                next.put(id.equals(other.optString("id")) ? updated : other);
            }
            if (create) next.put(updated);
            return writeDirectory(id, next) ? "" : "storage";
        }
    }

    private static String canonical(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private boolean writeDirectory(String active, JSONArray rows) {
        return prefs.edit().putString(DIRECTORY, object("active", active, "profiles", rows).toString()).commit();
    }

    boolean select(String id) {
        synchronized (LOCK) {
            JSONArray all = profiles();
            return lookup(all, id) != null && writeDirectory(id, all);
        }
    }

    String preference(String name) {
        return prefs.getString(scoped(name) ? settingKey(name, activeId()) : name, defaultValue(name));
    }

    private static boolean scoped(String name) {
        return "style".equals(name) || "language".equals(name);
    }

    private static String settingKey(String name, String profile) {
        return scoped(name) ? name + "." + profile : name;
    }

    private String defaultValue(String name) {
        switch (name) {
            case "theme": return "system";
            case "font": case "style": return "standard";
            case "language": return "auto";
            case "locale": return "zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage()) ? "zh-CN" : "en-US";
            default: return "";
        }
    }

    boolean setPreference(String name, String value) {
        if (!validSetting(name, value)) return false;
        synchronized (LOCK) {
            return prefs.edit().putString(settingKey(name, activeId()), value).commit();
        }
    }

    private static boolean validSetting(String key, String value) {
        if (value == null) return false;
        switch (key) {
            case "theme": return value.matches("system|light|dark");
            case "font": return value.matches("compact|standard|large|largest");
            case "style": return value.matches("brief|standard|detailed");
            case "language": return value.matches("auto|zh|en");
            case "locale": return value.matches("zh-CN|en-US");
            case "voiceName": return value.length() <= 512 && value.chars().noneMatch(Character::isISOControl);
            case "location":
                if (value.isEmpty()) return true;
                if (value.length() > 256) return false;
                try { return new JSONObject(value).optString("region").matches("[A-Z]{2}"); }
                catch (JSONException invalid) { return false; }
            default: return false;
        }
    }

    String locale() { return preference("locale"); }
    String locationLabel() {
        JSONObject place;
        try { place = new JSONObject(preference("location")); }
        catch (JSONException absent) { return ""; }
        String region = place.optString("region");
        if (!region.matches("[A-Z]{2}")) return "";
        if ("CN".equals(region)) {
            String city = nameForCode(R.array.account_cities, place.optString("city"));
            if (!city.isEmpty()) return city;
            String province = nameForCode(R.array.account_provinces, place.optString("province"));
            if (!province.isEmpty()) return province;
        }
        return new Locale("", region).getDisplayCountry(Locale.forLanguageTag(locale()));
    }

    private String nameForCode(int resource, String code) {
        if (code.length() != 6) return "";
        for (String entry : context.getResources().getStringArray(resource))
            if (entry.startsWith(code + "|")) return entry.substring(7);
        return "";
    }

    int fontScale() {
        switch (preference("font")) {
            case "compact": return 90;
            case "large": return 115;
            case "largest": return 130;
            default: return 100;
        }
    }
    boolean dark() {
        String theme = preference("theme");
        return "dark".equals(theme) || "system".equals(theme) && (context.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Merge legacy account IDs and missing settings; existing native edits take precedence on retries. */
    boolean importLegacy(JSONObject legacy, JSONObject settings) {
        synchronized (LOCK) {
            SharedPreferences.Editor edit = prefs.edit();
            if (legacy != null) {
                JSONArray incoming = legacy.optJSONArray("profiles");
                JSONArray merged = prefs.contains(DIRECTORY) ? profiles() : new JSONArray();
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < merged.length(); i++) seen.add(merged.optJSONObject(i).optString("id"));
                if (incoming != null) for (int i = 0; i < incoming.length(); i++) {
                    JSONObject p = incoming.optJSONObject(i);
                    if (p == null) continue;
                    String id = p.optString("id"), user = p.optString("username").trim(), nick = p.optString("nickname").trim();
                    if (!validId(id) || user.isEmpty() || user.length() > 64 || nick.length() > 64 || !seen.add(id)) continue;
                    String avatar = p.optString("avatar");
                    merged.put(object("id", id, "username", user, "nickname", nick.isEmpty() ? user : nick,
                            "avatar", NativeProfilePhoto.valid(avatar) ? avatar : null));
                }
                if (merged.length() > 0) {
                    String active = prefs.contains(DIRECTORY) ? activeId() : legacy.optString("active");
                    if (!seen.contains(active)) active = merged.optJSONObject(0).optString("id");
                    edit.putString(DIRECTORY, object("active", active, "profiles", merged).toString());
                }
            }
            if (settings != null) {
                settings.keys().forEachRemaining(owner -> {
                    JSONObject values = settings.optJSONObject(owner);
                    boolean global = "global".equals(owner);
                    if (values == null || (!global && !validId(owner))) return;
                    values.keys().forEachRemaining(name -> {
                        String key = settingKey(name, owner);
                        if (global != key.equals(name) || prefs.contains(key)) return;
                        String val = values.optString(name);
                        if (validSetting(name, val)) edit.putString(key, val);
                    });
                });
            }
            return edit.commit();
        }
    }
}
