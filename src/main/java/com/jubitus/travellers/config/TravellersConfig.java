package com.jubitus.travellers.config;

import net.minecraftforge.common.config.Config;

public class TravellersConfig {

    public static final General GENERAL = new General();
    public static final Rendering RENDERING = new Rendering();
    public static final EntityRenderLod ENTITY_RENDER_LOD = new EntityRenderLod();
    public static final Spawn SPAWN = new Spawn();
    public static final SpawnTravellers SPAWN_TRAVELLERS = new SpawnTravellers();
    public static final SpawnRaiders SPAWN_RAIDERS = new SpawnRaiders();
    public static final SpawnWorkers SPAWN_WORKERS = new SpawnWorkers();
    public static final Despawn DESPAWN = new Despawn();
    public static final Attributes ATTRIBUTES = new Attributes();
    public static final Speed SPEED = new Speed();
    public static final Combat COMBAT = new Combat();
    public static final PlayerAggro PLAYER_AGGRO = new PlayerAggro();
    public static final WorkerFormation WORKER_FORMATION = new WorkerFormation();
    public static final Destinations DESTINATIONS = new Destinations();
    public static final Offscreen OFFSCREEN = new Offscreen();
    public static final Materialization MATERIALIZATION = new Materialization();
    public static final Travel TRAVEL = new Travel();
    public static final Sound SOUND = new Sound();
    public static final Choir CHOIR = new Choir();
    public static final Camp CAMP = new Camp();
    public static final CampEvents CAMP_EVENTS = new CampEvents();
    public static final BattleEvents BATTLE_EVENTS = new BattleEvents();
    public static final Fishing FISHING = new Fishing();
    public static final ControlledVillage CONTROLLED_VILLAGE = new ControlledVillage();
    public static final Worker WORKER = new Worker();
    public static final Recruitment RECRUITMENT = new Recruitment();
    public static final Merchant MERCHANT = new Merchant();
    public static final MobFarm MOB_FARM = new MobFarm();
    public static final Filters FILTERS = new Filters();
    public static final AggroBlacklist AGGRO_BLACKLIST = new AggroBlacklist();
    public static final AttackRules ATTACK_RULES = new AttackRules();
    public static final Notifications NOTIFICATIONS = new Notifications();

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

