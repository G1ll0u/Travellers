package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.sounds.MsgChoirSoundDone;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TravellerVoiceClientBus {
    private static final java.util.Map<Integer, MovingTravellerSound> LIVE = new java.util.HashMap<>();

    private TravellerVoiceClientBus() {
    }

    public static void play(EntityTraveller t, SoundEvent evt, float vol, float pitch, boolean isChoir) {
        if (t == null || evt == null) return;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null || mc.getSoundHandler() == null) return;

        stopIfAny(t.getEntityId());

        MovingTravellerSound now = new MovingTravellerSound(t, evt, vol, pitch);
        now.isChoir = isChoir;
        LIVE.put(t.getEntityId(), now);
        mc.getSoundHandler().playSound(now);
    }

    public static void play(EntityTraveller t, SoundEvent evt, float vol, float pitch) {
        play(t, evt, vol, pitch, false);
    }

    public static void stopIfAny(int entityId) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null || mc.getSoundHandler() == null) return;

        MovingTravellerSound old = LIVE.remove(entityId);
        if (old != null) {
            mc.getSoundHandler().stopSound(old);
            old.stopNow();
        }
    }

    public static void applyMute(int entityId, int durationMs) {
        MovingTravellerSound snd = LIVE.get(entityId);
        if (snd != null) {
            snd.muteUntilMs = Math.max(snd.muteUntilMs, System.currentTimeMillis() + durationMs);
        }
    }

    static void onSoundFinished(MovingTravellerSound snd) {
        if (snd == null) return;

        MovingTravellerSound cur = LIVE.get(snd.getTravellerEntityId());
        if (cur == snd) {
            LIVE.remove(snd.getTravellerEntityId());
        }

        // Notify server when a choir sound ends naturally so it can schedule a rejoin.
        if (snd.isChoir && !snd.stoppedExternally) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgChoirSoundDone(snd.getTravellerEntityId()));
        }
    }
}