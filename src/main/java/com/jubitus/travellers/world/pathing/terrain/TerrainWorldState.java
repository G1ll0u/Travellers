package com.jubitus.travellers.world.pathing.terrain;

import com.jubitus.travellers.config.TravellersConfig;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension owner of the {@link TerrainProfileGrid} (and, from Phase 2, the chunk scanner queue).
 * Created on {@code WorldEvent.Load} by {@link TerrainPersistenceEvents}, ticked from
 * {@code TravellerChunkManager.onWorldTick}, disposed (flushed) on {@code WorldEvent.Unload}.
 *
 * <p>The {@link BiomeIndex} is one table per save (the biome registry is global), so it is a
 * process-wide singleton here, rebuilt if the save root changes (integrated server loading a
 * different world in the same JVM).
 */
public final class TerrainWorldState {

    private static final Map<Integer, TerrainWorldState> STATES = new ConcurrentHashMap<>();

    private static BiomeIndex biomeIndex;
    private static String biomeIndexRoot;

    private final int dimension;
    private final TerrainProfileGrid grid;
    private long lastFlushCheckTick = Long.MIN_VALUE;

    /** Measured chunks whose region file is still loading: chunk key -> packed sample. */
    private final Long2LongOpenHashMap heldSamples = new Long2LongOpenHashMap();

    private long scanned;

    private TerrainWorldState(WorldServer world) {
        this.dimension = world.provider.getDimension();
        File root = terrainRoot(world);
        BiomeIndex idx = sharedBiomeIndex(root);
        this.grid = new TerrainProfileGrid(dimension, new File(root, "dim" + dimension), idx);
    }

    /** Gets (creating if needed) the state for a world. Returns {@code null} while planned routes are off. */
    @Nullable
    public static TerrainWorldState get(WorldServer world) {
        if (!TravellersConfig.ROUTING.enablePlannedRoutes) return null;
        return STATES.computeIfAbsent(world.provider.getDimension(), d -> new TerrainWorldState(world));
    }

    @Nullable
    public static TerrainWorldState getExisting(int dimension) {
        return STATES.get(dimension);
    }

    /** Flushes and drops the state for a dimension (world unload). */
    public static void remove(int dimension) {
        TerrainWorldState s = STATES.remove(dimension);
        if (s != null) s.grid.flushAllSync();
    }

    /** Server stop: flush every dimension and forget the shared biome index. */
    public static void disposeAll() {
        for (TerrainWorldState s : STATES.values()) s.grid.flushAllSync();
        STATES.clear();
        synchronized (TerrainWorldState.class) {
            biomeIndex = null;
            biomeIndexRoot = null;
        }
    }

    public TerrainProfileGrid getGrid() {
        return grid;
    }

    public void tick(WorldServer world, long now) {
        boolean periodic = lastFlushCheckTick == Long.MIN_VALUE || now - lastFlushCheckTick >= 40L;
        if (grid.drainPendingLoads() > 0 || (periodic && !heldSamples.isEmpty())) {
            storeHeldSamples(world);
        }
        if (periodic) {
            lastFlushCheckTick = now;
            grid.flushDue(now, TravellersConfig.ROUTING.terrainFlushIntervalTicks);
        }
    }

    public int heldSampleCount() {
        return heldSamples.size();
    }

    public long scannedCount() {
        return scanned;
    }

    /** Measures a chunk read by {@code ChunkReader}; the sample is held until its region file is loaded. */
    public void acceptChunk(WorldServer world, Chunk chunk) {
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        long sample = TerrainChunkScanner.measure(world, chunk, grid.getBiomeIndex());
        scanned++;
        if (!TerrainChunkScanner.store(world, grid, chunk.x, chunk.z, sample)) {
            heldSamples.put(key, sample);
        }
    }

    private static int chunkX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    private static int chunkZ(long key) {
        return (int) (key >>> 32);
    }

    private void storeHeldSamples(WorldServer world) {
        ObjectIterator<Long2LongMap.Entry> it = heldSamples.long2LongEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2LongMap.Entry e = it.next();
            long key = e.getLongKey();
            if (TerrainChunkScanner.store(world, grid, chunkX(key), chunkZ(key), e.getLongValue())) {
                it.remove();
            }
        }
    }

    /** Flush all dirty regions now (async) - called on {@code WorldEvent.Save}. */
    public void flushDirtyAsync(long now) {
        grid.flushDue(now, 0);
    }

    // ------------------------------------------------------------------

    private static synchronized BiomeIndex sharedBiomeIndex(File root) {
        String path = root.getAbsolutePath();
        if (biomeIndex == null || !path.equals(biomeIndexRoot)) {
            biomeIndex = BiomeIndex.buildOrLoad(root);
            biomeIndexRoot = path;
        }
        return biomeIndex;
    }

    private static File terrainRoot(WorldServer world) {
        File worldDir = world.getSaveHandler().getWorldDirectory();
        return new File(worldDir, "travellers/terrainprofile");
    }
}
