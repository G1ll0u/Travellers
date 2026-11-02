package com.jubitus.travellers.config;

import net.minecraftforge.common.config.Config;

public class TravellersConfig {

    public static final General GENERAL = new General();
    public static final Rendering RENDERING = new Rendering();
    public static final Worldmap WORLDMAP = new Worldmap();
    public static final ChunkReader CHUNK_READER = new ChunkReader();
    public static final EntityRenderLod ENTITY_RENDER_LOD = new EntityRenderLod();
    public static final Spawn SPAWN = new Spawn();
    public static final SpawnTravellers SPAWN_TRAVELLERS = new SpawnTravellers();
    public static final SpawnRaiders SPAWN_RAIDERS = new SpawnRaiders();
    public static final SpawnWorkers SPAWN_WORKERS = new SpawnWorkers();
    public static final GroupFollow GROUP_FOLLOW = new GroupFollow();
    public static final Attributes ATTRIBUTES = new Attributes();
    public static final Speed SPEED = new Speed();
    public static final Combat COMBAT = new Combat();
    public static final Equipment EQUIPMENT = new Equipment();
    public static final PlayerAggro PLAYER_AGGRO = new PlayerAggro();
    public static final WorkerFormation WORKER_FORMATION = new WorkerFormation();
    public static final Destinations DESTINATIONS = new Destinations();
    public static final VillageVisit VILLAGE_VISIT = new VillageVisit();
    public static final Gaze GAZE = new Gaze();
    public static final Offscreen OFFSCREEN = new Offscreen();
    public static final Materialization MATERIALIZATION = new Materialization();
    public static final Travel TRAVEL = new Travel();
    public static final GapJump GAP_JUMP = new GapJump();
    public static final Mounts MOUNTS = new Mounts();
    public static final Stables STABLES = new Stables();
    public static final Elytra ELYTRA = new Elytra();
    public static final HazardSafety HAZARD_SAFETY = new HazardSafety();
    public static final Sound SOUND = new Sound();
    public static final Choir CHOIR = new Choir();
    public static final CampEvents CAMP_EVENTS = new CampEvents();
    public static final RestSpots REST_SPOTS = new RestSpots();
    public static final BattleEvents BATTLE_EVENTS = new BattleEvents();
    public static final Fishing FISHING = new Fishing();
    public static final ControlledVillage CONTROLLED_VILLAGE = new ControlledVillage();
    public static final Worker WORKER = new Worker();
    public static final Money MONEY = new Money();
    public static final Recruitment RECRUITMENT = new Recruitment();
    public static final Merchant MERCHANT = new Merchant();
    public static final Filters FILTERS = new Filters();
    public static final AggroBlacklist AGGRO_BLACKLIST = new AggroBlacklist();
    public static final AttackRules ATTACK_RULES = new AttackRules();
    public static final Notifications NOTIFICATIONS = new Notifications();
    public static final GuiTooltips GUI_TOOLTIPS = new GuiTooltips();
    public static final WorkerHub WORKER_HUB = new WorkerHub();
    public static final Compat COMPAT = new Compat();
    public static final Trace TRACE = new Trace();
    public static final Routing ROUTING = new Routing();
    public static final Dialogue DIALOGUE = new Dialogue();
    public static final Raids RAIDS = new Raids();

    public static class Trace {
        @Config.Comment("Record what an individual traveller is doing, tick by tick, into its own file under logs/travellers/trace. Use /travellersdebug trace to pick one.")
        public boolean enabled = false;

        @Config.Comment("Start recording on its own when a traveller gets stuck, so a problem that only happens to a few of them is captured even when you are not watching.")
        public boolean autoCapture = false;

        @Config.Comment("How long to keep recording after an automatic capture starts, in seconds.")
        @Config.RangeInt(min = 5, max = 600)
        public int autoCaptureSeconds = 30;

        @Config.Comment("How many automatic recordings may run at the same time. Keeps one bad spot from filling the folder.")
        @Config.RangeInt(min = 1, max = 20)
        public int autoCaptureMaxConcurrent = 3;

        @Config.Comment("How long a traveller must be trying to move without getting anywhere before it counts as stuck, in seconds.")
        @Config.RangeDouble(min = 0.5, max = 30.0)
        public double incidentStallSeconds = 2.0;

        @Config.Comment("Record one line every this many ticks. 1 gives the most detail and the biggest files.")
        @Config.RangeInt(min = 1, max = 20)
        public int sampleEveryTicks = 1;

        @Config.Comment("Show a live panel on screen with what the traced travellers are doing.")
        public boolean showHud = true;

        @Config.Comment("Movement profile (/travellersdebug profile): how long a traveller must be stuck before it counts as a stall, in seconds.")
        @Config.RangeDouble(min = 1.0, max = 60.0)
        public double profileStallSeconds = 3.0;

        @Config.Comment("Movement profile: how far from its place in the formation a follower must be to count as lagging behind, in blocks.")
        @Config.RangeDouble(min = 2.0, max = 128.0)
        public double profileFollowerLagBlocks = 12.0;

        @Config.Comment("Movement profile: how many of the worst stalls the report lists.")
        @Config.RangeInt(min = 1, max = 100)
        public int profileWorstCases = 10;
    }

    public static class General {
        @Config.Comment("Enable traveller entity (disable = no travellers spawn at all).")
        public boolean enableTravellerEntity = true;

        @Config.Comment("Enable raider traveller variant.")
        public boolean enableTravellerRaider = true;

        @Config.Comment("Enable debug logging.")
        public boolean debug = false;

        @Config.Comment("Allow travellers to open fence gates.")
        public boolean enableTravellerOpenFenceGates = true;

        @Config.Comment("Allow travellers to open doors.")
        public boolean enableTravellerOpenDoors = true;

        @Config.Comment("Travellers close doors and fence gates behind them once they have passed through. Turn off to leave everything open.")
        public boolean travellerCloseDoorsBehind = true;

        @Config.Comment("Let travellers also operate iron and other non-wood doors. Off by default, like vanilla villagers.")
        public boolean travellerOperateMetalDoors = false;

        @Config.Comment("Enable debug logging for follower teleport rescues.")
        public boolean debugFollowTeleport = false;
    }

    public static class Rendering {
        @Config.Comment("Show name tags above travellers heads.")
        public boolean showTravellerNames = false;

        @Config.Comment("Colour of the armband every raider wears, as a hex code (000000 is black, FF0000 is red).")
        public String raiderMarkColor = "000000";
    }

    public static class ChunkReader {
        @Config.Comment("How many newly loaded chunks are read per tick to fill the world map and the travellers' terrain knowledge. A chunk that unloads before its turn is still read, so a lower value only makes the map fill a little slower, never leaves holes.")
        @Config.RangeInt(min = 1, max = 256)
        public int chunksPerTick = 16;

        @Config.Comment("Same as above, but when nobody is online (for world pre-generation).")
        @Config.RangeInt(min = 1, max = 4096)
        public int chunksPerTickEmptyServer = 512;

        @Config.Comment("How many changed chunks (a block placed or broken on the surface, a village being built, freshly decorated terrain) are re-read per tick to update the world map and the travellers' terrain knowledge. A changed chunk that unloads first is still re-read, so a lower value only delays the update.")
        @Config.RangeInt(min = 1, max = 256)
        public int changedChunksPerTick = 16;
    }

    public static class Worldmap {
        @Config.Comment("Opportunistically fill the worldmap whenever a chunk loads (player exploring, worker travel, or a world pregeneration tool). Never affects per-player fog of war.")
        public boolean mapPregenOnChunkLoadEnabled = true;

        @Config.Comment("Max worldmap overview tiles rebuilt per server tick when zooming out. These are cheap (no world access, just combining already-generated data) so a low number is rarely needed. Default: 8.")
        @Config.RangeInt(min = 1, max = 256)
        public int mapPyramidBudgetTilesPerTick = 8;

        @Config.Comment("Safety net: how many loaded chunks the worldmap re-checks per tick in the background, for the rare change that goes unnoticed. Changes are normally picked up right away without it. 0 turns it off.")
        @Config.RangeInt(min = 0, max = 64)
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
        public boolean showFullMap = false;

        @Config.Comment("Max worldmap tiles kept in memory per zoom level on the client. Higher = smoother panning/zooming with less re-fetching, more client RAM used. Default: 128.")
        @Config.RangeInt(min = 16, max = 2048)
        public int mapClientTileCacheSizePerLevel = 128;

        @Config.Comment("How far around you (in chunks, 16 blocks each) the map is revealed as you explore.")
        @Config.RangeInt(min = 1, max = 32)
        public int mapDiscoveryRadiusChunks = 10;

        @Config.Comment("How soft the edge of the unexplored fog is on the map. 0 = sharp edge.")
        @Config.RangeDouble(min = 0.0, max = 0.5)
        public float mapFogEdgeSoftness = 0.5f;

