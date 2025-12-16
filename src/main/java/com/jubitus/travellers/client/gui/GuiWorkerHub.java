package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

@SideOnly(Side.CLIENT)
public class GuiWorkerHub extends GuiContainer {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton transportButton;
    private GuiButton followButton;
    private GuiButton inventoryButton; // NEW

    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker_hub.png");

    public GuiWorkerHub(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerHub(player.inventory, worker));
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 90;
    }

    @Override
    public void initGui() {
        super.initGui();

        int x = this.guiLeft;
        int y = this.guiTop;

        this.buttonList.clear();

        int bw  = 60;
        int bh  = 20;
        int gap = 4;

        int totalW = bw * 3 + gap * 2;
        int left = x + (this.xSize - totalW) / 2;
        int buttonsY = y + 40;

        this.transportButton = this.addButton(new GuiButton(
                0,
                left,
                buttonsY,
                bw, bh,
                I18n.format("travellers.worker.hub.transport", "Transport")
        ));

        this.followButton = this.addButton(new GuiButton(
                1,
                left + bw + gap,
                buttonsY,
                bw, bh,
                I18n.format("travellers.worker.hub.follow", "Follow")
        ));

        this.inventoryButton = this.addButton(new GuiButton(
                2,
                left + (bw + gap) * 2,
                buttonsY,
                bw, bh,
                I18n.format("travellers.worker.hub.inventory", "Inventory")
        ));
//        // Only allow the hiring player to use this button
//        boolean hiredByThisPlayer =
//                worker.isHiredFollower()
//                        && player.getUniqueID().equals(worker.getFollowOwnerId());
//
//        this.inventoryButton.enabled = hiredByThisPlayer;
    }


    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == transportButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.TRANSPORT)
            );
            // ❌ no mc.player.closeScreen() here
            return;
        }

        if (button == followButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS)
            );
            return;
        }

        if (button == inventoryButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.INVENTORY)
            );
            // ❌ no mc.player.closeScreen() here
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1F, 1F, 1F, 1F);
        mc.getTextureManager().bindTexture(TEX);
        int x = this.guiLeft;
        int y = this.guiTop;
        this.drawTexturedModalRect(x, y, 0, 0, this.xSize, this.ySize);
    }

}
