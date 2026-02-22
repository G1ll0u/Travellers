package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;

import javax.annotation.Nullable;
import java.util.List;

public class EntityAIDefendOwnerProactively extends EntityAIBase {

    private final EntityTravellerWorker worker;
    private final int intervalTicks;
    private int cooldown = 0;

    @Nullable
    private EntityLivingBase pending;

    public EntityAIDefendOwnerProactively(EntityTravellerWorker worker, int intervalTicks) {
        this.worker = worker;
        this.intervalTicks = Math.max(1, intervalTicks);
        this.setMutexBits(0);
    }

    @Override
    public boolean shouldExecute() {
        if (worker.world.isRemote) return false;
        if (!worker.isHiredFollower()) return false;

        if (worker.getAttackTarget() != null && worker.getAttackTarget().isEntityAlive()) return false;

        if (--cooldown > 0) return false;
        cooldown = intervalTicks;

        EntityPlayer owner = worker.getFollowOwner();
        if (owner == null || owner.isDead) return false;

        // scan around OWNER (this is the key)
        double r = 12.0D;
        AxisAlignedBB box = owner.getEntityBoundingBox().grow(r, 4.0D, r);

        List<EntityLivingBase> list = worker.world.getEntitiesWithinAABB(
                EntityLivingBase.class, box,
                e -> {
                    if (e == null || !e.isEntityAlive()) return false;

                    // normal hostile mobs
                    if (e instanceof net.minecraft.entity.monster.IMob) return true;

                    // hostile marked players
                    if (e instanceof EntityPlayer) {
                        EntityPlayer p = (EntityPlayer) e;
                        return worker.isEnemyPlayer(p);
                    }

                    // enemy hired workers
                    if (e instanceof EntityTravellerWorker) {
                        return worker.isEnemyHiredWorker((EntityTravellerWorker) e);
                    }

                    return false;
                }
        );


        if (list.isEmpty()) return false;

        EntityLivingBase best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (EntityLivingBase e : list) {
            // Hard skip if blacklisted etc.
            if (!(e instanceof net.minecraft.entity.monster.IMob)) {
                if (e instanceof EntityPlayer && !worker.isEnemyPlayer((EntityPlayer) e)) continue;
                if (e instanceof EntityTravellerWorker && !worker.isEnemyHiredWorker((EntityTravellerWorker) e)) continue;
            }

            boolean targetsOwner = false;
            if (e instanceof EntityLiving) {
                targetsOwner = ((EntityLiving) e).getAttackTarget() == owner;
            }
            if (e.getRevengeTarget() == owner) targetsOwner = true;

            // Prefer mobs already targeting owner
            double d2 = owner.getDistanceSq(e);
            double score = d2 + (targetsOwner ? -200.0 : 0.0);

            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }

        pending = best;
        return pending != null;
    }

    @Override
    public void startExecuting() {
        if (pending != null && pending.isEntityAlive()) {
            // urgent: bypass scheduler close-range, and bypass limiter if emergency applies
            worker.trySetAttackTargetLimited(pending, 2);
        }
        pending = null;
    }
}

