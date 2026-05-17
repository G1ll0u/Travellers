package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.VoiceSet;
import com.jubitus.travellers.entity.culture.choir.ChoirIndex;
import com.jubitus.travellers.entity.culture.choir.ChoirRegistry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class ModSounds {

    // Global / fallback events (optional but handy)
    public static SoundEvent TRAVELLER_HURT;
    public static SoundEvent TRAVELLER_TALK;
    public static SoundEvent TRAVELLER_HELLO;
    public static SoundEvent TRAVELLER_SONG1, TRAVELLER_SONG2, TRAVELLER_SONG3;
    public static SoundEvent AMBIENT;

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

    private static void registerAggregateEvents(java.util.List<SoundEvent> out, RaiderVoiceIndex idx, String eventPrefix) {
        if (idx == null || idx.langs == null) return;

        for (java.util.Map.Entry<String, java.util.List<String>> en : idx.langs.entrySet()) {
            String lang = en.getKey().toLowerCase(java.util.Locale.ROOT);
            for (String personality : en.getValue()) {
                if (personality == null) continue;
                personality = personality.toLowerCase(java.util.Locale.ROOT);

                reg(out, eventPrefix + "." + lang + "." + personality + ".talk");
                reg(out, eventPrefix + "." + lang + "." + personality + ".hurt");
                reg(out, eventPrefix + "." + lang + "." + personality + ".hello");
                reg(out, eventPrefix + "." + lang + "." + personality + ".ambient");
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
            for (String personality : en.getValue()) {
                if (personality == null) continue;
                personality = personality.toLowerCase(java.util.Locale.ROOT);

                reg(out, eventPrefix + "." + lang + "." + personality + ".talk");
                reg(out, eventPrefix + "." + lang + "." + personality + ".hurt");
                reg(out, eventPrefix + "." + lang + "." + personality + ".hello");
                reg(out, eventPrefix + "." + lang + "." + personality + ".ambient");
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

        // ---------------- TRAVELLERS ----------------
        String tDefaultLang = (idx.defaultLang == null ? "en" : idx.defaultLang).toLowerCase(java.util.Locale.ROOT);
        String tDefaultPers = (idx.defaultPersonality == null ? "default" : idx.defaultPersonality).toLowerCase(java.util.Locale.ROOT);

        TRAVELLER_TALK  = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Reference.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".talk.__0"));
        TRAVELLER_HURT  = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Reference.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".hurt.__0"));
        TRAVELLER_HELLO = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Reference.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".hello.__0"));
        AMBIENT         = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Reference.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".ambient.__0"));

        // ---------------- RAIDERS ----------------
        String rDefaultLang = (ridx.defaultLang == null ? "en" : ridx.defaultLang).toLowerCase(java.util.Locale.ROOT);
        String rDefaultPers = (ridx.defaultPersonality == null ? "default" : ridx.defaultPersonality).toLowerCase(java.util.Locale.ROOT);

        // Optional: if you want global raiders fallbacks in ModSounds, add fields like RAIDER_TALK etc.
        // Otherwise we just resolve per-culture below.

        for (CultureDef def : CultureRegistry.all()) {
            String cultureId = def.id.toLowerCase(java.util.Locale.ROOT);

            // Use your real mapping (norman->fr, german->de, etc.)
            String lang = CultureRegistry.langForCulture(cultureId).toLowerCase(java.util.Locale.ROOT);


            // ---- traveller variants ----
            java.util.List<String> tPersIds = idx.langs.get(lang);
            if (tPersIds == null) tPersIds = java.util.Collections.emptyList();

            java.util.List<VoicePersonality> tPersonalities = new java.util.ArrayList<>();
            for (String pid : tPersIds) {
                if (pid == null) continue;
                String clean = pid.toLowerCase(java.util.Locale.ROOT).trim();
                if (clean.isEmpty()) continue;

                VoiceSet vs = CultureRegistry.buildVoiceSet(lang, clean, idx.defaultLang, idx.defaultPersonality);
                tPersonalities.add(new VoicePersonality(clean, vs));
            }
            CultureRegistry.TRAVELLER_VOICE_VARIANTS.put(cultureId, tPersonalities);

            String tFallbackPers = pickFallbackPersonalityForLang(lang, idx.defaultPersonality, tPersIds);
            VoiceSet tFallback = CultureRegistry.buildVoiceSet(lang, tFallbackPers, idx.defaultLang, idx.defaultPersonality);
            CultureRegistry.TRAVELLER_VOICES.put(cultureId, tFallback);


            // ---- raiders variants (unchanged idea, but also use langForCulture) ----
            java.util.List<String> rPersIds = ridx.langs.get(lang);
            if (rPersIds == null) rPersIds = java.util.Collections.emptyList();

            java.util.List<VoicePersonality> rPersonalities = new java.util.ArrayList<>();
            for (String pid : rPersIds) {
                if (pid == null) continue;
                String clean = pid.toLowerCase(java.util.Locale.ROOT).trim();
                if (clean.isEmpty()) continue;

                VoiceSet vs = CultureRegistry.buildRaiderVoiceSet(lang, clean, ridx.defaultLang, ridx.defaultPersonality);
                rPersonalities.add(new VoicePersonality(clean, vs));
            }
            CultureRegistry.RAIDER_VOICE_VARIANTS.put(cultureId, rPersonalities);

            String rFallbackPers = pickFallbackPersonalityForLang(lang, ridx.defaultPersonality, rPersIds);
            VoiceSet rFallback = CultureRegistry.buildRaiderVoiceSet(lang, rFallbackPers, ridx.defaultLang, ridx.defaultPersonality);
            CultureRegistry.RAIDER_VOICES.put(cultureId, rFallback);

        }

    }




    private static SoundEvent reg(java.util.List<SoundEvent> out, String keyPath) {
        final ResourceLocation id = new ResourceLocation(Reference.MOD_ID, keyPath);
        final SoundEvent ev = new SoundEvent(id).setRegistryName(id);
        out.add(ev);
        return ev;
    }

    public static void assignVoicesToCultures() {
        VoiceIndex idx = VoiceIndex.loadOrDefault();
        RaiderVoiceIndex ridx = RaiderVoiceIndex.loadOrDefault();
        assignVoicesToCulturesDynamic(idx, ridx);
    }




    private static String pickFallbackPersonalityForLang(String lang, String globalDefault, java.util.List<String> langPersonalities) {
        String dp = (globalDefault == null ? "default" : globalDefault).toLowerCase(java.util.Locale.ROOT);

        if (langPersonalities != null) {
            for (String p : langPersonalities) {
                if (p != null && p.equalsIgnoreCase(dp)) return dp;
            }
            // If the global default doesn't exist for this language (FR has no "default"),
            // use the first available personality for that language (e.g. "default").
            if (!langPersonalities.isEmpty() && langPersonalities.get(0) != null) {
                return langPersonalities.get(0).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return dp;
    }

}