        @Config.Comment("How wavy and natural the edge of the unexplored fog looks. 0 = smooth, regular edge.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float mapFogEdgeNoise = 0.8f;

        @Config.Comment("How strongly the map colours each kind of land (forest, desert, snow...). 0 = plain sepia map.")
        @Config.RangeInt(min = 0, max = 100)
        public int mapBiomeTint = 50;

        @Config.Comment("How many map pieces your graphics card keeps ready to draw. Higher = fewer redraws when moving around a big map, more video memory used (about 0.25 MB each).")
        @Config.RangeInt(min = 32, max = 1024)
        public int mapClientTextureCacheSize = 160;

        @Config.Comment("How many map pieces are prepared per frame when opening or moving the map. Higher fills the map faster but can cause small stutters.")
        @Config.RangeInt(min = 1, max = 32)
        public int mapTextureBuildsPerFrame = 3;

        @Config.Comment("Show active virtual battles as markers on the worldmap.")
        public boolean battleMarkersOnMap = true;

        @Config.Comment("Animate fights on the maps: arrows fly from archers to their targets and melee fighters lunge at their opponent. Costs a little network traffic while a map is open.")
        public boolean animateFightsOnMap = true;

        @Config.Comment("Initial zoom for the worldmap in the formation hub (blocks per pixel). Smaller = more zoomed in. 0.5 means 1 block per 2 pixels.")
        @Config.RangeDouble(min = 0.0625, max = 8.0)
        public double formationHubMapInitialBpp = 0.5;

        @Config.Comment("Master switch: reveal foreign travellers/raiders/workers and battles on the player worldmap/formation hub, gated by hiredWorkerVisionRadius.")
        public boolean foreignUnitVisionEnabled = true;

        @Config.Comment("Radius (blocks) around each of a player's own alive hired units within which foreign travellers/raiders/workers and battles become visible on the map.")
        @Config.RangeInt(min = 8, max = 512)
        public int hiredWorkerVisionRadius = 148;

        @Config.Comment("Show HP of discovered foreign entities on the map (own workers always show HP regardless of this setting).")
        public boolean revealForeignEntityHealth = false;

        @Config.Comment("Also treat the player's own position as a vision source, in addition to their hired units - reveals foreign travellers/raiders/workers and battles within hiredWorkerVisionRadius of the player, even with no hired units nearby.")
        public boolean playerIsVisionSource = true;

        @Config.Comment("Show the player marker as a directional arrow pointing where the player was looking when the map was opened, instead of a plain dot.")
        public boolean playerMarkerDirectional = true;

        @Config.Comment("Global multiplier applied on top of every marker's size below. 1.0 = unchanged.")
        @Config.RangeDouble(min = 0.25, max = 4.0)
        public double markerScaleGlobal = 1.0;

        @Config.Comment("Size (pixels) of the player marker.")
        @Config.RangeInt(min = 1, max = 32)
        public int playerMarkerSize = 7;

        @Config.Comment("Also show lone buildings (isolated Millénaire buildings) on your maps, once you have explored where they stand.")
        public boolean showLoneBuildings = true;

        @Config.Comment("Size (pixels) of village and lone building icons.")
        @Config.RangeInt(min = 1, max = 32)
        public int villageMarkerSize = 10;

        @Config.Comment("Size (pixels) of home, group-leader and follower worker markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int workerMarkerSize = 6;

        @Config.Comment("Extra pixels added on top of workerMarkerSize for the currently selected follower.")
        @Config.RangeInt(min = 0, max = 32)
        public int followerHighlightedSizeBonus = 5;

        @Config.Comment("Size (pixels) of foreign traveller/raider/worker population markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int populationMarkerSize = 6;

        @Config.Comment("Size (pixels) of camp firepit and camp shelter-slot markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int campMarkerSize = 4;

        @Config.Comment("Size (pixels) of destination-cross and battle markers.")
        @Config.RangeInt(min = 1, max = 32)
        public int alertMarkerSize = 8;
    }

    public static class EntityRenderLod {
        @Config.Comment("How far away (in blocks, at normal render distance) travellers, raiders and workers stay visible. Grows with your render distance like other creatures. A horse stays visible around 94.")
        @Config.RangeInt(min = 32, max = 256)
        public int entityRenderDistance = 128;

        @Config.Comment("Master switch for distance-based LOD on traveller/raider/worker entity rendering (cosmetic overlay layers + held item). Reduces GPU/CPU cost when many are visible at once. Purely cosmetic beyond the configured distances.")
        public boolean enabled = true;

        @Config.Comment("Beyond this distance (blocks), travellers, raiders and workers are drawn in a single pass, with clothes, armour and owner mark painted onto their skin. Much faster for big groups; armour looks flatter and loses its enchantment shine.")
        @Config.RangeInt(min = 4, max = 128)
        public int bakedSkinMinDistance = 24;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the clothes overlay layer. This layer re-renders the full biped model a second time, so it's the most expensive of the three and invisible at range anyway.")
        @Config.RangeInt(min = 4, max = 128)
        public int clothesLayerMaxDistance = 128;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the owner-mark armband layer (workers only).")
        @Config.RangeInt(min = 4, max = 128)
        public int armbandLayerMaxDistance = 128;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip the backpack/bamboo/chuspas layer (Henchmen only).")
        @Config.RangeInt(min = 4, max = 128)
        public int backpackLayerMaxDistance = 128;

        @Config.Comment("Beyond this distance (blocks) from the camera, skip rendering the held item (weapon/tool in hand) for travellers/raiders/workers. Highest-impact option since vanilla's 3D item rendering is the single most expensive part of entity rendering.")
        @Config.RangeInt(min = 4, max = 128)
        public int heldItemMaxDistance = 128;

        @Config.Comment("Beyond this distance (blocks), weapons and tools held by travellers, raiders and workers are drawn as a flat picture instead of a full 3D item. Much faster for big groups; loses the enchantment shine. Shields and 3D items are unchanged.")
        @Config.RangeInt(min = 4, max = 128)
        public int heldItemIconMinDistance = 16;

        @Config.Comment("Beyond this distance (blocks), horses ridden by travellers, raiders and workers are drawn in one go and lose their tiny harness details (ropes, buckles, reins). Much faster for big cavalry groups.")
        @Config.RangeInt(min = 4, max = 128)
        public int mountLodMinDistance = 24;
    }

    public static class Spawn {
        @Config.Comment("Enable culture-weighted spawning (cultures near spawn get higher weight).")
        public boolean travellerCultureWeightedSpawn = true;

        @Config.Comment("Radius (blocks) to scan for village cultures when picking spawn culture.")
        @Config.RangeInt(min = 64, max = 10000)
        public int travellerCultureScanRadius = 1024;

        @Config.Comment("If true, cultures with larger populations get more spawn weight.")
        public boolean travellerCultureWeightUseVillagePopulation = true;

        @Config.Comment("Minimum distance (blocks) a fresh traveller/raider/worker spawn point must keep from ANY known settlement (village or lone building), not just the one it belongs to. Keeps population from ever appearing to spring up right next to a settlement the player just discovered.")
        @Config.RangeInt(min = 0, max = 2000)
        public int spawnSettlementAvoidanceRadius = 160;
    }

    public static class SpawnTravellers {
        @Config.Comment("How many travellers a village should have per villager it has. Example: 0.33 means 1 traveller for every 3 villagers.")
        @Config.RangeDouble(min = 0.01, max = 50.0)
        public float travellerCapPerVillager = 0.25f;

        @Config.Comment("Every village always gets at least this many travellers, even a brand new one with no population yet.")
        @Config.RangeInt(min = 0, max = 256)
        public int travellerCapMinPerVillage = 3;

        @Config.Comment("No single village will ever get more travellers than this, no matter how big it grows.")
        @Config.RangeInt(min = 0, max = 256)
        public int travellerCapMaxPerVillage = 32;

        @Config.Comment("How many travellers appear around a standalone building that isn't part of a full village.")
        @Config.RangeInt(min = 0, max = 256)
        public int travellerCapPerLoneBuilding = 2;

        @Config.Comment("Minimum distance (blocks) from its home village/lone building a freshly spawned traveller can appear.")
        @Config.RangeInt(min = 0, max = 2000)
        public int travellerSpawnMinDistanceFromHome = 150;

        @Config.Comment("Maximum distance (blocks) from its home village/lone building a freshly spawned traveller can appear. Should roughly match how far apart settlements are in the world, so population looks disseminated instead of clumped near home.")
        @Config.RangeInt(min = 0, max = 3000)
        public int travellerSpawnMaxDistanceFromHome = 600;

        @Config.Comment("Chance (0-100) that a newly spawned traveller brings a small travelling group with them instead of being alone.")
        @Config.RangeInt(min = 0, max = 100)
        public int groupSpawnChance = 60;

        @Config.Comment("Smallest possible size for a traveller group (including the leader).")
        @Config.RangeInt(min = 2, max = 64)
        public int groupMinSize = 2;

        @Config.Comment("Largest possible size for a traveller group (including the leader).")
        @Config.RangeInt(min = 2, max = 64)
        public int groupMaxSize = 6;
    }

    public static class SpawnRaiders {
        @Config.Comment("Minimum distance (blocks) from its home village/lone building a freshly spawned raider can appear.")
        @Config.RangeInt(min = 0, max = 2000)
        public int raiderSpawnMinDistanceFromHome = 150;

        @Config.Comment("Maximum distance (blocks) from its home village/lone building a freshly spawned raider can appear. Should roughly match how far apart settlements are in the world, so raiders look disseminated instead of camped right outside the settlement they harass.")
        @Config.RangeInt(min = 0, max = 3000)
        public int raiderSpawnMaxDistanceFromHome = 600;

        @Config.Comment("How many raiders a village should attract per villager it has. Example: 0.2 means 1 raider for every 5 villagers.")
        @Config.RangeDouble(min = 0.01, max = 64.0)
        public float raiderCapPerVillager = 0.12f;

        @Config.Comment("Every village always attracts at least this many raiders, even a brand new one with no population yet.")
        @Config.RangeInt(min = 0, max = 256)
        public int raiderCapMinPerVillage = 1;

        @Config.Comment("No single village will ever attract more raiders than this, no matter how big it grows.")
        @Config.RangeInt(min = 0, max = 256)
        public int raiderCapMaxPerVillage = 6;

        @Config.Comment("How many raiders appear around a standalone building that isn't part of a full village.")
        @Config.RangeInt(min = 0, max = 32)
        public int raiderCapPerLoneBuilding = 2;

        @Config.Comment("If true, raiders travel along the Ultreia/MPB road network like travellers do. If false, raiders roam freely off-road.")
        public boolean raidersUseUltreiaPaths = true;

        @Config.Comment("Chance (0-100) that a newly spawned raider brings a small warband with them instead of being alone.")
        @Config.RangeInt(min = 0, max = 100)
        public int groupSpawnChance = 60;

        @Config.Comment("Smallest possible size for a raider warband (including the leader).")
        @Config.RangeInt(min = 2, max = 64)
        public int groupMinSize = 2;

        @Config.Comment("Largest possible size for a raider warband (including the leader).")
        @Config.RangeInt(min = 2, max = 64)
        public int groupMaxSize = 6;
    }

    public static class SpawnWorkers {
        @Config.Comment("How many workers a village should have per villager it has. Same formula as travellers/raiders. Example: 0.1 means 1 worker for every 10 villagers.")
        @Config.RangeDouble(min = 0.01, max = 20.0)
        public float workerCapPerVillager = 0.17f;

        @Config.Comment("Every village always gets at least this many workers, even a brand new one with no population yet.")
        @Config.RangeInt(min = 0, max = 256)
        public int workerCapMinPerVillage = 1;

        @Config.Comment("No single village will ever get more workers than this, no matter how big it grows.")
        @Config.RangeInt(min = 0, max = 256)
        public int workerCapMaxPerVillage = 16;

        @Config.Comment("Workers the global population controller spawns per lone building.")
        @Config.RangeInt(min = 0, max = 4)
        public int workerCapPerLoneBuilding = 1;

        @Config.Comment("Most free workers the world keeps at once; workers you hire no longer count.")
        @Config.RangeInt(min = 0, max = 2048)
        public int workerLoadedCap = 1024;

        @Config.Comment("Minimum distance (blocks) from its home village/lone building a freshly spawned worker can appear.")
        @Config.RangeInt(min = 0, max = 2000)
        public int workerSpawnMinDistanceFromHome = 150;

        @Config.Comment("Maximum distance (blocks) from its home village/lone building a freshly spawned worker can appear. Same dissemination ring as travellers/raiders, so a discovered village doesn't show its whole workforce bunched together.")
        @Config.RangeInt(min = 0, max = 3000)
        public int workerSpawnMaxDistanceFromHome = 600;

        @Config.Comment("Chance (0-100) that a newly spawned merchant worker travels with an escort of other workers. The escort can only be hired together with the merchant.")
        @Config.RangeInt(min = 0, max = 100)
        public int groupSpawnChance = 75;

        @Config.Comment("Smallest possible size for an escorted merchant group (including the merchant).")
        @Config.RangeInt(min = 2, max = 16)
        public int groupMinSize = 2;

        @Config.Comment("Largest possible size for an escorted merchant group (including the merchant).")
        @Config.RangeInt(min = 2, max = 16)
        public int groupMaxSize = 6;
    }

    public static class GroupFollow {
        @Config.Comment("Distance (blocks) from the leader beyond which a follower starts speeding up to catch up.")
        @Config.RangeDouble(min = 2.0, max = 32.0)
        public double catchUpTriggerDistance = 8.0;

    }


    public static class Attributes {
        @Config.Comment("Height of a traveller's hitbox, for a normal-sized one. Short and tall travellers get a hitbox scaled from this. Raise it if hits seem to pass through their head.")
        @Config.RangeDouble(min = 0.5, max = 4.0)
        public float hitboxHeight = 2.0f;

        @Config.Comment("Width of a traveller's hitbox, for a normal-sized one. Keep it below 1 block, or travellers will refuse to walk through doors and narrow streets.")
        @Config.RangeDouble(min = 0.2, max = 0.95)
        public float hitboxWidth = 0.6f;

        @Config.Comment("Max health of generic travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public float travellerMaxHealth = 20.0f;

        @Config.Comment("FOLLOW_RANGE (detection radius) for generic travellers.")
        @Config.RangeDouble(min = 4.0, max = 256.0)
        public float travellerFollowRange = 96.0f;

        @Config.Comment("Base melee damage per hit.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public float attackDamage = 1.0f;

        @Config.Comment("How much damage travellers take from monsters and other creatures (1 = normal, 0 = immune). Falls, fire and drowning are not affected.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerMobDamageScale = 0.25f;

        @Config.Comment("How much damage raiders take from monsters and other creatures (1 = normal, 0 = immune). Falls, fire and drowning are not affected.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float raiderMobDamageScale = 0.25f;

        @Config.Comment("How much damage henchmen take from monsters and other creatures (1 = normal, 0 = immune). Falls, fire and drowning are not affected.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float workerMobDamageScale = 0.25f;

        @Config.Comment("How much damage travellers deal to monsters, animals and villagers (1 = normal). Raise it if creatures in your modpack are too tough.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerDamageToMobsScale = 1.0f;

        @Config.Comment("How much damage raiders deal to monsters, animals and villagers (1 = normal). Raise it if creatures in your modpack are too tough.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float raiderDamageToMobsScale = 1.0f;

        @Config.Comment("How much damage henchmen deal to monsters, animals and villagers (1 = normal). Raise it if creatures in your modpack are too tough.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float workerDamageToMobsScale = 1.25f;

        @Config.Comment("Multiplier on all damage travellers deal to players.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public float travellerPlayerDamageMultiplier = 1.0f;


        @Config.Comment("Max health of raider travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public float raiderMaxHealth = 20.0f;




        @Config.Comment("Max health of worker travellers.")
        @Config.RangeDouble(min = 1.0, max = 1000.0)
        public double workerMaxHealth = 25.0;

        @Config.Comment("FOLLOW_RANGE for workers.")
        @Config.RangeDouble(min = 4.0, max = 256.0)
        public double workerFollowRange = 96.0;


        @Config.Comment("Blocks per path step for worker travel.")
        @Config.RangeInt(min = 1, max = 64)
        public int workerStepBlocks = 24;

        @Config.Comment("Blocks per path step for normal traveller travel.")
        @Config.RangeInt(min = 1, max = 128)
        public int stepBlocks = 24;
    }

    public static class Speed {
        @Config.Comment("Walking pace of travellers, raiders and workers on the road, in % of a walking player (100 = same speed, a sprinting player is 130).")
        @Config.RangeDouble(min = 20, max = 200)
        public double travelPercent = 92;

        @Config.Comment("Running pace of units in a fight, in % of a walking player. Keep it under 130 so a sprinting player can always run away.")
        @Config.RangeDouble(min = 20, max = 300)
        public double attackPercent = 115;

        @Config.Comment("Pace of a rider travelling on a horse, in % of a walking player. Keep it under your own horse's speed so you can catch them (the slowest vanilla horse is about 113).")
        @Config.RangeDouble(min = 20, max = 400)
        public double mountedPercent = 110;

        @Config.Comment("Fastest pace a follower or a unit catching up uses to close a gap, in % of a walking player. Followers always keep up with a faster leader.")
        @Config.RangeDouble(min = 20, max = 300)
        public double followMaxPercent = 125;

        @Config.Comment("Fastest pace a rider following or catching up uses to close a gap, in % of a walking player. Riders always keep up with a faster leader; this is what makes them catch up faster than walkers.")
        @Config.RangeDouble(min = 20, max = 400)
        public double mountedFollowMaxPercent = 150;

        @Config.Comment("Boat speed multiplier for anyone convoying alongside someone else's boat (hired workers following their owner, and travellers/raiders following their group leader).")
        @Config.RangeDouble(min = 0.1, max = 10.0)
        public double followerBoatConvoySpeed = 1.0;

        @Config.Comment("Pace while a traveller walks up to a fishing spot, in % of a walking player.")
        @Config.RangeDouble(min = 10, max = 150)
        public double fishApproachPercent = 55;

    }

    public static class Combat {
        @Config.Comment("If true, travellers/raiders/workers fight normally even on Peaceful difficulty, instead of disengaging and refusing to attack.")
        public boolean ignorePeaceful = false;

        @Config.Comment("How long (in ticks, 20 = 1 second) a traveller, raider or worker ignores further hits after being hit. 0 = every arrow and blow lands in full, 10 = Minecraft's normal protection. Fire, lava and other environmental damage always keep the normal protection.")
        @Config.RangeInt(min = 0, max = 10)
        public int hitProtectionTicks = 5;

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

        @Config.Comment("How far away units notice and attack enemy units and players.")
        @Config.RangeInt(min = 1, max = 200)
        public int unitAttackTriggerRange = 128;

        @Config.Comment("How far away units attack monsters, villagers and other creatures; a monster is only attacked once it is close enough to notice them, unless it attacks first.")
        @Config.RangeInt(min = 1, max = 200)
        public int mobAttackTriggerRange = 12;

        @Config.Comment("Health fraction below which entities enter defensive mode.")
        @Config.RangeDouble(min = 0.05, max = 0.50)
        public float lowHealthCombatFrac = 0.20f;

        @Config.Comment("Max engagement radius for workers in anchor/stay mode.")
        @Config.RangeInt(min = 3, max = 64)
        public int anchorModeEngageRadius = 12;

        @Config.Comment("Radius (blocks) around the owner/leader that hired workers proactively scan for threats already attacking them (EntityAIDefendOwnerProactively) - needs to reach ranged attackers standing well back, not just melee range.")
        @Config.RangeInt(min = 4, max = 64)
        public int defendOwnerProactiveRadius = 48;

        @Config.Comment("Max engagement radius for entities in low-health defensive mode.")
        @Config.RangeInt(min = 2, max = 16)
        public int lowHealthEngageRadius = 5;

        @Config.Comment("Ticks a traveller keeps chasing an attack target after losing sight/proximity of it, before giving up (unless it dies or gets out of range first).")
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
        public double meleeReachBonus = 0.0;

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

        @Config.Comment("Minimum distance before travellers uses a bow.")
        @Config.RangeDouble(min = 1.0, max = 64.0)
        public float rangedMinDist = 6.0f;

        @Config.Comment("Maximum distance travellers will attempt to shoot.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public float rangedMaxDist = 96.0f;

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

        @Config.Comment("Arrows and blows between allies (you and your henchmen, henchmen of the same player, members of the same group, and their pets and horses) pass through them without effect. Your own hits on your pets and horses stay normal. Turn off to use the two settings below instead.")
        public boolean friendlyFirePassesThrough = true;

        @Config.Comment("When allied hits do not pass through: how much of its damage an allied arrow or blow deals, in percent.")
        @Config.RangeInt(min = 0, max = 100)
        public int friendlyFireDamagePercent = 25;

        @Config.Comment("When allied hits do not pass through: how hard an allied arrow or blow pushes back, in percent.")
        @Config.RangeInt(min = 0, max = 100)
        public int friendlyFireKnockbackPercent = 25;

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

        @Config.Comment("For hired worker groups set to the 'Defend' attitude: max distance (blocks) from the group's leader/owner a worker will chase an attack target. Beyond this it holds back and waits for the fight to come closer.")
        @Config.RangeDouble(min = 2.0, max = 64.0)
        public double defendTetherRadius = 24.0;

        @Config.Comment("For hired worker groups set to the 'Hold Position' attitude: max distance (blocks) from a worker's own spot in formation it will step out to strike a target that got too close, before returning to formation.")
        @Config.RangeDouble(min = 1.0, max = 16.0)
        public double holdBreakRadius = 4.0;

        @Config.Comment("Radius (blocks) around a hired worker's owner/leader that it checks for a closer, more urgent threat when picking a target to attack - so escorts guard the player instead of always chasing whatever is nearest to themselves.")
        @Config.RangeDouble(min = 0.0, max = 48.0)
        public double escortOwnerPriorityRadius = 12.0;

        @Config.Comment("Once a mounted melee fighter closes to striking distance of its target, it gets off and fights on foot instead of swinging from the saddle. Turn off to keep them mounted for the whole fight.")
        public boolean mountedMeleeDismountEnabled = false;

        @Config.Comment("While a mounted melee fighter is still closing the distance (not yet close enough to strike), it curves in off to one side instead of riding straight at the target like a pedestrian. Higher looks more like a real mounted skirmish; 0 disables it and rides straight in.")
        @Config.RangeDouble(min = 0.0, max = 6.0)
        public double mountedMeleeApproachCurve = 3.5;
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
        public int rareWeight = 2;

        @Config.Comment("Same as commonWeight/goodWeight/rareWeight above, but only for raiders. Lets raiders spawn better equipped on average.")
        @Config.RangeInt(min = 0, max = 100)
        public int raiderCommonWeight = 60;

        @Config.Comment("Raider equivalent of goodWeight.")
        @Config.RangeInt(min = 0, max = 100)
        public int raiderGoodWeight = 35;

        @Config.Comment("Raider equivalent of rareWeight.")
        @Config.RangeInt(min = 0, max = 100)
        public int raiderRareWeight = 6;

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
        public float raiderArmorChanceMultiplier = 0.67f;

        @Config.Comment("Multiplier applied to the 4 armor chances above for hired workers (1.0 = no change).")
        @Config.RangeDouble(min = 0.1, max = 5.0)
        public float workerArmorChanceMultiplier = 1.2f;

        @Config.Comment("Chance to equip a bow instead of a melee weapon at spawn, when no better data (defaultweapon/startingInv) says otherwise.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float bowChance = 0.35f;

        @Config.Comment("Chance to prefer a culture's own signature gear (e.g. Norman broadsword/plate for Normans) over generic items when both are available at spawn.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float cultureSignaturePreferenceChance = 0.70f;

        @Config.Comment("Chance for each equipped armor piece or held weapon to drop when a traveller/raider dies (also used by virtual battle loot). Hired workers always drop everything and ignore this.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float equipmentDropChance = 0.085f;

        @Config.Comment("Chance for each backpack item to drop when a traveller/raider dies (also used by virtual battle loot), up to backpackMaxDrops.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float backpackDropChance = 0.30f;

        @Config.Comment("Max number of backpack items dropped when a traveller/raider dies (also used by virtual battle loot).")
        @Config.RangeInt(min = 0, max = 27)
        public int backpackMaxDrops = 2;

        @Config.Comment("Chance that a raider spawns with a shield (never together with a bow).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float shieldChanceRaider = 0.25f;

        @Config.Comment("Chance that a worker spawns with a shield (never together with a bow).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float shieldChanceWorker = 0.35f;

        @Config.Comment("Chance that a traveller spawns with a shield (never together with a bow).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float shieldChanceTraveller = 0.25f;

        @Config.Comment("Units wearing an iron or better helmet/chestplate are this many times more likely to spawn with a shield.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public float shieldIronArmorMultiplier = 2.0f;

        @Config.Comment("How long (seconds) a unit keeps its shield raised before it has to lower it.")
        @Config.RangeDouble(min = 0.5, max = 10.0)
        public float shieldRaisedSeconds = 2.0f;

        @Config.Comment("How long (seconds) a unit must wait after lowering its shield before raising it again.")
        @Config.RangeDouble(min = 0.0, max = 20.0)
        public float shieldRestSeconds = 3.0f;

        @Config.Comment("Walking speed while the shield is raised, as a fraction of normal speed (1 = no slowdown).")
        @Config.RangeDouble(min = 0.1, max = 1.0)
        public float shieldRaisedSpeedFactor = 0.5f;

        @Config.Comment("A unit whose shield absorbs a hit makes no pain sound, only the shield's block sound.")
        public boolean shieldBlockMutesHurtSound = true;

        @Config.Comment("Most stacks of food a unit keeps in its backpack; further food on the ground is ignored.")
        @Config.RangeInt(min = 0, max = 18)
        public int maxFoodStacks = 4;

        @Config.Comment("Travellers and non-hired workers only wear their own culture's gear, plus plain vanilla and other-mod items. Turn off to let anyone wear anything. Raiders and hired workers are never filtered.")
        public boolean cultureEquipmentFilterEnabled = true;

        @Config.Comment("A used weapon or armor piece below this much remaining durability (%) is only picked up when that slot is completely empty.")
        @Config.RangeInt(min = 0, max = 100)
        public int minDurabilityPercentToEquip = 25;

        @Config.Comment("Raiders always spawn wearing at least a helmet and a chestplate, so they stand out from friendly travellers at a glance.")
        public boolean raiderGuaranteedArmor = true;

        @Config.Comment("The guaranteed raider helmet and chestplate are at least iron tier.")
        public boolean raiderMinIronArmor = true;

        @Config.Comment("Raiders wear and loot equipment from any culture, not only their own — a scavenged, mismatched look that reads as 'bandit'.")
        public boolean raiderAllowMixedCultureGear = true;
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

        @Config.Comment("Days offline after which a player's workers can no longer be attacked by a declared war. 0 = never protected.")
        @Config.RangeInt(min = 0, max = 365)
        public int offlineProtectionDays = 0;
    }

    public static class WorkerFormation {

        @Config.Comment("Distance at which follower switches back from return-to-leader to normal follow.")
        @Config.RangeDouble(min = 1.0, max = 128.0)
        public double returnFollowSwitchDist = 12.0;

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

        @Config.Comment("How long, in seconds, a follower can be stuck before it gets the same unstuck help as a traveller on the road.")
        @Config.RangeInt(min = 1, max = 30)
        public int followStuckCatchupSeconds = 4;




        @Config.Comment("How much faster workers move when they've fallen behind and need to catch up.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double catchUpSpeedBoost = 1.0;

        @Config.Comment("Widens the distance-to-slot and distance-to-leader thresholds that trigger catch-up speed, for a mounted follower. Horseback naturally runs farther behind before it matters, so the same thresholds tuned for walking kept a mounted follower boosted almost permanently. 1.0 = same thresholds as walking.")
        @Config.RangeDouble(min = 1.0, max = 5.0)
        public double mountedCatchUpDistanceScale = 2.5;

        @Config.Comment("How close (blocks) a mounted follower must be to its slot before it starts slowing down for arrival, instead of riding in at full speed and skidding to a stop on top of it.")
        @Config.RangeDouble(min = 1.0, max = 12.0)
        public double mountedArrivalDecelRadius = 4.0;

        @Config.Comment("Smallest arrival radius ever asked of a mounted follower, however tight its formation slot would otherwise be. A horse turns in a wide circle at speed (see the mount steering system) - asking it to settle inside a radius tighter than that circle makes it orbit the spot instead of stopping on it.")
        @Config.RangeDouble(min = 0.5, max = 6.0)
        public double mountedMinSettleRadius = 2.4;

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


        @Config.Comment("How long (in ticks, 20 = 1 second) a group leader must stand still before its offscreen followers stop shuffling into formation and just stay where you left them. This is what keeps a group parked inside a house from spreading out through the walls while you are away.")
        @Config.RangeInt(min = 0, max = 1200)
        public int anchoredHoldDelayTicks = 60;

        @Config.Comment("How far (blocks) an offscreen follower may be from a standing leader and still simply stay put. Beyond this it is considered lost (scattered by a fight, leader moved away) and walks back into formation.")
        @Config.RangeDouble(min = 4.0, max = 64.0)
        public double anchoredHoldRadius = 24.0;

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

        @Config.Comment("How long a follower keeps walking straight at an obstacle before searching for a way around it. Higher is lighter on the server but looks more hesitant.")
        @Config.RangeInt(min = 1, max = 40)
        public int steeringStuckTicksBeforePathfind = 20;

        @Config.Comment("Let a worker blocked by an obstacle follow a path a nearby groupmate just found, instead of finding its own.")
        public boolean sharedRouteCorridorEnabled = true;

        @Config.Comment("How long (in ticks, 20 = 1 second) a found path stays usable by other workers before it's considered stale.")
        @Config.RangeInt(min = 20, max = 600)
        public int sharedRouteCorridorTtlTicks = 160;

        @Config.Comment("How far (blocks) a worker can be from a groupmate's path and still jump onto it.")
        @Config.RangeDouble(min = 2.0, max = 16.0)
        public double sharedRouteCorridorMaxSnapDistance = 8.0;

        @Config.Comment("Let any nearby worker/traveller reuse a path someone else recently found, not just a member of the same group. Turn off to go back to only sharing within one leader's own group.")
        public boolean sharedRouteCorridorWorldWide = true;

        @Config.Comment("Size (blocks) of the grid cells used to look up nearby shared paths when sharing is world-wide.")
        @Config.RangeInt(min = 8, max = 64)
        public int sharedRouteCorridorCellSize = 24;

        @Config.Comment("How far off (in degrees) a shared path's overall direction can be from where a worker is actually headed and still be considered useful. Keeps workers from reusing a path that happens to pass nearby but goes the wrong way.")
        @Config.RangeDouble(min = 10.0, max = 90.0)
        public double sharedRouteCorridorDirectionToleranceDeg = 50.0;

        @Config.Comment("Most shared paths remembered at once, world-wide. Oldest ones are dropped first once this is exceeded.")
        @Config.RangeInt(min = 32, max = 2000)
        public int sharedRouteCorridorMaxCachedRoutes = 256;

        @Config.Comment("Let a worker that's fully blocked by an obstacle (no detour, no shared path) ask for a real route sooner instead of waiting.")
        public boolean steeringBlockedFastEscalationEnabled = true;

        @Config.Comment("How long (in ticks, 20 = 1 second) a worker can be fully blocked by an obstacle before it asks for a real route sooner.")
        @Config.RangeInt(min = 10, max = 200)
        public int steeringBlockedEscalationGraceTicks = 30;

        @Config.Comment("Max number of real pathfinding requests allowed across all owners in a single tick.")
        @Config.RangeInt(min = 10, max = 2000)
        public int pathSchedulerGlobalMaxPathsPerTick = 16;

        @Config.Comment("Extra, much smaller limit on top of the one above, just for horseback followers finding a real route. A mounted route search costs far more than a walking one, so a server with many riders needs a tighter ceiling on how many of those run in a single tick, even while plenty of budget remains for walking followers.")
        @Config.RangeInt(min = 5, max = 500)
        public int pathSchedulerMountedGlobalMaxPathsPerTick = 24;

        @Config.Comment("How much more of a rider's pathfinding budget a single mounted route search uses up, compared to a walking one. Higher means mounted followers get real routes less often relative to their own budget, leaving more of it for other requests.")
        @Config.RangeDouble(min = 1.0, max = 8.0)
        public double mountedPathCostMultiplier = 3.5;

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

    }

    public static class Gaze {
        @Config.Comment("How fast travellers turn their head. Lower is calmer.")
        @Config.RangeDouble(min = 2.0, max = 40.0)
        public double headTurnSpeed = 12.0;

        @Config.Comment("How far a standing traveller turns its head before its body turns to follow.")
        @Config.RangeDouble(min = 10.0, max = 75.0)
        public double bodyFollowAngle = 45.0;

        @Config.Comment("How fast a standing traveller's body turns to follow its head. Lower is calmer.")
        @Config.RangeDouble(min = 1.0, max = 30.0)
        public double bodyTurnSpeed = 6.0;

        @Config.Comment("How fast a halted rider turns its horse toward what it is looking at. Lower is calmer.")
        @Config.RangeDouble(min = 1.0, max = 20.0)
        public double mountedTurnSpeed = 2.0;

        @Config.Comment("How far to one side a halted rider keeps its horse from what it is looking at, so the horse's head doesn't block its view.")
        @Config.RangeDouble(min = 0.0, max = 60.0)
        public double mountedSideOffset = 35.0;

        @Config.Comment("Shortest time a traveller keeps looking at the same thing before looking elsewhere (seconds).")
        @Config.RangeDouble(min = 0.5, max = 30.0)
        public double holdMinSeconds = 1.0;

        @Config.Comment("Longest time a traveller keeps looking at the same thing before looking elsewhere (seconds).")
        @Config.RangeDouble(min = 0.5, max = 30.0)
        public double holdMaxSeconds = 5.0;

        @Config.Comment("Chance, each time a traveller looks around, that it glances at someone or something nearby (0 = never, 1 = always).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double glanceChance = 0.4;

        @Config.Comment("Shortest time a traveller looks at someone passing by (seconds).")
        @Config.RangeDouble(min = 0.5, max = 15.0)
        public double glanceMinSeconds = 1.25;

        @Config.Comment("Longest time a traveller looks at someone passing by (seconds).")
        @Config.RangeDouble(min = 0.5, max = 15.0)
        public double glanceMaxSeconds = 3.5;

        @Config.Comment("How far away travellers keep following someone with their eyes.")
        @Config.RangeDouble(min = 4.0, max = 48.0)
        public double glanceRange = 16.0;

        @Config.Comment("How far a walking or riding traveller can turn its head; past it, it stops looking.")
        @Config.RangeDouble(min = 30.0, max = 75.0)
        public double walkingNeckAngle = 70.0;

        @Config.Comment("Shortest time a traveller stopped in front of you keeps its eyes on you before briefly looking away (seconds).")
        @Config.RangeDouble(min = 1.0, max = 60.0)
        public double lookAwayMinSeconds = 4.0;

        @Config.Comment("Longest time a traveller stopped in front of you keeps its eyes on you before briefly looking away (seconds).")
        @Config.RangeDouble(min = 1.0, max = 60.0)
        public double lookAwayMaxSeconds = 10.0;

        @Config.Comment("How long a traveller's nod takes when it says hello (seconds).")
        @Config.RangeDouble(min = 0.1, max = 2.0)
        public double nodSeconds = 0.4;

        @Config.Comment("How deep a traveller nods when it says hello (degrees).")
        @Config.RangeDouble(min = 5.0, max = 60.0)
        public double nodDepth = 25.0;

        @Config.Comment("Travellers only look around when a player is at least this close; farther away nobody sees it, so they save the effort.")
        @Config.RangeDouble(min = 8.0, max = 128.0)
        public double watchRange = 32.0;

        @Config.Comment("How far around them travellers notice people and animals worth a look.")
        @Config.RangeDouble(min = 4.0, max = 24.0)
        public double awarenessRadius = 10.0;

        @Config.Comment("How close a player must stand for a traveller to stop and face them.")
        @Config.RangeDouble(min = 1.0, max = 8.0)
        public double stopForPlayerDistance = 3.2;

        @Config.Comment("How long a player must stay that close before the traveller stops for them (seconds).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double stopForPlayerDelaySeconds = 1.0;

        @Config.Comment("Travellers say hello to people and groups they meet on the road, and to you.")
        public boolean greetingsEnabled = true;

        @Config.Comment("How close travellers must get to each other, or to you, to say hello.")
        @Config.RangeDouble(min = 2.0, max = 16.0)
        public double greetRange = 6.0;

        @Config.Comment("How long before the same travellers say hello to each other, or to you, again (seconds).")
        @Config.RangeDouble(min = 10.0, max = 3600.0)
        public double greetCooldownSeconds = 300.0;
    }

    public static class VillageVisit {
        @Config.Comment("Travellers stop and visit each village on their way: they stroll its streets, visit some buildings, read the signs and look at the people around.")
        public boolean enabledTravellers = true;

        @Config.Comment("Raiders stop in each village on their way and prowl its streets (they still attack villagers as usual).")
        public boolean enabledRaiders = true;

        @Config.Comment("Wandering (not hired) workers visit villages like travellers, and leave their building materials to the village when they go.")
        public boolean enabledWorkers = true;

        @Config.Comment("Shortest stay in a village (seconds).")
        @Config.RangeInt(min = 0, max = 3600)
        public int stayMinSeconds = 10;

        @Config.Comment("Longest stay in a village (seconds).")
        @Config.RangeInt(min = 0, max = 3600)
        public int stayMaxSeconds = 45;

        @Config.Comment("Shortest halt at a lone building (seconds).")
        @Config.RangeInt(min = 0, max = 3600)
        public int loneBuildingHaltMinSeconds = 15;

        @Config.Comment("Longest halt at a lone building (seconds).")
        @Config.RangeInt(min = 0, max = 3600)
        public int loneBuildingHaltMaxSeconds = 40;

        @Config.Comment("How close to a village's centre a traveller must get to count as arrived and start its visit. Walking into the town hall itself also counts.")
        @Config.RangeInt(min = 4, max = 128)
        public int arrivalRadius = 36;

        @Config.Comment("Same, for a lone building.")
        @Config.RangeInt(min = 4, max = 128)
        public int loneBuildingArrivalRadius = 24;

        @Config.Comment("Buildings travellers visit: any building with one of these Millénaire tags.")
        public String[] buildingIncludeTags = new String[]{"leasure", "market", "inn", "religious", "seller"};

        @Config.Comment("Buildings travellers visit: any building whose Millénaire plan name contains one of these words.")
        public String[] buildingIncludePlans = new String[]{};

        @Config.Comment("Buildings travellers never visit, by Millénaire tag (wins over the lists above).")
        public String[] buildingExcludeTags = new String[]{};

        @Config.Comment("Buildings travellers never visit, by words in the Millénaire plan name (wins over the lists above).")
        public String[] buildingExcludePlans = new String[]{};

        @Config.Comment("Travellers also visit the town hall.")
        public boolean visitTownHall = true;

        @Config.Comment("Travellers go and read the Millénaire information panels.")
        public boolean visitPanels = true;

        @Config.Comment("Raiders also visit buildings and panels instead of only prowling the streets.")
        public boolean raidersVisitBuildings = false;

        @Config.Comment("How often travellers simply stroll the streets, compared to the other activities.")
        @Config.RangeInt(min = 0, max = 100)
        public int weightStroll = 4;

        @Config.Comment("How often travellers visit a building, compared to the other activities.")
        @Config.RangeInt(min = 0, max = 100)
        public int weightBuilding = 3;

        @Config.Comment("How often travellers go and read a panel, compared to the other activities.")
        @Config.RangeInt(min = 0, max = 100)
        public int weightPanel = 2;

        @Config.Comment("Shortest time a traveller stays at a spot it walked to (seconds).")
        @Config.RangeInt(min = 0, max = 600)
        public int lingerMinSeconds = 6;

        @Config.Comment("Longest time a traveller stays at a spot it walked to (seconds).")
        @Config.RangeInt(min = 0, max = 600)
        public int lingerMaxSeconds = 20;

        @Config.Comment("How far from a building's entrance a traveller strolls around it.")
        @Config.RangeInt(min = 1, max = 32)
        public int strollSpreadBlocks = 6;

        @Config.Comment("Walking pace while visiting a village, in % of a walking player.")
        @Config.RangeDouble(min = 10, max = 150)
        public double walkPercent = 40;

        @Config.Comment("How long a traveller keeps trying to reach a spot in a village without getting any closer before it gives up and does something else (seconds).")
        @Config.RangeInt(min = 2, max = 120)
        public int stuckGiveUpSeconds = 8;

        @Config.Comment("How often a visiting traveller looks around for someone to look at or talk to (seconds).")
        @Config.RangeInt(min = 1, max = 60)
        public int socialCheckSeconds = 2;

        @Config.Comment("How close someone must be for a visiting traveller to talk to them or glance at them.")
        @Config.RangeInt(min = 2, max = 24)
        public int socialRadius = 6;

        @Config.Comment("Chance, each time a visiting traveller looks around, that it stops to talk with someone nearby (0 = never, 1 = always).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double talkChance = 0.15;

        @Config.Comment("Shortest conversation (seconds).")
        @Config.RangeInt(min = 1, max = 120)
        public int talkMinSeconds = 4;

        @Config.Comment("Longest conversation (seconds).")
        @Config.RangeInt(min = 1, max = 120)
        public int talkMaxSeconds = 10;

        @Config.Comment("Members of a group each visit the village on their own instead of trailing their leader, and gather again when the leader leaves.")
        public boolean followersDisperse = true;

        @Config.Comment("How close a group's members must be to their leader before it sets off again after a village visit.")
        @Config.RangeInt(min = 2, max = 64)
        public int regroupRadius = 12;

        @Config.Comment("Longest time a group leader waits for its members before leaving a village anyway (seconds).")
        @Config.RangeInt(min = 0, max = 600)
        public int regroupTimeoutSeconds = 60;

        @Config.Comment("How strongly visitors avoid a building or spot other visitors are already at, so they spread over the whole village (0 = they don't care).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double crowdPenalty = 1.5;

        @Config.Comment("How fast a visiting traveller turns its body while walking. Lower gives rounder, calmer turns.")
        @Config.RangeDouble(min = 5.0, max = 90.0)
        public double visitBodyTurnSpeed = 20.0;

        @Config.Comment("Visitors step aside or wait a moment instead of walking straight into people.")
        public boolean avoidEnabled = true;

        @Config.Comment("How close someone in front must be before a visitor steps aside.")
        @Config.RangeDouble(min = 0.8, max = 4.0)
        public double avoidDistance = 1.8;

        @Config.Comment("Most travellers that can chat together in one circle.")
        @Config.RangeInt(min = 2, max = 8)
        public int talkMaxMembers = 4;

        @Config.Comment("How far apart two travellers stand when they talk.")
        @Config.RangeDouble(min = 1.0, max = 5.0)
        public double talkDistance = 2.0;

        @Config.Comment("Travellers nod a little while listening in a conversation.")
        public boolean talkNods = true;



        @Config.Comment("While nobody is around, how often a visiting traveller moves on to another spot of the village (seconds).")
        @Config.RangeInt(min = 5, max = 600)
        public int virtualStepSeconds = 30;

        @Config.Comment("While nobody is around, how long a traveller waits at its horse's post for the horse before leaving without it; it gets the horse back later (seconds).")
        @Config.RangeInt(min = 10, max = 3600)
        public int hitchReclaimTimeoutSeconds = 120;

        @Config.Comment("How many blocks of free ground travellers keep from a Millénaire building when they have to be moved somewhere (a rescue, or reappearing near a player). They are never placed inside a building or on its roof.")
        @Config.RangeInt(min = 0, max = 8)
        public int buildingClearance = 1;

        @Config.Comment("How far below and above a Millénaire building's own height a spot still counts as part of that building (roof decorations, spires, basements).")
        @Config.RangeInt(min = 0, max = 32)
        public int buildingHeightMargin = 8;

        @Config.Comment("How often (seconds) the mod re-reads Millénaire's building list, to notice new or upgraded buildings.")
        @Config.RangeInt(min = 5, max = 3600)
        public int buildingIndexRefreshSeconds = 60;

        @Config.Comment("Debug view (/travellersdebug village show): how far around you buildings are drawn.")
        @Config.RangeInt(min = 16, max = 512)
        public int debugViewRadius = 96;

        @Config.Comment("Debug view: how close you must be for a building's name and tags to be written above it.")
        @Config.RangeInt(min = 8, max = 256)
        public int debugLabelDistance = 48;
    }

    public static class Destinations {
        @Config.Comment("How close a traveller must get to a destination that isn't a village or lone building to count as arrived.")
        @Config.RangeInt(min = 1, max = 256)
        public int villageNear = 48;

        @Config.Comment("Max radius (blocks) within which travellers pick next village destination.")
        @Config.RangeInt(min = 100, max = 10000)
        public int villagePickRadius = 4000;

        // --- Regionalized destination choice (Plan B) ---

        @Config.Comment("How strongly travellers prefer their own culture's region. 0 = ignore culture, 1 = almost never leave it.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double regionalizationStrength = 0.6;

        @Config.Comment("How strongly travellers head toward larger villages. 0 = village size ignored.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double populationBias = 0.5;

        @Config.Comment("Same, for raiders - they favour big settlements a bit more (more to raid).")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double raiderPopulationBias = 0.7;

        @Config.Comment("Raiders head toward foreign and hostile regions instead of their own.")
        public boolean raiderRegionInversion = true;

        @Config.Comment("Minimum share of travellers that ignore regional preference and roam anywhere.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double explorationEpsilonFloor = 0.03;

        @Config.Comment("Extra pull toward villages on the road network, so traffic follows the roads.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double roadConnectivityBonus = 0.35;

        @Config.Comment("[Advanced] Score multiplier for a destination visited in the last few trips (anti ping-pong).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double recentDestPenaltyFactor = 0.35;

        @Config.Comment("[Advanced] How many recent destinations are remembered per traveller.")
        @Config.RangeInt(min = 0, max = 8)
        public int recentDestMemory = 3;

        @Config.Comment("How strongly travellers and workers who wandered far from home drift back toward it. 0 = they never feel homesick. Raiders ignore it.")
        @Config.RangeDouble(min = 0.0, max = 3.0)
        public double homePullWeight = 1.0;

        @Config.Comment("Distance from home (blocks) within which travellers roam freely with no pull back home.")
        @Config.RangeInt(min = 0, max = 20000)
        public int homePullFreeRadius = 1200;

        @Config.Comment("Distance from home (blocks) at which the pull back home is at its strongest.")
        @Config.RangeInt(min = 1, max = 40000)
        public int homePullFullRadius = 3000;

        @Config.Comment("[Advanced] How hard a foreign-culture destination is penalised (multiplies the culture bias).")
        @Config.RangeDouble(min = 0.0, max = 4.0)
        public double cultureMismatchPenalty = 1.0;

        @Config.Comment("[Advanced] Span of the relation multiplier below neutral.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double relationBiasLow = 0.5;

        @Config.Comment("[Advanced] Span of the relation multiplier above neutral.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double relationBiasHigh = 0.5;

        @Config.Comment("[Advanced] Weight of the biome/culture corridor friendliness term.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double corridorBiomeWeight = 0.4;

        @Config.Comment("[Advanced] Floor on the culture multiplier so an isolated culture still has reachable destinations.")
        @Config.RangeDouble(min = 0.01, max = 1.0)
        public double minCultureWeight = 0.05;

        @Config.Comment("[Advanced] Village size that scores 'neutral' (population multiplier around 1).")
        @Config.RangeInt(min = 1, max = 200)
        public int populationRef = 15;

        @Config.Comment("[Advanced] Cap on the population multiplier so a capital can't vacuum the map.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double maxPopMultiplier = 3.0;

        @Config.Comment("[Advanced] How many candidates the cheap prefilter keeps for the heavier scoring pass.")
        @Config.RangeInt(min = 2, max = 32)
        public int topNCandidates = 8;

        @Config.Comment("[Advanced] How long (ticks) a cached village-to-village relation value is trusted.")
        @Config.RangeInt(min = 200, max = 24000)
        public int relationCacheTtlTicks = 1200;

        @Config.Comment("[Advanced] How long (ticks) a cached corridor friendliness score is trusted.")
        @Config.RangeInt(min = 1200, max = 240000)
        public int corridorCacheTtlTicks = 24000;

        @Config.Comment("Print destination-choice debug logs to the server console.")
        public boolean regionDebug = false;

        @Config.Comment("Write every destination choice (shortlist + per-term values) to a CSV in logs/travellers/destination/ so the maths can be checked against real play.")
        public boolean logChoices = false;
    }

    public static class Offscreen {
        @Config.Comment("Put the whole off-screen traveller simulation on hold while nobody is connected. Travellers stop travelling and pick up exactly where they left off when someone logs in.")
        public boolean pauseWhenServerEmpty = false;

        @Config.Comment("Instead of putting it on hold, let the off-screen simulation keep running slowly while nobody is connected. Ignored when the option above is on.")
        public boolean slowWhenServerEmpty = false;

        @Config.Comment("How many off-screen deliveries, village gifts or equipment pickups may open village chests at the same moment. Lower is smoother.")
        @Config.RangeInt(min = 1, max = 20)
        public int chestJobsPerTick = 1;

        @Config.Comment("How fast the off-screen simulation runs while nobody is connected, when the slow-down option is on. 1.0 = normal speed, 0.25 = four times slower.")
        @Config.RangeDouble(min = 0.01, max = 1.0)
        public double emptyServerSpeedMultiplier = 0.25;

        @Config.Comment("When true, travellers ignore mobs and incoming attacks while off-screen.")
        public boolean travellerNoAggroOffscreen = false;

        @Config.Comment("Enable the world-wide global population controller that keeps travellers and raiders populated across all settlements, even when chunks are unloaded.")
        public boolean globalPopulationEnabled = true;

        @Config.Comment("How often (in ticks) the global population controller runs. Lower = faster repopulation after reset, higher CPU cost.")
        @Config.RangeInt(min = 40, max = 12000)
        public int globalPopulationRefreshTicks = 600;

        @Config.Comment("Hard cap on the total number of travellers + raiders in the world (loaded and offscreen combined). Henchmen have their own separate cap and are not counted here.")
        @Config.RangeInt(min = 0, max = 10000)
        public int globalPopulationCap = 2000;

        @Config.Comment("Max number of new travellers/raiders/workers created per refresh cycle. Higher = faster repopulation after /travellersdebug reset, but more spawns happening at once.")
        @Config.RangeInt(min = 1, max = 500)
        public int globalPopulationMaxSpawnsPerCycle = 128;

        @Config.Comment("Log detailed per-village spawn attempt results to the server console (why a traveller/raider/worker was or wasn't spawned this cycle). Very spammy, for diagnosing spawn issues only.")
        public boolean populationDebugLog = false;
    }

    public static class Materialization {
        @Config.Comment("Stop travellers/raiders/workers from appearing inside another group's campsite shelter when they reappear near a player.")
        public boolean avoidCampFootprints = true;

        @Config.Comment("Extra safety gap (in blocks) kept around a camp shelter when pushing an unrelated entity out of it.")
        @Config.RangeInt(min = 0, max = 5)
        public int campFootprintMargin = 1;

        @Config.Comment("When someone reappears standing on foliage (a leaf block, a modded tree branch), how far straight down (in blocks) to look for the real ground beneath before giving up and leaving them where they are. Needs to clear the tallest trees in the pack - big Dynamic Trees dark oak / jungle can be 25+ blocks. Keeps travellers off tree canopy without ever leaving them stuck offscreen.")
        @Config.RangeInt(min = 0, max = 48)
        public int canopySinkMaxDrop = 10;

        @Config.Comment("How far (in blocks) to search for a spot wide enough for a horse and its rider when a mounted traveller reappears near a player. Larger values find room on rougher ground but cost a little more to compute.")
        @Config.RangeInt(min = 2, max = 24)
        public int mountFootprintSearchRadius = 12;

        @Config.Comment("If no spot within the search radius fits a horse cleanly, place it on the least-bad nearby spot (lifted clear of any blocks it overlaps) instead of dropping it embedded at the rider's feet. Turn off only to force the old behaviour.")
        public boolean mountBestEffortLastResort = true;

        @Config.Comment("When a traveller/raider/worker would reappear on a roof, a man-made surface, or in a tree's own trunk column, how far around it (in blocks) to look for natural ground to put it on instead. 0 turns the search off.")
        @Config.RangeInt(min = 0, max = 24)
        public int naturalGroundSearchRadius = 8;

        @Config.Comment("When someone you left inside a building would reappear well above the floor it was standing on (typically pushed up onto the roof by a wall in the way), how far around it (in blocks) to look for free space back on its own floor. 0 turns the search off.")
        @Config.RangeInt(min = 0, max = 16)
        public int sameLevelSearchRadius = 6;

        @Config.Comment("When a traveller/raider/worker has wandered away from where it went out of view, make it reappear on the surface instead of dropping into a cave (sometimes next to lava). Anyone that did NOT move reappears exactly where it was, whether that was underground, in a building, or on the surface.")
        public boolean surfaceClampOnMaterialize = true;

        @Config.Comment("Travellers/raiders/workers appear and disappear based on whether the chunk they stand on is loaded, whatever the reason it is loaded (your view distance, a Millenaire village, another mod's chunk loader). This is how many ticks a chunk must stay loaded before an offscreen unit reappears there. Prevents a brief, incidental chunk load from popping a unit in and straight back out.")
        @Config.RangeInt(min = 0, max = 1200)
        public int materializeChunkStableTicks = 40;

        @Config.Comment("After a traveller/raider/worker turns into an offscreen unit, how long (ticks) before it is allowed to reappear. Stops the ones that go offscreen because the ground ahead of them is not loaded - while the ground they stand on still is - from popping straight back in and walking the same two steps over and over. Give it enough time to walk clear of the loaded area (a chunk is 16 blocks wide).")
        @Config.RangeInt(min = 0, max = 1200)
        public int rematerializeGraceTicks = 100;

        @Config.Comment("Safety net, rarely used: how many ticks a real traveller/raider/worker must stand outside loaded chunks before it turns back into an offscreen unit. Units normally turn offscreen the moment their chunk unloads, which is instant; this only catches one that walked past the edge under its own power.")
        @Config.RangeInt(min = 0, max = 1200)
        public int virtualizeChunkUnneededTicks = 40;

        @Config.Comment("Write a line to the server log every time a traveller/raider/worker goes offscreen or reappears, with the reason and how far the nearest player was. For diagnosing units that pop in or out in front of you.")
        public boolean logEvents = false;
    }

    public static class Travel {
        @Config.Comment("If true, travellers may fish while stopped near water.")
        public boolean fishWhileTravelEnabled = true;

        @Config.Comment("Width (blocks) of the corridor used to collect intermediate village stops along a travel route.")
        @Config.RangeDouble(min = 8.0, max = 256.0)
        public double corridorWidth = 128.0;

        @Config.Comment("Travellers leave and enter walled villages through a gate, and walk around other walled villages on their way. Turn off to ignore village walls when planning routes.")
        public boolean routeThroughVillageGates = true;

        @Config.Comment("If a traveller/raider/worker walking toward a destination makes no real progress for this many ticks while already far from every player, it stops trying to detour/teleport around the obstacle and turns virtual right away instead, continuing its journey offscreen. Only matters right at the edge of loaded chunks; harmless everywhere else. Lower = gives up trying to walk through sooner.")
        @Config.RangeInt(min = 20, max = 1200)
        public int edgeStuckVirtualizeGraceTicks = 200;

        @Config.Comment("Minimum distance (blocks) from every player required before a traveller/raider/worker that is genuinely blocked (nothing loaded in any direction it tried) is allowed to turn virtual. Kept comfortably above normal viewing distance so this can never fire while someone is watching. Set to 0 to remove the check entirely.")
        @Config.RangeInt(min = 0, max = 256)
        public int edgeStuckMinPlayerDistance = 64;

        @Config.Comment("How long (ticks) a worker following its group leader/escort keeps facing ground that is not loaded before it gives up walking and continues its journey offscreen instead. Only applies to that follow mode - ordinary travel/go-order/catchup movement instead waits for sustained lack of progress (see edgeStuckVirtualizeGraceTicks), since it already has a more reliable way to tell.")
        @Config.RangeInt(min = 1, max = 200)
        public int edgeAheadUnloadedGraceTicks = 20;

        @Config.Comment("How long a traveller makes no headway along its route before it looks for a fresh path, in seconds.")
        @Config.RangeInt(min = 1, max = 60)
        public int stuckRepathSeconds = 1;

        @Config.Comment("How long before it tries a short detour around what is blocking it, in seconds.")
        @Config.RangeInt(min = 1, max = 60)
        public int stuckDetourSeconds = 2;

        @Config.Comment("How long before it plans a whole new route from where it stands (another village gate, another way around), in seconds.")
        @Config.RangeInt(min = 1, max = 120)
        public int stuckReplanSeconds = 3;

        @Config.Comment("How long before it hops a few blocks to get unstuck, in seconds. Kept short and rare so nobody sees a traveller jump around.")
        @Config.RangeInt(min = 2, max = 300)
        public int stuckHopSeconds = 6;

        @Config.Comment("Same, but for a traveller pressed against something and not moving at all, in seconds.")
        @Config.RangeInt(min = 2, max = 300)
        public int stuckPinnedHopSeconds = 8;

        @Config.Comment("How long a traveller may wander underground before it is brought back to the surface, in seconds.")
        @Config.RangeInt(min = 2, max = 300)
        public int stuckUndergroundHopSeconds = 5;

        @Config.Comment("Minimum time between two unstuck hops of the same traveller, in seconds.")
        @Config.RangeInt(min = 1, max = 300)
        public int stuckHopCooldownSeconds = 5;

        @Config.Comment("How far an unstuck hop may move a traveller, in blocks. Small = discreet.")
        @Config.RangeInt(min = 2, max = 16)
        public int stuckHopBlocks = 5;

        @Config.Comment("How far an unstuck hop may reach after earlier hops found nowhere to land, in blocks.")
        @Config.RangeInt(min = 2, max = 32)
        public int stuckHopMaxBlocks = 16;

        @Config.Comment("Before an unstuck hop, how far beyond the landing spot a traveller checks it can keep walking, in blocks.")
        @Config.RangeInt(min = 4, max = 48)
        public int stuckHopOnwardBlocks = 12;

        @Config.Comment("How many unstuck-hop landing checks the whole world may run per tick. Lower = lighter on the server, slower rescues.")
        @Config.RangeInt(min = 1, max = 20)
        public int stuckHopChecksPerTick = 2;

        @Config.Comment("How far a traveller can be pushed away from its route (by a fight or a rescue) before it plans a new route from where it stands instead of walking back to the old one.")
        @Config.RangeInt(min = 16, max = 512)
        public int plannedRouteMaxOffRouteBlocks = 32;

        @Config.Comment("How far sideways a travel waypoint may shift to find real ground to stand on, instead of pointing at a tree trunk or the top of a canopy.")
        @Config.RangeInt(min = 0, max = 4)
        public int waypointGroundSearchRadius = 2;
    }

    public static class GapJump {
        @Config.Comment("Widest gap (in blocks) a traveller/raider/worker can jump across, roughly a player's sprint-jump.")
        @Config.RangeInt(min = 1, max = 6)
        public int maxGapWidth = 4;

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

    public static class Mounts {
        @Config.Comment("Travellers, raiders and workers can ride a horse for their travels. Master switch for the whole feature.")
        public boolean enabled = true;

        @Config.Comment("Chance (%) that a group of travellers rides horses. The whole group rides or the whole group walks.")
        @Config.RangeInt(min = 0, max = 100)
        public int travellerMountChancePercent = 12;

        @Config.Comment("Chance (%) that a group of raiders rides horses. The whole group rides or the whole group walks.")
        @Config.RangeInt(min = 0, max = 100)
        public int raiderMountChancePercent = 10;

        @Config.Comment("Chance (%) that a worker appears at its village with its own horse, before anyone hires it.")
        @Config.RangeInt(min = 0, max = 100)
        public int workerMountChancePercent = 8;

        @Config.Comment("A bonded mount belongs to its rider - nobody, not even the worker's own owner, can tame, feed, or ride it away while the rider is alive. Turn off to make them normal, stealable horses.")
        public boolean preventPlayerTheft = true;

        @Config.Comment("A traveller, raider or hired worker that dies leaves its horse behind as an ordinary loose horse, exactly where it died. Without any cleanup these pile up forever near well-travelled routes. How long (in ticks, 20 per second) it is left completely alone before it becomes eligible for cleanup - long enough for a nearby player (or another recruited worker) to notice and claim it for themselves.")
        @Config.RangeInt(min = 200, max = 72000)
        public int looseMountCleanupGraceTicks = 600;

        @Config.Comment("A loose ex-mount past its grace period is only ever removed while no player is within this many blocks of it - it never simply vanishes in front of anyone, even one who isn't looking straight at it. If a player stays nearby indefinitely it just waits.")
        @Config.RangeDouble(min = 8.0, max = 256.0)
        public double looseMountCleanupMinPlayerDistance = 48.0;

        @Config.Comment("An unmounted hired worker with nothing else to do can notice a nearby loose ex-mount horse (one abandoned by a dead rider, not yet claimed) and walk over to bond with it. How far away (in blocks) it will notice one.")
        @Config.RangeDouble(min = 4.0, max = 64.0)
        public double looseMountClaimRadius = 20.0;

        @Config.Comment("How often (in ticks, 20 per second) an unmounted hired worker checks for a nearby loose ex-mount horse to claim. Cheap but pointless to run every tick.")
        @Config.RangeInt(min = 20, max = 400)
        public int looseMountClaimScanIntervalTicks = 60;

        @Config.Comment("How far down (in blocks) a mounted rider sits into the saddle, scaled by their own height roll. Matches the value vanilla uses for a mounted player - raise it (toward 0) if they look sunk into the horse's back, lower it if they look like they're floating above it.")
        @Config.RangeDouble(min = -1.0, max = 0.0)
        public double mountedSeatYOffset = -0.37;

        @Config.Comment("Log every mounted jump decision to the server log. Very spammy - only for diagnosing mounted pathfinding problems.")
        public boolean debugMounts = false;




        @Config.Comment("Followers on horseback ride along continuously behind a moving leader, matching its pace, instead of stopping on their formation spot and catching up in bursts. Only affects mounted followers; turn off to go back to the stop-and-catch-up behavior.")
        public boolean mountedPaceFollowEnabled = true;

        @Config.Comment("Let a mounted traveller step down and lead its horse on foot through ground the horse cannot get through - a narrow doorway, a thin wall, a tight gully - then climb back on once past it. Only ever triggers when the way is genuinely walkable on foot but not on horseback.")
        public boolean terrainDismountEnabled = true;

        @Config.Comment("Shortest time, in ticks, a traveller stays on foot after stepping down for tight ground. Stops them hopping on and off repeatedly around an awkward spot.")
        @Config.RangeInt(min = 20, max = 600)
        public int terrainRemountAfterTicks = 100;

        @Config.Comment("How far a traveller must have walked from where it stepped down before it will climb back on. Together with the delay above, this is what makes sure it is actually past the obstacle and not still standing beside it.")
        @Config.RangeDouble(min = 2.0, max = 32.0)
        public double terrainRemountDistance = 4.0;

        @Config.Comment("How deep water can be, in blocks, for a mounted traveller to ride straight through it instead of getting off. At the default a horse fords ankle-deep streams with its rider staying in the saddle; anything deeper means swimming, so the rider dismounts and crosses on foot.")
        @Config.RangeInt(min = 0, max = 3)
        public int mountedMaxFordDepth = 1;

        @Config.Comment("When a rider dies, its horse dies with it instead of being left loose. A horse dying never kills its rider.")
        public boolean mountDiesWithRider = false;

        @Config.Comment("How far a rider will go to defend its horse when someone attacks it.")
        @Config.RangeInt(min = 0, max = 64)
        public int defendMountRange = 24;

        @Config.Comment("How badly hurt a mount has to be, as a percentage of its own max health, before its rider gets off and leads it on foot instead of continuing to ride it. 0 turns this off. The horse stays led until it heals back above this line, however that happens.")
        @Config.RangeInt(min = 0, max = 100)
        public int mountLowHealthLedThresholdPercent = 35;

        @Config.Comment("How far ahead, in blocks, a mounted traveller aims when simply riding straight at its destination like a player would - checking the ground for that stretch and committing to it, before checking the next stretch. Shorter follows terrain more cautiously; longer rides straighter but re-checks bigger stretches at once.")
        @Config.RangeInt(min = 4, max = 24)
        public int mountedDirectLegBlocks = 10;

        @Config.Comment("How close, in blocks, a mounted traveller must pass by a travel waypoint to count it as reached and aim for the next one. A rider rides through waypoints at speed rather than stopping exactly on them - too small and the horse circles trying to hit the exact block.")
        @Config.RangeDouble(min = 2.0, max = 12.0)
        public double mountedWaypointReachRadius = 5.0;

        @Config.Comment("How reluctant a mounted traveller is to jump an obstacle rather than walk around it. Higher = they only jump when going around would be a real detour; lower = they hop over things readily.")
        @Config.RangeDouble(min = 0.0, max = 20.0)
        public double mountedJumpCostMalus = 4.0;

        @Config.Comment("Widest hole or stream, in blocks, a mounted rider jumps across instead of going around or riding into the water. 0 = horses never jump across gaps.")
        @Config.RangeInt(min = 0, max = 6)
        public int mountedMaxGapWidth = 4;

        @Config.Comment("Let the route planner itself route a mounted traveller over an obstacle its horse could jump, instead of only ever walking around one. Turn off and the planner never plans a jump - it only ever routes around obstacles, and any jumping left is purely reactive (see mountedReactiveJumpEnabled).")
        public boolean mountedJumpEnabled = true;

        @Config.Comment("Let a mounted traveller hop an obstacle its horse cannot simply step over, once it is already pressed against it mid-ride (independent from the route planner's own jump routing above). How high depends on the individual horse's own jump strength, so a poor jumper never tries what a good one can clear.")
        public boolean mountedReactiveJumpEnabled = true;

        @Config.Comment("Let a mounted traveller nudge sideways when its horse is jammed against something a jump cannot help with (a tree trunk, low branches, anything not directly climbable) - without this the only reactive response to being blocked is the jump attempt, and a horse pinned by something that was never climbable in the first place just sits there rejecting jumps until the slow stuck-recovery system eventually steps in.")
        public boolean mountedSidestepEnabled = true;

        @Config.Comment("How many ticks a mounted horse must be genuinely jammed (not simply slowed) before it tries nudging sideways around the obstacle. Lower reacts faster but risks reacting to a one-tick brush against something it would have cleared on its own.")
        @Config.RangeInt(min = 1, max = 60)
        public int mountedSidestepTriggerTicks = 10;

        @Config.Comment("How hard a mounted horse nudges sideways when trying to sidestep an obstacle a jump cannot clear. Higher moves it clear of a tight spot faster but looks like a harder shove.")
        @Config.RangeDouble(min = 0.05, max = 1.0)
        public double mountedSidestepImpulse = 0.5;

        @Config.Comment("How many sidesteps in a row (close together in time, see mountedSidestepGiveUpWindowTicks) a mounted rider will try at the same spot before giving up on riding through it and leading the horse on foot instead. Some spots (dense canopy, a tight natural maze) genuinely have no side a shove can fix - without a limit the horse just repeats the same short loop forever.")
        @Config.RangeInt(min = 1, max = 10)
        public int mountedSidestepGiveUpAfter = 3;

        @Config.Comment("How many ticks between two sidesteps still counts as 'the same spot, still stuck' rather than a fresh, unrelated obstacle later on the route.")
        @Config.RangeInt(min = 20, max = 400)
        public int mountedSidestepGiveUpWindowTicks = 100;

        @Config.Comment("How strongly a mounted rider avoids routes where its own head would scrape a low ceiling or branch (the horse still fits). This is a preference, not a ban - they will duck through leaves and low branches rather than be stranded. Higher = they detour around low cover more readily.")
        @Config.RangeDouble(min = 0.0, max = 16.0)
        public double mountLowHeadroomMalus = 3.0;

        @Config.Comment("How fast a mounted rider may turn, in degrees per tick. A horse copies its rider's facing exactly, so this is what stops a long body from snapping round. Lower = smoother, wider turns; higher = sharper but twitchier.")
        @Config.RangeDouble(min = 1.0, max = 90.0)
        public double mountedMaxTurnPerTick = 15.0;

        @Config.Comment("Lets a mounted rider cut a straight diagonal shortcut across its route instead of following the route's own nodes corner by corner. Turn off to always walk the planned route node by node - slower and more staircase-like on slopes, but immune to the shortcut approving a straight line the horse cannot actually walk (a steep climb, a tree trunk between sample points). Useful for isolating whether a mounted stuck/wedge problem comes from this shortcut.")
        public boolean mountedCorridorShortcutEnabled = true;

        @Config.Comment("How far ahead a mounted rider looks along its route for a straight shortcut, in blocks. This is what lets a horse cut diagonally across a slope instead of following the route corner by corner like a staircase. Higher = straighter, more direct riding; lower = sticks closer to the planned route.")
        @Config.RangeInt(min = 2, max = 32)
        public int mountedLookaheadBlocks = 12;

        @Config.Comment("Refuses a straight shortcut when the horse would need to turn too sharply to actually reach it in a straight line - a horse turns gradually, not on the spot, so a sharp shortcut is really walked as a curve that can clip terrain the straight-line check never sampled. Turn off to restore the old straight-line-only check.")
        public boolean mountedCorridorTurnGateEnabled = true;

        @Config.Comment("How much the horse is allowed to bow off the sampled shortcut line while turning onto it, as a multiple of its own half-width. Lower = only near-straight shortcuts are taken (safer, more cautious); higher = sharper turns are allowed onto a shortcut (more direct riding, more risk of clipping something the straight-line check missed).")
        @Config.RangeDouble(min = 0.25, max = 4.0)
        public double mountedCorridorTurnGateMargin = 1.0;

        @Config.Comment("Biggest drop, in blocks, a mounted rider will take by riding straight off it rather than going around. Riders happily drop down small ledges; past this they look for a way down instead.")
        @Config.RangeInt(min = 0, max = 8)
        public int mountedMaxStraightDrop = 3;

        @Config.Comment("Biggest total height gain, in blocks, a mounted rider will cut straight across in one go rather than following the route's own steps. Each individual step of the climb is always checked against the horse's own step and jump ability, so this only limits how long a climb one straight line commits to - keep it high and riders charge straight up steep but climbable slopes like a player would, instead of zig-zagging up them in little planner steps.")
        @Config.RangeInt(min = 0, max = 16)
        public int mountedMaxStraightRise = 10;

        @Config.Comment("A bonded horse takes no damage from cactus, matching its rider's own immunity - without this a horse can die to a hazard its rider was never at risk from.")
        public boolean mountImmuneToCactus = true;

        @Config.Comment("A rider keeps its horse for life: if the horse ever goes missing without dying (removed by another mod, lost with a broken chunk), it comes back next to its rider instead of being gone for good.")
        public boolean restoreLostMounts = true;

        @Config.Comment("How far (in blocks) a horse being led on a leash can trail behind its rider before it is brought back to them. Keep it generous - the horse is pulled along by the leash well before this.")
        @Config.RangeInt(min = 8, max = 128)
        public int leadMaxDistance = 12;

        @Config.Comment("How close (in blocks) a rider must be to its horse to climb back into the saddle.")
        @Config.RangeDouble(min = 1.0, max = 16.0)
        public double remountDistance = 5.0;

        @Config.Comment("How close (in blocks) a led horse must be to its rider before it stops walking - close enough to be 'at heel'.")
        @Config.RangeDouble(min = 0.5, max = 8.0)
        public double ledFollowStopDistance = 3.0;

        @Config.Comment("How far behind its rider (in blocks) a led horse aims: it walks in the rider's own footsteps a short way back, rather than cutting straight at the rider and weaving. Larger = the horse hangs further back in single file.")
        @Config.RangeDouble(min = 1.5, max = 6.0)
        public double ledFollowLeadGapBlocks = 2.5;

        @Config.Comment("Past this distance (in blocks), if the led horse also has no route of its own to walk (deep water, a wall it cannot path around), the leash tows it in a straight line toward its rider - the boat-crossing / swimming case.")
        @Config.RangeDouble(min = 4.0, max = 32.0)
        public double ledFollowTowDistance = 8.0;

        @Config.Comment("The fastest a led horse is allowed to trot to close a gap to its rider, as a multiple of the rider's own current pace. It normally paces exactly with the rider; this only caps how hard it sprints after falling behind.")
        @Config.RangeDouble(min = 1.0, max = 4.0)
        public double ledFollowCatchupMaxSpeedFactor = 2.2;

        @Config.Comment("How many ticks a led horse must be jammed against an obstacle (going nowhere) before it stops trying to step straight after its rider and asks the pathfinder for a route around instead.")
        @Config.RangeInt(min = 4, max = 60)
        public int ledFollowObstacleTicks = 12;

        @Config.Comment("Once a led horse has fallen back on the pathfinder to get around an obstacle, how many ticks that lasts before it goes back to stepping straight after its rider.")
        @Config.RangeInt(min = 10, max = 200)
        public int ledFollowPathfindTicks = 40;

        @Config.Comment("How long a rider tries to walk back to its horse before the horse simply comes to them. Only a safety net for a horse stuck somewhere it can't walk out of.")
        @Config.RangeInt(min = 40, max = 2400)
        public int remountTimeoutTicks = 300;

        @Config.Comment("While a rider is leading its horse on foot: if the horse gets stuck this far (in blocks) or more behind and stops making progress, bring it straight to the rider rather than waiting for the full leash-recall distance. This is the quick reunite that stops a rider looking frozen while its horse is snagged on terrain.")
        @Config.RangeDouble(min = 4.0, max = 32.0)
        public double ledStuckRecallDistance = 12.0;

        @Config.Comment("How many ticks a led horse must make no headway toward its rider (while past ledStuckRecallDistance) before it is brought to them.")
        @Config.RangeInt(min = 20, max = 400)
        public int ledStuckRecallTicks = 60;

        @Config.Comment("Travellers tie their horse up at the edge of a village they are about to visit, walk in on foot, and fetch it again when they leave. Off: they lead their horse through the village.")
        public boolean hitchMountInVillages = true;

        @Config.Comment("Tell a hired worker's owner when its horse gives up trying to ride through somewhere and starts leading it on foot instead. Only for the cases where the horse genuinely could not make it - routine dismounts (villages, boats, camps) never notify.")
        public boolean notifyOnMountLedFallback = false;

        @Config.Comment("How many route options the pathfinder is allowed to consider before giving up on a leg of the journey. Raising this helps a mounted rider find a way around tricky terrain it currently gives up on too early, at the cost of more work for the game to do in those same tricky spots. Only affects Travellers/raiders/hired workers - other creatures are untouched. Leave at the default unless riders are visibly failing to route around obstacles a horse should manage.")
        @Config.RangeInt(min = 200, max = 1000)
        public int pathfinderNodeBudget = 200;

        @Config.Comment("How many blocks deep a mounted rider looks ahead when deciding whether a rough or stepped rock face can be jumped, instead of only ever checking the one column directly in front. Higher lets a horse clear a wider natural obstacle in one hop; it costs a few extra terrain checks only when the horse is already pressed against something.")
        @Config.RangeInt(min = 1, max = 5)
        public int mountedJumpLookaheadColumns = 3;

        @Config.Comment("When a mounted rider needs to turn by more than this many degrees (a U-turn to go around a hole, a sharp course change), it stops and pivots on the spot instead of curving round while still moving forward. Prevents a horse from drifting over the edge of a pit while turning away from it. Lower = stops for gentler turns too (very safe, a bit start-stop); higher = only stops for near-reversals.")
        @Config.RangeDouble(min = 20.0, max = 170.0)
        public double mountedPivotInPlaceAngle = 55.0;

        @Config.Comment("Once a mounted rider has started pivoting on the spot, it keeps pivoting until its heading is within this many degrees of where it wants to go, then rides on. Keeps the pivot from flickering on and off right at the trigger angle.")
        @Config.RangeDouble(min = 5.0, max = 60.0)
        public double mountedPivotReleaseAngle = 18.0;

        @Config.Comment("While leading its horse on foot over dry ground, a rider eases off to this fraction of its normal pace so the leash isn't dragging the horse. The horse paces itself right behind either way; 1.0 = no easing off.")
        @Config.RangeDouble(min = 0.2, max = 1.0)
        public double ledRiderPaceFactor = 0.9;


        @Config.Comment("A mounted traveller, raider or non-hired worker steps off and leads its horse as soon as it enters a village or lone building's grounds, walking through on foot and climbing back on once clear. Does not apply to a hired worker actively escorting its owner. Turn off to let horses ride straight through villages.")
        public boolean mountedDismountNearVillage = false;

        @Config.Comment("How many ticks in a row a dismounted rider and its horse must both be on dry, solid ground and close together before the rider climbs back on. Stops the on-and-off flicker at a shelving bank where the horse touches the bottom for a moment. 20 ticks = 1 second.")
        @Config.RangeInt(min = 1, max = 60)
        public int remountStableTicks = 10;

        @Config.Comment("How often (in ticks) the game re-tells nearby players who is sitting on which horse. Fixes riders that look frozen in place while their horse walks off without them. 0 turns the repair off.")
        @Config.RangeInt(min = 0, max = 600)
        public int riderSyncIntervalTicks = 60;
    }

    public static class Stables {
        @Config.Comment("Master switch for the Stable zone type and everything that runs inside it.")
        public boolean enabled = true;

        @Config.Comment("How often (in ticks) a Stable checks that every horse it tracks is still inside its bounds, and rescans for newly wandered-in wild horses.")
        @Config.RangeInt(min = 5, max = 200)
        public int containmentCheckIntervalTicks = 20;

        @Config.Comment("Extra margin (in blocks) beyond a Stable's own box before a tracked horse is nudged/teleported back in, or a wandering-in wild horse stops counting as claimed.")
        @Config.RangeInt(min = 0, max = 8)
        public int containmentMarginBlocks = 2;

        @Config.Comment("How often (in ticks) an on-duty palfrenier attempts a taming interaction on an untamed reserved horse.")
        @Config.RangeInt(min = 20, max = 400)
        public int tamingAttemptIntervalTicks = 60;

        @Config.Comment("How often (in ticks) a palfrenier checks whether it should trigger deliberate breeding (golden carrot/apple) on a pair of eligible horses.")
        @Config.RangeInt(min = 100, max = 6000)
        public int breedingCheckIntervalTicks = 600;

        @Config.Comment("Minimum time (in ticks) a bred/tamed pair must wait before either horse may be fed into love mode again.")
        @Config.RangeInt(min = 200, max = 24000)
        public int breedingCooldownTicks = 6000;

        @Config.Comment("Notify the owner when their Stable successfully tames a wild horse.")
        public boolean notifyOnTame = true;

        @Config.Comment("Notify the owner when their Stable saddles a horse.")
        public boolean notifyOnSaddle = true;

        @Config.Comment("Notify the owner when a foal is born at their Stable.")
        public boolean notifyOnBirth = true;

        @Config.Comment("Keep a running log file per Stable (logs/travellers/stable/) recording every taming, saddling, birth and a periodic head-count, each tagged loaded or offscreen - for checking the two stay in step over a long test. Also readable in-game with /travellersdebug stable log.")
        public boolean debugLog = false;

        @Config.Comment("How often (in ticks) the Stable debug log writes a head-count snapshot line.")
        @Config.RangeInt(min = 200, max = 72000)
        public int debugLogSnapshotIntervalTicks = 6000;

        @Config.Comment("Notify the owner when a worker sent on Stable duty surrenders its own horse into the pool it now tends.")
        public boolean notifyOnMountRecovered = true;

        @Config.Comment("How many tamed horses a Stable pool may hold per hired worker the player has.")
        @Config.RangeInt(min = 1, max = 10)
        public int horseCapPerWorker = 10;

        @Config.Comment("A Stable always keeps at least this many horses in its pool. Get Equipment won't hand out a horse if doing so would drop the pool below this number.")
        @Config.RangeInt(min = 0, max = 10)
        public int minPoolReserve = 2;


        @Config.Comment("How long (in ticks) a palfrenier stays mounted on a horse it's taming before dismounting, as part of the taming ritual.")
        @Config.RangeInt(min = 10, max = 200)
        public int tamingMountRideTicks = 40;

        @Config.Comment("How long (in ticks) a palfrenier keeps the chest open while reaching in for a saddle it actually needs.")
        @Config.RangeInt(min = 5, max = 100)
        public int chestOpenTicks = 15;

        @Config.Comment("When a palfrenier has nothing urgent to do (nothing to tame or saddle, breeding on cooldown), how often it picks a small ambient task - checking on a horse or looking around.")
        @Config.RangeInt(min = 60, max = 4000)
        public int ambientDutyIntervalTicks = 300;

        @Config.Comment("How long (in ticks) a palfrenier works before taking a break. Randomised a little per palfrenier so a whole stable doesn't rest at once.")
        @Config.RangeInt(min = 200, max = 24000)
        public int workSessionTicks = 1200;

        @Config.Comment("How long (in ticks) a palfrenier's break lasts. During a break it ambles around the pen and looks about naturally, and starts no chores.")
        @Config.RangeInt(min = 100, max = 6000)
        public int breakDurationTicks = 400;

        @Config.Comment("How often (in ticks), during a break, a palfrenier wanders to a new spot in the pen.")
        @Config.RangeInt(min = 20, max = 600)
        public int breakWanderIntervalTicks = 80;

        @Config.Comment("Extra taming progress a horse gains when it throws the palfrenier off during the mount ritual, on top of a normal feeding beat. Higher makes the mount-and-buck moment the visibly big step toward taming.")
        @Config.RangeInt(min = 0, max = 40)
        public int tamingBuckBonusTemper = 6;

        @Config.Comment("Guard-rail: a Stable's pool can also never exceed its own pen's footprint divided by this many square blocks per horse, whatever the player-wide worker cap would otherwise allow - stops a small pen from packing in dozens of horses. A larger pen naturally allows more.")
        @Config.RangeInt(min = 2, max = 64)
        public int horseCapAreaPerHorse = 8;

        @Config.Comment("A Stable only gives a horse to a worker who comes for one once the horse has at least this much of its health back, in percent.")
        @Config.RangeInt(min = 0, max = 100)
        public int minHealthPercentForEquipment = 75;
    }

    public static class Elytra {
        @Config.Comment("Travellers, raiders and workers wearing an Elytra can glide with it - off a cliff, across a canyon or a river. They can only glide downwards, exactly like a player without fireworks, so they still walk most of the time.")
        public boolean enabled = true;

        @Config.Comment("Hired workers always have a firework to use when they need one to catch up, without spending anything from their own inventory. Turn on if you don't want to keep them stocked with rockets.")
        public boolean infiniteRockets = false;

        @Config.Comment("Elytra worn by travellers, raiders and workers never wears out or breaks from flying.")
        public boolean ignoreElytraDurability = false;

        @Config.Comment("Hired workers can fly alongside you without actually wearing an Elytra, as long as whoever they're escorting is currently flying. They still need a real one to take off on their own while travelling solo.")
        public boolean noElytraNeededForHenchmen = false;

        @Config.Comment("Every traveller, raider and worker is treated as if it always has a usable Elytra equipped, whether it's wearing one or not - overrides the setting above too.")
        public boolean noElytraNeededAtAll = false;

        @Config.Comment("Let them give themselves a running jump to get airborne. Without this they can only start gliding by stepping off a real drop, which almost never lines up on its own.")
        public boolean assistedLaunch = true;

        @Config.Comment("How fast they turn on the spot to face their destination before jumping, in degrees per tick. Lower looks more deliberate, higher gets them into the air sooner.")
        @Config.RangeDouble(min = 2.0, max = 45.0)
        public double alignTurnPerTick = 12.0;

        @Config.Comment("How hard the running jump throws them upwards. Higher means an easier takeoff from flat ground, but it looks less natural.")
        @Config.RangeDouble(min = 0.3, max = 1.2)
        public double launchVerticalBoost = 0.37;

        @Config.Comment("How hard the running jump throws them forwards, so they get moving fast enough for the wings to bite.")
        @Config.RangeDouble(min = 0.0, max = 1.5)
        public double launchForwardBoost = 0.55;

        @Config.Comment("How many blocks forward they travel per block of height lost while gliding. Higher means they stay in the air longer and can cross wider gaps.")
        @Config.RangeDouble(min = 2.0, max = 10.0)
        public double glideRatio = 5.0;

        @Config.Comment("Steepest nose-down angle used while gliding, in degrees. Higher means faster, steeper dives when they have height to spare.")
        @Config.RangeDouble(min = 5.0, max = 80.0)
        public double maxDivePitch = 40.0;

        @Config.Comment("Steepest nose-up angle used to stretch a glide when they are running out of height, in degrees. Too much and they stall and drop.")
        @Config.RangeDouble(min = 0.0, max = 25.0)
        public double maxClimbPitch = 25.0;

        @Config.Comment("How fast they can change their nose angle while flying, in degrees per tick. Low values give smooth, gradual dives and pull-ups; high values let them snap between angles, which looks twitchy.")
        @Config.RangeDouble(min = 1.0, max = 30.0)
        public double maxPitchChangePerTick = 8.0;

        @Config.Comment("How fast they can turn while flying, in degrees per tick. Low values give wide, natural banking turns; high values let them turn on a coin.")
        @Config.RangeDouble(min = 1.0, max = 20.0)
        public double maxTurnPerTick = 15.0;

        @Config.Comment("How close to their destination (blocks) they start pulling up to bleed off speed before landing.")
        @Config.RangeDouble(min = 2.0, max = 40.0)
        public double flareDistance = 8.0;

        @Config.Comment("How close to the ground (blocks) they start pulling up to land, wherever they are.")
        @Config.RangeDouble(min = 1.0, max = 20.0)
        public double flareHeight = 5.0;

        @Config.Comment("How far ahead (blocks) they look for terrain in the way while flying, so they climb over or steer around it instead of smacking into it.")
        @Config.RangeDouble(min = 2.0, max = 32.0)
        public double lookAheadDistance = 12.0;

        @Config.Comment("How often (in ticks) that look-ahead check runs. Higher is cheaper but they react more slowly.")
        @Config.RangeInt(min = 1, max = 20)
        public int lookAheadIntervalTicks = 4;

        @Config.Comment("Longest a single flight can last, in ticks. A safety net so a flight that goes wrong always ends in a landing instead of carrying on forever.")
        @Config.RangeInt(min = 100, max = 6000)
        public int maxFlightTicks = 6000;

        @Config.Comment("How long (in ticks) after landing before they may take off again.")
        @Config.RangeInt(min = 0, max = 2000)
        public int landingCooldownTicks = 100;

        @Config.Comment("Cancel fall damage when a glide ends in a proper landing. Turn off if you want a botched landing to hurt.")
        public boolean preventLandingFallDamage = true;

        @Config.Comment("Followers wearing an Elytra take off with whoever they are following when that person starts gliding, and do their best to keep up. Without fireworks they cannot climb, so an escort that gets left behind lands and runs after you as usual.")
        public boolean followTargetFlight = true;

        @Config.Comment("Hired workers escorting you may light a firework rocket to keep up when you climb away from them, taken from their own inventory. Give them rockets and they will use them; give them none and they simply glide.")
        public boolean workerFireworkBoost = true;

        @Config.Comment("Shortest gap (in ticks) between two rockets from the same worker.")
        @Config.RangeInt(min = 10, max = 200)
        public int fireworkBoostIntervalTicks = 30;

        @Config.Comment("Most rockets a worker will spend on a single flight, however far behind they fall.")
        @Config.RangeInt(min = 1, max = 32)
        public int maxFireworksPerFlight = 32;

        @Config.Comment("How steeply followers are allowed to point while catching up to someone well above them, in degrees - far beyond the normal unpowered glide climb angle above. A rocket's push follows the direction they're looking, so this is what lets a boosted rocket pull them upward toward you instead of mostly sideways.")
        @Config.RangeDouble(min = 20.0, max = 89.0)
        public double steepClimbMaxPitch = 75.0;

        @Config.Comment("How fast (degrees per tick) they can snap to that steep climb angle - deliberately much faster than the ordinary pitch-change speed above. A firework's push locks onto whichever direction they're looking within its first couple of ticks, so a slow, graceful turn toward vertical wastes most of the boost on a shallow angle.")
        @Config.RangeDouble(min = 5.0, max = 90.0)
        public double steepClimbPitchChangePerTick = 45.0;

        @Config.Comment("How long (in ticks) a follower keeps pointing steeply at whoever it's chasing after the height gap that justified it, so the aim is already steep well before the next rocket ignites - not just for the boost's own duration afterwards.")
        @Config.RangeInt(min = 5, max = 60)
        public int followRocketClimbHoldTicks = 20;

        @Config.Comment("How far behind (in blocks, sideways) a follower has to fall before it also spends a rocket just to catch up horizontally - separate from the vertical catch-up above, for when you accelerate away in level flight instead of climbing.")
        @Config.RangeDouble(min = 4.0, max = 48.0)
        public double followRocketHorizontalDeficit = 16.0;

        @Config.Comment("If a follower ever falls this far behind (in blocks, sideways) while flying, it gives up on the flight and lands instead of continuing to trail behind in the air - keeps it from drifting out of loaded chunks mid-flight, where it would be yanked back to the ground anyway. Lower this if your server's view distance is short.")
        @Config.RangeDouble(min = 24.0, max = 128.0)
        public double followFlightAbortDistance = 96.0;

        @Config.Comment("How many ticks in a row a gap has to keep looking rocket-worthy before a follower actually spends one on it. Filters out a brief height/speed wobble from you so it doesn't fire a rocket on its own - only a gap that's still there a moment later is real.")
        @Config.RangeInt(min = 0, max = 40)
        public int followRocketConfirmTicks = 16;

        @Config.Comment("Skip lighting a rocket if the follower is already closing the gap on its own at least this fast (blocks per tick), climbing/diving/gliding under a previous boost or just good positioning. Firing on top of an already-effective approach is what sends them overshooting past you and needing a second rocket right back the other way.")
        @Config.RangeDouble(min = 0.05, max = 2.0)
        public double followRocketSkipClosingSpeed = 0.5;

        @Config.Comment("Once a follower is within this many blocks of its formation spot, it stops aiming straight at it and instead just flies with the same nose angle and heading as whoever it's following - like a real flock, not a swarm of missiles converging on one point. This is what stops them overshooting past you and having to turn back. Kept small on purpose, close to the formation spacing above - a real height gap that's worth climbing hard for (or spending a rocket on) needs to fall outside this radius, or the mirroring would smother the climb.")
        @Config.RangeDouble(min = 2.0, max = 12.0)
        public double chaseMirrorRadius = 4.0;

        @Config.Comment("How many extra blocks beyond the radius above it takes to fade all the way from mirroring whoever they're following to steering straight at the formation spot - a smooth transition instead of an abrupt switch between the two.")
        @Config.RangeDouble(min = 1.0, max = 32.0)
        public double chaseMirrorFadeDistance = 8.0;

        @Config.Comment("How many degrees a follower tilts up or down per block it's above or below its formation spot, while steering straight at it (beyond the mirroring radius above). Replaces aiming exactly at the spot's bearing, which for a formation slot this close produced wild swings from small, ordinary height differences.")
        @Config.RangeDouble(min = 0.5, max = 10.0)
        public double chasePitchGainDegPerBlock = 3.0;

        @Config.Comment("For this many ticks after whoever they follow takes off, followers launch right away instead of waiting until they've fallen far behind - keeps the whole group leaving the ground together instead of trickling into the air one by one.")
        @Config.RangeInt(min = 0, max = 200)
        public int followTakeoffJoinWindowTicks = 20;

        @Config.Comment("Longest random delay (in ticks) before a follower reacts to a takeoff, so a whole escort doesn't launch on the exact same tick. Lower means a tighter, quicker group launch.")
        @Config.RangeInt(min = 0, max = 60)
        public int followTakeoffStaggerTicks = 10;

        @Config.Comment("How far ahead (in ticks) followers aim sideways/forwards of who they're chasing. Higher tracks a fast-moving leader more eagerly but overshoots turns.")
        @Config.RangeDouble(min = 5.0, max = 40.0)
        public double followChaseLeadTicks = 20.0;

        @Config.Comment("How far ahead (in ticks) followers aim vertically of who they're chasing. Kept lower than the sideways lead on purpose: a player boosting straight up with fireworks briefly reaches a huge climb rate, and aiming that far ahead vertically would fling followers into a wild overclimb.")
        @Config.RangeDouble(min = 2.0, max = 20.0)
        public double followChaseVerticalLeadTicks = 8.0;

        @Config.Comment("Furthest (in blocks) that vertical aim-ahead is allowed to reach above or below the leader's own altitude, however fast they're currently climbing or falling. Caps the effect of a firework's brief burst of vertical speed.")
        @Config.RangeDouble(min = 2.0, max = 40.0)
        public double followChaseMaxAltitudeLead = 14.0;

        @Config.Comment("Sideways spacing (in blocks) between followers flying together, so they fan out around the leader instead of stacking on one point.")
        @Config.RangeDouble(min = 1.0, max = 8.0)
        public double followFormationLateralSpacing = 3.0;

        @Config.Comment("Vertical spacing (in blocks) between layers of followers flying together, so a big escort splits into a few altitude bands instead of all crowding the leader's exact height.")
        @Config.RangeDouble(min = 0.0, max = 6.0)
        public double followFormationVerticalSpacing = 1.5;

        @Config.Comment("Let them decide to fly on their own while travelling, whenever the ground ahead drops away enough for a glide to actually pay off. Turn this off to keep flight for followers and commands only.")
        public boolean autoTakeoff = true;

        @Config.Comment("They only consider flying when their destination is at least this many blocks away. Short hops are always walked.")
        @Config.RangeDouble(min = 16.0, max = 512.0)
        public double minTravelDistance = 80.0;

        @Config.Comment("A glide has to cover at least this many blocks to be worth taking off for. Higher means they only fly when it really saves them a walk.")
        @Config.RangeDouble(min = 8.0, max = 256.0)
        public double minGlideDistance = 35.0;

        @Config.Comment("How far ahead (blocks) they look when judging whether a glide is worth it.")
        @Config.RangeDouble(min = 32.0, max = 512.0)
        public double maxScanDistance = 176.0;

        @Config.Comment("How much clear air (blocks) they want between their glide path and the ground below it. Higher makes them more cautious about skimming terrain.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public double corridorClearance = 2.5;

        @Config.Comment("How often (in ticks) a traveller checks whether a glide is available. Higher is cheaper but they miss more opportunities.")
        @Config.RangeInt(min = 20, max = 400)
        public int scanIntervalTicks = 60;

        @Config.Comment("Highest number of these terrain checks allowed across the whole world each tick, so a crowd of elytra wearers can never spike the server.")
        @Config.RangeInt(min = 1, max = 64)
        public int maxScansPerTick = 4;

        @Config.Comment("Log every takeoff, landing and flight decision to the server log. Very spammy - only for diagnosing flight problems.")
        public boolean debugElytra = false;
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

        @Config.Comment("Blocks (registry names, e.g. 'somemod:quicksand') treated as dangerous everywhere: travellers avoid walking and reappearing on them, horses refuse them and camps are never built on them. Lava and modded liquids are always dangerous and need no entry here.")
        public String[] hazardBlocks = new String[]{"biomesoplenty:sand"};
    }

    public static class Sound {
        @Config.Comment("If true, travellers occasionally mutter while travelling.")
        public boolean travellerAmbient = true;

        @Config.Comment("Minimum ticks between solo ambient voice lines.")
        @Config.RangeInt(min = 40, max = 20000)
        public int travellerAmbientMinDelay = 1200;

        @Config.Comment("Maximum ticks between solo ambient voice lines.")
        @Config.RangeInt(min = 80, max = 60000)
        public int travellerAmbientMaxDelay = 4200;

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

        @Config.Comment("How far away (in blocks) songs and choirs can be heard. Other voices use the distance above.")
        @Config.RangeDouble(min = 8.0, max = 128.0)
        public double songMaxDistance = 72.0;

        @Config.Comment("With Sound Physics, how much walls muffle songs and choirs compared to other sounds. 1 is the same, lower lets them carry through walls better.")
        @Config.RangeDouble(min = 0.1, max = 1.0)
        public float songWallAbsorption = 0.5f;

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
        public int spawnAmbientDelayTicks = 300;
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
        public int choirAutoCooldownTicks = 1200;

        @Config.Comment("How long a choir session lasts in ticks.")
        @Config.RangeInt(min = 100, max = 36000)
        public int choirSessionDurationTicks = 4200;

        @Config.Comment("Consecutive spread-check failures before stopping the choir.")
        @Config.RangeInt(min = 1, max = 20)
        public int choirSpreadGraceChecks = 3;
    }


    // Offscreen-capable camp event system (travellers + raiders).
    public static class CampEvents {
        @Config.Comment("Master switch for the new offscreen-capable camp event system. IT WON'T WORK, STRUCTURES ARE NOT INCLUDED FOR NOW")
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

        @Config.Comment("How many newly found campsites get their tent spots worked out per tick. Lower is lighter on the server; new camps just take a moment longer to become usable.")
        @Config.RangeInt(min = 1, max = 64)
        public int campLayoutsPerTick = 2;

        @Config.Comment("Chance (0-1) that a patch of land can hold a campsite. Each patch always gets the same answer, so exploring the same area again never adds more camps.")
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
    }

    public static class RestSpots {
        @Config.Comment("Travellers find pretty places in the wild where they stop for a break.")
        public boolean enabled = true;

        @Config.Comment("How often a patch of land is checked for a rest spot. Each patch always gets the same answer.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double discoveryChancePerChunk = 0.10;

        @Config.Comment("Minimum distance between two rest spots, in blocks.")
        @Config.RangeInt(min = 32, max = 2048)
        public int minSpacing = 160;

        @Config.Comment("No rest spot this close to a Millenaire village, in blocks.")
        @Config.RangeInt(min = 0, max = 2048)
        public int minVillageDistance = 150;

        @Config.Comment("How much walkable ground a rest spot needs, in blocks. Higher keeps only places roomy enough for big groups.")
        @Config.RangeInt(min = 9, max = 400)
        public int minWalkableCells = 40;

        @Config.Comment("How pretty a place must be to become a rest spot (the four weights below add up to the full score).")
        @Config.RangeInt(min = 0, max = 200)
        public int minScore = 40;

        @Config.Comment("How much a river or lake close by makes a place pretty.")
        @Config.RangeInt(min = 0, max = 100)
        public int weightWater = 30;

        @Config.Comment("How much standing above the land around (hilltop, ridge, cliff over a valley) makes a place pretty.")
        @Config.RangeInt(min = 0, max = 100)
        public int weightHeight = 30;

        @Config.Comment("How much open sky with a few trees around (a forest edge rather than deep woods) makes a place pretty.")
        @Config.RangeInt(min = 0, max = 100)
        public int weightOpenSky = 20;

        @Config.Comment("How much flowers on the ground make a place pretty.")
        @Config.RangeInt(min = 0, max = 100)
        public int weightFlowers = 20;

        @Config.Comment("Biomes that make a place prettier or uglier, as biome=points (negative for less pretty).")
        public String[] biomeScores = new String[]{
                "minecraft:swampland=-15", "minecraft:mutated_swampland=-15",
                "minecraft:desert=-10", "minecraft:mutated_desert=-10",
                "minecraft:mutated_forest=10"
        };

        @Config.Comment("How many possible rest spots are examined per tick. Lower is lighter on the server; spots just appear a little later.")
        @Config.RangeInt(min = 1, max = 32)
        public int scoringsPerTick = 2;

        @Config.Comment("Chance that a trip includes a break at a rest spot near the way.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double stopChance = 0.25;

        @Config.Comment("How far off their way travellers go for a break, in blocks.")
        @Config.RangeInt(min = 16, max = 512)
        public int maxDetour = 96;

        @Config.Comment("Shortest break at a rest spot, in minutes.")
        @Config.RangeInt(min = 1, max = 60)
        public int pauseMinMinutes = 2;

        @Config.Comment("Longest break at a rest spot, in minutes.")
        @Config.RangeInt(min = 1, max = 60)
        public int pauseMaxMinutes = 6;
    }

    public static class BattleEvents {
        @Config.Comment("Master switch for offscreen virtual battles between virtual travellers/raiders/workers.")
        public boolean enableVirtualBattles = true;

        @Config.Comment("Ticks between encounter-detection passes over virtual records.")
        @Config.RangeInt(min = 5, max = 200)
        public int detectionIntervalTicks = 10;

        @Config.Comment("Ticks between battle simulation steps (pawn movement + attacks).")
        @Config.RangeInt(min = 1, max = 100)
        public int battleSimIntervalTicks = 10;

        @Config.Comment("Distance (blocks) at which two hostile virtual parties engage each other.")
        @Config.RangeInt(min = 8, max = 128)
        public int engagementRadius = 64;

        @Config.Comment("Virtual entities within this distance of an ongoing battle join it (group pull-in and reinforcements).")
        @Config.RangeInt(min = 8, max = 256)
        public int battleJoinRadius = 32;

        @Config.Comment("Melee reach (blocks) for virtual pawns.")
        @Config.RangeDouble(min = 1.0, max = 5.0)
        public double meleeReach = 2.0;

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

        @Config.Comment("Also follow fights happening near players (not just offscreen ones), so they get a map marker and owners hear about them.")
        public boolean detectLoadedEncounters = true;

        @Config.Comment("Units out of sight slowly heal after a fight, like they do by eating when you can see them.")
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
        public float fishStartChancePerTick = 0.005f;

    }

    public static class ControlledVillage {
        @Config.Comment("Enable controlled village integration (barracks building, hire restriction).")
        public boolean controlledVillageFeatureEnabled = true;

        @Config.Comment("If true, player must be chief of at least one Millenaire village to hire workers.")
        public boolean requireControlledVillageToHire = false;

    }

    public static class Worker {
        @Config.Comment("Dead workers who had a bed (barrack or stable) come back beside it after a few days.")
        public boolean workerRespawnAtBed = true;

        @Config.Comment("How many mornings a dead worker waits before coming back beside its bed.")
        @Config.RangeInt(min = 1, max = 30)
        public int workerRespawnDays = 3;

        @Config.Comment("Time of day dead workers come back (0 = sunrise, 6000 = noon).")
        @Config.RangeInt(min = 0, max = 23999)
        public int workerRespawnMorningTime = 1000;

        @Config.Comment("Default half-width (X axis) for lone barracks detection.")
        @Config.RangeInt(min = 4, max = 32)
        public int workerLoneBarrackHalfWidth = 5;

        @Config.Comment("Default half-depth (Z axis) for lone barracks detection.")
        @Config.RangeInt(min = 4, max = 32)
        public int workerLoneBarrackHalfDepth = 5;

        @Config.Comment("Default half-height for lone barracks detection.")
        @Config.RangeInt(min = 2, max = 64)
        public int workerLoneBarrackHalfHeight = 4;

        @Config.Comment("Largest zone (barrack, stable) a player can create, measured from its centre to its side.")
        @Config.RangeInt(min = 4, max = 32)
        public int zoneMaxHalfWidth = 16;

        @Config.Comment("Tallest zone a player can create, measured from its centre up or down.")
        @Config.RangeInt(min = 2, max = 64)
        public int zoneMaxHalfHeight = 32;

        @Config.Comment("How often zones check their beds and chests for changes nobody reported (pistons, other mods...).")
        @Config.RangeInt(min = 20, max = 6000)
        public int zoneRescanIntervalTicks = 100;

        @Config.Comment("Writes each worker's cargo and gear to the game log at every mission step (for bug hunting).")
        public boolean debugMissionLog = false;

        @Config.Comment("If true, workers send a chat message when their trade affects reputation.")
        public boolean workerNotifyOnVillageTrade = true;

        @Config.Comment("If true, workers automatically loot mobs they kill.")
        public boolean workerAutoLootMobs = true;

        @Config.Comment("Max radius (blocks) around worker home to search for delivery villages.")
        @Config.RangeInt(min = 128, max = 100000)
        public int workerVillageDetectRadius = 8000;

        @Config.Comment("How close workers must get to the spot you send them to before they stop.")
        @Config.RangeInt(min = 1, max = 512)
        public int workerGoArrivalRadius = 4;

        @Config.Comment("How close workers must get to a barrack, stable or village to pick up equipment there.")
        @Config.RangeInt(min = 1, max = 512)
        public int workerEquipArrivalRadius = 32;

        @Config.Comment("Only tell you a Go order arrived if the destination was at least this far (blocks)...")
        @Config.RangeInt(min = 0, max = 10000)
        public int goNotifyMinDistance = 48;

        @Config.Comment("...or if the trip took at least this long (seconds).")
        @Config.RangeInt(min = 0, max = 36000)
        public int goNotifyMinSeconds = 60;

        @Config.Comment("How close (blocks) a worker coming back from a sale/donation stays to you while it waits for you to take the goods.")
        @Config.RangeDouble(min = 1.5, max = 10.0)
        public double pickupEscortDistance = 3.0;

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


        @Config.Comment("Minimum block distance between worker villages.")
        @Config.RangeInt(min = 100, max = 10000)
        public int workerVillageMinDistance = 250;
    }

    public static class Money {
        @Config.Comment("Bronze deniers every raider slain by a player drops, however poorly equipped (64 bronze = 1 silver, 64 silver = 1 gold).")
        @Config.RangeInt(min = 0, max = 100000)
        public int dropBaseDeniers = 4;

        @Config.Comment("Extra bronze deniers a slain raider drops for each point of armour and weapon damage it carried.")
        @Config.RangeInt(min = 0, max = 100000)
        public int dropDeniersPerGearPoint = 3;

        @Config.Comment("How much a drop can vary up or down from its usual amount, in percent.")
        @Config.RangeInt(min = 0, max = 100)
        public int dropRandomPercent = 30;

        @Config.Comment("Experience every slain raider gives, however poorly equipped.")
        @Config.RangeInt(min = 0, max = 10000)
        public int xpBase = 3;

        @Config.Comment("Extra experience a slain raider gives for each point of armour and weapon damage it carried.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double xpPerGearPoint = 0.5;

        @Config.Comment("How much the experience can vary up or down from its usual amount, in percent.")
        @Config.RangeInt(min = 0, max = 100)
        public int xpRandomPercent = 30;

        @Config.Comment("What peaceful travellers and unhired workers drop (deniers and experience), in percent of a raider with the same gear.")
        @Config.RangeInt(min = 0, max = 1000)
        public int peacefulDropPercent = 50;

        @Config.Comment("Bronze deniers every worker costs to hire, however poorly equipped.")
        @Config.RangeInt(min = 0, max = 10000000)
        public int hireBaseDeniers = 192;

        @Config.Comment("Extra bronze deniers a worker costs to hire for each point of armour and weapon damage it carries.")
        @Config.RangeInt(min = 0, max = 100000)
        public int hireDeniersPerGearPoint = 24;

        @Config.Comment("How much one worker's hiring price can differ from another's with the same gear, in percent.")
        @Config.RangeInt(min = 0, max = 100)
        public int hireRandomPercent = 15;
    }

    public static class Recruitment {
        @Config.Comment("Max 2D distance (blocks) from player when searching villages for recruitment.")
        @Config.RangeInt(min = 0, max = 30000)
        public int recruitMaxVillageRadius = 8000;

        @Config.Comment("Flat recruitment fee in deniers added once per mission.")
        @Config.RangeInt(min = 0, max = 1000000)
        public int recruitFeeFlat = 256;

        @Config.Comment("Additional fee per block of total 2D route distance.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public float recruitFeePerBlock = 0.07f;

        @Config.Comment("Additional fee per visited village (in deniers).")
        @Config.RangeInt(min = 0, max = 1000000)
        public int recruitFeePerVillage = 128;

        @Config.Comment("Minimum distance between recruitment target villages.")
        @Config.RangeInt(min = 0, max = 10000)
        public int recruitMinVillageDistance = 300;

        @Config.Comment("Lowest Millénaire reputation with a culture that still lets you recruit its people (0 is Neutral, 2048 is Stellar).")
        @Config.RangeInt(min = -640, max = 4096)
        public int recruitMinCultureReputation = 0;

        @Config.Comment("While any village of a culture is your enemy, none of its people will join you.")
        public boolean recruitEnemyVillageBlocksCulture = true;

        @Config.Comment("Chance in percent that a passer-by (or their whole group) agrees to join, at the lowest reputation that allows recruiting.")
        @Config.RangeInt(min = 0, max = 100)
        public int recruitAcceptChancePercent = 25;

        @Config.Comment("The same chance once your reputation with their culture is Stellar.")
        @Config.RangeInt(min = 0, max = 100)
        public int recruitAcceptChanceMaxPercent = 75;

        @Config.Comment("Chance that someone met on the road agrees, in percent of the chance in a village (where the recruiter takes the time to talk).")
        @Config.RangeInt(min = 0, max = 100)
        public int recruitRoadChancePercent = 50;

        @Config.Comment("How long a recruiter waits in each village for people to come by, in minutes.")
        @Config.RangeInt(min = 1, max = 60)
        public int recruitStayMinutes = 5;

        @Config.Comment("How many recruits you can expect per village visited: the tour plans one village of a culture for this many recruits wanted from it.")
        @Config.RangeInt(min = 1, max = 20)
        public int recruitsPerPlannedVillage = 3;

        @Config.Comment("Most recruits one recruiter can be sent to bring back.")
        @Config.RangeInt(min = 1, max = 100)
        public int recruitMaxPerMission = 20;

        @Config.Comment("Extra money in the recruiter's purse, in percent of the expected hiring prices, for recruits better equipped than average. What is left comes back to you.")
        @Config.RangeInt(min = 0, max = 200)
        public int recruitPurseMarginPercent = 25;
    }

    // Kept under its old name so saved settings still apply: these tune the wandering Henchmen's gift.
    public static class Merchant {
        @Config.Comment("Share of a village's missing construction materials a wandering Henchman gives when it leaves the village.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public float merchantGiveFraction = 0.01f;

        @Config.Comment("Minimum donation for bulk items (large stacks, e.g. planks).")
        @Config.RangeInt(min = 0, max = 64000)
        public int merchantMinBulkAmount = 8;

        @Config.Comment("Minimum donation for small-stack items (banners, tools, etc.).")
        @Config.RangeInt(min = 0, max = 64000)
        public int merchantMinSmallAmount = 1;

        @Config.Comment("How many different kinds of materials a wandering Henchman gives per village visit.")
        @Config.RangeInt(min = 0, max = 64)
        public int merchantMaxDistinctItems = 1;


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
        public String[] excludedRaiderVillagerTagsRaw = new String[]{"child", "chief"};

        @Config.Comment("Villager txt filenames (without .txt) excluded from raider pool.")
        public String[] excludedRaiderVillagerFilesRaw = new String[]{};

        @Config.Comment("Villager tags included in raider appearance pool (empty = no restriction).")
        public String[] includedRaiderVillagerTagsRaw = new String[]{"hostile", "raider", "archer"};

        @Config.Comment("Villager filenames included in raider appearance pool (empty = no restriction).")
        public String[] includedRaiderVillagerFilesRaw = new String[]{};

        @Config.Comment("If true, ALL includedRaiderVillagerTagsRaw must be present on the villager (AND). If false, ANY one tag matching is enough (OR).")
        public boolean includeRaiderTagMatchRequiresAll = false;

        // --- Worker ---
        @Config.Comment("Villager txt tags excluded from worker appearance pool.")
        public String[] excludedWorkerVillagerTagsRaw = new String[]{"child", "chief", "hostile"};

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
        public float fontScale = 1.0f;

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
        public int maxVisible = 4;

        @Config.Comment("Show red Danger notifications on screen (deaths, raids, battles, war). They are always kept in your history.")
        public boolean showDanger = true;

        @Config.Comment("Show orange Alert notifications on screen (something went wrong and may need you: a lost bed, a refused sale).")
        public boolean showAlert = true;

        @Config.Comment("Show green Success notifications on screen (recruits, sales, missions done, foals born).")
        public boolean showSuccess = true;

        @Config.Comment("Show white Information notifications on screen (orders sent, arrivals, group changes).")
        public boolean showInfo = true;

        @Config.Comment("Show the muted notifications that explain why something you clicked could not be done.")
        public boolean showRefusal = true;

        @Config.Comment("Maximum number of past events kept in your notification history (oldest are dropped first).")
        @Config.RangeInt(min = 10, max = 2000)
        public int journalRetentionPerPlayer = 200;

        @Config.Comment("Maximum number of notifications saved up for you while you are offline, before the oldest ones are dropped.")
        @Config.RangeInt(min = 5, max = 200)
        public int offlineQueueCapPerPlayer = 50;

        // --- History panel (event log banner in the Formation & Groups screen) ---
        @Config.Comment("Show the event history panel in the Formation & Groups screen.")
        public boolean historyPanelEnabled = true;

        @Config.Comment("Height (pixels) of the history panel.")
        @Config.RangeInt(min = 40, max = 220)
        public int historyPanelHeight = 52;

        @Config.Comment("Height (pixels) of each row in the history panel.")
        @Config.RangeInt(min = 8, max = 20)
        public int historyPanelRowHeight = 14;

        @Config.Comment("Text size in the history panel (1.0 = normal size).")
        @Config.RangeDouble(min = 0.5, max = 1.5)
        public float historyPanelFontScale = 1.0f;

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

    public static class WorkerHub {
        @Config.Comment("Keep several menu branches open at the same time. Every button then keeps a fixed spot, but the menu needs much more room and will fall back to opening one branch at a time when it does not fit your screen.")
        public boolean multiBranchExpand = false;

        @Config.Comment("Width of the menu buttons, in pixels.")
        @Config.RangeInt(min = 40, max = 160)
        public int buttonWidth = 76;

        @Config.Comment("Height of the menu buttons, in pixels.")
        @Config.RangeInt(min = 12, max = 40)
        public int buttonHeight = 18;

        @Config.Comment("Vertical gap between two buttons of the same column, in pixels.")
        @Config.RangeInt(min = 0, max = 30)
        public int rowGap = 6;

        @Config.Comment("Horizontal gap between two columns of the menu, in pixels.")
        @Config.RangeInt(min = 4, max = 60)
        public int columnGap = 22;

        @Config.Comment("Size of the portrait shown at the center of the menu, in pixels.")
        @Config.RangeInt(min = 24, max = 120)
        public int portraitSize = 54;

        @Config.Comment("Empty space kept between the portrait and the first column of buttons, in pixels.")
        @Config.RangeInt(min = 4, max = 60)
        public int portraitGap = 14;

        @Config.Comment("Draw the lines linking a menu button to the ones it opens.")
        public boolean showLinks = true;

        @Config.Comment("The portrait turns its head towards your mouse cursor.")
        public boolean headFollowsCursor = true;

        @Config.Comment("How far the portrait can turn its head left or right, in degrees.")
        @Config.RangeInt(min = 0, max = 90)
        public int headMaxYaw = 50;

        @Config.Comment("How far the portrait can tilt its head up or down, in degrees.")
        @Config.RangeInt(min = 0, max = 60)
        public int headMaxPitch = 25;

        @Config.Comment("How gradually the portrait's head catches up when turning to follow your cursor. Lower is smoother and slower, higher is snappier and more immediate.")
        @Config.RangeDouble(min = 0.05, max = 1.0)
        public double headSmoothing = 0.25;

        @Config.Comment("Show the culture, health and home village lines under the portrait.")
        public boolean showInfoLines = true;

        @Config.Comment("Let you right-click and drag a menu button out to place a shortcut copy of it, so you don't have to open the menu tree every time. Shared by every hired worker's menu.")
        public boolean enableShortcuts = true;
    }

    public static class Compat {
        @Config.Comment("If Ancient Warfare 2 is installed: travellers/hired workers (not raiders) will only fight AW2 faction NPCs (Empire, Bandits, etc.) that are actually hostile - a hired worker mirrors its owner's real standing with that faction (same as attacking one yourself would cause), and AW2 NPCs will likewise only attack travellers/workers under the same condition. Ownerless travellers use the faction's default disposition (some factions default hostile, most don't). Does nothing if AW2 is not installed.")
        public boolean ancientWarfare2ReciprocalHostility = true;

        @Config.Comment("Debug log (server console) for Ancient Warfare 2 hostility checks - prints every AncientWarfare2Compat.isHostile() call and result. Very spammy, only for diagnosing the compat feature.")
        public boolean ancientWarfare2DebugLog = false;
    }

    public static class Routing {
        @Config.Comment("Let travellers plan routes around mountains and rough ground instead of walking in a straight line. Turn this off to fall back to the old straight-line behaviour everywhere.")
        public boolean enablePlannedRoutes = true;

        @Config.Comment("How far ahead along its route a traveller aims while walking. Higher = smoother walking that cuts road bends more; lower = hugs winding roads more closely.")
        @Config.RangeInt(min = 8, max = 48)
        public int itineraryCarrotBlocks = 24;

        @Config.Comment("How closely a remembered road route keeps every little bend of the road, in blocks. Higher = lighter saves, looser road following.")
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public double itineraryRoadSimplifyBlocks = 2.0;

        @Config.Comment("How far away a traveller will look for a Millenaire road to join, in blocks. Also how far from a village's centre its roads may end.")
        @Config.RangeInt(min = 16, max = 512)
        public int roadJoinSearchRadius = 256;

        @Config.Comment("How much longer a road trip may be than walking straight there and still be taken. 1.5 = a road up to half again as long is still preferred.")
        @Config.RangeDouble(min = 1.0, max = 4.0)
        public double roadMaxDetourRatio = 1.45;

        @Config.Comment("Same as above, for travellers on horseback. Riders like roads more than walkers do.")
        @Config.RangeDouble(min = 1.0, max = 4.0)
        public double roadMaxDetourRatioMounted = 1.67;

        @Config.Comment("Let travellers plan boat crossings over lakes, bays and wide rivers when walking around would take much longer.")
        public boolean enableBoatRoutes = true;

        @Config.Comment("How much more tiring a block by boat counts compared to a block on foot, when a traveller chooses between rowing across and walking around. Higher = walks around more often.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double boatCostFactor = 3.0;

        @Config.Comment("The hassle of getting into a boat, counted as this many blocks of walking. Higher = short crossings are swum or walked around instead.")
        @Config.RangeDouble(min = 0.0, max = 1000.0)
        public double boatEmbarkCost = 32.0;

        @Config.Comment("Narrowest stretch of water a traveller will plan a boat for, in blocks. Narrower water is swum or walked around.")
        @Config.RangeInt(min = 16, max = 512)
        public int boatMinCrossingBlocks = 32;

        @Config.Comment("Advanced: how much of an area must be water (0..1) before a boat may cross it.")
        @Config.RangeDouble(min = 0.1, max = 1.0)
        public double boatWaterFrac = 0.86;

        @Config.Comment("How much faster than walking a traveller crosses water by boat while no player is near.")
        @Config.RangeDouble(min = 0.5, max = 5.0)
        public double boatVirtualSpeedMultiplier = 1.5;

        @Config.Comment("How far before and after a village wall gate travellers line up to walk straight through it, in blocks.")
        @Config.RangeInt(min = 3, max = 24)
        public int gateApproachBlocks = 8;

        @Config.Comment("How far ahead a traveller plans a route at once, in blocks.")
        @Config.RangeInt(min = 200, max = 20000)
        public int legCapBlocks = 4000;

        @Config.Comment("Advanced: spacing between search nodes for the coarse route search, in blocks. Smaller = detours around smaller features, but more work per route.")
        @Config.RangeInt(min = 16, max = 512)
        public int coarseNodeSpacing = 16;

        @Config.Comment("Advanced: hard cap on how many nodes the coarse route search may expand.")
        @Config.RangeInt(min = 64, max = 30000)
        public int coarseNodeBudget = 20000;

        @Config.Comment("Advanced: how far a planned route may cut corners when it is tidied up, in blocks. Lower = follows the terrain more closely.")
        @Config.RangeDouble(min = 0.0, max = 64.0)
        public double coarseSimplifyBlocks = 2.0;

        @Config.Comment("Advanced: how much the coarse search box is inflated past the straight line, as a fraction.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double coarseBoxInflation = 0.30;

        @Config.Comment("Background threads used for route planning.")
        @Config.RangeInt(min = 1, max = 8)
        public int routePlannerThreads = 2;

        @Config.Comment("How many new routes may be planned each tick, for the whole world.")
        @Config.RangeInt(min = 1, max = 64)
        public int routeJobsPerTick = 8;

        @Config.Comment("Advanced: how long a cached route stays valid, in ticks.")
        @Config.RangeInt(min = 200, max = 200000)
        public int routeCacheTtlTicks = 3000;

        @Config.Comment("Advanced: maximum number of cached routes kept in memory.")
        @Config.RangeInt(min = 64, max = 65536)
        public int routeCacheMaxEntries = 2048;

        // ----- cost model weights (advanced) -----
        @Config.Comment("Advanced weight: cost per block of route length. Should stay well above the terrain weights below.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double stepWeight = 1.0;

        @Config.Comment("Advanced weight: extra cost per block of height difference between two route points. Higher = routes weave around hills instead of climbing them.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double slopeWeight = 2.2;

        @Config.Comment("Advanced weight: extra cost for stepping down into a pit ringed by higher ground.")
        @Config.RangeDouble(min = 0.0, max = 10000.0)
        public double pitWeight = 40.0;

        @Config.Comment("Advanced weight: extra cost for rough, broken ground.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double roughnessWeight = 1.2;

        @Config.Comment("Advanced weight: extra cost for crossing water.")
        @Config.RangeDouble(min = 0.0, max = 1000.0)
        public double waterWeight = 16.0;

        @Config.Comment("Advanced weight: additional cost for deep ocean on top of the water cost.")
        @Config.RangeDouble(min = 0.0, max = 10000.0)
        public double deepOceanExtra = 60.0;

        @Config.Comment("Advanced: water fraction (0..1) at or above which a cell counts as deep water.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double deepWaterFrac = 0.25;

        @Config.Comment("Advanced weight: extra cost for mountain biomes.")
        @Config.RangeDouble(min = 0.0, max = 10000.0)
        public double mountainBiomeWeight = 20.0;

        @Config.Comment("Advanced weight: small extra cost for ground only estimated from the biome, so proven-real corridors are mildly preferred.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double estimateUncertainty = 2.0;

        @Config.Comment("Advanced weight: how strongly a culture's biome preferences pull its route. Reserved for a future update.")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double cultureBiomeWeight = 0.0;

        @Config.Comment("Advanced: cost assigned to an impassable cell. A large finite number, never infinite.")
        @Config.RangeDouble(min = 100.0, max = 1.0E9)
        public double blockedCost = 100000.0;

        // ----- terrain profile grid (advanced) -----
        @Config.Comment("Memory used for the terrain map, in regions (higher = smoother far routes). Each region is about 2 MB.")
        @Config.RangeInt(min = 4, max = 256)
        public int terrainMemoryRegionCap = 64;

        @Config.Comment("Advanced: how often the terrain map is written to disk, in ticks.")
        @Config.RangeInt(min = 200, max = 200000)
        public int terrainFlushIntervalTicks = 2400;

        @Config.Comment("Print route-planning and terrain-map debug logs to the server console.")
        public boolean debug = false;
    }

    public static class Raids {
        @Config.Comment("Lets raider hordes come for each player from time to time. Off by default.")
        public boolean enabled = true;

        @Config.Comment("Average number of in-game days between two raids on the same player. Each raid is a bit earlier or later than this.")
        @Config.RangeDouble(min = 0.5, max = 60.0)
        public float averageIntervalDays = 5.0f;

        @Config.Comment("How likely a raid is to come at night, compared with the day weight. Example: 80 and 20 means four raids out of five come at night.")
        @Config.RangeInt(min = 0, max = 100)
        public int nightWeight = 80;

        @Config.Comment("How likely a raid is to come during the day, compared with the night weight.")
        @Config.RangeInt(min = 0, max = 100)
        public int dayWeight = 20;

        @Config.Comment("No raid on a player during this many in-game days after they were first seen by the raid system.")
        @Config.RangeDouble(min = 0.0, max = 60.0)
        public float graceDays = 3.0f;

        @Config.Comment("How hard raids hit. 1.0 is the normal size for your workers; 2.0 sends twice as many raiders, 0.5 half as many.")
        @Config.RangeDouble(min = 0.1, max = 5.0)
        public float difficultyScale = 1.0f;

        @Config.Comment("The most raiders a single raid will ever bring, all waves together, however many workers you have.")
        @Config.RangeInt(min = 1, max = 4000)
        public int maxRaiders = 40;

        @Config.Comment("Only your henchmen within this many blocks of you count when a raid is sized, so a lone trip far from your army brings a small raid. 0 counts all of them, wherever they are.")
        @Config.RangeInt(min = 0, max = 2000)
        public int strengthRadius = 128;

        @Config.Comment("The most raid raiders alive at once on the whole server, all players together. A wave that would go over it comes smaller, or waits.")
        @Config.RangeInt(min = 10, max = 2400)
        public int maxActiveRaidRaiders = 160;

        @Config.Comment("How far from you (in blocks) the raiders appear. They walk the rest of the way, so this is also your warning time. Never closer than the server's view distance.")
        @Config.RangeInt(min = 80, max = 500)
        public int spawnDistance = 180;

        @Config.Comment("Fewest waves in a raid. Bigger raids get more waves, between this and the maximum.")
        @Config.RangeInt(min = 1, max = 10)
        public int minWaves = 1;

        @Config.Comment("Most waves in a raid. Set it equal to the minimum for a fixed number of waves.")
        @Config.RangeInt(min = 1, max = 50)
        public int maxWaves = 4;

        @Config.Comment("The next wave comes when the current one is nearly beaten, or after this many minutes at the latest.")
        @Config.RangeDouble(min = 0.5, max = 20.0)
        public float maxWaveDelayMinutes = 5.0f;

        @Config.Comment("How often a raid comes from a single direction, compared with the other approaches.")
        @Config.RangeInt(min = 0, max = 100)
        public int oneFrontWeight = 50;

        @Config.Comment("How often a raid's waves come from two different directions, compared with the other approaches.")
        @Config.RangeInt(min = 0, max = 100)
        public int twoFrontsWeight = 30;

        @Config.Comment("How often a raid surrounds you, each wave from another direction, compared with the other approaches.")
        @Config.RangeInt(min = 0, max = 100)
        public int encircleWeight = 20;

        @Config.Comment("How often a wave is a horde: more raiders, lightly armed.")
        @Config.RangeInt(min = 0, max = 100)
        public int hordeWeight = 40;

        @Config.Comment("How often a wave is elite: fewer raiders in heavy armour.")
        @Config.RangeInt(min = 0, max = 100)
        public int eliteWeight = 20;

        @Config.Comment("How often a wave is cavalry: every raider on horseback. Never happens when mounts are off.")
        @Config.RangeInt(min = 0, max = 100)
        public int cavalryWeight = 20;

        @Config.Comment("Armour of horde raiders: one tier (NONE, LEATHER, CHAIN, IRON, DIAMOND), a range like LEATHER-CHAIN drawn again for each raider, or NATURAL for the usual gear of their culture.")
        public String hordeArmor = "LEATHER-CHAIN";

        @Config.Comment("Melee weapon of horde raiders: one tier (STONE, IRON, DIAMOND), a range like STONE-IRON drawn again for each raider, or NATURAL for the usual weapons of their culture.")
        public String hordeWeapon = "STONE";

        @Config.Comment("Share (percent) of horde raiders who carry a bow instead of a melee weapon.")
        @Config.RangeInt(min = 0, max = 100)
        public int hordeArcherPercent = 25;

        @Config.Comment("Chance (percent) that a horde raider carries a shield. Archers never do.")
        @Config.RangeInt(min = 0, max = 100)
        public int hordeShieldPercent = 10;

        @Config.Comment("Armour of elite raiders, written like the horde armour.")
        public String eliteArmor = "IRON-DIAMOND";

        @Config.Comment("Melee weapon of elite raiders, written like the horde weapon.")
        public String eliteWeapon = "IRON-DIAMOND";

        @Config.Comment("Share (percent) of elite raiders who carry a bow instead of a melee weapon.")
        @Config.RangeInt(min = 0, max = 100)
        public int eliteArcherPercent = 15;

        @Config.Comment("Chance (percent) that an elite raider carries a shield. Archers never do.")
        @Config.RangeInt(min = 0, max = 100)
        public int eliteShieldPercent = 100;

        @Config.Comment("Armour of cavalry raiders, written like the horde armour.")
        public String cavalryArmor = "CHAIN-IRON";

        @Config.Comment("Melee weapon of cavalry raiders, written like the horde weapon.")
        public String cavalryWeapon = "STONE-IRON";

        @Config.Comment("Share (percent) of cavalry raiders who carry a bow instead of a melee weapon.")
        @Config.RangeInt(min = 0, max = 100)
        public int cavalryArcherPercent = 0;

        @Config.Comment("Chance (percent) that a cavalry raider carries a shield. Archers never do.")
        @Config.RangeInt(min = 0, max = 100)
        public int cavalryShieldPercent = 25;

        @Config.Comment("Chance (percent) that a wave after the first comes from another culture allowed to raid you.")
        @Config.RangeInt(min = 0, max = 100)
        public int mixedCultureChance = 25;

        @Config.Comment("Cultures allowed to raid you, by their Millenaire culture name (for example: norman, japanese, byzantines, hindi, inuits, mayan, seljuk). Leave empty to allow every culture present in the world.")
        public String[] allowedCultures = new String[0];
    }

    public static class Dialogue {
        @Config.Comment("Lets players right-click a traveller and have a real AI conversation instead of the canned lines. "
                + "Each player needs their own Ollama (a free, locally-installed AI program) running on their own computer - "
                + "the server never contacts any AI service itself. Off by default because it depends on that separate install.")
        public boolean enabled = false;

        @Config.Comment("How close (in blocks) counts as actually standing inside a village, in conversation.")
        @Config.RangeInt(min = 20, max = 300)
        public int villageInsideRadius = 120;

        @Config.Comment("How close (in blocks) counts as actually standing inside a lone building (much smaller than a full village), in conversation.")
        @Config.RangeInt(min = 10, max = 150)
        public int loneBuildingInsideRadius = 32;

        @Config.Comment("How close (in blocks) counts as right next to a village without being inside it, in conversation.")
        @Config.RangeInt(min = 50, max = 800)
        public int villageAdjacentRadius = 300;

        @Config.Comment("How far around a traveller (in blocks) it looks for other settlements worth mentioning in conversation.")
        @Config.RangeInt(min = 100, max = 8000)
        public int nearbySettlementRadius = 2400;

        @Config.Comment("Maximum number of nearby settlements mentioned in one conversation's context, so the AI isn't overloaded with facts.")
        @Config.RangeInt(min = 0, max = 32)
        public int nearbySettlementMaxCount = 8;

        @Config.Comment("How far around a traveller (in blocks) it looks at the ground to describe the terrain (flat, hilly, near water...) in conversation. Needs the planned-routes terrain scanner to be on.")
        @Config.RangeInt(min = 16, max = 256)
        public int terrainSummaryRadius = 64;

        @Config.Comment("How far away (in blocks) it checks for a different landscape right next door, so a traveller near the edge of two areas can mention it.")
        @Config.RangeInt(min = 8, max = 128)
        public int biomeBorderCheckRadius = 64;

        @Config.Comment("How far around a traveller (in blocks) it surveys the land for distant landmarks (mountains, forest, seas, rivers...) worth mentioning in conversation.")
        @Config.RangeInt(min = 200, max = 8000)
        public int distantLandmarkRadius = 2400;

        @Config.Comment("How finely it surveys that land (in blocks between each look). Smaller catches smaller/thinner features and points in a truer direction, but costs more each time a conversation starts.")
        @Config.RangeInt(min = 32, max = 400)
        public int distantLandmarkSampleSpacing = 100;

        @Config.Comment("Maximum number of distant landmarks mentioned in one conversation's context, so the AI isn't overloaded with facts.")
        @Config.RangeInt(min = 0, max = 32)
        public int distantLandmarkMaxCount = 6;

        @Config.Comment("How far around a traveller (in blocks) it notices animals, other people and other travellers worth mentioning in conversation.")
        @Config.RangeInt(min = 8, max = 64)
        public int nearbyEntityRadius = 20;

        @Config.Comment("Maximum number of different animal types mentioned in one conversation's context, so the AI isn't overloaded with facts.")
        @Config.RangeInt(min = 0, max = 12)
        public int nearbyAnimalMaxTypes = 5;

        @Config.Comment("If a player turns on tool use (Advanced settings), how many times in a row the NPC can look something up before it just answers with what it has. Stops a misbehaving model from looking things up forever.")
        @Config.RangeInt(min = 1, max = 10)
        public int maxToolRoundsPerTurn = 3;

        @Config.Comment("Lets travellers mention their own trade or role (farmer, merchant...) in conversation.")
        public boolean occupationLabelEnabled = true;

        @Config.Comment("Gives each traveller a fixed, consistent reason for being on the road (trade run, pilgrimage, heading home...) that it can explain if asked.")
        public boolean travelMotiveEnabled = true;

        @Config.Comment("Lets travellers remember things about their whole life - not just what one specific player told them - across separate conversations. Off by default.")
        public boolean memoryEnabled = true;

        @Config.Comment("How many recent things about one player a traveller keeps readily in mind, without needing to think back.")
        @Config.RangeInt(min = 1, max = 20)
        public int memoryIndexCap = 5;

        @Config.Comment("How many older things about one player a traveller can still recall if asked, before the oldest ones are forgotten for good.")
        @Config.RangeInt(min = 1, max = 100)
        public int memoryArchiveCap = 20;

        @Config.Comment("How many things a traveller tries to remember from one conversation, once it ends.")
        @Config.RangeInt(min = 1, max = 5)
        public int memoryFactsPerSession = 5;

        @Config.Comment("Lets a traveller also keep a short, evolving summary of its whole life, rewritten after every conversation. Independent of the fact list above.")
        public boolean memorySummaryEnabled = true;

        @Config.Comment("Maximum length (in characters) of a traveller's life summary. Longer results are simply cut short.")
        @Config.RangeInt(min = 100, max = 4000)
        public int memorySummaryMaxChars = 800;

        @Config.Comment("Minimum real time (in minutes) that must pass between two messages in the same conversation before the traveller notices the gap.")
        @Config.RangeInt(min = 1, max = 60)
        public int messageGapMinMinutesToMention = 2;
    }
}
