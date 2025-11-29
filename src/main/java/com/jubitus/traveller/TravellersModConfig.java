package com.jubitus.traveller;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.utils.mobs.RaiderAttackList;
import com.jubitus.traveller.traveller.utils.mobs.TravellerBlacklist;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.io.IOException;


public class TravellersModConfig {
    public static boolean debug = false;
    public static boolean enableTravellerOpenFenceGates = true;
    public static boolean enableTravellerOpenDoors = true;

    public static boolean enableMobFarmAvoidance = true;

    public static int mobFarmCheckRadius = 6;


    public static int mobFarmEntityThreshold = 15;


    public static int mobFarmFenceRadius = 4;

    public static int mobFarmFenceThreshold = 12;
    public static int followMaxFollowersPerLeader = 4; // 0 or negative = unlimited

    public static final java.util.Set<String> EXCLUDED_RAIDER_VILLAGER_TAGS = new java.util.HashSet<>();
    public static final java.util.Set<String> EXCLUDED_RAIDER_VILLAGER_FILES = new java.util.HashSet<>();
    public static String[] excludedRaiderVillagerTagsRaw = new String[] {
            // e.g. keep children for raiders if you want, or:
            "child","chief"
    };
    public static String[] excludedRaiderVillagerFilesRaw = new String[] {
            // maybe raiders should not look like chiefs:
            "alchemist", "alchemistapprentice", "alchemistassistant"
    };

    // NEW: Raider include filters (whitelist-style)
    public static final java.util.Set<String> INCLUDED_RAIDER_VILLAGER_TAGS  = new java.util.HashSet<>();
    public static final java.util.Set<String> INCLUDED_RAIDER_VILLAGER_FILES = new java.util.HashSet<>();

    // Raw config arrays for raider includes (default: empty = "no restriction")
    public static String[] includedRaiderVillagerTagsRaw  = new String[]{
            "hostile"
    };
    public static String[] includedRaiderVillagerFilesRaw = new String[]{
    };

    // How far villages/lonebuildings influence traveller spawns (in blocks)
    public static int travellerVillageInfluenceRadius = 1200;  // e.g. 400 blocks
    public static int travellerLoneInfluenceRadius    = 600;  // e.g. 250 blocks

    // How strong each center type is
    public static double travellerVillageWeight = 1.0;  // 1.0 = full strength
    public static double travellerLoneWeight    = 0.4;  // lonebuildings weaker

    // Shape of falloff: 1.0 = linear, >1 = steeper near edge, <1 = flatter
    public static double travellerFalloffPower  = 1.0;

    public static int travellerMaxPositionChecksPerAttempt = 4;
    public static int raiderMaxPositionChecksPerAttempt = 4;

