package com.jubitus.travellers.village.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.ScrollList;
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
import com.jubitus.travellers.worldmap.gui.WorkerMapScreenSupport;
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

    private int listX, listY, listW, listH;
    private final ScrollList list = new ScrollList(12);
    private static final float ROW_TEXT_SCALE = 0.85F;

    private final int xSize = 176;
    private final int ySize = 156;
    private int guiLeft;
    private int guiTop;

    // ── Map data ──────────────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final WorkerMapScreenSupport mapData = new WorkerMapScreenSupport();

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
        list.setBounds(listX, listY, listW, listH);

        this.toggleEnemy.enabled = false;

        // Map to the left of the panel
        int mapX = this.guiLeft - MAP_W - 8;
        int mapY = this.guiTop;
        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, MAP_W, ySize,
                "travellers_village_relations_map", referencePos());

        mapData.subscribe();
        if (worker != null) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        }
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestVillageRelations());
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        mapData.unsubscribe();
    }

    // ── IWorkerGoListReceiver ─────────────────────────────────────────────────

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        mapData.setPlaces(entries);
        rebuildMapMarkers();
    }

    // ── IWorkerMapReceiver ────────────────────────────────────────────────────

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        mapData.setWorkers(list);
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
        list.clamp(this.centers.size());
        if (this.toggleEnemy != null)
            this.toggleEnemy.enabled = this.selected >= 0 && this.selected < this.centers.size();

        rebuildMapMarkers();
    }

    // ── Map markers ───────────────────────────────────────────────────────────

    private void rebuildMapMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();

        // Homes from GoList (villages come from the relations data below)
        for (MsgWorkerGoList.Entry e : mapData.places) {
            if (e.pos != null && e.kind == MsgWorkerGoList.Kind.HOME)
                mapController.panel.addMarker(WorldMapMarkerFactory.home(e.pos, e.label));
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
        for (MsgMapWorkersUpdate.Entry e : mapData.workers) {
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
        } else if (list.isOver(mouseX, mouseY)) {
            list.scrollBy(wheel, centers.size());
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0) return;
        int idx = list.rowAt(mouseX, mouseY, centers.size());
        if (idx < 0) return;

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

        if (mapController != null) mapController.drawTooltip(this, mouseX, mouseY);
    }

    private void drawVillageList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listX - 2, listY - 2, listW + 4, listH + 4);

        list.clamp(centers.size());
        int maxLabelW = (int) ((listW - 6) / ROW_TEXT_SCALE);
        for (int i = 0; i < list.rowsToDraw(centers.size()); i++) {
            int idx = list.scroll() + i;
            int rowTop = list.rowTop(idx);
            boolean hovered = list.isRowHovered(idx, mouseX, mouseY);
            boolean isEnemy = enemies != null && idx < enemies.size() && enemies.get(idx);
            TravellersGuiTheme.drawListRow(listX, rowTop, listW, list.rowH, hovered, idx == selected);

            int textColor = isEnemy ? TravellersGuiTheme.COL_CANNOT
                    : (hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
            TravellersGuiTheme.drawScaledText(fontRenderer, fontRenderer.trimStringToWidth(labels.get(idx), maxLabelW),
                    listX + 3, rowTop + 2, ROW_TEXT_SCALE, textColor);
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
