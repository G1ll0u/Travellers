package com.jubitus.travellers.follow;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;

/** A follower's stuck evidence (stagnation, failed paths, orbit) and its cave-only rescue. */
final class FollowStuckSignals {

    private final EntityAIFollowLeader ai;
    private final EntityTraveller follower;

    int pathFailTicks;
    int failedAllowedPathTicks;
    int schedulerDeniedTicks;
    int recentPathSuccessTicks;
    int activePathTicks;
    int recoveryCooldown;

    boolean hasProgressAnchor;

    /**
     * Best (smallest) squared distance-to-goal seen since the anchor was last reset. Stagnation is
     * judged against this rather than raw per-tick movement - see {@link #tickStuckSignals}.
     */
    private double bestGoalDistSq = Double.MAX_VALUE;
    private static final double PROGRESS_EPSILON_SQ = 0.3D * 0.3D;

    /**
     * Whether the follower made real progress toward its goal on the most recent
     * {@link #tickStuckSignals} pass. Used by {@link FollowSteering#applyNonPathSteering} to decide whether an
     * obstacle deflection is actually working, as opposed to merely being possible - see its own
     * comment for why "a hazard-free direction exists" alone is not a safe signal to reset on.
     */
    boolean progressedTowardGoalLastTick = true;

    int orbitTicks;
    private double lastOrbitAngle;
    private boolean hasOrbitAngle;

    FollowStuckSignals(EntityAIFollowLeader ai, EntityTraveller follower) {
        this.ai = ai;
        this.follower = follower;
    }

    /** Ticks spent wanting to move without entering a new cell; lives in the entity's shared stuck record. */
    int stagnationTicks() {
        return follower.getStuckRecord().followStall();
    }

    void tickStuckSignals(GoalCandidate goal) {
        boolean hasPath = !follower.getNavigator().noPath();
        if (hasPath) activePathTicks++;
        else if (activePathTicks > 0) activePathTicks--;

        double goalDistSq = FollowMath.distanceSq(follower.posX, follower.posY, follower.posZ, goal.x, goal.y, goal.z);

        if (!hasProgressAnchor) {
            bestGoalDistSq = goalDistSq;
            hasProgressAnchor = true;
            progressedTowardGoalLastTick = true;
            return;
        }

        boolean tryingToMove = !ai.idleAtSlot && (hasPath || goalDistSq > 4.0D * 4.0D);

        // Local steering signal only: closing on the current goal this tick.
        boolean progressed = goalDistSq < bestGoalDistSq - PROGRESS_EPSILON_SQ;
        if (progressed) {
            bestGoalDistSq = goalDistSq;
        }
        progressedTowardGoalLastTick = progressed;

        // Stagnation is the entity's shared stuck evidence (new cells, see StuckRecord): jostling or
        // pacing never fakes it, and it survives target changes and follow/travel hand-offs.
        follower.getStuckRecord().tickFollow(follower.posX, follower.posY, follower.posZ, tryingToMove);

        if (!tryingToMove && failedAllowedPathTicks > 0) {
            failedAllowedPathTicks--;
        }
    }

    void tickOrbitDetection(GoalCandidate goal, FollowMode mode, FollowRuntimeConfig cfg) {
        double dx = follower.posX - goal.x;
        double dz = follower.posZ - goal.z;
        double distSq = dx * dx + dz * dz;

        if (distSq < 1.8D || distSq > 16.0D || ai.idleAtSlot || ai.settledTicks >= EntityAIFollowLeader.SETTLED_FAST_PATH_TICKS) {
            orbitTicks = 0;
            hasOrbitAngle = false;
            return;
        }

        double angle = Math.atan2(dz, dx);

        if (!hasOrbitAngle) {
            lastOrbitAngle = angle;
            hasOrbitAngle = true;
            orbitTicks = 0;
            return;
        }

        double delta = FollowMath.wrapToPi(angle - lastOrbitAngle);
        lastOrbitAngle = angle;

        int trigger = mode == FollowMode.CAVE ? cfg.cave.orbitTriggerTicks : 50;

        if (Math.abs(delta) > 0.10D && stagnationTicks() > 10) {
            orbitTicks++;
        } else if (orbitTicks > 0) {
            orbitTicks--;
        }

        if (orbitTicks > trigger + 20) {
            orbitTicks = trigger + 20;
        }
    }

