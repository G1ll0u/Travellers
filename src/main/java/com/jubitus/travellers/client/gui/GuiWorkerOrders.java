package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.message.MsgHireWorker;
import com.jubitus.travellers.utils.net.message.MsgMakeWorkerLeader;
import com.jubitus.travellers.utils.net.message.MsgToggleWorkerFollow;
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
public class GuiWorkerOrders extends GuiContainer {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton hireButton;
    private GuiButton followButton;
    private GuiButton leaderButton;

    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker_follow.png");

    public GuiWorkerOrders(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerFollow(player.inventory, worker)); // same container
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
        int yBase   = this.guiTop + 32;

        int bw = 80;
        int bh = 20;
        int gap = 4;

        // Create buttons ONCE. Logic (text/visible/enabled) will be done in updateScreen()
        this.hireButton = this.addButton(new GuiButton(
                0, centerX - bw / 2, yBase, bw, bh, ""
        ));

        this.followButton = this.addButton(new GuiButton(
                1, centerX - bw - gap / 2, yBase + bh + gap, bw, bh, ""
        ));

        this.leaderButton = this.addButton(new GuiButton(
                2, centerX + gap / 2, yBase + bh + gap, bw, bh,
                I18n.format("travellers.worker.orders.make_leader", "Make leader")
        ));

        // First state update
        refreshButtonStates();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // Every tick, recompute what should be visible / labels
        refreshButtonStates();
    }

    private void refreshButtonStates() {
        boolean hired = worker.isHiredFollower();
        UUID ownerId  = worker.getFollowOwnerId();
        boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());

        // --- Hire button ---
        String hireLabel;
        boolean hireEnabled = true;

        if (!hired) {
            hireLabel = I18n.format("travellers.worker.orders.hire", "Hire (2a)");
        } else if (hiredByMe) {
            hireLabel = I18n.format("travellers.worker.orders.unhire", "Dismiss");
        } else {
            hireLabel = I18n.format("travellers.worker.orders.hired_other", "Hired by someone else");
            hireEnabled = false;
        }
        this.hireButton.displayString = hireLabel;
        this.hireButton.enabled = hireEnabled;

        // --- Follow button (only when hired by me) ---
        boolean followActive = worker.isFollowOrderActive(); // synced via your data/packet
        this.followButton.visible = hiredByMe;
        this.followButton.enabled = hiredByMe;

        if (hiredByMe) {
            this.followButton.displayString = followActive
                    ? I18n.format("travellers.worker.orders.follow_on", "Follow: ON")
                    : I18n.format("travellers.worker.orders.follow_off", "Follow: OFF");
        }

        // --- Leader button (only when hired by me) ---
        this.leaderButton.visible = hiredByMe;
        this.leaderButton.enabled = hiredByMe;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == hireButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgHireWorker(worker.getEntityId())
            );
            // ❌ DO NOT close the screen here
            return;
        }

        if (button == followButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgToggleWorkerFollow(worker.getEntityId())
            );
            return;
        }

        if (button == leaderButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgMakeWorkerLeader(worker.getEntityId())
            );
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
                I18n.format("travellers.worker.orders.title", "Orders"),
                8, 6, 0x404040
        );
    }
}
