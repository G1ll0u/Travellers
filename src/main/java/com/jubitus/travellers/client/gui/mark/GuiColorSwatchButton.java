package com.jubitus.travellers.client.gui.mark;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

public class GuiColorSwatchButton extends GuiButton {

    private int color = 0xFFFFFF;

    public GuiColorSwatchButton(int buttonId, int x, int y, int widthIn, int heightIn) {
        super(buttonId, x, y, widthIn, heightIn, "");
    }

    public void setColor(int color) {
        this.color = color & 0xFFFFFF;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!this.visible) return;

        this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;

        int border = this.enabled ? 0xFFAAAAAA : 0xFF666666;
        int fill = 0xFF000000 | color;

        drawRect(this.x, this.y, this.x + this.width, this.y + this.height, border);
        drawRect(this.x + 1, this.y + 1, this.x + this.width - 1, this.y + this.height - 1, fill);

        if (this.hovered) {
            drawRect(this.x + 2, this.y + 2, this.x + this.width - 2, this.y + this.height - 2, 0x22FFFFFF);
        }
    }
}