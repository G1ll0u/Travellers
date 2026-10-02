package com.jubitus.travellers.village.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.worker.gui.GuiWorkerBehavior;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.worldmap.gui.WorldMapController;
import com.jubitus.travellers.worldmap.gui.WorldMapMarkerFactory;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.worldmap.net.MsgSubscribeWorkerMap;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worldmap.net.MsgRequestMapDiscovery;
import com.jubitus.travellers.village.net.MsgSetVillageEnemy;
import com.jubitus.travellers.village.net.MsgWorkerRequestVillageRelations;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Edits the player's account-wide village relations. {@code worker}, when present, is only
 * used for map-centering/home-marker richness — never required for correctness. */
@SideOnly(Side.CLIENT)
public class GuiWorkerVillageRelations extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapReceiver, ITravellersGui {

    private static final int MAP_W = 200;

    private final EntityPlayer player;
    @Nullable private final EntityTravellerWorker worker;
    @Nullable private final GuiScreen returnTo;

    private GuiButton toggleEnemy;
    private GuiButton back;

    private List<BlockPos> centers = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private List<Integer> distances = new ArrayList<>();
    private List<Boolean> enemies = new ArrayList<>();
    private int selected = -1;
    private int scrollOffset = 0;

    private int listX, listY, listW, listH;
    private final int rowH = 12;

    private final int xSize = 176;
    private final int ySize = 156;
    private int guiLeft;
    private int guiTop;

    // ── Map data ──────────────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final List<MsgWorkerGoList.Entry> homeEntries = new ArrayList<>();
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();

    public GuiWorkerVillageRelations(EntityPlayer player, EntityTravellerWorker worker) {
        this(player, worker, null);
    }

    public GuiWorkerVillageRelations(EntityPlayer player, @Nullable EntityTravellerWorker worker, @Nullable GuiScreen returnTo) {
        this.player = player;
        this.worker = worker;
        this.returnTo = returnTo;
    }

    private BlockPos referencePos() {
        return worker != null ? worker.getPosition() : player.getPosition();
    }

