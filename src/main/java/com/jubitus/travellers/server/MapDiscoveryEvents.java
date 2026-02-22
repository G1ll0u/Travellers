package com.jubitus.travellers.server;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.server.map.MapDiscoverySavedData;
import com.jubitus.travellers.server.map.MapTileGenerator;
import com.jubitus.travellers.server.map.MapTileImageSavedData;
import com.jubitus.travellers.server.map.WorldMapDiscovery;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import com.jubitus.travellers.utils.net.message.map.MsgMapDiscoverTile;
import com.jubitus.travellers.utils.net.message.map.MsgMapTileData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class MapDiscoveryEvents {

    // Separate cooldowns (ticks)
    private static final java.util.Map<java.util.UUID, Integer> DISCOVER_CD = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> GEN_CD = new java.util.HashMap<>();

    // Tune these safely
    private static final int DISCOVER_EVERY_TICKS = 5; // 0.25s
    private static final int GEN_EVERY_TICKS = 20;     // 1.0s

    // "Fog of war" radius in TILES around the player (1 => 3x3 tiles)
    private static final int DISCOVER_RADIUS_TILES = 1;

    // Generation radius (keep small for performance)
    private static final int GEN_RADIUS_TILES = 0; // only the tile the player is in

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.player.world.isRemote) return;
        if (!(e.player instanceof EntityPlayerMP)) return;

        EntityPlayerMP p = (EntityPlayerMP) e.player;
        World world = p.world;

        java.util.UUID id = p.getUniqueID();

        tickDown(DISCOVER_CD, id);
        tickDown(GEN_CD, id);

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

            for (int dz = -DISCOVER_RADIUS_TILES; dz <= DISCOVER_RADIUS_TILES; dz++) {
                for (int dx = -DISCOVER_RADIUS_TILES; dx <= DISCOVER_RADIUS_TILES; dx++) {
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

            for (int dz = -GEN_RADIUS_TILES; dz <= GEN_RADIUS_TILES && tilesUpdated < maxTilesPerRun; dz++) {
                for (int dx = -GEN_RADIUS_TILES; dx <= GEN_RADIUS_TILES && tilesUpdated < maxTilesPerRun; dx++) {
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
    private static int countNonZero(byte[] data) {
        int c = 0;
        for (byte b : data) if ((b & 0xFF) != 0) c++;
        return c;
    }

}
