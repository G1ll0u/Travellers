package com.jubitus.travellers.ground;

import com.jubitus.travellers.ground.chunk.LoadedChunks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Where to sit down outdoors, and which way to face: flat full ground, not in a hole, legroom in
 * front, and the longest open view (better over falling ground or water). Never loads a chunk; a
 * view simply ends where the loaded world ends.
 */
public final class SeatFinder {

    /** The eight headings a seat may face, as XZ steps. */
    private static final int[][] DIRS = {{0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}};
    private static final int VIEW_LENGTH = 16;
    /** A heading whose view stops closer than this is never faced. */
    private static final int MIN_VIEW = 6;
    /** The line of sight may climb this much per block (looking slightly up is still a view). */
    private static final double RISE_PER_BLOCK = 0.1D;
    /** Ground this far below the seat counts as a full plunging view. */
    private static final int DROP_FULL = 8;
    private static final int WATER_FULL = 4;
    private static final int WEIGHT_VIEW = 50;
    private static final int WEIGHT_DROP = 30;
    private static final int WEIGHT_WATER = 20;
    /** Below this a seat is not worth sitting on. */
    private static final int MIN_SCORE = 40;
    private static final double MIN_SPACING_SQ = 2.0D * 2.0D;

    private SeatFinder() {
    }

    public static final class Seat {
        public final BlockPos feet;
        /** Index into the eight headings. */
        public final int dir;
        public final int score;

        public Seat(BlockPos feet, int dir, int score) {
            this.feet = feet;
            this.dir = Math.floorMod(dir, DIRS.length);
            this.score = score;
        }

        /** The body heading while seated. */
        public float yaw() {
            return (float) (MathHelper.atan2(DIRS[dir][1], DIRS[dir][0]) * (180.0D / Math.PI)) - 90.0F;
        }

        /** A point out in the view, for the eyes to rest on. */
        public BlockPos viewPoint() {
            return feet.add(DIRS[dir][0] * 12, 1, DIRS[dir][1] * 12);
        }
    }

    /** The best seats among these feet positions, best first, kept apart from each other, at most {@code max}. */
    public static List<Seat> best(WorldServer world, Iterable<BlockPos> candidates, int max) {
        List<Seat> all = new ArrayList<>();
        for (BlockPos feet : candidates) {
            Seat s = judge(world, feet);
            if (s != null) all.add(s);
        }
        all.sort((a, b) -> Integer.compare(b.score, a.score));
        List<Seat> out = new ArrayList<>();
        for (Seat s : all) {
            if (out.size() >= max) break;
            boolean apart = true;
            for (Seat o : out) {
                if (o.feet.distanceSq(s.feet) < MIN_SPACING_SQ) {
                    apart = false;
                    break;
                }
            }
            if (apart) out.add(s);
        }
        return out;
    }

    /** Cheap re-check of a stored seat before sitting on it: ground and legroom still there, nothing built in front. */
    public static boolean stillGood(WorldServer world, Seat seat) {
        return isSeat(world, seat.feet) && facingScore(world, seat.feet, DIRS[seat.dir]) >= 0;
    }

    @Nullable
    private static Seat judge(WorldServer world, BlockPos feet) {
        if (!isSeat(world, feet)) return null;
        int bestDir = -1, bestScore = -1;
        for (int d = 0; d < DIRS.length; d++) {
            int score = facingScore(world, feet, DIRS[d]);
            if (score > bestScore) {
                bestScore = score;
                bestDir = d;
            }
        }
        return bestScore >= MIN_SCORE ? new Seat(feet, bestDir, bestScore) : null;
    }

    /** Standable on a full block, nothing dangerous within a step, and not sunk in a hole or nook. */
    private static boolean isSeat(WorldServer world, BlockPos feet) {
        if (!Footing.canStandAt(world, feet) || !world.getBlockState(feet.down()).isFullCube()) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (BlockRules.isHazard(world.getBlockState(feet.add(dx, 0, dz)))
                        || BlockRules.isHazard(world.getBlockState(feet.add(dx, -1, dz)))) return false;
            }
        }
        int walls = 0;
        for (int d = 0; d < DIRS.length; d += 2) {
            if (!Footing.hasRoom(world, feet.add(DIRS[d][0], 0, DIRS[d][1]))) walls++;
        }
        return walls < 2;
    }

    /** Room for the legs: the cell ahead is free at feet and head height (open ground or a drop). */
    private static boolean hasLegroom(WorldServer world, BlockPos feet, int[] d) {
        BlockPos ahead = feet.add(d[0], 0, d[1]);
        return Footing.hasRoom(world, ahead) && Footing.hasRoom(world, ahead.up());
    }

    private static int facingScore(WorldServer world, BlockPos feet, int[] d) {
        if (!hasLegroom(world, feet, d)) return -1;
        int eyeY = feet.getY() + 1;
        int free = 0, drop = 0, water = 0;
        for (int k = 2; k <= VIEW_LENGTH; k++) {
            int x = feet.getX() + d[0] * k, z = feet.getZ() + d[1] * k;
            int top = surfaceTop(world, x, z);
            if (top == Integer.MIN_VALUE || top - eyeY > k * RISE_PER_BLOCK) break;
            free = k;
            drop += Math.max(0, Math.min(DROP_FULL, feet.getY() - top));
            if (BlockRules.isWater(world.getBlockState(new BlockPos(x, top - 1, z)))) water++;
        }
        if (free < MIN_VIEW) return -1;
        int steps = free - 1;
        double view = (double) free / VIEW_LENGTH;
        double dropShare = (double) drop / (steps * DROP_FULL);
        double waterShare = Math.min(1.0D, (double) water / WATER_FULL);
        return (int) Math.round(WEIGHT_VIEW * view + WEIGHT_DROP * dropShare + WEIGHT_WATER * waterShare);
    }

    // The first free Y above the highest light-blocking block (leaves count, grass and flowers don't); MIN_VALUE when unloaded.
    private static int surfaceTop(WorldServer world, int x, int z) {
        Chunk chunk = LoadedChunks.peek(world, x >> 4, z >> 4);
        return chunk == null ? Integer.MIN_VALUE : chunk.getHeightValue(x & 15, z & 15);
    }
}
