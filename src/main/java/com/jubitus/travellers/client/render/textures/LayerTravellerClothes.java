package com.jubitus.travellers.client.render.textures;

import com.jubitus.travellers.client.render.RenderTraveller;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.TravellerAppearance;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.util.ResourceLocation;

public class LayerTravellerClothes implements net.minecraft.client.renderer.entity.layers.LayerRenderer<EntityTraveller> {

    private final RenderTraveller renderer;

    public LayerTravellerClothes(RenderTraveller renderer) {
        this.renderer = renderer;
    }

    @Override
    public void doRenderLayer(EntityTraveller entity, float limbSwing, float limbSwingAmount,
                              float partialTicks, float ageInTicks, float netHeadYaw,
                              float headPitch, float scale) {

        CultureDef c = entity.getCulture();
        TravellerAppearance app = c.getAppearance(entity.getTextureIndex());

        // pick one clothes texture at random *per entity* or based on some saved choice
        // simplest: deterministic by entity UUID so it stays stable
        java.util.Random r = new java.util.Random(entity.getUniqueID().getMostSignificantBits()
                ^ entity.getUniqueID().getLeastSignificantBits());
        ResourceLocation clothes = app.pickRandomClothes(r);
        if (clothes == null) return;

        renderer.bindTexture(clothes);

        net.minecraft.client.renderer.GlStateManager.enableBlend();
        net.minecraft.client.renderer.GlStateManager.blendFunc(
                net.minecraft.client.renderer.GlStateManager.SourceFactor.SRC_ALPHA,
                net.minecraft.client.renderer.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

        // reuse main model; clothes are full 64×32 skins that sit exactly on top
        renderer.getMainModel().render(entity, limbSwing, limbSwingAmount, ageInTicks,
                netHeadYaw, headPitch, scale);

        net.minecraft.client.renderer.GlStateManager.disableBlend();
    }

    @Override
    public boolean shouldCombineTextures() {
        // we draw an extra pass after the base texture; no vanilla combo
        return false;
    }
}
