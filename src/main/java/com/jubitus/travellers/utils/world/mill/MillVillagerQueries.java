package com.jubitus.travellers.utils.world.mill;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.entity.MillVillager;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility methods to query LOADED Millénaire villagers and raiders.
 *
 * IMPORTANT:
 * - Call from SERVER side only (world.isRemote == false)
 * - Call from the main server thread (normal entity AI tick is fine)
 */
public final class MillVillagerQueries {

    private MillVillagerQueries() {}

    // ---- Public API ----

    /**
     * Returns the Town Hall building for the village whose center is at approxTownHallPos,
     * or the nearest TH within maxRadius blocks (XZ).
     *
     * @param world server world
     * @param approxTownHallPos exact/approx Town Hall position
     * @param maxRadius blocks (XZ) to search if not exact
     */
    @Nullable
    public static Building resolveTownHall(World world, BlockPos approxTownHallPos, int maxRadius) {
        MillWorldData wd = getWD(world);
        if (wd == null) return null;

        // 1) Exact match (most reliable if you already store TH centers)
        Building exact = wd.getBuilding(new Point(
                approxTownHallPos.getX(),
                approxTownHallPos.getY(),
                approxTownHallPos.getZ()
        ));
        if (exact != null && exact.isTownhall) {
            return exact;
        }

        // 2) Nearest TH within radius (XZ)
        return findNearestTownHallXZ(wd, approxTownHallPos, maxRadius);
    }

    /**
     * List all LOADED villagers that belong to the village at approxTownHallPos.
     * Raiders are excluded by default.
     */
    public static List<MillVillager> getLoadedVillageVillagers(World world, BlockPos approxTownHallPos, int maxRadius) {
        return getLoadedVillageVillagers(world, approxTownHallPos, maxRadius, false);
    }

    /**
     * List all LOADED villagers that belong to the village at approxTownHallPos.
     *
     * @param includeRaiders if true, include raiders too
     */
    public static List<MillVillager> getLoadedVillageVillagers(World world,
                                                               BlockPos approxTownHallPos,
                                                               int maxRadius,
                                                               boolean includeRaiders) {
        MillWorldData wd = getWD(world);
        if (wd == null) return Collections.emptyList();

        Building th = resolveTownHall(world, approxTownHallPos, maxRadius);
        if (th == null) return Collections.emptyList();

        List<MillVillager> out = new ArrayList<>();
        for (MillVillager v : wd.getAllKnownVillagers()) {
            if (v == null) continue;

            // Filter by that Town Hall
            if (!belongsToTownHall(v, th)) continue;

            // Default: exclude raiders
            if (!includeRaiders && isRaider(v)) continue;

            // Skip dead entities (extra safety)
            if (!v.isEntityAlive()) continue;

            out.add(v);
        }

        return out;
    }

    /**
     * List LOADED raiders currently assigned to / attacking the village (Town Hall).
     * This is what you want for "attack raiders that attack the village I'm in".
     */
    public static List<MillVillager> getLoadedRaidersForVillage(World world, BlockPos approxTownHallPos, int maxRadius) {
        MillWorldData wd = getWD(world);
        if (wd == null) return Collections.emptyList();

        Building th = resolveTownHall(world, approxTownHallPos, maxRadius);
        if (th == null) return Collections.emptyList();

        List<MillVillager> out = new ArrayList<>();
        for (MillVillager v : wd.getAllKnownVillagers()) {
            if (v == null) continue;
            if (!v.isEntityAlive()) continue;

            if (!isRaider(v)) continue;
            if (!belongsToTownHall(v, th)) continue;

            out.add(v);
        }

        return out;
    }

    // ---- Internals ----

    @Nullable
    private static MillWorldData getWD(World world) {
        if (world == null || world.isRemote) return null;

        try {
            // Make sure we use the correct WorldServer for the dimension
            WorldServer ws = (world instanceof WorldServer)
                    ? (WorldServer) world
                    : world.getMinecraftServer().getWorld(world.provider.getDimension());
            if (ws == null) return null;

            return Mill.getMillWorld(ws);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isRaider(MillVillager v) {
        // In your decompiled class, v.isRaider is copied into VillagerRecord.raidingVillage
        // So this should be the correct flag for "is a raiders".
        try {
            return v.isRaider;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean belongsToTownHall(MillVillager v, Building townHall) {
        if (townHall == null) return false;

        // Preferred: Building instance comparison (fast, usually correct)
        Building vTh = v.getTownHall();
        if (vTh == townHall) {
            return true;
        }

        // Fallback: compare positions (if instance differs for some reason)
        try {
            if (vTh != null && vTh.getPos() != null && townHall.getPos() != null) {
                return vTh.getPos().equals(townHall.getPos());
            }
        } catch (Throwable ignored) {}

        // Another fallback: compare villager's stored town hall point if accessible
        try {
            Point p = v.townHallPoint;
            if (p != null && townHall.getPos() != null) {
                return p.equals(townHall.getPos());
            }
        } catch (Throwable ignored) {}

        return false;
    }

    @Nullable
    private static Building findNearestTownHallXZ(MillWorldData wd, BlockPos origin, int maxRadius) {
        if (wd == null || wd.villagesList == null || wd.villagesList.pos == null) return null;

        int ox = origin.getX();
        int oz = origin.getZ();
        long bestD2 = Long.MAX_VALUE;
        Point bestPoint = null;

        long maxR2 = (long) maxRadius * (long) maxRadius;

        for (Point p : wd.villagesList.pos) {
            if (p == null) continue;

            long dx = (long) p.getiX() - ox;
            long dz = (long) p.getiZ() - oz;
            long d2 = dx * dx + dz * dz;

            if (d2 <= maxR2 && d2 < bestD2) {
                bestD2 = d2;
                bestPoint = p;
            }
        }

        if (bestPoint == null) return null;

        Building b = wd.getBuilding(bestPoint);
        if (b != null && b.isTownhall) return b;

        return null;
    }
}