package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.follow.net.MsgRequestFormationHub;
import com.jubitus.travellers.gui.ButtonTooltipHelper;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.notify.JournalEntry;
import com.jubitus.travellers.notify.net.MsgRequestJournalHistory;
import com.jubitus.travellers.unit.equipment.net.MsgEquipDestsResponse;
import com.jubitus.travellers.unit.equipment.net.MsgRequestEquipDests;
import com.jubitus.travellers.worker.gui.GuiWorkerBehavior;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import com.jubitus.travellers.worldmap.gui.GuiWorldMapPanel;
import com.jubitus.travellers.worldmap.gui.WorkerMapScreenSupport;
import com.jubitus.travellers.worldmap.gui.WorldMapContentPopulator;
import com.jubitus.travellers.worldmap.gui.WorldMapController;
import com.jubitus.travellers.worldmap.gui.WorldMapMarkerFactory;
import com.jubitus.travellers.worldmap.gui.receiver.IBattleMapReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IForeignUnitMapReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.zone.gui.GuiWorkerBarracks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;

/**
 * Groups screen: leader list (left), worldmap (centre), selected-group card (right), journal (bottom).
 * Left click selects on the map or the list, right click on the map opens the orders menu for the
 * current selection and whatever was clicked, right drag pans the map.
 */
