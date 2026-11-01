package com.jubitus.travellers.worldmap.pyramid;

import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeDictionary.Type;

/**
 * The biome family a map cell is tinted with, read from Forge's biome dictionary so modded
 * biomes get one too. One family per 16x16-pixel cell, stored in the tile's trailer.
 */
public enum MapBiomeFamily {
    // INVARIANT: the ordinal is saved in map tiles: append only, never reorder or remove.
    UNKNOWN, WATER, PLAINS, FOREST, CONIFER, JUNGLE, SWAMP, DESERT, SAVANNA, MESA, SNOW, MOUNTAIN, MUSHROOM;

    private static final MapBiomeFamily[] VALUES = values();
    private static final byte NOT_CACHED = -1;
    private static final byte[] BY_BIOME_ID = new byte[256];

    static {
        java.util.Arrays.fill(BY_BIOME_ID, NOT_CACHED);
    }

    public static MapBiomeFamily byId(int id) {
        return id > 0 && id < VALUES.length ? VALUES[id] : UNKNOWN;
    }

    /** Family of a chunk biome-array id (255 = not generated yet). Server thread only. */
    static int ofBiomeId(int biomeId) {
        if (biomeId < 0 || biomeId > 254) return UNKNOWN.ordinal();
        byte cached = BY_BIOME_ID[biomeId];
        if (cached == NOT_CACHED) {
            Biome biome = Biome.getBiome(biomeId);
            cached = (byte) (biome == null ? UNKNOWN : of(biome)).ordinal();
            BY_BIOME_ID[biomeId] = cached;
        }
        return cached;
    }

    // First match wins: a snowy taiga is snow, a forested hill is forest.
    private static MapBiomeFamily of(Biome b) {
        if (has(b, Type.OCEAN) || has(b, Type.RIVER)) return WATER;
        if (has(b, Type.MUSHROOM)) return MUSHROOM;
        if (has(b, Type.SNOWY)) return SNOW;
        if (has(b, Type.MESA)) return MESA;
        if (has(b, Type.JUNGLE)) return JUNGLE;
        if (has(b, Type.SWAMP)) return SWAMP;
        if (has(b, Type.SAVANNA)) return SAVANNA;
        if (has(b, Type.SANDY) || has(b, Type.BEACH)) return DESERT;
        if (has(b, Type.CONIFEROUS)) return CONIFER;
        if (has(b, Type.FOREST)) return FOREST;
        if (has(b, Type.MOUNTAIN)) return MOUNTAIN;
        return PLAINS;
    }

    private static boolean has(Biome b, Type t) {
        return BiomeDictionary.hasType(b, t);
    }

    /** Most counted land family; water only when no land was counted; UNKNOWN when nothing was. */
    static byte majority(int[] counts) {
        int best = UNKNOWN.ordinal();
        for (int f = PLAINS.ordinal(); f < counts.length; f++) {
            if (counts[f] > 0 && (best == UNKNOWN.ordinal() || counts[f] > counts[best])) best = f;
        }
        if (best == UNKNOWN.ordinal() && counts[WATER.ordinal()] > 0) best = WATER.ordinal();
        return (byte) best;
    }

    static int count() {
        return VALUES.length;
    }
}
