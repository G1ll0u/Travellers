package com.jubitus.travellers.entity.entityAI.follow;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fair rate limiter for expensive pathfinding.
 * - Global cap (safety)
 * - Per-owner token bucket (knobs per player)
 * - Deterministic staggering so all followers get turns
 */
public final class PathScheduler {


    private static final Map<UUID, OwnerBucket> buckets = new HashMap<>();
    // ===== KNOBS =====
    public static int GLOBAL_MAX_PATHS_PER_TICK = 12;
    // "maximum pathing per player"
    public static double OWNER_PATHS_PER_SECOND = 8.0;
    // allow short bursts
    public static double OWNER_MAX_BURST = 3.0;
    // distributes pathing across followers
    public static int STAGGER_TICKS = 12;
    private static long globalTick = Long.MIN_VALUE;
    private static int globalUsed = 0;

    private PathScheduler() {
    }

    // old signature kept for compatibility
    public static boolean tryAcquire(World world, @Nullable EntityLivingBase owner, int entityId, int priority) {
        return tryAcquire(world, owner, entityId, priority, 1); // gilles groupSize = 1
    }

    /**
     * priority:
     * 0 = normal
     * 1 = far / no path
     * 2 = stuck / urgent
     */
    public static boolean tryAcquire(World w, EntityLivingBase owner, int followerEntityId, int priority, int groupSize) {
        if (owner == null) return false;

        long now = w.getTotalWorldTime();

        // global cap per tick
        if (now != globalTick) {
            globalTick = now;
            globalUsed = 0;
        }
        if (globalUsed >= GLOBAL_MAX_PATHS_PER_TICK) return false;

        UUID oid = owner.getUniqueID();
        OwnerBucket b = buckets.get(oid);
        if (b == null) {
            b = new OwnerBucket();
            b.tick = now;
            b.tokens = OWNER_MAX_BURST; // start full
            buckets.put(oid, b);
        }

        // refill tokens
        long dt = now - b.tick;
        if (dt > 0) {
            double refillPerTick = OWNER_PATHS_PER_SECOND / 20.0;
            b.tokens = Math.min(OWNER_MAX_BURST, b.tokens + dt * refillPerTick);
            b.tick = now;
        }

// stagger everyone except truly urgent stuck (2)
        if (priority < 2 && STAGGER_TICKS > 1) {
            int slot = Math.floorMod(followerEntityId, STAGGER_TICKS);
            int cur = (int) Math.floorMod(now, STAGGER_TICKS);
            if (slot != cur) return false;
        }


        // token check
        double cost = 1.0;

// scale cost by nearby follower count (passed in)
        cost += Math.max(0, groupSize - 10) * 0.05;   // 80 followers => +3.5 cost => ~4.5 total
        cost = Math.min(cost, 6.0);                  // cap
        if (priority >= 2) {
            if (b.tokens < -2.0) return false; // small debt allowed for urgent
        } else {
            if (b.tokens < cost) return false;
        }

        b.tokens -= cost;
        globalUsed++;
        return true;
    }

    private static class OwnerBucket {
        long tick;
        double tokens;
    }

}
