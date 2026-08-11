package com.jubitus.travellers.server;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.server.map.pyramid.TileKey;
import com.jubitus.travellers.server.map.pyramid.WorldMapDiscovery;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.map.MsgMapDiscoverTile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player fog-of-war discovery. Tile *generation and freshness* is entirely chunk-load-driven
 * ({@code MapTileChunkLoadPregen} for first paint, {@code MapLoadedChunkResampler} for ongoing
 * correctness) so it works with zero players online (bulk pregen tools) and this class no longer
 * duplicates any of that.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class MapDiscoveryEvents {

    private static final Map<UUID, Integer> DISCOVER_CD = new HashMap<>();

    private static final int DISCOVER_EVERY_TICKS = 5; // 0.25s — not exposed, always fast

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.player.world.isRemote) return;
        if (!(e.player instanceof EntityPlayerMP)) return;

        EntityPlayerMP p = (EntityPlayerMP) e.player;
        World world = p.world;

        UUID id = p.getUniqueID();
        tickDown(DISCOVER_CD, id);
        if (DISCOVER_CD.getOrDefault(id, 0) > 0) return;

        int tileRadius = viewRadiusTiles(p);
        int cx = ((int) Math.floor(p.posX)) >> 4;
        int cz = ((int) Math.floor(p.posZ)) >> 4;
        int chunksPerTile = WorldMapDiscovery.CHUNKS_PER_TILE;

        int playerTileX = TileKey.floorDiv(cx, chunksPerTile);
        int playerTileZ = TileKey.floorDiv(cz, chunksPerTile);

        com.jubitus.travellers.server.map.pyramid.MapDiscoverySavedData saved =
                com.jubitus.travellers.server.map.pyramid.MapDiscoverySavedData.get(world);
        Set<Long> discovered = saved.getTiles(id);

        boolean anyNew = false;
        for (int dz = -tileRadius; dz <= tileRadius; dz++) {
            for (int dx = -tileRadius; dx <= tileRadius; dx++) {
                int tx = playerTileX + dx;
                int tz = playerTileZ + dz;
                long key = TileKey.pack(tx, tz);

                if (discovered.add(key)) {
                    anyNew = true;
                    TravellerFollowNet.sendToPlayer(p, new MsgMapDiscoverTile(tx, tz));
                }
            }
        }

        if (anyNew) saved.markDirty();
        DISCOVER_CD.put(id, DISCOVER_EVERY_TICKS);
    }

    private static void tickDown(Map<UUID, Integer> map, UUID id) {
        int v = map.getOrDefault(id, 0);
        if (v > 0) map.put(id, v - 1);
    }

    // View distance in chunks → radius in tiles (1 tile = chunksPerTile chunks).
    private static int viewRadiusTiles(EntityPlayerMP p) {
        net.minecraft.server.MinecraftServer srv = p.getServer();
        int viewChunks = (srv != null) ? srv.getPlayerList().getViewDistance() : 8;
        return Math.max(1, viewChunks / WorldMapDiscovery.CHUNKS_PER_TILE);
    }
}
