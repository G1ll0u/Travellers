package com.jubitus.traveller;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.utils.mobs.TravellerBlacklist;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.io.IOException;


public class TravellersModConfig {

    public static final TravellerBlacklist TRAVELLER_BLACKLIST = new TravellerBlacklist();
    private static final String CONFIG_VERSION_KEY = "configVersion";
    private static final String CURRENT_VERSION = "3.7";
    public static int loadedTravellerCap;
    public static int MinGroupSizeAtSpawn;
    public static int MaxGroupSizeAtSpawn;
    public static int TravellerWeight;
    public static int villageRadius;
    public static double maxHealth;
    public static double attackMovementSpeed;
    public static boolean callForHelp;
    public static double helpRange;
    public static int villageRoamMinDuration;
    public static int villageRoamMaxDuration;
    public static double movementSpeedWhileTravel;
    public static String[] travellerAggroBlacklistIds = new String[0];      // e.g., {"minecraft:creeper", "alexsmobs:*"}
    public static String[] travellerAggroBlacklistClasses = new String[0];  // e.g., {"net.minecraft.entityAI.monster.EntityGhast"}
    public static double movementSpeedWhileFollowing;
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
    public static double attackTriggerRange;
    static boolean enableTravellerEntity = true;
    private static Configuration CONFIG;      // <— keep it
    private static File CONFIG_FILE;          // <— keep it

    public static void init(File configFile) {
        CONFIG_FILE = configFile;
        // Ensure parent folder exists
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        CONFIG = new Configuration(configFile);
        boolean needsRewrite = false;

        try {
            final boolean exists = configFile.exists();
            final long size = exists ? configFile.length() : 0L;

            // treat empty/missing as new
            if (!exists || size == 0L) {
                writeDefaults(CONFIG);
                System.out.println("[ModConfig] " + (exists ? "Empty config detected; " : "")
                        + "created fresh config with version " + CURRENT_VERSION + ".");
                return;
            }

            CONFIG.load();

            // Legacy cleanup example
            if (CONFIG.getCategory(Configuration.CATEGORY_GENERAL).containsKey("terrainMargin")) {
                CONFIG.getCategory(Configuration.CATEGORY_GENERAL).remove("terrainMargin");
                System.out.println("[ModConfig] Removed legacy key 'terrainMargin'.");
                needsRewrite = true;
            }

            String version = CONFIG.get(Configuration.CATEGORY_GENERAL, CONFIG_VERSION_KEY, "").getString();
            if (!CURRENT_VERSION.equals(version)) {
                System.out.println("[ModConfig] Config version mismatch (found '" + version + "'). Recreating as " + CURRENT_VERSION + "...");
                backupAndDelete(configFile, version);
                CONFIG = new Configuration(configFile);
                writeDefaults(CONFIG);
                System.out.println("[ModConfig] Config recreated with version " + CURRENT_VERSION + ".");
                return;
            }

            // Version matches → read values
            readValues(CONFIG);
            applyRuntimeEffects(); // <-- apply immediately on game start, too

        } catch (Exception e) {
            System.err.println("[ModConfig] Error loading config: " + e.getMessage());
        } finally {
            if (CONFIG.hasChanged() || needsRewrite) {
                CONFIG.save();
                System.out.println("[ModConfig] Config saved.");
            }
            if (needsRewrite) {
                System.out.println("[ModConfig] Config updated due to legacy key removal.");
            }
        }
    }

    // fresh file
    private static void writeDefaults(Configuration cfg) {
        loadValues(cfg);
        cfg.save();
    }

