package com.jubitus.travellers.world.pathing.terrain;

import net.minecraft.block.material.Material;

/**
 * The mod's single material-based canopy rule, reused by the terrain scanner so a forested cell
 * reads its <em>ground</em> height, not its tree-canopy height.
 *
 * <p>Mirrors {@code MaterializePositionResolver.isStableFloorMaterial} exactly (kept as a separate
 * copy only because that one is private in another package). Matching by {@link Material} rather
 * than by block class is what makes modded trees - Dynamic Trees / DramaticTrees branch and leaf
 * blocks - count as foliage the scan descends through, without naming their blocks.
 */
public final class TerrainSurface {

    private TerrainSurface() {
    }

    public static boolean isFoliage(Material m) {
        return m == Material.LEAVES
                || m == Material.WOOD
                || m == Material.PLANTS
                || m == Material.VINE
                || m == Material.CACTUS
                || m == Material.GOURD;
    }
}
