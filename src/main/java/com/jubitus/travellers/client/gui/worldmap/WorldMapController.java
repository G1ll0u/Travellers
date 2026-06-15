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
    private float dragStartCenterX, dragStartCenterZ;

    private long lastViewSendMs;
    private long lastTileRequestMs;
    private long lastDetailRequestMs;

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

    public static final float MAX_ZOOM_BPP = 64f;
    public static final float MIN_ZOOM_BPP = 0.0625f; // 1 block = 16 pixels
    private static final float ZOOM_FACTOR = 1.3f;    // each wheel click ×1.3 or ÷1.3

    public void handleWheel(int mouseX, int mouseY, int wheel) {
        if (!isMouseOver(mouseX, mouseY) || wheel == 0) return;

        float oldBpp = panel.getZoomBlocksPerPixel();
        float newBpp = wheel > 0
                ? Math.max(MIN_ZOOM_BPP, oldBpp / ZOOM_FACTOR)  // zoom in
                : Math.min(MAX_ZOOM_BPP, oldBpp * ZOOM_FACTOR); // zoom out
        if (newBpp == oldBpp) return;

        // Keep the world position under the cursor fixed while zooming (float precision)
        float oldCX = panel.getCenterXf();
        float oldCZ = panel.getCenterZf();
        float dx = mouseX - (mapX + mapW / 2f);
        float dy = mouseY - (mapY + mapH / 2f);

        float worldX = oldCX + dx * oldBpp;
        float worldZ = oldCZ + dy * oldBpp;

        panel.setZoomBlocksPerPixel(newBpp);
        panel.setCenterF(worldX - dx * newBpp, worldZ - dy * newBpp);
    }

    /** Returns true if the click was consumed (starts right-click pan). */
    public boolean onMouseClick(int mouseX, int mouseY, int button) {
        if (button == 1 && isMouseOver(mouseX, mouseY)) {
            dragging = true;
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragStartCenterX = panel.getCenterXf();
            dragStartCenterZ = panel.getCenterZf();
            return true;
        }
        return false;
    }

    public void onMouseDrag(int mouseX, int mouseY) {
        if (!dragging) return;
        float bpp = panel.getZoomBlocksPerPixel();
        panel.setCenterF(
                dragStartCenterX - (mouseX - dragStartX) * bpp,
                dragStartCenterZ - (mouseY - dragStartY) * bpp);
    }

    public void onMouseRelease(int state) {
        if (state == 1 && dragging) {
            dragging = false;
        }
    }

    public void draw() {
        panel.draw(mapX, mapY);
        requestTiles();
        requestDetailTiles();
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

        float bpp = panel.getZoomBlocksPerPixel();
        BlockPos c = panel.getCenter();
        int halfW = Math.round((mapW / 2) * bpp);
        int halfH = Math.round((mapH / 2) * bpp);

        int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapTiles(
                floorDiv(c.getX() - halfW, tileBlocks) - 1,
                floorDiv(c.getX() + halfW, tileBlocks) + 1,
                floorDiv(c.getZ() - halfH, tileBlocks) - 1,
                floorDiv(c.getZ() + halfH, tileBlocks) + 1));
    }

    private void requestDetailTiles() {
        if (panel.getZoomBlocksPerPixel() > 4f) return;
        long now = System.currentTimeMillis();
        if (now - lastDetailRequestMs < 500) return;
        lastDetailRequestMs = now;

        float bpp = panel.getZoomBlocksPerPixel();
        BlockPos c = panel.getCenter();
        int halfW = Math.round((mapW / 2) * bpp);
        int halfH = Math.round((mapH / 2) * bpp);

        int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
        TravellerFollowNet.CHANNEL.sendToServer(new com.jubitus.travellers.server.net.message.map.MsgRequestDetailTiles(
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
        float bpp = panel.getZoomBlocksPerPixel();
        int range = Math.round(Math.max(mapW / 2f * bpp, mapH / 2f * bpp)) + 256;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerMapView(dim, c.getX(), c.getZ(), range));
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }
}
