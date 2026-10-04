package com.jubitus.travellers.travel;

import com.jubitus.travellers.unit.LandingSpots;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.flight.ElytraCapability;
import com.jubitus.travellers.flight.ElytraOpportunityScanner;
import com.jubitus.travellers.follow.FollowRuntimeConfig;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.MountedLeadController;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.pathing.route.TravelPlan;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.itinerary.ItineraryPlanner;
import com.jubitus.travellers.pathing.itinerary.ItineraryState;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EntityAITravel extends EntityAIBase {

    // If we were denied a global scheduler slot while having noPath,
// don't treat it as "stuck" for a short grace period.
    private int lastSchedulerDenyTick = -999999;

    private static final double CATCHUP_SPEED_MULT = 1.35;  // 35% faster in catchup
    private static final int TRAVEL_PATH_COOLDOWN_TICKS = 15;
    private static final int PATH_COOLDOWN_TICKS = 20;
    private static final double NET_MOVE_1S_SQ = 0.35 * 0.35; // < 0.35 blocks over 1s == effectively stuck
    // Blocks the remaining distance must shrink by to count as headway
    static final double PROGRESS_IMPROVE_BLOCKS = 1.5;
    private static final int FAILED_HOP_RETRY_TICKS = 2 * 20;
    private static final int EDGE_RETRY_TICKS = 20;
    private static final double MOVE_EPS_SQ = 0.03 * 0.03;
    // --- Boat / ocean crossing tuning ---
    private final EntityTraveller mob;
    final TravelTerrain terrain;
    private final TravelRescue rescue;
    private final TravelBoatMode boats;
    private final double reachDistSq;
    private final int recalcTicks;
    private int nextTravelSlotTick = 0;   // scheduler pacing (global)
    private int nextComputePathTick = 0;  // actual expensive path compute pacing (per mob)
    private Vec3d moveWindowStart = Vec3d.ZERO;
    private int moveWindowTicks = 0;
    private boolean moveWindowInit = false;
    // --- The one stuck ladder (see TravelStuckLadder) and its progress baseline ---
    @Nullable
    private int lastPathIndex = -1;
    private int pathNoAdvanceTicks = 0;
    // --- water entry trigger for boats ---
    boolean wasInWater = false;
    // --- Underground state cache (avoid world queries every tick) ---
    private boolean cachedUnderground = false;
    private int undergroundCacheRefreshTick = -999;
    private static final int UNDERGROUND_CACHE_TICKS = 20;
    // --- Force surface rescue if underground too long ---
    private int undergroundTicks = 0;
    // ---- Movement-based stuck signal (prevents jitter resetting progress) ----
    private Vec3d lastMovePos = Vec3d.ZERO;
    int noMoveTicks = 0;
    int stepBlocks;
    // --- boat stall tracking ---
    private double speed;
    BlockPos currentWaypoint;
    BlockPos targetVillage;
    private boolean arriveInside = false;
    // near-radius cache for the current target
    private int targetNearSq = 0;
    // simple guard so we don't spam boat spawning

    // --- mounted rider meets water: see MountedLeadController (shared with the follow AIs) ---
    private int nextWaterProbeTick = 0;
    // Itinerary version the progress baselines were taken against (re-baselined when it changes)
    int nextItineraryReplanTick = 0;
    private static final int ITINERARY_REPLAN_COOLDOWN_TICKS = 10 * 20;
    private static final int ITINERARY_BACKLOG_CHECK_TICKS = 40;


    public EntityAITravel(EntityTraveller mob, double speed, int stepBlocks) {
        this.mob = mob;
        this.terrain = new TravelTerrain(mob);
        this.rescue = new TravelRescue(this, mob, terrain);
        this.boats = new TravelBoatMode(this, mob);
        this.speed = speed;
        this.stepBlocks = Math.max(8, stepBlocks);
        this.reachDistSq = 3.0 * 3.0;
        this.recalcTicks = 12;
        this.setMutexBits(1 | 2); // MOVE (1) + LOOK (2) so nothing else steals the head while travelling
    }








    // ---- helpers ----



    double getActiveSpeed() {
        double s = this.speed;

        if (mob.isCatchupTravel()) {
            s *= CATCHUP_SPEED_MULT;
        }

        if (mob.isInWater()) {
            s *= TravellersConfig.COMBAT.swimSpeedMultiplier;
        }

        // Leading the horse on foot over dry ground: hang back (only slow, never stop) so the leash
        // isn't dragging it. No-op while swimming or not leading - see MountedLeadController, applied
        // identically by the follow AIs.
        s *= MountedLeadController.ledRiderPaceMultiplier(mob);

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
        // A traveller/raider group follower is steered entirely by EntityAIFollowLeader (which also
        // tracks a still-virtual leader, see EntityTraveller.tickVirtualGroupLeaderFollow) - it must
        // never independently travel toward its own copied destination, or it silently locks the
        // movement mutex away from the follow AI the first time it starts before the leader is
        // resolvable (see project memory on group materialization). The one exception is catchup: once
        // EntityAIFollowLeader itself has decided this follower is too far behind for short-range
        // formation chasing to work (EntityAIFollowLeader.canUseFollow() bails out via isCatchupTravel()
        // the moment that happens), this task must be allowed to run despite still being grouped, or
        // nothing drives the follower at all.
        boolean catchup = mob.isCatchupTravel();
        if (mob.getGroupLeaderUUID() != null && !catchup) return false;


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
        // A visitor strolls beyond the arrival radius; travel must not drag it back to the centre.
        if (!catchup && mob.isVisitingVillage()) return false;

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
        boolean catchup = mob.isCatchupTravel();
        if (mob.getGroupLeaderUUID() != null && !catchup) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

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
        boats.resetLeg();

        mob.mountState.setTravelSpeedContext(true);

        if (mob instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) mob).setUsingTravelAI(true);
        }
        arriveInside = mob.isAtTargetStop();
        this.targetVillage = getLiveGoalPos();
        if (this.targetVillage == null) return;

        ensureItinerary(true);

        mob.scheduleNextTravelAmbient();
        if (mob.getDistanceSqToCenter(this.targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }

        this.currentWaypoint = computeNextWaypointToward(this.targetVillage, this.stepBlocks);
        mob.setCurrentWaypoint(this.currentWaypoint);
        debugMark(this.currentWaypoint);
        pushPathToWaypoint(true);

        stuck().beginEpisode(episodeKey());
        resetLocalTracking();


        undergroundCacheRefreshTick = -999;
    }

    @Override
    public void resetTask() {

        mob.mountState.setTravelSpeedContext(false);
        wasInWater = mob.isInWater();
        boats.resetLeg();
        // The reason to be in the air was to get somewhere this task no longer owns - come down.
        mob.elytraFlight.abortToLanding();

        this.currentWaypoint = null;
        mob.setCurrentWaypoint(null);
        clearNav();
        resetLocalTracking();
        // Every normal boat-crossing exit goes through dismountAndDeleteBoat() (arrival, stuck
        // timeout...), which is also what restores MountMode.MOUNTED afterward - but a mutex-losing
        // preemption (a higher-priority task like the follow AI reclaiming the movement bits, e.g. an
        // escort follower catching up across water and then its leader/owner getting close enough to
        // hand control back) skips all of those and lands here instead, mid-crossing. Without this,
        // the rider was left stuck in LED forever with no self-heal timer armed for it (that only
        // covers the terrain-dismount/sidestep-wedge LED below) and the boat leaked.
        if (isInBoat()) {
            dismountAndDeleteBoat();
        }
        // Never leave a rider on foot because of terrain this task is no longer routing through -
        // getLedFallbackSinceTick() >= 0 means TravellerMountSupport is still tracking a fallback LED
        // this task caused (or the sidestep give-up did; both share the same tracking).
        if (mob.mountState.getLedFallbackSinceTick() >= 0) {
            TravellerMountSupport.setMountMode(mob, MountMode.MOUNTED);
        }
        if (mob instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) mob).setUsingTravelAI(false);
        }
        undergroundCacheRefreshTick = -999;
    }

    @Override
    public void updateTask() {

        // Don't interfere with an active gap jump — let physics carry the entity.
        if (mob.getNavigator() instanceof com.jubitus.travellers.pathing.TravellerNavigateGround) {
            if (((com.jubitus.travellers.pathing.TravellerNavigateGround) mob.getNavigator()).isGapJumping()) {
                return;
            }
        }

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
                    boats.resetLeg();
                    wasInWater = false;
                }
                clearNav();
                wasInWater = false;
                lastSchedulerDenyTick = mob.ticksExisted;
                return;
            }
        }

        // -----------------------------------------
        // Elytra flight owns movement outright while it lasts. The controller is ticked from
        // EntityTraveller.travel(), not from here, so this branch only has to stay out of its way -
        // every stuck/stall/repath measurement below assumes a walking entity on a path.
        // -----------------------------------------
        if (mob.elytraFlight.isActive()) {
            return;
        }

        // -----------------------------------------
        // Cooldowns for the SIMPLE stuck system
        // -----------------------------------------
        if (!mob.world.isRemote && (mob.ticksExisted % ITINERARY_BACKLOG_CHECK_TICKS) == 0) tickItineraryBacklog();
        // -----------------------------------------
        // Ambient chatter (keep)
        // -----------------------------------------
        if (!mob.world.isRemote && TravellersConfig.SOUND.travellerAmbient) {
            if (mob.ticksExisted >= mob.nextTravelAmbientTick) {
                mob.maybePlayTravelAmbient();
            }
        }

        boats.tickCooldown();

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
            currentWaypoint = computeNextWaypointOrHandleBlocked();
            if (mob.isDead) return;
            mob.setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            pushPathToWaypoint();
        }

        // Arrived?
        boolean catchup = mob.isCatchupTravel();

        if (!catchup && mob.getDistanceSqToCenter(targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }

        // No per-tick "is the chunk ahead loaded" probe here on purpose - it used to be, but it fired
        // off a single directional sample (as little as 1s of no loaded ground ahead) well before the
        // stall backstop below could ever weigh in, even though that backstop is the more reliable
        // signal of the two: it tracks actual distance-to-destination improvement, not raw movement, so
        // the small real sideways moves a mob makes while pinned at the edge (loaded space still exists
        // on every side except toward the destination) don't fool it the way they'd fool a plain
        // no-movement timer. Removed once EntityTraveller.tickEdgeVirtualizeProbe was scoped down to
        // EntityAIFollowLeader followers only, the one case with no equivalent of this backstop.
        // -----------------------------------------
        // Elytra: is the ground ahead worth gliding over? Ordered after the arrival and edge checks
        // so we never take off on top of the destination or toward unloaded terrain. The cheap gates
        // (config, elytra worn, state) come before the budgeted terrain scan, and almost nobody wears
        // an elytra, so this costs a field read for the entire rest of the world's population.
        // -----------------------------------------
        if (tryTakeOff()) return;

        if (boats.tickAfloat()) return;
        // -----------------------------------------
        // Mounted rider meets water: step off and lead the horse in on foot. The plain on-foot
        // travel AI below (swim pathing, boat spawning, shore rescue) then carries the pair across;
        // TravellerMountSupport remounts once both are back on dry ground together. No crossing state
        // machine - just "un-fordable water ahead => LED".
        // -----------------------------------------
        if (!mob.world.isRemote && TravellersConfig.MOUNTS.mountedDismountForWater
                && mob.isMountedOnBondedMount() && mob.ticksExisted >= nextWaterProbeTick) {
            nextWaterProbeTick = mob.ticksExisted + 8;
            if (MountedLeadController.waterAheadNeedsLeading(mob, travelBearingXZ())) {
                MountedLeadController.enterLedForWater(mob);
            }
        }

        boolean inWaterNow = mob.isInWater();
        boats.tickEmbark(inWaterNow);
        wasInWater = inWaterNow;


        // A previously-valid waypoint can go stale if its chunk stopped being loaded/needed after it
        // was picked but before the mob reached it (e.g. it was only transiently loaded, or a player
        // walked away) - the navigator would otherwise just silently fail against it forever, only
        // recovering several seconds later via the slow stall-based escalation. Drop it immediately so
        // the block below recomputes through the fast computeNextWaypointOrHandleBlocked() path instead
        // of waiting for that.
        if (currentWaypoint != null && !LoadedChunks.isAreaLoaded(mob.world, currentWaypoint, 1)) {
            currentWaypoint = null;
            mob.setCurrentWaypoint(null);
            clearNav();
        }

        // -----------------------------------------
        // Ensure we always have a waypoint
        // -----------------------------------------
        if (currentWaypoint == null) {
            currentWaypoint = computeNextWaypointOrHandleBlocked();
            if (mob.isDead) return;
            mob.setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            pushPathToWaypoint();
        }

        // A mounted traveller rides straight at the waypoint whenever the ground ahead takes it
        // (see tryMountedDirectRide) - the planner below is the fallback for ground that doesn't,
        // not the normal mode. While riding direct there is no path to advance, so the path-stall
        // counter must not accumulate into the stuck ladder; real stalls are still caught by
        // noMoveTicks, which watches actual displacement.
        boolean ridingDirect = !mob.world.isRemote && tryMountedDirectRide();
        if (ridingDirect) {
            if (!mob.getNavigator().noPath()) clearNav();
            pathNoAdvanceTicks = 0;
        }

        // Periodic refresh of pathing toward waypoint. A mounted rider that is neither riding
        // direct nor holding a path is standing completely unattended - the observed shape was a
        // dead stop of up to half a second at the end of every partial route while this gate waited
        // for the recalc phase - so it may ask immediately; pushPathToWaypoint's own scheduler slot
        // and per-mob cooldowns still bound the actual work.
        boolean recalcDue = (mob.ticksExisted + mob.getRecalcPhase()) % this.recalcTicks == 0;
        // Bounded by the per-mob path cooldown: "immediately" means "without waiting out the recalc
        // phase on top of the cooldown", never "a search every tick".
        boolean mountedUnattended = mob.isMountedOnBondedMount() && mob.getNavigator().noPath()
                && mob.ticksExisted >= nextComputePathTick;
        if (!ridingDirect && (recalcDue || mountedUnattended)) {

            boolean stuckish =
                    (pathNoAdvanceTicks >= 40)  // ~2s without path index moving
                            || (noMoveTicks >= 40); // ~2s without actual movement

            if (mob.getNavigator().noPath() || stuckish) {
                pushPathToWaypoint(stuckish);
            }

        }


        // Reached waypoint? reseed next.
        // Road waypoints use XZ-only distance: the road block Y may not match terrain Y, and
        // requiring 3D closeness would stall the entity if it passes overhead or underfoot.
        if (currentWaypoint != null && isRoadWaypointReached(currentWaypoint)) {
            currentWaypoint = computeNextWaypointOrHandleBlocked();
            if (mob.isDead) return;
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
        } else if (!ridingDirect) {
            // if we have no path while not arrived, treat as stuck-ish too. Riding direct is the
            // exception: no path is its normal state, and noMoveTicks already guards a real stall.
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

            // Steer toward the waypoint instead of standing still while waiting for a
            // path slot. Only applied when the direct path is clear of lava and cliffs.
            if (!mob.world.isRemote && currentWaypoint != null && !isInBoat()) {
                double d2ToWp = mob.getDistanceSqToCenter(currentWaypoint);
                if (d2ToWp > reachDistSq && d2ToWp <= 8.0 * 8.0
                        && terrain.isDirectPathSafe(currentWaypoint)) {
                    mob.getMoveHelper().setMoveTo(
                            currentWaypoint.getX() + 0.5,
                            currentWaypoint.getY(),
                            currentWaypoint.getZ() + 0.5,
                            getActiveSpeed()
                    );
                }
            }

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

        // Underground detector (caves, overhangs), cached every 20 ticks
        if ((mob.ticksExisted - undergroundCacheRefreshTick) >= UNDERGROUND_CACHE_TICKS) {
            cachedUnderground = terrain.isUndergroundNow();
            undergroundCacheRefreshTick = mob.ticksExisted;
        }
        if (cachedUnderground) undergroundTicks++;
        else undergroundTicks = 0;

        // ===== Stuck ladder: progress = entering a cell not visited recently (see StuckRecord) =====
        boolean progressed = stuck().visit(now.x, now.y, now.z);

        if (!mob.world.isRemote) {
            // Evidence gathered under the old mount state says nothing about the new one: no hop on the flip tick.
            boolean mountJustChanged = mob.mountState.wasRidingBondedMountLastTick() != mob.isMountedOnBondedMount();
            TravelStuckLadder.Action action = stuck().ladder.tick(progressed, noMoveTicks, undergroundTicks,
                    !mountJustChanged, ladderTimings());
            if (action != TravelStuckLadder.Action.NONE) {
                runStuckAction(action);
                return;
            }
        }
        lastMovePos = now;
    }

    private StuckRecord stuck() {
        return mob.getStuckRecord();
    }

    /** What a stuck episode is about: the stop for normal travel, catchup as a whole for a moving goal. */
    private Object episodeKey() {
        return mob.isCatchupTravel() ? StuckRecord.CATCHUP : targetVillage;
    }

    private static TravelStuckLadder.Timings ladderTimings() {
        TravellersConfig.Travel t = TravellersConfig.TRAVEL;
        return new TravelStuckLadder.Timings(t.stuckRepathSeconds * 20, t.stuckDetourSeconds * 20,
                t.stuckReplanSeconds * 20, t.stuckHopSeconds * 20, t.stuckPinnedHopSeconds * 20,
                t.stuckUndergroundHopSeconds * 20, t.stuckHopCooldownSeconds * 20, FAILED_HOP_RETRY_TICKS,
                t.edgeStuckVirtualizeGraceTicks, EDGE_RETRY_TICKS);
    }

    private void runStuckAction(TravelStuckLadder.Action action) {
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "ladder " + action
                + " stall=" + stuck().ladder.stallTicks() + " pin=" + noMoveTicks + " underground=" + undergroundTicks);
        switch (action) {
            case REPATH:
                // Is the horse the reason? Stepping down beats detouring around a wall a rider can't pass.
                if (mob.isMountedOnBondedMount()) considerTerrainDismount(currentWaypoint, true);
                clearNav();
                currentWaypoint = computeNextWaypointOrHandleBlocked();
                if (mob.isDead) return;
                mob.setCurrentWaypoint(currentWaypoint);
                debugMark(currentWaypoint);
                pushPathToWaypoint(true);
                break;
            case DETOUR:
                clearNav();
                if (!tryDetourWide()) {
                    BlockPos escape = findLocalEscapeWaypoint();
                    if (escape != null) {
                        currentWaypoint = escape;
                        mob.setCurrentWaypoint(escape);
                    } else {
                        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "dead end: no reachable detour or escape");
                        stuck().ladder.deadEnd(ladderTimings());
                    }
                }
                pushPathToWaypoint(true);
                break;
            case REPLAN:
                if (abandonUnreachableGate()) break;
                com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_STUCK_REPLAN);
                nextItineraryReplanTick = 0;
                replanItineraryFromHere("no headway for " + stuck().ladder.stallTicks() + " ticks");
                currentWaypoint = null;
                mob.setCurrentWaypoint(null);
                clearNav();
                break;
            case HOP:
                if (!abandonUnreachableGate()) hop();
                break;
            case EDGE_VIRTUALIZE:
                // Refuses unless the ground ahead is unloaded: a unit stuck on loaded terrain keeps climbing the ladder.
                mob.tryVirtualizeStuckAtEdge(targetVillage);
                break;
            default:
                break;
        }
    }

    /** A short, discreet hop: out of the water, up out of a hole, or a few blocks along the route. */
    private void hop() {
        com.jubitus.travellers.debug.trace.TravellerTracer.incident(mob, "stuck",
                "hop firing pin=" + noMoveTicks + " stall=" + stuck().ladder.stallTicks() + " underground=" + undergroundTicks);
        // Each failed hop reaches further, so a unit is never left retrying the same impossible hop.
        int failed = stuck().ladder.failedHops();
        int maxHop = Math.max(TravellersConfig.TRAVEL.stuckHopBlocks, TravellersConfig.TRAVEL.stuckHopMaxBlocks);
        boolean rescued = mob.isInWater() && rescue.teleportToNearestShoreTowardTarget(8, 6);
        if (!rescued) {
            int step = Math.min(maxHop, TravellersConfig.TRAVEL.stuckHopBlocks * (1 + failed));
            TravelRescue.HopResult r = rescue.teleportToResumePoint(resumeRoute(step));
            if (r == TravelRescue.HopResult.DEFERRED) {
                stuck().ladder.hopDeferred();
                return;
            }
            rescued = r == TravelRescue.HopResult.MOVED;
        }
        if (!rescued) rescued = rescue.teleportToSurfaceRescue(Math.min(maxHop, 5 * (1 + failed)), failed == 0 ? 6 : 3);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "hop result=" + (rescued ? "moved" : "failed"));
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, rescued
                ? com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_RESCUE_TP
                : com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_RESCUE_FAILED);
        if (rescued) {
            resetStuckState();
            currentWaypoint = computeNextWaypointToward(targetVillage, stepBlocks);
            mob.setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            pushPathToWaypoint();
            return;
        }
        stuck().ladder.hopFailed(ladderTimings());
    }

    /**
     * Hop candidates along the way the unit means to go, nearest first: (landing column, onward point
     * {@code TRAVEL.stuckHopOnwardBlocks} further on). The itinerary when it has one, else its current
     * waypoint then its goal. Never a straight line at the destination past the waypoint.
     */
    private List<BlockPos[]> resumeRoute(int step) {
        int onward = TravellersConfig.TRAVEL.stuckHopOnwardBlocks;
        int[] dists = {step, step + 4, step + 8};
        List<BlockPos[]> out = new ArrayList<>(dists.length);
        int y = MathHelper.floor(mob.posY);
        ItineraryState st = mob.getItineraryState();
        if (mob.isItineraryDriven() && targetVillage != null && st.isFor(targetVillage)) {
            Itinerary it = st.get();
            st.cursor.advance(it, mob.posX, mob.posZ);
            for (int d : dists) {
                st.cursor.computeCarrot(it, d);
                BlockPos col = new BlockPos(MathHelper.floor(st.cursor.carrotX), y, MathHelper.floor(st.cursor.carrotZ));
                st.cursor.computeCarrot(it, d + onward);
                out.add(new BlockPos[]{col, new BlockPos(MathHelper.floor(st.cursor.carrotX), y, MathHelper.floor(st.cursor.carrotZ))});
            }
            return out;
        }
        if (targetVillage == null) return out;
        double[] xs, zs;
        if (currentWaypoint != null) {
            xs = new double[]{mob.posX, currentWaypoint.getX() + 0.5D, targetVillage.getX() + 0.5D};
            zs = new double[]{mob.posZ, currentWaypoint.getZ() + 0.5D, targetVillage.getZ() + 0.5D};
        } else {
            xs = new double[]{mob.posX, targetVillage.getX() + 0.5D};
            zs = new double[]{mob.posZ, targetVillage.getZ() + 0.5D};
        }
        for (int d : dists) {
            out.add(new BlockPos[]{pointAlong(xs, zs, d, y), pointAlong(xs, zs, d + onward, y)});
        }
        return out;
    }

    private static BlockPos pointAlong(double[] xs, double[] zs, double dist, int y) {
        for (int i = 0; i + 1 < xs.length; i++) {
            double dx = xs[i + 1] - xs[i], dz = zs[i + 1] - zs[i];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (dist <= len || i + 2 == xs.length) {
                double t = len < 1e-6 ? 0.0 : Math.min(1.0, dist / len);
                return new BlockPos(MathHelper.floor(xs[i] + dx * t), y, MathHelper.floor(zs[i] + dz * t));
            }
            dist -= len;
        }
        return new BlockPos(MathHelper.floor(xs[xs.length - 1]), y, MathHelper.floor(zs[zs.length - 1]));
    }

    void clearNav() {
        mob.getNavigator().clearPath();
        lastPathIndex = -1;
        pathNoAdvanceTicks = 0;
    }

    /**
     * Consider gliding this leg instead of walking it. Returns true when a flight started, in which
     * case this tick belongs to the flight controller.
     */
    private boolean tryTakeOff() {
        if (!TravellersConfig.ELYTRA.enabled || !TravellersConfig.ELYTRA.autoTakeoff) return false;
        if (isInBoat() || mob.elytraFlight.isOnCooldown()) return false;
        if (!ElytraCapability.canTakeOff(mob)) return false;
        if (!com.jubitus.travellers.pathing.PathBudget.tryAcquire(mob.world, com.jubitus.travellers.pathing.PathBudget.Client.ELYTRA_SCAN, mob.getEntityId(), 0)) return false;

        Vec3d glide = ElytraOpportunityScanner.findGlideTarget(mob, targetVillage);
        if (glide == null) return false;

        if (!mob.elytraFlight.requestTakeoff(glide)) return false;

        this.currentWaypoint = null;
        mob.setCurrentWaypoint(null);
        clearNav();
        return true;
    }

    @Nullable
    private BlockPos getLiveGoalPos() {
        // Dynamic goal while in catchup (return-to-owner for a worker, back-to-leader for a plain
        // group follower - see EntityTraveller.getDynamicCatchupGoal()/EntityTravellerWorker's own
        // richer override).
        if (mob.isCatchupTravel()) {
            BlockPos cg = mob.getDynamicCatchupGoal();
            if (cg != null) return cg;
        }

        // Everyone else: normal village travel goal
        return mob.getTargetVillage();
    }

    private void retarget(BlockPos newTarget) {
        this.targetVillage = newTarget;
        ensureItinerary(false);
        // A moving catchup goal is the same episode; only a different destination clears the evidence.
        stuck().beginEpisode(episodeKey());

        int near;
        if (mob.isCatchupTravel()) {
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

        boats.allowBoatAgain();
    }

    /**
     * Makes sure an itinerary-driven unit holds an itinerary for {@code targetVillage}. One handed
     * over from the virtual record (same leg) is kept as-is, cursor included; anything else is built
     * fresh through the same {@link ItineraryPlanner} the offscreen simulation uses.
     */
    private void ensureItinerary(boolean traceKept) {
        if (!(mob.world instanceof WorldServer) || targetVillage == null) return;
        if (!mob.isItineraryDriven()) return;
        ItineraryState st = mob.getItineraryState();
        if (st.isFor(targetVillage)) {
            Itinerary it = st.get();
            st.cursor.advance(it, mob.posX, mob.posZ);
            if (traceKept) com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                    String.format(java.util.Locale.ROOT, "itinerary kept src=%s verts=%d seg=%d along=%.0f/%.0f off=%.1f",
                            it.source, it.size(), st.cursor.segment(), st.cursor.along(), it.totalLength(),
                            st.cursor.offRouteDistance()));
            return;
        }
        if (st.isForced()) return;
        buildItinerary();
    }

    private void buildItinerary() {
        WorldServer ws = (WorldServer) mob.world;
        ItineraryPlanner.build(ws, mob.getUniqueID(), mob.getItineraryState(), mob.posX, mob.posY, mob.posZ,
                targetVillage,
                ItineraryPlanner.mayUseRoads(mob instanceof com.jubitus.travellers.unit.EntityTravellerRaider),
                com.jubitus.travellers.pathing.PathBody.isMounted(mob), mob.getCultureId(),
                ws.getTotalWorldTime(), mob);
    }

    /** Retries terrain plans for the itinerary's still-straight bridges (budgeted inside). */
    private void tickItineraryBacklog() {
        ItineraryState st = mob.getItineraryState();
        if (!st.hasBacklog() || !st.isFor(targetVillage)) return;
        ItineraryPlanner.tryPlanBacklog((WorldServer) mob.world, mob.getUniqueID(), st,
                com.jubitus.travellers.pathing.PathBody.isMounted(mob), mob.getCultureId(),
                mob.world.getTotalWorldTime());
    }

    /** Rebuilds the itinerary from the current position (pushed far off it, or crossed water off it). */
    void replanItineraryFromHere(String reason) {
        if (!mob.isItineraryDriven() || mob.getItineraryState().isForced()) return;
        if (mob.ticksExisted < nextItineraryReplanTick) return;
        nextItineraryReplanTick = mob.ticksExisted + ITINERARY_REPLAN_COOLDOWN_TICKS;
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path", "itinerary replan: " + reason);
        buildItinerary();
    }

    /**
     * Progress measure for the stuck ladder: remaining distance along the itinerary when the unit
     * follows one for this leg, else straight XZ distance to the target. Squared.
     */
    /** Remaining distance along the itinerary (or straight to the target), in blocks. */
    double progressDist() {
        double d2 = progressDistSq();
        return d2 == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(d2);
    }

    private double progressDistSq() {
        if (targetVillage == null) return Double.MAX_VALUE;
        ItineraryState st = mob.getItineraryState();
        if (mob.isItineraryDriven() && st.isFor(targetVillage)) {
            st.cursor.advance(st.get(), mob.posX, mob.posZ);
            double rem = st.cursor.remaining(st.get());
            return rem * rem;
        }
        return TravelTerrain.horizDistSq(mob.getPosition(), targetVillage);
    }

    /**
     * The itinerary's carrot resolved to ground: a point {@code step} blocks ahead of the unit's
     * projection onto the polyline (forward-only cursor), halved until it lands on navigable ground.
     */
    @Nullable
    private BlockPos itineraryWaypoint(BlockPos dest, int step) {
        if (!mob.isItineraryDriven()) return null;
        ItineraryState st = mob.getItineraryState();
        if (!st.isFor(dest)) return null;
        Itinerary it = st.get();
        st.cursor.advance(it, mob.posX, mob.posZ);
        if (st.cursor.offRouteDistance() > TravellersConfig.TRAVEL.plannedRouteMaxOffRouteBlocks) {
            replanItineraryFromHere(String.format(java.util.Locale.ROOT, "off route %.0f blocks",
                    st.cursor.offRouteDistance()));
            it = st.get();
            if (it == null || !st.isFor(dest)) return null;
        }
        int carrot = Math.min(TravellersConfig.ROUTING.itineraryCarrotBlocks, step);
        // Near a wall gate, aim short so the unit threads the opening instead of cutting the corner.
        if (st.cursor.distanceToKind(it, Itinerary.GATE, carrot) >= 0.0) {
            carrot = Math.min(carrot, Math.max(8, TravellersConfig.ROUTING.gateApproachBlocks));
        }
        for (int s = carrot; s >= 8; s /= 2) {
            st.cursor.computeCarrot(it, s);
            // On a road the itinerary knows the surface height (a bridge deck, not the gap under it);
            // elsewhere the layer nearest our own height is the best guess.
            int refY = st.cursor.carrotKind == Itinerary.ROAD
                    ? MathHelper.floor(st.cursor.carrotY) + 1 : MathHelper.floor(mob.posY); // road Y is the surface block
            BlockPos ground = terrain.findGroundNear(new BlockPos(MathHelper.floor(st.cursor.carrotX),
                    refY, MathHelper.floor(st.cursor.carrotZ)), refY);
            if (ground != null && terrain.isNavigable(ground)) return ground;
        }
        return null;
    }

    /** The wall gate the itinerary threads here (cursor on its GATE run or one carrot before it), else null. */
    @Nullable
    private BlockPos gateAhead() {
        if (targetVillage == null || !mob.isItineraryDriven()) return null;
        ItineraryState st = mob.getItineraryState();
        if (!st.isFor(targetVillage)) return null;
        Itinerary it = st.get();
        st.cursor.advance(it, mob.posX, mob.posZ);
        if (st.cursor.distanceToKind(it, Itinerary.GATE, TravellersConfig.ROUTING.itineraryCarrotBlocks) < 0.0) return null;
        int last = it.size() - 2;
        int k = st.cursor.segment();
        while (k < last && it.kind(k) != Itinerary.GATE) k++;
        // A GATE run is two segments with the gate between them.
        return k < last && it.kind(k + 1) == Itinerary.GATE ? it.vertex(k + 1) : it.vertex(k);
    }

    /** Stuck at a wall gate: rule it out for this leg and rebuild, so another gate is tried. */
    private boolean abandonUnreachableGate() {
        BlockPos gate = gateAhead();
        if (gate == null || mob.getItineraryState().isForced()) return false;
        if (!mob.getItineraryState().excludedGates.add(
                com.jubitus.travellers.pathing.itinerary.ItineraryGates.gateKey(gate))) return false;
        com.jubitus.travellers.debug.trace.TravellerTracer.incident(mob, "stuck",
                "gate unreachable " + gate.getX() + "," + gate.getZ() + ", rebuilding the itinerary");
        nextItineraryReplanTick = 0;
        replanItineraryFromHere("gate unreachable");
        resetStuckState();
        currentWaypoint = null;
        mob.setCurrentWaypoint(null);
        clearNav();
        return true;
    }







    /** The trap, if any, is behind (a hop landed, a boat crossing ended, another gate is tried). */
    void resetStuckState() {
        stuck().reset();
        resetLocalTracking();
    }

    /** This task's own movement baselines; the stuck evidence itself lives on the entity and survives. */
    private void resetLocalTracking() {
        boats.resetStall(progressDist());
        consecutiveNoWaypoint = 0;

        lastMovePos = mob.getPositionVector();
        noMoveTicks = 0;
        moveWindowStart = lastMovePos;
        moveWindowTicks = 0;
        moveWindowInit = true;

        lastPathIndex = -1;
        pathNoAdvanceTicks = 0;
    }


    void pushPathToWaypoint(boolean force) {
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
        com.jubitus.travellers.pathing.PathBudget.Result slot = com.jubitus.travellers.pathing.PathBudget.acquire(
                mob.world, com.jubitus.travellers.pathing.PathBudget.Client.TRAVEL, mob.getEntityId(), priority);
        com.jubitus.travellers.debug.profile.MovementProfiler.travelSchedule(priority, slot);
        if (slot != com.jubitus.travellers.pathing.PathBudget.Result.GRANTED) {

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
            boats.steerBoatToWaypoint();
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
        boolean ok = navigateTo(currentWaypoint, navSpeed);

        // A route that goes nowhere is not a route. Pick a different waypoint now rather than
        // re-asking for the same unreachable one until the stuck ladder resorts to a teleport.
        if (ok && isPathStillborn(currentWaypoint) && mob.ticksExisted >= nextStillbornDetourTick) {
            nextStillbornDetourTick = mob.ticksExisted + STILLBORN_DETOUR_INTERVAL;
            com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_STILLBORN);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                    "stillborn route to " + currentWaypoint.getX() + "," + currentWaypoint.getZ()
                            + " - detouring instead of walking it");
            // A stillborn route is the shape an enclosure produces, so ask the same question here as
            // on an outright failure: is the horse the reason? Detouring around a wall a rider could
            // simply step over would be busywork.
            considerTerrainDismount(currentWaypoint, false);
            clearNav();
            if (tryDetourWide()) {
                ok = navigateTo(currentWaypoint, navSpeed);
            }
        }

