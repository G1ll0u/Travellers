package com.jubitus.travellers.combat.ai;

import com.jubitus.travellers.combat.PlayerHostility;
import com.jubitus.travellers.unit.EntityTravellerWorker;
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
        if (!worker.playerHostility.canRetaliateAgainst(attacker)) return false;

        return this.isSuitableTarget(attacker, false);
    }

    @Override
    public void startExecuting() {
        if (attacker != null && attacker.isEntityAlive()) {
            worker.setAttackTarget(attacker);

            UUID enemyOwner = PlayerHostility.ownerIdOf(attacker);
            worker.playerHostility.engageEnemyOwner(enemyOwner);

            // Escalate individual -> group: every other hired worker of the same owner nearby
            // joins the fight too, regardless of what kind of attacker this was (mob, AW2 hostile,
            // enemy worker, or a player the owner already declared war on - canRetaliateAgainst
            // above already gated that last case, this doesn't re-check it).
            PlayerHostility.rallyGroupAgainst(worker, attacker, enemyOwner);
        }

        this.timestamp = worker.getRevengeTimer();
        super.startExecuting();
    }

    // Hands the target off once: vanilla's continue/reset would drop it beyond FOLLOW_RANGE or after 3 s unseen.
    @Override
    public boolean shouldContinueExecuting() {
        return false;
    }

    @Override
    public void resetTask() {
    }
}
