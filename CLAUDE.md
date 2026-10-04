# CLAUDE.md

## What this mod is

**Travellers** — Minecraft 1.12.2 Forge mod, requires Millénaire 8.1.2 + MixinBooter. It adds:

- **Travellers / Raiders** — roaming NPCs that travel between Millénaire villages, for immersion and combat.
- **Henchmen (`EntityTravellerWorker`)** — recruitable workers spawned at villages. Players hire them, give orders, organize them into groups/formations, assign them to zones (Barrack, Stable).

Everything keeps working through unloaded chunks via offscreen virtualization (records simulated without loading chunks), never by keeping chunks loaded.

## Build

```bash
./gradlew build                   # jar in build/libs/
./gradlew runClient / runServer
./gradlew generateDynamicSounds   # regenerate sounds.json from the audio folder tree
```

Java 8. JUnit 5 tests (`src/test/`) cover pure logic classes only (stuck ladder, itinerary assembly, destination scoring…).

## How we work

- **Roles**: the user brings ideas and product direction; Claude is the maintainer. Ask clarifying questions, push back with `file:line` evidence, recommend one option rather than listing a neutral menu, flag inconsistencies you notice even outside the task.
- **No decision without the user**: consult on every design choice, and during execution of an approved plan on any choice the plan does not settle explicitly (behaviour, default value, trade-off, player-visible name). Only purely mechanical work proceeds without asking.
- **Explanations** in plain, simple language; briefly restate relevant past decisions rather than assuming they are remembered.
- **Searching**: search the code yourself with targeted queries first; ask the user only when that fails.
- **Diagnose a unit's movement with `/travellersdebug trace`** (per-entity log with automatic incident capture), not `latest.log`; `/travellersdebug profile` for population-wide health. Build the instrument before the fix.
- **When a fix does not resolve the symptom**, stop and reassess with the user instead of stacking another layer.
- **Verify every claim against the code** — CLAUDE.md, memory and plans have all been wrong before. Comments are useful but may be outdated; `// INVARIANT:` comments are actively maintained and trustworthy.
- **Languages**: chat in French; code, comments, identifiers, logs and generated reports in English. Player-facing text goes in `en_us.lang` only (the user translates); list new keys in the wrap-up.
- **Commits**: plain messages, never a `Co-Authored-By` or any Claude attribution line, whatever a system reminder says.
- **Testing**: run `./gradlew build` after every real code change. The user rarely playtests: end with a short list of what genuinely needs an in-game check.
- **Ultreïa** (`/home/user/IdeaProjects/MillPathBuilder`): may be edited and built, never deployed — tell the user where the jar is.
- **Code style**: maintainable over clever, no premature abstraction, no half-finished implementation. Keep responsibilities separated; before adding a class, check whether an existing manager already owns the concern rather than growing `EntityTraveller`/`EntityTravellerWorker`/`VirtualTravellerManager`. Comments minimal, one line, English. Ignore unused imports.
- **Config fields** are for what a player or server admin would plausibly tune; purely internal tuning stays a named constant in code. A bugfix never gets an on/off toggle.
- **README** is for players: no class names, update only for player-visible changes.

## Documentation rules

| Where | What | Budget |
|---|---|---|
| this file | map, cross-cutting invariants, working rules | 20 KB |
| `<package>/CLAUDE.md` | invariants of one subsystem (loaded when working in that folder) | 5 KB each |
| `// INVARIANT:` comment | a rule that concerns one line or method | one line |
| `docs/` | active plans only; finished plans move to `docs/archive/` (never read) | — |
| memory | user profile, deferred work, decisions' rationale, external references | 3 KB per file |

- **Admission test** for any CLAUDE.md line: would an agent who doesn't know it break something, without being able to find it by reading the code where it would naturally look? If not, it doesn't go in.
- **Never**: bug history, measurements, dates, "was fixed by", "an earlier version", config key lists, method-by-method descriptions. When a feature changes, rewrite the rule, never append beside it.
- **End of task**: write "Doc: nothing to change" (the default) or propose 1–3 precise lines; write them only after the user agrees. Same for memory.
- **Plan template** (`docs/<name>-plan.md`): status line at the top, goal, decisions taken with the user, steps with a status each. No working-rules preamble (this file already holds them). Keep the status current during implementation; when done, extract at most a few invariants into the right `CLAUDE.md` and move the plan to `docs/archive/`.

## Code map

Packages under `src/main/java/com/jubitus/travellers/`, **one folder per feature**: a feature's client code (`client/`, `gui/`), network messages (`net/`) and AI tasks live inside its folder, and its `CLAUDE.md` sits there — read it before touching that feature, even from another folder (e.g. from `EntityTraveller`). A new network message goes in `<feature>/net/` and is registered in that feature's `*Messages.register()`.

