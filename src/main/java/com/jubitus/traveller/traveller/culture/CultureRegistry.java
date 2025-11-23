package com.jubitus.traveller.traveller.culture;

public final class CultureRegistry {
    private static final java.util.Map<String, CultureDef> CULT = new java.util.LinkedHashMap<>();
    // Per-type voice maps (so travellers/raiders can differ if you want)
    public static final java.util.Map<String, VoiceSet> TRAVELLER_VOICES = new java.util.HashMap<>();
    public static final java.util.Map<String, VoiceSet> RAIDER_VOICES    = new java.util.HashMap<>();

    private CultureRegistry() {}

    public static void bootstrapDefaults() {
        if (!CULT.isEmpty()) return;

        // Texture counts are how many PNGs you’ll ship per culture.
        register(new CultureDef("en", "English", 8, 3));
        register(new CultureDef("fr", "Français", 8, 3));
    }

    public static void register(CultureDef def) { CULT.put(def.id, def); }

    public static CultureDef get(String id) {
        CultureDef d = CULT.get(id);
        if (d == null && !CULT.isEmpty()) return CULT.values().iterator().next();
        return d;
    }

    public static String pickRandomId(java.util.Random r) {
        java.util.List<String> ids = new java.util.ArrayList<>(CULT.keySet());
        return ids.get(r.nextInt(ids.size()));
    }

    public static java.util.Collection<CultureDef> all() { return CULT.values(); }
}
