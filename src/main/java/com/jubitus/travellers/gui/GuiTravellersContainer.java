package com.jubitus.travellers.gui;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Base of every screen with item slots: the shared {@link FramedPanel} (frame + title) is the container's
 * whole area, every slot gets the theme's slot box, and the screen places its slots in {@link #layoutSlots}.
 */
@SideOnly(Side.CLIENT)
public abstract class GuiTravellersContainer extends GuiContainer implements ITravellersGui {

    protected static final int SLOT = TravellersGuiTheme.SLOT;
    protected static final int ROW_GAP = 4;
    /** Width of a 9-column slot grid (the player's inventory). */
    protected static final int GRID_W = 9 * SLOT;
    /** The player's inventory block: three rows, a gap, the hotbar. */
    protected static final int PLAYER_INV_H = 3 * SLOT + ROW_GAP + SLOT;
    protected FramedPanel panel;

    protected GuiTravellersContainer(Container container) {
        super(container);
    }

    protected abstract String title();

    /** Wanted frame size; the frame is the container's area, so clicks inside it never drop the held item. */
    protected abstract int frameW();

    protected abstract int frameH();

    /** Places the slots and adds the buttons; {@link #panel} is ready. */
    protected abstract void layoutSlots();

    @Override
    public void initGui() {
        panel = new FramedPanel(width, height, frameW(), frameH());
        xSize = panel.w;
        ySize = panel.h;
        super.initGui();
        buttonList.clear();
        layoutSlots();
    }

    /**
     * Places {@code count} slots from {@code first} in a grid of {@code cols} columns whose boxes start at
     * screen (boxX, boxY).
     */
    protected void placeGrid(int first, int count, int cols, int boxX, int boxY) {
        for (int i = 0; i < count; i++) {
            Slot s = inventorySlots.inventorySlots.get(first + i);
            s.xPos = boxX + 1 + (i % cols) * SLOT - guiLeft;
            s.yPos = boxY + 1 + (i / cols) * SLOT - guiTop;
        }
    }

    /** Places the player's 27 inventory slots then the 9 hotbar slots (container order) from screen (boxX, boxY). */
    protected void placePlayerInventory(int first, int boxX, int boxY) {
        placeGrid(first, 27, 9, boxX, boxY);
        placeGrid(first + 27, 9, 9, boxX, boxY + 3 * SLOT + ROW_GAP);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        panel.draw(fontRenderer, title());
        for (Slot s : inventorySlots.inventorySlots) TravellersGuiTheme.drawSlot(guiLeft + s.xPos, guiTop + s.yPos);
    }
}
