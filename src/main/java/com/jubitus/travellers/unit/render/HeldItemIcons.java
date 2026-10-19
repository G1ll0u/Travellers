package com.jubitus.travellers.unit.render;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.pipeline.LightUtil;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Beyond {@code heldItemIconMinDistance}, a flat held item (sword, tool, bow) is drawn as one
 * full-size face in its middle plane, skipping the back face and the hundreds of edge quads
 * RenderItem rebuilds every frame.
 * Built-in (shield) and 3D models keep the vanilla path. Mirrors RenderItem.renderItemModel's GL setup.
 */
@SideOnly(Side.CLIENT)
public final class HeldItemIcons {

    // Generated items' front face sits at z = 8.5/16; this moves it onto the item's middle plane.
    private static final float FRONT_TO_MIDDLE = -0.5F / 16.0F;

    // Per baked model: its full-size front faces (one per layer), empty when it is not a flat item.
    private static final Map<IBakedModel, List<BakedQuad>> FACES = new WeakHashMap<>();

    private HeldItemIcons() {
    }

    /** True if the item was drawn as an icon; false means the caller renders it normally. */
    public static boolean tryRender(EntityLivingBase entity, ItemStack stack,
                                    ItemCameraTransforms.TransformType transform, boolean leftHanded) {
        if (stack.isEmpty() || !RenderLodHelper.isBeyond(entity, TravellersConfig.ENTITY_RENDER_LOD.heldItemIconMinDistance)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        IBakedModel model = mc.getRenderItem().getItemModelWithOverrides(stack, entity.world, entity);
        if (model.isBuiltInRenderer() || model.isGui3d()) return false;
        List<BakedQuad> faces = FACES.computeIfAbsent(model, HeldItemIcons::flatFaces);
        if (faces.isEmpty()) return false;

        TextureManager textures = mc.getTextureManager();
        textures.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        textures.getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).setBlurMipmap(false, false);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableRescaleNormal();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.pushMatrix();
        ForgeHooksClient.handleCameraTransforms(model, transform, leftHanded);
        GlStateManager.translate(-0.5F, -0.5F, -0.5F + FRONT_TO_MIDDLE);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        for (BakedQuad quad : faces) {
            int color = quad.hasTintIndex() ? mc.getItemColors().colorMultiplier(stack, quad.getTintIndex()) | 0xFF000000 : -1;
            LightUtil.renderQuadColor(buffer, quad, color);
        }
        tessellator.draw();

        GlStateManager.cullFace(GlStateManager.CullFace.BACK);
        GlStateManager.popMatrix();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        textures.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        textures.getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).restoreLastBlurMipmap();
        return true;
    }

    private static List<BakedQuad> flatFaces(IBakedModel model) {
        List<BakedQuad> out = new ArrayList<>();
        for (EnumFacing side : EnumFacing.values()) {
            collectFaces(model.getQuads(null, side, 0L), out);
        }
        collectFaces(model.getQuads(null, null, 0L), out);
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    private static void collectFaces(List<BakedQuad> quads, List<BakedQuad> out) {
        for (BakedQuad quad : quads) {
            // Living-entity rendering has culling off, so the front face alone shows from both sides.
            if (quad.getFace() == EnumFacing.SOUTH && spansWholeFace(quad)) {
                out.add(quad);
            }
        }
    }

    // A generated item's layer face covers the whole 0..1 square; edge quads are thin strips.
    private static boolean spansWholeFace(BakedQuad quad) {
        if (!quad.getFormat().getElement(0).isPositionElement()) return false;
        int[] data = quad.getVertexData();
        int stride = quad.getFormat().getIntegerSize();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            float x = Float.intBitsToFloat(data[v * stride]);
            float y = Float.intBitsToFloat(data[v * stride + 1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return minX < 0.01F && maxX > 0.99F && minY < 0.01F && maxY > 0.99F;
    }
}
