package com.jubitus.travellers.entity.entityai.combat;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Reacts when the player owner OR the escort worker-leader takes a hit.
 * Fires once per new attacker (UUID-based dedup) instead of every tick of the revenge countdown.
 */
public class EntityAIOwnerHurtByTargetTraveller extends EntityAIBase {

    private final EntityTravellerWorker worker;
    @Nullable private EntityLivingBase attacker;
    @Nullable private UUID lastOwnerRevengerId = null;
    @Nullable private UUID lastLeaderRevengerId = null;

    public EntityAIOwnerHurtByTargetTraveller(EntityTravellerWorker worker) {
        super();
        this.worker = worker;
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

        EntityPlayer owner = worker.getFollowOwner();
        EntityTravellerWorker leader = worker.getEscortLeaderEntity();

        EntityLivingBase picked = null;
        double bestDistSq = Double.MAX_VALUE;

        // Check owner's revenge target
        if (owner != null && !owner.isDead) {
            EntityLivingBase ownerRevenge = owner.getRevengeTarget();
            if (ownerRevenge != null && ownerRevenge.isEntityAlive()) {
                UUID id = ownerRevenge.getUniqueID();
                if (!id.equals(lastOwnerRevengerId) && isSuitable(ownerRevenge)) {
                    double d2 = worker.getDistanceSq(ownerRevenge);
                    if (d2 < bestDistSq) {
                        picked = ownerRevenge;
                        bestDistSq = d2;
                    }
                }
            } else if (ownerRevenge == null) {
                lastOwnerRevengerId = null;
            }
        }

        // Check worker-leader's revenge target
        if (leader != null && leader.isEntityAlive()) {
            EntityLivingBase leaderRevenge = leader.getRevengeTarget();
            if (leaderRevenge != null && leaderRevenge.isEntityAlive()) {
                UUID id = leaderRevenge.getUniqueID();
                if (!id.equals(lastLeaderRevengerId) && isSuitable(leaderRevenge)) {
                    double d2 = worker.getDistanceSq(leaderRevenge);
                    if (d2 < bestDistSq) {
                        picked = leaderRevenge;
                    }
                }
            } else if (leaderRevenge == null) {
                lastLeaderRevengerId = null;
            }
        }

        if (picked == null) return false;
        this.attacker = picked;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return false;
    }

    @Override
    public void startExecuting() {
        if (attacker == null || !attacker.isEntityAlive()) return;

        UUID id = attacker.getUniqueID();
        // Record which revenge triggered this so we don't fire again for the same attacker
        EntityPlayer owner = worker.getFollowOwner();
        if (owner != null && owner.getRevengeTarget() == attacker) lastOwnerRevengerId = id;
        EntityTravellerWorker leader = worker.getEscortLeaderEntity();
        if (leader != null && leader.getRevengeTarget() == attacker) lastLeaderRevengerId = id;

        worker.setAttackTarget(attacker);
        attacker = null;
    }

    private boolean isSuitable(@Nullable EntityLivingBase target) {
        if (target == null || !target.isEntityAlive()) return false;
        if (target == worker.getFollowOwner()) return false;
        return worker.canRetaliateAgainst(target);
    }
}
