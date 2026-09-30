package com.jubitus.travellers.world.pathing.terrain;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable read view of the terrain profile grid over one bounded box, captured on the server
 * thread and handed to a route-planner worker. Holds only published (read-only) region arrays and a
 * pre-resolved mountain-biome id set - no {@code World}/{@code Chunk}/{@code Biome}/{@code Entity}
 * reference - so the worker touches nothing live (performance contract §2.2).
 *
 * <p>Reads outside the captured regions, or into a region that was not resident at capture time,
 * return the same pessimistic sentinels as {@link TerrainProfileGrid}.
 */
public final class TerrainGridView {

    private final Map<Long, TerrainProfileRegion> regions;
    private final Set<Short> mountainBiomeIds;

    TerrainGridView(Map<Long, TerrainProfileRegion> regions, Set<Short> mountainBiomeIds) {
        this.regions = regions;
        this.mountainBiomeIds = mountainBiomeIds;
    }

    /** Test factory. */
    public static TerrainGridView forTest(Map<Long, TerrainProfileRegion> regions, Set<Short> mountainBiomeIds) {
        return new TerrainGridView(new HashMap<>(regions),
                mountainBiomeIds == null ? Collections.emptySet() : new HashSet<>(mountainBiomeIds));
    }

    private TerrainProfileRegion regionFor(int worldX, int worldZ) {
        int cx = TerrainProfile.cellCoord(worldX);
        int cz = TerrainProfile.cellCoord(worldZ);
        return regions.get(TerrainProfile.regionKeyForCell(cx, cz));
    }

    private static int localIdx(int worldX, int worldZ) {
        return TerrainProfile.localIndex(TerrainProfile.cellCoord(worldX), TerrainProfile.cellCoord(worldZ));
    }

    public byte source(int worldX, int worldZ) {
        TerrainProfileRegion r = regionFor(worldX, worldZ);
        return r == null ? TerrainProfile.SOURCE_EMPTY : r.source[localIdx(worldX, worldZ)];
    }

    /** Mean surface Y, or {@code Short.MIN_VALUE} when unknown. */
    public short height(int worldX, int worldZ) {
        TerrainProfileRegion r = regionFor(worldX, worldZ);
        return r == null ? Short.MIN_VALUE : r.heightMean[localIdx(worldX, worldZ)];
    }

    /** Roughness 0..255, pessimistic 255 when unknown. */
    public int amplitude(int worldX, int worldZ) {
        TerrainProfileRegion r = regionFor(worldX, worldZ);
        return r == null ? 255 : TerrainProfileRegion.u8(r.amplitude[localIdx(worldX, worldZ)]);
    }

    /** Water fraction 0..255, 0 when unknown. */
    public int water(int worldX, int worldZ) {
        TerrainProfileRegion r = regionFor(worldX, worldZ);
        return r == null ? 0 : TerrainProfileRegion.u8(r.water[localIdx(worldX, worldZ)]);
    }

    public short biomeIx(int worldX, int worldZ) {
        TerrainProfileRegion r = regionFor(worldX, worldZ);
        return r == null ? TerrainProfile.BIOME_IX_UNKNOWN : r.biomeIx[localIdx(worldX, worldZ)];
    }

    public boolean isMountain(int worldX, int worldZ) {
        return mountainBiomeIds.contains(biomeIx(worldX, worldZ));
    }

    public boolean hasAnyData() {
        return !regions.isEmpty();
    }
}
