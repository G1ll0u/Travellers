package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;

public class EntityAIOwnerHurtTargetTraveller extends EntityAITarget {

    private final EntityTravellerWorker worker;
    private EntityLivingBase target;
    private int timestamp;

    public EntityAIOwnerHurtTargetTraveller(EntityTravellerWorker worker) {
        super(worker, false);
        this.worker = worker;
    }

    @Override
    public boolean shouldExecute() {
        if (!worker.isHiredFollower()) return false;
        // Low health: don't assist owner's offensive targeting, focus on healing
        if (worker.isLowHealth()) return false;

        EntityPlayer owner = worker.getFollowOwner();
        if (owner == null) return false;

        this.target = owner.getLastAttackedEntity();
        int i = owner.getLastAttackedEntityTime();

        if (i == this.timestamp) {
            return false;
        }
        return this.isSuitableTarget(target, false);
    }

    @Override
    public void startExecuting() {
        this.taskOwner.setAttackTarget(this.target);
        EntityPlayer owner = worker.getFollowOwner();
        if (owner != null) {
            this.timestamp = owner.getLastAttackedEntityTime();
        }
        super.startExecuting();
    }

    @Override
    protected boolean isSuitableTarget(@Nullable EntityLivingBase target, boolean includeInvincibles) {
        if (target == null || !target.isEntityAlive()) return false;
        if (target == worker.getFollowOwner()) return false;

        TravellerBlacklist blacklist = TravellersDerivedConfig.TRAVELLER_AGGRO_BLACKLIST;
        if (blacklist != null && blacklist.isBlacklisted(target)) return false;

        // Assist owner if:
        // - target is a hostile mob, OR
        // - target is an enemy by declared/engaged rules
        if (target instanceof net.minecraft.entity.monster.IMob) return true;

        if (target instanceof EntityPlayer) {
            return worker.isEnemyPlayer((EntityPlayer) target);
        }
        if (target instanceof EntityTravellerWorker) {
            return worker.isEnemyHiredWorker((EntityTravellerWorker) target);
        }

        return super.isSuitableTarget(target, includeInvincibles);
    }



}
