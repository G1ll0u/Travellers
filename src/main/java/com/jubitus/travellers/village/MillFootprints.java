package com.jubitus.travellers.village;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * World-wide index of every Millénaire building footprint (villages and lone buildings alike), read
 * from Millénaire's resident building data - no block scan, no chunk loading. The one answer to
 * "is this spot inside a Millénaire building (walls, rooms, roof)?", shared by every teleport picker,
 * the materialize resolver and the spawner. Server thread only.
 */
public final class MillFootprints {

    /** Chunk buckets are padded by this much, the most any query margin may ask for. */
    private static final int BUCKET_PAD = 8;

    private static final Map<World, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    private MillFootprints() {
    }

    public static final class Footprint {
        // Inclusive bounds of the blocks the building's plan places.
        public final int minX, minY, minZ, maxX, maxY, maxZ;
        // The spacing Millénaire reserves around the building, inclusive, XZ only.
        public final int resMinX, resMinZ, resMaxX, resMaxZ;
        public final Building building;

        Footprint(Building building, BuildingLocation loc) {
            this.building = building;
            // The blocks the plan really places: BuildingPlan lays deltaX in [0, length) and deltaZ in
            // [0, width) at offset -size/2, through adjustForOrientation. Millénaire's own minx..maxx is
            // off by one on a side that depends on orientation and size parity.
            int l = Math.max(1, loc.length), w = Math.max(1, loc.width);
            Point a = loc.pos == null ? null
                    : BuildingPlan.adjustForOrientation(loc.pos.getiX(), 0, loc.pos.getiZ(), -(l / 2), -(w / 2), loc.orientation);
            Point b = loc.pos == null ? null
                    : BuildingPlan.adjustForOrientation(loc.pos.getiX(), 0, loc.pos.getiZ(), l - 1 - l / 2, w - 1 - w / 2, loc.orientation);
            if (a != null && b != null) {
                this.minX = Math.min(a.getiX(), b.getiX());
                this.maxX = Math.max(a.getiX(), b.getiX());
                this.minZ = Math.min(a.getiZ(), b.getiZ());
                this.maxZ = Math.max(a.getiZ(), b.getiZ());
            } else {
                this.minX = loc.minx;
                this.maxX = loc.maxx;
                this.minZ = loc.minz;
                this.maxZ = loc.maxz;
            }
            this.minY = loc.miny;
            this.maxY = Math.max(loc.miny, loc.maxy - 1);
            this.resMinX = loc.minxMargin;
            this.resMinZ = loc.minzMargin;
            this.resMaxX = loc.maxxMargin;
            this.resMaxZ = loc.maxzMargin;
        }

        public boolean reservedOverlaps(BlockPos min, BlockPos max) {
            return resMinX <= max.getX() && resMaxX >= min.getX() && resMinZ <= max.getZ() && resMaxZ >= min.getZ();
        }

        public boolean reservedContains(int x, int z) {
            return x >= resMinX && x <= resMaxX && z >= resMinZ && z <= resMaxZ;
        }

        boolean containsXZ(int x, int z, int margin) {
            return x >= minX - margin && x <= maxX + margin && z >= minZ - margin && z <= maxZ + margin;
        }

        boolean containsY(int y, int heightMargin) {
            return y >= minY - heightMargin && y <= maxY + heightMargin;
        }

        public String planKey() {
            BuildingLocation loc = building.location;
            return loc != null && loc.planKey != null ? loc.planKey : "?";
        }
    }

    private static final class Snapshot {
        final long builtTick;
        final List<Footprint> all;
        final Map<Long, List<Footprint>> byChunk;

        Snapshot(long builtTick, List<Footprint> all, Map<Long, List<Footprint>> byChunk) {
            this.builtTick = builtTick;
            this.all = all;
            this.byChunk = byChunk;
        }
    }

    /** The building whose footprint (grown by {@code margin} blocks around, and by the configured
     * height margin above/below) contains this spot, or {@code null}. */
    @Nullable
    public static Footprint at(World world, double x, double y, double z, int margin) {
        int bx = MathHelper.floor(x), by = MathHelper.floor(y), bz = MathHelper.floor(z);
        int m = MathHelper.clamp(margin, 0, BUCKET_PAD);
        int hm = TravellersConfig.VILLAGE_VISIT.buildingHeightMargin;
        List<Footprint> bucket = snapshot(world).byChunk.get(chunkKey(bx >> 4, bz >> 4));
        if (bucket == null) return null;
        for (Footprint f : bucket) {
            if (f.containsXZ(bx, bz, m) && f.containsY(by, hm)) return f;
        }
        return null;
    }

    /** Height-blind variant, for positions whose Y is not trusted (a stale offscreen Y). */
    @Nullable
    public static Footprint atPlanar(World world, double x, double z, int margin) {
        int bx = MathHelper.floor(x), bz = MathHelper.floor(z);
        int m = MathHelper.clamp(margin, 0, BUCKET_PAD);
        List<Footprint> bucket = snapshot(world).byChunk.get(chunkKey(bx >> 4, bz >> 4));
        if (bucket == null) return null;
        for (Footprint f : bucket) {
            if (f.containsXZ(bx, bz, m)) return f;
        }
        return null;
    }

