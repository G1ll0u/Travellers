package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.entity.player.EntityPlayer;

public class EntityAIStopAndLookAtPlayer extends net.minecraft.entity.ai.EntityAIBase {
    private final EntityTraveller traveller;
    private final double triggerRangeSq;
    private final float yawLimit;
    private final float pitchLimit;

    private EntityPlayer target;

    public EntityAIStopAndLookAtPlayer(EntityTraveller traveller, double triggerRange, float yawLimit, float pitchLimit) {
        this.traveller = traveller;
        this.triggerRangeSq = triggerRange * triggerRange;
        this.yawLimit = yawLimit;
        this.pitchLimit = pitchLimit;
        this.setMutexBits(2); // 1=MOVE, 2=LOOK
    }

    @Override
    public boolean shouldExecute() {
        if (traveller.world.isRemote) return false;
        if (traveller.hasFollowLeader()) return false;
        if (traveller.isInCombat()) return false;
        if (traveller.isAutoEating()) return false;

        // NEW: ignore while a player is mounted
        if (traveller.hasPlayerPassenger()) return false;

        EntityPlayer p = traveller.world.getClosestPlayerToEntity(traveller, Math.sqrt(triggerRangeSq));
        if (p == null || !p.isEntityAlive() || p.isSpectator()) return false;

        double dSq = traveller.getDistanceSq(p);
        if (dSq > triggerRangeSq) return false;

        if (!traveller.getEntitySenses().canSee(p)) return false;

        this.target = p;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (traveller.hasFollowLeader()) return false;
        if (traveller.isInCombat() || traveller.isAutoEating()) return false;
        if (traveller.hasPlayerPassenger()) return false; // NEW
        return target != null && target.isEntityAlive()
                && traveller.getDistanceSq(target) <= triggerRangeSq
                && traveller.getEntitySenses().canSee(target);
    }

    @Override
    public void startExecuting() {
        // Don't force a stop if someone is riding
        if (!traveller.hasPlayerPassenger()) {
            traveller.getNavigator().clearPath();
        }
    }

    @Override
    public void resetTask() {
        this.target = null;
    }

    @Override
    public void updateTask() {
        if (traveller.isInCombat() || traveller.hasFollowLeader()) return;
        if (traveller.hasPlayerPassenger()) return; // NEW: do nothing while mounted

        // Optional: only keep this if you want them to stop when looking
        traveller.getNavigator().clearPath();

        traveller.getLookHelper().setLookPositionWithEntity(target, yawLimit, pitchLimit);
    }
}
