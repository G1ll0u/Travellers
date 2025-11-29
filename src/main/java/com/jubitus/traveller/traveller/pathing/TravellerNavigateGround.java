package com.jubitus.traveller.traveller.pathing;

import com.jubitus.traveller.traveller.camp.CampfireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class TravellerNavigateGround extends PathNavigateGround {
    public TravellerNavigateGround(EntityLiving entity, World worldIn) {
        super(entity, worldIn);
    }

    @Override
    protected PathFinder getPathFinder() {
        TravellerNodeProcessor proc = new TravellerNodeProcessor();
        proc.setCanEnterDoors(true);
        proc.setCanOpenDoors(true);
        proc.setCanSwim(true);
        this.nodeProcessor = proc;
        return new PathFinder(proc);
    }

    /**
     * Robust direct-path test that forbids squeezing through diagonal 2-high corners.
     * Also treats water columns as navigable (swimming OK).
     */
    @Override
    protected boolean isDirectPathBetweenPoints(Vec3d a, Vec3d b, int sx, int sy, int sz) {
        // Force the pathfinder to always use full node graph.
        return false;
    }


    /**
     * 2-high clearance test for the entity footprint at grid (x,z), allowing water columns.
     */
    private boolean isFootprintClear(int x, int y, int z, int sx, int sy, int sz) {
        for (int ix = 0; ix < sx; ix++) {
            for (int iz = 0; iz < sz; iz++) {
                BlockPos feet = new BlockPos(x + ix, y, z + iz);
                IBlockState atFeet = world.getBlockState(feet);
                IBlockState atHead = world.getBlockState(feet.up());

                // --- NEVER STAND IN / ON THE MILLÉNAIRE FIRE PIT ---
                Block fire = CampfireBlock.get();
                if (fire != null) {
                    IBlockState below = world.getBlockState(feet.down());
                    if (atFeet.getBlock() == fire
                            || atHead.getBlock() == fire
                            || below.getBlock() == fire) {
                        return false;
                    }
                }

                // --- HARD-BLOCK other dangerous blocks (cactus, fire, magma, etc.) ---
                IBlockState below = world.getBlockState(feet.down());
                if (isDangerousBlock(atFeet) || isDangerousBlock(atHead) || isDangerousBlock(below)) {
                    return false;
                }

                // --- WATER HANDLING (your existing code) ---
                if (atFeet.getMaterial() == Material.WATER) {
                    // still allow swimming, but be sure the head has space
                    if (atHead.getMaterial() != Material.WATER
                            && !atHead.getMaterial().isReplaceable()) {
                        return false;
                    }
                    continue;
                }

                if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) {
                    return false;
                }

                if (!atFeet.getMaterial().isReplaceable()) return false;
                if (!atHead.getMaterial().isReplaceable()) return false;

                // you already had this, just moved 'below' up so we can reuse it:
                if (!supportsStandingSurface(feet.down(), below)) return false;
            }
        }
        return true;
    }

    private boolean isDangerousBlock(IBlockState state) {
        Block b = state.getBlock();
        Material m = state.getMaterial();

        // Vanilla cactus
        if (b == Blocks.CACTUS || m == Material.CACTUS) return true;

        // Vanilla fire & lava family
        if (b == Blocks.FIRE ||
                b == Blocks.LAVA || b == Blocks.FLOWING_LAVA) return true;

        // Other painful vanilla stuff (optional, but usually good)
        return b == Blocks.MAGMA || b == Blocks.LIT_FURNACE;

        // If you have a custom campfire that isn’t the Millénaire fire pit,
        // add it here too.
    }

    /**
     * Minimal “can stand on” check (accepts ice/glass like your AI helper).
     */
    private boolean supportsStandingSurface(BlockPos pos, IBlockState st) {
        if (st.getMaterial().isLiquid()) return false;

        BlockFaceShape shape = st.getBlockFaceShape(world, pos, EnumFacing.UP);
        if (shape == BlockFaceShape.SOLID) return true;

        AxisAlignedBB bb = st.getBoundingBox(world, pos);
        if (bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001) return true;

        Block b = st.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.FROSTED_ICE
                || b == Blocks.GLASS || b == Blocks.STAINED_GLASS;
    }

}

