package com.jubitus.travellers.mixins;

import com.jubitus.travellers.utils.mobs.OwnedMobFriendlyTravellerFilter;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityOwnable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents player-owned mobs (wolves, and any modded tameable implementing
 * IEntityOwnable) from targeting friendly travellers/workers, even when
 * accidentally hit by them or when the owner hits them.
 *
 * Targets EntityLiving.setAttackTarget rather than EntityWolf specifically:
 * subclasses that override it (e.g. EntityWolf) call super.setAttackTarget()
 * first, so cancelling here also stops the target assignment for them.
 */
@Mixin(EntityLiving.class)
public class MixinOwnedMobFriendlyTraveller {

    @Inject(method = "setAttackTarget", at = @At("HEAD"), cancellable = true)
    private void travellers$filterFriendlyTraveller(EntityLivingBase target, CallbackInfo ci) {
        if (target == null) return;
        if (!(this instanceof IEntityOwnable)) return;
        IEntityOwnable owned = (IEntityOwnable) this;
        if (OwnedMobFriendlyTravellerFilter.isProtected(target, owned)) {
            ci.cancel();
        }
    }
}
