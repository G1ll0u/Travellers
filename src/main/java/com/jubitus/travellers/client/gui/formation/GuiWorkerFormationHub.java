package com.jubitus.travellers.client.gui.formation;

import com.jubitus.travellers.client.gui.bahavior.GuiWorkerBehavior;
import com.jubitus.travellers.client.gui.go.GuiWorkerGo;
import com.jubitus.travellers.client.gui.home.GuiWorkerBarracks;
import com.jubitus.travellers.client.gui.home.WorkerHeadRenderer;
import com.jubitus.travellers.client.gui.worldmap.IWorkerGoListReceiver;
import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.IBattleMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.IForeignUnitMapReceiver;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.worldmap.WorldMapContentPopulator;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.formations.FormationLeaderInfo;
import com.jubitus.travellers.server.net.message.formations.MsgRequestFormationHub;
import com.jubitus.travellers.server.net.message.formations.MsgUpdateFormationByScope;
import com.jubitus.travellers.server.net.message.group.MsgGroupComeBack;
import com.jubitus.travellers.server.net.message.group.MsgGroupCreate;
import com.jubitus.travellers.server.net.message.group.MsgGroupMerge;
import com.jubitus.travellers.server.net.message.group.MsgLeaderGoTo;
import com.jubitus.travellers.server.net.message.home.MsgEquipDestsResponse;
import com.jubitus.travellers.server.net.message.home.MsgLeaderGetEquip;
import com.jubitus.travellers.server.net.message.home.MsgRequestEquipDests;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerRequestGoList;
import com.jubitus.travellers.client.input.FormationHubKeyHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.*;

