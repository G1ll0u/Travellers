package com.jubitus.travellers.client.gui.formation;

import com.jubitus.travellers.client.gui.ButtonTooltipHelper;
import com.jubitus.travellers.client.gui.GuiContextMenu;
import com.jubitus.travellers.client.gui.GuiTravellersButton;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.behavior.GuiWorkerBehavior;
import com.jubitus.travellers.client.gui.home.GuiWorkerBarracks;
import com.jubitus.travellers.client.gui.home.WorkerHeadRenderer;
import com.jubitus.travellers.client.gui.worldmap.WorldMapContentPopulator;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.client.gui.worldmap.receiver.IBattleMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.receiver.IForeignUnitMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.client.input.FormationHubKeyHandler;
import com.jubitus.travellers.client.marker.ClientOwnerVisualsCache;
import com.jubitus.travellers.client.notify.ClientJournalCache;
import com.jubitus.travellers.client.notify.LocationFormat;
import com.jubitus.travellers.client.notify.NoticeText;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.notify.BattleSummary;
import com.jubitus.travellers.notify.JournalEntry;
import com.jubitus.travellers.server.map.stream.MsgMapBattleUpdate;
import com.jubitus.travellers.server.map.stream.MsgMapForeignUnitsUpdate;
import com.jubitus.travellers.server.map.stream.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.stream.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.formations.FormationLeaderInfo;
import com.jubitus.travellers.server.net.message.formations.MsgRequestFormationHub;
import com.jubitus.travellers.server.net.message.formations.MsgUpdateFormationByScope;
import com.jubitus.travellers.server.net.message.group.MsgGroupCreate;
import com.jubitus.travellers.server.net.message.group.MsgGroupFollow;
import com.jubitus.travellers.server.net.message.group.MsgGroupMerge;
import com.jubitus.travellers.server.net.message.group.MsgLeaderGoTo;
import com.jubitus.travellers.server.net.message.group.MsgSetLeaderFollow;
import com.jubitus.travellers.server.net.message.home.MsgEquipDestsResponse;
import com.jubitus.travellers.server.net.message.home.MsgLeaderGetEquip;
import com.jubitus.travellers.server.net.message.home.MsgRequestEquipDests;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.server.net.message.notify.MsgRequestJournalHistory;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerRequestGoList;
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
        implements IWorkerGoListReceiver, IWorkerMapReceiver, IBattleMapReceiver, IForeignUnitMapReceiver, ITravellersGui {

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
    private static final int LIST_W = 112;
    private static final int CARD_W = 124;
    private static final int ROW_H = 20;
    private static final int HISTORY_GUTTER_W = 6;
    private static final int TARGET_RADIUS_PX = 7;

    private static final int COL_TEXT     = TravellersGuiTheme.COL_TEXT;
    private static final int COL_TEXT_DIM = TravellersGuiTheme.COL_TEXT_DIM;

    private enum SelectionMode { NONE, LEADER, FOLLOWERS }

    // ── Data ──────────────────────────────────────────────────────────────────
    private final EntityPlayer player;
    private final int representativeEntityId;
    private final boolean standalone;
    private final List<FormationLeaderInfo> leaders;
    private final Map<String, WorkerFormationSettings> settingsByScope;

    private WorkerFormationScopeKey selectedScope;
    private WorkerFormationSettings currentSettings;
    private int selectedIndex = 0;
    private SelectionMode selectionMode = SelectionMode.NONE;
    private List<UUID> mapSelectedFollowers = Collections.emptyList();
    // After a group is formed, the new leader is selected when the refreshed list arrives
    private Set<UUID> pendingSelectFromFollowers = Collections.emptySet();

    private final List<MsgWorkerGoList.Entry> poiEntries = new ArrayList<>();
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private final List<MsgMapBattleUpdate.Entry> battleEntries = new ArrayList<>();
    private final List<MsgMapForeignUnitsUpdate.Entry> foreignEntries = new ArrayList<>();
    private List<MsgEquipDestsResponse.EquipDestInfo> equipDests = Collections.emptyList();

    // ── Geometry ──────────────────────────────────────────────────────────────
    private int frameX, frameY, frameW, frameH;
    private int boxTop, boxH;
    private int listX;
    private int mapBoxX, mapBoxW;
    private int mapX, mapY, mapW, mapH;  // the map widget itself, 1px inside its box border
    private int cardX;
    private int hintY, hintLines;
    private int histX, histY, histW, histH;

    // ── Widgets / state ───────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final GuiContextMenu menu = new GuiContextMenu();
    private final ButtonTooltipHelper tooltips = new ButtonTooltipHelper();
    private final List<BlockPos> menuTargets = new ArrayList<>();

    private GuiWorkerFormationSlider escortsSlider, followDistSlider, spacingSlider, widthSlider;
    private GuiButton styleButton, attitudeButton, barracksButton, behaviorButton, closeButton;

    private int listScroll = 0;
    private boolean mapDragSelecting = false;
    private int mapDragStartX, mapDragStartY, mapDragCurrentX, mapDragCurrentY;

    private BlockPos destinationCrossPos = null;
    private long destinationCrossExpireMs = 0L;
    private static final long DESTINATION_CROSS_DURATION_MS = 1500L;
    private static final net.minecraft.util.ResourceLocation DESTINATION_CROSS_TEX =
            new net.minecraft.util.ResourceLocation("travellers", "textures/map/destination_cross.png");

    private int historyScroll = 0;
    private boolean historyPinnedToBottom = true;
    private int lastHistoryCount = -1;

    private int leaderListRefreshTicks = 0;
    private static final int LEADER_LIST_REFRESH_INTERVAL_TICKS = 20;

    private long lastSendMs = 0L;
    private boolean pendingSync = false;
    private static final long SYNC_THROTTLE_MS = 120L;

    public GuiWorkerFormationHub(EntityPlayer player, int representativeEntityId, boolean standalone,
                                 List<FormationLeaderInfo> leaders, Map<String, WorkerFormationSettings> settingsByScope,
                                 WorkerFormationScopeKey initialScope) {
        this.player = player;
        this.representativeEntityId = representativeEntityId;
        this.standalone = standalone;
        this.leaders = leaders;
        this.settingsByScope = settingsByScope;
        for (int i = 0; i < leaders.size(); i++) {
            if (scopeOf(leaders.get(i)).equals(initialScope)) {
                selectedIndex = i;
                break;
            }
        }
        applySelection(false);
        if (!leaders.isEmpty()) selectionMode = SelectionMode.LEADER;
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        menu.close();
        menuTargets.clear();
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
        int cx = cardX + INNER;
        int cw = CARD_W - INNER * 2;
        int y = boxTop + HEADER_H + 14;
        styleButton = addButton(new GuiTravellersButton(1, cx, y, cw, 16, ""));
        attitudeButton = addButton(new GuiTravellersButton(2, cx, y + 20, cw, 16, ""));
        tooltips.clear();
        tooltips.set(attitudeButton, "travellers.worker.formation.attitude.tooltip");
        int sliderY = y + 42;
        int pitch = Math.max(24, Math.min(28, (boxTop + boxH - INNER - 20 - sliderY) / 3));
        escortsSlider = new GuiWorkerFormationSlider(cx, sliderY, cw, 20,
                "travellers.worker.formation.escorts",
                WorkerFormationSettings.MIN_ESCORTS, WorkerFormationSettings.MAX_ESCORTS,
                1.0D, currentSettings != null ? currentSettings.numEscorts : 0,
                v -> Integer.toString((int) Math.round(v)));
        followDistSlider = new GuiWorkerFormationSlider(cx, sliderY + pitch, cw, 20,
                "travellers.worker.formation.follow_distance",
                WorkerFormationSettings.MIN_FOLLOW_DISTANCE, WorkerFormationSettings.MAX_FOLLOW_DISTANCE,
                0.5D, currentSettings != null ? currentSettings.followDistance : 8.0, this::formatDouble);
        spacingSlider = new GuiWorkerFormationSlider(cx, sliderY + pitch * 2, cw, 20,
                "travellers.worker.formation.spacing",
                WorkerFormationSettings.MIN_SPACING, WorkerFormationSettings.MAX_SPACING,
                0.25D, currentSettings != null ? currentSettings.spacing : 1.0, this::formatDouble);
        widthSlider = new GuiWorkerFormationSlider(cx, sliderY + pitch * 3, cw, 20,
                "travellers.worker.formation.width",
                WorkerFormationSettings.MIN_WIDTH, WorkerFormationSettings.MAX_WIDTH,
                1.0D, currentSettings != null ? currentSettings.width : 4,
                v -> Integer.toString((int) Math.round(v)));

        // A resize keeps the view; a fresh open starts on the selected leader.
        BlockPos center = mapController != null ? mapController.panel.getCenter() : getSelectedLeaderPos();
        float bpp = mapController != null ? mapController.panel.getZoomBlocksPerPixel()
                : (float) TravellersConfig.WORLDMAP.formationHubMapInitialBpp;
        mapController = new WorldMapController(Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_formation_hub_map", center);
        mapController.panel.setZoomBlocksPerPixel(bpp);

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(
                representativeEntityId >= 0 ? representativeEntityId : -1));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestEquipDests());

        historyPinnedToBottom = true;
        historyScroll = Integer.MAX_VALUE;
        lastHistoryCount = -1;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestJournalHistory());

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
        histH = history ? TravellersConfig.NOTIFICATIONS.historyPanelHeight : 0;
        if (fixed + boxH + histH > availH) histH = Math.max(history ? MIN_HISTORY_H : 0, availH - fixed - boxH);
        if (fixed + boxH + histH > availH) boxH = Math.max(MIN_BOX_H, availH - fixed - histH);
        frameH = fixed + boxH + histH;

        frameX = frameX(frameW);
        frameY = Math.max(4, (height - frameH) / 2);
        boxTop = frameY + TITLE_H;
        mapX = mapBoxX + 1;
        mapY = boxTop + 1;
        mapW = mapBoxW - 2;
        mapH = boxH - 2;
        hintY = boxTop + boxH + 3;

        histX = frameX + PAD;
        histY = boxTop + boxH + hintH;
        histW = frameW - PAD * 2;
    }

    private int frameX(int w) {
        return (width - w) / 2;
    }

    private List<String> hintText() {
        return fontRenderer.listFormattedStringToWidth(I18n.format("travellers.worker.formation.hint"),
                (int) ((cardX - PAD - mapBoxX) / HINT_SCALE));
    }

    private static final float HINT_SCALE = 0.75f;

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        flushPendingSync(true);
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    // ── Live refresh (MsgOpenFormationHubGui while open) ─────────────────────

    public void updateLeaders(List<FormationLeaderInfo> newLeaders, Map<String, WorkerFormationSettings> newSettings) {
        // Keep the selection by identity: the list is re-sorted by distance on every rebuild.
        UUID prevUuid = null;
        boolean prevPlayer = false;
        if (selectedIndex >= 0 && selectedIndex < leaders.size()) {
            prevPlayer = leaders.get(selectedIndex).isPlayer;
            prevUuid = leaders.get(selectedIndex).uuid;
        }

        leaders.clear();
        leaders.addAll(newLeaders);
        settingsByScope.clear();
        settingsByScope.putAll(newSettings);

        if (!pendingSelectFromFollowers.isEmpty()) {
            for (int i = 0; i < leaders.size(); i++) {
                FormationLeaderInfo info = leaders.get(i);
                if (!info.isPlayer && info.uuid != null && pendingSelectFromFollowers.contains(info.uuid)) {
                    pendingSelectFromFollowers = Collections.emptySet();
                    selectLeaderByIndex(i, false);
                    return;
                }
            }
            pendingSelectFromFollowers = Collections.emptySet();
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
        if (relocated >= 0) selectedIndex = relocated;
        else if (selectedIndex >= leaders.size()) selectedIndex = Math.max(0, leaders.size() - 1);

        if (leaders.isEmpty()) selectionMode = SelectionMode.NONE;
        else if (selectionMode == SelectionMode.NONE) selectionMode = SelectionMode.LEADER;
        // A periodic refresh must not yank the map or reset sliders being dragged.
        if (selectionChanged || currentSettings == null) applySelection(false);
        refreshButtons();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        flushPendingSync(false);

        int count = ClientJournalCache.entries().size();
        if (count != lastHistoryCount) {
            lastHistoryCount = count;
            if (historyPinnedToBottom) historyScroll = Integer.MAX_VALUE;
        }

        if (++leaderListRefreshTicks >= LEADER_LIST_REFRESH_INTERVAL_TICKS) {
            leaderListRefreshTicks = 0;
            if (!menu.isOpen()) TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestFormationHub());
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        TravellersGuiTheme.drawPanel(frameX, frameY, frameW, frameH);
        fontRenderer.drawString(TextFormatting.BOLD + I18n.format("travellers.worker.formation.hub_title"),
                listX + INNER, frameY + (TITLE_H - fontRenderer.FONT_HEIGHT) / 2 + 1, COL_TEXT);

        drawLeaderList(mouseX, mouseY);
        drawCard();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        TravellersGuiTheme.drawInnerPanel(mapBoxX, boxTop, mapBoxW, boxH);
        rebuildMapMarkers();
        mapController.panel.setHighlighted(highlightedTargets(mouseX, mouseY));
        mapController.draw();
        drawDestinationCross();
        drawSelectionRectangle();
        drawHint();
        drawHistoryPanel(mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (selectionMode != SelectionMode.FOLLOWERS) {
            escortsSlider.draw(mc, mouseX, mouseY);
            followDistSlider.draw(mc, mouseX, mouseY);
            spacingSlider.draw(mc, mouseX, mouseY);
            widthSlider.draw(mc, mouseX, mouseY);
        }

        if (menu.isOpen()) {
            menu.draw(mouseX, mouseY);
            return;
        }
        if (!mapDragSelecting) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) drawHoveringText(Arrays.asList(label.split("\n")), mouseX, mouseY);
        }
        if (TravellersConfig.NOTIFICATIONS.historyPanelBattleTooltipEnabled) {
            JournalEntry hovered = getHistoryEntryAt(mouseX, mouseY);
            if (hovered != null && hovered.battleSummary != null) {
                drawHoveringText(buildBattleTooltip(hovered.battleSummary), mouseX, mouseY);
            }
        }
        tooltips.render(mouseX, mouseY, width, height);
    }

    private int listRowsTop() {
        return boxTop + HEADER_H + 2;
    }

    private int visibleListRows() {
        return Math.max(1, (boxTop + boxH - 2 - listRowsTop()) / ROW_H);
    }

    private void drawBoxHeader(int x, int w, String text) {
        fontRenderer.drawString(fontRenderer.trimStringToWidth(text, w - INNER * 2), x + INNER, boxTop + 4, COL_TEXT);
        TravellersGuiTheme.drawSeparator(x + 1, boxTop + HEADER_H - 1, w - 2);
    }

    private void drawLeaderList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listX, boxTop, LIST_W, boxH);
        drawBoxHeader(listX, LIST_W, TextFormatting.BOLD + I18n.format("travellers.worker.formation.leaders"));
        clampScroll();
        int rowsTop = listRowsTop();
        int visibleRows = visibleListRows();
        for (int i = 0; i < visibleRows; i++) {
            int idx = listScroll + i;
            if (idx >= leaders.size()) break;
            int rowTop = rowsTop + i * ROW_H;
            boolean hovered = !menu.isOpen() && mouseX >= listX && mouseX < listX + LIST_W
                    && mouseY >= rowTop && mouseY < rowTop + ROW_H;
            boolean selected = idx == selectedIndex && selectionMode == SelectionMode.LEADER;
            TravellersGuiTheme.drawListRow(listX + 1, rowTop, LIST_W - 2, ROW_H, hovered, selected);

            FormationLeaderInfo info = leaders.get(idx);
            int textX = listX + INNER;
            if (!info.isPlayer) {
                GlStateManager.color(1f, 1f, 1f, 1f);
                WorkerHeadRenderer.draw(info.cultureId, info.textureIndex, info.ownerColor, listX + INNER, rowTop + 2, 16);
                textX = listX + INNER + 19;
            }
            String name = info.isPlayer ? I18n.format("travellers.worker.formation.you") : info.name;
            if (info.isVirtual) name = TextFormatting.GRAY + name;
            int maxW = listX + LIST_W - INNER - textX;
            fontRenderer.drawString(fontRenderer.trimStringToWidth(name, maxW), textX, rowTop + 2,
                    selected ? 0xFFFFCC88 : COL_TEXT);

            String sub = info.followerCount + " ♥";
            if (!info.isPlayer) {
                double dx = info.posX - player.posX, dz = info.posZ - player.posZ;
                sub += "  " + (int) Math.sqrt(dx * dx + dz * dz) + "m " + LocationFormat.cardinal(dx, dz);
            }
            GlStateManager.pushMatrix();
            GlStateManager.translate(textX, rowTop + 12, 0f);
            GlStateManager.scale(0.75f, 0.75f, 1f);
            fontRenderer.drawString(sub, 0, 0, COL_TEXT_DIM);
            GlStateManager.popMatrix();
        }
        if (listScroll > 0) fontRenderer.drawString("▲", listX + LIST_W - INNER - 6, boxTop + 3, COL_TEXT_DIM);
        if (listScroll + visibleRows < leaders.size())
            fontRenderer.drawString("▼", listX + LIST_W - INNER - 6, boxTop + boxH - 10, COL_TEXT_DIM);
    }

    /** Right box: what is selected (header), its state, and for a group or your escort its formation settings. */
    private void drawCard() {
        TravellersGuiTheme.drawInnerPanel(cardX, boxTop, CARD_W, boxH);
        int textX = cardX + INNER;
        int textW = CARD_W - INNER * 2;
        int stateY = boxTop + HEADER_H + 3;
        if (selectionMode == SelectionMode.FOLLOWERS) {
            drawBoxHeader(cardX, CARD_W, TextFormatting.BOLD + I18n.format(
                    "travellers.worker.formation.card.followers_selected", mapSelectedFollowers.size()));
            List<String> lines = fontRenderer.listFormattedStringToWidth(
                    I18n.format("travellers.worker.formation.card.followers_hint"), textW);
            for (int i = 0; i < lines.size(); i++) fontRenderer.drawString(lines.get(i), textX, stateY + i * 10, COL_TEXT_DIM);
            return;
        }
        FormationLeaderInfo sel = selectedLeader();
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

    /** Markers drawn highlighted: what a right click would target under the cursor, or the open menu's targets. */
    private Set<BlockPos> highlightedTargets(int mouseX, int mouseY) {
        if (menu.isOpen()) return new HashSet<>(menuTargets);
        if (mapDragSelecting || !mapController.isMouseOver(mouseX, mouseY)) return Collections.emptySet();
        List<Target> hits = findTargets(mouseX, mouseY);
        return hits.isEmpty() ? Collections.emptySet() : Collections.singleton(hits.get(0).pos);
    }

    private void drawDestinationCross() {
        if (destinationCrossPos == null) return;
        long now = System.currentTimeMillis();
        if (now >= destinationCrossExpireMs) {
            destinationCrossPos = null;
            return;
        }
        int sx = Math.round(mapController.worldToScreenX(destinationCrossPos.getX()));
        int sy = Math.round(mapController.worldToScreenY(destinationCrossPos.getZ()));
        if (!mapController.isMouseOver(sx, sy)) return;

        float elapsed = (now - (destinationCrossExpireMs - DESTINATION_CROSS_DURATION_MS)) / (float) DESTINATION_CROSS_DURATION_MS;
        float alpha = elapsed < 0.6f ? 1.0f : 1.0f - (elapsed - 0.6f) / 0.4f;
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, alpha);
        mc.getTextureManager().bindTexture(DESTINATION_CROSS_TEX);
        drawModalRectWithCustomSizedTexture(sx - 4, sy - 4, 0, 0, 8, 8, 8, 8);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ── Journal ───────────────────────────────────────────────────────────────

    private void drawHistoryPanel(int mouseX, int mouseY) {
        if (histH <= 0) return;
        TravellersGuiTheme.drawInnerPanel(histX, histY, histW, histH);
        int pad = 3;
        int innerX = histX + pad, innerY = histY + pad;
        int innerW = histW - pad * 2 - HISTORY_GUTTER_W;
        int innerH = histH - pad * 2;

        if (!ClientJournalCache.isLoaded()) {
            fontRenderer.drawString(I18n.format("travellers.gui.history.loading"), innerX, innerY, COL_TEXT_DIM);
            return;
        }
        List<JournalEntry> entries = ClientJournalCache.entries();
        if (entries.isEmpty()) {
            fontRenderer.drawString(I18n.format("travellers.gui.history.empty"), innerX, innerY, COL_TEXT_DIM);
            return;
        }

        float fontScale = TravellersConfig.NOTIFICATIONS.historyPanelFontScale;
        clampHistoryScroll(entries, innerH);
        long worldTime = mc.world != null ? mc.world.getTotalWorldTime() : 0L;
        boolean showBattleIcon = TravellersConfig.NOTIFICATIONS.historyPanelBattleTooltipEnabled;

        int rowTop = innerY;
        int shown = 0;
        for (int idx = historyScroll; idx < entries.size(); idx++) {
            JournalEntry entry = entries.get(idx);
            String[] lines = historyLines(entry, worldTime);
            int rowH = historyEntryHeight(lines.length);
            if (shown > 0 && rowTop + rowH > innerY + innerH) break;
            shown++;

            // Only a located entry reacts to the mouse: clicking it centers the map there.
            boolean hovered = !menu.isOpen() && entry.place != null && mouseX >= innerX && mouseX < innerX + innerW
                    && mouseY >= rowTop && mouseY < rowTop + rowH;
            TravellersGuiTheme.drawListRow(innerX, rowTop, innerW, rowH, hovered, false);
            drawRect(innerX + 1, rowTop + rowH / 2 - 2, innerX + 5, rowTop + rowH / 2 + 2, entry.type.color);

            boolean hasBattle = showBattleIcon && entry.battleSummary != null;
            int iconW = hasBattle ? 10 : 0;
            int textMaxW = Math.max(10, (int) ((innerW - 8 - iconW) / fontScale) - 4);
            float lineH = historyLineHeight();
            GlStateManager.pushMatrix();
            GlStateManager.translate(innerX + 8, rowTop + (rowH - lineH * lines.length) / 2f, 0f);
            GlStateManager.scale(fontScale, fontScale, 1f);
            for (int l = 0; l < lines.length; l++) {
                fontRenderer.drawString(fontRenderer.trimStringToWidth(lines[l], textMaxW), 0,
                        Math.round(l * lineH / fontScale), COL_TEXT);
            }
            GlStateManager.popMatrix();
            if (hasBattle) {
                fontRenderer.drawString("⚔", innerX + innerW - 10, rowTop + (rowH - fontRenderer.FONT_HEIGHT) / 2, 0xFFCCCCCC);
            }
            rowTop += rowH;
        }
        drawHistoryScrollbar(innerX + innerW + 2, innerY, innerH, entries.size(), shown);
    }

    // Lives in a strip reserved on the right of the journal, so it never covers row content.
    private void drawHistoryScrollbar(int x, int y, int h, int total, int shown) {
        if (total <= shown || total <= 0) return;
        drawRect(x, y, x + 2, y + h, 0x30FFFFFF);
        int thumbH = Math.max(6, h * shown / total);
        int maxStart = Math.max(1, total - shown);
        int thumbY = y + (h - thumbH) * Math.min(historyScroll, maxStart) / maxStart;
        drawRect(x, thumbY, x + 2, thumbY + thumbH, 0xC0FFFFFF);
    }

    /** Entry text split on '\n' (only the first line carries the time-ago prefix). */
    private String[] historyLines(JournalEntry entry, long worldTime) {
        String text = NoticeText.format(entry.i18nKey, entry.args, entry.place, entry.detail);
        String[] lines = text.split("\n", -1);
        lines[0] = TextFormatting.GRAY + "[" + formatTimeAgo(worldTime - entry.worldTime) + "] "
                + TextFormatting.RESET + lines[0];
        return lines;
    }

    private float historyLineHeight() {
        return fontRenderer.FONT_HEIGHT * TravellersConfig.NOTIFICATIONS.historyPanelFontScale + 1f;
    }

    private int historyEntryHeight(int lineCount) {
        int minH = TravellersConfig.NOTIFICATIONS.historyPanelRowHeight;
        return lineCount <= 1 ? minH : Math.max(minH, (int) Math.ceil(historyLineHeight() * lineCount) + 2);
    }

    private int historyEntryHeight(JournalEntry entry) {
        return historyEntryHeight(historyLines(entry, 0L).length);
    }

    /** Scroll is the index of the first visible entry; the max is the earliest start that still fits the newest entry at the bottom. */
    private void clampHistoryScroll(List<JournalEntry> entries, int innerH) {
        int total = entries.size();
        int idx = total;
        int used = 0;
        while (idx > 0) {
            int h = historyEntryHeight(entries.get(idx - 1));
            if (used + h > innerH) break;
            used += h;
            idx--;
        }
        int max = Math.max(0, Math.min(idx, total - 1));
        if (historyScroll > max) historyScroll = max;
        if (historyScroll < 0) historyScroll = 0;
        historyPinnedToBottom = historyScroll >= max;
    }

    private String formatTimeAgo(long deltaTicks) {
        long seconds = Math.max(0, deltaTicks) / 20;
        if (seconds < 60) return I18n.format("travellers.gui.history.time.just_now");
        long minutes = seconds / 60;
        if (minutes < 60) return I18n.format("travellers.gui.history.time.minutes_ago", minutes);
        long hours = minutes / 60;
        if (hours < 24) return I18n.format("travellers.gui.history.time.hours_ago", hours);
        return I18n.format("travellers.gui.history.time.days_ago", hours / 24);
    }

    @Nullable
    private JournalEntry getHistoryEntryAt(int mouseX, int mouseY) {
        if (histH <= 0 || menu.isOpen()) return null;
        int pad = 3;
        int innerX = histX + pad, innerY = histY + pad;
        int innerW = histW - pad * 2 - HISTORY_GUTTER_W;
        int innerH = histH - pad * 2;
        if (mouseX < innerX || mouseX >= innerX + innerW || mouseY < innerY || mouseY >= innerY + innerH) return null;
        List<JournalEntry> entries = ClientJournalCache.entries();
        int rowTop = innerY;
        for (int idx = historyScroll; idx >= 0 && idx < entries.size(); idx++) {
            int rowH = historyEntryHeight(entries.get(idx));
            if (idx > historyScroll && rowTop + rowH > innerY + innerH) break;
            if (mouseY < rowTop + rowH) return entries.get(idx);
            rowTop += rowH;
        }
        return null;
    }

    /** Centers the map on the unit an entry is about when it is on the map, else on where it happened. */
    private void handleHistoryRowClick(int mouseX, int mouseY) {
        JournalEntry entry = getHistoryEntryAt(mouseX, mouseY);
        if (entry == null || entry.place == null) return;
        BlockPos target = entry.place.pos;
        MsgMapWorkersUpdate.Entry live = workerByUuid(entry.place.subject);
        if (live != null) target = live.pos;
        mapController.setCenter(target);
    }

    private List<String> buildBattleTooltip(BattleSummary bs) {
        List<String> lines = new ArrayList<>();
        String location = LocationFormat.describe(bs.location, bs.dimension);
        if (location != null) lines.add(location);
        for (BattleSummary.CasualtyEntry c : bs.ownWorkerCasualties) {
            lines.add(c.died
                    ? I18n.format("travellers.gui.history.battle_tooltip.casualty_died", c.name)
                    : I18n.format("travellers.gui.history.battle_tooltip.casualty_hurt", c.name, (int) c.healthAfter));
        }
        if (!bs.attackerDescriptions.isEmpty()) {
            lines.add(I18n.format("travellers.gui.history.battle_tooltip.attackers"));
            for (String desc : bs.attackerDescriptions) lines.add("- " + desc);
        }
        return lines;
    }

    // ── Map markers ───────────────────────────────────────────────────────────

    private void rebuildMapMarkers() {
        mapController.panel.clearMarkers();
        int ownerColor = ClientOwnerVisualsCache.getColor();
        WorldMapContentPopulator.populate(mapController.panel, poiEntries, workerEntries, player, ownerColor, null);
        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        WorldMapContentPopulator.populateForeignUnits(mapController.panel, foreignEntries, myDim);
        for (MsgMapBattleUpdate.Entry e : battleEntries) {
            if (e.dim == myDim) mapController.panel.addMarker(WorldMapMarkerFactory.battle(e.pos, e.label));
        }

        // Selected markers again on top (same type = drawn later within the pass)
        FormationLeaderInfo group = selectedGroup();
        if (group != null) {
            MsgMapWorkersUpdate.Entry e = workerByUuid(group.uuid);
            if (e != null && e.isLeader) {
                mapController.panel.addMarker(WorldMapMarkerFactory.leaderHighlighted(e.pos, WorldMapContentPopulator.workerLabel(e)));
            }
        } else if (selectionMode == SelectionMode.FOLLOWERS) {
            Set<UUID> selSet = new HashSet<>(mapSelectedFollowers);
            for (MsgMapWorkersUpdate.Entry e : workerEntries) {
                if (!e.isLeader && e.uuid != null && selSet.contains(e.uuid) && e.pos != null) {
                    mapController.panel.addMarker(WorldMapMarkerFactory.followerHighlighted(
                            e.pos, WorldMapContentPopulator.workerLabel(e), e.ownerColor));
                }
            }
        }
    }

    // ── Receivers ─────────────────────────────────────────────────────────────

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        poiEntries.clear();
        if (entries != null) poiEntries.addAll(entries);
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        workerEntries.clear();
        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        for (MsgMapWorkersUpdate.Entry e : list) {
            if (e.dim == myDim) workerEntries.add(e);
        }
    }

    @Override
    public void receiveBattles(List<MsgMapBattleUpdate.Entry> entries) {
        battleEntries.clear();
        if (entries != null) battleEntries.addAll(entries);
    }

    @Override
    public void receiveForeignUnits(List<MsgMapForeignUnitsUpdate.Entry> entries) {
        foreignEntries.clear();
        if (entries != null) foreignEntries.addAll(entries);
    }

    /** Called by MsgEquipDestsResponse: where a group can be sent for equipment. */
    public void onEquipDestsReceived(List<MsgEquipDestsResponse.EquipDestInfo> dests) {
        this.equipDests = dests != null ? dests : Collections.emptyList();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == closeButton) {
            flushPendingSync(true);
            if (standalone) mc.displayGuiScreen(null);
            else TravellerFollowNet.CHANNEL.sendToServer(new MsgOpenWorkerSubGui(representativeEntityId, MsgOpenWorkerSubGui.Type.HUB));
        } else if (button == barracksButton) {
            mc.displayGuiScreen(new GuiWorkerBarracks(player, this));
        } else if (button == behaviorButton) {
            mc.displayGuiScreen(new GuiWorkerBehavior(player, this));
        } else if (button == styleButton && currentSettings != null) {
            currentSettings.style = currentSettings.style.next();
            onSettingsChanged(true);
        } else if (button == attitudeButton && currentSettings != null) {
            currentSettings.attitude = currentSettings.attitude.next();
            onSettingsChanged(true);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1 && menu.isOpen()) {
            closeMenu();
            return;
        }
        if (keyCode == FormationHubKeyHandler.KEY.getKeyCode()) {
            flushPendingSync(true);
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (menu.isOpen()) {
            menu.mouseClicked(mouseX, mouseY);
            if (!menu.isOpen()) menuTargets.clear();
            return;
        }

        if (mouseButton == 0 && histH > 0 && mouseX >= histX && mouseX < histX + histW
                && mouseY >= histY && mouseY < histY + histH) {
            handleHistoryRowClick(mouseX, mouseY);
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

        if (mouseButton == 0 && mouseX >= listX && mouseX < listX + LIST_W
                && mouseY >= listRowsTop() && mouseY < listRowsTop() + visibleListRows() * ROW_H) {
            int idx = listScroll + (mouseY - listRowsTop()) / ROW_H;
            if (idx >= 0 && idx < leaders.size()) selectLeaderByIndex(idx, true);
            return;
        }

        if (selectionMode != SelectionMode.FOLLOWERS) {
            boolean consumed = escortsSlider.mousePressed(mouseX, mouseY, mouseButton);
            consumed |= followDistSlider.mousePressed(mouseX, mouseY, mouseButton);
            consumed |= spacingSlider.mousePressed(mouseX, mouseY, mouseButton);
            consumed |= widthSlider.mousePressed(mouseX, mouseY, mouseButton);
            if (consumed) {
                pullSliders();
                onSettingsChanged(false);
                return;
            }
        }

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
        boolean changed = escortsSlider.mouseDragged(mouseX, mouseY);
        changed |= followDistSlider.mouseDragged(mouseX, mouseY);
        changed |= spacingSlider.mouseDragged(mouseX, mouseY);
        changed |= widthSlider.mouseDragged(mouseX, mouseY);
        if (changed) {
            pullSliders();
            onSettingsChanged(false);
        }
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
            openOrdersMenu(mouseX, mouseY);
            return;
        }

        boolean changed = escortsSlider.mouseReleased(state);
        changed |= followDistSlider.mouseReleased(state);
        changed |= spacingSlider.mouseReleased(state);
        changed |= widthSlider.mouseReleased(state);
        if (changed) {
            pullSliders();
            onSettingsChanged(false);
            flushPendingSync(true);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) return;
        if (menu.isOpen()) {
            closeMenu();
            return;
        }
        int mouseX = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;

        if (mouseX >= listX && mouseX < listX + LIST_W && mouseY >= boxTop && mouseY < boxTop + boxH) {
            listScroll += wheel > 0 ? -1 : 1;
            clampScroll();
        } else if (mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (histH > 0 && mouseX >= histX && mouseX < histX + histW && mouseY >= histY && mouseY < histY + histH) {
            historyScroll += wheel > 0 ? -1 : 1;
            clampHistoryScroll(ClientJournalCache.entries(), histH - 6);
        }
    }

    private void closeMenu() {
        menu.close();
        menuTargets.clear();
    }

    // ── Map targets ───────────────────────────────────────────────────────────

    private enum TargetKind { PLAYER, LEADER, FOLLOWER, ON_DUTY, PLACE }

    /** Something under the cursor on the map. */
    private static final class Target {
        final TargetKind kind;
        final BlockPos pos;
        final String name;
        @Nullable final UUID uuid;
        @Nullable final MsgEquipDestsResponse.EquipDestInfo equip;
        final float distSq;

        Target(TargetKind kind, BlockPos pos, String name, @Nullable UUID uuid,
               @Nullable MsgEquipDestsResponse.EquipDestInfo equip, float distSq) {
            this.kind = kind;
            this.pos = pos;
            this.name = name;
            this.uuid = uuid;
            this.equip = equip;
            this.distSq = distSq;
        }
    }

    /** Everything within a few pixels of the cursor: units first (nearest first), then places. */
    private List<Target> findTargets(int mouseX, int mouseY) {
        List<Target> units = new ArrayList<>();
        List<Target> places = new ArrayList<>();
        float r2 = TARGET_RADIUS_PX * TARGET_RADIUS_PX;

        float d = screenDistSq(player.getPosition(), mouseX, mouseY);
        if (d <= r2) {
            units.add(new Target(TargetKind.PLAYER, player.getPosition(),
                    I18n.format("travellers.worker.formation.you"), null, null, d));
        }
        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (e.pos == null || e.uuid == null) continue;
            d = screenDistSq(e.pos, mouseX, mouseY);
            if (d > r2) continue;
            TargetKind kind = WorldMapContentPopulator.isOnDuty(e) ? TargetKind.ON_DUTY
                    : e.isLeader ? TargetKind.LEADER : TargetKind.FOLLOWER;
            units.add(new Target(kind, e.pos, e.name, e.uuid, null, d));
        }
        Set<BlockPos> placed = new HashSet<>();
        for (MsgWorkerGoList.Entry e : poiEntries) {
            if (e.pos == null) continue;
            d = screenDistSq(e.pos, mouseX, mouseY);
            if (d > r2) continue;
            places.add(new Target(TargetKind.PLACE, e.pos, e.label, null, equipDestAt(e.pos), d));
            placed.add(e.pos);
        }
        for (MsgEquipDestsResponse.EquipDestInfo e : equipDests) {
            if (e.pos == null || placed.contains(e.pos)) continue;
            d = screenDistSq(e.pos, mouseX, mouseY);
            if (d <= r2) places.add(new Target(TargetKind.PLACE, e.pos, e.name, null, e, d));
        }
        Comparator<Target> nearest = Comparator.comparingDouble(t -> t.distSq);
        units.sort(nearest);
        places.sort(nearest);
        units.addAll(places);
        return units;
    }

    private float screenDistSq(BlockPos pos, int mouseX, int mouseY) {
        float dx = mouseX - mapController.worldToScreenX(pos.getX());
        float dy = mouseY - mapController.worldToScreenY(pos.getZ());
        return dx * dx + dy * dy;
    }

    @Nullable
    private MsgEquipDestsResponse.EquipDestInfo equipDestAt(BlockPos pos) {
        for (MsgEquipDestsResponse.EquipDestInfo e : equipDests) {
            if (pos.equals(e.pos)) return e;
        }
        return null;
    }

    // ── Orders menu ───────────────────────────────────────────────────────────

    /**
     * Builds the menu for the current selection and what lies under the cursor. A unit the selection
     * has no order for (or a right click with nothing to command) selects that unit and shows its own menu.
     */
    private void openOrdersMenu(int mouseX, int mouseY) {
        closeMenu();
        List<Target> hits = findTargets(mouseX, mouseY);
        Target unit = !hits.isEmpty() && hits.get(0).kind != TargetKind.PLACE ? hits.get(0) : null;
        Target place = null;
        for (Target t : hits) {
            if (t.kind == TargetKind.PLACE) {
                place = t;
                break;
            }
        }
        BlockPos ground = mapController.screenToWorld(mouseX, mouseY);

        FormationLeaderInfo group = selectedGroup();
        boolean followers = selectionMode == SelectionMode.FOLLOWERS && !mapSelectedFollowers.isEmpty();

        if (unit != null && unit.kind == TargetKind.ON_DUTY) {
            menu.header(unit.name).disabled(tr("no_orders"), tr("reason.on_duty"));
            menuTargets.add(unit.pos);
        } else if (unit != null && unit.kind == TargetKind.LEADER && group == null && !followers) {
            selectLeaderByUuid(unit.uuid);
            buildGroupMenu(selectedGroup(), unit, null, null);
        } else if (unit != null && unit.kind == TargetKind.FOLLOWER
                && !(followers && mapSelectedFollowers.contains(unit.uuid))) {
            selectFollowers(Collections.singletonList(unit.uuid));
            buildFollowersMenu(unit, null, null);
        } else if (group != null) {
            buildGroupMenu(group, unit, place, ground);
        } else if (followers) {
            buildFollowersMenu(unit, place, ground);
        } else {
            if (place != null) menu.header(place.name);
            menu.disabled(tr("no_orders"), tr("reason.select_first"));
        }
        menu.open(mouseX, mouseY, width, height);
    }

    /** Orders for one worker group: toward itself, a unit, a place, or bare ground. */
    private void buildGroupMenu(@Nullable FormationLeaderInfo group, @Nullable Target unit,
                                @Nullable Target place, @Nullable BlockPos ground) {
        if (group == null || group.uuid == null) return;
        UUID src = group.uuid;
        String busy = group.busy ? tr("reason.busy") : null;
        boolean goOffered = false;
        boolean self = unit != null && src.equals(unit.uuid);

        if (self) {
            // Halt whenever it is moving (following or sent somewhere); resume whenever it isn't following.
            menu.header(group.name);
            if (group.followActive || group.goActive) {
                menu.itemUnless(busy, tr("halt"), () -> setLeaderFollow(group, false));
            }
            if (!group.followActive) {
                menu.itemUnless(busy, tr("resume"), () -> setLeaderFollow(group, true));
            }
            menuTargets.add(unit.pos);
        } else if (unit != null && unit.kind == TargetKind.PLAYER) {
            menu.header(unit.name);
            addGo(src, unit.pos, busy);
            menu.itemUnless(busy, tr("follow_me"), () -> TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupFollow(src, null)));
            menu.itemUnless(busy, tr("merge_escort"), () -> TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupMerge(src, null)));
            goOffered = true;
            menuTargets.add(unit.pos);
        } else if (unit != null && unit.kind == TargetKind.LEADER) {
            FormationLeaderInfo target = leaderByUuid(unit.uuid);
            String why = busy != null ? busy : target != null && target.busy ? tr("reason.target_busy") : null;
            UUID dst = unit.uuid;
            menu.header(unit.name);
            addGo(src, unit.pos, busy);
            menu.itemUnless(why, tr("follow_group"), () -> TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupFollow(src, dst)));
            menu.itemUnless(why, tr("merge_group"), () -> TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupMerge(src, dst)));
            goOffered = true;
            menuTargets.add(unit.pos);
        }

        if (place != null) {
            menu.header(place.name);
            if (!goOffered) addGo(src, place.pos, busy);
            goOffered = true;
            if (place.equip != null) {
                MsgEquipDestsResponse.EquipDestInfo dest = place.equip;
                menu.itemUnless(busy, tr("equip_return"), () -> sendEquip(src, dest, false));
                menu.itemUnless(busy, tr("equip_stay"), () -> sendEquip(src, dest, true));
            }
            menuTargets.add(place.pos);
        }

        if (!goOffered && !self && ground != null) {
            addGo(src, ground, busy);
            menuTargets.add(ground);
        }
    }

    private void setLeaderFollow(FormationLeaderInfo group, boolean follow) {
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSetLeaderFollow(group.uuid, follow));
        // shown at once; the next list refresh corrects a refusal
        group.followActive = follow;
        group.goActive = false;
    }

    private void addGo(UUID leader, BlockPos pos, @Nullable String busy) {
        menu.itemUnless(busy, tr("go"), () -> {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgLeaderGoTo(leader, pos));
            showDestination(pos);
        });
    }

    private void sendEquip(UUID leader, MsgEquipDestsResponse.EquipDestInfo dest, boolean stay) {
        TravellerFollowNet.CHANNEL.sendToServer(new MsgLeaderGetEquip(leader, dest.pos, dest.kind, stay));
        showDestination(dest.pos);
    }

    /** Orders for the selected loose followers. */
    private void buildFollowersMenu(@Nullable Target unit, @Nullable Target place, @Nullable BlockPos ground) {
        List<UUID> sel = new ArrayList<>(mapSelectedFollowers);
        String needTwo = sel.size() < 2 ? tr("reason.need_two") : null;

        if (unit != null && unit.kind == TargetKind.PLAYER) {
            menu.header(unit.name).item(tr("follow_me"), () -> sendFollowers(MsgGroupCreate.Action.COME_BACK, sel, null));
            menuTargets.add(unit.pos);
            return;
        }
        if (unit != null && unit.kind == TargetKind.LEADER) {
            FormationLeaderInfo target = leaderByUuid(unit.uuid);
            String why = target != null && target.busy ? tr("reason.target_busy") : null;
            UUID dst = unit.uuid;
            menu.header(unit.name).itemUnless(why, tr("join_group"),
                    () -> sendFollowers(MsgGroupCreate.Action.ASSIGN_TO_LEADER, sel, dst));
            menuTargets.add(unit.pos);
            return;
        }
        if (unit != null && unit.kind == TargetKind.FOLLOWER) {
            // right click on the selection itself
            menu.header(I18n.format("travellers.worker.formation.card.followers_selected", sel.size()));
            menu.item(tr("follow_me"), () -> sendFollowers(MsgGroupCreate.Action.COME_BACK, sel, null));
            menu.itemUnless(needTwo, tr("create_group"), () -> createGroup(sel, null));
            menuTargets.add(unit.pos);
            return;
        }
        BlockPos dest = place != null ? place.pos : ground;
        if (place != null) menu.header(place.name);
        if (dest != null) {
            menu.item(tr("create_group_go"), () -> createGroup(sel, dest));
            menuTargets.add(dest);
        }
        menu.itemUnless(needTwo, tr("create_group"), () -> createGroup(sel, null));
    }

    private void sendFollowers(MsgGroupCreate.Action action, List<UUID> sel, @Nullable UUID leader) {
        TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupCreate(action, sel, leader));
        selectPlayerRow();
    }

    private void createGroup(List<UUID> sel, @Nullable BlockPos goTo) {
        pendingSelectFromFollowers = new HashSet<>(sel);
        TravellerFollowNet.CHANNEL.sendToServer(goTo != null
                ? MsgGroupCreate.createAndGo(sel, goTo)
                : new MsgGroupCreate(MsgGroupCreate.Action.CREATE_GROUP, sel, null));
        if (goTo != null) showDestination(goTo);
        selectPlayerRow();
    }

    private void showDestination(BlockPos pos) {
        destinationCrossPos = pos;
        destinationCrossExpireMs = System.currentTimeMillis() + DESTINATION_CROSS_DURATION_MS;
    }

    private static String tr(String key) {
        return I18n.format("travellers.worker.formation.menu." + key);
    }

    // ── Left-click selection ──────────────────────────────────────────────────

    private void handleMapLeftClick(int mouseX, int mouseY) {
        boolean shift = isShiftKeyDown();
        MsgMapWorkersUpdate.Entry closest = null;
        float best = TARGET_RADIUS_PX * TARGET_RADIUS_PX;
        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (e.pos == null || e.uuid == null || WorldMapContentPopulator.isOnDuty(e)) continue;
            float d = screenDistSq(e.pos, mouseX, mouseY);
            if (d <= best) {
                best = d;
                closest = e;
            }
        }

        if (closest == null) {
            if (!shift) selectPlayerRow(); // empty map: back to the resting state, map left where it is
            return;
        }
        if (shift) {
            // Shift adds followers to the selection and ignores leaders
            if (!closest.isLeader) {
                Set<UUID> combined = new LinkedHashSet<>(selectionMode == SelectionMode.FOLLOWERS
                        ? mapSelectedFollowers : Collections.emptyList());
                combined.add(closest.uuid);
                selectFollowers(new ArrayList<>(combined));
            }
        } else if (closest.isLeader) {
            selectLeaderByUuid(closest.uuid);
        } else {
            selectFollowers(Collections.singletonList(closest.uuid));
        }
    }

    private void computeMapRectangleSelection() {
        int x1 = Math.min(mapDragStartX, mapDragCurrentX), x2 = Math.max(mapDragStartX, mapDragCurrentX);
        int y1 = Math.min(mapDragStartY, mapDragCurrentY), y2 = Math.max(mapDragStartY, mapDragCurrentY);
        int pad = TARGET_RADIUS_PX / 2;
        List<UUID> leaderUuids = new ArrayList<>();
        List<UUID> followerUuids = new ArrayList<>();
        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (e.pos == null || e.uuid == null || WorldMapContentPopulator.isOnDuty(e)) continue;
            float sx = mapController.worldToScreenX(e.pos.getX());
            float sy = mapController.worldToScreenY(e.pos.getZ());
            if (sx < x1 - pad || sx > x2 + pad || sy < y1 - pad || sy > y2 + pad) continue;
            (e.isLeader ? leaderUuids : followerUuids).add(e.uuid);
        }

        if (isShiftKeyDown()) {
            if (followerUuids.isEmpty()) return;
            Set<UUID> combined = new LinkedHashSet<>(selectionMode == SelectionMode.FOLLOWERS
                    ? mapSelectedFollowers : Collections.emptyList());
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

    @Nullable
    private FormationLeaderInfo selectedLeader() {
        if (selectionMode != SelectionMode.LEADER || selectedIndex < 0 || selectedIndex >= leaders.size()) return null;
        return leaders.get(selectedIndex);
    }

    /** The selected worker group, or null when "You", followers or nothing is selected. */
    @Nullable
    private FormationLeaderInfo selectedGroup() {
        FormationLeaderInfo sel = selectedLeader();
        return sel != null && !sel.isPlayer && sel.uuid != null ? sel : null;
    }

    private void selectLeaderByIndex(int idx, boolean recenter) {
        if (idx < 0 || idx >= leaders.size()) return;
        selectedIndex = idx;
        selectionMode = SelectionMode.LEADER;
        mapSelectedFollowers = Collections.emptyList();
        int vis = visibleListRows();
        if (idx < listScroll) listScroll = idx;
        else if (idx >= listScroll + vis) listScroll = idx - vis + 1;
        applySelection(recenter);
        refreshButtons();
    }

    private void selectLeaderByUuid(@Nullable UUID uuid) {
        if (uuid == null) return;
        for (int i = 0; i < leaders.size(); i++) {
            if (uuid.equals(leaders.get(i).uuid)) {
                selectLeaderByIndex(i, false);
                return;
            }
        }
    }

    private void selectFollowers(List<UUID> uuids) {
        mapSelectedFollowers = uuids;
        selectionMode = SelectionMode.FOLLOWERS;
        refreshButtons();
    }

    /** The resting state: "You" selected, map left where it is. */
    private void selectPlayerRow() {
        for (int i = 0; i < leaders.size(); i++) {
            if (leaders.get(i).isPlayer) {
                selectLeaderByIndex(i, false);
                return;
            }
        }
        selectionMode = SelectionMode.NONE;
        mapSelectedFollowers = Collections.emptyList();
        refreshButtons();
    }

    private void applySelection(boolean recenter) {
        if (leaders.isEmpty()) {
            currentSettings = WorkerFormationSettings.defaults();
            selectedScope = null;
            return;
        }
        FormationLeaderInfo info = leaders.get(Math.min(selectedIndex, leaders.size() - 1));
        selectedScope = scopeOf(info);
        currentSettings = getSettingsForScope(selectedScope);
        pushSettingsToSliders();
        if (recenter && mapController != null) mapController.setCenter(findCurrentLeaderPos(info));
    }

    private BlockPos findCurrentLeaderPos(FormationLeaderInfo info) {
        if (info.isPlayer) return player.getPosition();
        MsgMapWorkersUpdate.Entry e = workerByUuid(info.uuid);
        return e != null ? e.pos : new BlockPos((int) info.posX, 64, (int) info.posZ);
    }

    private BlockPos getSelectedLeaderPos() {
        if (leaders.isEmpty()) return player.getPosition();
        FormationLeaderInfo info = leaders.get(Math.min(selectedIndex, leaders.size() - 1));
        return info.isPlayer ? player.getPosition() : new BlockPos((int) info.posX, 64, (int) info.posZ);
    }

    @Nullable
    private MsgMapWorkersUpdate.Entry workerByUuid(@Nullable UUID uuid) {
        if (uuid == null) return null;
        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (uuid.equals(e.uuid) && e.pos != null) return e;
        }
        return null;
    }

    @Nullable
    private FormationLeaderInfo leaderByUuid(@Nullable UUID uuid) {
        if (uuid == null) return null;
        for (FormationLeaderInfo l : leaders) {
            if (uuid.equals(l.uuid)) return l;
        }
        return null;
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private void pushSettingsToSliders() {
        if (currentSettings == null) return;
        if (escortsSlider != null) escortsSlider.setValue(currentSettings.numEscorts);
        if (followDistSlider != null) followDistSlider.setValue(currentSettings.followDistance);
        if (spacingSlider != null) spacingSlider.setValue(currentSettings.spacing);
        if (widthSlider != null) widthSlider.setValue(currentSettings.width);
    }

    private void pullSliders() {
        if (currentSettings == null) return;
        currentSettings.numEscorts = (int) Math.round(escortsSlider.getValue());
        currentSettings.followDistance = followDistSlider.getValue();
        currentSettings.spacing = spacingSlider.getValue();
        currentSettings.width = (int) Math.round(widthSlider.getValue());
        currentSettings.clamp();
    }

    private void onSettingsChanged(boolean immediate) {
        if (currentSettings != null) currentSettings.clamp();
        refreshButtons();
        pendingSync = true;
        if (immediate) flushPendingSync(true);
    }

    private void flushPendingSync(boolean force) {
        if (!pendingSync || selectedScope == null || currentSettings == null) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastSendMs) < SYNC_THROTTLE_MS) return;
        lastSendMs = now;
        pendingSync = false;
        settingsByScope.put(selectedScope.asMapKey(), currentSettings.copy().clamp());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgUpdateFormationByScope(selectedScope, currentSettings.copy().clamp()));
    }

    private void refreshButtons() {
        boolean settings = selectionMode != SelectionMode.FOLLOWERS && currentSettings != null;
        if (styleButton == null) return;
        styleButton.visible = settings;
        attitudeButton.visible = settings;
        if (!settings) return;
        styleButton.displayString = I18n.format("travellers.worker.formation.style") + ": "
                + I18n.format("travellers.worker.formation.style." + currentSettings.style.name().toLowerCase(Locale.ROOT));
        attitudeButton.displayString = I18n.format("travellers.worker.formation.attitude") + ": "
                + I18n.format("travellers.worker.formation.attitude." + currentSettings.attitude.name().toLowerCase(Locale.ROOT));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkerFormationSettings getSettingsForScope(@Nullable WorkerFormationScopeKey scope) {
        if (scope == null) return WorkerFormationSettings.defaults();
        WorkerFormationSettings s = settingsByScope.get(scope.asMapKey());
        return s != null ? s.copy().clamp() : WorkerFormationSettings.defaults();
    }

    private WorkerFormationScopeKey scopeOf(FormationLeaderInfo info) {
        if (info.isPlayer || info.uuid == null)
            return new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.PLAYER, player.getUniqueID());
        return new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, info.uuid);
    }

    private void clampScroll() {
        int vis = visibleListRows();
        int max = Math.max(0, leaders.size() - vis);
        listScroll = Math.max(0, Math.min(listScroll, max));
    }

    private String formatDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001D) return Integer.toString((int) Math.rint(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
