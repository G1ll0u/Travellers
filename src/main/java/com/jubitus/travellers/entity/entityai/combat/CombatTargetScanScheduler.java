package com.jubitus.travellers.entity.entityai.combat;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.world.World;

/**
 * World-wide per-tick budget for target-acquisition scans (the AABB nearby-enemy searches in
 * EntityAIDefendIfClose, EntityAIDefendOwnerProactively, and EntityAINearestAttackableTarget
 * subclasses). Same pattern as CombatPathScheduler, applied to target scanning instead of
 * pathfinding, so a mass battle with many hired workers can't spike CPU by having all of them
 * scan the same tick. An entity that misses the budget just retries on its next scheduled tick.
 */
public final class CombatTargetScanScheduler {

    private static long globalTick = Long.MIN_VALUE;
    private static int globalUsed = 0;

    private CombatTargetScanScheduler() {
    }

    public static boolean tryAcquire(World w, int entityId, int priority) {
        int cap = TravellersConfig.COMBAT.combatTargetScanBudgetPerTick;
        long now = w.getTotalWorldTime();

        if (now != globalTick) {
            globalTick = now;
            globalUsed = 0;
        }

        int effectiveCap = (priority >= 2) ? (cap + cap / 4) : cap;

        if (globalUsed >= effectiveCap) return false;
        globalUsed++;
        return true;
    }
}
