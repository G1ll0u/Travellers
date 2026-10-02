package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/**
 * Weather, time of day and biome (E2 of the context-expansion plan). None of these need bucketing
 * against D-8's grounding rule - they're already exactly what a player standing next to the
 * traveller can see for themselves.
 *
 * <p><b>Trap:</b> {@code Biome#getBiomeName()} crashes with a {@code NoSuchMethodError} on at least
 * one real dedicated-server environment (see {@code CultureRegistry.findBucketForBiome}'s own
 * comment - the same trap, hit a second time here) - use {@link Biome#getRegistryName()} instead,
 * never the display-name method.
 */
public final class WorldStateDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        World world = traveller.world;
        BlockPos pos = traveller.getPosition();

        String weather;
        if (world.isThundering()) {
            weather = "thundering";
        } else if (isSnowingAt(world, pos)) {
            weather = "snowing";
        } else if (world.isRaining()) {
            weather = "raining";
        } else {
            weather = "clear";
        }
        builder.weatherPhrase(weather);

        builder.timeOfDayPhrase(timeOfDayPhrase(world.getWorldTime()));

        Biome biome = world.getBiome(pos);
        builder.biomeName(biomeDisplayName(biome));

        builder.standingInSnow(isStandingInSnow(world, pos));
    }

    /**
     * Mirrors {@code World.isRainingAt} (2026-09-12 feedback: travellers only ever noticed rain, never
     * snow) but reports the snow case instead of just excluding it - same precipitation/sky/biome
     * checks, so a traveller under a roof or indoors still correctly reports no snow.
     */
    private static boolean isSnowingAt(World world, BlockPos pos) {
        if (!world.isRaining()) return false;
        if (!world.canSeeSky(pos)) return false;
        if (world.getPrecipitationHeight(pos).getY() > pos.getY()) return false;
        Biome biome = world.getBiome(pos);
        return biome.getEnableSnow() || world.canSnowAt(pos, false);
    }

    /** Whether the ground right under the traveller's feet is snow-covered, regardless of current weather. */
    private static boolean isStandingInSnow(World world, BlockPos pos) {
        return isSnowBlock(world, pos) || isSnowBlock(world, pos.down());
    }

    private static boolean isSnowBlock(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return block == Blocks.SNOW_LAYER || block == Blocks.SNOW;
    }

    /**
     * Four buckets (2026-09-12 feedback: the old dawn/day/dusk/night split reported "night" while the
     * sky was already brightening toward sunrise, ticks 23000-23999). Tick 0 = 6:00, 1000 ticks/hour:
     * morning wraps across the 0 boundary (23000-23999 is the pre-dawn glow, 0-5999 is dawn through
     * pre-noon), midday is noon to sunset, evening is sunset through the start of full dark, night is
     * the core dark hours.
     */
    private static String timeOfDayPhrase(long worldTime) {
        long dayTime = worldTime % 24000L;
        if (dayTime >= 23000L || dayTime < 6000L) return "morning";
        if (dayTime < 12000L) return "midday";
        if (dayTime < 18000L) return "evening";
        return "night";
    }

    /**
     * e.g. "minecraft:extreme_hills" -> "extreme hills". Never {@code getBiomeName()} - see class doc.
     * Package-visible: {@link SurroundingBiomeDialogueContextProvider} reuses it for biomes other than
     * the traveller's own, so both places hit the same crash-avoiding trap exactly once.
     */
    static String biomeDisplayName(Biome biome) {
        if (biome == null || biome.getRegistryName() == null) return "";
        return biome.getRegistryName().getPath().replace('_', ' ');
    }
}
