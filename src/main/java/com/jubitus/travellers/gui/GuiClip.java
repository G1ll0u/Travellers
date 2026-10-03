package com.jubitus.travellers.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/** Clips drawing to a GUI-space rectangle (scissor test). Not nestable: end() turns clipping off. */
@SideOnly(Side.CLIENT)
public final class GuiClip {

    private GuiClip() {}

    public static void begin(int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getMinecraft();
        int f = new ScaledResolution(mc).getScaleFactor();
        // Scissor is in window pixels with a bottom-left origin; GUI y maps to exactly y * f from the top.
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * f, mc.displayHeight - (y + h) * f, w * f, h * f);
    }

    public static void end() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
