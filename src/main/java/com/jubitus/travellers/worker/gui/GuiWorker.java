package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.MoneyText;
import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ButtonTooltipHelper;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapScreen;
import com.jubitus.travellers.worldmap.gui.WorkerMapView;
import com.jubitus.travellers.unit.DeliveryJob;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.worldmap.net.MsgSubscribeWorkerMap;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worldmap.net.MsgRequestMapDiscovery;
import com.jubitus.travellers.worker.net.MsgWorkerRequestVillages;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import com.jubitus.travellers.worker.net.MsgWorkerSetDestination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import com.jubitus.travellers.worker.inventory.ContainerWorker;
@SideOnly(Side.CLIENT)
public class GuiWorker extends GuiContainer implements IWorkerGoListReceiver, IWorkerMapScreen, ITravellersGui {

    private static final int GAP = 8;
    private static final int LIST_W = 140;
    private static final int HEADER_H = 12;

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<VillageRow> villages = new ArrayList<>();

    private GuiButton sendButton;
    private GuiButton validateButton;
    private GuiButton donateButton;
    private GuiButton backButton;

    private final ButtonTooltipHelper tooltips = new ButtonTooltipHelper();

    private int lastCost = 0;
    private boolean costValid = false;
    private int validatedVillageIdx = -1;

    private int mapBoxX, mapBoxW, listBoxX, listBoxH;
    private final ScrollList list = new ScrollList();
    private int selectedIndex = -1;

    private WorkerMapView map;

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

        int mapW = Math.max(150, Math.min(240, width - 16 - xSize - LIST_W - GAP * 2));
        int totalW = mapW + GAP + xSize + GAP + LIST_W;
        int startX = Math.max(4, (this.width - totalW) / 2);
        this.guiLeft = startX + mapW + GAP;
        this.guiTop = (this.height - this.ySize) / 2;

        // Map | inventory | list share their top and bottom edges; the map's nav row is level with the last button row.
        int rowH = MapListLayout.BTN_H, gap = MapListLayout.BTN_GAP;
        mapBoxX = startX;
        mapBoxW = mapW;
        listBoxX = guiLeft + xSize + GAP;
        listBoxH = ySize - 2 * (rowH + gap);
        list.setBounds(listBoxX + 1, guiTop + 1 + HEADER_H, LIST_W - 2, listBoxH - 2 - HEADER_H);

        this.buttonList.clear();
        int buttonW = (LIST_W - gap) / 2;
        int row0 = guiTop + listBoxH + gap, row1 = guiTop + ySize - rowH;
        int col1 = listBoxX + buttonW + gap;

        this.validateButton = this.addButton(new GuiTravellersButton(
                0, listBoxX, row0, buttonW, rowH, I18n.format("travellers.worker.validate", "Validate")));
        this.sendButton = this.addButton(new GuiTravellersButton(
                1, col1, row0, buttonW, rowH, I18n.format("travellers.worker.send", "Send")));
        this.backButton = this.addButton(new GuiTravellersButton(
                3, listBoxX, row1, buttonW, rowH, I18n.format("travellers.gui.back", "Back")));
        this.donateButton = this.addButton(new GuiTravellersButton(
                2, col1, row1, buttonW, rowH, I18n.format("travellers.worker.donate", "Donate")));

        tooltips.set(sendButton, "travellers.worker.send.tooltip");
        tooltips.set(donateButton, "travellers.worker.donate.tooltip");

        villages.clear();
        list.resetScroll();
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

        map = new WorkerMapView(mapBoxX + 1, guiTop + 1, mapW - 2, ySize - 2 - (rowH + gap),
                "travellers_worker_transport_map", worker.getPosition());
        map.addNav(buttonList, 10, worker::getPosition, row1);
        map.setSelected((BlockPos) null);

