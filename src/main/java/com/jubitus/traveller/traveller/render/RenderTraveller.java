package com.jubitus.traveller.traveller.render;

import com.jubitus.traveller.traveller.culture.CultureDef;
import com.jubitus.traveller.traveller.culture.TravellerAppearance;
import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.entityAI.EntityTravellerRaider;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.util.ResourceLocation;

public class RenderTraveller extends RenderBiped<EntityTraveller> {

    // Track which travellers we've already logged, so we don't spam
    private static final java.util.Set<java.util.UUID> LOGGED_TEXTURES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public RenderTraveller(RenderManager renderManagerIn) {
        super(renderManagerIn, new ModelSimpleHumanoid(), 0.5F);

        this.addLayer(new LayerHeldItem(this));

        this.addLayer(new LayerBipedArmor(this) {
            @Override
            protected void initArmor() {
                this.modelLeggings = new ModelSimpleHumanoid(0.35F, 64, 32);
                this.modelArmor = new ModelSimpleHumanoid(0.80F, 64, 32);
            }
        });

        this.addLayer(new LayerCustomHead(((ModelBiped) this.getMainModel()).bipedHead));

        // NEW: clothes/hair overlay coming from Millénaire
        this.addLayer(new LayerTravellerClothes(this));
    }


    @Override
    protected ResourceLocation getEntityTexture(EntityTraveller e) {
        CultureDef c = e.getCulture();
        TravellerAppearance app = c.getAppearance(e.getTextureIndex());

        if (e instanceof EntityTravellerRaider) {
            app = c.getRaiderAppearance(e.getTextureIndex());
        } else {
            app = c.getAppearance(e.getTextureIndex());
        }

        // base nude or "underwear + hair" layer from Millénaire
        return app.baseTexture;
    }

    @Override
    protected void preRenderCallback(EntityTraveller entity, float partialTickTime) {
        GlStateManager.scale(0.85F, 0.85F, 0.85F);
    }
}
