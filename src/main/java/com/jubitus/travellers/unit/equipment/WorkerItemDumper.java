package com.jubitus.travellers.unit.equipment;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import java.util.List;

/**
 * Dump Items: moves a worker's backpack surplus into the chests. It keeps what the worker uses: its best
 * melee weapon, its best bow and armour better than what it wears (the equipment manager equips it). Worn
 * gear is never in the backpack, and the delivery cargo is not touched.
 */
final class WorkerItemDumper {

    private WorkerItemDumper() {}

    /** Returns {items stored, items that did not fit and stayed in the backpack}. */
    static int[] dump(EntityTravellerWorker worker, List<IInventory> chests) {
        TravellerEquipmentManager eq = worker.equipment;
        ItemStackHandler pack = worker.inventory;
        int keepMelee = bestSlot(pack, true);
        int keepBow = bestSlot(pack, false);
        int stored = 0;
        int left = 0;
        for (int i = 0; i < pack.getSlots(); i++) {
            ItemStack s = pack.getStackInSlot(i);
            if (s.isEmpty() || i == keepMelee || i == keepBow || eq.isGhost(s)) continue;
            if (s.getItem() instanceof ItemArmor && eq.isArmorUpgrade(s, ((ItemArmor) s.getItem()).armorType)) continue;
            ItemStack rest = s.copy();
            for (IInventory chest : chests) {
                rest = ItemHandlerHelper.insertItemStacked(new InvWrapper(chest), rest, false);
                if (rest.isEmpty()) break;
            }
            stored += s.getCount() - rest.getCount();
            left += rest.getCount();
            if (rest.getCount() != s.getCount()) pack.setStackInSlot(i, rest);
        }
        return new int[]{stored, left};
    }

    /** Slot of the best-ranked melee weapon (or bow), -1 when there is none. */
    private static int bestSlot(ItemStackHandler pack, boolean melee) {
        int best = -1;
        double bestRank = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < pack.getSlots(); i++) {
            ItemStack s = pack.getStackInSlot(i);
            boolean fits = melee ? TravellerEquipmentManager.isWeapon(s) && !TravellerEquipmentManager.isBow(s)
                    : TravellerEquipmentManager.isBow(s);
            if (!fits) continue;
            double rank = TravellerEquipmentPolicy.weaponRank(s);
            if (rank > bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        return best;
    }
}
