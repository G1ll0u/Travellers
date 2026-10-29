package com.jubitus.travellers.zone.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.worldmap.gui.WorkerMapView;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerBarracksReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapScreen;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerZoneDutyReceiver;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.worldmap.net.MsgSubscribeWorkerMap;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.zone.net.MsgAssignWorkerBarrack;
import com.jubitus.travellers.zone.net.MsgAssignWorkerDuty;
import com.jubitus.travellers.zone.net.MsgDeletePlayerZone;
import com.jubitus.travellers.zone.net.MsgRequestWorkerBarracksData;
import com.jubitus.travellers.zone.net.MsgRequestWorkerZoneData;
import com.jubitus.travellers.zone.net.MsgUnassignWorkerBarrack;
import com.jubitus.travellers.zone.net.MsgUnassignWorkerDuty;
import com.jubitus.travellers.zone.net.MsgWorkerBarracksData;
import com.jubitus.travellers.zone.net.MsgWorkerZoneData;
import com.jubitus.travellers.worldmap.net.MsgRequestMapDiscovery;
import com.jubitus.travellers.zone.ZoneType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.jubitus.travellers.gui.WorkerHeadRenderer;

/**
 * Single zone-assignment GUI: one merged list of the player's Barracks and Stables. Dragging a
 * worker onto a Barrack row assigns it a home ({@link MsgAssignWorkerBarrack}); onto a Stable row
 * assigns it Stable duty ({@link MsgAssignWorkerDuty}), which also makes that Stable's bed the
 * worker's home. Each row carries its {@link ZoneType} so the icon and the outgoing message follow
 * from it. Both server snapshots ({@link MsgWorkerBarracksData} for Barracks, {@link MsgWorkerZoneData}
 * for Stables) are kept as-is and merged here client-side. The server re-validates every assignment
 * independently.
 */
