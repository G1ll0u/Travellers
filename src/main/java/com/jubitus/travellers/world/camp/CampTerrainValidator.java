package com.jubitus.travellers.world.camp;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRedstoneDiode;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWall;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

import com.jubitus.travellers.config.TravellersDerivedConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Heuristic "is this natural terrain?" check used to make sure a camp never grades/overwrites a
 * player build, a Millénaire structure, or anything else man-made. Deliberately a blacklist of
 * clearly-artificial signals (tile entities, crafted blocks/materials, whole mod namespaces) rather
 * than a whitelist of natural blocks, so terrain-generation mods (RTG, Biomes O' Plenty, ...) whose
 * ground blocks we can't enumerate still pass. False positives (a stray mineshaft plank vetoing a
 * slot) are acceptable — the failure mode must always be "camp doesn't build here", never
 * "someone's house got flattened".
 *
 * <p>Also doubles as the hazard gate: any liquid (vanilla or modded, via {@link Material#isLiquid()}
 * or {@link IFluidBlock}) fails, plus anything named in the configurable
 * {@code CAMP_EVENTS.terrainHazardBlacklist} — the escape hatch for hazards (modded quicksand, etc.)
 * that don't register as a liquid but shouldn't have a shelter built on/in them either.
 */
public final class CampTerrainValidator {

    private CampTerrainValidator() {
    }

    /** Vanilla blocks that read as crafted/man-made but aren't caught by material or class checks. */
    private static final Set<Block> ARTIFICIAL_BLOCKS = new HashSet<>();

    static {
        ARTIFICIAL_BLOCKS.add(Blocks.PLANKS);
        ARTIFICIAL_BLOCKS.add(Blocks.STONEBRICK);
        ARTIFICIAL_BLOCKS.add(Blocks.BRICK_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.NETHER_BRICK);
        ARTIFICIAL_BLOCKS.add(Blocks.RED_NETHER_BRICK);
        ARTIFICIAL_BLOCKS.add(Blocks.QUARTZ_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.BOOKSHELF);
        ARTIFICIAL_BLOCKS.add(Blocks.GLOWSTONE);
        ARTIFICIAL_BLOCKS.add(Blocks.SEA_LANTERN);
        ARTIFICIAL_BLOCKS.add(Blocks.CONCRETE);
        ARTIFICIAL_BLOCKS.add(Blocks.CONCRETE_POWDER);
        ARTIFICIAL_BLOCKS.add(Blocks.PURPUR_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.PURPUR_PILLAR);
        ARTIFICIAL_BLOCKS.add(Blocks.END_BRICKS);
        ARTIFICIAL_BLOCKS.add(Blocks.HAY_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.SLIME_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.TNT);
    }

    /** True if every block in the inclusive cuboid [min, max] passes {@link #isCellNatural}. Requires loaded chunks. */
    public static boolean isVolumeNatural(World world, BlockPos min, BlockPos max) {
        for (BlockPos p : BlockPos.getAllInBoxMutable(min, max)) {
            if (!isCellNatural(world, p)) return false;
        }
        return true;
    }

    /**
     * True if {@code state} is one of Millénaire's own building blocks. Used both by
     * {@link #isCellNatural} (camps must never be placed on top of a village/lone-building) and by
     * a forced camp evacuation that must never clear a Millénaire block a village grew into a
     * previously-natural footprint.
     */
    public static boolean isMillenaireBlock(IBlockState state) {
        ResourceLocation rl = state.getBlock().getRegistryName();
        return rl != null && "millenaire".equals(rl.getNamespace());
    }

    /** True if the block at {@code pos} shows no artificial signal (air always passes). */
    public static boolean isCellNatural(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        Block b = state.getBlock();
        if (b.isAir(state, world, pos)) return true;

        // Anything with block-entity data (chests, furnaces, signs, banners, mod machines...).
        if (b.hasTileEntity(state) || world.getTileEntity(pos) != null) return false;

        if (ARTIFICIAL_BLOCKS.contains(b)) return false;

        // Millénaire's own building blocks (paths, walls, decorative blocks) — camps must never touch
        // village/lone-building structures, wherever they were placed.
        if (isMillenaireBlock(state)) return false;

        // Liquids (vanilla or modded) and configured hazard blocks: never build in/on them, even if
        // they'd otherwise read as "natural" terrain (e.g. a modded quicksand block).
        Material mat = state.getMaterial();
        if (mat.isLiquid() || b instanceof IFluidBlock) return false;
        ResourceLocation blockId = b.getRegistryName();
        if (blockId != null && TravellersDerivedConfig.CAMP_TERRAIN_HAZARD_BLACKLIST
                .contains(blockId.toString().toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }

        if (b instanceof BlockDoor || b instanceof BlockBed || b instanceof BlockFence
                || b instanceof BlockFenceGate || b instanceof BlockWall || b instanceof BlockStairs
                || b instanceof BlockSlab || b instanceof BlockPane || b instanceof BlockTorch
                || b instanceof BlockLadder || b instanceof BlockTrapDoor || b instanceof BlockCarpet
                || b instanceof BlockButton || b instanceof BlockLever || b instanceof BlockRailBase
                || b instanceof BlockRedstoneWire || b instanceof BlockRedstoneDiode
                || b instanceof BlockBasePressurePlate || b instanceof BlockAnvil
                || b instanceof BlockWorkbench) {
            return false;
        }

        Material m = state.getMaterial();
        return m != Material.CLOTH && m != Material.GLASS && m != Material.CIRCUITS
                && m != Material.CARPET && m != Material.IRON && m != Material.ANVIL
                && m != Material.PORTAL && m != Material.REDSTONE_LIGHT;
    }
}
