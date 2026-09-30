package com.jubitus.travellers.server.map;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.map.pyramid.MapDiscoverySavedData;
import com.jubitus.travellers.server.map.pyramid.MapPyramidStore;
import com.jubitus.travellers.server.map.pyramid.TileKey;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.map.MsgMapDiscoverTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player fog of war: reveals a disc of chunks around each overworld player whenever they enter a
 * new chunk, and sends the changed tile masks to that player.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class MapDiscoveryEvents {

    private static final int CHECK_EVERY_TICKS = 5;

    // Last (chunk, radius) a player was revealed at, so standing still costs nothing.
    private static final Map<UUID, long[]> LAST = new HashMap<>();
    private static final LongOpenHashSet CHANGED = new LongOpenHashSet();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.player.world.isRemote || !(e.player instanceof EntityPlayerMP)) return;
        if (e.player.ticksExisted % CHECK_EVERY_TICKS != 0) return;
        EntityPlayerMP p = (EntityPlayerMP) e.player;
        if (!MapPyramidStore.isMapped(p.world)) return;

        int cx = ((int) Math.floor(p.posX)) >> 4;
        int cz = ((int) Math.floor(p.posZ)) >> 4;
        int radius = TravellersConfig.WORLDMAP.mapDiscoveryRadiusChunks;
        long chunk = ChunkPos.asLong(cx, cz);
        long[] last = LAST.computeIfAbsent(p.getUniqueID(), k -> new long[]{Long.MIN_VALUE, -1});
        if (last[0] == chunk && last[1] == radius) return;
        last[0] = chunk;
        last[1] = radius;

        MapDiscoverySavedData saved = MapDiscoverySavedData.get(p.world);
        Long2ObjectOpenHashMap<long[]> masks = saved.getMasks(p.getUniqueID());
        CHANGED.clear();
        int r2 = radius * radius + radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > r2) continue;
                long key = saved.discoverChunk(masks, cx + dx, cz + dz);
                if (key != Long.MIN_VALUE) CHANGED.add(key);
            }
        }
        for (LongIterator it = CHANGED.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            TravellerFollowNet.sendToPlayer(p, new MsgMapDiscoverTile(TileKey.unpackX(key), TileKey.unpackZ(key), masks.get(key)));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        LAST.remove(e.player.getUniqueID());
    }
}
