package com.jubitus.travellers.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.pathing.GapJumpEvaluator;
import com.jubitus.travellers.pathing.GapJumpResult;
import com.jubitus.travellers.pathing.PathBody;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/** Direct steering of one unit without A*: hazard probes (lava, drops, water), deflection, gap jumps, the move-helper hand-off. Shared by follow and combat. */
public final class DirectSteering {

    /** Extra attempt for a mounted body whose straight leg was refused; true when it steered. */
    interface MountedFallback {
        boolean steer(TravellerNavigateGround nav, double legBlocks, double speed);
    }

    /**
     * One tick of straight steering toward a point, never into a hazard (lava, drop, water, wall): deflects
     * around it, jumps a clearable gap, or halts the unit and counts a blocked tick. False when blocked.
     */
    public boolean steerToward(double x, double y, double z, double speed) {
        double dx = x - follower.posX;
        double dz = z - follower.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-4D) return true;
        double dirX = dx / dist;
        double dirZ = dz / dist;

        if (isHazardAhead(follower.posX, follower.posZ, dirX, dirZ, dist, false)
                && !isJumpableGapAhead(follower.posX, follower.posY, follower.posZ, dirX, dirZ, dist)) {
            // INVARIANT: a deflection counts as a blocked tick, or a long riverbank would never escalate to a path.
            steeringBlockedTicks++;
            if (tryObstacleDeflection(y, dirX, dirZ, dist, speed, false)) return true;
            // The move helper keeps walking toward its last target; park it so the unit cannot slide into the hazard.
            follower.getNavigator().clearPath();
            follower.getMoveHelper().setMoveTo(follower.posX, follower.posY, follower.posZ, 0.0D);
            return false;
        }
        if (!tryGapJump(x, z, speed)) steerTo(x, y, z, speed, null);
        return true;
    }

    private static final int HAZARD_LOOKAHEAD_STEPS = 3;
    private static final double HAZARD_LOOKAHEAD_STEP = 0.85D;
    private static final int EDGE_DANGER_DEPTH = 2;

    /** Consecutive ticks the straight line to the goal has been hazard-blocked with no usable detour. */
    public int steeringBlockedTicks;
    private int deflectionRecheckCooldown;
    private double cachedDeflectDirX;
    private double cachedDeflectDirZ;
    private boolean hasCachedDeflectDir;

    private final EntityTraveller follower;

    public DirectSteering(EntityTraveller follower) {
        this.follower = follower;
    }

    public void reset() {
        steeringBlockedTicks = 0;
        deflectionRecheckCooldown = 0;
        hasCachedDeflectDir = false;
    }

    /** The single way this task hands a direct steering target to the move helper. While mounted,
     * {@code getMoveHelper()} is the horse's own, so this steers the horse directly. */
    void steerTo(double x, double y, double z, double speed, MountedFallback fallback) {
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
                steered = fallback != null && fallback.steer(nav, legBlocks, speed);
            }
            steeringBlockedTicks = steered ? 0 : steeringBlockedTicks + 1;
            return;
        }
        // On foot the last couple of blocks need no corridor (fine slot placement); beyond, a wall
        // must count as blocked or the follower walks into it forever without ever asking for a path.
        double dx = x - follower.posX;
        double dz = z - follower.posZ;
        if (dx * dx + dz * dz > ON_FOOT_FREE_STEER_SQ && follower.getNavigator() instanceof TravellerNavigateGround) {
            TravellerNavigateGround nav = (TravellerNavigateGround) follower.getNavigator();
            double legBlocks = TravellersConfig.MOUNTS.mountedDirectLegBlocks;
            boolean steered = nav.steerAround(x, z, legBlocks, speed)
                    || (fallback != null && fallback.steer(nav, legBlocks, speed));
            steeringBlockedTicks = steered ? 0 : steeringBlockedTicks + 1;
            return;
        }
        steeringBlockedTicks = 0;
        follower.getMoveHelper().setMoveTo(x, y, z, speed);
    }

    private static final double ON_FOOT_FREE_STEER_SQ = 2.0D * 2.0D;

    boolean tryGapJump(double goalX, double goalZ, double speed) {
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

    /** A hazard ({@link BlockRules#isHazard}), or vanilla water when the follower must stay dry. */
    private static boolean isUnsafeBlock(IBlockState s, boolean waterIsSafe) {
        return BlockRules.isHazard(s) || (!waterIsSafe && BlockRules.isWater(s));
    }

    boolean isHazardousStandPos(BlockPos feet, boolean waterIsSafe) {
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

    boolean isHazardAhead(double startX, double startZ, double dirX, double dirZ,
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
    boolean isJumpableGapAhead(double startX, double startY, double startZ,
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

    /**
     * Tries a small fan of directions around the direct line to the goal, looking for one that
     * isn't hazard-blocked, and steers toward a local point along it instead of the literal goal.
     * Cheap: no pathfinding, just the same block-column hazard scan already used for the direct line.
     */
    boolean tryObstacleDeflection(double goalY, double dirX, double dirZ, double dist,
                                          double moveSpeed, boolean waterIsSafe) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (!wf.steeringObstacleAvoidanceEnabled) return false;

        double probeDist = Math.min(dist, HAZARD_LOOKAHEAD_STEP * HAZARD_LOOKAHEAD_STEPS);

        if (deflectionRecheckCooldown > 0) {
            deflectionRecheckCooldown--;
            if (hasCachedDeflectDir
                    && !isHazardAhead(follower.posX, follower.posZ, cachedDeflectDirX, cachedDeflectDirZ, probeDist, waterIsSafe)) {
                steerTowardLocalPoint(goalY, cachedDeflectDirX, cachedDeflectDirZ, probeDist, moveSpeed);
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
                    steerTowardLocalPoint(goalY, ndx, ndz, probeDist, moveSpeed);
                    return true;
                }
            }
        }

        hasCachedDeflectDir = false;
        return false;
    }

    private void steerTowardLocalPoint(double goalY, double dirX, double dirZ, double probeDist, double moveSpeed) {
        double px = follower.posX + dirX * probeDist;
        double pz = follower.posZ + dirZ * probeDist;
        follower.getMoveHelper().setMoveTo(px, goalY, pz, moveSpeed);
    }
}
