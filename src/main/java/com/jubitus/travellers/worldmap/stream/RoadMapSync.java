package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.pathing.MPBIntegration;
import com.jubitus.travellers.worldmap.net.MsgMapRoads;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends the overworld's Ultreïa road network to a player when they open a map, only if it changed
 * since what that player already holds. The client paints it into its map tiles. Server thread only.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class RoadMapSync {

    private static final int MAX_PART_BYTES = 256 * 1024;

    private static final Map<UUID, Long> SENT_REVISION = new HashMap<>();
    private static WeakReference<WorldServer> cachedWorld = new WeakReference<>(null);
    private static long cachedRevision = Long.MIN_VALUE;
    private static List<List<int[]>> cachedParts = Collections.emptyList();

    private RoadMapSync() {}

    public static void onMapOpened(EntityPlayerMP player) {
        if (!MPBIntegration.hasMapRoads()) return;
        WorldServer overworld = DimensionManager.getWorld(0);
        if (overworld == null) return;
        long revision = MPBIntegration.getRoadNetworkRevision(overworld);
        if (revision < 0) return;
        Long sent = SENT_REVISION.get(player.getUniqueID());
        if (sent != null && sent == revision) return;

        if (revision != cachedRevision || overworld != cachedWorld.get()) {
            cachedParts = split(MPBIntegration.getRoadSegmentsForMap(overworld));
            cachedRevision = revision;
            cachedWorld = new WeakReference<>(overworld);
        }
        for (int i = 0; i < cachedParts.size(); i++) {
            TravellersNetwork.CHANNEL.sendTo(new MsgMapRoads(revision, i, cachedParts.size(), cachedParts.get(i)), player);
        }
        SENT_REVISION.put(player.getUniqueID(), revision);
    }

    // Always at least one part, so an emptied network still clears the client's roads.
    private static List<List<int[]>> split(List<int[]> segments) {
        List<List<int[]>> parts = new ArrayList<>();
        List<int[]> current = new ArrayList<>();
        int bytes = 0;
        for (int[] seg : segments) {
            if (seg == null || seg.length < 7) continue;
            int size = MsgMapRoads.estimateBytes(seg);
            if (bytes + size > MAX_PART_BYTES && !current.isEmpty()) {
                parts.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(seg);
            bytes += size;
        }
        parts.add(current);
        return parts;
    }

    // A fresh login holds no roads yet (the client clears its map caches on connect).
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        SENT_REVISION.remove(e.player.getUniqueID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        SENT_REVISION.remove(e.player.getUniqueID());
    }
}
