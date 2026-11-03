package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.ButtonTooltipHelper;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.GuiTravellersContainer;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.MoneyText;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.unit.DeliveryJob;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.worker.inventory.ContainerWorker;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestVillages;
import com.jubitus.travellers.worker.net.MsgWorkerSetDestination;
import com.jubitus.travellers.worldmap.gui.WorkerMapView;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Transport mission: cargo and the player's inventory on the left; map, known villages with the travel cost,
 * Sell / Donate and Back on the right. Sell and Donate stay greyed (tooltip says why) until the order can go.
 */
@SideOnly(Side.CLIENT)
public class GuiWorker extends GuiTravellersContainer implements IWorkerGoListReceiver, IWorkerMapScreen {

    private static final int CARGO_SLOTS = 18;
    private static final int SIDE_W = 170;
    private static final int GAP = MapListLayout.GAP;
    private static final int LABEL_H = 10;
    private static final int HEADER_H = 12;
    private static final int LIST_ROWS = 5;
    private static final int MIN_MAP_H = 40;

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<VillageRow> villages = new ArrayList<>();
    private final ScrollList list = new ScrollList();
    private final ButtonTooltipHelper tooltips = new ButtonTooltipHelper();
    private int selectedIndex = -1;

    private GuiButton sellButton, donateButton, backButton;
    private WorkerMapView map;
    private int sideX, mapBoxY, mapBoxH, listBoxY, listBoxH;
    private int cargoLabelY, invLabelY;

