package com.jubitus.travellers.config;

import net.minecraftforge.common.config.Config;

public class TravellersConfig {

    public static final General GENERAL = new General();
    public static final Rendering RENDERING = new Rendering();
    public static final Worldmap WORLDMAP = new Worldmap();
    public static final EntityRenderLod ENTITY_RENDER_LOD = new EntityRenderLod();
    public static final Spawn SPAWN = new Spawn();
    public static final SpawnTravellers SPAWN_TRAVELLERS = new SpawnTravellers();
    public static final SpawnRaiders SPAWN_RAIDERS = new SpawnRaiders();
    public static final SpawnWorkers SPAWN_WORKERS = new SpawnWorkers();
    public static final GroupFollow GROUP_FOLLOW = new GroupFollow();
    public static final Despawn DESPAWN = new Despawn();
    public static final Attributes ATTRIBUTES = new Attributes();
    public static final Speed SPEED = new Speed();
    public static final Combat COMBAT = new Combat();
    public static final Equipment EQUIPMENT = new Equipment();
    public static final PlayerAggro PLAYER_AGGRO = new PlayerAggro();
    public static final WorkerFormation WORKER_FORMATION = new WorkerFormation();
    public static final Destinations DESTINATIONS = new Destinations();
    public static final Offscreen OFFSCREEN = new Offscreen();
    public static final Materialization MATERIALIZATION = new Materialization();
    public static final Travel TRAVEL = new Travel();
    public static final GapJump GAP_JUMP = new GapJump();
    public static final HazardSafety HAZARD_SAFETY = new HazardSafety();
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
    public static final GuiTooltips GUI_TOOLTIPS = new GuiTooltips();
    public static final Compat COMPAT = new Compat();

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
    }

    public static class Worldmap {
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
        public int mapPushMinIntervalMs = 500;

        @Config.Comment("How many chunks per tick the '/travellersdebug map resweep' admin command processes. Higher = faster bulk correction, more server load while it runs. Default: 8.")
        @Config.RangeInt(min = 1, max = 64)
        public int mapResweepChunksPerTick = 8;

        @Config.Comment("Log detailed worldmap generation/refresh/network activity to the console. For diagnosing worldmap bugs only - very spammy, leave off otherwise.")
        public boolean debugMap = false;

        @Config.Comment("How often (in ticks) '/travellersdebug map resweep' reports its progress to the player who ran it. Default: 200 (10s).")
        @Config.RangeInt(min = 20, max = 2000)
        public int mapResweepProgressIntervalTicks = 200;

        @Config.Comment("Reveal the entire generated worldmap to every player, bypassing per-player fog of war. Only affects visibility of tiles already present in the shared tile cache; does not force generation of unexplored areas. Turning it back off restores each player's own fog of war exactly as it was. Applies the next time a player (re)opens the map.")
        public boolean showFullMap = true;

        @Config.Comment("Max worldmap tiles kept in memory per zoom level on the client. Higher = smoother panning/zooming with less re-fetching, more client RAM used. Default: 128.")
        @Config.RangeInt(min = 16, max = 2048)
        public int mapClientTileCacheSizePerLevel = 128;

        @Config.Comment("Show active virtual battles as markers on the worldmap.")
        public boolean battleMarkersOnMap = true;

        @Config.Comment("Initial zoom for the worldmap in the formation hub (blocks per pixel). Smaller = more zoomed in. 0.5 means 1 block per 2 pixels.")
        @Config.RangeDouble(min = 0.0625, max = 8.0)
        public double formationHubMapInitialBpp = 0.5;

        @Config.Comment("Master switch: reveal foreign travellers/raiders/workers and battles on the player worldmap/formation hub, gated by hiredWorkerVisionRadius.")
        public boolean foreignUnitVisionEnabled = true;

        @Config.Comment("Radius (blocks) around each of a player's own alive hired units within which foreign travellers/raiders/workers and battles become visible on the map.")
        @Config.RangeInt(min = 8, max = 512)
        public int hiredWorkerVisionRadius = 96;

        @Config.Comment("Show HP of discovered foreign entities on the map (own workers always show HP regardless of this setting).")
        public boolean revealForeignEntityHealth = false;

        @Config.Comment("Also treat the player's own position as a vision source, in addition to their hired units - reveals foreign travellers/raiders/workers and battles within hiredWorkerVisionRadius of the player, even with no hired units nearby.")
        public boolean playerIsVisionSource = true;

        @Config.Comment("Show the player marker as a directional arrow pointing where the player was looking when the map was opened, instead of a plain dot.")
        public boolean playerMarkerDirectional = true;

        @Config.Comment("Global multiplier applied on top of every marker's size below. 1.0 = unchanged.")
        @Config.RangeDouble(min = 0.25, max = 4.0)
        public double markerScaleGlobal = 0.75;

        @Config.Comment("Size (pixels) of the player marker.")
        @Config.RangeInt(min = 1, max = 32)
        public int playerMarkerSize = 6;

        @Config.Comment("Size (pixels) of village and controlled-village markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int villageMarkerSize = 8;

        @Config.Comment("Size (pixels) of home, group-leader and follower worker markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int workerMarkerSize = 6;

        @Config.Comment("Extra pixels added on top of workerMarkerSize for the currently selected follower.")
        @Config.RangeInt(min = 0, max = 32)
        public int followerHighlightedSizeBonus = 5;

        @Config.Comment("Size (pixels) of foreign traveller/raider/worker population markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int populationMarkerSize = 4;

        @Config.Comment("Size (pixels) of camp firepit and camp shelter-slot markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int campMarkerSize = 1;

        @Config.Comment("Size (pixels) of destination-cross and battle markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int alertMarkerSize = 8;
    }

    public static class EntityRenderLod {
        @Config.Comment("Master switch for distance-based LOD on traveller/raider/worker entity rendering (cosmetic overlay layers + held item). Reduces GPU/CPU cost when many are visible at once. Purely cosmetic beyond the configured distances.")
        public boolean enabled = false;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the clothes overlay layer. This layer re-renders the full biped model a second time, so it's the most expensive of the three and invisible at range anyway.")
        @Config.RangeInt(min = 4, max = 128)
        public int clothesLayerMaxDistance = 96;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the owner-mark armband layer (workers only).")
        @Config.RangeInt(min = 4, max = 128)
        public int armbandLayerMaxDistance = 128;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the backpack/bamboo/chuspas layer (workers/merchants only).")
        @Config.RangeInt(min = 4, max = 128)
        public int backpackLayerMaxDistance = 128;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip rendering the held item (weapon/tool in hand) for travellers/raiders/workers. Highest-impact option since vanilla's 3D item rendering is the single most expensive part of entity rendering.")
        @Config.RangeInt(min = 4, max = 128)
        public int heldItemMaxDistance = 32;
    }

    public static class Spawn {
        @Config.Comment("If true, travellers may spawn with a dog companion.")
        public boolean spawnTravellerWithDog = false;

        @Config.Comment("Chance (0.0–1.0) that a traveller spawns with a dog companion.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float travellerDogSpawnChance = 0.0085f;

        @Config.Comment("Enable culture-weighted spawning (cultures near spawn get higher weight).")
        public boolean travellerCultureWeightedSpawn = true;

        // --- Everything below only feeds the /travellersmap debug heatmap visualization.
        // It does not influence where or how many travellers/raiders/workers actually spawn —
        // real spawning is entirely driven by SpawnTravellers/SpawnRaiders/SpawnWorkers below.

        @Config.Comment("[Debug map only] Radius (blocks) around a Millenaire village shown as traveller density on /travellersmap.")
        @Config.RangeInt(min = 0, max = 5000)
        public int travellerVillageInfluenceRadius = 1000;

        @Config.Comment("[Debug map only] Radius (blocks) around a lone building shown as traveller density on /travellersmap.")
        @Config.RangeInt(min = 0, max = 5000)
        public int travellerLoneInfluenceRadius = 600;

        @Config.Comment("[Debug map only] Strength of village influence on the /travellersmap density display (1.0 = normal).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerVillageWeight = 0.3f;

        @Config.Comment("[Debug map only] Strength of lone building influence on the /travellersmap density display.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerLoneWeight = 0.2f;

        @Config.Comment("[Debug map only] Falloff curve shape for the /travellersmap density display: 1 = linear, >1 = steeper near edge, <1 = flatter.")
        @Config.RangeDouble(min = 0.1, max = 10.0)
        public float travellerFalloffPower = 1.0f;

        @Config.Comment("[Debug map only] Max combined density shown on /travellersmap when multiple village halos overlap.")
        @Config.RangeDouble(min = 0.1, max = 50.0)
        public float travellerMaxDensity = 1.8f;

        @Config.Comment("[Debug map only] Baseline density shown on /travellersmap outside of any village/lone-building influence (0 = none).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerOutsideVillageBaseDensity = 0.10f;

        @Config.Comment("[Debug map only] Enable the population-based density boost on /travellersmap.")
        public boolean travellerPopulationWeightEnabled = true;

        @Config.Comment("[Debug map only] Radius (blocks) to search for the host village when computing the /travellersmap population density boost.")
        @Config.RangeInt(min = 64, max = 10000)
        public int travellerPopulationWeightRadius = 700;

        @Config.Comment("[Debug map only] Extra density per villager on /travellersmap (0.05 = +5% per villager).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerPopulationWeightPerVillager = 0.05f;

        @Config.Comment("[Debug map only] Max overall density multiplier from population on /travellersmap.")
        @Config.RangeDouble(min = 0.1, max = 10.0)
        public float travellerPopulationWeightMaxMultiplier = 0.1f;

        @Config.Comment("Radius (blocks) to scan for village cultures when picking spawn culture.")
        @Config.RangeInt(min = 64, max = 10000)
        public int travellerCultureScanRadius = 1024;

        @Config.Comment("If true, cultures with larger populations get more spawn weight.")
        public boolean travellerCultureWeightUseVillagePopulation = true;
    }

    public static class SpawnTravellers {
        @Config.Comment("How many travellers a village should have per villager it has. Example: 0.33 means 1 traveller for every 3 villagers.")
        @Config.RangeDouble(min = 0.01, max = 10.0)
        public float travellerCapPerVillager = 0.24f;

        @Config.Comment("Every village always gets at least this many travellers, even a brand new one with no population yet.")
        @Config.RangeInt(min = 0, max = 64)
        public int travellerCapMinPerVillage = 2;

        @Config.Comment("No single village will ever get more travellers than this, no matter how big it grows.")
        @Config.RangeInt(min = 0, max = 64)
        public int travellerCapMaxPerVillage = 12;

        @Config.Comment("How many travellers appear around a standalone building that isn't part of a full village.")
        @Config.RangeInt(min = 0, max = 4)
        public int travellerCapPerLoneBuilding = 2;

        @Config.Comment("Radius (blocks) used by /travellersinfo to show nearby traveller counts. Display only, does not affect spawning.")
        @Config.RangeInt(min = 128, max = 8000)
        public int travellerRegionalRadius = 1200;

        @Config.Comment("Chance (0-100) that a newly spawned traveller brings a small travelling group with them instead of being alone.")
        @Config.RangeInt(min = 0, max = 100)
        public int groupSpawnChance = 50;

        @Config.Comment("Smallest possible size for a traveller group (including the leader).")
        @Config.RangeInt(min = 2, max = 12)
        public int groupMinSize = 2;

        @Config.Comment("Largest possible size for a traveller group (including the leader).")
        @Config.RangeInt(min = 2, max = 12)
        public int groupMaxSize = 8;
    }

    public static class SpawnRaiders {
        @Config.Comment("Minimum distance (blocks) from any village where a raider is allowed to appear. Keeps raiders from popping up right on top of a settlement.")
        @Config.RangeInt(min = 16, max = 512)
        public int raiderMinVillageDistance = 128;

        @Config.Comment("How many raiders a village should attract per villager it has. Example: 0.2 means 1 raider for every 5 villagers.")
        @Config.RangeDouble(min = 0.01, max = 10.0)
        public float raiderCapPerVillager = 0.12f;

        @Config.Comment("Every village always attracts at least this many raiders, even a brand new one with no population yet.")
        @Config.RangeInt(min = 0, max = 32)
        public int raiderCapMinPerVillage = 1;

        @Config.Comment("No single village will ever attract more raiders than this, no matter how big it grows.")
        @Config.RangeInt(min = 0, max = 32)
        public int raiderCapMaxPerVillage = 12;

        @Config.Comment("How many raiders appear around a standalone building that isn't part of a full village.")
        @Config.RangeInt(min = 0, max = 4)
        public int raiderCapPerLoneBuilding = 1;

        @Config.Comment("Radius (blocks) used by /travellersinfo to show nearby raider counts. Display only, does not affect spawning.")
        @Config.RangeInt(min = 128, max = 8000)
        public int raiderRegionalRadius = 1200;

        @Config.Comment("If true, raiders travel along the Ultreia/MPB road network like travellers do. If false, raiders roam freely off-road.")
        public boolean raidersUseUltreiaPaths = true;

        @Config.Comment("Chance (0-100) that a newly spawned raider brings a small warband with them instead of being alone.")
        @Config.RangeInt(min = 0, max = 100)
        public int groupSpawnChance = 50;

        @Config.Comment("Smallest possible size for a raider warband (including the leader).")
        @Config.RangeInt(min = 2, max = 12)
        public int groupMinSize = 2;

        @Config.Comment("Largest possible size for a raider warband (including the leader).")
        @Config.RangeInt(min = 2, max = 12)
        public int groupMaxSize = 8;
    }

    public static class SpawnWorkers {
        @Config.Comment("Minimum Millenaire population before a village can have any workers.")
        @Config.RangeInt(min = 0, max = 1024)
        public int workerMinPop = 3;

        @Config.Comment("Extra population required per additional worker beyond the first.")
        @Config.RangeInt(min = 1, max = 1024)
        public int workerPopPerWorker = 3;

        @Config.Comment("Maximum workers assigned to a single village.")
        @Config.RangeInt(min = 0, max = 16)
        public int workerMaxPerVillage = 8;

        @Config.Comment("Workers the global population controller spawns per lone building.")
        @Config.RangeInt(min = 0, max = 4)
        public int workerCapPerLoneBuilding = 0;

        @Config.Comment("Hard cap on total workers (virtual + loaded) spawned by the population controller. Does not count hired workers assigned via the barracks/recruitment flow beyond their home village's own target.")
        @Config.RangeInt(min = 0, max = 2048)
        public int workerLoadedCap = 1024;

        @Config.Comment("A freshly spawned worker appears somewhere on their way out of the village instead of standing right on top of it, so discovering a village doesn't show its whole workforce bunched together. This sets how far along their route they can appear, as a percentage of the way to their first destination (0 = right at the village, 100 = as far as a travelling worker ever appears).")
        @Config.RangeInt(min = 0, max = 100)
        public int workerSpawnDisperseMaxPercent = 40;

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

    public static class GroupFollow {
        @Config.Comment("Give each follower its own small spot near the leader instead of everyone aiming for the exact same point. Reduces crowding and followers getting stuck on each other.")
        public boolean spreadFollowEnabled = true;

        @Config.Comment("How close (blocks) a follower must get to its spot before it stops and waits there.")
        @Config.RangeDouble(min = 0.5, max = 8.0)
        public double stopDistance = 2.0;

        @Config.Comment("Distance (blocks) from the leader beyond which a follower starts speeding up to catch up.")
        @Config.RangeDouble(min = 2.0, max = 32.0)
        public double catchUpTriggerDistance = 8.0;

        @Config.Comment("Distance (blocks) from the leader at which a follower reaches its maximum catch-up speed.")
        @Config.RangeDouble(min = 4.0, max = 64.0)
        public double catchUpMaxDistance = 16.0;

        @Config.Comment("How much faster (multiplier) a follower moves at maximum catch-up speed. 1.0 = no speed boost.")
        @Config.RangeDouble(min = 1.0, max = 3.0)
        public double catchUpSpeedMultiplier = 1.18;

        @Config.Comment("Followers visibly sprint while catching up from far behind.")
        public boolean sprintWhileCatchingUp = true;

        @Config.Comment("How often (ticks) a follower re-checks its path while calmly keeping pace with the leader.")
        @Config.RangeInt(min = 2, max = 40)
        public int repathTicksNormal = 8;

        @Config.Comment("How often (ticks) a follower re-checks its path while catching up from far behind. Lower keeps it closer to the leader but costs more performance.")
        @Config.RangeInt(min = 1, max = 20)
        public int repathTicksCatchup = 3;

        @Config.Comment("How long (ticks) to wait before retrying pathfinding after a failed attempt.")
        @Config.RangeInt(min = 2, max = 40)
        public int repathTicksFailed = 6;

        @Config.Comment("How long (ticks) a follower can be stuck making no progress before it teleports partway toward the group. 160 = about 8 seconds.")
        @Config.RangeInt(min = 20, max = 1200)
        public int stagnationTeleportTicks = 120;

        @Config.Comment("Maximum distance (blocks) a stuck follower teleports in one jump. Followers hop gradually toward the group instead of jumping straight onto the leader.")
        @Config.RangeDouble(min = 2.0, max = 32.0)
        public double teleportMaxStep = 10.0;

        @Config.Comment("A stuck follower only teleports if it is at least this far (blocks) from its spot. Below this it just keeps walking.")
        @Config.RangeDouble(min = 1.0, max = 16.0)
        public double teleportMinTriggerDistance = 6.0;

        @Config.Comment("Beyond this distance (blocks) to the leader, a follower stops trying to pathfind straight to the leader's exact spot (which usually fails and causes repeated teleport-hops) and instead walks there step by step, like the normal travel AI does.")
        @Config.RangeDouble(min = 16.0, max = 128.0)
        public double directPathMaxDistance = 40.0;

        @Config.Comment("How far (blocks) each walking step aims when a follower is too far to pathfind straight to the leader (see directPathMaxDistance).")
        @Config.RangeDouble(min = 8.0, max = 48.0)
        public double catchupStepDistance = 24.0;
    }

    public static class Despawn {
        @Config.Comment("Despawn entities near the edge of loaded chunks after a grace period.")
        public boolean despawnNearUnloadedChunks = true;

        @Config.Comment("Despawn entities far beyond any player's view radius.")
        public boolean despawnWhenFarFromPlayers = true;

        @Config.Comment("Unused — kept for config compatibility only.")
        @Config.RangeInt(min = 0, max = 16)
        public int farExtraChunks = 2;

        @Config.Comment("How long (ticks) an entity must remain outside view distance before despawning.")
        @Config.RangeInt(min = 0, max = 120000)
        public int farGraceTicks = 300;

        @Config.Comment("Hard minimum distance (blocks) for the far-despawn check.")
        @Config.RangeInt(min = 16, max = 1024)
        public int farMinDistanceBlocks = 128;

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
        public float travellerMaxHealth = 25.0f;

        @Config.Comment("FOLLOW_RANGE (detection radius) for generic travellers.")
        @Config.RangeDouble(min = 4.0, max = 256.0)
        public float travellerFollowRange = 64.0f;

        @Config.Comment("Base melee damage per hit.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public float attackDamage = 1.0f;

        @Config.Comment("Global damage multiplier for travellers hit by mobs (not players). 0 = immune.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerDamageScale = 0.35f;

        @Config.Comment("Multiplier on all damage travellers deal to players.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerPlayerDamageMultiplier = 1.0f;

        @Config.Comment("Multiplier on damage a hired worker deals to its own owner (melee and arrows), for example when it hits you by accident while fighting the same enemy. Set to 0 so your own workers can never hurt you.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float workerOwnerDamageMultiplier = 0.0f;

        @Config.Comment("Max health of raider travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public float raiderMaxHealth = 20.0f;

        @Config.Comment("Max health of merchant travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public float merchantMaxHealth = 60.0f;

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
        public double workerMaxHealth = 25.0;

        @Config.Comment("FOLLOW_RANGE for workers.")
        @Config.RangeDouble(min = 4.0, max = 256.0)
        public double workerFollowRange = 64.0;


        @Config.Comment("Blocks per path step for worker travel.")
        @Config.RangeInt(min = 1, max = 64)
        public int workerStepBlocks = 64;

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

        @Config.Comment("Movement speed multiplier while following.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public double movementSpeedWhileFollowingPlayer = 0.57;

        @Config.Comment("Movement speed multiplier during travel task.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeedWhileTravel = 0.52f;

        @Config.Comment("AI move speed while fighting in melee reach. Chase speed (closing distance before reach) scales proportionally above this value.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public float movementSpeedWhileAttacking = 0.56f;

        @Config.Comment("Boat speed multiplier for anyone convoying alongside someone else's boat (hired workers following their owner, and travellers/raiders following their group leader).")
        @Config.RangeDouble(min = 0.1, max = 10.0)
        public double followerBoatConvoySpeed = 1.0;

        @Config.Comment("Approach speed while a traveller fishes during travel.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public double fishApproachSpeed = 0.45;

    }

    public static class Combat {
        @Config.Comment("If true, travellers call nearby allies for help when attacked.")
        public boolean callForHelp = true;

        @Config.Comment("Skip calling allies for help if the attacker already has at least this many attackers/defenders engaged - a big group that's already responding doesn't need to keep recruiting more.")
        @Config.RangeInt(min = 1, max = 30)
        public int callForHelpSkipIfAttackersAtLeast = 5;

        @Config.Comment("Max range (blocks) allies respond to a help call.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public float helpRange = 24.0f;

        @Config.Comment("Ticks after combat ends before sword is hidden.")
        @Config.RangeInt(min = 0, max = 2000)
        public int swordHideDelay = 120;

        @Config.Comment("Health fraction at or below which travellers eat food (0.0–1.0).")
        @Config.RangeDouble(min = 0.1, max = 1.0)
        public float eatHealthThreshold = 0.7f;

        @Config.Comment("Max range (blocks) travellers will attack a hostile mob.")
        @Config.RangeInt(min = 1, max = 200)
        public int attackTriggerRange = 24;

        @Config.Comment("Health fraction below which entities enter defensive mode.")
        @Config.RangeDouble(min = 0.05, max = 0.50)
        public float lowHealthCombatFrac = 0.20f;

        @Config.Comment("Max engagement radius for workers in anchor/stay mode.")
        @Config.RangeInt(min = 3, max = 64)
        public int anchorModeEngageRadius = 12;

        @Config.Comment("Radius (blocks) around the owner/leader that hired workers proactively scan for threats already attacking them (EntityAIDefendOwnerProactively) - needs to reach ranged attackers standing well back, not just melee range.")
        @Config.RangeInt(min = 4, max = 64)
        public int defendOwnerProactiveRadius = 24;

        @Config.Comment("Max engagement radius for entities in low-health defensive mode.")
        @Config.RangeInt(min = 2, max = 16)
        public int lowHealthEngageRadius = 5;

        @Config.Comment("Ticks a traveller keeps chasing an attack target after losing sight/proximity of it, before giving up (unless it dies or leaves attackTriggerRange first).")
        @Config.RangeInt(min = 0, max = 400)
        public int losMemoryTicks = 60;

        @Config.Comment("Minimum ticks a melee attacker holds its slot on a target before it can be evicted by a better-placed attacker (prevents mid-chase eviction in crowded fights).")
        @Config.RangeInt(min = 0, max = 400)
        public int engagementGraceTicks = 60;

        @Config.Comment("Ticks after killing an attack target during which retargeting is boosted (scans run every tick instead of waiting on their normal timer) so nearby threats are engaged without pausing.")
        @Config.RangeInt(min = 1, max = 100)
        public int retargetBoostWindowTicks = 15;

        @Config.Comment("Extra melee reach (blocks) added on top of hitbox contact distance, for all traveller types.")
        @Config.RangeDouble(min = 0.0, max = 3.0)
        public double meleeReachBonus = 0.3;

        @Config.Comment("Additional melee reach (blocks) for raiders, on top of meleeReachBonus.")
        @Config.RangeDouble(min = 0.0, max = 3.0)
        public double raiderExtraMeleeReach = 0.3;

        @Config.Comment("Max melee attackers allowed on the same target at once (bow users don't count against this).")
        @Config.RangeInt(min = 1, max = 64)
        public int maxAttackersPerTarget = 8;

        @Config.Comment("For hired workers: the melee-attacker cap on a target is also raised to (hired group size / this number), so a big formation can bring enough attackers to actually kill something instead of everyone getting capped at a handful. Lower = more workers allowed to pile on the same target.")
        @Config.RangeInt(min = 1, max = 50)
        public int groupSizeAttackerCapDivisor = 5;

        @Config.Comment("Ticks of inactivity before an attacker's reserved slot on a target is considered stale and freed.")
        @Config.RangeInt(min = 5, max = 200)
        public int attackerSlotStaleTicks = 20;

        @Config.Comment("Max number of entities allowed to run a full target-acquisition scan (nearby-enemy AABB search) in the same tick, world-wide. Prevents CPU spikes during mass battles with many hired workers. Entities that miss the budget simply retry next tick.")
        @Config.RangeInt(min = 4, max = 500)
        public int combatTargetScanBudgetPerTick = 40;

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

        @Config.Comment("How close to their max shooting range archers prefer to stand, as a fraction between min and max distance (0 = hug min range, 1 = hug max range). They'll drift toward this distance from the target while free to move.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float rangedPreferredDistFactor = 0.72f;

        @Config.Comment("Speed multiplier archers use while repositioning to a spot where they can see their target again, on top of their normal aiming movement speed. They keep the bow drawn the whole time so they can shoot the instant they see the target.")
        @Config.RangeDouble(min = 1.0, max = 3.0)
        public float rangedNoLosSpeedMultiplier = 1.3f;

        @Config.Comment("Speed multiplier archers use while sidestepping to find a clear shot when a friendly is standing in the way.")
        @Config.RangeDouble(min = 1.0, max = 3.0)
        public float rangedBlockedSidestepSpeedMultiplier = 1.5f;

        @Config.Comment("Base arrow damage in half-hearts, matching a vanilla skeleton's unarmored hit (no bow enchantment).")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public float rangedBaseDamage = 2.0f;

        @Config.Comment("Extra arrow damage added per level of the Power enchantment on the bow actually used, so a better bow still hits harder without it being excessive.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float rangedPowerDamagePerLevel = 0.5f;

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
        public float attackCooldownMultiplier = 1.5f;

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

    public static class Equipment {
        @Config.Comment("If false, spawn equipment is picked with no quality weighting (old behaviour: purely random pick from everything available).")
        public boolean rarityWeightingEnabled = true;

        @Config.Comment("Chance (out of common+good+rare) of a plain/basic weapon or armor piece at spawn.")
        @Config.RangeInt(min = 0, max = 100)
        public int commonWeight = 50;

        @Config.Comment("Chance (out of common+good+rare) of a solid mid-tier weapon or armor piece at spawn.")
        @Config.RangeInt(min = 0, max = 100)
        public int goodWeight = 35;

        @Config.Comment("Chance (out of common+good+rare) of a top-tier weapon or armor piece at spawn.")
        @Config.RangeInt(min = 0, max = 100)
        public int rareWeight = 8;

        @Config.Comment("Same as commonWeight/goodWeight/rareWeight above, but only for raiders. Lets raiders spawn better equipped on average.")
        @Config.RangeInt(min = 0, max = 100)
        public int raiderCommonWeight = 30;

        @Config.Comment("Raider equivalent of goodWeight.")
        @Config.RangeInt(min = 0, max = 100)
        public int raiderGoodWeight = 45;

        @Config.Comment("Raider equivalent of rareWeight.")
        @Config.RangeInt(min = 0, max = 100)
        public int raiderRareWeight = 12;

        @Config.Comment("Same as commonWeight/goodWeight/rareWeight above, but only for hired workers.")
        @Config.RangeInt(min = 0, max = 100)
        public int workerCommonWeight = 50;

        @Config.Comment("Worker equivalent of goodWeight.")
        @Config.RangeInt(min = 0, max = 100)
        public int workerGoodWeight = 35;

        @Config.Comment("Worker equivalent of rareWeight.")
        @Config.RangeInt(min = 0, max = 100)
        public int workerRareWeight = 15;

        @Config.Comment("Chance to spawn with a helmet.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float armorChanceHead = 0.3f;

        @Config.Comment("Chance to spawn with a chestplate.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float armorChanceChest = 0.25f;

        @Config.Comment("Chance to spawn with leggings.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float armorChanceLegs = 0.25f;

        @Config.Comment("Chance to spawn with boots.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float armorChanceFeet = 0.25f;

        @Config.Comment("Multiplier applied to the 4 armor chances above for raiders (1.0 = no change).")
        @Config.RangeDouble(min = 0.1, max = 5.0)
        public float raiderArmorChanceMultiplier = 1.9f;

        @Config.Comment("Multiplier applied to the 4 armor chances above for hired workers (1.0 = no change).")
        @Config.RangeDouble(min = 0.1, max = 5.0)
        public float workerArmorChanceMultiplier = 1.0f;

        @Config.Comment("Chance to equip a bow instead of a melee weapon at spawn, when no better data (defaultweapon/startingInv) says otherwise.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float bowChance = 0.4f;

        @Config.Comment("Chance to prefer a culture's own signature gear (e.g. Norman broadsword/plate for Normans) over generic items when both are available at spawn.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float cultureSignaturePreferenceChance = 0.70f;
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
        public double returnFollowSwitchDist = 12.0;

        @Config.Comment("Ticks between formation chunk recomputation.")
        @Config.RangeInt(min = 1, max = 100)
        public int chunkRecomputeIntervalTicks = 5;

        @Config.Comment("Max number of workers standing side-by-side in a formation row. Keeps big groups from spreading too wide to reach their spot.")
        @Config.RangeInt(min = 2, max = 16)
        public int maxRowWidth = 16;

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
        public double arriveRadius = 2.3;

        @Config.Comment("How close workers stay behind you.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double leaderDistancePlayer = 1.3;

        @Config.Comment("How close workers stay behind a worker acting as their group leader.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double leaderDistanceWorkerLeader = 1.3;

        @Config.Comment("Distance (blocks) between rows while underground.")
        @Config.RangeDouble(min = 0.5, max = 4.0)
        public double caveRowSpacing = 1.0;

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

        @Config.Comment("Let formation followers react sooner when their leader moves fast (a sprinting player, or a worker leader traveling at speed), instead of waiting until they've already fallen far behind.")
        public boolean fastLeaderPriorityBoostEnabled = true;

        @Config.Comment("How fast (blocks per tick) a leader must be moving to count as 'moving fast'. Works for both a sprinting player and a fast-traveling worker leader.")
        @Config.RangeDouble(min = 0.05, max = 1.0)
        public double fastLeaderSpeedThreshold = 0.25;

        @Config.Comment("Lowest priority a formation follower gets while its leader moves fast, so it asks for a path update sooner instead of waiting for the usual distance thresholds.")
        @Config.RangeInt(min = 0, max = 2)
        public int fastLeaderMinPriority = 1;

        @Config.Comment("Longest a formation follower waits between path updates while its leader moves fast (in ticks, 20 = 1 second).")
        @Config.RangeInt(min = 2, max = 40)
        public int fastLeaderRepathIntervalCapTicks = 10;

        @Config.Comment("How far (blocks) an offscreen worker must be from its formation spot before it walks back faster than usual (e.g. after being scattered by a fight).")
        @Config.RangeDouble(min = 2.0, max = 32.0)
        public double virtualRegroupTriggerDistance = 4.0;

        @Config.Comment("How much faster an offscreen worker walks back to its formation spot once it's far enough away to trigger the speed-up above.")
        @Config.RangeDouble(min = 1.0, max = 4.0)
        public double virtualRegroupSpeedMultiplier = 1.25;

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

        @Config.Comment("A worker prefers cheap direct steering toward its formation spot over full pathfinding. It only requests a real path after being stuck (blocked, no way around) for this many ticks in a row - avoids paying for pathfinding on open ground where steering already works fine. Once a real path is found, it is followed until finished before going back to steering.")
        @Config.RangeInt(min = 1, max = 40)
        public int steeringStuckTicksBeforePathfind = 4;

        @Config.Comment("Let a worker blocked by an obstacle follow a path a nearby groupmate just found, instead of finding its own.")
        public boolean sharedRouteCorridorEnabled = true;

        @Config.Comment("How long (in ticks, 20 = 1 second) a found path stays usable by other workers before it's considered stale.")
        @Config.RangeInt(min = 20, max = 600)
        public int sharedRouteCorridorTtlTicks = 160;

        @Config.Comment("How far (blocks) a worker can be from a groupmate's path and still jump onto it.")
        @Config.RangeDouble(min = 2.0, max = 16.0)
        public double sharedRouteCorridorMaxSnapDistance = 8.0;

        @Config.Comment("Deprecated - replaced by HazardSafety, which applies the same kind of nudge to every traveller/raider/worker, not just hired formation followers. Kept only so old config files don't break; no longer read by any code.")
        public boolean lavaRepulsionEnabled = true;

        @Config.Comment("Deprecated - see lavaRepulsionEnabled.")
        @Config.RangeDouble(min = 0.5, max = 3.0)
        public double lavaRepulsionRadius = 1.3;

        @Config.Comment("Deprecated - see lavaRepulsionEnabled.")
        @Config.RangeDouble(min = 0.0, max = 0.2)
        public double lavaRepulsionStrength = 0.045;

        @Config.Comment("Let a worker that's fully blocked by an obstacle (no detour, no shared path) ask for a real route sooner instead of waiting.")
        public boolean steeringBlockedFastEscalationEnabled = true;

        @Config.Comment("How long (in ticks, 20 = 1 second) a worker can be fully blocked by an obstacle before it asks for a real route sooner.")
        @Config.RangeInt(min = 10, max = 200)
        public int steeringBlockedEscalationGraceTicks = 30;

        @Config.Comment("Max number of real pathfinding requests allowed across all owners in a single tick.")
        @Config.RangeInt(min = 10, max = 2000)
        public int pathSchedulerGlobalMaxPathsPerTick = 160;

        @Config.Comment("How many pathfinding requests per second a single owner's workers are allowed on average.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public double pathSchedulerOwnerPathsPerSecond = 120.0;

        @Config.Comment("How many pathfinding requests an owner's workers can burst through at once before the per-second limit kicks in.")
        @Config.RangeDouble(min = 1.0, max = 200.0)
        public double pathSchedulerOwnerMaxBurst = 20.0;

        @Config.Comment("Extra pathfinding requests per second added to an owner's budget for each worker in their formation, so a group of 100+ isn't stuck sharing the same small budget as a group of 2.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double pathSchedulerOwnerPerFollowerBonus = 0.6;

        @Config.Comment("Extra burst capacity added to an owner's budget for each worker in their formation.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double pathSchedulerOwnerBurstPerFollowerBonus = 0.1;

        @Config.Comment("Max number of workers counted toward the per-worker budget bonus above. Keeps one huge group from claiming an unlimited share — the global per-tick cap above still protects overall server performance regardless.")
        @Config.RangeInt(min = 10, max = 500)
        public int pathSchedulerOwnerBonusGroupSizeCap = 150;

        @Config.Comment("How far (blocks) a follower's target spot can shift before it gives up reusing its current path and asks for a new one.")
        @Config.RangeDouble(min = 0.5, max = 6.0)
        public double pathReuseGoalShiftMaxBlocks = 2.0;

        @Config.Comment("Max distance (blocks) from its target spot a follower can be and still just reuse its current path instead of recalculating.")
        @Config.RangeDouble(min = 4.0, max = 24.0)
        public double pathReuseWorkerToGoalMaxBlocks = 12.0;

        @Config.Comment("How long (in ticks, 20 = 1 second) an owner's pathfinding budget is kept around after they're no longer seen.")
        @Config.RangeInt(min = 20, max = 72000)
        public int pathSchedulerOwnerStateExpireTicks = 20 * 30;

        @Config.Comment("How long (in ticks, 20 = 1 second) a follower's pathfinding history is kept around after it's no longer seen.")
        @Config.RangeInt(min = 20, max = 72000)
        public int pathSchedulerFollowerStateExpireTicks = 20 * 12;

        @Config.Comment("Max vertical distance (blocks) a worker can be from its formation slot and still count as 'arrived'. Keep this small - a worker that fell into a pit below its slot must NOT be mistaken for one that arrived, or it stops trying to escape.")
        @Config.RangeDouble(min = 1.0, max = 8.0)
        public double formationSettleVerticalTolerance = 3.0;

        @Config.Comment("How long (in ticks, 20 = 1 second) a worker with no path must make zero progress toward its slot before it's rescued with a short teleport out of a pit.")
        @Config.RangeInt(min = 20, max = 400)
        public int pitRescueStagnationTicks = 120;

        @Config.Comment("How many failed pathfinding attempts a trapped worker needs before the pit-rescue teleport is allowed to fire.")
        @Config.RangeInt(min = 5, max = 100)
        public int pitRescuePathFailTicks = 20;

        @Config.Comment("Max distance (blocks) a single pit-rescue teleport hop can cover. Kept short so a rescued worker steps discreetly out of trouble instead of jumping far across the map.")
        @Config.RangeDouble(min = 2.0, max = 12.0)
        public double pitRescueStepDistance = 5.0;

        @Config.Comment("How long (in ticks, 20 = 1 second) a worker following a VIRTUAL (offscreen) leader can make zero progress toward its slot before it gets the same short pit-rescue teleport as when the leader is loaded.")
        @Config.RangeInt(min = 40, max = 600)
        public int virtualLeaderPitRescueTicks = 200;
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

        @Config.Comment("Hard cap on the total number of travellers + raiders in the world (loaded and offscreen combined). Workers and merchants have their own separate caps below and are not counted here.")
        @Config.RangeInt(min = 0, max = 10000)
        public int globalPopulationCap = 2000;

        @Config.Comment("Max number of new travellers/raiders/workers created per refresh cycle. Higher = faster repopulation after /travellersdebug reset, but more spawns happening at once.")
        @Config.RangeInt(min = 1, max = 500)
        public int globalPopulationMaxSpawnsPerCycle = 60;

        @Config.Comment("Log detailed per-village spawn attempt results to the server console (why a traveller/raider/worker was or wasn't spawned this cycle). Very spammy, for diagnosing spawn issues only.")
        public boolean populationDebugLog = false;
    }

    public static class Materialization {
        @Config.Comment("Stop travellers/raiders/workers from appearing inside another group's campsite shelter when they reappear near a player.")
        public boolean avoidCampFootprints = true;

        @Config.Comment("Extra safety gap (in blocks) kept around a camp shelter when pushing an unrelated entity out of it.")
        @Config.RangeInt(min = 0, max = 5)
        public int campFootprintMargin = 1;

        @Config.Comment("Stop travellers/raiders/workers from appearing on top of tree leaves or logs when they reappear near a player.")
        public boolean avoidTreeCanopy = true;

        @Config.Comment("Stop travellers/raiders/workers from appearing on top of lava, water, or other hazardous liquids when they reappear near a player.")
        public boolean avoidHazardLiquids = true;

        @Config.Comment("When a traveller/raider/worker has wandered away from where it went out of view, make it reappear on the surface instead of dropping into a cave (sometimes next to lava). Anyone that did NOT move reappears exactly where it was, whether that was underground, in a building, or on the surface.")
        public boolean surfaceClampOnMaterialize = true;

        @Config.Comment("Travellers/raiders/workers now appear and disappear based on whether the ground chunk under them is actually loaded (by any player's view distance, or force-loaded by this or another mod) rather than raw distance to a player. This is how many ticks a chunk must stay continuously loaded before an offscreen unit reappears there. Prevents a brief, incidental chunk load (e.g. a background map/camp task) from popping a unit in and out.")
        @Config.RangeInt(min = 0, max = 1200)
        public int materializeChunkStableTicks = 40;

        @Config.Comment("How many ticks a real traveller/raider/worker's chunk must stay continuously unneeded (nobody watching it, nothing force-loading it) before it turns back into an offscreen unit. Prevents a one-tick loading hiccup from popping a unit out and back in.")
        @Config.RangeInt(min = 0, max = 1200)
        public int virtualizeChunkUnneededTicks = 40;
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

        @Config.Comment("If a traveller/raider/worker walking toward a destination makes no real progress for this many ticks while already far from every player, it stops trying to detour/teleport around the obstacle and turns virtual right away instead, continuing its journey offscreen. Only matters right at the edge of loaded chunks; harmless everywhere else. Lower = gives up trying to walk through sooner.")
        @Config.RangeInt(min = 20, max = 1200)
        public int edgeStuckVirtualizeGraceTicks = 200;

        @Config.Comment("Minimum distance (blocks) from every player required before a traveller/raider/worker that is genuinely blocked (nothing loaded in any direction it tried) is allowed to turn virtual. Small on purpose - it only exists to stop this from happening right in front of someone on a very small view distance setting. Set to 0 to remove the check entirely.")
        @Config.RangeInt(min = 0, max = 256)
        public int edgeStuckMinPlayerDistance = 24;
    }

    public static class GapJump {
        @Config.Comment("Widest gap (in blocks) a traveller/raider/worker can jump across, roughly a player's sprint-jump.")
        @Config.RangeInt(min = 1, max = 6)
        public int maxGapWidth = 3;

        @Config.Comment("How close (in blocks) to their own standing height a landing spot must be to count as 'the other side' of the gap. Keeps them from jumping partway in and landing at the bottom of a ditch instead of clearing it.")
        @Config.RangeInt(min = 0, max = 3)
        public int landingHeightTolerance = 1;

        @Config.Comment("For a real hazard only (lava, water, a pit with no bottom in reach): how far below their standing height a landing is still allowed to be, once no closer same-height landing exists. Lets them jump down onto a lower ledge across a chasm instead of refusing the jump.")
        @Config.RangeInt(min = 1, max = 10)
        public int maxHazardFallTolerance = 4;

        @Config.Comment("If true, the pathfinder itself can plan a route across a jumpable gap instead of only reacting to one it happens to be walking toward. Lets it choose to jump when that is actually shorter than walking around.")
        public boolean pathfinderAware = true;

        @Config.Comment("Extra path cost added to a jump option, on top of its real distance. Higher values make the pathfinder prefer walking around a gap unless jumping is clearly shorter.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double pathfinderJumpCostMalus = 0.5;

        @Config.Comment("Ticks after landing before another jump can fire, before adding the per-width bonus below. Kept short so closely spaced gaps (an obstacle course) can be jumped back to back.")
        @Config.RangeInt(min = 0, max = 100)
        public int postJumpCooldownBaseTicks = 5;

        @Config.Comment("Extra cooldown ticks added per block of gap width just jumped, on top of the base cooldown above.")
        @Config.RangeInt(min = 0, max = 20)
        public int postJumpCooldownPerWidthTicks = 2;

        @Config.Comment("Hardest horizontal speed (blocks per tick) a jump is ever allowed to push someone at. Keeps a long jump from looking like a teleport - it may land a little short instead of overshooting. Needs to stay well above the speed a normal jumpable gap actually requires, or every jump falls short by design.")
        @Config.RangeDouble(min = 0.2, max = 1.5)
        public double maxJumpHorizontalSpeed = 1.0;

        @Config.Comment("Extra jump height added per block of gap width beyond 1 - a longer jump is a bit more of a leap, like a player jumping harder for a longer gap, not just moving faster in the air.")
        @Config.RangeDouble(min = 0.0, max = 0.2)
        public double jumpHeightBoostPerGapBlock = 0.035;

        @Config.Comment("Highest jump height (initial upward speed) allowed, however wide the gap is.")
        @Config.RangeDouble(min = 0.42, max = 1.0)
        public double maxJumpVerticalVelocity = 0.55;

        @Config.Comment("How much of the jump's horizontal speed is kept the instant it lands (0 = stops dead, 1 = keeps skating at full speed). Landing on a small platform (a narrow pillar) needs a low value or the leftover speed just carries them straight across it and off the far edge.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double landingMomentumRetention = 0.05;

        @Config.Comment("If a jump lands within this many blocks of where it was actually aimed, snap onto that exact spot instead of leaving them slightly off-center. Keeps landings precise on small platforms. A real disruption mid-jump (getting hit, bumped by another entity) that throws them further off than this is still respected and never gets corrected.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double landingSnapMaxCorrection = 0.75;

        @Config.Comment("Log every gap-jump decision (why one was attempted, skipped, or refused) to the server log. Very spammy - only for diagnosing jump problems.")
        public boolean debugGapJump = false;
    }

    public static class HazardSafety {
        @Config.Comment("Constantly nudges every traveller/raider/worker away from nearby lava, fire and cliffs/void, on top of their normal AI. A crowded group can shove a member sideways regardless of what its own AI wants to do - this makes stumbling into a fatal drop far less likely without stopping a deliberate crossing (the nudge is gentle, real steering/pathing easily overrides it).")
        public boolean enabled = true;

        @Config.Comment("How close (blocks) to a hazard column this nudge starts applying.")
        @Config.RangeDouble(min = 0.5, max = 3.0)
        public double scanRadius = 1.4;

        @Config.Comment("How strong the away-from-lava/fire nudge is at its strongest (right at the edge). Keep this well below normal movement speed or it will fight a deliberate lava-jump approach.")
        @Config.RangeDouble(min = 0.0, max = 0.2)
        public double hazardStrength = 0.045;

        @Config.Comment("How strong the away-from-water nudge is. Kept low/zero by default - water is a normal crossing medium (fording, swimming after a leader), not a real danger.")
        @Config.RangeDouble(min = 0.0, max = 0.2)
        public double waterStrength = 0.0;

        @Config.Comment("Drops shallower than this (blocks) are treated as normal terrain (stairs, a ledge) and never pushed away from - matches vanilla's own fall-damage-free range.")
        @Config.RangeInt(min = 1, max = 8)
        public int cliffMinDropDepth = 4;

        @Config.Comment("Drops at or beyond this depth (blocks), or a column with no floor found at all (bottomless/void), get the strongest cliff push.")
        @Config.RangeInt(min = 4, max = 32)
        public int cliffMaxScanDepth = 10;

        @Config.Comment("How strong the away-from-cliff nudge is at its strongest (a very deep drop or void).")
        @Config.RangeDouble(min = 0.0, max = 0.2)
        public double cliffStrength = 0.05;

        @Config.Comment("Multiplies the whole nudge while actively fighting (has an attack or revenge target), so it never makes an entity hesitate to close in on an enemy near a ledge.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double combatRepulsionFactor = 0.3;

        @Config.Comment("Log every non-trivial hazard nudge applied to the server log. Very spammy - only for diagnosing edge/lava incidents.")
        public boolean debugHazardRepulsion = false;
    }

    public static class Sound {
        @Config.Comment("If true, travellers play a voice line when right-clicked.")
        public boolean travellerSpeaks = true;

        @Config.Comment("If true, travellers occasionally mutter while travelling.")
        public boolean travellerAmbient = true;

        @Config.Comment("Minimum ticks between solo ambient voice lines.")
        @Config.RangeInt(min = 40, max = 20000)
        public int travellerAmbientMinDelay = 2400;

        @Config.Comment("Maximum ticks between solo ambient voice lines.")
        @Config.RangeInt(min = 80, max = 60000)
        public int travellerAmbientMaxDelay = 7200;

        @Config.Comment("Volume for solo ambient travel voice lines, for a player with no volume-boosting mods.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float travellerAmbientVolume = 1.0f;

        @Config.Comment("Volume for choir/group singing, for a player with no volume-boosting mods.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float travellerChoirVolume = 1.0f;

        @Config.Comment("Volume for talk and hello voice lines, for a player with no volume-boosting mods.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float travellerTalkVolume = 1.0f;

        @Config.Comment("Volume for hurt sounds, for a player with no volume-boosting mods.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float travellerHurtVolume = 1.0f;

        @Config.Comment("Minimum ticks before the same entity can play a hurt sound again. Low values make a big battle sound like a constant, overlapping scream instead of individual, audible hits.")
        @Config.RangeInt(min = 5, max = 200)
        public int hurtSoundEntityCooldownTicks = 30;

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
        public int attackSoundEntityCooldownTicks = 120;

        @Config.Comment("Minimum ticks before another shout is allowed within the same 64-block zone (prevents group spam).")
        @Config.RangeInt(min = 40, max = 12000)
        public int attackSoundZoneCooldownTicks = 240;

        @Config.Comment("Maximum new sounds that can start in a single tick (throttle to prevent audio spam).")
        @Config.RangeInt(min = 1, max = 20)
        public int maxNewSoundsPerTick = 10;

        @Config.Comment("Volume for attack shout voice lines, for a player with no volume-boosting mods.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float attackSoundVolume = 1.0f;

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
        public int creeperWarnCooldownTicks = 100;

        @Config.Comment("Volume for creeper warning voice lines, for a player with no volume-boosting mods.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float creeperWarnVolume = 1.0f;

        @Config.Comment("If true, travellers and raiders play a voice line when they kill an entity.")
        public boolean killSoundEnabled = true;

        @Config.Comment("Minimum ticks before the same entity can play a kill sound again.")
        @Config.RangeInt(min = 20, max = 6000)
        public int killSoundEntityCooldownTicks = 100;

        @Config.Comment("Minimum ticks before another kill sound is allowed within the same 64-block zone.")
        @Config.RangeInt(min = 40, max = 12000)
        public int killSoundZoneCooldownTicks = 60;

        @Config.Comment("Volume for kill voice lines, for a player with no volume-boosting mods.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float killSoundVolume = 1.0f;

        @Config.Comment("If true, travellers and raiders play a voice line when they die.")
        public boolean deathSoundEnabled = true;

        @Config.Comment("Minimum ticks before another death sound is allowed within the same 64-block zone.")
        @Config.RangeInt(min = 40, max = 12000)
        public int deathSoundZoneCooldownTicks = 60;

        @Config.Comment("Volume for death voice lines, for a player with no volume-boosting mods.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public float deathSoundVolume = 1.0f;

        @Config.Comment("Extra volume multiplier automatically applied to all traveller voice sounds only when the Sound Physics mod is detected. Sound Physics needs a louder source to reach the same distance because it computes its own realistic falloff, so this keeps range consistent without making sounds too loud for players who don't have it installed.")
        @Config.RangeDouble(min = 1.0, max = 8.0)
        public float soundPhysicsVolumeBoost = 4.0f;

        @Config.Comment("How long (milliseconds) a voice sound takes to fade out instead of cutting instantly. Applies whenever a sound is interrupted, replaced, or its speaker disappears (dies, unloads).")
        @Config.RangeInt(min = 50, max = 3000)
        public int voiceFadeOutMs = 400;

        // --- Attenuation (no-SoundPhysics fallback) ---
        @Config.Comment("Distance (blocks) at which voice sounds play at full volume. Only used when SoundPhysics is absent.")
        @Config.RangeDouble(min = 0.5, max = 16.0)
        public double voiceReferenceDistance = 1.0;

        @Config.Comment("Distance (blocks) beyond which voice sounds become inaudible entirely, with or without SoundPhysics. This is the main setting to raise or lower how far away travellers can be heard talking/singing/shouting.")
        @Config.RangeDouble(min = 8.0, max = 128.0)
        public double voiceMaxDistance = 48.0;

        @Config.Comment("Distance rolloff exponent when SoundPhysics is absent. 1.0 = natural 1/r (recommended), 2.0 = inverse-square (fast fade), 0.5 = gentle.")
        @Config.RangeDouble(min = 0.25, max = 4.0)
        public double voiceRolloffPower = 1.0;

        @Config.Comment("Max simultaneous entity-linked voice sounds (ambient + talk + hurt + choir combined). Prevents OpenAL source pool exhaustion. Lower if you get 'Channel null' errors.")
        @Config.RangeInt(min = 4, max = 30)
        public int maxConcurrentVoiceSounds = 30;

        @Config.Comment("Log every sound play/stop event to the client log (for debugging CodecJOrbis errors). Disable in production.")
        public boolean soundDebugLog = false;

        @Config.Comment("Ticks after spawning before a traveller can play its first ambient voice line.")
        @Config.RangeInt(min = 0, max = 12000)
        public int spawnAmbientDelayTicks = 1200;
    }

    public static class Choir {
        @Config.Comment("Enable the traveller choir system.")
        public boolean choirEnabled = true;

        @Config.Comment("Max radius (blocks) to detect nearby travellers for a choir group.")
        @Config.RangeDouble(min = 4.0, max = 48.0)
        public float choirGroupRadius = 8.0f;

        @Config.Comment("If a singer drifts this far from choir center, the session is flagged.")
        @Config.RangeDouble(min = 8.0, max = 96.0)
        public float choirBreakRadius = 25.0f;

        @Config.Comment("Probability per cooldown cycle that an auto-choir attempt is made (0.0–1.0).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float choirAutoChance = 0.4f;

        @Config.Comment("Minimum ticks between auto-choir session attempts per dimension.")
        @Config.RangeInt(min = 20, max = 144000)
        public int choirAutoCooldownTicks = 3200;

        @Config.Comment("How long a choir session lasts in ticks.")
        @Config.RangeInt(min = 100, max = 36000)
        public int choirSessionDurationTicks = 6700;

        @Config.Comment("Consecutive spread-check failures before stopping the choir.")
        @Config.RangeInt(min = 1, max = 20)
        public int choirSpreadGraceChecks = 3;
    }

    public static class Camp {
        @Config.Comment("If true, travellers will sometimes make camps with a firepit.")
        public boolean campsEnabled = false;

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
        public int campJoinCooldownTicks = 7200;

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
        public boolean enabled = false;

        @Config.Comment("Radius (blocks) of a campsite: shelters and firepit are placed within this distance of the center.")
        @Config.RangeInt(min = 4, max = 64)
        public int campsiteRadius = 32;

        @Config.Comment("Minimum distance (blocks) between two campsites.")
        @Config.RangeInt(min = 16, max = 2048)
        public int campsiteMinSpacing = 128;

        @Config.Comment("Campsites are never discovered closer than this (blocks) to any Millenaire village.")
        @Config.RangeInt(min = 0, max = 2048)
        public int campsiteMinVillageDistance = 300;

        @Config.Comment("Max chunk-discovery probes processed per server tick (keeps exploration cheap).")
        @Config.RangeInt(min = 1, max = 64)
        public int discoveryProbesPerTick = 8;

        @Config.Comment("Chance (0-1) that an eligible freshly-loaded chunk is registered as a campsite candidate.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double discoveryChancePerChunk = 0.15;

        @Config.Comment("A campsite must hold at least this many valid shelter slots to be kept.")
        @Config.RangeInt(min = 1, max = 16)
        public int minShelterSlots = 4;

        @Config.Comment("Maximum shelter slots recorded per campsite.")
        @Config.RangeInt(min = 1, max = 256)
        public int maxShelterSlots = 64;

        @Config.Comment("Minimum gap (blocks) between two shelter footprints in a campsite.")
        @Config.RangeInt(min = 0, max = 8)
        public int shelterGap = 2;

        @Config.Comment("Max ground-height variation (blocks) tolerated across a footprint; the footprint is graded flat when a shelter is built. Higher = works on rougher (RTG/modded) terrain.")
        @Config.RangeInt(min = 0, max = 6)
        public int flatTolerance = 3;

        @Config.Comment("How close (blocks) a traveller must pass a campsite center to consider stopping.")
        @Config.RangeInt(min = 4, max = 128)
        public int campSeekRadius = 32;

        @Config.Comment("Chance (0-1), per roll, that a traveller passing a campsite during the day stops to camp.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double daytimeStopChance = 0.02;

        @Config.Comment("Minimum time, in ticks, between two daytime camp-stop rolls for the same traveller (20 ticks = 1s).")
        @Config.RangeInt(min = 20, max = 72000)
        public int daytimeStopRollIntervalTicks = 7200;

        @Config.Comment("Minimum daytime camp pause, in ticks (20 ticks = 1s).")
        @Config.RangeInt(min = 20, max = 240000)
        public int daytimePauseMinTicks = 2400;

        @Config.Comment("Maximum daytime camp pause, in ticks.")
        @Config.RangeInt(min = 20, max = 240000)
        public int daytimePauseMaxTicks = 5200;

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
        public double daytimeNapChance = 0.25;

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
        public boolean campCultureRestrictionEnabled = true;

        @Config.Comment("If true, raiders ignore campCultureRestrictionEnabled and may join/found a raider camp of any culture.")
        public boolean raidersIgnoreCulture = true;

        @Config.Comment("How many campsites '/travellersdebug camp reset' processes per tick (each one briefly loads its chunks to restore terrain). Lower = less lag on servers with many campsites, but a slower reset.")
        @Config.RangeInt(min = 1, max = 50)
        public int campResetCampsitesPerTick = 3;

        @Config.Comment("How often (in ticks; 200 = 10 seconds) '/travellersdebug camp reset' reports its progress in chat.")
        @Config.RangeInt(min = 20, max = 12000)
        public int campResetProgressIntervalTicks = 200;

        @Config.Comment("How many blocks in front of a shelter's entrance are checked for walkable ground before accepting a camp slot, so shelters don't get built facing a cliff or ledge.")
        @Config.RangeInt(min = 1, max = 8)
        public int entranceCheckDistance = 3;

        @Config.Comment("Maximum ground height difference (blocks) allowed in front of a shelter's entrance compared to its floor. A bigger drop/rise is treated as a cliff and that entrance direction is rejected.")
        @Config.RangeInt(min = 1, max = 8)
        public int entranceMaxStepDiff = 2;

        @Config.Comment("Extra block IDs (registry names, e.g. 'somemod:quicksand') that camps must never be built on, in addition to the automatic checks for liquids and modded fluid blocks. Use this for hazards (quicksand, gas, acid, etc.) that aren't detected automatically. Leave empty for no extra restrictions.")
        public String[] terrainHazardBlacklist = new String[]{};
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
        public double meleeReach = 1.0;

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

        @Config.Comment("Minimum number of enemies fighting your workers before you get notified. Stops tiny skirmishes from spamming you.")
        @Config.RangeInt(min = 1, max = 64)
        public int notifyMinEnemyCount = 2;

        @Config.Comment("Enemies must outnumber your workers in the fight by at least this much (0.5 = half as many enemies as workers) before you get notified.")
        @Config.RangeDouble(min = 0.0, max = 5.0)
        public double notifyMinEnemyToWorkerRatio = 0.5;

        @Config.Comment("If on, a worker's death always notifies you, ignoring the enemy-count/ratio thresholds above. If off, deaths use the same thresholds as the battle-start notice.")
        public boolean notifyDeathAlwaysNotifies = true;

        @Config.Comment("Also detect and notify about fights between hired workers, raiders and other players' workers while everyone involved is in a loaded area (not just offscreen virtual battles).")
        public boolean detectLoadedEncounters = true;

        @Config.Comment("Virtual travellers/workers regain health offscreen after combat, mirroring the physical EntityAIAutoEat AI (raiders excluded, they don't auto-eat physically either).")
        public boolean virtualCombatHealingEnabled = true;

        @Config.Comment("Ticks after last combat before offscreen healing can start (mirrors EntityAIAutoEat's physical post-combat lockout, 30-50 ticks).")
        @Config.RangeInt(min = 0, max = 2400)
        public int postCombatHealDelayTicks = 120;
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
        public boolean requireControlledVillageToHire = false;

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

        @Config.Comment("Print merchant donation activity to the log. Only useful for debugging.")
        public boolean merchantDebugLog = false;
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

        // --- Shared wildcard exclusion (applies to traveller/raider/worker pools alike) ---
        @Config.Comment("Villager filenames (without .txt) excluded from ALL pools (traveller/raider/worker), with * wildcard support. E.g.: special*,quest*")
        public String[] excludedVillagerFileWildcards = new String[]{"special*", "quest*", "*farm*"};

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
        public String[] travellerAggroBlacklistClasses = new String[]{"net.minecraft.entity.monster.EntityGhast", "net.minecraft.entity.monster.EntityEnderman"};

        @Config.Comment("Entity registry IDs that raiders will NOT aggro. Supports wildcards like modid:*")
        public String[] raiderAggroBlacklistIds = new String[]{"minecraft:creeper", "minecraft:enderman"};

        @Config.Comment("Fully-qualified class names that raiders will NOT aggro (subclasses match).")
        public String[] raiderAggroBlacklistClasses = new String[]{};
    }

    public static class AttackRules {
        @Config.Comment("Entity IDs raiders will attack (exact or modid:*).")
        public String[] raiderAttackIdRules = new String[]{"millenaire:genericvillager", "millenaire:genericasimmfemale", "millenaire:genericsimmfemale", "travellers:traveller", "travellers:traveller_worker"};

        @Config.Comment("Java class names raiders will attack (exact or subclasses).")
        public String[] raiderAttackClassRules = new String[]{};

        @Config.Comment("Entity IDs friendly travellers will treat as dangerous (exact or modid:*).")
        public String[] travellerAttackIdRules = new String[]{"mod_lavacow:zombiemushroom", "mod_lavacow:parasite", "mod_lavacow:ptera"};

        @Config.Comment("Java class names friendly travellers will attack (exact or subclasses). Ancient Warfare 2 faction NPCs (Empire, Bandits, etc.) are NOT listed here - they are handled dynamically by AncientWarfare2Compat, matching each faction's actual reputation with the traveller's owner instead of a fixed list. See COMPAT.ancientWarfare2ReciprocalHostility.")
        public String[] travellerAttackClassRules = new String[]{};

        @Config.Comment("Entity IDs hired workers will treat as dangerous (exact or modid:*). Empty by default - falls back to the traveller attack list above.")
        public String[] workerAttackIdRules = new String[]{"mod_lavacow:parasite", "mod_lavacow:ptera"};

        @Config.Comment("Java class names hired workers will attack (exact or subclasses). Empty by default - falls back to the traveller attack list above.")
        public String[] workerAttackClassRules = new String[]{};
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

        // --- History panel (event log banner in the Formation & Groups screen) ---
        @Config.Comment("Show the event history panel in the Formation & Groups screen.")
        public boolean historyPanelEnabled = true;

        @Config.Comment("Where the history panel appears relative to the Formation & Groups panel. Options: ABOVE, BELOW.")
        public String historyPanelPosition = "BELOW";

        @Config.Comment("Height (pixels) of the history panel.")
        @Config.RangeInt(min = 40, max = 220)
        public int historyPanelHeight = 96;

        @Config.Comment("Height (pixels) of each row in the history panel.")
        @Config.RangeInt(min = 8, max = 20)
        public int historyPanelRowHeight = 11;

        @Config.Comment("Text size in the history panel (1.0 = normal size).")
        @Config.RangeDouble(min = 0.5, max = 1.5)
        public float historyPanelFontScale = 0.8f;

        @Config.Comment("Maximum number of past events kept loaded in the history panel.")
        @Config.RangeInt(min = 10, max = 2000)
        public int historyPanelMaxEntries = 200;

        @Config.Comment("Show a battle icon with a hover tooltip on history entries about a battle.")
        public boolean historyPanelBattleTooltipEnabled = true;
    }

    public static class GuiTooltips {
        @Config.Comment("Show a short description when you hover certain buttons in the Henchman menus.")
        public boolean masterEnabled = true;

        @Config.Comment("How long (milliseconds) you need to hover a button before its description appears.")
        @Config.RangeInt(min = 0, max = 3000)
        public int hoverDelayMs = 500;
    }

    public static class Compat {
        @Config.Comment("If Ancient Warfare 2 is installed: travellers/hired workers (not raiders) will only fight AW2 faction NPCs (Empire, Bandits, etc.) that are actually hostile - a hired worker mirrors its owner's real standing with that faction (same as attacking one yourself would cause), and AW2 NPCs will likewise only attack travellers/workers under the same condition. Ownerless travellers use the faction's default disposition (some factions default hostile, most don't). Does nothing if AW2 is not installed.")
        public boolean ancientWarfare2ReciprocalHostility = true;

        @Config.Comment("Debug log (server console) for Ancient Warfare 2 hostility checks - prints every AncientWarfare2Compat.isHostile() call and result. Very spammy, only for diagnosing the compat feature.")
        public boolean ancientWarfare2DebugLog = false;
    }
}
