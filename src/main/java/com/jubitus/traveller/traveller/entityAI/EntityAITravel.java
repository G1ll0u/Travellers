package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.Random;

public class EntityAITravel extends EntityAIBase {

    private static final int NO_PATH_RESELECT = 20; // ~1s
    // Small detour headings, degrees
    private static final int[] DETOUR_ANGLES = new int[]{-60, 60, -30, 30, -90, 90, 120, -120};
    private static final int STALL_T1 = 6 * 20;   // ~6s no progress → widen detours
    private static final int STALL_T2 = 12 * 20;  // ~12s → wall-follow mode
    private static final int STALL_T3 = 20 * 20;  // ~20s → local escape scan
    private static final double PROJ_STEP_OK = 0.8;   // ~0.8 block of forward progress counts
    // Escalating scan radii (≈ blocks)
    private static final int ESCAPE_SCAN_L0 = 48;
    private static final int ESCAPE_SCAN_L1 = 64;
    private static final int ESCAPE_SCAN_L2 = 96;
    private static final int ESCAPE_SCAN_L3 = 128;
    // --- Progress-aware hard-unstuck gates ---
    private static final int HARD_UNSTUCK_MIN_STALL = 2;     // don't TP for light stalls
    private static final int HARD_UNSTUCK_T_NOBEST = 12 * 20; // ≥12s since best distance improved
    private static final int HARD_UNSTUCK_T_NOPROJ = 6 * 20;  // ≥6s without forward component
    private static final double HARD_UNSTUCK_MOVE_SQ = 0.20 * 0.20; // barely moved (~0.2 block)
    private static final int HARD_UNSTUCK_COOLDOWN = 10 * 20; // ≥10s between TPs
    private static final int HARD_UNSTUCK_MAX_PER_TRIP = 3;   // safety cap
    private static final int POST_RECOVER_GUARD = 80; // ~4s no-backtrack after combat
    private final EntityTraveller mob;
    private final int stepBlocks;
    private final double reachDistSq;
    private final int recalcTicks;
    private final int stuckCheckTicks;
    private double speed;
    private BlockPos currentWaypoint;
    private BlockPos targetVillage;
    private boolean arriveInside = false;
    // Stuck detection
    private Vec3d lastPos = Vec3d.ZERO;
    private int noPathTicks = 0;
    // progress tracking/escalation
    private double bestDistSq = Double.MAX_VALUE;
    private int ticksSinceBest = 0;
    private int stallLevel = 0; // 0 normal, 1 wide detour, 2 wall-follow, 3 local escape scan
    private boolean wallFollow = false;
    private int followSide = 1; // +1 = right hand, -1 = left handc
    // directional progress
    private Vec3d lastProgressPos = Vec3d.ZERO;
    private double projAccum = 0.0;
    private boolean resetAfterCombatDone = false;

    // near-radius cache for the current target
    private int targetNearSq = 0;
    private int hardUnstuckCooldown = 0;
    private int hardUnstuckCount = 0;
    private int ticksNoForward = 0;            // time since we last accumulated forward progress
    private Vec3d lastTPProgressAnchor = Vec3d.ZERO; // anchor to measure "are we orbiting?"
    // --- Post-combat guard against backtracking ---
    private int postRecoverGuardTicks = 0;

    public EntityAITravel(EntityTraveller mob, double speed, int stepBlocks) {
        this.mob = mob;
        this.speed = speed;
        this.stepBlocks = Math.max(8, stepBlocks);
        this.reachDistSq = 3.0 * 3.0;
        this.recalcTicks = 12;
        this.stuckCheckTicks = 60;
        this.setMutexBits(1 | 2); // MOVE (1) + LOOK (2) so nothing else steals the head while travelling
    }


    /**
     * True if the block at `pos` can support standing on top (accepts ice).
     */
    private static boolean supportsStandingSurface(World w, BlockPos pos, IBlockState st) {
        // guard again for safety (some callers pass in computed states, but be strict)
        if (!w.isBlockLoaded(pos)) return false;

        if (st.getMaterial().isLiquid()) return false;

        BlockFaceShape shape = st.getBlockFaceShape(w, pos, EnumFacing.UP);
        if (shape == BlockFaceShape.SOLID) return true;

        AxisAlignedBB bb = st.getBoundingBox(w, pos);
        if (bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001) return true;

        Block b = st.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.FROSTED_ICE
                || b == Blocks.GLASS || b == Blocks.STAINED_GLASS;
    }

    public void setSpeed(double s) {
        this.speed = s;
    }

