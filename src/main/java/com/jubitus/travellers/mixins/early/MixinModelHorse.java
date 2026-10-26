package com.jubitus.travellers.mixins;

import com.jubitus.travellers.mount.client.HorseLod;
import net.minecraft.client.model.ModelHorse;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far horses ridden by our units are drawn in one buffer ({@link HorseLod}).
 * Shadows only read the parts; require = 0 so a rewritten render() just loses the LOD.
 */
@Mixin(ModelHorse.class)
public abstract class MixinModelHorse {

    @Shadow @Final private ModelRenderer head;
    @Shadow @Final private ModelRenderer horseLeftEar;
    @Shadow @Final private ModelRenderer horseRightEar;
    @Shadow @Final private ModelRenderer neck;
    @Shadow @Final private ModelRenderer mane;
    @Shadow @Final private ModelRenderer body;
    @Shadow @Final private ModelRenderer tailBase;
    @Shadow @Final private ModelRenderer tailMiddle;
    @Shadow @Final private ModelRenderer tailTip;
    @Shadow @Final private ModelRenderer backLeftLeg;
    @Shadow @Final private ModelRenderer backLeftShin;
    @Shadow @Final private ModelRenderer backLeftHoof;
    @Shadow @Final private ModelRenderer backRightLeg;
    @Shadow @Final private ModelRenderer backRightShin;
    @Shadow @Final private ModelRenderer backRightHoof;
    @Shadow @Final private ModelRenderer frontLeftLeg;
    @Shadow @Final private ModelRenderer frontLeftShin;
    @Shadow @Final private ModelRenderer frontLeftHoof;
    @Shadow @Final private ModelRenderer frontRightLeg;
    @Shadow @Final private ModelRenderer frontRightShin;
    @Shadow @Final private ModelRenderer frontRightHoof;
    @Shadow @Final private ModelRenderer horseSaddleBottom;
    @Shadow @Final private ModelRenderer horseSaddleFront;
    @Shadow @Final private ModelRenderer horseSaddleBack;

    @Unique private ModelRenderer[] travellers$bareParts;
    @Unique private ModelRenderer[] travellers$saddledParts;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void travellers$flattenFarMount(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                            float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (((Object) this).getClass() != ModelHorse.class || !HorseLod.shouldFlatten(entity)) return;
        if (travellers$bareParts == null) {
            // Vanilla's adult EntityHorse order, minus the tiny harness pieces (ropes, buckles, reins).
            travellers$bareParts = new ModelRenderer[]{
                    backLeftLeg, backLeftShin, backLeftHoof, backRightLeg, backRightShin, backRightHoof,
                    frontLeftLeg, frontLeftShin, frontLeftHoof, frontRightLeg, frontRightShin, frontRightHoof,
                    body, tailBase, tailMiddle, tailTip, neck, mane, horseLeftEar, horseRightEar, head};
            travellers$saddledParts = new ModelRenderer[travellers$bareParts.length + 3];
            travellers$saddledParts[0] = horseSaddleBottom;
            travellers$saddledParts[1] = horseSaddleFront;
            travellers$saddledParts[2] = horseSaddleBack;
            System.arraycopy(travellers$bareParts, 0, travellers$saddledParts, 3, travellers$bareParts.length);
        }
        HorseLod.draw(entity, travellers$bareParts, travellers$saddledParts, scale);
        ci.cancel();
    }
}
