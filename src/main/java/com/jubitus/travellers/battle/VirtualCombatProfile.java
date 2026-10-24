package com.jubitus.travellers.battle;

import com.google.common.collect.Multimap;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.combat.TravellerCombatLogic;
import com.jubitus.travellers.combat.TravellerItemDamage;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.init.Enchantments;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable combat stats derived from a virtual record's frozen entity NBT.
 * Mirrors what the loaded entity would fight with (base attributes + best real weapon
 * + equipped armor), so virtual and physical combat produce comparable outcomes.
 */
public final class VirtualCombatProfile {

    public final float maxHealth;
    public final float meleeDamage;
    public final int attackCooldownTicks;
    public final boolean isArcher;
    public final float rangedDamage;
    public final int rangedCooldownTicks;
    public final double rangedMinDist;
    public final double rangedMaxDist;
    public final float armor;
    public final float toughness;
    /** A real shield in the offhand and no bow: virtual hits may be parried. */
    public final boolean hasShield;
    public final boolean hasMeleeWeapon;
    // Worn chestplate (EMPTY when none), for the Formation Hub's group summary.
    public final ItemStack chestArmour;

    private VirtualCombatProfile(float maxHealth, float meleeDamage, int attackCooldownTicks,
                                 boolean isArcher, float rangedDamage, int rangedCooldownTicks,
                                 double rangedMinDist, double rangedMaxDist,
                                 float armor, float toughness, boolean hasShield,
                                 boolean hasMeleeWeapon, ItemStack chestArmour) {
        this.maxHealth = maxHealth;
        this.meleeDamage = meleeDamage;
        this.attackCooldownTicks = attackCooldownTicks;
        this.isArcher = isArcher;
        this.rangedDamage = rangedDamage;
        this.rangedCooldownTicks = rangedCooldownTicks;
        this.rangedMinDist = rangedMinDist;
        this.rangedMaxDist = rangedMaxDist;
        this.armor = armor;
        this.toughness = toughness;
        this.hasShield = hasShield;
        this.hasMeleeWeapon = hasMeleeWeapon;
        this.chestArmour = chestArmour;
    }

    /** No weapon, no bow and no armour. */
    public boolean isDefenceless() {
        return !isArcher && !hasMeleeWeapon && armor <= 0f;
    }

    public static VirtualCombatProfile fromEntityNbt(NBTTagCompound nbt, String entityClass) {
        float defaultMaxHealth = "WORKER".equals(entityClass)
                ? (float) TravellersConfig.ATTRIBUTES.workerMaxHealth
                : TravellersConfig.ATTRIBUTES.travellerMaxHealth;
        float maxHealth = (float) attributeBase(nbt, "generic.maxHealth", defaultMaxHealth);
        float baseAttack = (float) attributeBase(nbt, "generic.attackDamage",
                TravellersConfig.ATTRIBUTES.attackDamage);

        List<ItemStack> hand = readStackList(nbt, "HandItems");
        List<ItemStack> backpack = readBackpack(nbt);

        ItemStack weapon = pickBestWeapon(hand, backpack);
        float meleeDamage = TravellerCombatLogic.computeMeleeDamage(
                baseAttack + weaponAttackModifier(weapon), weapon, null);
        int cooldown = TravellerItemDamage.computeAttackCooldownTicks(weapon);

        boolean archer = containsBow(hand) || containsBow(backpack);
        float rangedDamage = TravellersConfig.COMBAT.rangedBaseDamage
                * TravellersConfig.COMBAT.rangedDamageMultiplier;

        float armor = 0f, toughness = 0f;
        List<ItemStack> worn = readStackList(nbt, "ArmorItems");
        // Saved in slot index order: feet, legs, chest, head.
        ItemStack chest = worn.size() > EntityEquipmentSlot.CHEST.getIndex()
                ? worn.get(EntityEquipmentSlot.CHEST.getIndex()) : ItemStack.EMPTY;
        for (ItemStack s : worn) {
            if (s.isEmpty() || !(s.getItem() instanceof ItemArmor)) continue;
            EntityEquipmentSlot slot = ((ItemArmor) s.getItem()).armorType;
            Multimap<String, AttributeModifier> mods = s.getAttributeModifiers(slot);
            armor += sumAdditive(mods, SharedMonsterAttributes.ARMOR.getName());
            toughness += sumAdditive(mods, SharedMonsterAttributes.ARMOR_TOUGHNESS.getName());
        }

        return new VirtualCombatProfile(maxHealth, meleeDamage, Math.max(1, cooldown),
                archer, rangedDamage, Math.max(1, TravellersConfig.COMBAT.rangedCooldown),
                TravellersConfig.COMBAT.rangedMinDist, TravellersConfig.COMBAT.rangedMaxDist,
                armor, toughness, !archer && containsShield(hand), !weapon.isEmpty(), chest);
    }

