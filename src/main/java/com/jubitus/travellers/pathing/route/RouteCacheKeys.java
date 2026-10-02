package com.jubitus.travellers.pathing.route;

import net.minecraft.util.math.BlockPos;

/**
 * The one formula for bucketing a coarse-route request into a {@link TravelRoutePlanner} cache key.
 * Used by both the virtual side ({@code VirtualTravellerManager}'s per-bridge requests) and the
 * loaded side ({@code ItineraryPlanner} for a loaded owner) - it has to stay byte-for-byte identical between the two,
 * or a loaded traveller and a virtual traveller planning the same leg silently stop sharing a cache
 * entry instead of failing loudly.
 */
public final class RouteCacheKeys {

    private RouteCacheKeys() {
    }

    /** Cache key bucketed to 128-block cells so nearby units on the same leg share one planned route. */
    public static String bucketed(int dim, BlockPos start, BlockPos end, boolean mount) {
        return dim + "|" + (start.getX() >> 7) + "," + (start.getZ() >> 7)
                + ">" + (end.getX() >> 7) + "," + (end.getZ() >> 7) + (mount ? "|m" : "");
    }
}
