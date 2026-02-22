package com.jubitus.travellers.client.sound;

public final class RaiderVoiceIndex {
    public String defaultLang = "en";
    public String defaultPersonality = "default";
    public java.util.Map<String, java.util.List<String>> langs = new java.util.HashMap<>();

    public static RaiderVoiceIndex loadOrDefault() {
        try {
            java.io.InputStream in = RaiderVoiceIndex.class.getClassLoader()
                    .getResourceAsStream("assets/travellers/voices/raiders_index.json");
            if (in == null) return new RaiderVoiceIndex();

            java.io.InputStreamReader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8);
            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<RaiderVoiceIndex>() {}.getType();
            RaiderVoiceIndex idx = new com.google.gson.Gson().fromJson(r, t);

            if (idx == null) return new RaiderVoiceIndex();
            if (idx.langs == null) idx.langs = new java.util.HashMap<>();
            if (idx.defaultLang == null) idx.defaultLang = "en";
            if (idx.defaultPersonality == null) idx.defaultPersonality = "default";
            return idx;
        } catch (Throwable ignored) {
            return new RaiderVoiceIndex();
        }
    }
}
