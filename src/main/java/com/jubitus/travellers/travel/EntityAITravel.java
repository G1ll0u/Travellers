package com.jubitus.travellers.travel;

import com.jubitus.travellers.unit.UnitSpeed;
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

    private static final double CATCHUP_SPEED_MULT = 1.35;  // 35% faster in catchup
    // Blocks the remaining distance must shrink by to count as headway
    static final double PROGRESS_IMPROVE_BLOCKS = 1.5;
    private final EntityTraveller mob;
    final TravelTerrain terrain;
    final TravelRescue rescue;
    final TravelBoatMode boats;
    final TravelPathDriver paths;
    final TravelMountPolicy mounts;
    final TravelItineraryFollower itinerary;
    final TravelWaypointPicker waypoints;
    final TravelStuckActions stuckActions;
    final double reachDistSq;
    private final int recalcTicks;
    // water entry trigger for boats
    boolean wasInWater = false;
    int stepBlocks;
    BlockPos currentWaypoint;
    BlockPos targetVillage;
    private boolean arriveInside = false;
    // near-radius cache for the current target
    int targetNearSq = 0;

    public EntityAITravel(EntityTraveller mob, int stepBlocks) {
        this.mob = mob;
        this.terrain = new TravelTerrain(mob);
        this.rescue = new TravelRescue(this, mob, terrain);
        this.boats = new TravelBoatMode(this, mob);
        this.paths = new TravelPathDriver(this, mob);
        this.mounts = new TravelMountPolicy(this, mob);
        this.itinerary = new TravelItineraryFollower(this, mob);
        this.waypoints = new TravelWaypointPicker(this, mob);
        this.stuckActions = new TravelStuckActions(this, mob);
        this.stepBlocks = Math.max(8, stepBlocks);
        this.reachDistSq = 3.0 * 3.0;
        this.recalcTicks = 12;
        this.setMutexBits(1 | 2); // MOVE (1) + LOOK (2) so nothing else steals the head while travelling
    }

    // ---- helpers ----

    double getActiveSpeed() {
        double s = UnitSpeed.travelNav();

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

        // Block random travelling ONLY when they are an active follower.
        if (mob instanceof EntityTravellerWorker && followOrderOwnsMovement((EntityTravellerWorker) mob)) return false;

        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;
        // A visitor strolls beyond the arrival radius; travel must not drag it back to the centre.
        if (!catchup && mob.isVisitingVillage()) return false;

        // only block "already inside" when NOT in catchup
        if (!catchup && (mob.isAtTargetStop() || mob.getTravelCommon().isAtTargetStopHys(false))) return false;

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
            if (followOrderOwnsMovement(w)) return false;
        }

        // In catchup, don't stop just because we're "at target stop"
        if (!catchup && (mob.isAtTargetStop() || mob.getTravelCommon().isAtTargetStopHys(false))) {
            paths.clearNav();
            return false;
        }

        BlockPos tv = getLiveGoalPos();
        if (tv == null) return false;
        if (!tv.equals(this.targetVillage)) retarget(tv);

        arriveInside = mob.getTravelCommon().isAtTargetStopHys(arriveInside);
        return catchup || !arriveInside;
    }

    /** Follow order ON (companion mode idle); travel is allowed with follow OFF (anchor), catchup, a job, or going home. */
    private static boolean followOrderOwnsMovement(EntityTravellerWorker w) {
        return w.isHiredFollower()
                && w.isFollowOrderActive()
                && !w.isJobActive()
                && !w.isCatchupTravel()
                && !w.isGoingHome();
    }

    /**
     * Offscreen (not tracked): never pathfind, never load chunks; only the chunk loader and the
     * offscreen hop move the unit. A hired catchup worker pauses only if truly untracked AND outside
     * the server view distance: the approximate view-distance check has a margin that creates a dead
     * zone where neither offscreen hops nor pathfinding work.
     */
    boolean isOffscreen() {
        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;
            if (w.isHiredFollower() && w.isCatchupTravel()) {
                return w.getTrackingPlayerCount() <= 0
                        && !w.isInPlayerViewDistance();
            }
        }
        return !mob.isTrackedByAnyPlayer();
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

        itinerary.ensureItinerary(true);

        mob.scheduleNextTravelAmbient();
        if (mob.getDistanceSqToCenter(this.targetVillage) <= this.targetNearSq) {
            onArrivedAtVillage();
            return;
        }

        this.currentWaypoint = waypoints.computeNextWaypointToward(this.targetVillage, this.stepBlocks);
        mob.getTravelCommon().setCurrentWaypoint(this.currentWaypoint);
        debugMark(this.currentWaypoint);
        paths.pushPathToWaypoint(true);

        stuckActions.stuck().beginEpisode(stuckActions.episodeKey());
        resetLocalTracking();

        stuckActions.undergroundCacheRefreshTick = -999;
    }

    @Override
    public void resetTask() {

        mob.mountState.setTravelSpeedContext(false);
        wasInWater = mob.isInWater();
        boats.resetLeg();
        // The reason to be in the air was to get somewhere this task no longer owns - come down.
        mob.elytraFlight.abortToLanding();

        this.currentWaypoint = null;
        mob.getTravelCommon().setCurrentWaypoint(null);
        paths.clearNav();
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
        mounts.onTaskStop();
        if (mob instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) mob).setUsingTravelAI(false);
        }
        stuckActions.undergroundCacheRefreshTick = -999;
    }

    @Override
    public void updateTask() {

        // Don't interfere with an active gap jump — let physics carry the entity.
        if (com.jubitus.travellers.pathing.TravellerNavigateGround.isMidGapJump(mob)) return;

        if (!mob.world.isRemote) {
            if (isOffscreen()) {
                if (isInBoat()) {
                    dismountAndDeleteBoat();
                    boats.resetLeg();
                    wasInWater = false;
                }
                paths.clearNav();
                wasInWater = false;
                paths.lastSchedulerDenyTick = mob.ticksExisted;
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
        if (!mob.world.isRemote && (mob.ticksExisted % TravelItineraryFollower.ITINERARY_BACKLOG_CHECK_TICKS) == 0) itinerary.tickItineraryBacklog();
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
            paths.clearNav();
            return;
        }
        // -----------------------------------------
        // Keep target up to date
        // -----------------------------------------
        BlockPos liveTarget = getLiveGoalPos();
        if (liveTarget == null) return;

        if (!liveTarget.equals(targetVillage)) {
            retarget(liveTarget);
            currentWaypoint = waypoints.computeNextWaypointOrHandleBlocked();
            if (mob.isDead) return;
            mob.getTravelCommon().setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            paths.pushPathToWaypoint();
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
            mob.getTravelCommon().setCurrentWaypoint(null);
            paths.clearNav();
        }

        // -----------------------------------------
        // Ensure we always have a waypoint
        // -----------------------------------------
        if (currentWaypoint == null) {
            currentWaypoint = waypoints.computeNextWaypointOrHandleBlocked();
            if (mob.isDead) return;
            mob.getTravelCommon().setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            paths.pushPathToWaypoint();
        }

        // A mounted traveller rides straight at the waypoint whenever the ground ahead takes it
        // (see tryMountedDirectRide) - the planner below is the fallback for ground that doesn't,
        // not the normal mode. While riding direct there is no path to advance, so the path-stall
        // counter must not accumulate into the stuck ladder; real stalls are still caught by
        // noMoveTicks, which watches actual displacement.
        boolean ridingDirect = !mob.world.isRemote && mounts.tryMountedDirectRide();
        if (ridingDirect) {
            if (!mob.getNavigator().noPath()) paths.clearNav();
            paths.pathNoAdvanceTicks = 0;
        }

        // Periodic refresh of pathing toward waypoint. A mounted rider that is neither riding
        // direct nor holding a path is standing completely unattended - the observed shape was a
        // dead stop of up to half a second at the end of every partial route while this gate waited
        // for the recalc phase - so it may ask immediately; pushPathToWaypoint's own scheduler slot
        // and per-mob cooldowns still bound the actual work.
        boolean recalcDue = (mob.ticksExisted + mob.getTravelCommon().getRecalcPhase()) % this.recalcTicks == 0;
        // Bounded by the per-mob path cooldown: "immediately" means "without waiting out the recalc
        // phase on top of the cooldown", never "a search every tick".
        boolean mountedUnattended = mob.isMountedOnBondedMount() && mob.getNavigator().noPath()
                && mob.ticksExisted >= paths.nextComputePathTick;
        if (!ridingDirect && (recalcDue || mountedUnattended)) {

            boolean stuckish =
                    (paths.pathNoAdvanceTicks >= 40)  // ~2s without path index moving
                            || (stuckActions.noMoveTicks >= 40); // ~2s without actual movement

            if (mob.getNavigator().noPath() || stuckish) {
                paths.pushPathToWaypoint(stuckish);
            }

        }

        // Reached waypoint? reseed next.
        // Road waypoints use XZ-only distance: the road block Y may not match terrain Y, and
        // requiring 3D closeness would stall the entity if it passes overhead or underfoot.
        if (currentWaypoint != null && waypoints.isRoadWaypointReached(currentWaypoint)) {
            currentWaypoint = waypoints.computeNextWaypointOrHandleBlocked();
            if (mob.isDead) return;
            mob.getTravelCommon().setCurrentWaypoint(currentWaypoint);
            debugMark(currentWaypoint);
            paths.pushPathToWaypoint();
        }
        Path path = mob.getNavigator().getPath();
        if (path != null) {
            int idx = path.getCurrentPathIndex();
            if (idx == paths.lastPathIndex) paths.pathNoAdvanceTicks++;
            else {
                paths.lastPathIndex = idx;
                paths.pathNoAdvanceTicks = 0;
            }
        } else if (!ridingDirect) {
            // if we have no path while not arrived, treat as stuck-ish too. Riding direct is the
            // exception: no path is its normal state, and noMoveTicks already guards a real stall.
            paths.pathNoAdvanceTicks++;
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
                        && (mob.ticksExisted - paths.lastSchedulerDenyTick) <= 40; // 2 seconds grace

        if (waitingForPathSlot) {
            // Prevent teleport escalation caused purely by throttling
            stuckActions.noMoveTicks = Math.min(stuckActions.noMoveTicks, 20);

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

            return;
        }

        stuckActions.tickMotion(now);

        // ===== Stuck ladder: progress = entering a cell not visited recently (see StuckRecord) =====
        boolean progressed = stuckActions.stuck().visit(now.x, now.y, now.z);

        if (!mob.world.isRemote) {
            // Evidence gathered under the old mount state says nothing about the new one: no hop on the flip tick.
            boolean mountJustChanged = mob.mountState.wasRidingBondedMountLastTick() != mob.isMountedOnBondedMount();
            TravelStuckLadder.Action action = stuckActions.stuck().ladder.tick(progressed, stuckActions.noMoveTicks, stuckActions.undergroundTicks,
                    !mountJustChanged, TravelStuckActions.ladderTimings());
            if (action != TravelStuckLadder.Action.NONE) {
                stuckActions.runStuckAction(action);
                return;
            }
        }
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
        mob.getTravelCommon().setCurrentWaypoint(null);
        paths.clearNav();
        return true;
    }

    @Nullable
    private BlockPos getLiveGoalPos() {
        // Dynamic goal while in catchup (return-to-owner for a worker, back-to-leader for a plain
        // group follower - see FollowCatchup.dynamicGoal()).
        if (mob.isCatchupTravel()) {
            BlockPos cg = mob.catchup.dynamicGoal();
            if (cg != null) return cg;
        }

        // Everyone else: normal village travel goal
        return mob.getTravelCommon().getTargetVillage();
    }

    private void retarget(BlockPos newTarget) {
        this.targetVillage = newTarget;
        itinerary.ensureItinerary(false);
        // A moving catchup goal is the same episode; only a different destination clears the evidence.
        stuckActions.stuck().beginEpisode(stuckActions.episodeKey());

        int near;
        if (mob.isCatchupTravel()) {
            near = 6;
        } else {
            near = mob.arrivalRadiusBlocks(newTarget);
        }
        this.targetNearSq = near * near;

        // Initialize movement/progress baselines
        stuckActions.moveWindowStart = mob.getPositionVector();
        stuckActions.moveWindowTicks = 0;
        stuckActions.moveWindowInit = true;

        boats.allowBoatAgain();
    }

    /** The trap, if any, is behind (a hop landed, a boat crossing ended, another gate is tried). */
    void resetStuckState() {
        stuckActions.stuck().reset();
        resetLocalTracking();
    }

    /** This task's own movement baselines; the stuck evidence itself lives on the entity and survives. */
    private void resetLocalTracking() {
        boats.resetStall(itinerary.progressDist());
        waypoints.consecutiveNoWaypoint = 0;

        stuckActions.noMoveTicks = 0;
        stuckActions.moveWindowStart = mob.getPositionVector();
        stuckActions.moveWindowTicks = 0;
        stuckActions.moveWindowInit = true;

        paths.lastPathIndex = -1;
        paths.pathNoAdvanceTicks = 0;
    }

    private void onArrivedAtVillage() {
        // do NOT clear targetVillage here; route manager uses it to align with Roam
        this.currentWaypoint = null;
        mob.getTravelCommon().setCurrentWaypoint(null);
        paths.clearNav();
        if (isInBoat()) {
            dismountAndDeleteBoat();
        }
        boats.allowBoatAgain();
        // Let Roam start this tick
        // (Roam’s shouldExecute() will pass now because distance ≤ VILLAGE_NEAR)
    }

    /**
     * Debug-only (path test): replaces the itinerary with a forced one through these hand-marked
     * points to the current target, which nothing rebuilds. Pass {@code null} to drop it.
     */
    public void setForcedWaypoints(@Nullable List<BlockPos> waypoints) {
        ItineraryState st = mob.getItineraryState();
        BlockPos dest = mob.getTravelCommon().getTargetVillage();
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

    /** The goal the profiler measures progress against. */
    @Nullable
    public BlockPos getProfileGoal() {
        return targetVillage;
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

}