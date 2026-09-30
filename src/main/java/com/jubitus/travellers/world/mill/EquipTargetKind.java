package com.jubitus.travellers.world.mill;

/**
 * What kind of place a Get-Equipment mission is heading to. Was a raw {@code boolean useWholeVillage}
 * (village vs. lone barrack) before the Stable zone type needed a third kind - a closed enum threaded
 * end-to-end instead of a second boolean, so a plausible fourth kind later is one more constant.
 */
public enum EquipTargetKind {
    LONE_BARRACK,
    CONTROLLED_VILLAGE,
    STABLE;

    public static EquipTargetKind fromNbt(String name) {
        if (name == null) return LONE_BARRACK;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return LONE_BARRACK;
        }
    }
}
