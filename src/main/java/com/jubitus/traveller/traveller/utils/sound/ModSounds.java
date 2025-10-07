package com.jubitus.traveller.traveller.utils.sound;

import com.jubitus.traveller.Reference;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class ModSounds {

    private ModSounds() {}

    public static SoundEvent TRAVELLER_HURT;

    public static SoundEvent TRAVELLER_TALK;

    public static SoundEvent TRAVELLER_SONG1, TRAVELLER_SONG2, TRAVELLER_SONG3;
    public static SoundEvent AMBIENT;


    private static SoundEvent sound(String path) {
        ResourceLocation id = new ResourceLocation(Reference.MOD_ID, path);
        return new SoundEvent(id).setRegistryName(id);
    }

    @SubscribeEvent
    public static void onRegisterSounds(net.minecraftforge.event.RegistryEvent.Register<SoundEvent> e) {
        TRAVELLER_HURT     = sound("entity.traveller.hurt");

        TRAVELLER_TALK      = sound("entity.traveller.talk");
        e.getRegistry().registerAll(
                TRAVELLER_HURT,
                TRAVELLER_TALK,
                TRAVELLER_SONG1 = sound("entity.traveller.ambient1"),
        AMBIENT = sound("entity.traveller.ambient"),
        TRAVELLER_SONG2 = sound("entity.traveller.ambient2"),
        TRAVELLER_SONG3 = sound("entity.traveller.ambient3")
        );
    }
}