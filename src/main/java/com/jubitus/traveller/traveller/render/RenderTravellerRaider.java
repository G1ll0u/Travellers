package com.jubitus.traveller.traveller.render;

import com.jubitus.traveller.traveller.culture.CultureDef;
import com.jubitus.traveller.traveller.entityAI.EntityTravellerRaider;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.util.ResourceLocation;

// RenderTravellerRaider.java
public class RenderTravellerRaider extends RenderBiped<EntityTravellerRaider> {

    public RenderTravellerRaider(RenderManager renderManagerIn) {
        super(renderManagerIn, new ModelSimpleHumanoid(), 0.5F);

        this.addLayer(new LayerHeldItem(this));

        this.addLayer(new LayerBipedArmor(this) {
            @Override
            protected void initArmor() {
                this.modelLeggings = new ModelSimpleHumanoid(0.35F, 64, 32);
                this.modelArmor    = new ModelSimpleHumanoid(0.80F, 64, 32);
            }
        });

        this.addLayer(new LayerCustomHead(((ModelBiped) this.getMainModel()).bipedHead));
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityTravellerRaider e) {
        // Culture-aware: textures/entity/<cultureId>/raider_<idx>.png
        CultureDef c = e.getCulture();
        int idx = e.getTextureIndex();
        int max = Math.max(1, c.raiderTextureCount);
        if (idx < 0) idx = 0;
        if (idx >= max) idx = idx % max;
        return c.raiderTexture(idx);
    }

    @Override
    protected void preRenderCallback(EntityTravellerRaider entity, float partialTickTime) {
        GlStateManager.scale(0.85F, 0.85F, 0.85F);
    }
}
