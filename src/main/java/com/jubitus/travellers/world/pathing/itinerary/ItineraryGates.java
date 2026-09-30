package com.jubitus.travellers.world.pathing.itinerary;

import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Pure geometry of walled villages for itineraries: which gate a leg uses to leave or enter a
 * wall ring, and the inner / gate / outer vertices that thread it. No world access, unit-tested.
 */
public final class ItineraryGates {

    private ItineraryGates() {
    }

    // A point this far past the ring radius still counts as inside the walls.
    public static final double INSIDE_MARGIN = 3.0;

    /** A village's wall ring: centre, radius and gate positions. */
    public static final class Ring {
        public final int cx;
        public final int cz;
        public final double radius;
        public final List<BlockPos> gates;

        public Ring(int cx, int cz, double radius, List<BlockPos> gates) {
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.gates = gates;
        }

        public boolean isInside(double x, double z, double margin) {
            double dx = x - cx, dz = z - cz;
            double r = radius + margin;
            return dx * dx + dz * dz <= r * r;
        }
    }

    public static long gateKey(BlockPos g) {
        return ((long) g.getX() << 32) ^ (g.getZ() & 0xFFFFFFFFL);
    }

    /** The gate with the least detour from {@code from} to {@code to}, skipping excluded ones. */
    @Nullable
    public static BlockPos pickGate(Ring r, BlockPos from, BlockPos to, Set<Long> excluded) {
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (BlockPos g : r.gates) {
            if (excluded.contains(gateKey(g))) continue;
            double cost = dist(from, g) + dist(g, to);
            if (cost < bestCost) {
                bestCost = cost;
                best = g;
            }
        }
        return best;
    }

    /**
     * Vertices threading {@code gate} radially: inner, gate, outer when leaving; outer, gate, inner
     * when entering. {@code approach} is how far each side point sits from the gate.
     */
    public static List<BlockPos> through(Ring r, BlockPos gate, boolean leaving, int approach) {
        double dx = gate.getX() - r.cx, dz = gate.getZ() - r.cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return Collections.singletonList(gate);
        dx /= len;
        dz /= len;
        BlockPos inner = new BlockPos((int) Math.round(gate.getX() - dx * approach), gate.getY(),
                (int) Math.round(gate.getZ() - dz * approach));
        BlockPos outer = new BlockPos((int) Math.round(gate.getX() + dx * approach), gate.getY(),
                (int) Math.round(gate.getZ() + dz * approach));
        return leaving ? Arrays.asList(inner, gate, outer) : Arrays.asList(outer, gate, inner);
    }

    /** Leaving vertices when {@code origin} is inside the ring and {@code dest} is not; else empty. */
    public static List<BlockPos> exitVia(@Nullable Ring r, BlockPos origin, BlockPos dest, int approach,
                                         Set<Long> excluded) {
        if (r == null || !r.isInside(origin.getX(), origin.getZ(), INSIDE_MARGIN)
                || r.isInside(dest.getX(), dest.getZ(), INSIDE_MARGIN)) {
            return new ArrayList<>();
        }
        BlockPos g = pickGate(r, origin, dest, excluded);
        return g == null ? new ArrayList<>() : new ArrayList<>(through(r, g, true, approach));
    }

    /** Entering vertices when {@code dest} is inside the ring and {@code origin} is not; else empty. */
    public static List<BlockPos> enterVia(@Nullable Ring r, BlockPos origin, BlockPos dest, int approach,
                                          Set<Long> excluded) {
        if (r == null || !r.isInside(dest.getX(), dest.getZ(), INSIDE_MARGIN)
                || r.isInside(origin.getX(), origin.getZ(), INSIDE_MARGIN)) {
            return new ArrayList<>();
        }
        BlockPos g = pickGate(r, origin, dest, excluded);
        return g == null ? new ArrayList<>() : new ArrayList<>(through(r, g, false, approach));
    }

    private static double dist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
