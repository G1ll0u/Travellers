package com.jubitus.travellers.entity.entityAI.combat;

import net.minecraft.world.World;

public final class CombatPathScheduler {

    public static int GLOBAL_MAX_PATHS_PER_TICK = 24;
    public static int STAGGER_TICKS = 1;


    private static long globalTick = Long.MIN_VALUE;
    private static int globalUsed = 0;

    private CombatPathScheduler() {
    }

    public static boolean tryAcquire(World w, int entityId, int priority) {
        long now = w.getTotalWorldTime();

        if (now != globalTick) {
            globalTick = now;
            globalUsed = 0;
        }

        int cap = (priority >= 2) ? (GLOBAL_MAX_PATHS_PER_TICK + 2) : GLOBAL_MAX_PATHS_PER_TICK;

        if (priority <= 1 && STAGGER_TICKS > 1) {
            int slot = Math.floorMod(entityId, STAGGER_TICKS);
            int cur = (int) Math.floorMod(now, STAGGER_TICKS);
            if (slot != cur) return false;
        }

        if (globalUsed >= cap) return false;
        globalUsed++;
        return true;
    }
}