// throttle next path attempt
        nextComputePathTick = mob.ticksExisted + PATH_COOLDOWN_TICKS;

        if (ok) {
            lastPathIndex = -1;
            pathNoAdvanceTicks = 0;
            // Only clear the "mounted routing isn't working" streak on real progress. A lone
            // non-stillborn tick used to reset it unconditionally, but an entity oscillating in
            // place between two adjacent path nodes (spinning between idx=0/2 and idx=1/2 at a
            // spot the horse genuinely cannot fit through) reads as "not stillborn" often enough
            // that the streak never reached UNUSABLE_ROUTES_BEFORE_DISMOUNT - so
            // considerTerrainDismount's on-foot check never ran and the rider spun in place
            // indefinitely instead of stepping down to lead the horse through. noMoveTicks is the
            // existing robust "has this entity actually gone anywhere" signal (used by the stuck
            // rescue ladder), so reuse it here instead of the flaky per-tick stillborn read.
            if (!isPathStillborn(currentWaypoint) && noMoveTicks < 20) unusableRouteStreak = 0;
        }


// If pathfinder refuses, try a shorter hop toward target
        if (!ok && targetVillage != null) {
            int old = this.stepBlocks;
            this.stepBlocks = Math.max(12, old / 2);
            BlockPos retry = computeNextWaypointToward(targetVillage, this.stepBlocks);
            this.stepBlocks = old;

            if (retry != null) {
                this.currentWaypoint = retry;
                mob.setCurrentWaypoint(retry);
                debugMark(retry);
                ok = navigateTo(retry, navSpeed);
            }

            // Both attempts failed and the entity is stuck — try any nearby position to
            // escape enclosed spaces (buildings, etc.) before the teleport system activates.
            if (!ok && !mob.world.isRemote && noMoveTicks >= 40) {
                BlockPos escape = findLocalEscapeWaypoint();
                if (escape != null) {
                    this.currentWaypoint = escape;
                    mob.setCurrentWaypoint(escape);
                    navigateTo(escape, navSpeed);
                }
            }
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
        boats.allowBoatAgain();
        // Let Roam start this tick
        // (Roam’s shouldExecute() will pass now because distance ≤ VILLAGE_NEAR)
    }

    /**
     * Calls tryMoveToXYZ with FOLLOW_RANGE clamped to just past the waypoint distance.
     * Prevents PathNavigateGround from creating a ChunkCache extending far beyond the waypoint,
     * which would load chunks along the road well outside the player's view distance.
     */
    /**
     * Rides straight at the current waypoint when the planner has nothing to offer, so long as the
     * ground between here and there can actually take a horse
     * ({@link TravellerNavigateGround#steerDirect}). Returns true when it took over this tick.
     *
     * <p>Deliberately restricted to a mounted rider: on foot the planner rarely comes up empty (a
     * traveller asks for a one-cell corridor), and this is not the place to change how walking works.
     * A horse asks for a two-cell corridor three blocks tall and in broken terrain the planner fails
     * regularly - which is where standing still and waiting to be teleported came from.
     *
     * <p>Also the honest reading of what a player does: most riding needs no route at all, only a
     * direction and a look at what is in front.
     */
    /** How far a path may end from the waypoint it was asked for before it counts as a partial. */
    private static final double PARTIAL_PATH_TOLERANCE = 3.0D;

    /** A partial route ending this close to where the entity already stands has gained it nothing. */
    private static final double STILLBORN_PATH_RADIUS = 2.5D;

    /** Minimum gap between two stillborn-route detours, so a traveller in a genuinely awkward spot
     * re-aims a couple of times a second at most rather than every path attempt. */
    private static final int STILLBORN_DETOUR_INTERVAL = 40;

    private int nextStillbornDetourTick;

    /**
     * True when the pathfinder answered with a route that neither reaches the waypoint nor gets
     * anywhere: it ends essentially where the entity is standing.
     *
     * <p>Vanilla's {@code PathFinder} never reports failure outright - when the target is unreachable
     * it returns a best-effort route to the closest node it managed to expand, and
     * {@code tryMoveToXYZ} calls that success. Usually that is helpful, since a route that gets part
     * of the way is worth walking. It stops being helpful when the closest reachable node is the one
     * already occupied, and there is nothing in the answer itself to say so.
     *
     * <p>Measured, in mountains: the same waypoint re-requested eight times in a row, each time
     * returning a route ending 39 blocks short at the entity's own position, no movement between
     * them, until the stuck ladder eventually teleported it. Same shape for a worker walled in - it
     * walks to the inside corner nearest the destination and stalls there, which from outside reads
     * as ignoring an open doorway. Both are one missing question: did this route actually gain us
     * anything?
     */
    private boolean isPathStillborn(BlockPos waypoint) {
        net.minecraft.pathfinding.Path path = mob.getNavigator().getPath();
        if (path == null || waypoint == null) return false;
        net.minecraft.pathfinding.PathPoint end = path.getFinalPathPoint();
        if (end == null) return false;

        double missX = (end.x + 0.5D) - (waypoint.getX() + 0.5D);
        double missZ = (end.z + 0.5D) - (waypoint.getZ() + 0.5D);
        if (missX * missX + missZ * missZ <= PARTIAL_PATH_TOLERANCE * PARTIAL_PATH_TOLERANCE) return false;

        double gainX = (end.x + 0.5D) - mob.posX;
        double gainZ = (end.z + 0.5D) - mob.posZ;
        return gainX * gainX + gainZ * gainZ <= STILLBORN_PATH_RADIUS * STILLBORN_PATH_RADIUS;
    }

    /**
     * Records when the pathfinder answered with a route that does not actually reach the waypoint.
     *
     * <p>Vanilla's {@code PathFinder} does not report failure by returning nothing: when it cannot
     * reach the target it hands back a best-effort route to the closest node it managed to touch, and
     * {@code tryMoveToXYZ} reports success. So an entity walled in walks confidently to whichever
     * inside corner is nearest the destination, stops there, and is eventually teleported - which
     * from outside looks exactly like "it ignored the open passage and drove into the corner". A
     * traveller has no reason to look for a way out, because it was never asked to go to the way out:
     * travel waypoints are placed in a straight line toward the village, so in an enclosure they land
     * inside the wall. Nothing in a trace distinguished that from a real pathfinding failure until
     * this line existed.
     */
    private void reportPartialPath(BlockPos waypoint) {
        if (mob.world.isRemote) return;
        if (!com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(mob.getUniqueID())) return;

        net.minecraft.pathfinding.Path path = mob.getNavigator().getPath();
        if (path == null) return;
        net.minecraft.pathfinding.PathPoint end = path.getFinalPathPoint();
        if (end == null) return;

        double dx = (end.x + 0.5D) - (waypoint.getX() + 0.5D);
        double dz = (end.z + 0.5D) - (waypoint.getZ() + 0.5D);
        double miss = Math.sqrt(dx * dx + dz * dz);
        if (miss <= PARTIAL_PATH_TOLERANCE) return;

        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                String.format(java.util.Locale.ROOT,
                        "PARTIAL path: asked %d,%d,%d but route ends %d,%d,%d (%.1f blocks short) - waypoint unreachable",
                        waypoint.getX(), waypoint.getY(), waypoint.getZ(), end.x, end.y, end.z, miss));
    }

    /** Ticks between two rider-sized probes, so a traveller failing to path every tick does not run a
     * second full search every tick alongside it. */
    private static final int PATH_FAILURE_PROBE_INTERVAL = 40;

    private int nextPathFailureProbeTick;

    /**
     * When a mounted traveller fails to get a path, records whether the same destination would have
     * been reachable on foot. That one comparison is what a trace has been missing: "no path" alone
     * never said whether the horse's footprint was the reason.
     *
     * <p>Only runs for an entity actually being traced, and at most every couple of seconds - it
     * costs a second pathfinding search.
     */
    private void reportMountedPathFailure(BlockPos waypoint) {
        considerTerrainDismount(waypoint, false);
    }

    /**
     * Gets off the horse when the horse is the reason the route fails, and leads it through on foot.
     *
     * <p>The trigger is a measurement, not a guess: {@link TravellerNavigateGround#canReachOnFootSized}
     * re-runs the same search with the mount sizing switched off, so "the destination is reachable on
     * foot but not mounted" is established rather than inferred. Anything else - a genuinely
     * unreachable destination, an awkward but passable slope - leaves the rider in the saddle.
     *
     * <p>This is the honest answer to a whole class of terrain rather than a fix for one shape of it.
     * A horse occupies a two-cell footprint, and vanilla's node model requires every cell of that
     * footprint to be walkable at once - so a doorway one block wide, or a wall one block thick that
     * a real horse would simply step across, is impassable to the planner however the sizing is
     * tuned. Chasing those cases inside the footprint model is how this system grew its previous
     * complexity. Stepping down and walking is what a rider does, costs nothing, and covers all of
     * them at once.
     *
     * @param force skip the "several attempts" cushion below and check immediately. Only meant for
     *     the fast no-path rescue's own last-instant look, where the entity has already been
     *     completely motionless for a full 3 seconds - stronger evidence than that cushion exists to
     *     filter out. The normal per-search path ({@link #reportMountedPathFailure}) always passes
     *     {@code false}: one unusable route is ordinary and a lateral detour usually settles it.
     */
    private void considerTerrainDismount(BlockPos waypoint, boolean force) {
        if (mob.world.isRemote || waypoint == null) return;
        if (!TravellersConfig.MOUNTS.terrainDismountEnabled) return;
        if (!mob.isMountedOnBondedMount()) return;
        // A path search launched from a horse still rising out of a jump routinely comes back empty -
        // the node the search starts from is mid-air, past the ground it just left and short of
        // wherever it lands. That is not evidence the horse can't get through; it is evidence the
        // search asked the question at the wrong instant. A trace caught exactly this: a horse jumped
        // a step mid-climb, and while still ascending (posY rising, onGround false) the very next
        // failed search dismounted it into landing on foot, one tick after it had already jumped the
        // hard part. Let it land before judging whether the ground genuinely defeats it.
        Entity mount = mob.getRidingEntity();
        if (mount != null && !mount.onGround) return;
        // Stepping down is a last resort, not a first reaction. One unusable route is ordinary -
        // rough ground produces them and a lateral detour usually settles it. Dismounting on the
        // first one made a rider get off wherever a detour used to fire, which is both wrong and
        // conspicuous. Only a situation that survives several attempts is really about the horse.
        if (!force && ++unusableRouteStreak < UNUSABLE_ROUTES_BEFORE_DISMOUNT) return;
        if (mob.ticksExisted < nextPathFailureProbeTick) return;
        nextPathFailureProbeTick = mob.ticksExisted + PATH_FAILURE_PROBE_INTERVAL;
        if (!(mob.getNavigator() instanceof TravellerNavigateGround)) return;

        boolean onFoot = ((TravellerNavigateGround) mob.getNavigator()).canReachOnFootSized(waypoint);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                "no usable route to " + waypoint.getX() + "," + waypoint.getY() + "," + waypoint.getZ()
                        + " mounted=no onFootSized=" + (onFoot ? "YES (footprint is the blocker)" : "no (unreachable either way)"));
        if (!onFoot) return;

        unusableRouteStreak = 0;
        com.jubitus.travellers.debug.profile.MovementProfiler.ledStarted(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.TERRAIN);
        TravellerMountSupport.setMountMode(mob, MountMode.LED);
        // Arms the shared time+distance self-heal back to MOUNTED - see
        // TravellerMountSupport.markTerrainFallbackLed/considerTerrainFallbackRemount. Ticked every
        // tick from TravellerMountSupport.tickMount() regardless of which AI task is currently
        // running, not just while this task's updateTask() happens to be active.
        TravellerMountSupport.markTerrainFallbackLed(mob);
        TravellerMountSupport.notifyMountLedFallback(mob,
                com.jubitus.travellers.notify.NotificationDefs.MOUNT_LED_TERRAIN_BLOCKED);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "mount",
                "dismounting to lead the horse through terrain it cannot path");
        clearNav();
    }

    /** Consecutive route attempts that produced nothing usable. Reset by any route that does. */
    private static final int UNUSABLE_ROUTES_BEFORE_DISMOUNT = 3;

    private int unusableRouteStreak;

    // =====================================================================================
    // Mounted rider meets water
    //
    // Deliberately tiny: the only mounted-specific rule is "un-fordable water right ahead => step
    // off and lead the horse in". Everything after that - swimming, boats, shore rescue, remounting
    // on the far bank - is the ordinary on-foot travel AI plus TravellerMountSupport's leash/remount
    // reconcile. No crossing state machine (that version fought the navigator and oscillated).
    // =====================================================================================

    /** Bearing (XZ, normalised) from the mob toward its current aim (waypoint, else target village). */
    @Nullable
    double[] travelBearingXZ() {
        BlockPos aim = currentWaypoint != null ? currentWaypoint : targetVillage;
        if (aim == null) return null;
        double dx = (aim.getX() + 0.5) - mob.posX;
        double dz = (aim.getZ() + 0.5) - mob.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) return null;
        return new double[]{dx / len, dz / len};
    }

    /** Ticks direct riding stays off after a refused corridor, so the planner's route actually gets
     * walked instead of being recomputed and discarded at every clear/blocked boundary. */
    private static final int DIRECT_RIDE_RETRY_TICKS = 20;

    private int directRideRetryAt;
    private BlockPos lastDirectRideWaypoint;

    /**
     * How a mounted traveller normally travels: straight at the waypoint, the way a player rides -
     * committing to one proven stretch of ground at a time rather than a planned route. The aim
     * point rolls ahead of the horse by {@code MOUNTS.mountedDirectLegBlocks} along the bearing to
     * the waypoint, and {@link TravellerNavigateGround#steerDirect} proves that stretch with the
     * same footprint corridor the planner uses (terrain-following, ford-depth water, full-body
     * sweep) before committing, re-proving as the horse advances. Slopes ride as slopes - up to the
     * corridor's total-rise budget per stretch, any rideable descent - and anything the stretch
     * can't take (a 2-block ledge, a tree line, deep water) refuses the corridor and hands travel
     * to the A* planner, whose own hop candidates and the reactive jump take over. A refusal backs
     * off for {@link #DIRECT_RIDE_RETRY_TICKS} so the planner's route is walked, not thrashed.
     *
     * <p>This is deliberately first, not a no-path fallback: over open or mountainous ground the
     * planner's partial best-effort routes each end somewhere slightly different, and walking one
     * after another reads as looping and zigzagging. The straight line needs no route at all.
     *
     * <p>The aim point is quantized to block centres so {@code steerDirect}'s cached corridor
     * verdict holds between ticks instead of re-proving on every fraction of drift.
     */
    private boolean tryMountedDirectRide() {
        if (!mob.isMountedOnBondedMount()) return false;
        if (currentWaypoint == null || isInBoat()) return false;
        // A refusal only speaks for the leg it refused - a new waypoint is a new bearing, so the
        // backoff doesn't carry over. Without this, the rider goes planner-mode for up to a second
        // after every waypoint switch near whatever refused the previous leg, which read as a
        // hesitation or a little loop at each waypoint.
        if (!currentWaypoint.equals(lastDirectRideWaypoint)) {
            lastDirectRideWaypoint = currentWaypoint;
            directRideRetryAt = 0;
        }
        if (mob.ticksExisted < directRideRetryAt) return false;
        if (!(mob.getNavigator() instanceof TravellerNavigateGround)) return false;

        double dx = currentWaypoint.getX() + 0.5D - mob.posX;
        double dz = currentWaypoint.getZ() + 0.5D - mob.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 2.0D) return false;

        double leg = Math.min(dist, TravellersConfig.MOUNTS.mountedDirectLegBlocks);
        double ax = Math.floor(mob.posX + dx / dist * leg) + 0.5D;
        double az = Math.floor(mob.posZ + dz / dist * leg) + 0.5D;

        TravellerNavigateGround nav = (TravellerNavigateGround) mob.getNavigator();
        if (!nav.steerDirect(ax, az, getActiveSpeed())) {
            directRideRetryAt = mob.ticksExisted + DIRECT_RIDE_RETRY_TICKS;
            return false;
        }
        // Riding straight IS the route working - the planner's failure streak must not survive it,
        // or a rough patch minutes ago still counts toward a dismount here.
        unusableRouteStreak = 0;
        return true;
    }

    private boolean navigateTo(BlockPos waypoint, double speed) {
        net.minecraft.entity.ai.attributes.IAttributeInstance followRangeAttr =
                mob.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.FOLLOW_RANGE);
        double fullRange = followRangeAttr.getBaseValue();
        double dist = Math.sqrt(mob.getDistanceSq(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5));
        double safeRange = Math.max(16.0, dist + 8.0);
        boolean clamped = safeRange < fullRange;
        if (clamped) followRangeAttr.setBaseValue(safeRange);
        boolean ok = mob.getNavigator().tryMoveToXYZ(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, speed);
        if (clamped) followRangeAttr.setBaseValue(fullRange);
        if (ok) reportPartialPath(waypoint); else reportMountedPathFailure(waypoint);
        if (com.jubitus.travellers.config.TravellersConfig.GAP_JUMP.debugGapJump) {
            net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                    "[Travellers/GapJump] %s#%d: navigateTo waypoint %d,%d,%d from %.2f,%.2f,%.2f -> pathfind %s",
                    mob.getName(), mob.getEntityId(), waypoint.getX(), waypoint.getY(), waypoint.getZ(),
                    mob.posX, mob.posY, mob.posZ, ok ? "OK" : "FAILED (noPath)");
        }
        return ok;
    }

    /**
     * Compute a "next" waypoint toward 'dest' that is inside currently loaded chunks.
     * Does not force-load anything. Returns null if no safe waypoint is found.
     * Now tries straight toward the target first, then small symmetric detours.
     */
    // Consecutive normal-progression waypoint computations (not boat mode, not the "arrived" case -
    // arrival is already handled before any of these call sites run) that found no candidate at all in
    // any direction. This is a much faster signal than the stall-based backstop above: an unloaded
    // chunk immediately ahead is detected the moment the route tries to advance into it, not several
    // seconds later once movement has visibly stalled.
    private int consecutiveNoWaypoint = 0;
    private static final int NO_WAYPOINT_VIRTUALIZE_THRESHOLD = 2;

    /**
     * Wraps {@link #computeNextWaypointToward} for the normal route-advance call sites (waypoint
     * reached, retarget, no waypoint yet). If it genuinely can't find any loaded/navigable candidate in
     * any direction, twice in a row, first tries handing off to the offscreen simulation instead of
     * leaving the mob to bump around near the edge until the slower stall backstop kicks in -
     * {@code tryVirtualizeStuckAtEdge()} itself still refuses to fire near a player, so this can never
     * make a unit vanish while someone is watching. Callers must check {@code mob.isDead} immediately
     * after calling this and bail out if true.
     *
     * <p>When virtualization also refuses (loaded terrain, not an unloaded edge - the pocket/enclosure
     * case this exists for), the blind local terrain probing that produced the repeated null has
     * already demonstrably failed: hand the real, farther destination straight to
     * {@code navigateTo()} instead of guessing another local hop. Vanilla's own search range bound
     * ({@code PathNavigate.getPathSearchRange()}) already caps the cost of that honest ask to the same
     * class of search every waypoint hop already pays for.
     */
    @Nullable
    private BlockPos computeNextWaypointOrHandleBlocked() {
        BlockPos wp = computeNextWaypointToward(targetVillage, stepBlocks);
        if (wp != null) {
            consecutiveNoWaypoint = 0;
            return wp;
        }
        if (mob.world.isRemote) return null;
        if (++consecutiveNoWaypoint < NO_WAYPOINT_VIRTUALIZE_THRESHOLD) return null;
        if (mob.tryVirtualizeStuckAtEdge(targetVillage)) return null;
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_NO_WAYPOINT);
        return picked("destinationFallback", targetVillage, targetVillage);
    }

    /**
     * Debug-only (path test): replaces the itinerary with a forced one through these hand-marked
     * points to the current target, which nothing rebuilds. Pass {@code null} to drop it.
     */
    public void setForcedWaypoints(@Nullable List<BlockPos> waypoints) {
        ItineraryState st = mob.getItineraryState();
        BlockPos dest = mob.getTargetVillage();
        if (waypoints == null || waypoints.isEmpty() || dest == null) {
            if (st.isForced()) st.clear();
            return;
        }
        List<BlockPos> pts = new ArrayList<>();
        pts.add(mob.getPosition());
        pts.addAll(waypoints);
        pts.add(dest);
        st.setForced(Itinerary.of(pts, Itinerary.WALK, TravelPlan.QUALITY_REAL, "forced"), mob.posX, mob.posZ);
    }

    BlockPos computeNextWaypointToward(BlockPos dest, int baseStepIgnored) {
        if (dest == null) return null;

        if (shouldUseHeightAwareCatchup(dest)) {
            BlockPos wp = computeHeightAwareWaypointToward(dest);
            if (wp != null) {
                return picked("heightAware", wp, dest);
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


        if (boatMode) return boats.afloatWaypoint(from, dest, dir, step);

        // --- Itinerary: the leg's route polyline, shared with the offscreen simulation ---
        BlockPos carrot = itineraryWaypoint(dest, step);
        if (carrot != null) return picked("itinerary", carrot, dest);

        BlockPos bestPath = null;
        // --- 1) straight candidates, no lane offset ---
        for (int s = step; s >= 16; s -= 16) {
            BlockPos candidate = terrain.surfaceAtOffset(from, dir, s, Vec3d.ZERO);
            if (candidate != null) {
                candidate = preferShoreWhenInWater(from, candidate);

                if (candidate != null) {
                    if (terrain.isPathBelow(candidate)) {
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
                    return picked("straight", bestPath, dest);
                }
            }
        }

        // --- 1b) short-leg tail: `step` can legitimately be below 16 (dynamicStep's own minStep is
        // 12, and any real distance-to-target under 16 forces it lower still), but the loop above
        // never tries anything under 16 - so a short leg fell straight through to the angle-based
        // detour tier below every single time. That tier takes the first candidate that resolves,
        // not the one scored best toward the target, and a fresh call a few ticks later - after the
        // entity has drifted a fraction of a block - can resolve a *different* angle first. Measured:
        // a debug-test traveller sent on a ~10-block hop (step computed as 10) repathed every ~3
        // ticks, each new waypoint shifted by about a block from the last, and it never reached
        // normal walking speed. This same tail runs, briefly and usually unnoticed, in the last
        // stretch of every real journey too - it just never dominated a whole trip before.
        if (bestPath == null && step < 16) {
            for (int s = Math.min(step, 12); s >= 4; s -= 4) {
                BlockPos candidate = terrain.surfaceAtOffset(from, dir, s, Vec3d.ZERO);
                if (candidate != null) {
                    candidate = preferShoreWhenInWater(from, candidate);
                    if (candidate != null) {
                        return picked("straightShort", candidate, dest);
                    }
                }
            }
        }

        // --- 2) small symmetric detours, still no lane offset ---
        // Reached only once every straight-tier distance has already failed to produce any
        // standable/loaded candidate - structurally rare, not the per-waypoint hot path, so the
        // extra A* reachability check below is affordable here.
        int detStep = Math.max(12, step / 2);
        int[] detAngles = new int[]{30, -30, 60, -60, 90, -90};
        TravellerNavigateGround navGround = (TravellerNavigateGround) mob.getNavigator();

        for (int angleDeg : detAngles) {
            Vec3d r = terrain.rotateY(dir, Math.toRadians(angleDeg));
            BlockPos candidate = terrain.surfaceAtOffset(from, r, detStep, Vec3d.ZERO);
            if (candidate != null) {
                candidate = preferShoreWhenInWater(from, candidate);
                if (candidate != null && navGround.isReachable(candidate)) {
                    return picked("smallDetour" + angleDeg, candidate, dest);
                }
            }
        }

        // --- 3) tiny random reseed (unchanged) ---
        Random rand = mob.getRNG();
        for (int i = 0; i < 6; i++) {
            int nx = from.getX() + rand.nextInt(9) - 4;
            int nz = from.getZ() + rand.nextInt(9) - 4;
            BlockPos guess = new BlockPos(nx, from.getY(), nz);
            BlockPos surface = terrain.findSurfaceOrWaterY(guess);
            if (surface != null && LoadedChunks.isAreaLoaded(mob.world, surface, 1) && terrain.isNavigable(surface)) {
                return picked("randomReseed", surface, dest);
            }
        }

        return null;
    }

    /** Trace-only: records which tier produced a waypoint and how far it points toward the goal. */
    BlockPos picked(String source, BlockPos wp, BlockPos dest) {
        if (!mob.world.isRemote && com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(mob.getUniqueID())) {
            double fwd = terrain.forwardComponent(wp, mob.getPosition(), TravelTerrain.goalDirXZ(mob, dest));
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                    String.format(java.util.Locale.ROOT, "waypoint %d,%d,%d src=%s fwd=%.1f",
                            wp.getX(), wp.getY(), wp.getZ(), source, fwd));
        }
        return wp;
    }











    /**
     * Scans all 8 compass directions at 6 blocks to find any navigable position.
     * Used as a last resort when the entity is stuck in an enclosed space
     * and normal directional waypoints all fail.
     */
    @Nullable
    private BlockPos findLocalEscapeWaypoint() {
        BlockPos from = mob.getPosition();
        TravellerNavigateGround navGround = (TravellerNavigateGround) mob.getNavigator();
        for (int a = 0; a < 8; a++) {
            double rad = Math.PI * 2.0 * a / 8.0;
            int nx = from.getX() + (int) Math.round(Math.sin(rad) * 6);
            int nz = from.getZ() + (int) Math.round(Math.cos(rad) * 6);
            BlockPos candidate = terrain.findSurfaceOrWaterY(new BlockPos(nx, from.getY(), nz));
            if (candidate != null && LoadedChunks.isAreaLoaded(mob.world, candidate, 1) && terrain.isNavigable(candidate)
                    && navGround.isReachable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The goal the profiler measures progress against. */
    @Nullable
    public BlockPos getProfileGoal() {
        return targetVillage;
    }

    private boolean tryDetourWide() {
        if (this.targetVillage == null) return false;
        BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(targetVillage.getX() - from.getX(), 0, targetVillage.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return false;

        Vec3d base = to.normalize();
        int follow = (int) Math.floor(mob.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getAttributeValue());
        int maxStep = Math.max(6, follow - 2);               // never ask pathfinder beyond follow range
        int detStep = Math.min(Math.max(24, this.stepBlocks), maxStep);

        // Full circle, smallest deviation first - a candidate pointing away from the target is
        // exactly the shape of the real escape out of a U-shaped obstacle or a pocket, so it must
        // stay tryable, not filtered out (see acceptDetourCandidate). Each candidate is now also
        // reachability-validated, which is what actually rules out a dead end instead of geometry.
        int[] ANG = new int[]{30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};
        TravellerNavigateGround navGround = (TravellerNavigateGround) mob.getNavigator();

        for (int angleDeg : ANG) {
            Vec3d dir = terrain.rotateY(base, Math.toRadians(angleDeg));
            BlockPos candidate = terrain.surfaceAtOffset(from, dir, detStep,
                    new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getPathLaneOffset() * 0.5));

            if (candidate == null) continue; // no standable/loaded terrain at all at this angle

            if (!acceptDetourCandidate(candidate, from)) {
                com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                        "detour ang=" + angleDeg + " rejected:backslide");
                continue;
            }
            if (!navGround.isReachable(candidate)) {
                com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                        "detour ang=" + angleDeg + " rejected:unreachable");
                continue;
            }

            this.currentWaypoint = candidate;
            mob.setCurrentWaypoint(candidate);
            debugMark(this.currentWaypoint);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                    "detour ang=" + angleDeg + " accepted");
            com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_DETOUR_WIDE);
            return true;
        }
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_DETOUR_WIDE_FAILED);
        return false;
    }

    // XZ only: a travel waypoint is a surface column, its Y is incidental. A 3D check against a Y
    // resolved onto a canopy or a ledge can never be satisfied.
    private boolean isRoadWaypointReached(BlockPos wp) {
        double reachSq = reachDistSq;
        if (mob.isMountedOnBondedMount()) {
            double reach = TravellersConfig.MOUNTS.mountedWaypointReachRadius;
            reachSq = reach * reach;
        }
        double dx = mob.posX - (wp.getX() + 0.5);
        double dz = mob.posZ - (wp.getZ() + 0.5);
        return dx * dx + dz * dz <= reachSq;
    }








    // --- DEBUG ONLY ---
    void debugMark(@Nullable BlockPos p) {
        if (!TravellersConfig.GENERAL.debug) return;
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




    /**
     * Compute a dynamic step size:
     * - Far from the village: use the full stepBlocks (e.g. 64).
     * - Closer in: shrink down so we can thread gates/streets precisely.
     */
    int dynamicStep(BlockPos from, BlockPos dest) {
        double dist = Math.sqrt(from.distanceSq(dest));

        // How close is “arrival-ish”?
        // We already have targetNearSq from retarget(), so use that to define a “near” radius.
        double nearRadius = Math.max(24.0, Math.sqrt(this.targetNearSq) * 2.0); // start shrinking inside ~2× arrival radius
        double farRadius = 160.0;  // fully “far” beyond this (tweak as needed)

        int minStep = 12;              // smallest waypoint step near village
        int maxStep = this.stepBlocks; // e.g. 64

        // When a follower in catchup is within view distance, the MC 1.12 pathfinder must handle
        // the navigation (no more offscreen hops). Cap step to what the A* search can realistically
        // solve.
        boolean inViewCatchup = false;
        if (mob.isCatchupTravel() && mob.isInPlayerViewDistance()) {
            inViewCatchup = true;
            maxStep = Math.min(maxStep, 24);
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
        if (terrain.isStandable(forwardSameY)) return forwardSameY;

        return candidateTowardTarget;
    }

    boolean isInBoat() {
        return mob.isRiding() && mob.getRidingEntity() instanceof EntityBoat;
    }

    void dismountAndDeleteBoat() {
        TravellerBoats.leave(mob);
    }

    /** A short hop toward {@code target} (offscreen-hop and stuck-rescue callers). */
    public boolean teleportToward(BlockPos target, int stepBlocks) {
        return rescue.teleportToward(target, stepBlocks);
    }

    public boolean teleportToSurfaceRescue(int radius, int minRise) {
        return rescue.teleportToSurfaceRescue(radius, minRise);
    }



















    // A detour candidate is deliberately not required to point forward toward the target - the
    // whole point of a detour is that the direct/forward direction is blocked, so the real opening
    // out of a pocket or U-shaped obstacle is routinely sideways or behind. Reachability (checked
    // by the caller, via TravellerNavigateGround.isReachable) is what actually validates a
    // candidate now; this is just a tie-breaker among reachable ones.
    private boolean acceptDetourCandidate(BlockPos cand, BlockPos from) {
        if (cand == null || targetVillage == null) return false;

        // Don't accept a waypoint that is clearly farther from target (allow tiny backslide)
        double cur = TravelTerrain.horizDistSq(from, targetVillage);
        double cd = TravelTerrain.horizDistSq(cand, targetVillage);
        double allowBackslideBlocks = 6.0;
        return !(cd > cur + allowBackslideBlocks * allowBackslideBlocks);
    }




    void pushPathToWaypoint() {
        pushPathToWaypoint(false);
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
    private boolean shouldUseHeightAwareCatchup(BlockPos dest) {
        if (!mob.isCatchupTravel()) return false;

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        double vert = Math.abs(mob.getEntityBoundingBox().minY - dest.getY());
        boolean covered = terrain.isCoveredGoal(dest, cfg.cave.minCoverBlocks);

        if (covered) return true;
        return vert >= cfg.catchup.heightAwareVerticalGap;
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
            return pickBestHeightAwareCandidate(dest.getX(), dest.getZ(), dest.getY(), dest, from, terrain.isCoveredGoal(dest, cfg.cave.minCoverBlocks));
        }

        dir = dir.normalize();

        double dist = Math.sqrt(from.distanceSq(dest));
        int step = MathHelper.clamp(dynamicStep(from, dest), 8, 20);
        boolean preferCovered = terrain.isCoveredGoal(dest, cfg.cave.minCoverBlocks);

        for (int s = step; s >= 6; s -= 2) {
            double t = Math.min(1.0D, s / Math.max(1.0D, dist));
            int xBase = from.getX() + MathHelper.floor(dir.x * s);
            int zBase = from.getZ() + MathHelper.floor(dir.z * s);
            int yCenter = (int) Math.round(terrain.lerp(from.getY(), dest.getY(), Math.min(0.95D, t + 0.10D)));

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
                    if (!LoadedChunks.isAreaLoaded(mob.world, feet, 1)) continue;

                    if (terrain.isSwimmable(feet)) {
                        BlockPos snapped = terrain.snapToWaterSurface(feet);
                        if (snapped == null) continue;
                        feet = snapped;
                    } else {
                        if (!terrain.isStandable(feet)) continue;
                        feet = terrain.adjustTreeTopToGround(feet);
                        if (!terrain.isStandable(feet)) continue;

                        IBlockState below = mob.world.getBlockState(feet.down());
                        if (!terrain.isNaturalGroundSupport(feet.down()) && !com.jubitus.travellers.ground.Footing.isFloor(mob.world, feet.down(), below)) {
                            continue;
                        }
                    }

                    if (!terrain.isNavigable(feet)) continue;

                    // A walking target, not a placement: buildings allowed.
                    if (!LandingSpots.isSafe(mob.world, feet, mob, true, true)) continue;

                    double score = 0.0D;
                    score += TravelTerrain.horizDistSq(feet, dest) * 0.06D;
                    score += Math.abs(feet.getY() - dest.getY()) * 2.25D;
                    score += Math.abs(feet.getY() - yCenter) * 0.65D;
                    score -= TravelTerrain.horizDistSq(from, feet) * 0.008D;

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


    private void debug(String tag, String message) {
        if (!TravellersConfig.GENERAL.debugFollowTeleport) return;
        if (mob == null || mob.world == null || mob.world.isRemote) return;
        MillLog.major(mob, "[TravelDebug][" + tag + "][id=" + mob.getEntityId() + "] " + message);
    }

}