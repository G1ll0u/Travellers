package com.jubitus.travellers.virtual;

import com.jubitus.travellers.unit.UnitSpeed;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ambient.EntityAIAutoEat;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.VirtualizationGate;
import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.pathing.MPBIntegration;
import com.jubitus.travellers.pathing.PathBody;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.itinerary.ItineraryCursor;
import com.jubitus.travellers.pathing.itinerary.ItineraryPlanner;
import com.jubitus.travellers.pathing.route.RouteCostParams;
import com.jubitus.travellers.pathing.route.TravelPlan;
import com.jubitus.travellers.pathing.route.TravelRouteJob;
import com.jubitus.travellers.pathing.route.TravelRoutePlanner;
import com.jubitus.travellers.pathing.terrain.TerrainGridView;
import com.jubitus.travellers.pathing.terrain.TerrainProfileGrid;
import com.jubitus.travellers.pathing.terrain.TerrainWorldState;
import com.jubitus.travellers.camp.CampOccupant;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.camp.CampsiteRecord;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.ground.chunk.TravellerChunkManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.*;

public class VirtualTravellerManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersVirtualWorkers";

    /** Ticks each offscreen step covers (one step every N ticks): a step moves a record that far ahead at once. */
    public static final int SIM_INTERVAL = 20;

    /** Live virtual-sim step distance: the same travel pace as a loaded unit, so a config change applies at once. */
    private static double currentSimStep() {
        return UnitSpeed.blocksPerTick(UnitSpeed.travelPercent()) * SIM_INTERVAL;
    }

    // After player-triggered materialization, block re-virtualization for this many ticks
    private static final int MATERIALIZE_GRACE_TICKS = 60;
    /** A camping record this close to its slot counts as standing in it. */
    private static final double CAMP_SLOT_REACHED_BLOCKS = 2.0;

    /** A tied horse farther than this from its rider going offscreen is left standing at its post. */

    // Max forced materializations per tick (hired workers are prioritized)
    private static final int DELIVERY_PER_TICK = 2;

    // How often to refresh dynamic goals (ticks)
    private static final int GOAL_REFRESH_INTERVAL = 20;

    // Check materialization every N ticks
    private static final int MATERIALIZE_CHECK_INTERVAL = 5;

    // Check job arrivals every N ticks (positions only advance every SIM_INTERVAL)
    private static final int JOB_ARRIVAL_CHECK_INTERVAL = 10;

    // Check virtualization every N ticks (reduced for faster dead-zone exit)
    private static final int VIRTUALIZE_CHECK_INTERVAL = 10;

    private final Map<UUID, VirtualTravellerRecord> records = new HashMap<>();
    private final VirtualWorkerMissions missions = new VirtualWorkerMissions(this, records);
    private final VirtualVillageVisits visits = new VirtualVillageVisits(this, records);
    private final Map<UUID, Long> graceUntil = new HashMap<>();
    /**
     * Mirror of {@link #graceUntil}: blocks re-materializing for a moment after a unit went virtual.
     *
     * <p>Needed because the edge probe deliberately virtualizes a unit that is standing on a LOADED
     * chunk - it is the ground *ahead* that is not loaded. Without this, the record it leaves behind is
     * immediately eligible to materialize again on the very same chunk, and the unit oscillates:
     * appear, walk one second up to the boundary, vanish, appear... The grace gives the offscreen
     * simulation time to carry the record clear of the loaded area (chunks are 16 blocks wide, so a
     * couple of seconds of virtual walking), after which the ordinary "is my chunk loaded" rule holds
     * on its own.
     */
    private final Map<UUID, Long> materializeGraceUntil = new HashMap<>();

    private long lastSimTick = -1;
    private long lastMatCheckTick = -1;
    private long lastVirtCheckTick = -1;
    private long lastJobArrivalTick = -1;

    public VirtualTravellerManager(String name) {
        super(name);
    }

    public VirtualTravellerManager() {
        super(DATA_NAME);
    }

    public static VirtualTravellerManager get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        VirtualTravellerManager mgr = (VirtualTravellerManager) storage.getOrLoadData(VirtualTravellerManager.class, DATA_NAME);
        if (mgr == null) {
            mgr = new VirtualTravellerManager(DATA_NAME);
            storage.setData(DATA_NAME, mgr);
        }
        return mgr;
    }

    public void tick(WorldServer world, long now) {
        boolean empty = isServerEmpty();
        boolean paused = empty && TravellersConfig.OFFSCREEN.pauseWhenServerEmpty;

        // Fold in any coarse terrain polylines that finished planning. Bookkeeping only (it rewrites
        // a route, it does not move anyone), so it runs even while the simulation is paused.
        drainRoutePlans(world);

        if (paused) {
            // Paused means "the offscreen world stops advancing", NOT "stop keeping the books". The
            // virtualize pass keeps running so anything still loaded settles offscreen and the server
            // reaches a clean, genuinely idle state - otherwise a unit left standing in a permanently
            // loaded area (the spawn chunks) would keep ticking its AI all night with the one pass that
            // could retire it switched off. Materializing stays off: a unit that appears while the
            // simulation is frozen would just stand there until someone logs in.
            graceUntil.entrySet().removeIf(e -> now > e.getValue());
            materializeGraceUntil.entrySet().removeIf(e -> now > e.getValue());
            if (now - lastVirtCheckTick >= VIRTUALIZE_CHECK_INTERVAL) {
                lastVirtCheckTick = now;
                checkVirtualize(world, now);
            }
            return;
        }
        // Slowing down means firing each pass less often while every pass still does the same amount
        // of work - a step is a step. Stretching the intervals therefore slows movement, goals,
        // arrivals and the materialize/virtualize checks all by the same factor, which is what makes
        // it read as "the world runs slower" rather than "some parts of it lag behind the others".
        double stretch = (empty && TravellersConfig.OFFSCREEN.slowWhenServerEmpty)
                ? 1.0 / Math.max(0.01, TravellersConfig.OFFSCREEN.emptyServerSpeedMultiplier)
                : 1.0;

        // Clean expired grace periods
        graceUntil.entrySet().removeIf(e -> now > e.getValue());
        materializeGraceUntil.entrySet().removeIf(e -> now > e.getValue());

        // Refresh goals for dynamic targets (catchup / follow owner)
        if (now - lastGoalRefresh >= every(GOAL_REFRESH_INTERVAL, stretch)) {
            lastGoalRefresh = now;
            refreshAllGoals(world, now);
        }

        // Advance virtual positions
        if (now - lastSimTick >= every(SIM_INTERVAL, stretch)) {
            lastSimTick = now;
            advanceVirtual(now);
        }

        // Check for job arrivals. Throttled like every other pass here: offscreen positions only move
        // once per SIM_INTERVAL, so scanning every record every tick re-asked a question whose inputs
        // had not changed 19 times out of 20.
        if (now - lastJobArrivalTick >= every(JOB_ARRIVAL_CHECK_INTERVAL, stretch)) {
            lastJobArrivalTick = now;
            missions.checkArrivals(world);
        }

        // Check for player-triggered materialization
        if (now - lastMatCheckTick >= every(MATERIALIZE_CHECK_INTERVAL, stretch)) {
            lastMatCheckTick = now;
            checkPlayerMaterialize(world, now);
        }

        // Check which real workers should become virtual
        if (now - lastVirtCheckTick >= every(VIRTUALIZE_CHECK_INTERVAL, stretch)) {
            lastVirtCheckTick = now;
            checkVirtualize(world, now);
        }

        if (!records.isEmpty()) markDirty();
    }

    /** One pass's interval, stretched by the empty-server slow-down (1.0 = untouched). */
    private static long every(int interval, double stretch) {
        return stretch == 1.0 ? interval : Math.max(1L, Math.round(interval * stretch));
    }

    /**
     * True when nobody at all is connected. Deliberately server-wide rather than per-dimension: "the
     * server is empty" is what a player means, and a player standing in the Nether is still a reason
     * for the overworld not to be frozen mid-journey.
     *
     * <p>Note that this only gates the offscreen <em>traveller</em> simulation. Camps, virtual battles
     * and Stables are all anchored to elapsed world time on purpose (so a gap - a shutdown, a long
     * unloaded spell - resolves proportionally instead of firing as a burst), and pausing their driver
     * would either change nothing or hand them a huge catch-up the moment someone logs in.
     */
    private static boolean isServerEmpty() {
        net.minecraft.server.MinecraftServer srv =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        return srv != null && srv.getPlayerList().getCurrentPlayerCount() == 0;
    }

    private long lastGoalRefresh = -1;

    // -------------------------------------------------------------------------
    // Movement simulation
    // -------------------------------------------------------------------------

    // Blend factor for smoothing a virtual entity's facing toward its movement direction each sim
    // step. Purely cosmetic (only visible via a materialized entity's initial facing), but without
    // it a leader's rotYaw stays frozen at whatever it was at virtualization until the next
    // waypoint snaps it - a worker-formation leader's own jittery heading would otherwise feed
    // straight into VirtualFormationSimulator's follower offsets.
    private static final float YAW_SMOOTHING_BLEND = 0.35F;

    private void advanceVirtual(long now) {
        double simStep = currentSimStep();
        for (VirtualTravellerRecord r : records.values()) {
            if (r.battleId != null) continue; // battle sim owns movement
            boolean mountedPace = r.mounted && (r.mountMode == null || "MOUNTED".equals(r.mountMode));

            // Route-driven records glide along their itinerary: the carrot one step ahead of the
            // cursor is exactly where this step lands, so the unit stays on the polyline.
            Itinerary it = followsItinerary(r) ? r.itinerary.get() : null;
            if (it != null) {
                ItineraryCursor c = r.itinerary.cursor;
                c.advance(it, r.posX, r.posZ);
                double stepCap = c.kind(it) == Itinerary.BOAT
                        ? simStep * TravellersConfig.ROUTING.boatVirtualSpeedMultiplier
                        : mountedPace ? simStep * UnitSpeed.mountedFactor() : simStep;
                c.computeCarrot(it, stepCap);
                moveToward(r, c.carrotX, c.carrotZ, stepCap, now);
                continue;
            }

            if (r.goal == null) continue;
            double dx = r.goal.getX() + 0.5 - r.posX;
            double dz = r.goal.getZ() + 0.5 - r.posZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.5) continue;
            // Escort followers left far behind their slot (typically scattered by a virtual battle)
            // close the gap faster than the normal walk speed instead of trudging back at the same
            // pace they'd use for routine formation adjustments.
            double stepCap;
            boolean followBoosted = false;
            if (r.escortLeaderUUID != null && dist > TravellersConfig.WORKER_FORMATION.virtualRegroupTriggerDistance) {
                stepCap = simStep * UnitSpeed.followCatchUpFactor(mountedPace);
                followBoosted = true;
            } else if (r.groupLeaderUUID != null && dist > TravellersConfig.GROUP_FOLLOW.catchUpTriggerDistance) {
                // Traveller/raider group follower falling behind: mirrors the loaded follow AI's
                // catch-up boost so "falls behind -> speeds up" holds whether loaded or virtual.
                stepCap = simStep * UnitSpeed.followCatchUpFactor(mountedPace);
                followBoosted = true;
            } else {
                stepCap = simStep;
            }
            // Mounted records cover ground faster offscreen too, mirroring the horse's real speed
            // bonus. A rider that went virtual leading or hitching its horse is walking.
            if (mountedPace && !followBoosted) stepCap *= UnitSpeed.mountedFactor();
            // A follower whose leader is crossing by boat keeps up with the boat.
            if (leaderAfloat(r)) stepCap = Math.max(stepCap, simStep * TravellersConfig.ROUTING.boatVirtualSpeedMultiplier);
            moveToward(r, r.goal.getX() + 0.5, r.goal.getZ() + 0.5, stepCap, now);
        }
    }

    /** The water surface at the record's own position when it is on a planned boat segment, else null. */
    @Nullable
    private static MaterializePositionResolver.ResolvedPosition afloatSpawnPos(WorldServer world, VirtualTravellerRecord r) {
        if (!TravellersConfig.ROUTING.enablePlannedRoutes || !followsItinerary(r)) return null;
        Itinerary it = r.itinerary.get();
        if (it == null || r.itinerary.cursor.kind(it) != Itinerary.BOAT) return null;
        int bx = MathHelper.floor(r.posX), bz = MathHelper.floor(r.posZ);
        if (!world.getChunkProvider().chunkExists(bx >> 4, bz >> 4)) return null;
        if (VirtualTravellerManager.isFrozenByVanillaAt(world, bx + 0.5, bz + 0.5)) return null;
        double y = MaterializePositionResolver.waterSurfaceY(world, bx, bz);
        return Double.isNaN(y) ? null : new MaterializePositionResolver.ResolvedPosition(bx + 0.5, y, bz + 0.5, "afloat");
    }

    private boolean leaderAfloat(VirtualTravellerRecord r) {
        UUID leaderId = r.groupLeaderUUID != null ? r.groupLeaderUUID : r.escortLeaderUUID;
        if (leaderId == null) return false;
        VirtualTravellerRecord leader = records.get(leaderId);
        if (leader == null || !followsItinerary(leader)) return false;
        Itinerary it = leader.itinerary.get();
        return it != null && leader.itinerary.cursor.kind(it) == Itinerary.BOAT;
    }

    private static void moveToward(VirtualTravellerRecord r, double tx, double tz, double stepCap, long now) {
        double dx = tx - r.posX;
        double dz = tz - r.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5) return;
        double step = Math.min(stepCap, dist);
        r.posX += (dx / dist) * step;
        r.posZ += (dz / dist) * step;
        r.lastVirtualMoveTick = now;
        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        r.rotYaw = blendYawTowards(r.rotYaw, targetYaw, YAW_SMOOTHING_BLEND);
        logRouteTrail(r, now);
    }

    /** Destination of the leg a route-driven record is walking: its personal travel goal or current trip stop. */
    @Nullable
    private static BlockPos legDestination(VirtualTravellerRecord r) {
        if (r.personalTravelGoal != null) return r.personalTravelGoal;
        BlockPos mission = missionDest(r);
        return mission != null ? mission : r.currentTripStop();
    }

    /**
     * Where a hired worker's fixed-goal mission leads (sale/transport and recruiting stops by
     * jobGoal, a Go order or Get Equipment by its fixed r.goal), else null. The return to the owner is
     * excluded: the owner moves. Mirrors EntityTravellerWorker.isOnFixedGoalMission.
     */
    @Nullable
    private static BlockPos missionDest(VirtualTravellerRecord r) {
        if (!"WORKER".equals(r.entityClass) || !r.isHiredFollower) return null;
        if ("TO_VILLAGE".equals(r.workerMode) || "RECRUITING".equals(r.workerMode)) return r.jobGoal;
        if (r.goOrderActive && ("IDLE".equals(r.workerMode) || "GET_EQUIPMENT".equals(r.workerMode))) return r.goal;
        return null;
    }

    /** True when this record's movement comes from its itinerary this step. */
    private static boolean followsItinerary(VirtualTravellerRecord r) {
        return r.campSiteId == null && r.visit == null && routeDrivenMovement(r) && r.itinerary.isFor(legDestination(r));
    }

    /**
     * Diagnostic (Plan A Phase 3b, {@code ROUTING.debug}): dumps a routed solo/leader traveller's
     * live position against its route so we can see whether {@code posX/posZ} actually walks the bent
     * polyline or straight-lines it. {@code chordDev} is the perpendicular distance of the current
     * position from the straight line between the route's first and last vertex - if it climbs to
     * tens of blocks mid-trip the sim is following the bend; if it stays near 0 the polyline is not
     * driving movement. Throttled per record; named records only, to keep the paste readable.
     */
    private static void logRouteTrail(VirtualTravellerRecord r, long now) {
        if (!TravellersConfig.ROUTING.debug) return;
        if (now - r.lastTrailLogTick < 100L) return;
        if (r.name == null || r.name.isEmpty()) return;
        Itinerary it = r.itinerary.get();
        if (it == null || it.size() < 3 || !routeDrivenMovement(r)) return;
        r.lastTrailLogTick = now;
        double cdx = it.x(it.size() - 1) - it.x(0);
        double cdz = it.z(it.size() - 1) - it.z(0);
        double clen = Math.sqrt(cdx * cdx + cdz * cdz);
        double chordDev = clen < 1.0 ? 0.0
                : Math.abs((r.posX - (it.x(0) + 0.5)) * cdz - (r.posZ - (it.z(0) + 0.5)) * cdx) / clen;
        rlog("trail " + r.entityClass + " " + r.name + " pos=" + (int) r.posX + "," + (int) r.posZ
                + " seg=" + r.itinerary.cursor.segment() + "/" + (it.size() - 1)
                + " along=" + (int) r.itinerary.cursor.along() + "/" + (int) it.totalLength()
                + " off=" + (int) r.itinerary.cursor.offRouteDistance()
                + " chordDev=" + (int) chordDev + " q=" + it.quality);
    }

    /** Blends {@code from} toward {@code to} by {@code factor}, taking the shortest angular path. */
    private static float blendYawTowards(float from, float to, float factor) {
        float delta = net.minecraft.util.math.MathHelper.wrapDegrees(to - from);
        return from + delta * factor;
    }

    /**
     * True for records whose movement is driven by their itinerary (see {@link #advanceVirtual}):
     * a solo/leader traveller or raider, a
     * non-recruited idle worker roaming village-to-village (D-2), or any worker walking a Go Home /
     * Go To Duty personal-travel leg. A group/escort follower tracks its leader, not a route.
     */
    private static boolean routeDrivenMovement(VirtualTravellerRecord r) {
        if ("TRAVELLER".equals(r.entityClass) || "RAIDER".equals(r.entityClass)) {
            return r.groupLeaderUUID == null;
        }
        if ("WORKER".equals(r.entityClass)) {
            if (r.personalTravelGoal != null) return true;
            if (missionDest(r) != null) return !r.isCatchupMode;
            return !r.isHiredFollower && r.escortLeaderUUID == null && !r.isCatchupMode
                    && "IDLE".equals(r.workerMode);
        }
        return false;
    }

    /** [Travellers/Route] console line, gated on {@code ROUTING.debug}. */
    private static void rlog(String msg) {
        if (TravellersConfig.ROUTING.debug) {
            net.minecraftforge.fml.common.FMLLog.log.info("[Travellers/Route] " + msg);
        }
    }

    private static String fmt(BlockPos p) {
        return p.getX() + "," + p.getZ();
    }

    /**
     * Hands every finished coarse polyline to {@link ItineraryPlanner}, which splices it into its
     * owner's itinerary (record or loaded entity). The one place in the mod that calls
     * {@link TravelRoutePlanner#drainResults(int)} - it empties the shared result queue outright.
     */
    private void drainRoutePlans(WorldServer world) {
        if (!ItineraryPlanner.hasPending() && TravelRoutePlanner.pendingResultCount() == 0) return;
        List<TravelRoutePlanner.RouteResult> results =
                TravelRoutePlanner.drainResults(world.provider.getDimension());
        if (results.isEmpty()) return;
        for (TravelRoutePlanner.RouteResult res : results) {
            if (ItineraryPlanner.applyResult(world, res, records::get)) {
                markDirty();
                if (TravellersConfig.ROUTING.debug) logTerrainComparison(world, res.plan.polyline);
            }
        }
    }

    /**
     * Diagnostic (Plan A Phase 3b, {@code ROUTING.debug}): compares the terrain the spliced polyline
     * actually crosses against the terrain the straight chord between its endpoints would cross,
     * sampling the live grid every 16 blocks. If {@code poly[...]} is not meaningfully gentler than
     * {@code chord[...]} the route is geometrically bent but not avoiding anything - the cost model /
     * weights are the problem, not the movement code. {@code rise} = summed |Δ mean-Y| (total climb),
     * {@code maxAmp} = worst roughness cell, {@code wet} = share of samples over water (x1000),
     * {@code noData} = samples with no resident terrain cell.
     */
    private static void logTerrainComparison(WorldServer world, List<BlockPos> poly) {
        if (poly == null || poly.size() < 2) return;
        TerrainWorldState st = TerrainWorldState.get(world);
        TerrainProfileGrid grid = st != null ? st.getGrid() : null;
        if (grid == null) return;
        BlockPos a = poly.get(0);
        BlockPos b = poly.get(poly.size() - 1);
        rlog("  terrain poly" + terrainStats(grid, poly)
                + " chord" + terrainStats(grid, java.util.Arrays.asList(a, b)));
    }

    /** {@code [rise=NN maxAmp=NN wet=NNN noData=NN n=NN]} sampled every 16 blocks along the vertex list. */
    private static String terrainStats(TerrainProfileGrid grid, List<BlockPos> verts) {
        int n = 0;
        int wet = 0;
        int noData = 0;
        int maxAmp = 0;
        double rise = 0.0;
        int prevH = Short.MIN_VALUE;
        for (int i = 1; i < verts.size(); i++) {
            BlockPos p = verts.get(i - 1);
            BlockPos q = verts.get(i);
            double dx = q.getX() - p.getX();
            double dz = q.getZ() - p.getZ();
            int steps = Math.max(1, (int) Math.round(Math.sqrt(dx * dx + dz * dz) / 16.0));
            for (int s = (i == 1 ? 0 : 1); s <= steps; s++) {
                int sx = p.getX() + (int) Math.round(dx * s / steps);
                int sz = p.getZ() + (int) Math.round(dz * s / steps);
                n++;
                if (!grid.isResidentForWorld(sx, sz)) {
                    noData++;
                    continue;
                }
                int amp = grid.getAmplitude(sx, sz);
                if (amp > maxAmp) maxAmp = amp;
                if (grid.getWater(sx, sz) > 40) wet++;
                short h = grid.getHeight(sx, sz);
                if (h != Short.MIN_VALUE && prevH != Short.MIN_VALUE) rise += Math.abs(h - prevH);
                if (h != Short.MIN_VALUE) prevH = h;
            }
        }
        int wetPerMille = n > 0 ? (wet * 1000 / n) : 0;
        return "[rise=" + (int) rise + " maxAmp=" + maxAmp + " wet=" + wetPerMille
                + " noData=" + noData + " n=" + n + "]";
    }

    /** Max perpendicular distance from any polyline vertex to the straight chord between its ends. */
    private static double maxChordDev(List<BlockPos> poly) {
        if (poly == null || poly.size() < 3) return 0.0;
        BlockPos a = poly.get(0), b = poly.get(poly.size() - 1);
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double len2 = dx * dx + dz * dz;
        if (len2 < 1.0) return 0.0;
        double max = 0.0;
        for (BlockPos p : poly) {
            double cross = Math.abs((p.getX() - a.getX()) * dz - (p.getZ() - a.getZ()) * dx);
            max = Math.max(max, cross / Math.sqrt(len2));
        }
        return max;
    }

    private static String polyStr(List<BlockPos> poly) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < poly.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(poly.get(i).getX()).append(',').append(poly.get(i).getZ());
        }
        return sb.append(']').toString();
    }

    private void refreshAllGoals(WorldServer world, long now) {
        for (VirtualTravellerRecord r : records.values()) {
            if (r.dimension != world.provider.getDimension()) continue;
            simulateHealing(r, now);
            if (r.battleId != null) continue; // travel/camp goals suspended while fighting
            refreshGoal(world, r, now);
        }
    }

    /**
     * Offscreen counterpart to {@link com.jubitus.travellers.ambient.EntityAIAutoEat}:
     * gradually heals a virtual record using the same threshold/lockout knobs as the physical AI, so a
     * unit that fought (and survived) a virtual battle recovers HP while offscreen instead
     * of staying wounded until a player happens to materialize it. Called every
     * GOAL_REFRESH_INTERVAL (20 ticks), matching this method's own cadence.
     */
    private void simulateHealing(VirtualTravellerRecord r, long now) {
        if (!TravellersConfig.BATTLE_EVENTS.virtualCombatHealingEnabled) return;
        if (r.battleId != null) return; // still fighting: physical AI blocks eating during combat too
        if (r.entityNbt == null) return;

        int lastCombatTick = r.entityNbt.getInteger("LastCombatTick");
        if (now - lastCombatTick < TravellersConfig.BATTLE_EVENTS.postCombatHealDelayTicks) return;

        float max = r.getCombatProfile().maxHealth;
        float hp = r.getHealthResolved();
        if (hp >= max - 0.001F) {
            r.recoveringToFull = false;
            return;
        }
        if (!r.recoveringToFull && hp > max * TravellersConfig.COMBAT.eatHealthThreshold) return;

        r.recoveringToFull = true;
        float healed = Math.min(max, hp + EntityAIAutoEat.AVG_HEAL_PER_TICK * GOAL_REFRESH_INTERVAL);
        r.health = healed;
        r.entityNbt.setFloat("Health", healed);
        if (healed >= max - 0.001F) r.recoveringToFull = false;
    }

    /** Ticks since a record last moved offscreen, treating "never moved" as infinitely long ago. */
    private static long idleTicksSince(long lastMoveTick, long now) {
        return lastMoveTick == Long.MIN_VALUE ? Long.MAX_VALUE : now - lastMoveTick;
    }

    /**
     * Freezes an escort follower in place while its leader is standing still, instead of walking it
     * to a freshly computed formation slot.
     * <p>
     * Offscreen slots come from {@link VirtualFormationSimulator}, which is surface-only by design
     * (it may not scan blocks), while the loaded {@code FollowFormationCoordinator} compacts the
     * same formation to fit indoors. Left to itself the offscreen sim therefore drags a group that
     * was parked inside a building out through its walls toward the wide open-field shape - and the
     * materializer then has to lift those wall-embedded records up, which is what put them on the
     * roof. A leader that is not moving has nothing to re-form around, so the group simply holds.
     * <p>
     * Followers genuinely out of position (scattered by a virtual battle, leader teleported) are
     * past {@code anchoredHoldRadius} and still regroup normally.
     */
    private static boolean holdAnchoredFormation(VirtualTravellerRecord r,
                                                 double leaderX, double leaderZ,
                                                 long leaderIdleTicks) {
        if (leaderIdleTicks < TravellersConfig.WORKER_FORMATION.anchoredHoldDelayTicks) return false;
        double dx = leaderX - r.posX;
        double dz = leaderZ - r.posZ;
        double hold = TravellersConfig.WORKER_FORMATION.anchoredHoldRadius;
        if (dx * dx + dz * dz > hold * hold) return false;
        // Overwrite rather than leave untouched: the goal seeded at virtualization points at the
        // owner for any hired follower, and holding that would walk the whole group to the player.
        r.goal = new BlockPos(MathHelper.floor(r.posX), MathHelper.floor(r.posY), MathHelper.floor(r.posZ));
        return true;
    }

    /**
     * Returns true if the record is camping (halted at its slot) this tick. Also self-heals: if the
     * occupant is gone (departed/evicted), clears the cache and returns false so travel resumes.
     */
    private boolean handleCamp(WorldServer world, VirtualTravellerRecord r) {
        CampsiteManager cm = CampsiteManager.get(world);
        if (r.campSiteId != null) {
            CampsiteRecord camp = cm.getById(r.campSiteId);
            CampOccupant occ = camp != null ? camp.occupants.get(r.campSlotIndex) : null;
            if (occ == null || !occ.travellerUUID.equals(r.uuid)) {
                r.campSiteId = null;
                r.campSlotIndex = -1;
                r.campCooldownUntil = world.getTotalWorldTime() + 1200L; // ~1 min before camping again
                return false; // resume normal routing
            }
            BlockPos sp = cm.slotStandPos(r.campSiteId, r.campSlotIndex);
            if (sp != null) {
                // It walks to its slot (advanceVirtual); only once there does it take the slot's height.
                r.goal = sp;
                if (isAtCampSlot(r, sp)) r.posY = sp.getY();
            }
            return true;
        }
        return tryCampClaim(world, r, cm);
    }

    /** Attempts to claim a campsite for a passing traveller/raider. Groups join their leader's camp. */
    private boolean tryCampClaim(WorldServer world, VirtualTravellerRecord r, CampsiteManager cm) {
        if (!TravellersConfig.CAMP_EVENTS.enabled) return false;
        if (world.getTotalWorldTime() < r.campCooldownUntil) return false; // just left a camp

        String culture = r.entityNbt != null ? r.entityNbt.getString("CultureId") : "";

        CampsiteRecord camp;
        if (r.groupLeaderUUID != null) {
            // Follower: only camp if the leader is already camping, and join that exact site.
            VirtualTravellerRecord leader = records.get(r.groupLeaderUUID);
            if (leader == null || leader.campSiteId == null) return false;
            camp = cm.getById(leader.campSiteId);
            if (camp == null || !camp.canClaim(r.entityClass, culture)) return false;
        } else {
            camp = cm.findClaimable(r.dimension, r.posX, r.posZ, r.entityClass, culture,
                    TravellersConfig.CAMP_EVENTS.campSeekRadius);
            if (camp == null) return false;
            // Night: always stop. Day: roll the configured chance. Followers skip this (they follow the leader).
            if (!CampsiteManager.isNight(world)
                    && world.rand.nextDouble() >= TravellersConfig.CAMP_EVENTS.daytimeStopChance) {
                return false;
            }
        }
        long now = world.getTotalWorldTime();
        // Followers use the leader's departTick so the whole group leaves together.
        long depart = CampsiteManager.computeDepartTick(world, now);
        if (r.groupLeaderUUID != null) {
            for (com.jubitus.travellers.camp.CampOccupant o : camp.occupants.values()) {
                if (o.travellerUUID.equals(r.groupLeaderUUID)) { depart = o.departTick; break; }
            }
        }
        int slot = cm.claim(camp, r.uuid, r.entityClass, culture, now, depart);
        if (slot < 0) return false;

        r.campSiteId = camp.id;
        r.campSlotIndex = slot;
        BlockPos sp = cm.slotStandPos(camp.id, slot);
        if (sp != null) r.goal = sp; // walked to by advanceVirtual, never placed there
        return true;
    }

    private static boolean isAtCampSlot(VirtualTravellerRecord r, BlockPos slot) {
        double dx = slot.getX() + 0.5 - r.posX;
        double dz = slot.getZ() + 0.5 - r.posZ;
        return dx * dx + dz * dz <= CAMP_SLOT_REACHED_BLOCKS * CAMP_SLOT_REACHED_BLOCKS;
    }

    /**
     * Recomputes {@code r.goal} immediately instead of waiting for the next periodic
     * {@link #refreshAllGoals} pass (up to {@link #GOAL_REFRESH_INTERVAL} ticks away). Used right
     * after a virtual battle ends so a scattered escort follower/leader's formation goal is correct
     * the instant combat resolves, rather than materializing at (or continuing to walk from) a stale
     * pre-battle or mid-battle position.
     */
    public void forceRefreshGoal(WorldServer world, VirtualTravellerRecord r, long now) {
        if (r.dimension != world.provider.getDimension()) return;
        refreshGoal(world, r, now);
    }

    private void refreshGoal(WorldServer world, VirtualTravellerRecord r, long now) {
        // Non-worker travellers/raiders: advance through route, replan at end
        if (!"WORKER".equals(r.entityClass)) {
            // A raider on a raid never camps; its single-stop trip is re-aimed by the raid scheduler.
            boolean raiding = "RAIDER".equals(r.entityClass)
                    && com.jubitus.travellers.raid.RaidWorldData.isRaider(world, r.uuid);
            // Camp lifecycle takes priority: halt at the slot while camping, or claim a passing campsite.
            if (!raiding && r.visit == null && handleCamp(world, r)) return;
            // Group follower: track leader's position (virtual or, once it has materialized, loaded),
            // offset by the same deterministic ring the materializer uses, so the group marches as a
            // loose pack (not a stack) regardless of which side of the group has materialized.
            // Deliberately RING-shaped here regardless of r.groupFormationStyle - fully offscreen
            // simulation has no rendering to keep coherent, only positions close enough together that
            // nothing looks stacked; the real style (GroupFormationTracker, loaded-side only) takes
            // over the moment the group is actually visible. A group whose rolled style isn't RING
            // can briefly reshuffle into its real shape right after materializing near a player -
            // a known, accepted simplification, not a bug.
            if (r.groupLeaderUUID != null) {
                if (r.visit != null) {
                    visits.tick(world, r, now);
                    if (r.visit != null) return;
                } else if (visits.tryStartFollower(world, r, now)) {
                    return;
                }
                VirtualTravellerRecord leaderRec = records.get(r.groupLeaderUUID);
                int[] off = MaterializePositionResolver.followerSpreadOffset(r.uuid);
                // BlockPos's double constructor floors; an (int) cast here truncated toward zero and
                // shifted every goal one block east/north of where it belongs west/north of the origin.
                if (leaderRec != null) {
                    r.goal = new BlockPos(leaderRec.posX + off[0], leaderRec.posY,
                            leaderRec.posZ + off[1]);
                    return;
                }
                Entity liveLeader = world.getEntityFromUuid(r.groupLeaderUUID);
                if (liveLeader != null && !liveLeader.isDead) {
                    r.goal = new BlockPos(liveLeader.posX + off[0], liveLeader.posY,
                            liveLeader.posZ + off[1]);
                    return;
                }
                // Leader gone without a death hook (not virtual, not loaded): elect a successor. A follower
                // tracks the new leader from the next refresh; the successor travels on below.
                r.groupLeaderUUID = com.jubitus.travellers.follow.GroupSuccession.onLeaderMissing(world,
                        r.groupLeaderUUID, r.uuid);
                if (r.groupLeaderUUID != null) return;
            }
            // Leader or solo: movement along the itinerary happens in advanceVirtual; here we keep
            // the itinerary current and move on to the next trip stop at the end of each leg.
            tickRoamRoute(world, r, now);
            return;
        }

        // Non-hired, idle workers may camp exactly like travellers (join/found a civilian camp).
        // Hired or actively-working workers (mid-mission/escort) never stop to camp.
        if (TravellersConfig.CAMP_EVENTS.workersCanCamp && !r.isHiredFollower
                && "IDLE".equals(r.workerMode) && r.visit == null && handleCamp(world, r)) return;

        // Goal precedence below mirrors the loaded getVirtualGoal() exactly - catchup, active mission,
        // go order, THEN escort slot, THEN follow-owner - so a worker resolves the same goal whether it
        // is loaded or virtual. In particular a follower that also carries a mission or a Go order obeys
        // that (as it does while loaded) instead of the escort branch silently overriding it, which used
        // to differ between the two sides.

        // Catchup: always track live target
        if (r.isCatchupMode && r.catchupTargetUUID != null) {
            Entity target = world.getEntityFromUuid(r.catchupTargetUUID);
            if (target != null && !target.isDead) {
                r.goal = target.getPosition();
                return;
            }
        }
        // Hired worker returning from a job: track owner's current position dynamically
        // (covers both sale missions and recruit return — owner may have moved since mission start)
        if ("RETURNING".equals(r.workerMode) && r.isHiredFollower && r.followOwnerUUID != null) {
            EntityPlayer owner = world.getPlayerEntityByUUID(r.followOwnerUUID);
            missions.tickReturnToOwner(world, r, owner != null && !owner.isDead ? owner : null);
            return;
        }
        // Personal self-travel (Go Home / Go To Duty): a fixed self-chosen destination that must keep
        // progressing offscreen and survive virtualize/materialize regardless of workerMode. Handled
        // ABOVE the mission/go-order early-returns below - an ON_DUTY record walking to a far Stable
        // would otherwise never get its goal refreshed (the old duty branch sat after a
        // "workerMode != IDLE -> return" and was dead code for exactly this in-transit case).
        if (r.personalTravelGoal != null) {
            double pdx = r.personalTravelGoal.getX() + 0.5 - r.posX;
            double pdz = r.personalTravelGoal.getZ() + 0.5 - r.posZ;
            if (pdx * pdx + pdz * pdz <= VirtualWorkerMissions.JOB_ARRIVAL_FORCE_DIST * VirtualWorkerMissions.JOB_ARRIVAL_FORCE_DIST) {
                // Arrived offscreen (straight-line check to the commanded point - unchanged
                // semantics). Clear the in-transit markers on both the record and its frozen NBT so a
                // later materialize doesn't re-arm travel from a stale GoingHome/GoingToDuty flag
                // (harmless if it does - the loaded arrival check clears it next tick - but this keeps
                // the two sides honest), and drop the planned leg.
                r.goal = r.personalTravelGoal;
                r.personalTravelGoal = null;
                r.goingHome = false;
                r.goingToDuty = false;
                if (r.entityNbt != null) {
                    r.entityNbt.setBoolean("GoingHome", false);
                    r.entityNbt.setBoolean("GoingToDuty", false);
                }
                r.itinerary.clear();
                return;
            }
            // Not arrived: walk a terrain-planned polyline toward the commanded point (D-2), same
            // TravelRoutePlanner as a roam route but with no end-of-route re-pick.
            tickPersonalTravelRoute(world, r, now);
            return;
        }

        // Arrived and on duty: hold at the zone itself (the offscreen equivalent of EntityAIWorkerDuty
        // having nothing left to do but be there), and run the duty kind's offscreen behaviour if it
        // has one. Solo-only assignment means this never competes with the escort/follow branches.
        if (r.dutyZoneCenter != null && r.dutyKind != null && "ON_DUTY".equals(r.workerMode)) {
            r.goal = r.dutyZoneCenter;
            double ddx = r.dutyZoneCenter.getX() + 0.5 - r.posX;
            double ddz = r.dutyZoneCenter.getZ() + 0.5 - r.posZ;
            if (ddx * ddx + ddz * ddz <= VirtualWorkerMissions.JOB_ARRIVAL_FORCE_DIST * VirtualWorkerMissions.JOB_ARRIVAL_FORCE_DIST) {
                com.jubitus.travellers.zone.duty.VirtualDutyBehavior behavior =
                        com.jubitus.travellers.zone.duty.VirtualDutyDefs.get(r.dutyKind);
                if (behavior != null) behavior.tickVirtual(world, r, now);
            }
            return;
        }

        // Active mission or go order: its goal is fixed (the arrival checks read it), only the
        // itinerary toward it is kept current.
        BlockPos mission = missionDest(r);
        if (mission != null) {
            tickMissionRoute(world, r, mission, now);
            return;
        }
        if (!"IDLE".equals(r.workerMode)) return;
        if (r.goOrderActive) return;

        // Escort follower: ALWAYS hold a live formation slot around the leader (virtual or loaded),
        // whether the leader is moving or anchored/idle. Recomputing every refresh - not just when
        // r.goal is null - is what keeps the follower pinned to its slot even when its goal was seeded
        // elsewhere at virtualization: getVirtualGoal() falls back to the OWNER's position for any
        // hired follower and does not know about the escort leader, so an idle leader's followers would
        // otherwise hold that stale owner-directed goal and walk all the way to the player (the exact
        // "my group's followers all travelled to me" bug). A stationary leader yields a stationary
        // slot, so this is never "chasing a target that isn't moving".
        if (r.escortLeaderUUID != null && r.followOrderActive) {
            VirtualTravellerRecord leaderRec = records.get(r.escortLeaderUUID);
            if (leaderRec != null) {
                if (holdAnchoredFormation(r, leaderRec.posX, leaderRec.posZ,
                        idleTicksSince(leaderRec.lastVirtualMoveTick, now))) {
                    return;
                }
                boolean mounted = r.mountNbt != null || leaderRec.mountNbt != null;
                VirtualFormationSimulator.applyFormationGoal(world, records, r, r.escortLeaderUUID,
                        leaderRec.posX, leaderRec.posY, leaderRec.posZ, leaderRec.rotYaw, true, mounted, now);
                return;
            }
            Entity leader = world.getEntityFromUuid(r.escortLeaderUUID);
            if (leader != null && !leader.isDead) {
                double moved = leader.posX - leader.prevPosX;
                double movedZ = leader.posZ - leader.prevPosZ;
                long idleTicks = (moved * moved + movedZ * movedZ) < 1.0E-4 ? Long.MAX_VALUE : 0L;
                if (holdAnchoredFormation(r, leader.posX, leader.posZ, idleTicks)) return;
                boolean mounted = r.mountNbt != null || PathBody.isMounted(leader);
                VirtualFormationSimulator.applyFormationGoal(world, records, r, r.escortLeaderUUID,
                        leader.posX, leader.posY, leader.posZ, leader.rotationYaw, true, mounted, now);
                return;
            }
            // Leader is neither a virtual record nor loaded in this dimension (gone without a death hook):
            // elect a successor. A follower tracks it from the next refresh; the successor (or a follower
            // left without one) falls through to owner-follow below.
            UUID goneLeader = r.escortLeaderUUID;
            r.escortLeaderUUID = world.getMinecraftServer() == null ? null
                    : com.jubitus.travellers.follow.group.WorkerGroupService.onLeaderMissing(
                            world.getMinecraftServer(), r.followOwnerUUID, goneLeader, r.uuid);
            if (r.escortLeaderUUID != null) return;
        }

        // Hired follower with no active mission and follow ON: hold a live formation slot
        // relative to the owner, same shared geometry as the escort-leader case above.
        if (r.isHiredFollower && r.followOwnerUUID != null && r.followOrderActive) {
            EntityPlayer owner = world.getPlayerEntityByUUID(r.followOwnerUUID);
            if (owner != null && !owner.isDead) {
                VirtualFormationSimulator.applyFormationGoal(world, records, r, r.followOwnerUUID,
                        owner.posX, owner.posY, owner.posZ, owner.rotationYaw, false, r.mountNbt != null, now);
                return;
            }
        }

        // D-2: a non-recruited idle worker roams village-to-village, terrain-planned exactly like a
        // traveller. Reached only after every hired / escort / mission / go-order / personal-travel /
        // ON_DUTY branch above has had its turn and not returned - i.e. a plain idle worker with
        // nothing else to do. virtualizeWorker captured its loaded roam trip into r.tripStops.
        if (!r.isHiredFollower && r.escortLeaderUUID == null && r.personalTravelGoal == null) {
            tickRoamRoute(world, r, now);
            return;
        }
        // Follow OFF / no owner / hired-but-anchored: goal stays unchanged so the worker stays put.
    }

    void tryVirtualDonate(WorldServer world, VirtualTravellerRecord r) {
        BlockPos center = r.currentTripStop() != null ? r.currentTripStop() : r.goal;
        if (center == null) return;
        // A gift is skipped rather than delayed when the chest budget is spent this tick.
        if (!com.jubitus.travellers.ground.chunk.LoadedChunks.tryAcquireJob(world)) return;
        com.jubitus.travellers.village.MillGoods.donateToConstruction(world, center);
    }

    /**
     * Picks a fresh roam destination for a virtual traveller/raider/roaming worker via the single
     * {@link com.jubitus.travellers.travel.destination.TravelDestinationChooser}
     * authority (Plan B). Budget-gated: a denied {@link ItineraryPlanner#tryAcquire} returns {@code null} so
     * the caller leaves the record dwelling and retries on the next goal refresh.
     */
    @Nullable
    private BlockPos pickRoamDestination(WorldServer world, VirtualTravellerRecord r) {
        long now = world.getTotalWorldTime();
        if (!ItineraryPlanner.tryAcquire(now)) return null;
        return chooseRoamDestination(world, r, roamOriginOf(r));
    }

    // First leg of a freshly spawned record, chosen from its home settlement.
    @Nullable
    public BlockPos pickInitialDestination(WorldServer world, VirtualTravellerRecord r) {
        r.noteChosenDestination(r.homeVillage, TravellersConfig.DESTINATIONS.recentDestMemory);
        return chooseRoamDestination(world, r, r.homeVillage);
    }

    @Nullable
    private BlockPos chooseRoamDestination(WorldServer world, VirtualTravellerRecord r, BlockPos origin) {
        com.jubitus.travellers.travel.destination.TravelDestinationChooser.DestinationContext ctx =
                new com.jubitus.travellers.travel.destination.TravelDestinationChooser.DestinationContext();
        ctx.originCentre = origin;
        ctx.homeVillage = r.homeVillage;
        ctx.cultureId = r.cultureId;
        ctx.kind = "RAIDER".equals(r.entityClass)
                ? com.jubitus.travellers.travel.destination.TravelDestinationChooser.EntityKind.RAIDER
                : "WORKER".equals(r.entityClass)
                ? com.jubitus.travellers.travel.destination.TravelDestinationChooser.EntityKind.WORKER
                : com.jubitus.travellers.travel.destination.TravelDestinationChooser.EntityKind.TRAVELLER;
        ctx.wanderlust = r.wanderlust;
        ctx.exploreChance = r.exploreChance;
        ctx.recentDestinations = r.recentDestinations;
        ctx.rng = world.rand;
        ctx.applyRoadRestriction = false; // the itinerary already consumes MPB road legs
        ctx.badVillages = null;
        ctx.debugId = r.uuid;

        com.jubitus.travellers.travel.destination.TravelDestinationChooser.ChosenDestination chosen =
                com.jubitus.travellers.travel.destination.TravelDestinationChooser.choose(world, ctx);
        if (chosen == null) return null;
        r.noteChosenDestination(chosen.dest, TravellersConfig.DESTINATIONS.recentDestMemory);
        return chosen.dest;
    }

    private static BlockPos roamOriginOf(VirtualTravellerRecord r) {
        if (r.finalTripStop() != null) return r.finalTripStop();
        if (r.goal != null) return r.goal;
        return new BlockPos(MathHelper.floor(r.posX), MathHelper.floor(r.posY), MathHelper.floor(r.posZ));
    }

    /** Picks a fresh roam destination and starts a one-stop trip toward it (end-of-trip replan). */
    private void startNewTrip(WorldServer world, VirtualTravellerRecord r, long now) {
        if (com.jubitus.travellers.raid.RaidWorldData.isRaider(world, r.uuid)) return;
        BlockPos dest = pickRoamDestination(world, r);
        if (dest == null) return;
        r.setTrip(java.util.Collections.singletonList(dest), 0);
        r.itinerary.clear();
        r.goal = dest;
        if (ItineraryPlanner.tryAcquire(now)) buildItinerary(world, r, dest, now);
        markDirty();
    }

    /** Leg finished: move on to the next stop of the trip, or pick a new trip after the last one. */
    void advanceTrip(WorldServer world, VirtualTravellerRecord r, long now) {
        if (r.tripIndex >= 0 && r.tripIndex < r.tripStops.size() - 1) {
            r.tripIndex++;
            r.itinerary.clear();
            r.goal = r.currentTripStop();
            markDirty();
            return;
        }
        startNewTrip(world, r, now);
    }

    /**
     * Shared village-to-village roam servo for a solo/leader traveller or raider and for a
     * non-recruited idle worker. Movement along the itinerary happens in {@link #advanceVirtual};
     * here the itinerary is (re)built for the current trip stop, its bridges get planned, and the
     * trip advances once the leg's end is reached.
     */
    private void tickRoamRoute(WorldServer world, VirtualTravellerRecord r, long now) {
        if (r.visit != null) {
            visits.tick(world, r, now);
            return;
        }
        BlockPos stop = r.currentTripStop();
        if (stop == null) {
            startNewTrip(world, r, now);
            return;
        }
        if (!r.itinerary.isFor(stop)) {
            // Straight toward the stop until a build slot is free.
            r.goal = stop;
            if (ItineraryPlanner.tryAcquire(now)) buildItinerary(world, r, stop, now);
            return;
        }
        ItineraryPlanner.tryPlanBacklog(world, r.uuid, r.itinerary, isMountedRecord(r), r.cultureId, now);
        if (visits.tryStart(world, r, stop, now)) return;
        if (legReached(r, stop)) {
            // No stay here (visits off, or no Millénaire data): a worker still leaves its gift.
            if ("WORKER".equals(r.entityClass)) tryVirtualDonate(world, r);
            advanceTrip(world, r, now);
            return;
        }
        updateGoalFromItinerary(r);
    }

    /**
     * Keeps the itinerary toward {@code r.personalTravelGoal} (a hired worker's Go Home / Go To Duty
     * leg) current. Never picks a new destination - refreshGoal's own arrival check ends the leg.
     */
    private void tickPersonalTravelRoute(WorldServer world, VirtualTravellerRecord r, long now) {
        BlockPos goal = r.personalTravelGoal;
        if (!r.itinerary.isFor(goal)) {
            r.goal = goal;
            if (ItineraryPlanner.tryAcquire(now)) buildItinerary(world, r, goal, now);
            return;
        }
        ItineraryPlanner.tryPlanBacklog(world, r.uuid, r.itinerary, isMountedRecord(r), r.cultureId, now);
        updateGoalFromItinerary(r);
    }

    /** Keeps the itinerary toward a fixed mission destination current; never touches r.goal. */
    private void tickMissionRoute(WorldServer world, VirtualTravellerRecord r, BlockPos dest, long now) {
        if (!r.itinerary.isFor(dest)) {
            if (ItineraryPlanner.tryAcquire(now)) buildItinerary(world, r, dest, now, false);
            return;
        }
        ItineraryPlanner.tryPlanBacklog(world, r.uuid, r.itinerary, isMountedRecord(r), r.cultureId, now);
    }

    private void buildItinerary(WorldServer world, VirtualTravellerRecord r, BlockPos dest, long now) {
        buildItinerary(world, r, dest, now, true);
    }

    private void buildItinerary(WorldServer world, VirtualTravellerRecord r, BlockPos dest, long now,
                                boolean goalFollowsCarrot) {
        ItineraryPlanner.build(world, r.uuid, r.itinerary, r.posX, r.posY, r.posZ, dest,
                ItineraryPlanner.mayUseRoads("RAIDER".equals(r.entityClass)), isMountedRecord(r),
                r.cultureId, now, null);
        if (goalFollowsCarrot) updateGoalFromItinerary(r);
        markDirty();
    }

    private static boolean isMountedRecord(VirtualTravellerRecord r) {
        return r.mounted && (r.mountMode == null || "MOUNTED".equals(r.mountMode));
    }

    private static boolean legReached(VirtualTravellerRecord r, BlockPos stop) {
        double simStep = currentSimStep();
        double dx = stop.getX() + 0.5 - r.posX;
        double dz = stop.getZ() + 0.5 - r.posZ;
        if (dx * dx + dz * dz < simStep * simStep) return true;
        Itinerary it = r.itinerary.get();
        return it != null && r.itinerary.cursor.remaining(it) < 1.0;
    }

    /** Points {@code r.goal} (read by materialize/diagnostics) at the itinerary's next carrot. */
    private static void updateGoalFromItinerary(VirtualTravellerRecord r) {
        Itinerary it = r.itinerary.get();
        if (it == null) return;
        ItineraryCursor c = r.itinerary.cursor;
        c.advance(it, r.posX, r.posZ);
        c.computeCarrot(it, TravellersConfig.ROUTING.itineraryCarrotBlocks);
        r.goal = new BlockPos(MathHelper.floor(c.carrotX), MathHelper.floor(r.posY), MathHelper.floor(c.carrotZ));
    }

    // -------------------------------------------------------------------------
    // Player-triggered materialization
    // -------------------------------------------------------------------------

    /**
     * Materialize/virtualize used to be a pure "distance to nearest player" check approximating the
     * view distance. It is now driven by one fact only: is the unit's own ground chunk actually
     * loaded, for any reason at all - a player's view distance, a Millenaire town-hall ticket,
     * another mod's chunk loader, this mod's own transient loads. So a worker sent far away on a Go
     * order appears the instant it walks onto chunks somebody is keeping loaded, exactly as if a
     * player had walked up to it. Both directions share {@link #isFrozenByVanillaAt} as the single
     * source of truth so there is never a case where "should this be real" disagrees depending on
     * which side is asking.
     */
    private void checkPlayerMaterialize(WorldServer world, long now) {
        if (records.isEmpty()) return;
        int dim = world.provider.getDimension();

        int stableTicks = TravellersConfig.MATERIALIZATION.materializeChunkStableTicks;

        List<UUID> toMaterialize = null;

        for (VirtualTravellerRecord r : records.values()) {
            if (r.dimension != dim) continue;

            // Not merely "the chunk is loaded" but "vanilla would actually tick a unit standing here"
            // - the stricter of the two, and the same predicate the virtualize side uses.
            if (isFrozenByVanillaAt(world, r.posX, r.posZ)) {
                materializeWatch.remove(r.uuid);
                continue;
            }
            if (materializeGraceUntil.containsKey(r.uuid)) {
                materializeWatch.remove(r.uuid);
                continue;
            }

            // The debounce is continuous ticking ground, not one chunk: a boat or a rider crosses
            // chunks faster than the debounce and would otherwise never reappear.
            Long since = materializeWatch.get(r.uuid);
            if (since == null) {
                materializeWatch.put(r.uuid, now);
                continue;
            }
            if (now - since >= stableTicks) {
                if (toMaterialize == null) toMaterialize = new ArrayList<>();
                toMaterialize.add(r.uuid);
            }
        }

        if (toMaterialize != null) {
            for (UUID id : toMaterialize) {
                materializeWatch.remove(id);
                VirtualTravellerRecord r = records.get(id);
                if (r == null) continue;
                // A real entity already carries this identity (a stale copy read back from a chunk
                // file, a virtualize that silently refused): the record is the duplicate, and the
                // loaded body wins. Drop the record rather than spawning a second one.
                if (world.getEntityFromUuid(id) != null) {
                    records.remove(id);
                    continue;
                }
                VirtualizationLog.materialize(world, r);
                boolean spawned = materializeEntity(world, r, now);
                com.jubitus.travellers.debug.profile.MovementProfiler.materialized(r.uuid, spawned);
                if (!spawned) {
                    // The spawn was refused (see materializeEntity), and the record was handed back.
                    // Hold it a moment so the next pass isn't an immediate retry of the same failure.
                    materializeGraceUntil.put(id,
                            now + TravellersConfig.MATERIALIZATION.rematerializeGraceTicks);
                }
            }
        }

        // Bound growth: drop watch entries for records that no longer exist (materialized elsewhere,
        // removed, dead) instead of letting them accumulate for the life of the server.
        if (!materializeWatch.isEmpty()) materializeWatch.keySet().retainAll(records.keySet());
    }

    // -------------------------------------------------------------------------
    // Virtualization of real workers
    // -------------------------------------------------------------------------

    /**
     * Decides which real units should go offscreen. Two reasons, both meaning "this unit cannot
     * function as a physical entity here":
     *
     * <ul>
     *   <li><b>Vanilla has stopped ticking it</b> ({@link #isFrozenByVanilla}) - by far the common
     *       case, and the one that has to be caught from the world tick because the unit's own tick is
     *       precisely what is no longer running. See that method for why.</li>
     *   <li><b>It is standing outside loaded terrain altogether</b> - a unit that drifted across the
     *       boundary under its own power, which leaves it in world.loadedEntityList with
     *       addedToChunk=false so no unload event ever fires for it.</li>
     * </ul>
     *
     * <p>The everyday "a player walked away" case is handled neither here nor by any poll, but by
     * {@link ChunkUnloadVirtualizeHandler} at the moment the chunk unloads - a debounced poll cannot
     * win that race, since PlayerChunkMap.removeEntry() queues the unload in the same call that stops
     * the chunk being tracked.
     */
    private void checkVirtualize(WorldServer world, long now) {
        int unneededTicks = TravellersConfig.MATERIALIZATION.virtualizeChunkUnneededTicks;

        // Snapshot entity list to avoid ConcurrentModificationException
        List<EntityTravellerWorker> workers = new ArrayList<>();
        List<EntityTraveller> travellers = new ArrayList<>();
        for (Entity e : world.loadedEntityList) {
            if (e.isDead) continue;
            if (e instanceof EntityTravellerWorker) workers.add((EntityTravellerWorker) e);
            else if (e instanceof EntityTraveller) travellers.add((EntityTraveller) e);
        }

        Set<UUID> stillLoaded = new HashSet<>();

        for (EntityTravellerWorker worker : workers) {
            UUID id = worker.getUniqueID();
            stillLoaded.add(id);
            if (graceUntil.containsKey(id)) { virtualizeWatch.remove(id); continue; }
            boolean frozen = isFrozenByVanilla(world, worker);
            // INVARIANT: the gate is skipped for a frozen unit - it never ticks again, so a blocker like
            // isHandActive() (stopped mid-meal) could never end and it would stand at the border for good.
            if (!frozen && !VirtualizationGate.mayVirtualize(worker)) { virtualizeWatch.remove(id); continue; }
            if (!tickUnneededWatch(id, worker, world, now, unneededTicks, frozen)) continue;
            VirtualizationLog.virtualize(frozen ? "frozen by vanilla" : "chunk not loaded", worker);
            if (frozen) {
                logFrozenVirtualize(worker, worker.handOff.forceResolvingBlockers());
            } else {
                virtualizeWorker(world, worker, now);
            }
        }

        for (EntityTraveller entity : travellers) {
            UUID id = entity.getUniqueID();
            stillLoaded.add(id);
            if (graceUntil.containsKey(id)) { virtualizeWatch.remove(id); continue; }
            boolean frozen = isFrozenByVanilla(world, entity);
            if (!frozen && !VirtualizationGate.mayVirtualize(entity)) { virtualizeWatch.remove(id); continue; }
            if (!tickUnneededWatch(id, entity, world, now, unneededTicks, frozen)) continue;
            VirtualizationLog.virtualize(frozen ? "frozen by vanilla" : "chunk not loaded", entity);
            if (frozen) {
                logFrozenVirtualize(entity, entity.handOff.forceResolvingBlockers());
            } else {
                virtualizeTraveller(world, entity, now);
            }
        }

        if (!virtualizeWatch.isEmpty()) virtualizeWatch.keySet().retainAll(stillLoaded);
    }

    /**
     * Tracks how long a loaded unit has been continuously standing outside loaded terrain. Returns
     * true once the debounce window has elapsed (caller should virtualize immediately after).
     */
    private boolean tickUnneededWatch(UUID id, Entity entity, WorldServer world, long now,
                                      int unneededTicks, boolean frozen) {
        // Derived from the live position, NOT from entity.chunkCoordX/Z: those only update while the
        // entity is actually attached to a chunk, so they go stale at exactly the moment that matters
        // here (an entity that walked past the boundary has addedToChunk=false and keeps reporting the
        // last chunk it was attached to - which is still loaded, so it would never virtualize).
        // The materialize side derives its chunk the same way, so both agree by construction.
        int cx = net.minecraft.util.math.MathHelper.floor(entity.posX) >> 4;
        int cz = net.minecraft.util.math.MathHelper.floor(entity.posZ) >> 4;
        if (!frozen && LoadedChunks.isChunkLoaded(world, cx, cz)) {
            virtualizeWatch.remove(id);
            return false;
        }
        Long since = virtualizeWatch.get(id);
        if (since == null) {
            virtualizeWatch.put(id, now);
            return false;
        }
        if (now - since < unneededTicks) return false;
        virtualizeWatch.remove(id);
        return true;
    }

    /**
     * True if vanilla has stopped ticking this entity because it sits too close to unloaded terrain -
     * an exact mirror of the gate in {@code World.updateEntityWithOptionalForce}, not an approximation
     * of it.
     *
     * <p>This is the single most important reason a unit has to go offscreen, and the least obvious.
     * Vanilla refuses to tick any non-player entity unless the area 32 blocks around it is fully
     * loaded (0 blocks - i.e. just its own chunk - when that chunk holds a ForgeChunkManager ticket).
     * A traveller that walks up to the edge of loaded terrain therefore stops receiving
     * {@code onUpdate} entirely, well before it reaches the visible boundary: no AI, no travel task,
     * no self-check of any kind. It becomes a statue, and every unit that arrives there joins it -
     * which is what "travellers gather at the border and never leave" actually is.
     *
     * <p>Because of that, nothing driven from the entity's own tick can ever fix this; the check has
     * to run from the world tick, which is why it lives here. And since a frozen unit can do nothing
     * physical at all, "vanilla won't tick it" is not merely a hint that it should be virtual - it is
     * the definition of it.
     */
    public static boolean isFrozenByVanilla(WorldServer world, Entity entity) {
        return isFrozenByVanillaAt(world, entity.posX, entity.posZ);
    }

    /**
     * Position-based form of {@link #isFrozenByVanilla}, for deciding whether an offscreen record may
     * become a real entity at a given spot.
     *
     * <p>Materialize and virtualize deliberately share this one predicate so they are exact
     * complements: a unit is physical precisely where vanilla would tick it, and virtual everywhere
     * else. Anything less strict on the materialize side re-creates the original bug from the other
     * direction - a record placed inside the 32-block band would spawn as a statue vanilla refuses to
     * tick, then be virtualized straight back by the rule above, over and over.
     */
    public static boolean isFrozenByVanillaAt(WorldServer world, double posX, double posZ) {
        if (isEntityTickingSuspended(world)) return true;
        int x = net.minecraft.util.math.MathHelper.floor(posX);
        int z = net.minecraft.util.math.MathHelper.floor(posZ);
        boolean ticketed = world.getPersistentChunks()
                .containsKey(new net.minecraft.util.math.ChunkPos(x >> 4, z >> 4));
        int range = ticketed ? 0 : 32;
        // BlockPos overload because vanilla's int one is private; identical bounds and allowEmpty flag.
        return !world.isAreaLoaded(
                new BlockPos(x - range, 0, z - range), new BlockPos(x + range, 0, z + range), true);
    }

    /**
     * True when vanilla has stopped ticking <b>every</b> entity in this world, not just the ones near
     * unloaded terrain.
     *
     * <p>{@code WorldServer.updateEntities()} opens with a gate that is easy to miss and changes
     * everything for this mod ({@code WorldServer:629}):
     *
     * <pre>if (this.playerEntities.isEmpty() &amp;&amp; getPersistentChunks().isEmpty()) {
     *     if (this.updateEntityTick++ &gt;= 300) return;
     * } else this.resetUpdateEntityTick();</pre>
     *
     * <p>So on a world with nobody in it and no chunk-loading ticket, 15 seconds later <b>no entity
     * ticks at all</b>. World ticks keep firing (this manager keeps running), which is precisely what
     * made the world spawn a one-way trap: Minecraft keeps the spawn chunks loaded forever, a
     * Millenaire building in them is a travel destination, so records walked in and materialized - and
     * then stood there as statues. They could not travel, could not get stuck, could not hand
     * themselves back to the offscreen simulation, and {@code checkVirtualize} saw a perfectly normal
     * unit in a loaded chunk. The whole world's population ended up stacked on the spawn chunks, and
     * only started draining when a player logged in and {@code resetUpdateEntityTick()} let their AI
     * run again - exactly the reported symptom.
     *
     * <p>Folding it in here rather than anywhere else is deliberate: this predicate's contract is "would
     * vanilla tick a unit standing at this spot", it was simply only mirroring the inner, per-entity
     * half of that question ({@code World.updateEntityWithOptionalForce}) and not the outer, per-world
     * one. Both sides read it, so materialize and virtualize stay exact complements: nothing new
     * appears in a world that has stopped ticking, and everything already in it goes back offscreen
     * where the simulation is still running. This is not a "is a player nearby" rule - a chunk-loading
     * ticket keeps entities ticking with nobody connected, and units then materialize there as usual.
     *
     * <p>The 300-tick countdown is not mirrored (it is a private field with no getter) and does not
     * need to be: once the condition holds, entities are guaranteed to stop within 15 seconds, and the
     * virtualize side has its own {@code virtualizeChunkUnneededTicks} debounce on top.
     */
    public static boolean isEntityTickingSuspended(WorldServer world) {
        return world.playerEntities.isEmpty() && world.getPersistentChunks().isEmpty();
    }

    // Since when each virtual record has stood on ground vanilla ticks, whatever chunk it is in.
    private final Map<UUID, Long> materializeWatch = new HashMap<>();
    private final Map<UUID, Long> virtualizeWatch = new HashMap<>();

    /**
     * True while this unit is inside the short settling window after materializing. checkVirtualize
     * already honours it; the edge probe has to ask explicitly, since it virtualizes directly rather
     * than going through that pass - and a unit that materializes right at the boundary would
     * otherwise be probed straight back out on its next tick, which is the same oscillation
     * materializeGraceUntil prevents from the other side.
     */
    public boolean isInVirtualizeGrace(UUID id) {
        return graceUntil.containsKey(id);
    }

    private static void logFrozenVirtualize(EntityTraveller entity, boolean ok) {
        if (!TravellersConfig.GENERAL.debug) return;
        net.minecraftforge.fml.common.FMLLog.log.info(
                "[Travellers/Frozen] {} at ({}, {}) was no longer ticked by vanilla -> virtualize {}",
                entity.getName(), (int) entity.posX, (int) entity.posZ, ok ? "OK" : "REFUSED");
    }

    // -------------------------------------------------------------------------
    // Virtualize / Materialize
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Record → entity state reconciliation
    // -------------------------------------------------------------------------

    /**
     * A palfrenier materializing at night reappears already asleep in its assigned Stable bed,
     * rather than walking there from wherever it spawned - unless it was pulled into a virtual
     * battle (r.battleId set), in which case it materializes awake and fighting via the normal path.
     * The sleep pose itself is loaded-only and re-derived here every materialize (see
     * {@code virtualizeWorker}, which clears it before snapshotting).
     */
    private void maybeRestoreStableSleep(WorldServer world, EntityTravellerWorker worker, VirtualTravellerRecord r) {
        if (!com.jubitus.travellers.camp.CampsiteManager.isNight(world)) return;
        BlockPos bed = resolveArrivedPalfrenierBed(world, r);
        if (bed == null) return;
        // The worker was already placed at the foot of this bed by resolveStableBedSpawnPos; snap it
        // into the bed (trySleepInBed positions itself from the bed geometry). If it can't lie down
        // (bed broken/obstructed) it just stays standing there and EntityAIWorkerDuty's night routine
        // walks it in - we never leave it on the bed block itself, which pushes it back out.
        worker.sleep.trySleepIn(bed);
    }

    /**
     * The bed of a palfrenier that has actually reached its Stable (STABLE_HAND duty, has a bed
     * assignment, within arrival range of the zone, not mid-battle), or null. Used both to decide the
     * materialize spawn position (foot of the bed, inside the structure) and whether to restore the
     * night sleep pose. A palfrenier still travelling keeps its route and spawns on it, not here.
     */
    @Nullable
    private BlockPos resolveArrivedPalfrenierBed(WorldServer world, VirtualTravellerRecord r) {
        if (r.battleId != null) return null;
        if (!"STABLE_HAND".equals(r.dutyKind) || r.dutyZoneCenter == null || r.followOwnerUUID == null) return null;
        if (r.personalTravelGoal != null) return null; // still in transit
        double dx = r.dutyZoneCenter.getX() + 0.5D - r.posX;
        double dz = r.dutyZoneCenter.getZ() + 0.5D - r.posZ;
        if (dx * dx + dz * dz > 64.0D * 64.0D) return null; // small margin over JOB_ARRIVAL_FORCE_DIST
        com.jubitus.travellers.zone.PlayerZoneRecord zone =
                com.jubitus.travellers.zone.PlayerZoneManager.get(world)
                        .getByOwnerAndCenter(r.followOwnerUUID, r.dutyZoneCenter);
        if (zone == null || !(zone.data instanceof com.jubitus.travellers.zone.stable.StableZoneData)) return null;
        BlockPos bed = ((com.jubitus.travellers.zone.stable.StableZoneData) zone.data).bedByWorker.get(r.uuid);
        if (bed == null || !world.isBlockLoaded(bed)) return null;
        return bed;
    }

    /**
     * Spawn position for a palfrenier materializing at its Stable: a standable block at the foot of
     * its assigned bed, inside the structure - never the generic surface-clamped position, which for
     * a Stable built inside a building is routinely the rooftop. Mirrors the camp system's slot-anchored
     * spawn and reuses {@code PlayerZoneManager.findSpawnBesideBed} (the same "floor neighbour, never
     * above" search a Barrack death-respawn uses). Null when the worker isn't an arrived palfrenier.
     */
    @Nullable
    private MaterializePositionResolver.ResolvedPosition resolveStableBedSpawnPos(WorldServer world, VirtualTravellerRecord r) {
        BlockPos bed = resolveArrivedPalfrenierBed(world, r);
        if (bed == null) return null;
        BlockPos spot = com.jubitus.travellers.zone.PlayerZoneManager.findSpawnBesideBed(world, bed);
        return new MaterializePositionResolver.ResolvedPosition(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, "stableBed");
    }

    /**
     * Returns true if the worker actually went offscreen (it is dead and a record now holds it).
     *
     * <p>Refuses when a record for this identity already exists: two copies of one unit, one loaded
     * and one virtual, is a state nothing downstream can reconcile, and overwriting the record would
     * throw away whatever the offscreen simulation has been doing with it. See
     * {@link TravellerLoadDedupHandler} for where such a duplicate comes from and how it is resolved.
     */
    public boolean virtualizeWorker(WorldServer world, EntityTravellerWorker worker, long now) {
        if (records.containsKey(worker.getUniqueID())) return false;

        VirtualTravellerRecord r = VirtualSnapshot.captureWorker(world, worker, now);

        // getVirtualGoal()'s escort/follow-owner branches deliberately seed the raw leader/owner
        // position with no formation offset applied (see its own comment) - by design that's only
        // meant to survive until the next scheduled refreshGoal pass, up to GOAL_REFRESH_INTERVAL
        // (20) ticks away, refines it into the real slot. In that window advanceVirtual already walks
        // r.posX/r.posZ toward the unrefined raw target, and a materialize check can land while it's
        // still there - which is what made an escort follower "teleport" onto its worker leader's
        // exact tile on its next materialize. Refine immediately so no tick ever observes the raw
        // seed as anything other than a resolved formation slot.
        if (r.escortLeaderUUID != null || (r.isHiredFollower && r.followOwnerUUID != null && r.followOrderActive)) {
            refreshGoal(world, r, now);
        }

        // Non-hired workers camp exactly like travellers; re-derive occupation from the campsite.
        if (TravellersConfig.CAMP_EVENTS.workersCanCamp && !r.isHiredFollower) {
            VirtualSnapshot.deriveCampOccupation(world, worker.getUniqueID(), now, r);
        }

        // Followers are NOT force-virtualized here; each one virtualizes individually
        // once it leaves the player's view distance (via checkVirtualize).
        return commitVirtualized(world, worker, r, now);
    }

    /** Final step of both virtualize doors: the record takes the identity, then the body leaves the world. */
    private boolean commitVirtualized(WorldServer world, EntityTraveller entity, VirtualTravellerRecord r, long now) {
        records.put(r.uuid, r);
        materializeGraceUntil.put(r.uuid, now + TravellersConfig.MATERIALIZATION.rematerializeGraceTicks);
        com.jubitus.travellers.battle.VirtualBattleManager.get(world)
                .restoreBattleIdIfParticipant(r);
        VirtualMounts.captureIfBonded(entity, r);
        entity.setDead();
        markDirty();
        return true;
    }

    /**
     * Ends a virtual rider's bond after its horse died in the loaded world (see
     * {@code HorseEvents}). Without this the record would keep its captured mount data and
     * materialize the rider onto a horse the player just watched die.
     */
    public void clearRecordMount(UUID riderUUID) {
        VirtualTravellerRecord r = records.get(riderUUID);
        if (r == null) return;
        r.mounted = false;
        r.mountNbt = null;
        r.mountMode = null;
        // The horse is dead: nothing may rebuild it from the frozen snapshot at materialize.
        if (r.entityNbt != null) {
            r.entityNbt.removeTag("MountUUIDMost");
            r.entityNbt.removeTag("MountUUIDLeast");
            r.entityNbt.removeTag("MountSnapshot");
            r.entityNbt.removeTag("VillageHitchStop");
        }
        if (r.visit != null) {
            r.visit.hitchPos = null;
            r.visit.horseAtPost = false;
        }
        markDirty();
    }

    /**
     * Symmetric counterpart to {@link #clearRecordMount}: hands an already-virtual rider back a bonded
     * mount it had lost, straight into the record - mirrors exactly what {@code captureMountIfBonded}
     * would have written had the horse still been reachable at virtualize time. Called by
     * {@code TravellerMountSupport.handleBondedHorseChunkUnload} for the one gap that scrubs a rider's
     * mount reference honestly (its horse's own chunk unloaded on a different tick - see that method's
     * doc) but leaves the actual horse recoverable a little longer: this is that recovery.
     */
    public void captureOrphanedMount(UUID riderUUID, NBTTagCompound mountNbt) {
        VirtualTravellerRecord r = records.get(riderUUID);
        if (r == null) return;
        r.mounted = true;
        r.mountNbt = mountNbt;
        if (r.visit != null) r.visit.horseAtPost = false;
        markDirty();
    }

    /**
     * Virtualizes a non-worker EntityTraveller (traveller or raider). Returns true if it actually
     * went offscreen - see {@link #virtualizeWorker} for why the duplicate case refuses rather than
     * overwriting, and why callers must not assume success.
     */
    public boolean virtualizeTraveller(WorldServer world, EntityTraveller entity, long now) {
        if (records.containsKey(entity.getUniqueID())) return false;

        VirtualTravellerRecord r = VirtualSnapshot.captureTraveller(world, entity, now);
        return commitVirtualized(world, entity, r, now);
    }

    /** Pairing of the raw camp slot (or null if not camping) and the resolved safe spawn position. */
    private static final class CampSpawnResult {
        @Nullable final BlockPos campStand;
        final MaterializePositionResolver.ResolvedPosition pos;

        CampSpawnResult(@Nullable BlockPos campStand, MaterializePositionResolver.ResolvedPosition pos) {
            this.campStand = campStand;
            this.pos = pos;
        }
    }

    /**
     * Camp-aware spawn positioning shared by traveller/raider/worker materialize: places a camping
     * entity inside its slot on a real floor (entrance point if the shelter already exists, to avoid
     * spawning inside a wall) rather than the raw virtual position. Returns {@code fallback} unchanged
     * when the record isn't camping.
     */
    private CampSpawnResult resolveCampSpawnPos(WorldServer world, VirtualTravellerRecord r,
                                                 MaterializePositionResolver.ResolvedPosition fallback) {
        if (r.campSiteId == null) return new CampSpawnResult(null, fallback);
        CampsiteManager campMgr = CampsiteManager.get(world);
        BlockPos campStand = campMgr.slotStandPos(r.campSiteId, r.campSlotIndex);
        if (campStand == null) return new CampSpawnResult(null, fallback);
        // Still on its way to the slot: it appears where it is and its camp AI walks the rest.
        if (!isAtCampSlot(r, campStand)) return new CampSpawnResult(campStand, fallback);

        CampsiteRecord campRec = campMgr.getById(r.campSiteId);
        CampOccupant occ = campRec != null ? campRec.occupants.get(r.campSlotIndex) : null;
        BlockPos spawnPos = (occ != null && occ.hasAnyStructure())
                ? campMgr.slotApproachPos(r.campSiteId, r.campSlotIndex)
                : campStand;
        if (spawnPos == null) spawnPos = campStand;
        double cy = MaterializePositionResolver.findSafeFloorY(
                world, spawnPos.getX(), spawnPos.getZ(), spawnPos.getY());
        cy = MaterializePositionResolver.ensureNotInSolid(world, spawnPos.getX(), cy, spawnPos.getZ());
        return new CampSpawnResult(campStand,
                new MaterializePositionResolver.ResolvedPosition(spawnPos.getX() + 0.5, cy, spawnPos.getZ() + 0.5, "campSlot"));
    }

    /**
     * Turns one record back into a real entity, and owns the hand-off: on success the record is
     * removed, on failure it is put back untouched and false is returned.
     *
     * <p>Never force-loads a chunk: a unit only ever materializes into terrain something else is
     * already keeping loaded (that is precisely what qualified it, see checkPlayerMaterialize).
     *
     * <p><b>The return value is the whole point of this signature.</b> {@code World.spawnEntity}
     * silently returns false for an entity whose target chunk is not loaded, and again if any mod
     * cancels the {@code EntityJoinWorldEvent} - no log, no exception. The record used to be removed
     * by the caller before this even ran, so a refusal deleted the unit permanently. Everything after
     * the spawn (mount restore, battle re-wiring, village registry) is likewise conditional on it
     * actually having worked.
     */
    private boolean materializeEntity(WorldServer world, VirtualTravellerRecord r, long now) {
        boolean isWorker = !("TRAVELLER".equals(r.entityClass) || "RAIDER".equals(r.entityClass));

        MaterializePositionResolver.ResolvedPosition pos =
                MaterializePositionResolver.resolve(world, r, records);

        if (isWorker) com.jubitus.travellers.debug.MissionDebugLog.record(r.uuid, r.name, "materialize", r.entityNbt);

        // Health may have changed virtually (battle sim) — sync into the frozen NBT before restore
        if (r.health >= 0f && r.entityNbt != null) {
            r.entityNbt.setFloat("Health", r.health);
        }

        // Camping entity: spawn inside its slot on a real floor (never legs-in-ground or floating).
        // Non-hired workers camp like travellers; hired/working ones never have a campSiteId set.
        CampSpawnResult campSpawn = resolveCampSpawnPos(world, r, pos);
        BlockPos campStand = campSpawn.campStand;
        pos = campSpawn.pos;

        if (isWorker) {
            // A palfrenier that has reached its Stable spawns at the foot of its bed inside the
            // structure, never the generic surface-clamp (a rooftop for an indoor Stable).
            MaterializePositionResolver.ResolvedPosition bedSpawn = resolveStableBedSpawnPos(world, r);
            if (bedSpawn != null) pos = bedSpawn;
        }

        // Every placement rule above may move the spawn point away from the position that qualified
        // this record, and some of them by a lot. This is the one gate that guarantees we still end
        // up somewhere the entity can exist.
        pos = MaterializePositionResolver.ensureSpawnable(world, r, pos);

        // Mid-crossing: reappear on the water where the boat was; the travel AI boards on its first tick.
        MaterializePositionResolver.ResolvedPosition afloat = campStand == null ? afloatSpawnPos(world, r) : null;
        if (afloat != null) pos = afloat;

        EntityTraveller entity;
        if (isWorker) {
            entity = new EntityTravellerWorker(world);
        } else {
            entity = "RAIDER".equals(r.entityClass)
                    ? new EntityTravellerRaider(world)
                    : new EntityTraveller(world);
        }
        entity.readFromNBT(r.entityNbt);
        // Attributes (max health, speed, ...) were frozen in NBT at virtualization time — re-apply
        // whatever the live config says now, so a config change made while offscreen isn't only
        // visible after the next full applyRuntimeEffects() pass.
        entity.onConfigReload();
        entity.setUniqueId(r.uuid);
        // Unconditional: entityNbt never carries the itinerary, the record is authoritative.
        entity.setItineraryState(r.itinerary);
        // Same for the village stay: the loaded visit task resumes it with the time left.
        entity.setVillageVisitState(r.visit);

        // A mounted record resolves its position rider-sized above, but the horse that is about to
        // appear under it is far wider/taller: a rider dropped in a tree-lined gap leaves the horse
        // no column to fit, and the mount footprint's last-resort fallback then floats it into the
        // canopy. Shift the whole pair to ground the horse's own body fits before either spawns, so
        // spawnMountFromNbt can place the horse right at the rider and remount on the spot.
        if (afloat == null && r.mounted && world.getChunkProvider().chunkExists(
                MathHelper.floor(pos.x) >> 4, MathHelper.floor(pos.z) >> 4)) {
            net.minecraft.entity.passive.EntityHorse scratch = new net.minecraft.entity.passive.EntityHorse(world);
            if (r.mountNbt != null) {
                scratch.readFromNBT(r.mountNbt);
            } else {
                scratch.onInitialSpawn(world.getDifficultyForLocation(
                        new BlockPos(MathHelper.floor(pos.x), MathHelper.floor(pos.y), MathHelper.floor(pos.z))), null);
            }
            BlockPos hf = MaterializePositionResolver.findClearMountFootprint(
                    world, scratch, entity, pos.x, pos.y, pos.z, null,
                    TravellersConfig.MATERIALIZATION.mountFootprintSearchRadius, true);
            if (hf != null) {
                pos = new MaterializePositionResolver.ResolvedPosition(hf.getX() + 0.5, hf.getY(), hf.getZ() + 0.5, pos.via + ">mountFootprint");
            }
        }

        if (r.visit != null && r.visit.facing != null) {
            // A visitor reappears facing the building it was at.
            r.rotYaw = (float) (Math.atan2(r.visit.facing.getZ() + 0.5 - pos.z, r.visit.facing.getX() + 0.5 - pos.x)
                    * 180.0 / Math.PI) - 90.0F;
        }
        entity.setPositionAndRotation(pos.x, pos.y, pos.z, r.rotYaw, 0.0f);
        com.jubitus.travellers.debug.MapJumpLog.materialized(r.uuid, world.getMinecraftServer().getTickCounter(),
                r.posX, r.posZ, pos.x, pos.z, pos.via);
        entity.motionX = 0;
        entity.motionY = 0;
        entity.motionZ = 0;

        if (isWorker) {
            VirtualSnapshot.applyToWorker(r, (EntityTravellerWorker) entity);
        } else {
            VirtualSnapshot.applyToTraveller(r, entity);
        }

        // The trip moved on offscreen: a visit finished before virtualizing says nothing about now.
        entity.getTravelCommon().setVisitedStop(null);
        if (campStand != null) {
            // Camping: stay put at the slot. Real camp behaviour (build/sleep/wander) is AI (P4).
            entity.getTravelCommon().setReadyToDepart(false);
            entity.getTravelCommon().setVillageIdling(true);
            entity.getTravelCommon().setVillageIdleTicks(0);
            // Sleep is a purely-loaded cosmetic pose, never simulated virtually or persisted to
            // NBT — but force it off anyway so a fresh materialize can never render "lying down"
            // while EntityAICampStay (re-evaluating from scratch) thinks the entity is standing.
            entity.sleep.wakeUp();
        } else if (!isWorker) {
            // The trip as it advanced offscreen; the itinerary (already handed over above) keeps the
            // current leg's line, so the entity continues exactly where the record was heading.
            if (r.currentTripStop() != null) {
                entity.restoreVirtualRoute(r.tripStops, r.tripIndex);
            } else {
                entity.getTravelCommon().setReadyToDepart(true);
                entity.getTravelCommon().setVillageIdleTicks(0);
                entity.getTravelCommon().setVillageIdling(false);
            }
        } else if (r.currentTripStop() != null
                && !r.isHiredFollower && "IDLE".equals(r.workerMode)
                && r.personalTravelGoal == null && r.escortLeaderUUID == null) {
            // A non-recruited idle worker that was roaming gets its trip back, same rule as travellers.
            // Every other worker case is handled by VirtualSnapshot.applyToWorker above.
            entity.restoreVirtualRoute(r.tripStops, r.tripIndex);
        }

        // Hand the identity over atomically: the record must be gone before the entity joins (a
        // record still present is what TravellerLoadDedupHandler treats as proof of a duplicate, and
        // it would cancel this very spawn), and must come straight back if the join is refused.
        // INVARIANT: loaded XOR virtual - remove the record just before the spawn, restore it if refused.
        records.remove(r.uuid);
        com.jubitus.travellers.debug.autotest.AutotestInvariants.beforeSpawn(pos.via);
        if (!world.spawnEntity(entity)) {
            records.put(r.uuid, r);
            net.minecraftforge.fml.common.FMLLog.log.warn(
                    "[Travellers/Virtual] world refused to spawn {} ({}) at ({}, {}, {}) - keeping it "
                            + "virtual and retrying later",
                    r.name, r.entityClass, (int) pos.x, (int) pos.y, (int) pos.z);
            return false;
        }

        VirtualMounts.restoreOrBond(entity, r);
        if (isWorker) {
            maybeRestoreStableSleep(world, (EntityTravellerWorker) entity, r);
        }
        if (r.battleId != null) {
            com.jubitus.travellers.battle.VirtualBattleManager.get(world)
                    .onParticipantMaterialized(world, r.uuid, entity);
        }
        if (isWorker && r.homeVillage != null) {
            com.jubitus.travellers.spawn.TravellerVillageRegistry reg =
                    com.jubitus.travellers.spawn.TravellerVillageRegistry.get(world);
            if (reg.getWorkerFor(r.homeVillage) == null) {
                reg.setWorkerFor(r.homeVillage, r.uuid);
            }
        }

        graceUntil.put(r.uuid, now + MATERIALIZE_GRACE_TICKS);
        markDirty();
        return true;
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public boolean isVirtual(UUID uuid) {
        return records.containsKey(uuid);
    }

    @Nullable
    public BlockPos getVirtualPosition(UUID uuid) {
        VirtualTravellerRecord r = records.get(uuid);
        if (r == null) return null;
        return new BlockPos(r.posX, r.posY, r.posZ);
    }

    public int getTotalCount() {
        return records.size();
    }

    public int getHiredCount() {
        int n = 0;
        for (VirtualTravellerRecord r : records.values()) {
            if (r.isHiredFollower) n++;
        }
        return n;
    }

    /** Remove the virtual record for a worker that died via other means. */
    public void removeRecord(WorldServer world, UUID uuid) {
        VirtualTravellerRecord r = records.remove(uuid);
        if (r == null) return;
        markDirty();
        // Died offscreen: a horse held as data goes with it, but one still standing somewhere (its
        // post) becomes an ordinary loose horse, now or when its chunk next loads.
        if (r.mountNbt == null && r.entityNbt != null && r.entityNbt.hasUniqueId("MountUUID")) {
            TravellerMountSupport.releaseHorseOfGoneRider(world, r.entityNbt.getUniqueId("MountUUID"));
        }
    }

    /**
     * Removes all virtual records whose entityClass matches any of the given values.
     * Returns the number of records removed.
     */
    public int clearByClass(String... classes) {
        java.util.Set<String> target = new java.util.HashSet<>(java.util.Arrays.asList(classes));
        int removed = 0;
        java.util.Iterator<VirtualTravellerRecord> it = records.values().iterator();
        while (it.hasNext()) {
            if (target.contains(it.next().entityClass)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) markDirty();
        return removed;
    }

    public boolean isWorkerOwnedByPlayer(UUID workerUUID, UUID playerUUID) {
        VirtualTravellerRecord r = records.get(workerUUID);
        if (r == null) return false;
        return r.isHiredFollower && playerUUID.equals(r.followOwnerUUID);
    }

    /**
     * Removes all WORKER virtual records that are not currently hired by a player.
     * Returns the number of records removed.
     */
    public int clearWorkersExceptHired() {
        int removed = 0;
        java.util.Iterator<VirtualTravellerRecord> it = records.values().iterator();
        while (it.hasNext()) {
            VirtualTravellerRecord r = it.next();
            if ("WORKER".equals(r.entityClass) && !r.isHiredFollower) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) markDirty();
        return removed;
    }

    // Group / leader reassignment (create, assign, come back, merge, make-leader, go-to) and the Hub
    // leader-list build all live in com.jubitus.travellers.follow.group.WorkerGroupService now, so
    // the loaded and virtual sides are handled by one shared path. The old cmd*/buildLeaderList
    // helpers were removed with that centralization.

    public Iterable<VirtualTravellerRecord> getRecordsView() {
        return records.values();
    }

    @javax.annotation.Nullable
    public VirtualTravellerRecord getRecord(UUID uuid) {
        return records.get(uuid);
    }

    public java.util.Collection<VirtualTravellerRecord> getAllRecords() {
        return records.values();
    }

    /** Number of virtual workers whose home village matches the given key (Y ignored). */
    public int getCountForVillage(BlockPos key) {
        int n = 0;
        for (VirtualTravellerRecord r : records.values()) {
            if (r.homeVillage == null) continue;
            if (r.homeVillage.getX() == key.getX() && r.homeVillage.getZ() == key.getZ()) n++;
        }
        return n;
    }

    /** Number of virtual entities of a given entityClass whose home village matches the key (Y ignored). */
    public int countByHomeVillage(WorldServer world, BlockPos key, String entityClass) {
        int n = 0;
        for (VirtualTravellerRecord r : records.values()) {
            if (!entityClass.equals(r.entityClass)) continue;
            if (r.homeVillage == null) continue;
            // INVARIANT: raid raiders never count toward a village quota (the soft spawner would refill them).
            if (com.jubitus.travellers.raid.RaidWorldData.isRaider(world, r.uuid)) continue;
            if (r.homeVillage.getX() == key.getX() && r.homeVillage.getZ() == key.getZ()) n++;
        }
        return n;
    }

    /** Count all virtual records of a given entityClass. */
    public int countByClass(String entityClass) {
        int n = 0;
        for (VirtualTravellerRecord r : records.values()) {
            if (entityClass.equals(r.entityClass)) n++;
        }
        return n;
    }

    /** Add a pre-built record (used for virtual spawning without world entity). */
    /** Re-aims a raid leader's single-stop trip at {@code goal} (the raid's player moved). */
    public void retargetRaidLeader(UUID uuid, BlockPos goal) {
        VirtualTravellerRecord r = records.get(uuid);
        if (r == null || r.groupLeaderUUID != null) return;
        r.setTrip(java.util.Collections.singletonList(goal), 0);
        r.itinerary.clear();
        r.goal = goal;
        markDirty();
    }

    public void addRecord(VirtualTravellerRecord r) {
        records.put(r.uuid, r);
        markDirty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        records.clear();
        NBTTagList list = nbt.getTagList("VW", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            try {
                VirtualTravellerRecord r = VirtualTravellerRecord.fromNbt(list.getCompoundTagAt(i));
                records.put(r.uuid, r);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (VirtualTravellerRecord r : records.values()) {
            list.appendTag(r.toNbt());
        }
        nbt.setTag("VW", list);
        return nbt;
    }
}
