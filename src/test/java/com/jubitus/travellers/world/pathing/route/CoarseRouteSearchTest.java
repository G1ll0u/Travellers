package com.jubitus.travellers.world.pathing.route;

import com.jubitus.travellers.world.pathing.terrain.TerrainGridView;
import com.jubitus.travellers.world.pathing.terrain.TerrainProfile;
import com.jubitus.travellers.world.pathing.terrain.TerrainProfileRegion;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic-grid fixtures for the coarse A*: a mountain band, a pit, budget exhaustion, no-data. */
class CoarseRouteSearchTest {

    private static final int SPACING = 128;
    private static final int BUDGET = 4000;

    // A single region covering world blocks 0..8191.
    private static TerrainProfileRegion baseRegion(int baseHeight, int amp, int water) {
        TerrainProfileRegion r = new TerrainProfileRegion(0, 0);
        for (int i = 0; i < TerrainProfile.CELLS_PER_REGION; i++) {
            r.putCell(i, baseHeight, amp, water, TerrainProfile.SOURCE_REAL_SCAN, (short) 0);
        }
        r.dirty = false;
        return r;
    }

    private static void setBlockCell(TerrainProfileRegion r, int worldX, int worldZ,
                                     int h, int amp, int water, short biomeIx) {
        int li = TerrainProfile.localIndex(TerrainProfile.cellCoord(worldX), TerrainProfile.cellCoord(worldZ));
        r.putCell(li, h, amp, water, TerrainProfile.SOURCE_REAL_SCAN, biomeIx);
    }

    private static TerrainGridView viewOf(TerrainProfileRegion r, Set<Short> mountainIds) {
        Map<Long, TerrainProfileRegion> m = new HashMap<>();
        m.put(TerrainProfile.regionKey(r.regionX, r.regionZ), r);
        return TerrainGridView.forTest(m, mountainIds);
    }

    private static RouteCostParams params(double slope, double pit, double rough, double water,
                                          double deepOcean, double mountain) {
        return RouteCostParams.of(1.0, slope, pit, rough, water, deepOcean, 0.6, mountain, 2.0, 0.0, 100000.0);
    }

    private static TravelRouteJob job(TerrainGridView view, RouteCostParams p,
                                      int ox, int oz, int dx, int dz) {
        return new TravelRouteJob(0, UUID.randomUUID(), ox, oz, dx, dz,
                0, 0, 8191, 8191, false, null, p, 0L, view, SPACING, BUDGET);
    }

    private static double length(List<BlockPos> pts) {
        double d = 0;
        for (int i = 1; i < pts.size(); i++) {
            double dx = pts.get(i).getX() - pts.get(i - 1).getX();
            double dz = pts.get(i).getZ() - pts.get(i - 1).getZ();
            d += Math.sqrt(dx * dx + dz * dz);
        }
        return d;
    }

    /** True if any point sampled along the polyline falls inside the axis-aligned rectangle. */
    private static boolean polylineEntersRect(List<BlockPos> pts, int rMinX, int rMinZ, int rMaxX, int rMaxZ) {
        for (int i = 1; i < pts.size(); i++) {
            BlockPos a = pts.get(i - 1), b = pts.get(i);
            int steps = (int) (length(java.util.Arrays.asList(a, b)) / 16) + 1;
            for (int s = 0; s <= steps; s++) {
                double t = s / (double) steps;
                double x = a.getX() + (b.getX() - a.getX()) * t;
                double z = a.getZ() + (b.getZ() - a.getZ()) * t;
                if (x >= rMinX && x <= rMaxX && z >= rMinZ && z <= rMaxZ) return true;
            }
        }
        return false;
    }

