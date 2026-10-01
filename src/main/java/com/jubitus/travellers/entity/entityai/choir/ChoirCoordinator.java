package com.jubitus.travellers.entity.entityai.choir;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.choir.ChoirRegistry;
import com.jubitus.travellers.entity.culture.choir.ChoirSongDef;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.*;

// Ticked every 20 game ticks (1 second) by ChoirWorldTicker.

public final class ChoirCoordinator {
    private static final Map<Integer, ChoirCoordinator> BY_DIM = new HashMap<>();

    private final List<ChoirSession> sessions = new ArrayList<>();
    private final Map<UUID, ChoirSession> sessionByPlayer = new HashMap<>();
    // round-robin index per "lang:personality" key for auto-choir
    private final Map<String, Integer> songIndex = new HashMap<>();
    // round-robin index per player UUID (stored as string) for worker choir
    private final Map<String, Integer> playerSongIndex = new HashMap<>();

    private long nextAutoAttemptTick = 0;

    // Ticks after voice becomes free before a singer tries to rejoin (3 seconds)
    private static final int REJOIN_WAIT_TICKS = 60;
    // Minimum remaining session time required before re-singing (3 seconds)
    private static final int REJOIN_MIN_REMAINING = 60;

    private ChoirCoordinator() {}

    public static ChoirCoordinator get(World world) {
        return BY_DIM.computeIfAbsent(world.provider.getDimension(), id -> new ChoirCoordinator());
    }

    public static void remove(int dim) {
        BY_DIM.remove(dim);
    }

    public void tick(World world) {
        if (world.isRemote) return;
        long now = world.getTotalWorldTime();

        tickActiveSessions(world, now);

        if (!TravellersConfig.CHOIR.choirEnabled || !ChoirRegistry.hasAnySongs()) return;
        // Auto-choir is still throttled at the original interval
        if (now >= nextAutoAttemptTick) {
            nextAutoAttemptTick = now + TravellersConfig.CHOIR.choirAutoCooldownTicks;
            boolean autoActive = sessions.stream().anyMatch(s -> s.ownerPlayerId == null);
            if (!autoActive) tryStartAutoChoir(world, now);
        }
    }

    private void tickActiveSessions(World world, long now) {
        Iterator<ChoirSession> it = sessions.iterator();
        while (it.hasNext()) {
            ChoirSession s = it.next();
            if (now >= s.endTick) {
                stopAndRemove(world, s, it);
                continue;
            }
            if (isTooFarApart(s)) {
                s.incrementSpreadFail();
                if (s.getSpreadFailCount() >= TravellersConfig.CHOIR.choirSpreadGraceChecks) {
                    stopAndRemove(world, s, it);
                    continue;
                }
            } else {
                s.resetSpreadFail();
            }
            checkAndRejoinInterruptedSingers(s, now);
        }
    }

    private void checkAndRejoinInterruptedSingers(ChoirSession s, long now) {
        for (EntityTraveller singer : s.singers) {
            if (!singer.isEntityAlive() || !singer.isChoirSinging()) continue;

            // Stop and suppress singers who are fighting or in post-combat silence
            if (singer.isInCombat() || singer.isInPostCombatSilence()) {
                singer.stopChoir();
                // Push the rejoin window past the silence so they don't re-enter early
                s.rejoinAt.put(singer.getEntityId(), now + REJOIN_WAIT_TICKS);
                continue;
            }

            if (singer.isVoiceLockedNow()) {
                // Still singing — clear any pending rejoin schedule
                s.rejoinAt.remove(singer.getEntityId());
                continue;
            }

            // Voice is free while session is still active → interrupted
            Long rejoin = s.rejoinAt.get(singer.getEntityId());
            if (rejoin == null) {
                // First time we notice the voice is free: schedule rejoin
                s.rejoinAt.put(singer.getEntityId(), now + REJOIN_WAIT_TICKS);
                continue;
            }
            if (now < rejoin) continue;

            long remaining = s.endTick - now;
            if (remaining < REJOIN_MIN_REMAINING) {
                s.rejoinAt.remove(singer.getEntityId());
                continue;
            }

            int stemIdx = s.stemByEntityId.getOrDefault(singer.getEntityId(), 0);
            singer.playVoiceMoving(s.song.stem(stemIdx), s.vol, s.pitch, (int) remaining,
                    true, EntityTraveller.VoiceKind.CHOIR);
            s.rejoinAt.remove(singer.getEntityId()); // clear — now active again
        }
    }

