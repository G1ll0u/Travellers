package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.unit.equipment.EquipLoadout;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ButtonTooltipHelper;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.worldmap.gui.GuiWorldMapPanel;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.worldmap.gui.WorldMapNavButtons;
import com.jubitus.travellers.worldmap.gui.WorkerMapScreenSupport;
import com.jubitus.travellers.worldmap.gui.WorldMapController;
import com.jubitus.travellers.worldmap.gui.WorldMapMarkerFactory;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.worldmap.net.MsgSubscribeWorkerMap;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worldmap.net.MsgRequestMapDiscovery;
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
    private WorldMapNavButtons navButtons;
    private GuiButton goButton;
    private GuiButton getEquipButton, dumpButton;
    // True while the Stay/Return dialog belongs to Dump Items rather than Get Equipment.
    private boolean dumpMission = false;

    private boolean showEquipDialog = false;
    private GuiButton equipStayButton, equipReturnButton, equipCancelButton;

    private static final int[] TICK_KINDS = {EquipLoadout.MELEE, EquipLoadout.BOW, EquipLoadout.SHIELD, EquipLoadout.ARMOR};
    private static final String[] TICK_KEYS = {"melee", "bow", "shield", "armour"};
    private final GuiTravellersButton[] tickButtons = new GuiTravellersButton[TICK_KINDS.length];
    private EquipLoadout ticks = EquipLoadout.DEFAULT;
    // The gear ticks only belong to Get Equipment at a Barrack or a village, not to Dump Items or a Stable.
    private boolean ticksShown = false;

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
    private final ScrollList list = new ScrollList(12);
    private static final float ROW_TEXT_SCALE = 0.85F;

    private final WorkerMapScreenSupport mapData = new WorkerMapScreenSupport();
    // The list rows are the received places, nearest first.
    private final List<MsgWorkerGoList.Entry> rows = mapData.places;
    private int selectedIndex = -1;
    private BlockPos selectedRowPos = null;
    private BlockPos selectedMapPos = null;

    private int guiLeft, guiTop;
    private static final int TICK_H = 16;

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

        list.setBounds(listX, listY, listW, listH);
        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_worker_map", homePosition());
        mapController.panel.setSelectedPos(null);
        selectedMapPos = null;

        this.buttonList.clear();

        int bw = 70, bh = 20, gap = 4;
        int buttonsTop = listY + listH + gap;

        this.goButton = this.addButton(new GuiTravellersButton(1, listX + listW - bw, buttonsTop, bw, bh,
                I18n.format("travellers.gui.go", "Go")));
        this.goButton.enabled = false;

        navButtons = new WorldMapNavButtons(mapController, buttonList, 2, this::homePosition);
        this.backButton = this.addButton(new GuiTravellersButton(9, listX, buttonsTop, 50, bh,
                I18n.format("travellers.gui.back", "Back")));

        // Second row under the list: Get Equipment (only for controlled villages / lone barracks)
        int buttonsTop2 = buttonsTop + bh + gap;
        int halfW = (listW - gap) / 2;
        this.getEquipButton = this.addButton(new GuiTravellersButton(10, listX, buttonsTop2, halfW, bh,
                I18n.format("travellers.worker.barracks.get_equip", "Get Equipment")));
        this.getEquipButton.enabled = false;
        this.dumpButton = this.addButton(new GuiTravellersButton(14, listX + halfW + gap, buttonsTop2, listW - halfW - gap, bh,
                I18n.format("travellers.worker.barracks.dump", "Dump Items")));
        this.dumpButton.enabled = false;

        // Inline equip dialog buttons (over the list), hidden until opened; placed by layoutEquipDialog
        this.equipStayButton   = this.addButton(new GuiTravellersButton(11, listX, listY, listW, bh,
                I18n.format("travellers.worker.barracks.equip_stay", "Equip and Stay")));
        this.equipReturnButton = this.addButton(new GuiTravellersButton(12, listX, listY, listW, bh,
                I18n.format("travellers.worker.barracks.equip_return", "Equip and Return")));
        this.equipCancelButton = this.addButton(new GuiTravellersButton(13, listX, listY, listW, bh,
                I18n.format("travellers.gui.cancel", "Cancel")));
        ticks = worker != null && worker.getEquipRole() != null ? worker.getEquipRole() : EquipLoadout.DEFAULT;
        for (int i = 0; i < TICK_KINDS.length; i++) {
            tickButtons[i] = this.addButton(new GuiTravellersButton(20 + i, listX, listY, halfW, TICK_H,
                    I18n.format("travellers.worker.formation.menu.loadout." + TICK_KEYS[i]))
                    .style(GuiTravellersButton.Style.TOGGLE));
        }
        refreshTicks();
        showEquipDialog = false;
        applyEquipDialogVisibility();

        tooltips.set(getEquipButton, "travellers.worker.barracks.get_equip.tooltip");
        tooltips.set(dumpButton, "travellers.worker.barracks.dump.tooltip");
        tooltips.set(equipStayButton, "travellers.worker.barracks.equip_stay.tooltip");
        tooltips.set(equipReturnButton, "travellers.worker.barracks.equip_return.tooltip");

        rows.clear();
        list.resetScroll();
        selectedIndex = -1;

        int listRequestId = repEntityId >= 0 ? repEntityId : -1;
        if (listRequestId >= 0)
            TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(listRequestId));
        mapData.subscribe();
    }

    /** Where the map opens and where Center goes back to. */
    private BlockPos homePosition() {
        return leaderMode && leaderPos != null ? leaderPos
                : (worker != null ? worker.getPosition() : player.getPosition());
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        mapData.unsubscribe();
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        mapData.setPlaces(entries);
        rows.sort(Comparator.comparingInt(r -> r.distance));
        selectedIndex = -1;
        goButton.enabled = false;
        if (getEquipButton != null) getEquipButton.enabled = false;
        list.resetScroll();
        rebuildMarkers();
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        mapData.setWorkers(list);
        rebuildMarkers();
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        int workerColor = worker != null ? worker.getOwnerMarkColor() : ClientOwnerVisualsCache.getColor();

        // Highlighted: the currently selected row (shown in gold)
        java.util.Set<BlockPos> highlighted = selectedRowPos != null ? Collections.singleton(selectedRowPos) : null;
        mapData.populate(mapController, player, workerColor, highlighted);

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
            else if (list.isOver(mouseX, mouseY))
                list.scrollBy(wheel, rows.size());
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

        if (mapController != null) mapController.drawTooltip(this, mouseX, mouseY);
        tooltips.render(mouseX, mouseY, this.width, this.height);
    }

    private void drawList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listX - 2, listY - 2, listW + 4, listH + 4);
        list.clamp(rows.size());

        int maxLabelW = (int) ((listW - 6) / ROW_TEXT_SCALE);
        for (int i = 0; i < list.rowsToDraw(rows.size()); i++) {
            int idx = list.scroll() + i;
            int rowTop = list.rowTop(idx);
            boolean hovered = list.isRowHovered(idx, mouseX, mouseY);
            TravellersGuiTheme.drawListRow(listX, rowTop, listW, list.rowH, hovered, idx == selectedIndex);

            MsgWorkerGoList.Entry r = rows.get(idx);
            String prefix = r.kind == MsgWorkerGoList.Kind.VILLAGE ? "[V] "
                    : r.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE ? "[CV] "
                    : r.kind == MsgWorkerGoList.Kind.STABLE ? "[S] "
                    : "";
            String label = TravellersGuiTheme.shortenMiddle(fontRenderer, prefix + r.label,
                    " (" + r.distance + "m)", maxLabelW);
            TravellersGuiTheme.drawScaledText(fontRenderer, label, listX + 3, rowTop + 2, ROW_TEXT_SCALE,
                    hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
        }
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
        if (mouseButton == 0) {
            int idx = list.rowAt(mouseX, mouseY, rows.size());
            if (idx >= 0) {
                selectedIndex = idx;
                MsgWorkerGoList.Entry r = rows.get(idx);
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
        if (button == backButton) {
            if (leaderMode && returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else {
                TravellersNetwork.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            }
            return;
        }

        if (button == getEquipButton || button == dumpButton) {
            dumpMission = button == dumpButton;
            updateGetEquipEnabled();
            showEquipDialog = true;
            applyEquipDialogVisibility();
            return;
        }
        for (int i = 0; i < TICK_KINDS.length; i++) {
            if (button == tickButtons[i]) {
                ticks = ticks.toggle(TICK_KINDS[i]);
                refreshTicks();
                return;
            }
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
                TravellersNetwork.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            }
            return;
        }

        if (navButtons.handle(button)) return;

        if (button == goButton) {
            if (leaderMode && leaderUUID != null) {
                BlockPos dest = selectedMapPos;
                if (dest == null && selectedIndex >= 0 && selectedIndex < rows.size())
                    dest = rows.get(selectedIndex).pos;
                if (dest == null) return;
                TravellersNetwork.CHANNEL.sendToServer(new MsgLeaderGoTo(leaderUUID, dest));
                mc.player.closeScreen();
                return;
            }
            if (selectedMapPos != null) {
                TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerGoToPos(worker.getEntityId(), selectedMapPos));
                mc.player.closeScreen();
                return;
            }
            if (selectedIndex < 0) return;
            TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerGoTo(worker.getEntityId(), selectedIndex));
            mc.player.closeScreen();
            return;
        }

        super.actionPerformed(button);
    }

    private void refreshTicks() {
        for (int i = 0; i < TICK_KINDS.length; i++) tickButtons[i].toggled(ticks.has(TICK_KINDS[i]));
    }

    /** Gear ticks in two columns on top when shown, then Stay / Return / Cancel. */
    private void layoutEquipDialog() {
        int gap = 4;
        int y = listY + 4;
        for (int i = 0; i < TICK_KINDS.length; i++) {
            GuiTravellersButton b = tickButtons[i];
            b.x = i % 2 == 0 ? listX : listX + listW - b.width;
            b.y = y + (i / 2) * (TICK_H + gap);
            b.visible = showEquipDialog && ticksShown;
        }
        if (ticksShown) y += 2 * (TICK_H + gap) + gap;
        for (GuiButton b : new GuiButton[]{equipStayButton, equipReturnButton, equipCancelButton}) {
            b.y = y;
            y += b.height + gap;
        }
    }

    private void applyEquipDialogVisibility() {
        boolean d = showEquipDialog;
        layoutEquipDialog();
        equipStayButton.visible = d;
        equipReturnButton.visible = d;
        equipCancelButton.visible = d;

        goButton.visible = !d;
        getEquipButton.visible = !d;
        dumpButton.visible = !d;
        backButton.visible = !d;
        navButtons.setVisible(!d);
    }

    /** Get Equipment is allowed for a controlled village, a lone barrack, or a stable row. */
    private void updateGetEquipEnabled() {
        boolean ok = selectedMapPos == null && selectedIndex >= 0 && selectedIndex < rows.size()
                && (rows.get(selectedIndex).kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE
                    || rows.get(selectedIndex).kind == MsgWorkerGoList.Kind.HOME
                    || rows.get(selectedIndex).kind == MsgWorkerGoList.Kind.STABLE);
        if (getEquipButton != null) getEquipButton.enabled = ok;
        boolean stable = ok && rows.get(selectedIndex).kind == MsgWorkerGoList.Kind.STABLE;
        if (dumpButton != null) dumpButton.enabled = ok && !stable;
        boolean dump = dumpMission && !stable;
        ticksShown = ok && !stable && !dump;
        if (getEquipButton != null) {
            getEquipButton.displayString = I18n.format(stable
                    ? "travellers.worker.barracks.take_horses" : "travellers.worker.barracks.get_equip");
            tooltips.set(getEquipButton, stable
                    ? "travellers.worker.barracks.take_horses.tooltip" : "travellers.worker.barracks.get_equip.tooltip");
        }
        String missionKey = stable ? "take_horses" : dump ? "dump" : "equip";
        if (equipStayButton != null) {
            equipStayButton.displayString = I18n.format("travellers.worker.barracks." + missionKey + "_stay");
            tooltips.set(equipStayButton, "travellers.worker.barracks." + missionKey + "_stay.tooltip");
        }
        if (equipReturnButton != null) {
            equipReturnButton.displayString = I18n.format("travellers.worker.barracks." + missionKey + "_return");
            tooltips.set(equipReturnButton, "travellers.worker.barracks." + missionKey + "_return.tooltip");
        }
    }

    private void sendGetEquipment(boolean stay) {
        if (selectedIndex < 0 || selectedIndex >= rows.size()) return;
        MsgWorkerGoList.Entry r = rows.get(selectedIndex);
        com.jubitus.travellers.unit.equipment.EquipTargetKind kind =
                r.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE
                        ? com.jubitus.travellers.unit.equipment.EquipTargetKind.CONTROLLED_VILLAGE
                        : r.kind == MsgWorkerGoList.Kind.STABLE
                        ? com.jubitus.travellers.unit.equipment.EquipTargetKind.STABLE
                        : com.jubitus.travellers.unit.equipment.EquipTargetKind.LONE_BARRACK;
        if (dumpMission && kind != com.jubitus.travellers.unit.equipment.EquipTargetKind.STABLE) kind = kind.toDump();
        UUID targetUUID = leaderMode ? leaderUUID : (worker != null ? worker.getUniqueID() : null);
        if (targetUUID == null || r.pos == null) return;
        TravellersNetwork.CHANNEL.sendToServer(
                new com.jubitus.travellers.unit.equipment.net.MsgWorkerGetEquipment(
                        targetUUID, stay, kind, r.pos, ticks));
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
