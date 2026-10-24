package com.jubitus.travellers.worldmap.gui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.function.IntUnaryOperator;

/** A route polyline and dots on a worldmap viewport; scissor is assumed already set. */
@SideOnly(Side.CLIENT)
public final class MapRouteLine {

    private MapRouteLine() {
    }

    /** Vertices are block columns; {@code segmentColor} gives the ARGB of segment i (vertex i to i+1). */
    public static void draw(int mapX, int mapY, int mapW, int mapH, float centerX, float centerZ, float bpp,
                            int[] xs, int[] zs, IntUnaryOperator segmentColor) {
        if (xs.length < 2) return;
        float ox = mapX + mapW / 2f;
        float oy = mapY + mapH / 2f;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.glLineWidth(2.0F);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < xs.length - 1; i++) {
            int c = segmentColor.applyAsInt(i);
            vertex(b, ox + (xs[i] + 0.5f - centerX) / bpp, oy + (zs[i] + 0.5f - centerZ) / bpp, c);
            vertex(b, ox + (xs[i + 1] + 0.5f - centerX) / bpp, oy + (zs[i + 1] + 0.5f - centerZ) / bpp, c);
        }
        t.draw();
        GlStateManager.glLineWidth(1.0F);
        GlStateManager.enableTexture2D();
    }

    /** A black-rimmed square dot of half-size r at screen (x, y). */
    public static void dot(float x, float y, int r, int argb) {
        int ix = Math.round(x), iy = Math.round(y);
        Gui.drawRect(ix - r - 1, iy - r - 1, ix + r + 1, iy + r + 1, 0xFF000000);
        Gui.drawRect(ix - r, iy - r, ix + r, iy + r, argb);
    }

    private static void vertex(BufferBuilder b, float x, float y, int argb) {
        b.pos(x, y, 0).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255).endVertex();
    }
}
