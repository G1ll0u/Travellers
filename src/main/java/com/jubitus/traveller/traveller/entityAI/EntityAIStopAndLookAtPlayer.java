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
        // Block both movement & other look AIs while this runs
        this.setMutexBits(2); // 1=MOVE, 2=LOOK
    }

    @Override
    public boolean shouldExecute() {
        if (traveller.hasFollowLeader()) return false; // <-- don't interrupt follower
        if (traveller.world.isRemote) return false;
        if (traveller.isInCombat()) return false;
        if (traveller.isAutoEating()) return false;

        EntityPlayer p = traveller.world.getClosestPlayerToEntity(traveller, Math.sqrt(triggerRangeSq));
        if (p == null) return false;
        if (!p.isEntityAlive()) return false;
        if (p.isSpectator()) return false;

        double dSq = traveller.getDistanceSq(p);
        if (dSq > triggerRangeSq) return false;

        // optional: require LOS so they don’t fixate through walls
        if (!traveller.getEntitySenses().canSee(p)) return false;

        this.target = p;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (traveller.hasFollowLeader()) return false; // <-- belt & suspenders
        if (target == null || !target.isEntityAlive()) return false;
        if (traveller.isInCombat() || traveller.isAutoEating()) return false;
        return traveller.getDistanceSq(target) <= triggerRangeSq
                && traveller.getEntitySenses().canSee(target);
    }

    @Override
    public void startExecuting() {
        traveller.getNavigator().clearPath();  // stop moving immediately
    }

    @Override
    public void resetTask() {
        this.target = null;
        // No special cleanup needed; other movement AIs will resume next tick
    }

    @Override
    public void updateTask() {
        if (traveller.isInCombat() || traveller.hasFollowLeader()) return; // <-- no head control during follow/combat
        traveller.getNavigator().clearPath(); // keep only if you want them to *stop* when not following
        traveller.getLookHelper().setLookPositionWithEntity(target, 30f, 30f);
    }
}

