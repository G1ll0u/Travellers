package com.jubitus.travellers.pathing.terrain;

/**
 * One region of the terrain profile grid: {@link TerrainProfile#REGION_CELLS} squared cells stored
 * as parallel struct-of-arrays. About 1.75 MB resident.
 *
 * <p><b>Threading discipline.</b> A region is filled entirely on the server thread (region load,
 * {@code ensureEstimated}, the chunk scanner), then published to {@link TerrainProfileGrid}'s
 * concurrent map. Publication establishes a happens-before edge; after that the arrays are only
 * read, so the plain array gets below are safe from any thread. The one field mutated after
 * publication is {@link #dirty} / {@link #lastFlushTick}, and only from the server thread.
 */
public final class TerrainProfileRegion {

    public final int regionX;
    public final int regionZ;

    /** Mean surface Y of the cell's columns (vanilla heightmap - counts liquids, so a water cell reads the water surface). */
    public final short[] heightMean;
    /** (maxY - minY) across the cell, clamped 0..255 - roughness. */
    public final byte[] amplitude;
    /** Fraction of columns whose surface block is liquid, 0..255. */
    public final byte[] water;
    /** {@link TerrainProfile#SOURCE_EMPTY} / SOURCE_BIOME_EST / SOURCE_REAL_SCAN. */
    public final byte[] source;
    /** {@link BiomeIndex} id, or {@link TerrainProfile#BIOME_IX_UNKNOWN}. */
    public final short[] biomeIx;

    public boolean dirty;
    public long lastFlushTick = Long.MIN_VALUE;

    public TerrainProfileRegion(int regionX, int regionZ) {
        this(regionX, regionZ,
                new short[TerrainProfile.CELLS_PER_REGION],
                new byte[TerrainProfile.CELLS_PER_REGION],
                new byte[TerrainProfile.CELLS_PER_REGION],
                new byte[TerrainProfile.CELLS_PER_REGION],
                newUnknownBiomeArray());
    }

    TerrainProfileRegion(int regionX, int regionZ, short[] heightMean, byte[] amplitude,
                         byte[] water, byte[] source, short[] biomeIx) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.heightMean = heightMean;
        this.amplitude = amplitude;
        this.water = water;
        this.source = source;
        this.biomeIx = biomeIx;
    }

    private static short[] newUnknownBiomeArray() {
        short[] a = new short[TerrainProfile.CELLS_PER_REGION];
        java.util.Arrays.fill(a, TerrainProfile.BIOME_IX_UNKNOWN);
        return a;
    }

    /** Writes one cell (server thread) and marks the region dirty. */
    public void putCell(int localIndex, int heightMeanY, int amp, int water255, byte source, short biomeIx) {
        this.heightMean[localIndex] = (short) heightMeanY;
        this.amplitude[localIndex] = (byte) clamp255(amp);
        this.water[localIndex] = (byte) clamp255(water255);
        this.source[localIndex] = source;
        this.biomeIx[localIndex] = biomeIx;
        this.dirty = true;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** Unsigned view of the {@code amplitude}/{@code water} bytes. */
    public static int u8(byte b) {
        return b & 0xFF;
    }

    /** Immutable copy of the five arrays, taken on the server thread before an async write. */
    Snapshot snapshot() {
        return new Snapshot(regionX, regionZ,
                heightMean.clone(), amplitude.clone(), water.clone(), source.clone(), biomeIx.clone());
    }

    static final class Snapshot {
        final int regionX;
        final int regionZ;
        final short[] heightMean;
        final byte[] amplitude;
        final byte[] water;
        final byte[] source;
        final short[] biomeIx;

        Snapshot(int regionX, int regionZ, short[] heightMean, byte[] amplitude,
                 byte[] water, byte[] source, short[] biomeIx) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.heightMean = heightMean;
            this.amplitude = amplitude;
            this.water = water;
            this.source = source;
            this.biomeIx = biomeIx;
        }
    }
}
