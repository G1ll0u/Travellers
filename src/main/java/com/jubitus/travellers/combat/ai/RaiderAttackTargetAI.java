package com.jubitus.travellers.combat.ai;

import com.google.common.base.Predicate;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import com.jubitus.travellers.combat.CombatTargetScanScheduler;

/**
 * Target-scanning AI for raiders. Wraps EntityAINearestAttackableTarget with two additions:
 * - Skips the AABB scan when the raider already has a valid attack target (direct retaliation
 *   via attackEntityFrom handles switching while in combat).
 * - Spreads scan ticks across entity IDs to avoid frame spikes with large raider groups.
 */
public class RaiderAttackTargetAI<T extends EntityLivingBase>
        extends EntityAINearestAttackableTarget<T> {

    // Vanilla's targetChance is a per-tick random gate we can't reach directly (private/final) —
    // re-rolling it several times in the same tick is the cheap way to make a boosted retarget
    // near-certain to fire this tick instead of waiting on chance.
    private static final int RETARGET_BOOST_ATTEMPTS = 20;

    private final EntityTravellerRaider raider;

    public RaiderAttackTargetAI(EntityTravellerRaider raider,
                                Class<T> targetClass,
                                int checkInterval,
                                boolean mustSee,
                                boolean mustReach,
                                Predicate<? super T> selector) {
        super(raider, targetClass, checkInterval, mustSee, mustReach, selector);
        this.raider = raider;
    }

    /** Convenience ctor for simple class-only targeting (no predicate). */
    public RaiderAttackTargetAI(EntityTravellerRaider raider,
                                Class<T> targetClass,
                                boolean mustSee) {
        super(raider, targetClass, mustSee);
        this.raider = raider;
    }

    @Override
    public boolean shouldExecute() {
        // Skip the expensive AABB scan when already engaged with a valid target.
        // Direct retaliation (attackEntityFrom) handles switching targets while in combat.
        EntityLivingBase current = raider.getAttackTarget();
        if (current != null && current.isEntityAlive()) return false;

        // Peacefully camping: don't proactively scan for new targets. Retaliation still works
        // via TravellerHurtByTarget.
        if (raider.isCampingPassive()) return false;

        // Budget the scan world-wide so mass battles can't spike CPU (this task's scan runs at
        // an accelerated rate for every entity currently without a target).
        int scanPriority = raider.isRetargetBoosted() ? 2 : 0;
        if (!CombatTargetScanScheduler.tryAcquire(raider.world, raider.getEntityId(), scanPriority)) return false;

        if (raider.isRetargetBoosted()) {
            for (int i = 0; i < RETARGET_BOOST_ATTEMPTS; i++) {
                if (super.shouldExecute()) return true;
            }
            return false;
        }

        return super.shouldExecute();
    }
}
