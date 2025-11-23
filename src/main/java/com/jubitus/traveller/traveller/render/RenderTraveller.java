package com.jubitus.traveller.traveller.render;

import com.jubitus.traveller.traveller.culture.CultureDef;
import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.util.ResourceLocation;

public class RenderTraveller extends RenderBiped<EntityTraveller> {

    public RenderTraveller(RenderManager renderManagerIn) {
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
    protected ResourceLocation getEntityTexture(EntityTraveller e) {
        // Culture-aware: textures/entity/<cultureId>/traveller_<idx>.png
        CultureDef c = e.getCulture();                 // from your earlier step
        int idx = e.getTextureIndex();
        int max = Math.max(1, c.travellerTextureCount);
        if (idx < 0) idx = 0;
        if (idx >= max) idx = idx % max;              // safety if NBT/config changes
        return c.travellerTexture(idx);
    }

    @Override
    protected void preRenderCallback(EntityTraveller entity, float partialTickTime) {
        GlStateManager.scale(0.85F, 0.85F, 0.85F);
    }
}
