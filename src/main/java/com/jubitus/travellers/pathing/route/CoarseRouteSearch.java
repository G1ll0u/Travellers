package com.jubitus.travellers.pathing.route;

import com.jubitus.travellers.pathing.terrain.TerrainGridView;
import com.jubitus.travellers.pathing.terrain.TerrainProfile;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* on a virtual square lattice (spacing {@code job.nodeSpacing}), 8-connected, plus the road
 * vertices of {@code job.roads}, hard-capped at {@code job.nodeBudget} expansions. Runs on a planner worker thread - reads only the immutable
 * {@link TravelRouteJob} and its {@link TerrainGridView}.
 *
 * <p><b>Always returns a polyline.</b> On success, the route to the destination; on budget
 * exhaustion or a walled-in target, the route to the best (closest-to-goal) expanded node plus a
 * straight segment to the destination.
 */
public final class CoarseRouteSearch {

    private CoarseRouteSearch() {
    }

    private static final class Node {
        final int i;
        final int j;
        // Road vertex index in job.roads, or -1 for a lattice node.
        final int road;
        final int worldX;
        final int worldZ;
        final short height;
        // Afloat: reached by boat, may only continue over water or step ashore.
        final boolean boat;
        double g;
        double f;
        Node parent;
        // How it was reached from its parent: WALK, ROAD or BOAT.
        byte via;

        Node(int i, int j, int road, boolean boat, int worldX, int worldZ, short height) {
            this.i = i;
            this.j = j;
            this.road = road;
            this.boat = boat;
            this.worldX = worldX;
            this.worldZ = worldZ;
            this.height = height;
        }
    }

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    // A road vertex this close to another road's vertex is a junction you can step across.
    private static final double JUNCTION_BLOCKS = 10.0;

    public static TravelPlan run(TravelRouteJob job, long now) {
        return new Search(job).run(now);
    }

