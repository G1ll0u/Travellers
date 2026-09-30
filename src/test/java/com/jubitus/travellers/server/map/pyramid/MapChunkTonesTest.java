package com.jubitus.travellers.server.map.pyramid;

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
    void writeChunkReportsChangesOnlyWhenPixelsDiffer() {
        byte[] tile = new byte[MapTileGenerator.TILE_BYTES];
        byte[] t = new byte[256];
        java.util.Arrays.fill(t, (byte) 90);
        assertTrue(MapChunkPainter.writeChunk(tile, 15, 15, t));
        assertEquals(90, tile[(15 * 16 + 15) * MapTileGenerator.TILE_PX + 15 * 16 + 15]);
        assertFalse(MapChunkPainter.writeChunk(tile, 15, 15, t));
        assertEquals(0, tile[0]);
    }
}
