package com.jubitus.travellers.client.sound;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class VoiceIndex {
    public String defaultLang = "en";
    public String defaultPersonality = "default";
    public Map<String, List<String>> langs = new HashMap<>();

    public static VoiceIndex loadOrDefault() {
        try {
            InputStream in = VoiceIndex.class.getClassLoader()
                    .getResourceAsStream("assets/travellers/voices/index.json");
            if (in == null) return new VoiceIndex();

            InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            Type t = new TypeToken<VoiceIndex>(){}.getType();
            VoiceIndex idx = new Gson().fromJson(r, t);
            if (idx == null) return new VoiceIndex();
            if (idx.langs == null) idx.langs = new HashMap<>();
            if (idx.defaultLang == null) idx.defaultLang = "en";
            if (idx.defaultPersonality == null) idx.defaultPersonality = "default";
            return idx;
        } catch (Throwable ignored) {
            return new VoiceIndex();
        }
    }
}
