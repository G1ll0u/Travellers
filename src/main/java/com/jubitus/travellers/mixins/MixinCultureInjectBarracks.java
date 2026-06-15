package com.jubitus.travellers.mixins;

import com.jubitus.travellers.TravellersModConfig;
import org.millenaire.common.buildingplan.BuildingCustomPlan;
import org.millenaire.common.culture.Culture;
import org.millenaire.common.utilities.virtualdir.VirtualDir;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;

/**
 * Injects a global "travellers_barracks" custom building into every Millénaire
 * culture so that player-controlled villages of any culture can place it.
 * The building has no villager residents — it is reserved for worker henchmen.
 */
@Mixin(value = Culture.class, remap = false)
public class MixinCultureInjectBarracks {

    @Redirect(
            method = "load(Lorg/millenaire/common/utilities/virtualdir/VirtualDir;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/millenaire/common/buildingplan/BuildingCustomPlan;loadCustomBuildings(Lorg/millenaire/common/utilities/virtualdir/VirtualDir;Lorg/millenaire/common/culture/Culture;)Ljava/util/Map;"
            ),
            remap = false
    )
    private Map<String, BuildingCustomPlan> travellers$injectGlobalBarracks(VirtualDir dir, Culture culture) {
        Map<String, BuildingCustomPlan> map = BuildingCustomPlan.loadCustomBuildings(dir, culture);

        if (!TravellersModConfig.controlledVillageFeatureEnabled) return map;
        if (map.containsKey("travellers_barracks")) return map;

        Map<String, BuildingCustomPlan> mutable = new HashMap<>(map);
        mutable.put("travellers_barracks", travellers$buildBarracksPlan(culture));
        return mutable;
    }

    private static BuildingCustomPlan travellers$buildBarracksPlan(Culture culture) {
        BuildingCustomPlan plan = new BuildingCustomPlan(culture, "travellers_barracks");
        plan.nativeName = "Workers Barracks";
        plan.tags.add("barracks");
        plan.radius = TravellersModConfig.workerBarracksRadius;
        plan.heightRadius = 3;
        plan.minResources.put(BuildingCustomPlan.TypeRes.SIGN, 1);
        plan.maxResources.put(BuildingCustomPlan.TypeRes.SIGN, 1);
        plan.minResources.put(BuildingCustomPlan.TypeRes.CHEST, 1);
        plan.maxResources.put(BuildingCustomPlan.TypeRes.CHEST, 20);
//        int slots = TravellersModConfig.barracksSlotsPerBuilding;
//        if (slots > 0) {
//            plan.minResources.put(BuildingCustomPlan.TypeRes.SPAWN, slots);
//            plan.maxResources.put(BuildingCustomPlan.TypeRes.SPAWN, slots);
//        }
        return plan;
    }
}
