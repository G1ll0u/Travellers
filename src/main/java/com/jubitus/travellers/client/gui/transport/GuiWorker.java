package com.jubitus.travellers.client.gui.transport;

import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.worldmap.IWorkerGoListReceiver;
import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.WorldMapContentPopulator;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.server.net.message.villageshomes.MsgWorkerRequestVillages;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerRequestGoList;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerSetDestination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
@SideOnly(Side.CLIENT)
public class GuiWorker extends GuiContainer implements IWorkerGoListReceiver, IWorkerMapReceiver, ITravellersGui {

    private static final int MAP_W = 150;

    private static final int COL_BG       = TravellersGuiTheme.COL_BG;
    private static final int COL_BORDER   = TravellersGuiTheme.COL_BORDER;
    private static final int COL_TEXT     = TravellersGuiTheme.COL_TEXT;
    private static final int COL_TEXT_DIM = TravellersGuiTheme.COL_TEXT_DIM;
    private static final int COL_SEL      = TravellersGuiTheme.COL_ROW_SEL;
    private static final int COL_HOVER    = TravellersGuiTheme.COL_ROW_HOVER;
    private static final int COL_PANEL    = TravellersGuiTheme.COL_PANEL;

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<VillageRow> villages = new ArrayList<>();

    private GuiButton sendButton;
    private GuiButton validateButton;
    private GuiButton donateButton;
    private GuiButton backButton;

    private int lastCost = 0;
    private boolean costValid = false;
    private int validatedVillageIdx = -1;

    private int listX, listY, listW, listH;
    private final int rowH = 12;
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    // ── Map data ──────────────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final List<MsgWorkerGoList.Entry> poiEntries = new ArrayList<>();
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

    public GuiWorker(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorker(player.inventory, worker));
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 150;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.xSize = 176;
        this.ySize = 150;
        listW = 120;
        listH = 120;

        int totalW = MAP_W + 8 + xSize + 8 + listW;
        int startX = Math.max(4, (this.width - totalW) / 2);

        int mapX = startX;
        this.guiLeft = startX + MAP_W + 8;
        this.guiTop = (this.height - this.ySize) / 2;

        this.listX = this.guiLeft + this.xSize + 8;
        this.listY = this.guiTop + 4;

        this.buttonList.clear();

        int buttonWidth = 60, buttonHeight = 20, buttonGapX = 4, buttonGapY = 4;
        int buttonsTop = listY + listH + buttonGapY;

        this.validateButton = this.addButton(new GuiButton(
                0, listX, buttonsTop, buttonWidth, buttonHeight,
                I18n.format("travellers.worker.validate", "Validate")));

        this.sendButton = this.addButton(new GuiButton(
                1, listX + buttonWidth + buttonGapX, buttonsTop, buttonWidth, buttonHeight,
                I18n.format("travellers.worker.send", "Send")));

        this.backButton = this.addButton(new GuiButton(
                3, listX, buttonsTop + buttonHeight + buttonGapY,
                buttonWidth, buttonHeight, I18n.format("travellers.gui.back", "Back")));

        this.donateButton = this.addButton(new GuiButton(
                2, listX + buttonWidth + buttonGapX, buttonsTop + buttonHeight + buttonGapY,
                buttonWidth, buttonHeight, I18n.format("travellers.worker.donate", "Donate")));

        villages.clear();
        scrollOffset = 0;
        selectedIndex = -1;
        costValid = false;
        validatedVillageIdx = -1;
        lastCost = 0;
        this.sendButton.enabled = false;
        this.donateButton.enabled = false;
        this.validateButton.enabled = true;

