package com.jubitus.travellers.client.gui.bahavior.relations;

import com.jubitus.travellers.client.gui.worldmap.IWorkerGoListReceiver;
import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.server.net.message.relations.MsgSetVillageEnemy;
import com.jubitus.travellers.server.net.message.relations.MsgWorkerRequestVillageRelations;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerRequestGoList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.client.GuiScrollingList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerVillageRelations extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapReceiver {

    private static final int MAP_W = 200;

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton toggleEnemy;
    private GuiButton back;

    private VillageList listWidget;

    private List<BlockPos> centers = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private List<Integer> distances = new ArrayList<>();
    private List<Boolean> enemies = new ArrayList<>();
    private int selected = -1;

    private final int xSize = 176;
    private final int ySize = 156;
    private int guiLeft;
    private int guiTop;

    // ── Map data ──────────────────────────────────────────────────────────────
    private WorldMapController mapController;
    private final List<MsgWorkerGoList.Entry> homeEntries = new ArrayList<>();
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

    public GuiWorkerVillageRelations(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop  = (this.height - this.ySize) / 2;

        this.buttonList.clear();

        int centerX = this.guiLeft + this.xSize / 2;

        this.toggleEnemy = this.addButton(new GuiButton(
                0, centerX - 60, this.guiTop + 110, 120, 20,
                I18n.format("travellers.worker.village_relations.toggle_enemy", "Toggle enemy")));
        this.back = this.addButton(new GuiButton(
                1, centerX - 60, this.guiTop + 132, 120, 20,
                I18n.format("travellers.gui.back", "Back")));

        this.listWidget = new VillageList(
                Minecraft.getMinecraft(), 160, 80,
                this.guiTop + 22, this.guiTop + 102,
                this.guiLeft + 8, 14, this.width, this.height);

        this.toggleEnemy.enabled = false;

        // Map to the left of the panel
        int mapX = this.guiLeft - MAP_W - 8;
        int mapY = this.guiTop;
        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, MAP_W, ySize,
                "travellers_village_relations_map", worker.getPosition());

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestVillageRelations(this.worker.getEntityId()));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
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
            if (playerOwnerColor == 0xFFFFFF && e.ownerColor != 0) playerOwnerColor = e.ownerColor;
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
                player.getPosition(), player.getName(), playerOwnerColor));
    }

    private void onVillageSelected(int index) {
        if (mapController != null && index >= 0 && index < centers.size()) {
            mapController.setCenter(centers.get(index));
        }
        rebuildMapMarkers();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == this.toggleEnemy) {
            if (this.selected >= 0 && this.selected < this.centers.size()) {
                boolean newEnemy = !this.enemies.get(this.selected);
                // Optimistic update
                this.enemies.set(this.selected, newEnemy);
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgSetVillageEnemy(this.worker.getEntityId(), this.centers.get(this.selected), newEnemy));
                rebuildMapMarkers();
            }
            return;
        }

        if (button == this.back) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.BEHAVIOR));
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
        } else if (this.listWidget != null) {
            this.listWidget.handleMouseInput(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
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

        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + this.ySize, 0xCC000000);
        drawRect(this.guiLeft + 2, this.guiTop + 2, this.guiLeft + this.xSize - 2, this.guiTop + this.ySize - 2, 0xFF202020);

        // Map
        if (mapController != null) {
            drawRect(mapController.mapX - 2, mapController.mapY - 2,
                     mapController.mapX + MAP_W + 2, mapController.mapY + ySize + 2, 0x80000000);
            rebuildMapMarkers();
            mapController.draw();
        }

        if (this.listWidget != null) this.listWidget.drawScreen(mouseX, mouseY, partialTicks);

        this.fontRenderer.drawString(
                I18n.format("travellers.worker.village_relations.title", "Village relations"),
                this.guiLeft + 8, this.guiTop + 6, 0xFFFFFF);
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.village_relations.hint", "Select a village, then toggle enemy."),
                this.guiLeft + 8, this.guiTop + 16, 0xC0C0C0);

        if (this.toggleEnemy != null)
            this.toggleEnemy.enabled = this.selected >= 0 && this.selected < this.centers.size();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    // ── Inner list widget ─────────────────────────────────────────────────────

    private class VillageList extends GuiScrollingList {

        VillageList(Minecraft client, int width, int height, int top, int bottom, int left,
                    int entryHeight, int screenWidth, int screenHeight) {
            super(client, width, height, top, bottom, left, entryHeight, screenWidth, screenHeight);
        }

        @Override
        protected int getSize() { return labels == null ? 0 : labels.size(); }

        @Override
        protected void elementClicked(int index, boolean doubleClick) {
            selected = index;
            if (toggleEnemy != null)
                toggleEnemy.enabled = selected >= 0 && selected < centers.size();
            onVillageSelected(index);

            if (doubleClick && index >= 0 && index < centers.size()) {
                boolean newEnemy = !enemies.get(index);
                enemies.set(index, newEnemy);
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgSetVillageEnemy(worker.getEntityId(), centers.get(index), newEnemy));
                rebuildMapMarkers();
            }
        }

        @Override
        protected boolean isSelected(int index) { return index == selected; }

        @Override
        protected void drawBackground() {}

        @Override
        protected void drawSlot(int slotIdx, int entryRight, int slotTop, int slotBuffer,
                                net.minecraft.client.renderer.Tessellator tess) {
            if (slotIdx < 0 || slotIdx >= labels.size()) return;
            String s = labels.get(slotIdx);
            boolean enemy = enemies != null && slotIdx < enemies.size() && enemies.get(slotIdx);
            int color = enemy ? 0xCC4444 : (slotIdx == selected ? 0xFFFF99 : 0xFFFFFF);
            GuiWorkerVillageRelations.this.fontRenderer.drawString(s, this.left + 2, slotTop + 2, color);
        }
    }
}
