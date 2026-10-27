package com.jubitus.travellers.pathing;

import com.jubitus.travellers.ground.chunk.LoadedChunks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * The block view of one of our path searches: loaded chunks only. A missing chunk reads as a blank one
 * (all air, no floor, so never walked into) and is never loaded, unlike vanilla's ChunkCache.
 */
final class LoadedOnlyChunkCache extends ChunkCache {

    // INVARIANT: missing chunks share one blank chunk; building a Chunk is costly (entity lists) and a search may need dozens.
    private static Chunk blank;

    /** {@code home} is the searcher's own position: the only chunk vanilla's constructor touches, loaded since it ticks. */
    LoadedOnlyChunkCache(WorldServer world, BlockPos home, BlockPos from, BlockPos to) {
        super(world, home, home, 0);
        this.chunkX = from.getX() >> 4;
        this.chunkZ = from.getZ() >> 4;
        int maxX = to.getX() >> 4;
        int maxZ = to.getZ() >> 4;
        this.chunkArray = new Chunk[maxX - chunkX + 1][maxZ - chunkZ + 1];
        this.empty = true;
        for (int x = chunkX; x <= maxX; x++) {
            for (int z = chunkZ; z <= maxZ; z++) {
                Chunk c = LoadedChunks.peek(world, x, z);
                if (c == null) c = blank(world);
                else if (!c.isEmptyBetween(from.getY(), to.getY())) this.empty = false;
                this.chunkArray[x - chunkX][z - chunkZ] = c;
            }
        }
    }

    private static Chunk blank(WorldServer world) {
        if (blank == null || blank.getWorld() != world) blank = new Chunk(world, 0, 0);
        return blank;
    }
}
