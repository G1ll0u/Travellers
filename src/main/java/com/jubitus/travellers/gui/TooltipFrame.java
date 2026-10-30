package com.jubitus.travellers.gui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** The themed box of the map tooltips (Barrack, Stable, settlement): beside the cursor, kept on screen. */
@SideOnly(Side.CLIENT)
public final class TooltipFrame {

    public static final int PAD = 5;
    public static final int LINE = 11;
    // Above tooltips (z 300), like the group summary.
    private static final float Z = 350F;

    private TooltipFrame() {
    }

    /** Draws a w×h box near the mouse and returns its top-left {x, y}; draw inside, then call {@link #close()}. */
    public static int[] open(int w, int h, int mouseX, int mouseY, int screenW, int screenH) {
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + w > screenW - 2) x = mouseX - w - 12;
        if (y + h > screenH - 2) y = screenH - h - 2;
        if (y < 2) y = 2;

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0F, 0F, Z);

        Gui.drawRect(x, y, x + w, y + h, 0xF0100C08);
        TravellersGuiTheme.drawBorder(x, y, w, h, TravellersGuiTheme.COL_BORDER);
        return new int[]{x, y};
    }

    public static void close() {
        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }
}
