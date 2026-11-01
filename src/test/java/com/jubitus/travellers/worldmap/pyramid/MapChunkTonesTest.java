package com.jubitus.travellers.worldmap.pyramid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapChunkTonesTest {

    private static byte[] tones(int[] heights, byte[] kind) {
        byte[] out = new byte[256];
        MapTileGenerator.chunkTones(heights, kind, out);
        return out;
    }

    @Test
    void everyPixelIsPaintedIncludingEdges() {
        int[] h = new int[256];
        for (int i = 0; i < 256; i++) h[i] = 40 + (i * 7) % 90;
        byte[] out = tones(h, new byte[256]);
        for (int i = 0; i < 256; i++) assertNotEquals(0, out[i], "pixel " + i);
    }

    @Test
    void edgeColumnAndRowGetTheInteriorSlopeOnARamp() {
        int[] h = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) h[z * 16 + x] = 64 + x + 2 * z;
        }
        byte[] out = tones(h, new byte[256]);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int expected = MapTileGenerator.tone(64 + x + 2 * z, -1, -2, MapTileGenerator.TOP_SOLID);
                assertEquals(expected, out[z * 16 + x] & 0xFF, "x=" + x + " z=" + z);
            }
        }
    }

    @Test
    void waterUsesWaterPalette() {
        int[] h = new int[256];
        java.util.Arrays.fill(h, 62);
        byte[] kind = new byte[256];
        java.util.Arrays.fill(kind, MapTileGenerator.TOP_WATER);
        byte[] out = tones(h, kind);
        int t = out[0] & 0xFF;
        assertTrue(t >= 36 && t <= 105, "water tone " + t);
    }

    @Test
    void foliageIsSlightlyDarkerGroundAndNeverWater() {
        int ground = MapTileGenerator.tone(70, 0, 0, MapTileGenerator.TOP_SOLID);
        int forest = MapTileGenerator.tone(70, 0, 0, MapTileGenerator.TOP_FOLIAGE);
        assertTrue(forest < ground && forest > ground - 20, "forest " + forest + " ground " + ground);
        assertTrue(MapTileGenerator.tone(1, -20, -20, MapTileGenerator.TOP_FOLIAGE) > 75);
    }

    @Test
    void writeChunkReportsChangesOnlyWhenPixelsDiffer() {
        byte[] tile = new byte[MapTileGenerator.TILE_BYTES];
        byte[] t = new byte[256];
        java.util.Arrays.fill(t, (byte) 90);
        byte forest = (byte) MapBiomeFamily.FOREST.ordinal();
        assertTrue(MapChunkPainter.writeChunk(tile, 15, 15, t, forest));
        assertEquals(90, tile[(15 * 16 + 15) * MapTileGenerator.TILE_PX + 15 * 16 + 15]);
        assertEquals(forest, tile[MapTileGenerator.cellIndex(15, 15)]);
        assertFalse(MapChunkPainter.writeChunk(tile, 15, 15, t, forest));
        assertTrue(MapChunkPainter.writeChunk(tile, 15, 15, t, (byte) MapBiomeFamily.DESERT.ordinal()));
        assertEquals(0, tile[0]);
        assertEquals(0, tile[MapTileGenerator.cellIndex(0, 0)]);
    }

    @Test
    void familyMajorityPrefersLandAndIgnoresUnknown() {
        int[] c = new int[MapBiomeFamily.values().length];
        assertEquals(MapBiomeFamily.UNKNOWN.ordinal(), MapBiomeFamily.majority(c));
        c[MapBiomeFamily.UNKNOWN.ordinal()] = 200;
        c[MapBiomeFamily.WATER.ordinal()] = 50;
        assertEquals(MapBiomeFamily.WATER.ordinal(), MapBiomeFamily.majority(c));
        c[MapBiomeFamily.DESERT.ordinal()] = 3;
        c[MapBiomeFamily.PLAINS.ordinal()] = 5;
        assertEquals(MapBiomeFamily.PLAINS.ordinal(), MapBiomeFamily.majority(c));
    }

    @Test
    void downsampleTakesTheCellMajorityPerQuadrant() {
        byte[] child = new byte[MapTileGenerator.TILE_BYTES];
        byte snow = (byte) MapBiomeFamily.SNOW.ordinal();
        byte mesa = (byte) MapBiomeFamily.MESA.ordinal();
        child[MapTileGenerator.cellIndex(0, 0)] = snow;
        child[MapTileGenerator.cellIndex(1, 0)] = snow;
        child[MapTileGenerator.cellIndex(0, 1)] = mesa;
        child[MapTileGenerator.cellIndex(15, 15)] = mesa;
        byte[] out = MapTileGenerator.downsample(null, child, null, null);
        assertEquals(snow, out[MapTileGenerator.cellIndex(8, 0)]);
        assertEquals(mesa, out[MapTileGenerator.cellIndex(15, 7)]);
        assertEquals(0, out[MapTileGenerator.cellIndex(0, 0)]);
    }

    @Test
    void oldTilesInflateWithAnUnknownTrailer() {
        byte[] old = new byte[MapTileGenerator.TONE_BYTES];
        java.util.Arrays.fill(old, (byte) 120);
        byte[] in = TileCompression.inflate(TileCompression.deflate(old), MapTileGenerator.TILE_BYTES);
        assertEquals(120, in[MapTileGenerator.TONE_BYTES - 1]);
        assertEquals(0, in[MapTileGenerator.cellIndex(0, 0)]);
    }
}
