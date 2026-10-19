package com.jubitus.travellers.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.UnitSpeed;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;

/** How fast a follower moves toward its goal (catch-up boosts, mounted taper, smoothing). */
final class FollowSpeedPolicy {

    private final EntityAIFollowLeader ai;
    private final EntityTraveller follower;

    /** A follower never crawls, however slowly its leader dawdles. */
    private static final double MIN_FOLLOW_PERCENT = 35.0D;

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
        boolean mounted = ai.isMounted();

        // Adapt to the leader: walk at the leader's measured pace (travel pace when it is standing),
        // and only use the margin up to followMaxPercent to close a gap.
        double percent = UnitSpeed.travelPercent();
        if (slot.leaderActuallyMoving) {
            percent = Math.max(MIN_FOLLOW_PERCENT, UnitSpeed.percentOfBlocksPerTick(slot.leaderSpeed));
        }
        // A leader slowing for its group: matching its pace would never close the gap it is waiting on.
        if (GroupFormationTracker.isLeaderPacing(target.getUniqueID(), follower.world.getTotalWorldTime())) {
            percent = Math.max(percent, mounted ? TravellersConfig.SPEED.mountedPercent : UnitSpeed.travelPercent());
        }

        // Horseback naturally runs farther behind its leader/slot between checks than walking does -
        // without widening these, a mounted follower reads its own larger cruising gap as "falling
        // behind" almost continuously and stays boosted nearly all the time.
        double mountScale = mounted ? wf.mountedCatchUpDistanceScale : 1.0D;

        if (distToGoal > 3.5D * mountScale) percent *= 1.0D + 0.08D * catchUp;
        if (distToGoal > 6.0D * mountScale) percent *= 1.0D + 0.12D * catchUp;
        if (distToGoal > 9.0D * mountScale) percent *= 1.0D + 0.15D * catchUp;

        if (distToLeader > 14.0D * mountScale) percent *= 1.0D + 0.10D * catchUp;
        if (distToLeader > 24.0D * mountScale) percent *= 1.0D + 0.15D * catchUp;

        if (mode == FollowMode.CAVE) percent *= 1.03D;
        if (airborneLeader) percent *= 1.04D;

        // Taper toward the slot instead of riding in at full speed and skidding past it - a horse's
        // momentum doesn't cancel the instant the follower settles (closeEnoughToSlot clears the path
        // outright), so without this a mounted follower visibly overshoots and snaps back on arrival.
        if (mounted && distToGoal < wf.mountedArrivalDecelRadius) {
            percent *= MathHelper.clamp(distToGoal / wf.mountedArrivalDecelRadius, 0.35D, 1.0D);
        }

        percent = MathHelper.clamp(percent, MIN_FOLLOW_PERCENT, UnitSpeed.followMaxPercent(mounted));
        // The horse multiplies the rider's own pace: ask the rider for the share it does not provide.
        if (mounted) percent /= UnitSpeed.mountedFactor();

        double speed = UnitSpeed.nav(percent);
        smoothedMoveSpeed = smoothedMoveSpeed * 0.76D + speed * 0.24D;
        return smoothedMoveSpeed;
    }
}
