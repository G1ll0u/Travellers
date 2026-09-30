package com.jubitus.travellers.mixins;

import com.jubitus.travellers.world.mill.MillVillagerTravellerGuard;
import net.minecraft.entity.EntityLivingBase;
import org.millenaire.common.entity.MillVillager;
import org.millenaire.common.village.Building;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents MillVillagers from calling for help or setting revenge targets
 * against friendly travellers (non-raiders, non-enemy-workers).
 *
 * The "else" branch in func_70097_a (ordinal 2 among all call sites) fires
 * when the damage source is any non-player, non-MillVillager entity — which
 * includes all EntityTraveller subclasses.
 */
@Mixin(value = MillVillager.class, remap = false)
public class MixinMillVillagerFriendlyTraveller {

    @Redirect(
            method = "func_70097_a",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/millenaire/common/entity/MillVillager;func_70624_b(Lnet/minecraft/entity/EntityLivingBase;)V",
                    ordinal = 2
            ),
            remap = false
    )
    private void travellers$skipSetRevengeVsFriendly(MillVillager mv, EntityLivingBase attacker) {
        if (MillVillagerTravellerGuard.shouldSkipRetaliation(attacker, mv)) return;
        ((net.minecraft.entity.EntityLivingBase) mv).setRevengeTarget(attacker);
    }

    @Redirect(
            method = "func_70097_a",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/millenaire/common/village/Building;callForHelp(Lnet/minecraft/entity/EntityLivingBase;)V",
                    ordinal = 2
            ),
            remap = false
    )
    private void travellers$skipCallForHelpVsFriendly(Building building, EntityLivingBase attacker) {
        if (MillVillagerTravellerGuard.shouldSkipRetaliation(attacker, (MillVillager) (Object) this)) return;
        building.callForHelp(attacker);
    }
}
