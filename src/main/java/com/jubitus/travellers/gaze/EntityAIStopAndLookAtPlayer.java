package com.jubitus.travellers.gaze;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.travel.EntityAITravel;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;

/**
 * A player who stays close makes a walking or idle traveller stop and face them, for as long as they
 * stay. Movement only: the head is the gaze's ({@link TravellerGaze.Kind#INTEREST}). It only ever
 * pauses plain travel or idling - a camp, a fishing break, a conversation, a fight keep going and the
 * player just gets glances.
 */
public class EntityAIStopAndLookAtPlayer extends EntityAIBase {

    private static final double LEAVE_MARGIN = 1.5D;
    private static final int LOST_SIGHT_TICKS = 40;

    private final EntityTraveller traveller;

    @Nullable
    private EntityPlayer candidate;
    private long candidateSince;
    @Nullable
    private EntityPlayer target;
    private int unseenTicks;

    public EntityAIStopAndLookAtPlayer(EntityTraveller traveller) {
        this.traveller = traveller;
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (traveller.world.isRemote || !canPauseNow()) {
            candidate = null;
            return false;
        }
        EntityPlayer p = traveller.world.getClosestPlayerToEntity(traveller, TravellersConfig.GAZE.stopForPlayerDistance);
        if (p == null || p.isSpectator() || !traveller.getEntitySenses().canSee(p)) {
            candidate = null;
            return false;
        }
        long now = traveller.world.getTotalWorldTime();
        if (p != candidate) {
            candidate = p;
            candidateSince = now;
        }
        // Someone just walking past only gets a glance; someone who stays, a stop.
        if (now - candidateSince < TravellersConfig.GAZE.stopForPlayerDelaySeconds * 20.0D) return false;
        target = p;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (target == null || !target.isEntityAlive() || target.isSpectator() || !canPauseNow()) return false;
        double r = TravellersConfig.GAZE.stopForPlayerDistance + LEAVE_MARGIN;
        if (traveller.getDistanceSq(target) > r * r) return false;
        unseenTicks = traveller.getEntitySenses().canSee(target) ? 0 : unseenTicks + 1;
        return unseenTicks < LOST_SIGHT_TICKS;
    }

    @Override
    public void startExecuting() {
        unseenTicks = 0;
        traveller.getNavigator().clearPath();
        traveller.getGaze().focus(TravellerGaze.Kind.INTEREST, target);
        traveller.getGreeter().greet(target);
    }

    @Override
    public void updateTask() {
        if (!traveller.getNavigator().noPath()) traveller.getNavigator().clearPath();
        traveller.getGaze().focus(TravellerGaze.Kind.INTEREST, target);
    }

    @Override
    public void resetTask() {
        target = null;
        candidate = null;
        traveller.getGaze().clearFocus(TravellerGaze.Kind.INTEREST);
    }

    /** Only plain travel or idling is paused; anything else the traveller is busy with goes on. */
    private boolean canPauseNow() {
        if (traveller.sleep.isSleeping() || traveller.isInCombat() || traveller.isAutoEating()) return false;
        if (traveller.hasPlayerPassenger() || traveller.isVisitingVillage()) return false;
        // A group member stops with its leader, not on its own; a catching-up follower keeps going.
        if (traveller.getGroupLeaderUUID() != null || traveller.isCatchupTravel()) return false;
        // Pausing resets the travel task, which would undo a crossing, a flight or a terrain dismount.
        if (traveller.isElytraFlying() || traveller.getRidingEntity() instanceof EntityBoat) return false;
        if (traveller.getLedFallbackSinceTick() >= 0) return false;
        if (traveller instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) traveller;
            if (w.isChoirFreezeActive()) return false;
            if (w.isHiredFollower() && (!w.isJobActive() || w.isOnDuty())) return false;
        }
        EntityAIBase moving = traveller.getRunningMovementTask();
        return moving == null || moving == this || moving instanceof EntityAITravel;
    }
}
