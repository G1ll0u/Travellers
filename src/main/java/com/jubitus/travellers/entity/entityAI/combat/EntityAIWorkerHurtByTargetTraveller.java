package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.util.math.AxisAlignedBB;

import javax.annotation.Nullable;
import java.util.UUID;

public class EntityAIWorkerHurtByTargetTraveller extends EntityAITarget {

    private final EntityTravellerWorker worker;
    @Nullable private EntityLivingBase attacker;
    private int timestamp;

    public EntityAIWorkerHurtByTargetTraveller(EntityTravellerWorker worker) {
        super(worker, true);
        this.worker = worker;
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (worker.world.isRemote) return false;
        if (!worker.isHiredFollower()) return false;

        attacker = worker.getRevengeTarget();
        int i = worker.getRevengeTimer();

        if (i == this.timestamp) return false;
        if (attacker == null || !attacker.isEntityAlive()) return false;

        // Critical constraint: only retaliate if allowed
        if (!worker.canRetaliateAgainst(attacker)) return false;

        return this.isSuitableTarget(attacker, false);
    }

    @Override
    public void startExecuting() {
        if (attacker != null && attacker.isEntityAlive()) {
            worker.setAttackTarget(attacker);

            UUID enemyOwner = EntityTravellerWorker.getOwnerIdOf(attacker);
            worker.engageEnemyOwner(enemyOwner);
        }

        this.timestamp = worker.getRevengeTimer();
        super.startExecuting();
    }
}
