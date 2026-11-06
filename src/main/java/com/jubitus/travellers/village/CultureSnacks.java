package com.jubitus.travellers.village;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.millenaire.common.culture.Culture;
import org.millenaire.common.item.ItemFoodMultiple;
import org.millenaire.common.item.TradeGood;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * What a culture eats and drinks on a break: its own Millénaire goods (custom packs merged) that are eaten or drunk,
 * minus raw ingredients, potions and harmful food, plus the config's extras. Built per culture on first use, logged.
 * Also the one answer to "is this drink alcohol, and how strong": Millénaire's own drunk duration, or the config list.
 */
public final class CultureSnacks {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Snacks");
    private static final int SPECIALTY_WEIGHT = 3;
    // Millénaire gives a few seconds of nausea to ayran too; real alcohol starts at cider and wine (5 s).
    private static final int MIN_MILLENAIRE_ALCOHOL = 5;
    // Strength of a drink from the config list: as strong as cider or wine.
    private static final int CONFIG_ALCOHOL_STRENGTH = 5;
    // Eaten, but no one would snack on them.
    private static final Set<Item> HARMFUL = new HashSet<>(Arrays.asList(
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.CHORUS_FRUIT, Items.GOLDEN_APPLE));
    private static final List<Item> FALLBACK = Arrays.asList(Items.BREAD, Items.APPLE, Items.BAKED_POTATO, Items.COOKED_BEEF);

    private static final Map<String, List<Snack>> MENUS = new HashMap<>();

    /** What a unit without a culture eats to heal. */
    public static final Snack FALLBACK_HELPING = new Snack(new ItemStack(Items.BREAD), false, 0, 1);

    private CultureSnacks() {
    }

    public static final class Snack {
        public final ItemStack stack;
        public final boolean drink;
        /** How strong the drink is (Millénaire's drunk seconds; cider 5, calva 10), 0 for no alcohol. */
        public final int alcohol;
        final int weight;

        Snack(ItemStack stack, boolean drink, int alcohol, int weight) {
            this.stack = stack;
            this.drink = drink;
            this.alcohol = alcohol;
            this.weight = weight;
        }
    }

    /** Config reload: menus are rebuilt (and logged again) on next use. */
    public static void clear() {
        MENUS.clear();
    }

    /** Builds and logs every loaded culture's menu, so the server log shows them at start. */
    public static void buildAll() {
        for (Culture c : Culture.ListCultures) menu(c.key);
    }

    /** A random drink (or food when {@code drink} is false) of this culture, weighted; null when it has none. */
    @Nullable
    public static Snack pick(String cultureKey, boolean drink, Random rng) {
        return pick(cultureKey, drink, true, rng);
    }

    /** Same, leaving out alcohol unless {@code alcohol} is true. */
    @Nullable
    public static Snack pick(String cultureKey, boolean drink, boolean alcohol, Random rng) {
        int total = 0;
        List<Snack> menu = menu(cultureKey);
        for (Snack s : menu) if (fits(s, drink, alcohol)) total += s.weight;
        if (total <= 0) return null;
        int roll = rng.nextInt(total);
        for (Snack s : menu) {
            if (!fits(s, drink, alcohol)) continue;
            roll -= s.weight;
            if (roll < 0) return s;
        }
        return null;
    }

    private static boolean fits(Snack s, boolean drink, boolean alcohol) {
        return s.drink == drink && (alcohol || s.alcohol == 0);
    }

    private static List<Snack> menu(String cultureKey) {
        List<Snack> menu = MENUS.get(cultureKey);
        if (menu == null) {
            menu = build(cultureKey);
            MENUS.put(cultureKey, menu);
        }
        return menu;
    }

