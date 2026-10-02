package com.jubitus.travellers.pathing;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import javax.annotation.Nullable;

/**
 * Detects jumpable gaps ahead of an entity. Results are cached in a fixed-size
 * open-addressing table keyed by (blockPos, direction, tick bucket) so that
 * many entities moving through the same gap share a single scan result.
 *
 * The landing column is found geometrically: starting from the narrowest jump and widening,
 * a candidate landing is only accepted once solid ground genuinely resumes near the entity's
 * own standing height (see {@link TravellersConfig.GapJump#landingHeightTolerance}). A floor
 * found partway across the gap (e.g. the flat bottom of a trench) is never mistaken for the far
 * rim, no matter how shallow the gap is - it simply isn't close enough to the entity's own
 * height yet, so the scan keeps widening past it.
 */
public final class GapJumpEvaluator {

    private GapJumpEvaluator() {}

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
        // Cardinal-only on purpose: a genuinely diagonal jump's real (ballistic, non-grid-aligned)
        // flight path can clip a block corner that a grid-based obstruction scan along a single
        // diagonal line never samples, since neither of the two flanking columns is ever checked -
        // this is what caused a jump to "look clear" here but hit an obstacle mid-flight and drop
        // the entity into the gap it was trying to clear. Snapping to the dominant axis (never
        // both dx and dz nonzero) keeps every jump's approach and landing on a single straight
        // grid line, where the existing obstruction scan is exact.
        int dx, dz;
        if (Math.abs(dirX) >= Math.abs(dirZ)) {
            dx = dirX > 0.0D ? 1 : (dirX < 0.0D ? -1 : 0);
            dz = 0;
        } else {
            dz = dirZ > 0.0D ? 1 : -1;
            dx = 0;
        }
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

        // Immediate step ahead must have no solid floor (otherwise it is passable terrain, not a gap).
        if (isSolid(world, cx + dx, cy - 1, cz + dz)) return GapJumpResult.NONE;

        // Reject if the entity would just walk into a solid wall (the "gap" is actually a wall).
        if (isSolid(world, cx + dx, cy, cz + dz) || isSolid(world, cx + dx, cy + 1, cz + dz)) {
            return GapJumpResult.NONE;
        }

        // Classify the very first gap column: a plain 1-block step-down (solid floor exactly one
        // block below current level, nothing hazardous above it) is walked normally, never jumped.
        boolean firstColHazardMaterial = false;
        int firstColFloorY = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
        for (int scanY = cy - 1; scanY >= cy - 6; scanY--) {
            scanPos.setPos(cx + dx, scanY, cz + dz);
            Material m = world.getBlockState(scanPos).getMaterial();
            if (m == Material.LAVA || m == Material.WATER || m == Material.FIRE) {
                firstColHazardMaterial = true;
                break;
            }
            if (m.isSolid()) {
                firstColFloorY = scanY;
                break;
            }
        }
        if (firstColFloorY == cy - 2) return GapJumpResult.NONE; // simple step-down, just walk it

        // A genuine hazard: immediately dangerous material, or no floor found in the deep scan (void).
        boolean firstColIsHazard = firstColHazardMaterial || firstColFloorY == Integer.MIN_VALUE;

        int maxGap = TravellersConfig.GAP_JUMP.maxGapWidth;
        int tolerance = TravellersConfig.GAP_JUMP.landingHeightTolerance;

        // Pass 1: widen the jump until solid ground resumes close to the entity's own standing
        // height. This alone correctly clears a trench/ditch/pond of any depth as long as it is
        // narrower than maxGap - the far rim, not the gap's own floor, is what gets picked.
        GapJumpResult primary = findLanding(world, cx, cy, cz, dx, dz, maxGap, tolerance, tolerance);
        if (primary != null) return primary;

        // Pass 2: only for a confirmed hazard with no near-height landing available - accept a
        // farther-below landing (a real chasm/lake crossing). Pass 1 already claimed every
        // near-height column, so this can never land back inside the same gap it's crossing.
        if (firstColIsHazard) {
            int maxFall = TravellersConfig.GAP_JUMP.maxHazardFallTolerance;
            GapJumpResult fallback = findLanding(world, cx, cy, cz, dx, dz, maxGap, 1, maxFall);
            if (fallback != null) return fallback;
        }

        // Gap confirmed but no reachable/safe landing within range.
        return GapJumpResult.UNJUMPABLE;
    }

    /**
     * Scans landing columns from gapW=1..maxGap (closest first) for the first one whose height
     * delta relative to the entity's own floor falls within [-maxFallDelta, maxRiseDelta].
     * Returns null if none qualifies (caller may retry with a looser band, or give up).
     */
    @Nullable
    private static GapJumpResult findLanding(IBlockAccess world, int cx, int cy, int cz, int dx, int dz,
                                              int maxGap, int maxRiseDelta, int maxFallDelta) {
        for (int gapW = 1; gapW <= maxGap; gapW++) {
            // The whole span the entity flies over (columns 1..gapW) needs clear headroom, or it
            // would jump straight into a wall/ceiling placed partway across the gap. Once one
            // column is obstructed, every wider jump crosses it too - no point trying wider.
            boolean obstructed = false;
            for (int s = 1; s <= gapW; s++) {
                if (isSolid(world, cx + dx * s, cy, cz + dz * s) || isSolid(world, cx + dx * s, cy + 1, cz + dz * s)) {
                    obstructed = true;
                    break;
                }
            }
            if (obstructed) break;

            int lx = cx + dx * (gapW + 1);
            int lz = cz + dz * (gapW + 1);

            int floorY = findFloorBlock(world, lx, cy + 1, lz, cy - 6);
            if (floorY == Integer.MIN_VALUE) continue; // no floor within reach at this width - try wider

            int delta = floorY - (cy - 1);
            if (delta > maxRiseDelta) continue;
            if (delta < -maxFallDelta) continue;

            // The landing column must have headroom (feet + 1 block of clearance for a 2-tall entity).
            int feetY = floorY + 1;
            if (isSolid(world, lx, feetY, lz) || isSolid(world, lx, feetY + 1, lz)) continue;

            // Reject hazardous landing.
            if (isHazard(world, lx, feetY, lz) || isHazard(world, lx, floorY, lz)) continue;

            double landSurfY = surfaceTop(world, lx, floorY, lz);
            return GapJumpResult.jumpable(lx + 0.5, landSurfY, lz + 0.5, gapW, lx, feetY, lz);
        }
        return null;
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
