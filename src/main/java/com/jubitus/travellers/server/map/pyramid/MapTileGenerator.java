package com.jubitus.travellers.server.map.pyramid;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Samples world terrain into level-0 pyramid tiles (256x256 pixels, 1 pixel = 1 block).
 * Higher pyramid levels are never sampled from the world — they're pure downsamples of the
 * level below (see {@link #downsample}), computed by {@link PyramidDirtyPropagator}.
 */
public final class MapTileGenerator {
    private MapTileGenerator() {}

    public static final int TILE_PX    = 256;
    public static final int TILE_BYTES = TILE_PX * TILE_PX;

    // ── Level-0 generation (world sampling) ──────────────────────────────────

    public static boolean fillMissingPixels(World world, int tileX, int tileZ, byte[] inout, int maxPixelsToFill) {
        if (inout == null || inout.length != TILE_BYTES) return false;
        if (maxPixelsToFill <= 0) return false;

        final int baseX = tileX * TILE_PX;
        final int baseZ = tileZ * TILE_PX;

        boolean changed = false;
        int filledNow = 0;

        for (int pz = 0; pz < TILE_PX; pz++) {
            for (int px = 0; px < TILE_PX; px++) {
                int idx = pz * TILE_PX + px;
                if ((inout[idx] & 0xFF) != 0) continue;

                int wx = baseX + px;
                int wz = baseZ + pz;

                if (!world.isBlockLoaded(new BlockPos(wx, 0, wz), false)) continue;

                int tone = sampleTone(world, wx, wz, 1);
                if (tone < 0) continue;

                inout[idx] = (byte) (tone & 0xFF);
                changed = true;
                if (++filledNow >= maxPixelsToFill) return true;
            }
        }
        return changed;
    }

    /**
     * Fills only the level-0 pixels that fall within the given chunk (16x16 blocks = 16x16 pixels
     * at 1px = 1 block, exactly one chunk's worth, no scan of the rest of the tile).
     */
    public static boolean fillChunkPixels(World world, int chunkX, int chunkZ, byte[] inout) {
        if (inout == null || inout.length != TILE_BYTES) return false;

        final int chunksPerTile = WorldMapDiscovery.CHUNKS_PER_TILE; // 16
        int tileX = Math.floorDiv(chunkX, chunksPerTile);
        int tileZ = Math.floorDiv(chunkZ, chunksPerTile);

        int localChunkX = chunkX - tileX * chunksPerTile;
        int localChunkZ = chunkZ - tileZ * chunksPerTile;

        final int lx0 = localChunkX * 16;
        final int lz0 = localChunkZ * 16;

        final int baseX = tileX * TILE_PX;
        final int baseZ = tileZ * TILE_PX;

        boolean changed = false;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int px = lx0 + dx;
                int pz = lz0 + dz;
                int idx = pz * TILE_PX + px;
                if ((inout[idx] & 0xFF) != 0) continue;

                int wx = baseX + px;
                int wz = baseZ + pz;

                if (!world.isBlockLoaded(new BlockPos(wx, 0, wz), false)) continue;

                int tone = sampleTone(world, wx, wz, 1);
                if (tone < 0) continue;

                inout[idx] = (byte) (tone & 0xFF);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Re-samples every level-0 pixel that falls within the given chunk (16x16 blocks = 16x16
     * pixels), always overwriting whatever is already there. Unlike {@link #fillChunkPixels}, this
     * never skips a non-zero pixel — used by the continuous loaded-chunk sweep so terrain edits and
     * progressively-built structures (e.g. a growing Millenaire village) eventually repaint.
     * Pixels in unloaded chunks are skipped silently.
     */
    public static boolean refreshChunkPixels(World world, int chunkX, int chunkZ, byte[] inout) {
        if (inout == null || inout.length != TILE_BYTES) return false;

        final int chunksPerTile = WorldMapDiscovery.CHUNKS_PER_TILE; // 16
        int tileX = Math.floorDiv(chunkX, chunksPerTile);
        int tileZ = Math.floorDiv(chunkZ, chunksPerTile);

        int localChunkX = chunkX - tileX * chunksPerTile;
        int localChunkZ = chunkZ - tileZ * chunksPerTile;

        final int lx0 = localChunkX * 16;
        final int lz0 = localChunkZ * 16;

        final int baseX = tileX * TILE_PX;
        final int baseZ = tileZ * TILE_PX;

        boolean changed = false;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int px = lx0 + dx;
                int pz = lz0 + dz;
                int idx = pz * TILE_PX + px;

                int wx = baseX + px;
                int wz = baseZ + pz;

                if (!world.isBlockLoaded(new BlockPos(wx, 0, wz), false)) continue;

                int newTone = sampleTone(world, wx, wz, 1);
                if (newTone < 0) continue; // neighbor not reliably loaded this pass; leave pixel untouched

                if ((inout[idx] & 0xFF) != newTone) {
                    inout[idx] = (byte) (newTone & 0xFF);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Returns a clamped 1-255 tone, or -1 if the relief-shading neighborhood isn't reliably
     * available yet. Both neighbors must be loaded (never a silent height fallback): a neighbor
     * chunk that flips in and out of "loaded" between resample passes (chunk boundary, view
     * distance edge) would otherwise make the exact same pixel's shade term flap between two
     * different values every pass — invisible when a pixel was only ever sampled once (the old
     * write-once pregen), but a visible flicker now that {@link #refreshChunkPixels} re-samples
     * the same pixels repeatedly. -1 just means "skip this pass, try again once neighbors settle".
     */
    private static int sampleTone(World world, int wx, int wz, int step) {
        BlockPos nx = new BlockPos(wx + step, 0, wz);
        BlockPos nz = new BlockPos(wx, 0, wz + step);
        if (!world.isBlockLoaded(nx, false) || !world.isBlockLoaded(nz, false)) return -1;

        BlockPos col = new BlockPos(wx, 0, wz);
        int heightHere = world.getHeight(col).getY();
        int heightX = world.getHeight(nx).getY();
        int heightZ = world.getHeight(nz).getY();

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

    // ── Pyramid downsampling (levels 1+, pure array math, no world access) ──

    /**
     * Builds one parent tile (256x256) from its 4 children at the level below, arranged:
     * q00=(x0,z0) q10=(x1,z0) q01=(x0,z1) q11=(x1,z1). Any of the 4 may be null (not yet
     * generated) — its region is simply left unknown (0) in the result.
     * Each child's 256x256 is box-downsampled 2x into a 128x128 quadrant of the output;
     * unknown (0) source pixels are excluded from the average rather than pulling it toward 0.
     */
    public static byte[] downsample(byte[] q00, byte[] q10, byte[] q01, byte[] q11) {
        byte[] out = new byte[TILE_BYTES];
        downsampleQuadrant(q00, out, 0, 0);
        downsampleQuadrant(q10, out, TILE_PX / 2, 0);
        downsampleQuadrant(q01, out, 0, TILE_PX / 2);
        downsampleQuadrant(q11, out, TILE_PX / 2, TILE_PX / 2);
        return out;
    }

    private static void downsampleQuadrant(byte[] child, byte[] out, int outX0, int outZ0) {
        if (child == null || child.length != TILE_BYTES) return;

        final int half = TILE_PX / 2;
        for (int oz = 0; oz < half; oz++) {
            for (int ox = 0; ox < half; ox++) {
                int sx = ox * 2;
                int sz = oz * 2;
                int a = child[sz * TILE_PX + sx] & 0xFF;
                int b = child[sz * TILE_PX + sx + 1] & 0xFF;
                int c = child[(sz + 1) * TILE_PX + sx] & 0xFF;
                int d = child[(sz + 1) * TILE_PX + sx + 1] & 0xFF;

                int sum = 0, count = 0;
                if (a != 0) { sum += a; count++; }
                if (b != 0) { sum += b; count++; }
                if (c != 0) { sum += c; count++; }
                if (d != 0) { sum += d; count++; }

                int tone = (count > 0) ? (sum / count) : 0;
                out[(outZ0 + oz) * TILE_PX + (outX0 + ox)] = (byte) (tone & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
}
