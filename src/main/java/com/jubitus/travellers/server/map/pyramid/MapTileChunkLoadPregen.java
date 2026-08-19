package com.jubitus.travellers.server.map.pyramid;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Level;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Opportunistically fills pyramid level 0 whenever a chunk loads, for any reason: a player
 * exploring, a worker's travel route keeping chunks loaded, or a third-party world
 * pregeneration tool forcing chunks to load ahead of time (with no player online at all).
 * Never touches per-player fog of war (MapDiscoverySavedData) — only the shared tile data.
 *
 * Chunk loads are queued and drained under a per-tick time budget (not a flat per-tick count)
 * so this scales with whatever a bulk chunk-pregeneration tool throws at it — thousands of
 * chunk loads per tick with nobody online — without spiking a single tick's cost.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class MapTileChunkLoadPregen {

    private static final ArrayDeque<QueuedChunk> QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED_KEYS = new HashSet<>();

    private static final int CHECK_TIME_EVERY_N = 8;

    private static final class QueuedChunk {
        final World world;
        final int chunkX, chunkZ;
        QueuedChunk(World world, int chunkX, int chunkZ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load e) {
        if (!TravellersConfig.WORLDMAP.mapPregenOnChunkLoadEnabled) return;

        World world = e.getWorld();
        if (world.isRemote) return;

        Chunk chunk = e.getChunk();
        long dimAndChunkKey = queueKey(world.provider.getDimension(), chunk.x, chunk.z);

        synchronized (QUEUE) {
            if (!QUEUED_KEYS.add(dimAndChunkKey)) return;
            QUEUE.addLast(new QueuedChunk(world, chunk.x, chunk.z));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        if (!TravellersConfig.WORLDMAP.mapPregenOnChunkLoadEnabled) return;

        long budgetNanos = TravellersConfig.WORLDMAP.mapChunkLoadBudgetMsPerTick * 1_000_000L;
        long start = System.nanoTime();

        int processedSinceCheck = 0;
        while (true) {
            QueuedChunk qc;
            synchronized (QUEUE) {
                qc = QUEUE.pollFirst();
                if (qc != null) QUEUED_KEYS.remove(queueKey(qc.world.provider.getDimension(), qc.chunkX, qc.chunkZ));
            }
            if (qc == null) break;

            processChunk(qc.world, qc.chunkX, qc.chunkZ);

            if (++processedSinceCheck >= CHECK_TIME_EVERY_N) {
                processedSinceCheck = 0;
                if (System.nanoTime() - start >= budgetNanos) break;
            }
        }
    }

    private static void processChunk(World world, int chunkX, int chunkZ) {
        int chunksPerTile = WorldMapDiscovery.CHUNKS_PER_TILE;
        int tileX = Math.floorDiv(chunkX, chunksPerTile);
        int tileZ = Math.floorDiv(chunkZ, chunksPerTile);
        long key = TileKey.pack(tileX, tileZ);

        MapPyramidStore level0 = MapPyramidStore.get(world, 0);
        byte[] data = level0.get(key);
        if (data == null || data.length != MapTileGenerator.TILE_BYTES) {
            data = new byte[MapTileGenerator.TILE_BYTES];
        }
        if (MapTileGenerator.fillChunkPixels(world, chunkX, chunkZ, data)) {
            level0.put(key, data);
            int dim = world.provider.getDimension();
            PyramidDirtyPropagator.markParentDirty(dim, 0, tileX, tileZ);
            if (TravellersConfig.WORLDMAP.debugMap) {
                FMLLog.log(Level.INFO, "[Travellers/MapPregen] dim=%d chunk %d,%d first-painted -> tile %d,%d",
                        dim, chunkX, chunkZ, tileX, tileZ);
            }
        }
    }

    private static long queueKey(int dimension, int chunkX, int chunkZ) {
        // dimension folded into the high bits alongside the packed chunk coordinate;
        // collisions across dimensions are astronomically unlikely and only cause a
        // redundant re-process, never a correctness issue.
        return (((long) dimension) << 40) ^ TileKey.pack(chunkX, chunkZ);
    }
}
