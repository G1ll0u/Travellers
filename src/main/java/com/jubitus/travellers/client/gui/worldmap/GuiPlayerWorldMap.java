package com.jubitus.travellers.client.gui.worldmap;

import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.map.WorldMapDiscovery;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapTiles;
import com.jubitus.travellers.server.net.message.map.MsgWorkerMapView;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerRequestGoList;
import com.jubitus.travellers.client.input.WorldMapKeyHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.*;

@SideOnly(Side.CLIENT)
public class GuiPlayerWorldMap extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapReceiver {

    private GuiButton backButton;
    private GuiButton zoomInButton, zoomOutButton, recenterButton;
    private GuiButton panN, panS, panWBtn, panE;

    private final EntityPlayer player;
    private final int contextWorkerId;

    private GuiWorldMapPanel mapPanel;
    private int mapX, mapY, mapW, mapH;

    private boolean draggingMap = false;
    private int dragStartX, dragStartY;
    private BlockPos dragStartCenter = null;

    private long lastViewSendMs = 0L;
    private long lastTileRequestMs = 0L;

    // POI data (villages/homes) received from server
    private static class PoiEntry {
        BlockPos pos;
        String label;
        MsgWorkerGoList.Kind kind;
    }

    // Worker data received from server stream
    private static class WorkerEntry {
        BlockPos pos;
        String name;
        boolean isLeader;
        int ownerColor;
    }

    private final List<PoiEntry> poiEntries = new ArrayList<>();
    private final List<WorkerEntry> workerEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

    public GuiPlayerWorldMap(EntityPlayer player, int contextWorkerId) {
        this.player = player;
        this.contextWorkerId = contextWorkerId;
    }

    @Override
    public void initGui() {
        super.initGui();

        mapW = Math.min(360, this.width - 20);
        mapH = Math.min(240, this.height - 70);
        mapX = (this.width - mapW) / 2;
        mapY = (this.height - mapH) / 2 - 10;

        this.buttonList.clear();

        this.mapPanel = new GuiWorldMapPanel(
                Minecraft.getMinecraft(),
                Minecraft.getMinecraft().world,
                mapW, mapH,
                "travellers_player_map"
        );
        GuiWorldMapPanelHooks.setPanel(mapPanel);
        mapPanel.setCenter(player.getPosition());
        mapPanel.setSelectedPos(null);

        int aw = 16, ah = 16;
        int midY = mapY + (mapH / 2) - (ah / 2);
        int midX = mapX + (mapW / 2) - (aw / 2);

        panWBtn = this.addButton(new GuiButton(2, mapX + 2, midY, aw, ah, "<"));
        panE    = this.addButton(new GuiButton(3, mapX + mapW - aw - 2, midY, aw, ah, ">"));
        panN    = this.addButton(new GuiButton(4, midX, mapY + 2, aw, ah, "^"));
        panS    = this.addButton(new GuiButton(5, midX, mapY + mapH - ah - 2, aw, ah, "v"));

        int zTop = mapY + mapH + 6;
        zoomOutButton  = this.addButton(new GuiButton(6, mapX, zTop, 20, 20, "-"));
        zoomInButton   = this.addButton(new GuiButton(7, mapX + 24, zTop, 20, 20, "+"));
        recenterButton = this.addButton(new GuiButton(8, mapX + mapW - 58, zTop, 58, 20,
                I18n.format("travellers.map.center", "Center")));
        backButton     = this.addButton(new GuiButton(9, mapX + mapW - 122, zTop, 58, 20,
                I18n.format(contextWorkerId < 0 ? "travellers.gui.close" : "travellers.gui.back")));

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(contextWorkerId));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        requestVisibleTilesFromServer();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int wheel = Mouse.getDWheel();
        if (wheel != 0 && isMouseOverMap(mouseX, mouseY)) {
            handleMapWheelZoom(wheel);
        }

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        drawRect(mapX - 2, mapY - 2, mapX + mapW + 2, mapY + mapH + 2, 0x80000000);

        rebuildMarkers();
        mapPanel.draw(mapX, mapY);
        sendMapViewToServerIfNeeded();
        requestVisibleTilesFromServer();

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHoverTooltip(mouseX, mouseY);
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (!isMouseOverMap(mouseX, mouseY) || mapPanel == null) return;
        String label = mapPanel.getTooltipAt(mouseX, mouseY, mapX, mapY);
        if (label != null && !label.isEmpty()) {
            this.drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == WorldMapKeyHandler.KEY.getKeyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 1 && isMouseOverMap(mouseX, mouseY)) {
            draggingMap = true;
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragStartCenter = mapPanel.getCenter();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (!draggingMap || dragStartCenter == null) return;
        int bpp = mapPanel.getZoomBlocksPerPixel();
        mapPanel.setCenter(dragStartCenter.add(-(mouseX - dragStartX) * bpp, 0, -(mouseY - dragStartY) * bpp));
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 1 && draggingMap) {
            draggingMap = false;
            dragStartCenter = null;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        int stepBlocks = mapPanel.getZoomBlocksPerPixel() * 48;

        if (button == backButton) {
            if (contextWorkerId < 0) {
                mc.displayGuiScreen(null);
            } else {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(contextWorkerId, MsgOpenWorkerSubGui.Type.HUB));
            }
            return;
        }
        if (button == panN)   { mapPanel.pan(0, -stepBlocks); return; }
        if (button == panS)   { mapPanel.pan(0,  stepBlocks); return; }
        if (button == panWBtn){ mapPanel.pan(-stepBlocks, 0); return; }
        if (button == panE)   { mapPanel.pan( stepBlocks, 0); return; }
        if (button == zoomOutButton) {
            mapPanel.setZoomBlocksPerPixel(clamp(mapPanel.getZoomBlocksPerPixel() + 1, 1, WorldMapController.MAX_ZOOM_BPP)); return;
        }
        if (button == zoomInButton) {
            mapPanel.setZoomBlocksPerPixel(clamp(mapPanel.getZoomBlocksPerPixel() - 1, 1, WorldMapController.MAX_ZOOM_BPP)); return;
        }
        if (button == recenterButton) { mapPanel.setCenter(player.getPosition()); return; }

