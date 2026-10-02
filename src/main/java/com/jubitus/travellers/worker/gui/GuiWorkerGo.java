package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.client.gui.GuiTravellersButton;
import com.jubitus.travellers.client.gui.ButtonTooltipHelper;
import com.jubitus.travellers.client.marker.ClientOwnerVisualsCache;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.worldmap.GuiWorldMapPanel;
import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.WorldMapContentPopulator;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.server.map.stream.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.stream.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.follow.net.MsgLeaderGoTo;
import com.jubitus.travellers.worker.net.MsgWorkerGoTo;
import com.jubitus.travellers.worker.net.MsgWorkerGoToPos;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
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
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class GuiWorkerGo extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapReceiver, ITravellersGui {

    private GuiButton backButton;
    private GuiButton zoomInButton, zoomOutButton, recenterButton;
    private GuiButton goButton;
    private GuiButton getEquipButton;
    private GuiButton panN, panS, panW, panE;

    private boolean showEquipDialog = false;
    private GuiButton equipStayButton, equipReturnButton, equipCancelButton;

    private final ButtonTooltipHelper tooltips = new ButtonTooltipHelper();

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private boolean leaderMode = false;
    @Nullable private UUID leaderUUID = null;
    @Nullable private BlockPos leaderPos = null;
    @Nullable private GuiScreen returnTo = null;
    private int repEntityId = -1;

    private WorldMapController mapController;
    private int mapX, mapY, mapW, mapH;

    private int listX, listY, listW, listH;
    private final int rowH = 12;
    private int scrollOffset = 0;

    private final List<Row> rows = new ArrayList<>();
    private int selectedIndex = -1;
    private BlockPos selectedRowPos = null;
    private BlockPos selectedMapPos = null;

    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();

    private int guiLeft, guiTop;

    public GuiWorkerGo(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
        this.repEntityId = worker != null ? worker.getEntityId() : -1;
    }

    public static GuiWorkerGo forLeader(EntityPlayer player, UUID leaderUUID,
                                         int representativeEntityId, BlockPos leaderPos,
                                         GuiScreen returnTo) {
        GuiWorkerGo gui = new GuiWorkerGo(player, null);
        gui.leaderMode = true;
        gui.leaderUUID = leaderUUID;
        gui.leaderPos = leaderPos;
        gui.returnTo = returnTo;
        gui.repEntityId = representativeEntityId;
        return gui;
    }

    @Override
    public void initGui() {
        super.initGui();

        int shiftLeft = 40;
        int xSize = 176;
        int ySize = 150;
        this.guiLeft = Math.max(4, (this.width - xSize) / 2 - shiftLeft);
        this.guiTop = (this.height - ySize) / 2;

        this.listW = 140;
        this.listH = 120;
        this.listX = this.guiLeft + xSize + 8;
        this.listY = this.guiTop + 4;

        this.mapW = 190;
        this.mapH = 150;
        this.mapY = this.listY;

        int gapToList = 14;
        this.mapX = this.listX - this.mapW - gapToList;

        int minLeft = 6;
        if (this.mapX < minLeft) {
            int shift = minLeft - this.mapX;
            this.guiLeft += shift;
            this.listX   += shift;
            this.mapX    += shift;
        }

        BlockPos initCenter = leaderMode && leaderPos != null ? leaderPos
                : (worker != null ? worker.getPosition() : player.getPosition());

        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_worker_map", initCenter);
        mapController.panel.setSelectedPos(null);
        selectedMapPos = null;

        this.buttonList.clear();

        int bw = 70, bh = 20, gap = 4;
        int buttonsTop = listY + listH + gap;

        this.goButton = this.addButton(new GuiTravellersButton(1, listX + listW - bw, buttonsTop, bw, bh,
                I18n.format("travellers.gui.go", "Go")));
        this.goButton.enabled = false;

        int aw = 16, ah = 16;
        int ay = mapY + (mapH / 2) - (ah / 2);
        this.panW = this.addButton(new GuiTravellersButton(2, mapX + 2, ay, aw, ah, "<"));
        this.panE = this.addButton(new GuiTravellersButton(3, mapX + mapW - aw - 2, ay, aw, ah, ">"));

        int axMid = mapX + (mapW / 2) - (aw / 2);
        this.panN = this.addButton(new GuiTravellersButton(4, axMid, mapY + 2, aw, ah, "^"));
        this.panS = this.addButton(new GuiTravellersButton(5, axMid, mapY + mapH - ah - 2, aw, ah, "v"));

        int zTop = mapY + mapH + 6;
        this.zoomOutButton  = this.addButton(new GuiTravellersButton(6, mapX, zTop, 20, 20, "-"));
        this.zoomInButton   = this.addButton(new GuiTravellersButton(7, mapX + 24, zTop, 20, 20, "+"));
        this.recenterButton = this.addButton(new GuiTravellersButton(8, mapX + mapW - 58, zTop, 58, 20,
                I18n.format("travellers.map.center", "Center")));
        this.backButton = this.addButton(new GuiTravellersButton(9, listX, buttonsTop, 50, bh,
                I18n.format("travellers.gui.back", "Back")));

        // Second row under the list: Get Equipment (only for controlled villages / lone barracks)
        int buttonsTop2 = buttonsTop + bh + gap;
        this.getEquipButton = this.addButton(new GuiTravellersButton(10, listX, buttonsTop2, listW, bh,
                I18n.format("travellers.worker.barracks.get_equip", "Get Equipment")));
        this.getEquipButton.enabled = false;

        // Inline equip dialog buttons (over the list), hidden until opened
        int dlgY = listY + 4;
        this.equipStayButton   = this.addButton(new GuiTravellersButton(11, listX, dlgY, listW, bh,
                I18n.format("travellers.worker.barracks.equip_stay", "Equip and Stay")));
        this.equipReturnButton = this.addButton(new GuiTravellersButton(12, listX, dlgY + bh + gap, listW, bh,
                I18n.format("travellers.worker.barracks.equip_return", "Equip and Return")));
        this.equipCancelButton = this.addButton(new GuiTravellersButton(13, listX, dlgY + (bh + gap) * 2, listW, bh,
                I18n.format("travellers.gui.cancel", "Cancel")));
        showEquipDialog = false;
        applyEquipDialogVisibility();

        tooltips.set(getEquipButton, "travellers.worker.barracks.get_equip.tooltip");
        tooltips.set(equipStayButton, "travellers.worker.barracks.equip_stay.tooltip");
        tooltips.set(equipReturnButton, "travellers.worker.barracks.equip_return.tooltip");

        rows.clear();
        scrollOffset = 0;
        selectedIndex = -1;

        int listRequestId = repEntityId >= 0 ? repEntityId : -1;
        if (listRequestId >= 0)
            TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(listRequestId));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        rows.clear();
        for (MsgWorkerGoList.Entry e : entries) {
            rows.add(new Row(e.kind, e.pos, e.label, e.distance));
        }
        rows.sort(Comparator.comparingInt(r -> r.distance));
        selectedIndex = -1;
        goButton.enabled = false;
        if (getEquipButton != null) getEquipButton.enabled = false;
        scrollOffset = 0;
        clampScroll();
        rebuildMarkers();
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        workerEntries.clear();
        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        for (MsgMapWorkersUpdate.Entry e : list) {
            if (e.dim != myDim) continue;
            workerEntries.add(e);
        }
        rebuildMarkers();
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        int workerColor = worker != null ? worker.getOwnerMarkColor() : ClientOwnerVisualsCache.getColor();

        // Convert rows to GoList entries for the populator
        java.util.List<MsgWorkerGoList.Entry> pois = new java.util.ArrayList<>();
        for (Row r : rows) {
            if (r.pos == null) continue;
            MsgWorkerGoList.Entry e;
            if (r.kind == MsgWorkerGoList.Kind.VILLAGE)
                e = MsgWorkerGoList.Entry.village(r.pos, r.label, r.distance);
            else if (r.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE)
                e = MsgWorkerGoList.Entry.controlledVillage(r.pos, r.label, r.distance);
            else if (r.kind == MsgWorkerGoList.Kind.STABLE)
                e = MsgWorkerGoList.Entry.stable(r.pos, r.label, r.distance);
            else
                e = MsgWorkerGoList.Entry.home(r.pos, r.label, r.distance);
            pois.add(e);
        }

        // Highlighted: the currently selected row (shown in gold)
        java.util.Set<net.minecraft.util.math.BlockPos> highlighted = null;
        if (selectedRowPos != null) {
            highlighted = java.util.Collections.singleton(selectedRowPos);
        }

        mapController.panel.clearMarkers();
        WorldMapContentPopulator.populate(
                mapController.panel, pois, workerEntries, player, workerColor, highlighted);

        // Custom map-click destination drawn above all other markers
        if (selectedMapPos != null) {
            mapController.panel.addMarker(WorldMapMarkerFactory.destinationCross(selectedMapPos));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int wheel = Mouse.getDWheel();
        if (wheel != 0) {
            if (mapController != null && mapController.isMouseOver(mouseX, mouseY))
                mapController.handleWheel(mouseX, mouseY, wheel);
            else
                handleListWheelScroll(mouseX, mouseY, wheel);
        }

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        if (mapController != null) {
            TravellersGuiTheme.drawMapFrame(mapX, mapY, mapW, mapH);
            rebuildMarkers();
            mapController.draw();
        }

        if (showEquipDialog) {
            TravellersGuiTheme.drawPanel(listX - 2, listY - 2, listW + 4, listH + 4);
            TravellersGuiTheme.drawTitle(this.fontRenderer,
                    I18n.format("travellers.worker.barracks.equip_title", "Choose mission:"),
                    listX + 2, listY - 10);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        if (!showEquipDialog) drawList(mouseX, mouseY);

        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(java.util.Arrays.asList(label.split("\n")), mouseX, mouseY);
            }
        }
        tooltips.render(mouseX, mouseY, this.width, this.height);
    }

    private void drawList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listX - 2, listY - 2, listW + 4, listH + 4);
        clampScroll();

        int rowsVisible = Math.min(getVisibleRows(), rows.size() - scrollOffset);
        for (int i = 0; i < rowsVisible; i++) {
            int rowTop = listY + i * rowH;
            int rowBottom = rowTop + rowH;
            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;
            int globalIdx = scrollOffset + i;
            boolean sel = (globalIdx == selectedIndex);
            TravellersGuiTheme.drawListRow(listX, rowTop, listW, rowH, hovered, sel);

            Row r = rows.get(globalIdx);
            final float scale = 0.85F;
            String prefix = r.kind == MsgWorkerGoList.Kind.VILLAGE ? "[V] "
                    : r.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE ? "[CV] "
                    : r.kind == MsgWorkerGoList.Kind.STABLE ? "[S] "
                    : ""; //<-- yes it's stupid
            String distSuffix = " (" + r.distance + "m)";
            String label = shortenNameWithSuffix(prefix + r.label, distSuffix, (int)((listW - 6) / scale));

            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            int textColor = hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
            this.fontRenderer.drawString(label, 0, 0, textColor);
            GlStateManager.popMatrix();
        }
    }

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
        StringBuilder pre = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (this.fontRenderer.getStringWidth(pre + String.valueOf(name.charAt(i))) > half) break;
            pre.append(name.charAt(i));
        }
        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String candidate = name.charAt(i) + tail.toString();
            if (this.fontRenderer.getStringWidth(pre + ellipsis + candidate) > remaining) break;
            tail.insert(0, name.charAt(i));
        }
        return pre + ellipsis + tail + suffix;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // While the equip dialog is open, only its buttons are interactive.
        if (showEquipDialog) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        // Right-click drag handled by controller
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);

        // Left-click on map: select world position as destination
        if (mouseButton == 0 && mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            BlockPos pos = mapController.screenToWorld(mouseX, mouseY);
            selectedMapPos = pos;
            mapController.panel.setSelectedPos(null);
            selectedRowPos = null;
            selectedIndex = -1;
            goButton.enabled = true;
            updateGetEquipEnabled();
            rebuildMarkers();
            return;
        }

        // Left-click on list
        if (mouseButton == 0 && !rows.isEmpty() && isMouseOverList(mouseX, mouseY)) {
            int idx = scrollOffset + (mouseY - listY) / rowH;
            if (idx >= 0 && idx < rows.size()) {
                selectedIndex = idx;
                Row r = rows.get(idx);
                selectedMapPos = null;
                selectedRowPos = r.pos;
                mapController.setCenter(r.pos);
                mapController.panel.setSelectedPos(r.pos);
                goButton.enabled = true;
                updateGetEquipEnabled();
                rebuildMarkers();
            }
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (mapController != null) mapController.onMouseDrag(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (mapController != null) mapController.onMouseRelease(state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        // wheel is handled in drawScreen via Mouse.getDWheel()
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        int stepBlocks = Math.round(mapController.panel.getZoomBlocksPerPixel() * 48);

        if (button == backButton) {
            if (leaderMode && returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            }
            return;
        }

        if (button == getEquipButton) {
            showEquipDialog = true;
            applyEquipDialogVisibility();
            return;
        }
        if (button == equipCancelButton) {
            showEquipDialog = false;
            applyEquipDialogVisibility();
            return;
        }
        if (button == equipStayButton || button == equipReturnButton) {
            sendGetEquipment(button == equipStayButton);
            showEquipDialog = false;
            applyEquipDialogVisibility();
            if (leaderMode && returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else if (!leaderMode && worker != null) {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            }
            return;
        }

        if (button == panN) { mapController.panel.pan(0, -stepBlocks); return; }
        if (button == panS) { mapController.panel.pan(0,  stepBlocks); return; }
        if (button == panW) { mapController.panel.pan(-stepBlocks, 0); return; }
        if (button == panE) { mapController.panel.pan( stepBlocks, 0); return; }

        if (button == zoomOutButton) {
            mapController.handleWheel(mapController.mapX + mapController.mapW / 2,
                    mapController.mapY + mapController.mapH / 2, -1);
            return;
        }
        if (button == zoomInButton) {
            mapController.handleWheel(mapController.mapX + mapController.mapW / 2,
                    mapController.mapY + mapController.mapH / 2, 1);
            return;
        }

        if (button == recenterButton) {
            BlockPos center = leaderMode && leaderPos != null ? leaderPos
                    : (worker != null ? worker.getPosition() : player.getPosition());
            mapController.setCenter(center);
            return;
        }

        if (button == goButton) {
            if (leaderMode && leaderUUID != null) {
                BlockPos dest = selectedMapPos;
                if (dest == null && selectedIndex >= 0 && selectedIndex < rows.size())
                    dest = rows.get(selectedIndex).pos;
                if (dest == null) return;
                TravellerFollowNet.CHANNEL.sendToServer(new MsgLeaderGoTo(leaderUUID, dest));
                mc.player.closeScreen();
                return;
            }
            if (selectedMapPos != null) {
                TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerGoToPos(worker.getEntityId(), selectedMapPos));
                mc.player.closeScreen();
                return;
            }
            if (selectedIndex < 0) return;
            TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerGoTo(worker.getEntityId(), selectedIndex));
            mc.player.closeScreen();
            return;
        }

        super.actionPerformed(button);
    }

    private void applyEquipDialogVisibility() {
        boolean d = showEquipDialog;
        equipStayButton.visible = d;
        equipReturnButton.visible = d;
        equipCancelButton.visible = d;

        goButton.visible = !d;
        getEquipButton.visible = !d;
        backButton.visible = !d;
        panN.visible = !d; panS.visible = !d; panW.visible = !d; panE.visible = !d;
        zoomInButton.visible = !d; zoomOutButton.visible = !d; recenterButton.visible = !d;
    }

    /** Get Equipment is allowed for a controlled village, a lone barrack, or a stable row. */
    private void updateGetEquipEnabled() {
        boolean ok = selectedMapPos == null && selectedIndex >= 0 && selectedIndex < rows.size()
                && (rows.get(selectedIndex).kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE
                    || rows.get(selectedIndex).kind == MsgWorkerGoList.Kind.HOME
                    || rows.get(selectedIndex).kind == MsgWorkerGoList.Kind.STABLE);
        if (getEquipButton != null) getEquipButton.enabled = ok;
    }

    private void sendGetEquipment(boolean stay) {
        if (selectedIndex < 0 || selectedIndex >= rows.size()) return;
        Row r = rows.get(selectedIndex);
        com.jubitus.travellers.unit.equipment.EquipTargetKind kind =
                r.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE
                        ? com.jubitus.travellers.unit.equipment.EquipTargetKind.CONTROLLED_VILLAGE
                        : r.kind == MsgWorkerGoList.Kind.STABLE
                        ? com.jubitus.travellers.unit.equipment.EquipTargetKind.STABLE
                        : com.jubitus.travellers.unit.equipment.EquipTargetKind.LONE_BARRACK;
        UUID targetUUID = leaderMode ? leaderUUID : (worker != null ? worker.getUniqueID() : null);
        if (targetUUID == null || r.pos == null) return;
        TravellerFollowNet.CHANNEL.sendToServer(
                new com.jubitus.travellers.unit.equipment.net.MsgWorkerGetEquipment(
                        targetUUID, stay, kind, r.pos));
    }

    private static class Row {
        final MsgWorkerGoList.Kind kind;
        final BlockPos pos;
        final String label;
        final int distance;
        Row(MsgWorkerGoList.Kind kind, BlockPos pos, String label, int distance) {
            this.kind = kind; this.pos = pos; this.label = label; this.distance = distance;
        }
    }

    private boolean isMouseOverList(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH;
    }

    private int getVisibleRows() { return Math.max(1, listH / rowH); }
    private int getMaxScroll()   { return Math.max(0, rows.size() - getVisibleRows()); }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > getMaxScroll()) scrollOffset = getMaxScroll();
    }

    private void handleListWheelScroll(int mouseX, int mouseY, int wheel) {
        if (wheel == 0 || !isMouseOverList(mouseX, mouseY)) return;
        scrollOffset += (wheel > 0) ? -1 : 1;
        clampScroll();
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    public Rectangle getJeiMapArea() {
        return new Rectangle(mapX - 2, mapY - 2, mapW + 4, mapH + 26);
    }

    public Rectangle getJeiListArea() {
        return new Rectangle(listX - 2, listY - 2, listW + 4, listH + 24);
    }
}
