package com.jubitus.travellers.entity.entityAI.travel;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.debug.TravelDebugLogger;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import org.millenaire.common.block.IBlockPath;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EntityAITravel extends EntityAIBase {

    private int absoluteStuckTicks = 0;
    private static final int ABS_STUCK_T = 8 * 20; // 8 seconds


    // --- "no progress toward target" hard trigger ---
    private int noProgressActionCooldown = 0;
    private static final int NO_PROGRESS_TRIGGER = 6 * 20;   // 6s without improving bestDistSq
    private static final int NO_PROGRESS_ACTION_CD = 3 * 20; // don't spam actions

    private int pushStallTicks = 0;
    private Vec3d lastPushPos = Vec3d.ZERO;
    private boolean pushPosInit = false;
    private static final int BOAT_STALL_TICKS = 40;      // ~2 seconds
    private static final double BOAT_MIN_MOVE_SQ = 0.04 * 0.04; // ~0.04 blocks / tick
    // --- Water crossing tuning ---
    private static final int WATER_AHEAD_PROBE = 24; // look ~24 blocks ahead
    private static final int WATER_AHEAD_MIN = 6; // if >=12 of those are water, treat as water corridor
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
    private static final int HARD_UNSTUCK_MIN_STALL = 3;     // don't TP for light stalls
    private static final int HARD_UNSTUCK_T_NOBEST = 12 * 20; // ≥12s since best distance improved
    private static final int HARD_UNSTUCK_T_NOPROJ = 6 * 20;  // ≥6s without forward component
    private static final double HARD_UNSTUCK_MOVE_SQ = 0.20 * 0.20; // barely moved (~0.2 block)
    private static final int HARD_UNSTUCK_COOLDOWN = 10 * 20; // ≥10s between TPs
    private static final int HARD_UNSTUCK_MAX_PER_TRIP = 3;   // safety cap
    private static final int POST_RECOVER_GUARD = 80; // ~4s no-backtrack after combat
    // --- Boat / ocean crossing tuning ---
    private static final int BOAT_OCEAN_PROBE = 64;   // how far ahead we scan for water when deciding to use a boat
    private static final int BOAT_OCEAN_MIN_WATER = 16;   // how many water samples required to call it an "ocean"
    private static final double BOAT_MIN_DISTANCE = 40.0; // only use boats if we’re at least this far from the destination
    private static final double BOAT_EXIT_RADIUS = 24.0; // when this close to target village, try to leave boat
    private static final double BOAT_SPEED_MULT = 0.85; // boat speed vs normal travel speed
    private final EntityTraveller mob;
    private int stepBlocks;
    private final double reachDistSq;
    private final int recalcTicks;
    private final int stuckCheckTicks;
    // --- boat stall tracking ---
    private Vec3d lastBoatPos = Vec3d.ZERO;
    private int boatStallTicks = 0;
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
    // simple guard so we don't spam boat spawning
    private boolean triedBoatThisLeg = false;

    // --- Path-advancement stall detection (corners/buildings/caves) ---
    private int lastPathIndex = -1;
    private int pathIndexStallTicks = 0;

    private double lastDistToWpSq = Double.MAX_VALUE;
    private int wpProgressStallTicks = 0;

    // optional: don't spam escape attempts
    private int escapeCooldown = 0;

    private int noWpProgressTicks = 0;
    private double lastWpDistSq = Double.MAX_VALUE;
    private static final int STALL_PATHINDEX_T = 3 * 20; // 3s
    private static final int STALL_WP_T        = 3 * 20; // 3s
    // ---- Path-point stall (strong signal) ----


    private int pathPointStallTicks = 0;
    private double lastDistToNextPointSq = Double.MAX_VALUE;

    // Tuning
    private static final int PATH_STALL_TICKS = 5 * 20;  // 5s
    private static final int POINT_STALL_TICKS = 3 * 20; // 3s
    private static final int ESCAPE_COOLDOWN = 6 * 20;



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

    // ---- helpers ----

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

    @Nullable
    public static BlockPos findNearestGate(World world, BlockPos villageCenter, BlockPos from) {
        List<BlockPos> gates = MillVillageIndex.getVillageGates(world, villageCenter, 80, 40);
        if (gates == null || gates.isEmpty()) return null;

        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        for (BlockPos g : gates) {
            double d2 = g.distanceSq(from);
            // Gates in Millénaire can be ~2 blocks thick; we want the middle of the opening
            if (d2 < bestD2) {
                best = g;
                bestD2 = d2;
            }
        }
        return best;
    }

    public void setSpeed(double s) {
        this.speed = s;
    }
    public void setStepBlocks(int stepBlocks) {
        this.stepBlocks = stepBlocks;
    }
    @Override
    public boolean shouldExecute() {
        if (this.mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this.mob;

            // If he's hired and NOT on a job, do not run travel AI
            if (w.isHiredFollower() && !w.isJobActive()) {
                return false;
            }
        }
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
        if (this.mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) this.mob;
            if (w.isHiredFollower() && !w.isJobActive()) {
                return false;
            }
        }
        BlockPos tv = mob.getTargetVillage();
        if (tv == null) return false;
        if (!tv.equals(this.targetVillage)) retarget(tv);
        arriveInside = mob.isAtTargetStopHys(arriveInside);

        return !arriveInside;
    }

    @Override
    public void startExecuting() {
        if (mob instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) mob).setUsingTravelAI(true);
        }
        arriveInside = mob.isAtTargetStop();
        this.targetVillage = mob.getTargetVillage();
        if (this.targetVillage == null) return;

        mob.scheduleNextTravelAmbient();
        if (mob.getDistanceSqToCenter(this.targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
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

        // NEW: seed teleport anchor at current position for this leg
        this.lastTPProgressAnchor = mob.getPositionVector();
        this.ticksNoForward = 0;
        // optional: reset cooldown/count at start of each leg
        // this.hardUnstuckCooldown = 0;    // or leave as-is if you want cross-leg cooldown
        // this.hardUnstuckCount = 0;

        triedBoatThisLeg = false;
    }


    @Override
    public void resetTask() {
        this.currentWaypoint = null;
        mob.setCurrentWaypoint(null);
        mob.getNavigator().clearPath();
            if (mob instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) mob).setUsingTravelAI(false);
        }
    }

    @Override
    public void updateTask() {
        if (noProgressActionCooldown > 0) noProgressActionCooldown--;
        if (escapeCooldown > 0) escapeCooldown--;
        if (!mob.world.isRemote && mob.ticksExisted % 20 == 0) {
            WorldServer ws = (WorldServer) mob.world;
            ForgeChunkManager.Ticket t = mob.getOrCreateTravelTicket();
            if (t != null) {

                // Build desired set
                java.util.HashSet<ChunkPos> desired = new java.util.HashSet<>();

                ChunkPos c = new ChunkPos(mob.getPosition());

                // 3x3 bubble
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        desired.add(new ChunkPos(c.x + dx, c.z + dz));
                    }
                }

                // 1 chunk ahead toward waypoint/target
                BlockPos aim = (currentWaypoint != null) ? currentWaypoint : targetVillage;
                if (aim != null) {
                    int ax = Integer.signum(aim.getX() - mob.getPosition().getX());
                    int az = Integer.signum(aim.getZ() - mob.getPosition().getZ());
                    desired.add(new ChunkPos(c.x + ax * 2, c.z + az * 2));
                }

                // Current forced set
                java.util.HashSet<ChunkPos> forcedNow = new java.util.HashSet<>(t.getChunkList());

                // Unforce chunks we no longer want
                for (ChunkPos old : forcedNow) {
                    if (!desired.contains(old)) {
                        ForgeChunkManager.unforceChunk(t, old);

                        // If off-screen / far from players, ask server to unload soon
                        if (!mob.isInPlayerViewDistance() && isFarEnoughFromAnyPlayer()) {
                            net.minecraft.world.chunk.Chunk loaded = ws.getChunkProvider().getLoadedChunk(old.x, old.z);
                            if (loaded != null) {
                                ws.getChunkProvider().queueUnload(loaded);
                            }
                        }
                    }
                }

                // Force newly needed chunks
                for (ChunkPos need : desired) {
                    if (!forcedNow.contains(need)) {
                        ForgeChunkManager.forceChunk(t, need);
                    }
                }
            }
        }


        if (hardUnstuckCooldown > 0) hardUnstuckCooldown--;
        if (waterWaypointCooldown > 0) waterWaypointCooldown--;



        // --- SIMPLE OFF-SCREEN TRAVEL MODE ---
