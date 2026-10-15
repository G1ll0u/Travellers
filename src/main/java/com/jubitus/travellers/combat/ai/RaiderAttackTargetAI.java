package com.jubitus.travellers.combat.ai;

import com.google.common.base.Predicate;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import com.jubitus.travellers.pathing.PathBudget;

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
        if (!PathBudget.tryAcquire(raider.world, PathBudget.Client.COMBAT_SCAN, raider.getEntityId(), scanPriority)) return false;

        // A raider on a raid goes for the raid's player and that player's hired workers first.
        java.util.UUID raidPlayer = com.jubitus.travellers.raid.RaidWorldData
                .targetPlayer(raider.world, raider.getUniqueID());
        if (raidPlayer != null) {
            EntityLivingBase preferred = nearestRaidTarget(raidPlayer);
            if (preferred != null) {
                @SuppressWarnings("unchecked")
                T picked = (T) preferred;
                this.targetEntity = picked;
                return true;
            }
        }

        if (raider.isRetargetBoosted()) {
            for (int i = 0; i < RETARGET_BOOST_ATTEMPTS; i++) {
                if (super.shouldExecute()) return true;
            }
            return false;
        }

        return super.shouldExecute();
    }

    private EntityLivingBase nearestRaidTarget(java.util.UUID playerId) {
        double range = getTargetDistance();
        net.minecraft.util.math.AxisAlignedBB box = raider.getEntityBoundingBox().grow(range, 4.0D, range);
        EntityLivingBase best = null;
        double bestSq = Double.MAX_VALUE;
        for (EntityLivingBase e : raider.world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (!e.isEntityAlive() || e.isInvisible() || raider.isIgnoring(e)) continue;
            boolean preferred;
            if (e instanceof net.minecraft.entity.player.EntityPlayer) {
                net.minecraft.entity.player.EntityPlayer p = (net.minecraft.entity.player.EntityPlayer) e;
                preferred = p.getUniqueID().equals(playerId) && !p.isCreative() && !p.isSpectator();
            } else if (e instanceof com.jubitus.travellers.unit.EntityTravellerWorker) {
                com.jubitus.travellers.unit.EntityTravellerWorker w = (com.jubitus.travellers.unit.EntityTravellerWorker) e;
                preferred = w.isHiredFollower() && playerId.equals(w.getFollowOwnerId());
            } else {
                preferred = false;
            }
            if (!preferred || !raider.getEntitySenses().canSee(e)) continue;
            double d = raider.getDistanceSq(e);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }
}
