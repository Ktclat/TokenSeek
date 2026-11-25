package io.tokenseek.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.Voice;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static io.tokenseek.app.NativeAccountStore.object;

/** Native local profile, API connection and settings navigation. */
final class NativeAccountPages extends FrameLayout {
    interface Host {
        void open(String destination);
        void changed(String reason);
        void pickPhoto();
    }
    private final Activity activity;
    private final Host host;
    private final KeyVault vault;
    private final NativeAccountStore store;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final Map<String, String> provinces, cities;
    private Resources strings;
    private NativeSettings settings;
    private NativeChatKeys keys;
    private NativeProfileEditor editor;
    private NativeVoiceOptions voices;
    private String page = "", balanceOwner = "", balance = "";
    private String keyOwner = "", selectedKeyId = "", selectedSuffix = "";
    private int savedKeyCount = -1;
    private boolean externalRoot;

    NativeAccountPages(Activity activity, KeyVault vault, Host host) {
        super(activity);
        this.activity = activity;
        this.vault = vault;
        this.host = host;
        store = new NativeAccountStore(activity);
        provinces = places(R.array.account_provinces);
        cities = places(R.array.account_cities);
    }

    private Map<String, String> places(int id) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String entry : getResources().getStringArray(id)) {
            int separator = entry.indexOf('|');
            names.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return names;
    }

    String page() { return page; }

    Bundle navigation() {
        Bundle navigation = new Bundle();
        navigation.putString("page", page);
        navigation.putStringArrayList("parents", new ArrayList<>(history));
        navigation.putBoolean("externalRoot", externalRoot);
        return navigation;
    }

    void restoreNavigation(Bundle navigation) {
        history.clear();
        ArrayList<String> parents = navigation.getStringArrayList("parents");
        if (parents != null) history.addAll(parents);
        externalRoot = navigation.getBoolean("externalRoot");
        show(navigation.getString("page", "home"));
    }

    void enter(String requested) {
        render(requested);
        history.clear();
        externalRoot = true;
    }

    void render(String requested) {
        externalRoot = false;
        history.clear();
        String next = requested == null || "settings".equals(requested) ? "home" : requested;
        boolean addKey = "addKey".equals(next);
        if (addKey) next = "keys";
        if (!"home".equals(next)) {
            history.push("home");
            if ("keys".equals(next) || "connect".equals(next) || "profile".equals(next)) history.push("account");
            if ("font".equals(next)) history.push("theme");
            if ("newprofile".equals(next)) history.push("profiles");
            if ("locale".equals(next) || "location".equals(next) || "voice".equals(next)) history.push("general");
        }
        show(next, addKey);
    }

    void back() {
        if (keys != null) keys.back();
        else pop();
    }
    private void pop() {
        if (history.isEmpty()) host.open(externalRoot ? "back" : "chat");
        else show(history.pop());
    }
    private void push(String to) {
        history.push(page);
        show(to);
    }
    void close() {
        releaseForm();
        closeVoices();
        settings = null;
        removeAllViews();
        history.clear();
        page = "";
    }
    void refresh() {
        if (keys != null) keys.refresh();
        else if (settings != null) show(page);
    }

    void setBalance(String owner, String summary) {
        if (!store.activeId().equals(owner)) return;
        String next = summary == null ? "" : summary;
        if (owner.equals(balanceOwner) && next.equals(balance)) return;
        balanceOwner = owner;
        balance = next;
        if ("account".equals(page)) refresh();
    }

    /** Consumes only the masked list already obtained by the host when it selects a chat key. */
    void setKeySummary(String owner, JSONArray entries, String activeId) {
        if (!store.activeId().equals(owner)) return;
        String selected = "", suffix = "";
        int count = entries == null ? -1 : entries.length();
        if (entries != null && activeId != null && !activeId.isEmpty()) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && activeId.equals(entry.optString("id"))) {
                    selected = activeId;
                    String tail = entry.optString("suffix", "");
                    if (tail.matches("[A-Za-z0-9_-]{4}")) suffix = tail;
                    break;
                }
            }
            if (selected.isEmpty()) count = -1;
        }
        if (owner.equals(keyOwner) && selected.equals(selectedKeyId) && suffix.equals(selectedSuffix) && count == savedKeyCount) return;
        if (!owner.equals(keyOwner) || !selected.equals(selectedKeyId)) {
            balance = "";
            balanceOwner = "";
        }
        keyOwner = owner;
        selectedKeyId = selected;
        selectedSuffix = suffix;
        savedKeyCount = count;
        if ("account".equals(page)) refresh();
    }

    void onPhotoResult(Uri selected) {
        if (editor != null) editor.onPhotoResult(selected);
    }

    private void releaseForm() {
        if (keys != null) keys.close();
        if (editor != null) editor.close();
        keys = null;
        editor = null;
    }

    private void closeVoices() {
        if (voices != null) voices.close();
        voices = null;
    }

    private void show(String next) { show(next, false); }

    private void show(String next, boolean addKey) {
        String locale = store.locale();
        Configuration config = new Configuration(getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(locale));
        strings = getContext().createConfigurationContext(config).getResources();
        releaseForm();
        page = next;
        if (!"voice".equals(next)) closeVoices();
        else if (voices == null) {
            voices = new NativeVoiceOptions();
            voices.load(activity, Locale.forLanguageTag(locale), () -> {
                if ("voice".equals(page) && voices != null) show(page);
            });
        }
        boolean connecting = "connect".equals(page);
        if (connecting || "keys".equals(page)) {
            settings = null;
            removeAllViews();
            keys = new NativeChatKeys(activity, vault, (action, owner) -> {
                switch (action) {
                    case "back": pop(); break;
                    case "chat": host.open("chat"); break;
                    case "changed": host.changed("keys"); break;
                    case "switch":
                        if (store.select(owner)) {
                            show("keys");
                            host.changed("profile");
                        } else error("storage");
                        break;
                    default: break;
                }
            });
            addView(keys, -1, -1);
            keys.render(object("page", "keys", "profile", store.activeId(), "profiles", store.profiles(),
                    "nickname", store.profile().optString("nickname"), "locale", locale, "dark", store.dark(), "scale", store.fontScale(),
                    "focusNewKey", connecting || addKey, "connectFromAccount", connecting));
            return;
        }
        if ("profile".equals(page) || "newprofile".equals(page)) {
            settings = null;
            removeAllViews();
            boolean create = "newprofile".equals(page);
            editor = new NativeProfileEditor(activity, strings, create ? null : store.profile(), store.dark(), store.fontScale(),
                    new NativeProfileEditor.Listener() {
                        public void back() { pop(); }
                        public void pickPhoto() { host.pickPhoto(); }
                        public void save(String user, String nickname, String avatar) {
                            String failure = store.saveProfile(create, user, nickname, avatar);
                            if (!failure.isEmpty()) {
                                editor.statusText(label("photo".equals(failure) ? "photo_error" : failure));
                                return;
                            }
                            pop();
                            host.changed("profile");
                        }
                    });
            addView(editor, -1, -1);
            return;
        }
        if (settings == null) {
            removeAllViews();
            settings = new NativeSettings(activity, (source, action) -> {
                if (source.equals(page)) act(action);
            });
            addView(settings, -1, -1);
        }
        JSONObject screen = object("page", page, "title", label(page), "dark", store.dark(), "scale", store.fontScale(),
                "back", strings.getString(R.string.chat_keys_back), "groups", groups());
        try {
            if ("account".equals(page)) screen.put("chat", strings.getString(R.string.chat_keys_return));
            if ("font".equals(page)) screen.put("sample", label("sample"));
            if ("location".equals(page)) screen.put("sample", strings.getString(R.string.ac_location_help));
            if ("voice".equals(page) && voices.values().isEmpty()) screen.put("sample", strings.getString(
                    voices.loaded() ? R.string.ac_voices_empty : R.string.ac_voices_loading));
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        settings.render(screen);
    }

    private JSONArray groups() {
        JSONArray groups = new JSONArray();
        JSONObject profile = store.profile();
        switch (page) {
            case "home":
                groups.put(group(null, link("account", profile.optString("nickname"))));
                groups.put(group(null, link("theme", label(store.preference("theme"))), link("personalization"), link("general"), link("data")));
                groups.put(group(null, link("profiles")));
                break;
            case "account":
                groups.put(group(strings.getString(R.string.ac_device_account), link("profile", profile.optString("nickname"))));
                groups.put(apiGroup(profile.optString("id")));
                break;
            case "profiles":
                JSONArray choices = new JSONArray(), rows = store.profiles();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    String id = row.optString("id"), name = row.optString("nickname"), username = row.optString("username");
                    choices.put(object("id", "switch/" + id, "label", username.equals(name) ? name : name + " · @" + username,
                            "selected", id.equals(store.activeId())));
                }
                groups.put(object("items", choices));
                groups.put(group(null, link("newprofile"), link("profile")));
                break;
            case "theme":
                groups.put(options("theme", null, "system", "light", "dark"));
                groups.put(group(null, link("font", label(store.preference("font")))));
                break;
            case "font":
                groups.put(options("font", null, "compact", "standard", "large", "largest"));
                break;
            case "personalization":
                groups.put(options("style", "style", "brief", "standard", "detailed"));
                groups.put(options("language", "language", "auto", "zh", "en"));
                break;
            case "general":
                groups.put(group(null, link("locale", label(store.locale())), link("voice"),
                        link("location", locationName())));
                break;
            case "locale": groups.put(options("locale", null, "zh-CN", "en-US")); break;
            case "voice":
                if (!voices.values().isEmpty()) groups.put(voiceChoices());
                break;
            case "location": case "cities": groups.put(locationOptions()); break;
            default: throw new IllegalArgumentException("Unknown native account page: " + page);
        }
        return groups;
    }

    private JSONObject apiGroup(String owner) {
        JSONArray rows = new JSONArray();
        int count = owner.equals(keyOwner) ? savedKeyCount : -1;
        if (count >= 0 && selectedKeyId.isEmpty()) rows.put(link("connect"));
        if (count != 0) {
            String summary;
            if (count < 0) summary = strings.getString(R.string.ac_keys_unavailable);
            else if (selectedKeyId.isEmpty()) summary = strings.getString(R.string.ac_keys_not_selected);
            else if (selectedSuffix.isEmpty()) summary = strings.getString(R.string.ac_key_selected);
            else summary = strings.getString(R.string.ac_key_selected_suffix, selectedSuffix);
            rows.put(link("keys", summary));
        }
        rows.put(link("wallet", owner.equals(balanceOwner) ? balance : ""));
        rows.put(link("usage"));
        return object("label", "DeepSeek API", "items", rows);
    }

    private JSONObject voiceChoices() {
        JSONArray rows = new JSONArray();
        String selected = store.preference("voiceName");
        boolean available = false;
        for (Voice voice : voices.values()) if (selected.equals(voice.getName())) available = true;
        rows.put(object("id", "set/voiceName/", "label", strings.getString(R.string.ac_voice_default), "selected", !available));
        for (Voice voice : voices.values()) {
            String name = voice.getName();
            rows.put(object("id", "set/voiceName/" + name, "label", name, "selected", name.equals(selected)));
        }
        return object("items", rows);
    }

    private JSONObject options(String name, String caption, String... values) {
        JSONArray rows = new JSONArray();
        String current = store.preference(name);
        for (String value : values)
            rows.put(object("id", "set/" + name + "/" + value, "label", label(value), "selected", value.equals(current)));
        return object("label", caption == null ? null : label(caption), "items", rows);
    }
    private JSONObject link(String page) { return link(page, null); }
    private JSONObject link(String page, String value) { return object("id", page, "label", label(page), "value", value); }
    private JSONObject group(String caption, JSONObject... rows) { return object("label", caption, "items", new JSONArray(Arrays.asList(rows))); }

    private JSONObject location() {
        try { return new JSONObject(store.preference("location")); }
        catch (JSONException ignored) { return new JSONObject(); }
    }
    private boolean useProvinces() {
        return "zh".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
    }
    private String country(String code) {
        return new Locale("", code).getDisplayCountry(Locale.forLanguageTag(store.locale()));
    }
    private String locationName() {
        String result = store.locationLabel();
        return result.isEmpty() ? label("unset") : result;
    }
    private JSONObject locationOptions() {
        JSONObject place = location();
        boolean city = "cities".equals(page);
        String scope = city ? "city" : useProvinces() ? "province" : "region";
        String active = place.optString(scope);
        JSONArray rows = new JSONArray();
        rows.put(object("id", scope + "/", "label", label("unset"), "selected", active.isEmpty()));
        Map<String, String> choices;
        if (city) {
            choices = new LinkedHashMap<>();
            String province = place.optString("province");
            if (province.length() == 6) for (Map.Entry<String, String> entry : cities.entrySet())
                if (entry.getKey().startsWith(province.substring(0, 2))) choices.put(entry.getKey(), entry.getValue());
        } else if (useProvinces()) choices = provinces;
        else {
            choices = new LinkedHashMap<>();
            ArrayList<String> codes = new ArrayList<>(Arrays.asList(Locale.getISOCountries()));
            Collator collator = Collator.getInstance(Locale.forLanguageTag(store.locale()));
            codes.sort((a, b) -> collator.compare(country(a), country(b)));
            for (String code : codes) choices.put(code, country(code));
        }
        for (Map.Entry<String, String> choice : choices.entrySet())
            rows.put(object("id", scope + "/" + choice.getKey(), "label", choice.getValue(), "selected", choice.getKey().equals(active)));
        return object("items", rows);
    }

    private void act(String action) {
        if ("back".equals(action)) { pop(); return; }
        if (action.startsWith("set/")) {
            String[] parts = action.split("/", 3);
            if (!store.setPreference(parts[1], parts[2])) { error("storage"); return; }
            if ("locale".equals(page) || "voice".equals(page)) pop();
            else show(page);
            host.changed("settings");
        } else if (action.startsWith("switch/")) {
            String id = action.substring(7);
            if (id.equals(store.activeId())) return;
            if (!store.select(id)) { error("storage"); return; }
            show(page);
            host.changed("profile");
        } else if ("location".equals(page) || "cities".equals(page)) {
            setLocation(action);
        } else {
            switch (action) {
                case "chat": case "wallet": case "usage": case "data": host.open(action); break;
                default: push(action);
            }
        }
    }

    private void setLocation(String action) {
        String[] parts = action.split("/", -1);
        if (parts.length != 2) return;
        String kind = parts[0], value = parts[1];
        JSONObject next = null;
        if ("city".equals(kind)) next = object("region", "CN", "province", location().optString("province"), "city", value.isEmpty() ? null : value);
        else if (!value.isEmpty()) {
            if ("province".equals(kind)) {
                next = value.equals(location().optString("province")) ? location() : object("region", "CN", "province", value);
            } else next = object("region", value);
        }
        if (!store.setPreference("location", next == null ? "" : next.toString())) { error("storage"); return; }
        if ("province".equals(kind) && !value.isEmpty()) push("cities");
        else {
            if ("city".equals(kind) && "location".equals(history.peek())) history.pop();
            pop();
        }
        host.changed("settings");
    }
    private void error(String code) { Toast.makeText(activity, label(code), Toast.LENGTH_SHORT).show(); }

    private String label(String key) {
        int id;
        switch (key) {
            case "home": id = R.string.ac_settings; break;
            case "account": id = R.string.ac_account; break;
            case "theme": id = R.string.ac_theme; break;
            case "font": id = R.string.ac_font; break;
            case "personalization": id = R.string.ac_personalization; break;
            case "general": id = R.string.ac_general; break;
            case "data": id = R.string.ac_data; break;
            case "profiles": id = R.string.ac_profiles; break;
            case "profile": id = R.string.ac_profile; break;
            case "newprofile": id = R.string.ac_newprofile; break;
            case "keys": id = R.string.ac_keys; break;
            case "connect": id = R.string.ac_connect; break;
            case "wallet": id = R.string.ac_wallet; break;
            case "usage": id = R.string.ac_usage; break;
            case "photo_error": id = R.string.ac_photo_error; break;
            case "duplicate": id = R.string.ac_duplicate; break;
            case "invalid": id = R.string.ac_invalid; break;
            case "storage": id = R.string.ac_storage; break;
            case "sample": id = R.string.ac_sample; break;
            case "style": id = R.string.ac_style; break;
            case "language": id = R.string.ac_language; break;
            case "locale": id = R.string.ac_locale; break;
            case "voice": id = R.string.ac_voice; break;
            case "location": id = R.string.ac_location; break;
            case "cities": id = R.string.ac_cities; break;
            case "unset": id = R.string.ac_unset; break;
            case "system": id = R.string.ac_system; break;
            case "light": id = R.string.ac_light; break;
            case "dark": id = R.string.ac_dark; break;
            case "compact": id = R.string.ac_compact; break;
            case "standard": id = R.string.ac_standard; break;
            case "large": id = R.string.ac_large; break;
            case "largest": id = R.string.ac_largest; break;
            case "brief": id = R.string.ac_brief; break;
            case "detailed": id = R.string.ac_detailed; break;
            case "auto": id = R.string.ac_auto; break;
            case "zh": id = R.string.ac_zh; break;
            case "en": id = R.string.ac_en; break;
            case "zh-CN": id = R.string.ac_zh_cn; break;
            case "en-US": id = R.string.ac_en_us; break;
            default: throw new IllegalArgumentException("Unknown account label: " + key);
        }
        return strings.getString(id);
    }
}
