package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.message.MsgHireWorker;
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
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class GuiWorkerFollow extends GuiContainer {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton hireButton;

    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker_follow.png");

    public GuiWorkerFollow(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerFollow(player.inventory, worker));
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 90;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();

        int bw = 80;
        int bh = 20;
        int x = this.guiLeft + (this.xSize - bw) / 2;
        int y = this.guiTop + 40;

        // Decide label / enabled state
        boolean hired = worker.isHiredFollower();
        UUID ownerId = worker.getFollowOwnerId();
        boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());

        String label;
        boolean enabled = true;

        if (!hired) {
            label = I18n.format("travellers.worker.follow.hire", "Hire (2a)");
        } else if (hiredByMe) {
            label = I18n.format("travellers.worker.follow.unhire", "Unhire");
        } else {
            label = I18n.format("travellers.worker.follow.already_hired", "Already hired");
            enabled = false;
        }

        this.hireButton = this.addButton(new GuiButton(
                0, x, y, bw, bh, label
        ));
        this.hireButton.enabled = enabled;
    }


    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == hireButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgHireWorker(worker.getEntityId())
            );
            mc.player.closeScreen();
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

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.follow.title", "Follower"),
                8, 6, 0x404040
        );
    }
}
