package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.sounds.MsgChoirSoundDone;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TravellerVoiceClientBus {
    private static final java.util.Map<Integer, EntityLinkedSound> LIVE = new java.util.HashMap<>();

    private TravellerVoiceClientBus() {
    }

    public static void play(EntityTraveller t, SoundEvent evt, float vol, float pitch, boolean isChoir) {
        if (t == null || evt == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSoundHandler() == null) return;

        EntityPlayer player = mc.player;
        if (player != null) {
            double maxDist = TravellersConfig.SOUND.voiceMaxDistance;
            if (t.getDistanceSq(player) > maxDist * maxDist) return;
        }

        stopIfAny(t.getEntityId());

        EntityLinkedSound snd = new EntityLinkedSound(t, evt, vol, pitch);
        snd.isChoir = isChoir;
        LIVE.put(t.getEntityId(), snd);
        mc.getSoundHandler().playSound(snd);
    }


    public static void stopIfAny(int entityId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSoundHandler() == null) return;

        EntityLinkedSound old = LIVE.remove(entityId);
        if (old != null) {
            mc.getSoundHandler().stopSound(old);
            old.stopNow();
        }
    }

    public static void applyMute(int entityId, int durationMs) {
        EntityLinkedSound snd = LIVE.get(entityId);
        if (snd != null) {
            snd.muteUntilMs = Math.max(snd.muteUntilMs, System.currentTimeMillis() + durationMs);
        }
    }

    static void onSoundFinished(EntityLinkedSound snd) {
        if (snd == null) return;

        EntityLinkedSound cur = LIVE.get(snd.getEntityId());
        if (cur == snd) {
            LIVE.remove(snd.getEntityId());
        }

        if (snd.isChoir && !snd.stoppedExternally) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgChoirSoundDone(snd.getEntityId()));
        }
    }
}
