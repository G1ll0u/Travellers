package com.jubitus.travellers.server.map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class MapTileGenerator {
    private MapTileGenerator() {}

    public static final int DETAIL_PX    = 256;
    public static final int DETAIL_BYTES = DETAIL_PX * DETAIL_PX;

    public static byte[] buildTile64(World world, int tileX, int tileZ) {
        byte[] out = new byte[64 * 64]; // 0 = unknown
        fillMissingPixels(world, tileX, tileZ, out);
        return out;
    }

    // Keep old signature for existing code
    public static boolean fillMissingPixels(World world, int tileX, int tileZ, byte[] inout) {
        return fillMissingPixels(world, tileX, tileZ, inout, Integer.MAX_VALUE);
    }

    /**
     * Progressive generation with a budget.
     * - Only fills pixels that are 0 (unknown)
     * - Only fills if the sample position is loaded
     * - Stops after maxPixelsToFill pixels were written this call
     */
    public static boolean fillMissingPixels(World world, int tileX, int tileZ, byte[] inout, int maxPixelsToFill) {
        if (inout == null || inout.length != 64 * 64) return false;
        if (maxPixelsToFill <= 0) return false;

        final int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16; // 256
        final int step = tileBlocks / 64; // 4 blocks per pixel

        final int baseX = tileX * tileBlocks;
        final int baseZ = tileZ * tileBlocks;

        boolean changed = false;
        int filledNow = 0;

        for (int pz = 0; pz < 64; pz++) {
            for (int px = 0; px < 64; px++) {
                int idx = pz * 64 + px;

                if ((inout[idx] & 0xFF) != 0) continue;

                int wx = baseX + px * step + step / 2;
                int wz = baseZ + pz * step + step / 2;

                BlockPos col = new BlockPos(wx, 0, wz);

                // Do NOT force chunk loads
                if (!world.isBlockLoaded(col, false)) continue;

                int tone = sampleTone(world, wx, wz, step);
                inout[idx] = (byte) (tone & 0xFF);

                changed = true;
                filledNow++;

                if (filledNow >= maxPixelsToFill) {
                    return true;
                }
            }
        }

        return changed;
    }

    /**
     * Re-samples up to {@code maxCount} pixels starting at {@code startPixel} (wraps mod 4096).
     * Unlike {@link #fillMissingPixels}, this overwrites existing non-zero values.
     * Pixels in unloaded chunks are skipped silently.
     *
     * @return number of pixels whose value actually changed
     */
    public static int refreshPixels(World world, int tileX, int tileZ, byte[] inout, int startPixel, int maxCount) {
        if (inout == null || inout.length != 64 * 64) return 0;

        final int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
        final int step = tileBlocks / 64;
        final int baseX = tileX * tileBlocks;
        final int baseZ = tileZ * tileBlocks;
        final int total = 64 * 64;

        int changed = 0;
        for (int i = 0; i < maxCount; i++) {
            int idx = (startPixel + i) % total;
            int pz = idx / 64;
            int px = idx % 64;

            int wx = baseX + px * step + step / 2;
            int wz = baseZ + pz * step + step / 2;

            if (!world.isBlockLoaded(new BlockPos(wx, 0, wz), false)) continue;

            int newTone = sampleTone(world, wx, wz, step);
            if ((inout[idx] & 0xFF) != newTone) {
                inout[idx] = (byte) (newTone & 0xFF);
                changed++;
            }
        }
        return changed;
    }

    private static int sampleTone(World world, int wx, int wz, int step) {
        BlockPos col = new BlockPos(wx, 0, wz);

        int heightHere = world.getHeight(col).getY();

        // Avoid forcing loads for neighbors; fall back to heightHere
        int heightX = heightHere;
        BlockPos nx = new BlockPos(wx + step, 0, wz);
        if (world.isBlockLoaded(nx, false)) heightX = world.getHeight(nx).getY();

        int heightZ = heightHere;
        BlockPos nz = new BlockPos(wx, 0, wz + step);
        if (world.isBlockLoaded(nz, false)) heightZ = world.getHeight(nz).getY();

        BlockPos topPos = new BlockPos(wx, Math.max(1, heightHere - 1), wz);
        IBlockState st = world.getBlockState(topPos);
        Block topBlock = st.getBlock();
        Material mat = st.getMaterial();

        boolean isWater = (mat == Material.WATER) || (topBlock == Blocks.WATER || topBlock == Blocks.FLOWING_WATER);
        boolean isLava  = (mat == Material.LAVA)  || (topBlock == Blocks.LAVA  || topBlock == Blocks.FLOWING_LAVA);

        int base = clamp(70 + (heightHere * 120 / 256), 60, 200);
        if (heightHere > 110) base = clamp(base + 18, 60, 218);
        if (heightHere > 140) base = clamp(base + 25, 60, 238);
        if (heightHere > 180) base = clamp(base + 15, 60, 248); // sommets très hauts → quasi blanc

        int dx = heightHere - heightX;
        int dz = heightHere - heightZ;
        // Multiplier 7 au lieu de 4 : relief plus visible
        int shade = clamp(128 + dx * 7 + dz * 7, 50, 210);

        // Blend 50/50 base+shade pour mieux conserver le relief dans le tone final
        int tone = (base + shade) / 2;

        if (isWater) tone = clamp(48 + shade / 5, 36, 105);
        if (isLava)  tone = clamp(175 + shade / 8, 165, 235);

        // IMPORTANT: tone must never be 0 for real terrain (0 is our "unknown" sentinel)
        return clamp(tone, 1, 255);
    }

    // ── Detail tile (256×256, 1 px = 1 block) ────────────────────────────────

    public static boolean fillDetailMissingPixels(World world, int tileX, int tileZ, byte[] inout, int maxPixelsToFill) {
        if (inout == null || inout.length != DETAIL_BYTES) return false;
        if (maxPixelsToFill <= 0) return false;

        final int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
        final int baseX = tileX * tileBlocks;
        final int baseZ = tileZ * tileBlocks;

        boolean changed = false;
        int filledNow = 0;

        for (int pz = 0; pz < DETAIL_PX; pz++) {
            for (int px = 0; px < DETAIL_PX; px++) {
                int idx = pz * DETAIL_PX + px;
                if ((inout[idx] & 0xFF) != 0) continue;

                int wx = baseX + px;
                int wz = baseZ + pz;

                if (!world.isBlockLoaded(new BlockPos(wx, 0, wz), false)) continue;

                inout[idx] = (byte) (sampleTone(world, wx, wz, 1) & 0xFF);
                changed = true;
                if (++filledNow >= maxPixelsToFill) return true;
            }
        }
        return changed;
    }

    public static int refreshDetailPixels(World world, int tileX, int tileZ, byte[] inout, int startPixel, int maxCount) {
        if (inout == null || inout.length != DETAIL_BYTES) return 0;

        final int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
        final int baseX = tileX * tileBlocks;
        final int baseZ = tileZ * tileBlocks;

        int changed = 0;
        for (int i = 0; i < maxCount; i++) {
            int idx = (startPixel + i) % DETAIL_BYTES;
            int pz = idx / DETAIL_PX;
            int px = idx % DETAIL_PX;

            int wx = baseX + px;
            int wz = baseZ + pz;

            if (!world.isBlockLoaded(new BlockPos(wx, 0, wz), false)) continue;

            int newTone = sampleTone(world, wx, wz, 1);
            if ((inout[idx] & 0xFF) != newTone) {
                inout[idx] = (byte) (newTone & 0xFF);
                changed++;
            }
        }
        return changed;
    }

    // ── Chunk-scoped fill (opportunistic pregen on chunk load) ──────────────

    /**
     * Fills only the normal-tile (64x64) pixels that fall within the given chunk.
     * Chunk boundaries are pixel-aligned (16 blocks = 4 pixels at step 4), so this
     * touches exactly the pixels owned by that chunk, no scan of the rest of the tile.
     */
    public static boolean fillChunkPixels(World world, int chunkX, int chunkZ, byte[] inout) {
        if (inout == null || inout.length != 64 * 64) return false;

        final int chunksPerTile = WorldMapDiscovery.CHUNKS_PER_TILE;
        int tileX = Math.floorDiv(chunkX, chunksPerTile);
        int tileZ = Math.floorDiv(chunkZ, chunksPerTile);

        int localChunkX = chunkX - tileX * chunksPerTile;
        int localChunkZ = chunkZ - tileZ * chunksPerTile;

        final int pxPerChunk = 64 / chunksPerTile; // 4
        final int lpx0 = localChunkX * pxPerChunk;
        final int lpz0 = localChunkZ * pxPerChunk;

        final int tileBlocks = chunksPerTile * 16;
        final int step = tileBlocks / 64; // 4
        final int baseX = tileX * tileBlocks;
        final int baseZ = tileZ * tileBlocks;

        boolean changed = false;
        for (int dz = 0; dz < pxPerChunk; dz++) {
            for (int dx = 0; dx < pxPerChunk; dx++) {
                int px = lpx0 + dx;
                int pz = lpz0 + dz;
                int idx = pz * 64 + px;
                if ((inout[idx] & 0xFF) != 0) continue;

                int wx = baseX + px * step + step / 2;
                int wz = baseZ + pz * step + step / 2;

                if (!world.isBlockLoaded(new BlockPos(wx, 0, wz), false)) continue;

                inout[idx] = (byte) (sampleTone(world, wx, wz, step) & 0xFF);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Fills only the detail-tile (256x256, 1px = 1 block) pixels that fall within the given chunk.
     */
    public static boolean fillDetailChunkPixels(World world, int chunkX, int chunkZ, byte[] inout) {
        if (inout == null || inout.length != DETAIL_BYTES) return false;

        final int chunksPerTile = WorldMapDiscovery.CHUNKS_PER_TILE;
        int tileX = Math.floorDiv(chunkX, chunksPerTile);
        int tileZ = Math.floorDiv(chunkZ, chunksPerTile);

        int localChunkX = chunkX - tileX * chunksPerTile;
        int localChunkZ = chunkZ - tileZ * chunksPerTile;

        final int lx0 = localChunkX * 16;
        final int lz0 = localChunkZ * 16;

        final int tileBlocks = chunksPerTile * 16;
        final int baseX = tileX * tileBlocks;
        final int baseZ = tileZ * tileBlocks;

        boolean changed = false;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int px = lx0 + dx;
                int pz = lz0 + dz;
                int idx = pz * DETAIL_PX + px;
                if ((inout[idx] & 0xFF) != 0) continue;

                int wx = baseX + px;
                int wz = baseZ + pz;

                if (!world.isBlockLoaded(new BlockPos(wx, 0, wz), false)) continue;

                inout[idx] = (byte) (sampleTone(world, wx, wz, 1) & 0xFF);
                changed = true;
            }
        }
        return changed;
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
}