@SideOnly(Side.CLIENT)
public class GuiWorkerFormationHub extends GuiScreen
        implements IWorkerGoListReceiver, IWorkerMapReceiver, IBattleMapReceiver, IForeignUnitMapReceiver, ITravellersGui,
        FormationOrdersMenu.Host, com.jubitus.travellers.worldmap.gui.receiver.IStrikeMapReceiver {

    // ── Layout ────────────────────────────────────────────────────────────────
    // Three boxes (leaders | map | card) share one top and one bottom edge; inside each box the
    // header line, the separator under it and the content padding are the same.
    private static final int FRAME_W = 560;
    private static final int BOX_H = 230;
    private static final int MIN_BOX_H = 176;
    private static final int MIN_HISTORY_H = 36;
    private static final int PAD = 4;          // frame edge to boxes, and between boxes
    private static final int INNER = 4;        // box edge to its text
    private static final int TITLE_H = 20;     // frame top to box top
    private static final int HEADER_H = 14;    // box header line, separator included
    private static final int HINT_LINE_H = 8;  // one line of the small hint text
    private static final float HINT_SCALE = 0.75f;
    private static final int LIST_W = 112;
    private static final int CARD_W = 124;

    private static final int COL_TEXT     = TravellersGuiTheme.COL_TEXT;
    private static final int COL_TEXT_DIM = TravellersGuiTheme.COL_TEXT_DIM;

    // ── Data ──────────────────────────────────────────────────────────────────
    private final EntityPlayer player;
    private final int representativeEntityId;
    private final boolean standalone;
    private final List<FormationLeaderInfo> leaders;
    private final Map<String, WorkerFormationSettings> settingsByScope;

    private final FormationSelection selection;
    private final WorkerMapScreenSupport mapData = new WorkerMapScreenSupport();

    // ── Parts ─────────────────────────────────────────────────────────────────
    private final FormationLeaderList leaderList;
    private final FormationSettingsCard card;
    private final FormationOrdersMenu orders;
    private final FormationActionBar actionBar;
    private final FormationHubJournal journal = new FormationHubJournal();
    private WorldMapController mapController;
    private final ButtonTooltipHelper tooltips = new ButtonTooltipHelper();

    // ── Geometry ──────────────────────────────────────────────────────────────
    private int frameX, frameY, frameW, frameH;
    private int boxTop, boxH;
    private int listX;
    private int mapBoxX, mapBoxW;
    private int mapX, mapY, mapW, mapH;  // the map widget itself, 1px inside its box border
    private int cardX;
    private int hintY, hintLines;

    // ── Widgets / state ───────────────────────────────────────────────────────
    private GuiButton barracksButton, behaviorButton, closeButton;

    private boolean mapDragSelecting = false;
    private int mapDragStartX, mapDragStartY, mapDragCurrentX, mapDragCurrentY;

    private int leaderListRefreshTicks = 0;
    private static final int LEADER_LIST_REFRESH_INTERVAL_TICKS = 20;

    public GuiWorkerFormationHub(EntityPlayer player, int representativeEntityId, boolean standalone,
                                 List<FormationLeaderInfo> leaders, Map<String, WorkerFormationSettings> settingsByScope,
                                 WorkerFormationScopeKey initialScope) {
        this.player = player;
        this.representativeEntityId = representativeEntityId;
        this.standalone = standalone;
        this.leaders = leaders;
        this.settingsByScope = settingsByScope;
        this.selection = new FormationSelection(leaders);
        this.leaderList = new FormationLeaderList(leaders);
        this.card = new FormationSettingsCard(settingsByScope);
        this.orders = new FormationOrdersMenu(player, selection, mapData, this);
        this.actionBar = new FormationActionBar(selection, orders, this::centerOnSelectedGroup);
        for (int i = 0; i < leaders.size(); i++) {
            if (scopeOf(leaders.get(i)).equals(initialScope)) {
                selection.index = i;
                break;
            }
        }
        applySelection(false);
        if (!leaders.isEmpty()) selection.mode = FormationSelection.Mode.LEADER;
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        orders.close();
        layout();

        // Title bar: general screens and close, right-aligned on the card's right edge
        int buttonY = frameY + (TITLE_H - 14) / 2;
        int bx = frameX + frameW - PAD;
        closeButton = addButton(new GuiTravellersButton(99, bx - 14, buttonY, 14, 14, "×"));
        bx -= 14 + PAD;
        String behavior = I18n.format("travellers.worker.hub.behavior");
        int behaviorW = fontRenderer.getStringWidth(behavior) + 10;
        behaviorButton = addButton(new GuiTravellersButton(37, bx - behaviorW, buttonY, behaviorW, 14, behavior));
        bx -= behaviorW + PAD;
        String barracks = I18n.format("travellers.worker.hub.barracks");
        int barracksW = fontRenderer.getStringWidth(barracks) + 10;
        barracksButton = addButton(new GuiTravellersButton(36, bx - barracksW, buttonY, barracksW, 14, barracks));

        // Card content, under its header: state line, style + attitude, then the four sliders
        tooltips.clear();
        card.init(buttonList, tooltips, cardX + INNER, boxTop + HEADER_H + 14, CARD_W - INNER * 2,
                boxTop + boxH - INNER);

        // A resize keeps the view; a fresh open starts on the selected leader.
        BlockPos center = mapController != null ? mapController.panel.getCenter() : getSelectedLeaderPos();
        float bpp = mapController != null ? mapController.panel.getZoomBlocksPerPixel()
                : (float) TravellersConfig.WORLDMAP.formationHubMapInitialBpp;
        mapController = new WorldMapController(Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_formation_hub_map", center);
        mapController.panel.setZoomBlocksPerPixel(bpp);
        orders.setMap(mapController);

        mapData.subscribe();
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(
                representativeEntityId >= 0 ? representativeEntityId : -1));
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestEquipDests());

        journal.reset();
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestJournalHistory());

        refreshButtons();
    }

    /** Title bar, three boxes, the hint band under the map, then the journal; the journal shrinks first when space is short. */
    private void layout() {
        frameW = Math.min(FRAME_W, width - 8);
        listX = frameX(frameW) + PAD;
        cardX = frameX(frameW) + frameW - PAD - CARD_W;
        mapBoxX = listX + LIST_W + PAD;
        mapBoxW = cardX - PAD - mapBoxX;
        hintLines = Math.max(1, Math.min(2, hintText().size()));
        int hintH = hintLines * HINT_LINE_H + 4;

        boolean history = TravellersConfig.NOTIFICATIONS.historyPanelEnabled;
        int availH = height - 8;
        int fixed = TITLE_H + hintH + PAD;
        boxH = BOX_H;
        int histH = history ? TravellersConfig.NOTIFICATIONS.historyPanelHeight : 0;
        if (fixed + boxH + histH > availH) histH = Math.max(history ? MIN_HISTORY_H : 0, availH - fixed - boxH);
        if (fixed + boxH + histH > availH) boxH = Math.max(MIN_BOX_H, availH - fixed - histH);
        frameH = fixed + boxH + histH;

        frameX = frameX(frameW);
        frameY = Math.max(4, (height - frameH) / 2);
        boxTop = frameY + TITLE_H;
        mapX = mapBoxX + 1;
        mapY = boxTop + 1;
        mapW = mapBoxW - 2;
        mapH = boxH - 2 - FormationActionBar.HEIGHT - 1;
        actionBar.setBounds(mapX, mapY + mapH + 1, mapW);
        hintY = boxTop + boxH + 3;

        leaderList.setBounds(listX, boxTop + HEADER_H + 2, LIST_W, boxTop + boxH - 2);
        journal.setBounds(frameX + PAD, boxTop + boxH + hintH, frameW - PAD * 2, histH);
    }

    private int frameX(int w) {
        return (width - w) / 2;
    }

    private List<String> hintText() {
        return fontRenderer.listFormattedStringToWidth(I18n.format("travellers.worker.formation.hint"),
                (int) ((cardX - PAD - mapBoxX) / HINT_SCALE));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        card.flush(true);
        mapData.unsubscribe();
    }

    // ── Live refresh (MsgOpenFormationHubGui while open) ─────────────────────

    public void updateLeaders(List<FormationLeaderInfo> newLeaders, Map<String, WorkerFormationSettings> newSettings) {
        // Keep the selection by identity: the list is re-sorted by distance on every rebuild.
        UUID prevUuid = null;
        boolean prevPlayer = false;
        if (selection.index >= 0 && selection.index < leaders.size()) {
            prevPlayer = leaders.get(selection.index).isPlayer;
            prevUuid = leaders.get(selection.index).uuid;
        }

        leaders.clear();
        leaders.addAll(newLeaders);
        settingsByScope.clear();
        settingsByScope.putAll(newSettings);

        if (!selection.pendingFromFollowers.isEmpty()) {
            for (int i = 0; i < leaders.size(); i++) {
                FormationLeaderInfo info = leaders.get(i);
                if (!info.isPlayer && info.uuid != null && selection.pendingFromFollowers.contains(info.uuid)) {
                    selection.pendingFromFollowers = Collections.emptySet();
                    selectLeaderByIndex(i, false);
                    return;
                }
            }
            selection.pendingFromFollowers = Collections.emptySet();
        }

        int relocated = -1;
        for (int i = 0; i < leaders.size(); i++) {
            FormationLeaderInfo info = leaders.get(i);
            if ((prevPlayer && info.isPlayer) || (prevUuid != null && prevUuid.equals(info.uuid))) {
                relocated = i;
                break;
            }
        }
        boolean selectionChanged = relocated < 0;
        if (relocated >= 0) selection.index = relocated;
        else if (selection.index >= leaders.size()) selection.index = Math.max(0, leaders.size() - 1);

        if (leaders.isEmpty()) selection.mode = FormationSelection.Mode.NONE;
        else if (selection.mode == FormationSelection.Mode.NONE) selection.mode = FormationSelection.Mode.LEADER;
        // A periodic refresh must not yank the map or reset sliders being dragged.
        if (selectionChanged || !card.hasSettings()) applySelection(false);
        refreshButtons();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        card.flush(false);
        journal.tick();

        if (++leaderListRefreshTicks >= LEADER_LIST_REFRESH_INTERVAL_TICKS) {
            leaderListRefreshTicks = 0;
            if (!orders.isOpen()) TravellersNetwork.CHANNEL.sendToServer(new MsgRequestFormationHub());
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        TravellersGuiTheme.drawPanel(frameX, frameY, frameW, frameH);
        fontRenderer.drawString(TextFormatting.BOLD + I18n.format("travellers.worker.formation.hub_title"),
                listX + INNER, frameY + (TITLE_H - fontRenderer.FONT_HEIGHT) / 2 + 1, COL_TEXT);

        TravellersGuiTheme.drawInnerPanel(listX, boxTop, LIST_W, boxH);
        drawBoxHeader(listX, LIST_W, TextFormatting.BOLD + I18n.format("travellers.worker.formation.leaders"));
        leaderList.draw(fontRenderer, player, mouseX, mouseY, !orders.isOpen(),
                selection.mode == FormationSelection.Mode.LEADER ? selection.index : -1,
                boxTop + 3, boxTop + boxH - 10);
        drawCard();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        TravellersGuiTheme.drawInnerPanel(mapBoxX, boxTop, mapBoxW, boxH);
        rebuildMapMarkers();
        mapController.panel.setHighlighted(orders.highlightedTargets(mouseX, mouseY, mapDragSelecting));
        mapController.draw();
        orders.drawDestinationCross();
        drawSelectionRectangle();
        actionBar.draw(fontRenderer, mouseX, mouseY, !orders.isOpen());
        drawHint();
        journal.draw(mouseX, mouseY, !orders.isOpen());

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (!selection.isFollowers()) card.drawSliders(mc, mouseX, mouseY);

        if (orders.isOpen()) {
            orders.draw(mouseX, mouseY);
            return;
        }
        List<String> barTip = actionBar.tooltipAt(mouseX, mouseY);
        if (barTip != null) drawHoveringText(barTip, mouseX, mouseY);
        else if (!mapDragSelecting) mapController.drawTooltip(this, mouseX, mouseY);
        if (TravellersConfig.NOTIFICATIONS.historyPanelBattleTooltipEnabled) {
            JournalEntry hovered = journal.entryAt(mouseX, mouseY);
            if (hovered != null && hovered.battleSummary != null) {
                com.jubitus.travellers.battle.client.BattleTooltip.forJournal(hovered.battleSummary)
                        .draw(fontRenderer, mouseX, mouseY, width, height);
            }
        }
        tooltips.render(mouseX, mouseY, width, height);
        FormationLeaderInfo hoveredLeader = leaderList.infoAt(mouseX, mouseY);
        if (hoveredLeader != null) GroupSummaryTooltip.draw(fontRenderer, hoveredLeader, mouseX, mouseY, width, height);
    }

    private void drawBoxHeader(int x, int w, String text) {
        fontRenderer.drawString(fontRenderer.trimStringToWidth(text, w - INNER * 2), x + INNER, boxTop + 4, COL_TEXT);
        TravellersGuiTheme.drawSeparator(x + 1, boxTop + HEADER_H - 1, w - 2);
    }

    /** Right box: what is selected (header), its state, and for a group or your escort its formation settings. */
    private void drawCard() {
        TravellersGuiTheme.drawInnerPanel(cardX, boxTop, CARD_W, boxH);
        int textX = cardX + INNER;
        int textW = CARD_W - INNER * 2;
        int stateY = boxTop + HEADER_H + 3;
        if (selection.isFollowers()) {
            drawBoxHeader(cardX, CARD_W, TextFormatting.BOLD + I18n.format(
                    "travellers.worker.formation.card.followers_selected", selection.followers.size()));
            List<String> lines = fontRenderer.listFormattedStringToWidth(
                    I18n.format("travellers.worker.formation.card.followers_hint"), textW);
            for (int i = 0; i < lines.size(); i++) fontRenderer.drawString(lines.get(i), textX, stateY + i * 10, COL_TEXT_DIM);
            return;
        }
        FormationLeaderInfo sel = selection.leader();
        if (sel == null) return;
        drawBoxHeader(cardX, CARD_W, TextFormatting.BOLD + (sel.isPlayer
                ? I18n.format("travellers.worker.formation.card.you") : sel.name));
        String state;
        if (sel.isPlayer) {
            state = I18n.format("travellers.worker.formation.card.workers", sel.followerCount);
        } else {
            String key = sel.busy ? "busy" : sel.goActive ? "en_route" : sel.followActive ? "following" : "halted";
            state = I18n.format("travellers.worker.formation.card.workers", sel.followerCount + 1)
                    + " · " + I18n.format("travellers.worker.formation.card." + key);
        }
        fontRenderer.drawString(fontRenderer.trimStringToWidth(state, textW), textX, stateY, COL_TEXT_DIM);
    }

    private void drawHint() {
        List<String> lines = hintText();
        GlStateManager.pushMatrix();
        GlStateManager.translate(mapBoxX + 1, hintY, 0f);
        GlStateManager.scale(HINT_SCALE, HINT_SCALE, 1f);
        for (int i = 0; i < Math.min(hintLines, lines.size()); i++) {
            fontRenderer.drawString(lines.get(i), 0, Math.round(i * HINT_LINE_H / HINT_SCALE), COL_TEXT_DIM);
        }
        GlStateManager.popMatrix();
    }

    private void drawSelectionRectangle() {
        if (!mapDragSelecting) return;
        int x1 = Math.max(mapX, Math.min(mapDragStartX, mapDragCurrentX));
        int y1 = Math.max(mapY, Math.min(mapDragStartY, mapDragCurrentY));
        int x2 = Math.min(mapX + mapW, Math.max(mapDragStartX, mapDragCurrentX));
        int y2 = Math.min(mapY + mapH, Math.max(mapDragStartY, mapDragCurrentY));
        if (x1 >= x2 || y1 >= y2) return;
        drawRect(x1, y1, x2, y2, 0x3300AAFF);
        TravellersGuiTheme.drawBorder(x1, y1, x2 - x1, y2 - y1, 0xFF00AAFF);
    }

    /** Centers the map on the unit an entry is about and selects it when it is on the map, else centers on where it happened. */
    private void handleJournalClick(int mouseX, int mouseY) {
        JournalEntry entry = journal.entryAt(mouseX, mouseY);
        if (entry == null || entry.place == null) return;
        BlockPos target = entry.place.pos;
        MsgMapWorkersUpdate.Entry live = mapData.workerByUuid(entry.place.subject);
        if (live != null) {
            target = live.pos;
            if (!WorldMapContentPopulator.isOnDuty(live)) selectWorker(live);
        }
        mapController.setCenter(target);
    }

    // ── Map markers ───────────────────────────────────────────────────────────

    private void rebuildMapMarkers() {
        mapData.populate(mapController, player, ClientOwnerVisualsCache.getColor(), null);

        // Selected markers again on top (same type = drawn later within the pass)
        FormationLeaderInfo group = selection.group();
        if (group != null) {
            MsgMapWorkersUpdate.Entry e = mapData.workerByUuid(group.uuid);
            if (e != null && e.isLeader) {
                mapController.panel.addMarker(WorldMapMarkerFactory.leaderHighlighted(e.pos, WorldMapContentPopulator.workerLabel(e))
                        .at(mapData.drawX(e), mapData.drawZ(e)));
            }
        } else if (selection.isFollowers()) {
            Set<UUID> selSet = new HashSet<>(selection.followers);
            for (MsgMapWorkersUpdate.Entry e : mapData.workers) {
                if (!e.isLeader && e.uuid != null && selSet.contains(e.uuid) && e.pos != null) {
                    mapController.panel.addMarker(WorldMapMarkerFactory.followerHighlighted(
                            e.pos, WorldMapContentPopulator.workerLabel(e), e.ownerColor)
                            .at(mapData.drawX(e), mapData.drawZ(e)));
                }
            }
        }
    }

    // ── Receivers ─────────────────────────────────────────────────────────────

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        mapData.setPlaces(entries);
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        mapData.setWorkers(list);
    }

    @Override
    public void receiveBattles(List<MsgMapBattleUpdate.Entry> entries) {
        mapData.setBattles(entries);
    }

    @Override
    public void receiveForeignUnits(List<MsgMapForeignUnitsUpdate.Entry> entries) {
        mapData.setForeignUnits(entries);
    }

    /** Called by MsgEquipDestsResponse: where a group can be sent for equipment. */
    public void onEquipDestsReceived(List<MsgEquipDestsResponse.EquipDestInfo> dests) {
        orders.setEquipDests(dests);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == closeButton) {
            card.flush(true);
            if (standalone) mc.displayGuiScreen(null);
            else TravellersNetwork.CHANNEL.sendToServer(new MsgOpenWorkerSubGui(representativeEntityId, MsgOpenWorkerSubGui.Type.HUB));
        } else if (button == barracksButton) {
            mc.displayGuiScreen(new GuiWorkerBarracks(player, this));
        } else if (button == behaviorButton) {
            mc.displayGuiScreen(new GuiWorkerBehavior(player, this));
        } else {
            card.actionPerformed(button);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1 && orders.isOpen()) {
            orders.close();
            return;
        }
        if (keyCode == FormationHubKeyHandler.KEY.getKeyCode()) {
            card.flush(true);
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (orders.isOpen()) {
            orders.mouseClicked(mouseX, mouseY);
            return;
        }

        if (mouseButton == 0 && journal.isOver(mouseX, mouseY)) {
            handleJournalClick(mouseX, mouseY);
            return;
        }

        if (mouseButton == 0 && actionBar.isOver(mouseX, mouseY)) {
            actionBar.mouseClicked(mouseX, mouseY);
            return;
        }

        if (mapController.isMouseOver(mouseX, mouseY)) {
            if (mouseButton == 1) {
                mapController.onMouseClick(mouseX, mouseY, mouseButton); // pans, or opens the menu on a still release
            } else if (mouseButton == 0) {
                mapDragSelecting = true;
                mapDragStartX = mapDragCurrentX = mouseX;
                mapDragStartY = mapDragCurrentY = mouseY;
            }
            return;
        }

        if (mouseButton == 0 || mouseButton == 1) {
            int idx = leaderList.rowAt(mouseX, mouseY);
            if (idx >= 0) {
                if (mouseButton == 0) selectLeaderByIndex(idx, true);
                else orders.openForLeaderRow(leaders.get(idx), mouseX, mouseY, width, height);
                return;
            }
        }

        if (!selection.isFollowers() && card.mousePressed(mouseX, mouseY, mouseButton)) return;

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (mapDragSelecting) {
            mapDragCurrentX = mouseX;
            mapDragCurrentY = mouseY;
            return;
        }
        mapController.onMouseDrag(mouseX, mouseY);
        card.mouseDragged(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);

        if (mapDragSelecting && state == 0) {
            mapDragSelecting = false;
            int dragDist = Math.abs(mapDragCurrentX - mapDragStartX) + Math.abs(mapDragCurrentY - mapDragStartY);
            if (dragDist < 5) handleMapLeftClick(mapDragCurrentX, mapDragCurrentY);
            else computeMapRectangleSelection();
            return;
        }

        mapController.onMouseRelease(state);
        if (state == 1 && mapController.endRightClick(mouseX, mouseY)) {
            orders.open(mouseX, mouseY, width, height);
            return;
        }

        card.mouseReleased(state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) return;
        if (orders.isOpen()) {
            orders.close();
            return;
        }
        int mouseX = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;

        if (mouseX >= listX && mouseX < listX + LIST_W && mouseY >= boxTop && mouseY < boxTop + boxH) {
            leaderList.scrollBy(wheel);
        } else if (mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (journal.isOver(mouseX, mouseY)) {
            journal.scrollBy(wheel);
        }
    }

    // ── Left-click selection ──────────────────────────────────────────────────

    private void handleMapLeftClick(int mouseX, int mouseY) {
        boolean shift = isShiftKeyDown();
        MsgMapWorkersUpdate.Entry closest = orders.selectableWorkerAt(mouseX, mouseY, shift);

        if (closest == null) {
            if (!shift) selectPlayerRow(); // empty map: back to the resting state, map left where it is
            return;
        }
        if (shift) {
            // Shift adds followers to the selection and ignores leaders
            if (!closest.isLeader) {
                Set<UUID> combined = new LinkedHashSet<>(selection.isFollowers()
                        ? selection.followers : Collections.emptyList());
                combined.add(closest.uuid);
                selectFollowers(new ArrayList<>(combined));
            }
        } else {
            selectWorker(closest);
        }
    }

    private void selectWorker(MsgMapWorkersUpdate.Entry worker) {
        if (worker.isLeader) {
            selectLeaderByUuid(worker.uuid);
        } else if (worker.followLeaderUuid != null && selection.indexOf(worker.followLeaderUuid) >= 0) {
            selectLeaderByUuid(worker.followLeaderUuid); // a member stands for its whole group
        } else {
            selectFollowers(Collections.singletonList(worker.uuid));
        }
    }

    private void computeMapRectangleSelection() {
        int x1 = Math.min(mapDragStartX, mapDragCurrentX), x2 = Math.max(mapDragStartX, mapDragCurrentX);
        int y1 = Math.min(mapDragStartY, mapDragCurrentY), y2 = Math.max(mapDragStartY, mapDragCurrentY);
        int pad = Math.round(mapController.hitRadiusPx(GuiWorldMapPanel.Marker.Size.WORKER) / 2f);
        List<UUID> leaderUuids = new ArrayList<>();
        List<UUID> followerUuids = new ArrayList<>();
        for (MsgMapWorkersUpdate.Entry e : mapData.workers) {
            if (e.pos == null || e.uuid == null || WorldMapContentPopulator.isOnDuty(e)) continue;
            float sx = mapController.worldToScreenX(mapData.drawX(e));
            float sy = mapController.worldToScreenY(mapData.drawZ(e));
            if (sx < x1 - pad || sx > x2 + pad || sy < y1 - pad || sy > y2 + pad) continue;
            (e.isLeader ? leaderUuids : followerUuids).add(e.uuid);
        }

        if (isShiftKeyDown()) {
            if (followerUuids.isEmpty()) return;
            Set<UUID> combined = new LinkedHashSet<>(selection.isFollowers()
                    ? selection.followers : Collections.emptyList());
            combined.addAll(followerUuids);
            selectFollowers(new ArrayList<>(combined));
        } else if (!leaderUuids.isEmpty()) {
            selectLeaderByUuid(leaderUuids.get(0));
        } else if (!followerUuids.isEmpty()) {
            selectFollowers(followerUuids);
        } else {
            selectPlayerRow();
        }
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private void selectLeaderByIndex(int idx, boolean recenter) {
        if (idx < 0 || idx >= leaders.size()) return;
        selection.index = idx;
        selection.mode = FormationSelection.Mode.LEADER;
        selection.followers = Collections.emptyList();
        leaderList.ensureVisible(idx);
        applySelection(recenter);
        refreshButtons();
    }

    @Override
    public void selectLeaderByUuid(@Nullable UUID uuid) {
        int idx = selection.indexOf(uuid);
        if (idx >= 0) selectLeaderByIndex(idx, false);
    }

    @Override
    public void selectFollowers(List<UUID> uuids) {
        selection.followers = uuids;
        selection.mode = FormationSelection.Mode.FOLLOWERS;
        refreshButtons();
    }

    @Override
    public void selectPlayerRow() {
        int idx = selection.indexOfPlayer();
        if (idx >= 0) {
            selectLeaderByIndex(idx, false);
            return;
        }
        selection.mode = FormationSelection.Mode.NONE;
        selection.followers = Collections.emptyList();
        refreshButtons();
    }

    private void applySelection(boolean recenter) {
        FormationLeaderInfo info = selection.currentRow();
        if (info == null) {
            card.select(null);
            return;
        }
        card.select(scopeOf(info));
        if (recenter && mapController != null) mapController.setCenter(findCurrentLeaderPos(info));
    }

    private BlockPos findCurrentLeaderPos(FormationLeaderInfo info) {
        if (info.isPlayer) return player.getPosition();
        MsgMapWorkersUpdate.Entry e = mapData.workerByUuid(info.uuid);
        return e != null ? e.pos : new BlockPos((int) info.posX, 64, (int) info.posZ);
    }

    private BlockPos getSelectedLeaderPos() {
        FormationLeaderInfo info = selection.currentRow();
        if (info == null) return player.getPosition();
        return info.isPlayer ? player.getPosition() : new BlockPos((int) info.posX, 64, (int) info.posZ);
    }

    private void centerOnSelectedGroup() {
        FormationLeaderInfo group = selection.group();
        if (group != null && mapController != null) mapController.setCenter(findCurrentLeaderPos(group));
    }

    private void refreshButtons() {
        card.refreshButtons(!selection.isFollowers() && card.hasSettings());
    }

    private WorkerFormationScopeKey scopeOf(FormationLeaderInfo info) {
        return FormationSettingsCard.scopeOf(info, player.getUniqueID());
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void receiveStrikes(int dim, java.util.List<com.jubitus.travellers.worldmap.net.MsgMapBattleStrikes.Entry> strikes) {
        if (mapController != null) mapController.panel.receiveStrikes(dim, strikes);
    }
}
