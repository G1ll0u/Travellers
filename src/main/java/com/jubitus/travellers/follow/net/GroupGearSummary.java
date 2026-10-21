package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.battle.VirtualCombatProfile;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.equipment.TravellerEquipmentManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** A group's health and gear in totals, shown when hovering a leader row in the Formation Hub. */
public final class GroupGearSummary {

    /** A member's armour, from its chestplate; also the bar's order, best first. */
    public enum Armour { DIAMOND, IRON, CHAIN, GOLD, LEATHER, OTHER, NONE }

    private static final float WOUNDED_BELOW = 0.5F;

    public int members;
    public float healthSum;
    public int wounded;
    public int melee, bow, shield, horse;
    public final int[] armour = new int[Armour.values().length];

    public void addLoaded(EntityTravellerWorker w) {
        TravellerEquipmentManager eq = w.equipment;
        ItemStack hand = w.getHeldItemMainhand();
        boolean bowCarried = eq.hasBowAnywhere();
        add(w.getHealth(), w.getMaxHealth(),
                !eq.findBestWeaponInInventory().isEmpty() || TravellerEquipmentManager.isWeapon(hand) && !eq.isGhost(hand),
                bowCarried,
                !bowCarried && TravellerEquipmentManager.isShield(w.getHeldItemOffhand()),
                w.getMountUUID() != null,
                w.getItemStackFromSlot(EntityEquipmentSlot.CHEST));
    }

    public void addVirtual(VirtualTravellerRecord r) {
        VirtualCombatProfile p = r.getCombatProfile();
        add(r.getHealthResolved(), p.maxHealth, p.hasMeleeWeapon, p.isArcher, p.hasShield,
                r.mountNbt != null, p.chestArmour);
    }

    private void add(float health, float maxHealth, boolean hasMelee, boolean hasBow, boolean hasShield,
                     boolean hasHorse, ItemStack chest) {
        float fraction = maxHealth > 0F ? Math.min(1F, health / maxHealth) : 1F;
        members++;
        healthSum += fraction;
        if (fraction < WOUNDED_BELOW) wounded++;
        if (hasMelee) melee++;
        if (hasBow) bow++;
        if (hasShield) shield++;
        if (hasHorse) horse++;
        armour[armourOf(chest).ordinal()]++;
    }

    private static Armour armourOf(ItemStack chest) {
        if (chest.isEmpty()) return Armour.NONE;
        if (!(chest.getItem() instanceof ItemArmor)) return Armour.OTHER;
        switch (((ItemArmor) chest.getItem()).getArmorMaterial()) {
            case DIAMOND: return Armour.DIAMOND;
            case IRON: return Armour.IRON;
            case CHAIN: return Armour.CHAIN;
            case GOLD: return Armour.GOLD;
            case LEATHER: return Armour.LEATHER;
            default: return Armour.OTHER;
        }
    }

    /** Average health of the members, 0..1. */
    public float averageHealth() {
        return members == 0 ? 1F : healthSum / members;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("N", members);
        tag.setFloat("H", healthSum);
        tag.setInteger("W", wounded);
        tag.setInteger("M", melee);
        tag.setInteger("B", bow);
        tag.setInteger("S", shield);
        tag.setInteger("R", horse);
        tag.setIntArray("A", armour);
        return tag;
    }

    public static GroupGearSummary fromNbt(NBTTagCompound tag) {
        GroupGearSummary s = new GroupGearSummary();
        s.members = tag.getInteger("N");
        s.healthSum = tag.getFloat("H");
        s.wounded = tag.getInteger("W");
        s.melee = tag.getInteger("M");
        s.bow = tag.getInteger("B");
        s.shield = tag.getInteger("S");
        s.horse = tag.getInteger("R");
        int[] a = tag.getIntArray("A");
        System.arraycopy(a, 0, s.armour, 0, Math.min(a.length, s.armour.length));
        return s;
    }
}
