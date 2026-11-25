package io.tokenseek.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selects voices actually installed for the read-aloud language on this device. */
final class NativeVoiceOptions {
    private TextToSpeech engine;
    private boolean closed, loaded;
    private final List<Voice> voices = new ArrayList<>();

    void load(Context context, Locale language, Runnable changed) {
        Handler ui = new Handler(Looper.getMainLooper());
        try {
            engine = new TextToSpeech(context, status -> ui.post(() -> {
                if (closed) return;
                try {
                    if (status == TextToSpeech.SUCCESS && engine != null) {
                        Set<Voice> available = engine.getVoices();
                        if (available != null) for (Voice voice : available) {
                            if (usable(voice, language)) voices.add(voice);
                        }
                        Collections.sort(voices, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
                    }
                } catch (RuntimeException unavailable) { voices.clear(); }
                loaded = true;
                changed.run();
            }));
        } catch (RuntimeException unavailable) {
            loaded = true;
        }
    }

    boolean loaded() { return loaded; }
    List<Voice> values() { return voices; }

    static Voice find(Set<Voice> available, String name, Locale language) {
        if (available == null || name == null || name.isEmpty()) return null;
        for (Voice voice : available)
            if (usable(voice, language) && name.equals(voice.getName())) return voice;
        return null;
    }

    private static boolean usable(Voice voice, Locale language) {
        if (voice == null || voice.getLocale() == null || voice.getName() == null || voice.getName().isEmpty()
                || !voice.getLocale().getLanguage().equals(language.getLanguage()) || voice.isNetworkConnectionRequired()) return false;
        Set<String> features = voice.getFeatures();
        return features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    void close() {
        closed = true;
        if (engine != null) engine.shutdown();
        engine = null;
    }
}
