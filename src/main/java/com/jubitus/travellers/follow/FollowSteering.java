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

/** Direct steering without A*: hazard probes, deflection, shared corridor, gap jumps, the leader's trail. */
final class FollowSteering {

    private final EntityAIFollowLeader ai;
    private final EntityTraveller follower;

    private static final double DIRECT_STEER_STOP_NEAR_SLOT_SQ = 1.35D * 1.35D;
    private static final double DIRECT_STEER_STOP_WORKER_LEADER_SQ = 1.75D * 1.75D;

    private static final int HAZARD_LOOKAHEAD_STEPS = 3;
    private static final double HAZARD_LOOKAHEAD_STEP = 0.85D;
    private static final int EDGE_DANGER_DEPTH = 2;

    /** Consecutive ticks the straight line to the goal has been hazard-blocked with no usable detour. */
    int steeringBlockedTicks;
    private int deflectionRecheckCooldown;
    private double cachedDeflectDirX;
    private double cachedDeflectDirZ;
    private boolean hasCachedDeflectDir;

    FollowSteering(EntityAIFollowLeader ai, EntityTraveller follower) {
        this.ai = ai;
        this.follower = follower;
    }

    void reset() {
        steeringBlockedTicks = 0;
        deflectionRecheckCooldown = 0;
        hasCachedDeflectDir = false;
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
            steeringBlockedTicks = 0;
            return;
        }

        double toGoalDx = goal.x - follower.posX;
        double toGoalDz = goal.z - follower.posZ;
        double toGoalDistSq = toGoalDx * toGoalDx + toGoalDz * toGoalDz;
        double toGoalDist = Math.sqrt(toGoalDistSq);

