package com.jubitus.travellers.entity.entityAI.travel;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.entityAI.follow.FollowRuntimeConfig;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelPathScheduler;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.millenaire.common.block.IBlockPath;
import org.millenaire.common.utilities.MillLog;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

public class EntityAITravel extends EntityAIBase {

    // If we were denied a global scheduler slot while having noPath,
// don't treat it as "stuck" for a short grace period.
    private int lastSchedulerDenyTick = -999999;

    private static final double CATCHUP_SPEED_MULT = 1.35;  // 35% faster in catchup
    private static final double RETURN_SPEED_MULT = 1.15;  // optional if you have a separate return mode
    private static final int TRAVEL_PATH_COOLDOWN_TICKS = 15;
    private static final int PATH_COOLDOWN_TICKS = 20;
    private static final double NET_MOVE_1S_SQ = 0.35 * 0.35; // < 0.35 blocks over 1s == effectively stuck
    private static final int NO_PROGRESS_TRIGGER = 4 * 20; // start detouring after 4s of no improvement
    private static final double PROGRESS_IMPROVE_SQ = 1.5 * 1.5; // ~1.5 blocks improvement counts
    private static final int PATH_NO_ADVANCE_TRIGGER = 6 * 20; // 4s
    private static final int BOAT_RETRY_CD = 40; // 2 seconds
    private static final int DETOUR_GRACE_TICKS = 20 * 15;   // 15 seconds
    private static final int HARD_PIN_TP_TICKS = 20 * 8;    // 8 seconds (still TP fast if totally stuck)
    private static final int UNDERGROUND_FORCE_TP = 10 * 20; // 5 seconds underground -> TP
    // Only count this as "real progress" (prevents detour jitter resets)
// 8 blocks closer (squared distance = 64)
    private static final double MEANINGFUL_PROGRESS_SQ = 6.0 * 6.0;
    // ---- SIMPLE GLOBAL STUCK SYSTEM ----
    private static final int STUCK_T1 = 5 * 20;   // 5s -> small detour
    private static final int STUCK_T2 = 15 * 20;   // 10s -> big detour
    private static final int STUCK_T3 = 20 * 20;  // 20 -> teleport nudge
    private static final int STUCK_ACTION_CD = 2 * 20;   // 2s between actions
    private static final int STUCK_TP_CD = 10 * 20;      // 10s between teleports
    private static final int TP_STEP_BLOCKS = 5;
    // How much distance-to-village must improve to count as "progress"
// (squared distance, so 1.0 ~= 1 block improvement)
    private static final double PROGRESS_EPS = 1.0;
    // ~0.03 blocks/tick is basically not moving (XZ only)
    private static final double MOVE_EPS_SQ = 0.03 * 0.03;
    private static final int NO_MOVE_TRIGGER = 3 * 20; // 3 seconds
    // Small detour headings, degrees
    private static final int[] DETOUR_ANGLES = new int[]{-60, 60, -30, 30, -90, 90, 120, -120};
    // --- Boat / ocean crossing tuning ---
    private static final int BOAT_OCEAN_PROBE = 16;   // how far ahead we scan for water when deciding to use a boat
    private static final int BOAT_OCEAN_MIN_WATER = 16;   // how many water samples required to call it an "ocean"
    private static final double BOAT_MIN_DISTANCE = 40.0; // only use boats if we’re at least this far from the destination
    private static final double BOAT_EXIT_RADIUS = 24.0; // when this close to target village, try to leave boat
    private static final double BOAT_SPEED_MULT = 0.85; // boat speed vs normal travel speed
    private final EntityTraveller mob;
    private final double reachDistSq;
    private final int recalcTicks;
    private final int stuckCheckTicks;
    private int nextTravelSlotTick = 0;   // scheduler pacing (global)
    private int nextComputePathTick = 0;  // actual expensive path compute pacing (per mob)
    private Vec3d moveWindowStart = Vec3d.ZERO;
    private int moveWindowTicks = 0;
    private boolean moveWindowInit = false;
    // --- Progress stall (XZ distance-to-target not improving) ---
    private int noProgressTicks = 0;
    private double progressCheckpointSq = Double.MAX_VALUE;
    private int lastPathIndex = -1;
    private int pathNoAdvanceTicks = 0;
    // --- water entry trigger for boats ---
    private boolean wasInWater = false;
    private int boatRetryCooldown = 0;
    private int detourGraceTicks = 0;
    // --- Force surface rescue if underground too long ---
    private int undergroundTicks = 0;
    private int stuckTicks = 0;
    private int stuckStage = 0;           // 0=none, 1=detour, 2=wide detour, 3=TP mode
    private int stuckActionCooldown = 0;
    private int stuckTpCooldown = 0;
    private double bestDistSq = Double.MAX_VALUE;
    // ---- Movement-based stuck signal (prevents jitter resetting progress) ----
    private Vec3d lastMovePos = Vec3d.ZERO;
    private int noMoveTicks = 0;
    private int stepBlocks;
    // --- boat stall tracking ---
    private double speed;
    private BlockPos currentWaypoint;
    private BlockPos targetVillage;
    private boolean arriveInside = false;
    // Stuck detection
    private Vec3d lastPos = Vec3d.ZERO;
    // near-radius cache for the current target
    private int targetNearSq = 0;
    // simple guard so we don't spam boat spawning
    private boolean triedBoatThisLeg = false;
    public EntityAITravel(EntityTraveller mob, double speed, int stepBlocks) {
        this.mob = mob;
        this.speed = speed;
        this.stepBlocks = Math.max(8, stepBlocks);
        this.reachDistSq = 3.0 * 3.0;
        this.recalcTicks = 12;
        this.stuckCheckTicks = 60;
        this.setMutexBits(1 | 2); // MOVE (1) + LOOK (2) so nothing else steals the head while travelling
    }