    @Test
    void routesAroundAMountainBandThroughTheCorridor() {
        // Baseline plains; a 3-cell-wide mountain-biome band at x in [3840,4223] for z in [0,5119],
        // leaving an open corridor south of it. The direct line (z=4096) runs into the band.
        Set<Short> mountainIds = new HashSet<>(Collections.singletonList((short) 9));
        TerrainProfileRegion barrier = baseRegion(64, 8, 0);
        for (int wz = 0; wz <= 5119; wz += 16) {
            for (int wx = 3840; wx <= 4223; wx += 16) {
                setBlockCell(barrier, wx, wz, 64, 8, 0, (short) 9);
            }
        }
        TravelPlan withWall = CoarseRouteSearch.run(
                job(viewOf(barrier, mountainIds), params(0.8, 0, 0.15, 6, 60, 5000.0),
                        512, 4096, 7680, 4096), 100L);

        TerrainProfileRegion flat = baseRegion(64, 8, 0);
        TravelPlan straight = CoarseRouteSearch.run(
                job(viewOf(flat, Collections.emptySet()), params(0.8, 0, 0.15, 6, 60, 0.0),
                        512, 4096, 7680, 4096), 100L);

        assertTrue(withWall.reachedGoal, "should still reach the destination");
        assertEquals(7680, withWall.polyline.get(withWall.polyline.size() - 1).getX());
        assertTrue(length(withWall.polyline) > length(straight.polyline) * 1.04,
                "mountain route must be a detour: " + length(withWall.polyline)
                        + " vs straight " + length(straight.polyline));
        // The band's guaranteed-blocked core - the route cannot be here (it goes around the south end).
        assertFalse(polylineEntersRect(withWall.polyline, 3856, 0, 4207, 3600),
                "route must not cut through the mountain band");
        assertTrue(polylineEntersRect(straight.polyline, 3856, 3900, 4207, 4300),
                "sanity: the no-barrier route does run through that x-band on the direct line");
    }

    @Test
    void routesAroundASingleCellPitOnTheDirectLine() {
        // Baseline high ground h120; one 128-block cell on the direct line dropped to h40 and fully
        // ringed by h120, so pitExtra fires for the lattice node that lands in it.
        TerrainProfileRegion pit = baseRegion(120, 6, 0);
        for (int wz = 4096; wz <= 4223; wz += 16) {
            for (int wx = 4096; wx <= 4223; wx += 16) {
                setBlockCell(pit, wx, wz, 40, 6, 0, (short) 0);
            }
        }
        // Line at z=4160 (the pit cell's centre) so the detour clears the cell cleanly.
        TravelPlan around = CoarseRouteSearch.run(
                job(viewOf(pit, Collections.emptySet()), params(0.2, 3000.0, 0.05, 4, 60, 0),
                        512, 4160, 7680, 4160), 50L);
        TravelPlan straight = CoarseRouteSearch.run(
                job(viewOf(pit, Collections.emptySet()), params(0.0, 0.0, 0.05, 4, 60, 0),
                        512, 4160, 7680, 4160), 50L);

        assertTrue(around.reachedGoal);
        assertTrue(length(around.polyline) >= length(straight.polyline),
                "pit-avoiding route is at least as long as the straight one");
        for (BlockPos v : around.polyline) {
            boolean inPit = v.getX() >= 4096 && v.getX() <= 4223 && v.getZ() >= 4096 && v.getZ() <= 4223;
            assertFalse(inPit, "no route vertex may land in the pit cell: " + around.polyline);
        }
        boolean detoured = false;
        for (BlockPos v : around.polyline) if (v.getZ() != 4160) detoured = true;
        assertTrue(detoured, "route should step off the direct line to skirt the pit: " + around.polyline);
        // Sanity: with pitWeight=0 the route stays dead straight (and does pass through the pit cell).
        for (BlockPos v : straight.polyline) {
            assertEquals(4160, v.getZ(), "no-pit route stays on the direct line: " + straight.polyline);
        }
    }

    @Test
    void chargesARidgeSittingBetweenTwoLatticeNodes() {
        // Baseline plains h64. A short 2-cell-wide wall h150 at x in [576,607], z in [1600,2400] -
        // short enough that skirting an end is cheaper than crossing. With SPACING=128 the wall sits
        // entirely BETWEEN the lattice nodes at x=512 and x=640, so the search only detours it if it
        // samples along the edge rather than only at the far node.
        TerrainProfileRegion ridge = baseRegion(64, 6, 0);
        for (int wz = 1600; wz <= 2400; wz += 16) {
            for (int wx = 576; wx <= 607; wx += 16) {
                setBlockCell(ridge, wx, wz, 150, 6, 0, (short) 0);
            }
        }
        TravelPlan around = CoarseRouteSearch.run(
                job(viewOf(ridge, Collections.emptySet()), params(3.0, 0, 1.0, 6, 60, 0),
                        256, 2000, 4000, 2000), 0L);
        // control: slopeWeight/roughness 0 -> the wall is invisible and the route runs dead through it.
        TravelPlan straight = CoarseRouteSearch.run(
                job(viewOf(ridge, Collections.emptySet()), params(0.0, 0, 0.0, 6, 60, 0),
                        256, 2000, 4000, 2000), 0L);

        assertTrue(around.reachedGoal);
        assertTrue(polylineEntersRect(straight.polyline, 576, 1600, 607, 2400),
                "sanity: the slope-blind route runs straight through the ridge");
        assertFalse(polylineEntersRect(around.polyline, 576, 1600, 607, 2400),
                "slope-aware route must skirt the ridge, not cross it: " + around.polyline);
        assertTrue(length(around.polyline) > length(straight.polyline) * 1.02,
                "ridge route must be a detour: " + length(around.polyline)
                        + " vs straight " + length(straight.polyline));
    }

