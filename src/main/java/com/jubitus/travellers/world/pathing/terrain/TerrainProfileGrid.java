package com.jubitus.travellers.world.pathing.terrain;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sparse {@code region key -> region} map with an LRU cap, one per dimension. Held by
 * {@link TerrainWorldState}.
 *
 * <p><b>Reads</b> ({@code getSource/getHeight/getWater/getAmplitude/getBiomeIx}) are thread-safe and
 * return a pessimistic sentinel when the region is not resident. <b>Writes and lifecycle</b>
 * ({@link #ensureResident}, {@link #ensureEstimated}, {@link #putRealCell}, {@link #drainPendingLoads},
 * {@link #flushDue}, {@link #flushAllSync}) are server-thread only.
 *
 * <p>A region miss never blocks: {@link #ensureResident} fires an async load; the loaded region is
 * published on a later server tick via {@link #drainPendingLoads}. Until then reads see the sentinel.
 */
public final class TerrainProfileGrid {

    private final int dimension;
    private final File dimDir;
    private final BiomeIndex biomeIndex;

    private final ConcurrentHashMap<Long, TerrainProfileRegion> resident = new ConcurrentHashMap<>();
    private final java.util.Set<Long> loadRequested = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<TerrainProfileRegion> pendingPublish = new ConcurrentLinkedQueue<>();

    /** Access-order LRU tracker (server thread only). Value unused. */
    private final LinkedHashMap<Long, Boolean> lru = new LinkedHashMap<Long, Boolean>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return false; // eviction handled explicitly in publishRegion so we can flush-if-dirty
        }
    };

    public TerrainProfileGrid(int dimension, File dimDir, BiomeIndex biomeIndex) {
        this.dimension = dimension;
        this.dimDir = dimDir;
        this.biomeIndex = biomeIndex;
    }

    public int getDimension() {
        return dimension;
    }

    public BiomeIndex getBiomeIndex() {
        return biomeIndex;
    }

    // ------------------------------------------------------------------
    // Reads (any thread)
    // ------------------------------------------------------------------

    public byte getSource(int worldX, int worldZ) {
        TerrainProfileRegion r = regionForWorld(worldX, worldZ);
        return r == null ? TerrainProfile.SOURCE_EMPTY : r.source[localIdxForWorld(worldX, worldZ)];
    }

    /** Mean surface Y, or {@code Short.MIN_VALUE} sentinel when not resident. */
    public short getHeight(int worldX, int worldZ) {
        TerrainProfileRegion r = regionForWorld(worldX, worldZ);
        return r == null ? Short.MIN_VALUE : r.heightMean[localIdxForWorld(worldX, worldZ)];
    }

    /** Roughness 0..255, or 255 (pessimistic) when not resident. */
    public int getAmplitude(int worldX, int worldZ) {
        TerrainProfileRegion r = regionForWorld(worldX, worldZ);
        return r == null ? 255 : TerrainProfileRegion.u8(r.amplitude[localIdxForWorld(worldX, worldZ)]);
    }

    /** Water fraction 0..255, or 0 when not resident. */
    public int getWater(int worldX, int worldZ) {
        TerrainProfileRegion r = regionForWorld(worldX, worldZ);
        return r == null ? 0 : TerrainProfileRegion.u8(r.water[localIdxForWorld(worldX, worldZ)]);
    }

    public short getBiomeIx(int worldX, int worldZ) {
        TerrainProfileRegion r = regionForWorld(worldX, worldZ);
        return r == null ? TerrainProfile.BIOME_IX_UNKNOWN : r.biomeIx[localIdxForWorld(worldX, worldZ)];
    }

    public boolean isResidentForWorld(int worldX, int worldZ) {
        return regionForWorld(worldX, worldZ) != null;
    }

    public boolean isRegionResidentForCell(int cellX, int cellZ) {
        return resident.containsKey(TerrainProfile.regionKeyForCell(cellX, cellZ));
    }

    private TerrainProfileRegion regionForWorld(int worldX, int worldZ) {
        int cellX = TerrainProfile.cellCoord(worldX);
        int cellZ = TerrainProfile.cellCoord(worldZ);
        return resident.get(TerrainProfile.regionKeyForCell(cellX, cellZ));
    }

    private static int localIdxForWorld(int worldX, int worldZ) {
        return TerrainProfile.localIndex(TerrainProfile.cellCoord(worldX), TerrainProfile.cellCoord(worldZ));
    }

    // ------------------------------------------------------------------
    // Lifecycle (server thread)
    // ------------------------------------------------------------------

    /** Requests an async load for the region covering this cell, if not already resident/requested. */
    public void ensureResident(int cellX, int cellZ) {
        long key = TerrainProfile.regionKeyForCell(cellX, cellZ);
        if (resident.containsKey(key)) return;
        if (!loadRequested.add(key)) return;
        int rx = TerrainProfile.regionKeyX(key);
        int rz = TerrainProfile.regionKeyZ(key);
        TerrainProfileIO.loadAsync(fileFor(rx, rz), rx, rz, pendingPublish::add);
    }

    /**
     * Synchronously reads the region covering this cell and publishes it. Debug-command / test use
     * only - the normal path is {@link #ensureResident} + {@link #drainPendingLoads}, off the server
     * thread.
     */
    public void loadRegionBlocking(int cellX, int cellZ) {
        long key = TerrainProfile.regionKeyForCell(cellX, cellZ);
        if (resident.containsKey(key)) return;
        int rx = TerrainProfile.regionKeyX(key);
        int rz = TerrainProfile.regionKeyZ(key);
        TerrainProfileRegion r;
        try {
            r = TerrainProfileIO.readSync(fileFor(rx, rz), rx, rz);
        } catch (Exception e) {
            r = new TerrainProfileRegion(rx, rz);
        }
        loadRequested.remove(key);
        publishRegion(r);
    }

    /** Publishes any regions whose async load has completed since the last call; returns how many. */
    public int drainPendingLoads() {
        int n = 0;
        TerrainProfileRegion r;
        while ((r = pendingPublish.poll()) != null) {
            long key = TerrainProfile.regionKey(r.regionX, r.regionZ);
            loadRequested.remove(key);
            publishRegion(r);
            n++;
        }
        return n;
    }

    /** True when every region the box touches is resident; requests the missing ones otherwise. */
    public boolean ensureBoxResident(int minX, int minZ, int maxX, int maxZ) {
        boolean all = true;
        int rMinX = TerrainProfile.regionCoord(TerrainProfile.cellCoord(minX));
        int rMaxX = TerrainProfile.regionCoord(TerrainProfile.cellCoord(maxX));
        int rMinZ = TerrainProfile.regionCoord(TerrainProfile.cellCoord(minZ));
        int rMaxZ = TerrainProfile.regionCoord(TerrainProfile.cellCoord(maxZ));
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                if (resident.containsKey(TerrainProfile.regionKey(rx, rz))) continue;
                ensureResident(rx * TerrainProfile.REGION_CELLS, rz * TerrainProfile.REGION_CELLS);
                all = false;
            }
        }
        return all;
    }

    /** Package-private for tests. Adds a region to the resident set and evicts the LRU tail if over cap. */
    void publishRegion(TerrainProfileRegion region) {
        long key = TerrainProfile.regionKey(region.regionX, region.regionZ);
        resident.put(key, region);
        lru.put(key, Boolean.TRUE);
        int cap = com.jubitus.travellers.config.TravellersConfig.ROUTING.terrainMemoryRegionCap;
        while (lru.size() > Math.max(1, cap)) {
            Long eldest = lru.keySet().iterator().next();
            lru.remove(eldest);
            TerrainProfileRegion evicted = resident.remove(eldest);
            if (evicted != null && evicted.dirty) {
                TerrainProfileIO.writeAsync(fileFor(evicted.regionX, evicted.regionZ), evicted.snapshot());
            }
        }
    }

    /**
     * Walks every cell in the world-space box; for each {@code EMPTY} cell of a resident region,
     * fills a biome estimate. Non-resident regions in the box get an async load request and are
     * skipped this pass.
     */
    public void ensureEstimated(World world, int minX, int minZ, int maxX, int maxZ) {
        int cMinX = TerrainProfile.cellCoord(minX);
        int cMinZ = TerrainProfile.cellCoord(minZ);
        int cMaxX = TerrainProfile.cellCoord(maxX);
        int cMaxZ = TerrainProfile.cellCoord(maxZ);
        for (int cz = cMinZ; cz <= cMaxZ; cz++) {
            for (int cx = cMinX; cx <= cMaxX; cx++) {
                long key = TerrainProfile.regionKeyForCell(cx, cz);
                TerrainProfileRegion region = resident.get(key);
                if (region == null) {
                    ensureResident(cx, cz);
                    continue;
                }
                int li = TerrainProfile.localIndex(cx, cz);
                if (region.source[li] != TerrainProfile.SOURCE_EMPTY) continue;
                BiomeTerrainEstimator.Estimate est = BiomeTerrainEstimator.estimateAt(world, cx, cz);
                if (est == null) continue;
                ResourceLocation name = BiomeTerrainEstimator.biomeNameAt(world, cx, cz);
                short bi = name != null ? biomeIndex.idFor(name) : TerrainProfile.BIOME_IX_UNKNOWN;
                region.putCell(li, est.heightMean, est.amplitude, est.water,
                        TerrainProfile.SOURCE_BIOME_EST, bi);
            }
        }
    }

    /**
     * Upgrades one cell to {@code REAL_SCAN} (chunk scanner, Phase 2). Returns {@code false} - and
     * requests an async region load - if the region is not resident yet; the scanner then re-queues
     * the chunk for a later pass.
     */
    public boolean putRealCell(int cellX, int cellZ, int heightMeanY, int amp, int water255, short biomeIx) {
        long key = TerrainProfile.regionKeyForCell(cellX, cellZ);
        TerrainProfileRegion region = resident.get(key);
        if (region == null) {
            ensureResident(cellX, cellZ);
            return false;
        }
        region.putCell(TerrainProfile.localIndex(cellX, cellZ),
                heightMeanY, amp, water255, TerrainProfile.SOURCE_REAL_SCAN, biomeIx);
        return true;
    }

    /**
     * Builds an immutable {@link TerrainGridView} over the box for a route-planner worker. Server
     * thread only. Regions not resident at capture time are simply absent from the view (the worker
     * reads sentinels there); call {@link #ensureEstimated} first so the box is at least biome-filled.
     */
    public TerrainGridView captureView(int minX, int minZ, int maxX, int maxZ) {
        Map<Long, TerrainProfileRegion> captured = new java.util.HashMap<>();
        int rMinX = TerrainProfile.regionCoord(TerrainProfile.cellCoord(minX));
        int rMaxX = TerrainProfile.regionCoord(TerrainProfile.cellCoord(maxX));
        int rMinZ = TerrainProfile.regionCoord(TerrainProfile.cellCoord(minZ));
        int rMaxZ = TerrainProfile.regionCoord(TerrainProfile.cellCoord(maxZ));
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                long key = TerrainProfile.regionKey(rx, rz);
                TerrainProfileRegion r = resident.get(key);
                if (r != null) captured.put(key, r);
            }
        }
        java.util.Set<Short> mountainIds = new java.util.HashSet<>();
        int n = biomeIndex.size();
        for (short id = 0; id < n; id++) {
            if (biomeIndex.isMountainBiome(id)) mountainIds.add(id);
        }
        return new TerrainGridView(captured, mountainIds);
    }

    /** Async-flushes every dirty region whose last flush is older than {@code intervalTicks}. */
    public void flushDue(long now, int intervalTicks) {
        for (TerrainProfileRegion r : resident.values()) {
            if (!r.dirty) continue;
            if (r.lastFlushTick != Long.MIN_VALUE && now - r.lastFlushTick < intervalTicks) continue;
            TerrainProfileIO.writeAsync(fileFor(r.regionX, r.regionZ), r.snapshot());
            r.dirty = false;
            r.lastFlushTick = now;
        }
    }

    /** Synchronous flush of every dirty region - world unload / server stop only. */
    public void flushAllSync() {
        for (TerrainProfileRegion r : resident.values()) {
            if (!r.dirty) continue;
            try {
                TerrainProfileIO.writeSync(fileFor(r.regionX, r.regionZ), r.snapshot());
            } catch (Exception e) {
                System.err.println("[Travellers/Route] terrain flush failed for region "
                        + r.regionX + "," + r.regionZ + ": " + e);
            }
            r.dirty = false;
        }
    }

    // ------------------------------------------------------------------
    // Debug / stats
    // ------------------------------------------------------------------

    public static final class RegionStats {
        public final boolean resident;
        public final boolean dirty;
        public final int empty;
        public final int biomeEst;
        public final int realScan;

        RegionStats(boolean resident, boolean dirty, int empty, int biomeEst, int realScan) {
            this.resident = resident;
            this.dirty = dirty;
            this.empty = empty;
            this.biomeEst = biomeEst;
            this.realScan = realScan;
        }
    }

    public RegionStats statsForCell(int cellX, int cellZ) {
        long key = TerrainProfile.regionKeyForCell(cellX, cellZ);
        TerrainProfileRegion r = resident.get(key);
        if (r == null) return new RegionStats(false, false, TerrainProfile.CELLS_PER_REGION, 0, 0);
        int empty = 0, est = 0, real = 0;
        for (byte s : r.source) {
            if (s == TerrainProfile.SOURCE_REAL_SCAN) real++;
            else if (s == TerrainProfile.SOURCE_BIOME_EST) est++;
            else empty++;
        }
        return new RegionStats(true, r.dirty, empty, est, real);
    }

    public List<Long> residentKeys() {
        return new ArrayList<>(resident.keySet());
    }

    public int residentCount() {
        return resident.size();
    }

    private File fileFor(int rx, int rz) {
        return new File(dimDir, "r." + rx + "." + rz + ".tpg");
    }
}
