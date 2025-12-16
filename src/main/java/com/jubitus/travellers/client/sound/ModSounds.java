package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.VoiceSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

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

    private ModSounds() {
    }

    @SubscribeEvent
    public static void onRegisterSounds(net.minecraftforge.event.RegistryEvent.Register<SoundEvent> e) {

        java.util.List<SoundEvent> toReg = new java.util.ArrayList<>();

        // If you actually want 3 “songs”, give them their own entries in sounds.json
        // or just drop these three lines if you don't need them:
        TRAVELLER_SONG1 = reg(toReg, "entity.traveller.ambient1");
        TRAVELLER_SONG2 = reg(toReg, "entity.traveller.ambient2");
        TRAVELLER_SONG3 = reg(toReg, "entity.traveller.ambient3");

        // ---- EN profile (default) ----
        SoundEvent enTalk = reg(toReg, "entity.traveller.en.talk");
        SoundEvent enHurt = reg(toReg, "entity.traveller.en.hurt");
        SoundEvent enHello = reg(toReg, "entity.traveller.en.hello");
        SoundEvent enAmb = reg(toReg, "entity.traveller.en.ambient");

        EN_TRAV = new VoiceSet(enTalk, enHurt, enHello, enAmb);

        // ---- FR profile ----
        SoundEvent frTalk = reg(toReg, "entity.traveller.fr.talk");
        SoundEvent frHurt = reg(toReg, "entity.traveller.fr.hurt");
        SoundEvent frHello = reg(toReg, "entity.traveller.fr.hello");
        SoundEvent frAmb = reg(toReg, "entity.traveller.fr.ambient");

        FR_TRAV = new VoiceSet(frTalk, frHurt, frHello, frAmb);

        // ---- Raiders ----
        SoundEvent enRaidTalk = reg(toReg, "entity.raider.en.talk");
        SoundEvent enRaidHurt = reg(toReg, "entity.raider.en.hurt");
        SoundEvent enRaidAmb = reg(toReg, "entity.raider.en.ambient");
        EN_RAID = new VoiceSet(enRaidTalk, enRaidHurt, null, enRaidAmb);

        SoundEvent frRaidTalk = reg(toReg, "entity.raider.fr.talk");
        SoundEvent frRaidHurt = reg(toReg, "entity.raider.fr.hurt");
        SoundEvent frRaidAmb = reg(toReg, "entity.raider.fr.ambient");
        FR_RAID = new VoiceSet(frRaidTalk, frRaidHurt, null, frRaidAmb);

        // (We’ll move the culture loop out of here; see step 2)

        // Set global fallbacks (EN as default)
        TRAVELLER_TALK = enTalk;
        TRAVELLER_HURT = enHurt;
        TRAVELLER_HELLO = enHello;
        AMBIENT = enAmb;

        e.getRegistry().registerAll(toReg.toArray(new SoundEvent[0]));
    }


    private static SoundEvent reg(java.util.List<SoundEvent> out, String keyPath) {
        final ResourceLocation id = new ResourceLocation(Reference.MOD_ID, keyPath);
        final SoundEvent ev = new SoundEvent(id).setRegistryName(id);
        out.add(ev);
        return ev;
    }

    public static void assignVoicesToCultures() {
        // If sounds haven’t been registered yet, do nothing.
        if (EN_TRAV == null || EN_RAID == null) {
            System.out.println("[Travellers] Sounds not ready yet, skipping voice assignment.");
            return;
        }

        for (CultureDef def : CultureRegistry.all()) {
            String id = def.id.toLowerCase(java.util.Locale.ROOT);

            VoiceSet travSet = EN_TRAV;
            VoiceSet raidSet = EN_RAID;

            // HERE is where we use “contains” instead of equals
            if (id.contains("norman")) {
                travSet = FR_TRAV;
                raidSet = FR_RAID;
            }

            CultureRegistry.TRAVELLER_VOICES.put(id, travSet);
            CultureRegistry.RAIDER_VOICES.put(id, raidSet);
        }
    }

}