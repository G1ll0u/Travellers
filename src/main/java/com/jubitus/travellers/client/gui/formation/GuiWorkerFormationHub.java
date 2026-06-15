package com.jubitus.travellers.client.gui.formation;

import com.jubitus.travellers.client.gui.go.GuiWorkerGo;
import com.jubitus.travellers.client.gui.home.GuiWorkerHomeList;
import com.jubitus.travellers.client.gui.worldmap.IWorkerGoListReceiver;
import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.worldmap.WorldMapContentPopulator;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.formations.FormationLeaderInfo;
import com.jubitus.travellers.server.net.message.formations.MsgUpdateFormationByScope;
import com.jubitus.travellers.server.net.message.group.MsgGroupComeBack;
import com.jubitus.travellers.server.net.message.group.MsgGroupMerge;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerRequestGoList;
import com.jubitus.travellers.client.input.FormationHubKeyHandler;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class GuiWorkerFormationHub extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapReceiver {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int X_SIZE  = 660;
    private static final int Y_SIZE  = 290;
    private static final int MAP_W   = 150;
    private static final int LIST_W  = 130;
    private static final int ROW_H   = 20;

    private static final int COL_BG       = TravellersGuiTheme.COL_BG;
    private static final int COL_BORDER   = TravellersGuiTheme.COL_BORDER;
    private static final int COL_TEXT     = TravellersGuiTheme.COL_TEXT;
    private static final int COL_TEXT_DIM = TravellersGuiTheme.COL_TEXT_DIM;
    private static final int COL_SEL      = TravellersGuiTheme.COL_ROW_SEL;
    private static final int COL_HOVER    = TravellersGuiTheme.COL_ROW_HOVER;
    private static final int COL_PANEL    = TravellersGuiTheme.COL_PANEL;

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
    private int mapAreaX, mapAreaY, mapAreaH;
    private int listX, listY, listH;
    private int formX;
    private int previewX, previewY, previewW, previewH;
    private int rightX, rightW;
    private int bottomY;

    // ── State ─────────────────────────────────────────────────────────────────
    private int listScroll = 0;
    private boolean formationTab = true;
    private boolean mergeMode = false;

    // ── Map data ──────────────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final List<MsgWorkerGoList.Entry> poiEntries = new ArrayList<>();
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

    // ── Widgets ───────────────────────────────────────────────────────────────
    private GuiFormationPreviewPanel previewPanel;
    private GuiWorkerFormationSlider escortsSlider;
    private GuiWorkerFormationSlider followDistSlider;
    private GuiWorkerFormationSlider spacingSlider;
    private GuiWorkerFormationSlider widthSlider;
    private GuiWorkerFormationSlider turnSlider;

    private GuiButton styleButton;
    private GuiButton caveButton;
    private GuiButton tabFormBtn;
    private GuiButton tabOrderBtn;
    private GuiButton zoomInBtn, zoomOutBtn, recenterBtn;
    private GuiButton backButton;
    private GuiButton goButton;
    private GuiButton comeBackButton;
    private GuiButton mergeButton;
    private GuiButton mergeCancelButton;
    private GuiButton homeButton;

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

        // Map area (leftmost)
        mapAreaX = guiLeft + 6;
        mapAreaY = guiTop + 22;
        mapAreaH = effH - 44;

        // Leader list
        listX = mapAreaX + MAP_W + 8;
        listY = guiTop + 22;
        listH = effH - 44;

        // Form area
        formX = listX + LIST_W + 8;

        int tabY  = guiTop + 18;
        int tabW  = 96;
        int tabH  = 20;
        tabFormBtn  = addButton(new GuiButton(20, formX, tabY, tabW, tabH, I18n.format("travellers.worker.formation.title")));
        tabOrderBtn = addButton(new GuiButton(21, formX + tabW + 4, tabY, tabW, tabH, I18n.format("travellers.worker.formation.orders_tab")));

        int contentY = tabY + tabH + 4;

        previewX = formX + 4;
        previewY = contentY;
        previewW = 198;
        previewH = effH - (contentY - guiTop) - 28;

        int fc = currentSettings != null ? getFollowerCount() : 0;
        previewPanel = new GuiFormationPreviewPanel(Minecraft.getMinecraft(), previewW, previewH);
        previewPanel.setData(currentSettings != null ? currentSettings : WorkerFormationSettings.defaults(), fc);

        rightX = previewX + previewW + 8;
        rightW = guiLeft + effW - 8 - rightX;
        if (rightW < 100) rightW = 100;

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

        bottomY = guiTop + effH - 24;

        zoomOutBtn  = addButton(new GuiButton(10, previewX,      bottomY, 20, 20, "-"));
        zoomInBtn   = addButton(new GuiButton(11, previewX + 24, bottomY, 20, 20, "+"));
        recenterBtn = addButton(new GuiButton(12, previewX + 50, bottomY, 60, 20,
                I18n.format("travellers.worker.formation.preview.reset")));

        backButton = addButton(new GuiButton(99, guiLeft + effW - 70, bottomY, 60, 20,
                I18n.format(standalone ? "travellers.gui.close" : "travellers.gui.back")));

        int obY = contentY;
        int obW = rightW + previewW + 8;
        int obX = previewX;
        goButton       = addButton(new GuiButton(30, obX, obY,        obW, 20, I18n.format("travellers.worker.formation.go")));
        comeBackButton = addButton(new GuiButton(31, obX, obY + 24,   obW, 20, I18n.format("travellers.worker.formation.come_back")));
        mergeButton    = addButton(new GuiButton(32, obX, obY + 48,   obW, 20, I18n.format("travellers.worker.formation.merge")));
        homeButton     = addButton(new GuiButton(34, obX, obY + 72,   obW, 20, I18n.format("travellers.worker.formation.set_home", "Set Home")));
        mergeCancelButton = addButton(new GuiButton(33, obX, obY + 96, obW, 20, I18n.format("travellers.worker.formation.merge_cancel")));

        // Map
        BlockPos initialCenter = getSelectedLeaderPos();
        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapAreaX, mapAreaY, MAP_W, mapAreaH,
                "travellers_formation_hub_map", initialCenter);

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(
                representativeEntityId >= 0 ? representativeEntityId : -1));

        refreshButtons();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        flushPendingSync(true);
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
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

        // Map
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        if (mapController != null) {
            drawRect(mapAreaX - 1, mapAreaY - 1, mapAreaX + MAP_W + 1, mapAreaY + mapAreaH + 1, COL_PANEL);
            rebuildMapMarkers();
            mapController.draw();
        }

        // Separator: map | list
        drawRect(listX - 4, guiTop + 2, listX - 3, guiTop + effH - 2, COL_BORDER);

        drawLeaderList(mouseX, mouseY);

        // Separator: list | form
        drawRect(listX + LIST_W + 3, guiTop + 2, listX + LIST_W + 4, guiTop + effH - 2, COL_BORDER);

        if (formationTab) {
            drawFormationContent(mouseX, mouseY, partialTicks);
        } else {
            drawOrdersContent(mouseX, mouseY);
        }

        if (mergeMode) drawMergeOverlay(mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (formationTab && !mergeMode) {
            escortsSlider.draw(mc, mouseX, mouseY);
            followDistSlider.draw(mc, mouseX, mouseY);
            spacingSlider.draw(mc, mouseX, mouseY);
            widthSlider.draw(mc, mouseX, mouseY);
            turnSlider.draw(mc, mouseX, mouseY);
        }

        // Highlight selected tab
        GuiButton activeTab = formationTab ? tabFormBtn : tabOrderBtn;
        if (activeTab != null && !mergeMode)
            drawRect(activeTab.x, activeTab.y, activeTab.x + activeTab.width, activeTab.y + activeTab.height, 0x60FFFFFF);

        if (formationTab && !mergeMode) {
            List<String> hover = previewPanel.getHoverLines(mouseX, mouseY, previewX, previewY);
            if (!hover.isEmpty()) {
                drawHoveringText(hover, mouseX, mouseY);
                return;
            }
        }

        if (mapController != null && !mergeMode) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
            }
        }
    }

    private void drawLeaderList(int mouseX, int mouseY) {
        drawRect(listX - 1, listY - 1, listX + LIST_W + 1, listY + listH + 1, COL_PANEL);

        clampScroll();
        int visibleRows = listH / ROW_H;

        for (int i = 0; i < visibleRows; i++) {
            int idx = listScroll + i;
            if (idx >= leaders.size()) break;

            int rowTop = listY + i * ROW_H;
            boolean hovered = !mergeMode && mouseX >= listX && mouseX < listX + LIST_W
                    && mouseY >= rowTop && mouseY < rowTop + ROW_H;
            boolean selected = idx == selectedIndex && !mergeMode;

            FormationLeaderInfo info = leaders.get(idx);
            boolean mergeTarget = mergeMode && idx != selectedIndex;

            int bg = selected ? COL_SEL : (hovered ? COL_HOVER : 0);
            if (bg != 0) drawRect(listX, rowTop, listX + LIST_W, rowTop + ROW_H, bg);
            if (mergeTarget && mouseX >= listX && mouseX < listX + LIST_W
                    && mouseY >= rowTop && mouseY < rowTop + ROW_H) {
                drawRect(listX, rowTop, listX + LIST_W, rowTop + ROW_H, 0x50FFE060);
            }

            String name = info.isPlayer ? I18n.format("travellers.worker.formation.you") : info.name;
            if (info.isVirtual) name = TextFormatting.GRAY + name;
            String nameTrimmed = fontRenderer.trimStringToWidth(name, LIST_W - 4);
            fontRenderer.drawString(nameTrimmed, listX + 3, rowTop + 2, selected ? 0xFFFFCC88 : COL_TEXT);

            double dx = info.posX - player.posX;
            double dz = info.posZ - player.posZ;
            int dist = (int) Math.sqrt(dx * dx + dz * dz);
            String dir = info.isPlayer ? "" : getCardinalDir(dx, dz);
            String sub = info.followerCount + " ♥  " + (info.isPlayer ? "-" : dist + "m " + dir);

            GlStateManager.pushMatrix();
            float sc = 0.8f;
            GlStateManager.translate(listX + 3, rowTop + 11, 0f);
            GlStateManager.scale(sc, sc, 1f);
            fontRenderer.drawString(sub, 0, 0, COL_TEXT_DIM);
            GlStateManager.popMatrix();
        }

        if (listScroll > 0)
            fontRenderer.drawString("▲", listX + LIST_W - 10, listY + 1, COL_TEXT_DIM);
        if (listScroll + (listH / ROW_H) < leaders.size())
            fontRenderer.drawString("▼", listX + LIST_W - 10, listY + listH - 9, COL_TEXT_DIM);
    }

    private void drawFormationContent(int mouseX, int mouseY, float partialTicks) {
        previewPanel.draw(previewX, previewY, mouseX, mouseY);

        if (selectedIndex < leaders.size()) {
            FormationLeaderInfo sel = leaders.get(selectedIndex);
            String lbl = sel.isPlayer ? I18n.format("travellers.worker.formation.you") : sel.name;
            String header = I18n.format("travellers.worker.formation.followers") + ": " + sel.followerCount;
            fontRenderer.drawString(lbl, rightX, guiTop + 6, COL_TEXT);
            fontRenderer.drawString(header, rightX, guiTop + 6 + 9, COL_TEXT_DIM);
        }
    }

    private void drawOrdersContent(int mouseX, int mouseY) {
        if (selectedIndex >= leaders.size()) return;
        FormationLeaderInfo sel = leaders.get(selectedIndex);
        String lbl = sel.isPlayer ? I18n.format("travellers.worker.formation.you") : sel.name;
        drawCenteredString(fontRenderer,
                TextFormatting.BOLD + lbl,
                formX + (guiLeft + effW - 6 - formX) / 2,
                guiTop + 42, COL_TEXT);
    }

    private void drawMergeOverlay(int mouseX, int mouseY) {
        int overlayX = listX + LIST_W + 3;
        drawRect(overlayX, guiTop, guiLeft + effW, guiTop + effH, 0xC0000000);
        String hint = I18n.format("travellers.worker.formation.merge_hint");
        drawCenteredString(fontRenderer, hint,
                overlayX + (guiLeft + effW - overlayX) / 2,
                guiTop + effH / 2, 0xFFFFE060);
    }

    // ── Map markers ───────────────────────────────────────────────────────────

    private void rebuildMapMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();
        WorldMapContentPopulator.populate(
                mapController.panel, poiEntries, workerEntries, player, playerOwnerColor);
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

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == tabFormBtn)  { formationTab = true;  refreshButtons(); return; }
        if (button == tabOrderBtn) { formationTab = false; refreshButtons(); return; }

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

        if (button == styleButton) {
            if (currentSettings != null) { currentSettings.style = currentSettings.style.next(); onSettingsChanged(true); }
            return;
        }
        if (button == caveButton) {
            if (currentSettings != null) { currentSettings.adaptiveCave = !currentSettings.adaptiveCave; onSettingsChanged(true); }
            return;
        }
        if (button == zoomInBtn)   { previewPanel.zoomIn();    return; }
        if (button == zoomOutBtn)  { previewPanel.zoomOut();   return; }
        if (button == recenterBtn) { previewPanel.resetView(); return; }

        if (button == goButton)          { handleGoButton();   return; }
        if (button == comeBackButton)    { handleComeBack();   return; }
        if (button == mergeButton)       { enterMergeMode();   return; }
        if (button == homeButton)        { handleHomeButton(); return; }
        if (button == mergeCancelButton) { exitMergeMode();    return; }

        super.actionPerformed(button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == FormationHubKeyHandler.KEY.getKeyCode()) {
            flushPendingSync(true);
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // Map right-click pan
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        // List click
        if (mouseButton == 0 && mouseX >= listX && mouseX < listX + LIST_W
                && mouseY >= listY && mouseY < listY + listH) {
            int idx = listScroll + (mouseY - listY) / ROW_H;
            if (idx >= 0 && idx < leaders.size()) {
                if (mergeMode) { if (idx != selectedIndex) performMerge(idx); }
                else selectLeader(idx);
                return;
            }
        }

        if (formationTab && !mergeMode) {
            boolean consumed = false;
            if (escortsSlider.mousePressed(mouseX, mouseY, mouseButton))    consumed = true;
            if (followDistSlider.mousePressed(mouseX, mouseY, mouseButton)) consumed = true;
            if (spacingSlider.mousePressed(mouseX, mouseY, mouseButton))    consumed = true;
            if (widthSlider.mousePressed(mouseX, mouseY, mouseButton))      consumed = true;
            if (turnSlider.mousePressed(mouseX, mouseY, mouseButton))       consumed = true;
            if (consumed) { pullSliders(); onSettingsChanged(false); return; }
            if (previewPanel.mousePressed(mouseX, mouseY, mouseButton, previewX, previewY)) return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (mapController != null) mapController.onMouseDrag(mouseX, mouseY);
        if (!formationTab || mergeMode) return;
        boolean changed = false;
        changed |= escortsSlider.mouseDragged(mouseX, mouseY);
        changed |= followDistSlider.mouseDragged(mouseX, mouseY);
        changed |= spacingSlider.mouseDragged(mouseX, mouseY);
        changed |= widthSlider.mouseDragged(mouseX, mouseY);
        changed |= turnSlider.mouseDragged(mouseX, mouseY);
        if (changed) { pullSliders(); onSettingsChanged(false); }
        previewPanel.mouseDragged(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (mapController != null) mapController.onMouseRelease(state);
        if (!formationTab || mergeMode) return;
        boolean changed = false;
        changed |= escortsSlider.mouseReleased(state);
        changed |= followDistSlider.mouseReleased(state);
        changed |= spacingSlider.mouseReleased(state);
        changed |= widthSlider.mouseReleased(state);
        changed |= turnSlider.mouseReleased(state);
        previewPanel.mouseReleased(state);
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
        } else if (formationTab && !mergeMode && previewPanel != null
                && previewPanel.isMouseOver(mouseX, mouseY, previewX, previewY)) {
            previewPanel.handleWheel(wheel);
        }
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private void selectLeader(int idx) {
        if (idx < 0 || idx >= leaders.size()) return;
        selectedIndex = idx;
        applySelection();
        refreshButtons();
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
        if (previewPanel != null) previewPanel.setData(currentSettings, info.followerCount);
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

    // ── Leader Orders ─────────────────────────────────────────────────────────

    private void handleGoButton() {
        if (selectedIndex >= leaders.size()) return;
        FormationLeaderInfo sel = leaders.get(selectedIndex);
        if (sel.isPlayer) return;
        BlockPos leaderPos = new BlockPos((int) sel.posX, 64, (int) sel.posZ);
        GuiWorkerGo goGui = GuiWorkerGo.forLeader(player, sel.uuid, representativeEntityId, leaderPos, this);
        mc.displayGuiScreen(goGui);
    }

    private void handleHomeButton() {
        if (selectedIndex >= leaders.size()) return;
        FormationLeaderInfo sel = leaders.get(selectedIndex);
        if (sel.isPlayer) return;
        mc.displayGuiScreen(new GuiWorkerHomeList(player, sel.uuid, representativeEntityId, this));
    }

    private void handleComeBack() {
        if (selectedIndex >= leaders.size()) return;
        FormationLeaderInfo sel = leaders.get(selectedIndex);
        if (sel.isPlayer) return;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgGroupComeBack(sel.uuid));

        int count = sel.followerCount + 1;
        leaders.remove(selectedIndex);
        if (!leaders.isEmpty()) {
            if (selectedIndex >= leaders.size()) selectedIndex = leaders.size() - 1;
            for (FormationLeaderInfo l : leaders) {
                if (l.isPlayer) { l.followerCount += count; break; }
            }
            applySelection();
        }
        refreshButtons();
    }

    // ── Settings sync ─────────────────────────────────────────────────────────

    private void onSettingsChanged(boolean immediate) {
        if (currentSettings != null) currentSettings.clamp();
        if (previewPanel != null) previewPanel.setData(currentSettings, getFollowerCount());
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
        if (previewPanel != null) previewPanel.setData(currentSettings, getFollowerCount());
    }

    // ── Button refresh ────────────────────────────────────────────────────────

    private void refreshButtons() {
        boolean noSelection = selectedIndex >= leaders.size();
        FormationLeaderInfo sel = noSelection ? null : leaders.get(selectedIndex);
        boolean isPlayer = sel != null && sel.isPlayer;

        boolean showForm = formationTab && !mergeMode;
        if (styleButton != null) styleButton.visible   = showForm;
        if (caveButton  != null) caveButton.visible    = showForm;
        if (zoomInBtn   != null) zoomInBtn.visible     = showForm;
        if (zoomOutBtn  != null) zoomOutBtn.visible    = showForm;
        if (recenterBtn != null) recenterBtn.visible   = showForm;

        boolean showOrders = !formationTab && !mergeMode;
        if (goButton       != null) { goButton.visible       = showOrders; goButton.enabled       = showOrders && !isPlayer; }
        if (comeBackButton != null) { comeBackButton.visible = showOrders; comeBackButton.enabled = showOrders && !isPlayer; }
        if (mergeButton    != null) { mergeButton.visible    = showOrders; mergeButton.enabled    = showOrders && !isPlayer; }
        if (homeButton     != null) { homeButton.visible     = showOrders; homeButton.enabled     = showOrders && !isPlayer; }
        if (mergeCancelButton != null) mergeCancelButton.visible = mergeMode;
        if (backButton != null) backButton.visible = !mergeMode;

        if (showForm && currentSettings != null) {
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

    private int getFollowerCount() {
        if (selectedIndex >= leaders.size()) return 0;
        return leaders.get(selectedIndex).followerCount;
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
