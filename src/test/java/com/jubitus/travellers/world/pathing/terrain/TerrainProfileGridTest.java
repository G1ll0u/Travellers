package com.jubitus.travellers.world.pathing.terrain;

import com.jubitus.travellers.config.TravellersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LRU cap + sentinel reads + a grid/IO/region integration round-trip. No Minecraft classes touched. */
class TerrainProfileGridTest {

    private static TerrainProfileGrid newGrid(Path dir) {
        return new TerrainProfileGrid(0, dir.toFile(), BiomeIndex.empty());
    }

    @Test
    void nonResidentReadsReturnPessimisticSentinels(@TempDir Path dir) {
        TerrainProfileGrid grid = newGrid(dir);
        assertEquals(TerrainProfile.SOURCE_EMPTY, grid.getSource(0, 0));
        assertEquals(Short.MIN_VALUE, grid.getHeight(0, 0));
        assertEquals(255, grid.getAmplitude(0, 0));      // pessimistic: assume rough
        assertEquals(0, grid.getWater(0, 0));
        assertEquals(TerrainProfile.BIOME_IX_UNKNOWN, grid.getBiomeIx(0, 0));
        assertFalse(grid.isResidentForWorld(0, 0));
    }

    @Test
    void lruEvictsOldestOverCap(@TempDir Path dir) {
        int savedCap = TravellersConfig.ROUTING.terrainMemoryRegionCap;
        TravellersConfig.ROUTING.terrainMemoryRegionCap = 3;
        try {
            TerrainProfileGrid grid = newGrid(dir);
            for (int i = 0; i < 6; i++) grid.publishRegion(new TerrainProfileRegion(i, 0));
            assertEquals(3, grid.residentCount(), "cap must be enforced");
            // The three most recently published survive.
            assertTrue(grid.isResidentForWorld(5 * TerrainProfile.REGION_BLOCKS, 0));
            assertTrue(grid.isResidentForWorld(4 * TerrainProfile.REGION_BLOCKS, 0));
            assertTrue(grid.isResidentForWorld(3 * TerrainProfile.REGION_BLOCKS, 0));
            assertFalse(grid.isResidentForWorld(0, 0));
        } finally {
            TravellersConfig.ROUTING.terrainMemoryRegionCap = savedCap;
        }
    }

    @Test
    void republishingAResidentRegionRefreshesItsLruRecency(@TempDir Path dir) {
        int savedCap = TravellersConfig.ROUTING.terrainMemoryRegionCap;
        TravellersConfig.ROUTING.terrainMemoryRegionCap = 2;
        try {
            TerrainProfileGrid grid = newGrid(dir);
            grid.publishRegion(new TerrainProfileRegion(0, 0));
            grid.publishRegion(new TerrainProfileRegion(1, 0));
            grid.publishRegion(new TerrainProfileRegion(0, 0)); // re-touch region 0 -> now most recent
            grid.publishRegion(new TerrainProfileRegion(2, 0)); // evicts region 1, the eldest
            assertEquals(2, grid.residentCount());
            assertTrue(grid.isResidentForWorld(0, 0));
            assertFalse(grid.isResidentForWorld(1 * TerrainProfile.REGION_BLOCKS, 0));
            assertTrue(grid.isResidentForWorld(2 * TerrainProfile.REGION_BLOCKS, 0));
        } finally {
            TravellersConfig.ROUTING.terrainMemoryRegionCap = savedCap;
        }
    }

    @Test
    void realCellSurvivesFlushAndReloadThroughANewGrid(@TempDir Path dir) {
        TerrainProfileGrid g1 = newGrid(dir);
        // region (0,0), cell (2,3) local index
        g1.publishRegion(new TerrainProfileRegion(0, 0));
        g1.putRealCell(2, 3, 91, 12, 200, (short) 7);
        g1.flushAllSync();

        TerrainProfileGrid g2 = newGrid(dir);
        g2.loadRegionBlocking(2, 3);
        int wx = 2 * TerrainProfile.CELL_SIZE + 1;
        int wz = 3 * TerrainProfile.CELL_SIZE + 1;
        assertEquals(TerrainProfile.SOURCE_REAL_SCAN, g2.getSource(wx, wz));
        assertEquals(91, g2.getHeight(wx, wz));
        assertEquals(12, g2.getAmplitude(wx, wz));
        assertEquals(200, g2.getWater(wx, wz));
        assertEquals(7, g2.getBiomeIx(wx, wz));
    }

    @Test
    void flushDueClearsDirtyAndStampsTick(@TempDir Path dir) {
        TerrainProfileGrid grid = newGrid(dir);
        TerrainProfileRegion r = new TerrainProfileRegion(0, 0);
        grid.publishRegion(r);
        r.putCell(0, 70, 1, 0, TerrainProfile.SOURCE_BIOME_EST, (short) 0);
        assertTrue(r.dirty);
        grid.flushDue(1000L, 0);
        assertFalse(r.dirty);
        assertEquals(1000L, r.lastFlushTick);
    }

    @Test
    void tpgFileLandsUnderTheDimDirectory(@TempDir Path dir) {
        TerrainProfileGrid grid = newGrid(dir);
        TerrainProfileRegion r = new TerrainProfileRegion(1, 1);
        grid.publishRegion(r);
        r.putCell(0, 70, 1, 0, TerrainProfile.SOURCE_BIOME_EST, (short) 0);
        grid.flushAllSync();
        assertTrue(new File(dir.toFile(), "r.1.1.tpg").isFile());
    }
}
