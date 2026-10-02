package com.jubitus.travellers.pathing.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Region file framing + deflate round-trip, and graceful handling of absent / unknown-version files. */
class TerrainProfileRegionIOTest {

    private static TerrainProfileRegion filledRegion(int rx, int rz) {
        TerrainProfileRegion r = new TerrainProfileRegion(rx, rz);
        for (int i = 0; i < TerrainProfile.CELLS_PER_REGION; i++) {
            int h = 40 + (i % 180);                 // varied surface Y
            int amp = i % 256;
            int water = (i * 7) % 256;
            byte src = (byte) (i % 3);              // EMPTY / BIOME_EST / REAL_SCAN
            short bi = (short) (i % 64);
            r.putCell(i, h, amp, water, src, bi);
        }
        return r;
    }

    @Test
    void roundTripsEveryArray(@TempDir Path dir) throws Exception {
        TerrainProfileRegion src = filledRegion(3, -2);
        File file = new File(dir.toFile(), "r.3.-2.tpg");

        TerrainProfileIO.writeSync(file, src.snapshot());
        TerrainProfileRegion back = TerrainProfileIO.readSync(file, 3, -2);

        assertEquals(3, back.regionX);
        assertEquals(-2, back.regionZ);
        assertArrayEquals(src.heightMean, back.heightMean, "heightMean");
        assertArrayEquals(src.amplitude, back.amplitude, "amplitude");
        assertArrayEquals(src.water, back.water, "water");
        assertArrayEquals(src.source, back.source, "source");
        assertArrayEquals(src.biomeIx, back.biomeIx, "biomeIx");
    }

    @Test
    void absentFileGivesEmptyRegion(@TempDir Path dir) throws Exception {
        TerrainProfileRegion r = TerrainProfileIO.readSync(new File(dir.toFile(), "nope.tpg"), 0, 0);
        for (int i = 0; i < TerrainProfile.CELLS_PER_REGION; i++) {
            assertEquals(TerrainProfile.SOURCE_EMPTY, r.source[i]);
            assertEquals(0, r.heightMean[i]);
            assertEquals(TerrainProfile.BIOME_IX_UNKNOWN, r.biomeIx[i]);
        }
        assertFalse(r.dirty);
    }

    @Test
    void unknownVersionGivesEmptyRegionInsteadOfCrashing(@TempDir Path dir) throws Exception {
        File file = new File(dir.toFile(), "r.0.0.tpg");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(0x54504731); // MAGIC
            out.writeInt(999);        // bogus version
            out.writeInt(TerrainProfile.CELL_SIZE);
            out.writeInt(TerrainProfile.REGION_CELLS);
            out.writeInt(7);
            out.writeInt(1);
            out.writeInt(0);
        }
        TerrainProfileRegion r = TerrainProfileIO.readSync(file, 0, 0);
        assertEquals(TerrainProfile.SOURCE_EMPTY, r.source[0]);
        assertEquals(TerrainProfile.CELLS_PER_REGION, r.heightMean.length);
    }

    @Test
    void snapshotIsAnIndependentCopy() {
        TerrainProfileRegion r = filledRegion(0, 0);
        TerrainProfileRegion.Snapshot s = r.snapshot();
        r.putCell(0, 123, 5, 5, TerrainProfile.SOURCE_REAL_SCAN, (short) 9);
        assertFalse(s.heightMean[0] == 123, "snapshot must not see the later mutation");
    }
}
