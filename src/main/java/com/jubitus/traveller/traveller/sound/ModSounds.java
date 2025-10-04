package com.jubitus.traveller.traveller.sound;

import com.jubitus.traveller.Reference;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID) // <- use YOUR mod id holder
public final class ModSounds {
    private ModSounds() {}

    public static SoundEvent TRAVELLER_HURT1;
    public static SoundEvent TRAVELLER_HURT2;
    public static SoundEvent TRAVELLER_HURT3;
    public static SoundEvent TRAVELLER_HURT_RARE;

    private static SoundEvent sound(String path) {
        ResourceLocation id = new ResourceLocation(Reference.MOD_ID, path);
        return new SoundEvent(id).setRegistryName(id);
    }

    @SubscribeEvent
    public static void onRegisterSounds(net.minecraftforge.event.RegistryEvent.Register<SoundEvent> e) {
        TRAVELLER_HURT1     = sound("entity.traveller.hurt1");
        TRAVELLER_HURT2     = sound("entity.traveller.hurt2");
        TRAVELLER_HURT3     = sound("entity.traveller.hurt3");
        TRAVELLER_HURT_RARE = sound("entity.traveller.hurt_rare");
        e.getRegistry().registerAll(TRAVELLER_HURT1, TRAVELLER_HURT2, TRAVELLER_HURT3, TRAVELLER_HURT_RARE);
    }
}