        super.actionPerformed(button);
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        poiEntries.clear();
        for (MsgWorkerGoList.Entry e : entries) {
            PoiEntry poi = new PoiEntry();
            poi.pos = e.pos;
            poi.label = e.label != null ? e.label : "";
            poi.kind = e.kind;
            poiEntries.add(poi);
        }
        rebuildMarkers();
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        workerEntries.clear();
        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        for (MsgMapWorkersUpdate.Entry e : list) {
            if (e.dim != myDim) continue;
            WorkerEntry w = new WorkerEntry();
            w.pos = e.pos;
            w.name = (e.name != null && !e.name.isEmpty()) ? e.name : ("Worker#" + e.entityId);
            w.isLeader = e.isLeader;
            w.ownerColor = e.ownerColor;
            workerEntries.add(w);
            // track player's owner color from any received worker
            if (playerOwnerColor == 0xFFFFFF && e.ownerColor != 0) {
                playerOwnerColor = e.ownerColor;
            }
        }
        rebuildMarkers();
    }

    private void rebuildMarkers() {
        if (mapPanel == null) return;
        mapPanel.clearMarkers();

        for (PoiEntry poi : poiEntries) {
            if (poi.kind == MsgWorkerGoList.Kind.VILLAGE) {
                mapPanel.addMarker(WorldMapMarkerFactory.village(poi.pos, poi.label));
            } else {
                mapPanel.addMarker(WorldMapMarkerFactory.home(poi.pos, poi.label));
            }
        }

        for (WorkerEntry w : workerEntries) {
            if (w.isLeader) {
                mapPanel.addMarker(WorldMapMarkerFactory.leader(w.pos, w.name, w.ownerColor));
            } else {
                mapPanel.addMarker(WorldMapMarkerFactory.follower(w.pos, w.name, w.ownerColor));
            }
        }

        // Player drawn last (on top)
        mapPanel.addMarker(WorldMapMarkerFactory.player(player.getPosition(), player.getName(), playerOwnerColor));
    }

    private boolean isMouseOverMap(int mouseX, int mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH;
    }

    private void handleMapWheelZoom(int wheel) {
        int bpp = mapPanel.getZoomBlocksPerPixel();
        bpp = wheel > 0 ? bpp - 1 : bpp + 1;
        mapPanel.setZoomBlocksPerPixel(clamp(bpp, 1, WorldMapController.MAX_ZOOM_BPP));
    }

    private int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    private void requestVisibleTilesFromServer() {
        long now = System.currentTimeMillis();
        if (now - lastTileRequestMs < 250) return;
        lastTileRequestMs = now;

        int bpp = mapPanel.getZoomBlocksPerPixel();
        int halfWBlocks = (mapW / 2) * bpp;
        int halfHBlocks = (mapH / 2) * bpp;

        int minX = mapPanel.getCenter().getX() - halfWBlocks;
        int maxX = mapPanel.getCenter().getX() + halfWBlocks;
        int minZ = mapPanel.getCenter().getZ() - halfHBlocks;
        int maxZ = mapPanel.getCenter().getZ() + halfHBlocks;

        int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapTiles(
                floorDiv(minX, tileBlocks) - 1, floorDiv(maxX, tileBlocks) + 1,
                floorDiv(minZ, tileBlocks) - 1, floorDiv(maxZ, tileBlocks) + 1));
        sendMapViewToServer();
    }

    private void sendMapViewToServerIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastViewSendMs < 250) return;
        sendMapViewToServer();
    }

    private void sendMapViewToServer() {
        long now = System.currentTimeMillis();
        if (now - lastViewSendMs < 250) return;
        lastViewSendMs = now;

        int dim = Minecraft.getMinecraft().world.provider.getDimension();
        BlockPos c = mapPanel.getCenter();
        int bpp = mapPanel.getZoomBlocksPerPixel();
        int range = Math.max((mapW / 2) * bpp, (mapH / 2) * bpp) + 256;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerMapView(dim, c.getX(), c.getZ(), range));
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
