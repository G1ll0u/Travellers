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
        byte[] out = new byte[64 * 64]; // gilles 0 = unknown
        fillMissingPixels(world, tileX, tileZ, out);
        return out;
    }

    /**
     * Progressive generation:
     * - Only fills pixels that are currently 0 (unknown)
     * - Only fills if the sample position is loaded
     * Returns true if any pixel changed.
     */
    public static boolean fillMissingPixels(World world, int tileX, int tileZ, byte[] inout) {
        if (inout == null || inout.length != 64 * 64) return false;

        final int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16; // 256
        final int step = tileBlocks / 64; // 4 blocks per pixel

        final int baseX = tileX * tileBlocks;
        final int baseZ = tileZ * tileBlocks;

        boolean changed = false;

        for (int pz = 0; pz < 64; pz++) {
            for (int px = 0; px < 64; px++) {
                int idx = pz * 64 + px;

                // already generated
                if ((inout[idx] & 0xFF) != 0) continue;

                int wx = baseX + px * step + step / 2;
                int wz = baseZ + pz * step + step / 2;

                BlockPos col = new BlockPos(wx, 0, wz);

                // Do NOT force chunk loads
                if (!world.isBlockLoaded(col, false)) continue;

                int tone = sampleTone(world, wx, wz, step);
                inout[idx] = (byte) (tone & 0xFF);
                changed = true;
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
        if (heightHere > 110) base = clamp(base + 15, 60, 220);
        if (heightHere > 140) base = clamp(base + 20, 60, 235);

        int dx = heightHere - heightX;
        int dz = heightHere - heightZ;
        int shade = clamp(128 + dx * 4 + dz * 4, 70, 200);

        int tone = (base * 2 + shade) / 3;

        if (isWater) tone = clamp(55 + shade / 6, 40, 120);
        if (isLava)  tone = clamp(170 + shade / 8, 160, 230);

        // IMPORTANT: tone must never be 0 for real terrain (0 is our "unknown" sentinel)
        return clamp(tone, 1, 255);
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
}

