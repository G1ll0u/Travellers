package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.client.gui.mark.GuiColorSwatchButton;
import com.jubitus.travellers.client.gui.mark.GuiWorkerMarkEditor;
import com.jubitus.travellers.client.gui.worldmap.GuiPlayerWorldMap;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.workerplayer.MsgHireWorker;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.workerplayer.MsgRequestServerCosts;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.io.IOException;
import java.util.UUID;


@SideOnly(Side.CLIENT)
public class GuiWorkerHub extends GuiScreen implements ITravellersGui {

    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker_hub.png");

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private GuiButton transportButton;
    private GuiButton followButton;
    private GuiButton inventoryButton;
    private GuiButton behaviorButton;
    private GuiButton hireButton;
    private GuiButton mapButton;
    private GuiButton backButton;
    private GuiButton formationButton;
    private GuiButton barracksButton;
    private GuiColorSwatchButton markButton;

    private final ButtonTooltipHelper tooltips = new ButtonTooltipHelper();

    private int xSize = 176;
    private int ySize = 142;
    private int guiLeft;
    private int guiTop;

    public GuiWorkerHub(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        int x = this.guiLeft;
        int y = this.guiTop;

        this.buttonList.clear();

        int bw = 60;
        int bh = 20;
        int gap = 4;

        int totalW = bw * 3 + gap * 2;
        int left = x + (this.xSize - totalW) / 2;
        int buttonsY = y + 40;

        this.transportButton = this.addButton(new GuiButton(
                0,
                left,
                buttonsY,
                bw,
                bh,
                I18n.format("travellers.worker.hub.transport")
        ));

        this.followButton = this.addButton(new GuiButton(
                1,
                left + bw + gap,
                buttonsY,
                bw,
                bh,
                I18n.format("travellers.worker.hub.follow")
        ));

        this.inventoryButton = this.addButton(new GuiButton(
                2,
                left + (bw + gap) * 2,
                buttonsY,
                bw,
                bh,
                I18n.format("travellers.worker.hub.inventory")
        ));

        int row2Y = buttonsY + bh + 6;
        int centerX = x + this.xSize / 2;

        this.hireButton = this.addButton(new GuiButton(
                4,
                centerX - bw - gap / 2,
                row2Y,
                bw,
                bh,
                ""
        ));

        this.behaviorButton = this.addButton(new GuiButton(
                3,
                centerX + gap / 2,
                row2Y,
                bw,
                bh,
                I18n.format("travellers.worker.hub.behavior")
        ));

        int row3Y = row2Y + bh + 6;
        int left3 = x + (this.xSize - totalW) / 2;

        this.formationButton = this.addButton(new GuiButton(
                5,
                left3,
                row3Y,
                bw,
                bh,
                I18n.format("travellers.worker.hub.formation")
        ));

        this.barracksButton = this.addButton(new GuiButton(
                9,
                left3 + bw + gap,
                row3Y,
                bw,
                bh,
                I18n.format("travellers.worker.hub.barracks")
        ));

        this.mapButton = this.addButton(new GuiButton(
                6,
                left3 + (bw + gap) * 2,
                row3Y,
                bw,
                bh,
                I18n.format("travellers.worker.hub.map")
        ));

        this.backButton = this.addButton(new GuiButton(
                7,
                centerX - bw / 2,
                row3Y + bh + 6,
                bw,
                bh,
                I18n.format("travellers.gui.back")
        ));

        this.markButton = this.addButton(new GuiColorSwatchButton(
                8,
                x + this.xSize - 22,
                y + 8,
                14,
                14
        ));

        tooltips.set(transportButton, "travellers.worker.hub.transport.tooltip");
        tooltips.set(formationButton, "travellers.worker.hub.formation.tooltip");
        tooltips.set(barracksButton, "travellers.worker.hub.barracks.tooltip");

        refreshButtonStates();
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestServerCosts());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        refreshButtonStates();
    }

    private void refreshButtonStates() {
        boolean hired = worker.isHiredFollower();
        UUID ownerId = worker.getFollowOwnerId();
        boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());

        String hireLabel;
        boolean hireEnabled = true;

        if (!hired) {
            int cost = TravellersDerivedConfig.workerHireCostDeniersServerCache;
            String costStr = cost >= 0 ? MillCommonUtilities.getShortPrice(cost) : "?";
            hireLabel = I18n.format("travellers.worker.orders.hire", costStr);
        } else if (hiredByMe) {
            hireLabel = I18n.format("travellers.worker.orders.unhire");
        } else {
            hireLabel = I18n.format("travellers.worker.orders.hired_other");
            hireEnabled = false;
        }

        if (hireButton != null) {
            hireButton.displayString = hireLabel;
            hireButton.enabled = hireEnabled;
            hireButton.visible = true;
        }

        if (transportButton != null) {
            transportButton.visible = true;
            transportButton.enabled = hiredByMe;
        }

        if (inventoryButton != null) {
            inventoryButton.visible = true;
            inventoryButton.enabled = hiredByMe;
        }

        if (followButton != null) {
            followButton.visible = true;
            followButton.enabled = hiredByMe;
        }

        if (formationButton != null) {
            formationButton.visible = true;
            formationButton.enabled = hiredByMe;
        }

        if (barracksButton != null) {
            barracksButton.visible = true;
            barracksButton.enabled = hiredByMe;
        }

        if (behaviorButton != null) {
            behaviorButton.visible = true;
            behaviorButton.enabled = hiredByMe;
        }

        if (mapButton != null) {
            mapButton.visible = true;
            mapButton.enabled = hiredByMe;
        }

        if (markButton != null) {
            markButton.visible = true;
            markButton.enabled = hiredByMe;
            markButton.setColor(worker.getOwnerMarkColor());
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backButton) {
            mc.player.closeScreen();
            return;
        }
        if (button == transportButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.TRANSPORT)
            );
            return;
        }
        if (button == formationButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.FORMATION)
            );
            return;
        }
        if (button == barracksButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HOME)
            );
            return;
        }

        if (button == mapButton) {
            mc.displayGuiScreen(new GuiPlayerWorldMap(player, worker.getEntityId()));
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
        if (button == markButton) {
            mc.displayGuiScreen(new GuiWorkerMarkEditor(this, player, worker));
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

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (markButton != null && markButton.isMouseOver()) {
            drawHoveringText(
                    java.util.Arrays.asList(
                            I18n.format("travellers.worker.hub.mark_button"),
                            I18n.format("travellers.worker.hub.mark_button.desc")
                    ),
                    mouseX,
                    mouseY
            );
        }
        tooltips.render(mouseX, mouseY, this.width, this.height);
        com.jubitus.travellers.client.notify.NotificationOverlayRenderer.draw(new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}