//        if (TravellersModConfig.travellerSimpleOffscreenTravel
//                && !mob.isInPlayerViewDistance()
//                && isFarEnoughFromAnyPlayer()) {
//
//            tickSimpleOffscreenTravel();
//            return;
//        }

        // --- ambient travel chatter (cheap) ---
        if (!mob.world.isRemote && TravellersModConfig.travellerAmbient) {
            if (mob.ticksExisted >= mob.nextTravelAmbientTick) {
                mob.maybePlayTravelAmbient();
            }
        }

        if (projAccum >= 0.4) {
            ticksNoForward = 0; // made some forward component this tick window
        } else {
            ticksNoForward++;
        }

        if (mob.isAutoEating() || mob.isHandActive()) { // bail hard
            mob.getNavigator().clearPath();
            return;
        }

        if (targetVillage != null &&
                mob.getDistanceSqToCenter(targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }

        if (mob.isInCombat()) return;

        // periodic refresh
        if ((mob.ticksExisted + mob.getRecalcPhase()) % this.recalcTicks == 0) {
            if (!(mob.isInWater() && waterWaypointCooldown > 0)) {
                pushPathToWaypoint();
            }
        }

        // --- post-combat recovery handling ---
        int recover = mob.getTravelRecoverTicks();
        if (recover > 0) {
            if (!resetAfterCombatDone) {
                BlockPos liveTarget = mob.getTargetVillage();
                if (liveTarget != null && (!liveTarget.equals(this.targetVillage))) {
                    this.targetVillage = liveTarget;
                    this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
                    mob.setCurrentWaypoint(this.currentWaypoint);
                    debugMark(this.currentWaypoint);
                }
                this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
                this.ticksNoForward = 0;
                this.hardUnstuckCooldown = Math.max(hardUnstuckCooldown, 20);
                this.hardUnstuckCount = 0;
                this.lastTPProgressAnchor = mob.getPositionVector();
                this.ticksSinceBest = 0;
                this.stallLevel = 0;
                this.lastProgressPos = mob.getPositionVector();
                this.projAccum = 0.0;
                this.noPathTicks = 0;
                mob.getNavigator().clearPath();
                pushPathToWaypoint();
                resetAfterCombatDone = true;
                postRecoverGuardTicks = POST_RECOVER_GUARD;
            }
            if (postRecoverGuardTicks > 0) postRecoverGuardTicks--;
            if ((mob.ticksExisted % this.recalcTicks) == 0) {
                pushPathToWaypoint();
            }
            return;
        } else {
            resetAfterCombatDone = false;
        }

        BlockPos liveTarget = mob.getTargetVillage();
        if (liveTarget == null) return;

        if (!liveTarget.equals(this.targetVillage)) {
            retarget(liveTarget);
            this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
            mob.setCurrentWaypoint(this.currentWaypoint);

            this.bestDistSq = mob.getDistanceSqToCenter(this.targetVillage);
            this.ticksSinceBest = 0;
            this.stallLevel = 0;
            this.lastProgressPos = mob.getPositionVector();
            this.projAccum = 0.0;

            mob.getNavigator().clearPath();
            pushPathToWaypoint();
        }

        // --- BOAT STALL & KEEP-ALIVE ---
        if (mob.isRiding() && mob.getRidingEntity() instanceof EntityTravellerBoat) {
            EntityTravellerBoat boat =
                    (EntityTravellerBoat) mob.getRidingEntity();

            Vec3d bp = new Vec3d(boat.posX, boat.posY, boat.posZ);
            if (lastBoatPos == Vec3d.ZERO) {
                lastBoatPos = bp;
            }

            double movedSq = bp.squareDistanceTo(lastBoatPos);
            if (movedSq < BOAT_MIN_MOVE_SQ) {
                boatStallTicks++;
            } else {
                boatStallTicks = 0;
                lastBoatPos = bp;
            }

            if (this.currentWaypoint != null) {
                steerBoatToWaypoint();
            } else if (this.targetVillage != null) {
                this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
                mob.setCurrentWaypoint(this.currentWaypoint);
                debugMark(this.currentWaypoint);
                steerBoatToWaypoint();
            }

            if (boatStallTicks >= BOAT_STALL_TICKS) {
                boatStallTicks = 0;

                if (this.targetVillage != null) {
                    this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
                    mob.setCurrentWaypoint(this.currentWaypoint);
                    debugMark(this.currentWaypoint);
                    steerBoatToWaypoint();
                }
            }
        }

        // --- BOAT COLLISION / DISMOUNT HOOK ---
        if (isInBoat()) {
            EntityTravellerBoat boat = currentBoat();
            if (boat != null && boatHitSurface(boat)) {
                handleBoatHitSurface(boat);
            }
        }

        if ((mob.ticksExisted % 5) == 0) {
            logState("TICK", "");
        }

        // Reached current waypoint? pick next (single place)
        if (this.currentWaypoint != null &&
                mob.getDistanceSqToCenter(this.currentWaypoint) <= this.reachDistSq) {

            if (mob.isInWater() && waterWaypointCooldown > 0) {
                // keep going; do not change waypoint yet
            } else {
                this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
                mob.setCurrentWaypoint(this.currentWaypoint);
                debugMark(this.currentWaypoint);
                if (mob.isInWater()) waterWaypointCooldown = 10; // ~0.5s guard

                logState("WAYPOINT_RESEED", "reason=reached");
            }
        }

        // classic distance-to-goal check
        double d2 = mob.getDistanceSqToCenter(this.targetVillage);
        boolean distImproved = d2 < bestDistSq - 0.75;

        Vec3d pos = mob.getPositionVector();
        Vec3d goalXZ = new Vec3d(targetVillage.getX() + 0.5 - pos.x, 0, targetVillage.getZ() + 0.5 - pos.z);
        if (goalXZ.lengthSquared() > 1e-6) goalXZ = goalXZ.normalize();
        Vec3d deltaXZ = new Vec3d(pos.x - lastProgressPos.x, 0, pos.z - lastProgressPos.z);
        double forward = deltaXZ.dotProduct(goalXZ);
        if (forward > PROJ_STEP_OK) {
            projAccum += forward;
            this.lastProgressPos = pos;
        }
// ------------------------------------------------------
// PUSH-STALL DETECTOR (snow ledges / corners / fences)
// Trigger when we are colliding horizontally AND not making XZ progress.
// ------------------------------------------------------
        if (!pushPosInit) { lastPushPos = pos; pushPosInit = true; }

        double pdx = pos.x - lastPushPos.x;
        double pdz = pos.z - lastPushPos.z;
        double movedSqXZ_push = pdx * pdx + pdz * pdz;

// "pushing" = collided horizontally + barely moved in XZ
        if (mob.collidedHorizontally && movedSqXZ_push < 0.08*0.08) {
            pushStallTicks++;
        } else {
            pushStallTicks = 0;
            lastPushPos = pos;
        }

        if (!mob.world.isRemote && escapeCooldown == 0 && pushStallTicks >= 40) { // ~2s
            logState("STALL_PUSH", "collideXZ");
            handleStuckEscalation();
            pushPathToWaypoint();

            // allow early nudge even if stallLevel is still low
            if (hardUnstuckCooldown == 0 && hardUnstuckCount < HARD_UNSTUCK_MAX_PER_TRIP) {
                hardTeleportNudgeTowardTarget(false);
            }

            pushStallTicks = 0;
            resetStallSignals();
            escapeCooldown = ESCAPE_COOLDOWN;
            return;
        }

// ------------------------------------------------------------------
// ALWAYS-ON "NO PROGRESS TOWARD TARGET" TRIGGER
// If we haven't improved bestDistSq in a while, force an unstuck action,
// even if we're not colliding and path indices are jittering.
// ------------------------------------------------------------------
        if (!mob.world.isRemote
                && escapeCooldown == 0
                && noProgressActionCooldown == 0
                && targetVillage != null
                && !mob.isInCombat()
                && !mob.isAutoEating()
                && !mob.isHandActive()) {

            if (ticksSinceBest >= NO_PROGRESS_TRIGGER) {
                logState("STALL_NOPROGRESS", "ticksSinceBest=" + ticksSinceBest);
                stallLevel = Math.min(3, stallLevel + 1);   // <-- NEW: force escalation
                // do your normal escalation (detour/wallfollow/scan)
                handleStuckEscalation();

                // force navigator to actually try it
                mob.getNavigator().clearPath();
                pushPathToWaypoint();

                // optional: if it's *really* stuck, allow TP even if other signals didn't fire
                if (stallLevel >= 2 && hardUnstuckCooldown == 0 && hardUnstuckCount < HARD_UNSTUCK_MAX_PER_TRIP) {
                    hardTeleportNudgeTowardTarget(true);
                }

                resetStallSignals();
                escapeCooldown = ESCAPE_COOLDOWN;
                noProgressActionCooldown = NO_PROGRESS_ACTION_CD;
            }
        }


        // ----- normal LAND logic below -----

        boolean hasPath = !mob.getNavigator().noPath();
        noPathTicks = hasPath ? 0 : (noPathTicks + 1);

// --- PATH ADVANCEMENT STALL DETECTOR ---
        PathNavigate nav = mob.getNavigator();
        Path path = (nav != null) ? nav.getPath() : null;

// NEW: don't let stall counters "charge up" during escape cooldown
        if (escapeCooldown > 0) {
            lastPathIndex = -1;
            pathIndexStallTicks = 0;
            pathPointStallTicks = 0;
            lastDistToNextPointSq = Double.MAX_VALUE;
        } else {
            if (path != null && nav != null && !nav.noPath()) {
                int idx = path.getCurrentPathIndex();

                if (idx == lastPathIndex) pathIndexStallTicks++;
                else pathIndexStallTicks = 0;

                lastPathIndex = idx;

                // distance to NEXT path point not decreasing?
                PathPoint pp = path.getPathPointFromIndex(idx);
                if (pp != null) {
                    double dx = (pp.x + 0.5) - mob.posX;
                    double dy = (pp.y)       - mob.posY;
                    double dz = (pp.z + 0.5) - mob.posZ;
                    double dSq = dx*dx + dy*dy + dz*dz;

                    if (dSq > lastDistToNextPointSq - 0.05) pathPointStallTicks++;
                    else pathPointStallTicks = 0;

                    lastDistToNextPointSq = dSq;
                } else {
                    pathPointStallTicks = 0;
                    lastDistToNextPointSq = Double.MAX_VALUE;
                }
            } else {
                lastPathIndex = -1;
                pathIndexStallTicks = 0;
                pathPointStallTicks = 0;
                lastDistToNextPointSq = Double.MAX_VALUE;
            }
        }


        // ------------------------------------------------------
// CAVE VERTICAL ESCAPE (fires even at stallLevel 0)
// ------------------------------------------------------
        if (!mob.world.isRemote
                && escapeCooldown == 0
                && isLikelyInCave()
                && (pathIndexStallTicks >= 80 || wpProgressStallTicks >= 80)) {

            logState("STALL_CAVE", "");

            if (tryVerticalCaveEscape()) {
                escapeCooldown = ESCAPE_COOLDOWN;
                hardUnstuckCooldown = 40;
                return;
            }
        }

        // ------------------------------------------------------------------
// PATH ADVANCEMENT STALL DETECTOR (corners / caves / interiors)
// Triggers even when stallLevel==0, because “distance to village” can
// still look fine while being pinned.
// ------------------------------------------------------------------
//        if (path != null && !mob.getNavigator().noPath()) {
//            int idx = path.getCurrentPathIndex();
//            if (idx == lastPathIndex) pathIndexStallTicks++;
//            else pathIndexStallTicks = 0;
//            lastPathIndex = idx;
//        } else {
//            lastPathIndex = -1;
//            pathIndexStallTicks = 0;
//        }

// Distance-to-waypoint not decreasing (waypoint can be inside wall corner)
        if (currentWaypoint != null) {
            double dWp = mob.getDistanceSqToCenter(currentWaypoint);
            if (dWp > lastDistToWpSq - 0.25) wpProgressStallTicks++;
            else wpProgressStallTicks = 0;
            lastDistToWpSq = dWp;
        } else {
            lastDistToWpSq = Double.MAX_VALUE;
            wpProgressStallTicks = 0;
        }

        boolean pathFrozen  = pathIndexStallTicks >= PATH_STALL_TICKS;
        boolean pointFrozen = pathPointStallTicks >= POINT_STALL_TICKS;

        if (!mob.world.isRemote && escapeCooldown == 0 && (pathFrozen || pointFrozen)) {
            logState("STALL_PATH", "idxStall=" + pathIndexStallTicks + ",ptStall=" + pathPointStallTicks);

            // 0) pit escape first (fast + decisive)
            if (isLikelyInPit() && tryPitEscapeTeleport()) {
                escapeCooldown = ESCAPE_COOLDOWN;
                hardUnstuckCooldown = Math.max(hardUnstuckCooldown, 40);
                return;
            }

            // 1) cave escape second (your vertical escape)
            if (isLikelyInCave() && tryVerticalCaveEscape()) {
                escapeCooldown = ESCAPE_COOLDOWN;
                hardUnstuckCooldown = Math.max(hardUnstuckCooldown, 40);
                return;
            }

            // 2) force escalation NOW even if stallLevel==0
            handleStuckEscalation();
            pushPathToWaypoint();

            // 3) if still frozen shortly after, do the forward TP nudge even at low stallLevel
            // (this is your old "hardTeleportNudgeTowardTarget", but don't wait 20 seconds)
            if (pathIndexStallTicks >= (PATH_STALL_TICKS + 40) && hardUnstuckCooldown == 0) {
                if (hardTeleportNudgeTowardTarget(true)) {
                    escapeCooldown = ESCAPE_COOLDOWN;
                    return;
                }
            }

            escapeCooldown = ESCAPE_COOLDOWN;
        }

        if (noPathTicks >= NO_PATH_RESELECT) {
            this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
            mob.setCurrentWaypoint(this.currentWaypoint);
            debugMark(this.currentWaypoint);
            pushPathToWaypoint();
            logState("WAYPOINT_RESEED", "reason=noPath");
            noPathTicks = 0;
        }

        // --- PROGRESS-AWARE LAST RESORT ---
        if (stallLevel >= HARD_UNSTUCK_MIN_STALL &&
                hardUnstuckCooldown == 0 &&
                hardUnstuckCount < HARD_UNSTUCK_MAX_PER_TRIP) {

            boolean longNoBest = (ticksSinceBest >= HARD_UNSTUCK_T_NOBEST);
            boolean longNoForward = (ticksNoForward >= HARD_UNSTUCK_T_NOPROJ);

            Vec3d now = mob.getPositionVector();

            if (lastTPProgressAnchor == Vec3d.ZERO) {
                lastTPProgressAnchor = now;
            }

// Ignore vertical bobbing: only care about horizontal displacement
            double dx = now.x - lastTPProgressAnchor.x;
            double dz = now.z - lastTPProgressAnchor.z;
            double movedSqSinceAnchor = dx * dx + dz * dz;

            Vec3d p = mob.getPositionVector();
            double movedSqXZ = dx*dx + dz*dz;
            lastPos = p;
            if (movedSqXZ < 0.02 * 0.02) absoluteStuckTicks++;
            else absoluteStuckTicks = 0;

            if (!mob.world.isRemote && escapeCooldown == 0 && absoluteStuckTicks >= ABS_STUCK_T) {
                logState("STALL_ABSOLUTE", "movedSqXZ=" + movedSqXZ);

                // escalate once so we don't spam tiny detours
                stallLevel = 3;

                // Try “normal escape” once
                handleStuckEscalation();
                mob.getNavigator().clearPath();
                pushPathToWaypoint();

                // If still totally stuck: FORCE TP
                hardTeleportNudgeTowardTarget(true);

                resetStallSignals();
                escapeCooldown = ESCAPE_COOLDOWN;
                absoluteStuckTicks = 0;
                return;
            }


// Allow more lateral swirl when swimming; rely mainly on "no progress" timers.
            double moveBudgetSq = HARD_UNSTUCK_MOVE_SQ;
            if (mob.isInWater()) {
                // e.g. allow up to ~1.5 blocks of orbit radius
                moveBudgetSq = 1.5 * 1.5;
            }

            if ((longNoBest || longNoForward) && movedSqSinceAnchor <= moveBudgetSq) {
                if (hardTeleportNudgeTowardTarget(false)) {
                    lastTPProgressAnchor = mob.getPositionVector();
                    return;
                } else {
                    lastTPProgressAnchor = now;
                }
                // Global guard: if we *should* be moving to a waypoint but nav has no path,
// force one more try at pathing.
                if (this.currentWaypoint != null) {
                    double d2wp = mob.getDistanceSqToCenter(this.currentWaypoint);

                    // We still haven't reached this waypoint
                    if (d2wp > this.reachDistSq && mob.getNavigator().noPath()) {
                        logState("GUARD_REPATH", "wp=" + currentWaypoint);
                        pushPathToWaypoint();
                    }
                }

                // ---------------------------------------------------------------------
// SAFETY: ensure navigator actually gets updated even if some activation
// / tickrate / other mod interferes with normal LivingUpdate flow.
// ---------------------------------------------------------------------
            }

        }
        if (!mob.world.isRemote) {

            // If we think we have a path, force navigator to advance it.
            if (nav != null && !nav.noPath()) {
                nav.onUpdateNavigation();
            }
        }
        if (mob.ticksExisted % this.stuckCheckTicks == 0) {
            if (!mob.isAutoEating() && !mob.isHandActive()) {
                Vec3d now = mob.getPositionVector();

                // ---- use XZ only so water bobbing doesn't count as progress ----
                double dx = now.x - this.lastPos.x;
                double dz = now.z - this.lastPos.z;
                double movedSqXZ = dx * dx + dz * dz;

                this.lastPos = now;

                double stuckMoveSq = 0.6 * 0.6;
                if (mob.isInWater()) {
                    // swimming "stuck" can still swirl around quite a bit
                    stuckMoveSq = 1.5 * 1.5;
                }
                if (movedSqXZ < stuckMoveSq) {
                    if (stallLevel >= 3 && ticksSinceBest > (STALL_T3 + 5 * 20) &&
                            hardUnstuckCooldown == 0 && hardUnstuckCount < HARD_UNSTUCK_MAX_PER_TRIP) {

                        if (hardTeleportNudgeTowardTarget(true)) {
                            lastTPProgressAnchor = mob.getPositionVector();
                            return;
                        }
                    }

                    handleStuckEscalation();
                    mob.breakNearbyLeaves();
                }
            }
        }
    }

    private void retarget(BlockPos newTarget) {
        this.targetVillage = newTarget;
        int near = mob.arrivalRadiusBlocks(newTarget); // unified
        this.targetNearSq = near * near;
        this.triedBoatThisLeg = false; // NEW
    }

    private void pushPathToWaypoint() {
        if (this.currentWaypoint == null) return;

        // If we're in a boat, don't use Navigator; steer manually.
        if (mob.isRiding() && mob.getRidingEntity() instanceof EntityBoat) {
            steerBoatToWaypoint();
            return;
        }

        // normal land/water navigation
        Path current = mob.getNavigator().getPath();
        if (current != null) {
            PathPoint dest = current.getFinalPathPoint();
            if (dest != null) {
                if (Math.abs(dest.x - currentWaypoint.getX()) <= 1 &&
                        Math.abs(dest.z - currentWaypoint.getZ()) <= 1) return;
            }
        }

        double navSpeed = this.speed;
        if (mob.isInWater()) {
            navSpeed *= TravellersModConfig.SWIM_MULT;
        }
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

        BlockPos from = mob.getPosition();
        Vec3d destVec = new Vec3d(dest.getX() - from.getX(), 0, dest.getZ() - from.getZ());
        if (destVec.lengthSquared() < 1.0) return null;

        Vec3d dir = gateBiasedDirection(from, dest, destVec);
        if (dir.lengthSquared() < 1e-6) {
            dir = destVec.normalize();
        }

        int baseStep = dynamicStep(from, dest);
        int step = Math.min(
                baseStep + mob.getPathStepJitter(),
                (int) Math.sqrt(mob.getDistanceSqToCenter(dest))
        );
        step = Math.max(8, step);

        boolean boatMode = isInBoat();

        // --- NEW: only enter boat after we have a safe boat waypoint + clear water corridor ---
        if (!boatMode && !triedBoatThisLeg) {
            triedBoatThisLeg = true;
            if (shouldUseBoat(from, dest)) {
                BlockPos boatWp = pickFirstBoatWaypoint(from, dest, dir, step);
                if (boatWp != null) {
                    // direction from travellers to first boat waypoint
                    Vec3d boatDir = new Vec3d(
                            boatWp.getX() + 0.5 - (from.getX() + 0.5),
                            0,
                            boatWp.getZ() + 0.5 - (from.getZ() + 0.5)
                    ).normalize();
                    if (tryEnterBoat(from, boatDir)) {
                        this.currentWaypoint = boatWp;
                        mob.setCurrentWaypoint(boatWp);
                        debugMark(boatWp);
                        return boatWp; // already in boat, first hop is safe
                    }
                }
            }
            // If we get here: either corridor not clear or boat spawn failed → just fall back to normal land/swim logic below.
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

            for (int s = step; s >= 16; s -= 16) {
                BlockPos cand = surfaceAtOffsetWater(from, dir, s);
                if (cand != null ) {
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
        BlockPos bestPath = null;
        // --- 1) straight candidates, no lane offset ---
        for (int s = step; s >= 16; s -= 16) {
            BlockPos candidate = surfaceAtOffset(from, dir, s, Vec3d.ZERO);
            if (candidate != null ) {
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

                if (TravellersModConfig.debug) {
                    Vec3d toDir = gateBiasedDirection(from, dest, destVec);
                    double stepDX = (candidate.getX() + 0.5) - (from.getX() + 0.5);
                    double stepDZ = (candidate.getZ() + 0.5) - (from.getZ() + 0.5);
                    double stepLen = Math.sqrt(stepDX * stepDX + stepDZ * stepDZ);
                    double dotForward = (stepDX * toDir.x + stepDZ * toDir.z);
                    double cosAngle = dotForward / (stepLen + 1e-6); // avoid div by zero

                    TravelDebugLogger.log(
                            mob,
                            this.targetVillage,
                            candidate,
                            this.stallLevel,
                            this.wallFollow,
                            this.followSide,
                            this.bestDistSq,
                            this.ticksSinceBest,
                            this.projAccum,
                            this.ticksNoForward,
                            this.noPathTicks,
                            this.hardUnstuckCount,
                            this.hardUnstuckCooldown,
                            this.postRecoverGuardTicks,
                            "WAYPOINT_PICK_STRAIGHT",
                            "stepLen=" + stepLen +
                                    ",dotForward=" + dotForward +
                                    ",cosAngle=" + cosAngle +
                                    ",destX=" + dest.getX() +
                                    ",destZ=" + dest.getZ()
                    );
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
            if (candidate != null ) {
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
                pushPathToWaypoint();
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
            if (surface != null && isAreaLoaded(surface, 1) && isNavigable(surface)) {
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
            return top;
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
                return p;
            }
            if (isStandable(p)) {
                if (!mob.isInWater()) {
                    return adjustTreeTopToGround(p);
                }
                return p;
            }
        }

        // 3. Deeper downward probe (valleys, shallow water tops, snow layers)
        p = top;
        for (int dy = 0; dy < 12 && p.getY() > 1; dy++) {
            p = p.down();
            if (isSwimmable(p)) {
                return p;
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
        BlockPos to   = pos.add( radius,  radius,  radius);

        // allowLoading = true → will load missing chunks
        return mob.world.isAreaLoaded(from, to, true);
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
        int detStep = Math.max(24, this.stepBlocks);

        int[] ANG = (stallLevel >= 3)
                ? new int[]{120, -120, 150, -150, 180, 90, -90, 60, -60, 30, -30}
                : new int[]{120, -120, 90, -90, 60, -60, 30, -30};

        for (int angleDeg : ANG) {
            Vec3d dir = rotateY(base, Math.toRadians(angleDeg));
            BlockPos candidate = surfaceAtOffset(from, dir, detStep,
                    new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getPathLaneOffset() * 0.5));

            if (candidate != null ) {
                this.currentWaypoint = candidate;
                mob.setCurrentWaypoint(candidate);
                debugMark(this.currentWaypoint);
                pushPathToWaypoint();
                logState("DETOUR_WIDE_PICK", "ang=" + angleDeg + ",step=" + detStep);
                return;
            }
        }
    }

    private void enterOrStepWallFollow() {
        if (!wallFollow) {
            wallFollow = true;
            followSide = mob.getRNG().nextBoolean() ? 1 : -1;
            this.lastProgressPos = mob.getPositionVector(); // reset baseline
            logState("WALLFOLLOW_ENTER", "side=" + followSide);
        }

        Vec3d pos = mob.getPositionVector();
        Vec3d goalDir = new Vec3d(targetVillage).add(0.5, 0, 0.5).subtract(pos).normalize();
        Vec3d delta = pos.subtract(this.lastProgressPos);
        double forward = delta.dotProduct(goalDir);

        // normal wall-follow stepping
        stepWallFollow(goalDir, followSide);
        logState("WALLFOLLOW_STEP", "");

        if (forward > 0.2) {   // making headway in the right general direction
            ticksSinceBest = 0;
            this.lastProgressPos = pos;
        }
    }

    private void stepWallFollow(Vec3d forward, int side) {
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        Vec3d[] order = (side > 0)
                ? new Vec3d[]{right, forward, right.scale(-1)}
                : new Vec3d[]{right.scale(-1), forward, right};

        BlockPos from = mob.getPosition();
        int step = 6;

        for (Vec3d dir : order) {
            BlockPos cand = surfaceAtOffset(from, dir, step, Vec3d.ZERO);
            if (cand != null) {
                this.currentWaypoint = cand;
                mob.setCurrentWaypoint(cand);
                debugMark(cand);
                pushPathToWaypoint();
                return;
            }
        }

        // if all failed, flip side and try next tick
        followSide = -followSide;
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
            logState("ESCAPE_SCAN_PICK",
                    "r=" + radius + ",best=" + best.getX() + "," + best.getY() + "," + best.getZ());
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

                this.currentWaypoint = p;
                mob.setCurrentWaypoint(p);
                logState("MACRO_BYPASS_PICK",
                        "ang=" + ang + ",dist=" + s + ",cand=" + p.getX() + "," + p.getY() + "," + p.getZ());
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
        if (!mob.isInWater() && !isLikelyInCave() && isCaveLike(cand)) return false;
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
        if (isPathBelow(cand)) {
            score += 3.0; // strong bias; tweak 1.5–4.0
        }

        return score;
    }

    private boolean hardTeleportNudgeTowardTarget(boolean force) {
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
            logState("TP_NUDGE",
                    "step=" + s + ",surf=" + surf.getX() + "," + surf.getY() + "," + surf.getZ());
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
        logState("TP_FAIL", "noLanding");
        return false;


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

        // For water travel, prefer a water cell if both are valid
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
        if (!TravellersModConfig.debug) return; // or a new flag e.g. debugTravelLog

        TravelDebugLogger.log(
                mob,
                this.targetVillage,
                this.currentWaypoint,
                this.stallLevel,
                this.wallFollow,
                this.followSide,
                this.bestDistSq,
                this.ticksSinceBest,
                this.projAccum,
                this.ticksNoForward,
                this.noPathTicks,
                this.hardUnstuckCount,
                this.hardUnstuckCooldown,
                this.postRecoverGuardTicks,
                event,
                note == null ? "" : note
        );
    }

    /**
     * Compute a direction toward 'dest' that is softly biased toward the nearest gate.
     * <p>
     * - If we are inside / near a village that is NOT 'dest' (leaving a walled village),
     * we strongly bias toward that village's gate (pure gate heading).
     * <p>
     * - Otherwise, when approaching the destination village, we only bias toward its gate
     * if the gate lies roughly between travellers and village center (to avoid weird
     * in-village orbiting).
     */
    private Vec3d gateBiasedDirection(BlockPos from, BlockPos dest, Vec3d destVec) {

        World w = mob.world;

        if (destVec.lengthSquared() < 1.0) {
            return destVec; // too close, don't bother
        }

        double distToDest = Math.sqrt(destVec.lengthSquared());
        Vec3d dirDest = destVec.normalize();

        BlockPos gateBias = null;
        double gateWeight = 0.0;     // 0 = pure dest, 1 = pure gate
        boolean gateIsLeaving = false;

        // --- 1) Gate of the village we are currently inside / near (for LEAVING) ---
        MillVillageIndex.VillageInfo hereVi =
                MillVillageIndex.findNearest2D(w, from, 80); // ~80-block search radius

        if (hereVi != null
                && !hereVi.center.equals(dest)                     // only if this isn't the dest village
                && MillVillageIndex.isNearVillage(w, from, 50)) {  // "inside-ish" radius

            BlockPos g = findNearestGate(w, hereVi.center, from);
            if (g != null) {

                // NEW: only bias toward this gate if it lies between us and the destination
                // (prevents going back to a gate that is now *behind* us)
                final double LEAVE_GATE_LATERAL_TOL = 8.0; // tweak if needed
                if (isGateBetween(from, dest, g, LEAVE_GATE_LATERAL_TOL)) {
                    gateBias = g;
                    gateIsLeaving = true;
                    double distToHere = Math.sqrt(from.distanceSq(hereVi.center));
                    double leaveRadius = 50.0; // same as isNearVillage radius
                    double tLeave = Math.max(0.0, Math.min(1.0, distToHere / leaveRadius));
                    gateWeight = 0.5 + 0.5 * (1.0 - tLeave); // ~1.0 near center, ~0.5 near edge
                }
            }
        }

        // --- 2) Otherwise, gate of the destination village (for APPROACHING) ---
        if (gateBias == null) {
            final double GATE_BIAS_RADIUS = 120.0; // start caring about gates when within this range

            if (distToDest < GATE_BIAS_RADIUS) {
                BlockPos g = findNearestGate(w, dest, from);
                if (g != null) {
                    // only bias toward this gate if it is between us and the village center
                    final double GATE_LATERAL_TOL = 8.0; // allowed sideways offset
                    if (isGateBetween(from, dest, g, GATE_LATERAL_TOL)) {
                        gateBias = g;

                        // Weight ramps up as we get closer to the village
                        double t = Math.max(0.0, Math.min(1.0,
                                (GATE_BIAS_RADIUS - distToDest) / GATE_BIAS_RADIUS));
                        gateWeight = 0.25 + 0.55 * t; // ≈ [0.25 .. 0.80]
                    }
                }
            }
        }

        if (gateBias == null) {
            // no valid gate to bias toward
            return dirDest;
        }

        // --- Compute gate direction ---
        Vec3d toGate = new Vec3d(
                gateBias.getX() - from.getX(),
                0,
                gateBias.getZ() - from.getZ()
        );
        if (toGate.lengthSquared() < 1e-6) {
            return dirDest; // gate is essentially at our feet
        }
        Vec3d dirGate = toGate.normalize();

        // --- Leaving a village: just go to the gate directly ---
        if (gateIsLeaving) {
            return dirGate;
        }

        // --- Approaching dest: mix gate + dest directions ---
        double wGate = gateWeight;
        double wDest = 1.0 - wGate;

        Vec3d mixed = dirGate.scale(wGate).add(dirDest.scale(wDest));
        if (mixed.lengthSquared() < 1e-6) {
            return dirDest;
        }

        return mixed.normalize();
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
            boolean likelyGround =
                    (m == Material.GROUND || m == Material.GRASS || m == Material.ROCK ||
                            m == Material.SAND || m == Material.CLAY || m == Material.SNOW);

            if (!likelyGround) continue;

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
     * Escalating stuck handler:
     * - stallLevel 0: tiny local detour
     * - stallLevel 1: wider detour
     * - stallLevel 2: wall-follow mode
     * - stallLevel 3+: full escape scan / macro bypass
     * <p>
     * Works in both land and water.
     */
    private void handleStuckEscalation() {
        if (this.targetVillage == null) return;

        if (stallLevel == 0) {
            // smallest nudge: local little detour
            tryDetour();
        } else if (stallLevel == 1) {
            // slightly larger, wider detours
            tryDetourWide();
        } else if (stallLevel == 2) {
            // wall-follow mode along obstacles / shores
            enterOrStepWallFollow();
        } else {
            // full local escape scan + macro bypass
            int scan = currentEscapeScanRadius();
            if (localEscapeScan(scan)) {
                pushPathToWaypoint();
            } else if (macroLateralBypass()) {
                pushPathToWaypoint();
            } else {
                // if everything fails, fall back to tiny detour so we at least jiggle
                tryDetour();
            }
        }
    }

    /**
     * Compute a dynamic step size:
     * - Far from the village: use the full stepBlocks (e.g. 64).
     * - Closer in: shrink down so we can thread gates/streets precisely.
     */
    private int dynamicStep(BlockPos from, BlockPos dest) {
        double dist = Math.sqrt(from.distanceSq(dest));

        // How close is "arrival-ish"?
        // We already have targetNearSq from retarget(), so use that to define a “near” radius.
        double nearRadius = Math.max(24.0, Math.sqrt(this.targetNearSq) * 2.0); // start shrinking inside ~2× arrival radius
        double farRadius = 160.0;  // fully "far" beyond this (tweak as needed)

        int minStep = 12;              // smallest waypoint step near village
        int maxStep = this.stepBlocks; // e.g. 64

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
        return mob.isRiding() && mob.getRidingEntity() instanceof EntityTravellerBoat;
    }

    @Nullable
    private EntityTravellerBoat currentBoat() {
        if (!isInBoat()) return null;
        return (EntityTravellerBoat) mob.getRidingEntity();
    }

    /**
     * True if it's worth spawning a boat: long-ish distance and a mostly-water corridor ahead.
     */
    private boolean shouldUseBoat(BlockPos from, BlockPos dest) {
        double dist = Math.sqrt(from.distanceSq(dest));
        if (dist < BOAT_MIN_DISTANCE) return false;  // too short, just swim/walk

        Vec3d dir = new Vec3d(
                dest.getX() + 0.5 - (from.getX() + 0.5),
                0,
                dest.getZ() + 0.5 - (from.getZ() + 0.5)
        );
        if (dir.lengthSquared() < 1e-6) return false;
        dir = dir.normalize();

        // re-use your corridor scanner but with longer probe
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

        w.spawnEntity(boat);

        mob.startRiding(boat, true);
        lastBoatPos = new Vec3d(boat.posX, boat.posY, boat.posZ);
        boatStallTicks = 0;

        mob.getNavigator().clearPath(); // we steer manually while boating
        return true;
    }

    private void dismountAndDeleteBoat() {
        if (!isInBoat()) return;
        if (mob.world.isRemote) return;

        EntityTravellerBoat boat = currentBoat();
        if (boat == null) return;

        mob.dismountRidingEntity();
        boat.setDead(); // 1.12: remove without drops
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
        if (d2 < 0.25) {
            // close enough to this waypoint
            boat.motionX = 0;
            boat.motionZ = 0;
            return;
        }

        Vec3d dir = delta.normalize();
        double boatSpeed = this.speed * BOAT_SPEED_MULT;

        boat.motionX = dir.x * boatSpeed;
        boat.motionZ = dir.z * boatSpeed;
        // rotate the boat to face direction of travel
        float yaw = yawFromDirection(dir);

// Rotate boat
        boat.setLocationAndAngles(boat.posX, boat.posY, boat.posZ, yaw, boat.rotationPitch);
        boat.rotationYaw = yaw;
        boat.prevRotationYaw = yaw;

// Sync travellers
        mob.rotationYaw = yaw;
        mob.rotationYawHead = yaw;
        mob.renderYawOffset = yaw;


        // clear mob path so navigator doesn't fight us
        mob.getNavigator().clearPath();
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

        // Column under the boat's center
        BlockPos boatCol = new BlockPos(
                net.minecraft.util.math.MathHelper.floor(boat.posX),
                net.minecraft.util.math.MathHelper.floor(boat.posY - 0.2D),
                net.minecraft.util.math.MathHelper.floor(boat.posZ)
        );

        if (!w.isBlockLoaded(boatCol)) return false;

        IBlockState feet = w.getBlockState(boatCol);
        IBlockState below = w.getBlockState(boatCol.down());

        boolean feetWater = isVanillaWater(feet);
        boolean belowWater = isVanillaWater(below);

        // --- 1) If we are no longer over ANY water in this column → we've run onto land/ice ---
        if (!feetWater && !belowWater) {
            return true;
        }

        // Still over a water column → don't dismount yet based only on depth.
        // But we might be at the shore: check a couple blocks AHEAD for dry standable ground.

        // Direction of travel: prefer actual motion; fall back to waypoint / target village
        Vec3d dir = new Vec3d(boat.motionX, 0, boat.motionZ);
        if (dir.lengthSquared() < 1e-4) {
            if (this.currentWaypoint != null) {
                dir = new Vec3d(
                        this.currentWaypoint.getX() + 0.5 - boat.posX,
                        0,
                        this.currentWaypoint.getZ() + 0.5 - boat.posZ
                );
            } else if (this.targetVillage != null) {
                dir = new Vec3d(
                        this.targetVillage.getX() + 0.5 - boat.posX,
                        0,
                        this.targetVillage.getZ() + 0.5 - boat.posZ
                );
            } else {
                return false;
            }
        }

        if (dir.lengthSquared() < 1e-6) return false;
        dir = dir.normalize();

        int sx = (int) Math.signum(dir.x);
        int sz = (int) Math.signum(dir.z);
        if (sx == 0 && sz == 0) return false;

        // Look 1–2 blocks ahead along the heading for a dry exit spot
        for (int k = 1; k <= 2; k++) {
            BlockPos aheadCol = new BlockPos(
                    boatCol.getX() + sx * k,
                    boatCol.getY(),
                    boatCol.getZ() + sz * k
            );
            if (!isAreaLoaded(aheadCol, 1)) break;

            BlockPos surf = findSurfaceOrWaterY(aheadCol);
            if (surf == null) continue;

            IBlockState s = w.getBlockState(surf);
            // If the surface here is NOT water and is dry-standable, we treat that as "shore hit"
            if (!isVanillaWater(s) && isDryStandableForBoatExit(surf)) {
                return true;
            }
        }

        return false;
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
                net.minecraft.util.math.MathHelper.floor(boat.posX),
                net.minecraft.util.math.MathHelper.floor(boat.posY),
                net.minecraft.util.math.MathHelper.floor(boat.posZ)
        );

        // Try to find a dry standable block near where we hit
        BlockPos shore = findDrySurfaceNear(boatPos);
        // 1) Dismount + delete boat no matter what
        dismountAndDeleteBoat();
        triedBoatThisLeg = false;

        // 2) If we found a shore tile, snap next waypoint to it
        if (shore != null) {
            this.currentWaypoint = shore;
            mob.setCurrentWaypoint(shore);
            debugMark(shore);
            mob.getNavigator().clearPath();
            pushPathToWaypoint();
        } else {
            // fallback: just let travellers swim / walk from wherever he ends up
            mob.getNavigator().clearPath();
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
            int sx = net.minecraft.util.math.MathHelper.floor(x + stepx * i);
            int sz = net.minecraft.util.math.MathHelper.floor(z + stepz * i);
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

    /**
     * True if 'gate' lies roughly between 'from' and 'dest' along the A->B line,
     * within some lateral tolerance.
     */
    private boolean isGateBetween(BlockPos from, BlockPos dest, BlockPos gate, double maxLateral) {
        Vec3d A = new Vec3d(from.getX() + 0.5, 0, from.getZ() + 0.5);
        Vec3d B = new Vec3d(dest.getX() + 0.5, 0, dest.getZ() + 0.5);
        Vec3d G = new Vec3d(gate.getX() + 0.5, 0, gate.getZ() + 0.5);

        Vec3d AB = B.subtract(A);
        double ab2 = AB.lengthSquared();
        if (ab2 < 1e-6) return false;

        // Projection of AG onto AB, as fraction along AB
        double t = G.subtract(A).dotProduct(AB) / ab2;

        // Gate must be between A and B (0 <= t <= 1)
        if (t < 0.0 || t > 1.0) return false;

        // Lateral distance from gate to the A->B line
        Vec3d H = A.add(AB.scale(t)); // closest point on AB to G
        double lateral = G.subtract(H).length();

        return lateral <= maxLateral;
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
    private void tickSimpleOffscreenTravel() {
        if (targetVillage == null) {
            BlockPos tv = mob.getTargetVillage();
            if (tv == null) return;
            retarget(tv);
        }

        BlockPos tv = this.targetVillage;
        if (tv == null) return;

        double d2 = mob.getDistanceSqToCenter(tv);
        if (d2 <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }

        BlockPos from = mob.getPosition();

        // How big a hop per simple step
        double dist = Math.sqrt(d2);
        double hop = Math.min(dist, Math.max(8.0, this.stepBlocks * 0.75));

        Vec3d dir = new Vec3d(
                tv.getX() + 0.5 - (from.getX() + 0.5),
                0,
                tv.getZ() + 0.5 - (from.getZ() + 0.5)
        );
        if (dir.lengthSquared() < 1e-6) {
            onArrivedAtVillage();
            return;
        }
        dir = dir.normalize();

        // Guess a target XZ hop
        double nx = from.getX() + 0.5 + dir.x * hop;
        double nz = from.getZ() + 0.5 + dir.z * hop;
        BlockPos guess = new BlockPos(nx, from.getY(), nz);

        // Try to land on something sensible using your existing surface logic
        BlockPos surface = findSurfaceOrWaterY(guess);
        if (surface == null) {
            // Fallback: just move along XZ at current Y
            surface = new BlockPos(nx, from.getY(), nz);
        }

        // No path, just teleport the travellers in small-ish hops
        mob.getNavigator().clearPath();
        mob.setPositionAndUpdate(
                surface.getX() + 0.5,
                surface.getY(),
                surface.getZ() + 0.5
        );
        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;

        // Reset progress/stall tracking so normal mode is happy when we come back on-screen
        this.lastPos = mob.getPositionVector();
        this.lastProgressPos = this.lastPos;
        this.projAccum = 0.0;
        this.bestDistSq = mob.getDistanceSqToCenter(tv);
        this.ticksSinceBest = 0;
        this.noPathTicks = 0;
        this.stallLevel = 0;
        this.wallFollow = false;
        this.hardUnstuckCooldown = 0;
    }
    private boolean tryCornerEscapeTeleport() {
        if (mob.world.isRemote) return false;

        World w = mob.world;
        BlockPos origin = mob.getPosition();

        // small radius for “inside building corner”; increase if underground
        int r = (origin.getY() < 50) ? 20 : 12;

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos feet = origin.add(dx, dy, dz);
                    if (!isAreaLoaded(feet, 1)) continue;
                    if (!isStandable(feet)) continue;

                    AxisAlignedBB aabb = mob.getEntityBoundingBox().offset(
                            (feet.getX() + 0.5) - mob.posX,
                            feet.getY() - mob.posY,
                            (feet.getZ() + 0.5) - mob.posZ
                    );

                    if (!w.getCollisionBoxes(mob, aabb).isEmpty()) continue;
                    if (intersectsForbiddenLiquid(w, aabb)) continue;

                    // clearance so we don’t TP into “hugging the wall corner”
                    AxisAlignedBB expanded = aabb.grow(0.15, 0, 0.15);
                    if (!w.getCollisionBoxes(mob, expanded).isEmpty()) continue;

                    double score = feet.distanceSq(origin); // closest valid wins
                    if (score < bestScore) {
                        bestScore = score;
                        best = feet;
                    }
                }
            }
        }

        if (best == null) return false;

        logState("TP_ESCAPE", "to=" + best);

        mob.setPositionAndUpdate(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;

        mob.getNavigator().clearPath();

        // reset trackers so we don’t instantly re-trigger
        lastPos = mob.getPositionVector();
        lastProgressPos = lastPos;
        projAccum = 0.0;
        ticksNoForward = 0;
        ticksSinceBest = 0;
        noPathTicks = 0;

        lastPathIndex = -1;
        pathIndexStallTicks = 0;
        lastDistToWpSq = Double.MAX_VALUE;
        wpProgressStallTicks = 0;

        // reseed waypoint from new spot
        if (targetVillage != null) {
            currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
            mob.setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            pushPathToWaypoint();
        }

        return true;
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

    private boolean tryVerticalCaveEscape() {
        if (mob.world.isRemote) return false;

        World w = mob.world;
        BlockPos base = mob.getPosition();

        // scan upward aggressively
        for (int dy = 1; dy <= 24; dy++) {
            BlockPos p = base.up(dy);

            if (!isAreaLoaded(p, 1)) continue;
            if (!isStandable(p)) continue;

            AxisAlignedBB bb = mob.getEntityBoundingBox().offset(
                    (p.getX() + 0.5) - mob.posX,
                    p.getY() - mob.posY,
                    (p.getZ() + 0.5) - mob.posZ
            );

            if (!w.getCollisionBoxes(mob, bb).isEmpty()) continue;
            if (intersectsForbiddenLiquid(w, bb)) continue;

            logState("TP_CAVE_UP", "to=" + p);

            mob.setPositionAndUpdate(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            mob.motionX = mob.motionY = mob.motionZ = 0;
            mob.velocityChanged = true;

            mob.getNavigator().clearPath();

            // hard reset progress trackers
            lastProgressPos = mob.getPositionVector();
            lastTPProgressAnchor = lastProgressPos;
            ticksNoForward = 0;
            ticksSinceBest = 0;
            projAccum = 0;
            stallLevel = 0;
            noPathTicks = 0;

            if (targetVillage != null) {
                currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
                mob.setCurrentWaypoint(currentWaypoint);
                debugMark(currentWaypoint);
                pushPathToWaypoint();
            }

            return true;
        }
        return false;
    }
    private boolean isLikelyInPit() {
        BlockPos p = mob.getPosition();
        World w = mob.world;

        // If headroom is low and 3-4 horizontal sides are solid, you're in a shaft/corner trap.
        int solidSides = 0;
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            IBlockState s = w.getBlockState(p.offset(f));
            if (s.getMaterial().isSolid()) solidSides++;
        }

        boolean cramped =
                !w.getBlockState(p.up()).getMaterial().isReplaceable() ||
                        solidSides >= 3;

        // Also, if there's no standable block within 1 step around us (can't step out)
        boolean noStepOut = true;
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            BlockPos rim = p.offset(f);
            if (isStandable(rim)) { noStepOut = false; break; }
        }

        return cramped && noStepOut;
    }
    private boolean tryPitEscapeTeleport() {
        if (mob.world.isRemote) return false;

        World w = mob.world;
        BlockPos base = mob.getPosition();

        // climb up to 32 blocks, and try a small ring each level
        for (int dy = 1; dy <= 32; dy++) {
            BlockPos level = base.up(dy);

            // ring radius 0..2 (so we can exit even if the shaft is covered by a lip)
            for (int r = 0; r <= 2; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // ring only

                        BlockPos cand = level.add(dx, 0, dz);
                        if (!isAreaLoaded(cand, 1)) continue;
                        if (!isStandable(cand)) continue;

                        AxisAlignedBB aabb = mob.getEntityBoundingBox().offset(
                                (cand.getX() + 0.5) - mob.posX,
                                cand.getY() - mob.posY,
                                (cand.getZ() + 0.5) - mob.posZ
                        );
                        if (!w.getCollisionBoxes(mob, aabb).isEmpty()) continue;
                        if (intersectsForbiddenLiquid(w, aabb)) continue;

                        logState("TP_PIT", "to=" + cand);

                        mob.setPositionAndUpdate(cand.getX() + 0.5, cand.getY(), cand.getZ() + 0.5);
                        mob.motionX = mob.motionY = mob.motionZ = 0;
                        mob.velocityChanged = true;
                        mob.getNavigator().clearPath();

                        // reset trackers
                        lastProgressPos = mob.getPositionVector();
                        lastTPProgressAnchor = lastProgressPos;
                        ticksNoForward = 0;
                        ticksSinceBest = 0;
                        projAccum = 0;
                        stallLevel = 0;
                        noPathTicks = 0;
                        pathIndexStallTicks = 0;
                        pathPointStallTicks = 0;

                        // reseed
                        if (targetVillage != null) {
                            currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
                            mob.setCurrentWaypoint(currentWaypoint);
                            debugMark(currentWaypoint);
                            pushPathToWaypoint();
                        }

                        return true;
                    }
                }
            }
        }
        return false;
    }
    private void resetStallSignals() {
        lastPathIndex = -1;
        pathIndexStallTicks = 0;
        pathPointStallTicks = 0;
        lastDistToNextPointSq = Double.MAX_VALUE;

        wpProgressStallTicks = 0;
        lastDistToWpSq = Double.MAX_VALUE;

        pushStallTicks = 0;
        noPathTicks = 0;
    }
    private boolean isCaveLike(BlockPos p) {
        World w = mob.world;

        // If we can see sky, it's not a cave.
        if (w.canSeeSky(p.up())) return false;

        // Low skylight means underground/overhang.
        // (1.12) EnumSkyBlock.SKY is skylight only
        int sky = w.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, p.up());
        if (sky >= 8) return false;

        return true;
    }

}