package com.jubitus.travellers.choir;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ChoirIndex {
    // lang -> personality -> [SongEntry]
    public Map<String, Map<String, List<SongEntry>>> songs = new HashMap<>();
    // lang -> alias personality -> target personality (shares the same songs)
    public Map<String, Map<String, String>> aliases = new HashMap<>();

    public static final class SongEntry {
        public String name;
        public List<String> stems; // event keys e.g. "entity.travellers.fr.gilles.choir.mysong.__0"
        public int durationTicks; // longest stem duration in ticks; 0 = fall back to config
    }

    public static ChoirIndex loadOrDefault() {
        try {
            InputStream in = ChoirIndex.class.getClassLoader()
                    .getResourceAsStream("assets/travellers/voices/choir_index.json");
            if (in == null) return new ChoirIndex();
            InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            Type t = new TypeToken<ChoirIndex>() {}.getType();
            ChoirIndex idx = new Gson().fromJson(r, t);
            if (idx == null) return new ChoirIndex();
            if (idx.songs == null) idx.songs = new HashMap<>();
            return idx;
        } catch (Throwable ignored) {
            return new ChoirIndex();
        }
    }
}
