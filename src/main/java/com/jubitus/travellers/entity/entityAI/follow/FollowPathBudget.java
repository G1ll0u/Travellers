package com.jubitus.travellers.entity.entityAI.follow;

import net.minecraft.world.World;

/**
 * Hard cap for expensive pathfinding calls per tick (per world).
 * This prevents 50 followers from all pathing in the same tick.
 */
public final class FollowPathBudget {

    // Tune: 4–12 is a good range. Start with 6.
    public static int MAX_PATHS_PER_TICK = 6;

    private static long lastTick = Long.MIN_VALUE;
    private static int used = 0;

    private FollowPathBudget() {
    }

    public static boolean tryAcquire(World w) {
        long t = w.getTotalWorldTime();
        if (t != lastTick) {
            lastTick = t;
            used = 0;
        }
        if (used >= MAX_PATHS_PER_TICK) return false;
        used++;
        return true;
    }
}
