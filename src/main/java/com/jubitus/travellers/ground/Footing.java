package com.jubitus.travellers.ground;

import com.jubitus.travellers.ground.chunk.LoadedChunks;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * The mod's one answer to "can a unit stand here" and "can it swim here", for a one-block-wide,
 * two-block-tall body with its feet at {@code feet}. Never loads a chunk.
 */
public final class Footing {

    private Footing() {
    }

    /** Feet, head and floor cells are loaded, the floor holds a unit, and feet and head have room. */
    public static boolean canStandAt(World world, BlockPos feet) {
        if (!LoadedChunks.isLoaded(world, feet)) return false;
        BlockPos down = feet.down();
        return isFloor(world, down, world.getBlockState(down)) && hasRoom(world, feet) && hasRoom(world, feet.up());
    }

    /** Vanilla water at the feet, water or room at the head, no hazard at feet, head or below. */
    public static boolean canSwimAt(World world, BlockPos feet) {
        if (!LoadedChunks.isLoaded(world, feet)) return false;
        IBlockState atFeet = world.getBlockState(feet);
        if (!BlockRules.isVanillaWater(atFeet)) return false;
        BlockPos up = feet.up();
        IBlockState atHead = world.getBlockState(up);
        if (BlockRules.isHazard(atHead) || BlockRules.isHazard(world.getBlockState(feet.down()))) return false;
        return BlockRules.isVanillaWater(atHead) || atHead.getMaterial().isReplaceable()
                || atHead.getCollisionBoundingBox(world, up) == null;
    }

    /**
     * A block a unit can put its feet on: a full top face or a real collision height (slabs, paths,
     * snow, farmland), never a liquid, a hazard, any tree part (logs included) or vegetation.
     */
    public static boolean isFloor(IBlockAccess world, BlockPos pos, IBlockState s) {
        if (s.getMaterial().isLiquid() || BlockRules.isHazard(s) || !BlockRules.isStableFloor(world, pos, s)) return false;
        if (s.getBlockFaceShape(world, pos, EnumFacing.UP) == BlockFaceShape.SOLID) return true;
        if (s.getBlock() == Blocks.SNOW_LAYER) return true;
        AxisAlignedBB bb = s.getCollisionBoundingBox(world, pos);
        return bb != null && bb.maxY - bb.minY > 0.001;
    }

    /** A cell a body part can occupy: no liquid, and nothing solid in the way. */
    public static boolean hasRoom(IBlockAccess world, BlockPos pos) {
        IBlockState s = world.getBlockState(pos);
        if (s.getMaterial().isLiquid()) return false;
        return s.getMaterial().isReplaceable() || s.getCollisionBoundingBox(world, pos) == null;
    }

}