    private static boolean containsShield(List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (!s.isEmpty() && s.getItem().isShield(s, null)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // NBT helpers (no live entity)
    // -------------------------------------------------------------------------

    private static double attributeBase(NBTTagCompound nbt, String name, double fallback) {
        if (nbt == null || !nbt.hasKey("Attributes", 9)) return fallback;
        NBTTagList list = nbt.getTagList("Attributes", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            if (name.equals(tag.getString("Name"))) return tag.getDouble("Base");
        }
        return fallback;
    }

    private static List<ItemStack> readStackList(NBTTagCompound nbt, String key) {
        List<ItemStack> out = new ArrayList<>();
        if (nbt == null || !nbt.hasKey(key, 9)) return out;
        NBTTagList list = nbt.getTagList(key, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            out.add(new ItemStack(list.getCompoundTagAt(i)));
        }
        return out;
    }

    private static List<ItemStack> readBackpack(NBTTagCompound nbt) {
        List<ItemStack> out = new ArrayList<>();
        if (nbt == null || !nbt.hasKey("TravellerInv", 10)) return out;
        NBTTagCompound inv = nbt.getCompoundTag("TravellerInv");
        if (!inv.hasKey("Items", 9)) return out;
        NBTTagList items = inv.getTagList("Items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            out.add(new ItemStack(items.getCompoundTagAt(i)));
        }
        return out;
    }

    /** Best real weapon among hand + backpack (ghost display copies are skipped). */
    private static ItemStack pickBestWeapon(List<ItemStack> hand, List<ItemStack> backpack) {
        ItemStack best = ItemStack.EMPTY;
        double bestScore = 0.0;
        List<ItemStack> all = new ArrayList<>(hand);
        all.addAll(backpack);
        for (ItemStack s : all) {
            if (s.isEmpty() || isGhost(s)) continue;
            double dmg = weaponAttackModifier(s);
            if (dmg <= 0.0 && !(s.getItem() instanceof ItemSword)) continue;
            double score = dmg + sharpnessBonus(s);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static float weaponAttackModifier(ItemStack s) {
        if (s.isEmpty()) return 0f;
        return sumAdditive(s.getAttributeModifiers(EntityEquipmentSlot.MAINHAND),
                SharedMonsterAttributes.ATTACK_DAMAGE.getName());
    }

    private static float sumAdditive(Multimap<String, AttributeModifier> mods, String name) {
        float sum = 0f;
        for (AttributeModifier m : mods.get(name)) {
            if (m.getOperation() == 0) sum += (float) m.getAmount();
        }
        return sum;
    }

    private static double sharpnessBonus(ItemStack s) {
        int lvl = Enchantments.SHARPNESS != null
                ? EnchantmentHelper.getEnchantmentLevel(Enchantments.SHARPNESS, s) : 0;
        return lvl > 0 ? 0.51 * lvl + 0.5 : 0.0;
    }

    private static boolean containsBow(List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (!s.isEmpty() && s.getItem() instanceof ItemBow && !isGhost(s)) return true;
        }
        return false;
    }

    private static boolean isGhost(ItemStack s) {
        return com.jubitus.travellers.unit.equipment.TravellerEquipmentManager.isGhostItem(s);
    }

    @Override
    public String toString() {
        return String.format("hp=%.1f dmg=%.1f cd=%dt%s ranged=%.1f armor=%.1f/%.1f",
                maxHealth, meleeDamage, attackCooldownTicks,
                isArcher ? " archer" : "", rangedDamage, armor, toughness);
    }
}
