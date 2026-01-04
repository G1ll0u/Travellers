package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.message.MsgHireWorker;
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
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class GuiWorkerHub extends GuiContainer {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton transportButton;
    private GuiButton followButton;
    private GuiButton inventoryButton; // NEW
    private GuiButton behaviorButton;
    private GuiButton hireButton; // NEW



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

        this.transportButton = this.addButton(new GuiButton(0, left, buttonsY, bw, bh,
                I18n.format("travellers.worker.hub.transport", "Transport")));

        this.followButton = this.addButton(new GuiButton(1, left + bw + gap, buttonsY, bw, bh,
                I18n.format("travellers.worker.hub.follow", "Follow")));

        this.inventoryButton = this.addButton(new GuiButton(2, left + (bw + gap) * 2, buttonsY, bw, bh,
                I18n.format("travellers.worker.hub.inventory", "Inventory")));

        // NEW: Behavior (second row, centered)
// Row 2: Hire + Behavior
        int row2Y = buttonsY + bh + 6;
        int centerX = x + this.xSize / 2;

// Hire (left)
        this.hireButton = this.addButton(new GuiButton(
                4,
                centerX - bw - gap / 2,
                row2Y,
                bw,
                bh,
                "" // label set in refreshButtonStates()
        ));

// Behavior (right)
        this.behaviorButton = this.addButton(new GuiButton(
                3,
                centerX + gap / 2,
                row2Y,
                bw,
                bh,
                I18n.format("travellers.worker.hub.behavior", "Behavior")
        ));

        refreshButtonStates();
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
        if (button == behaviorButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.BEHAVIOR)
            );
            return;
        }
        if (button == hireButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgHireWorker(worker.getEntityId())
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
    public void updateScreen() {
        super.updateScreen();
        refreshButtonStates();
    }

    private void refreshButtonStates() {
        boolean hired = worker.isHiredFollower();
        UUID ownerId  = worker.getFollowOwnerId();
        boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());

        // --- Hire button label + enable ---
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

        if (hireButton != null) {
            hireButton.displayString = hireLabel;
            hireButton.enabled = hireEnabled;
            hireButton.visible = true;
        }

        // --- Buttons that require being hired by me ---
        if (transportButton != null) {
            transportButton.visible = true;
            transportButton.enabled = hiredByMe;
        }

        if (inventoryButton != null) {
            inventoryButton.visible = true;
            inventoryButton.enabled = hiredByMe; // ✅ now it greys out
        }

        if (followButton != null) {
            followButton.visible = true;
            followButton.enabled = hiredByMe;
        }

        // ✅ Behavior stays visible, just disabled if not hired
        if (behaviorButton != null) {
            behaviorButton.visible = true;
            behaviorButton.enabled = hiredByMe;
        }
    }




}
