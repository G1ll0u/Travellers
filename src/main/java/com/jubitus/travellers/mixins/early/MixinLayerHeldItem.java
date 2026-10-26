package com.jubitus.travellers.mixins.early;

import com.jubitus.travellers.unit.render.HeldItemIcons;
import com.jubitus.travellers.unit.render.RenderLodHelper;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
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

    // WrapOperation chains with other mods' wrappers; require = 0 so a rewritten target only loses the icon LOD.
    @WrapOperation(method = "renderHeldItem", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemRenderer;renderItemSide(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;Z)V"))
    private void travellers$flatIconWhenFar(ItemRenderer renderer, EntityLivingBase entity, ItemStack stack,
                                            ItemCameraTransforms.TransformType transform, boolean leftHanded,
                                            Operation<Void> original) {
        if (!(entity instanceof EntityTraveller) || !HeldItemIcons.tryRender(entity, stack, transform, leftHanded)) {
            original.call(renderer, entity, stack, transform, leftHanded);
        }
    }
}