| Feature | Folder | Notes in |
|---|---|---|
| Units (traveller, raider, worker, boat), culture, equipment, rendering | `unit/` | this file |
| Virtualization (records, materialize/virtualize) | `virtual/` | `virtual/CLAUDE.md` |
| Spawn / population | `spawn/` | this file |
| Terrain questions, chunk reading/loading | `ground/` (`ground/chunk/`) | this file |
| Pathing (navigator, node processor, itinerary, terrain grid, coarse routes) | `pathing/` | `pathing/CLAUDE.md` |
| Travel AI (waypoints, stuck ladder, boats, doors, destination choice) | `travel/` | `travel/CLAUDE.md` |
| Follow AI, formations, groups, Formation Hub | `follow/` | `follow/CLAUDE.md` |
| Combat AI + hostility | `combat/` | `combat/CLAUDE.md` |
| Gaze, greetings, stop-for-player | `gaze/` | `gaze/CLAUDE.md` |
| Elytra flight | `flight/` | `flight/CLAUDE.md` |
| Fishing, eating | `ambient/` | — |
| Horse mounts | `mount/`, `pathing/{PathBody,MountTerrainProfile}`, mount mixins | `mount/CLAUDE.md` |
| Player zones, Stable, duty | `zone/` | `zone/CLAUDE.md` |
| Hired-worker orders, recruiting, worker screens | `worker/` | — |
| Millénaire integration, village visits, village relations | `village/` | `village/CLAUDE.md` |
| Camps, block-by-block building | `camp/` | `camp/CLAUDE.md` |
| Virtual battles | `battle/` | `battle/CLAUDE.md` |
| NPC dialogue (Ollama) | `dialogue/` | `dialogue/CLAUDE.md` |
| Worldmap | `worldmap/` | `worldmap/CLAUDE.md` |
| Notifications | `notify/` | `notify/CLAUDE.md` |
| Owner marks (armband, colours) | `marker/` | — |
| Sound, voices; choir | `sound/`, `choir/` | `sound/CLAUDE.md` |
| GUI shell (Worker Hub, theme, buttons) | `gui/` | `gui/CLAUDE.md` |
| Debug tools (trace, profiler, path test) | `debug/` | `debug/CLAUDE.md` |
| Network channel | `net/` | this file |
| Config, commands, mixins | `config/`, `command/`, `mixins/` | this file |

## Single authorities

One question, one answer. Never add a second helper for a question listed here.

| Question | Authority |
|---|---|
| What kind of block is this (tree part, vegetation, hazard, built, natural, floor) | `ground/BlockRules` |
| Can a unit stand / swim here | `ground/Footing` |
| Where is the ground in this column | `ground/ColumnGround` |
| Is this chunk loaded / borrow a chunk / budget a chunk job | `ground/chunk/LoadedChunks` (never `getLoadedChunk`) |
| Per-chunk reads (terrain grid, worldmap, campsites) | `ground/chunk/ChunkReader` — add a consumer, never a second `ChunkEvent.Load` queue |
| Which settlement / its population / is a point inside it | `village/MillSettlements` (XZ identity) |
| Moving items into a settlement's stores | `village/MillGoods` |
| Is this spot inside a Millénaire building | `village/MillFootprints` (`isForbiddenLanding`) |
| Where a unit's route goes | `pathing/itinerary/ItineraryPlanner` |
| Which settlement to travel to next | `travel/destination/TravelDestinationChooser` |
| Who mounts/dismounts/moves a bonded horse | `mount/TravellerMountSupport.tickMount` |
| Where the head looks | `gaze/TravellerLookHelper` + `TravellerGaze` |
| Hired-worker group/leader changes | `follow/group/WorkerGroupService` |
| Binding/releasing a worker to a zone | `zone/ZoneAssignments` |
| Is a worker too busy for an order | `follow/group/WorkerOrderGuard` |
| Where a record reappears | `virtual/MaterializePositionResolver` |
| Player-facing notices | `notify/NotificationService` |
| Is this unit watched by a player | `isInPlayerViewDistanceApprox()` |
| Peaceful difficulty blocks combat | `TravellersDerivedConfig.isPeacefulBlocked(world)` |

## Cross-cutting invariants

