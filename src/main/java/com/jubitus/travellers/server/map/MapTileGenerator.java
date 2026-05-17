package com.jubitus.travellers.server.map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class MapTileGenerator {
    private MapTileGenerator() {}

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

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
}

