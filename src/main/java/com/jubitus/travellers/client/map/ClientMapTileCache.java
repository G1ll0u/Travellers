package com.jubitus.travellers.client.map;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientMapTileCache {

    // Keep only the most recent tiles in RAM
    private static final int MAX_TILES = 1024; // tune: 512..4096

    private static final java.util.LinkedHashMap<Long, byte[]> tiles =
            new java.util.LinkedHashMap<Long, byte[]>(MAX_TILES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, byte[]> eldest) {
                    return size() > MAX_TILES;
                }
            };

    private ClientMapTileCache() {}

    public static void clear() { tiles.clear(); }

    public static void put(long key, byte[] data) {
        if (data == null) return;
        tiles.put(key, data);
    }

    public static byte[] get(long key) { return tiles.get(key); }

    public static boolean has(long key) { return tiles.containsKey(key); }

    public static java.util.Map<Long, byte[]> view() {
        return java.util.Collections.unmodifiableMap(tiles);
    }
}
