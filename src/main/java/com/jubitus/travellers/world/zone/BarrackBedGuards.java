package com.jubitus.travellers.world.zone;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;

/** Detects a Barrack worker bed being broken in real time so the owner can be notified immediately. */
@Mod.EventBusSubscriber
public final class BarrackBedGuards {

    private BarrackBedGuards() {
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        World w = e.getWorld();
        if (!(w instanceof WorldServer)) return;

        BlockPos headPos = resolveBedHeadPos(e.getState(), w, e.getPos());
        if (headPos == null) return;

        PlayerZoneManager.get(w).onBedBroken((WorldServer) w, headPos);
    }

    /** Beds are tracked by their HEAD half only (see {@link BlockRoleScanners#BED}); resolve foot -> head. */
    @Nullable
    private static BlockPos resolveBedHeadPos(IBlockState st, World world, BlockPos pos) {
        Block b = st.getBlock();
        boolean isBed;
        try {
            isBed = (b instanceof BlockBed) || b.isBed(st, world, pos, null);
        } catch (Throwable t) {
            isBed = b instanceof BlockBed;
        }
        if (!isBed) return null;

        if (b instanceof BlockBed) {
            try {
                EnumFacing facing = st.getValue(BlockBed.FACING);
                BlockBed.EnumPartType part = st.getValue(BlockBed.PART);
                return part == BlockBed.EnumPartType.HEAD ? pos : pos.offset(facing);
            } catch (Throwable ignored) {
                // non-standard BlockBed: count it anyway, matching the BED scanner's fallback
            }
        }
        return pos;
    }
}
