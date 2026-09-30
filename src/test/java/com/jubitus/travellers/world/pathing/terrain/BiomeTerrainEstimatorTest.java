package com.jubitus.travellers.world.pathing.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure {@link BiomeTerrainEstimator#estimate} coverage.
 *
 * <p>The MC-facing half ({@code estimateAt} / {@code classify}) cannot run in a bare JVM test - the
 * {@code net.minecraft.world.biome.Biome} constructor dereferences {@code Blocks.GRASS}, which is
 * null until {@code Bootstrap.register()}. That half is exercised in-world via
 * {@code /travellersdebug terrain}.
 */
class BiomeTerrainEstimatorTest {

    @Test
    void plainsLikeSitsJustAboveSeaLevelWithLittleRoughness() {
        BiomeTerrainEstimator.Estimate e =
                BiomeTerrainEstimator.estimate(0.125f, 0.05f, BiomeWaterClass.LAND);
        assertEquals(65, e.heightMean); // round(63 + 0.125*17)
        assertEquals(0, e.water);
        assertTrue(e.amplitude <= 3, "flat biome => tiny amplitude, was " + e.amplitude);
    }

    @Test
    void extremeHillsAreTallerAndRougher() {
        BiomeTerrainEstimator.Estimate e =
                BiomeTerrainEstimator.estimate(1.0f, 0.5f, BiomeWaterClass.LAND);
        assertEquals(80, e.heightMean);      // round(63 + 1.0*17)
        assertEquals(21, e.amplitude);       // round(0.5 * 42.5)
        assertEquals(0, e.water);
    }

    @Test
    void oceanIsFullWaterAndClampedToSeaLevel() {
        BiomeTerrainEstimator.Estimate e =
                BiomeTerrainEstimator.estimate(-1.0f, 0.1f, BiomeWaterClass.OCEAN);
        assertEquals(255, e.water);
        assertTrue(e.heightMean <= 63, "ocean height must read the water surface, was " + e.heightMean);
    }

    @Test
    void riverAndSwampCarryPartialWater() {
        assertEquals(200, BiomeTerrainEstimator.estimate(-0.5f, 0f, BiomeWaterClass.RIVER).water);
        assertEquals(128, BiomeTerrainEstimator.estimate(0.1f, 0.1f, BiomeWaterClass.SWAMP).water);
    }

    @Test
    void amplitudeAndWaterStayInByteRange() {
        BiomeTerrainEstimator.Estimate e =
                BiomeTerrainEstimator.estimate(6.0f, 40.0f, BiomeWaterClass.LAND);
        assertTrue(e.amplitude >= 0 && e.amplitude <= 255);
        assertTrue(e.water >= 0 && e.water <= 255);
    }
}
