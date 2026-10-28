package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.FramedPanel;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.village.gui.GuiWorkerVillageRelations;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.net.TravellersNetwork;
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

    private FramedPanel panel;

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

        this.panel = new FramedPanel(width, height, 200, 40 + 3 * (MapListLayout.BTN_H + MapListLayout.BTN_GAP) + MapListLayout.PAD);
        this.buttonList.clear();

        int bw = Math.min(160, panel.bodyW);
        int bx = panel.bodyX + (panel.bodyW - bw) / 2;
        int y = panel.bodyY;
        int step = MapListLayout.BTN_H + MapListLayout.BTN_GAP;

        this.enemiesButton = this.addButton(new GuiTravellersButton(0, bx, y, bw, MapListLayout.BTN_H,
                I18n.format("travellers.worker.behavior.player_enemies", "Player enemies")));
        this.villageRelationsButton = this.addButton(new GuiTravellersButton(2, bx, y + step, bw, MapListLayout.BTN_H,
                I18n.format("travellers.worker.behavior.village_relations", "Village relations")));
        this.backButton = this.addButton(new GuiTravellersButton(1, bx, panel.bodyY + panel.bodyH - MapListLayout.BTN_H, bw, MapListLayout.BTN_H,
                I18n.format("travellers.gui.back", "Back")));
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
                TravellersNetwork.CHANNEL.sendToServer(
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

        panel.draw(fontRenderer, I18n.format("travellers.worker.behavior.title", "Behavior"));

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
