package com.jubitus.traveller.traveller.render;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

public class ModelSimpleHumanoid extends ModelBiped {

    public ModelSimpleHumanoid() {
        this(0.0F, 64, 64); // your entity’s main skin: 64×64
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
    }
}
