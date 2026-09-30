package com.jubitus.travellers.world.pathing.terrain;

/**
 * Shared geometry and constants for the terrain profile grid (Plan A).
 *
 * <p>The grid is a coarse, per-dimension height/water/roughness map used only to plan offscreen
 * traveller routes around large obstacles (mountain ranges, deep ocean). It never force-loads a
 * chunk and never touches block precision - fine relief below one cell is invisible here and stays
 * the loaded pathfinder's problem.
 *
 * <p>{@link #CELL_SIZE} and {@link #REGION_CELLS} are compile-time constants on purpose: changing
 * either invalidates every {@code .tpg} region file on disk, so they are not config fields.
 */
public final class TerrainProfile {

    /** One cell == one chunk (16 blocks). */
    public static final int CELL_SIZE = 16;

    /** A region is REGION_CELLS x REGION_CELLS cells (512 = 8192 blocks). Unit of file + LRU + array. */
    public static final int REGION_CELLS = 512;

    public static final int REGION_BLOCKS = CELL_SIZE * REGION_CELLS; // 8192
    public static final int CELLS_PER_REGION = REGION_CELLS * REGION_CELLS; // 262144

    /** {@code source} byte values. */
    public static final byte SOURCE_EMPTY = 0;
    public static final byte SOURCE_BIOME_EST = 1;
    public static final byte SOURCE_REAL_SCAN = 2;

    /** Sentinel returned by grid reads when the region is not resident. */
    public static final short BIOME_IX_UNKNOWN = -1;

    private TerrainProfile() {
    }

    // ------------------------------------------------------------------
    // Coordinate math (pure, allocation-free)
    // ------------------------------------------------------------------

    /** World block X/Z -> cell (chunk) coordinate. */
    public static int cellCoord(int worldXZ) {
        return Math.floorDiv(worldXZ, CELL_SIZE);
    }

    /** Cell coordinate -> region coordinate. */
    public static int regionCoord(int cellXZ) {
        return Math.floorDiv(cellXZ, REGION_CELLS);
    }

    /** Packs a region (rx, rz) into a single long key. */
    public static long regionKey(int regionX, int regionZ) {
        return ((long) regionX & 0xFFFFFFFFL) | ((long) regionZ << 32);
    }

    public static int regionKeyX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int regionKeyZ(long key) {
        return (int) (key >> 32);
    }

    /** Region key for a cell coordinate pair. */
    public static long regionKeyForCell(int cellX, int cellZ) {
        return regionKey(regionCoord(cellX), regionCoord(cellZ));
    }

    /** Local cell index (0..CELLS_PER_REGION-1) within its region, for a cell coordinate pair. */
    public static int localIndex(int cellX, int cellZ) {
        int lx = Math.floorMod(cellX, REGION_CELLS);
        int lz = Math.floorMod(cellZ, REGION_CELLS);
        return lz * REGION_CELLS + lx;
    }
}
