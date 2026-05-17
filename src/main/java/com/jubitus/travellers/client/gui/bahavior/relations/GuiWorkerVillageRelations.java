package com.jubitus.travellers.client.gui.bahavior.relations;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.relations.MsgSetVillageEnemy;
import com.jubitus.travellers.server.net.message.relations.MsgWorkerRequestVillageRelations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.client.GuiScrollingList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerVillageRelations extends GuiScreen {

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

    public GuiWorkerVillageRelations(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        this.buttonList.clear();

        int centerX = this.guiLeft + this.xSize / 2;

        this.toggleEnemy = this.addButton(new GuiButton(
                0,
                centerX - 60,
                this.guiTop + 110,
                120,
                20,
                I18n.format("travellers.worker.village_relations.toggle_enemy", "Toggle enemy")
        ));
        this.back = this.addButton(new GuiButton(
                1,
                centerX - 60,
                this.guiTop + 132,
                120,
                20,
                I18n.format("travellers.gui.back", "Back")
        ));

        this.listWidget = new VillageList(
                Minecraft.getMinecraft(),
                160,
                80,
                this.guiTop + 22,
                this.guiTop + 102,
                this.guiLeft + 8,
                14,
                this.width,
                this.height
        );

        this.toggleEnemy.enabled = false;

        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestVillageRelations(this.worker.getEntityId()));
    }

    public void receiveVillageList(List<BlockPos> centers, List<String> labels, List<Integer> distances, List<Boolean> enemies) {
        this.centers = centers != null ? centers : new ArrayList<>();
        this.labels = labels != null ? labels : new ArrayList<>();
        this.distances = distances != null ? distances : new ArrayList<>();
        this.enemies = enemies != null ? enemies : new ArrayList<>();

        if (this.selected >= this.centers.size()) {
            this.selected = -1;
        }

        if (this.toggleEnemy != null) {
            this.toggleEnemy.enabled = this.selected >= 0 && this.selected < this.centers.size();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == this.toggleEnemy) {
            if (this.selected >= 0 && this.selected < this.centers.size()) {
                boolean newEnemy = !this.enemies.get(this.selected);
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgSetVillageEnemy(this.worker.getEntityId(), this.centers.get(this.selected), newEnemy)
                );
            }
            return;
        }

        if (button == this.back) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.BEHAVIOR)
            );
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();

        if (this.listWidget != null) {
            int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            this.listWidget.handleMouseInput(mouseX, mouseY);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + this.ySize, 0xCC000000);
        drawRect(this.guiLeft + 2, this.guiTop + 2, this.guiLeft + this.xSize - 2, this.guiTop + this.ySize - 2, 0xFF202020);

        if (this.listWidget != null) {
            this.listWidget.drawScreen(mouseX, mouseY, partialTicks);
        }

        this.fontRenderer.drawString(
                I18n.format("travellers.worker.village_relations.title", "Village relations"),
                this.guiLeft + 8,
                this.guiTop + 6,
                0xFFFFFF
        );
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.village_relations.hint", "Select a village, then toggle enemy."),
                this.guiLeft + 8,
                this.guiTop + 16,
                0xC0C0C0
        );

        if (this.toggleEnemy != null) {
            this.toggleEnemy.enabled = this.selected >= 0 && this.selected < this.centers.size();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private class VillageList extends GuiScrollingList {

        VillageList(Minecraft client, int width, int height, int top, int bottom, int left, int entryHeight, int screenWidth, int screenHeight) {
            super(client, width, height, top, bottom, left, entryHeight, screenWidth, screenHeight);
        }

        @Override
        protected int getSize() {
            return labels == null ? 0 : labels.size();
        }

        @Override
        protected void elementClicked(int index, boolean doubleClick) {
            selected = index;

            if (toggleEnemy != null) {
                toggleEnemy.enabled = selected >= 0 && selected < centers.size();
            }

            if (doubleClick && index >= 0 && index < centers.size()) {
                boolean newEnemy = !enemies.get(index);
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgSetVillageEnemy(worker.getEntityId(), centers.get(index), newEnemy)
                );
            }
        }

        @Override
        protected boolean isSelected(int index) {
            return index == selected;
        }

        @Override
        protected void drawBackground() {
        }

        @Override
        protected void drawSlot(int slotIdx, int entryRight, int slotTop, int slotBuffer, net.minecraft.client.renderer.Tessellator tess) {
            if (slotIdx < 0 || slotIdx >= labels.size()) return;

            String s = labels.get(slotIdx);
            boolean enemy = enemies != null && slotIdx < enemies.size() && enemies.get(slotIdx);

            int color = enemy ? 0xCC4444 : 0xFFFFFF;
            GuiWorkerVillageRelations.this.fontRenderer.drawString(s, this.left + 2, slotTop + 2, color);
        }
    }
}