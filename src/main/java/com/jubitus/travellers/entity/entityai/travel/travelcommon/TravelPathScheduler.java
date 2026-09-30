package com.jubitus.travellers.entity.entityai.travel.travelcommon;

import com.jubitus.travellers.debug.profile.MovementProfiler;
import net.minecraft.world.World;

public final class TravelPathScheduler {

    // ===== KNOBS =====
    // Normal path refreshes (has path already, just updating)
    public static int NORMAL_MAX_PATHS_PER_TICK = 128;

    // Urgent requests (noPath / hard stuck) get their own larger budget
    public static int URGENT_MAX_PATHS_PER_TICK = 256;

    // Absolute safety ceiling (normal + urgent combined)
    public static int HARD_MAX_PATHS_PER_TICK = 512;

    // Spread normal/medium entities across this many ticks
    public static int STAGGER_TICKS = 10;

    private static long globalTick = Long.MIN_VALUE;
    private static int normalUsed = 0;
    private static int urgentUsed = 0;

    private TravelPathScheduler() {
    }

    /**
     * priority:
     * 0 = normal (has path already, just refreshing)
     * 1 = medium (stuck-ish)
     * 2 = urgent (noPath or hard stuck)
     */
    public static boolean tryAcquire(World w, int entityId, int priority) {
        int result = acquire(w, entityId, priority);
        MovementProfiler.travelSchedule(priority, result);
        return result == MovementProfiler.TRAVEL_GRANTED;
    }

    private static int acquire(World w, int entityId, int priority) {
        long now = w.getTotalWorldTime();

        // reset per-tick counters
        if (now != globalTick) {
            globalTick = now;
            normalUsed = 0;
            urgentUsed = 0;
        }

        boolean urgent = (priority >= 2);

        // staggering for normal/medium only
        if (!urgent && STAGGER_TICKS > 1) {
            int slot = Math.floorMod(entityId, STAGGER_TICKS);
            int cur = (int) Math.floorMod(now, STAGGER_TICKS);
            if (slot != cur) return MovementProfiler.TRAVEL_NOT_ITS_TURN;
        }

        int totalUsed = normalUsed + urgentUsed;
        if (totalUsed >= HARD_MAX_PATHS_PER_TICK) return MovementProfiler.TRAVEL_FULL_HARD;

        if (urgent) {
            if (urgentUsed >= URGENT_MAX_PATHS_PER_TICK) return MovementProfiler.TRAVEL_FULL_URGENT;
            urgentUsed++;
            return MovementProfiler.TRAVEL_GRANTED;
        } else {
            if (normalUsed >= NORMAL_MAX_PATHS_PER_TICK) return MovementProfiler.TRAVEL_FULL_NORMAL;
            normalUsed++;
            return MovementProfiler.TRAVEL_GRANTED;
        }
    }
}
