package com.jubitus.travellers.zone.gui;

import com.jubitus.travellers.client.gui.GuiTravellersButton;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.worldmap.WorldMapContentPopulator;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerBarracksReceiver;
import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerZoneDutyReceiver;
import com.jubitus.travellers.client.marker.ClientOwnerVisualsCache;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.server.map.stream.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.stream.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
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
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
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
        implements IWorkerBarracksReceiver, IWorkerZoneDutyReceiver, IWorkerMapReceiver, ITravellersGui {

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

    // Fixed reference for the nearest-first unassigned sort (captured at open, never re-taken).
    private BlockPos sortAnchor;

    // drag state
    @Nullable private WorkerRow dragWorker = null;
    private boolean dragFromAssigned = false;
    private boolean dragging = false;

    private WorldMapController mapController;
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();

    private GuiButton backBtn;
    private GuiButton zoomInBtn, zoomOutBtn, recenterBtn;
    private static final int DELETE_ICON_W = 12;
    private static final int SCROLLBAR_W = 3;

    private int mapX, mapY, mapW, mapH;
    private int listX, listW;
    private int zoneListY, zoneListH;
    private int unassignedY, unassignedH;
    private int selectedY, selectedH;
    private final int rowH = 14;
    private final int titleH = 10;

    private int zoneScroll = 0, unassignedScroll = 0, selectedScroll = 0;

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

        this.sortAnchor = (worker != null) ? worker.getPosition() : player.getPosition();

        mapW = 180;
        mapH = Math.min(210, this.height - 60);
        listW = 150;

        int totalW = mapW + 12 + listW;
        int startX = Math.max(4, (this.width - totalW) / 2);
        mapX = startX;
        listX = startX + mapW + 12;
        int top = Math.max(14, (this.height - mapH) / 2);
        mapY = top;

        // Right column: three stacked panels. The zone list is small (few zones); the two worker
        // panels take the rest so many rows are visible at once.
        int colTop = top;
        int panelGap = 6;
        int avail = (mapY + mapH) - colTop - titleH * 3 - panelGap * 2;
        zoneListH = Math.max(rowH * 3, avail * 2 / 7);
        unassignedH = Math.max(rowH * 3, (avail - zoneListH) / 2);
        selectedH = Math.max(rowH * 2, avail - zoneListH - unassignedH);

        zoneListY = colTop + titleH;
        unassignedY = zoneListY + zoneListH + panelGap + titleH;
        selectedY = unassignedY + unassignedH + panelGap + titleH;

        int bh = 20;
        int buttonsY = mapY + mapH + 6;
        this.backBtn = this.addButton(new GuiTravellersButton(0, mapX, buttonsY, 50, bh,
                I18n.format("travellers.gui.back", "Back")));

        this.zoomOutBtn = this.addButton(new GuiTravellersButton(2, mapX + 54, buttonsY, 20, bh, "-"));
        this.zoomInBtn = this.addButton(new GuiTravellersButton(3, mapX + 76, buttonsY, 20, bh, "+"));
        this.recenterBtn = this.addButton(new GuiTravellersButton(4, mapX + 98, buttonsY, 44, bh,
                I18n.format("travellers.map.center", "Center")));

        BlockPos center = (worker != null) ? worker.getPosition() : player.getPosition();
        mapController = new WorldMapController(Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_barracks_map", center);

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestWorkerBarracksData());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestWorkerZoneData());

        rebuildWorkerPanels();
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
        barrackWorkers.clear();
        barrackWorkers.addAll(data.workers);
        if (selectedZoneIndex >= zoneRows.size()) selectedZoneIndex = -1;
        rebuildWorkerPanels();
        clampScrolls();
        if (mapController != null) rebuildMarkers();
        if (data.notification != null && !data.notification.isEmpty()) {
            notificationKey = data.notification;
            notificationExpireMs = System.currentTimeMillis() + 4000L;
        }
    }

    @Override
    public void receiveZoneDutyData(MsgWorkerZoneData data) {
        stables.clear();
        stables.addAll(data.stables);
        zoneWorkers.clear();
        zoneWorkers.addAll(data.workers);
        if (selectedZoneIndex >= zoneRows.size()) selectedZoneIndex = -1;
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
        }
        if (mapController != null) rebuildMarkers();
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

    private void rebuildMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();
        int ownerColor = ClientOwnerVisualsCache.getColor();
        for (int i = 0; i < zoneRows.size(); i++) {
            ZoneRow z = zoneRows.get(i);
            if (i == selectedZoneIndex) {
                mapController.panel.addMarker(WorldMapMarkerFactory.villageHighlighted(z.center, z.name));
            } else if (z.type == ZoneType.STABLE) {
                mapController.panel.addMarker(WorldMapMarkerFactory.stable(z.center, z.name, ownerColor));
            } else {
                mapController.panel.addMarker(WorldMapMarkerFactory.home(z.center, z.name, ownerColor));
            }
        }
        WorldMapContentPopulator.populate(mapController.panel,
                Collections.emptyList(), workerEntries, player, ownerColor);
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

        drawZoneList(mouseX, mouseY);
        drawWorkerPanel(I18n.format("travellers.worker.zones.unassigned_any", "Unassigned Henchmen"),
                unassigned, unassignedY, unassignedH, unassignedScroll, mouseX, mouseY);
        drawWorkerPanel(I18n.format("travellers.worker.zones.assigned_here", "Assigned Here"),
                selectedHench, selectedY, selectedH, selectedScroll, mouseX, mouseY);

        this.fontRenderer.drawString(
                I18n.format("travellers.worker.barracks.dragdrop", "Drag & drop to assign/unassign"),
                listX, selectedY + selectedH + 3, TravellersGuiTheme.COL_TEXT_DIM);

        drawNotification();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty())
                drawHoveringText(java.util.Arrays.asList(label.split("\n")), mouseX, mouseY);
        }

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

    private void drawZoneList(int mouseX, int mouseY) {
        String title = I18n.format("travellers.worker.zones.list_zones", "Barracks & Stables");
        TravellersGuiTheme.drawTitle(this.fontRenderer, title, listX, zoneListY - titleH);
        TravellersGuiTheme.drawInnerPanel(listX - 2, zoneListY - 2, listW + 4, zoneListH + 4);

        int visible = Math.min(zoneListH / rowH, zoneRows.size() - zoneScroll);
        for (int i = 0; i < visible; i++) {
            int idx = zoneScroll + i;
            int rowTop = zoneListY + i * rowH;
            boolean hovered = inRect(mouseX, mouseY, listX, rowTop, listW, rowH);
            boolean sel = idx == selectedZoneIndex;
            TravellersGuiTheme.drawListRow(listX, rowTop, listW, rowH, hovered, sel);
            ZoneRow z = zoneRows.get(idx);
            drawZoneIcon(z.type, listX + 1, rowTop - 1, 14);
            String label = z.name + " (" + z.capacityLabel + ")";
            this.fontRenderer.drawString(trim(label, listW - DELETE_ICON_W - 20), listX + 16, rowTop + 3,
                    hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
            boolean overIcon = inRect(mouseX, mouseY, listX + listW - DELETE_ICON_W, rowTop, DELETE_ICON_W, rowH);
            this.fontRenderer.drawString("X", listX + listW - DELETE_ICON_W + 3, rowTop + 3,
                    overIcon ? 0xFFFF5555 : 0xFFAA4444);
        }
        drawScrollbar(zoneListY, zoneListH, zoneRows.size(), zoneScroll);
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

    private void drawWorkerPanel(String title, List<WorkerRow> list,
                                 int panelY, int panelH, int scroll, int mouseX, int mouseY) {
        TravellersGuiTheme.drawTitle(this.fontRenderer, title, listX, panelY - titleH);
        TravellersGuiTheme.drawInnerPanel(listX - 2, panelY - 2, listW + 4, panelH + 4);

        int visible = Math.min(panelH / rowH, list.size() - scroll);
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            WorkerRow w = list.get(idx);
            int rowTop = panelY + i * rowH;
            boolean hovered = inRect(mouseX, mouseY, listX, rowTop, listW, rowH);
            boolean drag = dragging && dragWorker == w;
            TravellersGuiTheme.drawListRow(listX, rowTop, listW, rowH, hovered, drag);
            WorkerHeadRenderer.draw(w.cultureId, w.textureIndex, w.ownerColor, listX + 1, rowTop - 1, 16);
            int color = hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
            String label = w.name;
            if (w.assignedType == ZoneType.STABLE) label = "[S] " + label;
            else if (w.assignedType == ZoneType.BARRACK) label = "[H] " + label;
            this.fontRenderer.drawString(trim(label, listW - 22), listX + 20, rowTop + 3, color);
        }
        drawScrollbar(panelY, panelH, list.size(), scroll);
    }

    /** Thin track + thumb on the right edge, so a panel visibly reads as a scrollable list. */
    private void drawScrollbar(int panelY, int panelH, int count, int scroll) {
        int rows = panelH / rowH;
        int barX = listX + listW - SCROLLBAR_W;
        drawRect(barX, panelY, barX + SCROLLBAR_W, panelY + panelH, 0x33FFFFFF);
        if (count <= rows) return;
        int thumbH = Math.max(6, panelH * rows / count);
        int maxScroll = count - rows;
        int thumbY = panelY + (panelH - thumbH) * scroll / Math.max(1, maxScroll);
        drawRect(barX, thumbY, barX + SCROLLBAR_W, thumbY + thumbH, 0xAAFFFFFF);
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

    private void refreshAll() {
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestWorkerBarracksData());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestWorkerZoneData());
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;

        int bIdx = rowAt(mouseX, mouseY, zoneListY, zoneListH, zoneScroll, zoneRows.size());
        if (bIdx >= 0 && mouseX >= listX + listW - DELETE_ICON_W) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgDeletePlayerZone(zoneRows.get(bIdx).center));
            if (selectedZoneIndex == bIdx) selectedZoneIndex = -1;
            refreshAll();
            return;
        }
        if (bIdx >= 0) {
            selectedZoneIndex = bIdx;
            rebuildWorkerPanels();
            clampScrolls();
            if (mapController != null) {
                mapController.setCenter(zoneRows.get(bIdx).center);
                rebuildMarkers();
            }
            return;
        }

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
                // Unassign: route by what the worker is currently assigned to.
                if (dragWorker.assignedType == ZoneType.STABLE) {
                    TravellerFollowNet.CHANNEL.sendToServer(new MsgUnassignWorkerDuty(dragWorker.uuid));
                } else if (dragWorker.assignedType == ZoneType.BARRACK) {
                    TravellerFollowNet.CHANNEL.sendToServer(new MsgUnassignWorkerBarrack(dragWorker.uuid));
                }
                refreshAll();
            } else if (!dragFromAssigned && overSelected && selectedZoneIndex >= 0
                    && selectedZoneIndex < zoneRows.size()) {
                ZoneRow z = zoneRows.get(selectedZoneIndex);
                if (z.type == ZoneType.STABLE) {
                    TravellerFollowNet.CHANNEL.sendToServer(new MsgAssignWorkerDuty(dragWorker.uuid, z.center));
                } else {
                    TravellerFollowNet.CHANNEL.sendToServer(new MsgAssignWorkerBarrack(dragWorker.uuid, z.center));
                }
                refreshAll();
            }
        }
        dragging = false;
        dragWorker = null;
    }

    private void handleWheel(int mouseX, int mouseY, int wheel) {
        if (mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (inRect(mouseX, mouseY, listX, zoneListY, listW, zoneListH)) {
            zoneScroll += wheel > 0 ? -1 : 1;
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
        zoneScroll = clamp(zoneScroll, zoneRows.size(), zoneListH);
        unassignedScroll = clamp(unassignedScroll, unassigned.size(), unassignedH);
        selectedScroll = clamp(selectedScroll, selectedHench.size(), selectedH);
    }

    private int clamp(int scroll, int count, int panelH) {
        int max = Math.max(0, count - panelH / rowH);
        if (scroll < 0) return 0;
        return Math.min(scroll, max);
    }

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
