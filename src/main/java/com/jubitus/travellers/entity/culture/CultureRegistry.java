package com.jubitus.travellers.entity.culture;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.client.sound.ModSounds;
import com.jubitus.travellers.client.sound.RaiderVoiceIndex;
import com.jubitus.travellers.client.sound.VoiceIndex;
import com.jubitus.travellers.client.sound.VoicePersonality;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CultureRegistry {
    public static final Map<String, VoiceSet> TRAVELLER_VOICES = new HashMap<>();
    public static final Map<String, VoiceSet> RAIDER_VOICES = new HashMap<>();

    public static final Map<String, java.util.List<VoicePersonality>> TRAVELLER_VOICE_VARIANTS = new HashMap<>();

    // Optional: same idea for raiders (only useful if your raiders entity actually uses it)
    public static final Map<String, java.util.List<VoicePersonality>> RAIDER_VOICE_VARIANTS = new HashMap<>();

    private static final Map<String, CultureDef> CULT = new LinkedHashMap<>();
    private static final String DEFAULT_BIOME_KEY = "plains";
    private static final Map<String, Map<String, Integer>> BIOME_CULTURE_WEIGHTS = new HashMap<>();
    private static final Map<String, ItemStack> MILLENAIRE_ITEMS = new HashMap<>();
    private static boolean bootstrapped = false;
    private static boolean itemListLoaded = false;



    private CultureRegistry() {
    }


    public static void bootstrapAllMillenaireCultures() {
        if (bootstrapped) return; // optional now, but fine

        List<File> roots = new ArrayList<>();

        File base = detectMillenaireBaseCultures();
        if (base != null) roots.add(base);

        ensureItemListLoaded();
        roots.addAll(detectMillenaireCustomCultureRoots());

        doBootstrapAll(roots);
    }


    private static void bootstrapFromRoot(File culturesRoot) {
        if (culturesRoot == null || !culturesRoot.isDirectory()) return;

        for (File cultureDir : culturesRoot.listFiles()) {
            if (cultureDir == null || !cultureDir.isDirectory()) continue;

            String cultureId = cultureDir.getName();

            File villagersDir = new File(cultureDir, "villagers");
            File villagesDir = new File(cultureDir, "mill");
            File lonebuildingsDir = new File(cultureDir, "lonebuildings");
            File tradedGoodsFile = new File(cultureDir, "traded_goods.txt"); // NEW

            boolean alreadyKnown = CULT.containsKey(cultureId);
            if (!alreadyKnown && !hasUsefulCultureContent(villagersDir, villagesDir, lonebuildingsDir)) {
                continue;
            }

            CultureDef def = CULT.get(cultureId);
            if (def == null) {
                def = new CultureDef(cultureId, cultureId);
                CULT.put(cultureId, def);
            }

            if (villagersDir.isDirectory()) {
                walkVillagerTxts(def, villagersDir);
            }

            if (villagesDir.isDirectory()) {
                walkVillageTxts(cultureId, villagesDir);
            }

            if (lonebuildingsDir.isDirectory()) {
                walkVillageTxts(cultureId, lonebuildingsDir);
            }

            // NEW: hook in per-culture traded goods
            if (tradedGoodsFile.isFile()) {
                parseTradedGoodsForCulture(def, tradedGoodsFile);
            }
        }
    }


    private static void walkVillageTxts(String cultureId, File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File f : children) {
            if (f.isDirectory()) {
                walkVillageTxts(cultureId, f); // recurse into subfolders
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                parseVillageBiomeTxt(cultureId, f);
            }
        }
    }

    private static void parseVillageBiomeTxt(String cultureId, File file) {
        Set<String> biomeKeysInThisFile = new HashSet<>();

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8))) {

            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;

                int comment = line.indexOf("//");
                if (comment >= 0) {
                    line = line.substring(0, comment).trim();
                    if (line.isEmpty()) continue;
                }

                if (line.regionMatches(true, 0, "biome=", 0, 6)) {
                    String biomeName = line.substring(6).trim();
                    if (!biomeName.isEmpty()) {
                        String key = normalizeBiomeKey(biomeName);
                        if (!key.isEmpty()) {
                            biomeKeysInThisFile.add(key);
                        }
                    }
                }

                // IMPORTANT: ignore "weight=" completely now
            }
        } catch (Exception ex) {
            System.out.println("[Travellers] Failed to parse village/lonebuilding file: " +
                    file.getAbsolutePath() + " : " + ex);
            return;
        }

        if (biomeKeysInThisFile.isEmpty()) return;

        // Each file contributes +1 per (culture, biome) it references
        for (String biomeKey : biomeKeysInThisFile) {
            Map<String, Integer> byCulture =
                    BIOME_CULTURE_WEIGHTS.computeIfAbsent(biomeKey, k -> new HashMap<>());

            int prev = byCulture.getOrDefault(cultureId, 0);
            byCulture.put(cultureId, prev + 1);
        }
    }

    private static boolean textureExists(ResourceLocation loc) {
        // On dedicated server we cannot access client resources at all.
        if (FMLCommonHandler.instance().getSide() == Side.SERVER) {
            // Just assume true (or log and return true). We only need this
            // to filter out bad textures for the *client* anyway.
            return true;
        }

        try {
            IResource res = Minecraft.getMinecraft()
                    .getResourceManager()
                    .getResource(loc);
            return res != null;
        } catch (Exception e) {
            return false;
        }
    }


    private static void walkVillagerTxts(CultureDef def, File villagersDir) {
        if (!villagersDir.isDirectory()) return;

        for (File f : villagersDir.listFiles()) {
            if (f == null) continue;

            if (f.isDirectory()) {
                walkVillagerTxts(def, f); // recurse
                continue;
            }

            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) continue;

            // Let parseVillagerTxt decide travellers vs raiders usage.
            // It handles travellers excludes + raiders includes.
            parseVillagerTxt(def, f);
        }
    }


    private static void parseVillagerTxt(CultureDef culture, File txt) {
        // === 1) Traveller file-based exclusion ===
        // (Raiders use includes instead, so they never early-return here.)
        boolean fileExcludedForTravellers = TravellersModConfig.isVillagerFileExcludedForTravellers(txt);

        String gender = "male";
        List<String> baseTextures = new ArrayList<>();
        List<String> clothesTextures = new ArrayList<>();

        // Travellers: if any tag=... matches EXCLUDED_VILLAGER_TAGS, we skip for travellers only
        boolean excludedTravellerByTag = false;

        // Raiders: we use a whitelist. Track all tags in the file.
        Set<String> tagsInFile = new HashSet<>();

        String firstNameListId = null;
        String familyNameListId = null;

        // Normalized filename (without .txt) for raiders includes
        String villagerBaseName = txt.getName().toLowerCase(Locale.ROOT);
        if (villagerBaseName.endsWith(".txt")) {
            villagerBaseName = villagerBaseName.substring(0, villagerBaseName.length() - 4);
        }

        try (BufferedReader br = new BufferedReader(new FileReader(txt))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                // --- tag filter from config ---
                if ("tag".equalsIgnoreCase(key)) {
                    String tagLc = value.toLowerCase(Locale.ROOT);

                    // Travellers: classic exclude behavior
                    if (TravellersModConfig.EXCLUDED_VILLAGER_TAGS.contains(tagLc)) {
                        excludedTravellerByTag = true;
                    }

                    // Raiders: collect for later include checks
                    tagsInFile.add(tagLc);

                    continue;
                }

                if ("gender".equalsIgnoreCase(key)) {
                    gender = value.toLowerCase(Locale.ROOT);

                } else if ("texture".equalsIgnoreCase(key)) {
                    // e.g. textures/entity/norman/male/nor_peasant_0.png
                    baseTextures.add(value);

                } else if ("clothes".equalsIgnoreCase(key)) {
                    // May be:
                    //   clothes=category,path
                    // or
                    //   clothes=category,index,path
                    String[] parts = value.split(",");
                    if (parts.length == 2) {
                        // category, path
                        clothesTextures.add(parts[1].trim());
                    } else if (parts.length >= 3) {
                        // category, index, path (Seljuk-style)
                        clothesTextures.add(parts[2].trim());
                    }

                } else if ("firstNameList".equalsIgnoreCase(key)) {
                    firstNameListId = value;

                } else if ("familyNameList".equalsIgnoreCase(key)) {
                    familyNameListId = value;


                    // May be:
                    //   clothes=category,path
                    // or
                    //   clothes=category,index,path

                    String[] parts = value.split(",");
                    if (parts.length == 2) {
                        // category, path
                        clothesTextures.add(parts[1].trim());
                    } else if (parts.length >= 3) {
                        // category, index, path (Seljuk-style)
                        clothesTextures.add(parts[2].trim());
                    }

                }
            }
        } catch (Exception e) {
            System.err.println("[Travellers] Error parsing villager txt " + txt.getPath() + ": " + e);
            return;
        }

        // === 2) Decide raiders inclusion ===

        // Are there any include rules configured?
        boolean hasRaiderFileIncludes = !TravellersModConfig.INCLUDED_RAIDER_VILLAGER_FILES.isEmpty();
        boolean hasRaiderTagIncludes = !TravellersModConfig.INCLUDED_RAIDER_VILLAGER_TAGS.isEmpty();

        // Check filename match (if rules exist)
        boolean raiderFileMatch = hasRaiderFileIncludes &&
                TravellersModConfig.INCLUDED_RAIDER_VILLAGER_FILES.contains(villagerBaseName);

        // Check tag match (if rules exist)
        boolean raiderTagMatch = false;
        if (hasRaiderTagIncludes) {
            for (String t : tagsInFile) {
                if (TravellersModConfig.INCLUDED_RAIDER_VILLAGER_TAGS.contains(t)) {
                    raiderTagMatch = true;
                    break;
                }
            }
        }
