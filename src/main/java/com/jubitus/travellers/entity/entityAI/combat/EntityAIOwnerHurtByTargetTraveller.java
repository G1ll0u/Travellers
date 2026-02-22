package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;

public class EntityAIOwnerHurtByTargetTraveller extends EntityAITarget {

    private final EntityTravellerWorker worker;
    private EntityLivingBase attacker;
    private int timestamp;

    public EntityAIOwnerHurtByTargetTraveller(EntityTravellerWorker worker) {
        super(worker, false);
        this.worker = worker;
    }

    @Override
    public boolean shouldExecute() {
        if (!worker.isHiredFollower()) return false;

        EntityPlayer owner = worker.getFollowOwner();
        if (owner == null) return false;

        this.attacker = owner.getRevengeTarget();
        int i = owner.getRevengeTimer();

        if (i == this.timestamp) {
            return false;
        }
        return this.isSuitableTarget(attacker, false);
    }

    @Override
    public void startExecuting() {
        this.taskOwner.setAttackTarget(this.attacker);
        EntityPlayer owner = worker.getFollowOwner();
        if (owner != null) {
            this.timestamp = owner.getRevengeTimer();
        }
        super.startExecuting();
    }

    @Override
    protected boolean isSuitableTarget(@Nullable EntityLivingBase target, boolean includeInvincibles) {
        if (target == null || !target.isEntityAlive()) return false;
        if (target == worker.getFollowOwner()) return false;

        // Critical constraint: only retaliate if allowed
        if (!worker.canRetaliateAgainst(target)) return false;

        return super.isSuitableTarget(target, includeInvincibles);
    }

}
