package com.jubitus.traveller.traveller.utils.villages;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.utilities.MillCommonUtilities;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class MillVillageIndex {

    private MillVillageIndex() {
    }

    // ---------- Public API ----------

    /**
     * All known village centers for this world/dimension.
     */
    public static List<BlockPos> getAllVillageCenters(World world) {
        MillWorldData wd = getWD(world);
        if (wd == null || wd.villagesList == null || wd.villagesList.pos == null) {
            return java.util.Collections.emptyList();
        }
        List<BlockPos> out = new ArrayList<>(wd.villagesList.pos.size());
        for (Point p : wd.villagesList.pos) out.add(toBlockPos(p));
        return out;
    }


    @Nullable
    private static MillWorldData getWD(World world) {
        try {
            final WorldServer ws = (world instanceof WorldServer)
                    ? (WorldServer) world
                    : world.getMinecraftServer().getWorld(world.provider.getDimension());
            if (ws == null) return null;
            return Mill.getMillWorld(ws);
        } catch (Throwable t) {
            return null;
        }
    }


    /**
     * Find the VillageInfo whose TH is exactly at that BlockPos (Y ignored unless it differs).
     */

    private static BlockPos toBlockPos(Point p) {
        return new BlockPos(p.getiX(), p.getiY(), p.getiZ());
    }

    /**
     * Best-effort name: exact → nearest3D(128) → nearest2D(160) → "x/y/z".
     */
    public static String nameForApprox(World world, BlockPos approxTH) {
        VillageInfo vi = findExact(world, approxTH);
        if (vi == null) vi = findNearest3D(world, approxTH, 128);
        if (vi == null) vi = findNearest2D(world, approxTH, 160);
        if (vi != null) return vi.name;
        return approxTH.getX() + "/" + approxTH.getY() + "/" + approxTH.getZ();
    }

    /**
     * Exact classification by matching the saved centers.
     */
    @Nullable
    public static VillageInfo findExact(World world, BlockPos pos) {
        MillWorldData wd = getWD(world);
        if (wd == null) return null;

        // villages
        for (int i = 0; i < wd.villagesList.pos.size(); i++) {
            Point p = wd.villagesList.pos.get(i);
            if (p.getiX() == pos.getX() && p.getiY() == pos.getY() && p.getiZ() == pos.getZ()) {
                return new VillageInfo(pos, true,
                        wd.villagesList.names.get(i),
                        wd.villagesList.types.get(i),
                        wd.villagesList.cultures.get(i));
            }
        }
        // lone buildings
        for (int i = 0; i < wd.loneBuildingsList.pos.size(); i++) {
            Point p = wd.loneBuildingsList.pos.get(i);
            if (p.getiX() == pos.getX() && p.getiY() == pos.getY() && p.getiZ() == pos.getZ()) {
                return new VillageInfo(pos, false,
                        wd.loneBuildingsList.names.get(i),
                        wd.loneBuildingsList.types.get(i),
                        wd.loneBuildingsList.cultures.get(i));
            }
        }
        return null;
    }

    /**
     * 3D nearest Town Hall within radius (blocks).
     */
    @Nullable
    public static VillageInfo findNearest3D(World world, BlockPos origin, int maxRadius) {
        MillWorldData wd = getWD(world);
        if (wd == null) return null;

        long maxSq = (long) maxRadius * (long) maxRadius;
        Point op = new Point(origin.getX(), origin.getY(), origin.getZ());

        Point bestP = null;
        long bestD = Long.MAX_VALUE;

        for (Point p : wd.villagesList.pos) {
            long d = (long) op.distanceToSquared(p);
            if (d <= maxSq && d < bestD) {
                bestD = d;
                bestP = p;
            }
        }
        if (bestP == null) return null;
        Building th = wd.getBuilding(bestP);
        return (th != null) ? buildInfo(wd, th) : null;
    }

    /**
     * Nearest (XZ) within a max radius; returns which list it came from.
     */
    @Nullable
    public static VillageInfo findNearest2D(World world, BlockPos pos, int maxRadius) {
        MillWorldData wd = getWD(world);
        if (wd == null) return null;
        long best = Long.MAX_VALUE;
        VillageInfo bestVI = null;
        long maxR2 = (long) maxRadius * (long) maxRadius;

        // villages
        for (int i = 0; i < wd.villagesList.pos.size(); i++) {
            Point p = wd.villagesList.pos.get(i);
            long d2 = dist2XZ(pos, p);
            if (d2 <= maxR2 && d2 < best) {
                best = d2;
                bestVI = new VillageInfo(new BlockPos(p.getiX(), p.getiY(), p.getiZ()), true,
                        wd.villagesList.names.get(i), wd.villagesList.types.get(i), wd.villagesList.cultures.get(i));
            }
        }
        // lone buildings
        for (int i = 0; i < wd.loneBuildingsList.pos.size(); i++) {
            Point p = wd.loneBuildingsList.pos.get(i);
            long d2 = dist2XZ(pos, p);
            if (d2 <= maxR2 && d2 < best) {
                best = d2;
                bestVI = new VillageInfo(new BlockPos(p.getiX(), p.getiY(), p.getiZ()), false,
                        wd.loneBuildingsList.names.get(i), wd.loneBuildingsList.types.get(i), wd.loneBuildingsList.cultures.get(i));
            }
        }

        return bestVI;

    }

    /**
     * Exact classification by matching the saved centers.
     */
//    @Nullable
//    public static VillageInfo newFindExact(World world, BlockPos pos) {
//        MillWorldData wd = getWD(world);
//        if (wd == null) return null;
//
//        // villages
//        for (int i = 0; i < wd.villagesList.pos.size(); i++) {
//            Point p = wd.villagesList.pos.get(i);
//            if (p.getiX() == pos.getX() && p.getiY() == pos.getY() && p.getiZ() == pos.getZ()) {
//                return new VillageInfo(pos, true,
//                        wd.villagesList.names.get(i),
//                        wd.villagesList.types.get(i),
//                        wd.villagesList.cultures.get(i));
//            }
//        }
//        // lone buildings
//        for (int i = 0; i < wd.loneBuildingsList.pos.size(); i++) {
//            Point p = wd.loneBuildingsList.pos.get(i);
//            if (p.getiX() == pos.getX() && p.getiY() == pos.getY() && p.getiZ() == pos.getZ()) {
//                return new VillageInfo(pos, false,
//                        wd.loneBuildingsList.names.get(i),
//                        wd.loneBuildingsList.types.get(i),
//                        wd.loneBuildingsList.cultures.get(i));
//            }
//        }
//        return null;
//    }

//    /**
//     * 2D (XZ) nearest Town Hall within radius (blocks). Useful if Y in your saved pos is off.
//     */
//    @Nullable
//    public static VillageInfo newFindNearest2D(World world, BlockPos origin, int maxRadius) {
//        MillWorldData wd = getWD(world);
//        if (wd == null) return null;
//
//        int ox = origin.getX(), oz = origin.getZ();
//        long maxSq = (long) maxRadius * (long) maxRadius;
//
//        Point bestP = null;
//        long bestD = Long.MAX_VALUE;
//
//        for (Point p : wd.villagesList.pos) {
//            int dx = p.getiX() - ox;
//            int dz = p.getiZ() - oz;
//            long d = (long) dx * dx + (long) dz * dz;
//            if (d <= maxSq && d < bestD) {
//                bestD = d;
//                bestP = p;
//            }
//        }
//        if (bestP == null) return null;
//        Building th = wd.getBuilding(bestP);
//        return (th != null) ? buildInfo(wd, th) : null;
//    }
    private static MillVillageIndex.VillageInfo buildInfo(MillWorldData wd, Building th) {
        Integer idx = wd.villagesList.rankByPos.get(th.getPos());
        String name = th.toString();
        String culture = "";
        String typeKey = "";
        if (idx != null) {
            MillCommonUtilities.VillageList vl = wd.villagesList;
            name = safeString(vl.names, idx, name);
            culture = safeString(vl.cultures, idx, "");
            typeKey = safeString(vl.types, idx, "");
        }
        Point p = th.getPos();
        return new MillVillageIndex.VillageInfo(
                new BlockPos(p.getiX(), p.getiY(), p.getiZ()),
                true,           // this is a village (Town Hall), not a lone building
                name,
                typeKey,        // <— ctor expects typeKey before cultureKey
                culture
        );
    }

    private static long dist2XZ(BlockPos a, Point p) {
        long dx = a.getX() - p.getiX();
        long dz = a.getZ() - p.getiZ();
        return dx * dx + dz * dz;
    }

    // helper
    private static String safeString(java.util.List<String> list, int idx, String def) {
        return (idx >= 0 && idx < list.size() && list.get(idx) != null) ? list.get(idx) : def;
    }

    // ---------- Helpers ----------
    @Nullable
    public static String villageNameFor(World world, BlockPos approxTH) {
        MillWorldData wd = getWD(world);
        if (wd == null || wd.villagesList == null) return null;

        // 1) Try exact match first
        for (int i = 0; i < wd.villagesList.pos.size(); i++) {
            Point p = wd.villagesList.pos.get(i);
            if (p.getiX() == approxTH.getX() && p.getiY() == approxTH.getY() && p.getiZ() == approxTH.getZ()) {
                Object name = wd.villagesList.names.get(i);
                return name != null ? name.toString() : null;
            }
        }

        // 2) Fall back to nearest 2D within a tolerance (Y can differ a lot)
        final int maxRadius = 160; // tune if needed
        final long maxSq = (long) maxRadius * (long) maxRadius;
        int ox = approxTH.getX(), oz = approxTH.getZ();
        int bestIdx = -1;
        long bestD = Long.MAX_VALUE;

        for (int i = 0; i < wd.villagesList.pos.size(); i++) {
            Point p = wd.villagesList.pos.get(i);
            long dx = p.getiX() - ox, dz = p.getiZ() - oz;
            long d = dx * dx + dz * dz;
            if (d <= maxSq && d < bestD) {
                bestD = d;
                bestIdx = i;
            }
        }

        if (bestIdx >= 0) {
            Object name = wd.villagesList.names.get(bestIdx);
            return name != null ? name.toString() : null;
        }

        return null;
    }

    private static boolean equalsBlockPos(Point p, BlockPos bp) {
        return p.getiX() == bp.getX() && p.getiY() == bp.getY() && p.getiZ() == bp.getZ();
    }

    public static boolean isLoneBuildingStop(World world, BlockPos center) {
        MillVillageIndex.VillageInfo vi = findExact(world, center);
        return vi != null && !vi.isVillage;
    }

    /**
     * True if the position is within {@code radiusBlocks} of any **village** Town Hall.
     * Lone buildings are ignored.
     */
    public static boolean isNearVillage(World world, BlockPos pos, int radiusBlocks) {
        MillWorldData wd = getWD(world);
        if (wd == null || wd.villagesList == null || wd.villagesList.pos == null) return false;

        final long r2 = (long) radiusBlocks * (long) radiusBlocks;

        // 1) 3D check against saved Town Hall positions
        for (Point p : wd.villagesList.pos) {
            long dx = p.getiX() - pos.getX();
            long dy = p.getiY() - pos.getY();
            long dz = p.getiZ() - pos.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }

        // 2) 2D fallback (Y in Millénaire data can differ a lot)
        for (Point p : wd.villagesList.pos) {
            long dx = p.getiX() - pos.getX();
            long dz = p.getiZ() - pos.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /**
     * Variant that treats **either** a village TH or a lone building as “near”.
     * Use if you want travellers to also appear near lone buildings.
     */
    public static boolean isNearAnySettlement(World world, BlockPos pos, int radiusBlocks) {
        MillWorldData wd = getWD(world);
        if (wd == null) return false;

        final long r2 = (long) radiusBlocks * (long) radiusBlocks;

        // Villages (3D)
        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (Point p : wd.villagesList.pos) {
                long dx = p.getiX() - pos.getX();
                long dy = p.getiY() - pos.getY();
                long dz = p.getiZ() - pos.getZ();
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
        }
        // Lone buildings (3D)
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (Point p : wd.loneBuildingsList.pos) {
                long dx = p.getiX() - pos.getX();
                long dy = p.getiY() - pos.getY();
                long dz = p.getiZ() - pos.getZ();
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
        }
        return false;
    }

    public static boolean hasVillageData(World world) {
        MillWorldData wd = getWD(world);
        return wd != null && wd.villagesList != null && wd.villagesList.pos != null && !wd.villagesList.pos.isEmpty();
    }

    /**
     * Lightweight view of a village (Town Hall).
     */
    public static final class VillageInfo {
        public final BlockPos center;
        public final boolean isVillage;     // true = village, false = lone building
        public final String name;
        public final String typeKey;
        public final String cultureKey;

        VillageInfo(BlockPos c, boolean v, String n, String t, String cu) {
            this.center = c;
            this.isVillage = v;
            this.name = n;
            this.typeKey = t;
            this.cultureKey = cu;
        }
    }
}



