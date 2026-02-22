package com.jubitus.travellers.client.gui.orders;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import com.jubitus.travellers.utils.net.message.*;
import com.jubitus.travellers.utils.net.message.home.MsgWorkerGoHome;
import com.jubitus.travellers.utils.net.message.leadfollow.MsgClearEscortLeader;
import com.jubitus.travellers.utils.net.message.leadfollow.MsgMakeWorkerLeader;
import com.jubitus.travellers.utils.net.message.leadfollow.MsgToggleWorkerFollow;
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
    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker_follow.png");
    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private GuiButton recruitButton;
    private GuiButton followPlayerButton; // NEW
    private GuiButton homeButton;   // NEW
    private GuiButton goHomeButton; // NEW
    private GuiButton followButton;
    private GuiButton leaderButton;
    private GuiButton goButton;

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
        int yBase = this.guiTop + 32;

        int bw = 80;
        int bh = 20;
        int gap = 4;

        // Create buttons ONCE. Logic (text/visible/enabled) will be done in updateScreen()

        this.goButton = this.addButton(new GuiButton(
                8, centerX - bw / 2, yBase + (bh + gap) * 5, bw, bh, "Go..."
        ));


        this.followButton = this.addButton(new GuiButton(
                1, centerX - bw - gap / 2, yBase + bh + gap, bw, bh, ""
        ));

        this.leaderButton = this.addButton(new GuiButton(
                2, centerX + gap / 2, yBase + bh + gap, bw, bh,
                I18n.format("travellers.worker.orders.make_leader", "Make leader")
        ));
        this.followPlayerButton = this.addButton(new GuiButton(
                4, centerX - bw / 2, yBase + (bh + gap) * 4, bw, bh,
                I18n.format("travellers.worker.orders.follow_player", "Follow player")
        ));


        this.recruitButton = this.addButton(new GuiButton(
                3, centerX - bw / 2, yBase + (bh + gap) * 2, bw, bh,
                I18n.format("travellers.worker.orders.recruit", "Recruit")
        ));
        this.homeButton = this.addButton(new GuiButton(
                6, centerX - bw - gap / 2, yBase + (bh + gap) * 3, bw, bh, "Home"
        ));

        this.goHomeButton = this.addButton(new GuiButton(
                7, centerX + gap / 2, yBase + (bh + gap) * 3, bw, bh, "Go home"
        ));


        // First state update
        refreshButtonStates();
    }

    private void refreshButtonStates() {
        boolean hired = worker.isHiredFollower();
        UUID ownerId = worker.getFollowOwnerId();
        boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());
        int leaderCount = worker.getLeaderFollowerCount();
        this.goButton.visible = hiredByMe;
        this.goButton.enabled = hiredByMe;


        // --- Follow button (only when hired by me) ---
        boolean followActive = worker.isFollowOrderActive(); // synced via your data/packet
        this.followButton.visible = hiredByMe;
        this.followButton.enabled = hiredByMe;

        if (hiredByMe) {
            this.followButton.displayString = followActive
                    ? I18n.format("travellers.worker.orders.follow_on", "Follow: ON")
                    : I18n.format("travellers.worker.orders.follow_off", "Follow: OFF");
        }

        boolean followingLeader = worker.hasEscortLeader();

        this.followPlayerButton.visible = hiredByMe;
        this.followPlayerButton.enabled = hiredByMe && followingLeader;

        if (hiredByMe) {
            this.followPlayerButton.displayString = followingLeader
                    ? I18n.format("travellers.worker.orders.follow_player", "Follow player")
                    : I18n.format("travellers.worker.orders.following_player", "Following player");
        }

// --- Home buttons ---
        this.homeButton.visible = hiredByMe;
        this.homeButton.enabled = hiredByMe;

// You need a worker-side boolean for "has assigned home" (we add it in section 8)
        boolean hasHome = worker.hasAssignedHomeClient(); // implement this (client-synced)
        this.goHomeButton.visible = hiredByMe;
        this.goHomeButton.enabled = hiredByMe && hasHome;

        this.goHomeButton.displayString = hasHome ? "Go home" : "Go home (no home)";

        // --- Leader button (only when hired by me) ---
        this.leaderButton.visible = hiredByMe;
        this.leaderButton.enabled = hiredByMe;

        if (hiredByMe) {
            if (leaderCount > 0) {
                this.leaderButton.displayString = "Leader (" + leaderCount + ")";
            } else {
                this.leaderButton.displayString = "Make leader";
            }
        }

        this.recruitButton.visible = hiredByMe;
        this.recruitButton.enabled = hiredByMe; // keep simple; server will reject if busy

    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.orders.title", "Orders"),
                8, 6, 0x404040
        );
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
        // Every tick, recompute what should be visible / labels
        refreshButtonStates();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        System.out.println("[GuiWorkerOrders] actionPerformed id=" + button.id + " text=" + button.displayString);
        if (button == followButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgToggleWorkerFollow(worker.getEntityId())
            );
            return;
        }

        if (button == goButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.GO)
            );
            return;
        }


        if (button == leaderButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgMakeWorkerLeader(worker.getEntityId())
            );
            return;
        }
        if (button == recruitButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.RECRUIT)
            );
            return;
        }
        if (button == followPlayerButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgClearEscortLeader(worker.getEntityId())
            );
            return;
        }
        if (button == homeButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HOME)
            );
            return;
        }
        if (button == goHomeButton) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerGoHome(worker.getEntityId()));
            return;
        }

        super.actionPerformed(button);
    }
}