    @Override
    public boolean shouldExecute() {
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;
        if (mob.isAtTargetStop() || mob.isAtTargetStopHys(false)) return false; // already inside
        BlockPos tv = mob.getTargetVillage();
        if (tv == null) return false;
        retarget(tv);
        return !mob.isAtTargetStop(); // ← unified
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;
        if (mob.isAtTargetStop() || mob.isAtTargetStopHys(false)) {
            mob.getNavigator().clearPath();
            return false;                           // yield to Roam
        }
        BlockPos tv = mob.getTargetVillage();
        if (tv == null) return false;
        if (!tv.equals(this.targetVillage)) retarget(tv);
        arriveInside = mob.isAtTargetStopHys(arriveInside);

        return !arriveInside;
    }

    // ---- helpers ----

    @Override
    public void startExecuting() {
        arriveInside = mob.isAtTargetStop();
        this.targetVillage = mob.getTargetVillage();
        if (this.targetVillage == null) return;
        if (mob.getDistanceSqToCenter(this.targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }
        this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
        mob.setCurrentWaypoint(this.currentWaypoint);
        pushPathToWaypoint();

        this.lastPos = mob.getPositionVector();
        this.noPathTicks = 0;

        this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
        this.ticksSinceBest = 0;
        this.stallLevel = 0;

        this.lastProgressPos = mob.getPositionVector();
        this.projAccum = 0.0;

    }

    @Override
    public void resetTask() {
        this.currentWaypoint = null;
        mob.setCurrentWaypoint(null);
        mob.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        if (hardUnstuckCooldown > 0) hardUnstuckCooldown--;
        if (projAccum >= 0.4) {
            ticksNoForward = 0; // made some forward component this tick window
        } else {
            ticksNoForward++;
        }
        if (mob.isAutoEating() || mob.isHandActive()) { // NEW: bail hard
            mob.getNavigator().clearPath();
            return;
        }
        if (targetVillage != null &&
                mob.getDistanceSqToCenter(targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }
        if (mob.isInCombat()) return;
        // --- post-combat recovery handling ---
        int recover = mob.getTravelRecoverTicks();
        if (recover > 0) {
            // one-shot reset of progress baselines so stall logic doesn't trigger
            if (!resetAfterCombatDone) {
                // refresh the live target in case the route advanced
                BlockPos liveTarget = mob.getTargetVillage();
                if (liveTarget != null && (!liveTarget.equals(this.targetVillage))) {
                    this.targetVillage = liveTarget;
                    this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
                    mob.setCurrentWaypoint(this.currentWaypoint);
                }
                this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
                this.ticksNoForward = 0;
                this.hardUnstuckCooldown = Math.max(hardUnstuckCooldown, 20); // brief grace
                this.hardUnstuckCount = 0; // optional: per-trip cap may reset on new leg
                this.lastTPProgressAnchor = mob.getPositionVector();
                this.ticksSinceBest = 0;
                this.stallLevel = 0;
                this.lastProgressPos = mob.getPositionVector();
                this.projAccum = 0.0;
                this.noPathTicks = 0;
                mob.getNavigator().clearPath();
                pushPathToWaypoint();
                resetAfterCombatDone = true;
                postRecoverGuardTicks = POST_RECOVER_GUARD; // forbid backward hops for a bit
            }
            if (postRecoverGuardTicks > 0) postRecoverGuardTicks--;
            // while recovering: just keep the path fresh, but DO NOT run stuck/escape logic
            if ((mob.ticksExisted % this.recalcTicks) == 0) {
                pushPathToWaypoint();
            }
            return; // skip the rest (stuck detection, detours, etc.)
        } else {
            resetAfterCombatDone = false; // ready for next time
        }
        BlockPos liveTarget = mob.getTargetVillage();
        if (liveTarget == null) return;

        if (!liveTarget.equals(this.targetVillage)) {
            // The route advanced (or was reassigned) -> retarget cleanly
            retarget(liveTarget);  // sets targetVillage and its per-type near radius
            this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
            mob.setCurrentWaypoint(this.currentWaypoint);

            // Reset progress trackers so stall logic doesn’t fight the new target
            this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
            this.ticksSinceBest = 0;
            this.stallLevel = 0;
            this.lastProgressPos = mob.getPositionVector();
            this.projAccum = 0.0;


            mob.getNavigator().clearPath();
            pushPathToWaypoint();
        }

// Reached current waypoint? pick next
        if (this.currentWaypoint != null &&
                mob.getDistanceSqToCenter(this.currentWaypoint) <= this.reachDistSq) {
            this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
            mob.setCurrentWaypoint(this.currentWaypoint);
        }

// classic distance-to-goal check (keep it)
        double d2 = mob.getDistanceSqToCenter(this.targetVillage);
        boolean distImproved = d2 < bestDistSq - 0.75; // ~0.86 blocks net

// NEW: directional progress (component toward the village)
// (XZ only)
        Vec3d pos = mob.getPositionVector();
        Vec3d goalXZ = new Vec3d(targetVillage.getX() + 0.5 - pos.x, 0, targetVillage.getZ() + 0.5 - pos.z);
        if (goalXZ.lengthSquared() > 1e-6) goalXZ = goalXZ.normalize();
        Vec3d deltaXZ = new Vec3d(pos.x - lastProgressPos.x, 0, pos.z - lastProgressPos.z);
        double forward = deltaXZ.dotProduct(goalXZ);
        if (forward > PROJ_STEP_OK) {
            projAccum += forward;
            this.lastProgressPos = pos;                // move the baseline so we measure fresh increments
        }

        if (distImproved || projAccum >= 2.5) {        // EITHER got closer OR made ~2.5 blocks of forward component
            bestDistSq = Math.min(bestDistSq, d2);
            this.lastTPProgressAnchor = mob.getPositionVector();
            this.ticksNoForward = 0;
            ticksSinceBest = 0;
            projAccum = 0.0;
            if (stallLevel > 0) stallLevel--;          // de-escalate on any real progress
        } else {
            ticksSinceBest++;
            if (stallLevel == 0 && ticksSinceBest > STALL_T1) stallLevel = 1;
            else if (stallLevel == 1 && ticksSinceBest > STALL_T2) stallLevel = 2;
            else if (stallLevel == 2 && ticksSinceBest > STALL_T3) stallLevel = 3;
        }


// ----- normal LAND logic below -----
        if ((mob.ticksExisted + mob.getRecalcPhase()) % this.recalcTicks == 0) {
            pushPathToWaypoint();
        }

        boolean hasPath = !mob.getNavigator().noPath();
        noPathTicks = hasPath ? 0 : (noPathTicks + 1);

        if (noPathTicks >= NO_PATH_RESELECT) {
            this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
            mob.setCurrentWaypoint(this.currentWaypoint);
            pushPathToWaypoint();
            noPathTicks = 0;
        }
// --- PROGRESS-AWARE LAST RESORT ---
// If we have not improved 'bestDistSq' for a while OR not accumulated forward projection,
// AND we have effectively not moved in world space, trigger hard TP nudge.
        if (stallLevel >= HARD_UNSTUCK_MIN_STALL &&
                hardUnstuckCooldown == 0 &&
                hardUnstuckCount < HARD_UNSTUCK_MAX_PER_TRIP) {

            // "no net approach" gate
            boolean longNoBest = (ticksSinceBest >= HARD_UNSTUCK_T_NOBEST);
            boolean longNoForward = (ticksNoForward >= HARD_UNSTUCK_T_NOPROJ);

            // "orbiting" / not moving gate (XZ-ish)
            Vec3d now = mob.getPositionVector();
            double movedSqSinceAnchor = now.squareDistanceTo(lastTPProgressAnchor);

            if ((longNoBest || longNoForward) && movedSqSinceAnchor <= HARD_UNSTUCK_MOVE_SQ) {
                if (hardTeleportNudgeTowardTarget()) {
                    // after successful TP, reset anchor so we don't immediately fire again
                    lastTPProgressAnchor = mob.getPositionVector();
                    // and skip the rest of the stuck logic this tick
                    return;
                } else {
                    // failed to find a landing; push anchor forward once to avoid spamming
                    lastTPProgressAnchor = now;
                }
            }
        }
        if (mob.ticksExisted % this.stuckCheckTicks == 0) {
            if (!mob.isAutoEating() && !mob.isHandActive()) {
                double movedSq = mob.getPositionVector().squareDistanceTo(this.lastPos);
                this.lastPos = mob.getPositionVector();

                if (movedSq < 0.6 * 0.6) {
                    // If we already escalated past T3 and still no progress for 5s → TP immediately
                    if (stallLevel >= 3 && ticksSinceBest > (STALL_T3 + 5 * 20) &&
                            hardUnstuckCooldown == 0 && hardUnstuckCount < HARD_UNSTUCK_MAX_PER_TRIP) {
                        if (hardTeleportNudgeTowardTarget()) {
                            lastTPProgressAnchor = mob.getPositionVector();
                            return;
                        }
                    }

                    // otherwise run your ladder...
                    if (stallLevel <= 1) {
                        tryDetourWide();
                    } else if (stallLevel == 2) {
                        enterOrStepWallFollow();
                    } else {
                        int scan = currentEscapeScanRadius();
                        if (localEscapeScan(scan)) {
                            pushPathToWaypoint();
                        } else if (macroLateralBypass()) {
                            pushPathToWaypoint();
                        } else {
                            tryDetour(); // tiny reseed fallback
                        }


                    }
                    mob.breakNearbyLeaves();

                }
            }
        }
    }

    private void retarget(BlockPos newTarget) {
        this.targetVillage = newTarget;
        int near = mob.arrivalRadiusBlocks(newTarget); // unified
        this.targetNearSq = near * near;
    }

    private void pushPathToWaypoint() {
        if (this.currentWaypoint == null) return;

        Path current = mob.getNavigator().getPath();
        if (current != null) {
            PathPoint dest = current.getFinalPathPoint();
            if (dest != null) {
                if (Math.abs(dest.x - currentWaypoint.getX()) <= 1 &&
                        Math.abs(dest.z - currentWaypoint.getZ()) <= 1) return;            }
        }

        // water
        double navSpeed = this.speed;
        mob.getNavigator().tryMoveToXYZ(
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY(),
                currentWaypoint.getZ() + 0.5,
                navSpeed
        );
    }

    private void onArrivedAtVillage() {
        // do NOT clear targetVillage here; route manager uses it to align with Roam
        this.currentWaypoint = null;
        mob.setCurrentWaypoint(null);
        mob.getNavigator().clearPath();

        // Let Roam start this tick
        // (Roam’s shouldExecute() will pass now because distance ≤ VILLAGE_NEAR)
    }

    /**
     * Compute a "next" waypoint toward 'dest' that is inside currently loaded chunks.
     * Does not force-load anything. Returns null if no safe waypoint is found.
     */
    private BlockPos computeNextWaypointToward(BlockPos dest, int baseStep) {
        BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(dest.getX() - from.getX(), 0, dest.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return null;

// 3a) apply a tiny per-entityAI heading bias
        double biasedYawRad = Math.toRadians(mob.getPathAngleBiasDeg());
        Vec3d dir = rotateY(to.normalize(), biasedYawRad);

// 3b) jitter the step size slightly
        int step = Math.min(baseStep + mob.getPathStepJitter(),
                (int) Math.sqrt(mob.getDistanceSqToCenter(dest)));
        step = Math.max(8, step);

// try straight candidates first (but they’re now slightly biased)
        for (int s = step; s >= 16; s -= 16) {
            // lane offset: a small perpendicular shift so paths don’t stack perfectly
            Vec3d left = new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getPathLaneOffset());
            BlockPos candidate = surfaceAtOffset(from, dir, s, left);
            if (candidate != null) return candidate;
        }

// detours (also from biased heading)
        int detStep = Math.max(12, step / 2);
        for (int angle : new int[]{-60, 60, -30, 30, -90, 90}) {
            Vec3d r = rotateY(dir, Math.toRadians(angle));
            Vec3d left = new Vec3d(-r.z, 0, r.x).normalize().scale(mob.getPathLaneOffset() * 0.6); // soften on detours
            BlockPos candidate = surfaceAtOffset(from, r, detStep, left);
            if (candidate != null) return candidate;
        }

        // tiny random reseed
        Random r = mob.getRNG();
        for (int i = 0; i < 6; i++) {
            int nx = from.getX() + r.nextInt(9) - 4;
            int nz = from.getZ() + r.nextInt(9) - 4;
            BlockPos surf = findSurfaceOrWaterY(new BlockPos(nx, from.getY(), nz));
            if (surf != null && isAreaLoaded(surf, 1) && isNavigable(surf)) return surf;
        }
        return null;
    }

    private BlockPos surfaceAtOffset(BlockPos from, Vec3d dir, int s, Vec3d lateral) {
        int nx = from.getX() + (int) Math.round(dir.x * s + lateral.x);
        int nz = from.getZ() + (int) Math.round(dir.z * s + lateral.z);
        BlockPos surface = findSurfaceOrWaterY(new BlockPos(nx, from.getY(), nz));
        return (surface != null && isAreaLoaded(surface, 1) && isNavigable(surface)) ? surface : null;
    }

    /**
     * Try small angular detours that remain within loaded chunks.
     */
    private void tryDetour() {
        if (this.targetVillage == null) return;

        BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return;

        Vec3d base = to.normalize();

        for (int angleDeg : DETOUR_ANGLES) {
            Vec3d dir = rotateY(base, Math.toRadians(angleDeg));
            int nx = from.getX() + (int) Math.round(dir.x * Math.max(12, this.stepBlocks / 2));
            int nz = from.getZ() + (int) Math.round(dir.z * Math.max(12, this.stepBlocks / 2));
            BlockPos surface = findSurfaceOrWaterY(new BlockPos(nx, from.getY(), nz));
            if (surface != null && isAreaLoaded(surface, 1) && isNavigable(surface)) {
                this.currentWaypoint = surface;
                pushPathToWaypoint();
                return;
            }
        }

        // Last resort: pick a tiny random step in loaded area
        Random r = mob.getRNG();
        for (int i = 0; i < 6; i++) {
            int nx = from.getX() + r.nextInt(9) - 4;
            int nz = from.getZ() + r.nextInt(9) - 4;
            BlockPos surface = findSurfaceOrWaterY(new BlockPos(nx, from.getY(), nz));
            if (surface != null && isAreaLoaded(surface, 1) && isNavigable(surface)) {
                this.currentWaypoint = surface;
                pushPathToWaypoint();
                return;
            }
        }
    }

    private Vec3d rotateY(Vec3d v, double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        return new Vec3d(v.x * cos - v.z * sin, 0, v.x * sin + v.z * cos);
    }

    /**
     * Find a reasonable Y at/near the given XZ by scanning down/up without loading chunks.
     */
    private BlockPos findSurfaceOrWaterY(BlockPos guess) {
        final World world = mob.world;
        if (!isAreaLoaded(guess, 1)) return null;

        // 1. Base sample (1.12: returns the top *solid or liquid* at XZ)
        final BlockPos top = world.getTopSolidOrLiquidBlock(guess);

        // Quick hit: often top is already good.
        if (isSwimmable(top) || isStandable(top)) return top;

        // 2. Small upward probe (bridges, slabs under ceilings, leaf canopies)
        final int actualMaxY = world.getActualHeight() - 1;
        BlockPos p = top;
        for (int i = 0; i < 3 && p.getY() < actualMaxY; i++) {
            p = p.up();
            // same chunk column; safe after the isAreaLoaded(guess,1) gate
            if (isSwimmable(p) || isStandable(p)) return p;
        }

        // 3. Deeper downward probe (valleys, shallow water tops, snow layers)
        p = top;
        for (int dy = 0; dy < 12 && p.getY() > 1; dy++) {
            p = p.down();
            if (isSwimmable(p) || isStandable(p)) return p;
        }

        return null;
    }


    /**
     * Chunk-safe "isAreaLoaded" that never loads chunks.
     */
    private boolean isAreaLoaded(BlockPos pos, int radius) {
        if (!(mob.world instanceof WorldServer)) return mob.world.isAreaLoaded(pos, radius);
        WorldServer ws = (WorldServer) mob.world;

        int minCX = (pos.getX() - radius) >> 4;
        int maxCX = (pos.getX() + radius) >> 4;
        int minCZ = (pos.getZ() - radius) >> 4;
        int maxCZ = (pos.getZ() + radius) >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!ws.getChunkProvider().chunkExists(cx, cz)) return false;
            }
        }
        return true;
    }

    private boolean isNavigable(BlockPos feet) {
        return isStandable(feet) || isSwimmable(feet);
    }

    /**
     * “Can the mob stand with feet at `feet`?” (land version)
     */
    private boolean isStandable(BlockPos feet) {
        World world = mob.world;

        // HARD guards
        if (!world.isBlockLoaded(feet)) return false;
        if (!world.isBlockLoaded(feet.up())) return false;
        if (!world.isBlockLoaded(feet.down())) return false;

        final IBlockState below = world.getBlockState(feet.down());
        final IBlockState atFeet = world.getBlockState(feet);
        final IBlockState atHead = world.getBlockState(feet.up());

        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) return false;
        if (!supportsStandingSurface(world, feet.down(), below)) return false;

        return atFeet.getMaterial().isReplaceable() && atHead.getMaterial().isReplaceable();
    }

    private boolean isSwimmable(BlockPos feet) {
        World world = mob.world;

        // HARD guards
        if (!world.isBlockLoaded(feet)) return false;
        if (!world.isBlockLoaded(feet.up())) return false;
        if (!world.isBlockLoaded(feet.down())) return false;

        IBlockState feetS = world.getBlockState(feet);
        IBlockState headS = world.getBlockState(feet.up());
        IBlockState belowS = world.getBlockState(feet.down());

        boolean feetWater = feetS.getMaterial() == net.minecraft.block.material.Material.WATER;
        boolean headOK = headS.getMaterial() == net.minecraft.block.material.Material.WATER
                || headS.getMaterial().isReplaceable();

        return feetWater && headOK && belowS.getMaterial() != net.minecraft.block.material.Material.LAVA;
    }

    private void tryDetourWide() {
        if (this.targetVillage == null) return;
        BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return;

        Vec3d base = to.normalize();
        int detStep = Math.max(24, this.stepBlocks);

        // Prefer forward-hemisphere first; allow deep-back only at max stall
        int[] ANG = (stallLevel >= 3)
                ? new int[]{120, -120, 150, -150, 180, 90, -90, 60, -60, 30, -30}
                : new int[]{120, -120, 90, -90, 60, -60, 30, -30};

        for (int angleDeg : ANG) {
            Vec3d dir = rotateY(base, Math.toRadians(angleDeg));
            BlockPos candidate = surfaceAtOffset(from, dir, detStep,
                    new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getPathLaneOffset() * 0.5));
            if (candidate != null && !segmentBlockedTwoHigh(from, candidate)) {
                this.currentWaypoint = candidate;
                pushPathToWaypoint();
                return;
            }
        }
    }

    private void enterOrStepWallFollow() {
        if (!wallFollow) {
            wallFollow = true;
            followSide = mob.getRNG().nextBoolean() ? 1 : -1;
            this.lastProgressPos = mob.getPositionVector(); // reset baseline
        }

        Vec3d pos = mob.getPositionVector();
        Vec3d goalDir = new Vec3d(targetVillage).add(0.5, 0, 0.5).subtract(pos).normalize();
        Vec3d delta = pos.subtract(this.lastProgressPos);
        double forward = delta.dotProduct(goalDir);

        // normal wall-follow stepping
        stepWallFollow(goalDir, followSide);

        if (forward > 0.2) {   // making headway in the right general direction
            ticksSinceBest = 0;
            this.lastProgressPos = pos;
        }
    }

    private void stepWallFollow(Vec3d forward, int side) {
        // Build the preferred order: turn toward wall (side), then forward, then away from wall.
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        Vec3d[] order = (side > 0)
                ? new Vec3d[]{right, forward, right.scale(-1)}
                : new Vec3d[]{right.scale(-1), forward, right};

        BlockPos from = mob.getPosition();
        int step = 6; // short, controlled steps along the perimeter
        for (Vec3d dir : order) {
            BlockPos cand = surfaceAtOffset(from, dir, step, Vec3d.ZERO);
            if (cand != null) {
                // sanity: ensure we’re not “through” a two-high blocker between from→cand
                if (!segmentBlockedTwoHigh(from, cand)) {
                    this.currentWaypoint = cand;
                    pushPathToWaypoint();
                    return;
                }
            }
        }

        // If all three failed, flip side once to try the other edge before giving up next tick
        followSide = -followSide;
    }

    private boolean segmentBlockedTwoHigh(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(1, (int)Math.ceil(Math.sqrt(dx*dx + dz*dz)) / 2); // half density
        double ax = a.getX() + 0.5, az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
        double stepx = (bx - ax) / steps, stepz = (bz - az) / steps;

        for (int i = 1; i <= steps; i++) {
            int x = (int) Math.floor(ax + stepx * i);
            int z = (int) Math.floor(az + stepz * i);
            BlockPos col = new BlockPos(x, a.getY(), z);
            if (!isAreaLoaded(col, 1)) return true; // treat unloaded as blocked

            BlockPos surf = findSurfaceOrWaterY(col);      // ← recompute proper feet Y here
            if (surf == null || !isNavigable(surf)) return true;
        }
        return false;
    }

    private boolean localEscapeScan(int radius) {
        radius = Math.max(radius, currentEscapeScanRadius());

        BlockPos from = mob.getPosition();
        Vec3d toDir = new Vec3d(
                targetVillage.getX() - from.getX(), 0,
                targetVillage.getZ() - from.getZ()
        ).normalize();

        int maxAngle = (stallLevel >= 2) ? 150 : 105;
        int stepAngle = 12;

        long distSqNow = (long) from.distanceSq(targetVillage);

        BlockPos best = null;
        double bestScore = -1e9;

        for (int a = -maxAngle; a <= maxAngle; a += stepAngle) {
            Vec3d dir = rotateY(toDir, Math.toRadians(a));
            for (int s = 8; s <= radius; s += 4) {
                BlockPos p = surfaceAtOffset(from, dir, s, Vec3d.ZERO);
                if (p == null) continue;
                if (segmentBlockedTwoHigh(from, p)) continue;
                if (!acceptCandidate(p, from, toDir, distSqNow)) continue;

                double sc = scoreCandidate(p, from, toDir, distSqNow, a);
                if (sc > bestScore) {
                    bestScore = sc;
                    best = p;
                }
            }
        }

        if (best != null) {
            this.currentWaypoint = best;
            return true;
        }
        return false;
    }

    // How much we're allowed to get "worse" (distance^2) when escaping
    private int backtrackAllowanceSq() {
        // Allow only modest regressions; mountains punish large backtracks
        switch (stallLevel) {
            case 0:
                return 4;     // ~2 blocks
            case 1:
                return 64;    // ~8 blocks
            case 2:
                return 256;   // ~16 blocks
            default:
                return 900;   // ~30 blocks (was 64^2=4096)
        }
    }

    /**
     * Escalate scan radius with stall level.
     */
    private int currentEscapeScanRadius() {
        switch (stallLevel) {
            case 1:
                return ESCAPE_SCAN_L1;
            case 2:
                return ESCAPE_SCAN_L2;
            case 3:
                return ESCAPE_SCAN_L3;
            default:
                return ESCAPE_SCAN_L0;
        }
    }

    /**
     * Big sidestep around obstacles: try lateral moves (±90°, also ±60/±120) at larger distances.
     */
    private boolean macroLateralBypass() {
        if (this.targetVillage == null) return false;

        BlockPos from = mob.getPosition();
        Vec3d toDir = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ()).normalize();

        int[] angles = new int[]{90, -90, 60, -60, 120, -120};
        int[] dists = new int[]{32, 48, 64, 80, 96};

        for (int ang : angles) {
            Vec3d dir = rotateY(toDir, Math.toRadians(ang));
            for (int s : dists) {
                BlockPos p = surfaceAtOffset(from, dir, s, Vec3d.ZERO);
                if (p == null) continue;
                if (segmentBlockedTwoHigh(from, p)) continue;

                this.currentWaypoint = p;
                return true;
            }
        }
        return false;
    }

    // How many blocks of negative forward motion we allow
    private double forwardBudgetBlocks() {
        if (postRecoverGuardTicks > 0) return 0.0;
        switch (stallLevel) {
            case 0:
                return 0.25;
            case 1:
                return 1.0;
            case 2:
                return 2.5;
            default:
                return 4.0;   // was 8.0
        }
    }


    // Max sideways distance from the line to target (in blocks)
    private double lateralCapBlocks() {
        switch (stallLevel) {
            case 0:
                return 16.0;
            case 1:
                return 24.0;
            case 2:
                return 40.0;
            default:
                return 64.0;
        }
    }

    // Forward component (in blocks) from 'from' to 'p' along the direction to the target (XZ only)
    private double forwardComponent(BlockPos p, BlockPos from, Vec3d toDir) {
        Vec3d deltaXZ = new Vec3d(p.getX() + 0.5 - (from.getX() + 0.5), 0, p.getZ() + 0.5 - (from.getZ() + 0.5));
        return deltaXZ.dotProduct(toDir);
    }

    // Lateral distance (in blocks) from p to the A->target line (XZ)
    private double lateralDistanceToLine(BlockPos p, BlockPos from, BlockPos target) {
        if (target == null) return Double.POSITIVE_INFINITY;
        Vec3d A = new Vec3d(from.getX() + 0.5, 0, from.getZ() + 0.5);
        Vec3d B = new Vec3d(target.getX() + 0.5, 0, target.getZ() + 0.5);
        Vec3d P = new Vec3d(p.getX() + 0.5, 0, p.getZ() + 0.5);
        Vec3d AB = B.subtract(A);
        double ab2 = AB.lengthSquared();
        if (ab2 < 1e-9) return P.subtract(A).length();
        double t = P.subtract(A).dotProduct(AB) / ab2;
        t = Math.max(0, Math.min(1, t));
        Vec3d H = A.add(AB.scale(t));
        return P.subtract(H).length();
    }

    // Accept/reject a candidate with forward + lateral + “how much worse” checks
    private boolean acceptCandidate(BlockPos cand, BlockPos from, Vec3d toDir, long distSqNow) {
        if (this.targetVillage == null) return false;
        // forward gate

        double f = forwardComponent(cand, from, toDir);
        if (f < -forwardBudgetBlocks()) return false;

        // distance gate
        long d2 = (long) cand.distanceSq(this.targetVillage);
        if (d2 > distSqNow + backtrackAllowanceSq()) return false;

        // lateral gate
        return !(lateralDistanceToLine(cand, from, this.targetVillage) > lateralCapBlocks());
    }

    // Tiny score so we pick the best candidate (favor forward, penalize big turns & going away)
    private double scoreCandidate(BlockPos cand, BlockPos from, Vec3d toDir, long distSqNow, int angleDeg) {
        double f = forwardComponent(cand, from, toDir); // can be negative
        long d2 = (long) cand.distanceSq(this.targetVillage);
        double worse = Math.max(0, Math.sqrt(Math.max(0, d2 - distSqNow)));
        double lateral = lateralDistanceToLine(cand, from, this.targetVillage);

        double score = 0.0;
        score += Math.max(0, f) * 1.2;       // reward forward more
        score -= Math.max(0, -f) * 2.0;      // punish backward strongly
        score -= worse * 0.15;               // slightly stronger penalty
        score -= Math.sqrt(lateral) * 0.08;  // more lateral discipline
        score -= (Math.abs(angleDeg) * 0.02);// angle penalty ×2

        return score;
    }

    private boolean hardTeleportNudgeTowardTarget() {
        if (mob.world.isRemote) return false;
        if (this.targetVillage == null) return false;
        if (stallLevel < HARD_UNSTUCK_MIN_STALL) return false;
        if (hardUnstuckCooldown > 0) return false;
        if (hardUnstuckCount >= HARD_UNSTUCK_MAX_PER_TRIP) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

        final BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return false;
        Vec3d dir = to.normalize();

        // Try a few forward distances; first success wins
        final int[] steps = new int[]{5, 7, 9, 12};
        for (int s : steps) {
            int nx = from.getX() + (int) Math.round(dir.x * s);
            int nz = from.getZ() + (int) Math.round(dir.z * s);
            BlockPos guess = new BlockPos(nx, from.getY(), nz);

            // Never load chunks for this
            if (!isAreaLoaded(guess, 1)) continue;

            BlockPos surf = findSurfaceOrWaterY(guess); // your existing “top solid/liquid then scan down”
            if (surf == null) continue;

            // Prefer dry landing: if feet is water but there is a dry standable a bit down, scan a few extra
            IBlockState below = mob.world.getBlockState(surf.down());
            IBlockState feet = mob.world.getBlockState(surf);
            if (below.getMaterial() == net.minecraft.block.material.Material.LAVA
                    || feet.getMaterial() == net.minecraft.block.material.Material.LAVA) {
                continue;
            }

            // Must be navigable (your helper already checks feet/head)
            if (!isNavigable(surf)) continue;

            // Collision safety for the entity’s AABB at target
            AxisAlignedBB aabb = mob.getEntityBoundingBox().offset(
                    (surf.getX() + 0.5) - mob.posX,
                    surf.getY() - mob.posY,
                    (surf.getZ() + 0.5) - mob.posZ
            );
            if (!isAreaLoaded(surf, 1)) continue;
            if (!mob.world.getCollisionBoxes(mob, aabb).isEmpty()) continue;

            // All good: perform the jump
            mob.setPositionAndUpdate(surf.getX() + 0.5, surf.getY(), surf.getZ() + 0.5);
            mob.motionX = mob.motionY = mob.motionZ = 0.0D;
            mob.velocityChanged = true;
            mob.getNavigator().clearPath();

            // Reset progress trackers so we don't immediately re-escalate
            this.lastPos = mob.getPositionVector();
            this.lastProgressPos = this.lastPos;
            this.projAccum = 0.0;
            this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
            this.ticksSinceBest = 0;
            this.noPathTicks = 0;
            this.wallFollow = false;
            if (stallLevel > 0) stallLevel--;

            // Re-seed a waypoint from the new spot
            this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
            hardUnstuckCooldown = HARD_UNSTUCK_COOLDOWN;
            hardUnstuckCount++;
            ticksNoForward = 0;
            lastTPProgressAnchor = mob.getPositionVector();
// recompute bestDist from the new spot
            bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
            ticksSinceBest = 0;
            projAccum = 0.0;
            return true;
        }
        return false;
    }

}

