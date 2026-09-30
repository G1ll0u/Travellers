package com.jubitus.travellers.world.mill;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;

public final class MillControlledVillageHelper {

    // playerUUID -> [cachedAtTick, isChief (0/1)]
    private static final Map<UUID, long[]> CHIEF_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_TICKS = 20L * 30L; // 30 seconds

    private MillControlledVillageHelper() {}

    public static boolean isPlayerVillageChief(World world, EntityPlayer player) {
        UUID id = player.getUniqueID();
        long now = world.getTotalWorldTime();
        long[] entry = CHIEF_CACHE.get(id);
        if (entry != null && now - entry[0] < CACHE_TTL_TICKS) {
            return entry[1] == 1L;
        }
        boolean chief = queryIsChief(world, player);
        CHIEF_CACHE.put(id, new long[]{now, chief ? 1L : 0L});
        return chief;
    }

    public static Set<BlockPos> getControlledVillageCenters(World world, EntityPlayer player) {
        MillWorldData wd = MillVillageIndex.getWD(world);
        if (wd == null || wd.villagesList == null) return Collections.emptySet();

        Set<BlockPos> result = new HashSet<>();
        for (Point p : wd.villagesList.pos) {
            Building townHall = wd.getBuilding(p);
            if (townHall == null || !townHall.isTownhall || !townHall.controlledBy(player)) continue;
            result.add(toBlockPos(townHall.getPos()));
        }
        return result;
    }

    public static void invalidateCache(UUID playerId) {
        CHIEF_CACHE.remove(playerId);
    }

    public static BlockPos toBlockPos(Point p) {
        return new BlockPos(p.getiX(), p.getiY(), p.getiZ());
    }

    private static boolean queryIsChief(World world, EntityPlayer player) {
        MillWorldData wd = MillVillageIndex.getWD(world);
        if (wd == null || wd.villagesList == null) return false;

        for (Point p : wd.villagesList.pos) {
            Building b = wd.getBuilding(p);
            if (b != null && b.isTownhall && b.controlledBy(player)) return true;
        }
        return false;
    }
}
