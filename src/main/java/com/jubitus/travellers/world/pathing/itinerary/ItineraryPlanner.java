package com.jubitus.travellers.world.pathing.itinerary;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.world.pathing.MPBIntegration;
import com.jubitus.travellers.world.pathing.route.RouteCacheKeys;
import com.jubitus.travellers.world.pathing.route.RoadLayer;
import com.jubitus.travellers.world.pathing.route.RouteCostParams;
import com.jubitus.travellers.world.pathing.route.TravelPlan;
import com.jubitus.travellers.world.pathing.route.TravelRouteJob;
import com.jubitus.travellers.world.pathing.route.TravelRoutePlanner;
import com.jubitus.travellers.world.pathing.route.WallRings;
import com.jubitus.travellers.world.mill.MillSettlements;
import com.jubitus.travellers.world.mill.VillageGateIndex;
import com.jubitus.travellers.world.pathing.terrain.TerrainGridView;
import com.jubitus.travellers.world.pathing.terrain.TerrainProfileGrid;
import com.jubitus.travellers.world.pathing.terrain.TerrainWorldState;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLLog;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one place that decides a leg's itinerary, for loaded and virtual units alike: MPB road legs
 * (simplified) plus straight bridges laid immediately, each bridge later upgraded to a coarse terrain
 * polyline on the planner thread and spliced in wherever the unit is by then (record or entity).
 * Server thread only.
 */
public final class ItineraryPlanner {

    private ItineraryPlanner() {
    }

    private static final int BACKLOG_RETRY_TICKS = 40;
    // A job that never reported back (planner failure) stops blocking a re-request after this long.
    private static final long PENDING_EXPIRY_TICKS = 1200L;

    private static final class Pending {
        final UUID owner;
        final BlockPos start;
        final BlockPos end;
        final long requestedTick;

        Pending(UUID owner, BlockPos start, BlockPos end, long requestedTick) {
            this.owner = owner;
            this.start = start;
            this.end = end;
            this.requestedTick = requestedTick;
        }
    }

    // Bridge jobs in flight, keyed by a fresh per-bridge UUID (one unit can have several).
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private static long budgetTick = Long.MIN_VALUE;
    private static int budgetUsed;

    /** Per-tick admission budget shared by itinerary builds, bridge jobs and virtual destination picks. */
    public static boolean tryAcquire(long now) {
        if (now != budgetTick) {
            budgetTick = now;
            budgetUsed = 0;
        }
        if (budgetUsed >= Math.max(1, TravellersConfig.ROUTING.routeJobsPerTick)) return false;
        budgetUsed++;
        return true;
    }

    public static boolean mayUseRoads(boolean raider) {
        return MPBIntegration.PRESENT && (!raider || TravellersConfig.SPAWN_RAIDERS.raidersUseUltreiaPaths);
    }

