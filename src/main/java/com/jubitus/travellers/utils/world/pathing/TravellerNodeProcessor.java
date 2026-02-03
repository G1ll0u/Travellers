package com.jubitus.travellers.utils.world.pathing;

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

import javax.annotation.Nullable;

public class TravellerNodeProcessor extends WalkNodeProcessor {


    private static final ResourceLocation PATH_DIRT_ID = new ResourceLocation("millenaire", "pathdirt");
    // How far the landing can be (2 = jump over 1-block gap, 3 = over 2-block gap, 4 = over 3-block gap)
// How far the landing can be (2 = jump over 1-block gap, 3 = over 2-block gap, 4 = over 3-block gap)
    private static final int MAX_JUMP_LAND_DIST = 4; // change to 3 if you want to be safer
    private static final int MAX_JUMP_UP = 1;              // can jump up 1 block
    private static final int MAX_JUMP_DOWN_SOLID = 2;      // allow drop-jumps down onto solid
    private static final int MAX_JUMP_DOWN_WATER = 24;      // allow bigger drops if landing is water
    private static final int MAX_WATER_JUMP_DIST = 3;
    private static final int MAX_DROP_SOLID = 8;   // tune 4..12
    private static final int MAX_DROP_WATER = 16;  // keep big for water shafts
    @Nullable
    private Block pathDirt;
    private boolean followerMode = false;

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
// --- NEW: allow "gap jumps" up to N blocks forward (adds long neighbors) ---
// Followers: keep pathfinding cheap.
// Travel AI: allow fancy jumps/drops.
        if (!followerMode) {
            count = addGapJumpNeighbors(options, count, current, target, maxDistance);
            count = addDropDownNeighbors(options, count, current, target, maxDistance);
        } else {
            // Optional: allow a *small* version for followers (cheap)
            count = addGapJumpNeighborsFollowerCheap(options, count, current, target, maxDistance);
            count = addDropDownNeighborsFollowerCheap(options, count, current, target, maxDistance);
        }
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

