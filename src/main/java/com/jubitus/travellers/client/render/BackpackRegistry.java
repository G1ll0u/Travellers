package com.jubitus.travellers.client.render;

import com.jubitus.travellers.entity.travellers.EntityTraveller;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BackpackRegistry {
    private static final Map<String, IBackpackStyle> STYLES = new LinkedHashMap<>();

    public static void register(IBackpackStyle s) { STYLES.put(s.id(), s); }

    @Nullable
    public static IBackpackStyle get(String id) { return STYLES.get(id); }

    // simple resolver example: pick first that matches, else default
    public static IBackpackStyle resolve(EntityTraveller e) {

        String id = null;
        try { id = e.getBackpackStyleId(); } catch (Exception ignored) {}

        // Only use explicit id if player/server actually set one
        if (id != null) {
            String norm = id.trim().toLowerCase(java.util.Locale.ROOT);
            if (!norm.isEmpty() && !"auto".equals(norm)) {
                IBackpackStyle explicit = STYLES.get(norm);
                if (explicit != null) return explicit;
            }
        }

        // Auto mode: try matchers
        for (IBackpackStyle s : STYLES.values()) {
            if ("default".equals(s.id())) continue;
            if (s.matches(e)) return s;
        }

        return STYLES.get("default");
    }

}
