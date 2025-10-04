package com.jubitus.traveller.traveller.render;

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

    private static final ResourceLocation[] TEXTURES = new ResourceLocation[]{
            new ResourceLocation("travellers", "textures/entity/traveller1.png"),
            new ResourceLocation("travellers", "textures/entity/traveller2.png"),
            new ResourceLocation("travellers", "textures/entity/traveller3.png"),
            new ResourceLocation("travellers", "textures/entity/traveller4.png"),
            new ResourceLocation("travellers", "textures/entity/traveller5.png"),
            new ResourceLocation("travellers", "textures/entity/traveller6.png"),
            new ResourceLocation("travellers", "textures/entity/traveller7.png")
    };

    public RenderTraveller(RenderManager renderManagerIn) {
        super(renderManagerIn, new ModelSimpleHumanoid(), 0.5F);

        // Renders items in MAINHAND/OFFHAND
        this.addLayer(new LayerHeldItem(this));

        // Renders vanilla armor on HEAD/CHEST/LEGS/FEET equipment slots
        this.addLayer(new LayerBipedArmor(this) {
            @Override
            protected void initArmor() {
                // Inner = leggings (layer_2), Outer = chest/boots/helmet (layer_1)
                this.modelLeggings = new ModelSimpleHumanoid(0.35F, 64, 32);
                this.modelArmor = new ModelSimpleHumanoid(0.80F, 64, 32);
            }
        });


        // Optional: render skulls, pumpkins, etc. worn in HEAD slot (non-armor)
        this.addLayer(new LayerCustomHead(((ModelBiped) this.getMainModel()).bipedHead));
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityTraveller entity) {
        return TEXTURES[entity.getTextureIndex()];
    }

    @Override
    protected void preRenderCallback(EntityTraveller entity, float partialTickTime) {
        GlStateManager.scale(0.85F, 0.85F, 0.85F);
    }
}
