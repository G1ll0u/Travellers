package com.jubitus.traveller;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.utils.mobs.RaiderAttackList;
import com.jubitus.traveller.traveller.utils.mobs.TravellerBlacklist;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.io.IOException;


public class TravellersModConfig {


    public static int CAMP_RADIUS = 5; // or expose a public constant on EntityTraveller
    // === CAMP / FIREPIT BEHAVIOR ===
    public static int SEEK_RADIUS = 24; // how far to look for camps
    public static int MAX_JOINERS_PER_CAMP = 12;

    // Firecamp idle behavior (wander / look delays in ticks)
    public static int CAMP_WANDER_MIN_DELAY = 40;   // 2s  (20 tps)
    public static int CAMP_WANDER_MAX_DELAY = 140;  // 7s
    public static int CAMP_LOOK_MIN_DELAY   = 30;   // 1.5s
    public static int CAMP_LOOK_MAX_DELAY   = 110;  // 5.5s
    public static int loadedRaiderCap;
    public static int serverViewDistanceFallbackChunks = 12;
    public static boolean debug = false;
    private static String[] raiderAttackIdRules = new String[0];
    private static String[] raiderAttackClassRules = new String[0];
    public static final RaiderAttackList RAIDER_ATTACK_LIST = new RaiderAttackList();
    public static final TravellerBlacklist TRAVELLER_BLACKLIST = new TravellerBlacklist();
    private static final String CONFIG_VERSION_KEY = "configVersion";
    private static final String CURRENT_VERSION = "4.2"; // yes I always change ver when testing, that's stupid
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
    public static boolean travellerSpeaks;
    public static boolean travellerAmbient = true;           // NEW
    public static int travellerAmbientMinDelay;   // 10s
    public static int travellerAmbientMaxDelay;    // 30s
    public static float travellerAmbientVolume;      // 0.0–1.0
    static boolean enableTravellerEntity = true;
    private static Configuration CONFIG;      // <— keep it
    private static File CONFIG_FILE;          // <— keep it
    public static boolean enableTravellerRaider = true;
    public static int RaiderWeight;
    public static int RaiderMinGroup;
    public static int RaiderMaxGroup;
    public static int playerCalmForgiveTicks;  // 8s without hitting -> calm down
    public static double playerCalmNotifyRadius; // how far we "hear" a player's hit
    public static int playerFleeForgiveTicks;   // need to be out of LOS/too far for 6s
    public static double playerFleeKeepDistance;  // if closer than this, never forgive (even w/o hits)
    // NEW: two separate aggro blacklists
    public static final TravellerBlacklist TRAVELLER_AGGRO_BLACKLIST = new TravellerBlacklist();
    public static final TravellerBlacklist RAIDER_AGGRO_BLACKLIST    = new TravellerBlacklist();
    public static String[] raiderAggroBlacklistIds     = new String[0];
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
    public static int travellerTopUpEveryTicks = 10*20;    // how often to try
    public static int travellerAttemptsPerPlayer = 1;   // attempts per player each cycle
    public static int travellerSpawnMinDist = 32;       // from player
    public static int travellerSpawnMaxDist = 96;       // from player
    public static int travellerSpawnLightMin = 8;       // same as your getCanSpawnHere

    public static boolean despawnWhenFarFromPlayers = true;
    public static int farExtraChunks = 2;           // buffer beyond view radius (in chunks)
    public static int farGraceTicks = 15 * 20;      // must be far for ~15s to fade
    public static int farMinDistanceBlocks = 128;   // hard floor even if view radius is tiny

    // Raiders — soft-spawn target & pacing
    public static int raiderTargetLoaded = 8;          // keep far lower than travellers
    public static int raiderTopUpEveryTicks = 200;     // try every ~10s
    public static int raiderAttemptsPerPlayer = 1;     // 1 try per player each cycle
    public static float raiderSoftSpawnChancePerAttempt = 1.0F; // only 25% of tries attempt

