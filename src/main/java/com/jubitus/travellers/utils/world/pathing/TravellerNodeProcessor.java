package com.jubitus.travellers.utils.world.pathing;

import com.jubitus.travellers.utils.world.MPBIntegration;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.material.Material;
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
import org.millenaire.common.block.IBlockPath;

import javax.annotation.Nullable;

public class TravellerNodeProcessor extends WalkNodeProcessor {


    private static final ResourceLocation PATH_DIRT_ID = new ResourceLocation("millenaire", "pathdirt");

    @Nullable
    private Block pathDirt;
    @Nullable
    private Block mpbPathBlock;
    @Nullable
    private Block mpbPathSlab;
    private boolean followerMode = false;

    @Override
    public void init(IBlockAccess worldIn, EntityLiving entityIn) {
        super.init(worldIn, entityIn);
        if (pathDirt == null) {
            pathDirt = net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS.getValue(PATH_DIRT_ID);
        }
        if (mpbPathBlock == null && MPBIntegration.PRESENT) {
            mpbPathBlock = net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS
                    .getValue(new net.minecraft.util.ResourceLocation("millpathbuilder", "path_block"));
            mpbPathSlab = net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS
                    .getValue(new net.minecraft.util.ResourceLocation("millpathbuilder", "path_slab"));
        }
    }

    @Override
    public int findPathOptions(PathPoint[] options, PathPoint current, PathPoint target, float maxDistance) {
        int count = super.findPathOptions(options, current, target, maxDistance);
        for (int i = 0; i < count; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            BlockPos feet = new BlockPos(p.x, p.y, p.z);
            if (isImmediateHazard(feet) || isImmediateHazard(feet.down())) {
                options[i] = null;
                continue;
            }
            Block under = this.blockaccess.getBlockState(feet.down()).getBlock();
            if (under == Blocks.WATERLILY || under instanceof BlockLilyPad) {
                options[i] = null;
            }

        }
        if (this.entity != null && !this.entity.isInWater()) {
            // Low enough that paths through water are chosen when the land detour is long,
            // high enough that land routes are preferred when only a few blocks longer.
            float waterMalus = followerMode ? 1.5F : 2.5F;
            for (int i = 0; i < count; i++) {
                PathPoint p = options[i];
                if (p == null) continue;

                PathNodeType t = this.getPathNodeType(this.blockaccess, p.x, p.y, p.z);
                if (t == PathNodeType.WATER) {
                    int man = Math.abs(p.x - current.x) + Math.abs(p.z - current.z);
                    boolean isTarget = (p.x == target.x && p.y == target.y && p.z == target.z);
                    if (man <= 1 && !isTarget) {
                        p.costMalus += waterMalus;
                    }
                }
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
/// --- 4b) Allow diagonals, but forbid "corner cutting" ---
// If moving diagonally, both side cardinals must be passable too.
// This prevents trying to squeeze between two diagonal solid blocks.
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
                int stepX = Integer.signum(dx);
                int stepZ = Integer.signum(dz);

                // check the two "side" squares we would skim past
                // use a conservative Y check if your diagonal can be up/down
                int yCheck = Math.max(cy, p.y);

                boolean side1Ok = isCardinalColumnClear(cx + stepX, yCheck, cz);
                boolean side2Ok = isCardinalColumnClear(cx, yCheck, cz + stepZ);

                if (!side1Ok || !side2Ok) {
                    options[i] = null; // reject only the unsafe diagonal
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

        // 5) path preference, using compacted count
        for (int i = 0; i < w; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            BlockPos feet = new BlockPos(p.x, p.y, p.z);
            if (isPathPreferred(feet)) {
                p.costMalus -= isMPBPath(feet) ? 1.2f : 0.75f;
            } else if (isPathNearby(feet)) {
                p.costMalus -= 0.5f;
            }
        }

        return w;
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

    private boolean isCardinalColumnClear(int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);

        // liquids should block corner-cutting
        IBlockState sf = this.blockaccess.getBlockState(feet);
        if (sf.getMaterial().isLiquid()) return false;

        // feet/head must be empty-ish (no collision box)
        if (sf.getCollisionBoundingBox(this.blockaccess, feet) != null) return false;

        BlockPos head = feet.up();
        IBlockState sh = this.blockaccess.getBlockState(head);
        if (sh.getMaterial().isLiquid()) return false;
        if (sh.getCollisionBoundingBox(this.blockaccess, head) != null) return false;

        // Optional: prevent diagonals that skim a drop/hazard
        return !isGapOrHazardBelow(feet.down());
    }

    private boolean isAnyPathBlock(Block b) {
        if (b instanceof IBlockPath) return true;
        if (mpbPathBlock != null && b == mpbPathBlock) return true;
        if (mpbPathSlab != null && b == mpbPathSlab) return true;
        return false;
    }

    private boolean isPathPreferred(BlockPos feet) {
        return isAnyPathBlock(this.blockaccess.getBlockState(feet.down()).getBlock());
    }

    private boolean isMPBPath(BlockPos feet) {
        Block b = this.blockaccess.getBlockState(feet.down()).getBlock();
        return (mpbPathBlock != null && b == mpbPathBlock) || (mpbPathSlab != null && b == mpbPathSlab);
    }

    // check a 1-block ring for path (helps the path “lock on” to gates/roads)
    private boolean isPathNearby(BlockPos feet) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block b = this.blockaccess.getBlockState(
                        new BlockPos(feet.getX() + dx, feet.getY() - 1, feet.getZ() + dz)).getBlock();
                if (isAnyPathBlock(b)) return true;
            }
        }
        return false;
    }

    private boolean isGapOrHazardBelow(BlockPos below1) {
        IBlockState s1 = this.blockaccess.getBlockState(below1);
        Material m1 = s1.getMaterial();

        // liquids count as hazards
        if (m1 == Material.LAVA) return true;
        if (m1 == Material.WATER) return true;

        // 2-deep hole: below1 is air-like AND below2 is air-like
        if (s1.getCollisionBoundingBox(this.blockaccess, below1) != null) return false;

        BlockPos below2 = below1.down();
        IBlockState s2 = this.blockaccess.getBlockState(below2);
        Material m2 = s2.getMaterial();

        if (m2 == Material.LAVA) return true;
        if (m2 == Material.WATER) return true;

        return s2.getCollisionBoundingBox(this.blockaccess, below2) == null;
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

    private static int snowLayersAt(IBlockState s) {
        if (s.getBlock() == Blocks.SNOW_LAYER) {
            try {
                return s.getValue(net.minecraft.block.BlockSnow.LAYERS);
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }


    public void setFollowerMode(boolean followerMode) {
        this.followerMode = followerMode;
    }
    private boolean isImmediateHazard(BlockPos pos) {
        IBlockState s = this.blockaccess.getBlockState(pos);
        Block b = s.getBlock();
        Material m = s.getMaterial();

        if (m == Material.LAVA) return true;
        if (b == Blocks.FIRE) return true;
        if (b == Blocks.MAGMA) return true; // si ta version le possède

        return false;
    }
}