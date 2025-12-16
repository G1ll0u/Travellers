package com.jubitus.travellers.client.render;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

public class LayerTravellerBackpack implements net.minecraft.client.renderer.entity.layers.LayerRenderer<EntityTraveller> {

    private static final ResourceLocation BACKPACK_TEXTURE =
            new ResourceLocation("travellers", "textures/entity/backpack.png");

    // NEW: bamboo texture (make/add this PNG)
    private static final ResourceLocation BAMBOO_TEXTURE =
            new ResourceLocation("travellers", "textures/entity/bamboo.png");

    private static final ResourceLocation CHUSPAS_TEXTURE =
            new ResourceLocation("travellers", "textures/entity/chuspas.png");


    // NEW: cultures that should use bamboo
    private static final java.util.Set<String> BAMBOO_CULTURES = new java.util.HashSet<>(
            java.util.Arrays.asList("japanese", "song", "daiviet")
    );

    private static final java.util.Set<String> CHUSPAS_CULTURES = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "inca", "huari" // example placeholder
            )
    );

    private final RenderTraveller renderer;

    private final ModelBackpack modelBackpack = new ModelBackpack();
    private final ModelBamboo modelBamboo = new ModelBamboo(); // or: new bamboo();
    private final ModelChuspas modelChuspas = new ModelChuspas();

    public LayerTravellerBackpack(RenderTraveller renderer) {
        this.renderer = renderer;
    }

    @Override
    public void doRenderLayer(EntityTraveller entity,
                              float limbSwing, float limbSwingAmount, float partialTicks,
                              float ageInTicks, float netHeadYaw, float headPitch, float scale) {

        if (!(entity instanceof EntityTravellerWorker)
                && !(entity instanceof EntityTravellerMerchant)) return;

        if (!entity.shouldRenderBackpack()) return;

        IBackpackStyle style = BackpackRegistry.resolve(entity);
        if (style == null) return;

        GlStateManager.pushMatrix();

        ModelBiped biped = (ModelBiped) renderer.getMainModel();
        biped.setRotationAngles(limbSwing, limbSwingAmount, ageInTicks,
                netHeadYaw, headPitch, scale, entity);

        biped.bipedBody.postRender(scale);

        renderer.bindTexture(style.texture(entity));
        style.render(renderer, entity,
                limbSwing, limbSwingAmount, ageInTicks,
                netHeadYaw, headPitch, scale);

        GlStateManager.popMatrix();
    }



    @Override
    public boolean shouldCombineTextures() {
        return false;
    }

}

