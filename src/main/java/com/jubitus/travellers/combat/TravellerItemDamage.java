package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;

import com.google.common.collect.Multimap;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Enchantments;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

import java.util.Random;

/**
 * Handles realistic item durability and attack-speed mechanics for traveller entities,
 * mirroring player behaviour as closely as possible.
 */
public final class TravellerItemDamage {

    private TravellerItemDamage() {}

    private static final Random RNG = new Random();
    private static final EntityEquipmentSlot[] ARMOR_SLOTS = {
            EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
            EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET
    };

    /** Damages the held weapon by 1, respecting Unbreaking and the Unbreakable tag. */
    public static void damageWeaponOnHit(EntityLivingBase entity) {
        if (!TravellersConfig.COMBAT.weaponDurabilityEnabled) return;
        if (entity.world.isRemote) return;
        ItemStack weapon = entity.getHeldItemMainhand();
        if (weapon.isEmpty() || !weapon.isItemStackDamageable() || isUnbreakable(weapon)) return;
        int actual = applyUnbreaking(weapon, 1);
        if (actual <= 0) return;
        weapon.setItemDamage(weapon.getItemDamage() + actual);
        if (weapon.getItemDamage() > weapon.getMaxDamage()) {
            entity.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /**
     * Damages equipped armor after taking a hit, mirroring InventoryPlayer.damageArmor().
     * @param rawDamage pre-reduction damage amount
     */
    public static void damageArmorOnHit(EntityLivingBase entity, float rawDamage) {
        if (!TravellersConfig.COMBAT.armorDurabilityEnabled) return;
        if (entity.world.isRemote) return;
        int dmg = Math.max(1, (int) (rawDamage / 4.0F));
        for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = entity.getItemStackFromSlot(slot);
            if (armor.isEmpty() || !(armor.getItem() instanceof ItemArmor)
                    || !armor.isItemStackDamageable() || isUnbreakable(armor)) continue;
            int actual = applyUnbreaking(armor, dmg);
            if (actual <= 0) continue;
            armor.setItemDamage(armor.getItemDamage() + actual);
            if (armor.getItemDamage() > armor.getMaxDamage()) {
                entity.setItemStackToSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Computes melee attack cooldown in ticks from the weapon's ATTACK_SPEED attribute,
     * using player base speed (4.0 attacks/s) so vanilla and modded items work out of the box.
     */
    public static int computeAttackCooldownTicks(EntityLivingBase entity) {
        return computeAttackCooldownTicks(entity.getHeldItemMainhand());
    }

    /** Weapon-only variant usable without a live entity (virtual battle simulation). */
    public static int computeAttackCooldownTicks(ItemStack weapon) {
        if (weapon.isEmpty()) return 24; // bare hands: fixed cooldown, ignore multiplier

        if (!TravellersConfig.COMBAT.weaponAttackSpeedEnabled) {
            return TravellersConfig.COMBAT.baseAttackCooldownTicks;
        }

        Multimap<String, AttributeModifier> mods = weapon.getAttributeModifiers(EntityEquipmentSlot.MAINHAND);
        double speedMod = 0.0;
        for (AttributeModifier mod : mods.get(SharedMonsterAttributes.ATTACK_SPEED.getName())) {
            if (mod.getOperation() == 0) speedMod += mod.getAmount(); // additive only (vanilla standard)
        }
        if (speedMod == 0.0) return scaled(TravellersConfig.COMBAT.baseAttackCooldownTicks);

        double effectiveSpeed = 4.0 + speedMod; // 4.0 = player base attack speed
        if (effectiveSpeed <= 0.0) return scaled(TravellersConfig.COMBAT.maxAttackCooldownTicks);

        int ticks = scaled((int) Math.round(20.0 / effectiveSpeed));
        return Math.max(TravellersConfig.COMBAT.minAttackCooldownTicks,
                Math.min(TravellersConfig.COMBAT.maxAttackCooldownTicks, ticks));
    }

    private static boolean isUnbreakable(ItemStack stack) {
        return stack.hasTagCompound()
                && stack.getTagCompound().hasKey("Unbreakable", 1)
                && stack.getTagCompound().getBoolean("Unbreakable");
    }

    private static int scaled(int ticks) {
        return Math.max(1, (int) Math.round(ticks * TravellersConfig.COMBAT.attackCooldownMultiplier));
    }

    /** Returns the effective damage after applying Unbreaking probability. */
    private static int applyUnbreaking(ItemStack stack, int damage) {
        int level = EnchantmentHelper.getEnchantmentLevel(Enchantments.UNBREAKING, stack);
        if (level == 0) return damage;
        int actual = 0;
        for (int i = 0; i < damage; i++) {
            if (RNG.nextInt(level + 1) == 0) actual++;
        }
        return actual;
    }
}
