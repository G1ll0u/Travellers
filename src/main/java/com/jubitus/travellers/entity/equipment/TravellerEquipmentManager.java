package com.jubitus.travellers.entity.equipment;

import com.jubitus.travellers.config.TravellersConfig;

import com.google.common.collect.Multimap;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Centralises all equipment logic: scoring, ghost items, spawn init, pickup, loot drops,
 * and weapon visibility management.
 * Each EntityTraveller holds one instance as {@code public final TravellerEquipmentManager equipment}.
 * <p>
 * To change how equipment is scored, dropped, or managed, edit this class.
 */
public class TravellerEquipmentManager {

    // NBT tags for ghost items — visual-only items that carry no stat modifiers
    public static final String GHOST_SWORD_TAG = "MillmixGhostSword";
    public static final String GHOST_BOW_TAG = "TravellerGhostBow";
    public static final String GHOST_TORCH_TAG = "TravellerGhostTorch";
    public static final String GHOST_ROD_TAG = "TravellerGhostRod";
    public static final String GHOST_BLOCK_TAG = "TravellerGhostBlock";

    private final EntityTraveller entity;

    private int swordHideTicks = 0;
    private int pickupCooldown = 0;
    private int pickupScanTicker = 0;

    public TravellerEquipmentManager(EntityTraveller entity) {
        this.entity = entity;
    }

    // -------------------------------------------------------------------------
    // Item scoring — edit these to change how equipment is valued
    // -------------------------------------------------------------------------

    /**
     * Scores a melee weapon. Higher = better.
     * Accounts for base damage, material tier, Sharpness enchant, and remaining durability.
     */
    public double scoreWeapon(ItemStack s) {
        if (s.isEmpty()) return Double.NEGATIVE_INFINITY;

        double baseDmg;
        int materialRank;
        if (s.getItem() instanceof ItemSword) {
            ItemSword sw = (ItemSword) s.getItem();
            baseDmg = sw.getAttackDamage();
            String mat = sw.getToolMaterialName();
            materialRank = "DIAMOND".equals(mat) ? 5
                    : "IRON".equals(mat) ? 4
                    : "STONE".equals(mat) ? 3
                    : "GOLD".equals(mat) ? 3
                    : "WOOD".equals(mat) ? 2 : 1;
        } else {
            baseDmg = getMainHandAttackDamage(s);
            materialRank = 3;
        }

        int sharp = Enchantments.SHARPNESS != null
                ? EnchantmentHelper.getEnchantmentLevel(Enchantments.SHARPNESS, s) : 0;
        double ench = sharp > 0 ? (0.51 * sharp + 0.5) : 0.0;

        return baseDmg + ench + durabilityBias(s) + (materialRank * 0.1);
    }

    /**
     * Scores an armor piece for the given slot. Higher = better.
     * Accounts for armor value, toughness, Protection enchant, and remaining durability.
     */
    public double scoreArmor(ItemStack s, EntityEquipmentSlot slot) {
        if (s.isEmpty()) return Double.NEGATIVE_INFINITY;

        Multimap<String, AttributeModifier> a = s.getAttributeModifiers(slot);
        double armor = sumAttr(a, SharedMonsterAttributes.ARMOR.getName());
        double tough = sumAttr(a, SharedMonsterAttributes.ARMOR_TOUGHNESS.getName());
        int prot = Enchantments.PROTECTION != null
                ? EnchantmentHelper.getEnchantmentLevel(Enchantments.PROTECTION, s) : 0;
        double ench = prot * 0.4;
        // Chest > legs > boots/helm
        int slotWeight = (slot == EntityEquipmentSlot.CHEST) ? 3
                : (slot == EntityEquipmentSlot.LEGS) ? 2 : 1;

        return (armor + 0.5 * tough) * slotWeight + ench + durabilityBias(s);
    }

    // -------------------------------------------------------------------------
    // Item classification
    // -------------------------------------------------------------------------

