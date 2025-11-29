package com.jubitus.traveller.traveller.pathing;

import com.jubitus.traveller.traveller.camp.CampfireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import javax.annotation.Nullable;

public class TravellerNodeProcessor extends WalkNodeProcessor {


    private static final ResourceLocation PATH_DIRT_ID = new ResourceLocation("millenaire", "pathdirt");
    @Nullable
    private Block pathDirt;

    @Override
    public void init(IBlockAccess worldIn, EntityLiving entityIn) {
        super.init(worldIn, entityIn);
        if (pathDirt == null) {
            pathDirt = net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS.getValue(PATH_DIRT_ID);
        }
    }

    @Override
    public int findPathOptions(PathPoint[] options, PathPoint current, PathPoint target, float maxDistance) {
        int count = super.findPathOptions(options, current, target, maxDistance);

        for (int i = 0; i < count; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            // diagonal squeeze check
            int dx = p.x - current.x;
            int dz = p.z - current.z;

            if (dx != 0 && dz != 0) {

            }


            // hard-ban lilypad underfoot
            BlockPos feet = new BlockPos(p.x, p.y, p.z);
            Block under = this.blockaccess.getBlockState(feet.down()).getBlock();
            if (under == Blocks.WATERLILY || under instanceof BlockLilyPad) {
                options[i] = null;
            }
        }

        // 2) compact after nulling
        int w = 0;
        for (int i = 0; i < count; i++) if (options[i] != null) options[w++] = options[i];

        // 3) your path-dirt preference logic (unchanged), iterate to w
        for (int i = 0; i < w; i++) {
            PathPoint p = options[i];

            if (p == null) continue;
            BlockPos feet = new BlockPos(p.x, p.y, p.z);
            if (isPathPreferred(feet)) {
                p.costMalus = Math.min(p.costMalus, p.costMalus - 0.75f);
            } else if (isPathNearby(feet)) {
                p.costMalus = Math.min(p.costMalus, p.costMalus - 0.35f);
            }
        }
        return w;
    }

    @Override
    protected PathNodeType getPathNodeTypeRaw(IBlockAccess worldIn, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState state = worldIn.getBlockState(pos);
        Block block = state.getBlock();

        // --- HARD BLOCK lily pads (existing) ---
        if (block == net.minecraft.init.Blocks.WATERLILY || block instanceof net.minecraft.block.BlockLilyPad) {
            return PathNodeType.BLOCKED;
        }
        IBlockState below = worldIn.getBlockState(pos.down());
        if (below.getBlock() == net.minecraft.init.Blocks.WATERLILY
                || below.getBlock() instanceof net.minecraft.block.BlockLilyPad) {
            return PathNodeType.BLOCKED;
        }

        // --- HARD BLOCK the Millénaire fire pit ---
        Block fire = CampfireBlock.get();
        if (fire != null) {
            if (block == fire || below.getBlock() == fire) {
                return PathNodeType.BLOCKED;
            }
        }

        PathNodeType base = super.getPathNodeTypeRaw(worldIn, x, y, z);

        // Fence-gate reclass (your existing logic)...
        if (block instanceof net.minecraft.block.BlockFenceGate) {
            boolean open = state.getValue(net.minecraft.block.BlockFenceGate.OPEN);
            return open ? PathNodeType.DOOR_OPEN : PathNodeType.DOOR_WOOD_CLOSED;
        }
        if (base == PathNodeType.FENCE && block instanceof net.minecraft.block.BlockFenceGate) {
            boolean open = state.getValue(net.minecraft.block.BlockFenceGate.OPEN);
            return open ? PathNodeType.DOOR_OPEN : PathNodeType.DOOR_WOOD_CLOSED;
        }

        return base;
    }

    private boolean isLiquidNode(PathNodeType t) {
        return t == PathNodeType.WATER;
    }


    private boolean isTwoHighWalkable(int x, int y, int z) {
        PathNodeType feet = this.getPathNodeType(this.blockaccess, x, y, z);
        PathNodeType head = this.getPathNodeType(this.blockaccess, x, y + 1, z);

        if (feet != PathNodeType.WALKABLE) return false;
        // head only needs to be open, not walkable
        return head == PathNodeType.WALKABLE || head == PathNodeType.OPEN;
    }

    private boolean isDropOff(int x, int y, int z) {
        PathNodeType below = this.getPathNodeType(this.blockaccess, x, y - 1, z);
        return below == PathNodeType.DANGER_OTHER;
    }


    private boolean isPathPreferred(BlockPos feet) {
        if (pathDirt == null) return false;
        IBlockState below = this.blockaccess.getBlockState(feet.down());
        return below.getBlock() == pathDirt;
    }

    // check a 1-block ring for path (helps the path “lock on” to gates/roads)
    private boolean isPathNearby(BlockPos feet) {
        if (pathDirt == null) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                IBlockState s = this.blockaccess.getBlockState(new BlockPos(feet.getX() + dx, feet.getY() - 1, feet.getZ() + dz));
                if (s.getBlock() == pathDirt) return true;
            }
        }
        return false;
    }

}