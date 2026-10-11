package com.jubitus.travellers.combat.ai;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.combat.Hostility;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.combat.lists.EntityRuleList;
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
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (!worker.isHiredFollower()) return false;
        // Defensive posture (on duty): never joins the owner's own attacks, only retaliates.
        if (worker.isCampingPassive()) return false;
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
        // Owner just tapped a bonded horse (routine when riding near one) - never assist onto it.
        if (target instanceof net.minecraft.entity.passive.EntityHorse
                && HorseMarkers.isBondedMount((net.minecraft.entity.passive.EntityHorse) target)) {
            return false;
        }

        if (worker.getAggroBlacklist().matches(target)) return false;

        if (Hostility.isWorkerEnemy(worker, target)) return true;
        // Players and workers only when they are enemies; anything else the owner hits is a target too.
        if (target instanceof EntityPlayer || target instanceof EntityTravellerWorker) return false;
        return super.isSuitableTarget(target, includeInvincibles);
    }



}
