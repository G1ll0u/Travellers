package com.jubitus.travellers.world.camp;

import com.jubitus.travellers.ground.BlockRules;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;




/**
 * Camp rule "may a camp touch this volume": every cell is air or natural-looking, never built
 * (BlockRules.isBuilt) nor a liquid/hazard. Failure mode is always "camp doesn't build here".
 */
public final class CampTerrainValidator {

    private CampTerrainValidator() {
    }

    /** True if every block in the inclusive cuboid [min, max] passes {@link #isCellNatural}. Requires loaded chunks. */
    public static boolean isVolumeNatural(World world, BlockPos min, BlockPos max) {
        for (BlockPos p : BlockPos.getAllInBoxMutable(min, max)) {
            if (!isCellNatural(world, p)) return false;
        }
        return true;
    }

    /** True if the block at {@code pos} is air, or neither built, liquid nor a hazard. */
    public static boolean isCellNatural(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock().isAir(state, world, pos)) return true;
        return !state.getMaterial().isLiquid() && !BlockRules.isHazard(state) && !BlockRules.isBuilt(state);
    }
}
