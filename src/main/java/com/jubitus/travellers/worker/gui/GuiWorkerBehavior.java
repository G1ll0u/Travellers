package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.client.gui.GuiTravellersButton;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.village.gui.GuiWorkerVillageRelations;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Entry point for the account-wide behavior settings (declared player enemies, village
 * relations). Neither sub-screen needs a specific worker anymore — {@code worker} is kept only
 * so the "Back" button can return to that worker's Hub when this was opened from one.
 */
@SideOnly(Side.CLIENT)
public class GuiWorkerBehavior extends GuiScreen implements ITravellersGui {

    private final EntityPlayer player;
    @Nullable private final EntityTravellerWorker worker;
    @Nullable private final GuiScreen returnTo;

    private GuiButton villageRelationsButton;
    private GuiButton enemiesButton;
    private GuiButton backButton;

    private final int xSize = 176;
    private final int ySize = 120;
    private int guiLeft;
    private int guiTop;

    public GuiWorkerBehavior(EntityPlayer player, @Nullable GuiScreen returnTo) {
        this(player, null, returnTo);
    }

    public GuiWorkerBehavior(EntityPlayer player, @Nullable EntityTravellerWorker worker, @Nullable GuiScreen returnTo) {
        this.player = player;
        this.worker = worker;
        this.returnTo = returnTo;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        this.buttonList.clear();

        int centerX = this.guiLeft + this.xSize / 2;
        int yBase = this.guiTop + 40;

        this.enemiesButton = this.addButton(new GuiTravellersButton(
                0,
                centerX - 60,
                yBase,
                120,
                20,
                I18n.format("travellers.worker.behavior.player_enemies", "Player enemies")
        ));
        this.backButton = this.addButton(new GuiTravellersButton(
                1,
                centerX - 60,
                yBase + 26,
                120,
                20,
                I18n.format("travellers.gui.back", "Back")
        ));
        this.villageRelationsButton = this.addButton(new GuiTravellersButton(
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
            this.mc.displayGuiScreen(new GuiWorkerEnemies(this, this.player));
            return;
        }

        if (button == this.backButton) {
            if (returnTo != null) {
                mc.displayGuiScreen(returnTo);
            } else if (worker != null) {
                TravellerFollowNet.CHANNEL.sendToServer(
                        new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB)
                );
            } else {
                mc.displayGuiScreen(null);
            }
            return;
        }

        if (button == this.villageRelationsButton) {
            this.mc.displayGuiScreen(new GuiWorkerVillageRelations(this.player, this.worker, this));
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        TravellersGuiTheme.drawPanel(this.guiLeft, this.guiTop, this.xSize, this.ySize);

        this.fontRenderer.drawString(
                I18n.format("travellers.worker.behavior.title", "Behavior"),
                this.guiLeft + 8,
                this.guiTop + 6,
                TravellersGuiTheme.COL_TEXT
        );

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
