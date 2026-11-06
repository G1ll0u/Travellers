package com.jubitus.travellers.sound;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.unit.culture.VoiceSet;
import com.jubitus.travellers.choir.ChoirIndex;
import com.jubitus.travellers.choir.ChoirRegistry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ModSounds {

    // Global / fallback events (optional but handy)
    public static SoundEvent TRAVELLER_HURT;
    public static SoundEvent TRAVELLER_TALK;
    public static SoundEvent TRAVELLER_HELLO;
    public static SoundEvent TRAVELLER_SONG1, TRAVELLER_SONG2, TRAVELLER_SONG3;
    public static SoundEvent AMBIENT;
    /** Raid cues; null until the user ships clips in sounds/raid/horn and sounds/raid/end. */
    @javax.annotation.Nullable public static SoundEvent RAID_HORN, RAID_END;
    /** A sip of a drink, any unit (variations in sounds/snack/sip); null until clips ship, vanilla's gulp then. */
    @javax.annotation.Nullable public static SoundEvent SNACK_SIP;

    public static VoiceSet EN_TRAV, FR_TRAV;
    public static VoiceSet EN_RAID, FR_RAID;

    // NEW: personality lists (add new actors here and everything else keeps working)
    public static final java.util.List<VoicePersonality> EN_TRAV_PERSONALITIES = new java.util.ArrayList<>();
    public static final java.util.List<VoicePersonality> FR_TRAV_PERSONALITIES = new java.util.ArrayList<>();


    private ModSounds() {
    }

    @SubscribeEvent
    public static void onRegisterSounds(net.minecraftforge.event.RegistryEvent.Register<SoundEvent> e) {
        java.util.List<SoundEvent> toReg = new java.util.ArrayList<>();

        VoiceIndex idx = VoiceIndex.loadOrDefault();
        RaiderVoiceIndex ridx = RaiderVoiceIndex.loadOrDefault();
        ChoirIndex cidx = ChoirIndex.loadOrDefault();

        // 1) Register all aggregate classic events
        registerAggregateEvents(toReg, idx, "entity.travellers");
        registerAggregateEvents(toReg, ridx, "entity.travellers.raiders");

        // 2) Register all exact clip events
        registerExactClipEvents(toReg, idx);
        registerExactClipEvents(toReg, ridx);

        // 3) Register choir stem events
        registerChoirEvents(toReg, cidx);

        // 4) Raid cues, only those sounds.json backs with clips
        java.util.Set<String> declared = declaredSoundEvents();
        if (declared.contains("raid.horn")) RAID_HORN = reg(toReg, "raid.horn");
        if (declared.contains("raid.end")) RAID_END = reg(toReg, "raid.end");
        if (declared.contains("snack.sip")) SNACK_SIP = reg(toReg, "snack.sip");

        e.getRegistry().registerAll(toReg.toArray(new SoundEvent[0]));
        assignVoicesToCulturesDynamic(idx, ridx);
        ChoirRegistry.load(cidx);
    }

    private static void registerChoirEvents(java.util.List<SoundEvent> out, ChoirIndex idx) {
        if (idx == null || idx.songs == null) return;
        for (java.util.Map.Entry<String, java.util.Map<String, java.util.List<ChoirIndex.SongEntry>>> langEntry : idx.songs.entrySet()) {
            if (langEntry.getValue() == null) continue;
            for (java.util.Map.Entry<String, java.util.List<ChoirIndex.SongEntry>> persEntry : langEntry.getValue().entrySet()) {
                if (persEntry.getValue() == null) continue;
                for (ChoirIndex.SongEntry entry : persEntry.getValue()) {
                    if (entry.stems == null) continue;
                    for (String stemKey : entry.stems) {
                        if (stemKey != null && !stemKey.isEmpty()) reg(out, stemKey);
                    }
                }
            }
        }
    }

    private static final String[] RAIDER_KINDS = {"talk", "hurt", "hello", "ambient", "attack", "kill", "death", "drunk"};
    private static final String[] TRAVELLER_KINDS = {"talk", "hurt", "hello", "ambient", "attack", "creeper", "kill", "death", "drunk"};

    private static void registerAggregateEvents(java.util.List<SoundEvent> out, RaiderVoiceIndex idx, String eventPrefix) {
        if (idx == null || idx.langs == null) return;

        for (java.util.Map.Entry<String, java.util.List<String>> en : idx.langs.entrySet()) {
            String lang = en.getKey().toLowerCase(java.util.Locale.ROOT);
            java.util.Map<String, java.util.Map<String, java.util.List<String>>> byPersonality =
                    idx.clips == null ? null : idx.clips.get(lang);

            for (String personality : en.getValue()) {
                if (personality == null) continue;
                personality = personality.toLowerCase(java.util.Locale.ROOT);

                // Only register an aggregate id for a (lang, personality, kind) that sounds.json
                // actually backs with real clips - a personality with no clips for a kind (or no
                // clips at all, e.g. a dialogue-only sheet with no sound folder) would otherwise get
                // a SoundEvent registered with nothing behind it (2026-09-14 feedback: "the mod
                // registers non-existent sounds").
                java.util.Map<String, java.util.List<String>> kindClips =
                        byPersonality == null ? null : byPersonality.get(personality);
                if (kindClips == null) continue;

                for (String kind : RAIDER_KINDS) {
                    java.util.List<String> exact = kindClips.get(kind);
                    if (exact == null || exact.isEmpty()) continue;
                    reg(out, eventPrefix + "." + lang + "." + personality + "." + kind);
                }
            }
        }
    }

    private static void registerExactClipEvents(java.util.List<SoundEvent> out, RaiderVoiceIndex idx) {
        if (idx == null || idx.clips == null) return;

        for (java.util.Map<String, java.util.Map<String, java.util.List<String>>> byLang : idx.clips.values()) {
            if (byLang == null) continue;

            for (java.util.Map<String, java.util.List<String>> byPersonality : byLang.values()) {
                if (byPersonality == null) continue;

                for (java.util.List<String> eventKeys : byPersonality.values()) {
                    if (eventKeys == null) continue;

                    for (String key : eventKeys) {
                        if (key == null || key.trim().isEmpty()) continue;
                        reg(out, key);
                    }
                }
            }
        }
    }

    private static void registerAggregateEvents(java.util.List<SoundEvent> out, VoiceIndex idx, String eventPrefix) {
        if (idx == null || idx.langs == null) return;

        for (java.util.Map.Entry<String, java.util.List<String>> en : idx.langs.entrySet()) {
            String lang = en.getKey().toLowerCase(java.util.Locale.ROOT);
            java.util.Map<String, java.util.Map<String, java.util.List<String>>> byPersonality =
                    idx.clips == null ? null : idx.clips.get(lang);

            for (String personality : en.getValue()) {
                if (personality == null) continue;
                personality = personality.toLowerCase(java.util.Locale.ROOT);

                // Same guard as the raider overload above - only register what sounds.json actually
                // backs. A sound-less personality (dialogue-only .md sheet, no sound folder - see
                // generateDynamicSounds) has an entry here with every kind empty, so it registers
                // nothing at all, which is correct: VoiceSet.isEmpty() already falls it through to
                // the culture's default voice set.
                java.util.Map<String, java.util.List<String>> kindClips =
                        byPersonality == null ? null : byPersonality.get(personality);
                if (kindClips == null) continue;

                for (String kind : TRAVELLER_KINDS) {
                    java.util.List<String> exact = kindClips.get(kind);
                    if (exact == null || exact.isEmpty()) continue;
                    reg(out, eventPrefix + "." + lang + "." + personality + "." + kind);
                }
            }
        }
    }

    private static void registerExactClipEvents(java.util.List<SoundEvent> out, VoiceIndex idx) {
        if (idx == null || idx.clips == null) return;

        for (java.util.Map<String, java.util.Map<String, java.util.List<String>>> byLang : idx.clips.values()) {
            if (byLang == null) continue;

            for (java.util.Map<String, java.util.List<String>> byPersonality : byLang.values()) {
                if (byPersonality == null) continue;

                for (java.util.List<String> eventKeys : byPersonality.values()) {
                    if (eventKeys == null) continue;

                    for (String key : eventKeys) {
                        if (key == null || key.trim().isEmpty()) continue;
                        reg(out, key);
                    }
                }
            }
        }
    }

    public static void assignVoicesToCulturesDynamic(VoiceIndex idx, RaiderVoiceIndex ridx) {
        // Clear old mappings
        CultureRegistry.TRAVELLER_VOICES.clear();
        CultureRegistry.TRAVELLER_VOICE_VARIANTS.clear();

        CultureRegistry.RAIDER_VOICES.clear();
        CultureRegistry.RAIDER_VOICE_VARIANTS.clear();

        VoicePersonalityOverrides overrides = VoicePersonalityOverrides.loadOrDefault();

        // ---------------- TRAVELLERS ----------------
        String tDefaultLang = (idx.defaultLang == null ? "en" : idx.defaultLang).toLowerCase(java.util.Locale.ROOT);
        String tDefaultPers = (idx.defaultPersonality == null ? "default" : idx.defaultPersonality).toLowerCase(java.util.Locale.ROOT);

        TRAVELLER_TALK  = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Tags.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".talk.__0"));
        TRAVELLER_HURT  = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Tags.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".hurt.__0"));
        TRAVELLER_HELLO = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Tags.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".hello.__0"));
        AMBIENT         = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Tags.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".ambient.__0"));

        // ---------------- RAIDERS ----------------
        String rDefaultLang = (ridx.defaultLang == null ? "en" : ridx.defaultLang).toLowerCase(java.util.Locale.ROOT);
        String rDefaultPers = (ridx.defaultPersonality == null ? "default" : ridx.defaultPersonality).toLowerCase(java.util.Locale.ROOT);

        // Optional: if you want global raiders fallbacks in ModSounds, add fields like RAIDER_TALK etc.
        // Otherwise we just resolve per-culture below.

        for (CultureDef def : CultureRegistry.all()) {
            String cultureId = def.id.toLowerCase(java.util.Locale.ROOT);

            String lang = CultureRegistry.langForCulture(cultureId).toLowerCase(java.util.Locale.ROOT);


            // ---- traveller variants ----
            java.util.List<String> tPersIds = idx.langs.get(lang);
            if (tPersIds == null) tPersIds = java.util.Collections.emptyList();
            java.util.Map<String, java.util.Map<String, java.util.List<String>>> tClipsForLang =
                    idx.clips == null ? null : idx.clips.get(lang);

            java.util.List<VoicePersonality> tPersonalities = new java.util.ArrayList<>();
            for (String pid : tPersIds) {
                if (pid == null) continue;
                String clean = pid.toLowerCase(java.util.Locale.ROOT).trim();
                if (clean.isEmpty()) continue;

                // A dialogue-only personality (character sheet, no sound folder) has no clips of its
                // own - borrow another personality's real voice instead of silently ending up with an
                // empty VoiceSet (2026-09-14 feedback: this is what made many norman travellers use
                // the English en/default voice instead of a french one).
                String sourcePid = resolveClipSourcePersonality(lang, clean, tClipsForLang, overrides,
                        idx.defaultPersonality, tPersIds);
                VoiceSet vs = CultureRegistry.buildVoiceSet(lang, sourcePid, idx.defaultLang, idx.defaultPersonality);
                tPersonalities.add(new VoicePersonality(clean, vs));
            }
            CultureRegistry.TRAVELLER_VOICE_VARIANTS.put(cultureId, tPersonalities);

            String tFallbackPers = pickFallbackPersonalityForLang(lang, idx.defaultPersonality, tPersIds, tClipsForLang);
            VoiceSet tFallback = CultureRegistry.buildVoiceSet(lang, tFallbackPers, idx.defaultLang, idx.defaultPersonality);
            CultureRegistry.TRAVELLER_VOICES.put(cultureId, tFallback);


            // ---- raiders variants (unchanged idea, but also use langForCulture) ----
            java.util.List<String> rPersIds = ridx.langs.get(lang);
            if (rPersIds == null) rPersIds = java.util.Collections.emptyList();
            java.util.Map<String, java.util.Map<String, java.util.List<String>>> rClipsForLang =
                    ridx.clips == null ? null : ridx.clips.get(lang);

            java.util.List<VoicePersonality> rPersonalities = new java.util.ArrayList<>();
            for (String pid : rPersIds) {
                if (pid == null) continue;
                String clean = pid.toLowerCase(java.util.Locale.ROOT).trim();
                if (clean.isEmpty()) continue;

                VoiceSet vs = CultureRegistry.buildRaiderVoiceSet(lang, clean, ridx.defaultLang, ridx.defaultPersonality);
                rPersonalities.add(new VoicePersonality(clean, vs));
            }
            CultureRegistry.RAIDER_VOICE_VARIANTS.put(cultureId, rPersonalities);

            String rFallbackPers = pickFallbackPersonalityForLang(lang, ridx.defaultPersonality, rPersIds, rClipsForLang);
            VoiceSet rFallback = CultureRegistry.buildRaiderVoiceSet(lang, rFallbackPers, ridx.defaultLang, ridx.defaultPersonality);
            CultureRegistry.RAIDER_VOICES.put(cultureId, rFallback);

        }

    }




    private static java.util.Set<String> declaredSoundEvents() {
        try (java.io.InputStream in = ModSounds.class.getClassLoader()
                .getResourceAsStream("assets/" + Tags.MOD_ID + "/sounds.json")) {
            if (in == null) return java.util.Collections.emptySet();
            com.google.gson.JsonObject root = new com.google.gson.JsonParser()
                    .parse(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) keys.add(e.getKey());
            return keys;
        } catch (Throwable ignored) {
            return java.util.Collections.emptySet();
        }
    }

    private static SoundEvent reg(java.util.List<SoundEvent> out, String keyPath) {
        final ResourceLocation id = new ResourceLocation(Tags.MOD_ID, keyPath);
        final SoundEvent ev = new SoundEvent(id).setRegistryName(id);
        out.add(ev);
        return ev;
    }

    public static void assignVoicesToCultures() {
        VoiceIndex idx = VoiceIndex.loadOrDefault();
        RaiderVoiceIndex ridx = RaiderVoiceIndex.loadOrDefault();
        assignVoicesToCulturesDynamic(idx, ridx);
    }




    /**
     * A whole culture's fallback voice (no specific personality picked) - resolves to the language's
     * own explicit default (see {@link #explicitLangDefault}), backed by whichever personality
     * actually has real clips.
     */
    private static String pickFallbackPersonalityForLang(String lang, String globalDefault,
            java.util.List<String> langPersonalities,
            java.util.Map<String, java.util.Map<String, java.util.List<String>>> clipsForLang) {
        String langDefault = explicitLangDefault(lang, globalDefault);
        return resolveClipSourcePersonality(lang, langDefault, clipsForLang, null, globalDefault, langPersonalities);
    }

    /**
     * The maintainer's own explicit default personality per language (2026-09-14 feedback) - not
     * whichever id happened to sort first alphabetically, which silently became a dialogue-only,
     * sound-less sheet once those started sharing the same personality folders as real voiced ones.
     */
    private static String explicitLangDefault(String lang, String globalDefault) {
        if ("fr".equalsIgnoreCase(lang)) return "gilles";
        return (globalDefault == null ? "default" : globalDefault).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Which personality's real clips should back {@code personalityId} in {@code lang}: itself if it
     * has any, else its explicit "borrow this voice" override ({@link VoicePersonalityOverrides}) if
     * that target actually has clips, else the language's own explicit default
     * ({@link #explicitLangDefault}), else the first personality in the language that has any real
     * clips at all, else the raw global default as a last resort (an empty {@link VoiceSet} is still
     * possible here and is handled fine further up - see {@code VoiceSet.isEmpty()}).
     */
    private static String resolveClipSourcePersonality(String lang, String personalityId,
            java.util.Map<String, java.util.Map<String, java.util.List<String>>> clipsForLang,
            VoicePersonalityOverrides overrides, String globalDefault, java.util.List<String> langPersonalities) {

        if (hasRealClips(clipsForLang, personalityId)) return personalityId;

        String borrowed = overrides == null ? null : overrides.get(lang, personalityId);
        if (borrowed != null && hasRealClips(clipsForLang, borrowed)) return borrowed;

        String langDefault = explicitLangDefault(lang, globalDefault);
        if (hasRealClips(clipsForLang, langDefault)) return langDefault;

        if (langPersonalities != null) {
            for (String p : langPersonalities) {
                if (p != null && hasRealClips(clipsForLang, p.toLowerCase(java.util.Locale.ROOT))) {
                    return p.toLowerCase(java.util.Locale.ROOT);
                }
            }
        }
        return langDefault;
    }

    private static boolean hasRealClips(java.util.Map<String, java.util.Map<String, java.util.List<String>>> clipsForLang,
            String personalityId) {
        java.util.Map<String, java.util.List<String>> kindClips = clipsForLang == null ? null : clipsForLang.get(personalityId);
        if (kindClips == null) return false;
        for (java.util.List<String> keys : kindClips.values()) {
            if (keys != null && !keys.isEmpty()) return true;
        }
        return false;
    }

}