    public GuiWorker(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorker(player.inventory, worker, false));
        this.player = player;
        this.worker = worker;
    }

    @Override
    protected String title() {
        return I18n.format("travellers.worker.title");
    }

    @Override
    protected int frameW() {
        return MapListLayout.PAD * 2 + GRID_W + GAP + SIDE_W;
    }

    @Override
    protected int frameH() {
        return 230;
    }

    @Override
    protected void layoutSlots() {
        int left = panel.bodyX, top = panel.bodyY, bottom = panel.bodyY + panel.bodyH;
        int rowH = MapListLayout.BTN_H, gap = MapListLayout.BTN_GAP;

        // Left: cargo at the top, the player's inventory sitting on the frame's bottom edge.
        cargoLabelY = top;
        placeGrid(0, CARGO_SLOTS, 9, left, top + LABEL_H);
        int invY = bottom - PLAYER_INV_H;
        invLabelY = invY - LABEL_H;
        placePlayerInventory(CARGO_SLOTS, left, invY);

        // Right: map, list, then [Sell][Donate] and [Back][Center], bottom row level with the hotbar's bottom.
        sideX = left + GRID_W + GAP;
        int row1 = bottom - rowH, row0 = row1 - gap - rowH;
        int half = (SIDE_W - gap) / 2;
        sellButton = addButton(new GuiTravellersButton(1, sideX, row0, half, rowH, I18n.format("travellers.worker.send")));
        donateButton = addButton(new GuiTravellersButton(2, sideX + SIDE_W - half, row0, half, rowH, I18n.format("travellers.worker.donate")));
        backButton = addButton(new GuiTravellersButton(3, sideX, row1, half, rowH, I18n.format("travellers.gui.back")));

        int sideH = row0 - gap - top;
        listBoxH = Math.min(HEADER_H + LIST_ROWS * list.rowH + 2, sideH - gap - MIN_MAP_H);
        listBoxY = row0 - gap - listBoxH;
        mapBoxY = top;
        mapBoxH = listBoxY - gap - mapBoxY;
        list.setBounds(sideX + 1, listBoxY + 1 + HEADER_H, SIDE_W - 2, listBoxH - 2 - HEADER_H);
        list.resetScroll();

        map = new WorkerMapView(sideX + 1, mapBoxY + 1, SIDE_W - 2, mapBoxH - 2,
                "travellers_worker_transport_map", worker.getPosition());
        map.addNav(buttonList, 10, worker::getPosition, sideX + SIDE_W - half, row1, half);
        map.setSelected((BlockPos) null);

        tooltips.clear();
        tooltips.set(sellButton, () -> tooltip("travellers.worker.send.tooltip"));
        tooltips.set(donateButton, () -> tooltip("travellers.worker.donate.tooltip"));

        villages.clear();
        selectedIndex = -1;
        updateButtons();

        map.subscribe();
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        if (isMine()) TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestVillages(worker.getEntityId()));
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

    public void receiveVillageList(List<BlockPos> centers, List<String> labels, List<Integer> distances) {
        villages.clear();
        villages.addAll(VillageRow.fromServer(centers, labels, distances));
        list.resetScroll();
        select(villages.isEmpty() ? -1 : 0);
    }

    // ── Order state ───────────────────────────────────────────────────────────

    private boolean isMine() {
        return WorkerLookup.isOwnedBy(worker, player.getUniqueID());
    }

    @Nullable
    private VillageRow selected() {
        return selectedIndex >= 0 && selectedIndex < villages.size() ? villages.get(selectedIndex) : null;
    }

    private int usedCargoSlots() {
        int used = 0;
        for (int i = 0; i < CARGO_SLOTS; i++) {
            if (inventorySlots.getSlot(i).getHasStack()) used++;
        }
        return used;
    }

    private int cost() {
        VillageRow row = selected();
        return row == null ? 0 : DeliveryJob.computeTravelCost(row.distance, usedCargoSlots());
    }

    /** Why the order cannot go yet, or null when Sell and Donate are allowed. */
    @Nullable
    private String blockReason() {
        if (!isMine()) return I18n.format("travellers.worker.blocked.not_yours");
        if (selected() == null) return I18n.format("travellers.worker.blocked.no_village");
        if (usedCargoSlots() == 0) return I18n.format("travellers.worker.blocked.empty_cargo");
        int cost = cost();
        if (!MillMoney.canAfford(player, cost)) {
            return I18n.format("travellers.worker.blocked.money", MillMoney.format(cost),
                    MillMoney.format(MillMoney.countPlayerMoney(player)));
        }
        return null;
    }

    private String tooltip(String allowedKey) {
        String reason = blockReason();
        return reason != null ? reason : I18n.format(allowedKey);
    }

    private void updateButtons() {
        boolean ok = blockReason() == null;
        sellButton.enabled = ok;
        donateButton.enabled = ok;
    }

    private void select(int idx) {
        selectedIndex = idx;
        VillageRow row = selected();
        if (row != null) map.controller.setCenter(row.center);
        map.setSelected(row != null ? row.center : null);
        updateButtons();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        updateButtons(); // cargo and purse change while the screen is open
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        map.drawTooltip(this, mouseX, mouseY);
        tooltips.render(mouseX, mouseY, width, height);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
        fontRenderer.drawString(I18n.format("travellers.worker.cargo"), panel.bodyX, cargoLabelY, TravellersGuiTheme.COL_TEXT_DIM);
        fontRenderer.drawString(I18n.format("container.inventory"), panel.bodyX, invLabelY, TravellersGuiTheme.COL_TEXT_DIM);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        TravellersGuiTheme.drawInnerPanel(sideX, mapBoxY, SIDE_W, mapBoxH);
        TravellersGuiTheme.drawInnerPanel(sideX, listBoxY, SIDE_W, listBoxH);
        map.draw(player, ClientOwnerVisualsCache.getColor(), mouseX, mouseY);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        drawVillageList(mouseX, mouseY);
    }

    private void drawVillageList(int mouseX, int mouseY) {
        int hy = listBoxY + 1 + (HEADER_H - fontRenderer.FONT_HEIGHT) / 2;
        if (villages.isEmpty()) {
            for (String line : fontRenderer.listFormattedStringToWidth(I18n.format("travellers.worker.no_village_in_range"), SIDE_W - 8)) {
                fontRenderer.drawString(line, sideX + 4, hy, TravellersGuiTheme.COL_TEXT_DIM);
                hy += fontRenderer.FONT_HEIGHT + 1;
            }
            return;
        }
        MoneyText.draw(fontRenderer, I18n.format("travellers.worker.cost", MillMoney.format(cost())), sideX + 4, hy,
                TravellersGuiTheme.COL_TEXT);
        TravellersGuiTheme.drawSeparator(list.x, list.y - 1, list.w);

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
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (map.isMouseOver(mouseX, mouseY)) map.wheel(mouseX, mouseY, wheel);
        else if (list.isOver(mouseX, mouseY)) list.scrollBy(wheel, villages.size());
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        map.mouseDown(mouseX, mouseY, mouseButton);
        if (mouseButton != 0 || list.mouseDown(mouseX, mouseY, villages.size())) return;
        int idx = list.rowAt(mouseX, mouseY, villages.size());
        if (idx >= 0) select(idx);
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
            TravellersNetwork.CHANNEL.sendToServer(new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            return;
        }
        if ((button == sellButton || button == donateButton) && blockReason() == null) {
            VillageRow row = selected();
            TravellersNetwork.CHANNEL.sendToServer(
                    new MsgWorkerSetDestination(worker.getEntityId(), row.center, row.display, button == donateButton));
            mc.player.closeScreen();
            return;
        }
        super.actionPerformed(button);
    }
}
