package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.worldmap.IWorkerBarracksReceiver;
import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.WorldMapContentPopulator;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.home.MsgAssignWorkerBarrack;
import com.jubitus.travellers.server.net.message.home.MsgDeleteLoneBarrack;
import com.jubitus.travellers.server.net.message.home.MsgRequestWorkerBarracksData;
import com.jubitus.travellers.server.net.message.home.MsgUnassignWorkerBarrack;
import com.jubitus.travellers.server.net.message.home.MsgWorkerBarracksData;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
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

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerBarracks extends GuiScreen implements IWorkerBarracksReceiver, IWorkerMapReceiver {

    private final EntityPlayer player;
    @Nullable private final EntityTravellerWorker worker; // null in leader/global mode
    @Nullable private final GuiScreen returnTo;

    private final List<MsgWorkerBarracksData.BarrackEntry> barracks = new ArrayList<>();
    private final List<MsgWorkerBarracksData.WorkerEntry> allWorkers = new ArrayList<>();
    private final List<MsgWorkerBarracksData.WorkerEntry> unassigned = new ArrayList<>();
    private final List<MsgWorkerBarracksData.WorkerEntry> selectedHench = new ArrayList<>();

    private int selectedBarrack = -1;

    // drag state
    @Nullable private MsgWorkerBarracksData.WorkerEntry dragWorker = null;
    private boolean dragFromAssigned = false;
    private boolean dragging = false;

    private WorldMapController mapController;
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

    private GuiButton backBtn;
    private GuiButton zoomInBtn, zoomOutBtn, recenterBtn;
    private static final int DELETE_ICON_W = 12;

    private int mapX, mapY, mapW, mapH;
    private int listX, listW;
    private int barrackY, barrackH;
    private int unassignedY, unassignedH;
    private int selectedY, selectedH;
    private final int rowH = 14;
    private final int titleH = 10;

    private int barrackScroll = 0, unassignedScroll = 0, selectedScroll = 0;

    @Nullable private String notificationKey = null;
    private long notificationExpireMs = 0L;

    public GuiWorkerBarracks(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
        this.returnTo = null;
    }

    public GuiWorkerBarracks(EntityPlayer player, GuiScreen returnTo) {
        this.player = player;
        this.worker = null;
        this.returnTo = returnTo;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();

        mapW = 180;
        mapH = Math.min(180, this.height - 70);
        listW = 150;

        int totalW = mapW + 12 + listW;
        int startX = Math.max(4, (this.width - totalW) / 2);
        mapX = startX;
        listX = startX + mapW + 12;
        int top = Math.max(14, (this.height - mapH) / 2);
        mapY = top;

        // Right column: three stacked panels (barracks / unassigned / selected)
        int colTop = top;
        int panelGap = 6;
        int avail = mapH - titleH * 3 - panelGap * 2;
        barrackH = Math.max(rowH, avail / 3);
        unassignedH = Math.max(rowH, avail / 3);
        selectedH = Math.max(rowH, avail - barrackH - unassignedH);

        barrackY = colTop + titleH;
        unassignedY = barrackY + barrackH + panelGap + titleH;
        selectedY = unassignedY + unassignedH + panelGap + titleH;

        int bh = 20;
        int buttonsY = mapY + mapH + 6;
        this.backBtn = this.addButton(new GuiButton(0, mapX, buttonsY, 50, bh,
                I18n.format("travellers.gui.back", "Back")));

        // map zoom controls
        this.zoomOutBtn = this.addButton(new GuiButton(2, mapX + 54, buttonsY, 20, bh, "-"));
        this.zoomInBtn = this.addButton(new GuiButton(3, mapX + 76, buttonsY, 20, bh, "+"));
        this.recenterBtn = this.addButton(new GuiButton(4, mapX + 98, buttonsY, 44, bh,
                I18n.format("travellers.map.center", "Center")));

        BlockPos center = (worker != null) ? worker.getPosition() : player.getPosition();
        mapController = new WorldMapController(Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_barracks_map", center);

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestWorkerBarracksData());
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    @Override
    public void receiveBarracksData(MsgWorkerBarracksData data) {
        barracks.clear();
        barracks.addAll(data.barracks);
        allWorkers.clear();
        allWorkers.addAll(data.workers);
        if (selectedBarrack >= barracks.size()) selectedBarrack = -1;
        rebuildWorkerPanels();
        clampScrolls();
        if (mapController != null) rebuildMarkers();
        if (data.notification != null && !data.notification.isEmpty()) {
            notificationKey = data.notification;
            notificationExpireMs = System.currentTimeMillis() + 4000L;
        }
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        workerEntries.clear();
        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        for (MsgMapWorkersUpdate.Entry e : list) {
            if (e.dim != myDim) continue;
            workerEntries.add(e);
            if (playerOwnerColor == 0xFFFFFF && e.ownerColor != 0) playerOwnerColor = e.ownerColor;
        }
        if (mapController != null) rebuildMarkers();
    }

    private void rebuildWorkerPanels() {
        unassigned.clear();
        selectedHench.clear();
        BlockPos selCenter = (selectedBarrack >= 0 && selectedBarrack < barracks.size())
                ? barracks.get(selectedBarrack).center : null;
        java.util.Set<BlockPos> knownCenters = new java.util.HashSet<>();
        for (MsgWorkerBarracksData.BarrackEntry b : barracks) knownCenters.add(b.center);
        for (MsgWorkerBarracksData.WorkerEntry w : allWorkers) {
            if (w.assignedCenter == null || !knownCenters.contains(w.assignedCenter)) {
                unassigned.add(w);
            } else if (selCenter != null && w.assignedCenter.equals(selCenter)) {
                selectedHench.add(w);
            }
        }
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();
        for (int i = 0; i < barracks.size(); i++) {
            MsgWorkerBarracksData.BarrackEntry b = barracks.get(i);
            if (i == selectedBarrack) {
                mapController.panel.addMarker(WorldMapMarkerFactory.villageHighlighted(b.center, b.name));
            } else {
                mapController.panel.addMarker(WorldMapMarkerFactory.home(b.center, b.name, playerOwnerColor));
            }
        }
        WorldMapContentPopulator.populate(mapController.panel,
                Collections.emptyList(), workerEntries, player, playerOwnerColor);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int wheel = Mouse.getDWheel();
        if (wheel != 0) handleWheel(mouseX, mouseY, wheel);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        if (mapController != null) {
            TravellersGuiTheme.drawMapFrame(mapX, mapY, mapW, mapH);
            rebuildMarkers();
            mapController.draw();
        }

        drawBarrackList(mouseX, mouseY);
        drawWorkerPanel(I18n.format("travellers.worker.barracks.unassigned", "Unassigned Henchmen"),
                unassigned, unassignedY, unassignedH, unassignedScroll, mouseX, mouseY);
        drawWorkerPanel(I18n.format("travellers.worker.barracks.selected", "Selected Barrack Henchmen"),
                selectedHench, selectedY, selectedH, selectedScroll, mouseX, mouseY);

        // hint line under the selected panel
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.barracks.dragdrop", "Drag & drop to assign/unassign"),
                listX, selectedY + selectedH + 3, TravellersGuiTheme.COL_TEXT_DIM);

        drawNotification();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty())
                drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
        }

        // dragged worker ghost under the cursor
        if (dragging && dragWorker != null) {
            WorkerHeadRenderer.draw(dragWorker.cultureId, dragWorker.textureIndex, dragWorker.ownerColor,
                    mouseX + 6, mouseY - 8, 16);
            this.fontRenderer.drawString(trim(dragWorker.name, 90), mouseX + 24, mouseY - 4,
                    TravellersGuiTheme.COL_TEXT_HOV);
        }
    }

    private void drawBarrackList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawTitle(this.fontRenderer,
                I18n.format("travellers.worker.barracks.list", "Barracks"), listX, barrackY - titleH);
        TravellersGuiTheme.drawInnerPanel(listX - 2, barrackY - 2, listW + 4, barrackH + 4);

        int visible = Math.min(barrackH / rowH, barracks.size() - barrackScroll);
        for (int i = 0; i < visible; i++) {
            int idx = barrackScroll + i;
            int rowTop = barrackY + i * rowH;
            boolean hovered = inRect(mouseX, mouseY, listX, rowTop, listW, rowH);
            boolean sel = idx == selectedBarrack;
            TravellersGuiTheme.drawListRow(listX, rowTop, listW, rowH, hovered, sel);
            MsgWorkerBarracksData.BarrackEntry be = barracks.get(idx);
            String label = be.name + " (" + be.assigned + "/" + be.beds + ")";
            this.fontRenderer.drawString(trim(label, listW - DELETE_ICON_W - 6), listX + 3, rowTop + 3,
                    hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
            // delete icon (red X) at the right edge of the row
            boolean overIcon = inRect(mouseX, mouseY, listX + listW - DELETE_ICON_W, rowTop, DELETE_ICON_W, rowH);
            this.fontRenderer.drawString("X", listX + listW - DELETE_ICON_W + 3, rowTop + 3,
                    overIcon ? 0xFFFF5555 : 0xFFAA4444);
        }
    }

    private void drawWorkerPanel(String title, List<MsgWorkerBarracksData.WorkerEntry> list,
                                 int panelY, int panelH, int scroll, int mouseX, int mouseY) {
        TravellersGuiTheme.drawTitle(this.fontRenderer, title, listX, panelY - titleH);
        TravellersGuiTheme.drawInnerPanel(listX - 2, panelY - 2, listW + 4, panelH + 4);

        int visible = Math.min(panelH / rowH, list.size() - scroll);
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            MsgWorkerBarracksData.WorkerEntry w = list.get(idx);
            int rowTop = panelY + i * rowH;
            boolean hovered = inRect(mouseX, mouseY, listX, rowTop, listW, rowH);
            boolean drag = dragging && dragWorker == w;
            TravellersGuiTheme.drawListRow(listX, rowTop, listW, rowH, hovered, drag);
            WorkerHeadRenderer.draw(w.cultureId, w.textureIndex, w.ownerColor, listX + 1, rowTop - 1, 16);
            this.fontRenderer.drawString(trim(w.name, listW - 22), listX + 20, rowTop + 3,
                    hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
        }
    }

    private void drawNotification() {
        if (notificationKey == null) return;
        if (System.currentTimeMillis() > notificationExpireMs) { notificationKey = null; return; }
        String text = I18n.format(notificationKey);
        int textW = this.fontRenderer.getStringWidth(text);
        int totalW = mapW + 12 + listW;
        int startX = Math.max(4, (this.width - totalW) / 2);
        int notifW = Math.max(textW + 12, totalW);
        int notifX = startX + totalW / 2 - notifW / 2;
        int notifY = mapY - 14;
        drawRect(notifX, notifY, notifX + notifW, notifY + 12, 0xCC660000);
        this.fontRenderer.drawString(text, notifX + (notifW - textW) / 2, notifY + 2, 0xFFFF6666);
    }

    private String trim(String s, int maxWidth) {
        if (s == null) s = "";
        return this.fontRenderer.trimStringToWidth(s, maxWidth);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;

        // barrack list: delete-icon click takes priority over selection
        int bIdx = rowAt(mouseX, mouseY, barrackY, barrackH, barrackScroll, barracks.size());
        if (bIdx >= 0 && mouseX >= listX + listW - DELETE_ICON_W) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgDeleteLoneBarrack(barracks.get(bIdx).center));
            if (selectedBarrack == bIdx) selectedBarrack = -1;
            TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestWorkerBarracksData());
            return;
        }
        if (bIdx >= 0) {
            selectedBarrack = bIdx;
            rebuildWorkerPanels();
            clampScrolls();
            if (mapController != null) {
                mapController.setCenter(barracks.get(bIdx).center);
                rebuildMarkers();
            }
            return;
        }

        // begin drag from a worker panel
        int uIdx = rowAt(mouseX, mouseY, unassignedY, unassignedH, unassignedScroll, unassigned.size());
        if (uIdx >= 0) {
            dragWorker = unassigned.get(uIdx);
            dragFromAssigned = false;
            dragging = true;
            return;
        }
        int sIdx = rowAt(mouseX, mouseY, selectedY, selectedH, selectedScroll, selectedHench.size());
        if (sIdx >= 0) {
            dragWorker = selectedHench.get(sIdx);
            dragFromAssigned = true;
            dragging = true;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (!dragging && mapController != null) mapController.onMouseDrag(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (mapController != null) mapController.onMouseRelease(state);

        if (dragging && dragWorker != null) {
            boolean overUnassigned = inRect(mouseX, mouseY, listX, unassignedY, listW, unassignedH);
            boolean overSelected = inRect(mouseX, mouseY, listX, selectedY, listW, selectedH);

            if (dragFromAssigned && overUnassigned) {
                TravellerFollowNet.CHANNEL.sendToServer(new MsgUnassignWorkerBarrack(dragWorker.uuid));
            } else if (!dragFromAssigned && overSelected && selectedBarrack >= 0) {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgAssignWorkerBarrack(dragWorker.uuid, barracks.get(selectedBarrack).center));
            }
        }
        dragging = false;
        dragWorker = null;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backBtn) {
            if (returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else if (worker != null) {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            } else {
                mc.player.closeScreen();
            }
            return;
        }
        if (button == zoomInBtn) {
            mapController.handleWheel(mapX + mapW / 2, mapY + mapH / 2, 1);
            return;
        }
        if (button == zoomOutBtn) {
            mapController.handleWheel(mapX + mapW / 2, mapY + mapH / 2, -1);
            return;
        }
        if (button == recenterBtn) {
            BlockPos c = (worker != null) ? worker.getPosition() : player.getPosition();
            mapController.setCenter(c);
            return;
        }
        super.actionPerformed(button);
    }

    private void handleWheel(int mouseX, int mouseY, int wheel) {
        if (mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (inRect(mouseX, mouseY, listX, barrackY, listW, barrackH)) {
            barrackScroll += wheel > 0 ? -1 : 1;
            clampScrolls();
        } else if (inRect(mouseX, mouseY, listX, unassignedY, listW, unassignedH)) {
            unassignedScroll += wheel > 0 ? -1 : 1;
            clampScrolls();
        } else if (inRect(mouseX, mouseY, listX, selectedY, listW, selectedH)) {
            selectedScroll += wheel > 0 ? -1 : 1;
            clampScrolls();
        }
    }

    private void clampScrolls() {
        barrackScroll = clamp(barrackScroll, barracks.size(), barrackH);
        unassignedScroll = clamp(unassignedScroll, unassigned.size(), unassignedH);
        selectedScroll = clamp(selectedScroll, selectedHench.size(), selectedH);
    }

    private int clamp(int scroll, int count, int panelH) {
        int max = Math.max(0, count - panelH / rowH);
        if (scroll < 0) return 0;
        return Math.min(scroll, max);
    }

    /** Index in the panel of the row under (mouseX,mouseY), or -1. */
    private int rowAt(int mouseX, int mouseY, int panelY, int panelH, int scroll, int count) {
        if (!inRect(mouseX, mouseY, listX, panelY, listW, panelH)) return -1;
        int idx = scroll + (mouseY - panelY) / rowH;
        return (idx >= 0 && idx < count) ? idx : -1;
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