    /**
     * Lays a fresh itinerary from {@code (x,z)} to {@code dest} into {@code st} and starts planning its
     * bridges. {@code traced} is the loaded entity for trace events, null for a virtual owner.
     */
    public static void build(WorldServer world, UUID owner, ItineraryState st, double x, double y, double z,
                             BlockPos dest, boolean useRoads, boolean mounted, @Nullable String cultureId,
                             boolean loaded, long now, @Nullable EntityTraveller traced) {
        dropPending(owner);
        BlockPos origin = new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
        // With the terrain planner and Ultreia's road-box query, roads are part of the route search
        // itself (any stretch of road that pays off is taken). Otherwise fall back to Ultreia's own
        // whole-trip road route, which only exists when both ends are near a connected road.
        boolean roadsInSearch = useRoads && terrainPlanningOn(world) && MPBIntegration.hasRoadBoxQuery();
        java.util.List<java.util.List<BlockPos>> legs = useRoads && !roadsInSearch
                ? MPBIntegration.findRoadRouteLegs(world, origin.getX(), origin.getZ(), dest.getX(), dest.getZ(),
                        TravellersConfig.ROUTING.roadJoinSearchRadius)
                : null;
        String roadNote = roadsInSearch ? " roads=inSearch" : "";
        if (legs != null && !legs.isEmpty()) {
            double ratio = roadDetourRatio(origin, legs, dest, RouteCostParams.roadDetourRatio(mounted));
            if (ratio > RouteCostParams.roadDetourRatio(mounted)) {
                roadNote = String.format(java.util.Locale.ROOT, " roadsRejected(detour=%.2fx)", ratio);
                legs = null;
            }
        }
        if (!st.isFor(dest)) st.excludedGates.clear();
        java.util.List<BlockPos> exitVia = null, enterVia = null;
        if (TravellersConfig.TRAVEL.routeThroughVillageGates) {
            int approach = TravellersConfig.ROUTING.gateApproachBlocks;
            exitVia = ItineraryGates.exitVia(ringNear(world, origin, RING_LOOKUP_BLOCKS), origin, dest, approach, st.excludedGates);
            enterVia = ItineraryGates.enterVia(ringNear(world, dest, DEST_RING_LOOKUP_BLOCKS), origin, dest, approach, st.excludedGates);
        }
        ItineraryAssembly.Seed seed = ItineraryAssembly.assemble(origin, exitVia, legs, enterVia, dest,
                Math.max(0, TravellersConfig.ROUTING.legCapBlocks),
                TravellersConfig.ROUTING.itineraryRoadSimplifyBlocks);
        st.set(seed.itinerary, x, z, seed.bridges, now);
        st.useRoads = roadsInSearch;

        String msg = "itinerary built -> " + dest.getX() + "," + dest.getZ()
                + " src=" + seed.itinerary.source + " verts=" + seed.itinerary.size()
                + " " + seed.itinerary.kindsSummary()
                + " len=" + (int) seed.itinerary.totalLength() + " bridges=" + seed.bridges.size() + roadNote;
        if (traced != null) TravellerTracer.event(traced, "path", msg);
        rlog(owner, msg);

        tryPlanBacklog(world, owner, st, mounted, cultureId, loaded, now);
    }

    /**
     * How much the road trip "costs" compared to walking straight, scaled so it compares directly to
     * roadMaxDetourRatio: off-road blocks cost 1, road blocks 1/roadMaxDetourRatio. A trip entirely on
     * a road exactly roadMaxDetourRatio times longer than the straight line scores exactly that ratio.
     */
    static double roadDetourRatio(BlockPos origin, java.util.List<java.util.List<BlockPos>> legs, BlockPos dest,
                                  double ratio) {
        double straight = Math.max(1.0, dist(origin, dest));
        double offRoad = 0.0, road = 0.0;
        BlockPos cur = origin;
        for (java.util.List<BlockPos> leg : legs) {
            if (leg == null || leg.isEmpty()) continue;
            offRoad += dist(cur, leg.get(0));
            for (int i = 1; i < leg.size(); i++) road += dist(leg.get(i - 1), leg.get(i));
            cur = leg.get(leg.size() - 1);
        }
        offRoad += dist(cur, dest);
        double cost = offRoad + road / ratio;
        return cost / straight * ratio;
    }

    // How far from a leg's origin / destination the walled village it leaves / enters is looked up.
    private static final int RING_LOOKUP_BLOCKS = 256;
    private static final int DEST_RING_LOOKUP_BLOCKS = 32;

    /** The wall ring of the village nearest {@code p} (villages only), or null when it has no gates. */
    @Nullable
    private static ItineraryGates.Ring ringNear(WorldServer world, BlockPos p, int radius) {
        MillSettlements.Settlement v = MillSettlements.nearest(world, p, radius);
        if (v == null || !v.isVillage) return null;
        return toRing(VillageGateIndex.ringFor(world, v.center));
    }

    @Nullable
    private static ItineraryGates.Ring toRing(@Nullable VillageGateIndex.Ring r) {
        return r == null ? null : new ItineraryGates.Ring(r.center.getX(), r.center.getZ(), r.radius, r.gates);
    }

