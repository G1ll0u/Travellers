package com.jubitus.travellers.world.pathing.route;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A coarse route polyline plus a little metadata. Transient on a loaded entity; on a
 * {@code VirtualTravellerRecord} the polyline and index <em>are</em> the existing
 * {@code travelRoute} / {@code routeIndex} fields, with {@code computedTick} / {@code quality}
 * persisted alongside.
 */
public final class TravelPlan {

    public static final byte QUALITY_STRAIGHT = 0;
    public static final byte QUALITY_COARSE = 1;
    public static final byte QUALITY_REAL = 2;

    public final List<BlockPos> polyline;
    public final long computedTick;
    public final byte quality;
    /** Whether the search actually reached the destination (vs. a best-effort partial + straight tail). */
    public final boolean reachedGoal;
    // One Itinerary kind (WALK/ROAD) per segment; null = all WALK.
    @javax.annotation.Nullable
    public final byte[] kinds;

    public TravelPlan(List<BlockPos> polyline, long computedTick, byte quality, boolean reachedGoal) {
        this(polyline, computedTick, quality, reachedGoal, null);
    }

    public TravelPlan(List<BlockPos> polyline, long computedTick, byte quality, boolean reachedGoal,
                      @javax.annotation.Nullable byte[] kinds) {
        this.polyline = polyline;
        this.computedTick = computedTick;
        this.quality = quality;
        this.reachedGoal = reachedGoal;
        this.kinds = kinds;
    }

    /** A degenerate straight-line "plan" from one point to another. */
    public static TravelPlan straight(BlockPos from, BlockPos to, long tick) {
        List<BlockPos> pts = new ArrayList<>(2);
        if (from != null) pts.add(from);
        pts.add(to);
        return new TravelPlan(pts, tick, QUALITY_STRAIGHT, true);
    }

    public List<BlockPos> polylineCopy() {
        return Collections.unmodifiableList(new ArrayList<>(polyline));
    }

    public static String qualityName(byte q) {
        switch (q) {
            case QUALITY_REAL:
                return "REAL";
            case QUALITY_COARSE:
                return "COARSE";
            default:
                return "STRAIGHT";
        }
    }
}
