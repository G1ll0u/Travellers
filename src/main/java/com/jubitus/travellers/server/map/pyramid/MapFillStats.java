package com.jubitus.travellers.server.map.pyramid;

/**
 * Debug counters for level-0 worldmap filling (chunk painting, background sweep, pushes, tile
 * compression). Read by {@code /travellersdebug map stats}. Server thread only, except the store
 * compression counters written by the map IO thread.
 */
public final class MapFillStats {

    private MapFillStats() {
    }

    // Chunk painting (chunk reader, sweep and resweep alike)
    static long painted;
    static long unchanged;
    static long paintNanos;

    // Background sweep
    static long sweepProcessed;
    static long sweepChanged;
    static long sweepPasses;
    static long sweepLastPassTicks = -1;
    static int sweepLastPassChunks;
    static long sweepNanos;

    // Pushes to players
    static long pushSentLevel0;
    static long pushSentUpper;
    static long pushCoalesced;
    static long pushDeflateNanos;

    // Tile store compression (off the server thread since the chunk reader rework)
    static volatile long storeDeflateNanos;
    static volatile long storeDeflateCount;

    public static void reset() {
        painted = unchanged = paintNanos = 0;
        sweepProcessed = sweepChanged = sweepPasses = sweepNanos = 0;
        sweepLastPassTicks = -1;
        sweepLastPassChunks = 0;
        pushSentLevel0 = pushSentUpper = pushCoalesced = pushDeflateNanos = 0;
        storeDeflateNanos = storeDeflateCount = 0;
    }

    public static long painted() { return painted; }
    public static long unchanged() { return unchanged; }
    public static long paintNanos() { return paintNanos; }
    public static long sweepProcessed() { return sweepProcessed; }
    public static long sweepChanged() { return sweepChanged; }
    public static long sweepPasses() { return sweepPasses; }
    public static long sweepLastPassTicks() { return sweepLastPassTicks; }
    public static int sweepLastPassChunks() { return sweepLastPassChunks; }
    public static long sweepNanos() { return sweepNanos; }
    public static long pushSentLevel0() { return pushSentLevel0; }
    public static long pushSentUpper() { return pushSentUpper; }
    public static long pushCoalesced() { return pushCoalesced; }
    public static long pushDeflateNanos() { return pushDeflateNanos; }
    public static long storeDeflateNanos() { return storeDeflateNanos; }
    public static long storeDeflateCount() { return storeDeflateCount; }

    /** Hole census of one level-0 tile, per chunk (16x16 chunks of 16x16 pixels). */
    public static final class HoleCensus {
        public int fullChunks;
        public int emptyChunks;
        public int partialChunks;
        /** Partial chunks whose missing pixels are all on the last column and/or last row. */
        public int edgeOnlyPartialChunks;
        public int missingPixelsInPartial;

        public void add(HoleCensus o) {
            fullChunks += o.fullChunks;
            emptyChunks += o.emptyChunks;
            partialChunks += o.partialChunks;
            edgeOnlyPartialChunks += o.edgeOnlyPartialChunks;
            missingPixelsInPartial += o.missingPixelsInPartial;
        }
    }

    /** Classifies every chunk of a level-0 tile as full, empty or partial (0 = unknown pixel). */
    public static HoleCensus census(byte[] tile) {
        HoleCensus c = new HoleCensus();
        final int px = MapTileGenerator.TILE_PX;
        final int chunks = WorldMapDiscovery.CHUNKS_PER_TILE;
        if (tile == null || tile.length != MapTileGenerator.TILE_BYTES) {
            c.emptyChunks = chunks * chunks;
            return c;
        }
        for (int cz = 0; cz < chunks; cz++) {
            for (int cx = 0; cx < chunks; cx++) {
                int missing = 0;
                boolean edgeOnly = true;
                for (int dz = 0; dz < 16; dz++) {
                    int row = (cz * 16 + dz) * px + cx * 16;
                    for (int dx = 0; dx < 16; dx++) {
                        if (tile[row + dx] != 0) continue;
                        missing++;
                        if (dx != 15 && dz != 15) edgeOnly = false;
                    }
                }
                if (missing == 0) {
                    c.fullChunks++;
                } else if (missing == 256) {
                    c.emptyChunks++;
                } else {
                    c.partialChunks++;
                    c.missingPixelsInPartial += missing;
                    if (edgeOnly) c.edgeOnlyPartialChunks++;
                }
            }
        }
        return c;
    }
}
