package com.jubitus.travellers.config;

import net.minecraftforge.common.config.Config;

public class TravellersConfig {

    public static final General GENERAL = new General();
    public static final Rendering RENDERING = new Rendering();
    public static final Spawn SPAWN = new Spawn();
    public static final SpawnTravellers SPAWN_TRAVELLERS = new SpawnTravellers();
    public static final SpawnRaiders SPAWN_RAIDERS = new SpawnRaiders();
    public static final SpawnWorkers SPAWN_WORKERS = new SpawnWorkers();
    public static final Despawn DESPAWN = new Despawn();
    public static final Attributes ATTRIBUTES = new Attributes();
    public static final Combat COMBAT = new Combat();
    public static final PlayerAggro PLAYER_AGGRO = new PlayerAggro();
    public static final Follow FOLLOW = new Follow();
    public static final Destinations DESTINATIONS = new Destinations();
    public static final Offscreen OFFSCREEN = new Offscreen();
    public static final Travel TRAVEL = new Travel();
    public static final Sound SOUND = new Sound();
    public static final Choir CHOIR = new Choir();
    public static final Camp CAMP = new Camp();
    public static final Fishing FISHING = new Fishing();
    public static final ControlledVillage CONTROLLED_VILLAGE = new ControlledVillage();
    public static final Worker WORKER = new Worker();
    public static final Recruitment RECRUITMENT = new Recruitment();
    public static final Merchant MERCHANT = new Merchant();
    public static final MobFarm MOB_FARM = new MobFarm();
    public static final Filters FILTERS = new Filters();
    public static final AggroBlacklist AGGRO_BLACKLIST = new AggroBlacklist();
    public static final AttackRules ATTACK_RULES = new AttackRules();

    public static class General {
        @Config.Comment("Enable traveller entity (disable = no travellers spawn at all).")
        public boolean enableTravellerEntity = true;

        @Config.Comment("Enable raider traveller variant.")
        public boolean enableTravellerRaider = true;

        @Config.Comment("Enable debug logging.")
        public boolean debug = false;

        @Config.Comment("Enable MPB road-routing debug logs (route planning and road-following events).")
        public boolean mpbDebugLog = false;

        @Config.Comment("Allow travellers to open fence gates.")
        public boolean enableTravellerOpenFenceGates = true;

        @Config.Comment("Allow travellers to open doors.")
        public boolean enableTravellerOpenDoors = true;
    }

    public static class Rendering {
        @Config.Comment("Show name tags above travellers heads.")
        public boolean showTravellerNames = false;

        @Config.Comment("How often (in ticks) the normal map tiles (64x64) are generated. Lower = faster generation, higher CPU cost. Default: 20 (1s).")
        @Config.RangeInt(min = 1, max = 200)
        public int mapTileGenIntervalTicks = 20;

        @Config.Comment("How often (in ticks) the normal map tiles are refreshed to reflect terrain changes. Default: 10 (0.5s).")
        @Config.RangeInt(min = 1, max = 200)
        public int mapTileRefreshIntervalTicks = 10;

        @Config.Comment("How often (in ticks) detail map tiles (256x256, 1px=1block) are generated. Lower = faster, higher CPU cost. Default: 40 (2s).")
        @Config.RangeInt(min = 1, max = 400)
        public int mapDetailGenIntervalTicks = 40;

        @Config.Comment("Max pixels filled per detail tile generation run. Higher = faster fill, higher CPU spike. Default: 4096.")
        @Config.RangeInt(min = 64, max = 65536)
        public int mapDetailGenPixelsPerRun = 4096;

        @Config.Comment("How often (in ticks) detail tiles are refreshed to reflect terrain changes. Default: 20 (1s).")
        @Config.RangeInt(min = 1, max = 400)
        public int mapDetailRefreshIntervalTicks = 20;

        @Config.Comment("Pixels re-sampled per detail tile per refresh run. Higher = faster terrain updates, higher CPU cost. Default: 2048.")
        @Config.RangeInt(min = 16, max = 65536)
        public int mapDetailRefreshPixelsPerRun = 2048;
    }

    public static class Spawn {
        @Config.Comment("Radius (blocks) around a Millenaire village that influences traveller spawn weight.")
        @Config.RangeInt(min = 0, max = 5000)
        public int travellerVillageInfluenceRadius = 1000;

        @Config.Comment("Radius (blocks) around a lone building that influences traveller spawn weight.")
        @Config.RangeInt(min = 0, max = 5000)
        public int travellerLoneInfluenceRadius = 600;