    private static Vec3d goalDirXZ(EntityLiving mob, BlockPos target) {
        Vec3d pos = mob.getPositionVector();
        Vec3d v = new Vec3d(target.getX() + 0.5 - pos.x, 0, target.getZ() + 0.5 - pos.z);
        if (v.lengthSquared() < 1e-6) return new Vec3d(0, 0, 1);
        return v.normalize();
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
// ✅ DO NOT LOAD CHUNKS just to check fluids
                    if (!w.isBlockLoaded(pos)) return true; // treat unknown as unsafe
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
            } catch (Throwable ignore) {
            }
        }
        return 0;
    }

    // ---- helpers ----

    private static boolean isChunkSafeLoaded(World world, int cx, int cz) {
        if (!(world instanceof WorldServer)) return false;
        ChunkProviderServer cps = ((WorldServer) world).getChunkProvider();
        Chunk ch = cps.getLoadedChunk(cx, cz);
        return ch != null && !ch.unloadQueued;
    }

    private static boolean isBlockSafeLoaded(World world, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        return isChunkSafeLoaded(world, cx, cz);
    }

    private double getActiveSpeed() {
        double s = this.speed;

        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;

            if (w.isCatchupTravel()) {
                s *= CATCHUP_SPEED_MULT;
            }
            // If you have a separate "return mode" flag, do it here:
            // else if (w.isGoingHome()) { s *= RETURN_SPEED_MULT; }
        }

        if (mob.isInWater()) {
            s *= TravellersModConfig.SWIM_MULT;
        }

        return s;
    }

    public void setSpeed(double s) {
        this.speed = s;
    }

    public void setStepBlocks(int stepBlocks) {
        this.stepBlocks = stepBlocks;
    }

    @Override
    public boolean shouldExecute() {
        boolean catchup = (mob instanceof EntityTravellerWorker) && ((EntityTravellerWorker) mob).isCatchupTravel();


        if (this.mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this.mob;

            // Block random travelling ONLY when they are an active follower.
            if (w.isHiredFollower()
                    && w.isFollowOrderActive()
                    && !w.isJobActive()
                    && !w.isCatchupTravel()
                    && !w.isGoingHome()) {
                return false;
            }
        }


        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

        // only block "already inside" when NOT in catchup
        if (!catchup && (mob.isAtTargetStop() || mob.isAtTargetStopHys(false))) return false;

        BlockPos tv = getLiveGoalPos();
        if (tv == null) return false;
        retarget(tv);

        // in catchup, always run if we have a target
        return catchup || !mob.isAtTargetStop();
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

        boolean catchup = (mob instanceof EntityTravellerWorker) && ((EntityTravellerWorker) mob).isCatchupTravel();

        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;

            // Only run Travel AI when worker explicitly says it should be using travel AI
            if (!w.isUsingTravelAI()) {
                return false;
            }
        }


        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;

            // Only block Travel AI when follow order is ON (companion mode idle),
            // but allow it when follow is OFF (anchor), catchup, JOB, or goingHome.
            if (w.isHiredFollower()
                    && w.isFollowOrderActive()
                    && !w.isJobActive()
                    && !w.isCatchupTravel()
                    && !w.isGoingHome()) {
                return false;
            }
        }

        // In catchup, don't stop just because we're "at target stop"
        if (!catchup && (mob.isAtTargetStop() || mob.isAtTargetStopHys(false))) {
            clearNav();
            return false;
        }

        BlockPos tv = getLiveGoalPos();
        if (tv == null) return false;
        if (!tv.equals(this.targetVillage)) retarget(tv);

        arriveInside = mob.isAtTargetStopHys(arriveInside);
        return catchup || !arriveInside;
    }



    @Override
    public void startExecuting() {
        wasInWater = mob.isInWater();
        boatRetryCooldown = 0;



        if (mob instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) mob).setUsingTravelAI(true);
        }
        arriveInside = mob.isAtTargetStop();
        this.targetVillage = getLiveGoalPos();
        if (this.targetVillage == null) return;
        double d2 = horizDistSq(mob.getPosition(), targetVillage);
        bestDistSq = d2;
        progressCheckpointSq = d2;
        this.stuckTicks = 0;

        mob.scheduleNextTravelAmbient();
        if (mob.getDistanceSqToCenter(this.targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }

        this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
        mob.setCurrentWaypoint(this.currentWaypoint);
        debugMark(this.currentWaypoint);
        pushPathToWaypoint(true);

        this.lastPos = mob.getPositionVector();
        resetStuckState();
        this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);

        this.stuckStage = 0;
        this.stuckActionCooldown = 0;
        this.stuckTpCooldown = 0;
        lastMovePos = mob.getPositionVector();
        noMoveTicks = 0;


        triedBoatThisLeg = false;
    }

    @Override
    public void resetTask() {

        wasInWater = mob.isInWater();
        boatRetryCooldown = 0;

        this.currentWaypoint = null;
        mob.setCurrentWaypoint(null);
        clearNav();
        resetStuckState();
        if (mob instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) mob).setUsingTravelAI(false);
        }
    }

    @Override
    public void updateTask() {

// -----------------------------------------
// OFFSCREEN (NOT TRACKED): NEVER PATHFIND, NEVER LOAD CHUNKS.
// Only chunkloader + offscreenHop should move them.
// For hired catchup workers, only pause if truly untracked AND outside
// the server view distance. The approximate view-distance check has a
// margin that creates a dead zone where neither offscreen hops nor
// pathfinding work.
// -----------------------------------------
        if (!mob.world.isRemote) {
            boolean offscreen;
            if (mob instanceof EntityTravellerWorker) {
                EntityTravellerWorker w = (EntityTravellerWorker) mob;
                if (w.isHiredFollower() && w.isCatchupTravel()) {
                    offscreen = w.getTrackingPlayerCount() <= 0
                            && !w.isInPlayerViewDistance();
                } else {
                    offscreen = !mob.isTrackedByAnyPlayer();
                }
            } else {
                offscreen = !mob.isTrackedByAnyPlayer();
            }
            if (offscreen) {
                if (isInBoat()) {
                    dismountAndDeleteBoat();
                    triedBoatThisLeg = false;
                    boatRetryCooldown = 0;
                    wasInWater = false;
                }
                clearNav();
                wasInWater = false;
                lastSchedulerDenyTick = mob.ticksExisted;
                return;
            }
        }

        // -----------------------------------------
        // Cooldowns for the SIMPLE stuck system
        // -----------------------------------------
        if (stuckActionCooldown > 0) stuckActionCooldown--;
        if (stuckTpCooldown > 0) stuckTpCooldown--;
        if (detourGraceTicks > 0) detourGraceTicks--;
        // -----------------------------------------
        // Ambient chatter (keep)
        // -----------------------------------------
        if (!mob.world.isRemote && TravellersModConfig.travellerAmbient) {
            if (mob.ticksExisted >= mob.nextTravelAmbientTick) {
                mob.maybePlayTravelAmbient();
            }
        }

        if (boatRetryCooldown > 0) boatRetryCooldown--;

        // -----------------------------------------
        // Hard bails (keep)
        // -----------------------------------------
        if (mob.isAutoEating() || mob.isHandActive() || mob.isInCombat()) {
            clearNav();
            return;
        }
        // -----------------------------------------
        // Keep target up to date
        // -----------------------------------------
        BlockPos liveTarget = getLiveGoalPos();
        if (liveTarget == null) return;

        if (!liveTarget.equals(targetVillage)) {
            retarget(liveTarget);
            currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
            mob.setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            pushPathToWaypoint();
        }

        // Arrived?
        boolean catchup = (mob instanceof EntityTravellerWorker) && ((EntityTravellerWorker) mob).isCatchupTravel();

        if (!catchup && mob.getDistanceSqToCenter(targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }


        // -----------------------------------------
        // Boat handling (keep it simple: steer + dismount when hit shore)
        // -----------------------------------------

        EntityTravellerBoat boat = currentBoat();
        if (boat != null) {
            // if boat is colliding and not making progress, bail out
            boolean pinned = boat.collidedHorizontally
                    && (boat.motionX * boat.motionX + boat.motionZ * boat.motionZ) < 0.0004; // ~0.02^2

            if (pinned) {
                handleBoatHitSurface(boat); // dismount + delete + try shore
                return;
            }
        }

        if (isInBoat()) {

            if (currentWaypoint == null) {
                currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
                mob.setCurrentWaypoint(currentWaypoint);
                debugMark(currentWaypoint);
            }
            steerBoatToWaypoint();

// ---- Boat reached waypoint? pick the next one (THIS WAS MISSING) ----
            if (boat != null && currentWaypoint != null) {
                double d2xz = horizDistSq(
                        new BlockPos(MathHelper.floor(boat.posX), 0, MathHelper.floor(boat.posZ)),
                        new BlockPos(currentWaypoint.getX(), 0, currentWaypoint.getZ())
                );

                // Use a slightly larger threshold than 0.25 to avoid “micro-stall”
                if (d2xz <= 2.0 * 2.0) {
                    BlockPos next = computeNextWaypointToward(targetVillage, stepBlocks);
                    if (next != null) {
                        currentWaypoint = next;
                        mob.setCurrentWaypoint(next);
                        debugMark(next);
                    }
                }
            }

// existing shore detection
            if (boat != null && boatHitSurface(boat)) {
                handleBoatHitSurface(boat);
            }
            wasInWater = false;
            return;
        }
        // -----------------------------------------
// Boat: enter-on-water, not on-waypoint
// -----------------------------------------
        boolean inWaterNow = mob.isInWater();

        if (!isInBoat() && inWaterNow) {
            if (!mob.world.isRemote && boatRetryCooldown == 0 && !triedBoatThisLeg) {

                // Optional: don’t immediately boat if we just stepped into a puddle
                // Only do it if we’re *actually* swimming / stalled / long corridor
                // (pick one or two of these guards)
                boolean stalled = (noMoveTicks >= 20);              // 1s not moving
                boolean noPath = mob.getNavigator().noPath();

                if (stalled || noPath) {
                    BlockPos from = mob.getPosition();
                    BlockPos dest = targetVillage;
                    if (dest != null && shouldUseBoat(from, dest)) {

                        Vec3d dirToDest = goalDirXZ(mob, dest);
                        int step = Math.max(16, dynamicStep(from, dest));

                        BlockPos boatWp = pickFirstBoatWaypoint(from, dest, dirToDest, step);
                        if (boatWp != null) {
                            Vec3d boatDir = new Vec3d(
                                    boatWp.getX() + 0.5 - (from.getX() + 0.5),
                                    0,
                                    boatWp.getZ() + 0.5 - (from.getZ() + 0.5)
                            );
                            if (boatDir.lengthSquared() < 1e-6) boatDir = dirToDest;

                            if (tryEnterBoat(from, boatDir.normalize())) {
                                triedBoatThisLeg = true;     // ONLY on success
                                currentWaypoint = boatWp;
                                mob.setCurrentWaypoint(boatWp);
                                debugMark(boatWp);
                                clearNav();
                            } else {
                                boatRetryCooldown = BOAT_RETRY_CD;
                            }
                        } else {
                            boatRetryCooldown = BOAT_RETRY_CD;
                        }
                    }
                }
            }
        }

// keep this for any other logic that uses it
        wasInWater = inWaterNow;


        // -----------------------------------------
        // Ensure we always have a waypoint
        // -----------------------------------------
        if (currentWaypoint == null) {
            currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
            mob.setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            pushPathToWaypoint();
        }

        // Periodic refresh of pathing toward waypoint
// Periodic refresh of pathing toward waypoint
        if ((mob.ticksExisted + mob.getRecalcPhase()) % this.recalcTicks == 0) {

            boolean stuckish =
                    (pathNoAdvanceTicks >= 40)  // ~2s without path index moving
                            || (noMoveTicks >= 40); // ~2s without actual movement

            if (mob.getNavigator().noPath() || stuckish) {
                pushPathToWaypoint(stuckish);
            }

        }


        // Reached waypoint? reseed next
        if (currentWaypoint != null && mob.getDistanceSqToCenter(currentWaypoint) <= this.reachDistSq) {
            currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
            mob.setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            pushPathToWaypoint();
        }
        Path path = mob.getNavigator().getPath();
        if (path != null) {
            int idx = path.getCurrentPathIndex();
            if (idx == lastPathIndex) pathNoAdvanceTicks++;
            else {
                lastPathIndex = idx;
                pathNoAdvanceTicks = 0;
            }
        } else {
            // if we have no path while not arrived, treat as stuck-ish too
            pathNoAdvanceTicks++;
        }

// -----------------------------------------
// Movement signal (prevents jitter from blocking TP)
// -----------------------------------------
        Vec3d now = mob.getPositionVector();

        // ----------------------------------------------------
// If we are currently "paused" because the global scheduler
// wouldn't let us compute a path while we have noPath,
// don't escalate stuck timers or teleport.
// ----------------------------------------------------
        boolean waitingForPathSlot =
                !mob.world.isRemote
                        && mob.getNavigator().noPath()
                        && (mob.ticksExisted - lastSchedulerDenyTick) <= 40; // 2 seconds grace

        if (waitingForPathSlot) {
            // Prevent teleport escalation caused purely by throttling
            noMoveTicks = Math.min(noMoveTicks, 20);
            noProgressTicks = Math.min(noProgressTicks, 20);
            stuckTicks = Math.min(stuckTicks, 20);

            // keep movement tracking consistent
            lastMovePos = now;
            return;
        }


        if (!moveWindowInit) {
            moveWindowStart = now;
            moveWindowTicks = 0;
            moveWindowInit = true;
        }


        double mdx = now.x - lastMovePos.x;
        double mdy = now.y - lastMovePos.y;
        double mdz = now.z - lastMovePos.z;
        double movedSqXZ = mdx * mdx + mdz * mdz;
        boolean movedHoriz = movedSqXZ >= MOVE_EPS_SQ;

        moveWindowTicks++;
        if (moveWindowTicks >= 20) { // 1 second window
            double dx = now.x - moveWindowStart.x;
            double dz = now.z - moveWindowStart.z;
            double netSq = dx * dx + dz * dz;

            if (netSq < NET_MOVE_1S_SQ) {
                noMoveTicks += 20; // accumulate in ticks so your existing thresholds still work
            } else {
                noMoveTicks = 0;
            }

            moveWindowStart = now;
            moveWindowTicks = 0;
        }


        // -----------------------------------------
// Underground detector (caves, overhangs)
// -----------------------------------------
        boolean underground = isUndergroundNow();
        if (underground) undergroundTicks++;
        else undergroundTicks = 0;
        // -----------------------------------------
        // ===== SIMPLE GLOBAL STUCK LOGIC =====
        // Only based on: "are we getting closer to the village?"
        // -----------------------------------------
        double d2 = horizDistSq(mob.getPosition(), targetVillage);
        if (progressCheckpointSq == Double.MAX_VALUE) progressCheckpointSq = d2;

        if (bestDistSq == Double.MAX_VALUE) bestDistSq = d2;
        if (!mob.world.isRemote && mob.isInWater() && noMoveTicks >= 3 * 20) {
            if (teleportToNearestShoreTowardTarget(8, 6)) {
                resetStuckState();
                currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
                mob.setCurrentWaypoint(currentWaypoint);
                pushPathToWaypoint(true);
                return;
            }
        }

// improvement since best-ever
        double improve = bestDistSq - d2;
        if (improve >= PROGRESS_IMPROVE_SQ) {
            bestDistSq = d2;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        // "stuck pressure" comes from either not moving OR not progressing
        int stall = Math.max(noMoveTicks, noProgressTicks);

// If we are stalling, increment stuckTicks faster (optional but effective)
        if (stall > 0) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            stuckStage = 0;
        }


        double progressSinceCheckpoint = progressCheckpointSq - d2;
        boolean pathAdvanced = (pathNoAdvanceTicks == 0);   // means index changed this tick

        boolean meaningfulProgress =
                (progressSinceCheckpoint >= MEANINGFUL_PROGRESS_SQ)
                        && (movedHoriz || pathAdvanced);

// If navigator claims it has a path but we're not actually moving,
// force a detour earlier (works around 1.12.2 "path but stuck" issues).
        if (!mob.world.isRemote) {
            boolean hasPath = (mob.getNavigator().getPath() != null) && !mob.getNavigator().noPath();
            if (hasPath && noMoveTicks >= NO_MOVE_TRIGGER && stuckActionCooldown == 0) {
                clearNav();
                tryDetourWide();
                pushPathToWaypoint(true);
                stuckActionCooldown = STUCK_ACTION_CD;
                detourGraceTicks = DETOUR_GRACE_TICKS;
            }
        }

// Only move the checkpoint when we had meaningful progress
        if (meaningfulProgress) {
            progressCheckpointSq = d2;
            stuckTicks = 0;
            stuckStage = 0;
            pathNoAdvanceTicks = 0;
        }

// If underground too long -> go straight to TP stage
        if (undergroundTicks >= UNDERGROUND_FORCE_TP) {
            stuckTicks = Math.max(stuckTicks, STUCK_T3);
            stuckStage = Math.max(stuckStage, 2);
        }
// If not underground but not moving: escalate gradually
        else if (noMoveTicks >= NO_MOVE_TRIGGER) {
            // first: wide detour stage
            stuckTicks = Math.max(stuckTicks, STUCK_T2);
            stuckStage = Math.max(stuckStage, 1);

            // only after being hard-pinned longer do we allow TP stage
            if (noMoveTicks >= HARD_PIN_TP_TICKS) {
                stuckTicks = Math.max(stuckTicks, STUCK_T3);
                stuckStage = Math.max(stuckStage, 2);
            }
        }

        if (!mob.world.isRemote && stuckActionCooldown == 0) {

            // ✅ TP stage first (highest priority)
            if (stuckTicks >= STUCK_T3) {
                boolean hardPinned =
                        (noMoveTicks >= HARD_PIN_TP_TICKS)
                                || (pathNoAdvanceTicks >= PATH_NO_ADVANCE_TRIGGER)
                                || (noProgressTicks >= HARD_PIN_TP_TICKS);

                if (!hardPinned && detourGraceTicks > 0) return;

                if (stuckTpCooldown == 0) {
                    boolean rescued = teleportToSurfaceRescue(5, 6);
                    rescued |= teleportToward(targetVillage, TP_STEP_BLOCKS);

                    if (rescued) {
                        resetStuckState();
                        bestDistSq = mob.getDistanceSqToCenter(targetVillage);
                        stuckTpCooldown = STUCK_TP_CD;
                        stuckActionCooldown = STUCK_ACTION_CD;
                        detourGraceTicks = 0;

                        currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
                        mob.setCurrentWaypoint(currentWaypoint);
                        debugMark(currentWaypoint);
                        pushPathToWaypoint();
                    } else {
                        tryDetourWide();
                        pushPathToWaypoint();
                        stuckActionCooldown = STUCK_ACTION_CD;
                        detourGraceTicks = DETOUR_GRACE_TICKS;
                    }
                }
                return;
            }

            // ✅ Only do cliff detours while NOT in TP stage
            if (stuckTicks >= STUCK_T1
                    && noMoveTicks >= 20
                    && isCliffAheadTowardTarget(6, 4)) {
                tryDetourWide();
                pushPathToWaypoint();
                stuckActionCooldown = STUCK_ACTION_CD;
                detourGraceTicks = DETOUR_GRACE_TICKS;
                return;
            }


        }
        lastMovePos = now;
    }

    private void clearNav() {
        mob.getNavigator().clearPath();
        lastPathIndex = -1;
        pathNoAdvanceTicks = 0;
    }

    @Nullable
    private BlockPos getLiveGoalPos() {
        // Worker-only dynamic goal (catchup/return-to-owner)
        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;

            // Use your dynamic goal only when catchup travel is active
            if (w.isCatchupTravel()) {
                BlockPos cg = w.getDynamicCatchupGoal(); // <- the 10s refreshed BlockPos you added
                if (cg != null) return cg;
            }
        }

        // Everyone else: normal village travel goal
        return mob.getTargetVillage();
    }

    private void retarget(BlockPos newTarget) {
        this.targetVillage = newTarget;

        resetStuckState(); // ok as long as we re-init below

        int near;
        if (mob instanceof EntityTravellerWorker && ((EntityTravellerWorker) mob).isCatchupTravel()) {
            near = 6;
        } else {
            near = mob.arrivalRadiusBlocks(newTarget);
        }
        this.targetNearSq = near * near;

        // Initialize movement/progress baselines
        lastMovePos = mob.getPositionVector();
        moveWindowStart = lastMovePos;
        moveWindowTicks = 0;
        moveWindowInit = true;
        noMoveTicks = 0;

        double d2 = horizDistSq(mob.getPosition(), newTarget);
        bestDistSq = d2;
        progressCheckpointSq = d2;
        noProgressTicks = 0;

        stuckTicks = 0;
        stuckStage = 0;
        stuckActionCooldown = 0;
        stuckTpCooldown = 0;
        undergroundTicks = 0;

        this.triedBoatThisLeg = false;
    }

    private void resetStuckState() {
        progressCheckpointSq = Double.MAX_VALUE;
        progressCheckpointSq = (targetVillage != null) ? horizDistSq(mob.getPosition(), targetVillage) : Double.MAX_VALUE;
        bestDistSq = progressCheckpointSq;

        stuckTicks = 0;
        stuckStage = 0;
        stuckActionCooldown = 0;
        stuckTpCooldown = 0;

        lastMovePos = mob.getPositionVector();
        noMoveTicks = 0;

// also reset the 1-second window tracker (you currently don't)
        moveWindowStart = lastMovePos;
        moveWindowTicks = 0;
        moveWindowInit = true;

        lastPathIndex = -1;
        pathNoAdvanceTicks = 0;

    }

    private static double horizDistSq(BlockPos a, BlockPos b) {
        double dx = (a.getX() + 0.5) - (b.getX() + 0.5);
        double dz = (a.getZ() + 0.5) - (b.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private void pushPathToWaypoint(boolean force) {
        // Never pathfind when nobody is tracking us.
        // For hired catchup workers, use the actual tracking count — the approximate
        // view-distance check has a margin that falsely blocks pathfinding in the
        // "dead zone" where chunks aren't actually loaded.
        if (!mob.world.isRemote) {
            boolean skipPathfinding;
            if (mob instanceof EntityTravellerWorker) {
                EntityTravellerWorker w = (EntityTravellerWorker) mob;
                if (w.isHiredFollower() && w.isCatchupTravel()) {
                    skipPathfinding = w.getTrackingPlayerCount() <= 0
                            && !w.isInPlayerViewDistance();
                } else {
                    skipPathfinding = !mob.isTrackedByAnyPlayer();
                }
            } else {
                skipPathfinding = !mob.isTrackedByAnyPlayer();
            }
            if (skipPathfinding) {
                lastSchedulerDenyTick = mob.ticksExisted;
                return;
            }
        }
        if (this.currentWaypoint == null) return;
        // --- NEW: throttle travel path computations (avoid stop-go by NOT clearing path if denied) ---
        if (!canComputeTravelPath(force)) return;

        boolean stuckish =
                (pathNoAdvanceTicks >= 40) || (noMoveTicks >= 40);

        int priority = 0;
        if (mob.getNavigator().noPath()) priority = 2;     // urgent (otherwise freeze)
        else if (stuckish) priority = 1;

        // Ask the global scheduler if we can path this tick
        if (!TravelPathScheduler.tryAcquire(mob.world, mob.getEntityId(), priority)) {

            // If we have no path, this denial means we will stand still.
            // Record it so updateTask() won't escalate stuck/teleport just because we're throttled.
            if (mob.getNavigator().noPath()) {
                lastSchedulerDenyTick = mob.ticksExisted;
            }

            // IMPORTANT: do NOT clear path here — keep walking the old path smoothly
            return;
        }

// we got a slot; clear any "denied" mark
        lastSchedulerDenyTick = -999999;


        // Per-mob cooldown so this mob doesn't immediately ask again next tick
        nextTravelSlotTick = mob.ticksExisted + TRAVEL_PATH_COOLDOWN_TICKS;

        // --- NEW: throttle expensive path computations ---
        if (!canComputeNewPath(force)) {
            return;
        }


        // If we're in a boat, don't use Navigator; steer manually.
        if (mob.isRiding() && mob.getRidingEntity() instanceof EntityBoat) {
            steerBoatToWaypoint();
            return;
        }

        // normal land/water navigation
        Path current = mob.getNavigator().getPath();
        if (!force && current != null) {
            PathPoint dest = current.getFinalPathPoint();
            if (dest != null) {
                if (Math.abs(dest.x - currentWaypoint.getX()) <= 1 &&
                        Math.abs(dest.z - currentWaypoint.getZ()) <= 1) return;
            }
        }

        double navSpeed = getActiveSpeed();
        boolean ok = mob.getNavigator().tryMoveToXYZ(
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY(),
                currentWaypoint.getZ() + 0.5,
                navSpeed
        );

// throttle next path attempt
        nextComputePathTick = mob.ticksExisted + PATH_COOLDOWN_TICKS;

        if (ok) {
            lastPathIndex = -1;
            pathNoAdvanceTicks = 0;
        }


// NEW: if pathfinder refuses, immediately pick a nearer waypoint
        if (!ok && targetVillage != null) {
            // shrink the hop and try again (prevents "stand still until TP")
            int old = this.stepBlocks;
            this.stepBlocks = Math.max(12, old / 2);

            BlockPos retry = computeNextWaypointToward(targetVillage, this.stepBlocks);
            this.stepBlocks = old;

            if (retry != null) {
                this.currentWaypoint = retry;
                mob.setCurrentWaypoint(retry);
                debugMark(retry);

                mob.getNavigator().tryMoveToXYZ(
                        retry.getX() + 0.5,
                        retry.getY(),
                        retry.getZ() + 0.5,
                        navSpeed
                );
            }
        }
        if (pathNoAdvanceTicks >= 8 * 20) { // 8s stuck on same node (or no path)
            clearNav();

            // Pick a new waypoint, but don't re-enter pushPathToWaypoint recursively.
            tryDetourWide();

            // Reset the trigger so we don't immediately re-trigger the same branch this tick.
            pathNoAdvanceTicks = 0;
            lastPathIndex = -1;

            stuckActionCooldown = STUCK_ACTION_CD;
            detourGraceTicks = DETOUR_GRACE_TICKS;

            // Let updateTask() call pushPathToWaypoint again on the next recalc tick (or next tick).
        }

    }

    private void onArrivedAtVillage() {
        // do NOT clear targetVillage here; route manager uses it to align with Roam
        this.currentWaypoint = null;
        mob.setCurrentWaypoint(null);
        clearNav();
        if (isInBoat()) {
            dismountAndDeleteBoat();
        }
        triedBoatThisLeg = false;
        // Let Roam start this tick
        // (Roam’s shouldExecute() will pass now because distance ≤ VILLAGE_NEAR)
    }

    /**
     * Compute a "next" waypoint toward 'dest' that is inside currently loaded chunks.
     * Does not force-load anything. Returns null if no safe waypoint is found.
     * Now tries straight toward the target first, then small symmetric detours.
     */
    private BlockPos computeNextWaypointToward(BlockPos dest, int baseStepIgnored) {
        if (dest == null) return null;

        if (shouldUseHeightAwareCatchup(dest)) {
            BlockPos wp = computeHeightAwareWaypointToward(dest);
            if (wp != null) {
                return wp;
            }
        }

        BlockPos from = mob.getPosition();
        Vec3d destVec = new Vec3d(dest.getX() - from.getX(), 0, dest.getZ() - from.getZ());
        if (destVec.lengthSquared() < 1.0) return null;

        Vec3d dir = destVec.normalize();

        int baseStep = dynamicStep(from, dest);
        int step = Math.min(
                baseStep + mob.getPathStepJitter(),
                (int) Math.sqrt(mob.getDistanceSqToCenter(dest))
        );
        step = Math.max(8, step);

        boolean boatMode = isInBoat();

// ✅ Only clamp to navigator range when NOT in a boat
        if (!boatMode) {
            int navRange = (int) Math.max(16.0, mob.getNavigator().getPathSearchRange());
            step = Math.min(step, navRange - 8);
            step = Math.max(8, step);
        } else {
            // ✅ Boat steering is manual; ensure we can actually pick a waypoint
            step = Math.max(16, step);
        }


        // --- BOAT MODE PATHING ---
        if (boatMode) {
            double distToDest = Math.sqrt(from.distanceSq(dest));

            // 1) If close enough to destination, try to find a shore exit and leave the boat.
            if (distToDest <= BOAT_EXIT_RADIUS) {
                BlockPos shore = findDrySurfaceNear(dest);
                if (shore != null) {
                    // exit boat and resume normal land nav
                    dismountAndDeleteBoat();
                    this.triedBoatThisLeg = false; // allow boat again on a future long leg if needed
                    return shore;
                }
            }

            // 2) Otherwise, pick a water waypoint using your water-specific helper.
            BlockPos best = null;

            for (int s = step; s >= 16; s -= 8) {
                BlockPos cand = surfaceAtOffsetWater(from, dir, s);
                if (cand != null) {
                    best = cand;
                    break;
                }
            }

            // 3) Small detours in water if straight fails
            if (best == null) {
                int detStep = Math.max(16, step / 2);
                int[] detAngles = new int[]{30, -30, 60, -60, 90, -90};
                for (int angleDeg : detAngles) {
                    Vec3d r = rotateY(dir, Math.toRadians(angleDeg));
                    BlockPos cand = surfaceAtOffsetWater(from, r, detStep);
                    if (cand != null) {
                        best = cand;
                        break;
                    }
                }
            }

            if (best != null) {
                return best; // stay in water between waypoints
            }

            // 4) If we completely fail to find water waypoint, as a last resort try to exit boat
            BlockPos shore = findDrySurfaceNear(from);
            if (shore != null) {
                dismountAndDeleteBoat();
                this.triedBoatThisLeg = false;
                return shore;
            }

            // give up for now
            return null;
        }

        // --- MPB road-following ---
        if (com.jubitus.travellers.utils.world.MPBIntegration.PRESENT && mob.world instanceof WorldServer) {
            WorldServer ws = (WorldServer) mob.world;
            int roadStep = Math.max(16, Math.min(step, (int) Math.sqrt(mob.getDistanceSqToCenter(dest)) - 4));
            int searchR = (mob.getDistanceSqToCenter(dest) > 64 * 64) ? 22 : 14;
            // API returns the road block pos (solid); feet = one block above
            BlockPos roadBlock = com.jubitus.travellers.utils.world.MPBIntegration.findNextRoadWaypoint(
                    ws, from.getX(), from.getZ(), dest.getX(), dest.getZ(), roadStep, searchR);
            if (roadBlock != null) {
                BlockPos roadFeet = roadBlock.up();
                if (isAreaLoaded(roadFeet, 1) && isNavigable(roadFeet)) {
                    return roadFeet;
                }
            }
        }

        BlockPos bestPath = null;
        // --- 1) straight candidates, no lane offset ---
        for (int s = step; s >= 16; s -= 16) {
            BlockPos candidate = surfaceAtOffset(from, dir, s, Vec3d.ZERO);
            if (candidate != null) {
                candidate = preferShoreWhenInWater(from, candidate);

                if (candidate != null) {
                    if (isPathBelow(candidate)) {
                        // First time we see a path tile, remember it but keep scanning
                        if (bestPath == null) {
                            bestPath = candidate;
                        }
                    } else {
                        // Non-path candidate: keep it as a fallback if we never saw a path
                        if (bestPath == null) {
                            bestPath = candidate;
                        }
                    }
                }

                if (bestPath != null) {
                    return bestPath;
                }
            }
        }

        // --- 2) small symmetric detours, still no lane offset ---
        int detStep = Math.max(12, step / 2);
        int[] detAngles = new int[]{30, -30, 60, -60, 90, -90};

        for (int angleDeg : detAngles) {
            Vec3d r = rotateY(dir, Math.toRadians(angleDeg));
            BlockPos candidate = surfaceAtOffset(from, r, detStep, Vec3d.ZERO);
            if (candidate != null) {
                candidate = preferShoreWhenInWater(from, candidate);
                return candidate;
            }
        }

        // --- 3) tiny random reseed (unchanged) ---
        Random rand = mob.getRNG();
        for (int i = 0; i < 6; i++) {
            int nx = from.getX() + rand.nextInt(9) - 4;
            int nz = from.getZ() + rand.nextInt(9) - 4;
            BlockPos guess = new BlockPos(nx, from.getY(), nz);
            BlockPos surface = findSurfaceOrWaterY(guess);
            if (surface != null && isAreaLoaded(surface, 1) && isNavigable(surface)) {
                return surface;
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
        logState("DETOUR_SMALL_START", "from=" + from.getX() + "," + from.getY() + "," + from.getZ());
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
                mob.setCurrentWaypoint(surface);
                debugMark(this.currentWaypoint);
                logState("DETOUR_SMALL_PICK", "cand=" + surface.getX() + "," + surface.getY() + "," + surface.getZ());
                return;
            }
        }

        // Last resort: pick a tiny random step in loaded area
        Random r = mob.getRNG();
        for (int i = 0; i < 6; i++) {
            int nx = from.getX() + r.nextInt(9) - 4;
            int nz = from.getZ() + r.nextInt(9) - 4;
            BlockPos surface = findSurfaceOrWaterY(new BlockPos(nx, from.getY(), nz));
            if (surface != null
                    && isAreaLoaded(surface, 1)
                    && isNavigable(surface)
                    && acceptDetourCandidate(surface, from)) {
                this.currentWaypoint = surface;
                mob.setCurrentWaypoint(surface);
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
        if (isSwimmable(top)) {
            return snapToWaterSurface(top);
        }
        if (isStandable(top)) {
            // If we're on ground (not swimming), avoid tree canopies
            if (!mob.isInWater()) {
                return adjustTreeTopToGround(top);
            }
            return top;
        }

        // 2. Small upward probe (bridges, slabs under ceilings, leaf canopies)
        final int actualMaxY = world.getActualHeight() - 1;
        BlockPos p = top;
        for (int i = 0; i < 3 && p.getY() < actualMaxY; i++) {
            p = p.up();
            if (isSwimmable(p)) {
                return snapToWaterSurface(p);
            }
            if (isStandable(p)) {
                // If p itself is a snow layer, treat feet as the AIR block above it.
                if (world.getBlockState(p).getBlock() == Blocks.SNOW_LAYER) {
                    BlockPos airAbove = p.up();
                    if (isAreaLoaded(airAbove, 1) && isStandable(airAbove)) return airAbove;
                }
                if (!mob.isInWater()) return adjustTreeTopToGround(p);
                return p;
            }
        }

        // 3. Deeper downward probe (valleys, shallow water tops, snow layers)
        p = top;
        for (int dy = 0; dy < 12 && p.getY() > 1; dy++) {
            p = p.down();
            if (isSwimmable(p)) {
                return snapToWaterSurface(p);
            }
            if (isStandable(p)) {
                if (!mob.isInWater()) {
                    return adjustTreeTopToGround(p);
                }
                return p;
            }
        }

        return null;
    }

    /**
     * Chunk-safe "isAreaLoaded" that never loads chunks.
     */
    private boolean isAreaLoaded(BlockPos pos, int radius) {
        BlockPos from = pos.add(-radius, -radius, -radius);
        BlockPos to = pos.add(radius, radius, radius);

        // allowLoading = true → will load missing chunks
        return mob.world.isAreaLoaded(from, to, false);
    }

    private boolean isNavigable(BlockPos feet) {
        return isStandable(feet) || isSwimmable(feet);
    }

    /**
     * “Can the mob stand with feet at `feet`?” (land version)
     */
    private boolean isStandable(BlockPos feet) {
        World world = mob.world;


        if (!isBlockSafeLoaded(world, feet) ||
                !isBlockSafeLoaded(world, feet.up()) ||
                !isBlockSafeLoaded(world, feet.down())) {
            return false;
        }

        final IBlockState below = world.getBlockState(feet.down());
        final IBlockState atFeet = world.getBlockState(feet);
        final IBlockState atHead = world.getBlockState(feet.up());

        if (isLeafBlock(world, feet.down(), below)) return false;

        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) return false;
        if (!supportsStandingSurface(world, feet.down(), below)) return false;


        boolean feetOk =
                atFeet.getBlock() == Blocks.SNOW_LAYER
                        || atFeet.getMaterial().isReplaceable()
                        || atFeet.getCollisionBoundingBox(world, feet) == null;

        boolean headOk =
                atHead.getMaterial().isReplaceable()
                        || atHead.getCollisionBoundingBox(world, feet.up()) == null
                        || atHead.getBlock() == Blocks.SNOW_LAYER;

        return feetOk && headOk;
    }

    private boolean isSwimmable(BlockPos feetPos) {
        World world = mob.world;
        if (!world.isBlockLoaded(feetPos) ||
                !world.isBlockLoaded(feetPos.up()) ||
                !world.isBlockLoaded(feetPos.down())) {
            return false;
        }

        IBlockState feet = world.getBlockState(feetPos);
        IBlockState head = world.getBlockState(feetPos.up());
        IBlockState below = world.getBlockState(feetPos.down());

        // Feet must be vanilla water
        if (!isVanillaWater(feet)) return false;

        // Absolutely forbid any non-water liquids / quicksand / lava
        if (isForbiddenLiquid(feet) || isForbiddenLiquid(head) || isForbiddenLiquid(below)) {
            return false;
        }

        // Head can be air OR water; just don't allow solid, non-replaceable stuff there.
        // solid block right in front of face → not a swimming cell
        return head.getMaterial().isReplaceable() || head.getMaterial().isLiquid();

        // If you really want to forbid "head in lava / mod fluids", that’s already covered by isForbiddenLiquid above.
    }

    private void tryDetourWide() {
        if (this.targetVillage == null) return;
        BlockPos from = mob.getPosition();
        logState("DETOUR_WIDE_START", "from=" + from.getX() + "," + from.getY() + "," + from.getZ());
        Vec3d to = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return;

        Vec3d base = to.normalize();
        int follow = (int) Math.floor(mob.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getAttributeValue());
        int maxStep = Math.max(6, follow - 2);               // never ask pathfinder beyond follow range
        int detStep = Math.min(Math.max(24, this.stepBlocks), maxStep);

        int[] ANG = new int[]{120, -120, 150, -150, 180, 90, -90, 60, -60, 30, -30};

        for (int angleDeg : ANG) {
            Vec3d dir = rotateY(base, Math.toRadians(angleDeg));
            BlockPos candidate = surfaceAtOffset(from, dir, detStep,
                    new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getPathLaneOffset() * 0.5));

            if (acceptDetourCandidate(candidate, from)) {
                this.currentWaypoint = candidate;
                mob.setCurrentWaypoint(candidate);
                debugMark(this.currentWaypoint);
                logState("DETOUR_WIDE_PICK", "ang=" + angleDeg + ",step=" + detStep);
                return;
            }
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

    @Nullable
    private BlockPos preferShoreWhenInWater(BlockPos from, BlockPos candidateTowardTarget) {
        return preferShoreWhenInWater(from, candidateTowardTarget, /*preferExit=*/true);
    }

    /**
     * If preferExit==true and we're in water, nudge the candidate to a nearby rim.
     * If preferExit==false, keep the candidate as-is (used for long water crossings).
     */
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

        // NEW: only try same-Y exit, don’t force +1 steps from deep water
        BlockPos forwardSameY = new BlockPos(nx, c.getY(), nz);
        if (isStandable(forwardSameY)) return forwardSameY;

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
            int nx = wx + (int) Math.round(dir.x * s);
            int nz = wz + (int) Math.round(dir.z * s);
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

    /**
     * Try a water-favoring surface pick (keeps you in water lanes if present).
     */
    @Nullable
    private BlockPos surfaceAtOffsetWater(BlockPos from, Vec3d dir, int s) {
        int nx = from.getX() + (int) Math.round(dir.x * s);
        int nz = from.getZ() + (int) Math.round(dir.z * s);
        BlockPos guess = new BlockPos(nx, from.getY(), nz);

        BlockPos surf = findSurfaceOrWaterY(guess);
        if (surf == null || !isAreaLoaded(surf, 1)) return null;

        if (isVanillaWater(mob.world.getBlockState(surf))) {
            BlockPos snapped = snapToWaterSurface(surf);
            if (snapped != null) surf = snapped;
        }

        if (isSwimmable(surf)) return surf;
        if (isNavigable(surf)) return surf;
        return null;
    }

    /**
     * True if feet cell is solid/walkable land (not liquid).
     */
    private boolean isDryStandable(BlockPos feet) {
        return isStandable(feet) && !mob.world.getBlockState(feet).getMaterial().isLiquid();
    }

    /**
     * Try to get a land surface at/near XZ (without loading chunks).
     */
    @Nullable
    private BlockPos findDrySurfaceNear(BlockPos guess) {
        BlockPos surf = findSurfaceOrWaterY(guess);
        if (surf == null) return null;
        if (isDryStandable(surf)) return surf;

        // small lateral nudge to hop off pond tiles (±1 in a plus pattern)
        final int[][] off = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : off) {
            BlockPos n = new BlockPos(surf.getX() + d[0], surf.getY(), surf.getZ() + d[1]);
            if (!isAreaLoaded(n, 1)) continue;
            if (isDryStandable(n)) return n;
        }
        return null;
    }

    /**
     * Land-first variant of surfaceAtOffset; falls back to water only if allowed.
     */
    @Nullable
    private BlockPos surfaceAtOffsetDryFirst(BlockPos from, Vec3d dir, int s, Vec3d lateral, boolean allowWaterFallback) {
        int nx = from.getX() + (int) Math.round(dir.x * s + lateral.x);
        int nz = from.getZ() + (int) Math.round(dir.z * s + lateral.z);
        BlockPos g = new BlockPos(nx, from.getY(), nz);

        BlockPos dry = findDrySurfaceNear(g);
        if (dry != null && isAreaLoaded(dry, 1) && isStandable(dry)) return dry;

        if (!allowWaterFallback) return null;

        BlockPos wet = findSurfaceOrWaterY(g);
        if (wet != null && isAreaLoaded(wet, 1) && isSwimmable(wet)) return wet;
        return null;
    }

    // --- DEBUG ONLY ---
    private void debugMark(@Nullable BlockPos p) {
        if (!TravellersModConfig.debug) return;
        if (p == null) return;
        if (mob.world.isRemote) return;

        World w = mob.world;
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

    private void logState(String event, String note) {
        if (!TravellersModConfig.debug) {
        } // or a new flag e.g. debugTravelLog
    }

    /**
     * True if this block is likely part of a natural tree (log or leaf).
     * We still allow standing on wood floors for buildings – this is just a heuristic,
     * used only when we ask for "ground-ish" surface for waypoints.
     */
    private boolean isTreeSupportBlock(World w, BlockPos pos, IBlockState s) {
        Block b = s.getBlock();

        // Vanilla & basic mod detection first
        if (b instanceof net.minecraft.block.BlockLog) return true;
        if (b instanceof net.minecraft.block.BlockLeaves) return true;

        // Millénaire / Modded overrides (instance methods)
        try {
            if (b.isLeaves(s, w, pos)) return true;
        } catch (Throwable ignored) {
        }

        try {
            if (b.isWood(w, pos)) return true;
        } catch (Throwable ignored) {
        }

        // Fallback on material
        Material m = s.getMaterial();
        return (m == Material.WOOD || m == Material.LEAVES);
    }

    /**
     * If 'standPos' is standing on a tree trunk/canopy, try to push it down to the
     * first "ground-like" solid below (dirt/stone/grass/sand/etc) with free headroom.
     * If nothing suitable is found, returns the original standPos.
     */
    private BlockPos adjustTreeTopToGround(BlockPos standPos) {
        World w = mob.world;

        BlockPos below = standPos.down();
        if (!w.isBlockLoaded(below)) return standPos;

        IBlockState under = w.getBlockState(below);
        if (!isTreeSupportBlock(w, below, under)) {
            // Already standing on something non-tree-like
            return standPos;
        }

        // Scan downward looking for a "ground" support
        BlockPos p = below;
        for (int i = 0; i < 16 && p.getY() > 1; i++) {
            p = p.down();
            if (!w.isBlockLoaded(p) || !w.isBlockLoaded(p.up())) break;

            IBlockState ground = w.getBlockState(p);
            Material m = ground.getMaterial();

            // "Ground-ish" materials – tweak to taste
            if (!isNaturalGroundSupport(p)) continue;

            BlockPos feet = p.up();
            // reuse your nav test
            if (isStandable(feet)) {
                return feet;
            }
        }

        // No better ground found, keep the original
        return standPos;
    }

    private boolean isPathBelow(BlockPos feet) {
        BlockPos below = feet.down();
        if (!mob.world.isBlockLoaded(below)) return false;
        IBlockState st = mob.world.getBlockState(below);
        return st.getBlock() instanceof IBlockPath;
    }

    /**
     * Compute a dynamic step size:
     * - Far from the village: use the full stepBlocks (e.g. 64).
     * - Closer in: shrink down so we can thread gates/streets precisely.
     */
    private int dynamicStep(BlockPos from, BlockPos dest) {
        double dist = Math.sqrt(from.distanceSq(dest));

        // How close is “arrival-ish”?
        // We already have targetNearSq from retarget(), so use that to define a “near” radius.
        double nearRadius = Math.max(24.0, Math.sqrt(this.targetNearSq) * 2.0); // start shrinking inside ~2× arrival radius
        double farRadius = 160.0;  // fully “far” beyond this (tweak as needed)

        int minStep = 12;              // smallest waypoint step near village
        int maxStep = this.stepBlocks; // e.g. 64

        // When a hired follower in catchup is within view distance, the MC 1.12
        // pathfinder must handle the navigation (no more offscreen hops).
        // Cap step to what the A* search can realistically solve.
        boolean inViewCatchup = false;
        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;
            if (w.isCatchupTravel() && w.isInPlayerViewDistance()) {
                inViewCatchup = true;
                maxStep = Math.min(maxStep, 24);
            }
        }

        // Compute interpolation factor t in [0,1]
        double t;
        if (dist <= nearRadius) {
            t = 0.0;         // at/inside near radius → minStep
        } else if (dist >= farRadius) {
            t = 1.0;         // far away → maxStep
        } else {
            t = (dist - nearRadius) / (farRadius - nearRadius);
        }

        int base = (int) Math.round(minStep + (maxStep - minStep) * t);

        // Never step farther than our actual distance, and never below minStep
        base = Math.min(base, (int) dist);
        base = Math.max(minStep, base);

        return base;
    }

    private boolean isInBoat() {
        return mob != null
                && mob.isRiding()
                && mob.getRidingEntity() instanceof EntityBoat; // covers vanilla + subclasses
        // or: instanceof EntityTravellerBoat if you ONLY ever ride your custom boat
    }

    @Nullable
    private EntityTravellerBoat currentBoat() {
        if (!isInBoat()) return null;
        Entity r = mob.getRidingEntity();
        return (r instanceof EntityTravellerBoat) ? (EntityTravellerBoat) r : null;
    }

    /**
     * True if it's worth spawning a boat: long-ish distance and a mostly-water corridor ahead.
     */
    private boolean shouldUseBoat(BlockPos from, BlockPos dest) {
        double dist = Math.sqrt(from.distanceSq(dest));

        boolean catchup = (mob instanceof EntityTravellerWorker)
                && ((EntityTravellerWorker) mob).isCatchupTravel();

        double min = catchup ? 12.0 : BOAT_MIN_DISTANCE;  // smaller for catchup
        if (dist < min) return false;

        Vec3d dir = new Vec3d(dest.getX() + 0.5 - (from.getX() + 0.5), 0, dest.getZ() + 0.5 - (from.getZ() + 0.5));
        if (dir.lengthSquared() < 1e-6) return false;
        dir = dir.normalize();

        return isMostlyWaterAhead(from, dir, BOAT_OCEAN_PROBE, BOAT_OCEAN_MIN_WATER);
    }

    private boolean tryEnterBoat(BlockPos from, Vec3d dir) {
        if (mob.world.isRemote) return false;
        if (isInBoat()) return true; // already in one

        World w = mob.world;

        // Find a surface spawn pos (air just above top water)
        BlockPos spawnPos = findBoatSurfaceSpawnPos(from, dir);
        if (spawnPos == null) {
            return false; // no suitable water column here
        }

        double spawnX = spawnPos.getX() + 0.5;
        double spawnZ = spawnPos.getZ() + 0.5;
        double spawnY = spawnPos.getY() + 0.02D; // tiny nudge above the surface

        EntityTravellerBoat boat =
                new EntityTravellerBoat(w, spawnX, spawnY, spawnZ);

        Vec3d spawnDir = (dir.lengthSquared() > 1e-6) ? dir.normalize()
                : new Vec3d(0, 0, 1);

        float yaw = yawFromDirection(spawnDir);

        boat.setLocationAndAngles(
                spawnX,
                spawnY,
                spawnZ,
                yaw,
                boat.rotationPitch
        );
        boat.rotationYaw = yaw;
        boat.prevRotationYaw = yaw;

        // Sync rider orientation
        mob.rotationYaw = yaw;
        mob.rotationYawHead = yaw;
        mob.renderYawOffset = yaw;

        boolean spawned = w.spawnEntity(boat);
        if (!spawned) {
            boat.setDead();            // or just let it GC, but setDead is fine here
            boatRetryCooldown = BOAT_RETRY_CD;
            return false;
        }

        if (!mob.startRiding(boat, true)) {
            // If mounting fails for any reason, remove the boat too
            boat.setDead();
            boatRetryCooldown = BOAT_RETRY_CD;
            return false;
        }

        clearNav();
        return true;
    }

    private void dismountAndDeleteBoat() {
        if (!isInBoat()) return;

        Entity r = mob.getRidingEntity();
        mob.dismountRidingEntity();

        if (r instanceof EntityTravellerBoat) {
            ((EntityTravellerBoat) r).requestKill(); // NOT setDead()
        }
    }

    private void steerBoatToWaypoint() {
        EntityTravellerBoat boat = currentBoat();
        if (boat == null || this.currentWaypoint == null) return;

        Vec3d pos = boat.getPositionVector();
        Vec3d target = new Vec3d(
                currentWaypoint.getX() + 0.5,
                boat.posY, // stay roughly at current Y, boat handles vertical bobbing
                currentWaypoint.getZ() + 0.5
        );
        Vec3d delta = target.subtract(pos);
        double d2 = delta.lengthSquared();
        if (d2 < 0.75 * 0.75) {
            // close enough; let updateTask reseed the next waypoint
            return;
        }


        Vec3d dir = delta.normalize();
        double boatSpeed = getActiveSpeed() * BOAT_SPEED_MULT;

        boat.motionX = dir.x * boatSpeed;
        boat.motionZ = dir.z * boatSpeed;
        // rotate the boat to face direction of travel
        float yaw = yawFromDirection(dir);

// Rotate boat
        boat.rotationYaw = yaw;
        boat.prevRotationYaw = yaw;

// Sync travellers
        mob.rotationYaw = yaw;
        mob.rotationYawHead = yaw;
        mob.renderYawOffset = yaw;


        // clear mob path so navigator doesn't fight us
        clearNav();
    }

    @Nullable
    private BlockPos findBoatWaterSurface(BlockPos xz) {
        World w = mob.world;
        if (!w.isBlockLoaded(xz)) return null;

        int maxY = w.getActualHeight() - 1;
        BlockPos pos = new BlockPos(xz.getX(), maxY, xz.getZ());

        // Scan downward until we hit water
        while (pos.getY() > 0) {
            IBlockState s = w.getBlockState(pos);
            if (isVanillaWater(s)) {
                // Climb up to the topmost contiguous water block
                while (pos.getY() < maxY && isVanillaWater(w.getBlockState(pos.up()))) {
                    pos = pos.up();
                }
                return pos; // this is the top water block at the surface
            }
            pos = pos.down();
        }
        return null;
    }

    // ---- Liquid filters: only vanilla water is allowed ----
    static boolean isVanillaWater(IBlockState s) {
        Block b = s.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER; // 1.12.2
    }

    private float yawFromDirection(Vec3d dir) {
        // Minecraft yaw: 0 = south (+Z), 90 = west (-X)
        // Standard conversion:
        // yaw = atan2(z, x) * 180/PI - 90
        double yawRad = Math.atan2(dir.z, dir.x);
        return (float) (yawRad * (180D / Math.PI) - 90.0D);
    }

    private boolean boatHitSurface(EntityTravellerBoat boat) {
        World w = mob.world;

        BlockPos boatCol = new BlockPos(
                MathHelper.floor(boat.posX),
                MathHelper.floor(boat.posY - 0.2D),
                MathHelper.floor(boat.posZ)
        );
        if (!w.isBlockLoaded(boatCol)) return false;

        IBlockState feet = w.getBlockState(boatCol);
        IBlockState below = w.getBlockState(boatCol.down());

        boolean feetWater = isVanillaWater(feet);
        boolean belowWater = isVanillaWater(below);

        // 1) If we’re not over water at all, we beached / hit land/ice
        if (!feetWater && !belowWater) {
            return true;
        }

        // 2) Otherwise check a bit ahead at the “waterline”
        Vec3d dir = new Vec3d(boat.motionX, 0, boat.motionZ);

        // If barely moving, aim toward waypoint (or fallback to yaw)
        if (dir.lengthSquared() < 1.0e-4) {
            if (currentWaypoint != null) {
                dir = new Vec3d(
                        currentWaypoint.getX() + 0.5 - boat.posX,
                        0,
                        currentWaypoint.getZ() + 0.5 - boat.posZ
                );
            }
            if (dir.lengthSquared() < 1.0e-6) {
                float yaw = boat.rotationYaw;
                double yawRad = Math.toRadians(yaw);
                dir = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
            }
        }

        dir = dir.normalize();

        // sample 1–2 blocks ahead, at waterline
        double sx = boat.posX + dir.x * 1.6D;
        double sz = boat.posZ + dir.z * 1.6D;
        double sy = boat.posY - 0.2D;

        BlockPos ahead = new BlockPos(
                MathHelper.floor(sx),
                MathHelper.floor(sy),
                MathHelper.floor(sz)
        );
        if (!w.isBlockLoaded(ahead)) return false;

        IBlockState aFeet = w.getBlockState(ahead);
        IBlockState aBelow = w.getBlockState(ahead.down());

        boolean aheadWater = isVanillaWater(aFeet) || isVanillaWater(aBelow);

        // If ahead column is not water, we’re about to grind shoreline
        return !aheadWater;
    }

    private boolean isDryStandableForBoatExit(BlockPos feet) {
        World w = mob.world;

        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down()))
            return false;

        IBlockState below = w.getBlockState(feet.down());
        if (below.getMaterial().isLiquid()) return false;

        IBlockState atFeet = w.getBlockState(feet);
        IBlockState atHead = w.getBlockState(feet.up());

        boolean free =
                (atFeet.getMaterial().isReplaceable() || atFeet.getCollisionBoundingBox(w, feet) == null)
                        && (atHead.getMaterial().isReplaceable() || atHead.getCollisionBoundingBox(w, feet.up()) == null);

        BlockFaceShape shape = below.getBlockFaceShape(w, feet.down(), EnumFacing.UP);
        boolean solidBelow =
                shape == BlockFaceShape.SOLID
                        || below.getBlock() == Blocks.SNOW_LAYER
                        || below.getBlock() == Blocks.GRASS_PATH
                        || below.getBlock() == Blocks.ICE
                        || below.getBlock() == Blocks.PACKED_ICE;

        return free && solidBelow;
    }

    private void handleBoatHitSurface(EntityTravellerBoat boat) {
        World w = mob.world;
        BlockPos boatPos = new BlockPos(
                MathHelper.floor(boat.posX),
                MathHelper.floor(boat.posY),
                MathHelper.floor(boat.posZ)
        );

        // Try to find a dry standable block near where we hit
        BlockPos shore = findDrySurfaceNear(boatPos);
        // 1) Dismount + delete boat no matter what
        dismountAndDeleteBoat();
        wasInWater = false;
        boatRetryCooldown = 0; // optional: allow immediate re-boat if still water
        triedBoatThisLeg = false;

        // 2) If we found a shore tile, snap next waypoint to it
        if (shore != null) {
            this.currentWaypoint = shore;
            mob.setCurrentWaypoint(shore);
            debugMark(shore);
            clearNav();
            pushPathToWaypoint();
        } else {
            // fallback: just let travellers swim / walk from wherever he ends up
            clearNav();
        }
    }

    /**
     * Returns true if the straight XZ corridor from 'from' to 'to' is "pure water":
     * - every sampled column has a water surface (top block is vanilla water)
     * - does not force-load chunks.
     */
    private boolean isWaterCorridorClear(BlockPos from, BlockPos to) {
        World w = mob.world;

        double dx = to.getX() + 0.5 - (from.getX() + 0.5);
        double dz = to.getZ() + 0.5 - (from.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return false;

        int steps = (int) Math.ceil(dist); // ~1 sample per block

        double stepx = dx / steps;
        double stepz = dz / steps;

        double x = from.getX() + 0.5;
        double z = from.getZ() + 0.5;

        for (int i = 1; i <= steps; i++) {
            int sx = MathHelper.floor(x + stepx * i);
            int sz = MathHelper.floor(z + stepz * i);
            BlockPos col = new BlockPos(sx, from.getY(), sz);

            // never load chunks
            if (!isAreaLoaded(col, 1)) return false;

            // Use your existing "findSurfaceOrWaterY" to find the top navigable cell
            BlockPos surf = findSurfaceOrWaterY(col);
            if (surf == null) return false;

            IBlockState s = w.getBlockState(surf);

            // corridor is only valid if the *surface* is vanilla water
            if (!isVanillaWater(s)) {
                return false; // solid/ice/etc. -> obstacle
            }
        }
        return true;
    }

    @Nullable
    private BlockPos pickFirstBoatWaypoint(BlockPos from, BlockPos dest, Vec3d dir, int step) {
        BlockPos best = null;

        // Straight water candidates
        for (int s = step; s >= 16; s -= 16) {
            BlockPos cand = surfaceAtOffsetWater(from, dir, s);
            if (cand != null && isWaterCorridorClear(from, cand)) {
                best = cand;
                break;
            }
        }

        if (best != null) return best;

        // Small detours if straight failed
        int detStep = Math.max(16, step / 2);
        int[] detAngles = new int[]{30, -30, 60, -60, 90, -90};

        for (int angleDeg : detAngles) {
            Vec3d r = rotateY(dir, Math.toRadians(angleDeg));
            BlockPos cand = surfaceAtOffsetWater(from, r, detStep);
            if (cand != null && isWaterCorridorClear(from, cand)) {
                return cand;
            }
        }

        return null;
    }

    @Nullable
    private BlockPos findBoatSurfaceSpawnPos(BlockPos from, Vec3d dir) {
        World w = mob.world;

        // Decide which XZ column to use: current or one step in front
        BlockPos columnXZ = from;
        IBlockState feet = w.getBlockState(from);
        IBlockState below = w.getBlockState(from.down());

        if (!isVanillaWater(feet) && !isVanillaWater(below) && dir != null) {
            int stepX = (int) Math.signum(dir.x);
            int stepZ = (int) Math.signum(dir.z);
            if (stepX != 0 || stepZ != 0) {
                columnXZ = new BlockPos(from.getX() + stepX, from.getY(), from.getZ() + stepZ);
            }
        }

        int x = columnXZ.getX();
        int z = columnXZ.getZ();
        int startY = columnXZ.getY();
        int maxY = w.getActualHeight() - 1;
        int minY = 1;

        BlockPos topWater = null;

        // 1) Scan upwards: find the highest continuous vanilla water block
        for (int y = startY; y <= maxY; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!w.isBlockLoaded(p)) break;
            IBlockState s = w.getBlockState(p);

            if (isVanillaWater(s)) {
                topWater = p; // keep updating as we go up
            } else if (topWater != null) {
                // we left the water column; topWater is the surface water
                break;
            }
        }

        // 2) If we never saw water above, scan a bit downward
        if (topWater == null) {
            for (int y = startY - 1; y >= minY; y--) {
                BlockPos p = new BlockPos(x, y, z);
                if (!w.isBlockLoaded(p)) break;
                IBlockState s = w.getBlockState(p);
                if (isVanillaWater(s)) {
                    topWater = p;
                    break;
                }
            }
        }

        if (topWater == null) {
            // No vanilla water in this column → don't spawn a boat at all
            return null;
        }

        // We want the AIR block directly above the topmost water
        BlockPos spawnPos = topWater.up();

        // Safety: don't spawn inside a solid ceiling (e.g. underwater cave roof)
        IBlockState head = w.getBlockState(spawnPos);
        if (!head.getMaterial().isReplaceable()
                && head.getCollisionBoundingBox(w, spawnPos) != null) {
            return null;
        }

        return spawnPos;
    }

    private boolean isFarEnoughFromAnyPlayer() {
        double minDist = TravellersModConfig.travellerSimpleOffscreenMinPlayerDist;
        if (minDist <= 0) return true;

        EntityPlayer nearest = mob.world.getClosestPlayerToEntity(mob, minDist);
        return nearest == null;
    }

    private boolean isLikelyInCave() {
        BlockPos p = mob.getPosition();
        World w = mob.world;

        // NEW: if we can see the sky, it's not a cave (superflat fix)
        if (w.canSeeSky(p.up())) return false;

        // Still allow low-Y to count as cave, but only if it's covered
        if (p.getY() < 55) return true;

        int solid = 0;
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            IBlockState s = w.getBlockState(p.offset(f));
            if (s.getMaterial().isSolid()) solid++;
        }
        return solid >= 3;
    }

    private boolean isCaveLike(BlockPos p) {
        World w = mob.world;

        // If we can see sky, it's not a cave.
        if (w.canSeeSky(p.up())) return false;

        // Low skylight means underground/overhang.
        // (1.12) EnumSkyBlock.SKY is skylight only
        int sky = w.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, p.up());
        return sky < 8;
    }

    public boolean teleportToward(BlockPos target, int stepBlocks) {
        if (mob.world.isRemote) return false;
        if (target == null) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

        if (isInBoat()) dismountAndDeleteBoat();

        final BlockPos start = mob.getPosition();
        final Vec3d fwd = goalDirXZ(mob, target);
        final double curD2 = horizDistSq(start, target);

        final double MIN_FORWARD = 1.5; // blocks
        final double MAX_BACKSLIDE = 4.0;
        final double MAX_BACKSLIDE_SQ = MAX_BACKSLIDE * MAX_BACKSLIDE;
        final int LOAD_RADIUS = 2;

        Vec3d to = new Vec3d(target.getX() - start.getX(), 0, target.getZ() - start.getZ());
        if (to.lengthSquared() < 1.0) return false;

        Vec3d dir = to.normalize();
        Vec3d right = new Vec3d(-dir.z, 0, dir.x);

        int lateralBase = Math.max(4, stepBlocks / 2);
        int[] lateral = new int[]{0, +lateralBase, -lateralBase, +lateralBase * 2, -lateralBase * 2};

        BlockPos best = null;
        double bestScore = -1e18;

        for (int lat : lateral) {
            int nx = start.getX() + (int) Math.round(dir.x * stepBlocks + right.x * lat);
            int nz = start.getZ() + (int) Math.round(dir.z * stepBlocks + right.z * lat);

            BlockPos guess = new BlockPos(nx, start.getY(), nz);
            if (!isAreaLoaded(guess, LOAD_RADIUS)) continue;

            BlockPos surf = findSurfaceOrWaterY(guess);
            if (surf == null) continue;

            // ✅ If candidate is water, snap it to the TOP of that water column
            if (isSwimmable(surf)) {
                BlockPos top = snapToWaterSurface(surf);
                if (top == null) continue; // no safe headroom / not loaded
                surf = top;
            }


            boolean swimmable = isSwimmable(surf);

// Only require "solid support" for LAND targets.
// If it's water, allow it (we will "land" as swimming).
            if (!swimmable) {
                boolean okSupport = isNaturalGroundSupport(surf.down());
                if (!okSupport) {
                    IBlockState sup = mob.world.getBlockState(surf.down());
                    okSupport = supportsStandingSurface(mob.world, surf.down(), sup);
                }
                if (!okSupport) continue;

                // Only do tree-top correction for land
                surf = adjustTreeTopToGround(surf);
            }

// Must still be navigable (land OR water)
            if (!isNavigable(surf)) continue;


            double landY = candidateLandingY(surf);

            AxisAlignedBB aabb = mob.getEntityBoundingBox().offset(
                    (surf.getX() + 0.5) - mob.posX,
                    landY - mob.posY,
                    (surf.getZ() + 0.5) - mob.posZ
            );

            if (!isAabbAreaLoaded(aabb)) continue;
            if (!mob.world.getCollisionBoxes(mob, aabb).isEmpty()) continue;
            if (intersectsForbiddenLiquid(mob.world, aabb)) continue;


            // forward + backslide gating
            double sx = (surf.getX() + 0.5) - (start.getX() + 0.5);
            double sz = (surf.getZ() + 0.5) - (start.getZ() + 0.5);
            Vec3d step = new Vec3d(sx, 0, sz);

            double forward = step.dotProduct(fwd);
            if (forward < MIN_FORWARD) continue;

            double candD2 = horizDistSq(surf, target);
            if (candD2 > curD2 + MAX_BACKSLIDE_SQ) continue;

            double score = (forward * 10.0) - (candD2 * 0.001) - (lat * lat * 0.01);
            if (score > bestScore) {
                bestScore = score;
                best = surf;
            }
        }

        if (best == null) return false;

        double y = isSwimmable(best) ? (best.getY() + 0.02D) : teleportLandingY(best);
        mob.setPositionAndUpdate(best.getX() + 0.5, y, best.getZ() + 0.5);
        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;
        clearNav();
        return true;
    }

    public boolean teleportToSurfaceRescue(int radius, int minRise) {
        if (mob.world.isRemote) return false;

        World w = mob.world;
        BlockPos origin = mob.getPosition();
        int minY = origin.getY() + minRise;

        // If no target, keep old “closest wins”
        final boolean hasTarget = (targetVillage != null);

        Vec3d fwd = hasTarget ? goalDirXZ(mob, targetVillage) : new Vec3d(0, 0, 1);
        double curD2 = hasTarget ? horizDistSq(origin, targetVillage) : 0.0;

        // Two buckets:
        //  - bestForward: must be forward-ish toward target
        //  - bestAny: any valid surface (fallback if no forward exists)
        BlockPos bestForward = null;
        double bestForwardScore = -1e18;

        BlockPos bestAny = null;
        double bestAnyScore = Double.MAX_VALUE; // fallback uses “closest” like before

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {

                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;

                BlockPos probe = new BlockPos(x, origin.getY(), z);
                if (!isAreaLoaded(probe, 1)) continue;

                BlockPos top = w.getTopSolidOrLiquidBlock(probe);

                if (top.getY() < minY) continue;

                BlockPos feet = adjustTreeTopToGround(top);
                if (!isStandable(feet)) continue;
                boolean okSupport = isNaturalGroundSupport(feet.down());
                if (!okSupport && stuckStage >= 2) {
                    // stage 3: allow man-made solids too (still reject leaves/liquids via isStandable())
                    IBlockState sup = mob.world.getBlockState(feet.down());
                    okSupport = supportsStandingSurface(mob.world, feet.down(), sup);
                }
                if (!okSupport) continue;

                double landY = candidateLandingY(feet);

                AxisAlignedBB aabb = mob.getEntityBoundingBox().offset(
                        (feet.getX() + 0.5) - mob.posX,
                        landY - mob.posY,
                        (feet.getZ() + 0.5) - mob.posZ
                );
                if (!isAabbAreaLoaded(aabb)) continue;

                if (!w.getCollisionBoxes(mob, aabb).isEmpty()) continue;
                if (intersectsForbiddenLiquid(w, aabb)) continue;


                // ---- Fallback (old behaviour): closest to origin ----
                double closestScore = feet.distanceSq(origin);
                if (closestScore < bestAnyScore) {
                    bestAnyScore = closestScore;
                    bestAny = feet;
                }

                if (!hasTarget) continue;

                // ---- PATCH C: prefer forward + closer-to-target ----
                double sx = (feet.getX() + 0.5) - (origin.getX() + 0.5);
                double sz = (feet.getZ() + 0.5) - (origin.getZ() + 0.5);
                double forward = new Vec3d(sx, 0, sz).dotProduct(fwd);

                double candD2 = horizDistSq(feet, targetVillage);

                // Hard reject: “significantly behind AND not closer”
                if (forward < -1.0 && candD2 >= curD2) continue;

                // Score: strongly prefer closer-to-target, then forward, then not too far from origin
                double score = -(candD2 * 0.01) + (forward * 5.0) - (closestScore * 0.001);

                if (score > bestForwardScore) {
                    bestForwardScore = score;
                    bestForward = feet;
                }
            }
        }

        BlockPos best = (bestForward != null) ? bestForward : bestAny;
        if (best == null) return false;

        if (isInBoat()) dismountAndDeleteBoat();

        double y = teleportLandingY(best);
        mob.setPositionAndUpdate(best.getX() + 0.5, y, best.getZ() + 0.5);

        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;
        clearNav();
        return true;
    }

    private boolean teleportToSurfaceRescue(int radius) {
        return teleportToSurfaceRescue(radius, 6);
    }

    private boolean acceptDetourCandidate(BlockPos cand, BlockPos from) {
        if (cand == null || targetVillage == null) return false;

        // Must be generally forward, not sideways/behind
        Vec3d g = goalDirXZ(mob, targetVillage); // normalized
        Vec3d d = new Vec3d(cand.getX() + 0.5 - (from.getX() + 0.5), 0,
                cand.getZ() + 0.5 - (from.getZ() + 0.5));

        double forward = d.dotProduct(g);
        if (forward < 1.0) return false; // reject sideways/behind

        // Don’t accept a waypoint that is clearly farther from target (allow tiny backslide)
        double cur = horizDistSq(from, targetVillage);
        double cd = horizDistSq(cand, targetVillage);
        double allowBackslideBlocks = 6.0;
        return !(cd > cur + allowBackslideBlocks * allowBackslideBlocks);
    }

    private boolean isUndergroundNow() {
        BlockPos p = mob.getPosition();
        World w = mob.world;

        // If we can see sky, definitely not underground
        if (w.canSeeSky(p.up())) return false;

        // If skylight is still high, we're outdoors under leaves/foliage, not in a cave
        int sky = w.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, p.up());
        if (sky >= 10) return false;

        // If the "roof" is close above us, it's probably a building/interior, not a cave
        BlockPos top = w.getTopSolidOrLiquidBlock(p);
        int roofDelta = top.getY() - p.getY();
        return roofDelta >= 8;

        // Otherwise: deep under something with low skylight -> cave-like
    }

    private boolean isNaturalGroundSupport(BlockPos supportPos) {
        World w = mob.world;
        if (!w.isBlockLoaded(supportPos)) return false;

        IBlockState s = w.getBlockState(supportPos);
        Block b = s.getBlock();

        // Never tree blocks
        if (b instanceof net.minecraft.block.BlockLog) return false;
        if (b instanceof net.minecraft.block.BlockLeaves) return false;
        try {
            if (b.isWood(w, supportPos)) return false;
        } catch (Throwable ignore) {
        }
        try {
            if (b.isLeaves(s, w, supportPos)) return false;
        } catch (Throwable ignore) {
        }

        // Reject "construction-ish" blocks by class
        if (b instanceof net.minecraft.block.BlockStairs) return false;
        if (b instanceof net.minecraft.block.BlockSlab) return false;
        if (b instanceof net.minecraft.block.BlockFence) return false;
        if (b instanceof net.minecraft.block.BlockWall) return false;
        if (b instanceof net.minecraft.block.BlockGlass) return false;

        // Reject by registry name keyword (catches stonebrick, mod bricks, etc.)
        ResourceLocation rl = b.getRegistryName();
        if (rl != null) {
            String p = rl.getPath();
            if (p.contains("brick") || p.contains("stonebrick") || p.contains("stairs") || p.contains("slab")
                    || p.contains("plank") || p.contains("wood") || p.contains("log")
                    || p.contains("cobble") || p.contains("wall") || p.contains("fence")) {
                return false;
            }
        }

        // Allow only “natural-ish” materials
        Material m = s.getMaterial();
        return (m == Material.GRASS
                || m == Material.GROUND
                || m == Material.SAND
                || m == Material.ROCK
                || m == Material.CLAY
                || m == Material.SNOW
                || m == Material.ICE);
    }

    private boolean isCliffAheadTowardTarget(int ahead, int drop) {
        if (targetVillage == null) return false;
        Vec3d pos = mob.getPositionVector();
        Vec3d dir = new Vec3d(targetVillage.getX() + 0.5 - pos.x, 0, targetVillage.getZ() + 0.5 - pos.z);
        if (dir.lengthSquared() < 1e-6) return false;
        dir = dir.normalize();

        BlockPos base = mob.getPosition();
        for (int i = 1; i <= ahead; i++) {
            int x = base.getX() + (int) Math.round(dir.x * i);
            int z = base.getZ() + (int) Math.round(dir.z * i);
            BlockPos p = new BlockPos(x, base.getY(), z);

            boolean allAir = true;
            for (int d = 0; d <= drop; d++) {
                if (!mob.world.isAirBlock(p.down(d))) {
                    allAir = false;
                    break;
                }
            }
            if (allAir) return true;
        }
        return false;
    }

    private void pushPathToWaypoint() {
        pushPathToWaypoint(false);
    }

    private double feetYOnTopOf(BlockPos ground) {
        IBlockState st = mob.world.getBlockState(ground);
        AxisAlignedBB bb = st.getCollisionBoundingBox(mob.world, ground);
        if (bb == null) return ground.getY();          // no collision box => not standable
        return ground.getY() + bb.maxY;                // top surface height (handles snow layers!)
    }

    private double teleportLandingY(BlockPos feetPos) {
        World w = mob.world;
        IBlockState atFeet = w.getBlockState(feetPos);

        // If we're standing "in" a thin block (snow layer), stand on top of it.
        if (atFeet.getBlock() == Blocks.SNOW_LAYER) {
            return feetYOnTopOf(feetPos); // uses that layer's collision height
        }

        // Normal case: feetPos is air/replaceable; stand on the block below.
        return feetYOnTopOf(feetPos.down());
    }

    private double candidateLandingY(BlockPos feetPos) {
        return isSwimmable(feetPos) ? (feetPos.getY() + 0.02D) : teleportLandingY(feetPos);
    }

    // Finds a dry standable tile near us, biased forward toward target, and teleports there.
