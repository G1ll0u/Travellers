package com.jubitus.travellers.world.zone;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockFence;
import net.minecraft.init.Blocks;

/**
 * Standard {@link BlockRoleScanner}s shared by every zone type's creation/validation scan. Moved
 * here verbatim from the original lone-barrack-only bed/chest detection so both zone types (and any
 * future one) scan through the exact same logic.
 */
public final class BlockRoleScanners {

    private BlockRoleScanners() {
    }

    public static final BlockRoleScanner CHEST = (world, pos, state) -> {
        Block b = state.getBlock();
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST;
    };

    /**
     * Uses {@code Block#isBed} so modded beds are supported. For standard {@link BlockBed}-derived
     * beds only the HEAD half counts, to avoid double counting.
     */
    public static final BlockRoleScanner BED = (world, pos, state) -> {
        Block b = state.getBlock();
        boolean isBed;
        try {
            isBed = (b instanceof BlockBed) || b.isBed(state, world, pos, null);
        } catch (Throwable t) {
            isBed = b instanceof BlockBed;
        }
        if (!isBed) return false;
        if (b instanceof BlockBed) {
            try {
                return state.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD;
            } catch (Throwable ignored) {
                // non-standard BlockBed: count it anyway
            }
        }
        return true;
    };

    /** Matches a vanilla fence - the block a leash knot can actually attach to
     * ({@code EntityLeashKnot.getKnotForPosition} requires one). Fence gates deliberately excluded:
     * vanilla doesn't spawn leash knots on them either. */
    public static final BlockRoleScanner HITCH = (world, pos, state) -> state.getBlock() instanceof BlockFence;
}