// === 2b) Decide worker inclusion ===
        boolean hasWorkerFileIncludes = !TravellersModConfig.INCLUDED_WORKER_VILLAGER_FILES.isEmpty();
        boolean hasWorkerTagIncludes = !TravellersModConfig.INCLUDED_WORKER_VILLAGER_TAGS.isEmpty();

        boolean workerFileMatch = hasWorkerFileIncludes &&
                TravellersModConfig.INCLUDED_WORKER_VILLAGER_FILES.contains(villagerBaseName);

        boolean workerTagMatch = false;
        if (hasWorkerTagIncludes) {
            for (String t : tagsInFile) {
                if (TravellersModConfig.INCLUDED_WORKER_VILLAGER_TAGS.contains(t)) {
                    workerTagMatch = true;
                    break;
                }
            }
        }

// Design choice: workers only come from *explicit* includes, so default=false
        boolean workerAllowed = (hasWorkerFileIncludes && workerFileMatch)
                || (hasWorkerTagIncludes && workerTagMatch);

// === 2c) Decide merchant inclusion ===
        boolean hasMerchantFileIncludes = !TravellersModConfig.INCLUDED_MERCHANT_VILLAGER_FILES.isEmpty();
        boolean hasMerchantTagIncludes = !TravellersModConfig.INCLUDED_MERCHANT_VILLAGER_TAGS.isEmpty();

        boolean merchantFileMatch = hasMerchantFileIncludes &&
                TravellersModConfig.INCLUDED_MERCHANT_VILLAGER_FILES.contains(villagerBaseName);

        boolean merchantTagMatch = false;
        if (hasMerchantTagIncludes) {
            for (String t : tagsInFile) {
                if (TravellersModConfig.INCLUDED_MERCHANT_VILLAGER_TAGS.contains(t)) {
                    merchantTagMatch = true;
                    break;
                }
            }
        }

        boolean merchantAllowed = (hasMerchantFileIncludes && merchantFileMatch)
                || (hasMerchantTagIncludes && merchantTagMatch);

        // Final decision:
        // - If no includes defined at all -> raiderAllowed = true (backwards compatible).
        // - Else -> allowed if filename OR tag matches an include.
        boolean raiderAllowed;
        if (!hasRaiderFileIncludes && !hasRaiderTagIncludes) {
            raiderAllowed = true;
        } else {
            raiderAllowed = (hasRaiderFileIncludes && raiderFileMatch)
                    || (hasRaiderTagIncludes && raiderTagMatch);
        }

        // === 3) Gender & texture guards (common) ===
        if (!"male".equals(gender)) return;  // adjust if you want females too
        if (baseTextures.isEmpty()) return;

        // === 4) Build appearance objects once ===
        List<TravellerAppearance> built = new ArrayList<>();
        for (String texStr : baseTextures) {
            ResourceLocation base = normalizeMillenaireTexture(texStr);

            if (!textureExists(base)) {
                System.out.println("[Travellers] Ignoring missing base texture: " + base);
                continue;
            }

            TravellerAppearance app = new TravellerAppearance(base);

            for (String c : clothesTextures) {
                ResourceLocation cl = normalizeMillenaireTexture(c);
                if (textureExists(cl)) {
                    app. clothes.add(cl);
                } else {
                    System.out.println("[Travellers] Ignoring missing clothes texture: " + cl);
                }
            }

            built.add(app);
        }

        if (built.isEmpty()) return;

