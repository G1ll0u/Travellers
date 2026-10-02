package com.jubitus.travellers.world.chunkmanager;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Side-effect-free lookup of loaded chunks. {@code ChunkProviderServer.getLoadedChunk} clears the
 * chunk's queued unload, which keeps it loaded until the next autosave; this never does.
 */
public final class LoadedChunks {

    private static long jobTick = Long.MIN_VALUE;
    private static int jobsThisTick;

    private LoadedChunks() {
    }

    /** World-wide pace for offscreen jobs that load chunks temporarily: false means try again on a later tick. */
    public static boolean tryAcquireJob(WorldServer world) {
        long now = world.getTotalWorldTime();
        if (now != jobTick) {
            jobTick = now;
            jobsThisTick = 0;
        }
        if (jobsThisTick >= Math.max(1, com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.chestJobsPerTick)) return false;
        jobsThisTick++;
        return true;
    }

    /** The loaded chunk at these chunk coordinates, or null. Never loads, never delays an unload. */
    @Nullable
    public static Chunk peek(WorldServer world, int chunkX, int chunkZ) {
        return world.getChunkProvider().loadedChunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Same, from a packed {@link ChunkPos#asLong} key. */
    @Nullable
    public static Chunk peek(WorldServer world, long chunkKey) {
        return world.getChunkProvider().loadedChunks.get(chunkKey);
    }

    /**
     * Loads an already-generated chunk for a short job (never generates one). A chunk this call had
     * to load is added to {@code loadedByUs}; hand that list to {@link #release} once the job is done.
     */
    @Nullable
    public static Chunk loadTemporarily(WorldServer world, int chunkX, int chunkZ, List<Chunk> loadedByUs) {
        Chunk chunk = peek(world, chunkX, chunkZ);
        if (chunk != null) return chunk;
        chunk = world.getChunkProvider().loadChunk(chunkX, chunkZ);
        if (chunk != null) loadedByUs.add(chunk);
        return chunk;
    }

    /** Queues the unload of chunks loaded by {@link #loadTemporarily}, unless a player is watching them by now. */
    public static void release(WorldServer world, List<Chunk> loadedByUs) {
        for (Chunk chunk : loadedByUs) {
            if (!world.getPlayerChunkMap().contains(chunk.x, chunk.z)) {
                world.getChunkProvider().queueUnload(chunk);
            }
        }
        loadedByUs.clear();
    }
}
