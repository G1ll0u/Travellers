package com.jubitus.travellers;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.utils.mobs.lists.AttackList;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSyncServerCosts;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.io.IOException;


public class TravellersModConfig {
    // --- Worker hire cost (deniers) ---
// Default: 2 argent = 2*64 deniers
    public static int workerHireCostDeniers = 10 * 64;

    // CLIENT ONLY: last value received from server for GUI display.
// -1 = unknown (not received yet)
    public static int workerHireCostDeniers_serverCache = -1;


    // Worker "Go..." order: how close is considered "arrived"
    public static int workerGoArrivalRadius = 32; // blocks (tweak)

    // Worker idle anchor radius when follow is OFF (used after Go...)
    public static double workerIdleAnchorRadius = 2.0D; // blocks (tweak)

    public static int recruitMinVillageDistance = 300; // default, tweak

    // process only N villages per maintenance pass
    public static final int MERCHANT_VILLAGES_PER_PASS = 0;
    public static final int WORKER_VILLAGES_PER_PASS = 1;
    public static final java.util.Set<String> EXCLUDED_RAIDER_VILLAGER_TAGS = new java.util.HashSet<>();
    public static final java.util.Set<String> EXCLUDED_RAIDER_VILLAGER_FILES = new java.util.HashSet<>();
    // NEW: Raider include filters (whitelist-style)
    public static final java.util.Set<String> INCLUDED_RAIDER_VILLAGER_TAGS = new java.util.HashSet<>();
    public static final java.util.Set<String> INCLUDED_RAIDER_VILLAGER_FILES = new java.util.HashSet<>();
    // NEW: worker include filters (whitelist-style)
    public static final java.util.Set<String> INCLUDED_WORKER_VILLAGER_TAGS = new java.util.HashSet<>();
    public static final java.util.Set<String> INCLUDED_WORKER_VILLAGER_FILES = new java.util.HashSet<>();
    // NEW: merchant include filters (whitelist-style)
    public static final java.util.Set<String> INCLUDED_MERCHANT_VILLAGER_TAGS = new java.util.HashSet<>();
    public static final java.util.Set<String> INCLUDED_MERCHANT_VILLAGER_FILES = new java.util.HashSet<>();
    public static final int DOG_SCAN_COOLDOWN_TICKS = 5 * 20;
    public static final int CAMP_SWING_EVERY = 80;         // ~4s baseline between “cooking” swings
    public static final int SPAWN_AMBIENT_DELAY_TICKS = 12 * 20;   // ~12s after spawn
    public static final AttackList RAIDER_ATTACK_LIST = new AttackList();
    public static final AttackList TRAVELLER_ATTACK_LIST = new AttackList();
    public static final TravellerBlacklist TRAVELLER_BLACKLIST = new TravellerBlacklist();
    // NEW: two separate aggro blacklists
    public static final TravellerBlacklist TRAVELLER_AGGRO_BLACKLIST = new TravellerBlacklist();
    public static final TravellerBlacklist RAIDER_AGGRO_BLACKLIST = new TravellerBlacklist();
    public static final java.util.Set<String> EXCLUDED_VILLAGER_TAGS = new java.util.HashSet<>();
    public static final java.util.Set<String> EXCLUDED_VILLAGER_FILES = new java.util.HashSet<>();
    public static final int CAMP_JOIN_COOLDOWN_TICKS = 20 * 60; // 60s; tweak as you like
    public static final int THREAT_FOR_SEE_ATTACK = 5;// when we "observe" hostiles hurting allies/players
    public static final int CAMP_RANDOM_COOLDOWN = 6 * 60 * 20; // ~6 min between pauses
    // --- Grace hits vs players ---
    public static final int PLAYER_FREE_HITS = 3;
    public static final int PLAYER_FORGIVE_WINDOW_TICKS = 6 * 20; // reset window per attacker
    public static final double CHORUS_RADIUS = 32.0;
    // --- Controlled Village ---
    public static boolean controlledVillageFeatureEnabled = true;
    public static boolean requireControlledVillageToHire = true;
    public static int barracksSlotsPerBuilding = 5;
    public static int workerBarracksRadius = 10;
    public static boolean workerRespawnInBarracks = false;
    public static int workerRespawnMorningTime = 1000; // Minecraft day-ticks (0=midnight, 1000=sunrise)

    // --- Teenager → Worker ---
    public static boolean teenagerToWorkerEnabled = false;

    // --- Choir ---
    public static boolean choirEnabled = true;
    public static double CHOIR_GROUP_RADIUS = 12.0;
    public static double CHOIR_BREAK_RADIUS = 25.0;
    public static float CHOIR_AUTO_CHANCE = 0.05f;
    public static int CHOIR_AUTO_COOLDOWN_TICKS = 12000;
    public static int CHOIR_SESSION_DURATION_TICKS = 3 * 20 * 60;
    public static int CHOIR_SPREAD_GRACE_CHECKS = 3;
    public static int CHOIR_TICK_INTERVAL = 100;
    // Volume multiplier for choir sounds. Higher = audible from farther away (1.0 ≈ 16 blocks, 3.0 ≈ 48 blocks).
    public static float choirSoundVolumeMultiplier = 3.0f;
    public static final int TRAVEL_TORCH_COOLDOWN_TICKS_DEFAULT = 20 * 10; // fallback if config 0
    public static final double TRAVEL_TORCH_NEARBY_RADIUS_DEFAULT = 10.0D;
    public static final int THREAT_DECAY_PER_SEC = 2; // decay rate
    public static final int THREAT_DECAY_INTERVAL_TICKS = 20; // 1s
    public static final int THREAT_TTL_TICKS = 20 * 30; // forget after 30s of not seeing it
    // --- Auto-eat state ---
    public static final double SWIM_MULT = 3; // ~+130% in water (feels snappy, stays stable)
    public static final double CORRIDOR_WIDTH = 64.0; // tune 32–64
    private static final String CONFIG_VERSION_KEY = "configVersion";
    private static final String CURRENT_VERSION = "5.2"; // yes I always change ver when testing, that's stupid
    // Recruit mission tuning
    public static int recruitMaxVillageRadius = 4000;        // max distance from player
    public static int recruitVillagesMin = 1;
    public static int recruitVillagesMax = 20;

    public static int recruitFeeFlat_serverCache = -1;


    public static double recruitFeePerBlock_serverCache = Double.NaN;


    public static int recruitFeePerVillage_serverCache = -1;



    // ---- worker population → workers per village ----
    public static int recruitSpawnMin = 2;                  // workers spawned per visited village
    public static int recruitSpawnMax = 4;
    public static int recruitLeaderAssignRadius = 12000;        // radius to assign/clear escort leader
    // Fee formula tuning (deniers)
    public static int recruitFeeFlat = 128;                 // 2 argent if 64 deniers/argent
    public static double recruitFeePerBlock = 0.05;         // per block of route (2D)
    public static int recruitFeePerVillage = 4 * 64;            // +4 argent per village
    public static int workerGlobalAssignedCap = 4096; // example: 8–20 feels good
    public static boolean travellerOffscreenHopEnabled = true;
    public static int travellerOffscreenHopIntervalTicks = 20 * 7; // 6s, every 6s, do a hop
    public static int travellerOffscreenHopStepBlocks = 24;        // hop distance in blocks
    // optional tuning knobs if you like:
    public static double followerBoatConvoySpeed = 4.0D;      // boats vs normal follow speed
    // Global damage multiplier applied to ALL damage travellers take.
// Does NOT apply to damage caused by players.
// 1.0 = normal, 0.5 = half damage, 0 = immune to non-player sources.
    public static float travellerDamageScale = 1.0F;
    // 1) Off-screen aggro immunity
    public static boolean travellerNoAggroOffscreen = true;
    // 2) Off-screen damage scale (0 = immune, 0.2 = 80% reduction, 1 = normal)
    public static float travellerOffscreenDamageScale = 0.2F;
    // 3) Simple travel mode when off-screen
    public static boolean travellerSimpleOffscreenTravel = false;
    // Optional threshold so simple travel only kicks in when fairly far from any player
    public static double travellerSimpleOffscreenMinPlayerDist = 64.0D;
    public static boolean workerAutoLootMobs = true;
    public static int merchantVillageMinDistance = 1200; // blocks between merchant villages
    public static int workerVillageMinDistance = 1200; // blocks between worker villages
    public static int merchantLoadedCap = 16;
    public static int workerLoadedCap = 2048;
    public static int workerMinPop = 16;
    public static int workerPopPerWorker = 10;
    @Config.Comment("Maximum number of travellers workers per village")
    public static int workerMaxPerVillage = 2;
    @Config.Comment("How often (ticks) we re-check villages for worker counts")
    public static int workerRecheckEveryTicks = 600;
    public static boolean workerNotifyOnVillageTrade = true;
    // Base travel cost per used cargo slot for a trip of workerTravelDistanceBase blocks
    public static double workerTravelBaseCostPerSlot = 10.0D;
    // “Reference” distance. Default Millénaire village spacing ≈ 800, as you said
    public static double workerTravelDistanceBase = 400.0D;
    // Optional: flat extra deniers per trip, regardless of distance/slots
    public static int workerTravelFlatBaseCost = 50;
    // Optional: multiplier on top (for global tuning)
    public static double workerTravelCostMultiplier = 1.0D;
    public static int workerVillageDetectRadius = 4000;
    public static int workerStepBlocks = 24;
    public static int workerChunkRadius = 1; // whatever you want
    public static double workerMaxHealth = 60.0D;
    public static double workerFollowRange = 64.0D;
    // --- Traveller stats ---
    public static float travellerMaxHealth = 25.0F;
    public static float travellerFollowRange = 64.0F;
    // --- Merchant stats ---
    public static float merchantMaxHealth = 60.0F;
    public static float merchantFollowRange = 32.0F;
    // Travel AI
    public static int merchantStepBlocks = 16;
    // Merchant spawn logic
    public static int merchantMinPop = 8;
    public static int merchantPopPerMerchant = 10;
    public static int merchantMaxPerVillage = 2;
    public static int merchantRecheckEveryTicks = 400;
    public static int merchantChunkRadius = 1;          // default radius per merchant
    public static int merchantMaxForcedChunks = 256;    // hard cap per world
    public static int merchantMaxChunkloadingMerchants = 1024; // if many merchants, only first N get chunkloading
    public static boolean merchantChunkloadingEnabled = true;
    // How big a slice of the total required amount the merchant tries to supply.
// 0.0833 ≈ 1/12; 0.1 = 10%; 0.25 = 25%...
    public static double merchantGiveFraction = 1.0D / 100.0D;
    // Minimum donation for "bulk" items (big stacks, e.g. planks).
    public static int merchantMinBulkAmount = 1;
    // Minimum donation for "small stack" items (banners, tools, etc.)
    public static int merchantMinSmallAmount = 1;
    // How many *different* items a merchant is allowed to donate during one visit.
    public static int merchantMaxDistinctItems = 1;
    // --- Workers ---
    public static String[] includedWorkerVillagerTagsRaw = new String[]{
            "helpInAttacks",
            "heavydrinker",
            "archer",
            "raider"
    };
    public static String[] includedWorkerVillagerFilesRaw = new String[]{

    };
    // --- Merchants ---
    public static String[] includedMerchantVillagerTagsRaw = new String[]{
            "visitor"
    };
    public static String[] includedMerchantVillagerFilesRaw = new String[]{
            // e.g. "nor_shopkeeper"
    };
    public static boolean debug = false;
    public static boolean debugFollowTeleport = false;
    public static boolean enableTravellerOpenFenceGates = true;
    public static boolean enableTravellerOpenDoors = true;
    public static boolean enableMobFarmAvoidance = true;
    public static int mobFarmCheckRadius = 6;
    public static int mobFarmEntityThreshold = 15;
    public static int mobFarmFenceRadius = 4;
    public static int mobFarmFenceThreshold = 12;
    public static int followMaxFollowersPerLeader = 4; // 0 or negative = unlimited
    // Raw config arrays for raiders includes (default: empty = "no restriction")
    public static String[] includedRaiderVillagerTagsRaw = new String[]{
            "hostile"
    };
    public static String[] includedRaiderVillagerFilesRaw = new String[]{
    };
    // How far mill/lonebuildings influence travellers spawns (in blocks)
    public static int travellerVillageInfluenceRadius = 1000;  // e.g. 400 blocks
    public static int travellerLoneInfluenceRadius = 600;  // e.g. 250 blocks
    // How strong each center type is
    public static double travellerVillageWeight = 0.3;  // 1.0 = full strength
    public static double travellerLoneWeight = 0.3;  // lonebuildings weaker
    // Shape of falloff: 1.0 = linear, >1 = steeper near edge, <1 = flatter
    public static double travellerFalloffPower = 1.0;
    public static int travellerMaxPositionChecksPerAttempt = 4;
    public static int raiderMaxPositionChecksPerAttempt = 4;
    // Optional: cap on total density when multiple mill overlap
    public static double travellerMaxDensity = 1.4;
    public static int CAMP_RADIUS = 8; // or expose a public constant on EntityTraveller
    // === CAMP / FIREPIT BEHAVIOR ===
    public static int SEEK_RADIUS = 12; // how far to look for camps
    public static int MAX_JOINERS_PER_CAMP = 12;
    // Firecamp idle behavior (wander / look delays in ticks)
    public static int CAMP_WANDER_MIN_DELAY = 40;   // 2s  (20 tps)
    public static int CAMP_WANDER_MAX_DELAY = 140;  // 7s
    public static int CAMP_LOOK_MIN_DELAY = 30;   // 1.5s
    public static int CAMP_LOOK_MAX_DELAY = 110;  // 5.5s
    public static int loadedRaiderCap;
    public static int serverViewDistanceFallbackChunks = 8;
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
    public static String[] travellerAggroBlacklistClasses = new String[0];  // e.g., {"net.minecraft.entity.monster.EntityGhast"}
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
    public static float rangedBaseDamage;
    public static float rangedDamageMultiplier;
    public static float travellerPlayerDamageMultiplier;
    // --- FOLLOWER AI TUNING ---
    public static double followDefaultRange;
    public static double followDesiredDist;
    public static double followMaxDist;
    public static int followRepushEvery;
    public static int followDurationMin;
    public static int followDurationMax;
    public static float followAngleAlignDeg;
    public static float followStartChance = 0.75f;
    public static double movementSpeed;
    public static int followRange;
    public static double attackDamage;
    public static double attackTriggerRange;
    // Fraction of max HP below which entities enter defensive mode (stop seeking new targets)
    public static float lowHealthCombatFrac = 0.20f;
    // Engagement radius (blocks) for anchor-mode workers (followOrderActive=false)
    public static double anchorModeEngageRadius = 12.0D;
    // Engagement radius (blocks) for any entity in low-health defensive mode
    public static double lowHealthEngageRadius = 5.0D;
    // --- Combat item realism ---
    public static boolean weaponDurabilityEnabled = true;
    public static boolean armorDurabilityEnabled = true;
    public static boolean weaponAttackSpeedEnabled = true;
    public static int baseAttackCooldownTicks = 20;
    public static int minAttackCooldownTicks = 6;
    public static int maxAttackCooldownTicks = 30;
    public static float attackCooldownMultiplier = 2.0f;
    public static boolean travellerSpeaks;
    public static boolean travellerAmbient = true;
    public static int travellerAmbientMinDelay;
    public static int travellerAmbientMaxDelay;
    public static float travellerAmbientVolume;
    public static float travellerChoirVolume;
    public static float travellerTalkVolume;
    public static float travellerHurtVolume;
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
    public static int travellerTargetLoaded = 48;       // total travellers you’d like loaded (across all dims)
    public static int travellerTopUpEveryTicks = 20 * 15;    // how often to try
    public static int travellerAttemptsPerPlayer = 1;   // attempts per player each cycle
    public static int travellerSpawnMinDist = 64;       // from player
    public static int travellerSpawnMaxDist = 128;       // from player
    public static int travellerSpawnLightMin = 8;       // same as your getCanSpawnHere
    public static boolean despawnWhenFarFromPlayers = true;
    public static int farExtraChunks = 2;           // buffer beyond view radius (in chunks)
    public static int farGraceTicks = 15 * 20;      // must be far for ~15s to fade
    public static int farMinDistanceBlocks = 128;   // hard floor even if view radius is tiny
    // Raiders — soft-spawn target & pacing
    public static int raiderTargetLoaded = 16;          // keep far lower than travellers
    public static int raiderTopUpEveryTicks = 400;     // try every ~10s
    public static int raiderAttemptsPerPlayer = 2;     // 1 try per player each cycle
    public static float raiderSoftSpawnChancePerAttempt = 1.0F; // only 25% of tries attempt
    // Where to place them relative to players (reuse/adjust ring)
    public static int raiderSpawnMinDist = 96;
    public static int raiderSpawnMaxDist = 320;
    // Darkness rule (hostile vibe)
    public static boolean enableTravellerSoftSpawns;
    public static boolean enableRaiderSoftSpawns;
    public static boolean campsEnabled = true;
    public static boolean travelTorchEnabled = false; // default on
    public static int travelTorchCooldownTicks = 20 * 10; // e.g. 10s after unequip
    public static double travelTorchNearbyRadius = 20.0D; // “someone else has light” radius
    public static boolean spawnTravellerWithDog = false;
    public static double travellerDogSpawnChance = 0.0085; // 3%
    public static String[] excludedVillagerTagsRaw = new String[]{"child", "chief", "hostile"};
    public static String[] excludedVillagerFilesRaw = new String[]{"alchemist", "alchemistapprentice", "alchemistassistant"};
    public static int fishWaterScanRadius = 6;          // blocks
    public static int fishMinTimeTicks = 15 * 20;     // 8s
    public static int fishMaxTimeTicks = 40 * 20;    // 16s
    public static int fishReelEveryTicks = 45;         // ticks
    public static int fishCastWindupTicks = 15;         // ticks
    public static int fishCooldownTicks = 5 * 60 * 20; // 3 minutes
    public static float fishStartChancePerTick = 0.002f;
    public static double fishApproachSpeed = 0.45D;
    public static int stepBlocks = 24;
    // Minimum distance (in blocks) raiders must spawn away from any village TH
    public static int raiderMinVillageDistance = 160;
    public static boolean showTravellerNames = false;
    // Base chance per anchor attempt, before density (0..1)
    public static double travellerBaseSpawnChance = 0.6;
    public static float campStartChancePerTick = 0.0008f;
    // --- Traveller population-based spawn weighting ---
    public static boolean travellerPopulationWeightEnabled = true;
    /**
     * Horizontal radius (blocks) to look for the "host" village for a spawn pos
     */
    public static int travellerPopulationWeightRadius =700;
    /**
     * Extra spawn weight per villager (0.02 => +2% per villager)
     */
    public static double travellerPopulationWeightPerVillager = 0.05D;
    /**
     * Max overall multiplier from population (e.g. 3.0 => up to 3×)
     */
    public static double travellerPopulationWeightMaxMultiplier = 1.5D;
    /**
     * Baseline spawn density when no village/lonebuilding influence (0 = none)
     */
    public static double travellerOutsideVillageBaseDensity = 0.05D;
    // --- Traveller culture-weighted spawns ---
    public static boolean travellerCultureWeightedSpawn = true;
    /**
     * Radius to scan for village cultures when choosing spawn culture
     */
    public static int travellerCultureScanRadius = 1024;
    /**
     * If true, cultures with bigger population get more weight
     */
    public static boolean travellerCultureWeightUseVillagePopulation = true;
    // ---- Dynamic travellers caps (world + regional) ----
    public static float travellerCapPerVillager;        // 1/3 = 1 travellers per ~3 villagers
    public static int travellerCapMinPerVillage;
    public static int travellerCapMaxPerVillage;
    public static int travellerCapPerLoneBuilding;
    public static int travellerCapGlobalMin;
    public static int travellerCapGlobalMax;
    public static int travellerRegionalRadius = 600;
    // ---- Dynamic raiders caps (regional) ----
    public static float raiderCapPerVillager;           // 1/5 = 1 raiders per ~5 villagers
    public static int raiderCapMinPerVillage;
    public static int raiderCapMaxPerVillage;
    public static int raiderCapPerLoneBuilding;
    public static int raiderRegionalRadius = 600;
    // And a dedicated base spawn chance for raiders (instead of reusing travellers one)
    public static float raiderBaseSpawnChance;
    public static boolean requirePathForAggro = true;
    public static boolean requireLOSForMemoryTargets = true;
    public static boolean requireLOSForChainTargets = true;
    public static double travellerOffscreenViewRadius = 256;
    public static double travellerViewBoostBaseRadius = 128;
    public static double travellerViewBoostCombatBonus = 128;
    public static boolean allowTravellerChunkLoading = true;
    public static double returnFollowSwitchDist = 32;
    public static int chunkRecomputeIntervalTicks = 5;
    public static double followerRingMoveBaseRadius = 2.2D;
    public static double followerRingMoveSlotStep = 0.25;
    public static double raiderMaxHealth = 20.0F;
    static boolean enableTravellerEntity = true;
    private static String[] raiderAttackIdRules = new String[0];
    private static String[] raiderAttackClassRules = new String[0];

