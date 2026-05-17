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

    private long lastViewSendMs = 0L;

    private final EntityPlayer player;
    private final int contextWorkerId;

    private GuiWorldMapPanel mapPanel;

    private int mapX, mapY, mapW, mapH;

    private GuiButton zoomInButton, zoomOutButton, recenterButton;
    private GuiButton panN, panS, panWBtn, panE;

    private boolean draggingMap = false;
    private int dragStartX, dragStartY;
    private BlockPos dragStartCenter = null;

    private long lastTileRequestMs = 0L;

    private final List<PoiMarker> poi = new ArrayList<>();
    private final List<WorkerMarker> workers = new ArrayList<>();

    private static class PoiMarker {
        BlockPos pos;
        String label;
        int color;
        int radius;
    }

    private static class WorkerMarker {
        int entityId;
        int dim;
        BlockPos pos;
        String name;
        int color;
        int radius;
    }

    public GuiPlayerWorldMap(EntityPlayer player, int contextWorkerId) {
        this.player = player;
        this.contextWorkerId = contextWorkerId;
    }

    @Override
    public void initGui() {
        super.initGui();

        // Big map size with safe margins
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

        // Pan buttons inside the map
        int aw = 16, ah = 16;
        int midY = mapY + (mapH / 2) - (ah / 2);
        int midX = mapX + (mapW / 2) - (aw / 2);

        panWBtn = this.addButton(new GuiButton(2, mapX + 2, midY, aw, ah, "<"));
        panE    = this.addButton(new GuiButton(3, mapX + mapW - aw - 2, midY, aw, ah, ">"));
        panN    = this.addButton(new GuiButton(4, midX, mapY + 2, aw, ah, "^"));
        panS    = this.addButton(new GuiButton(5, midX, mapY + mapH - ah - 2, aw, ah, "v"));

        // Zoom + center below map
        int zTop = mapY + mapH + 6;
        zoomOutButton = this.addButton(new GuiButton(6, mapX, zTop, 16, 16, "-"));
        zoomInButton  = this.addButton(new GuiButton(7, mapX + 20, zTop, 16, 16, "+"));
        recenterButton = this.addButton(new GuiButton(
                8,
                mapX + mapW - 54,
                zTop,
                54,
                16,
                I18n.format("travellers.map.center", "Center")
        ));
        backButton = this.addButton(new GuiButton(9, mapX + mapW - 110, zTop, 50, 16, I18n.format("travellers.gui.back", "Back")));

        // Requests
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(contextWorkerId));

        // Subscribe worker live positions
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

        // Wheel zoom
        int wheel = Mouse.getDWheel();
        if (wheel != 0 && isMouseOverMap(mouseX, mouseY)) {
            handleMapWheelZoom(wheel);
        }

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        // Map frame
        drawRect(mapX - 2, mapY - 2, mapX + mapW + 2, mapY + mapH + 2, 0x80000000);

        // Map
        rebuildMarkers();
        mapPanel.draw(mapX, mapY);
        sendMapViewToServerIfNeeded();
        requestVisibleTilesFromServer();

        // Buttons + tooltips
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHoverTooltip(mouseX, mouseY);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        // Right click drag
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

        int dxPx = mouseX - dragStartX;
        int dyPx = mouseY - dragStartY;

        int bpp = mapPanel.getZoomBlocksPerPixel();

        int dxBlocks = -dxPx * bpp;
        int dzBlocks = -dyPx * bpp;

        mapPanel.setCenter(dragStartCenter.add(dxBlocks, 0, dzBlocks));
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
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(contextWorkerId, MsgOpenWorkerSubGui.Type.HUB)
            );
            return;
        }

        if (button == panN) { mapPanel.pan(0, -stepBlocks); return; }
        if (button == panS) { mapPanel.pan(0,  stepBlocks); return; }
        if (button == panWBtn) { mapPanel.pan(-stepBlocks, 0); return; }
        if (button == panE) { mapPanel.pan(stepBlocks, 0); return; }

        if (button == zoomOutButton) {
            mapPanel.setZoomBlocksPerPixel(clamp(mapPanel.getZoomBlocksPerPixel() + 1, 1, 16));
            return;
        }
        if (button == zoomInButton) {
            mapPanel.setZoomBlocksPerPixel(clamp(mapPanel.getZoomBlocksPerPixel() - 1, 1, 16));
            return;
        }
        if (button == recenterButton) {
            mapPanel.setCenter(player.getPosition());
            return;
        }

        super.actionPerformed(button);
    }

    private boolean isMouseOverMap(int mouseX, int mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW
                && mouseY >= mapY && mouseY < mapY + mapH;
    }

    private void handleMapWheelZoom(int wheel) {
        int bpp = mapPanel.getZoomBlocksPerPixel();
        if (wheel > 0) bpp--;
        else bpp++;
        mapPanel.setZoomBlocksPerPixel(clamp(bpp, 1, 16));
    }

    private int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    // ---- Receive villages/homes from server (same packet as GuiWorkerGo) ----
    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        poi.clear();

        for (MsgWorkerGoList.Entry e : entries) {
            PoiMarker m = new PoiMarker();
            m.pos = e.pos;
            m.label = e.label != null ? e.label : "";

            if (e.kind == MsgWorkerGoList.Kind.VILLAGE) {
                m.color = 0xffd7af;
                m.radius = 2;
            } else {
                m.color = 0xb8dba6;
                m.radius = 2;
            }
            poi.add(m);
        }

        rebuildMarkers();
    }

    // ---- Receive worker positions stream ----
    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        workers.clear();

        int myDim = Minecraft.getMinecraft().world.provider.getDimension();

        for (MsgMapWorkersUpdate.Entry e : list) {
            // Only show workers in the current dimension
            if (e.dim != myDim) continue;

            WorkerMarker m = new WorkerMarker();
            m.entityId = e.entityId;
            m.dim = e.dim;
            m.pos = e.pos;
            m.name = (e.name != null && !e.name.isEmpty()) ? e.name : ("Worker#" + e.entityId);
            m.color = 0xFFfce5cd;
            m.radius = 2;
            workers.add(m);
        }

        rebuildMarkers();
    }

    private void rebuildMarkers() {
        if (mapPanel == null) return;

        mapPanel.clearMarkers();
        mapPanel.setPlayerMarker(player.getPosition(), 0xFF55FF55, 2);

        for (PoiMarker m : poi) {
            mapPanel.addMarker(new GuiWorldMapPanel.Marker(m.pos, m.color, m.radius));
        }

        for (WorkerMarker m : workers) {
            mapPanel.addMarker(new GuiWorldMapPanel.Marker(m.pos, m.color, m.radius));
        }
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        String label = findLabelUnderMouse(mouseX, mouseY);
        if (label == null || label.isEmpty()) return;

        this.drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
    }

    private String findLabelUnderMouse(int mouseX, int mouseY) {
        // Check POIs first (village/home)
        for (PoiMarker m : poi) {
            if (isMouseOverMarker(mouseX, mouseY, m.pos, m.radius + 2)) {
                return m.label;
            }
        }
        // Then workers
        for (WorkerMarker m : workers) {
            if (isMouseOverMarker(mouseX, mouseY, m.pos, m.radius + 2)) {
                return m.name;
            }
        }
        return null;
    }

    private boolean isMouseOverMarker(int mouseX, int mouseY, BlockPos pos, int radiusPx) {
        if (pos == null) return false;

        BlockPos center = mapPanel.getCenter();
        int bpp = mapPanel.getZoomBlocksPerPixel();
        if (bpp <= 0) bpp = 1;

        // world -> screen approximation (same mapping idea as screenToWorld)
        int sx = mapX + (mapW / 2) + (pos.getX() - center.getX()) / bpp;
        int sy = mapY + (mapH / 2) + (pos.getZ() - center.getZ()) / bpp;

        int dx = mouseX - sx;
        int dy = mouseY - sy;
        return (dx * dx + dy * dy) <= (radiusPx * radiusPx);
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

        int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16; // 256
        int minTileX = floorDiv(minX, tileBlocks) - 1;
        int maxTileX = floorDiv(maxX, tileBlocks) + 1;
        int minTileZ = floorDiv(minZ, tileBlocks) - 1;
        int maxTileZ = floorDiv(maxZ, tileBlocks) + 1;

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapTiles(minTileX, maxTileX, minTileZ, maxTileZ));
        sendMapViewToServer();
    }
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }
    private void sendMapViewToServerIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastViewSendMs < 250) return; // 4 times/sec max
        lastViewSendMs = now;

        if (mapPanel == null) return;

        int dim = Minecraft.getMinecraft().world.provider.getDimension();
        BlockPos c = mapPanel.getCenter();

        // Range based on zoom and visible size (plus a little padding)
        int bpp = mapPanel.getZoomBlocksPerPixel();
        int halfWBlocks = (mapW / 2) * bpp;
        int halfHBlocks = (mapH / 2) * bpp;
        int range = Math.max(halfWBlocks, halfHBlocks) + 256;

        TravellerFollowNet.CHANNEL.sendToServer(
                new MsgWorkerMapView(dim, c.getX(), c.getZ(), range)
        );
    }
    private void sendMapViewToServer() {
        long now = System.currentTimeMillis();
        if (now - lastViewSendMs < 250) return; // throttle
        lastViewSendMs = now;

        int dim = Minecraft.getMinecraft().world.provider.getDimension();
        BlockPos c = mapPanel.getCenter();

        int bpp = mapPanel.getZoomBlocksPerPixel();
        int halfWBlocks = (mapW / 2) * bpp;
        int halfHBlocks = (mapH / 2) * bpp;

        // radius that safely covers the whole visible map
        int rangeBlocks = Math.max(halfWBlocks, halfHBlocks) + 64;

        TravellerFollowNet.CHANNEL.sendToServer(
                new MsgWorkerMapView(dim, c.getX(), c.getZ(), rangeBlocks)
        );
    }
}