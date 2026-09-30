package com.jubitus.travellers.world.pathing.route;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable road network slice handed to a route-planner worker: every road vertex in the search
 * box (resampled so no two consecutive vertices are more than {@code maxGap} apart), linked to its
 * neighbours along the same road, plus a spatial hash for "which road vertices are near here".
 */
public final class RoadLayer {

    public static final RoadLayer EMPTY = new RoadLayer(new int[0], new int[0], new int[0], new int[0], new int[0], 16);

    final int[] xs;
    final int[] ys;
    final int[] zs;
    // Index of the previous / next vertex along the same road, -1 at a road end.
    final int[] prev;
    final int[] next;
    private final int cell;
    private final Map<Long, int[]> buckets = new HashMap<>();

    private RoadLayer(int[] xs, int[] ys, int[] zs, int[] prev, int[] next, int cell) {
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.prev = prev;
        this.next = next;
        this.cell = Math.max(4, cell);
        Map<Long, List<Integer>> tmp = new HashMap<>();
        for (int i = 0; i < xs.length; i++) {
            tmp.computeIfAbsent(key(Math.floorDiv(xs[i], this.cell), Math.floorDiv(zs[i], this.cell)),
                    k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<Long, List<Integer>> e : tmp.entrySet()) {
            int[] a = new int[e.getValue().size()];
            for (int k = 0; k < a.length; k++) a[k] = e.getValue().get(k);
            buckets.put(e.getKey(), a);
        }
    }

    /** Server thread: builds the layer from raw road polylines (road surface points, any density). */
    public static RoadLayer build(List<List<BlockPos>> polylines, double simplifyEps, int maxGap) {
        if (polylines == null || polylines.isEmpty()) return EMPTY;
        List<int[]> pts = new ArrayList<>();
        List<int[]> links = new ArrayList<>();
        for (List<BlockPos> raw : polylines) {
            if (raw == null || raw.size() < 2) continue;
            List<BlockPos> line = resample(CoarseRouteSearch.simplify(raw, simplifyEps), maxGap);
            int base = pts.size();
            for (int k = 0; k < line.size(); k++) {
                BlockPos p = line.get(k);
                pts.add(new int[]{p.getX(), p.getY(), p.getZ()});
                links.add(new int[]{k == 0 ? -1 : base + k - 1, k == line.size() - 1 ? -1 : base + k + 1});
            }
        }
        int n = pts.size();
        int[] xs = new int[n], ys = new int[n], zs = new int[n], pv = new int[n], nx = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i)[0];
            ys[i] = pts.get(i)[1];
            zs[i] = pts.get(i)[2];
            pv[i] = links.get(i)[0];
            nx[i] = links.get(i)[1];
        }
        return new RoadLayer(xs, ys, zs, pv, nx, maxGap);
    }

    private static List<BlockPos> resample(List<BlockPos> line, int maxGap) {
        List<BlockPos> out = new ArrayList<>();
        out.add(line.get(0));
        for (int k = 1; k < line.size(); k++) {
            BlockPos a = line.get(k - 1), b = line.get(k);
            double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
            int pieces = (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / Math.max(1, maxGap));
            for (int t = 1; t < pieces; t++) {
                double f = t / (double) pieces;
                out.add(new BlockPos((int) Math.round(a.getX() + dx * f),
                        (int) Math.round(a.getY() + (b.getY() - a.getY()) * f),
                        (int) Math.round(a.getZ() + dz * f)));
            }
            out.add(b);
        }
        return out;
    }

    public boolean isEmpty() {
        return xs.length == 0;
    }

    public int size() {
        return xs.length;
    }

    /** Calls {@code out} with every vertex within {@code radius} (XZ) of the point. */
    void forEachNear(int x, int z, double radius, java.util.function.IntConsumer out) {
        int r = (int) Math.ceil(radius / cell);
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        double r2 = radius * radius;
        for (int i = cx - r; i <= cx + r; i++) {
            for (int j = cz - r; j <= cz + r; j++) {
                int[] b = buckets.get(key(i, j));
                if (b == null) continue;
                for (int v : b) {
                    double dx = xs[v] - x, dz = zs[v] - z;
                    if (dx * dx + dz * dz <= r2) out.accept(v);
                }
            }
        }
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }
}
