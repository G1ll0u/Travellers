package com.jubitus.travellers.utils.world.pathing;

import com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon.CampfireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
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
//        if (isLadderColumn(current.x, current.y, current.z)) {
//            if (addLadderNeighbor(options, count, current.x, current.y + 1, current.z, target, maxDistance)) {
//                count++;
//            }
//            if (addLadderNeighbor(options, count, current.x, current.y - 1, current.z, target, maxDistance)) {
//                count++;
//            }
//        }

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
// --- NEW: filter neighbors by TRUE step height (snow layers / fractional surfaces) ---
        double curSurfY = surfaceYForNode(current.x, current.y, current.z);
        double maxUp = this.entity.stepHeight + 1e-3;

        for (int i = 0; i < w; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            double nextSurfY = surfaceYForNode(p.x, p.y, p.z);

            // If the floor rises more than we can step/jump-handle as a "walk" move, reject it.
            // (This prevents paths that lead to infinite pushing at snow-block+layer edges.)
            if ((nextSurfY - curSurfY) > maxUp) {
                options[i] = null;
            }
        }

// compact again
        int w3 = 0;
        for (int i = 0; i < w; i++) {
            if (options[i] != null) {
                options[w3++] = options[i];
            }
        }
        w = w3;
// --- 4b) NEW: forbid *unsafe* diagonal steps (no corner-cutting off cliffs) ---
        int cx = current.x;
        int cy = current.y;
        int cz = current.z;

        for (int i = 0; i < w; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            int dx = p.x - cx;
            int dz = p.z - cz;

            // Diagonal move?
            if (dx != 0 && dz != 0) {
                int sx = cx + dx; // side X cell
                int sz = cz + dz; // side Z cell

                boolean sideXSafe = isCardinalSafeForDiagonal(sx, cy, cz);
                boolean sideZSafe = isCardinalSafeForDiagonal(cx, cy, sz);

                // If either “side” is blocked or a drop-off, do NOT allow the diagonal.
                if (!sideXSafe || !sideZSafe) {
                    options[i] = null;
                }
            }
        }

// Re-compact after killing unsafe diagonals
        int w2 = 0;
        for (int i = 0; i < w; i++) {
            if (options[i] != null) {
                options[w2++] = options[i];
            }
        }
        w = w2;

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

        // ... your existing hard blocks ...

        PathNodeType base = super.getPathNodeTypeRaw(worldIn, x, y, z);

        // ... your fence gate reclass ...

        if (this.entity != null) {
            double mobFeetY = this.entity.getEntityBoundingBox().minY;

            // ---- NEW: snow-layer fractional step check ----
            // If the target node is a snow layer, stepping onto it is "y + layers/8" not "y".
            if (base == PathNodeType.WALKABLE && block == Blocks.SNOW_LAYER) {
                int layers = snowLayersAt(state);          // 1..8
                double extra = layers / 8.0;               // 0.125..1.0
                double dyEff = (y + extra) - mobFeetY;     // <-- effective vertical step

                // Use actual stepHeight (yours is 1.0)
                if (dyEff > this.entity.stepHeight + 1e-3) {
                    return PathNodeType.BLOCKED;
                }
            }

            // Keep your water-exit limiter (works fine)
            if (this.entity.isInWater()) {
                double dy = y - mobFeetY;
                final double maxExitStep = Math.min(this.entity.stepHeight, 0.8D);
                if (base == PathNodeType.WALKABLE && dy > maxExitStep) {
                    return PathNodeType.BLOCKED;
                }
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

    private boolean isCardinalSafeForDiagonal(int x, int y, int z) {
        PathNodeType t = this.getPathNodeType(this.blockaccess, x, y, z);

        // Fully blocked or obviously bad
        if (t == PathNodeType.BLOCKED
                || t == PathNodeType.LAVA
                || t == PathNodeType.DAMAGE_FIRE
                || t == PathNodeType.DANGER_FIRE
                || t == PathNodeType.DAMAGE_CACTUS) {
            return false;
        }

        // Treat drop-offs as unsafe for diagonal corner-cutting
        if (t == PathNodeType.DANGER_OTHER) {
            return false;
        }

        // OPEN / WALKABLE / WATER etc. are OK here
        return true;
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
    private static int snowLayersAt(IBlockState s) {
        if (s.getBlock() == Blocks.SNOW_LAYER) {
            try { return s.getValue(net.minecraft.block.BlockSnow.LAYERS); }
            catch (Throwable ignored) {}
        }
        return 0;
    }
    private double surfaceYForNode(int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double best = y;

        for (int dy = 0; dy >= -2; dy--) {
            pos.setPos(x, y + dy, z);
            IBlockState st = this.blockaccess.getBlockState(pos);

            AxisAlignedBB bb = st.getBoundingBox(this.blockaccess, pos);
            if (bb == null || bb == Block.NULL_AABB) continue;

            double top = (y + dy) + bb.maxY;
            if (top > best) best = top;
        }
        return best;
    }

}