        @Config.Comment("Enable debug logging for follower teleport rescues.")
        public boolean debugFollowTeleport = false;
    }

    public static class Rendering {
        @Config.Comment("Show name tags above travellers heads.")
        public boolean showTravellerNames = false;

        @Config.Comment("Opportunistically fill the worldmap whenever a chunk loads (player exploring, worker travel, or a world pregeneration tool). Never affects per-player fog of war.")
        public boolean mapPregenOnChunkLoadEnabled = true;

        @Config.Comment("Max milliseconds per server tick spent processing chunk loads for the worldmap. Time-based (not a chunk count) so it automatically keeps up with a bulk world pregeneration tool loading many chunks at once, while staying safe for normal play. Default: 2ms.")
        @Config.RangeInt(min = 1, max = 50)
        public int mapChunkLoadBudgetMsPerTick = 2;

        @Config.Comment("Max worldmap overview tiles rebuilt per server tick when zooming out. These are cheap (no world access, just combining already-generated data) so a low number is rarely needed. Default: 8.")
        @Config.RangeInt(min = 1, max = 256)
        public int mapPyramidBudgetTilesPerTick = 8;

        @Config.Comment("How many currently-loaded chunks the worldmap re-checks per server tick, so terrain edits and growing villages eventually repaint. Only looks at chunks already loaded for other reasons - never keeps extra chunks loaded. Default: 4.")
        @Config.RangeInt(min = 1, max = 64)
        public int mapSweepChunksPerTick = 4;

        @Config.Comment("How often (in ticks) the worldmap rebuilds its list of currently-loaded chunks to re-check. Default: 100 (5s).")
        @Config.RangeInt(min = 20, max = 2000)
        public int mapSweepRebuildIntervalTicks = 100;

        @Config.Comment("How far (in blocks) a worldmap tile update is pushed to nearby players so their map redraws it. Default: 512.")
        @Config.RangeInt(min = 64, max = 4096)
        public int mapInvalidateNotifyRadiusBlocks = 512;

        @Config.Comment("Minimum time (in milliseconds) between two worldmap tile pushes to players for the same tile, at the most detailed zoom level. Zoomed-out tiles cover a much bigger area and are pushed even less often (this value is multiplied by how zoomed out that level is). Raise this if the map still flickers; lower it for faster updates at the cost of more network traffic. Default: 1000.")
        @Config.RangeInt(min = 100, max = 60000)
        public int mapPushMinIntervalMs = 1000;

        @Config.Comment("How many chunks per tick the '/travellersdebug map resweep' admin command processes. Higher = faster bulk correction, more server load while it runs. Default: 8.")
        @Config.RangeInt(min = 1, max = 64)
        public int mapResweepChunksPerTick = 8;

        @Config.Comment("Log detailed worldmap generation/refresh/network activity to the console. For diagnosing worldmap bugs only - very spammy, leave off otherwise.")
        public boolean debugMap = false;

        @Config.Comment("How often (in ticks) '/travellersdebug map resweep' reports its progress to the player who ran it. Default: 200 (10s).")
        @Config.RangeInt(min = 20, max = 2000)
        public int mapResweepProgressIntervalTicks = 200;

        @Config.Comment("Reveal the entire generated worldmap to every player, bypassing per-player fog of war. Only affects visibility of tiles already present in the shared tile cache; does not force generation of unexplored areas. Turning it back off restores each player's own fog of war exactly as it was. Applies the next time a player (re)opens the map.")
        public boolean showFullMap = false;

        @Config.Comment("Max worldmap tiles kept in memory per zoom level on the client. Higher = smoother panning/zooming with less re-fetching, more client RAM used. Default: 128.")
        @Config.RangeInt(min = 16, max = 2048)
        public int mapClientTileCacheSizePerLevel = 128;
    }

    public static class EntityRenderLod {
        @Config.Comment("Master switch for distance-based LOD on traveller/raider/worker entity rendering (cosmetic overlay layers + held item). Reduces GPU/CPU cost when many are visible at once. Purely cosmetic beyond the configured distances.")
        public boolean enabled = true;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the clothes overlay layer. This layer re-renders the full biped model a second time, so it's the most expensive of the three and invisible at range anyway.")
        @Config.RangeInt(min = 4, max = 128)
        public int clothesLayerMaxDistance = 32;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the owner-mark armband layer (workers only).")
        @Config.RangeInt(min = 4, max = 128)
        public int armbandLayerMaxDistance = 24;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the backpack/bamboo/chuspas layer (workers/merchants only).")
        @Config.RangeInt(min = 4, max = 128)
        public int backpackLayerMaxDistance = 24;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip rendering the held item (weapon/tool in hand) for travellers/raiders/workers. Highest-impact option since vanilla's 3D item rendering is the single most expensive part of entity rendering.")
        @Config.RangeInt(min = 4, max = 128)
        public int heldItemMaxDistance = 40;
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

        @Config.Comment("Radius (blocks) within which a position must have a village to be considered a valid traveller/raider spawn point.")
        @Config.RangeInt(min = 16, max = 4000)
        public int villageSpawnRadius = 128;
    }

    public static class SpawnTravellers {
        @Config.Comment("Hard cap on concurrently loaded traveller entities. Vanilla spawn checks respect this.")
        @Config.RangeInt(min = 2, max = 2048)
        public int loadedTravellerCap = 64;

        @Config.Comment("Travellers per villager for global population formula: target = max(pop * perVillager, minPerVillage).")
        @Config.RangeDouble(min = 0.01, max = 10.0)
        public float travellerCapPerVillager = 1.0f / 3.0f;

        @Config.Comment("Minimum travellers the global controller spawns per village, even if population is 0 or unloaded.")
        @Config.RangeInt(min = 0, max = 64)
        public int travellerCapMinPerVillage = 2;

        @Config.Comment("Maximum travellers the global controller spawns per village.")
        @Config.RangeInt(min = 0, max = 64)
        public int travellerCapMaxPerVillage = 8;

        @Config.Comment("Travellers the global controller spawns per lone building.")
        @Config.RangeInt(min = 0, max = 4)
        public int travellerCapPerLoneBuilding = 1;

        @Config.Comment("Global minimum traveller target across the whole world.")
        @Config.RangeInt(min = 0, max = 128)
        public int travellerCapGlobalMin = 4;

        @Config.Comment("Global maximum traveller target across the whole world.")
        @Config.RangeInt(min = 0, max = 256)
        public int travellerCapGlobalMax = 1024;

        @Config.Comment("Radius (blocks) used to compute regional traveller counts for /travellersinfo display.")
        @Config.RangeInt(min = 128, max = 8000)
        public int travellerRegionalRadius = 800;

        @Config.Comment("Max travellers within local cap radius (vanilla spawn guard).")
        @Config.RangeInt(min = 1, max = 512)
        public int travellerLocalCap = 64;

        @Config.Comment("Radius for local traveller cap check (blocks, vanilla spawn guard).")
        @Config.RangeInt(min = 32, max = 2048)
        public int travellerLocalCapRadius = 192;

        @Config.Comment("Chance (0-100) that a virtual traveller spawn event creates a group instead of a solo traveller.")
        @Config.RangeInt(min = 0, max = 100)
        public int groupSpawnChance = 35;

        @Config.Comment("Minimum group size when a traveller group spawns.")
        @Config.RangeInt(min = 2, max = 12)
        public int groupMinSize = 2;

        @Config.Comment("Maximum group size for traveller groups.")
        @Config.RangeInt(min = 2, max = 12)
        public int groupMaxSize = 4;

        @Config.Comment("Vanilla biome EntityRegistry.addSpawn weight for travellers (0 = disabled; travellers normally only spawn via the virtual population controller).")
        @Config.RangeInt(min = 0, max = 100)
        public int vanillaSpawnWeight = 0;

        @Config.Comment("Minimum group size for the vanilla biome spawn entry (only used if vanillaSpawnWeight > 0).")
        @Config.RangeInt(min = 1, max = 32)
        public int vanillaSpawnMinGroupSize = 2;

        @Config.Comment("Maximum group size for the vanilla biome spawn entry (only used if vanillaSpawnWeight > 0).")
        @Config.RangeInt(min = 1, max = 32)
        public int vanillaSpawnMaxGroupSize = 12;
    }

    public static class SpawnRaiders {
        @Config.Comment("Hard cap on concurrently loaded raider entities. Vanilla spawn checks respect this.")
        @Config.RangeInt(min = 1, max = 256)
        public int loadedRaiderCap = 64;

        @Config.Comment("Minimum distance (blocks) from any village center where raiders are forbidden to spawn virtually.")
        @Config.RangeInt(min = 16, max = 512)
        public int raiderMinVillageDistance = 160;

        @Config.Comment("Raiders per villager for global population formula: target = max(pop * perVillager, minPerVillage).")
        @Config.RangeDouble(min = 0.01, max = 10.0)
        public float raiderCapPerVillager = 0.15f;

        @Config.Comment("Minimum raiders the global controller spawns per village, even if population is 0 or unloaded.")
        @Config.RangeInt(min = 0, max = 32)
        public int raiderCapMinPerVillage = 1;

        @Config.Comment("Maximum raiders the global controller spawns per village.")
        @Config.RangeInt(min = 0, max = 32)
        public int raiderCapMaxPerVillage = 12;

        @Config.Comment("Raiders the global controller spawns per lone building.")
        @Config.RangeInt(min = 0, max = 4)
        public int raiderCapPerLoneBuilding = 1;

        @Config.Comment("Radius (blocks) used to compute regional raider counts for /travellersinfo display.")
        @Config.RangeInt(min = 128, max = 8000)
        public int raiderRegionalRadius = 600;

        @Config.Comment("Max raiders within local cap radius (vanilla spawn guard).")
        @Config.RangeInt(min = 1, max = 512)
        public int raiderLocalCap = 64;

        @Config.Comment("Radius for local raider cap check (blocks, vanilla spawn guard).")
        @Config.RangeInt(min = 32, max = 2048)
        public int raiderLocalCapRadius = 192;

        @Config.Comment("If true, raiders use the Ultreia/MPB road network for both real and virtual travel. False = raiders roam freely off-road.")
        public boolean raidersUseUltreiaPaths = false;

        @Config.Comment("Chance (0-100) that a virtual raider spawn event creates a group instead of a solo raider.")
        @Config.RangeInt(min = 0, max = 100)
        public int groupSpawnChance = 50;

        @Config.Comment("Minimum group size when a raider group spawns.")
        @Config.RangeInt(min = 2, max = 12)
        public int groupMinSize = 2;

        @Config.Comment("Maximum group size for raider groups.")
        @Config.RangeInt(min = 2, max = 12)
        public int groupMaxSize = 5;

        @Config.Comment("Vanilla biome EntityRegistry.addSpawn weight for raiders (0 = disabled; raiders normally only spawn via the virtual population controller).")
        @Config.RangeInt(min = 0, max = 100)
        public int vanillaSpawnWeight = 0;

        @Config.Comment("Minimum group size for the vanilla biome spawn entry (only used if vanillaSpawnWeight > 0).")
        @Config.RangeInt(min = 1, max = 32)
        public int vanillaSpawnMinGroupSize = 2;

        @Config.Comment("Maximum group size for the vanilla biome spawn entry (only used if vanillaSpawnWeight > 0).")
        @Config.RangeInt(min = 1, max = 32)
        public int vanillaSpawnMaxGroupSize = 8;
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

        @Config.Comment("Workers the global population controller spawns per lone building.")
        @Config.RangeInt(min = 0, max = 4)
        public int workerCapPerLoneBuilding = 0;

        @Config.Comment("Hard cap on total workers (virtual + loaded) spawned by the population controller. Does not count hired workers assigned via the barracks/recruitment flow beyond their home village's own target.")
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

        @Config.Comment("Probe radius in chunks for edge despawn. 4 = triggers at chunk 5+ from player (80 blocks at view=8). Capped at viewDistance/2 at runtime.")
        @Config.RangeInt(min = 1, max = 8)
        public int despawnProbeRadiusChunks = 4;

        @Config.Comment("Ticks near chunk edge before the entity fades out. Counter decays (not resets) when entity re-enters the safe zone.")
        @Config.RangeInt(min = 0, max = 1200)
        public int despawnEdgeGraceTicks = 40;
    }

    public static class Attributes {
        @Config.Comment("Max health of generic travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public float travellerMaxHealth = 20.0f;

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

        @Config.Comment("Ticks between checks for a nearby dog to recruit as a companion.")
        @Config.RangeInt(min = 1, max = 6000)
        public int dogScanCooldownTicks = 5 * 20;
    }

    public static class Speed {
        @Config.Comment("Base movement speed multiplier.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeed = 0.52f;

        @Config.Comment("Movement speed multiplier while following.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeedWhileFollowing = 0.55f;

        @Config.Comment("Movement speed multiplier during travel task.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeedWhileTravel = 0.52f;

        @Config.Comment("AI move speed while fighting in melee reach. Chase speed (closing distance before reach) scales proportionally above this value.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeedWhileAttacking = 0.56f;

        @Config.Comment("Boat speed multiplier for hired workers convoying a player's boat.")
        @Config.RangeDouble(min = 0.1, max = 10.0)
        public double followerBoatConvoySpeed = 4.0;

        @Config.Comment("Approach speed while a traveller fishes during travel.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public double fishApproachSpeed = 0.45;
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
        public int anchorModeEngageRadius = 8;

        @Config.Comment("Max engagement radius for entities in low-health defensive mode.")
        @Config.RangeInt(min = 2, max = 16)
        public int lowHealthEngageRadius = 5;

        @Config.Comment("Ticks a traveller keeps chasing an attack target after losing sight/proximity of it, before giving up (unless it dies or leaves attackTriggerRange first).")
        @Config.RangeInt(min = 0, max = 400)
        public int losMemoryTicks = 60;

        @Config.Comment("Minimum ticks a melee attacker holds its slot on a target before it can be evicted by a better-placed attacker (prevents mid-chase eviction in crowded fights).")
        @Config.RangeInt(min = 0, max = 400)
        public int engagementGraceTicks = 60;

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

        @Config.Comment("Threat score lost per decay tick (see threatDecayIntervalTicks).")
        @Config.RangeInt(min = 0, max = 100)
        public int threatDecayPerSec = 2;

        @Config.Comment("Ticks between threat decay ticks (20 = once per second).")
        @Config.RangeInt(min = 1, max = 200)
        public int threatDecayIntervalTicks = 20;

        @Config.Comment("Ticks before an unseen threat is forgotten entirely.")
        @Config.RangeInt(min = 20, max = 12000)
        public int threatTtlTicks = 20 * 30;

        @Config.Comment("Threat score added when a traveller observes a hostile hurting an ally or player.")
        @Config.RangeInt(min = 0, max = 100)
        public int threatForSeeAttack = 5;

        @Config.Comment("Speed multiplier applied while swimming (3.0 = +200%).")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double swimSpeedMultiplier = 3.0;
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

        @Config.Comment("Number of hits from a player a traveller tolerates before retaliating for real.")
        @Config.RangeInt(min = 0, max = 20)
        public int playerFreeHits = 3;

        @Config.Comment("Ticks each free-hit grace window lasts before resetting.")
        @Config.RangeInt(min = 1, max = 1200)
        public int playerForgiveWindowTicks = 6 * 20;
    }

    public static class WorkerFormation {

        @Config.Comment("Distance at which follower switches back from return-to-leader to normal follow.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public double returnFollowSwitchDist = 32.0;

        @Config.Comment("Ticks between formation chunk recomputation.")
        @Config.RangeInt(min = 1, max = 100)
        public int chunkRecomputeIntervalTicks = 5;

        @Config.Comment("Max number of workers standing side-by-side in a formation row. Keeps big groups from spreading too wide to reach their spot.")
        @Config.RangeInt(min = 2, max = 16)
        public int maxRowWidth = 8;

        @Config.Comment("Max number of workers standing side-by-side while following through tunnels or caves.")
        @Config.RangeInt(min = 1, max = 4)
        public int maxTunnelWidth = 3;

        @Config.Comment("Distance (blocks) between formation rows.")
        @Config.RangeDouble(min = 0.5, max = 6.0)
        public double rowSpacing = 1.75;

        @Config.Comment("Distance (blocks) between workers standing side-by-side.")
        @Config.RangeDouble(min = 0.5, max = 6.0)
        public double laneSpacing = 1.80;

        @Config.Comment("Extra spacing (blocks) between the closest workers and the ones behind them.")
        @Config.RangeDouble(min = 0.5, max = 6.0)
        public double ringSpacing = 1.35;

        @Config.Comment("How close a worker must get to its spot to count as 'in position'.")
        @Config.RangeDouble(min = 0.5, max = 4.0)
        public double arriveRadius = 1.35;

        @Config.Comment("How close workers stay behind you.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double leaderDistancePlayer = 2.7;

        @Config.Comment("How close workers stay behind a worker acting as their group leader.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double leaderDistanceWorkerLeader = 3.5;

        @Config.Comment("Distance (blocks) between rows while underground.")
        @Config.RangeDouble(min = 0.5, max = 4.0)
        public double caveRowSpacing = 1.28;

        @Config.Comment("Distance (blocks) between workers side-by-side while underground.")
        @Config.RangeDouble(min = 0.3, max = 4.0)
        public double caveLaneSpacing = 0.98;

        @Config.Comment("How close a worker must get to its spot to count as 'in position' while underground.")
        @Config.RangeDouble(min = 0.5, max = 6.0)
        public double caveArriveRadius = 3.60;

        @Config.Comment("How long (in ticks, 20 = 1 second) a worker can be stuck before it teleports closer to catch up.")
        @Config.RangeInt(min = 20, max = 400)
        public int stuckTeleportDelayTicks = 100;

        @Config.Comment("Max distance (blocks) from the leader a stuck worker is allowed to teleport to.")
        @Config.RangeDouble(min = 4.0, max = 32.0)
        public double stuckTeleportMaxDistance = 10.0;

        @Config.Comment("Slowest movement speed for workers trying to catch up (1.0 = normal walking speed).")
        @Config.RangeDouble(min = 0.05, max = 1.0)
        public double minSpeedMultiplier = 0.20;

        @Config.Comment("Fastest movement speed for workers trying to catch up (1.0 = normal walking speed).")
        @Config.RangeDouble(min = 0.5, max = 2.0)
        public double maxSpeedMultiplier = 0.92;

        @Config.Comment("How much faster workers move when you sprint.")
        @Config.RangeDouble(min = 1.0, max = 2.0)
        public double sprintSpeedBoost = 1.12;

        @Config.Comment("How much faster workers move when they've fallen behind and need to catch up.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double catchUpSpeedBoost = 1.0;

        @Config.Comment("Let workers steer around a cliff, ravine or water edge instead of just stopping at it when they can't get a full path this tick.")
        public boolean steeringObstacleAvoidanceEnabled = true;

        @Config.Comment("How far sideways (in degrees) a worker will look for a clear way around an obstacle before giving up.")
        @Config.RangeDouble(min = 20.0, max = 150.0)
        public double steeringMaxDeflectionDegrees = 70.0;

        @Config.Comment("Angle (in degrees) between each direction a worker checks while steering around an obstacle.")
        @Config.RangeDouble(min = 5.0, max = 45.0)
        public double steeringDeflectionStepDegrees = 20.0;

        @Config.Comment("Ticks between re-checking a working detour direction around an obstacle (1 = every tick).")
        @Config.RangeInt(min = 1, max = 20)
        public int steeringDeflectionRecheckIntervalTicks = 4;

        @Config.Comment("Let a worker blocked by an obstacle follow a path a nearby groupmate just found, instead of finding its own.")
        public boolean sharedRouteCorridorEnabled = true;

        @Config.Comment("How long (in ticks, 20 = 1 second) a found path stays usable by other workers before it's considered stale.")
        @Config.RangeInt(min = 20, max = 600)
        public int sharedRouteCorridorTtlTicks = 100;

        @Config.Comment("How far (blocks) a worker can be from a groupmate's path and still jump onto it.")
        @Config.RangeDouble(min = 2.0, max = 16.0)
        public double sharedRouteCorridorMaxSnapDistance = 6.0;

        @Config.Comment("Let a worker that's fully blocked by an obstacle (no detour, no shared path) ask for a real route sooner instead of waiting.")
        public boolean steeringBlockedFastEscalationEnabled = true;

        @Config.Comment("How long (in ticks, 20 = 1 second) a worker can be fully blocked by an obstacle before it asks for a real route sooner.")
        @Config.RangeInt(min = 10, max = 200)
        public int steeringBlockedEscalationGraceTicks = 30;
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

        @Config.Comment("Enable the world-wide global population controller that keeps travellers and raiders populated across all settlements, even when chunks are unloaded.")
        public boolean globalPopulationEnabled = true;

        @Config.Comment("How often (in ticks) the global population controller runs. Lower = faster repopulation after reset, higher CPU cost.")
        @Config.RangeInt(min = 40, max = 12000)
        public int globalPopulationRefreshTicks = 200;

        @Config.Comment("Hard cap on total virtual travellers + raiders. Prevents runaway growth.")
        @Config.RangeInt(min = 0, max = 10000)
        public int globalPopulationCap = 2000;

        @Config.Comment("Max virtual traveller/raider records spawned per refresh cycle. Higher = faster recovery after /travellersdebug reset.")
        @Config.RangeInt(min = 1, max = 500)
        public int globalPopulationMaxSpawnsPerCycle = 60;
    }

    public static class Materialization {
        @Config.Comment("Stop travellers/raiders/workers from appearing inside another group's campsite shelter when they reappear near a player.")
        public boolean avoidCampFootprints = true;

        @Config.Comment("Extra safety gap (in blocks) kept around a camp shelter when pushing an unrelated entity out of it.")
        @Config.RangeInt(min = 0, max = 5)
        public int campFootprintMargin = 1;

        @Config.Comment("Stop travellers/raiders/workers from appearing on top of tree leaves or logs when they reappear near a player.")
        public boolean avoidTreeCanopy = true;
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

        @Config.Comment("Fallback torch cooldown (ticks) used only if travelTorchCooldownTicks is set to 0.")
        @Config.RangeInt(min = 1, max = 72000)
        public int travelTorchCooldownTicksDefault = 20 * 10;

        @Config.Comment("Fallback 'someone else has light' radius (blocks) used only if travelTorchNearbyRadius is set to 0.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public double travelTorchNearbyRadiusDefault = 10.0;

        @Config.Comment("Width (blocks) of the corridor used to collect intermediate village stops along a travel route.")
        @Config.RangeDouble(min = 8.0, max = 256.0)
        public double corridorWidth = 64.0;
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

        @Config.Comment("Maximum new sounds that can start in a single tick (throttle to prevent audio spam).")
        @Config.RangeInt(min = 1, max = 20)
        public int maxNewSoundsPerTick = 5;

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

        @Config.Comment("Max simultaneous entity-linked voice sounds (ambient + talk + choir combined). Prevents OpenAL source pool exhaustion. Lower if you get 'Channel null' errors.")
        @Config.RangeInt(min = 4, max = 30)
        public int maxConcurrentVoiceSounds = 16;

        @Config.Comment("Log every sound play/stop event to the client log (for debugging CodecJOrbis errors). Disable in production.")
        public boolean soundDebugLog = false;

        @Config.Comment("Ticks after spawning before a traveller can play its first ambient voice line.")
        @Config.RangeInt(min = 0, max = 12000)
        public int spawnAmbientDelayTicks = 12 * 20;
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

        @Config.Comment("Ticks before a traveller can rejoin a camp fire after leaving one.")
        @Config.RangeInt(min = 0, max = 72000)
        public int campJoinCooldownTicks = 20 * 60;

        @Config.Comment("Ticks between random camp-stop attempts for a single traveller.")
        @Config.RangeInt(min = 0, max = 720000)
        public int campRandomCooldown = 6 * 60 * 20;

        @Config.Comment("Baseline ticks between animation swings while cooking/working at a camp.")
        @Config.RangeInt(min = 1, max = 6000)
        public int campSwingEvery = 80;
    }

    // New offscreen-capable camp event system (travellers + raiders). Independent from the legacy Camp class above.
    public static class CampEvents {
        @Config.Comment("Master switch for the new offscreen-capable camp event system.")
        public boolean enabled = true;

        @Config.Comment("Radius (blocks) of a campsite: shelters and firepit are placed within this distance of the center.")
        @Config.RangeInt(min = 4, max = 64)
        public int campsiteRadius = 16;

        @Config.Comment("Minimum distance (blocks) between two campsites.")
        @Config.RangeInt(min = 16, max = 2048)
        public int campsiteMinSpacing = 128;

        @Config.Comment("Campsites are never discovered closer than this (blocks) to any Millenaire village.")
        @Config.RangeInt(min = 0, max = 2048)
        public int campsiteMinVillageDistance = 300;

        @Config.Comment("Max chunk-discovery probes processed per server tick (keeps exploration cheap).")
        @Config.RangeInt(min = 1, max = 64)
        public int discoveryProbesPerTick = 4;

        @Config.Comment("Chance (0-1) that an eligible freshly-loaded chunk is registered as a campsite candidate.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double discoveryChancePerChunk = 0.02;

        @Config.Comment("A campsite must hold at least this many valid shelter slots to be kept.")
        @Config.RangeInt(min = 1, max = 16)
        public int minShelterSlots = 2;

        @Config.Comment("Maximum shelter slots recorded per campsite.")
        @Config.RangeInt(min = 1, max = 256)
        public int maxShelterSlots = 64;

        @Config.Comment("Minimum gap (blocks) between two shelter footprints in a campsite.")
        @Config.RangeInt(min = 0, max = 8)
        public int shelterGap = 1;

        @Config.Comment("Max ground-height variation (blocks) tolerated across a footprint; the footprint is graded flat when a shelter is built. Higher = works on rougher (RTG/modded) terrain.")
        @Config.RangeInt(min = 0, max = 6)
        public int flatTolerance = 3;

        @Config.Comment("How close (blocks) a traveller must pass a campsite center to consider stopping.")
        @Config.RangeInt(min = 4, max = 128)
        public int campSeekRadius = 32;

        @Config.Comment("Chance (0-1) that a traveller passing a campsite during the day stops to camp.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double daytimeStopChance = 0.15;

        @Config.Comment("Minimum daytime camp pause, in ticks (20 ticks = 1s).")
        @Config.RangeInt(min = 20, max = 240000)
        public int daytimePauseMinTicks = 2400;

        @Config.Comment("Maximum daytime camp pause, in ticks.")
        @Config.RangeInt(min = 20, max = 240000)
        public int daytimePauseMaxTicks = 7200;

        @Config.Comment("Ticks between each block placed/removed by a loaded traveller building or tearing down its shelter (20 ticks = 1s). Default: ~1 block every half second.")
        @Config.RangeInt(min = 1, max = 200)
        public int buildTicksPerBlock = 10;

        @Config.Comment("Ticks per block of construction/teardown progress for camps with no traveller loaded nearby (virtual/offscreen). Loaded builders also spend time walking between placements, so this should be higher than buildTicksPerBlock for a comparable overall pace.")
        @Config.RangeInt(min = 1, max = 2000)
        public int virtualBuildTicksPerBlock = 20;

        @Config.Comment("Horizontal (X/Z only) distance a traveller must be within to place or break the next block of its shelter without repositioning.")
        @Config.RangeDouble(min = 1.0, max = 8.0)
        public double buildReachDistance = 4.0;

        @Config.Comment("Every this many ticks, a camping traveller/raider with a bed rolls a chance to take a daytime nap.")
        @Config.RangeInt(min = 20, max = 72000)
        public int daytimeNapRollIntervalTicks = 2400;

        @Config.Comment("Chance (0-1), per roll, that a camping traveller/raider with a bed takes a daytime nap.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double daytimeNapChance = 0.12;

        @Config.Comment("Minimum daytime nap duration, in ticks, once asleep.")
        @Config.RangeInt(min = 20, max = 72000)
        public int daytimeNapMinTicks = 400;

        @Config.Comment("Maximum daytime nap duration, in ticks, once asleep.")
        @Config.RangeInt(min = 20, max = 72000)
        public int daytimeNapMaxTicks = 1200;

        @Config.Comment("After being woken up (e.g. by combat), a camping traveller/raider waits this many ticks before it is allowed to lie back down.")
        @Config.RangeInt(min = 0, max = 12000)
        public int wakeCooldownTicks = 100;

        @Config.Comment("Radius (blocks) around a sleeping/napping traveller or raider scanned for hostile threats. If any are found, it wakes up and is allowed to fight back instead of staying passively asleep.")
        @Config.RangeDouble(min = 1.0, max = 32.0)
        public double sleepThreatWakeRadius = 6.0;

        @Config.Comment("Once a camp has no occupants left (e.g. they were all killed), its abandoned firepit goes out after this many ticks (6000 = 5 min). The unlit pit lingers like a ruin until the camp is reused or cleaned up.")
        @Config.RangeInt(min = 20, max = 240000)
        public int emptyCampFirepitExtinguishTicks = 6000;

        @Config.Comment("Non-hired workers may join/found civilian camps alongside travellers (never raiders).")
        public boolean workersCanCamp = true;

        @Config.Comment("If true, a camp only accepts occupants matching the culture of its first occupant.")
        public boolean campCultureRestrictionEnabled = false;

        @Config.Comment("If true, raiders ignore campCultureRestrictionEnabled and may join/found a raider camp of any culture.")
        public boolean raidersIgnoreCulture = true;

        @Config.Comment("How many campsites '/travellersdebug camp reset' processes per tick (each one briefly loads its chunks to restore terrain). Lower = less lag on servers with many campsites, but a slower reset.")
        @Config.RangeInt(min = 1, max = 50)
        public int campResetCampsitesPerTick = 3;

        @Config.Comment("How often (in ticks; 200 = 10 seconds) '/travellersdebug camp reset' reports its progress in chat.")
        @Config.RangeInt(min = 20, max = 12000)
        public int campResetProgressIntervalTicks = 200;
    }

    public static class BattleEvents {
        @Config.Comment("Master switch for offscreen virtual battles between virtual travellers/raiders/workers.")
        public boolean enableVirtualBattles = true;

        @Config.Comment("Ticks between encounter-detection passes over virtual records.")
        @Config.RangeInt(min = 5, max = 200)
        public int detectionIntervalTicks = 20;

        @Config.Comment("Ticks between battle simulation steps (pawn movement + attacks).")
        @Config.RangeInt(min = 1, max = 100)
        public int battleSimIntervalTicks = 10;

        @Config.Comment("Distance (blocks) at which two hostile virtual parties engage each other.")
        @Config.RangeInt(min = 8, max = 128)
        public int engagementRadius = 48;

        @Config.Comment("Virtual entities within this distance of an ongoing battle join it (group pull-in and reinforcements).")
        @Config.RangeInt(min = 8, max = 256)
        public int battleJoinRadius = 64;

        @Config.Comment("Two battles whose centers drift closer than this are merged (when their sides are compatible).")
        @Config.RangeInt(min = 8, max = 128)
        public int mergeBattleDistance = 32;

        @Config.Comment("Melee reach (blocks) for virtual pawns.")
        @Config.RangeDouble(min = 1.0, max = 5.0)
        public double meleeReach = 1.8;

        @Config.Comment("Max simultaneous melee attackers per target (mirrors the loaded AttackTargetLimiter). Archers are exempt.")
        @Config.RangeInt(min = 1, max = 16)
        public int maxMeleePerTarget = 4;

        @Config.Comment("Max battles advanced per simulation pass (round-robin across passes).")
        @Config.RangeInt(min = 1, max = 64)
        public int maxBattlesSimPerTick = 4;

        @Config.Comment("Safety cap: a battle older than this many ticks is force-resolved (24000 = 1 day).")
        @Config.RangeInt(min = 1200, max = 480000)
        public int maxBattleDurationTicks = 24000;

        @Config.Comment("Global multiplier applied to all virtual battle damage (tune virtual vs physical lethality).")
        @Config.RangeDouble(min = 0.05, max = 10.0)
        public double virtualDamageMultiplier = 1.0;

        @Config.Comment("Allow virtual raiders to attack player-hired workers.")
        public boolean raidersAttackHiredWorkers = true;

        @Config.Comment("Raiders only engage hired workers after this many ticks of sustained proximity (100 = 5s). Passing groups are left alone.")
        @Config.RangeInt(min = 0, max = 2400)
        public int raiderVsHiredEngageDelayTicks = 100;

        @Config.Comment("Allow virtual battles between hired workers of players who declared each other hostile.")
        public boolean hiredVsHiredBattles = true;

        @Config.Comment("Allow enemy groups to assault occupied camps (campers defend; camp ruins on death).")
        public boolean campAssaults = true;

        @Config.Comment("Hired-worker group leaders are targeted last and shielded by nearby followers, so they die among the last.")
        public boolean leaderSurvivalBias = true;

        @Config.Comment("Virtual traveller deaths in battle contribute their equipment/inventory to the battle's loot pool.")
        public boolean dropLootTravellers = false;

        @Config.Comment("Virtual raider deaths in battle contribute their equipment/inventory to the battle's loot pool.")
        public boolean dropLootRaiders = false;

        @Config.Comment("Virtual non-hired worker deaths in battle contribute their equipment/inventory to the battle's loot pool.")
        public boolean dropLootWorkers = false;

        @Config.Comment("Virtual hired-worker deaths in battle contribute their equipment/inventory to the battle's loot pool.")
        public boolean dropLootHiredWorkers = true;

        @Config.Comment("On a clear battle victory, surviving virtual winners try to upgrade their equipment from the battle's loot pool before the rest hits the ground.")
        public boolean battleLootingEnabled = true;

        @Config.Comment("Pending virtual loot drops expire after this many ticks (0 = never).")
        @Config.RangeInt(min = 0, max = 1728000)
        public int lootExpiryTicks = 0;

        @Config.Comment("Notify a worker's owner when their hired workers enter a virtual battle.")
        public boolean notifyOwnerOnBattleStart = true;

        @Config.Comment("Notify a worker's owner when one of their hired workers dies in a virtual battle.")
        public boolean notifyOwnerOnDeath = true;

        @Config.Comment("Show active virtual battles as markers on the worldmap.")
        public boolean battleMarkersOnMap = true;

        @Config.Comment("Virtual travellers/workers regain health offscreen after combat, mirroring the physical EntityAIAutoEat AI (raiders excluded, they don't auto-eat physically either).")
        public boolean virtualCombatHealingEnabled = true;

        @Config.Comment("Ticks after last combat before offscreen healing can start (mirrors EntityAIAutoEat's physical post-combat lockout, 30-50 ticks).")
        @Config.RangeInt(min = 0, max = 2400)
        public int postCombatHealDelayTicks = 40;
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
        public boolean workerRespawnInBarracks = true;

        @Config.Comment("In-game day time (0-23999) when dead workers respawn at their barracks.")
        @Config.RangeInt(min = 0, max = 23999)
        public int workerRespawnMorningTime = 1000;
    }

    public static class Worker {
        @Config.Comment("If true, hired workers without a barrack are automatically assigned to the nearest one.")
        public boolean autoAssignWorkersToBarracks = false;

        @Config.Comment("Number of in-game days before a dead worker respawns at their barrack (requires workerRespawnInBarracks).")
        @Config.RangeInt(min = 1, max = 30)
        public int workerBarrackRespawnDays = 3;

        @Config.Comment("Default half-width (X axis) for lone barracks detection.")
        @Config.RangeInt(min = 4, max = 32)
        public int workerLoneBarrackHalfWidth = 5;

        @Config.Comment("Default half-depth (Z axis) for lone barracks detection.")
        @Config.RangeInt(min = 4, max = 32)
        public int workerLoneBarrackHalfDepth = 5;

        @Config.Comment("Default half-height for lone barracks detection.")
        @Config.RangeInt(min = 2, max = 64)
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

        @Config.Comment("Initial zoom for the worldmap in the formation hub (blocks per pixel). Smaller = more zoomed in. 0.5 means 1 block per 2 pixels.")
        @Config.RangeDouble(min = 0.0625, max = 8.0)
        public double formationHubMapInitialBpp = 0.5;

        @Config.Comment("Master switch: reveal foreign travellers/raiders/workers and battles on the player worldmap/formation hub, gated by hiredWorkerVisionRadius.")
        public boolean foreignUnitVisionEnabled = true;

        @Config.Comment("Radius (blocks) around each of a player's own alive hired units within which foreign travellers/raiders/workers and battles become visible on the map.")
        @Config.RangeInt(min = 8, max = 512)
        public int hiredWorkerVisionRadius = 48;

        @Config.Comment("Show HP of discovered foreign entities on the map (own workers always show HP regardless of this setting).")
        public boolean revealForeignEntityHealth = false;
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

        @Config.Comment("Villages processed per merchant maintenance pass (0 = disabled; the merchant feature is currently unused).")
        @Config.RangeInt(min = 0, max = 64)
        public int merchantVillagesPerPass = 0;
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
        // --- Traveller ---
        @Config.Comment("Villager txt tags excluded from traveller appearance pool. E.g.: child,chief,hostile")
        public String[] excludedTravellerVillagerTagsRaw = new String[]{"child", "chief", "hostile"};

        @Config.Comment("Villager txt filenames (without .txt) excluded from traveller pool.")
        public String[] excludedTravellerVillagerFilesRaw = new String[]{"alchemist", "alchemistapprentice", "alchemistassistant"};

        @Config.Comment("Villager tags included in traveller appearance pool (empty = no restriction, allow all not excluded above).")
        public String[] includedTravellerVillagerTagsRaw = new String[]{};

        @Config.Comment("Villager filenames included in traveller appearance pool (empty = no restriction).")
        public String[] includedTravellerVillagerFilesRaw = new String[]{};

        @Config.Comment("If true, ALL includedTravellerVillagerTagsRaw must be present on the villager (AND). If false, ANY one tag matching is enough (OR).")
        public boolean includeTravellerTagMatchRequiresAll = false;

        // --- Raider ---
        @Config.Comment("Villager txt tags excluded from raider appearance pool.")
        public String[] excludedRaiderVillagerTagsRaw = new String[]{};

        @Config.Comment("Villager txt filenames (without .txt) excluded from raider pool.")
        public String[] excludedRaiderVillagerFilesRaw = new String[]{};

        @Config.Comment("Villager tags included in raider appearance pool (empty = no restriction).")
        public String[] includedRaiderVillagerTagsRaw = new String[]{"hostile"};

        @Config.Comment("Villager filenames included in raider appearance pool (empty = no restriction).")
        public String[] includedRaiderVillagerFilesRaw = new String[]{};

        @Config.Comment("If true, ALL includedRaiderVillagerTagsRaw must be present on the villager (AND). If false, ANY one tag matching is enough (OR).")
        public boolean includeRaiderTagMatchRequiresAll = false;

        // --- Worker ---
        @Config.Comment("Villager txt tags excluded from worker appearance pool.")
        public String[] excludedWorkerVillagerTagsRaw = new String[]{};

        @Config.Comment("Villager txt filenames (without .txt) excluded from worker pool.")
        public String[] excludedWorkerVillagerFilesRaw = new String[]{};

        @Config.Comment("Villager tags included in worker appearance pool (empty = no restriction).")
        public String[] includedWorkerVillagerTagsRaw = new String[]{"helpInAttacks", "heavydrinker", "archer", "raider"};

        @Config.Comment("Villager filenames included in worker appearance pool (empty = no restriction).")
        public String[] includedWorkerVillagerFilesRaw = new String[]{};

        @Config.Comment("If true, ALL includedWorkerVillagerTagsRaw must be present on the villager (AND). If false, ANY one tag matching is enough (OR).")
        public boolean includeWorkerTagMatchRequiresAll = false;

        // --- Merchant (EntityTravellerMerchant is being phased out, left as-is) ---
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

    public static class Notifications {
        @Config.Comment("Master switch for the on-screen notification popups. Debug/admin command output in chat is not affected by this.")
        public boolean masterEnabled = true;

        @Config.Comment("Which corner of the screen notifications appear in. Options: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT.")
        public String corner = "BOTTOM_RIGHT";

        @Config.Comment("Horizontal distance (pixels) from the screen edge.")
        @Config.RangeInt(min = 0, max = 500)
        public int marginX = 8;

        @Config.Comment("Vertical distance (pixels) from the screen edge.")
        @Config.RangeInt(min = 0, max = 500)
        public int marginY = 8;

        @Config.Comment("Width (pixels) of each notification box. Long messages wrap to fit this width.")
        @Config.RangeInt(min = 80, max = 600)
        public int widthPixels = 220;

        @Config.Comment("Text size for notifications (1.0 = normal size).")
        @Config.RangeDouble(min = 0.5, max = 2.0)
        public float fontScale = 0.85f;

        @Config.Comment("How long (ticks) a notification stays fully visible before it starts fading out. 20 ticks = 1 second.")
        @Config.RangeInt(min = 20, max = 2400)
        public int displayDurationTicks = 100;

        @Config.Comment("How long (ticks) the fade-in animation takes.")
        @Config.RangeInt(min = 0, max = 100)
        public int fadeInTicks = 6;

        @Config.Comment("How long (ticks) the fade-out animation takes.")
        @Config.RangeInt(min = 0, max = 100)
        public int fadeOutTicks = 12;

        @Config.Comment("Maximum number of notifications visible on screen at once. Older ones scroll off as new ones arrive.")
        @Config.RangeInt(min = 1, max = 30)
        public int maxVisible = 8;

        @Config.Comment("Show Information notifications (general non-critical updates).")
        public boolean showInfo = true;

        @Config.Comment("Show Warning notifications (things that need your attention).")
        public boolean showWarning = true;

        @Config.Comment("Show Event notifications (battles, deliveries, recruitment results). These are also saved to your notification history.")
        public boolean showEvent = true;

        @Config.Comment("Show Error notifications (things that failed).")
        public boolean showError = true;

        @Config.Comment("Maximum number of Event notifications kept in your notification history (oldest are dropped first). Used by the notification journal screen.")
        @Config.RangeInt(min = 10, max = 2000)
        public int journalRetentionPerPlayer = 200;

        @Config.Comment("Maximum number of notifications saved up for you while you are offline, before the oldest ones are dropped.")
        @Config.RangeInt(min = 5, max = 200)
        public int offlineQueueCapPerPlayer = 50;
    }
}
