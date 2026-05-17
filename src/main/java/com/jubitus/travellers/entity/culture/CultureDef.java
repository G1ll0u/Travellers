package com.jubitus.travellers.entity.culture;

import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.util.ResourceLocation;

public final class CultureDef {
    public final String id;
    public final String displayName;
    private final java.util.List<TravellerAppearance> travellerLooks = new java.util.ArrayList<>();
    private final java.util.List<TravellerAppearance> raiderLooks = new java.util.ArrayList<>();
    private final java.util.List<String> maleFirstNames = new java.util.ArrayList<>();
    private final java.util.List<String> familyNames = new java.util.ArrayList<>();
    private final java.util.List<TravellerAppearance> workerLooks = new java.util.ArrayList<>();
    private final java.util.List<TravellerAppearance> merchantLooks = new java.util.ArrayList<>();

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
     * Weapon or tool (melee-ish); excludes bows & armor, and excludes 'foreigntrade' goods
     */
    public ItemStack randomMeleeOrTool(java.util.Random r) {
        java.util.List<ItemStack> pool = collectNonForeignItems(stack -> {
            Item item = stack.getItem();
            if (item instanceof ItemBow) return false;
            if (item instanceof ItemArmor) return false;
            if (item instanceof ItemSword) return true;
            return item instanceof ItemTool;
        });
        return pickRandom(pool, r);
    }

    private java.util.List<ItemStack> collectNonForeignItems(java.util.function.Predicate<ItemStack> pred) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.List<ItemStack>> e : itemsByCategory.entrySet()) {
            String category = e.getKey();
            // SKIP foreigntrade entirely
            if (category != null && category.equalsIgnoreCase("foreigntrade")) {
                continue;
            }

            java.util.List<ItemStack> list = e.getValue();
            if (list == null) continue;

            for (ItemStack s : list) {
                if (s == null || s.isEmpty()) continue;
                if (pred.test(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    // --- PUBLIC: culture-aware equipment picks (NON-foreigntrade only) ---

    private ItemStack pickRandom(java.util.List<ItemStack> list, java.util.Random r) {
        if (list == null || list.isEmpty()) return ItemStack.EMPTY;
        return list.get(r.nextInt(list.size())).copy();
    }

    /**
     * Any bow-like item from culture, excluding 'foreigntrade'
     */
    public ItemStack randomBow(java.util.Random r) {
        java.util.List<ItemStack> pool = collectNonForeignItems(stack -> {
            Item item = stack.getItem();
            if (item instanceof ItemBow) return true;
            if (item.getRegistryName() != null) {
                String path = item.getRegistryName().getPath().toLowerCase(java.util.Locale.ROOT);
                return path.contains("bow");
            }
            return false;
        });
        return pickRandom(pool, r);
    }

    /**
     * Any armor piece (any slot), excluding 'foreigntrade'
     */
    public ItemStack randomArmor(java.util.Random r) {
        java.util.List<ItemStack> pool = collectNonForeignItems(stack -> stack.getItem() instanceof ItemArmor);
        return pickRandom(pool, r);
    }

    /**
     * Armor piece for a specific slot, excluding 'foreigntrade'
     */
    public ItemStack randomArmorForSlot(EntityEquipmentSlot slot, java.util.Random r) {
        if (slot == null || !slot.getSlotType().equals(EntityEquipmentSlot.Type.ARMOR)) {
            return ItemStack.EMPTY;
        }
        java.util.List<ItemStack> pool = collectNonForeignItems(stack -> {
            if (!(stack.getItem() instanceof ItemArmor)) return false;
            ItemArmor armor = (ItemArmor) stack.getItem();
            return armor.armorType == slot;
        });
        return pickRandom(pool, r);
    }

    // randomTradeGood(...) can KEEP using foreigntrade if you want:
    public ItemStack randomTradeGood(java.util.Random r) {
        // Only foreigntrade / construction etc. – keep your previous logic here if you still want it
        ItemStack s = randomItemForCategory("foreigntrade", r);
        if (!s.isEmpty()) return s;
        return randomItemForCategory("construction", r);
    }
    // --- Name list API ---

    public void addMaleFirstNames(java.util.List<String> names) {
        addAllUniqueTrimmed(maleFirstNames, names);
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

    public void addFamilyNames(java.util.List<String> names) {
        addAllUniqueTrimmed(familyNames, names);
    }

    public boolean hasMaleFirstNames() {
        return !maleFirstNames.isEmpty();
    }

    public boolean hasFamilyNames() {
        return !familyNames.isEmpty();
    }

    public String randomFullName(java.util.Random r) {
        String first = randomMaleFirstName(r);
        String last = randomFamilyName(r);

        if (first.isEmpty() && last.isEmpty()) return "";
        if (last.isEmpty()) return first;
        if (first.isEmpty()) return last;
        return first + " " + last;
    }

    public String randomMaleFirstName(java.util.Random r) {
        if (maleFirstNames.isEmpty()) return "";
        return maleFirstNames.get(r.nextInt(maleFirstNames.size()));
    }

    public String randomFamilyName(java.util.Random r) {
        if (familyNames.isEmpty()) return "";
        return familyNames.get(r.nextInt(familyNames.size()));
    }

    public void addWorkerAppearance(TravellerAppearance a) {
        workerLooks.add(a);
    }

    public void addMerchantAppearance(TravellerAppearance a) {
        merchantLooks.add(a);
    }

    public int getWorkerTextureCount() {
        if (workerLooks != null && !workerLooks.isEmpty()) {
            return workerLooks.size();
        }
        // fallback to travellers pool if you want
        return getTravellerTextureCount();
    }

    public int getMerchantTextureCount() {
        if (merchantLooks != null && !merchantLooks.isEmpty()) {
            return merchantLooks.size();
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

    public TravellerAppearance getMerchantAppearance(int index) {
        java.util.List<TravellerAppearance> list =
                (merchantLooks != null && !merchantLooks.isEmpty()) ? merchantLooks : travellerLooks;

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
