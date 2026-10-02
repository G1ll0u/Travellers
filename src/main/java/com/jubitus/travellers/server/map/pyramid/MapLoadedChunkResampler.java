package com.jubitus.travellers.server.map.pyramid;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.map.MsgMapTileData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import org.apache.logging.log4j.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps level-0 worldmap pixels correct for as long as their chunk stays loaded, for whatever
 * reason. The first paint comes from {@code ChunkReader} and changes from its changed-chunk queue;
 * this slow blind rotation is only a safety net for changes neither of them sees. Never loads a chunk
 * itself — only re-checks chunks already loaded, so it adds zero chunk-loading cost.
 *
 * Ticked from {@code TravellerChunkManager.onWorldTick}. Maintains a rotating per-dimension queue
 * of currently loaded chunks, rebuilt periodically and drained under a small per-tick budget.
 */
public class MapLoadedChunkResampler {

    public static final MapLoadedChunkResampler INSTANCE = new MapLoadedChunkResampler();

    private final Map<Integer, Deque<Long>> queues = new HashMap<>();
    private final Map<Integer, Long> lastRebuildTick = new HashMap<>();

    private MapLoadedChunkResampler() {}

    public void tick(WorldServer world, long now) {
        flushDuePendingPushes();
        if (TravellersConfig.WORLDMAP.mapSweepChunksPerTick <= 0 || !MapPyramidStore.isMapped(world)) return;
        int dim = world.provider.getDimension();

        // Rebuild only once the current pass has fully drained, never mid-pass on a timer — a
        // world with more loaded chunks than mapSweepChunksPerTick * mapSweepRebuildIntervalTicks
        // (e.g. a high render distance) used to have its queue wholesale discarded and rebuilt
        // from the front every rebuildInterval ticks, before ever reaching the tail. Those tail
        // chunks (always the same ones, since getLoadedChunks() iteration order is stable while
        // the loaded set doesn't change) were silently skipped forever — a structure spanning
        // several chunks could show only its "front" chunks updated no matter how long you waited.
        // mapSweepRebuildIntervalTicks now only matters as a minimum cadence between passes once
        // a pass has finished (it's a floor on refresh rate for a small loaded set, not a ceiling
        // that truncates a large one).
        long rebuildInterval = TravellersConfig.WORLDMAP.mapSweepRebuildIntervalTicks;
        Long lastRebuild = lastRebuildTick.get(dim);
        Deque<Long> queue = queues.get(dim);
        if (queue == null || (queue.isEmpty() && (lastRebuild == null || now - lastRebuild >= rebuildInterval))) {
            if (queue != null && lastRebuild != null && dim == 0) {
                MapFillStats.sweepPasses++;
                MapFillStats.sweepLastPassTicks = now - lastRebuild;
            }
            queue = rebuildQueue(world);
            if (dim == 0) MapFillStats.sweepLastPassChunks = queue.size();
            queues.put(dim, queue);
            lastRebuildTick.put(dim, now);
            if (TravellersConfig.WORLDMAP.debugMap) {
                FMLLog.log(Level.INFO, "[Travellers/MapSweep] dim=%d queue rebuilt: %d loaded chunk(s)",
                        dim, queue.size());
            }
        }

        int budget = TravellersConfig.WORLDMAP.mapSweepChunksPerTick;
        int processed = 0, changedCount = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < budget && !queue.isEmpty(); i++) {
            long packedChunk = queue.pollFirst();
            int chunkX = TileKey.unpackX(packedChunk);
            int chunkZ = TileKey.unpackZ(packedChunk);
            if (processChunk(world, chunkX, chunkZ)) changedCount++;
            processed++;
        }
        MapFillStats.sweepNanos += System.nanoTime() - t0;
        MapFillStats.sweepProcessed += processed;
        MapFillStats.sweepChanged += changedCount;
        if (TravellersConfig.WORLDMAP.debugMap && processed > 0) {
            FMLLog.log(Level.INFO, "[Travellers/MapSweep] dim=%d tick: %d chunk(s) processed, %d changed, %d remaining in queue (this is the ALWAYS-ON background sweep, not the 'map resweep' admin command)",
                    dim, processed, changedCount, queue.size());
        }
    }

    private Deque<Long> rebuildQueue(WorldServer world) {
        Deque<Long> queue = new ArrayDeque<>();
        for (Chunk chunk : world.getChunkProvider().getLoadedChunks()) {
            queue.addLast(TileKey.pack(chunk.x, chunk.z));
        }
        return queue;
    }

    // True if the chunk is still loaded and its pixels changed.
    private boolean processChunk(WorldServer world, int chunkX, int chunkZ) {
        Chunk chunk = com.jubitus.travellers.ground.chunk.LoadedChunks.peek(world, chunkX, chunkZ);
        return chunk != null && MapChunkPainter.paint(world, chunk);
    }

    // (dim<<40 | level<<32 | packed tileX,tileZ isn't safe - tileX/tileZ already use the full 64
    // bits via TileKey.pack) - keyed by a small composite object instead.
    private static final Map<PushKey, Long> lastPushMs = new HashMap<>();

    /**
     * Throttled-away pushes that still need to go out once their interval elapses, latest bytes
     * only (older pending data for the same key is simply overwritten). Without this, two chunks
     * that resample into the *same* tile within one throttle window (trivially true within a
     * single tile, since it spans 16x16 chunks) drop the later push outright — and since the
     * level-0 store is already correct at that point, no future {@code changed} pixel diff ever
     * fires to trigger a resend, permanently freezing that tile on stale client data. This was the
     * root cause of a structure built across a chunk boundary showing correctly in one chunk but
     * not the other, with no amount of waiting fixing it.
     */
    private static final Map<PushKey, PendingPush> pendingPushes = new HashMap<>();

    /**
     * Pushes fresh tile bytes straight to nearby players — no separate "invalidate, then let the
     * client re-request" round trip. That two-step used to leave a visible window where the client
     * had evicted its cached tile but not yet received the replacement, during which the renderer's
     * coarser/finer fallback kicked in — visible as a flash of a different resolution/contrast. Also
     * used by {@link PyramidDirtyPropagator} for levels above 0.
     *
     * Throttled per (dim, level, tile): a coarse level's tile can cover a huge footprint (level 6 is
     * up to 16384 blocks/tile), so virtually any change anywhere within it re-triggers a push - most
     * of those are imperceptible at that zoom and don't need real-time delivery. The minimum interval
     * scales with the tile's footprint (`mapPushMinIntervalMs * 2^level`), so level 0 stays responsive
     * while level 6 pushes at most a few times a minute. A throttled push is never dropped outright —
     * it's coalesced into {@link #pendingPushes} and flushed by {@link #flushDuePendingPushes()} once
     * the interval elapses, so the client always eventually receives the latest bytes.
     */
    public static void pushTileUpdate(int dim, int level, int tileX, int tileZ, byte[] rawData) {
        PushKey key = new PushKey(dim, level, tileX, tileZ);
        long now = System.currentTimeMillis();
        long minIntervalMs = TravellersConfig.WORLDMAP.mapPushMinIntervalMs * (long) (1 << level);
        Long last = lastPushMs.get(key);
        if (last != null && now - last < minIntervalMs) {
            pendingPushes.put(key, new PendingPush(rawData, last + minIntervalMs));
            MapFillStats.pushCoalesced++;
            return;
        }
        pendingPushes.remove(key);
        lastPushMs.put(key, now);
        sendNow(dim, level, tileX, tileZ, rawData);
    }

    /** Sends every coalesced pending push whose throttle window has elapsed. */
    private void flushDuePendingPushes() {
        if (pendingPushes.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<PushKey, PendingPush>> it = pendingPushes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PushKey, PendingPush> e = it.next();
            if (now < e.getValue().dueAtMs) continue;
            PushKey key = e.getKey();
            lastPushMs.put(key, now);
            sendNow(key.dim, key.level, key.tileX, key.tileZ, e.getValue().rawData);
            it.remove();
        }
    }

    private static void sendNow(int dim, int level, int tileX, int tileZ, byte[] rawData) {
        int tileBlocks = MapTileGenerator.TILE_PX * (1 << level);
        double centerX = tileX * (double) tileBlocks + tileBlocks / 2.0;
        double centerZ = tileZ * (double) tileBlocks + tileBlocks / 2.0;
        double radius = TravellersConfig.WORLDMAP.mapInvalidateNotifyRadiusBlocks * (double) (1 << level);

        if (level == 0) MapFillStats.pushSentLevel0++;
        else MapFillStats.pushSentUpper++;
        long t0 = System.nanoTime();
        byte[] compressed = TileCompression.deflate(rawData);
        MapFillStats.pushDeflateNanos += System.nanoTime() - t0;
        TravellerFollowNet.CHANNEL.sendToAllAround(
                new MsgMapTileData(level, tileX, tileZ, compressed),
                new NetworkRegistry.TargetPoint(dim, centerX, 64.0, centerZ, radius));

        if (TravellersConfig.WORLDMAP.debugMap) {
            FMLLog.log(Level.INFO, "[Travellers/MapNet] dim=%d push sent: level=%d tile=%d,%d radius=%.0f",
                    dim, level, tileX, tileZ, radius);
        }
    }

    private static final class PendingPush {
        final byte[] rawData;
        final long dueAtMs;
        PendingPush(byte[] rawData, long dueAtMs) {
            this.rawData = rawData;
            this.dueAtMs = dueAtMs;
        }
    }

    private static final class PushKey {
        final int dim, level, tileX, tileZ;
        PushKey(int dim, int level, int tileX, int tileZ) {
            this.dim = dim; this.level = level; this.tileX = tileX; this.tileZ = tileZ;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PushKey)) return false;
            PushKey k = (PushKey) o;
            return dim == k.dim && level == k.level && tileX == k.tileX && tileZ == k.tileZ;
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(dim, level, tileX, tileZ);
        }
    }
}
