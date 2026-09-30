package com.jubitus.travellers.world.pathing;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public final class MPBIntegration {

    public static final boolean PRESENT = detect();

    private static Class<?> apiClass;
    private static Method methodIsOnRoad;
    private static Method methodFindNextWaypoint;
    private static Method methodFindRoadSpawnPos;
    private static Method methodFindNearestRoadPos;
    private static Method methodFindRoadRoute;
    private static Method methodFindRoadRouteLegs;
    private static Method methodGetConnectedVillages;
    private static Method methodGetRoadPolylinesInBox;

    /** Fallback leg split: consecutive waypoints farther apart than this are a village crossing. */
    private static final int LEG_GAP_THRESHOLD_SQ = 40 * 40;

    private MPBIntegration() {}

    private static boolean detect() {
        try {
            apiClass = Class.forName("com.jubitus.ultreia.api.MPBRoadAPI");
            methodIsOnRoad = apiClass.getMethod("isOnRoad", WorldServer.class, int.class, int.class);
            methodFindNextWaypoint = apiClass.getMethod("findNextRoadWaypointToward",
                    WorldServer.class, int.class, int.class, int.class, int.class, int.class, int.class);
            methodFindRoadSpawnPos = apiClass.getMethod("findRoadSpawnPos", WorldServer.class, int.class, int.class, int.class);
            methodFindNearestRoadPos = apiClass.getMethod("findNearestRoadPos", WorldServer.class, int.class, int.class, int.class);
            methodFindRoadRoute = apiClass.getMethod("findRoadRoute",
                    WorldServer.class, int.class, int.class, int.class, int.class, int.class);
            methodGetConnectedVillages = apiClass.getMethod("getConnectedVillages", WorldServer.class);
            // Optional (newer MPB only); fallback splits the flat route by gap distance
            try {
                methodFindRoadRouteLegs = apiClass.getMethod("findRoadRouteLegs",
                        WorldServer.class, int.class, int.class, int.class, int.class, int.class);
            } catch (NoSuchMethodException e) {
                methodFindRoadRouteLegs = null;
            }
            // Optional (newer Ultreia only): roads inside a box, for the route planner's road layer.
            try {
                methodGetRoadPolylinesInBox = apiClass.getMethod("getRoadPolylinesInBox",
                        WorldServer.class, int.class, int.class, int.class, int.class);
            } catch (NoSuchMethodException e) {
                methodGetRoadPolylinesInBox = null;
            }
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    public static boolean isOnRoad(WorldServer world, int x, int z) {
        if (!PRESENT) return false;
        try {
            return (Boolean) methodIsOnRoad.invoke(null, world, x, z);
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    public static BlockPos findNextRoadWaypoint(
            WorldServer world, int fromX, int fromZ, int toX, int toZ,
            int stepBlocks, int searchRadius) {
        if (!PRESENT) return null;
        try {
            return (BlockPos) methodFindNextWaypoint.invoke(null, world, fromX, fromZ, toX, toZ, stepBlocks, searchRadius);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static BlockPos findRoadSpawnPos(WorldServer world, int x, int z, int maxRadius) {
        if (!PRESENT) return null;
        try {
            return (BlockPos) methodFindRoadSpawnPos.invoke(null, world, x, z, maxRadius);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static BlockPos findNearestRoadPos(WorldServer world, int x, int z, int maxRadius) {
        if (!PRESENT) return null;
        try {
            return (BlockPos) methodFindNearestRoadPos.invoke(null, world, x, z, maxRadius);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns an ordered list of road-surface BlockPos for a route, or empty list if no route found. */
    @SuppressWarnings("unchecked")
    public static List<BlockPos> findRoadRoute(WorldServer world, int fromX, int fromZ, int toX, int toZ, int maxSearchRadius) {
        if (!PRESENT) return Collections.emptyList();
        try {
            List<BlockPos> result = (List<BlockPos>) methodFindRoadRoute.invoke(null, world, fromX, fromZ, toX, toZ, maxSearchRadius);
            return result != null ? result : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Returns the route split into legs: each inner list is a physically contiguous road
     * polyline; legs are separated by village crossings. Empty list if no route found.
     * Uses the structured MPB API when available, otherwise splits the flat route by gaps.
     */
    @SuppressWarnings("unchecked")
    public static List<List<BlockPos>> findRoadRouteLegs(WorldServer world, int fromX, int fromZ,
                                                         int toX, int toZ, int maxSearchRadius) {
        if (!PRESENT) return Collections.emptyList();
        if (methodFindRoadRouteLegs != null) {
            try {
                List<List<BlockPos>> result = (List<List<BlockPos>>)
                        methodFindRoadRouteLegs.invoke(null, world, fromX, fromZ, toX, toZ, maxSearchRadius);
                return result != null ? result : Collections.emptyList();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
        return splitByGaps(findRoadRoute(world, fromX, fromZ, toX, toZ, maxSearchRadius));
    }

    private static List<List<BlockPos>> splitByGaps(List<BlockPos> flat) {
        if (flat.isEmpty()) return Collections.emptyList();
        List<List<BlockPos>> legs = new java.util.ArrayList<>();
        List<BlockPos> current = new java.util.ArrayList<>();
        BlockPos prev = null;
        for (BlockPos p : flat) {
            if (prev != null) {
                long dx = p.getX() - prev.getX(), dz = p.getZ() - prev.getZ();
                if (dx * dx + dz * dz > LEG_GAP_THRESHOLD_SQ) {
                    legs.add(current);
                    current = new java.util.ArrayList<>();
                }
            }
            current.add(p);
            prev = p;
        }
        if (!current.isEmpty()) legs.add(current);
        return legs;
    }

    /** True when the installed Ultreia can list the roads inside a box (see {@link #getRoadPolylinesInBox}). */
    public static boolean hasRoadBoxQuery() {
        return PRESENT && methodGetRoadPolylinesInBox != null;
    }

    /** Every road/bridge polyline touching the box, as road-surface points. Empty when unavailable. */
    @SuppressWarnings("unchecked")
    public static List<List<BlockPos>> getRoadPolylinesInBox(WorldServer world, int minX, int minZ, int maxX, int maxZ) {
        if (!hasRoadBoxQuery()) return Collections.emptyList();
        try {
            List<List<BlockPos>> result = (List<List<BlockPos>>)
                    methodGetRoadPolylinesInBox.invoke(null, world, minX, minZ, maxX, maxZ);
            return result != null ? result : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** Returns village center positions (XZ, Y=0) that are endpoints of road segments. */
    @SuppressWarnings("unchecked")
    public static List<BlockPos> getConnectedVillages(WorldServer world) {
        if (!PRESENT) return Collections.emptyList();
        try {
            List<BlockPos> result = (List<BlockPos>) methodGetConnectedVillages.invoke(null, world);
            return result != null ? result : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