    private static String[] travellerAttackIdRules = new String[0];
    private static String[] travellerAttackClassRules = new String[0];
    private static Configuration CONFIG;      // <— keep it
    private static File CONFIG_FILE;          // <— keep it

    public static double travellerStepLimiterStrength = 1.5D;
    public static double travellerHurtLimiterStrength = 1.5D;

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
            syncToTravellersConfig();
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

    // fresh file
    private static void writeDefaults(Configuration cfg) {
        loadValues(cfg);
        syncToTravellersConfig();
        cfg.save();
    }

    // Copy TravellersModConfig fields → TravellersConfig so GUI always shows current values.
    private static void syncToTravellersConfig() {
        com.jubitus.travellers.config.TravellersConfig.GENERAL.enableTravellerEntity = enableTravellerEntity;
        com.jubitus.travellers.config.TravellersConfig.GENERAL.enableTravellerRaider = enableTravellerRaider;
        com.jubitus.travellers.config.TravellersConfig.GENERAL.debug = debug;
        com.jubitus.travellers.config.TravellersConfig.GENERAL.enableTravellerOpenFenceGates = enableTravellerOpenFenceGates;
        com.jubitus.travellers.config.TravellersConfig.GENERAL.enableTravellerOpenDoors = enableTravellerOpenDoors;

        com.jubitus.travellers.config.TravellersConfig.RENDERING.showTravellerNames = showTravellerNames;

        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerVillageInfluenceRadius = travellerVillageInfluenceRadius;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerLoneInfluenceRadius = travellerLoneInfluenceRadius;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerVillageWeight = (float) travellerVillageWeight;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerLoneWeight = (float) travellerLoneWeight;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerFalloffPower = (float) travellerFalloffPower;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerMaxDensity = (float) travellerMaxDensity;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerOutsideVillageBaseDensity = (float) travellerOutsideVillageBaseDensity;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.spawnTravellerWithDog = spawnTravellerWithDog;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerDogSpawnChance = (float) travellerDogSpawnChance;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerPopulationWeightEnabled = travellerPopulationWeightEnabled;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerPopulationWeightRadius = travellerPopulationWeightRadius;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerPopulationWeightPerVillager = (float) travellerPopulationWeightPerVillager;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerPopulationWeightMaxMultiplier = (float) travellerPopulationWeightMaxMultiplier;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerCultureWeightedSpawn = travellerCultureWeightedSpawn;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerCultureScanRadius = travellerCultureScanRadius;
        com.jubitus.travellers.config.TravellersConfig.SPAWN.travellerCultureWeightUseVillagePopulation = travellerCultureWeightUseVillagePopulation;

        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.loadedTravellerCap = loadedTravellerCap;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.minGroupSizeAtSpawn = MinGroupSizeAtSpawn;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.maxGroupSizeAtSpawn = MaxGroupSizeAtSpawn;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.enableTravellerSoftSpawns = enableTravellerSoftSpawns;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerTargetLoaded = travellerTargetLoaded;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerTopUpEveryTicks = travellerTopUpEveryTicks;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerAttemptsPerPlayer = travellerAttemptsPerPlayer;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerSpawnMinDist = travellerSpawnMinDist;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerSpawnMaxDist = travellerSpawnMaxDist;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerSpawnLightMin = travellerSpawnLightMin;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerMaxPositionChecksPerAttempt = travellerMaxPositionChecksPerAttempt;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerBaseSpawnChance = (float) travellerBaseSpawnChance;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerCapPerVillager = travellerCapPerVillager;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerCapMinPerVillage = travellerCapMinPerVillage;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerCapMaxPerVillage = travellerCapMaxPerVillage;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerCapPerLoneBuilding = travellerCapPerLoneBuilding;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerCapGlobalMin = travellerCapGlobalMin;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerCapGlobalMax = travellerCapGlobalMax;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerRegionalRadius = travellerRegionalRadius;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerLocalCap = travellerLocalCap;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerLocalCapRadius = travellerLocalCapRadius;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS.travellerSpawnPermille = travellerSpawnPermille;

        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.loadedRaiderCap = loadedRaiderCap;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderMinGroup = RaiderMinGroup;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderMaxGroup = RaiderMaxGroup;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.enableRaiderSoftSpawns = enableRaiderSoftSpawns;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderTargetLoaded = raiderTargetLoaded;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderTopUpEveryTicks = raiderTopUpEveryTicks;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderAttemptsPerPlayer = raiderAttemptsPerPlayer;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderSoftSpawnChancePerAttempt = raiderSoftSpawnChancePerAttempt;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderSpawnMinDist = raiderSpawnMinDist;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderSpawnMaxDist = raiderSpawnMaxDist;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderMinVillageDistance = raiderMinVillageDistance;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderBaseSpawnChance = raiderBaseSpawnChance;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderCapPerVillager = raiderCapPerVillager;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderCapMinPerVillage = raiderCapMinPerVillage;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderCapMaxPerVillage = raiderCapMaxPerVillage;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderCapPerLoneBuilding = raiderCapPerLoneBuilding;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderRegionalRadius = raiderRegionalRadius;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderLocalCap = raiderLocalCap;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderLocalCapRadius = raiderLocalCapRadius;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS.raiderMaxPositionChecksPerAttempt = raiderMaxPositionChecksPerAttempt;

        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.workerMinPop = workerMinPop;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.workerPopPerWorker = workerPopPerWorker;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.workerMaxPerVillage = workerMaxPerVillage;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.workerRecheckEveryTicks = workerRecheckEveryTicks;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.workerLoadedCap = workerLoadedCap;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.merchantLoadedCap = merchantLoadedCap;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.merchantMinPop = merchantMinPop;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.merchantPopPerMerchant = merchantPopPerMerchant;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.merchantMaxPerVillage = merchantMaxPerVillage;
        com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS.merchantRecheckEveryTicks = merchantRecheckEveryTicks;

        com.jubitus.travellers.config.TravellersConfig.DESPAWN.despawnNearUnloadedChunks = despawnNearUnloadedChunks;
        com.jubitus.travellers.config.TravellersConfig.DESPAWN.despawnWhenFarFromPlayers = despawnWhenFarFromPlayers;
        com.jubitus.travellers.config.TravellersConfig.DESPAWN.farExtraChunks = farExtraChunks;
        com.jubitus.travellers.config.TravellersConfig.DESPAWN.farGraceTicks = farGraceTicks;
        com.jubitus.travellers.config.TravellersConfig.DESPAWN.farMinDistanceBlocks = farMinDistanceBlocks;
        com.jubitus.travellers.config.TravellersConfig.DESPAWN.serverViewDistanceFallbackChunks = serverViewDistanceFallbackChunks;
        com.jubitus.travellers.config.TravellersConfig.DESPAWN.despawnProbeRadiusChunks = despawnProbeRadiusChunks;
        com.jubitus.travellers.config.TravellersConfig.DESPAWN.despawnEdgeGraceTicks = despawnEdgeGraceTicks;

        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.travellerMaxHealth = travellerMaxHealth;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.movementSpeed = (float) movementSpeed;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.movementSpeedWhileFollowing = (float) movementSpeedWhileFollowing;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.movementSpeedWhileTravel = (float) movementSpeedWhileTravel;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.movementSpeedWhileAttacking = (float) attackMovementSpeed;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.travellerFollowRange = travellerFollowRange;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.attackDamage = (float) attackDamage;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.travellerDamageScale = travellerDamageScale;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.travellerPlayerDamageMultiplier = travellerPlayerDamageMultiplier;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.raiderMaxHealth = (float) raiderMaxHealth;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.merchantMaxHealth = merchantMaxHealth;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.merchantFollowRange = merchantFollowRange;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.merchantStepBlocks = merchantStepBlocks;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.merchantChunkRadius = merchantChunkRadius;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.merchantMaxForcedChunks = merchantMaxForcedChunks;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.merchantMaxChunkloadingMerchants = merchantMaxChunkloadingMerchants;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.merchantChunkloadingEnabled = merchantChunkloadingEnabled;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.workerMaxHealth = workerMaxHealth;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.workerFollowRange = workerFollowRange;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.workerChunkRadius = workerChunkRadius;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.workerStepBlocks = workerStepBlocks;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.stepBlocks = stepBlocks;
        com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES.workerGlobalAssignedCap = workerGlobalAssignedCap;

        com.jubitus.travellers.config.TravellersConfig.COMBAT.callForHelp = callForHelp;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.helpRange = (float) helpRange;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.swordHideDelay = swordHideDelay;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.eatHealthThreshold = eatHealthThreshold;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.attackTriggerRange = (int) attackTriggerRange;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.lowHealthCombatFrac = lowHealthCombatFrac;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.anchorModeEngageRadius = (int) anchorModeEngageRadius;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.lowHealthEngageRadius = (int) lowHealthEngageRadius;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.requirePathForAggro = requirePathForAggro;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.requireLOSForMemoryTargets = requireLOSForMemoryTargets;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.requireLOSForChainTargets = requireLOSForChainTargets;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.travellerOffscreenViewRadius = travellerOffscreenViewRadius;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.travellerViewBoostBaseRadius = travellerViewBoostBaseRadius;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.travellerViewBoostCombatBonus = travellerViewBoostCombatBonus;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.rangedMinDist = (float) rangedMinDist;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.rangedMaxDist = (float) rangedMaxDist;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.rangedCooldown = rangedCooldown;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.rangedBaseDamage = rangedBaseDamage;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.rangedDamageMultiplier = rangedDamageMultiplier;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.weaponDurabilityEnabled = weaponDurabilityEnabled;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.armorDurabilityEnabled = armorDurabilityEnabled;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.weaponAttackSpeedEnabled = weaponAttackSpeedEnabled;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.baseAttackCooldownTicks = baseAttackCooldownTicks;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.minAttackCooldownTicks = minAttackCooldownTicks;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.maxAttackCooldownTicks = maxAttackCooldownTicks;
        com.jubitus.travellers.config.TravellersConfig.COMBAT.attackCooldownMultiplier = attackCooldownMultiplier;

        com.jubitus.travellers.config.TravellersConfig.PLAYER_AGGRO.playerCalmForgiveTicks = playerCalmForgiveTicks;
        com.jubitus.travellers.config.TravellersConfig.PLAYER_AGGRO.playerCalmNotifyRadius = (float) playerCalmNotifyRadius;
        com.jubitus.travellers.config.TravellersConfig.PLAYER_AGGRO.playerFleeForgiveTicks = playerFleeForgiveTicks;
        com.jubitus.travellers.config.TravellersConfig.PLAYER_AGGRO.playerFleeKeepDistance = (float) playerFleeKeepDistance;

        com.jubitus.travellers.config.TravellersConfig.FOLLOW.followDefaultRange = (float) followDefaultRange;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.followDurationMin = followDurationMin;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.followDurationMax = followDurationMax;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.followStartChance = followStartChance;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.followMaxFollowersPerLeader = followMaxFollowersPerLeader;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.followerBoatConvoySpeed = followerBoatConvoySpeed;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.followerRingMoveBaseRadius = followerRingMoveBaseRadius;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.followerRingMoveSlotStep = followerRingMoveSlotStep;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.returnFollowSwitchDist = returnFollowSwitchDist;
        com.jubitus.travellers.config.TravellersConfig.FOLLOW.chunkRecomputeIntervalTicks = chunkRecomputeIntervalTicks;

        com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.villageNear = villageNear;
        com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.villageRadius = villageRadius;
        com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.lonebuildingNear = lonebuildingNear;
        com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.villagePickRadius = villagePickRadius;
        com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.villageRoamMinDuration = villageRoamMinDuration;
        com.jubitus.travellers.config.TravellersConfig.DESTINATIONS.villageRoamMaxDuration = villageRoamMaxDuration;

        com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.travellerOffscreenHopEnabled = travellerOffscreenHopEnabled;
        com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.travellerOffscreenHopIntervalTicks = travellerOffscreenHopIntervalTicks;
        com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.travellerOffscreenHopStepBlocks = travellerOffscreenHopStepBlocks;
        com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen = travellerNoAggroOffscreen;
        com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.travellerOffscreenDamageScale = travellerOffscreenDamageScale;
        com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.travellerSimpleOffscreenTravel = travellerSimpleOffscreenTravel;
        com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.travellerSimpleOffscreenMinPlayerDist = travellerSimpleOffscreenMinPlayerDist;
        com.jubitus.travellers.config.TravellersConfig.OFFSCREEN.allowTravellerChunkLoading = allowTravellerChunkLoading;

        com.jubitus.travellers.config.TravellersConfig.TRAVEL.travelTorchEnabled = travelTorchEnabled;
        com.jubitus.travellers.config.TravellersConfig.TRAVEL.travelTorchCooldownTicks = travelTorchCooldownTicks;
        com.jubitus.travellers.config.TravellersConfig.TRAVEL.travelTorchNearbyRadius = (float) travelTorchNearbyRadius;
        com.jubitus.travellers.config.TravellersConfig.TRAVEL.fishWhileTravelEnabled = fishWhileTravelEnabled;

        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerSpeaks = travellerSpeaks;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerAmbient = travellerAmbient;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerAmbientMinDelay = travellerAmbientMinDelay;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerAmbientMaxDelay = travellerAmbientMaxDelay;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerAmbientVolume = travellerAmbientVolume;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerChoirVolume = travellerChoirVolume;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerTalkVolume = travellerTalkVolume;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerHurtVolume = travellerHurtVolume;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerStepLimiterStrength = (float) travellerStepLimiterStrength;
        com.jubitus.travellers.config.TravellersConfig.SOUND.travellerHurtLimiterStrength = (float) travellerHurtLimiterStrength;

        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirEnabled = choirEnabled;
        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirGroupRadius = (float) CHOIR_GROUP_RADIUS;
        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirBreakRadius = (float) CHOIR_BREAK_RADIUS;
        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirAutoChance = CHOIR_AUTO_CHANCE;
        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirAutoCooldownTicks = CHOIR_AUTO_COOLDOWN_TICKS;
        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirSessionDurationTicks = CHOIR_SESSION_DURATION_TICKS;
        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirSpreadGraceChecks = CHOIR_SPREAD_GRACE_CHECKS;
        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirTickInterval = CHOIR_TICK_INTERVAL;
        com.jubitus.travellers.config.TravellersConfig.CHOIR.choirSoundVolumeMultiplier = choirSoundVolumeMultiplier;

        com.jubitus.travellers.config.TravellersConfig.CAMP.campsEnabled = campsEnabled;
        com.jubitus.travellers.config.TravellersConfig.CAMP.campRadius = CAMP_RADIUS;
        com.jubitus.travellers.config.TravellersConfig.CAMP.campSeekRadius = SEEK_RADIUS;
        com.jubitus.travellers.config.TravellersConfig.CAMP.campMaxJoinersPerCamp = MAX_JOINERS_PER_CAMP;
        com.jubitus.travellers.config.TravellersConfig.CAMP.campWanderMinDelay = CAMP_WANDER_MIN_DELAY;
        com.jubitus.travellers.config.TravellersConfig.CAMP.campWanderMaxDelay = CAMP_WANDER_MAX_DELAY;
        com.jubitus.travellers.config.TravellersConfig.CAMP.campLookMinDelay = CAMP_LOOK_MIN_DELAY;
        com.jubitus.travellers.config.TravellersConfig.CAMP.campLookMaxDelay = CAMP_LOOK_MAX_DELAY;
        com.jubitus.travellers.config.TravellersConfig.CAMP.campStartChancePerTick = campStartChancePerTick;

        com.jubitus.travellers.config.TravellersConfig.FISHING.fishWaterScanRadius = fishWaterScanRadius;
        com.jubitus.travellers.config.TravellersConfig.FISHING.fishMinTimeTicks = fishMinTimeTicks;
        com.jubitus.travellers.config.TravellersConfig.FISHING.fishMaxTimeTicks = fishMaxTimeTicks;
        com.jubitus.travellers.config.TravellersConfig.FISHING.fishReelEveryTicks = fishReelEveryTicks;
        com.jubitus.travellers.config.TravellersConfig.FISHING.fishCastWindupTicks = fishCastWindupTicks;
        com.jubitus.travellers.config.TravellersConfig.FISHING.fishCooldownTicks = fishCooldownTicks;
        com.jubitus.travellers.config.TravellersConfig.FISHING.fishStartChancePerTick = fishStartChancePerTick;
        com.jubitus.travellers.config.TravellersConfig.FISHING.fishApproachSpeed = fishApproachSpeed;

        com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled = controlledVillageFeatureEnabled;
        com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.requireControlledVillageToHire = requireControlledVillageToHire;
        com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.barracksSlotsPerBuilding = barracksSlotsPerBuilding;
        com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.workerBarracksRadius = workerBarracksRadius;
        com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.workerRespawnInBarracks = workerRespawnInBarracks;
        com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.workerRespawnMorningTime = workerRespawnMorningTime;
        com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.teenagerToWorkerEnabled = teenagerToWorkerEnabled;

        com.jubitus.travellers.config.TravellersConfig.WORKER.workerHireCostDeniers = workerHireCostDeniers;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerNotifyOnVillageTrade = workerNotifyOnVillageTrade;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerAutoLootMobs = workerAutoLootMobs;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerVillageDetectRadius = workerVillageDetectRadius;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerGoArrivalRadius = workerGoArrivalRadius;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerIdleAnchorRadius = workerIdleAnchorRadius;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerTravelBaseCostPerSlot = (float) workerTravelBaseCostPerSlot;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerTravelDistanceBase = (float) workerTravelDistanceBase;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerTravelFlatBaseCost = workerTravelFlatBaseCost;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerTravelCostMultiplier = (float) workerTravelCostMultiplier;
        com.jubitus.travellers.config.TravellersConfig.WORKER.merchantVillageMinDistance = merchantVillageMinDistance;
        com.jubitus.travellers.config.TravellersConfig.WORKER.workerVillageMinDistance = workerVillageMinDistance;

        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitMaxVillageRadius = recruitMaxVillageRadius;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitVillagesMin = recruitVillagesMin;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitVillagesMax = recruitVillagesMax;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitSpawnMin = recruitSpawnMin;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitSpawnMax = recruitSpawnMax;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitFeeFlat = recruitFeeFlat;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitFeePerBlock = (float) recruitFeePerBlock;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitFeePerVillage = recruitFeePerVillage;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitLeaderAssignRadius = recruitLeaderAssignRadius;
        com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitMinVillageDistance = recruitMinVillageDistance;

        com.jubitus.travellers.config.TravellersConfig.MERCHANT.merchantGiveFraction = (float) merchantGiveFraction;
        com.jubitus.travellers.config.TravellersConfig.MERCHANT.merchantMinBulkAmount = merchantMinBulkAmount;
        com.jubitus.travellers.config.TravellersConfig.MERCHANT.merchantMinSmallAmount = merchantMinSmallAmount;
        com.jubitus.travellers.config.TravellersConfig.MERCHANT.merchantMaxDistinctItems = merchantMaxDistinctItems;

        com.jubitus.travellers.config.TravellersConfig.MOB_FARM.enableMobFarmAvoidance = enableMobFarmAvoidance;
        com.jubitus.travellers.config.TravellersConfig.MOB_FARM.mobFarmCheckRadius = mobFarmCheckRadius;
        com.jubitus.travellers.config.TravellersConfig.MOB_FARM.mobFarmEntityThreshold = mobFarmEntityThreshold;
        com.jubitus.travellers.config.TravellersConfig.MOB_FARM.mobFarmFenceRadius = mobFarmFenceRadius;
        com.jubitus.travellers.config.TravellersConfig.MOB_FARM.mobFarmFenceThreshold = mobFarmFenceThreshold;

        com.jubitus.travellers.config.TravellersConfig.FILTERS.excludedVillagerTagsRaw = excludedVillagerTagsRaw;
        com.jubitus.travellers.config.TravellersConfig.FILTERS.excludedVillagerFilesRaw = excludedVillagerFilesRaw;
        com.jubitus.travellers.config.TravellersConfig.FILTERS.includedRaiderVillagerTagsRaw = includedRaiderVillagerTagsRaw;
        com.jubitus.travellers.config.TravellersConfig.FILTERS.includedRaiderVillagerFilesRaw = includedRaiderVillagerFilesRaw;
        com.jubitus.travellers.config.TravellersConfig.FILTERS.includedWorkerVillagerTagsRaw = includedWorkerVillagerTagsRaw;
        com.jubitus.travellers.config.TravellersConfig.FILTERS.includedWorkerVillagerFilesRaw = includedWorkerVillagerFilesRaw;
        com.jubitus.travellers.config.TravellersConfig.FILTERS.includedMerchantVillagerTagsRaw = includedMerchantVillagerTagsRaw;
        com.jubitus.travellers.config.TravellersConfig.FILTERS.includedMerchantVillagerFilesRaw = includedMerchantVillagerFilesRaw;

        com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST.travellerAggroBlacklistIds = travellerAggroBlacklistIds;
        com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST.travellerAggroBlacklistClasses = travellerAggroBlacklistClasses;
        com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST.raiderAggroBlacklistIds = raiderAggroBlacklistIds;
        com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST.raiderAggroBlacklistClasses = raiderAggroBlacklistClasses;

        com.jubitus.travellers.config.TravellersConfig.ATTACK_RULES.raiderAttackIdRules = raiderAttackIdRules;
        com.jubitus.travellers.config.TravellersConfig.ATTACK_RULES.raiderAttackClassRules = raiderAttackClassRules;
        com.jubitus.travellers.config.TravellersConfig.ATTACK_RULES.travellerAttackIdRules = travellerAttackIdRules;
        com.jubitus.travellers.config.TravellersConfig.ATTACK_RULES.travellerAttackClassRules = travellerAttackClassRules;
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

    public static void applyRuntimeEffects() {
        net.minecraftforge.fml.common.FMLCommonHandler fml = net.minecraftforge.fml.common.FMLCommonHandler.instance();
        net.minecraft.server.MinecraftServer srv = fml.getMinecraftServerInstance();
        if (srv == null) return;

        if (rangedMaxDist < attackTriggerRange) {
            rangedMaxDist = attackTriggerRange;
        }

        for (net.minecraft.world.World world : srv.worlds) {
            if (world == null) continue;

            for (EntityTraveller t : world.getEntities(EntityTraveller.class, e -> true)) {

                final double cfgMaxHealth;
                final double cfgFollowRange;

                if (t instanceof EntityTravellerMerchant) {
                    cfgMaxHealth = merchantMaxHealth;
                    cfgFollowRange = merchantFollowRange;
                } else {
                    cfgMaxHealth = travellerMaxHealth;
                    cfgFollowRange = travellerFollowRange;
                }

                t.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MAX_HEALTH)
                        .setBaseValue(cfgMaxHealth);
                if (t.getHealth() > t.getMaxHealth()) {
                    t.setHealth(t.getMaxHealth());
                }

                t.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.ATTACK_DAMAGE)
                        .setBaseValue(attackDamage);

                t.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED)
                        .setBaseValue(movementSpeed);

                t.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.FOLLOW_RANGE)
                        .setBaseValue(cfgFollowRange);

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
                "Rendering",
                false,
                "If false, Traveller name tags will not be shown above their heads."
        );

        loadedTravellerCap = cfg.getInt(
                "loadedTravellerCap",
                "Soft spawn — Travellers",
                64, 2, 2048,
                "Won't spawn travellers if CURRENTLY LOADED travellers are more than this value"
        );
        MinGroupSizeAtSpawn = cfg.getInt(
                "TravellerMinGroupSizeAtSpawn",
                "Soft spawn — Travellers",
                2, 1, 32,
                "Minimum group size when spawning. Impacts soft spawner."
        );
        MaxGroupSizeAtSpawn = cfg.getInt(
                "TravellerMaxGroup",
                "Soft spawn — Travellers",
                12, 1, 32,
                "Maximum group size when travellers spawning. Impacts soft spawner."

        );
        loadedRaiderCap = cfg.getInt(
                "loadedRaiderCap", "Soft spawn — Raiders",
                64, 1, 256,
                "Won't naturally spawn raiders if CURRENTLY LOADED travellers are more than this value."
        );
        RaiderMinGroup = cfg.getInt(
                "RaiderMinGroup", "Soft spawn — Raiders",
                2, 1, 32,
                "Minimum group size for raiders spawns. Impacts soft spawner."
        );
        RaiderMaxGroup = cfg.getInt(
                "RaiderMaxGroup", "Soft spawn — Raiders",
                8, 1, 32,
                "Maximum group size for raiders spawns (must be >= min). Impacts soft spawner."
        );

        // === TRAVELLER PET / DOG COMPANION ===
        spawnTravellerWithDog = cfg.getBoolean(
                "spawnTravellerWithDog",
                "Soft spawn — General",
                spawnTravellerWithDog,
                "If true, travellers, raiders and workers have a chance to spawn with a dog companion. DISABLED TEMPORARILY"
        );

        travellerDogSpawnChance = cfg.getFloat(
                "travellerDogSpawnChance",
                "Soft spawn — General",
                (float) travellerDogSpawnChance, 0.0f, 1.0f,
                "Chance (0.0–1.0) that a travellers spawns with a dog companion. DISABLED TEMPORARILY OR FOR EVER"
        );

        travellerPopulationWeightPerVillager = cfg.getFloat(
                "Soft spawn — Travellers",
                "Soft spawn — General",
                0.05F,     // default
                0.0F,      // min
                10.0F,     // max
                "Extra spawn weight added per villager (0.02 ⇒ +2% per villager)"
        );

        travellerPopulationWeightMaxMultiplier = cfg.getFloat(
                "Soft spawn — Travellers",
                "Soft spawn — General",
                1.2F,      // default
                0.1F,      // min
                10.0F,     // max
                "Maximum overall multiplier applied from population influence"
        );

        travellerOutsideVillageBaseDensity = cfg.getFloat(
                "OutsideVillageBaseDensity",
                "Soft spawn — General",
                0.10F,      // default
                0.0F,      // min
                10.0F,     // max
                "Baseline spawn density outside of mill and lone buildings (0 = none)"
        );


        travellerVillageInfluenceRadius = cfg.getInt(
                "VillageInfluenceRadius", "Soft spawn — General",
                1000, 0, 5000,
                "How far a Millénaire *village* influences travellers spawning (in blocks).\n" +
                        "Higher = larger halo around mill."
        );

        travellerLoneInfluenceRadius = cfg.getInt(
                "LoneInfluenceRadius", "Soft spawn — General",
                600, 0, 5000,
                "How far a *lonebuilding* influences travellers spawning (in blocks).\n" +
                        "Lonebuildings usually have a smaller radius."
        );

        travellerVillageWeight = cfg.getFloat(
                "VillageWeight", "Soft spawn — General",
                0.3f, 0.0f, 10.0f,
                "Strength of village influence.\n" +
                        "1.0 = normal, >1 = stronger, 0 disables mill."
        );

        travellerLoneWeight = cfg.getFloat(
                "LoneWeight", "Soft spawn — General",
                0.2f, 0.0f, 10.0f,
                "Strength of lonebuilding influence.\n" +
                        "0.4 = weaker than mill, 0 disables lonebuilding influence."
        );

        travellerFalloffPower = cfg.getFloat(
                "FalloffPower", "Soft spawn — General",
                1.0f, 0.1f, 10.0f,
                "Shape of falloff curve.\n" +
                        "1 = linear, >1 = sharper falloff near edge, <1 = softer and flatter."
        );

        travellerMaxDensity = cfg.getFloat(
                "TravellerMaxDensity", "Soft spawn — General",
                1.2f, 0.1f, 50.0f,
                "Maximum combined density when multiple village/lonebuilding halos overlap.\n" +
                        "Prevents stacked mill from giving extreme spawn weights."
        );

        if (RaiderMaxGroup < RaiderMinGroup) {
            RaiderMaxGroup = RaiderMinGroup;
        }

