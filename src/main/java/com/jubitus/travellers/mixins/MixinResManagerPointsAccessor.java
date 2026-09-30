package com.jubitus.travellers.mixins;

import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.buildingmanagers.ResManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Raw special points of a building plan: the public getters silently fall back to other points
// (leisure -> selling -> sleeping), which hides whether the plan actually defines them.
@Mixin(value = ResManager.class, remap = false)
public interface MixinResManagerPointsAccessor {
    @Accessor("leasurePos")
    Point travellers$getLeasurePos();

    @Accessor("sellingPos")
    Point travellers$getSellingPos();

    @Accessor("sleepingPos")
    Point travellers$getSleepingPos();

    @Accessor("pathStartPos")
    Point travellers$getPathStartPos();
}
