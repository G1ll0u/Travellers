package com.jubitus.travellers.entity.entityai.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Hard cap "how many melee attackers may hold the same target at once".
 * Archers bypass this limit entirely — they don't compete for melee slots.
 * The cap grows over time if the target is hard to kill (long fight, kills attackers).
 */
public final class AttackTargetLimiter {

    private static final Map<UUID, TargetBucket> buckets = new HashMap<>();

    private static int maxAttackersPerTarget() {
        return TravellersConfig.COMBAT.maxAttackersPerTarget;
    }

    private static int staleTicks() {
        return TravellersConfig.COMBAT.attackerSlotStaleTicks;
    }

    // Dynamic cap thresholds
    private static final int AGE_BONUS_1_TICKS = 5 * 20;   // +1 after 5s
    private static final int AGE_BONUS_2_TICKS = 15 * 20;  // +2 after 15s
    private static final int MAX_DEATH_BONUS = 3;           // +1 per attacker killed, up to +3

    private AttackTargetLimiter() {
    }

    /**
     * Priority for melee slot competition. Archers never call this — they bypass.
     */
    public static int priorityFor(EntityLivingBase attacker) {
        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.isLowHealth()) return 0;
        }
        return 2;
    }

    /**
     * Computes the effective attacker cap for a target, scaled by fight duration and danger.
     * Returns maxAttackersPerTarget() if the target has no bucket (fight just started).
     */
    public static int getEffectiveMax(EntityLivingBase target, long now) {
        if (target == null) return maxAttackersPerTarget();
        TargetBucket b = buckets.get(target.getUniqueID());
        if (b == null) return maxAttackersPerTarget();
        int cap = maxAttackersPerTarget();
        long age = now - b.firstEngagedTick;
        if (age >= AGE_BONUS_2_TICKS) cap += 4;
        else if (age >= AGE_BONUS_1_TICKS) cap += 4;
        cap += Math.min(MAX_DEATH_BONUS, b.attackerDeaths);
        return cap;
    }

    /**
     * Called when a traveller attacking this target dies.
     * Signals that the target is dangerous and should allow more attackers.
     */
    public static void notifyAttackerDied(EntityLivingBase target) {
        if (target == null) return;
        TargetBucket b = buckets.get(target.getUniqueID());
        if (b != null) b.attackerDeaths++;
    }

    /**
     * Returns how many attackers currently hold a slot on this target.
     */
    public static int getAttackerCount(EntityLivingBase target) {
        if (target == null) return 0;
        TargetBucket b = buckets.get(target.getUniqueID());
        return b == null ? 0 : b.attackers.size();
    }

    /**
     * Try to reserve/keep a slot for attacker on this target (uses default cap).
     */
    public static boolean tryJoin(EntityLivingBase attacker, EntityLivingBase target, int priority) {
        return tryJoin(attacker, target, priority, maxAttackersPerTarget());
    }

    /**
     * Try to reserve/keep a slot for attacker on this target with a custom per-target cap.
     */
    public static boolean tryJoin(EntityLivingBase attacker, EntityLivingBase target, int priority, int maxAttackers) {
        if (attacker == null || target == null) return false;
        World w = attacker.world;
        if (w == null) return false;

        long now = w.getTotalWorldTime();
        UUID tid = target.getUniqueID();
        UUID aid = attacker.getUniqueID();

        TargetBucket b = buckets.get(tid);
        if (b == null) {
            b = new TargetBucket();
            b.firstEngagedTick = now;
            buckets.put(tid, b);
        }

        pruneBucket(b, now);

        double d2 = attacker.getDistanceSq(target);

        // already reserved -> refresh
        AttackEntry existing = b.attackers.get(aid);
        if (existing != null) {
            existing.lastSeenTick = now;
            existing.priority = priority;
            existing.distSq = d2;
            return true;
        }

        // room -> take slot
        if (b.attackers.size() < maxAttackers) {
            b.attackers.put(aid, new AttackEntry(now, priority, d2));
            return true;
        }

        // full -> can we replace a worse attacker?
        // Attackers still within their engagement grace window are never eviction candidates —
        // this is what stops a traveller's chase from being cut short the instant a "better"
        // contender shows up mid-fight.
        long graceTicks = TravellersConfig.COMBAT.engagementGraceTicks;
        UUID worstId = null;
        AttackEntry worst = null;

        for (Map.Entry<UUID, AttackEntry> e : b.attackers.entrySet()) {
            AttackEntry cur = e.getValue();
            if (now - cur.engagedSinceTick < graceTicks) continue;

            if (worst == null) {
                worst = cur;
                worstId = e.getKey();
                continue;
            }

            // define "worse":
            // 1) lower priority is worse
            // 2) if same priority: FARTHER is worse
            // 3) if same: OLDER is worse
            boolean curIsWorse =
                    (cur.priority < worst.priority) ||
                            (cur.priority == worst.priority && cur.distSq > worst.distSq) ||
                            (cur.priority == worst.priority && cur.distSq == worst.distSq && cur.lastSeenTick < worst.lastSeenTick);

            if (curIsWorse) {
                worst = cur;
                worstId = e.getKey();
            }
        }

        // Every current attacker is still within its grace window -> no room to join yet
        if (worst == null) return false;

        // If new attacker is better than worst -> replace
        boolean newIsBetter =
                (priority > worst.priority) ||
                        (priority == worst.priority && d2 < worst.distSq);

        if (newIsBetter && worstId != null) {
            b.attackers.remove(worstId);
            b.attackers.put(aid, new AttackEntry(now, priority, d2));
            return true;
        }

        return false;
    }

    private static void pruneBucket(TargetBucket b, long now) {
        if (b.attackers.isEmpty()) return;
        // At most one prune per world tick per bucket — avoids O(attackers) on every keepAlive call
        if (b.lastPruneTick == now) return;
        b.lastPruneTick = now;
        Iterator<Map.Entry<UUID, AttackEntry>> it = b.attackers.entrySet().iterator();
        while (it.hasNext()) {
            AttackEntry e = it.next().getValue();
            long age = now - e.lastSeenTick;
            if (age > staleTicks()) it.remove();
        }
    }

    /**
     * Release reservation (call when clearing target / dying).
     */
    public static void leave(EntityLivingBase attacker, EntityLivingBase target) {
        if (attacker == null || target == null) return;
        World w = attacker.world;
        if (w == null) return;

        long now = w.getTotalWorldTime();
        UUID tid = target.getUniqueID();
        UUID aid = attacker.getUniqueID();

        TargetBucket b = buckets.get(tid);
        if (b == null) return;

        b.attackers.remove(aid);
        pruneBucket(b, now);

        if (b.attackers.isEmpty()) {
            buckets.remove(tid);
        }
    }

    private static final class AttackEntry {
        long lastSeenTick;
        final long engagedSinceTick; // set once, when the slot was first taken — grace window anchor
        int priority;     // 2 = melee preferred, 1 = bow, 0 = other
        double distSq;    // distance to target (used to keep "nearest")

        AttackEntry(long t, int p, double d2) {
            lastSeenTick = t;
            engagedSinceTick = t;
            priority = p;
            distSq = d2;
        }
    }

    private static final class TargetBucket {
        final Map<UUID, AttackEntry> attackers = new HashMap<>();
        long lastPruneTick = Long.MIN_VALUE;
        long firstEngagedTick = Long.MAX_VALUE;
        int attackerDeaths = 0;
    }
}
