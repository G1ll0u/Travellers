package com.jubitus.travellers.utils.world.pathing;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Detects jumpable gaps ahead of an entity. Results are cached in a fixed-size
 * open-addressing table keyed by (blockPos, direction, tick bucket) so that
 * many entities moving through the same gap share a single scan result.
 */
public final class GapJumpEvaluator {

    private GapJumpEvaluator() {}

    // Entity can jump over gaps up to this many blocks wide at the same or lower Y.
    public static final int MAX_GAP = 3;

    // How long a cache entry stays valid (in world ticks).
    private static final int CACHE_TTL = 16;

    // Must be a power of two.
    private static final int CACHE_SIZE = 512;
    private static final int CACHE_MASK = CACHE_SIZE - 1;

    private static final long[]        CACHE_KEYS    = new long[CACHE_SIZE];
    private static final GapJumpResult[] CACHE_VALS  = new GapJumpResult[CACHE_SIZE];
    private static final long[]        CACHE_EXPIRES = new long[CACHE_SIZE];

    /**
     * Returns whether a jumpable gap exists immediately ahead of the entity.
     *
     * @param world     block access (world)
     * @param worldTime world.getTotalWorldTime()
     * @param cx        entity feet block X  (MathHelper.floor(posX))
     * @param cy        entity feet block Y  (MathHelper.floor(BB.minY))
     * @param cz        entity feet block Z  (MathHelper.floor(posZ))
     * @param dirX      normalized movement direction X
     * @param dirZ      normalized movement direction Z
     */
    public static GapJumpResult evaluate(IBlockAccess world, long worldTime,
                                          int cx, int cy, int cz,
                                          double dirX, double dirZ) {
        int dx = quantize(dirX);
        int dz = quantize(dirZ);
        if (dx == 0 && dz == 0) return GapJumpResult.NONE;

        int dir9 = (dx + 1) * 3 + (dz + 1);
        long key  = packKey(cx, cy, cz, dir9);
        int  slot = slot(key);

        if (CACHE_KEYS[slot] == key && worldTime < CACHE_EXPIRES[slot]) {
            return CACHE_VALS[slot];
        }

        GapJumpResult result = scan(world, cx, cy, cz, dx, dz);
        CACHE_KEYS[slot]    = key;
        CACHE_VALS[slot]    = result;
        CACHE_EXPIRES[slot] = worldTime + CACHE_TTL;
        return result;
    }

    // ----- scan -----

