package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;
import com.jubitus.travellers.server.map.MsgSubscribeWorkerMap;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.home.MsgWorkerRequestHomeList;
import com.jubitus.travellers.server.net.message.home.MsgWorkerSetHome;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerHomeList extends GuiScreen implements IWorkerMapReceiver {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<Row> homes = new ArrayList<>();

    private GuiButton setBtn;
    private GuiButton backBtn;

    private int listX, listY, listW, listH;
    private int mapX, mapY, mapW, mapH;
    private final int rowH = 12;

    private Integer selectedIdx = null;
    private int scrollOffset = 0;

    private WorldMapController mapController;
    private final List<MsgMapWorkersUpdate.Entry> workerEntries = new ArrayList<>();
    private int playerOwnerColor = 0xFFFFFF;

    public GuiWorkerHomeList(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();

        listW = 140;
        listH = 120;
        mapW  = 190;
        mapH  = 150;

        int totalW = mapW + 14 + listW;
        int startX = Math.max(4, (this.width - totalW) / 2);
        mapX  = startX;
        listX = startX + mapW + 14;
        mapY  = (this.height - mapH) / 2;
        listY = mapY + (mapH - listH) / 2;

        this.buttonList.clear();

        int bw = 70;
        int bh = 20;
        int gap = 4;
        int buttonsTop = listY + listH + gap;

        this.backBtn = this.addButton(new GuiButton(
                1,
                listX,
                buttonsTop,
                bw,
                bh,
                I18n.format("travellers.gui.back", "Back")
        ));

        this.setBtn = this.addButton(new GuiButton(
                0,
                listX + bw + gap,
                buttonsTop,
                bw,
                bh,
                I18n.format("travellers.worker.home_list.set_home", "Set home")
        ));

        this.setBtn.enabled = false;
        this.selectedIdx = null;
        this.scrollOffset = 0;

        mapController = new WorldMapController(
                Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
                "travellers_home_list_map", worker.getPosition());

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(true));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestHomeList(this.worker.getEntityId()));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgSubscribeWorkerMap(false));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == this.backBtn) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS)
            );
            return;
        }

        if (button == this.setBtn) {
            if (this.selectedIdx == null) return;
            int idx = this.selectedIdx;
            if (idx < 0 || idx >= this.homes.size()) return;

            Row row = this.homes.get(idx);
            TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerSetHome(this.worker.getEntityId(), row.pos));
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS)
            );
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0) return;
        if (this.homes.isEmpty()) return;
        if (!isMouseOverList(mouseX, mouseY)) return;

        int idx = this.scrollOffset + (mouseY - this.listY) / this.rowH;
        if (idx >= 0 && idx < this.homes.size()) {
            this.selectedIdx = idx;
            this.setBtn.enabled = true;
            if (mapController != null) mapController.setCenter(this.homes.get(idx).pos);
            rebuildMarkers();
        }
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

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel  = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) return;

        if (mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (isMouseOverList(mouseX, mouseY)) {
            this.scrollOffset += wheel > 0 ? -1 : 1;
            clampScroll();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        if (mapController != null) {
            drawRect(mapX - 2, mapY - 2, mapX + mapW + 2, mapY + mapH + 2, 0x80000000);
            rebuildMarkers();
            mapController.draw();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        drawHomeList(mouseX, mouseY);

        if (mapController != null) {
            String label = mapController.getTooltip(mouseX, mouseY);
            if (label != null && !label.isEmpty()) {
                drawHoveringText(Collections.singletonList(label), mouseX, mouseY);
            }
        }
    }

    private void drawHomeList(int mouseX, int mouseY) {
        drawRect(this.listX - 2, this.listY - 2, this.listX + this.listW + 2, this.listY + this.listH + 2, 0x80000000);

        this.fontRenderer.drawString(
                I18n.format("travellers.worker.home_list.title", "Homes"),
                this.listX,
                this.listY - 10,
                0xF9CBAE
        );

        if (this.homes.isEmpty()) {
            this.fontRenderer.drawString(
                    I18n.format("travellers.worker.home_list.empty", "No homes."),
                    this.listX + 4,
                    this.listY + 4,
                    0xC0C0C0
            );
            return;
        }

        clampScroll();

        int visibleRows = Math.min(getVisibleRows(), this.homes.size() - this.scrollOffset);
        BlockPos assigned = this.worker.getAssignedHomePos();

        for (int i = 0; i < visibleRows; i++) {
            int rowTop = this.listY + i * this.rowH;
            int rowBottom = rowTop + this.rowH;
            int globalIdx = this.scrollOffset + i;

            boolean hovered = mouseX >= this.listX && mouseX < this.listX + this.listW
                    && mouseY >= rowTop && mouseY < rowBottom;
            boolean selected = this.selectedIdx != null && this.selectedIdx == globalIdx;

            int bg = selected ? 0x4033FF33 : (hovered ? 0x20FFFFFF : 0x20000000);
            drawRect(this.listX, rowTop, this.listX + this.listW, rowBottom, bg);

            Row row = this.homes.get(globalIdx);

            String label = row.name == null || row.name.trim().isEmpty()
                    ? I18n.format("travellers.gui.unnamed", "(unnamed)")
                    : row.name.trim();

            if (assigned != null && assigned.equals(row.pos)) {
                label = "* " + label;
            }

            final float scale = 0.85F;

            GlStateManager.pushMatrix();
            GlStateManager.translate(this.listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);

            int maxWidth = (int) ((this.listW - 6) / scale);
            String trimmed = this.fontRenderer.trimStringToWidth(label, maxWidth);

            this.fontRenderer.drawString(trimmed, 0, 0, 0xE0E0E0);
            GlStateManager.popMatrix();
        }
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        mapController.panel.clearMarkers();

        BlockPos assigned = this.worker.getAssignedHomePos();

        for (int i = 0; i < homes.size(); i++) {
            Row row = homes.get(i);
            if (row.pos == null) continue;
            String label = (row.name == null || row.name.trim().isEmpty())
                    ? I18n.format("travellers.gui.unnamed", "(unnamed)")
                    : row.name.trim();
            if (assigned != null && assigned.equals(row.pos)) label = "* " + label;

            if (this.selectedIdx != null && this.selectedIdx == i) {
                mapController.panel.addMarker(WorldMapMarkerFactory.villageHighlighted(row.pos, label));
            } else {
                mapController.panel.addMarker(WorldMapMarkerFactory.home(row.pos, label));
            }
        }

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

    public void receiveHomes(List<BlockPos> pos, List<String> names) {
        this.homes.clear();

        int n = Math.min(pos.size(), names.size());
        for (int i = 0; i < n; i++) {
            this.homes.add(new Row(pos.get(i), names.get(i)));
        }

        this.selectedIdx = null;
        this.scrollOffset = 0;

        if (this.setBtn != null) {
            this.setBtn.enabled = false;
        }

        if (!homes.isEmpty() && mapController != null) {
            mapController.setCenter(homes.get(0).pos);
        }
    }

    private boolean isMouseOverList(int mouseX, int mouseY) {
        return mouseX >= this.listX && mouseX < this.listX + this.listW
                && mouseY >= this.listY && mouseY < this.listY + this.listH;
    }

    private int getVisibleRows() {
        return Math.max(1, this.listH / this.rowH);
    }

    private int getMaxScroll() {
        return Math.max(0, this.homes.size() - getVisibleRows());
    }

    private void clampScroll() {
        if (this.scrollOffset < 0) this.scrollOffset = 0;
        int max = getMaxScroll();
        if (this.scrollOffset > max) this.scrollOffset = max;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    public Rectangle getJeiMapArea() {
        return new Rectangle(mapX - 2, mapY - 2, mapW + 4, mapH + 4);
    }

    public Rectangle getJeiListArea() {
        return new Rectangle(listX - 2, listY - 12, listW + 4, listH + 36);
    }

    private static class Row {
        final BlockPos pos;
        final String name;

        Row(BlockPos pos, String name) {
            this.pos = pos;
            this.name = name == null ? "" : name;
        }
    }
}
