package com.jubitus.travellers.camp.build;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * One block of a {@link StructureBuildPlan}: final world position and final (already
 * rotated/mirrored) block state, plus the raw (untransformed) tile-entity NBT if any — mirrored and
 * rotated at placement time (needs a live {@link net.minecraft.tileentity.TileEntity} instance to call
 * onto, so it can't be pre-baked into the NBT) exactly like vanilla {@code Template.addBlocksToWorld}.
 *
 * <p>{@link #companion}, when set, is the other half of a two-block vanilla block (door upper/lower,
 * bed head/foot) that must exist in the world at the same instant as this one — both blocks'
 * {@code neighborChanged} logic checks for its other half and self-destructs (dropping as an item) if
 * it's missing, so placing/breaking them one at a time with other steps in between is unsafe. The plan
 * builder folds the companion into this single step and omits its own entry from the plan.
 */
public final class BuildStep {

    public final BlockPos pos;
    public final IBlockState state;
    @Nullable public final NBTTagCompound tileNbt;
    public final Mirror mirror;
    public final Rotation rotation;
    @Nullable public BuildStep companion;

    public BuildStep(BlockPos pos, IBlockState state, @Nullable NBTTagCompound tileNbt, Mirror mirror, Rotation rotation) {
        this.pos = pos;
        this.state = state;
        this.tileNbt = tileNbt;
        this.mirror = mirror;
        this.rotation = rotation;
    }
}
