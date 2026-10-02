package com.jubitus.travellers.ground.chunk;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.map.pyramid.MapChunkPainter;
import com.jubitus.travellers.server.map.pyramid.MapPyramidStore;
import com.jubitus.travellers.pathing.terrain.TerrainWorldState;
import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The one reader of chunks. Each newly loaded chunk is read once, while it is in memory, and handed
 * to every consumer (travel terrain grid, worldmap level 0, campsite discovery). A chunk changed
 * afterwards is re-read for the consumers that care: decoration re-reads terrain and map, a surface
 * block change repaints the map. Both queues drain under a per-tick budget and a chunk still queued
 * when it unloads is read right then, so nothing is lost. Never loads a chunk. Server thread only.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ChunkReader {

    private static final byte CHANGE_MAP = 1;
    private static final byte CHANGE_TERRAIN = 2;

    private static final Map<Integer, LongLinkedOpenHashSet> PENDING = new HashMap<>();
    private static final Map<Integer, Long2ByteLinkedOpenHashMap> CHANGED = new HashMap<>();

    private static long enqueued;
    private static long read;
    private static long readAtUnload;
    private static long lost;
    private static int queuePeak;
    private static long readNanos;

    private static long changeMarked;
    private static long changeRead;
    private static long changeReadAtUnload;
    private static long changeLost;
    private static long mapRepaintChanged;
    private static int changeQueuePeak;

    private ChunkReader() {
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load e) {
        World world = e.getWorld();
        if (world instanceof WorldServer && MapPyramidStore.isMapped(world)) {
            world.addEventListener(new SurfaceListener());
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload e) {
        if (e.getWorld().isRemote) return;
        int dim = e.getWorld().provider.getDimension();
        PENDING.remove(dim);
        CHANGED.remove(dim);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load e) {
        World world = e.getWorld();
        if (world.isRemote || !(world instanceof WorldServer) || !anyConsumer()) return;
        Chunk chunk = e.getChunk();
        LongLinkedOpenHashSet q = PENDING.computeIfAbsent(world.provider.getDimension(), d -> new LongLinkedOpenHashSet());
        if (q.add(ChunkPos.asLong(chunk.x, chunk.z))) {
            enqueued++;
            if (q.size() > queuePeak) queuePeak = q.size();
        }
    }

    // Blocks are still intact while this fires (vanilla saves the chunk right after).
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload e) {
        World world = e.getWorld();
        if (world.isRemote || !(world instanceof WorldServer)) return;
        int dim = world.provider.getDimension();
        Chunk chunk = e.getChunk();
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        Long2ByteLinkedOpenHashMap changed = CHANGED.get(dim);
        byte flags = changed != null ? changed.remove(key) : 0;
        LongLinkedOpenHashSet q = PENDING.get(dim);
        if (q != null && q.remove(key)) {
            readNow((WorldServer) world, chunk);
            readAtUnload++;
        } else if (flags != 0) {
            readChanges((WorldServer) world, chunk, flags);
            changeReadAtUnload++;
        }
    }

    // Decorating chunk (x, z) writes into the 2x2 chunks starting at it (vanilla's +8 offset).
    @SubscribeEvent
    public static void onPopulated(PopulateChunkEvent.Post e) {
        World world = e.getWorld();
        if (world.isRemote || !(world instanceof WorldServer)) return;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                markChanged((WorldServer) world, e.getChunkX() + dx, e.getChunkZ() + dz, (byte) (CHANGE_MAP | CHANGE_TERRAIN));
            }
        }
    }

    private static void markChanged(WorldServer world, int chunkX, int chunkZ, byte flags) {
        if (!mapsChanges(world)) flags &= ~CHANGE_MAP;
        if ((flags & CHANGE_TERRAIN) != 0 && TerrainWorldState.get(world) == null) flags &= ~CHANGE_TERRAIN;
        if (flags == 0) return;
        int dim = world.provider.getDimension();
        long key = ChunkPos.asLong(chunkX, chunkZ);
        LongLinkedOpenHashSet pending = PENDING.get(dim);
        if (pending != null && pending.contains(key)) return; // its first read is still to come
        Long2ByteLinkedOpenHashMap q = CHANGED.computeIfAbsent(dim, d -> new Long2ByteLinkedOpenHashMap());
        byte old = q.get(key);
        if ((old | flags) == old) return;
        q.put(key, (byte) (old | flags));
        if (old == 0) {
            changeMarked++;
            if (q.size() > changeQueuePeak) changeQueuePeak = q.size();
        }
    }

    public static void tick(WorldServer world) {
        int dim = world.provider.getDimension();
        LongLinkedOpenHashSet q = PENDING.get(dim);
        if (q != null && !q.isEmpty()) {
            int budget = isServerEmpty()
                    ? TravellersConfig.CHUNK_READER.chunksPerTickEmptyServer
                    : TravellersConfig.CHUNK_READER.chunksPerTick;
            for (int i = 0; i < budget && !q.isEmpty(); i++) {
                long key = q.removeFirstLong();
                Chunk chunk = LoadedChunks.peek(world, key);
                if (chunk == null) {
                    lost++; // unloaded without its unload event reaching us; should stay at zero
                    continue;
                }
                readNow(world, chunk);
            }
        }
        Long2ByteLinkedOpenHashMap changed = CHANGED.get(dim);
        if (changed != null && !changed.isEmpty()) {
            int budget = TravellersConfig.CHUNK_READER.changedChunksPerTick;
            for (int i = 0; i < budget && !changed.isEmpty(); i++) {
                long key = changed.firstLongKey();
                byte flags = changed.removeFirstByte();
                Chunk chunk = LoadedChunks.peek(world, key);
                if (chunk == null) {
                    changeLost++;
                    continue;
                }
                readChanges(world, chunk, flags);
            }
        }
    }

    private static void readNow(WorldServer world, Chunk chunk) {
        long t0 = System.nanoTime();
        TerrainWorldState terrain = TerrainWorldState.get(world);
        if (terrain != null) terrain.acceptChunk(world, chunk);
        if (TravellersConfig.WORLDMAP.mapPregenOnChunkLoadEnabled && MapPyramidStore.isMapped(world)) {
            MapChunkPainter.paint(world, chunk);
        }
        if (TravellersConfig.CAMP_EVENTS.enabled) {
            com.jubitus.travellers.camp.CampsiteManager.get(world).onChunkRead(world, chunk.x, chunk.z);
        }
        readNanos += System.nanoTime() - t0;
        read++;
    }

    private static void readChanges(WorldServer world, Chunk chunk, byte flags) {
        long t0 = System.nanoTime();
        if ((flags & CHANGE_TERRAIN) != 0) {
            TerrainWorldState terrain = TerrainWorldState.get(world);
            if (terrain != null) terrain.acceptChunk(world, chunk);
        }
        if ((flags & CHANGE_MAP) != 0 && mapsChanges(world) && MapChunkPainter.paint(world, chunk)) {
            mapRepaintChanged++;
        }
        readNanos += System.nanoTime() - t0;
        changeRead++;
    }

    private static boolean anyConsumer() {
        return TravellersConfig.ROUTING.enablePlannedRoutes || TravellersConfig.WORLDMAP.mapPregenOnChunkLoadEnabled
                || TravellersConfig.CAMP_EVENTS.enabled;
    }

    private static boolean mapsChanges(World world) {
        return TravellersConfig.WORLDMAP.mapPregenOnChunkLoadEnabled && MapPyramidStore.isMapped(world);
    }

    private static boolean isServerEmpty() {
        net.minecraft.server.MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
        return srv != null && srv.getPlayerList().getCurrentPlayerCount() == 0;
    }

    public static int queuedCount() {
        int n = 0;
        for (LongLinkedOpenHashSet q : PENDING.values()) n += q.size();
        return n;
    }

    public static int changedQueuedCount() {
        int n = 0;
        for (Long2ByteLinkedOpenHashMap q : CHANGED.values()) n += q.size();
        return n;
    }

    public static int queuePeak() { return queuePeak; }
    public static long enqueuedCount() { return enqueued; }
    public static long readCount() { return read; }
    public static long readAtUnloadCount() { return readAtUnload; }
    public static long lostCount() { return lost; }
    public static long readNanos() { return readNanos; }

    public static String changeStats() {
        return String.format("changed chunks: queued now %d (peak %d)  marked %d  re-read %d (at unload %d)  LOST %d  map really changed %d",
                changedQueuedCount(), changeQueuePeak, changeMarked, changeRead, changeReadAtUnload, changeLost, mapRepaintChanged);
    }

    public static void resetCounters() {
        enqueued = read = readAtUnload = lost = readNanos = 0;
        changeMarked = changeRead = changeReadAtUnload = changeLost = mapRepaintChanged = 0;
        queuePeak = changeQueuePeak = 0;
    }

    // Surface block changes (players, explosions, Millénaire building) only matter to the worldmap.
    private static final class SurfaceListener implements IWorldEventListener {

        @Override
        public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
            if (oldState == newState) return;
            // Only the top block (and the height itself) shows on the map; the heightmap is already updated here.
            if (pos.getY() < world.getHeight(pos.getX(), pos.getZ()) - 1) return;
            markChanged((WorldServer) world, pos.getX() >> 4, pos.getZ() >> 4, CHANGE_MAP);
        }

        @Override public void notifyLightSet(BlockPos pos) {}
        @Override public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}
        @Override public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent soundIn, SoundCategory category,
                                                       double x, double y, double z, float volume, float pitch) {}
        @Override public void playRecord(SoundEvent soundIn, BlockPos pos) {}
        @Override public void spawnParticle(int particleID, boolean ignoreRange, double xCoord, double yCoord, double zCoord,
                                            double xSpeed, double ySpeed, double zSpeed, int... parameters) {}
        @Override public void spawnParticle(int id, boolean ignoreRange, boolean minimiseParticleLevel, double x, double y, double z,
                                            double xSpeed, double ySpeed, double zSpeed, int... parameters) {}
        @Override public void onEntityAdded(Entity entityIn) {}
        @Override public void onEntityRemoved(Entity entityIn) {}
        @Override public void broadcastSound(int soundID, BlockPos pos, int data) {}
        @Override public void playEvent(EntityPlayer player, int type, BlockPos blockPosIn, int data) {}
        @Override public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {}
    }
}
