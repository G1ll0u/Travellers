package com.jubitus.travellers.client.gui.worldmap;

import com.jubitus.travellers.server.map.WorldMapDiscovery;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapTiles;
import com.jubitus.travellers.server.net.message.map.MsgWorkerMapView;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;

public class WorldMapController {

    public final GuiWorldMapPanel panel;
    public final int mapX, mapY, mapW, mapH;

    private boolean dragging;
    private int dragStartX, dragStartY;
    private BlockPos dragStartCenter;

    private long lastViewSendMs;
    private long lastTileRequestMs;

    public WorldMapController(Minecraft mc, int mapX, int mapY, int mapW, int mapH,
                               String textureKey, BlockPos initialCenter) {
        this.mapX = mapX;
        this.mapY = mapY;
        this.mapW = mapW;
        this.mapH = mapH;
        this.panel = new GuiWorldMapPanel(mc, mc.world, mapW, mapH, textureKey);
        panel.setCenter(initialCenter != null ? initialCenter : BlockPos.ORIGIN);
        GuiWorldMapPanelHooks.setPanel(panel);
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW
                && mouseY >= mapY && mouseY < mapY + mapH;
    }

    public static final int MAX_ZOOM_BPP = 64;

    public void handleWheel(int mouseX, int mouseY, int wheel) {
        if (!isMouseOver(mouseX, mouseY) || wheel == 0) return;
        int bpp = panel.getZoomBlocksPerPixel() + (wheel > 0 ? -1 : 1);
        panel.setZoomBlocksPerPixel(Math.max(1, Math.min(MAX_ZOOM_BPP, bpp)));
    }

    /** Returns true if the click was consumed (starts right-click pan). */
    public boolean onMouseClick(int mouseX, int mouseY, int button) {
        if (button == 1 && isMouseOver(mouseX, mouseY)) {
            dragging = true;
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragStartCenter = panel.getCenter();
            return true;
        }
        return false;
    }

    public void onMouseDrag(int mouseX, int mouseY) {
        if (!dragging || dragStartCenter == null) return;
        int bpp = panel.getZoomBlocksPerPixel();
        panel.setCenter(dragStartCenter.add(
                -(mouseX - dragStartX) * bpp, 0, -(mouseY - dragStartY) * bpp));
    }

    public void onMouseRelease(int state) {
        if (state == 1 && dragging) {
            dragging = false;
            dragStartCenter = null;
        }
    }

    public void draw() {
        panel.draw(mapX, mapY);
        requestTiles();
        sendView();
    }

    /** Returns the marker label under the mouse, or null. */
    public String getTooltip(int mouseX, int mouseY) {
        if (!isMouseOver(mouseX, mouseY)) return null;
        return panel.getTooltipAt(mouseX, mouseY, mapX, mapY);
    }

    public void setCenter(BlockPos pos) {
        if (pos != null) panel.setCenter(pos);
    }

    public BlockPos screenToWorld(int mouseX, int mouseY) {
        return panel.screenToWorld(mouseX, mouseY, mapX, mapY);
    }

    private void requestTiles() {
        long now = System.currentTimeMillis();
        if (now - lastTileRequestMs < 300) return;
        lastTileRequestMs = now;

        int bpp = panel.getZoomBlocksPerPixel();
        BlockPos c = panel.getCenter();
        int halfW = (mapW / 2) * bpp;
        int halfH = (mapH / 2) * bpp;

        int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapTiles(
                floorDiv(c.getX() - halfW, tileBlocks) - 1,
                floorDiv(c.getX() + halfW, tileBlocks) + 1,
                floorDiv(c.getZ() - halfH, tileBlocks) - 1,
                floorDiv(c.getZ() + halfH, tileBlocks) + 1));
    }

    private void sendView() {
        long now = System.currentTimeMillis();
        if (now - lastViewSendMs < 250) return;
        lastViewSendMs = now;

        int dim = Minecraft.getMinecraft().world.provider.getDimension();
        BlockPos c = panel.getCenter();
        int bpp = panel.getZoomBlocksPerPixel();
        int range = Math.max((mapW / 2) * bpp, (mapH / 2) * bpp) + 256;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerMapView(dim, c.getX(), c.getZ(), range));
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }
}
