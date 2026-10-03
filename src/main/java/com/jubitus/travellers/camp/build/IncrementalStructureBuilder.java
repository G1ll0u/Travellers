package com.jubitus.travellers.camp.build;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Places or breaks a single {@link BuildStep} in the world — the primitive shared by the loaded
 * (animated, one block at a time) and virtual/reconcile (bulk catch-up) camp build paths. Entity-agnostic:
 * callers handle any entity-specific animation (arm swing) themselves right after calling this.
 */
public final class IncrementalStructureBuilder {

    private IncrementalStructureBuilder() {
    }

    /**
     * Places one block (and its {@link BuildStep#companion}, if any — see {@link BuildStep}) with
     * vanilla place semantics (flag 3: notify + relight) and its place sound.
     */
    public static void placeStep(World world, BuildStep step) {
        placeSingle(world, step);
        if (step.companion != null) placeSingle(world, step.companion);
    }

    private static void placeSingle(World world, BuildStep step) {
        world.setBlockState(step.pos, step.state, 3);
        if (step.tileNbt != null) {
            TileEntity te = world.getTileEntity(step.pos);
            if (te != null) {
                NBTTagCompound nbt = step.tileNbt.copy();
                nbt.setInteger("x", step.pos.getX());
                nbt.setInteger("y", step.pos.getY());
                nbt.setInteger("z", step.pos.getZ());
                te.readFromNBT(nbt);
                te.mirror(step.mirror);
                te.rotate(step.rotation);
            }
        }
        SoundType sound = step.state.getBlock().getSoundType();
        world.playSound(null, step.pos, sound.getPlaceSound(), SoundCategory.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
    }

    /** Breaks a {@link BuildStep} and its companion (if any) together — see {@link #breakStep(World, BlockPos)}. */
    public static void breakStep(World world, BuildStep step) {
        breakStep(world, step.pos);
        if (step.companion != null) breakStep(world, step.companion.pos);
    }

    /**
     * Breaks one block with the exact vanilla "player breaking a block" particle + sound event
     * (world event 2001), then clears it with no drops and no neighbour-break cascade. Takes a plain
     * position (not a {@link BuildStep}) since it reads whatever is currently at {@code pos} — useful
     * for one-off breaks (the firepit) as well as plan-driven teardown.
     */
    public static void breakStep(World world, BlockPos pos) {
        IBlockState current = world.getBlockState(pos);
        if (!current.getBlock().isAir(current, world, pos)) {
            world.playEvent(2001, pos, Block.getStateId(current));
        }
        if (world.getTileEntity(pos) != null) world.removeTileEntity(pos);
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
    }
}
