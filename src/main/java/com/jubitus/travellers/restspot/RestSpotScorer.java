package com.jubitus.travellers.restspot;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.SeatFinder;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.pathing.terrain.TerrainProfile;
import com.jubitus.travellers.pathing.terrain.TerrainProfileGrid;
import com.jubitus.travellers.pathing.terrain.TerrainWorldState;
import com.jubitus.travellers.village.MillFootprints;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.BiomeDictionary;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Judges one candidate chunk: is there a roomy walkable patch, and how pretty is it. Reads only the
 * 3x3 loaded chunks around the candidate plus the terrain grid; never loads a chunk.
 */
final class RestSpotScorer {

    /** How far the walkable patch may spread from where it starts. */
    private static final int ROOM_RADIUS = RestSpotRecord.REACH;
    /** Water and trees count this far beyond the patch. */
    private static final int SCENERY_REACH = 6;
    /** Where the patch may start, around the chunk middle; with the radii above it stays inside the 3x3 chunks. */
    private static final int[] START_OFFSETS = {0, -4, 4};
    private static final int MAX_STEP = 1;
    private static final int WATER_COLUMNS_FULL = 24;
    private static final int FLOWERS_FULL = 6;
    private static final double TREES_FOR_EDGE = 0.15;
    private static final int PROMINENCE_FULL = 16;
    /** Terrain grid ring compared with the spot's own height, in chunks. */
    private static final int RING_MIN = 2;
    private static final int RING_MAX = 4;
    private static final int MAX_SEATS = 8;

    private static final int SPAN = ROOM_RADIUS + SCENERY_REACH;
    private static final int SIDE = 2 * SPAN + 1;
    private static final int NOT_WALKABLE = Integer.MIN_VALUE;

    private static String[] biomeSource;
    private static Map<ResourceLocation, Integer> biomeScores = new HashMap<>();

    private RestSpotScorer() {
    }