    private void stopAndRemove(World world, ChoirSession s, Iterator<ChoirSession> it) {
        stopSession(world, s);
        it.remove();
        if (s.ownerPlayerId != null) sessionByPlayer.remove(s.ownerPlayerId);
    }

    private boolean isTooFarApart(ChoirSession s) {
        if (s.singers.isEmpty()) return true;

        // On utilise le premier chanteur comme ancêtre mobile (leader) au lieu de startCenter
        EntityTraveller leader = s.singers.get(0);
        if (!leader.isEntityAlive()) return true;

        double maxDistSq = TravellersConfig.CHOIR.choirBreakRadius * TravellersConfig.CHOIR.choirBreakRadius;
        for (EntityTraveller singer : s.singers) {
            if (!singer.isEntityAlive()) return true;

            // Calcul de la distance par rapport au leader du groupe en mouvement
            double dx = singer.posX - leader.posX;
            double dz = singer.posZ - leader.posZ;
            if (dx * dx + dz * dz > maxDistSq) return true;
        }
        return false;
    }

    private void stopSession(World world, ChoirSession s) {
        for (EntityTraveller singer : s.singers) {
            singer.stopChoir();
            if (singer instanceof EntityTravellerWorker) {
                ((EntityTravellerWorker) singer).setChoirFreeze(false, 0, 0);
            }
        }
    }

    // --- Auto choir ---

    private void tryStartAutoChoir(World world, long now) {
        if (world.rand.nextFloat() >= TravellersConfig.CHOIR.choirAutoChance) return;

        List<EntityTraveller> candidates = world.getEntities(EntityTraveller.class, t ->
                t != null && t.isEntityAlive() && !t.isChoirSinging() && !t.isInCombat()
                        && !(t instanceof EntityTravellerWorker && ((EntityTravellerWorker) t).isHiredFollower())
        );

        if (candidates.size() < 2) return;

        Collections.shuffle(candidates, world.rand);

        for (EntityTraveller seed : candidates) {
            String lang = CultureRegistry.langForCulture(seed.getCultureId()).toLowerCase(Locale.ROOT);
            String personality = seed.getVoicePersonalityId().toLowerCase(Locale.ROOT);

            List<ChoirSongDef> songs = ChoirRegistry.getSongs(lang, personality);
            if (songs.isEmpty()) continue;

            List<EntityTraveller> group = collectNearby(seed, candidates, TravellersConfig.CHOIR.choirGroupRadius);
            if (group.size() < 2) continue;

            ChoirSongDef song = nextSongRoundRobin(songs, songIndex, lang + ":" + personality);
            int singerCount = Math.min(group.size(), song.stemCount());
            if (singerCount < 2) continue;

            startSession(world, song, group.subList(0, singerCount), now, null);
            return;
        }
    }

    private List<EntityTraveller> collectNearby(EntityTraveller seed, List<EntityTraveller> pool, double radius) {
        List<EntityTraveller> result = new ArrayList<>();
        result.add(seed);
        double rSq = radius * radius;
        for (EntityTraveller c : pool) {
            if (c == seed) continue;
            double dx = c.posX - seed.posX, dz = c.posZ - seed.posZ;
            if (dx * dx + dz * dz <= rSq) result.add(c);
        }
        return result;
    }

    // --- Worker choir toggle ---

