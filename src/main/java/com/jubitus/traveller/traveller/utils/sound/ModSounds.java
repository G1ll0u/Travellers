package com.jubitus.traveller.traveller.utils.sound;

import com.jubitus.traveller.Reference;
import com.jubitus.traveller.traveller.culture.CultureDef;
import com.jubitus.traveller.traveller.culture.CultureRegistry;
import com.jubitus.traveller.traveller.culture.VoiceSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class ModSounds {

    // Fallbacks (keep your existing fields)
    public static SoundEvent TRAVELLER_HURT;
    public static SoundEvent TRAVELLER_TALK;
    public static SoundEvent TRAVELLER_HELLO;          // NEW fallback
    public static SoundEvent TRAVELLER_SONG1, TRAVELLER_SONG2, TRAVELLER_SONG3;
    public static SoundEvent AMBIENT;

    private ModSounds() {}

    @SubscribeEvent
    public static void onRegisterSounds(net.minecraftforge.event.RegistryEvent.Register<SoundEvent> e) {
        // Ensure cultures exist (en, fr, ...), and texture counts are set.
        CultureRegistry.bootstrapDefaults();

        java.util.List<SoundEvent> toReg = new java.util.ArrayList<>();

        // ---- Fallback / non-culture pooled keys (match your current sounds.json) ----
        TRAVELLER_TALK  = reg(toReg, "entity.traveller.talk");
        TRAVELLER_HURT  = reg(toReg, "entity.traveller.hurt");
        TRAVELLER_HELLO = reg(toReg, "entity.traveller.hello");   // NEW fallback
        AMBIENT         = reg(toReg, "entity.traveller.ambient");

        // Keep your discrete ambient# too (they’re used elsewhere / remain valid)
        TRAVELLER_SONG1 = reg(toReg, "entity.traveller.ambient1");
        TRAVELLER_SONG2 = reg(toReg, "entity.traveller.ambient2");
        TRAVELLER_SONG3 = reg(toReg, "entity.traveller.ambient3");

        // ---- Culture-scoped keys ----
        for (CultureDef def : CultureRegistry.all()) {
            final String c = def.id; // e.g., "en", "fr"

            // Traveller voices per culture (pooled ambient)
            SoundEvent tTalk  = reg(toReg, "entity.traveller." + c + ".talk");
            SoundEvent tHurt  = reg(toReg, "entity.traveller." + c + ".hurt");
            SoundEvent tHello = reg(toReg, "entity.traveller." + c + ".hello");
            SoundEvent tAmb   = reg(toReg, "entity.traveller." + c + ".ambient");

            // Store for runtime lookup
            // Ensure your VoiceSet is: new VoiceSet(SoundEvent talk, SoundEvent hurt, SoundEvent hello, SoundEvent... ambient)
            CultureRegistry.TRAVELLER_VOICES.put(c, new VoiceSet(tTalk, tHurt, tHello, tAmb));

            // --- Optional: raider-specific voices (uncomment if you add the sounds)
            // SoundEvent rTalk = reg(toReg, "entity.raider." + c + ".talk");
            // SoundEvent rHurt = reg(toReg, "entity.raider." + c + ".hurt");
            // SoundEvent rAmb  = reg(toReg, "entity.raider." + c + ".ambient");
            // CultureRegistry.RAIDER_VOICES.put(c, new VoiceSet(rTalk, rHurt, null, rAmb));
        }

        e.getRegistry().registerAll(toReg.toArray(new SoundEvent[0]));
    }

    // Helper that creates + tracks a SoundEvent by registry key (matches keys in sounds.json)
    private static SoundEvent reg(java.util.List<SoundEvent> out, String keyPath) {
        final ResourceLocation id = new ResourceLocation(Reference.MOD_ID, keyPath);
        final SoundEvent ev = new SoundEvent(id).setRegistryName(id);
        out.add(ev);
        return ev;
    }
}