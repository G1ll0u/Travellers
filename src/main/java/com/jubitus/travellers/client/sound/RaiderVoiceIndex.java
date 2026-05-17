package com.jubitus.travellers.client.sound;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RaiderVoiceIndex {
    public String defaultLang = "en";
    public String defaultPersonality = "default";

    // lang -> [personality ids]
    public Map<String, List<String>> langs = new HashMap<>();

    // lang -> personality -> kind -> [eventKey1, eventKey2, ...]
    public Map<String, Map<String, Map<String, List<String>>>> clips = new HashMap<>();

    public static RaiderVoiceIndex loadOrDefault() {
        try {
            InputStream in = RaiderVoiceIndex.class.getClassLoader()
                    .getResourceAsStream("assets/travellers/voices/raiders_index.json");
            if (in == null) return new RaiderVoiceIndex();

            InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            Type t = new TypeToken<RaiderVoiceIndex>() {}.getType();
            RaiderVoiceIndex idx = new Gson().fromJson(r, t);
            if (idx == null) return new RaiderVoiceIndex();

            if (idx.langs == null) idx.langs = new HashMap<>();
            if (idx.clips == null) idx.clips = new HashMap<>();
            if (idx.defaultLang == null) idx.defaultLang = "en";
            if (idx.defaultPersonality == null) idx.defaultPersonality = "default";

            return idx;
        } catch (Throwable ignored) {
            return new RaiderVoiceIndex();
        }
    }
}