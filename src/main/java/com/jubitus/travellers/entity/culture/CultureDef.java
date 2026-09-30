package com.jubitus.travellers.entity.culture;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.equipment.TravellerEquipmentManager;
import com.jubitus.travellers.entity.equipment.TravellerEquipmentPolicy;
import com.jubitus.travellers.entity.equipment.TravellerMillPriorities;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.util.ResourceLocation;

public final class CultureDef {

    /** Which of TravellersConfig.Equipment's rarity-weight triples to draw spawn gear from. */
    public enum WeightProfile { TRAVELLER, RAIDER, WORKER }

    public final String id;
    public final String displayName;
    private final java.util.List<TravellerAppearance> travellerLooks = new java.util.ArrayList<>();
    private final java.util.List<TravellerAppearance> raiderLooks = new java.util.ArrayList<>();

    /**
     * Millenaire pairs first/family names per villager type (e.g. a caste's first name list is only
     * ever combined with that same caste's family name list). Keeping one pool per distinct
     * (firstNameListId, familyNameListId) pair - instead of flattening every list from every villager
     * file into two big culture-wide pools - avoids cross-pairing combinations Millenaire would
     * never produce (e.g. a low-caste first name with a high-caste family name).
     */
    private static final class NamePool {
        final java.util.List<String> firstNames = new java.util.ArrayList<>();
        final java.util.List<String> familyNames = new java.util.ArrayList<>();
    }

    private final java.util.Map<String, NamePool> namePools = new java.util.LinkedHashMap<>();
    private final java.util.List<TravellerAppearance> workerLooks = new java.util.ArrayList<>();

    // SIMPLE: category -> list of ItemStacks
    private final java.util.Map<String, java.util.List<ItemStack>> itemsByCategory = new java.util.HashMap<>();