    boolean tryProgressiveRecovery(EntityLivingBase target, FollowMode mode, FollowRuntimeConfig cfg) {
        if (ai.currentGoal == null) return false;
        if (recoveryCooldown > 0) return false;

        // Outside caves a stuck follower is handed to catchup travel (see updateTask): one ladder, one hop.
        if (mode != FollowMode.CAVE) return false;

        if (schedulerDeniedTicks > 0) return false;
        if (recentPathSuccessTicks > 0) return false;

        boolean visible = canSeeTarget(target);

        boolean outsideVsUnderground = FollowRecoveryHelper.isWorkerOutsideWhileLeaderUnderground(follower, target);

        // Surface workers with underground leader: shorter grace, relaxed thresholds.
        // They can never get activePathTicks (underground goal unreachable from surface),
        // so the normal condition would never trigger without this bypass.
        int graceTicks = outsideVsUnderground ? 15 : cfg.rescue.caveModeGraceTicks;
        if (ai.modeTicks < graceTicks) {
            return false;
        }

        if (!isHardCaveRescueCase(target, ai.currentGoal, cfg)) {
            return false;
        }

        int minStagnation = cfg.rescue.caveMinStagnationTicks;
        int minFailed = cfg.rescue.caveMinFailedPathTicks;

        if (outsideVsUnderground) {
            // Faster thresholds: stagnation builds quickly since the path always fails,
            // and activePathTicks can never reach 20 from the surface.
            minStagnation = 50;
            minFailed = 16;
        } else if (visible) {
            minStagnation += cfg.rescue.visibleExtraStagnationTicks;
            minFailed += cfg.rescue.visibleExtraFailedPathTicks;
        }

        boolean caveCase =
                stagnationTicks() >= minStagnation
                        && failedAllowedPathTicks >= minFailed
                        && (activePathTicks >= cfg.rescue.minActivePathTicks || outsideVsUnderground);

        if (caveCase) {
            boolean rescued = FollowRecoveryHelper.tryCaveFollowRescue(
                    follower,
                    target,
                    ai.currentGoal.x,
                    ai.currentGoal.y,
                    ai.currentGoal.z,
                    cfg
            );

            if (rescued) {
                com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(follower, com.jubitus.travellers.debug.profile.MovementAnomaly.FOLLOW_CAVE_RESCUE);
                onTeleportRecovery(cfg);
                return true;
            }
        }

        return false;
    }

    private boolean isHardCaveRescueCase(EntityLivingBase target,
                                         GoalCandidate goal,
                                         FollowRuntimeConfig cfg) {
        if (FollowRecoveryHelper.shouldSuppressCaveRescue(
                follower,
                target,
                goal.x,
                goal.y,
                goal.z,
                cfg)) {
            return false;
        }

        boolean outsideVsUnderground = FollowRecoveryHelper.isWorkerOutsideWhileLeaderUnderground(follower, target);

        boolean aboveLeader =
                FollowRecoveryHelper.isFollowerRoughlyAboveLeader(
                        follower,
                        target,
                        cfg.rescue.caveMaxHorizontal,
                        cfg.rescue.caveMinVerticalAbove
                );

        boolean extremeFailure =
                failedAllowedPathTicks >= cfg.rescue.caveRequireSamePocketFailTicks
                        && stagnationTicks() >= cfg.rescue.caveRequireSamePocketStagnationTicks
                        && activePathTicks >= cfg.rescue.minActivePathTicks * 2;

        return outsideVsUnderground || aboveLeader || extremeFailure;
    }

    private void onTeleportRecovery(FollowRuntimeConfig cfg) {
        follower.getStuckRecord().reset();
        resetEvidence();
        resetOrbit();
        ai.paths.repathCooldown = 12;
        recoveryCooldown = cfg.rescue.cooldownTicks;
        ai.dropGoals();
        ai.leaveIdle();
        ai.steering.reset();
    }

    /** Clears the stuck evidence earned so far (not the recovery cooldown). */
    void resetEvidence() {
        pathFailTicks = 0;
        failedAllowedPathTicks = 0;
        activePathTicks = 0;
        recentPathSuccessTicks = 0;
        schedulerDeniedTicks = 0;
        hasProgressAnchor = false;
        progressedTowardGoalLastTick = true;
    }

    void resetOrbit() {
        orbitTicks = 0;
        hasOrbitAngle = false;
    }

    private boolean canSeeTarget(EntityLivingBase target) {
        return follower.canEntityBeSeen(target) || target.canEntityBeSeen(follower);
    }
}
