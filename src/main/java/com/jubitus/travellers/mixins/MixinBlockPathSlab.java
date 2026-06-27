package com.jubitus.travellers.mixins;

import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.millenaire.common.block.BlockPathSlab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Corrects BlockPathSlab bottom-half height from 7/16 to 8/16 (standard slab).
 *
 * Bug: EntityMoveHelper fires a premature jump when targetY - entityY > 0.5.
 * With a 7/16 slab in a hole, dY = 0.5625 triggers the jump before the entity
 * reaches the block edge. The entity jumps straight up with no horizontal
 * displacement and lands back on the slab — stuck indefinitely.
 * With 8/16 height, dY = 0.5 (not strictly greater), so the jump is never
 * triggered and vanilla step-up handles the transition at the block edge.
 */
@Mixin(value = BlockPathSlab.class, remap = false)
public class MixinBlockPathSlab {

    private static final AxisAlignedBB FIXED_BOTTOM_HALF = new AxisAlignedBB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);

    // func_185496_a = getBoundingBox(IBlockState, IBlockAccess, BlockPos)
    @Inject(method = "func_185496_a", at = @At("HEAD"), cancellable = true, remap = false)
    private void travellers$fixBottomSlabHeight(IBlockState state, IBlockAccess source, BlockPos pos,
                                                CallbackInfoReturnable<AxisAlignedBB> cir) {
        if (state.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.BOTTOM) {
            cir.setReturnValue(FIXED_BOTTOM_HALF);
        }
    }
}
