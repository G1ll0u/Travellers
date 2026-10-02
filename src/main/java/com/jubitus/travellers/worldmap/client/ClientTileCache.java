package com.jubitus.travellers.worldmap.client;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.worldmap.pyramid.PyramidDirtyPropagator;
import com.jubitus.travellers.worldmap.pyramid.TileKey;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One LRU cache per pyramid level, replacing the old separate normal/detail caches. Because
 * the tile pyramid makes viewport tile-count roughly constant across zoom levels (each level's
 * tiles cover proportionally more world area), a single generous per-level cap comfortably
 * holds several screenfuls at any zoom without evicting the tiles currently in view.
 */
@SideOnly(Side.CLIENT)
public final class ClientTileCache {

    private static final Map<Integer, LinkedHashMap<Long, byte[]>> LEVELS = new java.util.HashMap<>();

    private ClientTileCache() {}

    private static LinkedHashMap<Long, byte[]> levelMap(int level) {
        return LEVELS.computeIfAbsent(level, l -> {
            int cap = TravellersConfig.WORLDMAP.mapClientTileCacheSizePerLevel;
            return new LinkedHashMap<Long, byte[]>(cap, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                    return size() > cap;
                }
            };
        });
    }

    public static void clear() { LEVELS.clear(); }

    public static void put(int level, int tileX, int tileZ, byte[] data) {
        if (data == null) return;
        levelMap(level).put(TileKey.pack(tileX, tileZ), data);
    }

    public static byte[] get(int level, long key) { return levelMap(level).get(key); }

    public static boolean has(int level, long key) { return levelMap(level).containsKey(key); }

    public static void invalidate(int level, long key) { levelMap(level).remove(key); }

    /** Nearest coarser level (bigger blocks/pixel) than {@code level} that has this tile's world area cached. */
    public static byte[] getCoarserFallback(int level, int worldX, int worldZ) {
        for (int l = level + 1; l <= PyramidDirtyPropagator.MAX_LEVEL; l++) {
            int tileBlocks = com.jubitus.travellers.worldmap.pyramid.MapTileGenerator.TILE_PX * (1 << l);
            int tx = TileKey.floorDiv(worldX, tileBlocks);
            int tz = TileKey.floorDiv(worldZ, tileBlocks);
            byte[] tile = get(l, TileKey.pack(tx, tz));
            if (tile != null) return tile;
        }
        return null;
    }
}
