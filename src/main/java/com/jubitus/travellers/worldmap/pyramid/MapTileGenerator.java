package com.jubitus.travellers.worldmap.pyramid;


/**
 * Samples world terrain into level-0 pyramid tiles (256x256 pixels, 1 pixel = 1 block).
 * Higher pyramid levels are never sampled from the world — they're pure downsamples of the
 * level below (see {@link #downsample}), computed by {@link PyramidDirtyPropagator}.
 */
public final class MapTileGenerator {
    private MapTileGenerator() {}

    public static final int TILE_PX    = 256;
    public static final int TILE_BYTES = TILE_PX * TILE_PX;

    public static final byte TOP_SOLID = 0;
    public static final byte TOP_WATER = 1;
    public static final byte TOP_LAVA = 2;
    /** Solid ground under tree canopy: its height is the ground's, its tone a little darker. */
    public static final byte TOP_FOLIAGE = 3;

    private static final int FOLIAGE_DARKEN = 12;
    // Above the client's water threshold (ClientTileTextures.RenderSettings), so a forest never turns blue
    private static final int FOLIAGE_MIN_TONE = 76;

    // ── Level-0 generation ──────────────────────────────────────────────────

    /**
     * Tones of one chunk (16x16, index z*16+x) from its heightmap and top-block kind. The relief
     * slope uses the east/south column, or the west/north one on the chunk's last column/row, so a
     * chunk never needs its neighbours and always produces the same pixels.
     */
    public static void chunkTones(int[] heights, byte[] topKind, byte[] out) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int h = heights[i];
                int slopeX = x < 15 ? h - heights[i + 1] : heights[i - 1] - h;
                int slopeZ = z < 15 ? h - heights[i + 16] : heights[i - 16] - h;
                out[i] = (byte) tone(h, slopeX, slopeZ, topKind[i]);
            }
        }
    }

    /** Clamped 1-255 tone (0 is the "unknown" sentinel and never returned). */
    static int tone(int height, int slopeX, int slopeZ, byte topKind) {
        int base = clamp(70 + (height * 120 / 256), 60, 200);
        if (height > 110) base = clamp(base + 18, 60, 218);
        if (height > 140) base = clamp(base + 25, 60, 238);
        if (height > 180) base = clamp(base + 15, 60, 248);

        int shade = clamp(128 + slopeX * 7 + slopeZ * 7, 50, 210);
        int tone = (base + shade) / 2;

        if (topKind == TOP_FOLIAGE) tone = Math.max(FOLIAGE_MIN_TONE, tone - FOLIAGE_DARKEN);
        if (topKind == TOP_WATER) tone = clamp(48 + shade / 5, 36, 105);
        if (topKind == TOP_LAVA) tone = clamp(175 + shade / 8, 165, 235);
        return clamp(tone, 1, 255);
    }

    // ── Pyramid downsampling (levels 1+, pure array math, no world access) ──

    /**
     * Builds one parent tile (256x256) from its 4 children at the level below, arranged:
     * q00=(x0,z0) q10=(x1,z0) q01=(x0,z1) q11=(x1,z1). Any of the 4 may be null (not yet
     * generated) — its region is simply left unknown (0) in the result.
     * Each child's 256x256 is box-downsampled 2x into a 128x128 quadrant of the output;
     * unknown (0) source pixels are excluded from the average rather than pulling it toward 0.
     */
    public static byte[] downsample(byte[] q00, byte[] q10, byte[] q01, byte[] q11) {
        byte[] out = new byte[TILE_BYTES];
        downsampleQuadrant(q00, out, 0, 0);
        downsampleQuadrant(q10, out, TILE_PX / 2, 0);
        downsampleQuadrant(q01, out, 0, TILE_PX / 2);
        downsampleQuadrant(q11, out, TILE_PX / 2, TILE_PX / 2);
        return out;
    }

    private static void downsampleQuadrant(byte[] child, byte[] out, int outX0, int outZ0) {
        if (child == null || child.length != TILE_BYTES) return;

        final int half = TILE_PX / 2;
        for (int oz = 0; oz < half; oz++) {
            for (int ox = 0; ox < half; ox++) {
                int sx = ox * 2;
                int sz = oz * 2;
                int a = child[sz * TILE_PX + sx] & 0xFF;
                int b = child[sz * TILE_PX + sx + 1] & 0xFF;
                int c = child[(sz + 1) * TILE_PX + sx] & 0xFF;
                int d = child[(sz + 1) * TILE_PX + sx + 1] & 0xFF;

                int sum = 0, count = 0;
                if (a != 0) { sum += a; count++; }
                if (b != 0) { sum += b; count++; }
                if (c != 0) { sum += c; count++; }
                if (d != 0) { sum += d; count++; }

                int tone = (count > 0) ? (sum / count) : 0;
                out[(outZ0 + oz) * TILE_PX + (outX0 + ox)] = (byte) (tone & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
}
