package com.jubitus.travellers.client.render.marker;

import com.jubitus.travellers.client.render.RenderTraveller;
import com.jubitus.travellers.client.render.models.ModelSimpleHumanoid;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;


public class LayerTravellerArmband implements LayerRenderer<EntityTraveller> {

    private final RenderTraveller renderer;
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

        if (!(entity instanceof EntityTravellerWorker)) {
            return;
        }

        EntityTravellerWorker worker = (EntityTravellerWorker) entity;
        if (!worker.shouldRenderOwnerMark()) {
            return;
        }

        WorkerMarkStyle style = worker.getOwnerMarkStyle();
        int color = worker.getOwnerMarkColor();

        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;

        ModelBiped mainModel = (ModelBiped) renderer.getMainModel();

        model.setModelAttributes(mainModel);
        model.setLivingAnimations(entity, limbSwing, limbSwingAmount, partialTicks);

        renderer.bindTexture(style.getTexture(worker.isVisualLeader()));

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.color(r, g, b, 1.0F);

        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1.0F, -10.0F);

        model.render(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale);

        GlStateManager.doPolygonOffset(0.0F, 0.0F);
        GlStateManager.disablePolygonOffset();

        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    @Override
    public boolean shouldCombineTextures() {
        return false;
    }
}