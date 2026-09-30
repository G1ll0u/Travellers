package com.jubitus.travellers.entity.entityai.combat;

import com.google.common.base.Predicate;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

/**
 * Throttled version of EntityAINearestAttackableTarget for workers.
 *
 * When a worker is peacefully following its owner (no current attack target),
 * the combat scan runs every IDLE_CHECK_INTERVAL ticks instead of every
 * DEFAULT_CHECK_INTERVAL ticks. This is the main fix for the 15% tick usage
 * caused by 500 workers each scanning a large AABB every 10 ticks.
 *
 * When the worker is already in combat or not in follow mode, it falls back
 * to the normal vanilla check interval (no behaviour change).
 */
public class ThrottledAttackTargetAI<T extends EntityLivingBase>
        extends EntityAINearestAttackableTarget<T> {

    // How many ticks between combat scans when following peacefully (no current target).
    // 40 ticks = 2 seconds. Fine for follow escort — still reacts quickly enough.
    private static final int IDLE_CHECK_INTERVAL = 40;

    // Vanilla's targetChance is a per-tick random gate we can't reach directly (private/final) —
    // re-rolling it several times in the same tick is the cheap way to make a boosted retarget
    // near-certain to fire this tick instead of waiting on chance.
    private static final int RETARGET_BOOST_ATTEMPTS = 20;

    private final EntityTravellerWorker worker;

    public ThrottledAttackTargetAI(EntityTravellerWorker worker,
                                   Class<T> targetClass,
                                   int vanillaCheckInterval,
                                   boolean mustSee,
                                   boolean mustReach,
                                   Predicate<? super T> selector) {
        super(worker, targetClass, vanillaCheckInterval, mustSee, mustReach, selector);
        this.worker = worker;
    }

    @Override
    public boolean shouldExecute() {
        // Low health: stop seeking new targets, rely on direct retaliation (EntityAIWorkerHurtByTargetTraveller)
        if (worker.isLowHealth()) return false;

        // Anchor/stay mode: don't range far, EntityAIDefendIfClose handles close defense
        if (worker.isHiredFollower() && !worker.isFollowOrderActive()) return false;

        boolean boosted = worker.isRetargetBoosted();

        // If the worker is quietly following and has no current target,
        // only scan every IDLE_CHECK_INTERVAL ticks.
        // We spread the checks across entity IDs so all 500 workers don't hit the same tick.
        if (!boosted && worker.isFollowOrderActive() && worker.getAttackTarget() == null) {
            if ((worker.ticksExisted + worker.getEntityId() * 7) % IDLE_CHECK_INTERVAL != 0) {
                return false;
            }
        }

        // Budget the scan world-wide so a mass battle with many hired workers can't spike CPU.
        int scanPriority = boosted ? 2 : 0;
        if (!CombatTargetScanScheduler.tryAcquire(worker.world, worker.getEntityId(), scanPriority)) return false;

        boolean found;
        if (boosted) {
            found = false;
            for (int i = 0; i < RETARGET_BOOST_ATTEMPTS; i++) {
                if (super.shouldExecute()) { found = true; break; }
            }
        } else {
            found = super.shouldExecute();
        }

        if (found) preferTargetNearOwner();
        return found;
    }

    /**
     * Escorts guard the player/leader, not just themselves: if a valid target is standing
     * meaningfully closer to the worker's owner/leader than the one vanilla's nearest-to-self sort
     * picked, engage that one instead. Bounded to one small extra AABB query, only when a target
     * was already found and the worker isn't already right next to its owner (nothing to gain).
     */
    private void preferTargetNearOwner() {
        if (!worker.isHiredFollower() || targetEntity == null) return;

        EntityLivingBase owner = worker.getEffectiveFollowTarget();
        if (owner == null || owner == worker) return;

        double followerToOwnerSq = worker.getDistanceSq(owner);
        if (followerToOwnerSq < 4.0D * 4.0D) return;

        double radius = TravellersConfig.COMBAT.escortOwnerPriorityRadius;
        if (radius <= 0.0D) return;

        AxisAlignedBB box = owner.getEntityBoundingBox().grow(radius, 6.0D, radius);
        List<T> nearOwner = worker.world.getEntitiesWithinAABB(targetClass, box, targetEntitySelector);
        if (nearOwner.isEmpty()) return;

        double currentDistToOwnerSq = owner.getDistanceSq(targetEntity);
        T best = null;
        double bestDistSq = currentDistToOwnerSq;

        for (T candidate : nearOwner) {
            double d = owner.getDistanceSq(candidate);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = candidate;
            }
        }

        // Only switch when meaningfully closer to the owner - avoids flip-flopping between two
        // near-equal candidates every scan.
        if (best != null && bestDistSq < currentDistToOwnerSq - 4.0D) {
            targetEntity = best;
        }
    }
}