        if (toGoalDist < 1.0E-4D) {
            follower.getNavigator().clearPath();
            steeringBlockedTicks = 0;
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
            int blocked = steeringBlockedTicks + 1;
            // INVARIANT: a wedged horse keeps being pushed, or the wedge flickers off and sidestep/jump never fire.
            steerTo(goal.x, goal.y, goal.z, Math.max(0.20D, moveSpeed * 0.90D));
            steeringBlockedTicks = blocked;
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
        if (isHazardAhead(follower.posX, follower.posZ, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist, waterIsSafe)
                && !isJumpableGapAhead(follower.posX, follower.posY, follower.posZ, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist)) {
            boolean handled = tryObstacleDeflection(goal, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist, moveSpeed, waterIsSafe)
                    || tryFollowSharedCorridor(target, goal, moveSpeed);

            if (handled) {
                // A hazard-free direction existing is not proof it's actually useful - along a
                // riverbank/cliff edge, deflection can keep finding a direction that merely runs
                // parallel to the obstacle forever, never making real headway. Only treat that as
                // "handled" (reset the escalation counter) when it's also produced real progress
                // toward the goal; otherwise keep counting so a genuinely stuck follower still
                // escalates to real pathfinding (the only thing that can actually route across
                // water/around the obstacle) instead of oscillating at the edge indefinitely.
                steeringBlockedTicks = ai.stuck.progressedTowardGoalLastTick ? 0 : steeringBlockedTicks + 1;
                return;
            }

            steeringBlockedTicks++;
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
        if (isHazardousStandPos(steerFeet, waterIsSafe)) {
            follower.getNavigator().clearPath();
            return;
        }

        double clampedSpeed = Math.max(0.20D, moveSpeed * 0.90D);

        if (airborneLeader) {
            if (horizToGoal > 1.8D) {
                if (!tryGapJump(goal.x, goal.z, clampedSpeed)) {
                    steerTo(goal.x, follower.posY, goal.z, clampedSpeed);
                }
            } else {
                follower.getNavigator().clearPath();
            }
            return;
        }

        if (mode == FollowMode.CAVE && Math.abs(follower.posY - target.posY) <= 3.0D) {
            double s = Math.max(0.20D, moveSpeed * 0.92D);
            if (!tryGapJump(goal.x, goal.z, s)) {
                steerTo(goal.x, goal.y, goal.z, s);
            }
            return;
        }

        if (verticalGap > 1.25D) {
            if (horizToGoal > 2.0D) {
                double s = Math.max(0.20D, moveSpeed * 0.84D);
                if (!tryGapJump(goal.x, goal.z, s)) {
                    steerTo(goal.x, follower.posY, goal.z, s);
                }
            } else if (!leaderStill) {
                double s = Math.max(0.20D, moveSpeed * 0.78D);
                if (!tryGapJump(goal.x, goal.z, s)) {
                    steerTo(goal.x, follower.posY, goal.z, s);
                }
            } else {
                follower.getNavigator().clearPath();
            }
            return;
        }

        if (!tryGapJump(goal.x, goal.z, clampedSpeed)) {
            steerTo(goal.x, goal.y, goal.z, clampedSpeed);
        }
    }

    /** The single way this task hands a direct steering target to the move helper. While mounted,
     * {@code getMoveHelper()} is the horse's own, so this steers the horse directly. */
    private void steerTo(double x, double y, double z, double speed) {
        // A mounted body's direct steer must be proven against the horse's real corridor first -
        // every hazard probe upstream of here is sized from the rider's sub-1-block hitbox and
        // happily approves a line between two trees the horse cannot fit through. steerDirect runs
        // the same footprint-sized corridor test the planner and travel AI use (one clearance model,
        // see MountTerrainProfile), commands its own halt on refusal, and a refusal counts as a
        // blocked tick so the escalation to real pathfinding still fires instead of the horse being
        // re-aimed at the same impassable line every tick.
        if (PathBody.isMounted(follower) && follower.getNavigator() instanceof TravellerNavigateGround) {
            TravellerNavigateGround nav = (TravellerNavigateGround) follower.getNavigator();
            double legBlocks = TravellersConfig.MOUNTS.mountedDirectLegBlocks;
            boolean steered = nav.steerDirectLegCapped(x, z, legBlocks, speed);
            if (!steered) {
                // The straight leg toward the slot can fail on rolling terrain even though the
                // leader itself just rode through here - retrace its own proven trail before
                // escalating to a real search. Same idea as a led horse following its rider's
                // footsteps, applied to a mounted group leader instead.
                steered = tryFollowLeaderTrail(nav, legBlocks, speed);
            }
            steeringBlockedTicks = steered ? 0 : steeringBlockedTicks + 1;
            return;
        }
        steeringBlockedTicks = 0;
        follower.getMoveHelper().setMoveTo(x, y, z, speed);
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

    private boolean tryGapJump(double goalX, double goalZ, double speed) {
        PathNavigate nav = follower.getNavigator();
        if (!(nav instanceof TravellerNavigateGround)) return false;

        TravellerNavigateGround groundNav = (TravellerNavigateGround) nav;
        if (groundNav.offerGapJump(goalX, goalZ, speed)) return true;

        // A jump is fully validated and ready to fire, only withheld by its own cooldown - hold
        // in place (report "handled" so every caller's own setMoveTo fallback is skipped) instead
        // of walking toward a goal across the gap, which on a small landing (a narrow pillar, a
        // tight obstacle course) would step the follower clean off the edge and into the gap it
        // hasn't been allowed to jump yet.
        if (groundNav.isJumpPendingCooldown(goalX, goalZ)) {
            follower.getMoveHelper().setMoveTo(follower.posX, follower.posY, follower.posZ, 0.0D);
            return true;
        }
        return false;
    }

    private boolean slotLikeLeaderMovement(EntityLivingBase target) {
        return Math.abs(target.posX - target.prevPosX) > 0.05D
                || Math.abs(target.posZ - target.prevPosZ) > 0.05D;
    }

    /** A hazard ({@link BlockRules#isHazard}), or vanilla water when the follower must stay dry. */
    private static boolean isUnsafeBlock(IBlockState s, boolean waterIsSafe) {
        return BlockRules.isHazard(s) || (!waterIsSafe && BlockRules.isWater(s));
    }

    private boolean isHazardousStandPos(BlockPos feet, boolean waterIsSafe) {
        World world = follower.world;
        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.down()) || !world.isBlockLoaded(feet.up())) {
            return true;
        }

        IBlockState at = world.getBlockState(feet);
        IBlockState below = world.getBlockState(feet.down());
        IBlockState up = world.getBlockState(feet.up());

        if (isUnsafeBlock(at, waterIsSafe) || isUnsafeBlock(below, waterIsSafe)) return true;

        if (!(at.getMaterial().isReplaceable() || at.getCollisionBoundingBox(world, feet) == null)) return true;
        if (!(up.getMaterial().isReplaceable() || up.getCollisionBoundingBox(world, feet.up()) == null)) return true;

        if (!waterIsSafe && below.getMaterial().isLiquid()) return true;
        if (below.getCollisionBoundingBox(world, feet.down()) == null && !below.getMaterial().isLiquid()) return true;

        if (isDropOrUnsafeEdge(feet, EDGE_DANGER_DEPTH, waterIsSafe)) return true;

        return false;
    }

