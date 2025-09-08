package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.traveller.utils.MillenaireVillageDirectory;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Random;

public class EntityAINewTravel extends EntityAIBase {

    private final EntityTraveller mob;
    private final double speed;
    private final int stepBlocks;
    private final double reachDistSq;
    private final int recalcTicks;
    private final int stuckCheckTicks;

    private BlockPos currentWaypoint;
    private BlockPos targetVillage;


    // Stuck detection
    private Vec3d lastPos = Vec3d.ZERO;
    private int ticksSinceProgress = 0;
    private int noPathTicks = 0;
    private static final int NO_PATH_RESELECT = 20; // ~1s

    // Small detour headings, degrees
    private static final int[] DETOUR_ANGLES = new int[]{-60, 60, -30, 30, -90, 90, 120, -120};

    //WATER
    private boolean waterCruise = false;
    private int waterCruiseExitGrace = 0;        // keep cruise for a few ticks after leaving water
    private static final int WATER_CRUISE_EXIT_GRACE_TICKS = 10; // ~0.5s
    private float cruiseYaw = 0f;                // smoothed heading
    private int cruiseAimCooldown = 0;           // don’t re-aim every tick
    private static final int CRUISE_AIM_PERIOD = 8; // ticks between aim updates

    // progress tracking/escalation
    private double bestDistSq = Double.MAX_VALUE;
    private int ticksSinceBest = 0;
    private int stallLevel = 0; // 0 normal, 1 wide detour, 2 wall-follow, 3 local escape scan
    private static final int STALL_T1 = 6 * 20;   // ~6s no progress → widen detours
    private static final int STALL_T2 = 12 * 20;  // ~12s → wall-follow mode
    private static final int STALL_T3 = 20 * 20;  // ~20s → local escape scan

    private boolean wallFollow = false;
    private int wallFollowTicks = 0;
    private static final int WALLFOLLOW_MAX = 10 * 20; // ~10s
    private int followSide = 1; // +1 = right hand, -1 = left handc

    // directional progress
    private Vec3d lastProgressPos = Vec3d.ZERO;
    private double projAccum = 0.0;
    private static final double PROJ_STEP_OK = 0.8;   // ~0.8 block of forward progress counts
    private static final double PROJ_RESET_DIST = 2.0; // move ~2 blocks before resetting base
    // EntityTraveller fields
    private int travelRecoverTicks = 0;
    private boolean resetAfterCombatDone = false;

    // add near-radius cache for the current target
    private int targetNearSq = 0;

    private static int sq(int r) { return r * r; }

    // Escalating scan radii (≈ blocks)
    private static final int ESCAPE_SCAN_L0 = 48;
    private static final int ESCAPE_SCAN_L1 = 64;
    private static final int ESCAPE_SCAN_L2 = 96;
    private static final int ESCAPE_SCAN_L3 = 128;



    public EntityAINewTravel(EntityTraveller mob, double speed, int stepBlocks) {
        this.mob = mob;
        this.speed = speed;
        this.stepBlocks = Math.max(8, stepBlocks);
        this.reachDistSq = 3.0 * 3.0;
        this.recalcTicks = 12;
        this.stuckCheckTicks = 60;
        this.setMutexBits(1 | 2); // MOVE (1) + LOOK (2) so nothing else steals the head while travelling
    }

    @Override
    public boolean shouldExecute() {
        if (mob.isInCombat()) return false;
        BlockPos tv = mob.getTargetVillage();
        if (tv == null) return false;

        retarget(tv); // set village + near radius for this target
        return mob.getDistanceSqToCenter(tv) > this.targetNearSq;
    }

    @Override
    public void startExecuting() {
        this.targetVillage = mob.getTargetVillage();
        if (this.targetVillage != null) {
            retarget(this.targetVillage);
        }
        this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
        pushPathToWaypoint();

        this.lastPos = mob.getPositionVector();
        this.ticksSinceProgress = 0;
        this.noPathTicks = 0;

        this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
        this.ticksSinceBest = 0;
        this.stallLevel = 0;

        this.lastProgressPos = mob.getPositionVector();
        this.projAccum = 0.0;

        this.waterCruise = false;
        this.waterCruiseExitGrace = 0;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.isInCombat()) return false;

        BlockPos tv = mob.getTargetVillage();
        if (tv == null) return false;

        if (this.targetVillage == null || !tv.equals(this.targetVillage)) {
            retarget(tv); // refresh near radius for new target
        }

