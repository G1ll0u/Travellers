package com.jubitus.travellers.follow;

import com.jubitus.travellers.follow.formations.FormationGeometry;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A group on a road walks in file along the road itself: each follower stands a few steps behind the one ahead,
 * measured along the route, so curves and bridges are followed rather than cut. Distances are the formation's own
 * (escorts first, just behind the leader, then the rest from the follow distance at the row spacing). Two abreast
 * where the road is wide enough, one by one on a bridge or a narrow stretch; the file narrows a little before it gets
 * there. Widths are measured on the spot, in loaded chunks only, once per stretch of road. One per group leader.
 */
public final class GroupRoadFile {

    /** The formation's distances, as FormationGeometry places them off the road. */
    static final class Shape {
        final double firstGap;
        final double rowSpacing;
        final double escortRowGap;

        private Shape(double firstGap, double rowSpacing, double escortRowGap) {
            this.firstGap = firstGap;
            this.rowSpacing = rowSpacing;
            this.escortRowGap = escortRowGap;
        }

        static Shape of(double baseFollowDistance, WorkerFormationSettings settings, double bodyScale) {
            return new Shape(FormationGeometry.baseDistance(baseFollowDistance, settings),
                    FormationGeometry.rowSpacing(settings, bodyScale), FormationGeometry.escortRowGap(bodyScale));
        }
    }
    /** The file forms this far before the road starts, so the group is lined up when it gets there. */
    private static final double ENTER_AHEAD = 6.0D;
    /** A narrow stretch this far ahead already makes the file single. */
    private static final double NARROW_AHEAD = 6.0D;
    private static final double LANE_OFFSET = 0.75D;
    /** Width is sampled every this many blocks of route. */
    private static final double SAMPLE = 4.0D;
    private static final int MIN_TWO_LANE_WIDTH = 3;
    private static final int MAX_SIDE_SCAN = 2;
    private static final byte UNMEASURED = 0, ONE_LANE = 1, TWO_LANES = 2;

    private final List<UUID> order = new ArrayList<>();
    // The order actually laid out: escorts first, then the rest, each in file order.
    private final List<UUID> packed = new ArrayList<>();
    private boolean active;
    private Itinerary sampled;
    private byte[] lanes = new byte[0];

    private int count;
    private double[] xs = new double[0], zs = new double[0], behind = new double[0];

    boolean isActive() {
        return active;
    }

    /** Rank of a follower in the file (joins at the back), or -1 when the file is not active. */
    int rankOf(UUID follower) {
        if (!active) return -1;
        int r = packed.indexOf(follower);
        return r >= 0 && r < count ? r : -1;
    }

    double x(int rank) {
        return xs[rank];
    }

    double z(int rank) {
        return zs[rank];
    }

    /** How far behind the base point this rank stands, along the route. */
    double behind(int rank) {
        return behind[rank];
    }

    /**
     * Once per tick per leader: whether the group files (a road under or just ahead of it) and where each rank
     * stands behind {@code baseAlong} on {@code it}.
     */
    void update(WorldServer world, Entity leader, Itinerary it, double baseAlong, List<UUID> members,
                Set<UUID> escorts, Shape shape) {
        double tail = shape.firstGap + (members.size() + 1) * shape.rowSpacing;
        boolean wasActive = active;
        active = it != null && roadWithin(it, baseAlong - tail, baseAlong + ENTER_AHEAD);
        if (!active) {
            order.clear();
            packed.clear();
            return;
        }
        if (it != sampled) {
            sampled = it;
            lanes = new byte[(int) (it.totalLength() / SAMPLE) + 2];
        }
        if (!wasActive) {
            // Entering: whoever is nearest the leader leads the file, so nobody has to cross the others.
            order.clear();
            order.addAll(members);
            order.sort(Comparator.comparingDouble(id -> distanceTo(world, id, leader)));
        } else {
            order.retainAll(members);
            for (UUID id : members) if (!order.contains(id)) order.add(id);
        }
        packed.clear();
        for (UUID id : order) if (escorts.contains(id)) packed.add(id);
        int escortCount = packed.size();
        for (UUID id : order) if (!escorts.contains(id)) packed.add(id);
        pack(world, it, baseAlong, escortCount, shape);
    }

    private void pack(WorldServer world, Itinerary it, double baseAlong, int escortCount, Shape shape) {
        count = packed.size();
        if (xs.length < count) {
            xs = new double[count];
            zs = new double[count];
            behind = new double[count];
        }
        double d = packRows(world, it, baseAlong, 0, escortCount, FormationGeometry.ESCORT_BACK, shape.escortRowGap);
        double first = escortCount > 0 ? Math.max(shape.firstGap, d - shape.escortRowGap + shape.rowSpacing)
                : shape.firstGap;
        packRows(world, it, baseAlong, escortCount, count, first, shape.rowSpacing);
    }

    /** Lays ranks {@code from..to-1} in rows from {@code d} behind, {@code gap} apart; returns the next row's distance. */
    private double packRows(WorldServer world, Itinerary it, double baseAlong, int from, int to, double d, double gap) {
        int r = from;
        while (r < to) {
            double a = baseAlong - d;
            int inRow = Math.min(lanesAt(world, it, a), to - r);
            for (int lane = 0; lane < inRow; lane++, r++) {
                double side = inRow == 1 ? 0.0D : (lane == 0 ? -LANE_OFFSET : LANE_OFFSET);
                place(it, a, side, r);
                behind[r] = d;
            }
            d += gap;
        }
        return d;
    }

