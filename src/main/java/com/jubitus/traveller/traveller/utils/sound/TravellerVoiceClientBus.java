package com.jubitus.traveller.traveller.utils.sound;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TravellerVoiceClientBus {
    private static final java.util.Map<Integer, MovingTravellerSound> LIVE = new java.util.HashMap<>();

    private TravellerVoiceClientBus() {}

    public static void play(EntityTraveller t, SoundEvent evt, float vol, float pitch) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();

        // Preempt any existing moving sound for this traveller
        MovingTravellerSound old = LIVE.remove(t.getEntityId());
        if (old != null) {
            mc.getSoundHandler().stopSound(old);
            old.stopNow(); // ✅ preempt solo
        }


        MovingTravellerSound now = new MovingTravellerSound(t, evt, vol, pitch);
        LIVE.put(t.getEntityId(), now);
        mc.getSoundHandler().playSound(now);
    }

    public static void stopIfAny(int entityId) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        MovingTravellerSound old = LIVE.remove(entityId);
        if (old != null) {
            mc.getSoundHandler().stopSound(old);
            old.stopNow();
        }
    }
}