    private static GapJumpResult scan(IBlockAccess world, int cx, int cy, int cz, int dx, int dz) {
        // Entity must stand on something solid.
        if (!isSolid(world, cx, cy - 1, cz)) return GapJumpResult.NONE;

        // Immediate step ahead must have no solid floor (otherwise it is passable terrain).
        if (isSolid(world, cx + dx, cy - 1, cz + dz)) return GapJumpResult.NONE;

        // Also reject if entity would just walk into a solid wall (the gap is actually a wall).
        if (isSolid(world, cx + dx, cy, cz + dz) || isSolid(world, cx + dx, cy + 1, cz + dz)) {
            return GapJumpResult.NONE;
        }

        // Classify the first gap column: skip step-downs; only allow wide jumps over real hazards.
        boolean gapDangerous = false;
        boolean gapFoundFloor = false;
        boolean gapStepDown = false;
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
        for (int scanY = cy - 1; scanY >= cy - 5; scanY--) {
            scanPos.setPos(cx + dx, scanY, cz + dz);
            Material m = world.getBlockState(scanPos).getMaterial();
            if (m == Material.LAVA || m == Material.WATER || m == Material.FIRE) {
                gapDangerous = true;
                break;
            }
            if (m.isSolid()) {
                gapFoundFloor = true;
                gapStepDown = (scanY == cy - 2); // floor 1 block below = simple step-down
                break;
            }
        }
        if (!gapFoundFloor && !gapDangerous) gapDangerous = true; // void counts as dangerous
        if (gapStepDown) return GapJumpResult.NONE;

        // Try landing spots from closest to furthest (prefer shortest jump).
        for (int gapW = 1; gapW <= MAX_GAP; gapW++) {
            // Non-dangerous gap (narrow ditch): only allow 1-wide jump to avoid unnecessary detour.
            if (!gapDangerous && gapW > 1) continue;
            int lx = cx + dx * (gapW + 1);
            int lz = cz + dz * (gapW + 1);

            // Find a solid floor block at the landing column (scan a few blocks up and down).
            int floorY = findFloorBlock(world, lx, cy + 1, lz, cy - 5);
            if (floorY == Integer.MIN_VALUE) continue;

            // Height delta relative to entity's current floor block.
            int delta = floorY - (cy - 1);
            if (delta > 1)  continue; // landing is more than 1 block higher — can't jump there
            if (delta < -5) continue; // landing is too deep — dangerous fall

            // The landing column must have headroom (feet + 1 block of clearance for 2-tall entity).
            int feetY = floorY + 1;
            if (isSolid(world, lx, feetY, lz) || isSolid(world, lx, feetY + 1, lz)) continue;

            // All gap columns (steps 1..gapW) must truly be open at cy-1 (no floor at entity's
            // current height). This prevents treating a staircase as a gap.
            boolean gapClear = true;
            for (int s = 2; s <= gapW; s++) {
                if (isSolid(world, cx + dx * s, cy - 1, cz + dz * s)) {
                    gapClear = false;
                    break;
                }
            }
            if (!gapClear) continue;

            // Reject hazardous landing.
            if (isHazard(world, lx, feetY, lz) || isHazard(world, lx, floorY, lz)) continue;

            double landSurfY = surfaceTop(world, lx, floorY, lz);
            return GapJumpResult.jumpable(lx + 0.5, landSurfY, lz + 0.5, gapW);
        }

        // Gap confirmed but no reachable landing.
        return GapJumpResult.UNJUMPABLE;
    }

    // ----- block helpers -----

    private static boolean isSolid(IBlockAccess world, int x, int y, int z) {
        return world.getBlockState(new BlockPos(x, y, z)).getMaterial().isSolid();
    }

    /** Scan downward from startY to minY for the first solid block. Returns its Y, or MIN_VALUE. */
    private static int findFloorBlock(IBlockAccess world, int x, int startY, int z, int minY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = startY; y >= minY; y--) {
            pos.setPos(x, y, z);
            if (world.getBlockState(pos).getMaterial().isSolid()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Top surface Y of the block at (x, y, z) accounting for partial blocks (slabs, etc.). */
    private static double surfaceTop(IBlockAccess world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState st = world.getBlockState(pos);
        AxisAlignedBB bb = st.getBoundingBox(world, pos);
        if (bb == null || bb == Block.NULL_AABB) return y + 1.0;
        return y + bb.maxY;
    }

    private static boolean isHazard(IBlockAccess world, int x, int y, int z) {
        Material m = world.getBlockState(new BlockPos(x, y, z)).getMaterial();
        // Water is a hazard for landings: prevents jumping into rivers/ponds
        return m == Material.LAVA || m == Material.FIRE || m == Material.CACTUS || m == Material.WATER;
    }

    // ----- cache internals -----

    /** Converts a continuous direction component to -1, 0, or +1. */
    private static int quantize(double d) {
        return (int) Math.signum(Math.round(d * 2.0));
    }

    private static long packKey(int cx, int cy, int cz, int dir9) {
        return ((long) (cx & 0x1FFFFF)) << 42
             | ((long) (cz & 0x1FFFFF)) << 21
             | ((long) (cy &     0xFF)) <<  4
             | (dir9 & 0xF);
    }

    private static int slot(long key) {
        int h = (int) (key ^ (key >>> 32));
        h ^= (h >>> 16);
        return h & CACHE_MASK;
    }
}