// --- PLAYER CALM / FLEE LOGIC (combat tuning) ---
        playerCalmForgiveTicks = cfg.getInt(
                "PlayerCalmForgiveTicks", "Combat - General",
                200, 0, 20 * 60,
                "Ticks a player must go without hitting anything before travellers drop aggro (20 ticks = 1s)."
        );
        playerCalmNotifyRadius = cfg.getFloat(
                "PlayerCalmNotifyRadius", "Combat - General",
                18, 1.0f, 128.0f,
                "Radius around a player within which travellers will notice the player's last hit."
        );
        playerFleeForgiveTicks = cfg.getInt(
                "PlayerFleeForgiveTicks", "Combat - General",
                160, 0, 20 * 60,
                "Ticks a player must stay out of line-of-sight / far enough before raiders give up the chase."
        );
        playerFleeKeepDistance = cfg.getFloat(
                "PlayerFleeKeepDistance", "Combat - General",
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
        travellerAmbientMinDelay = cfg.getInt("travellerAmbientMinDelay", "sound", 400, 40, 20_000,
                "Minimum ticks between solo ambient voice lines (400 = 20s).");
        travellerAmbientMaxDelay = cfg.getInt("travellerAmbientMaxDelay", "sound", 1200, 80, 60_000,
                "Maximum ticks between solo ambient voice lines (1200 = 60s).");
        travellerAmbientVolume = cfg.getFloat("travellerAmbientVolume", "sound", 0.8F, 0.0F, 1.0F,
                "Volume for solo ambient travel voice lines (0.0–1.0).");
        travellerChoirVolume = cfg.getFloat("travellerChoirVolume", "sound", 1.0F, 0.0F, 1.0F,
                "Volume for choir/group singing (0.0–1.0).");
        travellerTalkVolume = cfg.getFloat("travellerTalkVolume", "sound", 1.0F, 0.0F, 1.0F,
                "Volume for talk and hello voice lines (0.0–1.0).");
        travellerHurtVolume = cfg.getFloat("travellerHurtVolume", "sound", 0.9F, 0.0F, 1.0F,
                "Volume for hurt sounds (0.0–1.0).");
        travellerStepLimiterStrength = cfg.getFloat(
                "travellerStepLimiterStrength",
                "audio",
                1.5F,
                0.0F,
                3.0F,
                "Strength of traveller footstep crowd limiting. 0 = disabled, 1 = default, 2+ = stronger."
        );

        travellerHurtLimiterStrength = cfg.getFloat(
                "travellerHurtLimiterStrength",
                "audio",
                1.5F,
                0.0F,
                3.0F,
                "Strength of traveller hurt crowd limiting. 0 = disabled, 1 = default, 2+ = stronger."
        );
        if (travellerAmbientMaxDelay < travellerAmbientMinDelay) {
            travellerAmbientMaxDelay = travellerAmbientMinDelay;
        }

        // CHOIR
        choirEnabled = cfg.getBoolean("choirEnabled", "choir", true,
                "Enable the traveller choir system (group polyphonic singing).");
        CHOIR_GROUP_RADIUS = cfg.getFloat("choirGroupRadius", "choir", 12.0f, 4.0f, 48.0f,
                "Max radius (blocks) to detect nearby travellers for a choir group.");
        CHOIR_BREAK_RADIUS = cfg.getFloat("choirBreakRadius", "choir", 25.0f, 8.0f, 96.0f,
                "If a singer drifts this far from the choir start center, the session is flagged.");
        CHOIR_AUTO_CHANCE = cfg.getFloat("choirAutoChance", "choir", 0.4f, 0.0f, 1.0f,
                "Probability per cooldown cycle that an auto-choir attempt is made (0.0–1.0).");
        CHOIR_AUTO_COOLDOWN_TICKS = cfg.getInt("choirAutoCooldownTicks", "choir", 600, 20, 144000,
                "Minimum ticks between auto-choir session attempts per dimension (600 = 30s).");
        CHOIR_SESSION_DURATION_TICKS = cfg.getInt("choirSessionDurationTicks", "choir", 3600, 100, 36000,
                "How long a choir session lasts in ticks (3600 = 3 min).");
        CHOIR_SPREAD_GRACE_CHECKS = cfg.getInt("choirSpreadGraceChecks", "choir", 3, 1, 20,
                "How many consecutive spread-check failures before stopping the choir.");
        CHOIR_TICK_INTERVAL = cfg.getInt("choirTickInterval", "choir", 100, 20, 400,
                "How often (ticks) the choir coordinator checks sessions and attempts new ones.");
        choirSoundVolumeMultiplier = cfg.getFloat("choirSoundVolumeMultiplier", "choir", 3.0f, 0.1f, 16.0f,
                "Volume multiplier for choir sounds. Doubles as range control: 1.0 ≈ 16 blocks, 3.0 ≈ 48 blocks.");

        // CONTROLLED VILLAGE
        controlledVillageFeatureEnabled = cfg.getBoolean("controlledVillageFeatureEnabled",
                "Controlled Village", true,
                "Enable the controlled village integration (barracks custom building, hire restriction).");
        requireControlledVillageToHire = cfg.getBoolean("requireControlledVillageToHire",
                "Controlled Village", true,
                "If true, a player must be chief of at least one Millenaire village to hire henchmen.");
        barracksSlotsPerBuilding = cfg.getInt("barracksSlotsPerBuilding",
                "Controlled Village", 5, 0, 64,
                "Number of worker slots per barracks building. Also controls how many SPAWN blocks the building requires. Set 0 to disable hay bale requirement");
        workerBarracksRadius = cfg.getInt("workerBarracksRadius",
                "Controlled Village", 10, 4, 32,
                "Radius (blocks) of a Workers Barracks building area.");
        workerRespawnInBarracks = cfg.getBoolean("workerRespawnInBarracks",
                "Controlled Village", false,
                "If true, hired workers with a barracks home respawn there the next in-game day after dying.");
        workerRespawnMorningTime = cfg.getInt("workerRespawnMorningTime",
                "Controlled Village", 1000, 0, 23999,
                "In-game day time (0-23999) when dead workers respawn at their barracks (1000 = sunrise).");

        enableTravellerEntity = cfg.getBoolean(
                "enableTravellerEntity",
                "General travellers settings",
                true,
                "Enable travellers entity. (stupid to disable it lol)"
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
                "raiders attack tweaks",
                new String[]{
                        "minecraft:creeper",
                        "minecraft:enderman"
                }, // default: empty (raiders fight most things)
                "Blacklist by entity registry ID for RAIDERS. Supports wildcards like 'modid:*'."
        );

        raiderAggroBlacklistClasses = cfg.getStringList(
                "RaiderAggroBlacklistClasses",
                "raiders attack tweaks",
                new String[]{},
                "Blacklist by fully-qualified class name for RAIDERS. Any subclass will match."
        );


// === RAIDERS ===
        raiderAttackIdRules = cfg.getStringList(
                "raiderAttackEntityIds",
                "raiders attack tweaks",
                new String[]{
                        "millenaire:genericvillager",
                        "millenaire:genericasimmfemale",
                        "millenaire:genericsimmfemale"
                },
                "Entity IDs raiders travellers will attack (exact or 'modid:*')."
        );

        raiderAttackClassRules = cfg.getStringList(
                "raiderAttackEntityClasses",
                "raiders attack tweaks",
                new String[]{},
                "Java classes raiders travellers will attack (exact or subclasses)."
        );

// Push into list
        RAIDER_ATTACK_LIST.reload(raiderAttackIdRules, raiderAttackClassRules);


// === NORMAL TRAVELLERS (non-raiders) ===
        travellerAttackIdRules = cfg.getStringList(
                "travellerAttackEntityIds",
                "travellers attack tweaks",
                new String[]{
                        // example defaults – you can leave empty if you want
                        // "modid:some_ambient_mob"
                },
                "Entity IDs friendly travellers will treat as dangerous (exact or 'modid:*')."
        );

        travellerAttackClassRules = cfg.getStringList(
                "travellerAttackEntityClasses",
                "travellers attack tweaks",
                new String[]{},
                "Java classes friendly travellers will attack (exact or subclasses)."
        );

// Push into list
        TRAVELLER_ATTACK_LIST.reload(travellerAttackIdRules, travellerAttackClassRules);

// --- TRAVELLER BASE ATTRIBUTES ---
// --- TRAVELLER BASE ATTRIBUTES ---
// Keep old fields maxHealth/followRange for backwards-compat, but prefer travellerMaxHealth/travellerFollowRange

        travellerMaxHealth = cfg.getFloat(
                "TravellerMaxHealth", "Traveller base attributes",
                25.0f, 1.0f, 1000.0f,
                "Max health of generic travellers (20 = player default)."
        );
        maxHealth = travellerMaxHealth; // keep old field in sync

        movementSpeed = cfg.getFloat(
                "MovementSpeedBase", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Base speed mult."
        );

        movementSpeedWhileFollowing = cfg.getFloat(
                "MovementSpeedWhileFollowing", "Traveller base attributes",
                0.55f, 0.05f, 2.0f,
                "Movement speed multiplier while following"
        );

        movementSpeedWhileTravel = cfg.getFloat(
                "MovementSpeedWhileTravelling", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Movement speed multiplier for travel task."
        );

        attackMovementSpeed = cfg.getFloat(
                "MovementSpeedWhileAttacking", "Traveller base attributes",
                0.56f, 0.05f, 2.0f,
                "Movement speed multiplier while attacking."
        );

        travellerFollowRange = cfg.getFloat(
                "TravellerTrackRange", "Traveller base attributes",
                64.0f, 4.0f, 256.0f,
                "FOLLOW_RANGE (detection radius in blocks) for generic travellers."
        );
        followRange = (int) travellerFollowRange; // keep old int in sync

        attackDamage = cfg.getFloat(
                "TravellerAttackDamage", "Traveller base attributes",
                1.0f, 0.0f, 100.0f,
                "Base damage dealt per melee hit."
        );
        travellerDamageScale = cfg.getFloat(
                "TravellerDamageScale", "Traveller base attributes",
                0.6f, 0.0f, 10.0f,
                "Global damage multiplier applied to travellers for mobs hits.\n" +
                        "1.0 = normal damage.\n" +
                        "0.5 = take half damage.\n" +
                        "0.0 = immune to all non-player damage.\n" +
                        "Does NOT affect damage dealt by players."
        );


        // --- MERCHANT BASE ATTRIBUTES ---
        merchantMaxHealth = cfg.getFloat(
                "MerchantMaxHealth", "Merchant base attributes",
                60.0f, 1.0f, 1000.0f,
                "Max health of merchant travellers (20 = player default)."
        );

        merchantFollowRange = cfg.getFloat(
                "MerchantTrackRange", "Merchant base attributes",
                24.0f, 4.0f, 256.0f,
                "FOLLOW_RANGE (detection radius in blocks) for merchants."
        );
        merchantStepBlocks = cfg.getInt(
                "MerchantStepBlocks", "Merchant travel behaviour",
                16, 1, 128,
                "Distance in blocks between generated path waypoints while merchants travel to/from mill."
        );
// --- Merchant spawn logic ---
        merchantMinPop = cfg.getInt(
                "MerchantMinPopulation", "Merchant spawn behavior",
                200, 0, 10_000,
                "Minimum Millénaire village population required before any merchants are allowed."
        );

        merchantPopPerMerchant = cfg.getInt(
                "MerchantPopPerMerchant", "Merchant spawn behavior",
                10, 1, 10_000,
                "Additional villagers required for each extra merchant in a village."
        );

        merchantMaxPerVillage = cfg.getInt(
                "MerchantMaxPerVillage", "Merchant spawn behavior",
                0, 0, 256,
                "Maximum number of merchants that can be assigned to a single village."
        );

        merchantRecheckEveryTicks = cfg.getInt(
                "MerchantRecheckEveryTicks", "Merchant spawn behavior",
                200, 20, 20 * 60 * 60,
                "How often (in ticks) merchant logic rechecks mill (20 ticks = 1 second)."
        );


        workerChunkRadius = cfg.getInt(
                "WorkerChunkRadius",
                "Worker behavior",
                1, 1, 10,
                "Chunk radius to keep loaded around a worker travellers."
        );

        workerStepBlocks = cfg.getInt(
                "WorkerStepBlocks",
                "Worker behavior",
                16, 1, 64,
                "Blocks per path step for worker travel."
        );

        attackDamage = cfg.getFloat(
                "TravellerAttackDamage", "Traveller base attributes",
                2.0f, 0.0f, 100.0f,
                "Base damage dealt per melee hit."
        );

        // --- TRAVELLER TUNING (new) ---
        villageNear = cfg.getInt(
                "VillageNearDistance", "destination tweaks",
                16, 1, 256,
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
                "playerCalmForgiveTicks", "Combat - General", playerCalmForgiveTicks, 20, 20 * 60,
                "Ticks a player must go without hitting anything before travellers drop aggro."
        );
        playerCalmNotifyRadius = cfg.getFloat(
                "playerCalmNotifyRadius", "Combat - General", (float) playerCalmNotifyRadius, 4f, 64f,
                "Radius around a player within which travellers will notice the player's last hit."
        );
        callForHelp = cfg.getBoolean(
                "callForHelp",
                "Combat - General",
                true,
                "If true, travellers will call nearby allies for help when attacked."
        );

        helpRange = cfg.getFloat(
                "helpRange",
                "Combat - General",
                24.0f, 1.0f, 128.0f,
                "Maximum range (in blocks) within which allies will respond to a travellers’s call for help."
        );


        swordHideDelay = cfg.getInt(
                "SwordHideDelay", "Combat - General",
                120, 0, 2000,
                "Ticks until sword is hidden after combat ends."
        );

        eatHealthThreshold = cfg.getFloat(
                "EatHealthThreshold", "Combat - General",
                0.5f, 0.1f, 1.0f,
                "Traveller starts eating at or below this fraction of max health."
        );

        rangedMinDist = cfg.getFloat(
                "RangedMinDist", "Combat - General",
                6.0f, 1.0f, 64.0f,
                "Minimum distance before travellers uses a bow."
        );

        rangedMaxDist = cfg.getFloat(
                "RangedMaxDist", "Combat - General",
                24.0f, 1.0f, 128.0f,
                "Maximum distance travellers will attempt to shoot at."
        );

        rangedCooldown = cfg.getInt(
                "RangedCooldown", "Combat - General",
                40, 1, 200,
                "Ticks between ranged attacks (20 ticks = 1 second)."
        );

        rangedBaseDamage = cfg.getFloat(
                "RangedBaseDamage", "Combat - General",
                6.0f, 0.0f, 100.0f,
                "Base arrow damage in half-hearts (6.0 = 3 hearts). Applied before multipliers."
        );

        rangedDamageMultiplier = cfg.getFloat(
                "RangedDamageMultiplier", "Combat - General",
                1.0f, 0.0f, 10.0f,
                "Multiplier applied to ranged damage. 1.0 = normal, 0.5 = half."
        );

        travellerPlayerDamageMultiplier = cfg.getFloat(
                "TravellerPlayerDamageMultiplier", "Combat - General",
                0.75f, 0.0f, 10.0f,
                "Multiplier applied to ALL damage (melee and ranged) dealt to players by travellers.\n" +
                        "0.75 = 75% of normal damage. 1.0 = unchanged."
        );

        weaponDurabilityEnabled = cfg.getBoolean(
                "WeaponDurabilityEnabled", "Combat - Item realism",
                true,
                "If true, traveller weapons lose durability on each hit (same rate as players)."
        );
        armorDurabilityEnabled = cfg.getBoolean(
                "ArmorDurabilityEnabled", "Combat - Item realism",
                true,
                "If true, traveller armor loses durability when they take damage (same rate as players)."
        );
        weaponAttackSpeedEnabled = cfg.getBoolean(
                "WeaponAttackSpeedEnabled", "Combat - Item realism",
                true,
                "If true, melee attack cooldown is derived from the weapon's ATTACK_SPEED attribute."
        );
        baseAttackCooldownTicks = cfg.getInt(
                "BaseAttackCooldownTicks", "Combat - Item realism",
                24, 1, 100,
                "Attack cooldown (ticks) used when no weapon is held or the weapon has no speed modifier. 20 = 1 attack/s."
        );
        minAttackCooldownTicks = cfg.getInt(
                "MinAttackCooldownTicks", "Combat - Item realism",
                24, 1, 40,
                "Minimum melee attack cooldown in ticks, even with the fastest weapon."
        );
        maxAttackCooldownTicks = cfg.getInt(
                "MaxAttackCooldownTicks", "Combat - Item realism",
                76, 1, 100,
                "Maximum melee attack cooldown in ticks, even with the slowest weapon."
        );
        attackCooldownMultiplier = cfg.getFloat(
                "AttackCooldownMultiplier", "Combat - Item realism",
                2.25f, 0.1f, 10.0f,
                "Percentage multiplier applied to the final attack cooldown (1.0 = weapon speed as-is, 1.5 = 50% slower, 2.0 = twice slower)."
        );

        attackTriggerRange = cfg.getInt(
                "AttackTriggerRange", "Combat - General",
                24, 1, 200,
                "How far (in blocks) travellers will attack an hostile mob."
        );
        lowHealthCombatFrac = cfg.getFloat(
                "LowHealthCombatFrac", "Combat - General",
                0.20f, 0.05f, 0.50f,
                "Health fraction (0.0-1.0) below which entities enter defensive mode and stop seeking new targets."
        );
        anchorModeEngageRadius = cfg.getInt(
                "AnchorModeEngageRadius", "Combat - General",
                12, 3, 64,
                "Max engagement radius (blocks) for workers in stay/anchor mode (follow order OFF)."
        );
        lowHealthEngageRadius = cfg.getInt(
                "LowHealthEngageRadius", "Combat - General",
                5, 2, 16,
                "Max engagement radius (blocks) for entities in low-health defensive mode."
        );
// --- FOLLOWER AI TUNING (new) ---
        followDefaultRange = cfg.getFloat(
                "FollowDefaultRange", "Traveller follow task tweaks",
                6.0f, 1.0f, 64.0f,
                "Detection radius (blocks) for deciding to follow another travellers."
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
        

        followStartChance = cfg.getFloat(
                "FollowStartChance", "Traveller follow task tweaks",
                0.75f, 0.0f, 1.0f,
                "Chance (0–1) that a travellers will decide to follow when meeting another."
        );


        villageRoamMinDuration = cfg.getInt(
                "villageRoamMinDuration",
                "General",
                10, 0, 24000,
                "Minimum duration (in seconds) a travellers will roam before resetting its roam target."
        );

        villageRoamMaxDuration = cfg.getInt(
                "villageRoamMaxDuration",
                "Traveller roam inside village task tweaks",
                30, 0, 24000,
                "Maximum duration (in seconds) a travellers will roam before resetting its roam target."
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
                stepBlocks, 1, 128,
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
                "Dynamic travellers cap: travellers per villager. 1/3 = ~1 travellers per 3 villagers."
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
                1, 0, 4,
                "How many travellers each lone building contributes to the dynamic caps."
        );

        travellerCapGlobalMin = cfg.getInt(
                "TravellerCapGlobalMin", "Soft spawn — Travellers",
                4, 0, 128,
                "Global minimum for the dynamic world travellers target."
        );

        travellerCapGlobalMax = cfg.getInt(
                "TravellerCapGlobalMax", "Soft spawn — Travellers",
                1024, 0, 256,
                "Global maximum for the dynamic world travellers target."
        );

        travellerRegionalRadius = cfg.getInt(
                "TravellerRegionalRadius", "Soft spawn — Travellers",
                800, 128, 8000,
                "Horizontal radius (in blocks) used when computing regional travellers caps around a player."
        );

        // ===== Raider caps =====
        raiderCapPerVillager = cfg.getFloat(
                "RaiderCapPerVillager", "Soft spawn — Raiders",
                1.0f / 5.0f, 0.01f, 10.0f,
                "Dynamic raiders cap: raiders per villager. 1/5 = ~1 raiders per 5 villagers."
        );

        raiderCapMinPerVillage = cfg.getInt(
                "RaiderCapMinPerVillage", "Soft spawn — Raiders",
                1, 0, 32,
                "Minimum raiders contributed by each village to the regional raiders caps."
        );

        raiderCapMaxPerVillage = cfg.getInt(
                "RaiderCapMaxPerVillage", "Soft spawn — Raiders",
                12, 0, 32,
                "Maximum raiders contributed by each village to the regional raiders caps."
        );

        raiderCapPerLoneBuilding = cfg.getInt(
                "RaiderCapPerLoneBuilding", "Soft spawn — Raiders",
                1, 0, 4,
                "How many raiders each lone building contributes to the regional raiders caps."
        );

        raiderRegionalRadius = cfg.getInt(
                "RaiderRegionalRadius", "Soft spawn — Raiders",
                600, 128, 8000,
                "Horizontal radius (in blocks) used when computing regional raiders caps around a player."
        );

        // Distance from Town Hall / village center inside which raiders never soft-spawn
        raiderMinVillageDistance = cfg.getInt(
                "RaiderMinVillageDistance", "Soft spawn — Raiders",
                160, 16, 512,
                "Minimum distance from Millénaire village centers where raiders are not allowed to soft-spawn."
        );

        raiderBaseSpawnChance = cfg.getFloat(
                "RaiderBaseSpawnChance", "Soft spawn — Raiders",
                0.65f, 0.0f, 1.0f,
                "Base chance per raiders soft-spawn attempt before multiplying by Millénaire density."
        );
// Guard: ensure max >= min for travellers ring
        if (travellerSpawnMaxDist < travellerSpawnMinDist) {
            travellerSpawnMaxDist = travellerSpawnMinDist;
        }

// === Soft spawn — Raiders ===
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
                "Maximum number of travellers that will gather around a single campcommon."
        );

// === CAMPS: FIRECAMP IDLE TIMERS ===
        CAMP_WANDER_MIN_DELAY = cfg.getInt(
                "campWanderMinDelay",
                "Camp feature",
                CAMP_WANDER_MIN_DELAY, 1, 20 * 60 * 10,
                "Minimum ticks between wander moves while at campcommon (20 ticks = 1s)."
        );

        CAMP_WANDER_MAX_DELAY = cfg.getInt(
                "campWanderMaxDelay",
                "Camp feature",
                CAMP_WANDER_MAX_DELAY, 1, 20 * 60 * 10,
                "Maximum ticks between wander moves while at campcommon (must be >= min)."
        );

        CAMP_LOOK_MIN_DELAY = cfg.getInt(
                "campLookMinDelay",
                "Camp feature",
                CAMP_LOOK_MIN_DELAY, 1, 20 * 60 * 10,
                "Minimum ticks between 'look around' actions while at campcommon."
        );

        CAMP_LOOK_MAX_DELAY = cfg.getInt(
                "campLookMaxDelay",
                "Camp feature",
                CAMP_LOOK_MAX_DELAY, 1, 20 * 60 * 10,
                "Maximum ticks between 'look around' actions while at campcommon (must be >= min)."
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

        travellerOffscreenHopIntervalTicks = cfg.getInt(
                "travellerOffscreenHopIntervalTicks",
                "Travel behavior - offscreen",
                travellerOffscreenHopIntervalTicks, 0, 20 * 60 * 60,
                "teleport interval when offscreen (20 ticks = 1s)."
        );

        travellerOffscreenHopStepBlocks = cfg.getInt(
                "travellerOffscreenHopStepBlocks",
                "Travel behavior - offscreen",
                travellerOffscreenHopStepBlocks, 0, 20 * 60 * 60,
                "teleport distance when offscreen"
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
                        + "from the travellers appearance pool. Example: [\"child\", \"bandit\"]"
        );


        excludedVillagerFilesRaw = cfg.getStringList(
                "ExcludedVillagerFiles",
                "Traveller villager filters",
                excludedVillagerFilesRaw,
                "Villager txt filenames (without path, with or without .txt) to exclude from the "
                        + "travellers appearance pool. Example: [\"boy\", \"girl\", \"wife_laundry\"]"
        );

        includedRaiderVillagerTagsRaw = cfg.getStringList(
                "IncludedRaiderVillagerTags",
                "Raider villager filters",
                includedRaiderVillagerTagsRaw,
                "If non-empty, only villager txts with tag=... matching one of these values can be used " +
                        "for the *raiders* appearance pool. Example: [\"bandit\", \"guard\"]. " +
                        "Empty list = no tag restriction."
        );

        includedRaiderVillagerFilesRaw = cfg.getStringList(
                "IncludedRaiderVillagerFiles",
                "Raider villager filters",
                includedRaiderVillagerFilesRaw,
                "If non-empty, only villager txt filenames (without path, with or without .txt) matching " +
                        "one of these values can be used for the raiders appearance pool. Example: [\"bandit\", \"guard\"] " +
                        "Empty list = no filename restriction."
        );

        // --- Worker villager filters ---
        includedWorkerVillagerTagsRaw = cfg.getStringList(
                "IncludedWorkerVillagerTags",
                "Worker villager filters",
                includedWorkerVillagerTagsRaw,
                "If non-empty, only villager txts with tag=... matching one of these values can be used " +
                        "for the *worker* appearance pool. Example: [\"worker\", \"labourer\"]. " +
                        "Empty list = no tag restriction by tag (files may still restrict)."
        );

        includedWorkerVillagerFilesRaw = cfg.getStringList(
                "IncludedWorkerVillagerFiles",
                "Worker villager filters",
                includedWorkerVillagerFilesRaw,
                "Villager txt base names (without .txt) that are allowed for the *worker* appearance pool. " +
                        "Example: [\"nor_peasant\", \"nor_labourer\"]. Empty list = no file-based restriction."
        );

// --- Merchant villager filters ---
        includedMerchantVillagerTagsRaw = cfg.getStringList(
                "IncludedMerchantVillagerTags",
                "Merchant villager filters",
                includedMerchantVillagerTagsRaw,
                "If non-empty, only villager txts with tag=... matching one of these values can be used " +
                        "for the *merchant* appearance pool. Example: [\"merchant\", \"trader\"]."
        );

        includedMerchantVillagerFilesRaw = cfg.getStringList(
                "IncludedMerchantVillagerFiles",
                "Merchant villager filters",
                includedMerchantVillagerFilesRaw,
                "Villager txt base names (without .txt) that are allowed for the *merchant* appearance pool. " +
                        "Example: [\"nor_shopkeeper\", \"nor_trader\"]."
        );

        // -----------------------------
// Travellers – Off-screen safety
// -----------------------------

        travellerNoAggroOffscreen = cfg.getBoolean(
                "TravellerNoAggroOffscreen", "despawn & performance",
                false,
                "When true, travellers are ignored by mobs while outside any player's view distance.\n" +
                        "They will not acquire new hostile targets and incoming attacks from mobs will be cancelled."
        );

        travellerOffscreenDamageScale = cfg.getFloat(
                "TravellerOffscreenDamageScale", "despawn & performance",
                0.2F, 0.0F, 1.0F,
                "Damage multiplier applied to travellers when outside any player's view distance.\n" +
                        "0.0 = immune, 0.2 = take 20% of normal damage (80% reduction), 1.0 = no reduction."
        );



        workerHireCostDeniers = cfg.getInt("workerHireCostDeniers", "Workers - Recruitment", 10 * 64, 0, 10000000,
                "Cost to hire a worker (in deniers). Default: 2 argent = 2*64 deniers.");
// -----------------------------
// Travellers – Loaded caps
// -----------------------------

        merchantLoadedCap = cfg.getInt(
                "MerchantLoadedCap", "Travellers — Population",
                0, 0, 1024,
                "Maximum number of loaded travellers merchants in this world.\n" +
                        "0 = no limit."
        );

        workerLoadedCap = cfg.getInt(
                "WorkerLoadedCap", "Travellers — Population",
                1024, 0, 2048,
                "Maximum number of loaded travellers workers in this world.\n" +
                        "0 = no limit."
        );


// -----------------------------
// Worker population / per-village limits
// -----------------------------

        workerMinPop = cfg.getInt(
                "WorkerMinPop", "Soft spawn — Workers",
                10, 0, 1024,
                "Minimum Millénaire villagers required before a village can receive any workers."
        );

        workerPopPerWorker = cfg.getInt(
                "WorkerPopPerWorker", "Soft spawn — Workers",
                6, 1, 1024,
                "Additional Millénaire population required for each extra travellers worker beyond the first.\n" +
                        "Effective workers per village ≈ 1 + (population - workerMinPop) / workerPopPerWorker (clamped to workerMaxPerVillage)."
        );

        workerMaxPerVillage = cfg.getInt(
                "WorkerMaxPerVillage", "Soft spawn — Workers",
                8, 0, 16,
                "Maximum number of travellers workers assigned to a single village."
        );

        workerRecheckEveryTicks = cfg.getInt(
                "WorkerRecheckEveryTicks", "Soft spawn — Workers",
                200, 1, 20 * 60 * 60,
                "How often (in ticks) villages are re-scanned for worker population / assignment.\n" +
                        "Default 200 ticks ≈ 10 seconds."
        );


// -----------------------------
// Worker trade / economy
// -----------------------------

        workerNotifyOnVillageTrade = cfg.getBoolean(
                "WorkerNotifyOnVillageTrade", "Worker behavior",
                true,
                "If true, workers send a chat message to the hiring player when their trade/donation\n" +
                        "with a village affects reputation."
        );

        workerTravelBaseCostPerSlot = cfg.getFloat(
                "WorkerTravelBaseCostPerSlot", "Worker behavior",
                20.0F, 0.0F, 100000.0F,
                "Base travel cost in deniers per used cargo slot for a trip of workerTravelDistanceBase blocks.\n" +
                        "Final cost is further modified by distance, workerTravelFlatBaseCost and workerTravelCostMultiplier."
        );

        workerTravelDistanceBase = cfg.getFloat(
                "WorkerTravelDistanceBase", "Worker behavior",
                250.0F, 1.0F, 10000.0F,
                "Reference distance (in blocks) used when computing worker travel cost per slot.\n" +
                        "Around 400–800 matches typical Millénaire village spacing."
        );

        workerTravelFlatBaseCost = cfg.getInt(
                "WorkerTravelFlatBaseCost", "Worker behavior",
                5 * 64, 0, 100000,
                "Flat extra deniers added to each worker trip cost, regardless of distance or cargo slots."
        );

        workerTravelCostMultiplier = cfg.getFloat(
                "WorkerTravelCostMultiplier", "Worker behavior",
                1.18F, 0.0F, 1000.0F,
                "Global multiplier applied on top of computed worker travel cost.\n" +
                        "Use this to quickly scale all worker job prices up or down."
        );

        workerVillageDetectRadius = cfg.getInt(
                "WorkerVillageDetectRadius", "Worker behavior",
                8000, 128, 100000,
                "Maximum radius (in blocks) around a worker's home when searching for Millénaire villages\n" +
                        "that can be used as delivery targets."
        );

        workerAutoLootMobs = cfg.getBoolean(
                "WorkerAutoLootMobs",
                "Worker behavior",
                true,
                "If true, workers will automatically loot mobs they kill."
        );

        merchantVillageMinDistance = cfg.getInt(
                "MerchantVillageMinDistance",
                "Worker behavior",
                600, 100, 10000,
                "Minimum block distance between merchant villages."
        );

        workerVillageMinDistance = cfg.getInt(
                "WorkerVillageMinDistance",
                "Worker behavior",
                250, 100, 10000,
                "Minimum block distance between worker villages."
        );
// =====================================================
// Workers - Recruitment
// =====================================================

        recruitMaxVillageRadius = cfg.getInt(
                "RecruitMaxVillageRadius", "Workers - Recruitment",
                8000, 0, 30000,
                "Maximum 2D distance (in blocks) from the hiring player used when searching\n" +
                        "for villages eligible for a recruitment mission.\n" +
                        "Higher values allow farther villages but can increase search/route costs."
        );

        recruitSpawnMin = cfg.getInt(
                "RecruitSpawnMin", "Workers - Recruitment",
                1, 0, 64,
                "Minimum number of workers spawned at each visited village during recruitment.\n" +
                        "If this is greater than RecruitSpawnMax, the code should clamp/swaps values."
        );

        recruitSpawnMax = cfg.getInt(
                "RecruitSpawnMax", "Workers - Recruitment",
                4, 0, 64,
                "Maximum number of workers spawned at each visited village during recruitment.\n" +
                        "Actual spawn count is random between RecruitSpawnMin and RecruitSpawnMax (inclusive)."
        );

        recruitFeeFlat = cfg.getInt(
                "RecruitFeeFlat", "Workers - Recruitment",
                4 * 64, 0, 1000000,
                "Flat recruitment fee in deniers, added once per mission.\n" +
                        "Example: 128 deniers = 2 argent. default value is 20 argent."
        );

        recruitFeePerBlock = cfg.getFloat(
                "RecruitFeePerBlock", "Workers - Recruitment",
                0.07f, 0.0f, 100.0f,
                "Additional recruitment fee per block of total 2D route distance.\n" +
                        "Tip: 0.05 means ~50 deniers per 1000 blocks."
        );

        recruitFeePerVillage = cfg.getInt(
                "RecruitFeePerVillage", "Workers - Recruitment",
                20 * 64, 0, 1000000,
                "Additional recruitment fee per visited village, in deniers.\n" +
                        "Example: 20 * 64 = 1280 deniers = 4 argent per village."
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
        // --- Worker include sets ---
        INCLUDED_WORKER_VILLAGER_TAGS.clear();
        for (String t : includedWorkerVillagerTagsRaw) {
            if (t != null) {
                String s = t.trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) {
                    INCLUDED_WORKER_VILLAGER_TAGS.add(s);
                }
            }
        }

        INCLUDED_WORKER_VILLAGER_FILES.clear();
        for (String f : includedWorkerVillagerFilesRaw) {
            if (f != null) {
                String s = f.trim().toLowerCase(java.util.Locale.ROOT);
                if (s.endsWith(".txt")) {
                    s = s.substring(0, s.length() - 4);
                }
                if (!s.isEmpty()) {
                    INCLUDED_WORKER_VILLAGER_FILES.add(s);
                }
            }
        }

        // --- Merchant include sets ---
        INCLUDED_MERCHANT_VILLAGER_TAGS.clear();
        for (String t : includedMerchantVillagerTagsRaw) {
            if (t != null) {
                String s = t.trim().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) {
                    INCLUDED_MERCHANT_VILLAGER_TAGS.add(s);
                }
            }
        }

        INCLUDED_MERCHANT_VILLAGER_FILES.clear();
        for (String f : includedMerchantVillagerFilesRaw) {
            if (f != null) {
                String s = f.trim().toLowerCase(java.util.Locale.ROOT);
                if (s.endsWith(".txt")) {
                    s = s.substring(0, s.length() - 4);
                }
                if (!s.isEmpty()) {
                    INCLUDED_MERCHANT_VILLAGER_FILES.add(s);
                }
            }
        }

        // ---------- Fishing ----------
        fishWaterScanRadius = cfg.getInt(
                "FishWaterScanRadius", "Fishing",
                6, 1, 32,
                "Radius (in blocks) to search for suitable water around the travellers when Fishing."
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
                "Every this many ticks, travellers will play their 'reel' animation while Fishing."
        );

        fishCastWindupTicks = cfg.getInt(
                "FishCastWindupTicks", "Fishing",
                15, 1, 20 * 10,
                "Wind-up time in ticks before the Fishing cast actually happens."
        );

        fishCooldownTicks = cfg.getInt(
                "FishCooldownTicks", "Fishing",
                5 * 60 * 20, 0, 60 * 60 * 20,
                "Cooldown in ticks before a travellers will consider Fishing again."
        );

        fishStartChancePerTick = cfg.getFloat(
                "FishStartChancePerTick", "Fishing",
                0.002f, 0.0f, 1.0f,
                "Per-tick chance (0–1) that a travellers decides to start Fishing " +
                        "when all other conditions are met."
        );
        merchantGiveFraction = cfg.getFloat(
                "MerchantGiveFraction", "Merchant behavior",
                0.01f,       // default
                0.0f,               // minimum
                1.0f,               // maximum
                "Fraction of total required construction resources the merchant donates.\n" +
                        "Example: 0.0833 = about 1/12 of the total needed. 0.1 = 10%. 0.25 = 25%."
        );

        merchantMinBulkAmount = cfg.getInt(
                "MerchantMinBulkAmount", "Merchant behavior",
                8,                 // default
                0,                  // min
                64000,              // max
                "Minimum amount donated for bulk items (maxStack > 16). Example: planks, stone."
        );

        merchantMinSmallAmount = cfg.getInt(
                "MerchantMinSmallAmount", "Merchant behavior",
                1,                  // default
                0,                  // min
                64000,              // max
                "Minimum amount donated for small-stack items (maxStack <= 16). Example: banners, tools."
        );

        merchantMaxDistinctItems = cfg.getInt(
                "MerchantMaxDistinctItems", "Merchant behavior",
                1,                  // default
                0,                  // min
                64,                 // max
                "How many DIFFERENT item types a merchant can donate per village visit."
        );


// Guard: ensure max >= min for raiders ring
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


    // Called after TravellersConfig fields are updated via GUI/reflector to rebuild derived sets and apply guards.
    public static void applyDerivedValues() {
        // Rebuild excluded traveller sets
        EXCLUDED_VILLAGER_TAGS.clear();
        for (String t : com.jubitus.travellers.config.TravellersConfig.FILTERS.excludedVillagerTagsRaw) {
            if (t != null) { String s = t.trim().toLowerCase(java.util.Locale.ROOT); if (!s.isEmpty()) EXCLUDED_VILLAGER_TAGS.add(s); }
        }
        EXCLUDED_VILLAGER_FILES.clear();
        for (String f : com.jubitus.travellers.config.TravellersConfig.FILTERS.excludedVillagerFilesRaw) {
            if (f != null) { String s = f.trim().toLowerCase(java.util.Locale.ROOT); if (s.endsWith(".txt")) s = s.substring(0, s.length()-4); if (!s.isEmpty()) EXCLUDED_VILLAGER_FILES.add(s); }
        }
        // Rebuild included raider sets
        INCLUDED_RAIDER_VILLAGER_TAGS.clear();
        for (String t : com.jubitus.travellers.config.TravellersConfig.FILTERS.includedRaiderVillagerTagsRaw) {
            if (t != null) { String s = t.trim().toLowerCase(java.util.Locale.ROOT); if (!s.isEmpty()) INCLUDED_RAIDER_VILLAGER_TAGS.add(s); }
        }
        INCLUDED_RAIDER_VILLAGER_FILES.clear();
        for (String f : com.jubitus.travellers.config.TravellersConfig.FILTERS.includedRaiderVillagerFilesRaw) {
            if (f != null) { String s = f.trim().toLowerCase(java.util.Locale.ROOT); if (s.endsWith(".txt")) s = s.substring(0, s.length()-4); if (!s.isEmpty()) INCLUDED_RAIDER_VILLAGER_FILES.add(s); }
        }
        // Rebuild included worker sets
        INCLUDED_WORKER_VILLAGER_TAGS.clear();
        for (String t : com.jubitus.travellers.config.TravellersConfig.FILTERS.includedWorkerVillagerTagsRaw) {
            if (t != null) { String s = t.trim().toLowerCase(java.util.Locale.ROOT); if (!s.isEmpty()) INCLUDED_WORKER_VILLAGER_TAGS.add(s); }
        }
        INCLUDED_WORKER_VILLAGER_FILES.clear();
        for (String f : com.jubitus.travellers.config.TravellersConfig.FILTERS.includedWorkerVillagerFilesRaw) {
            if (f != null) { String s = f.trim().toLowerCase(java.util.Locale.ROOT); if (s.endsWith(".txt")) s = s.substring(0, s.length()-4); if (!s.isEmpty()) INCLUDED_WORKER_VILLAGER_FILES.add(s); }
        }
        // Rebuild included merchant sets
        INCLUDED_MERCHANT_VILLAGER_TAGS.clear();
        for (String t : com.jubitus.travellers.config.TravellersConfig.FILTERS.includedMerchantVillagerTagsRaw) {
            if (t != null) { String s = t.trim().toLowerCase(java.util.Locale.ROOT); if (!s.isEmpty()) INCLUDED_MERCHANT_VILLAGER_TAGS.add(s); }
        }
        INCLUDED_MERCHANT_VILLAGER_FILES.clear();
        for (String f : com.jubitus.travellers.config.TravellersConfig.FILTERS.includedMerchantVillagerFilesRaw) {
            if (f != null) { String s = f.trim().toLowerCase(java.util.Locale.ROOT); if (s.endsWith(".txt")) s = s.substring(0, s.length()-4); if (!s.isEmpty()) INCLUDED_MERCHANT_VILLAGER_FILES.add(s); }
        }
        // Rebuild attack/aggro lists
        RAIDER_ATTACK_LIST.reload(
                com.jubitus.travellers.config.TravellersConfig.ATTACK_RULES.raiderAttackIdRules,
                com.jubitus.travellers.config.TravellersConfig.ATTACK_RULES.raiderAttackClassRules);
        TRAVELLER_ATTACK_LIST.reload(
                com.jubitus.travellers.config.TravellersConfig.ATTACK_RULES.travellerAttackIdRules,
                com.jubitus.travellers.config.TravellersConfig.ATTACK_RULES.travellerAttackClassRules);
        TRAVELLER_AGGRO_BLACKLIST.reload(
                com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST.travellerAggroBlacklistIds,
                com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST.travellerAggroBlacklistClasses);
        RAIDER_AGGRO_BLACKLIST.reload(
                com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST.raiderAggroBlacklistIds,
                com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST.raiderAggroBlacklistClasses);
    }

    // Sync server-side caches from TravellersConfig fields.
    public static void syncServerCachesFromConfig() {
        workerHireCostDeniers_serverCache = com.jubitus.travellers.config.TravellersConfig.WORKER.workerHireCostDeniers;
        recruitFeeFlat_serverCache = com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitFeeFlat;
        recruitFeePerBlock_serverCache = com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitFeePerBlock;
        recruitFeePerVillage_serverCache = com.jubitus.travellers.config.TravellersConfig.RECRUITMENT.recruitFeePerVillage;
    }

    // Copy all TravellersConfig fields into TravellersModConfig fields so existing game code keeps working.
    public static void syncFromTravellersConfig() {
        com.jubitus.travellers.config.TravellersConfig.General    g  = com.jubitus.travellers.config.TravellersConfig.GENERAL;
        com.jubitus.travellers.config.TravellersConfig.Rendering  rn = com.jubitus.travellers.config.TravellersConfig.RENDERING;
        com.jubitus.travellers.config.TravellersConfig.Spawn      sp = com.jubitus.travellers.config.TravellersConfig.SPAWN;
        com.jubitus.travellers.config.TravellersConfig.SpawnTravellers st = com.jubitus.travellers.config.TravellersConfig.SPAWN_TRAVELLERS;
        com.jubitus.travellers.config.TravellersConfig.SpawnRaiders sr = com.jubitus.travellers.config.TravellersConfig.SPAWN_RAIDERS;
        com.jubitus.travellers.config.TravellersConfig.SpawnWorkers sw = com.jubitus.travellers.config.TravellersConfig.SPAWN_WORKERS;
        com.jubitus.travellers.config.TravellersConfig.Despawn    ds = com.jubitus.travellers.config.TravellersConfig.DESPAWN;
        com.jubitus.travellers.config.TravellersConfig.Attributes at = com.jubitus.travellers.config.TravellersConfig.ATTRIBUTES;
        com.jubitus.travellers.config.TravellersConfig.Combat     cb = com.jubitus.travellers.config.TravellersConfig.COMBAT;
        com.jubitus.travellers.config.TravellersConfig.PlayerAggro pa = com.jubitus.travellers.config.TravellersConfig.PLAYER_AGGRO;
        com.jubitus.travellers.config.TravellersConfig.Follow     fo = com.jubitus.travellers.config.TravellersConfig.FOLLOW;
        com.jubitus.travellers.config.TravellersConfig.Destinations de = com.jubitus.travellers.config.TravellersConfig.DESTINATIONS;
        com.jubitus.travellers.config.TravellersConfig.Offscreen  of = com.jubitus.travellers.config.TravellersConfig.OFFSCREEN;
        com.jubitus.travellers.config.TravellersConfig.Travel     tr = com.jubitus.travellers.config.TravellersConfig.TRAVEL;
        com.jubitus.travellers.config.TravellersConfig.Sound      so = com.jubitus.travellers.config.TravellersConfig.SOUND;
        com.jubitus.travellers.config.TravellersConfig.Choir      ch = com.jubitus.travellers.config.TravellersConfig.CHOIR;
        com.jubitus.travellers.config.TravellersConfig.Camp       ca = com.jubitus.travellers.config.TravellersConfig.CAMP;
        com.jubitus.travellers.config.TravellersConfig.Fishing    fi = com.jubitus.travellers.config.TravellersConfig.FISHING;
        com.jubitus.travellers.config.TravellersConfig.ControlledVillage cv = com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE;
        com.jubitus.travellers.config.TravellersConfig.Worker     wo = com.jubitus.travellers.config.TravellersConfig.WORKER;
        com.jubitus.travellers.config.TravellersConfig.Recruitment rc = com.jubitus.travellers.config.TravellersConfig.RECRUITMENT;
        com.jubitus.travellers.config.TravellersConfig.Merchant   me = com.jubitus.travellers.config.TravellersConfig.MERCHANT;
        com.jubitus.travellers.config.TravellersConfig.MobFarm    mf = com.jubitus.travellers.config.TravellersConfig.MOB_FARM;
        com.jubitus.travellers.config.TravellersConfig.Filters    fl = com.jubitus.travellers.config.TravellersConfig.FILTERS;
        com.jubitus.travellers.config.TravellersConfig.AggroBlacklist ab = com.jubitus.travellers.config.TravellersConfig.AGGRO_BLACKLIST;

        // General
        enableTravellerEntity       = g.enableTravellerEntity;
        enableTravellerRaider       = g.enableTravellerRaider;
        debug                       = g.debug;
        enableTravellerOpenFenceGates = g.enableTravellerOpenFenceGates;
        enableTravellerOpenDoors    = g.enableTravellerOpenDoors;

        // Rendering
        showTravellerNames          = rn.showTravellerNames;

        // Spawn
        travellerVillageInfluenceRadius = sp.travellerVillageInfluenceRadius;
        travellerLoneInfluenceRadius  = sp.travellerLoneInfluenceRadius;
        travellerVillageWeight        = sp.travellerVillageWeight;
        travellerLoneWeight           = sp.travellerLoneWeight;
        travellerFalloffPower         = sp.travellerFalloffPower;
        travellerMaxDensity           = sp.travellerMaxDensity;
        travellerOutsideVillageBaseDensity = sp.travellerOutsideVillageBaseDensity;
        spawnTravellerWithDog         = sp.spawnTravellerWithDog;
        travellerDogSpawnChance       = sp.travellerDogSpawnChance;
        travellerPopulationWeightEnabled = sp.travellerPopulationWeightEnabled;
        travellerPopulationWeightRadius  = sp.travellerPopulationWeightRadius;
        travellerPopulationWeightPerVillager = sp.travellerPopulationWeightPerVillager;
        travellerPopulationWeightMaxMultiplier = sp.travellerPopulationWeightMaxMultiplier;
        travellerCultureWeightedSpawn = sp.travellerCultureWeightedSpawn;
        travellerCultureScanRadius    = sp.travellerCultureScanRadius;
        travellerCultureWeightUseVillagePopulation = sp.travellerCultureWeightUseVillagePopulation;

        // SpawnTravellers
        loadedTravellerCap            = st.loadedTravellerCap;
        MinGroupSizeAtSpawn           = st.minGroupSizeAtSpawn;
        MaxGroupSizeAtSpawn           = st.maxGroupSizeAtSpawn;
        enableTravellerSoftSpawns     = st.enableTravellerSoftSpawns;
        travellerTargetLoaded         = st.travellerTargetLoaded;
        travellerTopUpEveryTicks      = st.travellerTopUpEveryTicks;
        travellerAttemptsPerPlayer    = st.travellerAttemptsPerPlayer;
        travellerSpawnMinDist         = st.travellerSpawnMinDist;
        travellerSpawnMaxDist         = st.travellerSpawnMaxDist;
        travellerSpawnLightMin        = st.travellerSpawnLightMin;
        travellerMaxPositionChecksPerAttempt = st.travellerMaxPositionChecksPerAttempt;
        travellerBaseSpawnChance      = st.travellerBaseSpawnChance;
        travellerCapPerVillager       = st.travellerCapPerVillager;
        travellerCapMinPerVillage     = st.travellerCapMinPerVillage;
        travellerCapMaxPerVillage     = st.travellerCapMaxPerVillage;
        travellerCapPerLoneBuilding   = st.travellerCapPerLoneBuilding;
        travellerCapGlobalMin         = st.travellerCapGlobalMin;
        travellerCapGlobalMax         = st.travellerCapGlobalMax;
        travellerRegionalRadius       = st.travellerRegionalRadius;
        travellerLocalCap             = st.travellerLocalCap;
        travellerLocalCapRadius       = st.travellerLocalCapRadius;
        travellerSpawnPermille        = st.travellerSpawnPermille;

        // SpawnRaiders
        loadedRaiderCap               = sr.loadedRaiderCap;
        RaiderMinGroup                = sr.raiderMinGroup;
        RaiderMaxGroup                = sr.raiderMaxGroup;
        enableRaiderSoftSpawns        = sr.enableRaiderSoftSpawns;
        raiderTargetLoaded            = sr.raiderTargetLoaded;
        raiderTopUpEveryTicks         = sr.raiderTopUpEveryTicks;
        raiderAttemptsPerPlayer       = sr.raiderAttemptsPerPlayer;
        raiderSoftSpawnChancePerAttempt = sr.raiderSoftSpawnChancePerAttempt;
        raiderSpawnMinDist            = sr.raiderSpawnMinDist;
        raiderSpawnMaxDist            = sr.raiderSpawnMaxDist;
        raiderMinVillageDistance      = sr.raiderMinVillageDistance;
        raiderBaseSpawnChance         = sr.raiderBaseSpawnChance;
        raiderCapPerVillager          = sr.raiderCapPerVillager;
        raiderCapMinPerVillage        = sr.raiderCapMinPerVillage;
        raiderCapMaxPerVillage        = sr.raiderCapMaxPerVillage;
        raiderCapPerLoneBuilding      = sr.raiderCapPerLoneBuilding;
        raiderRegionalRadius          = sr.raiderRegionalRadius;
        raiderLocalCap                = sr.raiderLocalCap;
        raiderLocalCapRadius          = sr.raiderLocalCapRadius;
        raiderMaxPositionChecksPerAttempt = sr.raiderMaxPositionChecksPerAttempt;

        // SpawnWorkers
        workerMinPop                  = sw.workerMinPop;
        workerPopPerWorker            = sw.workerPopPerWorker;
        workerMaxPerVillage           = sw.workerMaxPerVillage;
        workerRecheckEveryTicks       = sw.workerRecheckEveryTicks;
        workerLoadedCap               = sw.workerLoadedCap;
        merchantLoadedCap             = sw.merchantLoadedCap;
        merchantMinPop                = sw.merchantMinPop;
        merchantPopPerMerchant        = sw.merchantPopPerMerchant;
        merchantMaxPerVillage         = sw.merchantMaxPerVillage;
        merchantRecheckEveryTicks     = sw.merchantRecheckEveryTicks;

        // Despawn
        despawnNearUnloadedChunks     = ds.despawnNearUnloadedChunks;
        despawnWhenFarFromPlayers     = ds.despawnWhenFarFromPlayers;
        farExtraChunks                = ds.farExtraChunks;
        farGraceTicks                 = ds.farGraceTicks;
        farMinDistanceBlocks          = ds.farMinDistanceBlocks;
        serverViewDistanceFallbackChunks = ds.serverViewDistanceFallbackChunks;
        despawnProbeRadiusChunks      = ds.despawnProbeRadiusChunks;
        despawnEdgeGraceTicks         = ds.despawnEdgeGraceTicks;

        // Attributes
        travellerMaxHealth            = at.travellerMaxHealth;
        maxHealth                     = at.travellerMaxHealth;
        movementSpeed                 = at.movementSpeed;
        movementSpeedWhileFollowing   = at.movementSpeedWhileFollowing;
        movementSpeedWhileTravel      = at.movementSpeedWhileTravel;
        attackMovementSpeed           = at.movementSpeedWhileAttacking;
        travellerFollowRange          = at.travellerFollowRange;
        followRange                   = (int) at.travellerFollowRange;
        attackDamage                  = at.attackDamage;
        travellerDamageScale          = at.travellerDamageScale;
        travellerPlayerDamageMultiplier = at.travellerPlayerDamageMultiplier;
        raiderMaxHealth               = at.raiderMaxHealth;
        merchantMaxHealth             = at.merchantMaxHealth;
        merchantFollowRange           = at.merchantFollowRange;
        merchantStepBlocks            = at.merchantStepBlocks;
        merchantChunkRadius           = at.merchantChunkRadius;
        merchantMaxForcedChunks       = at.merchantMaxForcedChunks;
        merchantMaxChunkloadingMerchants = at.merchantMaxChunkloadingMerchants;
        merchantChunkloadingEnabled   = at.merchantChunkloadingEnabled;
        workerMaxHealth               = at.workerMaxHealth;
        workerFollowRange             = at.workerFollowRange;
        workerChunkRadius             = at.workerChunkRadius;
        workerStepBlocks              = at.workerStepBlocks;
        stepBlocks                    = at.stepBlocks;
        workerGlobalAssignedCap       = at.workerGlobalAssignedCap;

        // Combat
        callForHelp                   = cb.callForHelp;
        helpRange                     = cb.helpRange;
        swordHideDelay                = cb.swordHideDelay;
        eatHealthThreshold            = cb.eatHealthThreshold;
        attackTriggerRange            = cb.attackTriggerRange;
        lowHealthCombatFrac           = cb.lowHealthCombatFrac;
        anchorModeEngageRadius        = cb.anchorModeEngageRadius;
        lowHealthEngageRadius         = cb.lowHealthEngageRadius;
        requirePathForAggro           = cb.requirePathForAggro;
        requireLOSForMemoryTargets    = cb.requireLOSForMemoryTargets;
        requireLOSForChainTargets     = cb.requireLOSForChainTargets;
        travellerOffscreenViewRadius  = cb.travellerOffscreenViewRadius;
        travellerViewBoostBaseRadius  = cb.travellerViewBoostBaseRadius;
        travellerViewBoostCombatBonus = cb.travellerViewBoostCombatBonus;
        rangedMinDist                 = cb.rangedMinDist;
        rangedMaxDist                 = cb.rangedMaxDist;
        rangedCooldown                = cb.rangedCooldown;
        rangedBaseDamage              = cb.rangedBaseDamage;
        rangedDamageMultiplier        = cb.rangedDamageMultiplier;
        weaponDurabilityEnabled       = cb.weaponDurabilityEnabled;
        armorDurabilityEnabled        = cb.armorDurabilityEnabled;
        weaponAttackSpeedEnabled      = cb.weaponAttackSpeedEnabled;
        baseAttackCooldownTicks       = cb.baseAttackCooldownTicks;
        minAttackCooldownTicks        = cb.minAttackCooldownTicks;
        maxAttackCooldownTicks        = cb.maxAttackCooldownTicks;
        attackCooldownMultiplier      = cb.attackCooldownMultiplier;

        // PlayerAggro
        playerCalmForgiveTicks        = pa.playerCalmForgiveTicks;
        playerCalmNotifyRadius        = pa.playerCalmNotifyRadius;
        playerFleeForgiveTicks        = pa.playerFleeForgiveTicks;
        playerFleeKeepDistance        = pa.playerFleeKeepDistance;

        // Follow
        followDefaultRange            = fo.followDefaultRange;
        followDurationMin             = fo.followDurationMin;
        followDurationMax             = fo.followDurationMax;
        followStartChance             = fo.followStartChance;
        followMaxFollowersPerLeader   = fo.followMaxFollowersPerLeader;
        followerBoatConvoySpeed       = fo.followerBoatConvoySpeed;
        followerRingMoveBaseRadius    = fo.followerRingMoveBaseRadius;
        followerRingMoveSlotStep      = fo.followerRingMoveSlotStep;
        returnFollowSwitchDist        = fo.returnFollowSwitchDist;
        chunkRecomputeIntervalTicks   = fo.chunkRecomputeIntervalTicks;

        // Destinations
        villageNear                   = de.villageNear;
        villageRadius                 = de.villageRadius;
        lonebuildingNear              = de.lonebuildingNear;
        villagePickRadius             = de.villagePickRadius;
        villageRoamMinDuration        = de.villageRoamMinDuration;
        villageRoamMaxDuration        = de.villageRoamMaxDuration;

        // Offscreen
        travellerOffscreenHopEnabled  = of.travellerOffscreenHopEnabled;
        travellerOffscreenHopIntervalTicks = of.travellerOffscreenHopIntervalTicks;
        travellerOffscreenHopStepBlocks = of.travellerOffscreenHopStepBlocks;
        travellerNoAggroOffscreen     = of.travellerNoAggroOffscreen;
        travellerOffscreenDamageScale = of.travellerOffscreenDamageScale;
        travellerSimpleOffscreenTravel = of.travellerSimpleOffscreenTravel;
        travellerSimpleOffscreenMinPlayerDist = of.travellerSimpleOffscreenMinPlayerDist;
        allowTravellerChunkLoading    = of.allowTravellerChunkLoading;

        // Travel
        travelTorchEnabled            = tr.travelTorchEnabled;
        travelTorchCooldownTicks      = tr.travelTorchCooldownTicks;
        travelTorchNearbyRadius       = tr.travelTorchNearbyRadius;
        fishWhileTravelEnabled        = tr.fishWhileTravelEnabled;

        // Sound
        travellerSpeaks               = so.travellerSpeaks;
        travellerAmbient              = so.travellerAmbient;
        travellerAmbientMinDelay      = so.travellerAmbientMinDelay;
        travellerAmbientMaxDelay      = so.travellerAmbientMaxDelay;
        travellerAmbientVolume        = so.travellerAmbientVolume;
        travellerChoirVolume          = so.travellerChoirVolume;
        travellerTalkVolume           = so.travellerTalkVolume;
        travellerHurtVolume           = so.travellerHurtVolume;
        travellerStepLimiterStrength  = so.travellerStepLimiterStrength;
        travellerHurtLimiterStrength  = so.travellerHurtLimiterStrength;

        // Choir
        choirEnabled                  = ch.choirEnabled;
        CHOIR_GROUP_RADIUS            = ch.choirGroupRadius;
        CHOIR_BREAK_RADIUS            = ch.choirBreakRadius;
        CHOIR_AUTO_CHANCE             = ch.choirAutoChance;
        CHOIR_AUTO_COOLDOWN_TICKS     = ch.choirAutoCooldownTicks;
        CHOIR_SESSION_DURATION_TICKS  = ch.choirSessionDurationTicks;
        CHOIR_SPREAD_GRACE_CHECKS     = ch.choirSpreadGraceChecks;
        CHOIR_TICK_INTERVAL           = ch.choirTickInterval;
        choirSoundVolumeMultiplier    = ch.choirSoundVolumeMultiplier;

        // Camp
        campsEnabled                  = ca.campsEnabled;
        CAMP_RADIUS                   = ca.campRadius;
        SEEK_RADIUS                   = ca.campSeekRadius;
        MAX_JOINERS_PER_CAMP          = ca.campMaxJoinersPerCamp;
        CAMP_WANDER_MIN_DELAY         = ca.campWanderMinDelay;
        CAMP_WANDER_MAX_DELAY         = ca.campWanderMaxDelay;
        CAMP_LOOK_MIN_DELAY           = ca.campLookMinDelay;
        CAMP_LOOK_MAX_DELAY           = ca.campLookMaxDelay;
        campStartChancePerTick        = ca.campStartChancePerTick;

        // Fishing
        fishWaterScanRadius           = fi.fishWaterScanRadius;
        fishMinTimeTicks              = fi.fishMinTimeTicks;
        fishMaxTimeTicks              = fi.fishMaxTimeTicks;
        fishReelEveryTicks            = fi.fishReelEveryTicks;
        fishCastWindupTicks           = fi.fishCastWindupTicks;
        fishCooldownTicks             = fi.fishCooldownTicks;
        fishStartChancePerTick        = fi.fishStartChancePerTick;
        fishApproachSpeed             = fi.fishApproachSpeed;

        // ControlledVillage
        controlledVillageFeatureEnabled = cv.controlledVillageFeatureEnabled;
        requireControlledVillageToHire  = cv.requireControlledVillageToHire;
        barracksSlotsPerBuilding        = cv.barracksSlotsPerBuilding;
        workerBarracksRadius            = cv.workerBarracksRadius;
        workerRespawnInBarracks         = cv.workerRespawnInBarracks;
        workerRespawnMorningTime        = cv.workerRespawnMorningTime;
        teenagerToWorkerEnabled         = cv.teenagerToWorkerEnabled;

        // Worker
        workerHireCostDeniers         = wo.workerHireCostDeniers;
        workerNotifyOnVillageTrade    = wo.workerNotifyOnVillageTrade;
        workerAutoLootMobs            = wo.workerAutoLootMobs;
        workerVillageDetectRadius     = wo.workerVillageDetectRadius;
        workerGoArrivalRadius         = wo.workerGoArrivalRadius;
        workerIdleAnchorRadius        = wo.workerIdleAnchorRadius;
        workerTravelBaseCostPerSlot   = wo.workerTravelBaseCostPerSlot;
        workerTravelDistanceBase      = wo.workerTravelDistanceBase;
        workerTravelFlatBaseCost      = wo.workerTravelFlatBaseCost;
        workerTravelCostMultiplier    = wo.workerTravelCostMultiplier;
        merchantVillageMinDistance    = wo.merchantVillageMinDistance;
        workerVillageMinDistance      = wo.workerVillageMinDistance;

        // Recruitment
        recruitMaxVillageRadius       = rc.recruitMaxVillageRadius;
        recruitVillagesMin            = rc.recruitVillagesMin;
        recruitVillagesMax            = rc.recruitVillagesMax;
        recruitSpawnMin               = rc.recruitSpawnMin;
        recruitSpawnMax               = rc.recruitSpawnMax;
        recruitFeeFlat                = rc.recruitFeeFlat;
        recruitFeePerBlock            = rc.recruitFeePerBlock;
        recruitFeePerVillage          = rc.recruitFeePerVillage;
        recruitLeaderAssignRadius     = rc.recruitLeaderAssignRadius;
        recruitMinVillageDistance     = rc.recruitMinVillageDistance;

        // Merchant
        merchantGiveFraction          = me.merchantGiveFraction;
        merchantMinBulkAmount         = me.merchantMinBulkAmount;
        merchantMinSmallAmount        = me.merchantMinSmallAmount;
        merchantMaxDistinctItems      = me.merchantMaxDistinctItems;

        // MobFarm
        enableMobFarmAvoidance        = mf.enableMobFarmAvoidance;
        mobFarmCheckRadius            = mf.mobFarmCheckRadius;
        mobFarmEntityThreshold        = mf.mobFarmEntityThreshold;
        mobFarmFenceRadius            = mf.mobFarmFenceRadius;
        mobFarmFenceThreshold         = mf.mobFarmFenceThreshold;

        // Filters (raw arrays)
        excludedVillagerTagsRaw         = fl.excludedVillagerTagsRaw;
        excludedVillagerFilesRaw        = fl.excludedVillagerFilesRaw;
        includedRaiderVillagerTagsRaw   = fl.includedRaiderVillagerTagsRaw;
        includedRaiderVillagerFilesRaw  = fl.includedRaiderVillagerFilesRaw;
        includedWorkerVillagerTagsRaw   = fl.includedWorkerVillagerTagsRaw;
        includedWorkerVillagerFilesRaw  = fl.includedWorkerVillagerFilesRaw;
        includedMerchantVillagerTagsRaw = fl.includedMerchantVillagerTagsRaw;
        includedMerchantVillagerFilesRaw = fl.includedMerchantVillagerFilesRaw;

        // AggroBlacklist
        travellerAggroBlacklistIds      = ab.travellerAggroBlacklistIds;
        travellerAggroBlacklistClasses  = ab.travellerAggroBlacklistClasses;
        raiderAggroBlacklistIds         = ab.raiderAggroBlacklistIds;
        raiderAggroBlacklistClasses     = ab.raiderAggroBlacklistClasses;
    }

    /**
     * Public entrypoint to reload from disk (for commands or GUI).
     */
    public static void reload() {
        if (CONFIG == null) {
            CONFIG = new Configuration(CONFIG_FILE);
        }

        try {
            CONFIG.load();
            readValues(CONFIG);

            workerHireCostDeniers_serverCache = workerHireCostDeniers;
            recruitFeeFlat_serverCache = recruitFeeFlat;
            recruitFeePerBlock_serverCache = recruitFeePerBlock;
            recruitFeePerVillage_serverCache = recruitFeePerVillage;

            if (CONFIG.hasChanged()) {
                CONFIG.save();
            }

            applyRuntimeEffects();
            broadcastServerCostsToAllPlayers();

            System.out.println("[ModConfig] Reloaded config from disk.");
        } catch (Exception e) {
            System.err.println("[ModConfig] Reload failed: " + e.getMessage());
        }
    }


    public static boolean isVillagerFileExcludedForTravellers(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".txt")) {
            name = name.substring(0, name.length() - 4);
        }
        return EXCLUDED_VILLAGER_FILES.contains(name);
    }

    public static boolean isVillagerFileExcludedForRaiders(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".txt")) {
            name = name.substring(0, name.length() - 4);
        }
        return EXCLUDED_RAIDER_VILLAGER_FILES.contains(name);
    }
    public static void broadcastServerCostsToAllPlayers() {
        net.minecraftforge.fml.common.FMLCommonHandler fml = net.minecraftforge.fml.common.FMLCommonHandler.instance();
        net.minecraft.server.MinecraftServer srv = fml.getMinecraftServerInstance();
        if (srv == null) return;

        MsgSyncServerCosts packet = new MsgSyncServerCosts(
                TravellersModConfig.workerHireCostDeniers_serverCache,
                TravellersModConfig.recruitFeeFlat_serverCache,
                TravellersModConfig.recruitFeePerBlock_serverCache,
                TravellersModConfig.recruitFeePerVillage_serverCache
        );

        for (net.minecraft.entity.player.EntityPlayerMP p : srv.getPlayerList().getPlayers()) {
            TravellerFollowNet.CHANNEL.sendTo(packet, p);
        }
    }

}