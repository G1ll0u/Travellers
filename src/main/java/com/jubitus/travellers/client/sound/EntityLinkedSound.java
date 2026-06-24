package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ITickableSound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Entity-following sound that does NOT extend MovingSound.
 * SoundPhysics forces all MovingSound instances to SoundCategory.RECORDS (bypassing reverb).
 * By extending PositionedSound + ITickableSound directly we preserve the real sound category
 * and get full environmental audio processing from SoundPhysics.
 *
 * Attenuation strategy:
 *  - SoundPhysics present → LINEAR + baseVolume; SP handles distance rolloff and reverb.
 *  - SoundPhysics absent  → NONE (disable engine attenuation) + manual inverse-distance
 *    curve computed each frame from config params (voiceReferenceDistance / voiceMaxDistance /
 *    voiceRolloffPower).  This avoids the harsh LINEAR cutoff and lets the user tune audible range.
 */
@SideOnly(Side.CLIENT)
public final class EntityLinkedSound extends PositionedSound implements ITickableSound {

    private static final boolean HAS_SOUND_PHYSICS;
    static {
        boolean found = false;
        try { Class.forName("com.sonicether.soundphysics.SoundPhysics"); found = true; }
        catch (ClassNotFoundException ignored) {}
        HAS_SOUND_PHYSICS = found;
    }

    private final EntityTraveller entity;
    private final int entityId;
    private final float baseVolume;

    boolean isChoir = false;
    boolean isAmbient = false;
    boolean stoppedExternally = false;
    long muteUntilMs = 0;

    private boolean donePlaying = false;

    private long fadeStartMs = -1;
    private static final long FADE_DURATION_MS = 150;

    public EntityLinkedSound(EntityTraveller e, SoundEvent evt, float vol, float pitch) {
        super(evt, e.getSoundCategory());
        this.entity = e;
        this.entityId = e.getEntityId();
        this.baseVolume = vol;
        this.pitch = pitch;
        this.repeat = false;
        this.attenuationType = HAS_SOUND_PHYSICS ? AttenuationType.LINEAR : AttenuationType.NONE;

        this.xPosF = (float) e.posX;
        this.yPosF = (float) (e.posY + e.height * 0.6f);
        this.zPosF = (float) e.posZ;

        // With SP: start near-silent so SP's globalVolumeMultiplier (applied only in playSound,
        // not in updateAllSounds) doesn't create a loud spike. Must be > 0 so SoundManager
        // doesn't bail out early with its "if (volume <= 0) return" guard. update() raises it
        // to baseVolume on the first tick, before the first setVolume from updateAllSounds.
        this.volume = HAS_SOUND_PHYSICS ? 0.001f : vol * computeDistanceGain(e);
    }

    private static float computeDistanceGain(EntityTraveller e) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) return 1.0f;
        float dist = (float) e.getDistance(player);
        float refDist = (float) TravellersConfig.SOUND.voiceReferenceDistance;
        float maxDist = (float) TravellersConfig.SOUND.voiceMaxDistance;
        float rolloff = (float) TravellersConfig.SOUND.voiceRolloffPower;
        if (dist <= refDist) return 1.0f;
        if (dist >= maxDist) return 0.0f;
        return (float) Math.pow((double) refDist / dist, rolloff);
    }

    public int getEntityId() {
        return entityId;
    }

    public double getDistanceSqToPlayer() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null || entity == null) return 0.0;
        return entity.getDistanceSq(player);
    }

    public void stopNow() {
        if (!donePlaying && fadeStartMs < 0) {
            stoppedExternally = true;
            fadeStartMs = System.currentTimeMillis();
        }
    }

    @Override
    public void update() {
        if (donePlaying) return;

        // Hard stop: entity gone (no fade — source position is meaningless)
        if (entity == null || !entity.isEntityAlive()) {
            donePlaying = true;
            TravellerVoiceClientBus.onSoundFinished(this);
            return;
        }

        if (Minecraft.getMinecraft().isGamePaused()) return;

        xPosF = (float) entity.posX;
        yPosF = (float) (entity.posY + entity.height * 0.6f);
        zPosF = (float) entity.posZ;

        // Fade-out triggered by stopNow()
        if (fadeStartMs >= 0) {
            long elapsed = System.currentTimeMillis() - fadeStartMs;
            if (elapsed >= FADE_DURATION_MS) {
                donePlaying = true;
                TravellerVoiceClientBus.onSoundFinished(this);
                return;
            }
            volume = currentBaseVolume() * (1.0f - (float) elapsed / FADE_DURATION_MS);
            return;
        }

        if (muteUntilMs > 0 && System.currentTimeMillis() < muteUntilMs) {
            volume = 0.0f;
            return;
        }

        if (HAS_SOUND_PHYSICS) {
            volume = currentBaseVolume();
        } else {
            volume = currentBaseVolume() * computeDistanceGain(entity);
        }

        if (donePlaying) {
            TravellerVoiceClientBus.onSoundFinished(this);
        }
    }

    private float currentBaseVolume() {
        return isChoir ? baseVolume * TravellersModConfig.choirSoundVolumeMultiplier : baseVolume;
    }

    @Override
    public boolean isDonePlaying() {
        return donePlaying;
    }
}
