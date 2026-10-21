package com.jubitus.travellers.unit.equipment;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.culture.CultureDef;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

/**
 * Forced gear for {@code /summonworkers} and raid waves: overrides parts of the natural spawn roll, culture
 * items of the asked tier first, the vanilla piece as fallback. Runs after {@code onInitialSpawn}.
 */
public final class SummonLoadout {

    public enum Gear { NATURAL, NONE, LEATHER, CHAIN, IRON, DIAMOND }

    public enum Weapon { NATURAL, MELEE, BOW }

    /** Melee weapon tier; NATURAL keeps the culture's own weapon. */
    public enum Blade { NATURAL, STONE, IRON, DIAMOND }

    public enum Choice { NATURAL, YES, NO }

    private static final EntityEquipmentSlot[] ARMOR_SLOTS = {
            EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET};
    private static final ItemArmor.ArmorMaterial[] ARMOR_TIERS = {
            ItemArmor.ArmorMaterial.LEATHER, ItemArmor.ArmorMaterial.CHAIN,
            ItemArmor.ArmorMaterial.IRON, ItemArmor.ArmorMaterial.DIAMOND};
    private static final Item[] SWORD_TIERS = {Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD};

    private SummonLoadout() {
    }

    /** The melee tier follows the armour: stone up to chainmail, then iron and diamond. */
    public static void apply(EntityTraveller w, @Nullable CultureDef c, Random r, CultureDef.WeightProfile profile,
                             Gear gear, Weapon weapon, Choice shield) {
        Blade blade = gear == Gear.NATURAL ? Blade.NATURAL
                : gear == Gear.DIAMOND ? Blade.DIAMOND : gear == Gear.IRON ? Blade.IRON : Blade.STONE;
        apply(w, c, r, profile, gear, weapon, blade, shield);
    }

    public static void apply(EntityTraveller w, @Nullable CultureDef c, Random r, CultureDef.WeightProfile profile,
                             Gear gear, Weapon weapon, Blade blade, Choice shield) {
        if (gear != Gear.NATURAL) {
            for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
                if (gear == Gear.NONE) w.setItemStackToSlot(slot, ItemStack.EMPTY);
                else wear(w, c, r, profile, slot, gear);
            }
        }

        boolean hasBow = w.equipment.hasBowAnywhere();
        boolean wantBow = weapon == Weapon.BOW || (weapon == Weapon.NATURAL && hasBow && shield != Choice.YES);
        boolean keep = wantBow ? hasBow
                : !hasBow && blade == Blade.NATURAL && (weapon == Weapon.NATURAL || hasMelee(w.inventory));
        if (!keep) {
            removeWeapons(w.inventory);
            ItemStack chosen = wantBow ? bow(c, r, profile) : melee(c, r, profile, blade);
            ItemHandlerHelper.insertItemStacked(w.inventory, chosen, false);
        }

        // Shield last, on the final weapon and armour (natural keeps the usual chance, iron bonus included).
        if (TravellerEquipmentManager.isShield(w.getHeldItemOffhand())) {
            w.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
        if (shield == Choice.YES) {
            w.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        } else if (shield == Choice.NATURAL) {
            w.equipment.trySpawnShield(r, profile);
        }
    }

    /** Puts one armour piece of the given tier (LEATHER or better) on the slot, culture item first. */
    public static void wear(EntityTraveller w, @Nullable CultureDef c, Random r, CultureDef.WeightProfile profile,
                            EntityEquipmentSlot slot, Gear gear) {
        w.setItemStackToSlot(slot, armorPiece(c, r, profile, slot, gear));
    }

    private static ItemStack armorPiece(@Nullable CultureDef c, Random r, CultureDef.WeightProfile profile,
                                        EntityEquipmentSlot slot, Gear gear) {
        ItemArmor.ArmorMaterial wanted = ARMOR_TIERS[gear.ordinal() - Gear.LEATHER.ordinal()];
        if (c != null) {
            List<ItemStack> pool = c.spawnPool(profile, s ->
                    s.getItem() instanceof ItemArmor
                            && ((ItemArmor) s.getItem()).armorType == slot
                            && !TravellerEquipmentManager.isGoldTier(s)
                            && armorTier((ItemArmor) s.getItem(), slot) == wanted);
            if (!pool.isEmpty()) return pool.get(r.nextInt(pool.size())).copy();
        }
        return new ItemStack(vanillaArmor(wanted, slot));
    }