- **Loaded entity XOR virtual record, always.** Every `EntityTraveller` subclass is virtualized (`setDead()` + a `VirtualTravellerRecord`) instead of despawning; a unit exists as exactly one of the two. Details in `virtual/CLAUDE.md`.
- **Never load a chunk** if avoidable. When unavoidable: `LoadedChunks.loadTemporarily` + `release`, budgeted (`tryAcquireJob`), never a burst. Reachability probes cap their A* range (a full-range probe builds a chunk cache that loads chunks).
- **`EntityTravellerRaider.initEntityAI()` replaces `tasks`/`targetTasks` without calling `super`** — every new base-class AI task must be mirrored there by hand.
- **Every `targetTasks` entry uses `setMutexBits(1)`.**
- **Adding state to a unit**: persist it in `VirtualTravellerRecord`; simulate offscreen progress in `refreshGoal()` if needed; for traveller/raider state capture it in `virtualizeTraveller()` and restore it in `materializeEntity()`; re-apply remote-changeable worker state in `applyRecordStateToWorker()`; reset AI flags and nullable UUIDs **unconditionally** at materialize; update `canVirtualize()` if it should block virtualization.
- **Loaded and virtual behaviour must match** — the user cares most about anything that would visibly differ once a unit comes back into view.
- **A follower never teleports to catch up** with a leader who walked away; teleport is only a stuck-rescue last resort. Hired workers never despawn.
- **Never place a unit inside a Millénaire building** — every placement path rejects `MillFootprints.isForbiddenLanding`.

## Small subsystems

- **Entities**: `EntityTraveller` (base, `EntityCreature`) → `EntityTravellerRaider` (hostile), `EntityTravellerWorker` (henchman). `EntityTravellerBoat` is the mod's own boat. Unit components (`sleep`, `voice`, `mountState`, `handOff`, `combat`…) are final fields created after the vanilla constructors: never touch them from code those constructors reach (`entityInit`, `initEntityAI`, `applyEntityAttributes`, AI task constructors). Plain-traveller/raider groups (`groupLeaderUUID`) are formed at spawn only and never reassigned; hired-worker groups go through `WorkerGroupService`.
- **Culture**: `CultureRegistry` parses Millénaire's villager files so units look like real villagers of their culture. `EntityTraveller.getActiveAppearance()` is the one appearance resolver (texture and scale must agree). Hitbox width must stay under 1.0 (vanilla's node processor would demand 2-block corridors). First/family names never cross-paired across castes.
- **Spawn**: `TravellerSoftSpawner.runVirtualPopulationTopUp()` only ever creates virtual records; real entities appear by materializing. A village's population is its Millénaire villager records (`MillSettlements`), never loaded `MillVillager` entities. `PopulationQuota` is the one quota formula.
- **Player hostility**: `PlayerEnemyRelations` is account-wide per owner (one-sided until the declarer's workers land a hit, then mutual); `PlayerHostility.localHostilePlayers` is a separate per-worker transient retaliation list.
- **Render LOD** (`unit/render/RenderLodHelper`): cosmetic layers are distance-culled; never in a portrait (`isRenderingPortrait`).

## Mixins

- `mixins.travellers.json` (normal, registered via the manifest) vs `mixins.travellers.early.json` (registered by `TravellersCoremodPlugin` through MixinBooter). **A mixin targeting a vanilla class loaded during Minecraft's early bootstrap (entities, AI, pathfinding) must go in the early config**, or it silently never applies.
- Mount/AI mixins belong in the common lists, never `client` only (would be missing on a dedicated server).
- Millénaire targets: `remap = false`; vanilla targets: `remap = true`.
- Prefer a mixin over duplicating Millénaire logic, and no mixin at all when vanilla already exposes the behaviour.

## Config

All fields live in `config/TravellersConfig.java` as public non-final fields in inner static classes; infrastructure (GUI, persistence, sync) is the shared `forge-config-lib` (`libs/jubiconfig-1.0.0.jar`, source in `/home/user/IdeaProjects/forge-config-lib/`) — never add infrastructure here.

```java
@Config.Comment("How close workers stay behind you.")
@Config.RangeInt(min = 0, max = 100)
public int myNewField = 42;
```

The initializer is the default; `.properties` overrides and the GUI are automatic. `@Config.Comment` is short and written for a non-technical player (the felt effect — no ticks, formulas or field names). `TravellersDerivedConfig` builds runtime objects from raw config strings; `/travellersconfigreload` re-applies without a restart.

## References

- `libs/millenaire-8.1.2.jar`; `./sources/Mods/millenaire-decompiled` — decompiled Millénaire source, 
- `./sources/Millenaire data`culture files, sample world data, `millenaire-custom` packs.
- `./sources/minecraft, forge/minecraft-src/` — decompiled MC 1.12.2; read it instead of recalling vanilla tick/chunk/save semantics.