    /**
     * Backup only real files (non-empty).
     */
    private static void backupAndDelete(File configFile, String oldVersion) {
        try {
            if (!configFile.exists()) {
                // Nothing to back up
                return;
            }
            if (configFile.length() == 0L) {
                // Skip creating a pointless .bak; just delete the stub
                if (!configFile.delete()) {
                    System.err.println("[ModConfig] Failed to delete empty old config file.");
                }
                return;
            }

            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            String name = configFile.getName();
            int dot = name.lastIndexOf('.');
            String base = (dot > 0 ? name.substring(0, dot) : name);
            File backup = new File(configFile.getParentFile(), base + "_" + ts + ".cfg.bak");

            java.nio.file.Path src = configFile.toPath();
            java.nio.file.Path dst = backup.toPath();

            // Try atomic move; fall back to copy+delete
            try {
                java.nio.file.Files.move(src, dst,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception moveEx) {
                java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!configFile.delete()) throw new IOException("Could not delete original config after backup");
            }

            System.out.println("[ModConfig] Backed up old config to: " + backup.getName());
        } catch (Throwable t) {
            System.err.println("[ModConfig] Failed to backup old config: " + t.getMessage());
            if (configFile.exists() && !configFile.delete()) {
                System.err.println("[ModConfig] Also failed to delete old config file.");
            }
        }
    }

    // version-matching file
    private static void readValues(Configuration cfg) {
        loadValues(cfg);
    }

