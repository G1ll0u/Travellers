package com.jubitus.travellers.follow;

import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.pathing.GapJumpEvaluator;
import com.jubitus.travellers.pathing.GapJumpResult;
import com.jubitus.travellers.pathing.PathBody;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Follow-specific steering decisions on top of {@link DirectSteering}: slot stops, leader trail, shared corridor. */
final class FollowSteering {

    private final EntityAIFollowLeader ai;
    private final EntityTraveller follower;
    final DirectSteering direct;

    private static final double DIRECT_STEER_STOP_NEAR_SLOT_SQ = 1.35D * 1.35D;
    private static final double DIRECT_STEER_STOP_WORKER_LEADER_SQ = 1.75D * 1.75D;

    FollowSteering(EntityAIFollowLeader ai, EntityTraveller follower) {
        this.ai = ai;
        this.follower = follower;
        this.direct = new DirectSteering(follower);
    }

    void reset() {
        direct.reset();
    }

    void applyNonPathSteering(GoalCandidate goal,
                                      EntityLivingBase target,
                                      double moveSpeed,
                                      boolean airborneLeader,
                                      FollowMode mode) {
        double verticalGap = goal.y - follower.posY;
        double horizToGoalSq = FollowMath.distanceSq2D(follower.posX, follower.posZ, goal.x, goal.z);
        double horizToGoal = Math.sqrt(horizToGoalSq);

        boolean leaderStill = Math.abs(target.posX - target.prevPosX) < 0.05D
                && Math.abs(target.posZ - target.prevPosZ) < 0.05D;

        // The leader itself is swimming (not just standing at a shore) — water ahead is the
        // expected medium to cross, not an obstacle to steer away from.
        boolean waterIsSafe = ai.isLeaderSwimming(target);

        if (isCloseEnoughToStopSteering(goal, target)) {
            follower.getNavigator().clearPath();
            direct.steeringBlockedTicks = 0;
            return;
        }

        double toGoalDx = goal.x - follower.posX;
        double toGoalDz = goal.z - follower.posZ;
        double toGoalDistSq = toGoalDx * toGoalDx + toGoalDz * toGoalDz;
        double toGoalDist = Math.sqrt(toGoalDistSq);

        if (toGoalDist < 1.0E-4D) {
            follower.getNavigator().clearPath();
            direct.steeringBlockedTicks = 0;
            return;
        }

        // Checked BEFORE the hazard/deflection heuristics below, not after: those are sized from the
        // follower's own sub-1-block hitbox (see their own comment further down), so for a mounted body
        // they routinely find "a hazard-free direction" and report the tick as handled - resetting
        // steeringBlockedTicks - while the horse is demonstrably wedged against something none of them
        // can see. A trace caught exactly this: mount:wedged=1 steady for dozens of samples in a row
        // while follow:blocked stayed at 0 the whole time, because the deflection heuristic kept
        // claiming success first every single tick and this check never got a turn. Real physics is
        // the only reliable witness for a mounted body, so it must win outright, not merely be
        // consulted once the rider-sized heuristics happen to agree nothing is wrong.
        if (TravellerMountSupport.isMountWedged(follower)) {
            int blocked = direct.steeringBlockedTicks + 1;
            // INVARIANT: a wedged horse keeps being pushed, or the wedge flickers off and sidestep/jump never fire.
            steerTo(goal.x, goal.y, goal.z, Math.max(0.20D, moveSpeed * 0.90D));
            direct.steeringBlockedTicks = blocked;
            return;
        }

        // Straight line to the goal is blocked (cliff, ravine, water bank, etc). Rather than
        // stalling at the edge, try a local detour first, then a nearby groupmate's found path,
        // before giving up and letting the stuck-recovery/escalation timers take over.
        //
        // A hazard that is actually a jumpable gap must NOT be deflected around or stalled at:
        // keep steering straight toward it instead, so the follower closes the remaining
        // distance to the edge and the reactive gap-jump below (and every tick via
        // TravellerNavigateGround) fires once it's actually close enough. Without this check,
        // isHazardAhead's early-warning lookahead flags the gap well before the entity is close
        // enough for GapJumpEvaluator's own edge-adjacent check to confirm it — so the deflection
        // logic used to run first every time, routing followers around/away from a gap they could
        // have simply jumped, and in a crowded group that's what kept shoving followers off the
        // edge into it instead. Only a genuinely unjumpable hazard (too wide, lava, no safe
        // landing) still falls through to deflection/corridor-follow/stop.
        if (direct.isHazardAhead(follower.posX, follower.posZ, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist, waterIsSafe)
                && !direct.isJumpableGapAhead(follower.posX, follower.posY, follower.posZ, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist)) {
            boolean handled = direct.tryObstacleDeflection(goal.y, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist, moveSpeed, waterIsSafe)
                    || tryFollowSharedCorridor(target, goal, moveSpeed);

            if (handled) {
                // A hazard-free direction existing is not proof it's actually useful - along a
                // riverbank/cliff edge, deflection can keep finding a direction that merely runs
                // parallel to the obstacle forever, never making real headway. Only treat that as
                // "handled" (reset the escalation counter) when it's also produced real progress
                // toward the goal; otherwise keep counting so a genuinely stuck follower still
                // escalates to real pathfinding (the only thing that can actually route across
                // water/around the obstacle) instead of oscillating at the edge indefinitely.
                direct.steeringBlockedTicks = ai.stuck.progressedTowardGoalLastTick ? 0 : direct.steeringBlockedTicks + 1;
                return;
            }

            direct.steeringBlockedTicks++;
            follower.getNavigator().clearPath();
            return;
        }
        // No blanket counter reset here. Reaching this point only means the rider-sized hazard
        // probes saw nothing - for a mounted body the real verdict is steerDirect's corridor test
        // inside steerTo, and resetting before it runs is what pinned the counter in a 0->1->0
        // oscillation forever: a trace caught a horse halted 16 ticks straight against a tree,
        // follow:blocked=1 on every sample, never escalating to real pathfinding. steerTo itself
        // now settles the counter both ways (success resets, refusal counts).

        if (ai.idleAtSlot) {
            follower.getNavigator().clearPath();
            return;
        }

        if (horizToGoal <= 0.85D && !target.isSprinting() && !slotLikeLeaderMovement(target)) {
            follower.getNavigator().clearPath();
            return;
        }

        BlockPos steerFeet = new BlockPos(goal.x, goal.y, goal.z);
        if (direct.isHazardousStandPos(steerFeet, waterIsSafe)) {
            follower.getNavigator().clearPath();
            return;
        }

        double clampedSpeed = Math.max(0.20D, moveSpeed * 0.90D);

        if (airborneLeader) {
            if (horizToGoal > 1.8D) {
                if (!direct.tryGapJump(goal.x, goal.z, clampedSpeed)) {
                    steerTo(goal.x, follower.posY, goal.z, clampedSpeed);
                }
            } else {
                follower.getNavigator().clearPath();
            }
            return;
        }

        if (mode == FollowMode.CAVE && Math.abs(follower.posY - target.posY) <= 3.0D) {
            double s = Math.max(0.20D, moveSpeed * 0.92D);
            if (!direct.tryGapJump(goal.x, goal.z, s)) {
                steerTo(goal.x, goal.y, goal.z, s);
            }
            return;
        }

        if (verticalGap > 1.25D) {
            if (horizToGoal > 2.0D) {
                double s = Math.max(0.20D, moveSpeed * 0.84D);
                if (!direct.tryGapJump(goal.x, goal.z, s)) {
                    steerTo(goal.x, follower.posY, goal.z, s);
                }
            } else if (!leaderStill) {
                double s = Math.max(0.20D, moveSpeed * 0.78D);
                if (!direct.tryGapJump(goal.x, goal.z, s)) {
                    steerTo(goal.x, follower.posY, goal.z, s);
                }
            } else {
                follower.getNavigator().clearPath();
            }
            return;
        }

        if (!direct.tryGapJump(goal.x, goal.z, clampedSpeed)) {
            steerTo(goal.x, goal.y, goal.z, clampedSpeed);
        }
    }

