package com.jubitus.travellers.client.sound;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit "borrow this voice" declarations for a dialogue-only personality (a character sheet with
 * no sound folder of its own, see {@code PersonalityProfileLoader}) - lang -> personalityId -> the
 * id of another personality, same language, whose real clips it should use instead (2026-09-14
 * feedback). Entirely optional: a personality with no entry here still works, falling through to
 * {@code ModSounds}'s own per-language default instead.
 *
 * <p>File: {@code assets/travellers/personalities/voice_overrides.json}, e.g.:
 * <pre>{"fr": {"blase_nihiliste": "gilles_fou"}, "en": {"afraid": "default"}}</pre>
 */
public final class VoicePersonalityOverrides {

    public Map<String, Map<String, String>> overrides = new HashMap<>();

    public static VoicePersonalityOverrides loadOrDefault() {
        try (InputStream in = VoicePersonalityOverrides.class.getClassLoader()
                .getResourceAsStream("assets/travellers/personalities/voice_overrides.json")) {
            if (in == null) return new VoicePersonalityOverrides();

            InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            Type t = new TypeToken<Map<String, Map<String, String>>>() {
            }.getType();
            Map<String, Map<String, String>> parsed = new Gson().fromJson(r, t);

            VoicePersonalityOverrides result = new VoicePersonalityOverrides();
            if (parsed != null) result.overrides = parsed;
            return result;
        } catch (Throwable ignored) {
            return new VoicePersonalityOverrides();
        }
    }

    /** Nullable - the personality id (same language) this one should borrow real clips from, if declared. */
    public String get(String lang, String personalityId) {
        if (lang == null || personalityId == null) return null;
        Map<String, String> forLang = overrides.get(lang.toLowerCase(Locale.ROOT));
        if (forLang == null) return null;
        String target = forLang.get(personalityId.toLowerCase(Locale.ROOT));
        return (target == null || target.trim().isEmpty()) ? null : target.trim().toLowerCase(Locale.ROOT);
    }
}
