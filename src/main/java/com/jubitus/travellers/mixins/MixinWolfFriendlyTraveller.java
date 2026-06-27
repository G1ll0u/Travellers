package com.jubitus.travellers.mixins;

import com.jubitus.travellers.utils.mobs.WolfFriendlyTravellerFilter;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityWolf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents player-owned wolves from targeting friendly travellers/workers,
 * even when accidentally hit by them or when the owner hits them.
 */
@Mixin(EntityWolf.class)
public class MixinWolfFriendlyTraveller {

    @Inject(method = "setAttackTarget", at = @At("HEAD"), cancellable = true)
    private void travellers$filterFriendlyTraveller(EntityLivingBase target, CallbackInfo ci) {
        if (target == null) return;
        EntityWolf self = (EntityWolf) (Object) this;
        if (!self.isTamed()) return;
        if (WolfFriendlyTravellerFilter.isProtected(target, self)) {
            ci.cancel();
        }
    }
}
