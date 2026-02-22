package com.jubitus.travellers.client.gui.go;

import com.jubitus.travellers.client.gui.worldmap.GuiWorldMapPanel;
import com.jubitus.travellers.client.gui.worldmap.GuiWorldMapPanelHooks;
import com.jubitus.travellers.server.map.WorldMapDiscovery;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import com.jubitus.travellers.utils.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.utils.net.message.map.MsgRequestMapTiles;
import com.jubitus.travellers.utils.net.message.workergo.MsgWorkerGoList;
import com.jubitus.travellers.utils.net.message.workergo.MsgWorkerGoTo;
import com.jubitus.travellers.utils.net.message.workergo.MsgWorkerGoToPos;
import com.jubitus.travellers.utils.net.message.workergo.MsgWorkerRequestGoList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerGo extends GuiContainer {

    private long lastTileRequestMs = 0L;

    private boolean draggingMap = false;
    private int dragStartX, dragStartY;
    private BlockPos dragStartCenter = null;


    private GuiButton zoomInButton, zoomOutButton, recenterButton;

    // Pour contrôler quel point est "sélectionné dans la liste"
    private BlockPos selectedRowPos = null;
    private MsgWorkerGoList.Kind selectedRowKind = null;


    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private final List<Row> rows = new ArrayList<>();
    private int selectedIndex = -1;

    private GuiButton goButton;
    private GuiButton panN, panS, panW, panE;

    private GuiWorldMapPanel mapPanel;
    private BlockPos selectedMapPos = null;

    private int mapX, mapY, mapW, mapH;


    private int listX, listY, listW, listH;
    private final int rowH = 12;

    // --- SCROLLING ---
    private int scrollOffset = 0;

    private boolean isMouseOverList(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH;
    }

    private int getVisibleRows() {
        return Math.max(1, listH / rowH);
    }

    private int getMaxScroll() {
        return Math.max(0, rows.size() - getVisibleRows());
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        int max = getMaxScroll();
        if (scrollOffset > max) scrollOffset = max;
    }


    public GuiWorkerGo(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerGo(player.inventory, worker));
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 150;
    }

    @Override
    public void initGui() {
        super.initGui();

        int shiftLeft = 40;
        this.guiLeft = Math.max(4, this.guiLeft - shiftLeft);
        this.guiTop = (this.height - this.ySize) / 2;

        this.listW = 140;
        this.listH = 120;
        this.listX = this.guiLeft + this.xSize + 8;
        this.listY = this.guiTop + 4;

// --- MAP: bigger and pushed left ---
// --- MAP: bigger and always left of the list ---
        this.mapW = 190;   // bigger (adjust if needed)
        this.mapH = 150;
        this.mapY = this.listY;

        int gapToList = 14;
        this.mapX = this.listX - this.mapW - gapToList;

// Ensure map doesn't go off-screen left
        int minLeft = 6;
        if (this.mapX < minLeft) {
            int shiftRight = (minLeft - this.mapX);
            // shift everything right by shiftRight
            this.guiLeft += shiftRight;
            this.listX   += shiftRight;
            this.mapX    += shiftRight;
        }


// init map panel
// (Re)create panel if size changed
        this.mapPanel = new GuiWorldMapPanel(
                Minecraft.getMinecraft(),
                Minecraft.getMinecraft().world,
                mapW, mapH,
                "travellers_worker_map"
        );
        GuiWorldMapPanelHooks.setPanel(mapPanel);

        this.mapPanel.setCenter(worker.getPosition());
        this.mapPanel.setSelectedPos(null);
        this.selectedMapPos = null;


        this.buttonList.clear();

        int bw = 70, bh = 16, gap = 4;
        int buttonsTop = listY + listH + gap;

        this.goButton = this.addButton(new GuiButton(1, listX + (listW - bw), buttonsTop, bw, bh, "Go"));
        this.goButton.enabled = false;

// Arrow buttons around the map
// Arrow buttons INSIDE the map (prevents overlap with list)
        int aw = 16, ah = 16;

// left/right inside
        int ay = mapY + (mapH / 2) - (ah / 2);
        this.panW = this.addButton(new GuiButton(2, mapX + 2, ay, aw, ah, "<"));
        this.panE = this.addButton(new GuiButton(3, mapX + mapW - aw - 2, ay, aw, ah, ">"));

// up/down inside
        int axMid = mapX + (mapW / 2) - (aw / 2);
        this.panN = this.addButton(new GuiButton(4, axMid, mapY + 2, aw, ah, "^"));
        this.panS = this.addButton(new GuiButton(5, axMid, mapY + mapH - ah - 2, aw, ah, "v"));

// Zoom & recenter buttons (below the map) - now no overlap with panS
        int zbh = 16;
        int zbw = 16;
        int zTop = mapY + mapH + 6;   // little extra gap

        this.zoomOutButton = this.addButton(new GuiButton(6, mapX, zTop, zbw, zbh, "-"));
        this.zoomInButton  = this.addButton(new GuiButton(7, mapX + zbw + 4, zTop, zbw, zbh, "+"));
        this.recenterButton = this.addButton(new GuiButton(8, mapX + mapW - 54, zTop, 54, zbh, "Center"));

        this.goButton.enabled = false;

        rows.clear();
        scrollOffset = 0;

        selectedIndex = -1;

        // Request list from server
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        requestVisibleTilesFromServer();

    }

    // Called by MsgWorkerGoList
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        rows.clear();
        for (MsgWorkerGoList.Entry e : entries) {
            rows.add(new Row(e.kind, e.pos, e.label, e.distance));
        }
        rows.sort(Comparator.comparingInt(r -> r.distance));
        selectedIndex = -1;
        goButton.enabled = false;
        // Update map markers
        mapPanel.clearMarkers();
        for (Row r : rows) {
            if (r.kind == MsgWorkerGoList.Kind.VILLAGE) {
                // village marker: dark + visible
                mapPanel.addMarker(new GuiWorldMapPanel.Marker(r.pos, 0xFF1E1A14, 2));
            } else {
                // home marker: lighter
                mapPanel.addMarker(new GuiWorldMapPanel.Marker(r.pos, 0xFF6B5B3E, 2));
            }
        }
        if (selectedRowPos != null && selectedRowKind == MsgWorkerGoList.Kind.VILLAGE) {
            mapPanel.addMarker(new GuiWorldMapPanel.Marker(selectedRowPos, 0xFFFFE39A, 3));
        }
        scrollOffset = 0;
        clampScroll();

    }
    private void handleListWheelScroll(int mouseX, int mouseY, int listSize, int wheel) {
        if (listSize <= 0) return;
        if (wheel == 0) return;

        // Only scroll if the mouse is over the list panel
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return;
        }

        int dir = (wheel > 0) ? -1 : 1; // up = -1, down = +1
        scrollOffset += dir;

        if (scrollOffset < 0) scrollOffset = 0;

        int visible = Math.max(1, listH / rowH);
        int maxScroll = Math.max(0, listSize - visible);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }



    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);

        int wheel = org.lwjgl.input.Mouse.getDWheel();
        if (wheel != 0) {
            if (isMouseOverMap(mouseX, mouseY)) {
                handleMapWheelZoom(wheel);
            } else {
                handleListWheelScroll(mouseX, mouseY, rows.size(), wheel);
            }
        }

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        drawRect(mapX - 2, mapY - 2, mapX + mapW + 2, mapY + mapH + 2, 0x80000000);
        mapPanel.draw(mapX, mapY);
        requestVisibleTilesFromServer();

        drawList(mouseX, mouseY);
        this.renderHoveredToolTip(mouseX, mouseY);
    }



    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        // Intentionally empty (same style as your recruit screen)
    }

    private void drawList(int mouseX, int mouseY) {
        drawRect(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0x80000000);

        clampScroll();

        int rowsVisible = Math.min(getVisibleRows(), rows.size() - scrollOffset);
        for (int i = 0; i < rowsVisible; i++) {
            int rowTop = listY + i * rowH;
            int rowBottom = rowTop + rowH;

            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;

            int globalIdx = scrollOffset + i;
            boolean sel = (globalIdx == selectedIndex);

            int bg = sel ? 0x4033FF33 : (hovered ? 0x20FFFFFF : 0x20000000);
            drawRect(listX, rowTop, listX + listW, rowBottom, bg);

            Row r = rows.get(globalIdx);

            final float scale = 0.85F;

            String prefix = (r.kind == MsgWorkerGoList.Kind.VILLAGE) ? "[V] " : "[H] ";
            String distSuffix = " (" + r.distance + "m)";

            String label = shortenNameWithSuffix(prefix + r.label, distSuffix, (int)((listW - 6) / scale));

            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            this.fontRenderer.drawString(label, 0, 0, 0xE0E0E0);
            GlStateManager.popMatrix();
        }

    }

    // Same helper logic as recruit UI (copy/paste friendly)
    private String shortenNameWithSuffix(String name, String suffix, int maxWidth) {
        int fullWidth = this.fontRenderer.getStringWidth(name + suffix);
        if (fullWidth <= maxWidth) return name + suffix;

        int suffixWidth = this.fontRenderer.getStringWidth(suffix);
        if (suffixWidth >= maxWidth) return this.fontRenderer.trimStringToWidth(suffix, maxWidth);

        int remaining = maxWidth - suffixWidth;

        String ellipsis = "...";
        int ellWidth = this.fontRenderer.getStringWidth(ellipsis);
        if (ellWidth >= remaining) return suffix;

        int half = (remaining - ellWidth) / 2;

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            String candidate = prefix.toString() + name.charAt(i);
            if (this.fontRenderer.getStringWidth(candidate) > half) break;
            prefix.append(name.charAt(i));
        }

        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String candidate = name.charAt(i) + tail.toString();
            int w = this.fontRenderer.getStringWidth(prefix + ellipsis + candidate);
            if (w > remaining) break;
            tail.insert(0, name.charAt(i));
        }

        return prefix + ellipsis + tail + suffix;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        // Click on map => select destination
        if (mouseButton == 0) {
            if (mouseX >= mapX && mouseX < mapX + mapW &&
                    mouseY >= mapY && mouseY < mapY + mapH) {

                BlockPos pos = mapPanel.screenToWorld(mouseX, mouseY, mapX, mapY);
                selectedMapPos = pos;
                mapPanel.setSelectedPos(pos);
                selectedRowPos = null;
                selectedRowKind = null;

                // Clear list selection if any
                selectedIndex = -1;

                // Enable go
                goButton.enabled = true;
                return; // don't fall through to list selection
            }
        }
