package com.jubitus.travellers.unit.equipment;

/**
 * What a Get-Equipment mission is heading to do. Was a raw {@code boolean useWholeVillage}
 * (village vs. lone barrack) before the Stable zone type needed a third kind - a closed enum threaded
 * end-to-end instead of a second boolean, so a plausible fourth kind later is one more constant.
 * {@link #STABLE_RETURN} goes to the same Stable zones as {@link #STABLE} but hands horses back.
 * {@link #DUMP_BARRACK} / {@link #DUMP_VILLAGE} go to the same chests as the two equipment kinds but leave items there.
 * New constants are appended: the ordinal travels over the network.
 */
public enum EquipTargetKind {
    LONE_BARRACK,
    CONTROLLED_VILLAGE,
    STABLE,
    STABLE_RETURN,
    DUMP_BARRACK,
    DUMP_VILLAGE;

    /** True for the missions that drop the backpack's surplus into the chests instead of taking gear. */
    public boolean isDump() {
        return this == DUMP_BARRACK || this == DUMP_VILLAGE;
    }

    /** The chest source a chest mission reads: a controlled village or a Barrack. */
    public EquipTargetKind chestKind() {
        return this == CONTROLLED_VILLAGE || this == DUMP_VILLAGE ? CONTROLLED_VILLAGE : LONE_BARRACK;
    }

    /** The dump mission matching a get-equipment chest target (village or Barrack). */
    public EquipTargetKind toDump() {
        return chestKind() == CONTROLLED_VILLAGE ? DUMP_VILLAGE : DUMP_BARRACK;
    }

    public static EquipTargetKind fromNbt(String name) {
        if (name == null) return LONE_BARRACK;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return LONE_BARRACK;
        }
    }
}
