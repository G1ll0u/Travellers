package com.jubitus.travellers.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/** An item drawn as a small GUI icon at any size. */
@SideOnly(Side.CLIENT)
public final class ItemIcon {

    private static final float ITEM_PX = 16f;

    private ItemIcon() {
    }

    /** As in an inventory slot (blocks in 3D), scaled to a size×size square at (x, y). */
    public static void draw(ItemStack stack, int x, int y, int size) {
        Minecraft mc = Minecraft.getMinecraft();
        // 3D block models hide their back faces (bed legs) only with the depth test on; maps turn it off
        boolean depthWasOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GlStateManager.enableDepth();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(size / ITEM_PX, size / ITEM_PX, 1f);
        RenderHelper.enableGUIStandardItemLighting();
        mc.getRenderItem().renderItemIntoGUI(stack, 0, 0);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.popMatrix();
        if (!depthWasOn) GlStateManager.disableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** The item's flat sprite, which can fade with {@code alpha} (an inventory render cannot). */
    public static void drawFlat(Item item, int x, int y, int size, float alpha) {
        Minecraft mc = Minecraft.getMinecraft();
        TextureAtlasSprite sprite = mc.getRenderItem().getItemModelMesher().getParticleIcon(item);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.color(1f, 1f, 1f, alpha);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buf.pos(x, y + size, 0).tex(sprite.getMinU(), sprite.getMaxV()).endVertex();
        buf.pos(x + size, y + size, 0).tex(sprite.getMaxU(), sprite.getMaxV()).endVertex();
        buf.pos(x + size, y, 0).tex(sprite.getMaxU(), sprite.getMinV()).endVertex();
        buf.pos(x, y, 0).tex(sprite.getMinU(), sprite.getMinV()).endVertex();
        tess.draw();
    }
}
