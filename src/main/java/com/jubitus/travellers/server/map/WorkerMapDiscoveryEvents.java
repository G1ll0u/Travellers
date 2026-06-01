package com.jubitus.travellers.server.map;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.map.MsgMapDiscoverTile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.*;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class WorkerMapDiscoveryEvents {

    // Run every 5 ticks to amortize scans
    private static final int RUN_EVERY_TICKS = 5;

    // Per-owner cooldowns (ticks)
    private static final Map<UUID, Integer> DISCOVER_CD = new HashMap<>();
    private static final Map<UUID, Integer> GEN_CD = new HashMap<>();

    // Round-robin index per owner (so we don't always scan same worker)
    private static final Map<UUID, Integer> RR_INDEX = new HashMap<>();

    // Tune safely
    private static final int DISCOVER_EVERY_TICKS = 40; // per owner, ~2s
    private static final int GEN_EVERY_TICKS = 40;      // per owner, ~2s

    // Tile radius around a worker (0 = only the tile they are in)
    private static final int DISCOVER_RADIUS_TILES = 0;

    // Generation budgets
    private static final int MAX_WORKERS_PER_OWNER_PER_RUN = 2;
    private static final int MAX_TILES_PER_OWNER_PER_RUN = 2;
    private static final int GEN_MAX_PIXELS_PER_TILE = 128;
    private static final int MIN_FILLED_TO_SAVE = 64;

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        World world = e.world;
        if (world.isRemote) return;
        if (!(world instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) world;

        // Optional: keep it overworld-only to avoid dimension mixing in your current save format
        if (ws.provider.getDimension() != 0) return;

        long time = ws.getTotalWorldTime();
        if (time % RUN_EVERY_TICKS != 0) return;

        List<EntityTravellerWorker> loadedWorkers = ws.getEntities(EntityTravellerWorker.class, w ->
                w != null && w.isEntityAlive() && w.isHiredFollower() && w.getFollowOwnerId() != null
        );
        if (loadedWorkers.isEmpty()) return;

        // Group by owner
        Map<UUID, List<EntityTravellerWorker>> byOwner = new HashMap<>();
        for (EntityTravellerWorker w : loadedWorkers) {
            UUID owner = w.getFollowOwnerId();
            if (owner == null) continue;
            byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(w);
        }

        MapDiscoverySavedData discData = MapDiscoverySavedData.get(ws);
        MapTileImageSavedData imgData = MapTileImageSavedData.get(ws);

        MinecraftServer server = ws.getMinecraftServer();

        for (Map.Entry<UUID, List<EntityTravellerWorker>> ent : byOwner.entrySet()) {
            UUID ownerId = ent.getKey();
            List<EntityTravellerWorker> workers = ent.getValue();
            if (workers.isEmpty()) continue;

            // ---- DISCOVERY ----
            int dcd = DISCOVER_CD.getOrDefault(ownerId, 0);
            dcd = Math.max(0, dcd - RUN_EVERY_TICKS);
            if (dcd == 0) {
                boolean anyNew = false;

                int start = RR_INDEX.getOrDefault(ownerId, 0);
                int count = Math.min(MAX_WORKERS_PER_OWNER_PER_RUN, workers.size());

                Set<Long> discovered = discData.getTiles(ownerId);

                for (int i = 0; i < count; i++) {
                    EntityTravellerWorker w = workers.get((start + i) % workers.size());

                    int cx = ((int) Math.floor(w.posX)) >> 4;
                    int cz = ((int) Math.floor(w.posZ)) >> 4;
                    int tileX = floorDiv(cx, WorldMapDiscovery.CHUNKS_PER_TILE);
                    int tileZ = floorDiv(cz, WorldMapDiscovery.CHUNKS_PER_TILE);

                    for (int dz = -DISCOVER_RADIUS_TILES; dz <= DISCOVER_RADIUS_TILES; dz++) {
                        for (int dx = -DISCOVER_RADIUS_TILES; dx <= DISCOVER_RADIUS_TILES; dx++) {
                            int tx = tileX + dx;
                            int tz = tileZ + dz;
                            long key = pack(tx, tz);

                            if (discovered.add(key)) {
                                anyNew = true;

                                // Only push packet if player is currently watching the map
                                if (server != null && WorkerMapStream.isSubscribed(ownerId)) {
                                    EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(ownerId);
                                    if (p != null) {
                                        TravellerFollowNet.sendToPlayer(p, new MsgMapDiscoverTile(tx, tz));
                                    }
                                }
                            }
                        }
                    }
                }

                RR_INDEX.put(ownerId, (start + count) % workers.size());

                if (anyNew) discData.markDirty();
                dcd = DISCOVER_EVERY_TICKS;
            }
            DISCOVER_CD.put(ownerId, dcd);

            // ---- GENERATION ----
            int gcd = GEN_CD.getOrDefault(ownerId, 0);
            gcd = Math.max(0, gcd - RUN_EVERY_TICKS);
            if (gcd == 0) {
                int tilesBudget = MAX_TILES_PER_OWNER_PER_RUN;

                Set<Long> discovered = discData.getTiles(ownerId);

                // Visit some workers (round-robin) and generate the tile they are in
                int start = RR_INDEX.getOrDefault(ownerId, 0);
                int count = Math.min(MAX_WORKERS_PER_OWNER_PER_RUN, workers.size());

                Set<Long> visitedTilesThisRun = new HashSet<>();

                for (int i = 0; i < count && tilesBudget > 0; i++) {
                    EntityTravellerWorker w = workers.get((start + i) % workers.size());

                    int cx = ((int) Math.floor(w.posX)) >> 4;
                    int cz = ((int) Math.floor(w.posZ)) >> 4;
                    int tileX = floorDiv(cx, WorldMapDiscovery.CHUNKS_PER_TILE);
                    int tileZ = floorDiv(cz, WorldMapDiscovery.CHUNKS_PER_TILE);

                    long key = pack(tileX, tileZ);
                    if (!discovered.contains(key)) continue;
                    if (!visitedTilesThisRun.add(key)) continue;

                    byte[] data = imgData.get(key);
                    boolean had = (data != null && data.length == MapTileImageSavedData.TILE_BYTES);
                    if (!had) data = new byte[MapTileImageSavedData.TILE_BYTES];

                    boolean changed = MapTileGenerator.fillMissingPixels(ws, tileX, tileZ, data, GEN_MAX_PIXELS_PER_TILE);
                    int filled = countNonZero(data);

                    // Save only if we have meaningful content
                    if (filled >= MIN_FILLED_TO_SAVE && (!had || changed)) {
                        imgData.put(key, data);

                        // Do NOT spam tile packets when map is closed.
                        // Client will request visible tiles anyway.
                    }

                    tilesBudget--;
                }

                gcd = GEN_EVERY_TICKS;
            }
            GEN_CD.put(ownerId, gcd);
        }
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }

    private static int countNonZero(byte[] data) {
        int c = 0;
        for (byte b : data) if ((b & 0xFF) != 0) c++;
        return c;
    }
}