package com.jubitus.travellers.entity.entityAI.combat;

import com.google.common.base.Predicate;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;

/**
 * Target-scanning AI for raiders. Wraps EntityAINearestAttackableTarget with two additions:
 * - Skips the AABB scan when the raider already has a valid attack target (direct retaliation
 *   via attackEntityFrom handles switching while in combat).
 * - Spreads scan ticks across entity IDs to avoid frame spikes with large raider groups.
 */
public class RaiderAttackTargetAI<T extends EntityLivingBase>
        extends EntityAINearestAttackableTarget<T> {

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

        return super.shouldExecute();
    }
}
