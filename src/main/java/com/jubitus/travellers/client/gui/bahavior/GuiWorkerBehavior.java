package com.jubitus.travellers.client.gui.bahavior;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

@SideOnly(Side.CLIENT)
public class GuiWorkerBehavior extends GuiScreen {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton villageRelationsButton;
    private GuiButton enemiesButton;
    private GuiButton backButton;

    private final int xSize = 176;
    private final int ySize = 120;
    private int guiLeft;
    private int guiTop;

    public GuiWorkerBehavior(EntityPlayer player, EntityTravellerWorker worker) {
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
        int yBase = this.guiTop + 40;

        this.enemiesButton = this.addButton(new GuiButton(
                0,
                centerX - 60,
                yBase,
                120,
                20,
                I18n.format("travellers.worker.behavior.player_enemies", "Player enemies")
        ));
        this.backButton = this.addButton(new GuiButton(
                1,
                centerX - 60,
                yBase + 26,
                120,
                20,
                I18n.format("travellers.gui.back", "Back")
        ));
        this.villageRelationsButton = this.addButton(new GuiButton(
                2,
                centerX - 60,
                yBase + 52,
                120,
                20,
                I18n.format("travellers.worker.behavior.village_relations", "Village relations")
        ));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == this.enemiesButton) {
            this.mc.displayGuiScreen(new GuiWorkerEnemies(this, this.player, this.worker));
            return;
        }

        if (button == this.backButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB)
            );
            return;
        }

        if (button == this.villageRelationsButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(this.worker.getEntityId(), MsgOpenWorkerSubGui.Type.VILLAGE_RELATIONS)
            );
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + this.ySize, 0xCC000000);
        drawRect(this.guiLeft + 2, this.guiTop + 2, this.guiLeft + this.xSize - 2, this.guiTop + this.ySize - 2, 0xFF202020);

        this.fontRenderer.drawString(
                I18n.format("travellers.worker.behavior.title", "Behavior"),
                this.guiLeft + 8,
                this.guiTop + 6,
                0xFFFFFF
        );

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}