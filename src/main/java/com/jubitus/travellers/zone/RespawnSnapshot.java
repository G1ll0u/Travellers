package com.jubitus.travellers.zone;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.equipment.TravellerEquipmentManager;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.items.ItemStackHandler;

/** What a dead hired worker brings back to its bed: its saved self, its gear if the config keeps it, nothing else. */
public final class RespawnSnapshot {

    private static final String[] DROPPED_KEYS = {
            // Its horse went loose where it died: it comes back on foot, never with a second copy of it.
            "MountUUID", "MountSnapshot", "PendingMountNbt", "PendingMountPos", "MountMode", "VillageHitchStop",
            // Death-moment state vanilla would restore: burning, potion effects, elytra flight, hit flash.
            "Fire", "ActiveEffects", "FallFlying", "Motion", "HurtTime",
            // Cargo and legacy inventory never come back.
            "TravelInventory", "PersonalInventory"};

    private RespawnSnapshot() {}

    /** Real armour, weapon, bow or shield; everything else in the backpack drops at death. */
    public static boolean isKeptGear(ItemStack s) {
        if (s.isEmpty() || TravellerEquipmentManager.isGhostItem(s)) return false;
        return s.getItem() instanceof ItemArmor || TravellerEquipmentManager.isWeapon(s)
                || TravellerEquipmentManager.isBow(s) || TravellerEquipmentManager.isShield(s);
    }

    /** Turns a dying worker's full NBT into its respawn snapshot, in place. */
    static void strip(NBTTagCompound nbt, boolean keepGear) {
        for (String key : DROPPED_KEYS) nbt.removeTag(key);
        if (!keepGear) {
            nbt.removeTag("HandItems");
            nbt.removeTag("ArmorItems");
            nbt.removeTag("TravellerInv");
            return;
        }
        filterStacks(nbt.getTagList("HandItems", 10), true);
        filterStacks(nbt.getTagList("ArmorItems", 10), true);
        if (nbt.hasKey("TravellerInv", 10)) {
            NBTTagList items = nbt.getCompoundTag("TravellerInv").getTagList("Items", 10);
            for (int i = items.tagCount() - 1; i >= 0; i--) {
                if (!isKeptGear(new ItemStack(items.getCompoundTagAt(i)))) items.removeTag(i);
            }
        }
    }

    /** Removes the kept gear from a worker's NBT, so only what was not kept goes into a battle's loot. */
    public static void removeKeptGear(NBTTagCompound nbt) {
        filterStacks(nbt.getTagList("HandItems", 10), false);
        filterStacks(nbt.getTagList("ArmorItems", 10), false);
        if (nbt.hasKey("TravellerInv", 10)) {
            NBTTagList items = nbt.getCompoundTag("TravellerInv").getTagList("Items", 10);
            for (int i = items.tagCount() - 1; i >= 0; i--) {
                if (isKeptGear(new ItemStack(items.getCompoundTagAt(i)))) items.removeTag(i);
            }
        }
    }

    /** Takes the kept gear off a dying worker's body, so its death drops only the rest. */
    public static void takeKeptGearOff(EntityTravellerWorker worker) {
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            if (isKeptGear(worker.getItemStackFromSlot(slot))) worker.setItemStackToSlot(slot, ItemStack.EMPTY);
        }
        ItemStackHandler inv = worker.inventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isKeptGear(inv.getStackInSlot(i))) inv.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    // Hand/armour lists are fixed-size slot lists: an unkept entry becomes an empty compound, never removed.
    private static void filterStacks(NBTTagList slots, boolean keepGear) {
        for (int i = 0; i < slots.tagCount(); i++) {
            ItemStack s = new ItemStack(slots.getCompoundTagAt(i));
            if (s.isEmpty() || isKeptGear(s) != keepGear) slots.set(i, new NBTTagCompound());
        }
    }
}
