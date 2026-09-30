package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.Tags;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The player-leader counterpart to {@code EntityTraveller.leadTrail} - a mounted escort's own
 * "convoy trail" fallback (see {@code EntityAIFollowLeader.tryFollowLeaderTrail},
 * {@code MountedFollowPacer}) needs a recent-ground-track to retrace regardless of what kind of
 * entity it is escorting, and a player is by far the single most common leader a hired-worker
 * escort actually has - unlike a worker/traveller leader, a player has no {@code EntityTraveller}
 * field to carry this on, so it lives here instead, keyed by player UUID.
 *
 * <p>Recorded by whichever follower happens to tick first each game tick ({@link #record} is
 * idempotent per (player, tick) so several escorts of the same player only pay for one write),
 * never by a dedicated per-player task - there is no cost when nobody is actually following a
 * player, since nothing calls {@link #record} in that case.
 */
public final class PlayerLeaderTrail {

    private static final int MAX_POINTS = 64;

    private static final Map<UUID, ArrayDeque<Vec3d>> TRAILS = new HashMap<>();
    private static final Map<UUID, Long> LAST_RECORDED_TICK = new HashMap<>();

    private PlayerLeaderTrail() {
    }

    /** Appends the player's current position to their trail, at most once per game tick. */
    public static void record(EntityPlayer player) {
        long now = player.world.getTotalWorldTime();
        UUID id = player.getUniqueID();
        Long lastTick = LAST_RECORDED_TICK.get(id);
        if (lastTick != null && lastTick == now) return;
        LAST_RECORDED_TICK.put(id, now);

        ArrayDeque<Vec3d> trail = TRAILS.computeIfAbsent(id, k -> new ArrayDeque<>());
        Vec3d head = trail.peekFirst();
        if (head != null) {
            double dx = head.x - player.posX, dz = head.z - player.posZ;
            double distSq = dx * dx + dz * dz;
            if (distSq < 0.0036D) return; // < 0.06 blocks moved: nothing new
            // A teleport-sized jump makes the trail actively misleading (a straight line through
            // whatever was between the old and new spot) rather than merely stale - same threshold
            // EntityTraveller.setPositionAndUpdate uses to drop its own lead trail on a big hop.
            if (distSq > 256.0D) trail.clear();
        }
        trail.addFirst(new Vec3d(player.posX, player.posY, player.posZ));
        while (trail.size() > MAX_POINTS) trail.removeLast();
    }

    /**
     * The point on this player's trail roughly {@code blocks} of walked distance behind them - same
     * semantics as {@code EntityTraveller.leadTrailPointBehind}. {@code null} when nothing has been
     * recorded yet (no escort has ticked behind this player this session, or they teleported).
     */
    @Nullable
    public static Vec3d pointBehind(UUID playerId, double blocks) {
        ArrayDeque<Vec3d> trail = TRAILS.get(playerId);
        if (trail == null || trail.isEmpty()) return null;
        double acc = 0.0D;
        Vec3d prev = null;
        Vec3d last = null;
        for (Vec3d p : trail) { // iterates newest -> oldest
            last = p;
            if (prev != null) {
                double dx = p.x - prev.x, dz = p.z - prev.z;
                acc += Math.sqrt(dx * dx + dz * dz);
                if (acc >= blocks) return p;
            }
            prev = p;
        }
        return last;
    }

    @Mod.EventBusSubscriber(modid = Tags.MOD_ID)
    public static final class Cleanup {
        private Cleanup() {
        }

        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            UUID id = event.player.getUniqueID();
            TRAILS.remove(id);
            LAST_RECORDED_TICK.remove(id);
        }
    }
}