    // Optional: cap on total density when multiple villages overlap
    public static double travellerMaxDensity    = 1.5;
    public static final int DOG_SCAN_COOLDOWN_TICKS = 5 * 20;
    public static final int CAMP_SWING_EVERY = 80;         // ~4s baseline between “cooking” swings
    public static final int SPAWN_AMBIENT_DELAY_TICKS = 12 * 20;   // ~12s after spawn
    public static final RaiderAttackList RAIDER_ATTACK_LIST = new RaiderAttackList();
    public static final TravellerBlacklist TRAVELLER_BLACKLIST = new TravellerBlacklist();
    // NEW: two separate aggro blacklists
    public static final TravellerBlacklist TRAVELLER_AGGRO_BLACKLIST = new TravellerBlacklist();
    public static final TravellerBlacklist RAIDER_AGGRO_BLACKLIST = new TravellerBlacklist();
    public static final java.util.Set<String> EXCLUDED_VILLAGER_TAGS = new java.util.HashSet<>();
    public static final java.util.Set<String> EXCLUDED_VILLAGER_FILES = new java.util.HashSet<>();
    public static final int CAMP_JOIN_COOLDOWN_TICKS = 20 * 60; // 60s; tweak as you like
    public static final int THREAT_FOR_SEE_ATTACK = 5;// when we "observe" hostiles hurting allies/players
    private static final String CONFIG_VERSION_KEY = "configVersion";
    private static final String CURRENT_VERSION = "4.6"; // yes I always change ver when testing, that's stupid
    public static final int CAMP_RANDOM_COOLDOWN = 6 * 60 * 20; // ~6 min between pauses
    // --- Grace hits vs players ---
    public static final int PLAYER_FREE_HITS = 3;
    public static final int PLAYER_FORGIVE_WINDOW_TICKS = 6 * 20; // reset window per attacker
    public static final double CHORUS_RADIUS = 32.0;
    public static final int TRAVEL_TORCH_COOLDOWN_TICKS_DEFAULT = 20 * 10; // fallback if config 0
    public static final double TRAVEL_TORCH_NEARBY_RADIUS_DEFAULT = 10.0D;
    public static final int THREAT_DECAY_PER_SEC = 2; // decay rate
    public static final int THREAT_DECAY_INTERVAL_TICKS = 20; // 1s
    public static final int THREAT_TTL_TICKS = 20 * 30; // forget after 30s of not seeing it
    // --- Auto-eat state ---
    public static final double SWIM_MULT = 3; // ~+130% in water (feels snappy, stays stable)
    public static final double CORRIDOR_WIDTH = 64.0; // tune 32–64
    public static int CAMP_RADIUS = 8; // or expose a public constant on EntityTraveller
    // === CAMP / FIREPIT BEHAVIOR ===
    public static int SEEK_RADIUS = 24; // how far to look for camps
    public static int MAX_JOINERS_PER_CAMP = 12;
    // Firecamp idle behavior (wander / look delays in ticks)
    public static int CAMP_WANDER_MIN_DELAY = 40;   // 2s  (20 tps)
    public static int CAMP_WANDER_MAX_DELAY = 140;  // 7s
    public static int CAMP_LOOK_MIN_DELAY = 30;   // 1.5s
    public static int CAMP_LOOK_MAX_DELAY = 110;  // 5.5s
    public static int loadedRaiderCap;
    public static int serverViewDistanceFallbackChunks = 12;
    public static boolean fishWhileTravelEnabled = true;
    public static int loadedTravellerCap;
    public static int MinGroupSizeAtSpawn;
    public static int MaxGroupSizeAtSpawn;
    public static int TravellerWeight;
    public static int villageRadius;
    public static final int VILLAGE_RADIUS = villageRadius;     // area to roam in (blocks from center)
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
    public static final int VILLAGE_NEAR = villageNear;       // distance considered "arrived"
    public static int lonebuildingNear;
    public static int villagePickRadius;
    public static final int VILLAGE_PICK_RADIUS = villagePickRadius; // max radius for next-destination picks
    public static int swordHideDelay;
    public static final int SWORD_HIDE_DELAY = swordHideDelay; // ticks until sword is hidden
    public static float eatHealthThreshold;
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
    public static int followRange;
    public static double attackDamage;
    public static double attackTriggerRange;
    public static boolean travellerSpeaks;
    public static boolean travellerAmbient = true;           // NEW
    public static int travellerAmbientMinDelay;   // 10s
    public static int travellerAmbientMaxDelay;    // 30s
    public static float travellerAmbientVolume;      // 0.0–1.0
    public static boolean enableTravellerRaider = true;
    public static int RaiderWeight;
    public static int RaiderMinGroup;
    public static int RaiderMaxGroup;
    public static int playerCalmForgiveTicks;  // 8s without hitting -> calm down
    public static double playerCalmNotifyRadius; // how far we "hear" a player's hit
    public static int playerFleeForgiveTicks;   // need to be out of LOS/too far for 6s
    public static double playerFleeKeepDistance;  // if closer than this, never forgive (even w/o hits)
    public static String[] raiderAggroBlacklistIds = new String[0];
    public static String[] raiderAggroBlacklistClasses = new String[0];
    public static int villageSpawnRadius = 128; // ~160 blocks radius (~10 chunks). Tune 96–256.
    public static int travellerLocalCap = 64;        // max travellers within radius
    public static int travellerLocalCapRadius = 192; // ~12 chunks
    public static int raiderLocalCap = 64;           // scarcer than travellers
    public static int raiderLocalCapRadius = 192;
    public static int travellerSpawnPermille = 120; // 12% of “eligible” attempts
    public static boolean despawnNearUnloadedChunks = true;
    public static int despawnProbeRadiusChunks = 1; // how many chunks around the mob must be loaded (1 = 3x3)
    public static int despawnEdgeGraceTicks = 3 * 20; // time standing near the edge before fading
    public static int travellerTargetLoaded = 24;       // total travellers you’d like loaded (across all dims)
    public static int travellerTopUpEveryTicks = 600;    // how often to try
    public static int travellerAttemptsPerPlayer = 1;   // attempts per player each cycle
    public static int travellerSpawnMinDist = 128;       // from player
    public static int travellerSpawnMaxDist = 256;       // from player
    public static int travellerSpawnLightMin = 8;       // same as your getCanSpawnHere
    public static boolean despawnWhenFarFromPlayers = true;
    public static int farExtraChunks = 2;           // buffer beyond view radius (in chunks)
    public static int farGraceTicks = 15 * 20;      // must be far for ~15s to fade
    public static int farMinDistanceBlocks = 128;   // hard floor even if view radius is tiny
    // Raiders — soft-spawn target & pacing
    public static int raiderTargetLoaded = 12;          // keep far lower than travellers
    public static int raiderTopUpEveryTicks = 600;     // try every ~10s
    public static int raiderAttemptsPerPlayer = 1;     // 1 try per player each cycle
    public static float raiderSoftSpawnChancePerAttempt = 1.0F; // only 25% of tries attempt
    // Where to place them relative to players (reuse/adjust ring)
    public static int raiderSpawnMinDist = 128;
    public static int raiderSpawnMaxDist = 256;
    // Darkness rule (hostile vibe)
    public static int raiderMaxBlockLight = 8;         // require block light
    public static boolean enableTravellerSoftSpawns;
    public static boolean enableRaiderSoftSpawns;
    public static boolean campsEnabled = true;
    public static boolean travelTorchEnabled = false; // default on
    public static int travelTorchCooldownTicks = 20 * 10; // e.g. 10s after unequip
    public static double travelTorchNearbyRadius = 20.0D; // “someone else has light” radius
    public static boolean spawnTravellerWithDog = true;
    public static double travellerDogSpawnChance = 0.03; // 3%
    public static String[] excludedVillagerTagsRaw = new String[]{"child", "chief",};
    public static String[] excludedVillagerFilesRaw = new String[]{"alchemist", "alchemistapprentice", "alchemistassistant"};
    public static int fishWaterScanRadius = 6;          // blocks
    public static int fishMinTimeTicks = 15 * 20;     // 8s
    public static int fishMaxTimeTicks = 40 * 20;    // 16s
    public static int fishReelEveryTicks = 45;         // ticks
    public static int fishCastWindupTicks = 15;         // ticks
    public static int fishCooldownTicks = 3 * 60 * 20; // 3 minutes
    public static float fishStartChancePerTick = 0.0015f;
    public static double fishApproachSpeed = 0.45D;
    public static int stepBlocks = 32;
    // Minimum distance (in blocks) raiders must spawn away from any village TH
    public static int raiderMinVillageDistance = 172;
    static boolean enableTravellerEntity = true;
    private static String[] raiderAttackIdRules = new String[0];
    private static String[] raiderAttackClassRules = new String[0];
    private static Configuration CONFIG;      // <— keep it
    private static File CONFIG_FILE;          // <— keep it
    public static boolean showTravellerNames = false;
    // Base chance per anchor attempt, before density (0..1)
    public static double travellerBaseSpawnChance = 0.6;
    public static float campStartChancePerTick = 0.0008f;
    // --- Traveller population-based spawn weighting ---
    public static boolean travellerPopulationWeightEnabled = true;
    /** Horizontal radius (blocks) to look for the "host" village for a spawn pos */
    public static int travellerPopulationWeightRadius = 400;
    /** Extra spawn weight per villager (0.02 => +2% per villager) */
    public static double travellerPopulationWeightPerVillager = 0.05D;
    /** Max overall multiplier from population (e.g. 3.0 => up to 3×) */
    public static double travellerPopulationWeightMaxMultiplier = 1.5D;

    /** Baseline spawn density when no village/lonebuilding influence (0 = none) */
    public static double travellerOutsideVillageBaseDensity = 0.2D;

