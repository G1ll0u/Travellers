package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.worldmap.gui.WorkerMapScreenSupport;
import com.jubitus.travellers.worldmap.gui.WorldMapController;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.RecruitMission;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.worldmap.net.MsgSubscribeWorkerMap;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worldmap.net.MsgRequestMapDiscovery;
import com.jubitus.travellers.worker.net.MsgRecruitStartSelectedVillages;
import com.jubitus.travellers.worker.net.MsgWorkerRequestVillages;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import com.jubitus.travellers.worker.net.MsgRequestServerCosts;
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
import org.millenaire.common.utilities.MillCommonUtilities;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerRecruit extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapReceiver, ITravellersGui {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<VillageRow> villages = new ArrayList<>();
    private final java.util.Set<Integer> selected = new java.util.HashSet<>();

    private GuiButton startButton;
    private GuiButton backButton;

    private int listX, listY, listW, listH;
    private int mapX, mapY, mapW, mapH;
    private final ScrollList list = new ScrollList(12);
    private static final float ROW_TEXT_SCALE = 0.85F;
    private int lastFee = 0;

    private int guiTop;

    // ── Map data ──────────────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final WorkerMapScreenSupport mapData = new WorkerMapScreenSupport();

    public GuiWorkerRecruit(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();

        listW = 150;
        listH = 120;
        mapW  = 190;
        mapH  = 150;

        // Center the full layout: map + gap + list
        int totalW = mapW + 14 + listW;
        int startX = Math.max(4, (this.width - totalW) / 2);
        mapX = startX;
        listX = startX + mapW + 14;
        mapY = (this.height - mapH) / 2;
        listY = mapY + (mapH - listH) / 2;
        guiTop = mapY;
        list.setBounds(listX, listY, listW, listH);

        this.buttonList.clear();

        int bw = 70;
        int bh = 20;
        int gap = 4;
        int buttonsTop = listY + listH + gap;

        this.backButton = this.addButton(new GuiTravellersButton(
                2, listX, buttonsTop, bw, bh,
                I18n.format("travellers.gui.back", "Back")));

        this.startButton = this.addButton(new GuiTravellersButton(
                0, listX + bw + gap, buttonsTop, bw, bh,
                I18n.format("travellers.worker.recruit.start", "Recruit")));

        this.startButton.enabled = false;

        villages.clear();
        selected.clear();
        list.resetScroll();
        recomputeFee();

        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_recruit_map", worker.getPosition());

        mapData.subscribe();
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestVillages(worker.getEntityId()));
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestServerCosts());
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        mapData.unsubscribe();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        recomputeFee();
    }

    // ── IWorkerGoListReceiver ─────────────────────────────────────────────────

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        mapData.setPlaces(entries);
        rebuildMapMarkers();
    }

    // ── IWorkerMapReceiver ────────────────────────────────────────────────────

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        mapData.setWorkers(list);
        rebuildMapMarkers();
    }

    // ── Map markers ───────────────────────────────────────────────────────────

    private void rebuildMapMarkers() {
        if (mapController == null) return;

        // Collect highlighted village positions from the recruit selection
        java.util.Set<net.minecraft.util.math.BlockPos> highlighted = new java.util.HashSet<>();
        for (int i : selected) {
            if (i >= 0 && i < villages.size()) highlighted.add(villages.get(i).center);
        }

        mapData.populate(mapController, player, ClientOwnerVisualsCache.getColor(), highlighted);
    }

    private void recomputeFee() {
        if (startButton == null) return;
        if (selected.isEmpty()) { lastFee = 0; startButton.enabled = false; return; }

        java.util.List<Integer> sel = new java.util.ArrayList<>(selected);
        java.util.Collections.sort(sel);
        java.util.List<BlockPos> route = new java.util.ArrayList<>();
        for (int idx : sel) {
            if (idx >= 0 && idx < villages.size()) route.add(villages.get(idx).center);
        }

        BlockPos start = player.getPosition();
        double perBlock = TravellersDerivedConfig.recruitFeePerBlockServerCache;
        int flat = TravellersDerivedConfig.recruitFeeFlatServerCache;
        int perVillage = TravellersDerivedConfig.recruitFeePerVillageServerCache;
        boolean hasServer = flat >= 0 && perVillage >= 0 && !Double.isNaN(perBlock);

        int dist = RecruitMission.computeRouteDistance2D(start, route, start);
        if (hasServer) {
            lastFee = Math.max(0, flat + (perVillage * route.size()) + (int) Math.ceil(dist * perBlock));
        } else {
            lastFee = RecruitMission.computeFee(dist, route.size());
        }

        int money = MillMoney.countPlayerMoney(player);
        startButton.enabled = money >= lastFee;
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel  = Mouse.getEventDWheel();
        if (wheel == 0) return;

        if (mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (list.isOver(mouseX, mouseY)) {
            list.scrollBy(wheel, villages.size());
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0) return;
        int idx = list.rowAt(mouseX, mouseY, villages.size());
        if (idx < 0) return;

        int minD = TravellersConfig.RECRUITMENT.recruitMinVillageDistance;
        int dist = villages.get(idx).distance;
        if (dist < minD) {
            com.jubitus.travellers.notify.client.ClientNotificationCache.push(
                    com.jubitus.travellers.notify.NotificationType.ERROR,
                    I18n.format("travellers.worker.recruit.too_close", minD),
                    Minecraft.getMinecraft().world != null ? Minecraft.getMinecraft().world.getTotalWorldTime() : 0L);
            return;
        }

        if (selected.contains(idx)) selected.remove(idx);
        else selected.add(idx);

        recomputeFee();
        rebuildMapMarkers();
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
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backButton) {
            TravellersNetwork.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            return;
        }

        if (button == startButton) {
            if (selected.isEmpty()) return;
            java.util.List<Integer> sel = new java.util.ArrayList<>(selected);
            java.util.Collections.sort(sel);
            java.util.List<BlockPos> route = new java.util.ArrayList<>();
            for (int idx : sel) {
                if (idx >= 0 && idx < villages.size()) route.add(villages.get(idx).center);
            }
            TravellersNetwork.CHANNEL.sendToServer(
                    new MsgRecruitStartSelectedVillages(worker.getEntityId(), route));
            mc.player.closeScreen();
            return;
        }

        super.actionPerformed(button);
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        // Map
        if (mapController != null) {
            TravellersGuiTheme.drawMapFrame(mapX, mapY, mapW, mapH);
            rebuildMapMarkers();
            mapController.draw();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        drawVillageList(mouseX, mouseY);

        if (mapController != null) mapController.drawTooltip(this, mouseX, mouseY);
    }

    private void drawVillageList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listX - 2, listY - 2, listW + 4, listH + 4);

        int money = MillMoney.countPlayerMoney(player);
        String feeStr = MillMoney.format(lastFee);
        int col = money >= lastFee ? TravellersGuiTheme.COL_AFFORD : TravellersGuiTheme.COL_CANNOT;
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.recruit.fee", feeStr),
                listX, listY - 10, col);

        list.clamp(villages.size());
        int maxLabelW = (int) ((listW - 6) / ROW_TEXT_SCALE);
        for (int i = 0; i < list.rowsToDraw(villages.size()); i++) {
            int idx = list.scroll() + i;
            int rowTop = list.rowTop(idx);
            boolean hovered = list.isRowHovered(idx, mouseX, mouseY);
            boolean isSel = selected.contains(idx);
            TravellersGuiTheme.drawListRow(listX, rowTop, listW, list.rowH, hovered, isSel);
            TravellersGuiTheme.drawScaledText(fontRenderer,
                    (isSel ? "[X] " : "[ ] ") + villages.get(idx).label(fontRenderer, maxLabelW),
                    listX + 3, rowTop + 2, ROW_TEXT_SCALE,
                    hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
        }
    }

    public void receiveVillageList(List<BlockPos> centers, List<String> labels, List<Integer> distances) {
        villages.clear();
        villages.addAll(VillageRow.fromServer(centers, labels, distances));
        selected.clear();
        list.resetScroll();
        recomputeFee();
        rebuildMapMarkers();
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    public Rectangle getJeiListArea() {
        return new Rectangle(listX - 2, listY - 12, listW + 4, listH + 38);
    }

    public Rectangle getJeiMapArea() {
        return new Rectangle(mapX - 2, mapY - 2, mapW + 4, mapH + 4);
    }
}
