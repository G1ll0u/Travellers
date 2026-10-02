package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.pathing.terrain.BiomeTerrainEstimator;
import com.jubitus.travellers.pathing.terrain.BiomeWaterClass;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grid-scan replacement for the old 8-ray landmark search (2026-09-17 feedback): a biome or water
 * body is essentially never shaped like a compass rose, so sampling along 8 fixed rays and reporting
 * whichever ray hits a landmark first reports the direction of an arbitrary ray, not the direction of
 * the nearest actual edge of that landmass - and it had no notion of water at all (oceans and rivers
 * were invisible to it; only {@code TerrainSummaryDialogueContextProvider}'s immediate-area phrase
 * ever mentioned water, and only for the traveller's own ~64-block surroundings).
 *
 * <p>Instead this samples a square grid of {@code World.getBiome} calls (no chunk load, same trick
 * the ray search already relied on) and flood-fills same-kind neighbours into actual connected
 * regions ("blobs"). The nearest sampled cell of a blob gives the true closest approach - fixing the
 * direction - and the blob's cell count gives an honest sense of scale. Sea/lake/river are told apart
 * by shape (bounded and roundish -> lake; reaches the edge of the whole survey -> sea; bounded but
 * long and thin -> river), never by biome name - this world's lakes and rivers can share one biome id,
 * so trusting the tag to tell them apart doesn't work.
 *
 * <p>The sampled grid is small (a few thousand cells at most) and flood-filling it is a plain
 * in-memory BFS with no further world access - cheap enough to run to completion once per dialogue
 * open, no cap needed.
 */
final class TerrainLandmarkScanner {

    private static final int[][] NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Grid-distance bucket, matching the 100-block rounding every other dialogue distance uses (D-8). */
    private static final int BUCKET_SIZE = 100;

    private TerrainLandmarkScanner() {
    }

    private enum WaterKind {SEA, LAKE, RIVER}

    private static final class Cell {
        boolean water;
        BiomeLandmarkClassifier.Kind landKind = BiomeLandmarkClassifier.Kind.NONE;
        Biome biome;
    }

    private static final class Blob {
        boolean water;
        BiomeLandmarkClassifier.Kind landKind;
        WaterKind waterKind;
        boolean containsOrigin;
        int cellCount;
        int minIx = Integer.MAX_VALUE, maxIx = Integer.MIN_VALUE, minIz = Integer.MAX_VALUE, maxIz = Integer.MIN_VALUE;
        boolean touchesEdge;
        long nearestDistSq = Long.MAX_VALUE;
        int nearestIx, nearestIz;
        Biome nearestBiome;
    }

    static List<DialogueContext.BiomeFeatureBlurb> scan(World world, BlockPos origin,
                                                          int radiusBlocks, int spacingBlocks, int maxCount) {
        List<DialogueContext.BiomeFeatureBlurb> out = new ArrayList<>();
        if (maxCount <= 0 || radiusBlocks <= 0 || spacingBlocks <= 0) return out;

        int n = Math.max(1, radiusBlocks / spacingBlocks);
        int size = n * 2 + 1;

        Cell[][] grid = new Cell[size][size];
        for (int iz = 0; iz < size; iz++) {
            for (int ix = 0; ix < size; ix++) {
                int wx = origin.getX() + (ix - n) * spacingBlocks;
                int wz = origin.getZ() + (iz - n) * spacingBlocks;
                Biome b = world.getBiome(new BlockPos(wx, origin.getY(), wz));
                Cell c = new Cell();
                c.biome = b;
                if (b != null) {
                    BiomeWaterClass wc = BiomeTerrainEstimator.classify(b);
                    c.water = wc == BiomeWaterClass.OCEAN || wc == BiomeWaterClass.RIVER;
                    if (!c.water) c.landKind = BiomeLandmarkClassifier.classify(b);
                }
                grid[ix][iz] = c;
            }
        }

        boolean[][] visited = new boolean[size][size];
        List<Blob> blobs = new ArrayList<>();
        for (int iz = 0; iz < size; iz++) {
            for (int ix = 0; ix < size; ix++) {
                if (visited[ix][iz]) continue;
                Cell seed = grid[ix][iz];
                if (!seed.water && seed.landKind == BiomeLandmarkClassifier.Kind.NONE) {
                    visited[ix][iz] = true; // unremarkable ground - never grouped or reported
                    continue;
                }
                Blob blob = floodFill(grid, visited, ix, iz, size, n);
                if (!blob.containsOrigin) blobs.add(blob);
            }
        }

        // One landmark per resolved kind - closest occurrence wins - so the prompt stays as short as
        // the old ray search kept it, but now "closest" means the blob's true nearest cell, not
        // whichever fixed ray happened to hit it first.
        Map<String, Blob> bestPerKey = new HashMap<>();
        for (Blob blob : blobs) {
            if (blob.water) blob.waterKind = classifyWater(blob);
            String key = blob.water ? blob.waterKind.name() : blob.landKind.name();
            Blob existing = bestPerKey.get(key);
            if (existing == null || blob.nearestDistSq < existing.nearestDistSq) {
                bestPerKey.put(key, blob);
            }
        }

        List<Blob> finalists = new ArrayList<>(bestPerKey.values());
        finalists.sort(Comparator.comparingLong(b -> b.nearestDistSq));
        for (Blob blob : finalists) {
            if (out.size() >= maxCount) break;
            String name = blob.water ? waterName(blob) : landName(blob);
            if (name == null || name.isEmpty()) continue;
            int dx = bucket((blob.nearestIx - n) * spacingBlocks);
            int dz = bucket((blob.nearestIz - n) * spacingBlocks);
            out.add(new DialogueContext.BiomeFeatureBlurb(name, dx, dz));
        }
        return out;
    }

    private static Blob floodFill(Cell[][] grid, boolean[][] visited, int startX, int startZ, int size, int originIdx) {
        Cell seedCell = grid[startX][startZ];
        Blob blob = new Blob();
        blob.water = seedCell.water;
        blob.landKind = seedCell.landKind;

        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startZ});
        visited[startX][startZ] = true;

        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int ix = p[0], iz = p[1];

            blob.cellCount++;
            if (ix < blob.minIx) blob.minIx = ix;
            if (ix > blob.maxIx) blob.maxIx = ix;
            if (iz < blob.minIz) blob.minIz = iz;
            if (iz > blob.maxIz) blob.maxIz = iz;
            if (ix == 0 || iz == 0 || ix == size - 1 || iz == size - 1) blob.touchesEdge = true;
            if (ix == originIdx && iz == originIdx) blob.containsOrigin = true;

            long dx = ix - originIdx, dz = iz - originIdx;
            long distSq = dx * dx + dz * dz;
            if (distSq < blob.nearestDistSq) {
                blob.nearestDistSq = distSq;
                blob.nearestIx = ix;
                blob.nearestIz = iz;
                blob.nearestBiome = grid[ix][iz].biome;
            }

            for (int[] d : NEIGHBORS) {
                int nx = ix + d[0], nz = iz + d[1];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size || visited[nx][nz]) continue;
                Cell nc = grid[nx][nz];
                if (nc.water != blob.water) continue;
                if (!blob.water && nc.landKind != blob.landKind) continue;
                visited[nx][nz] = true;
                queue.add(new int[]{nx, nz});
            }
        }
        return blob;
    }

    /**
     * Shape, never the biome tag, decides sea vs lake vs river - the whole point of this rewrite
     * (2026-09-17 report: rivers and lakes share a biome id on this world). A blob that stays inside
     * the survey and is roughly as wide as it is tall reads as a lake; one that runs off the edge of
     * what was even looked at reads as the sea; anything else bounded-but-thin is a river.
     */
    private static WaterKind classifyWater(Blob blob) {
        int w = blob.maxIx - blob.minIx + 1;
        int h = blob.maxIz - blob.minIz + 1;
        double elongation = Math.max(w, h) / (double) Math.max(1, Math.min(w, h));
        double fillRatio = blob.cellCount / (double) (w * h);
        if (!blob.touchesEdge && elongation <= 1.8 && fillRatio >= 0.45) return WaterKind.LAKE;
        if (blob.touchesEdge) return WaterKind.SEA;
        return WaterKind.RIVER;
    }

    private static String waterName(Blob blob) {
        switch (blob.waterKind) {
            case SEA:
                return "the sea";
            case LAKE:
                return blob.cellCount <= 1 ? "a small pond" : blob.cellCount >= 6 ? "a large lake" : "a lake";
            case RIVER:
            default:
                return "a river";
        }
    }

    private static String landName(Blob blob) {
        String biomeName = WorldStateDialogueContextProvider.biomeDisplayName(blob.nearestBiome);
        if (biomeName.isEmpty()) return null;
        String sizeAdjective;
        if (blob.touchesEdge || blob.cellCount >= 20) sizeAdjective = "a vast expanse of ";
        else if (blob.cellCount >= 8) sizeAdjective = "a wide stretch of ";
        else if (blob.cellCount <= 2) sizeAdjective = "a small patch of ";
        else sizeAdjective = "";
        return sizeAdjective + biomeName + " area";
    }

    /** Same convention as {@code AmbientActivityDialogueContextProvider.bucket} - never an exact figure (D-8). */
    private static int bucket(int offset) {
        return Math.round(offset / (float) BUCKET_SIZE) * BUCKET_SIZE;
    }
}