    private void steerTo(double x, double y, double z, double speed) {
        direct.steerTo(x, y, z, speed, this::tryFollowLeaderTrail);
    }

    /**
     * Last resort before counting the tick as blocked: aim at a point on the leader's own recent
     * trail ({@link EntityTraveller#leadTrailPointBehind}) instead of probing a fresh line the
     * follower has no reason to believe is any better than the one that just failed. The leader
     * proved that ground passable a few ticks ago - retracing it is far more likely to succeed than
     * another blind straight shot, and costs nothing beyond the corridor check the leg-capped steer
     * already does. Only meaningful for a leader that is itself an {@code EntityTraveller} recording
     * a trail (see {@code EntityTraveller.onLivingUpdate} - a bonded, mounted leader always is).
     */
    boolean tryFollowLeaderTrail(TravellerNavigateGround nav, double legBlocks, double speed) {
        double distToLeader = ai.horizontalDistanceTo(ai.followTarget);
        Vec3d trailPoint;
        if (ai.followTarget instanceof EntityTraveller) {
            trailPoint = ((EntityTraveller) ai.followTarget).mountState.leadTrailPointBehind(distToLeader);
        } else if (ai.followTarget instanceof net.minecraft.entity.player.EntityPlayer) {
            trailPoint = PlayerLeaderTrail.pointBehind(ai.followTarget.getUniqueID(), distToLeader);
        } else {
            return false;
        }
        if (trailPoint == null) return false;
        return nav.steerDirectLegCapped(trailPoint.x, trailPoint.z, legBlocks, speed);
    }

    private boolean slotLikeLeaderMovement(EntityLivingBase target) {
        return Math.abs(target.posX - target.prevPosX) > 0.05D
                || Math.abs(target.posZ - target.prevPosZ) > 0.05D;
    }

    private boolean isCloseEnoughToStopSteering(GoalCandidate goal, EntityLivingBase target) {
        double dx = goal.x - follower.posX;
        double dz = goal.z - follower.posZ;
        double distSq = dx * dx + dz * dz;

        double stopSq = (target instanceof EntityTravellerWorker)
                ? DIRECT_STEER_STOP_WORKER_LEADER_SQ
                : DIRECT_STEER_STOP_NEAR_SLOT_SQ;

        return distSq <= stopSq;
    }

    /**
     * Last resort before stalling: reuse a nearby groupmate's recently found real path around
     * the same obstacle, if one exists and is still fresh.
     */
    private boolean tryFollowSharedCorridor(EntityLivingBase target, GoalCandidate goal, double moveSpeed) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (!wf.sharedRouteCorridorEnabled) return false;

        double bearingToGoal = Math.atan2(goal.z - follower.posZ, goal.x - follower.posX);
        Vec3d waypoint = FollowRouteCache.findNearestUsableWaypoint(
                follower.world,
                target,
                follower.posX,
                follower.posZ,
                bearingToGoal,
                wf.sharedRouteCorridorMaxSnapDistance,
                wf.sharedRouteCorridorTtlTicks
        );

        if (waypoint == null) return false;

        follower.getMoveHelper().setMoveTo(waypoint.x, waypoint.y, waypoint.z, moveSpeed);
        return true;
    }
}
