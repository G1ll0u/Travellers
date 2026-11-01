package com.jubitus.travellers.worldmap.pyramid;

import com.jubitus.travellers.ground.BlockRules;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * Paints one loaded chunk's 16x16 level-0 pixels straight from the chunk (heightmap + top block),
 * never through the world, so it needs no neighbour chunk. Stores and pushes the tile only when a
 * pixel changed. The one level-0 writer: chunk reader, background sweep and admin resweep.
 */
public final class MapChunkPainter {

    private MapChunkPainter() {
    }

    private static final int MAX_CANOPY_DEPTH = 40;

    private static final int[] HEIGHTS = new int[256];
    private static final byte[] TOP_KIND = new byte[256];
    private static final byte[] TONES = new byte[256];

    /** Server thread only. Returns true if any pixel changed. */
    public static boolean paint(WorldServer world, Chunk chunk) {
        long t0 = System.nanoTime();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int y = Math.max(1, chunk.getHeightValue(x, z) - 1);
                IBlockState top = chunk.getBlockState(x, y, z);
                boolean canopy = BlockRules.isCanopy(top);
                if (canopy) {
                    // Relief comes from the ground under the trees, so a forest edge is not drawn as a cliff
                    int floor = Math.max(1, y - MAX_CANOPY_DEPTH);
                    while (y > floor && isUnderCanopy(top)) top = chunk.getBlockState(x, --y, z);
                }
                HEIGHTS[i] = y + 1;
                Material m = top.getMaterial();
                TOP_KIND[i] = m == Material.WATER ? MapTileGenerator.TOP_WATER
                        : m == Material.LAVA ? MapTileGenerator.TOP_LAVA
                        : canopy ? MapTileGenerator.TOP_FOLIAGE
                        : MapTileGenerator.TOP_SOLID;
            }
        }
        MapTileGenerator.chunkTones(HEIGHTS, TOP_KIND, TONES);

        int tileX = Math.floorDiv(chunk.x, WorldMapDiscovery.CHUNKS_PER_TILE);
        int tileZ = Math.floorDiv(chunk.z, WorldMapDiscovery.CHUNKS_PER_TILE);
        long key = TileKey.pack(tileX, tileZ);
        MapPyramidStore level0 = MapPyramidStore.get(world, 0);
        byte[] data = level0.get(key);
        if (data == null || data.length != MapTileGenerator.TILE_BYTES) data = new byte[MapTileGenerator.TILE_BYTES];

        boolean changed = writeChunk(data, chunk.x - tileX * WorldMapDiscovery.CHUNKS_PER_TILE,
                chunk.z - tileZ * WorldMapDiscovery.CHUNKS_PER_TILE, TONES);
        if (changed) {
            level0.put(key, data);
            int dim = world.provider.getDimension();
            PyramidDirtyPropagator.markParentDirty(dim, 0, tileX, tileZ);
            MapLoadedChunkResampler.pushTileUpdate(dim, 0, tileX, tileZ, data);
            MapFillStats.painted++;
        } else {
            MapFillStats.unchanged++;
        }
        MapFillStats.paintNanos += System.nanoTime() - t0;
        return changed;
    }

    /** Tree parts, air and plants between a canopy and the ground below it. */
    private static boolean isUnderCanopy(IBlockState s) {
        Material m = s.getMaterial();
        return BlockRules.isTreePart(s) || (!m.blocksMovement() && !m.isLiquid());
    }

    /** Copies a chunk's 256 tones into its place in a tile. Returns true if any pixel changed. */
    static boolean writeChunk(byte[] tile, int localChunkX, int localChunkZ, byte[] tones) {
        boolean changed = false;
        for (int z = 0; z < 16; z++) {
            int row = (localChunkZ * 16 + z) * MapTileGenerator.TILE_PX + localChunkX * 16;
            for (int x = 0; x < 16; x++) {
                byte t = tones[z * 16 + x];
                if (tile[row + x] != t) {
                    tile[row + x] = t;
                    changed = true;
                }
            }
        }
        return changed;
    }
}
