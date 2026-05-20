package com.jubitus.travellers.client.gui.recruit;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.client.gui.worldmap.IWorkerGoListReceiver;
import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.server.net.message.recruit.MsgRecruitStartSelectedVillages;
import com.jubitus.travellers.server.net.message.villageshomes.MsgWorkerRequestVillages;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerRequestGoList;
import com.jubitus.travellers.server.net.message.workerplayer.MsgRequestServerCosts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
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
public class GuiWorkerRecruit extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapReceiver {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<VillageRow> villages = new ArrayList<>();
    private final java.util.Set<Integer> selected = new java.util.HashSet<>();

    private GuiButton startButton;
    private GuiButton backButton;

    private int listX, listY, listW, listH;
    private int mapX, mapY, mapW, mapH;
    private final int rowH = 12;
    private int lastFee = 0;
    private int scrollOffset = 0;

    private int guiTop;

    // ── Map data ──────────────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final List<MsgWorkerGoList.Entry> poiEntries = new ArrayList<>();
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

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

        this.buttonList.clear();

        int bw = 70;
        int bh = 20;
        int gap = 4;
        int buttonsTop = listY + listH + gap;

        this.backButton = this.addButton(new GuiButton(
                2, listX, buttonsTop, bw, bh,
                I18n.format("travellers.gui.back", "Back")));

        this.startButton = this.addButton(new GuiButton(
                0, listX + bw + gap, buttonsTop, bw, bh,
                I18n.format("travellers.worker.recruit.start", "Recruit")));

        this.startButton.enabled = false;

