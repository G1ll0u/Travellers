package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ITickableSound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Entity-following sound that does NOT extend MovingSound.
 * SoundPhysics forces all MovingSound instances to SoundCategory.RECORDS (bypassing reverb).
 * By extending PositionedSound + ITickableSound directly we preserve the real sound category
 * and get full environmental audio processing from SoundPhysics.
 */
@SideOnly(Side.CLIENT)
public final class EntityLinkedSound extends PositionedSound implements ITickableSound {

    private final EntityTraveller entity;
    private final int entityId;
    private final float baseVolume;

    boolean isChoir = false;
    boolean stoppedExternally = false;
    long muteUntilMs = 0;

    private boolean donePlaying = false;

    public EntityLinkedSound(EntityTraveller e, SoundEvent evt, float vol, float pitch) {
        super(evt, e.getSoundCategory());
        this.entity = e;
        this.entityId = e.getEntityId();
        this.baseVolume = vol;
        this.volume = vol;
        this.pitch = pitch;
        this.repeat = false;
        this.attenuationType = AttenuationType.LINEAR;

        this.xPosF = (float) e.posX;
        this.yPosF = (float) (e.posY + e.height * 0.6f);
        this.zPosF = (float) e.posZ;
    }

    public int getEntityId() {
        return entityId;
    }

    public void stopNow() {
        stoppedExternally = true;
        donePlaying = true;
    }

    @Override
    public void update() {
        if (stoppedExternally || entity == null || !entity.isEntityAlive()) {
            donePlaying = true;
            TravellerVoiceClientBus.onSoundFinished(this);
            return;
        }

        if (Minecraft.getMinecraft().isGamePaused()) return;

        xPosF = (float) entity.posX;
        yPosF = (float) (entity.posY + entity.height * 0.6f);
        zPosF = (float) entity.posZ;

        if (muteUntilMs > 0 && System.currentTimeMillis() < muteUntilMs) {
            volume = 0.0f;
        } else {
            volume = isChoir
                    ? baseVolume * TravellersModConfig.choirSoundVolumeMultiplier
                    : baseVolume;
        }

        if (donePlaying) {
            TravellerVoiceClientBus.onSoundFinished(this);
        }
    }

    @Override
    public boolean isDonePlaying() {
        return donePlaying;
    }
}
