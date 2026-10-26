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

    // Candidates are tried nearest-first; this only bounds a raider walled off from its nearest ones.
    private static final int MAX_RAYS_PER_SCAN = 8;

    private final EntityTravellerRaider raider;
    /** Vanilla's targetChance (private there): the raid scan rolls the same 1-in-N gate. */
    private final int scanChance;

    public RaiderAttackTargetAI(EntityTravellerRaider raider,
                                Class<T> targetClass,
                                int checkInterval,
                                boolean mustSee,
                                boolean mustReach,
                                Predicate<? super T> selector) {
        super(raider, targetClass, checkInterval, mustSee, mustReach, selector);
        this.raider = raider;
        this.scanChance = checkInterval;
    }

    /** Convenience ctor for simple class-only targeting (no predicate). */
    public RaiderAttackTargetAI(EntityTravellerRaider raider,
                                Class<T> targetClass,
                                boolean mustSee) {
        super(raider, targetClass, mustSee);
        this.raider = raider;
        this.scanChance = 10;
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
        if (raidPlayer != null && (raider.isRetargetBoosted() || scanChance <= 0
                || raider.getRNG().nextInt(scanChance) == 0)) {
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
        net.minecraft.util.math.AxisAlignedBB box = raider.getEntityBoundingBox().grow(range, com.jubitus.travellers.combat.SharedThreatScan.SCAN_HEIGHT, range);
        boolean profiling = com.jubitus.travellers.debug.profile.MovementProfiler.isActive();
        long startNanos = profiling ? System.nanoTime() : 0L;
        int inBox = 0, rays = 0;
        java.util.List<EntityLivingBase> candidates = new java.util.ArrayList<>();
        for (EntityLivingBase e : raider.world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            inBox++;
            if (!e.isEntityAlive() || e.isInvisible() || raider.isIgnoring(e)) continue;
            if (!com.jubitus.travellers.combat.SharedThreatScan.heightAllows(raider, e)) continue;
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
            if (preferred) candidates.add(e);
        }
        // Nearest first, stop at the first visible one: far candidates never cost a ray.
        candidates.sort(java.util.Comparator.comparingDouble(raider::getDistanceSq));
        EntityLivingBase best = null;
        for (EntityLivingBase e : candidates) {
            if (rays >= MAX_RAYS_PER_SCAN) break;
            rays++;
            if (raider.getEntitySenses().canSee(e)) {
                best = e;
                break;
            }
        }
        if (profiling) {
            com.jubitus.travellers.debug.profile.MovementProfiler.raidScan(
                    inBox, candidates.size(), rays, best != null, System.nanoTime() - startNanos);
        }
        return best;
    }

    // Hands the target off once: vanilla's continue/reset would drop it beyond FOLLOW_RANGE or after 3 s unseen.
    @Override
    public boolean shouldContinueExecuting() {
        return false;
    }

    @Override
    public void resetTask() {
    }

    // Detection uses the combat range, not FOLLOW_RANGE (a pathing setting): a target beyond it would be refused anyway.
    @Override
    protected double getTargetDistance() {
        return com.jubitus.travellers.config.TravellersConfig.COMBAT.unitAttackTriggerRange;
    }

    // Vanilla scans 4 blocks up/down; candidates must be seen, which already rules out caves.
    @Override
    protected net.minecraft.util.math.AxisAlignedBB getTargetableArea(double targetDistance) {
        return taskOwner.getEntityBoundingBox().grow(targetDistance, com.jubitus.travellers.combat.SharedThreatScan.SCAN_HEIGHT, targetDistance);
    }
}
