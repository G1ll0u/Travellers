package com.jubitus.travellers.debug.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Holds the latest loaded-chunks rectangle ({@link com.jubitus.travellers.debug.net.MsgLoadedChunksMap}) and draws
 * it as a translucent quad over the worldmap, for the highlight on {@code GuiTravellerPopulationMap}.
 */
@SideOnly(Side.CLIENT)
public final class ClientLoadedChunksOverlay {

    private static final int LOADED_ARGB = 0x66FFB000;

    private static DynamicTexture tex;
    private static ResourceLocation loc;
    private static int texW, texH;

    private static int minChunkX, minChunkZ, wChunks, hChunks;
    private static boolean present;

    private ClientLoadedChunksOverlay() {
    }

    /** Client thread only. */
    public static synchronized void accept(int minChunkX, int minChunkZ, int w, int h, byte[] bits) {
        ClientLoadedChunksOverlay.minChunkX = minChunkX;
        ClientLoadedChunksOverlay.minChunkZ = minChunkZ;
        wChunks = w;
        hChunks = h;

        if (tex == null || texW != w || texH != h) {
            if (loc != null) Minecraft.getMinecraft().getTextureManager().deleteTexture(loc);
            tex = new DynamicTexture(w, h);
            loc = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("travellers_loaded_chunks", tex);
            texW = w;
            texH = h;
        }
        int[] data = tex.getTextureData();
        for (int i = 0; i < w * h && i < data.length; i++) {
            boolean loaded = (bits[i >> 3] & (1 << (i & 7))) != 0;
            data[i] = loaded ? LOADED_ARGB : 0;
        }
        tex.updateDynamicTexture();
        present = true;
    }

    public static void clear() {
        present = false;
    }

    /** Same view transform as {@link ClientTerrainDebugOverlay#draw}; scissor is assumed already set. */
    public static void draw(int mapX, int mapY, int mapW, int mapH, float centerX, float centerZ, float bpp) {
        if (!present || loc == null) return;
        float sx0 = mapX + mapW / 2f + (minChunkX * 16 - centerX) / bpp;
        float sy0 = mapY + mapH / 2f + (minChunkZ * 16 - centerZ) / bpp;
        float sx1 = mapX + mapW / 2f + ((minChunkX + wChunks) * 16 - centerX) / bpp;
        float sy1 = mapY + mapH / 2f + ((minChunkZ + hChunks) * 16 - centerZ) / bpp;

        Minecraft.getMinecraft().getTextureManager().bindTexture(loc);
        GlStateManager.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
        GlStateManager.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableBlend();

        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.BufferBuilder b = t.getBuffer();
        b.begin(org.lwjgl.opengl.GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX);
        b.pos(sx0, sy1, 0).tex(0, 1).endVertex();
        b.pos(sx1, sy1, 0).tex(1, 1).endVertex();
        b.pos(sx1, sy0, 0).tex(1, 0).endVertex();
        b.pos(sx0, sy0, 0).tex(0, 0).endVertex();
        t.draw();
    }
}