    /** True for swords and other melee weapons (tool-class "sword" or high attack damage). */
    public boolean isWeapon(ItemStack s) {
        if (s.isEmpty()) return false;
        Item it = s.getItem();
        if (it instanceof ItemSword) return true;
        try {
            Set<String> classes = it.getToolClasses(s);
            if (classes != null && classes.contains("sword")) return true;
        } catch (Throwable ignored) {}

        double dmg = getMainHandAttackDamage(s);
        if (dmg >= 3.0) {
            try {
                Set<String> classes = it.getToolClasses(s);
                if (classes != null && classes.contains("axe")) return false;
            } catch (Throwable ignored) {}
            ResourceLocation rl = Item.REGISTRY.getNameForObject(it);
            return rl == null || !rl.getPath().toLowerCase(Locale.ROOT).contains("axe");
        }
        return false;
    }

    /** True for any ItemBow. */
    public boolean isBow(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemBow;
    }

    /** True when s is a valid armor piece for the given slot. */
    public boolean isArmorForSlot(ItemStack s, EntityEquipmentSlot slot) {
        if (s.isEmpty()) return false;
        Item it = s.getItem();
        if (it instanceof ItemArmor) return ((ItemArmor) it).armorType == slot;
        try { return it.isValidArmor(s, slot, entity); } catch (Throwable ignore) { return false; }
    }

    // -------------------------------------------------------------------------
    // Upgrade checks
    // -------------------------------------------------------------------------

