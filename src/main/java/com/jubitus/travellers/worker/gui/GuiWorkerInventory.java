package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.GuiTravellersContainer;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.worker.inventory.ContainerWorkerInventory;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

/** A worker's gear (centred row) and bag over the player's inventory, Back at the bottom. */
@SideOnly(Side.CLIENT)
public class GuiWorkerInventory extends GuiTravellersContainer {

    private static final int SECTION_GAP = 9;

    private final EntityTravellerWorker worker;
    private GuiButton backButton;
    private int separatorY;

    public GuiWorkerInventory(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerInventory(player.inventory, worker));
        this.worker = worker;
    }

    @Override
    protected String title() {
        return I18n.format("travellers.worker.inventory.title");
    }

    @Override
    protected int frameW() {
        return GRID_W + MapListLayout.PAD * 2;
    }

    @Override
    protected int frameH() {
        return MapListLayout.TITLE_H + MapListLayout.PAD * 2 + bodyH();
    }

    // gear, bag, separator, player inventory, hotbar, Back
    private static int bodyH() {
        return SLOT + ROW_GAP + 2 * SLOT + SECTION_GAP * 2 + 1 + PLAYER_INV_H + ROW_GAP + MapListLayout.BTN_H;
    }

    @Override
    protected void layoutSlots() {
        int x = panel.bodyX, y = panel.bodyY;
        int gear = ContainerWorkerInventory.ARMOR_COUNT;
        placeGrid(0, gear, gear, x + (GRID_W - gear * SLOT) / 2, y);
        y += SLOT + ROW_GAP;
        placeGrid(gear, 18, 9, x, y);
        y += 2 * SLOT + SECTION_GAP;
        separatorY = y;
        y += 1 + SECTION_GAP;
        placePlayerInventory(gear + 18, x, y);
        y += PLAYER_INV_H + ROW_GAP;
        backButton = addButton(new GuiTravellersButton(0, x, y, GRID_W, MapListLayout.BTN_H,
                I18n.format("travellers.gui.back")));
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
        TravellersGuiTheme.drawSeparator(panel.bodyX, separatorY, GRID_W);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backButton) {
            TravellersNetwork.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            return;
        }
        super.actionPerformed(button);
    }
}
