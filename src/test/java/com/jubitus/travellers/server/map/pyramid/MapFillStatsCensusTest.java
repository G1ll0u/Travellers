package com.jubitus.travellers.server.map.pyramid;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapFillStatsCensusTest {

    private static final int PX = MapTileGenerator.TILE_PX;

    private static void paintChunk(byte[] tile, int cx, int cz) {
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                tile[(cz * 16 + dz) * PX + cx * 16 + dx] = 100;
            }
        }
    }

    @Test
    void missingTileIsAllEmpty() {
        MapFillStats.HoleCensus c = MapFillStats.census(null);
        assertEquals(256, c.emptyChunks);
        assertEquals(0, c.partialChunks);
    }

    @Test
    void fullTileHasNoHoles() {
        byte[] tile = new byte[MapTileGenerator.TILE_BYTES];
        Arrays.fill(tile, (byte) 100);
        MapFillStats.HoleCensus c = MapFillStats.census(tile);
        assertEquals(256, c.fullChunks);
        assertEquals(0, c.partialChunks);
    }

    @Test
    void missingLastColumnAndRowIsEdgeOnly() {
        byte[] tile = new byte[MapTileGenerator.TILE_BYTES];
        paintChunk(tile, 3, 5);
        for (int i = 0; i < 16; i++) {
            tile[(5 * 16 + i) * PX + 3 * 16 + 15] = 0;
            tile[(5 * 16 + 15) * PX + 3 * 16 + i] = 0;
        }
        MapFillStats.HoleCensus c = MapFillStats.census(tile);
        assertEquals(1, c.partialChunks);
        assertEquals(1, c.edgeOnlyPartialChunks);
        assertEquals(31, c.missingPixelsInPartial);
        assertEquals(255, c.emptyChunks);
    }

    @Test
    void holeInsideChunkIsNotEdgeOnly() {
        byte[] tile = new byte[MapTileGenerator.TILE_BYTES];
        paintChunk(tile, 0, 0);
        tile[7 * PX + 7] = 0;
        MapFillStats.HoleCensus c = MapFillStats.census(tile);
        assertEquals(1, c.partialChunks);
        assertEquals(0, c.edgeOnlyPartialChunks);
        assertEquals(1, c.missingPixelsInPartial);
    }
}
