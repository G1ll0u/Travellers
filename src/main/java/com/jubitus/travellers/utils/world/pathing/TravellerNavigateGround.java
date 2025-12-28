package com.jubitus.travellers.utils.world.pathing;

import com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon.CampfireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.pathfinding.PathPoint;
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

    // --- NEW: compute the true surface Y for a path node (handles snow layers) ---
    private double surfaceYForNode(int x, int y, int z) {
        // In vanilla, node y is typically the "feet cell". The floor is usually in y-1,
        // but snow layers can be in y itself.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        double best = y; // fallback

        // Check y (snow layer can be here), y-1 (normal ground), and y-2 (safety)
        for (int dy = 0; dy >= -2; dy--) {
            pos.setPos(x, y + dy, z);
            IBlockState st = this.world.getBlockState(pos);

            AxisAlignedBB bb = st.getCollisionBoundingBox(this.world, pos);
            if (bb == null) continue;

            double top = (y + dy) + bb.maxY;
            if (top > best) best = top;
        }

        return best;
    }

    private Vec3d vecForPathPoint(PathPoint p) {
        double y = surfaceYForNode(p.x, p.y, p.z);
        return new Vec3d(p.x + 0.5D, y, p.z + 0.5D);
    }

    // --- NEW: override pathFollow so MoveHelper aims at correct Y (not integer p.y) ---
    @Override
    protected void pathFollow() {
        Vec3d entityPos = this.getEntityPosition();
        int idx = this.currentPath.getCurrentPathIndex();

        // vanilla-ish: try to advance nodes that are "reached"
        for (int i = idx; i < this.currentPath.getCurrentPathLength(); i++) {
            PathPoint pp = this.currentPath.getPathPointFromIndex(i);
            Vec3d v = vecForPathPoint(pp);

            double dx = Math.abs(this.entity.posX - v.x);
            double dz = Math.abs(this.entity.posZ - v.z);

            // keep vanilla horizontal reach behavior
            double reach = this.entity.width > 0.75F ? this.entity.width / 2.0F : 0.75D - this.entity.width / 2.0D;

            if (dx < reach && dz < reach) {
                this.currentPath.setCurrentPathIndex(i + 1);
            } else {
                break;
            }
        }

        // If path done, stop
        if (this.currentPath.isFinished()) return;

        // Drive movement toward current node, but with corrected Y
        PathPoint cur = this.currentPath.getPathPointFromIndex(this.currentPath.getCurrentPathIndex());
        Vec3d target = vecForPathPoint(cur);

        this.entity.getMoveHelper().setMoveTo(target.x, target.y, target.z, this.speed);
    }

    @Override
    protected Vec3d getEntityPosition() {
        // Important: use real feet Y, not rounded
        return new Vec3d(this.entity.posX, this.entity.getEntityBoundingBox().minY, this.entity.posZ);
    }
}


