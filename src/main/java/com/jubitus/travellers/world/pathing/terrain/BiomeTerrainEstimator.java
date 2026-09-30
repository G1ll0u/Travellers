package com.jubitus.travellers.world.pathing.terrain;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;

/**
 * Fills a grid cell from its biome alone, no chunk load. Used everywhere a {@code REAL_SCAN} cell
 * does not exist yet, so the route planner always has data for every cell inside its search box.
 *
 * <p>Split in two on purpose (performance contract §2.2): {@link #estimate} is a pure function of
 * primitives, unit-tested directly; {@link #estimateAt} is the server-thread wrapper that reads the
 * live {@link World}/{@code BiomeDictionary}.
 */
public final class BiomeTerrainEstimator {

    private static final int SEA_LEVEL = 63;
    /** Vanilla-ish: surface Y ~= sea level + baseHeight * this. */
    private static final double HEIGHT_SCALE = 17.0;
    private static final double AMPLITUDE_SCALE = HEIGHT_SCALE * 2.5;

    private BiomeTerrainEstimator() {
    }

    /** Result of an estimate: the four numbers written into a cell (biome id is added by the caller). */
    public static final class Estimate {
        public final short heightMean;
        public final int amplitude; // 0..255
        public final int water;     // 0..255

        public Estimate(short heightMean, int amplitude, int water) {
            this.heightMean = heightMean;
            this.amplitude = amplitude;
            this.water = water;
        }
    }

    /** Pure core. */
    public static Estimate estimate(float baseHeight, float heightVariation, BiomeWaterClass waterClass) {
        int mean = (int) Math.round(SEA_LEVEL + baseHeight * HEIGHT_SCALE);
        int amp = (int) Math.round(Math.abs(heightVariation) * AMPLITUDE_SCALE);
        int water;
        switch (waterClass) {
            case OCEAN:
                water = 255;
                mean = Math.min(mean, SEA_LEVEL); // read the water surface, not the seabed
                break;
            case RIVER:
                water = 200;
                mean = Math.min(mean, SEA_LEVEL);
                break;
            case SWAMP:
                water = 128;
                break;
            case LAND:
            default:
                water = 0;
                break;
        }
        return new Estimate(clampShort(mean), clamp255(amp), water);
    }

    /** Server thread only: samples the biome at the cell centre. Returns {@code null} on failure. */
    public static Estimate estimateAt(World world, int cellX, int cellZ) {
        int wx = cellX * TerrainProfile.CELL_SIZE + TerrainProfile.CELL_SIZE / 2;
        int wz = cellZ * TerrainProfile.CELL_SIZE + TerrainProfile.CELL_SIZE / 2;
        Biome biome = world.getBiome(new BlockPos(wx, 64, wz));
        if (biome == null) return null;
        return estimate(biome.getBaseHeight(), biome.getHeightVariation(), classify(biome));
    }

    /** Server thread only: biome name at the cell centre, or {@code null}. */
    public static net.minecraft.util.ResourceLocation biomeNameAt(World world, int cellX, int cellZ) {
        int wx = cellX * TerrainProfile.CELL_SIZE + TerrainProfile.CELL_SIZE / 2;
        int wz = cellZ * TerrainProfile.CELL_SIZE + TerrainProfile.CELL_SIZE / 2;
        Biome biome = world.getBiome(new BlockPos(wx, 64, wz));
        return biome == null ? null : biome.getRegistryName();
    }

    public static BiomeWaterClass classify(Biome biome) {
        try {
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN)) return BiomeWaterClass.OCEAN;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.RIVER)) return BiomeWaterClass.RIVER;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SWAMP)) return BiomeWaterClass.SWAMP;
        } catch (RuntimeException ignored) {
            // BiomeDictionary throws for a biome not registered through Forge; treat as land.
        }
        return BiomeWaterClass.LAND;
    }

    private static short clampShort(int v) {
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        return (short) v;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
