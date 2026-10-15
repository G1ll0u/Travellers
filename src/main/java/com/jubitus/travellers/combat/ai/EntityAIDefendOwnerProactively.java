package com.jubitus.travellers.combat.ai;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;

import javax.annotation.Nullable;
import java.util.List;
import com.jubitus.travellers.combat.AttackTargetLimiter;
import com.jubitus.travellers.pathing.PathBudget;
import com.jubitus.travellers.combat.Hostility;

/**
 * Proactive defense: scans around the player owner AND the escort worker-leader.
 * Spreads workers across multiple threats via contest-aware scoring and ignore-list filtering.
 */
public class EntityAIDefendOwnerProactively extends EntityAIBase {

    private final EntityTravellerWorker worker;
    private final int intervalTicks;
    private int cooldown = 0;

    @Nullable
    private EntityLivingBase pending;

    public EntityAIDefendOwnerProactively(EntityTravellerWorker worker, int intervalTicks) {
        this.worker = worker;
        this.intervalTicks = Math.max(1, intervalTicks);
        this.cooldown = worker.getRNG().nextInt(Math.max(1, intervalTicks));
        // Shared mutex bit 1 (vanilla convention for target AIs) so this can't race with the
        // other target-selector tasks and overwrite a target mid-fight — see EntityAIDefendIfClose.
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (worker.world.isRemote) return false;
        if (!worker.isHiredFollower()) return false;
        // On duty / camping = defensive posture: no proactive target picking, retaliation only.
        if (worker.isCampingPassive()) return false;
        if (worker.isLowHealth()) return false;
        if (worker.getAttackTarget() != null && worker.getAttackTarget().isEntityAlive()) return false;

        if (--cooldown > 0) return false;
        cooldown = intervalTicks;

        EntityPlayer owner = worker.getFollowOwner();
        if (owner == null || owner.isDead) return false;

        // Budget the AABB scan below world-wide so mass battles can't spike CPU.
        int scanPriority = worker.isRetargetBoosted() ? 2 : 0;
        if (!PathBudget.tryAcquire(worker.world, PathBudget.Client.COMBAT_SCAN, worker.getEntityId(), scanPriority)) return false;

        EntityTravellerWorker leader = worker.getEscortLeaderEntity();

        // Vertical grow matches the horizontal radius (not a tight ±4/5 band like before) -
        // ranged attackers are routinely well above/below the owner (AW2 town walls/towers,
        // ledges, etc.), and the per-candidate dy filter below only applies to entities that
        // AREN'T already actively attacking, so this can't start chasing things through a
        // ceiling/floor - see the threateningLeadership bypass.
        double r = TravellersConfig.COMBAT.defendOwnerProactiveRadius;
        AxisAlignedBB box = owner.getEntityBoundingBox().grow(r, r, r);
        if (leader != null && leader.isEntityAlive()) {
            box = box.union(leader.getEntityBoundingBox().grow(r, r, r));
        }

        List<EntityLivingBase> list = worker.world.getEntitiesWithinAABB(
                EntityLivingBase.class, box,
                e -> Hostility.isWorkerEnemy(worker, e)
        );

        if (list.isEmpty()) return false;

        // Sort by distance to owner and cap candidates before doing LOS raycasts
        if (list.size() > 12) {
            list.sort((a, b) -> Double.compare(owner.getDistanceSq(a), owner.getDistanceSq(b)));
            list = list.subList(0, 12);
        }

        EntityLivingBase best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (EntityLivingBase e : list) {
            // Is this entity actively threatening the owner or leader? Computed before the
            // cave filter below on purpose - an entity that's already attacking must never be
            // filtered out by it (see that filter's own comment).
            boolean threateningLeadership = Hostility.isAggressingOurSide(worker, e);

            // Skip enemies in caves above/below (adjacent cave problem) - but never an entity
            // that's already actively attacking leadership (e.g. a ranged attacker on a wall or
            // tower well above the owner, not remotely a "different cave" situation).
            double dy = e.posY - owner.posY;
            if (!threateningLeadership && Math.abs(dy) > 5.0D) continue;

            // Require either the worker or the owner to have line of sight
            boolean canSee = worker.getEntitySenses().canSee(e);
            if (!canSee) continue;

            // Skip targets the worker was already blocked on, unless they're actively attacking leadership
            if (!threateningLeadership && worker.isIgnoring(e)) continue;

            int attackerCount = AttackTargetLimiter.getAttackerCount(e);
            double d2 = owner.getDistanceSq(e);

            // Prefer threats targeting leadership; penalize already-contested targets
            double score = d2
                    + (threateningLeadership ? -200.0 : 0.0)
                    + attackerCount * 2.5 * attackerCount * 2.5;

            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }

        pending = best;
        return pending != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return false;
    }

    @Override
    public void startExecuting() {
        if (pending != null && pending.isEntityAlive()) {
            worker.trySetAttackTargetLimited(pending, 1);
        }
        pending = null;
    }
}
