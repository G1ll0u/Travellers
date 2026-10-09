package com.jubitus.travellers.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;

/** How fast a follower moves toward its goal (catch-up boosts, mounted taper, smoothing). */
final class FollowSpeedPolicy {

    private final EntityAIFollowLeader ai;
    private final EntityTraveller follower;

    double smoothedMoveSpeed;

    FollowSpeedPolicy(EntityAIFollowLeader ai, EntityTraveller follower) {
        this.ai = ai;
        this.follower = follower;
    }

    double computeMoveSpeed(EntityLivingBase target,
                                    FollowFormationCoordinator.SlotAssignment slot,
                                    double distToLeader,
                                    double distToGoal,
                                    FollowMode mode,
                                    boolean airborneLeader) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        double catchUp = wf.catchUpSpeedBoost;

        double targetMove = slot.leaderSpeed;
        double speed = ai.baseSpeed;

        if (target.isSneaking()) speed *= 0.88D;
        if (target.isSprinting()) speed *= wf.sprintSpeedBoost;

        if (slot.isEscort && slot.leaderActuallyMoving) speed *= 1.0D + 0.10D * catchUp;

        if (targetMove > 0.08D) speed *= 1.0D + 0.08D * catchUp;
        if (targetMove > 0.14D) speed *= 1.0D + 0.10D * catchUp;

        // Horseback naturally runs farther behind its leader/slot between checks than walking does -
        // without widening these, a mounted follower reads its own larger cruising gap as "falling
        // behind" almost continuously and stays boosted toward maxSpeedMultiplier nearly all the
        // time, which is what "they go way too fast" actually was.
        boolean mounted = ai.isMounted();
        double mountScale = mounted ? wf.mountedCatchUpDistanceScale : 1.0D;

        if (distToGoal > 3.5D * mountScale) speed *= 1.0D + 0.08D * catchUp;
        if (distToGoal > 6.0D * mountScale) speed *= 1.0D + 0.12D * catchUp;
        if (distToGoal > 9.0D * mountScale) speed *= 1.0D + 0.15D * catchUp;

        if (distToLeader > 14.0D * mountScale) speed *= 1.0D + 0.10D * catchUp;
        if (distToLeader > 24.0D * mountScale) speed *= 1.0D + 0.15D * catchUp;

        if (mode == FollowMode.CAVE) speed *= 1.03D;
        if (airborneLeader) speed *= 1.04D;
        if (ai.followTarget instanceof EntityTravellerWorker) speed *= 1.02D;

        // Taper toward the slot instead of riding in at full speed and skidding past it - a horse's
        // momentum doesn't cancel the instant the follower settles (closeEnoughToSlot clears the path
        // outright), so without this a mounted follower visibly overshoots and snaps back on arrival.
        if (mounted && distToGoal < wf.mountedArrivalDecelRadius) {
            speed *= MathHelper.clamp(distToGoal / wf.mountedArrivalDecelRadius, 0.35D, 1.0D);
        }

        speed = MathHelper.clamp(speed, wf.minSpeedMultiplier, wf.maxSpeedMultiplier);
        smoothedMoveSpeed = smoothedMoveSpeed * 0.76D + speed * 0.24D;
        return MathHelper.clamp(smoothedMoveSpeed, wf.minSpeedMultiplier, wf.maxSpeedMultiplier);
    }
}
