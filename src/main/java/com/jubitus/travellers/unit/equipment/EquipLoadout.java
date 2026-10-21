package com.jubitus.travellers.unit.equipment;

import net.minecraft.item.ItemStack;

/**
 * The kinds of gear a hired worker uses, ticked by the player on Get Equipment. The mission takes the
 * ticked kinds and leaves the others in the chests; afterwards the worker only picks up ticked kinds.
 */
public final class EquipLoadout {

    public static final int MELEE = 1;
    public static final int BOW = 2;
    public static final int SHIELD = 4;
    public static final int ARMOR = 8;
    private static final int ALL = MELEE | BOW | SHIELD | ARMOR;

    /** A hired worker's saved role. */
    public static final String ROLE_NBT_KEY = "EquipRole";

    public static final EquipLoadout DEFAULT = new EquipLoadout(MELEE | SHIELD | ARMOR);

    // INVARIANT: BOW and SHIELD are never both set (an archer has no hand for a shield).
    public final int bits;

    private EquipLoadout(int bits) {
        this.bits = bits;
    }

    /** Bits from a save or a packet; a bow wins over a shield. */
    public static EquipLoadout of(int bits) {
        bits &= ALL;
        if ((bits & BOW) != 0) bits &= ~SHIELD;
        return new EquipLoadout(bits);
    }

    public boolean has(int kind) {
        return (bits & kind) != 0;
    }

    /** Flips one kind; ticking Bow unticks Shield and the other way round. */
    public EquipLoadout toggle(int kind) {
        int b = bits ^ kind;
        if ((b & kind) != 0) {
            if (kind == BOW) b &= ~SHIELD;
            if (kind == SHIELD) b &= ~BOW;
        }
        return of(b);
    }

    /** False for gear of an unticked kind; anything that is not gear is allowed. */
    public boolean allows(ItemStack stack) {
        TravellerMillPriorities.Bucket bucket = TravellerEquipmentPolicy.bucketFor(stack, null);
        if (bucket == null) return true;
        switch (bucket) {
            case MELEE: return has(MELEE);
            case RANGED: return has(BOW);
            case SHIELD: return has(SHIELD);
            default: return has(ARMOR);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EquipLoadout && ((EquipLoadout) o).bits == bits;
    }

    @Override
    public int hashCode() {
        return bits;
    }
}
