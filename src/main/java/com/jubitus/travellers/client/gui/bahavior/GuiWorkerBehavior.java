package com.jubitus.travellers.client.gui.bahavior;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import com.jubitus.travellers.utils.net.message.MsgOpenWorkerSubGui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

@SideOnly(Side.CLIENT)
public class GuiWorkerBehavior extends GuiContainer {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton enemiesButton;
    private GuiButton backButton;

    public GuiWorkerBehavior(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerBehavior(player.inventory, worker)); // IMPORTANT
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 120;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();

        int centerX = this.guiLeft + this.xSize / 2;
        int yBase = this.guiTop + 40;

        enemiesButton = this.addButton(new GuiButton(0, centerX - 60, yBase, 120, 20, "Player enemies"));
        backButton = this.addButton(new GuiButton(1, centerX - 60, yBase + 26, 120, 20, "Back"));
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        this.fontRenderer.drawString("Behavior", 8, 6, 0x404040);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        this.drawDefaultBackground();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == enemiesButton) {
            mc.displayGuiScreen(new GuiWorkerEnemies(this, player, worker));
            return;
        }
        if (button == backButton) {
            // IMPORTANT: don't open container GUIs directly with displayGuiScreen
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB)
            );
            return;
        }
        super.actionPerformed(button);
    }
}
