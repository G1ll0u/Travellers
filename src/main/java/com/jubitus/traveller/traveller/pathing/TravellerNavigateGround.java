package com.jubitus.traveller.traveller.pathing;

import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
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
        if (this.world == null) return false;

        // current grid cell (feet), target grid cell
        int x0 = net.minecraft.util.math.MathHelper.floor(a.x);
        int z0 = net.minecraft.util.math.MathHelper.floor(a.z);
        int y = net.minecraft.util.math.MathHelper.floor(a.y);

        int x1 = net.minecraft.util.math.MathHelper.floor(b.x);
        int z1 = net.minecraft.util.math.MathHelper.floor(b.z);

        int dx = Integer.compare(x1, x0);
        int dz = Integer.compare(z1, z0);

        // Early out: start/goal same column
        if (x0 == x1 && z0 == z1) {
            return isFootprintClear(x0, y, z0, sx, sy, sz);
        }

        // DDA along the dominant axis
        double rx = (dx == 0) ? Double.POSITIVE_INFINITY
                : (dx > 0 ? ((x0 + 1) - a.x) : (a.x - x0)) / Math.abs(b.x - a.x);
        double rz = (dz == 0) ? Double.POSITIVE_INFINITY
                : (dz > 0 ? ((z0 + 1) - a.z) : (a.z - z0)) / Math.abs(b.z - a.z);

        double tMaxX = rx;
        double tMaxZ = rz;
        double tDeltaX = (dx == 0) ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(b.x - a.x);
        double tDeltaZ = (dz == 0) ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(b.z - a.z);

        // Check starting cell
        if (!isFootprintClear(x0, y, z0, sx, sy, sz)) return false;

        int cx = x0, cz = z0;

        // Walk until we reach the target cell
        while (cx != x1 || cz != z1) {
            // Decide which boundary we cross next
            boolean stepX = tMaxX < tMaxZ;
            boolean stepZ = tMaxZ < tMaxX;
            boolean stepDiag = !stepX && !stepZ; // equal -> diagonal

            if (stepX) {
                cx += dx;
                tMaxX += tDeltaX;
                if (!isFootprintClear(cx, y, cz, sx, sy, sz)) return false;
            } else if (stepZ) {
                cz += dz;
                tMaxZ += tDeltaZ;
                if (!isFootprintClear(cx, y, cz, sx, sy, sz)) return false;
            } else { // diagonal corner: require BOTH adjacent cardinals clear -> no squeezing
                // Peek the two cardinals we would “cut across”
                int nx = cx + dx;
                int nz = cz + dz;
                // both cardinals must be clear
                if (!isFootprintClear(nx, y, cz, sx, sy, sz)) return false;
                if (!isFootprintClear(cx, y, nz, sx, sy, sz)) return false;

                // now move diagonally
                cx = nx;
                cz = nz;
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
                if (!isFootprintClear(cx, y, cz, sx, sy, sz)) return false;
            }
        }
        return true;
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

                // ❌ Block water here so “direct path” won’t cross it.
                if (atFeet.getMaterial() == net.minecraft.block.material.Material.WATER
                        || atHead.getMaterial() == net.minecraft.block.material.Material.WATER) {
                    return false;
                }

                // On land: feet & head must be passable, and below must support standing
                if (!atFeet.getMaterial().isReplaceable()) return false;
                if (!atHead.getMaterial().isReplaceable()) return false;

                IBlockState below = world.getBlockState(feet.down());
                if (!supportsStandingSurface(feet.down(), below)) return false;
            }
        }
        return true;
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

