package com.jubitus.travellers.pathing.itinerary;

import com.jubitus.travellers.pathing.route.TravelPlan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure assembly of a leg's first itinerary: origin, every MPB road leg (simplified), destination,
 * with straight WALK bridges in the gaps. Each bridge is later replaced by a coarse terrain polyline
 * (see {@link ItineraryPlanner}). No world access, unit-tested.
 */
public final class ItineraryAssembly {

    private ItineraryAssembly() {
    }

    /** One straight gap a coarse terrain search should replace with a real polyline. */
    public static final class Bridge {
        public final BlockPos start;
        public final BlockPos end;

        public Bridge(BlockPos start, BlockPos end) {
            this.start = start;
            this.end = end;
        }
    }

    public static final class Seed {
        public final Itinerary itinerary;
        public final List<Bridge> bridges;

        Seed(Itinerary itinerary, List<Bridge> bridges) {
            this.itinerary = itinerary;
            this.bridges = bridges;
        }
    }

    /**
     * @param maxBridgeLen longer bridges are split into equal pieces (<= 0 disables)
     * @param simplifyEps  Douglas-Peucker tolerance for road legs, in blocks (<= 0 keeps every point)
     */
    public static Seed assemble(BlockPos origin, List<List<BlockPos>> roadLegs, BlockPos dest,
                                int maxBridgeLen, double simplifyEps) {
        return assemble(origin, null, roadLegs, null, dest, maxBridgeLen, simplifyEps);
    }

    /**
     * Same, threading the gates of the walled villages the leg leaves and enters: {@code exitVia} /
     * {@code enterVia} are {@link ItineraryGates#through} vertices (inner/gate/outer), laid as fixed
     * segments; only the stretch between the two outer points is bridged.
     */
    public static Seed assemble(BlockPos origin, @javax.annotation.Nullable List<BlockPos> exitVia,
                                List<List<BlockPos>> roadLegs, @javax.annotation.Nullable List<BlockPos> enterVia,
                                BlockPos dest, int maxBridgeLen, double simplifyEps) {
        List<BlockPos> pts = new ArrayList<>();
        List<Byte> kinds = new ArrayList<>();
        List<Bridge> bridges = new ArrayList<>();

        pts.add(origin);
        boolean anyRoad = false;
        boolean anyGate = false;
        if (exitVia != null && !exitVia.isEmpty()) {
            for (int i = 0; i < exitVia.size(); i++) {
                pts.add(exitVia.get(i));
                kinds.add(i == 0 ? Itinerary.WALK : Itinerary.GATE);
            }
            anyGate = true;
        }
        BlockPos cursor = pts.get(pts.size() - 1);

        if (roadLegs != null) {
            for (List<BlockPos> rawLeg : roadLegs) {
                if (rawLeg == null || rawLeg.isEmpty()) continue;
                List<BlockPos> leg = simplify(rawLeg, simplifyEps);
                BlockPos legStart = leg.get(0);
                if (!sameXZ(cursor, legStart)) {
                    addBridge(pts, kinds, bridges, cursor, legStart, maxBridgeLen);
                }
                for (int i = 1; i < leg.size(); i++) {
                    if (sameXZ(pts.get(pts.size() - 1), leg.get(i))) continue;
                    pts.add(leg.get(i));
                    kinds.add(Itinerary.ROAD);
                    anyRoad = true;
                }
                cursor = pts.get(pts.size() - 1);
            }
        }

        if (enterVia != null && !enterVia.isEmpty()) {
            if (!sameXZ(cursor, enterVia.get(0))) addBridge(pts, kinds, bridges, cursor, enterVia.get(0), maxBridgeLen);
            for (int i = 1; i < enterVia.size(); i++) {
                pts.add(enterVia.get(i));
                kinds.add(Itinerary.GATE);
            }
            pts.add(dest);
            kinds.add(Itinerary.WALK);
            anyGate = true;
        } else if (!sameXZ(cursor, dest)) {
            addBridge(pts, kinds, bridges, cursor, dest, maxBridgeLen);
        }
        if (pts.size() == 1) {
            pts.add(dest);
            kinds.add(Itinerary.WALK);
        }

        int n = pts.size();
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i).getX();
            ys[i] = pts.get(i).getY();
            zs[i] = pts.get(i).getZ();
        }
        byte[] k = new byte[n - 1];
        for (int i = 0; i < n - 1; i++) k[i] = kinds.get(i);
        String source = (anyRoad ? "roads+bridges" : "straight") + (anyGate ? "+gates" : "");
        return new Seed(new Itinerary(xs, ys, zs, k, TravelPlan.QUALITY_STRAIGHT, source), bridges);
    }

    private static void addBridge(List<BlockPos> pts, List<Byte> kinds, List<Bridge> bridges,
                                  BlockPos from, BlockPos to, int maxLen) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        int pieces = (maxLen > 0 && d > maxLen) ? (int) Math.ceil(d / maxLen) : 1;
        BlockPos prev = from;
        for (int k = 1; k <= pieces; k++) {
            BlockPos next;
            if (k == pieces) {
                next = to;
            } else {
                double t = k / (double) pieces;
                next = new BlockPos(
                        (int) Math.round(from.getX() + dx * t),
                        (int) Math.round(from.getY() + (to.getY() - from.getY()) * t),
                        (int) Math.round(from.getZ() + dz * t));
            }
            pts.add(next);
            kinds.add(Itinerary.WALK);
            bridges.add(new Bridge(prev, next));
            prev = next;
        }
    }

    /** Douglas-Peucker in XZ, endpoints always kept. */
    static List<BlockPos> simplify(List<BlockPos> pts, double eps) {
        if (eps <= 0.0 || pts.size() < 3) return pts;
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        simplifyRange(pts, 0, pts.size() - 1, eps * eps, keep);
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    private static void simplifyRange(List<BlockPos> pts, int a, int b, double epsSq, boolean[] keep) {
        // Iterative stack instead of recursion: road legs can be several hundred points long.
        int[] stack = new int[pts.size() * 2];
        int sp = 0;
        stack[sp++] = a;
        stack[sp++] = b;
        while (sp > 0) {
            int hi = stack[--sp];
            int lo = stack[--sp];
            if (hi - lo < 2) continue;
            double ax = pts.get(lo).getX(), az = pts.get(lo).getZ();
            double sx = pts.get(hi).getX() - ax, sz = pts.get(hi).getZ() - az;
            double len2 = sx * sx + sz * sz;
            int worst = -1;
            double worstD = epsSq;
            for (int i = lo + 1; i < hi; i++) {
                double px = pts.get(i).getX() - ax, pz = pts.get(i).getZ() - az;
                double d;
                if (len2 < 1e-9) {
                    d = px * px + pz * pz;
                } else {
                    double t = Math.max(0.0, Math.min(1.0, (px * sx + pz * sz) / len2));
                    double qx = px - sx * t, qz = pz - sz * t;
                    d = qx * qx + qz * qz;
                }
                if (d > worstD) {
                    worstD = d;
                    worst = i;
                }
            }
            if (worst < 0) continue;
            keep[worst] = true;
            stack[sp++] = lo;
            stack[sp++] = worst;
            stack[sp++] = worst;
            stack[sp++] = hi;
        }
    }

    static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }
}
