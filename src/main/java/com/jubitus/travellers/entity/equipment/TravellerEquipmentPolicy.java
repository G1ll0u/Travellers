package com.jubitus.travellers.entity.equipment;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.equipment.TravellerMillPriorities.Bucket;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Enchantments;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The single authority on "should this traveller equip this item". Pure, stateless, shared by spawn
 * pools ({@code CultureDef}), live ground pickup ({@code TravellerEquipmentManager}) and offscreen
 * battle pillage ({@code VirtualBattleManager}).
 *
 * <p>Four questions:
 * <ul>
 *   <li><b>culture</b> — a traveller/raider (and non-hired workers) only equips its own culture's
 *       Millenaire signature gear, plus vanilla and other-mod items. Another culture's armour is
 *       refused outright; another culture's weapon only as a last resort (empty hand). Raiders and
 *       hired workers bypass this filter entirely.</li>
 *   <li><b>quality</b> — Millenaire's authored priority table ({@link TravellerMillPriorities});
 *       unlisted items fall back to a material-tier estimate on the same scale.</li>
 *   <li><b>broken floor</b> — a damaged item below {@code EQUIPMENT.minDurabilityPercentToEquip} is
 *       only taken when the slot is otherwise empty.</li>
 *   <li><b>upgrade</b> — higher quality wins; equal quality (or the same item) → better condition wins.</li>
 * </ul>
 */
public final class TravellerEquipmentPolicy {

    private static final double QUALITY_EPS = 0.5;
    private static final double DURABILITY_EPS = 0.05;

    private static Set<String> cultureStems;

    private TravellerEquipmentPolicy() {
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * @param cultureId   the entity's culture id (Millenaire folder name, e.g. "norman", "byzantines")
     * @param bypassCulture true for raiders and hired workers — skips the culture ownership filter
     * @param current     what the entity currently has in that slot / its best owned weapon (may be empty)
     * @param candidate   the item being considered
     * @param slot        the armour slot, or {@code null} for a weapon
     */
    public static boolean isUpgrade(String cultureId, boolean bypassCulture,
                                    ItemStack current, ItemStack candidate, @Nullable EntityEquipmentSlot slot) {
        Bucket bucket = bucketFor(candidate, slot);
        if (bucket == null) return false;
        if (isForbidden(candidate)) return false;

        boolean slotEmpty = current == null || current.isEmpty();
        if (!cultureAllows(cultureId, bypassCulture, candidate, slot, slotEmpty)) return false;
        if (!slotEmpty && !aboveBrokenFloor(candidate)) return false;
        if (slotEmpty) return true;

        double qc = quality(candidate, bucket);
        double qh = quality(current, bucket);
        if (qc > qh + QUALITY_EPS) return true;
        if (qc < qh - QUALITY_EPS) return false;
        // Same tier (or literally the same item): prefer the better-kept one.
        return durabilityFraction(candidate) > durabilityFraction(current) + DURABILITY_EPS;
    }

    /** Bucket for an item: explicit armour slot wins, otherwise bow vs melee vs armour-type. */
    @Nullable
    public static Bucket bucketFor(ItemStack stack, @Nullable EntityEquipmentSlot slot) {
        if (stack == null || stack.isEmpty()) return null;
        if (slot != null) {
            switch (slot) {
                case HEAD: return Bucket.HELMET;
                case CHEST: return Bucket.CHEST;
                case LEGS: return Bucket.LEGS;
                case FEET: return Bucket.BOOTS;
                default: return null;
            }
        }
        if (TravellerEquipmentManager.isBow(stack)) return Bucket.RANGED;
        if (TravellerEquipmentManager.isWeapon(stack)) return Bucket.MELEE;
        if (stack.getItem() instanceof ItemArmor) {
            switch (((ItemArmor) stack.getItem()).armorType) {
                case HEAD: return Bucket.HELMET;
                case CHEST: return Bucket.CHEST;
                case LEGS: return Bucket.LEGS;
                case FEET: return Bucket.BOOTS;
                default: return null;
            }
        }
        return null;
    }

    /** Millenaire priority if listed, else a material-tier estimate on the same ~40-100 scale. */
    public static double quality(ItemStack stack, Bucket bucket) {
        Integer p = TravellerMillPriorities.rawPriority(stack, bucket);
        return p != null ? p : fallbackPriority(stack, bucket);
    }

    /** Convenience: weapon quality for "which of my weapons is best" comparisons. */
    public static double weaponRank(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Double.NEGATIVE_INFINITY;
        Bucket b = TravellerEquipmentManager.isBow(stack) ? Bucket.RANGED : Bucket.MELEE;
        return quality(stack, b) + durabilityFraction(stack); // condition as sub-unit tiebreak
    }

    public static double armorRank(ItemStack stack, EntityEquipmentSlot slot) {
        Bucket b = bucketFor(stack, slot);
        if (b == null) return Double.NEGATIVE_INFINITY;
        return quality(stack, b) + durabilityFraction(stack);
    }

    /**
     * True if this entity's culture must not equip this item. Vanilla / other-mod items and
     * Millenaire items with no culture in their name are always allowed.
     */
    public static boolean cultureAllows(String cultureId, boolean bypassCulture, ItemStack stack,
                                        @Nullable EntityEquipmentSlot slot, boolean slotEmpty) {
        if (bypassCulture || !TravellersConfig.EQUIPMENT.cultureEquipmentFilterEnabled) return true;
        String owner = owningCultureStem(stack);
        if (owner == null) return true;
        if (owner.equals(stemOf(cultureId))) return true;
        // Foreign Millenaire culture gear:
        if (slot != null && slot.getSlotType() == EntityEquipmentSlot.Type.ARMOR) return false;
        return slotEmpty; // weapon: only when the entity would otherwise be unarmed
    }

    /** priority=0 in the Millenaire table, or a vanilla weapon/bow Millenaire deliberately never lists (wood/gold sword). */
    public static boolean isForbidden(ItemStack stack) {
        Bucket b = bucketFor(stack, null);
        if (b == null) return false;
        if (TravellerMillPriorities.isExplicitlyForbidden(stack, b)) return true;
        ResourceLocation rl = stack.getItem().getRegistryName();
        if (rl != null && "minecraft".equals(rl.getNamespace())) {
            boolean weaponish = TravellerEquipmentManager.isBow(stack) || TravellerEquipmentManager.isWeapon(stack);
            if (weaponish && !TravellerMillPriorities.isListed(stack, b)) return true;
        }
        return false;
    }

    /** A damaged item this far gone is only acceptable for an empty slot. */
    public static boolean aboveBrokenFloor(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isItemStackDamageable()) return true;
        int max = stack.getMaxDamage();
        if (max <= 0) return true;
        double frac = (max - stack.getItemDamage()) / (double) max;
        return frac * 100.0 >= TravellersConfig.EQUIPMENT.minDurabilityPercentToEquip;
    }

