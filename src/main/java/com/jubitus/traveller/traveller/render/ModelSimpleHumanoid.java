package com.jubitus.traveller.traveller.render;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

public class ModelSimpleHumanoid extends ModelBiped {

    public ModelSimpleHumanoid() {
        super(0.0F, 0.0F, 64, 64);

        // Head
        this.bipedHead = new ModelRenderer(this, 0, 0);
        this.bipedHead.addBox(-4F, -8F, -4F, 8, 8, 8, 0.0F);

        // Body
        this.bipedBody = new ModelRenderer(this, 16, 16);
        this.bipedBody.addBox(-4F, 0F, -2F, 8, 12, 4, 0.0F);

        // Right Arm
        this.bipedRightArm = new ModelRenderer(this, 40, 16);
        this.bipedRightArm.addBox(-3F, -2F, -2F, 4, 12, 4, 0.0F);
        this.bipedRightArm.setRotationPoint(-5F, 2F, 0F);

        // Left Arm
        this.bipedLeftArm = new ModelRenderer(this, 40, 16);
        this.bipedLeftArm.mirror = true;
        this.bipedLeftArm.addBox(-1F, -2F, -2F, 4, 12, 4, 0.0F);
        this.bipedLeftArm.setRotationPoint(5F, 2F, 0F);

        // Right Leg
        this.bipedRightLeg = new ModelRenderer(this, 0, 16);
        this.bipedRightLeg.addBox(-2F, 0F, -2F, 4, 12, 4, 0.0F);
        this.bipedRightLeg.setRotationPoint(-2F, 12F, 0F);

        // Left Leg
        this.bipedLeftLeg = new ModelRenderer(this, 0, 16);
        this.bipedLeftLeg.mirror = true;
        this.bipedLeftLeg.addBox(-2F, 0F, -2F, 4, 12, 4, 0.0F);
        this.bipedLeftLeg.setRotationPoint(2F, 12F, 0F);
    }

    @Override
    public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks,
                                  float netHeadYaw, float headPitch, float scale, Entity entity) {
        super.setRotationAngles(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale, entity);

        if (!(entity instanceof EntityTraveller)) return;
        EntityTraveller t = (EntityTraveller) entity;

        // Prefer forced swing (from the packet). Fall back to ModelBiped's swingProgress.
        float swing = t.getForcedSwingProgress();
        if (swing <= 0f) swing = this.swingProgress;

        if (swing <= 0f) return;

        // Avoid bow/eat poses stomping the swing during these few ticks
        this.rightArmPose = ArmPose.EMPTY;
        this.leftArmPose = ArmPose.EMPTY;

        float s1 = MathHelper.sin(swing * (float) Math.PI);
        float s2 = MathHelper.sin(MathHelper.sqrt(swing) * (float) Math.PI);

        // Right-arm “raise then slash” (your original idea)
        this.bipedRightArm.rotateAngleZ = 0.0F;
        this.bipedRightArm.rotateAngleY = 0.4F * s2;
        this.bipedRightArm.rotateAngleX = -((float) Math.PI / 2F) - 1.2F * s2;
        this.bipedRightArm.rotateAngleX += 1.4F * s1;
    }
}

