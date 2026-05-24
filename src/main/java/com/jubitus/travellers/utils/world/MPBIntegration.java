package com.jubitus.travellers.utils.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

public final class MPBIntegration {

    public static final boolean PRESENT = detect();

    private static Class<?> apiClass;
    private static Method methodIsOnRoad;
    private static Method methodFindNextWaypoint;
    private static Method methodFindRoadSpawnPos;

    private MPBIntegration() {}

    private static boolean detect() {
        try {
            apiClass = Class.forName("com.jubitus.millpathbuilder.api.MPBRoadAPI");
            methodIsOnRoad = apiClass.getMethod("isOnRoad", WorldServer.class, int.class, int.class);
            methodFindNextWaypoint = apiClass.getMethod("findNextRoadWaypointToward",
                    WorldServer.class, int.class, int.class, int.class, int.class, int.class, int.class);
            methodFindRoadSpawnPos = apiClass.getMethod("findRoadSpawnPos", WorldServer.class, int.class, int.class, int.class);
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
}
