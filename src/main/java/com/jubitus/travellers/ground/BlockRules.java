package com.jubitus.travellers.ground;

import com.jubitus.travellers.config.TravellersConfig;
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
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.IFluidBlock;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The mod's one answer to "what kind of block is this" for terrain decisions: tree parts, canopy,
 * vegetation, stable floors, water, hazards, built vs natural. Pure block tests, no world scan. Server thread only.
 */
public final class BlockRules {

    private BlockRules() {
    }

    private static final Map<Block, Boolean> LISTED_HAZARD = new IdentityHashMap<>();
    private static Set<String> hazardIds;

    /** Any water-material liquid (vanilla, Streams rivers, modded water): the one liquid units may swim or row through. */
    public static boolean isWater(IBlockState s) {
        return s.getMaterial() == Material.WATER;
    }

    /** Leaves, vanilla or modded (mods override {@code isLeaves}; world/pos may be null when unknown). */
    public static boolean isLeaves(@Nullable IBlockAccess world, @Nullable BlockPos pos, IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof BlockLeaves || s.getMaterial() == Material.LEAVES) return true;
        if (world == null || pos == null) return false;
        try {
            return b.isLeaves(s, world, pos);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isTreePart(IBlockState s) {
        return isTreePart(null, null, s);
    }

    /**
     * Anything a tree is made of: logs, leaves, vines, Dynamic Trees (and addon) branches, and any
     * modded wooden block named like a log, branch or trunk. Planks are not.
     */
    public static boolean isTreePart(@Nullable IBlockAccess world, @Nullable BlockPos pos, IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof BlockLog || isLeaves(world, pos, s)) return true;
        Material m = s.getMaterial();
        if (m == Material.VINE) return true;
        if (m == Material.WOOD && isTreeWood(b)) return true;
        if (world == null || pos == null) return false;
        try {
            return b.isWood(world, pos);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Tree parts a column search may pass through on its way down to the ground. A bare log is
     * excluded: it may be a player's pillar or cabin wall.
     */
    public static boolean isCanopy(IBlockState s) {
        Block b = s.getBlock();
        Material m = s.getMaterial();
        if (b instanceof BlockLeaves || m == Material.LEAVES || m == Material.VINE) return true;
        return m == Material.WOOD && isDynamicTrees(b);
    }

    /** Leaves, plants, vines, cactus, pumpkins and melons. */
    public static boolean isVegetation(IBlockState s) {
        Material m = s.getMaterial();
        return m == Material.LEAVES || m == Material.PLANTS || m == Material.VINE
                || m == Material.CACTUS || m == Material.GOURD || s.getBlock() instanceof BlockLeaves;
    }

    /** A floor a unit may be left standing on: not a tree part, not vegetation (planks and docks are fine). */
    public static boolean isStableFloor(IBlockState s) {
        return !isTreePart(s) && !isVegetation(s);
    }

    /** Same, also honouring a mod's own {@code isLeaves}/{@code isWood} answer at this position. */
    public static boolean isStableFloor(IBlockAccess world, BlockPos pos, IBlockState s) {
        return !isTreePart(world, pos, s) && !isVegetation(s);
    }

    /**
     * Dangerous to touch or stand on: lava, any non-vanilla-water liquid or fluid block, fire, magma,
     * cactus, and every block in the shared hazard list. Vanilla water is never a hazard.
     */
    public static boolean isHazard(IBlockState s) {
        Block b = s.getBlock();
        if ((s.getMaterial().isLiquid() || b instanceof IFluidBlock) && !isWater(s)) return true;
        if (b == Blocks.FIRE || b == Blocks.MAGMA || b == Blocks.CACTUS) return true;
        return isListedHazard(b);
    }

    /** Ice of any kind: never ground to build or camp on. */
    public static boolean isIce(IBlockState s) {
        Material m = s.getMaterial();
        return m == Material.ICE || m == Material.PACKED_ICE;
    }

    private static boolean isListedHazard(Block b) {
        Boolean cached = LISTED_HAZARD.get(b);
        if (cached != null) return cached;
        if (hazardIds == null) {
            Set<String> ids = new HashSet<>();
            for (String id : TravellersConfig.HAZARD_SAFETY.hazardBlocks) {
                if (id == null) continue;
                String t = id.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) ids.add(t);
            }
            hazardIds = ids;
        }
        ResourceLocation rl = b.getRegistryName();
        boolean listed = rl != null && hazardIds.contains(rl.toString().toLowerCase(Locale.ROOT));
        LISTED_HAZARD.put(b, listed);
        return listed;
    }

    /**
     * Man-made: block entities, crafted blocks and materials, construction shapes (doors, fences,
     * stairs, slabs...), Millénaire's own blocks, and registry names that read as crafted (bricks,
     * planks, cobblestone...). A blacklist, so modded natural terrain passes.
     */
    public static boolean isBuilt(IBlockState s) {
        Boolean cached = BUILT.get(s);
        if (cached == null) {
            cached = computeBuilt(s);
            BUILT.put(s, cached);
        }
        return cached;
    }

    /** Solid ground nature made: not a tree, vegetation or anything built. Hazards are a separate question. */
    public static boolean isNaturalGround(IBlockState s) {
        Material m = s.getMaterial();
        if (!m.blocksMovement() || m.isLiquid()) return false;
        return !isTreePart(s) && !isVegetation(s) && !isBuilt(s);
    }

    public static boolean isMillenaireBlock(IBlockState s) {
        ResourceLocation rl = s.getBlock().getRegistryName();
        return rl != null && "millenaire".equals(rl.getNamespace());
    }

    private static final Map<IBlockState, Boolean> BUILT = new IdentityHashMap<>();

    private static final Set<Block> CRAFTED_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.PLANKS, Blocks.STONEBRICK, Blocks.BRICK_BLOCK, Blocks.NETHER_BRICK, Blocks.RED_NETHER_BRICK,
            Blocks.QUARTZ_BLOCK, Blocks.BOOKSHELF, Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.CONCRETE,
            Blocks.CONCRETE_POWDER, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.END_BRICKS,
            Blocks.HAY_BLOCK, Blocks.SLIME_BLOCK, Blocks.TNT, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE));

    private static final String[] CRAFTED_NAME_PARTS = {"brick", "plank", "cobble", "stairs", "slab", "wall", "fence"};

    private static boolean computeBuilt(IBlockState s) {
        Block b = s.getBlock();
        if (b.hasTileEntity(s) || CRAFTED_BLOCKS.contains(b) || isMillenaireBlock(s)) return true;
        if (b instanceof BlockDoor || b instanceof BlockBed || b instanceof BlockFence
                || b instanceof BlockFenceGate || b instanceof BlockWall || b instanceof BlockStairs
                || b instanceof BlockSlab || b instanceof BlockPane || b instanceof BlockTorch
                || b instanceof BlockLadder || b instanceof BlockTrapDoor || b instanceof BlockCarpet
                || b instanceof BlockButton || b instanceof BlockLever || b instanceof BlockRailBase
                || b instanceof BlockRedstoneWire || b instanceof BlockRedstoneDiode
                || b instanceof BlockBasePressurePlate || b instanceof BlockAnvil
                || b instanceof BlockWorkbench) {
            return true;
        }
        Material m = s.getMaterial();
        if (m == Material.CLOTH || m == Material.GLASS || m == Material.CIRCUITS || m == Material.CARPET
                || m == Material.IRON || m == Material.ANVIL || m == Material.PORTAL || m == Material.REDSTONE_LIGHT) {
            return true;
        }
        ResourceLocation rl = b.getRegistryName();
        if (rl == null) return false;
        String path = rl.getPath();
        for (String part : CRAFTED_NAME_PARTS) {
            if (path.contains(part)) return true;
        }
        return false;
    }

    /** Called when the config is (re)loaded. */
    public static void clearCache() {
        LISTED_HAZARD.clear();
        hazardIds = null;
        BUILT.clear();
    }

    private static final Map<Block, Boolean> TREE_WOOD = new IdentityHashMap<>();
    private static final String[] TREE_NAME_PARTS = {"log", "branch", "trunk"};

    // A wooden block from Dynamic Trees or its addons, or named like a log/branch/trunk by any mod.
    private static boolean isTreeWood(Block b) {
        Boolean cached = TREE_WOOD.get(b);
        if (cached != null) return cached;
        boolean tree = isDynamicTrees(b);
        ResourceLocation id = b.getRegistryName();
        if (!tree && id != null) {
            String path = id.getPath();
            for (String part : TREE_NAME_PARTS) {
                if (path.contains(part)) {
                    tree = true;
                    break;
                }
            }
        }
        TREE_WOOD.put(b, tree);
        return tree;
    }

    private static boolean isDynamicTrees(Block b) {
        ResourceLocation id = b.getRegistryName();
        return id != null && id.getNamespace().startsWith("dynamictrees");
    }
}
