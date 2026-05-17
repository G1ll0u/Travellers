package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MovingTravellerSound extends net.minecraft.client.audio.MovingSound
        implements net.minecraft.client.audio.ITickableSound {

    private final EntityTraveller traveller;
    private final int travellerEntityId;
    boolean stoppedExternally = false;
    boolean isChoir = false;
    private float originalVolume;
    long muteUntilMs = 0; // system-time millis; 0 = not muted

    public MovingTravellerSound(EntityTraveller e, SoundEvent evt, float vol, float pitch) {
        super(evt, e.getSoundCategory());
        this.traveller = e;
        this.travellerEntityId = e.getEntityId();
        this.volume = vol;
        this.originalVolume = vol;
        this.pitch = pitch;
        this.repeat = false;
        this.attenuationType = AttenuationType.LINEAR;

        this.xPosF = (float) e.posX;
        this.yPosF = (float) (e.posY + e.height * 0.6);
        this.zPosF = (float) e.posZ;
    }

    public int getTravellerEntityId() {
        return travellerEntityId;
    }

    public void stopNow() {
        this.stoppedExternally = true;
        this.donePlaying = true;
    }

    @Override
    public void update() {
        if (stoppedExternally || traveller == null || !traveller.isEntityAlive()) {
            this.donePlaying = true;
            TravellerVoiceClientBus.onSoundFinished(this);
            return;
        }

        if (net.minecraft.client.Minecraft.getMinecraft().isGamePaused()) return;

        this.xPosF = (float) traveller.posX;
        this.yPosF = (float) (traveller.posY + traveller.height * 0.6);
        this.zPosF = (float) traveller.posZ;

        // Apply mute: volume=0 during interruption, restored at correct song position.
        this.volume = (muteUntilMs > 0 && System.currentTimeMillis() < muteUntilMs)
                ? 0.0f : originalVolume;

        if (this.donePlaying) {
            TravellerVoiceClientBus.onSoundFinished(this);
        }
    }
}