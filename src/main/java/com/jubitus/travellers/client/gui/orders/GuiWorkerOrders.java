package com.jubitus.travellers.client.gui.orders;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.choir.MsgWorkerChoirToggle;
import com.jubitus.travellers.server.net.message.home.MsgWorkerGoHome;
import com.jubitus.travellers.server.net.message.leadfollow.MsgClearEscortLeader;
import com.jubitus.travellers.server.net.message.leadfollow.MsgMakeWorkerLeader;
import com.jubitus.travellers.server.net.message.leadfollow.MsgToggleWorkerFollow;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class GuiWorkerOrders extends GuiScreen {

    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker_orders.png");

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton recruitButton;
    private GuiButton followPlayerButton;
    private GuiButton homeButton;
    private GuiButton goHomeButton;
    private GuiButton followButton;
    private GuiButton leaderButton;
    private GuiButton goButton;
    private GuiButton choirButton;
    private GuiButton backButton;

    private int xSize = 176;
    private int ySize = 166;
    private int guiLeft;
    private int guiTop;

    public GuiWorkerOrders(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        int centerX = this.guiLeft + this.xSize / 2;

        int bw = 80;
        int bh = 20;
        int gap = 4;

        int yBase = this.guiTop + 10;

        this.followButton = this.addButton(new GuiButton(
                1,
                centerX - bw - gap / 2,
                yBase + bh + gap,
                bw,
                bh,
                ""
        ));

        this.leaderButton = this.addButton(new GuiButton(
                2,
                centerX + gap / 2,
                yBase + bh + gap,
                bw,
                bh,
                I18n.format("travellers.worker.orders.make_leader")
        ));

        this.recruitButton = this.addButton(new GuiButton(
                3,
                centerX - bw / 2,
                yBase + (bh + gap) * 2,
                bw,
                bh,
                I18n.format("travellers.worker.orders.recruit")
        ));

        this.homeButton = this.addButton(new GuiButton(
                6,
                centerX - bw - gap / 2,
                yBase + (bh + gap) * 3,
                bw,
                bh,
                I18n.format("travellers.worker.orders.home")
        ));

        this.goHomeButton = this.addButton(new GuiButton(
                7,
                centerX + gap / 2,
                yBase + (bh + gap) * 3,
                bw,
                bh,
                I18n.format("travellers.worker.orders.go_home")
        ));

        this.followPlayerButton = this.addButton(new GuiButton(
                4,
                centerX - bw / 2,
                yBase + (bh + gap) * 4,
                bw,
                bh,
                I18n.format("travellers.worker.orders.follow_player")
        ));

        this.goButton = this.addButton(new GuiButton(
                8,
                centerX - bw / 2,
                yBase + (bh + gap) * 5,
                bw,
                bh,
                I18n.format("travellers.worker.orders.go")
        ));

        this.choirButton = this.addButton(new GuiButton(
                10,
                centerX - bw / 2,
                yBase + (bh + gap) * 6,
                bw,
                bh,
                ""
        ));

        this.backButton = this.addButton(new GuiButton(
                9,
                centerX - bw / 2,
                yBase + (bh + gap) * 7,
                bw,
                bh,
                I18n.format("travellers.gui.back")
        ));

        refreshButtonStates();
    }

    private void refreshButtonStates() {
        boolean hired = worker.isHiredFollower();
        UUID ownerId = worker.getFollowOwnerId();
        boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());
        int leaderCount = worker.getLeaderFollowerCount();

        this.goButton.visible = hiredByMe;
        this.goButton.enabled = hiredByMe;

        boolean followActive = worker.isFollowOrderActive();
        this.followButton.visible = hiredByMe;
        this.followButton.enabled = hiredByMe;

        if (hiredByMe) {
            this.followButton.displayString = followActive
                    ? I18n.format("travellers.worker.orders.follow_on")
                    : I18n.format("travellers.worker.orders.follow_off");
        }

        boolean followingLeader = worker.hasEscortLeader();

        this.followPlayerButton.visible = hiredByMe;
        this.followPlayerButton.enabled = hiredByMe && followingLeader;

        if (hiredByMe) {
            this.followPlayerButton.displayString = followingLeader
                    ? I18n.format("travellers.worker.orders.follow_player")
                    : I18n.format("travellers.worker.orders.following_player");
        }

        this.homeButton.visible = hiredByMe;
        this.homeButton.enabled = hiredByMe;

        boolean hasHome = worker.hasAssignedHomeClient();
        this.goHomeButton.visible = hiredByMe;
        this.goHomeButton.enabled = hiredByMe && hasHome;
        this.goHomeButton.displayString = hasHome
                ? I18n.format("travellers.worker.orders.go_home")
                : I18n.format("travellers.worker.orders.go_home_no_home");

        this.leaderButton.visible = hiredByMe;
        this.leaderButton.enabled = hiredByMe;

        if (hiredByMe) {
            if (leaderCount > 0) {
                this.leaderButton.displayString = I18n.format("travellers.worker.orders.leader_count", leaderCount);
            } else {
                this.leaderButton.displayString = I18n.format("travellers.worker.orders.make_leader");
            }
        }

        this.recruitButton.visible = hiredByMe;
        this.recruitButton.enabled = hiredByMe;

        boolean choirActive = hiredByMe && worker.isWorkerChoirActive();
        this.choirButton.visible = hiredByMe;
        this.choirButton.enabled = hiredByMe;
        if (hiredByMe) {
            this.choirButton.displayString = choirActive
                    ? I18n.format("travellers.worker.orders.choir_stop")
                    : I18n.format("travellers.worker.orders.choir_start");
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        refreshButtonStates();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB)
            );
            return;
        }

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

        if (button == choirButton) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerChoirToggle(worker.getEntityId()));
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        GlStateManager.color(1F, 1F, 1F, 1F);
        mc.getTextureManager().bindTexture(TEX);
        this.drawTexturedModalRect(guiLeft, guiTop, 0, 0, this.xSize, this.ySize);

        drawOrdersTitle();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawOrdersTitle() {
        String title = TextFormatting.BOLD.toString()
                + TextFormatting.UNDERLINE
                + I18n.format("travellers.worker.orders.title");

        int titleWidth = this.fontRenderer.getStringWidth(title);
        float scale = 1.15F;

        GlStateManager.pushMatrix();
        GlStateManager.translate(
                this.guiLeft + (this.xSize / 2F) - ((titleWidth * scale) / 2F),
                this.guiTop + 12,
                0F
        );
        GlStateManager.scale(scale, scale, 1F);
        this.fontRenderer.drawString(title, 0, 0, 0xf9cbae);
        GlStateManager.popMatrix();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}