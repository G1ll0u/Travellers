package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/** The player's own full-screen map: places, own workers, battles and discovered foreign units. */
@SideOnly(Side.CLIENT)
public class GuiPlayerWorldMap extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapScreen, ITravellersGui {

    private static final int MARGIN = 8;

    private GuiButton backButton;
    private final EntityPlayer player;
    private final int contextWorkerId;
    private WorkerMapView map;
    private int frameX, frameW;

    public GuiPlayerWorldMap(EntityPlayer player, int contextWorkerId) {
        this.player = player;
        this.contextWorkerId = contextWorkerId;
    }

    @Override
    public WorkerMapView mapView() {
        return map;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();

        // Same chrome as the other screens (frame, title, map box), but the frame takes the whole screen.
        frameX = MARGIN;
        frameW = width - MARGIN * 2;
        int frameY = MARGIN, frameH = height - MARGIN * 2;
        int bodyX = frameX + MapListLayout.PAD, bodyY = frameY + MapListLayout.TITLE_H + MapListLayout.PAD;
        int bodyW = frameW - MapListLayout.PAD * 2;
        int bodyH = frameH - MapListLayout.TITLE_H - MapListLayout.PAD * 2;
        int boxH = bodyH - MapListLayout.BTN_H - MapListLayout.BTN_GAP;

        map = new WorkerMapView(bodyX + 1, bodyY + 1, bodyW - 2, boxH - 2, "travellers_player_map", player.getPosition());
        int rowY = bodyY + bodyH - MapListLayout.BTN_H;
        map.addNav(buttonList, 0, player::getPosition, rowY);
        backButton = addButton(new GuiTravellersButton(9, bodyX + bodyW - 58, rowY, 58, MapListLayout.BTN_H,
                I18n.format(contextWorkerId < 0 ? "travellers.gui.close" : "travellers.gui.back")));

        map.subscribe();
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(contextWorkerId));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        map.unsubscribe();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        int frameY = MARGIN;
        TravellersGuiTheme.drawPanel(frameX, frameY, frameW, height - MARGIN * 2);
        MapListLayout.drawTitle(fontRenderer, frameX, frameW, frameY, I18n.format("travellers.map.title"));
        TravellersGuiTheme.drawInnerPanel(map.controller.mapX - 1, map.controller.mapY - 1,
                map.controller.mapW + 2, map.controller.mapH + 2);
        map.draw(player, ClientOwnerVisualsCache.getColor(), mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
        map.drawTooltip(this, mouseX, mouseY);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        map.wheel(mouseX, mouseY, wheel);
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
        map.mouseDown(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        map.mouseDrag(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        map.mouseUp(state);
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
        if (map.handleButton(button)) return;
        super.actionPerformed(button);
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        map.data.setPlaces(entries);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
