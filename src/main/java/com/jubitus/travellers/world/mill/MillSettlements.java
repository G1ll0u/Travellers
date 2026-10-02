package com.jubitus.travellers.world.mill;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.utilities.MillCommonUtilities;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.VillagerRecord;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one way into Millénaire's settlement list (villages and lone buildings). A settlement is
 * identified by its XZ centre only: stored stops and home villages don't always carry Millénaire's
 * exact Y. Millénaire keeps every settlement, building and villager record resident from world load,
 * so nothing here depends on chunks being loaded. Server side only.
 */
public final class MillSettlements {

    /** How far from a stored position a settlement is still recognised as "that one". */
    public static final int RESOLVE_RADIUS = 160;

    private static final int SNAPSHOT_TTL_TICKS = 600;
    private static final Map<Integer, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private MillSettlements() {
    }

    public static final class Settlement {
        /** Millénaire's town hall position, real Y. */
        public final BlockPos center;
        public final boolean isVillage;
        public final String name;
        public final String typeKey;
        public final String cultureKey;
        private final Point point;

        Settlement(Point point, boolean isVillage, String name, String typeKey, String cultureKey) {
            this.point = point;
            this.center = new BlockPos(point.getiX(), point.getiY(), point.getiZ());
            this.isVillage = isVillage;
            this.name = name;
            this.typeKey = typeKey;
            this.cultureKey = cultureKey;
        }

        @Nullable
        public Building townHall(World world) {
            MillWorldData wd = wd(world);
            return wd == null ? null : wd.getBuilding(point);
        }

        /** Millénaire's own radius for this settlement (VillageType.radius), 0 when unknown. */
        public int radius(World world) {
            Building th = townHall(world);
            return th != null && th.villageType != null ? Math.max(0, th.villageType.radius) : 0;
        }

        /** Living, non-raiding villager records: unloaded villagers count too. */
        public int population(World world) {
            Building th = townHall(world);
            if (th == null) return 0;
            int count = 0;
            for (VillagerRecord vr : th.getVillagerRecords().values()) {
                if (vr != null && !vr.killed && !vr.raidingVillage) count++;
            }
            return count;
        }

        public String displayName() {
            return name != null && !name.isEmpty() ? name
                    : center.getX() + "/" + center.getY() + "/" + center.getZ();
        }
    }

    /** Every settlement of a dimension, refreshed every 30 seconds. Never mutate its lists. */
    public static final class Snapshot {
        public final List<Settlement> all;
        public final List<Settlement> villages;
        public final List<Settlement> loneBuildings;
        public final List<BlockPos> centers;
        public final List<BlockPos> villageCenters;
        private final Map<Long, Settlement> byXZ;
        final long builtTick;

        Snapshot(List<Settlement> villages, List<Settlement> loneBuildings, long builtTick) {
            List<Settlement> all = new ArrayList<>(villages);
            all.addAll(loneBuildings);
            List<BlockPos> centers = new ArrayList<>(all.size());
            Map<Long, Settlement> byXZ = new HashMap<>();
            for (Settlement s : all) {
                centers.add(s.center);
                byXZ.putIfAbsent(key(s.center.getX(), s.center.getZ()), s);
            }
            this.all = Collections.unmodifiableList(all);
            this.villages = Collections.unmodifiableList(villages);
            this.loneBuildings = Collections.unmodifiableList(loneBuildings);
            this.centers = Collections.unmodifiableList(centers);
            List<BlockPos> villageCenters = new ArrayList<>(villages.size());
            for (Settlement s : villages) villageCenters.add(s.center);
            this.villageCenters = Collections.unmodifiableList(villageCenters);
            this.byXZ = byXZ;
            this.builtTick = builtTick;
        }

        @Nullable
        public Settlement at(int x, int z) {
            return byXZ.get(key(x, z));
        }

        @Nullable
        public Settlement at(BlockPos pos) {
            return pos == null ? null : at(pos.getX(), pos.getZ());
        }

        /** Culture key of the settlement at this centre, or "" if unknown. */
        public String cultureAt(BlockPos centre) {
            Settlement s = at(centre);
            return s == null || s.cultureKey == null ? "" : s.cultureKey;
        }

        public boolean isVillage(BlockPos centre) {
            Settlement s = at(centre);
            return s != null && s.isVillage;
        }
    }

