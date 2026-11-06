package com.jubitus.travellers.unit.render.models;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.render.ModelFlattener;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

public class ModelSimpleHumanoid extends ModelBiped {

    // Set by RenderTraveller while the unit is drawn from its baked skin: one buffer instead of one call per part.
    public boolean flattened;
    private ModelRenderer[] flatParts;

    public ModelSimpleHumanoid() {
        this(0.0F, 64, 32); // your entity’s main skin: 64×64
    }

    // Use this for armor: (modelSize, 64, 32)
    public ModelSimpleHumanoid(float modelSize, int texW, int texH) {
        super(modelSize, 0.0F, texW, texH);

        // Head
        this.bipedHead = new ModelRenderer(this, 0, 0);
        this.bipedHead.addBox(-4F, -8F, -4F, 8, 8, 8, modelSize);

        // Body
        this.bipedBody = new ModelRenderer(this, 16, 16);
        this.bipedBody.addBox(-4F, 0F, -2F, 8, 12, 4, modelSize);

        // Right Arm
        this.bipedRightArm = new ModelRenderer(this, 40, 16);
        this.bipedRightArm.addBox(-3F, -2F, -2F, 4, 12, 4, modelSize);
        this.bipedRightArm.setRotationPoint(-5F, 2F, 0F);

        // Left Arm
        this.bipedLeftArm = new ModelRenderer(this, 40, 16);
        this.bipedLeftArm.mirror = true;
        this.bipedLeftArm.addBox(-1F, -2F, -2F, 4, 12, 4, modelSize);
        this.bipedLeftArm.setRotationPoint(5F, 2F, 0F);

        // Right Leg
        this.bipedRightLeg = new ModelRenderer(this, 0, 16);
        this.bipedRightLeg.addBox(-2F, 0F, -2F, 4, 12, 4, modelSize);
        this.bipedRightLeg.setRotationPoint(-2F, 12F, 0F);

        // Left Leg
        this.bipedLeftLeg = new ModelRenderer(this, 0, 16);
        this.bipedLeftLeg.mirror = true;
        this.bipedLeftLeg.addBox(-2F, 0F, -2F, 4, 12, 4, modelSize);
        this.bipedLeftLeg.setRotationPoint(2F, 12F, 0F);
    }

    public ModelSimpleHumanoid(float modelSize) {
        this(modelSize, 64, 64);
    }

    @Override
    public void render(Entity entityIn, float limbSwing, float limbSwingAmount, float ageInTicks,
                       float netHeadYaw, float headPitch, float scale) {
        if (!flattened || isChild) {
            super.render(entityIn, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale);
            return;
        }
        // Mirrors ModelBiped.render's adult branch.
        setRotationAngles(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale, entityIn);
        if (flatParts == null) {
            flatParts = new ModelRenderer[]{bipedHead, bipedBody, bipedRightArm, bipedLeftArm,
                    bipedRightLeg, bipedLeftLeg, bipedHeadwear};
        }
        GlStateManager.pushMatrix();
        if (entityIn.isSneaking()) {
            GlStateManager.translate(0.0F, 0.2F, 0.0F);
        }
        ModelFlattener.draw(flatParts, scale);
        GlStateManager.popMatrix();
    }

