package com.jubitus.travellers.client.gui.worldmap;

import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * Adds the standard set of map markers to a panel in the correct draw order.
 *
 * Draw order (lowest = drawn first, highest = drawn on top):
 *   regular villages → controlled villages → homes → followers → leaders → player
 *
 * This method does NOT call clearMarkers(). The caller must do that first,
 * allowing extra markers to be added before or after this call.
 */
public final class WorldMapContentPopulator {

    private WorldMapContentPopulator() {}

    /**
     * Adds standard markers (POIs + workers + player) to the panel.
     *
     * @param panel             target panel
     * @param pois              village / controlled-village / home entries from the server
     * @param workers           worker position entries from the server
     * @param player            local player (may be null — player marker skipped if null)
     * @param playerOwnerColor  owner mark color (0xRRGGBB) used for player, leaders, controlled villages
     * @param highlightedVillages  if non-null, village positions in this set use the highlighted style
     */
    public static void populate(
            GuiWorldMapPanel panel,
            List<MsgWorkerGoList.Entry> pois,
            List<MsgMapWorkersUpdate.Entry> workers,
            EntityPlayer player,
            int playerOwnerColor,
            Set<BlockPos> highlightedVillages) {

        panel.setLegendOwnerColor(playerOwnerColor);

        int myDim = Minecraft.getMinecraft().world.provider.getDimension();

        // 1 — Regular villages
        for (MsgWorkerGoList.Entry e : pois) {
            if (e.pos == null || e.kind != MsgWorkerGoList.Kind.VILLAGE) continue;
            if (highlightedVillages != null && highlightedVillages.contains(e.pos)) {
                panel.addMarker(WorldMapMarkerFactory.villageHighlighted(e.pos, e.label));
            } else {
                panel.addMarker(WorldMapMarkerFactory.village(e.pos, e.label));
            }
        }

        // 2 — Controlled villages
        for (MsgWorkerGoList.Entry e : pois) {
            if (e.pos == null || e.kind != MsgWorkerGoList.Kind.CONTROLLED_VILLAGE) continue;
            panel.addMarker(WorldMapMarkerFactory.controlledVillage(e.pos, e.label, playerOwnerColor));
        }

        // 3 — Homes
        for (MsgWorkerGoList.Entry e : pois) {
            if (e.pos == null) continue;
            if (e.kind == MsgWorkerGoList.Kind.VILLAGE || e.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE) continue;
            panel.addMarker(WorldMapMarkerFactory.home(e.pos, e.label, playerOwnerColor));
        }

        // 4 — Follower workers
        for (MsgMapWorkersUpdate.Entry e : workers) {
            if (e.pos == null || e.dim != myDim || e.isLeader) continue;
            String name = (e.name != null && !e.name.isEmpty()) ? e.name : "Worker#" + e.entityId;
            panel.addMarker(WorldMapMarkerFactory.follower(e.pos, name, e.ownerColor));
        }

        // 5 — Leader workers
        for (MsgMapWorkersUpdate.Entry e : workers) {
            if (e.pos == null || e.dim != myDim || !e.isLeader) continue;
            String name = (e.name != null && !e.name.isEmpty()) ? e.name : "Worker#" + e.entityId;
            panel.addMarker(WorldMapMarkerFactory.leader(e.pos, name, e.ownerColor));
        }

        // 6 — Player (topmost)
        if (player != null) {
            panel.addMarker(WorldMapMarkerFactory.player(
                    player.getPosition(), player.getName(), playerOwnerColor));
        }
    }

    /** Convenience overload with no highlighted villages. */
    public static void populate(
            GuiWorldMapPanel panel,
            List<MsgWorkerGoList.Entry> pois,
            List<MsgMapWorkersUpdate.Entry> workers,
            EntityPlayer player,
            int playerOwnerColor) {
        populate(panel, pois, workers, player, playerOwnerColor, null);
    }
}
