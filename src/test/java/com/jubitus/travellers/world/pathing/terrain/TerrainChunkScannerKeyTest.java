package com.jubitus.travellers.world.pathing.terrain;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scanner queue keys chunks with {@link ChunkPos#asLong} and unpacks them by hand in
 * {@code ChunkReader.tick}. A packing mismatch there would silently scan the wrong
 * cells, so pin the round-trip for negative coordinates too.
 */
class TerrainChunkScannerKeyTest {

    private static int unpackX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    private static int unpackZ(long key) {
        return (int) (key >>> 32);
    }

    @Test
    void roundTripsPositiveAndNegativeChunkCoords() {
        int[][] samples = {
                {0, 0}, {1, 2}, {-1, -1}, {-30000000 >> 4, 30000000 >> 4},
                {123456, -654321}, {-1, 7}, {7, -1},
        };
        for (int[] s : samples) {
            long key = ChunkPos.asLong(s[0], s[1]);
            assertEquals(s[0], unpackX(key), "x for " + s[0] + "," + s[1]);
            assertEquals(s[1], unpackZ(key), "z for " + s[0] + "," + s[1]);
        }
    }

    @Test
    void cellSampleRoundTrips() {
        int[][] samples = {{64, 12, 0, 5}, {-40, 255, 255, 0}, {319, 0, 128, 32767}, {0, 7, 3, -1}};
        for (int[] s : samples) {
            long p = TerrainChunkScanner.pack(s[0], s[1], s[2], (short) s[3]);
            assertEquals(s[0], TerrainChunkScanner.heightOf(p));
            assertEquals(s[1], TerrainChunkScanner.ampOf(p));
            assertEquals(s[2], TerrainChunkScanner.waterOf(p));
            assertEquals((short) s[3], TerrainChunkScanner.biomeOf(p));
        }
    }

    @Test
    void cellSampleClampsAmplitudeAndWater() {
        long p = TerrainChunkScanner.pack(70, 400, -3, (short) 1);
        assertEquals(255, TerrainChunkScanner.ampOf(p));
        assertEquals(0, TerrainChunkScanner.waterOf(p));
        assertEquals(70, TerrainChunkScanner.heightOf(p));
    }
}
