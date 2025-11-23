package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Random;

public class EntityAITravel extends EntityAIBase {
    // --- Water crossing tuning ---
    private static final int WATER_AHEAD_PROBE = 24; // look ~24 blocks ahead
    private static final int WATER_AHEAD_MIN   = 6; // if >=12 of those are water, treat as water corridor

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

    private int waterWaypointCooldown = 0;
    // How far we're willing to path for a *single* waypoint (blocks)
    private static final int LOCAL_PATH_RANGE = 48; // tune as you like



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
        if (!w.isBlockLoaded(pos)) return false;

        // NEW: never stand on leaves
        if (isLeafBlock(w, pos, st)) return false;

        if (st.getMaterial().isLiquid()) return false;

        BlockFaceShape shape = st.getBlockFaceShape(w, pos, EnumFacing.UP);
        if (shape == BlockFaceShape.SOLID) return true;

        AxisAlignedBB bb = st.getBoundingBox(w, pos);
        if (bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001) return true;

        Block b = st.getBlock();
        if (b == Blocks.SNOW_LAYER) return true;

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

        // already there?
        mob.scheduleNextTravelAmbient();
        if (mob.getDistanceSqToCenter(this.targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }

        // NEW: torch on when starting travel at night
        if (!mob.world.isDaytime()) {
            mob.setTravelTorchVisible(true);
        }

        this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
        mob.setCurrentWaypoint(this.currentWaypoint);
        debugMark(this.currentWaypoint);
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
        if (waterWaypointCooldown > 0) waterWaypointCooldown--;

        // Reached current waypoint? pick next
        if (this.currentWaypoint != null &&
                mob.getDistanceSqToCenter(this.currentWaypoint) <= this.reachDistSq) {

            // in water: avoid thrash, wait a moment before reseeding
            if (mob.isInWater() && waterWaypointCooldown > 0) {
                // keep going; do not change waypoint yet
            } else {
                this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
                mob.setCurrentWaypoint(this.currentWaypoint);
                debugMark(this.currentWaypoint);
                if (mob.isInWater()) waterWaypointCooldown = 10; // ~0.5s guard
            }
        }

// periodic refresh
        if ((mob.ticksExisted + mob.getRecalcPhase()) % this.recalcTicks == 0) {
            if (!(mob.isInWater() && waterWaypointCooldown > 0)) {
                pushPathToWaypoint();
            }
        }

        // --- ambient travel chatter (cheap) ---
        if (!mob.world.isRemote && TravellersModConfig.travellerAmbient) {
            if (mob.ticksExisted >= mob.nextTravelAmbientTick) {
                mob.maybePlayTravelAmbient();
            }
        }
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
                    debugMark(this.currentWaypoint);
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
            debugMark(this.currentWaypoint);
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
            debugMark(this.currentWaypoint);
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
                        Math.abs(dest.z - currentWaypoint.getZ()) <= 1) return;
            }
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
        mob.setTravelTorchVisible(false);
        // Let Roam start this tick
        // (Roam’s shouldExecute() will pass now because distance ≤ VILLAGE_NEAR)
    }

    /**
     * Compute a "next" waypoint toward 'dest' that is inside currently loaded chunks.
     * Does not force-load anything. Returns null if no safe waypoint is found.
     */
    /**
     * Compute a "next" waypoint toward 'dest' that is inside currently loaded chunks.
     * Does not force-load anything. Returns null if no safe waypoint is found.
     */
    private BlockPos computeNextWaypointToward(BlockPos dest, int baseStep) {
        BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(dest.getX() - from.getX(), 0, dest.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return null;

        // 3a) tiny per-entity heading bias
        double biasedYawRad = Math.toRadians(mob.getPathAngleBiasDeg());
        Vec3d dir = rotateY(to.normalize(), biasedYawRad);

        // 3b) jitter step size slightly
        int step = Math.min(baseStep + mob.getPathStepJitter(),
                (int) Math.sqrt(mob.getDistanceSqToCenter(dest)));
        step = Math.max(8, step);
        boolean waterCorridor = isMostlyWaterAhead(from, dir, WATER_AHEAD_PROBE, WATER_AHEAD_MIN);

// Land-first straight candidates; only fallback to water if corridor is truly water-dominant
        for (int s = step; s >= 16; s -= 16) {
            Vec3d left = new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getPathLaneOffset());
            BlockPos candidate = surfaceAtOffsetDryFirst(from, dir, s, left, /*allowWaterFallback=*/waterCorridor);
            if (candidate != null) {
                // if we started in water, try to exit; if already dry, keep it
                candidate = preferShoreWhenInWater(from, candidate, /*preferExit=*/true);
                if (candidate != null) return candidate;
            }
        }




        // Detours (also from biased heading)
        int detStep = Math.max(12, step / 2);
        for (int angle : new int[]{-60, 60, -30, 30, -90, 90}) {
            Vec3d r = rotateY(dir, Math.toRadians(angle));
            Vec3d left2 = new Vec3d(-r.z, 0, r.x).normalize().scale(mob.getPathLaneOffset() * 0.6);
            BlockPos candidate = surfaceAtOffsetDryFirst(from, r, detStep, left2, /*allowWaterFallback=*/waterCorridor);
            if (candidate != null) {
                candidate = preferShoreWhenInWater(from, candidate, /*preferExit=*/true);
                if (candidate != null && !segmentBlockedTwoHigh(from, candidate)) return candidate;
            }
        }


        // Tiny random reseed
        Random rand = mob.getRNG();
        for (int i = 0; i < 6; i++) {
            int nx = from.getX() + rand.nextInt(9) - 4;
            int nz = from.getZ() + rand.nextInt(9) - 4;
            BlockPos dry = findDrySurfaceNear(new BlockPos(nx, from.getY(), nz));
            if (dry != null && isAreaLoaded(dry, 1) && isStandable(dry)) return dry;

            if (waterCorridor) {
                BlockPos wet = findSurfaceOrWaterY(new BlockPos(nx, from.getY(), nz));
                if (wet != null && isAreaLoaded(wet, 1) && isSwimmable(wet)) return wet;
            }
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
                debugMark(this.currentWaypoint);
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
                debugMark(this.currentWaypoint);
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

        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.up()) || !world.isBlockLoaded(feet.down()))
            return false;

        final IBlockState below  = world.getBlockState(feet.down());
        final IBlockState atFeet = world.getBlockState(feet);
        final IBlockState atHead = world.getBlockState(feet.up());

        // NEW: do not consider leaves as valid support
        if (isLeafBlock(world, feet.down(), below)) return false;

        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) return false;
        if (!supportsStandingSurface(world, feet.down(), below)) return false;

        // --- NEW: allow standing where the feet cell is a snow_layer (thin collision) ---
        boolean feetOk =
                atFeet.getBlock() == Blocks.SNOW_LAYER
                        ||  atFeet.getMaterial().isReplaceable()
                        ||  atFeet.getCollisionBoundingBox(world, feet) == null;

        boolean headOk =
                atHead.getMaterial().isReplaceable()
                        ||  atHead.getCollisionBoundingBox(world, feet.up()) == null
                        ||  atHead.getBlock() == Blocks.SNOW_LAYER; // tiny dust on head cell is fine too

        return feetOk && headOk;
    }


    private boolean isSwimmable(BlockPos feetPos) {
        World world = mob.world;
        if (!world.isBlockLoaded(feetPos) || !world.isBlockLoaded(feetPos.up()) || !world.isBlockLoaded(feetPos.down()))
            return false;

        IBlockState feet = world.getBlockState(feetPos);
        IBlockState head = world.getBlockState(feetPos.up());
        IBlockState below = world.getBlockState(feetPos.down());

        // Feet must be vanilla water; head can be water or replaceable air
        boolean feetOK = isVanillaWater(feet);
        boolean headOK = isVanillaWater(head) || head.getMaterial().isReplaceable();

        // absolutely forbid any non-water liquids/quicksand/lava around
        if (isForbiddenLiquid(feet) || isForbiddenLiquid(head) || isForbiddenLiquid(below)) return false;

        return feetOK && headOK;
    }


    private void tryDetourWide() {
        if (this.targetVillage == null) return;
        BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return;

        Vec3d base = to.normalize();
        int detStep = Math.max(24, this.stepBlocks);
        boolean waterCorridor = isMostlyWaterAhead(from, base, WATER_AHEAD_PROBE, WATER_AHEAD_MIN);
        // Prefer forward-hemisphere first; allow deep-back only at max stall
        int[] ANG = (stallLevel >= 3)
                ? new int[]{120, -120, 150, -150, 180, 90, -90, 60, -60, 30, -30}
                : new int[]{120, -120, 90, -90, 60, -60, 30, -30};

        for (int angleDeg : ANG) {
            Vec3d dir = rotateY(base, Math.toRadians(angleDeg));
            BlockPos candidate = waterCorridor
                    ? surfaceAtOffsetWater(from, dir, detStep)
                    : surfaceAtOffset(from, dir, detStep,
                    new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getPathLaneOffset() * 0.5));

            if (candidate != null && !segmentBlockedTwoHigh(from, candidate)) {
                this.currentWaypoint = candidate;
                debugMark(this.currentWaypoint);
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
                    debugMark(this.currentWaypoint);
                    pushPathToWaypoint();
                    return;
                }
            }
        }

        // If all three failed, flip side once to try the other edge before giving up next tick
        followSide = -followSide;
    }

    private boolean segmentBlockedTwoHigh(BlockPos a, BlockPos b) {
        // How densely to sample along the segment (XZ)
        int steps = Math.max(1, (int) Math.ceil(a.distanceSq(b) > 0 ? Math.sqrt(a.distanceSq(b)) : 1));

        double ax = a.getX() + 0.5, az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
        double stepx = (bx - ax) / steps, stepz = (bz - az) / steps;

        // Soft budget like before (permit a few inconclusive samples)
        int softFails = 0, softBudget = Math.max(1, steps / 6);

        // We’ll compare against the mob’s current stepHeight (1.4F on land, 0.8F in water)
        // Give a tiny epsilon to avoid flapping on equal heights.
        final double stepAllowance = Math.max(0.8, mob.stepHeight) + 0.10;

        // Seed previous effective Y with the start column
        BlockPos col0 = new BlockPos(MathHelper.floor(ax), a.getY(), MathHelper.floor(az));
        double prevY = effectiveTopY(mob.world, col0);
        if (Double.isNaN(prevY)) return true; // treat unknown as blocked

        for (int i = 1; i <= steps; i++) {
            int x = (int) Math.floor(ax + stepx * i);
            int z = (int) Math.floor(az + stepz * i);
            BlockPos col = new BlockPos(x, a.getY(), z);
            if (!isAreaLoaded(col, 1)) return true; // never load chunks → treat as blocked

            double y = effectiveTopY(mob.world, col);
            if (Double.isNaN(y)) {
                if (++softFails > softBudget) return true;
                continue;
            }

            // --- NEW: fractional step check (handles snow_layer 3→5, etc.) ---
            double dy = y - prevY;

            // big upward cliff?
            if (dy > stepAllowance) return true;

            // big downward drop is fine for blocker test, but if you want to avoid cliffs,
            // you can add: if (dy < -2.5) return true; // optional

            // also ensure the *feet* column is navigable (land or vanilla-water only)
            BlockPos feetPos = new BlockPos(x, (int)Math.floor(y), z);
            if (!isNavigable(feetPos)) {
                if (++softFails > softBudget) return true;
            } else {
                // check that headroom around this effective top doesn't intersect forbidden liquids
                AxisAlignedBB aabb = mob.getEntityBoundingBox().offset(
                        (x + 0.5) - mob.posX,
                        (Math.floor(y) + 0.0) - mob.posY, // feet at column’s integer Y; collision boxes will clip actual top
                        (z + 0.5) - mob.posZ
                );
                if (intersectsForbiddenLiquid(mob.world, aabb)) {
                    if (++softFails > softBudget) return true;
                } else {
                    // sample counted as OK; update baseline height
                    prevY = y;
                }
            }
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
        switch (stallLevel) {
            case 0:
                return 9;   // was 4  (~3 blocks)
            case 1:
                return 100; // was 64 (~10 blocks)
            case 2:
                return 256;
            default:
                return 900;
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
        double dy = cand.getY() - from.getY();
        double slopePenalty = Math.max(0, Math.abs(dy) - 1) * 0.6; // punish >1 block up/down
        score -= slopePenalty;

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
            if (isForbiddenLiquid(below) || isForbiddenLiquid(feet)) continue;
            if (isLeafBlock(mob.world, surf.down(), below)) continue;
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
            if (intersectsForbiddenLiquid(mob.world, aabb)) continue; // <-- NEW


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
            debugMark(this.currentWaypoint);
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


    /**
     * If preferExit==true and we're in water, nudge the candidate to a nearby rim.
     * If preferExit==false, keep the candidate as-is (used for long water crossings).
     */
    @Nullable
    private BlockPos preferShoreWhenInWater(BlockPos from, BlockPos candidateTowardTarget, boolean preferExit) {
        if (!preferExit) return candidateTowardTarget;
        if (!mob.isInWater()) return candidateTowardTarget;
        if (candidateTowardTarget == null) return null;

        World w = mob.world;
        BlockPos c = candidateTowardTarget;

        Vec3d dir = new Vec3d(
                targetVillage.getX() + 0.5 - (from.getX() + 0.5),
                0,
                targetVillage.getZ() + 0.5 - (from.getZ() + 0.5)
        );
        if (dir.lengthSquared() < 1e-6) return candidateTowardTarget;
        dir = dir.normalize();

        int nx = c.getX() + (int) Math.signum(dir.x);
        int nz = c.getZ() + (int) Math.signum(dir.z);

        for (int dy = 0; dy <= 1; dy++) {
            BlockPos up = new BlockPos(nx, c.getY() + dy, nz);
            if (isStandable(up)) return up;
        }
        return candidateTowardTarget;
    }
    /**
     * Return true if the next 'probe' blocks in the travel direction are mostly water
     * (without loading chunks).
     */
    private boolean isMostlyWaterAhead(BlockPos from, Vec3d dir, int probe, int minWater) {
        int wx = from.getX(), wy = from.getY(), wz = from.getZ();
        int waterCount = 0, samples = 0;

        for (int s = 2; s <= probe; s += 2) {
            int nx = wx + (int)Math.round(dir.x * s);
            int nz = wz + (int)Math.round(dir.z * s);
            BlockPos col = new BlockPos(nx, wy, nz);
            if (!isAreaLoaded(col, 1)) break; // don't load chunks

            BlockPos surf = findSurfaceOrWaterY(col);
            if (surf == null) continue;

            IBlockState feet = mob.world.getBlockState(surf);
            IBlockState below = mob.world.getBlockState(surf.down());

            boolean waterFeet = isVanillaWater(feet);
            boolean waterBelow = isVanillaWater(below);

            if (waterFeet || waterBelow) waterCount++;
            samples++;
        }
        return samples >= 6 && waterCount >= Math.min(samples, minWater);
    }

    /** Try a water-favoring surface pick (keeps you in water lanes if present). */
    @Nullable
    private BlockPos surfaceAtOffsetWater(BlockPos from, Vec3d dir, int s) {
        int nx = from.getX() + (int)Math.round(dir.x * s);
        int nz = from.getZ() + (int)Math.round(dir.z * s);
        BlockPos guess = new BlockPos(nx, from.getY(), nz);
        BlockPos surf = findSurfaceOrWaterY(guess);
        if (surf == null || !isAreaLoaded(surf, 1)) return null;

        // For water travel, prefer a water cell if both are valid
        if (isSwimmable(surf)) return surf;
        if (isNavigable(surf)) return surf;
        return null;
    }
    /** True if feet cell is solid/walkable land (not liquid). */
    private boolean isDryStandable(BlockPos feet) {
        return isStandable(feet) && !mob.world.getBlockState(feet).getMaterial().isLiquid();
    }
    /** Try to get a land surface at/near XZ (without loading chunks). */
    @Nullable
    private BlockPos findDrySurfaceNear(BlockPos guess) {
        BlockPos surf = findSurfaceOrWaterY(guess);
        if (surf == null) return null;
        if (isDryStandable(surf)) return surf;

        // small lateral nudge to hop off pond tiles (±1 in a plus pattern)
        final int[][] off = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : off) {
            BlockPos n = new BlockPos(surf.getX()+d[0], surf.getY(), surf.getZ()+d[1]);
            if (!isAreaLoaded(n, 1)) continue;
            if (isDryStandable(n)) return n;
        }
        return null;
    }

    /** Land-first variant of surfaceAtOffset; falls back to water only if allowed. */
    @Nullable
    private BlockPos surfaceAtOffsetDryFirst(BlockPos from, Vec3d dir, int s, Vec3d lateral, boolean allowWaterFallback) {
        int nx = from.getX() + (int)Math.round(dir.x * s + lateral.x);
        int nz = from.getZ() + (int)Math.round(dir.z * s + lateral.z);
        BlockPos g = new BlockPos(nx, from.getY(), nz);

        BlockPos dry = findDrySurfaceNear(g);
        if (dry != null && isAreaLoaded(dry, 1) && isStandable(dry)) return dry;

        if (!allowWaterFallback) return null;

        BlockPos wet = findSurfaceOrWaterY(g);
        if (wet != null && isAreaLoaded(wet, 1) && isSwimmable(wet)) return wet;
        return null;
    }
    // --- DEBUG ONLY ---
    private void debugMark(@javax.annotation.Nullable BlockPos p) {
        if (!TravellersModConfig.debug) return;
        if (p == null) return;
        if (mob.world.isRemote) return;

        net.minecraft.world.World w = mob.world;
        net.minecraft.entity.EntityAreaEffectCloud cloud = new net.minecraft.entity.EntityAreaEffectCloud(w,
                p.getX() + 0.5, p.getY() + 1.1, p.getZ() + 0.5);

        cloud.setDuration(Math.max(20, 5000)); // lifetime
        cloud.setWaitTime(0);
        cloud.setRadius(0.15F);          // tiny dot
        cloud.setRadiusPerTick(0.0F);    // don't grow
        cloud.setParticle(net.minecraft.util.EnumParticleTypes.FLAME); // pick any you like

        // Optional: make it very obvious
        // cloud.setColor(0x33FF66); // only shows with potion-based particles

        w.spawnEntity(cloud);
    }
    // ---- Liquid filters: only vanilla water is allowed ----
    private static boolean isVanillaWater(IBlockState s) {
        Block b = s.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER; // 1.12.2
    }

    // BoP quicksand: user said it's registered as biomesoplenty:sand
    private static boolean isBoPQuicksand(IBlockState s) {
        ResourceLocation rl = s.getBlock().getRegistryName();
        return rl != null && "biomesoplenty".equals(rl.getNamespace()) && "sand".equals(rl.getPath());
    }

    // Any liquid other than vanilla water is forbidden (lava, mod fluids, quicksand, etc.)
    private static boolean isForbiddenLiquid(IBlockState s) {
        if (isBoPQuicksand(s)) return true;
        if (!s.getMaterial().isLiquid()) return false;
        return !isVanillaWater(s);
    }

    // AxisAlignedBB check that ignores vanilla water but flags any forbidden liquid
    public static boolean intersectsForbiddenLiquid(World w, AxisAlignedBB aabb) {
        int minX = MathHelper.floor(aabb.minX);
        int maxX = MathHelper.floor(aabb.maxX + 1.0);
        int minY = MathHelper.floor(aabb.minY);
        int maxY = MathHelper.floor(aabb.maxY + 1.0);
        int minZ = MathHelper.floor(aabb.minZ);
        int maxZ = MathHelper.floor(aabb.maxZ + 1.0);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    pos.setPos(x, y, z);
                    IBlockState s = w.getBlockState(pos);
                    if (isForbiddenLiquid(s)) return true;
                }
            }
        }
        return false;
    }
    // ---- Leaves helper (1.12.2 + modded leaves support) ----
    private static boolean isLeafBlock(World w, BlockPos pos, IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof net.minecraft.block.BlockLeaves) return true;
        try {
            return b.isLeaves(s, w, pos); // many mods override this
        } catch (Throwable ignore) {
            return false;
        }
    }
    // --- Snow height helpers (1.12.2) ---
    private static int snowLayers(IBlockState s) {
        // BlockSnow (the thin "layer") has LAYERS 1..8; BlockSnow (full block) is Blocks.SNOW
        if (s.getBlock() == Blocks.SNOW_LAYER) {
            try {
                return s.getValue(net.minecraft.block.BlockSnow.LAYERS);
            } catch (Throwable ignore) {}
        }
        return 0;
    }

    // Effective top Y at a column, including fractional snow height.
