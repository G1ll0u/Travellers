package com.jubitus.travellers.battle;

import com.jubitus.travellers.combat.TravellerItemDamage;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Offscreen counterpart of the loaded hit hooks: gear wear and combat stamp, applied to the record's NBT. */
final class VirtualItemWear {

    private VirtualItemWear() {}

    /** The attacker's main-hand weapon loses durability, like {@code TravellerItemDamage.damageWeaponOnHit}. */
    static void wearWeapon(VirtualTravellerRecord rec) {
        if (!TravellersConfig.COMBAT.weaponDurabilityEnabled || rec.entityNbt == null) return;
        NBTTagList hand = rec.entityNbt.getTagList("HandItems", 10);
        if (hand.tagCount() == 0) return;
        wearEntry(rec, hand.getCompoundTagAt(0));
    }

    /** Every worn armor piece loses durability, like {@code TravellerItemDamage.damageArmorOnHit}. */
    static void wearArmor(VirtualTravellerRecord rec, float rawDamage) {
        if (!TravellersConfig.COMBAT.armorDurabilityEnabled || rec.entityNbt == null) return;
        NBTTagList armor = rec.entityNbt.getTagList("ArmorItems", 10);
        int wear = TravellerItemDamage.armorWearFor(rawDamage);
        for (int i = 0; i < armor.tagCount(); i++) {
            NBTTagCompound tag = armor.getCompoundTagAt(i);
            if (new ItemStack(tag).getItem() instanceof ItemArmor) wearEntry(rec, tag, wear);
        }
    }

    /** A parried hit wears the offhand shield like the loaded one (hits under 3 damage do not). */
    static void wearShield(VirtualTravellerRecord rec, float rawDamage) {
        if (rawDamage < 3.0F || rec.entityNbt == null) return;
        NBTTagList hands = rec.entityNbt.getTagList("HandItems", 10);
        int wear = 1 + (int) Math.floor(rawDamage);
        for (int i = 0; i < hands.tagCount(); i++) {
            NBTTagCompound tag = hands.getCompoundTagAt(i);
            ItemStack s = new ItemStack(tag);
            if (!s.isEmpty() && s.getItem().isShield(s, null)) wearEntry(rec, tag, wear);
        }
    }

    /** Same stamp the loaded entity writes, so the post-combat heal delay counts from the last blow. */
    static void markCombat(VirtualTravellerRecord rec, long now) {
        if (rec.entityNbt != null) rec.entityNbt.setInteger("LastCombatTick", (int) now);
    }

    private static void wearEntry(VirtualTravellerRecord rec, NBTTagCompound tag) {
        wearEntry(rec, tag, 1);
    }

    private static void wearEntry(VirtualTravellerRecord rec, NBTTagCompound tag, int wear) {
        ItemStack stack = new ItemStack(tag);
        if (stack.isEmpty() || com.jubitus.travellers.unit.equipment.TravellerEquipmentManager.isGhostItem(stack)) return;
        boolean broke = TravellerItemDamage.wearStack(stack, wear);
        // Rewrite in place: the list entry keeps its slot (an emptied slot is an empty compound).
        for (String key : new java.util.ArrayList<>(tag.getKeySet())) tag.removeTag(key);
        if (!broke) stack.writeToNBT(tag);
        // Durability never changes the profile; a broken piece does (damage, armor values).
        if (broke) rec.invalidateNbtCaches();
    }
}