    @Nullable
    public static MillWorldData wd(World world) {
        try {
            WorldServer ws = world instanceof WorldServer ? (WorldServer) world
                    : world.getMinecraftServer().getWorld(world.provider.getDimension());
            return ws == null ? null : Mill.getMillWorld(ws);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Snapshot snapshot(World world) {
        int dim = world.provider.getDimension();
        long now = world.getTotalWorldTime();
        Snapshot s = SNAPSHOTS.get(dim);
        if (s != null && now >= s.builtTick && now - s.builtTick < SNAPSHOT_TTL_TICKS) return s;
        s = build(world, now);
        SNAPSHOTS.put(dim, s);
        return s;
    }

    public static List<Settlement> all(World world) {
        return snapshot(world).all;
    }

    public static List<Settlement> villages(World world) {
        return snapshot(world).villages;
    }

    public static List<Settlement> loneBuildings(World world) {
        return snapshot(world).loneBuildings;
    }

    /** The settlement whose centre is exactly at this XZ. */
    @Nullable
    public static Settlement at(World world, BlockPos pos) {
        return snapshot(world).at(pos);
    }

    /** The settlement at this XZ, else the nearest one within {@link #RESOLVE_RADIUS}. */
    @Nullable
    public static Settlement resolve(World world, BlockPos pos) {
        if (pos == null) return null;
        Settlement s = at(world, pos);
        return s != null ? s : nearest(world, pos, RESOLVE_RADIUS);
    }

    /** Best-effort player-facing name of the settlement at/near this position, "x/y/z" otherwise. */
    public static String nameOf(World world, BlockPos pos) {
        String name = nameOrNull(world, pos);
        return name != null ? name : pos.getX() + "/" + pos.getY() + "/" + pos.getZ();
    }

    @Nullable
    public static String nameOrNull(World world, BlockPos pos) {
        Settlement s = resolve(world, pos);
        return s != null && s.name != null && !s.name.isEmpty() ? s.name : null;
    }

    /** Nearest settlement (XZ) within {@code radius}, villages and lone buildings alike. */
    @Nullable
    public static Settlement nearest(World world, BlockPos pos, int radius) {
        return nearestIn(snapshot(world).all, pos, radius);
    }

    @Nullable
    public static Settlement nearestVillage(World world, BlockPos pos, int radius) {
        return nearestIn(snapshot(world).villages, pos, radius);
    }

    @Nullable
    private static Settlement nearestIn(List<Settlement> list, BlockPos pos, int radius) {
        long best = (long) radius * radius;
        Settlement found = null;
        for (Settlement s : list) {
            long d = distSqXZ(s.center, pos);
            if (d <= best) {
                best = d;
                found = s;
            }
        }
        return found;
    }

    /** True if any settlement centre lies within {@code radius} blocks (XZ). */
    public static boolean isNear(World world, BlockPos pos, int radius) {
        return nearest(world, pos, radius) != null;
    }

    /** True if {@code pos} lies within a settlement's own Millénaire radius (generous vertical band). */
    public static boolean isInside(World world, BlockPos pos) {
        for (Settlement s : snapshot(world).all) {
            if (Math.abs(s.center.getY() - pos.getY()) > 32) continue;
            int r = s.radius(world);
            if (r > 0 && distSqXZ(s.center, pos) <= (long) r * r) return true;
        }
        return false;
    }

    /** Case-insensitive exact name match first, then a substring match either way. Villages only. */
    @Nullable
    public static Settlement villageByName(World world, String query) {
        if (query == null || query.trim().isEmpty()) return null;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        Settlement partial = null;
        for (Settlement s : snapshot(world).villages) {
            if (s.name == null || s.name.isEmpty()) continue;
            String hay = s.name.toLowerCase(Locale.ROOT);
            if (hay.equals(needle)) return s;
            if (partial == null && (hay.contains(needle) || needle.contains(hay))) partial = s;
        }
        return partial;
    }

    /** Town hall of the settlement at this XZ, or null. */
    @Nullable
    public static Building townHall(World world, BlockPos pos) {
        Settlement s = at(world, pos);
        return s == null ? null : s.townHall(world);
    }

    public static int population(World world, BlockPos pos) {
        Settlement s = at(world, pos);
        return s == null ? 0 : s.population(world);
    }

    /** Village centres whose town hall this player controls (Millénaire chief). */
    public static Set<BlockPos> controlledBy(World world, EntityPlayer player) {
        Set<BlockPos> out = new HashSet<>();
        for (Settlement s : snapshot(world).villages) {
            Building th = s.townHall(world);
            if (th != null && th.isTownhall && th.controlledBy(player)) out.add(s.center);
        }
        return out;
    }

    private static Snapshot build(World world, long now) {
        MillWorldData wd = wd(world);
        if (wd == null) return new Snapshot(Collections.emptyList(), Collections.emptyList(), now);
        return new Snapshot(read(wd.villagesList, true), read(wd.loneBuildingsList, false), now);
    }

    private static List<Settlement> read(@Nullable MillCommonUtilities.VillageList list, boolean village) {
        List<Settlement> out = new ArrayList<>();
        if (list == null || list.pos == null) return out;
        for (int i = 0; i < list.pos.size(); i++) {
            Point p = list.pos.get(i);
            if (p == null) continue;
            out.add(new Settlement(p, village, at(list.names, i), at(list.types, i), at(list.cultures, i)));
        }
        return out;
    }

    private static String at(@Nullable List<String> list, int i) {
        return list != null && i < list.size() && list.get(i) != null ? list.get(i) : "";
    }

    private static long distSqXZ(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static long key(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }
}
