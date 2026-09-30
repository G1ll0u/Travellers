package com.jubitus.travellers.server.map.pyramid;

import net.minecraft.world.World;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One instance per (world, pyramid level) — 256x256 tone tiles, 1 pixel = {@code 2^level}
 * blocks. Level 0 is sampled directly from the world; every other level is a pure downsample
 * of the level below it (see {@link MapTileGenerator#downsample} / {@link PyramidDirtyPropagator}).
 *
 * Deliberately NOT a {@code WorldSavedData}: vanilla's {@code MapStorage.saveAllData()} writes
 * every dirty entry as gzip-compressed NBT synchronously on the calling (server) thread at
 * every save-all — on a large world with many tiles that alone was blocking the game for
 * several seconds. Persistence here is instead a plain framed file per level
 * ({@link PyramidFileIO}), written on a background thread ({@link PyramidIOExecutor}),
 * triggered by {@link MapPyramidPersistenceEvents} instead of the vanilla save pipeline.
 */
public class MapPyramidStore {
    private static final Map<World, Map<Integer, MapPyramidStore>> REGISTRY = new WeakHashMap<>();

    private final Map<Long, byte[]> tiles = new HashMap<>();
    // Compressed bytes of tiles unchanged since they were last compressed.
    private final Map<Long, byte[]> compressedCache = new HashMap<>();
    // Tiles changed since last compressed -> change stamp; compressed lazily (save thread or first send).
    private final Map<Long, Long> staleStamps = new HashMap<>();
    private long changeCounter;
    private final int level;
    private int migrationVersion;
    private boolean dirty;

    private MapPyramidStore(int level) {
        this.level = level;
    }

    // The worldmap only covers the overworld.
    public static final int MAP_DIMENSION = 0;

    public static boolean isMapped(World world) {
        return world.provider.getDimension() == MAP_DIMENSION;
    }

    public static synchronized MapPyramidStore get(World world, int level) {
        Map<Integer, MapPyramidStore> perLevel = REGISTRY.computeIfAbsent(world, w -> new HashMap<>());
        return perLevel.computeIfAbsent(level, l -> load(world, l));
    }

    private static MapPyramidStore load(World world, int level) {
        MapPyramidStore store = new MapPyramidStore(level);
        File file = fileFor(world, level);
        PyramidFileIO.Loaded loaded = PyramidFileIO.read(file);
        store.migrationVersion = loaded.migrationVersion;
        int discarded = 0;
        for (Map.Entry<Long, byte[]> e : loaded.compressedTiles.entrySet()) {
            byte[] raw;
            try {
                raw = TileCompression.inflate(e.getValue(), MapTileGenerator.TILE_BYTES);
            } catch (RuntimeException ex) {
                // PyramidFileIO.read() only protects the file's framing (ints/lengths); an individual
                // tile's compressed payload can still be corrupt on its own (e.g. a torn write from a
                // crash) while the framing around it still parses fine. Tile data is cosmetic and
                // regenerable, so drop just this one tile rather than failing the whole world load —
                // it repaints itself the next time its chunk loads.
                discarded++;
                continue;
            }
            store.compressedCache.put(e.getKey(), e.getValue());
            store.tiles.put(e.getKey(), raw);
        }
        if (discarded > 0) {
            System.err.println("[Travellers] Discarded " + discarded + " corrupt worldmap tile(s) from " + file);
        }
        return store;
    }

    public int getLevel() { return level; }

    public synchronized int getMigrationVersion() { return migrationVersion; }

    public synchronized void setMigrationVersion(int value) {
        this.migrationVersion = value;
        dirty = true;
    }

    public synchronized boolean has(long key) {
        return tiles.containsKey(key);
    }

    public synchronized java.util.Set<Long> keySet() {
        return new java.util.HashSet<>(tiles.keySet());
    }

    public synchronized byte[] get(long key) {
        return tiles.get(key);
    }

    public synchronized void put(long key, byte[] data) {
        tiles.put(key, data);
        compressedCache.remove(key);
        staleStamps.put(key, ++changeCounter);
        dirty = true;
    }

    /** Compressed bytes of a tile (compressed now if it changed since), or null if the tile doesn't exist. */
    public synchronized byte[] getCompressed(long key) {
        byte[] raw = tiles.get(key);
        if (raw == null) return null;
        byte[] c = compressedCache.get(key);
        if (c == null) {
            c = deflateCounted(raw);
            compressedCache.put(key, c);
            staleStamps.remove(key);
        }
        return c;
    }

    // Keeps the save thread's compression unless the tile changed again meanwhile.
    private synchronized void adoptCompressed(long key, long stamp, byte[] compressed) {
        Long current = staleStamps.get(key);
        if (current != null && current == stamp) {
            compressedCache.put(key, compressed);
            staleStamps.remove(key);
        }
    }

    private static byte[] deflateCounted(byte[] raw) {
        long t0 = System.nanoTime();
        byte[] c = TileCompression.deflate(raw);
        MapFillStats.storeDeflateNanos += System.nanoTime() - t0;
        MapFillStats.storeDeflateCount++;
        return c;
    }

    /** Snapshots dirty data (raw copies of changed tiles) and hands compression + disk write to a background thread. */
    synchronized void saveAsync(World world) {
        if (!dirty) return;
        dirty = false;
        Map<Long, byte[]> snapshot = new HashMap<>(compressedCache);
        Map<Long, byte[]> staleRaw = new HashMap<>();
        Map<Long, Long> stamps = new HashMap<>(staleStamps);
        for (Long key : stamps.keySet()) staleRaw.put(key, tiles.get(key).clone());
        int mv = migrationVersion;
        File file = fileFor(world, level);
        PyramidIOExecutor.submit(() -> {
            Map<Long, byte[]> fresh = new HashMap<>();
            for (Map.Entry<Long, byte[]> e : staleRaw.entrySet()) fresh.put(e.getKey(), deflateCounted(e.getValue()));
            snapshot.putAll(fresh);
            PyramidFileIO.write(file, snapshot, mv);
            for (Map.Entry<Long, byte[]> e : fresh.entrySet()) {
                adoptCompressed(e.getKey(), stamps.get(e.getKey()), e.getValue());
            }
        });
    }

    /** Synchronous flush — only for a clean world unload/server stop, never during normal play. */
    synchronized void saveSync(World world) {
        if (!dirty) return;
        dirty = false;
        for (Long key : new java.util.ArrayList<>(staleStamps.keySet())) getCompressed(key);
        PyramidFileIO.write(fileFor(world, level), new HashMap<>(compressedCache), migrationVersion);
    }

    private static File fileFor(World world, int level) {
        File worldDir = world.getSaveHandler().getWorldDirectory();
        File dir = new File(worldDir, "travellers/mapdata/dim" + world.provider.getDimension());
        return new File(dir, "L" + level + ".dat");
    }

    /** Flushes every dirty level of the given world asynchronously. Called on {@code WorldEvent.Save}. */
    static void saveAllDirtyAsync(World world) {
        Map<Integer, MapPyramidStore> perLevel;
        synchronized (MapPyramidStore.class) {
            perLevel = REGISTRY.get(world);
        }
        if (perLevel == null) return;
        for (MapPyramidStore store : perLevel.values()) store.saveAsync(world);
    }

    /** Waits for any in-flight background tile writes to finish. Call once, at server shutdown. */
    public static void shutdownIOAndAwait() {
        PyramidIOExecutor.shutdownAndAwait();
    }

    /** Flushes every dirty level of the given world synchronously. Called on world unload/server stop. */
    static void flushAllSync(World world) {
        Map<Integer, MapPyramidStore> perLevel;
        synchronized (MapPyramidStore.class) {
            perLevel = REGISTRY.get(world);
        }
        if (perLevel == null) return;
        for (MapPyramidStore store : perLevel.values()) store.saveSync(world);
    }
}