    // Where to place them relative to players (reuse/adjust ring)
    public static int raiderSpawnMinDist = 64;
    public static int raiderSpawnMaxDist = 128;

    // Darkness rule (hostile vibe)
    public static int raiderMaxBlockLight = 8;         // require block light
    public static boolean enableTravellerSoftSpawns;
    public static boolean enableRaiderSoftSpawns;

    public static boolean campsEnabled = true;

    public static boolean travelTorchEnabled = true; // default on
    public static int travelTorchCooldownTicks = 20 * 10; // e.g. 10s after unequip
    public static double travelTorchNearbyRadius = 10.0D; // “someone else has light” radius
    public static boolean spawnTravellerWithDog = true;
    public static double travellerDogSpawnChance = 0.35; // 35%

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

        TravellerWeight = cfg.getInt(
                "TravellerWeight",
                "Traveller & Raider Gen",
                3, 0, 256,
                "Increase to make traveller spawn more often; set to 0 to disable traveller entity spawn."
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
                1, 1, 32,
                "Minimum group size when spawning"
        );
        MaxGroupSizeAtSpawn = cfg.getInt(
                "TravellerMaxGroupSizeAtSpawn",
                "Traveller & Raider Gen",
                6, 1, 32,
                "Maximum group size when traveller spawning"

        );
        loadedRaiderCap  = cfg.getInt(
                "loadedRaiderCap", "Traveller & Raider Gen",
                64, 1, 256,
                "Won't naturally spawn raiders if CURRENTLY LOADED travellers are more than this value."
        );
        RaiderWeight = cfg.getInt(
                "RaiderWeight", "Traveller & Raider Gen",
                3, 0, 256,
                "Spawn weight for raider travellers (set 0 to disable raider spawns)."
        );
        RaiderMinGroup = cfg.getInt(
                "RaiderMinGroup", "Traveller & Raider Gen",
                1, 1, 32,
                "Minimum group size for raider spawns."
        );
        RaiderMaxGroup = cfg.getInt(
                "RaiderMaxGroup", "Traveller & Raider Gen",
                4, 1, 32,
                "Maximum group size for raider spawns (must be >= min)."
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
                30.0f, 1.0f, 1000.0f,
                "Max health of traveller (20 = player default)."
        );



        movementSpeed = cfg.getFloat(
                "MovementSpeedBase", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Base speed mult."
        );
        movementSpeedWhileFollowing = cfg.getFloat(
                "MovementSpeedWhileFollowing", "Traveller base attributes",
                0.56f, 0.05f, 2.0f,
                "Movement speed multiplier while following"
        );
        movementSpeedWhileTravel = cfg.getFloat(
                "MovementSpeedWhileTravelling", "Traveller base attributes",
                0.52f, 0.05f, 2.0f,
                "Movement speed multiplier for travel task."
        );
        attackMovementSpeed = cfg.getFloat(
                "MovementSpeedWhileAttacking", "Traveller base attributes",
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
                "VillageNearDistance", "destination tweaks",
                64, 1, 256,
                "Distance (in blocks) considered 'arrived' at a village."
        );
        // --- TRAVELLER TUNING (new) ---
        villageRadius = cfg.getInt(
                "VillageRoamArea", "destination tweaks",
                64, 1, 256,
                "Area size to roam in village (has to be less than VillageNearDistance)"
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
                "playerCalmForgiveTicks", "Traveller combat tweaks", playerCalmForgiveTicks, 20, 20*60,
                "Ticks a player must go without hitting anything before travellers drop aggro."
        );
        playerCalmNotifyRadius = cfg.getFloat(
                "playerCalmNotifyRadius", "Traveller combat tweaks", (float)playerCalmNotifyRadius, 4f, 64f,
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
                0.90f, 0.0f, 1.0f,
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

// === SOFT SPAWN — TRAVELLERS ===
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
                travelTorchEnabled,   // default true
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