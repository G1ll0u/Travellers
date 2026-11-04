package com.jubitus.travellers.unit.render;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;

/** Vanilla's held-item layer, except that a snack is shown in the main hand in place of what the unit really holds. */
public class LayerTravellerHeldItem extends LayerHeldItem {

    public LayerTravellerHeldItem(RenderLivingBase<?> renderer) {
        super(renderer);
    }

    @Override
    public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount, float partialTicks,
                              float ageInTicks, float netHeadYaw, float headPitch, float scale) {
        ItemStack snack = entity instanceof EntityTraveller ? ((EntityTraveller) entity).snack.getStack() : ItemStack.EMPTY;
        if (snack.isEmpty()) {
            super.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale);
            return;
        }
        // Mirrors LayerHeldItem.doRenderLayer with the snack as the main-hand stack.
        boolean rightHanded = entity.getPrimaryHand() == EnumHandSide.RIGHT;
        ItemStack left = rightHanded ? entity.getHeldItemOffhand() : snack;
        ItemStack right = rightHanded ? snack : entity.getHeldItemOffhand();
        GlStateManager.pushMatrix();
        if (livingEntityRenderer.getMainModel().isChild) {
            GlStateManager.translate(0.0F, 0.75F, 0.0F);
            GlStateManager.scale(0.5F, 0.5F, 0.5F);
        }
        renderInHand(entity, right, ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND, EnumHandSide.RIGHT);
        renderInHand(entity, left, ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND, EnumHandSide.LEFT);
        GlStateManager.popMatrix();
    }

    // Copy of LayerHeldItem.renderHeldItem, which is private.
    private void renderInHand(EntityLivingBase entity, ItemStack stack, ItemCameraTransforms.TransformType transform,
                              EnumHandSide side) {
        if (stack.isEmpty()) return;
        GlStateManager.pushMatrix();
        if (entity.isSneaking()) GlStateManager.translate(0.0F, 0.2F, 0.0F);
        translateToHand(side);
        GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);
        boolean left = side == EnumHandSide.LEFT;
        GlStateManager.translate((float) (left ? -1 : 1) / 16.0F, 0.125F, -0.625F);
        Minecraft.getMinecraft().getItemRenderer().renderItemSide(entity, stack, transform, left);
        GlStateManager.popMatrix();
    }
}