    private boolean isHazardAhead(double startX, double startZ, double dirX, double dirZ,
                                  double maxDist, boolean waterIsSafe) {
        int steps = Math.min(HAZARD_LOOKAHEAD_STEPS, Math.max(1, (int) Math.ceil(maxDist / HAZARD_LOOKAHEAD_STEP)));
        double sampleY = follower.getEntityBoundingBox().minY;

        for (int i = 1; i <= steps; i++) {
            double t = i * HAZARD_LOOKAHEAD_STEP;
            double sx = startX + dirX * t;
            double sz = startZ + dirZ * t;

            BlockPos feet = new BlockPos(sx, sampleY, sz);
            if (isHazardousApproachPos(feet, waterIsSafe)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Same lookahead cadence as {@link #isHazardAhead}, but asks GapJumpEvaluator whether a real
     * jump would clear the ground ahead once the entity actually reaches it, instead of just
     * flagging "hazard, avoid". GapJumpEvaluator itself only evaluates from directly at the edge
     * (its cx is "the current column"), so this walks the same sample points forward and asks
     * "if I were standing here, would this be a jumpable gap" for each — using the entity's
     * current Y throughout, the same flat-approach assumption isHazardAhead already makes with
     * its own fixed sampleY.
     */
    private boolean isJumpableGapAhead(double startX, double startY, double startZ,
                                       double dirX, double dirZ, double maxDist) {
        World world = follower.world;
        long worldTime = world.getTotalWorldTime();
        int cy = MathHelper.floor(startY + 0.5D);

        int steps = Math.min(HAZARD_LOOKAHEAD_STEPS, Math.max(1, (int) Math.ceil(maxDist / HAZARD_LOOKAHEAD_STEP)));
        for (int i = 1; i <= steps; i++) {
            double t = i * HAZARD_LOOKAHEAD_STEP;
            int cx = MathHelper.floor(startX + dirX * t);
            int cz = MathHelper.floor(startZ + dirZ * t);

            GapJumpResult gap = GapJumpEvaluator.evaluate(world, worldTime, cx, cy, cz, dirX, dirZ);
            if (gap.type == GapJumpResult.Type.JUMPABLE) return true;
            if (gap.type == GapJumpResult.Type.UNJUMPABLE) return false; // real gap, can't clear it
            // NONE: no gap at this sample point yet (still solid ground) - keep scanning forward
        }
        return false;
    }

    private boolean isHazardousApproachPos(BlockPos feet, boolean waterIsSafe) {
        World world = follower.world;

        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.down()) || !world.isBlockLoaded(feet.up())) {
            return true;
        }

        IBlockState at = world.getBlockState(feet);
        IBlockState up = world.getBlockState(feet.up());
        IBlockState below = world.getBlockState(feet.down());

        // Water is hazardous to a dry-land follower (avoid wandering into a lake for no reason),
        // but not to one already trailing a swimming leader — it needs to enter water to keep up.
        if (isUnsafeBlock(at, waterIsSafe) || isUnsafeBlock(below, waterIsSafe)) return true;

        if (!(at.getMaterial().isReplaceable() || at.getCollisionBoundingBox(world, feet) == null)) {
            // Allow 1-block step-ups (climbable slope). If the position one block up is walkable
            // and not a drop, this is just terrain rising — not a real navigation hazard.
            if (!isClimbableStepUp(world, feet, waterIsSafe)) return true;
        }

        if (!(up.getMaterial().isReplaceable() || up.getCollisionBoundingBox(world, feet.up()) == null)) return true;

        if (isDropOrUnsafeEdge(feet, EDGE_DANGER_DEPTH, waterIsSafe)) return true;

        return false;
    }

    private boolean isClimbableStepUp(World world, BlockPos feet, boolean waterIsSafe) {
        BlockPos feetUp = feet.up();
        if (!world.isBlockLoaded(feetUp) || !world.isBlockLoaded(feetUp.up())) return false;
        IBlockState atUp = world.getBlockState(feetUp);
        IBlockState headUp = world.getBlockState(feetUp.up());
        boolean feetUpClear = atUp.getMaterial().isReplaceable() || atUp.getCollisionBoundingBox(world, feetUp) == null;
        boolean headUpClear = headUp.getMaterial().isReplaceable() || headUp.getCollisionBoundingBox(world, feetUp.up()) == null;
        return feetUpClear && headUpClear && !isDropOrUnsafeEdge(feetUp, EDGE_DANGER_DEPTH, waterIsSafe);
    }

    private boolean isDropOrUnsafeEdge(BlockPos feet, int maxDepth, boolean waterIsSafe) {
        World world = follower.world;

        for (int d = 1; d <= maxDepth; d++) {
            BlockPos check = feet.down(d);
            if (!world.isBlockLoaded(check)) {
                return true;
            }

            IBlockState state = world.getBlockState(check);

            if (isUnsafeBlock(state, waterIsSafe)) {
                return true;
            }

            BlockFaceShape shape = state.getBlockFaceShape(world, check, EnumFacing.UP);
            if (shape == BlockFaceShape.SOLID && !state.getMaterial().isLiquid()) {
                return false;
            }

            // Open water below a swimming follower's step is the normal medium, not a drop —
            // there is no "floor" concept to require while already swimming.
            if (waterIsSafe && state.getMaterial().isLiquid()) {
                return false;
            }
        }

        return true;
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
     * Tries a small fan of directions around the direct line to the goal, looking for one that
     * isn't hazard-blocked, and steers toward a local point along it instead of the literal goal.
     * Cheap: no pathfinding, just the same block-column hazard scan already used for the direct line.
     */
    private boolean tryObstacleDeflection(GoalCandidate goal, double dirX, double dirZ, double dist,
                                          double moveSpeed, boolean waterIsSafe) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (!wf.steeringObstacleAvoidanceEnabled) return false;

        double probeDist = Math.min(dist, HAZARD_LOOKAHEAD_STEP * HAZARD_LOOKAHEAD_STEPS);

        if (deflectionRecheckCooldown > 0) {
            deflectionRecheckCooldown--;
            if (hasCachedDeflectDir
                    && !isHazardAhead(follower.posX, follower.posZ, cachedDeflectDirX, cachedDeflectDirZ, probeDist, waterIsSafe)) {
                steerTowardLocalPoint(goal, cachedDeflectDirX, cachedDeflectDirZ, probeDist, moveSpeed);
                return true;
            }
        }

        double baseAngle = Math.atan2(dirZ, dirX);
        double stepRad = Math.toRadians(wf.steeringDeflectionStepDegrees);
        double maxRad = Math.toRadians(wf.steeringMaxDeflectionDegrees);

        for (double offset = stepRad; offset <= maxRad + 1.0E-6D; offset += stepRad) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double angle = baseAngle + sign * offset;
                double ndx = Math.cos(angle);
                double ndz = Math.sin(angle);

                if (!isHazardAhead(follower.posX, follower.posZ, ndx, ndz, probeDist, waterIsSafe)) {
                    cachedDeflectDirX = ndx;
                    cachedDeflectDirZ = ndz;
                    hasCachedDeflectDir = true;
                    deflectionRecheckCooldown = wf.steeringDeflectionRecheckIntervalTicks;
                    steerTowardLocalPoint(goal, ndx, ndz, probeDist, moveSpeed);
                    return true;
                }
            }
        }

        hasCachedDeflectDir = false;
        return false;
    }

    private void steerTowardLocalPoint(GoalCandidate goal, double dirX, double dirZ, double probeDist, double moveSpeed) {
        double px = follower.posX + dirX * probeDist;
        double pz = follower.posZ + dirZ * probeDist;
        follower.getMoveHelper().setMoveTo(px, goal.y, pz, moveSpeed);
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
