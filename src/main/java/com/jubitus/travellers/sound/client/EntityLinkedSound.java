package com.jubitus.travellers.sound.client;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ITickableSound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
        // Checking the classloader's resource list (instead of Class.forName) matches what
        // actually works to detect SoundPhysics under non-stock loaders (e.g. Cleanroom) where
        // a plain Class.forName can spuriously fail depending on classloading order/timing.
        boolean found;
        try {
            found = Launch.classLoader.getResource("com/sonicether/soundphysics/SoundPhysics.class") != null;
        } catch (Throwable t) {
            found = false;
        }
        HAS_SOUND_PHYSICS = found;
        LogManager.getLogger("TravellerSound").info("[Sound] SoundPhysics detected: {}", HAS_SOUND_PHYSICS);
    }

    private final EntityTraveller entity;
    private final int entityId;
    private final float baseVolume;

    boolean isChoir = false;
    boolean isAmbient = false;
    boolean stoppedExternally = false;
    long muteUntilMs = 0;
    private long muteTransitionStartMs = -1;
    private boolean wasMutedLastTick = false;

    private boolean donePlaying = false;

    private long fadeStartMs = -1;
    private float fadeBaseVolume = 0f;

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

        // Must be the real, final value (not a placeholder near-zero seed): vanilla's
        // SoundManager.playSound() reads getVolume() at construction time TWICE — once to
        // compute the initial AL_GAIN (fine either way, that gets corrected every tick anyway),
        // and once to decide the sound's audible RANGE ("float f = 16.0F; if (f3 > 1.0F) f *= f3;",
        // passed once into the engine as the attenuation/rolloff reference and never revisited).
        // A near-zero seed permanently caps every SoundPhysics-attenuated (LINEAR) voice sound
        // to vanilla's ~16-block default range, regardless of soundPhysicsVolumeBoost or any
        // configured volume — since those only take effect later, in update(), after the range
        // was already locked in. This was the actual cause of "boosting volume changes nothing,
        // still too quiet at range" with SoundPhysics installed.
        this.volume = currentBaseVolume() * (HAS_SOUND_PHYSICS ? 1.0f : computeDistanceGain(e));
    }

    private static float computeDistanceGain(EntityTraveller e) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) return 1.0f;
        return computeDistanceGain((float) e.getDistance(player));
    }

    // Shared with MsgPlayTravellerVoice's "entity already gone" fallback (e.g. a death sound
    // whose speaker was already removed client-side by the time the sound message arrived) —
    // that path plays a plain vanilla one-shot with no per-tick correction, so it needs the
    // same manual falloff applied once, up front, instead of no distance handling at all.
    public static float computeDistanceGain(float dist) {
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
            // Fade from wherever this sound's volume actually is right now (already correctly
            // distance-attenuated by the previous update() tick), not from a freshly recomputed
            // "ideal" full volume — see the note on this same field's use in update().
            fadeBaseVolume = volume;
        }
    }

    @Override
    public void update() {
        if (donePlaying) return;

        if (Minecraft.getMinecraft().isGamePaused()) return;

        // entity.isDead (true only once the entity is actually removed, e.g. after the death
        // animation finishes) rather than !isEntityAlive() (true the instant health hits 0,
        // ~1 second before removal — using that here made every death sound treat its own
        // still-valid, still-correctly-positioned entity as "gone" from its very first tick,
        // jumping straight to a fade target that skips the distance curve entirely: a pop).
        boolean entityGone = entity == null || entity.isDead;

        if (!entityGone) {
            xPosF = (float) entity.posX;
            yPosF = (float) (entity.posY + entity.height * 0.6f);
            zPosF = (float) entity.posZ;
        } else if (fadeStartMs < 0) {
            // Entity died/unloaded mid-sound: fade out from its last known position
            // instead of cutting to silence outright.
            fadeStartMs = System.currentTimeMillis();
            fadeBaseVolume = volume;
        }

        // Fade-out triggered by stopNow() or the entity disappearing above.
        if (fadeStartMs >= 0) {
            long elapsed = System.currentTimeMillis() - fadeStartMs;
            long fadeDurationMs = TravellersConfig.SOUND.voiceFadeOutMs;
            if (elapsed >= fadeDurationMs) {
                donePlaying = true;
                TravellerVoiceClientBus.onSoundFinished(this);
                return;
            }
            // Fade from fadeBaseVolume (this sound's actual, already-attenuated volume at the
            // moment the fade started), NOT a freshly recomputed currentBaseVolume() — that used
            // to be the raw, distance-ignorant base volume, so interrupting/evicting/ending a
            // sound that was correctly quiet because it was far away made it jump to full volume
            // for an instant before ramping down: an audible spike, worst for the exact sounds
            // eviction targets (the farthest, quietest ones).
            volume = fadeBaseVolume * (1.0f - (float) elapsed / fadeDurationMs);
            return;
        }

        float target = HAS_SOUND_PHYSICS ? currentBaseVolume() : currentBaseVolume() * computeDistanceGain(entity);

        // Choir mute (getting hit mid-song silences the sound for a while without stopping the
        // underlying stream, see EntityTraveller.attackEntityFrom) used to snap straight from 0
        // to full volume and back — a hard pop in both directions. Ramp across the mute edges
        // the same way every other stop/interrupt in this class does.
        boolean isMuted = muteUntilMs > 0 && System.currentTimeMillis() < muteUntilMs;
        if (isMuted != wasMutedLastTick) {
            muteTransitionStartMs = System.currentTimeMillis();
        }
        wasMutedLastTick = isMuted;

        if (muteTransitionStartMs >= 0) {
            long elapsed = System.currentTimeMillis() - muteTransitionStartMs;
            long rampMs = TravellersConfig.SOUND.voiceFadeOutMs;
            if (elapsed >= rampMs) {
                muteTransitionStartMs = -1;
                volume = isMuted ? 0.0f : target;
            } else {
                float t = (float) elapsed / rampMs;
                volume = isMuted ? target * (1.0f - t) : target * t;
            }
        } else {
            volume = isMuted ? 0.0f : target;
        }
    }

    private float currentBaseVolume() {
        return HAS_SOUND_PHYSICS ? baseVolume * TravellersConfig.SOUND.soundPhysicsVolumeBoost : baseVolume;
    }

    @Override
    public boolean isDonePlaying() {
        return donePlaying;
    }
}
