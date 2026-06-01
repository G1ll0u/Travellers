package com.jubitus.travellers.mixins;

import org.millenaire.common.village.Building;
import org.millenaire.common.village.buildingmanagers.ResManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Building.class, remap = false)
public interface MixinBuildingResManagerAccessor {
    @Accessor("resManager")
    ResManager travellers$getResManager();
}