// radius = XZ scan radius, minForward = require at least this many blocks forward (soft).
    public boolean teleportToNearestShoreTowardTarget(int radius, int minForward) {
        if (mob.world.isRemote) return false;
        if (targetVillage == null) return false;

        BlockPos origin = mob.getPosition();
        Vec3d fwd = goalDirXZ(mob, targetVillage);

        BlockPos best = null;
        double bestScore = -1e18;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {

                BlockPos probe = origin.add(dx, 0, dz);
                if (!isAreaLoaded(probe, 1)) continue;

                BlockPos top = mob.world.getTopSolidOrLiquidBlock(probe);
                BlockPos feet = adjustTreeTopToGround(top);

                // We want DRY land only
                if (!isDryStandableForBoatExit(feet)) continue;

                double sx = (feet.getX() + 0.5) - (origin.getX() + 0.5);
                double sz = (feet.getZ() + 0.5) - (origin.getZ() + 0.5);
                Vec3d step = new Vec3d(sx, 0, sz);

                double forward = step.dotProduct(fwd);
                if (forward < -2.0) continue; // don't go backwards unless desperate

                // Optional soft forward preference
                double forwardBonus = Math.max(0.0, forward - minForward);

                double d2ToTarget = horizDistSq(feet, targetVillage);
                double dist2FromMe = sx * sx + sz * sz;

                // Score: prefer closer to target + forward + not too far
                double score = -(d2ToTarget * 0.01) + (forwardBonus * 5.0) - (dist2FromMe * 0.001);

                // Collision check at REAL landing Y (snow-safe)
                double landY = teleportLandingY(feet);
                AxisAlignedBB aabb = mob.getEntityBoundingBox().offset(
                        (feet.getX() + 0.5) - mob.posX,
                        landY - mob.posY,
                        (feet.getZ() + 0.5) - mob.posZ
                );
                if (!mob.world.getCollisionBoxes(mob, aabb).isEmpty()) continue;
                if (intersectsForbiddenLiquid(mob.world, aabb)) continue;

                if (score > bestScore) {
                    bestScore = score;
                    best = feet;
                }
            }
        }

        if (best == null) return false;

        if (isInBoat()) dismountAndDeleteBoat();

        double y = teleportLandingY(best);
        mob.setPositionAndUpdate(best.getX() + 0.5, y, best.getZ() + 0.5);
        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;
        clearNav();
        return true;
    }

    private boolean isAabbAreaLoaded(AxisAlignedBB aabb) {
        BlockPos min = new BlockPos(
                MathHelper.floor(aabb.minX),
                MathHelper.floor(aabb.minY),
                MathHelper.floor(aabb.minZ)
        );
        BlockPos max = new BlockPos(
                MathHelper.floor(aabb.maxX),
                MathHelper.floor(aabb.maxY),
                MathHelper.floor(aabb.maxZ)
        );
        return mob.world.isAreaLoaded(min, max, false); // false = never load
    }

    // If p is in vanilla water, move it up to the TOP of that water column (surface water block).
