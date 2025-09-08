package com.jubitus.traveller;

import com.jubitus.traveller.traveller.utils.TravellerBlacklist;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class TravellersModConfig {
    public static int TravellerWeight;
    public static int villageRadius;
    public static double maxHealth;
    private static Configuration config;
    private static final String CONFIG_VERSION_KEY = "configVersion";
    private static final String CURRENT_VERSION = "2.9";

    public static String[] travellerAggroBlacklistIds = new String[0];      // e.g., {"minecraft:creeper", "alexsmobs:*"}
    public static String[] travellerAggroBlacklistClasses = new String[0];  // e.g., {"net.minecraft.entityAI.monster.EntityGhast"}
    public static final TravellerBlacklist TRAVELLER_BLACKLIST = new TravellerBlacklist();
    static boolean enableTravellerEntity = true;

    // --- TRAVELLER TUNING ---
    public static int villageNear;
    public static int lonebuildingNear;
    public static int villagePickRadius;

    public static int swordHideDelay;
    public static float eatHealthThreshold;
    public static int eatDurationTicks;

    public static double rangedMinDist;
    public static double rangedMaxDist;
    public static int rangedCooldown;

    // --- FOLLOWER AI TUNING ---
    public static double followDefaultRange;
    public static double followDesiredDist;
    public static double followMaxDist;
    public static int followRepushEvery;
    public static int followDurationMin;
    public static int followDurationMax;
    public static float followAngleAlignDeg;
    public static float followStartChance;

    public static double movementSpeed;
    public static double followRange;
    public static double attackDamage;

    public static void init(File configFile) {
        config = new Configuration(configFile);
        boolean needsRewrite = false;

        try {
            // If a file exists, load it so we can inspect version & legacy keys.
            if (configFile.exists()) {
                config.load();

                // Remove legacy keys first
                if (config.getCategory(Configuration.CATEGORY_GENERAL).containsKey("terrainMargin")) {
                    config.getCategory(Configuration.CATEGORY_GENERAL).remove("terrainMargin");
                    System.out.println("[ModConfig] Removed legacy key 'terrainMargin'.");
                    needsRewrite = true; // we’ll save after cleanup if version matches
                }

// Version check
                String version = config.get(Configuration.CATEGORY_GENERAL, CONFIG_VERSION_KEY, "").getString();
                if (!CURRENT_VERSION.equals(version)) {
                    System.out.println("[ModConfig] Config version mismatch (found '" + version + "'). Recreating config as " + CURRENT_VERSION + "...");
                    if (configFile.exists()) {
                        backupAndDelete(configFile, version);
                    }
                    config = new Configuration(configFile);
                    writeDefaults(config);
                    System.out.println("[ModConfig] Config recreated with version " + CURRENT_VERSION + ".");
                    return;
                }
            } else {
                // No file yet → write a fresh one with defaults
                writeDefaults(config);
                System.out.println("[ModConfig] Config created with version " + CURRENT_VERSION + ".");
                return;
            }

            // If we get here, version matches → load values normally
            readValues(config);

            // After loading values, (re)build the parsed blacklist
            TRAVELLER_BLACKLIST.reload(travellerAggroBlacklistIds, travellerAggroBlacklistClasses);

        } catch (Exception e) {
            System.err.println("[ModConfig] Error loading config: " + e.getMessage());
        } finally {
            if (config.hasChanged() || needsRewrite) {
                config.save();
                System.out.println("[ModConfig] Config saved.");
            }
            if (needsRewrite) {
                System.out.println("[ModConfig] Config updated due to legacy key removal.");
            }
        }
    }

    /** Write a clean file with current defaults and CURRENT_VERSION set. */
    private static void writeDefaults(Configuration cfg) {
        // Always set the version first
        cfg.get(Configuration.CATEGORY_GENERAL, CONFIG_VERSION_KEY, CURRENT_VERSION).set(CURRENT_VERSION);


        // --- TRAVELLER ENTITY (spawn + behavior) ---

        TravellerWeight = cfg.getInt(
                "TravellerEntityWeight",
                "Traveller Gen",
                10, 0, 256,
                "Increase to make traveller spawn more often; set to 0 to disable traveller entityAI spawn."
        );

        enableTravellerEntity = cfg.getBoolean(
                "enableTravellerEntity",
                "General traveller settings",
                true,
                "Enable traveller entityAI."
        );

        travellerAggroBlacklistIds = cfg.getStringList(
                "TravellerAggroBlacklistIDs",
                "Traveller aggro blacklist",
                new String[] { "minecraft:creeper", "traveller:traveller" },
                "Blacklist by entityAI registry ID. Supports wildcards like 'modid:*'.\n" +
                        "Examples: 'minecraft:creeper', 'twilightforest:ur_ghast', 'alexsmobs:*'\n" +
                        "Entities on this list won't ever attack traveller."
        );

        travellerAggroBlacklistClasses = cfg.getStringList(
                "TravellerAggroBlacklistClasses",
                "Traveller Aggro Blacklist",
                new String[] {
                        "net.minecraft.entityAI.monster.EntityGhast",
                        "net.minecraft.entityAI.monster.EntityEnderman"
                },
                "Blacklist by fully-qualified class name. Any subclass will match.\n" +
                        "Examples: 'net.minecraft.entityAI.monster.EntityGhast', 'com.example.mobs.EntityBrute'.\n" +
                        "Entities on this list won't ever attack traveller."
        );
// --- TRAVELLER BASE ATTRIBUTES ---
        maxHealth = cfg.getFloat(
                "TravellerMaxHealth", "Traveller base attributes",
                50.0f, 1.0f, 1000.0f,
                "Max health of traveller (20 = player default)."
        );

        movementSpeed = cfg.getFloat(
                "TravellerMovementSpeed", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Base movement speed multiplier. 0.1 = player walking speed."
        );

        followRange = cfg.getFloat(
                "TravellerFollowRange", "Traveller base attributes",
                64.0f, 4.0f, 256.0f,
                "How far away (in blocks) the traveller can detect targets/points of interest."
        );

        attackDamage = cfg.getFloat(
                "TravellerAttackDamage", "Traveller base attributes",
                4.0f, 0.0f, 100.0f,
                "Damage dealt per melee hit."
        );

        // --- TRAVELLER TUNING (new) ---
        villageNear = cfg.getInt(
                "VillageNearDistance", "Traveller tweaks",
                32, 1, 256,
                "Distance (in blocks) considered 'arrived' at a village."
        );
        lonebuildingNear = cfg.getInt(
                "LoneBuildingNearDistance", "Traveller tweaks",
                12, 1, 256,
                "Distance (in blocks) considered 'arrived' at a lone building."
        );

        villagePickRadius = cfg.getInt(
                "VillagePickRadius", "Traveller tweaks",
                4000, 100, 10000,
                "Maximum radius (in blocks) within which travellers can pick the next village destination."
        );

        swordHideDelay = cfg.getInt(
                "SwordHideDelay", "Traveller tweaks",
                120, 0, 2000,
                "Ticks until sword is hidden after combat ends."
        );

        eatHealthThreshold = cfg.getFloat(
                "EatHealthThreshold", "Traveller tweaks",
                0.9f, 0.1f, 1.0f,
                "Traveller starts eating at or below this fraction of max health."
        );

        eatDurationTicks = cfg.getInt(
                "EatDurationTicks", "Traveller tweaks",
                80, 1, 1000,
                "Number of ticks it takes to finish eating (20 ticks = 1 second)."
        );

        rangedMinDist = (double) cfg.getFloat(
                "RangedMinDist", "Traveller tweaks",
                6.0f, 1.0f, 64.0f,
                "Minimum distance before traveller uses a bow."
        );

        rangedMaxDist = (double) cfg.getFloat(
                "RangedMaxDist", "Traveller tweaks",
                32.0f, 1.0f, 128.0f,
                "Maximum distance traveller will attempt to shoot at."
        );

        rangedCooldown = cfg.getInt(
                "RangedCooldown", "Traveller tweaks",
                40, 1, 200,
                "Ticks between ranged attacks (20 ticks = 1 second)."
        );
// --- FOLLOWER AI TUNING (new) ---
        followDefaultRange = cfg.getFloat(
                "FollowDefaultRange", "Traveller tweaks",
                6.0f, 1.0f, 64.0f,
                "Detection radius (blocks) for deciding to follow another traveller."
        );

        followDesiredDist = cfg.getFloat(
                "FollowDesiredDist", "Traveller tweaks",
                2.5f, 0.5f, 10.0f,
                "Desired standing distance behind the followed traveller."
        );

        followMaxDist = cfg.getFloat(
                "FollowMaxDist", "Traveller tweaks",
                24.0f, 1.0f, 128.0f,
                "Maximum distance (blocks) before following is cancelled."
        );

        followRepushEvery = cfg.getInt(
                "FollowRepushEvery", "Traveller tweaks",
                48, 1, 2000,
                "How often (in ticks) to reapply pathfinding push while following."
        );

        followDurationMin = cfg.getInt(
                "FollowDurationMin", "Traveller tweaks",
                1200, 20, 20000,
                "Minimum duration (ticks) to follow when starting. (20 ticks = 1s, 1200 = 1 min)."
        );

        followDurationMax = cfg.getInt(
                "FollowDurationMax", "Traveller tweaks",
                4800, 20, 40000,
                "Maximum duration (ticks) to follow when starting. (20 ticks = 1s, 2400 = 2 min)."
        );

        followAngleAlignDeg = cfg.getFloat(
                "FollowAngleAlignDeg", "Traveller tweaks",
                60f, 0f, 180f,
                "Angle (degrees) within which two travellers are considered 'roughly aligned' for following."
        );

        followStartChance = cfg.getFloat(
                "FollowStartChance", "Traveller tweaks",
                0.48f, 0.0f, 1.0f,
                "Chance (0–1) that a traveller will decide to follow when meeting another."
        );

        // Rebuild blacklist for the defaults we just wrote
        TRAVELLER_BLACKLIST.reload(travellerAggroBlacklistIds, travellerAggroBlacklistClasses);

        cfg.save();
    }


    /** Read current values from an already-loaded, version-matching config. */
    private static void readValues(Configuration cfg) {
        // Ensure version key stays pinned
        cfg.get(Configuration.CATEGORY_GENERAL, CONFIG_VERSION_KEY, CURRENT_VERSION).set(CURRENT_VERSION);

        // --- TRAVELLER ENTITY (spawn + behavior) ---
        TravellerWeight = cfg.getInt(
                "TravellerEntityWeight", "General traveller settings",
                10, 0, 256,
                "Increase to make traveller spawn more often; set to 0 to disable traveller entityAI spawn."
        );

        enableTravellerEntity = cfg.getBoolean(
                "enableTravellerEntity", "General traveller settings",
                true,
                "Enable traveller entityAI."
        );

        travellerAggroBlacklistIds = cfg.getStringList(
                "TravellerAggroBlacklistIDs", "General traveller settings",
                new String[] { "minecraft:creeper", "traveller:traveller" },
                "Blacklist by entityAI registry ID. Supports wildcards like 'modid:*'.\n" +
                        "Examples: 'minecraft:creeper', 'twilightforest:ur_ghast', 'alexsmobs:*'\n" +
                        "Entities on this list won't ever attack traveller."
        );

        travellerAggroBlacklistClasses = cfg.getStringList(
                "TravellerAggroBlacklistClasses", "General traveller settings",
                new String[] {
                        "net.minecraft.entityAI.monster.EntityGhast",
                        "net.minecraft.entityAI.monster.EntityEnderman"
                },
                "Blacklist by fully-qualified class name. Any subclass will match.\n" +
                        "Examples: 'net.minecraft.entityAI.monster.EntityGhast', 'com.example.mobs.EntityBrute'.\n" +
                        "Entities on this list won't ever attack traveller."
        );
// --- TRAVELLER BASE ATTRIBUTES ---
        maxHealth = cfg.getFloat(
                "TravellerMaxHealth", "Traveller base attributes",
                50.0f, 1.0f, 1000.0f,
                "Max health of traveller (20 = player default)."
        );

        movementSpeed = cfg.getFloat(
                "TravellerMovementSpeed", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Base movement speed multiplier. 0.1 = player walking speed."
        );

        followRange = cfg.getFloat(
                "TravellerFollowRange", "Traveller base attributes",
                48.0f, 4.0f, 256.0f,
                "How far away (in blocks) the traveller can detect targets/points of interest."
        );

        attackDamage = cfg.getFloat(
                "TravellerAttackDamage", "Traveller base attributes",
                4.0f, 0.0f, 100.0f,
                "Damage dealt per melee hit."
        );
        // --- TRAVELLER TUNING (new) ---
        followDefaultRange = cfg.getFloat(
                "FollowDefaultRange", "Traveller tweaks",
                6.0f, 1.0f, 64.0f,
                "Detection radius (blocks) for deciding to follow another traveller."
        );

        followDesiredDist = cfg.getFloat(
                "FollowDesiredDist", "Traveller tweaks",
                2.5f, 0.5f, 10.0f,
                "Desired standing distance behind the followed traveller."
        );

        followMaxDist = cfg.getFloat(
                "FollowMaxDist", "Traveller tweaks",
                24.0f, 1.0f, 128.0f,
                "Maximum distance (blocks) before following is cancelled."
        );

        followRepushEvery = cfg.getInt(
                "FollowRepushEvery", "Traveller tweaks",
                48, 1, 2000,
                "How often (in ticks) to reapply pathfinding push while following."
        );

        followDurationMin = cfg.getInt(
                "FollowDurationMin", "Traveller tweaks",
                1200, 20, 20000,
                "Minimum duration (ticks) to follow when starting. (20 ticks = 1s, 1200 = 1 min)."
        );

        followDurationMax = cfg.getInt(
                "FollowDurationMax", "Traveller tweaks",
                4800, 20, 40000,
                "Maximum duration (ticks) to follow when starting. (20 ticks = 1s, 2400 = 2 min)."
        );

        followAngleAlignDeg = cfg.getFloat(
                "FollowAngleAlignDeg", "Traveller tweaks",
                60f, 0f, 180f,
                "Angle (degrees) within which two travellers are considered 'roughly aligned' for following."
        );

        followStartChance = cfg.getFloat(
                "FollowStartChance", "Traveller tweaks",
                0.48f, 0.0f, 1.0f,
                "Chance (0–1) that a traveller will decide to follow when meeting another."
        );
        villageNear = cfg.getInt(
                "VillageNearDistance", "Traveller tweaks",
                32, 1, 256,
                "Distance (in blocks) considered 'arrived' at a village."
        );
        lonebuildingNear = cfg.getInt(
                "LoneBuildingNearDistance", "Traveller tweaks",
                12, 1, 256,
                "Distance (in blocks) considered 'arrived' at a lone building."
        );

        villagePickRadius = cfg.getInt(
                "VillagePickRadius", "Traveller tweaks",
                2000, 100, 10000,
                "Maximum radius (in blocks) within which travellers can pick the next village destination."
        );

        swordHideDelay = cfg.getInt(
                "SwordHideDelay", "Traveller tweaks",
                120, 0, 2000,
                "Ticks until sword is hidden after combat ends."
        );

        eatHealthThreshold = cfg.getFloat(
                "EatHealthThreshold", "Traveller tweaks",
                0.9f, 0.1f, 1.0f,
                "Traveller starts eating at or below this fraction of max health."
        );

        eatDurationTicks = cfg.getInt(
                "EatDurationTicks", "Traveller tweaks",
                80, 1, 1000,
                "Number of ticks it takes to finish eating (20 ticks = 1 second)."
        );

        rangedMinDist = cfg.getFloat(
                "RangedMinDist", "Traveller tweaks",
                6.0f, 1.0f, 64.0f,
                "Minimum distance before traveller uses a bow."
        );

        rangedMaxDist = cfg.getFloat(
                "RangedMaxDist", "Traveller tweaks",
                32.0f, 1.0f, 128.0f,
                "Maximum distance traveller will attempt to shoot at."
        );

        rangedCooldown = cfg.getInt(
                "RangedCooldown", "Traveller tweaks",
                40, 1, 200,
                "Ticks between ranged attacks (20 ticks = 1 second)."
        );
    }

    /** Backup existing file and delete it so a clean one can be written. */
    private static void backupAndDelete(File configFile, String oldVersion) {
        if (!configFile.exists()) {
            return; // nothing to back up
        }
        try {
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File backup = new File(configFile.getParentFile(),
                    configFile.getName().replace(".cfg", "") + "_" + ts + ".cfg.bak");
            if (!configFile.renameTo(backup)) {
                org.apache.commons.io.FileUtils.copyFile(configFile, backup);
                if (!configFile.delete()) {
                    throw new IOException("Could not delete original config after backup");
                }
            }
            System.out.println("[ModConfig] Backed up old config to: " + backup.getName());
        } catch (Throwable t) {
            System.err.println("[ModConfig] Failed to backup old config: " + t.getMessage());
            if (configFile.exists() && !configFile.delete()) {
                System.err.println("[ModConfig] Also failed to delete old config file.");
            }
        }
    }

}