@SideOnly(Side.CLIENT)
public class GuiWorkerBarracks extends GuiScreen
        implements IWorkerBarracksReceiver, IWorkerZoneDutyReceiver, IWorkerGoListReceiver, IWorkerMapScreen, ITravellersGui {

    private static final ResourceLocation TEX_HOME =
            new ResourceLocation("travellers", "textures/map/marker_home.png");
    private static final ResourceLocation TEX_STABLE =
            new ResourceLocation("travellers", "textures/map/marker_stable.png");

    /** A Barrack or Stable row in the merged left list. */
    private static final class ZoneRow {
        final BlockPos center;
        final String name;
        final String capacityLabel;
        final ZoneType type;

        ZoneRow(BlockPos center, String name, String capacityLabel, ZoneType type) {
            this.center = center;
            this.name = name;
            this.capacityLabel = capacityLabel;
            this.type = type;
        }
    }

    private static final class WorkerRow {
        final UUID uuid;
        final String name;
        final String cultureId;
        final int textureIndex;
        final int ownerColor;
        @Nullable final BlockPos assignedCenter;
        @Nullable final ZoneType assignedType; // null = unassigned
        final boolean grouped;
        @Nullable final BlockPos pos;

        WorkerRow(UUID uuid, String name, String cultureId, int textureIndex, int ownerColor,
                  @Nullable BlockPos assignedCenter, @Nullable ZoneType assignedType,
                  boolean grouped, @Nullable BlockPos pos) {
            this.uuid = uuid;
            this.name = name;
            this.cultureId = cultureId;
            this.textureIndex = textureIndex;
            this.ownerColor = ownerColor;
            this.assignedCenter = assignedCenter;
            this.assignedType = assignedType;
            this.grouped = grouped;
            this.pos = pos;
        }
    }

    private final EntityPlayer player;
    @Nullable private final EntityTravellerWorker worker; // null in leader/global mode
    @Nullable private final GuiScreen returnTo;

    // Raw server data.
    private final List<MsgWorkerBarracksData.BarrackEntry> barracks = new ArrayList<>();
    private final List<MsgWorkerBarracksData.WorkerEntry> barrackWorkers = new ArrayList<>();
    private final List<MsgWorkerZoneData.StableEntry> stables = new ArrayList<>();
    private final List<MsgWorkerZoneData.WorkerEntry> zoneWorkers = new ArrayList<>();

    // Merged rows.
    private final List<ZoneRow> zoneRows = new ArrayList<>();
    private final List<WorkerRow> unassigned = new ArrayList<>();
    private final List<WorkerRow> selectedHench = new ArrayList<>();

    private int selectedZoneIndex = -1;
    // The first zone is selected once, when both snapshots have arrived (barracks come first in the list).
    private boolean barracksReceived, stablesReceived, firstZoneSelected;

    // Fixed reference for the nearest-first unassigned sort (captured at open, never re-taken).
    private BlockPos sortAnchor;

    // drag state
    @Nullable private WorkerRow dragWorker = null;
    private boolean dragFromAssigned = false;
    private boolean dragging = false;

    private WorkerMapView map;
    private MapListLayout layout;

    private GuiButton backBtn;
    private static final int DELETE_ICON_W = 12;
    private static final int HEADER_H = 14;
    private static final int PANEL_GAP = 6;

    private int listX, listW;
    private final int rowH = 14;
    private final ScrollList zoneList = new ScrollList(rowH);
    private final ScrollList unassignedList = new ScrollList(rowH);
    private final ScrollList selectedList = new ScrollList(rowH);
    // Outer boxes of the three stacked panels (x is shared): {y, h}.
    private final int[] zoneBox = new int[2], unassignedBox = new int[2], selectedBox = new int[2];

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
    public WorkerMapView mapView() {
        return map;
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        map.data.setPlaces(entries);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();

        this.sortAnchor = (worker != null) ? worker.getPosition() : player.getPosition();

        layout = new MapListLayout(width, height, 190, 1);
        listX = layout.listX();
        listW = layout.listW();

        // Right column: three stacked boxes. The zone list is small (few zones); the two worker
        // boxes take the rest so many rows are visible at once.
        int avail = layout.listBoxH - PANEL_GAP * 2;
        int zoneH = Math.max(HEADER_H + rowH * 3 + 2, avail * 2 / 7);
        int unassignedH = Math.max(HEADER_H + rowH * 3 + 2, (avail - zoneH) / 2);
        int selectedH = Math.max(HEADER_H + rowH * 2 + 2, avail - zoneH - unassignedH);
        zoneBox[0] = layout.listBoxY;
        zoneBox[1] = zoneH;
        unassignedBox[0] = zoneBox[0] + zoneH + PANEL_GAP;
        unassignedBox[1] = unassignedH;
        selectedBox[0] = unassignedBox[0] + unassignedH + PANEL_GAP;
        selectedBox[1] = selectedH;
        bindList(zoneList, zoneBox);
        bindList(unassignedList, unassignedBox);
        bindList(selectedList, selectedBox);

        this.backBtn = this.addButton(new GuiTravellersButton(0, layout.sideButtonX(0, 1), layout.sideRowY(0), layout.sideButtonW(1),
                MapListLayout.BTN_H, I18n.format("travellers.gui.back", "Back")));

        BlockPos center = (worker != null) ? worker.getPosition() : player.getPosition();
        map = new WorkerMapView(layout.mapX(), layout.mapY(), layout.mapW(), layout.mapH(), "travellers_barracks_map", center);
        map.addNav(buttonList, 2, () -> worker != null ? worker.getPosition() : player.getPosition(), layout.navRowY());

        map.subscribe();
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(-1));
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestWorkerBarracksData());
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestWorkerZoneData());

        rebuildWorkerPanels();
    }

    /** Rows live under the box header, inside the 1px border. */
    private void bindList(ScrollList l, int[] box) {
        l.setBounds(listX, box[0] + 1 + HEADER_H, listW, box[1] - 2 - HEADER_H);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        map.unsubscribe();
    }

    @Override
    public void receiveBarracksData(MsgWorkerBarracksData data) {
        barracksReceived = true;
        barracks.clear();
        barracks.addAll(data.barracks);
        barrackWorkers.clear();
        barrackWorkers.addAll(data.workers);
        if (selectedZoneIndex >= zoneRows.size()) selectedZoneIndex = -1;
        rebuildWorkerPanels();
        selectFirstZoneOnce();
        clampScrolls();
        refreshSelection();
        if (data.notification != null && !data.notification.isEmpty()) {
            notificationKey = data.notification;
            notificationExpireMs = System.currentTimeMillis() + 4000L;
        }
    }

    @Override
    public void receiveZoneDutyData(MsgWorkerZoneData data) {
        stablesReceived = true;
        stables.clear();
        stables.addAll(data.stables);
        zoneWorkers.clear();
        zoneWorkers.addAll(data.workers);
        if (selectedZoneIndex >= zoneRows.size()) selectedZoneIndex = -1;
        rebuildWorkerPanels();
        selectFirstZoneOnce();
        clampScrolls();
        refreshSelection();
        if (data.notification != null && !data.notification.isEmpty()) {
            notificationKey = data.notification;
            notificationExpireMs = System.currentTimeMillis() + 4000L;
        }
    }

    private void selectFirstZoneOnce() {
        if (firstZoneSelected || !barracksReceived || !stablesReceived) return;
        firstZoneSelected = true;
        if (zoneRows.isEmpty()) return;
        selectedZoneIndex = 0;
        rebuildWorkerPanels();
        map.controller.setCenter(zoneRows.get(0).center);
    }

    private void rebuildWorkerPanels() {
        zoneRows.clear();
        unassigned.clear();
        selectedHench.clear();

        for (MsgWorkerBarracksData.BarrackEntry b : barracks) {
            zoneRows.add(new ZoneRow(b.center,
                    (b.name == null || b.name.isEmpty()) ? "Barrack" : b.name,
                    b.assigned + "/" + b.beds, ZoneType.BARRACK));
        }
        for (MsgWorkerZoneData.StableEntry s : stables) {
            zoneRows.add(new ZoneRow(s.center,
                    (s.name == null || s.name.isEmpty()) ? "Stable" : s.name,
                    s.assigned + "/" + s.capacity, ZoneType.STABLE));
        }

        java.util.Set<BlockPos> knownCenters = new java.util.HashSet<>();
        for (ZoneRow z : zoneRows) knownCenters.add(z.center);

        // Merge workers by UUID. A worker is assigned to a Stable if the Duty snapshot says so;
        // otherwise to a Barrack if the Home snapshot says so; otherwise unassigned.
        Map<UUID, WorkerRow> merged = new LinkedHashMap<>();
        for (MsgWorkerZoneData.WorkerEntry w : zoneWorkers) {
            BlockPos ac = (w.assignedCenter != null && knownCenters.contains(w.assignedCenter)) ? w.assignedCenter : null;
            merged.put(w.uuid, new WorkerRow(w.uuid, w.name, w.cultureId, w.textureIndex, w.ownerColor,
                    ac, ac != null ? ZoneType.STABLE : null, w.grouped, w.pos));
        }
        for (MsgWorkerBarracksData.WorkerEntry w : barrackWorkers) {
            WorkerRow existing = merged.get(w.uuid);
            BlockPos ac = (w.assignedCenter != null && knownCenters.contains(w.assignedCenter)) ? w.assignedCenter : null;
            if (existing == null) {
                merged.put(w.uuid, new WorkerRow(w.uuid, w.name, w.cultureId, w.textureIndex, w.ownerColor,
                        ac, ac != null ? ZoneType.BARRACK : null, false, w.pos));
            } else if (existing.assignedType == null && ac != null) {
                merged.put(w.uuid, new WorkerRow(w.uuid, existing.name, existing.cultureId, existing.textureIndex,
                        existing.ownerColor, ac, ZoneType.BARRACK, existing.grouped,
                        existing.pos != null ? existing.pos : w.pos));
            }
        }

        BlockPos selCenter = (selectedZoneIndex >= 0 && selectedZoneIndex < zoneRows.size())
                ? zoneRows.get(selectedZoneIndex).center : null;
        for (WorkerRow w : merged.values()) {
            if (w.assignedType == null) {
                unassigned.add(w);
            } else if (selCenter != null && selCenter.equals(w.assignedCenter)) {
                selectedHench.add(w);
            }
        }

        // Nearest-first, using the anchor captured when the GUI opened (never re-sorted as the
        // player walks around). Rows with no known position sort last.
        unassigned.sort((a, b) -> Long.compare(distSq(a.pos), distSq(b.pos)));
    }

    private long distSq(@Nullable BlockPos p) {
        if (p == null || sortAnchor == null) return Long.MAX_VALUE;
        long dx = p.getX() - sortAnchor.getX();
        long dz = p.getZ() - sortAnchor.getZ();
        return dx * dx + dz * dz;
    }

    private void refreshSelection() {
        map.setSelected(selectedZoneIndex >= 0 && selectedZoneIndex < zoneRows.size()
                ? zoneRows.get(selectedZoneIndex).center : null);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        TravellersGuiTheme.drawPanel(layout.frameX, layout.frameY, layout.frameW, layout.frameH);
        MapListLayout.drawTitle(fontRenderer, layout.frameX, layout.frameW, layout.frameY,
                I18n.format("travellers.worker.barracks.title"));
        String hint = I18n.format("travellers.worker.barracks.dragdrop", "Drag & drop to assign/unassign");
        fontRenderer.drawString(hint, layout.frameX + layout.frameW - MapListLayout.PAD - fontRenderer.getStringWidth(hint),
                layout.frameY + (MapListLayout.TITLE_H - fontRenderer.FONT_HEIGHT) / 2 + 1, TravellersGuiTheme.COL_TEXT_DIM);
        TravellersGuiTheme.drawInnerPanel(layout.mapBoxX, layout.mapBoxY, layout.mapBoxW, layout.mapBoxH);
        map.draw(player, ClientOwnerVisualsCache.getColor(), dragging ? -1 : mouseX, dragging ? -1 : mouseY);

        drawZoneList(mouseX, mouseY);
        drawWorkerPanel(I18n.format("travellers.worker.zones.unassigned_any", "Unassigned Henchmen"),
                unassigned, unassignedList, unassignedBox, mouseX, mouseY);
        drawWorkerPanel(I18n.format("travellers.worker.zones.assigned_here", "Assigned Here"),
                selectedHench, selectedList, selectedBox, mouseX, mouseY);

        drawNotification();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (!dragging) map.drawTooltip(this, mouseX, mouseY);

        if (dragging && dragWorker != null) {
            WorkerHeadRenderer.draw(dragWorker.cultureId, dragWorker.textureIndex, dragWorker.ownerColor,
                    mouseX + 6, mouseY - 8, 16);
            this.fontRenderer.drawString(trim(dragWorker.name, 90), mouseX + 24, mouseY - 4,
                    TravellersGuiTheme.COL_TEXT_HOV);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backBtn) {
            if (returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else if (worker != null) {
                TravellersNetwork.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            } else {
                mc.player.closeScreen();
            }
            return;
        }
        if (map.handleButton(button)) return;
        super.actionPerformed(button);
    }

    private void drawBoxHeader(String title, int[] box) {
        fontRenderer.drawString(TravellersGuiTheme.trimLabel(fontRenderer, title, listW - 6), listX + 3,
                box[0] + 1 + (HEADER_H - fontRenderer.FONT_HEIGHT) / 2 + 1, TravellersGuiTheme.COL_TEXT);
        TravellersGuiTheme.drawSeparator(listX, box[0] + HEADER_H, listW);
    }

    private void drawZoneList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(layout.listBoxX, zoneBox[0], layout.listBoxW, zoneBox[1]);
        drawBoxHeader(I18n.format("travellers.worker.zones.list_zones", "Barracks & Stables"), zoneBox);

        zoneList.clamp(zoneRows.size());
        int rowW = zoneList.rowW(zoneRows.size());
        int visible = zoneList.rowsToDraw(zoneRows.size());
        for (int i = 0; i < visible; i++) {
            int idx = zoneList.scroll() + i;
            int rowTop = zoneList.rowTop(idx);
            boolean hovered = zoneList.isRowHovered(idx, mouseX, mouseY) && mouseX < zoneList.x + rowW;
            boolean sel = idx == selectedZoneIndex;
            TravellersGuiTheme.drawListRow(listX, rowTop, rowW, rowH, hovered, sel);
            ZoneRow z = zoneRows.get(idx);
            drawZoneIcon(z.type, listX + 1, rowTop - 1, 14);
            String label = z.name + " (" + z.capacityLabel + ")";
            fontRenderer.drawString(trim(label, rowW - DELETE_ICON_W - 20), listX + 16, zoneList.textY(fontRenderer, rowTop),
                    hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
            int delX = listX + rowW - DELETE_ICON_W;
            boolean overIcon = inRect(mouseX, mouseY, delX, rowTop, DELETE_ICON_W, rowH);
            fontRenderer.drawString("X", delX + 3, zoneList.textY(fontRenderer, rowTop), overIcon ? 0xFFFF5555 : 0xFFAA4444);
        }
        zoneList.drawScrollbar(zoneRows.size());

        // How to make a zone, in the room the rows leave free.
        int y = zoneList.rowTop(zoneList.scroll() + visible) + 4;
        int bottom = zoneBox[0] + zoneBox[1] - 2;
        for (String line : fontRenderer.listFormattedStringToWidth(I18n.format("travellers.worker.zones.create_hint"), listW - 8)) {
            if (y + fontRenderer.FONT_HEIGHT > bottom) break;
            fontRenderer.drawString(line, listX + 4, y, TravellersGuiTheme.COL_TEXT_DIM);
            y += fontRenderer.FONT_HEIGHT + 1;
        }
    }

    private void drawZoneIcon(ZoneType type, int x, int y, int size) {
        int c = ClientOwnerVisualsCache.getColor() & 0xFFFFFF;
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        mc.getTextureManager().bindTexture(type == ZoneType.STABLE ? TEX_STABLE : TEX_HOME);
        GlStateManager.color(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, 1f);
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, size, size, size, size);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void drawWorkerPanel(String title, List<WorkerRow> rows, ScrollList panel, int[] box, int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(layout.listBoxX, box[0], layout.listBoxW, box[1]);
        drawBoxHeader(title, box);

        panel.clamp(rows.size());
        int rowW = panel.rowW(rows.size());
        int visible = panel.rowsToDraw(rows.size());
        for (int i = 0; i < visible; i++) {
            int idx = panel.scroll() + i;
            WorkerRow w = rows.get(idx);
            int rowTop = panel.rowTop(idx);
            boolean hovered = panel.isRowHovered(idx, mouseX, mouseY) && mouseX < panel.x + rowW;
            boolean drag = dragging && dragWorker == w;
            TravellersGuiTheme.drawListRow(listX, rowTop, rowW, rowH, hovered, drag);
            WorkerHeadRenderer.draw(w.cultureId, w.textureIndex, w.ownerColor, listX + 1, rowTop - 1, 16);
            int color = hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
            String label = w.name;
            if (w.assignedType == ZoneType.STABLE) label = "[S] " + label;
            else if (w.assignedType == ZoneType.BARRACK) label = "[H] " + label;
            fontRenderer.drawString(trim(label, rowW - 22), listX + 20, panel.textY(fontRenderer, rowTop), color);
        }
        panel.drawScrollbar(rows.size());
    }

    private void drawNotification() {
        if (notificationKey == null) return;
        if (System.currentTimeMillis() > notificationExpireMs) { notificationKey = null; return; }
        String text = fontRenderer.trimStringToWidth(I18n.format(notificationKey), layout.mapW() - 12);
        int textW = fontRenderer.getStringWidth(text);
        int notifX = layout.mapX() + (layout.mapW() - textW - 12) / 2;
        int notifY = layout.mapY() + 3;
        drawRect(notifX, notifY, notifX + textW + 12, notifY + 12, 0xCC660000);
        fontRenderer.drawString(text, notifX + 6, notifY + 2, 0xFFFF6666);
    }

    private String trim(String s, int maxWidth) {
        if (s == null) s = "";
        return this.fontRenderer.trimStringToWidth(s, maxWidth);
    }

    private void refreshAll() {
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestWorkerBarracksData());
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestWorkerZoneData());
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (map.mouseDown(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        if (zoneList.mouseDown(mouseX, mouseY, zoneRows.size())
                || unassignedList.mouseDown(mouseX, mouseY, unassigned.size())
                || selectedList.mouseDown(mouseX, mouseY, selectedHench.size())) return;

        int bIdx = zoneList.rowAt(mouseX, mouseY, zoneRows.size());
        if (bIdx >= 0 && mouseX >= listX + zoneList.rowW(zoneRows.size()) - DELETE_ICON_W) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgDeletePlayerZone(zoneRows.get(bIdx).center));
            if (selectedZoneIndex == bIdx) selectedZoneIndex = -1;
            refreshAll();
            return;
        }
        if (bIdx >= 0) {
            selectedZoneIndex = bIdx;
            rebuildWorkerPanels();
            clampScrolls();
            map.controller.setCenter(zoneRows.get(bIdx).center);
            refreshSelection();
            return;
        }

        int uIdx = unassignedList.rowAt(mouseX, mouseY, unassigned.size());
        if (uIdx >= 0) {
            dragWorker = unassigned.get(uIdx);
            dragFromAssigned = false;
            dragging = true;
            return;
        }
        int sIdx = selectedList.rowAt(mouseX, mouseY, selectedHench.size());
        if (sIdx >= 0) {
            dragWorker = selectedHench.get(sIdx);
            dragFromAssigned = true;
            dragging = true;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (!dragging) map.mouseDrag(mouseX, mouseY);
        zoneList.mouseDrag(mouseY, zoneRows.size());
        unassignedList.mouseDrag(mouseY, unassigned.size());
        selectedList.mouseDrag(mouseY, selectedHench.size());
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        map.mouseUp(state);
        zoneList.mouseUp();
        unassignedList.mouseUp();
        selectedList.mouseUp();

        if (dragging && dragWorker != null) {
            boolean overUnassigned = unassignedList.isOver(mouseX, mouseY);
            boolean overSelected = selectedList.isOver(mouseX, mouseY);

            if (dragFromAssigned && overUnassigned) {
                // Unassign: route by what the worker is currently assigned to.
                if (dragWorker.assignedType == ZoneType.STABLE) {
                    TravellersNetwork.CHANNEL.sendToServer(new MsgUnassignWorkerDuty(dragWorker.uuid));
                } else if (dragWorker.assignedType == ZoneType.BARRACK) {
                    TravellersNetwork.CHANNEL.sendToServer(new MsgUnassignWorkerBarrack(dragWorker.uuid));
                }
                refreshAll();
            } else if (!dragFromAssigned && overSelected && selectedZoneIndex >= 0
                    && selectedZoneIndex < zoneRows.size()) {
                ZoneRow z = zoneRows.get(selectedZoneIndex);
                if (z.type == ZoneType.STABLE) {
                    TravellersNetwork.CHANNEL.sendToServer(new MsgAssignWorkerDuty(dragWorker.uuid, z.center));
                } else {
                    TravellersNetwork.CHANNEL.sendToServer(new MsgAssignWorkerBarrack(dragWorker.uuid, z.center));
                }
                refreshAll();
            }
        }
        dragging = false;
        dragWorker = null;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        handleWheel(Mouse.getEventX() * width / mc.displayWidth, height - Mouse.getEventY() * height / mc.displayHeight - 1, wheel);
    }

    private void handleWheel(int mouseX, int mouseY, int wheel) {
        if (map.isMouseOver(mouseX, mouseY)) {
            map.wheel(mouseX, mouseY, wheel);
        } else if (zoneList.isOver(mouseX, mouseY)) {
            zoneList.scrollBy(wheel, zoneRows.size());
        } else if (unassignedList.isOver(mouseX, mouseY)) {
            unassignedList.scrollBy(wheel, unassigned.size());
        } else if (selectedList.isOver(mouseX, mouseY)) {
            selectedList.scrollBy(wheel, selectedHench.size());
        }
    }

    private void clampScrolls() {
        zoneList.clamp(zoneRows.size());
        unassignedList.clamp(unassigned.size());
        selectedList.clamp(selectedHench.size());
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
