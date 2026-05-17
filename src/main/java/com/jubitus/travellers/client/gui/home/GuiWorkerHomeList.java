package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.home.MsgWorkerRequestHomeList;
import com.jubitus.travellers.server.net.message.home.MsgWorkerSetHome;
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
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerHomeList extends GuiScreen {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<Row> homes = new ArrayList<>();

    private GuiButton setBtn;
    private GuiButton backBtn;

    private final int xSize = 176;
    private final int ySize = 150;
    private int guiLeft;
    private int guiTop;

    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private final int rowH = 12;

    private Integer selectedIdx = null;
    private int scrollOffset = 0;

    public GuiWorkerHomeList(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        int shiftLeft = 40;
        this.guiLeft = Math.max(4, this.guiLeft - shiftLeft);

        this.listW = 140;
        this.listH = 120;
        this.listX = this.guiLeft + this.xSize + 8;
        this.listY = this.guiTop + 4;

        this.buttonList.clear();

        int bw = 70;
        int bh = 16;
        int gap = 4;
        int buttonsTop = this.listY + this.listH + gap;

        this.setBtn = this.addButton(new GuiButton(
                0,
                this.listX,
                buttonsTop,
                bw,
                bh,
                I18n.format("travellers.worker.home_list.set_home", "Set home")
        ));

        this.backBtn = this.addButton(new GuiButton(
                1,
                this.listX + bw + gap,
                buttonsTop,
                bw,
                bh,
                I18n.format("travellers.gui.back", "Back")
        ));

        this.setBtn.enabled = false;
        this.selectedIdx = null;
        this.scrollOffset = 0;

        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestHomeList(this.worker.getEntityId()));
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
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0) return;
        if (this.homes.isEmpty()) return;
        if (!isMouseOverList(mouseX, mouseY)) return;

        int idx = this.scrollOffset + (mouseY - this.listY) / this.rowH;
        if (idx >= 0 && idx < this.homes.size()) {
            this.selectedIdx = idx;
            this.setBtn.enabled = true;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        handleWheel(mouseX, mouseY);
        drawHomeList(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
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

    private void handleWheel(int mouseX, int mouseY) {
        if (!isMouseOverList(mouseX, mouseY)) return;

        int wheel = org.lwjgl.input.Mouse.getDWheel();
        if (wheel == 0) return;

        this.scrollOffset += wheel > 0 ? -1 : 1;
        clampScroll();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
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