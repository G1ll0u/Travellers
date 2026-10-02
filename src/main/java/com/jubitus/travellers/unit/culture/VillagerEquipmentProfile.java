package com.jubitus.travellers.unit.culture;

import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Equipment tied to a specific Millenaire villager .txt (defaultweapon=/startingInv= fields),
 * attached to the {@link TravellerAppearance} built from that file so a spawned entity wearing
 * that appearance can be equipped like the villager type it represents.
 */
public final class VillagerEquipmentProfile {
    /** From defaultweapon=, the villager's signature weapon. May be empty. */
    public ItemStack defaultWeapon = ItemStack.EMPTY;

    /** From startingInv= lines, one stack per entry. May be empty. */
    public final List<ItemStack> startingInv = new ArrayList<>();

    public boolean isEmpty() {
        return defaultWeapon.isEmpty() && startingInv.isEmpty();
    }
}
