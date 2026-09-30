package com.jubitus.travellers.world.camp;

import com.jubitus.travellers.entity.entityai.ambient.camp.campcommon.CampfireBlock;
import com.jubitus.travellers.world.camp.shelter.TerrainSnapshot;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Places and removes the decorative Millénaire fire pit at a campsite center. Snapshot-backed so
 * removal restores the exact terrain and relights (via {@link TerrainSnapshot}).
 */
public final class CampFirepit {

    private CampFirepit() {
    }

    /** Places a lit fire pit at {@code firePos}, returning the snapshot needed to remove it later. Null if Millénaire is absent. */
    @Nullable
    public static TerrainSnapshot place(World world, BlockPos firePos) {
        IBlockState st = CampfireBlock.litStateFacingYaw(0f);
        if (st == null) return null; // Millénaire fire pit not available
        TerrainSnapshot snapshot = TerrainSnapshot.capture(world, firePos, 1, 1, 1);
        world.setBlockState(firePos, st, 3);
        if (world instanceof net.minecraft.world.WorldServer) {
            com.jubitus.travellers.world.camp.shelter.CampLighting.refresh(
                    (net.minecraft.world.WorldServer) world, firePos, 1, 1, 1);
        }
        return snapshot;
    }

    public static void remove(World world, TerrainSnapshot snapshot) {
        snapshot.restore(world);
    }

    /** True if the block at {@code pos} is Millénaire's fire pit (lit or not). */
    public static boolean isFirepitAt(World world, BlockPos pos) {
        net.minecraft.block.Block b = CampfireBlock.get();
        return b != null && world.getBlockState(pos).getBlock() == b;
    }

    /**
     * Flips the {@code lit} property of an already-placed fire pit in place (keeps alignment and its
     * tile entity), refreshing lighting so the 15→0 light change propagates. Puffs smoke when going
     * out. No-op if the block at {@code pos} isn't Millénaire's fire pit anymore.
     */
    public static void setLit(World world, BlockPos pos, boolean lit) {
        if (!isFirepitAt(world, pos)) return;
        IBlockState st = world.getBlockState(pos);
        if (CampfireBlock.isLit(st) == lit) return;
        IBlockState flipped = CampfireBlock.withLit(st, lit);
        if (flipped == null) return;
        world.setBlockState(pos, flipped, 3);
        if (!lit) {
            world.playSound(null, pos, net.minecraft.init.SoundEvents.BLOCK_FIRE_EXTINGUISH,
                    net.minecraft.util.SoundCategory.BLOCKS, 0.5F, 1.0F);
        }
        if (world instanceof net.minecraft.world.WorldServer) {
            net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
            com.jubitus.travellers.world.camp.shelter.CampLighting.refresh(ws, pos, 1, 1, 1);
            if (!lit) {
                ws.spawnParticle(net.minecraft.util.EnumParticleTypes.SMOKE_LARGE,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.01);
            }
        }
    }
}