    private static void applyRuntimeEffects() {
        // Example: update any existing Traveller entities with new attributes
        net.minecraftforge.fml.common.FMLCommonHandler fml = net.minecraftforge.fml.common.FMLCommonHandler.instance();
        net.minecraft.server.MinecraftServer srv = fml.getMinecraftServerInstance();
        if (srv == null) return;

        for (net.minecraft.world.World world : srv.worlds) {
            if (world == null) continue;
            for (EntityTraveller t : world.getEntities(EntityTraveller.class, e -> true)) {
                // Assuming your entity uses standard attributes:
                t.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MAX_HEALTH).setBaseValue(maxHealth);
                if (t.getHealth() > t.getMaxHealth()) t.setHealth(t.getMaxHealth());

                t.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(attackDamage);
                t.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(movementSpeed);
                t.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(followRange);
                t.onConfigReload();
            }
        }
    }

    /**
     * Write a clean file with current defaults and CURRENT_VERSION set.
     */
    private static void loadValues(Configuration cfg) {
        // Always set the version first
        cfg.get(Configuration.CATEGORY_GENERAL, CONFIG_VERSION_KEY, CURRENT_VERSION).set(CURRENT_VERSION);

        // --- TRAVELLER ENTITY (spawn + behavior) ---

        TravellerWeight = cfg.getInt(
                "TravellerEntityWeight",
                "Traveller Gen",
                10, 0, 256,
                "Increase to make traveller spawn more often; set to 0 to disable traveller entity spawn."
        );
        loadedTravellerCap = cfg.getInt(
                "loadedTravellerCap",
                "Traveller Gen",
                32, 2, 200,
                "Won't spawn traveller if CURRENTLY LOADED travellers are more than this value"
        );
        MinGroupSizeAtSpawn = cfg.getInt(
                "MinGroupSizeAtSpawn",
                "Traveller Gen",
                1, 1, 32,
                "Minimum group size when spawning"
        );
        MaxGroupSizeAtSpawn = cfg.getInt(
                "MaxGroupSizeAtSpawn",
                "Traveller Gen",
                6, 1, 32,
                "Minimum group size when traveller spawning"


        );

        enableTravellerEntity = cfg.getBoolean(
                "enableTravellerEntity",
                "General traveller settings",
                true,
                "Enable traveller entity. (stupid to disable it lol)"
        );

        travellerAggroBlacklistIds = cfg.getStringList(
                "TravellerAggroBlacklistIDs",
                "Traveller aggro blacklist",
                new String[]{"minecraft:creeper",
                        "millenaire:genericvillager",
                        "millenaire:genericasimmfemale",
                        "millenaire:genericsimmfemale"},

                "Blacklist by entityAI registry ID. Supports wildcards like 'modid:*'."

        );

        travellerAggroBlacklistClasses = cfg.getStringList(
                "TravellerAggroBlacklistClasses",
                "Traveller Aggro Blacklist",
                new String[]{
                        "net.minecraft.entity.monster.EntityGhast",
                        "net.minecraft.entity.monster.EntityEnderman"
                },
                "Blacklist by fully-qualified class name. Any subclass will match."

        );
// --- TRAVELLER BASE ATTRIBUTES ---
        maxHealth = cfg.getFloat(
                "TravellerMaxHealth", "Traveller base attributes",
                40.0f, 1.0f, 1000.0f,
                "Max health of traveller (20 = player default)."
        );

        movementSpeed = cfg.getFloat(
                "TravellerMovementSpeedBase", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Base speed mult."
        );
        movementSpeedWhileFollowing = cfg.getFloat(
                "TravellerMovementSpeedWhileFollowing", "Traveller base attributes",
                0.56f, 0.05f, 2.0f,
                "Movement speed multiplier while following"
        );
        movementSpeedWhileTravel = cfg.getFloat(
                "TravellerMovementSpeedWhileTravelling", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Movement speed multiplier for travel task."
        );
        attackMovementSpeed = cfg.getFloat(
                "TravellerMovementSpeedWhileAttacking", "Traveller base attributes",
                0.67f, 0.05f, 2.0f,
                "Movement speed multiplier while attacking."
        );

        followRange = cfg.getFloat(
                "TravellerTrackRange", "Traveller base attributes",
                64.0f, 4.0f, 256.0f,
                "How far away (in blocks) the traveller can detect targets/points of interest."
        );

        attackDamage = cfg.getFloat(
                "TravellerAttackDamage", "Traveller base attributes",
                2.0f, 0.0f, 100.0f,
                "Base damage dealt per melee hit."
        );

        // --- TRAVELLER TUNING (new) ---
        villageNear = cfg.getInt(
                "VillageNearDistance", "Traveller destination tweaks",
                64, 1, 256,
                "Distance (in blocks) considered 'arrived' at a village."
        );
        // --- TRAVELLER TUNING (new) ---
        villageRadius = cfg.getInt(
                "VillageRoamArea", "Traveller destination tweaks",
                64, 1, 256,
                "Area size to roam in village (has to be less than VillageNearDistance)"
        );

        lonebuildingNear = cfg.getInt(
                "LoneBuildingNearDistance", "Traveller destination tweaks",
                16, 1, 256,
                "Distance (in blocks) considered 'arrived' at a lone building."
        );

        villagePickRadius = cfg.getInt(
                "VillagePickRadius", "Traveller destination tweaks",
                4000, 100, 10000,
                "Maximum radius (in blocks) within which travellers can pick the next village destination."
        );
        //COMBAT
        callForHelp = cfg.getBoolean(
                "callForHelp",
                "Traveller combat tweaks",
                true,
                "If true, travellers will call nearby allies for help when attacked."
        );

        helpRange = cfg.getFloat(
                "helpRange",
                "Traveller combat tweaks",
                24.0f, 1.0f, 128.0f,
                "Maximum range (in blocks) within which allies will respond to a traveller’s call for help."
        );


        swordHideDelay = cfg.getInt(
                "SwordHideDelay", "Traveller combat tweaks",
                120, 0, 2000,
                "Ticks until sword is hidden after combat ends."
        );

        eatHealthThreshold = cfg.getFloat(
                "EatHealthThreshold", "Traveller combat tweaks",
                0.9f, 0.1f, 1.0f,
                "Traveller starts eating at or below this fraction of max health."
        );

        eatDurationTicks = cfg.getInt(
                "EatDurationTicks", "Traveller combat tweaks",
                80, 1, 1000,
                "Number of ticks it takes to finish eating (20 ticks = 1 second)."
        );

        rangedMinDist = cfg.getFloat(
                "RangedMinDist", "Traveller combat tweaks",
                6.0f, 1.0f, 64.0f,
                "Minimum distance before traveller uses a bow."
        );

        rangedMaxDist = cfg.getFloat(
                "RangedMaxDist", "Traveller combat tweaks",
                32.0f, 1.0f, 128.0f,
                "Maximum distance traveller will attempt to shoot at."
        );

        rangedCooldown = cfg.getInt(
                "RangedCooldown", "Traveller combat tweaks",
                40, 1, 200,
                "Ticks between ranged attacks (20 ticks = 1 second)."
        );
        attackTriggerRange = cfg.getInt(
                "AttackTriggerRange", "Traveller combat tweaks",
                16, 1, 200,
                "How far (in blocks) traveller will attack an hostile mob."
        );
// --- FOLLOWER AI TUNING (new) ---
        followDefaultRange = cfg.getFloat(
                "FollowDefaultRange", "Traveller follow task tweaks",
                6.0f, 1.0f, 64.0f,
                "Detection radius (blocks) for deciding to follow another traveller."
        );

        followDesiredDist = cfg.getFloat(
                "FollowDesiredDist", "Traveller follow task tweaks",
                2.5f, 0.5f, 10.0f,
                "Desired standing distance behind the followed traveller."
        );

        followMaxDist = cfg.getFloat(
                "FollowMaxDist", "Traveller follow task tweaks",
                24.0f, 1.0f, 128.0f,
                "Maximum distance (blocks) before following is cancelled."
        );

        followRepushEvery = cfg.getInt(
                "FollowRepushEvery", "Traveller follow task tweaks",
                48, 1, 2000,
                "How often (in ticks) to reapply pathfinding push while following. Default value is good."
        );

        followDurationMin = cfg.getInt(
                "FollowDurationMin", "Traveller follow task tweaks",
                60, 20, 20000,
                "Minimum duration (seconds) to follow when starting."
        );

        followDurationMax = cfg.getInt(
                "FollowDurationMax", "Traveller follow task tweaks",
                240, 20, 40000,
                "Maximum duration (seconds) to follow when starting. (20 ticks = 1s, 2400 = 2 min)."
        );

        followAngleAlignDeg = cfg.getFloat(
                "FollowAngleAlignDeg", "Traveller follow task tweaks",
                60f, 0f, 180f,
                "Angle (degrees) within which two travellers are considered 'roughly aligned' for following."
        );

        followStartChance = cfg.getFloat(
                "FollowStartChance", "Traveller follow task tweaks",
                0.48f, 0.0f, 1.0f,
                "Chance (0–1) that a traveller will decide to follow when meeting another."
        );


        villageRoamMinDuration = cfg.getInt(
                "villageRoamMinDuration",
                "Traveller roam inside village task tweaks",
                30, 0, 24000,
                "Minimum duration (in seconds) a traveller will roam before resetting its roam target."
        );

        villageRoamMaxDuration = cfg.getInt(
                "villageRoamMaxDuration",
                "Traveller roam inside village task tweaks",
                120, 0, 24000,
                "Maximum duration (in seconds) a traveller will roam before resetting its roam target."
        );

        // Rebuild blacklist for the defaults we just wrote
        TRAVELLER_BLACKLIST.reload(travellerAggroBlacklistIds, travellerAggroBlacklistClasses);

        cfg.save();
    }

    /**
     * Public entrypoint to reload from disk (for commands or GUI).
     */
    public static void reload() {
        if (CONFIG == null) {
            CONFIG = new Configuration(CONFIG_FILE);
        }
        try {
            CONFIG.load();           // re-read file on disk
            readValues(CONFIG);      // populate static fields + blacklist
            if (CONFIG.hasChanged()) CONFIG.save();
            applyRuntimeEffects();   // << important: push values into live entities/AI
            System.out.println("[ModConfig] Reloaded config from disk.");
        } catch (Exception e) {
            System.err.println("[ModConfig] Reload failed: " + e.getMessage());
        }
    }
}