    // --- Traveller culture-weighted spawns ---
    public static boolean travellerCultureWeightedSpawn = true;
    /** Radius to scan for village cultures when choosing spawn culture */
    public static int travellerCultureScanRadius = 512;
    /** If true, cultures with bigger population get more weight */
    public static boolean travellerCultureWeightUseVillagePopulation = true;

    // ---- Dynamic traveller caps (world + regional) ----
    public static float travellerCapPerVillager;        // 1/3 = 1 traveller per ~3 villagers
    public static int   travellerCapMinPerVillage;
    public static int   travellerCapMaxPerVillage;
    public static int   travellerCapPerLoneBuilding;
    public static int   travellerCapGlobalMin;
    public static int   travellerCapGlobalMax;
    public static int   travellerRegionalRadius;

    // ---- Dynamic raider caps (regional) ----
    public static float raiderCapPerVillager;           // 1/5 = 1 raider per ~5 villagers
    public static int   raiderCapMinPerVillage;
    public static int   raiderCapMaxPerVillage;
    public static int   raiderCapPerLoneBuilding;
    public static int   raiderRegionalRadius;
    
    // And a dedicated base spawn chance for raiders (instead of reusing traveller one)
    public static float raiderBaseSpawnChance;

    public static void init(File configFile) {
        CONFIG_FILE = configFile;

        // NEW: delete legacy /config/travellers.cfg if it exists
        deleteLegacyRootConfig();

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
        if (rangedMaxDist < attackTriggerRange) {
            rangedMaxDist = attackTriggerRange;
        }
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
        showTravellerNames = cfg.getBoolean(
                "showTravellersNames",
                "general",
                false,
                "If false, Traveller name tags will not be shown above their heads."
        );
        TravellerWeight = cfg.getInt(
                "TravellerWeight",
                "Traveller & Raider Gen",
                0, 0, 256,
                "Don't set over than 0 if you use my soft-spawner, it is the vanilla traveller spawn weight"
        );
        loadedTravellerCap = cfg.getInt(
                "loadedTravellerCap",
                "Traveller & Raider Gen",
                64, 2, 200,
                "Won't spawn traveller if CURRENTLY LOADED travellers are more than this value"
        );
        MinGroupSizeAtSpawn = cfg.getInt(
                "TravellerMinGroupSizeAtSpawn",
                "Traveller & Raider Gen",
                2, 1, 32,
                "Minimum group size when spawning. Impacts soft spawner."
        );
        MaxGroupSizeAtSpawn = cfg.getInt(
                "TravellerMaxGroupSizeAtSpawn",
                "Traveller & Raider Gen",
                12, 1, 32,
                "Maximum group size when traveller spawning. Impacts soft spawner."

        );
        loadedRaiderCap = cfg.getInt(
                "loadedRaiderCap", "Traveller & Raider Gen",
                64, 1, 256,
                "Won't naturally spawn raiders if CURRENTLY LOADED travellers are more than this value."
        );
        RaiderWeight = cfg.getInt(
                "RaiderWeight", "Traveller & Raider Gen",
                0, 0, 256,
                "Don't set over than 0 if you use my soft-spawner, it is the vanilla raider spawn weight"
        );
        RaiderMinGroup = cfg.getInt(
                "RaiderMinGroup", "Traveller & Raider Gen",
                2, 1, 32,
                "Minimum group size for raider spawns. Impacts soft spawner."
        );
        RaiderMaxGroup = cfg.getInt(
                "RaiderMaxGroup", "Traveller & Raider Gen",
                8, 1, 32,
                "Maximum group size for raider spawns (must be >= min). Impacts soft spawner."
        );

        // === TRAVELLER PET / DOG COMPANION ===
        spawnTravellerWithDog = cfg.getBoolean(
                "spawnTravellerWithDog",
                "Traveller & Raider Gen",
                spawnTravellerWithDog,
                "If true, travellers have a chance to spawn with a dog companion."
        );

        travellerDogSpawnChance = cfg.getFloat(
                "travellerDogSpawnChance",
                "Traveller & Raider Gen",
                (float) travellerDogSpawnChance, 0.0f, 1.0f,
                "Chance (0.0–1.0) that a traveller spawns with a dog companion."
        );

        travellerPopulationWeightPerVillager = cfg.getFloat(
                "PopulationWeightPerVillager",
                "Soft spawn — General",
                0.05F,     // default
                0.0F,      // min
                10.0F,     // max
                "Extra spawn weight added per villager (0.02 ⇒ +2% per villager)"
        );

        travellerPopulationWeightMaxMultiplier = cfg.getFloat(
                "PopulationWeightMaxMultiplier",
                "Soft spawn — General",
                1.5F,      // default
                0.1F,      // min
                10.0F,     // max
                "Maximum overall multiplier applied from population influence"
        );

        travellerOutsideVillageBaseDensity = cfg.getFloat(
                "OutsideVillageBaseDensity",
                "Soft spawn — General",
                0.2F,      // default
                0.0F,      // min
                10.0F,     // max
                "Baseline spawn density outside of villages and lone buildings (0 = none)"
        );


        travellerVillageInfluenceRadius = cfg.getInt(
                "VillageInfluenceRadius", "Soft spawn — General",
                1200, 0, 5000,
                "How far a Millénaire *village* influences traveller spawning (in blocks).\n" +
                        "Higher = larger halo around villages."
        );

        travellerLoneInfluenceRadius = cfg.getInt(
                "LoneInfluenceRadius", "Soft spawn — General",
                600, 0, 5000,
                "How far a *lonebuilding* influences traveller spawning (in blocks).\n" +
                        "Lonebuildings usually have a smaller radius."
        );

        travellerVillageWeight = cfg.getFloat(
                "VillageWeight", "Soft spawn — General",
                1.0f, 0.0f, 10.0f,
                "Strength of village influence.\n" +
                        "1.0 = normal, >1 = stronger, 0 disables villages."
        );

        travellerLoneWeight = cfg.getFloat(
                "LoneWeight", "Soft spawn — General",
                0.4f, 0.0f, 10.0f,
                "Strength of lonebuilding influence.\n" +
                        "0.4 = weaker than villages, 0 disables lonebuilding influence."
        );

        travellerFalloffPower = cfg.getFloat(
                "FalloffPower", "Soft spawn — General",
                1.0f, 0.1f, 10.0f,
                "Shape of falloff curve.\n" +
                        "1 = linear, >1 = sharper falloff near edge, <1 = softer and flatter."
        );

        travellerMaxDensity = cfg.getFloat(
                "TravellerMaxDensity", "Soft spawn — General",
                1.5f, 0.1f, 50.0f,
                "Maximum combined density when multiple village/lonebuilding halos overlap.\n" +
                        "Prevents stacked villages from giving extreme spawn weights."
        );

        if (RaiderMaxGroup < RaiderMinGroup) {
            RaiderMaxGroup = RaiderMinGroup;
        }

// --- PLAYER CALM / FLEE LOGIC (combat tuning) ---
        playerCalmForgiveTicks = cfg.getInt(
                "PlayerCalmForgiveTicks", "Traveller combat tweaks",
                200, 0, 20 * 60,
                "Ticks a player must go without hitting anything before travellers drop aggro (20 ticks = 1s)."
        );
        playerCalmNotifyRadius = cfg.getFloat(
                "PlayerCalmNotifyRadius", "Traveller combat tweaks",
                18, 1.0f, 128.0f,
                "Radius around a player within which travellers will notice the player's last hit."
        );
        playerFleeForgiveTicks = cfg.getInt(
                "PlayerFleeForgiveTicks", "Traveller combat tweaks",
                200, 0, 20 * 60,
                "Ticks a player must stay out of line-of-sight / far enough before raiders give up the chase."
        );
        playerFleeKeepDistance = cfg.getFloat(
                "PlayerFleeKeepDistance", "Traveller combat tweaks",
                24.0f, 1.0f, 128.0f,
                "If the player is closer than this distance, raiders won't forgive even if not hitting."
        );

        // SOUND
        travellerSpeaks = cfg.getBoolean(
                "travellerSpeaks",
                "sound",
                true,
                "If true, say hello when right click on them."
        );
        travellerSpeaks = cfg.getBoolean("travellerSpeaks", "sound", true,
                "If true, travellers play a voice line when right-clicked.");
        travellerAmbient = cfg.getBoolean("travellerAmbient", "sound", true,
                "If true, travellers occasionally mutter/whistle while travelling.");
        travellerAmbientMinDelay = cfg.getInt("travellerAmbientMinDelay", "sound", 1500, 40, 20_000,
                "Minimum ticks between ambient travel lines (default 200 = 10s).");
        travellerAmbientMaxDelay = cfg.getInt("travellerAmbientMaxDelay", "sound", 5000, 80, 60_000,
                "Maximum ticks between ambient travel lines (default 600 = 30s).");
        travellerAmbientVolume = cfg.getFloat("travellerAmbientVolume", "sound", 0.8F, 0.0F, 1.0F,
                "Volume for ambient travel lines (0.0–1.0).");
        if (travellerAmbientMaxDelay < travellerAmbientMinDelay) {
            travellerAmbientMaxDelay = travellerAmbientMinDelay;
        }
        enableTravellerEntity = cfg.getBoolean(
                "enableTravellerEntity",
                "General traveller settings",
                true,
                "Enable traveller entity. (stupid to disable it lol)"
        );

        // ATTACK

        travellerAggroBlacklistIds = cfg.getStringList(
                "TravellerAggroBlacklistIDs",
                "Traveller aggro blacklist",
                new String[]{
                        "minecraft:creeper",
                },
                "Blacklist by entity registry ID. Supports wildcards like 'modid:*'."
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

        raiderAggroBlacklistIds = cfg.getStringList(
                "RaiderAggroBlacklistIDs",
                "raider attack tweaks",
                new String[]{
                        "minecraft:creeper"
                }, // default: empty (raiders fight most things)
                "Blacklist by entity registry ID for RAIDERS. Supports wildcards like 'modid:*'."
        );

        raiderAggroBlacklistClasses = cfg.getStringList(
                "RaiderAggroBlacklistClasses",
                "raider attack tweaks",
                new String[]{},
                "Blacklist by fully-qualified class name for RAIDERS. Any subclass will match."
        );


        raiderAttackIdRules = cfg.getStringList(
                "raiderAttackEntityIds",
                "raider attack tweaks",
                new String[]{
                        "millenaire:genericvillager",
                        "millenaire:genericasimmfemale",
                        "millenaire:genericsimmfemale"},
                "Entity IDs raider travellers will attack (exact or 'modid:*')."

        );

        raiderAttackClassRules = cfg.getStringList(
                "raiderAttackEntityClasses",
                "raider attack tweaks",
                new String[]{},
                "Java classes raider travellers will attack (exact or subclasses)."
        );

// --- TRAVELLER BASE ATTRIBUTES ---
        maxHealth = cfg.getFloat(
                "TravellerMaxHealth", "Traveller base attributes",
                40.0f, 1.0f, 1000.0f,
                "Max health of traveller (20 = player default)."
        );


        movementSpeed = cfg.getFloat(
                "MovementSpeedBase", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Base speed mult."
        );
        movementSpeedWhileFollowing = cfg.getFloat(
                "MovementSpeedWhileFollowing", "Traveller base attributes",
                0.58f, 0.05f, 2.0f,
                "Movement speed multiplier while following"
        );
        movementSpeedWhileTravel = cfg.getFloat(
                "MovementSpeedWhileTravelling", "Traveller base attributes",
                0.56f, 0.05f, 2.0f,
                "Movement speed multiplier for travel task."
        );
        attackMovementSpeed = cfg.getFloat(
                "MovementSpeedWhileAttacking", "Traveller base attributes",
                0.67f, 0.05f, 2.0f,
                "Movement speed multiplier while attacking."
        );

        followRange = cfg.getInt(
                "TravellerTrackRange", "Traveller base attributes",
                128, 4, 256,
                "How far away (in blocks) the traveller can detect targets/points of interest."
        );

        attackDamage = cfg.getFloat(
                "TravellerAttackDamage", "Traveller base attributes",
                2.0f, 0.0f, 100.0f,
                "Base damage dealt per melee hit."
        );

        // --- TRAVELLER TUNING (new) ---
        villageNear = cfg.getInt(
                "VillageNearDistance", "destination tweaks",
                32, 1, 256,
                "Distance (in blocks) considered 'arrived' at a village."
        );
        // --- TRAVELLER TUNING (new) ---
        villageRadius = cfg.getInt(
                "VillageRoamArea", "destination tweaks",
                32, 1, 256,
                "Area size to roam in village (has to be equal or less than VillageNearDistance)"
        );

        lonebuildingNear = cfg.getInt(
                "LoneBuildingNearDistance", "destination tweaks",
                16, 1, 256,
                "Distance (in blocks) considered 'arrived' at a lone building."
        );

        villagePickRadius = cfg.getInt(
                "VillagePickRadius", "destination tweaks",
                4000, 100, 10000,
                "Maximum radius (in blocks) within which travellers can pick the next village destination."
        );
        //COMBAT
        playerCalmForgiveTicks = cfg.getInt(
                "playerCalmForgiveTicks", "Traveller combat tweaks", playerCalmForgiveTicks, 20, 20 * 60,
                "Ticks a player must go without hitting anything before travellers drop aggro."
        );
        playerCalmNotifyRadius = cfg.getFloat(
                "playerCalmNotifyRadius", "Traveller combat tweaks", (float) playerCalmNotifyRadius, 4f, 64f,
                "Radius around a player within which travellers will notice the player's last hit."
        );
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
                0.7f, 0.1f, 1.0f,
                "Traveller starts eating at or below this fraction of max health."
        );

        rangedMinDist = cfg.getFloat(
                "RangedMinDist", "Traveller combat tweaks",
                6.0f, 1.0f, 64.0f,
                "Minimum distance before traveller uses a bow."
        );

        rangedMaxDist = cfg.getFloat(
                "RangedMaxDist", "Traveller combat tweaks",
                24.0f, 1.0f, 128.0f,
                "Maximum distance traveller will attempt to shoot at."
        );

        rangedCooldown = cfg.getInt(
                "RangedCooldown", "Traveller combat tweaks",
                40, 1, 200,
                "Ticks between ranged attacks (20 ticks = 1 second)."
        );
        attackTriggerRange = cfg.getInt(
                "AttackTriggerRange", "Traveller combat tweaks",
                24, 1, 200,
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
                128.0f, 1.0f, 128.0f,
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
                320, 20, 40000,
                "Maximum duration (seconds) to follow when starting. (20 ticks = 1s, 2400 = 2 min)."
        );

        followAngleAlignDeg = cfg.getFloat(
                "FollowAngleAlignDeg", "Traveller follow task tweaks",
                360f, 0f, 180f,
                "Angle (degrees) within which two travellers are considered 'roughly aligned' for following."
        );

        followStartChance = cfg.getFloat(
                "FollowStartChance", "Traveller follow task tweaks",
                0.75f, 0.0f, 1.0f,
                "Chance (0–1) that a traveller will decide to follow when meeting another."
        );


        villageRoamMinDuration = cfg.getInt(
                "villageRoamMinDuration",
                "Traveller roam inside village task tweaks",
                10, 0, 24000,
                "Minimum duration (in seconds) a traveller will roam before resetting its roam target."
        );

        villageRoamMaxDuration = cfg.getInt(
                "villageRoamMaxDuration",
                "Traveller roam inside village task tweaks",
                30, 0, 24000,
                "Maximum duration (in seconds) a traveller will roam before resetting its roam target."
        );

        // === DESPAWN / PERFORMANCE ===
        despawnNearUnloadedChunks = cfg.getBoolean(
                "despawnNearUnloadedChunks", "Despawn & performance",
                despawnNearUnloadedChunks,
                "If true, entities near the edge of loaded chunks will fade out after a short grace."
        );

        despawnWhenFarFromPlayers = cfg.getBoolean(
                "despawnWhenFarFromPlayers", "Despawn & performance",
                despawnWhenFarFromPlayers,
                "If true, entities far beyond any player's view radius (with a buffer) will fade."
        );
        stepBlocks = cfg.getInt(
                "StepBlocks", "Despawn & performance",
                64, 1, 128,
                "Distance in blocks between generated path waypoints while travelling."
        );

        farExtraChunks = cfg.getInt(
                "farExtraChunks", "Despawn & performance",
                farExtraChunks, 0, 16,
                "Extra chunk buffer beyond the server view radius used to decide 'far'."
        );

        farGraceTicks = cfg.getInt(
                "farGraceTicks", "Despawn & performance",
                farGraceTicks, 0, 20 * 60 * 10,
                "How long an entity must remain far before despawning (ticks; 20 = 1s)."
        );

        farMinDistanceBlocks = cfg.getInt(
                "farMinDistanceBlocks", "Despawn & performance",
                farMinDistanceBlocks, 16, 1024,
                "Hard minimum distance considered 'far', even if the view radius is tiny."
        );

// === Soft spawn — Travellers  ===
        travellerTargetLoaded = cfg.getInt(
                "travellerTargetLoaded", "Soft spawn — Travellers",
                travellerTargetLoaded, 0, 500,
                "Soft target for concurrently loaded Travellers across all dimensions."
        );

        travellerTopUpEveryTicks = cfg.getInt(
                "travellerTopUpEveryTicks", "Soft spawn — Travellers",
                travellerTopUpEveryTicks, 1, 20 * 60 * 30,
                "How often to attempt spawn Travellers (ticks)."
        );

        travellerSpawnMinDist = cfg.getInt(
                "travellerSpawnMinDist", "Soft spawn — Travellers",
                travellerSpawnMinDist, 8, 512,
                "Minimum spawn distance from the player (blocks)."
        );

        travellerSpawnMaxDist = cfg.getInt(
                "travellerSpawnMaxDist", "Soft spawn — Travellers",
                travellerSpawnMaxDist, 16, 1024,
                "Maximum spawn distance from the player (blocks). Must be >= min."
        );

        travellerSpawnLightMin = cfg.getInt(
                "travellerSpawnLightMin", "Soft spawn — Travellers",
                travellerSpawnLightMin, 0, 15,
                "Minimum block light level at the spawn position."
        );
        travellerMaxPositionChecksPerAttempt = cfg.getInt(
                "travellerMaxPositionChecksPerAttempt",
                "Soft spawn — Travellers",
                4, 1, 32,
                "How many random positions to test per soft-spawn attempt before giving up."
        );

        raiderMaxPositionChecksPerAttempt = cfg.getInt(
                "raiderMaxPositionChecksPerAttempt",
                "Soft spawn — Raiders",
                4, 1, 32,
                "How many random positions to test per soft-spawn attempt for raiders."
        );
        // ===== Traveller caps =====
        travellerCapPerVillager = cfg.getFloat(
                "TravellerCapPerVillager", "Soft spawn — Travellers",
                1.0f / 3.0f, 0.01f, 10.0f,
                "Dynamic traveller cap: travellers per villager. 1/3 = ~1 traveller per 3 villagers."
        );

        travellerCapMinPerVillage = cfg.getInt(
                "TravellerCapMinPerVillage", "Soft spawn — Travellers",
                2, 0, 64,
                "Minimum travellers contributed by each village to the dynamic caps."
        );

        travellerCapMaxPerVillage = cfg.getInt(
                "TravellerCapMaxPerVillage", "Soft spawn — Travellers",
                8, 0, 64,
                "Maximum travellers contributed by each village to the dynamic caps."
        );

        travellerCapPerLoneBuilding = cfg.getInt(
                "TravellerCapPerLoneBuilding", "Soft spawn — Travellers",
                1, 0, 16,
                "How many travellers each lone building contributes to the dynamic caps."
        );

        travellerCapGlobalMin = cfg.getInt(
                "TravellerCapGlobalMin", "Soft spawn — Travellers",
                0, 0, 128,
                "Global minimum for the dynamic world traveller target."
        );

        travellerCapGlobalMax = cfg.getInt(
                "TravellerCapGlobalMax", "Soft spawn — Travellers",
                64, 0, 256,
                "Global maximum for the dynamic world traveller target."
        );

        travellerRegionalRadius = cfg.getInt(
                "TravellerRegionalRadius", "Soft spawn — Travellers",
                1000, 128, 8000,
                "Horizontal radius (in blocks) used when computing regional traveller caps around a player."
        );

        // ===== Raider caps =====
        raiderCapPerVillager = cfg.getFloat(
                "RaiderCapPerVillager", "Soft spawn — Raiders",
                1.0f / 5.0f, 0.01f, 10.0f,
                "Dynamic raider cap: raiders per villager. 1/5 = ~1 raider per 5 villagers."
        );

        raiderCapMinPerVillage = cfg.getInt(
                "RaiderCapMinPerVillage", "Soft spawn — Raiders",
                1, 0, 32,
                "Minimum raiders contributed by each village to the regional raider caps."
        );

        raiderCapMaxPerVillage = cfg.getInt(
                "RaiderCapMaxPerVillage", "Soft spawn — Raiders",
                4, 0, 32,
                "Maximum raiders contributed by each village to the regional raider caps."
        );

        raiderCapPerLoneBuilding = cfg.getInt(
                "RaiderCapPerLoneBuilding", "Soft spawn — Raiders",
                0, 0, 16,
                "How many raiders each lone building contributes to the regional raider caps."
        );

        raiderRegionalRadius = cfg.getInt(
                "RaiderRegionalRadius", "Soft spawn — Raiders",
                1000, 128, 8000,
                "Horizontal radius (in blocks) used when computing regional raider caps around a player."
        );

        // Distance from Town Hall / village center inside which raiders never soft-spawn
        raiderMinVillageDistance = cfg.getInt(
                "RaiderMinVillageDistance", "Soft spawn — Raiders",
                80, 16, 512,
                "Minimum distance from Millénaire village centers where raiders are allowed to soft-spawn."
        );

        raiderBaseSpawnChance = cfg.getFloat(
                "RaiderBaseSpawnChance", "Soft spawn — Raiders",
                0.15f, 0.0f, 1.0f,
                "Base chance per raider soft-spawn attempt before multiplying by Millénaire density."
        );
// Guard: ensure max >= min for traveller ring
        if (travellerSpawnMaxDist < travellerSpawnMinDist) {
            travellerSpawnMaxDist = travellerSpawnMinDist;
        }

// === SOFT SPAWN — RAIDERS ===
        raiderTargetLoaded = cfg.getInt(
                "raiderTargetLoaded", "Soft spawn — Raiders",
                raiderTargetLoaded, 0, 200,
                "Soft target for concurrently loaded Raiders across all dimensions."
        );

        raiderTopUpEveryTicks = cfg.getInt(
                "raiderTopUpEveryTicks", "Soft spawn — Raiders",
                raiderTopUpEveryTicks, 1, 20 * 60 * 30,
                "How often to attempt topping up Raiders (ticks)."
        );

        raiderAttemptsPerPlayer = cfg.getInt(
                "raiderAttemptsPerPlayer", "Soft spawn — Raiders",
                raiderAttemptsPerPlayer, 0, 10,
                "How many soft-spawn attempts to run per player each cycle."
        );

        raiderSoftSpawnChancePerAttempt = cfg.getFloat(
                "raiderSoftSpawnChancePerAttempt", "Soft spawn — Raiders",
                raiderSoftSpawnChancePerAttempt, 0.0f, 1.0f,
                "Chance (0–1) that a given soft-spawn attempt will actually try to place Raiders."
        );

// === RAIDER PLACEMENT RING ===
        raiderSpawnMinDist = cfg.getInt(
                "raiderSpawnMinDist", "Soft spawn — Raiders",
                raiderSpawnMinDist, 8, 1024,
                "Minimum spawn distance from the player for Raiders (blocks)."
        );

        raiderSpawnMaxDist = cfg.getInt(
                "raiderSpawnMaxDist", "Soft spawn — Raiders",
                raiderSpawnMaxDist, 16, 2048,
                "Maximum spawn distance from the player for Raiders (blocks). Must be >= min."
        );

        enableTravellerSoftSpawns = cfg.getBoolean(
                "EnableTravellerSoftSpawns", "Despawn & performance",
                true,   // default: current behavior
                "If true, the Soft Spawner periodically tops up travellers near players. "
                        + "Set false to disable soft spawning and rely entirely on vanilla chunk spawning."
        );

        enableRaiderSoftSpawns = cfg.getBoolean(
                "EnableRaiderSoftSpawns", "Despawn & performance",
                true,   // default: current behavior
                "If true, the Soft Spawner periodically tops up raiders near players. "
                        + "Set false to disable soft spawning and rely entirely on vanilla chunk spawning."
        );
// === CAMPS ===
        campsEnabled = cfg.getBoolean(
                "campsEnabled",
                "Camp feature",
                campsEnabled,   // default true
                "If true, travellers will sometimes take a break and make camps with firepit block"
        );

        CAMP_RADIUS = cfg.getInt(
                "campRadius",
                "Camp feature",
                CAMP_RADIUS, 1, 32,
                "Radius (in blocks) around the campfire where travellers will gather or idle."
        );


// === CAMPS: SEARCH / JOIN BEHAVIOR ===
        SEEK_RADIUS = cfg.getInt(
                "campSeekRadius",
                "Camp feature",
                MAX_JOINERS_PER_CAMP, 1, 128,
                "How far (in blocks) travellers look for nearby camps to join."
        );

        MAX_JOINERS_PER_CAMP = cfg.getInt(
                "campMaxJoinersPerCamp",
                "Camp feature",
                MAX_JOINERS_PER_CAMP, 1, 32,
                "Maximum number of travellers that will gather around a single camp."
        );

// === CAMPS: FIRECAMP IDLE TIMERS ===
        CAMP_WANDER_MIN_DELAY = cfg.getInt(
                "campWanderMinDelay",
                "Camp feature",
                CAMP_WANDER_MIN_DELAY, 1, 20 * 60 * 10,
                "Minimum ticks between wander moves while at camp (20 ticks = 1s)."
        );

        CAMP_WANDER_MAX_DELAY = cfg.getInt(
                "campWanderMaxDelay",
                "Camp feature",
                CAMP_WANDER_MAX_DELAY, 1, 20 * 60 * 10,
                "Maximum ticks between wander moves while at camp (must be >= min)."
        );

        CAMP_LOOK_MIN_DELAY = cfg.getInt(
                "campLookMinDelay",
                "Camp feature",
                CAMP_LOOK_MIN_DELAY, 1, 20 * 60 * 10,
                "Minimum ticks between 'look around' actions while at camp."
        );

        CAMP_LOOK_MAX_DELAY = cfg.getInt(
                "campLookMaxDelay",
                "Camp feature",
                CAMP_LOOK_MAX_DELAY, 1, 20 * 60 * 10,
                "Maximum ticks between 'look around' actions while at camp (must be >= min)."
        );

// Guards
        if (CAMP_WANDER_MAX_DELAY < CAMP_WANDER_MIN_DELAY) {
            CAMP_WANDER_MAX_DELAY = CAMP_WANDER_MIN_DELAY;
        }
        if (CAMP_LOOK_MAX_DELAY < CAMP_LOOK_MIN_DELAY) {
            CAMP_LOOK_MAX_DELAY = CAMP_LOOK_MIN_DELAY;
        }


        // === TRAVEL TORCH / TRAVEL BEHAVIOR ===
        travelTorchEnabled = cfg.getBoolean(
                "travelTorchEnabled",
                "Travel behavior",
                travelTorchEnabled,   // default false
                "If true, travellers can use the travel torch behavior (carrying light while travelling)."
        );

        travelTorchCooldownTicks = cfg.getInt(
                "travelTorchCooldownTicks",
                "Travel behavior",
                travelTorchCooldownTicks, 0, 20 * 60 * 60,
                "Cooldown in ticks after unequipping the travel torch before it can be used again (20 ticks = 1s)."
        );

        travelTorchNearbyRadius = cfg.getFloat(
                "travelTorchNearbyRadius",
                "Travel behavior",
                (float) travelTorchNearbyRadius, 0.0F, 128.0F,
                "Radius (in blocks) within which a nearby light source counts as 'someone else has light'."
        );

        excludedVillagerTagsRaw = cfg.getStringList(
                "ExcludedVillagerTags",
                "Millenaire culture filters",
                excludedVillagerTagsRaw,
                "Any villager txt with tag=... matching one of these values will be excluded "
                        + "from the traveller appearance pool. Example: [\"child\", \"bandit\"]"
        );

        excludedVillagerFilesRaw = cfg.getStringList(
                "ExcludedVillagerFiles",
                "Millenaire culture filters",
                excludedVillagerFilesRaw,
                "Villager txt filenames (without path, with or without .txt) to exclude from the "
                        + "traveller appearance pool. Example: [\"boy\", \"girl\", \"wife_laundry\"]"
        );
        excludedVillagerTagsRaw = cfg.getStringList(
                "ExcludedVillagerTags",
                "Traveller villager filters",
                excludedVillagerTagsRaw,
                "Any villager txt with tag=... matching one of these values will be excluded "
                        + "from the traveller appearance pool. Example: [\"child\", \"bandit\"]"
        );

        excludedVillagerFilesRaw = cfg.getStringList(
                "ExcludedVillagerFiles",
                "Traveller villager filters",
                excludedVillagerFilesRaw,
                "Villager txt filenames (without path, with or without .txt) to exclude from the "
                        + "traveller appearance pool. Example: [\"boy\", \"girl\", \"wife_laundry\"]"
        );

        includedRaiderVillagerTagsRaw = cfg.getStringList(
                "IncludedRaiderVillagerTags",
                "Raider villager filters",
                includedRaiderVillagerTagsRaw,
                "If non-empty, only villager txts with tag=... matching one of these values can be used " +
                        "for the *raider* appearance pool. Example: [\"bandit\", \"guard\"]. " +
                        "Empty list = no tag restriction."
        );

        includedRaiderVillagerFilesRaw = cfg.getStringList(
                "IncludedRaiderVillagerFiles",
                "Raider villager filters",
                includedRaiderVillagerFilesRaw,
                "If non-empty, only villager txt filenames (without path, with or without .txt) matching " +
                        "one of these values can be used for the raider appearance pool. Example: [\"bandit\", \"guard\"] " +
                        "Empty list = no filename restriction."
        );

        // Rebuild sets (normalize to lower-case)
        EXCLUDED_VILLAGER_TAGS.clear();
        for (String t : excludedVillagerTagsRaw) {
            if (t != null) {
                String s = t.trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) EXCLUDED_VILLAGER_TAGS.add(s);
            }
        }