    @Test
    void budgetExhaustionStillReturnsAPolylineEndingAtTheDestination() {
        TerrainProfileRegion flat = baseRegion(64, 10, 0);
        TravelRouteJob tiny = new TravelRouteJob(0, UUID.randomUUID(), 256, 256, 7936, 7936,
                0, 0, 8191, 8191, false, null, params(0.8, 40, 0.15, 6, 60, 0), 0L,
                viewOf(flat, Collections.emptySet()), SPACING, 15);
        TravelPlan plan = CoarseRouteSearch.run(tiny, 0L);
        assertFalse(plan.polyline.isEmpty());
        assertEquals(7936, plan.polyline.get(plan.polyline.size() - 1).getX());
        assertEquals(7936, plan.polyline.get(plan.polyline.size() - 1).getZ());
        assertFalse(plan.reachedGoal, "15 expansions cannot cross the whole region");
    }

    @Test
    void noTerrainDataYieldsAnEssentiallyStraightLine() {
        TerrainGridView empty = TerrainGridView.forTest(Collections.emptyMap(), Collections.emptySet());
        TravelPlan plan = CoarseRouteSearch.run(
                job(empty, params(0.8, 40, 0.15, 6, 60, 0), 1000, 1000, 6000, 4000), 0L);
        assertTrue(plan.reachedGoal);
        BlockPos origin = new BlockPos(1000, 64, 1000);
        BlockPos dest = new BlockPos(6000, 64, 4000);
        assertEquals(1000, plan.polyline.get(0).getX());
        assertEquals(1000, plan.polyline.get(0).getZ());
        assertEquals(dest.getX(), plan.polyline.get(plan.polyline.size() - 1).getX());
        assertEquals(dest.getZ(), plan.polyline.get(plan.polyline.size() - 1).getZ());
        // With no terrain data every cell costs the same, so the route is the lattice's straight-line
        // staircase: every vertex stays within ~1.5 node spacings of the true chord.
        double maxDev = 0;
        for (BlockPos p : plan.polyline) maxDev = Math.max(maxDev, perpDev(p, origin, dest));
        assertTrue(maxDev <= SPACING * 1.5, "route wandered off the straight line: maxDev=" + maxDev);
    }

    private static double perpDev(BlockPos pt, BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len2 = dx * dx + dz * dz;
        double t = ((pt.getX() - a.getX()) * dx + (pt.getZ() - a.getZ()) * dz) / len2;
        double px = a.getX() + t * dx;
        double pz = a.getZ() + t * dz;
        double ex = pt.getX() - px, ez = pt.getZ() - pz;
        return Math.sqrt(ex * ex + ez * ez);
    }

    @Test
    void simplifyDropsCollinearAndNearCollinearPoints() {
        List<BlockPos> pts = java.util.Arrays.asList(
                new BlockPos(0, 64, 0), new BlockPos(100, 64, 0), new BlockPos(200, 64, 0),
                new BlockPos(300, 64, 2), new BlockPos(400, 64, 0), new BlockPos(500, 64, 0));
        List<BlockPos> out = CoarseRouteSearch.simplify(pts, 64.0);
        assertEquals(new BlockPos(0, 64, 0), out.get(0));
        assertEquals(new BlockPos(500, 64, 0), out.get(out.size() - 1));
        assertTrue(out.size() <= 3, "a nearly-straight line should collapse to its endpoints, was " + out);
    }

    @Test
    void flatGroundAtAnOddAngleIsOneStraightLineNotALatticeZigzag() {
        TerrainProfileRegion flat = baseRegion(64, 6, 0);
        TravelRouteJob fine = new TravelRouteJob(0, UUID.randomUUID(), 256, 256, 1256, 656,
                0, 0, 8191, 8191, false, null, params(3.0, 40, 0.15, 6, 60, 0), 0L,
                viewOf(flat, Collections.emptySet()), 16, 8000);
        TravelPlan plan = CoarseRouteSearch.run(fine, 0L);
        assertTrue(plan.reachedGoal);
        assertEquals(2, plan.polyline.size(), "straight on flat ground: " + plan.polyline);
    }

