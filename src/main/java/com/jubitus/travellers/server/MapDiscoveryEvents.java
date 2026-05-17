package com.jubitus.travellers.server;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.server.map.MapDiscoverySavedData;
import com.jubitus.travellers.server.map.MapTileGenerator;
import com.jubitus.travellers.server.map.MapTileImageSavedData;
import com.jubitus.travellers.server.map.WorldMapDiscovery;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.map.MsgMapDiscoverTile;
import com.jubitus.travellers.server.net.message.map.MsgMapTileData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class MapDiscoveryEvents {

    private static final java.util.Map<java.util.UUID, Integer> DISCOVER_CD = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> GEN_CD = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> REFRESH_CD = new java.util.HashMap<>();

    // Per-tile pixel cursor so refresh cycles through all 4096 pixels over time.
    private static final java.util.Map<Long, Integer> PIXEL_CURSOR = new java.util.HashMap<>();

    private static final int DISCOVER_EVERY_TICKS = 5;   // 0.25s
    private static final int GEN_EVERY_TICKS      = 20;  // 1.0s
    // Refresh: every 0.5s, 512 px/tile, 3x3 tiles → full tile cycle ≈ 4s.
    // Most pixels are skipped (chunk not loaded) so actual cost is minimal.
    private static final int REFRESH_EVERY_TICKS  = 10;
    private static final int REFRESH_PIXELS_PER_TILE = 512;
    // Only refresh tiles that are mostly generated (avoids wasting budget on sparse tiles)
    private static final int REFRESH_MIN_FILLED   = 512;

    // Radii are derived from server view distance at runtime — see viewRadiusTiles().

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.player.world.isRemote) return;
        if (!(e.player instanceof EntityPlayerMP)) return;

        EntityPlayerMP p = (EntityPlayerMP) e.player;
        World world = p.world;

        int tileRadius = viewRadiusTiles(p);

        java.util.UUID id = p.getUniqueID();

        tickDown(DISCOVER_CD, id);
        tickDown(GEN_CD, id);
        tickDown(REFRESH_CD, id);

        int cx = ((int) Math.floor(p.posX)) >> 4;
        int cz = ((int) Math.floor(p.posZ)) >> 4;

        int playerTileX = floorDiv(cx, WorldMapDiscovery.CHUNKS_PER_TILE);
        int playerTileZ = floorDiv(cz, WorldMapDiscovery.CHUNKS_PER_TILE);

        MapDiscoverySavedData saved = MapDiscoverySavedData.get(world);
        java.util.Set<Long> discovered = saved.getTiles(id);
        MapTileImageSavedData img = MapTileImageSavedData.get(world);

        // 1) Discovery (tile-radius)
        if (DISCOVER_CD.getOrDefault(id, 0) <= 0) {
            boolean anyNew = false;

            for (int dz = -tileRadius; dz <= tileRadius; dz++) {
                for (int dx = -tileRadius; dx <= tileRadius; dx++) {
                    int tx = playerTileX + dx;
                    int tz = playerTileZ + dz;
                    long key = pack(tx, tz);

                    if (discovered.add(key)) {
                        anyNew = true;
                        TravellerFollowNet.sendToPlayer(p, new MsgMapDiscoverTile(tx, tz));
                    }
                }
            }

            if (anyNew) saved.markDirty();
            DISCOVER_CD.put(id, DISCOVER_EVERY_TICKS);
        }

        // 2) Progressive tile generation (fills missing pixels as chunks load)
        if (GEN_CD.getOrDefault(id, 0) <= 0) {
            int tilesUpdated = 0;
            int maxTilesPerRun = 1; // keep it safe

            for (int dz = -tileRadius; dz <= tileRadius && tilesUpdated < maxTilesPerRun; dz++) {
                for (int dx = -tileRadius; dx <= tileRadius && tilesUpdated < maxTilesPerRun; dx++) {
                    int tx = playerTileX + dx;
                    int tz = playerTileZ + dz;
                    long key = pack(tx, tz);

                    if (!discovered.contains(key)) continue;

                    byte[] data = img.get(key);
                    boolean had = (data != null && data.length == MapTileImageSavedData.TILE_BYTES);

                    if (!had) {
                        data = new byte[MapTileImageSavedData.TILE_BYTES]; // all 0 = unknown
                    }

                    boolean changed = MapTileGenerator.fillMissingPixels(world, tx, tz, data);
                    int filled = countNonZero(data);

// Only persist/send if we have at least some meaningful content
// (prevents saving tons of nearly-empty tiles)
                    if (filled < 64) { // tune: 64..512
                        continue;
                    }

                    // If we created it or changed it, store & push
                    if (!had || changed) {

                        img.put(key, data);
                        TravellerFollowNet.sendToPlayer(p, new MsgMapTileData(tx, tz, data));
                        tilesUpdated++;
                    }
                }
            }

            GEN_CD.put(id, GEN_EVERY_TICKS);
        }

        // 3) Progressive tile refresh — re-samples already-generated pixels so new
        //    buildings / terrain changes eventually appear on the map.
        //    Client picks up changes through its normal 300 ms tile-request polling.
        if (REFRESH_CD.getOrDefault(id, 0) <= 0) {
            for (int dz = -tileRadius; dz <= tileRadius; dz++) {
                for (int dx = -tileRadius; dx <= tileRadius; dx++) {
                    int tx = playerTileX + dx;
                    int tz = playerTileZ + dz;
                    long key = pack(tx, tz);

                    if (!discovered.contains(key)) continue;

                    byte[] data = img.get(key);
                    if (data == null || data.length != MapTileImageSavedData.TILE_BYTES) continue;
                    if (countNonZero(data) < REFRESH_MIN_FILLED) continue;

                    int cursor = PIXEL_CURSOR.getOrDefault(key, 0);
                    int changed = MapTileGenerator.refreshPixels(world, tx, tz, data, cursor, REFRESH_PIXELS_PER_TILE);
                    PIXEL_CURSOR.put(key, (cursor + REFRESH_PIXELS_PER_TILE) % MapTileImageSavedData.TILE_BYTES);

                    if (changed > 0) {
                        img.put(key, data);
                    }
                }
            }
            REFRESH_CD.put(id, REFRESH_EVERY_TICKS);
        }
    }

    private static void tickDown(java.util.Map<java.util.UUID, Integer> map, java.util.UUID id) {
        int v = map.getOrDefault(id, 0);
        if (v > 0) map.put(id, v - 1);
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }
    // View distance in chunks → radius in tiles (1 tile = CHUNKS_PER_TILE chunks).
    private static int viewRadiusTiles(EntityPlayerMP p) {
        net.minecraft.server.MinecraftServer srv = p.getServer();
        int viewChunks = (srv != null) ? srv.getPlayerList().getViewDistance() : 8;
        return Math.max(1, viewChunks / WorldMapDiscovery.CHUNKS_PER_TILE);
    }

    private static int countNonZero(byte[] data) {
        int c = 0;
        for (byte b : data) if ((b & 0xFF) != 0) c++;
        return c;
    }

}