        EXCLUDED_VILLAGER_FILES.clear();
        for (String f : excludedVillagerFilesRaw) {
            if (f != null) {
                String s = f.trim().toLowerCase(java.util.Locale.ROOT);
                if (s.endsWith(".txt")) s = s.substring(0, s.length() - 4);
                if (!s.isEmpty()) EXCLUDED_VILLAGER_FILES.add(s);
            }
        }

        EXCLUDED_VILLAGER_TAGS.clear();
        for (String t : excludedVillagerTagsRaw) {
            if (t != null) {
                String s = t.trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) EXCLUDED_VILLAGER_TAGS.add(s);
            }
        }

        EXCLUDED_VILLAGER_FILES.clear();
        for (String f : excludedVillagerFilesRaw) {
            if (f != null) {
                String s = f.trim().toLowerCase(java.util.Locale.ROOT);
                if (s.endsWith(".txt")) s = s.substring(0, s.length() - 4);
                if (!s.isEmpty()) EXCLUDED_VILLAGER_FILES.add(s);
            }
        }

        INCLUDED_RAIDER_VILLAGER_TAGS.clear();
        for (String t : includedRaiderVillagerTagsRaw) {
            if (t != null) {
                String s = t.trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) INCLUDED_RAIDER_VILLAGER_TAGS.add(s);
            }
        }

        INCLUDED_RAIDER_VILLAGER_FILES.clear();
        for (String f : includedRaiderVillagerFilesRaw) {
            if (f != null) {
                String s = f.trim().toLowerCase(java.util.Locale.ROOT);
                if (s.endsWith(".txt")) s = s.substring(0, s.length() - 4);
                if (!s.isEmpty()) INCLUDED_RAIDER_VILLAGER_FILES.add(s);
            }
        }
        // ---------- Fishing ----------
        fishWaterScanRadius = cfg.getInt(
                "FishWaterScanRadius", "Fishing",
                6, 1, 32,
                "Radius (in blocks) to search for suitable water around the traveller when fishing."
        );

        fishMinTimeTicks = cfg.getInt(
                "FishMinTimeTicks", "Fishing",
                15 * 20, 1, 10 * 60 * 20,
                "Minimum time in ticks before a fish can bite."
        );

        fishMaxTimeTicks = cfg.getInt(
                "FishMaxTimeTicks", "Fishing",
                40 * 20, 1, 10 * 60 * 20,
                "Maximum time in ticks before a fish must bite (if any)."
        );

        fishReelEveryTicks = cfg.getInt(
                "FishReelEveryTicks", "Fishing",
                45, 1, 20 * 60,
                "Every this many ticks, travellers will play their 'reel' animation while fishing."
        );

        fishCastWindupTicks = cfg.getInt(
                "FishCastWindupTicks", "Fishing",
                15, 1, 20 * 10,
                "Wind-up time in ticks before the fishing cast actually happens."
        );

        fishCooldownTicks = cfg.getInt(
                "FishCooldownTicks", "Fishing",
                5 * 60 * 20, 0, 60 * 60 * 20,
                "Cooldown in ticks before a traveller will consider fishing again."
        );

        fishStartChancePerTick = cfg.getFloat(
                "FishStartChancePerTick", "Fishing",
                0.008f, 0.0f, 1.0f,
                "Per-tick chance (0–1) that a traveller decides to start fishing " +
                        "when all other conditions are met."
        );
        
        

