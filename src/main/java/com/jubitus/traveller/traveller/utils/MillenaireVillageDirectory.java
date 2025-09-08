package com.jubitus.traveller.traveller.utils;

import com.jubitus.traveller.TravellersModConfig;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MillenaireVillageDirectory {

    public static final class Entry {
        public final String name;
        public final BlockPos pos;     // center from txt
        public final String type;
        public final String culture;
        public final boolean isVillage;
        Entry(String name, BlockPos pos, String type, String culture, boolean isVillage) {
            this.name = name; this.pos = pos; this.type = type; this.culture = culture; this.isVillage = isVillage;
        }
    }

    private static final Map<BlockPos, Entry> BY_POS = new HashMap<>();
    private static volatile boolean loaded = false;
    private static long villagesMTime = 0L;
    private static long loneMTime = 0L;

    private static File villagesTxt(File folder)     { return new File(folder, "villages.txt"); }
    private static File lonebuildingsTxt(File folder){ return new File(folder, "lonebuildings.txt"); }

    public static synchronized void load(File millenaireFolder) {
        if (millenaireFolder == null) return;
        if (loaded) return;
        doLoad(millenaireFolder);
    }

    public static synchronized void reloadIfStale(File millenaireFolder) {
        if (millenaireFolder == null) return;
        File v = villagesTxt(millenaireFolder);
        File l = lonebuildingsTxt(millenaireFolder);
        long vm = v.exists() ? v.lastModified() : 0L;
        long lm = l.exists() ? l.lastModified() : 0L;
        if (!loaded || vm != villagesMTime || lm != loneMTime) {
            doLoad(millenaireFolder);
        }
    }

    public static synchronized void forceReload(File millenaireFolder) {
        if (millenaireFolder == null) return;
        doLoad(millenaireFolder);
    }

    private static void doLoad(File folder) {
        BY_POS.clear();
        loadFile(villagesTxt(folder), true);
        loadFile(lonebuildingsTxt(folder), false);
        villagesMTime = villagesTxt(folder).exists() ? villagesTxt(folder).lastModified() : 0L;
        loneMTime     = lonebuildingsTxt(folder).exists() ? lonebuildingsTxt(folder).lastModified() : 0L;
        loaded = true;
    }

    private static void loadFile(File file, boolean villageFlag) {
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // Format: Name;X/Y/Z;type;culture;
                String[] parts = line.split(";");
                if (parts.length < 2) continue;

                String name = parts[0];
                String[] xyz = parts[1].split("/");
                if (xyz.length != 3) continue;

                int x = parseIntSafe(xyz[0]);
                int y = parseIntSafe(xyz[1]);
                int z = parseIntSafe(xyz[2]);
                BlockPos pos = new BlockPos(x, y, z);

                String type = parts.length > 2 ? parts[2] : "";
                String culture = parts.length > 3 ? parts[3] : "";

                BY_POS.put(pos, new Entry(name, pos, type, culture, villageFlag));
            }
        } catch (IOException ignored) { }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    // --- lookups ---

    @Nullable
    public static Entry findExact(BlockPos pos) {
        return BY_POS.get(pos);
    }

    /** Find nearest by 3D distance within a given RADIUS (in blocks). */
    @Nullable
    public static Entry findNearest(BlockPos pos, int maxRadius) {
        long maxSq = (long) maxRadius * (long) maxRadius;
        Entry best = null;
        long bestD = Long.MAX_VALUE;
        for (Entry e : BY_POS.values()) {
            long d = (long) pos.distanceSq(e.pos);
            if (d <= maxSq && d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** 2D (XZ) nearest within radius — useful if Y differs a lot from the stored center. */
    @Nullable
    public static Entry findNearest2D(BlockPos pos, int maxRadius) {
        long maxSq = (long) maxRadius * (long) maxRadius;
        Entry best = null;
        long bestD = Long.MAX_VALUE;
        int px = pos.getX(), pz = pos.getZ();
        for (Entry e : BY_POS.values()) {
            int dx = e.pos.getX() - px;
            int dz = e.pos.getZ() - pz;
            long d = (long) dx * dx + (long) dz * dz;
            if (d <= maxSq && d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** All village centers parsed from txt (excludes lone buildings). */
    public static List<BlockPos> getVillageCenters() {
        List<BlockPos> out = new ArrayList<>();
        for (Entry e : BY_POS.values()) if (e.isVillage) out.add(e.pos);
        return out;
    }

    /** All centers (villages + lone buildings) if you ever need them. */
    public static List<BlockPos> getAllCenters() {
        return new ArrayList<>(BY_POS.keySet());
    }
    /** Returns the appropriate 'near' radius for this center (village vs lonebuilding). */
    public static int nearRadiusFor(BlockPos center) {
        Entry e = BY_POS.get(center);
        if (e != null && !e.isVillage) {
            return TravellersModConfig.lonebuildingNear;   // lone building
        }
        return TravellersModConfig.villageNear;            // default to village
    }
}

