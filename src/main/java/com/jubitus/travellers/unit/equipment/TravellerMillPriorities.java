package com.jubitus.travellers.unit.equipment;

import com.jubitus.travellers.unit.culture.CultureRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Millenaire's own equipment "quality" model: a hand-authored priority integer per item, parsed from
 * {@code mods/millenaire/villagerconfig/default.txt} ({@code weaponHandToHandPriority=normanbroadsword,100},
 * {@code armourChestplatePriority=steelchest,70}, ...). Higher = better; {@code 0} = never equip.
 *
 * <p>This is the reference ranking used by {@link TravellerEquipmentPolicy}. Millenaire has no
 * durability concept, so this table only ranks item <i>types</i>; condition is a tie-breaker applied
 * separately by the policy. Unlisted items (mostly other mods') fall back to a material-tier estimate.
 */
public final class TravellerMillPriorities {

    /** Equipment buckets a priority line can target. */
    public enum Bucket { MELEE, RANGED, HELMET, CHEST, LEGS, BOOTS, SHIELD }

    // config-key prefix -> bucket. Swords double as both a weapon and a "tool" in Millenaire; we take
    // the max of the two so a culture that only lists its sword under toolSwordPriority still ranks it.
    private static final Map<String, Bucket> KEY_TO_BUCKET = new HashMap<>();

    static {
        KEY_TO_BUCKET.put("weaponhandtohandpriority", Bucket.MELEE);
        KEY_TO_BUCKET.put("toolswordpriority", Bucket.MELEE);
        KEY_TO_BUCKET.put("weaponrangedpriority", Bucket.RANGED);
        KEY_TO_BUCKET.put("armourhelmetpriority", Bucket.HELMET);
        KEY_TO_BUCKET.put("armourchestplatepriority", Bucket.CHEST);
        KEY_TO_BUCKET.put("armourleggingspriority", Bucket.LEGS);
        KEY_TO_BUCKET.put("armourbootspriority", Bucket.BOOTS);
    }

    private static final Map<Bucket, Map<ResourceLocation, Integer>> TABLE = new EnumMap<>(Bucket.class);
    private static boolean loaded = false;

    private TravellerMillPriorities() {
    }

    public static synchronized void reload() {
        TABLE.clear();
        for (Bucket b : Bucket.values()) TABLE.put(b, new HashMap<>());
        loaded = true;

        File root = CultureRegistry.detectMillenaireRoot();
        File def = new File(new File(root, "villagerconfig"), "default.txt");
        if (!def.isFile()) {
            System.out.println("[Travellers] villagerconfig/default.txt not found at " + def.getAbsolutePath()
                    + " - equipment quality falls back to material tiers only.");
            return;
        }
        parse(def);
        int n = 0;
        for (Map<ResourceLocation, Integer> m : TABLE.values()) n += m.size();
        System.out.println("[Travellers] Loaded " + n + " Millenaire equipment priorities from default.txt");
    }

    private static void ensureLoaded() {
        if (!loaded) reload();
    }

    private static void parse(File file) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                Bucket bucket = KEY_TO_BUCKET.get(key);
                if (bucket == null) continue;

                String[] parts = line.substring(eq + 1).split(",");
                if (parts.length < 2) continue;
                ItemStack stack = CultureRegistry.getMillenaireItemByKey(parts[0].trim());
                if (stack.isEmpty()) continue;
                ResourceLocation rl = stack.getItem().getRegistryName();
                if (rl == null) continue;
                int prio;
                try {
                    prio = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                // Sword listed under both weapon + tool: keep the higher of the two.
                TABLE.get(bucket).merge(rl, prio, Math::max);
            }
        } catch (Exception ex) {
            System.out.println("[Travellers] Failed to parse villagerconfig/default.txt: " + ex);
        }
    }

    /** Raw Millenaire priority for this item in this bucket, or {@code null} if the item is not listed. */
    public static Integer rawPriority(ItemStack stack, Bucket bucket) {
        if (stack == null || stack.isEmpty() || bucket == null) return null;
        ensureLoaded();
        ResourceLocation rl = stack.getItem().getRegistryName();
        if (rl == null) return null;
        return TABLE.get(bucket).get(rl);
    }

    public static boolean isListed(ItemStack stack, Bucket bucket) {
        return rawPriority(stack, bucket) != null;
    }

    /** Listed by Millenaire but with priority 0 — an explicit "never equip this". */
    public static boolean isExplicitlyForbidden(ItemStack stack, Bucket bucket) {
        Integer p = rawPriority(stack, bucket);
        return p != null && p <= 0;
    }
}
