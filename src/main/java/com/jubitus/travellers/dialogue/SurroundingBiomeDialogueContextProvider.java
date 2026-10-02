package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/**
 * Biome awareness beyond the single spot the traveller stands on (2026-09-12 feedback): whether it is
 * standing right at the edge of two different areas, and any notable landscape (mountains, forest,
 * seas, rivers...) further out, bucketed by rough direction - the raw material for lines like "head
 * toward the forest north-east and you'll reach the desert" instead of only ever describing the one
 * tile underfoot. The distant-landmark half is a grid survey + flood fill ({@link
 * TerrainLandmarkScanner}, 2026-09-17 rewrite of an earlier 8-ray version that pointed at whichever
 * ray happened to clip a landmass first, not its true nearest edge, and had no notion of water at all.
 *
 * <p>Every sample goes through {@link World#getBiome}, which for a position outside the loaded area
 * resolves straight from the biome-generation layers ({@code BiomeProvider}) and never touches chunk
 * data - the same no-chunk-load trick {@code BiomeTerrainEstimator} already relies on, so this is
 * safe to sample thousands of blocks out. Runs once per dialogue open, not per tick.
 */
public final class SurroundingBiomeDialogueContextProvider implements DialogueContextProvider {

    // 8 compass rays, clockwise from north, for the short-range border check only. {dx, dz}.
    private static final int[][] DIRECTIONS = {
            {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
    };

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        World world = traveller.world;
        BlockPos origin = traveller.getPosition();
        Biome here = world.getBiome(origin);

        contributeBorder(world, origin, here, builder);
        contributeDistantLandmarks(world, origin, builder);
    }

    /** Steps out a short distance in every direction; the first different biome found is the "edge". */
    private static void contributeBorder(World world, BlockPos origin, Biome here, DialogueContext.Builder builder) {
        int radius = TravellersConfig.DIALOGUE.biomeBorderCheckRadius;
        for (int[] dir : DIRECTIONS) {
            BlockPos p = new BlockPos(origin.getX() + dir[0] * radius, origin.getY(), origin.getZ() + dir[1] * radius);
            Biome there = world.getBiome(p);
            if (there != here) {
                builder.atBiomeBorder(true);
                builder.borderBiomeName(WorldStateDialogueContextProvider.biomeDisplayName(there));
                return;
            }
        }
    }

    private static void contributeDistantLandmarks(World world, BlockPos origin, DialogueContext.Builder builder) {
        int radius = TravellersConfig.DIALOGUE.distantLandmarkRadius;
        int spacing = TravellersConfig.DIALOGUE.distantLandmarkSampleSpacing;
        int maxCount = TravellersConfig.DIALOGUE.distantLandmarkMaxCount;
        for (DialogueContext.BiomeFeatureBlurb blurb : TerrainLandmarkScanner.scan(world, origin, radius, spacing, maxCount)) {
            builder.addNearbyBiomeFeature(blurb);
        }
    }
}