    private static List<BlockPos> straightRoad(int x0, int z0, int x1, int z1) {
        List<BlockPos> pts = new java.util.ArrayList<>();
        int n = (int) Math.ceil(Math.sqrt((double) (x1 - x0) * (x1 - x0) + (double) (z1 - z0) * (z1 - z0)) / 4.0);
        for (int k = 0; k <= n; k++) {
            pts.add(new BlockPos(x0 + (x1 - x0) * k / n, 64, z0 + (z1 - z0) * k / n));
        }
        return pts;
    }

    private static TravelPlan runWithRoads(List<List<BlockPos>> roads, int ox, int oz, int dx, int dz) {
        TerrainProfileRegion ground = baseRegion(64, 12, 0);
        RoadLayer layer = RoadLayer.build(roads, 2.0, 16);
        TravelRouteJob j = new TravelRouteJob(0, UUID.randomUUID(), ox, oz, dx, dz,
                0, 0, 8191, 8191, false, null, params(3.0, 40, 1.0, 6, 60, 0), 0L,
                viewOf(ground, Collections.emptySet()), 16, 20000, layer);
        return CoarseRouteSearch.run(j, 0L);
    }

    private static double roadLength(TravelPlan plan) {
        if (plan.kinds == null) return 0.0;
        double len = 0.0;
        for (int i = 0; i < plan.kinds.length; i++) {
            if (plan.kinds[i] != CoarseRouteSearch.ROAD) continue;
            len += length(java.util.Arrays.asList(plan.polyline.get(i), plan.polyline.get(i + 1)));
        }
        return len;
    }

    @Test
    void takesARoadThatOnlyCoversPartOfTheWay() {
        // Mirrors a live case: the road heads the right way for ~700 blocks but never reaches the
        // destination, which Ultreia's own whole-trip routing can't use at all.
        TravelPlan plan = runWithRoads(Collections.singletonList(straightRoad(468, 4444, 1136, 4498)),
                411, 4617, 2400, 3589);
        assertTrue(plan.reachedGoal);
        assertTrue(roadLength(plan) > 400, "expected a long road stretch, got " + roadLength(plan) + " " + plan.polyline);
    }

    @Test
    void ignoresARoadGoingTheWrongWay() {
        TravelPlan plan = runWithRoads(Collections.singletonList(straightRoad(600, 3000, 600, 6000)),
                400, 4000, 2400, 4000);
        assertTrue(plan.reachedGoal);
        assertTrue(roadLength(plan) < 64, "a crossing road should at most be crossed: " + roadLength(plan));
    }

    // ----- boat crossings (server-like weights) -----

    private static RouteCostParams boatParams(double boatFactor) {
        return RouteCostParams.withBoats(params(3.0, 40, 1.0, 6, 60, 25), boatFactor, 32.0, 0.75, 32.0);
    }

    private static TravelPlan runOverWater(TerrainProfileRegion r, double boatFactor, int ox, int oz, int dx, int dz) {
        TravelRouteJob j = new TravelRouteJob(0, UUID.randomUUID(), ox, oz, dx, dz,
                0, 0, 8191, 8191, false, null, boatParams(boatFactor), 0L,
                viewOf(r, Collections.emptySet()), 16, 20000, RoadLayer.EMPTY);
        return CoarseRouteSearch.run(j, 0L);
    }

    private static void water(TerrainProfileRegion r, int x0, int z0, int x1, int z1) {
        for (int wz = z0; wz <= z1; wz += 16) {
            for (int wx = x0; wx <= x1; wx += 16) setBlockCell(r, wx, wz, 62, 0, 255, (short) 0);
        }
    }

    private static double kindLength(TravelPlan plan, byte kind) {
        if (plan.kinds == null) return 0.0;
        double len = 0.0;
        for (int i = 0; i < plan.kinds.length; i++) {
            if (plan.kinds[i] != kind) continue;
            len += length(java.util.Arrays.asList(plan.polyline.get(i), plan.polyline.get(i + 1)));
        }
        return len;
    }

    @Test
    void crossesAWideLakeByBoatWhenThereIsNoWayAround() {
        TerrainProfileRegion r = baseRegion(64, 6, 0);
        water(r, 1008, 0, 1407, 8191);
        TravelPlan plan = runOverWater(r, 2.0, 600, 3000, 1800, 3000);
        assertTrue(plan.reachedGoal);
        double boat = kindLength(plan, CoarseRouteSearch.BOAT);
        assertTrue(boat > 300 && boat < 500, "expected one crossing of ~400 blocks, got " + boat + " " + plan.polyline);
        // Every boat vertex sits on the lake (shore vertices within one cell of it).
        for (int i = 0; i < plan.kinds.length; i++) {
            if (plan.kinds[i] != CoarseRouteSearch.BOAT) continue;
            int x = plan.polyline.get(i + 1).getX();
            assertTrue(x >= 992 && x <= 1423, "boat vertex off the lake: " + plan.polyline.get(i + 1));
        }
    }

