package com.jubitus.travellers.entity.entityAI.combat;

import com.google.common.base.Predicate;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;

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

        if (boosted) {
            for (int i = 0; i < RETARGET_BOOST_ATTEMPTS; i++) {
                if (super.shouldExecute()) return true;
            }
            return false;
        }

        return super.shouldExecute();
    }
}