    private void place(Itinerary it, double a, double side, int rank) {
        pointAt(it, a, side, POINT);
        xs[rank] = POINT[0];
        zs[rank] = POINT[1];
    }

    private static final double[] POINT = new double[2];

    /** Route point {@code a} (extended straight back before the start), shifted {@code side} to the right. */
    private static void pointAt(Itinerary it, double a, double side, double[] out) {
        int k = segmentAt(it, Math.max(0.0D, a));
        double sx = it.x(k + 1) - it.x(k), sz = it.z(k + 1) - it.z(k);
        double len = Math.sqrt(sx * sx + sz * sz);
        double tx = len < 1e-6 ? 1.0D : sx / len, tz = len < 1e-6 ? 0.0D : sz / len;
        double px, pz;
        if (a < 0.0D) {
            px = it.x(0) + 0.5D + tx * a;
            pz = it.z(0) + 0.5D + tz * a;
        } else {
            double t = len < 1e-6 ? 0.0D : Math.min(1.0D, (a - it.cum(k)) / (it.cum(k + 1) - it.cum(k)));
            px = it.x(k) + 0.5D + sx * t;
            pz = it.z(k) + 0.5D + sz * t;
        }
        out[0] = px - tz * side;
        out[1] = pz + tx * side;
    }

    /** Lanes available at {@code a}, narrowed by any narrow sample just ahead. */
    private int lanesAt(WorldServer world, Itinerary it, double a) {
        int from = Math.max(0, (int) (a / SAMPLE));
        int to = Math.min(lanes.length - 1, (int) ((a + NARROW_AHEAD) / SAMPLE));
        for (int i = from; i <= to; i++) {
            // An unloaded sample is not stored: measured for real once its chunk loads.
            if (lanes[i] == UNMEASURED) lanes[i] = measure(world, it, (i + 0.5D) * SAMPLE);
            if (lanes[i] == ONE_LANE) return 1;
        }
        return 2;
    }

    private byte measure(WorldServer world, Itinerary it, double a) {
        a = Math.min(a, it.totalLength());
        int k = segmentAt(it, a);
        double sx = it.x(k + 1) - it.x(k), sz = it.z(k + 1) - it.z(k);
        double len = Math.sqrt(sx * sx + sz * sz);
        if (len < 1e-6) return TWO_LANES;
        double t = Math.min(1.0D, (a - it.cum(k)) / (it.cum(k + 1) - it.cum(k)));
        double cx = it.x(k) + 0.5D + sx * t, cz = it.z(k) + 0.5D + sz * t;
        int refY = (int) Math.round(it.y(k) + (it.y(k + 1) - it.y(k)) * t);
        if (!LoadedChunks.isLoaded(world, cx, cz)) return UNMEASURED;
        int y = ColumnGround.nearestStandable(world, (int) Math.floor(cx), (int) Math.floor(cz), refY, 3);
        // Unknown ground (unloaded, odd spot): never constrain the file on a guess.
        if (y == ColumnGround.NONE) return TWO_LANES;
        BlockPos center = new BlockPos(Math.floor(cx), y, Math.floor(cz));
        if (isBridge(world, center)) return ONE_LANE;
        double px = -sz / len, pz = sx / len;
        int width = 1 + side(world, cx, cz, px, pz, y) + side(world, cx, cz, -px, -pz, y);
        return width >= MIN_TWO_LANE_WIDTH ? TWO_LANES : ONE_LANE;
    }

    /** Standable columns in a row from the centre toward one side, each within a block of the last. */
    private static int side(WorldServer world, double cx, double cz, double dx, double dz, int y) {
        int n = 0;
        for (int step = 1; step <= MAX_SIDE_SCAN; step++) {
            int sy = ColumnGround.nearestStandable(world, (int) Math.floor(cx + dx * step),
                    (int) Math.floor(cz + dz * step), y, 1);
            if (sy == ColumnGround.NONE) break;
            y = sy;
            n++;
        }
        return n;
    }

    /** Nothing solid under the deck: open air or water below the floor block. */
    private static boolean isBridge(WorldServer world, BlockPos feet) {
        return !world.getBlockState(feet.down(2)).getMaterial().blocksMovement()
                && !world.getBlockState(feet.down(3)).getMaterial().blocksMovement();
    }

    private static boolean roadWithin(Itinerary it, double from, double to) {
        int last = it.size() - 2;
        for (int k = segmentAt(it, Math.max(0.0D, from)); k <= last && it.cum(k) <= to; k++) {
            if (it.kind(k) == Itinerary.ROAD) return true;
        }
        return false;
    }

    /** Segment holding route distance {@code a} (binary search: routes run to hundreds of segments). */
    private static int segmentAt(Itinerary it, double a) {
        int lo = 0, hi = it.size() - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (it.cum(mid) <= a) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private static double distanceTo(WorldServer world, UUID id, Entity leader) {
        Entity e = world.getEntityFromUuid(id);
        return e == null ? Double.MAX_VALUE : e.getDistanceSq(leader);
    }
}
