package com.jubitus.travellers.decor;

import net.minecraft.block.Block;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * A 2x2 painting hung on a wall. The block is the bottom-left corner as seen from the front;
 * the model extends one block to the viewer's right and one block up.
 */
public class BlockWallPainting extends BlockHorizontal {

    private static final double T = 1.0D / 16.0D;
    private static final AxisAlignedBB BOX_NORTH = new AxisAlignedBB(-1.0D, 0.0D, 1.0D - T, 1.0D, 2.0D, 1.0D);
    private static final AxisAlignedBB BOX_SOUTH = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 2.0D, 2.0D, T);
    private static final AxisAlignedBB BOX_EAST = new AxisAlignedBB(0.0D, 0.0D, -1.0D, T, 2.0D, 1.0D);
    private static final AxisAlignedBB BOX_WEST = new AxisAlignedBB(1.0D - T, 0.0D, 0.0D, 1.0D, 2.0D, 2.0D);

    public BlockWallPainting() {
        super(Material.WOOD);
        setHardness(0.0F);
        setSoundType(SoundType.WOOD);
        setCreativeTab(CreativeTabs.DECORATIONS);
        setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    // INVARIANT: matches the blockstate rotations and the model, which extends toward rotateYCCW().
    private static EnumFacing rightOf(EnumFacing facing) {
        return facing.rotateYCCW();
    }

    private static boolean wallHolds(IBlockAccess world, BlockPos pos, EnumFacing facing) {
        BlockPos wall = pos.offset(facing.getOpposite());
        return world.getBlockState(wall).getBlockFaceShape(world, wall, facing) == BlockFaceShape.SOLID;
    }

    @Override
    public boolean canPlaceBlockOnSide(World world, BlockPos pos, EnumFacing side) {
        if (!side.getAxis().isHorizontal() || !wallHolds(world, pos, side)) return false;
        BlockPos right = pos.offset(rightOf(side));
        for (BlockPos cell : new BlockPos[]{right, pos.up(), right.up()}) {
            if (!world.getBlockState(cell).getBlock().isReplaceable(world, cell)) return false;
        }
        return true;
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        return getDefaultState().withProperty(FACING, facing);
    }

    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block block, BlockPos fromPos) {
        if (!wallHolds(world, pos, state.getValue(FACING))) {
            dropBlockAsItem(world, pos, state, 0);
            world.setBlockToAir(pos);
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        switch (state.getValue(FACING)) {
            case SOUTH: return BOX_SOUTH;
            case EAST: return BOX_EAST;
            case WEST: return BOX_WEST;
            default: return BOX_NORTH;
        }
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return NULL_AABB;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.UNDEFINED;
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.byHorizontalIndex(meta));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }
}
