package com.jubitus.travellers.world.mill;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.village.BuildingProject;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives the wall ring and gate openings of a Millenaire village from its resident building
 * data, so itineraries can thread a gate instead of walking into the wall.
 *
 * <p>No block scanning and no chunk loading: Millenaire keeps every town hall and its wall
 * building projects in memory whether the chunks are loaded or not. Cached per village centre
 * (walls are near-static within a session; a village that upgrades its walls is picked up after
 * the TTL).
 */
public final class VillageGateIndex {

    /** Village centres are unique enough on XZ; key ignores dimension (one manager per world). */
    private static final ConcurrentHashMap<Long, Entry> CACHE = new ConcurrentHashMap<>();
    private static final long TTL_TICKS = 20L * 60L * 3L; // 3 minutes
    private static final int MAX_ENTRIES = 256;

    /** A wall segment whose plan key contains any of these is treated as a gate opening. */
    private static final String[] GATE_KEY_MARKERS = {"gate", "gateway"};

    private VillageGateIndex() {
    }

    /**
     * The wall ring of the village centred at {@code villageCenter}, or {@code null} when that
     * settlement has no wall gateways (no walls, or an unwalled lone building).
     */
    @Nullable
    public static Ring ringFor(World world, BlockPos villageCenter) {
        if (world == null || villageCenter == null) return null;

        long key = keyOf(villageCenter);
        long now = world.getTotalWorldTime();

        Entry cached = CACHE.get(key);
        if (cached != null && now - cached.stampTick < TTL_TICKS) {
            return cached.ring;
        }

        Ring ring = compute(world, villageCenter);
        if (CACHE.size() > MAX_ENTRIES) CACHE.clear();
        CACHE.put(key, new Entry(ring, now));
        return ring;
    }

    @Nullable
    private static Ring compute(World world, BlockPos villageCenter) {
        Building townHall = MillSettlements.townHall(world, villageCenter);
        if (townHall == null || townHall.buildingProjects == null) return null;

        List<BuildingProject> wallProjects =
                townHall.buildingProjects.get(BuildingProject.EnumProjects.WALLBUILDING);
        if (wallProjects == null || wallProjects.isEmpty()) return null;

        List<BlockPos> gates = new ArrayList<>();
        List<BlockPos> segments = new ArrayList<>();

        for (BuildingProject bp : wallProjects) {
            if (bp == null || bp.location == null) continue;
            BuildingLocation bl = bp.location;
            Point p = bl.pos;
            if (p == null) continue;

            BlockPos at = new BlockPos(p.getiX(), p.getiY(), p.getiZ());
            segments.add(at);

            String planKey = bl.planKey;
            if (planKey != null && containsAny(planKey.toLowerCase(java.util.Locale.ROOT), GATE_KEY_MARKERS)) {
                gates.add(at);
            }
        }

        if (gates.isEmpty()) return null;

        // Ring radius: a high percentile of the wall segments' horizontal distance from centre,
        // so a single stray/planned-far segment does not inflate it. Falls back to the gates.
        List<BlockPos> basis = segments.isEmpty() ? gates : segments;
        double radius = percentileHorizontalDistance(villageCenter, basis, 0.90D);
        if (radius <= 0) return null;

        return new Ring(villageCenter, radius, gates);
    }

    private static boolean containsAny(String s, String[] needles) {
        for (String n : needles) {
            if (s.contains(n)) return true;
        }
        return false;
    }

    private static double percentileHorizontalDistance(BlockPos center, List<BlockPos> pts, double q) {
        double[] d = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            double dx = pts.get(i).getX() - center.getX();
            double dz = pts.get(i).getZ() - center.getZ();
            d[i] = Math.sqrt(dx * dx + dz * dz);
        }
        java.util.Arrays.sort(d);
        int idx = (int) Math.floor(q * (d.length - 1));
        return d[Math.max(0, Math.min(d.length - 1, idx))];
    }

    private static long keyOf(BlockPos p) {
        return ((long) p.getX() & 0xFFFFFFFFL) | (((long) p.getZ() & 0xFFFFFFFFL) << 32);
    }

    /** Invalidate everything (e.g. on world unload). Cheap; the cache rebuilds lazily. */
    public static void clear() {
        CACHE.clear();
    }

    // ------------------------------------------------------------------------------------------

    public static final class Ring {
        public final BlockPos center;
        public final double radius;
        public final List<BlockPos> gates;

        Ring(BlockPos center, double radius, List<BlockPos> gates) {
            this.center = center;
            this.radius = radius;
            this.gates = gates;
        }
    }

    private static final class Entry {
        @Nullable
        final Ring ring;
        final long stampTick;

        Entry(@Nullable Ring ring, long stampTick) {
            this.ring = ring;
            this.stampTick = stampTick;
        }
    }
}