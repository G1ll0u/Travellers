package com.jubitus.travellers.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Holds the most recent terrain-grid debug tile ({@link com.jubitus.travellers.server.net.message.map.MsgTerrainDebugMap})
 * and draws it as a stretched quad, for the terrain overlay on {@code GuiTravellerPopulationMap}.
 * Debug only; single instance, replaced whenever a fresh tile arrives.
 */
@SideOnly(Side.CLIENT)
public final class ClientTerrainDebugOverlay {

    private static DynamicTexture tex;
    private static ResourceLocation loc;
    private static int texW, texH;

    private static int minCellX, minCellZ, wCells, hCells;
    private static byte mode = -1;
    private static boolean present;

    private ClientTerrainDebugOverlay() {
    }

    /** Client thread only. */
    public static synchronized void accept(int minCellX, int minCellZ, int w, int h, byte mode, int[] argb) {
        ClientTerrainDebugOverlay.minCellX = minCellX;
        ClientTerrainDebugOverlay.minCellZ = minCellZ;
        ClientTerrainDebugOverlay.wCells = w;
        ClientTerrainDebugOverlay.hCells = h;
        ClientTerrainDebugOverlay.mode = mode;

        if (tex == null || texW != w || texH != h) {
            if (loc != null) Minecraft.getMinecraft().getTextureManager().deleteTexture(loc);
            tex = new DynamicTexture(w, h);
            loc = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("travellers_terrain_debug", tex);
            texW = w;
            texH = h;
        }
        int[] data = tex.getTextureData();
        System.arraycopy(argb, 0, data, 0, Math.min(argb.length, data.length));
        tex.updateDynamicTexture();
        present = true;
    }

    public static boolean hasDataFor(byte wantMode) {
        return present && mode == wantMode && loc != null;
    }

    public static void clear() {
        present = false;
        mode = -1;
    }

    /**
     * Draws the current tile into the map viewport. {@code (mapX,mapY,mapW,mapH)} is the panel rect;
     * {@code centerX/Z} and {@code bpp} are its live view transform. Scissor is assumed already set.
     */
    public static void draw(int mapX, int mapY, int mapW, int mapH, float centerX, float centerZ, float bpp) {
        if (!present || loc == null) return;
        int wx0 = minCellX * 16;
        int wz0 = minCellZ * 16;
        int wx1 = (minCellX + wCells) * 16;
        int wz1 = (minCellZ + hCells) * 16;

        float sx0 = mapX + mapW / 2f + (wx0 - centerX) / bpp;
        float sy0 = mapY + mapH / 2f + (wz0 - centerZ) / bpp;
        float sx1 = mapX + mapW / 2f + (wx1 - centerX) / bpp;
        float sy1 = mapY + mapH / 2f + (wz1 - centerZ) / bpp;

        Minecraft.getMinecraft().getTextureManager().bindTexture(loc);
        // Crisp cells.
        GlStateManager.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
        GlStateManager.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend(); // opaque: it replaces the worldmap

        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.BufferBuilder b = t.getBuffer();
        b.begin(org.lwjgl.opengl.GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX);
        b.pos(sx0, sy1, 0).tex(0, 1).endVertex();
        b.pos(sx1, sy1, 0).tex(1, 1).endVertex();
        b.pos(sx1, sy0, 0).tex(1, 0).endVertex();
        b.pos(sx0, sy0, 0).tex(0, 0).endVertex();
        t.draw();

        GlStateManager.enableBlend();
    }
}
