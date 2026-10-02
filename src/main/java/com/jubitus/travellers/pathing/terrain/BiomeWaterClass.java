package com.jubitus.travellers.pathing.terrain;

/**
 * Coarse water classification of a biome, derived from {@code BiomeDictionary.Type} rather than
 * {@code instanceof BiomeOcean/BiomeRiver} so modded biomes are covered.
 */
public enum BiomeWaterClass {
    LAND,
    OCEAN,
    RIVER,
    SWAMP
}
