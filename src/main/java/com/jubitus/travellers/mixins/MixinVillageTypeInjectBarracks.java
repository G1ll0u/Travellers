package com.jubitus.travellers.mixins;

import com.jubitus.travellers.TravellersModConfig;
import org.millenaire.common.buildingplan.BuildingCustomPlan;
import org.millenaire.common.culture.Culture;
import org.millenaire.common.culture.VillageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/**
 * After a player-controlled VillageType is loaded, adds the global barracks
 * plan to its customBuildings list so it appears in the creation wand UI.
 * The plan itself was already injected into Culture.customBuildings by
 * MixinCultureInjectBarracks, so culture.getBuildingCustom() will find it.
 */
@Mixin(value = VillageType.class, remap = false)
public class MixinVillageTypeInjectBarracks {

    @Inject(
            method = "loadVillageType(Ljava/io/File;Lorg/millenaire/common/culture/Culture;Z)Lorg/millenaire/common/culture/VillageType;",
            at = @At("RETURN"),
            remap = false
    )
    private static void travellers$injectBarracksIntoPlayerVillageType(
            File file, Culture culture, boolean lonebuilding,
            CallbackInfoReturnable<VillageType> cir) {

        VillageType vt = cir.getReturnValue();
        if (vt == null || !vt.playerControlled) return;
        if (!TravellersModConfig.controlledVillageFeatureEnabled) return;

        BuildingCustomPlan plan = culture.getBuildingCustom("travellers_barracks");
        if (plan != null && !vt.customBuildings.contains(plan)) {
            vt.customBuildings.add(plan);
        }
    }
}
