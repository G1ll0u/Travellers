package com.jubitus.travellers.marker.render;

import com.jubitus.travellers.unit.render.RenderLodHelper;
import com.jubitus.travellers.unit.render.RenderTraveller;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import com.jubitus.travellers.marker.WorkerMarkStyle;

import javax.annotation.Nullable;


public class LayerTravellerArmband implements LayerRenderer<EntityTraveller> {

    private final RenderTraveller renderer;
    // Single shared instance per renderer — reused for all entities each frame.
    private final ModelTravellerArmband model = new ModelTravellerArmband();

    public LayerTravellerArmband(RenderTraveller renderer) {
        this.renderer = renderer;
    }

    @Override
    public void doRenderLayer(EntityTraveller entity,
                              float limbSwing,
                              float limbSwingAmount,
                              float partialTicks,
                              float ageInTicks,
                              float netHeadYaw,
                              float headPitch,
                              float scale) {

        ResourceLocation texture = markTexture(entity);
        if (texture == null) return;

        if (renderer.isRenderingBaked()) return;

        if (RenderLodHelper.isBeyond(entity, TravellersConfig.ENTITY_RENDER_LOD.armbandLayerMaxDistance)) return;

        int color = markColor(entity);

        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8)  & 0xFF) / 255.0F;
        float b = (color         & 0xFF) / 255.0F;

        model.setModelAttributes((ModelBiped) renderer.getMainModel());
        model.setLivingAnimations(entity, limbSwing, limbSwingAmount, partialTicks);

        renderer.bindTexture(texture);

        // Lit like the body it sits on, so it matches the baked skin's shading.
        // Explicit blend func — robust against other mods that may have changed the state.
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);

        // Polygon offset as a secondary Z-fighting guard (primary guard is the 0.75F
        // inflation in ModelTravellerArmband, which physically separates the meshes).
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1.0F, -10.0F);

        GlStateManager.color(r, g, b, 1.0F);

        model.render(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale);

        // Restore GL state in strict reverse order.
        GlStateManager.doPolygonOffset(0.0F, 0.0F);
        GlStateManager.disablePolygonOffset();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** The armband this unit wears, or null if none: a hired worker's owner mark, or the raider mark. */
    @Nullable
    public static ResourceLocation markTexture(EntityTraveller entity) {
        if (entity instanceof EntityTravellerRaider) {
            return WorkerMarkStyle.PLAIN.getTexture(false);
        }
        if (entity instanceof EntityTravellerWorker) {
            EntityTravellerWorker worker = (EntityTravellerWorker) entity;
            if (worker.shouldRenderOwnerMark()) {
                return worker.getOwnerMarkStyle().getTexture(worker.isVisualLeader());
            }
        }
        return null;
    }

    /** RGB of the armband returned by {@link #markTexture}. */
    public static int markColor(EntityTraveller entity) {
        return entity instanceof EntityTravellerWorker
                ? ((EntityTravellerWorker) entity).getOwnerMarkColor()
                : TravellersDerivedConfig.raiderMarkColor();
    }

    @Override
    public boolean shouldCombineTextures() {
        return false;
    }
}