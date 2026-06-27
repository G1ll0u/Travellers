package com.jubitus.travellers.client.map;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientDetailTileCache {
    private static final int MAX_TILES = 64; // 64 × 64KB = 4MB max

    private static final java.util.LinkedHashMap<Long, byte[]> tiles =
            new java.util.LinkedHashMap<Long, byte[]>(MAX_TILES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, byte[]> eldest) {
                    return size() > MAX_TILES;
                }
            };

    private ClientDetailTileCache() {}

    public static void clear() { tiles.clear(); }
    public static void put(long key, byte[] data) { if (data != null) tiles.put(key, data); }
    public static byte[] get(long key) { return tiles.get(key); }
    public static boolean has(long key) { return tiles.containsKey(key); }
}