    private int addGapJumpNeighbors(PathPoint[] options, int count,
                                    PathPoint current, PathPoint target, float maxDistance) {

        // 4 cardinal directions only
        final int[][] DIRS = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] d : DIRS) {
            int dx = d[0];
            int dz = d[1];

            // Prefer longer jumps
            for (int dist = MAX_JUMP_LAND_DIST; dist >= 2; dist--) {

                int lx = current.x + dx * dist;
                int lz = current.z + dz * dist;

                // ✅ 1) Corridor check ONCE (independent of landing Y)
                if (!corridorAllowsGapJump(current.x, current.y, current.z, dx, dz, dist)) {
                    continue;
                }

                // ✅ 2) Try SOLID landings only in a small dy range (fast)
                //     (you already limit solid drop-jumps to MAX_JUMP_DOWN_SOLID anyway)
                boolean added = false;

                for (int dy = MAX_JUMP_UP; dy >= -MAX_JUMP_DOWN_SOLID; dy--) {
                    int ly = current.y + dy;

                    if (!isTwoHighWalkable(lx, ly, lz)) continue;

                    PathPoint p = this.openPoint(lx, ly, lz);
                    if (p == null || p.visited) continue;
                    if (p.distanceTo(target) >= maxDistance) continue;

                    float extra = 0.05f;
                    // small island penalty like before
                    float islandPenalty = 0.0f;
                    BlockPos afterBelow = new BlockPos(lx + dx, ly - 1, lz + dz);
                    if (isGapOrHazardBelow(afterBelow)) islandPenalty = 0.8f;

                    p.costMalus += extra + islandPenalty;

                    if (count >= options.length) return count;
                    options[count++] = p;
                    added = true;
                    break;
                }

                if (added) continue;

                // ✅ 3) WATER landing scan: do ONE downward scan (not dy loop × corridor loop)
                // Only do this if you actually want deep water drops.
                int bestWaterY = findFirstWaterLandingY(lx, current.y, lz, current.y - MAX_JUMP_DOWN_WATER);
                if (bestWaterY != Integer.MIN_VALUE) {

                    PathPoint p = this.openPoint(lx, bestWaterY, lz);
                    if (p != null && !p.visited && p.distanceTo(target) < maxDistance) {

                        float extra = -0.30f; // your water preference
                        float islandPenalty = 0.0f;
                        BlockPos afterBelow = new BlockPos(lx + dx, bestWaterY - 1, lz + dz);
                        if (isGapOrHazardBelow(afterBelow)) islandPenalty = 0.8f;

                        p.costMalus += extra + islandPenalty;

                        if (count >= options.length) return count;
                        options[count++] = p;
                    }
                }
            }
        }

        return count;
    }

    private int addDropDownNeighbors(PathPoint[] options, int count,
                                     PathPoint current, PathPoint target, float maxDistance) {

        int x = current.x;
        int y = current.y;
        int z = current.z;

        // Only consider dropping if the block directly below is open-ish (a hole starts)
        if (!isAirLike(new BlockPos(x, y - 1, z))) return count;

        // Scan down looking for a valid landing
        for (int dy = 2; dy <= MAX_DROP_WATER; dy++) {
            int ly = y - dy;

            BlockPos feet = new BlockPos(x, ly, z);
            BlockPos head = feet.up();

            // Must be pass-through where we land
            if (!isAirLike(feet) || !isAirLike(head)) continue;

            boolean landingWater = isWaterBlock(x, ly, z);
            if (landingWater) {
                if (!isWaterLandingOk(x, ly, z)) continue;
            } else {
                // solid landing: must be supported and not too deep
                if (dy > MAX_DROP_SOLID) continue;

                BlockPos below = feet.down();
                if (this.blockaccess.getBlockState(below).getCollisionBoundingBox(this.blockaccess, below) == null)
                    continue;
            }

            // IMPORTANT: the fall column between start and landing must be clear for feet+head
            boolean clear = true;
            for (int fy = y - 1; fy > ly; fy--) {
                BlockPos midFeet = new BlockPos(x, fy, z);
                if (!isAirLike(midFeet) || !isAirLike(midFeet.up())) {
                    clear = false;
                    break;
                }
            }
            if (!clear) break; // blocked shaft: stop scanning lower

            PathPoint p = this.openPoint(x, ly, z);
            if (p == null || p.visited) return count;

            if (p.distanceTo(target) >= maxDistance) return count;

            // small preference to take the drop when it helps reach the target
            p.costMalus += landingWater ? -0.15f : 0.10f;

            if (count < options.length) {
                options[count++] = p;
            }
            return count; // take first valid landing
        }

        return count;
    }

    private int addGapJumpNeighborsFollowerCheap(PathPoint[] options, int count,
                                                 PathPoint current, PathPoint target, float maxDistance) {
        // ONLY allow 2-block landings (small gap jump)
        final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] d : DIRS) {
            int dx = d[0], dz = d[1];
            int dist = 2;

            int lx = current.x + dx * dist;
            int lz = current.z + dz * dist;
            int ly = current.y; // no vertical search for followers

            if (!canGapJump(current.x, current.y, current.z, lx, ly, lz, dx, dz, dist)) continue;

            PathPoint p = this.openPoint(lx, ly, lz);
            if (p == null || p.visited) continue;
            if (p.distanceTo(target) >= maxDistance) continue;

            if (count < options.length) options[count++] = p;
        }
        return count;
    }

    private int addDropDownNeighborsFollowerCheap(PathPoint[] options, int count,
                                                  PathPoint current, PathPoint target, float maxDistance) {
        // Followers: tiny drop scan only (prevents huge shaft exploration)
        int x = current.x, y = current.y, z = current.z;

        // only if immediate below is open-ish
        if (!isAirLike(new BlockPos(x, y - 1, z))) return count;

        int maxDrop = 6; // <= this is the KEY (keep small)
        for (int dy = 2; dy <= maxDrop; dy++) {
            int ly = y - dy;
            BlockPos feet = new BlockPos(x, ly, z);

            if (!isAirLike(feet) || !isAirLike(feet.up())) continue;

            BlockPos below = feet.down();
            if (this.blockaccess.getBlockState(below).getCollisionBoundingBox(this.blockaccess, below) == null)
                continue;

            PathPoint p = this.openPoint(x, ly, z);
            if (p == null || p.visited) return count;
            if (p.distanceTo(target) >= maxDistance) return count;

            if (count < options.length) options[count++] = p;
            return count;
        }

        return count;
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

    // Corridor check (mid-air cells) for feet+head at y=current.y only.
// This is what your canGapJump() was doing repeatedly.
    private boolean corridorAllowsGapJump(int sx, int sy, int sz, int dx, int dz, int dist) {
        boolean sawGapOrHazard = false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int i = 1; i < dist; i++) {
            int x = sx + dx * i;
            int z = sz + dz * i;

            // feet must be air-like
            pos.setPos(x, sy, z);
            if (!isAirLike(pos)) return false;

            // head must be air-like
            pos.setPos(x, sy + 1, z);
            if (!isAirLike(pos)) return false;

            // below must contain a hazard/gap somewhere along the corridor
            pos.setPos(x, sy - 1, z);
            if (isGapOrHazardBelow(pos)) {
                sawGapOrHazard = true;
            }
        }

        return sawGapOrHazard;
    }

    private boolean isTwoHighWalkable(int x, int y, int z) {
        PathNodeType feet = this.getPathNodeType(this.blockaccess, x, y, z);
        PathNodeType head = this.getPathNodeType(this.blockaccess, x, y + 1, z);

        if (!(feet == PathNodeType.WALKABLE)) return false;
// head must be open-ish
        return (head == PathNodeType.OPEN || head == PathNodeType.WALKABLE);
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

    // Scan down for the first water block that satisfies isWaterLandingOk.
// Returns Integer.MIN_VALUE if none found.
    private int findFirstWaterLandingY(int x, int startY, int z, int minY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = startY; y >= minY; y--) {
            // must be water at feet
            if (!isWaterBlock(x, y, z)) continue;

            if (isWaterLandingOk(x, y, z)) {
                return y;
            }
        }

        return Integer.MIN_VALUE;
    }

    private boolean isAirLike(BlockPos p) {
        IBlockState s = this.blockaccess.getBlockState(p);

        // NEW: liquids are NEVER air-like (water has null collision box, so we must special-case it)
        if (s.getMaterial().isLiquid()) return false;

        // passable if replaceable OR no collision box
        return s.getMaterial().isReplaceable() || s.getCollisionBoundingBox(this.blockaccess, p) == null;
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
        return sh.getMaterial().isReplaceable() || sh.getCollisionBoundingBox(this.blockaccess, head) == null;

        // Optional: require at least 1-deep water (feet water is enough usually)
        // Optional stricter: require 2-deep:
        // if (this.blockaccess.getBlockState(feet.down()).getMaterial() != Material.WATER) return false;
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

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos pos2 = new BlockPos.MutableBlockPos();

        for (int i = 1; i < dist; i++) {
            int x = sx + dx * i;
            int y = sy;
            int z = sz + dz * i;

            pos.setPos(x, y, z);
            if (!isAirLike(pos)) return false;

            pos2.setPos(x, y + 1, z);
            if (!isAirLike(pos2)) return false;

            pos2.setPos(x, y - 1, z);
            if (isGapOrHazardBelow(pos2)) {
                sawGapOrHazard = true;
            }
        }

        return sawGapOrHazard;
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

    // Feet+head must be OPEN-ish so we can pass through while jumping
    private boolean isJumpAirColumn(int x, int y, int z) {
        PathNodeType feet = this.getPathNodeType(this.blockaccess, x, y, z);
        PathNodeType head = this.getPathNodeType(this.blockaccess, x, y + 1, z);

        // OPEN means air-like; WALKABLE means it thinks you can stand there (not what we want mid-air)
        boolean feetOk = (feet == PathNodeType.OPEN);
        boolean headOk = (head == PathNodeType.OPEN);

        return feetOk && headOk;
    }

    private boolean isLiquidNode(PathNodeType t) {
        return t == PathNodeType.WATER;
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
        return t != PathNodeType.DANGER_OTHER;

        // OPEN / WALKABLE / WATER etc. are OK here
    }

    private boolean isDropOff(int x, int y, int z) {
        PathNodeType below = this.getPathNodeType(this.blockaccess, x, y - 1, z);
        return below == PathNodeType.DANGER_OTHER;
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

    public void setFollowerMode(boolean followerMode) {
        this.followerMode = followerMode;
    }

}