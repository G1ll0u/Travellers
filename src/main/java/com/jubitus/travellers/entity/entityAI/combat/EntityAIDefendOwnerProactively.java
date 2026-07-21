package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;

import javax.annotation.Nullable;
import java.util.List;

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
        if (worker.isLowHealth()) return false;
        if (worker.getAttackTarget() != null && worker.getAttackTarget().isEntityAlive()) return false;

        if (--cooldown > 0) return false;
        cooldown = intervalTicks;

        EntityPlayer owner = worker.getFollowOwner();
        if (owner == null || owner.isDead) return false;

        // Budget the AABB scan below world-wide so mass battles can't spike CPU.
        int scanPriority = worker.isRetargetBoosted() ? 2 : 0;
        if (!CombatTargetScanScheduler.tryAcquire(worker.world, worker.getEntityId(), scanPriority)) return false;

        EntityTravellerWorker leader = worker.getEscortLeaderEntity();

        double r = 12.0D;
        AxisAlignedBB box = owner.getEntityBoundingBox().grow(r, 4.0D, r);
        if (leader != null && leader.isEntityAlive()) {
            box = box.union(leader.getEntityBoundingBox().grow(r, 4.0D, r));
        }

        List<EntityLivingBase> list = worker.world.getEntitiesWithinAABB(
                EntityLivingBase.class, box,
                e -> {
                    if (e == null || !e.isEntityAlive()) return false;
                    if (e instanceof net.minecraft.entity.monster.IMob) return true;
                    if (e instanceof EntityPlayer) return worker.isEnemyPlayer((EntityPlayer) e);
                    if (e instanceof EntityTravellerWorker) return worker.isEnemyHiredWorker((EntityTravellerWorker) e);
                    return false;
                }
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
            if (!(e instanceof net.minecraft.entity.monster.IMob)) {
                if (e instanceof EntityPlayer && !worker.isEnemyPlayer((EntityPlayer) e)) continue;
                if (e instanceof EntityTravellerWorker && !worker.isEnemyHiredWorker((EntityTravellerWorker) e)) continue;
            }

            // Skip enemies in caves above/below (adjacent cave problem)
            double dy = e.posY - owner.posY;
            if (Math.abs(dy) > 5.0D) continue;

            // Require either the worker or the owner to have line of sight
            boolean canSee = worker.getEntitySenses().canSee(e);
            if (!canSee) continue;

            // Is this entity actively threatening the owner or leader?
            boolean threateningLeadership = false;
            if (e instanceof EntityLiving) {
                EntityLiving el = (EntityLiving) e;
                threateningLeadership = el.getAttackTarget() == owner
                        || (leader != null && el.getAttackTarget() == leader);
            }
            if (e.getRevengeTarget() == owner || (leader != null && e.getRevengeTarget() == leader)) {
                threateningLeadership = true;
            }

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
