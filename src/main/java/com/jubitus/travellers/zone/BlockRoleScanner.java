package com.jubitus.travellers.zone;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Decides whether a block counts toward one of a zone's block-role requirements (bed, chest, hitch...). */
@FunctionalInterface
public interface BlockRoleScanner {
    boolean matches(World world, BlockPos pos, IBlockState state);
}