    private static List<Snack> build(String cultureKey) {
        Set<Item> excluded = itemsOf(TravellersConfig.REST_SPOTS.snackExcludedItems, "snackExcludedItems");
        Set<Item> alcohol = itemsOf(TravellersConfig.REST_SPOTS.alcoholicDrinks, "alcoholicDrinks");
        Map<Item, Snack> found = new HashMap<>();
        Culture culture = Culture.getCultureByName(cultureKey);
        if (culture != null) {
            for (TradeGood good : culture.goodsList) {
                if (TradeGood.FOREIGNTRADE.equals(good.travelBookCategory) || good.item == null) continue;
                addIfSnack(found, good.item.getItemStack(), excluded, alcohol, false);
            }
        }
        for (String line : TravellersConfig.REST_SPOTS.snackExtraItems) {
            int eq = line.indexOf('=');
            if (eq <= 0 || !line.substring(0, eq).trim().equalsIgnoreCase(cultureKey)) continue;
            Item item = itemOf(line.substring(eq + 1).trim(), "snackExtraItems");
            if (item != null && !addIfSnack(found, new ItemStack(item), excluded, alcohol, true)) {
                LOGGER.warn("[Snacks] snackExtraItems: {} is neither eaten nor drunk, ignored", line);
            }
        }
        boolean fallback = found.isEmpty();
        if (fallback) {
            for (Item item : FALLBACK) addIfSnack(found, new ItemStack(item), excluded, alcohol, true);
        }
        List<Snack> menu = new ArrayList<>(found.values());
        log(cultureKey, menu, fallback);
        return Collections.unmodifiableList(menu);
    }

    private static boolean addIfSnack(Map<Item, Snack> found, ItemStack stack, Set<Item> excluded, Set<Item> alcohol,
                                      boolean fromConfig) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        EnumAction action = stack.getItemUseAction();
        if (action != EnumAction.EAT && action != EnumAction.DRINK) return false;
        if (excluded.contains(item)) return true;
        if (!fromConfig && (item instanceof ItemPotion || HARMFUL.contains(item) || isRawIngredient(stack))) return true;
        ResourceLocation id = item.getRegistryName();
        int weight = id != null && !"minecraft".equals(id.getNamespace()) ? SPECIALTY_WEIGHT : 1;
        ItemStack shown = stack.copy();
        shown.setCount(1);
        boolean drink = action == EnumAction.DRINK;
        found.putIfAbsent(item, new Snack(shown, drink, drink ? alcoholStrength(item, alcohol) : 0, weight));
        return true;
    }

    private static int alcoholStrength(Item item, Set<Item> configured) {
        if (item instanceof ItemFoodMultiple) {
            int drunk = ((ItemFoodMultiple) item).getDrunkDuration();
            if (drunk >= MIN_MILLENAIRE_ALCOHOL) return drunk;
        }
        return configured.contains(item) ? CONFIG_ALCOHOL_STRENGTH : 0;
    }

    private static Set<Item> itemsOf(String[] ids, String field) {
        Set<Item> out = new HashSet<>();
        for (String id : ids) {
            Item item = itemOf(id.trim(), field);
            if (item != null) out.add(item);
        }
        return out;
    }

    /** Raw meat, fish, potatoes…: food that cooks into another food. */
    private static boolean isRawIngredient(ItemStack stack) {
        ItemStack cooked = FurnaceRecipes.instance().getSmeltingResult(stack);
        return !cooked.isEmpty() && cooked.getItemUseAction() == EnumAction.EAT;
    }

    @Nullable
    private static Item itemOf(String id, String field) {
        if (id.isEmpty()) return null;
        Item item = Item.getByNameOrId(id);
        // Defaults name items of optional culture packs: a missing one is normal, not a mistake.
        if (item == null) LOGGER.debug("[Snacks] {}: {} not installed, ignored", field, id);
        return item;
    }

    private static void log(String cultureKey, List<Snack> menu, boolean fallback) {
        StringBuilder drinks = new StringBuilder();
        StringBuilder foods = new StringBuilder();
        for (Snack s : menu) {
            StringBuilder sb = s.drink ? drinks : foods;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.stack.getItem().getRegistryName());
            if (s.weight > 1) sb.append(" x").append(s.weight);
            if (s.alcohol > 0) sb.append(" alcohol ").append(s.alcohol);
        }
        LOGGER.info("[Snacks] {}{}: drinks [{}] food [{}]", cultureKey, fallback ? " (no local food, vanilla menu)" : "",
                drinks, foods);
    }
}