    /** True if a traveller must not be put down here: inside a building, on its roof, or closer to
     * it than {@code VILLAGE_VISIT.buildingClearance}. */
    public static boolean isForbiddenLanding(World world, double x, double y, double z) {
        return at(world, x, y, z, TravellersConfig.VILLAGE_VISIT.buildingClearance) != null;
    }

    /** Moves {@code pos} (XZ only, Y untouched) just outside every building footprint it falls in,
     * toward the nearest edge. Bounded, in case the nudge lands in a neighbouring building. */
    public static BlockPos pushOutside(World world, BlockPos pos) {
        int clearance = TravellersConfig.VILLAGE_VISIT.buildingClearance;
        BlockPos candidate = pos;
        for (int i = 0; i < 6; i++) {
            Footprint hit = atPlanar(world, candidate.getX(), candidate.getZ(), clearance);
            if (hit == null) return candidate;
            candidate = pushOutOf(hit, candidate, clearance + 1);
        }
        return candidate;
    }

    private static BlockPos pushOutOf(Footprint f, BlockPos pos, int gap) {
        int x = pos.getX();
        int z = pos.getZ();
        int toMinX = x - (f.minX - gap);
        int toMaxX = (f.maxX + gap) - x;
        int toMinZ = z - (f.minZ - gap);
        int toMaxZ = (f.maxZ + gap) - z;
        int min = Math.min(Math.min(toMinX, toMaxX), Math.min(toMinZ, toMaxZ));
        if (min == toMinX) x = f.minX - gap;
        else if (min == toMaxX) x = f.maxX + gap;
        else if (min == toMinZ) z = f.minZ - gap;
        else z = f.maxZ + gap;
        return new BlockPos(x, pos.getY(), z);
    }

    /** Every footprint whose centre lies within {@code radius} blocks (XZ) of the given point. */
    public static List<Footprint> near(World world, double x, double z, double radius) {
        List<Footprint> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Footprint f : snapshot(world).all) {
            double cx = (f.minX + f.maxX + 1) * 0.5D - x;
            double cz = (f.minZ + f.maxZ + 1) * 0.5D - z;
            if (cx * cx + cz * cz <= r2) out.add(f);
        }
        return out;
    }

    /** Every footprint whose reserved box overlaps [min,max] (XZ). Collect once for an area, then test
     * its sub-areas with {@link #anyReserved}/{@link #anyReservedContains}. */
    public static List<Footprint> reservedIn(World world, BlockPos min, BlockPos max) {
        List<Footprint> out = new ArrayList<>();
        for (Footprint f : snapshot(world).all) {
            if (f.reservedOverlaps(min, max)) out.add(f);
        }
        return out;
    }

    public static boolean overlapsReserved(World world, BlockPos min, BlockPos max) {
        for (Footprint f : snapshot(world).all) {
            if (f.reservedOverlaps(min, max)) return true;
        }
        return false;
    }

    public static boolean anyReserved(List<Footprint> zones, BlockPos min, BlockPos max) {
        for (Footprint f : zones) {
            if (f.reservedOverlaps(min, max)) return true;
        }
        return false;
    }

    public static boolean anyReservedContains(List<Footprint> zones, int x, int z) {
        for (Footprint f : zones) {
            if (f.reservedContains(x, z)) return true;
        }
        return false;
    }

    public static int count(World world) {
        return snapshot(world).all.size();
    }

    private static Snapshot snapshot(World world) {
        long now = world.getTotalWorldTime();
        long ttl = TravellersConfig.VILLAGE_VISIT.buildingIndexRefreshSeconds * 20L;
        Snapshot s = SNAPSHOTS.get(world);
        if (s != null && now >= s.builtTick && now - s.builtTick < ttl) return s;
        s = build(world, now);
        SNAPSHOTS.put(world, s);
        return s;
    }

    private static Snapshot build(World world, long now) {
        MillWorldData wd = MillSettlements.wd(world);
        if (wd == null) return new Snapshot(now, Collections.emptyList(), Collections.emptyMap());

        List<Footprint> all = new ArrayList<>();
        Map<Long, List<Footprint>> byChunk = new HashMap<>();
        for (Building b : wd.allBuildings()) {
            if (b == null || b.location == null) continue;
            Footprint f = new Footprint(b, b.location);
            if (f.maxX < f.minX || f.maxZ < f.minZ) continue;
            all.add(f);
            int cx0 = (f.minX - BUCKET_PAD) >> 4, cx1 = (f.maxX + BUCKET_PAD) >> 4;
            int cz0 = (f.minZ - BUCKET_PAD) >> 4, cz1 = (f.maxZ + BUCKET_PAD) >> 4;
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    byChunk.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>(2)).add(f);
                }
            }
        }
        return new Snapshot(now, all, byChunk);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }
}