    /** True once the 3x3 chunks and the terrain grid around the candidate can be read; asks for missing grid regions. */
    static boolean isReady(WorldServer world, int cx, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (LoadedChunks.peek(world, cx + dx, cz + dz) == null) return false;
            }
        }
        TerrainProfileGrid grid = grid(world);
        if (grid == null) return true;
        boolean ready = true;
        for (int dz = -RING_MAX; dz <= RING_MAX; dz += RING_MAX) {
            for (int dx = -RING_MAX; dx <= RING_MAX; dx += RING_MAX) {
                if (!grid.isRegionResidentForCell(cx + dx, cz + dz)) {
                    grid.ensureResident(cx + dx, cz + dz);
                    ready = false;
                }
            }
        }
        return ready;
    }

    /** A judged chunk: the scored spot when a patch was found, and why it is refused (null when kept). */
    static final class Verdict {
        @Nullable final RestSpotRecord spot;
        @Nullable final String refusal;

        private Verdict(@Nullable RestSpotRecord spot, @Nullable String refusal) {
            this.spot = spot;
            this.refusal = refusal;
        }
    }

    /** Judges the chunk. Call only once {@link #isReady}. */
    static Verdict evaluate(WorldServer world, int cx, int cz) {
        TravellersConfig.RestSpots cfg = TravellersConfig.REST_SPOTS;
        BlockPos start = findStart(world, (cx << 4) + 8, (cz << 4) + 8);
        if (start == null) return new Verdict(null, "no natural ground in the chunk middle");
        if (MillSettlements.isNear(world, start, cfg.minVillageDistance)) return new Verdict(null, "too close to a village");
        if (MillFootprints.overlapsReserved(world, start.add(-ROOM_RADIUS, -8, -ROOM_RADIUS), start.add(ROOM_RADIUS, 8, ROOM_RADIUS))) {
            return new Verdict(null, "overlaps a Millenaire building");
        }

        // feet[i] is the feet Y of a patch column, NOT_WALKABLE elsewhere; the grid is centred on start.
        int[] feet = new int[SIDE * SIDE];
        Arrays.fill(feet, NOT_WALKABLE);
        int cells = floodPatch(world, start, feet);
        if (cells < cfg.minWalkableCells) return new Verdict(null, "too little room (" + cells + " blocks)");

        BlockPos center = patchCenter(start, feet);
        boolean[] near = surroundings(feet);
        int water = (int) Math.round(cfg.weightWater * waterShare(world, start, near));
        int height = (int) Math.round(cfg.weightHeight * heightShare(world, center));
        int openSky = (int) Math.round(cfg.weightOpenSky * openSkyShare(world, start, feet, near, cells));
        int flowers = (int) Math.round(cfg.weightFlowers * flowerShare(world, start, feet));
        int biomeScore = biomeScore(world.getBiome(center));
        RestSpotRecord spot = new RestSpotRecord(UUID.randomUUID(), center, cells, water, height, openSky, flowers, biomeScore);
        if (spot.score() < cfg.minScore) return new Verdict(spot, "not pretty enough");
        spot.seats = seats(world, start, feet);
        return new Verdict(spot, null);
    }

    /** The seats of a spot found before seats existed, from its patch walked again. Call once its 3x3 chunks are loaded. */
    static List<SeatFinder.Seat> seatsAround(WorldServer world, BlockPos center) {
        int[] feet = new int[SIDE * SIDE];
        Arrays.fill(feet, NOT_WALKABLE);
        floodPatch(world, center, feet);
        return seats(world, center, feet);
    }

    // Every other patch column (a checkerboard) is plenty: seats end up two blocks apart anyway.
    private static List<SeatFinder.Seat> seats(WorldServer world, BlockPos start, int[] feet) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
            for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
                int y = feet[index(dx, dz)];
                if (y != NOT_WALKABLE && ((dx + dz) & 1) == 0) candidates.add(new BlockPos(start.getX() + dx, y, start.getZ() + dz));
            }
        }
        return Collections.unmodifiableList(SeatFinder.best(world, candidates, MAX_SEATS));
    }

    @Nullable
    private static BlockPos findStart(WorldServer world, int x, int z) {
        for (int ox : START_OFFSETS) {
            for (int oz : START_OFFSETS) {
                int y = ColumnGround.naturalTop(world, x + ox, z + oz);
                if (y != ColumnGround.NONE) return new BlockPos(x + ox, y, z + oz);
            }
        }
        return null;
    }

    private static int index(int dx, int dz) {
        return (dz + SPAN) * SIDE + (dx + SPAN);
    }

    private static int floodPatch(WorldServer world, BlockPos start, int[] feet) {
        int cells = 0;
        ArrayDeque<int[]> open = new ArrayDeque<>();
        feet[index(0, 0)] = start.getY();
        open.add(new int[]{0, 0});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] c = open.poll();
            cells++;
            int y = feet[index(c[0], c[1])];
            for (int[] d : dirs) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (nx * nx + nz * nz > ROOM_RADIUS * ROOM_RADIUS || feet[index(nx, nz)] != NOT_WALKABLE) continue;
                int ny = ColumnGround.naturalTop(world, start.getX() + nx, start.getZ() + nz);
                if (ny == ColumnGround.NONE || Math.abs(ny - y) > MAX_STEP) continue;
                feet[index(nx, nz)] = ny;
                open.add(new int[]{nx, nz});
            }
        }
        return cells;
    }

    // The patch column closest to the patch's centroid.
    private static BlockPos patchCenter(BlockPos start, int[] feet) {
        long sx = 0, sz = 0, n = 0;
        for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
            for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
                if (feet[index(dx, dz)] == NOT_WALKABLE) continue;
                sx += dx;
                sz += dz;
                n++;
            }
        }
        double mx = (double) sx / n, mz = (double) sz / n;
        int bx = 0, bz = 0;
        double best = Double.MAX_VALUE;
        for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
            for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
                if (feet[index(dx, dz)] == NOT_WALKABLE) continue;
                double d = (dx - mx) * (dx - mx) + (dz - mz) * (dz - mz);
                if (d < best) {
                    best = d;
                    bx = dx;
                    bz = dz;
                }
            }
        }
        return new BlockPos(start.getX() + bx, feet[index(bx, bz)], start.getZ() + bz);
    }

    // Columns within SCENERY_REACH of the patch but not on it.
    private static boolean[] surroundings(int[] feet) {
        boolean[] near = new boolean[SIDE * SIDE];
        for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
            for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
                if (feet[index(dx, dz)] == NOT_WALKABLE) continue;
                for (int rz = -SCENERY_REACH; rz <= SCENERY_REACH; rz++) {
                    for (int rx = -SCENERY_REACH; rx <= SCENERY_REACH; rx++) {
                        near[index(dx + rx, dz + rz)] = true;
                    }
                }
            }
        }
        for (int i = 0; i < near.length; i++) {
            if (feet[i] != NOT_WALKABLE) near[i] = false;
        }
        return near;
    }

    private static IBlockState surfaceState(WorldServer world, int x, int z) {
        Chunk chunk = world.getChunk(x >> 4, z >> 4);
        int top = chunk.getHeightValue(x & 15, z & 15);
        return chunk.getBlockState(x, top - 1, z);
    }

    // River or lake water around the patch; the open sea does not count.
    private static double waterShare(WorldServer world, BlockPos start, boolean[] near) {
        int water = 0;
        for (int dz = -SPAN; dz <= SPAN; dz++) {
            for (int dx = -SPAN; dx <= SPAN; dx++) {
                if (!near[index(dx, dz)]) continue;
                int x = start.getX() + dx, z = start.getZ() + dz;
                if (!BlockRules.isWater(surfaceState(world, x, z))) continue;
                if (BiomeDictionary.hasType(world.getBiome(new BlockPos(x, 0, z)), BiomeDictionary.Type.OCEAN)) continue;
                water++;
            }
        }
        return Math.min(1.0, (double) water / WATER_COLUMNS_FULL);
    }

    // Open sky over the patch, best with a few trees around it (a forest edge rather than bare land).
    private static double openSkyShare(WorldServer world, BlockPos start, int[] feet, boolean[] near, int cells) {
        int open = 0;
        for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
            for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
                int y = feet[index(dx, dz)];
                if (y != NOT_WALKABLE && world.canSeeSky(new BlockPos(start.getX() + dx, y, start.getZ() + dz))) open++;
            }
        }
        int ring = 0, trees = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dz = -SPAN; dz <= SPAN; dz++) {
            for (int dx = -SPAN; dx <= SPAN; dx++) {
                if (!near[index(dx, dz)]) continue;
                int x = start.getX() + dx, z = start.getZ() + dz;
                ring++;
                p.setPos(x, world.getChunk(x >> 4, z >> 4).getHeightValue(x & 15, z & 15) - 1, z);
                if (BlockRules.isTreePart(world, p, world.getBlockState(p))) trees++;
            }
        }
        double treeShare = ring == 0 ? 0 : (double) trees / ring;
        return (double) open / cells * (0.5 + 0.5 * Math.min(1.0, treeShare / TREES_FOR_EDGE));
    }

    private static double flowerShare(WorldServer world, BlockPos start, int[] feet) {
        int flowers = 0;
        for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
            for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
                int y = feet[index(dx, dz)];
                if (y != NOT_WALKABLE && isFlower(world.getBlockState(new BlockPos(start.getX() + dx, y, start.getZ() + dz)))) {
                    flowers++;
                }
            }
        }
        return Math.min(1.0, (double) flowers / FLOWERS_FULL);
    }

    private static boolean isFlower(IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof BlockFlower) return true;
        if (b instanceof BlockDoublePlant) {
            BlockDoublePlant.EnumPlantType t = s.getValue(BlockDoublePlant.VARIANT);
            return t != BlockDoublePlant.EnumPlantType.GRASS && t != BlockDoublePlant.EnumPlantType.FERN;
        }
        ResourceLocation rl = b.getRegistryName();
        return rl != null && rl.getPath().contains("flower");
    }

    // How far the spot stands above the land a few chunks around, from the terrain grid.
    private static double heightShare(WorldServer world, BlockPos center) {
        TerrainProfileGrid grid = grid(world);
        if (grid == null) return 0;
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        long sum = 0;
        int n = 0;
        for (int dz = -RING_MAX; dz <= RING_MAX; dz++) {
            for (int dx = -RING_MAX; dx <= RING_MAX; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < RING_MIN) continue;
                int wx = (cx + dx) * TerrainProfile.CELL_SIZE, wz = (cz + dz) * TerrainProfile.CELL_SIZE;
                if (grid.getSource(wx, wz) == TerrainProfile.SOURCE_EMPTY) continue;
                short h = grid.getHeight(wx, wz);
                if (h == Short.MIN_VALUE) continue;
                sum += h;
                n++;
            }
        }
        if (n == 0) return 0;
        double prominence = (center.getY() - 1) - (double) sum / n;
        return Math.max(0, Math.min(1.0, prominence / PROMINENCE_FULL));
    }

    @Nullable
    private static TerrainProfileGrid grid(WorldServer world) {
        TerrainWorldState state = TerrainWorldState.get(world);
        return state == null ? null : state.getGrid();
    }

    private static int biomeScore(Biome biome) {
        String[] source = TravellersConfig.REST_SPOTS.biomeScores;
        if (source != biomeSource) {
            Map<ResourceLocation, Integer> parsed = new HashMap<>();
            for (String entry : source) {
                if (entry == null) continue;
                int eq = entry.lastIndexOf('=');
                if (eq <= 0) continue;
                try {
                    parsed.put(new ResourceLocation(entry.substring(0, eq).trim().toLowerCase(Locale.ROOT)),
                            Integer.parseInt(entry.substring(eq + 1).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            biomeScores = parsed;
            biomeSource = source;
        }
        Integer score = biomeScores.get(biome.getRegistryName());
        return score == null ? 0 : score;
    }
}