        map.subscribe();
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        if (hiredByMe)
            TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestVillages(worker.getEntityId()));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        map.unsubscribe();
    }

    @Override
    public WorkerMapView mapView() {
        return map;
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        map.data.setPlaces(entries);
    }

    private void refreshSelection() {
        map.setSelected(selectedIndex >= 0 && selectedIndex < villages.size() ? villages.get(selectedIndex).center : null);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        TravellersGuiTheme.drawInnerPanel(mapBoxX, guiTop, map.controller.mapW + 2, ySize - (MapListLayout.BTN_H + MapListLayout.BTN_GAP));
        TravellersGuiTheme.drawInnerPanel(listBoxX, guiTop, LIST_W, listBoxH);
        map.draw(player, ClientOwnerVisualsCache.getColor(), mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        drawVillageList(mouseX, mouseY);
        this.renderHoveredToolTip(mouseX, mouseY);

        map.drawTooltip(this, mouseX, mouseY);
        tooltips.render(mouseX, mouseY, this.width, this.height);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        this.fontRenderer.drawString(TextFormatting.BOLD + I18n.format("travellers.worker.title", "Worker"), 8, 6, TravellersGuiTheme.COL_TEXT);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int x = this.guiLeft;
        int y = this.guiTop;

        TravellersGuiTheme.drawPanel(x, y, xSize, ySize);

        // Slot backgrounds
        for (int i = 0; i < this.inventorySlots.inventorySlots.size(); i++) {
            Slot s = this.inventorySlots.inventorySlots.get(i);
            if (s != null) {
                int slotX = x + s.xPos;
                int slotY = y + s.yPos;
                TravellersGuiTheme.drawInnerPanel(slotX - 1, slotY - 1, 18, 18);
            }
        }
    }

    private void drawVillageList(int mouseX, int mouseY) {
        int hy = guiTop + 1 + (HEADER_H - fontRenderer.FONT_HEIGHT) / 2;
        if (villages.isEmpty()) {
            fontRenderer.drawString(I18n.format("travellers.worker.cost_unknown"), listBoxX + 4, hy, TravellersGuiTheme.COL_TEXT_DIM);
            fontRenderer.drawString(fontRenderer.trimStringToWidth(I18n.format("travellers.worker.no_village_in_range"), LIST_W - 8),
                    listBoxX + 4, list.y + 2, TravellersGuiTheme.COL_TEXT_DIM);
            return;
        }

        list.clamp(villages.size());
        int rowW = list.rowW(villages.size());
        for (int i = 0; i < list.rowsToDraw(villages.size()); i++) {
            int idx = list.scroll() + i;
            int rowTop = list.rowTop(idx);
            boolean hovered = list.isRowHovered(idx, mouseX, mouseY) && mouseX < list.x + rowW;
            TravellersGuiTheme.drawListRow(list.x, rowTop, rowW, list.rowH, hovered, idx == selectedIndex);
            fontRenderer.drawString(villages.get(idx).label(fontRenderer, rowW - ScrollList.TEXT_PAD * 2),
                    list.x + ScrollList.TEXT_PAD, list.textY(fontRenderer, rowTop),
                    hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
        }
        list.drawScrollbar(villages.size());

        String costStr = MillMoney.format(0);
        if (selectedIndex >= 0 && selectedIndex < villages.size()) {
            int usedSlots = getUsedCargoSlotsClient();
            int cost = DeliveryJob.computeTravelCost(villages.get(selectedIndex).distance, usedSlots);
            costStr = MillMoney.format(cost);
        }
        MoneyText.draw(this.fontRenderer, I18n.format("travellers.worker.cost", costStr), listBoxX + 4, hy,
                TravellersGuiTheme.COL_TEXT);
        TravellersGuiTheme.drawSeparator(list.x, list.y - 1, list.w);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel  = Mouse.getEventDWheel();
        if (wheel == 0) return;

        if (map.isMouseOver(mouseX, mouseY)) {
            map.wheel(mouseX, mouseY, wheel);
        } else if (list.isOver(mouseX, mouseY)) {
            list.scrollBy(wheel, villages.size());
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        map.mouseDown(mouseX, mouseY, mouseButton);
        if (mouseButton != 0 || list.mouseDown(mouseX, mouseY, villages.size())) return;

        int idx = list.rowAt(mouseX, mouseY, villages.size());
        if (idx >= 0) {
            selectedIndex = idx;
            costValid = false;
            validatedVillageIdx = -1;
            lastCost = 0;
            if (sendButton  != null) sendButton.enabled  = false;
            if (donateButton != null) donateButton.enabled = false;
            map.controller.setCenter(villages.get(idx).center);
            refreshSelection();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        map.mouseDrag(mouseX, mouseY);
        list.mouseDrag(mouseY, villages.size());
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        map.mouseUp(state);
        list.mouseUp();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (map.handleButton(button)) return;
        if (button == backButton) {
            TravellersNetwork.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            return;
        }

        if (button == sendButton) {
            if (sendButton.enabled && selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                TravellersNetwork.CHANNEL.sendToServer(
                        new MsgWorkerSetDestination(worker.getEntityId(), row.center, row.display, false));
                mc.player.closeScreen();
            }
            return;
        }

        if (button == donateButton) {
            if (donateButton.enabled && selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                TravellersNetwork.CHANNEL.sendToServer(
                        new MsgWorkerSetDestination(worker.getEntityId(), row.center, row.display, true));
                mc.player.closeScreen();
            }
            return;
        }

        if (button == validateButton) {
            if (selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                int usedSlots = getUsedCargoSlotsClient();
                int cost = DeliveryJob.computeTravelCost(row.distance, usedSlots);
                this.lastCost = cost;
                this.validatedVillageIdx = selectedIndex;
                this.costValid = true;
                int playerMoney = MillMoney.countPlayerMoney(this.player);
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
        this.villages.addAll(VillageRow.fromServer(centers, labels, distances));

        if (villages.isEmpty()) {
            this.selectedIndex = -1;
        } else {
            this.selectedIndex = 0;
            map.controller.setCenter(villages.get(0).center);
        }
        if (sendButton  != null) sendButton.enabled  = false;
        if (donateButton != null) donateButton.enabled = false;

        costValid = false;
        validatedVillageIdx = -1;
        lastCost = 0;
        list.resetScroll();
        refreshSelection();
    }

    private int getUsedCargoSlotsClient() {
        int used = 0;
        for (int i = 0; i < 18 && i < this.inventorySlots.inventorySlots.size(); i++) {
            Slot s = this.inventorySlots.getSlot(i);
            if (s != null && s.getHasStack()) used++;
        }
        return used;
    }

}
