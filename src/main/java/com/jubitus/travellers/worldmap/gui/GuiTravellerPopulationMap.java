package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.worldmap.gui.receiver.IBattleMapReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.ICampMapReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IPopulationMapReceiver;
import com.jubitus.travellers.worldmap.net.MsgMapCampUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate;
import com.jubitus.travellers.worldmap.net.MsgSubscribeCampMap;
import com.jubitus.travellers.worldmap.net.MsgSubscribePopulationMap;
import com.jubitus.travellers.debug.client.ClientItineraryDebugOverlay;
import com.jubitus.travellers.debug.client.ClientLoadedChunksOverlay;
import com.jubitus.travellers.debug.client.ClientTerrainDebugOverlay;
import com.jubitus.travellers.debug.net.MsgLoadedChunksRequest;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worldmap.net.MsgRequestMapDiscovery;
import com.jubitus.travellers.debug.net.MsgItineraryDebugRequest;
import com.jubitus.travellers.debug.net.MsgTerrainDebugRequest;
import com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Population overview: a trimmed variant of GuiPlayerWorldMap showing every traveller, raider and
 * worker in the world (loaded and virtual) instead of the player's own hired workers/POIs.
 */
@SideOnly(Side.CLIENT)
public class GuiTravellerPopulationMap extends GuiScreen
        implements IPopulationMapReceiver, ICampMapReceiver, IBattleMapReceiver, ITravellersGui {

    private GuiButton closeButton;
    private WorldMapNavButtons navButtons;
    private GuiButton teleportButton;
    private GuiButton spawnTravellerButton, spawnWorkerButton, spawnRaiderButton;
    private GuiButton terrainButton;
    private GuiButton routeButton;
    private long lastRouteReqMs;
    private GuiButton loadedChunksButton;
    private boolean highlightLoadedChunks;
    private long lastLoadedChunksReqMs;

    /** null = overlay off; otherwise the scanned-terrain map currently shown instead of the worldmap. */
    private TerrainProfileImageExport.Mode terrainMode;
    private long lastTerrainReqMs;

    private enum ClickMode { NONE, TELEPORT, SPAWN_TRAVELLER, SPAWN_WORKER, SPAWN_RAIDER, ROUTE }
    private ClickMode clickMode = ClickMode.NONE;

    private final EntityPlayer player;

    private WorldMapController mapController;
    private int mapX, mapY, mapW, mapH;

    private final List<MsgMapPopulationUpdate.Entry> populationEntries = new ArrayList<>();
    private final List<MsgMapCampUpdate.Entry> campEntries = new ArrayList<>();
    private final List<com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate.Entry> battleEntries = new ArrayList<>();

    public GuiTravellerPopulationMap(EntityPlayer player) {
        this.player = player;
    }

    @Override
    public void initGui() {
        super.initGui();

        mapW = Math.min(360, this.width - 20);
        mapH = Math.min(240, this.height - 119);
        mapX = (this.width - mapW) / 2;
        mapY = (this.height - mapH) / 2 - 10;

        this.buttonList.clear();

        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_population_map", player.getPosition());
        mapController.panel.setSelectedPos(null);

        navButtons = new WorldMapNavButtons(mapController, buttonList, 2, player::getPosition);
        int zTop = mapY + mapH + 6;
        closeButton    = this.addButton(new GuiTravellersButton(9, mapX + mapW - 122, zTop, 58, 20,
                I18n.format("travellers.gui.close")));
        teleportButton = this.addButton(new GuiTravellersButton(10, mapX + mapW - 186, zTop, 58, 20,
                I18n.format("travellers.map.teleport", "Teleport")));
        terrainButton = this.addButton(new GuiTravellersButton(20, mapX + 50, zTop, Math.max(90, mapW - 250), 20,
                terrainLabel()));

        mapController.panel.debugBackgroundOverlay = this::drawTerrainOverlay;

        int zTop2 = zTop + 24;
        int sbw = (mapW - 12) / 4;
        spawnTravellerButton = this.addButton(new GuiTravellersButton(11, mapX, zTop2, sbw, 20,
                I18n.format("travellers.map.spawn_traveller", "Spawn traveller")));
        spawnRaiderButton = this.addButton(new GuiTravellersButton(13, mapX + sbw + 4, zTop2, sbw, 20,
                I18n.format("travellers.map.spawn_raider", "Spawn raider")));
        spawnWorkerButton = this.addButton(new GuiTravellersButton(12, mapX + 2 * (sbw + 4), zTop2, sbw, 20,
                I18n.format("travellers.map.spawn_worker", "Spawn worker")));
        routeButton = this.addButton(new GuiTravellersButton(14, mapX + 3 * (sbw + 4), zTop2, sbw, 20, routeLabel()));

        loadedChunksButton = this.addButton(new GuiTravellersButton(15, mapX, zTop2 + 24, mapW, 20, loadedChunksLabel()));

        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribePopulationMap(true));
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribeCampMap(true));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribePopulationMap(false));
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribeCampMap(false));
        if (mapController != null && mapController.panel != null) {
            mapController.panel.debugBackgroundOverlay = null;
        }
        ClientTerrainDebugOverlay.clear();
        ClientLoadedChunksOverlay.clear();
        ClientItineraryDebugOverlay.clear();
    }

    private String routeLabel() {
        if (clickMode == ClickMode.ROUTE) return "Click unit...";
        return ClientItineraryDebugOverlay.isActive() ? "Route: hide" : "Route";
    }

    /** Re-asks for the selected unit's itinerary so the drawing follows it live. */
    private void refreshRoute() {
        java.util.UUID id = ClientItineraryDebugOverlay.selected();
        if (id == null) return;
        lastRouteReqMs = Minecraft.getSystemTime();
        TravellersNetwork.CHANNEL.sendToServer(new MsgItineraryDebugRequest(0, 0, 0, id));
    }

    private String loadedChunksLabel() {
        return "Highlight loaded chunks: " + (highlightLoadedChunks ? "ON" : "OFF");
    }

    /** Asks the server which chunks of the current viewport (plus a small margin) are loaded. */
    private void requestLoadedChunks() {
        if (!highlightLoadedChunks) return;
        lastLoadedChunksReqMs = Minecraft.getSystemTime();
        float bpp = mapController.panel.getZoomBlocksPerPixel();
        int cx = Math.round(mapController.panel.getCenterXf());
        int cz = Math.round(mapController.panel.getCenterZf());
        int halfWBlocks = Math.round(mapW / 2f * bpp) + 32;
        int halfHBlocks = Math.round(mapH / 2f * bpp) + 32;

        int minX = Math.floorDiv(cx - halfWBlocks, 16);
        int minZ = Math.floorDiv(cz - halfHBlocks, 16);
        int w = Math.floorDiv(cx + halfWBlocks, 16) - minX + 1;
        int h = Math.floorDiv(cz + halfHBlocks, 16) - minZ + 1;

        // Zoomed way out: a centred patch rather than nothing.
        int cap = MsgLoadedChunksRequest.MAX_CHUNKS;
        if (w > cap) {
            minX = Math.floorDiv(cx, 16) - cap / 2;
            w = cap;
        }
        if (h > cap) {
            minZ = Math.floorDiv(cz, 16) - cap / 2;
            h = cap;
        }
        TravellersNetwork.CHANNEL.sendToServer(new MsgLoadedChunksRequest(minX, minZ, w, h));
    }

    private String terrainLabel() {
        return "Terrain: " + (terrainMode == null ? "off" : terrainMode.name().toLowerCase());
    }

    private void cycleTerrainMode() {
        TerrainProfileImageExport.Mode[] all = TerrainProfileImageExport.Mode.values();
        if (terrainMode == null) {
            terrainMode = all[0];
        } else if (terrainMode.ordinal() + 1 >= all.length) {
            terrainMode = null;
        } else {
            terrainMode = all[terrainMode.ordinal() + 1];
        }
        terrainButton.displayString = terrainLabel();
        if (terrainMode == null) ClientTerrainDebugOverlay.clear();
        else requestTerrain();
    }

    /** Asks the server for the terrain cells covering the current viewport (plus a small margin). */
    private void requestTerrain() {
        if (terrainMode == null) return;
        lastTerrainReqMs = Minecraft.getSystemTime();
        float bpp = mapController.panel.getZoomBlocksPerPixel();
        int cx = Math.round(mapController.panel.getCenterXf());
        int cz = Math.round(mapController.panel.getCenterZf());
        int halfWBlocks = Math.round(mapW / 2f * bpp) + 48;
        int halfHBlocks = Math.round(mapH / 2f * bpp) + 48;

        int minCellX = Math.floorDiv(cx - halfWBlocks, 16);
        int minCellZ = Math.floorDiv(cz - halfHBlocks, 16);
        int wCells = Math.floorDiv(cx + halfWBlocks, 16) - minCellX + 1;
        int hCells = Math.floorDiv(cz + halfHBlocks, 16) - minCellZ + 1;

        // Clamp to the packet cap; when zoomed way out, show a centred patch rather than nothing.
        int cap = MsgTerrainDebugRequest.MAX_CELLS;
        if (wCells > cap) {
            minCellX = Math.floorDiv(cx, 16) - cap / 2;
            wCells = cap;
        }
        if (hCells > cap) {
            minCellZ = Math.floorDiv(cz, 16) - cap / 2;
            hCells = cap;
        }
        TravellersNetwork.CHANNEL.sendToServer(new MsgTerrainDebugRequest(
                minCellX, minCellZ, wCells, hCells, (byte) terrainMode.ordinal()));
    }

    /** Panel hook: draws the scanned-terrain tile over the worldmap background, under the markers. */
    private void drawTerrainOverlay() {
        if (terrainMode != null) {
            ClientTerrainDebugOverlay.draw(mapX, mapY, mapW, mapH,
                    mapController.panel.getCenterXf(), mapController.panel.getCenterZf(),
                    mapController.panel.getZoomBlocksPerPixel());
        }
        if (highlightLoadedChunks) {
            ClientLoadedChunksOverlay.draw(mapX, mapY, mapW, mapH,
                    mapController.panel.getCenterXf(), mapController.panel.getCenterZf(),
                    mapController.panel.getZoomBlocksPerPixel());
        }
        ClientItineraryDebugOverlay.draw(mapX, mapY, mapW, mapH,
                mapController.panel.getCenterXf(), mapController.panel.getCenterZf(),
                mapController.panel.getZoomBlocksPerPixel());
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int wheel = Mouse.getDWheel();
        if (wheel != 0) mapController.handleWheel(mouseX, mouseY, wheel);

        // Debug terrain overlay: refresh the tile ~once a second (markers stay live on their own).
        if (terrainMode != null && Minecraft.getSystemTime() - lastTerrainReqMs > 1000L) {
            requestTerrain();
        }
        if (highlightLoadedChunks && Minecraft.getSystemTime() - lastLoadedChunksReqMs > 1000L) {
            requestLoadedChunks();
        }
        if (ClientItineraryDebugOverlay.selected() != null && Minecraft.getSystemTime() - lastRouteReqMs > 1000L) {
            refreshRoute();
        }
        routeButton.displayString = routeLabel();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        TravellersGuiTheme.drawMapFrame(mapX, mapY, mapW, mapH);

        rebuildMarkers();
        mapController.draw();

        super.drawScreen(mouseX, mouseY, partialTicks);
        ClientItineraryDebugOverlay.drawInfo(mapX, mapY, mapW, mapH);
        drawCountsPanel();
        mapController.drawTooltip(this, mouseX, mouseY);
    }

    /** Small HUD box above the map showing live population counts, split loaded/virtual. */
    private void drawCountsPanel() {
        int travellerLoaded = 0, travellerVirtual = 0;
        int raiderLoaded = 0, raiderVirtual = 0;
        int workerLoaded = 0, workerVirtual = 0;
        int stableLoaded = 0, stableVirtual = 0;

        for (MsgMapPopulationUpdate.Entry e : populationEntries) {
            switch (e.kind) {
                case TRAVELLER:
                    if (e.virtual) travellerVirtual++; else travellerLoaded++;
                    break;
                case RAIDER:
                    if (e.virtual) raiderVirtual++; else raiderLoaded++;
                    break;
                case WORKER:
                    if (e.virtual) workerVirtual++; else workerLoaded++;
                    break;
                case STABLE:
                    if (e.virtual) stableVirtual++; else stableLoaded++;
                    break;
            }
        }

        FontRenderer fr = mc.fontRenderer;
        String l1 = I18n.format("travellers.map.population.traveller",
                travellerLoaded + travellerVirtual, travellerLoaded, travellerVirtual);
        String l2 = I18n.format("travellers.map.population.raider",
                raiderLoaded + raiderVirtual, raiderLoaded, raiderVirtual);
        String l3 = I18n.format("travellers.map.population.worker",
                workerLoaded + workerVirtual, workerLoaded, workerVirtual);
        String l4 = I18n.format("travellers.map.population.stable",
                stableLoaded + stableVirtual, stableLoaded, stableVirtual);

        int textW = Math.max(Math.max(fr.getStringWidth(l1), fr.getStringWidth(l2)),
                Math.max(fr.getStringWidth(l3), fr.getStringWidth(l4)));
        int boxX = mapX;
        int boxY = mapY - 3 - (4 * 10 + 6);
        int boxW = textW + 8;
        int boxH = 4 * 10 + 6;

        Gui.drawRect(boxX, boxY, boxX + boxW, boxY + boxH, 0x99090909);
        fr.drawString(l1, boxX + 4, boxY + 3, 0xFF88DD88);
        fr.drawString(l2, boxX + 4, boxY + 13, 0xFFDD6666);
        fr.drawString(l3, boxX + 4, boxY + 23, 0xFF6699DD);
        fr.drawString(l4, boxX + 4, boxY + 33, 0xFFDDBB66);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // ESC
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (clickMode != ClickMode.NONE && mouseButton == 0 && mapController.isMouseOver(mouseX, mouseY)) {
            net.minecraft.util.math.BlockPos target = mapController.screenToWorld(mouseX, mouseY);
            switch (clickMode) {
                case TELEPORT:
                    TravellersNetwork.CHANNEL.sendToServer(
                            new com.jubitus.travellers.debug.net.MsgTeleportToPos(target.getX(), target.getZ()));
                    break;
                case SPAWN_TRAVELLER:
                    TravellersNetwork.CHANNEL.sendToServer(new com.jubitus.travellers.debug.net.MsgSpawnEntityAtPos(
                            target.getX(), target.getZ(), com.jubitus.travellers.debug.net.MsgSpawnEntityAtPos.TYPE_TRAVELLER));
                    break;
                case SPAWN_WORKER:
                    TravellersNetwork.CHANNEL.sendToServer(new com.jubitus.travellers.debug.net.MsgSpawnEntityAtPos(
                            target.getX(), target.getZ(), com.jubitus.travellers.debug.net.MsgSpawnEntityAtPos.TYPE_WORKER));
                    break;
                case SPAWN_RAIDER:
                    TravellersNetwork.CHANNEL.sendToServer(new com.jubitus.travellers.debug.net.MsgSpawnEntityAtPos(
                            target.getX(), target.getZ(), com.jubitus.travellers.debug.net.MsgSpawnEntityAtPos.TYPE_RAIDER));
                    break;
                case ROUTE:
                    int radius = Math.max(8, Math.round(mapController.panel.getZoomBlocksPerPixel() * 12));
                    lastRouteReqMs = Minecraft.getSystemTime();
                    TravellersNetwork.CHANNEL.sendToServer(
                            new MsgItineraryDebugRequest(target.getX(), target.getZ(), radius, null));
                    break;
                default:
                    break;
            }
            // Teleport is one-shot; the spawn modes stay active so multiple entities can be
            // placed in a row until the player clicks the button again to turn it off.
            if (clickMode == ClickMode.TELEPORT || clickMode == ClickMode.ROUTE) {
                setClickMode(ClickMode.NONE);
            }
            return;
        }
        mapController.onMouseClick(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    /** Toggles a click-mode button on/off, keeping the modes mutually exclusive. */
    private void setClickMode(ClickMode mode) {
        clickMode = (clickMode == mode) ? ClickMode.NONE : mode;
        teleportButton.displayString = clickMode == ClickMode.TELEPORT
                ? I18n.format("travellers.map.teleport.active", "Click map...")
                : I18n.format("travellers.map.teleport", "Teleport");
        spawnTravellerButton.displayString = clickMode == ClickMode.SPAWN_TRAVELLER
                ? I18n.format("travellers.map.spawn.active", "Click map...")
                : I18n.format("travellers.map.spawn_traveller", "Spawn traveller");
        spawnWorkerButton.displayString = clickMode == ClickMode.SPAWN_WORKER
                ? I18n.format("travellers.map.spawn.active", "Click map...")
                : I18n.format("travellers.map.spawn_worker", "Spawn worker");
        spawnRaiderButton.displayString = clickMode == ClickMode.SPAWN_RAIDER
                ? I18n.format("travellers.map.spawn.active", "Click map...")
                : I18n.format("travellers.map.spawn_raider", "Spawn raider");
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        mapController.onMouseDrag(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        mapController.onMouseRelease(state);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == closeButton) { mc.displayGuiScreen(null); return; }
        if (navButtons.handle(button)) return;
        if (button == teleportButton) { setClickMode(ClickMode.TELEPORT); return; }
        if (button == spawnTravellerButton) { setClickMode(ClickMode.SPAWN_TRAVELLER); return; }
        if (button == spawnWorkerButton) { setClickMode(ClickMode.SPAWN_WORKER); return; }
        if (button == spawnRaiderButton) { setClickMode(ClickMode.SPAWN_RAIDER); return; }
        if (button == terrainButton) { cycleTerrainMode(); return; }
        if (button == loadedChunksButton) {
            highlightLoadedChunks = !highlightLoadedChunks;
            loadedChunksButton.displayString = loadedChunksLabel();
            if (highlightLoadedChunks) requestLoadedChunks();
            else ClientLoadedChunksOverlay.clear();
            return;
        }
        if (button == routeButton) {
            if (clickMode != ClickMode.ROUTE && ClientItineraryDebugOverlay.isActive()) {
                ClientItineraryDebugOverlay.clear();
            } else {
                setClickMode(ClickMode.ROUTE);
            }
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void receivePopulation(List<MsgMapPopulationUpdate.Entry> entries) {
        populationEntries.clear();
        if (entries != null) populationEntries.addAll(entries);
        rebuildMarkers();
    }

    @Override
    public void receiveCamps(List<MsgMapCampUpdate.Entry> entries) {
        campEntries.clear();
        if (entries != null) campEntries.addAll(entries);
        rebuildMarkers();
    }

    @Override
    public void receiveBattles(List<com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate.Entry> entries) {
        battleEntries.clear();
        if (entries != null) battleEntries.addAll(entries);
        rebuildMarkers();
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();

        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        for (MsgMapPopulationUpdate.Entry e : populationEntries) {
            if (e.dim != myDim) continue;
            switch (e.kind) {
                case TRAVELLER:
                    mapController.panel.addMarker(WorldMapMarkerFactory.populationTraveller(e.pos, e.virtual, e.label));
                    break;
                case RAIDER:
                    mapController.panel.addMarker(WorldMapMarkerFactory.populationRaider(e.pos, e.virtual, e.label));
                    break;
                case WORKER:
                    mapController.panel.addMarker(WorldMapMarkerFactory.populationWorker(e.pos, e.virtual, e.label));
                    break;
                case STABLE:
                    mapController.panel.addMarker(WorldMapMarkerFactory.populationStable(e.pos, e.virtual, e.label));
                    break;
            }
        }

        for (MsgMapCampUpdate.Entry e : campEntries) {
            if (e.dim != myDim) continue;
            if (e.kind == MsgMapCampUpdate.ObjKind.FIREPIT) {
                mapController.panel.addMarker(WorldMapMarkerFactory.campFirepit(e.pos, e.firepitBuilt, e.firepitLit, e.label));
            } else {
                mapController.panel.addMarker(WorldMapMarkerFactory.campSlot(e.pos, e.slotState, e.occupantVirtual, e.label));
            }
        }

        for (com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate.Entry e : battleEntries) {
            if (e.dim != myDim) continue;
            mapController.panel.addMarker(WorldMapMarkerFactory.battle(e.pos, e.label));
        }

        mapController.panel.addMarker(WorldMapMarkerFactory.player(player.getPosition(), player.getName(), 0xFFFFFF, player.rotationYaw));
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
