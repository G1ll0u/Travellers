package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.follow.net.MsgLeaderGoTo;
import com.jubitus.travellers.gui.GuiMapListScreen;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.equipment.EquipLoadout;
import com.jubitus.travellers.unit.equipment.EquipTargetKind;
import com.jubitus.travellers.unit.equipment.net.MsgWorkerGetEquipment;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerGoTo;
import com.jubitus.travellers.worker.net.MsgWorkerGoToPos;
import com.jubitus.travellers.worldmap.gui.WorldMapContentPopulator;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class GuiWorkerGo extends GuiMapListScreen {

    private static final int ID_GO = FIRST_FREE_ID, ID_BACK = ID_GO + 1, ID_EQUIP = ID_GO + 2, ID_DUMP = ID_GO + 3,
            ID_STAY = ID_GO + 4, ID_RETURN = ID_GO + 5, ID_CANCEL = ID_GO + 6, ID_TICK = ID_GO + 7;
    private static final int TICK_H = 16;
    private static final int[] TICK_KINDS = {EquipLoadout.MELEE, EquipLoadout.BOW, EquipLoadout.SHIELD, EquipLoadout.ARMOR};
    private static final String[] TICK_KEYS = {"melee", "bow", "shield", "armour"};

    private GuiButton backButton, goButton, getEquipButton, dumpButton;
    private GuiButton equipStayButton, equipReturnButton, equipCancelButton;
    private final GuiTravellersButton[] tickButtons = new GuiTravellersButton[TICK_KINDS.length];

    // True while the Stay/Return dialog belongs to Dump Items rather than Get Equipment.
    private boolean dumpMission = false;
    private boolean showEquipDialog = false;
    private EquipLoadout ticks = EquipLoadout.DEFAULT;
    // The gear ticks only belong to Get Equipment at a Barrack or a village, not to Dump Items or a Stable.
    private boolean ticksShown = false;

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private boolean leaderMode = false;
    @Nullable private UUID leaderUUID = null;
    @Nullable private BlockPos leaderPos = null;
    @Nullable private GuiScreen returnTo = null;
    private int repEntityId = -1;

    // The places shown on the map, nearest first.
    private final List<MsgWorkerGoList.Entry> rows = new ArrayList<>();
    private int selectedIndex = -1;
    private BlockPos selectedMapPos = null;

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

    private List<MsgWorkerGoList.Entry> rows() {
        return rows;
    }

    @Override protected String title() { return I18n.format("travellers.worker.go.title"); }
    @Override protected String mapKey() { return "travellers_worker_map"; }
    @Override protected int buttonRows() { return 2; }
    @Override protected int goListWorkerId() { return repEntityId; }
    @Override protected boolean sideLocked() { return showEquipDialog; }
    @Override protected int rowCount() { return rows().size(); }
    @Override protected boolean isRowSelected(int idx) { return idx == selectedIndex; }

    @Override
    protected int ownerColor() {
        return worker != null ? worker.getOwnerMarkColor() : ClientOwnerVisualsCache.getColor();
    }

    /** Where the map opens and where Center goes back to. */
    @Override
    protected BlockPos home() {
        return leaderMode && leaderPos != null ? leaderPos
                : (worker != null ? worker.getPosition() : player.getPosition());
    }

    @Override
    protected void addControls() {
        MapListLayout l = layout;
        int bh = MapListLayout.BTN_H;
        this.getEquipButton = addButton(new GuiTravellersButton(ID_EQUIP, l.sideButtonX(0, 2), l.sideRowY(0), l.sideButtonW(2), bh,
                I18n.format("travellers.worker.barracks.get_equip", "Get Equipment")));
        this.dumpButton = addButton(new GuiTravellersButton(ID_DUMP, l.sideButtonX(1, 2), l.sideRowY(0), l.sideButtonW(2), bh,
                I18n.format("travellers.worker.barracks.dump", "Dump Items")));
        this.backButton = addButton(new GuiTravellersButton(ID_BACK, l.sideButtonX(0, 2), l.sideRowY(1), l.sideButtonW(2), bh,
                I18n.format("travellers.gui.back", "Back")));
        this.goButton = addButton(new GuiTravellersButton(ID_GO, l.sideButtonX(1, 2), l.sideRowY(1), l.sideButtonW(2), bh,
                I18n.format("travellers.gui.go", "Go")));
        getEquipButton.enabled = dumpButton.enabled = goButton.enabled = false;

        // Inline equip dialog buttons (over the list), hidden until opened; placed by layoutEquipDialog.
        int dw = l.listW() - 8;
        this.equipStayButton = addButton(new GuiTravellersButton(ID_STAY, l.listX() + 4, l.listY(), dw, bh,
                I18n.format("travellers.worker.barracks.equip_stay", "Equip and Stay")));
        this.equipReturnButton = addButton(new GuiTravellersButton(ID_RETURN, l.listX() + 4, l.listY(), dw, bh,
                I18n.format("travellers.worker.barracks.equip_return", "Equip and Return")));
        this.equipCancelButton = addButton(new GuiTravellersButton(ID_CANCEL, l.listX() + 4, l.listY(), dw, bh,
                I18n.format("travellers.gui.cancel", "Cancel")));
        ticks = worker != null && worker.getEquipRole() != null ? worker.getEquipRole() : EquipLoadout.DEFAULT;
        for (int i = 0; i < TICK_KINDS.length; i++) {
            tickButtons[i] = addButton(new GuiTravellersButton(ID_TICK + i, l.listX() + 4, l.listY(), (dw - MapListLayout.BTN_GAP) / 2, TICK_H,
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

        rows().clear();
        selectedIndex = -1;
        selectedMapPos = null;
    }

    @Override
    protected void onPlacesReceived() {
        rows.clear();
        for (MsgWorkerGoList.Entry e : map.data.places) {
            if (WorldMapContentPopulator.isShown(e)) rows.add(e);
        }
        rows.sort(Comparator.comparingInt(r -> r.distance));
        selectedIndex = -1;
        goButton.enabled = false;
        if (getEquipButton != null) getEquipButton.enabled = false;
        list.resetScroll();
        refreshSelection();
    }

    private void refreshSelection() {
        map.setDestination(selectedMapPos);
        map.setSelected(selectedIndex >= 0 && selectedIndex < rows().size() ? rows().get(selectedIndex).pos : null);
    }

    @Override
    protected void drawRow(int idx, int x, int top, int rowW, boolean hovered, int mouseX, int mouseY) {
        MsgWorkerGoList.Entry r = rows().get(idx);
        String prefix = r.kind == MsgWorkerGoList.Kind.VILLAGE ? "[V] "
                : r.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE ? "[CV] "
                : r.kind == MsgWorkerGoList.Kind.STABLE ? "[S] "
                : r.kind == MsgWorkerGoList.Kind.LONE_BUILDING ? "[L] "
                : "";
        String label = TravellersGuiTheme.shortenMiddle(fontRenderer, prefix + r.label,
                " (" + r.distance + "m)", rowW - 6);
        drawRowLabel(label, x, top, rowW, hovered);
    }

    @Override
    protected void drawSideOverlay(int mouseX, int mouseY) {
        if (!showEquipDialog) return;
        TravellersGuiTheme.drawPanel(layout.listBoxX, layout.listBoxY, layout.listBoxW, layout.listBoxH);
        TravellersGuiTheme.drawTitle(fontRenderer,
                I18n.format("travellers.worker.barracks.equip_title", "Choose mission:"),
                layout.listX() + 4, layout.listY() + 4);
    }

    @Override
    protected void onRowClicked(int idx, int mouseX, int mouseY) {
        selectedIndex = idx;
        selectedMapPos = null;
        map.controller.setCenter(rows().get(idx).pos);
        goButton.enabled = true;
        updateGetEquipEnabled();
        refreshSelection();
    }

    @Override
    protected void onMapClicked(BlockPos pos) {
        selectedMapPos = pos;
        selectedIndex = -1;
        goButton.enabled = true;
        updateGetEquipEnabled();
        refreshSelection();
    }

    @Override
    protected boolean onAction(GuiButton button) throws IOException {
        if (button == backButton) {
            goBack();
        } else if (button == getEquipButton || button == dumpButton) {
            dumpMission = button == dumpButton;
            updateGetEquipEnabled();
            showEquipDialog = true;
            applyEquipDialogVisibility();
        } else if (button == equipCancelButton) {
            showEquipDialog = false;
            applyEquipDialogVisibility();
        } else if (button == equipStayButton || button == equipReturnButton) {
            sendGetEquipment(button == equipStayButton);
            showEquipDialog = false;
            applyEquipDialogVisibility();
            if (leaderMode && returnTo != null) mc.displayGuiScreen(returnTo);
            else if (!leaderMode && worker != null) openHub();
        } else if (button == goButton) {
            go();
        } else {
            for (int i = 0; i < TICK_KINDS.length; i++) {
                if (button == tickButtons[i]) {
                    ticks = ticks.toggle(TICK_KINDS[i]);
                    refreshTicks();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private void goBack() {
        if (leaderMode && returnTo != null) mc.displayGuiScreen(returnTo);
        else openHub();
    }

    private void openHub() {
        TravellersNetwork.CHANNEL.sendToServer(new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
    }

    private void go() {
        if (leaderMode && leaderUUID != null) {
            BlockPos dest = selectedMapPos;
            if (dest == null && selectedIndex >= 0 && selectedIndex < rows().size()) dest = rows().get(selectedIndex).pos;
            if (dest == null) return;
            TravellersNetwork.CHANNEL.sendToServer(new MsgLeaderGoTo(leaderUUID, dest));
        } else if (selectedMapPos != null) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerGoToPos(worker.getEntityId(), selectedMapPos));
        } else if (selectedIndex >= 0) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerGoTo(worker.getEntityId(), rows.get(selectedIndex).pos));
        } else {
            return;
        }
        mc.player.closeScreen();
    }

    private void refreshTicks() {
        for (int i = 0; i < TICK_KINDS.length; i++) tickButtons[i].toggled(ticks.has(TICK_KINDS[i]));
    }

    /** Gear ticks in two columns on top when shown, then Stay / Return / Cancel. */
    private void layoutEquipDialog() {
        int gap = MapListLayout.BTN_GAP;
        int y = layout.listY() + 18;
        for (int i = 0; i < TICK_KINDS.length; i++) {
            GuiTravellersButton b = tickButtons[i];
            b.x = i % 2 == 0 ? layout.listX() + 4 : layout.listX() + layout.listW() - 4 - b.width;
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
        map.setNavVisible(!d);
    }

    /** Get Equipment is allowed for a controlled village, a lone barrack, or a stable row. */
    private void updateGetEquipEnabled() {
        boolean ok = selectedMapPos == null && selectedIndex >= 0 && selectedIndex < rows().size()
                && (rows().get(selectedIndex).kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE
                    || rows().get(selectedIndex).kind == MsgWorkerGoList.Kind.HOME
                    || rows().get(selectedIndex).kind == MsgWorkerGoList.Kind.STABLE);
        if (getEquipButton != null) getEquipButton.enabled = ok;
        boolean stable = ok && rows().get(selectedIndex).kind == MsgWorkerGoList.Kind.STABLE;
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
        if (selectedIndex < 0 || selectedIndex >= rows().size()) return;
        MsgWorkerGoList.Entry r = rows().get(selectedIndex);
        EquipTargetKind kind = r.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE ? EquipTargetKind.CONTROLLED_VILLAGE
                : r.kind == MsgWorkerGoList.Kind.STABLE ? EquipTargetKind.STABLE
                : EquipTargetKind.LONE_BARRACK;
        if (dumpMission && kind != EquipTargetKind.STABLE) kind = kind.toDump();
        UUID targetUUID = leaderMode ? leaderUUID : (worker != null ? worker.getUniqueID() : null);
        if (targetUUID == null || r.pos == null) return;
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerGetEquipment(targetUUID, stay, kind, r.pos, ticks));
    }
}
