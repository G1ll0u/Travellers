package com.jubitus.travellers.utils.mobs;

import net.minecraft.entity.Entity;

public final class TravellerHostilityHelper {

    private static boolean lavaCowChecked = false;
    private static Class<?> lavaCowAggressiveClass = null;

    private TravellerHostilityHelper() {}

    public static boolean isLavaCowAggressive(Entity e) {
        if (e == null) return false;

        // Resolve the class only once
        if (!lavaCowChecked) {
            lavaCowChecked = true;
            try {
                lavaCowAggressiveClass = Class.forName(
                        "com.Fishmod.mod_LavaCow.entities.IAggressive"
                );
            } catch (Throwable t) {
                lavaCowAggressiveClass = null; // mod not present or different version
            }
        }

        return lavaCowAggressiveClass != null && lavaCowAggressiveClass.isInstance(e);
    }
}