        boolean hired = worker.isHiredFollower();
        UUID ownerId = worker.getFollowOwnerId();
        boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());
        if (!hiredByMe) {
            this.sendButton.enabled = false;
            this.donateButton.enabled = false;
            this.validateButton.enabled = false;
        }

        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, guiTop, MAP_W, ySize,
                "travellers_worker_transport_map", worker.getPosition());

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        if (hiredByMe)
            TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestVillages(worker.getEntityId()));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        poiEntries.clear();
        if (entries != null) poiEntries.addAll(entries);
        rebuildMapMarkers();
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
        rebuildMapMarkers();
    }

    private void rebuildMapMarkers() {
        if (mapController == null) return;

        // Highlighted village (the currently selected row)
        java.util.Set<net.minecraft.util.math.BlockPos> highlighted = null;
        if (selectedIndex >= 0 && selectedIndex < villages.size()) {
            highlighted = java.util.Collections.singleton(villages.get(selectedIndex).center);
        }

        mapController.panel.clearMarkers();
        WorldMapContentPopulator.populate(
                mapController.panel, poiEntries, workerEntries, player, playerOwnerColor, highlighted);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        if (mapController != null) {
            // Cadre thématisé autour de la carte
            drawRect(mapController.mapX - 2, mapController.mapY - 2,
                    mapController.mapX + MAP_W + 2, mapController.mapY + ySize + 2, COL_PANEL);
            drawRect(mapController.mapX - 1, mapController.mapY - 1,
                    mapController.mapX + MAP_W + 1, mapController.mapY + ySize + 1, COL_BORDER);
            rebuildMapMarkers();
            mapController.draw();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        drawVillageList(mouseX, mouseY);
        this.renderHoveredToolTip(mouseX, mouseY);

        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
            }
        }
        com.jubitus.travellers.client.notify.NotificationOverlayRenderer.draw(new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()));
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        this.fontRenderer.drawString(I18n.format("travellers.worker.title", "Worker"), 8, 6, COL_TEXT);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int x = this.guiLeft;
        int y = this.guiTop;

        // Rendu vectoriel du fond beige/marron de la boîte de dialogue centrale
        drawRect(x, y, x + xSize, y + ySize, COL_BG);
        drawRect(x, y, x + xSize, y + 1, COL_BORDER);
        drawRect(x, y + ySize - 1, x + xSize, y + ySize, COL_BORDER);
        drawRect(x, y, x + 1, y + ySize, COL_BORDER);
        drawRect(x + xSize - 1, y, x + xSize, y + ySize, COL_BORDER);

        // Rendu dynamique de l'arriere-plan des slots du Container
        for (int i = 0; i < this.inventorySlots.inventorySlots.size(); i++) {
            Slot s = this.inventorySlots.inventorySlots.get(i);
            if (s != null) {
                int slotX = x + s.xPos;
                int slotY = y + s.yPos;
                drawRect(slotX - 1, slotY - 1, slotX + 17, slotY + 17, COL_BORDER);
                drawRect(slotX, slotY, slotX + 16, slotY + 16, COL_PANEL);
            }
        }
    }

    private void drawVillageList(int mouseX, int mouseY) {
        if (villages.isEmpty()) {
            this.fontRenderer.drawString("No village in range...", listX, listY, COL_TEXT_DIM);
            String costLabel;
            int color;
            if (!costValid || selectedIndex < 0 || selectedIndex >= villages.size()
                    || validatedVillageIdx != selectedIndex) {
                costLabel = I18n.format("travellers.worker.cost_unknown");
                color = COL_TEXT_DIM;
            } else {
                String costStr = MillCommonUtilities.getShortPrice(lastCost);
                costLabel = I18n.format("travellers.worker.cost", costStr);
                int playerMoney = MillCommonUtilities.countMoney(this.player.inventory);
                color = playerMoney >= lastCost ? 0x00FF00 : 0xFF5555; // Garde le indicateurs de prix vert/rouge
            }
            this.fontRenderer.drawString(costLabel, listX, listY - 10, color);
            return;
        }

        // Panneau et bordure de la liste de villages de droite
        drawRect(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, COL_PANEL);
        drawRect(listX - 2, listY - 2, listX + listW + 2, listY - 1, COL_BORDER);
        drawRect(listX - 2, listY + listH + 1, listX + listW + 2, listY + listH + 2, COL_BORDER);
        drawRect(listX - 2, listY - 2, listX - 1, listY + listH + 2, COL_BORDER);
        drawRect(listX + listW + 1, listY - 2, listX + listW + 2, listY + listH + 2, COL_BORDER);

        clampScroll();
        int maxRows = getVisibleRows();
        int rowsToDraw = Math.min(maxRows, villages.size() - scrollOffset);
        final float scale = 0.85F;

        for (int i = 0; i < rowsToDraw; i++) {
            int rowTop    = listY + i * rowH;
            int rowBottom = rowTop + rowH;
            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;
            int globalIdx = scrollOffset + i;

            int bg = (globalIdx == selectedIndex) ? COL_SEL : (hovered ? COL_HOVER : 0);
            if (bg != 0) drawRect(listX, rowTop, listX + listW, rowBottom, bg);

            VillageRow row = villages.get(globalIdx);
            String label = makeRowLabel(row, scale);
            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            this.fontRenderer.drawString(label, 0, 0, hovered ? 0xFFFFCC88 : COL_TEXT);
            GlStateManager.popMatrix();
        }

        String costStr = MillCommonUtilities.getShortPrice(0);
        if (selectedIndex >= 0 && selectedIndex < villages.size()) {
            int usedSlots = getUsedCargoSlotsClient();
            int cost = EntityTravellerWorker.computeTravelCost(villages.get(selectedIndex).distance, usedSlots);
            costStr = MillCommonUtilities.getShortPrice(cost);
        }
        this.fontRenderer.drawString(I18n.format("travellers.worker.cost", costStr), listX, listY - 10, COL_TEXT);
    }

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
            int dir = (wheel > 0) ? -1 : 1;
            scrollOffset = Math.max(0, Math.min(scrollOffset + dir, getMaxScroll()));
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mapController != null) mapController.onMouseClick(mouseX, mouseY, mouseButton);

        if (mouseButton == 0 && !villages.isEmpty()
                && mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH) {
            int idx = scrollOffset + (mouseY - listY) / rowH;
            if (idx >= 0 && idx < villages.size()) {
                selectedIndex = idx;
                costValid = false;
                validatedVillageIdx = -1;
                lastCost = 0;
                if (sendButton  != null) sendButton.enabled  = false;
                if (donateButton != null) donateButton.enabled = false;
                if (mapController != null) mapController.setCenter(villages.get(idx).center);
                rebuildMapMarkers();
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
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            return;
        }

        if (button == sendButton) {
            if (sendButton.enabled && selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgWorkerSetDestination(worker.getEntityId(), row.center, row.display, false));
                mc.player.closeScreen();
            }
            return;
        }

        if (button == donateButton) {
            if (donateButton.enabled && selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgWorkerSetDestination(worker.getEntityId(), row.center, row.display, true));
                mc.player.closeScreen();
            }
            return;
        }

        if (button == validateButton) {
            if (selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                int usedSlots = getUsedCargoSlotsClient();
                int cost = EntityTravellerWorker.computeTravelCost(row.distance, usedSlots);
                this.lastCost = cost;
                this.validatedVillageIdx = selectedIndex;
                this.costValid = true;
                int playerMoney = MillCommonUtilities.countMoney(this.player.inventory);
                boolean canAfford = cost == 0 || playerMoney >= cost;
                this.sendButton.enabled = canAfford;
                this.donateButton.enabled = canAfford;
            } else {
                this.costValid = false;
                this.validatedVillageIdx = -1;
                this.lastCost = 0;
                this.sendButton.enabled = false;
                this.donateButton.enabled = false;
            }
            return;
        }

        super.actionPerformed(button);
    }

    public void receiveVillageList(List<BlockPos> centers, List<String> labels, List<Integer> distances) {
        this.villages.clear();
        int n = Math.min(centers.size(), Math.min(labels.size(), distances.size()));
        for (int i = 0; i < n; i++) {
            this.villages.add(new VillageRow(centers.get(i), labels.get(i), distances.get(i)));
        }
        this.villages.sort(Comparator.comparingInt(v -> v.distance));

        if (villages.isEmpty()) {
            this.selectedIndex = -1;
        } else {
            this.selectedIndex = 0;
            if (mapController != null) mapController.setCenter(villages.get(0).center);
        }
        if (sendButton  != null) sendButton.enabled  = false;
        if (donateButton != null) donateButton.enabled = false;

        costValid = false;
        validatedVillageIdx = -1;
        lastCost = 0;
        scrollOffset = 0;
        clampScroll();
        rebuildMapMarkers();
    }

    private int getUsedCargoSlotsClient() {
        int used = 0;
        for (int i = 0; i < 18 && i < this.inventorySlots.inventorySlots.size(); i++) {
            Slot s = this.inventorySlots.getSlot(i);
            if (s != null && s.getHasStack()) used++;
        }
        return used;
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
        if (this.fontRenderer.getStringWidth(name + suffix) <= maxWidth) return name + suffix;
        int sw = this.fontRenderer.getStringWidth(suffix);
        if (sw >= maxWidth) return this.fontRenderer.trimStringToWidth(suffix, maxWidth);
        int remaining = maxWidth - sw;
        String ellipsis = "...";
        int ew = this.fontRenderer.getStringWidth(ellipsis);
        if (ew >= remaining) return suffix;
        int half = (remaining - ew) / 2;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (this.fontRenderer.getStringWidth(prefix + String.valueOf(name.charAt(i))) > half) break;
            prefix.append(name.charAt(i));
        }
        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String c = name.charAt(i) + tail.toString();
            if (this.fontRenderer.getStringWidth(prefix + ellipsis + c) > remaining) break;
            tail.insert(0, name.charAt(i));
        }
        return prefix + ellipsis + tail + suffix;
    }

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

    public Rectangle getJeiListArea() {
        return new Rectangle(listX - 2, listY - 12, listW + 4, listH + 38);
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