    /** The vanilla material whose protection on this slot is closest to the piece's (ties go to the lower tier). */
    private static ItemArmor.ArmorMaterial armorTier(ItemArmor piece, EntityEquipmentSlot slot) {
        ItemArmor.ArmorMaterial best = ARMOR_TIERS[0];
        int bestGap = Integer.MAX_VALUE;
        for (ItemArmor.ArmorMaterial m : ARMOR_TIERS) {
            int gap = Math.abs(piece.damageReduceAmount - m.getDamageReductionAmount(slot));
            if (gap < bestGap) {
                bestGap = gap;
                best = m;
            }
        }
        return best;
    }

    private static Item vanillaArmor(ItemArmor.ArmorMaterial m, EntityEquipmentSlot slot) {
        switch (m) {
            case LEATHER:
                return slot == EntityEquipmentSlot.HEAD ? Items.LEATHER_HELMET : slot == EntityEquipmentSlot.CHEST
                        ? Items.LEATHER_CHESTPLATE : slot == EntityEquipmentSlot.LEGS ? Items.LEATHER_LEGGINGS : Items.LEATHER_BOOTS;
            case CHAIN:
                return slot == EntityEquipmentSlot.HEAD ? Items.CHAINMAIL_HELMET : slot == EntityEquipmentSlot.CHEST
                        ? Items.CHAINMAIL_CHESTPLATE : slot == EntityEquipmentSlot.LEGS ? Items.CHAINMAIL_LEGGINGS : Items.CHAINMAIL_BOOTS;
            case IRON:
                return slot == EntityEquipmentSlot.HEAD ? Items.IRON_HELMET : slot == EntityEquipmentSlot.CHEST
                        ? Items.IRON_CHESTPLATE : slot == EntityEquipmentSlot.LEGS ? Items.IRON_LEGGINGS : Items.IRON_BOOTS;
            default:
                return slot == EntityEquipmentSlot.HEAD ? Items.DIAMOND_HELMET : slot == EntityEquipmentSlot.CHEST
                        ? Items.DIAMOND_CHESTPLATE : slot == EntityEquipmentSlot.LEGS ? Items.DIAMOND_LEGGINGS : Items.DIAMOND_BOOTS;
        }
    }

    private static ItemStack melee(@Nullable CultureDef c, Random r, CultureDef.WeightProfile profile, Blade blade) {
        if (blade == Blade.NATURAL) {
            ItemStack natural = c != null ? c.randomMeleeOrTool(r, profile) : ItemStack.EMPTY;
            return natural.isEmpty() ? new ItemStack(Items.STONE_SWORD) : natural;
        }
        Item wanted = SWORD_TIERS[blade.ordinal() - Blade.STONE.ordinal()];
        if (c != null) {
            List<ItemStack> pool = c.spawnPool(profile, s ->
                    TravellerEquipmentManager.isWeapon(s)
                            && !TravellerEquipmentManager.isBelowWeaponFloor(s)
                            && !TravellerEquipmentManager.isGoldTier(s)
                            && swordTier(s) == wanted);
            if (!pool.isEmpty()) return pool.get(r.nextInt(pool.size())).copy();
        }
        return new ItemStack(wanted);
    }

    /** The vanilla sword whose damage is closest to this weapon's (ties go to the lower tier). */
    private static Item swordTier(ItemStack s) {
        double dmg = TravellerEquipmentManager.getMainHandAttackDamage(s);
        Item best = SWORD_TIERS[0];
        double bestGap = Double.MAX_VALUE;
        for (Item sword : SWORD_TIERS) {
            double gap = Math.abs(dmg - TravellerEquipmentManager.getMainHandAttackDamage(new ItemStack(sword)));
            if (gap < bestGap) {
                bestGap = gap;
                best = sword;
            }
        }
        return best;
    }

    private static ItemStack bow(@Nullable CultureDef c, Random r, CultureDef.WeightProfile profile) {
        ItemStack bow = c != null ? c.randomBow(r, profile) : ItemStack.EMPTY;
        return bow.isEmpty() ? new ItemStack(Items.BOW) : bow;
    }

    private static boolean hasMelee(ItemStackHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (TravellerEquipmentManager.isWeapon(inv.getStackInSlot(i))) return true;
        }
        return false;
    }

    private static void removeWeapons(ItemStackHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (TravellerEquipmentManager.isWeapon(s) || TravellerEquipmentManager.isBow(s)) {
                inv.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
    }
}
