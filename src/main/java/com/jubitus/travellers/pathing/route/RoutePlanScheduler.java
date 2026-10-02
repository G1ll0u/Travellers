package com.jubitus.travellers.pathing.route;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.world.World;

/**
 * World-wide admission budget for new route-planning jobs, a sibling of
 * {@code travel/TravelPathScheduler}: same shape (static
 * {@code tryAcquire}, per-tick counter that self-resets on tick change). A unit denied a slot uses
 * its straight-line fallback and retries on its next replan.
 *
 * <p>This gates <em>planning jobs</em> only. The terrain chunk scan has its own separate per-tick
 * integer budget ({@code CHUNK_READER.chunksPerTick}) - the two must not be conflated.
 */
public final class RoutePlanScheduler {

    // Spread non-urgent requests across this many ticks by entity id. 1 = off: virtual route jobs
    // carry a fresh random key per bridge (not a stable entity id), so a stagger here is pure
    // latency - VirtualTravellerManager's own 40-tick bridge-retry already spreads the load.
    public static int STAGGER_TICKS = 1;

    private static long tick = Long.MIN_VALUE;
    private static int used = 0;

    private RoutePlanScheduler() {
    }

    /**
     * @param priority 0 = normal, 1 = medium, 2 = urgent (skips the stagger). All virtual callers
     *                 pass 0/1 today; 2 is reserved for a future loaded caller.
     */
    public static boolean tryAcquire(World world, int entityId, int priority) {
        return tryAcquire(world.getTotalWorldTime(), entityId, priority);
    }

    static boolean tryAcquire(long now, int entityId, int priority) {
        if (now != tick) {
            tick = now;
            used = 0;
        }
        int max = Math.max(1, TravellersConfig.ROUTING.routeJobsPerTick);
        if (priority < 2 && STAGGER_TICKS > 1) {
            if (Math.floorMod(entityId, STAGGER_TICKS) != (int) Math.floorMod(now, STAGGER_TICKS)) {
                return false;
            }
        }
        if (used >= max) return false;
        used++;
        return true;
    }

    /** Test hook. */
    public static void resetForTest() {
        tick = Long.MIN_VALUE;
        used = 0;
    }
}