// === 5) Add to TRAVELLER pool if allowed ===
        if (!fileExcludedForTravellers && !excludedTravellerByTag) {
            for (TravellerAppearance app : built) {
                culture.addTravellerAppearance(app);
            }
        } else {
            System.out.println("[Travellers] Skipping travellers appearance for file=" + txt.getName());
        }

// === 6) Add to RAIDER pool if allowed by include rules ===
        if (raiderAllowed) {
            for (TravellerAppearance app : built) {
                culture.addRaiderAppearance(app);
            }
        } else {
            System.out.println("[Travellers] Skipping raiders appearance (not whitelisted) file=" + txt.getName());
        }

// === 7) Add to WORKER pool if allowed ===
        if (workerAllowed) {
            for (TravellerAppearance app : built) {
                culture.addWorkerAppearance(app);
            }
        } else {
            // Optional debug
            System.out.println("[Travellers] Skipping worker appearance (not whitelisted) file=" + txt.getName());
        }

// === 8) Add to MERCHANT pool if allowed ===
        if (merchantAllowed) {
            for (TravellerAppearance app : built) {
                culture.addMerchantAppearance(app);
            }
        } else {
            // Optional debug
            System.out.println("[Travellers] Skipping merchant appearance (not whitelisted) file=" + txt.getName());
        }


        // === 7) Name lists: firstNameList / familyNameList -> /cultures/<culture>/namelists/*.txt
        if (firstNameListId != null || familyNameListId != null) {
            File cultureDir = findCultureDirForVillagerFile(txt);
            if (cultureDir != null) {
                if (firstNameListId != null) {
                    List<String> firstNames = loadNameList(cultureDir, firstNameListId);
                    if (!firstNames.isEmpty()) {
                        culture.addMaleFirstNames(firstNames);
                    }
                }
                if (familyNameListId != null) {
                    List<String> familyNames = loadNameList(cultureDir, familyNameListId);
                    if (!familyNames.isEmpty()) {
                        culture.addFamilyNames(familyNames);
                    }
                }
            }
        }
    }

    /**
     * Given something like .../cultures/norman/villagers/farmer.txt,
     * walk up to the "villagers" folder, then return its parent
     * (the culture root: .../cultures/norman).
     */
    private static File findCultureDirForVillagerFile(File txt) {
        if (txt == null) return null;
        File cur = txt.getParentFile();
        while (cur != null && !cur.getName().equalsIgnoreCase("villagers")) {
            cur = cur.getParentFile();
        }
        if (cur == null) return null;
        return cur.getParentFile(); // this should be the culture folder
    }

    /**
     * Load a Millénaire namelist:
     * /cultures/<cultureId>/namelists/<listId>.txt
     * One name per non-empty, non-comment line.
     */
    private static List<String> loadNameList(File cultureDir, String listId) {
        List<String> result = new ArrayList<>();
        if (cultureDir == null || listId == null) return result;

        String fileName = listId.trim();
        if (fileName.isEmpty()) return result;

        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            fileName = fileName + ".txt";
        }

        File nameDir = new File(cultureDir, "namelists");
        File f = new File(nameDir, fileName);
        if (!f.isFile()) {
            System.out.println("[Travellers] Name list not found: " + f.getAbsolutePath());
            return result;
        }

        System.out.println("[Travellers] Loading namelist " + f.getAbsolutePath());

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(f),
                        StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#") || line.startsWith("//")) continue;

                result.add(line);
            }
        } catch (Exception e) {
            System.out.println("[Travellers] Error reading name list " + f.getAbsolutePath() + " : " + e);
        }

        return result;
    }

    public static String makeTravellerNameForCulture(String cultureId, Random r) {
        CultureDef def = CULT.get(cultureId);
        if (def == null) return "";
        return def.randomFullName(r);
    }


    private static ResourceLocation normalizeMillenaireTexture(String path) {
        path = path.replace('\\', '/');
        if (path.startsWith("textures/")) {
            return new ResourceLocation("millenaire", path);
        }
        if (path.indexOf(':') >= 0) {
            return new ResourceLocation(path);
        }
        return new ResourceLocation("millenaire", path);
    }

    public static Collection<CultureDef> all() {
        return CULT.values();
    }

    public static CultureDef get(String id) {
        CultureDef d = CULT.get(id);
        if (d == null && !CULT.isEmpty()) return CULT.values().iterator().next();
        return d;
    }

    public static File detectMillenaireCultures() {
        // Usually this ends up at: .minecraft/mods/millenaire/cultures
        File mcDir = net.minecraftforge.fml.common.Loader.instance()
                .getConfigDir()
                .getParentFile(); // go from config/ back to .minecraft/

        return new File(mcDir, "mods/millenaire/cultures");
    }

    public static String pickCultureIdForBiome(net.minecraft.world.biome.Biome biome, Random rand) {

        if (CULT.isEmpty()) {
            return "default"; // or some hardcoded culture
        }
        if (biome == null) {
            // no biome at all: just use Plains fallback
            Map<String, Integer> plains = getBucketForBiomeKey(DEFAULT_BIOME_KEY);
            if (plains != null && !plains.isEmpty()) {
                return pickFromBucket(plains, rand);
            }
            return pickRandomId(rand);
        }

        // 1) Try true biome-specific mapping
        Map<String, Integer> bucket = findBucketForBiome(biome);

        // 2) If none, fall back to "plains" mapping
        if (bucket == null || bucket.isEmpty()) {
            bucket = getBucketForBiomeKey(DEFAULT_BIOME_KEY);
        }

        // 3) If Plains also has no data, fall back to global random
        if (bucket == null || bucket.isEmpty()) {
            return pickRandomId(rand);
        }

        return pickFromBucket(bucket, rand);
    }

    private static Map<String, Integer> getBucketForBiomeKey(String key) {
        if (key == null || key.isEmpty()) return null;
        String norm = normalizeBiomeKey(key);

        // direct hit
        Map<String, Integer> bucket = BIOME_CULTURE_WEIGHTS.get(norm);
        if (bucket != null && !bucket.isEmpty()) return bucket;

        // fuzzy: strip spaces / underscores / hyphens
        String compact = norm.replace(" ", "").replace("_", "").replace("-", "");

        for (Map.Entry<String, Map<String, Integer>> e : BIOME_CULTURE_WEIGHTS.entrySet()) {
            String k = e.getKey();
            String kc = k.replace(" ", "").replace("_", "").replace("-", "");

            if (kc.equals(compact)) return e.getValue();

            // very small plural/singular fudge: woodland vs woodlands, plain vs plains
            if ((kc + "s").equals(compact) || kc.equals(compact + "s")) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String pickFromBucket(Map<String, Integer> bucket, Random rand) {
        int total = 0;
        for (int w : bucket.values()) total += Math.max(0, w);
        if (total <= 0) return pickRandomId(rand);

        int r = rand.nextInt(total);
        for (Map.Entry<String, Integer> e : bucket.entrySet()) {
            int w = Math.max(0, e.getValue());
            if (w <= 0) continue;
            r -= w;
            if (r < 0) return e.getKey();
        }
        return pickRandomId(rand);
    }

    public static String pickRandomId(Random r) {
        List<String> ids = new ArrayList<>(CULT.keySet());
        if (ids.isEmpty()) return "default";
        return ids.get(r.nextInt(ids.size()));
    }

    private static Map<String, Integer> findBucketForBiome(net.minecraft.world.biome.Biome biome) {
        if (biome == null) return null;

        // Some environments strip Biome#getBiomeName / func_185359_l, so don't use it.
        // Use registry name instead – it's stable and good for config keys.
        String name = "";
        String idFull = "";
        String idPath = "";

        if (biome.getRegistryName() != null) {
            idFull = normalizeBiomeKey(biome.getRegistryName().toString());  // e.g. "biomesoplenty:woodland"
            idPath = normalizeBiomeKey(biome.getRegistryName().getPath());   // e.g. "woodland"
            // Treat "name" as the readable key but base it on the path so older configs still work.
            name = idPath;
        }

        // 1) Direct hits first
        Map<String, Integer> bucket = null;
        if (!name.isEmpty()) bucket = BIOME_CULTURE_WEIGHTS.get(name);
        if (bucket == null && !idFull.isEmpty()) bucket = BIOME_CULTURE_WEIGHTS.get(idFull);
        if (bucket == null && !idPath.isEmpty()) bucket = BIOME_CULTURE_WEIGHTS.get(idPath);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket;
        }

        // 2) Fuzzy: strip spaces / underscores / hyphens
        String compactName = name.replace(" ", "").replace("_", "").replace("-", "");
        String compactPath = idPath.replace(" ", "").replace("_", "").replace("-", "");

        for (Map.Entry<String, Map<String, Integer>> e : BIOME_CULTURE_WEIGHTS.entrySet()) {
            String k = e.getKey();
            String kc = k.replace(" ", "").replace("_", "").replace("-", "");
            if (kc.equals(compactName) || kc.equals(compactPath)) {
                return e.getValue();
            }
            // small plural/singular fudge: woodland vs woodlands
            if ((kc + "s").equals(compactName) || kc.equals(compactName + "s")) {
                return e.getValue();
            }
        }

        return null;
    }


    private static String normalizeBiomeKey(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    public static Map<String, Map<String, Integer>> getBiomeWeights() {
        return BIOME_CULTURE_WEIGHTS;
    }

    public static File detectMillenaireBaseCultures() {
        // config/ -> .minecraft/
        File mcDir = net.minecraftforge.fml.common.Loader.instance()
                .getConfigDir()
                .getParentFile();

        File baseRoot = new File(mcDir, "mods/millenaire/cultures");
        return baseRoot.isDirectory() ? baseRoot : null;
    }

    public static List<File> detectMillenaireCustomCultureRoots() {
        List<File> out = new ArrayList<>();

        File mcDir = net.minecraftforge.fml.common.Loader.instance()
                .getConfigDir()
                .getParentFile();
        File customModsRoot = new File(mcDir, "mods/millenaire-custom/mods");
        if (!customModsRoot.isDirectory()) return out;

        File[] packs = customModsRoot.listFiles();
        if (packs == null) return out;

        for (File pack : packs) {
            if (!pack.isDirectory()) continue;
            File cultures = new File(pack, "cultures");
            if (cultures.isDirectory()) {
                out.add(cultures);
            }
        }
        return out;
    }


    private static boolean hasUsefulCultureContent(File villagersDir, File villagesDir, File lonebuildingsDir) {
        // A culture is "real" for travellers if it has at least one villager
        // OR at least one village
        // OR at least one lonebuilding config (which includes biomes).
        return hasAnyTxtRecursive(villagersDir)
                || hasAnyTxtRecursive(villagesDir)
                || hasAnyTxtRecursive(lonebuildingsDir);
    }

    private static boolean hasAnyTxtRecursive(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;

        for (File f : kids) {
            if (f.isDirectory()) {
                if (hasAnyTxtRecursive(f)) return true;
            } else if (f.isFile()
                    && f.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                return true;
            }
        }
        return false;
    }

    private static void ensureItemListLoaded() {
        if (itemListLoaded) return;
        itemListLoaded = true;

        try {
            File mcDir = net.minecraftforge.fml.common.Loader.instance()
                    .getConfigDir()
                    .getParentFile(); // .minecraft/

            // Millénaire layout:
            // .minecraft/mods/millenaire/itemlist.txt
            File millenaireRoot = new File(mcDir, "mods/millenaire");

            // 1) Preferred: single file itemlist.txt
            File singleFile = new File(millenaireRoot, "itemlist.txt");
            if (singleFile.isFile()) {
                System.out.println("[Travellers] Loading Millenaire itemlist.txt from " + singleFile.getAbsolutePath());
                parseMillenaireItemListFile(singleFile);
            } else {
                // 2) Fallback: directory "itemlist" containing multiple files (your previous assumption)
                File itemListRoot = new File(millenaireRoot, "itemlist");
                if (itemListRoot.isDirectory()) {
                    System.out.println("[Travellers] Loading Millenaire itemlist directory " + itemListRoot.getAbsolutePath());
                    File[] files = itemListRoot.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                                parseMillenaireItemListFile(f);
                            }
                        }
                    }
                } else {
                    System.out.println("[Travellers] No Millenaire itemlist found at " +
                            singleFile.getAbsolutePath() + " or in directory " + itemListRoot.getAbsolutePath());
                }
            }

            System.out.println("[Travellers] Loaded " + MILLENAIRE_ITEMS.size() + " Millenaire itemlist entries.");
        } catch (Exception ex) {
            System.out.println("[Travellers] Failed to load Millenaire itemlist: " + ex);
        }
    }

    private static void parseMillenaireItemListFile(File file) {
        System.out.println("[Travellers] Parsing Millenaire itemlist file: " + file.getAbsolutePath());
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;

                String[] parts = line.split(";");
                if (parts.length < 2) continue;

                String key = parts[0].trim();
                String itemId = parts[1].trim();
                int meta = 0;
                if (parts.length >= 3) {
                    try {
                        meta = Integer.parseInt(parts[2].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }

                if (key.isEmpty() || itemId.isEmpty()) continue;

                if (!itemId.contains(":")) {
                    System.out.println("[Travellers] Skipping non-RL Millenaire item id '" + itemId +
                            "' for key '" + key + "'");
                    continue;
                }

                try {
                    // Do NOT mess with case anymore; use exactly what Millénaire gives
                    ResourceLocation rl = new ResourceLocation(itemId);

                    Item item = ForgeRegistries.ITEMS.getValue(rl);
                    if (item == null) {
                        System.out.println("[Travellers] Unknown item id in Millenaire itemlist: " + rl);
                        continue;
                    }

                    ItemStack stack = new ItemStack(item, 1, meta);
                    MILLENAIRE_ITEMS.put(key.toLowerCase(Locale.ROOT), stack);
                } catch (Exception e) {
                    System.out.println("[Travellers] Error parsing item id '" + itemId +
                            "' for key '" + key + "' in " + file.getName() + " : " + e);
                }
            }
        } catch (Exception ex) {
            System.out.println("[Travellers] Error parsing Millenaire itemlist file " +
                    file.getAbsolutePath() + " : " + ex);
        }

        System.out.println("[Travellers] After " + file.getName() +
                " we have " + MILLENAIRE_ITEMS.size() + " items.");
    }


    private static void parseTradedGoodsForCulture(CultureDef def, File tradedGoodsFile) {
        if (def == null || tradedGoodsFile == null || !tradedGoodsFile.isFile()) return;

        System.out.println("[Travellers] Parsing traded_goods for culture " + def.id +
                " from " + tradedGoodsFile.getAbsolutePath());

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(tradedGoodsFile),
                        StandardCharsets.UTF_8))) {

            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;

                String[] parts = line.split(",");
                if (parts.length == 0) continue;

                String itemKey = parts[0].trim();
                if (itemKey.isEmpty()) continue;

                String category = "";
                if (parts.length >= 10) {
                    category = parts[9].trim().toLowerCase(Locale.ROOT);
                }

                ItemStack baseStack = MILLENAIRE_ITEMS.get(itemKey.toLowerCase(Locale.ROOT));
                if (baseStack == null || baseStack.isEmpty()) {
                    continue;
                }

                // just category + stack
                def.addCultureItem(category, baseStack);
            }
        } catch (Exception ex) {
            System.out.println("[Travellers] Error parsing traded_goods.txt for culture " +
                    def.id + " : " + ex);
        }
    }

    private static void doBootstrapAll(List<File> roots) {
        if (bootstrapped) return;
        bootstrapped = true;

        ensureItemListLoaded(); // make sure MILLENAIRE_ITEMS is ready

        if (roots == null || roots.isEmpty()) return;

        for (File root : roots) {
            if (root != null && root.isDirectory()) {
                bootstrapFromRoot(root); // already handles villagers, mill, lonebuildings, traded_goods
            }
        }
    }

    public static void initMillenaireData() {
        System.out.println("[Travellers] Initializing Millenaire cultures & items...");
        itemListLoaded = false; // allow reload if it was hit too early somehow
        MILLENAIRE_ITEMS.clear();

        ensureItemListLoaded();       // will now run AFTER registries are ready
        bootstrapAllMillenaireCultures(); // will parse traded_goods and attach items
        ModSounds.assignVoicesToCultures();
    }

    public static String pickRandomCultureIdWithRaiders(Random r) {
        List<String> ids = new ArrayList<>();
        for (CultureDef d : CULT.values()) {
            if (d != null && d.getExplicitRaiderTextureCount() > 0) {
                ids.add(d.id);
            }
        }
        if (ids.isEmpty()) return pickRandomId(r); // or "default"
        return ids.get(r.nextInt(ids.size()));
    }

    public static VoiceSet resolveTravellerVoiceSet(String cultureId, String personalityId) {
        if (cultureId == null) cultureId = "";
        cultureId = cultureId.toLowerCase(java.util.Locale.ROOT);
        ensureTravellerVoiceDataForCulture(cultureId);

        // 1) If we have a personality id, try exact match in variants
        if (personalityId != null && !personalityId.isEmpty()) {
            String pid = personalityId.toLowerCase(java.util.Locale.ROOT);

            java.util.List<VoicePersonality> vars = TRAVELLER_VOICE_VARIANTS.get(cultureId);
            if (vars != null) {
                for (VoicePersonality vp : vars) {
                    if (vp != null && vp.id != null
                            && vp.id.toLowerCase(java.util.Locale.ROOT).equals(pid)) {
                        return vp.voiceSet;
                    }
                }
            }
        }

        // 2) IMPORTANT: if no personality (""), return the CULTURE fallback,
        // not the global ModSounds fallback (which is English default).
        VoiceSet fallback = TRAVELLER_VOICES.get(cultureId);
        if (fallback != null) return fallback;

        // 3) Last resort: global fallback
        return null;
    }



    public static String pickRandomTravellerPersonalityId(String cultureId, java.util.Random rand) {
        String cid = (cultureId == null ? "" : cultureId).toLowerCase(java.util.Locale.ROOT);
        ensureTravellerVoiceDataForCulture(cultureId);

        java.util.List<VoicePersonality> list = TRAVELLER_VOICE_VARIANTS.get(cid);
        if (list == null || list.isEmpty()) return ""; // keep empty -> fallback

        VoicePersonality p = list.get(rand.nextInt(list.size()));
        return (p == null || p.id == null) ? "" : p.id;
    }




    public static java.util.List<VoicePersonality> getTravellerVoiceVariants(String cultureId) {
        String cid = normalizeCultureId(cultureId);
        java.util.List<VoicePersonality> list = TRAVELLER_VOICE_VARIANTS.get(cid);
        return (list == null) ? java.util.Collections.emptyList() : list;
    }

    private static String normalizeCultureId(String cultureId) {
        if (cultureId == null) return "";
        return cultureId.trim().toLowerCase(java.util.Locale.ROOT);
    }


    private static java.util.List<String> getClipKeys(
            VoiceIndex idx,
            String lang,
            String personality,
            String kind
    ) {
        if (idx == null || idx.clips == null) return java.util.Collections.emptyList();

        java.util.Map<String, java.util.Map<String, java.util.List<String>>> byLang = idx.clips.get(lang);
        if (byLang == null) return java.util.Collections.emptyList();

        java.util.Map<String, java.util.List<String>> byPersonality = byLang.get(personality);
        if (byPersonality == null) return java.util.Collections.emptyList();

        java.util.List<String> keys = byPersonality.get(kind);
        return keys == null ? java.util.Collections.emptyList() : keys;
    }

    @javax.annotation.Nullable
    public static VoiceSet buildVoiceSet(
            String lang,
            String personality,
            String defaultLang,
            String defaultPersonality
    ) {
        VoiceIndex idx = VoiceIndex.loadOrDefault();

        lang = (lang == null ? "en" : lang).toLowerCase(java.util.Locale.ROOT);
        personality = (personality == null ? "default" : personality).toLowerCase(java.util.Locale.ROOT);
        defaultLang = (defaultLang == null ? "en" : defaultLang).toLowerCase(java.util.Locale.ROOT);
        defaultPersonality = (defaultPersonality == null ? "default" : defaultPersonality).toLowerCase(java.util.Locale.ROOT);

        SoundEvent[] talk = resolveExactEvents(idx, lang, personality, "talk", defaultLang, defaultPersonality);
        SoundEvent[] hurt = resolveExactEvents(idx, lang, personality, "hurt", defaultLang, defaultPersonality);
        SoundEvent[] hello = resolveExactEvents(idx, lang, personality, "hello", defaultLang, defaultPersonality);
        SoundEvent[] ambient = resolveExactEvents(idx, lang, personality, "ambient", defaultLang, defaultPersonality);

        return new VoiceSet(talk, hurt, hello, ambient);
    }

    public static String pickRandomRaiderPersonalityId(String cultureId, java.util.Random rand) {
        String cid = (cultureId == null ? "" : cultureId).toLowerCase(java.util.Locale.ROOT);

        java.util.List<VoicePersonality> list = RAIDER_VOICE_VARIANTS.get(cid);
        if (list == null || list.isEmpty()) return "";

        VoicePersonality p = list.get(rand.nextInt(list.size()));
        return (p == null || p.id == null) ? "" : p.id;
    }

    public static VoiceSet resolveRaiderVoiceSet(String cultureId, String personalityId) {
        String cid = (cultureId == null ? "" : cultureId).toLowerCase(java.util.Locale.ROOT);

        boolean isNormanLike = cid.contains("norman");
        String lang = langForCulture(cultureId);


        // These defaults come from raiders_index.json (you can store them globally if you prefer)
        String defaultLang = "en";
        String defaultPersonality = "default";

        return buildRaiderVoiceSet(lang, personalityId, defaultLang, defaultPersonality);
    }

    @javax.annotation.Nullable
    public static VoiceSet buildRaiderVoiceSet(
            String lang,
            String personality,
            String defaultLang,
            String defaultPersonality
    ) {
        RaiderVoiceIndex idx = RaiderVoiceIndex.loadOrDefault();

        lang = (lang == null ? "en" : lang).toLowerCase(java.util.Locale.ROOT);
        personality = (personality == null ? "default" : personality).toLowerCase(java.util.Locale.ROOT);
        defaultLang = (defaultLang == null ? "en" : defaultLang).toLowerCase(java.util.Locale.ROOT);
        defaultPersonality = (defaultPersonality == null ? "default" : defaultPersonality).toLowerCase(java.util.Locale.ROOT);

        SoundEvent[] talk = resolveExactRaiderEvents(idx, lang, personality, "talk", defaultLang, defaultPersonality);
        SoundEvent[] hurt = resolveExactRaiderEvents(idx, lang, personality, "hurt", defaultLang, defaultPersonality);
        SoundEvent[] hello = resolveExactRaiderEvents(idx, lang, personality, "hello", defaultLang, defaultPersonality);
        SoundEvent[] ambient = resolveExactRaiderEvents(idx, lang, personality, "ambient", defaultLang, defaultPersonality);

        return new VoiceSet(talk, hurt, hello, ambient);
    }

    private static SoundEvent[] resolveExactRaiderEvents(
            RaiderVoiceIndex idx,
            String lang,
            String personality,
            String kind,
            String defaultLang,
            String defaultPersonality
    ) {
        java.util.List<String> keys = getRaiderClipKeys(idx, lang, personality, kind);

        if ((keys == null || keys.isEmpty()) && !personality.equalsIgnoreCase(defaultPersonality)) {
            keys = getRaiderClipKeys(idx, lang, defaultPersonality, kind);
        }

        if ((keys == null || keys.isEmpty())
                && (!lang.equalsIgnoreCase(defaultLang) || !personality.equalsIgnoreCase(defaultPersonality))) {
            keys = getRaiderClipKeys(idx, defaultLang, defaultPersonality, kind);
        }

        if (keys == null || keys.isEmpty()) {
            return new SoundEvent[0];
        }

        java.util.List<SoundEvent> out = new java.util.ArrayList<>();
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) continue;
            SoundEvent ev = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Tags.MOD_ID, key));
            if (ev != null) out.add(ev);
        }

        return out.toArray(new SoundEvent[0]);
    }

    private static java.util.List<String> getRaiderClipKeys(
            RaiderVoiceIndex idx,
            String lang,
            String personality,
            String kind
    ) {
        if (idx == null || idx.clips == null) return java.util.Collections.emptyList();

        java.util.Map<String, java.util.Map<String, java.util.List<String>>> byLang = idx.clips.get(lang);
        if (byLang == null) return java.util.Collections.emptyList();

        java.util.Map<String, java.util.List<String>> byPersonality = byLang.get(personality);
        if (byPersonality == null) return java.util.Collections.emptyList();

        java.util.List<String> keys = byPersonality.get(kind);
        return keys == null ? java.util.Collections.emptyList() : keys;
    }

    public static String langForCulture(String cultureId) {
        String id = (cultureId == null ? "" : cultureId).toLowerCase(java.util.Locale.ROOT);

        // Add your mapping rules here.
        // Use contains() so it works for "german", "german_village", etc.
        if (id.contains("norman")) return "fr";
        if (id.contains("franks")) return "fr";
        if (id.contains("german")) return "de";

        // default
        return "en";
    }
    private static void ensureTravellerVoiceDataForCulture(String cultureId) {
        String cid = normalizeCultureId(cultureId);
        if (cid.isEmpty()) return;

        // If already built, nothing to do
        if (TRAVELLER_VOICE_VARIANTS.containsKey(cid) && TRAVELLER_VOICES.containsKey(cid)) return;

        VoiceIndex idx = VoiceIndex.loadOrDefault();
        String lang = langForCulture(cid).toLowerCase(java.util.Locale.ROOT);

        java.util.List<String> persIds = idx.langs.get(lang);
        if (persIds == null) persIds = java.util.Collections.emptyList();

        java.util.List<VoicePersonality> personalities = new java.util.ArrayList<>();
        for (String pid : persIds) {
            if (pid == null) continue;
            String clean = pid.trim().toLowerCase(java.util.Locale.ROOT);
            if (clean.isEmpty()) continue;
            VoiceSet vs = buildVoiceSet(lang, clean, idx.defaultLang, idx.defaultPersonality);
            personalities.add(new VoicePersonality(clean, vs));
        }
        TRAVELLER_VOICE_VARIANTS.put(cid, personalities);

        // Per-language fallback personality (FR -> first personality, EN -> "default")
        String fallbackPid = idx.defaultPersonality == null ? "default" : idx.defaultPersonality;
        fallbackPid = fallbackPid.toLowerCase(java.util.Locale.ROOT);

        boolean hasGlobalDefault = false;
        for (String p : persIds) {
            if (p != null && p.equalsIgnoreCase(fallbackPid)) { hasGlobalDefault = true; break; }
        }
        if (!hasGlobalDefault && !persIds.isEmpty() && persIds.get(0) != null) {
            fallbackPid = persIds.get(0).toLowerCase(java.util.Locale.ROOT);
        }

        TRAVELLER_VOICES.put(cid, buildVoiceSet(lang, fallbackPid, idx.defaultLang, idx.defaultPersonality));
    }

    private static SoundEvent[] resolveExactEvents(
            VoiceIndex idx,
            String lang,
            String personality,
            String kind,
            String defaultLang,
            String defaultPersonality
    ) {
        java.util.List<String> keys = getClipKeys(idx, lang, personality, kind);

        if ((keys == null || keys.isEmpty()) && !personality.equalsIgnoreCase(defaultPersonality)) {
            keys = getClipKeys(idx, lang, defaultPersonality, kind);
        }

        if ((keys == null || keys.isEmpty())
                && (!lang.equalsIgnoreCase(defaultLang) || !personality.equalsIgnoreCase(defaultPersonality))) {
            keys = getClipKeys(idx, defaultLang, defaultPersonality, kind);
        }

        if (keys == null || keys.isEmpty()) {
            return new SoundEvent[0];
        }

        java.util.List<SoundEvent> out = new java.util.ArrayList<>();
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) continue;
            SoundEvent ev = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Tags.MOD_ID, key));
            if (ev != null) out.add(ev);
        }

        return out.toArray(new SoundEvent[0]);
    }





}


