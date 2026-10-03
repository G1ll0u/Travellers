package com.jubitus.travellers.pathing;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.function.IntSupplier;

/**
 * The one world-wide per-tick budget for the mod's expensive searches (A* paths, route jobs, terrain
 * and target scans). Each client has its own row; a denied caller simply retries on a later tick.
 * Priority: 0 = normal, 1 = stuck-ish, 2 = urgent (skips the stagger, may get a larger cap).
 * Policies on top of a row (the follow AI's per-owner tokens) stay with their caller.
 */
public final class PathBudget {

    private static final int TRAVEL_STAGGER_TICKS = 10;
    private static final int TRAVEL_MAX_PER_TICK = 128;
    private static final int TRAVEL_URGENT_MAX_PER_TICK = 256;
    private static final int COMBAT_MAX_PER_TICK = 24;

    public enum Client {
        /** Travel waypoint paths; urgent requests are counted apart. */
        TRAVEL(() -> TRAVEL_STAGGER_TICKS, () -> TRAVEL_MAX_PER_TICK, () -> TRAVEL_URGENT_MAX_PER_TICK, true),
        /** Follow paths, every follower of every owner. */
        FOLLOW(() -> 1, () -> TravellersConfig.WORKER_FORMATION.pathSchedulerGlobalMaxPathsPerTick,
                () -> TravellersConfig.WORKER_FORMATION.pathSchedulerGlobalMaxPathsPerTick, false),
        /** Mounted follow paths, on top of {@link #FOLLOW}. */
        FOLLOW_MOUNTED(() -> 1, () -> TravellersConfig.WORKER_FORMATION.pathSchedulerMountedGlobalMaxPathsPerTick,
                () -> TravellersConfig.WORKER_FORMATION.pathSchedulerMountedGlobalMaxPathsPerTick, false),
        /** A led horse finding its way around an obstacle. */
        MOUNT(() -> 1, () -> TravellersConfig.WORKER_FORMATION.pathSchedulerMountedGlobalMaxPathsPerTick,
                () -> TravellersConfig.WORKER_FORMATION.pathSchedulerMountedGlobalMaxPathsPerTick, false),
        /** Paths toward a combat target. */
        COMBAT(() -> 1, () -> COMBAT_MAX_PER_TICK, () -> COMBAT_MAX_PER_TICK + 2, false),
        /** Nearby-enemy searches. */
        COMBAT_SCAN(() -> 1, () -> TravellersConfig.COMBAT.combatTargetScanBudgetPerTick,
                () -> TravellersConfig.COMBAT.combatTargetScanBudgetPerTick + TravellersConfig.COMBAT.combatTargetScanBudgetPerTick / 4, false),
        /** Itinerary builds, coarse route jobs and virtual destination picks. */
        ITINERARY(() -> 1, () -> Math.max(1, TravellersConfig.ROUTING.routeJobsPerTick),
                () -> Math.max(1, TravellersConfig.ROUTING.routeJobsPerTick), false),
        /** Elytra take-off terrain scans, each unit on its own tick of the scan interval. */
        ELYTRA_SCAN(() -> Math.max(1, TravellersConfig.ELYTRA.scanIntervalTicks),
                () -> TravellersConfig.ELYTRA.maxScansPerTick, () -> TravellersConfig.ELYTRA.maxScansPerTick, false),
        /** Reachability checks of a stuck unit's hop landing. */
        STUCK_HOP(() -> 1, () -> TravellersConfig.TRAVEL.stuckHopChecksPerTick,
                () -> TravellersConfig.TRAVEL.stuckHopChecksPerTick, false);

        private final IntSupplier staggerTicks;
        private final IntSupplier cap;
        private final IntSupplier urgentCap;
        private final boolean urgentCountedApart;

        Client(IntSupplier staggerTicks, IntSupplier cap, IntSupplier urgentCap, boolean urgentCountedApart) {
            this.staggerTicks = staggerTicks;
            this.cap = cap;
            this.urgentCap = urgentCap;
            this.urgentCountedApart = urgentCountedApart;
        }
    }

    public enum Result {
        GRANTED,
        /** Not this key's tick of the stagger: the normal spreading, not a shortage. */
        NOT_ITS_TURN,
        FULL
    }

    private static final int CLIENTS = Client.values().length;
    private static final int[] used = new int[CLIENTS];
    private static final int[] urgentUsed = new int[CLIENTS];
    private static long tick = Long.MIN_VALUE;

    private PathBudget() {
    }

    public static boolean tryAcquire(World world, Client client, int key, int priority) {
        return acquire(world.getTotalWorldTime(), client, key, priority) == Result.GRANTED;
    }

    /** For unstaggered clients called without a world at hand. */
    public static boolean tryAcquire(long now, Client client) {
        return acquire(now, client, 0, 0) == Result.GRANTED;
    }

    public static Result acquire(World world, Client client, int key, int priority) {
        return acquire(world.getTotalWorldTime(), client, key, priority);
    }

    static Result acquire(long now, Client client, int key, int priority) {
        boolean urgent = priority >= 2;
        int stagger = client.staggerTicks.getAsInt();
        if (!urgent && stagger > 1 && Math.floorMod(key, stagger) != Math.floorMod(now, stagger)) {
            return Result.NOT_ITS_TURN;
        }
        if (isFull(now, client, priority)) return Result.FULL;
        take(now, client, priority);
        return Result.GRANTED;
    }

    /** Two-step use for a caller with its own policy between the check and the grant. */
    public static boolean isFull(World world, Client client, int priority) {
        return isFull(world.getTotalWorldTime(), client, priority);
    }

    public static void take(World world, Client client, int priority) {
        take(world.getTotalWorldTime(), client, priority);
    }

    private static boolean isFull(long now, Client client, int priority) {
        roll(now);
        int i = client.ordinal();
        boolean urgent = priority >= 2;
        int count = urgent && client.urgentCountedApart ? urgentUsed[i] : used[i];
        int cap = urgent ? client.urgentCap.getAsInt() : client.cap.getAsInt();
        return count >= cap;
    }

    private static void take(long now, Client client, int priority) {
        roll(now);
        int i = client.ordinal();
        if (priority >= 2 && client.urgentCountedApart) urgentUsed[i]++;
        else used[i]++;
    }

    private static void roll(long now) {
        if (now == tick) return;
        tick = now;
        Arrays.fill(used, 0);
        Arrays.fill(urgentUsed, 0);
    }

    /** Test hook. */
    static void resetForTest() {
        tick = Long.MIN_VALUE;
        Arrays.fill(used, 0);
        Arrays.fill(urgentUsed, 0);
    }
}