// Returns null if chunks aren't loaded or if head space is blocked.
    @Nullable
    private BlockPos snapToWaterSurface(BlockPos p) {
        World w = mob.world;
        if (!isAreaLoaded(p, 1)) return null;

        BlockPos q = p;

        // If we're not actually in water, don't try to "surface" it.
        if (!isVanillaWater(w.getBlockState(q))) return q;

        int maxY = w.getActualHeight() - 1;

        // Climb up through water until the next block is NOT water
        while (q.getY() < maxY && isVanillaWater(w.getBlockState(q.up()))) {
            q = q.up();
            if (!isAreaLoaded(q, 1)) return null;
        }

        // Make sure there is space above (so the mob isn't trying to swim into a ceiling)
        BlockPos head = q.up();
        if (!w.isBlockLoaded(head)) return null;

        IBlockState headState = w.getBlockState(head);
        if (!headState.getMaterial().isReplaceable()
                && headState.getCollisionBoundingBox(w, head) != null) {
            return null;
        }

        return q;
    }

    private boolean canComputeNewPath(boolean force) {
        if (force) return true;

        // allow if we truly have no path (otherwise mob stops)
        if (mob.getNavigator().noPath()) return true;

        // throttle normal repaths
        return mob.ticksExisted >= nextComputePathTick;
    }

    private boolean canComputeTravelPath(boolean force) {
        if (force) return true;

        // If we have NO path, we must compute one or we will stop.
        if (mob.getNavigator().noPath()) return true;

        // Per-mob cooldown (prevents the same mob asking every tick)
        return mob.ticksExisted >= nextTravelSlotTick;
    }
    private boolean canRepathNow(int priority) {
        // If we’re going to clear, we must be able to reacquire.
        return TravelPathScheduler.tryAcquire(mob.world, mob.getEntityId(), priority);
    }
    private boolean shouldUseHeightAwareCatchup(BlockPos dest) {
        if (!(mob instanceof EntityTravellerWorker)) return false;

        EntityTravellerWorker w = (EntityTravellerWorker) mob;
        if (!w.isCatchupTravel()) return false;

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        double vert = Math.abs(mob.getEntityBoundingBox().minY - dest.getY());
        boolean covered = isCoveredGoal(dest, cfg.cave.minCoverBlocks);

        if (covered) return true;
        return vert >= cfg.catchup.heightAwareVerticalGap;
    }

    private boolean isCoveredGoal(BlockPos pos, int minCover) {
        if (pos == null || !mob.world.isBlockLoaded(pos)) return false;
        if (mob.world.canSeeSky(pos)) return false;

        BlockPos top = mob.world.getTopSolidOrLiquidBlock(new BlockPos(pos.getX(), 0, pos.getZ()));
        return top != null && (top.getY() - pos.getY()) >= minCover;
    }

    @Nullable
    private BlockPos computeHeightAwareWaypointToward(BlockPos dest) {
        BlockPos from = mob.getPosition();
        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();

        Vec3d dir = new Vec3d(
                (dest.getX() + 0.5D) - (from.getX() + 0.5D),
                0.0D,
                (dest.getZ() + 0.5D) - (from.getZ() + 0.5D)
        );

        if (dir.lengthSquared() < 1.0D) {
            return pickBestHeightAwareCandidate(dest.getX(), dest.getZ(), dest.getY(), dest, from, isCoveredGoal(dest, cfg.cave.minCoverBlocks));
        }

        dir = dir.normalize();

        double dist = Math.sqrt(from.distanceSq(dest));
        int step = MathHelper.clamp(dynamicStep(from, dest), 8, 20);
        boolean preferCovered = isCoveredGoal(dest, cfg.cave.minCoverBlocks);

        for (int s = step; s >= 6; s -= 2) {
            double t = Math.min(1.0D, s / Math.max(1.0D, dist));
            int xBase = from.getX() + MathHelper.floor(dir.x * s);
            int zBase = from.getZ() + MathHelper.floor(dir.z * s);
            int yCenter = (int) Math.round(lerp(from.getY(), dest.getY(), Math.min(0.95D, t + 0.10D)));

            BlockPos candidate = pickBestHeightAwareCandidate(xBase, zBase, yCenter, dest, from, preferCovered);
            if (candidate != null) {
                return candidate;
            }
        }

        return pickBestHeightAwareCandidate(dest.getX(), dest.getZ(), dest.getY(), dest, from, preferCovered);
    }

    @Nullable
    private BlockPos pickBestHeightAwareCandidate(int xBase,
                                                  int zBase,
                                                  int yCenter,
                                                  BlockPos dest,
                                                  BlockPos from,
                                                  boolean preferCovered) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos feet = new BlockPos(xBase + ox, yCenter + dy, zBase + oz);
                    if (!isAreaLoaded(feet, 1)) continue;

                    if (isSwimmable(feet)) {
                        BlockPos snapped = snapToWaterSurface(feet);
                        if (snapped == null) continue;
                        feet = snapped;
                    } else {
                        if (!isStandable(feet)) continue;
                        feet = adjustTreeTopToGround(feet);
                        if (!isStandable(feet)) continue;

                        IBlockState below = mob.world.getBlockState(feet.down());
                        if (!isNaturalGroundSupport(feet.down()) && !supportsStandingSurface(mob.world, feet.down(), below)) {
                            continue;
                        }
                    }

                    if (!isNavigable(feet)) continue;

                    double landY = candidateLandingY(feet);
                    AxisAlignedBB aabb = mob.getEntityBoundingBox().offset(
                            (feet.getX() + 0.5D) - mob.posX,
                            landY - mob.posY,
                            (feet.getZ() + 0.5D) - mob.posZ
                    );

                    if (!isAabbAreaLoaded(aabb)) continue;
                    if (!mob.world.getCollisionBoxes(mob, aabb).isEmpty()) continue;
                    if (intersectsForbiddenLiquid(mob.world, aabb)) continue;

                    double score = 0.0D;
                    score += horizDistSq(feet, dest) * 0.06D;
                    score += Math.abs(feet.getY() - dest.getY()) * 2.25D;
                    score += Math.abs(feet.getY() - yCenter) * 0.65D;
                    score -= horizDistSq(from, feet) * 0.008D;

                    if (preferCovered && mob.world.canSeeSky(feet)) {
                        score += 30.0D;
                    }

                    if (score < bestScore) {
                        bestScore = score;
                        best = feet;
                    }
                }
            }
        }

        return best;
    }

    private double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private void debug(String tag, String message) {
        if (!TravellersModConfig.debugFollowTeleport) return;
        if (mob == null || mob.world == null || mob.world.isRemote) return;
        MillLog.major(mob, "[TravelDebug][" + tag + "][id=" + mob.getEntityId() + "] " + message);
    }
}