    @Override
    public void initGui() {
        super.initGui();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop  = (this.height - this.ySize) / 2;

        this.buttonList.clear();

        int centerX = this.guiLeft + this.xSize / 2;

        this.toggleEnemy = this.addButton(new GuiTravellersButton(
                0, centerX - 60, this.guiTop + 110, 120, 20,
                I18n.format("travellers.worker.village_relations.toggle_enemy", "Toggle enemy")));
        this.back = this.addButton(new GuiTravellersButton(
                1, centerX - 60, this.guiTop + 132, 120, 20,
                I18n.format("travellers.gui.back", "Back")));

        this.listX = this.guiLeft + 8;
        this.listY = this.guiTop + 24;
        this.listW = 160;
        this.listH = 78;

        this.toggleEnemy.enabled = false;

        // Map to the left of the panel
        int mapX = this.guiLeft - MAP_W - 8;
        int mapY = this.guiTop;
        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, MAP_W, ySize,
                "travellers_village_relations_map", referencePos());

        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        if (worker != null) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        }
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestVillageRelations());
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    // ── IWorkerGoListReceiver ─────────────────────────────────────────────────

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        homeEntries.clear();
        if (entries != null) {
            for (MsgWorkerGoList.Entry e : entries) {
                if (e.kind == MsgWorkerGoList.Kind.HOME) homeEntries.add(e);
            }
        }
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
        }
        rebuildMapMarkers();
    }

    // ── Village list callback ─────────────────────────────────────────────────

    public void receiveVillageList(List<BlockPos> centers, List<String> labels,
                                   List<Integer> distances, List<Boolean> enemies) {
        this.centers   = centers  != null ? centers  : new ArrayList<>();
        this.labels    = labels   != null ? labels   : new ArrayList<>();
        this.distances = distances != null ? distances : new ArrayList<>();
        this.enemies   = enemies  != null ? enemies  : new ArrayList<>();

        if (this.selected >= this.centers.size()) this.selected = -1;
        clampScroll();
        if (this.toggleEnemy != null)
            this.toggleEnemy.enabled = this.selected >= 0 && this.selected < this.centers.size();

        rebuildMapMarkers();
    }

    // ── Map markers ───────────────────────────────────────────────────────────

    private void rebuildMapMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();

        // Homes from GoList
        for (MsgWorkerGoList.Entry e : homeEntries) {
            if (e.pos != null) mapController.panel.addMarker(WorldMapMarkerFactory.home(e.pos, e.label));
        }

        // Villages from relations data, colored by enemy status and selection
        for (int i = 0; i < centers.size(); i++) {
            BlockPos pos = centers.get(i);
            String  name = (i < labels.size()) ? labels.get(i) : "";
            boolean enemy = enemies != null && i < enemies.size() && enemies.get(i);
            boolean sel   = (i == selected);

            if (sel && enemy)       mapController.panel.addMarker(WorldMapMarkerFactory.villageEnemyHighlighted(pos, name));
            else if (sel)           mapController.panel.addMarker(WorldMapMarkerFactory.villageHighlighted(pos, name));
            else if (enemy)         mapController.panel.addMarker(WorldMapMarkerFactory.villageEnemy(pos, name));
            else                    mapController.panel.addMarker(WorldMapMarkerFactory.village(pos, name));
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

        mapController.panel.addMarker(WorldMapMarkerFactory.player(
                player.getPosition(), player.getName(), ClientOwnerVisualsCache.getColor(), player.rotationYaw));
    }

    private void onVillageSelected(int index) {
        if (mapController != null && index >= 0 && index < centers.size()) {
            mapController.setCenter(centers.get(index));
        }
        rebuildMapMarkers();
    }

    // ── List helpers ──────────────────────────────────────────────────────────

    private boolean isMouseOverList(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH;
    }

    private int getVisibleRows() { return Math.max(1, listH / rowH); }
    private int getMaxScroll()   { return Math.max(0, centers.size() - getVisibleRows()); }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        int max = getMaxScroll();
        if (scrollOffset > max) scrollOffset = max;
    }

    private void toggleAt(int index) {
        if (index < 0 || index >= centers.size()) return;
        boolean newEnemy = !enemies.get(index);
        enemies.set(index, newEnemy);
        TravellersNetwork.CHANNEL.sendToServer(new MsgSetVillageEnemy(centers.get(index), newEnemy));
        rebuildMapMarkers();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == this.toggleEnemy) {
            toggleAt(this.selected);
            return;
        }

        if (button == this.back) {
            if (returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else if (worker != null) {
                // Opened from a worker's hub (server-side, so there is no parent screen to hold).
                com.jubitus.travellers.net.TravellersNetwork.CHANNEL.sendToServer(
                        new com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui(
                                worker.getEntityId(),
                                com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui.Type.HUB));
            } else {
                mc.displayGuiScreen(new GuiWorkerBehavior(player, null, null));
            }
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel  = Mouse.getEventDWheel();

        if (wheel != 0 && mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (wheel != 0 && isMouseOverList(mouseX, mouseY)) {
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

        if (mouseButton != 0 || centers.isEmpty()) return;
        if (!isMouseOverList(mouseX, mouseY)) return;

        int idx = scrollOffset + (mouseY - listY) / rowH;
        if (idx < 0 || idx >= centers.size()) return;

        selected = idx;
        toggleEnemy.enabled = true;
        onVillageSelected(idx);
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

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        TravellersGuiTheme.drawPanel(this.guiLeft, this.guiTop, this.xSize, this.ySize);

        // Map
        if (mapController != null) {
            TravellersGuiTheme.drawMapFrame(mapController.mapX, mapController.mapY, MAP_W, ySize);
            rebuildMapMarkers();
            mapController.draw();
        }

        this.fontRenderer.drawString(
                I18n.format("travellers.worker.village_relations.title", "Village relations"),
                this.guiLeft + 8, this.guiTop + 6, TravellersGuiTheme.COL_TEXT);

        drawVillageList(mouseX, mouseY);

        if (this.toggleEnemy != null)
            this.toggleEnemy.enabled = this.selected >= 0 && this.selected < this.centers.size();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(java.util.Arrays.asList(label.split("\n")), mouseX, mouseY);
            }
        }
    }

    private void drawVillageList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listX - 2, listY - 2, listW + 4, listH + 4);

        clampScroll();
        int rowsToDraw = Math.min(getVisibleRows(), centers.size() - scrollOffset);
        for (int i = 0; i < rowsToDraw; i++) {
            int rowTop    = listY + i * rowH;
            int rowBottom = rowTop + rowH;
            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;
            int globalIdx = scrollOffset + i;
            boolean isSel = globalIdx == selected;
            boolean isEnemy = enemies != null && globalIdx < enemies.size() && enemies.get(globalIdx);

            TravellersGuiTheme.drawListRow(listX, rowTop, listW, rowH, hovered, isSel);

            String label = labels.get(globalIdx);
            float scale = 0.85F;

            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);

            int maxUnscaledWidth = (int) ((this.listW - 6) / scale);
            String trimmed = this.fontRenderer.trimStringToWidth(label, maxUnscaledWidth);

            int textColor = isEnemy ? TravellersGuiTheme.COL_CANNOT
                    : (hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
            this.fontRenderer.drawString(trimmed, 0, 0, textColor);
            GlStateManager.popMatrix();
        }

        if (centers.isEmpty()) {
            this.fontRenderer.drawString(
                    I18n.format("travellers.worker.village_relations.none_known", "No known villages nearby yet."),
                    listX, listY + 2, TravellersGuiTheme.COL_TEXT_DIM);
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