    public static double durabilityFraction(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isItemStackDamageable()) return 1.0;
        int max = stack.getMaxDamage();
        if (max <= 0) return 1.0;
        return (max - stack.getItemDamage()) / (double) max;
    }

    /** Iron tier or better — used to gate the guaranteed raider armour. */
    public static boolean isAtLeastIronArmor(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ItemArmor)) return false;
        ItemArmor.ArmorMaterial m = ((ItemArmor) stack.getItem()).getArmorMaterial();
        return m == ItemArmor.ArmorMaterial.IRON || m == ItemArmor.ArmorMaterial.DIAMOND
                || m == ItemArmor.ArmorMaterial.CHAIN;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    @Nullable
    private static String owningCultureStem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ResourceLocation rl = stack.getItem().getRegistryName();
        if (rl == null || !"millenaire".equals(rl.getNamespace())) return null;
        String path = rl.getPath().toLowerCase(Locale.ROOT);
        for (String stem : stems()) {
            if (!stem.isEmpty() && path.contains(stem)) return stem;
        }
        return null;
    }

    private static String stemOf(String cultureId) {
        if (cultureId == null) return "";
        String s = cultureId.trim().toLowerCase(Locale.ROOT);
        return s.endsWith("s") ? s.substring(0, s.length() - 1) : s;
    }

    private static Set<String> stems() {
        Set<String> s = cultureStems;
        if (s == null) {
            s = new LinkedHashSet<>();
            for (com.jubitus.travellers.entity.culture.CultureDef c : CultureRegistry.all()) {
                if (c != null && c.id != null) s.add(stemOf(c.id));
            }
            cultureStems = s;
        }
        return s;
    }

    /** Call after cultures (re)load so the stem set is rebuilt. */
    public static void invalidateCache() {
        cultureStems = null;
    }

    private static double fallbackPriority(ItemStack stack, Bucket bucket) {
        if (stack == null || stack.isEmpty()) return 0.0;
        Item it = stack.getItem();

        if (bucket == Bucket.HELMET || bucket == Bucket.CHEST || bucket == Bucket.LEGS || bucket == Bucket.BOOTS) {
            double base = 40.0;
            if (it instanceof ItemArmor) {
                switch (((ItemArmor) it).getArmorMaterial()) {
                    case DIAMOND: base = 85; break;
                    case IRON: base = 78; break;
                    case CHAIN: base = 62; break;
                    case GOLD: base = 50; break;
                    case LEATHER: base = 40; break;
                    default: base = 45; break; // modded material
                }
            }
            int prot = Enchantments.PROTECTION != null
                    ? EnchantmentHelper.getEnchantmentLevel(Enchantments.PROTECTION, stack) : 0;
            return base + prot * 3.0;
        }

        // Weapon / bow
        double base;
        if (it instanceof ItemSword) {
            switch (((ItemSword) it).getToolMaterialName()) {
                case "DIAMOND": base = 90; break;
                case "IRON": base = 80; break;
                case "STONE": base = 70; break;
                case "GOLD": base = 45; break;
                case "WOOD": base = 20; break;
                default: base = 60; break; // modded material
            }
        } else if (it instanceof ItemBow) {
            base = 78;
        } else {
            base = 55; // generic melee item
        }
        int sharp = Enchantments.SHARPNESS != null
                ? EnchantmentHelper.getEnchantmentLevel(Enchantments.SHARPNESS, stack) : 0;
        return base + sharp * 3.0;
    }
}
