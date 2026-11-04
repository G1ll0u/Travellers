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
 */
public final class CultureSnacks {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Snacks");
    private static final int SPECIALTY_WEIGHT = 3;
    // Eaten, but no one would snack on them.
    private static final Set<Item> HARMFUL = new HashSet<>(Arrays.asList(
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.CHORUS_FRUIT, Items.GOLDEN_APPLE));
    private static final List<Item> FALLBACK = Arrays.asList(Items.BREAD, Items.APPLE, Items.BAKED_POTATO, Items.COOKED_BEEF);

    private static final Map<String, List<Snack>> MENUS = new HashMap<>();

    private CultureSnacks() {
    }

    public static final class Snack {
        public final ItemStack stack;
        public final boolean drink;
        final int weight;

        Snack(ItemStack stack, boolean drink, int weight) {
            this.stack = stack;
            this.drink = drink;
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
        int total = 0;
        List<Snack> menu = menu(cultureKey);
        for (Snack s : menu) if (s.drink == drink) total += s.weight;
        if (total <= 0) return null;
        int roll = rng.nextInt(total);
        for (Snack s : menu) {
            if (s.drink != drink) continue;
            roll -= s.weight;
            if (roll < 0) return s;
        }
        return null;
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
        Set<Item> excluded = new HashSet<>();
        for (String id : TravellersConfig.REST_SPOTS.snackExcludedItems) {
            Item item = itemOf(id.trim(), "snackExcludedItems");
            if (item != null) excluded.add(item);
        }
        Map<Item, Snack> found = new HashMap<>();
        Culture culture = Culture.getCultureByName(cultureKey);
        if (culture != null) {
            for (TradeGood good : culture.goodsList) {
                if (TradeGood.FOREIGNTRADE.equals(good.travelBookCategory) || good.item == null) continue;
                addIfSnack(found, good.item.getItemStack(), excluded, false);
            }
        }
        for (String line : TravellersConfig.REST_SPOTS.snackExtraItems) {
            int eq = line.indexOf('=');
            if (eq <= 0 || !line.substring(0, eq).trim().equalsIgnoreCase(cultureKey)) continue;
            Item item = itemOf(line.substring(eq + 1).trim(), "snackExtraItems");
            if (item != null && !addIfSnack(found, new ItemStack(item), excluded, true)) {
                LOGGER.warn("[Snacks] snackExtraItems: {} is neither eaten nor drunk, ignored", line);
            }
        }
        boolean fallback = found.isEmpty();
        if (fallback) {
            for (Item item : FALLBACK) addIfSnack(found, new ItemStack(item), excluded, true);
        }
        List<Snack> menu = new ArrayList<>(found.values());
        log(cultureKey, menu, fallback);
        return Collections.unmodifiableList(menu);
    }

    private static boolean addIfSnack(Map<Item, Snack> found, ItemStack stack, Set<Item> excluded, boolean fromConfig) {
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
        found.putIfAbsent(item, new Snack(shown, action == EnumAction.DRINK, weight));
        return true;
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
        if (item == null) LOGGER.warn("[Snacks] {}: unknown item {}", field, id);
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
        }
        LOGGER.info("[Snacks] {}{}: drinks [{}] food [{}]", cultureKey, fallback ? " (no local food, vanilla menu)" : "",
                drinks, foods);
    }
}
