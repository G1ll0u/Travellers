package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Hard cap "how many attackers may hold the same target at once".
 * Works by reserving a slot per (target -> attacker) and expiring stale entries.
 */
public final class AttackTargetLimiter {

    private static final Map<UUID, TargetBucket> buckets = new HashMap<>();
    // ===== KNOBS =====
    public static int MAX_ATTACKERS_PER_TARGET = 4;
    public static int STALE_TICKS = 20; // 2s

    private AttackTargetLimiter() {
    }

    /**
     * Priority helper. You can tweak this.
     */
    public static int priorityFor(EntityLivingBase attacker) {
        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;

            // Low health entities yield their slot to healthy ones
            if (t.isLowHealth()) return 0;

            // "non-bow" workers should win the 8 slots
            boolean bowUser = t.hasBowAnywherePublic() || t.isAimingBow();
            return bowUser ? 1 : 2;
        }
        return 0;
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
     * Call while you are actively attacking to keep the reservation alive. Returns false if you lost the slot.
     */
    public static boolean keepAlive(EntityLivingBase attacker, EntityLivingBase target, int priority) {
        return tryJoin(attacker, target, priority, MAX_ATTACKERS_PER_TARGET);
    }

    /**
     * Try to reserve/keep a slot for attacker on this target (uses default cap).
     */
    public static boolean tryJoin(EntityLivingBase attacker, EntityLivingBase target, int priority) {
        return tryJoin(attacker, target, priority, MAX_ATTACKERS_PER_TARGET);
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
        UUID worstId = null;
        AttackEntry worst = null;

        for (Map.Entry<UUID, AttackEntry> e : b.attackers.entrySet()) {
            AttackEntry cur = e.getValue();
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
            if (age > STALE_TICKS) it.remove();
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
        int priority;     // 2 = melee preferred, 1 = bow, 0 = other
        double distSq;    // distance to target (used to keep "nearest")

        AttackEntry(long t, int p, double d2) {
            lastSeenTick = t;
            priority = p;
            distSq = d2;
        }
    }

    private static final class TargetBucket {
        final Map<UUID, AttackEntry> attackers = new HashMap<>();
        long lastPruneTick = Long.MIN_VALUE; // prune at most once per world tick
    }
}
