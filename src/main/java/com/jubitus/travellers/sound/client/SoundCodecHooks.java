package com.jubitus.travellers.sound.client;

import net.minecraftforge.client.event.sound.SoundSetupEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import paulscode.sound.SoundSystemConfig;

/**
 * Swaps the vanilla OGG codec for SafeCodecJOrbis every time the sound system
 * (re)loads. SoundSetupEvent fires right after vanilla registers CodecJOrbis,
 * so our registration always wins.
 */
@SideOnly(Side.CLIENT)
public final class SoundCodecHooks {

    private SoundCodecHooks() {
    }

    @SubscribeEvent
    public static void onSoundSetup(SoundSetupEvent event) {
        try {
            SoundSystemConfig.setCodec("ogg", SafeCodecJOrbis.class);
        } catch (Exception ignored) {
        }
    }
}