        return mob.getDistanceSqToCenter(tv) >= this.targetNearSq;
    }

    @Override
    public void updateTask() {
        if (mob.isInCombat()) return;
        // --- post-combat recovery handling ---
        int recover = mob.getTravelRecoverTicks();
        if (recover > 0) {
            // one-shot reset of progress baselines so stall logic doesn't trigger
            if (!resetAfterCombatDone) {
                // refresh the live target in case the route advanced
                BlockPos liveTarget = mob.getTargetVillage();
                if (liveTarget != null && (this.targetVillage == null || !liveTarget.equals(this.targetVillage))) {
                    this.targetVillage = liveTarget;
                    this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
                }
                this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
                this.ticksSinceBest = 0;
                this.stallLevel = 0;
                this.lastProgressPos = mob.getPositionVector();
                this.projAccum = 0.0;
                this.noPathTicks = 0;
                this.waterCruise = false;
                this.waterCruiseExitGrace = 0;
                mob.getNavigator().clearPath();
                pushPathToWaypoint();
                resetAfterCombatDone = true;
            }

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

        if (this.targetVillage == null || !liveTarget.equals(this.targetVillage)) {
            // The route advanced (or was reassigned) -> retarget cleanly
            retarget(liveTarget);  // sets targetVillage and its per-type near radius
            this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);

            // Reset progress trackers so stall logic doesn’t fight the new target
            this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
            this.ticksSinceBest = 0;
            this.stallLevel = 0;
            this.lastProgressPos = mob.getPositionVector();
            this.projAccum = 0.0;

            // If we were in any special travel mode, bail out of it
            this.waterCruise = false;
            this.waterCruiseExitGrace = 0;

            mob.getNavigator().clearPath();
            pushPathToWaypoint();
        }

        // Re-evaluate target village in case it changed to a closer one


        final World world = mob.world;

// Reached current waypoint? pick next
        if (this.currentWaypoint != null &&
                mob.getDistanceSqToCenter(this.currentWaypoint) <= this.reachDistSq) {
            this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
        }

// WATER-CRUISE STATE MACHINE
        boolean inWaterNow = mob.isInWater();

// Start cruise if in water OR the next waypoint is over water
        boolean wpIsWater = this.currentWaypoint != null && isWaterColumn(world, this.currentWaypoint);

        if (!waterCruise) {
            if (inWaterNow || wpIsWater) {
                waterCruise = true;
                waterCruiseExitGrace = 0;
                cruiseAimCooldown = 0;
                cruiseYaw = mob.rotationYaw; // start from current yaw
                mob.getNavigator().clearPath(); // ground nav off
            }
        } else {
            // we’re cruising: keep going straight at the village
            cruiseTowardVillage();

            // exit condition: not in water and feet on land; add small grace so we don't flicker
            if (!inWaterNow) {
                waterCruiseExitGrace++;
                if (waterCruiseExitGrace >= WATER_CRUISE_EXIT_GRACE_TICKS) {
                    waterCruise = false;
                    waterCruiseExitGrace = 0;

                    // hand off back to pathfinder from current location/waypoint
                    if (this.currentWaypoint == null) {
                        this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
                    }
                    pushPathToWaypoint(); // land path resumes
                }
            } else {
                waterCruiseExitGrace = 0;
            }

            // while cruising, do not let ground nav repath or stuck logic fight us
            this.noPathTicks = 0;
            return; // skip the rest of land logic this tick
        }

// classic distance-to-goal check (keep it)
        double d2 = mob.getDistanceSqToCenter(this.targetVillage);
        boolean distImproved = d2 < bestDistSq - 0.75; // ~0.86 blocks net

// NEW: directional progress (component toward the village)
        Vec3d pos = mob.getPositionVector();
        Vec3d goalDir = new Vec3d(targetVillage).add(0.5, 0, 0.5).subtract(pos).normalize();
        Vec3d delta = pos.subtract(this.lastProgressPos);
        double forward = delta.dotProduct(goalDir); // signed “meters toward village” since lastProgressPos

        if (forward > PROJ_STEP_OK) {
            projAccum += forward;
            this.lastProgressPos = pos;                // move the baseline so we measure fresh increments
        }

        if (distImproved || projAccum >= 2.5) {        // EITHER got closer OR made ~2.5 blocks of forward component
            bestDistSq = Math.min(bestDistSq, d2);
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
            pushPathToWaypoint();
            noPathTicks = 0;
        }


        // Periodic repush to avoid stale paths
        if (mob.ticksExisted % recalcTicks == 0) {
            pushPathToWaypoint();
        }

        // Stuck detection
        if (mob.ticksExisted % this.stuckCheckTicks == 0) {
            if (!mob.isAutoEating() && !mob.isHandActive()) {
                double movedSq = mob.getPositionVector().squareDistanceTo(this.lastPos);
                this.lastPos = mob.getPositionVector();

                if (movedSq < 0.6 * 0.6) {
                    mob.breakNearbyLeaves();
                    if (stallLevel <= 1) {
                        tryDetourWide(); // new: wider angles/longer step
                    } else if (stallLevel == 2) {
                        enterOrStepWallFollow(); // new: perimeter hugging
                    } else { // stallLevel >= 3
                        int scan = currentEscapeScanRadius();
                        if (localEscapeScan(scan)) {
                            pushPathToWaypoint();
                        } else if (macroLateralBypass()) {   // big sideways hop
                            pushPathToWaypoint();
                        } else {
                            // last resort: tiny random reseed
                            tryDetour();
                        }

                    }
                }
            }
        }
    }

    @Override
    public void resetTask() {
        this.currentWaypoint = null;
    }

    // ---- helpers ----

    private void pushPathToWaypoint() {
        if (this.currentWaypoint == null) return;
        if (mob.isInWater() || isWaterColumn(mob.world, this.currentWaypoint)) return; // nav off in water
        mob.getNavigator().tryMoveToXYZ(
                this.currentWaypoint.getX() + 0.5,
                this.currentWaypoint.getY(),
                this.currentWaypoint.getZ() + 0.5,
                this.speed
        );
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
                (int)Math.sqrt(mob.getDistanceSqToCenter(dest)));
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
            BlockPos surf = findSurfaceY(new BlockPos(nx, from.getY(), nz));
            if (surf != null && isAreaLoaded(surf, 1) && isStandable(surf)) return surf;
        }
        return null;
    }

    private BlockPos surfaceAtOffset(BlockPos from, Vec3d dir, int s, Vec3d lateral) {
        int nx = from.getX() + (int)Math.round(dir.x * s + lateral.x);
        int nz = from.getZ() + (int)Math.round(dir.z * s + lateral.z);
        BlockPos surface = findSurfaceY(new BlockPos(nx, from.getY(), nz));
        return (surface != null && isAreaLoaded(surface, 1) && isStandable(surface)) ? surface : null;
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
            BlockPos surface = findSurfaceY(new BlockPos(nx, from.getY(), nz));
            if (surface != null && isAreaLoaded(surface, 1) && isStandable(surface)) {
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
            BlockPos surface = findSurfaceY(new BlockPos(nx, from.getY(), nz));
            if (surface != null && isAreaLoaded(surface, 1) && isStandable(surface)) {
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
    private BlockPos findSurfaceY(BlockPos guess) {
        World world = mob.world;
        if (!isAreaLoaded(guess, 1)) return null;

        int top = world.getHeight(guess).getY(); // in 1.12 this does not load new chunks for an already loaded column
        if (top <= 0) return null;

        // Place entityAI feet on top block
        BlockPos pos = new BlockPos(guess.getX(), top, guess.getZ());

        // If inside leaves/fence etc, nudge down until on solid with air headroom
        for (int dy = 0; dy < 8; dy++) {
            BlockPos below = pos.down();
            if (isStandable(pos)) return pos;
            pos = below;
        }
        return isStandable(pos) ? pos : null;
    }

    /** “Can the mob stand with feet at `feet`?” (land version) */
    private boolean isStandable(BlockPos feet) {
        World world = mob.world;

        IBlockState below = world.getBlockState(feet.down());
        IBlockState atFeet = world.getBlockState(feet);
        IBlockState atHead = world.getBlockState(feet.up());

        // 1) below must actually support standing
        if (!supportsStandingSurface(world, feet.down(), below)) return false;

        // 2) feet/head must be passable (air, tall grass, etc.)
        return atFeet.getMaterial().isReplaceable()
                && atHead.getMaterial().isReplaceable();
    }

    /** True if the block at `pos` can support standing on top (accepts ice). */
    private static boolean supportsStandingSurface(World w, BlockPos pos, IBlockState st) {
        // liquids never support standing
        if (st.getMaterial().isLiquid()) return false;

        // prefer explicit face shape when available
        BlockFaceShape shape = st.getBlockFaceShape(w, pos, EnumFacing.UP);
        if (shape == BlockFaceShape.SOLID) return true;

        // fallback: has a collision box (i.e., something to stand on)
        AxisAlignedBB bb = st.getBoundingBox(w, pos);
        if (bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001) return true;

        // final small allowlist for known standable non-opaque blocks
        Block b = st.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.FROSTED_ICE
                || b == Blocks.GLASS || b == Blocks.STAINED_GLASS;
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
    private boolean isWaterColumn(World w, BlockPos p) {
        IBlockState sFeet = w.getBlockState(p);
        IBlockState sBelow = w.getBlockState(p.down());
        return sFeet.getMaterial() == net.minecraft.block.material.Material.WATER
                || sBelow.getMaterial() == net.minecraft.block.material.Material.WATER;
    }
    private void swimToward(BlockPos wp) {
        // Disable pathing so ground nav doesn’t yank us back to shore
        mob.getNavigator().clearPath();

        // Aim & nudge forward
        Vec3d pos = mob.getPositionVector();
        Vec3d target = new Vec3d(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
        Vec3d dir = target.subtract(pos);
        double d2 = dir.lengthSquared();
        if (d2 < 1e-6) return;
        dir = dir.normalize();

        // Forward push (a bit stronger than on land)
        double push = 0.08;              // tune 0.06–0.12
        mob.motionX += dir.x * push;
        mob.motionZ += dir.z * push;

        mob.rotationYaw = (float)(Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
        mob.renderYawOffset = mob.rotationYaw;
    }
    private void cruiseTowardVillage() {
        if (this.targetVillage == null) return;

        // 1) aim direction (update only every few ticks to avoid jitter)
        if (cruiseAimCooldown-- <= 0) {
            cruiseAimCooldown = CRUISE_AIM_PERIOD;
            Vec3d pos = mob.getPositionVector();
            Vec3d tgt = new Vec3d(targetVillage).add(0.5, 0, 0.5);
            Vec3d dir = tgt.subtract(pos);
            if (dir.lengthSquared() > 1e-6) {
                float aimYaw = (float)(Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
                // smooth the yaw a bit so we don't snap if target is almost behind us
                cruiseYaw = lerpAngle(cruiseYaw, aimYaw, 12f); // max 12° per update
            }
        }

        mob.rotationYaw = cruiseYaw;
        mob.renderYawOffset = cruiseYaw;

        // 2) forward push (cap top speed in water)
        double forwardPush = 0.03; // reduce if still too fast; 0.05–0.08 is nice
        Vec3d fwd = new Vec3d(-Math.sin(Math.toRadians(cruiseYaw)),
                0,
                Math.cos(Math.toRadians(cruiseYaw)));
        mob.motionX += fwd.x * forwardPush;
        mob.motionZ += fwd.z * forwardPush;

        // keep eyes near surface so they don't dive
        BlockPos eye = new BlockPos(mob.posX, mob.posY + mob.getEyeHeight(), mob.posZ);
        boolean eyeInWater = mob.world.getBlockState(eye).getMaterial() == net.minecraft.block.material.Material.WATER;
        if (eyeInWater) mob.motionY = Math.max(mob.motionY, 0.03);

        // 3) opportunistic shoreline snap: if we see land ahead, set a landing waypoint for handoff
        BlockPos landing = scanLandingAhead(32); // ~32 blocks scan
        if (landing != null) {
            this.currentWaypoint = landing;
            // stay in cruise until we’re actually on land; we don’t pushPath here
        }
    }

    // linear step toward target angle (−180..180 wrap)
    private static float lerpAngle(float cur, float target, float maxStep) {
        float d = net.minecraft.util.math.MathHelper.wrapDegrees(target - cur);
        d = (d >  maxStep) ?  maxStep : (d < -maxStep ? -maxStep : d);
        return cur + d;
    }

    /** First standable non-water column on the forward ray, without loading chunks. */
    @Nullable
    private BlockPos scanLandingAhead(int max) {
        Vec3d dir = new Vec3d(-Math.sin(Math.toRadians(cruiseYaw)), 0,
                Math.cos(Math.toRadians(cruiseYaw))).normalize();
        BlockPos base = mob.getPosition();

        for (int step = 6; step <= max; step += 2) { // skip near samples
            int x = base.getX() + (int)Math.round(dir.x * step);
            int z = base.getZ() + (int)Math.round(dir.z * step);
            BlockPos col = new BlockPos(x, base.getY(), z);
            if (!isAreaLoaded(col, 1)) continue;

            // top of solid/liquid
            BlockPos top = mob.world.getTopSolidOrLiquidBlock(col);
            IBlockState sTop = mob.world.getBlockState(top);
            boolean waterTop = sTop.getMaterial() == net.minecraft.block.material.Material.WATER;

            // try a few blocks downward to find feet on solid with air headroom
            BlockPos p = top;
            for (int dy = 0; dy < 6; dy++) {
                if (!waterTop && isStandable(p)) return p; // first dry standable spot
                p = p.down();
                if (p.getY() <= 1) break;
                waterTop = mob.world.getBlockState(p).getMaterial() == net.minecraft.block.material.Material.WATER;
            }
        }
        return null;
    }
    private void tryDetourWide() {
        if (this.targetVillage == null) return;
        BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return;

        Vec3d base = to.normalize();
        int detStep = Math.max(24, this.stepBlocks); // bigger than normal
        int[] ANG = new int[]{180, 150, -150, 120, -120, 90, -90, 60, -60, 30, -30};
        for (int angleDeg : ANG) {
            Vec3d dir = rotateY(base, Math.toRadians(angleDeg));
            BlockPos candidate = surfaceAtOffset(from, dir, detStep,
                    new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getPathLaneOffset() * 0.5));
            if (candidate != null) {
                this.currentWaypoint = candidate;
                pushPathToWaypoint();
                return;
            }
        }
    }
    private void enterOrStepWallFollow() {
        if (!wallFollow) {
            wallFollow = true;
            wallFollowTicks = 0;
            followSide = mob.getRNG().nextBoolean() ? 1 : -1;
            this.lastProgressPos = mob.getPositionVector(); // reset baseline
        }
        wallFollowTicks++;

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
                ? new Vec3d[]{ right, forward, right.scale(-1) }
                : new Vec3d[]{ right.scale(-1), forward, right };

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
        int steps = Math.max(1, (int)Math.ceil(Math.sqrt(dx*dx + dz*dz))); // ~1 sample per block
        double ax = a.getX() + 0.5, az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
        double dxStep = (bx - ax) / steps;
        double dzStep = (bz - az) / steps;
        int y = a.getY();

        for (int i = 1; i <= steps; i++) {
            int x = (int)Math.floor(ax + dxStep * i);
            int z = (int)Math.floor(az + dzStep * i);
            BlockPos p = new BlockPos(x, y, z);
            if (!isAreaLoaded(p, 1)) return true;   // treat unloaded as blocked (your rule)
            if (!isStandable(p)) return true;       // feet/head blocked somewhere along the line
        }
        return false;
    }

    private boolean localEscapeScan(int radius) {
        radius = Math.max(radius, currentEscapeScanRadius());

        BlockPos from = mob.getPosition();
        Vec3d toDir = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ()).normalize();

        // On heavy stall, allow wider angles (up to ±150°)
        int maxAngle = (stallLevel >= 2) ? 150 : 105;
        int stepAngle = 12;

        long distSqNow = (long) from.distanceSq(targetVillage);
        int worseBudget = backtrackAllowanceSq();

        for (int a = -maxAngle; a <= maxAngle; a += stepAngle) {
            Vec3d dir = rotateY(toDir, Math.toRadians(a));
            for (int s = 8; s <= radius; s += 4) {
                BlockPos p = surfaceAtOffset(from, dir, s, Vec3d.ZERO);
                if (p == null) continue;
                if (segmentBlockedTwoHigh(from, p)) continue;

                long d2 = (long) p.distanceSq(targetVillage);
                // Accept if it doesn't get too much worse than now (budget grows with stall)
                if (d2 <= distSqNow + worseBudget) {
                    this.currentWaypoint = p;
                    return true;
                }
            }
        }
        return false;
    }

    private void retarget(BlockPos newTarget) {
        this.targetVillage = newTarget;
        // pick per-type radius (village vs lone building) and cache squared form
        int near = MillenaireVillageDirectory.nearRadiusFor(newTarget);
        this.targetNearSq = sq(near);
    }
    // How much we're allowed to get "worse" (distance^2) when escaping
    private int backtrackAllowanceSq() {
        // L0: ~+3 blocks, L1: ~+16 blocks, L2: ~+32 blocks, L3: ~+64 blocks
        switch (stallLevel) {
            case 0:  return 9;        // 3^2
            case 1:  return 256;      // 16^2
            case 2:  return 1024;     // 32^2
            default: return 4096;     // 64^2
        }
    }

    /** Escalate scan radius with stall level. */
    private int currentEscapeScanRadius() {
        switch (stallLevel) {
            case 1:  return ESCAPE_SCAN_L1;
            case 2:  return ESCAPE_SCAN_L2;
            case 3:  return ESCAPE_SCAN_L3;
            default: return ESCAPE_SCAN_L0;
        }
    }
    /** Big sidestep around obstacles: try lateral moves (±90°, also ±60/±120) at larger distances. */
    private boolean macroLateralBypass() {
        if (this.targetVillage == null) return false;

        BlockPos from = mob.getPosition();
        Vec3d toDir = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ()).normalize();

        int[] angles = new int[]{ 90, -90, 60, -60, 120, -120 };
        int[] dists  = new int[]{ 32, 48, 64, 80, 96 };

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
}