// Right click on map => start dragging
        if (mouseButton == 1) {
            if (mouseX >= mapX && mouseX < mapX + mapW &&
                    mouseY >= mapY && mouseY < mapY + mapH) {

                draggingMap = true;
                dragStartX = mouseX;
                dragStartY = mouseY;
                dragStartCenter = mapPanel.getCenter();
                return;
            }
        }


        if (mouseButton == 0 && !rows.isEmpty()) {
            if (mouseX >= listX && mouseX < listX + listW &&
                    mouseY >= listY && mouseY < listY + listH) {

                int idx = scrollOffset + (mouseY - listY) / rowH;

                if (idx >= 0 && idx < rows.size()) {
                    selectedIndex = idx;

                    Row r = rows.get(idx);

                    // Clear map-click selection
                    selectedMapPos = null;

                    // Track selected row
                    selectedRowPos = r.pos;
                    selectedRowKind = r.kind;

                    // If village selected: center + highlight it
                    if (r.kind == MsgWorkerGoList.Kind.VILLAGE) {
                        mapPanel.setCenter(r.pos);
                        mapPanel.setSelectedPos(r.pos);
                    } else {
                        // Optional: for homes too (comment if you want village-only)
                        mapPanel.setCenter(r.pos);
                        mapPanel.setSelectedPos(r.pos);
                    }

                    goButton.enabled = true;
                }


            }
        }
    }
    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);

        if (!draggingMap) return;

        // déplacement en pixels
        int dxPx = mouseX - dragStartX;
        int dyPx = mouseY - dragStartY;

        // convertir pixels -> blocks (suivant le zoom)
        int bpp = mapPanel.getZoomBlocksPerPixel();

        // Drag "naturel" :
        // si tu tires la carte vers la droite, la vue doit aller vers la gauche => signe négatif
        int dxBlocks = -dxPx * bpp;
        int dzBlocks = -dyPx * bpp;

        // On repart du centre initial pour éviter accumulation d'erreur
        BlockPos newCenter = dragStartCenter.add(dxBlocks, 0, dzBlocks);
        mapPanel.setCenter(newCenter);
    }
    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);

        // state == bouton relâché (0 gauche, 1 droit)
        if (state == 1 && draggingMap) {
            draggingMap = false;
            dragStartCenter = null;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {

        // Pan step = ~ 32 pixels worth of blocks
        int stepBlocks = mapPanel.getZoomBlocksPerPixel() * 48; // nicer with bigger map


        if (button == panN) { mapPanel.pan(0, -stepBlocks); return; }
        if (button == panS) { mapPanel.pan(0,  stepBlocks); return; }
        if (button == panW) { mapPanel.pan(-stepBlocks, 0); return; }
        if (button == panE) { mapPanel.pan( stepBlocks, 0); return; }

// Zoom
        if (button == zoomOutButton) {
            mapPanel.setZoomBlocksPerPixel(mapPanel.getZoomBlocksPerPixel() + 1);
            return;
        }
        if (button == zoomInButton) {
            mapPanel.setZoomBlocksPerPixel(mapPanel.getZoomBlocksPerPixel() - 1);
            return;
        }

// Recenter
        if (button == recenterButton) {
            mapPanel.setCenter(worker.getPosition());
            return;
        }


        if (button == goButton) {
            if (selectedMapPos != null) {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgWorkerGoToPos(worker.getEntityId(), selectedMapPos)
                );
                mc.player.closeScreen();
                return;
            }

            if (selectedIndex < 0) return;

            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgWorkerGoTo(worker.getEntityId(), selectedIndex)
            );

            mc.player.closeScreen();
            return;
        }


        super.actionPerformed(button);
    }

    private static class Row {
        final MsgWorkerGoList.Kind kind;
        final BlockPos pos;
        final String label;
        final int distance;

        Row(MsgWorkerGoList.Kind kind, BlockPos pos, String label, int distance) {
            this.kind = kind;
            this.pos = pos;
            this.label = label;
            this.distance = distance;
        }
    }
    private void requestVisibleTilesFromServer() {
        // throttle: pas plus que 3 fois/sec
        long now = System.currentTimeMillis();
        if (now - lastTileRequestMs < 300) return;
        lastTileRequestMs = now;

        int bpp = mapPanel.getZoomBlocksPerPixel();
        int halfWBlocks = (mapW / 2) * bpp;
        int halfHBlocks = (mapH / 2) * bpp;

        int minX = mapPanel.getCenter().getX() - halfWBlocks;
        int maxX = mapPanel.getCenter().getX() + halfWBlocks;
        int minZ = mapPanel.getCenter().getZ() - halfHBlocks;
        int maxZ = mapPanel.getCenter().getZ() + halfHBlocks;

        // Convert world coords -> tile coords
        int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16; // 256
        int minTileX = floorDiv(minX, tileBlocks) - 1; // marge
        int maxTileX = floorDiv(maxX, tileBlocks) + 1;
        int minTileZ = floorDiv(minZ, tileBlocks) - 1;
        int maxTileZ = floorDiv(maxZ, tileBlocks) + 1;

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapTiles(minTileX, maxTileX, minTileZ, maxTileZ));
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }
    private boolean isMouseOverMap(int mouseX, int mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW
                && mouseY >= mapY && mouseY < mapY + mapH;
    }

    /** wheel >0 = scroll up, wheel <0 = scroll down */
    private void handleMapWheelZoom(int wheel) {
        if (wheel == 0) return;

        // You can swap these if you prefer the other direction
        // Typical: scroll up = zoom in (less blocks per pixel), scroll down = zoom out
        if (wheel > 0) {
            mapPanel.setZoomBlocksPerPixel(mapPanel.getZoomBlocksPerPixel() - 1);
        } else {
            mapPanel.setZoomBlocksPerPixel(mapPanel.getZoomBlocksPerPixel() + 1);
        }

        // Clamp zoom to sane values to avoid 0 or insane zoom-out
        int bpp = mapPanel.getZoomBlocksPerPixel();
        if (bpp < 1) bpp = 1;
        if (bpp > 16) bpp = 16; // adjust if you want more/less max zoom out
        mapPanel.setZoomBlocksPerPixel(bpp);
    }

}
