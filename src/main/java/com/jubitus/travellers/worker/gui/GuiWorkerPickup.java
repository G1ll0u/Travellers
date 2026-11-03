package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.GuiTravellersContainer;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.worker.inventory.ContainerWorker;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.List;

/**
 * Back from a transport trip: the cargo (unsold goods, coins) over the player's inventory, and Take all.
 * The player can only take; once the cargo is empty the button becomes Done and the trip ends on close.
 */
@SideOnly(Side.CLIENT)
public class GuiWorkerPickup extends GuiTravellersContainer {

    private static final int CARGO_SLOTS = 18;
    private static final int LINE_H = 10;
    private static final int SECTION_GAP = 9;

    private final ContainerWorker container;
    private final boolean unsold;
    private List<String> summary;
    private GuiButton takeButton;
    private int separatorY;

    public GuiWorkerPickup(EntityPlayer player, EntityTravellerWorker worker, boolean unsold) {
        this(new ContainerWorker(player.inventory, worker, true), unsold);
    }

    private GuiWorkerPickup(ContainerWorker container, boolean unsold) {
        super(container);
        this.container = container;
        this.unsold = unsold;
    }

    @Override
    protected String title() {
        return I18n.format("travellers.worker.pickup.title");
    }

    @Override
    protected int frameW() {
        return GRID_W + MapListLayout.PAD * 2;
    }

    @Override
    protected int frameH() {
        summary = fontRenderer.listFormattedStringToWidth(
                I18n.format(unsold ? "travellers.worker.pickup.unsold" : "travellers.worker.pickup.earnings"), GRID_W);
        return MapListLayout.TITLE_H + MapListLayout.PAD * 2 + summary.size() * LINE_H + ROW_GAP
                + 2 * SLOT + SECTION_GAP * 2 + 1 + PLAYER_INV_H + ROW_GAP + MapListLayout.BTN_H;
    }

    @Override
    protected void layoutSlots() {
        int x = panel.bodyX, y = panel.bodyY + summary.size() * LINE_H + ROW_GAP;
        placeGrid(0, CARGO_SLOTS, 9, x, y);
        y += 2 * SLOT + SECTION_GAP;
        separatorY = y;
        y += 1 + SECTION_GAP;
        placePlayerInventory(CARGO_SLOTS, x, y);
        y += PLAYER_INV_H + ROW_GAP;
        takeButton = addButton(new GuiTravellersButton(0, x, y, GRID_W, MapListLayout.BTN_H, ""));
        updateButton();
    }

    private void updateButton() {
        takeButton.displayString = I18n.format(container.isCargoEmpty() ? "travellers.worker.pickup.done" : "travellers.worker.pickup.take_all");
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        updateButton();
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
        for (int i = 0; i < summary.size(); i++) {
            fontRenderer.drawString(summary.get(i), panel.bodyX, panel.bodyY + i * LINE_H, TravellersGuiTheme.COL_TEXT);
        }
        TravellersGuiTheme.drawSeparator(panel.bodyX, separatorY, GRID_W);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button != takeButton) {
            super.actionPerformed(button);
        } else if (container.isCargoEmpty()) {
            mc.player.closeScreen();
        } else {
            mc.playerController.sendEnchantPacket(container.windowId, ContainerWorker.TAKE_ALL);
        }
    }
}