// Guard: ensure max >= min for raider ring
        if (raiderSpawnMaxDist < raiderSpawnMinDist) {
            raiderSpawnMaxDist = raiderSpawnMinDist;
        }

        // Rebuild blacklist for the defaults we just wrote
        TRAVELLER_AGGRO_BLACKLIST.reload(travellerAggroBlacklistIds, travellerAggroBlacklistClasses);
        RAIDER_AGGRO_BLACKLIST.reload(raiderAggroBlacklistIds, raiderAggroBlacklistClasses);
        RAIDER_ATTACK_LIST.reload(raiderAttackIdRules, raiderAttackClassRules); // (unchanged)
        if (rangedMaxDist < attackTriggerRange) {
            rangedMaxDist = attackTriggerRange;
        }
        cfg.save();
    }

    public static boolean isVillagerFileIncludedForRaiders(java.io.File file) {
        if (file == null) return false; // null never included

        // If there are no include rules at all, allow all files (no restriction)
        if (INCLUDED_RAIDER_VILLAGER_FILES.isEmpty()) {
            return true;
        }

        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".txt")) {
            name = name.substring(0, name.length() - 4);
        }
        return INCLUDED_RAIDER_VILLAGER_FILES.contains(name);
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

    public static boolean isVillagerFileExcludedForTravellers(java.io.File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".txt")) {
            name = name.substring(0, name.length() - 4);
        }
        return EXCLUDED_VILLAGER_FILES.contains(name);
    }

    public static boolean isVillagerFileExcludedForRaiders(java.io.File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".txt")) {
            name = name.substring(0, name.length() - 4);
        }
        return EXCLUDED_RAIDER_VILLAGER_FILES.contains(name);
    }
    /**
     * Delete old config at /config/travellers.cfg (legacy location), if present.
     */
    private static void deleteLegacyRootConfig() {
        try {
            if (CONFIG_FILE == null) return;

            // CONFIG_FILE is /config/travellers/travellers.cfg
            File travellersDir = CONFIG_FILE.getParentFile();      // /config/travellers
            if (travellersDir == null) return;

            File configRoot = travellersDir.getParentFile();       // /config
            if (configRoot == null) return;

            File legacy = new File(configRoot, "travellers.cfg");  // /config/travellers.cfg

            if (!legacy.exists()) {
                return; // nothing to delete
            }

            if (legacy.delete()) {
                System.out.println("[ModConfig] Deleted legacy config: " + legacy.getAbsolutePath());
            } else {
                System.err.println("[ModConfig] Failed to delete legacy config: " + legacy.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("[ModConfig] Error while deleting legacy /config/travellers.cfg: " + e.getMessage());
        }
    }

}