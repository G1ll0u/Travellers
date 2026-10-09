package com.jubitus.travellers.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.PathNavigate;
import javax.annotation.Nullable;

/** Real path requests for a follower: priority, scheduler budget, cooldowns, path reuse, partial routes. */
final class FollowPathRequester {

    private final EntityAIFollowLeader ai;
    private final EntityTraveller follower;

    private double activePathGoalX;
    private double activePathGoalY;
    private double activePathGoalZ;
    boolean hasActivePathGoal;
    private static final int SCHEDULER_DENIAL_BLOCK_TELEPORT_TICKS = 60;
    private static final int RECENT_PATH_SUCCESS_BLOCK_TELEPORT_TICKS = 80;
    int repathCooldown;

    FollowPathRequester(EntityAIFollowLeader ai, EntityTraveller follower) {
        this.ai = ai;
        this.follower = follower;
    }

    /** Asks for a real path to the goal (budgeted), records the outcome and sets the next repath cooldown. */
    void requestPath(EntityLivingBase target, GoalCandidate goal, FollowFormationCoordinator.SlotAssignment slot,
                     FollowMode mode, boolean noPath, double distToGoal, double distToLeader, double moveSpeed,
                     boolean airborneLeader) {
        int priority = getPathPriority(noPath, distToGoal, distToLeader, mode, slot);

        PathScheduler.AcquireResult acquire =
                PathScheduler.tryAcquireDetailed(follower.world, target, follower.getEntityId(), priority,
                        Math.max(1, slot.groupSize), ai.isMounted());

        boolean moved = false;

        if (acquire == PathScheduler.AcquireResult.GRANTED) {
            if (shouldReuseCurrentPath(goal)) {
                moved = true;
            } else {
                moved = follower.getNavigator().tryMoveToXYZ(goal.x, goal.y, goal.z, moveSpeed);
            }

            if (moved && !pathEndsNear(follower.getNavigator().getPath(), goal)) {
                // Vanilla's best-effort partial route (e.g. to the foot of a pit wall): walk it, but it
                // is still a failure for the rescue counters.
                ai.stuck.failedAllowedPathTicks += 4;
                ai.stuck.pathFailTicks++;
                markActivePathGoal(goal);
            } else if (moved) {
                ai.stuck.recentPathSuccessTicks = RECENT_PATH_SUCCESS_BLOCK_TELEPORT_TICKS;
                ai.stuck.failedAllowedPathTicks = 0;
                ai.stuck.pathFailTicks = 0;
                ai.steering.steeringBlockedTicks = 0;
                markActivePathGoal(goal);

                if (TravellersConfig.WORKER_FORMATION.sharedRouteCorridorEnabled) {
                    FollowRouteCache.record(follower.world, target, follower.getNavigator().getPath());
                }
            } else {
                ai.stuck.failedAllowedPathTicks += 4;
                ai.stuck.pathFailTicks++;
                hasActivePathGoal = false;
                // The A* search itself found no route this tick (e.g. this follower's own lateral
                // offset doesn't line up with a valid crossing, or the search budget ran out on a
                // detour) - fall back to direct steering instead of leaving the entity with no
                // active goal at all. This is what actually jumps the gap for a follower whose
                // pathfind attempt failed: applyNonPathSteering's own isJumpableGapAhead check
                // takes over from here. Without this, a follower in this exact situation just sat
                // idle with stale motion while the rest of a large group pushed it toward the edge.
                ai.steering.applyNonPathSteering(goal, target, moveSpeed, airborneLeader, mode);
            }
        } else {
            if (PathScheduler.wasSchedulerDenial(acquire)) {
                ai.stuck.schedulerDeniedTicks = Math.max(ai.stuck.schedulerDeniedTicks, SCHEDULER_DENIAL_BLOCK_TELEPORT_TICKS);

                // A denial alone isn't "stuck" - the scheduler is just busy this tick and normally
                // grants it soon. But direct steering ALSO already failing for a while (steeringBlockedTicks
                // past its own escalation grace) means this follower currently has no working way to
                // move at all, not merely an unlucky scheduling tick. pathFailTicks/failedAllowedPathTicks
                // otherwise only ever advance from a GRANTED-but-failed search (the branch above) - under
                // sustained global-budget contention (many mounted followers competing for
                // pathSchedulerMountedGlobalMaxPathsPerTick) a follower can be denied every single tick
                // forever, in which case those counters never move and neither tryProgressiveRecovery's
                // pit-rescue nor (for workers) FollowModeSelector's near-slot CATCHUP escalation - both
                // gated on them - can ever trigger, regardless of how long it waits. Treating a
                // steering-also-failing denial like a failed search closes that gap.
                if (ai.steering.steeringBlockedTicks >= TravellersConfig.WORKER_FORMATION.steeringBlockedEscalationGraceTicks) {
                    ai.stuck.failedAllowedPathTicks += 4;
                    ai.stuck.pathFailTicks++;
                }
            }
            hasActivePathGoal = false;
            ai.steering.applyNonPathSteering(goal, target, moveSpeed, airborneLeader, mode);
        }

        if (moved) {
            repathCooldown = getRepathInterval(slot, priority, true, mode);
        } else {
            if (acquire != PathScheduler.AcquireResult.GRANTED) {
                repathCooldown = getDeniedRetryCooldown(acquire, distToLeader, distToGoal, priority);
            } else {
                repathCooldown = getRepathInterval(slot, priority, false, mode);
            }
        }

        follower.setSprinting(false);
    }

