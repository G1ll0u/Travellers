package com.jubitus.travellers.worldmap.pyramid;

import com.jubitus.travellers.ground.chunk.LoadedChunks;
import java.util.ArrayList;
import java.util.List;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Drives "/travellersdebug map resweep [all]": an explicit, on-demand escape hatch for worldmap
 * tiles that {@link MapLoadedChunkResampler} can never reach automatically because nobody has
 * visited that chunk since it was first captured (e.g. deep in a bulk-pregenerated world). Every
 * level-0 tile that already has captured data is force-refreshed once, chunk by chunk, using the
 * same "temporary load, no ticket, unloads naturally" pattern as {@code CampResetCoordinator} —
 * this is the one place in the worldmap system deliberately allowed to load chunks, since there is
 * no other way to know a chunk's current terrain/structures without touching it.
 *
 * Paced at {@link TravellersConfig.Worldmap#mapResweepChunksPerTick} chunks per tick so a large
 * backlog can't spike a single tick's cost.
 */
public class MapResweepCoordinator {

    public static final MapResweepCoordinator INSTANCE = new MapResweepCoordinator();

    private final Map<Integer, ResweepJob> jobs = new HashMap<>();

    private MapResweepCoordinator() {}

    public boolean isActive(int dim) {
        return jobs.containsKey(dim);
    }

    /** Stops a running resweep job for this dimension, if any. Returns false if none was running. */
    public boolean cancel(int dim, ICommandSender requester) {
        ResweepJob job = jobs.remove(dim);
        if (job == null) return false;
        requester.sendMessage(new TextComponentString(TextFormatting.YELLOW
                + "Map resweep cancelled: " + job.done + "/" + job.total + " chunk(s) had been processed."));
        if (TravellersConfig.WORLDMAP.debugMap) {
            FMLLog.log(Level.INFO, "[Travellers/MapResweep] dim=%d cancelled at %d/%d chunk(s)", dim, job.done, job.total);
        }
        return true;
    }

    /** Starts a resweep job for this world. Returns false if one is already running here. */
    public boolean start(WorldServer world, ICommandSender requester) {
        int dim = world.provider.getDimension();
        if (jobs.containsKey(dim)) return false;
        if (!MapPyramidStore.isMapped(world)) {
            requester.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "Map resweep: the worldmap only covers the overworld."));
            return true;
        }

        MapPyramidStore level0 = MapPyramidStore.get(world, 0);
        Deque<Long> pendingTiles = new ArrayDeque<>(level0.keySet());

        if (pendingTiles.isEmpty()) {
            requester.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "Map resweep: no captured worldmap tiles found in this dimension."));
            return true;
        }

        Deque<Long> pendingChunks = new ArrayDeque<>();
        for (long tileKey : pendingTiles) enqueueTileChunks(pendingChunks, tileKey);

        ResweepJob job = new ResweepJob(pendingChunks, requester, world.getTotalWorldTime());
        jobs.put(dim, job);
        requester.sendMessage(new TextComponentString(TextFormatting.GREEN
                + "Map resweep started: " + TextFormatting.YELLOW + pendingTiles.size() + TextFormatting.GREEN
                + " tile(s), " + TextFormatting.YELLOW + job.total + TextFormatting.GREEN + " chunk(s) queued."));
        if (TravellersConfig.WORLDMAP.debugMap) {
            FMLLog.log(Level.INFO, "[Travellers/MapResweep] dim=%d started: %d tile(s), %d chunk(s) queued (this is the on-demand admin command, not the always-on background sweep)",
                    dim, pendingTiles.size(), job.total);
        }
        return true;
    }

    public void tick(WorldServer world, long now) {
        int dim = world.provider.getDimension();
        ResweepJob job = jobs.get(dim);
        if (job == null) return;

        int budget = TravellersConfig.WORLDMAP.mapResweepChunksPerTick;
        for (int i = 0; i < budget && !job.pendingChunks.isEmpty(); i++) {
            long chunkKey = job.pendingChunks.poll();
            processChunk(world, dim, chunkKey);
            job.done++;
        }

        int reportInterval = TravellersConfig.WORLDMAP.mapResweepProgressIntervalTicks;
        if (now - job.lastReportTick >= reportInterval) {
            job.lastReportTick = now;
            job.requester.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Map resweep progress: " + job.done + "/" + job.total + " chunk(s) processed."));
        }

        if (job.pendingChunks.isEmpty()) {
            job.requester.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Map resweep complete: " + TextFormatting.YELLOW + job.total + TextFormatting.GREEN
                    + " chunk(s) processed."));
            jobs.remove(dim);
            if (TravellersConfig.WORLDMAP.debugMap) {
                FMLLog.log(Level.INFO, "[Travellers/MapResweep] dim=%d complete: %d chunk(s) processed", dim, job.total);
            }
        }
    }

    private void enqueueTileChunks(Deque<Long> pendingChunks, long tileKey) {
        int tileX = TileKey.unpackX(tileKey);
        int tileZ = TileKey.unpackZ(tileKey);
        int chunksPerTile = WorldMapDiscovery.CHUNKS_PER_TILE;
        int baseChunkX = tileX * chunksPerTile;
        int baseChunkZ = tileZ * chunksPerTile;
        for (int dz = 0; dz < chunksPerTile; dz++) {
            for (int dx = 0; dx < chunksPerTile; dx++) {
                pendingChunks.add(TileKey.pack(baseChunkX + dx, baseChunkZ + dz));
            }
        }
    }

    private void processChunk(WorldServer world, int dim, long chunkKey) {
        int chunkX = TileKey.unpackX(chunkKey);
        int chunkZ = TileKey.unpackZ(chunkKey);

        // A resweep can touch every captured chunk of the world: each one we load is released right
        // after painting, or loaded chunks pile up faster than vanilla unloads them (OOM on big worlds).
        List<Chunk> loadedByUs = new ArrayList<>(1);
        Chunk chunk = LoadedChunks.loadTemporarily(world, chunkX, chunkZ, loadedByUs);
        boolean wasAlreadyLoaded = loadedByUs.isEmpty();

        // A chunk loaded here was just queued by ChunkReader (read at the latest on its unload);
        // only one that was already loaded is painted directly, so no chunk is read twice.
        boolean paintHere = wasAlreadyLoaded || !TravellersConfig.WORLDMAP.mapPregenOnChunkLoadEnabled;
        boolean changed = paintHere && chunk != null && MapChunkPainter.paint(world, chunk);

        LoadedChunks.release(world, loadedByUs);

        if (TravellersConfig.WORLDMAP.debugMap && changed) {
            FMLLog.log(Level.INFO, "[Travellers/MapResweep] dim=%d chunk %d,%d changed", dim, chunkX, chunkZ);
        }
    }

    private static class ResweepJob {
        final Deque<Long> pendingChunks;
        final ICommandSender requester;
        final int total;
        int done = 0;
        long lastReportTick;

        ResweepJob(Deque<Long> pendingChunks, ICommandSender requester, long now) {
            this.pendingChunks = pendingChunks;
            this.requester = requester;
            this.total = pendingChunks.size();
            this.lastReportTick = now;
        }
    }
}