    @Test
    void swimsANarrowRiverInsteadOfBoating() {
        TerrainProfileRegion r = baseRegion(64, 6, 0);
        water(r, 1008, 0, 1023, 8191);
        TravelPlan plan = runOverWater(r, 2.0, 600, 3000, 1800, 3000);
        assertTrue(plan.reachedGoal);
        assertEquals(0.0, kindLength(plan, CoarseRouteSearch.BOAT), "no boat for a 16-block river: " + plan.polyline);
    }

    @Test
    void walksAroundASmallPondRatherThanRowingAcrossIt() {
        TerrainProfileRegion r = baseRegion(64, 6, 0);
        water(r, 1008, 2896, 1407, 3103);
        TravelPlan plan = runOverWater(r, 2.0, 600, 3000, 1800, 3000);
        assertTrue(plan.reachedGoal);
        assertEquals(0.0, kindLength(plan, CoarseRouteSearch.BOAT), "pond is cheap to skirt: " + plan.polyline);
        assertFalse(polylineEntersRect(plan.polyline, 1040, 2930, 1376, 3070), "route should skirt the pond: " + plan.polyline);
    }

    @Test
    void neverPlansABoatWhenBoatsAreOff() {
        TerrainProfileRegion r = baseRegion(64, 6, 0);
        water(r, 1008, 0, 1407, 8191);
        TravelPlan plan = runOverWater(r, 0.0, 600, 3000, 1800, 3000);
        assertTrue(plan.reachedGoal);
        assertEquals(0.0, kindLength(plan, CoarseRouteSearch.BOAT));
    }

    @Test
    void demotesACrossingShorterThanTheMinimum() {
        List<BlockPos> path = new java.util.ArrayList<>(java.util.Arrays.asList(
                new BlockPos(0, 64, 0), new BlockPos(16, 64, 0), new BlockPos(32, 64, 0),
                new BlockPos(100, 64, 0), new BlockPos(200, 64, 0)));
        List<Byte> kinds = new java.util.ArrayList<>(java.util.Arrays.asList(
                CoarseRouteSearch.WALK, CoarseRouteSearch.BOAT, CoarseRouteSearch.WALK, CoarseRouteSearch.BOAT));
        CoarseRouteSearch.demoteShortCrossings(path, kinds, 32.0);
        assertEquals(CoarseRouteSearch.WALK, (byte) kinds.get(1), "16-block crossing demoted");
        assertEquals(CoarseRouteSearch.BOAT, (byte) kinds.get(3), "100-block crossing kept");
    }

    @Test
    void walksAroundAWalledVillageButMayFollowARoadThroughIt() {
        TerrainProfileRegion r = baseRegion(64, 6, 0);
        WallRings walls = new WallRings(new int[]{2000}, new int[]{3000}, new double[]{80});
        TravelRouteJob around = new TravelRouteJob(0, UUID.randomUUID(), 1000, 3000, 3000, 3000,
                0, 0, 8191, 8191, false, null, params(3.0, 40, 1.0, 6, 60, 25), 0L,
                viewOf(r, Collections.emptySet()), 16, 20000, RoadLayer.EMPTY, walls);
        TravelPlan plan = CoarseRouteSearch.run(around, 0L);
        assertTrue(plan.reachedGoal);
        assertFalse(polylineEntersRect(plan.polyline, 1950, 2950, 2050, 3050), "must not cross the village: " + plan.polyline);

        // A road straight through it (entering and leaving by its gates) is still usable.
        RoadLayer road = RoadLayer.build(Collections.singletonList(straightRoad(1100, 3000, 2900, 3000)), 2.0, 16);
        TravelRouteJob withRoad = new TravelRouteJob(0, UUID.randomUUID(), 1000, 3000, 3000, 3000,
                0, 0, 8191, 8191, false, null, params(3.0, 40, 1.0, 6, 60, 25), 0L,
                viewOf(r, Collections.emptySet()), 16, 20000, road, walls);
        TravelPlan viaRoad = CoarseRouteSearch.run(withRoad, 0L);
        assertTrue(viaRoad.reachedGoal);
        assertTrue(roadLength(viaRoad) > 1500, "road through the gates is taken: " + roadLength(viaRoad));
    }
}
