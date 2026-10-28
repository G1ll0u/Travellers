package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * Adds the standard set of map markers to a panel in the correct draw order.
 *
 * Draw order is {@link GuiWorldMapPanel.Marker.MarkerType}'s declaration order, not the order markers are added.
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
     */
    public static void populate(
            GuiWorldMapPanel panel,
            List<MsgWorkerGoList.Entry> pois,
            List<MsgMapWorkersUpdate.Entry> workers,
            EntityPlayer player,
            int playerOwnerColor,
            @javax.annotation.Nullable WorkerMarkerSmoother smoother,
            DutyRing dutyRing) {

        panel.setLegendOwnerColor(playerOwnerColor);

        int myDim = Minecraft.getMinecraft().world.provider.getDimension();

        // 1 — Regular villages
        for (MsgWorkerGoList.Entry e : pois) {
            if (e.pos == null || e.kind != MsgWorkerGoList.Kind.VILLAGE) continue;
            panel.addMarker(WorldMapMarkerFactory.village(e.pos, e.label));
        }

        // 2 — Controlled villages
        for (MsgWorkerGoList.Entry e : pois) {
            if (e.pos == null || e.kind != MsgWorkerGoList.Kind.CONTROLLED_VILLAGE) continue;
            panel.addMarker(WorldMapMarkerFactory.controlledVillage(e.pos, e.label, playerOwnerColor));
        }

        // 3 — Homes (Barracks) and Stables — same owner tint, distinct icon
        for (MsgWorkerGoList.Entry e : pois) {
            if (e.pos == null) continue;
            if (e.kind == MsgWorkerGoList.Kind.VILLAGE || e.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE) continue;
            if (e.kind == MsgWorkerGoList.Kind.STABLE) {
                panel.addMarker(WorldMapMarkerFactory.stable(e.pos, e.label, playerOwnerColor));
            } else {
                panel.addMarker(WorldMapMarkerFactory.home(e.pos, e.label, playerOwnerColor));
            }
        }

        // 4 — Follower workers, or the role marker of an on-duty worker
        for (MsgMapWorkersUpdate.Entry e : workers) {
            if (e.pos == null || e.dim != myDim || e.isLeader) continue;
            if (isOnDuty(e) && dutyRing.covers(e.uuid)) {
                float[] at = dutyRing.position(e.uuid);
                panel.addMarker(WorldMapMarkerFactory.dutyWorker(e.pos, workerLabel(e), e.ownerColor, e.role)
                        .at(at[0], at[1]).forUnit(e.uuid));
            } else if (isOnDuty(e)) {
                panel.addMarker(smoothed(WorldMapMarkerFactory.dutyWorker(e.pos, workerLabel(e), e.ownerColor, e.role), e, smoother));
            } else {
                panel.addMarker(smoothed(WorldMapMarkerFactory.follower(e.pos, workerLabel(e), e.ownerColor), e, smoother));
            }
        }

        // 5 — Leader workers
        for (MsgMapWorkersUpdate.Entry e : workers) {
            if (e.pos == null || e.dim != myDim || !e.isLeader) continue;
            panel.addMarker(smoothed(WorldMapMarkerFactory.leader(e.pos, workerLabel(e), e.ownerColor), e, smoother));
        }

        // 6 — Player (topmost)
        if (player != null) {
            panel.addMarker(playerMarker(player, playerOwnerColor));
        }
    }

    /** The player's marker, drawn at the exact interpolated position (not the block) so it glides. */
    public static GuiWorldMapPanel.Marker playerMarker(EntityPlayer player, int ownerColor) {
        return WorldMapMarkerFactory.player(player.getPosition(), player.getName(), ownerColor, player.rotationYaw)
                .at(playerDrawX(player), playerDrawZ(player));
    }

    public static float playerDrawX(EntityPlayer player) {
        float pt = Minecraft.getMinecraft().getRenderPartialTicks();
        return (float) (player.lastTickPosX + (player.posX - player.lastTickPosX) * pt);
    }

    public static float playerDrawZ(EntityPlayer player) {
        float pt = Minecraft.getMinecraft().getRenderPartialTicks();
        return (float) (player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * pt);
    }

    private static GuiWorldMapPanel.Marker smoothed(GuiWorldMapPanel.Marker m, MsgMapWorkersUpdate.Entry e,
                                                    @javax.annotation.Nullable WorkerMarkerSmoother smoother) {
        return smoothed(m, e.uuid, e.pos, smoother);
    }

    static GuiWorldMapPanel.Marker smoothed(GuiWorldMapPanel.Marker m, @javax.annotation.Nullable java.util.UUID id,
                                            BlockPos pos, @javax.annotation.Nullable WorkerMarkerSmoother smoother) {
        if (id == null) return m;
        if (smoother == null) return m.forUnit(id);
        return m.at(smoother.x(id, pos.getX()), smoother.z(id, pos.getZ())).forUnit(id);
    }

    /** An on-duty worker (e.g. a palfrenier) takes no player order: drawn with its role marker, never selectable. */
    public static boolean isOnDuty(MsgMapWorkersUpdate.Entry e) {
        return e.role != null && !e.role.isEmpty();
    }

    /** Hover label: name, current activity, who it follows, live HP (red heart), battle flag. */
    public static String workerLabel(MsgMapWorkersUpdate.Entry e) {
        StringBuilder sb = new StringBuilder(
                (e.name != null && !e.name.isEmpty()) ? e.name
                        : I18n.format("travellers.map.worker.unnamed", e.entityId));
        if (e.statusKey != null && !e.statusKey.isEmpty()) {
            sb.append('\n').append("§7").append(I18n.format(e.statusKey));
        }
        String followLine = followLineFor(e);
        if (followLine != null) sb.append('\n').append("§7").append(followLine);
        if (e.health >= 0f && e.maxHealth > 0f) {
            sb.append('\n').append(I18n.format("travellers.map.worker.hp",
                    Math.round(e.health), Math.round(e.maxHealth)));
        }
        if (e.inBattle) sb.append('\n').append("§c").append(I18n.format("travellers.map.worker.in_battle"));
        return sb.toString();
    }

    /** "Following: You" / "Following: <leader>", or null when the worker follows no one (a leader/anchored). */
    private static String followLineFor(MsgMapWorkersUpdate.Entry e) {
        if (e.followsOwner) {
            return I18n.format("travellers.map.worker.following", I18n.format("travellers.worker.formation.you"));
        }
        if (e.followName != null && !e.followName.isEmpty()) {
            return I18n.format("travellers.map.worker.following", e.followName);
        }
        return null;
    }

    /**
     * Adds foreign (not-the-player's-own) traveller/raider/worker markers discovered via vision.
     * Does not clear existing markers — call alongside {@link #populate}.
     */
    public static void populateForeignUnits(
            GuiWorldMapPanel panel,
            java.util.List<com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate.Entry> entries,
            int myDim,
            @javax.annotation.Nullable WorkerMarkerSmoother smoother) {
        for (com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate.Entry e : entries) {
            if (e.pos == null || e.dim != myDim) continue;
            String label = foreignUnitLabel(e);
            GuiWorldMapPanel.Marker m;
            switch (e.entityClass) {
                case "RAIDER":
                    m = WorldMapMarkerFactory.populationRaider(e.pos, e.virtual, label);
                    break;
                case "WORKER":
                    m = e.hired ? WorldMapMarkerFactory.foreignHiredWorker(e.pos, e.virtual, e.ownerColor, label)
                            : WorldMapMarkerFactory.populationWorker(e.pos, e.virtual, label);
                    break;
                default:
                    m = WorldMapMarkerFactory.populationTraveller(e.pos, e.virtual, label);
                    break;
            }
            panel.addMarker(smoothed(m, e.uuid, e.pos, smoother));
        }
    }

    /** Hover label: name, live HP line when revealed, battle flag. */
    private static String foreignUnitLabel(com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate.Entry e) {
        StringBuilder sb = new StringBuilder((e.name != null && !e.name.isEmpty()) ? e.name
                : I18n.format("travellers.map.worker.unknown"));
        if (e.health >= 0f && e.maxHealth > 0f) {
            sb.append('\n').append(I18n.format("travellers.map.worker.hp",
                    Math.round(e.health), Math.round(e.maxHealth)));
        }
        if (e.inBattle) sb.append('\n').append("§c").append(I18n.format("travellers.map.worker.in_battle"));
        return sb.toString();
    }
}