    public CultureDef(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    // --- culture items API ---

    public void addCultureItem(String category, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String cat = (category == null ? "" : category.trim().toLowerCase(java.util.Locale.ROOT));

        itemsByCategory
                .computeIfAbsent(cat, c -> new java.util.ArrayList<>())
                .add(stack.copy());
    }

    public java.util.Map<String, java.util.List<ItemStack>> getAllCultureItems() {
        return java.util.Collections.unmodifiableMap(itemsByCategory);
    }

    public ItemStack randomWeapon(java.util.Random r) {
        ItemStack s = randomItemForCategory("weapons", r);
        if (!s.isEmpty()) return s;
        return randomItemForCategory("tools", r);
    }

    public ItemStack randomItemForCategory(String category, java.util.Random r) {
        java.util.List<ItemStack> list = getItemsForCategory(category);
        if (list.isEmpty()) return ItemStack.EMPTY;
        return list.get(r.nextInt(list.size())).copy();
    }

    public java.util.List<ItemStack> getItemsForCategory(String category) {
        if (category == null) return java.util.Collections.emptyList();
        java.util.List<ItemStack> list = itemsByCategory.get(
                category.trim().toLowerCase(java.util.Locale.ROOT)
        );
        return list != null ? java.util.Collections.unmodifiableList(list)
                : java.util.Collections.emptyList();
    }

    public ItemStack randomTool(java.util.Random r) {
        return randomItemForCategory("tools", r);
    }

    public void addTravellerAppearance(TravellerAppearance a) {
        travellerLooks.add(a);
    }

    public void addRaiderAppearance(TravellerAppearance a) {
        raiderLooks.add(a);
    }

    public int travellerAppearanceCount() {
        return travellerLooks.size();
    }

    public TravellerAppearance randomAppearance(java.util.Random r) {
        return getAppearance(r.nextInt(Math.max(1, travellerLooks.size())));
    }

    public TravellerAppearance getAppearance(int index) {
        if (travellerLooks.isEmpty()) {
            return new TravellerAppearance(
                    new ResourceLocation("minecraft", "textures/entity/steve.png"));
        }
        int i = index;
        if (i < 0) i = 0;
        if (i >= travellerLooks.size()) i = i % travellerLooks.size();
        return travellerLooks.get(i);
    }

    public int getRaiderTextureCount() {
        if (raiderLooks != null && !raiderLooks.isEmpty()) {
            return raiderLooks.size();
        }
        // fallback: share travellers pool if raiders list is empty
        return getTravellerTextureCount();
    }

    public int getTravellerTextureCount() {
        return travellerLooks != null ? travellerLooks.size() : 0;
    }

    public int getExplicitRaiderTextureCount() {
        return (raiderLooks != null) ? raiderLooks.size() : 0;
    }

    public TravellerAppearance getRaiderAppearance(int index) {
        java.util.List<TravellerAppearance> list =
                (raiderLooks != null && !raiderLooks.isEmpty()) ? raiderLooks : travellerLooks;

        if (list.isEmpty()) {
            return new TravellerAppearance(
                    new ResourceLocation("minecraft", "textures/entity/steve.png"));
        }
        int i = index;
        if (i < 0) i = 0;
        if (i >= list.size()) i = i % list.size();
        return list.get(i);
    }


    /**
     * A real melee weapon (sword-like; never a plain tool like an axe/pickaxe/hoe — those are
     * excluded so they never compete with actual weapons for the mainhand slot at spawn) picked
     * with quality-rarity weighting (see {@link #pickWeightedByQuality}). Falls back to any tool
     * only if the culture genuinely has no sword-like item at all, so an entity is never left
     * completely unarmed for lack of data.
     */
    public ItemStack randomMeleeOrTool(java.util.Random r, WeightProfile profile) {
        java.util.List<ItemStack> swords = collectItems(stack -> {
            Item item = stack.getItem();
            if (item instanceof ItemBow || item instanceof ItemArmor) return false;
            return TravellerEquipmentManager.isWeapon(stack) && spawnAllowed(stack, null, profile);
        });
        if (!swords.isEmpty()) {
            return pickWeightedByQuality(swords, r,
                    s -> TravellerEquipmentPolicy.quality(s, TravellerMillPriorities.Bucket.MELEE), profile);
        }

        java.util.List<ItemStack> tools = collectItems(stack -> {
            Item item = stack.getItem();
            if (item instanceof ItemBow || item instanceof ItemArmor || !(item instanceof ItemTool)) return false;
            return spawnAllowed(stack, null, profile);
        });
        return pickRandom(tools, r);
    }

    /**
     * Whether this exact stack may appear in {@code this} culture's spawn pool: not a Millenaire
     * "never equip" item, and either the culture's own gear / plain vanilla / another mod's — never
     * another Millenaire culture's signature gear (raiders excepted, they scavenge from everyone).
     */
    private boolean spawnAllowed(ItemStack s, EntityEquipmentSlot slotOrNull, WeightProfile profile) {
        if (s == null || s.isEmpty()) return false;
        if (TravellerEquipmentPolicy.isForbidden(s)) return false;
        boolean bypass = profile == WeightProfile.RAIDER
                && TravellersConfig.EQUIPMENT.raiderAllowMixedCultureGear;
        EntityEquipmentSlot slot = slotOrNull;
        if (slot == null && s.getItem() instanceof ItemArmor) slot = ((ItemArmor) s.getItem()).armorType;
        return TravellerEquipmentPolicy.cultureAllows(this.id, bypass, s, slot, false);
    }

    /**
     * "foreigntrade" is exotic merchant stock (imported curiosities, other cultures' signature gear,
     * vanilla diamond sets) that a culture sells to players but never actually wears or fights with —
     * see {@link #randomTradeGood}. Equipment pools must skip it or raiders/travellers end up spawning
     * in gear no one in that culture would realistically own.
     */
    private static final String CATEGORY_FOREIGN_TRADE = "foreigntrade";

    private java.util.List<ItemStack> collectItems(java.util.function.Predicate<ItemStack> pred) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.List<ItemStack>> entry : itemsByCategory.entrySet()) {
            if (CATEGORY_FOREIGN_TRADE.equals(entry.getKey())) continue;
            java.util.List<ItemStack> list = entry.getValue();
            if (list == null) continue;
            for (ItemStack s : list) {
                if (s == null || s.isEmpty()) continue;
                if (pred.test(s)) out.add(s);
            }
        }
        return out;
    }

    // --- PUBLIC: culture-aware equipment picks ---

    private ItemStack pickRandom(java.util.List<ItemStack> list, java.util.Random r) {
        if (list == null || list.isEmpty()) return ItemStack.EMPTY;
        return list.get(r.nextInt(list.size())).copy();
    }

    /**
     * Picks an item from the pool with a rarity-tier weighting instead of a flat uniform draw:
     * items are ranked by {@code scorer}, split into common/good/rare buckets by rank, then a
     * bucket is rolled using the configured weights (see {@link TravellersConfig.Equipment})
     * before picking uniformly within that bucket. This keeps the top-tier gear rare while still
     * guaranteeing the "common" bucket is drawn from the whole culture pool (steel/leather/etc.
     * included) rather than whatever happened to survive an old category exclusion.
     */
    private ItemStack pickWeightedByQuality(java.util.List<ItemStack> pool, java.util.Random r,
                                             java.util.function.ToDoubleFunction<ItemStack> scorer,
                                             WeightProfile profile) {
        if (pool.isEmpty()) return ItemStack.EMPTY;

        java.util.List<ItemStack> signature = filterCultureSignature(pool);
        java.util.List<ItemStack> effectivePool = (!signature.isEmpty()
                && r.nextFloat() < TravellersConfig.EQUIPMENT.cultureSignaturePreferenceChance)
                ? signature : pool;

        if (effectivePool.size() == 1 || !TravellersConfig.EQUIPMENT.rarityWeightingEnabled) {
            return pickRandom(effectivePool, r);
        }

        java.util.List<ItemStack> sorted = new java.util.ArrayList<>(effectivePool);
        sorted.sort(java.util.Comparator.comparingDouble(scorer));

        int n = sorted.size();
        int rareStart = Math.max(0, n - Math.max(1, (int) Math.ceil(n * 0.15)));
        int goodStart = Math.max(0, n - Math.max(1, (int) Math.ceil(n * 0.50)));
        if (goodStart > rareStart) goodStart = rareStart;

        java.util.List<ItemStack> rareBucket = sorted.subList(rareStart, n);
        java.util.List<ItemStack> goodBucket = sorted.subList(goodStart, rareStart);
        java.util.List<ItemStack> commonBucket = sorted.subList(0, goodStart);

        int commonWeight; int goodWeight; int rareWeight;
        switch (profile) {
            case RAIDER:
                commonWeight = TravellersConfig.EQUIPMENT.raiderCommonWeight;
                goodWeight = TravellersConfig.EQUIPMENT.raiderGoodWeight;
                rareWeight = TravellersConfig.EQUIPMENT.raiderRareWeight;
                break;
            case WORKER:
                commonWeight = TravellersConfig.EQUIPMENT.workerCommonWeight;
                goodWeight = TravellersConfig.EQUIPMENT.workerGoodWeight;
                rareWeight = TravellersConfig.EQUIPMENT.workerRareWeight;
                break;
            default:
                commonWeight = TravellersConfig.EQUIPMENT.commonWeight;
                goodWeight = TravellersConfig.EQUIPMENT.goodWeight;
                rareWeight = TravellersConfig.EQUIPMENT.rareWeight;
        }
        int total = Math.max(1, commonWeight + goodWeight + rareWeight);
        int roll = r.nextInt(total);

        java.util.List<ItemStack> chosenBucket;
        if (roll < rareWeight && !rareBucket.isEmpty()) {
            chosenBucket = rareBucket;
        } else if (roll < rareWeight + goodWeight && !goodBucket.isEmpty()) {
            chosenBucket = goodBucket;
        } else if (!commonBucket.isEmpty()) {
            chosenBucket = commonBucket;
        } else if (!goodBucket.isEmpty()) {
            chosenBucket = goodBucket;
        } else {
            chosenBucket = rareBucket;
        }

        return chosenBucket.get(r.nextInt(chosenBucket.size())).copy();
    }

    /**
     * Items whose registry name identifies them as this culture's own signature gear
     * (e.g. "millenaire:normanBroadsword" for the "norman" culture), as opposed to a generic
     * item (vanilla swords/armor, or another culture's) also present in this culture's item pool.
     */
    private java.util.List<ItemStack> filterCultureSignature(java.util.List<ItemStack> pool) {
        String idLower = id.toLowerCase(java.util.Locale.ROOT);
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (ItemStack s : pool) {
            ResourceLocation rl = s.getItem().getRegistryName();
            if (rl != null && rl.getPath().toLowerCase(java.util.Locale.ROOT).contains(idLower)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Any bow-like item from the culture pool, culture-filtered. */
    public ItemStack randomBow(java.util.Random r) {
        return randomBow(r, WeightProfile.TRAVELLER);
    }

    public ItemStack randomBow(java.util.Random r, WeightProfile profile) {
        java.util.List<ItemStack> pool = collectItems(stack -> {
            Item item = stack.getItem();
            boolean bowLike = item instanceof ItemBow
                    || (item.getRegistryName() != null
                    && item.getRegistryName().getPath().toLowerCase(java.util.Locale.ROOT).contains("bow"));
            return bowLike && spawnAllowed(stack, null, profile);
        });
        return pickRandom(pool, r);
    }

    /** Any armor piece (any slot), quality-rarity weighted, culture-filtered. */
    public ItemStack randomArmor(java.util.Random r, WeightProfile profile) {
        java.util.List<ItemStack> pool = collectItems(stack -> stack.getItem() instanceof ItemArmor
                && spawnAllowed(stack, ((ItemArmor) stack.getItem()).armorType, profile));
        return pickWeightedByQuality(pool, r,
                s -> TravellerEquipmentPolicy.armorRank(s, ((ItemArmor) s.getItem()).armorType), profile);
    }

    /** Armor piece for a specific slot, quality-rarity weighted, culture-filtered. */
    public ItemStack randomArmorForSlot(EntityEquipmentSlot slot, java.util.Random r, WeightProfile profile) {
        if (slot == null || !slot.getSlotType().equals(EntityEquipmentSlot.Type.ARMOR)) {
            return ItemStack.EMPTY;
        }
        java.util.List<ItemStack> pool = collectItems(stack -> {
            if (!(stack.getItem() instanceof ItemArmor)) return false;
            if (((ItemArmor) stack.getItem()).armorType != slot) return false;
            return spawnAllowed(stack, slot, profile);
        });
        return pickWeightedByQuality(pool, r, s -> TravellerEquipmentPolicy.armorRank(s, slot), profile);
    }

    // randomTradeGood(...) can KEEP using foreigntrade if you want:
    public ItemStack randomTradeGood(java.util.Random r) {
        // Only foreigntrade / construction etc. – keep your previous logic here if you still want it
        ItemStack s = randomItemForCategory("foreigntrade", r);
        if (!s.isEmpty()) return s;
        return randomItemForCategory("construction", r);
    }
    // --- Name list API ---

    /**
     * Registers/merges a villager type's first+family name lists as one pool, keyed by the
     * (firstNameListId, familyNameListId) pair so unrelated villager types never get their
     * names cross-paired (see {@link NamePool}).
     */
    public void addNamePool(String firstNameListId, String familyNameListId,
                             java.util.List<String> firstNames, java.util.List<String> familyNames) {
        String key = (firstNameListId == null ? "" : firstNameListId) + "|" + (familyNameListId == null ? "" : familyNameListId);
        NamePool pool = namePools.computeIfAbsent(key, k -> new NamePool());
        addAllUniqueTrimmed(pool.firstNames, firstNames);
        addAllUniqueTrimmed(pool.familyNames, familyNames);
    }

    private static void addAllUniqueTrimmed(java.util.List<String> target, java.util.List<String> src) {
        if (src == null) return;
        for (String s : src) {
            if (s == null) continue;
            String n = s.trim();
            if (n.isEmpty()) continue;
            if (!target.contains(n)) {
                target.add(n);
            }
        }
    }

    public boolean hasMaleFirstNames() {
        for (NamePool p : namePools.values()) {
            if (!p.firstNames.isEmpty()) return true;
        }
        return false;
    }

    public boolean hasFamilyNames() {
        for (NamePool p : namePools.values()) {
            if (!p.familyNames.isEmpty()) return true;
        }
        return false;
    }

    public String randomFullName(java.util.Random r) {
        NamePool pool = randomNonEmptyPool(r);
        String first = pool != null && !pool.firstNames.isEmpty()
                ? pool.firstNames.get(r.nextInt(pool.firstNames.size())) : "";
        String last = pool != null && !pool.familyNames.isEmpty()
                ? pool.familyNames.get(r.nextInt(pool.familyNames.size())) : "";

        if (first.isEmpty() && last.isEmpty()) return "";
        if (last.isEmpty()) return first;
        if (first.isEmpty()) return last;
        return first + " " + last;
    }

    @javax.annotation.Nullable
    private NamePool randomNonEmptyPool(java.util.Random r) {
        java.util.List<NamePool> nonEmpty = new java.util.ArrayList<>();
        for (NamePool p : namePools.values()) {
            if (!p.firstNames.isEmpty() || !p.familyNames.isEmpty()) nonEmpty.add(p);
        }
        if (nonEmpty.isEmpty()) return null;
        return nonEmpty.get(r.nextInt(nonEmpty.size()));
    }

    public void addWorkerAppearance(TravellerAppearance a) {
        workerLooks.add(a);
    }

    public int getWorkerTextureCount() {
        if (workerLooks != null && !workerLooks.isEmpty()) {
            return workerLooks.size();
        }
        // fallback to travellers pool if you want
        return getTravellerTextureCount();
    }

    public TravellerAppearance getWorkerAppearance(int index) {
        java.util.List<TravellerAppearance> list =
                (workerLooks != null && !workerLooks.isEmpty()) ? workerLooks : travellerLooks;

        if (list.isEmpty()) {
            return new TravellerAppearance(
                    new ResourceLocation("minecraft", "textures/entity/steve.png"));
        }
        int i = index;
        if (i < 0) i = 0;
        if (i >= list.size()) i = i % list.size();
        return list.get(i);
    }

}
