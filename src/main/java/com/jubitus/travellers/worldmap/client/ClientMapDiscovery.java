package com.jubitus.travellers.worldmap.client;

import com.jubitus.travellers.worldmap.pyramid.MapDiscoverySavedData;
import com.jubitus.travellers.worldmap.pyramid.TileKey;
import com.jubitus.travellers.worldmap.pyramid.WorldMapDiscovery;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** The local player's fog of war: one bit per discovered chunk, grouped by level-0 tile. Client thread only. */
@SideOnly(Side.CLIENT)
public final class ClientMapDiscovery {
    private static final Long2ObjectOpenHashMap<long[]> MASKS = new Long2ObjectOpenHashMap<>();
    private static boolean fullMap = false;

    // One-entry lookup cache: consecutive queries almost always hit the same tile.
    private static long lastKey = Long.MIN_VALUE;
    private static long[] lastMask;

    private ClientMapDiscovery() {}

    public static void clear() {
        MASKS.clear();
        lastKey = Long.MIN_VALUE;
        lastMask = null;
    }

    public static boolean isFullMap() {
        return fullMap;
    }

    public static boolean isChunkDiscovered(int chunkX, int chunkZ) {
        if (fullMap) return true;
        int tileX = chunkX >> 4;
        int tileZ = chunkZ >> 4;
        long key = TileKey.pack(tileX, tileZ);
        if (key != lastKey) {
            lastKey = key;
            lastMask = MASKS.get(key);
        }
        if (lastMask == null) return false;
        int bit = (chunkZ & 15) * 16 + (chunkX & 15);
        return (lastMask[bit >> 6] & (1L << (bit & 63))) != 0;
    }

    public static void mergeTile(int tileX, int tileZ, long[] mask) {
        if (merge(TileKey.pack(tileX, tileZ), mask) && !fullMap) invalidateTile(tileX, tileZ);
    }

    public static void mergeAll(long[] keys, long[][] masks, boolean full) {
        boolean fullChanged = full != fullMap;
        fullMap = full;
        for (int i = 0; i < keys.length; i++) {
            if (merge(keys[i], masks[i]) && !fullChanged && !full) {
                invalidateTile(TileKey.unpackX(keys[i]), TileKey.unpackZ(keys[i]));
            }
        }
        if (fullChanged) ClientTileTextures.invalidateAll();
    }

    private static boolean merge(long key, long[] mask) {
        long[] cur = MASKS.get(key);
        if (cur == null) {
            cur = new long[MapDiscoverySavedData.MASK_LONGS];
            MASKS.put(key, cur);
            if (key == lastKey) lastMask = cur;
        }
        boolean changed = false;
        for (int i = 0; i < cur.length; i++) {
            long m = cur[i] | mask[i];
            if (m != cur[i]) {
                cur[i] = m;
                changed = true;
            }
        }
        return changed;
    }

    // The fog edge blends across one chunk, so neighbouring tiles are redrawn too.
    private static void invalidateTile(int tileX, int tileZ) {
        int blocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
        ClientTileTextures.invalidateBlockArea(tileX * blocks - 16, tileZ * blocks - 16,
                (tileX + 1) * blocks + 16, (tileZ + 1) * blocks + 16);
    }
}