    /** True if candidate is strictly better than the best weapon currently held/stashed. */
    public boolean isWeaponUpgrade(ItemStack candidate) {
        if (!isWeapon(candidate)) return false;
        double cand = scoreWeapon(candidate);
        double have = Double.NEGATIVE_INFINITY;

        ItemStack hand = entity.getHeldItemMainhand();
        if (!hand.isEmpty() && isWeapon(hand) && !isGhostWeapon(hand)) {
            have = Math.max(have, scoreWeapon(hand));
        }
        ItemStackHandler inv = entity.inventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (isWeapon(s)) have = Math.max(have, scoreWeapon(s));
        }
        return cand > have + 0.05;
    }

    /** True if candidate is strictly better than the best armor already equipped/stashed for the slot. */
    public boolean isArmorUpgrade(ItemStack candidate, EntityEquipmentSlot slot) {
        if (!isArmorForSlot(candidate, slot)) return false;
        double cand = scoreArmor(candidate, slot);

        ItemStack cur = entity.getItemStackFromSlot(slot);
        double have = cur.isEmpty() ? Double.NEGATIVE_INFINITY : scoreArmor(cur, slot);

        ItemStackHandler inv = entity.inventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && isArmorForSlot(s, slot)) have = Math.max(have, scoreArmor(s, slot));
        }
        return cand > have + 0.05;
    }

    // -------------------------------------------------------------------------
    // Ghost items — visual-only copies that carry no attribute modifiers
    // -------------------------------------------------------------------------

    public ItemStack makeGhostWeapon(ItemStack src) {
        return tagGhost(src, GHOST_SWORD_TAG);
    }

    public ItemStack makeGhostBow(ItemStack src) {
        return tagGhost(src, GHOST_BOW_TAG);
    }

    public ItemStack makeGhostTorch() {
        ItemStack copy = new ItemStack(Blocks.TORCH);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean(GHOST_TORCH_TAG, true);
        copy.setTagCompound(tag);
        return copy;
    }

    public boolean isGhostWeapon(ItemStack s) {
        return checkGhostTag(s, GHOST_SWORD_TAG);
    }

    public boolean isGhostBow(ItemStack s) {
        return checkGhostTag(s, GHOST_BOW_TAG);
    }

    public boolean isGhostTorch(ItemStack s) {
        return !s.isEmpty()
                && s.getItem() == Item.getItemFromBlock(Blocks.TORCH)
                && s.hasTagCompound()
                && s.getTagCompound().getBoolean(GHOST_TORCH_TAG);
    }

    /**
     * Visual-only item representing the block a traveller is about to place while building a shelter
     * block by block. Returns {@link ItemStack#EMPTY} if the block has no item form (e.g. air, or a
     * block — like doors/beds — whose item was registered standalone rather than via the block→item
     * map) — caller should just leave the hand empty then.
     *
     * <p>Uses {@link net.minecraft.block.Block#damageDropped} rather than {@code getMetaFromState}: the
     * latter is the full in-world metadata, which often encodes orientation bits (e.g. a wall torch's
     * facing) that the item model never registered a variant for — that produced the "missing model"
     * pink/black checkerboard. {@code damageDropped} is vanilla's own "meta for the item representation
     * of this block state" (the same thing block breaking/picking uses), which strips exactly those bits.
     */
    public ItemStack makeGhostBlock(net.minecraft.block.state.IBlockState state) {
        Item item = Item.getItemFromBlock(state.getBlock());
        if (item == Items.AIR) return ItemStack.EMPTY;
        ItemStack copy = new ItemStack(item, 1, state.getBlock().damageDropped(state));
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean(GHOST_BLOCK_TAG, true);
        copy.setTagCompound(tag);
        return copy;
    }

    public boolean isGhostBlock(ItemStack s) {
        return checkGhostTag(s, GHOST_BLOCK_TAG);
    }

    // -------------------------------------------------------------------------
    // Backpack / inventory queries
    // -------------------------------------------------------------------------

    public boolean hasBowInBackpack() {
        ItemStackHandler inv = entity.inventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isBow(inv.getStackInSlot(i))) return true;
        }
        return false;
    }

    @Nullable
    public ItemStack findBowInBackpack() {
        ItemStackHandler inv = entity.inventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (isBow(s)) return s;
        }
        return null;
    }

    public boolean hasBowAnywhere() {
        ItemStack hand = entity.getHeldItemMainhand();
        return (!hand.isEmpty() && hand.getItem() instanceof ItemBow) || hasBowInBackpack();
    }

    public ItemStack findBestWeaponInInventory() {
        int bestIdx = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        ItemStackHandler inv = entity.inventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!isWeapon(s)) continue;
            double sc = scoreWeapon(s);
            if (sc > bestScore) { bestScore = sc; bestIdx = i; }
        }
        return bestIdx >= 0 ? inv.getStackInSlot(bestIdx) : ItemStack.EMPTY;
    }

    // -------------------------------------------------------------------------
    // Spawn equipment initialisation
    // -------------------------------------------------------------------------

    /**
     * Equips the traveller at spawn based on culture loot tables.
     * Weapons go into the inventory handler; armor goes directly to entity slots.
     * Call from {@code onInitialSpawn} after the culture is set.
     */
    public void initSpawnEquipment(CultureDef c, Random r) {
        if (entity.world.isRemote || c == null) return;

        ItemStack meleeOrTool = c.randomMeleeOrTool(r);
        if (!meleeOrTool.isEmpty()) entity.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, meleeOrTool);

        if (r.nextFloat() < 0.20f) {
            ItemStack bow = c.randomBow(r);
            if (!bow.isEmpty()) entity.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, bow);
        }

        trySpawnArmor(c, r, EntityEquipmentSlot.HEAD, 0.15f);
        trySpawnArmor(c, r, EntityEquipmentSlot.CHEST, 0.10f);
        trySpawnArmor(c, r, EntityEquipmentSlot.LEGS, 0.08f);
        trySpawnArmor(c, r, EntityEquipmentSlot.FEET, 0.12f);
    }

    // -------------------------------------------------------------------------
    // Equipment sync: equip best armor found in entity.inventory
    // -------------------------------------------------------------------------

    /**
     * Scans entity.inventory for armor upgrades and equips them to entity slots.
     * Displaced armor is returned to inventory. Only runs server-side.
     * Call periodically (e.g. every 100 ticks) or on GUI close.
     */
    public void syncEquipmentFromInventory() {
        if (entity.world.isRemote) return;
        for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{
                EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET}) {
            syncArmorSlot(slot);
        }
    }

    private void syncArmorSlot(EntityEquipmentSlot slot) {
        ItemStack current = entity.getItemStackFromSlot(slot);
        double currentScore = current.isEmpty() ? Double.NEGATIVE_INFINITY : scoreArmor(current, slot);

        int bestIdx = -1;
        double bestScore = currentScore;
        for (int i = 0; i < entity.inventory.getSlots(); i++) {
            ItemStack s = entity.inventory.getStackInSlot(i);
            if (!isArmorForSlot(s, slot)) continue;
            double sc = scoreArmor(s, slot);
            if (sc > bestScore + 0.05) {
                bestScore = sc;
                bestIdx = i;
            }
        }

        if (bestIdx >= 0) {
            ItemStack best = entity.inventory.extractItem(bestIdx, 1, false);
            if (!current.isEmpty()) {
                ItemHandlerHelper.insertItemStacked(entity.inventory, current.copy(), false);
            }
            entity.setItemStackToSlot(slot, best);
        }
    }

    // -------------------------------------------------------------------------
    // Ground item pickup
    // -------------------------------------------------------------------------

    /**
     * Called by {@code updateEquipmentIfNeeded}. Handles armour upgrades, bow stash,
     * weapon upgrades and food pickup.
     */
    public void handleGroundPickup(EntityItem itemEntity) {
        ItemStack stack = itemEntity.getItem();

        for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{
                EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET}) {
            if (isArmorForSlot(stack, slot) && isArmorUpgrade(stack, slot)) {
                ItemStack one = stack.copy();
                one.setCount(1);
                safeReplaceInSlot(slot, one, true);
                shrinkOrRemove(itemEntity);
                entity.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
                return;
            }
        }

        if (isBow(stack) && !hasBowInBackpack()) {
            takeOneAndStash(itemEntity);
            entity.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
            return;
        }

        if (isWeapon(stack) && isWeaponUpgrade(stack)) {
            takeOneAndStash(itemEntity);
            entity.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
            keepOnlyBestWeapon();
            return;
        }

        if (stack.getItem() instanceof ItemFood) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(entity.inventory, stack.copy(), false);
            if (remainder.isEmpty()) itemEntity.setDead();
            else itemEntity.setItem(remainder);
            entity.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
        }
    }

    /** Full scan of ground items within 3 blocks — upgrades armour, picks up bows, weapons, food. */
    public void scanAndPickupGroundItems() {
        AxisAlignedBB box = entity.getEntityBoundingBox().grow(3.0, 3.0, 3.0);
        List<EntityItem> drops = entity.world.getEntitiesWithinAABB(EntityItem.class, box,
                e -> e != null && e.isEntityAlive() && !e.cannotPickup() && !e.getItem().isEmpty());

        boolean picked = false;
        for (EntityItem ei : drops) {
            ItemStack stack = ei.getItem();

            if (stack.getItem() instanceof ItemFood) {
                ItemStack rem = ItemHandlerHelper.insertItemStacked(entity.inventory, stack.copy(), false);
                if (rem.isEmpty()) { ei.setDead(); picked = true; continue; }
                if (rem.getCount() != stack.getCount()) { ei.setItem(rem); picked = true; continue; }
            }
            if (isBow(stack) && !hasBowInBackpack()) { takeOneAndStash(ei); picked = true; continue; }
            if (isWeapon(stack) && isWeaponUpgrade(stack)) { takeOneAndStash(ei); picked = true; continue; }

            for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{
                    EntityEquipmentSlot.FEET, EntityEquipmentSlot.LEGS,
                    EntityEquipmentSlot.CHEST, EntityEquipmentSlot.HEAD}) {
                if (isArmorForSlot(stack, slot) && isArmorUpgrade(stack, slot)) {
                    ItemStack one = stack.copy();
                    one.setCount(1);
                    ItemStack old = entity.getItemStackFromSlot(slot);
                    entity.setItemStackToSlot(slot, one);
                    if (!old.isEmpty()) ItemHandlerHelper.insertItemStacked(entity.inventory, old, false);
                    shrinkOrRemove(ei);
                    picked = true;
                    break;
                }
            }
        }

        if (picked) {
            pickupCooldown = 20;
            entity.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 0.9F + entity.getRNG().nextFloat() * 0.2F);
        }
    }

    public void tryScanAndPickupGroundItemsThrottled() {
        if (pickupCooldown > 0) return;
        if ((pickupScanTicker++ & 15) != 0) return;
        scanAndPickupGroundItems();
    }

    // -------------------------------------------------------------------------
    // Loot drops
    // -------------------------------------------------------------------------

    /**
     * Drops up to 2 random backpack items with a 30% chance each.
     * Shuffles slot order to avoid early-slot bias.
     */
    public void dropBackpackLoot() {
        if (entity.world.isRemote || entity.inventory == null) return;
        final float DROP_CHANCE = 0.30f;
        final int MAX_DROPS = 2;

        int n = entity.inventory.getSlots();
        List<Integer> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) slots.add(i);
        Collections.shuffle(slots, entity.getRNG());

        int dropped = 0;
        for (int slot : slots) {
            if (dropped >= MAX_DROPS) break;
            ItemStack s = entity.inventory.getStackInSlot(slot);
            if (s.isEmpty()) continue;
            if (entity.getRNG().nextFloat() < DROP_CHANCE) {
                EntityItem item = new EntityItem(entity.world, entity.posX, entity.posY + 0.5, entity.posZ, s.copy());
                item.motionX = (entity.getRNG().nextDouble() - 0.5) * 0.1;
                item.motionY = 0.1;
                item.motionZ = (entity.getRNG().nextDouble() - 0.5) * 0.1;
                entity.world.spawnEntity(item);
                entity.inventory.extractItem(slot, s.getCount(), false);
                dropped++;
            }
        }
    }

    /**
     * Drops ALL items from the given inventory (used for worker death).
     * Each item spawns as an EntityItem with the default pickup delay.
     */
    public void dropInventoryFull(ItemStackHandler inv) {
        if (entity.world.isRemote || inv == null) return;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            EntityItem ei = new EntityItem(entity.world, entity.posX, entity.posY + 0.5, entity.posZ, stack.copy());
            ei.setDefaultPickupDelay();
            entity.world.spawnEntity(ei);
            inv.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    // -------------------------------------------------------------------------
    // Weapon visibility (combat draw / sheathe)
    // -------------------------------------------------------------------------

    /**
     * Manages the ghost weapon in the main hand slot:
     * shows best weapon while in combat and hides it after {@code SWORD_HIDE_DELAY} ticks of peace.
     * Must be called every server tick from {@code onLivingUpdate}.
     */
    public void handleWeaponVisibility() {
        if (entity.isAimingBow()) return;
        if (entity.world.isRemote) return;

        if (entity.combat.getInCombatFlag()) {
            swordHideTicks = TravellersConfig.COMBAT.swordHideDelay;
            ItemStack best = findBestWeaponInInventory();
            if (!best.isEmpty()) {
                ItemStack cur = entity.getHeldItemMainhand();
                if (cur.isEmpty() || !isGhostWeapon(cur) || scoreWeapon(best) > scoreWeapon(cur)) {
                    entity.setHeldItem(EnumHand.MAIN_HAND, makeGhostWeapon(best));
                }
            } else {
                if (isGhostWeapon(entity.getHeldItemMainhand())) {
                    entity.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                }
            }
        } else {
            if (swordHideTicks > 0) {
                swordHideTicks--;
            } else {
                ItemStack cur = entity.getHeldItemMainhand();
                if (isGhostWeapon(cur)) {
                    entity.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                } else if (!cur.isEmpty() && isWeapon(cur)) {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(entity.inventory, cur.copy(), false);
                    if (remainder.isEmpty()) entity.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                }
            }
        }
    }

    /** Immediately equips the best available ghost weapon. Called when combat is entered. */
    public void ensureBestWeaponNow() {
        ItemStack best = findBestWeaponInInventory();
        if (!best.isEmpty()) entity.setHeldItem(EnumHand.MAIN_HAND, makeGhostWeapon(best));
    }

    /** Delays weapon-sheathe timer, used by the ranged AI to keep the sword shown while aiming. */
    public void delaySwordHideVisual() {
        this.swordHideTicks = TravellersConfig.COMBAT.swordHideDelay;
    }

    // -------------------------------------------------------------------------
    // Weapon pruning — keep only the best weapon to avoid inventory bloat
    // -------------------------------------------------------------------------

    public void keepOnlyBestWeapon() {
        if (entity.world.isRemote) return;
        ItemStackHandler inv = entity.inventory;

        int bestInvIdx = -1;
        double bestInvScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!isWeapon(s)) continue;
            double sc = scoreWeapon(s);
            if (sc > bestInvScore) { bestInvScore = sc; bestInvIdx = i; }
        }

        ItemStack hand = entity.getHeldItemMainhand();
        boolean handReal = !hand.isEmpty() && isWeapon(hand) && !isGhostWeapon(hand);
        boolean bestIsHand = handReal && scoreWeapon(hand) >= bestInvScore;

        if (!bestIsHand && handReal && bestInvIdx >= 0) {
            dropStack(hand.copy());
            entity.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }

        for (int i = 0; i < inv.getSlots(); i++) {
            if (i == bestInvIdx) continue;
            ItemStack s = inv.getStackInSlot(i);
            if (!isWeapon(s)) continue;
            ItemStack extracted = inv.extractItem(i, s.getCount(), false);
            if (!extracted.isEmpty()) dropStack(extracted);
        }
    }

    // -------------------------------------------------------------------------
    // Pickup cooldown
    // -------------------------------------------------------------------------

    public void tickPickupCooldown() {
        if (pickupCooldown > 0) pickupCooldown--;
    }

    public int getPickupCooldown() { return pickupCooldown; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void trySpawnArmor(CultureDef c, Random r, EntityEquipmentSlot slot, float chance) {
        if (r.nextFloat() < chance) {
            ItemStack piece = c.randomArmorForSlot(slot, r);
            if (!piece.isEmpty()) entity.setItemStackToSlot(slot, piece);
        }
    }

    private void safeReplaceInSlot(EntityEquipmentSlot slot, ItemStack newStack, boolean dropOld) {
        ItemStack old = entity.getItemStackFromSlot(slot);
        entity.setItemStackToSlot(slot, newStack);
        if (!old.isEmpty()) {
            if (dropOld) dropStack(old.copy());
            else {
                ItemStack rem = ItemHandlerHelper.insertItemStacked(entity.inventory, old, false);
                if (!rem.isEmpty()) dropStack(rem);
            }
        }
    }

    private void shrinkOrRemove(EntityItem ei) {
        ItemStack s = ei.getItem();
        if (s.getCount() <= 1) ei.setDead();
        else { s.shrink(1); ei.setItem(s); }
    }

    private void takeOneAndStash(EntityItem ei) {
        ItemStack src = ei.getItem();
        ItemStack one = src.copy();
        one.setCount(1);
        ItemHandlerHelper.insertItemStacked(entity.inventory, one, false);
        shrinkOrRemove(ei);
    }

    private void dropStack(ItemStack stack) {
        if (stack.isEmpty() || entity.world.isRemote) return;
        EntityItem item = new EntityItem(entity.world, entity.posX, entity.posY + 0.5, entity.posZ, stack);
        item.motionX = (entity.getRNG().nextDouble() - 0.5) * 0.1;
        item.motionY = 0.15;
        item.motionZ = (entity.getRNG().nextDouble() - 0.5) * 0.1;
        item.setDefaultPickupDelay();
        entity.world.spawnEntity(item);
    }

    private ItemStack tagGhost(ItemStack src, String tag) {
        ItemStack copy = src.copy();
        copy.setCount(1);
        NBTTagCompound nbt = copy.getTagCompound();
        if (nbt == null) nbt = new NBTTagCompound();
        nbt.setBoolean(tag, true);
        if (nbt.hasKey("AttributeModifiers")) nbt.removeTag("AttributeModifiers");
        copy.setTagCompound(nbt);
        return copy;
    }

    private boolean checkGhostTag(ItemStack s, String tag) {
        return !s.isEmpty() && s.hasTagCompound() && s.getTagCompound().getBoolean(tag);
    }

    private double durabilityBias(ItemStack s) {
        if (!s.isItemStackDamageable()) return 0.0;
        int max = s.getMaxDamage();
        int left = Math.max(0, max - s.getItemDamage());
        return Math.min(0.9, (left / (double) max) * 0.5);
    }

    private double getMainHandAttackDamage(ItemStack s) {
        Multimap<String, AttributeModifier> map = s.getAttributeModifiers(EntityEquipmentSlot.MAINHAND);
        double sum = 0.0;
        for (AttributeModifier m : map.get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
            if (m.getOperation() == 0) sum += m.getAmount();
        }
        return sum;
    }

    private double sumAttr(Multimap<String, AttributeModifier> map, String attrName) {
        double sum = 0.0;
        for (AttributeModifier mod : map.get(attrName)) {
            if (mod.getOperation() == 0) sum += mod.getAmount();
        }
        return sum;
    }
}
