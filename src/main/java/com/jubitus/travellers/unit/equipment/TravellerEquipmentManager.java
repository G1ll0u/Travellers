package com.jubitus.travellers.unit.equipment;

import com.jubitus.travellers.config.TravellersConfig;

import com.google.common.collect.Multimap;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.TravellerAppearance;
import com.jubitus.travellers.unit.culture.VillagerEquipmentProfile;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
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

    /** Marks a stack the traveller picked up off a player's drop — always returned to the world on death. */
    public static final String PLAYER_GIFT_TAG = "TravellerPlayerGift";

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
    public static double scoreWeapon(ItemStack s) {
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
    public static double scoreArmor(ItemStack s, EntityEquipmentSlot slot) {
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
    public static boolean isWeapon(ItemStack s) {
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
    public static boolean isBow(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemBow;
    }

    /**
     * True for a wood- or gold-material sword — below the minimum acceptable spawn tier (stone).
     * Excluded from both the random weapon draw and villager-file defaultweapon=/startingInv=
     * picks, since travellers venture into a dangerous world and should never be reduced to these.
     */
    public static boolean isBelowWeaponFloor(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof ItemSword)) return false;
        String mat = ((ItemSword) s.getItem()).getToolMaterialName();
        return "WOOD".equals(mat) || "GOLD".equals(mat);
    }

    /**
     * True for gold-tier tools, weapons or armor (weak material, excluded from every spawn pool —
     * separate from {@link #isBelowWeaponFloor} since that one is sword-specific and also excludes
     * wood). Uses material-enum identity rather than {@code ArmorMaterial.getName()}, which is
     * client-only ({@code @SideOnly(CLIENT)}) and would crash if called on a dedicated server.
     */
    public static boolean isGoldTier(ItemStack s) {
        if (s.isEmpty()) return false;
        Item item = s.getItem();
        if (item instanceof ItemSword) {
            return "GOLD".equals(((ItemSword) item).getToolMaterialName());
        }
        if (item instanceof ItemTool) {
            return "GOLD".equals(((ItemTool) item).getToolMaterialName());
        }
        if (item instanceof ItemArmor) {
            return ((ItemArmor) item).getArmorMaterial() == ItemArmor.ArmorMaterial.GOLD;
        }
        return false;
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

    /**
     * True when the entity's culture bypasses the equipment filter: raiders (deliberately mixed,
     * scavenged look) and hired workers (the player equips them freely).
     */
    private boolean bypassCultureFilter() {
        if (entity instanceof EntityTravellerRaider) {
            return TravellersConfig.EQUIPMENT.raiderAllowMixedCultureGear;
        }
        if (entity instanceof EntityTravellerWorker) {
            return ((EntityTravellerWorker) entity).isHiredFollower();
        }
        return false;
    }

    /** Best real (non-ghost) weapon the entity currently holds or has stashed, or EMPTY. */
    private ItemStack bestOwnedWeapon() {
        ItemStack best = ItemStack.EMPTY;
        double bestRank = Double.NEGATIVE_INFINITY;
        ItemStack hand = entity.getHeldItemMainhand();
        if (!hand.isEmpty() && isWeapon(hand) && !isGhostWeapon(hand)) {
            best = hand;
            bestRank = TravellerEquipmentPolicy.weaponRank(hand);
        }
        ItemStackHandler inv = entity.inventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!isWeapon(s)) continue;
            double r = TravellerEquipmentPolicy.weaponRank(s);
            if (r > bestRank) { bestRank = r; best = s; }
        }
        return best;
    }

    /** Best armor the entity has equipped or stashed for the slot, or EMPTY. */
    private ItemStack bestOwnedArmor(EntityEquipmentSlot slot) {
        ItemStack best = entity.getItemStackFromSlot(slot);
        double bestRank = best.isEmpty() ? Double.NEGATIVE_INFINITY : TravellerEquipmentPolicy.armorRank(best, slot);
        ItemStackHandler inv = entity.inventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || !isArmorForSlot(s, slot)) continue;
            double r = TravellerEquipmentPolicy.armorRank(s, slot);
            if (r > bestRank) { bestRank = r; best = s; }
        }
        return best;
    }

    /** True if candidate is a culture-legal, condition-acceptable upgrade over the best weapon held/stashed. */
    public boolean isWeaponUpgrade(ItemStack candidate) {
        if (!isWeapon(candidate) && !isBow(candidate)) return false;
        return TravellerEquipmentPolicy.isUpgrade(entity.getCultureId(), bypassCultureFilter(),
                bestOwnedWeapon(), candidate, null);
    }

    /** True if candidate is a culture-legal, condition-acceptable upgrade over the best armor for the slot. */
    public boolean isArmorUpgrade(ItemStack candidate, EntityEquipmentSlot slot) {
        if (!isArmorForSlot(candidate, slot)) return false;
        return TravellerEquipmentPolicy.isUpgrade(entity.getCultureId(), bypassCultureFilter(),
                bestOwnedArmor(slot), candidate, slot);
    }

    // -------------------------------------------------------------------------
    // Player-gift provenance — items a player dropped and the traveller picked up
    // -------------------------------------------------------------------------

    public static boolean isPlayerGift(ItemStack s) {
        return !s.isEmpty() && s.hasTagCompound() && s.getTagCompound().getBoolean(PLAYER_GIFT_TAG);
    }

    private static void markPlayerGift(ItemStack s) {
        if (s.isEmpty()) return;
        NBTTagCompound tag = s.getTagCompound();
        if (tag == null) { tag = new NBTTagCompound(); s.setTagCompound(tag); }
        tag.setBoolean(PLAYER_GIFT_TAG, true);
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

    public ItemStack makeGhostRod(ItemStack src) {
        return tagGhost(src, GHOST_ROD_TAG);
    }

    public boolean isGhostRod(ItemStack s) {
        return checkGhostTag(s, GHOST_ROD_TAG);
    }

    /** True for any of the 5 ghost-tagged visual copies (sword, bow, torch, block, fishing rod). */
    public boolean isGhost(ItemStack s) {
        return isGhostWeapon(s) || isGhostBow(s) || isGhostTorch(s) || isGhostBlock(s) || isGhostRod(s);
    }

    /**
     * Clears every equipment slot still holding a ghost-tagged visual copy. Call before any vanilla
     * or custom drop logic runs (e.g. at the top of {@code onDeath}) so ghost copies — which carry no
     * attribute modifiers — never get dropped as if they were the real item.
     */
    public void purgeGhostItems() {
        if (entity.world.isRemote) return;
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            ItemStack s = entity.getItemStackFromSlot(slot);
            if (!s.isEmpty() && isGhost(s)) {
                entity.setItemStackToSlot(slot, ItemStack.EMPTY);
            }
        }
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
            double sc = TravellerEquipmentPolicy.weaponRank(s);
            if (sc > bestScore) { bestScore = sc; bestIdx = i; }
        }
        return bestIdx >= 0 ? inv.getStackInSlot(bestIdx) : ItemStack.EMPTY;
    }

    // -------------------------------------------------------------------------
    // Spawn equipment initialisation
    // -------------------------------------------------------------------------

    /**
     * Equips the traveller at spawn based on culture loot tables, overlaid with the specific
     * villager file's defaultweapon=/startingInv= (via {@code entity.getActiveAppearance()}) when
     * present. Weapons go into the inventory handler or mainhand; armor goes directly to entity slots.
     * Call from {@code onInitialSpawn} after the culture and appearance are set.
     */
    public void initSpawnEquipment(CultureDef c, Random r) {
        if (entity.world.isRemote || c == null) return;

        CultureDef.WeightProfile weightProfile = entity instanceof EntityTravellerRaider ? CultureDef.WeightProfile.RAIDER
                : entity instanceof EntityTravellerWorker ? CultureDef.WeightProfile.WORKER
                : CultureDef.WeightProfile.TRAVELLER;

        VillagerEquipmentProfile profile = null;
        TravellerAppearance appearance = entity.getActiveAppearance();
        if (appearance != null) profile = appearance.equipmentProfile;

        EnumSet<EntityEquipmentSlot> slotsFromProfile = EnumSet.noneOf(EntityEquipmentSlot.class);
        boolean weaponFromProfile = false;

        if (profile != null) {
            weaponFromProfile = equipProfileWeapon(profile, r);

            for (ItemStack stack : profile.startingInv) {
                EntityEquipmentSlot armorSlot = armorSlotOf(stack);
                if (armorSlot != null) {
                    entity.setItemStackToSlot(armorSlot, stack.copy());
                    slotsFromProfile.add(armorSlot);
                } else if (isWeapon(stack) || isBow(stack)) {
                    // Already handled by equipProfileWeapon; extra weapons in startingInv are ignored.
                } else if (entity instanceof EntityTravellerWorker) {
                    ItemHandlerHelper.insertItemStacked(entity.inventory, stack.copy(), false);
                }
                // Travellers/raiders have no accessible backpack: non-equipable extras are ignored.
            }
        }

        if (!weaponFromProfile) {
            if (r.nextFloat() < TravellersConfig.EQUIPMENT.bowChance) {
                ItemStack bow = c.randomBow(r, weightProfile);
                if (!bow.isEmpty()) entity.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, bow);
            }
            if (entity.getHeldItemMainhand().isEmpty()) {
                ItemStack meleeOrTool = c.randomMeleeOrTool(r, weightProfile);
                if (!meleeOrTool.isEmpty()) entity.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, meleeOrTool);
            }
        }

        float armorMultiplier = weightProfile == CultureDef.WeightProfile.RAIDER ? TravellersConfig.EQUIPMENT.raiderArmorChanceMultiplier
                : weightProfile == CultureDef.WeightProfile.WORKER ? TravellersConfig.EQUIPMENT.workerArmorChanceMultiplier
                : 1.0f;
        if (!slotsFromProfile.contains(EntityEquipmentSlot.HEAD))
            trySpawnArmor(c, r, EntityEquipmentSlot.HEAD, TravellersConfig.EQUIPMENT.armorChanceHead * armorMultiplier, weightProfile);
        if (!slotsFromProfile.contains(EntityEquipmentSlot.CHEST))
            trySpawnArmor(c, r, EntityEquipmentSlot.CHEST, TravellersConfig.EQUIPMENT.armorChanceChest * armorMultiplier, weightProfile);
        if (!slotsFromProfile.contains(EntityEquipmentSlot.LEGS))
            trySpawnArmor(c, r, EntityEquipmentSlot.LEGS, TravellersConfig.EQUIPMENT.armorChanceLegs * armorMultiplier, weightProfile);
        if (!slotsFromProfile.contains(EntityEquipmentSlot.FEET))
            trySpawnArmor(c, r, EntityEquipmentSlot.FEET, TravellersConfig.EQUIPMENT.armorChanceFeet * armorMultiplier, weightProfile);

        // Every traveller comes from a village and should have something to defend itself with —
        // never leave the mainhand empty for lack of pool data (bow-only cultures, sparse configs, etc).
        if (entity.getHeldItemMainhand().isEmpty()) {
            ItemStack fallback = c.randomMeleeOrTool(r, weightProfile);
            if (fallback.isEmpty()) fallback = c.randomBow(r, weightProfile);
            if (fallback.isEmpty()) fallback = new ItemStack(Items.STONE_SWORD);
            entity.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, fallback);
        }

        // Raiders always read as hostile at a glance: guaranteed helmet + chestplate, iron tier or better.
        if (entity instanceof EntityTravellerRaider && TravellersConfig.EQUIPMENT.raiderGuaranteedArmor) {
            ensureRaiderArmorPiece(c, r, EntityEquipmentSlot.HEAD, Items.IRON_HELMET);
            ensureRaiderArmorPiece(c, r, EntityEquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
        }

        applyConfiguredDropChances();
    }

    /** Ensures a raider's slot holds at least an iron-tier piece (when {@code raiderMinIronArmor}), drawing
     *  from the raider pool first and falling back to the given vanilla piece. */
    private void ensureRaiderArmorPiece(CultureDef c, Random r, EntityEquipmentSlot slot, net.minecraft.item.Item vanillaFallback) {
        boolean needIron = TravellersConfig.EQUIPMENT.raiderMinIronArmor;
        ItemStack cur = entity.getItemStackFromSlot(slot);
        if (!cur.isEmpty() && (!needIron || TravellerEquipmentPolicy.isAtLeastIronArmor(cur))) return;

        for (int i = 0; i < 6; i++) {
            ItemStack piece = c.randomArmorForSlot(slot, r, CultureDef.WeightProfile.RAIDER);
            if (piece.isEmpty()) break;
            if (!needIron || TravellerEquipmentPolicy.isAtLeastIronArmor(piece)) {
                entity.setItemStackToSlot(slot, piece);
                return;
            }
        }
        entity.setItemStackToSlot(slot, new ItemStack(vanillaFallback));
    }

    /**
     * Applies {@link TravellersConfig.Equipment#equipmentDropChance} to every armor/hand slot, so the
     * vanilla on-death drop roll ({@code EntityLiving#dropEquipment}) is config-driven instead of
     * permanently stuck at vanilla's own 0.085 default. No-op for entities (e.g. workers) that
     * override {@code dropEquipment} and don't use vanilla's per-slot chance at all.
     */
    private void applyConfiguredDropChances() {
        float chance = TravellersConfig.EQUIPMENT.equipmentDropChance;
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            entity.setDropChance(slot, chance);
        }
    }

    /**
     * Equips defaultweapon=/a weapon found in startingInv= to the mainhand, per the villager file's
     * profile — gated through {@link #isWeapon}/{@link #isBow} first, since Millenaire's own
     * defaultweapon= is often just a peaceful profession's day-to-day tool (e.g. a carpenter's
     * woodaxe), used there purely as a last-resort combat fallback rather than "this villager's
     * real weapon". When both a valid defaultweapon and a valid startingInv weapon are present,
     * one is picked at random between the two — neither is treated as strictly authoritative.
     * Returns true if a weapon (or bow) was equipped from the profile.
     */
    private boolean equipProfileWeapon(VillagerEquipmentProfile profile, Random r) {
        ItemStack fromDefault = profile.defaultWeapon;
        if ((!isWeapon(fromDefault) && !isBow(fromDefault)) || TravellerEquipmentPolicy.isForbidden(fromDefault)) {
            fromDefault = ItemStack.EMPTY;
        }

        ItemStack fromInv = ItemStack.EMPTY;
        for (ItemStack stack : profile.startingInv) {
            if ((isWeapon(stack) || isBow(stack)) && !TravellerEquipmentPolicy.isForbidden(stack)) { fromInv = stack; break; }
        }

        ItemStack chosen;
        if (!fromDefault.isEmpty() && !fromInv.isEmpty()) {
            chosen = r.nextBoolean() ? fromDefault : fromInv;
        } else if (!fromDefault.isEmpty()) {
            chosen = fromDefault;
        } else {
            chosen = fromInv;
        }

        if (chosen.isEmpty()) return false;
        entity.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, chosen.copy());
        return true;
    }

    @Nullable
    private EntityEquipmentSlot armorSlotOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemArmor)) return null;
        return ((ItemArmor) stack.getItem()).armorType;
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
        double currentScore = current.isEmpty() ? Double.NEGATIVE_INFINITY : TravellerEquipmentPolicy.armorRank(current, slot);

        int bestIdx = -1;
        double bestScore = currentScore;
        for (int i = 0; i < entity.inventory.getSlots(); i++) {
            ItemStack s = entity.inventory.getStackInSlot(i);
            if (!isArmorForSlot(s, slot)) continue;
            double sc = TravellerEquipmentPolicy.armorRank(s, slot);
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
     * Called by {@code updateEquipmentIfNeeded} for a single item the entity walked over.
     * Same logic as the throttled area scan below — both route through {@link #tryPickupOne}.
     */
    public void handleGroundPickup(EntityItem itemEntity) {
        if (tryPickupOne(itemEntity)) {
            entity.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 1.0F);
        }
    }

    /** Full scan of ground items within 3 blocks — armour/weapon/bow upgrades and food. */
    public void scanAndPickupGroundItems() {
        AxisAlignedBB box = entity.getEntityBoundingBox().grow(3.0, 3.0, 3.0);
        List<EntityItem> drops = entity.world.getEntitiesWithinAABB(EntityItem.class, box,
                e -> e != null && e.isEntityAlive() && !e.cannotPickup() && !e.getItem().isEmpty());

        boolean picked = false;
        for (EntityItem ei : drops) {
            if (tryPickupOne(ei)) picked = true;
        }

        if (picked) {
            pickupCooldown = 20;
            entity.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F, 0.9F + entity.getRNG().nextFloat() * 0.2F);
        }
    }

    /**
     * Considers one ground item: food into the backpack, a bow if the entity has none, then weapon
     * and armour upgrades via {@link TravellerEquipmentPolicy}. Items are taken exactly as they lie
     * (a plain {@code copy()}, never rebuilt), so condition is preserved. An item a player dropped
     * ({@code getThrower() != null}) is tagged so it is always returned on death.
     *
     * @return true if anything was taken from this stack
     */
    private boolean tryPickupOne(EntityItem ei) {
        ItemStack stack = ei.getItem();
        if (stack.isEmpty()) return false;
        boolean gift = ei.getThrower() != null;

        if (stack.getItem() instanceof ItemFood) {
            ItemStack rem = ItemHandlerHelper.insertItemStacked(entity.inventory, stack.copy(), false);
            if (rem.getCount() == stack.getCount()) return false; // nothing fit
            if (rem.isEmpty()) ei.setDead();
            else ei.setItem(rem);
            return true;
        }

        if (isBow(stack) && !hasBowInBackpack()) {
            takeOne(ei, gift);
            return true;
        }

        if ((isWeapon(stack) || isBow(stack)) && isWeaponUpgrade(stack)) {
            takeOne(ei, gift);
            keepOnlyBestWeapon();
            return true;
        }

        for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{
                EntityEquipmentSlot.FEET, EntityEquipmentSlot.LEGS,
                EntityEquipmentSlot.CHEST, EntityEquipmentSlot.HEAD}) {
            if (isArmorForSlot(stack, slot) && isArmorUpgrade(stack, slot)) {
                ItemStack one = stack.copy();
                one.setCount(1);
                if (gift) markPlayerGift(one);
                ItemStack old = entity.getItemStackFromSlot(slot);
                entity.setItemStackToSlot(slot, one);
                if (!old.isEmpty()) {
                    if (isPlayerGift(old)) dropStack(old.copy()); // displaced player item → back to the world now
                    else ItemHandlerHelper.insertItemStacked(entity.inventory, old, false);
                }
                shrinkOrRemove(ei);
                return true;
            }
        }
        return false;
    }

    /** Takes one unit off the ground item into the backpack, tagging it if it came from a player. */
    private void takeOne(EntityItem ei, boolean gift) {
        ItemStack one = ei.getItem().copy();
        one.setCount(1);
        if (gift) markPlayerGift(one);
        ItemHandlerHelper.insertItemStacked(entity.inventory, one, false);
        shrinkOrRemove(ei);
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
     * On death: first returns every player-gift stack unconditionally (so a player always gets back
     * what they handed over), then drops up to {@code backpackMaxDrops} of the remaining backpack
     * items at {@code backpackDropChance} each. Shuffles slot order to avoid early-slot bias.
     */
    public void dropBackpackLoot() {
        if (entity.world.isRemote || entity.inventory == null) return;
        final float dropChance = TravellersConfig.EQUIPMENT.backpackDropChance;
        final int maxDrops = TravellersConfig.EQUIPMENT.backpackMaxDrops;

        int n = entity.inventory.getSlots();

        // Player-gift items always come back, no roll, no cap.
        for (int i = 0; i < n; i++) {
            ItemStack s = entity.inventory.getStackInSlot(i);
            if (!s.isEmpty() && isPlayerGift(s)) {
                dropStack(entity.inventory.extractItem(i, s.getCount(), false));
            }
        }

        List<Integer> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) slots.add(i);
        Collections.shuffle(slots, entity.getRNG());

        int dropped = 0;
        for (int slot : slots) {
            if (dropped >= maxDrops) break;
            ItemStack s = entity.inventory.getStackInSlot(slot);
            if (s.isEmpty()) continue;
            if (entity.getRNG().nextFloat() < dropChance) {
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

        // Raiders must stay visibly armed even outside combat, so players can tell them apart from
        // travellers/workers on sight rather than only after being attacked.
        boolean neverSheathe = entity instanceof EntityTravellerRaider;

        if (entity.combat.getInCombatFlag() || neverSheathe) {
            swordHideTicks = TravellersConfig.COMBAT.swordHideDelay;
            ItemStack best = findBestWeaponInInventory();
            if (!best.isEmpty()) {
                ItemStack cur = entity.getHeldItemMainhand();
                if (cur.isEmpty() || !isGhostWeapon(cur)
                        || TravellerEquipmentPolicy.weaponRank(best) > TravellerEquipmentPolicy.weaponRank(cur)) {
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
            double sc = TravellerEquipmentPolicy.weaponRank(s);
            if (sc > bestInvScore) { bestInvScore = sc; bestInvIdx = i; }
        }

        ItemStack hand = entity.getHeldItemMainhand();
        boolean handReal = !hand.isEmpty() && isWeapon(hand) && !isGhostWeapon(hand);
        boolean bestIsHand = handReal && TravellerEquipmentPolicy.weaponRank(hand) >= bestInvScore;

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

    private void trySpawnArmor(CultureDef c, Random r, EntityEquipmentSlot slot, float chance, CultureDef.WeightProfile profile) {
        if (r.nextFloat() < chance) {
            ItemStack piece = c.randomArmorForSlot(slot, r, profile);
            if (!piece.isEmpty()) entity.setItemStackToSlot(slot, piece);
        }
    }

    /**
     * On death: unconditionally drops any player-gift item still worn or held (armor slots + hands),
     * clearing the slot so vanilla's per-slot drop roll doesn't also see it. Backpack player-gifts
     * are handled by {@link #dropBackpackLoot}. Call before vanilla {@code dropEquipment}.
     */
    public void dropPlayerGiftEquipment() {
        if (entity.world.isRemote) return;
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            ItemStack s = entity.getItemStackFromSlot(slot);
            if (!s.isEmpty() && isPlayerGift(s)) {
                dropStack(s.copy());
                entity.setItemStackToSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    private void shrinkOrRemove(EntityItem ei) {
        ItemStack s = ei.getItem();
        if (s.getCount() <= 1) ei.setDead();
        else { s.shrink(1); ei.setItem(s); }
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

    private static double durabilityBias(ItemStack s) {
        if (!s.isItemStackDamageable()) return 0.0;
        int max = s.getMaxDamage();
        int left = Math.max(0, max - s.getItemDamage());
        return Math.min(0.9, (left / (double) max) * 0.5);
    }

    private static double getMainHandAttackDamage(ItemStack s) {
        Multimap<String, AttributeModifier> map = s.getAttributeModifiers(EntityEquipmentSlot.MAINHAND);
        double sum = 0.0;
        for (AttributeModifier m : map.get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
            if (m.getOperation() == 0) sum += m.getAmount();
        }
        return sum;
    }

    private static double sumAttr(Multimap<String, AttributeModifier> map, String attrName) {
        double sum = 0.0;
        for (AttributeModifier mod : map.get(attrName)) {
            if (mod.getOperation() == 0) sum += mod.getAmount();
        }
        return sum;
    }
}
