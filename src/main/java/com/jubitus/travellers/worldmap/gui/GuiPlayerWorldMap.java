package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.worldmap.gui.receiver.IBattleMapReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IForeignUnitMapReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate;
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
    private WorldMapNavButtons navButtons;

    private final EntityPlayer player;
    private final int contextWorkerId;

    private WorldMapController mapController;
    private int mapX, mapY, mapW, mapH;

    private final WorkerMapScreenSupport mapData = new WorkerMapScreenSupport();

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

        navButtons = new WorldMapNavButtons(mapController, buttonList, 2, player::getPosition);
        int zTop = mapY + mapH + 6;
        backButton     = this.addButton(new GuiTravellersButton(9, mapX + mapW - 122, zTop, 58, 20,
                I18n.format(contextWorkerId < 0 ? "travellers.gui.close" : "travellers.gui.back")));

        mapData.subscribe();
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(contextWorkerId));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        mapData.unsubscribe();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        rebuildMarkers(); // markers glide between updates, so they are re-placed every frame

        int wheel = Mouse.getDWheel();
        if (wheel != 0) mapController.handleWheel(mouseX, mouseY, wheel);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        TravellersGuiTheme.drawMapFrame(mapX, mapY, mapW, mapH);

        rebuildMarkers();
        mapController.draw();

        super.drawScreen(mouseX, mouseY, partialTicks);
        mapController.drawTooltip(this, mouseX, mouseY);
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
        if (button == backButton) {
            if (contextWorkerId < 0) {
                mc.displayGuiScreen(null);
            } else {
                TravellersNetwork.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(contextWorkerId, MsgOpenWorkerSubGui.Type.HUB));
            }
            return;
        }
        if (navButtons.handle(button)) return;

        super.actionPerformed(button);
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        mapData.setPlaces(entries);
        rebuildMarkers();
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> list) {
        mapData.setWorkers(list);
        rebuildMarkers();
    }

    @Override
    public void receiveBattles(List<MsgMapBattleUpdate.Entry> entries) {
        mapData.setBattles(entries);
        rebuildMarkers();
    }

    @Override
    public void receiveForeignUnits(List<MsgMapForeignUnitsUpdate.Entry> entries) {
        mapData.setForeignUnits(entries);
        rebuildMarkers();
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        mapData.populate(mapController, player, ClientOwnerVisualsCache.getColor(), null);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