        villages.clear();
        selected.clear();
        scrollOffset = 0;
        recomputeFee();

        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_recruit_map", worker.getPosition());

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestVillages(worker.getEntityId()));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestServerCosts());
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        recomputeFee();
    }

    // ── IWorkerGoListReceiver ─────────────────────────────────────────────────

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        poiEntries.clear();
        if (entries != null) poiEntries.addAll(entries);
        rebuildMapMarkers();
    }

    // ── IWorkerMapReceiver ────────────────────────────────────────────────────

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        workerEntries.clear();
        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        for (MsgMapWorkersUpdate.Entry e : list) {
            if (e.dim != myDim) continue;
            workerEntries.add(e);
            if (playerOwnerColor == 0xFFFFFF && e.ownerColor != 0) playerOwnerColor = e.ownerColor;
        }
        rebuildMapMarkers();
    }

    // ── Map markers ───────────────────────────────────────────────────────────

    private void rebuildMapMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();

        // POIs from GoList (villages + homes)
        for (MsgWorkerGoList.Entry e : poiEntries) {
            if (e.pos == null) continue;
            if (e.kind == MsgWorkerGoList.Kind.VILLAGE)
                mapController.panel.addMarker(WorldMapMarkerFactory.village(e.pos, e.label));
            else
                mapController.panel.addMarker(WorldMapMarkerFactory.home(e.pos, e.label));
        }

        // Overlay: highlight checked villages from the recruit list
        for (int i = 0; i < villages.size(); i++) {
            if (selected.contains(i)) {
                mapController.panel.addMarker(
                        WorldMapMarkerFactory.villageHighlighted(villages.get(i).center, villages.get(i).display));
            }
        }

        // Workers
        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (e.pos == null) continue;
            String name = (e.name != null && !e.name.isEmpty()) ? e.name : "Worker";
            if (e.isLeader)
                mapController.panel.addMarker(WorldMapMarkerFactory.leader(e.pos, name, e.ownerColor));
            else
                mapController.panel.addMarker(WorldMapMarkerFactory.follower(e.pos, name, e.ownerColor));
        }

        // Player
        mapController.panel.addMarker(WorldMapMarkerFactory.player(
                player.getPosition(), player.getName(), playerOwnerColor));
    }

    // ── List helpers ──────────────────────────────────────────────────────────

    private boolean isMouseOverList(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH;
    }

    private int getVisibleRows() { return Math.max(1, listH / rowH); }
    private int getMaxScroll()   { return Math.max(0, villages.size() - getVisibleRows()); }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        int max = getMaxScroll();
        if (scrollOffset > max) scrollOffset = max;
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
        double perBlock = TravellersModConfig.recruitFeePerBlock_serverCache;
        int flat = TravellersModConfig.recruitFeeFlat_serverCache;
        int perVillage = TravellersModConfig.recruitFeePerVillage_serverCache;
        boolean hasServer = flat >= 0 && perVillage >= 0 && !Double.isNaN(perBlock);

        int dist = EntityTravellerWorker.computeRouteDistance2D(start, route, start);
        if (hasServer) {
            lastFee = Math.max(0, flat + (perVillage * route.size()) + (int) Math.ceil(dist * perBlock));
        } else {
            lastFee = EntityTravellerWorker.computeRecruitFee(dist, route.size());
        }

        int money = MillCommonUtilities.countMoney(player.inventory);
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
        } else if (isMouseOverList(mouseX, mouseY)) {
            scrollOffset += wheel > 0 ? -1 : 1;
            clampScroll();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0 || villages.isEmpty()) return;
        if (!isMouseOverList(mouseX, mouseY)) return;

        int idx = scrollOffset + (mouseY - listY) / rowH;
        if (idx < 0 || idx >= villages.size()) return;

        int minD = TravellersModConfig.recruitMinVillageDistance;
        int dist = villages.get(idx).distance;
        if (dist < minD) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.RED + I18n.format("travellers.worker.recruit.too_close", minD)));
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
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS));
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
            TravellerFollowNet.CHANNEL.sendToServer(
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
            drawRect(mapX - 2, mapY - 2, mapX + mapW + 2, mapY + mapH + 2, 0x80000000);
            rebuildMapMarkers();
            mapController.draw();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        drawVillageList(mouseX, mouseY);

        // Map hover tooltip
        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
            }
        }
    }

    private void drawVillageList(int mouseX, int mouseY) {
        drawRect(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0x80000000);

        int money = MillCommonUtilities.countMoney(player.inventory);
        String feeStr = MillCommonUtilities.getShortPrice(lastFee);
        int col = money >= lastFee ? 0x00FF00 : 0xFF5555;
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.recruit.fee", feeStr),
                listX, listY - 10, col);

        clampScroll();
        int rowsToDraw = Math.min(getVisibleRows(), villages.size() - scrollOffset);
        for (int i = 0; i < rowsToDraw; i++) {
            int rowTop    = listY + i * rowH;
            int rowBottom = rowTop + rowH;
            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;
            int globalIdx = scrollOffset + i;
            boolean isSel = selected.contains(globalIdx);
            int bg = isSel ? 0x4033FF33 : (hovered ? 0x20FFFFFF : 0x20000000);
            drawRect(listX, rowTop, listX + listW, rowBottom, bg);

            VillageRow row = villages.get(globalIdx);
            float scale = 0.85F;
            String label = makeRowLabel(row, scale);

            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            this.fontRenderer.drawString((isSel ? "[X] " : "[ ] ") + label, 0, 0, 0xE0E0E0);
            GlStateManager.popMatrix();
        }
    }

    private String makeRowLabel(VillageRow row, float scale) {
        String full = row.display;
        String distSuffix = " (" + row.distance + "m)";
        String baseName = full;
        int idx = full.lastIndexOf(" (");
        if (idx > 0 && full.endsWith("m)")) baseName = full.substring(0, idx);
        int maxW = (int) ((this.listW - 6) / scale);
        return shortenNameWithSuffix(baseName, distSuffix, maxW);
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
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (this.fontRenderer.getStringWidth(prefix + String.valueOf(name.charAt(i))) > half) break;
            prefix.append(name.charAt(i));
        }
        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String candidate = name.charAt(i) + tail.toString();
            if (this.fontRenderer.getStringWidth(prefix + ellipsis + candidate) > remaining) break;
            tail.insert(0, name.charAt(i));
        }
        return prefix + ellipsis + tail + suffix;
    }

    public void receiveVillageList(List<BlockPos> centers, List<String> labels, List<Integer> distances) {
        villages.clear();
        int n = Math.min(centers.size(), Math.min(labels.size(), distances.size()));
        for (int i = 0; i < n; i++) {
            villages.add(new VillageRow(centers.get(i), labels.get(i), distances.get(i)));
        }
        villages.sort(java.util.Comparator.comparingInt(v -> v.distance));
        selected.clear();
        scrollOffset = 0;
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

    private static class VillageRow {
        final BlockPos center;
        final String display;
        final int distance;
        VillageRow(BlockPos center, String display, int distance) {
            this.center = center; this.display = display; this.distance = distance;
        }
    }
}
