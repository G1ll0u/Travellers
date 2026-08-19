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
    private final Map<Long, byte[]> compressedCache = new HashMap<>();
    private final int level;
    private int migrationVersion;
    private boolean dirty;

    private MapPyramidStore(int level) {
        this.level = level;
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
        compressedCache.put(key, TileCompression.deflate(data));
        dirty = true;
    }

    /** Snapshots dirty data and hands the actual disk write off to a background thread. */
    synchronized void saveAsync(World world) {
        if (!dirty) return;
        dirty = false;
        Map<Long, byte[]> snapshot = new HashMap<>(compressedCache);
        int mv = migrationVersion;
        File file = fileFor(world, level);
        PyramidIOExecutor.submit(() -> PyramidFileIO.write(file, snapshot, mv));
    }

    /** Synchronous flush — only for a clean world unload/server stop, never during normal play. */
    synchronized void saveSync(World world) {
        if (!dirty) return;
        dirty = false;
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
