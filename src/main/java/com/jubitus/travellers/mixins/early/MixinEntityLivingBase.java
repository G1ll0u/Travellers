package com.jubitus.travellers.mixins.early;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A unit's shield covers the front of its body, decided by {@code ShieldGuard.covers}. */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBase {

    @Inject(method = "canBlockDamageSource", at = @At("HEAD"), cancellable = true)
    private void travellers$shieldCoversBodyFront(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (self instanceof EntityTraveller) {
            cir.setReturnValue(((EntityTraveller) self).shield.covers(source));
        }
    }
}
