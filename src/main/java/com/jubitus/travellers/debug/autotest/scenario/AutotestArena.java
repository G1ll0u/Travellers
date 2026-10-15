package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.village.MillFootprints;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/** The ground a scenario runs on: a loaded area around a centre, searched for gentle open runs. */
public final class AutotestArena {

    /** A run may climb or drop this much between two samples four blocks apart. */
    private static final int MAX_STEP_Y = 2;
    private static final int SAMPLE_SPACING = 4;
    private static final int CANDIDATES = 24;

    public final WorldServer world;
    public final int centerX;
    public final int centerZ;

    public AutotestArena(WorldServer world, int centerX, int centerZ) {
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    /** Dry ground at this column, or null (unloaded, water, inside a village building). */
    @Nullable
    public BlockPos groundAt(int x, int z) {
        int y = ColumnGround.terrain(world, x, z, false);
        if (y == ColumnGround.NONE) return null;
        if (MillFootprints.isForbiddenLanding(world, x + 0.5D, y, z + 0.5D)) return null;
        return new BlockPos(x, y, z);
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

    /** Dry ground {@code distance} blocks from {@code target}; each attempt starts from another direction. */
    @Nullable
    public BlockPos groundAround(BlockPos target, int distance, int attempt) {
        for (int i = 0; i < DIRECTIONS.length; i++) {
            int[] d = DIRECTIONS[(i + attempt) % DIRECTIONS.length];
            double len = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
            BlockPos p = groundAt((int) (target.getX() + d[0] / len * distance), (int) (target.getZ() + d[1] / len * distance));
            if (p != null) return p;
        }
        return null;
    }

    /**
     * Two points {@code length} blocks apart joined by dry, gently sloping ground; each attempt turns the
     * direction, so a retry never repeats the same line. Null when the arena has none.
     */
    @Nullable
    public BlockPos[] findRun(int length, int attempt) {
        for (int i = 0; i < CANDIDATES; i++) {
            int sx = centerX + (i % 6) * 8 - 20;
            int sz = centerZ + (i / 6) * 8 - 16;
            double angle = Math.toRadians(attempt * 90 + i * 37);
            double dx = Math.cos(angle), dz = Math.sin(angle);
            BlockPos prev = groundAt(sx, sz);
            if (prev == null) continue;
            BlockPos start = prev;
            boolean ok = true;
            for (int d = SAMPLE_SPACING; d <= length && ok; d += SAMPLE_SPACING) {
                BlockPos p = groundAt((int) Math.floor(sx + dx * d), (int) Math.floor(sz + dz * d));
                ok = p != null && Math.abs(p.getY() - prev.getY()) <= MAX_STEP_Y;
                if (ok) prev = p;
            }
            if (ok) return new BlockPos[]{start, prev};
        }
        return null;
    }

    private static final int WATER_STEP = 2;
    private static final int MIN_WATER_BLOCKS = 8;
    private static final int MAX_WATER_BLOCKS = 40;
    private static final int SEARCH_RADIUS = 56;
    private static final int SEARCH_SPACING = 8;

    /** A water column: nothing to stand on, but a swimmer's surface exists. */
    private boolean isWaterColumn(int x, int z) {
        return ColumnGround.terrain(world, x, z, false) == ColumnGround.NONE
                && ColumnGround.terrain(world, x, z, true) != ColumnGround.NONE;
    }

    /**
     * Dry ground on both sides of a stretch of open water (8 to 40 blocks across), as {start, landing}. Each attempt
     * skips the crossings the earlier ones used. Null when the arena has no such crossing.
     */
    @Nullable
    public BlockPos[] findWaterCrossing(int attempt) {
        int skip = attempt;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += SEARCH_SPACING) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz += SEARCH_SPACING) {
                for (int[] d : DIRECTIONS) {
                    BlockPos[] crossing = crossingFrom(centerX + dx, centerZ + dz, d[0], d[1]);
                    if (crossing != null && skip-- <= 0) return crossing;
                }
            }
        }
        return null;
    }

    @Nullable
    private BlockPos[] crossingFrom(int x, int z, int dx, int dz) {
        BlockPos start = groundAt(x, z);
        if (start == null) return null;
        BlockPos lastDry = start;
        int waterBlocks = 0;
        for (int step = 1; step <= (MAX_WATER_BLOCKS + MIN_WATER_BLOCKS) / WATER_STEP + 8; step++) {
            int sx = x + dx * step * WATER_STEP, sz = z + dz * step * WATER_STEP;
            BlockPos dry = groundAt(sx, sz);
            if (dry != null) {
                if (waterBlocks == 0) {
                    lastDry = dry;
                    continue;
                }
                return waterBlocks >= MIN_WATER_BLOCKS && waterBlocks <= MAX_WATER_BLOCKS ? new BlockPos[]{lastDry, dry} : null;
            }
            if (!isWaterColumn(sx, sz)) return null;
            waterBlocks += WATER_STEP;
        }
        return null;
    }
}
