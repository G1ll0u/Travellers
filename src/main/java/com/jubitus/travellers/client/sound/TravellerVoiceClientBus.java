package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.sounds.MsgChoirSoundDone;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@SideOnly(Side.CLIENT)
public final class TravellerVoiceClientBus {
    private static final Logger LOGGER = LogManager.getLogger("TravellerSound");
    private static final java.util.Map<Integer, EntityLinkedSound> LIVE = new java.util.HashMap<>();

    // ponytail: ms-bucket throttle; prevents burst of concurrent JAR reads that corrupt Jorbis streams
    private static int startsThisBucket = 0;
    private static long bucketMs = 0;

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

        if (TravellersConfig.SOUND.soundDebugLog) {
            EntityLinkedSound existing = LIVE.get(t.getEntityId());
            String evtKey = evt.getRegistryName() != null ? evt.getRegistryName().toString() : "?";
            if (existing != null) {
                LOGGER.info("[SoundBus] REPLACE entity={} old={} new={} choir={}", t.getEntityId(), existing.getSoundLocation(), evtKey, isChoir);
            } else {
                LOGGER.info("[SoundBus] PLAY entity={} event={} choir={}", t.getEntityId(), evtKey, isChoir);
            }
        }

        stopIfAny(t.getEntityId());

        // Enforce concurrent sound cap to avoid exhausting the OpenAL source pool.
        // Choir and non-ambient sounds evict the farthest ambient first.
        // If the cap is still exceeded after eviction, skip low-priority (ambient) sounds.
        int cap = TravellersConfig.SOUND.maxConcurrentVoiceSounds;
        if (LIVE.size() >= cap) {
            String evtKey = evt.getRegistryName() != null ? evt.getRegistryName().getPath() : "";
            boolean newIsAmbient = evtKey.contains("ambient");
            if (!evictFarthestAmbient()) {
                // No ambient to evict: skip if new sound is also ambient
                if (newIsAmbient) {
                    if (TravellersConfig.SOUND.soundDebugLog) {
                        LOGGER.info("[SoundBus] SKIP (cap={} no evictable ambient) entity={} event={}", cap, t.getEntityId(), evtKey);
                    }
                    return;
                }
                // Non-ambient (choir, talk, hurt): evict the farthest sound of any kind
                evictFarthestAny();
            }
        }

        // Throttle new JAR stream opens to avoid concurrent reads corrupting Jorbis.
        // Choir sounds are exempt — they must start on time.
        if (!isChoir) {
            long now = System.currentTimeMillis();
            if (now - bucketMs > 50) { // ~1 tick window
                startsThisBucket = 0;
                bucketMs = now;
            }
            int maxPerTick = TravellersConfig.SOUND.maxNewSoundsPerTick;
            if (startsThisBucket >= maxPerTick) {
                if (TravellersConfig.SOUND.soundDebugLog) {
                    LOGGER.info("[SoundBus] THROTTLE entity={} event={}", t.getEntityId(),
                            evt.getRegistryName() != null ? evt.getRegistryName() : "?");
                }
                return;
            }
            startsThisBucket++;
        }

        boolean isAmbient = evt.getRegistryName() != null && evt.getRegistryName().getPath().contains("ambient");
        EntityLinkedSound snd = new EntityLinkedSound(t, evt, vol, pitch);
        snd.isChoir = isChoir;
        snd.isAmbient = isAmbient;
        LIVE.put(t.getEntityId(), snd);
        mc.getSoundHandler().playSound(snd);
    }


    // Returns true if an ambient sound was found and evicted.
    private static boolean evictFarthestAmbient() {
        EntityLinkedSound farthest = null;
        double farthestDist = -1;
        for (EntityLinkedSound s : LIVE.values()) {
            if (!s.isAmbient) continue;
            double d = s.getDistanceSqToPlayer();
            if (d > farthestDist) { farthestDist = d; farthest = s; }
        }
        if (farthest == null) return false;
        if (TravellersConfig.SOUND.soundDebugLog) {
            LOGGER.info("[SoundBus] EVICT ambient entity={} dist={}", farthest.getEntityId(), (int) Math.sqrt(farthestDist));
        }
        LIVE.remove(farthest.getEntityId());
        // Fade out instead of an immediate stopSound(): the engine keeps ticking this
        // instance (it's no longer tracked in LIVE) until its own fade completes and
        // isDonePlaying() flips, which is when vanilla's SoundManager actually stops it.
        farthest.stopNow();
        return true;
    }

    private static void evictFarthestAny() {
        EntityLinkedSound farthest = null;
        double farthestDist = -1;
        for (EntityLinkedSound s : LIVE.values()) {
            if (s.isChoir) continue; // never evict choir
            double d = s.getDistanceSqToPlayer();
            if (d > farthestDist) { farthestDist = d; farthest = s; }
        }
        if (farthest == null) return;
        if (TravellersConfig.SOUND.soundDebugLog) {
            LOGGER.info("[SoundBus] EVICT any entity={} dist={}", farthest.getEntityId(), (int) Math.sqrt(farthestDist));
        }
        LIVE.remove(farthest.getEntityId());
        farthest.stopNow();
    }

    public static void stopIfAny(int entityId) {
        EntityLinkedSound old = LIVE.remove(entityId);
        if (old != null) {
            if (TravellersConfig.SOUND.soundDebugLog) {
                LOGGER.info("[SoundBus] STOP entity={} sound={}", entityId, old.getSoundLocation());
            }
            // Fade out rather than stopSound()-ing it immediately: an instant stop here
            // is what caused sounds to cut without any apparent reason (every replacement,
            // e.g. a new voice line pre-empting an old one, or an interruption on hit,
            // went through this path).
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