    /**
     * One A* over the terrain lattice plus the road layer: lattice edges cost distance + terrain,
     * road edges cost distance x roadCostFactor, and walking on or off a road costs the terrain in
     * between - one cost currency, so a road is taken exactly when (and as far as) it pays off.
     */
    private static final class Search {
        final TravelRouteJob job;
        final int s;
        final TerrainGridView view;
        final RouteCostParams p;
        final RoadLayer roads;
        final double hScale;
        // Boat edges are only offered when they can't undercut the heuristic (cost >= walking).
        final boolean boatsOn;
        final Map<Long, Node> best = new HashMap<>();
        // Tie-break equal f by preferring the deeper node (higher g == closer to the goal): shrinks
        // the explored fan and keeps the path straight where terrain cost is uniform.
        final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(a.f, b.f);
            return c != 0 ? c : Double.compare(b.g, a.g);
        });

        Search(TravelRouteJob job) {
            this.job = job;
            this.s = job.nodeSpacing;
            this.view = job.gridView;
            this.p = job.params;
            this.roads = job.roads;
            // Roads make travel cheaper than the plain distance: scale the heuristic so it stays admissible.
            this.hScale = roads.isEmpty() ? 1.0 : Math.min(1.0, p.roadCostFactor);
            this.boatsOn = p.boatCostFactor >= 1.0;
        }

        TravelPlan run(long now) {
            final int iGoal = Math.round((job.destX - job.originX) / (float) s);
            final int jGoal = Math.round((job.destZ - job.originZ) / (float) s);

            Node start = lattice(0, 0, false);
            start.g = 0;
            start.f = heuristic(start);
            open.add(start);

            Node bestTowardGoal = start;
            double bestH = dist(start.worldX, start.worldZ, job.destX, job.destZ);
            int expansions = 0;

            while (!open.isEmpty() && expansions < job.nodeBudget) {
                Node cur = open.poll();
                expansions++;

                double h = dist(cur.worldX, cur.worldZ, job.destX, job.destZ);
                if (h <= s * 0.75 || (cur.road < 0 && cur.i == iGoal && cur.j == jGoal)) {
                    return finish(job, now, cur, true);
                }
                if (h < bestH) {
                    bestH = h;
                    bestTowardGoal = cur;
                }
                if (cur.road < 0) expandLattice(cur);
                else expandRoad(cur);
            }
            // No path within budget: best-effort partial + straight tail to the real destination.
            return finish(job, now, bestTowardGoal, false);
        }

        private void expandLattice(Node cur) {
            for (int[] d : DIRS) {
                int ni = cur.i + d[0];
                int nj = cur.j + d[1];
                int nx = job.originX + ni * s;
                int nz = job.originZ + nj * s;
                if (nx < job.boxMinX || nx > job.boxMaxX || nz < job.boxMinZ || nz > job.boxMaxZ) continue;
                double segLen = (d[0] != 0 && d[1] != 0) ? s * 1.41421356 : s;
                double step = p.stepWeight * segLen;
                double distScale = segLen / 32.0; // terrain terms are per-block; normalize to a 32-block step

                // Sample the whole edge, not just its far node: a ridge, stream or mountain cell
                // between two lattice nodes is otherwise stepped over for free.
                int subSteps = Math.max(1, (int) Math.round(segLen / 16.0));
                int prevH = cur.height;
                double summedRise = 0.0;
                int maxAmp = 0;
                int maxWater = 0;
                int minWater = 255;
                boolean anyMountain = false;
                boolean anyEstimate = false;
                boolean walled = false;
                for (int t = 1; t <= subSteps; t++) {
                    double f = t / (double) subSteps;
                    int sx = cur.worldX + (int) Math.round((nx - cur.worldX) * f);
                    int sz = cur.worldZ + (int) Math.round((nz - cur.worldZ) * f);
                    if (walled(job, sx, sz)) {
                        walled = true;
                        break;
                    }
                    int amp = view.amplitude(sx, sz);
                    if (amp > maxAmp) maxAmp = amp;
                    int sw = view.water(sx, sz);
                    if (sw > maxWater) maxWater = sw;
                    if (sw < minWater) minWater = sw;
                    if (view.isMountain(sx, sz)) anyMountain = true;
                    if (view.source(sx, sz) == TerrainProfile.SOURCE_BIOME_EST) anyEstimate = true;
                    short sh = view.height(sx, sz);
                    if (sh != Short.MIN_VALUE && prevH != Short.MIN_VALUE) summedRise += Math.abs(sh - prevH);
                    if (sh != Short.MIN_VALUE) prevH = sh;
                }
                if (walled) continue;
                boolean boatable = boatsOn && minWater >= p.boatWater255;
                double rowCost = p.stepWeight * segLen * p.boatCostFactor;
                if (cur.boat) {
                    // Afloat: keep rowing over open water, anything else is stepping ashore.
                    if (boatable) {
                        relax(cur, lattice(ni, nj, true), rowCost, BOAT);
                        continue;
                    }
                } else if (boatable) {
                    relax(cur, lattice(ni, nj, true), p.boatEmbarkCost * p.stepWeight + rowCost, BOAT);
                }
                Node nb = lattice(ni, nj, false);
                double enter = TravelCostModel.edgeTerrainCost(
                        summedRise, maxAmp, maxWater, anyMountain, anyEstimate, distScale, p);
                double pit = TravelCostModel.pitExtra(nb.height, cur.height, ring(view, nx, nz, s), distScale, p);
                // Small cross-track penalty: nudges the route back onto the origin->dest line where
                // terrain cost is flat, without overriding a real terrain detour.
                double crossTrack = chordPerp(nx, nz, job) * p.stepWeight * 0.01;
                relax(cur, nb, step + enter + pit + crossTrack, WALK);
            }
            if (!cur.boat && !roads.isEmpty()) {
                roads.forEachNear(cur.worldX, cur.worldZ, s * 1.5, v -> {
                    if (walled(job, roads.xs[v], roads.zs[v])) return; // a road inside walls is joined at a gate
                    Node rn = road(v);
                    relax(cur, rn, segmentCost(cur.worldX, cur.worldZ, rn.worldX, rn.worldZ, job), WALK);
                });
            }
        }

        private void expandRoad(Node cur) {
            int v = cur.road;
            int[] along = {roads.prev[v], roads.next[v]};
            for (int w : along) {
                if (w < 0) continue;
                Node nb = road(w);
                double len = dist(cur.worldX, cur.worldZ, nb.worldX, nb.worldZ);
                relax(cur, nb, p.stepWeight * len * p.roadCostFactor, ROAD);
            }
            roads.forEachNear(cur.worldX, cur.worldZ, JUNCTION_BLOCKS, w -> {
                if (w == v || w == roads.prev[v] || w == roads.next[v]) return;
                Node nb = road(w);
                relax(cur, nb, segmentCost(cur.worldX, cur.worldZ, nb.worldX, nb.worldZ, job), WALK);
            });
            // Step off the road onto the lattice nodes around it.
            int i0 = Math.round((cur.worldX - job.originX) / (float) s);
            int j0 = Math.round((cur.worldZ - job.originZ) / (float) s);
            double reach = s * 1.5;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    int ni = i0 + di, nj = j0 + dj;
                    int nx = job.originX + ni * s, nz = job.originZ + nj * s;
                    if (nx < job.boxMinX || nx > job.boxMaxX || nz < job.boxMinZ || nz > job.boxMaxZ) continue;
                    if (dist(cur.worldX, cur.worldZ, nx, nz) > reach) continue;
                    if (walled(job, nx, nz)) continue;
                    relax(cur, lattice(ni, nj, false), segmentCost(cur.worldX, cur.worldZ, nx, nz, job), WALK);
                }
            }
        }

        private void relax(Node cur, Node nb, double edgeCost, byte via) {
            double tentativeG = cur.g + edgeCost;
            if (tentativeG < nb.g) {
                nb.g = tentativeG;
                nb.parent = cur;
                nb.via = via;
                nb.f = tentativeG + heuristic(nb);
                open.remove(nb); // no-op if absent
                open.add(nb);
            }
        }

        private Node lattice(int i, int j, boolean boat) {
            long k = key(i, j) | (boat ? (1L << 61) : 0L);
            Node n = best.get(k);
            if (n == null) {
                int wx = job.originX + i * s;
                int wz = job.originZ + j * s;
                n = new Node(i, j, -1, boat, wx, wz, view.height(wx, wz));
                n.g = Double.POSITIVE_INFINITY;
                best.put(k, n);
            }
            return n;
        }

        private Node road(int v) {
            long k = (1L << 62) | v;
            Node n = best.get(k);
            if (n == null) {
                int wx = roads.xs[v], wz = roads.zs[v];
                // The road's own surface height, not the coarse grid's: on a bridge that is the deck.
                n = new Node(0, 0, v, false, wx, wz, (short) roads.ys[v]);
                n.g = Double.POSITIVE_INFINITY;
                best.put(k, n);
            }
            return n;
        }

        private double heuristic(Node n) {
            return p.stepWeight * hScale * dist(n.worldX, n.worldZ, job.destX, job.destZ);
        }
    }

    // ------------------------------------------------------------------

    private static double dist(int x1, int z1, int x2, int z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Perpendicular distance from (x,z) to the infinite origin->dest line. */
    private static double chordPerp(int x, int z, TravelRouteJob job) {
        double ax = job.originX, az = job.originZ;
        double bx = job.destX, bz = job.destZ;
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1.0) return 0.0;
        double cross = (x - ax) * dz - (z - az) * dx;
        return Math.abs(cross) / Math.sqrt(len2);
    }

    private static short[] ring(TerrainGridView view, int x, int z, int s) {
        return new short[]{
                view.height(x + s, z), view.height(x - s, z), view.height(x, z + s), view.height(x, z - s),
                view.height(x + s, z + s), view.height(x + s, z - s), view.height(x - s, z + s), view.height(x - s, z - s)
        };
    }

    private static long key(int i, int j) {
        return ((long) (i & 0x1FFFFF)) | ((long) (j & 0x1FFFFF) << 21);
    }

    private static TravelPlan finish(TravelRouteJob job, long now, Node end, boolean reachedGoal) {
        ArrayList<Node> rev = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) rev.add(n);
        List<BlockPos> path = new ArrayList<>();
        List<Byte> kinds = new ArrayList<>();
        for (int k = rev.size() - 1; k >= 0; k--) {
            Node n = rev.get(k);
            int y = n.height == Short.MIN_VALUE ? 64 : n.height;
            if (!path.isEmpty()) kinds.add(n.via);
            path.add(new BlockPos(n.worldX, y, n.worldZ));
        }

        // Ensure the exact endpoints are present.
        int oy = job.gridView.height(job.originX, job.originZ);
        int dy = job.gridView.height(job.destX, job.destZ);
        BlockPos origin = new BlockPos(job.originX, oy == Short.MIN_VALUE ? 64 : oy, job.originZ);
        BlockPos dest = new BlockPos(job.destX, dy == Short.MIN_VALUE ? 64 : dy, job.destZ);
        if (!samXZ(path.get(0), origin)) {
            path.add(0, origin);
            kinds.add(0, WALK);
        }
        if (!samXZ(path.get(path.size() - 1), dest)) {
            path.add(dest);
            kinds.add(WALK);
        }

        demoteShortCrossings(path, kinds, job.params.boatMinCrossingBlocks);

        // Tidy each run separately: walked stretches get the any-angle pass, roads only a light
        // simplification, so a road keeps its real shape and its entry/exit points.
        List<BlockPos> out = new ArrayList<>();
        List<Byte> outKinds = new ArrayList<>();
        out.add(path.get(0));
        int a = 0;
        while (a < path.size() - 1) {
            byte kind = kinds.get(a);
            int b = a + 1;
            while (b < path.size() - 1 && kinds.get(b) == kind) b++;
            List<BlockPos> run = new ArrayList<>(path.subList(a, b + 1));
            List<BlockPos> tidy;
            if (kind == WALK) {
                // Repeated on its own output: each pass reaches further along an already-straightened run.
                tidy = run;
                for (int pass = 0; pass < 3; pass++) {
                    int before = tidy.size();
                    tidy = smooth(tidy, job);
                    if (tidy.size() == before) break;
                }
                tidy = simplify(tidy, job.params.simplifyBlocks);
            } else if (kind == BOAT) {
                tidy = smoothOverWater(run, job);
            } else {
                tidy = simplify(run, ROAD_SIMPLIFY_BLOCKS);
            }
            for (int k = 1; k < tidy.size(); k++) {
                out.add(tidy.get(k));
                outKinds.add(kind);
            }
            a = b;
        }
        byte[] k = new byte[outKinds.size()];
        boolean anyKind = false;
        for (int i = 0; i < k.length; i++) {
            k[i] = outKinds.get(i);
            anyKind |= k[i] != WALK;
        }
        return new TravelPlan(out, now, TravelPlan.QUALITY_COARSE, reachedGoal, anyKind ? k : null);
    }

    /** A boat run shorter than {@code minBlocks} is swum/walked instead: its segments become WALK. */
    static void demoteShortCrossings(List<BlockPos> path, List<Byte> kinds, double minBlocks) {
        int a = 0;
        while (a < kinds.size()) {
            if (kinds.get(a) != BOAT) {
                a++;
                continue;
            }
            int b = a;
            double len = 0.0;
            while (b < kinds.size() && kinds.get(b) == BOAT) {
                len += dist(path.get(b).getX(), path.get(b).getZ(), path.get(b + 1).getX(), path.get(b + 1).getZ());
                b++;
            }
            if (len < minBlocks) {
                for (int k = a; k < b; k++) kinds.set(k, WALK);
            }
            a = b;
        }
    }

    /** Straightens a boat run wherever the straight line stays over boatable water. */
    static List<BlockPos> smoothOverWater(List<BlockPos> run, TravelRouteJob job) {
        if (run.size() <= 2) return run;
        List<BlockPos> out = new ArrayList<>();
        out.add(run.get(0));
        int i = 0;
        while (i < run.size() - 1) {
            int best = i + 1;
            int maxK = Math.min(run.size() - 1, i + SMOOTH_LOOKAHEAD);
            for (int k = i + 2; k <= maxK; k++) {
                if (allWater(run.get(i), run.get(k), job)) best = k;
            }
            out.add(run.get(best));
            i = best;
        }
        return out;
    }

    private static boolean allWater(BlockPos a, BlockPos b, TravelRouteJob job) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        int pieces = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / PIECE_BLOCKS));
        for (int t = 1; t < pieces; t++) {
            double f = t / (double) pieces;
            int sx = a.getX() + (int) Math.round(dx * f);
            int sz = a.getZ() + (int) Math.round(dz * f);
            if (job.gridView.water(sx, sz) < job.params.boatWater255) return false;
        }
        return true;
    }

    // Same values as Itinerary.WALK / ROAD / BOAT.
    public static final byte WALK = 0;
    public static final byte ROAD = 1;
    public static final byte BOAT = 2;
    private static final double ROAD_SIMPLIFY_BLOCKS = 2.0;

    // Lattice vertices a shortcut may skip at once.
    private static final int SMOOTH_LOOKAHEAD = 24;
    // A shortcut is taken when it costs at most this much more than the lattice path it replaces.
    private static final double SMOOTH_TOLERANCE = 1.02;
    // Terrain is sampled every this many blocks along a segment (half a terrain-grid cell, so a
    // shortcut can't clip the corner of an obstacle cell between two samples).
    private static final double PIECE_BLOCKS = 8.0;

    /**
     * Any-angle pass: replaces runs of 45-degree lattice steps with straight lines wherever the
     * straight line costs no more terrain-wise, so routes bend where the terrain does, not where the
     * lattice does. Both sides are costed piecewise with the same terrain formula as the search.
     */
    static List<BlockPos> smooth(List<BlockPos> path, TravelRouteJob job) {
        if (path.size() <= 2) return path;
        List<BlockPos> out = new ArrayList<>();
        out.add(path.get(0));
        int i = 0;
        while (i < path.size() - 1) {
            int best = i + 1;
            double pathCost = 0.0;
            int maxK = Math.min(path.size() - 1, i + SMOOTH_LOOKAHEAD);
            for (int k = i + 1; k <= maxK; k++) {
                pathCost += segmentCost(path.get(k - 1), path.get(k), job);
                if (k == i + 1) continue;
                if (crossesWalls(path.get(i), path.get(k), job)) continue;
                if (segmentCost(path.get(i), path.get(k), job) <= pathCost * SMOOTH_TOLERANCE + 1e-6) best = k;
            }
            out.add(path.get(best));
            i = best;
        }
        return out;
    }

    // Around the leg's own ends, walls don't count: those are the gate points of the villages it leaves/enters.
    static boolean walled(TravelRouteJob job, double x, double z) {
        if (job.walls.isEmpty() || !job.walls.contains(x, z)) return false;
        double free = job.nodeSpacing * 2.0;
        return dist2(x, z, job.originX, job.originZ) > free * free && dist2(x, z, job.destX, job.destZ) > free * free;
    }

    private static boolean crossesWalls(BlockPos a, BlockPos b, TravelRouteJob job) {
        if (job.walls.isEmpty()) return false;
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        int pieces = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / PIECE_BLOCKS));
        for (int t = 1; t < pieces; t++) {
            double f = t / (double) pieces;
            if (walled(job, a.getX() + dx * f, a.getZ() + dz * f)) return true;
        }
        return false;
    }

    private static double dist2(double x1, double z1, double x2, double z2) {
        return (x1 - x2) * (x1 - x2) + (z1 - z2) * (z1 - z2);
    }

    private static double segmentCost(BlockPos a, BlockPos b, TravelRouteJob job) {
        return segmentCost(a.getX(), a.getZ(), b.getX(), b.getZ(), job);
    }

    /** Walking cost of a straight segment: distance + terrain, sampled every PIECE_BLOCKS. */
    static double segmentCost(int ax, int az, int bx, int bz, TravelRouteJob job) {
        TerrainGridView view = job.gridView;
        RouteCostParams p = job.params;
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return 0.0;
        int pieces = Math.max(1, (int) Math.ceil(len / PIECE_BLOCKS));
        double pieceLen = len / pieces;
        double cost = p.stepWeight * len;
        int prevH = view.height(ax, az);
        for (int t = 1; t <= pieces; t++) {
            double f = t / (double) pieces;
            int sx = ax + (int) Math.round(dx * f);
            int sz = az + (int) Math.round(dz * f);
            short h = view.height(sx, sz);
            double rise = (h != Short.MIN_VALUE && prevH != Short.MIN_VALUE) ? Math.abs(h - prevH) : 0.0;
            double distScale = pieceLen / 32.0;
            cost += TravelCostModel.edgeTerrainCost(rise, view.amplitude(sx, sz), view.water(sx, sz),
                    view.isMountain(sx, sz), view.source(sx, sz) == TerrainProfile.SOURCE_BIOME_EST,
                    distScale, p);
            cost += TravelCostModel.pitExtra(h, prevH, ring(view, sx, sz, job.nodeSpacing), distScale, p);
            if (h != Short.MIN_VALUE) prevH = h;
        }
        return cost;
    }

    private static boolean samXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    /** Collinear-point removal, then Douglas-Peucker with a coarse epsilon. */
    static List<BlockPos> simplify(List<BlockPos> pts, double epsilon) {
        if (pts.size() <= 2) return pts;
        List<BlockPos> noColinear = new ArrayList<>();
        noColinear.add(pts.get(0));
        for (int k = 1; k < pts.size() - 1; k++) {
            BlockPos a = noColinear.get(noColinear.size() - 1);
            BlockPos b = pts.get(k);
            BlockPos c = pts.get(k + 1);
            long cross = (long) (b.getX() - a.getX()) * (c.getZ() - a.getZ())
                    - (long) (b.getZ() - a.getZ()) * (c.getX() - a.getX());
            if (cross != 0) noColinear.add(b);
        }
        noColinear.add(pts.get(pts.size() - 1));
        if (noColinear.size() <= 2) return noColinear;

        boolean[] keep = new boolean[noColinear.size()];
        keep[0] = true;
        keep[noColinear.size() - 1] = true;
        dp(noColinear, 0, noColinear.size() - 1, epsilon, keep);
        List<BlockPos> out = new ArrayList<>();
        for (int k = 0; k < noColinear.size(); k++) if (keep[k]) out.add(noColinear.get(k));
        return out;
    }

    private static void dp(List<BlockPos> pts, int lo, int hi, double eps, boolean[] keep) {
        if (hi <= lo + 1) return;
        double maxD = -1;
        int idx = -1;
        BlockPos a = pts.get(lo);
        BlockPos b = pts.get(hi);
        for (int k = lo + 1; k < hi; k++) {
            double d = perpDist(pts.get(k), a, b);
            if (d > maxD) {
                maxD = d;
                idx = k;
            }
        }
        if (maxD > eps && idx > 0) {
            keep[idx] = true;
            dp(pts, lo, idx, eps, keep);
            dp(pts, idx, hi, eps, keep);
        }
    }

    private static double perpDist(BlockPos pt, BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len2 = dx * dx + dz * dz;
        if (len2 == 0) {
            double ex = pt.getX() - a.getX();
            double ez = pt.getZ() - a.getZ();
            return Math.sqrt(ex * ex + ez * ez);
        }
        double t = ((pt.getX() - a.getX()) * dx + (pt.getZ() - a.getZ()) * dz) / len2;
        double projX = a.getX() + t * dx;
        double projZ = a.getZ() + t * dz;
        double ex = pt.getX() - projX;
        double ez = pt.getZ() - projZ;
        return Math.sqrt(ex * ex + ez * ez);
    }
}
