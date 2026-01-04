package com.jubitus.travellers.utils.world.pathing;

import com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon.CampfireBlock;
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
import net.minecraft.util.math.MathHelper;
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
    // How far the landing can be (2 = jump over 1-block gap, 3 = over 2-block gap, 4 = over 3-block gap)
// How far the landing can be (2 = jump over 1-block gap, 3 = over 2-block gap, 4 = over 3-block gap)
    private static final int MAX_JUMP_LAND_DIST = 4; // change to 3 if you want to be safer
    private static final int MAX_JUMP_UP = 1;              // can jump up 1 block
    private static final int MAX_JUMP_DOWN_SOLID = 2;      // allow drop-jumps down onto solid
    private static final int MAX_JUMP_DOWN_WATER = 32;      // allow bigger drops if landing is water
    private static final int MAX_WATER_JUMP_DIST = 3;

    private int addGapJumpNeighbors(PathPoint[] options, int count, PathPoint current, PathPoint target, float maxDistance) {

        // 4 cardinal directions only (diagonal gap jumps are messy and usually look bad)
        final int[][] DIRS = new int[][] { {1,0}, {-1,0}, {0,1}, {0,-1} };

        for (int[] d : DIRS) {
            int dx = d[0];
            int dz = d[1];

            // Try landing at 2..MAX_JUMP_LAND_DIST blocks away
// Try landing at MAX..2 blocks away (prefer LONGER, safer jumps)
            for (int dist = MAX_JUMP_LAND_DIST; dist >= 2; dist--) {

                int lx = current.x + dx * dist;
                int lz = current.z + dz * dist;

                for (int dy = MAX_JUMP_UP; dy >= -MAX_JUMP_DOWN_WATER; dy--) {
                    int ly = current.y + dy;

                    if (!canGapJump(current.x, current.y, current.z, lx, ly, lz, dx, dz, dist)) {
                        continue;
                    }

                    PathPoint p = this.openPoint(lx, ly, lz);
                    if (p == null || p.visited) continue;
                    if (p.distanceTo(target) >= maxDistance) continue;

                    float extra = 0.05f;
                    if (isWaterBlock(lx, ly, lz) && dy < 0) extra -= 0.30f;

                    float islandPenalty = 0.0f;
                    BlockPos afterBelow = new BlockPos(lx + dx, ly - 1, lz + dz);
                    if (isGapOrHazardBelow(afterBelow)) islandPenalty = 0.8f;

                    p.costMalus += extra + islandPenalty;

                    if (count >= options.length) return count;
                    options[count++] = p;

                    break;
                }
            }

        }

        return count;
    }

    private boolean canGapJump(int sx, int sy, int sz,
                               int lx, int ly, int lz,
                               int dx, int dz, int dist) {

        // landing must be standable (2-high clearance)
// landing can be solid-walkable OR water (special case)
        boolean landingWater = isWaterBlock(lx, ly, lz);

        if (landingWater) {
            if (!isWaterLandingOk(lx, ly, lz)) return false;
        } else {
            // Limit how far down we allow onto solid (but allow deeper for water)
            if ((ly - sy) < -MAX_JUMP_DOWN_SOLID) return false;
            if (!isTwoHighWalkable(lx, ly, lz)) return false;
        }


        boolean sawGapOrHazard = false;

        for (int i = 1; i < dist; i++) {
            int x = sx + dx * i;
            int y = sy;
            int z = sz + dz * i;

            BlockPos midFeet = new BlockPos(x, y, z);
            BlockPos midHead = midFeet.up();
            BlockPos midBelow = midFeet.down();

            // Must be able to pass through (air-like at feet and head)
            if (!isAirLike(midFeet) || !isAirLike(midHead)) return false;

            // Must actually be a gap/hazard under us (otherwise we'd jump on flat ground)
            if (isGapOrHazardBelow(midBelow)) {
                sawGapOrHazard = true;
            }
        }

        return sawGapOrHazard;
    }


    // Feet+head must be OPEN-ish so we can pass through while jumping
    private boolean isJumpAirColumn(int x, int y, int z) {
        PathNodeType feet = this.getPathNodeType(this.blockaccess, x, y, z);
        PathNodeType head = this.getPathNodeType(this.blockaccess, x, y + 1, z);

        // OPEN means air-like; WALKABLE means it thinks you can stand there (not what we want mid-air)
        boolean feetOk = (feet == PathNodeType.OPEN);
        boolean headOk = (head == PathNodeType.OPEN);

        return feetOk && headOk;
    }

    @Override
    public int findPathOptions(PathPoint[] options, PathPoint current, PathPoint target, float maxDistance) {
        // 1) Let super fill the basic neighbors
        int count = super.findPathOptions(options, current, target, maxDistance);
// --- NEW: allow "gap jumps" up to N blocks forward (adds long neighbors) ---
        count = addGapJumpNeighbors(options, count, current, target, maxDistance);

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
// NEW: avoid stepping into water unless we are already swimming
        if (this.entity != null && !this.entity.isInWater()) {
            for (int i = 0; i < count; i++) {
                PathPoint p = options[i];
                if (p == null) continue;

                PathNodeType t = this.getPathNodeType(this.blockaccess, p.x, p.y, p.z);
                if (t == PathNodeType.WATER && this.entity != null && !this.entity.isInWater()) {
                    int man = Math.abs(p.x - current.x) + Math.abs(p.z - current.z);
                    boolean isTarget = (p.x == target.x && p.y == target.y && p.z == target.z);

                    if (man <= 1 && !isTarget) {
                        p.costMalus += 8.0F; // "avoid water", but allow if needed
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
// --- 4b) NEW: forbid *unsafe* diagonal steps (no corner-cutting off cliffs) ---
//        int cx = current.x;
//        int cy = current.y;
//        int cz = current.z;
//
//        for (int i = 0; i < w; i++) {
//            PathPoint p = options[i];
//            if (p == null) continue;
//
//            int dx = p.x - cx;
//            int dz = p.z - cz;
//
//            // Diagonal move?
//            if (dx != 0 && dz != 0) {
//                int sx = cx + dx; // side X cell
//                int sz = cz + dz; // side Z cell
//                    boolean sideXSafe = isCardinalSafeForDiagonal(sx, cy, cz);
//                    boolean sideZSafe = isCardinalSafeForDiagonal(cx, cy, sz);
//                boolean canX = false, canZ = false;
//                    if (!sideXSafe || !sideZSafe) {
//                        options[i] = null;   // <-- this removes diagonals
//                    }
//                }
//            }

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

        if (!(feet == PathNodeType.WALKABLE)) return false;
// head must be open-ish
        return (head == PathNodeType.OPEN || head == PathNodeType.WALKABLE);
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
    private boolean isAirLike(BlockPos p) {
        IBlockState s = this.blockaccess.getBlockState(p);

        // NEW: liquids are NEVER air-like (water has null collision box, so we must special-case it)
        if (s.getMaterial().isLiquid()) return false;

        // passable if replaceable OR no collision box
        return s.getMaterial().isReplaceable() || s.getCollisionBoundingBox(this.blockaccess, p) == null;
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
    private boolean isWaterBlock(int x, int y, int z) {
        IBlockState s = this.blockaccess.getBlockState(new BlockPos(x, y, z));
        return s.getMaterial() == Material.WATER;
    }
    private boolean isWaterLandingOk(int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        IBlockState sf = this.blockaccess.getBlockState(feet);
        if (sf.getMaterial() != Material.WATER) return false;

        // Don’t “land” in water with a solid block immediately above (bonks look bad)
        BlockPos head = feet.up();
        IBlockState sh = this.blockaccess.getBlockState(head);
        if (!sh.getMaterial().isReplaceable() && sh.getCollisionBoundingBox(this.blockaccess, head) != null) {
            return false;
        }

        // Optional: require at least 1-deep water (feet water is enough usually)
        // Optional stricter: require 2-deep:
        // if (this.blockaccess.getBlockState(feet.down()).getMaterial() != Material.WATER) return false;

        return true;
    }


}