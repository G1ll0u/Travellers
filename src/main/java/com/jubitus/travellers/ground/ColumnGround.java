package com.jubitus.travellers.ground;

import com.jubitus.travellers.ground.chunk.LoadedChunks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * The mod's one answer to "where is the ground in this column". A single top-down scan collects the
 * layers a unit can stand on (and, when allowed, the water surface), passing through air, canopy,
 * roofs and floors, and stops at the first natural ground, water or dangerous liquid - it never
 * scans into the caves below. Results are feet Y, or {@link #NONE}. Never loads a chunk.
 */
public final class ColumnGround {

    public static final int NONE = Integer.MIN_VALUE;

    private static final int PICK_TOP = 0;
    private static final int PICK_TERRAIN = 1;
    private static final int PICK_NEAREST = 2;

    /** How far below the column's top the scan may go before giving up. */
    private static final int MAX_SCAN_DEPTH = 160;

    private ColumnGround() {
    }

    /** The highest layer (a roof or a canopy-free floor counts). With {@code allowWater}, a water surface counts too. */
    public static int topLayer(World world, int x, int z, boolean allowWater) {
        return scan(world, x, z, PICK_TOP, 0, allowWater);
    }

    /** The real terrain: the layer on natural ground (or the water surface), under trees and roofs. */
    public static int terrain(World world, int x, int z, boolean allowWater) {
        return scan(world, x, z, PICK_TERRAIN, 0, allowWater);
    }

    /** The layer closest in height to {@code refY}. */
    public static int nearestLayer(World world, int x, int z, int refY, boolean allowWater) {
        return scan(world, x, z, PICK_NEAREST, refY, allowWater);
    }

    /**
     * The standable feet Y closest to {@code refY} within {@code range} blocks, lower first at equal
     * distance. Unlike the layer scan it goes anywhere: inside buildings, caves, under overhangs.
     */
    public static int nearestStandable(World world, int x, int z, int refY, int range) {
        if (!LoadedChunks.isLoaded(world, new BlockPos(x, 0, z))) return NONE;
        int maxFeet = world.getActualHeight() - 2;
        for (int d = 0; d <= range; d++) {
            int down = refY - d;
            if (down >= 1 && Footing.canStandAt(world, new BlockPos(x, down, z))) return down;
            int up = refY + d;
            if (d > 0 && up <= maxFeet && Footing.canStandAt(world, new BlockPos(x, up, z))) return up;
        }
        return NONE;
    }

    /**
     * Blocks between {@code pos} and the highest solid block of its column, leaves and foliage
     * ignored: 0 under open sky or a tree, large in a cave or deep inside a building. 0 when unloaded.
     */
    public static int coverAbove(World world, BlockPos pos) {
        if (!LoadedChunks.isLoaded(world, pos)) return 0;
        return Math.max(0, world.getTopSolidOrLiquidBlock(new BlockPos(pos.getX(), 0, pos.getZ())).getY() - pos.getY());
    }

    private static int scan(World world, int x, int z, int pick, int refY, boolean allowWater) {
        int top = columnTop(world, x, z);
        if (top == NONE) return NONE;
        int bottom = Math.max(1, top - MAX_SCAN_DEPTH);
        int found = NONE;
        int bestDy = Integer.MAX_VALUE;
        for (int y = top; y >= bottom; y--) {
            if (pick == PICK_NEAREST && found != NONE && refY - y > bestDy) break;
            BlockPos p = new BlockPos(x, y, z);
            IBlockState s = world.getBlockState(p);

            int feet = NONE;
            boolean stop = false;
            if (BlockRules.isWater(s)) {
                // The top water block is where a swimmer's feet are; never look for a seabed.
                if (allowWater && Footing.canSwimAt(world, p) && !BlockRules.isWater(world.getBlockState(p.up()))) {
                    feet = y;
                }
                stop = true;
            } else if (s.getMaterial().isLiquid()) {
                stop = true;
            } else if (isOpen(world, p, s)) {
                continue;
            } else {
                if (Footing.isFloor(world, p, s) && Footing.hasRoom(world, p.up()) && Footing.hasRoom(world, p.up(2))) {
                    feet = y + 1;
                }
                stop = BlockRules.isNaturalGround(s);
            }

            if (feet != NONE) {
                if (pick == PICK_TOP) return feet;
                if (pick == PICK_TERRAIN) {
                    found = feet; // top-down, so the last layer seen is the lowest
                } else {
                    int dy = Math.abs(feet - refY);
                    if (dy < bestDy) {
                        bestDy = dy;
                        found = feet;
                    }
                }
            }
            if (stop) break;
        }
        return found;
    }

    // Air, grass, flowers, torches, a thin snow layer: nothing to stand on, nothing in the way.
    private static boolean isOpen(World world, BlockPos p, IBlockState s) {
        return s.getMaterial().isReplaceable() || s.getCollisionBoundingBox(world, p) == null;
    }

    private static int columnTop(World world, int x, int z) {
        Chunk chunk;
        if (world instanceof WorldServer) {
            chunk = LoadedChunks.peek((WorldServer) world, x >> 4, z >> 4);
        } else {
            chunk = world.isBlockLoaded(new BlockPos(x, 0, z), false) ? world.getChunk(x >> 4, z >> 4) : null;
        }
        if (chunk == null) return NONE;
        return Math.min(world.getActualHeight() - 3, chunk.getTopFilledSegment() + 15);
    }
}