    private int getPathPriority(boolean noPath,
                                double distToGoal,
                                double distToLeader,
                                FollowMode mode,
                                FollowFormationCoordinator.SlotAssignment slot) {
        double dy = ai.followTarget == null ? 0.0D : Math.abs(follower.posY - ai.followTarget.posY);

        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (wf.steeringBlockedFastEscalationEnabled
                && ai.steering.steeringBlockedTicks >= wf.steeringBlockedEscalationGraceTicks) {
            return 2;
        }

        if (ai.stuck.pathFailTicks >= 6) return 2;
        if (mode == FollowMode.CAVE && dy >= 6.0D) return 2;
        if (mode == FollowMode.SURFACE && dy >= 10.0D) return 1;
        if (noPath && distToGoal > 4.0D && distToLeader > 10.0D) return 2;
        if (distToLeader >= 34.0D) return 2;
        if (distToLeader >= 22.0D) return 1;
        if (distToLeader >= 14.0D && distToGoal >= 4.5D) return 1;
        if (noPath && distToLeader >= 10.0D) return 1;
        if (slot.tunnelStrength >= 0.45D && distToGoal > 3.0D) return 1;

        if (isLeaderMovingFast(wf, slot)) return Math.max(0, wf.fastLeaderMinPriority);
        return 0;
    }

    // Escorts already re-path every 2-3 ticks regardless of speed (getRepathInterval), so this
    // only matters for formation followers, whose slots are farther out and see bigger per-tick
    // goal shifts while the leader moves fast (sprinting player, or a follower leader traveling) —
    // covers both, unlike checking EntityPlayer.isSprinting() alone.
    private boolean isLeaderMovingFast(TravellersConfig.WorkerFormation wf,
                                       FollowFormationCoordinator.SlotAssignment slot) {
        return wf.fastLeaderPriorityBoostEnabled
                && !slot.isEscort
                && slot.leaderActuallyMoving
                && slot.leaderSpeed >= wf.fastLeaderSpeedThreshold;
    }

    private int getDeniedRetryCooldown(PathScheduler.AcquireResult acquire,
                                       double distToLeader,
                                       double distToGoal,
                                       int priority) {
        int base;

        if (acquire == PathScheduler.AcquireResult.DENIED_SPREAD) base = 2;
        else if (acquire == PathScheduler.AcquireResult.DENIED_OWNER_TOKENS) base = 3;
        else if (acquire == PathScheduler.AcquireResult.DENIED_GLOBAL_LIMIT) base = 4;
        else base = 4;

        if (priority >= 2 || distToLeader >= 28.0D) {
            base = Math.max(1, base - 2);
        } else if (priority == 1 || distToLeader >= 18.0D) {
            base = Math.max(2, base - 1);
        }

        if (distToGoal <= 3.0D && distToLeader <= 10.0D) {
            base += 1;
        }

        return base + Math.abs((follower.getEntityId() * 11) % 2);
    }

    private int getRepathInterval(FollowFormationCoordinator.SlotAssignment slot,
                                  int priority,
                                  boolean pathSucceeded,
                                  FollowMode mode) {
        if (slot.isEscort) {
            // Escorts re-path aggressively to stay glued to the leader.
            int base = pathSucceeded ? 3 : 2;
            return base + Math.abs((follower.getEntityId() * 13) % 2);
        }

        int base = slot.groupSize >= 100 ? 34 : (slot.groupSize >= 60 ? 28 : (slot.groupSize >= 30 ? 22 : 16));

        if (slot.compactness < 0.8D) base += 2;
        if (mode == FollowMode.CAVE) base = Math.max(5, base - 1);
        if (slot.tunnelStrength >= 0.45D) base = Math.max(4, base - 1);

        if (priority >= 2) base = Math.max(4, base - 2);
        else if (priority == 1) base = Math.max(5, base - 1);

        if (!pathSucceeded) base = Math.max(4, base - 1);

        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (isLeaderMovingFast(wf, slot)) {
            base = Math.min(base, wf.fastLeaderRepathIntervalCapTicks);
        }

        return base + Math.abs((follower.getEntityId() * 13) % 3);
    }

    boolean shouldReuseCurrentPath(GoalCandidate goal) {
        PathNavigate nav = follower.getNavigator();
        if (nav == null || nav.noPath()) return false;
        if (!hasActivePathGoal) return false;

        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;

        double goalShiftSq = FollowMath.distanceSq(
                goal.x, goal.y, goal.z,
                activePathGoalX, activePathGoalY, activePathGoalZ
        );

        if (goalShiftSq > wf.pathReuseGoalShiftMaxBlocks * wf.pathReuseGoalShiftMaxBlocks) {
            return false;
        }

        double workerToGoalSq = FollowMath.distanceSq(
                follower.posX, follower.posY, follower.posZ,
                goal.x, goal.y, goal.z
        );

        return workerToGoalSq <= wf.pathReuseWorkerToGoalMaxBlocks * wf.pathReuseWorkerToGoalMaxBlocks;
    }

    private static boolean pathEndsNear(@Nullable net.minecraft.pathfinding.Path path, GoalCandidate goal) {
        if (path == null) return false;
        net.minecraft.pathfinding.PathPoint end = path.getFinalPathPoint();
        if (end == null) return false;
        double dx = end.x + 0.5D - goal.x;
        double dz = end.z + 0.5D - goal.z;
        return dx * dx + dz * dz <= TravellerNavigateGround.PARTIAL_PATH_TOLERANCE * TravellerNavigateGround.PARTIAL_PATH_TOLERANCE
                && Math.abs(end.y - goal.y) <= TravellerNavigateGround.PARTIAL_PATH_TOLERANCE;
    }

    private void markActivePathGoal(GoalCandidate goal) {
        activePathGoalX = goal.x;
        activePathGoalY = goal.y;
        activePathGoalZ = goal.z;
        hasActivePathGoal = true;
    }
}