    @Override
    public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks,
                                  float netHeadYaw, float headPitch, float scale, Entity entity) {
        super.setRotationAngles(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale, entity);

        this.rightArmPose = ArmPose.EMPTY;
        this.leftArmPose = ArmPose.EMPTY;

        if (!(entity instanceof EntityTraveller)) return;
        EntityTraveller t = (EntityTraveller) entity;

// 1) Aiming pose (skeleton-style)
        if (t.isAimingBow()) {
            this.rightArmPose = ArmPose.BOW_AND_ARROW;
            this.leftArmPose = ArmPose.BOW_AND_ARROW;

            this.bipedRightArm.rotateAngleY = -0.1F + this.bipedHead.rotateAngleY;
            this.bipedLeftArm.rotateAngleY = 0.1F + this.bipedHead.rotateAngleY + 0.4F;
            this.bipedRightArm.rotateAngleX = -((float) Math.PI / 2F) + this.bipedHead.rotateAngleX;
            this.bipedLeftArm.rotateAngleX = -((float) Math.PI / 2F) + this.bipedHead.rotateAngleX;

            this.bipedRightArm.rotateAngleZ = (float) Math.cos(ageInTicks * 0.09F) * 0.05F;
            this.bipedLeftArm.rotateAngleZ = -(float) Math.cos(ageInTicks * 0.09F) * 0.05F;
            this.bipedRightArm.rotateAngleX += (float) Math.sin(ageInTicks * 0.067F) * 0.05F;
            this.bipedLeftArm.rotateAngleX -= (float) Math.sin(ageInTicks * 0.067F) * 0.05F;

            // DO NOT 'return' here if you want vanilla leg/walk to keep applying;
            // just skip your swing override:
        } else {
            // 2) Your custom swing override (only if not aiming)
            float swing = t.getForcedSwingProgress();
            if (swing <= 0f) swing = this.swingProgress;
            if (swing > 0f) {
                float s1 = MathHelper.sin(swing * (float) Math.PI);
                float s2 = MathHelper.sin(MathHelper.sqrt(swing) * (float) Math.PI);

                this.bipedRightArm.rotateAngleZ = 0.0F;
                this.bipedRightArm.rotateAngleY = 0.4F * s2;
                this.bipedRightArm.rotateAngleX = -((float) Math.PI / 2F) - 1.2F * s2;
                this.bipedRightArm.rotateAngleX += 1.4F * s1;
            }
        }

        if (!t.isAimingBow()) applySnackPose(t, ageInTicks);

        // Raised shield: vanilla's block pose on the left (offhand) arm
        if (!t.isAimingBow() && t.isHandActive() && t.getActiveHand() == net.minecraft.util.EnumHand.OFF_HAND) {
            this.leftArmPose = ArmPose.BLOCK;
            this.bipedLeftArm.rotateAngleX = this.bipedLeftArm.rotateAngleX * 0.5F - 0.9424779F;
            this.bipedLeftArm.rotateAngleY = 0.5235988F;
        }
    }

    // Snack in the main hand: held in front of the chest, then brought to the mouth; drinking tips the head back.
    private void applySnackPose(EntityTraveller t, float ageInTicks) {
        float partial = ageInTicks - t.ticksExisted;
        float hold = t.snack.getHold(partial);
        if (hold <= 0.0F) return;
        float raise = t.snack.getRaise(partial);
        boolean drinking = t.snack.isDrinking();
        boolean right = t.getPrimaryHand() == net.minecraft.util.EnumHandSide.RIGHT;
        ModelRenderer arm = right ? bipedRightArm : bipedLeftArm;
        float side = right ? 1.0F : -1.0F;

        float x = lerp(arm.rotateAngleX, -0.6F, hold);
        float y = lerp(arm.rotateAngleY, -0.15F * side, hold);
        float z = lerp(arm.rotateAngleZ, 0.0F, hold);
        float mouthX = drinking ? -2.1F : -1.9F;
        if (!drinking) mouthX += MathHelper.sin(ageInTicks * 1.4F) * 0.08F; // chewing bob
        arm.rotateAngleX = lerp(x, mouthX, raise);
        arm.rotateAngleY = lerp(y, -0.6F * side, raise);
        arm.rotateAngleZ = z;
        // The arm aims at a head facing the body: the head comes round to meet the item, or it lands beside the face.
        if (raise > 0.0F) {
            // Vanilla leaves the head-to-body yaw unwrapped (350° for -10°): wrap it or the head spins a full turn.
            float headYaw = MathHelper.wrapDegrees(bipedHead.rotateAngleY * 57.29578F) * 0.017453292F;
            bipedHead.rotateAngleY = lerp(headYaw, 0.0F, raise);
            bipedHead.rotateAngleX = lerp(bipedHead.rotateAngleX, drinking ? -0.45F : 0.0F, raise);
            copyModelAngles(bipedHead, bipedHeadwear);
        }
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
