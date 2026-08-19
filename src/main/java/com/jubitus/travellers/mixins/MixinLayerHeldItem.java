package com.jubitus.travellers.mixins;

import com.jubitus.travellers.client.render.RenderLodHelper;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Held-item rendering (vanilla LayerHeldItem) is the single most expensive
 * per-entity render cost (3D item model, Optifine reflection). Skip it for
 * distant travellers/raiders/workers when many are on screen at once.
 */
@Mixin(LayerHeldItem.class)
public abstract class MixinLayerHeldItem {

    @Inject(method = "doRenderLayer", at = @At("HEAD"), cancellable = true)
    private void travellers$skipDistantHeldItem(EntityLivingBase entitylivingbaseIn, float limbSwing,
                                                  float limbSwingAmount, float partialTicks, float ageInTicks,
                                                  float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (entitylivingbaseIn instanceof EntityTraveller
                && RenderLodHelper.isBeyond(entitylivingbaseIn, TravellersConfig.ENTITY_RENDER_LOD.heldItemMaxDistance)) {
            ci.cancel();
        }
    }
}
