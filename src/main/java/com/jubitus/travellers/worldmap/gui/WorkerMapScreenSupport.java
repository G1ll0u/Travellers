package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.worldmap.net.MsgRequestMapDiscovery;
import com.jubitus.travellers.worldmap.net.MsgSubscribeWorkerMap;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What every screen showing the player's workers on a map shares: the live worker feed
 * subscription, the received places / workers / battles / foreign units, and the standard markers.
 */
@SideOnly(Side.CLIENT)
public final class WorkerMapScreenSupport {

    public final List<MsgWorkerGoList.Entry> places = new ArrayList<>();
    /** Workers in the player's current dimension only. */
    public final List<MsgMapWorkersUpdate.Entry> workers = new ArrayList<>();
    public final List<MsgMapBattleUpdate.Entry> battles = new ArrayList<>();
    public final List<MsgMapForeignUnitsUpdate.Entry> foreignUnits = new ArrayList<>();

    /** Call from initGui: asks for the fog of war and starts the live worker feed. */
    public void subscribe() {
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
    }

    /** Call from onGuiClosed. */
    public void unsubscribe() {
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    public void setPlaces(@Nullable List<MsgWorkerGoList.Entry> entries) {
        places.clear();
        if (entries != null) places.addAll(entries);
    }

    public void setWorkers(@Nullable List<MsgMapWorkersUpdate.Entry> entries) {
        workers.clear();
        if (entries == null) return;
        int myDim = currentDimension();
        for (MsgMapWorkersUpdate.Entry e : entries) {
            if (e.dim == myDim) workers.add(e);
        }
    }

    public void setBattles(@Nullable List<MsgMapBattleUpdate.Entry> entries) {
        battles.clear();
        if (entries != null) battles.addAll(entries);
    }

    public void setForeignUnits(@Nullable List<MsgMapForeignUnitsUpdate.Entry> entries) {
        foreignUnits.clear();
        if (entries != null) foreignUnits.addAll(entries);
    }

    /** Clears the map, then adds places, workers, the player, foreign units and battles. */
    public void populate(WorldMapController map, EntityPlayer player, int ownerColor,
                         @Nullable Set<BlockPos> highlightedVillages) {
        GuiWorldMapPanel panel = map.panel;
        panel.clearMarkers();
        WorldMapContentPopulator.populate(panel, places, workers, player, ownerColor, highlightedVillages);
        int myDim = currentDimension();
        WorldMapContentPopulator.populateForeignUnits(panel, foreignUnits, myDim);
        for (MsgMapBattleUpdate.Entry e : battles) {
            if (e.dim == myDim) panel.addMarker(WorldMapMarkerFactory.battle(e.pos, e.label));
        }
    }

    @Nullable
    public MsgMapWorkersUpdate.Entry workerByUuid(@Nullable java.util.UUID uuid) {
        if (uuid == null) return null;
        for (MsgMapWorkersUpdate.Entry e : workers) {
            if (uuid.equals(e.uuid) && e.pos != null) return e;
        }
        return null;
    }

    private static int currentDimension() {
        return Minecraft.getMinecraft().world.provider.getDimension();
    }
}
