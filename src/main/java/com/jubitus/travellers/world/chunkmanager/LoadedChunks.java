package com.jubitus.travellers.world.chunkmanager;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

/**
 * Side-effect-free lookup of loaded chunks. {@code ChunkProviderServer.getLoadedChunk} clears the
 * chunk's queued unload, which keeps it loaded until the next autosave; this never does.
 */
public final class LoadedChunks {

    private LoadedChunks() {
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
}