        @Config.Comment("Strength of village influence on spawn weight (1.0 = normal).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerVillageWeight = 0.3f;

        @Config.Comment("Strength of lone building influence on spawn weight.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerLoneWeight = 0.2f;

        @Config.Comment("Falloff curve shape: 1 = linear, >1 = steeper near edge, <1 = flatter.")
        @Config.RangeDouble(min = 0.1, max = 10.0)
        public float travellerFalloffPower = 1.0f;

        @Config.Comment("Max combined density when multiple village halos overlap.")
        @Config.RangeDouble(min = 0.1, max = 50.0)
        public float travellerMaxDensity = 1.2f;

        @Config.Comment("Baseline spawn density outside of village/lonebuilding influence (0 = none).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerOutsideVillageBaseDensity = 0.10f;

        @Config.Comment("If true, travellers may spawn with a dog companion.")
        public boolean spawnTravellerWithDog = false;

        @Config.Comment("Chance (0.0–1.0) that a traveller spawns with a dog companion.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float travellerDogSpawnChance = 0.0085f;

        @Config.Comment("Enable population-based spawn weight modifier.")
        public boolean travellerPopulationWeightEnabled = true;

        @Config.Comment("Radius (blocks) to search for the host village when computing population weight.")
        @Config.RangeInt(min = 64, max = 10000)
        public int travellerPopulationWeightRadius = 700;

        @Config.Comment("Extra spawn weight per villager (0.05 = +5% per villager).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerPopulationWeightPerVillager = 0.05f;

        @Config.Comment("Max overall spawn weight multiplier from population.")
        @Config.RangeDouble(min = 0.1, max = 10.0)
        public float travellerPopulationWeightMaxMultiplier = 1.2f;

        @Config.Comment("Enable culture-weighted spawning (cultures near spawn get higher weight).")
        public boolean travellerCultureWeightedSpawn = true;

        @Config.Comment("Radius (blocks) to scan for village cultures when picking spawn culture.")
        @Config.RangeInt(min = 64, max = 10000)
        public int travellerCultureScanRadius = 1024;

        @Config.Comment("If true, cultures with larger populations get more spawn weight.")
        public boolean travellerCultureWeightUseVillagePopulation = true;
    }

    public static class SpawnTravellers {
        @Config.Comment("Max concurrently loaded travellers before soft spawner stops.")
        @Config.RangeInt(min = 2, max = 2048)
        public int loadedTravellerCap = 64;

        @Config.Comment("Minimum group size when spawning travellers.")
        @Config.RangeInt(min = 1, max = 32)
        public int minGroupSizeAtSpawn = 2;

        @Config.Comment("Maximum group size when spawning travellers.")
        @Config.RangeInt(min = 1, max = 32)
        public int maxGroupSizeAtSpawn = 12;

        @Config.Comment("Enable soft spawner for travellers.")
        public boolean enableTravellerSoftSpawns = true;

        @Config.Comment("Soft target for concurrently loaded travellers across all dimensions.")
        @Config.RangeInt(min = 0, max = 500)
        public int travellerTargetLoaded = 48;

        @Config.Comment("How often (ticks) to attempt soft-spawning travellers.")
        @Config.RangeInt(min = 1, max = 36000)
        public int travellerTopUpEveryTicks = 300;

        @Config.Comment("Spawn attempts per player each soft-spawn cycle.")
        @Config.RangeInt(min = 0, max = 20)
        public int travellerAttemptsPerPlayer = 1;

        @Config.Comment("Minimum spawn distance from a player (blocks).")
        @Config.RangeInt(min = 8, max = 512)
        public int travellerSpawnMinDist = 64;

        @Config.Comment("Maximum spawn distance from a player (blocks).")
        @Config.RangeInt(min = 16, max = 1024)
        public int travellerSpawnMaxDist = 128;

        @Config.Comment("Minimum block light level at the spawn position.")
        @Config.RangeInt(min = 0, max = 15)
        public int travellerSpawnLightMin = 8;

        @Config.Comment("Max random positions tested per soft-spawn attempt before giving up.")
        @Config.RangeInt(min = 1, max = 32)
        public int travellerMaxPositionChecksPerAttempt = 4;

        @Config.Comment("Base chance per anchor attempt, before density influence (0.0–1.0).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float travellerBaseSpawnChance = 0.6f;

        @Config.Comment("Travellers per villager for dynamic world cap computation.")
        @Config.RangeDouble(min = 0.01, max = 10.0)
        public float travellerCapPerVillager = 1.0f / 3.0f;

        @Config.Comment("Minimum travellers each village contributes to dynamic cap.")
        @Config.RangeInt(min = 0, max = 64)
        public int travellerCapMinPerVillage = 2;

        @Config.Comment("Maximum travellers each village contributes to dynamic cap.")
        @Config.RangeInt(min = 0, max = 64)
        public int travellerCapMaxPerVillage = 8;

        @Config.Comment("Travellers each lone building contributes to dynamic cap.")
        @Config.RangeInt(min = 0, max = 4)
        public int travellerCapPerLoneBuilding = 1;

        @Config.Comment("Global minimum for dynamic world traveller target.")
        @Config.RangeInt(min = 0, max = 128)
        public int travellerCapGlobalMin = 4;

        @Config.Comment("Global maximum for dynamic world traveller target.")
        @Config.RangeInt(min = 0, max = 256)
        public int travellerCapGlobalMax = 1024;

        @Config.Comment("Radius (blocks) for regional traveller cap computation around a player.")
        @Config.RangeInt(min = 128, max = 8000)
        public int travellerRegionalRadius = 800;

        @Config.Comment("Max travellers within local cap radius.")
        @Config.RangeInt(min = 1, max = 512)
        public int travellerLocalCap = 64;

        @Config.Comment("Radius for local traveller cap check (blocks).")
        @Config.RangeInt(min = 32, max = 2048)
        public int travellerLocalCapRadius = 192;

        @Config.Comment("Permille of eligible spawn attempts that actually place a traveller.")
        @Config.RangeInt(min = 0, max = 1000)
        public int travellerSpawnPermille = 120;
    }

    public static class SpawnRaiders {
        @Config.Comment("Max concurrently loaded raiders before soft spawner stops.")
        @Config.RangeInt(min = 1, max = 256)
        public int loadedRaiderCap = 64;

        @Config.Comment("Minimum group size when spawning raiders.")
        @Config.RangeInt(min = 1, max = 32)
        public int raiderMinGroup = 2;

        @Config.Comment("Maximum group size when spawning raiders.")
        @Config.RangeInt(min = 1, max = 32)
        public int raiderMaxGroup = 8;

        @Config.Comment("Enable soft spawner for raiders.")
        public boolean enableRaiderSoftSpawns = true;

        @Config.Comment("Soft target for concurrently loaded raiders across all dimensions.")
        @Config.RangeInt(min = 0, max = 200)
        public int raiderTargetLoaded = 16;

        @Config.Comment("How often (ticks) to attempt soft-spawning raiders.")
        @Config.RangeInt(min = 1, max = 36000)
        public int raiderTopUpEveryTicks = 400;

        @Config.Comment("Spawn attempts per player each soft-spawn cycle for raiders.")
        @Config.RangeInt(min = 0, max = 10)
        public int raiderAttemptsPerPlayer = 2;

        @Config.Comment("Chance per soft-spawn attempt that raiders are actually placed (0.0–1.0).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float raiderSoftSpawnChancePerAttempt = 1.0f;

        @Config.Comment("Minimum spawn distance from a player for raiders (blocks).")
        @Config.RangeInt(min = 8, max = 1024)
        public int raiderSpawnMinDist = 96;

        @Config.Comment("Maximum spawn distance from a player for raiders (blocks).")
        @Config.RangeInt(min = 16, max = 2048)
        public int raiderSpawnMaxDist = 320;

        @Config.Comment("Minimum distance from Millenaire village centers where raiders cannot soft-spawn.")
        @Config.RangeInt(min = 16, max = 512)
        public int raiderMinVillageDistance = 160;

        @Config.Comment("Base chance per raider soft-spawn attempt before density multiplier (0.0–1.0).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float raiderBaseSpawnChance = 0.65f;

        @Config.Comment("Raiders per villager for dynamic regional cap computation.")
        @Config.RangeDouble(min = 0.01, max = 10.0)
        public float raiderCapPerVillager = 0.15f;

        @Config.Comment("Minimum raiders each village contributes to regional cap.")
        @Config.RangeInt(min = 0, max = 32)
        public int raiderCapMinPerVillage = 1;

        @Config.Comment("Maximum raiders each village contributes to regional cap.")
        @Config.RangeInt(min = 0, max = 32)
        public int raiderCapMaxPerVillage = 12;

        @Config.Comment("Raiders each lone building contributes to regional cap.")
        @Config.RangeInt(min = 0, max = 4)
        public int raiderCapPerLoneBuilding = 1;

        @Config.Comment("Radius (blocks) for regional raider cap computation around a player.")
        @Config.RangeInt(min = 128, max = 8000)
        public int raiderRegionalRadius = 600;

        @Config.Comment("Max raiders within local cap radius.")
        @Config.RangeInt(min = 1, max = 512)
        public int raiderLocalCap = 64;

        @Config.Comment("Radius for local raider cap check (blocks).")
        @Config.RangeInt(min = 32, max = 2048)
        public int raiderLocalCapRadius = 192;

        @Config.Comment("Max random positions tested per raider soft-spawn attempt.")
        @Config.RangeInt(min = 1, max = 32)
        public int raiderMaxPositionChecksPerAttempt = 4;
    }

    public static class SpawnWorkers {
        @Config.Comment("Minimum Millenaire population before a village can have any workers.")
        @Config.RangeInt(min = 0, max = 1024)
        public int workerMinPop = 10;

        @Config.Comment("Extra population required per additional worker beyond the first.")
        @Config.RangeInt(min = 1, max = 1024)
        public int workerPopPerWorker = 6;

        @Config.Comment("Maximum workers assigned to a single village.")
        @Config.RangeInt(min = 0, max = 16)
        public int workerMaxPerVillage = 8;

        @Config.Comment("How often (ticks) villages are re-scanned for worker assignment.")
        @Config.RangeInt(min = 1, max = 72000)
        public int workerRecheckEveryTicks = 200;

        @Config.Comment("Max concurrently loaded workers in this world (0 = no limit).")
        @Config.RangeInt(min = 0, max = 2048)
        public int workerLoadedCap = 1024;

        @Config.Comment("Max concurrently loaded merchants in this world (0 = no limit).")
        @Config.RangeInt(min = 0, max = 1024)
        public int merchantLoadedCap = 0;

        @Config.Comment("Minimum Millenaire village population for merchants to appear.")
        @Config.RangeInt(min = 0, max = 10000)
        public int merchantMinPop = 200;

        @Config.Comment("Extra population required per additional merchant.")
        @Config.RangeInt(min = 1, max = 10000)
        public int merchantPopPerMerchant = 10;

        @Config.Comment("Maximum merchants per village.")
        @Config.RangeInt(min = 0, max = 256)
        public int merchantMaxPerVillage = 0;

        @Config.Comment("How often (ticks) merchant logic rechecks villages.")
        @Config.RangeInt(min = 20, max = 72000)
        public int merchantRecheckEveryTicks = 200;
    }

    public static class Despawn {
        @Config.Comment("Despawn entities near the edge of loaded chunks after a grace period.")
        public boolean despawnNearUnloadedChunks = true;

        @Config.Comment("Despawn entities far beyond any player's view radius.")
        public boolean despawnWhenFarFromPlayers = true;

        @Config.Comment("Unused — kept for config compatibility only.")
        @Config.RangeInt(min = 0, max = 16)
        public int farExtraChunks = 0;

        @Config.Comment("How long (ticks) an entity must remain outside view distance before despawning.")
        @Config.RangeInt(min = 0, max = 120000)
        public int farGraceTicks = 100;

        @Config.Comment("Hard minimum distance (blocks) for the far-despawn check.")
        @Config.RangeInt(min = 16, max = 1024)
        public int farMinDistanceBlocks = 64;

        @Config.Comment("Fallback server view distance (chunks) used when real value is unavailable.")
        @Config.RangeInt(min = 2, max = 32)
        public int serverViewDistanceFallbackChunks = 8;

        @Config.Comment("Chunks around entity that must all be loaded to suppress edge despawn.")
        @Config.RangeInt(min = 0, max = 4)
        public int despawnProbeRadiusChunks = 1;

        @Config.Comment("Ticks standing near chunk edge before the entity fades out.")
        @Config.RangeInt(min = 0, max = 1200)
        public int despawnEdgeGraceTicks = 60;
    }

    public static class Attributes {
        @Config.Comment("Max health of generic travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public float travellerMaxHealth = 20.0f;

        @Config.Comment("Base movement speed multiplier.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeed = 0.52f;

        @Config.Comment("Movement speed multiplier while following.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeedWhileFollowing = 0.55f;

        @Config.Comment("Movement speed multiplier during travel task.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeedWhileTravel = 0.52f;

        @Config.Comment("Movement speed multiplier while attacking.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeedWhileAttacking = 0.56f;

        @Config.Comment("FOLLOW_RANGE (detection radius) for generic travellers.")
        @Config.RangeDouble(min = 4.0, max = 256.0)
        public float travellerFollowRange = 64.0f;

        @Config.Comment("Base melee damage per hit.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public float attackDamage = 2.0f;

        @Config.Comment("Global damage multiplier for travellers hit by mobs (not players). 0 = immune.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerDamageScale = 0.6f;

        @Config.Comment("Multiplier on all damage travellers deal to players.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerPlayerDamageMultiplier = 0.75f;

        @Config.Comment("Max health of raider travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public float raiderMaxHealth = 20.0f;

        @Config.Comment("Max health of merchant travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public float merchantMaxHealth = 40.0f;

        @Config.Comment("FOLLOW_RANGE for merchants.")
        @Config.RangeDouble(min = 4.0, max = 256.0)
        public float merchantFollowRange = 24.0f;

        @Config.Comment("Blocks per path step for merchant travel.")
        @Config.RangeInt(min = 1, max = 128)
        public int merchantStepBlocks = 16;

        @Config.Comment("Chunk radius to keep loaded around a merchant.")
        @Config.RangeInt(min = 1, max = 10)
        public int merchantChunkRadius = 1;

        @Config.Comment("Hard cap on forced chunks per world for merchants.")
        @Config.RangeInt(min = 1, max = 2048)
        public int merchantMaxForcedChunks = 256;

        @Config.Comment("Only the first N merchants get chunk-loading rights.")
        @Config.RangeInt(min = 0, max = 4096)
        public int merchantMaxChunkloadingMerchants = 1024;

        @Config.Comment("Enable merchant chunk-loading.")
        public boolean merchantChunkloadingEnabled = true;

        @Config.Comment("Max health of worker travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public double workerMaxHealth = 40.0;

        @Config.Comment("FOLLOW_RANGE for workers.")
        @Config.RangeDouble(min = 4.0, max = 256.0)
        public double workerFollowRange = 64.0;

        @Config.Comment("Chunk radius to keep loaded around a worker.")
        @Config.RangeInt(min = 1, max = 10)
        public int workerChunkRadius = 1;

        @Config.Comment("Blocks per path step for worker travel.")
        @Config.RangeInt(min = 1, max = 64)
        public int workerStepBlocks = 16;

        @Config.Comment("Blocks per path step for normal traveller travel.")
        @Config.RangeInt(min = 1, max = 128)
        public int stepBlocks = 24;

        @Config.Comment("Global cap on total assigned worker orders across the world.")
        @Config.RangeInt(min = 1, max = 65536)
        public int workerGlobalAssignedCap = 4096;
    }

    public static class Combat {
        @Config.Comment("If true, travellers call nearby allies for help when attacked.")
        public boolean callForHelp = true;

        @Config.Comment("Max range (blocks) allies respond to a help call.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public float helpRange = 24.0f;

        @Config.Comment("Ticks after combat ends before sword is hidden.")
        @Config.RangeInt(min = 0, max = 2000)
        public int swordHideDelay = 120;

        @Config.Comment("Health fraction at or below which travellers eat food (0.0–1.0).")
        @Config.RangeDouble(min = 0.1, max = 1.0)
        public float eatHealthThreshold = 0.5f;

        @Config.Comment("Max range (blocks) travellers will attack a hostile mob.")
        @Config.RangeInt(min = 1, max = 200)
        public int attackTriggerRange = 24;

        @Config.Comment("Health fraction below which entities enter defensive mode.")
        @Config.RangeDouble(min = 0.05, max = 0.50)
        public float lowHealthCombatFrac = 0.20f;

        @Config.Comment("Max engagement radius for workers in anchor/stay mode.")
        @Config.RangeInt(min = 3, max = 64)
        public int anchorModeEngageRadius = 12;

        @Config.Comment("Max engagement radius for entities in low-health defensive mode.")
        @Config.RangeInt(min = 2, max = 16)
        public int lowHealthEngageRadius = 5;

        @Config.Comment("Require a valid path before acquiring aggro on a target.")
        public boolean requirePathForAggro = true;

        @Config.Comment("Require line-of-sight for memory targets (recalled after a break).")
        public boolean requireLOSForMemoryTargets = true;

        @Config.Comment("Require line-of-sight when chaining aggro from allies.")
        public boolean requireLOSForChainTargets = true;

        @Config.Comment("View radius (blocks) for off-screen traveller entities.")
        @Config.RangeDouble(min = 16.0, max = 1024.0)
        public double travellerOffscreenViewRadius = 256.0;

        @Config.Comment("Base view boost radius (blocks).")
        @Config.RangeDouble(min = 0.0, max = 512.0)
        public double travellerViewBoostBaseRadius = 128.0;

        @Config.Comment("Additional view bonus (blocks) while in combat.")
        @Config.RangeDouble(min = 0.0, max = 512.0)
        public double travellerViewBoostCombatBonus = 128.0;

        @Config.Comment("Minimum distance before travellers uses a bow.")
        @Config.RangeDouble(min = 1.0, max = 64.0)
        public float rangedMinDist = 6.0f;

        @Config.Comment("Maximum distance travellers will attempt to shoot.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public float rangedMaxDist = 24.0f;

        @Config.Comment("Ticks between ranged attacks (20 = 1 second).")
        @Config.RangeInt(min = 1, max = 200)
        public int rangedCooldown = 40;

        @Config.Comment("Base arrow damage in half-hearts.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public float rangedBaseDamage = 6.0f;

        @Config.Comment("Multiplier applied to ranged damage.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float rangedDamageMultiplier = 1.0f;

        @Config.Comment("If true, traveller weapons lose durability on each hit.")
        public boolean weaponDurabilityEnabled = true;

        @Config.Comment("If true, traveller armor loses durability when they take damage.")
        public boolean armorDurabilityEnabled = true;

        @Config.Comment("If true, melee attack cooldown is derived from weapon ATTACK_SPEED attribute.")
        public boolean weaponAttackSpeedEnabled = true;

        @Config.Comment("Attack cooldown (ticks) when no weapon or weapon has no speed modifier.")
        @Config.RangeInt(min = 1, max = 100)
        public int baseAttackCooldownTicks = 24;

        @Config.Comment("Minimum melee attack cooldown in ticks.")
        @Config.RangeInt(min = 1, max = 40)
        public int minAttackCooldownTicks = 24;

        @Config.Comment("Maximum melee attack cooldown in ticks.")
        @Config.RangeInt(min = 1, max = 100)
        public int maxAttackCooldownTicks = 76;

        @Config.Comment("Multiplier on the final attack cooldown (1.0 = as-is, 2.0 = twice slower).")
        @Config.RangeDouble(min = 0.1, max = 10.0)
        public float attackCooldownMultiplier = 2.25f;
    }

    public static class PlayerAggro {
        @Config.Comment("Ticks without hitting before travellers drop aggro on player.")
        @Config.RangeInt(min = 0, max = 1200)
        public int playerCalmForgiveTicks = 200;

        @Config.Comment("Radius around a player within which travellers notice the player's last hit.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public float playerCalmNotifyRadius = 18.0f;

        @Config.Comment("Ticks out-of-LOS/far before raiders give up the chase.")
        @Config.RangeInt(min = 0, max = 1200)
        public int playerFleeForgiveTicks = 160;

        @Config.Comment("If player is closer than this, raiders never forgive even without hits.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public float playerFleeKeepDistance = 24.0f;
    }

    public static class Follow {
        @Config.Comment("Detection radius (blocks) to decide to follow another traveller.")
        @Config.RangeDouble(min = 1.0, max = 64.0)
        public float followDefaultRange = 6.0f;

        @Config.Comment("Minimum follow duration in seconds.")
        @Config.RangeInt(min = 20, max = 20000)
        public int followDurationMin = 60;

        @Config.Comment("Maximum follow duration in seconds.")
        @Config.RangeInt(min = 20, max = 40000)
        public int followDurationMax = 320;

        @Config.Comment("Chance (0–1) a traveller follows when meeting another.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float followStartChance = 0.75f;

        @Config.Comment("Maximum followers per group leader (0 = unlimited).")
        @Config.RangeInt(min = 0, max = 256)
        public int followMaxFollowersPerLeader = 4;

        @Config.Comment("Speed for boat convoy followers.")
        @Config.RangeDouble(min = 0.1, max = 20.0)
        public double followerBoatConvoySpeed = 4.0;

        @Config.Comment("Base ring radius (blocks) for formation slot placement.")
        @Config.RangeDouble(min = 0.5, max = 20.0)
        public double followerRingMoveBaseRadius = 2.2;

        @Config.Comment("Radius step between formation ring slots.")
        @Config.RangeDouble(min = 0.05, max = 5.0)
        public double followerRingMoveSlotStep = 0.25;

        @Config.Comment("Distance at which follower switches back from return-to-leader to normal follow.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public double returnFollowSwitchDist = 32.0;

        @Config.Comment("Ticks between formation chunk recomputation.")
        @Config.RangeInt(min = 1, max = 100)
        public int chunkRecomputeIntervalTicks = 5;
    }

    public static class Destinations {
        @Config.Comment("Distance (blocks) considered 'arrived' at a village.")
        @Config.RangeInt(min = 1, max = 256)
        public int villageNear = 16;

        @Config.Comment("Roam area size inside a village (blocks, should be <= villageNear).")
        @Config.RangeInt(min = 1, max = 256)
        public int villageRadius = 32;

        @Config.Comment("Distance (blocks) considered 'arrived' at a lone building.")
        @Config.RangeInt(min = 1, max = 256)
        public int lonebuildingNear = 16;

        @Config.Comment("Max radius (blocks) within which travellers pick next village destination.")
        @Config.RangeInt(min = 100, max = 10000)
        public int villagePickRadius = 4000;

        @Config.Comment("Minimum roam duration at a village (seconds).")
        @Config.RangeInt(min = 0, max = 24000)
        public int villageRoamMinDuration = 10;

        @Config.Comment("Maximum roam duration at a village (seconds).")
        @Config.RangeInt(min = 0, max = 24000)
        public int villageRoamMaxDuration = 30;
    }

    public static class Offscreen {
        @Config.Comment("Enable off-screen hop (periodic teleport along path when out of view).")
        public boolean travellerOffscreenHopEnabled = true;

        @Config.Comment("Ticks between off-screen hops.")
        @Config.RangeInt(min = 0, max = 72000)
        public int travellerOffscreenHopIntervalTicks = 140;

        @Config.Comment("Teleport distance (blocks) per off-screen hop.")
        @Config.RangeInt(min = 0, max = 1000)
        public int travellerOffscreenHopStepBlocks = 24;

        @Config.Comment("When true, travellers ignore mobs and incoming attacks while off-screen.")
        public boolean travellerNoAggroOffscreen = false;

        @Config.Comment("Damage multiplier for travellers when off-screen (0 = immune, 1 = normal).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float travellerOffscreenDamageScale = 0.2f;

        @Config.Comment("Use simplified travel logic when off-screen.")
        public boolean travellerSimpleOffscreenTravel = false;

        @Config.Comment("Minimum player distance for simple off-screen travel to activate.")
        @Config.RangeDouble(min = 0.0, max = 1024.0)
        public double travellerSimpleOffscreenMinPlayerDist = 64.0;

        @Config.Comment("Allow travellers to chunk-load areas along their path.")
        public boolean allowTravellerChunkLoading = true;
    }

    public static class Travel {
        @Config.Comment("If true, travellers can carry torches while travelling at night.")
        public boolean travelTorchEnabled = false;

        @Config.Comment("Cooldown (ticks) after unequipping travel torch before it can be used again.")
        @Config.RangeInt(min = 0, max = 72000)
        public int travelTorchCooldownTicks = 200;

        @Config.Comment("Radius (blocks) within which a nearby light source counts as existing cover.")
        @Config.RangeDouble(min = 0.0, max = 128.0)
        public float travelTorchNearbyRadius = 20.0f;

        @Config.Comment("If true, travellers may fish while stopped near water.")
        public boolean fishWhileTravelEnabled = true;
    }

    public static class Sound {
        @Config.Comment("If true, travellers play a voice line when right-clicked.")
        public boolean travellerSpeaks = true;

        @Config.Comment("If true, travellers occasionally mutter while travelling.")
        public boolean travellerAmbient = true;

        @Config.Comment("Minimum ticks between solo ambient voice lines.")
        @Config.RangeInt(min = 40, max = 20000)
        public int travellerAmbientMinDelay = 400;

        @Config.Comment("Maximum ticks between solo ambient voice lines.")
        @Config.RangeInt(min = 80, max = 60000)
        public int travellerAmbientMaxDelay = 1200;

        @Config.Comment("Volume for solo ambient travel voice lines. Values above 1.0 extend audible range with SoundPhysics.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float travellerAmbientVolume = 0.8f;

        @Config.Comment("Volume for choir/group singing. Values above 1.0 extend audible range with SoundPhysics.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float travellerChoirVolume = 1.0f;

        @Config.Comment("Volume for talk and hello voice lines. Values above 1.0 extend audible range with SoundPhysics.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float travellerTalkVolume = 4.0f;

        @Config.Comment("Volume for hurt sounds. Values above 1.0 extend audible range with SoundPhysics.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float travellerHurtVolume = 3.0f;

        @Config.Comment("Strength of footstep crowd limiting (0 = disabled, 1 = default, 2+ = stronger).")
        @Config.RangeDouble(min = 0.0, max = 3.0)
        public float travellerStepLimiterStrength = 1.5f;

        @Config.Comment("Strength of hurt sound crowd limiting (0 = disabled, 1 = default, 2+ = stronger).")
        @Config.RangeDouble(min = 0.0, max = 3.0)
        public float travellerHurtLimiterStrength = 1.5f;

        @Config.Comment("If true, travellers and raiders shout when engaging a new combat target.")
        public boolean attackSoundEnabled = true;

        @Config.Comment("Minimum ticks before the same entity can shout again.")
        @Config.RangeInt(min = 20, max = 6000)
        public int attackSoundEntityCooldownTicks = 60;

        @Config.Comment("Minimum ticks before another shout is allowed within the same 64-block zone (prevents group spam).")
        @Config.RangeInt(min = 40, max = 12000)
        public int attackSoundZoneCooldownTicks = 40;

        @Config.Comment("Volume for attack shout voice lines. Values above 1.0 extend audible range with SoundPhysics.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float attackSoundVolume = 4.0f;

        @Config.Comment("If true, nearby travellers (not raiders) warn the player when a creeper approaches from outside their field of view.")
        public boolean creeperWarnEnabled = true;

        @Config.Comment("Radius (blocks) in which travellers detect a threatening creeper near the player.")
        @Config.RangeDouble(min = 8.0, max = 64.0)
        public double creeperWarnCreeperRadius = 8.0;

        @Config.Comment("Max distance (blocks) between the warning traveller and the player.")
        @Config.RangeDouble(min = 4.0, max = 32.0)
        public double creeperWarnTravellerRadius = 12.0;

        @Config.Comment("Minimum ticks between two creeper warnings for the same player.")
        @Config.RangeInt(min = 100, max = 12000)
        public int creeperWarnCooldownTicks = 60;

        @Config.Comment("Volume for creeper warning voice lines. Values above 1.0 extend audible range with SoundPhysics.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float creeperWarnVolume = 3.0f;

        @Config.Comment("If true, travellers and raiders play a voice line when they kill an entity.")
        public boolean killSoundEnabled = true;

        @Config.Comment("Minimum ticks before the same entity can play a kill sound again.")
        @Config.RangeInt(min = 20, max = 6000)
        public int killSoundEntityCooldownTicks = 100;

        @Config.Comment("Minimum ticks before another kill sound is allowed within the same 64-block zone.")
        @Config.RangeInt(min = 40, max = 12000)
        public int killSoundZoneCooldownTicks = 60;

        @Config.Comment("Volume for kill voice lines. Values above 1.0 extend audible range with SoundPhysics.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float killSoundVolume = 4.0f;

        @Config.Comment("If true, travellers and raiders play a voice line when they die.")
        public boolean deathSoundEnabled = true;

        @Config.Comment("Minimum ticks before another death sound is allowed within the same 64-block zone.")
        @Config.RangeInt(min = 40, max = 12000)
        public int deathSoundZoneCooldownTicks = 60;

        @Config.Comment("Volume for death voice lines. Values above 1.0 extend audible range with SoundPhysics.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float deathSoundVolume = 4.0f;

        // --- Attenuation (no-SoundPhysics fallback) ---
        @Config.Comment("Distance (blocks) at which voice sounds play at full volume. Only used when SoundPhysics is absent.")
        @Config.RangeDouble(min = 0.5, max = 16.0)
        public double voiceReferenceDistance = 3.0;

        @Config.Comment("Distance (blocks) beyond which voice sounds become inaudible. Only used when SoundPhysics is absent.")
        @Config.RangeDouble(min = 8.0, max = 128.0)
        public double voiceMaxDistance = 48.0;

        @Config.Comment("Distance rolloff exponent when SoundPhysics is absent. 1.0 = natural 1/r (recommended), 2.0 = inverse-square (fast fade), 0.5 = gentle.")
        @Config.RangeDouble(min = 0.25, max = 4.0)
        public double voiceRolloffPower = 1.0;
    }

    public static class Choir {
        @Config.Comment("Enable the traveller choir system.")
        public boolean choirEnabled = true;

        @Config.Comment("Max radius (blocks) to detect nearby travellers for a choir group.")
        @Config.RangeDouble(min = 4.0, max = 48.0)
        public float choirGroupRadius = 12.0f;

        @Config.Comment("If a singer drifts this far from choir center, the session is flagged.")
        @Config.RangeDouble(min = 8.0, max = 96.0)
        public float choirBreakRadius = 25.0f;

        @Config.Comment("Probability per cooldown cycle that an auto-choir attempt is made (0.0–1.0).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float choirAutoChance = 0.4f;

        @Config.Comment("Minimum ticks between auto-choir session attempts per dimension.")
        @Config.RangeInt(min = 20, max = 144000)
        public int choirAutoCooldownTicks = 600;

        @Config.Comment("How long a choir session lasts in ticks.")
        @Config.RangeInt(min = 100, max = 36000)
        public int choirSessionDurationTicks = 3600;

        @Config.Comment("Consecutive spread-check failures before stopping the choir.")
        @Config.RangeInt(min = 1, max = 20)
        public int choirSpreadGraceChecks = 3;

        @Config.Comment("How often (ticks) the choir coordinator checks sessions.")
        @Config.RangeInt(min = 20, max = 400)
        public int choirTickInterval = 100;

        @Config.Comment("Volume multiplier for choir sounds. Also controls range: 1.0 ≈ 16 blocks, 3.0 ≈ 48 blocks.")
        @Config.RangeDouble(min = 0.1, max = 16.0)
        public float choirSoundVolumeMultiplier = 3.0f;
    }

    public static class Camp {
        @Config.Comment("If true, travellers will sometimes make camps with a firepit.")
        public boolean campsEnabled = true;

        @Config.Comment("Radius (blocks) around campfire where travellers gather.")
        @Config.RangeInt(min = 1, max = 32)
        public int campRadius = 8;

        @Config.Comment("How far (blocks) travellers look for nearby camps to join.")
        @Config.RangeInt(min = 1, max = 128)
        public int campSeekRadius = 12;

        @Config.Comment("Maximum travellers gathered around a single campfire.")
        @Config.RangeInt(min = 1, max = 32)
        public int campMaxJoinersPerCamp = 12;

        @Config.Comment("Minimum ticks between wander moves while at camp.")
        @Config.RangeInt(min = 1, max = 120000)
        public int campWanderMinDelay = 40;

        @Config.Comment("Maximum ticks between wander moves while at camp.")
        @Config.RangeInt(min = 1, max = 120000)
        public int campWanderMaxDelay = 140;

        @Config.Comment("Minimum ticks between look-around actions while at camp.")
        @Config.RangeInt(min = 1, max = 120000)
        public int campLookMinDelay = 30;

        @Config.Comment("Maximum ticks between look-around actions while at camp.")
        @Config.RangeInt(min = 1, max = 120000)
        public int campLookMaxDelay = 110;

        @Config.Comment("Per-tick chance (0–1) that a traveller starts a camp when conditions are met.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float campStartChancePerTick = 0.0008f;
    }

    public static class Fishing {
        @Config.Comment("Radius (blocks) to search for water around the traveller.")
        @Config.RangeInt(min = 1, max = 32)
        public int fishWaterScanRadius = 6;

        @Config.Comment("Minimum ticks before a fish can bite.")
        @Config.RangeInt(min = 1, max = 12000)
        public int fishMinTimeTicks = 300;

        @Config.Comment("Maximum ticks before a fish must bite.")
        @Config.RangeInt(min = 1, max = 12000)
        public int fishMaxTimeTicks = 800;

        @Config.Comment("Every this many ticks, traveller plays the reel animation.")
        @Config.RangeInt(min = 1, max = 1200)
        public int fishReelEveryTicks = 45;

        @Config.Comment("Wind-up ticks before the fishing cast actually happens.")
        @Config.RangeInt(min = 1, max = 200)
        public int fishCastWindupTicks = 15;

        @Config.Comment("Cooldown ticks before a traveller considers fishing again.")
        @Config.RangeInt(min = 0, max = 72000)
        public int fishCooldownTicks = 6000;

        @Config.Comment("Per-tick chance (0–1) a traveller decides to start fishing.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float fishStartChancePerTick = 0.002f;

        @Config.Comment("Movement speed while approaching fishing spot.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double fishApproachSpeed = 0.45;
    }

    public static class ControlledVillage {
        @Config.Comment("Enable controlled village integration (barracks building, hire restriction).")
        public boolean controlledVillageFeatureEnabled = true;

        @Config.Comment("If true, player must be chief of at least one Millenaire village to hire workers.")
        public boolean requireControlledVillageToHire = true;

        @Config.Comment("Worker slots per barracks building. Set 0 to disable hay bale requirement.")
        @Config.RangeInt(min = 0, max = 64)
        public int barracksSlotsPerBuilding = 5;

        @Config.Comment("Radius (blocks) of a worker barracks building area.")
        @Config.RangeInt(min = 4, max = 32)
        public int workerBarracksRadius = 10;

        @Config.Comment("If true, dead workers with a barracks home respawn there the next in-game day.")
        public boolean workerRespawnInBarracks = false;

        @Config.Comment("In-game day time (0-23999) when dead workers respawn at their barracks.")
        @Config.RangeInt(min = 0, max = 23999)
        public int workerRespawnMorningTime = 1000;

        @Config.Comment("If true, teenagers in controlled villages can become workers.")
        public boolean teenagerToWorkerEnabled = false;
    }

    public static class Worker {
        @Config.Comment("If true, hired workers without a barrack are automatically assigned to the nearest one.")
        public boolean autoAssignWorkersToBarracks = false;

        @Config.Comment("Number of in-game days before a dead worker respawns at their barrack (requires workerRespawnInBarracks).")
        @Config.RangeInt(min = 1, max = 30)
        public int workerBarrackRespawnDays = 3;

        @Config.Comment("Default half-width (X axis) for lone barracks detection.")
        @Config.RangeInt(min = 4, max = 16)
        public int workerLoneBarrackHalfWidth = 5;

        @Config.Comment("Default half-depth (Z axis) for lone barracks detection.")
        @Config.RangeInt(min = 4, max = 16)
        public int workerLoneBarrackHalfDepth = 5;

        @Config.Comment("Default half-height for lone barracks detection.")
        @Config.RangeInt(min = 2, max = 32)
        public int workerLoneBarrackHalfHeight = 4;

        @Config.Comment("Cost to hire a worker (in deniers). 1 argent = 64 deniers.")
        @Config.RangeInt(min = 0, max = 10000000)
        public int workerHireCostDeniers = 640;

        @Config.Comment("If true, workers send a chat message when their trade affects reputation.")
        public boolean workerNotifyOnVillageTrade = true;

        @Config.Comment("If true, workers automatically loot mobs they kill.")
        public boolean workerAutoLootMobs = true;

        @Config.Comment("Max radius (blocks) around worker home to search for delivery villages.")
        @Config.RangeInt(min = 128, max = 100000)
        public int workerVillageDetectRadius = 8000;

        @Config.Comment("Distance (blocks) considered 'arrived' at a Go-to destination.")
        @Config.RangeInt(min = 1, max = 512)
        public int workerGoArrivalRadius = 32;

        @Config.Comment("Idle anchor radius (blocks) after completing a Go order.")
        @Config.RangeDouble(min = 0.0, max = 64.0)
        public double workerIdleAnchorRadius = 2.0;

        @Config.Comment("Base travel cost (deniers) per used cargo slot for workerTravelDistanceBase blocks.")
        @Config.RangeDouble(min = 0.0, max = 100000.0)
        public float workerTravelBaseCostPerSlot = 20.0f;

        @Config.Comment("Reference distance (blocks) for per-slot travel cost computation.")
        @Config.RangeDouble(min = 1.0, max = 10000.0)
        public float workerTravelDistanceBase = 250.0f;

        @Config.Comment("Flat extra deniers added per trip regardless of distance or cargo.")
        @Config.RangeInt(min = 0, max = 100000)
        public int workerTravelFlatBaseCost = 320;

        @Config.Comment("Global multiplier on computed worker travel cost.")
        @Config.RangeDouble(min = 0.0, max = 1000.0)
        public float workerTravelCostMultiplier = 1.18f;

        @Config.Comment("Minimum block distance between merchant villages.")
        @Config.RangeInt(min = 100, max = 10000)
        public int merchantVillageMinDistance = 600;

        @Config.Comment("Minimum block distance between worker villages.")
        @Config.RangeInt(min = 100, max = 10000)
        public int workerVillageMinDistance = 250;
    }

    public static class Recruitment {
        @Config.Comment("Max 2D distance (blocks) from player when searching villages for recruitment.")
        @Config.RangeInt(min = 0, max = 30000)
        public int recruitMaxVillageRadius = 8000;

        @Config.Comment("Minimum villages searched per recruitment mission.")
        @Config.RangeInt(min = 1, max = 100)
        public int recruitVillagesMin = 1;

        @Config.Comment("Maximum villages searched per recruitment mission.")
        @Config.RangeInt(min = 1, max = 100)
        public int recruitVillagesMax = 20;

        @Config.Comment("Minimum workers spawned per visited village during recruitment.")
        @Config.RangeInt(min = 0, max = 64)
        public int recruitSpawnMin = 1;

        @Config.Comment("Maximum workers spawned per visited village during recruitment.")
        @Config.RangeInt(min = 0, max = 64)
        public int recruitSpawnMax = 4;

        @Config.Comment("Flat recruitment fee in deniers added once per mission.")
        @Config.RangeInt(min = 0, max = 1000000)
        public int recruitFeeFlat = 256;

        @Config.Comment("Additional fee per block of total 2D route distance.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public float recruitFeePerBlock = 0.07f;

        @Config.Comment("Additional fee per visited village (in deniers).")
        @Config.RangeInt(min = 0, max = 1000000)
        public int recruitFeePerVillage = 1280;

        @Config.Comment("Radius (blocks) to assign/clear escort leader.")
        @Config.RangeInt(min = 0, max = 100000)
        public int recruitLeaderAssignRadius = 12000;

        @Config.Comment("Minimum distance between recruitment target villages.")
        @Config.RangeInt(min = 0, max = 10000)
        public int recruitMinVillageDistance = 300;
    }

    public static class Merchant {
        @Config.Comment("Fraction of total required construction resources the merchant donates per visit.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float merchantGiveFraction = 0.01f;

        @Config.Comment("Minimum donation for bulk items (large stacks, e.g. planks).")
        @Config.RangeInt(min = 0, max = 64000)
        public int merchantMinBulkAmount = 8;

        @Config.Comment("Minimum donation for small-stack items (banners, tools, etc.).")
        @Config.RangeInt(min = 0, max = 64000)
        public int merchantMinSmallAmount = 1;

        @Config.Comment("How many different item types a merchant can donate per village visit.")
        @Config.RangeInt(min = 0, max = 64)
        public int merchantMaxDistinctItems = 1;
    }

    public static class MobFarm {
        @Config.Comment("If true, travellers avoid areas that look like mob farms.")
        public boolean enableMobFarmAvoidance = true;

        @Config.Comment("Radius (blocks) to check for mob farm entity density.")
        @Config.RangeInt(min = 1, max = 64)
        public int mobFarmCheckRadius = 6;

        @Config.Comment("Entity count threshold within mobFarmCheckRadius to flag as mob farm.")
        @Config.RangeInt(min = 1, max = 256)
        public int mobFarmEntityThreshold = 15;

        @Config.Comment("Radius (blocks) to check for fence density.")
        @Config.RangeInt(min = 1, max = 64)
        public int mobFarmFenceRadius = 4;

        @Config.Comment("Fence count threshold within mobFarmFenceRadius to flag as mob farm.")
        @Config.RangeInt(min = 1, max = 256)
        public int mobFarmFenceThreshold = 12;
    }

    public static class Filters {
        @Config.Comment("Villager txt tags excluded from traveller appearance pool. E.g.: child,chief,hostile")
        public String[] excludedVillagerTagsRaw = new String[]{"child", "chief", "hostile"};

        @Config.Comment("Villager txt filenames (without .txt) excluded from traveller pool.")
        public String[] excludedVillagerFilesRaw = new String[]{"alchemist", "alchemistapprentice", "alchemistassistant"};

        @Config.Comment("Villager tags included in raider appearance pool (empty = no restriction).")
        public String[] includedRaiderVillagerTagsRaw = new String[]{"hostile"};

        @Config.Comment("Villager filenames included in raider appearance pool (empty = no restriction).")
        public String[] includedRaiderVillagerFilesRaw = new String[]{};

        @Config.Comment("Villager tags included in worker appearance pool (empty = no restriction).")
        public String[] includedWorkerVillagerTagsRaw = new String[]{"helpInAttacks", "heavydrinker", "archer", "raider"};

        @Config.Comment("Villager filenames included in worker appearance pool (empty = no restriction).")
        public String[] includedWorkerVillagerFilesRaw = new String[]{};

        @Config.Comment("Villager tags included in merchant appearance pool (empty = no restriction).")
        public String[] includedMerchantVillagerTagsRaw = new String[]{"visitor"};

        @Config.Comment("Villager filenames included in merchant appearance pool (empty = no restriction).")
        public String[] includedMerchantVillagerFilesRaw = new String[]{};
    }

    public static class AggroBlacklist {
        @Config.Comment("Entity registry IDs that travellers will NOT aggro. Supports wildcards like modid:*")
        public String[] travellerAggroBlacklistIds = new String[]{"minecraft:creeper"};

        @Config.Comment("Fully-qualified class names that travellers will NOT aggro (subclasses match).")
        public String[] travellerAggroBlacklistClasses = new String[]{
                "net.minecraft.entity.monster.EntityGhast",
                "net.minecraft.entity.monster.EntityEnderman"
        };

        @Config.Comment("Entity registry IDs that raiders will NOT aggro. Supports wildcards like modid:*")
        public String[] raiderAggroBlacklistIds = new String[]{"minecraft:creeper", "minecraft:enderman"};

        @Config.Comment("Fully-qualified class names that raiders will NOT aggro (subclasses match).")
        public String[] raiderAggroBlacklistClasses = new String[]{};
    }

    public static class AttackRules {
        @Config.Comment("Entity IDs raiders will attack (exact or modid:*).")
        public String[] raiderAttackIdRules = new String[]{
                "millenaire:genericvillager",
                "millenaire:genericasimmfemale",
                "millenaire:genericsimmfemale"
        };

        @Config.Comment("Java class names raiders will attack (exact or subclasses).")
        public String[] raiderAttackClassRules = new String[]{};

        @Config.Comment("Entity IDs friendly travellers will treat as dangerous (exact or modid:*).")
        public String[] travellerAttackIdRules = new String[]{};

        @Config.Comment("Java class names friendly travellers will attack (exact or subclasses).")
        public String[] travellerAttackClassRules = new String[]{};
    }
}
