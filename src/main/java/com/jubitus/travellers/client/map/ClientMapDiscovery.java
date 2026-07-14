package com.jubitus.travellers.client.map;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientMapDiscovery {
    private static final java.util.Set<Long> tiles = new java.util.HashSet<>();
    private static volatile boolean fullMap = false;

    private ClientMapDiscovery() {}

    public static void clear() {
        tiles.clear();
    }

    public static void setFullMap(boolean value) {
        fullMap = value;
    }

    public static boolean isFullMap() {
        return fullMap;
    }

    /** Full replace (use only when you KNOW you want to reset). */
    public static void replaceAll(long[] keys) {
        tiles.clear();
        mergeAll(keys);
    }

    /** Merge/union: never removes tiles (prevents race wiping). */
    public static void mergeAll(long[] keys) {
        if (keys == null) return;
        for (long k : keys) tiles.add(k);
    }

    public static void addTile(int tileX, int tileZ) {
        long key = (((long) tileX) << 32) ^ (tileZ & 0xFFFFFFFFL);
        tiles.add(key);
    }

    public static java.util.Set<Long> getTilesView() {
        return java.util.Collections.unmodifiableSet(tiles);
    }
}

