package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.worldmap.gui.receiver.IBattleMapReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IForeignUnitMapReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import com.jubitus.travellers.worldmap.net.MsgSubscribeWorkerMap;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worldmap.net.MsgRequestMapDiscovery;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiPlayerWorldMap extends GuiScreen
        implements IWorkerGoListReceiver, IWorkerMapReceiver, IBattleMapReceiver, IForeignUnitMapReceiver, ITravellersGui {

    private GuiButton backButton;
    private GuiButton zoomInButton, zoomOutButton, recenterButton;
    private GuiButton panN, panS, panWBtn, panE;

    private final EntityPlayer player;
    private final int contextWorkerId;

    private WorldMapController mapController;
    private int mapX, mapY, mapW, mapH;

    private final List<MsgWorkerGoList.Entry> poiEntries  = new ArrayList<>();
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private final List<com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate.Entry> battleEntries = new ArrayList<>();
    private final List<com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate.Entry> foreignEntries = new ArrayList<>();

    public GuiPlayerWorldMap(EntityPlayer player, int contextWorkerId) {
        this.player = player;
        this.contextWorkerId = contextWorkerId;
    }

    @Override
    public void initGui() {
        super.initGui();

        int sideMargin = 16;
        int topMargin = 16;
        int bottomBarHeight = 34; // gap + zoom/back button row
        mapW = this.width - sideMargin * 2;
        mapH = this.height - topMargin - bottomBarHeight;
        mapX = sideMargin;
        mapY = topMargin;

        this.buttonList.clear();

        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_player_map", player.getPosition());
        mapController.panel.setSelectedPos(null);

        int aw = 16, ah = 16;
        int midY = mapY + (mapH / 2) - (ah / 2);
        int midX = mapX + (mapW / 2) - (aw / 2);

        panWBtn = this.addButton(new GuiTravellersButton(2, mapX + 2,           midY, aw, ah, "<"));
        panE    = this.addButton(new GuiTravellersButton(3, mapX + mapW - aw - 2, midY, aw, ah, ">"));
        panN    = this.addButton(new GuiTravellersButton(4, midX, mapY + 2,           aw, ah, "^"));
        panS    = this.addButton(new GuiTravellersButton(5, midX, mapY + mapH - ah - 2, aw, ah, "v"));

        int zTop = mapY + mapH + 6;
        zoomOutButton  = this.addButton(new GuiTravellersButton(6, mapX,           zTop, 20, 20, "-"));
        zoomInButton   = this.addButton(new GuiTravellersButton(7, mapX + 24,      zTop, 20, 20, "+"));
        recenterButton = this.addButton(new GuiTravellersButton(8, mapX + mapW - 58, zTop, 58, 20,
                I18n.format("travellers.map.center", "Center")));
        backButton     = this.addButton(new GuiTravellersButton(9, mapX + mapW - 122, zTop, 58, 20,
                I18n.format(contextWorkerId < 0 ? "travellers.gui.close" : "travellers.gui.back")));

        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(contextWorkerId));
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellersNetwork.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int wheel = Mouse.getDWheel();
        if (wheel != 0) mapController.handleWheel(mouseX, mouseY, wheel);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        TravellersGuiTheme.drawMapFrame(mapX, mapY, mapW, mapH);

        rebuildMarkers();
        mapController.draw();

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHoverTooltip(mouseX, mouseY);
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        String label = mapController.getTooltip(mouseX, mouseY);
        if (label != null && !label.isEmpty()) {
            this.drawHoveringText(java.util.Arrays.asList(label.split("\n")), mouseX, mouseY);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == WorldMapKeyHandler.KEY.getKeyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        mapController.onMouseClick(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        mapController.onMouseDrag(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        mapController.onMouseRelease(state);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        int step = Math.round(mapController.panel.getZoomBlocksPerPixel() * 48);

        if (button == backButton) {
            if (contextWorkerId < 0) {
                mc.displayGuiScreen(null);
            } else {
                TravellersNetwork.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(contextWorkerId, MsgOpenWorkerSubGui.Type.HUB));
            }
            return;
        }
        if (button == panN)   { mapController.panel.pan(0, -step); return; }
        if (button == panS)   { mapController.panel.pan(0,  step); return; }
        if (button == panWBtn){ mapController.panel.pan(-step, 0); return; }
        if (button == panE)   { mapController.panel.pan( step, 0); return; }
        if (button == zoomOutButton) {
            mapController.handleWheel(mapX + mapW / 2, mapY + mapH / 2, -1);
            return;
        }
        if (button == zoomInButton) {
            mapController.handleWheel(mapX + mapW / 2, mapY + mapH / 2, 1);
            return;
        }
        if (button == recenterButton) { mapController.setCenter(player.getPosition()); return; }

        super.actionPerformed(button);
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        poiEntries.clear();
        if (entries != null) poiEntries.addAll(entries);
        rebuildMarkers();
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        workerEntries.clear();
        for (MsgMapWorkersUpdate.Entry e : list) {
            workerEntries.add(e);
        }
        rebuildMarkers();
    }

    @Override
    public void receiveBattles(List<com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate.Entry> entries) {
        battleEntries.clear();
        if (entries != null) battleEntries.addAll(entries);
        rebuildMarkers();
    }

    @Override
    public void receiveForeignUnits(List<com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate.Entry> entries) {
        foreignEntries.clear();
        if (entries != null) foreignEntries.addAll(entries);
        rebuildMarkers();
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();
        WorldMapContentPopulator.populate(
                mapController.panel, poiEntries, workerEntries, player, ClientOwnerVisualsCache.getColor());
        int myDim = Minecraft.getMinecraft().world.provider.getDimension();
        WorldMapContentPopulator.populateForeignUnits(mapController.panel, foreignEntries, myDim);
        for (com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate.Entry e : battleEntries) {
            if (e.dim != myDim) continue;
            mapController.panel.addMarker(WorldMapMarkerFactory.battle(e.pos, e.label));
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
