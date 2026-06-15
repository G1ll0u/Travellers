package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.WorldMapContentPopulator;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.home.MsgLeaderSetHome;
import com.jubitus.travellers.server.net.message.home.MsgDeleteLoneBarrack;
import com.jubitus.travellers.server.net.message.home.MsgWorkerDeleteHome;
import com.jubitus.travellers.server.net.message.home.MsgWorkerGetEquipment;
import com.jubitus.travellers.server.net.message.home.MsgWorkerRequestHomeList;
import com.jubitus.travellers.server.net.message.home.MsgWorkerSetHome;
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

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class GuiWorkerHomeList extends GuiScreen implements IWorkerMapReceiver {

    private final EntityTravellerWorker worker;  // null in leader mode
    private final EntityPlayer player;
    private final UUID leaderUUID;
    private final int repEntityId;
    private final GuiScreen returnTo;
    private final boolean leaderMode;

    private final List<Row> homes = new ArrayList<>();

    private GuiButton setBtn;
    private GuiButton backBtn;
    private GuiButton deleteBtn;
    private GuiButton getEquipBtn;

    private boolean showEquipDialog;
    private GuiButton equipStayBarracksBtn;
    private GuiButton equipStayVillageBtn;
    private GuiButton equipReturnBarracksBtn;
    private GuiButton equipReturnVillageBtn;
    private GuiButton equipCancelBtn;

    private int listX, listY, listW, listH;
    private int mapX, mapY, mapW, mapH;
    private final int rowH = 12;

    private Integer selectedIdx = null;
    private int scrollOffset = 0;

    private WorldMapController mapController;
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

    public GuiWorkerHomeList(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
        this.leaderUUID = null;
        this.repEntityId = -1;
        this.returnTo = null;
        this.leaderMode = false;
    }

    public GuiWorkerHomeList(EntityPlayer player, UUID leaderUUID, int repEntityId, GuiScreen returnTo) {
        this.player = player;
        this.worker = null;
        this.leaderUUID = leaderUUID;
        this.repEntityId = repEntityId;
        this.returnTo = returnTo;
        this.leaderMode = true;
    }

    @Override
    public void initGui() {
        super.initGui();

        listW = 140;
        listH = 120;
        mapW  = 190;
        mapH  = 150;

        int totalW = mapW + 14 + listW;
        int startX = Math.max(4, (this.width - totalW) / 2);
        mapX  = startX;
        listX = startX + mapW + 14;
        mapY  = (this.height - mapH) / 2;
        listY = mapY + (mapH - listH) / 2;

        this.buttonList.clear();

        int bw = 68;
        int bh = 20;
        int gap = 4;
        int buttonsTop  = listY + listH + gap;
        int buttonsRow2 = buttonsTop + bh + gap;

        this.backBtn = this.addButton(new GuiButton(
                1, listX, buttonsTop, bw, bh,
                I18n.format("travellers.gui.back", "Back")));

        this.setBtn = this.addButton(new GuiButton(
                0, listX + bw + gap, buttonsTop, bw, bh,
                I18n.format("travellers.worker.home_list.set_home", "Set Home")));

        this.deleteBtn = this.addButton(new GuiButton(
                2, listX, buttonsRow2, bw, bh,
                I18n.format("travellers.worker.home_list.delete", "Delete")));

        this.getEquipBtn = this.addButton(new GuiButton(
                3, listX + bw + gap, buttonsRow2, bw, bh,
                I18n.format("travellers.worker.home_list.get_equip", "Get Equipment")));

        // equip sub-dialog buttons, hidden until showEquipDialog
        int dlgX = listX;
        int dlgW = listW;
        int dlgY = listY + 4;
        this.equipStayBarracksBtn   = this.addButton(new GuiButton(10, dlgX, dlgY,      dlgW, bh,
                I18n.format("travellers.worker.home_list.equip_stay_barracks",   "Stay - Barracks")));
        this.equipStayVillageBtn    = this.addButton(new GuiButton(11, dlgX, dlgY + 24, dlgW, bh,
                I18n.format("travellers.worker.home_list.equip_stay_village",    "Stay - Whole Village")));
        this.equipReturnBarracksBtn = this.addButton(new GuiButton(12, dlgX, dlgY + 48, dlgW, bh,
                I18n.format("travellers.worker.home_list.equip_return_barracks", "Come Back - Barracks")));
        this.equipReturnVillageBtn  = this.addButton(new GuiButton(13, dlgX, dlgY + 72, dlgW, bh,
                I18n.format("travellers.worker.home_list.equip_return_village",  "Come Back - Whole Village")));
        this.equipCancelBtn         = this.addButton(new GuiButton(14, dlgX, dlgY + 96, dlgW, bh,
                I18n.format("travellers.gui.cancel", "Cancel")));

        showEquipDialog = false;
        this.setBtn.enabled     = false;
        this.deleteBtn.enabled  = false;
        this.getEquipBtn.enabled = false;
        this.selectedIdx = null;
        this.scrollOffset = 0;

        applyDialogVisibility();

        BlockPos center = leaderMode ? player.getPosition() : worker.getPosition();
        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_home_list_map", center);

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        int reqId = leaderMode ? -1 : this.worker.getEntityId();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestHomeList(reqId));
    }

    private void applyDialogVisibility() {
        equipStayBarracksBtn.visible   = showEquipDialog;
        equipStayVillageBtn.visible    = showEquipDialog;
        equipReturnBarracksBtn.visible = showEquipDialog;
        equipReturnVillageBtn.visible  = showEquipDialog;
        equipCancelBtn.visible         = showEquipDialog;

        setBtn.visible      = !showEquipDialog;
        backBtn.visible     = !showEquipDialog;
        deleteBtn.visible   = !showEquipDialog;
        getEquipBtn.visible = !showEquipDialog;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == this.backBtn) {
            if (leaderMode && returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else if (!leaderMode) {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS));
            }
            return;
        }

        if (button == this.setBtn) {
            if (this.selectedIdx == null) return;
            int idx = this.selectedIdx;
            if (idx < 0 || idx >= this.homes.size()) return;
            Row row = this.homes.get(idx);
            TravellerHomeManager.HomeType homeType = row.isBarracks
                    ? TravellerHomeManager.HomeType.BARRACKS
                    : (row.isLoneBarrack ? TravellerHomeManager.HomeType.LONE_BARRACK : TravellerHomeManager.HomeType.CHEST);
            if (leaderMode) {
                TravellerFollowNet.CHANNEL.sendToServer(new MsgLeaderSetHome(leaderUUID, row.pos, homeType));
                if (returnTo != null) mc.displayGuiScreen(returnTo);
            } else {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgWorkerSetHome(this.worker.getEntityId(), row.pos, homeType));
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS));
            }
            return;
        }

        if (button == this.deleteBtn) {
            if (this.selectedIdx == null) return;
            int idx = this.selectedIdx;
            if (idx < 0 || idx >= this.homes.size()) return;
            Row row = this.homes.get(idx);
            if (row.isLoneBarrack) {
                TravellerFollowNet.CHANNEL.sendToServer(new MsgDeleteLoneBarrack(row.pos));
            } else if (!row.isBarracks) {
                TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerDeleteHome(row.pos));
            }
            this.selectedIdx = null;
            this.setBtn.enabled      = false;
            this.deleteBtn.enabled   = false;
            this.getEquipBtn.enabled = false;
            return;
        }

        if (button == this.getEquipBtn) {
            showEquipDialog = true;
            applyDialogVisibility();
            return;
        }

        if (button == this.equipCancelBtn) {
            showEquipDialog = false;
            applyDialogVisibility();
            return;
        }

        if (button == equipStayBarracksBtn || button == equipStayVillageBtn
                || button == equipReturnBarracksBtn || button == equipReturnVillageBtn) {
            boolean stay           = (button == equipStayBarracksBtn || button == equipStayVillageBtn);
            boolean useWholeVillage = (button == equipStayVillageBtn || button == equipReturnVillageBtn);
            sendGetEquipment(stay, useWholeVillage);
            showEquipDialog = false;
            applyDialogVisibility();
            if (leaderMode && returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else if (!leaderMode) {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS));
            }
            return;
        }

        super.actionPerformed(button);
    }

    private void sendGetEquipment(boolean stay, boolean useWholeVillage) {
        if (this.selectedIdx == null) return;
        int idx = this.selectedIdx;
        if (idx < 0 || idx >= this.homes.size()) return;
        Row row = this.homes.get(idx);
        UUID targetUUID = leaderMode ? leaderUUID : this.worker.getUniqueID();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerGetEquipment(targetUUID, stay, useWholeVillage, row.pos));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (showEquipDialog) return;
        if (mouseButton != 0) return;
        if (this.homes.isEmpty()) return;
        if (!isMouseOverList(mouseX, mouseY)) return;

        int idx = this.scrollOffset + (mouseY - this.listY) / this.rowH;
        if (idx >= 0 && idx < this.homes.size()) {
            this.selectedIdx = idx;
            Row row = this.homes.get(idx);
            this.setBtn.enabled      = true;
            this.deleteBtn.enabled   = !row.isBarracks;  // lone barracks and old chests are deletable
            this.getEquipBtn.enabled = row.isBarracks || row.isLoneBarrack;
            if (mapController != null) mapController.setCenter(row.pos);
            rebuildMarkers();
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
        int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel  = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) return;

        if (mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (!showEquipDialog && isMouseOverList(mouseX, mouseY)) {
            this.scrollOffset += wheel > 0 ? -1 : 1;
            clampScroll();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        if (mapController != null) {
            TravellersGuiTheme.drawMapFrame(mapX, mapY, mapW, mapH);
            rebuildMarkers();
            mapController.draw();
        }

        // Draw panels before super so buttons render on top
        if (showEquipDialog) {
            drawEquipDialog();
        } else {
            drawHomeList(mouseX, mouseY);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
            }
        }
    }

    private void drawHomeList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(this.listX - 2, this.listY - 2, this.listW + 4, this.listH + 4);

        TravellersGuiTheme.drawTitle(this.fontRenderer,
                I18n.format("travellers.worker.home_list.title", "Homes"),
                this.listX, this.listY - 10);

        if (this.homes.isEmpty()) {
            this.fontRenderer.drawString(
                    I18n.format("travellers.worker.home_list.empty", "No homes."),
                    this.listX + 4, this.listY + 4, TravellersGuiTheme.COL_TEXT_DIM);
            return;
        }

        clampScroll();

        int visibleRows = Math.min(getVisibleRows(), this.homes.size() - this.scrollOffset);
        BlockPos assigned = (worker != null) ? worker.getAssignedHomePos() : null;

        for (int i = 0; i < visibleRows; i++) {
            int rowTop    = this.listY + i * this.rowH;
            int rowBottom = rowTop + this.rowH;
            int globalIdx = this.scrollOffset + i;

            boolean hovered  = mouseX >= this.listX && mouseX < this.listX + this.listW
                    && mouseY >= rowTop && mouseY < rowBottom;
            boolean selected = this.selectedIdx != null && this.selectedIdx == globalIdx;

            TravellersGuiTheme.drawListRow(this.listX, rowTop, this.listW, this.rowH, hovered, selected);

            Row row = this.homes.get(globalIdx);

            String base   = row.name == null || row.name.trim().isEmpty()
                    ? I18n.format("travellers.gui.unnamed", "(unnamed)") : row.name.trim();
            String prefix = row.isBarracks ? "[B] " : (row.isLoneBarrack ? "[LB] " : "[C] ");
            String label  = prefix + base;
            if (assigned != null && assigned.equals(row.pos)) label = "* " + label;

            final float scale = 0.85F;
            GlStateManager.pushMatrix();
            GlStateManager.translate(this.listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            int maxWidth = (int) ((this.listW - 6) / scale);
            int textColor = hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(label, maxWidth), 0, 0, textColor);
            GlStateManager.popMatrix();
        }
    }

    private void drawEquipDialog() {
        TravellersGuiTheme.drawPanel(listX - 2, listY - 2, listW + 4, listH + 4);
        TravellersGuiTheme.drawTitle(this.fontRenderer,
                I18n.format("travellers.worker.home_list.equip_title", "Choose mission:"),
                listX + 2, listY - 10);
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();

        int ownerColor = (worker != null) ? worker.getOwnerMarkColor() : playerOwnerColor;
        BlockPos assigned = (worker != null) ? worker.getAssignedHomePos() : null;

        // Home markers (lowest priority — workers and player drawn on top by populator)
        for (int i = 0; i < homes.size(); i++) {
            Row row = homes.get(i);
            if (row.pos == null) continue;
            String base  = (row.name == null || row.name.trim().isEmpty())
                    ? I18n.format("travellers.gui.unnamed", "(unnamed)") : row.name.trim();
            String prefix = row.isBarracks ? "[B] " : (row.isLoneBarrack ? "[LB] " : "[C] ");
            String label = prefix + base;
            if (assigned != null && assigned.equals(row.pos)) label = "* " + label;

            if (this.selectedIdx != null && this.selectedIdx == i) {
                mapController.panel.addMarker(WorldMapMarkerFactory.villageHighlighted(row.pos, label));
            } else {
                mapController.panel.addMarker(WorldMapMarkerFactory.home(row.pos, label, ownerColor));
            }
        }

        // Workers + player via standard populator (no POIs for this GUI)
        WorldMapContentPopulator.populate(
                mapController.panel,
                java.util.Collections.emptyList(),
                workerEntries,
                player,
                playerOwnerColor);
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
    }

    public void receiveHomes(List<BlockPos> pos, List<String> names, List<Integer> types) {
        this.homes.clear();
        int n = Math.min(pos.size(), names.size());
        for (int i = 0; i < n; i++) {
            int t = i < types.size() ? types.get(i) : 0;
            boolean barracks = t == 1;
            boolean loneBarrack = t == 2;
            this.homes.add(new Row(pos.get(i), names.get(i), barracks, loneBarrack));
        }
        this.selectedIdx = null;
        this.scrollOffset = 0;
        if (this.setBtn != null) {
            this.setBtn.enabled     = false;
            this.deleteBtn.enabled  = false;
            this.getEquipBtn.enabled = false;
        }
        if (!homes.isEmpty() && mapController != null) mapController.setCenter(homes.get(0).pos);
    }

    private boolean isMouseOverList(int mouseX, int mouseY) {
        return mouseX >= this.listX && mouseX < this.listX + this.listW
                && mouseY >= this.listY && mouseY < this.listY + this.listH;
    }

    private int getVisibleRows() {
        return Math.max(1, this.listH / this.rowH);
    }

    private int getMaxScroll() {
        return Math.max(0, this.homes.size() - getVisibleRows());
    }

    private void clampScroll() {
        if (this.scrollOffset < 0) this.scrollOffset = 0;
        int max = getMaxScroll();
        if (this.scrollOffset > max) this.scrollOffset = max;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    public Rectangle getJeiMapArea() {
        return new Rectangle(mapX - 2, mapY - 2, mapW + 4, mapH + 4);
    }

    public Rectangle getJeiListArea() {
        return new Rectangle(listX - 2, listY - 12, listW + 4, listH + 56);
    }

    private static class Row {
        final BlockPos pos;
        final String name;
        final boolean isBarracks;
        final boolean isLoneBarrack;

        Row(BlockPos pos, String name, boolean isBarracks, boolean isLoneBarrack) {
            this.pos  = pos;
            this.name = name == null ? "" : name;
            this.isBarracks = isBarracks;
            this.isLoneBarrack = isLoneBarrack;
        }
    }
}