    /** Every walled village whose ring reaches into the box, as search obstacles. */
    private static WallRings wallsIn(WorldServer world, int[] box) {
        if (!TravellersConfig.TRAVEL.routeThroughVillageGates) return WallRings.EMPTY;
        MillSettlements.Snapshot snap = MillSettlements.snapshot(world);
        java.util.List<VillageGateIndex.Ring> found = null;
        for (BlockPos c : snap.villageCenters) {
            if (c.getX() < box[0] - RING_LOOKUP_BLOCKS || c.getX() > box[2] + RING_LOOKUP_BLOCKS
                    || c.getZ() < box[1] - RING_LOOKUP_BLOCKS || c.getZ() > box[3] + RING_LOOKUP_BLOCKS) continue;
            VillageGateIndex.Ring r = VillageGateIndex.ringFor(world, c);
            if (r == null) continue;
            if (found == null) found = new java.util.ArrayList<>();
            found.add(r);
        }
        if (found == null) return WallRings.EMPTY;
        int n = found.size();
        int[] xs = new int[n], zs = new int[n];
        double[] rs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = found.get(i).center.getX();
            zs[i] = found.get(i).center.getZ();
            rs[i] = found.get(i).radius;
        }
        return new WallRings(xs, zs, rs);
    }

    private static boolean terrainPlanningOn(WorldServer world) {
        TerrainWorldState terr = TerrainWorldState.get(world);
        return terr != null && terr.getGrid() != null;
    }

    private static double dist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Requests a terrain plan for every straight bridge not already in flight. Budgeted and throttled. */
    public static void tryPlanBacklog(WorldServer world, UUID owner, ItineraryState st, boolean mounted,
                                      @Nullable String cultureId, boolean loaded, long now) {
        if (!st.hasBacklog() || now < st.nextBacklogTry) return;
        st.nextBacklogTry = now + BACKLOG_RETRY_TICKS;

        TerrainWorldState terr = TerrainWorldState.get(world);
        TerrainProfileGrid grid = terr != null ? terr.getGrid() : null;
        if (grid == null) {
            st.backlog = null; // planned routes turned off: stay straight
            return;
        }
        Itinerary it = st.get();
        RouteCostParams params = RouteCostParams.snapshot(mounted);
        Iterator<ItineraryAssembly.Bridge> iter = st.backlog.iterator();
        while (iter.hasNext()) {
            ItineraryAssembly.Bridge b = iter.next();
            int si = it == null ? -1 : it.indexOfXZ(b.start.getX(), b.start.getZ(), 0);
            if (si < 0 || si + 1 >= it.size()
                    || it.x(si + 1) != b.end.getX() || it.z(si + 1) != b.end.getZ()) {
                iter.remove(); // already spliced, or the itinerary changed under us
                continue;
            }
            if (isPending(owner, b, now)) continue;
            if (!tryAcquire(now)) break;
            requestBridge(world, owner, b, grid, params, mounted, cultureId, loaded, st.useRoads, now);
        }
        if (st.backlog != null && st.backlog.isEmpty()) st.backlog = null;
    }

    private static void requestBridge(WorldServer world, UUID owner, ItineraryAssembly.Bridge b,
                                      TerrainProfileGrid grid, RouteCostParams params, boolean mounted,
                                      @Nullable String cultureId, boolean loaded, boolean useRoads, long now) {
        int spacing = Math.max(16, TravellersConfig.ROUTING.coarseNodeSpacing);
        int budget = Math.max(16, TravellersConfig.ROUTING.coarseNodeBudget);
        int[] box = TravelRouteJob.computeBox(b.start.getX(), b.start.getZ(), b.end.getX(), b.end.getZ(),
                TravellersConfig.ROUTING.coarseBoxInflation, spacing);
        // Unknown terrain would read as flat and free: wait for the region files (a few ticks), retried later.
        if (!grid.ensureBoxResident(box[0], box[1], box[2], box[3])) return;
        grid.ensureEstimated(world, box[0], box[1], box[2], box[3]);
        TerrainGridView view = grid.captureView(box[0], box[1], box[2], box[3]);

        RoadLayer roads = useRoads
                ? RoadLayer.build(MPBIntegration.getRoadPolylinesInBox(world, box[0], box[1], box[2], box[3]),
                        TravellersConfig.ROUTING.itineraryRoadSimplifyBlocks, spacing)
                : RoadLayer.EMPTY;

        UUID key = UUID.randomUUID();
        TravelRouteJob job = new TravelRouteJob(
                world.provider.getDimension(), key,
                b.start.getX(), b.start.getZ(), b.end.getX(), b.end.getZ(),
                box[0], box[1], box[2], box[3],
                mounted, cultureId, params, now, view, spacing, budget, roads, wallsIn(world, box));
        String cacheKey = RouteCacheKeys.bucketed(world.provider.getDimension(), b.start, b.end, mounted)
                + (useRoads ? "|r" : "");
        // Priority 2 for a loaded unit: skips the stagger meant to spread virtual requests over ticks.
        boolean ok = loaded
                ? TravelRoutePlanner.request(world, job, cacheKey, 2)
                : TravelRoutePlanner.request(world, job, cacheKey);
        if (ok) PENDING.put(key, new Pending(owner, b.start, b.end, now));
    }

    private static boolean isPending(UUID owner, ItineraryAssembly.Bridge b, long now) {
        PENDING.values().removeIf(p -> now - p.requestedTick > PENDING_EXPIRY_TICKS);
        for (Pending p : PENDING.values()) {
            if (p.owner.equals(owner) && ItineraryAssembly.sameXZ(p.start, b.start)
                    && ItineraryAssembly.sameXZ(p.end, b.end)) {
                return true;
            }
        }
        return false;
    }

    private static void dropPending(UUID owner) {
        PENDING.values().removeIf(p -> p.owner.equals(owner));
    }

    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    /**
     * Splices a finished bridge plan into its owner's itinerary, whether the owner is still a record
     * or has materialized since. Returns false for a result this planner did not request.
     */
    public static boolean applyResult(WorldServer world, TravelRoutePlanner.RouteResult res,
                                      Function<UUID, VirtualTravellerRecord> records) {
        Pending p = PENDING.remove(res.key);
        if (p == null) return false;

        ItineraryState st;
        double x, z;
        EntityTraveller traced = null;
        VirtualTravellerRecord r = records.apply(p.owner);
        if (r != null) {
            if (r.battleId != null) return true;
            st = r.itinerary;
            x = r.posX;
            z = r.posZ;
        } else {
            Entity e = world.getEntityFromUuid(p.owner);
            if (!(e instanceof EntityTraveller) || e.isDead) return true;
            traced = (EntityTraveller) e;
            st = traced.getItineraryState();
            x = e.posX;
            z = e.posZ;
        }
        if (st.backlog != null) {
            st.backlog.removeIf(b -> ItineraryAssembly.sameXZ(b.start, p.start) && ItineraryAssembly.sameXZ(b.end, p.end));
            if (st.backlog.isEmpty()) st.backlog = null;
        }
        Itinerary it = st.get();
        if (it == null || st.isForced()) return true;
        Itinerary spliced = it.withSplice(p.start, p.end, res.plan.polyline, res.plan.kinds, res.plan.quality);
        if (spliced == null) return true; // anchors gone: the itinerary was rebuilt meanwhile
        st.replace(spliced, x, z, world.getTotalWorldTime());

        String msg = "itinerary spliced bridge " + p.start.getX() + "," + p.start.getZ()
                + "->" + p.end.getX() + "," + p.end.getZ()
                + " q=" + TravelPlan.qualityName(res.plan.quality)
                + (res.fromCache ? " (cache from " + res.fromX + "," + res.fromZ + ")" : "")
                + " roadVertsInBox=" + res.roadVertices
                + " verts=" + spliced.size() + " " + spliced.kindsSummary() + " len=" + (int) spliced.totalLength()
                + (res.plan.reachedGoal ? "" : " PARTIAL")
                + " plan=" + polylineText(res.plan.polyline);
        if (traced != null) TravellerTracer.event(traced, "path", msg);
        rlog(p.owner, msg);
        return true;
    }

    private static String polylineText(java.util.List<net.minecraft.util.math.BlockPos> pts) {
        StringBuilder sb = new StringBuilder();
        for (net.minecraft.util.math.BlockPos p : pts) {
            if (sb.length() > 0) sb.append(';');
            sb.append(p.getX()).append(',').append(p.getZ());
        }
        return sb.toString();
    }

    private static void rlog(UUID owner, String msg) {
        if (TravellersConfig.ROUTING.debug) {
            FMLLog.log.info("[Travellers/Route] " + owner.toString().substring(0, 8) + " " + msg);
        }
    }
}
