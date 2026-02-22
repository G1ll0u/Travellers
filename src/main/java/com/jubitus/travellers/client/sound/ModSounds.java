package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.VoiceSet;
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

        // Register EVERYTHING the generator created:
        // entity.travellers.<lang>.<personality>.<kind>
        for (java.util.Map.Entry<String, java.util.List<String>> en : idx.langs.entrySet()) {
            String lang = en.getKey().toLowerCase(java.util.Locale.ROOT);
            for (String personality : en.getValue()) {
                personality = personality.toLowerCase(java.util.Locale.ROOT);

                // 4 kinds
                reg(toReg, "entity.travellers." + lang + "." + personality + ".talk");
                reg(toReg, "entity.travellers." + lang + "." + personality + ".hurt");
                reg(toReg, "entity.travellers." + lang + "." + personality + ".hello");
                reg(toReg, "entity.travellers." + lang + "." + personality + ".ambient");

            }


        }
        RaiderVoiceIndex ridx = RaiderVoiceIndex.loadOrDefault();

// Register raiders events:
// entity.travellers.raiders.<lang>.<personality>.<kind>
        for (java.util.Map.Entry<String, java.util.List<String>> en : ridx.langs.entrySet()) {
            String lang = en.getKey().toLowerCase(java.util.Locale.ROOT);
            for (String personality : en.getValue()) {
                personality = personality.toLowerCase(java.util.Locale.ROOT);

                reg(toReg, "entity.travellers.raiders." + lang + "." + personality + ".talk");
                reg(toReg, "entity.travellers.raiders." + lang + "." + personality + ".hurt");
                reg(toReg, "entity.travellers.raiders." + lang + "." + personality + ".hello");
                reg(toReg, "entity.travellers.raiders." + lang + "." + personality + ".ambient");
            }
        }
        // Also register raiders if you want the same system (optional).
        // If you keep raiders fixed, keep your old raiders reg code here.

        // Define global fallbacks using defaultLang/defaultPersonality
        String dl = idx.defaultLang.toLowerCase(java.util.Locale.ROOT);
        String dp = idx.defaultPersonality.toLowerCase(java.util.Locale.ROOT);

        TRAVELLER_TALK  = new SoundEvent(new ResourceLocation(Reference.MOD_ID, "entity.travellers." + dl + "." + dp + ".talk"))
                .setRegistryName(new ResourceLocation(Reference.MOD_ID, "entity.travellers." + dl + "." + dp + ".talk"));
        TRAVELLER_HURT  = new SoundEvent(new ResourceLocation(Reference.MOD_ID, "entity.travellers." + dl + "." + dp + ".hurt"))
                .setRegistryName(new ResourceLocation(Reference.MOD_ID, "entity.travellers." + dl + "." + dp + ".hurt"));
        TRAVELLER_HELLO = new SoundEvent(new ResourceLocation(Reference.MOD_ID, "entity.travellers." + dl + "." + dp + ".hello"))
                .setRegistryName(new ResourceLocation(Reference.MOD_ID, "entity.travellers." + dl + "." + dp + ".hello"));
        AMBIENT         = new SoundEvent(new ResourceLocation(Reference.MOD_ID, "entity.travellers." + dl + "." + dp + ".ambient"))
                .setRegistryName(new ResourceLocation(Reference.MOD_ID, "entity.travellers." + dl + "." + dp + ".ambient"));

        // NOTE:
        // The above 4 objects are NOT the ones registered in toReg (different instances).
        // So instead of creating new instances, we should resolve from registry later.
        // We'll do that in assignVoicesToCultures() after registration.

        e.getRegistry().registerAll(toReg.toArray(new SoundEvent[0]));
        assignVoicesToCulturesDynamic(idx, ridx);

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
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".talk"));
        TRAVELLER_HURT  = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Reference.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".hurt"));
        TRAVELLER_HELLO = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Reference.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".hello"));
        AMBIENT         = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Reference.MOD_ID,
                "entity.travellers." + tDefaultLang + "." + tDefaultPers + ".ambient"));

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



    private static VoicePersonality regTravellerPersonality(java.util.List<SoundEvent> out,
                                                            String personalityId,
                                                            String baseKey) {
        // baseKey examples:
        // "entity.travellers.en"
        // "entity.travellers.en.actorb"
        SoundEvent talk = reg(out, baseKey + ".talk");
        SoundEvent hurt = reg(out, baseKey + ".hurt");
        SoundEvent hello = reg(out, baseKey + ".hello");
        SoundEvent amb = reg(out, baseKey + ".ambient");

        VoiceSet set = new VoiceSet(talk, hurt, hello, amb);
        return new VoicePersonality(personalityId, set);
    }
    private static String pickFallbackPersonalityForLang(String lang, String globalDefault, java.util.List<String> langPersonalities) {
        String dp = (globalDefault == null ? "default" : globalDefault).toLowerCase(java.util.Locale.ROOT);

        if (langPersonalities != null) {
            for (String p : langPersonalities) {
                if (p != null && p.equalsIgnoreCase(dp)) return dp;
            }
            // If the global default doesn't exist for this language (FR has no "default"),
            // use the first available personality for that language (e.g. "gilles").
            if (!langPersonalities.isEmpty() && langPersonalities.get(0) != null) {
                return langPersonalities.get(0).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return dp;
    }

}