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
        // 1) Let super fill the basic neighbors
        int count = super.findPathOptions(options, current, target, maxDistance);

        // 2) Ladder-specific vertical neighbors (NO getSafePoint!)
        if (isLadderColumn(current.x, current.y, current.z)) {
            // up
            if (addLadderNeighbor(options, count, current.x, current.y + 1, current.z, target, maxDistance)) {
                count++;
            }
            // down (optional but recommended)
            if (addLadderNeighbor(options, count, current.x, current.y - 1, current.z, target, maxDistance)) {
                count++;
            }
        }

        // 3) your existing lily-pad hard-ban
        for (int i = 0; i < count; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            BlockPos feet = new BlockPos(p.x, p.y, p.z);
            Block under = this.blockaccess.getBlockState(feet.down()).getBlock();
            if (under == Blocks.WATERLILY || under instanceof BlockLilyPad) {
                options[i] = null;
            }
        }

        // 4) compact after nulls
        int w = 0;
        for (int i = 0; i < count; i++) {
            if (options[i] != null) {
                options[w++] = options[i];
            }
        }

        // 5) path-dirt preference, using compacted count
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

        // --- LADDER / VINE SUPPORT (NEW) ---
        if (block == Blocks.LADDER || block == Blocks.VINE) {
            // Treat standing “inside” a ladder block as valid
            return PathNodeType.WALKABLE;
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
        // --- NEW: limit how high we can “step out” of water ---
        // Mirror EntityAITravel.isStandable's swimming-exit rule.
        if (this.entity != null && this.entity.isInWater()) {
            // current mob feet Y (integer)
            double mobFeetY = Math.floor(this.entity.getEntityBoundingBox().minY);
            double dy = y - mobFeetY;

            // same cap as in isStandable: don't allow full 1-block jumps out of deep water
            final double maxExitStep = Math.min(this.entity.stepHeight, 0.8D);

            // If this node is considered walkable but sits too high above our current feet,
            // pretend it's blocked so the pathfinder won't aim for it.
            if (base == PathNodeType.WALKABLE && dy > maxExitStep) {
                return PathNodeType.BLOCKED;
            }
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
    private boolean isLadderColumn(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        Block bHere = this.blockaccess.getBlockState(pos).getBlock();
        Block bBelow = this.blockaccess.getBlockState(pos.down()).getBlock();

        return bHere == Blocks.LADDER || bHere == Blocks.VINE
                || bBelow == Blocks.LADDER || bBelow == Blocks.VINE;
    }
    private boolean addLadderNeighbor(PathPoint[] options,
                                      int index,
                                      int x, int y, int z,
                                      PathPoint target,
                                      float maxDistance) {
        // create or reuse PathPoint
        PathPoint p = this.openPoint(x, y, z);
        if (p == null || p.visited) return false;

        if (p.distanceTo(target) >= maxDistance) return false;

        PathNodeType t = this.getPathNodeType(this.blockaccess, x, y, z);
        if (t == PathNodeType.BLOCKED
                || t == PathNodeType.LAVA
                || t == PathNodeType.DAMAGE_FIRE
                || t == PathNodeType.DANGER_FIRE) {
            return false;
        }

        // WATER is ok only if you want water ladders; otherwise also reject:
        // if (t == PathNodeType.WATER) return false;

        options[index] = p;
        return true;
    }

}