// Returns NaN if chunk is not loaded or no navigable surface found quickly.
    private double effectiveTopY(World w, BlockPos col) {
        if (!isAreaLoaded(col, 1)) return Double.NaN;

        // Start from vanilla "top solid or liquid" of this column
        BlockPos top = w.getTopSolidOrLiquidBlock(col);

        // Walk up a few steps if needed (bridges/leaves can sit just above)
        BlockPos p = top;
        for (int i = 0; i < 3; i++) {
            IBlockState s = w.getBlockState(p);
            if (isStandable(p) || isSwimmable(p)) break;
            p = p.up();
            if (!isAreaLoaded(p, 1)) return Double.NaN;
        }

        // Then scan down a bit to land on something navigable
        for (int i = 0; i < 12 && p.getY() > 1; i++) {
            if (isStandable(p) || isSwimmable(p)) break;
            p = p.down();
            if (!isAreaLoaded(p, 1)) return Double.NaN;
        }

        if (!isNavigable(p)) return Double.NaN;

        // Base integer top (feet block Y)
        double y = p.getY();

        // Add fractional height if the *feet* cell is a snow layer
        IBlockState feet = w.getBlockState(p);
        int layers = snowLayers(feet);
        if (layers > 0) {
            y += layers / 8.0; // each layer is 1/8th of a block
        }

        // If the feet cell is a full SNOW block, we’re already at +1.0 implicitly via p.getY()
        // If there is an extra snow_layer on top of a full snow block, we’ll be sampling that
        // as a higher feet position on the next columns already.

        return y;
    }


}