    public void handleWorkerChoirToggle(World world, EntityTravellerWorker targetWorker, EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        long now = world.getTotalWorldTime();

        ChoirSession existing = sessionByPlayer.get(playerId);
        if (existing != null) {
            stopSession(world, existing);
            sessions.remove(existing);
            sessionByPlayer.remove(playerId);
            return;
        }

        if (!ChoirRegistry.hasAnySongs()) return;

        String lang = CultureRegistry.langForCulture(targetWorker.getCultureId()).toLowerCase(Locale.ROOT);
        String personality = targetWorker.getVoicePersonalityId().toLowerCase(Locale.ROOT);
        List<ChoirSongDef> songs = ChoirRegistry.getSongs(lang, personality);
        if (songs.isEmpty()) return;

        double r = TravellersConfig.CHOIR.choirGroupRadius;
        AxisAlignedBB box = targetWorker.getEntityBoundingBox().grow(r, 8, r);
        List<EntityTravellerWorker> nearby = world.getEntitiesWithinAABB(
                EntityTravellerWorker.class, box,
                w -> w != null && w.isEntityAlive() && !w.isChoirSinging() && !w.isOnDuty()
                     && w.isHiredFollower() && playerId.equals(w.getFollowOwnerId())
        );

        List<EntityTraveller> singers = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        if (!targetWorker.isChoirSinging() && seen.add(targetWorker.getEntityId())) {
            singers.add(targetWorker);
        }
        for (EntityTravellerWorker w : nearby) {
            if (seen.add(w.getEntityId())) singers.add(w);
        }

        ChoirSongDef song = nextSongRoundRobin(songs, playerSongIndex, playerId.toString());
        int singerCount = Math.min(singers.size(), song.stemCount());
        if (singerCount < 2) return;

        startSession(world, song, singers.subList(0, singerCount), now, playerId);
    }

    // --- Common session start ---

    private void startSession(World world, ChoirSongDef song, List<EntityTraveller> singers,
                              long now, @Nullable UUID ownerPlayerId) {
        float vol = TravellersConfig.SOUND.travellerChoirVolume;
        float pitch = 1.0f;
        int durTicks = song.durationTicks > 0 ? song.durationTicks : TravellersConfig.CHOIR.choirSessionDurationTicks;
        long endTick = now + durTicks;

        ChoirSession session = new ChoirSession(song, new ArrayList<>(singers), endTick,
                ownerPlayerId, vol, pitch);

        if (ownerPlayerId != null) {
            setupWorkerFormation(world, singers, ownerPlayerId);
        }

        for (int i = 0; i < singers.size(); i++) {
            EntityTraveller singer = singers.get(i);
            singer.setChoirSinging(true);
            singer.playVoiceMoving(song.stem(i), vol, pitch, durTicks, true, EntityTraveller.VoiceKind.CHOIR);
            if (singer instanceof EntityTravellerWorker) {
                ((EntityTravellerWorker) singer).setWorkerChoirActive(true);
            }
        }

        sessions.add(session);
        if (ownerPlayerId != null) sessionByPlayer.put(ownerPlayerId, session);
    }

    private void setupWorkerFormation(World world, List<EntityTraveller> singers, UUID ownerPlayerId) {
        EntityPlayer player = world.getPlayerEntityByUUID(ownerPlayerId);
        if (player == null || singers.isEmpty()) return;

        // Freeze each worker in place and have them face the player.
        // No teleporting — workers stay where they are; the AI task handles look.
        for (EntityTraveller singer : singers) {
            if (singer instanceof EntityTravellerWorker) {
                ((EntityTravellerWorker) singer).setChoirFreeze(true, player.posX, player.posZ);
            }
        }
    }

    // --- Helpers ---

    private static ChoirSongDef nextSongRoundRobin(List<ChoirSongDef> songs,
                                                    Map<String, Integer> indexMap, String key) {
        int idx = indexMap.getOrDefault(key, 0) % songs.size();
        indexMap.put(key, idx + 1);
        return songs.get(idx);
    }

    public boolean isWorkerChoirActive(UUID playerId) {
        return sessionByPlayer.containsKey(playerId);
    }
}
