package com.jubitus.travellers.mixins.early;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla fix: the server syncs an arrow's position after its first tick but not its velocity, so the client would
 * fly from tick 1 with the tick-0 velocity (one tick of gravity and drag missing) and land too far, snapping back
 * up to a second later. Skipping that first correction lets the client simulate the tick itself, matching the server.
 */
@Mixin(EntityArrow.class)
public abstract class MixinEntityArrowFirstTickSync {

    @Shadow
    protected boolean inGround;

    @Inject(method = "setPositionAndRotationDirect", at = @At("HEAD"), cancellable = true)
    private void travellers$skipFirstTickCorrection(double x, double y, double z, float yaw, float pitch, int increments, boolean teleport, CallbackInfo ci) {
        if (((Entity) (Object) this).ticksExisted == 0 && !inGround) ci.cancel();
    }
}
