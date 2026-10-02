package com.jubitus.travellers.dialogue;

import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;

/**
 * Buckets a biome into a landmark kind worth pointing out as a distant feature - mountains, forest,
 * desert... An ordinary biome (plains, generic grassland) returns {@link Kind#NONE} and is never
 * treated as a landmark. Used only to decide whether/how a sampled point differs from the
 * traveller's own surroundings - the actual wording sent to the model is always the biome's own
 * display name ({@link WorldStateDialogueContextProvider#biomeDisplayName}), never this enum.
 *
 * <p>Order matters: a biome can carry several {@link BiomeDictionary.Type}s at once (a snowy taiga is
 * both SNOWY and CONIFEROUS/FOREST) - the first match below wins.
 */
final class BiomeLandmarkClassifier {

    enum Kind { MOUNTAIN, MESA, JUNGLE, SWAMP, SNOWY, SAVANNA, SANDY, FOREST, NONE }

    private BiomeLandmarkClassifier() {
    }

    static Kind classify(Biome biome) {
        if (biome == null) return Kind.NONE;
        try {
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.MOUNTAIN)) return Kind.MOUNTAIN;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.MESA)) return Kind.MESA;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.JUNGLE)) return Kind.JUNGLE;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SWAMP)) return Kind.SWAMP;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SNOWY)) return Kind.SNOWY;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SAVANNA)) return Kind.SAVANNA;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SANDY)) return Kind.SANDY;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.FOREST)) return Kind.FOREST;
        } catch (RuntimeException ignored) {
            // BiomeDictionary throws for a biome not registered through Forge; treat as unremarkable.
        }
        return Kind.NONE;
    }
}
