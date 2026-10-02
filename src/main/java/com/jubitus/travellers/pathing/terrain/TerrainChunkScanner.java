package com.jubitus.travellers.pathing.terrain;

import com.jubitus.travellers.ground.BlockRules;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * Measures one chunk into a terrain cell sample: 64 columns (8x8), each walked down from the
 * light-heightmap top past foliage to the real ground or water surface. Fed by {@code ChunkReader}.
 */
public final class TerrainChunkScanner {

    private TerrainChunkScanner() {
    }

    /** How far below the light-heightmap top a column may descend past foliage before giving up. */
    private static final int MAX_DESCENT = 48;

    /** Measures one loaded chunk into a packed cell sample ({@link #pack}). Server thread only. */
    static long measure(WorldServer world, Chunk chunk, BiomeIndex biomeIndex) {
        long sumH = 0;
        int nH = 0;
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        int liquid = 0;

        for (int z = 1; z < 16; z += 2) {
            for (int x = 1; x < 16; x += 2) {
                int enc = groundColumn(chunk, x, z, chunk.getHeightValue(x, z));
                if (enc == 0) continue; // nothing found in range
                int h = Math.abs(enc);
                sumH += h;
                nH++;
                if (h < minH) minH = h;
                if (h > maxH) maxH = h;
                if (enc < 0) liquid++; // negative encodes "surface block is liquid"
            }
        }
        int heightMean = nH > 0 ? (int) (sumH / nH) : 0;
        int amplitude = nH > 0 ? (maxH - minH) : 0;
        int water255 = nH > 0 ? Math.round(255.0f * liquid / nH) : 0;

        net.minecraft.world.biome.Biome biome = chunk.getBiome(
                new net.minecraft.util.math.BlockPos(8, 64, 8), world.getBiomeProvider());
        ResourceLocation biomeName = biome == null ? null : biome.getRegistryName();
        short biomeIx = biomeName != null
                ? biomeIndex.idFor(biomeName)
                : TerrainProfile.BIOME_IX_UNKNOWN;
        return pack(heightMean, amplitude, water255, biomeIx);
    }

    /**
     * Stores a measured sample as a real cell. Returns {@code false} (and requests the region load)
     * when the region is not resident yet - the caller keeps the sample until it is.
     */
    static boolean store(WorldServer world, TerrainProfileGrid grid, int cellX, int cellZ, long sample) {
        boolean wasReal = grid.getSource(cellX * 16, cellZ * 16) == TerrainProfile.SOURCE_REAL_SCAN;
        boolean stored = grid.putRealCell(cellX, cellZ,
                heightOf(sample), ampOf(sample), waterOf(sample), biomeOf(sample));
        if (stored && !wasReal) {
            // A cell newly proven real invalidates any cached coarse route whose box covered it.
            com.jubitus.travellers.pathing.route.TravelRoutePlanner.onRealScan(
                    world.provider.getDimension(), cellX, cellZ);
        }
        return stored;
    }

    static long pack(int heightMean, int amplitude, int water255, short biomeIx) {
        int amp = Math.max(0, Math.min(255, amplitude));
        int water = Math.max(0, Math.min(255, water255));
        return ((long) heightMean & 0xFFFFL)
                | (((long) amp) << 16)
                | (((long) water) << 24)
                | (((long) biomeIx & 0xFFFFL) << 32);
    }

    static int heightOf(long sample) {
        return (short) (sample & 0xFFFFL);
    }

    static int ampOf(long sample) {
        return (int) ((sample >>> 16) & 0xFFL);
    }

    static int waterOf(long sample) {
        return (int) ((sample >>> 24) & 0xFFL);
    }

    static short biomeOf(long sample) {
        return (short) ((sample >>> 32) & 0xFFFFL);
    }

    /**
     * Walks down one column from the light-heightmap top, past air and foliage, to the first real
     * terrain (or water) surface.
     *
     * @return {@code 0} if nothing was found within {@link #MAX_DESCENT}; otherwise the surface Y
     * (the standable level, one above the found block - parity with {@code getHeightValue}),
     * <b>negated</b> when that surface block is liquid.
     */
    private static int groundColumn(Chunk chunk, int x, int z, int heightmapTop) {
        int yStart = Math.min(heightmapTop, 255);
        if (yStart <= 0) return 0;
        int floor = Math.max(0, yStart - MAX_DESCENT);
        for (int y = yStart; y >= floor; y--) {
            IBlockState st = chunk.getBlockState(x, y, z);
            Material m = st.getMaterial();
            if (m == Material.AIR) continue;
            if (BlockRules.isTreePart(st) || BlockRules.isVegetation(st)) continue;
            int surfaceY = y + 1;
            return m.isLiquid() ? -surfaceY : surfaceY;
        }
        // All air/foliage down to the cap (an overhang, or a bug) - fall back to the raw top.
        return heightmapTop;
    }
}