@SideOnly(Side.CLIENT)
public class GuiWorkerFormationHub extends GuiScreen
        implements IWorkerGoListReceiver, IWorkerMapReceiver, IBattleMapReceiver, IForeignUnitMapReceiver {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int X_SIZE   = 660;
    private static final int Y_SIZE   = 290;
    private static final int LIST_W   = 150;
    private static final int ORDERS_W = 130;
    private static final int ROW_H    = 22;

    private static final int COL_BG       = TravellersGuiTheme.COL_BG;
    private static final int COL_BORDER   = TravellersGuiTheme.COL_BORDER;
    private static final int COL_TEXT     = TravellersGuiTheme.COL_TEXT;
    private static final int COL_TEXT_DIM = TravellersGuiTheme.COL_TEXT_DIM;
    private static final int COL_SEL      = TravellersGuiTheme.COL_ROW_SEL;
    private static final int COL_HOVER    = TravellersGuiTheme.COL_ROW_HOVER;
    private static final int COL_PANEL    = TravellersGuiTheme.COL_PANEL;

    // ── Selection mode ────────────────────────────────────────────────────────
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

    // ── GUI geometry ──────────────────────────────────────────────────────────
    private int guiLeft, guiTop, effW, effH;
    private int listX, listY, listH;
    private int ordersX, ordersW;
    private int mapAreaX, mapAreaY, mapAreaH, mapW;
    private int rightX, rightW;
    private int contentY, bottomY;

    // ── State ─────────────────────────────────────────────────────────────────
    private int listScroll = 0;
    private boolean mergeMode = false;
    private boolean assignFollowersMode = false;
    private SelectionMode selectionMode = SelectionMode.NONE;
    private List<UUID> mapSelectedFollowers = Collections.emptyList();

    // ── Rectangle selection ───────────────────────────────────────────────────
    private boolean mapDragSelecting = false;
    private int mapDragStartX, mapDragStartY;
    private int mapDragCurrentX, mapDragCurrentY;

    // ── Go destination picking mode ───────────────────────────────────────────
    private boolean goPickingMode = false;
    private UUID goPickingLeaderUUID = null;

    // ── Equip destination picking mode ────────────────────────────────────────
    private boolean equipPickingMode = false;
    private UUID equipPickingLeaderUUID = null;
    private List<MsgEquipDestsResponse.EquipDestInfo> equipDests = null;

    private BlockPos destinationCrossPos = null;
    private long destinationCrossExpireMs = 0L;
    private static final long DESTINATION_CROSS_DURATION_MS = 1500L;
    private static final net.minecraft.util.ResourceLocation DESTINATION_CROSS_TEX =
            new net.minecraft.util.ResourceLocation("travellers", "textures/map/destination_cross.png");

    // After CREATE_GROUP, auto-select the new leader when updateLeaders() arrives
    private Set<UUID> pendingSelectFromFollowers = Collections.emptySet();

    // ── Map data ──────────────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final List<MsgWorkerGoList.Entry> poiEntries = new ArrayList<>();
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private final List<com.jubitus.travellers.server.map.MsgMapBattleUpdate.Entry> battleEntries = new ArrayList<>();
    private final List<com.jubitus.travellers.server.map.MsgMapForeignUnitsUpdate.Entry> foreignEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

    // ── Widgets ───────────────────────────────────────────────────────────────
    private GuiWorkerFormationSlider escortsSlider;
    private GuiWorkerFormationSlider followDistSlider;
    private GuiWorkerFormationSlider spacingSlider;
    private GuiWorkerFormationSlider widthSlider;
    private GuiWorkerFormationSlider turnSlider;

    private GuiButton styleButton;
    private GuiButton caveButton;
    private GuiButton backButton;
    // Leader orders
    private GuiButton goButton;
    private GuiButton comeBackButton;
    private GuiButton mergeButton;
    private GuiButton mergeCancelButton;
    // Follower orders
    private GuiButton groupButton;
    private GuiButton followerComeBackButton;
    private GuiButton followerMergeButton;
    // General
    private GuiButton barracksButton;
    private GuiButton behaviorButton;
    // Go / equip destination picking
    private GuiButton cancelGoButton;
    private GuiButton getEquipButton;

    // ── Sync ──────────────────────────────────────────────────────────────────
    private long lastSendMs = 0L;
    private boolean pendingSync = false;
    private static final long SYNC_THROTTLE_MS = 120L;

    // ─────────────────────────────────────────────────────────────────────────

    public GuiWorkerFormationHub(EntityPlayer player,
                                  int representativeEntityId,
                                  boolean standalone,
                                  List<FormationLeaderInfo> leaders,
                                  Map<String, WorkerFormationSettings> settingsByScope,
                                  WorkerFormationScopeKey initialScope) {
        this.player = player;
        this.representativeEntityId = representativeEntityId;
        this.standalone = standalone;
        this.leaders = leaders;
        this.settingsByScope = settingsByScope;

        this.selectedIndex = 0;
        for (int i = 0; i < leaders.size(); i++) {
            FormationLeaderInfo info = leaders.get(i);
            WorkerFormationScopeKey s = scopeOf(info);
            if (s != null && s.equals(initialScope)) {
                this.selectedIndex = i;
                break;
            }
        }
        applySelection();
        if (!leaders.isEmpty()) selectionMode = SelectionMode.LEADER;
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();

        effW = Math.min(X_SIZE, width - 4);
        effH = Math.min(Y_SIZE, height - 4);
        guiLeft = (width  - effW) / 2;
        guiTop  = Math.max(2, (height - effH) / 2);

        contentY = guiTop + 22;
        bottomY  = guiTop + effH - 24;

        // Leaders list (leftmost)
        listX = guiLeft + 6;
        listY = contentY;
        listH = effH - 44;

        // Orders panel
        ordersX = listX + LIST_W + 4;
        ordersW = ORDERS_W;

        // Worldmap
        mapAreaX = ordersX + ordersW + 4;
        mapAreaY = contentY;
        mapAreaH = effH - 44;

        // Sliders (rightmost) — map shrinks first so the slider panel keeps its full width
        int budget = effW - (6 + LIST_W + 4 + ordersW + 4 + 4 + 8);
        rightW = Math.max(80, Math.min(134, budget - 80));
        mapW   = Math.max(80, budget - rightW);
        rightX = mapAreaX + mapW + 4;

        // ── Sliders ───────────────────────────────────────────────────────────
        int rowY = contentY;
        styleButton = addButton(new GuiButton(1, rightX, rowY, rightW, 20, ""));
        rowY += 26;
        caveButton  = addButton(new GuiButton(2, rightX, rowY, rightW, 20, ""));
        rowY += 30;

        escortsSlider = new GuiWorkerFormationSlider(rightX, rowY, rightW, 20,
                "travellers.worker.formation.escorts",
                WorkerFormationSettings.MIN_ESCORTS, WorkerFormationSettings.MAX_ESCORTS,
                1.0D, currentSettings != null ? currentSettings.numEscorts : 0,
                v -> Integer.toString((int) Math.round(v)));
        rowY += 34;
        followDistSlider = new GuiWorkerFormationSlider(rightX, rowY, rightW, 20,
                "travellers.worker.formation.follow_distance",
                WorkerFormationSettings.MIN_FOLLOW_DISTANCE, WorkerFormationSettings.MAX_FOLLOW_DISTANCE,
                0.5D, currentSettings != null ? currentSettings.followDistance : 8.0,
                v -> formatDouble(v));
        rowY += 34;
        spacingSlider = new GuiWorkerFormationSlider(rightX, rowY, rightW, 20,
                "travellers.worker.formation.spacing",
                WorkerFormationSettings.MIN_SPACING, WorkerFormationSettings.MAX_SPACING,
                0.25D, currentSettings != null ? currentSettings.spacing : 1.0,
                v -> formatDouble(v));
        rowY += 34;
        widthSlider = new GuiWorkerFormationSlider(rightX, rowY, rightW, 20,
                "travellers.worker.formation.width",
                WorkerFormationSettings.MIN_WIDTH, WorkerFormationSettings.MAX_WIDTH,
                1.0D, currentSettings != null ? currentSettings.width : 4,
                v -> Integer.toString((int) Math.round(v)));
        rowY += 34;
        turnSlider = new GuiWorkerFormationSlider(rightX, rowY, rightW, 20,
                "travellers.worker.formation.turn",
                WorkerFormationSettings.MIN_TURN_SMOOTHNESS, WorkerFormationSettings.MAX_TURN_SMOOTHNESS,
                5.0D, currentSettings != null ? currentSettings.turnSmoothness : 40,
                v -> Integer.toString((int) Math.round(v)));

        // ── Orders buttons ────────────────────────────────────────────────────
        int obX = ordersX + 2;
        int obW = ordersW - 4;
        int obY = contentY + 2;

        // Leader orders
        goButton          = addButton(new GuiButton(30, obX, obY,      obW, 20, I18n.format("travellers.worker.formation.go")));
        comeBackButton    = addButton(new GuiButton(31, obX, obY + 24, obW, 20, I18n.format("travellers.worker.formation.come_back")));
        mergeButton       = addButton(new GuiButton(32, obX, obY + 48, obW, 20, I18n.format("travellers.worker.formation.merge")));
        mergeCancelButton = addButton(new GuiButton(33, obX, obY + 72, obW, 20, I18n.format("travellers.worker.formation.merge_cancel")));
        cancelGoButton    = addButton(new GuiButton(34, obX, obY + 72, obW, 20, I18n.format("travellers.worker.formation.go_cancel")));
        getEquipButton    = addButton(new GuiButton(40, obX, obY + 96, obW, 20, I18n.format("travellers.worker.formation.get_equip")));

        // Follower orders
        groupButton            = addButton(new GuiButton(35, obX, obY,      obW, 20, I18n.format("travellers.worker.formation.group", "Group")));
        followerComeBackButton = addButton(new GuiButton(38, obX, obY + 24, obW, 20, I18n.format("travellers.worker.formation.come_back")));
        followerMergeButton    = addButton(new GuiButton(39, obX, obY + 48, obW, 20, I18n.format("travellers.worker.formation.merge")));

        // General buttons (always visible, below orders; shifted down to make room for getEquipButton)
        int genY = contentY + 129;
        barracksButton = addButton(new GuiButton(36, obX, genY,      obW, 20, I18n.format("travellers.worker.hub.barracks")));
        behaviorButton = addButton(new GuiButton(37, obX, genY + 24, obW, 20, I18n.format("travellers.worker.hub.behavior")));

        // ── Back button ───────────────────────────────────────────────────────
        backButton = addButton(new GuiButton(99, guiLeft + effW - 70, bottomY, 60, 20,
                I18n.format(standalone ? "travellers.gui.close" : "travellers.gui.back")));

        // ── Worldmap ──────────────────────────────────────────────────────────
        BlockPos initialCenter = getSelectedLeaderPos();
        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapAreaX, mapAreaY, mapW, mapAreaH,
                "travellers_formation_hub_map", initialCenter);
        mapController.panel.setZoomBlocksPerPixel((float) TravellersConfig.WORKER.formationHubMapInitialBpp);

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(
                representativeEntityId >= 0 ? representativeEntityId : -1));

        refreshButtons();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        goPickingMode = false;
        goPickingLeaderUUID = null;
        equipPickingMode = false;
        equipPickingLeaderUUID = null;
        equipDests = null;
        flushPendingSync(true);
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    // ── Public refresh (called by MsgOpenFormationHubGui when hub is already open) ──

    public void updateLeaders(List<FormationLeaderInfo> newLeaders, Map<String, WorkerFormationSettings> newSettings) {
        leaders.clear();
        leaders.addAll(newLeaders);
        settingsByScope.clear();
        settingsByScope.putAll(newSettings);

        // After CREATE_GROUP: find the new leader (its UUID was one of the selected followers)
        if (!pendingSelectFromFollowers.isEmpty()) {
            for (int i = 0; i < leaders.size(); i++) {
                FormationLeaderInfo info = leaders.get(i);
                if (!info.isPlayer && info.uuid != null && pendingSelectFromFollowers.contains(info.uuid)) {
                    selectedIndex = i;
                    selectionMode = SelectionMode.LEADER;
                    mapSelectedFollowers = Collections.emptyList();
                    pendingSelectFromFollowers = Collections.emptySet();
                    applySelection();
                    refreshButtons();
                    return;
                }
            }
            pendingSelectFromFollowers = Collections.emptySet();
        }

        // Normal refresh: clamp selectedIndex, keep mode
        if (selectedIndex >= leaders.size()) selectedIndex = leaders.isEmpty() ? 0 : leaders.size() - 1;
        if (!leaders.isEmpty()) {
            if (selectionMode == SelectionMode.NONE) selectionMode = SelectionMode.LEADER;
        } else {
            selectionMode = SelectionMode.NONE;
        }
        applySelection();
        refreshButtons();
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Override
    public void updateScreen() {
        super.updateScreen();
        flushPendingSync(false);
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawRect(guiLeft, guiTop, guiLeft + effW, guiTop + effH, COL_BG);
        drawRect(guiLeft + 1, guiTop + 1, guiLeft + effW - 1, guiTop + effH - 1, COL_BORDER);

        drawCenteredString(fontRenderer,
                TextFormatting.BOLD + I18n.format("travellers.worker.formation.hub_title"),
                guiLeft + effW / 2, guiTop + 6, COL_TEXT);

        // Leaders list
        drawLeaderList(mouseX, mouseY);
        drawRect(listX + LIST_W + 1, guiTop + 2, listX + LIST_W + 2, guiTop + effH - 2, COL_BORDER);

        // Orders panel background
        drawRect(ordersX - 1, contentY - 1, ordersX + ordersW + 1, contentY + listH + 1, 0x18FFFFFF);
        drawRect(ordersX + ordersW + 1, guiTop + 2, ordersX + ordersW + 2, guiTop + effH - 2, COL_BORDER);

        // Worldmap
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        if (mapController != null) {
            drawRect(mapAreaX - 1, mapAreaY - 1, mapAreaX + mapW + 1, mapAreaY + mapAreaH + 1, COL_PANEL);
            rebuildMapMarkers();
            mapController.draw();
            drawDestinationCross();
        }
        drawRect(mapAreaX + mapW + 1, guiTop + 2, mapAreaX + mapW + 2, guiTop + effH - 2, COL_BORDER);


        // Bottom bar hints
        if (goPickingMode) {
            int hintY = bottomY + 6;
            int hintMaxX = guiLeft + effW - 74; // leave room for back button on the right
            drawCenteredString(fontRenderer,
                    I18n.format("travellers.worker.formation.go_pick_hint") + "  "
                    + TextFormatting.GRAY + I18n.format("travellers.worker.formation.go_pick_cancel"),
                    guiLeft + hintMaxX / 2, hintY, 0xFFFFE060);
        } else if (equipPickingMode) {
            int hintY = bottomY + 6;
            int hintMaxX = guiLeft + effW - 74;
            drawCenteredString(fontRenderer,
                    I18n.format("travellers.worker.formation.equip_pick_hint") + "  "
                    + TextFormatting.GRAY + I18n.format("travellers.worker.formation.go_pick_cancel"),
                    guiLeft + hintMaxX / 2, hintY, 0xFFE060FF);
        } else if (mergeMode) {
            int hintY = bottomY + 6;
            int hintMaxX = guiLeft + effW - 74;
            drawCenteredString(fontRenderer,
                    I18n.format("travellers.worker.formation.merge_hint") + "  "
                    + TextFormatting.GRAY + I18n.format("travellers.worker.formation.merge_cancel"),
                    guiLeft + hintMaxX / 2, hintY, 0xFFFFE060);
        } else if (assignFollowersMode) {
            int hintY = bottomY + 6;
            int hintMaxX = guiLeft + effW - 74;
            drawCenteredString(fontRenderer,
                    I18n.format("travellers.worker.formation.assign_hint") + "  "
                    + TextFormatting.GRAY + I18n.format("travellers.worker.formation.go_pick_cancel"),
                    guiLeft + hintMaxX / 2, hintY, 0xFF60FFB0);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        // Rectangle selection drawn on top of everything, clipped to the map area
        if (mapDragSelecting) {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.disableAlpha();
            int rx1 = Math.max(mapAreaX,          Math.min(mapDragStartX, mapDragCurrentX));
            int ry1 = Math.max(mapAreaY,          Math.min(mapDragStartY, mapDragCurrentY));
            int rx2 = Math.min(mapAreaX + mapW,  Math.max(mapDragStartX, mapDragCurrentX));
            int ry2 = Math.min(mapAreaY + mapAreaH, Math.max(mapDragStartY, mapDragCurrentY));
            if (rx1 < rx2 && ry1 < ry2) {
                drawRect(rx1, ry1, rx2, ry2, 0x3300AAFF);
                GlStateManager.enableAlpha();
                drawHorizontalLine(rx1, rx2, ry1, 0xFF00AAFF);
                drawHorizontalLine(rx1, rx2, ry2, 0xFF00AAFF);
                drawVerticalLine(rx1, ry1, ry2, 0xFF00AAFF);
                drawVerticalLine(rx2, ry1, ry2, 0xFF00AAFF);
            } else {
                GlStateManager.enableAlpha();
            }
            GlStateManager.enableTexture2D();
        }

        // Sliders (always visible, rendered last so they appear on top)
        {
            escortsSlider.draw(mc, mouseX, mouseY);
            followDistSlider.draw(mc, mouseX, mouseY);
            spacingSlider.draw(mc, mouseX, mouseY);
            widthSlider.draw(mc, mouseX, mouseY);
            turnSlider.draw(mc, mouseX, mouseY);
        }

        // Map tooltip
        if (mapController != null && !mapDragSelecting) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
            }
        }
    }

    private void drawDestinationCross() {
        if (destinationCrossPos == null || mapController == null) return;
        long now = System.currentTimeMillis();
        if (now >= destinationCrossExpireMs) { destinationCrossPos = null; return; }

        float cx = mapController.panel.getCenterXf();
        float cz = mapController.panel.getCenterZf();
        float bpp = mapController.panel.getZoomBlocksPerPixel();

        int sx = (int)(mapAreaX + mapW / 2f + (destinationCrossPos.getX() - cx) / bpp);
        int sy = (int)(mapAreaY + mapAreaH / 2f + (destinationCrossPos.getZ() - cz) / bpp);

        // Clamp to map area
        if (sx < mapAreaX || sx >= mapAreaX + mapW || sy < mapAreaY || sy >= mapAreaY + mapAreaH) return;

        float elapsed = (now - (destinationCrossExpireMs - DESTINATION_CROSS_DURATION_MS)) / (float) DESTINATION_CROSS_DURATION_MS;
        float alpha = elapsed < 0.6f ? 1.0f : 1.0f - (elapsed - 0.6f) / 0.4f;

        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, alpha);
        mc.getTextureManager().bindTexture(DESTINATION_CROSS_TEX);
        net.minecraft.client.renderer.BufferBuilder bb = net.minecraft.client.renderer.Tessellator.getInstance().getBuffer();
        bb.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX);
        bb.pos(sx - 4, sy + 4, 0).tex(0, 1).endVertex();
        bb.pos(sx + 4, sy + 4, 0).tex(1, 1).endVertex();
        bb.pos(sx + 4, sy - 4, 0).tex(1, 0).endVertex();
        bb.pos(sx - 4, sy - 4, 0).tex(0, 0).endVertex();
        net.minecraft.client.renderer.Tessellator.getInstance().draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void drawLeaderList(int mouseX, int mouseY) {
        drawRect(listX - 1, listY - 1, listX + LIST_W + 1, listY + listH + 1, COL_PANEL);

        clampScroll();
        int visibleRows = listH / ROW_H;

        for (int i = 0; i < visibleRows; i++) {
            int idx = listScroll + i;
            if (idx >= leaders.size()) break;

            int rowTop = listY + i * ROW_H;
            boolean hovered = mouseX >= listX && mouseX < listX + LIST_W
                    && mouseY >= rowTop && mouseY < rowTop + ROW_H;
            boolean selected = idx == selectedIndex && selectionMode == SelectionMode.LEADER
                    && !mergeMode && !assignFollowersMode;

            FormationLeaderInfo info = leaders.get(idx);
            boolean isMergeTarget    = mergeMode          && idx != selectedIndex && hovered;
            boolean isAssignTarget   = assignFollowersMode && hovered;

            int bg = selected ? COL_SEL : (hovered && !mergeMode && !assignFollowersMode ? COL_HOVER : 0);
            if (bg != 0) drawRect(listX, rowTop, listX + LIST_W, rowTop + ROW_H, bg);
            if (isMergeTarget)  drawRect(listX, rowTop, listX + LIST_W, rowTop + ROW_H, 0x50FFE060);
            if (isAssignTarget) drawRect(listX, rowTop, listX + LIST_W, rowTop + ROW_H, 0x5000FF88);

            // Head icon
            if (!info.isPlayer) {
                GlStateManager.color(1f, 1f, 1f, 1f);
                WorkerHeadRenderer.draw(info.cultureId, info.textureIndex, info.ownerColor,
                        listX + 2, rowTop + 2, 18);
            }

            int textOffX = info.isPlayer ? 3 : 22;
            String name = info.isPlayer ? I18n.format("travellers.worker.formation.you") : info.name;
            if (info.isVirtual) name = TextFormatting.GRAY + name;
            String nameTrimmed = fontRenderer.trimStringToWidth(name, LIST_W - textOffX - 2);
            fontRenderer.drawString(nameTrimmed, listX + textOffX, rowTop + 2, selected ? 0xFFFFCC88 : COL_TEXT);

            double dx = info.posX - player.posX;
            double dz = info.posZ - player.posZ;
            int dist = (int) Math.sqrt(dx * dx + dz * dz);
            String dir = info.isPlayer ? "" : getCardinalDir(dx, dz);
            String sub = info.followerCount + " ♥  " + (info.isPlayer ? "-" : dist + "m " + dir);

            GlStateManager.pushMatrix();
            float sc = 0.8f;
            GlStateManager.translate(listX + textOffX, rowTop + 12, 0f);
            GlStateManager.scale(sc, sc, 1f);
            fontRenderer.drawString(sub, 0, 0, COL_TEXT_DIM);
            GlStateManager.popMatrix();
        }

        if (listScroll > 0)
            fontRenderer.drawString("▲", listX + LIST_W - 10, listY + 1, COL_TEXT_DIM);
        if (listScroll + (listH / ROW_H) < leaders.size())
            fontRenderer.drawString("▼", listX + LIST_W - 10, listY + listH - 9, COL_TEXT_DIM);
    }

    private void drawModeOverlay() {
        int overlayX = ordersX - 3;
        drawRect(overlayX, guiTop, guiLeft + effW, guiTop + effH, 0xC0000000);
        String hint = assignFollowersMode
                ? I18n.format("travellers.worker.formation.assign_hint", "Click a leader to assign followers")
                : I18n.format("travellers.worker.formation.merge_hint");
        drawCenteredString(fontRenderer, hint,
                overlayX + (guiLeft + effW - overlayX) / 2,
                guiTop + effH / 2, 0xFFFFE060);
    }

    // ── Map markers ───────────────────────────────────────────────────────────

    private void rebuildMapMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();
        WorldMapContentPopulator.populate(
                mapController.panel, poiEntries, workerEntries, player, playerOwnerColor, null);

        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        WorldMapContentPopulator.populateForeignUnits(mapController.panel, foreignEntries, myDim);
        for (com.jubitus.travellers.server.map.MsgMapBattleUpdate.Entry e : battleEntries) {
            if (e.dim != myDim) continue;
            mapController.panel.addMarker(WorldMapMarkerFactory.battle(e.pos, e.label));
        }

        // Add highlighted markers on top (same type = drawn last within the pass = above normal ones)
        if (selectionMode == SelectionMode.LEADER && selectedIndex < leaders.size()) {
            FormationLeaderInfo sel = leaders.get(selectedIndex);
            if (!sel.isPlayer && sel.uuid != null) {
                for (MsgMapWorkersUpdate.Entry e : workerEntries) {
                    if (e.isLeader && sel.uuid.equals(e.uuid) && e.pos != null) {
                        String name = (e.name != null && !e.name.isEmpty()) ? e.name : "Worker#" + e.entityId;
                        mapController.panel.addMarker(WorldMapMarkerFactory.leaderHighlighted(e.pos, name));
                        break;
                    }
                }
            }
        } else if (selectionMode == SelectionMode.FOLLOWERS && !mapSelectedFollowers.isEmpty()) {
            Set<UUID> selSet = new HashSet<>(mapSelectedFollowers);
            for (MsgMapWorkersUpdate.Entry e : workerEntries) {
                if (!e.isLeader && e.uuid != null && selSet.contains(e.uuid) && e.pos != null) {
                    String name = (e.name != null && !e.name.isEmpty()) ? e.name : "Worker#" + e.entityId;
                    mapController.panel.addMarker(WorldMapMarkerFactory.followerHighlighted(e.pos, name, e.ownerColor));
                }
            }
        }

        // In equip picking mode, highlight valid destinations on the map
        if (equipPickingMode && equipDests != null) {
            for (MsgEquipDestsResponse.EquipDestInfo d : equipDests) {
                if (d.pos == null) continue;
                if (d.useWholeVillage) {
                    mapController.panel.addMarker(WorldMapMarkerFactory.controlledVillage(d.pos, d.name, playerOwnerColor));
                } else {
                    mapController.panel.addMarker(WorldMapMarkerFactory.home(d.pos, d.name, playerOwnerColor));
                }
            }
        }
    }

    // ── IWorkerGoListReceiver ─────────────────────────────────────────────────

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        poiEntries.clear();
        if (entries != null) poiEntries.addAll(entries);
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
    }

    // ── IBattleMapReceiver / IForeignUnitMapReceiver ─────────────────────────────

    @Override
    public void receiveBattles(List<com.jubitus.travellers.server.map.MsgMapBattleUpdate.Entry> entries) {
        battleEntries.clear();
        if (entries != null) battleEntries.addAll(entries);
    }

    @Override
    public void receiveForeignUnits(List<com.jubitus.travellers.server.map.MsgMapForeignUnitsUpdate.Entry> entries) {
        foreignEntries.clear();
        if (entries != null) foreignEntries.addAll(entries);
    }

    // ── Equip destinations (called by MsgEquipDestsResponse.ClientHandler) ───

    public void onEquipDestsReceived(List<MsgEquipDestsResponse.EquipDestInfo> dests) {
        this.equipDests = dests;
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backButton) {
            flushPendingSync(true);
            if (standalone) {
                mc.displayGuiScreen(null);
            } else {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(representativeEntityId, MsgOpenWorkerSubGui.Type.HUB));
            }
            return;
        }

        if (button == cancelGoButton) {
            goPickingMode = false;
            goPickingLeaderUUID = null;
            equipPickingMode = false;
            equipPickingLeaderUUID = null;
            equipDests = null;
            refreshButtons();
            return;
        }

        if (button == styleButton) {
            if (currentSettings != null) { currentSettings.style = currentSettings.style.next(); onSettingsChanged(true); }
            return;
        }
        if (button == caveButton) {
            if (currentSettings != null) { currentSettings.adaptiveCave = !currentSettings.adaptiveCave; onSettingsChanged(true); }
            return;
        }

        if (button == getEquipButton)         { handleGetEquipButton();        return; }
        if (button == goButton)               { handleGoButton();              return; }
        if (button == comeBackButton)         { handleComeBack();              return; }
        if (button == mergeButton)            { enterMergeMode();              return; }
        if (button == mergeCancelButton)      { exitMergeMode();               return; }
        if (button == groupButton)            { handleGroupButton();           return; }
        if (button == followerComeBackButton) { handleFollowerComeBack();      return; }
        if (button == followerMergeButton)    { enterAssignFollowersMode();    return; }
        if (button == barracksButton)         { handleBarracksButton();        return; }
        if (button == behaviorButton)         { handleBehaviorButton();        return; }

        super.actionPerformed(button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // Escape
            if (goPickingMode)       { goPickingMode = false; goPickingLeaderUUID = null; refreshButtons(); return; }
            if (equipPickingMode)    { equipPickingMode = false; equipPickingLeaderUUID = null; equipDests = null; refreshButtons(); return; }
            if (assignFollowersMode) { exitAssignFollowersMode(); return; }
            if (mergeMode)           { exitMergeMode();           return; }
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
        // Right-click on map → pan
        if (mapController != null && mouseButton == 1 && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.onMouseClick(mouseX, mouseY, mouseButton);
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        // Go / equip picking: handle map click immediately, no drag or selection
        if (mouseButton == 0 && (goPickingMode || equipPickingMode) && mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            handleMapSingleClick(mouseX, mouseY);
            return;
        }

        // Merge mode: left-click on map to pick a target leader
        if (mouseButton == 0 && mergeMode && mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            handleMapMergeClick(mouseX, mouseY);
            return;
        }

        // Left-click on map → start rectangle selection (only when not in a mode overlay)
        if (mouseButton == 0 && mapController != null && mapController.isMouseOver(mouseX, mouseY)
                && !mergeMode && !assignFollowersMode && !goPickingMode && !equipPickingMode) {
            mapDragSelecting = true;
            mapDragStartX = mapDragCurrentX = mouseX;
            mapDragStartY = mapDragCurrentY = mouseY;
            return;
        }

        // Assign followers mode: left-click on map to pick a target leader
        if (mouseButton == 0 && assignFollowersMode && mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            handleMapAssignClick(mouseX, mouseY);
            return;
        }

        // Click on leaders list
        if (mouseButton == 0 && mouseX >= listX && mouseX < listX + LIST_W
                && mouseY >= listY && mouseY < listY + listH) {
            int idx = listScroll + (mouseY - listY) / ROW_H;
            if (idx >= 0 && idx < leaders.size()) {
                if (assignFollowersMode) {
                    if (!leaders.get(idx).isPlayer) performFollowerAssign(idx);
                } else if (mergeMode) {
                    if (idx != selectedIndex) performMerge(idx);
                } else {
                    selectLeaderByIndex(idx);
                }
                return;
            }
        }

        if (!mergeMode && !assignFollowersMode) {
            boolean consumed = false;
            if (escortsSlider.mousePressed(mouseX, mouseY, mouseButton))    consumed = true;
            if (followDistSlider.mousePressed(mouseX, mouseY, mouseButton)) consumed = true;
            if (spacingSlider.mousePressed(mouseX, mouseY, mouseButton))    consumed = true;
            if (widthSlider.mousePressed(mouseX, mouseY, mouseButton))      consumed = true;
            if (turnSlider.mousePressed(mouseX, mouseY, mouseButton))       consumed = true;
            if (consumed) { pullSliders(); onSettingsChanged(false); return; }
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

        if (mapController != null) mapController.onMouseDrag(mouseX, mouseY);

        if (mergeMode || assignFollowersMode) return;
        boolean changed = false;
        changed |= escortsSlider.mouseDragged(mouseX, mouseY);
        changed |= followDistSlider.mouseDragged(mouseX, mouseY);
        changed |= spacingSlider.mouseDragged(mouseX, mouseY);
        changed |= widthSlider.mouseDragged(mouseX, mouseY);
        changed |= turnSlider.mouseDragged(mouseX, mouseY);
        if (changed) { pullSliders(); onSettingsChanged(false); }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);

        if (mapDragSelecting && state == 0) {
            mapDragSelecting = false;
            int dragDist = Math.abs(mapDragCurrentX - mapDragStartX) + Math.abs(mapDragCurrentY - mapDragStartY);
            if (dragDist < 5) {
                handleMapSingleClick(mapDragCurrentX, mapDragCurrentY);
            } else {
                computeMapRectangleSelection();
            }
            return;
        }

        if (mapController != null) mapController.onMouseRelease(state);

        if (mergeMode || assignFollowersMode) return;
        boolean changed = false;
        changed |= escortsSlider.mouseReleased(state);
        changed |= followDistSlider.mouseReleased(state);
        changed |= spacingSlider.mouseReleased(state);
        changed |= widthSlider.mouseReleased(state);
        changed |= turnSlider.mouseReleased(state);
        if (changed) { pullSliders(); onSettingsChanged(false); flushPendingSync(true); }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = org.lwjgl.input.Mouse.getEventX() * width  / mc.displayWidth;
        int mouseY = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;

        if (mouseX >= listX && mouseX < listX + LIST_W && mouseY >= listY && mouseY < listY + listH) {
            listScroll += wheel > 0 ? -1 : 1;
            clampScroll();
        } else if (mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        }
    }

    // ── Map selection ─────────────────────────────────────────────────────────

    private void handleMapSingleClick(int mouseX, int mouseY) {
        if (mapController == null) return;

        if (goPickingMode) {
            BlockPos dest = mapController.screenToWorld(mouseX, mouseY);
            TravellerFollowNet.CHANNEL.sendToServer(new MsgLeaderGoTo(goPickingLeaderUUID, dest));
            destinationCrossPos = dest;
            destinationCrossExpireMs = System.currentTimeMillis() + DESTINATION_CROSS_DURATION_MS;
            goPickingMode = false;
            goPickingLeaderUUID = null;
            refreshButtons();
            return;
        }

        if (equipPickingMode) {
            if (equipDests != null) {
                MsgEquipDestsResponse.EquipDestInfo nearest = findNearestEquipDest(mouseX, mouseY);
                if (nearest != null) {
                    TravellerFollowNet.CHANNEL.sendToServer(new MsgLeaderGetEquip(equipPickingLeaderUUID, nearest.pos, nearest.useWholeVillage));
                    destinationCrossPos = nearest.pos;
                    destinationCrossExpireMs = System.currentTimeMillis() + DESTINATION_CROSS_DURATION_MS;
                }
            }
            equipPickingMode = false;
            equipPickingLeaderUUID = null;
            equipDests = null;
            refreshButtons();
            return;
        }

        boolean shift = isShiftKeyDown();

        // Try to match a worker entry by proximity first (more reliable than tooltip)
        float bpp = mapController.panel.getZoomBlocksPerPixel();
        float cx = mapController.panel.getCenterXf();
        float cz = mapController.panel.getCenterZf();
        float threshold = Math.max(6f, bpp * 6);

        MsgMapWorkersUpdate.Entry closest = null;
        float closestDist = Float.MAX_VALUE;
        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (e.pos == null || e.uuid == null) continue;
            float sx = mapAreaX + mapW / 2f + (e.pos.getX() - cx) / bpp;
            float sy = mapAreaY + mapAreaH / 2f + (e.pos.getZ() - cz) / bpp;
            float dx = mouseX - sx, dy = mouseY - sy;
            float dist = dx * dx + dy * dy;
            if (dist < threshold * threshold && dist < closestDist) {
                closestDist = dist;
                closest = e;
            }
        }

        if (closest != null) {
            if (shift) {
                // Shift + click: only add followers, ignore leaders
                if (!closest.isLeader) {
                    Set<UUID> combined = new HashSet<>(mapSelectedFollowers);
                    combined.add(closest.uuid);
                    mapSelectedFollowers = new ArrayList<>(combined);
                    selectionMode = SelectionMode.FOLLOWERS;
                    refreshButtons();
                }
                // Shift + click on leader → ignore, keep current selection
            } else {
                if (closest.isLeader) {
                    selectLeaderByUUID(closest.uuid);
                } else {
                    // Single follower clicked without shift → select just that one
                    mapSelectedFollowers = new ArrayList<>(Collections.singletonList(closest.uuid));
                    selectionMode = SelectionMode.FOLLOWERS;
                    refreshButtons();
                }
            }
            return;
        }

        if (shift) {
            // Shift + click on empty space → keep current selection
            return;
        }

        // Nothing found — cancel selection
        selectionMode = SelectionMode.NONE;
        mapSelectedFollowers = Collections.emptyList();
        refreshButtons();
    }

    private void handleMapMergeClick(int mouseX, int mouseY) {
        if (mapController == null) return;
        float bpp = mapController.panel.getZoomBlocksPerPixel();
        float cx = mapController.panel.getCenterXf();
        float cz = mapController.panel.getCenterZf();
        float threshold = Math.max(6f, bpp * 6);

        MsgMapWorkersUpdate.Entry closest = null;
        float closestDist = Float.MAX_VALUE;
        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (!e.isLeader || e.pos == null || e.uuid == null) continue;
            float sx = mapAreaX + mapW / 2f + (e.pos.getX() - cx) / bpp;
            float sy = mapAreaY + mapAreaH / 2f + (e.pos.getZ() - cz) / bpp;
            float dx = mouseX - sx, dy = mouseY - sy;
            float dist = dx * dx + dy * dy;
            if (dist < threshold * threshold && dist < closestDist) {
                closestDist = dist;
                closest = e;
            }
        }

        if (closest != null && closest.uuid != null) {
            for (int i = 0; i < leaders.size(); i++) {
                if (closest.uuid.equals(leaders.get(i).uuid) && i != selectedIndex) {
                    performMerge(i);
                    return;
                }
            }
        }
    }

    private void handleMapAssignClick(int mouseX, int mouseY) {
        if (mapController == null) return;
        float bpp = mapController.panel.getZoomBlocksPerPixel();
        float cx = mapController.panel.getCenterXf();
        float cz = mapController.panel.getCenterZf();
        float threshold = Math.max(6f, bpp * 6);

        MsgMapWorkersUpdate.Entry closest = null;
        float closestDist = Float.MAX_VALUE;
        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (!e.isLeader || e.pos == null || e.uuid == null) continue;
            float sx = mapAreaX + mapW / 2f + (e.pos.getX() - cx) / bpp;
            float sy = mapAreaY + mapAreaH / 2f + (e.pos.getZ() - cz) / bpp;
            float dx = mouseX - sx, dy = mouseY - sy;
            float dist = dx * dx + dy * dy;
            if (dist < threshold * threshold && dist < closestDist) {
                closestDist = dist;
                closest = e;
            }
        }

        if (closest != null && closest.uuid != null) {
            for (int i = 0; i < leaders.size(); i++) {
                if (closest.uuid.equals(leaders.get(i).uuid)) {
                    performFollowerAssign(i);
                    return;
                }
            }
        }
    }

    private void computeMapRectangleSelection() {
        if (mapController == null) return;

        float bpp = mapController.panel.getZoomBlocksPerPixel();
        float cx = mapController.panel.getCenterXf();
        float cz = mapController.panel.getCenterZf();

        float wx1 = cx + (mapDragStartX   - mapAreaX - mapW   / 2f) * bpp;
        float wz1 = cz + (mapDragStartY   - mapAreaY - mapAreaH / 2f) * bpp;
        float wx2 = cx + (mapDragCurrentX - mapAreaX - mapW   / 2f) * bpp;
        float wz2 = cz + (mapDragCurrentY - mapAreaY - mapAreaH / 2f) * bpp;

        // Expand bounds by half the marker visual size so workers whose marker overlaps the rectangle are included.
        float marginBlocks = Math.max(6f, bpp * 6) * bpp;
        float minX = Math.min(wx1, wx2) - marginBlocks;
        float maxX = Math.max(wx1, wx2) + marginBlocks;
        float minZ = Math.min(wz1, wz2) - marginBlocks;
        float maxZ = Math.max(wz1, wz2) + marginBlocks;

        boolean shift = isShiftKeyDown();
        List<UUID> leaderUUIDs   = new ArrayList<>();
        List<UUID> followerUUIDs = new ArrayList<>();

        for (MsgMapWorkersUpdate.Entry e : workerEntries) {
            if (e.pos == null || e.uuid == null) continue;
            float ex = e.pos.getX();
            float ez = e.pos.getZ();
            if (ex >= minX && ex <= maxX && ez >= minZ && ez <= maxZ) {
                if (e.isLeader) leaderUUIDs.add(e.uuid);
                else followerUUIDs.add(e.uuid);
            }
        }

        if (shift) {
            // Shift: only add followers to existing selection, ignore leaders
            if (!followerUUIDs.isEmpty()) {
                Set<UUID> combined = new HashSet<>(mapSelectedFollowers);
                combined.addAll(followerUUIDs);
                mapSelectedFollowers = new ArrayList<>(combined);
                selectionMode = SelectionMode.FOLLOWERS;
                refreshButtons();
            }
            // Nothing found or only leaders → keep current selection unchanged
            return;
        }

        if (leaderUUIDs.isEmpty() && followerUUIDs.isEmpty()) {
            selectionMode = SelectionMode.NONE;
            mapSelectedFollowers = Collections.emptyList();
            refreshButtons();
            return;
        }

        if (!leaderUUIDs.isEmpty()) {
            // Leader priority: select first leader in list matching any selected UUID
            UUID firstLeaderUUID = leaderUUIDs.get(0);
            selectLeaderByUUID(firstLeaderUUID);
        } else {
            selectionMode = SelectionMode.FOLLOWERS;
            mapSelectedFollowers = followerUUIDs;
            refreshButtons();
        }
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private void selectLeaderByIndex(int idx) {
        if (idx < 0 || idx >= leaders.size()) return;
        if (goPickingMode) { goPickingMode = false; goPickingLeaderUUID = null; }
        if (equipPickingMode) { equipPickingMode = false; equipPickingLeaderUUID = null; equipDests = null; }
        selectedIndex = idx;
        selectionMode = SelectionMode.LEADER;
        mapSelectedFollowers = Collections.emptyList();
        applySelection();
        refreshButtons();
    }

    private void selectLeaderByUUID(UUID uuid) {
        for (int i = 0; i < leaders.size(); i++) {
            if (uuid.equals(leaders.get(i).uuid)) {
                selectLeaderByIndex(i);
                return;
            }
        }
    }

    private void applySelection() {
        if (leaders.isEmpty()) {
            currentSettings = WorkerFormationSettings.defaults();
            selectedScope = null;
            return;
        }
        int idx = Math.min(selectedIndex, leaders.size() - 1);
        FormationLeaderInfo info = leaders.get(idx);
        selectedScope = scopeOf(info);
        currentSettings = getSettingsForScope(selectedScope);
        pushSettingsToSliders();

        if (mapController != null) {
            mapController.setCenter(findCurrentLeaderPos(info));
        }
    }

    private BlockPos findCurrentLeaderPos(FormationLeaderInfo info) {
        if (!info.isPlayer && info.name != null && !info.name.isEmpty()) {
            for (MsgMapWorkersUpdate.Entry e : workerEntries) {
                if (e.isLeader && info.name.equals(e.name) && e.pos != null) return e.pos;
            }
        }
        return new BlockPos((int) info.posX, 64, (int) info.posZ);
    }

    private BlockPos getSelectedLeaderPos() {
        if (!leaders.isEmpty()) {
            FormationLeaderInfo info = leaders.get(Math.min(selectedIndex, leaders.size() - 1));
            return new BlockPos((int) info.posX, 64, (int) info.posZ);
        }
        return player.getPosition();
    }

    private void pushSettingsToSliders() {
        if (currentSettings == null) return;
        if (escortsSlider    != null) escortsSlider.setValue(currentSettings.numEscorts);
        if (followDistSlider != null) followDistSlider.setValue(currentSettings.followDistance);
        if (spacingSlider    != null) spacingSlider.setValue(currentSettings.spacing);
        if (widthSlider      != null) widthSlider.setValue(currentSettings.width);
        if (turnSlider       != null) turnSlider.setValue(currentSettings.turnSmoothness);
    }

    // ── Merge logic ───────────────────────────────────────────────────────────

    private void enterMergeMode() {
        if (selectedIndex >= leaders.size()) return;
        if (leaders.get(selectedIndex).isPlayer) return;
        mergeMode = true;
        refreshButtons();
    }

    private void exitMergeMode() {
        mergeMode = false;
        refreshButtons();
    }

    private void performMerge(int targetIdx) {
        if (targetIdx < 0 || targetIdx >= leaders.size()) { exitMergeMode(); return; }
        if (selectedIndex >= leaders.size()) { exitMergeMode(); return; }

        FormationLeaderInfo source = leaders.get(selectedIndex);
        FormationLeaderInfo target = leaders.get(targetIdx);
        if (source.isPlayer) { exitMergeMode(); return; }

        UUID targetUUID = target.isPlayer ? null : target.uuid;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupMerge(source.uuid, targetUUID));

        int srcCount = source.followerCount + 1;
        leaders.remove(selectedIndex);
        if (targetIdx > selectedIndex) targetIdx--;
        if (targetIdx >= 0 && targetIdx < leaders.size()) {
            leaders.get(targetIdx).followerCount += srcCount;
            selectedIndex = targetIdx;
            applySelection();
        } else if (!leaders.isEmpty()) {
            selectedIndex = 0;
            applySelection();
        }

        exitMergeMode();
        refreshButtons();
    }

    // ── Assign followers to leader ────────────────────────────────────────────

    private void enterAssignFollowersMode() {
        if (mapSelectedFollowers.isEmpty()) return;
        assignFollowersMode = true;
        refreshButtons();
    }

    private void exitAssignFollowersMode() {
        assignFollowersMode = false;
        refreshButtons();
    }

    private void performFollowerAssign(int targetIdx) {
        if (targetIdx < 0 || targetIdx >= leaders.size()) { exitAssignFollowersMode(); return; }
        FormationLeaderInfo target = leaders.get(targetIdx);
        if (target.isPlayer) { exitAssignFollowersMode(); return; }

        TravellerFollowNet.CHANNEL.sendToServer(
                new MsgGroupCreate(MsgGroupCreate.Action.ASSIGN_TO_LEADER, mapSelectedFollowers, target.uuid));

        // Optimistic update: add follower count to that leader
        target.followerCount += mapSelectedFollowers.size();

        mapSelectedFollowers = Collections.emptyList();
        exitAssignFollowersMode();
        selectionMode = SelectionMode.LEADER;
        selectedIndex = targetIdx;
        applySelection();
        refreshButtons();

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestFormationHub());
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void handleGetEquipButton() {
        if (selectionMode != SelectionMode.LEADER || selectedIndex >= leaders.size()) return;
        FormationLeaderInfo sel = leaders.get(selectedIndex);
        if (sel.isPlayer) return;
        equipPickingMode = true;
        equipPickingLeaderUUID = sel.uuid;
        equipDests = null;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestEquipDests());
        refreshButtons();
    }

    private MsgEquipDestsResponse.EquipDestInfo findNearestEquipDest(int mouseX, int mouseY) {
        if (equipDests == null || mapController == null) return null;
        float bpp = mapController.panel.getZoomBlocksPerPixel();
        float cx = mapController.panel.getCenterXf();
        float cz = mapController.panel.getCenterZf();
        float threshSq = 10f * 10f;
        MsgEquipDestsResponse.EquipDestInfo nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (MsgEquipDestsResponse.EquipDestInfo d : equipDests) {
            if (d.pos == null) continue;
            float sx = mapAreaX + mapW / 2f + (d.pos.getX() - cx) / bpp;
            float sy = mapAreaY + mapAreaH / 2f + (d.pos.getZ() - cz) / bpp;
            float dx = mouseX - sx;
            float dy = mouseY - sy;
            float distSq = dx * dx + dy * dy;
            if (distSq <= threshSq && distSq < nearestDist) {
                nearestDist = distSq;
                nearest = d;
            }
        }
        return nearest;
    }

    private void handleGoButton() {
        if (selectionMode != SelectionMode.LEADER || selectedIndex >= leaders.size()) return;
        FormationLeaderInfo sel = leaders.get(selectedIndex);
        if (sel.isPlayer) return;
        goPickingMode = true;
        goPickingLeaderUUID = sel.uuid;
        refreshButtons();
    }

    private void handleComeBack() {
        if (selectionMode != SelectionMode.LEADER || selectedIndex >= leaders.size()) return;
        FormationLeaderInfo sel = leaders.get(selectedIndex);
        if (sel.isPlayer) return;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupComeBack(sel.uuid));

        int count = sel.followerCount + 1;
        leaders.remove(selectedIndex);
        if (!leaders.isEmpty()) {
            for (int i = 0; i < leaders.size(); i++) {
                if (leaders.get(i).isPlayer) {
                    leaders.get(i).followerCount += count;
                    selectedIndex = i;
                    break;
                }
            }
            if (selectedIndex >= leaders.size()) selectedIndex = leaders.size() - 1;
            applySelection();
        }
        refreshButtons();
    }

    private void handleGroupButton() {
        if (selectionMode != SelectionMode.FOLLOWERS || mapSelectedFollowers.isEmpty()) return;
        // Save UUIDs before clearing — used to auto-select the new leader when updateLeaders() arrives
        pendingSelectFromFollowers = new HashSet<>(mapSelectedFollowers);
        TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupCreate(MsgGroupCreate.Action.CREATE_GROUP, mapSelectedFollowers, null));
        mapSelectedFollowers = Collections.emptyList();
        selectionMode = SelectionMode.NONE;
        refreshButtons();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestFormationHub());
    }

    private void handleFollowerComeBack() {
        if (selectionMode != SelectionMode.FOLLOWERS || mapSelectedFollowers.isEmpty()) return;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupCreate(MsgGroupCreate.Action.COME_BACK, mapSelectedFollowers, null));
        mapSelectedFollowers = Collections.emptyList();
        selectionMode = SelectionMode.NONE;
        refreshButtons();
    }

    private void handleBarracksButton() {
        mc.displayGuiScreen(new GuiWorkerBarracks(player, this));
    }

    private void handleBehaviorButton() {
        if (selectionMode != SelectionMode.LEADER || selectedIndex >= leaders.size()) return;
        FormationLeaderInfo sel = leaders.get(selectedIndex);
        if (sel.isPlayer || sel.entityId < 0) return;
        Entity e = mc.world.getEntityByID(sel.entityId);
        if (!(e instanceof EntityTravellerWorker)) return;
        mc.displayGuiScreen(new GuiWorkerBehavior(player, (EntityTravellerWorker) e, this));
    }

    // ── Settings sync ─────────────────────────────────────────────────────────

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
        TravellerFollowNet.CHANNEL.sendToServer(
                new MsgUpdateFormationByScope(selectedScope, currentSettings.copy().clamp()));
    }

    private void pullSliders() {
        if (currentSettings == null) return;
        currentSettings.numEscorts     = (int) Math.round(escortsSlider.getValue());
        currentSettings.followDistance = followDistSlider.getValue();
        currentSettings.spacing        = spacingSlider.getValue();
        currentSettings.width          = (int) Math.round(widthSlider.getValue());
        currentSettings.turnSmoothness = (int) Math.round(turnSlider.getValue());
        currentSettings.clamp();
    }

    // ── Button refresh ────────────────────────────────────────────────────────

    private void refreshButtons() {
        boolean noSel = selectedIndex >= leaders.size();
        FormationLeaderInfo sel = noSel ? null : leaders.get(selectedIndex);
        boolean isPlayer = sel != null && sel.isPlayer;
        boolean isLeaderMode   = selectionMode == SelectionMode.LEADER   && !mergeMode && !assignFollowersMode;
        boolean isFollowerMode = selectionMode == SelectionMode.FOLLOWERS && !mergeMode && !assignFollowersMode;

        // Formation sliders & style buttons
        if (styleButton != null) { styleButton.visible = !mergeMode && !assignFollowersMode; }
        if (caveButton  != null) { caveButton.visible  = !mergeMode && !assignFollowersMode; }

        // Leader orders
        boolean showLeaderOrders = isLeaderMode;
        boolean anyPickingMode = goPickingMode || equipPickingMode;
        if (goButton       != null) { goButton.visible = showLeaderOrders; goButton.enabled = showLeaderOrders && !isPlayer && !anyPickingMode; }
        if (comeBackButton != null) { comeBackButton.visible = showLeaderOrders; comeBackButton.enabled = showLeaderOrders && !isPlayer && !anyPickingMode; }
        if (mergeButton    != null) { mergeButton.visible = showLeaderOrders; mergeButton.enabled = showLeaderOrders && !isPlayer && !anyPickingMode && leaders.size() > 1; }
        if (getEquipButton != null) { getEquipButton.visible = showLeaderOrders && !isPlayer && !anyPickingMode; getEquipButton.enabled = showLeaderOrders && !isPlayer && !anyPickingMode; }

        // Follower orders
        boolean hasFollowers = !mapSelectedFollowers.isEmpty();
        boolean moreThanOneLeader = leaders.stream().filter(l -> !l.isPlayer).count() > 0;
        if (groupButton             != null) { groupButton.visible = isFollowerMode; groupButton.enabled = isFollowerMode && mapSelectedFollowers.size() >= 2; }
        if (followerComeBackButton  != null) { followerComeBackButton.visible = isFollowerMode; followerComeBackButton.enabled = isFollowerMode && hasFollowers; }
        if (followerMergeButton     != null) { followerMergeButton.visible = isFollowerMode; followerMergeButton.enabled = isFollowerMode && hasFollowers && moreThanOneLeader; }

        // Merge / go-cancel
        if (mergeCancelButton != null) mergeCancelButton.visible = mergeMode;
        if (cancelGoButton    != null) cancelGoButton.visible = anyPickingMode && !mergeMode && !assignFollowersMode;
        if (backButton        != null) backButton.visible = !mergeMode && !assignFollowersMode;

        // General buttons
        if (barracksButton != null) { barracksButton.visible = !mergeMode && !assignFollowersMode; barracksButton.enabled = !anyPickingMode; }
        if (behaviorButton != null) {
            behaviorButton.visible = !mergeMode && !assignFollowersMode;
            behaviorButton.enabled = !anyPickingMode && isLeaderMode && sel != null && !isPlayer && sel.entityId >= 0;
        }

        // Update style/cave labels
        if (!mergeMode && !assignFollowersMode && currentSettings != null) {
            if (styleButton != null)
                styleButton.displayString = I18n.format("travellers.worker.formation.style") + ": "
                        + I18n.format("travellers.worker.formation.style."
                        + currentSettings.style.name().toLowerCase(Locale.ROOT));
            if (caveButton != null)
                caveButton.displayString = currentSettings.adaptiveCave
                        ? I18n.format("travellers.worker.formation.cave.on")
                        : I18n.format("travellers.worker.formation.cave.off");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkerFormationSettings getSettingsForScope(WorkerFormationScopeKey scope) {
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
        int vis = listH / ROW_H;
        int max = Math.max(0, leaders.size() - vis);
        if (listScroll < 0) listScroll = 0;
        if (listScroll > max) listScroll = max;
        if (selectedIndex < listScroll) listScroll = selectedIndex;
        if (selectedIndex >= listScroll + vis) listScroll = selectedIndex - vis + 1;
        if (listScroll < 0) listScroll = 0;
    }

    private String getCardinalDir(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        if (angle < 0) angle += 360;
        int sect = (int) ((angle + 22.5) / 45) % 8;
        String[] dirs = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        return dirs[sect];
    }

    private String formatDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001D) return Integer.toString((int) Math.rint(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
