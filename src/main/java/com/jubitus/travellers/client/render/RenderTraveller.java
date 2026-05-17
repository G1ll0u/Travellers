package com.jubitus.travellers.client.render;

import com.jubitus.travellers.client.render.marker.LayerTravellerArmband;
import com.jubitus.travellers.client.render.models.ModelSimpleHumanoid;
import com.jubitus.travellers.client.render.textures.LayerTravellerBackpack;
import com.jubitus.travellers.client.render.textures.LayerTravellerClothes;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.TravellerAppearance;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
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
                this.modelLeggings = new ModelSimpleHumanoid(0.15F, 64, 32);
                this.modelArmor = new ModelSimpleHumanoid(0.55F, 64, 32);
            }
        });

        this.addLayer(new LayerCustomHead(((ModelBiped) this.getMainModel()).bipedHead));
        this.addLayer(new LayerTravellerClothes(this));
        this.addLayer(new LayerTravellerBackpack(this));
        this.addLayer(new LayerTravellerArmband(this)); // toujours en dernier
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
